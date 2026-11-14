package com.openminis.app.mibot.queue

import android.content.Context
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-scoped bridge from the durable task queue to the existing chat AgentLoop.
 * The queue remains a record/state store; this object is the executor.
 */
object TaskExecutionCoordinator {
    private const val TAG = "TaskExecutionCoordinator"
    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var scope: CoroutineScope? = null
    private var loop: Job? = null
    private var appContext: Context? = null
    private val running = mutableMapOf<String, Job>()

    @Synchronized
    fun start(context: Context, owner: CoroutineScope) {
        if (loop?.isActive == true) return
        appContext = context.applicationContext
        scope = owner
        val ctx = appContext ?: return
        val queue = TaskRunQueue.get(ctx)
        loop = owner.launch {
            // A process restart cannot resume an in-flight coroutine. Requeue the
            // durable marker, then let the same executor start it again.
            queue.requeueInterrupted()
            owner.launch {
                queue.runs.collect { wake.trySend(Unit) }
            }
            wake.trySend(Unit)
            for (signal in wake) {
                drain(ctx, queue)
            }
        }
    }

    fun signal() { wake.trySend(Unit) }

    fun cancel(context: Context, runId: String): Boolean {
        val cancelled = synchronized(running) { running.remove(runId)?.cancel(); true }
        TaskRunQueue.get(context.applicationContext).cancel(runId)
        wake.trySend(Unit)
        return cancelled
    }

    private suspend fun drain(context: Context, queue: TaskRunQueue) {
        lock.withLock {
            while (true) {
                val run = queue.current() ?: queue.startNext() ?: return
                if (running.containsKey(run.id)) return
                val child = scope?.launch(start = CoroutineStart.LAZY) { execute(context, queue, run) } ?: return
                synchronized(running) { running[run.id] = child }
                child.start()
                // The worker is intentionally serial. The existing ChatViewModel
                // handles prompt queuing, while one goal at a time keeps the durable
                // TaskRun state unambiguous and avoids duplicate model turns.
                return
            }
        }
    }

    private suspend fun execute(context: Context, queue: TaskRunQueue, run: TaskRun) {
        try {
            val sessionId = run.sessionId ?: HeadlessChatRunner.ensureSession(context)
            queue.attachSession(run.id, sessionId)
            val model = HeadlessChatRunner.applyModelOverride(context, sessionId, null, null)
            AppLogger.info(TAG, "starting task ${run.id.take(8)} session=${sessionId.take(8)} model=$model")
            val result = HeadlessChatRunner.prompt(
                context = context,
                sessionId = sessionId,
                text = run.goal,
                wait = true,
                timeoutMs = TASK_TIMEOUT_MS,
            )
            val current = queue.get(run.id)
            if (current?.state == TaskRunState.FAILED) return
            val failed = result.status !in setOf("Completed", "Running", "Queued") || result.timedOut
            queue.complete(
                run.id,
                failed = failed,
                resultText = result.responseText ?: result.status,
                error = if (failed) result.responseText ?: result.status else null,
            )
            AppLogger.info(TAG, "finished task ${run.id.take(8)} status=${result.status} failed=$failed")
        } catch (t: Throwable) {
            if (queue.get(run.id)?.state == TaskRunState.FAILED) return
            queue.complete(run.id, failed = true, error = "${t.javaClass.simpleName}: ${t.message}")
            AppLogger.error(TAG, "task ${run.id.take(8)} failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            synchronized(running) { running.remove(run.id) }
            wake.trySend(Unit)
        }
    }

    private const val TASK_TIMEOUT_MS = 30 * 60 * 1000L
}
