package com.pctoolkit.mobile

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.view.Display

data class Zona(val tipo: String, val c: Double)

class Amostra(
    val t: Long,
    val contador: Long?,
    val cpu: Map<String, LongArray>,
    val freqs: List<Int>,
    val zonas: List<Zona>,
    val gpu: Int?,
    val pids: String,
    val foco: String
)

class Delta(val fps: Double?, val usoCpu: Int?, val nucleoMax: Int?)

data class Bateria(val tempC: Double, val nivel: Int, val carregando: Boolean, val saude: Int)

/** Leitura de sensores: 1 chamada root por amostra (rapido o bastante para o monitor ao vivo). */
object Sensores {
    private val rxCpu = Regex("cpu|tsens|soc|cluster|big|little|mtktscpu|apc|kryo|core|silver|gold|prime|cpuss", RegexOption.IGNORE_CASE)
    private val rxNaoCpu = Regex("batt|usb|charger|pa_|modem|wifi|xo|bms|skin|camera|flash|lcd|disp", RegexOption.IGNORE_CASE)

    private fun script(pkg: String?): String {
        val sb = StringBuilder()
        sb.append("echo @FPS; service call SurfaceFlinger 1013 2>/dev/null; ")
        sb.append("echo @STAT; grep '^cpu' /proc/stat; ")
        sb.append("echo @FREQ; cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq 2>/dev/null; ")
        sb.append("echo @TEMP; for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type 2>/dev/null)=\$(cat \$z/temp 2>/dev/null)\"; done; ")
        sb.append("echo @GPU; cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null || cat /sys/kernel/gpu/gpu_busy 2>/dev/null; ")
        if (pkg != null) {
            sb.append("echo @PID; pidof ${Sh.q(pkg)}; ")
            sb.append("echo @FOCO; dumpsys window 2>/dev/null | grep -m1 mCurrentFocus; ")
        }
        sb.append("echo @FIM")
        return sb.toString()
    }

    fun ler(pkg: String? = null): Amostra {
        val r = Sh.root(script(pkg), 20)
        var sec = ""
        val stat = HashMap<String, LongArray>()
        val freqs = ArrayList<Int>()
        val zonas = ArrayList<Zona>()
        var cont: Long? = null
        var gpu: Int? = null
        val pid = StringBuilder()
        var foco = ""
        for (l0 in r.out.lines()) {
            val l = l0.trim()
            if (l.startsWith("@")) { sec = l; continue }
            if (l.isEmpty()) continue
            when (sec) {
                "@FPS" -> {
                    val m = Regex("Parcel\\(\\s*([0-9a-fA-F]{8})").find(l)
                    if (m != null && cont == null) cont = m.groupValues[1].toLong(16)
                }
                "@STAT" -> {
                    val p = l.split(Regex("\\s+"))
                    if (p.size > 4) stat[p[0]] = p.drop(1).mapNotNull { it.toLongOrNull() }.toLongArray()
                }
                "@FREQ" -> { val v = l.toIntOrNull(); if (v != null) freqs.add(v) }
                "@TEMP" -> {
                    val i = l.lastIndexOf('=')
                    if (i > 0) {
                        val v = l.substring(i + 1).trim().toDoubleOrNull()
                        if (v != null) {
                            val c = normTemp(v)
                            if (c != null) zonas.add(Zona(l.substring(0, i), c))
                        }
                    }
                }
                "@GPU" -> if (gpu == null) gpu = Regex("(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
                "@PID" -> pid.append(l).append(' ')
                "@FOCO" -> foco = l
            }
        }
        return Amostra(System.nanoTime(), cont, stat, freqs, zonas, gpu, pid.toString().trim(), foco)
    }

    fun normTemp(v: Double): Double? {
        val c = when {
            v > 1000 -> v / 1000.0
            v > 200 -> v / 10.0
            else -> v
        }
        return if (c in 10.0..130.0) c else null
    }

    fun tempCpu(a: Amostra): Double? {
        val z = a.zonas.filter { rxCpu.containsMatchIn(it.tipo) && !rxNaoCpu.containsMatchIn(it.tipo) }
        return z.maxOfOrNull { it.c }
    }

    fun delta(a: Amostra, b: Amostra): Delta {
        val dt = (b.t - a.t) / 1e9
        val ca = a.contador
        val cb = b.contador
        var fps: Double? = null
        if (ca != null && cb != null && dt > 0.2) {
            val d = cb - ca
            if (d >= 0) fps = d / dt
        }
        fun uso(k: String): Int? {
            val x = a.cpu[k] ?: return null
            val y = b.cpu[k] ?: return null
            val n = minOf(x.size, y.size, 8)
            if (n < 4) return null
            var tot = 0L
            for (i in 0 until n) tot += y[i] - x[i]
            var idle = y[3] - x[3]
            if (n > 4) idle += y[4] - x[4]
            return if (tot > 0) (((tot - idle) * 100) / tot).toInt().coerceIn(0, 100) else null
        }
        val nucleos = b.cpu.keys.filter { it != "cpu" }.mapNotNull { uso(it) }
        return Delta(fps, uso("cpu"), nucleos.maxOrNull())
    }

    /** O contador de quadros do SurfaceFlinger funciona neste aparelho? */
    fun contadorFuncionando(): Boolean {
        val a = ler()
        if (a.contador == null) return false
        try { Thread.sleep(600) } catch (e: InterruptedException) { }
        val b = ler()
        val c2 = b.contador
        return c2 != null && c2 >= a.contador
    }

    fun bateria(ctx: Context): Bateria {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0
        val h = i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
        val pct = if (lvl >= 0 && sc > 0) lvl * 100 / sc else -1
        val carregando = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return Bateria(t, pct, carregando, h)
    }

    fun statusTermico(ctx: Context): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.currentThermalStatus
    }

    fun nomeStatusTermico(s: Int): String = when (s) {
        0 -> "normal"
        1 -> "leve"
        2 -> "moderado (o sistema ja comeca a limitar)"
        3 -> "SEVERO (limitando desempenho)"
        4 -> "CRITICO"
        5 -> "EMERGENCIA"
        6 -> "DESLIGAMENTO"
        else -> "n/d"
    }

    fun memoria(ctx: Context): ActivityManager.MemoryInfo {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi
    }

    fun armazenamento(): Pair<Long, Long> {
        val s = StatFs(Environment.getDataDirectory().path)
        return Pair(s.availableBytes, s.totalBytes)
    }

    /** (Hz atual, Hz maximo na resolucao atual) */
    fun tela(ctx: Context): Pair<Float, Float> {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d: Display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return Pair(60f, 60f)
        val atual = d.refreshRate
        val modo = d.mode
        var max = atual
        for (m in d.supportedModes) {
            if (m.physicalWidth == modo.physicalWidth && m.physicalHeight == modo.physicalHeight && m.refreshRate > max) max = m.refreshRate
        }
        return Pair(atual, max)
    }

    fun linha(ctx: Context, a: Amostra, d: Delta?): String {
        val p = ArrayList<String>()
        val tc = tempCpu(a)
        p.add(if (tc != null) String.format(java.util.Locale.US, "CPU %.0f C", tc) else "CPU temp n/d")
        val b = bateria(ctx)
        p.add(String.format(java.util.Locale.US, "bateria %.1f C", b.tempC))
        d?.usoCpu?.let { p.add("uso $it%") }
        d?.nucleoMax?.let { p.add("nucleo max $it%") }
        if (a.freqs.isNotEmpty()) p.add("${a.freqs.max() / 1000} MHz")
        if (a.gpu != null) p.add("GPU ${a.gpu}%")
        val f = d?.fps
        if (f != null) p.add(String.format(java.util.Locale.US, "%.0f FPS", f))
        val st = statusTermico(ctx)
        if (st >= 2) p.add("TERMICO: " + nomeStatusTermico(st))
        return p.joinToString("  |  ")
    }

    fun corTemp(c: Double?): Int = when {
        c == null -> C.cinza
        c >= 90 -> C.vermelho
        c >= 80 -> C.amarelo
        else -> C.verde
    }

    fun corBateria(c: Double): Int = when {
        c >= 45 -> C.vermelho
        c >= 40 -> C.amarelo
        else -> C.verde
    }
}
