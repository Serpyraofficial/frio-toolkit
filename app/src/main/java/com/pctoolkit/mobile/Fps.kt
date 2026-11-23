package com.pctoolkit.mobile

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** DESEMPENHO E FPS (sem root; root so libera extras). */
object Fps {

    // ---------------- memoria ----------------
    /** Encerra o que esta em segundo plano. Devolve true se liberou memoria de verdade. */
    fun liberarRam(con: Console): Boolean {
        con.titulo("Liberar memória")
        val ctx = con.ctx
        val antes = Sensores.memoria(ctx).availMem
        con.linha("Livre agora: ${fmtTam(antes)} de ${fmtTam(Sensores.memoria(ctx).totalMem)}")
        // Android 14+: um app comum so consegue encerrar os proprios processos. Com Shizuku/root funciona em todos.
        if (!Sh.privOk() && android.os.Build.VERSION.SDK_INT >= 34) {
            con.aviso("No Android 14 ou mais novo, só dá para encerrar os outros apps com o modo automático (Shizuku).")
            if (!con.ask("Ativar o modo automático agora?") || !Priv.ativar(con)) { con.info("Sem ele, feche os apps pela tela de recentes (botão quadrado) antes de jogar."); return false }
        }
        val n = encerrarSegundoPlano(ctx, null)
        con.sleep(1500)
        val depois = Sensores.memoria(ctx).availMem
        val ganho = depois - antes
        if (ganho > 20L shl 20) {
            con.ok("$n apps encerrados em segundo plano")
            con.linha("Livre depois: ${fmtTam(depois)}  (+${fmtTam(ganho)})", T.verde)
        } else {
            con.ok("Encerrados $n apps em segundo plano. A memória livre quase não mudou (${fmtTam(depois)}): o Android já estava com a memória arrumada.")
        }
        con.info("O Android reabre alguns apps sozinho (mensageiros, redes sociais). Para eles não voltarem durante o jogo, use o Modo Jogo.")
        return ganho > 20L shl 20
    }

    /** Encerra os apps em segundo plano (menos [exceto]). Devolve quantos. */
    fun encerrarSegundoPlano(ctx: Context, exceto: String?): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val apps = ctx.packageManager.getInstalledApplications(0).filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != ctx.packageName && it.packageName != exceto }
        if (Sh.privOk()) {
            // processos que estao rodando agora (so os de apps instalados pelo usuario)
            val vivos = Sh.root("ps -A -o NAME 2>/dev/null", 10).out.lines().map { it.trim().substringBefore(':') }.toSet()
            val alvo = apps.map { it.packageName }.filter { it in vivos }
            if (alvo.isNotEmpty()) Sh.root(alvo.joinToString("; ") { "am kill $it" } + "; am kill-all", 30)
            return alvo.size
        }
        var n = 0
        for (a in apps) { try { am.killBackgroundProcesses(a.packageName); n++ } catch (e: Exception) { } }
        return n
    }

    // ---------------- teste de desempenho ----------------
    private fun trabalho(iter: Int): Long {
        var x = -7046029254386353131L
        var acc = 0.0
        for (i in 0 until iter) {
            x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
            acc += Math.sqrt((x and 0xFFFF).toDouble() + i)
        }
        return x + acc.toLong()
    }

    /** Pontos por segundo em [threads] nucleos durante [ms]. */
    private fun medir(threads: Int, ms: Long, con: Console?): Double {
        val total = AtomicLong(0)
        val fim = System.currentTimeMillis() + ms
        val ts = (0 until threads).map {
            Thread {
                var lixo = 0L
                while (System.currentTimeMillis() < fim && con?.cancelado != true) { lixo += trabalho(20000); total.incrementAndGet() }
                if (lixo == 42L) total.incrementAndGet()
            }
        }
        ts.forEach { it.start() }; ts.forEach { it.join() }
        return total.get() * 1000.0 / ms
    }

    fun benchmark(con: Console) {
        val ctx = con.ctx
        con.titulo("Teste de desempenho")
        con.linha("Mede a força do processador e se ele aguenta jogar por muito tempo sem perder velocidade (estrangulamento por calor).", T.texto2)
        val tipo = con.escolher("Qual teste?", listOf("Rápido (20 s)", "Completo com teste de calor (2 min)", "Ver resultados anteriores"))
        if (tipo < 0) return
        if (tipo == 2) { historico(con); return }
        con.info("Deixe o celular parado e com a tela ligada durante o teste.")
        val nucleos = Runtime.getRuntime().availableProcessors()
        con.linha("Processador: ${Sensores.soc()} · $nucleos núcleos")
        val b0 = Sensores.bateria(ctx).tempC
        medir(1, 1500, con)
        val single = medir(1, 5000, con)
        con.linha(String.format(Locale.US, "Um núcleo: %.0f pontos", single), T.azul)
        val multi = medir(nucleos, 6000, con)
        con.linha(String.format(Locale.US, "Todos os núcleos: %.0f pontos", multi), T.azul)
        var estab = -1.0
        if (tipo == 1) {
            con.subtitulo("Teste de calor (sustentado)")
            val amostras = ArrayList<Double>()
            for (i in 0 until 12) {
                if (con.cancelado) break
                val s = medir(nucleos, 9000, con)
                amostras.add(s)
                val t = Sensores.bateria(ctx).tempC
                con.linha(String.format(Locale.US, "%3d s  %6.0f pontos  ·  bateria %.1f °C  ·  %s", (i + 1) * 9, s, t, Limpeza.barra(s / (amostras.maxOrNull() ?: s))), T.texto2)
            }
            if (amostras.size >= 3) {
                estab = amostras.takeLast(3).average() / (amostras.maxOrNull() ?: 1.0) * 100
                con.linha("")
                con.linha(String.format(Locale.US, "Estabilidade: %.0f%%", estab), if (estab >= 85) T.verde else if (estab >= 70) T.amarelo else T.vermelho)
                when {
                    estab >= 85 -> con.ok("Ótimo: o celular mantém o desempenho mesmo esquentando.")
                    estab >= 70 -> con.aviso("Perde um pouco de força com o calor. Tire a capinha e evite jogar carregando.")
                    else -> con.aviso("Perde MUITA força com o calor: é isso que derruba o FPS depois de alguns minutos. Veja Temperatura > Dicas.")
                }
            }
        }
        val b1 = Sensores.bateria(ctx).tempC
        con.info("Bateria: ${f1(b0)} → ${f1(b1)} °C")
        val ant = Store.lerArr("bench.json")
        if (ant.length() > 0) {
            val u = ant.getJSONObject(ant.length() - 1)
            val d = (multi - u.optDouble("multi")) / u.optDouble("multi").coerceAtLeast(1.0) * 100
            con.info(String.format(Locale.US, "Comparado ao teste de %s: %+.0f%% (todos os núcleos)", u.optString("data"), d))
        }
        Store.adicionar("bench.json", JSONObject().put("data", agora()).put("single", single).put("multi", multi).put("estab", estab).put("temp", b1), 30)
    }

    private fun historico(con: Console) {
        val a = Store.lerArr("bench.json")
        if (a.length() == 0) { con.linha("Nenhum teste feito ainda."); return }
        con.subtitulo("Resultados anteriores")
        for (i in a.length() - 1 downTo 0) {
            val o = a.getJSONObject(i)
            val e = o.optDouble("estab", -1.0)
            con.item(String.format(Locale.US, "%s · 1 núcleo %.0f · todos %.0f%s", o.optString("data"), o.optDouble("single"), o.optDouble("multi"), if (e >= 0) String.format(Locale.US, " · estab. %.0f%%", e) else ""))
        }
    }

    // ---------------- tela ----------------
    fun tela(con: Console) {
        val ctx = con.ctx
        con.titulo("Taxa de atualização da tela")
        val (atual, max) = Sensores.tela(ctx)
        con.linha(String.format(Locale.US, "Agora: %.0f Hz · máximo da tela: %.0f Hz", atual, max), if (atual + 1 < max) T.amarelo else T.verde)
        if (max <= 61) { con.info("A tela deste celular é de 60 Hz: o jogo vai até 60 FPS."); return }
        con.info("Muitos celulares baixam para 60 Hz para economizar bateria, e os jogos ficam presos em 60 FPS.")
        if (atual + 1 >= max && !con.ask("A tela já está no máximo agora. Quer fixar no máximo mesmo assim (evita cair durante o jogo)?")) return
        if (Ajuste.pode() && Ajuste.mudar("system", "peak_refresh_rate", f0(max.toDouble()))) {
            var minOk = true
            if (con.ask("Também travar o MÍNIMO em ${f0(max.toDouble())} Hz? (mais fluido, gasta mais bateria)", importante = true)) minOk = Ajuste.mudar("system", "min_refresh_rate", f0(max.toDouble()))
            if (android.os.Build.MANUFACTURER.equals("samsung", true)) Ajuste.mudar("secure", "refresh_rate_mode", "1")
            con.sleep(1000)
            val (a2, _) = Sensores.tela(ctx)
            con.ok(String.format(Locale.US, "Tela liberada até %.0f Hz (desfaça em Desfazer). Agora: %.0f Hz", max, a2))
            if (!minOk) con.aviso("O Android não aceitou travar o mínimo: a tela ainda pode baixar sozinha em telas paradas.")
            if (a2 + 1 < max) con.info("Algumas telas só sobem os Hz quando há movimento (rolar a tela ou dentro do jogo). Isso é normal.")
        } else {
            if (Ajuste.pode()) con.aviso("Este celular não deixou mudar a taxa da tela por comando. Vou abrir a tela certa para você escolher.")
            con.linha("Vou abrir Tela: escolha a opção de taxa mais alta (Alta / Suave / 120 Hz / Adaptável).", T.azul)
            con.abrirEEsperar(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            val (a2, _) = Sensores.tela(ctx)
            con.linha(String.format(Locale.US, "Agora: %.0f Hz", a2))
            con.info("Dica: com o Modo avançado (ADB) o app faz isso sozinho, inclusive travando o mínimo.")
        }
    }

    fun animacoes(con: Console) {
        con.titulo("Animações do sistema")
        val ks = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        con.linha("Atual: " + ks.joinToString(" · ") { Ajuste.ler("global", it).ifEmpty { "1" } + "x" })
        val op = con.escolher("Velocidade das animações", listOf("0.5x (recomendado: tudo mais rápido)", "Desligadas (máxima rapidez)", "1x (padrão)"))
        if (op < 0) return
        val v = when (op) { 0 -> "0.5"; 1 -> "0"; else -> "1" }
        if (Ajuste.pode() && ks.all { Ajuste.mudar("global", it, v) }) con.ok("Animações em ${v}x (confirmado)")
        else {
            if (Ajuste.pode()) con.aviso("O Android não aceitou a mudança automática.")
            con.linha("Vou abrir as Opções do desenvolvedor. Procure “Escala de animação” (três itens) e escolha ${v}x.", T.azul)
            con.info("Se não abrir: Configurações > Sobre o telefone > toque 7 vezes em “Número da versão”.")
            con.abrirEEsperar(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
    }

    // ---------------- otimizar ----------------
    fun otimizar(con: Console) {
        val ctx = con.ctx
        con.titulo("Otimizar o celular para jogos")
        con.linha("Passo a passo: corrige o que dá automaticamente e abre a tela certa para o resto.", T.texto2)
        con.info("Nível de acesso: ${Acesso.nivel()}" + if (!Ajuste.pode()) " (com o Modo avançado mais itens ficam automáticos)" else "")
        var feitos = 0

        con.subtitulo("1. Economia de energia")
        if (Sensores.economiaEnergia(ctx)) {
            con.aviso("Economia de energia LIGADA: limita a CPU e a tela a 60 Hz.")
            if (con.ask("Desligar?")) { if (Auto.desligarEconomia(con)) feitos++ }
        } else con.ok("Desligada")

        con.subtitulo("2. Tela")
        val (a, m) = Sensores.tela(ctx)
        if (m > 61 && a + 1 < m) { con.aviso(String.format(Locale.US, "Tela em %.0f Hz de %.0f Hz possíveis", a, m)); if (con.ask("Corrigir?")) { tela(con); if (Ajuste.ler("system", "peak_refresh_rate").toFloatOrNull()?.let { it + 1 >= m } == true || Sensores.tela(ctx).first + 1 >= m) feitos++ } } else con.ok(String.format(Locale.US, "%.0f Hz", a))

        con.subtitulo("3. Animações")
        val an = Ajuste.ler("global", "animator_duration_scale").ifEmpty { "1" }
        if ((an.toFloatOrNull() ?: 1f) > 0.5f) { con.info("Animações em ${an}x"); if (con.ask("Deixar o sistema mais rápido (0.5x)?")) { if (Ajuste.pode() && listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale").all { Ajuste.mudar("global", it, "0.5") }) { con.ok("0.5x (confirmado)"); feitos++ } else { animacoes(con); if ((Ajuste.ler("global", "animator_duration_scale").toFloatOrNull() ?: 1f) <= 0.5f) feitos++ } } } else con.ok("${an}x")

        con.subtitulo("4. Antisserrilhado forçado (MSAA 4x)")
        if (Sh.getprop("debug.egl.force_msaa") == "true" || Ajuste.ler("global", "force_msaa") == "1") {
            con.aviso("“Forçar MSAA 4x” está LIGADO nas Opções do desenvolvedor: derruba muito o FPS.")
            if (con.ask("Desligar?")) { Auto.desligarMsaa(con); feitos++ }
        } else con.ok("Desligado")

        con.subtitulo("5. Varredura de Wi-Fi e Bluetooth")
        val ws = Ajuste.ler("global", "wifi_scan_always_enabled")
        if (ws == "1") {
            con.info("O celular procura redes o tempo todo (causa picos de ping a cada minuto).")
            if (Ajuste.pode()) { if (con.ask("Desligar a varredura em segundo plano?")) { if (Ajuste.mudar("global", "wifi_scan_always_enabled", "0")) { Ajuste.mudar("global", "ble_scan_always_enabled", "0"); con.ok("Desligada (confirmado)"); feitos++ } else con.aviso("O Android não aceitou. Desligue em Localização > Busca por Wi-Fi e Bluetooth.") } }
            else { con.info("Desligue em Configurações > Localização > Serviços de localização > Busca por Wi-Fi e Bluetooth."); if (con.ask("Abrir Localização?")) con.abrirEEsperar(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        } else con.ok("Desligada")

        con.subtitulo("6. Armazenamento")
        val (livre, total) = Sensores.armazenamento()
        if (total > 0 && livre * 100 / total < 10) { con.aviso("Menos de 10% livre: o celular fica lento para carregar jogos."); if (con.ask("Fazer a Limpeza rápida agora?")) { Limpeza.rapida(con, false); feitos++ } } else con.ok("${fmtTam(livre)} livres")

        con.subtitulo("7. Ferramenta de jogos do fabricante")
        val f = FerramentaFabricante.encontrar(ctx)
        if (f != null) {
            con.linha("Encontrei: ${f.second}", T.azul)
            con.info("Ela tem o modo Desempenho, bloqueio de notificações e o contador de FPS do próprio fabricante.")
            if (con.ask("Abrir ${f.second}?")) { if (!Links.abrirPacote(ctx, f.first)) Links.app(ctx, f.first) }
        } else con.info("Nenhuma encontrada (use o Modo Jogo do SerpyraToolkit).")

        con.subtitulo("8. Memória")
        if (con.ask("Encerrar apps em segundo plano agora?")) { if (liberarRam(con)) feitos++ }

        con.linha("")
        con.ok("Pronto: $feitos melhoria(s) aplicada(s). Rode o Raio-X para ver a nova nota.")
        Log2.registrar("otimizar: $feitos")
    }

    // ---------------- por jogo ----------------
    fun porJogo(con: Console, fixo: Jogo? = null) {
        if (!Pro.exigir(con, "Ajustes para um jogo")) return
        val ctx = con.ctx
        con.titulo("Ajustes para um jogo")
        val j = fixo ?: Jogos.escolher(con) ?: return
        con.linha(j.nome, T.verde)
        Jogos.tamanho(ctx, j.pkg)?.let { con.info("Ocupa ${fmtTam(it.total)} (cache ${fmtTam(it.cache)})") }
        val op = con.varios("O que fazer?", listOf(
            "Bateria sem restrição para o jogo (não é congelado em segundo plano)",
            "Abrir informações do app (limpar cache, permissões)",
            "Recomendações de gráficos dentro do jogo",
            "Abrir pelo Modo Jogo"
        ))
        for (i in op) when (i) {
            0 -> if (!Auto.liberarJogo(con, j.pkg)) {
                con.linha("Vou abrir o app: toque em Bateria > Sem restrição (ou Irrestrito).", T.azul)
                con.abrirEEsperar(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + j.pkg)))
            }
            1 -> con.abrirEEsperar(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + j.pkg)))
            2 -> dicasGraficos(con)
            3 -> { Store.putStr("ultimo_jogo", j.pkg); ModoJogo.executar(con) }
        }
    }

    fun dicasGraficos(con: Console) {
        val ctx = con.ctx
        val ram = Sensores.memoria(ctx).totalMem
        val (_, hz) = Sensores.tela(ctx)
        con.subtitulo("Recomendação para este celular")
        val nivel = when { ram >= 11L shl 30 -> 3; ram >= 7L shl 30 -> 2; ram >= 5L shl 30 -> 1; else -> 0 }
        val nomes = listOf("Básico", "Intermediário", "Forte", "Topo de linha")
        con.linha("Perfil: ${nomes[nivel]} (${fmtTam(ram)} de RAM, tela ${f0(hz.toDouble())} Hz)", T.azul)
        con.item("Taxa de quadros: " + if (hz > 61 && nivel >= 2) "a mais alta que o jogo oferecer (90/120)" else if (nivel >= 1) "60 FPS (Alto/Ultra)" else "60 FPS com gráficos no mínimo, ou 30 estável")
        con.item("Gráficos: " + listOf("Suave/Baixo", "Equilibrado/Médio", "Alto", "Muito alto")[nivel] + " — prefira FPS alto a gráfico bonito em jogos competitivos")
        con.item("Sombras e antisserrilhado: desligue primeiro se o FPS cair; são os que mais pesam")
        con.item("Resolução de renderização: 80–90% quase não muda a imagem e esfria o celular")
        con.item("Se esquenta e cai depois de 10 min: baixe um nível; FPS estável vale mais que pico")
    }

    // ---------------- medidor de FPS ----------------
    fun medidor(con: Console) {
        if (!Pro.exigir(con, "O Medidor de FPS na tela")) return
        con.titulo("Medidor de FPS")
        if (!Sh.privOk() || Sensores.contadorQuadros() == null) {
            con.linha(if (Sh.privOk()) "Este Android não libera o contador de quadros nem para o modo automático (alguns fabricantes bloqueiam). Dá para ver o FPS pelos jeitos abaixo." else "Um app comum não pode ler o FPS de outro app (bloqueio de segurança do Android). Com o modo automático (Shizuku) ou root, o medidor funciona.", T.texto2)
            con.subtitulo("Como ver o FPS sem root")
            val f = FerramentaFabricante.encontrar(con.ctx)
            if (f != null) con.item("${f.second}: tem contador de FPS no painel durante o jogo", T.verde)
            con.item("Muitos jogos mostram o FPS nas configurações (ex.: “Mostrar FPS”)")
            con.item("Opções do desenvolvedor > “Mostrar taxa de atualização” mostra os Hz da tela")
            con.item("O painel flutuante do Modo Jogo mostra temperatura, bateria e RAM em tempo real")
            if (f != null && con.ask("Abrir ${f.second}?")) { if (!Links.abrirPacote(con.ctx, f.first)) Links.app(con.ctx, f.first) }
            return
        }
        con.linha("Abra o jogo agora. Vou medir por 60 s.", T.texto2)
        if (!con.ask("Começar?")) return
        val amostras = ArrayList<Double>()
        var ant = Sensores.contadorQuadros() ?: run { con.erro("Não consegui ler o contador de quadros"); return }
        var t = System.nanoTime()
        for (i in 0 until 60) {
            if (con.cancelado) break
            con.sleep(1000)
            val c = Sensores.contadorQuadros() ?: break
            val agora = System.nanoTime()
            val fps = (c - ant) * 1e9 / (agora - t)
            ant = c; t = agora
            amostras.add(fps)
            if (i % 5 == 4) con.linha(String.format(Locale.US, "%2d s · %.0f FPS · %s", i + 1, fps, Sensores.resumo(con.ctx)), T.texto2)
        }
        if (amostras.isEmpty()) return
        val ord = amostras.sorted()
        con.linha("")
        con.linha(String.format(Locale.US, "Média %.0f FPS · 1%% pior %.0f · mínimo %.0f", amostras.average(), ord[(ord.size / 100).coerceAtMost(ord.size - 1)], ord[0]), T.verde)
    }

    // ---------------- segundo plano ----------------
    fun segundoPlano(con: Console) {
        val ctx = con.ctx
        con.titulo("Apps em segundo plano")
        con.linha("Apps que iniciam sozinhos quando o celular liga ou que você usou hoje: eles disputam memória e bateria com o jogo.", T.texto2)
        val pm = ctx.packageManager
        val boot = pm.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED), PackageManager.MATCH_DISABLED_COMPONENTS)
            .map { it.activityInfo.packageName }.toSet()
            .filter { p -> try { (pm.getApplicationInfo(p, 0).flags and ApplicationInfo.FLAG_SYSTEM) == 0 && p != ctx.packageName } catch (e: Exception) { false } }
        con.subtitulo("Iniciam com o celular (${boot.size})")
        val nomes = boot.map { Pair(it, Jogos.nome(ctx, it)) }.sortedBy { it.second.lowercase() }
        for ((_, n) in nomes) con.item(n)
        if (Acesso.uso()) {
            con.subtitulo("Mais usados hoje")
            for ((p, ms) in Jogos.usoHoje(ctx).entries.sortedByDescending { it.value }.take(10)) if (ms > 60000) con.item("${Jogos.nome(ctx, p)}: ${ms / 60000} min")
        }
        con.info("Para um app não rodar escondido: abra-o e escolha Bateria > Restrita (ou Otimizada).")
        val sel = con.varios(if (Sh.privOk()) "Impedir quais de rodar escondidos?" else "Abrir quais para restringir?", nomes.map { it.second })
        for (i in sel) if (!Auto.restringirSegundoPlano(con, nomes[i].first, nomes[i].second)) con.abrirEEsperar(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + nomes[i].first)))
    }
}
