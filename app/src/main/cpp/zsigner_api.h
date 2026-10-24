#pragma once

#include <string>
#include <vector>
#include <cstdint>

/**
 * Signs an IPA file using zsign engine.
 * Returns 0 on success, non-zero on failure (with errorMsg populated).
 */
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
);

/**
 * Inspects code signature of an IPA archive or Mach-O binary.
 */
std::string zsign_inspect_signature(const std::string& path);
