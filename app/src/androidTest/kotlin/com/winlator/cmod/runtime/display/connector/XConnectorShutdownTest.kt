package com.winlator.cmod.runtime.display.connector

import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class XConnectorShutdownTest {
    private class Fixture(multithreaded: Boolean = true, private val hold: CountDownLatch? = null) {
        val connected = CountDownLatch(1)
        val received = CountDownLatch(1)
        val closed = AtomicInteger()
        val client = AtomicReference<Client>()
        val path = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "xc-${UUID.randomUUID()}")
        val connector = XConnectorEpoll(
            UnixSocketConfig.createSocket(path.parent, path.name),
            object : ConnectionHandler {
                override fun handleNewConnection(value: Client) {
                    value.createIOStreams()
                    client.set(value)
                    connected.countDown()
                }
                override fun handleConnectionShutdown(value: Client) { closed.incrementAndGet() }
            },
            RequestHandler { value ->
                val input = value.inputStream
                if (input.available() == 0) false else {
                    input.readByte()
                    received.countDown()
                    hold?.await(5, TimeUnit.SECONDS)
                    true
                }
            },
        ).apply {
            setMultithreadedClients(multithreaded)
            setCanReceiveAncillaryMessages(true)
            start()
        }
        val peer = LocalSocket().apply { connect(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM)) }
        fun send() { peer.outputStream.write(42); peer.outputStream.flush(); assertTrue(received.await(2, TimeUnit.SECONDS)) }
        fun stop() {
            val done = CountDownLatch(1)
            thread(isDaemon = true) { try { connector.stop() } finally { done.countDown() } }
            assertTrue("Connector failed to stop", done.await(3, TimeUnit.SECONDS))
            assertEquals(1, closed.get())
            peer.close()
        }
    }

    @Test fun stopsIdleConnectedClient() {
        val fixture = Fixture()
        assertTrue(fixture.connected.await(2, TimeUnit.SECONDS))
        fixture.stop()
        fixture.connector.stop()
        assertEquals(1, fixture.closed.get())
    }

    @Test fun stopsAfterOneRequestWithoutWaitingForAnotherPacket() {
        val fixture = Fixture()
        fixture.send()
        fixture.stop()
    }

    @Test fun closesOnlyAfterTheRequestHandlerFinishes() {
        val release = CountDownLatch(1)
        val fixture = Fixture(hold = release)
        fixture.send()
        val stopped = CountDownLatch(1)
        thread(isDaemon = true) { fixture.connector.stop(); stopped.countDown() }
        try {
            assertFalse(stopped.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, fixture.closed.get())
            assertNotNull(fixture.client.get().inputStream)
        } finally { release.countDown() }
        assertTrue(stopped.await(3, TimeUnit.SECONDS))
        assertEquals(1, fixture.closed.get())
        fixture.peer.close()
    }

    @Test fun concurrentPeerCloseAndStopReleaseOnce() {
        repeat(12) {
            val fixture = Fixture()
            assertTrue(fixture.connected.await(2, TimeUnit.SECONDS))
            val closer = thread { fixture.peer.close() }
            fixture.stop()
            closer.join(2000)
            assertFalse(closer.isAlive)
            assertEquals(1, fixture.closed.get())
        }
    }

    @Test fun independentConnectorsDispatchAndStopWithoutSharedEvents() {
        val fixtures = List(4) { Fixture(multithreaded = it % 2 == 0) }
        fixtures.forEach { it.send() }
        fixtures.forEach { it.stop() }
    }
}
