package com.shilapi.xcertplay.ultra

/** LIVI's automaker settings info-line/section schema. These rows are deliberately read-only.
 * Vehicle operations remain in the typed climate services, not invented settings service types. */
object LiviVehicleSettings {
    data class Row(val iid: Long, val key: String, val label: String, val symbol: String, val section: String) {
        val valueIid get() = iid + 3
    }
    val rows = listOf(
        Row(1281, "version", "DiPlay version", "info.circle", "FX11"),
        Row(1289, "model", "Vehicle", "car", "FX11"),
        Row(1305, "range", "Remaining range", "fuelpump", "FX11"),
        Row(1313, "fuel", "Fuel level", "fuelpump", "FX11"),
        Row(1321, "coolant", "Coolant gauge", "thermometer", "FX11"),
        Row(1329, "ambient", "Outside temperature", "thermometer", "FX11"),
        Row(1337, "gear", "Gear", "gearshape", "FX11"),
        Row(1345, "tires_front", "Front tires · L / R", "car", "FX11"),
        Row(1353, "tires_rear", "Rear tires · L / R", "car", "FX11"),
        Row(1361, "low_beam", "Low beam", "headlight.low.beam", "FX11"),
        Row(1369, "auto_hold", "AutoHold holding", "parkingsign.circle", "FX11"),
        Row(1377, "parking_brake", "Parking brake", "parkingsign.circle", "FX11"),
        Row(1385, "power", "Climate power", "fan", "CLIMATE"),
        Row(1393, "driver_temp", "Driver target temperature", "thermometer", "CLIMATE"),
        Row(1401, "passenger_temp", "Passenger target temperature", "thermometer", "CLIMATE"),
        Row(1409, "fan", "Fan level", "fan", "CLIMATE"),
        Row(1417, "ac", "A/C", "snowflake", "CLIMATE"),
        Row(1425, "auto", "Automatic climate", "fan", "CLIMATE"),
        Row(1433, "recirculation", "Recirculation", "arrow.triangle.2.circlepath", "CLIMATE"),
        Row(1441, "operations", "Controls", "slider.horizontal.3", "CLIMATE"),
    )
    fun services(labels: Map<String, String> = emptyMap()): List<LiviClimateSchema.Service> {
        fun text(id: Long, type: Long, value: String) = LiviClimateSchema.Field(id, type, 10, false, fixed = value)
        fun order(id: Long, type: Long, value: Int, max: Double) =
            LiviClimateSchema.Field(id, type, 1, false, 0.0, max, 1.0, fixed = value.toLong())
        return rows.mapIndexed { index, row -> LiviClimateSchema.Service(row.iid, 0x16000015, listOf(
            text(row.iid+1, 0x30000019, "DiPlay.FX11.${row.key}"),
            text(row.iid+2, 0x30000001, labels[row.key] ?: row.label),
            LiviClimateSchema.Field(row.valueIid, 0x30000017, 10, false),
            order(row.iid+4, 0x36000006, 0, 15.0),
            text(row.iid+5, 0x3000005e, row.symbol),
            order(row.iid+6, 0x30000003, index, 100.0),
            text(row.iid+7, 0x36000028, row.section),
        )) } + listOf("FX11", "CLIMATE").mapIndexed { index, section ->
            val id = 1500L + index * 8
            LiviClimateSchema.Service(id, 0x16000020, listOf(
                text(id+1, 0x30000019, section), order(id+2, 0x30000003, index, 100.0),
                text(id+3, 0x36000004, labels[section] ?: if (section == "FX11") "Geely FX11" else "Climate")))
        }
    }
    fun values(values: Map<String, String>, unknown: String = "Unavailable"): Map<Long, Any?> =
        rows.associate { it.valueIid to (values[it.key] ?: unknown) }
}
