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
                if (!rodando) { iniciarBase(null); if (!rodando) return START_NOT_STICKY }
                ui.post { if (visivel && (janelas.isNotEmpty() || controles.isEmpty())) esconder() else { rem(bolha); bolha = null; mostrarTudo() } }
                return START_STICKY
            }
            "editar" -> { if (!rodando) { iniciarBase(null); if (!rodando) return START_NOT_STICKY }; ui.post { abrirEditor() }; return START_STICKY }
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
        if (!Licenca.ativo()) { semPro(); return }
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

    /** Sem assinatura ativa: fecha os controles e abre a tela do Pro. */
    private fun semPro() {
        try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("pro", true)) } catch (e: Exception) { }
        fechar()
    }

    private var ultimaChecagemPro = 0L

    private fun notif(): Notification {
        val alt = PendingIntent.getService(this, 11, Intent(this, TecladoService::class.java).setAction("alternar"), PendingIntent.FLAG_IMMUTABLE)
        val edt = PendingIntent.getService(this, 13, Intent(this, TecladoService::class.java).setAction("editar"), PendingIntent.FLAG_IMMUTABLE)
        val fec = PendingIntent.getService(this, 12, Intent(this, TecladoService::class.java).setAction("fechar"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CANAL).setSmallIcon(R.drawable.ic_jogo).setContentTitle("Controles no jogo ativos")
            .setContentText("Toque na bolinha ⌨ para editar, esconder ou fechar").setColor(T.verde).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Mostrar/Esconder", alt).build())
            .addAction(Notification.Action.Builder(null, "Editar", edt).build())
            .addAction(Notification.Action.Builder(null, "Fechar", fec).build()).build()
    }

    // ---------------- troca de perfil conforme o jogo na frente ----------------
    private val vigia = object : Runnable {
        override fun run() {
            if (!rodando) { vigiando = false; return }
            if (System.currentTimeMillis() - ultimaChecagemPro > 60_000) { ultimaChecagemPro = System.currentTimeMillis(); if (!Licenca.ativo()) { vigiando = false; semPro(); return } }
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
            val p = lp((c.x * w).toInt() - tamPx / 2, (c.y * h).toInt() - tamPx / 2, tamPx, tamPx)
            // O toque que o app envia vai para a janela que estiver no ponto. Se o nosso circulo estiver em cima do
            // proprio ponto do jogo, ele "engoliria" o toque: nesse caso ele vira so uma marca (os dedos passam direto
            // para o jogo e as teclas fisicas continuam funcionando). Alpha 0.8 e o maximo que o Android 12+ deixa passar.
            if (Controles.cobreAlvo(c, w, h, tamPx)) { p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; p.alpha = 0.8f; v.alpha = v.alpha.coerceAtMost(0.7f) }
            if (add(v, p)) janelas.add(v)
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
        item("✎  Editar botões", T.verde) { abrirEditor() }
        item("⌨  Teclado do PC: " + Layouts.nomes[Layouts.ids.indexOf(layout).coerceAtLeast(0)]) {
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

    // ================= EDITOR =================
    // Ideia central: cada botao aperta um ponto do JOGO (a mira). Para criar: toque em "+ Botao" e depois toque
    // no lugar do jogo que ele deve apertar. O circulo e onde VOCE aperta; a mira e onde o jogo recebe o toque.
    private fun dialogo(): AlertDialog.Builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.TemaDialogo))

    private fun mostrarDialogo(d: AlertDialog) {
        d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        try { d.show() } catch (e: Exception) { Log2.registrar("dialogo: $e") }
    }

    /** Linhas tracejadas do circulo ate a mira de cada botao. */
    private class Ligacoes(ctx: Context, val itens: () -> List<Controle>, val w: Int, val h: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = ctx.resources.displayMetrics.density * 2f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f); color = 0xCCFFFFFF.toInt()
        }
        override fun onDraw(c: Canvas) {
            for (k in itens()) {
                if (k.tipo != "toque" && !(k.tipo == "analogico" && k.analog == "toque")) continue
                c.drawLine(k.x * w, k.y * h, k.tx * w, k.ty * h, p)
            }
        }
    }

    private var edLista: MutableList<Controle> = ArrayList()
    private var edRedesenhar: () -> Unit = {}
    private var edAguardando: ((Float, Float) -> Unit)? = null
    private var edAviso = ""
    private var edSel: Controle? = null

    @SuppressLint("ClickableViewAccessibility")
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
        edLista = controles.map { Controle.de(it.json()) }.toMutableList()
        edAguardando = null; edAviso = ""; edSel = null
        val ligacoes = Ligacoes(this, { edLista }, w, h)

        // toque no "fundo" do editor: usado para escolher a mira (o ponto do jogo)
        raiz.setOnTouchListener { _, e ->
            val cb = edAguardando
            if (cb != null && e.actionMasked == MotionEvent.ACTION_UP) { edAguardando = null; cb(e.x, e.y) }
            else if (cb == null && e.actionMasked == MotionEvent.ACTION_UP && edSel != null) { edSel = null; edRedesenhar() }
            true
        }

        edRedesenhar = {
            raiz.removeAllViews()
            raiz.setBackgroundColor(if (edAguardando != null) 0x22000000 else 0x44000000)
            raiz.addView(ligacoes, FrameLayout.LayoutParams(w, h))
            ligacoes.invalidate()
            if (edAguardando == null) {
                for (c in edLista) {
                    val alvoDeToque = c.tipo == "toque" || (c.tipo == "analogico" && c.analog == "toque")
                    if (alvoDeToque) {
                        val m = Mira(this, if (c.tipo == "toque") T.azul else T.laranja)
                        val mt = Ui.dp(this, 40)
                        val lpM = FrameLayout.LayoutParams(mt, mt)
                        lpM.leftMargin = (c.tx * w).toInt() - mt / 2; lpM.topMargin = (c.ty * h).toInt() - mt / 2
                        raiz.addView(m, lpM)
                        arrastarNoEditor(m, lpM, mt, { edSel = c; edRedesenhar() }, { cx, cy -> c.tx = cx / w; c.ty = cy / h; ligacoes.invalidate() }, { cx, cy -> c.tx = cx / w; c.ty = cy / h; ligacoes.invalidate() })
                    }
                    val tam = Ui.dp(this, if (c.tipo == "analogico") c.tam * 2 else c.tam)
                    val v: View = if (c.tipo == "analogico") Analogico(this).also { it.texto = c.texto() } else {
                        val t = TextView(this)
                        t.text = c.texto(); t.gravity = Gravity.CENTER; t.setTextColor(T.texto); t.typeface = Ui.negrito
                        t.textSize = if (t.text.length > 3) 11f else 16f
                        val cor = when (c.tipo) { "toque" -> T.verde; "macro" -> T.roxo; else -> T.amarelo }
                        t.background = Ui.forma(this, 0xD90A0E14.toInt(), 50, cor, if (c === edSel) 4 else 2)
                        t
                    }
                    if (c === edSel) v.alpha = 1f else v.alpha = 0.92f
                    val lpV = FrameLayout.LayoutParams(tam, tam)
                    lpV.leftMargin = (c.x * w).toInt() - tam / 2; lpV.topMargin = (c.y * h).toInt() - tam / 2
                    raiz.addView(v, lpV)
                    arrastarNoEditor(v, lpV, tam, { edSel = c; edRedesenhar() }, { cx, cy -> c.x = cx / w; c.y = cy / h; ligacoes.invalidate() }, { cx, cy -> c.x = cx / w; c.y = cy / h; ligacoes.invalidate() })
                }
            }

            // barra de cima
            val barra = Ui.horizontal(this)
            barra.background = Ui.forma(this, 0xF20A0E14.toInt(), 50, Ui.alpha(T.verde, 0x88))
            barra.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4))
            fun bt(t: String, cor: Int, cheio: Boolean = false, acao: () -> Unit) {
                val b = Ui.texto(this, t, 13f, if (cheio) 0xFF06120D.toInt() else cor, Ui.negrito)
                b.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8))
                if (cheio) b.background = Ui.forma(this, cor, 50)
                b.setOnClickListener { acao() }
                val lpb = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); lpb.marginEnd = Ui.dp(this, 2)
                barra.addView(b, lpb)
            }
            if (edAguardando != null) {
                bt("Cancelar", T.vermelho) { edAguardando = null; edAviso = ""; edRedesenhar() }
            } else {
                bt("+ Botão", T.verde, true) { novoBotao(w, h) }
                bt("+ Analógico", T.laranja) { novoAnalogico(w, h) }
                bt("Mais", T.texto2) { maisOpcoes(edLista) { edRedesenhar() } }
                bt("Salvar", T.verde) { controles = edLista; PerfisControle.salvar(pkg, edLista); Controles.conflitos(edLista, w, h) { Ui.dp(this, it) }?.let { toast(it) }; fecharEditor() }
                bt("Sair", T.texto3) {
                    val mudou = edLista.map { it.json().toString() } != controles.map { it.json().toString() }
                    if (!mudou) fecharEditor()
                    else mostrarDialogo(dialogo().setMessage("Sair sem salvar as mudanças?").setPositiveButton("Sair sem salvar") { _, _ -> fecharEditor() }
                        .setNeutralButton("Salvar e sair") { _, _ -> controles = edLista; PerfisControle.salvar(pkg, edLista); fecharEditor() }
                        .setNegativeButton("Continuar editando", null).create())
                }
            }
            val hsv = HorizontalScrollView(this)
            hsv.isHorizontalScrollBarEnabled = false
            hsv.addView(barra)
            val lpB = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            lpB.topMargin = Ui.dp(this, 16)
            raiz.addView(hsv, lpB)

            // aviso / ajuda no rodape
            val texto = when {
                edAguardando != null -> edAviso
                edLista.isEmpty() -> "Toque em “+ Botão” e depois toque no lugar do JOGO que esse botão vai apertar (ex.: o botão de atirar)."
                else -> "Círculo = onde você aperta (arraste) · Mira ⊕ = onde o jogo recebe o toque (arraste) · Toque no círculo para ajustar"
            }
            val dica = Ui.texto(this, texto, if (edAguardando != null) 15f else 12f, if (edAguardando != null) T.texto else T.texto2, if (edAguardando != null) Ui.negrito else Ui.medio)
            dica.gravity = Gravity.CENTER
            dica.background = Ui.forma(this, 0xE60A0E14.toInt(), 14, if (edAguardando != null) T.verde else T.outline)
            dica.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8))
            val lpD = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (edAguardando != null) Gravity.CENTER else (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            lpD.bottomMargin = Ui.dp(this, 12); lpD.leftMargin = Ui.dp(this, 24); lpD.rightMargin = Ui.dp(this, 24)
            raiz.addView(dica, lpD)

            // painel de ajustes do botao selecionado
            edSel?.let { sel -> if (edAguardando == null) painelAjustes(raiz, sel, w, h) }
        }
        edRedesenhar()
        val p = lp(0, 0, w, h, focavel = false, tocavelFora = false)
        if (add(raiz, p)) editor = raiz
    }

    private fun aguardarAlvo(aviso: String, pronto: (Float, Float) -> Unit) {
        edAviso = aviso
        edAguardando = pronto
        edSel = null
        edRedesenhar()
    }

    private fun novoBotao(w: Int, h: Int) {
        aguardarAlvo("Toque no lugar do JOGO que este botão vai apertar") { x, y ->
            val c = Controle(tipo = "toque", x = x / w, y = y / h, tx = x / w, ty = y / h, tam = 56, opac = 0.75f)
            c.rotulo = (edLista.count { it.tipo == "toque" } + 1).toString()
            edLista.add(c); edSel = c; edAviso = ""
            edRedesenhar()
        }
    }

    private fun novoAnalogico(w: Int, h: Int) {
        aguardarAlvo("Toque no CENTRO do analógico (joystick) do jogo") { x, y ->
            val c = Controle(tipo = "analogico", x = x / w, y = y / h, tx = x / w, ty = y / h, tam = 60, analog = "toque", raio = 0.08f, opac = 0.7f)
            edLista.add(c); edSel = c; edAviso = ""
            edRedesenhar()
        }
    }

    /** Painel na parte de baixo (ou de cima) da tela com os ajustes do botao escolhido. Sem caixas de texto. */
    private fun painelAjustes(raiz: FrameLayout, c: Controle, w: Int, h: Int) {
        val box = Ui.vertical(this)
        box.background = Ui.forma(this, 0xF50A0E14.toInt(), 18, Ui.alpha(T.verde, 0x77))
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10))
        val topo = Ui.horizontal(this)
        val titulo = when (c.tipo) { "toque" -> "Botão ${c.texto()}"; "analogico" -> "Analógico"; "macro" -> "Macro"; else -> "Botão de tecla ${c.texto()}" }
        topo.addView(Ui.texto(this, titulo, 15f, T.texto, Ui.negrito), Ui.peso())
        val ok = Ui.texto(this, "Pronto", 13.5f, 0xFF06120D.toInt(), Ui.negrito)
        ok.background = Ui.forma(this, T.verde, 50); ok.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6))
        ok.setOnClickListener { edSel = null; edRedesenhar() }
        topo.addView(ok)
        box.addView(topo)

        fun rot(t: String) { val v = Ui.texto(this, t, 11.5f, T.texto3, Ui.medio); v.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 3)); box.addView(v) }
        fun chips(ops: List<String>, sel: Int, onSel: (Int) -> Unit) {
            val hs = HorizontalScrollView(this); hs.isHorizontalScrollBarEnabled = false
            val l = Ui.horizontal(this)
            ops.forEachIndexed { i, o ->
                val v = Ui.texto(this, o, 12.5f, if (i == sel) 0xFF06120D.toInt() else T.texto, Ui.medio)
                v.setPadding(Ui.dp(this, 11), Ui.dp(this, 6), Ui.dp(this, 11), Ui.dp(this, 6))
                v.background = if (i == sel) Ui.forma(this, T.verde, 50) else Ui.forma(this, T.surface2, 50, T.outline)
                v.setOnClickListener { onSel(i); edRedesenhar() }
                val lpv = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); lpv.marginEnd = Ui.dp(this, 6)
                l.addView(v, lpv)
            }
            hs.addView(l); box.addView(hs)
        }
        val teclasFisicas = listOf("Nenhuma", "Espaço", "W", "A", "S", "D", "Q", "E", "R", "F", "G", "C", "V", "X", "Z", "1", "2", "3", "4", "5", "Shift", "Ctrl", "Tab", "Botão A", "Botão B", "Botão X", "Botão Y", "L1", "R1", "L2", "R2")

        when (c.tipo) {
            "toque" -> {
                rot("Ao apertar")
                val ms = listOf("segurar", "alternar", "turbo")
                chips(listOf("Normal", "Liga/desliga", "Turbo (repete rápido)"), ms.indexOf(c.modo).coerceAtLeast(0)) {
                    c.modo = ms[it]
                    if (it > 0 && Controles.cobreAlvo(c, w, h, Ui.dp(this, c.tam))) {
                        val dx = Ui.dp(this, c.tam + 24).toFloat() / w
                        c.x = if (c.tx > 0.5f) (c.tx - dx).coerceAtLeast(0.04f) else (c.tx + dx).coerceAtMost(0.96f)
                        edAviso = "Movi o botão para o lado: Turbo e Liga/desliga na tela precisam do círculo fora do ponto do jogo (a linha mostra o ponto). Arraste para onde preferir."
                    }
                }
                if (c.modo == "turbo") {
                    val vs = listOf(200, 120, 70, 40)
                    rot("Velocidade do turbo")
                    chips(listOf("Lenta", "Média", "Rápida", "Máxima"), vs.indexOfFirst { it == c.turboMs }.let { if (it < 0) 2 else it }) { c.turboMs = vs[it] }
                }
                rot("Tecla do teclado ou controle físico (opcional)")
                val atual = if (c.tecla > 0) teclasFisicas.indexOfFirst { Teclas.codigo(it) == c.tecla }.coerceAtLeast(0) else 0
                chips(teclasFisicas, atual) { i ->
                    c.tecla = if (i == 0) 0 else Teclas.codigo(teclasFisicas[i])
                    c.rotulo = if (i == 0) (edLista.filter { it.tipo == "toque" }.indexOf(c) + 1).toString() else ""
                    if (i > 0 && !Mapeador.ativo()) toast("Para usar teclado ou controle físico, ative em SerpyraToolkit > Teclado e controles.")
                }
            }
            "analogico" -> {
                rot("Alcance do movimento")
                val rs = listOf(0.05f, 0.08f, 0.11f, 0.14f)
                chips(listOf("Curto", "Médio", "Longo", "Máximo"), rs.indexOfFirst { Math.abs(it - c.raio) < 0.01f }.let { if (it < 0) 1 else it }) { c.raio = rs[it] }
                if (c.analog != "toque") {
                    rot("Envia")
                    val ops = listOf("toque", "wasd", "setas")
                    chips(listOf("Arrastar no jogo", "W A S D", "Setas"), ops.indexOf(c.analog).coerceAtLeast(0)) { c.analog = ops[it] }
                }
            }
            "tecla" -> {
                rot("Tecla que este botão envia (só para jogos que aceitam teclado)")
                val lista = teclasFisicas.drop(1)
                chips(lista, lista.indexOfFirst { Teclas.codigo(it) == c.tecla }.coerceAtLeast(0)) { c.tecla = Teclas.codigo(lista[it]); c.rotulo = "" }
                rot("Ao apertar")
                val ms = listOf("segurar", "alternar", "turbo")
                chips(listOf("Normal", "Liga/desliga", "Turbo"), ms.indexOf(c.modo).coerceAtLeast(0)) { c.modo = ms[it] }
            }
            "macro" -> {
                val l = Ui.texto(this, "Sequência: " + c.macro.ifEmpty { "(vazia)" }, 12.5f, T.texto2)
                box.addView(l)
            }
        }
        rot("Tamanho e visibilidade")
        val tams = listOf(44, 56, 68, 84)
        chips(listOf("P", "M", "G", "GG"), tams.indexOf(c.tam).let { if (it < 0) 1 else it }) { c.tam = tams[it] }
        val ops = listOf(0.35f, 0.55f, 0.75f, 0.95f)
        chips(listOf("Quase invisível", "Fraco", "Normal", "Forte"), ops.indexOfFirst { Math.abs(it - c.opac) < 0.06f }.let { if (it < 0) 2 else it }) { c.opac = ops[it] }

        val acoes = Ui.horizontal(this)
        fun acao(t: String, cor: Int, f: () -> Unit) {
            val v = Ui.texto(this, t, 12.5f, cor, Ui.medio)
            v.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 4))
            v.setOnClickListener { f() }
            acoes.addView(v)
        }
        if (c.tipo == "toque" || (c.tipo == "analogico" && c.analog == "toque")) acao("⊕ Escolher outro ponto do jogo", T.azul) {
            aguardarAlvo(if (c.tipo == "toque") "Toque no novo lugar do JOGO que este botão vai apertar" else "Toque no CENTRO do analógico do jogo") { x, y -> c.tx = x / w; c.ty = y / h; edSel = c; edAviso = ""; edRedesenhar() }
        }
        if (c.tipo == "macro") acao("✎ Editar sequência", T.roxo) { editarControle(c, edLista, false) { edRedesenhar() } }
        acao("Duplicar", T.texto2) { val n = Controle.de(c.json()); n.id = System.nanoTime(); n.x = (c.x + 0.07f).coerceAtMost(0.95f); edLista.add(n); edSel = n; edRedesenhar() }
        acao("Excluir", T.vermelho) { edLista.remove(c); edSel = null; edRedesenhar() }
        val hsA = HorizontalScrollView(this); hsA.isHorizontalScrollBarEnabled = false; hsA.addView(acoes)
        box.addView(hsA)

        val emBaixo = c.y < 0.55f
        val lpP = FrameLayout.LayoutParams(Math.min(w - Ui.dp(this, 24), Ui.dp(this, 460)), ViewGroup.LayoutParams.WRAP_CONTENT,
            (if (emBaixo) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL)
        if (emBaixo) lpP.bottomMargin = Ui.dp(this, 8) else lpP.topMargin = Ui.dp(this, 64)
        box.isClickable = true
        raiz.addView(box, lpP)
    }

    private fun toast(t: String) { ui.post { android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show() } }

    private fun fecharEditor() {
        rem(editor); editor = null; edAguardando = null; edSel = null
        mostrarTudo()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun arrastarNoEditor(v: View, lp: FrameLayout.LayoutParams, tam: Int, toque: (() -> Unit)?, aoMover: (Float, Float) -> Unit, durante: (Float, Float) -> Unit = { _, _ -> }) {
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
                    if (moveu) { lp.leftMargin = ox + dx; lp.topMargin = oy + dy; v.layoutParams = lp; durante((lp.leftMargin + tam / 2).toFloat(), (lp.topMargin + tam / 2).toFloat()) }
                }
                MotionEvent.ACTION_UP -> {
                    if (moveu) aoMover((lp.leftMargin + tam / 2).toFloat(), (lp.topMargin + tam / 2).toFloat())
                    else toque?.invoke()
                }
            }
            true
        }
    }

    /** Ajustes avancados (so a macro usa caixa de texto). */
    private fun editarControle(c: Controle, lista: MutableList<Controle>, novo: Boolean, depois: () -> Unit) {
        val box = Ui.vertical(this)
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 6))
        box.addView(Ui.texto(this, "Teclas e esperas em milissegundos, separadas por espaço. Ex.: Q 100 E 100 R  ·  Shift+W 500", 12f, T.texto3))
        val e = EditText(ContextThemeWrapper(this, R.style.TemaDialogo))
        e.setText(c.macro); e.setTextColor(T.texto); e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        box.addView(e)
        box.addView(Ui.texto(this, "Macro envia teclas: só funciona em jogos que aceitam teclado (Roblox, Minecraft, emuladores).", 11.5f, T.amarelo))
        val d = dialogo().setTitle("Sequência da macro").setView(box)
            .setPositiveButton("OK") { _, _ -> c.macro = e.text.toString().trim(); depois() }
            .setNegativeButton(if (novo) "Cancelar" else "Voltar") { _, _ -> if (novo) lista.remove(c); depois() }.create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        mostrarDialogo(d)
    }

    private fun maisOpcoes(lista: MutableList<Controle>, depois: () -> Unit) {
        val ops = arrayOf(
            "Como funciona",
            "Teclado do PC na tela: " + Layouts.nomes[Layouts.ids.indexOf(layout).coerceAtLeast(0)],
            "Copiar botões de outro jogo",
            "Apagar todos os botões",
            "Teclado ou controle físico: " + if (Mapeador.ativo()) "ligado" else "desligado",
            "Avançado: botão de tecla (jogos que aceitam teclado)",
            "Avançado: macro (sequência de teclas)",
            "Compartilhar meus botões com um amigo",
            "Usar botões que um amigo mandou",
            "Botões prontos de outros jogadores",
            "Publicar meus botões para outros jogadores"
        )
        mostrarDialogo(dialogo().setItems(ops) { _, i ->
            when (i) {
                0 -> mostrarDialogo(dialogo().setTitle("Como funciona").setMessage(
                    "1. Toque em “+ Botão”.\n2. Toque no lugar do jogo que esse botão deve apertar (por exemplo, o botão de atirar do jogo). Aparece a mira ⊕ ali.\n3. Arraste o círculo para onde for mais confortável para o seu dedo.\n4. Toque no círculo para escolher Turbo, Liga/desliga, tamanho ou uma tecla do teclado/controle físico.\n5. Toque em Salvar.\n\n" +
                        "Quando você aperta o círculo durante o jogo, o app toca na mira para você. Funciona em qualquer jogo, inclusive Free Fire, PUBG e COD.\n\n" +
                        "Importante: se o círculo ficar em cima da própria mira, ele vira só uma marca (você toca direto no jogo e a tecla física continua funcionando). Para Turbo, Liga/desliga ou para mudar um botão de lugar, deixe o círculo fora da mira.\n\n" +
                        "Analógico: toque no centro do analógico do jogo. Ao arrastar o nosso, o app move o do jogo.\n\n" +
                        "Os botões ficam salvos para cada jogo."
                ).setPositiveButton("Entendi", null).create())
                1 -> mostrarDialogo(dialogo().setTitle("Teclado do PC na tela").setItems(arrayOf(
                    "Nenhum (recomendado para jogos de celular)", "Gamer: WASD e ações", "Setas", "Teclado completo"
                )) { _, k -> layout = listOf("nenhum", "gamer", "setas", "completo")[k]; Store.putStr("tec_layout", layout)
                    if (k > 0) toast("O teclado aparece ao sair do editor. Ele só funciona em jogos que aceitam teclado, como Roblox, Minecraft e emuladores.") }.create())
                2 -> {
                    val outros = Jogos.todosApps(this).filter { it.pkg != pkg && PerfisControle.tem(it.pkg) }
                    if (outros.isEmpty()) mostrarDialogo(dialogo().setMessage("Nenhum outro jogo tem botões salvos ainda.").setPositiveButton("OK", null).create())
                    else mostrarDialogo(dialogo().setTitle("Copiar de").setItems(outros.map { it.nome }.toTypedArray()) { _, k ->
                        lista.clear(); lista.addAll(PerfisControle.carregar(outros[k].pkg).onEach { it.id = System.nanoTime() + (it.id % 100000) }); depois()
                    }.create())
                }
                3 -> mostrarDialogo(dialogo().setMessage("Apagar todos os botões deste jogo?").setPositiveButton("Apagar") { _, _ -> lista.clear(); edSel = null; depois() }.setNegativeButton("Cancelar", null).create())
                4 -> mostrarDialogo(dialogo().setTitle("Teclado ou controle físico").setMessage(
                    "Com um teclado ou controle Bluetooth/USB, escolha uma tecla em cada botão (toque no círculo > Tecla do teclado ou controle físico). Ao apertar a tecla, o jogo recebe o toque na mira, igual ao MuMu Player. W A S D e as setas movem o analógico.\n\nSituação: " +
                        (if (Mapeador.ativo()) "ligado ✓" else "desligado. Para ligar: SerpyraToolkit > Teclado e controles > Teclado ou controle físico.")
                ).setPositiveButton("OK", null).create())
                5 -> { val c = Controle(tipo = "tecla", x = 0.5f, y = 0.5f, tecla = KeyEvent.KEYCODE_SPACE); lista.add(c); edSel = c; depois()
                    toast("Botão de tecla: só funciona em jogos que aceitam teclado. Para Free Fire, PUBG e afins, use “+ Botão”.") }
                6 -> { val c = Controle(tipo = "macro", x = 0.5f, y = 0.4f); lista.add(c); edSel = c; editarControle(c, lista, true, depois) }
                7 -> {
                    if (lista.isEmpty()) { toast("Crie pelo menos um botão antes de compartilhar."); return@setItems }
                    val cod = CompartilharBotoes.codigo(lista)
                    try { (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("botoes", cod)) } catch (e: Exception) { }
                    try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Meus botões do SerpyraToolkit para ${Jogos.nome(this, pkg)}. No app: Teclado e controles > editar > Mais > Usar botões que um amigo mandou, e cole:\n\n$cod"), "Compartilhar botões").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
                    toast("Código copiado. Mande para o seu amigo.")
                }
                8 -> {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val txt = try { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: "" } catch (e: Exception) { "" }
                    val novos = CompartilharBotoes.ler(txt)
                    val usar: (MutableList<Controle>) -> Unit = { nv ->
                        mostrarDialogo(dialogo().setTitle("Usar ${nv.size} botões do seu amigo?").setMessage("Os botões atuais deste jogo serão trocados.").setPositiveButton("Usar") { _, _ -> lista.clear(); lista.addAll(nv); edSel = null; depois(); toast("Botões do amigo colocados. Toque em Salvar.") }.setNegativeButton("Cancelar", null).create())
                    }
                    if (novos != null) usar(novos)
                    else {
                        // o Android pode nao deixar ler o que foi copiado por cima do jogo: deixa colar a mao
                        val campo = EditText(ContextThemeWrapper(this, R.style.TemaDialogo))
                        campo.hint = "Cole aqui (começa com SERPYRA-BOTOES:)"
                        campo.maxLines = 4; campo.setTextColor(T.texto)
                        mostrarDialogo(dialogo().setTitle("Usar botões de um amigo").setMessage("Segure o dedo no campo abaixo e toque em Colar.").setView(campo)
                            .setPositiveButton("Usar") { _, _ ->
                                val l = CompartilharBotoes.ler(campo.text.toString())
                                if (l == null) toast("Código inválido. Copie a mensagem inteira que o seu amigo mandou.") else usar(l)
                            }.setNegativeButton("Cancelar", null).create())
                    }
                }
                9 -> {
                    toast("Buscando botões de outros jogadores…")
                    Thread {
                        val l = Comunidade.listar(pkg)
                        ui.post {
                            when {
                                l == null -> toast("Sem conexão agora. Tente de novo.")
                                l.isEmpty() -> mostrarDialogo(dialogo().setTitle("Ainda não tem").setMessage("Ninguém publicou botões para ${Jogos.nome(this, pkg)} ainda. Que tal ser o primeiro? Monte os seus e use Mais > Publicar meus botões.").setPositiveButton("OK", null).create())
                                else -> mostrarDialogo(dialogo().setTitle("Botões para ${Jogos.nome(this, pkg)}").setItems(l.map { "${it.nome} · ${it.n} botões" + (if (it.autor.isNotBlank()) " · por ${it.autor}" else "") + " · ${it.baixados}↓" }.toTypedArray()) { _, k ->
                                    Thread {
                                        val cod = Comunidade.baixar(l[k].id)
                                        val nv = cod?.let { CompartilharBotoes.ler(it) }
                                        ui.post {
                                            if (nv == null) toast("Não consegui baixar. Tente de novo.")
                                            else mostrarDialogo(dialogo().setTitle("Usar “${l[k].nome}”?").setMessage("Os botões atuais deste jogo serão trocados. Depois ajuste a posição se precisar e toque em Salvar.").setPositiveButton("Usar") { _, _ -> lista.clear(); lista.addAll(nv); edSel = null; depois(); toast("Botões colocados. Toque em Salvar.") }.setNegativeButton("Cancelar", null).create())
                                        }
                                    }.start()
                                }.create())
                            }
                        }
                    }.start()
                }
                10 -> {
                    if (lista.size < 2) { toast("Monte pelo menos 2 botões antes de publicar."); return@setItems }
                    val nome = EditText(ContextThemeWrapper(this, R.style.TemaDialogo)); nome.hint = "Nome (ex.: 4 dedos rush)"; nome.setTextColor(T.texto)
                    val autor = EditText(ContextThemeWrapper(this, R.style.TemaDialogo)); autor.hint = "Seu apelido (opcional)"; autor.setTextColor(T.texto)
                    val caixa = android.widget.LinearLayout(this); caixa.orientation = android.widget.LinearLayout.VERTICAL
                    val pd = (16 * resources.displayMetrics.density).toInt(); caixa.setPadding(pd, 0, pd, 0)
                    caixa.addView(nome); caixa.addView(autor)
                    mostrarDialogo(dialogo().setTitle("Publicar para outros jogadores").setMessage("Seus ${lista.size} botões para ${Jogos.nome(this, pkg)} ficam disponíveis para quem usa o app. Só os botões vão: nada pessoal.").setView(caixa)
                        .setPositiveButton("Publicar") { _, _ ->
                            val cod = CompartilharBotoes.codigo(lista)
                            Thread {
                                val erro = Comunidade.publicar(pkg, Jogos.nome(this, pkg), nome.text.toString(), autor.text.toString(), cod, lista.size)
                                toast(erro ?: "Publicado! Obrigado por ajudar outros jogadores.")
                            }.start()
                        }.setNegativeButton("Cancelar", null).create())
                }
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
        con.linha("Crie botões por cima do jogo: cada um aperta um ponto do jogo para você, com turbo, liga/desliga ou uma tecla do teclado/controle físico.", T.texto2)
        if (!Pro.exigir(con, "Teclado e controles no jogo")) return
        if (!Sh.privOk()) {
            con.aviso("Para enviar teclas e toques a outro app o Android exige o modo completo ou root.")
            if (!Priv.ativar(con)) return
        }
        if (!con.garantirNome("Sobrepor a outros apps")) { con.erro("Sem permissão de sobreposição os botões não aparecem por cima do jogo."); return }
        if (!Injetor.preparar()) { con.erro("Não consegui acessar o serviço de entrada do Android."); return }
        if (Injetor.modo == "shell") con.aviso("Modo compatível: toques simples funcionam, mas segurar e arrastar não funcionam neste celular.")
        else con.ok("Envio de teclas e toques pronto")
        val jogo = if (pkgFixo != null) Jogo(pkgFixo, Jogos.nome(ctx, pkgFixo), "") else Jogos.escolher(con, "Em qual jogo?")
        if (jogo == null) return
        val tem = PerfisControle.tem(jogo.pkg)
        val opcoes = arrayListOf<String>()
        if (tem) opcoes.add("Jogar com meus botões (${PerfisControle.carregar(jogo.pkg).size})")
        val sug = Modelos.sugerido(jogo.nome, jogo.pkg)
        val txtModelo = "Usar um modelo pronto" + (if (sug >= 0) " de ${Modelos.LISTA[sug].nome}" else " (Free Fire, PUBG, COD...)") + if (!tem) "  (recomendado)" else ""
        if (!tem) opcoes.add(txtModelo)
        opcoes.add(if (tem) "Editar meus botões" else "Criar meus botões do zero")
        if (tem) opcoes.add(txtModelo)
        opcoes.add("Teclado ou controle físico (Bluetooth/USB): " + if (Mapeador.ativo()) "ligado" else "desligado")
        val i = con.escolher(jogo.nome, opcoes)
        if (i < 0) return
        if (opcoes[i].startsWith("Teclado ou controle")) {
            if (Mapeador.ativo()) { if (con.ask("Desligar o uso de teclado/controle físico?", "Desligar", "Manter")) { Mapeador.desativar(); con.ok("Desligado") } }
            else Mapeador.ativar(con)
            return
        }
        if (opcoes[i].startsWith("Usar um modelo") && !Modelos.escolherEAplicar(con, jogo)) return
        val editar = !opcoes[i].startsWith("Jogar")
        ctx.startForegroundService(Intent(ctx, TecladoService::class.java).putExtra("pkg", jogo.pkg).putExtra("abrir", true).putExtra("editar", editar))
        con.linha("")
        con.ok("Abrindo ${jogo.nome}" + if (editar) " no editor" else "")
        if (editar) {
            con.subtitulo("Como criar um botão (leva 10 segundos)")
            con.item("1. Toque em “+ Botão” na barra de cima")
            con.item("2. Toque no lugar do JOGO que ele deve apertar (ex.: o botão de atirar do jogo)")
            con.item("3. Arraste o círculo para onde ficar mais confortável")
            con.item("4. Toque no círculo para escolher Turbo, Liga/desliga ou uma tecla do controle")
            con.item("5. Toque em Salvar")
        } else {
            con.item("A bolinha ⌨ abre o menu: Editar, Esconder e Fechar. Arraste-a para mudar de lugar.")
        }
        con.info("Funciona em qualquer jogo, inclusive Free Fire, PUBG e COD. Os botões ficam salvos para cada jogo.")

    }
}
