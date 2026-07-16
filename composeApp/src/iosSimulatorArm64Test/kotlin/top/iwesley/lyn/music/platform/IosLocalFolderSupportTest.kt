@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.iwesley.lyn.music.platform

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL

class IosLocalFolderSupportTest {
    @Test
    fun boundedReadAcceptsExactLimitAndRejectsOneExtraByte() {
        val root = createTemporaryDirectory("bounded-read")
        try {
            val exactPath = "$root/exact.lrc"
            val oversizedPath = "$root/oversized.lrc"
            val exactBytes = ByteArray(TEST_READ_LIMIT) { index -> (index % 127).toByte() }
            assertTrue(writeIosFileBytes(exactPath, exactBytes))
            assertTrue(writeIosFileBytes(oversizedPath, exactBytes + 1))

            assertContentEquals(
                exactBytes,
                readIosFileBytesUpTo(NSURL.fileURLWithPath(exactPath), TEST_READ_LIMIT.toLong()),
            )
            assertNull(
                readIosFileBytesUpTo(NSURL.fileURLWithPath(oversizedPath), TEST_READ_LIMIT.toLong()),
            )
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(root, error = null)
        }
    }

    @Test
    fun resourceValuesDetectSymlinksAndVisitedDirectoriesRejectCycles() {
        val root = createTemporaryDirectory("symlink-cycle")
        try {
            val nested = "$root/nested"
            assertTrue(
                NSFileManager.defaultManager.createDirectoryAtPath(
                    path = nested,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                ),
            )
            assertTrue(writeIosFileBytes("$root/a.mp3", byteArrayOf(1)))
            assertTrue(writeIosFileBytes("$nested/b.mp3", byteArrayOf(2)))
            assertTrue(
                NSFileManager.defaultManager.createSymbolicLinkAtPath(
                    path = "$root/link.mp3",
                    withDestinationPath = "$root/a.mp3",
                    error = null,
                ),
            )
            assertTrue(
                NSFileManager.defaultManager.createSymbolicLinkAtPath(
                    path = "$nested/loop",
                    withDestinationPath = root,
                    error = null,
                ),
            )
            val rootUrl = NSURL.fileURLWithPath(root, isDirectory = true)
            val fileLinkUrl = NSURL.fileURLWithPath("$root/link.mp3")
            val directoryLinkUrl = NSURL.fileURLWithPath("$nested/loop", isDirectory = true)

            assertFalse(readIosUrlResourceValues(rootUrl).isSymbolicLink)
            assertTrue(readIosUrlResourceValues(fileLinkUrl).isSymbolicLink)
            assertTrue(readIosUrlResourceValues(directoryLinkUrl).isSymbolicLink)

            val visitedDirectories = IosVisitedDirectories()
            assertTrue(visitedDirectories.markVisited(rootUrl, fileResourceIdentifier = null))
            assertFalse(visitedDirectories.markVisited(directoryLinkUrl, fileResourceIdentifier = null))
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(root, error = null)
        }
    }

    private fun createTemporaryDirectory(label: String): String {
        val root = NSTemporaryDirectory() + "lynmusic-$label-${Random.nextLong().toULong()}"
        assertTrue(
            NSFileManager.defaultManager.createDirectoryAtPath(
                path = root,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            ),
        )
        return root
    }

    private companion object {
        const val TEST_READ_LIMIT = 128 * 1024
    }
}
