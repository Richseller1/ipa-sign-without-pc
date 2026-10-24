/*
 * usb_jni.c
 *
 * NEW file for this project (not part of upstream usbmuxd/libusbmuxd).
 * Written 2026-09-27 against the real usbmuxd source the user uploaded.
 * NOT compiled, NOT tested against real hardware.
 *
 * This is the thin JNI boundary. It does nothing clever on purpose —
 * all it does is take what Kotlin already knows (a permitted, open fd
 * plus vendor/product id) and hand it to usb_android_wrap_and_add()
 * in usb_android.c. Keeping this file "dumb" is deliberate: the less
 * logic here, the less that can silently be wrong.
 *
 * Expected Kotlin-side native method declarations (see UsbBridge.kt):
 *
 *   external fun nativeOnDeviceAttached(fd: Int, vendorId: Int, productId: Int): Int
 *   external fun nativeOnDeviceDetached(fd: Int)
 *   external fun nativeStartDaemon(socketName: String): Int
 *   external fun nativeStopDaemon()
 *
 * Package name below (com_aistudio_iossideloadbridge_app_usb_UsbBridge)
 * matches the app package you showed earlier
 * (com.aistudio.iossideloadbridge.app) with a "usb" sub-package for this
 * bridge class. If you place UsbBridge.kt in a different package, you
 * MUST rename these JNI function names to match — JNI resolves native
 * methods by the exact mangled package+class name, silently failing
 * (UnsatisfiedLinkError at runtime, not a compile error) if it doesn't
 * match.
 */

#include <jni.h>
#include <android/log.h>

#include "usb_android.h"
#include "usbmuxd_embedded.h"

#define LOG_TAG "usbmuxd-android-bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL
Java_com_aistudio_iossideloadbridge_app_usb_UsbBridge_nativeOnDeviceAttached(
        JNIEnv *env, jobject thiz, jint fd, jint vendorId, jint productId)
{
    (void)env; (void)thiz;
    LOGI("nativeOnDeviceAttached fd=%d vid=%04x pid=%04x", fd, vendorId, productId);
    int res = usb_android_wrap_and_add((int)fd, (uint16_t)vendorId, (uint16_t)productId);
    if (res != 0) {
        LOGW("usb_android_wrap_and_add failed: %d", res);
    }
    return (jint)res;
}

JNIEXPORT void JNICALL
Java_com_aistudio_iossideloadbridge_app_usb_UsbBridge_nativeOnDeviceDetached(
        JNIEnv *env, jobject thiz, jint fd)
{
    (void)env; (void)thiz;
    LOGI("nativeOnDeviceDetached fd=%d", fd);
    usb_android_device_gone((int)fd);
}

JNIEXPORT jint JNICALL
Java_com_aistudio_iossideloadbridge_app_usb_UsbBridge_nativeStartDaemon(
        JNIEnv *env, jobject thiz, jstring socketName, jstring storageDir)
{
    (void)thiz;
    const char *name = (*env)->GetStringUTFChars(env, socketName, NULL);
    const char *dir = (*env)->GetStringUTFChars(env, storageDir, NULL);
    LOGI("nativeStartDaemon socket=%s storageDir=%s", name, dir);
    int res = usbmuxd_embedded_start(name, dir);
    (*env)->ReleaseStringUTFChars(env, socketName, name);
    (*env)->ReleaseStringUTFChars(env, storageDir, dir);
    return (jint)res;
}

JNIEXPORT void JNICALL
Java_com_aistudio_iossideloadbridge_app_usb_UsbBridge_nativeStopDaemon(
        JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    LOGI("nativeStopDaemon");
    usbmuxd_embedded_stop();
}
