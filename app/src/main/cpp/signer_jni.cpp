#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "zsigner_api.h"

#define TAG "IpaSigner"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static std::string jstringToString(JNIEnv *env, jstring jstr) {
    if (!jstr) return "";
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    std::string str = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(jstr, chars);
    return str;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aistudio_iossideloadbridge_app_provisioning_IpaSigner_nativeSignIpa(
    JNIEnv *env,
    jclass clazz,
    jstring jIpaPath,
    jstring jCertPem,
    jstring jKeyPem,
    jbyteArray jProvData,
    jstring jOutIpaPath,
    jstring jTempDir,
    jstring jBundleId,
    jstring jAppName
) {
    std::string ipaPath = jstringToString(env, jIpaPath);
    std::string certPem = jstringToString(env, jCertPem);
    std::string keyPem = jstringToString(env, jKeyPem);
    std::string outIpaPath = jstringToString(env, jOutIpaPath);
    std::string tempDir = jstringToString(env, jTempDir);
    std::string bundleId = jstringToString(env, jBundleId);
    std::string appName = jstringToString(env, jAppName);

    std::vector<uint8_t> provBytes;
    if (jProvData != nullptr) {
        jsize len = env->GetArrayLength(jProvData);
        if (len > 0) {
            provBytes.resize(len);
            env->GetByteArrayRegion(jProvData, 0, len, (jbyte*)provBytes.data());
        }
    }

    LOGI("Calling zsign_sign_ipa for: %s -> %s", ipaPath.c_str(), outIpaPath.c_str());
    std::string errorMsg;
    int rc = zsign_sign_ipa(
        ipaPath,
        certPem,
        keyPem,
        provBytes,
        outIpaPath,
        tempDir,
        bundleId,
        appName,
        errorMsg
    );

    if (rc != 0) {
        LOGE("zsign_sign_ipa failed with code %d: %s", rc, errorMsg.c_str());
        std::string errStr = "ERROR: " + errorMsg;
        return env->NewStringUTF(errStr.c_str());
    }

    LOGI("zsign_sign_ipa succeeded: %s", outIpaPath.c_str());
    return env->NewStringUTF(outIpaPath.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aistudio_iossideloadbridge_app_provisioning_IpaSigner_nativeInspectSignature(
    JNIEnv *env,
    jclass clazz,
    jstring jIpaOrMachoPath
) {
    std::string path = jstringToString(env, jIpaOrMachoPath);
    std::string result = zsign_inspect_signature(path);
    return env->NewStringUTF(result.c_str());
}
