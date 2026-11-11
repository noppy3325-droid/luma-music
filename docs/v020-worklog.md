# v0.20 implementation and verification ledger

## Starting state

The workspace had no `.git` repository. The previous v0.1.0 source archive was copied to `.tools/v020-baseline.zip` before editing and extracted into an isolated `.tools/baseline-project` for comparative measurement. The existing GitHub repository is `noppy3325-droid/luma-music`; publication will preserve its existing history.

## Audit and plan

- `LibraryRepository`: old `DocumentFile` property access makes repeated provider queries, then reads tags, technical format, artwork and embedded lyrics serially before publishing the complete inventory. Replace with projected streaming discovery, incremental Room batches, two metadata workers and demand-loaded details.
- `LibraryDatabase` / `LumaApp`: additive migration from schema 1 to 2; preserve favorites, lyrics, playlists and references. Add cached analysis and custom presets.
- `LiquidBackdrop`, `Glass`, theme, library/player/root/standby: validate the pinned Backdrop public API in a debug scene before integrating live sibling-layer sampling throughout the UI. Keep source and foreground layers separate.
- `PlaybackController` / `LumaScreen`: isolate position updates from library composition and stop rebuilding the queue every 350 ms.
- `core/dsp`, Media3 service and sound settings: immutable Direct/Enhance configuration, real PCM EQ, headroom, stereo width, optional measured analysis and persisted settings. Keep unsupported output properties explicitly unknown.

## Verification recorded so far

- New DSP tests ran against intentional bypass stubs: 7 tests, 4 expected failures (EQ response, bounded output, stereo width, normalization). This demonstrates that these checks detect absent processing.
- Analysis tests ran against intentional stubs: 4 tests, 3 expected failures (K weighting, tag gain parsing, measured correction).
- Isolated baseline/spike APK and Android test APK built successfully using Backdrop 2.0.1 with the existing compiler/Compose stack. Compilation does not establish visual correctness.
- The initial ADB inventory had no phone. A Xiaomi 14T was later connected over USB and used for the release checks. The shared USB port prevented the simultaneous EarPods check.

The measurements, visual captures and hardware limits are recorded in [the v0.20 verification report](VERIFICATION-v0.20.md).
