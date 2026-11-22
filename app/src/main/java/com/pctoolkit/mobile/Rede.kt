package com.pctoolkit.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** REDE PARA JOGOS ONLINE. */
object Rede {
    class Ping(val media: Double, val min: Double, val max: Double, val jitter: Double, val perda: Int)

    fun rssi(ctx: Context): Int? {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val r = wm.connectionInfo?.rssi ?: return null
            if (r <= -127 || r >= 0) null else r
        } catch (e: Exception) { null }
    }

    private fun frequenciaWifi(ctx: Context): Int? = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val f = wm.connectionInfo?.frequency
        if (f == null || f <= 0) null else f
    } catch (e: Exception) { null }

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
        if (!r.out.contains("time=")) r = Sh.root("ping -c $n -i 0.3 -W 2 $host 2>/dev/null", (n + 10).toLong())
        val t = Regex("time[=<]([0-9.]+)").findAll(r.out).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        if (t.isEmpty()) return null
        var j = 0.0
        for (i in 1 until t.size) j += Math.abs(t[i] - t[i - 1])
        if (t.size > 1) j /= (t.size - 1)
        return Ping(t.average(), t.min(), t.max(), j, ((n - t.size) * 100 / n).coerceIn(0, 100))
    }

    fun testeCompleto(con: Console, ctx: Context) {
        con.titulo("TESTE DE CONEXAO PARA JOGOS")
        con.info("Mede ping, JITTER (variacao, o que causa 'teleporte'/lag) e PERDA de pacotes ate o roteador e ate a internet.")
        con.info("Assim da para saber se o problema e na sua casa (Wi-Fi) ou no provedor/operadora. Leva ~30 s.")
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        con.linha("Conexao: " + when { wifi -> "Wi-Fi"; caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "dados moveis (4G/5G)"; else -> "desconhecida" })
        if (wifi) {
            val r = rssi(ctx)
            val f = frequenciaWifi(ctx)
            if (r != null) con.linha("Sinal do Wi-Fi: $r dBm " + when { r >= -60 -> "(otimo)"; r >= -70 -> "(bom)"; else -> "(FRACO)" }, if (r < -70) C.vermelho else if (r < -60) C.amarelo else C.verde)
            if (f != null) con.linha("Faixa: " + if (f > 4900) "5 GHz (melhor para jogar)" else "2.4 GHz (mais interferencia; prefira 5 GHz se o roteador tiver)", if (f > 4900) C.verde else C.amarelo)
        }
        val gw = gateway(ctx)
        val alvos = ArrayList<Triple<String, String, Boolean>>()
        if (gw != null) alvos.add(Triple("Seu roteador", gw, true))
        alvos.add(Triple("Cloudflare (1.1.1.1)", "1.1.1.1", false))
        alvos.add(Triple("Google (8.8.8.8)", "8.8.8.8", false))
        val res = HashMap<String, Ping?>()
        for ((nome, h, local) in alvos) {
            con.info("medindo $nome...")
            val p = medirPing(h, 20)
            res[nome] = p
            if (p == null) { con.linha(String.format(java.util.Locale.US, "  %-22s sem resposta", nome), C.amarelo); continue }
            val cor = when {
                p.perda > 0 || p.jitter > 15 -> C.vermelho
                p.jitter > 5 || (local && p.media > 8) -> C.amarelo
                else -> C.verde
            }
            con.linha(String.format(java.util.Locale.US, "  %-22s ping %4.0f ms | min %.0f | max %.0f | jitter %.1f ms | perda %d%%", nome, p.media, p.min, p.max, p.jitter, p.perda), cor)
        }
        con.linha("")
        val loc = res["Seu roteador"]
        val net = res["Cloudflare (1.1.1.1)"]
        when {
            loc != null && (loc.jitter > 5 || loc.perda > 0 || loc.media > 8) -> {
                con.aviso("O problema esta DENTRO DE CASA (entre o celular e o roteador).")
                con.linha("  Chegue mais perto do roteador, use 5 GHz, evite paredes/micro-ondas e outros aparelhos baixando.", C.amarelo)
            }
            net != null && (net.jitter > 10 || net.perda > 0) -> {
                con.aviso("Sua rede local esta boa; a instabilidade vem do PROVEDOR/operadora ou de alguem usando muito a internet.")
                con.linha("  Pause downloads/streaming durante o jogo. Se continuar, reclame com o provedor mostrando estes numeros.", C.amarelo)
            }
            net != null -> con.ok("Conexao estavel para jogar.")
        }
        con.info("Referencia: ping ate 40 ms otimo no celular | jitter ate 5 ms otimo | perda tem que ser 0%.")
    }

    fun ajustesTcp(con: Console) {
        con.titulo("AJUSTES DE TCP (kernel)")
        if (!con.exigeRoot()) return
        con.linha("Equivalente ao 'desligar Nagle' do PC: ajustes do kernel que reduzem atraso em rajadas pequenas de dados.")
        con.info("Valem ate reiniciar o celular. Reversiveis no Desfazer.")
        val disp = Sh.ler("/proc/sys/net/ipv4/tcp_available_congestion_control")
        val itens = ArrayList<Triple<String, String, String>>()
        if (disp.split(Regex("\\s+")).contains("bbr")) itens.add(Triple("/proc/sys/net/ipv4/tcp_congestion_control", "bbr", "Controle de congestionamento BBR (menos fila/latencia em redes instaveis)"))
        itens.add(Triple("/proc/sys/net/ipv4/tcp_slow_start_after_idle", "0", "Sem 'partida lenta' depois de pausas (respostas mais rapidas)"))
        itens.add(Triple("/proc/sys/net/ipv4/tcp_fastopen", "3", "TCP Fast Open (conexoes novas mais rapidas)"))
        if (Sh.root("[ -f /proc/sys/net/ipv4/tcp_low_latency ]", 10).ok) itens.add(Triple("/proc/sys/net/ipv4/tcp_low_latency", "1", "Modo de baixa latencia do TCP"))
        for (t in itens) con.linha("  - " + t.third + "  (atual: " + Sh.ler(t.first).trim() + ")")
        if (!con.ask("Aplicar?")) return
        for ((p, v, d) in itens) { if (Undo.global.sysfs(p, v)) con.ok(d) else con.aviso("Kernel recusou: $d") }
    }

    fun placaRede(con: Console) {
        con.titulo("WI-FI PARA JOGOS (baixa latencia)")
        if (!con.exigeRoot()) return
        con.linha("Liga o modo de BAIXA LATENCIA e de ALTO DESEMPENHO do Wi-Fi e desliga a varredura em segundo plano")
        con.linha("(as varreduras periodicas sao a causa mais comum de pico de ping a cada ~1 minuto no celular).")
        con.info("Gasta um pouco mais de bateria. Reversivel no Desfazer. Os modos forcados valem ate reiniciar.")
        if (!con.ask("Aplicar?")) return
        val u = Undo.global
        val r1 = Sh.root("cmd wifi force-low-latency-mode enabled 2>&1", 15)
        if (r1.ok && !r1.out.contains("nknown")) { u.registrar("wifi:lowlat", "cmd wifi force-low-latency-mode disabled"); con.ok("Wi-Fi em modo de baixa latencia") } else con.aviso("Este Android nao oferece o modo de baixa latencia forcado")
        val r2 = Sh.root("cmd wifi force-hi-perf-mode enabled 2>&1", 15)
        if (r2.ok && !r2.out.contains("nknown")) { u.registrar("wifi:hiperf", "cmd wifi force-hi-perf-mode disabled"); con.ok("Wi-Fi em modo de alto desempenho") }
        u.setting("global", "wifi_scan_always_enabled", "0")
        u.setting("global", "ble_scan_always_enabled", "0")
        con.ok("Varredura de Wi-Fi/Bluetooth em segundo plano desligada")
        if (Sh.root("which iw", 10).ok) {
            if (Sh.root("iw dev wlan0 set power_save off", 10).ok) { u.registrar("wifi:ps", "iw dev wlan0 set power_save on"); con.ok("Economia de energia do Wi-Fi desligada") }
        }
    }

    /** Consulta DNS crua por UDP: mede o tempo real de resposta de um servidor especifico. */
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

    fun dns(con: Console, ctx: Context) {
        con.titulo("DNS MAIS RAPIDO")
        con.info("O DNS nao muda o ping DENTRO da partida, mas acelera login, matchmaking, lojas e downloads de atualizacao.")
        val atuais = dnsAtuais(ctx)
        class Cand(val nome: String, val ip: String, val host: String?, val atual: Boolean)
        val cands = ArrayList<Cand>()
        if (atuais.isNotEmpty()) cands.add(Cand("Atual (${atuais[0]})", atuais[0], null, true))
        cands.add(Cand("Cloudflare", "1.1.1.1", "one.one.one.one", false))
        cands.add(Cand("Google", "8.8.8.8", "dns.google", false))
        cands.add(Cand("Quad9", "9.9.9.9", "dns.quad9.net", false))
        cands.add(Cand("AdGuard (bloqueia anuncios)", "94.140.14.14", "dns.adguard-dns.com", false))
        val dom = listOf("garena.com", "pubgmobile.com", "riotgames.com", "roblox.com", "supercell.com", "epicgames.com", "playstation.com", "discord.com")
        val res = ArrayList<Pair<Cand, Double?>>()
        for (c in cands) {
            con.info("testando ${c.nome}...")
            medirDns(c.ip, dom)
            res.add(Pair(c, medirDns(c.ip, dom)))
        }
        con.linha("")
        val val0 = res.filter { it.second != null }.sortedBy { it.second }
        for ((c, ms) in res) {
            if (ms == null) { con.linha(String.format(java.util.Locale.US, "  %-28s sem resposta", c.nome), C.amarelo); continue }
            val barra = "#".repeat(Math.min(40, Math.max(1, (ms / 3).toInt())))
            con.linha(String.format(java.util.Locale.US, "  %-28s %6.1f ms  %s", c.nome, ms, barra), if (val0.isNotEmpty() && val0[0].first == c) C.verde else C.cinza)
        }
        if (val0.isEmpty()) return
        val melhor = val0[0]
        if (melhor.first.atual) { con.linha(""); con.ok("O seu DNS atual ja e o mais rapido daqui."); return }
        val atualMs = res.firstOrNull { it.first.atual }?.second
        if (atualMs != null && atualMs - (melhor.second ?: 0.0) < 3) { con.linha(""); con.ok("Diferenca menor que 3 ms: nao vale trocar."); return }
        if (Build.VERSION.SDK_INT < 28) { con.info("Trocar DNS pelo sistema exige Android 9+. Configure no roteador."); return }
        if (con.ask("Usar ${melhor.first.nome} como DNS Privado do Android (criptografado, vale no Wi-Fi e nos dados moveis)?")) {
            if (!con.exigeRoot()) return
            Undo.global.setting("global", "private_dns_mode", "hostname")
            Undo.global.setting("global", "private_dns_specifier", melhor.first.host ?: "")
            con.ok("DNS Privado: ${melhor.first.host}")
        }
    }

    fun restaurarDns(con: Console) {
        if (!con.exigeRoot()) return
        val n = Undo.global.desfazerPrefixo("set:global:private_dns") { }
        if (n > 0) con.ok("DNS original restaurado") else con.linha("O toolkit nao trocou o DNS.")
    }
}
