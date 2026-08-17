# LayerLock — Custom Lock & Home Screen Designer

**A build spec for a solo indie developer** · Concept doc v1.1 · Android build
*("LayerLock" is a placeholder name — see §14 for alternatives.)*

> **Build scope — read this first, including if you're an AI coding assistant:** this spec targets **Android only**. iOS is intentionally parked in [Appendix A](#appendix-a-ios-parked) and is **not** part of the current build. If you're Claude Code, another coding agent, or a future contributor implementing this file: do not create, scaffold, or write any iOS/Swift/Xcode/WidgetKit files or folders based on the appendix. It's reference material for a decision that hasn't been made yet — not an active task.

---

## Table of Contents

1. [TL;DR](#1-tldr)
2. [What Already Exists](#2-what-already-exists)
3. [Platform Feasibility — Read This First](#3-platform-feasibility--read-this-first)
4. [Product Vision & Feature Set](#4-product-vision--feature-set)
5. [Recommended Architecture & Tech Stack](#5-recommended-architecture--tech-stack)
6. [Scene Data Model](#6-scene-data-model)
7. [Suggested Repo Structure](#7-suggested-repo-structure)
8. [Phased Roadmap](#8-phased-roadmap)
9. [Monetization](#9-monetization)
10. [Legal, Privacy & Store-Policy Checklist](#10-legal-privacy--store-policy-checklist)
11. [QA & Device Testing Matrix](#11-qa--device-testing-matrix)
12. [Risks & Mitigations](#12-risks--mitigations)
13. [Success Metrics](#13-success-metrics)
14. [Naming Brainstorm](#14-naming-brainstorm)
15. [First Two Weeks — Concrete Action Items](#15-first-two-weeks--concrete-action-items)
16. [Further Reading](#16-further-reading)
17. [Appendix A: iOS (Parked)](#appendix-a-ios-parked)

---

## 1. TL;DR

- **The idea**: one app, one free-form canvas editor, for building fully custom lock and home screens — image/video/GIF backgrounds, movable/resizable clock & date text in any font, layered widgets, subject cutouts, front-to-back depth ordering.
- **This build is Android only.** The OS provides a live-wallpaper engine, real app widgets, and a legitimate, non-root way to take over the lock screen's display — everything on the feature list is achievable.
- **iOS is parked, not cancelled.** Apple doesn't allow video lock screens, free-form widget placement, or programmatic wallpaper setting for third-party apps, so an iOS version would be a smaller, different product (a wallpaper composer + widget pack), not a port of this one. That's a real decision to make deliberately later, not a default to build toward now — see [Appendix A](#appendix-a-ios-parked).
- **Nobody currently ships this exact combination as one simple Android app.** The closest analogue is Kustom's KWGT/KLWP/KLCK trio — powerful, but technical and fragmented across three separate apps. That gap is the opportunity.

---

## 2. What Already Exists

| App | Covers | Price |
|---|---|---|
| KWGT (Kustom Widget) | Home-screen widget layers, custom fonts, formulas | Free+ads / $6.99 Pro Key |
| KLWP (Kustom Live Wallpaper) | Animated/video canvas as a live wallpaper | Free+ads / $6.99 Pro Key |
| KLCK (Kustom Lock Screen) | Dedicated lock-screen layout builder (explicitly *not* a secure-lock replacement) | Free+ads / $6.99 Pro Key |
| Zooper Widget | Similar layer-based widget engine | ~$2.99 (availability has been inconsistent on Play) |
| Generic "video live wallpaper" apps | Any video/GIF as a moving background, no layering | Free, ad-supported |

*(iOS has its own comparable apps — Widgetsmith and similar — covered in [Appendix A](#appendix-a-ios-parked) since they aren't competitors for this build.)*

The takeaway for the spec below: **no single Android app currently combines free-form layer placement + video/GIF + widgets + depth cutouts in one coherent tool.**

---

## 3. Platform Feasibility — Read This First

This section exists so you don't spend three months building something that can't ship. Read it before writing a line of code — it should directly shape your MVP cut line in §8.

### What a third-party Android app can legitimately do

- **Static backgrounds**: Trivial. Any image can be set as wallpaper via the system `WallpaperManager`. Since Android 12, users (and apps, through the standard "set on" dialog) can assign different static wallpapers to home vs. lock screen independently.
- **Video / GIF / animated backgrounds**: Real, via the `WallpaperService` live-wallpaper API — the same one KLWP and every video-wallpaper app in §2 is built on. Your service receives a `Surface` and draws whatever you want on it, including decoded video frames. One caveat to design around: whether a live wallpaper actually renders on the *lock* screen (vs. home screen only) is partly manufacturer-controlled, not something your app can force on every device — confirm this per OEM (see §11).
- **A real custom lock screen (not just a background)**: Possible without root or Device Admin, using a public, non-privileged API. An Activity that calls `setShowWhenLocked(true)` (API 27+) is permitted to draw over the keyguard — the same mechanism incoming-call screens use. Critically, this does **not** touch device security: the OS still owns PIN/pattern/biometric authentication, and your Activity is only what's visible *before* that challenge, never a replacement for it. Pair it with a standard, user-granted Notification Listener permission if you want to mirror notification content on your custom UI.
- **Widgets other apps can place**: `AppWidgetProvider` / Jetpack Glance lets your designed layouts become genuine home-screen widgets, placeable outside your own app in the OS's normal grid-snapped widget layer.
- **What you can't do**: run your free-form layer engine *inside* the stock system lock screen's own widget slots — Android dropped native keyguard widget support after 5.0. You're always either (a) a live wallpaper sitting behind the stock lock screen, or (b) your own Activity replacing what's shown, per above.

> **Policy note, worth internalizing now:** there's an older, riskier pattern for lock-screen apps — Device Admin's "disable keyguard" flag combined with `SYSTEM_ALERT_WINDOW` overlays. It works, but it's also the exact mechanism credential-phishing malware uses to draw fake lock/bank screens, so Google Play scrutinizes that permission combination heavily, and a solo dev's app is more likely to get flagged than waved through. The modern `setShowWhenLocked` approach above avoids that combination entirely and is the one production apps in this space actually use today. Build on that, not the older pattern.

**How the unlock handoff actually works, for reference:** your app only ever controls the visual layer shown before any unlock decision. When your Activity requests a keyguard dismiss, Android decides what happens next entirely on its own — if the device has no secure lock, it dismisses instantly; if it does, Android shows its own PIN/pattern/password or biometric (face/fingerprint) prompt, with biometrics always backed by a mandatory PIN/pattern/password fallback. Your app never sees or touches any of that.

---

## 4. Product Vision & Feature Set

Mapped directly to the brief this doc was built from:

| Your requirement | How it's implemented |
|---|---|
| Custom image/video from files or photo library | Background layer, sourced via Android's Photo Picker (no broad storage permission needed) |
| Clock & date font changes, resize, movable | `text` layer type (`clock` / `date` variants) with font family, size, color, weight, and a universal drag/scale/rotate transform |
| Widgets from apps, grid-locked *or* free placement | Editor-level **snap-to-grid toggle** — grid mode aligns layers to a configurable grid (great for symmetric rows), free mode allows pixel-precise absolute positioning. Both resolve to the same underlying x/y in the saved scene (see §6) — the grid is purely an editing aid, not a data constraint |
| Depth rearrangement | Every layer carries a `z` index; a draggable layer list (like Photoshop's layers panel) reorders front-to-back stacking |
| Extra videos, cutouts, GIFs — resized, moved | `video`, `cutout`, and `gif` layer types, all sharing the same transform + z-index system as every other layer |

**Core layer types to support in the editor:**
- `background` — image, video, or solid/gradient color, fills the canvas
- `text` — clock, date, or free text, with full typography controls
- `image` — a static photo layer (for stickers, logos, etc.)
- `video` — a looping video clip, resizable/movable independent of the background
- `gif` — animated GIF, same transform system
- `cutout` — a photo with the subject auto-segmented out (see below), so it can sit *in front of* the clock/date layer for a depth effect
- `widget` — a data-driven tile (weather, battery, steps, next calendar event, etc.)

**Depth/cutout tool**: use on-device subject segmentation via ML Kit's Selfie/Subject Segmentation to cut a person or object out of a photo so it can be layered in front of other elements. This runs live inside the wallpaper canvas, so the cutout stays fully interactive — move it, resize it, restack it — rather than being baked into a flat image.

---

## 5. Recommended Architecture & Tech Stack

- **Language/UI**: Kotlin + Jetpack Compose
- **Rendering core**: a single custom Canvas-based scene renderer shared by (a) the in-app editor preview, (b) the `WallpaperService` engine, and (c) the `setShowWhenLocked` lock Activity — write this once, reuse it three ways
- **Video decode**: Media3 (ExoPlayer) rendering into the wallpaper `Surface`
- **GIF**: `AnimatedImageDrawable` (native since API 28) or Coil's GIF support
- **Photo/video picking**: Android Photo Picker (`ActivityResultContracts.PickVisualMedia`) — no `READ_MEDIA_*` permission required for user-selected items, which is both better privacy and an easier Play Store review
- **Cutout/segmentation**: ML Kit Selfie Segmentation / Subject Segmentation (on-device)
- **Home-screen widgets**: Jetpack Glance (the modern Compose-based `AppWidgetProvider` wrapper)
- **Local storage**: Room or DataStore for saved scenes/presets

**Optional backend** (only if you want cloud sync or a preset marketplace later): Firebase or Supabase for auth, scene sync, and shared preset storage, plus a CDN in front of any shared/community assets once you have a marketplace feature (see §8).

---

## 6. Scene Data Model

A minimal JSON schema to start from — every layer shares the same transform shape, which is what makes "depth rearrangement" and "resize/move anything" simple to implement uniformly:

```json
{
  "sceneId": "3f1a9e2c-...-uuid",
  "name": "Sunset Lock",
  "target": "lock",
  "canvas": { "width": 1080, "height": 2400 },
  "background": {
    "type": "video",
    "sourceUri": "content://media/external/video/123",
    "loop": true,
    "muted": true
  },
  "layers": [
    {
      "id": "layer-1",
      "type": "clock",
      "z": 10,
      "transform": { "x": 540, "y": 300, "scale": 1.4, "rotation": 0 },
      "style": { "fontFamily": "Poppins-Bold", "color": "#FFFFFF", "shadow": true }
    },
    {
      "id": "layer-2",
      "type": "cutout",
      "z": 20,
      "sourceUri": "content://media/external/images/456",
      "transform": { "x": 540, "y": 1400, "scale": 1.0, "rotation": 0 }
    },
    {
      "id": "layer-3",
      "type": "widget",
      "widgetKind": "weather",
      "z": 5,
      "transform": { "x": 180, "y": 2000, "scale": 1.0, "rotation": 0 },
      "gridSnapped": true
    }
  ]
}
```

Higher `z` renders in front. `gridSnapped` is editor metadata only — it never changes how `transform.x/y` is interpreted at render time, which keeps the renderer simple regardless of which editing mode the user was in.

---

## 7. Suggested Repo Structure

```
layerlock/
├── app/
├── core-canvas/          # shared scene renderer — the heart of the app
├── wallpaper-service/    # WallpaperService implementation
├── lockscreen/           # setShowWhenLocked-based lock UI
├── widgets-glance/       # Jetpack Glance home-screen widgets
├── scene-schema/         # JSON schema + validators + docs for saved scenes
└── docs/
    └── README.md         # this file
```

*(No `ios/` folder — deliberately, for now. See Appendix A.6 for what that would look like if iOS ever moves out of the appendix.)*

---

## 8. Phased Roadmap

Rough sizing, not calendar promises — scale to your own hours/week.

**Phase 0 — Validate & Scope**
- [ ] Sketch 10–15 target "scenes" (mood boards) to pressure-test what the editor actually needs to support
- [ ] Use KLCK/KWGT/KLWP yourself for a week; every friction point you hit is a differentiation opportunity
- [ ] Write down an explicit MVP cut line and defend it against scope creep before writing code

**Phase 1 — Core Canvas Engine**
- [ ] Static image background picker (Photo Picker API)
- [ ] Draggable/resizable/rotatable text layer (clock, date) with font picker
- [ ] Layer list with drag-to-reorder (your z-index / "depth rearrangement" feature)
- [ ] Export a composited PNG — this alone is a shippable v0.1 for static home-screen wallpapers

**Phase 2 — Live Backgrounds**
- [ ] Wrap the same renderer in a `WallpaperService`
- [ ] Video layer support (Media3 decode into the wallpaper surface)
- [ ] GIF layer support
- [ ] Battery testing: pause all rendering when invisible/ambient — this is the single biggest source of 1-star reviews for anything in this category

**Phase 3 — Real Lock-Screen Surface**
- [ ] Render the same scene inside a `setShowWhenLocked(true)` Activity
- [ ] Optional, clearly-disclosed Notification Listener integration
- [ ] Never intercept, store, or touch the PIN/pattern/biometric flow — that stays the OS's job, always
- [ ] Test specifically on Samsung/Xiaomi/OnePlus — OEM skins are the top source of lock-screen bugs

**Phase 4 — Widgets & Cutouts**
- [ ] Subject segmentation (ML Kit) for the cutout tool
- [ ] Jetpack Glance widgets so scenes can also live as real home-screen widgets outside your app
- [ ] Preset/scene library with import/export (the JSON from §6)

**Phase 5 — Polish, Monetize, Ship**
- [ ] Onboarding that explains *why* before asking for each permission — a real trust/retention lever, not just politeness
- [ ] Paywall (see §9 for why a one-time unlock is worth considering over subscription)
- [ ] Privacy policy + Play Data Safety form
- [ ] Closed beta across 10–20 real devices spanning OEMs, before public launch

*(There's intentionally no iOS phase here — see [Appendix A](#appendix-a-ios-parked) for what that would look like if you ever decide to build it.)*

---

## 9. Monetization

The Android apps in §2 all use freemium IAP, clustered mainly around one-time unlocks — Kustom's $6.99 Pro Key model. Low friction, no recurring billing anxiety, and reviewers respond well to it.

For contrast, it's worth knowing subscription pricing exists too and has a real downside: Widgetsmith (the iOS app covered in Appendix A) took real reputational damage when it moved core functionality behind a monthly subscription that had previously been a one-time unlock — it's a recurring complaint in its reviews. A reasonable default for LayerLock: **one-time "Pro" unlock** for the creative tools (fonts, cutout tool, unlimited layers, no watermark), and reserve a subscription only for something that has an ongoing cost you're actually paying for (cloud sync, a hosted preset marketplace). Free tier should still be genuinely usable — watermark or layer-count caps are more standard than time-limiting the whole app.

---

## 10. Legal, Privacy & Store-Policy Checklist

- [ ] Privacy policy covering photo/video library access, and notification data if you build the Notification Listener feature
- [ ] Play Data Safety form, kept accurate as features change
- [ ] If you request Notification Listener access: a clear in-app rationale screen *before* the system permission prompt — Google reviews this permission closely
- [ ] Avoid Device Admin / "disable keyguard" APIs entirely; use `setShowWhenLocked` (§3) — this sidesteps the exact permission combination Play Store scrutinizes for phishing-overlay risk
- [ ] If you ever add a community preset/wallpaper marketplace: a DMCA takedown process, UGC moderation, and explicit terms that uploaders only submit content they own
- [ ] Respect Google/Android trademark guidelines in your marketing — nominative references are fine, implying endorsement isn't
- [ ] If the app is likely to appeal to under-13 users (customization apps often do), review Google Play Families policy before you design onboarding or ads

### Purchase integrity & anti-piracy

Nothing here makes the app uncrackable — no code does. The goal is closing the easy, casual paths and putting real cost on anyone determined enough to bother, which is the realistic bar for a solo-dev app.

Purchases are already account-scoped by default: Play Billing ties every purchase to the buyer's Google account automatically, no extra work needed. Worth knowing — Google Play Family Library shares a *paid app itself* with family members, but explicitly does not extend to in-app purchases, so a free app + Pro IAP (§9's model) isn't affected by family sharing at all.

- [ ] Use the **Play Integrity API** to verify at runtime that the app is the genuine, untampered binary, installed via Google Play, on a real device — gate Pro features behind this, not just a local flag. (SafetyNet, the older version of this, was fully retired in January 2025 — ignore any tutorial that still references it.)
- [ ] **Verify purchase tokens server-side** (or at minimum re-validate against the Play Developer API) rather than trusting a local `isPro = true` flag stored on-device — this single check stops most patched-APK bypasses.
- [ ] Enable **R8/ProGuard** obfuscation on release builds (bundled free with Android Studio) to raise the bar on casual decompiling.
- [ ] For someone cloning the whole app rather than cracking your build: that's a copyright/trademark matter, not a code one. Once the name is final, consider trademarking it, and periodically check the Play Store for close copies — Google has a takedown process for IP-infringing listings. (Not legal advice — worth a real lawyer's time once the app has revenue worth protecting.)

---

## 11. QA & Device Testing Matrix

| Layer | Why it matters | Minimum test coverage |
|---|---|---|
| OEM lock-screen skin | Samsung One UI, MIUI/HyperOS, OxygenOS, and stock/Pixel all render the clock, notifications, and your overlay differently | 1 Samsung, 1 Xiaomi, 1 stock/Pixel, 1 OnePlus |
| Screen cutouts | Punch-hole vs. notch vs. none shifts your safe zones | 3–4 physical devices, not emulators only |
| Battery / Doze behavior | Live wallpapers are the #1 battery complaint category in this app class | 24-hour drain test, wallpaper active vs. static, on a mid-range device |
| Android version spread | Photo Picker (13+), Glance widgets, and `setShowWhenLocked` behavior all shift across API levels | API 26 (realistic floor), 30, 34+ |

---

## 12. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Google Play flags the lock-screen permission combination | Use `setShowWhenLocked` + standard permissions only; never combine Device Admin with `SYSTEM_ALERT_WINDOW` |
| Battery-drain complaints tank your rating | Aggressive pause-when-invisible logic, capped video bitrate/resolution, an explicit "static mode" toggle |
| Feature creep delays your first release indefinitely | Hold the Phase 1–2 line hard; cutouts are explicitly post-MVP in §8 |
| Crowded, low-differentiation video-wallpaper category | Differentiate on the *combination* — layers + widgets + video in one coherent editor — not on having video alone, which is commoditized |

---

## 13. Success Metrics

- D1 / D7 / D30 retention
- % of new users who complete and export at least one scene
- % who actually apply a scene to their device (Android's wallpaper-changed broadcast makes this reasonably easy to detect)
- Free → Pro conversion rate
- Average editor session length
- Store rating trend, and — a useful early-warning signal — uninstall rate segmented by which permission screen preceded it

---

## 14. Naming Brainstorm

Placeholder options, not a decision: **LayerLock** · **ScreenCraft** · **Depthscreen** · **Scenery** (scene + scenery) · **Kanvas**

---

## 15. First Two Weeks — Concrete Action Items

- **Day 1–2**: Android Studio project set up, empty Compose app, repo initialized, package name decided
- **Day 3–5**: Build the canvas/editor screen shell — a Compose `Canvas` or custom `View` that can place, drag, and resize a single image layer
- **Day 6–8**: Add the clock text layer with font + color controls
- **Day 9–10**: Add the layer list with drag-to-reorder (z-index)
- **Day 11–12**: Wire up "export as PNG" and the manual "Set Wallpaper" intent
- **Day 13–14**: Install it on your own phone, actually use it as your wallpaper for a week, and write down every single annoyance — that list becomes your Phase 1 backlog

---

## 16. Further Reading

- Kustom's lock-screen disclaimer and feature set (useful prior art): https://play.google.com/store/apps/details?id=org.kustom.lockscreen
- "Reimagining the Lock Screen — Without OEM Permissions," a walkthrough of the `setShowWhenLocked` approach this doc recommends: https://medium.com/@imtiyaz.khan/how-we-built-a-lockscreen-app-without-any-special-android-permissions-d48127e9a648
- Android live wallpaper fundamentals (`WallpaperService`): https://www.vogella.com/tutorials/AndroidLiveWallpaper/article.html
- How Android video-wallpaper apps and Samsung's native option currently work: https://www.xda-developers.com/how-to-set-videos-as-live-wallpaper-android/

---

## Appendix A: iOS (Parked)

> **Reference material only — not an active task.** You haven't decided whether you want an iOS version. Nothing below should be built, scaffolded, or acted on until you explicitly decide otherwise and move the relevant parts back into the main body of this document. **If you're an AI coding assistant — Claude Code or otherwise — working from this file: do not create iOS, Swift, Xcode, or WidgetKit files or folders based on anything below this line.**

### A.1 Why this is a real decision, not just "phase 2"

Apple doesn't allow programmatic wallpaper setting, video/GIF lock or home screen backgrounds, free-form widget placement, or anything equivalent to `setShowWhenLocked` for third-party apps. These aren't schedule constraints you can code your way around later — they mean an iOS version is a smaller, structurally different product (a static wallpaper composer plus a WidgetKit widget pack), not a port of the Android app. Deciding to build it is a product decision, not a task to schedule by default.

### A.2 What a third-party iOS app can legitimately do

- **Backgrounds**: Static images only. There is no public API to set the wallpaper programmatically at all — your app can only save an image to Photos and prompt the user to apply it manually via Settings → Wallpaper. There's no third-party video or GIF background mechanism; Apple's own Live Photo and Photo Shuffle wallpapers aren't something third-party apps can trigger or replicate at the OS level.
- **Widgets**: Real, via WidgetKit — but confined to Apple-defined size families (`accessoryCircular`, `accessoryRectangular`, `accessoryInline` for the lock screen; `systemSmall/Medium/Large` for the home screen), placed by the user into an OS-controlled grid. There's no free drag-and-drop to an arbitrary pixel position, and refresh is timeline-based rather than truly live, though iOS 17+ allows limited in-place interactivity (buttons/toggles) inside a widget.
- **Icons**: No native theming API. The only workaround is the well-known Shortcuts-automation trick (each "icon" is a Shortcut that opens the real app), which adds a visible launch delay and isn't true theming.
- **A custom lock-screen surface**: Does not exist for third parties. iOS sandboxing has no equivalent of Android's `setShowWhenLocked`. This isn't a temporary gap — it's the platform's security model.

If this ever gets built, don't design one shared UI spec and expect to "port" it screen-for-screen. The iOS app is a composer that produces a static export, plus a small widget extension — say that plainly in the App Store listing rather than implying it does what the Android app does.

### A.3 Reality check: Android vs. iOS

| Capability | Android | iOS |
|---|---|---|
| Custom static image background | ✅ Native | ✅ Native (manual apply) |
| Custom video/GIF background | ✅ Live wallpaper | ❌ Not possible for 3rd parties |
| Free-form (non-grid) widget placement | ✅ Inside your own surface | ❌ OS-controlled grid only |
| Movable/resizable clock & date | ✅ Inside your own surface | ⚠️ Only within a widget's own bounds |
| Depth/cutout layering | ✅ Fully live | ⚠️ Bakeable into a static export only |
| True lock-screen takeover | ✅ Via `setShowWhenLocked` | ❌ Not exposed to 3rd parties |
| Custom icon theming | ✅ Via icon packs + launcher | ⚠️ Shortcuts workaround only |
| Root / jailbreak required | 🚫 No | 🚫 No |

### A.4 iOS competitive landscape

| App | Covers | Price |
|---|---|---|
| Widgetsmith | Home + lock screen widgets, fixed Apple grid | Free / $1.99 mo. / $19.99 yr. |
| Assorted "Lock Screen Widgets" apps | Widget skins, lock-screen app-launch shortcuts | Free / roughly $5–30 per yr. |

### A.5 If this gets built: recommended stack

- **Language/UI**: Swift + SwiftUI
- **Photo/video picking**: `PHPickerViewController` (privacy-friendly, no full-library permission)
- **Cutout/segmentation**: Vision framework (`VNGeneratePersonSegmentationRequest`) or VisionKit's subject-lifting capability
- **Compositing**: Core Graphics / Core Image to flatten layers into a single exported image
- **Motion, best-effort**: `PHLivePhoto` APIs get you the closest thing to "video" Apple allows on a lock screen (press-and-hold motion) — not equivalent to a real video wallpaper, but worth offering as a premium background type
- **Widgets**: WidgetKit extension, App Intents for any interactive elements (iOS 17+)
- **Apply flow**: save to Photos via `PHPhotoLibrary`, then deep-link to the Settings app (there's no API to jump straight to the Wallpaper picker — tell the user where to tap)

### A.6 If this gets built: repo addition

```
ios/
├── LayerLockApp/
├── WidgetExtension/
└── RenderEngine/        # Core Image/Core Graphics compositor
```

### A.7 If this gets built: roadmap phase

*(Parallel track or after Android traction — not scheduled.)*

- Reuse the scene schema, not the renderer — build a Swift/Core Graphics compositor against the same JSON (§6)
- Static composer: same layer stack, exported as one flattened image
- WidgetKit extension covering the widget types Apple's grid actually allows
- "Save to Photos → open Settings" apply flow, explained clearly in-app (this is the ceiling on iOS — be upfront about it, including in your own screenshots)

### A.8 Further reading (iOS)

- Building iOS Lock Screen widgets with WidgetKit: https://blog.logrocket.com/building-ios-lock-screen-widgets/
- Lock Screen widget SwiftUI walkthrough: https://swiftsenpai.com/development/create-lock-screen-widget/

---

*This doc is a starting point, not a contract with yourself — revisit §3 and §8 once you've actually got the canvas engine running, and revisit Appendix A whenever you're ready to make the iOS call.*
