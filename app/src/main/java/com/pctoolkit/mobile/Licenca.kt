package com.pctoolkit.mobile

import android.content.ClipData
import android.app.AlertDialog
import android.widget.Toast
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
    const val PRECO = "15.00"
    const val PRECO_TXT = "R$ 15"
    const val TESTE_TXT = "1 hora"
    fun temPix() = CHAVE_PIX.isNotBlank() && NOME.isNotBlank()
    fun automatico() = SERVIDOR.startsWith("https://")
}

/** Compra automatica: Pix pela PurinCash, chave por e-mail e ativacao sozinha. */
object Compra {
    class Pedido(val id: String, val numero: String, val brCode: String, val qr: String, val teste: Boolean, val valor: String = "")
    class Status(val pago: Boolean, val chave: String, val validade: String, val emailEnviado: Boolean, val outro: String, val voucher: String = "", val para: String = "")

    private fun http(metodo: String, caminho: String, corpo: String?): Pair<Int, String> {
        val c = URL(Vendedor.SERVIDOR.trimEnd('/') + caminho).openConnection() as HttpURLConnection
        c.requestMethod = metodo; c.connectTimeout = 15000; c.readTimeout = 25000
        if (corpo != null) { c.doOutput = true; c.setRequestProperty("content-type", "application/json"); c.outputStream.use { it.write(corpo.toByteArray()) } }
        val code = c.responseCode
        val t = try { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
        c.disconnect()
        return Pair(code, t)
    }

    class Plano(val id: String, val nome: String, val dias: Int, val centavos: Int, val vitalicio: Boolean = false) {
        fun preco(c: Int = centavos) = "R$ " + String.format(Locale("pt", "BR"), "%.2f", c / 100.0).replace(".00", "").replace(",00", "")
        fun porMes() = if (dias > 0) centavos * 30 / dias else centavos
    }
    val PLANOS_PADRAO = listOf(Plano("m1", "1 mês", 30, 1500), Plano("m3", "3 meses", 90, 3900), Plano("m6", "6 meses", 180, 6900))

    /** Planos do servidor (com os precos atuais); sem internet usa os padroes. */
    fun planos(): List<Plano> = try {
        val (code, t) = http("GET", "/planos?v=2", null)
        if (code != 200) PLANOS_PADRAO else {
            val a = org.json.JSONObject(t).getJSONArray("planos")
            (0 until a.length()).map { a.getJSONObject(it) }.map { Plano(it.getString("id"), it.getString("nome"), it.getInt("dias"), it.getInt("centavos"), it.optBoolean("vitalicio")) }.ifEmpty { PLANOS_PADRAO }
        }
    } catch (e: Exception) { PLANOS_PADRAO }

    /** Confere um cupom. Devolve o preco final em centavos, ou lanca erro com a mensagem. */
    fun cupom(codigo: String, plano: String): Int {
        val cod = codigo.trim().uppercase().replace(" ", "")
        val (code, t) = try { http("GET", "/cupom?c=" + Uri.encode(cod) + "&plano=" + plano + "&codigo=" + Licenca.codigo(), null) } catch (e: Exception) { throw IllegalStateException("Sem internet para conferir o cupom. Tente de novo.") }
        val o = try { org.json.JSONObject(t) } catch (e: Exception) { org.json.JSONObject() }
        if (code == 200 && o.optBoolean("ok")) return o.getInt("centavos")
        // explica o motivo de verdade (o servidor responde igual para todos os casos)
        throw IllegalStateException(when {
            code >= 500 || code == 0 -> "O servidor não respondeu. Tente de novo em instantes."
            cod == "IND" + Licenca.codigo() -> "Esse é o SEU código de indicação: ele serve para os seus amigos, não vale no seu próprio celular."
            Regex("^IND[A-Z2-9]{8}$").matches(cod) -> "Esse código de indicação ainda não está valendo. Quem indicou precisa ter o Pro comprado e abrir o app atualizado uma vez."
            cod.startsWith("IND") -> "Código de indicação incompleto. Ele tem IND + 8 letras/números (ex.: INDABCD2345)."
            cod == "RENOVA10" -> "O RENOVA10 só vale para renovar um Pro comprado que vence em até 7 dias."
            cod.startsWith("PRE") || cod.startsWith("PCT") -> "Isso não é cupom: é um código de presente ou uma chave. Cole em “Tem um código?”, mais abaixo."
            else -> "O cupom $cod não existe, foi pausado ou já atingiu o limite de usos. Confira as letras."
        })
    }

    /** Teste gratis do Pro (uma vez por celular). Devolve a mensagem para o usuario. */
    fun teste(): Pair<Boolean, String> {
        val (code, t) = try { http("POST", "/teste", org.json.JSONObject().put("codigo", Licenca.codigo()).toString()) } catch (e: Exception) { return Pair(false, "Sem conexão. Tente de novo.") }
        val o = try { org.json.JSONObject(t) } catch (e: Exception) { org.json.JSONObject() }
        if (code == 200 || code == 409) Store.putBool("teste_usado", true)
        if (code != 200) return Pair(false, o.optString("erro", "Não deu para liberar o teste agora."))
        val ate = o.optLong("ate", 0L)
        val chave = o.optString("chave")
        if (ate > 0) { Store.putStr("lic_teste_chave", chave.trim()); Store.putStr("lic_teste_ate", ate.toString()); Store.putStr("lic_ate_tipo", "teste") }
        val (ok, m) = Licenca.ativar(chave)
        return Pair(ok, if (ok) "Teste liberado! Pro ativo até ${Licenca.validadeTexto()}." else m)
    }

    /** Troca de celular, passo 1: manda um codigo para o e-mail da compra. */
    fun trocaPedir(email: String): String? {
        val (code, t) = try { http("POST", "/troca/pedir", org.json.JSONObject().put("email", email).put("codigo", Licenca.codigo()).toString()) } catch (e: Exception) { return "Sem conexão. Tente de novo." }
        if (code == 200) return null
        return try { org.json.JSONObject(t).optString("erro", "Não deu certo.") } catch (e: Exception) { "Não deu certo (erro $code)." }
    }

    /** Troca de celular, passo 2: confirma o codigo e ativa o Pro neste celular. */
    fun trocaConfirmar(email: String, otp: String): Pair<Boolean, String> {
        val (code, t) = try { http("POST", "/troca/confirmar", org.json.JSONObject().put("email", email).put("codigo", Licenca.codigo()).put("otp", otp).toString()) } catch (e: Exception) { return Pair(false, "Sem conexão. Tente de novo.") }
        val o = try { org.json.JSONObject(t) } catch (e: Exception) { org.json.JSONObject() }
        if (code != 200) return Pair(false, o.optString("erro", "Não deu certo."))
        val (ok, m) = Licenca.ativar(o.optString("chave"))
        return Pair(ok, if (ok) "Pro transferido para este celular! Ativo até ${o.optString("validade")}." else m)
    }

    /** Cria o Pix. Devolve o pedido ou lanca erro com a mensagem para o usuario. */
    fun criar(email: String, plano: String = "m1", cupom: String = "", presente: String = ""): Pedido {
        val atual = if (Licenca.ativo() && presente.isEmpty()) Licenca.diaValidade() else 0
        val corpo = org.json.JSONObject().put("codigo", Licenca.codigo()).put("email", email).put("atual", atual).put("plano", plano).put("cupom", cupom).put("presente", presente).toString()
        val (code, t) = http("POST", "/pedido", corpo)
        val o = try { org.json.JSONObject(t) } catch (e: Exception) { org.json.JSONObject() }
        if (code !in 200..299) throw IllegalStateException(o.optString("erro", "Não consegui gerar o Pix (erro $code)."))
        val p = Pedido(o.getString("pedido"), o.optString("numero"), o.optString("brCode"), o.optString("qr"), o.optBoolean("teste"), o.optString("valor"))
        Store.putStr("lic_pedido", p.id); Store.putStr("lic_pedido_num", p.numero); Store.putStr("lic_pedido_br", p.brCode); Store.putStr("lic_pedido_qr", p.qr)
        Store.putStr("lic_pedido_em", System.currentTimeMillis().toString())
        Store.putStr("lic_pedido_valor", p.valor)
        Store.putStr("lic_email", email)
        return p
    }

    fun consultar(id: String): Status? {
        return try {
            val (code, t) = http("GET", "/status?pedido=" + Uri.encode(id), null)
            if (code == 404) return Status(false, "", "", false, "desconhecido")
            val o = org.json.JSONObject(t)
            Status(o.optString("status") == "pago", o.optString("chave"), o.optString("validade"), o.optBoolean("email"), o.optString("status"), o.optString("voucher"), o.optString("para"))
        } catch (e: Exception) { null }
    }

    /** Confere um pedido pendente (ao abrir o app). Ativa sozinho se ja foi pago. */
    fun conferirPendente(): Boolean {
        if (!Vendedor.automatico()) return false
        val id = Store.str("lic_pedido")
        if (id.isEmpty()) return false
        val desde = Store.str("lic_pedido_em").toLongOrNull() ?: 0L
        val st = consultar(id) ?: return false
        if (st.pago && st.voucher.isNotEmpty()) { Store.putStr("ultimo_presente", st.voucher + " → " + st.para); limpar(); return true }
        if (st.pago && st.chave.isNotEmpty()) { Licenca.ativar(st.chave); Store.putBool("lic_comprou", true); limpar(); return true }
        if (st.outro == "expired" || st.outro == "cancelled" || st.outro == "desconhecido" || System.currentTimeMillis() - desde > 2 * 86_400_000L) limpar()
        return false
    }

    /**
     * Recupera a chave deste celular no servidor (reinstalou o app, limpou os dados...).
     * A chave so funciona neste aparelho, entao o servidor pode devolve-la pelo codigo.
     */
    fun restaurar(forcar: Boolean = false): Boolean {
        if (!Vendedor.automatico()) return false
        val ultima = Store.str("lic_rest_em").toLongOrNull() ?: 0L
        if (!forcar && System.currentTimeMillis() - ultima < 12 * 3_600_000L) return false
        Store.putStr("lic_rest_em", System.currentTimeMillis().toString())
        return try {
            val (code, t) = http("GET", "/licenca?codigo=" + Licenca.codigo(), null)
            if (code == 410 && t.contains("revogada")) { Store.putStr("lic_chave", ""); Store.putStr("lic_registrada", ""); return true }
            if (code != 200) return false
            val oj = org.json.JSONObject(t); val ch = oj.optString("chave"); val ate = oj.optLong("ate", 0L)
            if (ate > 0 && ch.isNotEmpty()) { Store.putStr("lic_teste_chave", ch.trim()); Store.putStr("lic_teste_ate", ate.toString()); Store.putStr("lic_ate_tipo", "cortesia") }
            // ja ativo: so troca se a chave do servidor vale ate mais tarde (dias ganhos por indicacao, presente...)
            if (ch.isEmpty() || (Licenca.ativo() && Licenca.conferir(ch) <= Licenca.diaValidade())) return false
            val ok = Licenca.ativar(ch).first
            if (ok) Store.putBool("lic_comprou", true)
            ok
        } catch (e: Exception) { false }
    }

    /** Avisa o servidor qual chave este celular usa (assim o codigo de indicacao vale e o painel enxerga). Uma vez por chave. */
    fun registrar() {
        if (!Vendedor.automatico() || !Licenca.ativo()) return
        val ch = Store.str("lic_chave")
        if (ch.isEmpty() || Store.str("lic_registrada") == ch.hashCode().toString()) return
        try {
            val (code, t) = http("POST", "/registrar", org.json.JSONObject().put("codigo", Licenca.codigo()).put("chave", ch).toString())
            if (code == 410 && t.contains("revogada")) { Store.putStr("lic_chave", ""); return }
            if (code == 200) Store.putStr("lic_registrada", ch.hashCode().toString())
        } catch (e: Exception) { }
    }

    /** Que tipo de codigo a pessoa colou? */
    fun tipoCodigo(t: String): String {
        val c = t.trim().uppercase().replace(" ", "")
        return when {
            Licenca.normalizar(t) != null || c.startsWith("PCT-") || Regex("^[A-Z2-9]{4}-?[A-Z2-9]{4}\\.\\d+\\.").containsMatchIn(c) -> "chave"
            Regex("^PRE[A-Z2-9]{9}$").matches(c) -> "presente"
            Regex("^[A-Z0-9]{3,20}$").matches(c) -> "cupom"
            else -> ""
        }
    }

    /** Resgata um codigo de presente neste celular. */
    fun resgatar(voucher: String): Pair<Boolean, String> {
        val (code, t) = try { http("POST", "/presente", org.json.JSONObject().put("codigo", Licenca.codigo()).put("voucher", voucher).toString()) } catch (e: Exception) { return Pair(false, "Sem conexão. Tente de novo.") }
        val o = try { org.json.JSONObject(t) } catch (e: Exception) { org.json.JSONObject() }
        if (code != 200) return Pair(false, o.optString("erro", "Não deu certo."))
        val chave = o.optString("chave"); val ate = o.optLong("ate", 0L)
        if (ate > 0) { Store.putStr("lic_teste_chave", chave.trim()); Store.putStr("lic_teste_ate", ate.toString()); Store.putStr("lic_ate_tipo", "cortesia") }
        val (ok, m) = Licenca.ativar(chave)
        if (ok) Store.putBool("lic_comprou", true)
        return Pair(ok, if (ok) "Código ativado! Pro válido ${if (ate > 0) "" else "até "}${o.optString("validade")}." else m)
    }

    /** Pesquisa rapida quando o Pro vence sem renovar. */
    fun pesquisa(motivo: Int) { try { http("POST", "/pesquisa", org.json.JSONObject().put("codigo", Licenca.codigo()).put("motivo", motivo).toString()) } catch (e: Exception) { } }

    /** Aviso do dono para todos (promocao, novidade). Devolve id|texto|link ou null. */
    fun aviso(): Triple<Long, String, String>? = try {
        val (code, t) = http("GET", "/aviso", null)
        val o = org.json.JSONObject(t)
        if (code == 200 && o.optString("texto").isNotBlank()) Triple(o.optLong("id"), o.optString("texto"), o.optString("link")) else null
    } catch (e: Exception) { null }

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

    private val FORMATO = Regex("(?i:PCT-?)?([A-Za-z2-9]{4}-?[A-Za-z2-9]{4})\\.(\\d{1,6})\\.([A-Za-z0-9_-]{86})")

    /**
     * Acha a chave dentro do texto colado, mesmo com sujeira: espacos invisiveis que apps de e-mail colocam,
     * quebras de linha, aspas ou palavras copiadas junto. Devolve "PCT-CODIGO.dia.assinatura" ou null.
     */
    fun normalizar(texto: String): String? {
        val limpo = texto.filter { (it.code < 128 && it.isLetterOrDigit()) || it == '.' || it == '-' || it == '_' }
        val m = FORMATO.find(limpo) ?: return null
        return "PCT-" + m.groupValues[1].uppercase().replace("-", "") + "." + m.groupValues[2] + "." + m.groupValues[3]
    }

    /** Confere uma chave. Devolve o dia de validade (dias desde 1970) ou -1 se invalida para este aparelho. */
    fun conferir(chave: String): Int {
        return try {
            val t = (normalizar(chave) ?: chave.trim().replace("\\s".toRegex(), "")).removePrefix("PCT-").removePrefix("pct-")
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

    /** Fim do teste gratis (em milissegundos), se a chave atual for a do teste; senao 0. */
    fun fimTeste(): Long {
        val c = Store.str("lic_chave"); if (c.isEmpty() || c != Store.str("lic_teste_chave")) return 0L
        return Store.str("lic_teste_ate").toLongOrNull() ?: 0L
    }

    fun ativo(): Boolean {
        val d = diaValidade()
        if (d <= 0) return false
        val ft = fimTeste()
        if (ft > 0) return agoraConfiavel() < ft
        return agoraConfiavel() < (d + 1L) * DIA
    }

    fun diasRestantes(): Int { val d = diaValidade(); if (d <= 0) return 0; return (((d + 1L) * DIA - agoraConfiavel()) / DIA).toInt().coerceAtLeast(0) }

    fun vitalicio(): Boolean = diaValidade() >= 20000

    fun validadeTexto(): String {
        val d = diaValidade(); if (d <= 0) return ""
        if (d >= 20000) return "sempre (vitalício)"
        val ft = fimTeste()
        if (ft > 0) { val h = SimpleDateFormat("HH:mm", Locale.US); return "às " + h.format(Date(ft)) + (if (Store.str("lic_ate_tipo") == "cortesia") "" else " (teste grátis)") }
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
        Store.putStr("lic_chave", normalizar(chave) ?: chave.trim())
        try { AvisoPro.agendar(PcApp.ctx) } catch (e: Exception) { }
        return Pair(true, if (vitalicio()) "Pro vitalício ativado!" else "Pro ativado até ${validadeTexto()}!")
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
        "Modo Frio e Turbo no Modo Jogo",
        "Mira na tela para jogos de tiro",
        "Abrir os seus botões sozinho junto com o jogo",
        "Modo Jogo sem limite (grátis: ${ModoJogo.GRATIS_POR_DIA} por dia) e Modo campeonato",
        "Medidor de FPS e monitor de temperatura na tela, gravar a tela",
        "Internet para jogos, DNS mais rápido e ajustes por jogo",
        "Limpeza avançada (duplicados, grandes, apps esquecidos) e Blindagem contra vírus"
    )

    fun ativo(): Boolean = Licenca.ativo()

    /** Dentro de uma tarefa: true se pode usar; senao explica e oferece assinar. */
    fun exigir(con: Console, recurso: String): Boolean {
        if (ativo()) return true
        con.aviso("$recurso é uma função do SerpyraToolkit Pro.")
        con.info("Pro: a partir de ${Vendedor.PRECO_TXT} por mês. Libera: " + recursos.joinToString("; "))
        if (!Store.bool("teste_usado", false) && Licenca.diaValidade() <= 0) con.info("Dá para testar grátis por ${Vendedor.TESTE_TXT} na tela do Pro.")
        val d = Licenca.diaValidade()
        if (d > 0) con.info(if (Licenca.fimTeste() > 0) "Seu teste grátis de ${Vendedor.TESTE_TXT} terminou." else "Sua assinatura venceu em ${Licenca.validadeTexto()}.")
        if (con.ask("Assinar, testar grátis ou ativar uma chave agora?", "Ver o Pro", "Agora não", importante = true)) {
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
    Relatorio.evento("pro")
    Thread { Licenca.sincronizarHora() }.start()
    val col = coluna()
    val ativo = Licenca.ativo()
    col.addView(barraTopo("SerpyraToolkit Pro", if (ativo && Licenca.vitalicio()) "Vitalício: não vence" else if (ativo) "Ativo até ${Licenca.validadeTexto()}" else "Teste grátis ou a partir de ${Vendedor.PRECO_TXT} por mês"))

    // status
    val st = Ui.vertical(this)
    st.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18))
    st.background = Ui.gradiente(this, 0xFF0F2A22.toInt(), 0xFF101C30.toInt(), 22)
    val topo = Ui.horizontal(this)
    topo.addView(Ui.texto(this, if (ativo) "Pro ativo" else "Versão grátis", 20f, T.texto, Ui.negrito), Ui.peso())
    topo.addView(Ui.chip(this, if (ativo && Licenca.vitalicio()) "Vitalício" else if (ativo) "${Licenca.diasRestantes()} dias" else "Pro bloqueado", if (ativo) (if (Licenca.diasRestantes() <= 3) T.amarelo else T.verde) else T.texto3))
    st.addView(topo)
    for (r in Pro.recursos) {
        val v = Ui.texto(this, (if (ativo) "✓  " else "🔒  ") + r, 13f, if (ativo) T.texto else T.texto2)
        v.setPadding(0, Ui.dp(this, 8), 0, 0)
        st.addView(v)
    }
    if (ativo && !Licenca.vitalicio() && Licenca.diasRestantes() <= 3) {
        val a = Ui.texto(this, "Sua assinatura vence em breve. Renove para não perder as funções.", 12.5f, T.amarelo)
        a.setPadding(0, Ui.dp(this, 10), 0, 0); st.addView(a)
    }
    col.addView(st, Ui.linhaLp(this, 0, 6))

    if (Vendedor.automatico()) { secaoAutomatica(col, ativo); secaoChave(col); setContentView(raiz(col)); col.postDelayed({ if (!isFinishing) oferecerCopiado() }, 700); return }

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
    // teste gratis (uma vez por celular)
    if (!ativo && Licenca.diaValidade() <= 0 && !Store.bool("teste_usado", false)) {
        val ct = Ui.vertical(this)
        ct.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16))
        ct.background = Ui.gradiente(this, 0xFF14301F.toInt(), 0xFF121821.toInt(), 18)
        ct.addView(Ui.texto(this, "Teste o Pro grátis por ${Vendedor.TESTE_TXT}", 16f, T.texto, Ui.negrito))
        ct.addView(Ui.texto(this, "Sem pagar nada e sem cadastro. Vale uma vez por celular e por internet. Depois, se gostar, é só assinar.", 12.5f, T.texto2))
        val mt = Ui.texto(this, "", 12.5f, T.texto2)
        lateinit var bt: LinearLayout
        bt = Ui.botao(this, "Começar o teste grátis", T.verde, true) {
            bt.isEnabled = false; mt.text = "Liberando…"; mt.setTextColor(T.texto2)
            Thread {
                val (ok, m) = Compra.teste()
                runOnUiThread { mt.text = m; mt.setTextColor(if (ok) T.verde else T.amarelo); bt.isEnabled = !ok; if (ok) mt.postDelayed({ mostrar() }, 1600) }
            }.start()
        }
        ct.addView(bt, Ui.linhaLp(this, 12, 0))
        mt.setPadding(0, Ui.dp(this, 8), 0, 0)
        ct.addView(mt)
        col.addView(ct, Ui.linhaLp(this, 10))
    }

    // indique e ganhe
    if (ativo) {
        val ci = Ui.vertical(this)
        ci.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16))
        ci.background = Ui.gradiente(this, 0xFF2A2210.toInt(), 0xFF121821.toInt(), 18)
        ci.addView(Ui.texto(this, "Indique e ganhe", 16f, T.texto, Ui.negrito))
        ci.addView(Ui.texto(this, "Seu amigo ganha 10% de desconto com o seu código. Você ganha dias de Pro a cada compra dele: 5 dias (plano de 1 mês), 10 (3 meses), 15 (6 meses) ou 30 (vitalício), até 15 dias por mês" + (if (Licenca.vitalicio()) " (você já é vitalício, mas seu amigo ainda ganha o desconto)." else "."), 12.5f, T.texto2))
        val codInd = "IND" + Licenca.codigo()
        val ct = Ui.texto(this, codInd, 22f, T.amarelo, Typeface.MONOSPACE)
        ct.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4)); ct.setTextIsSelectable(true)
        ci.addView(ct)
        val bi = Ui.horizontal(this)
        bi.addView(Ui.botao(this, "Copiar", T.amarelo, false) { copiar("indicacao", codInd) }, Ui.peso())
        bi.addView(Ui.espaco(this, 10, 1))
        bi.addView(Ui.botao(this, "Compartilhar", T.amarelo, true) {
            val txt = "Uso o SerpyraToolkit para jogar melhor no celular (Modo Jogo, botões na tela, Modo Frio).\n\n1. Baixe: https://serpyratoolkit.pages.dev/?cupom=$codInd\n2. Abra o app e toque em SerpyraToolkit Pro\n3. Cole o código $codInd no campo \"Tem um código?\" ou em Cupom\n\nVocê ganha 10% de desconto em qualquer plano."
            try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt), "Indicar o SerpyraToolkit")) } catch (e: Exception) { }
        }, Ui.peso())
        ci.addView(bi, Ui.linhaLp(this, 8, 0))
        ci.addView(Ui.texto(this, "Vale para quem já comprou ou ganhou o Pro (não vale no teste grátis). O seu amigo cola o código no campo “Tem um código?” desta mesma tela.", 11.5f, T.texto3), Ui.linhaLp(this, 6, 0))
        Thread { Compra.registrar() }.start()
        col.addView(ci, Ui.linhaLp(this, 10))
    }

    // codigo de presente
    run {
        val cg = Ui.vertical(this)
        cg.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 14))
        cg.background = Ui.forma(this, T.surface, 18, T.outline)
        cg.addView(Ui.texto(this, "Tenho um código de presente 🎁", 14.5f, T.texto, Ui.medio))
        val eg = EditText(this)
        eg.hint = "PRE…"
        eg.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        eg.setTextColor(T.texto); eg.setHintTextColor(T.texto3)
        val lg = Ui.horizontal(this)
        lg.addView(eg, Ui.peso())
        val mg = Ui.texto(this, "", 12.5f, T.texto2)
        lateinit var bg: LinearLayout
        bg = Ui.botao(this, "Ativar", T.verde, false) {
            val v = eg.text.toString().trim().uppercase()
            if (v.length < 6) { mg.text = "Digite o código que chegou no e-mail."; mg.setTextColor(T.amarelo); return@botao }
            bg.isEnabled = false; mg.text = "Conferindo…"; mg.setTextColor(T.texto2)
            Thread { val (ok, m) = Compra.resgatar(v); runOnUiThread { bg.isEnabled = !ok; mg.text = m; mg.setTextColor(if (ok) T.verde else T.amarelo); if (ok) mg.postDelayed({ mostrar() }, 1800) } }.start()
        }
        lg.addView(bg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        cg.addView(lg, Ui.linhaLp(this, 4, 0))
        cg.addView(mg)
        Store.str("ultimo_presente").takeIf { it.isNotEmpty() }?.let { cg.addView(Ui.texto(this, "Último presente que você deu: $it", 11.5f, T.texto3), Ui.linhaLp(this, 6, 0)) }
        col.addView(cg, Ui.linhaLp(this, 10))
    }

    val sempre = ativo && Licenca.vitalicio()
    col.addView(Ui.secao(this, if (sempre) "Dar o Pro de presente" else if (ativo) "Renovar (os dias se somam)" else "Assinar com Pix"))
    val c = Ui.vertical(this)
    c.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16))
    c.background = Ui.forma(this, T.surface, 18, T.outline)

    // planos
    c.addView(Ui.texto(this, "Escolha o plano", 14.5f, T.texto, Ui.medio))
    var planos = Compra.PLANOS_PADRAO
    var escolhido = Store.str("lic_plano").ifEmpty { "m1" }
    var precoCupom = -1
    var cupomOk = ""
    val listaPlanos = Ui.vertical(this)
    c.addView(listaPlanos, Ui.linhaLp(this, 8, 4))
    lateinit var botaoTxt: () -> Unit
    var aplicarCupom: (String) -> Unit = {}
    fun desenharPlanos() {
        listaPlanos.removeAllViews()
        val base = planos.firstOrNull { it.id == "m1" }?.porMes() ?: 1000
        for (pl in planos) {
            val sel = pl.id == escolhido
            val linha = Ui.horizontal(this)
            linha.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12))
            linha.background = Ui.forma(this, if (sel) Ui.alpha(T.verde, 0x22) else T.surface2, 14, if (sel) T.verde else T.outline, if (sel) 2 else 1)
            val tx = Ui.vertical(this)
            tx.addView(Ui.texto(this, pl.nome, 15f, T.texto, Ui.medio))
            tx.addView(Ui.texto(this, if (pl.vitalicio) "Paga uma vez e usa para sempre" else if (pl.dias > 30) "${pl.preco(pl.porMes())} por mês" else "Renove quando quiser", 12f, if (pl.vitalicio) T.amarelo else T.texto3))
            linha.addView(tx, Ui.peso())
            val eco = if (base > 0 && !pl.vitalicio) 100 - pl.porMes() * 100 / base else 0
            if (eco >= 5) { linha.addView(Ui.chip(this, "−$eco%", T.verde)); linha.addView(Ui.espaco(this, 10, 1)) }
            linha.addView(Ui.texto(this, pl.preco(), 16f, if (sel) T.verde else T.texto, Ui.negrito))
            linha.isClickable = true
            linha.setOnClickListener { escolhido = pl.id; Store.putStr("lic_plano", pl.id); precoCupom = -1; desenharPlanos(); botaoTxt(); if (cupomOk.isNotEmpty()) aplicarCupom(cupomOk) }
            listaPlanos.addView(linha, Ui.linhaLp(this, 0, 8))
        }
    }

    c.addView(Ui.texto(this, "Seu e-mail", 14.5f, T.texto, Ui.medio), Ui.linhaLp(this, 10, 0))
    c.addView(Ui.texto(this, "Você recebe nele o pedido, a confirmação da compra e a sua chave.", 12f, T.texto3))
    val et = EditText(this)
    et.hint = "voce@email.com"
    et.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    et.setText(Store.str("lic_email"))
    et.setTextColor(T.texto); et.setHintTextColor(T.texto3)
    c.addView(et)

    // cupom
    val lc = Ui.horizontal(this)
    val ec = EditText(this)
    ec.hint = "Cupom de desconto (opcional)"
    ec.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
    ec.setTextColor(T.texto); ec.setHintTextColor(T.texto3)
    lc.addView(ec, Ui.peso())
    val msgCupom = Ui.texto(this, "", 12.5f, T.texto2)
    val pendente = Store.str("cupom_pendente")
    if (pendente.isNotEmpty()) { ec.setText(pendente); msgCupom.text = "Código $pendente pronto: toque em Aplicar (ou direto em Gerar Pix)."; msgCupom.setTextColor(T.azul) }
    else if (ativo && !sempre && Licenca.diasRestantes() <= 7 && Store.bool("lic_comprou", false)) {
        // so mostra o desconto de renovacao se o servidor confirmar que vale para este celular
        Thread { try { Compra.cupom("RENOVA10", escolhido); runOnUiThread { if (ec.text.isEmpty()) { ec.setText("RENOVA10"); msgCupom.text = "Seu Pro vence em ${Licenca.diasRestantes()} dia(s): renovando agora você ganha 10% (já está no campo)."; msgCupom.setTextColor(T.amarelo) } } } catch (e: Exception) { } }.start()
    }
    aplicarCupom = { codigo ->
        val cod = codigo.trim().uppercase()
        msgCupom.text = "Conferindo o cupom $cod…"; msgCupom.setTextColor(T.texto2)
        Thread {
            try {
                val v = Compra.cupom(cod, escolhido)
                val pl = planos.firstOrNull { it.id == escolhido }
                runOnUiThread { precoCupom = v; cupomOk = cod; Store.putStr("cupom_pendente", ""); msgCupom.text = "Cupom $cod aplicado: " + (pl?.let { "${it.preco()} → ${it.preco(v)}" } ?: ""); msgCupom.setTextColor(T.verde); botaoTxt() }
            } catch (e: Exception) {
                Store.putStr("cupom_pendente", "")
                runOnUiThread { precoCupom = -1; cupomOk = ""; msgCupom.text = e.message ?: "Cupom inválido."; msgCupom.setTextColor(T.vermelho); botaoTxt() }
            }
        }.start()
    }
    lc.addView(Ui.botao(this, "Aplicar", T.verde, false) {
        val cod = ec.text.toString().trim()
        if (cod.isEmpty()) { msgCupom.text = "Digite o cupom."; msgCupom.setTextColor(T.amarelo); return@botao }
        aplicarCupom(cod)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    c.addView(lc, Ui.linhaLp(this, 6, 0))
    c.addView(msgCupom)

    // presente
    val cbPres = android.widget.CheckBox(this)
    cbPres.text = "É um presente para outra pessoa"
    cbPres.setTextColor(T.texto)
    cbPres.isChecked = sempre
    if (sempre) cbPres.visibility = android.view.View.GONE
    val ep = EditText(this)
    ep.hint = "E-mail de quem vai ganhar"
    ep.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    ep.setTextColor(T.texto); ep.setHintTextColor(T.texto3)
    ep.visibility = if (sempre) android.view.View.VISIBLE else android.view.View.GONE
    val dicaPres = Ui.texto(this, "A pessoa recebe um código por e-mail e ativa no celular dela. O seu Pro não muda.", 12f, T.texto3)
    dicaPres.visibility = ep.visibility
    cbPres.setOnCheckedChangeListener { _, marcado -> val v = if (marcado) android.view.View.VISIBLE else android.view.View.GONE; ep.visibility = v; dicaPres.visibility = v }
    c.addView(cbPres, Ui.linhaLp(this, 8, 0))
    c.addView(ep)
    c.addView(dicaPres)

    val area = Ui.vertical(this)
    val msg = Ui.texto(this, "", 13f, T.texto2)
    msg.setPadding(0, Ui.dp(this, 8), 0, 0)
    val botao = Ui.botao(this, "Gerar Pix", T.verde, true) { }
    val rotulo = botao.getChildAt(botao.childCount - 1) as android.widget.TextView
    botaoTxt = {
        val pl = planos.firstOrNull { it.id == escolhido } ?: planos.first()
        rotulo.text = if (precoCupom >= 0) "Gerar Pix de ${pl.preco(precoCupom)} (com cupom)" else "Gerar Pix de ${pl.preco()}"
    }
    desenharPlanos(); botaoTxt()
    Thread { val l = Compra.planos(); runOnUiThread { planos = l; if (planos.none { it.id == escolhido }) escolhido = planos.first().id; desenharPlanos(); botaoTxt() } }.start()
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
        val valor = Store.str("lic_pedido_valor").ifEmpty { Vendedor.PRECO_TXT }
        val n = Ui.texto(this, "Pedido $numero · $valor" + if (teste) " · MODO DE TESTE" else "", 12.5f, T.texto2)
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
                if (st != null && st.pago && st.voucher.isNotEmpty()) {
                    Store.putStr("ultimo_presente", st.voucher + " → " + st.para)
                    Compra.limpar()
                    runOnUiThread {
                        msg.text = "Pagamento confirmado! Mandamos o código de presente ${st.voucher} para ${st.para}." + if (st.emailEnviado) "" else "\nSe o e-mail não chegar, passe este código para a pessoa."
                        msg.setTextColor(T.verde); area.removeAllViews()
                    }
                    return@Thread
                }
                if (st != null && st.pago && st.chave.isNotEmpty()) {
                    Store.putBool("lic_comprou", true)
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
        val digitado = ec.text.toString().trim().uppercase()
        if (digitado.isNotEmpty() && digitado != cupomOk) {
            // a pessoa digitou o cupom mas nao tocou em Aplicar: confere agora em vez de ignorar
            msg.text = "Conferindo o cupom antes de gerar o Pix…"; msg.setTextColor(T.texto2)
            Thread {
                try { val v = Compra.cupom(digitado, escolhido); runOnUiThread { precoCupom = v; cupomOk = digitado; botaoTxt(); botao.performClick() } }
                catch (e: Exception) {
                    Store.putStr("cupom_pendente", "")
                    runOnUiThread {
                        msg.text = ""
                        AlertDialog.Builder(this).setTitle("O cupom $digitado não vale")
                            .setMessage((e.message ?: "Cupom inválido.") + "\n\nQuer pagar o preço normal agora?")
                            .setPositiveButton("Pagar sem cupom") { _, _ -> ec.setText(""); cupomOk = ""; precoCupom = -1; msgCupom.text = ""; botaoTxt(); botao.performClick() }
                            .setNegativeButton("Corrigir o cupom", null).show()
                    }
                }
            }.start()
            return@setOnClickListener
        }
        if (digitado.isEmpty()) { cupomOk = ""; precoCupom = -1 }
        val presente = if (cbPres.isChecked) ep.text.toString().trim() else ""
        if (cbPres.isChecked && !android.util.Patterns.EMAIL_ADDRESS.matcher(presente).matches()) { msg.text = "Digite o e-mail de quem vai ganhar o presente."; msg.setTextColor(T.vermelho); return@setOnClickListener }
        msg.text = "Gerando o Pix…"; msg.setTextColor(T.texto2)
        botao.isEnabled = false
        Thread {
            try {
                val p = Compra.criar(email, escolhido, cupomOk, presente)
                runOnUiThread { botao.isEnabled = true; mostrarPix(p.numero, p.brCode, p.qr, p.teste); vigiar(p.id) }
            } catch (e: Exception) {
                runOnUiThread {
                    botao.isEnabled = true
                    if (cupomOk.isNotEmpty() && (e.message ?: "").contains("upom")) {
                        val c = cupomOk
                        msg.text = ""
                        AlertDialog.Builder(this).setTitle("O cupom $c não vale mais").setMessage((e.message ?: "") + "\n\nQuer pagar o preço normal agora?")
                            .setPositiveButton("Pagar sem cupom") { _, _ -> ec.setText(""); cupomOk = ""; precoCupom = -1; msgCupom.text = ""; botaoTxt(); botao.performClick() }
                            .setNegativeButton("Agora não", null).show()
                    } else { msg.text = e.message ?: "Sem conexão. Tente de novo."; msg.setTextColor(T.vermelho) }
                }
            }
        }.start()
    }

    // trocou de celular: passa o Pro pelo e-mail da compra (codigo de confirmacao no e-mail)
    if (!ativo) {
        col.addView(Ui.secao(this, "Trocou de celular?"))
        val ctr = Ui.vertical(this)
        ctr.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16))
        ctr.background = Ui.forma(this, T.surface, 18, T.outline)
        ctr.addView(Ui.texto(this, "Passe o seu Pro para este celular usando o e-mail da compra. Enviamos um código de confirmação para ele. Dá para trocar uma vez a cada 30 dias.", 12.5f, T.texto2))
        val ee = EditText(this)
        ee.hint = "E-mail da compra"
        ee.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        ee.setText(Store.str("lic_email")); ee.setTextColor(T.texto); ee.setHintTextColor(T.texto3)
        ctr.addView(ee)
        val eo = EditText(this)
        eo.hint = "Código de 6 números"
        eo.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        eo.setTextColor(T.texto); eo.setHintTextColor(T.texto3); eo.visibility = android.view.View.GONE
        ctr.addView(eo)
        val mtr = Ui.texto(this, "", 12.5f, T.texto2)
        lateinit var btr: LinearLayout
        var etapa = 0
        btr = Ui.botao(this, "Enviar código para o e-mail", T.verde, false) {
            val email = ee.text.toString().trim()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { mtr.text = "Digite o e-mail da compra."; mtr.setTextColor(T.vermelho); return@botao }
            btr.isEnabled = false; mtr.text = if (etapa == 0) "Enviando…" else "Conferindo…"; mtr.setTextColor(T.texto2)
            Thread {
                if (etapa == 0) {
                    val erro = Compra.trocaPedir(email)
                    runOnUiThread {
                        btr.isEnabled = true
                        if (erro == null) { etapa = 1; eo.visibility = android.view.View.VISIBLE; (btr.getChildAt(btr.childCount - 1) as android.widget.TextView).text = "Confirmar e ativar aqui"; mtr.text = "Enviamos um código para $email. Ele vale por 15 minutos."; mtr.setTextColor(T.azul) }
                        else { mtr.text = erro; mtr.setTextColor(T.amarelo) }
                    }
                } else {
                    val (ok, m) = Compra.trocaConfirmar(email, eo.text.toString().trim())
                    runOnUiThread { btr.isEnabled = !ok; mtr.text = m; mtr.setTextColor(if (ok) T.verde else T.amarelo); if (ok) { Store.putStr("lic_email", email); mtr.postDelayed({ mostrar() }, 1800) } }
                }
            }.start()
        }
        ctr.addView(btr, Ui.linhaLp(this, 10, 0))
        mtr.setPadding(0, Ui.dp(this, 8), 0, 0)
        ctr.addView(mtr)
        col.addView(ctr, Ui.linhaLp(this))
    }

    // retoma um Pix ja gerado (o app foi fechado antes de pagar)
    val pend = Store.str("lic_pedido")
    if (pend.isNotEmpty()) { mostrarPix(Store.str("lic_pedido_num"), Store.str("lic_pedido_br"), Store.str("lic_pedido_qr"), false); vigiar(pend) }
}

/** Usa qualquer codigo: chave, presente/cortesia ou cupom. */
private fun MainActivity.usarCodigo(texto: String, msg: android.widget.TextView) {
    val c = texto.trim().uppercase().replace(" ", "")
    when (Compra.tipoCodigo(c)) {
        "chave" -> {
            val (ok, m) = Licenca.ativar(texto)
            msg.text = m; msg.setTextColor(if (ok) T.verde else T.vermelho)
            if (ok) { Thread { Compra.registrar() }.start(); window.decorView.postDelayed({ mostrar() }, 1200) }
        }
        "presente" -> {
            msg.text = "Ativando o código de presente…"; msg.setTextColor(T.texto2)
            Thread { val (ok, m) = Compra.resgatar(c); runOnUiThread { msg.text = m; msg.setTextColor(if (ok) T.verde else T.vermelho); if (ok) window.decorView.postDelayed({ mostrar() }, 1500) } }.start()
        }
        "cupom" -> {
            Store.putStr("cupom_pendente", c)
            msg.text = "$c é um cupom de desconto: já coloquei no Pix lá em cima. Escolha o plano e toque em Gerar Pix."; msg.setTextColor(T.verde)
            window.decorView.postDelayed({ mostrar() }, 1600)
        }
        else -> { msg.text = "Não reconheci esse código. Confira se copiou inteiro."; msg.setTextColor(T.vermelho) }
    }
}

/** Ao abrir a tela do Pro: se a pessoa copiou um codigo (do WhatsApp, do e-mail...), oferece usar. */
private fun MainActivity.oferecerCopiado() {
    try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = (cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: "").trim()
        val achado = Regex("PCT-[A-Za-z0-9\\-]+\\.\\d+\\.[A-Za-z0-9_\\-]+|PRE[A-Z2-9]{9}|IND[A-Z2-9]{8}").find(t)?.value ?: return
        if (Store.str("clip_visto") == achado) return
        Store.putStr("clip_visto", achado)
        val tipo = when (Compra.tipoCodigo(achado)) { "chave" -> "a chave"; "presente" -> "o código de presente"; else -> "o cupom" }
        AlertDialog.Builder(this).setTitle("Usar o código copiado?").setMessage("Você copiou $tipo ${achado.take(24)}${if (achado.length > 24) "…" else ""}. Quer usar agora?")
            .setPositiveButton("Usar") { _, _ ->
                val tv = android.widget.TextView(this)
                usarCodigo(achado, tv)
                Toast.makeText(this, if (Compra.tipoCodigo(achado) == "cupom") "Cupom $achado colocado no Pix." else "Conferindo…", Toast.LENGTH_SHORT).show()
                if (Compra.tipoCodigo(achado) != "cupom") window.decorView.postDelayed({ Toast.makeText(this, tv.text, Toast.LENGTH_LONG).show(); mostrar() }, 2500)
            }.setNegativeButton("Não", null).show()
    } catch (e: Exception) { }
}

private fun MainActivity.secaoChave(col: LinearLayout) {
    // passo 3: ativar
    val c3 = Ui.vertical(this)
    c3.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16))
    c3.background = Ui.forma(this, T.surface, 18, T.outline)
    c3.addView(Ui.texto(this, if (Vendedor.automatico()) "Tem um código? Cole aqui" else "3. Cole a chave que você recebeu", 14.5f, T.texto, Ui.medio))
    if (Vendedor.automatico()) c3.addView(Ui.texto(this, "Serve para chave (PCT-…), código de presente/cortesia (PRE…) e cupom de desconto ou de indicação (IND…). O app descobre sozinho qual é.", 12f, T.texto3))
    val et = EditText(this)
    et.hint = "PCT-…  ·  PRE…  ·  IND…"
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
    linha.addView(Ui.botao(this, "Usar", T.verde, true) { usarCodigo(et.text.toString(), msg) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    c3.addView(linha, Ui.linhaLp(this, 10, 0))
    if (Vendedor.automatico() && !Licenca.ativo()) {
        c3.addView(Ui.botao(this, "Já comprei: recuperar meu Pro", T.azul, false) {
            msg.text = "Procurando sua compra..."; msg.setTextColor(T.texto2)
            Thread {
                val ok = Compra.restaurar(forcar = true)
                runOnUiThread {
                    msg.text = if (ok) "Pro recuperado! Ativo até ${Licenca.validadeTexto()}." else "Não achei compra ativa para este celular. Se comprou, cole a chave que chegou no seu e-mail."
                    msg.setTextColor(if (ok) T.verde else T.amarelo)
                    if (ok) msg.postDelayed({ mostrar() }, 1400)
                }
            }.start()
        }, Ui.linhaLp(this, 8, 0))
    }
    col.addView(c3, Ui.linhaLp(this))
}
