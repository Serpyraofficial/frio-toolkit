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
import java.io.File

data class Bateria(val tempC: Double, val nivel: Int, val carregando: Boolean, val saude: Int, val correnteMa: Int?, val ciclos: Int?, val tensaoMv: Int)

/** Sensores acessiveis a qualquer app (sem root) + extras opcionais com root. */
object Sensores {
    fun bateria(ctx: Context): Bateria {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0
        val h = i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
        val mv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val ciclos = if (Build.VERSION.SDK_INT >= 34) (i?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1).takeIf { it >= 0 } else null
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val ma = if (ua == Int.MIN_VALUE || ua == 0) null else (if (Math.abs(ua) > 20000) ua / 1000 else ua)
        val pct = if (lvl >= 0 && sc > 0) lvl * 100 / sc else -1
        val carregando = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return Bateria(t, pct, carregando, h, ma, ciclos, mv)
    }

    fun nomeSaude(h: Int): String = when (h) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "boa"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "SUPERAQUECIDA"
        BatteryManager.BATTERY_HEALTH_DEAD -> "gasta"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "tensão alta"
        BatteryManager.BATTERY_HEALTH_COLD -> "fria"
        else -> "não informada"
    }

    fun statusTermico(ctx: Context): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        return (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
    }

    /** Folga termica (0 = frio, 1 = vai comecar a limitar). Android 11+. */
    fun folgaTermica(ctx: Context): Float? {
        if (Build.VERSION.SDK_INT < 30) return null
        val v = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).getThermalHeadroom(10)
        return if (v.isNaN() || v < 0) null else v
    }

    fun nomeStatusTermico(s: Int): String = when (s) {
        0 -> "normal"
        1 -> "leve"
        2 -> "moderado (já começa a limitar)"
        3 -> "SEVERO (limitando o desempenho)"
        4 -> "CRÍTICO"
        5 -> "EMERGÊNCIA"
        6 -> "DESLIGANDO"
        else -> "não informado"
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
        for (m in d.supportedModes) if (m.physicalWidth == modo.physicalWidth && m.physicalHeight == modo.physicalHeight && m.refreshRate > max) max = m.refreshRate
        return Pair(atual, max)
    }

    fun economiaEnergia(ctx: Context): Boolean = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode

    /** Clock atual de cada nucleo (muitos celulares deixam ler sem root). */
    fun clocks(): List<Int> {
        val r = ArrayList<Int>()
        for (i in 0 until 16) {
            val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            if (!f.exists()) continue
            try { f.readText().trim().toIntOrNull()?.let { r.add(it / 1000) } } catch (e: Exception) { }
        }
        return r
    }

    fun clocksMax(): List<Int> {
        val r = ArrayList<Int>()
        for (i in 0 until 16) {
            val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (!f.exists()) continue
            try { f.readText().trim().toIntOrNull()?.let { r.add(it / 1000) } } catch (e: Exception) { }
        }
        return r
    }

    fun soc(): String = if (Build.VERSION.SDK_INT >= 31) (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).trim() else Build.HARDWARE

    fun corBateria(c: Double): Int = when { c >= 44 -> T.vermelho; c >= 39 -> T.amarelo; else -> T.verde }

    /** Linha de resumo para monitor/notificacao. */
    fun resumo(ctx: Context): String {
        val b = bateria(ctx)
        val m = memoria(ctx)
        val p = ArrayList<String>()
        p.add("Bateria " + f1(b.tempC) + " °C")
        p.add("${b.nivel}%")
        b.correnteMa?.let { if (!b.carregando) p.add("${Math.abs(it)} mA") }
        p.add("RAM livre " + fmtTam(m.availMem))
        val c = clocks()
        if (c.isNotEmpty()) p.add("CPU até ${c.max()} MHz")
        val st = statusTermico(ctx)
        if (st >= 2) p.add("CALOR: " + nomeStatusTermico(st))
        return p.joinToString("  ·  ")
    }

    // ---------------- extras com root: contador de quadros (FPS) ----------------
    fun contadorQuadros(): Long? {
        if (!Sh.privOk()) return null
        val r = Sh.root("service call SurfaceFlinger 1013 2>/dev/null", 5)
        val m = Regex("Parcel\\(\\s*([0-9a-fA-F]{8})").find(r.out) ?: return null
        return m.groupValues[1].toLong(16)
    }

    fun focoAtual(): String = if (Sh.privOk()) Sh.root("dumpsys window 2>/dev/null | grep -m1 mCurrentFocus", 5).out else ""
}
