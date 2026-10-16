// MotionPin adaptation of nihui/rife-ncnn-vulkan's v4 path (MIT).
// Uses the exact graph and Warp layer; adds checked errors, FP32 and correct
// row-stride cropping. No desktop CLI, image decoder or image resizing is used.
#include "rife_engine.h"
#include "rife_ops.h"
#include "gpu.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <mutex>
#include <stdexcept>

DEFINE_LAYER_CREATOR(Warp)

namespace {
void checked(int result, const char* operation) {
    if (result != 0) throw std::runtime_error(std::string(operation) + " failed (ncnn/Vulkan " + std::to_string(result) + ")");
}

ncnn::Mat prepare(const uint8_t* rgb, int w, int h, int pw, int ph) {
    ncnn::Mat padded(pw, ph, 3);
    if (padded.empty()) throw std::bad_alloc();
    padded.fill(0.f); // Same right/bottom zero padding as upstream v4.
    for (int q = 0; q < 3; ++q) {
        ncnn::Mat channel = padded.channel(q);
        for (int y = 0; y < h; ++y) {
            float* row = channel.row(y);
            for (int x = 0; x < w; ++x) row[x] = rgb[(static_cast<size_t>(y) * w + x) * 3 + q] / 255.f;
        }
    }
    return padded;
}
}

RifeEngine::RifeEngine(const std::string& model_dir, bool require_vulkan) {
    net_.opt.num_threads = 2;
    net_.opt.use_fp16_packed = false;
    net_.opt.use_fp16_storage = false;
    net_.opt.use_fp16_arithmetic = false;
    net_.opt.use_bf16_storage = false;
    net_.opt.use_int8_storage = false;
    net_.opt.use_int8_arithmetic = false;
    net_.opt.use_image_storage = false;
    net_.opt.use_vulkan_compute = require_vulkan;
    if (require_vulkan) {
        // One process-wide Vulkan instance. Engine close frees models/allocators;
        // instance teardown is intentionally deferred until process termination.
        static std::once_flag instance_once;
        std::call_once(instance_once, [] { checked(ncnn::create_gpu_instance(), "Vulkan initialization"); });
        if (ncnn::get_gpu_count() < 1) throw std::runtime_error("No Vulkan compute device; AI stopped without CPU fallback");
        const int gpu = ncnn::get_default_gpu_index();
        if (gpu < 0 || !ncnn::get_gpu_device(gpu)) throw std::runtime_error("Vulkan device unavailable");
        net_.set_vulkan_device(gpu);
        device_name_ = ncnn::get_gpu_info(gpu).device_name();
    } else {
        device_name_ = "CPU host validation only";
    }
    checked(net_.register_custom_layer("rife.Warp", Warp_layer_creator), "Warp registration");
    checked(net_.load_param((model_dir + "/flownet.param").c_str()), "RIFE graph load");
    checked(net_.load_model((model_dir + "/flownet.bin").c_str()), "RIFE weights load");
    if (require_vulkan && !net_.opt.use_vulkan_compute) throw std::runtime_error("ncnn disabled Vulkan; refusing silent fallback");
}

void RifeEngine::interpolate(const uint8_t* first, const uint8_t* second, uint8_t* output,
                             int width, int height, float timestep) {
    if (!first || !second || !output || width <= 0 || height <= 0 || width > 16384 || height > 16384 ||
        !std::isfinite(timestep) || timestep < 0.f || timestep > 1.f)
        throw std::invalid_argument("Invalid RGB8 frame dimensions or timestep");
    const size_t bytes = static_cast<size_t>(width) * height * 3;
    if (timestep == 0.f || timestep == 1.f) {
        std::memcpy(output, timestep == 0.f ? first : second, bytes);
        return;
    }
    const int pw = (width + 31) / 32 * 32;
    const int ph = (height + 31) / 32 * 32;
    ncnn::Mat in0 = prepare(first, width, height, pw, ph);
    ncnn::Mat in1 = prepare(second, width, height, pw, ph);
    ncnn::Mat time(pw, ph, 1);
    if (time.empty()) throw std::bad_alloc();
    time.fill(timestep);
    ncnn::Extractor extractor = net_.create_extractor();
    checked(extractor.input("in0", in0), "First frame input");
    checked(extractor.input("in1", in1), "Second frame input");
    checked(extractor.input("in2", time), "Timestep input");
    ncnn::Mat interpolated;
    // With use_vulkan_compute, ncnn uploads, runs Vulkan and reads back here.
    // setup-native.sh patches upstream's ignored submit failures to propagate.
    checked(extractor.extract("out0", interpolated), "RIFE inference");
    if (interpolated.empty() || interpolated.w != pw || interpolated.h != ph ||
        interpolated.c != 3 || interpolated.elempack != 1 || interpolated.elemsize != 4)
        throw std::runtime_error("RIFE output shape/precision violated the RGB FP32 contract");
    for (int q = 0; q < 3; ++q) {
        const ncnn::Mat channel = interpolated.channel(q);
        for (int y = 0; y < height; ++y) {
            const float* row = channel.row(y);
            for (int x = 0; x < width; ++x) {
                if (!std::isfinite(row[x])) throw std::runtime_error("RIFE produced a non-finite pixel");
                output[(static_cast<size_t>(y) * width + x) * 3 + q] =
                    static_cast<uint8_t>(std::clamp(row[x] * 255.f + 0.5f, 0.f, 255.f));
            }
        }
    }
}
