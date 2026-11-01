package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.catalog.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One hardware session shared by both negotiated CAF lanes. Nothing connects before a peer opens. */
object ClimatePowerProvisioning {
    fun create(backend: () -> HvacPowerBackend, diagnostic: (String) -> Unit = {}, theme: UltraThemeProfile? = null, extendedClimate: Boolean = false, climateEnabled: Boolean = true, settingsLabels: Map<String, String> = emptyMap()): AirPlayUltraProvisioning {
        val power = PowerRuntime(backend, diagnostic)
        val types = CafPluginRegistry.DEFAULT_CLIENT_TYPES + if (theme != null) setOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL) else emptySet()
        val factory = object : RcsDataStreamHandlerFactory {
            override fun supports(clientType: RcsClientType) = clientType in types
            override fun create(stream: RcsDataStream): RcsDataStreamHandler? = when (stream.clientType) {
                RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL -> uiSync(diagnostic, theme)
                in CafPluginRegistry.DEFAULT_CLIENT_TYPES -> power.handler()
                else -> null
            }
        }
        return AirPlayUltraProvisioning(
            pluginRegistrations = listOf(CafPluginRegistration(HvacPowerCaf.PLUGIN_ID, "1.0",
                CafPluginRegistry.DEFAULT_CLIENT_TYPES, object : CafPluginHandler {},
                pluginName = HvacPowerCaf.PLUGIN_NAME,
                pluginConfig = mapOf("name" to HvacPowerCaf.PLUGIN_NAME, "accessories" to if (extendedClimate) LiviClimateSchema.configuration(emptyMap(), climateEnabled, settingsLabels)["accessories"] else HvacPowerCaf.accessories))),
            // LIVI enables UI Sync only with theme displays. Climate alone has no OEM theme.
            uiSyncInfo = theme?.let { AirPlayUiSyncInfo.empty() },
            rcsFactories = types.associateWith { factory },
            runtimeFeatures = if (theme != null) setOf(AirPlayFeature.ALT_SCREEN) else emptySet(),
            // Theme selection alone opts into native UI context; climate-only keeps the existing display path.
        )
    }

    internal fun uiSync(diagnostic: (String) -> Unit, theme: UltraThemeProfile? = null) = object : RcsDataStreamHandler {
        private val state = LiviUiSync(theme)
        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            state.receive(message.body).forEach { stream.send(RcsMessage.command(it)) }
            diagnostic("UI Sync ready=${state.ready}; theme=${theme?.assetId ?: "none"} layout=${theme?.layoutId ?: "none"}")
        }
    }

    private class PowerRuntime(val backend: () -> HvacPowerBackend, val diagnostic: (String) -> Unit) {
        private var group: Group? = null
        private val ids = AtomicLong()
        private class Peer(val stream: RcsDataStream, val active: AtomicBoolean) {
            @Volatile var messageType = com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec.MESSAGE_TYPE_COMMAND
            @Volatile var packageKind = com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec.PACKAGE_ASYNC
            fun receive(message: RcsMessage) {
                val codec = com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
                require(message.messageType == codec.MESSAGE_TYPE_COMM || message.messageType == codec.MESSAGE_TYPE_COMMAND)
                // A CAF reply is an asynchronous command even when the phone requests with comm.
                // Preserve kind=0 only for the older raw carrier; match LIVI's duplex output otherwise.
                packageKind = if (message.packageKind == 0) 0 else codec.PACKAGE_ASYNC
                messageType = if (message.packageKind == 0) message.messageType else codec.MESSAGE_TYPE_COMMAND
            }
        }
        private inner class Group {
            val peers = ConcurrentHashMap<Long, Peer>()
            var sentDiagnostics = 0
            val model = HvacPowerCaf(backend(), diagnostic)
            val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fx11-climate-power").apply { isDaemon = true } }
            val poll = worker.scheduleWithFixedDelay({
                if (peers.isNotEmpty()) {
                    model.refresh()
                    notifyChanges()
                }
            }, 0, 500, TimeUnit.MILLISECONDS)
            fun send(id: Long, bytes: ByteArray) {
                val peer = peers[id] ?: return
                if (!peer.active.get()) return
                runCatching {
                    peer.stream.send(RcsMessage(peer.messageType, bytes, peer.packageKind))
                    if (sentDiagnostics++ < 16) diagnostic("CAF sent type=${peer.messageType} kind=${peer.packageKind} bytes=${bytes.size}")
                }.onFailure {
                    diagnostic("CAF send failed: ${it.javaClass.simpleName}")
                    peer.stream.close()
                }
            }
            fun notifyChanges() { model.updates().forEach { (id, body) -> send(id, body) } }
        }
        @Synchronized private fun attach(id: Long, peer: Peer): Group {
            val current = group ?: Group().also { group = it }
            current.peers[id] = peer
            diagnostic("CAF power channel opened; peers=${current.peers.size}")
            return current
        }
        @Synchronized private fun detach(id: Long, current: Group) {
            current.peers.remove(id)
            current.worker.execute { current.model.detach(id) }
            if (current.peers.isEmpty()) {
                if (group === current) group = null
                current.poll.cancel(false)
                current.worker.execute { runCatching { current.model.close() } }
                current.worker.shutdown()
                diagnostic("CAF power disconnected; backend released")
            }
        }
        fun handler() = object : RcsDataStreamHandler {
            private val id = ids.incrementAndGet()
            private val active = AtomicBoolean(false)
            private var current: Group? = null
            private val pending = Semaphore(32)
            override fun onStreamOpened(stream: RcsDataStream) {
                active.set(true)
                current = attach(id, Peer(stream, active))
            }
            override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
                if (!active.get()) return
                require(message.body.size <= 512 * 1024 && pending.tryAcquire()) { "CAF request queue full or payload too large" }
                val group = current ?: run { pending.release(); return }
                try { group.peers[id]?.receive(message) } catch(e: Exception) { pending.release(); throw e }
                group.worker.execute {
                    try {
                        if (active.get()) {
                            group.model.handle(id, message.body, active::get)?.let { group.send(id, it) }
                            group.notifyChanges()
                        }
                    } catch (e: Exception) {
                        diagnostic("CAF power request failed: ${e.javaClass.simpleName}")
                        stream.close()
                    } finally { pending.release() }
                }
            }
            override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
                if (!active.getAndSet(false)) return
                current?.let { detach(id, it) }
                current = null
            }
        }
    }
}
