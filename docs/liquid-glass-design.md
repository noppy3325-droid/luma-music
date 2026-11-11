# Liquid Glass rendering

Aurio pins `io.github.kyant0:backdrop:2.0.1` from Maven Central. The published metadata and the Android 2.0.1 source archive were checked during implementation; no dynamic version or unpublished sample-component import is used. Backdrop is Apache-2.0 licensed. Its copyright/license must remain in the third-party notice distributed with Aurio.

Primary sources:

- [Maven Central metadata](https://repo.maven.apache.org/maven2/io/github/kyant0/backdrop/maven-metadata.xml)
- [Published 2.0.1 Android sources](https://repo.maven.apache.org/maven2/io/github/kyant0/backdrop-android/2.0.1/backdrop-android-2.0.1-sources.jar)
- [Official Backdrop API](https://kyant.gitbook.io/backdrop/api/backdrops)
- [Official LiquidButton example](https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidButton.kt)

## Scene graph

`LiquidBackdropHost` owns a remembered graphics-layer backdrop. Its first child records only source content using `Modifier.layerBackdrop`. Floating controls are a later sibling and read that source through `Modifier.drawBackdrop`. They do not appear in their own recording. Coordinates are supplied by the library's layout-aware layer source; surfaces remain in the same window. No CPU screenshots or bitmap copies are used for live sampling.

The library foreground combines `vibrancy`, `colorControls`, `blur`, and `lens`, with Backdrop's built-in highlight/shadow. Luminance contrast is bounded before a restrained light/dark surface tint so black and white jackets do not make foreground labels disappear. List rows and artwork never get a glass shader. Separate-window dialogs retain an opaque Material surface because they cannot safely assume the main window's backdrop coordinate space.

High uses a 12dp blur and stronger edge lens, Balanced uses 8dp and a gentler lens, Reduced uses a 4dp blur without a lens, and Off uses a plain opaque surface. API 29–30 use an opaque fallback; API 31–32 support blur without the API 33 AGSL lens. The minimum supported API remains 29. Reduced motion and battery saver reduce High/Balanced to Reduced without changing the saved DataStore quality preference.

The existing `ui` package contains the common design system: `LumaTheme.kt` (colors/type/spacing/shapes), `LiquidBackdrop.kt` (host/material/quality), `LumaGlassControls.kt` (mini-player/tabs/material container), and `LumaSlider.kt` (thin track with a 48dp touch target, keyboard and accessibility progress actions). The mini-player accepts state and transport callbacks; it does not access a playback service. Plain settings groups are deliberately separate from sampled glass.

The library records scroll content under its floating mini-player and tabs. Player and Standby record their cached artwork-palette background and place artwork, text, and interactive controls above it. The queue panel and Standby transport group use the same material rather than a second effect engine. Palette extraction is cached by artwork URI, done on IO, and never triggered by playback-position updates.

`LumaScreen` strips position from its distinct playback summary. Seek, synchronized lyrics, and mini-player composables collect position locally. A saveable-state holder preserves library detail/scroll state through player and sound routes; no audio-service restart is needed for visual transitions.

## Spike verification

In a debug build, launch the activity with boolean intent extra `luma.glassDemo=true`. `GlassDemoScreen` provides scrolling colored artwork/text, white/gray/black/high-saturation sections, a fixed glass pill, and quality controls on a separate floating surface. Verify changing sampled images while scrolling, crisp foreground labels, edge refraction on API 33+, touch handling, rotation, and High/Balanced/Reduced/Off. Hardware validation and its exact environment are recorded in the final verification report; compilation alone is not visual verification.
