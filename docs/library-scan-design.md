# Library scanning and cache (v0.20)

`LibraryRepository` uses a three-stage pipeline. Stage A queries only document ID,
name, MIME, size and modification time through `DocumentsContract`. It consumes
cursor rows immediately and publishes each 16 audio discoveries and their folder
references in a Room transaction. There is no whole-directory materialization or
per-file `DocumentFile` call. Only user-selected tree roots and descendants are
visited. Root `DocumentFile` is used once when adding a folder to obtain its name.

Stage B has two IO workers. A lightweight row queue decouples discovery from slow
metadata reads; no image bytes are queued. It reads title, artist, album, duration
and basic tags with `MediaMetadataRetriever`. Filename placeholders are playable
and visible before this work finishes. Existing rows remain visible during scans.

Stage C is explicitly requested with `ensureDetails(uri)` by a visible row,
selected/playing track or lyrics request. At most two detail jobs run at once.
Per-URI mutexes serialize detail and metadata writes. It extracts artwork, writes
the original compressed image and a maximum 512px JPEG thumbnail under cache/art,
and reads embedded lyrics plus the technical stream format. Artwork decoding uses
bounds/subsampling before resizing and recycles temporary bitmaps. Unknown format
values remain null. Full artwork and thumbnail paths are distinct; list UI uses
`artworkThumbnail ?: artwork`, while Now Playing uses `artwork`.

A URI, positive provider size and positive modification time determine cache
identity. Equal size+mtime and READY metadata avoid all file opens on subsequent
scans. Providers returning unknown size/mtime are conservatively reparsed; there is
no guessed fingerprint. FAILED basic metadata is retryable on the next scan.
FAILED details are not retried on every scroll; a forced rescan resets them.
Missing disk artwork is reloaded on demand. External .lrc matches use actual IDs
returned by the same directory cursor, since SAF IDs cannot be guessed from names.
Those links are committed after the metadata workers finish. Manual lyrics take
precedence and do not trigger file decoding.

A complete directory traversal alone never deletes a track reference. Candidates
not found must also return an explicit missing result from an individual document
query. Permission, traversal, transient and tag failures retain previous refs.
Only removing a folder explicitly removes its references and analysis caches.
Cancelling a scan retains committed discovery batches. Favorite/lyrics edits and
playlists occupy separate tables and are never replaced by scan transactions.

Room schema 2 adds cache identity/state/thumbnail columns, audio_analysis and
dsp_presets. `MIGRATION_1_2` alters existing tables without replacing them. Migrated
metadata is initially READY; size=0 triggers one conservative refresh. The
migration instrumentation test creates the exact exported schema1, then checks
Room validation and persisted track/ref/folder/favorite/lyrics/playlist/entry data.

## Measurement

`LumaScan` logs ScanMetrics for each completed/cancelled run:

- enumerationMs: projected query and cursor read time, excluding the visitor/DB.
- filteringMs: extension/MIME classification, millisecond clock resolution.
- metadataMs: sum of two worker elapsed durations, including per-track DB writes.
  This is aggregate worker time, not additive with totalMs or dbMs.
- artworkMs: on-demand artwork retrieval/decode/cache time; normally zero during
  scans when no visible/playing track requests details.
- dbMs: discovery/metadata/link DB writes; excludes reference reconciliation.
- firstPublishedMs: time until the first discovery transaction commits. It is a
  persistence timestamp, not an Android frame/rendering measurement.
- totalMs, parsed, cached, queries (directory queries only).

Stage C also logs total stageC_ms and artwork_ms separately. The existing synthetic
200-WAV benchmark observes the first matching Room Flow emission as firstMs. Its
provider counts every actual query/open. `discoveryPrecedesMetadataAndUnchangedScanDoesNotOpenFiles`
uses a fresh in-memory DB and delayed synthetic file opens to assert initial
publication while metadata is pending, persisted lazy details, zero opens on a
cache hit, size-only invalidation, cancellation, permission loss and confirmed
file deletion. It does not touch user music or the persistent app DB.

Run the scanner and migration tests on the connected device via the project
Gradle connectedDebugAndroidTest task (or adb instrumentation class filtering).
Record `LumaScanBenchmark` before/after using the same fixture and device. Summarize
multiple runs with mean/p95/max; separately measure frame rendering/CPU/memory
with Perfetto or gfxinfo during playback and scrolling. A commit/Flow timestamp
must not be reported as first rendered frame. This document contains no invented
benchmark results; actual runs belong in the final device verification report.

## Limits

The lightweight Stage B queue and visited-URI sets scale with the selected
library. Metadata/detail extraction uses the Android framework; an individual
native decode may take time to return even after coroutine cancellation. There
is no custom USB driver and no audio DSP in the scan path. Artwork is in Android's
evictable cache directory. Full embedded artwork is kept compressed, not expanded
into a full-size bitmap in memory. Analysis is managed separately by the playback
DSP layer and must compare its persisted identity before reuse.
