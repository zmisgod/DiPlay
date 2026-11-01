package com.shilapi.xcertplay.ultra

import android.content.Context
import com.shilapi.xcertplay.host.R

internal object Fx11VehicleSettingsLabels {
    fun load(context: Context): Map<String, String> = mapOf(
        "version" to context.getString(R.string.fx11_caf_version),
        "model" to context.getString(R.string.fx11_caf_model),
        "range" to context.getString(R.string.fx11_caf_range),
        "fuel" to context.getString(R.string.fx11_caf_fuel),
        "coolant" to context.getString(R.string.fx11_caf_coolant),
        "ambient" to context.getString(R.string.fx11_caf_ambient),
        "gear" to context.getString(R.string.fx11_caf_gear),
        "tires_front" to context.getString(R.string.fx11_caf_tires_front),
        "tires_rear" to context.getString(R.string.fx11_caf_tires_rear),
        "low_beam" to context.getString(R.string.fx11_caf_low_beam),
        "auto_hold" to context.getString(R.string.fx11_caf_auto_hold),
        "parking_brake" to context.getString(R.string.fx11_caf_parking_brake),
        "power" to context.getString(R.string.fx11_caf_power),
        "driver_temp" to context.getString(R.string.fx11_caf_driver_temp),
        "passenger_temp" to context.getString(R.string.fx11_caf_passenger_temp),
        "fan" to context.getString(R.string.fx11_caf_fan),
        "ac" to context.getString(R.string.fx11_caf_ac),
        "auto" to context.getString(R.string.fx11_caf_auto),
        "recirculation" to context.getString(R.string.fx11_caf_recirculation),
        "operations" to context.getString(R.string.fx11_caf_operations),
        "FX11" to context.getString(R.string.fx11_caf_fx11),
        "CLIMATE" to context.getString(R.string.fx11_caf_climate),
        "unknown" to context.getString(R.string.fx11_caf_unknown),
        "on" to context.getString(R.string.fx11_caf_on),
        "off" to context.getString(R.string.fx11_caf_off),
        "operations_value" to context.getString(R.string.fx11_caf_operations_value),
    )
}
