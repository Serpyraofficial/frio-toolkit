package com.pctoolkit.mobile

import android.content.Intent
import android.provider.Settings
import java.util.Locale

/** RAIO-X: confere tudo que derruba o FPS e da uma nota de 0 a 100. */
object RaioX {
    class Item(val nome: String, val ok: Boolean, val detalhe: String, val peso: Int, val corrigir: ((Console) -> Unit)? = null)

    fun checar(con: Console): List<Item> {
        val ctx = con.ctx
        val r = ArrayList<Item>()
        val eco = Sensores.economiaEnergia(ctx)
        r.add(Item("Economia de energia", !eco, if (eco) "Ligada: limita CPU e tela" else "Desligada", 15) { c -> Auto.desligarEconomia(c) })

        val (hz, max) = Sensores.tela(ctx)
        // telas adaptativas ficam em 60 Hz com a imagem parada: o que importa e o limite configurado
        val pico = Ajuste.ler("system", "peak_refresh_rate").toFloatOrNull()
        val telaOk = max <= 61 || hz + 1 >= max || (pico != null && pico + 1 >= max)
        r.add(Item("Taxa da tela", telaOk, String.format(Locale.US, "%.0f Hz de %.0f Hz", hz, max), 12) { c -> Fps.tela(c) })

        val b = Sensores.bateria(ctx)
        r.add(Item("Temperatura", b.tempC < 40, "Bateria a ${f1(b.tempC)} °C", 12) { c -> Temperatura.dicas(c) })
        val st = Sensores.statusTermico(ctx)
        if (st >= 0) r.add(Item("Limitação por calor", st < 2, Sensores.nomeStatusTermico(st), 10) { c -> Temperatura.dicas(c) })

        if (b.carregando) r.add(Item("Carregando", false, "Jogar na tomada esquenta e limita o desempenho", 4, null))
        r.add(Item("Bateria", b.nivel >= 20 || b.carregando, "${b.nivel}%" + if (b.nivel < 20) " (muitos celulares limitam com bateria baixa)" else "", 5, null))

        val m = Sensores.memoria(ctx)
        val ramOk = m.availMem > m.totalMem / 4 && !m.lowMemory
        r.add(Item("Memória livre", ramOk, "${fmtTam(m.availMem)} de ${fmtTam(m.totalMem)}", 10) { c -> Fps.liberarRam(c) })

        val (livre, total) = Sensores.armazenamento()
        val armOk = total == 0L || livre * 100 / total >= 10
        r.add(Item("Armazenamento", armOk, "${fmtTam(livre)} livres", 8) { c -> Limpeza.rapida(c, false) })

        val an = (Ajuste.ler("global", "animator_duration_scale").ifEmpty { "1" }).toFloatOrNull() ?: 1f
        r.add(Item("Animações do sistema", an <= 0.5f, "${an}x", 4) { c -> Fps.animacoes(c) })

        val msaa = Sh.getprop("debug.egl.force_msaa") == "true"
        r.add(Item("MSAA 4x forçado", !msaa, if (msaa) "Ligado nas Opções do desenvolvedor (pesa muito)" else "Desligado", 8) { c -> Auto.desligarMsaa(c) })

        val tipo = Rede.tipo(ctx)
        if (tipo == "Wi-Fi") {
            val rs = Rede.rssi(ctx); val f = Rede.frequencia(ctx)
            r.add(Item("Sinal do Wi-Fi", rs == null || rs >= -70, if (rs != null) "$rs dBm" else "não informado", 6) { c -> Rede.testeCompleto(c) })
            if (f != null) r.add(Item("Faixa do Wi-Fi", f > 4900, if (f > 4900) "5 GHz" else "2,4 GHz (mais interferência)", 3, null))
        }
        val ws = Ajuste.ler("global", "wifi_scan_always_enabled") != "1"
        r.add(Item("Varredura de Wi-Fi em segundo plano", ws, if (ws) "Desligada" else "Ligada (picos de ping)", 3) { c ->
            if (Ajuste.pode() && Ajuste.mudar("global", "wifi_scan_always_enabled", "0")) { Ajuste.mudar("global", "ble_scan_always_enabled", "0"); c.ok("Varredura desligada (confirmado)") }
            else { c.linha("Desligue em Localização > Serviços de localização > Busca por Wi-Fi e Bluetooth.", T.azul); c.abrirEEsperar(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        })
        if (Rede.economiaDados(ctx)) r.add(Item("Economia de dados", false, "Ligada (atrasa o jogo em segundo plano)", 2) { c ->
            c.linha("Desligue “Economia de dados” na tela que vai abrir.", T.azul)
            c.abrirEEsperar(Intent(if (android.os.Build.VERSION.SDK_INT >= 28) Settings.ACTION_DATA_USAGE_SETTINGS else Settings.ACTION_WIRELESS_SETTINGS))
        })
        return r
    }

    fun executar(con: Console) {
        con.titulo("Raio-X de desempenho")
        con.linha("Confere o que mais derruba o FPS no celular.", T.texto2)
        val itens = checar(con)
        val total = itens.sumOf { it.peso }
        val pontos = itens.filter { it.ok }.sumOf { it.peso }
        val nota = if (total > 0) pontos * 100 / total else 100
        con.subtitulo("Resultado")
        for (i in itens) if (i.ok) con.ok("${i.nome}: ${i.detalhe}") else con.linha("✕  ${i.nome}: ${i.detalhe}", T.vermelho)
        con.linha("")
        con.linha("NOTA: $nota/100", if (nota >= 85) T.verde else if (nota >= 60) T.amarelo else T.vermelho)
        Store.putInt("nota_fps", nota)
        AtalhoWidget.atualizar(con.ctx)
        Comparar.buscar(nota)?.let { con.info(Comparar.texto(it) + " (média ${it.media}).") }
        val ruins = itens.filter { !it.ok && it.corrigir != null }
        if (ruins.isEmpty()) { con.ok(if (nota >= 85) "Tudo certo para jogar." else "Os itens restantes dependem de você (ex.: tirar da tomada)."); return }
        val sel = con.varios("Corrigir agora", ruins.map { it.nome + " — " + it.detalhe }, true)
        for (k in sel) { if (con.cancelado) break; con.subtitulo(ruins[k].nome); ruins[k].corrigir?.invoke(con) }
        if (sel.isNotEmpty()) {
            val novos = checar(con)
            val t2 = novos.sumOf { it.peso }
            val n2 = if (t2 > 0) novos.filter { it.ok }.sumOf { it.peso } * 100 / t2 else 100
            Store.putInt("nota_fps", n2)
            AtalhoWidget.atualizar(con.ctx)
            con.linha("")
            con.linha("NOVA NOTA: $n2/100  (antes $nota)", if (n2 >= 85) T.verde else T.amarelo)
        }
        con.info("Dica: na tela inicial, toque em Compartilhar para mandar a sua nota aos amigos (com o seu código de 10% de desconto).")
    }
}
