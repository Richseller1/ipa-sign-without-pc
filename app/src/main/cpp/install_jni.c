#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#include <libimobiledevice/libimobiledevice.h>
#include <libimobiledevice/lockdown.h>
#include <libimobiledevice/afc.h>
#include <libimobiledevice/installation_proxy.h>
#include <plist/plist.h>

#define LOG_TAG "install-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static volatile int g_cancel_install = 0;

struct instproxy_cb_context {
    JavaVM *jvm;
    jobject callback_ref;
    jmethodID on_progress_mid;
    volatile int is_finished;
    volatile int error_occurred;
    char error_name[128];
    char error_desc[256];
};

static void report_progress(JNIEnv *env, jobject cb_ref, jmethodID mid,
                            const char *stage, int percent,
                            long long transferred, long long total,
                            const char *err_name, const char *err_desc)
{
    if (!env || !cb_ref || !mid) return;
    jstring j_stage = (*env)->NewStringUTF(env, stage ? stage : "");
    jstring j_err_name = (*env)->NewStringUTF(env, err_name ? err_name : "");
    jstring j_err_desc = (*env)->NewStringUTF(env, err_desc ? err_desc : "");

    (*env)->CallVoidMethod(env, cb_ref, mid, j_stage, (jint)percent,
                           (jlong)transferred, (jlong)total, j_err_name, j_err_desc);

    (*env)->DeleteLocalRef(env, j_stage);
    (*env)->DeleteLocalRef(env, j_err_name);
    (*env)->DeleteLocalRef(env, j_err_desc);
}

static void instproxy_status_callback(plist_t command, plist_t status, void *user_data)
{
    (void)command;
    struct instproxy_cb_context *ctx = (struct instproxy_cb_context *)user_data;
    if (!ctx || !ctx->jvm) return;

    JNIEnv *env = NULL;
    int attached = 0;
    jint get_res = (*ctx->jvm)->GetEnv(ctx->jvm, (void **)&env, JNI_VERSION_1_6);
    if (get_res == JNI_EDETACHED) {
        if ((*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL) == 0) {
            attached = 1;
        } else {
            LOGE("Failed to attach current thread to JVM");
            return;
        }
    } else if (get_res != JNI_OK) {
        LOGE("GetEnv failed: %d", get_res);
        return;
    }

    char *status_name = NULL;
    instproxy_status_get_name(status, &status_name);

    int percent = -1;
    instproxy_status_get_percent_complete(status, &percent);

    char *err_name = NULL;
    char *err_desc = NULL;
    uint64_t err_code = 0;
    instproxy_error_t err_status = instproxy_status_get_error(status, &err_name, &err_desc, &err_code);

    LOGI("instproxy callback: status='%s', percent=%d, err_status=%d, err_name='%s'",
         status_name ? status_name : "NULL", percent, err_status, err_name ? err_name : "NULL");

    if (err_status != INSTPROXY_E_SUCCESS || err_name != NULL) {
        ctx->error_occurred = 1;
        ctx->is_finished = 1;
        if (err_name) {
            strncpy(ctx->error_name, err_name, sizeof(ctx->error_name) - 1);
        } else {
            snprintf(ctx->error_name, sizeof(ctx->error_name), "Error_%d", (int)err_status);
        }
        if (err_desc) {
            strncpy(ctx->error_desc, err_desc, sizeof(ctx->error_desc) - 1);
        }

        report_progress(env, ctx->callback_ref, ctx->on_progress_mid,
                        "INSTALL_ERROR", percent >= 0 ? percent : 0, 0, 0,
                        ctx->error_name, ctx->error_desc);
    } else if (status_name && strcmp(status_name, "Complete") == 0) {
        ctx->is_finished = 1;
        report_progress(env, ctx->callback_ref, ctx->on_progress_mid,
                        "COMPLETE", 100, 0, 0, "", "");
    } else {
        const char *stage = status_name ? status_name : "Installing";
        report_progress(env, ctx->callback_ref, ctx->on_progress_mid,
                        stage, percent >= 0 ? percent : 0, 0, 0, "", "");
    }

    if (status_name) free(status_name);
    if (err_name) free(err_name);
    if (err_desc) free(err_desc);

    if (attached) {
        (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    }
}

JNIEXPORT void JNICALL
Java_com_aistudio_iossideloadbridge_app_usb_UsbBridge_nativeCancelInstall(
    JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
    LOGI("nativeCancelInstall called");
    g_cancel_install = 1;
}

JNIEXPORT jint JNICALL
Java_com_aistudio_iossideloadbridge_app_usb_UsbBridge_nativeInstallIpa(
    JNIEnv *env, jobject thiz, jstring j_storage_dir, jstring j_ipa_path, jobject j_callback)
{
    (void)thiz;
    g_cancel_install = 0;

    const char *storage_dir = (*env)->GetStringUTFChars(env, j_storage_dir, NULL);
    const char *ipa_path = (*env)->GetStringUTFChars(env, j_ipa_path, NULL);

    if (storage_dir) {
        setenv("XDG_CONFIG_HOME", storage_dir, 1);
        setenv("HOME", storage_dir, 1);
        setenv("USBMUXD_SOCKET_ADDRESS", "127.0.0.1:27015", 1);
    }

    jclass cb_class = (*env)->GetObjectClass(env, j_callback);
    jmethodID mid_on_progress = (*env)->GetMethodID(env, cb_class, "onProgress",
        "(Ljava/lang/String;IJJLjava/lang/String;Ljava/lang/String;)V");

    if (!mid_on_progress) {
        LOGE("Failed to find onProgress method in callback object");
        if (storage_dir) (*env)->ReleaseStringUTFChars(env, j_storage_dir, storage_dir);
        if (ipa_path) (*env)->ReleaseStringUTFChars(env, j_ipa_path, ipa_path);
        return -1;
    }

    report_progress(env, j_callback, mid_on_progress, "CONNECTING", 0, 0, 0, "", "");

    // 1. Check local IPA file
    FILE *f = fopen(ipa_path, "rb");
    if (!f) {
        LOGE("Cannot open local IPA file: %s", ipa_path);
        report_progress(env, j_callback, mid_on_progress, "ERROR", 0, 0, 0,
                        "FileNotFound", "Cannot open temporary IPA file on disk");
        if (storage_dir) (*env)->ReleaseStringUTFChars(env, j_storage_dir, storage_dir);
        if (ipa_path) (*env)->ReleaseStringUTFChars(env, j_ipa_path, ipa_path);
        return -2;
    }

    fseeko(f, 0, SEEK_END);
    long long total_size = ftello(f);
    fseeko(f, 0, SEEK_SET);

    // 2. Connect to device
    idevice_t device = NULL;
    idevice_error_t ierr = idevice_new(&device, NULL);
    if (ierr != IDEVICE_E_SUCCESS || !device) {
        LOGE("idevice_new failed: %d", ierr);
        fclose(f);
        report_progress(env, j_callback, mid_on_progress, "ERROR", 0, 0, total_size,
                        "NoDevice", "No iOS device found via usbmuxd. Connect an unlocked iOS device (iPhone/iPad).");
        if (storage_dir) (*env)->ReleaseStringUTFChars(env, j_storage_dir, storage_dir);
        if (ipa_path) (*env)->ReleaseStringUTFChars(env, j_ipa_path, ipa_path);
        return -3;
    }

    // 3. Lockdown handshake
    lockdownd_client_t client = NULL;
    lockdownd_error_t lerr = lockdownd_client_new_with_handshake(device, &client, "iossideloadbridge");
    if (lerr != LOCKDOWN_E_SUCCESS || !client) {
        LOGE("lockdownd_client_new_with_handshake failed: %d", lerr);
        fclose(f);
        idevice_free(device);
        const char *msg = "Lockdown handshake failed. Unlock device and tap 'Trust' if prompted.";
        const char *code_str = "LockdownError";
        if (lerr == LOCKDOWN_E_PASSWORD_PROTECTED) {
            code_str = "PasswordProtected";
            msg = "iOS device is locked with a passcode. Please unlock your device.";
        } else if (lerr == LOCKDOWN_E_PAIRING_DIALOG_RESPONSE_PENDING) {
            code_str = "PairingPending";
            msg = "Pairing pending: Tap 'Trust' on your iOS device and enter passcode.";
        } else if (lerr == LOCKDOWN_E_USER_DENIED_PAIRING) {
            code_str = "UserDeniedPairing";
            msg = "Pairing denied: 'Don't Trust' was selected on device. Reconnect USB cable to retry.";
        }
        report_progress(env, j_callback, mid_on_progress, "ERROR", 0, 0, total_size, code_str, msg);
        if (storage_dir) (*env)->ReleaseStringUTFChars(env, j_storage_dir, storage_dir);
        if (ipa_path) (*env)->ReleaseStringUTFChars(env, j_ipa_path, ipa_path);
        return -4;
    }

    if (g_cancel_install) {
        fclose(f);
        lockdownd_client_free(client);
        idevice_free(device);
        report_progress(env, j_callback, mid_on_progress, "CANCELLED", 0, 0, total_size, "", "Cancelled by user");
        if (storage_dir) (*env)->ReleaseStringUTFChars(env, j_storage_dir, storage_dir);
        if (ipa_path) (*env)->ReleaseStringUTFChars(env, j_ipa_path, ipa_path);
        return -100;
    }

    // 4. Start AFC service
    report_progress(env, j_callback, mid_on_progress, "STARTING_AFC", 0, 0, total_size, "", "");
    lockdownd_service_descriptor_t afc_service = NULL;
    lerr = lockdownd_start_service(client, AFC_SERVICE_NAME, &afc_service);
    if (lerr != LOCKDOWN_E_SUCCESS || !afc_service) {
        LOGE("Failed to start AFC service: %d", lerr);
        fclose(f);
        lockdownd_client_free(client);
        idevice_free(device);
        report_progress(env, j_callback, mid_on_progress, "ERROR", 0, 0, total_size,
                        "AfcServiceStartFailed", "Failed to start com.apple.afc service on device.");
        if (storage_dir) (*env)->ReleaseStringUTFChars(env, j_storage_dir, storage_dir);
        if (ipa_path) (*env)->ReleaseStringUTFChars(env, j_ipa_path, ipa_path);
        return -5;
    }

    afc_client_t afc = NULL;
    afc_error_t aerr = afc_client_new(device, afc_service, &afc);
    lockdownd_service_descriptor_free(afc_service);
    afc_service = NULL;

    if (aerr != AFC_E_SUCCESS || !afc) {
        LOGE("afc_client_new failed: %d", aerr);
        fclose(f);
        lockdownd_client_free(client);
        idevice_free(device);
        report_progress(env, j_callback, mid_on_progress, "ERROR", 0, 0, total_size,
                        "AfcConnectFailed", "Could not connect to AFC filesystem on iPhone.");
        if (storage_dir) (*env)->ReleaseStringUTFChars(env, j_storage_dir, storage_dir);
        if (ipa_path) (*env)->ReleaseStringUTFChars(env, j_ipa_path, ipa_path);
        return -6;
    }

    // 5. Ensure PublicStaging directory exists on device
    afc_make_directory(afc, "PublicStaging");
    const char *remote_staging_path = "PublicStaging/staging.ipa";
    afc_remove_path(afc, remote_staging_path);

    uint64_t afc_handle = 0;
    aerr = afc_file_open(afc, remote_staging_path, AFC_FOPEN_WRONLY, &afc_handle);
    if (aerr != AFC_E_SUCCESS || !afc_handle) {
        LOGE("afc_file_open failed: %d", aerr);
        fclose(f);
        afc_client_free(afc);
        lockdownd_client_free(client);
        idevice_free(device);
        report_progress(env, j_callback, mid_on_progress, "ERROR", 0, 0, total_size,
                        "AfcFileOpenFailed", "Failed to create staging file in PublicStaging on device.");
        if (storage_dir) (*env)->ReleaseStringUTFChars(env, j_storage_dir, storage_dir);
        if (ipa_path) (*env)->ReleaseStringUTFChars(env, j_ipa_path, ipa_path);
        return -7;
    }

    // 6. Stream upload in 64 KB chunks
    LOGI("Starting AFC upload of %lld bytes in 64KB chunks to '%s'", total_size, remote_staging_path);
    char chunk_buf[65536];
    long long total_written = 0;
    int write_error = 0;

    while (!feof(f) && !g_cancel_install) {
        size_t bytes_read = fread(chunk_buf, 1, sizeof(chunk_buf), f);
        if (bytes_read == 0) break;

        size_t chunk_pos = 0;
        while (chunk_pos < bytes_read && !g_cancel_install) {
            uint32_t bytes_written = 0;
            aerr = afc_file_write(afc, afc_handle, chunk_buf + chunk_pos,
                                  (uint32_t)(bytes_read - chunk_pos), &bytes_written);
            if (aerr != AFC_E_SUCCESS) {
                LOGE("afc_file_write failed: %d", aerr);
                write_error = 1;
                break;
            }
            chunk_pos += bytes_written;
        }

        if (write_error) break;
        total_written += bytes_read;

        int pct = total_size > 0 ? (int)((total_written * 100) / total_size) : 0;
        report_progress(env, j_callback, mid_on_progress, "UPLOADING", pct, total_written, total_size, "", "");
    }

    fclose(f);
    afc_file_close(afc, afc_handle);

    if (g_cancel_install) {
        LOGI("Install cancelled during upload. Removing remote staging file.");
        afc_remove_path(afc, remote_staging_path);
        afc_client_free(afc);
        lockdownd_client_free(client);
        idevice_free(device);
        report_progress(env, j_callback, mid_on_progress, "CANCELLED", 0, total_written, total_size, "", "Upload cancelled by user");
        if (storage_dir) (*env)->ReleaseStringUTFChars(env, j_storage_dir, storage_dir);
        if (ipa_path) (*env)->ReleaseStringUTFChars(env, j_ipa_path, ipa_path);
        return -100;
    }

    if (write_error) {
        afc_remove_path(afc, remote_staging_path);
        afc_client_free(afc);
        lockdownd_client_free(client);
        idevice_free(device);
        report_progress(env, j_callback, mid_on_progress, "ERROR", 0, total_written, total_size,
                        "AfcWriteError", "Failed writing IPA data over AFC connection.");
        if (storage_dir) (*env)->ReleaseStringUTFChars(env, j_storage_dir, storage_dir);
        if (ipa_path) (*env)->ReleaseStringUTFChars(env, j_ipa_path, ipa_path);
        return -8;
    }

    // Free AFC client after successful upload
    afc_client_free(afc);
    afc = NULL;

    LOGI("Upload complete (%lld bytes). Starting installation_proxy...", total_written);
    report_progress(env, j_callback, mid_on_progress, "STARTING_INSTALL", 0, total_written, total_size, "", "");

    // 7. Start installation_proxy service
    lockdownd_service_descriptor_t inst_service = NULL;
    lerr = lockdownd_start_service(client, INSTPROXY_SERVICE_NAME, &inst_service);
    if (lerr != LOCKDOWN_E_SUCCESS || !inst_service) {
        LOGE("Failed to start installation_proxy: %d", lerr);
        lockdownd_client_free(client);
        idevice_free(device);
        report_progress(env, j_callback, mid_on_progress, "ERROR", 0, total_written, total_size,
                        "InstProxyStartFailed", "Failed to start com.apple.mobile.installation_proxy service.");
        if (storage_dir) (*env)->ReleaseStringUTFChars(env, j_storage_dir, storage_dir);
        if (ipa_path) (*env)->ReleaseStringUTFChars(env, j_ipa_path, ipa_path);
        return -9;
    }

    instproxy_client_t ipc = NULL;
    instproxy_error_t iperr = instproxy_client_new(device, inst_service, &ipc);
    lockdownd_service_descriptor_free(inst_service);
    inst_service = NULL;
    lockdownd_client_free(client);
    client = NULL;

    if (iperr != INSTPROXY_E_SUCCESS || !ipc) {
        LOGE("instproxy_client_new failed: %d", iperr);
        idevice_free(device);
        report_progress(env, j_callback, mid_on_progress, "ERROR", 0, total_written, total_size,
                        "InstProxyConnectFailed", "Could not connect to installation_proxy on iPhone.");
        if (storage_dir) (*env)->ReleaseStringUTFChars(env, j_storage_dir, storage_dir);
        if (ipa_path) (*env)->ReleaseStringUTFChars(env, j_ipa_path, ipa_path);
        return -10;
    }

    // 8. Prepare context and client options for instproxy_install
    struct instproxy_cb_context ctx;
    memset(&ctx, 0, sizeof(ctx));
    (*env)->GetJavaVM(env, &ctx.jvm);
    ctx.callback_ref = (*env)->NewGlobalRef(env, j_callback);
    ctx.on_progress_mid = mid_on_progress;
    ctx.is_finished = 0;
    ctx.error_occurred = 0;

    plist_t client_opts = instproxy_client_options_new();

    LOGI("Calling instproxy_install with remote path: %s", remote_staging_path);
    iperr = instproxy_install(ipc, remote_staging_path, client_opts, instproxy_status_callback, &ctx);
    instproxy_client_options_free(client_opts);

    if (iperr != INSTPROXY_E_SUCCESS) {
        LOGE("instproxy_install returned error: %d", iperr);
        instproxy_client_free(ipc);
        idevice_free(device);
        (*env)->DeleteGlobalRef(env, ctx.callback_ref);
        report_progress(env, j_callback, mid_on_progress, "ERROR", 0, total_written, total_size,
                        "InstProxyInstallFailed", "installation_proxy rejected install command.");
        if (storage_dir) (*env)->ReleaseStringUTFChars(env, j_storage_dir, storage_dir);
        if (ipa_path) (*env)->ReleaseStringUTFChars(env, j_ipa_path, ipa_path);
        return -11;
    }

    // 9. Wait for installation loop completion or user cancellation
    while (!ctx.is_finished && !g_cancel_install) {
        usleep(50000); // 50ms check
    }

    if (g_cancel_install) {
        LOGI("User cancelled during instproxy installation phase");
        report_progress(env, j_callback, mid_on_progress, "CANCELLED", 0, total_written, total_size, "", "Installation cancelled by user");
    }

    instproxy_client_free(ipc);
    idevice_free(device);
    (*env)->DeleteGlobalRef(env, ctx.callback_ref);

    if (storage_dir) (*env)->ReleaseStringUTFChars(env, j_storage_dir, storage_dir);
    if (ipa_path) (*env)->ReleaseStringUTFChars(env, j_ipa_path, ipa_path);

    if (ctx.error_occurred) {
        LOGI("Install finished with error: %s (%s)", ctx.error_name, ctx.error_desc);
        return -12;
    }

    LOGI("Install completed successfully");
    return 0;
}
