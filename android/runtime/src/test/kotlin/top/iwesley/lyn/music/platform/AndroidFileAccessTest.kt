package top.iwesley.lyn.music.platform

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
}
