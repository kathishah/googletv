# Video decode survey (2026-10-17)

Moved verbatim from `docs/DEVICE_TESTING.md` on 2026-10-19, with links updated for the new location.

TCL 65" A300 (MediaTek `mt5896`, Android TV 12). The panel is 3840×2160 at 60 Hz and reports Dolby Vision, HDR10, HLG and HDR10+ with a maximum luminance of 100 nits. The vendor codec tables (`/vendor/etc/media_codecs-mt5896.xml`) declare hardware H.264, HEVC, VP9 and AV1 decoders to 8K, Dolby Vision decoders to 4096×2176, and MJPEG, MPEG-4 and H.263 decoders to 1080p.

`tools/smb_access_probe/VideoDecodeProbe.kt` was copied temporarily into the app's `playback` package, built with `cd apps/screensaver && JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:assembleDebug`, pushed to the TV, and run with `CLASSPATH=/data/local/tmp/vprobe.apk app_process /system/bin com.googletv.screensaver.playback.VideoDecodeProbeKt /data/local/tmp/vsample`. The copy in the app source was removed afterwards. It printed per-clip results by index only, with no filenames or paths. The input was the smallest clip for each of the 23 distinct format combinations in the library sample; 21 were at most 200 MB and were pushed to a temporary TV folder (585 MB), then deleted along with the probe.

- **Result: all 21 clips demuxed and decoded** (up to 90 frames each) on hardware decoders: H.264 (`c2.mtk.avc`) from 160×90 to 3840×2160, HEVC (`c2.mtk.hevc`) including rotated 1080p, Dolby Vision HLG at 1280×720 and 3840×2160 (`c2.mtk.dvhe.st`), MPEG-4 Part 2, Motion JPEG (including one in an `.avi` container, which `MediaExtractor` opened), and AMR-NB, AAC and PCM audio.
- **Timing, unpaced and read from local flash:** first frame 44–433 ms for clips up to 1080p and about 1.3 s for 4K; 90 frames of 4K took 3.4–3.8 s.
- **Gaps:** one HE-AAC clip found no audio decoder in the lookup (irrelevant if playback is muted). The two largest sampled clips (a 664 MB 4K Dolby Vision `.mov` and a 250 MB SD `.m4v`) were not pushed. Not tested: surface rendering and frame pacing, rotation handling by a player, streaming from SMB, HDR output on this panel, long-clip memory behavior.
- **Streaming risk:** the measured SMB copy rate was 8.9–9.9 MB/s, about 72–79 Mbps. A 70.8 Mbps clip in the sample would leave almost no headroom, while the median clip (7.7 Mbps) would use about a tenth of it.
