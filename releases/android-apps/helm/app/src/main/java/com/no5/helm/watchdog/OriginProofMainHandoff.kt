package com.no5.helm.watchdog

import android.os.Handler
import android.os.Looper

/** Preserve Main ownership without queueing an already-Main proof a second time.
 * The task itself retains the lifecycle/context/whole-enqueue-age checks. */
internal fun deliverOriginProofOnMain(main: Handler, task: Runnable): Boolean {
    if (Looper.myLooper() == main.looper) { task.run(); return true }
    return main.post(task)
}
