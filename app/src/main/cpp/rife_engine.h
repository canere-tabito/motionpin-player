#pragma once

#include <cstdint>
#include <string>
#include "net.h"

// Fixed RIFE v4.6 graph. CPU is exposed only by the host validation executable.
class RifeEngine {
public:
    explicit RifeEngine(const std::string& model_dir, bool require_vulkan = true);
    void interpolate(const uint8_t* first, const uint8_t* second, uint8_t* output,
                     int width, int height, float timestep);
    const std::string& device_name() const { return device_name_; }
private:
    ncnn::Net net_;
    std::string device_name_;
};
