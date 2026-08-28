#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

namespace {

constexpr int kMaxFrames = 16;
constexpr int kSearchRadius = 16;
constexpr int kSearchStep = 2; // Keep Bayer parity stable.
constexpr int kSampleStep = 16;

inline int parity_index(int x, int y) {
    return ((y & 1) << 1) | (x & 1);
}

inline float clampf(float v, float lo, float hi) {
    return std::max(lo, std::min(hi, v));
}

inline uint16_t read_u16(const uint16_t* p, int width, int x, int y) {
    return p[static_cast<size_t>(y) * width + x];
}

float normalized_signal(
    uint16_t raw,
    int x,
    int y,
    const int* black,
    int white,
    double exposure_scale
) {
    const float b = static_cast<float>(black[parity_index(x, y)]);
    const float range = std::max(1.0f, static_cast<float>(white) - b);
    const float signal = std::max(0.0f, static_cast<float>(raw) - b);
    return static_cast<float>((signal / range) * exposure_scale);
}

float sharpness_score(
    const uint16_t* frame,
    int width,
    int height,
    const int* black,
    int white,
    double exposure_scale
) {
    if (width < 8 || height < 8) return 0.0f;
    double sum = 0.0;
    double count = 0.0;
    const int margin = 8;
    for (int y = margin; y < height - margin; y += kSampleStep) {
        for (int x = margin; x < width - margin; x += kSampleStep) {
            const float c = normalized_signal(read_u16(frame, width, x, y), x, y, black, white, exposure_scale);
            const float rx = normalized_signal(read_u16(frame, width, x + 2, y), x + 2, y, black, white, exposure_scale);
            const float by = normalized_signal(read_u16(frame, width, x, y + 2), x, y + 2, black, white, exposure_scale);
            sum += std::abs(c - rx) + std::abs(c - by);
            count += 2.0;
        }
    }
    return count > 0.0 ? static_cast<float>(sum / count) : 0.0f;
}

struct Alignment {
    int dx = 0;
    int dy = 0;
    float cost = 0.0f;
    bool accepted = true;
};

Alignment align_even_translation(
    const uint16_t* reference,
    const uint16_t* candidate,
    int width,
    int height,
    const int* black,
    int white,
    double candidate_to_reference_scale
) {
    Alignment best;
    best.cost = std::numeric_limits<float>::infinity();
    const int margin = kSearchRadius + 4;

    for (int dy = -kSearchRadius; dy <= kSearchRadius; dy += kSearchStep) {
        for (int dx = -kSearchRadius; dx <= kSearchRadius; dx += kSearchStep) {
            double cost = 0.0;
            int samples = 0;
            for (int y = margin; y < height - margin; y += kSampleStep) {
                const int cy = y + dy;
                for (int x = margin; x < width - margin; x += kSampleStep) {
                    const int cx = x + dx;
                    const float ref = normalized_signal(read_u16(reference, width, x, y), x, y, black, white, 1.0);
                    const float cand = normalized_signal(read_u16(candidate, width, cx, cy), cx, cy, black, white, candidate_to_reference_scale);
                    const float d = std::abs(ref - cand);
                    // Charbonnier-like robust matching cost.
                    cost += std::sqrt(static_cast<double>(d * d) + 1e-6);
                    ++samples;
                }
            }
            const float mean = samples > 0 ? static_cast<float>(cost / samples) : std::numeric_limits<float>::infinity();
            if (mean < best.cost) {
                best = Alignment{dx, dy, mean, true};
            }
        }
    }
    // Conservative global-motion gate. Outliers are discarded rather than ghosted.
    best.accepted = std::isfinite(best.cost) && best.cost < 0.10f;
    return best;
}

float median_small(std::array<float, kMaxFrames>& values, int n) {
    std::sort(values.begin(), values.begin() + n);
    if (n <= 0) return 0.0f;
    if ((n & 1) != 0) return values[n / 2];
    return 0.5f * (values[n / 2 - 1] + values[n / 2]);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_sahidcode404_camera_core_raw_NativeRawMerger_nativeMergePackedRaw(
    JNIEnv* env,
    jclass,
    jobjectArray frame_buffers,
    jint width,
    jint height,
    jlongArray exposure_ns_array,
    jintArray iso_array,
    jintArray black_array,
    jint white_level,
    jint /*cfa*/,
    jobject output_buffer,
    jfloatArray diagnostics_array
) {
    if (!frame_buffers || !exposure_ns_array || !iso_array || !black_array || !output_buffer) return -1;
    const int frame_count = env->GetArrayLength(frame_buffers);
    if (frame_count < 1 || frame_count > kMaxFrames || width <= 0 || height <= 0) return -2;
    if (env->GetArrayLength(exposure_ns_array) != frame_count || env->GetArrayLength(iso_array) != frame_count) return -3;
    if (env->GetArrayLength(black_array) < 4) return -4;

    const size_t pixel_count = static_cast<size_t>(width) * height;
    const jlong output_capacity = env->GetDirectBufferCapacity(output_buffer);
    if (output_capacity < static_cast<jlong>(pixel_count * sizeof(uint16_t))) return -5;
    auto* output = reinterpret_cast<uint16_t*>(env->GetDirectBufferAddress(output_buffer));
    if (!output) return -6;

    std::array<const uint16_t*, kMaxFrames> frames{};
    for (int i = 0; i < frame_count; ++i) {
        jobject buffer = env->GetObjectArrayElement(frame_buffers, i);
        if (!buffer) return -7;
        const jlong capacity = env->GetDirectBufferCapacity(buffer);
        auto* ptr = reinterpret_cast<const uint16_t*>(env->GetDirectBufferAddress(buffer));
        env->DeleteLocalRef(buffer);
        if (!ptr || capacity < static_cast<jlong>(pixel_count * sizeof(uint16_t))) return -8;
        frames[i] = ptr;
    }

    jboolean exposure_copy = JNI_FALSE;
    jboolean iso_copy = JNI_FALSE;
    jboolean black_copy = JNI_FALSE;
    const jlong* exposure_ns = env->GetLongArrayElements(exposure_ns_array, &exposure_copy);
    const jint* iso = env->GetIntArrayElements(iso_array, &iso_copy);
    const jint* black_j = env->GetIntArrayElements(black_array, &black_copy);
    if (!exposure_ns || !iso || !black_j) return -9;
    int black[4] = {black_j[0], black_j[1], black_j[2], black_j[3]};
    const int white = std::max(1, static_cast<int>(white_level));

    std::array<double, kMaxFrames> energy{};
    for (int i = 0; i < frame_count; ++i) {
        energy[i] = std::max(1.0, static_cast<double>(std::max<jlong>(1, exposure_ns[i])) * std::max(1, static_cast<int>(iso[i])));
    }

    // Select the sharpest reasonably exposed reference. Sharpness is normalized to each frame's own exposure.
    int reference_index = 0;
    float best_sharpness = -1.0f;
    for (int i = 0; i < frame_count; ++i) {
        const float score = sharpness_score(frames[i], width, height, black, white, 1.0);
        if (score > best_sharpness) {
            best_sharpness = score;
            reference_index = i;
        }
    }

    std::array<Alignment, kMaxFrames> alignments{};
    int accepted_count = 1;
    double accepted_cost_sum = 0.0;
    for (int i = 0; i < frame_count; ++i) {
        if (i == reference_index) {
            alignments[i] = Alignment{0, 0, 0.0f, true};
            continue;
        }
        const double scale = energy[reference_index] / energy[i];
        alignments[i] = align_even_translation(frames[reference_index], frames[i], width, height, black, white, scale);
        if (alignments[i].accepted) {
            ++accepted_count;
            accepted_cost_sum += alignments[i].cost;
        }
    }

    // Merge in reference-exposure RAW units. CFA parity is preserved because alignment offsets are even.
    std::array<float, kMaxFrames> values{};
    std::array<float, kMaxFrames> sorted{};
    std::array<float, kMaxFrames> deviations{};
    std::array<float, kMaxFrames> weights{};

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int n = 0;
            for (int i = 0; i < frame_count; ++i) {
                if (!alignments[i].accepted) continue;
                const int sx = x + alignments[i].dx;
                const int sy = y + alignments[i].dy;
                if (sx < 0 || sx >= width || sy < 0 || sy >= height) continue;

                const int black_level = black[parity_index(sx, sy)];
                const float raw = static_cast<float>(read_u16(frames[i], width, sx, sy));
                const float signal = std::max(0.0f, raw - black_level);
                const float source_range = std::max(1.0f, static_cast<float>(white - black_level));
                const float source_norm = clampf(signal / source_range, 0.0f, 1.0f);
                const double exposure_scale = energy[reference_index] / energy[i];
                const float ref_signal = static_cast<float>(signal * exposure_scale);

                const float shadow_confidence = clampf(source_norm / 0.04f, 0.0f, 1.0f);
                const float highlight_confidence = clampf((1.0f - source_norm) / 0.12f, 0.0f, 1.0f);
                const float motion_confidence = 1.0f / (1.0f + 12.0f * alignments[i].cost);
                const float noise_confidence = clampf(static_cast<float>(std::sqrt(std::max(0.1, energy[i] / energy[reference_index]))), 0.35f, 2.0f);

                values[n] = ref_signal;
                sorted[n] = ref_signal;
                weights[n] = std::max(0.01f, shadow_confidence * highlight_confidence * motion_confidence * noise_confidence);
                ++n;
            }

            const int out_black = black[parity_index(x, y)];
            if (n == 0) {
                output[static_cast<size_t>(y) * width + x] = read_u16(frames[reference_index], width, x, y);
                continue;
            }

            const float median = median_small(sorted, n);
            for (int i = 0; i < n; ++i) deviations[i] = std::abs(values[i] - median);
            std::array<float, kMaxFrames> deviation_sorted = deviations;
            const float mad = median_small(deviation_sorted, n);
            const float residual_gate = std::max(48.0f, 4.5f * mad);

            double weighted_sum = 0.0;
            double weight_sum = 0.0;
            for (int i = 0; i < n; ++i) {
                if (std::abs(values[i] - median) > residual_gate) continue;
                weighted_sum += static_cast<double>(values[i]) * weights[i];
                weight_sum += weights[i];
            }
            const float merged_signal = weight_sum > 0.0 ? static_cast<float>(weighted_sum / weight_sum) : median;
            const int merged = static_cast<int>(std::lround(merged_signal + out_black));
            output[static_cast<size_t>(y) * width + x] = static_cast<uint16_t>(std::clamp(merged, 0, white));
        }
    }

    if (diagnostics_array && env->GetArrayLength(diagnostics_array) >= 4) {
        const jfloat diagnostics[4] = {
            static_cast<jfloat>(reference_index),
            static_cast<jfloat>(accepted_count),
            static_cast<jfloat>(accepted_count > 1 ? accepted_cost_sum / (accepted_count - 1) : 0.0),
            best_sharpness,
        };
        env->SetFloatArrayRegion(diagnostics_array, 0, 4, diagnostics);
    }

    env->ReleaseLongArrayElements(exposure_ns_array, const_cast<jlong*>(exposure_ns), JNI_ABORT);
    env->ReleaseIntArrayElements(iso_array, const_cast<jint*>(iso), JNI_ABORT);
    env->ReleaseIntArrayElements(black_array, const_cast<jint*>(black_j), JNI_ABORT);
    return accepted_count;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_sahidcode404_camera_core_raw_NativeRawMerger_nativeRenderRawPreview(
    JNIEnv* env,
    jclass,
    jobject raw_buffer,
    jint width,
    jint height,
    jint row_stride,
    jint pixel_stride,
    jint out_width,
    jint out_height,
    jintArray black_array,
    jint white_level,
    jint /*cfa*/
) {
    if (!raw_buffer || !black_array || width <= 0 || height <= 0 || out_width <= 0 || out_height <= 0) return nullptr;
    const auto* bytes = reinterpret_cast<const uint8_t*>(env->GetDirectBufferAddress(raw_buffer));
    if (!bytes) return nullptr;
    jboolean is_copy = JNI_FALSE;
    const jint* black_j = env->GetIntArrayElements(black_array, &is_copy);
    if (!black_j || env->GetArrayLength(black_array) < 4) return nullptr;
    const int black[4] = {black_j[0], black_j[1], black_j[2], black_j[3]};
    const int white = std::max(1, static_cast<int>(white_level));

    std::vector<jint> pixels(static_cast<size_t>(out_width) * out_height);
    for (int oy = 0; oy < out_height; ++oy) {
        int sy = static_cast<int>((static_cast<int64_t>(oy) * height) / out_height);
        sy = std::clamp(sy, 0, height - 1);
        for (int ox = 0; ox < out_width; ++ox) {
            int sx = static_cast<int>((static_cast<int64_t>(ox) * width) / out_width);
            sx = std::clamp(sx, 0, width - 1);
            const uint8_t* p = bytes + static_cast<size_t>(sy) * row_stride + static_cast<size_t>(sx) * pixel_stride;
            const uint16_t raw = static_cast<uint16_t>(p[0] | (static_cast<uint16_t>(p[1]) << 8));
            const int b = black[parity_index(sx, sy)];
            const float linear = clampf((static_cast<float>(raw) - b) / std::max(1.0f, static_cast<float>(white - b)), 0.0f, 1.0f);
            const int v = static_cast<int>(255.0f * std::pow(linear, 1.0f / 2.2f));
            pixels[static_cast<size_t>(oy) * out_width + ox] = static_cast<jint>(0xff000000u | (v << 16) | (v << 8) | v);
        }
    }

    env->ReleaseIntArrayElements(black_array, const_cast<jint*>(black_j), JNI_ABORT);
    jintArray result = env->NewIntArray(static_cast<jsize>(pixels.size()));
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(pixels.size()), pixels.data());
    return result;
}
