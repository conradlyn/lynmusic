package top.iwesley.lyn.music.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AndroidArtworkCacheStoreTest {
    @Test
    fun `shared artwork cache store reuses process instance`() {
        val cacheDirectory = Files.createTempDirectory("lynmusic-android-artwork-cache").toFile()
        try {
            SharedAndroidArtworkCacheStore.resetForTesting()

            val first = SharedAndroidArtworkCacheStore.get(cacheDirectory)
            val second = SharedAndroidArtworkCacheStore.get(cacheDirectory)

            assertTrue(first === second)
        } finally {
            SharedAndroidArtworkCacheStore.resetForTesting()
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun `shared artwork cache store publishes versions when cache is replaced`() = runBlocking {
        val cacheDirectory = Files.createTempDirectory("lynmusic-android-artwork-cache").toFile()
        val sourceDirectory = Files.createTempDirectory("lynmusic-android-artwork-source").toFile()
        try {
            SharedAndroidArtworkCacheStore.resetForTesting()
            val store = SharedAndroidArtworkCacheStore.get(cacheDirectory)
            val cacheKey = "album:source:artist:album"
            val firstSource = File(sourceDirectory, "first.png").apply {
                writeBytes(completePngPayload())
            }
            val secondSource = File(sourceDirectory, "second.png").apply {
                writeBytes(completePngPayload())
            }

            assertEquals(0L, store.observeVersion(cacheKey).first())

            assertNotNull(store.cache(firstSource.absolutePath, cacheKey, replaceExisting = true))
            assertEquals(1L, store.observeVersion(cacheKey).first())

            assertNotNull(store.cache(secondSource.absolutePath, cacheKey, replaceExisting = true))
            assertEquals(2L, store.observeVersion(cacheKey).first())
        } finally {
            SharedAndroidArtworkCacheStore.resetForTesting()
            cacheDirectory.deleteRecursively()
            sourceDirectory.deleteRecursively()
        }
    }
}

private fun completePngPayload(): ByteArray {
    return byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
        0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )
}
