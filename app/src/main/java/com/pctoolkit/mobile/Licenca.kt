package com.pctoolkit.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Dados de quem vende (preencher quando o vendedor enviar). Vazio = secao escondida. */
object Vendedor {
    const val CHAVE_PIX = "8a81c986-40bd-41b0-99fd-dc6fded1e765"   // chave Pix aleatoria do vendedor
    const val NOME = "SerpyraToolkit"     // nome no QR (o banco do cliente mostra o titular real da chave)
    const val CIDADE = "Brasil"
    const val WHATSAPP = ""           // numero com DDI e DDD, so digitos (ex.: 5511999998888)
    /** Endereco do servidor de vendas (Cloudflare Worker). Vazio = so o Pix manual. */
    const val SERVIDOR = "https://serpyratoolkit.officialcarloswendrik.workers.dev"
    const val PRECO = "10.00"
    const val PRECO_TXT = "R$ 10"
    fun temPix() = CHAVE_PIX.isNotBlank() && NOME.isNotBlank()
    fun automatico() = SERVIDOR.startsWith("https://")
}

/** Compra automatica: Pix pela PurinCash, chave por e-mail e ativacao sozinha. */
object Compra {
    class Pedido(val id: String, val numero: String, val brCode: String, val qr: String, val teste: Boolean)
    class Status(val pago: Boolean, val chave: String, val validade: String, val emailEnviado: Boolean, val outro: String)

    private fun http(metodo: String, caminho: String, corpo: String?): Pair<Int, String> {
        val c = URL(Vendedor.SERVIDOR.trimEnd('/') + caminho).openConnection() as HttpURLConnection
        c.requestMethod = metodo; c.connectTimeout = 15000; c.readTimeout = 25000
        if (corpo != null) { c.doOutput = true; c.setRequestProperty("content-type", "application/json"); c.outputStream.use { it.write(corpo.toByteArray()) } }
        val code = c.responseCode
        val t = try { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
        c.disconnect()
        return Pair(code, t)
    }

    /** Cria o Pix. Devolve o pedido ou lanca erro com a mensagem para o usuario. */
    fun criar(email: String): Pedido {
        val atual = if (Licenca.ativo()) Licenca.diaValidade() else 0
        val corpo = org.json.JSONObject().put("codigo", Licenca.codigo()).put("email", email).put("atual", atual).toString()
        val (code, t) = http("POST", "/pedido", corpo)
        val o = try { org.json.JSONObject(t) } catch (e: Exception) { org.json.JSONObject() }
        if (code !in 200..299) throw IllegalStateException(o.optString("erro", "Não consegui gerar o Pix (erro $code)."))
        val p = Pedido(o.getString("pedido"), o.optString("numero"), o.optString("brCode"), o.optString("qr"), o.optBoolean("teste"))
        Store.putStr("lic_pedido", p.id); Store.putStr("lic_pedido_num", p.numero); Store.putStr("lic_pedido_br", p.brCode); Store.putStr("lic_pedido_qr", p.qr)
        Store.putStr("lic_pedido_em", System.currentTimeMillis().toString())
        Store.putStr("lic_email", email)
        return p
    }

    fun consultar(id: String): Status? {
        return try {
            val (code, t) = http("GET", "/status?pedido=" + Uri.encode(id), null)
            if (code == 404) return Status(false, "", "", false, "desconhecido")
            val o = org.json.JSONObject(t)
            Status(o.optString("status") == "pago", o.optString("chave"), o.optString("validade"), o.optBoolean("email"), o.optString("status"))
        } catch (e: Exception) { null }
    }

    /** Confere um pedido pendente (ao abrir o app). Ativa sozinho se ja foi pago. */
    fun conferirPendente(): Boolean {
        if (!Vendedor.automatico()) return false
        val id = Store.str("lic_pedido")
        if (id.isEmpty()) return false
        val desde = Store.str("lic_pedido_em").toLongOrNull() ?: 0L
        val st = consultar(id) ?: return false
        if (st.pago && st.chave.isNotEmpty()) { Licenca.ativar(st.chave); limpar(); return true }
        if (st.outro == "expired" || st.outro == "cancelled" || st.outro == "desconhecido" || System.currentTimeMillis() - desde > 2 * 86_400_000L) limpar()
        return false
    }

    fun limpar() { for (k in listOf("lic_pedido", "lic_pedido_num", "lic_pedido_br", "lic_pedido_qr", "lic_pedido_em")) Store.putStr(k, "") }

    fun imagem(dataUri: String): Bitmap? = try {
        val b = Base64.decode(dataUri.substringAfter("base64,"), Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)
    } catch (e: Exception) { null }
}

/**
 * LICENCA PRO: chave assinada (ECDSA P-256) pelo vendedor, presa a este celular e com data de validade.
 * O app so tem a chave PUBLICA: confere a assinatura sem internet e nao da para inventar uma chave.
 * Formato: PCT-<codigo do aparelho>.<dia de validade>.<assinatura base64url>
 */
object Licenca {
    private const val CHAVE_PUBLICA = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEe1+AJ9msQvTj5AdyZO0SzXl0SNyekG3tBq3nUeQHdWSvy3u7i8k6qTJsZ8iU890vHizszVAKeAxs4fjOVXVnFQ=="
    private const val DIA = 86_400_000L
    private const val B32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Codigo curto deste aparelho (8 caracteres), mostrado ao cliente. */
    fun codigo(): String {
        val id = Settings.Secure.getString(PcApp.ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "sem-id"
        val h = MessageDigest.getInstance("SHA-256").digest(("pctoolkit:" + id).toByteArray())
        val sb = StringBuilder()
        for (i in 0 until 8) sb.append(B32[(h[i].toInt() and 0xFF) % B32.length])
        return sb.toString()
    }

    fun codigoFormatado(): String { val c = codigo(); return c.substring(0, 4) + "-" + c.substring(4) }

    /** Codigo do pedido: aparelho + validade atual (para a renovacao somar os dias). */
    fun codigoPedido(): String { val d = diaValidade(); return codigoFormatado() + if (d > 0 && ativo()) "-" + d.toString(36).uppercase() else "" }

    /** Hora em que o app confia: nunca volta para tras (atrasar o relogio do celular nao estende a licenca). */
    fun agoraConfiavel(): Long {
        val visto = Store.str("lic_tempo").toLongOrNull() ?: 0L
        val agora = System.currentTimeMillis()
        if (agora > visto) { Store.putStr("lic_tempo", agora.toString()); return agora }
        return visto
    }

    /** Atualiza a hora confiavel pela internet (cabecalho Date de um site grande). */
    fun sincronizarHora() {
        try {
            val c = URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000; c.requestMethod = "HEAD"
            val d = c.date
            c.disconnect()
            if (d > 0) { val visto = Store.str("lic_tempo").toLongOrNull() ?: 0L; if (d > visto) Store.putStr("lic_tempo", d.toString()) }
        } catch (e: Exception) { }
    }

    private fun b64url(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** Assinatura crua (r||s, 64 bytes, como o WebCrypto gera) -> DER (como o Java confere). */
    private fun paraDer(raw: ByteArray): ByteArray {
        fun inteiro(b: ByteArray): ByteArray {
            var i = 0
            while (i < b.size - 1 && b[i].toInt() == 0) i++
            var v = b.copyOfRange(i, b.size)
            if (v[0].toInt() and 0x80 != 0) v = byteArrayOf(0) + v
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val r = inteiro(raw.copyOfRange(0, 32))
        val s = inteiro(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
    }

    /** Confere uma chave. Devolve o dia de validade (dias desde 1970) ou -1 se invalida para este aparelho. */
    fun conferir(chave: String): Int {
        return try {
            val t = chave.trim().replace("\\s".toRegex(), "").removePrefix("PCT-").removePrefix("pct-")
            val partes = t.split('.')
            if (partes.size != 3) return -1
            val dev = partes[0].uppercase().replace("-", "")
            val dia = partes[1].toInt()
            if (dev != codigo()) return -2
            val sig = b64url(partes[2])
            if (sig.size != 64) return -1
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(CHAVE_PUBLICA, Base64.DEFAULT)))
            val v = Signature.getInstance("SHA256withECDSA")
            v.initVerify(pub)
            v.update("$dev.$dia".toByteArray())
            if (v.verify(paraDer(sig))) dia else -1
        } catch (e: Exception) { -1 }
    }

    fun diaValidade(): Int {
        val c = Store.str("lic_chave")
        if (c.isEmpty()) return -1
        return conferir(c)
    }

    fun ativo(): Boolean { val d = diaValidade(); return d > 0 && agoraConfiavel() < (d + 1L) * DIA }

    fun diasRestantes(): Int { val d = diaValidade(); if (d <= 0) return 0; return (((d + 1L) * DIA - agoraConfiavel()) / DIA).toInt().coerceAtLeast(0) }

    fun validadeTexto(): String {
        val d = diaValidade(); if (d <= 0) return ""
        val f = SimpleDateFormat("dd/MM/yyyy", Locale.US); f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(d * DIA))
    }

    /** Ativa uma chave. Mensagem para o usuario. */
    fun ativar(chave: String): Pair<Boolean, String> {
        val d = conferir(chave)
        if (d == -2) return Pair(false, "Esta chave é de outro celular. O código deste aparelho é ${codigoFormatado()}.")
        if (d < 0) return Pair(false, "Chave inválida. Confira se copiou ela inteira.")
        val atual = diaValidade()
        if (atual > d && ativo()) return Pair(false, "Você já tem uma licença válida até mais tarde (${validadeTexto()}).")
        if (agoraConfiavel() >= (d + 1L) * DIA) return Pair(false, "Esta chave já venceu.")
        Store.putStr("lic_chave", chave.trim())
        return Pair(true, "Pro ativado até ${validadeTexto()}!")
    }

    // ---------------- Pix copia e cola (BR Code estatico) ----------------
    private fun campo(id: String, v: String) = id + String.format(Locale.US, "%02d", v.length) + v

    private fun crc16(s: String): String {
        var crc = 0xFFFF
        for (b in s.toByteArray(Charsets.UTF_8)) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return String.format(Locale.US, "%04X", crc)
    }

    private fun semAcento(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace("\\p{M}".toRegex(), "").replace("[^A-Za-z0-9 .\\-]".toRegex(), "")

    fun pixCopiaECola(): String {
        val conta = campo("00", "br.gov.bcb.pix") + campo("01", Vendedor.CHAVE_PIX)
        val txid = "PCT" + codigo()
        val corpo = campo("00", "01") + campo("26", conta) + campo("52", "0000") + campo("53", "986") + campo("54", Vendedor.PRECO) +
            campo("58", "BR") + campo("59", semAcento(Vendedor.NOME).take(25)) + campo("60", semAcento(Vendedor.CIDADE).take(15)) +
            campo("62", campo("05", txid)) + "6304"
        return corpo + crc16(corpo)
    }

    fun qr(texto: String, tam: Int): Bitmap {
        val m = QRCodeWriter().encode(texto, BarcodeFormat.QR_CODE, tam, tam)
        val bmp = Bitmap.createBitmap(tam, tam, Bitmap.Config.RGB_565)
        for (x in 0 until tam) for (y in 0 until tam) bmp.setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        return bmp
    }
}

/** Funcoes do plano Pro. */
object Pro {
    val recursos = listOf(
        "Teclado e controles no jogo (botões estilo MuMu, macros, teclado/controle físico)",
        "Tuner de jogos (resolução, limite de FPS e driver por jogo)",
        "Modo Frio e Turbo no Modo Jogo"
    )

    fun ativo(): Boolean = Licenca.ativo()

    /** Dentro de uma tarefa: true se pode usar; senao explica e oferece assinar. */
    fun exigir(con: Console, recurso: String): Boolean {
        if (ativo()) return true
        con.aviso("$recurso é uma função do SerpyraToolkit Pro.")
        con.info("Pro: ${Vendedor.PRECO_TXT} por mês. Libera: " + recursos.joinToString("; "))
        val d = Licenca.diaValidade()
        if (d > 0) con.info("Sua assinatura venceu em ${Licenca.validadeTexto()}.")
        if (con.ask("Assinar ou ativar uma chave agora?", "Ver como assinar", "Agora não", importante = true)) {
            (con.ctx as? MainActivity)?.let { a -> a.runOnUiThread { a.abrirTela { a.telaPro() } } }
        }
        return false
    }
}

private fun MainActivity.copiar(rotulo: String, t: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(rotulo, t))
    android.widget.Toast.makeText(this, "Copiado", android.widget.Toast.LENGTH_SHORT).show()
}

fun MainActivity.telaPro() {
    Thread { Licenca.sincronizarHora() }.start()
    val col = coluna()
    val ativo = Licenca.ativo()
    col.addView(barraTopo("SerpyraToolkit Pro", if (ativo) "Ativo até ${Licenca.validadeTexto()}" else "${Vendedor.PRECO_TXT} por mês"))

    // status
    val st = Ui.vertical(this)
    st.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18))
    st.background = Ui.gradiente(this, 0xFF0F2A22.toInt(), 0xFF101C30.toInt(), 22)
    val topo = Ui.horizontal(this)
    topo.addView(Ui.texto(this, if (ativo) "Pro ativo" else "Versão grátis", 20f, T.texto, Ui.negrito), Ui.peso())
    topo.addView(Ui.chip(this, if (ativo) "${Licenca.diasRestantes()} dias" else "Pro bloqueado", if (ativo) (if (Licenca.diasRestantes() <= 3) T.amarelo else T.verde) else T.texto3))
    st.addView(topo)
    for (r in Pro.recursos) {
        val v = Ui.texto(this, (if (ativo) "✓  " else "🔒  ") + r, 13f, if (ativo) T.texto else T.texto2)
        v.setPadding(0, Ui.dp(this, 8), 0, 0)
        st.addView(v)
    }
    if (ativo && Licenca.diasRestantes() <= 3) {
        val a = Ui.texto(this, "Sua assinatura vence em breve. Renove para não perder as funções.", 12.5f, T.amarelo)
        a.setPadding(0, Ui.dp(this, 10), 0, 0); st.addView(a)
    }
    col.addView(st, Ui.linhaLp(this, 0, 6))

    if (Vendedor.automatico()) { secaoAutomatica(col, ativo); secaoChave(col); setContentView(raiz(col)); return }

    // passo 1: codigo
    col.addView(Ui.secao(this, if (ativo) "Renovar (os dias se somam)" else "Como assinar"))
    val c1 = Ui.vertical(this)
    c1.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16))
    c1.background = Ui.forma(this, T.surface, 18, T.outline)
    c1.addView(Ui.texto(this, "1. Seu código do pedido", 14.5f, T.texto, Ui.medio))
    val cod = Ui.texto(this, Licenca.codigoPedido(), 22f, T.verde, Typeface.MONOSPACE)
    cod.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6))
    cod.setTextIsSelectable(true)
    c1.addView(cod)
    c1.addView(Ui.texto(this, "A chave que você receber só funciona neste celular.", 12f, T.texto3))
    c1.addView(Ui.botao(this, "Copiar código", T.verde, false) { copiar("codigo", Licenca.codigoPedido()) }, Ui.linhaLp(this, 10, 0))
    col.addView(c1, Ui.linhaLp(this))

    // passo 2: pagar
    val c2 = Ui.vertical(this)
    c2.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16))
    c2.background = Ui.forma(this, T.surface, 18, T.outline)
    c2.addView(Ui.texto(this, "2. Pague ${Vendedor.PRECO_TXT} por Pix", 14.5f, T.texto, Ui.medio))
    if (Vendedor.temPix()) {
        val pix = Licenca.pixCopiaECola()
        val iv = ImageView(this)
        try { iv.setImageBitmap(Licenca.qr(pix, 600)) } catch (e: Exception) { }
        val lpq = LinearLayout.LayoutParams(Ui.dp(this, 220), Ui.dp(this, 220)); lpq.gravity = Gravity.CENTER_HORIZONTAL; lpq.topMargin = Ui.dp(this, 10)
        iv.setBackgroundColor(0xFFFFFFFF.toInt()); iv.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8))
        c2.addView(iv, lpq)
        val pt = Ui.texto(this, "Escaneie no app do seu banco ou use o Pix copia e cola. Valor: ${Vendedor.PRECO_TXT}", 12.5f, T.texto2); pt.gravity = Gravity.CENTER; pt.setPadding(0, Ui.dp(this, 8), 0, 0)
        c2.addView(pt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        c2.addView(Ui.botao(this, "Copiar Pix copia e cola", T.verde, true) { copiar("pix", pix) }, Ui.linhaLp(this, 10, 0))
    } else {
        val t = Ui.texto(this, "Fale com o vendedor para pagar e informe o seu código do pedido.", 12.5f, T.texto2)
        t.setPadding(0, Ui.dp(this, 6), 0, 0); c2.addView(t)
    }
    if (Vendedor.WHATSAPP.isNotBlank()) {
        c2.addView(Ui.botao(this, "Enviar comprovante pelo WhatsApp", T.verde, false) {
            val msg = "Olá! Paguei o SerpyraToolkit Pro (${Vendedor.PRECO_TXT}). Meu código do pedido: ${Licenca.codigoPedido()}"
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${Vendedor.WHATSAPP}?text=" + Uri.encode(msg)))) } catch (e: Exception) { }
        }, Ui.linhaLp(this, 10, 0))
    }
    col.addView(c2, Ui.linhaLp(this))

    secaoChave(col)
    setContentView(raiz(col))
}

@Volatile private var vigiaPix = 0

/** Assinar com Pix automatico: e-mail -> QR -> confirma sozinho -> chave por e-mail. */
private fun MainActivity.secaoAutomatica(col: LinearLayout, ativo: Boolean) {
    col.addView(Ui.secao(this, if (ativo) "Renovar (os dias se somam)" else "Assinar com Pix"))
    val c = Ui.vertical(this)
    c.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16))
    c.background = Ui.forma(this, T.surface, 18, T.outline)
    c.addView(Ui.texto(this, "Seu e-mail", 14.5f, T.texto, Ui.medio))
    c.addView(Ui.texto(this, "Você recebe nele o pedido, a confirmação da compra e a sua chave de 30 dias.", 12f, T.texto3))
    val et = EditText(this)
    et.hint = "voce@email.com"
    et.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    et.setText(Store.str("lic_email"))
    et.setTextColor(T.texto); et.setHintTextColor(T.texto3)
    c.addView(et)
    val area = Ui.vertical(this)
    val msg = Ui.texto(this, "", 13f, T.texto2)
    msg.setPadding(0, Ui.dp(this, 8), 0, 0)
    val botao = Ui.botao(this, "Gerar Pix de ${Vendedor.PRECO_TXT}", T.verde, true) { }
    c.addView(botao, Ui.linhaLp(this, 12, 0))
    c.addView(msg)
    c.addView(area)
    col.addView(c, Ui.linhaLp(this))

    fun mostrarPix(numero: String, br: String, qr: String, teste: Boolean) {
        area.removeAllViews()
        val iv = ImageView(this)
        val bmp = Compra.imagem(qr) ?: (if (br.isNotEmpty()) try { Licenca.qr(br, 600) } catch (e: Exception) { null } else null)
        if (bmp != null) iv.setImageBitmap(bmp)
        iv.setBackgroundColor(0xFFFFFFFF.toInt()); iv.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8))
        val lpq = LinearLayout.LayoutParams(Ui.dp(this, 220), Ui.dp(this, 220)); lpq.gravity = Gravity.CENTER_HORIZONTAL; lpq.topMargin = Ui.dp(this, 12)
        area.addView(iv, lpq)
        val n = Ui.texto(this, "Pedido $numero · ${Vendedor.PRECO_TXT}" + if (teste) " · MODO DE TESTE" else "", 12.5f, T.texto2)
        n.gravity = Gravity.CENTER; n.setPadding(0, Ui.dp(this, 8), 0, 0)
        area.addView(n, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (br.isNotEmpty()) area.addView(Ui.botao(this, "Copiar Pix copia e cola", T.verde, false) { copiar("pix", br) }, Ui.linhaLp(this, 10, 0))
    }

    fun vigiar(id: String) {
        val meu = ++vigiaPix
        msg.text = "Aguardando o pagamento… o Pro ativa sozinho assim que o Pix cair."; msg.setTextColor(T.azul)
        Thread {
            val fim = System.currentTimeMillis() + 30 * 60_000L
            while (vigiaPix == meu && System.currentTimeMillis() < fim) {
                val st = Compra.consultar(id)
                if (st != null && st.pago && st.chave.isNotEmpty()) {
                    val (ok, m) = Licenca.ativar(st.chave)
                    Compra.limpar()
                    runOnUiThread {
                        msg.text = (if (ok) "Pagamento confirmado! Pro ativo até ${st.validade}." else m) + if (st.emailEnviado) "\nEnviamos o pedido e a chave para o seu e-mail." else ""
                        msg.setTextColor(if (ok) T.verde else T.amarelo)
                        msg.postDelayed({ mostrar() }, 3500)
                    }
                    return@Thread
                }
                if (st != null && (st.outro == "expired" || st.outro == "cancelled")) {
                    Compra.limpar()
                    runOnUiThread { msg.text = "Este Pix expirou. Gere um novo."; msg.setTextColor(T.amarelo); area.removeAllViews() }
                    return@Thread
                }
                try { Thread.sleep(4000) } catch (e: InterruptedException) { }
            }
        }.start()
    }

    botao.setOnClickListener {
        val email = et.text.toString().trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { msg.text = "Digite um e-mail válido."; msg.setTextColor(T.vermelho); return@setOnClickListener }
        msg.text = "Gerando o Pix…"; msg.setTextColor(T.texto2)
        botao.isEnabled = false
        Thread {
            try {
                val p = Compra.criar(email)
                runOnUiThread { botao.isEnabled = true; mostrarPix(p.numero, p.brCode, p.qr, p.teste); vigiar(p.id) }
            } catch (e: Exception) {
                runOnUiThread { botao.isEnabled = true; msg.text = e.message ?: "Sem conexão. Tente de novo."; msg.setTextColor(T.vermelho) }
            }
        }.start()
    }

    // retoma um Pix ja gerado (o app foi fechado antes de pagar)
    val pend = Store.str("lic_pedido")
    if (pend.isNotEmpty()) { mostrarPix(Store.str("lic_pedido_num"), Store.str("lic_pedido_br"), Store.str("lic_pedido_qr"), false); vigiar(pend) }
}

private fun MainActivity.secaoChave(col: LinearLayout) {
    // passo 3: ativar
    val c3 = Ui.vertical(this)
    c3.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16))
    c3.background = Ui.forma(this, T.surface, 18, T.outline)
    c3.addView(Ui.texto(this, if (Vendedor.automatico()) "Já tem uma chave? Cole aqui" else "3. Cole a chave que você recebeu", 14.5f, T.texto, Ui.medio))
    val et = EditText(this)
    et.hint = "PCT-…"
    et.setTextColor(T.texto); et.setHintTextColor(T.texto3)
    et.typeface = Typeface.MONOSPACE; et.textSize = 13f
    c3.addView(et)
    val msg = Ui.texto(this, "", 12.5f, T.texto2)
    c3.addView(msg)
    val linha = Ui.horizontal(this)
    linha.addView(Ui.botao(this, "Colar", T.verde, false) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        et.setText(cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: "")
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    linha.addView(Ui.espaco(this, 10, 1))
    linha.addView(Ui.botao(this, "Ativar", T.verde, true) {
        val (ok, m) = Licenca.ativar(et.text.toString())
        msg.text = m; msg.setTextColor(if (ok) T.verde else T.vermelho)
        if (ok) msg.postDelayed({ mostrar() }, 1200)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    c3.addView(linha, Ui.linhaLp(this, 10, 0))
    col.addView(c3, Ui.linhaLp(this))
}
