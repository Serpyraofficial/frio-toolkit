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
    fun http(metodo: String, rota: String, corpo: String?, admin: String? = null, dono: String? = null): Pair<Int, String> {
        val c = URL(Vendedor.SERVIDOR + rota).openConnection() as HttpURLConnection
        c.requestMethod = metodo; c.connectTimeout = 12000; c.readTimeout = 15000
        c.setRequestProperty("content-type", "application/json")
        if (admin != null) c.setRequestProperty("x-admin", admin)
        if (dono != null) c.setRequestProperty("x-dono", dono)
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

    // tela aberta agora (para o print) e o caminho que a pessoa fez ate o erro
    @Volatile var tela: java.lang.ref.WeakReference<android.app.Activity>? = null
    private val rastro = ArrayDeque<String>()
    @Volatile private var capturando = false

    fun passo(s: String) {
        val t = s.trim().take(60); if (t.isEmpty()) return
        synchronized(rastro) { if (rastro.lastOrNull() == t) return; rastro.addLast(t); while (rastro.size > 12) rastro.removeFirst() }
    }
    private fun rastroAtual(): List<String> = synchronized(rastro) { rastro.toList() }

    /** O dono do celular pode desligar o print nos ajustes. */
    fun printLigado() = Store.bool("erro_print", true)

    /** Instala o "pegador" de travamentos. Chamado ao abrir o app. */
    fun instalar() {
        if (anterior != null) return
        anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                guardar("crash", "travou (${t.name.take(20)})", e)
                // tenta mandar antes de o app fechar (no maximo 2,5 s)
                val envio = Thread { try { enviar(true) } catch (x: Throwable) { } }
                envio.start(); envio.join(2500)
            } catch (x: Throwable) { }
            anterior?.uncaughtException(t, e)
        }
    }

    fun falha(onde: String, e: Throwable) { guardar("falha", onde, e); mandarJa() }

    fun falha(onde: String, msg: String) {
        val o = JSONObject().put("tipo", "falha").put("onde", onde.take(60)).put("msg", msg.take(200)).put("em", System.currentTimeMillis())
        completar(o)
        adicionar(o)
        mandarJa()
    }

    private fun mandarJa() { Thread { try { enviar(true) } catch (e: Throwable) { } }.start() }

    private fun guardar(tipo: String, onde: String, e: Throwable) {
        val pilha = e.stackTrace.filter { it.className.startsWith("com.pctoolkit") }.take(6).joinToString("\n") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        val o = JSONObject().put("tipo", tipo).put("onde", onde.take(60)).put("msg", (e.javaClass.simpleName + ": " + (e.message ?: "")).take(200))
            .put("pilha", pilha).put("em", System.currentTimeMillis())
        completar(o)
        adicionar(o)
    }

    /** Junta a tela, o caminho, alguns dados do celular e o print. */
    private fun completar(o: JSONObject) {
        try {
            val r = rastroAtual()
            o.put("tela", r.lastOrNull() ?: "").put("rastro", JSONArray(r))
            val ex = JSONObject()
            try {
                val am = PcApp.ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
                ex.put("memoria", String.format(java.util.Locale("pt", "BR"), "%.1f de %.1f GB livres", mi.availMem / 1e9, mi.totalMem / 1e9))
            } catch (x: Throwable) { }
            try {
                val bi = PcApp.ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val t = bi?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
                val n = bi?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                if (t > 0) ex.put("bateria", "${t / 10} °C" + if (n >= 0) " · $n%" else "")
            } catch (x: Throwable) { }
            try { ex.put("modoCompleto", if (Priv.permitido()) "ligado" else "desligado") } catch (x: Throwable) { }
            try { ex.put("temPro", if (Licenca.ativo()) "sim" else "não") } catch (x: Throwable) { }
            ex.put("rom", Build.DISPLAY.take(40))
            o.put("extra", ex)
            val arq = capturar()
            if (arq.isNotEmpty()) o.put("printArq", arq)
        } catch (x: Throwable) { }
    }

    private fun pastaPrints() = java.io.File(PcApp.ctx.cacheDir, "erro_prints").apply { mkdirs() }

    /** Tira o print so da tela do proprio app (nao do jogo), escondendo campos de texto, e-mails e chaves. */
    private fun capturar(): String {
        if (!printLigado() || capturando) return ""
        val a = tela?.get() ?: return ""
        if (a.isFinishing) return ""
        var b64 = ""
        val fazer = {
            capturando = true
            try {
                val raiz = a.window.decorView
                val w = raiz.width; val h = raiz.height
                if (w > 0 && h > 0) {
                    val esc = 360f / w
                    val bmp = android.graphics.Bitmap.createBitmap((w * esc).toInt(), (h * esc).toInt(), android.graphics.Bitmap.Config.RGB_565)
                    val c = android.graphics.Canvas(bmp)
                    c.scale(esc, esc)
                    raiz.draw(c)
                    val tinta = android.graphics.Paint().apply { color = T.surface2 }
                    val rt = IntArray(2)
                    val sens = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+|[A-Za-z0-9+/=_-]{24,}")
                    fun varrer(v: View) {
                        if (v.visibility != View.VISIBLE) return
                        val esconder = v is android.widget.EditText || (v is TextView && sens.containsMatchIn(v.text ?: ""))
                        if (esconder && v.width > 0) { v.getLocationInWindow(rt); c.drawRect(rt[0].toFloat(), rt[1].toFloat(), (rt[0] + v.width).toFloat(), (rt[1] + v.height).toFloat(), tinta) }
                        if (v is ViewGroup) for (i in 0 until v.childCount) varrer(v.getChildAt(i))
                    }
                    varrer(raiz)
                    var q = 55
                    var bytes: ByteArray
                    do { val out = java.io.ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, out); bytes = out.toByteArray(); q -= 15 } while (bytes.size > 110_000 && q > 15)
                    bmp.recycle()
                    val pasta = pastaPrints()
                    pasta.listFiles()?.sortedBy { it.lastModified() }?.dropLast(4)?.forEach { it.delete() }
                    val f = java.io.File(pasta, "${System.currentTimeMillis()}.b64")
                    f.writeText(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    b64 = f.name
                }
            } catch (x: Throwable) { } finally { capturando = false }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) fazer()
        else {
            val l = java.util.concurrent.CountDownLatch(1)
            a.runOnUiThread { try { fazer() } finally { l.countDown() } }
            try { l.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (x: Throwable) { }
        }
        return b64
    }

    @Synchronized
    private fun adicionar(o: JSONObject) {
        val a = try { JSONArray(Store.str(CHAVE).ifEmpty { "[]" }) } catch (e: Exception) { JSONArray() }
        if (a.length() >= 20) return
        a.put(o)
        // commit imediato: no travamento o app fecha logo em seguida
        PcApp.ctx.getSharedPreferences("config", Context.MODE_PRIVATE).edit().putString(CHAVE, a.toString()).commit()
    }

    /** Manda o que estiver guardado. Na hora do erro (imediato) espera so 20 s entre envios; ao abrir o app, 10 min. */
    @Synchronized
    fun enviar(imediato: Boolean = false) {
        if (!Vendedor.automatico()) return
        val txt = Store.str(CHAVE)
        if (txt.isEmpty() || txt == "[]") return
        val ult = Store.str("relatorio_em").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - ult < (if (imediato) 20_000L else 600_000L)) return
        Store.putStr("relatorio_em", System.currentTimeMillis().toString())
        try {
            val lista = JSONArray(txt)
            val usados = mutableListOf<java.io.File>()
            var prints = 0
            for (i in 0 until lista.length()) {
                val o = lista.getJSONObject(i)
                val nome = o.optString("printArq"); o.remove("printArq")
                if (nome.isEmpty()) continue
                val f = java.io.File(pastaPrints(), nome)
                if (f.exists()) { if (prints < 2 && printLigado()) { o.put("print", f.readText()); prints++ }; usados.add(f) }
            }
            val corpo = JSONObject().put("codigo", Licenca.codigo()).put("versao", BuildConfig.VERSION_NAME)
                .put("modelo", (Build.MANUFACTURER + " " + Build.MODEL).take(40)).put("android", Build.VERSION.SDK_INT).put("erros", lista)
            val (code, _) = Servidor.http("POST", "/erro", corpo.toString())
            if (code in 200..299) { PcApp.ctx.getSharedPreferences("config", Context.MODE_PRIVATE).edit().putString(CHAVE, "").commit(); usados.forEach { it.delete() } }
        } catch (e: Exception) { }
    }

    /** Ajuste: mandar ou nao o print da tela junto com os erros. */
    fun configurar(con: Console) {
        con.titulo("Print da tela nos erros")
        con.linha("Quando algo dá errado, o app manda para a gente o que aconteceu, para corrigir mais rápido. Junto vai um print da tela do próprio SerpyraToolkit (não do jogo nem de outros apps), com os campos de texto, e-mails e chaves cobertos.", T.texto2)
        if (printLigado()) {
            con.ok("Ligado: o print vai junto com os erros.")
            if (con.ask("Desligar o print? Os erros continuam sendo avisados, só sem a imagem.", "Desligar o print", "Deixar ligado")) {
                Store.putBool("erro_print", false); pastaPrints().listFiles()?.forEach { it.delete() }; con.ok("Desligado. Nenhum print será mandado.")
            }
        } else {
            con.aviso("Desligado: os erros vão sem o print.")
            if (con.ask("Ligar o print de novo?", "Ligar", "Deixar desligado")) { Store.putBool("erro_print", true); con.ok("Ligado.") }
        }
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
        // largura do texto inteiro: sem isso, frase maior que a tela ficava cortada no meio da faixa
        tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        tv.visibility = View.INVISIBLE
        caixa.addView(tv, FrameLayout.LayoutParams(tv.measuredWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START))
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
            tv.translationX = larg; tv.visibility = View.VISIBLE
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
            val (code, t) = if (tok == "passe") Servidor.http("GET", "/dono/novas?desde=$desde&codigo=${Licenca.codigo()}", null, null, Store.str("dono_passe"))
                else Servidor.http("GET", "/admin/novas?desde=$desde", null, tok)
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
            val er = o.optJSONArray("erros") ?: JSONArray()
            for (i in 0 until er.length()) {
                val e = er.getJSONObject(i)
                notificar(ctx, 7500 + i, (if (e.optString("tipo") == "crash") "⚠️ O app travou: " else "⚠️ Erro novo: ") + e.optString("onde"),
                    e.optString("msg").take(120) + " · " + e.optInt("celulares") + " celular(es)", "https://serpyratoolkit.pages.dev/painel#erros")
            }
            val rl = o.optJSONArray("relatos") ?: JSONArray()
            for (i in 0 until rl.length()) {
                val r = rl.getJSONObject(i)
                notificar(ctx, 7550 + i, "💬 Relato de cliente: " + r.optString("modelo"), r.optString("texto"), "https://serpyratoolkit.pages.dev/painel#relatos")
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
        val tok = Admin.token().ifEmpty { con.texto("Token do painel", "O mesmo que você usa para entrar em serpyratoolkit.pages.dev/painel", secreto = true) ?: return }
        if (tok.isBlank()) return
        con.linha("Conferindo o token…", T.texto2)
        val (code, t) = try { if (tok == "passe") Servidor.http("GET", "/dono/novas?desde=0&codigo=${Licenca.codigo()}", null, null, Store.str("dono_passe")) else Servidor.http("GET", "/admin/novas?desde=0", null, tok.trim()) } catch (e: Exception) { Pair(0, "") }
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

/** Area do dono: so aparece depois de digitar o token do painel neste celular (segurar o nome no topo do Inicio). */
object Admin {
    /** Token do painel (se o dono digitou) ou "passe" (entrou com a chave do dono). */
    fun token() = Store.str("admin_token").ifEmpty { Store.str("dono_token").takeIf { it != "passe" } ?: "" }.ifEmpty { if (Store.str("dono_passe").isNotEmpty()) "passe" else "" }
    fun ativo() = token().isNotEmpty()

    fun executar(con: Console) {
        con.titulo("Área do dono")
        if (!ativo()) {
            con.linha("Só para o dono do app. Digite a chave do dono (ou o token do painel). Fica guardado só neste celular.", T.texto2)
            val tok = (con.texto("Chave do dono", "Chave do dono", secreto = true) ?: return).trim()
            if (tok.isEmpty()) return
            con.linha("Conferindo…", T.texto2)
            val (c1, t1) = try { Servidor.http("POST", "/dono/entrar", JSONObject().put("chave", tok).put("codigo", Licenca.codigo()).toString()) } catch (e: Exception) { Pair(0, "") }
            if (c1 == 200) {
                Store.putStr("dono_passe", try { JSONObject(t1).optString("passe") } catch (e: Exception) { "" })
            } else {
                val (code, _) = try { Servidor.http("GET", "/admin/saude", null, tok) } catch (e: Exception) { Pair(0, "") }
                if (code == 401) { con.aviso(if (c1 == 429) "Muitas tentativas. Tente de novo mais tarde." else "Chave errada."); return }
                if (code != 200) { con.aviso("Sem conexão com o servidor. Tente de novo."); return }
                Store.putStr("admin_token", tok)
            }
            con.ok("Pronto: este celular é do dono. A Área do dono aparece em Ferramentas > Manutenção.")
        }
        when (con.escolher("O que fazer?", listOf(
            "Avisos de venda e erros neste celular" + if (Dono.ligado()) " (ligado)" else "",
            "Print da tela nos erros (deste celular)" + if (Relatorio.printLigado()) " (ligado)" else " (desligado)",
            "Abrir o painel",
            "Sair da área do dono neste celular"))) {
            0 -> Dono.executar(con)
            1 -> Relatorio.configurar(con)
            2 -> { try { con.ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://serpyratoolkit.pages.dev/painel")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }; con.ok("Abrindo o painel") }
            3 -> if (con.ask("Sair da área do dono? Os avisos de venda neste celular também param.", "Sair", "Cancelar")) {
                Store.putStr("admin_token", ""); Store.putStr("dono_token", ""); Store.putStr("dono_passe", ""); Dono.agendar(con.ctx); con.ok("Pronto: a área do dono sumiu deste celular.")
            }
        }
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
