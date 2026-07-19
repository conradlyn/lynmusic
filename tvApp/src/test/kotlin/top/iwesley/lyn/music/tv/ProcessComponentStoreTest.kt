package top.iwesley.lyn.music.tv

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProcessComponentStoreTest {
    @Test
    fun createsComponentOnlyOnceUntilCleared() {
        var initializationCount = 0
        val store = ProcessComponentStore<Any>(factory = {
            initializationCount += 1
            Any()
        })

        val first = store.getOrCreate().getOrThrow()
        val second = store.getOrCreate().getOrThrow()

        assertSame(first, second)
        assertEquals(1, initializationCount)

        store.clear()
        val recreated = store.getOrCreate().getOrThrow()
        assertNotEquals(first, recreated)
        assertEquals(2, initializationCount)
    }

    @Test
    fun retriesInitializationAfterFailureAndCachesSuccess() {
        var initializationCount = 0
        var databaseAvailable = false
        val store = ProcessComponentStore<Any>(factory = {
            initializationCount += 1
            check(databaseAvailable) { "database unavailable" }
            Any()
        })

        assertTrue(store.getOrCreate().isFailure)
        databaseAvailable = true
        val recovered = store.getOrCreate().getOrThrow()
        val cached = store.getOrCreate().getOrThrow()

        assertEquals(2, initializationCount)
        assertSame(recovered, cached)
    }

    @Test
    fun concurrentSuccessfulReadsCreateOnlyOneComponent() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        var initializationCount = 0
        val store = ProcessComponentStore<Any>(factory = {
            initializationCount += 1
            Any()
        })

        try {
            val futures = List(8) {
                executor.submit<Any> {
                    start.await()
                    store.getOrCreate().getOrThrow()
                }
            }
            start.countDown()
            val components = futures.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, initializationCount)
            components.forEach { component -> assertSame(components.first(), component) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun clearDisposesOnlySuccessfulComponentOnce() {
        var databaseAvailable = false
        var disposed = false
        val component = Any()
        val store = ProcessComponentStore(factory = {
            check(databaseAvailable) { "database unavailable" }
            component
        })

        assertTrue(store.getOrCreate().isFailure)
        store.clear { disposed = true }
        assertFalse(disposed)

        databaseAvailable = true
        store.getOrCreate().getOrThrow()
        var disposalCount = 0
        store.clear { created ->
            assertSame(component, created)
            disposalCount += 1
        }
        store.clear { disposalCount += 1 }

        assertEquals(1, disposalCount)
    }
}
