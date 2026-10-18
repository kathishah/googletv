#include <libheif/heif.h>
#include <libheif/heif_tiling.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/bitmap.h>
#include <jni.h>
#endif

namespace {

struct ProbeResult {
    int width = 0;
    int height = 0;
    std::vector<uint32_t> pixels;
    std::string info;
};

std::string error_message(const heif_error& error) {
    const char* message = error.message ? error.message : "unknown libheif error";
    return std::string(message) + " (code=" + std::to_string(error.code) + ")";
}

// libheif reports where the first tile starts relative to the (rotated or cropped) image. On some
// rotated 24 MP HEICs the value it reports leaves the grid short of the image: with 5 columns of 896,
// an offset of 700 and an image 4284 wide the tiles reach only 3780, and the right-hand 12% of the
// picture came out black. The value that does cover the image is the tile size minus the reported one
// (196 here). The reported offset is kept whenever it covers the image, so images that decoded
// correctly decode exactly as before.
int64_t effective_offset(uint32_t offset, uint32_t tile, uint32_t count, uint32_t image) {
    if (offset == 0 || tile == 0) return offset;
    const int64_t total = static_cast<int64_t>(count) * tile;
    if (total - static_cast<int64_t>(offset) >= static_cast<int64_t>(image)) return offset;
    const int64_t alternative = static_cast<int64_t>(tile) - static_cast<int64_t>(offset % tile);
    if (total - alternative >= static_cast<int64_t>(image)) return alternative;
    return offset;
}

bool decode_tiles(const char* path, int target_width, int target_height, ProbeResult* result) {
    const auto started = std::chrono::steady_clock::now();
    heif_context* context = heif_context_alloc();
    if (context == nullptr) {
        result->info = "libheif context allocation failed";
        return false;
    }

    heif_image_handle* handle = nullptr;
    heif_error error = heif_context_read_from_file(context, path, nullptr);
    if (error.code != heif_error_Ok) {
        result->info = "open: " + error_message(error);
        heif_context_free(context);
        return false;
    }

    error = heif_context_get_primary_image_handle(context, &handle);
    if (error.code != heif_error_Ok || handle == nullptr) {
        result->info = "primary image: " + error_message(error);
        heif_context_free(context);
        return false;
    }

    heif_image_tiling tiling{};
    tiling.version = 1;
    error = heif_image_handle_get_image_tiling(handle, 1, &tiling);
    if (error.code != heif_error_Ok) {
        result->info = "tile metadata: " + error_message(error);
        heif_image_handle_release(handle);
        heif_context_free(context);
        return false;
    }
    if (tiling.image_width == 0 || tiling.image_height == 0 ||
        tiling.num_columns == 0 || tiling.num_rows == 0 ||
        tiling.image_width > static_cast<uint32_t>(std::numeric_limits<int>::max()) ||
        tiling.image_height > static_cast<uint32_t>(std::numeric_limits<int>::max())) {
        result->info = "invalid grid dimensions";
        heif_image_handle_release(handle);
        heif_context_free(context);
        return false;
    }

    const double scale = std::min(
        static_cast<double>(target_width) / tiling.image_width,
        static_cast<double>(target_height) / tiling.image_height);
    result->width = std::max(1, static_cast<int>(tiling.image_width * scale + 0.5));
    result->height = std::max(1, static_cast<int>(tiling.image_height * scale + 0.5));
    if (result->width > target_width) result->width = target_width;
    if (result->height > target_height) result->height = target_height;

    const size_t pixel_count = static_cast<size_t>(result->width) * result->height;
    if (pixel_count > 16 * 1024 * 1024) {
        result->info = "target image exceeds prototype allocation limit";
        heif_image_handle_release(handle);
        heif_context_free(context);
        return false;
    }
    result->pixels.assign(static_cast<size_t>(target_width) * target_height, 0xff000000u);
    const int offset_x = (target_width - result->width) / 2;
    const int offset_y = (target_height - result->height) / 2;
    const double scale_x = static_cast<double>(result->width) / tiling.image_width;
    const double scale_y = static_cast<double>(result->height) / tiling.image_height;

    const int64_t left_offset = effective_offset(
        tiling.left_offset, tiling.tile_width, tiling.num_columns, tiling.image_width);
    const int64_t top_offset = effective_offset(
        tiling.top_offset, tiling.tile_height, tiling.num_rows, tiling.image_height);

    heif_decoding_options* options = heif_decoding_options_alloc();
    if (options == nullptr) {
        result->info = "decode options allocation failed";
        heif_image_handle_release(handle);
        heif_context_free(context);
        return false;
    }
    options->ignore_transformations = 0;

    uint32_t decoded_tiles = 0;
    bool success = true;
    for (uint32_t tile_y = 0; tile_y < tiling.num_rows && success; ++tile_y) {
        for (uint32_t tile_x = 0; tile_x < tiling.num_columns; ++tile_x) {
            heif_image* tile = nullptr;
            error = heif_image_handle_decode_image_tile(
                handle, &tile, heif_colorspace_RGB, heif_chroma_interleaved_RGBA,
                options, tile_x, tile_y);
            if (error.code != heif_error_Ok || tile == nullptr) {
                result->info = "tile decode: " + error_message(error);
                success = false;
                break;
            }

            int stride = 0;
            const uint8_t* pixels = heif_image_get_plane_readonly(
                tile, heif_channel_interleaved, &stride);
            const int tile_width = heif_image_get_width(tile, heif_channel_interleaved);
            const int tile_height = heif_image_get_height(tile, heif_channel_interleaved);
            if (pixels == nullptr || stride < tile_width * 4 ||
                tile_width <= 0 || tile_height <= 0) {
                result->info = "invalid decoded tile buffer";
                heif_image_release(tile);
                success = false;
                break;
            }

            const int64_t tile_origin_x =
                static_cast<int64_t>(tile_x) * tiling.tile_width - left_offset;
            const int64_t tile_origin_y =
                static_cast<int64_t>(tile_y) * tiling.tile_height - top_offset;
            for (int y = 0; y < tile_height; ++y) {
                const int64_t source_y = tile_origin_y + y;
                if (source_y < 0 || source_y >= tiling.image_height) continue;
                int dest_y = offset_y + static_cast<int>(source_y * scale_y);
                if (dest_y < 0 || dest_y >= target_height) continue;
                const uint8_t* row = pixels + static_cast<size_t>(y) * stride;
                for (int x = 0; x < tile_width; ++x) {
                    const int64_t source_x = tile_origin_x + x;
                    if (source_x < 0 || source_x >= tiling.image_width) continue;
                    const int dest_x = offset_x + static_cast<int>(source_x * scale_x);
                    if (dest_x < 0 || dest_x >= target_width) continue;
                    const uint8_t* rgba = row + static_cast<size_t>(x) * 4;
                    result->pixels[static_cast<size_t>(dest_y) * target_width + dest_x] =
                        0xff000000u |
                        (static_cast<uint32_t>(rgba[0]) << 16) |
                        (static_cast<uint32_t>(rgba[1]) << 8) |
                        static_cast<uint32_t>(rgba[2]);
                }
            }
            heif_image_release(tile);
            ++decoded_tiles;
        }
    }

    heif_decoding_options_free(options);
    if (success) {
        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started).count();
        result->info = "source=" + std::to_string(tiling.image_width) + "x" +
            std::to_string(tiling.image_height) +
            " grid=" + std::to_string(tiling.num_columns) + "x" +
            std::to_string(tiling.num_rows) +
            " tiles=" + std::to_string(decoded_tiles) +
            " tileSize=" + std::to_string(tiling.tile_width) + "x" +
            std::to_string(tiling.tile_height) +
            " output=" + std::to_string(target_width) + "x" +
            std::to_string(target_height) +
            " offsets=" + std::to_string(tiling.left_offset) + "," + std::to_string(tiling.top_offset) +
            " used=" + std::to_string(left_offset) + "," + std::to_string(top_offset) +
            " content=" + std::to_string(result->width) + "x" +
            std::to_string(result->height) + "+" + std::to_string(offset_x) +
            "+" + std::to_string(offset_y) +
            " elapsedMs=" + std::to_string(elapsed);
    }

    heif_image_handle_release(handle);
    heif_context_free(context);
    return success;
}

}  // namespace

#ifdef __ANDROID__

static std::string g_last_info;

extern "C" JNIEXPORT jstring JNICALL
Java_com_googletv_screensaver_playback_NativeHeifDecoder_decodeInto(
    JNIEnv* env, jobject, jstring path, jobject bitmap) {
    if (path == nullptr || bitmap == nullptr) {
        return env->NewStringUTF("FAIL invalid input");
    }

    AndroidBitmapInfo bitmap_info{};
    if (AndroidBitmap_getInfo(env, bitmap, &bitmap_info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmap_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        bitmap_info.width == 0 || bitmap_info.height == 0 ||
        static_cast<int64_t>(bitmap_info.width) * bitmap_info.height > 16 * 1024 * 1024) {
        return env->NewStringUTF("FAIL unsupported bitmap");
    }

    const char* utf_path = env->GetStringUTFChars(path, nullptr);
    if (utf_path == nullptr) return env->NewStringUTF("FAIL input path unavailable");

    ProbeResult result;
    bool success = false;
    try {
        success = decode_tiles(utf_path, bitmap_info.width, bitmap_info.height, &result);
    } catch (...) {
        result.info = "native allocation or decoder exception";
    }
    env->ReleaseStringUTFChars(path, utf_path);
    if (!success) {
        return env->NewStringUTF(("FAIL " + result.info).c_str());
    }

    void* bitmap_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &bitmap_pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmap_pixels == nullptr) {
        return env->NewStringUTF("FAIL could not lock output bitmap");
    }
    auto* output = static_cast<uint8_t*>(bitmap_pixels);
    for (uint32_t y = 0; y < bitmap_info.height; ++y) {
        auto* output_row = output + static_cast<size_t>(y) * bitmap_info.stride;
        const auto* input_row = result.pixels.data() +
            static_cast<size_t>(y) * bitmap_info.width;
        for (uint32_t x = 0; x < bitmap_info.width; ++x) {
            const uint32_t argb = input_row[x];
            output_row[x * 4 + 0] = static_cast<uint8_t>((argb >> 16) & 0xff);
            output_row[x * 4 + 1] = static_cast<uint8_t>((argb >> 8) & 0xff);
            output_row[x * 4 + 2] = static_cast<uint8_t>(argb & 0xff);
            output_row[x * 4 + 3] = 0xff;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return env->NewStringUTF(("PASS " + result.info).c_str());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_googletv_heiftileprobe_HeifTileProbeActivity_nativeDecode(
    JNIEnv* env, jobject, jstring path, jint target_width, jint target_height) {
    if (path == nullptr || target_width <= 0 || target_height <= 0 ||
        static_cast<int64_t>(target_width) * target_height > 16 * 1024 * 1024) {
        g_last_info = "invalid input or target dimensions";
        return nullptr;
    }
    const char* utf_path = env->GetStringUTFChars(path, nullptr);
    if (utf_path == nullptr) return nullptr;

    ProbeResult result;
    bool success = false;
    try {
        success = decode_tiles(utf_path, target_width, target_height, &result);
    } catch (...) {
        result.info = "native allocation or decoder exception";
    }
    env->ReleaseStringUTFChars(path, utf_path);
    g_last_info = result.info;
    if (!success) return nullptr;

    jintArray pixels = env->NewIntArray(target_width * target_height);
    if (pixels == nullptr) {
        g_last_info = "JNI pixel allocation failed";
        return nullptr;
    }
    env->SetIntArrayRegion(pixels, 0, target_width * target_height,
        reinterpret_cast<const jint*>(result.pixels.data()));
    return pixels;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_googletv_heiftileprobe_HeifTileProbeActivity_nativeProbe(
    JNIEnv* env, jobject, jstring path, jint target_width, jint target_height) {
    if (path == nullptr || target_width <= 0 || target_height <= 0 ||
        static_cast<int64_t>(target_width) * target_height > 16 * 1024 * 1024) {
        return env->NewStringUTF("FAIL invalid input or target dimensions");
    }
    const char* utf_path = env->GetStringUTFChars(path, nullptr);
    if (utf_path == nullptr) return env->NewStringUTF("FAIL input path unavailable");

    ProbeResult result;
    bool success = false;
    try {
        success = decode_tiles(utf_path, target_width, target_height, &result);
    } catch (...) {
        result.info = "native allocation or decoder exception";
    }
    env->ReleaseStringUTFChars(path, utf_path);
    const std::string summary = std::string(success ? "PASS " : "FAIL ") + result.info;
    return env->NewStringUTF(summary.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_googletv_heiftileprobe_HeifTileProbeActivity_nativeLastInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_last_info.c_str());
}

#else

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: heif_tile_probe INPUT.heic [MAX_WIDTH MAX_HEIGHT]\n");
        return 2;
    }
    const int target_width = argc > 2 ? std::stoi(argv[2]) : 1920;
    const int target_height = argc > 3 ? std::stoi(argv[3]) : 1080;
    ProbeResult result;
    if (!decode_tiles(argv[1], target_width, target_height, &result)) {
        std::fprintf(stderr, "decode failed: %s\n", result.info.c_str());
        return 1;
    }
    std::string output = std::string(argv[1]) + ".ppm";
    FILE* file = std::fopen(output.c_str(), "wb");
    if (file == nullptr) {
        std::perror("open output");
        return 1;
    }
    std::fprintf(file, "P6\n%d %d\n255\n", target_width, target_height);
    for (uint32_t pixel : result.pixels) {
        const uint8_t rgb[] = {
            static_cast<uint8_t>((pixel >> 16) & 0xff),
            static_cast<uint8_t>((pixel >> 8) & 0xff),
            static_cast<uint8_t>(pixel & 0xff),
        };
        std::fwrite(rgb, sizeof(rgb), 1, file);
    }
    std::fclose(file);
    std::printf("%s\n", result.info.c_str());
    std::printf("wrote %s\n", output.c_str());
    return 0;
}

#endif
