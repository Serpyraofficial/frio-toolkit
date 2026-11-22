package com.pctoolkit.mobile

import android.os.Build

/** Menus (mesma estrutura do PC Toolkit 4.0). */
object Menus {
    private val ctx get() = PcApp.ctx

    fun principal(): Menu {
        val (livre, total) = Sensores.armazenamento()
        val nota = Store.int("nota_fps", -1)
        val sub = "Armazenamento: ${fmtTam(livre)} livres de ${fmtTam(total)}" + if (nota >= 0) "   |   Nota de FPS: $nota/100" else ""
        return Menu("PC TOOLKIT MOBILE 4.0", sub, listOf(
            Item("1) ASSISTENTE: fazer tudo", "Limpeza + Raio-X de FPS + otimizacao + aparencia + seguranca, passo a passo", C.verde, acao = { assistente(it) }),
            Item("2) Limpeza do celular", "Padrao, profunda, simulacao, arquivos grandes, jogos, Lixeira...", sub = { limpeza() }),
            Item("3) CENTRAL DE FPS", "Raio-X, Modo Jogo TURBO, medidor de FPS, tela, rede, latencia", C.verde, sub = { fps() }),
            Item("4) Aparencia e desempenho INTELIGENTE", "Animacoes, desfoque e tela conforme o seu hardware", C.verde, acao = { Aparencia.executar(it, ctx) }),
            Item("5) Temperatura e refrigeracao", "Diagnostico, monitor ao vivo, perfis frios, teste de estresse", sub = { temperatura() }),
            Item("6) SEGURANCA", "Cacar malware, app espiao, minerador e acesso indevido", C.vermelho, sub = { seguranca() }),
            Item("7) Manutencao do sistema", "Reparar/otimizar apps, TRIM, atualizacoes", sub = { manutencao() }),
            Item("8) Megazord: backup + restauracao de fabrica", "", C.magenta, sub = { megazord() }),
            Item("9) Desfazer TODAS as mudancas do toolkit", "", C.amarelo, acao = { desfazer(it) })
        ))
    }

    fun limpeza() = Menu("LIMPEZA DO CELULAR", "", listOf(
        Item("1) Analisar o armazenamento", "Mostra o que ocupa espaco, nao apaga nada", acao = { Limpeza.analisar(it, ctx) }),
        Item("2) Limpeza padrao", "Cache de todos os apps (menos jogos e apps abertos), miniaturas, temporarios", acao = { Limpeza.limpar(it, ctx, false, false) }),
        Item("3) Limpeza PROFUNDA", "+ logs do sistema, cache dos jogos (com aviso), otimizacao dos apps", C.magenta, acao = { Limpeza.limpar(it, ctx, true, false) }),
        Item("4) SIMULAR limpeza profunda", "Mostra quanto liberaria, sem apagar", acao = { Limpeza.limpar(it, ctx, true, true) }),
        Item("5) Revisar arquivos grandes", "Voce escolhe o que vai para a Lixeira do toolkit", acao = { Limpeza.revisarGrandes(it) }),
        Item("6) Revisar Downloads antigos", "Voce escolhe o que vai para a Lixeira do toolkit", acao = { Limpeza.revisarDownloads(it) }),
        Item("7) Quanto cada jogo ocupa", "", acao = { Limpeza.tamanhoJogos(it, ctx) }),
        Item("8) Otimizar os apps (ART)", "Equivale ao CompactOS: apps e jogos abrem mais rapido", acao = { Manutencao.dexopt(it) }),
        Item("9) Apps instalados (os maiores primeiro)", "Para desinstalar o que nao usa", acao = { Limpeza.appsInstalados(it, ctx) }),
        Item("10) Lixeira do toolkit", "Restaurar ou esvaziar", acao = { Limpeza.lixeira(it) })
    ))

    fun fps(): Menu {
        val nota = Store.int("nota_fps", -1)
        return Menu("CENTRAL DE FPS / JOGOS", if (nota >= 0) "Ultima nota do Raio-X: $nota/100" else "", listOf(
            Item("1) RAIO-X DE FPS", "Nota do celular + correcao com 1 toque (comece por aqui)", C.verde, acao = { RaioX.executar(it, ctx) }),
            Item("2) MODO JOGO", "Prepara, abre o jogo, pacote TURBO, mede FPS e restaura tudo", C.verde, acao = { ModoJogo.executar(it, ctx) }),
            Item("3) Medir FPS / benchmark ANTES x DEPOIS", "FPS medio, pior momento, estabilidade, gargalo", acao = { Fps.benchmark(it, ctx) }),
            Item("4) Otimizar o Android para jogos", "Perfil de CPU, Modo de Jogo, bateria, animacoes, Wi-Fi", acao = { Fps.otimizarAndroid(it, ctx) }),
            Item("5) Otimizar graficos dos jogos", "Minecraft, Roblox, Unreal + escala de resolucao/FPS para qualquer jogo", acao = { Fps.otimizarGraficos(it, ctx) }),
            Item("6) Ajustes por jogo", "Desempenho, Game Driver, escala, segundo plano - qualquer jogo", acao = { Fps.ajustesPorJogo(it, ctx) }),
            Item("7) Tela: Hz maximo e limite de FPS ideal", "", acao = { Fps.tela(it, ctx) }),
            Item("8) Rede para jogos online", "Jitter, perda, DNS mais rapido, Wi-Fi de baixa latencia", sub = { rede() }),
            Item("9) Diagnostico completo do celular", "", acao = { Fps.diagnostico(it, ctx) }),
            Item("10) Apps que abrem com o celular", "Desativar / restringir em segundo plano", acao = { Fps.inicializacao(it, ctx) }),
            Item("11) Liberar RAM agora", "", acao = { Fps.liberarRam(it, ctx) }),
            Item("12) Historico de sessoes e medicoes", "", acao = { Fps.historico(it) }),
            Item("13) Desfazer todas as mudancas", "", C.amarelo, acao = { desfazer(it) })
        ))
    }

    fun rede() = Menu("REDE PARA JOGOS ONLINE", "", listOf(
        Item("1) Teste completo de conexao", "Ping, jitter e perda: roteador x internet", acao = { Rede.testeCompleto(it, ctx) }),
        Item("2) Ajustes de TCP", "BBR, sem partida lenta, Fast Open (equivale ao Nagle do PC)", acao = { Rede.ajustesTcp(it) }),
        Item("3) Wi-Fi para jogos", "Baixa latencia, alto desempenho, sem varredura em segundo plano", acao = { Rede.placaRede(it) }),
        Item("4) DNS: testar o mais rapido e trocar", "Usa o DNS Privado do Android", acao = { Rede.dns(it, ctx) }),
        Item("5) DNS: voltar ao original", "", acao = { Rede.restaurarDns(it) })
    ))

    fun temperatura(): Menu = Menu("TEMPERATURA E REFRIGERACAO", "Perfil ativo: " + Store.str("perfil_ativo").ifEmpty { "padrao do celular" }, listOf(
        Item("1) Diagnostico termico", "Temperaturas, throttling, quem esquenta o celular", acao = { Temperatura.diagnostico(it, ctx) }),
        Item("2) Monitor ao vivo", "CPU, bateria, clock, uso por nucleo, GPU e FPS", acao = { Temperatura.monitor(it, ctx) }),
        Item("3) Aplicar perfil de energia/temperatura", "Maximo FPS / Equilibrado frio / Silencioso / Personalizado", acao = { Temperatura.aplicarPerfilMenu(it) }),
        Item("4) Voltar a CPU/GPU ao original", "", acao = { Temperatura.restaurarPerfil(it) }),
        Item("5) Teste de estresse da CPU", "Verifica se a refrigeracao aguenta", acao = { Temperatura.estresse(it, ctx) }),
        Item("6) Dicas de refrigeracao", "O que realmente baixa a temperatura", acao = { Temperatura.dicas(it) }),
        Item("7) Limite da protecao termica do Modo Jogo", "", acao = { Temperatura.configurarLimite(it) })
    ))

    fun seguranca() = Menu("SEGURANCA: MALWARE, ESPIOES, MINERADORES", "", listOf(
        Item("1) Varredura RAPIDA", "Apps, permissoes, CPU, rede, configuracoes (~1 min)", acao = { Seguranca.cacar(it, ctx, "Rapida") }),
        Item("2) Varredura COMPLETA", "+ arquivos suspeitos e APKs de mod/cheat baixados", acao = { Seguranca.cacar(it, ctx, "Completa") }),
        Item("3) Cacar so MINERADORES", "", acao = { Seguranca.cacar(it, ctx, "Minerador") }),
        Item("4) Protecao do Google (Play Protect)", "Equivale ao antivirus do Windows", acao = { Seguranca.playProtect(it) }),
        Item("5) Quarentena", "Ver / restaurar / desinstalar", acao = { Seguranca.menuQuarentena(it, ctx) }),
        Item("6) Ver o ultimo relatorio", "", acao = { Seguranca.ultimoRelatorio(it, ctx) }),
        Item("7) BLINDAGEM", "Proteger agora, caixa de areia, VirusTotal", C.magenta, sub = { blindagem() })
    ))

    fun blindagem() = Menu("BLINDAGEM: NAO DEIXAR O TROJAN ENTRAR", "Pensado para mod/cheat/crack baixado que vem com trojan/stealer junto", listOf(
        Item("1) PROTEGER AGORA (recomendado)", "Play Protect total, bloqueia instalacao de APK por navegador/Telegram, procura APKs suspeitos", C.verde, acao = { Seguranca.protegerAgora(it, ctx) }),
        Item("2) CAIXA DE AREIA", "Como testar um app suspeito isolado do seu perfil principal", C.verde, acao = { Seguranca.caixaDeAreia(it) }),
        Item("3) Verificar arquivo ou app na VirusTotal", "~70 antivirus de uma vez (chave gratuita)", acao = { Seguranca.verificarArquivoVt(it, ctx) }),
        Item("4) Configurar/trocar a chave da VirusTotal", "", acao = { Seguranca.configurarVt(it) })
    ))

    fun manutencao() = Menu("MANUTENCAO DO SISTEMA", "", listOf(
        Item("1) Reparar / otimizar o sistema", "Caches do sistema + ART + apps que mais travam", acao = { Manutencao.reparar(it, ctx) }),
        Item("2) Otimizar armazenamento (TRIM)", "", acao = { Manutencao.trim(it) }),
        Item("3) Atualizacoes e driver de video", "", acao = { Manutencao.atualizacoes(it, ctx) })
    ))

    fun megazord() = Menu("MEGAZORD: RESTAURACAO DE FABRICA", Megazord.backupOk?.let { "Backup conferido: " + it.replace(Sd.base, "/sdcard") } ?: "", listOf(
        Item("1) PASSO 1 - Preparar", "Checagens + inventario + BACKUP conferido", acao = { Megazord.preparar(it, ctx) }),
        Item("2) PASSO 2 - Restaurar o celular", "Apaga TUDO", C.vermelho, acao = { Megazord.redefinir(it) })
    ))

    fun desfazer(con: Console) {
        con.titulo("DESFAZER MUDANCAS DO TOOLKIT")
        if (!con.exigeRoot()) return
        var feito = false
        val n = Undo.global.quantos()
        if (n > 0) {
            Undo.global.desfazer { con.info("revertido: $it") }
            con.ok("$n configuracoes revertidas (CPU, tela, animacoes, rede, DNS, jogos, permissoes...)")
            Store.putStr("perfil_ativo", "")
            feito = true
        }
        if (!feito) con.linha("Nao havia nada para desfazer.")
        else con.linha("Pronto. Reinicie o celular para tudo voltar 100% ao normal.", C.verde)
        con.info("A quarentena de apps e a Lixeira do toolkit tem menus proprios (restaurar).")
    }

    fun assistente(con: Console) {
        con.titulo("ASSISTENTE: LIMPEZA + FPS + APARENCIA + SEGURANCA")
        con.linha("Vou guiar voce em 7 etapas, perguntando antes de cada uma:")
        con.linha("  1) Limpeza profunda   2) Raio-X de FPS   3) Otimizar o Android + perfil de temperatura")
        con.linha("  4) Aparencia          5) Graficos dos jogos   6) Diagnostico termico   7) Varredura de seguranca")
        if (!con.ask("Comecar?")) return
        if (!con.exigeRoot()) return
        con.linha(""); con.linha("[1/7] Limpeza profunda", C.ciano)
        if (con.ask("Fazer a limpeza profunda agora?")) Limpeza.limpar(con, ctx, true, false)
        con.linha(""); con.linha("[2/7] Raio-X de FPS", C.ciano)
        if (con.ask("Rodar o Raio-X de FPS (nota e correcoes)?")) RaioX.executar(con, ctx)
        con.linha(""); con.linha("[3/7] Otimizar o Android + perfil de energia/temperatura", C.ciano)
        if (con.ask("Aplicar agora?")) Fps.otimizarAndroid(con, ctx)
        con.linha(""); con.linha("[4/7] Aparencia e desempenho inteligente", C.ciano)
        if (con.ask("Ajustar conforme o seu hardware?")) Aparencia.executar(con, ctx)
        con.linha(""); con.linha("[5/7] Graficos dos jogos", C.ciano)
        if (con.ask("Otimizar os graficos dos jogos agora?")) Fps.otimizarGraficos(con, ctx)
        con.linha(""); con.linha("[6/7] Diagnostico termico", C.ciano)
        Temperatura.diagnostico(con, ctx)
        con.linha(""); con.linha("[7/7] Varredura de seguranca", C.ciano)
        if (con.ask("Fazer a varredura RAPIDA de ameacas agora?")) Seguranca.cacar(con, ctx, "Rapida")
        con.linha("")
        con.linha("Assistente concluido. Para jogar, use o MODO JOGO (Central de FPS).", C.verde)
        con.info("Para ver o ganho real: Medir FPS antes e depois, no mesmo lugar do jogo. (Android ${Build.VERSION.RELEASE})")
    }
}
