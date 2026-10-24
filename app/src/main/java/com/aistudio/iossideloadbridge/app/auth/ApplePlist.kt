package com.aistudio.iossideloadbridge.app.auth

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.Base64 as JavaBase64

/**
 * Lightweight, zero-dependency Property List (XML plist) serializer and parser
 * specifically designed for Apple's GrandSlam Authentication (GSA) payloads.
 */
object ApplePlist {

    private fun base64Encode(bytes: ByteArray): String {
        return try {
            JavaBase64.getEncoder().encodeToString(bytes)
        } catch (_: Throwable) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }

    private fun base64Decode(str: String): ByteArray {
        return try {
            JavaBase64.getDecoder().decode(str)
        } catch (_: Throwable) {
            android.util.Base64.decode(str, android.util.Base64.DEFAULT)
        }
    }

    /**
     * Serializes a dictionary map into standard Apple XML Property List format.
     */
    fun toXml(root: Map<String, Any?>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
        sb.append("<plist version=\"1.0\">\n")
        writeDict(sb, root, 0)
        sb.append("</plist>\n")
        return sb.toString()
    }

    private fun writeDict(sb: StringBuilder, map: Map<String, Any?>, indentLevel: Int) {
        val indent = "    ".repeat(indentLevel)
        val childIndent = "    ".repeat(indentLevel + 1)
        sb.append(indent).append("<dict>\n")
        for ((key, value) in map) {
            sb.append(childIndent).append("<key>").append(escapeXml(key)).append("</key>\n")
            writeValue(sb, value, indentLevel + 1)
        }
        sb.append(indent).append("</dict>\n")
    }

    private fun writeValue(sb: StringBuilder, value: Any?, indentLevel: Int) {
        val indent = "    ".repeat(indentLevel)
        when (value) {
            null -> {
                sb.append(indent).append("<string></string>\n")
            }
            is String -> {
                sb.append(indent).append("<string>").append(escapeXml(value)).append("</string>\n")
            }
            is ByteArray -> {
                val b64 = base64Encode(value)
                sb.append(indent).append("<data>").append(b64).append("</data>\n")
            }
            is Int, is Long, is Short, is Byte -> {
                sb.append(indent).append("<integer>").append(value.toString()).append("</integer>\n")
            }
            is Boolean -> {
                if (value) {
                    sb.append(indent).append("<true/>\n")
                } else {
                    sb.append(indent).append("<false/>\n")
                }
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                writeDict(sb, value as Map<String, Any?>, indentLevel)
            }
            is List<*> -> {
                sb.append(indent).append("<array>\n")
                for (item in value) {
                    writeValue(sb, item, indentLevel + 1)
                }
                sb.append(indent).append("</array>\n")
            }
            else -> {
                sb.append(indent).append("<string>").append(escapeXml(value.toString())).append("</string>\n")
            }
        }
    }

    private fun escapeXml(str: String): String {
        return str
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * Parses an Apple XML Property List into Kotlin nested Maps, Lists, Strings, and ByteArrays.
     */
    fun fromXml(xml: String): Map<String, Any?> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "dict") {
                @Suppress("UNCHECKED_CAST")
                return parseDict(parser) as Map<String, Any?>
            }
            eventType = parser.next()
        }
        return emptyMap()
    }

    private fun parseDict(parser: XmlPullParser): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        var currentKey: String? = null

        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "key" -> {
                            currentKey = parser.nextText()
                        }
                        else -> {
                            val value = parseValue(parser)
                            if (currentKey != null) {
                                result[currentKey] = value
                                currentKey = null
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "dict") {
                        return result
                    }
                }
            }
            eventType = parser.next()
        }
        return result
    }

    private fun parseArray(parser: XmlPullParser): List<Any?> {
        val list = mutableListOf<Any?>()
        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    list.add(parseValue(parser))
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "array") {
                        return list
                    }
                }
            }
            eventType = parser.next()
        }
        return list
    }

    private fun parseValue(parser: XmlPullParser): Any? {
        val tag = parser.name
        return when (tag) {
            "dict" -> parseDict(parser)
            "array" -> parseArray(parser)
            "string" -> parser.nextText()
            "integer" -> parser.nextText().toLongOrNull() ?: 0L
            "real" -> parser.nextText().toDoubleOrNull() ?: 0.0
            "true" -> {
                if (parser.isEmptyElementTag) {
                    parser.next()
                } else {
                    while (parser.next() != XmlPullParser.END_TAG) {}
                }
                true
            }
            "false" -> {
                if (parser.isEmptyElementTag) {
                    parser.next()
                } else {
                    while (parser.next() != XmlPullParser.END_TAG) {}
                }
                false
            }
            "data" -> {
                val b64 = parser.nextText().replace("\\s+".toRegex(), "")
                try {
                    base64Decode(b64)
                } catch (_: Exception) {
                    ByteArray(0)
                }
            }
            "date" -> parser.nextText()
            else -> {
                parser.nextText()
            }
        }
    }
}
