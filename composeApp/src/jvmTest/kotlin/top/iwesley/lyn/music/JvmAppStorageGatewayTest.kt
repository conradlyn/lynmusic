package top.iwesley.lyn.music

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import top.iwesley.lyn.music.core.model.AppStorageCategory
import top.iwesley.lyn.music.platform.JvmAppStorageGateway

class JvmAppStorageGatewayTest {
    @Test
    fun `gateway reads and clears configured cache directories`() = runTest {
        val root = Files.createTempDirectory("lynmusic-storage-test")
        root.resolve("artwork-cache").createDirectories()
        root.resolve("artwork").createDirectories()
        root.resolve("cache").createDirectories()
        root.resolve("offline").createDirectories()
        root.resolve("artwork-cache/cover-a.jpg").writeText("1234")
        root.resolve("artwork/cover-b.jpg").writeText("12")
        root.resolve("cache/track.bin").writeText("123456")
        root.resolve("offline/song.mp3").writeText("12345")

        val gateway = JvmAppStorageGateway(root.toFile())

        val initial = gateway.loadStorageSnapshot().getOrThrow()
        assertEquals(6L, initial.categories.first { it.category == AppStorageCategory.PlaybackCache }.sizeBytes)
        assertEquals(6L, initial.categories.first { it.category == AppStorageCategory.Artwork }.sizeBytes)
        assertEquals(5L, initial.categories.first { it.category == AppStorageCategory.OfflineDownloads }.sizeBytes)
        assertEquals(17L, initial.totalSizeBytes)
        assertEquals(listOf(root.toFile().absolutePath), initial.paths)

        gateway.clearCategory(AppStorageCategory.Artwork).getOrThrow()

        val cleared = gateway.loadStorageSnapshot().getOrThrow()
        assertEquals(0L, cleared.categories.first { it.category == AppStorageCategory.Artwork }.sizeBytes)
        assertEquals(6L, cleared.categories.first { it.category == AppStorageCategory.PlaybackCache }.sizeBytes)
        assertEquals(5L, cleared.categories.first { it.category == AppStorageCategory.OfflineDownloads }.sizeBytes)
        assertEquals(11L, cleared.totalSizeBytes)

        gateway.clearCategory(AppStorageCategory.OfflineDownloads).getOrThrow()

        val offlineCleared = gateway.loadStorageSnapshot().getOrThrow()
        assertEquals(0L, offlineCleared.categories.first { it.category == AppStorageCategory.OfflineDownloads }.sizeBytes)
        assertEquals(6L, offlineCleared.totalSizeBytes)
    }

    @Test
    fun `clearing cache does not follow a symbolic link outside data root`() = runTest {
        val root = Files.createTempDirectory("lynmusic-storage-link-test")
        val cache = root.resolve("cache").createDirectories()
        val external = Files.createTempDirectory("lynmusic-storage-external")
        val sentinel = external.resolve("keep.txt").apply { writeText("keep") }
        val link = cache.resolve("external-link")
        if (runCatching { Files.createSymbolicLink(link, external) }.isFailure) return@runTest
        val gateway = JvmAppStorageGateway(root.toFile())

        gateway.clearCategory(AppStorageCategory.PlaybackCache).getOrThrow()

        assertEquals("keep", sentinel.toFile().readText())
        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }
}
