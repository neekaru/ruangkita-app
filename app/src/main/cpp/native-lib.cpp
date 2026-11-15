
#include <jni.h>
#include <string>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <sys/stat.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <thread>

#include <gphoto2/gphoto2-camera.h>
#include <gphoto2/gphoto2-abilities-list.h>
#include <gphoto2/gphoto2-context.h>
#include <gphoto2/gphoto2-port.h>
#include <gphoto2/gphoto2-port-info-list.h>
#include <gphoto2/gphoto2-port-result.h>
#include <gphoto2/gphoto2-file.h>
#include <gphoto2/gphoto2-result.h>
#include "camera-paths.h"

// =====================================
// CONFIGURATION
// =====================================

static const int EVENT_TIMEOUT_MS = 1000;

static std::atomic<bool> g_stopRequested(false);
#include "camera-requests.h"

// =====================================
// DOWNLOAD CAMERA FILE
// =====================================

static bool downloadCameraFile(
        Camera* camera,
        GPContext* context,
        const char* folder,
        const char* filename,
        const std::string& outputDir,
        std::string& output,
        std::string* savedPath = nullptr
) {
    if (folder == nullptr || filename == nullptr) {
        output += "[DOWNLOAD ERROR] Invalid camera file path.\n";
        return false;
    }

    std::string safeName(filename);

    if (safeName.empty() ||
        safeName.find('/') != std::string::npos ||
        safeName.find('\\') != std::string::npos ||
        safeName == "." ||
        safeName == "..") {

        output += "[DOWNLOAD ERROR] Invalid filename.\n";
        return false;
    }

    const std::string destination = unusedPhotoPath(outputDir, safeName);

    const std::string temporary =
            destination + ".part";

    output += "\n[DOWNLOAD]\n";
    output += "File: " + safeName + "\n";

    CameraFile* file = nullptr;

    int ret = gp_file_new(&file);

    if (ret < GP_OK || file == nullptr) {
        output += "Cannot create CameraFile: ";
        output += std::to_string(ret);
        output += "\n";
        return false;
    }

    ret = gp_camera_file_get(
            camera,
            folder,
            filename,
            GP_FILE_TYPE_NORMAL,
            file,
            context
    );

    if (ret < GP_OK) {
        output += "Camera download FAILED: ";
        output += std::to_string(ret);
        output += "\n";

        gp_file_free(file);
        return false;
    }

    unlink(temporary.c_str());

    ret = gp_file_save(file, temporary.c_str());

    gp_file_free(file);

    if (ret < GP_OK) {
        output += "Temporary file save FAILED: ";
        output += std::to_string(ret);
        output += "\n";

        unlink(temporary.c_str());
        return false;
    }

    struct stat fileInfo{};

    if (stat(temporary.c_str(), &fileInfo) != 0 ||
        !S_ISREG(fileInfo.st_mode) ||
        fileInfo.st_size <= 0) {

        output += "ERROR: Temporary file is invalid.\n";
        unlink(temporary.c_str());
        return false;
    }

    if (cameraOperationCancelled()) {
        output += "Download cancelled before finalizing.\n";
        unlink(temporary.c_str());
        return false;
    }

    if (rename(
            temporary.c_str(),
            destination.c_str()
    ) != 0) {

        output += "ERROR: Cannot finalize downloaded file.\n";
        output += "Rename failed.\n";

        unlink(temporary.c_str());
        return false;
    }

    output += "Download: SUCCESS\n";
    output += "Saved to: " + destination + "\n";
    output += "File size: ";
    output += std::to_string(fileInfo.st_size);
    output += " bytes\n";

    if (savedPath) *savedPath = destination;
    return true;
}

#include "camera-browser.h"

// =====================================
// CAMERA EVENT LISTENER
// =====================================

static void listenCameraEvents(
        Camera* camera,
        GPContext* context,
        const std::string& outputDir,
        std::string& output
) {
    output += "\n[EVENT LISTENER]\n";
    output += "Continuous receiving started.\n";
    output += "Waiting for physical shutter...\n";
    output += "Press STOP RECEIVING to end the session.\n";

    int detectedFiles = 0;
    int receivedEvents = 0;
    int downloadedFiles = 0;
    int failedDownloads = 0;

    bool listenerError = false;
    bool eventsSupported = true;

    while (!g_stopRequested.load()) {
        processBrowserRequest(camera, context, outputDir);
        if (!eventsSupported) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            continue;
        }

        CameraEventType eventType = GP_EVENT_UNKNOWN;
        void* eventData = nullptr;

        int ret = gp_camera_wait_for_event(
                camera,
                browser.browsing.load() ? 100 : EVENT_TIMEOUT_MS,
                &eventType,
                &eventData,
                context
        );

        if (ret == GP_ERROR_NOT_SUPPORTED) {
            eventsSupported = false;
            output += "Automatic events unsupported; camera browsing remains available.\n";
            if (eventData) free(eventData);
            continue;
        }

        if (g_stopRequested.load()) {
            if (eventData != nullptr) {
                free(eventData);
            }
            break;
        }

        if (ret < GP_OK) {
            output += "\n[EVENT ERROR] ";
            output += std::to_string(ret);
            output += "\n";

            if (eventData != nullptr) {
                free(eventData);
            }

            listenerError = true;
            break;
        }

        switch (eventType) {

            case GP_EVENT_TIMEOUT:
                break;

            case GP_EVENT_FILE_ADDED: {

                receivedEvents++;
                detectedFiles++;

                if (eventData == nullptr) {
                    output += "\n[FILE ADDED] Empty event data.\n";
                    failedDownloads++;
                    break;
                }

                CameraFilePath* path =
                        static_cast<CameraFilePath*>(eventData);

                std::string folder(path->folder);
                std::string filename(path->name);

                output += "\n[FILE ADDED]\n";
                output += "Folder: " + folder + "\n";
                output += "Filename: " + filename + "\n";

                bool success = downloadCameraFile(
                        camera,
                        context,
                        folder.c_str(),
                        filename.c_str(),
                        outputDir,
                        output
                );

                if (success) {
                    downloadedFiles++;
                } else {
                    failedDownloads++;
                }

                free(eventData);
                eventData = nullptr;

                break;
            }

            case GP_EVENT_FILE_CHANGED: {

                receivedEvents++;
                output += "\n[FILE CHANGED]\n";

                if (eventData != nullptr) {
                    CameraFilePath* path =
                            static_cast<CameraFilePath*>(eventData);

                    output += "Folder: ";
                    output += path->folder;
                    output += "\nFilename: ";
                    output += path->name;
                    output += "\n";

                    free(eventData);
                    eventData = nullptr;
                }

                break;
            }

            case GP_EVENT_FOLDER_ADDED: {

                receivedEvents++;
                output += "\n[FOLDER ADDED]\n";

                if (eventData != nullptr) {
                    CameraFilePath* path =
                            static_cast<CameraFilePath*>(eventData);

                    output += "Folder: ";
                    output += path->folder;
                    output += "\nName: ";
                    output += path->name;
                    output += "\n";

                    free(eventData);
                    eventData = nullptr;
                }

                break;
            }

            case GP_EVENT_CAPTURE_COMPLETE:
                receivedEvents++;
                output += "\n[CAPTURE COMPLETE]\n";
                break;

            case GP_EVENT_UNKNOWN:

                receivedEvents++;

                if (eventData != nullptr) {
                    free(eventData);
                    eventData = nullptr;
                }

                break;

            default:

                receivedEvents++;

                output += "\n[OTHER EVENT] Type: ";
                output += std::to_string(eventType);
                output += "\n";

                if (eventData != nullptr) {
                    free(eventData);
                    eventData = nullptr;
                }

                break;
        }
    }

    output += "\n[EVENT LISTENER SUMMARY]\n";

    output += "Total events received: ";
    output += std::to_string(receivedEvents);
    output += "\n";

    output += "Detected files: ";
    output += std::to_string(detectedFiles);
    output += "\n";

    output += "Downloaded files: ";
    output += std::to_string(downloadedFiles);
    output += "\n";

    output += "Failed downloads: ";
    output += std::to_string(failedDownloads);
    output += "\n";

    if (g_stopRequested.load()) {
        output += "Stopped by user.\n";
    } else if (listenerError) {
        output += "Listener stopped because of an event error.\n";
    } else {
        output += "Listener stopped.\n";
    }
}

// =====================================
// JNI: RESET STOP REQUEST
// =====================================

extern "C"
JNIEXPORT void JNICALL
Java_com_ruangfoto_app_MainActivity_resetStop(
        JNIEnv* env,
        jobject /* this */
) {
    g_stopRequested.store(false);
}

// =====================================
// JNI: STOP REQUEST
// =====================================

extern "C"
JNIEXPORT void JNICALL
Java_com_ruangfoto_app_MainActivity_requestStop(
        JNIEnv* env,
        jobject /* this */
) {
    g_stopRequested.store(true);
}

// =====================================
// MAIN JNI FUNCTION
// =====================================

extern "C"
JNIEXPORT jstring JNICALL
Java_com_ruangfoto_app_MainActivity_testUsbFd(
        JNIEnv* env,
        jobject /* this */,
        jint fd,
        jstring camlibsPath,
        jstring iolibsPath,
        jstring outputPath,
        jint vendorId,
        jint productId
) {

    std::string result;

    result += "=== UNIVERSAL CAMERA USB RECEIVER ===\n";

    int ret = GP_OK;

    GPContext* context = nullptr;
    CameraAbilitiesList* abilitiesList = nullptr;
    GPPortInfoList* portList = nullptr;
    Camera* camera = nullptr;

    bool cameraInitialized = false;

    // =====================================
    // STEP 1: VALIDATE USB FD
    // =====================================

    result += "\n[USB]\n";
    result += "File descriptor: ";
    result += std::to_string(fd);
    result += "\n";

    char deviceIdText[64];

    snprintf(
            deviceIdText,
            sizeof(deviceIdText),
            "VID: 0x%04X\nPID: 0x%04X\n",
            vendorId,
            productId
    );

    result += deviceIdText;

    if (fd < 0) {
        result += "ERROR: Invalid USB file descriptor.\n";
        return env->NewStringUTF(result.c_str());
    }

    ret = gp_port_usb_set_sys_device(fd);

    if (ret < GP_OK) {
        result += "ERROR: USB FD registration failed: ";
        result += std::to_string(ret);
        result += "\n";

        return env->NewStringUTF(result.c_str());
    }

    result += "USB FD registration: SUCCESS\n";

    // =====================================
    // STEP 2: READ PATHS
    // =====================================

    if (camlibsPath == nullptr ||
        iolibsPath == nullptr ||
        outputPath == nullptr) {

        result += "ERROR: Missing directory arguments.\n";
        return env->NewStringUTF(result.c_str());
    }

    const char* camPath =
            env->GetStringUTFChars(camlibsPath, nullptr);

    const char* ioPath =
            env->GetStringUTFChars(iolibsPath, nullptr);

    const char* outPath =
            env->GetStringUTFChars(outputPath, nullptr);

    if (camPath == nullptr ||
        ioPath == nullptr ||
        outPath == nullptr) {

        if (camPath != nullptr)
            env->ReleaseStringUTFChars(camlibsPath, camPath);

        if (ioPath != nullptr)
            env->ReleaseStringUTFChars(iolibsPath, ioPath);

        if (outPath != nullptr)
            env->ReleaseStringUTFChars(outputPath, outPath);

        result += "ERROR: Cannot read directory arguments.\n";
        return env->NewStringUTF(result.c_str());
    }

    std::string camPathString(camPath);
    std::string ioPathString(ioPath);
    std::string outputDir(outPath);

    env->ReleaseStringUTFChars(camlibsPath, camPath);
    env->ReleaseStringUTFChars(iolibsPath, ioPath);
    env->ReleaseStringUTFChars(outputPath, outPath);

    setenv("CAMLIBS", camPathString.c_str(), 1);
    setenv("IOLIBS", ioPathString.c_str(), 1);

    struct stat dirInfo{};

    if (stat(outputDir.c_str(), &dirInfo) != 0 ||
        !S_ISDIR(dirInfo.st_mode)) {

        result += "ERROR: Output directory does not exist.\n";
        result += outputDir + "\n";

        return env->NewStringUTF(result.c_str());
    }

    result += "\n[OUTPUT DIRECTORY]\n";
    result += outputDir + "\n";

    // =====================================
    // STEP 3: CREATE CONTEXT
    // =====================================

    context = gp_context_new();

    if (context == nullptr) {
        result += "ERROR: GPContext creation failed.\n";
        goto cleanup;
    }

    // =====================================
    // STEP 4: LOAD CAMERA DRIVER
    // =====================================

    result += "\n[CAMERA DRIVER]\n";

    ret = gp_abilities_list_new(&abilitiesList);

    if (ret < GP_OK) {
        result += "ERROR: Cannot create abilities list: ";
        result += std::to_string(ret);
        result += "\n";
        goto cleanup;
    }

    ret = gp_abilities_list_load(abilitiesList, context);

    if (ret < GP_OK) {
        result += "ERROR: Cannot load camera drivers: ";
        result += std::to_string(ret);
        result += "\n";
        goto cleanup;
    }

    {
        int count = gp_abilities_list_count(abilitiesList);

        CameraAbilities selectedAbilities{};
        bool driverFound = false;

        for (int i = 0; i < count; i++) {

            CameraAbilities abilities{};

            ret = gp_abilities_list_get_abilities(
                    abilitiesList,
                    i,
                    &abilities
            );

            if (ret < GP_OK) {
                continue;
            }

            if (
                    abilities.usb_vendor ==
                    static_cast<unsigned int>(vendorId)
                    &&
                    abilities.usb_product ==
                    static_cast<unsigned int>(productId)
                    ) {
                selectedAbilities = abilities;
                driverFound = true;

                result += "Matching driver found: ";
                result += abilities.model;
                result += "\n";

                break;
            }
        }

        if (!driverFound) {
            result += "ERROR: No exact camera driver found.\n";
            result += "VID/PID: ";
            result += std::to_string(vendorId);
            result += "/";
            result += std::to_string(productId);
            result += "\n";

            goto cleanup;
        }

        ret = gp_camera_new(&camera);

        if (ret < GP_OK || camera == nullptr) {
            result += "ERROR: Camera creation failed: ";
            result += std::to_string(ret);
            result += "\n";
            goto cleanup;
        }

        ret = gp_camera_set_abilities(
                camera,
                selectedAbilities
        );

        if (ret < GP_OK) {
            result += "ERROR: Applying camera driver failed: ";
            result += std::to_string(ret);
            result += "\n";
            goto cleanup;
        }

        result += "Camera driver applied successfully.\n";
    }

    // =====================================
    // STEP 5: SELECT USB PORT
    // =====================================

    result += "\n[USB PORT]\n";

    ret = gp_port_info_list_new(&portList);

    if (ret < GP_OK) {
        result += "ERROR: Cannot create port list: ";
        result += std::to_string(ret);
        result += "\n";
        goto cleanup;
    }

    ret = gp_port_info_list_load(portList);

    if (ret < GP_OK) {
        result += "ERROR: Cannot load USB ports: ";
        result += std::to_string(ret);
        result += "\n";
        goto cleanup;
    }

    {
        int count = gp_port_info_list_count(portList);
        bool portFound = false;

        for (int i = 0; i < count; i++) {

            GPPortInfo info = nullptr;

            ret = gp_port_info_list_get_info(
                    portList,
                    i,
                    &info
            );

            if (ret < GP_OK || info == nullptr) {
                continue;
            }

            char* path = nullptr;
            gp_port_info_get_path(info, &path);

            if (path == nullptr) {
                continue;
            }

            if (strncmp(path, "usb:", 4) == 0) {

                ret = gp_camera_set_port_info(camera, info);

                if (ret >= GP_OK) {
                    portFound = true;
                    result += "USB port assigned: SUCCESS\n";
                    break;
                }
            }
        }

        if (!portFound) {
            result += "ERROR: No usable USB port found.\n";
            goto cleanup;
        }
    }

    // =====================================
    // STEP 6: INITIALIZE CAMERA
    // =====================================

    result += "\n[CAMERA INITIALIZATION]\n";

    ret = gp_camera_init(camera, context);

    if (ret < GP_OK) {
        result += "PTP initialization: FAILED (";
        result += std::to_string(ret);
        result += ")\n";
        goto cleanup;
    }

    cameraInitialized = true;
    startBrowserSession(context);

    result += "PTP initialization: SUCCESS\n";

    // =====================================
    // STEP 7: CONTINUOUS LISTENER
    // =====================================

    listenCameraEvents(
            camera,
            context,
            outputDir,
            result
    );

    // =====================================
    // STEP 8: CLEANUP
    // =====================================

    cleanup:
    endBrowserSession();

    result += "\n[CLEANUP]\n";

    if (camera != nullptr) {

        if (cameraInitialized) {
            int exitRet = gp_camera_exit(camera, context);

            result += "Camera session closed: ";
            result += std::to_string(exitRet);
            result += "\n";
        }

        gp_camera_free(camera);
        result += "Camera released.\n";
    }

    if (portList != nullptr) {
        gp_port_info_list_free(portList);
    }

    if (abilitiesList != nullptr) {
        gp_abilities_list_free(abilitiesList);
    }

    if (context != nullptr) {
        gp_context_unref(context);
    }

    result += "Cleanup completed.\n";
    result += "\n=== SESSION FINISHED ===";

    return env->NewStringUTF(result.c_str());
}
