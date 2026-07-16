package top.iwesley.lyn.music.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileHandle
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.fileHandleForReadingFromURL
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal suspend fun readIosRemoteBytes(target: String): ByteArray? =
    runCatching { readIosRemoteBytesOrThrow(target) }.getOrNull()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal suspend fun readIosRemoteBytesOrThrow(target: String): ByteArray = withContext(Dispatchers.Default) {
    val url = NSURL.URLWithString(target) ?: error("远端封面 URL 无效。")
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        NSData.create(contentsOfURL = url, options = 0u, error = error.ptr)?.toByteArray()
            ?: throw IllegalStateException(iosRemoteReadErrorMessage(error.value))
    }
}

private fun iosRemoteReadErrorMessage(error: NSError?): String {
    return if (error == null) {
        "远端封面读取失败。"
    } else {
        "NSError domain=${error.domain} code=${error.code} description=${error.localizedDescription}"
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun readIosLocalBytes(path: String): ByteArray? {
    val file = fopen(path, "rb") ?: return null
    return try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val byteCount = ftell(file).toInt()
        if (byteCount < 0) return null
        if (fseek(file, 0, SEEK_SET) != 0) return null
        val byteArray = ByteArray(byteCount)
        val bytesRead = byteArray.usePinned { pinned ->
            fread(
                pinned.addressOf(0).reinterpret<ByteVar>(),
                1.convert(),
                byteCount.convert(),
                file,
            ).toInt()
        }
        if (bytesRead != byteCount) return null
        byteArray
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun readIosFileBytesUpTo(url: NSURL, maxBytes: Long): ByteArray? {
    require(maxBytes in 1 until Int.MAX_VALUE.toLong())
    val capacity = (maxBytes + 1L).toInt()
    val output = ByteArray(capacity)
    return memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val handle = NSFileHandle.fileHandleForReadingFromURL(url, error = error.ptr) ?: return@memScoped null
        try {
            var totalBytesRead = 0
            while (totalBytesRead < capacity) {
                error.value = null
                val requestedBytes = minOf(IOS_BOUNDED_READ_CHUNK_BYTES, capacity - totalBytesRead)
                val data = handle.readDataUpToLength(requestedBytes.toULong(), error = error.ptr)
                    ?: return@memScoped null
                if (error.value != null) return@memScoped null
                val chunk = data.toByteArray()
                if (chunk.isEmpty()) break
                chunk.copyInto(output, destinationOffset = totalBytesRead)
                totalBytesRead += chunk.size
            }
            if (totalBytesRead > maxBytes) null else output.copyOf(totalBytesRead)
        } finally {
            handle.closeAndReturnError(null)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun writeIosFileBytes(path: String, bytes: ByteArray): Boolean {
    val file = fopen(path, "wb") ?: return false
    return try {
        val written = bytes.usePinned { pinned ->
            fwrite(
                pinned.addressOf(0),
                1.convert(),
                bytes.size.convert(),
                file,
            ).toInt()
        }
        written == bytes.size
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun filePathFromIosLocator(target: String): String {
    return if (target.startsWith("file://", ignoreCase = true)) {
        NSURL.URLWithString(target)?.path ?: target.removePrefix("file://")
    } else {
        target
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val byteCount = length.toInt()
    if (byteCount <= 0) return ByteArray(0)
    val byteArray = ByteArray(byteCount)
    byteArray.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return byteArray
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.convert())
    }
}

private const val IOS_BOUNDED_READ_CHUNK_BYTES = 64 * 1024
