# Aurio typography and shapes

Aurio bundles three unmodified Noto Sans JP 2.004 static OTFs under SIL OFL 1.1. The local family registers Regular (400), Medium (500), and Bold (700) with Compose. All 15 Material typography roles use the family, including chips, dialogs, buttons, time labels, and sound settings. The Xiaomi 14T rendered the earlier variable-font build at its thinnest weight, so the three static faces are loaded directly by Android.

The OTF metadata and file checksums are recorded in [aurio-font-metadata.json](aurio-font-metadata.json) and [aurio-fonts.csv](aurio-fonts.csv). Aurio ships the [OFL text](../licenses/NotoSansJP-OFL.txt) and copyright notice in its APK assets. [Noto CJK source files](https://github.com/notofonts/noto-cjk/tree/main/Sans/SubsetOTF/JP).

The bottom navigation uses a 68 dp outer capsule with a 34 dp radius and 5 dp inset. The 58 dp selected capsule uses 50% rounding, producing a 29 dp radius. The caps share the same center at rest.

The product is named Aurio. The app ID, database name, preference store and internal class/package names remain compatible with the prior Luma Music installation. Visible product, sound-mode, launcher, settings and release labels use Aurio.
