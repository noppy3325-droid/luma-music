# Aurio app icon

The three [icon concepts](design/aurio-icon-concepts.svg) show the wave A, paired resonance arcs, and a four-bar pulse. The 24 px previews preserve the wave A’s outline and the motion of its crossbar. The arcs converge at this size and the pulse has the silhouette of a generic equalizer, so Aurio uses the wave A.

The [SVG master](design/aurio-icon.svg) uses an ivory mark over a plum gradient. The Android adaptive icon translates both layers directly to 108 dp vector drawables. The rounded strokes fit inside a central 66 dp circle, so circular and rounded masks retain the mark. Android can tint the transparent foreground as a themed icon. [Android adaptive icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

Android 12 and later use the foreground vector and a solid plum background during the system splash screen. The Media3 notification uses a separate 24 dp white outline with heavier strokes for the status bar. [Android splash screen guidance](https://developer.android.com/develop/ui/views/launch/splash-screen) · [Media3 notification provider](https://developer.android.com/reference/androidx/media3/session/DefaultMediaNotificationProvider).

`BrandAssetsTest` asks Android for the installed Aurio launcher entry and renders the adaptive and themed drawables. `PlaybackDeviceTest` checks the active Aurio media notification during background playback and renders its 24 px small icon. The PNGs are written to the app’s external files directory under `brand-assets/`. The rendered files contain only Aurio’s drawables.
