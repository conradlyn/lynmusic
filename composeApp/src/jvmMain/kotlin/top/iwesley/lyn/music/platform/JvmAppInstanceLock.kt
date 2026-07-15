package top.iwesley.lyn.music.platform

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

internal class JvmAppInstanceLock private constructor(
    private val channel: FileChannel,
    private val fileLock: FileLock,
) : AutoCloseable {
    override fun close() {
        runCatching { fileLock.release() }
        runCatching { channel.close() }
    }

    companion object {
        fun tryAcquire(userHomeDirectory: File = File(System.getProperty("user.home"))): JvmAppInstanceLock? {
            val lockFile = File(userHomeDirectory, ".lynmusic-app.lock")
            lockFile.parentFile?.mkdirs()
            val channel = FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
            if (lock == null) {
                channel.close()
                return null
            }
            return JvmAppInstanceLock(channel, lock)
        }
    }
}
