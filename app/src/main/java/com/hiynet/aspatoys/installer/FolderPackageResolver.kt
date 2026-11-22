package com.hiynet.aspatoys.installer

import android.net.Uri
import android.provider.DocumentsContract
import com.hiynet.cesiumloader.core.AndroidPaths
import java.net.URLDecoder

object FolderPackageResolver {
    const val DefaultDataRoot = "/sdcard/Android/data"
    private const val UnityAddressablesFolder = "com.unity.addressables"

    fun isSelectableComFolder(folderName: String?): Boolean {
        val trimmed = folderName?.trim() ?: return false
        if (trimmed.equals(UnityAddressablesFolder, ignoreCase = true)) return false
        return AndroidPaths.isComPackage(trimmed)
    }

    fun fromPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val segments = path.split('/', '\\')
        for (i in segments.lastIndex downTo 0) {
            val candidate = segments[i].trim()
            if (isSelectableComFolder(candidate)) {
                return candidate
            }
        }
        return null
    }

    fun fromTreeDocumentId(documentId: String?): String? {
        if (documentId.isNullOrBlank()) return null
        val pathPart = documentId.substringAfterLast(':', missingDelimiterValue = documentId)
        return fromPath(pathPart)
    }

    fun fromTreeUriString(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        val cleaned = uriString.substringBefore('?').substringBefore('#')
        val pathOnly = if ("://" in cleaned) {
            val afterScheme = cleaned.substringAfter("://")
            val firstSlash = afterScheme.indexOf('/')
            if (firstSlash < 0) return null
            afterScheme.substring(firstSlash)
        } else {
            cleaned
        }
        val candidateSegments = buildList {
            if ("/document/" in pathOnly) {
                add(pathOnly.substringAfter("/document/").removeSuffix("/children"))
            }
            if ("/tree/" in pathOnly) {
                add(pathOnly.substringAfter("/tree/").substringBefore("/document/").removeSuffix("/children"))
            }
            if (isEmpty()) {
                add(pathOnly)
            }
        }
        for (raw in candidateSegments) {
            val decoded = decodePercent(raw)
            fromTreeDocumentId(decoded)?.let { return it }
            fromPath(decoded)?.let { return it }
        }
        return null
    }

    fun fromTreeUri(uri: Uri?): String? {
        if (uri == null) return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        fromTreeDocumentId(docId)?.let { return it }
        return fromTreeUriString(uri.toString())
    }

    fun sortFolders(
        folders: Collection<String>,
        recognizedPackages: Set<String> = emptySet(),
    ): List<String> =
        folders
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(
                compareByDescending<String> { it in recognizedPackages || AndroidPaths.isGamePackage(it) }
                    .thenByDescending(::isSelectableComFolder)
                    .thenBy { it.lowercase() }
                    .thenBy { it },
            )
            .toList()

    fun parentPath(path: String): String? {
        val normalized = path.trim().trimEnd('/', '\\')
        if (normalized.isEmpty()) return null
        val slash = maxOf(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'))
        return when {
            slash < 0 -> null
            slash == 0 -> "/"
            else -> normalized.substring(0, slash)
        }
    }

    private fun decodePercent(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name()) }
            .getOrDefault(value)
}
