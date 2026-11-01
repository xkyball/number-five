package com.no5.helm.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Only public signatures documented in KNOB_DIAGNOSTIC.md. Never retains an Activity.
 * The firmware may retain a removed listener: reuse one proxy and invalidate posted callbacks.
 */
internal class QfKnobInput(
    private val context: Context,
    private val classes: (String) -> Class<*> = { Class.forName(it) },
    private val service: () -> Any? = { context.getSystemService("util_service") },
) : KnobEventSource {
    private val main = Handler(Looper.getMainLooper())
    private var listener: Any? = null
    private var manager: Any? = null
    private var remove: Method? = null
    private var receive: ((KnobEvent) -> Unit)? = null
    @Volatile private var generation = 0L
    @Volatile private var active = false
    /** True only after the OEM manager accepted this process' single listener. */
    @Volatile var isRegistered = false
        private set
    override fun start(receive: (KnobEvent) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        stop()
        this.receive = receive
        active = true
        try {
            val managerType = classes("android.qf.util.UtilEventManager")
            val listenerType = classes("android.qf.util.UtilEventListener")
            val infoType = classes("android.qf.util.QFKeyEventInfo")
            val wrapper = service() ?: return stop()
            if (!managerType.isInstance(wrapper) || !listenerType.isInterface) return stop()
            val rawCode = infoType.getMethod("getKeyCode")
            val keyEvent = infoType.getMethod("getKeyEventInfo")
            val proxy = listener ?: Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { instance, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(instance)
                    "equals" -> instance === args?.getOrNull(0)
                    "toString" -> "No5 foreground knob listener"
                    "onReceived" -> {
                        val token = generation
                        try {
                            val payload = args?.getOrNull(1)
                            val type = args?.getOrNull(0) as? Int
                            val key = payload?.let { keyEvent.invoke(it) as? KeyEvent }
                            val raw = payload?.let { rawCode.invoke(it) as? Int }
                            if (active && type != null && key != null && raw != null) {
                                // Copy primitives; OEM owns KeyEvent. Null/ambiguous payloads do not act.
                                val event = KnobEvent(QfKey(type, raw, key.keyCode, key.scanCode, key.deviceId),
                                    key.action, key.repeatCount, key.downTime, key.eventTime, key.flags)
                                main.post { if (token == generation) this.receive?.invoke(event) }
                            }
                        } catch (_: Exception) { /* malformed OEM payload: no action */ }
                        null
                    }
                    else -> null
                }
            }
            listener = proxy
            manager = wrapper
            remove = managerType.getMethod("RPC_RemoveListener", listenerType)
            managerType.getMethod("RPC_KeyEventChangedListener", listenerType).invoke(wrapper, proxy)
            isRegistered = true
        } catch (_: ReflectiveOperationException) { stop() }
          catch (_: RuntimeException) { stop() }
          catch (_: LinkageError) { stop() }
    }
    override fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        active = false
        isRegistered = false
        ++generation
        receive = null
        val wrapper = manager
        manager = null
        try { if (wrapper != null && listener != null) remove?.invoke(wrapper, listener) }
        catch (_: ReflectiveOperationException) { /* inactive guard remains authoritative */ }
        catch (_: RuntimeException) { }
    }
}

internal object HelmKnobInput {
    private var input: ForegroundKnobInput? = null
    fun acquire(context: Context, source: com.no5.domain.AudioSourcePort): AutoCloseable {
        check(Looper.myLooper() == Looper.getMainLooper())
        val hub = input ?: ForegroundKnobInput(QfKnobInput(context.applicationContext),
            KnobProfile.UNVERIFIED, SystemClock::uptimeMillis).also { input = it }
        return hub.acquire(source)
    }
}
