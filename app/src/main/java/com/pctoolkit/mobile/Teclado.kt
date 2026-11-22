package com.pctoolkit.mobile

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.util.concurrent.Executors

/**
 * Envia teclas para o app que esta na frente (o jogo) como se fosse um teclado fisico.
 * Usa o servico de entrada do Android pelo Shizuku (mesmo poder do ADB): aperta e solta
 * de verdade, permite segurar teclas e varias ao mesmo tempo.
 */
object Injetor {
    private var im: Any? = null
    private var metodo: Method? = null
    private val fila = Executors.newSingleThreadExecutor()
    @Volatile var modo = "nenhum"   // binder | shell | nenhum

    fun preparar(): Boolean {
        if (im != null && Priv.pronto()) return true
        im = null; metodo = null; modo = "nenhum"
        if (Priv.pronto()) {
            try {
                if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("")
                val b = SystemServiceHelper.getSystemService("input")
                if (b != null) {
                    val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
                    val i = stub.getMethod("asInterface", IBinder::class.java).invoke(null, ShizukuBinderWrapper(b))
                    val m = i.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                    im = i; metodo = m; modo = "binder"
                    return true
                }
            } catch (e: Throwable) { Log2.registrar("injetor binder: $e") }
        }
        if (Sh.privOk()) { modo = "shell"; return true }
        return false
    }

    fun disponivel(): Boolean = Sh.privOk()

    fun tecla(codigo: Int, desce: Boolean, meta: Int, desde: Long, repeticao: Int = 0) {
        fila.execute {
            try {
                if (modo == "binder") {
                    val agora = SystemClock.uptimeMillis()
                    val e = KeyEvent(desde, agora, if (desce) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, codigo, repeticao, meta,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD)
                    metodo?.invoke(im, e, 0)
                } else if (modo == "shell" && desce && repeticao == 0) {
                    Sh.root("input keyevent $codigo", 5)
                }
            } catch (e: Throwable) {
                Log2.registrar("injetor: $e")
                if (modo == "binder") { modo = "shell" }
            }
        }
    }
}

/** Uma tecla do teclado virtual. */
class Tecla(val rotulo: String, val codigo: Int, val peso: Float = 1f, val altRotulo: String = "")

object Layouts {
    private fun letras(s: String) = s.map { Tecla(it.toString(), KeyEvent.keyCodeFromString("KEYCODE_" + it), 1f, it.lowercase()) }
    private fun numeros(s: String) = s.map { Tecla(it.toString(), KeyEvent.keyCodeFromString("KEYCODE_" + it)) }

    val ESC = Tecla("Esc", KeyEvent.KEYCODE_ESCAPE, 1.2f)
    val TAB = Tecla("Tab", KeyEvent.KEYCODE_TAB, 1.4f)
    val SHIFT = Tecla("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, 1.6f)
    val CTRL = Tecla("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, 1.3f)
    val ALT = Tecla("Alt", KeyEvent.KEYCODE_ALT_LEFT, 1.2f)
    val ESPACO = Tecla("Espaço", KeyEvent.KEYCODE_SPACE, 5f)
    val ENTER = Tecla("Enter", KeyEvent.KEYCODE_ENTER, 1.8f)
    val APAGAR = Tecla("⌫", KeyEvent.KEYCODE_DEL, 1.6f)

    /** Teclado completo de PC. */
    fun completo(): List<List<Tecla>> = listOf(
        listOf(ESC) + (1..12).map { Tecla("F$it", KeyEvent.KEYCODE_F1 + it - 1) } + listOf(Tecla("Del", KeyEvent.KEYCODE_FORWARD_DEL)),
        listOf(Tecla("`", KeyEvent.KEYCODE_GRAVE)) + numeros("1234567890") + listOf(Tecla("-", KeyEvent.KEYCODE_MINUS), Tecla("=", KeyEvent.KEYCODE_EQUALS), APAGAR),
        listOf(TAB) + letras("QWERTYUIOP") + listOf(Tecla("[", KeyEvent.KEYCODE_LEFT_BRACKET), Tecla("]", KeyEvent.KEYCODE_RIGHT_BRACKET), Tecla("\\", KeyEvent.KEYCODE_BACKSLASH)),
        listOf(Tecla("Caps", KeyEvent.KEYCODE_CAPS_LOCK, 1.6f)) + letras("ASDFGHJKL") + listOf(Tecla(";", KeyEvent.KEYCODE_SEMICOLON), Tecla("'", KeyEvent.KEYCODE_APOSTROPHE), ENTER),
        listOf(SHIFT) + letras("ZXCVBNM") + listOf(Tecla(",", KeyEvent.KEYCODE_COMMA), Tecla(".", KeyEvent.KEYCODE_PERIOD), Tecla("/", KeyEvent.KEYCODE_SLASH), Tecla("↑", KeyEvent.KEYCODE_DPAD_UP), Tecla("Shift", KeyEvent.KEYCODE_SHIFT_RIGHT, 1.3f)),
        listOf(CTRL, Tecla("Win", KeyEvent.KEYCODE_META_LEFT), ALT, ESPACO, Tecla("Alt", KeyEvent.KEYCODE_ALT_RIGHT), Tecla("←", KeyEvent.KEYCODE_DPAD_LEFT), Tecla("↓", KeyEvent.KEYCODE_DPAD_DOWN), Tecla("→", KeyEvent.KEYCODE_DPAD_RIGHT), Tecla("Ctrl", KeyEvent.KEYCODE_CTRL_RIGHT, 1.2f)),
        listOf(Tecla("Ins", KeyEvent.KEYCODE_INSERT), Tecla("Home", KeyEvent.KEYCODE_MOVE_HOME), Tecla("End", KeyEvent.KEYCODE_MOVE_END), Tecla("PgUp", KeyEvent.KEYCODE_PAGE_UP), Tecla("PgDn", KeyEvent.KEYCODE_PAGE_DOWN), Tecla("PrtSc", KeyEvent.KEYCODE_SYSRQ), Tecla("Pause", KeyEvent.KEYCODE_BREAK))
    )

    /** Lado esquerdo do modo Gamer: movimento. */
    fun gamerEsquerda(): List<List<Tecla>> = listOf(
        listOf(ESC, Tecla("1", KeyEvent.KEYCODE_1), Tecla("2", KeyEvent.KEYCODE_2), Tecla("3", KeyEvent.KEYCODE_3), Tecla("4", KeyEvent.KEYCODE_4)),
        listOf(TAB, Tecla("Q", KeyEvent.KEYCODE_Q), Tecla("W", KeyEvent.KEYCODE_W), Tecla("E", KeyEvent.KEYCODE_E), Tecla("R", KeyEvent.KEYCODE_R)),
        listOf(Tecla("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, 1.4f), Tecla("A", KeyEvent.KEYCODE_A), Tecla("S", KeyEvent.KEYCODE_S), Tecla("D", KeyEvent.KEYCODE_D), Tecla("F", KeyEvent.KEYCODE_F)),
        listOf(CTRL, Tecla("Z", KeyEvent.KEYCODE_Z), Tecla("X", KeyEvent.KEYCODE_X), Tecla("C", KeyEvent.KEYCODE_C), Tecla("V", KeyEvent.KEYCODE_V))
    )

    /** Lado direito do modo Gamer: acoes. */
    fun gamerDireita(): List<List<Tecla>> = listOf(
        listOf(Tecla("5", KeyEvent.KEYCODE_5), Tecla("6", KeyEvent.KEYCODE_6), Tecla("7", KeyEvent.KEYCODE_7), Tecla("M", KeyEvent.KEYCODE_M)),
        listOf(Tecla("G", KeyEvent.KEYCODE_G), Tecla("T", KeyEvent.KEYCODE_T), Tecla("↑", KeyEvent.KEYCODE_DPAD_UP), Tecla("I", KeyEvent.KEYCODE_I)),
        listOf(Tecla("←", KeyEvent.KEYCODE_DPAD_LEFT), Tecla("↓", KeyEvent.KEYCODE_DPAD_DOWN), Tecla("→", KeyEvent.KEYCODE_DPAD_RIGHT), ENTER),
        listOf(Tecla("Alt", KeyEvent.KEYCODE_ALT_LEFT), Tecla("Espaço", KeyEvent.KEYCODE_SPACE, 3f))
    )

    /** So setas + basicos (menus, emuladores, jogos de corrida). */
    fun setas(): List<List<Tecla>> = listOf(
        listOf(ESC, Tecla("↑", KeyEvent.KEYCODE_DPAD_UP), ENTER),
        listOf(Tecla("←", KeyEvent.KEYCODE_DPAD_LEFT), Tecla("↓", KeyEvent.KEYCODE_DPAD_DOWN), Tecla("→", KeyEvent.KEYCODE_DPAD_RIGHT)),
        listOf(Tecla("Shift", KeyEvent.KEYCODE_SHIFT_LEFT), Tecla("Espaço", KeyEvent.KEYCODE_SPACE, 2f))
    )

    val nomes = listOf("Gamer (WASD + ações)", "Teclado completo", "Setas")
    val ids = listOf("gamer", "completo", "setas")
}

class TecladoService : Service() {
    companion object {
        @Volatile var rodando = false
        private const val CANAL = "teclado"
        private const val ID = 5050
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val janelas = ArrayList<Pair<View, WindowManager.LayoutParams>>()
    private var bolha: Pair<View, WindowManager.LayoutParams>? = null
    private var meta = 0
    private var caps = false
    private var layout = "gamer"
    private val segurando = HashMap<Int, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "fechar" -> { fechar(); return START_NOT_STICKY }
            "alternar" -> { if (janelas.isNotEmpty()) minimizar() else mostrar(); return START_STICKY }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Teclado no jogo", NotificationManager.IMPORTANCE_LOW))
        val n = notif()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        rodando = true
        layout = intent?.getStringExtra("layout") ?: Store.str("tec_layout", "gamer")
        Store.putStr("tec_layout", layout)
        Thread { Injetor.preparar() }.start()
        ui.post { limpar(); mostrar() }
        val pkg = intent?.getStringExtra("pkg")
        if (pkg != null) {
            val abrir = packageManager.getLaunchIntentForPackage(pkg)
            if (abrir != null) { abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { startActivity(abrir) } catch (e: Exception) { } }
        }
        return START_STICKY
    }

    private fun notif(): Notification {
        val alt = PendingIntent.getService(this, 11, Intent(this, TecladoService::class.java).setAction("alternar"), PendingIntent.FLAG_IMMUTABLE)
        val fec = PendingIntent.getService(this, 12, Intent(this, TecladoService::class.java).setAction("fechar"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CANAL).setSmallIcon(R.drawable.ic_jogo).setContentTitle("Teclado no jogo ativo")
            .setContentText("Toque em ⌨ para mostrar ou esconder").setColor(T.verde).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Mostrar/Esconder", alt).build())
            .addAction(Notification.Action.Builder(null, "Fechar teclado", fec).build()).build()
    }

    // ---------------- janelas ----------------
    private fun lp(x: Int, y: Int, larguraTotal: Boolean): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            if (larguraTotal) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = x; p.y = y
        return p
    }

    private fun escala(): Float = Store.str("tec_escala", "1.0").toFloatOrNull() ?: 1f
    private fun opacidade(): Float = Store.str("tec_opac", "0.85").toFloatOrNull() ?: 0.85f

    private fun mostrar() {
        removerBolha()
        val dm = resources.displayMetrics
        when (layout) {
            "completo" -> {
                val v = painel("completo", Layouts.completo(), true)
                addJanela(v, lp(0, Store.int("tec_y_completo", dm.heightPixels / 2), true), "completo", true)
            }
            "setas" -> {
                val v = painel("setas", Layouts.setas(), false)
                addJanela(v, lp(Store.int("tec_x_setas", Ui.dp(this, 16)), Store.int("tec_y_setas", dm.heightPixels / 2), false), "setas", false)
            }
            else -> {
                val e = painel("esq", Layouts.gamerEsquerda(), false)
                addJanela(e, lp(Store.int("tec_x_esq", Ui.dp(this, 12)), Store.int("tec_y_esq", dm.heightPixels / 2), false), "esq", false)
                val d = painel("dir", Layouts.gamerDireita(), false)
                addJanela(d, lp(Store.int("tec_x_dir", dm.widthPixels - Ui.dp(this, 260)), Store.int("tec_y_dir", dm.heightPixels / 2), false), "dir", false)
            }
        }
    }

    private fun addJanela(v: View, p: WindowManager.LayoutParams, nome: String, soY: Boolean) {
        v.alpha = opacidade()
        v.tag = arrayOf(nome, soY)
        try { wm.addView(v, p); janelas.add(Pair(v, p)) } catch (e: Exception) { Log2.registrar("teclado addView: $e") }
    }

    private fun limpar() {
        soltarTudo()
        for ((v, _) in janelas) try { wm.removeView(v) } catch (e: Exception) { }
        janelas.clear()
        removerBolha()
    }

    private fun removerBolha() { bolha?.let { try { wm.removeView(it.first) } catch (e: Exception) { } }; bolha = null }

    private fun minimizar() {
        soltarTudo()
        for ((v, _) in janelas) try { wm.removeView(v) } catch (e: Exception) { }
        janelas.clear()
        val b = Ui.texto(this, "⌨", 22f, T.verde, Ui.negrito)
        b.gravity = Gravity.CENTER
        b.background = Ui.forma(this, 0xE60A0E14.toInt(), 50, Ui.alpha(T.verde, 0x88))
        val tam = Ui.dp(this, 48)
        val p = lp(Store.int("tec_bx", Ui.dp(this, 8)), Store.int("tec_by", Ui.dp(this, 120)), false)
        p.width = tam; p.height = tam
        arrastavel(b, b, p, "b", false, toque = { mostrar() })
        try { wm.addView(b, p); bolha = Pair(b, p) } catch (e: Exception) { }
    }

    private fun fechar() {
        ui.post {
            limpar()
            rodando = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() { limpar(); rodando = false; super.onDestroy() }

    // ---------------- montagem do painel ----------------
    @SuppressLint("ClickableViewAccessibility")
    private fun painel(nome: String, linhas: List<List<Tecla>>, larguraTotal: Boolean): View {
        val esc = escala()
        val alturaTecla = (Ui.dp(this, if (larguraTotal) 38 else 50) * esc).toInt()
        val larguraTecla = (Ui.dp(this, 50) * esc).toInt()
        val raiz = Ui.vertical(this)
        raiz.background = Ui.forma(this, 0xD90A0E14.toInt(), 16, Ui.alpha(T.verde, 0x55))
        raiz.setPadding(Ui.dp(this, 5), Ui.dp(this, 3), Ui.dp(this, 5), Ui.dp(this, 6))
        raiz.isMotionEventSplittingEnabled = true

        // barra de controle: arrastar, layout, tamanho, transparencia, minimizar, fechar
        val barra = Ui.horizontal(this)
        val alca = Ui.texto(this, "⠿ mover", 11f, T.texto3, Ui.medio)
        alca.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4))
        barra.addView(alca)
        barra.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        fun botao(t: String, acao: () -> Unit) {
            val b = Ui.texto(this, t, 12f, T.texto2, Ui.medio)
            b.setPadding(Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 9), Ui.dp(this, 4))
            b.setOnClickListener { acao() }
            barra.addView(b)
        }
        if (nome != "dir") {
            botao("Layout") { val i = (Layouts.ids.indexOf(layout) + 1) % Layouts.ids.size; layout = Layouts.ids[i]; Store.putStr("tec_layout", layout); limpar(); mostrar() }
            botao("A±") { val e = escala(); Store.putStr("tec_escala", (if (e >= 1.29f) 0.8f else e + 0.1f).toString()); limpar(); mostrar() }
            botao("◐") { val o = opacidade(); Store.putStr("tec_opac", (if (o <= 0.4f) 1.0f else o - 0.2f).toString()); for ((v, _) in janelas) v.alpha = opacidade() }
        }
        botao("—") { minimizar() }
        botao("✕") { fechar() }
        raiz.addView(barra)

        for (linha in linhas) {
            val l = LinearLayout(this)
            l.orientation = LinearLayout.HORIZONTAL
            l.isMotionEventSplittingEnabled = true
            for (t in linha) {
                val v = teclaView(t)
                val p = if (larguraTotal) LinearLayout.LayoutParams(0, alturaTecla, t.peso) else LinearLayout.LayoutParams((larguraTecla * t.peso).toInt(), alturaTecla)
                p.setMargins(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2))
                l.addView(v, p)
            }
            raiz.addView(l, LinearLayout.LayoutParams(if (larguraTotal) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        ui.post {
            val par = janelas.firstOrNull { it.first == raiz }
            if (par != null) arrastavel(alca, raiz, par.second, nome, larguraTotal)
        }
        return raiz
    }

    private fun ehModificador(c: Int) = c == KeyEvent.KEYCODE_SHIFT_LEFT || c == KeyEvent.KEYCODE_SHIFT_RIGHT || c == KeyEvent.KEYCODE_CTRL_LEFT ||
        c == KeyEvent.KEYCODE_CTRL_RIGHT || c == KeyEvent.KEYCODE_ALT_LEFT || c == KeyEvent.KEYCODE_ALT_RIGHT || c == KeyEvent.KEYCODE_META_LEFT

    private fun metaDe(c: Int): Int = when (c) {
        KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
        KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
        KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
        KeyEvent.KEYCODE_META_LEFT -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        else -> 0
    }

    private fun metaAtual(): Int = meta or (if (caps) KeyEvent.META_CAPS_LOCK_ON else 0)

    @SuppressLint("ClickableViewAccessibility")
    private fun teclaView(t: Tecla): View {
        val v = TextView(this)
        v.text = t.rotulo
        v.gravity = Gravity.CENTER
        v.setTextColor(T.texto)
        v.textSize = if (t.rotulo.length > 3) 11f else 14f
        v.typeface = Ui.medio
        val normal = Ui.forma(this, 0xFF1A222D.toInt(), 9, T.outline)
        val apertada = Ui.forma(this, Ui.alpha(T.verde, 0xCC), 9, T.verde)
        v.background = normal
        val repetir = object : Runnable {
            var n = 0
            override fun run() {
                val d = segurando[t.codigo] ?: return
                n++
                Injetor.tecla(t.codigo, true, metaAtual(), d, n)
                ui.postDelayed(this, 45)
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.background = apertada
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val agora = SystemClock.uptimeMillis()
                    if (t.codigo == KeyEvent.KEYCODE_CAPS_LOCK) caps = !caps
                    if (ehModificador(t.codigo)) meta = meta or metaDe(t.codigo)
                    segurando[t.codigo] = agora
                    Injetor.tecla(t.codigo, true, metaAtual(), agora)
                    if (!ehModificador(t.codigo)) { repetir.n = 0; ui.postDelayed(repetir, 420) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(repetir)
                    v.background = if (t.codigo == KeyEvent.KEYCODE_CAPS_LOCK && caps) Ui.forma(this, Ui.alpha(T.verde, 0x55), 9, T.verde) else normal
                    val d = segurando.remove(t.codigo) ?: SystemClock.uptimeMillis()
                    if (ehModificador(t.codigo)) meta = meta and metaDe(t.codigo).inv()
                    Injetor.tecla(t.codigo, false, metaAtual(), d)
                    true
                }
                else -> true
            }
        }
        return v
    }

    /** Solta tudo que estiver pressionado (ao esconder/fechar), para nenhuma tecla ficar presa no jogo. */
    private fun soltarTudo() {
        for ((c, d) in HashMap(segurando)) Injetor.tecla(c, false, 0, d)
        segurando.clear(); meta = 0
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun arrastavel(alca: View, janela: View, p: WindowManager.LayoutParams, nome: String, soY: Boolean, toque: (() -> Unit)? = null) {
        var ix = 0f; var iy = 0f; var ox = 0; var oy = 0; var moveu = false
        alca.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { ix = e.rawX; iy = e.rawY; ox = p.x; oy = p.y; moveu = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - ix).toInt(); val dy = (e.rawY - iy).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moveu = true
                    if (!soY) p.x = ox + dx
                    p.y = oy + dy
                    try { wm.updateViewLayout(janela, p) } catch (ex: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (nome == "b") { Store.putInt("tec_bx", p.x); Store.putInt("tec_by", p.y) }
                    else { if (!soY) Store.putInt("tec_x_$nome", p.x); Store.putInt("tec_y_$nome", p.y) }
                    if (!moveu) toque?.invoke()
                    true
                }
                else -> false
            }
        }
    }
}

/** Tela de tarefa para iniciar o teclado. */
object TecladoJogo {
    fun iniciar(con: Console, pkgFixo: String? = null) {
        val ctx = con.ctx
        con.titulo("Teclado no jogo")
        con.linha("Abre o jogo com um teclado virtual flutuante. As teclas chegam ao jogo como um teclado físico de verdade: dá para segurar, apertar várias ao mesmo tempo e usar Shift, Ctrl e Alt.", T.texto2)
        if (TecladoService.rodando) {
            if (con.ask("O teclado já está aberto. Fechar?", "Fechar", "Manter", importante = true)) ctx.startService(Intent(ctx, TecladoService::class.java).setAction("fechar"))
            return
        }
        if (!Sh.privOk()) {
            con.aviso("Para enviar teclas a outro app o Android exige o modo automático (Shizuku) ou root.")
            if (!Priv.ativar(con)) return
        }
        if (!con.garantirNome("Sobrepor a outros apps")) { con.erro("Sem permissão de sobreposição o teclado não aparece por cima do jogo."); return }
        if (!Injetor.preparar()) { con.erro("Não consegui acessar o serviço de teclado do Android."); return }
        if (Injetor.modo == "shell") con.aviso("Modo compatível: as teclas funcionam com toque simples, mas segurar teclas não funciona neste celular.")
        else con.ok("Envio de teclas pronto (aperta, segura e solta como um teclado real)")
        val jogo = if (pkgFixo != null) Jogo(pkgFixo, Jogos.nome(ctx, pkgFixo), "") else Jogos.escolher(con, "Abrir qual jogo com o teclado?")
        if (jogo == null) return
        val atual = Layouts.ids.indexOf(Store.str("tec_layout", "gamer")).coerceAtLeast(0)
        val i = con.escolher("Layout do teclado (troque depois no botão Layout)", Layouts.nomes.mapIndexed { k, n -> if (k == atual) "$n  ✓" else n })
        val layout = Layouts.ids[if (i < 0) atual else i]
        ctx.startForegroundService(Intent(ctx, TecladoService::class.java).putExtra("pkg", jogo.pkg).putExtra("layout", layout))
        con.linha("")
        con.ok("Abrindo ${jogo.nome} com o teclado")
        con.subtitulo("Como usar")
        con.item("⠿ mover: arraste para posicionar cada parte do teclado")
        con.item("Layout: alterna entre Gamer, Completo e Setas")
        con.item("A±: tamanho das teclas  ·  ◐: transparência")
        con.item("—: minimiza para a bolinha ⌨ (toque nela para voltar)  ·  ✕: fecha")
        con.linha("")
        con.info("Funciona em jogos que aceitam teclado físico (Roblox, Minecraft, emuladores, jogos de PC portados, navegador). Jogos feitos só para toque, como Free Fire e PUBG Mobile, ignoram teclas de teclado.")
    }
}
