package com.no5.helm.input

import android.os.Looper
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
class QfKnobInputTest {
    interface Listener { fun onReceived(type: Int, info: Info) }
    class Info(private val key: KeyEvent?) {
        fun getKeyCode() = 901
        fun getKeyEventInfo() = key
    }
    class Manager {
        val listeners = mutableSetOf<Listener>()
        var current: Listener? = null
        var removals = 0
        fun RPC_KeyEventChangedListener(listener: Listener) { listeners.add(listener); current = listener }
        fun RPC_RemoveListener(listener: Listener) { removals++; current = null }
    }
    private fun reader(manager: Manager) = QfKnobInput(RuntimeEnvironment.getApplication(), { name ->
        when (name.substringAfterLast('.')) {
            "UtilEventManager" -> Manager::class.java
            "UtilEventListener" -> Listener::class.java
            else -> Info::class.java
        }
    }, { manager })
    @Test fun oneProxyAcrossStartsAndQueuedCallbacksCannotCrossOwnership() {
        val manager = Manager(); val input = reader(manager)
        val first = mutableListOf<KnobEvent>(); val second = mutableListOf<KnobEvent>()
        input.start(first::add)
        assertTrue(input.isRegistered)
        manager.current!!.onReceived(7, Info(KeyEvent(100, 100, KeyEvent.ACTION_DOWN, 902, 0, 0, 904, 903)))
        input.stop(); input.start(second::add)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(first.isEmpty()); assertTrue(second.isEmpty())
        assertEquals(1, manager.listeners.size)
        manager.current!!.onReceived(7, Info(KeyEvent(101, 101, KeyEvent.ACTION_DOWN, 902, 0, 0, 904, 903)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(QfKey(7, 901, 902, 903, 904), second.single().key)
        input.stop()
        assertFalse(input.isRegistered)
        manager.listeners.single().onReceived(7, Info(KeyEvent(KeyEvent.ACTION_DOWN, 902)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, second.size)
    }
    @Test fun absentApiAndNullPayloadProduceNoAction() {
        val manager = Manager(); val input = reader(manager); var count = 0
        input.start { count++ }; manager.current!!.onReceived(7, Info(null))
        shadowOf(Looper.getMainLooper()).idle(); input.stop()
        val absent = QfKnobInput(RuntimeEnvironment.getApplication(), { throw ClassNotFoundException(it) })
        absent.start { count++ }; absent.stop()
        assertEquals(0, count)
    }
}
