package top.iwesley.lyn.music.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

class AndroidDateChangeCollectorTest {
    @Test
    fun collectorStopsWhenManagedScopeIsCancelled() = runBlocking {
        val events = MutableSharedFlow<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var refreshCount = 0
        val collector = scope.launchActivityResumedDateRefreshCollector(events) {
            refreshCount += 1
        }

        events.emit(Unit)
        assertEquals(1, refreshCount)

        scope.cancel()
        collector.join()
        events.emit(Unit)

        assertTrue(collector.isCancelled)
        assertEquals(1, refreshCount)
    }
}
