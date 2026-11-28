package com.pctoolkit.mobile

import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PcApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
        Relatorio.instalar()
    }

    companion object {
        lateinit var ctx: Context
    }
}

/** Paleta do app (tema escuro profissional). */
object T {
    // visual Y2K: azul-noite, violeta, cromo e verde-limao so nos destaques
    const val bg = 0xFF07081A.toInt()
    const val surface = 0xFF0F1036.toInt()
    const val surface2 = 0xFF12113A.toInt()
    const val outline = 0xFF3A3480.toInt()
    const val barra = 0xFF1C1A52.toInt()        // barra de titulo das "janelas"
    const val grade = 0x248C7BFF               // linhas da grade do fundo
    const val texto = 0xFFE9ECFF.toInt()
    const val texto2 = 0xFFB8BEF0.toInt()
    const val texto3 = 0xFF8B90C2.toInt()
    const val verde = 0xFFC8FF6B.toInt()
    const val azul = 0xFF8C7BFF.toInt()
    const val lilas = 0xFFA99CFF.toInt()
    const val roxo = 0xFFB57BFF.toInt()
    const val laranja = 0xFFFF9F45.toInt()
    const val vermelho = 0xFFFF5A6E.toInt()
    const val amarelo = 0xFFF5C542.toInt()
    const val ciano = 0xFF38D6E8.toInt()
    const val cinza = 0xFF8F9BB3.toInt()
}

/** Comandos de shell. Root e opcional: so usado se o celular tiver e o usuario permitir. */
object Sh {
    data class R(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
    }

    @Volatile private var root: Boolean? = null
    @Volatile var detalheRoot: String = ""
        private set
    private var suPath = "su"

    fun sh(cmd: String, timeoutS: Long = 30): R = exec(arrayOf("sh", "-c", cmd), timeoutS)

    private fun exec(argv: Array<String>, timeoutS: Long): R {
        return try {
            val p = ProcessBuilder(*argv).start()
            p.outputStream.close()
            val o = StringBuilder()
            val e = StringBuilder()
            val to = Thread { try { p.inputStream.bufferedReader().forEachLine { synchronized(o) { o.append(it).append('\n') } } } catch (x: Exception) { } }
            val te = Thread { try { p.errorStream.bufferedReader().forEachLine { synchronized(e) { e.append(it).append('\n') } } } catch (x: Exception) { } }
            to.start(); te.start()
            val fim = p.waitFor(timeoutS, TimeUnit.SECONDS)
            if (!fim) p.destroy()
            to.join(2000); te.join(2000)
            R(if (fim) p.exitValue() else -2, synchronized(o) { o.toString().trim() }, synchronized(e) { e.toString().trim() })
        } catch (x: Exception) {
            R(-1, "", x.message ?: "erro")
        }
    }

    fun getprop(nome: String): String = sh("getprop $nome", 5).out.trim()

    /** Ha algum binario su no aparelho? (nao pede permissao) */
    fun suExiste(): Boolean {
        val caminhos = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/su/bin/su", "/vendor/bin/su")
        if (caminhos.any { File(it).exists() }) return true
        return sh("command -v su", 5).out.isNotBlank()
    }

    /** Pede root ao gerenciador (Magisk/KernelSU/APatch). So chamar quando o usuario quiser. */
    fun verificarRoot(): Boolean {
        val cands = listOf("su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su")
        val det = StringBuilder()
        for (c in cands) {
            val r = exec(arrayOf(c, "-c", "id"), 40)
            if (r.ok && r.out.contains("uid=0")) {
                suPath = c; root = true; detalheRoot = "Root liberado via $c"
                return true
            }
            if (r.code != -1 || r.err.isNotEmpty()) det.append("$c: codigo ${r.code} ${r.err.take(80)}\n")
        }
        root = false
        detalheRoot = if (det.isEmpty()) "Nenhum su encontrado" else det.toString().trim()
        return false
    }

    /** Estado conhecido do root, sem pedir nada. */
    fun rootConhecido(): Boolean = root == true

    fun temRoot(): Boolean = root ?: (if (suExiste()) verificarRoot() else { root = false; detalheRoot = "Este celular nao tem root"; false })

    // ---- shell root persistente (um pedido de permissao so) ----
    private var proc: Process? = null
    private var entrada: java.io.OutputStream? = null
    private var fila = LinkedBlockingQueue<String>()
    private var erros = StringBuffer()

    /** Pode rodar comandos privilegiados (root ou Shizuku)? */
    fun privOk(): Boolean = rootConhecido() || Priv.pronto()

    /** Comando privilegiado: usa root se houver; senao o Shizuku. */
    fun root(cmd: String, timeoutS: Long = 60): R {
        if (!rootConhecido() && Priv.pronto()) return Priv.exec(cmd, timeoutS)
        if (!temRoot()) return R(-1, "", "sem root")
        if (timeoutS > 60) return exec(arrayOf(suPath, "-c", cmd), timeoutS)
        return persistente(cmd, timeoutS)
    }

    private fun abrirShell(): Boolean = try {
        val p = ProcessBuilder(suPath).start()
        val f = LinkedBlockingQueue<String>()
        val er = StringBuffer()
        fila = f; erros = er
        Thread { try { p.inputStream.bufferedReader().forEachLine { f.put(it) } } catch (x: Exception) { }; f.put("\u0000FIM") }.apply { isDaemon = true }.start()
        Thread { try { p.errorStream.bufferedReader().forEachLine { er.append(it).append('\n') } } catch (x: Exception) { } }.apply { isDaemon = true }.start()
        proc = p; entrada = p.outputStream
        true
    } catch (x: Exception) { false }

    private fun fecharShell() {
        try { proc?.destroy() } catch (x: Exception) { }
        proc = null; entrada = null
    }

    @Synchronized
    private fun persistente(cmd: String, timeoutS: Long): R {
        val p0 = proc
        if (p0 == null || !p0.isAlive) { fecharShell(); if (!abrirShell()) return R(-1, "", "su indisponivel") }
        val marca = "__PCTK_" + System.nanoTime() + "__"
        fila.clear(); erros.setLength(0)
        try {
            val e = entrada ?: return R(-1, "", "sem shell")
            e.write(("(" + cmd + "\n)\necho \"" + marca + ":\$?\"\n").toByteArray())
            e.flush()
        } catch (x: Exception) { fecharShell(); return R(-1, "", x.message ?: "erro") }
        val out = StringBuilder()
        val limite = System.currentTimeMillis() + timeoutS * 1000
        while (true) {
            val resta = limite - System.currentTimeMillis()
            if (resta <= 0) { fecharShell(); return R(-2, out.toString().trim(), "tempo esgotado") }
            val l = fila.poll(resta, TimeUnit.MILLISECONDS) ?: continue
            if (l == "\u0000FIM") { fecharShell(); return R(-1, out.toString().trim(), "shell encerrado") }
            if (l.startsWith(marca)) return R(l.substringAfter(':').trim().toIntOrNull() ?: -1, out.toString().trim(), erros.toString().trim())
            out.append(l).append('\n')
        }
    }

    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

/** Configuracoes e listas persistentes. */
object Store {
    private fun f(nome: String) = File(PcApp.ctx.filesDir, nome)
    private val prefs get() = PcApp.ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun lerArr(nome: String): JSONArray = try {
        val a = f(nome); if (a.exists()) JSONArray(a.readText()) else JSONArray()
    } catch (e: Exception) { JSONArray() }

    fun salvarArr(nome: String, a: JSONArray, max: Int = 0) {
        var x = a
        if (max > 0 && a.length() > max) {
            val n = JSONArray()
            for (i in a.length() - max until a.length()) n.put(a.get(i))
            x = n
        }
        f(nome).writeText(x.toString())
    }

    fun adicionar(nome: String, o: JSONObject, max: Int) { val a = lerArr(nome); a.put(o); salvarArr(nome, a, max) }
    fun apagar(nome: String) { f(nome).delete() }

    fun str(k: String, def: String = ""): String = prefs.getString(k, def) ?: def
    fun putStr(k: String, v: String) = prefs.edit().putString(k, v).apply()
    fun int(k: String, def: Int): Int = prefs.getInt(k, def)
    fun putInt(k: String, v: Int) = prefs.edit().putInt(k, v).apply()
    fun bool(k: String, def: Boolean): Boolean = prefs.getBoolean(k, def)
    fun putBool(k: String, v: Boolean) = prefs.edit().putBoolean(k, v).apply()
    fun putLong(k: String, v: Long) = prefs.edit().putLong(k, v).apply()
    fun putFloat(k: String, v: Float) = prefs.edit().putFloat(k, v).apply()
    fun tudo(): Map<String, *> = prefs.all
    fun conjunto(k: String): MutableSet<String> = HashSet(prefs.getStringSet(k, emptySet()) ?: emptySet())
    fun putConjunto(k: String, v: Set<String>) = prefs.edit().putStringSet(k, v).apply()
}

object Log2 {
    fun registrar(msg: String) {
        try {
            val f = File(PcApp.ctx.filesDir, "toolkit.log")
            if (f.exists() && f.length() > 512 * 1024) f.delete()
            f.appendText("[" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date()) + "] " + msg + "\n")
        } catch (e: Exception) { }
    }
}

fun fmtTam(bytes: Long): String {
    val a = kotlin.math.abs(bytes.toDouble())
    val s = if (bytes < 0) "-" else ""
    return when {
        a >= 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%s%.1f GB", s, a / (1024.0 * 1024 * 1024))
        a >= 1024.0 * 1024 -> String.format(Locale.US, "%s%.0f MB", s, a / (1024.0 * 1024))
        else -> String.format(Locale.US, "%s%.0f KB", s, a / 1024.0)
    }
}

fun agora(): String = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(java.util.Date())
fun f1(v: Double): String = String.format(Locale.US, "%.1f", v)
fun f0(v: Double): String = String.format(Locale.US, "%.0f", v)
