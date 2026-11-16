#pragma once

// The single camera owner is the only writer of finalized files in this app-private directory.
// Preserve the extension and every earlier photo, including captures from different cards/folders.
static std::string unusedPhotoPath(const std::string& directory, const std::string& name) {
    const size_t dot = name.find_last_of('.');
    const std::string stem = dot == std::string::npos ? name : name.substr(0, dot);
    const std::string extension = dot == std::string::npos ? "" : name.substr(dot);
    std::string path = directory + "/" + name;
    struct stat info{};
    for (unsigned int suffix = 1; lstat(path.c_str(), &info) == 0; ++suffix) {
        path = directory + "/" + stem + " (" + std::to_string(suffix) + ")" + extension;
    }
    return path;
}
