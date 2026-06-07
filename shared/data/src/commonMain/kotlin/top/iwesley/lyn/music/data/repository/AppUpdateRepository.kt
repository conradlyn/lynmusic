package top.iwesley.lyn.music.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.iwesley.lyn.music.core.model.AppReleaseInfo
import top.iwesley.lyn.music.core.model.LyricsHttpClient
import top.iwesley.lyn.music.core.model.LyricsRequest
import top.iwesley.lyn.music.core.model.LynMusicUpdateLinks
import top.iwesley.lyn.music.core.model.RequestMethod

interface AppUpdateRepository {
    suspend fun latestRelease(): Result<AppReleaseInfo>
}

class DefaultAppUpdateRepository(
    private val httpClient: LyricsHttpClient,
    private val latestReleaseUrl: String = LynMusicUpdateLinks.LATEST_RELEASE_API_URL,
    private val releasesUrl: String = LynMusicUpdateLinks.RELEASES_URL,
) : AppUpdateRepository {
    override suspend fun latestRelease(): Result<AppReleaseInfo> {
        return httpClient.request(
            LyricsRequest(
                method = RequestMethod.GET,
                url = latestReleaseUrl,
                headers = mapOf(
                    "Accept" to "application/vnd.github+json",
                    "User-Agent" to "LynMusic",
                ),
                timeoutMillis = APP_UPDATE_REQUEST_TIMEOUT_MILLIS,
            ),
        ).mapCatching { response ->
            if (response.statusCode !in 200..299) {
                error("检查更新失败：HTTP ${response.statusCode}")
            }
            if (response.body.isBlank()) {
                error("检查更新失败：响应为空。")
            }
            response.body.toAppReleaseInfo(releasesUrl)
        }
    }

    private fun String.toAppReleaseInfo(releasesUrl: String): AppReleaseInfo {
        val root = appUpdateJson.parseToJsonElement(this).jsonObject
        val tagName = root.stringField("tag_name").trim()
        if (tagName.isBlank()) {
            error("检查更新失败：缺少版本号。")
        }
        return AppReleaseInfo(
            tagName = tagName,
            name = root.stringField("name").ifBlank { tagName },
            body = root.stringField("body"),
            htmlUrl = root.stringField("html_url").ifBlank { releasesUrl },
            publishedAt = root.stringField("published_at"),
        )
    }

    private fun JsonObject.stringField(name: String): String {
        return this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private companion object {
        val appUpdateJson = Json {
            ignoreUnknownKeys = true
        }
    }
}

object UnsupportedAppUpdateRepository : AppUpdateRepository {
    override suspend fun latestRelease(): Result<AppReleaseInfo> {
        return Result.failure(UnsupportedOperationException("当前平台暂不支持检查更新。"))
    }
}

private const val APP_UPDATE_REQUEST_TIMEOUT_MILLIS = 15_000L
