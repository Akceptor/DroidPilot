package org.droidpilot

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Final, non-suspending service resource/state mutation after asynchronous teardown. */
internal suspend fun <T> serviceMutation(owner:Job,dispatcher:CoroutineDispatcher,block:()->T):T =
    withContext(dispatcher) {
        // Keep the request's Job, and also reject work after service destruction.
        // The check and mutation run together on the thread that calls onDestroy.
        owner.ensureActive()
        block()
    }
