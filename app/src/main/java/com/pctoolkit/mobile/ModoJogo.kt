package com.pctoolkit.mobile

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/** MODO JOGO sem root: prepara o celular, abre o jogo, vigia o calor e restaura tudo no fim. */
object ModoJogo {
    fun executar(con: Console, pkgFixo: String? = null) {
        val ctx = con.ctx
        con.titulo("Modo Jogo")
        con.linha("Prepara o celular, abre o jogo, mostra um painel com temperatura e memória e, quando você terminar, devolve tudo ao normal.", T.texto2)
        if (GameService.rodando) {
            con.aviso("Já existe uma sessão em andamento: ${Store.str("sessao_nome")}")
            if (con.ask("Encerrar a sessão agora?", "Encerrar", "Continuar", importante = true)) { ctx.startService(Intent(ctx, GameService::class.java).setAction("parar")); con.ok("Encerrando...") }
            return
        }
        val jogo = (if (pkgFixo != null) Jogo(pkgFixo, Jogos.nome(ctx, pkgFixo), "tuner") else Jogos.escolher(con)) ?: run { con.linha("Cancelado."); return }
        CentralJogos.usado(jogo.pkg)
        val perfil = Tuner.perfil(jogo.pkg)
        if (perfil.aplicado) con.info("Perfil do Tuner: " + perfil.resumo())
        con.linha("Jogo: ${jogo.nome}", T.verde)

        val nomes = arrayListOf(
            "Não perturbe durante o jogo",
            "Fechar apps em segundo plano antes de abrir",
            "Pausar a sincronização automática (e-mail, fotos, nuvem)",
            "Painel flutuante com temperatura, bateria e memória",
            "Alerta de calor (vibra se a bateria passar do limite)"
        )
        val chaves = arrayListOf("mj_dnd", "mj_ram", "mj_sync", "mj_painel", "mj_alerta")
        if (Pro.ativo()) { nomes.add("Modo Frio: segura a temperatura sem desligar o boost"); chaves.add("mj_frio") }
        else con.info("Modo Frio, Turbo e abrir os seus botões junto com o jogo fazem parte do SerpyraToolkit Pro.")
        if (Pro.ativo() && PerfisControle.tem(jogo.pkg)) { nomes.add("Abrir os meus botões do teclado junto com o jogo"); chaves.add("mj_botoes") }
        nomes.add("Mostrar o ping no painel (atraso da internet)"); chaves.add("mj_ping")
        nomes.add("Lembrar de fazer uma pausa a cada 1 hora de jogo"); chaves.add("mj_pausa")
        val sel = con.varios("O que ativar nesta sessão?", nomes, true)
        if (sel.isEmpty() && !con.ask("Nada marcado. Abrir o jogo mesmo assim, só com o monitoramento?")) return
        val op = HashMap<String, Boolean>()
        for (i in chaves.indices) op[chaves[i]] = sel.contains(i)
        op["mj_turbo"] = Pro.ativo() && Sh.privOk() && (perfil.turbo || con.ask("Ativar o Turbo? Trava o Android no desempenho máximo durante o jogo (mais FPS estável, mas esquenta e gasta mais bateria).", "Ativar", "Não", importante = true))
        if (op["mj_dnd"] == true && !Sh.privOk() && !con.garantirNome("Não perturbe")) op["mj_dnd"] = false
        if (op["mj_painel"] == true && !con.garantirNome("Sobrepor a outros apps")) op["mj_painel"] = false
        if (op["mj_botoes"] == true) {
            if (!Sh.privOk() && !Priv.ativar(con)) op["mj_botoes"] = false
            else if (!con.garantirNome("Sobrepor a outros apps") || !Injetor.preparar()) op["mj_botoes"] = false
            if (op["mj_botoes"] != true) con.aviso("Os botões não vão abrir sozinhos desta vez (precisa do modo automático e da sobreposição).")
        }
        if (!Acesso.notificacoes()) con.garantirNome("Notificações")
        val detecta = Acesso.uso()
        if (!detecta) {
            con.info("Sem o “Acesso ao uso” não dá para saber quando você fecha o jogo: encerre pela notificação ou pelo painel.")
            if (con.ask("Permitir o Acesso ao uso para o Modo Jogo encerrar sozinho?")) con.garantirNome("Acesso ao uso")
        }
        val limite = Store.int("limite_bateria", 43)
        if (op["mj_frio"] == null) op["mj_frio"] = false
        if (op["mj_frio"] == true && !Frio.pode()) {
            con.aviso("O Modo Frio precisa do modo automático (Shizuku) para mexer na tela e no FPS. Sem ele, só o alerta de calor funciona.")
            if (con.ask("Ativar o modo automático agora?")) Priv.ativar(con)
            if (!Frio.pode()) op["mj_frio"] = false
        }
        if (op["mj_frio"] == true) con.info("Modo Frio: começa a agir perto de $limite °C (mude em Temperatura > Alerta de calor).")
        if (!Sh.privOk() && Build.VERSION.SDK_INT >= 23) {
            val pmx = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pmx.isIgnoringBatteryOptimizations(ctx.packageName) && con.ask("Para o Modo Jogo não ser desligado pelo Android no meio da partida, permitir que o app rode sem restrição de bateria?", "Permitir", "Agora não")) {
                con.abrirEEsperar(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + ctx.packageName)))
            }
        }
        if (Build.VERSION.SDK_INT >= 31 && Build.MANUFACTURER.equals("samsung", true).not()) con.info("Dica: se o seu Android tiver “Modo de jogo/Desempenho” nas configurações do jogo, deixe em Desempenho.")

        val i = Intent(ctx, GameService::class.java).putExtra("pkg", jogo.pkg).putExtra("nome", jogo.nome).putExtra("limite", limite)
        for ((k, v) in op) i.putExtra(k, v)
        if (perfil.aplicado && perfil.resGlobal in 40..99 && Sh.privOk()) i.putExtra("res", perfil.resGlobal)
        ctx.startForegroundService(i)
        if (op["mj_botoes"] == true) {
            try { ctx.startForegroundService(Intent(ctx, TecladoService::class.java).putExtra("pkg", jogo.pkg).putExtra("layout", "nenhum").putExtra("abrir", false).putExtra("editar", false)) } catch (e: Exception) { }
            con.ok("Seus botões vão aparecer por cima de ${jogo.nome}")
        }
        con.linha("")
        con.ok("Sessão iniciada. O jogo vai abrir em instantes.")
        con.info("Acompanhe pela notificação “Modo Jogo”. Ao terminar, o resumo aparece aqui e no Histórico.")
        con.sleep(3000)
        var ultimo = 0L
        while (GameService.rodando && !con.cancelado) {
            con.sleep(1000)
            if (System.currentTimeMillis() - ultimo > 30000) { ultimo = System.currentTimeMillis(); con.info(GameService.status) }
        }
        if (con.cancelado) { con.info("A sessão continua em segundo plano."); return }
        val a = Store.lerArr("sessoes.json")
        if (a.length() > 0) {
            val o = a.getJSONObject(a.length() - 1)
            if (o.optString("pkg") == jogo.pkg) mostrarResultado(con, o)
        }
    }

    fun mostrarResultado(con: Console, o: JSONObject) {
        con.subtitulo("Sessão: ${o.optString("nome")} · ${o.optInt("dur")} min")
        con.linha("Bateria: ${f1(o.optDouble("tIni"))} → máx ${f1(o.optDouble("tMax"))} °C (média ${f1(o.optDouble("tMed"))} °C)", Sensores.corBateria(o.optDouble("tMax")))
        con.linha("Carga usada: ${o.optInt("gasto")}%" + if (o.optInt("dur") > 0) "  (${o.optInt("gasto") * 60 / o.optInt("dur").coerceAtLeast(1)}% por hora)" else "")
        con.linha("Memória livre mínima: ${fmtTam(o.optLong("ramMin"))}")
        val st = o.optInt("termMax", -1)
        if (st >= 0) con.linha("Estado térmico mais alto: " + Sensores.nomeStatusTermico(st), if (st >= 2) T.amarelo else T.texto)
        if (o.has("fps")) con.linha("FPS médio: ${o.optInt("fps")}" + if (o.has("fps1")) " · 1% pior: ${o.optInt("fps1")}" else "", T.verde)
        val fr = o.optInt("frio", 0)
        if (fr > 0) con.linha("Modo Frio chegou ao nível $fr (${Frio.descricao(fr)}) para segurar a temperatura", T.ciano)
        con.linha("")
        val tMax = o.optDouble("tMax")
        when {
            st >= 3 || tMax >= 45 -> con.aviso("O celular ESQUENTOU MUITO e limitou o desempenho. Tire a capinha, não jogue carregando e baixe um nível de gráfico.")
            st == 2 || tMax >= 41 -> con.aviso("Esquentou: o desempenho pode ter caído no fim. Veja Temperatura > Dicas.")
            else -> con.ok("Temperatura sob controle durante toda a sessão.")
        }
        if (o.optLong("ramMin") in 1 until (600L shl 20)) con.aviso("A memória ficou quase cheia: feche mais apps antes de jogar.")
    }

    fun historico(con: Console) {
        con.titulo("Histórico do Modo Jogo")
        val a = Store.lerArr("sessoes.json")
        if (a.length() == 0) { con.linha("Nenhuma sessão ainda."); return }
        for (i in a.length() - 1 downTo 0) {
            val o = a.getJSONObject(i)
            con.item("${o.optString("data")} · ${o.optString("nome")} · ${o.optInt("dur")} min · máx ${f1(o.optDouble("tMax"))} °C · ${o.optInt("gasto")}%", Sensores.corBateria(o.optDouble("tMax")))
        }
        val i = con.escolher("Ver detalhes", (a.length() - 1 downTo 0).map { a.getJSONObject(it).optString("data") + " · " + a.getJSONObject(it).optString("nome") })
        if (i >= 0) mostrarResultado(con, a.getJSONObject(a.length() - 1 - i))
    }
}

/** O que precisa voltar ao normal se o app for fechado no meio da sessao. */
object Sessao {
    fun pendente(): Boolean = Store.bool("sessao_ativa", false)

    fun restaurar(ctx: Context) {
        if (Store.bool("sync_pausada", false)) {
            try { ContentResolver.setMasterSyncAutomatically(true) } catch (e: Exception) { }
            Store.putBool("sync_pausada", false)
        }
        val f = Store.int("dnd_antes", -1)
        if (f > 0) {
            try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).setInterruptionFilter(f) } catch (e: Exception) { }
            Store.putInt("dnd_antes", -1)
        }
        if (Store.bool("perf_fixo", false) || Store.bool("frio_turbo", false)) { Sh.root("cmd power set-fixed-performance-mode-enabled false", 10); Store.putBool("perf_fixo", false) }
        if (Store.bool("dnd_cmd", false)) { Sh.root("cmd notification set_dnd off", 10); Store.putBool("dnd_cmd", false) }
        Tuner.restaurarResolucao()
        Frio.restaurarTela()
        if (Store.bool("frio_fps", false)) {
            val pk = try { JSONObject(Store.str("sessao_cfg")).optString("pkg") } catch (e: Exception) { "" }
            if (pk.isNotEmpty() && Build.VERSION.SDK_INT >= 33) { val p = Tuner.perfil(pk); Sh.root("cmd game set --mode 2 --downscale ${if (p.aplicado && p.escala < 0.999) String.format(Locale.US, "%.2f", p.escala) else "disable"} --fps ${if (p.aplicado && p.fps > 0) p.fps.toString() else "disable"} $pk 2>/dev/null", 10) }
            Store.putBool("frio_fps", false)
        }
        Frio.nivel = 0
        Store.putBool("sessao_ativa", false)
    }
}

/**
 * MODO FRIO: segura a temperatura sem desligar o boost.
 * Em vez de deixar o celular esquentar ate o sistema derrubar o FPS sozinho, baixa aos poucos
 * a taxa da tela (que limita o FPS) e, se precisar, o limite de FPS do jogo. Quando esfria, devolve.
 */
object Frio {
    @Volatile var nivel = 0
    private var mudouEm = 0L
    private var abaixoDesde = 0L

    fun pode(): Boolean = Ajuste.pode()

    private fun ler(ns: String, k: String) = Ajuste.ler(ns, k)

    /** Guarda os valores originais da tela (uma vez por sessao). */
    fun preparar() {
        nivel = 0; mudouEm = 0L; abaixoDesde = 0L
        if (Store.str("frio_orig").isNotEmpty()) return
        val o = JSONObject().put("peak", ler("system", "peak_refresh_rate")).put("min", ler("system", "min_refresh_rate")).put("sam", ler("secure", "refresh_rate_mode"))
        Store.putStr("frio_orig", o.toString())
    }

    private fun telaHz(hz: Int) {
        val o = try { JSONObject(Store.str("frio_orig")) } catch (e: Exception) { JSONObject() }
        Ajuste.gravar("system", "peak_refresh_rate", "$hz.0")
        val min = o.optString("min").toFloatOrNull() ?: 0f
        if (min > hz) Ajuste.gravar("system", "min_refresh_rate", "$hz.0")
        if (hz <= 60 && Build.MANUFACTURER.equals("samsung", true)) Ajuste.gravar("secure", "refresh_rate_mode", "0")
    }

    /** Devolve a tela ao que era antes do Modo Frio. */
    fun restaurarTela() {
        val s = Store.str("frio_orig")
        if (s.isEmpty()) return
        try {
            val o = JSONObject(s)
            fun volta(ns: String, k: String, v: String) { Ajuste.gravar(ns, k, v.ifEmpty { null }) }
            volta("system", "peak_refresh_rate", o.optString("peak"))
            volta("system", "min_refresh_rate", o.optString("min"))
            if (Build.MANUFACTURER.equals("samsung", true)) volta("secure", "refresh_rate_mode", o.optString("sam"))
        } catch (e: Exception) { }
        Store.putStr("frio_orig", "")
    }

    private fun limiteFps(pkg: String, fps: Int) {
        if (!Sh.privOk() || Build.VERSION.SDK_INT < 33) return
        val p = Tuner.perfil(pkg)
        val esc = if (p.aplicado && p.escala < 0.999) String.format(Locale.US, "%.2f", p.escala) else "disable"
        val modo = if (p.aplicado && p.modo == "bateria") 3 else 2
        Sh.root("cmd game set --mode $modo --downscale $esc --fps ${if (fps > 0) fps.toString() else "disable"} $pkg 2>/dev/null", 10)
    }

    /** Nivel que a temperatura pede agora. */
    fun desejado(t: Double, st: Int, folga: Float?, alvo: Int): Int = when {
        t >= alvo + 2.0 || st >= 3 -> 3
        t >= alvo || st >= 2 || (folga != null && folga >= 0.95f) -> 2
        t >= alvo - 1.5 || (folga != null && folga >= 0.85f) -> 1
        else -> 0
    }

    /**
     * Decide e aplica. Sobe na hora (com 20 s de intervalo); so desce um nivel depois de 90 s
     * abaixo do limite daquele nivel, para nao ficar oscilando.
     */
    fun ajustar(ctx: Context, pkg: String, t: Double, st: Int, folga: Float?, alvo: Int, turbo: Boolean): String? {
        val d = desejado(t, st, folga, alvo)
        val agora = System.currentTimeMillis()
        var novo = nivel
        if (d > nivel && agora - mudouEm > 20_000) { novo = nivel + 1; abaixoDesde = 0L }
        else if (d < nivel) {
            if (abaixoDesde == 0L) abaixoDesde = agora
            if (agora - abaixoDesde > 90_000 && t <= alvo - 2.5) { novo = nivel - 1; abaixoDesde = 0L }
        } else abaixoDesde = 0L
        if (novo == nivel) return null
        val antes = nivel
        nivel = novo
        mudouEm = agora
        aplicar(ctx, pkg, turbo, true)
        return if (novo > antes) "Modo Frio nível $novo: " + descricao(novo) else if (novo == 0) "Modo Frio: temperatura normal, desempenho total de volta" else "Modo Frio voltou ao nível $novo"
    }

    fun descricao(n: Int) = when (n) {
        1 -> "Turbo pausado"
        2 -> "tela em 60 Hz (FPS máx. 60)"
        3 -> "FPS limitado a 45 até esfriar"
        else -> "normal"
    }

    /** Aplica o estado do nivel atual (tambem usado para reafirmar periodicamente). */
    fun aplicar(ctx: Context, pkg: String, turbo: Boolean, mudou: Boolean) {
        val (_, max) = Sensores.tela(ctx)
        val maxHz = max.toInt()
        when (nivel) {
            0 -> if (mudou) { restaurarTelaSessao(); if (Tuner.perfil(pkg).aplicado || Store.bool("frio_fps", false)) limiteFps(pkg, Tuner.perfil(pkg).let { if (it.aplicado) it.fps else 0 }); Store.putBool("frio_fps", false) }
            1 -> { if (maxHz > 100) telaHz(90) else if (mudou) restaurarTelaSessao() }
            2 -> { telaHz(60) }
            3 -> { telaHz(60); if (mudou) { limiteFps(pkg, 45); Store.putBool("frio_fps", true) } }
        }
        if (mudou && nivel == 2 && Store.bool("frio_fps", false)) { limiteFps(pkg, Tuner.perfil(pkg).let { if (it.aplicado) it.fps else 0 }); Store.putBool("frio_fps", false) }
        if (Sh.privOk()) {
            val fixo = turbo && nivel == 0
            Sh.root("cmd power set-fixed-performance-mode-enabled $fixo 2>/dev/null", 10)
            if (fixo) Store.putBool("perf_fixo", true)
        }
    }

    /** No nivel 0 volta a tela ao original mas mantem os originais guardados ate o fim da sessao. */
    private fun restaurarTelaSessao() {
        val s = Store.str("frio_orig")
        if (s.isEmpty()) return
        try {
            val o = JSONObject(s)
            Ajuste.gravar("system", "peak_refresh_rate", o.optString("peak").ifEmpty { null })
            Ajuste.gravar("system", "min_refresh_rate", o.optString("min").ifEmpty { null })
            if (Build.MANUFACTURER.equals("samsung", true)) Ajuste.gravar("secure", "refresh_rate_mode", o.optString("sam").ifEmpty { null })
        } catch (e: Exception) { }
    }
}

class GameService : Service() {
    companion object {
        @Volatile var rodando = false
        @Volatile var status = ""
        private const val CANAL = "modojogo"
        private const val CANAL_ALERTA = "calor"
        private const val ID = 4040
        /** Apps que aparecem por cima do jogo sem que voce tenha saido dele. */
        private val transitorios = listOf("systemui", "com.google.android.gms", "com.android.vending", "com.google.android.play.games",
            "com.google.android.inputmethod", "inputmethod", "keyboard", "com.android.permissioncontroller", "packageinstaller",
            "com.google.android.permissioncontroller", "com.android.intentresolver", "com.android.chrome", "com.google.android.webview",
            "com.facebook.katana", "com.android.incallui", "com.samsung.android.incallui", "com.google.android.dialer", "gamebooster", "gamespace", "gametools", "joyose", "securitycenter", "gamemode", "gameassistant")
    }

    @Volatile private var parar = false
    private var painel: View? = null
    private var painelTxt: TextView? = null
    private val ui = Handler(Looper.getMainLooper())
    private var trava: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "parar") { parar = true; if (!rodando) stopSelf(); return START_NOT_STICKY }
        if (rodando) return START_STICKY
        // reinicio pelo Android depois de ter sido encerrado: retoma a sessao salva
        val cfg = if (intent == null || intent.getStringExtra("pkg") == null) Store.str("sessao_cfg") else ""
        val dados: JSONObject = when {
            intent != null && intent.getStringExtra("pkg") != null -> paraJson(intent)
            cfg.isNotEmpty() && Store.bool("sessao_ativa", false) -> try { JSONObject(cfg).put("retomada", true) } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        rodando = true
        criarCanais()
        val n = notif("Modo Jogo · " + dados.optString("nome"), "Preparando...")
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        } catch (e: Exception) { Log2.registrar("startForeground: $e"); rodando = false; stopSelf(); return START_NOT_STICKY }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            trava = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "pctoolkit:modojogo").also { it.acquire(6 * 3600_000L) }
        } catch (e: Exception) { }
        Thread {
            var terminou = false
            try { sessao(dados); terminou = true } catch (e: Throwable) { Log2.registrar("GameService erro: $e") } finally {
                if (terminou || parar) {
                    Sessao.restaurar(this)
                    Store.putStr("sessao_cfg", "")
                }
                ui.post { tirarPainel() }
                try { trava?.release() } catch (e: Exception) { }
                rodando = false
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }.start()
        return START_STICKY
    }

    private fun paraJson(it: Intent): JSONObject {
        val o = JSONObject().put("pkg", it.getStringExtra("pkg")).put("nome", it.getStringExtra("nome") ?: it.getStringExtra("pkg"))
            .put("limite", it.getIntExtra("limite", 43)).put("res", it.getIntExtra("res", 0))
        for (k in listOf("mj_dnd", "mj_ram", "mj_sync", "mj_painel", "mj_alerta", "mj_turbo", "mj_frio", "mj_ping", "mj_pausa")) o.put(k, it.getBooleanExtra(k, false))
        val b = Sensores.bateria(this)
        o.put("inicio", System.currentTimeMillis()).put("tIni", b.tempC).put("nIni", b.nivel)
        return o
    }

    private fun criarCanais() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Modo Jogo", NotificationManager.IMPORTANCE_LOW))
        val c = NotificationChannel(CANAL_ALERTA, "Alerta de calor", NotificationManager.IMPORTANCE_HIGH)
        c.setBypassDnd(true)
        nm.createNotificationChannel(c)
    }

    private fun notif(titulo: String, texto: String, canal: String = CANAL, acaoParar: Boolean = true): Notification {
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, canal).setSmallIcon(R.drawable.ic_jogo).setContentTitle(titulo).setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto)).setContentIntent(abrir).setColor(T.verde)
        if (acaoParar) {
            val p = PendingIntent.getService(this, 1, Intent(this, GameService::class.java).setAction("parar"), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, "Encerrar sessão", p).build())
        }
        if (canal == CANAL) b.setOngoing(true)
        return b.build()
    }

    private fun atualizar(t: String, s: String) { try { getSystemService(NotificationManager::class.java).notify(ID, notif(t, s)) } catch (e: Exception) { } }

    private fun avisar(id: Int, t: String, s: String) { try { getSystemService(NotificationManager::class.java).notify(id, notif(t, s, CANAL_ALERTA, false)) } catch (e: Exception) { } }

    /** Le os eventos novos desde [desde] e devolve o ultimo app que ficou na frente (ou null se nada mudou). */
    private fun eventos(desde: Long): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ev = try { usm.queryEvents(desde, System.currentTimeMillis()) } catch (e: Exception) { return null }
        val e = UsageEvents.Event()
        var ult: String? = null
        @Suppress("DEPRECATION")
        val tipoResumo = if (Build.VERSION.SDK_INT >= 29) UsageEvents.Event.ACTIVITY_RESUMED else UsageEvents.Event.MOVE_TO_FOREGROUND
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == tipoResumo) ult = e.packageName
        }
        return ult
    }

    private fun transitorio(p: String): Boolean = p == packageName || transitorios.any { p.contains(it) } || FerramentaFabricante.conhecidas.any { it.first == p }

    private fun jogoVivo(pkg: String): Boolean? {
        if (!Sh.privOk()) return null
        val r = Sh.root("pidof $pkg", 5)
        return r.out.isNotBlank()
    }

    /** Liga (ou religa) o boost. Chamado no inicio e a cada 30 s, porque o sistema pode desligar sozinho. */
    private fun reforcar(d: JSONObject, primeira: Boolean) {
        val pkg = d.optString("pkg")
        val nm = getSystemService(NotificationManager::class.java)
        if (d.optBoolean("mj_dnd")) {
            if (nm.isNotificationPolicyAccessGranted) {
                if (primeira && Store.int("dnd_antes", -1) <= 0) Store.putInt("dnd_antes", nm.currentInterruptionFilter)
                if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_PRIORITY) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            } else if (Sh.privOk()) { if (Sh.root("cmd notification set_dnd priority", 10).ok) Store.putBool("dnd_cmd", true) }
        }
        if (Sh.privOk()) {
            val self = packageName
            Sh.root("dumpsys deviceidle whitelist +$self >/dev/null 2>&1; dumpsys deviceidle whitelist +$pkg >/dev/null 2>&1; cmd appops set $self RUN_ANY_IN_BACKGROUND allow 2>/dev/null; am set-standby-bucket $self active 2>/dev/null", 15)
            val p = Tuner.perfil(pkg)
            if (Build.VERSION.SDK_INT >= 31 && !(p.aplicado && p.modo == "bateria")) Sh.root("cmd game mode performance $pkg 2>/dev/null", 10)
            val res = d.optInt("res")
            if (res in 40..99 && !Store.bool("res_global", false)) Tuner.aplicarResolucao(res)
        }
        if (d.optBoolean("mj_frio") && Frio.pode()) Frio.aplicar(this, pkg, d.optBoolean("mj_turbo"), primeira)
        else if (d.optBoolean("mj_turbo") && Sh.privOk()) { if (Sh.root("cmd power set-fixed-performance-mode-enabled true 2>&1", 10).ok) Store.putBool("perf_fixo", true) }
    }

    private fun sessao(d: JSONObject) {
        val pkg = d.optString("pkg")
        val nome = d.optString("nome", pkg)
        val limite = d.optInt("limite", 43)
        val retomada = d.optBoolean("retomada")
        Store.putBool("sessao_ativa", true)
        Store.putStr("sessao_nome", nome)
        Store.putStr("sessao_cfg", d.toString())
        if (d.optBoolean("mj_frio")) { Frio.preparar(); Frio.nivel = Store.int("frio_nivel", 0).takeIf { retomada } ?: 0 }

        if (!retomada) {
            if (d.optBoolean("mj_sync")) {
                try { if (ContentResolver.getMasterSyncAutomatically()) { ContentResolver.setMasterSyncAutomatically(false); Store.putBool("sync_pausada", true) } } catch (e: Exception) { }
            }
            if (d.optBoolean("mj_ram")) {
                if (Sh.privOk()) Sh.root("am kill-all", 15)
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                @Suppress("DEPRECATION")
                for (a in packageManager.getInstalledApplications(0)) if ((a.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && a.packageName != packageName && a.packageName != pkg) try { am.killBackgroundProcesses(a.packageName) } catch (e: Exception) { }
            }
        }
        reforcar(d, true)
        if (d.optBoolean("mj_painel") && android.provider.Settings.canDrawOverlays(this)) ui.post { porPainel() }
        if (!retomada) {
            val abrir = packageManager.getLaunchIntentForPackage(pkg)
            if (abrir != null) { abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(abrir) }
        }

        val inicio = d.optLong("inicio", System.currentTimeMillis())
        val tIni = d.optDouble("tIni", Sensores.bateria(this).tempC)
        val nIni = d.optInt("nIni", Sensores.bateria(this).nivel)
        var tMax = Math.max(tIni, Store.str("sessao_tmax").toDoubleOrNull() ?: 0.0); var tSoma = 0.0; var n = 0
        var ramMin = Long.MAX_VALUE
        var termMax = -1
        var avisado = 0L
        val detectar = Acesso.uso()
        var foraDesde = 0L
        var telaDesligadaDesde = 0L
        var viuJogo = retomada
        var ultimoFg: String? = if (retomada) pkg else null
        var ultimaConsulta = System.currentTimeMillis() - 10_000
        var ultimoReforco = System.currentTimeMillis()
        var shizukuCaiu = false
        var nivelFrioMax = 0
        val fpsLista = ArrayList<Double>()
        var pingTxt = ""; var voltas = 0; var horasAvisadas = 0
        var qAnt = Sensores.contadorQuadros(); var tAnt = System.nanoTime()
        val pmw = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        Thread.sleep(3000)
        while (!parar) {
            Thread.sleep(2000)
            val agora = System.currentTimeMillis()
            val b = Sensores.bateria(this)
            val mem = Sensores.memoria(this).availMem
            val st = Sensores.statusTermico(this)
            val folga = Sensores.folgaTermica(this)
            tMax = maxOf(tMax, b.tempC); tSoma += b.tempC; n++
            ramMin = minOf(ramMin, mem); termMax = maxOf(termMax, st)

            // FPS real (Shizuku/root)
            var fpsTxt = ""
            if (qAnt == null && Sh.privOk()) { qAnt = Sensores.contadorQuadros(); tAnt = System.nanoTime() }
            else if (qAnt != null) {
                val q = Sensores.contadorQuadros()
                val ag = System.nanoTime()
                if (q != null) { val f = (q - (qAnt ?: q)) * 1e9 / (ag - tAnt); if (f in 1.0..250.0) { fpsLista.add(f); fpsTxt = "${f.toInt()} FPS · " }; qAnt = q; tAnt = ag }
            }

            // Modo Frio
            if (d.optBoolean("mj_frio") && Frio.pode()) {
                val msg = Frio.ajustar(this, pkg, b.tempC, st, folga, limite, d.optBoolean("mj_turbo"))
                if (msg != null) { Store.putInt("frio_nivel", Frio.nivel); nivelFrioMax = maxOf(nivelFrioMax, Frio.nivel); avisar(ID + 3, msg, "Bateria a ${f1(b.tempC)} °C. O boost continua ligado; só o calor está sendo controlado.") }
            }

            // reforca o boost a cada 30 s (o sistema/fabricante pode desligar sozinho)
            if (agora - ultimoReforco > 30_000) {
                ultimoReforco = agora
                val ok = !Sh.privOk() && Store.str("modo_perm") != "manual" && Priv.instalado(this)
                if (ok && !shizukuCaiu) { shizukuCaiu = true; avisar(ID + 4, "Shizuku desligou", "O Modo Jogo continua, mas sem os ajustes automáticos. Abra o Shizuku e toque em Iniciar para voltar.") }
                if (Sh.privOk() && shizukuCaiu) { shizukuCaiu = false; try { getSystemService(NotificationManager::class.java).cancel(ID + 4) } catch (e: Exception) { } }
                reforcar(d, false)
                Store.putStr("sessao_tmax", tMax.toString())
            }

            // ping a cada ~6 s (conexao TCP rapida; nao usa dados de verdade)
            if (d.optBoolean("mj_ping") && voltas++ % 3 == 0) { val ms = medirPing(); pingTxt = if (ms >= 0) " · $ms ms" else " · sem internet" }
            val min = ((agora - inicio) / 60000).toInt()
            if (d.optBoolean("mj_pausa") && min >= 60 && min / 60 > horasAvisadas) { horasAvisadas = min / 60; avisarPausa(horasAvisadas) }
            val frioTxt = if (d.optBoolean("mj_frio") && Frio.nivel > 0) " · Frio ${Frio.nivel}" else ""
            status = "${fpsTxt}${f1(b.tempC)} °C$pingTxt · ${b.nivel}% · RAM ${fmtTam(mem)} · $min min$frioTxt" + if (st >= 2) " · CALOR" else ""
            atualizar("Modo Jogo · $nome", status)
            ui.post { painelTxt?.text = status; painelTxt?.setTextColor(Sensores.corBateria(b.tempC)) }
            if (d.optBoolean("mj_alerta") && b.tempC >= limite + (if (d.optBoolean("mj_frio")) 2 else 0) && agora - avisado > 180_000) {
                avisado = agora
                vibrar()
                avisar(ID + 1, "Celular quente: ${f1(b.tempC)} °C", "Acima do seu limite de $limite °C. Faça uma pausa, tire a capinha ou baixe os gráficos.")
            }

            // fim da sessao: so quando voce realmente saiu do jogo por alguns minutos
            if (detectar) {
                val fg = eventos(ultimaConsulta)
                ultimaConsulta = agora - 3000
                if (fg != null && !transitorio(fg)) ultimoFg = fg
                if (fg == pkg) ultimoFg = pkg
                if (ultimoFg == pkg) { viuJogo = true; foraDesde = 0L }
                else if (viuJogo && ultimoFg != null && foraDesde == 0L) foraDesde = agora
                val vivo = jogoVivo(pkg)
                val espera = if (vivo == false) 60_000L else 240_000L
                if (foraDesde > 0 && agora - foraDesde > espera) break
            }
            if (!pmw.isInteractive) { if (telaDesligadaDesde == 0L) telaDesligadaDesde = agora; if (agora - telaDesligadaDesde > 20 * 60_000L) break } else telaDesligadaDesde = 0L
            if (agora - inicio > 8 * 3600_000L) break
        }
        val b1 = Sensores.bateria(this)
        val dur = ((System.currentTimeMillis() - inicio) / 60000).toInt()
        val o = JSONObject().put("data", agora()).put("pkg", pkg).put("nome", nome).put("dur", dur)
            .put("tIni", tIni).put("tMax", tMax).put("tMed", if (n > 0) tSoma / n else b1.tempC)
            .put("gasto", (nIni - b1.nivel).coerceAtLeast(0)).put("ramMin", if (ramMin == Long.MAX_VALUE) 0L else ramMin).put("termMax", termMax)
            .put("frio", nivelFrioMax)
        if (fpsLista.size > 5) {
            o.put("fps", fpsLista.average().toInt())
            val ord = fpsLista.sorted()
            o.put("fps1", ord[(ord.size / 100).coerceAtMost(ord.size - 1)].toInt())
        }
        Store.adicionar("sessoes.json", o, 100)
        Store.putStr("sessao_tmax", "")
        Store.putInt("frio_nivel", 0)
        try {
            getSystemService(NotificationManager::class.java).notify(ID + 2, notif("Sessão encerrada: $nome", "$dur min · bateria até ${f1(tMax)} °C · ${(nIni - b1.nivel).coerceAtLeast(0)}% de carga" + if (o.has("fps")) " · ${o.optInt("fps")} FPS médio" else "", CANAL, false))
        } catch (e: Exception) { }
        status = ""
    }

    private fun avisarPausa(horas: Int) {
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel("pausa", "Lembrete de pausa", android.app.NotificationManager.IMPORTANCE_HIGH))
            val txt = "Você está jogando há $horas hora(s). Que tal uma pausa de 5 minutos para descansar os olhos e esfriar o celular?"
            nm.notify(7171, android.app.Notification.Builder(this, "pausa").setSmallIcon(R.drawable.ic_jogo).setContentTitle("Hora de uma pausa")
                .setContentText(txt).setStyle(android.app.Notification.BigTextStyle().bigText(txt)).setAutoCancel(true).setColor(T.verde).build())
        } catch (e: Exception) { }
    }

    private fun medirPing(): Int {
        for (alvo in listOf("1.1.1.1" to 443, "8.8.8.8" to 53)) {
            try {
                val t0 = System.nanoTime()
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(alvo.first, alvo.second), 1500) }
                return ((System.nanoTime() - t0) / 1_000_000).toInt()
            } catch (e: Exception) { }
        }
        return -1
    }

    private fun vibrar() {
        try {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250), -1))
        } catch (e: Exception) { }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun porPainel() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.background = Ui.forma(this, 0xE60A0E14.toInt(), 50, Ui.alpha(T.verde, 0x66))
        box.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6))
        val tv = Ui.texto(this, "Modo Jogo", 11.5f, T.verde, Ui.medio)
        box.addView(tv)
        val x = Ui.texto(this, "  ✕", 13f, T.texto2, Ui.negrito)
        x.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0)
        x.setOnClickListener { parar = true }
        box.addView(x)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = Ui.dp(this, 12); lp.y = Ui.dp(this, 60)
        var ix = 0f; var iy = 0f; var ox = 0; var oy = 0
        box.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = e.rawX; iy = e.rawY; ox = lp.x; oy = lp.y; true }
                MotionEvent.ACTION_MOVE -> { lp.x = ox + (e.rawX - ix).toInt(); lp.y = oy + (e.rawY - iy).toInt(); try { wm.updateViewLayout(box, lp) } catch (ex: Exception) { }; true }
                else -> false
            }
        }
        try { wm.addView(box, lp); painel = box; painelTxt = tv } catch (e: Exception) { Log2.registrar("painel: $e") }
    }

    private fun tirarPainel() {
        val p = painel ?: return
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(p) } catch (e: Exception) { }
        painel = null; painelTxt = null
    }

    override fun onDestroy() { parar = true; tirarPainel(); super.onDestroy() }
}

@Suppress("unused")
private fun fmtMin(ms: Long) = String.format(Locale.US, "%d min", ms / 60000)
