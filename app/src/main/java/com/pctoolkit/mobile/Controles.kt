package com.pctoolkit.mobile

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Envia teclas e toques para o app que esta na frente (o jogo) pelo servico de entrada do Android,
 * via Shizuku (mesmo poder do ADB). Teclas chegam como de um teclado fisico; toques como dedos na tela.
 */
object Injetor {
    private var im: Any? = null
    private var binder: IBinder? = null
    private var metodo: Method? = null
    val fila = Executors.newSingleThreadExecutor()
    @Volatile var modo = "nenhum"   // binder | interno | shell | nenhum

    @Synchronized
    fun preparar(): Boolean {
        val b0 = binder
        if (im != null && b0 != null && Priv.permitido() && b0.pingBinder()) return true
        if (modo == "interno" && EntradaInterna.pronto()) return true
        im = null; metodo = null; binder = null; modo = "nenhum"
        if (!Priv.permitido() && Interno.pronto() && EntradaInterna.pronto()) { modo = "interno"; return true }
        if (Priv.permitido()) {
            try {
                if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("")
                val b = SystemServiceHelper.getSystemService("input")
                if (b != null) {
                    val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
                    val i = stub.getMethod("asInterface", IBinder::class.java).invoke(null, ShizukuBinderWrapper(b))
                    metodo = i.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                    im = i; binder = b; modo = "binder"
                    return true
                }
            } catch (e: Throwable) { Log2.registrar("injetor binder: $e") }
        }
        if (Sh.privOk()) { modo = "shell"; return true }
        return false
    }

    /** Injeta (chamar so dentro da fila). Se a conexao caiu, reconecta e tenta de novo. */
    private fun injetar(e: InputEvent): Boolean {
        for (t in 0..1) {
            try {
                if (modo != "binder" && modo != "interno" && !preparar()) return false
                if (modo == "interno") { if (EntradaInterna.enviar(e)) return true; if (!preparar()) return false; if (modo == "interno") { if (EntradaInterna.enviar(e)) return true; continue } }
                if (modo != "binder") return false
                metodo?.invoke(im, e, 0)
                return true
            } catch (x: Throwable) {
                Log2.registrar("injetar: $x")
                synchronized(this) { im = null; binder = null }
                if (t == 1) modo = if (Sh.privOk()) "shell" else "nenhum"
            }
        }
        return false
    }

    fun tecla(codigo: Int, desce: Boolean, meta: Int, desde: Long, repeticao: Int = 0) {
        if (codigo <= 0) return
        fila.execute {
            val agora = SystemClock.uptimeMillis()
            val e = KeyEvent(desde, agora, if (desce) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, codigo, repeticao, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD)
            if (!injetar(e) && modo == "shell" && desce && repeticao == 0) Sh.root("input keyevent $codigo", 5)
        }
    }

    /** Aperta e solta (para macros). */
    fun clique(codigo: Int, meta: Int = 0) {
        val d = SystemClock.uptimeMillis()
        tecla(codigo, true, meta, d)
        fila.execute { Thread.sleep(35) }
        tecla(codigo, false, meta, d)
    }

    fun evento(e: MotionEvent, xShell: Float, yShell: Float, ehDown: Boolean) {
        if (!injetar(e) && modo == "shell" && ehDown) Sh.root("input tap ${xShell.toInt()} ${yShell.toInt()}", 5)
        e.recycle()
    }
}

/**
 * Varios "dedos virtuais" ao mesmo tempo, como um unico toque multi-ponto
 * (assim dois botoes de toque podem ser segurados juntos).
 */
object Toque {
    private class Dedo(val pid: Int, var x: Float, var y: Float)
    private val dedos = LinkedHashMap<Long, Dedo>()
    private var inicio = 0L
    private const val DISPOSITIVO = 0x7E0

    private fun props(): Array<MotionEvent.PointerProperties> = dedos.values.map { d ->
        val p = MotionEvent.PointerProperties(); p.id = d.pid; p.toolType = MotionEvent.TOOL_TYPE_FINGER; p
    }.toTypedArray()

    private fun coords(): Array<MotionEvent.PointerCoords> = dedos.values.map { d ->
        val c = MotionEvent.PointerCoords(); c.x = d.x; c.y = d.y; c.pressure = 1f; c.size = 0.05f; c.touchMajor = 12f; c.touchMinor = 12f; c
    }.toTypedArray()

    private fun obter(acao: Int): MotionEvent = MotionEvent.obtain(inicio, SystemClock.uptimeMillis(), acao, dedos.size, props(), coords(),
        0, 0, 1f, 1f, DISPOSITIVO, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)

    fun descer(chave: Long, x: Float, y: Float) {
        Injetor.fila.execute {
            if (dedos.containsKey(chave)) return@execute
            val usados = dedos.values.map { it.pid }.toSet()
            var pid = 0
            while (usados.contains(pid)) pid++
            if (dedos.isEmpty()) inicio = SystemClock.uptimeMillis()
            dedos[chave] = Dedo(pid, x, y)
            val idx = dedos.keys.indexOf(chave)
            val acao = if (dedos.size == 1) MotionEvent.ACTION_DOWN else (MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            Injetor.evento(obter(acao), x, y, true)
        }
    }

    fun mover(chave: Long, x: Float, y: Float) {
        Injetor.fila.execute {
            val d = dedos[chave] ?: return@execute
            d.x = x; d.y = y
            Injetor.evento(obter(MotionEvent.ACTION_MOVE), x, y, false)
        }
    }

    fun subir(chave: Long) {
        Injetor.fila.execute {
            val d = dedos[chave] ?: return@execute
            val idx = dedos.keys.indexOf(chave)
            val acao = if (dedos.size == 1) MotionEvent.ACTION_UP else (MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            Injetor.evento(obter(acao), d.x, d.y, false)
            dedos.remove(chave)
        }
    }

    fun soltarTudo() { for (k in ArrayList(dedos.keys)) subir(k) }

    /** Toque rapido (turbo / macro). */
    fun tocar(chave: Long, x: Float, y: Float) { descer(chave, x, y); Injetor.fila.execute { Thread.sleep(30) }; subir(chave) }
}

/** Nomes de teclas em portugues <-> codigos do Android. */
object Teclas {
    private val nomes = linkedMapOf(
        "ESPAÇO" to KeyEvent.KEYCODE_SPACE, "ESPACO" to KeyEvent.KEYCODE_SPACE, "SPACE" to KeyEvent.KEYCODE_SPACE,
        "ENTER" to KeyEvent.KEYCODE_ENTER, "SHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT, "CTRL" to KeyEvent.KEYCODE_CTRL_LEFT,
        "ALT" to KeyEvent.KEYCODE_ALT_LEFT, "TAB" to KeyEvent.KEYCODE_TAB, "ESC" to KeyEvent.KEYCODE_ESCAPE,
        "CAPS" to KeyEvent.KEYCODE_CAPS_LOCK, "APAGAR" to KeyEvent.KEYCODE_DEL, "BACKSPACE" to KeyEvent.KEYCODE_DEL,
        "DEL" to KeyEvent.KEYCODE_FORWARD_DEL, "CIMA" to KeyEvent.KEYCODE_DPAD_UP, "BAIXO" to KeyEvent.KEYCODE_DPAD_DOWN,
        "ESQUERDA" to KeyEvent.KEYCODE_DPAD_LEFT, "DIREITA" to KeyEvent.KEYCODE_DPAD_RIGHT, "↑" to KeyEvent.KEYCODE_DPAD_UP,
        "↓" to KeyEvent.KEYCODE_DPAD_DOWN, "←" to KeyEvent.KEYCODE_DPAD_LEFT, "→" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "UP" to KeyEvent.KEYCODE_DPAD_UP, "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN, "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT, "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "HOME" to KeyEvent.KEYCODE_MOVE_HOME, "END" to KeyEvent.KEYCODE_MOVE_END, "PGUP" to KeyEvent.KEYCODE_PAGE_UP, "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN,
        "INS" to KeyEvent.KEYCODE_INSERT, "WIN" to KeyEvent.KEYCODE_META_LEFT,
        "," to KeyEvent.KEYCODE_COMMA, "." to KeyEvent.KEYCODE_PERIOD, "/" to KeyEvent.KEYCODE_SLASH, ";" to KeyEvent.KEYCODE_SEMICOLON,
        "'" to KeyEvent.KEYCODE_APOSTROPHE, "[" to KeyEvent.KEYCODE_LEFT_BRACKET, "]" to KeyEvent.KEYCODE_RIGHT_BRACKET,
        "-" to KeyEvent.KEYCODE_MINUS, "=" to KeyEvent.KEYCODE_EQUALS, "`" to KeyEvent.KEYCODE_GRAVE, "\\" to KeyEvent.KEYCODE_BACKSLASH,
        "BOTÃO A" to KeyEvent.KEYCODE_BUTTON_A, "BOTÃO B" to KeyEvent.KEYCODE_BUTTON_B, "BOTÃO X" to KeyEvent.KEYCODE_BUTTON_X, "BOTÃO Y" to KeyEvent.KEYCODE_BUTTON_Y,
        "L1" to KeyEvent.KEYCODE_BUTTON_L1, "R1" to KeyEvent.KEYCODE_BUTTON_R1, "L2" to KeyEvent.KEYCODE_BUTTON_L2, "R2" to KeyEvent.KEYCODE_BUTTON_R2
    )

    val comuns = listOf("W", "A", "S", "D", "Espaço", "Shift", "Ctrl", "Alt", "Enter", "Tab", "Esc", "Q", "E", "R", "F", "G", "C", "V", "X", "Z",
        "1", "2", "3", "4", "5", "↑", "↓", "←", "→", "F1", "F2", "F3", "F4", "Apagar", "Botão A", "Botão B", "L1", "R1")

    fun codigo(texto: String): Int {
        val t = texto.trim().uppercase(Locale.ROOT)
        if (t.isEmpty()) return 0
        nomes[t]?.let { return it }
        if (t.length == 1 && (t[0] in 'A'..'Z' || t[0] in '0'..'9')) return KeyEvent.keyCodeFromString("KEYCODE_$t")
        if (Regex("F([1-9]|1[0-2])").matches(t)) return KeyEvent.KEYCODE_F1 + t.substring(1).toInt() - 1
        val c = KeyEvent.keyCodeFromString("KEYCODE_" + t.replace(' ', '_'))
        return if (c > 0) c else 0
    }

    fun nome(codigo: Int): String {
        for ((k, v) in nomes) if (v == codigo && k.length > 1 && k != "SPACE" && k != "ESPACO" && k != "BACKSPACE" && !k.startsWith("UP")) {
            return when (k) { "ESPAÇO" -> "Espaço"; "APAGAR" -> "⌫"; "CIMA" -> "↑"; "BAIXO" -> "↓"; "ESQUERDA" -> "←"; "DIREITA" -> "→"; else -> k.lowercase().replaceFirstChar { it.uppercase() }.let { if (it.length <= 4) it.uppercase() else it } }
        }
        if (codigo in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) return "F" + (codigo - KeyEvent.KEYCODE_F1 + 1)
        return KeyEvent.keyCodeToString(codigo).removePrefix("KEYCODE_").let { if (it.length > 6) it.take(6) else it }
    }
}

/** Um botao/controle colocado na tela. Posicoes em fracao da tela (0..1). */
class Controle(
    var id: Long = System.nanoTime(),
    var tipo: String = "tecla",      // tecla | toque | analogico | macro
    var x: Float = 0.5f, var y: Float = 0.5f,
    var tam: Int = 56,               // dp
    var rotulo: String = "",
    var tecla: Int = 0,
    var tx: Float = 0.5f, var ty: Float = 0.5f,   // ponto que recebe o toque (tipo toque/analogico)
    var modo: String = "segurar",    // segurar | alternar | turbo
    var turboMs: Int = 80,
    var macro: String = "",
    var analog: String = "wasd",     // wasd | setas | toque
    var raio: Float = 0.08f,         // alcance do analogico de toque (fracao da largura)
    var opac: Float = 0.75f
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("tipo", tipo).put("x", x.toDouble()).put("y", y.toDouble()).put("tam", tam)
        .put("rotulo", rotulo).put("tecla", tecla).put("tx", tx.toDouble()).put("ty", ty.toDouble()).put("modo", modo).put("turbo", turboMs)
        .put("macro", macro).put("analog", analog).put("raio", raio.toDouble()).put("opac", opac.toDouble())

    fun texto(): String = when {
        rotulo.isNotEmpty() -> rotulo
        tipo == "analogico" -> when (analog) { "setas" -> "↕↔"; "toque" -> "◎"; else -> "WASD" }
        tipo == "macro" -> "M"
        tecla > 0 -> Teclas.nome(tecla)
        tipo == "toque" -> "●"
        else -> "?"
    }

    companion object {
        fun de(o: JSONObject) = Controle(o.optLong("id", System.nanoTime()), o.optString("tipo", "tecla"), o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat(),
            o.optInt("tam", 56), o.optString("rotulo"), o.optInt("tecla"), o.optDouble("tx", 0.5).toFloat(), o.optDouble("ty", 0.5).toFloat(),
            o.optString("modo", "segurar"), o.optInt("turbo", 80), o.optString("macro"), o.optString("analog", "wasd"), o.optDouble("raio", 0.08).toFloat(), o.optDouble("opac", 0.75).toFloat())
    }
}

/** Regras de onde o toque enviado pelo app pode chegar. */
object Controles {
    /** O circulo deste controle fica em cima do proprio ponto do jogo? */
    fun cobreAlvo(c: Controle, w: Int, h: Int, tamPx: Int): Boolean {
        if (c.tipo != "toque" && !(c.tipo == "analogico" && c.analog == "toque")) return false
        val meio = tamPx / 2f + 4
        val dx = Math.abs(c.x * w - c.tx * w); val dy = Math.abs(c.y * h - c.ty * h)
        val alcance = if (c.tipo == "analogico") c.raio * w else 0f
        return dx < meio + alcance && dy < meio + alcance
    }

    /** Ponto do jogo de um botao escondido embaixo de OUTRO botao (o toque nao chegaria ao jogo). */
    fun conflitos(l: List<Controle>, w: Int, h: Int, dp: (Int) -> Int): String? {
        val r = ArrayList<String>()
        for (a in l) {
            if (a.tipo != "toque" && !(a.tipo == "analogico" && a.analog == "toque")) continue
            for (b in l) {
                if (a === b) continue
                val tam = dp(if (b.tipo == "analogico") b.tam * 2 else b.tam)
                if (cobreAlvo(b, w, h, tam)) continue   // b vira marca, nao bloqueia
                if (Math.abs(b.x * w - a.tx * w) < tam / 2f && Math.abs(b.y * h - a.ty * h) < tam / 2f) r.add("o ponto de “${a.texto()}” está embaixo de “${b.texto()}”")
            }
        }
        return if (r.isEmpty()) null else "Atenção: " + r.distinct().take(3).joinToString("; ") + ". Arraste um deles, senão o toque não chega ao jogo."
    }
}

/** Perfis de controles por jogo. */
object PerfisControle {
    private fun chave(pkg: String) = "ctl_$pkg"
    fun carregar(pkg: String): MutableList<Controle> {
        val s = Store.str(chave(pkg))
        if (s.isEmpty()) return ArrayList()
        return try { val a = JSONArray(s); (0 until a.length()).map { Controle.de(a.getJSONObject(it)) }.toMutableList() } catch (e: Exception) { ArrayList() }
    }
    fun salvar(pkg: String, l: List<Controle>) { val a = JSONArray(); l.forEach { a.put(it.json()) }; Store.putStr(chave(pkg), a.toString()) }
    fun tem(pkg: String) = Store.str(chave(pkg)).length > 2

    /** Copia o perfil de um jogo para outro. */
    fun copiar(de: String, para: String) { Store.putStr(chave(para), Store.str(chave(de))) }
}

/** Tamanho real da tela e o app em primeiro plano. */
object Tela {
    fun tamanho(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds; Pair(b.width(), b.height())
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    /** Pacote do app na frente (Shizuku). */
    fun appNaFrente(): String? {
        if (!Sh.privOk()) return null
        val o = Sh.root("dumpsys activity activities 2>/dev/null | grep -m2 -E 'topResumedActivity|mResumedActivity'; dumpsys window 2>/dev/null | grep -m1 mCurrentFocus", 8).out
        return Regex("u0 ([A-Za-z0-9_.]+)/").find(o)?.groupValues?.get(1)
    }
}
