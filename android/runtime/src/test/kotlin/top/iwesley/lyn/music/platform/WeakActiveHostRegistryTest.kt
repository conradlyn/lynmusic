package top.iwesley.lyn.music.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WeakActiveHostRegistryTest {
    @Test
    fun oldHostCannotClearNewHost() {
        val registry = WeakActiveHostRegistry<Any>()
        val oldHost = Any()
        val newHost = Any()

        assertNull(registry.bind(oldHost))
        assertSame(oldHost, registry.bind(newHost))

        assertFalse(registry.unbind(oldHost))
        assertSame(newHost, registry.current())
        assertTrue(registry.unbind(newHost))
        assertNull(registry.current())
    }
}
