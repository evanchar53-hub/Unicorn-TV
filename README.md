# Unicorn TV — Android & Fire TV IPTV Suite (v2.0)

Cosmic-nebula themed IPTV application built for **Android Phones, Tablets, Android TV, Google TV, and Amazon Fire TV / Fire OS**, engineered for maximum streaming throughput and resolutions from **720p to 4K60 HDR**.

## What's Included in This Package
1. **`android-firetv-app/`** — Complete Kotlin + Jetpack Compose + Media3 (`ExoPlayer`) native Android / Fire TV project:
   - **Cosmic Nebula Theme (`Theme.kt`)**: Deep Void Indigo (`#0D061F`), Cosmic Magenta (`#E91E8C` / `#FF2E93`), Electric Cyan (`#00D2FF`), and Radiant Violet (`#7B2CBF`).
   - **Splash Intro Video Player (`IntroSplashScreen.kt`)**: Hardware-accelerated intro video playback (`res/raw/unicorn_intro.mp4`) with D-pad skip support.
   - **Live TV & Resolution Lock (`720p` to `4K60 HDR`)**: Media3 `DefaultTrackSelector` with explicit resolution locks (`Auto AI ABR`, `4K UHD 2160p60`, `1080p FHD`, `720p HD`) and `MediaCodec` hardware tunneling.
   - **Multi-View Quad Hardware Decoder (`MultiViewScreen.kt`)**: Supports up to 4 simultaneous live feeds with selectable layouts (**1 Large + 3 Small**, **2 Large + 2 Small**, **3 Small**, and **2x2 Equal Grid**), independent channel swapping, and single-stream audio focus routing.
   - **Picture-in-Picture (`PipController.kt`)**: System-level Android / Fire OS PiP mode on Home press or in-app trigger.
   - **Interactive Electronic Program Guide (`EpgGuideScreen.kt`)**: Timeline grid with live program preview header, `tvg-id` XMLTV parser, time-shift offset (`-12h` to `+12h`), and mini-guide overlay.
   - **Collapsible 10-Foot Side Navigation Rail (`SideNavRail.kt`)**: D-pad optimized navigation rail + category/search drawer.
   - **4-Step Live TV & EPG Setup Wizard (`SetupWizardScreen.kt`)**: Xtream Codes API & M3U8 parser with QR quick-pair support.
   - **Multi-Threaded HTTP/2 Speed & Buffer Engine (`StreamNetworkOptimizer.kt`)**: Custom `OkHttp` connection pool + aggressive pre-fetch `DefaultLoadControl` for zero-buffer 4K playback.
2. **`.github/workflows/build_apk.yml`** — GitHub Actions workflow to automatically compile and package debug/release APKs for Android & Fire TV sideloading.
3. **`web-prototype/`** — Standalone React + Tailwind interactive UI prototype (`UnicornTV.jsx`).

## Building the APK
### Option A: Android Studio
1. Open `android-firetv-app/` in Android Studio Ladybug or newer.
2. Place your intro MP4 into `android-firetv-app/app/src/main/res/raw/unicorn_intro.mp4`.
3. Run **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

### Option B: GitHub Actions Cloud Build
Push this folder to a GitHub repository and run the **Build Unicorn TV APK** workflow under the **Actions** tab to download the compiled `app-debug.apk`.
