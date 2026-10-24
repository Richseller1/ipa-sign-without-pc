#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <android/log.h>

#include <libimobiledevice/libimobiledevice.h>
#include <libimobiledevice/lockdown.h>
#include <plist/plist.h>

#define LOG_TAG "lockdown-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jobject JNICALL
Java_com_aistudio_iossideloadbridge_app_usb_UsbBridge_nativeGetLockdownDeviceInfo(
    JNIEnv *env, jobject thiz, jstring j_storage_dir)
{
    const char *storage_dir = (*env)->GetStringUTFChars(env, j_storage_dir, NULL);
    if (storage_dir) {
        setenv("XDG_CONFIG_HOME", storage_dir, 1);
        setenv("HOME", storage_dir, 1);
        setenv("USBMUXD_SOCKET_ADDRESS", "127.0.0.1:27015", 1);
    }

    idevice_set_debug_level(1);

    idevice_t device = NULL;
    idevice_error_t ierr = idevice_new(&device, NULL);
    if (ierr != IDEVICE_E_SUCCESS || !device) {
        LOGE("idevice_new failed: %d", ierr);
        if (storage_dir) (*env)->ReleaseStringUTFChars(env, j_storage_dir, storage_dir);

        jclass cls = (*env)->FindClass(env, "com/aistudio/iossideloadbridge/app/usb/LockdownDeviceInfo");
        jmethodID mid = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;ZZ)V");
        jstring empty = (*env)->NewStringUTF(env, "");
        jstring err_msg = (*env)->NewStringUTF(env, "No iOS device found via usbmuxd. Connect an unlocked iOS device (iPhone/iPad).");
        return (*env)->NewObject(env, cls, mid, empty, empty, empty, empty, (jint)ierr, err_msg, JNI_FALSE, JNI_FALSE);
    }

    char *udid = NULL;
    idevice_get_udid(device, &udid);

    char *device_name = NULL;
    char *product_version = NULL;
    char *product_type = NULL;
    int error_code = 0;
    const char *error_message = "";
    int is_paired = 0;
    int needs_trust = 0;

    lockdownd_client_t client = NULL;
    lockdownd_error_t lerr = lockdownd_client_new_with_handshake(device, &client, "iossideloadbridge");
    LOGI("lockdownd_client_new_with_handshake returned: %d", lerr);

    if (lerr == LOCKDOWN_E_SUCCESS && client) {
        is_paired = 1;
        plist_t node = NULL;

        if (lockdownd_get_value(client, NULL, "DeviceName", &node) == LOCKDOWN_E_SUCCESS && node) {
            plist_get_string_val(node, &device_name);
            plist_free(node);
        }
        node = NULL;
        if (lockdownd_get_value(client, NULL, "ProductVersion", &node) == LOCKDOWN_E_SUCCESS && node) {
            plist_get_string_val(node, &product_version);
            plist_free(node);
        }
        node = NULL;
        if (lockdownd_get_value(client, NULL, "ProductType", &node) == LOCKDOWN_E_SUCCESS && node) {
            plist_get_string_val(node, &product_type);
            plist_free(node);
        }

        lockdownd_client_free(client);
        client = NULL;
    } else {
        error_code = (int)lerr;
        switch (lerr) {
            case LOCKDOWN_E_PASSWORD_PROTECTED:
                needs_trust = 1;
                error_message = "iOS device is locked with a passcode. Please unlock your device.";
                break;
            case LOCKDOWN_E_PAIRING_DIALOG_RESPONSE_PENDING:
                needs_trust = 1;
                error_message = "Pairing pending: Tap 'Trust' on your iOS device and enter passcode.";
                break;
            case LOCKDOWN_E_USER_DENIED_PAIRING:
                error_message = "Pairing denied: 'Don't Trust' was selected on device. Reconnect USB cable to retry.";
                break;
            default:
                error_message = "Lockdown handshake failed. Unlock device and tap 'Trust' if prompted.";
                break;
        }

        /* Try unauthenticated client to read ProductType or DeviceName if accessible */
        lockdownd_client_t unauth_client = NULL;
        if (lockdownd_client_new(device, &unauth_client, "iossideloadbridge") == LOCKDOWN_E_SUCCESS && unauth_client) {
            plist_t node = NULL;
            if (!product_type && lockdownd_get_value(unauth_client, NULL, "ProductType", &node) == LOCKDOWN_E_SUCCESS && node) {
                plist_get_string_val(node, &product_type);
                plist_free(node);
            }
            node = NULL;
            if (!device_name && lockdownd_get_value(unauth_client, NULL, "DeviceName", &node) == LOCKDOWN_E_SUCCESS && node) {
                plist_get_string_val(node, &device_name);
                plist_free(node);
            }
            lockdownd_client_free(unauth_client);
        }
    }

    idevice_free(device);

    if (storage_dir) {
        (*env)->ReleaseStringUTFChars(env, j_storage_dir, storage_dir);
    }

    jclass cls = (*env)->FindClass(env, "com/aistudio/iossideloadbridge/app/usb/LockdownDeviceInfo");
    jmethodID mid = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;ZZ)V");

    jstring j_dev_name = (*env)->NewStringUTF(env, device_name ? device_name : "");
    jstring j_prod_ver = (*env)->NewStringUTF(env, product_version ? product_version : "");
    jstring j_prod_type = (*env)->NewStringUTF(env, product_type ? product_type : "");
    jstring j_udid = (*env)->NewStringUTF(env, udid ? udid : "");
    jstring j_err_msg = (*env)->NewStringUTF(env, error_message ? error_message : "");

    if (device_name) free(device_name);
    if (product_version) free(product_version);
    if (product_type) free(product_type);
    if (udid) free(udid);

    return (*env)->NewObject(env, cls, mid, j_dev_name, j_prod_ver, j_prod_type, j_udid,
                             (jint)error_code, j_err_msg, (jboolean)is_paired, (jboolean)needs_trust);
}
