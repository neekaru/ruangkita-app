// c++ -std=c++17 camera-paths-test.cpp -o camera-paths-test && ./camera-paths-test
#include <cassert>
#include <cstdlib>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#ifdef _WIN32
#define lstat stat
#endif
#include "../../main/cpp/camera-paths.h"

int main() {
    const std::string directory = (std::filesystem::temp_directory_path() /
        ("ruangfoto-paths-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()))).string();
    assert(std::filesystem::create_directory(directory));
    const std::string first = unusedPhotoPath(directory, "DSC0001.ARW");
    std::ofstream(first) << "original";
    const std::string second = unusedPhotoPath(directory, "DSC0001.ARW");
    assert(second != first && second.substr(second.size() - 4) == ".ARW");
    std::ofstream(second) << "another card";
    const std::string third = unusedPhotoPath(directory, "DSC0001.ARW");
    assert(third != first && third != second);
    std::ifstream original(first);
    std::string text;
    original >> text;
    assert(text == "original");
    original.close();
    std::filesystem::remove_all(directory);
}
