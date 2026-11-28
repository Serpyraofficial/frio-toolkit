package com.pctoolkit.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

/** Notificacao simples que abre o app (com acao e cupom opcionais). */
object Avisos {
    fun mostrar(ctx: Context, id: Int, canal: String, nomeCanal: String, titulo: String, texto: String, acao: String? = null, cupom: String = "", alta: Boolean = false) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(canal, nomeCanal, if (alta) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT))
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (acao != null) i.putExtra("acao", acao)
            if (cupom.isNotEmpty()) i.putExtra("cupom", cupom)
            val pi = PendingIntent.getActivity(ctx, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(id, Notification.Builder(ctx, canal).setSmallIcon(R.drawable.ic_jogo).setContentTitle(titulo).setContentText(texto)
                .setStyle(Notification.BigTextStyle().bigText(texto)).setContentIntent(pi).setAutoCancel(true).setColor(T.verde).build())
        } catch (e: Exception) { }
    }
}

// ============================================================
// Protecao: confere se o app foi assinado pelo dono (copias modificadas perdem o Pro)
// ============================================================
object Integridade {
    private const val SHA = "e25d5915d64bf37dadd128d605498df894c08067e714d1cbcee8fd95c235c09b"
    @Volatile private var resultado: Boolean? = null
    @Volatile private var cert = ""

    private fun certificados(ctx: Context): List<String> = try {
        val pm = ctx.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val s = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            if (s == null) null else if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        (sigs ?: emptyArray()).map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
    } catch (e: Exception) { emptyList() }

    /** true so quando conseguiu ler a assinatura e ela nao e a do dono. */
    fun modificado(): Boolean {
        resultado?.let { return it }
        val l = certificados(PcApp.ctx)
        cert = l.firstOrNull() ?: ""
        val r = l.isNotEmpty() && l.none { it == SHA }
        resultado = r
        return r
    }

    /** Avisa o painel (uma vez por dia) quando roda uma copia modificada. */
    fun conferir() {
        if (!modificado() || !Vendedor.automatico()) return
        val hoje = (System.currentTimeMillis() / 86_400_000L).toString()
        if (Store.str("pirata_dia") == hoje) return
        Store.putStr("pirata_dia", hoje)
        try {
            Servidor.http("POST", "/pirata", JSONObject().put("codigo", Licenca.codigo()).put("cert", cert.take(16)).put("versao", BuildConfig.VERSION_NAME)
                .put("modelo", (Build.MANUFACTURER + " " + Build.MODEL).take(40)).toString())
        } catch (e: Exception) { }
    }
}

// ============================================================
// Notificacoes que o dono manda pelo painel, por grupo (gratis, Pro, vencidos)
// ============================================================
object Mensagens {
    fun meuGrupo(): String {
        if (Pro.ativo()) return "pro"
        val teve = Store.bool("lic_comprou", false) || (Licenca.diaValidade() > 0 && Store.str("lic_ate_tipo") != "teste")
        return if (teve) "vencidos" else "gratis"
    }

    fun conferir(ctx: Context) {
        if (!Vendedor.automatico()) return
        val ult = Store.str("msg_em").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - ult < 3 * 3600_000L) return
        Store.putStr("msg_em", System.currentTimeMillis().toString())
        val (code, t) = try { Servidor.http("GET", "/mensagens", null) } catch (e: Exception) { return }
        if (code != 200) return
        val l = try { JSONObject(t).optJSONArray("mensagens") } catch (e: Exception) { null } ?: return
        val vistas = Store.str("msg_vistas").split(",").filter { it.isNotBlank() }.toMutableList()
        val grupo = meuGrupo()
        for (k in 0 until l.length()) {
            val m = l.optJSONObject(k) ?: continue
            val id = m.optLong("id").toString()
            if (id in vistas) continue
            val pub = m.optString("publico", "todos")
            if (pub != "todos" && pub != grupo) continue
            vistas.add(id)
            val cupom = m.optString("cupom")
            Avisos.mostrar(ctx, 7300 + (m.optLong("id") % 100).toInt(), "novidades", "Novidades e promoções", m.optString("titulo"), m.optString("texto"),
                if (cupom.isNotEmpty() || pub == "gratis" || pub == "vencidos") "pro" else null, cupom)
        }
        Store.putStr("msg_vistas", vistas.takeLast(40).joinToString(","))
    }
}

// ============================================================
// Resumo da semana (uma notificacao a cada 7 dias)
// ============================================================
object Semanal {
    fun conferir(ctx: Context) {
        val agora = System.currentTimeMillis()
        val ult = Store.str("semanal_em").toLongOrNull()
        if (ult == null) { Store.putStr("semanal_em", agora.toString()); return }
        if (agora - ult < 7 * 86_400_000L || Store.bool("semanal_off", false)) return
        Store.putStr("semanal_em", agora.toString())
        val a = Store.lerArr("sessoes.json")
        var partidas = 0; var minutos = 0; var somaFps = 0; var nFps = 0; var tMax = 0.0
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optLong("em") < agora - 7 * 86_400_000L) continue
            partidas++; minutos += o.optInt("dur")
            if (o.has("fps")) { somaFps += o.optInt("fps"); nFps++ }
            tMax = maxOf(tMax, o.optDouble("tMax", 0.0))
        }
        val nota = Store.int("nota_fps", -1)
        if (partidas == 0 && nota < 0) return
        val partes = ArrayList<String>()
        if (partidas > 0) partes.add("$partidas partida(s), ${minutos / 60}h${"%02d".format(minutos % 60)} de jogo")
        if (nFps > 0) partes.add("${somaFps / nFps} FPS médio")
        if (tMax > 0) partes.add("máximo de ${f1(tMax)} °C")
        if (nota >= 0) partes.add("nota $nota/100")
        val dica = when {
            tMax >= 42 -> "Esquentou bastante: experimente o Modo Frio."
            nota in 0..69 -> "Faça o Raio-X de novo: tem coisa para corrigir."
            partidas == 0 -> "Que tal uma partida com o Modo Jogo?"
            else -> "Seu celular está em forma. Bom jogo!"
        }
        Avisos.mostrar(ctx, 7350, "resumo", "Resumo da semana", "Sua semana no SerpyraToolkit", partes.joinToString(" · ") + ". " + dica, if (partidas > 0) "relatorio" else "raiox")
    }
}

// ============================================================
// Oferta do Pro na hora certa (esquentou ou o FPS caiu na partida): no maximo 1x por dia
// ============================================================
object Upsell {
    fun oferecer(ctx: Context, titulo: String, texto: String): Boolean {
        if (Pro.ativo() || !Vendedor.automatico()) return false
        val hoje = (System.currentTimeMillis() / 86_400_000L).toString()
        if (Store.str("upsell_dia") == hoje) return false
        Store.putStr("upsell_dia", hoje)
        val teste = !Store.bool("teste_usado", false) && Licenca.diaValidade() <= 0
        Avisos.mostrar(ctx, 7360, "dicas", "Dicas durante o jogo", titulo, texto + if (teste) " Toque para testar grátis por ${Vendedor.TESTE_TXT}." else " Toque para ver o Pro.", "pro")
        return true
    }
}

// ============================================================
// Comparar com celulares do mesmo modelo
// ============================================================
object Comparar {
    class R(val n: Int, val media: Int, val melhorQue: Int, val doModelo: Boolean)
    fun modelo() = (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).take(40)

    fun buscar(nota: Int): R? {
        if (!Vendedor.automatico() || nota <= 0) return null
        return try {
            val mes = java.text.SimpleDateFormat("yyyy-MM", Locale.US).format(java.util.Date())
            val enviar = Store.str("nota_enviada") != mes
            val rota = "/nota?modelo=" + Uri.encode(modelo()) + "&nota=" + nota
            val (code, t) = if (enviar) Servidor.http("POST", rota, JSONObject().put("codigo", Licenca.codigo()).put("modelo", modelo()).put("nota", nota).toString())
                else Servidor.http("GET", rota, null)
            if (code != 200) return null
            if (enviar) Store.putStr("nota_enviada", mes)
            val o = JSONObject(t)
            if (o.optInt("n") < 3) return null
            val r = R(o.optInt("n"), o.optInt("media"), o.optInt("melhorQue"), o.optString("de") == "modelo")
            Store.putInt("nota_melhor", r.melhorQue); Store.putBool("nota_do_modelo", r.doModelo)
            r
        } catch (e: Exception) { null }
    }

    fun texto(r: R): String = "Melhor que ${r.melhorQue}% " + if (r.doModelo) "dos ${modelo()} que usam o app" else "dos celulares que usam o app"
}

// ============================================================
// Cartao com o resultado para compartilhar (com o codigo de indicacao)
// ============================================================
object Cartao {
    private fun gerar(ctx: Context, nota: Int): File {
        val w = 1080; val h = 1350
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF0F2A22.toInt(), 0xFF101C30.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        val cor = if (nota >= 85) 0xFF3DDC97.toInt() else if (nota >= 60) 0xFFFFC857.toInt() else 0xFFFF6B6B.toInt()
        fun txt(s: String, y: Float, tam: Float, cr: Int, negrito: Boolean = false) {
            p.color = cr; p.textSize = tam; p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.create(Typeface.DEFAULT, if (negrito) Typeface.BOLD else Typeface.NORMAL)
            c.drawText(s, w / 2f, y, p)
        }
        txt("SerpyraToolkit", 150f, 64f, 0xFFE8EEF5.toInt(), true)
        txt("Nota de desempenho do meu celular", 220f, 38f, 0xFF9AA8BA.toInt())
        // anel
        p.style = Paint.Style.STROKE; p.strokeWidth = 46f; p.strokeCap = Paint.Cap.ROUND
        val r = RectF(w / 2f - 250, 300f, w / 2f + 250, 800f)
        p.color = 0x33FFFFFF; c.drawArc(r, 0f, 360f, false, p)
        p.color = cor; c.drawArc(r, -90f, 360f * nota.coerceIn(0, 100) / 100f, false, p)
        p.style = Paint.Style.FILL
        txt("$nota", 600f, 210f, 0xFFFFFFFF.toInt(), true)
        txt("de 100", 670f, 44f, 0xFF9AA8BA.toInt())
        txt(Comparar.modelo(), 900f, 46f, 0xFFE8EEF5.toInt(), true)
        val mq = Store.int("nota_melhor", -1)
        if (mq >= 0) txt("Melhor que $mq% " + if (Store.bool("nota_do_modelo", false)) "dos celulares iguais" else "dos celulares no app", 970f, 40f, cor)
        p.color = 0x22FFFFFF; c.drawRoundRect(RectF(110f, 1060f, w - 110f, 1230f), 36f, 36f, p)
        txt("Ganhe 10% no Pro com o meu código", 1125f, 38f, 0xFF9AA8BA.toInt())
        txt("IND" + Licenca.codigo(), 1195f, 58f, 0xFF3DDC97.toInt(), true)
        txt("serpyratoolkit.pages.dev", 1300f, 36f, 0xFF66758A.toInt())
        val dir = File(ctx.cacheDir, "compartilhar"); dir.mkdirs()
        val f = File(dir, "nota.png")
        f.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bm.recycle()
        return f
    }

    fun compartilhar(ctx: Context, nota: Int) {
        try {
            gerar(ctx, nota)
            val uri = Uri.parse("content://" + ctx.packageName + ".imagens/nota.png")
            val texto = "Meu celular tirou $nota/100 no SerpyraToolkit 🎮 Testa o seu: serpyratoolkit.pages.dev · Use o meu código IND${Licenca.codigo()} e ganhe 10% no Pro."
            val i = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, texto)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, "Compartilhar minha nota").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Relatorio.falha("cartao", e) }
    }
}

/** Entrega a imagem do cartao para o app escolhido (WhatsApp, Instagram...). */
class ImagemProvider : ContentProvider() {
    private fun arquivo(uri: Uri): File? {
        val nome = uri.lastPathSegment ?: return null
        if (!nome.matches(Regex("[a-z0-9_-]+\\.png"))) return null
        return File(File(context!!.cacheDir, "compartilhar"), nome).takeIf { it.exists() }
    }
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = arquivo(uri) ?: throw java.io.FileNotFoundException()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = arquivo(uri)
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        if (f != null) c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

// ============================================================
// Relatar um problema (vai para o painel do dono)
// ============================================================
object Relatar {
    fun executar(con: Console) {
        con.titulo("Relatar um problema")
        con.linha("Conte o que aconteceu. A mensagem chega direto para a gente, com o modelo do celular e a versão do app.", T.texto2)
        val t = con.texto("O que aconteceu?", "Ex.: os botões somem quando abro o Free Fire") ?: return
        if (t.trim().length < 5) { con.aviso("Escreva um pouco mais para a gente entender."); return }
        var contato = ""
        if (con.ask("Quer receber a resposta por e-mail?", "Sim", "Não precisa", importante = true))
            contato = (con.texto("Seu e-mail", "voce@email.com", Store.str("lic_email")) ?: "").trim()
        con.linha("Enviando…", T.texto2)
        val corpo = JSONObject().put("codigo", Licenca.codigo()).put("texto", t.trim()).put("contato", contato).put("versao", BuildConfig.VERSION_NAME)
            .put("modelo", (Build.MANUFACTURER + " " + Build.MODEL).take(40)).put("android", Build.VERSION.RELEASE).put("pro", Pro.ativo())
        val (code, r) = try { Servidor.http("POST", "/relato", corpo.toString()) } catch (e: Exception) { Pair(0, "") }
        if (code == 200) { con.ok("Recebemos! Obrigado por avisar."); if (contato.contains("@")) con.info("Se precisar, respondemos em $contato.") }
        else con.aviso(try { JSONObject(r).optString("erro").ifEmpty { "Sem conexão agora. Tente de novo em alguns minutos." } } catch (e: Exception) { "Sem conexão agora. Tente de novo em alguns minutos." })
    }
}

// ============================================================
// Calculadora de sensibilidade (gratis)
// ============================================================
object Sensibilidade {
    private fun polegadas(ctx: Context): Double {
        val dm = ctx.resources.displayMetrics
        val wm = ctx.getSystemService(android.view.WindowManager::class.java)
        val (pw, ph) = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() } else dm.widthPixels to dm.heightPixels
        val x = pw / dm.xdpi.toDouble(); val y = ph / dm.ydpi.toDouble()
        return sqrt(x * x + y * y).coerceIn(4.0, 13.0)
    }

    fun executar(con: Console) {
        val ctx = con.ctx
        con.titulo("Calculadora de sensibilidade")
        con.linha("Um ponto de partida feito para a tela do seu celular. Depois ajuste de 5 em 5 no treino até a mira parar onde você quer.", T.texto2)
        val pol = polegadas(ctx)
        val hz = Sensores.tela(ctx).second
        con.info(String.format(Locale("pt", "BR"), "Seu celular: tela de %.1f polegadas, até %.0f Hz.", pol, hz))
        when (con.escolher("O que você quer?", listOf("Gerar a sensibilidade do Free Fire para este celular", "Converter a sensibilidade de outro celular para este"))) {
            0 -> {
                val estilo = con.escolher("Como você joga?", listOf("Mais capa (puxar rápido para a cabeça)", "Equilibrado", "Mais precisão (mira firme, longe)"))
                if (estilo < 0) return
                val fator = listOf(1.12, 1.0, 0.88)[estilo]
                val base = (150 * (6.5 / pol).pow(0.6) * fator * (if (hz >= 89) 0.95 else 1.0)).coerceIn(60.0, 200.0)
                fun v(m: Double) = (base * m).coerceIn(0.0, 200.0).toInt().let { it - it % 5 }
                con.subtitulo("Sensibilidade sugerida (escala de 0 a 200)")
                con.item("Geral: ${v(1.0)}", T.verde)
                con.item("Ponto vermelho: ${v(0.96)}", T.verde)
                con.item("Mira 2x: ${v(0.92)}", T.verde)
                con.item("Mira 4x: ${v(0.86)}", T.verde)
                con.item("Mira AWP: ${v(0.55)}", T.verde)
                con.item("Olhadinha: ${v(0.9)}", T.verde)
                con.linha("")
                con.info("Botão de tiro: entre 45% e 55% do tamanho, perto do polegar direito.")
                con.info("Se a mira passa da cabeça, baixe 5 na Geral. Se não chega, suba 5.")
            }
            1 -> {
                val antiga = con.numero("Sensibilidade Geral no celular antigo", 100, 1, 200)
                val tam = con.escolher("Tamanho da tela do celular antigo", listOf("Pequeno (até 6 polegadas)", "Médio (6 a 6,5)", "Grande (6,5 a 7)", "Tablet (mais de 7)"))
                if (tam < 0) return
                val polAntigo = listOf(5.8, 6.3, 6.7, 8.5)[tam]
                val nova = (antiga * sqrt(polAntigo / pol)).toInt().coerceIn(1, 200)
                con.subtitulo("Use no celular novo")
                con.item("Geral: $nova (era $antiga)", T.verde)
                con.info("Aplique a mesma proporção nas outras miras: multiplique cada uma por " + String.format(Locale("pt", "BR"), "%.2f", nova.toDouble() / antiga) + ".")
            }
            else -> return
        }
        con.ok("Pronto. Anote ou tire um print desta tela.")
    }
}

// ============================================================
// Preparar para jogar: memoria, limpeza rapida e Modo Jogo com um toque
// ============================================================
object Preparar {
    fun ultimoJogo(): String? = Store.str("jogos_uso").split(",").firstOrNull { it.isNotBlank() }

    fun executar(con: Console, pkg: String?) {
        val ctx = con.ctx
        val alvo = pkg ?: ultimoJogo() ?: Jogos.escolher(con)?.pkg ?: return
        con.titulo("Preparar para " + Jogos.nome(ctx, alvo))
        con.linha("Libera memória, faz uma limpeza rápida e abre o jogo com o Modo Jogo.", T.texto2)
        con.subtitulo("1. Memória")
        try { Fps.liberarRam(con) } catch (e: Exception) { con.info("Memória: pulei esta etapa.") }
        if (con.cancelado) return
        con.subtitulo("2. Limpeza rápida")
        try { Limpeza.rapida(con, false) } catch (e: Exception) { con.info("Limpeza: pulei esta etapa.") }
        if (con.cancelado) return
        con.subtitulo("3. Modo Jogo")
        ModoJogo.executar(con, alvo)
    }
}

// ============================================================
// Atalhos fora do app: bloco nas Configuracoes rapidas e widget na tela inicial
// ============================================================
class ModoJogoTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { it.state = if (GameService.rodando) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; it.updateTile() }
    }

    override fun onClick() {
        super.onClick()
        if (GameService.rodando) {
            try { startService(Intent(this, GameService::class.java).setAction("parar")) } catch (e: Exception) { }
            qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
            return
        }
        val a = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("acao", "modojogo")
        try {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 1, a, PendingIntent.FLAG_IMMUTABLE))
            else abrirAntigo(a)
        } catch (e: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun abrirAntigo(a: Intent) = startActivityAndCollapse(a)
}

class AtalhoWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_atalho)
            fun pi(acao: String, req: Int) = PendingIntent.getActivity(ctx, req, Intent(ctx, MainActivity::class.java).putExtra("acao", acao)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.w_modojogo, pi("modojogo", 9101))
            v.setOnClickPendingIntent(R.id.w_preparar, pi("preparar", 9102))
            v.setOnClickPendingIntent(R.id.w_raiox, pi("raiox", 9103))
            val nota = Store.int("nota_fps", -1)
            v.setTextViewText(R.id.w_raiox, if (nota >= 0) "Raio-X\nnota $nota" else "Raio-X")
            mgr.updateAppWidget(id, v)
        }
    }

    companion object {
        fun atualizar(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, AtalhoWidget::class.java))
                if (ids.isNotEmpty()) AtalhoWidget().onUpdate(ctx, mgr, ids)
            } catch (e: Exception) { }
        }
    }
}

// ============================================================
// Convite discreto para avaliar no site (de vez em quando, nunca insistente)
// ============================================================
object Avaliar {
    const val LINK = "https://serpyratoolkit.pages.dev/#avaliacoes"
    private const val DIA = 86_400_000L

    fun abrir(ctx: Context) {
        Store.putBool("avaliou", true)
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LINK)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    }

    /** Mostra o convite? So depois de uns dias de uso, no maximo 3 vezes, com 20 dias entre uma e outra. */
    fun mostrar(): Boolean {
        val agora = System.currentTimeMillis()
        val inicio = Store.str("inst_em").toLongOrNull() ?: run { Store.putStr("inst_em", agora.toString()); return false }
        if (Store.bool("avaliou", false) || Store.int("aval_vezes", 0) >= 3) return false
        if (agora - inicio < 4 * DIA) return false
        val usou = Store.lerArr("sessoes.json").length() >= 2 || Store.int("nota_fps", -1) >= 0
        if (!usou) return false
        val ult = Store.str("aval_fechou").toLongOrNull() ?: 0L
        return agora - ult >= 20 * DIA
    }

    fun fechar() {
        Store.putStr("aval_fechou", System.currentTimeMillis().toString())
        Store.putInt("aval_vezes", Store.int("aval_vezes", 0) + 1)
    }
}
