package com.pctoolkit.mobile

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Atualizacao pelo proprio app: confere a versao no servidor, baixa o APK novo e pede
 * a autorizacao do usuario para instalar por cima. Configuracoes e a licenca Pro continuam.
 */
object Atualizador {
    class Info(val codigo: Int, val nome: String, val notas: String, val sha256: String, val tamanho: Long, val arquivo: String = "")

    private const val INTERVALO = 45_000L   // confere sempre que o app abre ou volta (no maximo 1x a cada 45 s)
    private const val JOB = 4343

    /** Confere em segundo plano a cada 2 horas (com internet) e avisa por notificacao, mesmo com o app fechado. */
    fun agendar(ctx: Context) {
        try {
            val js = ctx.getSystemService(android.app.job.JobScheduler::class.java)
            if (js.getPendingJob(JOB) != null) return
            js.schedule(android.app.job.JobInfo.Builder(JOB, android.content.ComponentName(ctx, AtualizacaoJob::class.java))
                .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY).setPeriodic(2 * 3_600_000L).setPersisted(true).build())
        } catch (e: Exception) { Log2.registrar("job atualizacao: $e") }
    }

    /** Notificacao do sistema: "Atualizacao disponivel". Uma vez por versao. */
    fun notificar(ctx: Context, i: Info) {
        if (Store.str("upd_notificada") == i.codigo.toString()) return
        Store.putStr("upd_notificada", i.codigo.toString())
        try {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel("atualizacao", "Atualizações do app", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val abrir = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java).putExtra("acao", "atualizar").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val txt = i.notas.lines().firstOrNull { it.isNotBlank() }?.removePrefix("- ") ?: "Toque para atualizar. Seu Pro continua ativo."
            nm.notify(7273, android.app.Notification.Builder(ctx, "atualizacao").setSmallIcon(R.drawable.ic_jogo)
                .setContentTitle("SerpyraToolkit ${i.nome} disponível").setContentText(txt)
                .setStyle(android.app.Notification.BigTextStyle().bigText(txt + "\nToque para atualizar."))
                .setContentIntent(abrir).setAutoCancel(true).setColor(T.verde).build())
        } catch (e: Exception) { }
    }
    @Volatile var tela: WeakReference<Activity>? = null
    /** O app esta aberto na tela agora? (nao atualiza enquanto a pessoa usa) */
    @Volatile var visivel = false
    private const val JOB_LOGO = 4344

    // ---------------- atualizacao sozinha (sem a pessoa instalar) ----------------

    fun automatica(): Boolean = Store.bool("upd_auto", true)

    /** Pede uma tentativa daqui a ~1 minuto (ex.: a pessoa acabou de sair do app e ha versao nova). */
    fun agendarLogo(ctx: Context) {
        if (!automatica()) return
        try {
            val js = ctx.getSystemService(android.app.job.JobScheduler::class.java)
            js.schedule(android.app.job.JobInfo.Builder(JOB_LOGO, android.content.ComponentName(ctx, AtualizacaoJob::class.java))
                .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(60_000L).setOverrideDeadline(15 * 60_000L).build())
        } catch (e: Exception) { }
    }

    /** Algo do app esta rodando por cima de um jogo (botoes, mira, modo jogo)? Entao nao fecha o app agora. */
    @Suppress("DEPRECATION")
    fun emUso(ctx: Context): Boolean {
        if (visivel) return true
        return try {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            am.getRunningServices(60).any { it.service.packageName == ctx.packageName && it.foreground }
        } catch (e: Exception) { false }
    }

    /** Pode instalar sem perguntar nada a pessoa? */
    fun podeSilencioso(ctx: Context): Boolean =
        Sh.rootConhecido() || esperarShizuku() || (Build.VERSION.SDK_INT >= 31 && ctx.packageManager.canRequestPackageInstalls())

    private fun esperarShizuku(): Boolean {
        for (i in 0 until 15) { if (Priv.pronto()) return true; if (!Priv.rodando() && i > 4) return false; Thread.sleep(200) }
        return Priv.pronto()
    }

    /** Baixa (se ainda nao baixou) e confere o APK. Devolve o arquivo pronto ou null. */
    fun baixarSilencioso(ctx: Context, i: Info): File? {
        val arq = File(ctx.filesDir, "atualizacao-${i.codigo}.apk")
        if (arq.exists() && (i.sha256.isBlank() || sha256(arq).equals(i.sha256, true))) return arq
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("atualizacao-") }?.forEach { it.delete() }
        return try {
            val tmp = File(ctx.filesDir, "atualizacao-${i.codigo}.parte")
            if (!baixarRetomando(tmp, i, { false }, { })) return null
            if (i.sha256.isNotBlank() && !sha256(tmp).equals(i.sha256, true)) { tmp.delete(); return null }
            val pkg = ctx.packageManager.getPackageArchiveInfo(tmp.path, 0)
            if (pkg == null || pkg.packageName != ctx.packageName) { tmp.delete(); return null }
            if (!tmp.renameTo(arq)) { tmp.delete(); return null }
            arq
        } catch (e: Exception) { null }
    }

    /**
     * Instala por cima sem pedir confirmacao. Configuracoes, botoes e Pro continuam (mesma assinatura).
     * reabrir = abre o app de novo no fim (quando a pessoa pediu a atualizacao com o app aberto).
     * Devolve true se a instalacao foi feita (ou entregue ao Android para fazer sozinho).
     */
    fun instalarSilencioso(ctx: Context, arq: File, reabrir: Boolean = false): Boolean {
        val pkg = ctx.packageName
        val depois = if (reabrir) " && am start -n $pkg/${MainActivity::class.java.name} >/dev/null 2>&1" else ""
        if (Sh.rootConhecido() || esperarShizuku()) {
            Store.putBool("upd_silenciosa", true)
            val tam = arq.length()
            // o APK vai pela entrada do comando: o shell do Shizuku/root nao enxerga a pasta privada do app
            for (cmd in listOf("pm install -r -i $pkg -S $tam$depois", "pm install -r -S $tam$depois")) {
                val r = comEntrada(cmd, arq)
                if (r.ok && r.out.contains("Success")) { registrar("instalada pelo Shizuku"); return true }
                val txt = (r.out + " " + r.err).trim()
                Log2.registrar("pm install: ${r.code} $txt")
                if (txt.contains("USER_RESTRICTED")) Store.putBool("upd_falta_usb", true)
                registrar("Shizuku não conseguiu instalar: " + txt.take(140), true)
                if (txt.contains("USER_RESTRICTED")) break
            }
        }
        if (Build.VERSION.SDK_INT >= 31 && ctx.packageManager.canRequestPackageInstalls()) {
            // por aqui o Android nao reabre o app: se a pessoa pediu com o app aberto, avisa com notificacao no fim
            Store.putBool("upd_silenciosa", !reabrir)
            return try { sessao(ctx, arq, silencioso = true); registrar("enviada ao Android para instalar"); true } catch (e: Exception) { registrar("o Android recusou: ${e.message}", true); false }
        }
        Store.putBool("upd_silenciosa", false)
        return false
    }

    /** Roda um comando privilegiado (Shizuku ou root) mandando o arquivo pela entrada padrao. */
    private fun comEntrada(cmd: String, arq: File): Sh.R {
        return try {
            val p: Process = if (!Sh.rootConhecido()) Priv.processoComEntrada(cmd) ?: return Sh.R(-1, "", "sem Shizuku")
                else ProcessBuilder("su", "-c", cmd).start()
            val o = StringBuilder(); val e = StringBuilder()
            val to = Thread { try { p.inputStream.bufferedReader().forEachLine { synchronized(o) { o.append(it).append('\n') } } } catch (x: Exception) { } }
            val te = Thread { try { p.errorStream.bufferedReader().forEachLine { synchronized(e) { e.append(it).append('\n') } } } catch (x: Exception) { } }
            to.start(); te.start()
            try { p.outputStream.use { out -> arq.inputStream().use { it.copyTo(out, 64 * 1024) } } } catch (x: Exception) { }
            val fim = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
            if (!fim) p.destroy()
            to.join(2000); te.join(2000)
            Sh.R(if (fim) p.exitValue() else -2, synchronized(o) { o.toString().trim() }, synchronized(e) { e.toString().trim() })
        } catch (x: Throwable) { Sh.R(-1, "", x.message ?: "erro") }
    }

    fun xiaomi(): Boolean = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

    /** Guarda o resultado da ultima tentativa (aparece em Menu > Atualizar sozinho) e manda falhas ao relatorio. */
    fun registrar(res: String, falhou: Boolean = false) {
        val h = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date())
        Store.putStr("upd_log", "$h · $res")
        if (falhou) try { Relatorio.falha("Atualizar sozinho", res.take(180)) } catch (e: Throwable) { }
    }

    /**
     * A pessoa saiu do app: confere se ha versao nova e, se houver, instala ja (em segundos),
     * para que na proxima vez que abrir ja esteja atualizado. O job fica de reserva.
     */
    fun aoSair(ctx: Context) {
        if (!automatica()) return
        agendarLogo(ctx)
        Thread {
            try {
                Thread.sleep(3500)
                if (visivel) return@Thread
                val ultima = Store.str("upd_ultima").toLongOrNull() ?: 0L
                val i = pendente() ?: (if (System.currentTimeMillis() - ultima > 30_000L) verificar() else null) ?: return@Thread
                if (!podeSilencioso(ctx)) { registrar("este celular ainda não deixa atualizar sozinho"); return@Thread }
                val arq = baixarSilencioso(ctx, i) ?: run { registrar("não consegui baixar a versão ${i.nome}", true); return@Thread }
                if (visivel || emUso(ctx)) { registrar("versão ${i.nome} pronta; espera você parar de usar"); return@Thread }
                instalarSilencioso(ctx, arq)
            } catch (e: Throwable) { registrar("erro: ${e.message}", true) }
        }.start()
    }

    /** Tentativa em segundo plano: chamada pelo job. So instala se a pessoa nao estiver usando o app. */
    fun tentarSozinho(ctx: Context) {
        val i = verificar() ?: pendente() ?: return
        if (!automatica() || !podeSilencioso(ctx)) { notificar(ctx, i); return }
        val arq = baixarSilencioso(ctx, i) ?: run { registrar("não consegui baixar a versão ${i.nome}", true); return }
        if (emUso(ctx)) { registrar("versão ${i.nome} pronta; espera você parar de usar"); return }
        if (!instalarSilencioso(ctx, arq)) notificar(ctx, i)
    }

    fun atual(): Int = BuildConfig.VERSION_CODE

    /** Consulta o servidor. Devolve a versao nova, ou null se ja esta atualizado / sem internet. */
    fun verificar(): Info? {
        if (!Vendedor.automatico()) return null
        return try {
            val c = URL(Vendedor.SERVIDOR.trimEnd('/') + "/versao?t=" + System.currentTimeMillis() / 60000).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 15000
            if (c.responseCode != 200) { c.disconnect(); return null }
            val o = org.json.JSONObject(c.inputStream.bufferedReader().readText()); c.disconnect()
            Store.putStr("upd_ultima", System.currentTimeMillis().toString())
            val i = Info(o.optInt("versionCode"), o.optString("versionName"), o.optString("notas"), o.optString("sha256"), o.optLong("tamanho"), o.optString("arquivo"))
            if (i.codigo == atual()) Store.putStr("upd_notas_atual", i.notas)
            if (i.codigo > atual()) { guardar(i); i } else { Store.putStr("upd_codigo", ""); null }
        } catch (e: Exception) { null }
    }

    private fun guardar(i: Info) {
        Store.putStr("upd_codigo", i.codigo.toString()); Store.putStr("upd_nome", i.nome); Store.putStr("upd_notas", i.notas)
        Store.putStr("upd_sha", i.sha256); Store.putStr("upd_tam", i.tamanho.toString()); Store.putStr("upd_arq", i.arquivo)
    }

    /** Versao nova ja conhecida (para o aviso fixo na tela inicial), sem usar a internet. */
    fun pendente(): Info? {
        val c = Store.str("upd_codigo").toIntOrNull() ?: return null
        if (c <= atual()) return null
        return Info(c, Store.str("upd_nome"), Store.str("upd_notas"), Store.str("upd_sha"), Store.str("upd_tam").toLongOrNull() ?: 0L, Store.str("upd_arq"))
    }

    /** Ao abrir ou voltar para o app: confere (no maximo a cada 10 min). Mostra a janela uma vez por versao; o aviso fixo fica na tela inicial. */
    fun aoAbrir(act: MainActivity) {
        val ultima = Store.str("upd_ultima").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - ultima < INTERVALO) return
        Store.putStr("upd_ultima", System.currentTimeMillis().toString())
        Thread {
            val i = verificar() ?: return@Thread
            val sozinho = automatica() && podeSilencioso(act)
            Store.putBool("upd_sozinho_ok", sozinho)
            if (sozinho) {
                // baixa agora em silencio; instala quando a pessoa sair do app (onStop agenda)
                baixarSilencioso(act.applicationContext, i)
                act.runOnUiThread { act.atualizarInicio() }
                return@Thread
            }
            act.runOnUiThread {
                act.atualizarInicio()
                if (Store.str("upd_avisada") != i.codigo.toString()) { Store.putStr("upd_avisada", i.codigo.toString()); oferecer(act, i) }
            }
        }.start()
    }

    /** Primeira abertura depois de atualizar: mostra o que mudou. */
    fun novidadesSeAtualizou(act: MainActivity) {
        val vista = Store.int("versao_vista", 0)
        if (vista == atual()) return
        Store.putInt("versao_vista", atual())
        if (vista == 0) {
            // instalacao nova (nao foi atualizacao): nao mostra novidades
            val pi = try { act.packageManager.getPackageInfo(act.packageName, 0) } catch (e: Exception) { null }
            if (pi == null || pi.firstInstallTime == pi.lastUpdateTime) return
        }
        Thread {
            var notas = Store.str("upd_notas").takeIf { Store.str("upd_codigo") == atual().toString() } ?: ""
            if (notas.isBlank()) { verificar(); notas = Store.str("upd_notas_atual") }
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                AlertDialog.Builder(act).setTitle("Atualizado para a versão ${BuildConfig.VERSION_NAME} ✓")
                    .setMessage((if (notas.isNotBlank()) "O que tem de novo:\n\n" + notas.trim() + "\n\n" else "") + "Suas configurações e o Pro continuam valendo.")
                    .setPositiveButton("Legal", null).show()
            }
        }.start()
    }

    fun oferecer(act: MainActivity, i: Info) {
        if (act.isFinishing) return
        val msg = "Versão ${i.nome} disponível (você tem a ${BuildConfig.VERSION_NAME})." +
            (if (i.notas.isNotBlank()) "\n\nNovidades:\n" + i.notas.trim() else "") +
            "\n\nSuas configurações e o Pro continuam valendo."
        AlertDialog.Builder(act).setTitle("Atualização disponível").setMessage(msg)
            .setPositiveButton("Atualizar") { _, _ -> act.executar("Atualizar o app") { atualizar(it, i) } }
            .setNegativeButton("Depois") { _, _ -> act.atualizarInicio() }
            .show()
    }

    /** Liga/desliga a atualizacao sozinha e explica como esta neste celular. */
    fun configurar(con: Console) {
        con.titulo("Atualizar sozinho")
        val ctx = con.ctx
        con.info("Ligado, o app baixa as versões novas e instala sozinho quando você não está usando. Ao abrir, já está atualizado. Configurações, botões e Pro continuam iguais.")
        con.linha("")
        val pode = podeSilencioso(ctx)
        when {
            Sh.rootConhecido() || Priv.pronto() -> con.ok("Este celular atualiza sozinho (pelo modo automático).")
            pode -> con.ok("Este celular atualiza sozinho (Android ${Build.VERSION.RELEASE}).")
            Build.VERSION.SDK_INT >= 31 -> {
                con.aviso("Falta uma autorização do Android para o app se atualizar.")
                if (con.ask("Abrir a tela para permitir agora?", "Permitir", "Depois")) {
                    con.abrirEEsperar(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)))
                    if (ctx.packageManager.canRequestPackageInstalls()) con.ok("Pronto. Agora atualiza sozinho.") else con.aviso("Ainda sem a autorização. Você recebe um aviso quando sair versão nova.")
                }
            }
            else -> con.aviso("No Android ${Build.VERSION.RELEASE}, atualizar sozinho precisa do modo automático (Shizuku) ligado. Sem ele, você recebe um aviso quando sair versão nova.")
        }
        val log = Store.str("upd_log")
        if (log.isNotBlank()) con.info("Última tentativa: $log")
        if (xiaomi()) {
            con.linha("")
            con.aviso("Na Xiaomi/Redmi/Poco: ligue também “Instalar via USB” em Opções do desenvolvedor. Sem ela, a Xiaomi bloqueia a atualização sozinha.")
            if (con.ask("Abrir as Opções do desenvolvedor agora?", "Abrir", "Depois")) con.abrirEEsperar(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        con.linha("")
        val ligado = automatica()
        val novo = con.ask("Atualização automática está ${if (ligado) "LIGADA" else "DESLIGADA"}. O que prefere?", "Ligada", "Desligada")
        Store.putBool("upd_auto", novo)
        con.ok(if (novo) "Atualização automática ligada." else "Atualização automática desligada. Você será avisado das versões novas.")
    }

    /** Tela "Procurar atualização" (menu Sobre). */
    fun procurar(con: Console) {
        con.titulo("Atualização do app")
        con.info("Versão instalada: ${BuildConfig.VERSION_NAME}")
        con.linha("Procurando versão nova...", T.texto2)
        val i = verificar()
        if (i == null) { con.ok("Você já está na versão mais recente."); return }
        con.ok("Versão ${i.nome} disponível")
        if (i.notas.isNotBlank()) { con.linha(""); con.linha("Novidades:", T.azul); i.notas.trim().lines().forEach { con.item(it.removePrefix("- ").removePrefix("• ")) } }
        if (!con.ask("Baixar e instalar a versão ${i.nome} agora?", "Atualizar", "Agora não", importante = true)) return
        atualizar(con, i)
    }

    fun atualizar(con: Console, i: Info) {
        val ctx = con.ctx
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            con.aviso("O Android precisa da sua autorização para o app instalar a atualização.")
            con.info("Na tela que vai abrir, ative \"Permitir desta fonte\" e volte.")
            con.abrirEEsperar(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)))
            if (!ctx.packageManager.canRequestPackageInstalls()) { con.erro("Sem essa autorização não dá para atualizar. Tente de novo quando quiser."); return }
        }
        con.linha("Baixando a versão ${i.nome}...", T.texto2)
        val arq = File(ctx.cacheDir, "atualizacao.apk")
        try { baixar(con, arq, i) } catch (e: Exception) { con.erro("Falha no download: " + (e.message ?: "sem internet")); arq.delete(); return }
        if (con.cancelado) { arq.delete(); return }
        if (i.sha256.isNotBlank() && !sha256(arq).equals(i.sha256, true)) { con.erro("O arquivo baixado veio corrompido. Tente de novo."); arq.delete(); return }
        val pkg = ctx.packageManager.getPackageArchiveInfo(arq.path, 0)
        if (pkg == null || pkg.packageName != ctx.packageName) { con.erro("O arquivo baixado não é o SerpyraToolkit."); arq.delete(); return }
        con.ok("Download concluído")
        if (podeSilencioso(ctx)) {
            con.linha("")
            con.ok("Instalando sozinho. O app fecha e abre de novo já na versão ${i.nome}. Suas configurações e o Pro continuam.")
            con.sleep(1500)
            val fixo = File(ctx.filesDir, "atualizacao-${i.codigo}.apk"); arq.copyTo(fixo, true); arq.delete()
            if (instalarSilencioso(ctx, fixo, reabrir = true)) return
            con.aviso("Não deu para instalar sozinho desta vez. Vamos pelo instalador do Android.")
            fixo.copyTo(arq, true)
        }
        con.linha("")
        con.linha("Agora o Android vai pedir para você confirmar. Toque em Atualizar/Instalar.", T.amarelo)
        con.info("O app fecha sozinho para instalar. Depois aparece a notificação \"Atualizado\": toque nela (ou abra o app) para ver as novidades. Seu Pro continua ativo.")
        try { instalar(ctx, arq) } catch (e: Exception) { con.erro("Não consegui abrir o instalador: " + e.message) }
    }

    private fun baixar(con: Console, destino: File, i: Info) {
        var ultimo = -1
        val ok = baixarRetomando(destino, i, { con.cancelado }, { pct ->
            val p = pct / 25 * 25
            if (p != ultimo && p in 25..75) { ultimo = p; con.info("$p%") }
        }, { tent -> con.aviso("A conexão caiu. Tentando de novo e continuando de onde parou ($tent de 5)...") })
        if (!ok && !con.cancelado) throw IllegalStateException("a internet caiu várias vezes. Tente de novo num lugar com sinal melhor")
    }

    /**
     * Baixa o APK aguentando internet fraca: ate 5 tentativas e, se a conexao cair no meio,
     * continua de onde parou (o GitHub aceita pedir so o pedaco que falta).
     */
    fun baixarRetomando(destino: File, i: Info, cancelado: () -> Boolean, progresso: (Int) -> Unit, novaTentativa: (Int) -> Unit = {}): Boolean {
        destino.delete()
        val servidor = Vendedor.SERVIDOR.trimEnd('/') + "/apk"
        val direto = if (i.arquivo.isNotBlank()) "https://raw.githubusercontent.com/Serpyraofficial/frio-toolkit/android-app/apk/" + i.arquivo else ""
        for (tent in 1..5) {
            if (cancelado()) return false
            val ja = destino.length()
            val url = when { ja > 0 && direto.isNotEmpty() -> direto; tent % 2 == 0 && direto.isNotEmpty() -> direto; else -> servidor }
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 25000
                if (ja > 0 && url == direto) c.setRequestProperty("Range", "bytes=$ja-")
                val code = c.responseCode
                if (code != 200 && code != 206) { c.disconnect(); throw IllegalStateException("resposta $code") }
                val continuar = code == 206
                val total = if (c.contentLengthLong > 0) (if (continuar) ja + c.contentLengthLong else c.contentLengthLong) else i.tamanho
                var lido = if (continuar) ja else 0L
                c.inputStream.use { inp -> java.io.FileOutputStream(destino, continuar).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelado()) break
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); lido += n
                        if (total > 0) progresso((lido * 100 / total).toInt())
                    }
                } }
                c.disconnect()
                if (cancelado()) return false
                if (total <= 0 || destino.length() >= total) return true
            } catch (e: Exception) { Log2.registrar("download tentativa $tent: $e") }
            if (tent < 5) { novaTentativa(tent + 1); try { Thread.sleep(2000L * tent) } catch (x: InterruptedException) { } }
        }
        return false
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { val b = ByteArray(64 * 1024); while (true) { val n = it.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun instalar(ctx: Context, arq: File) { Store.putBool("upd_silenciosa", false); sessao(ctx, arq, silencioso = false) }

    private fun sessao(ctx: Context, arq: File, silencioso: Boolean) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (silencioso && Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("SerpyraToolkit.apk", 0, arq.length()).use { out -> arq.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pend = PendingIntent.getBroadcast(ctx, id, Intent(ctx, AtualizacaoReceiver::class.java).setPackage(ctx.packageName).putExtra("silencioso", silencioso), flags)
            s.commit(pend.intentSender)
        }
    }
}

/** Recebe o resultado do instalador do Android e abre a tela de confirmacao. */
class AtualizacaoReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (i.getBooleanExtra("silencioso", false)) {
                    // o Android quis confirmacao: nao abre nada por cima do que a pessoa esta fazendo; avisa
                    Atualizador.registrar("o Android pediu confirmação (avisei por notificação)", true)
                    Store.putBool("upd_silenciosa", false)
                    Atualizador.pendente()?.let { Store.putStr("upd_notificada", ""); Atualizador.notificar(c, it) }
                    return
                }
                @Suppress("DEPRECATION")
                val conf = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else i.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                val act = Atualizador.tela?.get()
                try {
                    if (act != null && !act.isFinishing) act.startActivity(conf) else c.startActivity(conf.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { Toast.makeText(c, "Abra o app de novo para concluir a atualização.", Toast.LENGTH_LONG).show() }
            }
            PackageInstaller.STATUS_SUCCESS -> { }
            PackageInstaller.STATUS_FAILURE_ABORTED -> Toast.makeText(c, "Atualização cancelada.", Toast.LENGTH_SHORT).show()
            else -> if (i.getBooleanExtra("silencioso", false)) Atualizador.registrar("o Android não instalou: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erro"), true) else Toast.makeText(c, "A atualização não foi instalada: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erro"), Toast.LENGTH_LONG).show()
        }
    }
}

/** Confere a versao em segundo plano. */
class AtualizacaoJob : android.app.job.JobService() {
    override fun onStartJob(p: android.app.job.JobParameters?): Boolean {
        Thread {
            try { Atualizador.tentarSozinho(applicationContext) } catch (e: Throwable) { }
            try { PixAbandonado.conferir(applicationContext) } catch (e: Throwable) { }
            try { Relatorio.enviar() } catch (e: Throwable) { }
            jobFinished(p, false)
        }.start()
        return true
    }
    override fun onStopJob(p: android.app.job.JobParameters?): Boolean = true
}

/** Depois que a atualizacao instala: o Android nao deixa reabrir sozinho, entao avisa com uma notificacao. */
class AtualizadoReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        c.filesDir.listFiles()?.filter { it.name.startsWith("atualizacao-") }?.forEach { it.delete() }
        Atualizador.agendar(c)
        // atualizou sozinho: sem notificacao; as novidades aparecem quando a pessoa abrir o app
        if (Store.bool("upd_silenciosa", false)) { Store.putBool("upd_silenciosa", false); return }
        try {
            val nm = c.getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel("atualizacao", "Atualizações do app", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val abrir = android.app.PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(7272, android.app.Notification.Builder(c, "atualizacao").setSmallIcon(R.drawable.ic_jogo)
                .setContentTitle("SerpyraToolkit atualizado para a ${BuildConfig.VERSION_NAME} ✓")
                .setContentText("Toque para abrir e ver as novidades. Seu Pro continua ativo.")
                .setContentIntent(abrir).setAutoCancel(true).setColor(T.verde).build())
        } catch (e: Exception) { }
    }
}
