package com.pctoolkit.mobile

/** Estrutura de navegacao do app. */
object Menus {
    private val L = R.drawable.ic_limpeza
    private val F = R.drawable.ic_fps
    private val A = R.drawable.ic_aparencia
    private val TP = R.drawable.ic_temperatura
    private val S = R.drawable.ic_seguranca
    private val M = R.drawable.ic_manutencao
    private val B = R.drawable.ic_backup
    private val D = R.drawable.ic_desfazer
    private val J = R.drawable.ic_jogo
    private val RD = R.drawable.ic_rede
    private val RX = R.drawable.ic_raiox
    private val CH = R.drawable.ic_chave

    private fun selo(precisa: Boolean, texto: String) = if (precisa) texto else ""

    fun categorias(): List<Item> = listOf(
        Item("Tuner de jogos", "Resolução, FPS e driver por jogo", J, T.verde, tela = { it.abrirTela { it.telaTuner() } }),
        Item("Teclado e controles", "Botões estilo MuMu por cima do jogo", J, T.roxo, acao = { TecladoJogo.iniciar(it) }),
        Item("Desempenho e FPS", "Raio-X, teste, otimizar, tela", F, T.verde, sub = { fps() }),
        Item("Limpeza", "Espaço, cache, duplicados", L, T.azul, sub = { limpeza() }),
        Item("Rede para jogos", "Ping, velocidade, DNS", RD, T.ciano, sub = { rede() }),
        Item("Temperatura", "Calor, monitor, alerta", TP, T.laranja, sub = { temperatura() }),
        Item("Segurança", "Vírus, espiões, golpes", S, T.vermelho, sub = { seguranca() }),
        Item("Aparência", "Fluidez e animações", A, T.roxo, acao = { Sistema.aparencia(it) }),
        Item("Manutenção", "Saúde, bateria, backup", M, T.amarelo, sub = { manutencao() }),
        Item("Acesso e permissões", "Nível: " + Acesso.nivel(), CH, T.texto2, tela = { it.abrirTela { it.telaPermissoes() } }),
        Item("SerpyraToolkit Pro", if (Pro.ativo()) "Ativo até " + Licenca.validadeTexto() else Vendedor.PRECO_TXT + " por mês", CH, T.amarelo, tela = { it.abrirTela { it.telaPro() } })
    )

    fun fps() = Menu("Desempenho e FPS", "Tudo que deixa o jogo mais fluido", F, T.verde, listOf(
        Item("Tuner de jogos", "Perfil por jogo: modo, resolução, FPS e driver", J, T.verde, "Novo", T.verde, tela = { it.abrirTela { it.telaTuner() } }),
        Item("Teclado e controles no jogo", "Botões com a tecla que quiser, toque, analógico e macro", J, T.roxo, "Novo", T.roxo, acao = { TecladoJogo.iniciar(it) }),
        Item("Raio-X de desempenho", "Nota de 0 a 100 e correção com um toque", RX, T.verde, "Recomendado", T.verde, acao = { RaioX.executar(it) }),
        Item("Modo Jogo", "Silencia, libera memória, abre o jogo e vigia o calor", J, T.verde, acao = { ModoJogo.executar(it) }),
        Item("Otimizar o celular para jogos", "Passo a passo completo", F, T.verde, acao = { Fps.otimizar(it) }),
        Item("Teste de desempenho", "Força do processador e estabilidade com calor", F, T.azul, acao = { Fps.benchmark(it) }),
        Item("Taxa de atualização da tela", "Destravar 90/120 Hz", F, T.azul, acao = { Fps.tela(it) }),
        Item("Ajustes para um jogo", "Bateria sem restrição e gráficos ideais", J, T.azul, acao = { Fps.porJogo(it) }),
        Item("Liberar memória", "Encerra apps em segundo plano", F, T.roxo, acao = { Fps.liberarRam(it) }),
        Item("Apps em segundo plano", "Quem inicia sozinho e gasta recursos", F, T.roxo, acao = { Fps.segundoPlano(it) }),
        Item("Medidor de FPS", "FPS real durante o jogo", F, T.cinza, selo(!Sh.privOk(), "Automático"), T.cinza, acao = { Fps.medidor(it) }),
        Item("Histórico do Modo Jogo", "Sessões, temperatura e bateria", J, T.cinza, acao = { ModoJogo.historico(it) })
    ))

    fun limpeza() = Menu("Limpeza", "Livre: " + fmtTam(Sensores.armazenamento().first), L, T.azul, listOf(
        Item("Limpeza rápida", "Lixo seguro + cache de todos os apps", L, T.azul, "Recomendado", T.verde, acao = { Limpeza.rapida(it, false) }),
        Item("Simular limpeza", "Mostra quanto libera sem apagar nada", L, T.azul, acao = { Limpeza.rapida(it, true) }),
        Item("Analisar armazenamento", "O que ocupa espaço, por tipo e pasta", L, T.azul, acao = { Limpeza.analisar(it) }),
        Item("Arquivos grandes", "Encontre os maiores arquivos", L, T.roxo, acao = { Limpeza.grandes(it) }),
        Item("Arquivos duplicados", "Cópias idênticas de fotos e vídeos", L, T.roxo, acao = { Limpeza.duplicados(it) }),
        Item("Downloads antigos", "Arquivos esquecidos na pasta Download", L, T.roxo, acao = { Limpeza.downloadsAntigos(it) }),
        Item("Quanto cada jogo ocupa", "Tamanho real com dados e cache", J, T.verde, acao = { Limpeza.tamanhoJogos(it) }),
        Item("Apps que mais ocupam", "Limpar dados ou desinstalar", L, T.amarelo, acao = { Limpeza.appsMaiores(it) }),
        Item("Apps esquecidos", "Sem uso há semanas", L, T.amarelo, acao = { Limpeza.esquecidos(it) }),
        Item("Lixeira do SerpyraToolkit", fmtTam(Lixeira.tamanho()) + " · restaurar ou esvaziar", D, T.cinza, acao = { Limpeza.lixeiraMenu(it) })
    ))

    fun rede() = Menu("Rede para jogos", "Conexão: " + Rede.tipo(PcApp.ctx), RD, T.ciano, listOf(
        Item("Teste de conexão para jogos", "Ping, jitter e perda até o roteador e a internet", RD, T.ciano, "Recomendado", T.verde, acao = { Rede.testeCompleto(it) }),
        Item("Teste de velocidade", "Download e upload", RD, T.ciano, acao = { Rede.velocidade(it) }),
        Item("DNS mais rápido", "Testa 5 servidores e configura o melhor", RD, T.azul, acao = { Rede.dns(it) }),
        Item("Informações do Wi-Fi", "Sinal, faixa e velocidade do link", RD, T.azul, acao = { it.titulo("Wi-Fi"); Rede.infoWifi(it) })
    ))

    fun temperatura() = Menu("Temperatura", "Bateria agora: " + f1(Sensores.bateria(PcApp.ctx).tempC) + " °C", TP, T.laranja, listOf(
        Item("Diagnóstico", "Temperatura, limitação por calor e sensores", TP, T.laranja, acao = { Temperatura.diagnostico(it) }),
        Item("Monitor em tempo real", "Temperatura, bateria, RAM e CPU", TP, T.laranja, acao = { Temperatura.monitor(it) }),
        Item("Modo Frio", "Segura a temperatura durante o jogo sem desligar o boost", TP, T.ciano, "Novo", T.ciano, acao = { con ->
            con.titulo("Modo Frio")
            con.linha("Funciona dentro do Modo Jogo. Conforme o celular esquenta, ele age em etapas e volta tudo quando esfria:", T.texto2)
            con.item("Nível 1 (perto do limite): pausa o Turbo e baixa a tela de 120 para 90 Hz")
            con.item("Nível 2 (no limite): tela em 60 Hz, o que limita o jogo a 60 FPS estáveis")
            con.item("Nível 3 (passou do limite): limita o jogo a 45 FPS até esfriar")
            con.info("Limite atual: " + Store.int("limite_bateria", 43) + " °C. Mude em Alerta de calor.")
            if (Pro.exigir(con, "O Modo Frio") && con.ask("Abrir o Modo Jogo agora?")) ModoJogo.executar(con)
        }),
        Item("Teste de calor", "Quanto esquenta e se perde força", TP, T.vermelho, acao = { Temperatura.estresse(it) }),
        Item("Alerta de calor", "Limite atual: " + Store.int("limite_bateria", 43) + " °C", TP, T.amarelo, acao = { Temperatura.limite(it) }),
        Item("Dicas para esquentar menos", "O que realmente funciona", TP, T.verde, acao = { Temperatura.dicas(it) }),
        Item("Economia de energia", if (Sensores.economiaEnergia(PcApp.ctx)) "Ligada agora" else "Desligada", TP, T.cinza, acao = { Temperatura.economia(it) }),
        Item("Limitar a CPU", "Perfis frio e silencioso", TP, T.cinza, selo(!Sh.rootConhecido(), "Root"), T.cinza, acao = { Temperatura.limitarCpu(it) })
    ))

    fun seguranca() = Menu("Segurança", Store.str("seg_ultimo").ifEmpty { "Nenhuma verificação ainda" }, S, T.vermelho, listOf(
        Item("Verificação de segurança", "Apps espiões, golpes e configurações de risco", S, T.vermelho, "Recomendado", T.verde, acao = { Seguranca.cacar(it) }),
        Item("Verificar na VirusTotal", "APK ou app com ~70 antivírus", S, T.vermelho, acao = { Seguranca.verificarArquivoVt(it) }),
        Item("Blindagem", "Fechar as portas de entrada de vírus", S, T.amarelo, acao = { Seguranca.blindagem(it) }),
        Item("Testar um app com segurança", "Como isolar apps duvidosos", S, T.azul, acao = { Seguranca.caixaDeAreia(it) }),
        Item("Último relatório", "Resultado da última verificação", S, T.cinza, acao = { Seguranca.ultimoRelatorio(it) })
    ))

    fun manutencao() = Menu("Manutenção", "Saúde, backup e restauração", M, T.amarelo, listOf(
        Item("Saúde do celular", "Bateria, ciclos, tempo ligado e armazenamento", M, T.amarelo, acao = { Sistema.saude(it) }),
        Item("Atualizações", "Sistema e apps", M, T.amarelo, acao = { Sistema.atualizacoes(it) }),
        Item("Backup", Store.str("ultimo_backup").ifEmpty { "Copiar fotos e arquivos com conferência" }, B, T.azul, acao = { Sistema.backup(it) }),
        Item("Restauração de fábrica", "Checklist seguro antes de apagar tudo", B, T.vermelho, acao = { Sistema.restaurarFabrica(it) }),
        Item("Desfazer alterações", Ajuste.quantos().toString() + " configuração(ões) alteradas", D, T.texto2, acao = { Sistema.desfazer(it) }),
        Item("Reiniciar", "Resolve lentidão acumulada", M, T.cinza, acao = { Sistema.reiniciar(it) }),
        Item("Sobre", "Versão e níveis de acesso", M, T.cinza, acao = { Sistema.info(it) })
    ))

    /** Assistente: faz o essencial em sequencia. */
    fun assistente(con: Console) {
        con.titulo("Assistente")
        con.linha("Faz o essencial em sequência. Desmarque o que não quiser.", T.texto2)
        val etapas = listOf("Raio-X de desempenho e correções", "Limpeza rápida", "Liberar memória", "Verificação de segurança", "Teste de conexão para jogos")
        val sel = con.varios("Etapas", etapas, true)
        for (i in sel) {
            if (con.cancelado) break
            when (i) {
                0 -> RaioX.executar(con)
                1 -> Limpeza.rapida(con, false)
                2 -> Fps.liberarRam(con)
                3 -> Seguranca.cacar(con)
                4 -> Rede.testeCompleto(con)
            }
        }
        con.linha("")
        if (!con.cancelado) con.ok("Assistente concluído. Para jogar, use o Modo Jogo.")
    }
}
