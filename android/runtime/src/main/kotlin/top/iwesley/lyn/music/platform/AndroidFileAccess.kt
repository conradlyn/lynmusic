package top.iwesley.lyn.music.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File
import java.net.URI

internal data class AndroidStorageRoot(
    val label: String,
    val root: File,
    val isRemovable: Boolean,
)

internal fun hasManageAllFilesAccess(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
}

internal fun hasDirectLocalFileAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        hasManageAllFilesAccess(context)
    } else {
        hasLegacyExternalStorageReadWriteAccess(context)
    }
}

internal fun shouldRequestLegacyDirectLocalFileAccess(context: Context): Boolean {
    return Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && !hasLegacyExternalStorageReadWriteAccess(context)
}

internal fun legacyDirectLocalFileAccessPermissions(): Array<String> {
    return arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )
}

internal fun legacyDirectLocalFileAccessGrantSummary(grants: Map<String, Boolean>): String {
    return "read=${grants[Manifest.permission.READ_EXTERNAL_STORAGE] == true} " +
        "write=${grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true}"
}

internal fun directLocalFileAccessPermissionLabel(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        "“管理所有文件”权限"
    } else {
        "存储读写权限"
    }
}

internal fun buildManageAllFilesAccessIntent(context: Context): Intent {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return Intent(Settings.ACTION_SETTINGS)
    }
    val appSpecificIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    return if (appSpecificIntent.resolveActivity(context.packageManager) != null) {
        appSpecificIntent
    } else {
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }
}

internal fun canResolveOpenDocumentTree(context: Context): Boolean {
    val packageName = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .resolveActivity(context.packageManager)
        ?.packageName
    return isSupportedOpenDocumentTreePackage(packageName)
}

internal fun isSupportedOpenDocumentTreePackage(packageName: String?): Boolean {
    val normalized = packageName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return false
    return !normalized.equals(FRAMEWORK_PACKAGE_STUBS_PACKAGE, ignoreCase = true) &&
        !normalized.contains(FRAMEWORK_PACKAGE_STUBS_MARKER, ignoreCase = true)
}

@Suppress("DEPRECATION")
internal fun listAndroidStorageRoots(context: Context): List<AndroidStorageRoot> {
    val primaryRoot = Environment.getExternalStorageDirectory()
    val normalizedPrimary = primaryRoot.canonicalFileOrSelf()
    val roots = linkedMapOf<String, AndroidStorageRoot>()

    fun addRoot(root: File, label: String, isRemovable: Boolean) {
        if (!root.exists() || !root.isDirectory) return
        val normalizedRoot = root.canonicalFileOrSelf()
        if (!roots.containsKey(normalizedRoot.absolutePath)) {
            roots[normalizedRoot.absolutePath] = AndroidStorageRoot(
                label = label,
                root = normalizedRoot,
                isRemovable = isRemovable,
            )
        }
    }

    addRoot(primaryRoot, label = "内置存储", isRemovable = false)
    context.getExternalFilesDirs(null)
        .asSequence()
        .filterNotNull()
        .mapNotNull(File::findStorageVolumeRoot)
        .map(File::canonicalFileOrSelf)
        .filterNot { root ->
            root == normalizedPrimary ||
                normalizedPrimary.absolutePath.startsWith(root.absolutePath + File.separator)
        }
        .forEach { root ->
            addRoot(
                root = root,
                label = "U 盘 ${root.name}",
                isRemovable = true,
            )
        }
    return roots.values.sortedWith(
        compareBy<AndroidStorageRoot> { it.isRemovable }
            .thenBy { it.label.lowercase() },
    )
}

internal fun resolveAndroidLocalTrackFile(locator: String): File? {
    val value = locator.trim()
    if (value.isBlank()) return null
    return runCatching {
        when {
            value.startsWith("file://", ignoreCase = true) -> File(URI(value))
            value.startsWith("/") -> File(value)
            Regex("^[A-Za-z]:[/\\\\].*").matches(value) -> File(value)
            else -> null
        }
    }.getOrNull()?.takeIf { it.isAbsolute }
}

internal fun resolveAndroidLocalTrackUri(locator: String): Uri? {
    val value = locator.trim()
    if (value.isBlank()) return null
    resolveAndroidLocalTrackFile(value)?.let { file ->
        return Uri.fromFile(file)
    }
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
    return when (uri.scheme?.lowercase()) {
        "content", "file" -> uri
        else -> null
    }
}

internal fun resolveTreeUriToDirectory(context: Context, treeUri: Uri): File? {
    if (!hasDirectLocalFileAccess(context)) return null
    if (treeUri.authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) return null
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
    val volumeId = documentId.substringBefore(':').trim()
    if (volumeId.isBlank()) return null
    val relativePath = documentId.substringAfter(':', "").trim('/')
    val volumeRoot = resolveStorageVolumeRoot(context, volumeId) ?: return null
    return if (relativePath.isBlank()) volumeRoot else File(volumeRoot, relativePath)
}

@Suppress("DEPRECATION")
private fun resolveStorageVolumeRoot(context: Context, volumeId: String): File? {
    if (volumeId.equals(PRIMARY_VOLUME_ID, ignoreCase = true)) {
        return Environment.getExternalStorageDirectory()
    }
    return context.getExternalFilesDirs(null)
        .asSequence()
        .filterNotNull()
        .mapNotNull(File::findStorageVolumeRoot)
        .firstOrNull { root -> root.name.equals(volumeId, ignoreCase = true) }
}

private fun File.findStorageVolumeRoot(): File? {
    var current: File? = this
    while (current != null) {
        if (current.parentFile?.absolutePath == STORAGE_ROOT_PATH) {
            return current
        }
        current = current.parentFile
    }
    return null
}

private fun File.canonicalFileOrSelf(): File {
    return runCatching { canonicalFile }.getOrDefault(absoluteFile)
}

private fun hasLegacyExternalStorageReadWriteAccess(context: Context): Boolean {
    return legacyDirectLocalFileAccessPermissions().all { permission ->
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}

private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"
private const val PRIMARY_VOLUME_ID = "primary"
private const val STORAGE_ROOT_PATH = "/storage"
private const val FRAMEWORK_PACKAGE_STUBS_PACKAGE = "com.android.tv.frameworkpackagestubs"
private const val FRAMEWORK_PACKAGE_STUBS_MARKER = "frameworkpackagestubs"
