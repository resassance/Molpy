package app.musicplayer.catalog

import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistPageCacheTest {
    @Test
    fun liveWaiterRetriesWithItsOwnLoadAfterProducerCancellation() = runTest {
        val cache = PlaylistPageCache<String, String>()
        val producerStarted = CompletableDeferred<Unit>()
        val producer = async {
            cache.get("page") {
                producerStarted.complete(Unit)
                awaitCancellation()
            }
        }
        producerStarted.await()
        var waiterLoads = 0
        val waiter = async {
            cache.get("page") {
                waiterLoads++
                "service-page"
            }
        }
        runCurrent()
        assertFalse(waiter.isCompleted)
        assertEquals(0, waiterLoads)
        producer.cancel()
        producer.join()
        val result = runCatching { waiter.await() }
        assertEquals(
            "service-page",
            result.getOrNull(),
            "Independent waiter inherited producer cancellation: $result",
        )
        assertEquals(1, waiterLoads)
        assertFalse(waiter.isCancelled)
        assertEquals("service-page", cache.get("page") { error("Successful retry must be cached") })
    }

    @Test
    fun canceledWaiterDoesNotRestartTheRequest() = runTest {
        val cache = PlaylistPageCache<String, String>()
        val gate = CompletableDeferred<String>()
        val producer = async { cache.get("page") { gate.await() } }
        runCurrent()
        var waiterLoads = 0
        val waiter = async {
            cache.get("page") {
                waiterLoads++
                "unexpected"
            }
        }
        runCurrent()
        waiter.cancel()
        runCurrent()
        producer.cancel()
        producer.join()
        assertFailsWith<CancellationException> { waiter.await() }
        assertEquals(0, waiterLoads)
        assertEquals("new-page", cache.get("page") { "new-page" })
    }

    @Test
    fun successfulProducerSharesOneValueAndCachesIt() = runTest {
        val cache = PlaylistPageCache<String, String>()
        val gate = CompletableDeferred<String>()
        var producerLoads = 0
        val producer = async {
            cache.get("page") {
                producerLoads++
                gate.await()
            }
        }
        runCurrent()
        var waiterLoads = 0
        val waiters =
            List(3) {
                async {
                    cache.get("page") {
                        waiterLoads++
                        "unexpected"
                    }
                }
            }
        runCurrent()
        gate.complete("shared-page")
        assertEquals("shared-page", producer.await())
        assertEquals(listOf("shared-page", "shared-page", "shared-page"), waiters.awaitAll())
        assertEquals("shared-page", cache.get("page") { error("Cached page must not be fetched") })
        assertEquals(1, producerLoads)
        assertEquals(0, waiterLoads)
    }

    @Test
    fun providerFailurePropagatesWithoutRetryingTheWaiterLoad() = runTest {
        val cache = PlaylistPageCache<String, String>()
        val gate = CompletableDeferred<Unit>()
        val failure = IOException("controlled provider failure")
        var waiterLoads = 0
        supervisorScope {
            val producer = async {
                cache.get("page") {
                    gate.await()
                    throw failure
                }
            }
            runCurrent()
            val waiter = async {
                cache.get("page") {
                    waiterLoads++
                    "unexpected"
                }
            }
            runCurrent()
            gate.complete(Unit)
            assertEquals(
                "controlled provider failure",
                assertFailsWith<IOException> { producer.await() }.message,
            )
            assertEquals(
                "controlled provider failure",
                assertFailsWith<IOException> { waiter.await() }.message,
            )
        }
        assertEquals(0, waiterLoads)
        assertEquals("retry-page", cache.get("page") { "retry-page" })
    }
}
