package top.iwesley.lyn.music

import kotlin.test.Test
import kotlin.test.assertContains

class StartupErrorUiTest {
    @Test
    fun `startup database error details include stack trace and cause`() {
        val error = IllegalStateException(
            "component failed",
            IllegalArgumentException("root cause"),
        )

        val details = requireNotNull(startupDatabaseErrorDetails(error))

        assertContains(details, "java.lang.IllegalStateException: component failed")
        assertContains(details, "Caused by: java.lang.IllegalArgumentException: root cause")
        assertContains(details, "StartupErrorUiTest")
    }
}
