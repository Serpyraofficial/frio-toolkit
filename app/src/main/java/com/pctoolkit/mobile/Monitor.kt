package com.pctoolkit.mobile

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Conversa simples com o servidor (sem travar a tela: chame fora da thread principal). */
object Servidor {
    fun http(metodo: String, rota: String, corpo: String?, admin: String? = null): Pair<Int, String> {
        val c = URL(Vendedor.SERVIDOR + rota).openConnection() as HttpURLConnection
        c.requestMethod = metodo; c.connectTimeout = 12000; c.readTimeout = 15000
        c.setRequestProperty("content-type", "application/json")
        if (admin != null) c.setRequestProperty("x-admin", admin)
        if (corpo != null) { c.doOutput = true; c.outputStream.use { it.write(corpo.toByteArray()) } }
        val code = c.responseCode
        val t = try { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
        c.disconnect()
        return Pair(code, t)
    }
}

// ============================================================
// Relatorio de erros: o app avisa o servidor quando algo falha no celular do cliente
// ============================================================
object Relatorio {
    private const val CHAVE = "relatorio_pendente"
    private var anterior: Thread.UncaughtExceptionHandler? = null

    /** Instala o "pegador" de travamentos. Chamado ao abrir o app. */
    fun instalar() {
        if (anterior != null) return
        anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { guardar("crash", "travou (${t.name.take(20)})", e) } catch (x: Throwable) { }
            anterior?.uncaughtException(t, e)
        }
    }

    fun falha(onde: String, e: Throwable) = guardar("falha", onde, e)

    fun falha(onde: String, msg: String) {
        val o = JSONObject().put("tipo", "falha").put("onde", onde.take(60)).put("msg", msg.take(200)).put("em", System.currentTimeMillis())
        adicionar(o)
    }

    private fun guardar(tipo: String, onde: String, e: Throwable) {
        val pilha = e.stackTrace.filter { it.className.startsWith("com.pctoolkit") }.take(6).joinToString("\n") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        val o = JSONObject().put("tipo", tipo).put("onde", onde.take(60)).put("msg", (e.javaClass.simpleName + ": " + (e.message ?: "")).take(200))
            .put("pilha", pilha).put("em", System.currentTimeMillis())
        adicionar(o)
    }

    @Synchronized
    private fun adicionar(o: JSONObject) {
        val a = try { JSONArray(Store.str(CHAVE).ifEmpty { "[]" }) } catch (e: Exception) { JSONArray() }
        if (a.length() >= 20) return
        a.put(o)
        // commit imediato: no travamento o app fecha logo em seguida
        PcApp.ctx.getSharedPreferences("config", Context.MODE_PRIVATE).edit().putString(CHAVE, a.toString()).commit()
    }

    /** Manda o que estiver guardado (no maximo 1x a cada 10 min). */
    fun enviar() {
        if (!Vendedor.automatico()) return
        val txt = Store.str(CHAVE)
        if (txt.isEmpty() || txt == "[]") return
        val ult = Store.str("relatorio_em").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - ult < 600_000L) return
        Store.putStr("relatorio_em", System.currentTimeMillis().toString())
        try {
            val corpo = JSONObject().put("codigo", Licenca.codigo()).put("versao", BuildConfig.VERSION_NAME)
                .put("modelo", (Build.MANUFACTURER + " " + Build.MODEL).take(40)).put("android", Build.VERSION.SDK_INT).put("erros", JSONArray(txt))
            val (code, _) = Servidor.http("POST", "/erro", corpo.toString())
            if (code in 200..299) Store.putStr(CHAVE, "")
        } catch (e: Exception) { }
    }

    /** Passos do funil de vendas (uma vez por dia por celular). */
    fun evento(tipo: String) {
        val hoje = (System.currentTimeMillis() / 86_400_000L).toString()
        if (Store.str("ev_$tipo") == hoje || !Vendedor.automatico()) return
        Store.putStr("ev_$tipo", hoje)
        Thread { try { Servidor.http("POST", "/evento", JSONObject().put("codigo", Licenca.codigo()).put("tipo", tipo).toString()) } catch (e: Exception) { } }.start()
    }
}

// ============================================================
// Faixa de promocao que corre na tela (o dono muda pelo painel, sem atualizar o app)
// ============================================================
object Promo {
    class Dados(val id: Long, val texto: String, val cupom: String, val cor: String, val ate: Long)

    fun atual(): Dados? {
        val s = Store.str("promo_json"); if (s.isEmpty()) return null
        return try {
            val o = JSONObject(s)
            val d = Dados(o.optLong("id"), o.optString("texto"), o.optString("cupom"), o.optString("cor", "verde"), o.optLong("ate"))
            if (d.texto.isBlank() || (d.ate > 0 && System.currentTimeMillis() > d.ate)) null else d
        } catch (e: Exception) { null }
    }

    /** Busca a promocao no servidor (no maximo 1x a cada 5 min). Devolve true se mudou. */
    fun atualizar(): Boolean {
        if (!Vendedor.automatico()) return false
        val ult = Store.str("promo_em").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - ult < 300_000L) return false
        Store.putStr("promo_em", System.currentTimeMillis().toString())
        return try {
            val (code, t) = Servidor.http("GET", "/promo", null)
            if (code != 200) return false
            val novo = if (JSONObject(t).optString("texto").isBlank()) "" else t
            val mudou = novo != Store.str("promo_json")
            Store.putStr("promo_json", novo)
            mudou
        } catch (e: Exception) { false }
    }

    fun cor(d: Dados) = when (d.cor) { "amarelo" -> T.amarelo; "vermelho" -> T.vermelho; "azul" -> T.azul; else -> T.verde }

    fun restante(d: Dados): String {
        if (d.ate <= 0) return ""
        val min = ((d.ate - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
        return when { min >= 2880 -> "  ·  termina em ${min / 1440} dias"; min >= 120 -> "  ·  termina em ${min / 60} horas"; else -> "  ·  últimos $min minutos!" }
    }

    /** A faixa que corre de uma ponta a outra. Toque: abre o Pro com o cupom já colocado. */
    fun faixa(act: MainActivity): View? {
        val d = atual() ?: return null
        val cor = cor(d)
        val caixa = FrameLayout(act)
        caixa.clipChildren = true
        caixa.background = Ui.forma(act, Ui.alpha(cor, 0x2E), 14, Ui.alpha(cor, 0x88))
        caixa.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 10))
        val tv = TextView(act)
        val txt = "🔥  " + d.texto + (if (d.cupom.isNotEmpty()) "  ·  cupom ${d.cupom}" else "") + restante(d) + "      "
        tv.text = txt
        tv.setTextColor(T.texto); tv.textSize = 14.5f * Letra.escala; tv.typeface = Ui.negrito
        tv.isSingleLine = true
        tv.setHorizontallyScrolling(true)
        caixa.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START))
        caixa.isClickable = true
        caixa.setOnClickListener {
            if (d.cupom.isNotEmpty()) Store.putStr("cupom_pendente", d.cupom)
            act.abrirTela { act.telaPro() }
        }
        caixa.contentDescription = d.texto
        // anima: entra pela direita e sai pela esquerda, sem parar
        caixa.post {
            val larg = caixa.width.toFloat()
            tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val texto = tv.measuredWidth.toFloat()
            val anim = ValueAnimator.ofFloat(larg, -texto)
            anim.duration = ((larg + texto) / act.resources.displayMetrics.density * 16).toLong().coerceIn(6000, 30000)
            anim.repeatCount = ValueAnimator.INFINITE
            anim.interpolator = LinearInterpolator()
            anim.addUpdateListener { tv.translationX = it.animatedValue as Float }
            anim.start()
            caixa.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { anim.cancel() }
            })
        }
        return caixa
    }
}

// ============================================================
// Aviso de venda no celular do dono (sem gastar e-mail)
// ============================================================
object Dono {
    private const val JOB = 4444
    fun ligado() = Store.str("dono_token").isNotEmpty()

    fun agendar(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (!ligado()) { js.cancel(JOB); return }
        if (js.getPendingJob(JOB) != null) return
        try {
            js.schedule(JobInfo.Builder(JOB, ComponentName(ctx, VendaJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60_000L).setPersisted(true).build())
        } catch (e: Exception) { Log2.registrar("job venda: $e") }
    }

    /** Confere vendas novas e avisa. Devolve quantas. */
    fun conferir(ctx: Context): Int {
        val tok = Store.str("dono_token"); if (tok.isEmpty()) return 0
        val desde = Store.str("dono_desde").toLongOrNull() ?: System.currentTimeMillis()
        return try {
            val (code, t) = Servidor.http("GET", "/admin/novas?desde=$desde", null, tok)
            if (code == 401) { notificar(ctx, 7600, "Aviso de venda desligado", "O token do painel mudou. Ligue de novo em Manutenção > Avisos de venda.", null); Store.putStr("dono_token", ""); return 0 }
            if (code != 200) return 0
            val o = JSONObject(t)
            Store.putStr("dono_desde", o.optLong("agora", System.currentTimeMillis()).toString())
            val l = o.optJSONArray("vendas") ?: JSONArray()
            for (i in 0 until l.length()) {
                val v = l.getJSONObject(i)
                val ti = (if (v.optBoolean("presente")) "🎁 Presente vendido: " else "💰 Nova venda: ") + v.optString("valor")
                notificar(ctx, 7700 + (v.optLong("em") % 1000).toInt(), ti, v.optString("plano") + (if (v.optString("cupom").isNotEmpty()) " · cupom " + v.optString("cupom") else "") + " · pedido " + v.optString("numero"), "https://serpyratoolkit.pages.dev/painel")
            }
            l.length()
        } catch (e: Exception) { 0 }
    }

    private fun notificar(ctx: Context, id: Int, t: String, s: String, link: String?) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("vendas", "Avisos de venda", NotificationManager.IMPORTANCE_HIGH))
            val i = if (link != null) Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)) else Intent(ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(ctx, id, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(id, Notification.Builder(ctx, "vendas").setSmallIcon(R.drawable.ic_jogo).setContentTitle(t).setContentText(s)
                .setContentIntent(pi).setAutoCancel(true).setColor(T.verde).build())
        } catch (e: Exception) { }
    }

    fun executar(con: Console) {
        con.titulo("Avisos de venda no meu celular")
        con.linha("Só para o dono do app. A cada Pix pago, chega uma notificação aqui no celular (confere a cada 15 minutos, sem gastar e-mail).", T.texto2)
        if (ligado()) {
            con.ok("Ligado")
            when (con.escolher("O que fazer?", listOf("Conferir agora", "Desligar os avisos"))) {
                0 -> { val n = conferir(con.ctx); if (n == 0) con.info("Nenhuma venda nova desde a última conferida.") else con.ok("$n venda(s) nova(s): veja as notificações") }
                1 -> { Store.putStr("dono_token", ""); agendar(con.ctx); con.ok("Avisos desligados") }
            }
            return
        }
        val tok = con.texto("Token do painel", "O mesmo que você usa para entrar em serpyratoolkit.pages.dev/painel") ?: return
        if (tok.isBlank()) return
        con.linha("Conferindo o token…", T.texto2)
        val (code, t) = try { Servidor.http("GET", "/admin/novas?desde=0", null, tok.trim()) } catch (e: Exception) { Pair(0, "") }
        if (code == 401) { con.erro("Token errado. Confira e tente de novo."); return }
        if (code != 200) { con.erro("Sem conexão com o servidor. Tente de novo."); return }
        Store.putStr("dono_token", tok.trim())
        Store.putStr("dono_desde", (try { JSONObject(t).optLong("agora") } catch (e: Exception) { System.currentTimeMillis() }).toString())
        agendar(con.ctx)
        if (!Acesso.notificacoes()) con.garantirNome("Notificações")
        con.ok("Pronto! A cada venda chega uma notificação aqui (em até 15 minutos).")
        con.info("Dica: deixe o SerpyraToolkit sem restrição de bateria para o aviso não atrasar.")
    }
}

class VendaJob : JobService() {
    override fun onStartJob(p: JobParameters?): Boolean {
        Thread { try { Dono.conferir(applicationContext) } catch (e: Throwable) { }; jobFinished(p, false) }.start()
        return true
    }
    override fun onStopJob(p: JobParameters?): Boolean = true
}

// ============================================================
// Lembrete de Pix que ficou sem pagar
// ============================================================
object PixAbandonado {
    fun conferir(ctx: Context) {
        val id = Store.str("lic_pedido"); if (id.isEmpty()) return
        val em = Store.str("lic_pedido_em").toLongOrNull() ?: return
        if (System.currentTimeMillis() - em < 2 * 3600_000L || Store.str("pix_lembrado") == id) return
        // se pagou, ativa; se nao, lembra uma vez
        try { if (Compra.conferirPendente()) return } catch (e: Exception) { }
        Store.putStr("pix_lembrado", id)
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("pix", "Lembrete do Pix", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(ctx, 7800, Intent(ctx, MainActivity::class.java).putExtra("acao", "pro").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(7800, Notification.Builder(ctx, "pix").setSmallIcon(R.drawable.ic_jogo).setContentTitle("Seu Pix do SerpyraToolkit Pro expirou")
                .setContentText("Toque para gerar outro em segundos. Seu cupom continua valendo.").setContentIntent(pi).setAutoCancel(true).setColor(T.verde).build())
        } catch (e: Exception) { }
    }
}

// ============================================================
// Compartilhar botoes com um amigo (codigo de texto)
// ============================================================
object CompartilharBotoes {
    private const val PREFIXO = "SERPYRA-BOTOES:"

    fun codigo(l: List<Controle>): String {
        val a = JSONArray(); l.forEach { a.put(it.json()) }
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(a.toString().toByteArray()) }
        return PREFIXO + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
    }

    fun ler(texto: String): MutableList<Controle>? {
        val t = texto.trim().substringAfter(PREFIXO, "").trim()
        if (t.isEmpty()) return null
        return try {
            val bytes = android.util.Base64.decode(t, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
            val json = java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
            val a = JSONArray(json)
            val l = ArrayList<Controle>()
            for (i in 0 until a.length().coerceAtMost(80)) l.add(Controle.de(a.getJSONObject(i)))
            l
        } catch (e: Exception) { null }
    }
}
