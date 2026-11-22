package com.pctoolkit.mobile

import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout

/** Telas do Tuner de jogos: lista de jogos e perfil de cada jogo. */

private fun MainActivity.iconeApp(pkg: String): Drawable? = try { packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }

private fun MainActivity.cartao(): LinearLayout {
    val c = Ui.vertical(this)
    c.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16))
    c.background = Ui.forma(this, T.surface, 18, T.outline)
    return c
}

/** Linha de opcoes selecionaveis (segmentos). */
private fun MainActivity.seletor(opcoes: List<String>, sel: Int, cor: Int, habilitadas: List<Boolean>? = null, onSel: (Int) -> Unit): View {
    val hs = HorizontalScrollView(this)
    hs.isHorizontalScrollBarEnabled = false
    val l = Ui.horizontal(this)
    l.setPadding(0, Ui.dp(this, 8), 0, 0)
    for ((i, t) in opcoes.withIndex()) {
        val on = i == sel
        val hab = habilitadas?.getOrNull(i) ?: true
        val v = Ui.texto(this, t, 12.5f, if (!hab) T.texto3 else if (on) T.bg else T.texto, Ui.medio)
        v.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8))
        v.background = Ui.clicavel(this, if (on) Ui.forma(this, cor, 50) else Ui.forma(this, T.surface2, 50, T.outline), 50)
        v.isClickable = true
        v.setOnClickListener { if (hab) onSel(i) }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.rightMargin = Ui.dp(this, 8)
        l.addView(v, lp)
    }
    hs.addView(l)
    return hs
}

private fun MainActivity.blocoOpcao(col: LinearLayout, titulo: String, desc: String, conteudo: View) {
    val c = cartao()
    c.addView(Ui.texto(this, titulo, 15f, T.texto, Ui.medio))
    if (desc.isNotEmpty()) { val d = Ui.texto(this, desc, 12f, T.texto2); d.setPadding(0, Ui.dp(this, 2), 0, 0); c.addView(d) }
    c.addView(conteudo)
    col.addView(c, Ui.linhaLp(this))
}

fun MainActivity.telaTuner() {
    val col = coluna()
    col.addView(barraTopo("Tuner de jogos", "Perfil de desempenho para cada jogo"))

    // status do aparelho
    val st = cartao()
    st.background = Ui.gradiente(this, 0xFF0F2A22.toInt(), 0xFF101C30.toInt(), 20)
    val topo = Ui.horizontal(this)
    topo.addView(Ui.texto(this, "Compatibilidade", 15.5f, T.texto, Ui.negrito), Ui.peso())
    topo.addView(if (Sh.privOk()) Ui.chip(this, "Pronto", T.verde) else Ui.chip(this, "Falta o modo automático", T.amarelo))
    st.addView(topo)
    fun linha(t: String, ok: Boolean) {
        val v = Ui.texto(this, (if (ok) "✓  " else "–  ") + t, 12.5f, if (ok) T.texto else T.texto3)
        v.setPadding(0, Ui.dp(this, 5), 0, 0)
        st.addView(v)
    }
    linha("Modo de jogo e escala de resolução" + if (Tuner.suportaGameMode()) "" else " (Android 12+)", Tuner.suportaGameMode())
    linha("Limite de FPS por jogo" + if (Tuner.suportaFps()) "" else " (Android 13+)", Tuner.suportaFps())
    linha("Driver ANGLE (Vulkan)", Tuner.temAngle())
    linha("Driver de jogo atualizável", Tuner.temDriverJogo())
    linha("Resolução da tela durante o jogo", true)
    linha("Medidor de FPS real", Sh.privOk())
    if (!Sh.privOk()) {
        val b = Ui.botao(this, "Ativar modo automático", T.verde, true) { executar("Modo automático") { Priv.ativar(it) } }
        st.addView(b, Ui.linhaLp(this, 12, 0))
    }
    col.addView(st, Ui.linhaLp(this, 0, 6))

    if (!Pro.ativo()) {
        col.addView(Ui.linhaMenu(this, R.drawable.ic_chave, T.amarelo, "Função do SerpyraToolkit Pro", "Monte os perfis à vontade; aplicar no jogo é liberado no Pro (${Vendedor.PRECO_TXT}/mês)", "Assinar", T.amarelo) { abrirTela { telaPro() } })
    }
    col.addView(Ui.secao(this, "Seus jogos"))
    val jogos = Jogos.instalados(this)
    if (jogos.isEmpty()) col.addView(Ui.texto(this, "Nenhum jogo encontrado. Use “Adicionar outro app”.", 13f, T.texto2))
    for (j in jogos) {
        val p = Tuner.perfil(j.pkg)
        val r = Ui.horizontal(this)
        r.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12))
        r.background = Ui.clicavel(this, Ui.forma(this, T.surface, 18, if (p.aplicado) Ui.alpha(T.verde, 0x66) else T.outline), 18)
        val iv = ImageView(this)
        iconeApp(j.pkg)?.let { iv.setImageDrawable(it) }
        r.addView(iv, LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)))
        r.addView(Ui.espaco(this, 12, 1))
        val tc = Ui.vertical(this)
        tc.addView(Ui.texto(this, j.nome, 15f, T.texto, Ui.medio))
        tc.addView(Ui.texto(this, p.resumo(), 12f, if (p.aplicado) T.verde else T.texto3))
        r.addView(tc, Ui.peso())
        r.addView(Ui.icone(this, R.drawable.ic_seta, T.texto3, 18))
        r.isClickable = true
        r.setOnClickListener { abrirTela { telaPerfil(j.pkg, j.nome) } }
        col.addView(r, Ui.linhaLp(this))
    }
    col.addView(Ui.linhaMenu(this, R.drawable.ic_jogo, T.azul, "Adicionar outro app", "Para jogos que não aparecem na lista", "", T.azul) {
        executar("Adicionar jogo") { con -> Jogos.escolher(con, "Escolha o app")?.let { con.ok("${it.nome} adicionado. Toque em Concluir e abra o Tuner de novo.") } }
    })

    col.addView(Ui.secao(this, "Sistema"))
    col.addView(Ui.linhaMenu(this, R.drawable.ic_fps, T.roxo, "Renderizador do sistema", "OpenGL ou Vulkan para a interface", "", T.roxo) { executar("Renderizador do sistema") { Tuner.renderizador(it) } })
    col.addView(Ui.linhaMenu(this, R.drawable.ic_fps, T.verde, "Medidor de FPS", "Confira o ganho real antes e depois", "", T.verde) { executar("Medidor de FPS") { Fps.medidor(it) } })
    setContentView(raiz(col))
}

fun MainActivity.telaPerfil(pkg: String, nome: String) {
    val p = Tuner.perfil(pkg)
    val col = coluna()
    col.addView(barraTopo(nome, if (p.aplicado) "Perfil ativo · " + p.resumo() else "Ainda sem ajustes"))
    fun redesenhar() { setContentView(raiz(col)) }

    // predefinicoes
    val pre = cartao()
    pre.addView(Ui.texto(this, "Predefinições", 15f, T.texto, Ui.medio))
    val nomesPre = listOf("Máximo FPS", "Equilibrado", "Qualidade", "Bateria")
    pre.addView(seletor(nomesPre, -1, T.verde) { i ->
        when (i) {
            0 -> { p.modo = "desempenho"; p.escala = 0.7; p.fps = 0; p.turbo = true }
            1 -> { p.modo = "desempenho"; p.escala = 0.85; p.fps = 60; p.turbo = false }
            2 -> { p.modo = "desempenho"; p.escala = 1.0; p.fps = 0; p.turbo = false }
            3 -> { p.modo = "bateria"; p.escala = 0.85; p.fps = 30; p.turbo = false; p.resGlobal = 0 }
        }
        Tuner.salvar(pkg, p.copy(aplicado = p.aplicado)); abrirTelaAtual { telaPerfil(pkg, nome) }
    })
    col.addView(pre, Ui.linhaLp(this))

    val modos = listOf("padrao", "desempenho", "bateria")
    blocoOpcao(col, "Modo de jogo", "Desempenho libera mais força da CPU/GPU; Bateria economiza.",
        seletor(listOf("Padrão", "Desempenho", "Bateria"), modos.indexOf(p.modo), T.verde, listOf(true, Tuner.suportaGameMode(), Tuner.suportaGameMode())) { i -> p.modo = modos[i]; Tuner.salvar(pkg, p); abrirTelaAtual { telaPerfil(pkg, nome) } })

    blocoOpcao(col, "Resolução do jogo", "Renderizar em menos pixels dá mais FPS e menos calor. 85% quase não muda a imagem.",
        seletor(Tuner.ESCALAS.map { it.second }, Tuner.ESCALAS.indexOfFirst { Math.abs(it.first - p.escala) < 0.01 }, T.azul, Tuner.ESCALAS.map { Tuner.suportaGameMode() }) { i -> p.escala = Tuner.ESCALAS[i].first; Tuner.salvar(pkg, p); abrirTelaAtual { telaPerfil(pkg, nome) } })

    val (_, hzMax) = Sensores.tela(this)
    val limites = Tuner.LIMITES.filter { it == 0 || it <= hzMax + 1 }
    blocoOpcao(col, "Limite de FPS", "Travar o FPS deixa a partida mais estável e esquenta menos.",
        seletor(limites.map { if (it == 0) "Sem limite" else "$it" }, limites.indexOf(p.fps), T.azul, limites.map { Tuner.suportaFps() }) { i -> p.fps = limites[i]; Tuner.salvar(pkg, p); abrirTelaAtual { telaPerfil(pkg, nome) } })

    val drivers = listOf("padrao", "angle", "jogo", "sistema")
    blocoOpcao(col, "Driver gráfico", "ANGLE roda OpenGL sobre Vulkan. Teste com o Medidor de FPS: ajuda alguns jogos e piora outros.",
        seletor(drivers.map { Tuner.nomeDriver(it) }, drivers.indexOf(p.driver), T.roxo, listOf(true, Tuner.temAngle(), Tuner.temDriverJogo(), Build.VERSION.SDK_INT >= 29)) { i -> p.driver = drivers[i]; Tuner.salvar(pkg, p); abrirTelaAtual { telaPerfil(pkg, nome) } })

    val resOps = listOf(0, 90, 80, 70)
    blocoOpcao(col, "Resolução da tela no Modo Jogo", "Funciona em qualquer celular: baixa a resolução da tela inteira só enquanto você joga e volta sozinha no fim.",
        seletor(listOf("Não mexer", "90%", "80%", "70%"), resOps.indexOf(p.resGlobal).coerceAtLeast(0), T.laranja) { i -> p.resGlobal = resOps[i]; Tuner.salvar(pkg, p); abrirTelaAtual { telaPerfil(pkg, nome) } })

    blocoOpcao(col, "Turbo no Modo Jogo", "Trava o Android no desempenho máximo durante a partida. Mais FPS estável, esquenta mais.",
        seletor(listOf("Desligado", "Ligado"), if (p.turbo) 1 else 0, T.laranja) { i -> p.turbo = i == 1; Tuner.salvar(pkg, p); abrirTelaAtual { telaPerfil(pkg, nome) } })

    // acoes
    val ac = Ui.horizontal(this)
    ac.addView(Ui.botao(this, "Aplicar", T.verde, true, R.drawable.ic_fps) { executar("Tuner") { Tuner.aplicar(it, pkg, nome, p) } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    ac.addView(Ui.espaco(this, 10, 1))
    ac.addView(Ui.botao(this, "Jogar", T.verde, false, R.drawable.ic_jogo) {
        Store.putStr("ultimo_jogo", pkg)
        executar("Modo Jogo") { con ->
            if (!Tuner.perfil(pkg).aplicado && Pro.ativo()) Tuner.aplicar(con, pkg, nome, p)
            ModoJogo.executar(con, pkg)
        }
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    col.addView(ac, Ui.linhaLp(this, 6, 10))
    col.addView(Ui.linhaMenu(this, R.drawable.ic_jogo, T.roxo, "Jogar com teclado", "Abre o jogo com o teclado virtual por cima", "", T.roxo) { executar("Teclado no jogo") { TecladoJogo.iniciar(it, pkg) } })
    val rs = Ui.texto(this, "Resetar para o padrão do Android", 13f, T.vermelho, Ui.medio)
    rs.gravity = Gravity.CENTER
    rs.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10))
    rs.isClickable = true
    rs.setOnClickListener { executar("Resetar") { Tuner.resetar(it, pkg, nome) } }
    col.addView(rs, Ui.linhaLp(this))
    redesenhar()
}
