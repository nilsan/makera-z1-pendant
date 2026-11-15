"""Makera Z1 simulator: speaks the stock wire protocol so the pendant app can be tested with the
machine off. Not a faithful Smoothieware: just enough G-code for jogging, zeroing and macros.

  python z1sim.py [--name Makera_Z1P_SIM] [--port 2222] [--latency-ms 0] [--no-broadcast]

  * UDP broadcast "name,ip,port,busy,version" to 255.255.255.255:3333 once a second.
  * TCP 2222, several clients at once. Every frame is logged.
  * '?' -> 0x81 status, '!' hold, '~' resume, 0x18 halt (-> Alarm, needs $X), 0x19 stop (flush queue).
  * Jobs: `play <name>` plays <--sd>/<name> with |P:line,percent,seconds| in the status, ~4 s in
    state Tool for each M6, and frame 0xB7 answering "path|md5" of the playing file.
  * Lines: G0/G1 (G90/G91, F), G4, G10 L20 P0, G92, G53, $H, $X, M220 S, M3/M5 S, M6 T, M999, anything
    else just gets "ok". Replies are 0x90 "ok\\r\\n".
  * Motion runs in real time at the commanded feed (G0 = axis max rate) x feed override, with a
    32-block planner queue; soft limits X -207..-1, Y -206..-1, Z -102..-1 once homed;
    $H ends at MPos -1 on X, Y and Z, like the real Z1, and like it answers "ok" only once homing is
    done (the slow switch touch and back-off take ~2 s more here; ~3.9 s in all from home on the Z1).
    A halt (0x18) during homing: "ALARM: Abort during cycle", "ALARM: Homing fail", then the $H's "ok".
"""
from __future__ import annotations

import argparse
import hashlib
import math
import os
import re
import socket
import threading
import time
from collections import deque

import z1proto as P

AXES = "XYZA"
MAX_RATE = {"X": 3000.0, "Y": 3000.0, "Z": 1000.0, "A": 1800.0}   # mm/min (deg/min for A)
SOFT = {"X": (-207.0, -1.0), "Y": (-206.0, -1.0), "Z": (-102.0, -1.0)}
QUEUE_MAX = 32
HOME_TOUCH_S = 2.0      # homing's slow switch touch and back-off, after the fast moves
QUEUE_DELAY_S = 0.100   # Smoothie conveyor queue_delay_time_ms default: a queue that was empty waits this long


def log(msg):
    print(f"{time.strftime('%H:%M:%S')}.{int(time.time() * 1000) % 1000:03d} {msg}", flush=True)


class Machine:
    def __init__(self):
        self.lock = threading.RLock()
        self.mpos = [-100.0, -100.0, -50.0, 0.0]
        self.wco = [0.0, 0.0, 0.0, 0.0]          # work offset: wpos = mpos - wco
        self.absolute = True
        self.feed = 500.0
        self.fro = 100.0
        self.spindle_target = 10000.0          # the real Z1 reports its default target while off
        self.spindle_factor = 100.0            # M223 spindle override, %
        self.spindle_on = False
        self.spindle_cur = 0.0
        self.spindle_at = time.monotonic()
        self.tool = 1                          # active tool (M6 T…); 0 = none: M3 then halts, like the Z1
        self.light = True
        self.vacuum = False
        self.state = "Idle"
        self.homed = False
        self.hold = False
        self.queue: deque = deque()               # each: (target list, feed mm/min) or ("dwell", s)
        self.cur = None
        self.msgs: list[str] = []                 # pushed to clients as 0x90
        self.wait_since = None                    # when the first block arrived in an empty queue
        self.starts = 0                           # restarts from an empty queue (stop-go count)
        self.job = None                           # a playing file (see play()); gives |P: in the status
        self.feed_line = 0                        # file line of the next queued move (job playback)
        self.tool_target = -1                     # |T: field 3: the tool a tool change asks for
        self.tool_wait = False                    # waiting for the user to swap the tool (state "Tool")
        self.sd = "sim_sd"                        # folder standing in for the SD card (/sd/gcodes/<name>)

    # --- G-code -------------------------------------------------------------------------
    def line(self, text: str) -> list[str]:
        t = text.strip().upper().replace(" ", "")
        if not t:
            return ["ok\r\n"]
        if t == "$H":
            return self._home_cmd()
        with self.lock:
            if t.startswith("PLAY"):
                return self.play(text.strip()[4:].strip())
            if t in ("$X", "M999"):
                if self.state == "Alarm":
                    self.state = "Idle"
                return ["ok\r\n"]
            if self.state == "Alarm":
                return ["error:Alarm lock\r\n"]
            words = re.findall(r"([A-Z])([-+]?\d*\.?\d*)", t)
            g = [float(v) for k, v in words if k == "G" and v]
            m = [float(v) for k, v in words if k == "M" and v]
            vals = {}
            for k, v in words:
                if k not in "GM" and v not in ("", "+", "-", "."):
                    vals[k] = float(v)
            if 90 in g:
                self.absolute = True
            if 91 in g:
                self.absolute = False
            if "F" in vals and not (10 in g or 4 in g):
                self.feed = vals["F"]
            if 223 in m and "S" in vals:
                self._spin()
                self.spindle_factor = max(50.0, min(200.0, vals["S"]))   # stock Z1 clamp
                return ["ok\r\n"]
            if 220 in m and "S" in vals:
                self.fro = max(10.0, min(300.0, vals["S"]))
                return ["ok\r\n"]
            if 821 in m:
                self.light = True
            if 822 in m:
                self.light = False
            if 851 in m:
                self.vacuum = True
            if 852 in m:
                self.vacuum = False
            if 6 in m and "T" in vals:
                self.tool = int(vals["T"])
            if 493.2 in m and "T" in vals:          # set current tool, no motion
                self.tool = int(vals["T"])
            if 3 in m or 4 in m:
                if self.tool <= 0 or self.tool >= 100000:
                    self.halt()
                    return ["ERROR: No tool or probe tool!\r\n"]
                self._spin()
                self.spindle_target = vals.get("S", self.spindle_target)
                self.spindle_on = True
            if 5 in m:
                self._spin()
                self.spindle_on = False
            if 10 in g and vals.get("L") == 20:
                for i, ax in enumerate(AXES):
                    if ax in vals:
                        self.wco[i] = self._end_pos()[i] - vals[ax]
                return ["ok\r\n"]
            if 92 in g:
                for i, ax in enumerate(AXES):
                    if ax in vals:
                        self.wco[i] = self._end_pos()[i] - vals[ax]
                return ["ok\r\n"]
            if 4 in g:
                self.queue.append(("dwell", vals.get("P", 0) / 1000.0 if "P" in vals else vals.get("S", 0)))
                return ["ok\r\n"]
            if any(ax in vals for ax in AXES) and (0 in g or 1 in g or not g or 53 in g or 90 in g or 91 in g):
                start = self._end_pos()
                tgt = list(start)
                for i, ax in enumerate(AXES):
                    if ax in vals:
                        if 53 in g:
                            tgt[i] = vals[ax]
                        elif self.absolute:
                            tgt[i] = vals[ax] + self.wco[i]
                        else:
                            tgt[i] = start[i] + vals[ax]
                if self.homed:
                    for i, ax in enumerate("XYZ"):
                        lo, hi = SOFT[ax]
                        if not (lo - 1e-6 <= tgt[i] <= hi + 1e-6):
                            self._alarm(f"Soft Endstop {ax} was exceeded - reset or $X or M999 required")
                            return ["error:Soft Endstop\r\n"]
                rapid = 0 in g and 1 not in g
                while len(self.queue) >= QUEUE_MAX:          # firmware blocks before 'ok' when full
                    self.lock.release()
                    time.sleep(0.01)
                    self.lock.acquire()
                self.queue.append((tgt, None if rapid else self.feed, self.feed_line))
                return ["ok\r\n"]
            return ["ok\r\n"]

    # --- jobs ------------------------------------------------------------------------------
    def sd_path(self, path: str) -> str:
        return os.path.join(self.sd, os.path.basename(path))

    def play(self, path: str) -> list[str]:
        """Play a file like the firmware's player: lines go to the planner in order, |P: reports
        line / percent of the file read / seconds run, M6 waits ~4 s in state Tool (standing in for
        the user's OK), and after the end |P: shows 100 % for about a second."""
        local = self.sd_path(path)
        if self.job or not os.path.isfile(local):
            return [f"error: can't play {path}\r\n"]
        data = open(local, "rb").read()
        sd = "/sd/gcodes/" + os.path.basename(path)
        self.job = {"path": sd, "md5": hashlib.md5(data).hexdigest(), "size": len(data), "read": 0,
                    "line": 0, "start": time.time(), "paused": 0.0, "done_at": None}
        threading.Thread(target=self._player, args=(data.decode("utf-8", "replace").splitlines(keepends=True),),
                         daemon=True).start()
        return [f"Playing {sd}\r\n", "ok\r\n"]

    def _wait_queue(self, empty=False):
        while True:
            with self.lock:
                if self.state == "Alarm" or not self.job:
                    return False
                if not self.hold and (len(self.queue) < QUEUE_MAX - 2 if not empty else (not self.queue and self.cur is None)):
                    return True
            time.sleep(0.01)

    def _player(self, lines):
        j = self.job
        for n, raw in enumerate(lines, 1):
            j["read"] += len(raw.encode())
            code = re.sub(r"\([^)]*\)", "", raw.split(";")[0]).strip().upper().replace(" ", "")
            if not code:
                continue
            if not self._wait_queue():
                break
            self.feed_line = n
            m6 = re.search(r"(?<![A-Z])M0*6(?![0-9.])", code)
            tm = re.search(r"T(\d+)", code)
            if m6 and tm and int(tm.group(1)) != self.tool:
                if not self._wait_queue(empty=True):
                    break
                j["line"] = n
                with self.lock:                     # the swap: Tool state, collet empty, then the new tool
                    self.tool_target = int(tm.group(1)); self.tool_wait = True
                    self.spindle_on = False
                t0 = time.time()
                time.sleep(1.0)
                with self.lock:
                    self.tool = -1
                time.sleep(3.0)
                with self.lock:
                    self.tool = self.tool_target; self.tool_wait = False
                j["paused"] += time.time() - t0
                continue
            if re.fullmatch(r"M0*(2|30)", code):
                break
            self.line(raw)
        self._wait_queue(empty=True)
        with self.lock:
            if self.job is j:
                j["read"] = j["size"]; j["done_at"] = time.time()

    def job_field(self) -> str:
        j = self.job
        if not j:
            return ""
        if j["done_at"] and time.time() - j["done_at"] > 1.2:
            self.job = None
            return ""
        el = int((j["done_at"] or time.time()) - j["start"] - j["paused"])
        return f"|P:{j['line']},{j['read'] * 100 // max(1, j['size'])},{el}"

    def _end_pos(self):
        for item in reversed(self.queue):
            if item[0] != "dwell":
                return list(item[0])
        if self.cur and self.cur[0] != "dwell":
            return list(self.cur[0])
        return list(self.mpos)

    def _alarm(self, msg):
        self.queue.clear()
        self.cur = None
        self.state = "Alarm"
        self.msgs.append(f"ALARM: {msg}\r\n")

    def halt(self):
        with self.lock:
            self._alarm("Abort during cycle")

    def stop(self):
        with self.lock:
            self.queue.clear()
            self.cur = None

    def _home_cmd(self) -> list[str]:
        with self.lock:
            if self.state == "Alarm":
                return ["error:Alarm lock\r\n"]
            self.queue.clear()
            self.cur = None
            self.state = "Home"
            threading.Thread(target=self._home, daemon=True).start()
        # The Z1 answers $H when homing is over (lines after it wait), also when a halt cut it short.
        while True:
            time.sleep(0.01)
            with self.lock:
                if self.state != "Home":
                    return ["ALARM: Homing fail\n", "ok\r\n"] if self.state == "Alarm" else ["ok\r\n"]

    def _home(self):
        while True:
            with self.lock:
                if self.state != "Home":
                    return
                done = True
                for i, ax in enumerate("XYZ"):
                    # The real Z1 homes to MPos -1.000 (the soft max); 0 would be past it,
                    # so the next move on any axis would trip the soft limit.
                    home = SOFT[ax][1]
                    step = 3000 / 60 * 0.01
                    if self.mpos[i] != home:
                        d = home - self.mpos[i]
                        self.mpos[i] = home if abs(d) <= step else self.mpos[i] + math.copysign(step, d)
                        done = False
                if done:
                    break
            time.sleep(0.01)
        end = time.time() + HOME_TOUCH_S
        while time.time() < end:
            time.sleep(0.01)
            with self.lock:
                if self.state != "Home":
                    return
        with self.lock:
            if self.state == "Home":
                self.homed = True
                self.state = "Idle"

    # --- motion thread ------------------------------------------------------------------
    def run(self):
        dt = 0.005
        dwell_end = 0.0
        while True:
            time.sleep(dt)
            with self.lock:
                if self.state in ("Alarm", "Home"):
                    continue
                if self.tool_wait:
                    self.state = "Tool"
                    continue
                if self.hold:
                    self.state = "Hold"
                    continue
                if self.cur is None and self.queue:
                    if self.state != "Run":
                        # empty -> non-empty: Smoothie holds off QUEUE_DELAY_S unless the queue fills
                        if self.wait_since is None:
                            self.wait_since = time.time()
                        if time.time() - self.wait_since < QUEUE_DELAY_S and len(self.queue) < QUEUE_MAX:
                            continue
                        self.wait_since = None
                        self.starts += 1
                        log(f"START #{self.starts} (queue {len(self.queue)})")
                    self.cur = self.queue.popleft()
                    if self.cur[0] == "dwell":
                        dwell_end = time.time() + self.cur[1]
                if self.cur is None:
                    self.state = "Idle"
                    continue
                self.state = "Run"
                if self.cur[0] == "dwell":
                    if time.time() >= dwell_end:
                        self.cur = None
                    continue
                tgt, feed = self.cur[0], self.cur[1]
                if len(self.cur) > 2 and self.job:
                    self.job["line"] = self.cur[2]
                d = [tgt[i] - self.mpos[i] for i in range(4)]
                dist = math.sqrt(sum(x * x for x in d))
                if dist < 1e-6:
                    self.mpos = list(tgt)
                    self.cur = None
                    continue
                if feed is None:
                    feed = min(MAX_RATE[AXES[i]] for i in range(4) if abs(d[i]) > 1e-9)
                else:
                    feed = feed * self.fro / 100.0
                    feed = min([feed] + [MAX_RATE[AXES[i]] for i in range(4) if abs(d[i]) > 1e-9])
                step = feed / 60.0 * dt
                if step >= dist:
                    self.mpos = list(tgt)
                    self.cur = None
                else:
                    self.mpos = [self.mpos[i] + d[i] / dist * step for i in range(4)]

    def _spin(self):
        """Ramp the measured RPM toward the target (on) or 0 (off), about 3000 RPM/s."""
        now = time.monotonic()
        goal = min(13000.0, self.spindle_target * self.spindle_factor / 100) if self.spindle_on else 0.0
        step = 3000.0 * (now - self.spindle_at)
        self.spindle_at = now
        d = goal - self.spindle_cur
        self.spindle_cur = goal if abs(d) <= step else self.spindle_cur + math.copysign(step, d)

    def status(self) -> str:
        with self.lock:
            self._spin()
            mp = self.mpos
            wp = [mp[i] - self.wco[i] for i in range(4)]
            f = self.cur[1] if self.cur and self.cur[0] != "dwell" and self.cur[1] else 0.0
            return (f"<{self.state}|MPos:{mp[0]:.4f},{mp[1]:.4f},{mp[2]:.4f},{mp[3]:.4f},0.0000"
                    f"|WPos:{wp[0]:.4f},{wp[1]:.4f},{wp[2]:.4f},{wp[3]:.4f},0.0000"
                    f"|F:{f:.1f},{self.feed:.1f},{self.fro:.1f}|S:{self.spindle_cur:.1f},{self.spindle_target:.1f},{self.spindle_factor:.1f},0"
                    f"|T:{self.tool},-7.100,{self.tool_target}{self.job_field()}|W:0.00>")


class Client(threading.Thread):
    def __init__(self, sock, addr, machine: Machine, latency: float):
        super().__init__(daemon=True)
        self.sock, self.addr, self.m, self.latency = sock, addr, machine, latency
        self.dec = P.Decoder()
        self.wlock = threading.Lock()
        self.lines: deque = deque()
        self.ev = threading.Event()

    def tx(self, ptype, text: str):
        if self.latency:
            time.sleep(self.latency)
        with self.wlock:
            try:
                self.sock.sendall(P.build(ptype, text.encode()))
            except OSError:
                pass

    dl_owner = None                               # one download at a time, across clients

    def download(self, ptype, payload):
        """0xB0 "download <path>\\n" -> 0xB1 md5; 0xB2 -> u32 count + u32 8192; 0xB3 u32 seq -> seq + data;
        0xB4 -> "ok"; 0xB5 cancel. Like the board: one owner, 8 KB packets, any order."""
        cls = Client
        if ptype == 0xB0:
            if cls.dl_owner not in (None, self):
                self.tx(0x91, "Other client is currently uploading/downloading, please wait!")
                return
            path = payload.decode("ascii", "replace").strip().replace("\x01", " ")
            path = path[len("download"):].strip() if path.startswith("download") else path
            local = self.m.sd_path(path)
            try:
                self.dl_data = open(local, "rb").read()
            except OSError:
                self.tx(0xB5, "Error: failed to open file!")
                return
            cls.dl_owner = self
            self.tx(0xB1, hashlib.md5(self.dl_data).hexdigest())
            return
        if cls.dl_owner is not self:
            return
        if ptype == 0xB2:
            n = (len(self.dl_data) + 8191) // 8192
            self.txb(0xB2, n.to_bytes(4, "big") + (8192).to_bytes(4, "big"))
        elif ptype == 0xB3:
            seq = int.from_bytes(payload[:4], "big")
            chunk = self.dl_data[(seq - 1) * 8192: seq * 8192]
            if seq < 1 or not chunk:
                self.tx(0xB5, "Machine received cmd timeout!")
            else:
                self.txb(0xB3, payload[:4] + chunk)
        elif ptype == 0xB4:
            cls.dl_owner = None
            self.tx(0xB4, "ok\r\n")
            self.tx(P.NORMAL_INFO, "Info: download success.\r\n")
        elif ptype == 0xB5:
            cls.dl_owner = None
            self.tx(0xB5, "Info: canceled by remote!")

    def txb(self, ptype, data: bytes):
        with self.wlock:
            try:
                self.sock.sendall(P.build(ptype, data))
            except OSError:
                pass

    def worker(self):
        # G-code lines are handled in order on their own thread (like the firmware's main loop),
        # so a full planner queue delays 'ok' without blocking real-time bytes.
        while True:
            self.ev.wait()
            self.ev.clear()
            while self.lines:
                line = self.lines.popleft()
                for r in self.m.line(line):
                    self.tx(P.NORMAL_INFO, r)

    def run(self):
        log(f"client {self.addr} connected")
        threading.Thread(target=self.worker, daemon=True).start()
        last_ver = 0.0
        self.sock.settimeout(0.1)
        while True:
            if time.time() - last_ver > 5:
                self.tx(P.FIRM_VER, "SIM-1.0")
                last_ver = time.time()
            while self.m.msgs:
                self.tx(P.NORMAL_INFO, self.m.msgs.pop(0))
            try:
                data = self.sock.recv(4096)
            except socket.timeout:
                continue
            except OSError:
                break
            if not data:
                break
            for ptype, payload in self.dec.feed(data):
                if ptype == P.CTRL_SINGLE and payload:
                    c = payload[:1]
                    if c == b"?":
                        self.tx(P.STATUS_RES, self.m.status())
                        continue
                    log(f"{self.addr} RT {c!r}")
                    if c == P.RT_HOLD:
                        self.m.hold = True
                    elif c == P.RT_RESUME:
                        self.m.hold = False
                    elif c == P.RT_HALT:
                        self.m.halt()           # the Z1 says "ALARM: Abort during cycle" (sent from msgs)
                    elif c == P.RT_STOP:
                        self.m.stop()
                elif ptype == P.CTRL_MULTI and payload.strip() == b"diagnose":
                    # like the firmware: answered with 0x82, no "ok"
                    self.tx(P.DIAG_RES, "{S:0,10000,0,0,25,23|L:0,0|V:1,28|F:0,0|G:%d,0,0,%d,%d|T:0|C:1|E:0,0,0,0,0,1,1,0|P:0,0|I:0|RSSI:-60}"
                            % (1 if self.m.light else 0, 1 if self.m.vacuum else 0, 80 if self.m.vacuum else 0))
                elif 0xB0 <= ptype <= 0xB6:
                    self.download(ptype, payload)
                elif ptype == 0xB7:
                    # which file is playing: "path|md5", or "|" (answered by the Wi-Fi board)
                    j = self.m.job
                    self.tx(0xB7, f"{j['path']}|{j['md5']}" if j else "|")
                elif ptype == P.CTRL_MULTI:
                    line = payload.decode("ascii", "replace")
                    log(f"{self.addr} LINE {line!r}")
                    self.lines.append(line)
                    self.ev.set()
                else:
                    log(f"{self.addr} type 0x{ptype:02X} {payload!r}")
        if Client.dl_owner is self:
            Client.dl_owner = None
        log(f"client {self.addr} gone")
        self.sock.close()


def local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.168.1.1", 9))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def broadcaster(name, port):
    # Bind to the LAN address and send to its /24 broadcast: on a multi-homed host,
    # 255.255.255.255 leaves by whichever interface the routing table picks.
    ip = local_ip()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.bind((ip, 0))
    bcast = ip.rsplit(".", 1)[0] + ".255"
    while True:
        msg = f"{name},{ip},{port},0,SIM-1.0".encode()
        try:
            s.sendto(msg, (bcast, P.UDP_DISCOVERY_PORT))
        except OSError as e:
            log(f"broadcast failed: {e}")
        time.sleep(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default="Makera_Z1P_SIM")
    ap.add_argument("--port", type=int, default=P.TCP_PORT)
    ap.add_argument("--latency-ms", type=float, default=0)
    ap.add_argument("--no-broadcast", action="store_true")
    ap.add_argument("--sd", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "sim_sd"),
                    help="folder standing in for the SD card: 'play <name>' plays <sd>/<name>")
    a = ap.parse_args()
    m = Machine()
    m.sd = a.sd
    threading.Thread(target=m.run, daemon=True).start()
    if not a.no_broadcast:
        threading.Thread(target=broadcaster, args=(a.name, a.port), daemon=True).start()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", a.port))
    srv.listen(4)
    log(f"sim '{a.name}' on {local_ip()}:{a.port}")
    while True:
        sock, addr = srv.accept()
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        Client(sock, addr, m, a.latency_ms / 1000).start()


if __name__ == "__main__":
    main()
