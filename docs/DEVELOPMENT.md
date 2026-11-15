# Z1 Pendant: developer notes

How the app talks to the machine, how jogging works, and what has been confirmed on a real Z1 Pro.
For installing and using the app, see the [README](../README.md).

## Source layout
- `android/`: the Gradle project (Kotlin 2.2, Compose Multiplatform 1.9, minSdk 31). Build the
  Android app with `gradlew assembleRelease`.
  - `shared/`: Kotlin Multiplatform module with nearly all the code, for Android and iOS
    (`iosArm64`, `iosSimulatorArm64`; static framework `Shared`). In `src/commonMain/kotlin/com/z1pendant/`:
    - `link/MachineLink.kt`: the transport interface plus `Dialect` (backend-specific G-code).
      `link/makera/*` is the stock-firmware backend (TCP frames, discovery; sockets are ktor-network).
      Another controller (e.g. LinuxCNC) would implement the same interface.
    - `jog/JogEngine.kt`: turns dial/pad/volume clicks into short relative moves (below).
    - `hotkeys/*`: hotkey model, JSON import/export (kotlinx-serialization, same file format as before),
      runner with guards (ask first, Idle only).
    - `ui/*`: all the Compose UI: main screen, handwheel, jog pad, hotkey grid and editor, Move box,
      settings, console. `AppViewModel.kt`: state and actions.
    - `platform/Platform.kt`: what each phone supplies (preferences, hotkeys.json, haptics, clock,
      back button, hotkey file picker); `util/Fmt.kt`: number formatting identical to Java's.
  - `shared/src/androidMain`: SharedPreferences "z1pendant", filesDir/hotkeys.json, vibrator, file pickers.
  - `shared/src/iosMain`: NSUserDefaults, Documents/hotkeys.json, Taptic feedback, and
    `MainViewController()` for the iOS app. Not compiled on Windows; the Xcode project comes later on a Mac.
  - `shared/src/androidUnitTest`: formatter vs Java, hotkey JSON vs org.json, frames, and the link
    against `z1sim.py` (`gradlew :shared:testDebugUnitTest`).
  - `app/`: the Android shell: `MainActivity` (key events, Wi-Fi low-latency lock, keep-screen-on,
    adb test hooks), manifest, icon.
- `python/z1proto.py`: wire protocol (frames, CRC, status and discovery parsers) with a self-test.
- `python/z1client.py`: PC test client: `discover`, `probe` (read-only), `second` (two TCP clients
  at once), `send`, `jog`. Anything that can move the machine needs `--motion`.
- `python/z1sim.py`: machine simulator: discovery broadcast, TCP 2222, G0/G1/G90/G91/G10 L20/G53/$H/$X,
  M220, M821/M822, M851/M852, `diagnose`, hold/resume/halt, a 32-block queue with the firmware's 100 ms
  start delay, and soft limits X −207..−1, Y −206..−1, Z −102..−1 that alarm like the real machine.

## Protocol (Makera app decompile + community firmware `MakeraFrame.h`)
`86 68 | len u16 BE | type | payload | CRC u16 BE | 55 AA`, len = 1 + payload + 2,
CRC-16/XMODEM (poly 0x1021, **init 0**) over len + type + payload. Test vector: `86 68 00 03 71 3b e5 55 aa`.
- To the machine: `0xA1` one real-time byte (`?` status, `!` hold, `~` resume, `0x18` halt, `0x19` stop,
  `0x1A` keep-alive); `0xA2` one command line (no newline).
- From the machine: `0x81` status `<Idle|MPos:x,y,z,a,b|WPos:…|F:feed,set,override|S:…|…>`,
  `0x90` text (`ok`, errors, alarms), `0x82` diagnose reply, `0x71` firmware version.
- Discovery: the machine's ESP32 broadcasts `name,ip,port,busy,state` to **UDP 3333**.
- The pendant jogs with short moves to absolute machine positions, `G53 G1 X… F…`. G53 applies to its
  own line only, so a jog never changes the machine's modal state: no G91 is left behind if the link
  drops or the app dies mid-jog. (It used to send `G91 G1` and restore `G90` afterwards.)
- The stock firmware does have `$J X… F…` (relative, F = a fraction of the axis max rate), but it sends
  no `ok`, which the line flow control relies on, and it tops out near 1200 mm/min, so the app doesn't
  use it. See the measurements below.

## Jog engine
Every 40 ms the clicks collected become one `G53 G1` move to the next machine position. Feed = hand speed (clicks in the last
200 ms × step) plus a correction that keeps about 120 ms of motion queued:
`feed = vHand + (lead − vHand·0.12 s) / 0.3 s`, clamped to 5 mm/min … axis max.

Why the buffer: Smoothie holds a queue that was empty for `queue_delay_time_ms` (100 ms) before running
it. If the queue runs dry mid-turn, the machine stops and restarts 100 ms late, which feels laggy.

The **lead** (commanded but not yet travelled, dead-reckoned from the last status) plus waiting clicks
is capped at `max(min(5 clicks, 5 mm), v²/2a + v·tick + vHand·(0.12 + 0.1) + 1 click)`. Clicks beyond
that are dropped (one thud vibration per 150 ms at most), so when input stops or the link drops, the
axis stops within that distance. Moves are clamped to the soft limits while the machine is homed.

A **jog session** starts from the machine position in a status that arrived *after* the first click
(never an older reading, which could be stale after a Wi-Fi gap), and ends once the machine is Idle and
quiet for 0.3 s. During a session the reported position must stay on the commanded path (between the
start and the targets sent, ±0.02 for status rounding) and the other axes must stay put. If not,
something else moved the machine: the session ends (thud, clicks dropped) and the next click starts
over from a fresh reading.

Jog pad: a tap is one click; a held button repeats after 0.4 s at `max(120 ms, step / axis max speed)`,
and letting go drops any clicks not yet sent.

The tap's click is sent when the finger lifts, and the repeat runs on a fixed clock. Nothing is sent
on touch: the Z1 (Smoothie) plans a move that is alone in its queue to end at a standstill and never
re-plans a move once it is running (`Block::calculate_trapezoid` returns early `if (is_ticking)`), so a
step sent on touch made every hold stop after its first step.

While a button repeats, the hand speed is the exact repeat rate and the feed is a constant 97 % of it
(`JogConfig.holdFeedFactor`), with no lead correction. On a real Z1, any feed change from move to move
is audible, and a feed even a few % faster than the clicks arrive empties the queue, so the machine
stops for a moment and starts again. A little slower, the queue fills slowly up to the cap, where a
dropped click costs nothing because moves are still queued. With the lead correction, every jump in the
lead estimate went into the feed (F918, F704, F921, F665, … at the 1 mm step). The repeat runs on a fixed
clock because a delay after each click adds up (124 ms instead of 120 on a Galaxy S21), which made the
real supply slower than the feed.

The lead's dead reckoning (every jog, not only the pad) counts from when the reported position was
read, not from the latest status: the Z1 refreshes the position in its status only every 250–370 ms and
repeats it in between, so a position is as old as the first status that carried it (trusted up to
400 ms, `JogConfig.positionMaxAgeMs`; after that the machine is standing still). Counting from the
latest status made the lead look up to ~0.3 s of travel too long, so clicks were dropped at the cap
while the machine had little queued.

## Haptics
VibrationEffect composition primitives: TICK per detent, CLICK every 10th, THUD when clicks are dropped
or blocked, a double click at a soft limit. Strength is in Settings.
On Android 13+ they play as `USAGE_PHYSICAL_EMULATION` (vibrations that stand in for a hardware part,
here the handwheel's detents). Without attributes Android files short primitives as touch feedback, and
the phone's **Touch feedback** switch then silences them; many people turn that off to quiet the
keyboard. Android's master **Use vibration & haptics** switch still applies, and the strength slider
(0 = off) is the app's own off switch. Android 12 has no such usage and keeps the default (touch).
iOS (not tested yet): light / medium / heavy impacts for tick / every 10th / thud, two rigid knocks
for a soft limit, a selection click for button presses.

## Machine outputs
`diagnose` (sent as an `0xA2` line) returns an `0x82` frame
`{S:…|L:…|V:…|F:…|G:light,beep,extendin,extendout,extendout_pwm|T:…|C:…|E:…|P:…|I:…|RSSI:…}` with no `ok`.
G[0] = light (M821 on / M822 off), G[3] = vacuum / chip-clear output (`switch.extendout`, M851 on /
M852 off). The app reads it every 3 s (not while jogging) for the Light and Vacuum toggle tiles.

## Spindle
Start: `M3 S<rpm>`; stop: `M5`. The status line carries both things the tile needs, every poll:
`|S:current,target,override%,…|` (current RPM is live; target stays at the default 10000 while off)
and `|T:tool,offset,target tool,…|` (active tool). The firmware halts the machine on M3 unless
1 ≤ tool ≤ 999 on the stock Z1 (Makera `makeraz1firmware`; "ERROR: No tool or probe tool!"; the community firmware allows < 100000), so the
start box refuses first: tool −1 = empty collet, and the probe numbers 0, 9999 and ≥ 999990
(community `ATCHandler`: "a 3-axis probe must be installed (tool 0, 9999 or tool >= 999990)"; the
community docs only name 999990+ as the 3D probe, 9999 is undocumented). In that case the box shows a T1–T99 stepper (− / +, hold to run faster) and
**Set tool**, which sends `M493.2 T<n>` (no motion; Makera's app sends it too; saved by the firmware, tool
length marked not measured). Stopping always asks too: the only sign of a job is the Run state, which
doesn't say who started the spindle. Running = current RPM > 0, or an M3 sent in the last 10 s (spin-up). The Z1
spindle is 150 W, 0–13,000 RPM (Makera's spec); the config holds motion `delay_on_s 8` after M3 and
`delay_off_s 4` after M5.

Override slider (drawer): in RPM, full bar = 13,000. Release sends `M223 S<desired / programmed × 100>`
to 0.01 % (programmed = the status's target field; assumed to exclude the override, as in the community
`SpindleControl`, **unverified on stock**). Range: 50 % of programmed (Makera's slider floor) up to
13,000 or 200 %, whichever is lower: the stock firmware clamps M223 to 50–200 % (community: 10–300 %), same as Makera's slider.

## Controls drawer
The bottom drawer (feed override, Hold, Resume, Reset) opens by itself on Hold, Pause or Alarm, and when
the machine moves without the pendant having started it (e.g. a job from Makera Studio). It closes again
at Idle if it opened itself. `OwnMotion` tracks the pendant's own motion: jogs, typed moves, console lines and
hotkeys count from the moment they are sent until the machine has been busy and is Idle again (or 1.5 s if it
never starts). The firmware acks a line when it is queued, not when the move ends, so the ack can't be
used. It used to count a jog as the pendant's only for 1.5 s after the last click, and typed moves not at
all, so a long jog step or a typed move opened the drawer. The view model re-checks it once per status (`ownMotionActive`),
not while the screen redraws.

## Jobs (confirmed on the Z1 2026-11-15, log in the parent project's reference/)
- **A job is playing iff the status has `|P:line,percent,elapsed s|`** (stock: 3 fields). It isn't there
  for M3, jogs or lines we send; it stays through tool changes and pauses, and through the job's own
  Idle moments (state reads Idle around tool changes and for ~4 s at the end), so "Idle" doesn't
  mean done. On a normal finish it reads 100 % for ~1.2 s, then goes; after an abort it just goes.
  The Wi-Fi board has a canned fallback status with `P:1234,50,1200`: ignored.
- `line` = 1-based file line of the block running (comments count); `percent` = share of the file
  read (sits at ~91 % over the last lines, then 100); `elapsed` stops during a tool-change wait.
  The app shows line ÷ total lines (once the file is read) for the percent and time left: the
  firmware's % is of the file read, and it reads ahead, so it hit 100 % ~100 lines before the end
  of a 11,600-line engraving (tiny moves at 30 mm/min) and the time left went blank.
- On a disconnect the app forgets the job (it may end or change while we're away) and asks for
  the file again on reconnect. If it was watching a job and comes back to no job, that job ended:
  overrides go back to 100 % as at a normal end. Back into a running job, it leaves them alone.
- State `Tool` = waiting for a manual tool change; `|T:active,TLO,target|` (target = the tool asked
  for; active reads −1 while the collet is empty during the swap).
- The state reads `Run` while the spindle turns even with nothing moving, so a pendant M3 counts as
  its own motion (no drawer pop).
- **Which file:** an empty frame of type `0xB7` → `0xB7 "path|md5"` (`"|"` when none). The Wi-Fi
  board answers it; it never reaches the motion controller. The `progress` text command also works
  (`file: …, 21 % complete, elapsed time: …, est time: …`).
- **Download** (Wi-Fi board, stop-and-wait; full spec: parent project `reference/download_protocol_notes.md`):
  `0xB0 "download <path>\n"` (spaces as 0x01) → `0xB1 md5`; `0xB2` → u32 packet count + u32 8192;
  `0xB3 u32 seq` (from 1) → seq + up to 8192 bytes; `0xB4` → "ok"; `0xB5` cancels. One transfer at a
  time across clients (others get `0x91`); 9 s idle aborts it. A QuickLZ copy (`/sd/gcodes/.lz/`,
  starts `00 00`) isn't handled yet. The app downloads the playing file once per md5, paced 25 ms per
  packet, and reads its tools (`JobFileParser`: Fusion `(T1 name D=… - type)` and Makera
  `;@MKR|TOOL|number=…|name=…` headers; `M6` lines, skipping changes to the tool already in).
- Overrides live in RAM in the firmware (makeraz1firmware): M220 → `Robot::seconds_per_minute`, M223 →
  the spindle `factor`; both reply just `ok`, and the status shows them (`F:…,override`, `S:…,factor`).
  Nothing resets the spindle factor but `M223 S100` or a reboot; the feed override resets on `M2`
  only (`M30` resets it only in grbl mode, which the Z1 isn't), and the probing macros end with
  `M220 S100`. Jobs end with `M30`, so both overrides carry into the next job.
- When a job ends (`|P:` gone for 3 statuses), the app puts feed and spindle overrides back to 100 %:
  the firmware keeps them across jobs, and an 85 % left over from earlier made a later job's S13000
  run at ~11,050.
- `?` in tool names (e.g. `(T4  30? ? 0.3 engraver …)`) are in the file itself: Fusion's post writes
  ASCII and replaces °, ×, ″ … with `?`. Name tools in ASCII in Fusion to avoid it.
- The Wi-Fi board takes about 3 clients at once; more get `0x91` "maximum number of client connections".
- Bare `S` lines don't change the spindle speed on the stock firmware; only `S… M3` does.

## Zero / undo
`zero` sends `G10 L20 P0 <axis>0`. Pressed again while the axis still reads 0.000 and hasn't moved, it
sends `G10 L20 P0 <axis><previous value>` instead. The Zero hotkeys (X/Y/Z/A, XY, XYZ, selected) share it.

## Typed moves
The Move box sends `G90 G0 <axis><value>` (Go to) or `G53 G0 <axis><machine end position>` (Move by,
computed from a status at most 1 s old), and refuses targets past the soft limits before sending anything.

## Test hooks
Debug builds only (`gradlew assembleDebug`); a release build ignores these extras, so other apps
can't use them to move the machine.
- `adb shell am start -n com.z1pendant/.MainActivity --es host <ip> [--ei port 2222] [--es name …]` connects.
- `… --ei testclicks <±n> --ei testms <ms> [--ei teststep <index>]` injects a burst of dial clicks.
- Simulator: `python3 python/z1sim.py`, then connect the app to that computer's IP (or
  `adb reverse tcp:2222 tcp:2222` and `--es host 127.0.0.1`).

## Confirmed on a real Z1 Pro (stock firmware 1.1.3.0.1.13)
- Discovery broadcast: `Makera_Z1P_xxxxxx,<ip>,2222,0,Idle`. The 5th field is the machine state; "busy"
  stayed 0 with Makera Studio connected.
- Status has no `H:` field and the firmware sends no periodic `0x71` version packets.
- G-code and `$` lines get `ok`; shell commands (`version`) reply with text only, so the app waits for
  an `ok` only on G-code / `$` lines. Errors can arrive split across packets.
- Replies come in order, and some only when the command is done: `$H` gets its `ok` when homing ends
  (3.9 s even from the home position, 2026-11-15). So the link has no reply timeout: a line out waits
  for its own reply, and two lines are out at most.
- A reset (Ctrl-X) during `$H`: `ALARM: Abort during cycle`, `ALARM: Homing fail`, then the `$H`'s `ok`
  80 ms after the reset. The app fails the lines out at once ("reset") but keeps them in line for up to
  1 s to take such late replies, and sends nothing new until then.
- Several TCP clients at once work (Makera Studio, the app, test clients), each getting its own replies.
- **Upper soft limit = the homed position, machine −1.000.** A move past it alarms ("Soft Endstop X was
  exceeded"); that's why the app's "Z up" goes to `G53 G0 Z-2`, not Z0. The machine homes at power-on.
- Dial jogs at 0.01 and 1 mm per click, M821/M822 light, M851/M852 vacuum: all confirmed.
- Status round trip over Wi-Fi: ~30 ms median from a PC. On a Pixel, Wi-Fi power save gave 18–300 ms
  until the app held `WIFI_MODE_FULL_LOW_LATENCY` while visible (then mostly 17–42 ms).

## Jog commands compared on a real Z1 (stock firmware 1.1.3)
Same X moves, 300 mm/min, status polled every 20 ms from a PC over Wi-Fi:

| Command | Start of a 5 mm move | 5 mm took | `ok` | Modal state after |
|---|---|---|---|---|
| `$J X-5 F0.25` | 229 ms | 1.15 s | none | unchanged (G90) |
| `G91 G1 X-5 F300` + `G90` | 250 ms | 1.21 s | yes | G91 until the `G90` |
| `G53 G1 X<pos> F300` | 226 ms | 1.21 s | yes | unchanged (G90) |

The start delay is the same for all three (it is mostly the Wi-Fi bridge and polling), so `$J`'s
skipped queue delay doesn't show. `$J F0.05` ran at about 60 mm/min, so its rate scale tops out near
1200 mm/min. A burst of ten `$J X-0.05` 40 ms apart all ran (0.5 mm exactly).

## Still unverified
- The file download on the real machine (protocol from the Makera app + Wi-Fi board decompiles; run on the simulator).
- Whether the stock firmware takes `M223` with two decimals and what its spindle "target" field means.
- Y/Z upper soft limits (assumed −1 like X).
