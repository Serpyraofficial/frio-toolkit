package com.pctoolkit.mobile

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Uma tecla do teclado virtual (paineis prontos). */
class Tecla(val rotulo: String, val codigo: Int, val peso: Float = 1f)

object Layouts {
    private fun letras(s: String) = s.map { Tecla(it.toString(), KeyEvent.keyCodeFromString("KEYCODE_$it")) }
    private fun t(r: String, c: Int, p: Float = 1f) = Tecla(r, c, p)
    private val ESC = t("Esc", KeyEvent.KEYCODE_ESCAPE, 1.2f)
    private val ENTER = t("Enter", KeyEvent.KEYCODE_ENTER, 1.8f)

    fun completo(): List<List<Tecla>> = listOf(
        listOf(ESC) + (1..12).map { t("F$it", KeyEvent.KEYCODE_F1 + it - 1) } + listOf(t("Del", KeyEvent.KEYCODE_FORWARD_DEL)),
        listOf(t("`", KeyEvent.KEYCODE_GRAVE)) + letras("1234567890") + listOf(t("-", KeyEvent.KEYCODE_MINUS), t("=", KeyEvent.KEYCODE_EQUALS), t("⌫", KeyEvent.KEYCODE_DEL, 1.6f)),
        listOf(t("Tab", KeyEvent.KEYCODE_TAB, 1.4f)) + letras("QWERTYUIOP") + listOf(t("[", KeyEvent.KEYCODE_LEFT_BRACKET), t("]", KeyEvent.KEYCODE_RIGHT_BRACKET), t("\\", KeyEvent.KEYCODE_BACKSLASH)),
        listOf(t("Caps", KeyEvent.KEYCODE_CAPS_LOCK, 1.6f)) + letras("ASDFGHJKL") + listOf(t(";", KeyEvent.KEYCODE_SEMICOLON), t("'", KeyEvent.KEYCODE_APOSTROPHE), ENTER),
        listOf(t("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, 1.6f)) + letras("ZXCVBNM") + listOf(t(",", KeyEvent.KEYCODE_COMMA), t(".", KeyEvent.KEYCODE_PERIOD), t("/", KeyEvent.KEYCODE_SLASH), t("↑", KeyEvent.KEYCODE_DPAD_UP), t("Shift", KeyEvent.KEYCODE_SHIFT_RIGHT, 1.3f)),
        listOf(t("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, 1.3f), t("Win", KeyEvent.KEYCODE_META_LEFT), t("Alt", KeyEvent.KEYCODE_ALT_LEFT, 1.2f), t("Espaço", KeyEvent.KEYCODE_SPACE, 5f), t("Alt", KeyEvent.KEYCODE_ALT_RIGHT), t("←", KeyEvent.KEYCODE_DPAD_LEFT), t("↓", KeyEvent.KEYCODE_DPAD_DOWN), t("→", KeyEvent.KEYCODE_DPAD_RIGHT), t("Ctrl", KeyEvent.KEYCODE_CTRL_RIGHT, 1.2f)),
        listOf(t("Ins", KeyEvent.KEYCODE_INSERT), t("Home", KeyEvent.KEYCODE_MOVE_HOME), t("End", KeyEvent.KEYCODE_MOVE_END), t("PgUp", KeyEvent.KEYCODE_PAGE_UP), t("PgDn", KeyEvent.KEYCODE_PAGE_DOWN), t("PrtSc", KeyEvent.KEYCODE_SYSRQ), t("Pause", KeyEvent.KEYCODE_BREAK))
    )

    fun gamerEsquerda(): List<List<Tecla>> = listOf(
        listOf(ESC, t("1", KeyEvent.KEYCODE_1), t("2", KeyEvent.KEYCODE_2), t("3", KeyEvent.KEYCODE_3), t("4", KeyEvent.KEYCODE_4)),
        listOf(t("Tab", KeyEvent.KEYCODE_TAB, 1.2f), t("Q", KeyEvent.KEYCODE_Q), t("W", KeyEvent.KEYCODE_W), t("E", KeyEvent.KEYCODE_E), t("R", KeyEvent.KEYCODE_R)),
        listOf(t("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, 1.4f), t("A", KeyEvent.KEYCODE_A), t("S", KeyEvent.KEYCODE_S), t("D", KeyEvent.KEYCODE_D), t("F", KeyEvent.KEYCODE_F)),
        listOf(t("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, 1.3f), t("Z", KeyEvent.KEYCODE_Z), t("X", KeyEvent.KEYCODE_X), t("C", KeyEvent.KEYCODE_C), t("V", KeyEvent.KEYCODE_V))
    )

    fun gamerDireita(): List<List<Tecla>> = listOf(
        listOf(t("5", KeyEvent.KEYCODE_5), t("6", KeyEvent.KEYCODE_6), t("7", KeyEvent.KEYCODE_7), t("M", KeyEvent.KEYCODE_M)),
        listOf(t("G", KeyEvent.KEYCODE_G), t("T", KeyEvent.KEYCODE_T), t("↑", KeyEvent.KEYCODE_DPAD_UP), t("I", KeyEvent.KEYCODE_I)),
        listOf(t("←", KeyEvent.KEYCODE_DPAD_LEFT), t("↓", KeyEvent.KEYCODE_DPAD_DOWN), t("→", KeyEvent.KEYCODE_DPAD_RIGHT), ENTER),
        listOf(t("Alt", KeyEvent.KEYCODE_ALT_LEFT), t("Espaço", KeyEvent.KEYCODE_SPACE, 3f))
    )

    fun setas(): List<List<Tecla>> = listOf(
        listOf(ESC, t("↑", KeyEvent.KEYCODE_DPAD_UP), ENTER),
        listOf(t("←", KeyEvent.KEYCODE_DPAD_LEFT), t("↓", KeyEvent.KEYCODE_DPAD_DOWN), t("→", KeyEvent.KEYCODE_DPAD_RIGHT)),
        listOf(t("Shift", KeyEvent.KEYCODE_SHIFT_LEFT), t("Espaço", KeyEvent.KEYCODE_SPACE, 2f))
    )

    val nomes = listOf("Só meus botões", "Gamer (WASD + ações)", "Teclado completo", "Setas")
    val ids = listOf("nenhum", "gamer", "completo", "setas")
}

/** Desenha o alvo (ponto de toque) no editor. */
private class Mira(ctx: Context, val cor: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var texto = ""
    override fun onDraw(c: Canvas) {
        val r = width / 2f
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.12f; p.color = cor
        c.drawCircle(r, r, r * 0.8f, p)
        c.drawLine(r, r * 0.1f, r, r * 1.9f, p); c.drawLine(r * 0.1f, r, r * 1.9f, r, p)
        p.style = Paint.Style.FILL; p.textSize = r * 0.55f; p.textAlign = Paint.Align.CENTER; p.color = 0xFFFFFFFF.toInt()
        c.drawText(texto, r, r * 0.62f, p)
    }
}

/** Analogico desenhado. */
private class Analogico(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var kx = 0f
    var ky = 0f
    var texto = ""
    override fun onDraw(c: Canvas) {
        val r = width / 2f
        p.style = Paint.Style.FILL; p.color = 0x990A0E14.toInt(); c.drawCircle(r, r, r * 0.96f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.05f; p.color = Ui.alpha(T.verde, 0xAA); c.drawCircle(r, r, r * 0.94f, p)
        p.style = Paint.Style.FILL; p.color = Ui.alpha(T.verde, 0xDD); c.drawCircle(r + kx * r * 0.55f, r + ky * r * 0.55f, r * 0.38f, p)
        p.color = 0xFF0A0E14.toInt(); p.textSize = r * 0.24f; p.textAlign = Paint.Align.CENTER
        c.drawText(texto, r + kx * r * 0.55f, r + ky * r * 0.55f + r * 0.09f, p)
    }
}

/**
 * Controles na tela: botoes personalizados (tecla, toque, analogico, macro), paineis de teclado,
 * editor estilo MuMu e bolinha com menu. Troca o perfil sozinho conforme o jogo que esta na frente.
 */
class TecladoService : Service() {
    companion object {
        @Volatile var rodando = false
        @Volatile var instancia: TecladoService? = null
        private const val CANAL = "teclado"
        private const val ID = 5050
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val janelas = ArrayList<View>()
    private var bolha: View? = null
    private var menu: View? = null
    private var editor: View? = null
    private var pkg: String = ""
    private var controles: MutableList<Controle> = ArrayList()
    private var layout = "nenhum"
    private var visivel = true
    private var meta = 0
    private var caps = false
    private val segurando = HashMap<Int, Long>()
    private val alternados = HashSet<Long>()
    private var vigiando = false
    private var ultimaConsulta = System.currentTimeMillis()
    private var fgAtual: String? = null
    private val fisicas = HashSet<Int>()
    private var joyFisico = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager; instancia = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Teclado no jogo", NotificationManager.IMPORTANCE_LOW))
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, notif())
        } catch (e: Exception) { Log2.registrar("teclado fgs: $e"); if (!rodando) stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            "fechar" -> { fechar(); return START_NOT_STICKY }
            "alternar" -> {
                if (!rodando) iniciarBase(null)
                ui.post { if (visivel && (janelas.isNotEmpty() || controles.isEmpty())) esconder() else { rem(bolha); bolha = null; mostrarTudo() } }
                return START_STICKY
            }
            "editar" -> { if (!rodando) iniciarBase(null); ui.post { abrirEditor() }; return START_STICKY }
        }
        iniciarBase(intent)
        if (intent?.getBooleanExtra("abrir", false) == true && pkg.isNotEmpty()) {
            val abrir = packageManager.getLaunchIntentForPackage(pkg)
            if (abrir != null) { abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { startActivity(abrir) } catch (e: Exception) { } }
        }
        if (intent?.getBooleanExtra("editar", false) == true) ui.postDelayed({ abrirEditor() }, 2500)
        return START_STICKY
    }

    /** Carrega perfil e mostra tudo. Sempre recria as janelas (resolve abrir de novo com o jogo aberto). */
    private fun iniciarBase(intent: Intent?) {
        rodando = true
        val novoPkg = intent?.getStringExtra("pkg") ?: Store.str("tec_pkg")
        intent?.getStringExtra("layout")?.let { layout = it; Store.putStr("tec_layout", it) } ?: run { layout = Store.str("tec_layout", "nenhum") }
        if (novoPkg.isNotEmpty()) { pkg = novoPkg; Store.putStr("tec_pkg", pkg) }
        controles = PerfisControle.carregar(pkg)
        Thread { Injetor.preparar() }.start()
        ui.post { removerTudo(); visivel = true; mostrarTudo() }
        if (!vigiando) { vigiando = true; ui.postDelayed(vigia, 1500) }
        Mapeador.iniciar { ev -> ui.post { try { teclaFisica(ev) } catch (e: Exception) { } } }
    }

    private fun notif(): Notification {
        val alt = PendingIntent.getService(this, 11, Intent(this, TecladoService::class.java).setAction("alternar"), PendingIntent.FLAG_IMMUTABLE)
        val edt = PendingIntent.getService(this, 13, Intent(this, TecladoService::class.java).setAction("editar"), PendingIntent.FLAG_IMMUTABLE)
        val fec = PendingIntent.getService(this, 12, Intent(this, TecladoService::class.java).setAction("fechar"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CANAL).setSmallIcon(R.drawable.ic_jogo).setContentTitle("Controles no jogo ativos")
            .setContentText("Toque na bolinha ⌨ para o menu").setColor(T.verde).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Mostrar/Esconder", alt).build())
            .addAction(Notification.Action.Builder(null, "Editar", edt).build())
            .addAction(Notification.Action.Builder(null, "Fechar", fec).build()).build()
    }

    // ---------------- troca de perfil conforme o jogo na frente ----------------
    private val vigia = object : Runnable {
        override fun run() {
            if (!rodando) { vigiando = false; return }
            val fg = appNaFrente()
            if (fg != null && editor == null) {
                val ignorar = fg == packageName || fg.contains("systemui") || fg.contains("inputmethod") || fg.contains("permissioncontroller") || fg.contains("gms")
                val inicio = fg.contains("launcher") || fg.contains("home")
                if (!ignorar && !inicio && fg != pkg && (PerfisControle.tem(fg) || Tuner.ehJogo(this@TecladoService, fg))) trocarPerfil(fg)
                if (!ignorar) {
                    if (fg != pkg && janelas.isNotEmpty()) esconderControles()
                    else if (fg == pkg && janelas.isEmpty() && visivel) mostrarControles()
                }
            }
            ui.postDelayed(this, 1200)
        }
    }

    private fun appNaFrente(): String? {
        if (!Acesso.uso()) return null
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val agora = System.currentTimeMillis()
        val ev = try { usm.queryEvents(ultimaConsulta - 2000, agora) } catch (e: Exception) { return fgAtual }
        ultimaConsulta = agora
        val e = UsageEvents.Event()
        @Suppress("DEPRECATION")
        val tipo = if (Build.VERSION.SDK_INT >= 29) UsageEvents.Event.ACTIVITY_RESUMED else UsageEvents.Event.MOVE_TO_FOREGROUND
        while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == tipo) fgAtual = e.packageName }
        return fgAtual
    }

    private fun trocarPerfil(novo: String) {
        soltarTudo()
        pkg = novo; Store.putStr("tec_pkg", pkg)
        controles = PerfisControle.carregar(pkg)
        removerControles()
        if (visivel) mostrarControles()
    }

    // ---------------- janelas ----------------
    private fun lp(x: Int, y: Int, w: Int, h: Int, focavel: Boolean = false, tocavelFora: Boolean = true): WindowManager.LayoutParams {
        var f = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!focavel) f = f or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (tocavelFora) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val p = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, f, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.START
        p.x = x; p.y = y
        if (Build.VERSION.SDK_INT >= 28) p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        return p
    }

    private fun add(v: View, p: WindowManager.LayoutParams): Boolean = try { wm.addView(v, p); true } catch (e: Exception) { Log2.registrar("overlay: $e"); false }
    private fun rem(v: View?) { if (v != null) try { wm.removeView(v) } catch (e: Exception) { } }

    private fun mostrarTudo() { visivel = true; mostrarControles(); mostrarBolha() }
    private fun esconder() { visivel = false; esconderControles(); rem(bolha); bolha = null; mostrarBolha() }
    private fun esconderControles() { soltarTudo(); removerControles() }
    private fun removerControles() { for (v in janelas) rem(v); janelas.clear() }
    private fun removerTudo() { soltarTudo(); removerControles(); rem(bolha); bolha = null; rem(menu); menu = null; rem(editor); editor = null }

    private fun mostrarControles() {
        removerControles()
        val (w, h) = Tela.tamanho(this)
        for (c in controles) {
            val v = viewJogo(c, w, h)
            val tamPx = Ui.dp(this, if (c.tipo == "analogico") c.tam * 2 else c.tam)
            if (add(v, lp((c.x * w).toInt() - tamPx / 2, (c.y * h).toInt() - tamPx / 2, tamPx, tamPx))) janelas.add(v)
        }
        when (layout) {
            "completo" -> painelTeclado("completo", Layouts.completo(), true)
            "setas" -> painelTeclado("setas", Layouts.setas(), false)
            "gamer" -> { painelTeclado("esq", Layouts.gamerEsquerda(), false); painelTeclado("dir", Layouts.gamerDireita(), false) }
        }
        if (bolha != null) { rem(bolha); bolha = null; mostrarBolha() }
    }

    // ---------------- bolinha e menu ----------------
    private fun mostrarBolha() {
        if (bolha != null) return
        val b = Ui.texto(this, "⌨", 21f, if (visivel) T.verde else T.texto3, Ui.negrito)
        b.gravity = Gravity.CENTER
        b.background = Ui.forma(this, 0xE60A0E14.toInt(), 50, Ui.alpha(T.verde, if (visivel) 0x99 else 0x44))
        val tam = Ui.dp(this, 46)
        val p = lp(Store.int("tec_bx", Ui.dp(this, 6)), Store.int("tec_by", Ui.dp(this, 140)), tam, tam)
        arrastar(b, b, p, salvar = { Store.putInt("tec_bx", p.x); Store.putInt("tec_by", p.y) }, toque = { abrirMenu(p) })
        if (add(b, p)) bolha = b
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun abrirMenu(pb: WindowManager.LayoutParams) {
        if (menu != null) { rem(menu); menu = null; return }
        val col = Ui.vertical(this)
        col.background = Ui.forma(this, 0xF20A0E14.toInt(), 16, Ui.alpha(T.verde, 0x66))
        col.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6))
        val nomeJogo = if (pkg.isEmpty()) "Nenhum jogo" else Jogos.nome(this, pkg)
        val tt = Ui.texto(this, nomeJogo + " · " + controles.size + " botões", 11.5f, T.texto3, Ui.medio)
        tt.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 6))
        col.addView(tt)
        fun item(t: String, cor: Int = T.texto, acao: () -> Unit) {
            val v = Ui.texto(this, t, 14f, cor, Ui.medio)
            v.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 10))
            v.background = Ui.clicavel(this, Ui.forma(this, 0x00000000, 10), 10)
            v.setOnClickListener { rem(menu); menu = null; acao() }
            col.addView(v)
        }
        item(if (visivel) "Esconder controles" else "Mostrar controles") { if (visivel) esconder() else { rem(bolha); bolha = null; mostrarTudo() } }
        item("✎  Editar botões (estilo MuMu)", T.verde) { abrirEditor() }
        item("⌨  Teclado: " + Layouts.nomes[Layouts.ids.indexOf(layout).coerceAtLeast(0)]) {
            val i = (Layouts.ids.indexOf(layout) + 1) % Layouts.ids.size
            layout = Layouts.ids[i]; Store.putStr("tec_layout", layout); visivel = true; mostrarControles()
        }
        item("◐  Transparência") { val o = Store.str("tec_opac", "0.85").toFloatOrNull() ?: 0.85f; Store.putStr("tec_opac", (if (o <= 0.36f) 1.0f else o - 0.15f).toString()); mostrarControles() }
        item("↻  Usar o jogo que está na tela") {
            Thread { val fg = Tela.appNaFrente(); ui.post { if (fg != null && fg != packageName) trocarPerfil(fg) } }.start()
        }
        item("✕  Fechar controles", T.vermelho) { fechar() }
        val (w, _) = Tela.tamanho(this)
        val mx = if (pb.x > w / 2) pb.x - Ui.dp(this, 260) else pb.x + Ui.dp(this, 52)
        val p = lp(mx.coerceAtLeast(0), pb.y, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        col.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) { rem(menu); menu = null }; false }
        if (add(col, p)) menu = col
    }

    private fun fechar() {
        ui.post {
            removerTudo()
            Mapeador.parar()
            rodando = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() { removerTudo(); Mapeador.parar(); rodando = false; instancia = null; super.onDestroy() }

    // ---------------- botoes no modo jogar ----------------
    private fun opac(c: Controle) = (c.opac * (Store.str("tec_opac", "0.85").toFloatOrNull() ?: 0.85f) / 0.85f).coerceIn(0.15f, 1f)

    @SuppressLint("ClickableViewAccessibility")
    private fun viewJogo(c: Controle, w: Int, h: Int): View {
        val tx = c.tx * w
        val ty = c.ty * h
        if (c.tipo == "analogico") {
            val a = Analogico(this)
            a.texto = c.texto(); a.alpha = opac(c)
            val ativos = HashSet<Int>()
            val cima = if (c.analog == "setas") KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_W
            val baixo = if (c.analog == "setas") KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_S
            val esq = if (c.analog == "setas") KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_A
            val dir = if (c.analog == "setas") KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_D
            a.setOnTouchListener { v, e ->
                val r = v.width / 2f
                var dx = (e.x - r) / r
                var dy = (e.y - r) / r
                val m = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (m > 1f) { dx /= m; dy /= m }
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        if (e.actionMasked == MotionEvent.ACTION_DOWN) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        a.kx = dx; a.ky = dy; a.invalidate()
                        if (c.analog == "toque") {
                            val px = tx + dx * c.raio * w
                            val py = ty + dy * c.raio * w
                            if (e.actionMasked == MotionEvent.ACTION_DOWN) Toque.descer(c.id, tx, ty)
                            Toque.mover(c.id, px, py)
                        } else {
                            val novas = HashSet<Int>()
                            if (dy < -0.38f) novas.add(cima)
                            if (dy > 0.38f) novas.add(baixo)
                            if (dx < -0.38f) novas.add(esq)
                            if (dx > 0.38f) novas.add(dir)
                            val agora = SystemClock.uptimeMillis()
                            for (k in ativos.filter { it !in novas }) Injetor.tecla(k, false, meta, segurando.remove(k) ?: agora)
                            for (k in novas.filter { it !in ativos }) { segurando[k] = agora; Injetor.tecla(k, true, meta, agora) }
                            ativos.clear(); ativos.addAll(novas)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        a.kx = 0f; a.ky = 0f; a.invalidate()
                        if (c.analog == "toque") Toque.subir(c.id)
                        else { val agora = SystemClock.uptimeMillis(); for (k in ativos) Injetor.tecla(k, false, meta, segurando.remove(k) ?: agora); ativos.clear() }
                    }
                }
                true
            }
            return a
        }
        val v = TextView(this)
        v.text = c.texto()
        v.gravity = Gravity.CENTER
        v.setTextColor(T.texto)
        v.textSize = (if (v.text.length > 3) 11f else 15f) * (c.tam / 56f).coerceIn(0.8f, 1.5f)
        v.typeface = Ui.negrito
        val cor = when (c.tipo) { "toque" -> T.azul; "macro" -> T.roxo; else -> T.verde }
        val normal = Ui.forma(this, 0xB30A0E14.toInt(), 50, Ui.alpha(cor, 0xCC), 2)
        val ativo = Ui.forma(this, Ui.alpha(cor, 0xCC), 50, cor, 2)
        v.background = normal
        v.alpha = opac(c)
        val turbo = object : Runnable {
            override fun run() {
                if (c.tipo == "toque") Toque.tocar(c.id, tx, ty) else if (c.tipo == "macro") executarMacro(c) else Injetor.clique(c.tecla, meta)
                ui.postDelayed(this, c.turboMs.toLong().coerceAtLeast(30))
            }
        }
        fun apertar() {
            val agora = SystemClock.uptimeMillis()
            when (c.tipo) {
                "toque" -> Toque.descer(c.id, tx, ty)
                "macro" -> executarMacro(c)
                else -> {
                    if (ehModificador(c.tecla)) meta = meta or metaDe(c.tecla)
                    segurando[c.tecla] = agora
                    Injetor.tecla(c.tecla, true, meta, agora)
                }
            }
        }
        fun soltar() {
            when (c.tipo) {
                "toque" -> Toque.subir(c.id)
                "macro" -> {}
                else -> {
                    val d = segurando.remove(c.tecla) ?: SystemClock.uptimeMillis()
                    if (ehModificador(c.tecla)) meta = meta and metaDe(c.tecla).inv()
                    Injetor.tecla(c.tecla, false, meta, d)
                }
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    when (c.modo) {
                        "turbo" -> { v.background = ativo; ui.post(turbo) }
                        "alternar" -> { if (alternados.remove(c.id)) { soltar(); v.background = normal } else { alternados.add(c.id); apertar(); v.background = ativo } }
                        else -> { v.background = ativo; apertar() }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    when (c.modo) {
                        "turbo" -> { ui.removeCallbacks(turbo); v.background = normal }
                        "alternar" -> {}
                        else -> { v.background = normal; soltar() }
                    }
                }
            }
            true
        }
        return v
    }

    /** Macro: "Q 100 E 100 R", "Shift+W 500". */
    private fun executarMacro(c: Controle) {
        val passos = c.macro.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
        Thread {
            for (p in passos) {
                val ms = p.toIntOrNull()
                if (ms != null) { Thread.sleep(ms.toLong().coerceIn(0, 10_000)); continue }
                val partes = p.split('+')
                var m = 0
                val mods = partes.dropLast(1).map { Teclas.codigo(it) }.filter { it > 0 }
                val d = SystemClock.uptimeMillis()
                for (k in mods) { m = m or metaDe(k); Injetor.tecla(k, true, m, d) }
                val k = Teclas.codigo(partes.last())
                if (k > 0) Injetor.clique(k, m)
                for (mk in mods.reversed()) { m = m and metaDe(mk).inv(); Injetor.tecla(mk, false, m, d) }
                Thread.sleep(40)
            }
        }.start()
    }

    // ---------------- paineis de teclado prontos ----------------
    private fun painelTeclado(nome: String, linhas: List<List<Tecla>>, larguraTotal: Boolean) {
        val (w, h) = Tela.tamanho(this)
        val esc = Store.str("tec_escala", "1.0").toFloatOrNull() ?: 1f
        val alt = (Ui.dp(this, if (larguraTotal) 36 else 46) * esc).toInt()
        val larg = (Ui.dp(this, 46) * esc).toInt()
        val raiz = Ui.vertical(this)
        raiz.background = Ui.forma(this, 0xD90A0E14.toInt(), 16, Ui.alpha(T.verde, 0x55))
        raiz.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 5))
        raiz.isMotionEventSplittingEnabled = true
        raiz.alpha = Store.str("tec_opac", "0.85").toFloatOrNull() ?: 0.85f
        val barra = Ui.horizontal(this)
        val alca = Ui.texto(this, "⠿ mover", 10.5f, T.texto3, Ui.medio)
        alca.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 10), Ui.dp(this, 3))
        barra.addView(alca)
        barra.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val tm = Ui.texto(this, "A±", 11.5f, T.texto2, Ui.medio)
        tm.setPadding(Ui.dp(this, 9), Ui.dp(this, 3), Ui.dp(this, 9), Ui.dp(this, 3))
        tm.setOnClickListener { Store.putStr("tec_escala", (if (esc >= 1.29f) 0.8f else esc + 0.1f).toString()); mostrarControles() }
        barra.addView(tm)
        raiz.addView(barra)
        for (linha in linhas) {
            val l = LinearLayout(this)
            l.orientation = LinearLayout.HORIZONTAL
            l.isMotionEventSplittingEnabled = true
            for (t in linha) {
                val v = teclaView(t)
                val p = if (larguraTotal) LinearLayout.LayoutParams(0, alt, t.peso) else LinearLayout.LayoutParams((larg * t.peso).toInt(), alt)
                p.setMargins(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2))
                l.addView(v, p)
            }
            raiz.addView(l, LinearLayout.LayoutParams(if (larguraTotal) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val padraoX = if (nome == "dir") w - Ui.dp(this, 230) else Ui.dp(this, 10)
        val p = lp(if (larguraTotal) 0 else Store.int("tec_x_$nome", padraoX), Store.int("tec_y_$nome", h / 2),
            if (larguraTotal) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        arrastar(alca, raiz, p, soY = larguraTotal, salvar = { if (!larguraTotal) Store.putInt("tec_x_$nome", p.x); Store.putInt("tec_y_$nome", p.y) })
        if (add(raiz, p)) janelas.add(raiz)
    }

    private fun ehModificador(c: Int) = metaDe(c) != 0

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
        v.textSize = if (t.rotulo.length > 3) 10.5f else 13.5f
        v.typeface = Ui.medio
        val normal = Ui.forma(this, 0xFF1A222D.toInt(), 9, T.outline)
        val apertada = Ui.forma(this, Ui.alpha(T.verde, 0xCC), 9, T.verde)
        v.background = normal
        val repetir = object : Runnable {
            var n = 0
            override fun run() { val d = segurando[t.codigo] ?: return; n++; Injetor.tecla(t.codigo, true, metaAtual(), d, n); ui.postDelayed(this, 45) }
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
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(repetir)
                    v.background = normal
                    val d = segurando.remove(t.codigo) ?: SystemClock.uptimeMillis()
                    if (ehModificador(t.codigo)) meta = meta and metaDe(t.codigo).inv()
                    Injetor.tecla(t.codigo, false, metaAtual(), d)
                }
            }
            true
        }
        return v
    }

    /** Solta tudo (nenhuma tecla ou toque fica preso no jogo). */
    private fun soltarTudo() {
        for ((c, d) in HashMap(segurando)) Injetor.tecla(c, false, 0, d)
        segurando.clear(); meta = 0; alternados.clear(); fisicas.clear(); joyFisico = false
        Toque.soltarTudo()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun arrastar(alca: View, janela: View, p: WindowManager.LayoutParams, soY: Boolean = false, salvar: () -> Unit = {}, toque: (() -> Unit)? = null) {
        var ix = 0f
        var iy = 0f
        var ox = 0
        var oy = 0
        var moveu = false
        alca.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { ix = e.rawX; iy = e.rawY; ox = p.x; oy = p.y; moveu = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - ix).toInt()
                    val dy = (e.rawY - iy).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moveu = true
                    if (moveu) { if (!soY) p.x = ox + dx; p.y = oy + dy; try { wm.updateViewLayout(janela, p) } catch (ex: Exception) { } }
                    true
                }
                MotionEvent.ACTION_UP -> { if (moveu) salvar() else toque?.invoke(); true }
                else -> false
            }
        }
    }

    // ================= EDITOR (estilo MuMu) =================
    private fun dialogo(): AlertDialog.Builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.TemaDialogo))

    private fun mostrarDialogo(d: AlertDialog) {
        d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        try { d.show() } catch (e: Exception) { Log2.registrar("dialogo: $e") }
    }

    fun abrirEditor() {
        if (editor != null) return
        soltarTudo()
        rem(menu); menu = null
        removerControles()
        rem(bolha); bolha = null
        val fg = fgAtual
        if (pkg.isEmpty() && fg != null && fg != packageName) { pkg = fg; Store.putStr("tec_pkg", pkg); controles = PerfisControle.carregar(pkg) }
        val (w, h) = Tela.tamanho(this)
        val raiz = FrameLayout(this)
        raiz.setBackgroundColor(0x55000000)
        val copia = controles.map { Controle.de(it.json()) }.toMutableList()
        lateinit var redesenhar: () -> Unit
        redesenhar = {
            raiz.removeAllViews()
            for (c in copia) {
                val tam = Ui.dp(this, if (c.tipo == "analogico") c.tam * 2 else c.tam)
                if (c.tipo == "toque" || (c.tipo == "analogico" && c.analog == "toque")) {
                    val m = Mira(this, if (c.tipo == "toque") T.azul else T.laranja)
                    m.texto = c.texto()
                    val mt = Ui.dp(this, if (c.tipo == "toque") 44 else 56)
                    val lpM = FrameLayout.LayoutParams(mt, mt)
                    lpM.leftMargin = (c.tx * w).toInt() - mt / 2; lpM.topMargin = (c.ty * h).toInt() - mt / 2
                    raiz.addView(m, lpM)
                    arrastarNoEditor(m, lpM, mt, null) { cx, cy -> c.tx = cx / w; c.ty = cy / h }
                }
                val v: View = if (c.tipo == "analogico") Analogico(this).also { it.texto = c.texto() } else {
                    val t = TextView(this)
                    t.text = c.texto(); t.gravity = Gravity.CENTER; t.setTextColor(T.texto); t.typeface = Ui.negrito
                    t.textSize = if (t.text.length > 3) 11f else 15f
                    val cor = when (c.tipo) { "toque" -> T.azul; "macro" -> T.roxo; else -> T.verde }
                    t.background = Ui.forma(this, 0xCC0A0E14.toInt(), 50, cor, 2)
                    t
                }
                val lpV = FrameLayout.LayoutParams(tam, tam)
                lpV.leftMargin = (c.x * w).toInt() - tam / 2; lpV.topMargin = (c.y * h).toInt() - tam / 2
                raiz.addView(v, lpV)
                arrastarNoEditor(v, lpV, tam, { editarControle(c, copia, false, redesenhar) }) { cx, cy -> c.x = cx / w; c.y = cy / h }
            }
            val barra = Ui.horizontal(this)
            barra.background = Ui.forma(this, 0xF20A0E14.toInt(), 50, Ui.alpha(T.verde, 0x88))
            barra.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4))
            fun bt(t: String, cor: Int, acao: () -> Unit) {
                val b = Ui.texto(this, t, 12.5f, cor, Ui.medio)
                b.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8))
                b.setOnClickListener { acao() }
                barra.addView(b)
            }
            bt("+ Tecla", T.verde) { novo(copia, "tecla", redesenhar) }
            bt("+ Toque", T.azul) { novo(copia, "toque", redesenhar) }
            bt("+ Analógico", T.laranja) { novo(copia, "analogico", redesenhar) }
            bt("+ Macro", T.roxo) { novo(copia, "macro", redesenhar) }
            bt("Mais", T.texto2) { maisOpcoes(copia, redesenhar) }
            bt("Salvar", T.verde) { controles = copia; PerfisControle.salvar(pkg, copia); fecharEditor() }
            bt("✕", T.vermelho) { fecharEditor() }
            val hsv = HorizontalScrollView(this)
            hsv.isHorizontalScrollBarEnabled = false
            hsv.addView(barra)
            val lpB = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            lpB.topMargin = Ui.dp(this, 24)
            raiz.addView(hsv, lpB)
            val dica = Ui.texto(this, (if (pkg.isEmpty()) "" else Jogos.nome(this, pkg) + " · ") + "Arraste para posicionar · toque no botão para editar · a mira ◎ é onde o toque acontece no jogo", 11.5f, T.texto2)
            dica.setBackgroundColor(0xAA0A0E14.toInt())
            dica.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4))
            raiz.addView(dica, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
        redesenhar()
        val p = lp(0, 0, w, h, focavel = false, tocavelFora = false)
        if (add(raiz, p)) editor = raiz
    }

    private fun fecharEditor() {
        rem(editor); editor = null
        mostrarTudo()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun arrastarNoEditor(v: View, lp: FrameLayout.LayoutParams, tam: Int, toque: (() -> Unit)?, aoMover: (Float, Float) -> Unit) {
        var ix = 0f
        var iy = 0f
        var ox = 0
        var oy = 0
        var moveu = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { ix = e.rawX; iy = e.rawY; ox = lp.leftMargin; oy = lp.topMargin; moveu = false; v.bringToFront() }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - ix).toInt()
                    val dy = (e.rawY - iy).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moveu = true
                    if (moveu) { lp.leftMargin = ox + dx; lp.topMargin = oy + dy; v.layoutParams = lp }
                }
                MotionEvent.ACTION_UP -> {
                    if (moveu) aoMover((lp.leftMargin + tam / 2).toFloat(), (lp.topMargin + tam / 2).toFloat())
                    else toque?.invoke()
                }
            }
            true
        }
    }

    private fun novo(lista: MutableList<Controle>, tipo: String, depois: () -> Unit) {
        val c = Controle(tipo = tipo, x = 0.5f + (lista.size % 5) * 0.04f, y = 0.45f + (lista.size % 4) * 0.05f)
        when (tipo) {
            "analogico" -> { c.x = 0.18f; c.y = 0.66f; c.tam = 60; c.tx = 0.18f; c.ty = 0.7f; c.analog = "toque" }
            "toque" -> { c.tx = (c.x - 0.12f).coerceAtLeast(0.05f); c.ty = c.y }
        }
        if (tipo == "analogico" || tipo == "macro") { lista.add(c); editarControle(c, lista, true, depois); return }
        // pede a letra direto, como no MuMu
        pedirTecla(if (tipo == "toque") "Tecla deste toque (aparece no botão)" else "Qual tecla este botão aperta?") { k, texto ->
            c.tecla = k
            if (k == 0) c.rotulo = texto.take(6)
            lista.add(c); depois()
        }
    }

    private fun pedirTecla(titulo: String, ok: (Int, String) -> Unit) {
        val box = Ui.vertical(this)
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0)
        val et = EditText(ContextThemeWrapper(this, R.style.TemaDialogo))
        et.hint = "Digite: W, Espaço, Shift, F1, 1, ↑…"
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        et.setTextColor(T.texto); et.setHintTextColor(T.texto3)
        box.addView(et)
        val chips = HorizontalScrollView(this)
        val linha = Ui.horizontal(this)
        for (n in Teclas.comuns) {
            val ch = Ui.chip(this, n, T.verde)
            ch.setOnClickListener { et.setText(n); et.setSelection(n.length) }
            val lpc = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); lpc.rightMargin = Ui.dp(this, 6)
            linha.addView(ch, lpc)
        }
        chips.addView(linha)
        box.addView(chips, Ui.linhaLp(this, 8, 4))
        val d = dialogo().setTitle(titulo).setView(box)
            .setPositiveButton("OK") { _, _ -> val txt = et.text.toString().trim(); ok(Teclas.codigo(txt), txt) }
            .setNegativeButton("Cancelar", null).create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        mostrarDialogo(d)
    }

    private fun editarControle(c: Controle, lista: MutableList<Controle>, novo: Boolean, depois: () -> Unit) {
        val box = Ui.vertical(this)
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 6))
        fun rotulo(t: String) { val v = Ui.texto(this, t, 12f, T.texto2, Ui.medio); v.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4)); box.addView(v) }
        fun info(t: String) { box.addView(Ui.texto(this, t, 11.5f, T.texto3)) }
        fun seletor(ops: List<String>, sel: Int, onSel: (Int) -> Unit) {
            val hs = HorizontalScrollView(this)
            val l = Ui.horizontal(this)
            val vistas = ArrayList<TextView>()
            ops.forEachIndexed { i, o ->
                val v = Ui.texto(this, o, 12.5f, if (i == sel) T.bg else T.texto, Ui.medio)
                v.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7))
                v.background = if (i == sel) Ui.forma(this, T.verde, 50) else Ui.forma(this, T.surface2, 50, T.outline)
                v.setOnClickListener {
                    vistas.forEachIndexed { k, x -> x.background = if (k == i) Ui.forma(this, T.verde, 50) else Ui.forma(this, T.surface2, 50, T.outline); x.setTextColor(if (k == i) T.bg else T.texto) }
                    onSel(i)
                }
                vistas.add(v)
                val lpv = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); lpv.rightMargin = Ui.dp(this, 6)
                l.addView(v, lpv)
            }
            hs.addView(l); box.addView(hs)
        }
        fun campo(dica: String, valor: String, numero: Boolean = false): EditText {
            val e = EditText(ContextThemeWrapper(this, R.style.TemaDialogo))
            e.hint = dica; e.setText(valor); e.setTextColor(T.texto); e.setHintTextColor(T.texto3)
            e.inputType = if (numero) InputType.TYPE_CLASS_NUMBER else (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            box.addView(e); return e
        }
        var etTecla: EditText? = null
        var etMacro: EditText? = null
        var etTurbo: EditText? = null
        when (c.tipo) {
            "analogico" -> {
                rotulo("O analógico envia")
                val ops = listOf("toque", "wasd", "setas")
                seletor(listOf("Arrastar no jogo", "W A S D", "Setas"), ops.indexOf(c.analog).coerceAtLeast(0)) { c.analog = ops[it] }
                info("“Arrastar no jogo” move o analógico do próprio jogo: coloque a mira laranja no centro dele. Funciona em qualquer jogo. Com teclado físico, W A S D também move.")
                rotulo("Alcance do arraste")
                val rs = listOf(0.05f, 0.08f, 0.11f, 0.14f)
                seletor(listOf("Curto", "Médio", "Longo", "Máximo"), rs.indexOfFirst { Math.abs(it - c.raio) < 0.01f }.let { if (it < 0) 1 else it }) { c.raio = rs[it] }
            }
            "macro" -> {
                rotulo("Sequência (teclas e esperas em milissegundos)")
                etMacro = campo("Ex.: Q 100 E 100 R  ·  Shift+W 500", c.macro)
                info("Cada nome é uma tecla apertada e solta; números são esperas. Use + para combinar (Ctrl+C).")
                rotulo("Ao apertar")
                val ms = listOf("segurar", "turbo")
                seletor(listOf("Roda uma vez", "Repete enquanto segura"), if (c.modo == "turbo") 1 else 0) { c.modo = ms[it] }
                rotulo("Intervalo entre repetições (ms)")
                etTurbo = campo("300", c.turboMs.toString(), true)
            }
            else -> {
                rotulo(if (c.tipo == "toque") "Tecla (aparece no botão e funciona no teclado/controle físico)" else "Tecla")
                etTecla = campo("Ex.: W, Espaço, F1, Botão A", if (c.tecla > 0) Teclas.nome(c.tecla) else "")
                if (c.tipo == "toque") info("Tocar neste botão (ou apertar a tecla num teclado/controle físico) faz o jogo receber um toque na mira azul. Funciona em qualquer jogo, inclusive os que não aceitam teclado.")
                rotulo("Ao apertar")
                val ms = listOf("segurar", "alternar", "turbo")
                seletor(listOf("Segurar", "Liga/desliga", "Turbo (repete)"), ms.indexOf(c.modo).coerceAtLeast(0)) { c.modo = ms[it] }
                rotulo("Intervalo do turbo (ms)")
                etTurbo = campo("80", c.turboMs.toString(), true)
            }
        }
        rotulo("Texto no botão (opcional)")
        val etRotulo = campo("Ex.: Pular, Atirar", c.rotulo)
        rotulo("Tamanho")
        val tams = listOf(44, 56, 68, 84)
        seletor(listOf("P", "M", "G", "GG"), tams.indexOf(c.tam).let { if (it < 0) 1 else it }) { c.tam = tams[it] }
        rotulo("Visibilidade")
        val ops = listOf(0.35f, 0.55f, 0.75f, 0.95f)
        seletor(listOf("25%", "50%", "75%", "100%"), ops.indexOfFirst { Math.abs(it - c.opac) < 0.06f }.let { if (it < 0) 2 else it }) { c.opac = ops[it] }
        val sv = ScrollView(this)
        sv.addView(box)
        val d = dialogo().setTitle(when (c.tipo) { "toque" -> "Botão de toque"; "analogico" -> "Analógico"; "macro" -> "Macro"; else -> "Botão de tecla" })
            .setView(sv)
            .setPositiveButton("OK") { _, _ ->
                etTecla?.let { c.tecla = Teclas.codigo(it.text.toString()) }
                etMacro?.let { c.macro = it.text.toString().trim() }
                etTurbo?.let { c.turboMs = it.text.toString().toIntOrNull()?.coerceIn(30, 5000) ?: 80 }
                c.rotulo = etRotulo.text.toString().trim().take(10)
                depois()
            }
            .setNeutralButton("Excluir") { _, _ -> lista.remove(c); depois() }
            .setNegativeButton(if (novo) "Cancelar" else "Duplicar") { _, _ ->
                if (novo) lista.remove(c) else { val n = Controle.de(c.json()); n.id = System.nanoTime(); n.x = (c.x + 0.06f).coerceAtMost(0.95f); n.tx = (c.tx + 0.06f).coerceAtMost(0.95f); lista.add(n) }
                depois()
            }.create()
        mostrarDialogo(d)
    }

    private fun maisOpcoes(lista: MutableList<Controle>, depois: () -> Unit) {
        val ops = arrayOf("Copiar botões de outro jogo", "Apagar todos os botões", "Teclado/controle físico (estilo MuMu)", "Ajuda")
        mostrarDialogo(dialogo().setItems(ops) { _, i ->
            when (i) {
                0 -> {
                    val outros = Jogos.todosApps(this).filter { it.pkg != pkg && PerfisControle.tem(it.pkg) }
                    if (outros.isEmpty()) mostrarDialogo(dialogo().setMessage("Nenhum outro jogo tem botões salvos ainda.").setPositiveButton("OK", null).create())
                    else mostrarDialogo(dialogo().setTitle("Copiar de").setItems(outros.map { it.nome }.toTypedArray()) { _, k ->
                        lista.clear(); lista.addAll(PerfisControle.carregar(outros[k].pkg).onEach { it.id = System.nanoTime() + (it.id % 100000) }); depois()
                    }.create())
                }
                1 -> mostrarDialogo(dialogo().setMessage("Apagar todos os botões deste jogo?").setPositiveButton("Apagar") { _, _ -> lista.clear(); depois() }.setNegativeButton("Cancelar", null).create())
                2 -> mostrarDialogo(dialogo().setTitle("Teclado/controle físico").setMessage(
                    "Com um teclado ou controle Bluetooth/USB, cada tecla colocada num botão de TOQUE vira um toque na mira azul, e o analógico “Arrastar no jogo” anda com W A S D ou setas, igual ao MuMu Player.\n\nStatus: " +
                        (if (Mapeador.ativo()) "ATIVO ✓ (lido pelo Shizuku, sem acessibilidade)" else "desligado. Ative em PC Toolkit > Teclado e controles.")
                ).setPositiveButton("OK", null).create())
                3 -> mostrarDialogo(dialogo().setTitle("Como funciona").setMessage(
                    "• Tecla: o jogo recebe a tecla como de um teclado de verdade (Roblox, Minecraft, emuladores).\n\n" +
                        "• Toque: o jogo recebe um toque na mira azul. Funciona em QUALQUER jogo (Free Fire, PUBG…): crie botões em lugares mais confortáveis, com turbo ou liga/desliga.\n\n" +
                        "• Analógico: arrasta o analógico do jogo, ou envia W A S D / setas.\n\n" +
                        "• Macro: sequência de teclas com esperas.\n\n" +
                        "Os botões ficam salvos por jogo e trocam sozinhos quando você muda de jogo."
                ).setPositiveButton("OK", null).create())
            }
        }.create())
    }

    /** Chamado pelo servico de acessibilidade quando uma tecla fisica e apertada. Devolve true se usou. */
    fun teclaFisica(e: KeyEvent): Boolean {
        if (editor != null || !visivel || controles.isEmpty()) return false
        if (fgAtual != null && fgAtual != pkg) return false
        val (w, h) = Tela.tamanho(this)
        val alvo = controles.firstOrNull { it.tipo == "toque" && it.tecla == e.keyCode }
        if (alvo != null) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) Toque.descer(alvo.id, alvo.tx * w, alvo.ty * h)
            else if (e.action == KeyEvent.ACTION_UP) Toque.subir(alvo.id)
            return true
        }
        val joy = controles.firstOrNull { it.tipo == "analogico" && it.analog == "toque" } ?: return false
        val dirs = mapOf(KeyEvent.KEYCODE_W to Pair(0f, -1f), KeyEvent.KEYCODE_S to Pair(0f, 1f), KeyEvent.KEYCODE_A to Pair(-1f, 0f), KeyEvent.KEYCODE_D to Pair(1f, 0f),
            KeyEvent.KEYCODE_DPAD_UP to Pair(0f, -1f), KeyEvent.KEYCODE_DPAD_DOWN to Pair(0f, 1f), KeyEvent.KEYCODE_DPAD_LEFT to Pair(-1f, 0f), KeyEvent.KEYCODE_DPAD_RIGHT to Pair(1f, 0f))
        val d0 = dirs[e.keyCode] ?: return false
        if (e.action == KeyEvent.ACTION_DOWN) fisicas.add(e.keyCode) else if (e.action == KeyEvent.ACTION_UP) fisicas.remove(e.keyCode)
        var dx = 0f
        var dy = 0f
        for (k in fisicas) { val d = dirs[k] ?: continue; dx += d.first; dy += d.second }
        val m = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val cx = joy.tx * w
        val cy = joy.ty * h
        val chave = joy.id + 1
        if (m < 0.01f) { if (joyFisico) { Toque.subir(chave); joyFisico = false } }
        else {
            if (!joyFisico) { Toque.descer(chave, cx, cy); joyFisico = true }
            Toque.mover(chave, cx + dx / m * joy.raio * w, cy + dy / m * joy.raio * w)
        }
        return true
    }
}

/** Tela de tarefa para iniciar o teclado e os controles. */
object TecladoJogo {
    fun iniciar(con: Console, pkgFixo: String? = null) {
        val ctx = con.ctx
        con.titulo("Teclado e controles no jogo")
        con.linha("Coloque botões onde quiser por cima do jogo, cada um com a tecla que você escolher, como no MuMu Player. Também tem teclado completo, analógico e macros.", T.texto2)
        if (!Sh.privOk()) {
            con.aviso("Para enviar teclas e toques a outro app o Android exige o modo automático (Shizuku) ou root.")
            if (!Priv.ativar(con)) return
        }
        if (!con.garantirNome("Sobrepor a outros apps")) { con.erro("Sem permissão de sobreposição os botões não aparecem por cima do jogo."); return }
        if (!Injetor.preparar()) { con.erro("Não consegui acessar o serviço de entrada do Android."); return }
        if (Injetor.modo == "shell") con.aviso("Modo compatível: toques simples funcionam, mas segurar e arrastar não funcionam neste celular.")
        else con.ok("Envio de teclas e toques pronto")
        val jogo = if (pkgFixo != null) Jogo(pkgFixo, Jogos.nome(ctx, pkgFixo), "") else Jogos.escolher(con, "Abrir qual jogo?")
        if (jogo == null) return
        val tem = PerfisControle.tem(jogo.pkg)
        val opcoes = arrayListOf<String>()
        if (tem) opcoes.add("Jogar com meus botões (${PerfisControle.carregar(jogo.pkg).size})")
        opcoes.add(if (tem) "Editar meus botões" else "Criar meus botões (editor estilo MuMu)")
        opcoes.add("Só o teclado Gamer (WASD + ações)")
        opcoes.add("Teclado completo de PC")
        val i = con.escolher(jogo.nome, opcoes)
        if (i < 0) return
        val escolha = opcoes[i]
        val editar = escolha.startsWith("Editar") || escolha.startsWith("Criar")
        val layout = when {
            escolha.startsWith("Só o teclado") -> "gamer"
            escolha.startsWith("Teclado completo") -> "completo"
            else -> "nenhum"
        }
        if (!Mapeador.ativo() && !Store.bool("mapeador_perguntado", false)) {
            Store.putBool("mapeador_perguntado", true)
            if (con.ask("Você usa teclado ou controle físico (Bluetooth/USB)? Posso fazer cada tecla virar um toque na tela, igual ao MuMu Player.", "Usar", "Não uso", importante = true)) Mapeador.ativar(con)
        }
        ctx.startForegroundService(Intent(ctx, TecladoService::class.java).putExtra("pkg", jogo.pkg).putExtra("layout", layout).putExtra("abrir", true).putExtra("editar", editar))
        con.linha("")
        con.ok("Abrindo ${jogo.nome}" + if (editar) " no editor" else "")
        con.subtitulo("Como usar")
        con.item("Bolinha ⌨: menu com Editar, Teclado, Esconder e Fechar (arraste para mudar de lugar)")
        con.item("Editor: + Tecla, + Toque, + Analógico, + Macro. Arraste para posicionar e toque no botão para mudar a letra")
        con.item("Botão de TOQUE funciona em qualquer jogo, mesmo nos que não aceitam teclado")
        con.item("Com o jogo já aberto: use o atalho “Teclado PC Toolkit” nas Configurações rápidas (puxe a barra de notificações e edite os blocos)")
        con.info("Os botões ficam salvos por jogo e trocam sozinhos quando você muda de jogo.")
    }
}
