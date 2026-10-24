#include "zsigner_api.h"

#include <unistd.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <ctime>
#include <vector>
#include <string>

#include "common.h"
#include "openssl.h"
#include "bundle.h"
#include "archive.h"
#include "macho.h"
#include "certcheck.h"

static bool writeBytesToFile(const std::string& path, const uint8_t* data, size_t len, mode_t mode = 0600) {
    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, mode);
    if (fd < 0) return false;
    size_t written = 0;
    while (written < len) {
        ssize_t n = write(fd, data + written, len - written);
        if (n <= 0) {
            close(fd);
            return false;
        }
        written += n;
    }
    close(fd);
    return true;
}

static bool writeStringToFile(const std::string& path, const std::string& content, mode_t mode = 0600) {
    return writeBytesToFile(path, (const uint8_t*)content.data(), content.size(), mode);
}

int zsign_sign_ipa(
    const std::string& ipaPath,
    const std::string& certPem,
    const std::string& keyPem,
    const std::vector<uint8_t>& provBytes,
    const std::string& outIpaPath,
    const std::string& tempDir,
    const std::string& bundleId,
    const std::string& appName,
    std::string& errorMsg
) {
    if (ipaPath.empty() || certPem.empty() || keyPem.empty() || outIpaPath.empty() || tempDir.empty()) {
        errorMsg = "Missing required arguments for IPA signing";
        return -1;
    }
    if (provBytes.empty()) {
        errorMsg = "Missing provisioning profile data";
        return -2;
    }

    ZFile::CreateFolder(tempDir.c_str());

    uint64_t sessionTimestamp = (uint64_t)time(NULL);
    pid_t pid = getpid();
    std::string sessionPrefix = tempDir + "/sign_" + std::to_string(pid) + "_" + std::to_string(sessionTimestamp);

    std::string certFile = sessionPrefix + "_cert.pem";
    std::string keyFile = sessionPrefix + "_key.pem";
    std::string provFile = sessionPrefix + "_profile.mobileprovision";
    std::string workFolder = sessionPrefix + "_payload";

    if (!writeStringToFile(certFile, certPem, 0600)) {
        errorMsg = "Could not write certificate scratch file";
        return -3;
    }
    if (!writeStringToFile(keyFile, keyPem, 0600)) {
        unlink(certFile.c_str());
        errorMsg = "Could not write private key scratch file";
        return -4;
    }
    if (!writeBytesToFile(provFile, provBytes.data(), provBytes.size(), 0600)) {
        unlink(certFile.c_str());
        unlink(keyFile.c_str());
        errorMsg = "Could not write provisioning profile scratch file";
        return -5;
    }

    auto cleanupScratch = [&]() {
        unlink(certFile.c_str());
        unlink(keyFile.c_str());
        unlink(provFile.c_str());
        if (ZFile::IsFolder(workFolder.c_str())) {
            ZFile::RemoveFolder(workFolder.c_str());
        }
    };

    ZSignAsset zsa;
    if (!zsa.Init(certFile, keyFile, provFile, "", "", false, false, false)) {
        cleanupScratch();
        errorMsg = "Failed to parse and validate signing certificate, private key, or provisioning profile";
        return -6;
    }

    if (!Zip::Extract(ipaPath.c_str(), workFolder.c_str())) {
        cleanupScratch();
        errorMsg = "Failed to extract IPA zip archive";
        return -7;
    }

    ZBundle bundle;
    std::vector<std::string> arrDylibFiles;
    std::vector<std::string> arrRemoveDylibNames;
    bool bForce = true;
    bool bWeakInject = false;
    bool bEnableCache = false;
    bool bRemoveProvision = false;

    bool signOk = bundle.SignFolder(
        &zsa,
        workFolder,
        bundleId,
        "", // retain existing version
        appName,
        arrDylibFiles,
        arrRemoveDylibNames,
        bForce,
        bWeakInject,
        bEnableCache,
        bRemoveProvision
    );

    if (!signOk) {
        cleanupScratch();
        errorMsg = "Mach-O code signing failed on application bundle or dylibs";
        return -8;
    }

    size_t pos = bundle.m_strAppFolder.rfind("Payload");
    std::string archiveSourceFolder = workFolder;
    if (pos != std::string::npos && pos > 0) {
        archiveSourceFolder = bundle.m_strAppFolder.substr(0, pos - 1);
    }

    if (!Zip::Archive(archiveSourceFolder.c_str(), outIpaPath.c_str(), 0)) {
        cleanupScratch();
        errorMsg = "Failed to package signed application into final .ipa archive";
        return -9;
    }

    cleanupScratch();
    return 0;
}

std::string zsign_inspect_signature(const std::string& path) {
    if (path.empty() || !ZFile::IsFileExists(path.c_str())) {
        return "ERROR: File does not exist";
    }

    if (ZFile::IsZipFile(path.c_str())) {
        int status = CheckCertificate(path, "");
        switch (status) {
            case 0: return "VALID (Signature verified, certificate valid)";
            case 1: return "REVOKED (Certificate has been revoked)";
            case 2: return "EXPIRED (Certificate has expired)";
            case -2: return "UNSIGNED (No code signature found)";
            default: return "ERROR (Failed to parse signature or certificate)";
        }
    }

    ZMachO macho;
    if (!macho.Init(path.c_str())) {
        return "ERROR: Not a valid Mach-O binary";
    }
    bool signedOk = macho.CheckSignature();
    return signedOk ? "SIGNED_OK" : "UNSIGNED";
}
