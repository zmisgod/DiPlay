package com.shilapi.xcertplay.ultra

import android.content.Context
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The verified FX11 demo ECARX route. No VHAL setters and no vendor implementation bundled. */
internal class Fx11HvacPowerBackend(context: Context, override val climateEnabled: Boolean = true, private val diagnostic: (String) -> Unit = {}) : HvacPowerBackend {
    private val context = context.applicationContext
    override val settingsLabels = Fx11VehicleSettingsLabels.load(context)
    private val vehicleSettings = Fx11VehicleSettingsSource(this.context)
    private var latestClimate: Map<Long, Any?> = emptyMap()
    private var latestPower: Boolean? = null
    private val version = runCatching { this.context.packageManager.getPackageInfo(this.context.packageName, 0).versionName }.getOrNull() ?: "DiPlay FX11"
    override fun readVehicleSettings(): Map<Long, Any?> {
        val data = vehicleSettings.values().mapValues { (_, value) -> settingsLabels[value] ?: value }.toMutableMap()
        data["version"] = version; data["model"] = "Geely FX11"
        data["operations"] = settingsLabels.getValue("operations_value")
        fun state(key: String, value: Any?) { if (value is Boolean) data[key] = settingsLabels.getValue(if (value) "on" else "off") }
        state("power", latestPower); state("ac", latestClimate[515]); state("auto", latestClimate[530]); state("recirculation", latestClimate[528])
        for ((key, id) in mapOf("driver_temp" to 520L, "passenger_temp" to 534L))
            (latestClimate[id] as? Number)?.let { data[key] = "${it.toDouble()} °C" }
        (latestClimate[525] as? Number)?.let { data["fan"] = it.toInt().toString() }
        if (latestClimate[526] == true) data["fan"] = "AUTO"
        return LiviVehicleSettings.values(data, settingsLabels.getValue("unknown"))
    }
    private var car: Any? = null
    private var watcher: Any? = null
    private var connectType: Class<*>? = null
    private lateinit var functionType: Class<*>
    @Volatile private var function: Any? = null
    @Volatile private var closed = false

    override fun connect() {
        if (function != null && !closed) return
        disconnectCar()
        closed = false
        try {
            val factory = Class.forName("com.ecarx.xui.adaptapi.car.Car")
            val carType = Class.forName("com.ecarx.xui.adaptapi.car.ICar")
            functionType = Class.forName("com.ecarx.xui.adaptapi.car.base.ICarFunction")
            val created = call(factory.getMethod("create", Context::class.java), null, context)
                ?: error("ECARX Car.create returned null")
            car = created
            val connectable = runCatching { Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable") }.getOrNull()
            connectType = connectable
            if (connectable != null && connectable.isInstance(created)) {
                val latch = CountDownLatch(1)
                val watcherType = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher")
                watcher = Proxy.newProxyInstance(watcherType.classLoader, arrayOf(watcherType)) { proxy, method, args ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "DiPlay FX11 climate watcher"
                        "onConnected" -> {
                            if (!closed && car === created) {
                                function = runCatching { call(carType.getMethod("getICarFunction"), created) }.getOrNull()
                            }
                            latch.countDown(); null
                        }
                        "onDisConnected" -> { if (car === created) function = null; latch.countDown(); null }
                        else -> null
                    }
                }
                call(connectable.getMethod("registerConnectWatcher", watcherType), created, watcher)
                check(latch.await(8, TimeUnit.SECONDS)) { "ECARX connection timeout" }
            } else function = call(carType.getMethod("getICarFunction"), created)
            check(function != null) { "ECARX unavailable" }
        } catch (e: Exception) { disconnectCar(); throw e }
    }

    override val extendedClimate = true
    private data class Target(val function: Int, val zone: Int?)
    private fun target(iid: Long): Target? = when (iid) {
        515L -> Target(0x10010300, ZONE)
        520L -> Target(0x10060100, 1)
        534L -> Target(0x10060100, 4)
        524L, 525L, 526L -> Target(0x10020100, 8)
        528L -> Target(0x10030100, null)
        530L -> Target(0x10010200, 1)
        else -> null
    }
    private fun access(receiver: Any, name: String, target: Target, value: Any? = null): Any? {
        val types = mutableListOf<Class<*>>(Int::class.javaPrimitiveType!!)
        val args = mutableListOf<Any>(target.function)
        target.zone?.let { types += Int::class.javaPrimitiveType!!; args += it }
        if (value != null) { types += if (value is Float) Float::class.javaPrimitiveType!! else Int::class.javaPrimitiveType!!; args += value }
        return call(functionType.getMethod(name, *types.toTypedArray()), receiver, *args.toTypedArray())
    }
    private fun available(receiver: Any, target: Target) =
        access(receiver, "isFunctionSupported", target)?.toString()?.equals("active", true) == true
    private fun raw(receiver: Any, target: Target): Int? = (access(receiver, "getFunctionValue", target) as? Number)?.toInt()
    private fun temperatureRange(receiver: Any): Triple<Double, Double, Double>? {
        fun value(id: Int) = (access(receiver, "getCustomizeFunctionValue", Target(id, ZONE)) as? Number)?.toDouble()
        // The declared CAF temperatures use Celsius; never reinterpret a Fahrenheit value.
        if (raw(receiver, Target(268830208, ZONE)) != 268830209) return null
        val min = value(268829440) ?: return null; val max = value(268829184) ?: return null; val step = value(268829696) ?: return null
        return if (min.isFinite() && max.isFinite() && step.isFinite() && min < max && step > 0 && step <= max-min)
            Triple(min,max,step) else null
    }
    override fun readAdditional(): Map<Long, Any?> {
        latestClimate = emptyMap()
        val receiver = function ?: return emptyMap()
        if (closed) return emptyMap()
        val result = listOf(515L, 520L, 534L, 524L, 525L, 526L, 528L, 530L).associateWith { iid ->
            runCatching {
                val t = target(iid)!!
                if (!available(receiver, t)) null
                else if (iid == 520L || iid == 534L) {
                    val range = temperatureRange(receiver)
                    val v = (access(receiver,"getCustomizeFunctionValue",t) as? Number)?.toDouble()
                    v?.takeIf { range != null && it.isFinite() && it in range.first..range.second }
                } else decode(iid, raw(receiver,t))
            }.getOrNull()
        }
        return (if (closed || function !== receiver) emptyMap() else result).also { latestClimate = it }
    }
    override fun setAdditional(iid: Long, value: Any, active: () -> Boolean): Boolean {
        val receiver = function ?: return false
        val t = target(iid) ?: return false
        if (closed || !active() || !available(receiver,t)) return false
        if (t.zone != null) {
            val zones = call(functionType.getMethod("getSupportedFunctionZones", Int::class.javaPrimitiveType),receiver,t.function) as? IntArray
            if (zones == null || t.zone !in zones) {
                diagnostic("ECARX write iid=$iid rejected=unsupported-zone function=${t.function} zone=${t.zone} supportedZones=${zones?.joinToString()}")
                return false
            }
        }
        val encoded: Any
        val setter: String
        if (iid == 520L || iid == 534L) {
            val range = temperatureRange(receiver) ?: return false
            val v = (value as? Number)?.toDouble() ?: return false
            val n = (v-range.first)/range.third
            if (!v.isFinite() || v !in range.first..range.second || kotlin.math.abs(n-kotlin.math.round(n)) > .001) return false
            encoded = v.toFloat(); setter = "setCustomizeFunctionValue"
        } else {
            encoded = encode(iid,value) ?: return false
            val supported = access(receiver,"getSupportedFunctionValue",t) as? IntArray ?: return false
            if (encoded !in supported) {
                diagnostic("ECARX write iid=$iid rejected=unsupported-value function=${t.function} zone=${t.zone} value=$encoded allowed=${supported.joinToString()}")
                return false
            }
            setter = "setFunctionValue"
        }
        if (closed || function !== receiver || !active()) return false
        val accepted = access(receiver,setter,t,encoded) == true
        diagnostic("ECARX write iid=$iid function=${t.function} zone=${t.zone} accepted=$accepted")
        return accepted
    }

    override fun readPower(): Boolean? {
        latestPower = null
        val target = function ?: return null
        if (closed || !supported(target)) return null
        val raw = (invoke(target, "getFunctionValue") as? Number)?.toInt()
        if (closed || function !== target) return null
        return when (raw) {
            0 -> false
            1 -> true
            else -> null
        }.also { latestPower = it }
    }
    override fun setPower(on: Boolean, active: () -> Boolean): Boolean {
        val target = function ?: return false
        if (closed || !supported(target) || !active()) return false
        // Recheck cancellation immediately before the single vendor write. Never resend on failure.
        if (closed || function !== target || !active()) return false
        return call(functionType.getMethod("setFunctionValue", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), target, POWER, ZONE, if (on) 1 else 0) == true
    }
    private fun supported(target: Any) = invoke(target, "isFunctionSupported")?.toString()?.equals("active", true) == true
    private fun invoke(target: Any, name: String) = call(functionType.getMethod(name,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), target, POWER, ZONE)
    override fun close() { vehicleSettings.close(); disconnectCar() }
    private fun disconnectCar() {
        latestClimate = emptyMap(); latestPower = null
        closed = true; function = null
        val old = car; car = null
        val type = connectType
        if (old != null && type != null && type.isInstance(old)) runCatching { call(type.getMethod("disconnect"), old) }
        watcher = null
    }
    private fun call(method: Method, receiver: Any?, vararg args: Any?): Any? = try {
        method.invoke(receiver, *args)
    } catch (e: InvocationTargetException) { throw e.targetException }

    companion object {
        internal fun decode(iid: Long, raw: Int?): Any? = when (iid) {
            515L, 530L -> when(raw) { 0 -> false; 1 -> true; else -> null }
            524L -> when(raw) { 0 -> false; in 0x10020101..0x1002010a -> true; else -> null }
            525L -> when(raw) { 0 -> 0L; in 0x10020101..0x10020109 -> (raw!!-0x10020100).toLong(); else -> null }
            526L -> when(raw) { 0x1002010a -> true; 0, in 0x10020101..0x10020109 -> false; else -> null }
            528L -> when(raw) { 0x10030101 -> true; 0x10030102 -> false; else -> null }
            else -> null
        }
        internal fun encode(iid: Long, value: Any): Int? = when(iid) {
            515L, 530L -> (value as? Boolean)?.let { if(it) 1 else 0 }
            525L -> (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in 0.0..9.0 }?.toInt()?.let { if(it == 0) 0 else 0x10020100 or it }
            528L -> (value as? Boolean)?.let { if(it) 0x10030101 else 0x10030102 }
            else -> null
        }
        const val POWER = 0x10010100
        const val ZONE = Int.MIN_VALUE
    }
}
