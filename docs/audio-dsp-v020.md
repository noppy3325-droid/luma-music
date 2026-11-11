# Aurio v0.20 audio DSP

Aurio defaults to Direct. Enhance changes decoded PCM inside the existing MediaSessionService and ExoPlayer; it does not create a second player or use a device-dependent Android Equalizer. The sound screen shows the processor input separately from the physical USB output. No AI restoration or confirmed bit-perfect label is implemented.

## Signal path

Media3 decoder → 16-bit PCM conversion → LumaAudioProcessor → Android AudioTrack → Android policy / USB / Bluetooth / speaker.

Media3 1.11.1 `DefaultAudioSink.Builder.setAudioProcessors` and the actual source implementation were checked. Its float-output path skips custom processors, so float output is disabled. The deliberately context-free legacy sink builder fixes PCM-only capabilities; encoded passthrough is unavailable. Both the sink offload support provider and TrackSelectionParameters disable offload. These choices guarantee the same PCM path in both modes and preserve the current queue, position, service, and MediaSession when the user changes sound settings. A later Media3 upgrade must re-check these deprecated builder integrations.

Source 24-bit/float audio is converted by Media3 to PCM16. No sample-rate conversion is performed by Aurio. This release therefore cannot claim high-resolution transparency. Direct bypasses only Aurio processing, byte for byte at the processor boundary after its 20ms transition; upstream conversion, the OS, system effects, Bluetooth codecs, and DAC processing are outside that guarantee.

## Real processing

- Ten independent RBJ peaking biquads, Q=1.2, centers 31/62/125/250/500/1000/2000/4000/8000/16000 Hz, ±12 dB. Bands at or above 0.48 of the input sample rate are bypassed. Each channel has separate filter memory. The displayed curve evaluates these same coefficients, including the actual available Auto profile.
- Gain and normalization precede EQ. Positive EQ gains are summed conservatively for headroom, with another 1 dB margin when boosting. Width above 100% also reserves its possible gain. Positive gain is limited by measured sample peak; without one Aurio assumes no available amplification above full scale. This can intentionally make aggressive presets much quieter.
- Stereo width uses M=(L+R)/2 and S=(L−R)/2, preserving the mono sum. It applies only to stereo; mono and multichannel streams skip width. Width is limited to 0–120%, with a warning above 100%.
- A final finite-value check and PCM sample saturation protect Enhance. This is a sample-peak guard, not an oversampled true-peak limiter or a −1 dBTP guarantee. Flat Enhance is otherwise an identity operation in range.
- Settings are immutable atomic snapshots. A 20ms crossfade of complete old/new filter-bank outputs avoids coefficient discontinuities. Direct bypass begins when that transition finishes. Mixer bit-perfect preference is excluded throughout Enhance and pending transitions. Output processing is reported only after the audio callback consumes PCM.
- The steady-state render loop reuses its frame and output buffers. File access, Room, metadata parsing, and audio analysis never run there. A new filter bank allocates only when a setting/analysis revision changes.

## Loudness and Auto

ReplayGain and R128 tags exposed by Media3 VorbisComment or ID3 TXXX metadata are inspected first. ReplayGain values are dB gain at their native 89 dB reference and are never presented as measured LUFS; the LUFS target does not alter these native gains. R128 values are signed Q7.8 dB relative to the −23 LUFS reference; target offset is applied in addition to the decoder's OpusHead gain. Invalid values and missing album tags are ignored. Both are capped at +6 / −24 dB and remain subject to peak protection. Tag coverage depends on extractor metadata; unsupported tag containers fall back to cached analysis or remain pending.

Analysis is an explicit local user action, never a scan completion requirement or an automatic full-library job. A mutex limits it to one decoder. MediaExtractor/MediaCodec decode mono/stereo at 8–192 kHz on a background-priority IO worker, with cancellation points and brief yields between buffers. PCM16 and float decoder outputs are accepted. Unsupported formats fail safely and cache the error. Only the first ten minutes are read; reaching the cap is marked partial. No source file is modified or uploaded.

The streaming estimator uses the two reference K-weighting filters, rate-adjusted by a bilinear transform, 400ms windows with 75% overlap, −70 absolute and −10 relative gates. This follows the BS.1770 approach but is an **estimated LUFS**, not a certified meter. Silence or insufficient complete windows produces no loudness value. Peak is a sample peak. Full source/decoder/codec effects and official meter-conformance material have not been exhaustively validated.

Album normalization requires a complete, valid cache for every album member. It computes a duration-weighted mean energy from the per-track gated estimates, sharing one gain to retain relative track levels. This is clearly labeled an album estimate: it is not equivalent to regrouping all the raw album gating blocks. A missing/partial member leaves album gain pending. Users may explicitly analyze the current album.

Auto measures ten band-pass energy accumulators. It conservatively cuts unusually strong low/high groups by at most 2 dB relative to measured midrange energy; it does not infer genre, voice, lost information, or an EarPods target. Manual bands with at least 0.5 dB adjustment win. Missing/silent analysis applies no Auto EQ. Cached identity includes URI, size, modification time, and analyzer version; removed folder caches are invalidated. Room holds analysis and named custom EQ presets; DataStore holds mode and all settings.

## Validation and physical limits

Pure JVM tests exercise real PCM sine output, all-band ±3 dB response at 44.1/48kHz, flat identity, channel isolation, split-buffer invariance, extreme settings/NaN safety, mono width, transitions back to exact bypass, manual/Auto priority, gain limits, the 997Hz K-weight reference at two sample rates, silence/partial input, and ReplayGain/R128 units. App tests exercise the actual Media3 processor adapter, exact PCM16 bytes in Direct, changed output in Enhance, and rate/channel reconfiguration. See the build test reports for the final run results.

USB-C EarPods and Bluetooth hardware acceptance tests remain unverified when no ADB device or USB audio device is connected. Physical buttons are handled through the existing MediaSession and Android volume controls; no claim is made that every EarPods button was observed. Existing output removal handling pauses playback. AudioTrack requested PCM rate is not a measurement of the DAC clock. OS-reported mixer capabilities or a successful preferred-mixer request alone never establish end-to-end bit-perfect delivery.

References: [RBJ cookbook](https://www.w3.org/TR/audio-eq-cookbook/), [ITU-R BS.1770-5](https://www.itu.int/rec/R-REC-BS.1770-5-202311-I/en), [RFC7845 tag units](https://datatracker.ietf.org/doc/html/rfc7845#section-5.2.1), [Media3 audio processors](https://developer.android.com/media/media3/exoplayer/customization#customizing_audio_processing).
