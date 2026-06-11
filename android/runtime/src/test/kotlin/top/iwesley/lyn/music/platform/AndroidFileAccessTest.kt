package top.iwesley.lyn.music.platform

import android.os.Environment
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidFileAccessTest {
    @Test
    fun `open document tree package rejects framework package stubs`() {
        assertFalse(isSupportedOpenDocumentTreePackage("com.android.tv.frameworkpackagestubs"))
    }

    @Test
    fun `open document tree package rejects package names containing framework package stubs marker`() {
        assertFalse(isSupportedOpenDocumentTreePackage("com.oem.frameworkpackagestubs.documents"))
    }

    @Test
    fun `open document tree package accepts regular documents ui package`() {
        assertTrue(isSupportedOpenDocumentTreePackage("com.google.android.documentsui"))
    }

    @Test
    fun `open document tree package rejects missing package names`() {
        assertFalse(isSupportedOpenDocumentTreePackage(null))
        assertFalse(isSupportedOpenDocumentTreePackage(""))
        assertFalse(isSupportedOpenDocumentTreePackage("   "))
    }

    @Test
    fun `readable storage volume state accepts mounted read write volumes`() {
        assertTrue(isReadableStorageVolumeState(Environment.MEDIA_MOUNTED))
    }

    @Test
    fun `readable storage volume state accepts mounted read only volumes`() {
        assertTrue(isReadableStorageVolumeState(Environment.MEDIA_MOUNTED_READ_ONLY))
    }

    @Test
    fun `readable storage volume state rejects unavailable volumes`() {
        assertFalse(isReadableStorageVolumeState(Environment.MEDIA_UNMOUNTED))
        assertFalse(isReadableStorageVolumeState(null))
    }
}
