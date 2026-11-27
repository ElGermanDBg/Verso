package com.verso.lyrics

import com.verso.lyrics.update.AppUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun isNewerVersionComparesSemverCorrectly() {
        assertTrue(AppUpdateManager.isNewerVersion("1.0.1", "1.0.0"))
        assertTrue(AppUpdateManager.isNewerVersion("1.1.0", "1.0.9"))
        assertTrue(AppUpdateManager.isNewerVersion("v2.0.0", "v1.9.9"))
        assertFalse(AppUpdateManager.isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(AppUpdateManager.isNewerVersion("0.9.9", "1.0.0"))
    }

    @Test
    fun parseReleaseJsonExtractsApkAsset() {
        val json = """
            {
              "tag_name": "v1.1.0",
              "name": "Verso v1.1.0",
              "body": "Nuevos estilos de letra.",
              "assets": [
                {
                  "name": "Verso-v1.1.0.apk",
                  "size": 19500000,
                  "browser_download_url": "https://github.com/ElGermanDBg/Verso/releases/download/v1.1.0/Verso-v1.1.0.apk"
                }
              ]
            }
        """.trimIndent()

        val release = AppUpdateManager.parseReleaseJson(json, currentVersion = "1.0.0")
        assertNotNull(release)
        assertEquals("v1.1.0", release!!.tagName)
        assertEquals("1.1.0", release.versionName)
        assertEquals("Verso-v1.1.0.apk", release.apkFileName)
        assertEquals(19500000L, release.apkSizeBytes)
        assertTrue(release.isNewer)
    }
}
