package top.iwesley.lyn.music.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateTest {
    @Test
    fun `version comparison detects patch segment update`() {
        assertTrue(isAppReleaseNewer(currentVersionName = "1.0.8", releaseTagName = "v1.0.8.1"))
    }

    @Test
    fun `version comparison treats same release as current`() {
        assertFalse(isAppReleaseNewer(currentVersionName = "1.0.8", releaseTagName = "v1.0.8"))
    }

    @Test
    fun `version comparison treats prerelease lower than stable release`() {
        assertFalse(isAppReleaseNewer(currentVersionName = "2.0.0", releaseTagName = "v2.0.0-rc1"))
        assertTrue(isAppReleaseNewer(currentVersionName = "2.0.0-rc1", releaseTagName = "v2.0.0"))
    }
}
