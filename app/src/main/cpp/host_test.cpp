#include "rife_engine.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iostream>
#include <limits>
#include <vector>

int main(int argc, char** argv) {
    if (argc < 3) { std::cerr << "Usage: rife_host_test model-dir output-prefix [vulkan]\n"; return 2; }
    try {
        // Odd dimensions exercise real right/bottom padding and row cropping.
        constexpr int w = 65, h = 49;
        std::vector<uint8_t> a(w*h*3), b(w*h*3), out(w*h*3), quarter(w*h*3);
        for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) for (int c = 0; c < 3; ++c) {
            const int i = (y*w+x)*3+c;
            a[i] = (x > 12 && x < 32 && y > 8 && y < 40) ? (c == 0 ? 230 : 70) : 20 + (x+y)%20;
            b[i] = (x > 28 && x < 48 && y > 8 && y < 40) ? (c == 0 ? 230 : 70) : 20 + (x+y)%20;
        }
        const auto start = std::chrono::steady_clock::now();
        RifeEngine engine(argv[1], argc > 3);
        engine.interpolate(a.data(), b.data(), out.data(), w, h, .5f);
        engine.interpolate(a.data(), b.data(), quarter.data(), w, h, .25f);
        long differs_blend = 0, differs_input = 0, differs_time = 0;
        for (size_t i=0; i<out.size(); ++i) {
            differs_blend += std::abs(int(out[i]) - (int(a[i])+b[i])/2) > 3;
            differs_input += out[i] != a[i];
            differs_time += out[i] != quarter[i];
        }
        if (differs_blend < 100 || differs_input < 100 || differs_time < 100) throw std::runtime_error("Expected model output differs from input/blend and responds to arbitrary timestep");
        for (auto pair : {std::make_pair("-input0.ppm", &a), {"-input1.ppm", &b}, {"-midpoint.ppm", &out}, {"-quarter.ppm", &quarter}}) {
            std::ofstream file(std::string(argv[2]) + pair.first, std::ios::binary);
            file << "P6\n" << w << " " << h << "\n255\n";
            file.write(reinterpret_cast<const char*>(pair.second->data()), pair.second->size());
        }
        engine.interpolate(a.data(), b.data(), out.data(), w, h, 0.f);
        if (a != out) throw std::runtime_error("Timestep zero mismatch");
        engine.interpolate(a.data(), b.data(), out.data(), w, h, 1.f);
        if (b != out) throw std::runtime_error("Timestep one mismatch");
        bool rejected = false;
        try { engine.interpolate(a.data(), b.data(), out.data(), w, h, std::numeric_limits<float>::quiet_NaN()); }
        catch (const std::invalid_argument&) { rejected = true; }
        if (!rejected) throw std::runtime_error("Non-finite timestep was not rejected");
        std::cout << "PASS real RIFE inference; backend=" << engine.device_name()
                  << "; shape=65x49; t=.25,.5; differing_blend_values=" << differs_blend
                  << "; differing_timestep_values=" << differs_time << "; elapsed_ms="
                  << std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count() << "\n";
        return 0;
    } catch (const std::exception& error) { std::cerr << "FAIL " << error.what() << "\n"; return 1; }
}
