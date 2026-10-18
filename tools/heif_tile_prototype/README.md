# HEIF grid tile prototype

The diagnostic app exercises libheif's per-tile API with a software HEVC decoder. The same pinned native decoder is linked into the screensaver app for large HEIF images. The production SMB-to-native path has been verified on the TV with landscape and portrait 24 MP photos. The probe remains useful for isolated decoder diagnostics.

## Build

From `apps/screensaver/`:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :heif-probe:assembleDebug
```

The Android native build fetches pinned libheif 1.23.5 and libde265 1.1.3 sources, compiling HEVC decode support for the TCL TV's 32-bit `armeabi-v7a` ABI. The first build requires network access.

## Host smoke check

The same C++ decoder can be built against a local libheif installation on macOS:

```sh
cmake -S tools/heif_tile_prototype -B /tmp/heif-tile-host-build -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/heif-tile-host-build --parallel 4
/tmp/heif-tile-host-build/heif_tile_probe /path/to/sample.heic 1920 1080
```

On 2026-10-14, a synthetic 5712×4284 HEIC grid with 45 HEVC tiles (640×896 each) decoded and assembled successfully on the host in 109 ms. A second synthetic file with a 90° image transform also decoded all 45 tiles. These checks exercise grid assembly and transformed coordinates on the development Mac.

## Run on a TV

Copy one readable 24 MP HEIC sample into the probe app's private files directory, then launch its activity:

```sh
adb push /tmp/sample.heic /data/local/tmp/heif-probe-sample.heic
adb shell run-as com.googletv.heiftileprobe mkdir -p files
adb shell run-as com.googletv.heiftileprobe cp /data/local/tmp/heif-probe-sample.heic files/sample.heic
adb shell am start -n com.googletv.heiftileprobe/.HeifTileProbeActivity
```

The activity decodes the HEIF grid tile by tile into a 1920×1080 preview buffer. It reports source dimensions, tile geometry, output dimensions, and elapsed time. It never logs the sample path or photo metadata.

On 2026-10-15, the probe ran on the TCL 65" A300 / Android TV 12 (API 31) with both synthetic grids. It decoded all 45 tiles in 2.5 seconds for the untransformed grid and 2.4 seconds for the 90° transformed grid; screenshots showed the expected grid order and transformed orientation.

The same day, the probe decoded one real 5712×4284 HEIC selected from a targeted first-25 filesystem walk. The read-only SMB Guest mount succeeded even though explicit Guest share enumeration showed only `IPC$`. libheif reported the transformed image as 4284×5712 with a 5×9 grid of 45 896×640 tiles. The TV assembled a 1920×1080 preview in 6,075 ms, and the user confirmed it looked correct and upright.

Then 100 real HEICs at 24 MP or higher were selected across the library's landscape, portrait, and wide/panoramic dimension groups and decoded on the TV: 100/100 passed. The set comprised 47 landscape (5712×4284), 44 portrait (4284×5712), and 9 wide images. The middle decode time was 6.885 s, p95 was 10.457 s, and maximum was 13.303 s. A 15300×3770 (57.6 MP) wide image decoded in 13.212 s. The automated batch runner stages one file at a time, suppresses source paths, and reports dimensions and timings only:

```sh
python3 tools/heif_tile_prototype/run_tv_batch.py /path/to/read-only-smb-mount --count 100
```

For a TV display preview (defaults to six samples at 12 seconds each), run:

```sh
python3 tools/heif_tile_prototype/run_tv_preview.py /path/to/read-only-smb-mount --count 6 --seconds 12
```

The six-image preview was run after the batch and the user confirmed the images were correctly framed and upright. The staged source file and probe app were removed afterward, and the production app was restored. The batch result establishes broad decoder coverage, and the integrated app path has been verified with two real 24 MP orientations on the TV. Memory use under sustained playback, color fidelity, and broader transform coverage remain open.

Temporary sample files should be removed from `/data/local/tmp` and the probe app's private files after the run.

## Result line and tile offsets (2026-10-18)

The decoder's result line now ends with `offsets=L,T used=L,T content=WxH+X+Y`. `content` is where the picture sits inside the target-sized canvas (centered, black around it); the app crops to it so a portrait photo is a portrait bitmap. `offsets` is what libheif reported for the first tile and `used` is what the decoder applied. On some rotated 24 MP HEICs libheif's offset leaves the tile grid short of the image (5 columns of 896 with an offset of 700 reach 3780 px of a 4284 px image), so the right or bottom 9 to 12% of the picture came out black. When the reported offset does not cover the image the decoder uses the tile size minus the offset (196 in that example, 48 for a top offset of 592), which does; an offset that covers the image is used as reported, so pictures that decoded correctly are unchanged. Seen on the TCL with two 4284x5712 photos; the rule is a heuristic and is not yet checked against a wider set.
