package com.shilapi.xcertplay

import android.app.*
import android.content.*
import android.graphics.*
import android.os.*
import com.shilapi.xcertplay.host.R
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

internal data class Esp32Command(val id: Long,val page: Int)

internal object Esp32Settings {
    private fun p(c: Context) = c.getSharedPreferences("esp32_display", 0)
    fun enabled(c: Context) = p(c).getBoolean("enabled", false)
    fun transport(c: Context)=p(c).getInt("transport",0).coerceIn(0,1)
    fun setTransport(c: Context,value: Int){require(value in 0..1);p(c).edit().putInt("transport",value).apply()}
    fun navigationTransform(c: Context) = p(c).getInt("navigation_transform",0).coerceIn(0,3)
    fun setNavigationTransform(c: Context,value: Int) { require(value in 0..3);p(c).edit().putInt("navigation_transform",value).apply() }
    fun host(c: Context) = p(c).getString("host", "192.168.77.1").orEmpty()
    fun token(c: Context) = p(c).getString("token", "").orEmpty()
    fun validHost(s: String) = s.length in 1..253 && Regex("[A-Za-z0-9.-]+").matches(s) && !s.startsWith('.')
    fun configure(c: Context, key: String, value: String) { p(c).edit().putString(key,value.trim()).apply() }
    fun enable(c: Context, on: Boolean) { p(c).edit().putBoolean("enabled", on).apply(); Esp32DisplayService.refresh(c) }
    @Synchronized fun command(c: Context, page: Int) {
        require(page in -1..2)
        val queued=pending(c)
        if(queued.size>=32) return // Bound an offline key burst without dropping its accepted gestures.
        val id=commandId(c)+1
        p(c).edit().putInt("command",page).putLong("commandId",id)
            .putString("pending",encode(queued+Esp32Command(id,page))).apply()
    }
    @Synchronized fun pending(c: Context): List<Esp32Command> {
        val raw=p(c).getString("pending",null) ?: return emptyList()
        return runCatching {
            val array=org.json.JSONArray(raw)
            (0 until array.length()).map { array.getJSONObject(it).let { j -> Esp32Command(j.getLong("id"),j.getInt("page")) } }
                .filter { it.id>ack(c) && it.page in -1..2 }.take(32)
        }.getOrDefault(emptyList())
    }
    private fun encode(commands: List<Esp32Command>) = org.json.JSONArray().apply {
        commands.forEach { put(JSONObject().put("id",it.id).put("page",it.page)) }
    }.toString()
    fun client(c: Context): String = p(c).getString("client",null) ?: java.util.UUID.randomUUID().toString().also { configure(c,"client",it) }
    fun commandId(c: Context) = p(c).getLong("commandId",0)
    fun command(c: Context) = p(c).getInt("command",-1)
    fun ack(c: Context) = p(c).getLong("ack",0)
    @Synchronized fun acknowledge(c: Context, id: Long) {
        p(c).edit().putLong("ack",maxOf(ack(c),id))
            .putString("pending",encode(pending(c).filter { it.id>id })).apply()
    }
}

/** One request at a time; JPEG compression and networking never run on the audio/UI thread. */
class Esp32DisplayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var remove: (() -> Unit)? = null
    private var latest = DashboardSnapshot()
    @Volatile private var alive = false
    private val client = Esp32HttpClient()
    private lateinit var usb: Esp32UsbClient
    private var usbAttachment: Esp32UsbAttachmentMonitor?=null
    private val tick = object : Runnable {
        override fun run() {
            if (!alive) return
            if (!Esp32Settings.enabled(this@Esp32DisplayService)) { stopSelf(); return }
            val host = Esp32Settings.host(this@Esp32DisplayService)
            val key = Esp32Settings.token(this@Esp32DisplayService)
            val transport=Esp32Settings.transport(this@Esp32DisplayService)
            val transform=Esp32Settings.navigationTransform(this@Esp32DisplayService)
            val commands = Esp32Settings.pending(this@Esp32DisplayService).take(8)
            // The shared artwork cache owns its bitmap; this worker gets a separate copy.
            val cover = latest.music.artwork?.takeUnless { it.isRecycled }?.let {
                Bitmap.createScaledBitmap(it,192,192,true).let { scaled ->
                    if (scaled === it) scaled.copy(Bitmap.Config.ARGB_8888,false) else scaled
                }
            }
            val snapshot = latest.copy(music = latest.music.copy(artwork = cover))
            worker.execute {
                fun request(path: String,body: ByteArray?) = if(transport==1) usb.request(key,path,body) else client.request(host,key,path,body)
                var delay = 1000L
                val started=SystemClock.elapsedRealtime()
                try {
                    require((transport==1 || Esp32Settings.validHost(host)) && key.matches(Regex("[A-Za-z0-9]{8,64}")))
                    var remote = JSONObject(request("/state",null).toString(Charsets.UTF_8))
                    if(remote.getInt("protocol") != 2) throw Esp32FirmwareMismatch()
                    for(command in commands) {
                        remote = JSONObject(request("/page",JSONObject().put("page",command.page).put("id",command.id).put("client",Esp32Settings.client(this@Esp32DisplayService)).toString().toByteArray()).toString(Charsets.UTF_8))
                        Esp32Settings.acknowledge(this@Esp32DisplayService,command.id)
                    }
                    val page = remote.getInt("page"); require(page in 0..2)
                    val carPlay=if(page==2) Esp32CarPlayFrames.capture() else null
                    val bitmap = try { Esp32FrameRenderer.render(this@Esp32DisplayService,snapshot,page,transform,carPlay) }
                        finally { carPlay?.recycle() }
                    val bytes = try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG,80,it) }.toByteArray() } finally { bitmap.recycle() }
                    require(bytes.size <= 128*1024)
                    request("/frame?revision=${remote.getLong("revision")}",bytes)
                    delay=Esp32FramePacing.delay(transport,page,SystemClock.elapsedRealtime()-started)
                    status = getString(R.string.esp32_connected) + " · " + getString(when(page) { 0 -> R.string.esp32_navigation; 1 -> R.string.esp32_music; else -> R.string.esp32_carplay })
                } catch (e: Exception) {
                    status = getString(when(e) { is Esp32FirmwareMismatch -> R.string.esp32_upgrade; is Esp32UsbUnavailable -> R.string.esp32_usb_missing; else -> R.string.esp32_disconnected }); delay = 3000L
                } finally {
                    cover?.recycle()
                    if (alive) main.postDelayed(this,delay)
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate();alive=true;usb=Esp32UsbClient(this)
        val nm=getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(NotificationChannel("esp32",getString(R.string.esp32_title),NotificationManager.IMPORTANCE_LOW))
        val builder=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,"esp32") else Notification.Builder(this)
        val open=PendingIntent.getActivity(this,61,Intent(this,DiPlayActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(1961,builder.setSmallIcon(R.drawable.ic_dp_dashboard).setContentTitle(getString(R.string.esp32_title)).setContentIntent(open).setOngoing(true).build())
        usbAttachment=Esp32UsbAttachmentMonitor(this).also { it.start() }
        remove=NativeDashboardRuntime.observe(this) { latest=it }
        GeelyWheelKeys.startEsp32(this)
        main.post(tick)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDestroy() { alive=false;usbAttachment?.close();usbAttachment=null;main.removeCallbacksAndMessages(null);client.close();usb.close();worker.shutdownNow();remove?.invoke();GeelyWheelKeys.stopEsp32();status="";super.onDestroy() }
    override fun onBind(intent: Intent?) = null
    companion object {
        @Volatile var status=""
        fun refresh(c: Context) {
            val i=Intent(c,Esp32DisplayService::class.java)
            if(Esp32Settings.enabled(c)) { if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i) else c.startService(i) } else c.stopService(i)
        }
    }
}

/** One in-flight frame; fast USB maps target 4 fps, slow devices simply run at their own pace. */
internal object Esp32FramePacing {
    fun delay(transport:Int,page:Int,elapsedMillis:Long):Long =
        if(transport==1 && page==2) maxOf(40L,250L-elapsedMillis.coerceAtLeast(0L)) else 1000L
}

private class Esp32FirmwareMismatch : Exception()

internal class Esp32HttpClient(private val port: Int = 8080) {
    @Volatile private var closed = false
    @Volatile private var connection: HttpURLConnection? = null
    fun close() { closed=true;connection?.disconnect() }
    fun request(host: String, key: String, path: String, body: ByteArray?): ByteArray {
        check(!closed)
        val c = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        connection=c
        try {
            c.connectTimeout=2000;c.readTimeout=2500;c.instanceFollowRedirects=false
            c.setRequestProperty("X-DiPlay-Key",key)
            if(body!=null) { c.requestMethod="POST";c.doOutput=true;c.setFixedLengthStreamingMode(body.size)
                c.setRequestProperty("Content-Type",if(path.startsWith("/frame")) "image/jpeg" else "application/json")
                c.outputStream.use { it.write(body) }
            }
            check(c.responseCode==200)
            return c.inputStream.use { val b=ByteArrayOutputStream().also { out -> val buf=ByteArray(1024); var n=it.read(buf); while(n>=0) { check(out.size()+n<=4096);out.write(buf,0,n);n=it.read(buf) } }.toByteArray();check(b.size<=4096);b }
        } finally { c.disconnect();connection=null }
    }
}

