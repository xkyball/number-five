package com.no5.helm.network

import com.no5.domain.SourceIdentity
import com.no5.domain.SourceMode
import com.no5.domain.TelemetryStatus
import org.json.JSONObject
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.test.*

/** Raw TCP fixture works with the Android test compile classpath, without jdk.httpserver. */
private class LoopbackServer : AutoCloseable {
    private val server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
    val port = server.localPort
    private val handlers = java.util.concurrent.ConcurrentHashMap<String, (Exchange) -> Unit>()
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "test-http-handler").apply { isDaemon = true } }
    private val acceptor = thread(isDaemon = true) {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            socket.soTimeout = 2_000
            executor.execute {
                try {
                    socket.use {
                        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val request = input.readLine()?.split(' ') ?: return@execute
                        val headers = Headers()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            headers.add(line.substringBefore(':'), line.substringAfter(':').trim())
                        }
                        handlers[request[1]]?.invoke(Exchange(socket, request[0], headers))
                    }
                } catch (_: IOException) { } catch (_: InterruptedException) { }
            }
        }
    }
    fun createContext(path: String, handler: (Exchange) -> Unit) { handlers[path] = handler }
    override fun close() { server.close(); executor.shutdownNow(); acceptor.join(1_000) }

    class Headers {
        private val entries = linkedMapOf<String, String>()
        fun add(name: String, value: String) { entries[name.lowercase()] = value }
        fun getFirst(name: String): String? = entries[name.lowercase()]
        fun lines(): String = entries.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
    }
    class Exchange(private val socket: Socket, val requestMethod: String, val requestHeaders: Headers) {
        val responseHeaders = Headers()
        lateinit var responseBody: OutputStream
        fun sendResponseHeaders(status: Int, length: Long) {
            val output = socket.getOutputStream()
            val chunked = length == 0L
            val lengthHeader = if (chunked) "Transfer-Encoding: chunked\r\n" else "Content-Length: ${length.coerceAtLeast(0)}\r\n"
            output.write(("HTTP/1.1 $status Test\r\n" + responseHeaders.lines() + lengthHeader + "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.flush()
            responseBody = if (!chunked) output else object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()))
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    if (count == 0) return
                    output.write("${count.toString(16)}\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(bytes, offset, count)
                    output.write("\r\n".toByteArray(Charsets.US_ASCII))
                }
                override fun flush() = output.flush()
                override fun close() { output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII)); output.close() }
            }
        }
        fun close() = socket.close()
    }
}

class PiHttpTransportTest {
    private fun withServer(block: (LoopbackServer, String) -> Unit) {
        LoopbackServer().use { block(it, "http://127.0.0.1:${it.port}") }
    }
    private val quickPolicy = PiNetworkPolicy(10, 500, 500, 1_000)
    @Test fun navigationAndSelectionUseOnlyTheirExplicitGatewayPaths() = withServer { server, endpoint ->
        val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
        for (path in listOf("/v1/audio/navigation", "/v1/audio/select")) server.createContext(path) { exchange ->
            methods += exchange.requestMethod
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 2); exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        UrlConnectionPiTransport(endpoint, quickPolicy).use { transport ->
            assertEquals("{}", transport.get("/v1/audio/navigation"))
            assertEquals("{}", transport.post("/v1/audio/select", "{}"))
            assertFailsWith<IllegalArgumentException> { transport.post("/v1/audio/select?uri=other", "{}") }
            assertFailsWith<IllegalArgumentException> { transport.get("/v1/audio/navigation/other") }
        }
        assertEquals(listOf("GET", "POST"), methods)
    }

    @Test fun artworkReadsUseBinaryMimeBoundsAndRestrictedGatewayPaths() = withServer { server, endpoint ->
        val path = "/v1/audio/artwork/" + "a".repeat(64)
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        server.createContext(path) { exchange ->
            assertEquals("image/jpeg, image/png", exchange.requestHeaders.getFirst("Accept"))
            exchange.responseHeaders.add("Content-Type", "image/jpeg")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        UrlConnectionPiTransport(endpoint, quickPolicy).use { transport ->
            assertContentEquals(bytes, transport.getBytes(path))
            assertFailsWith<IllegalArgumentException> { transport.getBytes("https://evil/private") }
            assertFailsWith<IllegalArgumentException> { transport.getBytes(path + "?key=bad") }
        }
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, 4); exchange.responseBody.use { it.write(bytes) }
        }
        UrlConnectionPiTransport(endpoint, quickPolicy).use { transport -> assertFailsWith<IOException> { transport.getBytes(path) } }
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, 5000); exchange.responseBody.use { it.write(ByteArray(5000)) }
        }
        UrlConnectionPiTransport(endpoint, quickPolicy.copy(maxResponseBytes=100)).use { transport -> assertFailsWith<IOException> { transport.getBytes(path) } }
    }

    @Test fun realHttpTransportDecodesCanonicalSimulatorFixtureThroughSource() = withServer { server, endpoint ->
        val body = wireFixture("helm").toByteArray()
        val calls = AtomicInteger()
        server.createContext("/v1/helm") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            assertEquals("application/json", exchange.requestHeaders.getFirst("Accept"))
            calls.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val received = CountDownLatch(1)
        val source = NetworkPiHelmSource(
            SourceIdentity("loopback", SourceMode.PI, "LOOPBACK TEST"),
            UrlConnectionPiTransport(endpoint, quickPolicy),
            now = { JSONObject(wireFixture("helm")).getLong("timestampEpochMs") },
            policy = quickPolicy,
        )
        source.snapshots.subscribe { if (it.quality.status == TelemetryStatus.LIVE) received.countDown() }
        try {
            assertTrue(received.await(2, TimeUnit.SECONDS))
            assertTrue(calls.get() >= 1)
            assertEquals(0.0, source.snapshots.value.speed)
            assertEquals(12.0, source.snapshots.value.depth)
            assertTrue(source.snapshots.value.quality.simulated)
        } finally { source.close() }
    }

    @Test fun healthyResponsesReuseOnePersistentSocket() {
        ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val count = AtomicInteger()
            val done = CountDownLatch(1)
            val worker = thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 2_000
                        socket.tcpNoDelay = true
                        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val output = socket.getOutputStream()
                        repeat(30) {
                            if (input.readLine() == null) return@thread
                            while (!input.readLine().isNullOrEmpty()) { }
                            count.incrementAndGet()
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
                            output.flush()
                        }
                    }
                } finally { done.countDown() }
            }
            UrlConnectionPiTransport("http://127.0.0.1:${server.localPort}", quickPolicy).use { transport ->
                repeat(30) { assertEquals("{}", transport.get("/v1/helm")) }
            }
            assertTrue(done.await(3, TimeUnit.SECONDS))
            worker.join(1_000)
            assertEquals(30, count.get(), "All requests must use the one accepted socket")
        }
    }

    @Test fun simulatorAudioCommandsAreBoundedAndCannotWriteHelmOrHardware() = withServer { server, endpoint ->
        val calls = AtomicInteger()
        server.createContext("/v1/audio/command") { exchange ->
            calls.incrementAndGet()
            assertEquals("POST", exchange.requestMethod)
            assertEquals("application/json", exchange.requestHeaders.getFirst("Content-Type"))
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        UrlConnectionPiTransport(endpoint, PiNetworkPolicy.AUDIO).use { transport ->
            assertEquals("{}", transport.post("/v1/audio/command", "{\"command\":\"play\"}"))
            assertFailsWith<IllegalArgumentException> { transport.post("/v1/helm", "{}") }
            assertFailsWith<IllegalArgumentException> { transport.post("/v1/simulation", "{}") }
            assertFailsWith<IllegalArgumentException> { transport.post("/v1/audio/command", "x".repeat(4097)) }
            assertEquals(1, calls.get())
        }
    }

    @Test fun audioWaitsForDelayedPlayerReadbackWithoutReplayingCommand() = withServer { server, endpoint ->
        val calls = AtomicInteger()
        server.createContext("/v1/audio/command") { exchange ->
            calls.incrementAndGet()
            // The real lab PAUSE observation took 2.3 s. A 1.5 s socket
            // timeout loses its ACK even though the receiver applied it.
            Thread.sleep(2_100)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        UrlConnectionPiTransport(endpoint, PiNetworkPolicy.AUDIO).use { transport ->
            assertEquals("{}", transport.post("/v1/audio/command", "{}"))
        }
        assertEquals(1, calls.get())
    }

    @Test fun redirectsAreNeverFollowed() = withServer { server, endpoint ->
        val followed = AtomicInteger()
        server.createContext("/v1/helm") { exchange ->
            exchange.responseHeaders.add("Location", "$endpoint/redirected")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/redirected") { exchange -> followed.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        UrlConnectionPiTransport(endpoint, quickPolicy).use { transport ->
            assertFailsWith<IOException> { transport.get("/v1/helm") }
        }
        assertEquals(0, followed.get())
    }

    @Test fun immediateSequentialSuccessfulRequestsDoNotRaceWorkerCompletion() = withServer { server, endpoint ->
        server.createContext("/v1/helm") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        UrlConnectionPiTransport(endpoint, quickPolicy).use { transport ->
            repeat(100) { assertEquals("{}", transport.get("/v1/helm")) }
        }
    }

    @Test fun wrongMediaTypeAndOversizedKnownOrChunkedBodiesAreRejected() = withServer { server, endpoint ->
        var mode = 0
        server.createContext("/v1/helm") { exchange ->
            val body = ByteArray(300) { 'x'.code.toByte() }
            exchange.responseHeaders.add("Content-Type", if (mode == 0) "text/html" else "application/json")
            exchange.sendResponseHeaders(200, if (mode == 2) 0 else body.size.toLong())
            try { exchange.responseBody.use { it.write(body) } } catch (_: IOException) { }
        }
        UrlConnectionPiTransport(endpoint, quickPolicy.copy(maxResponseBytes = 200)).use { transport ->
            repeat(3) { mode = it; assertFailsWith<IOException> { transport.get("/v1/helm") } }
        }
    }

    @Test fun readTimeoutAndClosedTransportFailWithoutUnboundedBlocking() = withServer { server, endpoint ->
        server.createContext("/v1/helm") { exchange ->
            Thread.sleep(400)
            try { exchange.sendResponseHeaders(200, -1) } catch (_: IOException) { }
            exchange.close()
        }
        val transport = UrlConnectionPiTransport(endpoint, quickPolicy.copy(readTimeoutMs = 50, requestTimeoutMs = 200))
        val start = System.nanoTime()
        assertFailsWith<IOException> { transport.get("/v1/helm") }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000)
        transport.close(); transport.close()
        assertFailsWith<IOException> { transport.get("/v1/helm") }
        assertFailsWith<IllegalArgumentException> { transport.get("/v1/simulation") }
    }

    @Test fun malformedUtf8IsRejected() = withServer { server, endpoint ->
        server.createContext("/v1/helm") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write(byteArrayOf(0xc3.toByte(), 0x28)) }
        }
        UrlConnectionPiTransport(endpoint, quickPolicy).use { transport ->
            assertFailsWith<IOException> { transport.get("/v1/helm") }
        }
    }

    @Test fun slowDripCannotExtendOverallRequestDeadline() = withServer { server, endpoint ->
        server.createContext("/v1/helm") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 100)
            try {
                exchange.responseBody.use { output ->
                    repeat(100) { output.write(' '.code); output.flush(); Thread.sleep(20) }
                }
            } catch (_: IOException) { } catch (_: InterruptedException) { }
        }
        UrlConnectionPiTransport(endpoint, quickPolicy.copy(readTimeoutMs = 1_000, requestTimeoutMs = 200)).use { transport ->
            val startedAt = System.nanoTime()
            assertFailsWith<SocketTimeoutException> { transport.get("/v1/helm") }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 800)
        }
    }

    @Test fun closeDuringStreamingReturnsWithoutWaitingForConnectionReadLock() = withServer { server, endpoint ->
        val streaming = CountDownLatch(1)
        server.createContext("/v1/helm") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 100)
            try {
                exchange.responseBody.use { output ->
                    repeat(100) { output.write(' '.code); output.flush(); streaming.countDown(); Thread.sleep(20) }
                }
            } catch (_: IOException) { } catch (_: InterruptedException) { }
        }
        val transport = UrlConnectionPiTransport(endpoint, quickPolicy.copy(requestTimeoutMs = 5_000))
        val result = AtomicReference<Throwable?>()
        val caller = thread {
            try { transport.get("/v1/helm") } catch (failure: Throwable) { result.set(failure) }
        }
        assertTrue(streaming.await(2, TimeUnit.SECONDS))
        val startedAt = System.nanoTime()
        transport.close()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 300)
        caller.join(1_000)
        assertFalse(caller.isAlive)
        assertTrue(result.get() is IOException)
    }
    @Test fun playlistArtworkUsesSameBinaryPolicyAndRejectsUnreviewedPaths() = withServer { server, endpoint ->
        val path = "/v1/audio/playlist-artwork/" + "b".repeat(64)
        val bytes = ByteArray(20); var calls = 0
        server.createContext(path) { exchange ->
            calls++; exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        UrlConnectionPiTransport(endpoint, quickPolicy).use { transport ->
            assertContentEquals(bytes, transport.getBytes(path))
            for (bad in listOf(path + "?key=bad", path + "/other", path.replace("b", "B"),
                "/v1/audio/playlist-artwork/../private", "https://evil/$path"))
                assertFailsWith<IllegalArgumentException> { transport.getBytes(bad) }
        }
        assertEquals(1, calls)
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        UrlConnectionPiTransport(endpoint, quickPolicy).use { transport -> assertFailsWith<IOException> { transport.getBytes(path) } }
    }

    @Test fun originProofUsesExplicitBoundedReadWithoutChangingOrdinaryPaths() = withServer { server, endpoint ->
        val path="/v1/sonar/origin-clock-proof?nonce=0123456789abcdef"
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type","application/json")
            exchange.sendResponseHeaders(200,2);exchange.responseBody.use{it.write("{}".toByteArray())}
        }
        UrlConnectionPiTransport(endpoint,quickPolicy).use { transport ->
            assertEquals("{}",transport.getBounded(path,2000,2048))
            assertFailsWith<IllegalArgumentException>{transport.get(path)}
            assertFailsWith<IllegalArgumentException>{transport.getBounded("/v1/audio",2000,2048)}
            assertFailsWith<IllegalArgumentException>{transport.getBounded(path,2001,2048)}
        }
    }
    @Test fun recoveryInputProofHasTheSameRealBoundedTransportAndCannotUseOrdinaryReads() = withServer { server, endpoint ->
        val path="/v1/sonar/recovery-input-proof?nonce=0123456789abcdef"
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type","application/json")
            exchange.sendResponseHeaders(200,2);exchange.responseBody.use{it.write("{}".toByteArray())}
        }
        UrlConnectionPiTransport(endpoint,quickPolicy).use { transport ->
            assertEquals("{}",transport.getBounded(path,2000,2048))
            assertFailsWith<IllegalArgumentException>{transport.get(path)}
            assertFailsWith<IllegalArgumentException>{transport.getBounded(path,2001,2048)}
            assertFailsWith<IllegalArgumentException>{transport.getBounded(path,2000,2049)}
            assertFailsWith<IllegalArgumentException>{transport.getBounded(path+"#foreign",2000,2048)}
        }
    }
    @Test fun originProofResponseSizeLimitIsAppliedDuringRealTransportRead() = withServer { server, endpoint ->
        val path="/v1/sonar/origin-clock-proof?nonce=oversize"
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type","application/json")
            exchange.sendResponseHeaders(200,2049);exchange.responseBody.use{it.write(ByteArray(2049){32})}
        }
        UrlConnectionPiTransport(endpoint,quickPolicy).use { transport ->
            assertFailsWith<IOException>{transport.getBounded(path,2000,2048)}
        }
    }
    @Test fun originProofRealTransportHonorsRemainingMonotonicRequestBudget() = withServer { server, endpoint ->
        val path="/v1/sonar/origin-clock-proof?nonce=late"
        server.createContext(path) { exchange ->
            Thread.sleep(300);exchange.responseHeaders.add("Content-Type","application/json")
            exchange.sendResponseHeaders(200,2);exchange.responseBody.use{it.write("{}".toByteArray())}
        }
        UrlConnectionPiTransport(endpoint,quickPolicy).use { transport ->
            val began=System.nanoTime()
            assertFailsWith<SocketTimeoutException>{transport.getBounded(path,50,2048)}
            assertTrue((System.nanoTime()-began)/1_000_000<250)
        }
    }

}
