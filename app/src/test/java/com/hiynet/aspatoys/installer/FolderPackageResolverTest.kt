package com.hiynet.aspatoys.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderPackageResolverTest {
    @Test
    fun isSelectableComFolderRecognizesValidComPackagesAndIgnoresInternalFolders() {
        assertTrue(FolderPackageResolver.isSelectableComFolder("com.feimo.astralparty"))
        assertTrue(FolderPackageResolver.isSelectableComFolder("com.jxpd.feimo.m4399"))
        assertTrue(FolderPackageResolver.isSelectableComFolder("com.custom.channel.game"))

        assertFalse(FolderPackageResolver.isSelectableComFolder("com.unity.addressables"))
        assertFalse(FolderPackageResolver.isSelectableComFolder("com.hiynet.aspatoys"))
        assertFalse(FolderPackageResolver.isSelectableComFolder("org.example.game"))
        assertFalse(FolderPackageResolver.isSelectableComFolder("Android"))
        assertFalse(FolderPackageResolver.isSelectableComFolder("com."))
        assertFalse(FolderPackageResolver.isSelectableComFolder(null))
        assertFalse(FolderPackageResolver.isSelectableComFolder("   "))
    }

    @Test
    fun fromPathExtractsDeepestSelectableComSegment() {
        assertEquals(
            "com.jxpd.feimo.m4399",
            FolderPackageResolver.fromPath("/sdcard/Android/data/com.jxpd.feimo.m4399"),
        )
        assertEquals(
            "com.feimo.astralparty.bilibili",
            FolderPackageResolver.fromPath("/storage/emulated/0/Android/data/com.feimo.astralparty.bilibili/"),
        )
        assertEquals(
            "com.custom.game",
            FolderPackageResolver.fromPath("/sdcard/Android/data/com.custom.game/files"),
        )
        assertEquals(
            "com.custom.game",
            FolderPackageResolver.fromPath("/sdcard/Android/data/com.custom.game/files/com.unity.addressables"),
        )
        assertNull(FolderPackageResolver.fromPath("/sdcard/Android/data/com.unity.addressables"))
        assertNull(FolderPackageResolver.fromPath("/sdcard/Android/data/com.hiynet.aspatoys"))
        assertNull(FolderPackageResolver.fromPath("/sdcard/Android/data/org.other.app"))
        assertNull(FolderPackageResolver.fromPath("/sdcard/Android/data"))
        assertNull(FolderPackageResolver.fromPath(""))
        assertNull(FolderPackageResolver.fromPath(null))
    }

    @Test
    fun fromTreeDocumentIdAndUriStringHandleSafFormats() {
        assertEquals(
            "com.jxpd.feimo.m4399",
            FolderPackageResolver.fromTreeDocumentId("primary:Android/data/com.jxpd.feimo.m4399"),
        )
        assertEquals(
            "com.custom.game",
            FolderPackageResolver.fromTreeDocumentId("1A2B-3C4D:Android/data/com.custom.game/files"),
        )
        assertEquals(
            "com.feimo.astralparty",
            FolderPackageResolver.fromTreeDocumentId("raw:/storage/emulated/0/Android/data/com.feimo.astralparty"),
        )
        assertNull(FolderPackageResolver.fromTreeDocumentId("primary:"))
        assertNull(FolderPackageResolver.fromTreeDocumentId("primary:Android/data"))

        assertEquals(
            "com.jxpd.feimo.m4399",
            FolderPackageResolver.fromTreeUriString(
                "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata%2Fcom.jxpd.feimo.m4399",
            ),
        )
        assertEquals(
            "com.custom.game",
            FolderPackageResolver.fromTreeUriString(
                "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata%2Fcom.custom.game/document/primary%3AAndroid%2Fdata%2Fcom.custom.game%2Ffiles%2Fcom.unity.addressables",
            ),
        )
        assertEquals(
            "com.feimo.astralparty.bilibili",
            FolderPackageResolver.fromTreeUriString(
                "file:///storage/emulated/0/Android/data/com.feimo.astralparty.bilibili",
            ),
        )
        assertNull(
            FolderPackageResolver.fromTreeUriString(
                "content://com.android.externalstorage.documents/tree/primary%3ADownload%2Forg.example",
            ),
        )
        assertNull(
            FolderPackageResolver.fromTreeUriString(
                "content://com.android.externalstorage.documents/root/primary",
            ),
        )
        assertNull(
            FolderPackageResolver.fromTreeUriString(
                "content://com.android.externalstorage.documents",
            ),
        )
    }

    @Test
    fun sortFoldersPinsRecognizedThenComThenOtherFolders() {
        val sorted = FolderPackageResolver.sortFolders(
            folders = listOf(
                "zz_folder",
                "com.z.other",
                "com.jxpd.feimo.m4399",
                "Android",
                "com.a.game",
                "com.custom.recognized",
                "com.unity.addressables",
                "com.hiynet.aspatoys",
                "com.a.game",
                "  ",
            ),
            recognizedPackages = setOf("com.custom.recognized"),
        )

        assertEquals(
            listOf(
                "com.custom.recognized",
                "com.jxpd.feimo.m4399",
                "com.a.game",
                "com.z.other",
                "Android",
                "com.hiynet.aspatoys",
                "com.unity.addressables",
                "zz_folder",
            ),
            sorted,
        )
    }

    @Test
    fun parentPathNavigatesUpCleanly() {
        assertEquals("/sdcard/Android", FolderPackageResolver.parentPath("/sdcard/Android/data"))
        assertEquals("/sdcard", FolderPackageResolver.parentPath("/sdcard/Android/"))
        assertEquals("/", FolderPackageResolver.parentPath("/sdcard"))
        assertNull(FolderPackageResolver.parentPath("/"))
        assertNull(FolderPackageResolver.parentPath(""))
    }
}
