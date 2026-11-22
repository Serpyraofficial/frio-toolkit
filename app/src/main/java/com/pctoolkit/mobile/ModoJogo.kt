package com.pctoolkit.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import org.json.JSONObject

/** Perguntas do Modo Jogo (no console) -> o servico faz o resto com o jogo aberto. */
object ModoJogo {
    private val pesados = listOf(
        "com.android.chrome", "com.microsoft.emmx", "org.mozilla.firefox", "com.brave.browser", "com.opera.browser", "com.sec.android.app.sbrowser",
        "com.facebook.katana", "com.facebook.orca", "com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.twitter.android",
        "com.snapchat.android", "com.google.android.youtube", "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient", "com.kwai.video",
        "com.google.android.apps.photos", "com.dropbox.android", "com.microsoft.skydrive", "com.google.android.apps.docs", "com.shopee.br",
        "com.mercadolibre", "com.alibaba.aliexpresshd", "com.einnovation.temu", "com.linkedin.android", "com.pinterest", "com.reddit.frontpage"
    )

    fun executar(con: Console, ctx: Context, preselecionado: String? = null) {
        con.titulo("MODO JOGO")
        con.linha("Prepara o celular, abre o jogo, aplica o pacote TURBO, mede FPS e gargalo, vigia a temperatura e,")
        con.linha("quando voce fechar o jogo, devolve tudo ao normal. Nao injeta nada no jogo: so mexe no Android.", C.cinza)
        if (!con.exigeRoot()) return
        if (GameService.rodando) { con.aviso("Ja existe uma sessao rodando. Feche o jogo dela primeiro."); return }

        var jogo: Jogo? = null
        if (preselecionado != null) jogo = Jogos.instalados(ctx).firstOrNull { it.nome.contains(preselecionado, true) || it.pkg == preselecionado }
        if (jogo == null) jogo = Jogos.escolher(con, ctx)
        if (jogo == null) { con.linha("Cancelado."); return }
        con.linha("Jogo: ${jogo.nome}", C.verde)

        val padrao = Store.str("perfil_jogo", "Maximo")
        val nomes = listOf("Maximo", "Frio", "Silencioso", "Manter")
        val ops = listOf(
            "MAXIMO FPS (CPU e GPU no maximo; mais quente)",
            "EQUILIBRADO FRIO (CPU a 85%; bem mais frio, perde pouco FPS)",
            "SILENCIOSO / BATERIA (CPU a 70%)",
            "Manter como esta"
        ).mapIndexed { i, s -> if (nomes[i] == padrao) "$s  [ultimo usado]" else s }
        val e = con.escolher("Perfil de energia/temperatura para esta sessao", ops)
        if (e < 0) return
        val perfil = nomes[e]
        Store.putStr("perfil_jogo", perfil)
        val limite = Store.int("limite_bateria", 44)
        val protecao = perfil == "Maximo" && con.ask("Ativar protecao termica automatica? (se a bateria chegar a $limite C, troco sozinho para o perfil Equilibrado frio)")

        // fechar apps pesados
        val abertos = Sh.root("ps -A -o NAME 2>/dev/null", 15).out.lines().map { it.trim().substringBefore(':') }.toSet()
        val achados = pesados.filter { abertos.contains(it) }
        if (achados.isNotEmpty()) {
            val sel = con.varios("Fechar apps pesados abertos? (salve o que estiver fazendo)", achados.map { Jogos.nome(ctx, it) }, true)
            for (i in sel) { Sh.root("am force-stop ${achados[i]}", 15); con.ok("Fechado: " + Jogos.nome(ctx, achados[i])) }
        } else con.info("Nenhum app pesado aberto.")

        val pausar = con.ask("Durante o jogo: ativar NAO PERTURBE e pausar a sincronizacao automatica (e-mail, fotos, nuvem)? Voltam sozinhos ao terminar")
        val ram = con.ask("Liberar RAM (fechar apps em segundo plano) antes de abrir?")

        con.linha("")
        con.linha("PACOTE TURBO (so durante o jogo; tudo volta ao normal quando voce fechar):", C.ciano)
        con.linha("  - Modo de Jogo do Android em DESEMPENHO e jogo fora da otimizacao de bateria")
        con.linha("  - Tela travada no Hz maximo e Wi-Fi em modo de baixa latencia")
        con.linha("  - Limpeza automatica de memoria quando a RAM livre ficar baixa (menos engasgos)")
        val mascara = Cpu.mascaraNucleosGrandes()
        if (mascara != null) con.linha("  - Opcional: prender o jogo nos ${mascara.second} nucleos GRANDES (os mais rapidos)")
        val turbo = con.ask("Ativar o pacote TURBO?")
        var prender = false
        if (turbo && mascara != null) prender = con.ask("Prender o jogo nos nucleos grandes? (alguns jogos com anti-cheat nao gostam; se travar, responda Nao da proxima vez)")
        val fpsOk = Sensores.contadorFuncionando()
        val medir = if (fpsOk) con.ask("Medir o FPS durante a sessao (FPS medio, pior momento, gargalo e temperatura no final)?") else {
            con.info("(Este aparelho nao expoe o contador de quadros: a sessao mede CPU/GPU/temperatura, sem FPS.)"); false
        }

        val i = Intent(ctx, GameService::class.java)
            .putExtra("modo", "sessao").putExtra("pkg", jogo.pkg).putExtra("nome", jogo.nome)
            .putExtra("perfil", perfil).putExtra("protecao", protecao).putExtra("pausar", pausar)
            .putExtra("ram", ram).putExtra("turbo", turbo).putExtra("prender", prender).putExtra("medir", medir)
        ctx.startForegroundService(i)
        con.linha("")
        con.ok("Sessao iniciada. O jogo vai abrir em instantes.")
        con.info("Acompanhe pela notificacao 'PC Toolkit - Modo Jogo'. Quando voce fechar o jogo, tudo volta ao normal")
        con.info("e o resumo (FPS, gargalo, temperatura) aparece na notificacao, aqui e no Historico.")
        con.sleep(3000)
        while (GameService.rodando && !con.cancelado) con.sleep(1000)
        if (con.cancelado) return
        val a = Store.lerArr("sessoes.json")
        if (a.length() > 0) {
            val o = a.getJSONObject(a.length() - 1)
            if (o.optString("pkg") == jogo.pkg) {
                con.linha("")
                con.linha("=== SESSAO ENCERRADA: ${o.optString("nome")} (${o.optInt("dur")} min) ===", C.verde)
                Fps.mostrarResultado(con, o)
                if (o.optBoolean("protegeu")) con.aviso("A protecao termica foi acionada durante o jogo (bateria chegou a $limite C).")
            }
        }
    }
}

/** Estado restaurado pelo Android depois da sessao (coisas que nao sao comandos root). */
object Sessao {
    fun restaurarSync() {
        if (Store.bool("sync_pausada", false)) {
            try { ContentResolver.setMasterSyncAutomatically(true) } catch (e: Exception) { }
            Store.putBool("sync_pausada", false)
        }
    }
}

class GameService : Service() {
    companion object {
        @Volatile var rodando = false
        private const val CANAL = "modojogo"
        private const val ID = 4040
    }

    @Volatile private var parar = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (rodando || intent == null) return START_NOT_STICKY
        rodando = true
        criarCanal()
        val n = notif("Preparando...", "")
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        val modo = intent.getStringExtra("modo") ?: "sessao"
        Thread {
            try {
                if (modo == "bench") bench(intent) else sessao(intent)
            } catch (e: Throwable) {
                Log2.registrar("GameService erro: $e")
            } finally {
                restaurarTudo()
                rodando = false
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        parar = true
        super.onDestroy()
    }

    private fun criarCanal() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Modo Jogo", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notif(titulo: String, texto: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CANAL)
        return b.setContentTitle("PC Toolkit - $titulo")
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun atualizar(titulo: String, texto: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID, notif(titulo, texto))
    }

    private fun vibrar() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { }
    }

    private fun restaurarTudo() {
        if (Undo.sessao.quantos() > 0) Undo.sessao.desfazer { }
        Sessao.restaurarSync()
    }

    // ------------------------------------------------------------
    // Estatisticas comuns (sessao e benchmark)
    // ------------------------------------------------------------
    private class Coleta {
        val fps = ArrayList<Double>()
        val core = ArrayList<Int>()
        val gpu = ArrayList<Int>()
        val freq = ArrayList<Int>()
        var picoCpu = 0.0
        var picoBat = 0.0
        var termico = 0
    }

    private fun coletar(c: Coleta, ant: Amostra, a: Amostra, pkg: String) {
        val d = Sensores.delta(ant, a)
        val emFoco = a.foco.contains(pkg)
        val f = d.fps
        if (f != null && emFoco && f >= 1) c.fps.add(f)
        if (emFoco) {
            d.nucleoMax?.let { c.core.add(it) }
            a.gpu?.let { c.gpu.add(it) }
            if (a.freqs.isNotEmpty()) c.freq.add(a.freqs.max())
        }
        val tc = Sensores.tempCpu(a)
        if (tc != null && tc > c.picoCpu) c.picoCpu = tc
        val b = Sensores.bateria(this)
        if (b.tempC > c.picoBat) c.picoBat = b.tempC
        val st = Sensores.statusTermico(this)
        if (st > c.termico) c.termico = st
    }

    private fun resultado(c: Coleta, pkg: String, nome: String): JSONObject {
        val o = JSONObject().put("data", agora()).put("pkg", pkg).put("nome", nome)
        val (_, maxHz) = Sensores.tela(this)
        var media = 0.0
        if (c.fps.size >= 3) {
            media = c.fps.average()
            val ord = c.fps.sorted()
            val pior = ord[Math.max(0, (ord.size * 0.05).toInt())]
            o.put("fps", Math.round(media * 10) / 10.0).put("min", Math.round(pior * 10) / 10.0)
            o.put("estab", Math.min(100, Math.round(pior / media * 100).toInt()))
        }
        val core = if (c.core.isNotEmpty()) c.core.average() else 0.0
        o.put("core", core.toInt())
        val gpu = if (c.gpu.isNotEmpty()) c.gpu.average() else -1.0
        if (gpu >= 0) o.put("gpu", gpu.toInt())
        var queda = 0
        if (c.freq.size >= 8) {
            val ini = c.freq.take(3).average(); val fim = c.freq.takeLast(3).average()
            if (ini > 0) queda = ((ini - fim) / ini * 100).toInt().coerceAtLeast(0)
        }
        o.put("queda", queda).put("picoCpu", Math.round(c.picoCpu)).put("picoBat", Math.round(c.picoBat * 10) / 10.0)
        val garg = when {
            media > 0 && media >= maxHz * 0.93 -> "TELA"
            c.termico >= 3 || queda >= 25 -> "TERMICO"
            gpu >= 85 -> "GPU"
            core >= 85 -> "CPU"
            media > 0 || c.core.isNotEmpty() -> "LIMITE"
            else -> ""
        }
        o.put("gargalo", garg)
        return o
    }

    private fun resumo(o: JSONObject): String {
        val p = ArrayList<String>()
        if (o.has("fps")) p.add(String.format(java.util.Locale.US, "FPS %.1f (pior %.1f)", o.getDouble("fps"), o.getDouble("min")))
        p.add(String.format(java.util.Locale.US, "bateria ate %.1f C", o.optDouble("picoBat", 0.0)))
        val g = o.optString("gargalo")
        if (g.isNotEmpty()) p.add("gargalo: $g")
        return p.joinToString(" | ")
    }

    // ------------------------------------------------------------
    // Benchmark
    // ------------------------------------------------------------
    private fun bench(it: Intent) {
        val pkg = it.getStringExtra("pkg") ?: return
        val nome = it.getStringExtra("nome") ?: pkg
        val seg = it.getIntExtra("seg", 60)
        val atraso = it.getIntExtra("atraso", 25)
        Jogos.abrirRoot(this, pkg)
        for (s in atraso downTo 1) {
            if (parar) return
            atualizar("Benchmark", "Entre na partida: a medicao de $nome comeca em $s s")
            Thread.sleep(1000)
        }
        vibrar()
        val c = Coleta()
        var ant = Sensores.ler(pkg)
        val fim = System.currentTimeMillis() + seg * 1000L
        while (System.currentTimeMillis() < fim && !parar) {
            Thread.sleep(1000)
            val a = Sensores.ler(pkg)
            coletar(c, ant, a, pkg); ant = a
            val f = c.fps.lastOrNull()
            val resta = (fim - System.currentTimeMillis()) / 1000
            atualizar("Benchmark", "Medindo $nome... ${resta}s" + if (f != null) String.format(java.util.Locale.US, "  |  %.0f FPS", f) else "")
        }
        val o = resultado(c, pkg, nome).put("rotulo", it.getStringExtra("rotulo") ?: "")
        Store.adicionar("bench.json", o, 300)
        vibrar()
        atualizar("Benchmark concluido", nome + ": " + resumo(o))
    }

    // ------------------------------------------------------------
    // Sessao do Modo Jogo
    // ------------------------------------------------------------
    private fun sessao(it: Intent) {
        val pkg = it.getStringExtra("pkg") ?: return
        val nome = it.getStringExtra("nome") ?: pkg
        val perfil = it.getStringExtra("perfil") ?: "Manter"
        val protecao = it.getBooleanExtra("protecao", false)
        val turbo = it.getBooleanExtra("turbo", false)
        val prender = it.getBooleanExtra("prender", false)
        val medir = it.getBooleanExtra("medir", false)
        val u = Undo.sessao
        val inicio = System.currentTimeMillis()

        if (it.getBooleanExtra("pausar", false)) {
            val zen = Sh.setting("global", "zen_mode")
            u.comando("dnd", "cmd notification set_dnd priority", if (zen.isEmpty() || zen == "0") "cmd notification set_dnd off" else "cmd notification set_dnd priority")
            try {
                if (ContentResolver.getMasterSyncAutomatically()) { ContentResolver.setMasterSyncAutomatically(false); Store.putBool("sync_pausada", true) }
            } catch (e: Exception) { }
        }
        if (it.getBooleanExtra("ram", false)) Sh.root("am kill-all 2>/dev/null; sync; echo 3 > /proc/sys/vm/drop_caches", 30)
        if (perfil != "Manter") Cpu.aplicarPerfil(perfil, 100, u)
        if (turbo) {
            Fps.otimizarPacote(pkg, u)
            val (_, maxHz) = Sensores.tela(this)
            if (maxHz > 61) {
                val v = String.format(java.util.Locale.US, "%.1f", maxHz)
                u.setting("system", "peak_refresh_rate", v)
                u.setting("system", "min_refresh_rate", v)
            }
            if (Sh.root("cmd wifi force-low-latency-mode enabled 2>&1", 15).ok) u.registrar("wifi:lowlat:s", "cmd wifi force-low-latency-mode disabled")
        }
        Store.putStr("ultimo_jogo", pkg)
        Log2.registrar("Modo Jogo iniciado: $pkg perfil $perfil turbo $turbo")

        atualizar("Modo Jogo", "Abrindo $nome...")
        Jogos.abrirRoot(this, pkg)

        val c = Coleta()
        var ant = Sensores.ler(pkg)
        var viuJogo = false
        var semJogoDesde = 0L
        val pidsPresos = HashSet<String>()
        var quentes = 0
        var protegeu = false
        var ultimaLimpeza = 0L
        var limpezas = 0
        val mascara = if (prender) Cpu.mascaraNucleosGrandes() else null
        val limite = Store.int("limite_bateria", 44)

        while (!parar) {
            Thread.sleep(2000)
            val a = Sensores.ler(pkg)
            val vivo = a.pids.isNotBlank()
            if (vivo) {
                viuJogo = true; semJogoDesde = 0L
                if (mascara != null) for (p in a.pids.split(Regex("\\s+"))) if (p.isNotBlank() && pidsPresos.add(p)) Sh.root("taskset -ap ${mascara.first} $p 2>/dev/null", 10)
                coletar(c, ant, a, pkg)
            } else {
                if (semJogoDesde == 0L) semJogoDesde = System.currentTimeMillis()
                val lim = if (viuJogo) 15000L else 120000L
                if (System.currentTimeMillis() - semJogoDesde > lim) break
            }
            ant = a
            // protecao termica
            val bat = Sensores.bateria(this).tempC
            if (protecao && !protegeu && bat >= limite) {
                quentes++
                if (quentes >= 2) { Cpu.aplicarPerfil("Frio", 85, u); protegeu = true }
            } else if (bat < limite) quentes = 0
            // memoria
            if (turbo && System.currentTimeMillis() - ultimaLimpeza > 60000) {
                val mi = Sensores.memoria(this)
                if (mi.availMem < mi.totalMem / 10 || mi.lowMemory) { Sh.root("sync; echo 3 > /proc/sys/vm/drop_caches", 20); limpezas++; ultimaLimpeza = System.currentTimeMillis() }
            }
            val d = Sensores.delta(ant, a)
            val f = if (medir) c.fps.lastOrNull() else null
            val min = (System.currentTimeMillis() - inicio) / 60000
            val txt = StringBuilder()
            if (f != null) txt.append(String.format(java.util.Locale.US, "%.0f FPS  |  ", f))
            txt.append(String.format(java.util.Locale.US, "bateria %.1f C", bat))
            Sensores.tempCpu(a)?.let { t -> txt.append(String.format(java.util.Locale.US, "  |  CPU %.0f C", t)) }
            a.gpu?.let { g -> txt.append("  |  GPU $g%") }
            d.nucleoMax?.let { m -> txt.append("  |  nucleo $m%") }
            if (protegeu) txt.append("\nProtecao termica ATIVA: perfil Equilibrado frio")
            txt.append("\n$min min de jogo. Feche o jogo normalmente para restaurar tudo.")
            atualizar(nome, txt.toString())
        }

        val o = resultado(c, pkg, nome)
        if (!medir) { o.remove("fps"); o.remove("min"); o.remove("estab") }
        o.put("dur", ((System.currentTimeMillis() - inicio) / 60000).toInt())
        o.put("perfil", perfil).put("turbo", turbo).put("protegeu", protegeu).put("limpezas", limpezas)
        Store.adicionar("sessoes.json", o, 200)
        restaurarTudo()
        vibrar()
        atualizar("Sessao encerrada", nome + ": " + resumo(o) + "\nTudo restaurado ao normal.")
        Log2.registrar("Modo Jogo encerrado: $pkg " + o.toString())
    }
}
