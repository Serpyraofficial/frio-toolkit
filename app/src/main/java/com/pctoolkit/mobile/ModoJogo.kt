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
    fun executar(con: Console) {
        val ctx = con.ctx
        con.titulo("Modo Jogo")
        con.linha("Prepara o celular, abre o jogo, mostra um painel com temperatura e memória e, quando você terminar, devolve tudo ao normal.", T.texto2)
        if (GameService.rodando) {
            con.aviso("Já existe uma sessão em andamento: ${Store.str("sessao_nome")}")
            if (con.ask("Encerrar a sessão agora?", "Encerrar", "Continuar")) { ctx.startService(Intent(ctx, GameService::class.java).setAction("parar")); con.ok("Encerrando...") }
            return
        }
        val jogo = Jogos.escolher(con) ?: run { con.linha("Cancelado."); return }
        con.linha("Jogo: ${jogo.nome}", T.verde)

        val nomes = listOf(
            "Não perturbe durante o jogo",
            "Fechar apps em segundo plano antes de abrir",
            "Pausar a sincronização automática (e-mail, fotos, nuvem)",
            "Painel flutuante com temperatura, bateria e memória",
            "Alerta de calor (vibra se a bateria passar do limite)"
        )
        val chaves = listOf("mj_dnd", "mj_ram", "mj_sync", "mj_painel", "mj_alerta")
        val sel = con.varios("O que ativar nesta sessão?", nomes, true)
        if (sel.isEmpty() && !con.ask("Nada marcado. Abrir o jogo mesmo assim, só com o monitoramento?")) return
        val op = HashMap<String, Boolean>()
        for (i in chaves.indices) op[chaves[i]] = sel.contains(i)
        if (op["mj_dnd"] == true && !con.garantirNome("Não perturbe")) op["mj_dnd"] = false
        if (op["mj_painel"] == true && !con.garantirNome("Sobrepor a outros apps")) op["mj_painel"] = false
        if (!Acesso.notificacoes()) con.garantirNome("Notificações")
        val detecta = Acesso.uso()
        if (!detecta) {
            con.info("Sem o “Acesso ao uso” não dá para saber quando você fecha o jogo: encerre pela notificação ou pelo painel.")
            if (con.ask("Permitir o Acesso ao uso para o Modo Jogo encerrar sozinho?")) con.garantirNome("Acesso ao uso")
        }
        val limite = Store.int("limite_bateria", 43)
        if (Build.VERSION.SDK_INT >= 31 && Build.MANUFACTURER.equals("samsung", true).not()) con.info("Dica: se o seu Android tiver “Modo de jogo/Desempenho” nas configurações do jogo, deixe em Desempenho.")

        val i = Intent(ctx, GameService::class.java).putExtra("pkg", jogo.pkg).putExtra("nome", jogo.nome).putExtra("limite", limite)
        for ((k, v) in op) i.putExtra(k, v)
        ctx.startForegroundService(i)
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
        if (o.has("fps")) con.linha("FPS médio: ${o.optInt("fps")}", T.verde)
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
        Store.putBool("sessao_ativa", false)
    }
}

class GameService : Service() {
    companion object {
        @Volatile var rodando = false
        @Volatile var status = ""
        private const val CANAL = "modojogo"
        private const val CANAL_ALERTA = "calor"
        private const val ID = 4040
    }

    @Volatile private var parar = false
    private var painel: View? = null
    private var painelTxt: TextView? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "parar") { parar = true; if (!rodando) stopSelf(); return START_NOT_STICKY }
        if (rodando || intent == null) return START_NOT_STICKY
        rodando = true
        criarCanais()
        val n = notif("Preparando...", "")
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        Thread {
            try { sessao(intent) } catch (e: Throwable) { Log2.registrar("GameService erro: $e") } finally {
                Sessao.restaurar(this)
                ui.post { tirarPainel() }
                rodando = false
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
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

    private fun primeiroPlano(desde: Long): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ev = usm.queryEvents(desde, System.currentTimeMillis())
        val e = UsageEvents.Event()
        var ult: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            @Suppress("DEPRECATION")
            val tipoResumo = if (Build.VERSION.SDK_INT >= 29) UsageEvents.Event.ACTIVITY_RESUMED else UsageEvents.Event.MOVE_TO_FOREGROUND
            if (e.eventType == tipoResumo) ult = e.packageName
        }
        return ult
    }

    private fun sessao(it: Intent) {
        val pkg = it.getStringExtra("pkg") ?: return
        val nome = it.getStringExtra("nome") ?: pkg
        val limite = it.getIntExtra("limite", 43)
        Store.putBool("sessao_ativa", true)
        Store.putStr("sessao_nome", nome)
        val nm = getSystemService(NotificationManager::class.java)
        if (it.getBooleanExtra("mj_dnd", false) && nm.isNotificationPolicyAccessGranted) {
            Store.putInt("dnd_antes", nm.currentInterruptionFilter)
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
        if (it.getBooleanExtra("mj_sync", false)) {
            try { if (ContentResolver.getMasterSyncAutomatically()) { ContentResolver.setMasterSyncAutomatically(false); Store.putBool("sync_pausada", true) } } catch (e: Exception) { }
        }
        if (it.getBooleanExtra("mj_ram", false)) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            for (a in packageManager.getInstalledApplications(0)) if ((a.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && a.packageName != packageName && a.packageName != pkg) try { am.killBackgroundProcesses(a.packageName) } catch (e: Exception) { }
        }
        val alerta = it.getBooleanExtra("mj_alerta", false)
        if (it.getBooleanExtra("mj_painel", false) && android.provider.Settings.canDrawOverlays(this)) ui.post { porPainel() }

        val abrir = packageManager.getLaunchIntentForPackage(pkg)
        if (abrir != null) { abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(abrir) }

        val inicio = System.currentTimeMillis()
        val b0 = Sensores.bateria(this)
        var tMax = b0.tempC; var tSoma = 0.0; var n = 0
        var ramMin = Long.MAX_VALUE
        var termMax = -1
        var avisado = 0L
        val detectar = Acesso.uso()
        var foraDesde = 0L
        var viuJogo = false
        val fpsLista = ArrayList<Double>()
        var qAnt = Sensores.contadorQuadros(); var tAnt = System.nanoTime()
        Thread.sleep(4000)
        while (!parar) {
            Thread.sleep(2000)
            val b = Sensores.bateria(this)
            val mem = Sensores.memoria(this).availMem
            val st = Sensores.statusTermico(this)
            tMax = maxOf(tMax, b.tempC); tSoma += b.tempC; n++
            ramMin = minOf(ramMin, mem); termMax = maxOf(termMax, st)
            var fpsTxt = ""
            if (qAnt != null) {
                val q = Sensores.contadorQuadros()
                val ag = System.nanoTime()
                if (q != null) { val f = (q - (qAnt ?: q)) * 1e9 / (ag - tAnt); if (f in 1.0..250.0) { fpsLista.add(f); fpsTxt = "${f.toInt()} FPS · " }; qAnt = q; tAnt = ag }
            }
            val min = ((System.currentTimeMillis() - inicio) / 60000).toInt()
            status = "${fpsTxt}${f1(b.tempC)} °C · ${b.nivel}% · RAM ${fmtTam(mem)} · $min min" + if (st >= 2) " · CALOR" else ""
            atualizar("Modo Jogo · $nome", status)
            ui.post { painelTxt?.text = status; painelTxt?.setTextColor(Sensores.corBateria(b.tempC)) }
            if (alerta && b.tempC >= limite && System.currentTimeMillis() - avisado > 180_000) {
                avisado = System.currentTimeMillis()
                vibrar()
                try { nm.notify(ID + 1, notif("Celular quente: ${f1(b.tempC)} °C", "Acima do seu limite de $limite °C. Faça uma pausa, tire a capinha ou baixe os gráficos.", CANAL_ALERTA, false)) } catch (e: Exception) { }
            }
            if (detectar) {
                val fg = primeiroPlano(System.currentTimeMillis() - 6000)
                if (fg == pkg) { viuJogo = true; foraDesde = 0 }
                else if (fg != null && fg != packageName && !fg.contains("systemui")) {
                    if (viuJogo && foraDesde == 0L) foraDesde = System.currentTimeMillis()
                }
                if (foraDesde > 0 && System.currentTimeMillis() - foraDesde > 45_000) break
            }
            if (System.currentTimeMillis() - inicio > 6 * 3600_000L) break
        }
        val b1 = Sensores.bateria(this)
        val dur = ((System.currentTimeMillis() - inicio) / 60000).toInt()
        val o = JSONObject().put("data", agora()).put("pkg", pkg).put("nome", nome).put("dur", dur)
            .put("tIni", b0.tempC).put("tMax", tMax).put("tMed", if (n > 0) tSoma / n else b1.tempC)
            .put("gasto", (b0.nivel - b1.nivel).coerceAtLeast(0)).put("ramMin", if (ramMin == Long.MAX_VALUE) 0L else ramMin).put("termMax", termMax)
        if (fpsLista.size > 5) o.put("fps", fpsLista.average().toInt())
        Store.adicionar("sessoes.json", o, 100)
        try {
            nm.notify(ID + 2, notif("Sessão encerrada: $nome", "$dur min · bateria até ${f1(tMax)} °C · ${(b0.nivel - b1.nivel).coerceAtLeast(0)}% de carga" + if (o.has("fps")) " · ${o.optInt("fps")} FPS médio" else "", CANAL, false).also { it.flags = it.flags and Notification.FLAG_ONGOING_EVENT.inv() })
        } catch (e: Exception) { }
        status = ""
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
