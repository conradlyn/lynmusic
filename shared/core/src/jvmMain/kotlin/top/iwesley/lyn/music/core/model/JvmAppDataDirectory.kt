package top.iwesley.lyn.music.core.model

import java.io.File

object JvmAppDataDirectory {
    @Volatile
    private var configuredRootDirectory: File? = null

    fun initialize(rootDirectory: File) {
        configuredRootDirectory = rootDirectory.absoluteFile.normalize()
    }

    fun rootDirectory(): File {
        return configuredRootDirectory ?: defaultRootDirectory()
    }

    fun resolve(relativePath: String): File = File(rootDirectory(), relativePath)

    fun defaultRootDirectory(userHome: String = System.getProperty("user.home")): File {
        return File(File(userHome), ".lynmusic").absoluteFile.normalize()
    }
}
