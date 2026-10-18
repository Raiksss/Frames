# Frames — manual camera for Android

A pro-style manual camera app (Camera2 API, Kotlin, no third-party libraries).

## Features
- **Manual exposure**: ISO and shutter speed in 1/3-stop steps (1/8000 s – 30 s, limited to what your phone's sensor allows). Switching to manual starts from the current auto values.
- **Manual focus** with distance readout (m / ∞), or continuous AF
- **Exposure compensation** (EV) in auto mode
- **White balance presets**: Auto, Daylight, Cloudy, Shade, Tungsten, Fluoro
- **Looks**, previewed live in the viewfinder and baked into the saved JPEG:
  Natural · Classic (warm, soft) · Vivid · Chrome (cool, punchy) · Mono · Mono HC (red-filter B&W)
- **35mm film looks** inspired by classic Kodak stocks: real tone curves per colour channel, red-orange halation around bright lights, vignette and a 3:2 frame like a 35mm negative.
  - *Portrait 400* — soft contrast, warm creamy skin, pastel colour
  - *Golden 200* — sunny warmth, rich yellows and reds
  - *Everyday 400* — punchy, saturated consumer film
  - *Saturate 100* — very saturated, deep reds and blues
  - *Cine 500T* — tungsten cinema film for night: cool cast, strong red glow around lights
  - *Grit 400 B&W* — high contrast, deep blacks
- 3:4 viewfinder, rule-of-thirds grid, EXIF data (ISO, shutter, aperture, focal length)
- Every photo is saved as a JPEG with the chosen look or film applied, straight to your gallery (album **Frames**, folder DCIM/Frames, `FRM_yyyyMMdd_HHmmss.jpg`)

## Build & install
1. Install **Android Studio** (free) on your computer.
2. *File → Open* and choose this `Frames` folder. Let it sync; it downloads Gradle and the Android SDK automatically the first time.
3. On the phone: *Settings → About phone →* tap *Build number* 7 times, then turn on *Developer options → USB debugging*.
4. Connect the phone by USB and press **Run ▶**.
   To get a shareable APK instead: *Build → Build App Bundle(s)/APK(s) → Build APK(s)*.

Requires Android 10+. Manual controls depend on the phone exposing them to apps — most mid-range and flagship phones from the last few years do. Unsupported controls show a message instead of failing.

## Code map
- `MainActivity.kt` — camera setup, controls, capture, saving
- `Looks.kt` — colour looks and the film engine (tone curves, grain, halation, 3:2 crop)
- `OverlayView.kt` — vignette and grid over the viewfinder

## Ideas for next steps
Tap-to-focus/meter, live histogram, focus peaking, zebras, lens switching (wide/tele), shutter sound, volume-key shutter.
