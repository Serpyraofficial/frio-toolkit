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
    class Info(val codigo: Int, val nome: String, val notas: String, val sha256: String, val tamanho: Long)

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
            val i = Info(o.optInt("versionCode"), o.optString("versionName"), o.optString("notas"), o.optString("sha256"), o.optLong("tamanho"))
            if (i.codigo == atual()) Store.putStr("upd_notas_atual", i.notas)
            if (i.codigo > atual()) { guardar(i); i } else { Store.putStr("upd_codigo", ""); null }
        } catch (e: Exception) { null }
    }

    private fun guardar(i: Info) {
        Store.putStr("upd_codigo", i.codigo.toString()); Store.putStr("upd_nome", i.nome); Store.putStr("upd_notas", i.notas)
        Store.putStr("upd_sha", i.sha256); Store.putStr("upd_tam", i.tamanho.toString())
    }

    /** Versao nova ja conhecida (para o aviso fixo na tela inicial), sem usar a internet. */
    fun pendente(): Info? {
        val c = Store.str("upd_codigo").toIntOrNull() ?: return null
        if (c <= atual()) return null
        return Info(c, Store.str("upd_nome"), Store.str("upd_notas"), Store.str("upd_sha"), Store.str("upd_tam").toLongOrNull() ?: 0L)
    }

    /** Ao abrir ou voltar para o app: confere (no maximo a cada 10 min). Mostra a janela uma vez por versao; o aviso fixo fica na tela inicial. */
    fun aoAbrir(act: MainActivity) {
        val ultima = Store.str("upd_ultima").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - ultima < INTERVALO) return
        Store.putStr("upd_ultima", System.currentTimeMillis().toString())
        Thread {
            val i = verificar() ?: return@Thread
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
        try { baixar(con, arq, i.tamanho) } catch (e: Exception) { con.erro("Falha no download: " + (e.message ?: "sem internet")); arq.delete(); return }
        if (con.cancelado) { arq.delete(); return }
        if (i.sha256.isNotBlank() && !sha256(arq).equals(i.sha256, true)) { con.erro("O arquivo baixado veio corrompido. Tente de novo."); arq.delete(); return }
        val pkg = ctx.packageManager.getPackageArchiveInfo(arq.path, 0)
        if (pkg == null || pkg.packageName != ctx.packageName) { con.erro("O arquivo baixado não é o SerpyraToolkit."); arq.delete(); return }
        con.ok("Download concluído")
        con.linha("")
        con.linha("Agora o Android vai pedir para você confirmar. Toque em Atualizar/Instalar.", T.amarelo)
        con.info("O app fecha sozinho para instalar. Depois aparece a notificação \"Atualizado\": toque nela (ou abra o app) para ver as novidades. Seu Pro continua ativo.")
        try { instalar(ctx, arq) } catch (e: Exception) { con.erro("Não consegui abrir o instalador: " + e.message) }
    }

    private fun baixar(con: Console, destino: File, esperado: Long) {
        val c = URL(Vendedor.SERVIDOR.trimEnd('/') + "/apk").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        if (c.responseCode != 200) throw IllegalStateException("servidor respondeu ${c.responseCode}")
        val total = if (c.contentLengthLong > 0) c.contentLengthLong else esperado
        var lido = 0L; var ultimo = -1
        c.inputStream.use { inp -> destino.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (con.cancelado) break
                val n = inp.read(buf); if (n < 0) break
                out.write(buf, 0, n); lido += n
                if (total > 0) { val pct = (lido * 100 / total).toInt() / 25 * 25; if (pct != ultimo && pct in 25..75) { ultimo = pct; con.info("$pct%") } }
            }
        } }
        c.disconnect()
        if (total > 0 && lido < total && !con.cancelado) throw IllegalStateException("download incompleto")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { val b = ByteArray(64 * 1024); while (true) { val n = it.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun instalar(ctx: Context, arq: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("SerpyraToolkit.apk", 0, arq.length()).use { out -> arq.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pend = PendingIntent.getBroadcast(ctx, id, Intent(ctx, AtualizacaoReceiver::class.java).setPackage(ctx.packageName), flags)
            s.commit(pend.intentSender)
        }
    }
}

/** Recebe o resultado do instalador do Android e abre a tela de confirmacao. */
class AtualizacaoReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val conf = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else i.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                val act = Atualizador.tela?.get()
                try {
                    if (act != null && !act.isFinishing) act.startActivity(conf) else c.startActivity(conf.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { Toast.makeText(c, "Abra o app de novo para concluir a atualização.", Toast.LENGTH_LONG).show() }
            }
            PackageInstaller.STATUS_SUCCESS -> { }
            PackageInstaller.STATUS_FAILURE_ABORTED -> Toast.makeText(c, "Atualização cancelada.", Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(c, "A atualização não foi instalada: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erro"), Toast.LENGTH_LONG).show()
        }
    }
}

/** Confere a versao em segundo plano. */
class AtualizacaoJob : android.app.job.JobService() {
    override fun onStartJob(p: android.app.job.JobParameters?): Boolean {
        Thread { try { Atualizador.verificar()?.let { Atualizador.notificar(applicationContext, it) } } catch (e: Throwable) { }; jobFinished(p, false) }.start()
        return true
    }
    override fun onStopJob(p: android.app.job.JobParameters?): Boolean = true
}

/** Depois que a atualizacao instala: o Android nao deixa reabrir sozinho, entao avisa com uma notificacao. */
class AtualizadoReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
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
