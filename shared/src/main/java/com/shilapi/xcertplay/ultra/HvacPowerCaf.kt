package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*
import java.io.Closeable

/** Implementations must use real feedback; null means unavailable, never OFF. */
interface HvacPowerBackend : Closeable {
    val extendedClimate: Boolean get() = false
    val climateEnabled: Boolean get() = true
    val settingsLabels: Map<String, String> get() = emptyMap()
    fun readVehicleSettings(): Map<Long, Any?> = emptyMap()
    fun readAdditional(): Map<Long, Any?> = emptyMap()
    fun setAdditional(iid: Long, value: Any, active: () -> Boolean): Boolean = false
    fun connect()
    fun readPower(): Boolean?
    fun setPower(on: Boolean, active: () -> Boolean): Boolean
}

/** Accessed by one session worker. Both CAF lanes share hardware and request deduplication. */
class HvacPowerCaf(private val backend: HvacPowerBackend, private val diagnostic: (String) -> Unit = {},
    private val waitForFeedback: (Long) -> Unit = Thread::sleep) : Closeable {
    private val schema = LiviClimateSchema.fields(backend.settingsLabels)
    private val settingsValueIds = LiviVehicleSettings.rows.map { it.valueIid }.toSet()
    private var connected = false
    private var closed = false
    private var power: Boolean? = null
    private var reported = false
    private var diagnosticRequests = 0
    private var readings = emptyMap<Long, Any?>()
    private data class Follower(val ids: Set<Long>, var last: Map<Long, Any?>)
    private val followers = linkedMapOf<Long, Follower>()
    private data class Write(val bytes: List<Byte>, val response: CafEnvelope)
    private val writes = linkedMapOf<Long, Write>()

    fun refresh() {
        if (closed) return
        if (!backend.climateEnabled) { readings = mapOf(770L to "5Seater"); return }
        val previous = power
        power = try {
            if (!connected) { backend.connect(); connected = true }
            backend.readPower()
        } catch (e: Exception) {
            if (!reported) diagnostic("ECARX power unavailable failureClass=${e.javaClass.simpleName}")
            null
        }
        val extra = (if (backend.extendedClimate && connected) runCatching { backend.readAdditional() }.getOrDefault(emptyMap()) else emptyMap()) + if (backend.extendedClimate) runCatching { backend.readVehicleSettings() }.getOrDefault(emptyMap()) else emptyMap()
        readings = if (backend.extendedClimate) schema.mapValues { (iid, field) ->
            field.fixed ?: (if (iid == POWER_IID) power else extra[iid] ?: if (iid in settingsValueIds) backend.settingsLabels["unknown"] ?: "Unavailable" else null)?.takeIf(field::accepts)
        } else mapOf(POWER_IID to power)
        if (!reported || previous != power) {
            reported = true
            diagnostic("ECARX power feedback=${power?.toString() ?: "unavailable"}")
        }
    }

    fun handle(channel: Long, body: ByteArray, active: () -> Boolean = { true }): ByteArray? {
        if (closed || !active()) return null
        val request = CafRcsCodec.decodeOpack(body)
        val message = request.message
        if (diagnosticRequests++ < 64) diagnostic("CAF request command=${message.command.wireName} plugin=${request.pluginId} transactionPresent=${message.transactionId != null}")
        val tx = message.transactionId ?: return null
        if (request.pluginId != PLUGIN_ID) return null
        val reader = CafMessageReader(request)
        fun response(command: CafCommand, values: Any? = null, errors: Map<Long, Long>? = null) =
            CafEnvelope(PLUGIN_ID, CafMessage(command, tx, values, errors))
        fun readings(command: CafCommand, ids: List<Long>): CafEnvelope {
            val values = linkedMapOf<Long, Any?>(); val errors = linkedMapOf<Long, Long>()
            ids.forEach { id ->
                if (this.readings[id] != null) values[id] = this.readings[id] else errors[id] = NO_VALUE
            }
            if (diagnosticRequests <= 64) diagnostic("CAF readings command=${command.wireName} requested=${ids.take(32)} " +
                "available=${values.keys.take(32)} unavailable=${errors.keys.take(32)} total=${ids.size}")
            // LIVI omits errors entirely when all requested readings are available.
            return response(command, values, errors.takeIf { it.isNotEmpty() })
        }
        fun ids() = if (reader.values() == "*") (if (backend.extendedClimate) readings.keys.toList() else listOf(POWER_IID))
            else reader.valuesList().map { CafNumbers.toLong(it, "HVAC IID") }
        val answer = when (message.command) {
            CafCommand.CONFIG_REQUEST -> {
                refresh()
                diagnostic("CAF config initialPowerAvailable=${power != null} " +
                    "available=${readings.filterValues { it != null }.keys} unavailable=${readings.filterValues { it == null }.keys}")
                response(CafCommand.CONFIG_RESPONSE, if (backend.extendedClimate) LiviClimateSchema.configuration(readings, backend.climateEnabled, backend.settingsLabels) else configuration(power))
            }
            CafCommand.READ_REQUEST -> { refresh(); readings(CafCommand.READ_RESPONSE, ids()) }
            CafCommand.REGISTER_REQUEST -> {
                refresh()
                val ids = ids()
                val followed = ids.filter { it in readings }.toSet()
                if (followed.isNotEmpty()) {
                    val previous = followers[channel]
                    followers[channel] = Follower(previous?.ids.orEmpty() + followed,
                        previous?.last.orEmpty() + readings.filterKeys { it in followed })
                }
                readings(CafCommand.REGISTER_RESPONSE, ids)
            }
            CafCommand.UNREGISTER_REQUEST -> {
                followers[channel]?.let { follower ->
                    val remaining = follower.ids - ids().toSet()
                    if (remaining.isEmpty()) followers.remove(channel)
                    else followers[channel] = Follower(remaining, follower.last.filterKeys { it in remaining })
                }
                response(CafCommand.UNREGISTER_RESPONSE)
            }
            CafCommand.WRITE_REQUEST -> {
                writes[tx]?.let { prior ->
                    // A reused transaction may never turn into a second hardware command.
                    return CafRcsCodec.encodeOpack(if (prior.bytes == body.toList()) prior.response
                        else response(CafCommand.WRITE_RESPONSE, errors = reader.valuesMap().keys.associateWith { NO_VALUE }))
                }
                val errors = reader.valuesMap().mapValues { (iid, target) ->
                    val field = if (backend.extendedClimate) LiviClimateSchema.fields[iid] else null
                    val allowed = if (iid == POWER_IID) target is Boolean else field?.writable == true && field.accepts(target)
                    if (!backend.climateEnabled || !allowed || target == null || !active()) {
                        diagnostic("CAF write iid=$iid rejected=disabled-readonly-invalid-or-cancelled")
                        NO_VALUE
                    }
                    else {
                        refresh()
                        if (readings[iid] == null || !active()) NO_VALUE
                        else if (same(readings[iid], target)) 0L
                        else {
                            val accepted = try { if (iid == POWER_IID) backend.setPower(target as Boolean, active) else backend.setAdditional(iid, target, active) } catch (e: Exception) { diagnostic("CAF write iid=$iid exception=${e.javaClass.simpleName}"); false }
                            // ECARX feedback is asynchronous. Send once, then wait up to 600ms for real
                            // feedback; never publish the requested value optimistically or resend it.
                            refresh()
                            if (accepted) {
                                var remaining = 12
                                while (!same(readings[iid], target) && active() && remaining-- > 0) {
                                    waitForFeedback(50)
                                    if (!active()) break
                                    refresh()
                                }
                            }
                            val confirmed = accepted && active() && same(readings[iid], target)
                            diagnostic("CAF write iid=$iid accepted=$accepted confirmed=$confirmed feedbackAvailable=${readings[iid] != null}")
                            if (confirmed) 0L else NO_VALUE
                        }
                    }
                }
                diagnostic("CAF write completed results=$errors")
                response(CafCommand.WRITE_RESPONSE, errors = errors).also {
                    writes[tx] = Write(body.toList(), it)
                    if (writes.size > 128) writes.remove(writes.keys.first())
                }
            }
            CafCommand.CONTROL_REQUEST -> response(CafCommand.CONTROL_RESPONSE, emptyMap<Long, Any?>(),
                reader.valuesMap().keys.associateWith { NO_VALUE })
            else -> return null
        }
        if (diagnosticRequests <= 64) diagnostic("CAF response command=${answer.message.command.wireName} errorCount=${answer.message.errors?.count { it.value != 0L } ?: 0}")
        return CafRcsCodec.encodeOpack(answer)
    }

    /** Every subscribed lane receives real external changes and loss of availability. */
    private fun same(a: Any?, b: Any?): Boolean = if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
    fun updates(): Map<Long, ByteArray> = followers.mapNotNull { (channel, follower) ->
        val values = readings.filterKeys { it in follower.ids }
        if (values == follower.last) null else {
            val changed = values.filter { (id, value) -> follower.last[id] != value }
            follower.last = values
            channel to CafRcsCodec.encodeOpack(CafEnvelope(PLUGIN_ID,
                CafMessage(CafCommand.UPDATE_NOTIFY, values = changed)))
        }
    }.toMap()

    fun detach(channel: Long) { followers.remove(channel) }
    override fun close() {
        if (closed) return
        closed = true
        followers.clear(); writes.clear(); power = null; connected = false
        backend.close()
    }

    companion object {
        const val PLUGIN_ID = 0L
        const val PLUGIN_NAME = "DiPlay.FX11.Climate"
        const val POWER_IID = 514L
        const val NO_VALUE = -6727L
        // LIVI 3f86ad7 vehicle_state/climate.rs: climate → HVAC → hvacOn.
        // No simulated initialValue; advertise ONLY the implemented boolean power characteristic.
        val accessories: List<Map<String, Any?>> = listOf(mapOf(
            "iid" to 512L, "type" to 0x01000001L, "version" to "1.0",
            "services" to listOf(mapOf("iid" to 513L, "type" to 0x11000001L,
                "characteristics" to listOf(mapOf(
                    "iid" to POWER_IID, "type" to 0x31000002L, "format" to 0L,
                    "mutable" to true, "writable" to true, "oemWritable" to true,
                    "priority" to 1L, "supportsInvalid" to true,
                )))),
        ))
        val config: Map<String, Any?> = mapOf("accessories" to accessories)
        /** Car-owned/shared values have an initialValue in LIVI. Only use fresh real feedback. */
        fun configuration(initialPower: Boolean?): Map<String, Any?> {
            if (initialPower == null) return config
            val characteristic = mapOf<String, Any?>(
                "iid" to POWER_IID, "type" to 0x31000002L, "format" to 0L,
                "mutable" to true, "writable" to true, "oemWritable" to true,
                "priority" to 1L, "initialValue" to initialPower)
            return mapOf("accessories" to listOf(mapOf(
                "iid" to 512L, "type" to 0x01000001L, "version" to "1.0",
                "services" to listOf(mapOf("iid" to 513L, "type" to 0x11000001L,
                    "characteristics" to listOf(characteristic))))))
        }

    }
}
