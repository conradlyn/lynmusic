package top.iwesley.lyn.music.feature.settings

import top.iwesley.lyn.music.core.model.LynMusicUpdateLinks

enum class AppUpdateUiStatus {
    Idle,
    Checking,
    Error,
    UpdateAvailable,
    UpToDate,
}

data class AppUpdateUiModel(
    val status: AppUpdateUiStatus,
    val message: String? = null,
    val latestVersion: String? = null,
    val downloadUrl: String = LynMusicUpdateLinks.RELEASES_URL,
    val errorMessage: String? = null,
)

fun SettingsState.toAppUpdateUiModel(): AppUpdateUiModel {
    val release = appUpdateLatestRelease
    return when {
        appUpdateChecking -> AppUpdateUiModel(
            status = AppUpdateUiStatus.Checking,
            message = "正在检查最新版本...",
        )

        release != null && appUpdateHasNewVersion == true -> AppUpdateUiModel(
            status = AppUpdateUiStatus.UpdateAvailable,
            message = "发现可用更新，可以到公众号获取云盘链接或者 GitHub 下载。",
            latestVersion = release.tagName,
            downloadUrl = release.htmlUrl.takeIf { it.isNotBlank() } ?: LynMusicUpdateLinks.RELEASES_URL,
            errorMessage = appUpdateError,
        )

        appUpdateError != null -> AppUpdateUiModel(
            status = AppUpdateUiStatus.Error,
            message = appUpdateError,
        )

        release != null && appUpdateHasNewVersion == false -> AppUpdateUiModel(
            status = AppUpdateUiStatus.UpToDate,
            message = "当前已是最新版本。",
        )

        else -> AppUpdateUiModel(status = AppUpdateUiStatus.Idle)
    }
}
