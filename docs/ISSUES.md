# Known Issues

Lightweight bug tracker for this repo (no GitHub issues / `gh` CLI configured yet — see
[docs/README.md](README.md) for the project spec this tracks against).

---

## OPEN-1 — Lock screen stays on / heats up / triggers pocket PIN lockouts

**Reported:** 2026-08-18
**Severity:** High — causes real lockouts ("too many incorrect attempts, try again later") and
device heat, not just a visual bug.

### Symptom

- The custom lock screen (`LockScreenActivity`) sometimes stays on well past the configured screen
  inactivity timeout while the phone is in a pocket, and the phone gets noticeably warm.
- On pulling the phone out, the system reports too many incorrect PIN attempts and imposes a
  lockout, even though the user made no deliberate unlock attempt.

### Likely root cause (from code inspection, not yet confirmed on device)

`LockScreenActivity` (`lockscreen/src/main/java/.../LockScreenActivity.kt`) draws a full-screen,
edge-to-edge `Box` over the entire keyguard and attaches
`detectVerticalDragGestures` to it with `UNLOCK_DRAG_THRESHOLD = -18f` — a very small movement
threshold — calling `requestUnlock()` (`KeyguardManager.requestDismissKeyguard`) on almost any
upward finger motion.

There is no proximity-sensor guard anywhere in the module (`lockscreen/` has no
`SENSOR_PROXIMITY` reference at all) and no code suppresses touch handling while the phone is
plausibly in a pocket. Combined:

1. Fabric contact in a pocket generates small, repeated touch/drag events against the lock scene's
   full-screen gesture detector.
2. Each of those events counts as user activity to the OS, which resets the screen's inactivity
   timer — explaining "stays on regardless of the configured timeout" and the associated heat
   (an ExoPlayer-backed video scene is also actively decoding/rendering the whole time, see
   `core-canvas/.../VideoPlayers.kt`).
3. Some of those drags cross the `-18f` threshold and call `requestDismissKeyguard()`, which hands
   off to the *system's own* secure PIN/pattern prompt.
4. Further blind pocket touches then land on that system keypad as if the user were typing,
   registering as wrong PIN attempts — eventually tripping Android's own too-many-attempts
   lockout.

Nothing here is a wake lock or an explicit "keep screen on" flag (grepped the whole repo — no
`PowerManager`, `WakeLock`, `FLAG_KEEP_SCREEN_ON`, or `WAKE_LOCK` permission exists), so the fix is
about *not reacting to touches when the phone is plausibly pocketed*, not about power management
APIs.

### Fix direction

- Gate the unlock-swipe gesture (and ideally all touch handling) behind a proximity-sensor check:
  if the proximity sensor reports "near" (covered), ignore touch input on the lock scene.
- Consider a firmer drag threshold than `-18f` so incidental brushes don't count as swipes.
- Consider `LockScreenService` deferring/cancelling the shown lock activity, or the activity
  finishing itself, if the proximity sensor stays covered for a while after screen-on (pocket
  wake), rather than leaving a live gesture-sensitive video scene on screen.

### Status

Not yet fixed. See prompt below for the Opus 5 Claude Code session tasked with implementing this.
