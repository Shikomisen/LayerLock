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

**Fixed in code (2026-08-18) — not yet validated on device.** Scoped to `lockscreen/`; `core-canvas/`
needed no change.

**What was implemented**

1. **Proximity gate** — new `lockscreen/.../ProximityGuard.kt` exposes
   `rememberScreenCovered(): State<Boolean>`, backed by `SensorManager` / `Sensor.TYPE_PROXIMITY`.
   Registration is bound to window visibility with `LifecycleStartEffect` (listen on `onStart`,
   unregister on `onStop` or composition disposal), so no listener survives the per-wake recreation
   this `noHistory` Activity goes through.
   - "Near" is `value < min(sensor.maximumRange, 5cm)`, which reads correctly on both the binary
     sensors (0 = near, `maximumRange` = far) and the continuous ones.
   - The state **defaults to uncovered** and only ever moves to covered on a positive reading. A
     sensor cannot be polled synchronously, and not every device has one; starting covered would
     mean a missing or slow sensor could leave the lock screen refusing to unlock. Devices without
     a proximity sensor behave exactly as before.
2. **Touch suppression** — in `LockScreenActivity`, the unlock gesture is now a conditional modifier
   (`.then(if (covered) Modifier else Modifier.unlockSwipe(...))`). While covered the detector is
   *detached from the chain*, not merely filtered, so `detectVerticalDragGestures` never sees the
   events and an in-flight gesture is cancelled if the phone goes into a pocket mid-swipe.
3. **`UNLOCK_DRAG_THRESHOLD`: `-18f` → `-100f`, and now cumulative per gesture.** The units change
   matters more than the number. The old value was compared against a *single drag event's delta*,
   making it a velocity test in disguise — it fired on any one frame that moved 18px (easy for
   fabric contact), while a slow deliberate swipe on a 120Hz panel might never move that far within
   one frame. The threshold is now total travel accumulated across one gesture, reset on
   `onDragStart`. `requestUnlock()` also fires at most **once per gesture** now; previously every
   further event past the threshold fired another `requestDismissKeyguard` into the system prompt
   the first one had already raised.
4. **Video** — `SceneSurface` already accepted `playVideo`, which swaps live ExoPlayer bands for
   poster frames, so the Activity passes `playVideo = !covered`. Nothing is visible against a pocket
   lining, so a covered wake holds no decoder. Passing the live state rather than sampling once at
   create also covers the phone being pocketed *after* the scene is already up.
5. **`LockScreenService` — deliberately unchanged** (documented in `showLockScene`'s KDoc). Deferring
   the activity start on a proximity reading would put a second async signal inside a loop already
   racing the keyguard, and would have to answer what happens when the phone leaves the pocket after
   the retry window closes (the scene would never appear). The Activity self-checking handles both
   pocket-wake and pocketed-later with one mechanism.

**Caveat: this may not fully fix symptom (a), "screen stays on".** Suppressing app-side touch
handling reliably fixes the lockout chain (steps 3–4 of the root cause) and removes the video
decode. But the inactivity-timer reset in step 2 happens during OS input dispatch, *before* the
event reaches the app — an app cannot decline to have a touch on its window count as user activity.
If the screen still stays on in a pocket after this change, that part is a separate problem and the
remaining lever is the Activity finishing itself while covered, which was not done here because a
transient "near" reading (a hand passing over the sensor) would then dismiss the user's lock screen
mid-look.

**Still needs on-device testing** — per [§11](README.md#11-qa--device-testing-matrix), which lists
Samsung / Xiaomi / OnePlus / Pixel as the required hardware. Proximity behaviour is OEM-inconsistent
(reporting ranges, binary vs. continuous, debounce, and whether the sensor reports at all while the
screen is off), so none of the above is confirmed until it is walked on real devices:

- Pocket test on each OEM: screen-on in pocket, confirm no unlock prompt and no PIN lockout.
- Confirm the 5cm near-threshold reads as "near" through a pocket lining on each sensor.
- Confirm a deliberate swipe still unlocks on the *first* try on both 60Hz and 120Hz panels.
- Confirm the gesture on a device with no proximity sensor is unchanged.
- Watch whether the screen still stays on past the timeout in a pocket (the caveat above).

The `-100f` value was chosen by reasoning about frame deltas, **not** measured against a real swipe
versus a light brush — that comparison needs hardware. `100px` is ~8mm on a typical modern panel;
if it feels off across the §11 matrix, the follow-up is expressing it in `dp` rather than raw pixels
so it is consistent across densities.

### On-device verification (2026-08-18, Nothing Phone, Android 16 / API 37)

Walked on one device via adb. What is now confirmed rather than reasoned:

- The custom lock screen appears on wake and plays its video scene.
- A 40px flick does **not** unlock; a ~900px deliberate swipe raises the system bouncer
  (`mCurrentFocus=AlternateBouncerView`, the under-display fingerprint prompt). The handoff to the
  OS is unchanged.
- `dumpsys sensorservice` lists `...lockscreen.ProximityGuardKt` as a client with matched
  register/unregister pairs across Activity recreation — no listener leak.
- This device's sensor (`GLS6851C`, goodix) reports exactly `0.00` near / `5.00` far with
  `maximumRange` 5.0, so `value < min(maximumRange, 5cm)` classifies it correctly. That is one
  sensor, and the binary-at-exactly-maximumRange shape is the easy case.

Still unverified, and still the point of the fix: **actual pocket behaviour.** Covering the sensor
cannot be simulated over adb on a physical device, so the checklist above stands — in particular
whether a pocket lining reads as "near" at all, and whether the screen still stays on past the
timeout (the caveat above). One tester, one OEM, one sensor is not the §11 matrix.

---

## OPEN-2 — Video wallpaper frozen on its first frame

**Reported:** 2026-08-18
**Severity:** High — the app's headline feature silently degrades to a static image.
**Status:** **Fixed 2026-08-18**, verified on device.

### Symptom

The live wallpaper showed a still frame instead of playing video. The same scene animated correctly
in the wallpaper picker's preview, which made it look like a scene-data or codec problem — it was
neither. Reported as "videos not working", alongside a separate, unrelated cause for the lock screen
not appearing (see *Diagnosis notes* below).

### Root cause

`GlWallpaperRenderer.setUpEgl()` called `eglMakeCurrent` exactly once, at `initialise()` time, and
no other entry point rebound the context.

**An `EGLContext` is current per-thread, not per-object.** A `WallpaperService` can have several
engines alive in one process — the picker's live preview alongside the real wallpaper, and home
alongside lock — and they all run on the *same* main-thread Looper, each with its own renderer and
its own context. So whichever engine initialised last left *its* context current on that thread, and
every earlier engine's subsequent GL call then ran against a foreign context.

`SurfaceTexture.updateTexImage()` is the call that fails loudly about it:

```
W LayerLockGl: GL draw failed
java.lang.IllegalStateException: Unable to update texture contents
    at android.graphics.SurfaceTexture.updateTexImage(SurfaceTexture.java:318)
    at ...GlWallpaperRenderer.drawFrame(GlWallpaperRenderer.kt:164)
```

thrown once per frame, caught by `drawFrame`'s `runCatching`, leaving the last successfully drawn
frame on screen forever. The preview looked fine precisely *because* it was alone when it started.

`release()`'s own comment already noted that at least two engines are alive at once; the
consequence for context currency was the part that had been missed.

### Fix

A `makeCurrent()` helper in `GlWallpaperRenderer`, called at the top of `drawFrame()` and
`setOverlay()` — every entry point that touches GL. This also makes `release()`'s unbinding of the
thread's context self-healing, since the next engine to draw rebinds itself first.

Verified on device: four successive home-screen captures now hash differently (video advancing) and
`GL draw failed` no longer appears in logcat.

### Diagnosis notes

Two things reported together as one bug turned out to be independent, and neither was caused by the
OPEN-1 work — the build under test predated it:

- **Wallpaper frozen** — the GL context bug above.
- **Custom lock screen absent** — `lockScreenEnabled` was simply `false` in `files/datastore/
  settings.json`. A fresh install earlier that day had reset it, along with `POST_NOTIFICATIONS`
  and the notification-listener grant.

Worth keeping in mind for future reports: **force-stopping the app makes Android revert the live
wallpaper to the stock `ImageWallpaper`**, and it does not come back on its own. So any process
death — an OEM battery killer, a crash, a reinstall — presents to the user as "my video wallpaper
turned into a static image". That is indistinguishable from OPEN-2 at a glance and worth ruling out
with `dumpsys wallpaper | grep mWallpaperComponent` before debugging the renderer.
