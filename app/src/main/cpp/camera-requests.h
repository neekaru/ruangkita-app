#pragma once

#include <algorithm>
#include <atomic>
#include <codecvt>
#include <deque>
#include <locale>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

// Only the camera listener executes requests. JNI producers never own a Camera* or GPContext*.
struct CameraRequest {
    uint64_t id = 0;
    std::string kind, folder, name, result;
    std::vector<char> preview;
    std::atomic<bool> cancelled{false};
    std::atomic<int> percent{-1};
    bool done = false;
};
struct CameraRequests {
    std::mutex mutex;
    uint64_t session = 0, nextId = 1;
    bool connected = false;
    std::atomic<bool> browsing{false};
    std::deque<std::shared_ptr<CameraRequest>> queue;
    std::unordered_map<uint64_t, std::shared_ptr<CameraRequest>> results;
    std::atomic<CameraRequest*> executing{nullptr};
    float progressTarget = 0;
};
static CameraRequests browser;

static std::string quoteJson(const std::string& value) {
    std::string out = "\"";
    for (unsigned char c : value) {
        if (c == '"' || c == '\\') { out += '\\'; out += static_cast<char>(c); }
        else if (c < 0x20) { char escaped[7]; snprintf(escaped, sizeof(escaped), "\\u%04x", c); out += escaped; }
        else out += static_cast<char>(c);
    }
    return out + "\"";
}
static jstring javaString(JNIEnv* env, const std::string& value) {
    try {
        std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> codec;
        auto text = codec.from_bytes(value);
        return env->NewString(reinterpret_cast<const jchar*>(text.data()), static_cast<jsize>(text.size()));
    } catch (...) { return env->NewStringUTF("{\"done\":true,\"error\":\"Camera returned invalid UTF-8\"}"); }
}
static std::string utf8String(JNIEnv* env, jstring value) {
    if (!value) return "";
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (!chars) return "";
    std::string out;
    try {
        std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> codec;
        const auto* start = reinterpret_cast<const char16_t*>(chars);
        out = codec.to_bytes(start, start + env->GetStringLength(value));
    } catch (...) { }
    env->ReleaseStringChars(value, chars);
    return out;
}
static bool safeCameraName(const std::string& name) {
    return !name.empty() && name != "." && name != ".." && name.find_first_of("/\\") == std::string::npos &&
           std::none_of(name.begin(), name.end(), [](unsigned char c) { return c < 0x20; });
}
static bool safeCameraFolder(const std::string& folder) {
    if (folder.empty() || folder[0] != '/' || folder.find('\\') != std::string::npos) return false;
    size_t start = 1;
    while (start < folder.size()) {
        size_t end = folder.find('/', start);
        std::string part = folder.substr(start, end == std::string::npos ? end : end - start);
        if (!safeCameraName(part)) return false;
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return true;
}
static bool cameraOperationCancelled() {
    CameraRequest* request = browser.executing.load();
    return g_stopRequested.load() || (request && request->cancelled.load());
}
static GPContextFeedback cancelCameraOperation(GPContext*, void*) {
    return cameraOperationCancelled() ? GP_CONTEXT_FEEDBACK_CANCEL : GP_CONTEXT_FEEDBACK_OK;
}
static unsigned int cameraProgressStart(GPContext*, float target, const char*, void*) {
    browser.progressTarget = target;
    return 1;
}
static void cameraProgressUpdate(GPContext*, unsigned int, float current, void*) {
    auto* request = browser.executing.load();
    if (request && browser.progressTarget > 0) request->percent.store(std::min(100, static_cast<int>(100 * current / browser.progressTarget)));
}
static void cameraProgressStop(GPContext*, unsigned int, void*) { }

extern "C" JNIEXPORT jlong JNICALL Java_com_ruangfoto_app_CameraBrowser_nativeSession(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(browser.mutex);
    return browser.connected ? static_cast<jlong>(browser.session) : 0;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_ruangfoto_app_CameraBrowser_nativeSubmit(
        JNIEnv* env, jobject, jlong session, jstring kind, jstring folder, jstring name) {
    auto request = std::make_shared<CameraRequest>();
    request->kind = utf8String(env, kind);
    request->folder = utf8String(env, folder);
    request->name = utf8String(env, name);
    if (!safeCameraFolder(request->folder) || (request->kind != "list" && !safeCameraName(request->name)) ||
        (request->kind != "list" && request->kind != "preview" && request->kind != "import")) return 0;
    std::lock_guard<std::mutex> lock(browser.mutex);
    if (!browser.connected || browser.session != static_cast<uint64_t>(session) || browser.results.size() >= 32) return 0;
    request->id = browser.nextId++;
    browser.results[request->id] = request;
    browser.queue.push_back(request);
    browser.browsing.store(true);
    return static_cast<jlong>(request->id);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_ruangfoto_app_CameraBrowser_nativePoll(JNIEnv* env, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(browser.mutex);
    auto found = browser.results.find(static_cast<uint64_t>(id));
    if (found == browser.results.end()) return env->NewStringUTF("{\"done\":true,\"error\":\"Camera session ended\"}");
    auto request = found->second;
    return javaString(env, request->done ? request->result : "{\"done\":false,\"percent\":" + std::to_string(request->percent.load()) + "}");
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_ruangfoto_app_CameraBrowser_nativePreview(JNIEnv* env, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(browser.mutex);
    auto found = browser.results.find(static_cast<uint64_t>(id));
    if (found == browser.results.end() || !found->second->done) return nullptr;
    auto& bytes = found->second->preview;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (result && !bytes.empty()) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}
extern "C" JNIEXPORT void JNICALL Java_com_ruangfoto_app_CameraBrowser_nativeCancel(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(browser.mutex);
    auto found = browser.results.find(static_cast<uint64_t>(id));
    if (found != browser.results.end()) found->second->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL Java_com_ruangfoto_app_CameraBrowser_nativeCloseBrowser(JNIEnv*, jobject, jlong session) {
    std::lock_guard<std::mutex> lock(browser.mutex);
    if (browser.session == static_cast<uint64_t>(session)) {
        browser.browsing.store(false);
        for (auto& entry : browser.results) entry.second->cancelled.store(true);
    }
}
extern "C" JNIEXPORT void JNICALL Java_com_ruangfoto_app_CameraBrowser_nativeRelease(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(browser.mutex);
    auto found = browser.results.find(static_cast<uint64_t>(id));
    if (found != browser.results.end()) { found->second->cancelled.store(true); browser.results.erase(found); }
}
static void startBrowserSession(GPContext* context) {
    std::lock_guard<std::mutex> lock(browser.mutex);
    ++browser.session;
    browser.connected = true;
    browser.browsing.store(false);
    browser.results.clear();
    browser.queue.clear();
    gp_context_set_cancel_func(context, cancelCameraOperation, nullptr);
    gp_context_set_progress_funcs(context, cameraProgressStart, cameraProgressUpdate, cameraProgressStop, nullptr);
}
static void endBrowserSession() {
    std::lock_guard<std::mutex> lock(browser.mutex);
    browser.connected = false;
    browser.browsing.store(false);
    for (auto& entry : browser.results) {
        entry.second->cancelled.store(true);
        entry.second->done = true;
        entry.second->result = "{\"done\":true,\"error\":\"Camera session ended\"}";
        entry.second->preview.clear();
    }
    browser.queue.clear();
}
