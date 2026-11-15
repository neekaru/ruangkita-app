#pragma once

static std::string cameraError(int code) {
    return std::string(gp_result_as_string(code)) + " (" + std::to_string(code) + ")";
}
static std::string browserListing(Camera* camera, GPContext* context, const std::string& folder) {
    std::string entries = "[";
    bool first = true;
    for (bool directories : {true, false}) {
        CameraList* raw = nullptr;
        int ret = gp_list_new(&raw);
        if (ret < GP_OK) return "{\"done\":true,\"error\":" + quoteJson(cameraError(ret)) + "}";
        std::unique_ptr<CameraList, decltype(&gp_list_free)> list(raw, gp_list_free);
        ret = directories ? gp_camera_folder_list_folders(camera, folder.c_str(), raw, context)
                          : gp_camera_folder_list_files(camera, folder.c_str(), raw, context);
        if (ret < GP_OK) return "{\"done\":true,\"error\":" + quoteJson(cameraError(ret)) + "}";
        const int count = gp_list_count(raw);
        for (int i = 0; i < count; ++i) {
            if (cameraOperationCancelled()) return "{\"done\":true,\"error\":\"Cancelled\"}";
            const char* name = nullptr;
            ret = gp_list_get_name(raw, i, &name);
            if (ret < GP_OK || !name) continue;
            if (!safeCameraName(name)) continue;
            if (!first) entries += ',';
            first = false;
            entries += "{\"name\":" + quoteJson(name) + ",\"directory\":" + (directories ? "true" : "false") + "}";
        }
    }
    return "{\"done\":true,\"entries\":" + entries + "]}";
}
static std::string browserPreview(Camera* camera, GPContext* context, CameraRequest& request) {
    CameraFileInfo info{};
    int ret = gp_camera_file_get_info(camera, request.folder.c_str(), request.name.c_str(), &info, context);
    int64_t size = ret >= GP_OK && (info.file.fields & GP_FILE_INFO_SIZE) ? static_cast<int64_t>(info.file.size) : -1;
    std::string note;
    CameraFile* raw = nullptr;
    if (ret >= GP_OK && (info.preview.fields & GP_FILE_INFO_SIZE) && info.preview.size > 8 * 1024 * 1024) {
        note = "Preview exceeds 8 MB; metadata only";
    } else if ((ret = gp_file_new(&raw)) >= GP_OK && raw) {
        std::unique_ptr<CameraFile, decltype(&gp_file_free)> file(raw, gp_file_free);
        ret = gp_camera_file_get(camera, request.folder.c_str(), request.name.c_str(), GP_FILE_TYPE_PREVIEW, raw, context);
        if (ret >= GP_OK) {
            const char* bytes = nullptr;
            unsigned long length = 0;
            ret = gp_file_get_data_and_size(raw, &bytes, &length);
            if (ret >= GP_OK && bytes && length <= 8 * 1024 * 1024) request.preview.assign(bytes, bytes + length);
            else note = "Preview unavailable or too large";
        } else note = "Preview unavailable: " + cameraError(ret);
    } else note = "Cannot allocate preview";
    return "{\"done\":true,\"size\":" + std::to_string(size) + ",\"note\":" + quoteJson(note) + "}";
}
static void processBrowserRequest(Camera* camera, GPContext* context, const std::string& outputDir) {
    std::shared_ptr<CameraRequest> request;
    {
        std::lock_guard<std::mutex> lock(browser.mutex);
        if (browser.queue.empty()) return;
        request = browser.queue.front();
        browser.queue.pop_front();
    }
    std::string result;
    browser.executing.store(request.get());
    if (cameraOperationCancelled()) result = "{\"done\":true,\"error\":\"Cancelled\"}";
    else if (request->kind == "list") result = browserListing(camera, context, request->folder);
    else if (request->kind == "preview") result = browserPreview(camera, context, *request);
    else {
        std::string log, saved;
        bool ok = downloadCameraFile(camera, context, request->folder.c_str(), request->name.c_str(), outputDir, log, &saved);
        result = ok ? "{\"done\":true,\"saved\":" + quoteJson(saved) + "}"
                    : "{\"done\":true,\"error\":" + quoteJson(log) + "}";
    }
    browser.executing.store(nullptr);
    std::lock_guard<std::mutex> lock(browser.mutex);
    request->result = std::move(result);
    request->done = true;
}
