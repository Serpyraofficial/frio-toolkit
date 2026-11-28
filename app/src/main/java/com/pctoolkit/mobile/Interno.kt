package com.pctoolkit.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AndroidUtils
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Modo completo" sem o Shizuku: o proprio app se conecta a Depuracao sem fio do celular (Android 11 ou mais novo)
 * e roda os comandos com o mesmo poder do ADB.
 */
object Interno {
    private class Gerente(ctx: Context) : AbsAdbConnectionManager() {
        private val chave: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            val fk = File(ctx.filesDir, "adb_chave.key"); val fc = File(ctx.filesDir, "adb_cert.der")
            var k: PrivateKey? = null; var c: Certificate? = null
            try {
                if (fk.exists() && fc.exists()) {
                    k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(fk.readBytes()))
                    c = fc.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
                }
            } catch (e: Exception) { k = null; c = null }
            if (k == null || c == null) {
                val g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048, SecureRandom())
                val par = g.generateKeyPair()
                val nome = X500Name("CN=SerpyraToolkit")
                val ini = Date(); val fim = Date(System.currentTimeMillis() + 3650L * 86_400_000L)
                val ext = CertificateExtensions()
                ext.set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(par.public).identifier))
                ext.set("PrivateKeyUsage", PrivateKeyUsageExtension(ini, fim))
                val info = X509CertInfo()
                info.set("version", CertificateVersion(2))
                info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
                info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get("SHA512withRSA")))
                info.set("subject", CertificateSubjectName(nome))
                info.set("key", CertificateX509Key(par.public))
                info.set("validity", CertificateValidity(ini, fim))
                info.set("issuer", CertificateIssuerName(nome))
                info.set("extensions", ext)
                val x = X509CertImpl(info); x.sign(par.private, "SHA512withRSA")
                k = par.private; c = x
                fk.writeBytes(par.private.encoded); fc.writeBytes(x.encoded)
            }
            chave = k!!; cert = c!!
        }
        override fun getPrivateKey(): PrivateKey = chave
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "SerpyraToolkit"
    }

    @Volatile private var g: Gerente? = null
    private fun gerente(ctx: Context): Gerente = g ?: synchronized(this) {
        // o TLS da Depuracao sem fio usa uma parte interna do Android: libera antes
        try { if (Build.VERSION.SDK_INT >= 28) org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("") } catch (e: Throwable) { }
        g ?: Gerente(ctx.applicationContext).also { g = it }
    }

    fun suportado() = Build.VERSION.SDK_INT >= 30
    fun pareado() = Store.bool("adb_pareado", false)
    fun pronto(): Boolean = try { g?.isConnected == true } catch (e: Throwable) { false }

    fun opcoesDevLigadas(ctx: Context) = try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 } catch (e: Exception) { false }
    fun depuracaoSemFioLigada(ctx: Context) = try { Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1 } catch (e: Exception) { false }
    fun podeLigarSozinho(ctx: Context) = ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** Procura a porta anunciada na rede local (pareamento ou conexao). */
    private fun porta(ctx: Context, tipo: String, segundos: Long): Int {
        val p = AtomicInteger(-1); val l = CountDownLatch(1)
        val m = AdbMdns(ctx, tipo) { _, port -> if (port > 0) { p.set(port); l.countDown() } }
        try { m.start(); l.await(segundos, TimeUnit.SECONDS) } catch (e: Throwable) { } finally { try { m.stop() } catch (e: Throwable) { } }
        return p.get()
    }

    /** Pareia com o codigo de 6 digitos. Se a porta nao for dada, descobre sozinho. */
    fun parear(ctx: Context, codigo: String, portaDada: Int = -1): Boolean {
        if (!suportado()) return false
        return try {
            val host = AndroidUtils.getHostIpAddress(ctx)
            val port = if (portaDada > 0) portaDada else porta(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING, 25)
            if (port <= 0) { Log2.registrar("adb: porta de pareamento nao achada"); return false }
            val ok = gerente(ctx).pair(host, port, codigo.filter { it.isDigit() })
            if (ok) { Store.putBool("adb_pareado", true); conectar(ctx); if (pronto()) depoisDeConectar(ctx) }
            ok
        } catch (e: Throwable) { Log2.registrar("adb parear: $e"); Relatorio.falha("modo completo: parear", e); false }
    }

    fun conectar(ctx: Context, timeoutMs: Long = 6000): Boolean {
        if (!suportado() || !pareado()) return false
        if (pronto()) return true
        return try {
            val gm = gerente(ctx)
            var ok = try { gm.connectTls(ctx, timeoutMs) } catch (e: io.github.muntashirakon.adb.AdbPairingRequiredException) { Store.putBool("adb_pareado", false); false }
            if (!ok) { val port = porta(ctx, AdbMdns.SERVICE_TYPE_TLS_CONNECT, timeoutMs / 1000 + 1); if (port > 0) ok = gm.connect(AndroidUtils.getHostIpAddress(ctx), port) }
            ok
        } catch (e: Throwable) { Log2.registrar("adb conectar: $e"); false }
    }

    /** Depois de reiniciar ou trocar de Wi-Fi: religa a Depuracao sem fio e reconecta, sem a pessoa ver nada. */
    fun religar(ctx: Context): Boolean {
        if (!suportado() || !pareado()) return false
        if (pronto()) return true
        if (!Rede.tipo(ctx).equals("Wi-Fi")) return false
        if (!depuracaoSemFioLigada(ctx) && podeLigarSozinho(ctx)) {
            try {
                if (!opcoesDevLigadas(ctx)) Settings.Global.putInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
                Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1)
                Thread.sleep(2500)
            } catch (e: Throwable) { Log2.registrar("adb religar: $e") }
        }
        return conectar(ctx)
    }

    /** Ao conectar pela primeira vez: guarda a permissao que deixa religar sozinho e libera o resto. */
    private fun depoisDeConectar(ctx: Context) {
        exec("pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS", 15)
        Store.putBool("adb_ok_uma_vez", true)
        try { Priv.autoConfigurar(true) } catch (e: Throwable) { }
    }

    private fun abrir(cmd: String): AdbStream? = try { g?.openStream("shell:$cmd") } catch (e: Throwable) { Log2.registrar("adb stream: $e"); null }

    fun exec(cmd: String, timeoutS: Long = 60): Sh.R {
        if (!pronto() && !conectar(PcApp.ctx)) return Sh.R(-1, "", "modo completo desconectado")
        val marca = "__RC" + System.nanoTime() + ":"
        val s = abrir("($cmd) 2>&1; echo \"$marca\$?\"") ?: return Sh.R(-1, "", "sem conexão")
        val out = StringBuilder(); var rc = -2
        val t = Thread {
            try {
                s.openInputStream().bufferedReader().forEachLine { l ->
                    if (l.startsWith(marca)) rc = l.substringAfter(marca).trim().toIntOrNull() ?: -1 else synchronized(out) { out.append(l).append('\n') }
                }
            } catch (e: Exception) { }
        }
        t.start(); t.join(timeoutS * 1000)
        try { s.close() } catch (e: Exception) { }
        return Sh.R(rc, synchronized(out) { out.toString().trim() }, "")
    }

    /** Processo longo (getevent) ou com entrada aberta (pm install -S), no formato que o resto do app ja usa. */
    fun processo(cmd: String): Process? {
        if (!pronto() && !conectar(PcApp.ctx)) return null
        val s = abrir(cmd) ?: return null
        return Fluxo(s)
    }

    private class Fluxo(private val s: AdbStream) : Process() {
        private val ent: InputStream = s.openInputStream()
        private val sai: OutputStream = s.openOutputStream()
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) = sai.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = sai.write(b, off, len)
            override fun flush() = sai.flush()
            // fechar a entrada nao pode fechar a conversa: o resultado ainda vai chegar
            override fun close() { try { sai.flush() } catch (e: Exception) { } }
        }
        override fun getInputStream(): InputStream = ent
        override fun getErrorStream(): InputStream = java.io.ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { while (!s.isClosed) Thread.sleep(80); return 0 }
        override fun exitValue(): Int { if (!s.isClosed) throw IllegalThreadStateException("rodando"); return 0 }
        override fun destroy() { try { s.close() } catch (e: Exception) { } }
    }

    // ---------------- pareamento pela notificacao (funciona em qualquer marca) ----------------
    const val NOTIF = 7900
    fun pedirCodigoNaNotificacao(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pareamento", "Ativar modo completo", NotificationManager.IMPORTANCE_HIGH))
        val ri = RemoteInput.Builder("codigo").setLabel("Código de 6 números").build()
        val pi = PendingIntent.getBroadcast(ctx, NOTIF, Intent(ctx, ParearReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val acao = Notification.Action.Builder(null, "Digitar o código", pi).addRemoteInput(ri).build()
        nm.notify(NOTIF, Notification.Builder(ctx, "pareamento").setSmallIcon(R.drawable.ic_jogo).setColor(T.verde)
            .setContentTitle("Digite aqui o código de pareamento")
            .setContentText("Com a janela do código aberta, puxe esta notificação e toque em Digitar o código.")
            .setStyle(Notification.BigTextStyle().bigText("Com a janela “Parear com código” aberta nas Configurações, puxe esta notificação, toque em “Digitar o código” e escreva os 6 números."))
            .addAction(acao).setOngoing(true).build())
    }

    fun avisar(ctx: Context, titulo: String, texto: String, fixa: Boolean = false) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("pareamento", "Ativar modo completo", NotificationManager.IMPORTANCE_HIGH))
            nm.notify(NOTIF, Notification.Builder(ctx, "pareamento").setSmallIcon(R.drawable.ic_jogo).setColor(T.verde)
                .setContentTitle(titulo).setContentText(texto).setOngoing(fixa).setAutoCancel(!fixa).build())
        } catch (e: Exception) { }
    }
}

class ParearReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val codigo = RemoteInput.getResultsFromIntent(i)?.getCharSequence("codigo")?.toString()?.filter { it.isDigit() } ?: return
        val pr = goAsync()
        Interno.avisar(c, "Conectando…", "Aguarde alguns segundos.", true)
        Thread {
            val ok = codigo.length == 6 && Interno.parear(c.applicationContext, codigo)
            if (ok) Interno.avisar(c, "Modo completo ativado ✓", "Pode voltar ao SerpyraToolkit.")
            else { Interno.avisar(c, "Não deu certo", "Confira o código (ele muda a cada vez) e tente de novo.") ; Interno.pedirCodigoNaNotificacao(c) }
            pr.finish()
        }.start()
    }
}
