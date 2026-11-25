package org.droidpilot

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ServiceMutationTest {
    private class QueuedDispatcher:CoroutineDispatcher() {
        val tasks=ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context:CoroutineContext,block:Runnable) { tasks.add(block) }
        fun drain() { while(true) (tasks.poll() ?: return).run() }
    }

    @Test fun `destroy before queued commit prevents resource acquisition`() = runBlocking {
        val owner=Job()
        val main=QueuedDispatcher()
        var acquired=false
        val apply=async(start=CoroutineStart.UNDISPATCHED) {
            serviceMutation(owner,main) { acquired=true }
        }
        // onDestroy runs on Main before the queued Apply continuation.
        owner.cancel()
        main.drain()
        apply.join()
        assertFalse("A stopped service must not acquire resources",acquired)
        assertTrue(apply.isCancelled)
    }

    @Test fun `live commit executes only on service dispatcher`() = runBlocking {
        val owner=Job()
        val main=QueuedDispatcher()
        var started=false
        val apply=async(start=CoroutineStart.UNDISPATCHED) {
            serviceMutation(owner,main) { started=true;42 }
        }
        assertFalse(started)
        main.drain()
        assertEquals(42,apply.await())
        assertTrue(started)
        owner.cancel()
    }

    @Test fun `cancelled request cannot commit into live service`() = runBlocking {
        val owner=Job()
        val main=QueuedDispatcher()
        var changed=false
        val pause=async(start=CoroutineStart.UNDISPATCHED) {
            serviceMutation(owner,main) { changed=true }
        }
        pause.cancel()
        main.drain()
        assertFalse(changed)
        assertTrue(owner.isActive)
        owner.cancel()
    }
}
