package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.ChatMessage
import dev.woms.mumdroid.service.SessionChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * The chat log is appended from the service's scope, which runs on
 * `Dispatchers.Default` (a thread pool), so append has to be a CAS update.
 * The old read-modify-write (`_messages.value = _messages.value + message`)
 * dropped lines whenever two appends interleaved.
 */
class SessionChatConcurrencyTest {

    @Test
    fun concurrentAppends_loseNothing() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val chat = SessionChat(scope)
            val threads = 8
            val perThread = 250
            // Start everyone at once so the appends actually interleave.
            val start = CountDownLatch(1)
            val workers = (0 until threads).map { t ->
                Thread {
                    start.await()
                    repeat(perThread) { i ->
                        chat.appendSync(ChatMessage(actorName = "u$t", text = "m$i"))
                    }
                }
            }
            workers.forEach { it.start() }
            start.countDown()
            workers.forEach { it.join() }

            assertEquals(threads * perThread, chat.messages.value.size)
        } finally {
            scope.cancel()
        }
    }
}
