package top.iwesley.lyn.music.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.iwesley.lyn.music.core.model.AppStorageCategory
import top.iwesley.lyn.music.core.model.AppStorageCategoryUsage
import top.iwesley.lyn.music.core.model.AppStorageGateway
import top.iwesley.lyn.music.core.model.AppStorageSnapshot
import top.iwesley.lyn.music.core.model.JvmAppDataDirectory
import top.iwesley.lyn.music.data.db.LynMusicDatabase

fun createJvmAppStorageGateway(
    rootDirectory: File = JvmAppDataDirectory.rootDirectory(),
    database: LynMusicDatabase? = null,
): AppStorageGateway = JvmAppStorageGateway(rootDirectory, database)

internal class JvmAppStorageGateway(
    private val rootDirectory: File,
    private val database: LynMusicDatabase? = null,
) : AppStorageGateway {
    override suspend fun loadStorageSnapshot(): Result<AppStorageSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val categories = listOf(
                AppStorageCategoryUsage(
                    category = AppStorageCategory.Artwork,
                    sizeBytes = listOf(
                        File(rootDirectory, "artwork-cache"),
                        File(rootDirectory, "artwork"),
                    ).sumOf(::directorySizeBytes),
                ),
                AppStorageCategoryUsage(
                    category = AppStorageCategory.PlaybackCache,
                    sizeBytes = directorySizeBytes(File(rootDirectory, "cache")),
                ),
                AppStorageCategoryUsage(
                    category = AppStorageCategory.OfflineDownloads,
                    sizeBytes = directorySizeBytes(File(rootDirectory, "offline")),
                ),
            )
            AppStorageSnapshot(
                totalSizeBytes = categories.sumOf { it.sizeBytes },
                categories = categories,
                paths = listOf(rootDirectory.absolutePath),
            )
        }
    }

    override suspend fun clearCategory(category: AppStorageCategory): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            when (category) {
                AppStorageCategory.Artwork -> {
                    listOf(
                        File(rootDirectory, "artwork-cache"),
                        File(rootDirectory, "artwork"),
                    ).forEach { directory ->
                        clearDirectory(directory)
                        directory.mkdirs()
                    }
                    Unit
                }

                AppStorageCategory.PlaybackCache -> {
                    val directory = File(rootDirectory, "cache")
                    clearDirectory(directory)
                    directory.mkdirs()
                    Unit
                }

                AppStorageCategory.OfflineDownloads -> {
                    val directory = File(rootDirectory, "offline")
                    clearDirectory(directory)
                    directory.mkdirs()
                    database?.offlineDownloadDao()?.deleteAll()
                    Unit
                }

                AppStorageCategory.LyricsShareTemp,
                AppStorageCategory.TagEditTemp,
                -> Unit
            }
        }
    }
}

private fun directorySizeBytes(root: File): Long {
    if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return 0L
    if (isJvmLinkOrReparsePoint(root.toPath())) return 0L
    if (root.isFile) return root.length()
    return root.listFiles().orEmpty().sumOf(::directorySizeBytes)
}

private fun clearDirectory(root: File) {
    if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return
    if (isJvmLinkOrReparsePoint(root.toPath())) {
        safeDeleteTree(root.toPath())
        return
    }
    Files.newDirectoryStream(root.toPath()).use { children ->
        children.forEach(::safeDeleteTree)
    }
}
