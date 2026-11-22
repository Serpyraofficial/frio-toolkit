package com.pctoolkit.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager

class Checagem(
    val grupo: String,
    val nome: String,
    val estado: String,      // OK, PROBLEMA, ATENCAO, INFO
    val detalhe: String,
    val peso: Int,
    val auto: Boolean,
    val fix: ((Console) -> Unit)?
)

/** RAIO-X DE FPS: nota do celular + correcao com 1 toque. */
object RaioX {
    private fun ok(g: String, n: String, peso: Int) = Checagem(g, n, "OK", "", peso, false, null)

    fun checagens(con: Console, ctx: Context): List<Checagem> {
        val L = ArrayList<Checagem>()
        val root = Sh.temRoot()

        con.info("  [1/8] root...")
        if (!root) L.add(Checagem("Sistema", "Sem acesso ROOT", "PROBLEMA", "Quase todas as correcoes precisam de root. Libere o app no Magisk/KernelSU/APatch.", 10, false, null))
        else L.add(ok("Sistema", "Root liberado", 10))

        con.info("  [2/8] tela...")
        val (hz, maxHz) = Sensores.tela(ctx)
        val peak = if (root) Sh.setting("system", "peak_refresh_rate").toFloatOrNull() else null
        if (maxHz > 61 && ((peak != null && peak + 1 < maxHz) || hz + 1 < maxHz)) {
            L.add(Checagem("Tela", String.format(java.util.Locale.US, "Tela limitada (agora %.0f Hz, aceita %.0f Hz)", hz, maxHz), "ATENCAO",
                "Com a tela liberada no maximo, jogos de 90/120 FPS conseguem mostrar todos os quadros e o toque responde mais rapido.", 5, root) { c ->
                Undo.global.setting("system", "peak_refresh_rate", String.format(java.util.Locale.US, "%.1f", maxHz)); c.ok("Tela liberada ate o Hz maximo")
            })
        } else L.add(ok("Tela", String.format(java.util.Locale.US, "Tela no Hz maximo (%.0f Hz)", maxHz), 5))

        con.info("  [3/8] energia e bateria...")
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isPowerSaveMode) L.add(Checagem("Energia", "ECONOMIA DE ENERGIA ligada", "PROBLEMA", "Ela corta o clock da CPU/GPU e limita o FPS (muitas vezes a 30-60).", 6, root) { c ->
            Undo.global.setting("global", "low_power", "0"); c.ok("Economia de energia desligada")
        }) else L.add(ok("Energia", "Economia de energia desligada", 6))
        val b = Sensores.bateria(ctx)
        if (b.nivel in 0..19 && !b.carregando) L.add(Checagem("Energia", "Bateria em ${b.nivel}%", "ATENCAO", "Abaixo de ~20% muitos celulares reduzem o desempenho automaticamente.", 2, false, null))
        if (b.tempC >= 40) L.add(Checagem("Energia", String.format(java.util.Locale.US, "Bateria quente (%.1f C) sem jogo", b.tempC), "ATENCAO", "Quente parado = vai limitar o FPS no jogo. Tire a capinha, pare de carregar, deixe esfriar.", 3, false, null))
        val st = Sensores.statusTermico(ctx)
        if (st >= 2) L.add(Checagem("Energia", "Android limitando desempenho por calor AGORA (" + Sensores.nomeStatusTermico(st) + ")", "PROBLEMA", "Veja o menu Temperatura.", 4, false, null))
        else if (st >= 0) L.add(ok("Energia", "Sem limitacao termica agora", 4))
        if (root) {
            val est = Store.str("perfil_ativo")
            if (est == "Frio" || est == "Silencioso" || est == "Custom") L.add(Checagem("Energia", "Perfil de CPU '$est' escolhido por voce", "INFO", "Troca um pouco de FPS por menos calor. Para FPS maximo use MAXIMO FPS.", 0, false, null))
            else {
                val pols = Cpu.politicas()
                val limitada = pols.any { it.max > 0 && it.curMax < it.max * 0.95 }
                if (limitada) L.add(Checagem("Energia", "Algum cluster da CPU esta com clock maximo limitado", "ATENCAO", "Libera o clock total (perfil MAXIMO FPS, reversivel).", 4, true) { c ->
                    Cpu.aplicarPerfil("Maximo", 100, Undo.global); c.ok("Perfil MAXIMO FPS aplicado")
                }) else L.add(ok("Energia", "CPU liberada para o clock maximo", 4))
            }
        }

        con.info("  [4/8] Android...")
        if (root) {
            val anim = Sh.setting("global", "animator_duration_scale").toFloatOrNull() ?: 1f
            if (anim > 0.6f) L.add(Checagem("Android", "Animacoes do sistema em ${anim}x", "ATENCAO", "Em 0.5x o celular fica visivelmente mais rapido para abrir apps e voltar ao jogo.", 1, true) { c ->
                for (k in listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) Undo.global.setting("global", k, "0.5"); c.ok("Animacoes em 0.5x")
            }) else L.add(ok("Android", "Animacoes rapidas", 1))
            if (Build.VERSION.SDK_INT >= 31 && Sh.setting("global", "disable_window_blurs") != "1") L.add(Checagem("Android", "Desfoque de janelas ligado", "ATENCAO", "O desfoque (painel de notificacoes, menus) pesa na GPU.", 1, true) { c ->
                Undo.global.setting("global", "disable_window_blurs", "1"); c.ok("Desfoque desligado")
            })
            if (Sh.root("getprop debug.egl.force_msaa", 10).out.trim() == "true") L.add(Checagem("Android", "'Forcar 4x MSAA' (opcoes do desenvolvedor) LIGADO", "PROBLEMA", "Obriga todo jogo OpenGL a usar antisserrilhado pesado: derruba muito o FPS.", 5, true) { c ->
                Undo.global.prop("debug.egl.force_msaa", "false"); c.ok("4x MSAA desligado")
            })
            val prof = Sh.root("getprop debug.hwui.profile", 10).out.trim()
            if (prof.isNotEmpty() && prof != "false") L.add(Checagem("Android", "'Perfil de renderizacao da GPU' LIGADO", "ATENCAO", "Opcao de desenvolvedor que mede cada quadro e custa desempenho.", 2, true) { c ->
                Undo.global.prop("debug.hwui.profile", "false"); c.ok("Perfil de renderizacao desligado")
            })
            if (Sh.setting("global", "wifi_scan_always_enabled") == "1") L.add(Checagem("Android", "Wi-Fi fazendo varredura em segundo plano", "ATENCAO", "A varredura periodica de redes causa picos de ping a cada ~1 minuto em muitos celulares.", 2, true) { c ->
                Undo.global.setting("global", "wifi_scan_always_enabled", "0"); Undo.global.setting("global", "ble_scan_always_enabled", "0"); c.ok("Varredura em segundo plano desligada")
            }) else L.add(ok("Android", "Sem varredura de Wi-Fi em segundo plano", 2))
        }

        con.info("  [5/8] jogos...")
        val jogos = Jogos.instalados(ctx)
        if (root && jogos.isNotEmpty()) {
            val wl = Sh.root("dumpsys deviceidle whitelist 2>/dev/null", 15).out
            val fora = jogos.filter { !wl.contains(",${it.pkg},") }
            if (fora.isNotEmpty()) L.add(Checagem("Jogos", "${fora.size} jogo(s) na otimizacao de bateria", "ATENCAO", "O Android pode congelar/matar o jogo quando voce sai rapidinho (ex.: responder mensagem) e limitar em segundo plano.", 3, true) { c ->
                for (j in jogos) Fps.otimizarPacote(j.pkg, Undo.global); c.ok("Jogos fora da otimizacao de bateria + Modo de Jogo DESEMPENHO")
            }) else L.add(ok("Jogos", "Jogos fora da otimizacao de bateria", 3))
            if (Build.VERSION.SDK_INT >= 31) {
                val semModo = jogos.filter { !Sh.root("cmd game mode get ${it.pkg} 2>/dev/null", 10).out.lowercase().contains("performance") }
                if (semModo.isNotEmpty()) L.add(Checagem("Jogos", "${semModo.size} jogo(s) sem o Modo de Jogo DESEMPENHO", "ATENCAO", "O Modo de Jogo do Android (12+) prioriza desempenho para o jogo em vez de bateria.", 2, true) { c ->
                    for (j in semModo) Undo.global.comando("game:${j.pkg}", "cmd game mode performance ${j.pkg}", "cmd game mode standard ${j.pkg}"); c.ok("Modo DESEMPENHO aplicado")
                }) else L.add(ok("Jogos", "Modo de Jogo DESEMPENHO nos jogos", 2))
            }
        }

        con.info("  [6/8] memoria e armazenamento...")
        val m = Sensores.memoria(ctx)
        val pctRam = if (m.totalMem > 0) m.availMem * 100 / m.totalMem else 100
        if (pctRam < 20) L.add(Checagem("Memoria", "So $pctRam% da RAM livre agora", "ATENCAO", "Pouca RAM = jogo recarregando texturas e engasgando.", 3, root) { c -> Fps.liberarRam(c, ctx) })
        else L.add(ok("Memoria", "RAM livre: $pctRam%", 3))
        val (livre, total) = Sensores.armazenamento()
        val pct = if (total > 0) livre * 100 / total else 100
        if (pct < 10) L.add(Checagem("Memoria", "Armazenamento com so $pct% livre", "PROBLEMA", "Celular com armazenamento cheio fica lento (o sistema de arquivos perde desempenho) e jogos travam ao salvar/carregar.", 3, false) { c ->
            Limpeza.limpar(c, ctx, false, false)
        }) else L.add(ok("Memoria", "Armazenamento: $pct% livre", 3))

        con.info("  [7/8] apps em segundo plano...")
        if (root) {
            val top = Sh.root("top -b -n 1 -m 6 2>/dev/null", 15).out.lines()
            val i0 = top.indexOfFirst { it.contains("PID") && it.contains("CPU") }
            if (i0 >= 0) {
                val cab = top[i0].trim().split(Regex("\\s+"))
                val ic = cab.indexOfFirst { it.contains("CPU") }
                val pesados = ArrayList<String>()
                for (l in top.drop(i0 + 1)) {
                    val p = l.trim().split(Regex("\\s+"))
                    if (p.size <= ic || ic < 0) continue
                    val cpu = p[ic].replace("%", "").replace("[", "").replace("]", "").toDoubleOrNull() ?: continue
                    val nome = p.last()
                    if (cpu >= 15 && !nome.startsWith("top") && nome != "su" && !nome.contains("pctoolkit")) pesados.add("$nome (${cpu.toInt()}%)")
                }
                if (pesados.isNotEmpty()) L.add(Checagem("Memoria", "Usando CPU agora sem voce jogar: " + pesados.joinToString(", "), "ATENCAO", "Se continuar com o celular parado, isso rouba FPS e bateria. Se nao reconhecer, rode Seguranca > Cacar mineradores.", 2, false, null))
            }
        }

        con.info("  [8/8] rede...")
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) L.add(Checagem("Rede", "Jogando no 4G/5G", "INFO", "Dados moveis variam muito o ping. Wi-Fi 5 GHz perto do roteador costuma ser mais estavel.", 0, false, null))
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val rssi = Rede.rssi(ctx)
            if (rssi != null && rssi < -70) L.add(Checagem("Rede", "Sinal de Wi-Fi fraco ($rssi dBm)", "ATENCAO", "Sinal fraco = perda de pacotes e lag. Chegue mais perto do roteador ou use 5 GHz.", 2, false, null))
            else if (rssi != null) L.add(ok("Rede", "Sinal de Wi-Fi bom ($rssi dBm)", 2))
        }
        return L
    }

    fun executar(con: Console, ctx: Context) {
        con.linha("=================== RAIO-X DE FPS ===================", C.verde)
        con.linha("Confere tudo que costuma derrubar o FPS no celular (root, tela, energia, calor, Android, jogos,")
        con.linha("memoria, apps de fundo, rede), da uma NOTA e mostra o que corrigir. Nada muda sem voce mandar.", C.cinza)
        con.linha("")
        val lista = checagens(con, ctx)
        var tot = 0; var bom = 0
        for (c in lista) if (c.estado != "INFO") { tot += c.peso; if (c.estado == "OK") bom += c.peso }
        val nota = if (tot > 0) Math.round(bom * 100.0 / tot).toInt() else 100
        val rank = mapOf("PROBLEMA" to 0, "ATENCAO" to 1, "INFO" to 2, "OK" to 3)
        val ord = lista.sortedWith(compareBy<Checagem> { rank[it.estado] ?: 9 }.thenByDescending { it.peso })
        val pend = ord.filter { it.estado != "OK" }
        con.linha("")
        for (c in ord.filter { it.estado == "OK" }) con.linha("   [ok]  ${c.nome}", 0xFF2EA043.toInt())
        con.linha("")
        for ((i, c) in pend.withIndex()) {
            val cor = when (c.estado) { "PROBLEMA" -> C.vermelho; "ATENCAO" -> C.amarelo; else -> C.ciano }
            val marca = if (c.fix != null) (if (c.auto) "  [correcao automatica]" else "  [correcao guiada]") else ""
            con.linha(String.format(java.util.Locale.US, "%3d) [%s] %s%s", i + 1, c.estado, c.nome, marca), cor)
            if (c.detalhe.isNotEmpty()) con.info("      " + c.detalhe)
        }
        val antes = Store.int("nota_fps", -1)
        val corN = if (nota < 60) C.vermelho else if (nota < 85) C.amarelo else C.verde
        con.linha("")
        con.linha("=====================================================", corN)
        con.linha("   NOTA DE FPS DO SEU CELULAR: $nota/100" + if (antes >= 0) "   (ultima vez: $antes)" else "", corN)
        con.linha("=====================================================", corN)
        Store.putInt("nota_fps", nota)
        Log2.registrar("Raio-X: nota $nota")
        val corr = pend.filter { it.fix != null }
        if (corr.isEmpty()) {
            if (pend.isNotEmpty()) con.info("Os itens acima dependem de hardware ou de voce: siga a explicacao de cada um.")
            return
        }
        val op = con.escolher("Corrigir", listOf("Aplicar TODAS as correcoes automaticas", "Escolher quais corrigir", "Nao mexer agora"))
        val alvos = when (op) {
            0 -> corr.filter { it.auto }
            1 -> con.varios("Quais corrigir?", corr.map { it.nome }).map { corr[it] }
            else -> emptyList()
        }
        if (alvos.isEmpty()) return
        for (c in alvos) {
            con.linha("")
            con.linha("--> " + c.nome, C.ciano)
            try { c.fix?.invoke(con) } catch (e: Exception) { con.aviso("Falhou: " + (e.message ?: "")) }
        }
        con.linha("")
        con.ok("Correcoes aplicadas. Rode o Raio-X de novo para ver a nota nova.")
        con.info("Dica: meca o FPS ANTES e DEPOIS (FPS > Medir FPS) para ver o ganho real no seu jogo.")
    }
}
