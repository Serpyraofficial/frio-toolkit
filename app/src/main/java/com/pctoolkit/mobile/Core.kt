package com.pctoolkit.mobile

import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class PcApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
    }

    companion object {
        lateinit var ctx: Context
    }
}

/** Cores do "console" (mesma ideia das cores do PowerShell). */
object C {
    const val fundo = 0xFF0D1117.toInt()
    const val painel = 0xFF161B22.toInt()
    const val texto = 0xFFE6EDF3.toInt()
    const val verde = 0xFF3FB950.toInt()
    const val amarelo = 0xFFD29922.toInt()
    const val vermelho = 0xFFF85149.toInt()
    const val ciano = 0xFF58A6FF.toInt()
    const val cinza = 0xFF8B949E.toInt()
    const val magenta = 0xFFBC8CFF.toInt()
}

/** Execucao de comandos (normal e como root via su). */
object Sh {
    data class R(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
    }

    @Volatile
    private var temRoot: Boolean? = null

    /** Comandos curtos usam um shell root persistente (o Magisk pede/avisa uma vez so); longos usam su novo. */
    fun root(cmd: String, timeoutS: Long = 60): R =
        if (timeoutS <= 60) persistente(cmd, timeoutS) else run(arrayOf("su", "-c", cmd), timeoutS)

    private var proc: Process? = null
    private var entrada: java.io.OutputStream? = null
    private var fila = java.util.concurrent.LinkedBlockingQueue<String>()
    private var erros = StringBuffer()

    private fun abrirShell(): Boolean {
        return try {
            val p = ProcessBuilder("su").start()
            val f = java.util.concurrent.LinkedBlockingQueue<String>()
            val er = StringBuffer()
            fila = f
            erros = er
            Thread { try { p.inputStream.bufferedReader().forEachLine { f.put(it) } } catch (e: Exception) { }; f.put("\u0000FIM") }.apply { isDaemon = true }.start()
            Thread { try { p.errorStream.bufferedReader().forEachLine { er.append(it).append('\n') } } catch (e: Exception) { } }.apply { isDaemon = true }.start()
            proc = p
            entrada = p.outputStream
            true
        } catch (e: Exception) { false }
    }

    private fun fecharShell() {
        try { proc?.destroy() } catch (e: Exception) { }
        proc = null; entrada = null
        fila.clear()
    }

    @Synchronized
    private fun persistente(cmd: String, timeoutS: Long): R {
        val p0 = proc
        if (p0 == null || !p0.isAlive) { fecharShell(); if (!abrirShell()) return R(-1, "", "su indisponivel") }
        val marca = "__PCTK_" + System.nanoTime() + "__"
        fila.clear()
        erros.setLength(0)
        try {
            val e = entrada ?: return R(-1, "", "sem shell")
            e.write(("(" + cmd + "\n)\necho \"" + marca + ":\$?\"\n").toByteArray())
            e.flush()
        } catch (ex: Exception) { fecharShell(); return R(-1, "", ex.message ?: "erro") }
        val out = StringBuilder()
        val limite = System.currentTimeMillis() + timeoutS * 1000
        while (true) {
            val resta = limite - System.currentTimeMillis()
            if (resta <= 0) { fecharShell(); return R(-2, out.toString().trim(), "tempo esgotado") }
            val l = fila.poll(resta, TimeUnit.MILLISECONDS) ?: continue
            if (l == "\u0000FIM") { fecharShell(); return R(-1, out.toString().trim(), "shell encerrado") }
            if (l.startsWith(marca)) {
                val code = l.substringAfter(':').trim().toIntOrNull() ?: -1
                return R(code, out.toString().trim(), erros.toString().trim())
            }
            out.append(l).append('\n')
        }
    }
    fun sh(cmd: String, timeoutS: Long = 30): R = run(arrayOf("sh", "-c", cmd), timeoutS)

    private fun run(argv: Array<String>, timeoutS: Long): R {
        return try {
            val p = ProcessBuilder(*argv).start()
            p.outputStream.close()
            val outB = StringBuilder()
            val errB = StringBuilder()
            val to = Thread { try { p.inputStream.bufferedReader().forEachLine { synchronized(outB) { outB.append(it).append('\n') } } } catch (e: Exception) { } }
            val te = Thread { try { p.errorStream.bufferedReader().forEachLine { synchronized(errB) { errB.append(it).append('\n') } } } catch (e: Exception) { } }
            to.start(); te.start()
            val fim = p.waitFor(timeoutS, TimeUnit.SECONDS)
            if (!fim) p.destroy()
            to.join(3000); te.join(3000)
            val code = if (fim) p.exitValue() else -2
            R(code, synchronized(outB) { outB.toString().trim() }, synchronized(errB) { errB.toString().trim() })
        } catch (e: Exception) {
            R(-1, "", e.message ?: "erro")
        }
    }

    fun verificarRoot(): Boolean {
        val r = root("id", 60)
        val ok = r.ok && r.out.contains("uid=0")
        temRoot = ok
        return ok
    }

    fun temRoot(): Boolean = temRoot ?: verificarRoot()

    /** Aspas seguras para o shell. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun ler(path: String): String = root("cat ${q(path)} 2>/dev/null", 15).out

    /** Grava um arquivo como root mantendo dono/permissao do arquivo existente. */
    fun gravar(path: String, conteudo: String): Boolean {
        val tmp = File(PcApp.ctx.cacheDir, "w-" + System.nanoTime() + ".tmp")
        tmp.writeText(conteudo)
        tmp.setReadable(true, false)
        val r = root("cat ${q(tmp.absolutePath)} > ${q(path)}", 20)
        tmp.delete()
        return r.ok
    }

    fun setting(ns: String, key: String): String {
        val v = root("settings get $ns $key", 10).out.trim()
        return if (v == "null") "" else v
    }
}

/** Configuracoes e listas persistentes (JSON na pasta privada do app). */
object Store {
    private fun f(nome: String) = File(PcApp.ctx.filesDir, nome)
    private val prefs get() = PcApp.ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun lerArr(nome: String): JSONArray = try {
        val a = f(nome)
        if (a.exists()) JSONArray(a.readText()) else JSONArray()
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

    fun adicionar(nome: String, o: JSONObject, max: Int) {
        val a = lerArr(nome)
        a.put(o)
        salvarArr(nome, a, max)
    }

    fun apagar(nome: String) { f(nome).delete() }

    fun str(k: String, def: String = ""): String = prefs.getString(k, def) ?: def
    fun putStr(k: String, v: String) = prefs.edit().putString(k, v).apply()
    fun int(k: String, def: Int): Int = prefs.getInt(k, def)
    fun putInt(k: String, v: Int) = prefs.edit().putInt(k, v).apply()
    fun bool(k: String, def: Boolean): Boolean = prefs.getBoolean(k, def)
    fun putBool(k: String, v: Boolean) = prefs.edit().putBoolean(k, v).apply()
    fun set(k: String): MutableSet<String> = HashSet(prefs.getStringSet(k, emptySet()) ?: emptySet())
    fun putSet(k: String, v: Set<String>) = prefs.edit().putStringSet(k, v).apply()
}

/**
 * Registro de "desfazer": antes de mudar qualquer coisa, guarda o comando que volta ao valor original.
 * Igual ao Set-RegBackup do toolkit do PC.
 */
class UndoLog(private val arquivo: String) {
    @Synchronized
    fun registrar(chave: String, comandoReverter: String) {
        val a = Store.lerArr(arquivo)
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("k") == chave) return
        a.put(JSONObject().put("k", chave).put("c", comandoReverter))
        Store.salvarArr(arquivo, a)
    }

    fun setting(ns: String, key: String, valor: String): Boolean {
        val atual = Sh.setting(ns, key)
        val rev = if (atual.isEmpty()) "settings delete $ns $key" else "settings put $ns $key ${Sh.q(atual)}"
        registrar("set:$ns:$key", rev)
        return Sh.root("settings put $ns $key ${Sh.q(valor)}", 10).ok
    }

    fun sysfs(path: String, valor: String): Boolean {
        val atual = Sh.ler(path).trim()
        if (atual.isNotEmpty()) registrar("sys:$path", "echo ${Sh.q(atual)} > ${Sh.q(path)}")
        return Sh.root("echo ${Sh.q(valor)} > ${Sh.q(path)}", 10).ok
    }

    fun prop(nome: String, valor: String): Boolean {
        val atual = Sh.root("getprop $nome", 10).out.trim()
        registrar("prop:$nome", "setprop $nome ${Sh.q(atual)}")
        return Sh.root("setprop $nome ${Sh.q(valor)}", 10).ok
    }

    fun comando(chave: String, aplicar: String, reverter: String): Boolean {
        registrar(chave, reverter)
        return Sh.root(aplicar, 30).ok
    }

    fun quantos(): Int = Store.lerArr(arquivo).length()

    fun tem(prefixo: String): Boolean {
        val a = Store.lerArr(arquivo)
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("k").startsWith(prefixo)) return true
        return false
    }

    @Synchronized
    fun desfazerPrefixo(prefixo: String, log: (String) -> Unit): Int {
        val a = Store.lerArr(arquivo)
        val resto = JSONArray()
        var n = 0
        for (i in a.length() - 1 downTo 0) {
            val o = a.getJSONObject(i)
            if (o.getString("k").startsWith(prefixo)) { Sh.root(o.getString("c"), 30); log(o.getString("k")); n++ }
        }
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (!o.getString("k").startsWith(prefixo)) resto.put(o)
        }
        Store.salvarArr(arquivo, resto)
        return n
    }

    @Synchronized
    fun desfazer(log: (String) -> Unit): Int {
        val a = Store.lerArr(arquivo)
        for (i in a.length() - 1 downTo 0) {
            val o = a.getJSONObject(i)
            Sh.root(o.getString("c"), 30)
            log(o.getString("k"))
        }
        Store.apagar(arquivo)
        return a.length()
    }
}

object Undo {
    val global = UndoLog("undo.json")
    val sessao = UndoLog("sessao-undo.json")
}

object Log2 {
    fun registrar(msg: String) {
        try {
            val f = File(PcApp.ctx.filesDir, "toolkit.log")
            if (f.exists() && f.length() > 512 * 1024) f.delete()
            f.appendText("[" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "] " + msg + "\n")
        } catch (e: Exception) { }
    }
}

fun fmtTam(bytes: Long): String {
    val v = bytes.toDouble()
    val s = if (v < 0) "-" else ""
    val a = kotlin.math.abs(v)
    return when {
        a >= 1024.0 * 1024 * 1024 -> String.format(java.util.Locale.US, "%s%.1f GB", s, a / (1024.0 * 1024 * 1024))
        a >= 1024.0 * 1024 -> String.format(java.util.Locale.US, "%s%.0f MB", s, a / (1024.0 * 1024))
        else -> String.format(java.util.Locale.US, "%s%.0f KB", s, a / 1024.0)
    }
}

fun agora(): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
