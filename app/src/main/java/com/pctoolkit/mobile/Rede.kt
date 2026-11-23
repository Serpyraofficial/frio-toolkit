package com.pctoolkit.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale

/** REDE PARA JOGOS ONLINE (tudo sem root). */
object Rede {
    class Ping(val media: Double, val min: Double, val max: Double, val jitter: Double, val perda: Int)

    @Suppress("DEPRECATION")
    private fun info(ctx: Context) = try { (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo } catch (e: Exception) { null }

    fun rssi(ctx: Context): Int? { val r = info(ctx)?.rssi ?: return null; return if (r <= -127 || r >= 0) null else r }
    fun frequencia(ctx: Context): Int? { val f = info(ctx)?.frequency ?: return null; return if (f <= 0) null else f }
    fun velocidadeLink(ctx: Context): Int? { val v = info(ctx)?.linkSpeed ?: return null; return if (v <= 0) null else v }

    fun tipo(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "sem conexão"
        return when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Dados móveis"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Cabo"
            else -> "Outra"
        }
    }

    fun economiaDados(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }

    fun gateway(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        for (r in lp.routes) {
            if (r.isDefaultRoute && r.gateway != null) {
                val g = r.gateway?.hostAddress ?: continue
                if (!g.contains(':') && g != "0.0.0.0") return g
            }
        }
        return null
    }

    fun dnsAtuais(ctx: Context): List<String> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
        return lp.dnsServers.mapNotNull { it.hostAddress }.filter { !it.contains(':') }
    }

    fun medirPing(host: String, n: Int): Ping? {
        var r = Sh.sh("ping -c $n -i 0.3 -W 2 $host 2>/dev/null", (n + 10).toLong())
        if (!r.out.contains("time=")) r = Sh.sh("ping -c $n -W 2 $host 2>/dev/null", (n + 10).toLong())
        val t = Regex("time[=<]([0-9.]+)").findAll(r.out).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        if (t.isEmpty()) return medirTcp(host, n)
        var j = 0.0
        for (i in 1 until t.size) j += Math.abs(t[i] - t[i - 1])
        if (t.size > 1) j /= (t.size - 1)
        return Ping(t.average(), t.min(), t.max(), j, ((n - t.size) * 100 / n).coerceIn(0, 100))
    }

    /** Alternativa ao ping quando o ICMP esta bloqueado: tempo de abrir uma conexao TCP na porta 443/53. */
    private fun medirTcp(host: String, n: Int): Ping? {
        val t = ArrayList<Double>()
        repeat(n.coerceAtMost(10)) {
            try {
                val s = java.net.Socket()
                val t0 = System.nanoTime()
                s.connect(java.net.InetSocketAddress(host, if (host.startsWith("192.") || host.startsWith("10.")) 53 else 443), 2000)
                t.add((System.nanoTime() - t0) / 1e6)
                s.close()
            } catch (e: Exception) { }
        }
        if (t.isEmpty()) return null
        var j = 0.0
        for (i in 1 until t.size) j += Math.abs(t[i] - t[i - 1])
        if (t.size > 1) j /= (t.size - 1)
        val tot = n.coerceAtMost(10)
        return Ping(t.average(), t.min(), t.max(), j, ((tot - t.size) * 100 / tot).coerceIn(0, 100))
    }

    fun infoWifi(con: Console) {
        val ctx = con.ctx
        con.linha("Conexão: " + tipo(ctx))
        if (tipo(ctx) == "Wi-Fi") {
            val r = rssi(ctx)
            val f = frequencia(ctx)
            val v = velocidadeLink(ctx)
            if (r != null) con.linha("Sinal: $r dBm " + when { r >= -60 -> "(ótimo)"; r >= -70 -> "(bom)"; else -> "(FRACO)" }, if (r < -70) T.vermelho else if (r < -60) T.amarelo else T.verde)
            if (f != null) con.linha("Faixa: " + when { f > 5900 -> "6 GHz (excelente)"; f > 4900 -> "5 GHz (melhor para jogar)"; else -> "2,4 GHz (mais interferência; prefira 5 GHz)" }, if (f > 4900) T.verde else T.amarelo)
            if (v != null) con.linha("Velocidade do link: $v Mbps", T.texto2)
        }
        if (economiaDados(ctx)) con.aviso("Economia de dados LIGADA: pode atrasar downloads e sincronizações em segundo plano.")
    }

    fun testeCompleto(con: Console) {
        val ctx = con.ctx
        con.titulo("Teste de conexão para jogos")
        con.linha("Mede ping, jitter (variação que causa “teleporte”) e perda de pacotes até o roteador e até a internet.", T.texto2)
        con.info("Mostra se o problema está na sua casa (Wi-Fi) ou no provedor. Leva cerca de 30 s.")
        infoWifi(con)
        val gw = if (tipo(ctx) == "Wi-Fi") gateway(ctx) else null
        val alvos = ArrayList<Triple<String, String, Boolean>>()
        if (gw != null) alvos.add(Triple("Seu roteador", gw, true))
        alvos.add(Triple("Cloudflare", "1.1.1.1", false))
        alvos.add(Triple("Google", "8.8.8.8", false))
        val res = HashMap<String, Ping?>()
        con.subtitulo("Resultados")
        for ((nome, h, local) in alvos) {
            if (con.cancelado) return
            con.info("medindo $nome...")
            val p = medirPing(h, 20)
            res[nome] = p
            if (p == null) { con.linha("$nome: sem resposta", T.amarelo); continue }
            val cor = when { p.perda > 0 || p.jitter > 15 -> T.vermelho; p.jitter > 5 || (local && p.media > 8) -> T.amarelo; else -> T.verde }
            con.linha(String.format(Locale.US, "%s: ping %.0f ms · jitter %.1f ms · perda %d%%", nome, p.media, p.jitter, p.perda), cor)
            con.info(String.format(Locale.US, "mín %.0f ms · máx %.0f ms", p.min, p.max))
        }
        con.linha("")
        val loc = res["Seu roteador"]
        val net = res["Cloudflare"]
        when {
            loc != null && (loc.jitter > 5 || loc.perda > 0 || loc.media > 8) -> {
                con.aviso("O problema está DENTRO DE CASA (entre o celular e o roteador).")
                con.info("Chegue mais perto do roteador, use 5 GHz, evite paredes e outros aparelhos baixando.")
            }
            net != null && (net.jitter > 10 || net.perda > 0) -> {
                con.aviso("Sua rede local está boa; a instabilidade vem do provedor/operadora ou de alguém usando muito a internet.")
                con.info("Pause downloads e streaming durante o jogo. Se continuar, mostre estes números ao provedor.")
            }
            net != null -> con.ok("Conexão estável para jogar.")
            else -> con.erro("Sem resposta da internet.")
        }
        con.info("Referência: ping até 40 ms ótimo · jitter até 5 ms ótimo · perda tem que ser 0%.")
        net?.let { Store.putInt("ultimo_ping", it.media.toInt()) }
    }

    fun velocidade(con: Console) {
        con.titulo("Teste de velocidade")
        con.linha("Baixa e envia dados de teste pela Cloudflare. Usa cerca de 50 MB de internet.", T.texto2)
        if (tipo(con.ctx) == "Dados móveis" && !con.ask("Você está nos dados móveis. Continuar mesmo assim?", importante = true)) return
        val p = medirTcp("speed.cloudflare.com", 5)
        if (p != null) con.linha(String.format(Locale.US, "Latência: %.0f ms", p.media))
        con.info("Medindo download...")
        var baixado = 0L
        val t0 = System.nanoTime()
        try {
            for (i in 0 until 4) {
                if (con.cancelado) return
                val c = URL("https://speed.cloudflare.com/__down?bytes=10000000").openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 20000
                c.inputStream.use { s -> val b = ByteArray(65536); while (true) { val n = s.read(b); if (n <= 0) break; baixado += n } }
                c.disconnect()
                if ((System.nanoTime() - t0) / 1e9 > 12) break
            }
        } catch (e: Exception) { con.aviso("Download interrompido: " + (e.message ?: "")) }
        val sD = (System.nanoTime() - t0) / 1e9
        val down = if (sD > 0) baixado * 8 / sD / 1e6 else 0.0
        con.linha(String.format(Locale.US, "Download: %.1f Mbps", down), if (down >= 25) T.verde else if (down >= 8) T.amarelo else T.vermelho)
        con.info("Medindo upload...")
        var enviado = 0L
        val t1 = System.nanoTime()
        try {
            val dados = ByteArray(2_000_000)
            for (i in 0 until 4) {
                if (con.cancelado) return
                val c = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 10000; c.readTimeout = 20000
                c.setFixedLengthStreamingMode(dados.size)
                c.outputStream.use { it.write(dados) }
                c.responseCode
                c.disconnect()
                enviado += dados.size
                if ((System.nanoTime() - t1) / 1e9 > 10) break
            }
        } catch (e: Exception) { con.aviso("Upload interrompido: " + (e.message ?: "")) }
        val sU = (System.nanoTime() - t1) / 1e9
        val up = if (sU > 0) enviado * 8 / sU / 1e6 else 0.0
        con.linha(String.format(Locale.US, "Upload: %.1f Mbps", up), if (up >= 10) T.verde else if (up >= 3) T.amarelo else T.vermelho)
        con.linha("")
        con.info("Para jogar online, estabilidade (jitter/perda) importa mais que velocidade: 5 Mbps já bastam para a partida.")
    }

    // ---------------- DNS ----------------
    private fun consultaDns(servidor: String, dominio: String): Double? {
        return try {
            val id = (Math.random() * 65535).toInt()
            val out = ByteArrayOutputStream()
            out.write(id shr 8); out.write(id and 0xff); out.write(0x01); out.write(0x00)
            out.write(0); out.write(1)
            repeat(6) { out.write(0) }
            for (p in dominio.split('.')) { out.write(p.length); out.write(p.toByteArray()) }
            out.write(0); out.write(0); out.write(1); out.write(0); out.write(1)
            val q = out.toByteArray()
            val s = DatagramSocket()
            try {
                s.soTimeout = 2000
                val t0 = System.nanoTime()
                s.send(DatagramPacket(q, q.size, InetAddress.getByName(servidor), 53))
                val buf = ByteArray(1024)
                val r = DatagramPacket(buf, buf.size)
                s.receive(r)
                val ms = (System.nanoTime() - t0) / 1e6
                if (r.length >= 2 && (buf[0].toInt() and 0xff) == (id shr 8) && (buf[1].toInt() and 0xff) == (id and 0xff)) ms else null
            } finally { s.close() }
        } catch (e: Exception) { null }
    }

    private fun medirDns(servidor: String, dominios: List<String>): Double? {
        val t = dominios.mapNotNull { consultaDns(servidor, it) }
        if (t.size < (dominios.size + 1) / 2) return null
        return t.average()
    }

    fun dns(con: Console) {
        val ctx = con.ctx
        con.titulo("DNS mais rápido")
        con.linha("O DNS não muda o ping dentro da partida, mas acelera login, busca de partida, lojas e downloads.", T.texto2)
        val atuais = dnsAtuais(ctx)
        class Cand(val nome: String, val ip: String, val host: String?, val atual: Boolean)
        val cands = ArrayList<Cand>()
        if (atuais.isNotEmpty()) cands.add(Cand("Atual (${atuais[0]})", atuais[0], null, true))
        cands.add(Cand("Cloudflare", "1.1.1.1", "one.one.one.one", false))
        cands.add(Cand("Google", "8.8.8.8", "dns.google", false))
        cands.add(Cand("Quad9", "9.9.9.9", "dns.quad9.net", false))
        cands.add(Cand("AdGuard (bloqueia anúncios)", "94.140.14.14", "dns.adguard-dns.com", false))
        val dom = listOf("garena.com", "pubgmobile.com", "riotgames.com", "roblox.com", "supercell.com", "epicgames.com", "playstation.com", "discord.com")
        val res = ArrayList<Pair<Cand, Double?>>()
        for (c in cands) {
            if (con.cancelado) return
            con.info("testando ${c.nome}...")
            medirDns(c.ip, dom)
            res.add(Pair(c, medirDns(c.ip, dom)))
        }
        con.subtitulo("Tempo de resposta")
        val val0 = res.filter { it.second != null }.sortedBy { it.second }
        for ((c, ms) in res) {
            if (ms == null) { con.linha("${c.nome}: sem resposta", T.amarelo); continue }
            con.linha(String.format(Locale.US, "%s: %.1f ms", c.nome, ms), if (val0.isNotEmpty() && val0[0].first == c) T.verde else T.texto2)
        }
        if (val0.isEmpty()) return
        val melhor = val0[0]
        con.linha("")
        if (melhor.first.atual) { con.ok("O seu DNS atual já é o mais rápido daqui."); return }
        val atualMs = res.firstOrNull { it.first.atual }?.second
        if (atualMs != null && atualMs - (melhor.second ?: 0.0) < 3) { con.ok("Diferença menor que 3 ms: não vale trocar."); return }
        val host = melhor.first.host ?: return
        if (Build.VERSION.SDK_INT < 28) { con.info("Trocar o DNS pelo sistema exige Android 9+. Configure no roteador: ${melhor.first.ip}"); return }
        if (!con.ask("Usar ${melhor.first.nome} como DNS Privado (criptografado, vale no Wi-Fi e nos dados móveis)?")) return
        if (Ajuste.pode() && Ajuste.mudar("global", "private_dns_specifier", host) && Ajuste.mudar("global", "private_dns_mode", "hostname")) {
            con.info("Testando se a internet continua funcionando com o DNS novo...")
            con.sleep(4000)
            val ok = listOf("www.wikipedia.org", "www.cloudflare.com", "www.google.com.br").any { try { java.net.InetAddress.getByName(it); true } catch (e: Exception) { con.sleep(1500); false } }
            if (ok) con.ok("DNS Privado ativo e testado: $host (dá para voltar em Desfazer)")
            else {
                Ajuste.gravar("global", "private_dns_mode", "opportunistic")
                con.erro("Esta rede bloqueia o DNS Privado (porta 853). Voltei ao automático para você não ficar sem internet.")
            }
        } else {
            if (Ajuste.pode()) con.aviso("O Android não aceitou mudar o DNS por comando. Faça pela tela:")
            con.ctx.let { (it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("dns", host)) }
            con.linha("Copiei \"$host\". Vou abrir as configurações de rede:", T.azul)
            con.item("Toque em “DNS privado” (às vezes em Mais conexões / Rede e internet).")
            con.item("Escolha “Nome do host do provedor” e cole: $host")
            con.abrirEEsperar(android.content.Intent(Settings.ACTION_WIRELESS_SETTINGS))
            val modo = Ajuste.ler("global", "private_dns_mode")
            if (modo == "hostname") con.ok("DNS Privado ativo") else con.info("Se não encontrou, busque “DNS privado” na barra de pesquisa das Configurações.")
        }
    }
}
