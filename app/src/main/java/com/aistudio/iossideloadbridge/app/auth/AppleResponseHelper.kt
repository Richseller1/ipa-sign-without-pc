package com.aistudio.iossideloadbridge.app.auth

/**
 * Shared response parser and validator for Apple GrandSlam (GSA) and Developer Services.
 *
 * Solves the wrapper-level issue across all endpoints (init, complete, validate, apptokens,
 * and Developer Services) by locating Status/resultCode/ec/em at any level:
 * - Top-level dictionary
 * - Nested inside "Response"
 * - Nested inside "Status"
 *
 * Success definition:
 * HTTP 2xx AND (ec/resultCode absent OR ec/resultCode == 0)
 */
object AppleResponseHelper {

    data class ParsedStatus(
        val isSuccess: Boolean,
        val httpCode: Int,
        val errorCode: Int?, // null if absent
        val errorMessage: String?, // null if absent
        val rawErrorCodeStr: String?,
        val activeDict: Map<String, Any?>, // Root or unwrapped "Response" dictionary
        val statusDict: Map<String, Any?>?, // Located Status dictionary if found
        val topLevelKeys: List<String>,
        val activeKeys: List<String>,
        val hasStatus: Boolean
    )

    /**
     * Parses the status of any Apple GSA or Developer Services XML plist response.
     */
    fun parseStatus(
        parsed: Map<String, Any?>,
        httpCode: Int
    ): ParsedStatus {
        @Suppress("UNCHECKED_CAST")
        val responseDict = parsed["Response"] as? Map<String, Any?>
        val activeDict = responseDict ?: parsed

        @Suppress("UNCHECKED_CAST")
        val statusDict = (responseDict?.get("Status") as? Map<String, Any?>)
            ?: (parsed["Status"] as? Map<String, Any?>)
            ?: (findNestedDict(parsed, "Status"))

        val rawEc = statusDict?.get("ec")
            ?: activeDict["ec"]
            ?: parsed["ec"]
            ?: activeDict["resultCode"]
            ?: parsed["resultCode"]

        val rawEm = statusDict?.get("em")
            ?: activeDict["em"]
            ?: parsed["em"]
            ?: activeDict["userString"]
            ?: parsed["userString"]
            ?: activeDict["responseStatus"]
            ?: parsed["responseStatus"]

        val errorCode: Int? = when (rawEc) {
            is Number -> rawEc.toInt()
            is String -> rawEc.trim().toIntOrNull()
            else -> null
        }

        val errorMessage: String? = rawEm?.toString()?.trim()?.ifBlank { null }
        val rawErrorCodeStr = rawEc?.toString()?.trim()?.ifBlank { null }

        val isHttpOk = httpCode in 200..299
        val isEcOk = (errorCode == null || errorCode == 0)
        val isSuccess = isHttpOk && isEcOk
        val hasStatus = statusDict != null || rawEc != null

        return ParsedStatus(
            isSuccess = isSuccess,
            httpCode = httpCode,
            errorCode = errorCode,
            errorMessage = errorMessage,
            rawErrorCodeStr = rawErrorCodeStr,
            activeDict = activeDict,
            statusDict = statusDict,
            topLevelKeys = parsed.keys.sorted(),
            activeKeys = activeDict.keys.sorted(),
            hasStatus = hasStatus
        )
    }

    private fun findNestedDict(map: Map<String, Any?>, targetKey: String): Map<String, Any?>? {
        for ((k, v) in map) {
            if (k.equals(targetKey, ignoreCase = true) && v is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                return v as Map<String, Any?>
            }
            if (v is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val found = findNestedDict(v as Map<String, Any?>, targetKey)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Formats a clear, diagnostic error message for display and logging.
     * Complies with Task 4: Never shows "ec=null, em=null".
     * If no Status is found, says: "no Status in response, keys=[...]".
     */
    fun formatErrorMessage(stepName: String, status: ParsedStatus): String {
        val stepPrefix = if (stepName.isNotEmpty()) "[$stepName] " else ""
        return if (!status.hasStatus) {
            "${stepPrefix}HTTP ${status.httpCode}: no Status in response, keys=${status.topLevelKeys}"
        } else {
            val ecStr = status.rawErrorCodeStr ?: "null"
            val emStr = status.errorMessage ?: when (status.errorCode) {
                -20101 -> "Your Apple ID or password was incorrect."
                -20102 -> "This account has been temporarily locked for security reasons."
                -21669 -> "Two-factor authentication code required."
                -22406 -> "Incorrect Apple ID credentials."
                1100 -> "Your session has expired. Please sign in again."
                else -> null
            }
            if (emStr != null) {
                "${stepPrefix}HTTP ${status.httpCode}, ec=$ecStr, em=$emStr"
            } else {
                "${stepPrefix}HTTP ${status.httpCode}, ec=$ecStr"
            }
        }
    }
}
