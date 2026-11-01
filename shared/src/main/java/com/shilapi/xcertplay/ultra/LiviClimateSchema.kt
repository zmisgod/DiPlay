package com.shilapi.xcertplay.ultra

/** LIVI e7b20b8 climate/resources IID and type vocabulary; values always come from FX11. */
object LiviClimateSchema {
    data class Field(val iid: Long, val type: Long, val format: Long, val writable: Boolean = true,
        val minimum: Double? = null, val maximum: Double? = null, val step: Double? = null,
        val units: Long? = null, val fixed: Any? = null) {
        fun accepts(value: Any?): Boolean = when (format) {
            0L -> value is Boolean
            1L -> value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0 && value.toDouble() in (minimum ?: 0.0)..(maximum ?: 255.0)
            9L -> value is Number && value.toDouble().isFinite() && value.toDouble() in minimum!!..maximum!! &&
                kotlin.math.abs((value.toDouble()-minimum)/step!! - kotlin.math.round((value.toDouble()-minimum)/step)) < .001
            10L -> value is String
            else -> false
        }
        fun describe(value: Any?): Map<String, Any?> = linkedMapOf<String, Any?>(
            "iid" to iid, "type" to type, "format" to format, "mutable" to (fixed == null),
            "writable" to (writable && fixed == null), "oemWritable" to true, "priority" to 1L,
        ).apply {
            if (minimum != null) put("minimumValue", if (format == 9L) minimum else minimum.toLong())
            if (maximum != null) put("maximumValue", if (format == 9L) maximum else maximum.toLong())
            if (step != null) put("stepValue", if (format == 9L) step else step.toLong())
            units?.let { put("units", it) }
            // Match LIVI's string metadata for vehicle layout keys and interior style.
            if (format == 10L) put("maximumLength", 65535L)
            val initial = fixed ?: value
            if (initial != null && accepts(initial)) put("initialValue", initial) else put("supportsInvalid", true)
        }
    }
    private fun bool(iid: Long, type: Long = 0x30000002, writable: Boolean = true) = Field(iid, type, 0, writable)
    private fun seat(iid: Long, key: String) = Field(iid, 0x36000065, 10, false, fixed = key)
    private fun temperature(iid: Long) = Field(iid, 0x31000017, 9, minimum = 16.0, maximum = 32.0, step = .5, units = 1082)
    data class Service(val iid: Long, val type: Long, val fields: List<Field>)
    val services = listOf(
        Service(513, 0x11000001, listOf(bool(514, 0x31000002), bool(515, 0x31000003))),
        Service(518, 0x11000002, listOf(temperature(520), seat(522,"seat_front_left"))),
        Service(532, 0x11000002, listOf(temperature(534), seat(536,"seat_front_right"))),
        Service(523, 0x11000007, listOf(bool(524, writable = false), Field(525, 0x31000012, 1,
            minimum = 0.0, maximum = 9.0, step = 1.0), bool(526,0x3000005f, false), seat(531,"seat_front"))),
        Service(527, 0x11000011, listOf(bool(528))),
        Service(529, 0x11000008, listOf(bool(530))),
    )
    val interior = Field(770, 0x48000004, 10, false, fixed = "5Seater")
    val fields = fields()
    fun fields(labels: Map<String, String> = emptyMap()) =
        (services.flatMap { it.fields } + interior + LiviVehicleSettings.services(labels).flatMap { it.fields }).associateBy { it.iid }
    val fixedValues = fields.values.mapNotNull { f -> f.fixed?.let { f.iid to it } }.toMap()
    fun configuration(values: Map<Long, Any?>, climateEnabled: Boolean = true, labels: Map<String, String> = emptyMap()): Map<String, Any?> = mapOf("accessories" to listOf(
        mapOf("iid" to 512L, "type" to 0x01000001L, "version" to "1.0", "services" to services.map { service ->
            mapOf("iid" to service.iid,"type" to service.type,"characteristics" to service.fields.map { it.describe(values[it.iid]) })
        }),
        mapOf("iid" to 768L, "type" to 0x01200002L, "version" to "1.0", "services" to listOf(
            mapOf("iid" to 769L, "type" to 0x20000001L, "characteristics" to listOf(interior.describe("5Seater")))))
        , mapOf("iid" to 1280L, "type" to 0x05000001L, "version" to "1.0", "services" to LiviVehicleSettings.services(labels).map { service ->
            mapOf("iid" to service.iid, "type" to service.type, "characteristics" to service.fields.map {
                it.describe(values[it.iid] ?: if (it.format == 10L && it.fixed == null) labels["unknown"] ?: "Unavailable" else null)
            })
        })
    ).filter { climateEnabled || it["iid"] == 768L })
}
