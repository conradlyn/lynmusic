package top.iwesley.lyn.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.iwesley.lyn.music.platform.JvmDesktopResourceGuard
import top.iwesley.lyn.music.platform.addSuppressedSafely

class JvmDesktopResourceGuardTest {
    @Test
    fun `guard closes resources in reverse order and continues after failures`() = runTest {
        val calls = mutableListOf<String>()
        val guard = JvmDesktopResourceGuard()
        guard.register { calls += "database" }
        guard.register {
            calls += "controls"
            error("controls failed")
        }
        guard.register { calls += "scope" }

        val result = guard.closeAll()

        assertTrue(result.isFailure)
        assertEquals(listOf("scope", "controls", "database"), calls)
        assertTrue(guard.closeAll().isSuccess)
        assertEquals(listOf("scope", "controls", "database"), calls)
    }

    @Test
    fun `transferred resource is not closed by guard`() = runTest {
        val calls = mutableListOf<String>()
        val guard = JvmDesktopResourceGuard()
        guard.register { calls += "database" }
        val transferred = guard.register { calls += "runtime" }
        guard.transfer(transferred)

        guard.closeAll().getOrThrow()

        assertEquals(listOf("database"), calls)
    }

    @Test
    fun `retained http client closes before database and only once`() = runTest {
        val calls = mutableListOf<String>()
        val guard = JvmDesktopResourceGuard()
        guard.register { calls += "database" }
        guard.register { calls += "http" }

        guard.closeAll().getOrThrow()
        guard.closeAll().getOrThrow()

        assertEquals(listOf("http", "database"), calls)
    }

    @Test
    fun `repeated failure instance is returned without self suppression`() = runTest {
        val calls = mutableListOf<String>()
        val sharedFailure = IllegalStateException("shared failure")
        val guard = JvmDesktopResourceGuard()
        guard.register {
            calls += "database"
            throw sharedFailure
        }
        guard.register { calls += "http" }
        guard.register {
            calls += "service"
            throw sharedFailure
        }

        val invocation = runCatching { guard.closeAll() }

        assertTrue(invocation.isSuccess)
        val closeResult = invocation.getOrThrow()
        assertTrue(closeResult.isFailure)
        assertSame(sharedFailure, closeResult.exceptionOrNull())
        assertEquals(listOf("service", "http", "database"), calls)
        assertTrue(sharedFailure.suppressedExceptions.none { it === sharedFailure })
    }

    @Test
    fun `safe suppressed helper ignores self and duplicate instances`() {
        val primary = IllegalStateException("primary")
        val secondary = IllegalStateException("secondary")

        primary.addSuppressedSafely(primary)
        primary.addSuppressedSafely(secondary)
        primary.addSuppressedSafely(secondary)

        assertEquals(listOf(secondary), primary.suppressedExceptions.toList())
    }

    @Test
    fun `scope registered before graph construction is stopped before database on failure`() = runTest {
        val calls = mutableListOf<String>()
        val guard = JvmDesktopResourceGuard()
        guard.register { synchronized(calls) { calls += "database" } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        guard.register { scope.coroutineContext[Job]?.cancelAndJoin() }
        val childStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        scope.launch {
            childStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { synchronized(calls) { calls += "scope" } }
            }
        }
        childStarted.await()

        val constructionFailure = runCatching { error("SharedGraph construction failed") }
        assertTrue(constructionFailure.isFailure)

        guard.closeAll().getOrThrow()

        assertEquals(listOf("scope", "database"), synchronized(calls) { calls.toList() })
    }
}
