package top.iwesley.lyn.music.platform

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class SerializedActivityRequestGateTest {
    @Test
    fun serializesOverlappingRequests() = runBlocking {
        val gate = SerializedActivityRequestGate()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val first = async(Dispatchers.Default) {
            gate.runOrNull {
                firstStarted.complete(Unit)
                releaseFirst.await()
                "first"
            }
        }
        firstStarted.await()
        val second = async(Dispatchers.Default) {
            gate.runOrNull {
                secondStarted.complete(Unit)
                "second"
            }
        }

        assertNull(withTimeoutOrNull(100L) { secondStarted.await() })
        releaseFirst.complete(Unit)

        assertEquals("first", first.await())
        assertEquals("second", second.await())
        assertEquals(Unit, secondStarted.await())
    }

    @Test
    fun invalidationPreventsQueuedRequestFromRunningOnOldHost() = runBlocking {
        val gate = SerializedActivityRequestGate()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondBlockStarted = AtomicBoolean(false)

        val first = async(Dispatchers.Default) {
            gate.runOrNull {
                firstStarted.complete(Unit)
                releaseFirst.await()
                null
            }
        }
        firstStarted.await()
        val second = async(Dispatchers.Default) {
            gate.runOrNull {
                secondBlockStarted.set(true)
                "second"
            }
        }

        gate.invalidatePendingRequests()
        releaseFirst.complete(Unit)

        assertNull(first.await())
        assertNull(second.await())
        assertFalse(secondBlockStarted.get())
    }

    @Test
    fun reactivationAllowsNewHostLifecycleRequests() = runBlocking {
        val gate = SerializedActivityRequestGate()
        gate.invalidatePendingRequests()

        assertNull(gate.runOrNull { "blocked" })

        gate.activate()
        assertEquals("active", gate.runOrNull { "active" })
    }

    @Test
    fun reactivationDoesNotReviveRequestsQueuedBeforeInvalidation() = runBlocking {
        val gate = SerializedActivityRequestGate()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val staleBlockStarted = AtomicBoolean(false)

        val first = async(Dispatchers.Default) {
            gate.runOrNull {
                firstStarted.complete(Unit)
                releaseFirst.await()
                "first"
            }
        }
        firstStarted.await()
        val stale = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            gate.runOrNull {
                staleBlockStarted.set(true)
                "stale"
            }
        }

        gate.invalidatePendingRequests()
        gate.activate()
        val fresh = async(Dispatchers.Default) {
            gate.runOrNull { "fresh" }
        }
        releaseFirst.complete(Unit)

        assertEquals("first", first.await())
        assertNull(stale.await())
        assertFalse(staleBlockStarted.get())
        assertEquals("fresh", fresh.await())
    }

    @Test
    fun reactivationDoesNotReviveRequestSubmittedWhileInactive() = runBlocking {
        val gate = SerializedActivityRequestGate()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val inactiveBlockStarted = AtomicBoolean(false)

        val first = async(Dispatchers.Default) {
            gate.runOrNull {
                firstStarted.complete(Unit)
                releaseFirst.await()
                "first"
            }
        }
        firstStarted.await()
        gate.invalidatePendingRequests()

        val inactive = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            gate.runOrNull {
                inactiveBlockStarted.set(true)
                "inactive"
            }
        }
        gate.activate()
        releaseFirst.complete(Unit)

        assertEquals("first", first.await())
        assertNull(inactive.await())
        assertFalse(inactiveBlockStarted.get())
        assertEquals("fresh", gate.runOrNull { "fresh" })
    }
}
