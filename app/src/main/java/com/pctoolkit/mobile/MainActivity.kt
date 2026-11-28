package com.pctoolkit.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CountDownLatch

class Item(
    val titulo: String,
    val desc: String = "",
    val icone: Int = R.drawable.ic_seta,
    val cor: Int = T.verde,
    val selo: String = "",
    val corSelo: Int = T.verde,
    val sub: (() -> Menu)? = null,
    val acao: ((Console) -> Unit)? = null,
    val tela: ((MainActivity) -> Unit)? = null
)

class Menu(val titulo: String, val subtitulo: String, val icone: Int, val cor: Int, val itens: List<Item>)

/**
 * Tela de tarefa: as funcoes rodam numa thread e conversam com o usuario por dialogos
 * (Sim/Nao, listas, texto) de forma sequencial. O registro aparece como um relatorio.
 */
class Console(private val act: MainActivity, private val tv: TextView, private val scroll: ScrollView) {
    private val buf = SpannableStringBuilder()
    @Volatile var cancelado = false
    var tarefa = ""
    val ctx: Context get() = act

    private fun add(t: String, cor: Int, negrito: Boolean = false) {
        act.runOnUiThread {
            val ini = buf.length
            buf.append(t).append('\n')
            buf.setSpan(ForegroundColorSpan(cor), ini, buf.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (negrito) buf.setSpan(StyleSpan(Typeface.BOLD), ini, buf.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (buf.length > 200_000) buf.delete(0, buf.length - 150_000)
            tv.text = buf
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    fun linha(t: String = "", cor: Int = T.texto) = add(t, cor)
    fun titulo(t: String) { add("", T.texto); add(t, T.verde, true) }
    fun subtitulo(t: String) { add("", T.texto); add(t, T.azul, true) }
    fun ok(t: String) = add("✓  $t", T.verde)
    fun aviso(t: String) = add("!  $t", T.amarelo)
    fun erro(t: String) { add("✕  $t", T.vermelho); try { Relatorio.falha(tarefa.ifEmpty { "tela" }, t) } catch (e: Throwable) { } }
    fun info(t: String) = add("   $t", T.texto2)
    fun item(t: String, cor: Int = T.texto) = add("•  $t", cor)

    private fun <V> naUi(padrao: V, bloco: (cb: (V) -> Unit) -> Unit): V {
        if (cancelado) return padrao
        val latch = CountDownLatch(1)
        var r = padrao
        var feito = false
        act.runOnUiThread {
            try { bloco { v -> if (!feito) { feito = true; r = v; latch.countDown() } } } catch (e: Exception) { latch.countDown() }
        }
        latch.await()
        return r
    }

    /**
     * Confirmacao. No modo Automatico so pergunta o que e importante (apagar, desinstalar, reiniciar...);
     * o resto segue sozinho e fica registrado no relatorio.
     */
    fun ask(pergunta: String, sim: String = "Sim", nao: String = "Não", importante: Boolean = false): Boolean {
        if (!importante && Modo.auto() && !cancelado) { add("→  " + pergunta.lineSequence().first().removeSuffix("?") + ": " + sim.lowercase(), T.texto3); return true }
        return perguntar(pergunta, sim, nao)
    }

    private fun perguntar(pergunta: String, sim: String, nao: String): Boolean = naUi(false) { cb ->
        AlertDialog.Builder(act).setMessage(pergunta)
            .setPositiveButton(sim) { _, _ -> cb(true) }
            .setNegativeButton(nao) { _, _ -> cb(false) }
            .setOnCancelListener { cb(false) }.show()
    }

    fun escolher(titulo: String, opcoes: List<String>): Int = naUi(-1) { cb ->
        AlertDialog.Builder(act).setTitle(titulo)
            .setItems(opcoes.toTypedArray()) { _, w -> cb(w) }
            .setNegativeButton("Cancelar") { _, _ -> cb(-1) }
            .setOnCancelListener { cb(-1) }.show()
    }

    fun varios(titulo: String, opcoes: List<String>, marcados: Boolean = false, importante: Boolean = false): List<Int> {
        if (marcados && !importante && Modo.auto() && !cancelado && opcoes.isNotEmpty()) { add("→  $titulo: todos", T.texto3); return opcoes.indices.toList() }
        return escolherVarios(titulo, opcoes, marcados)
    }

    private fun escolherVarios(titulo: String, opcoes: List<String>, marcados: Boolean): List<Int> = naUi(emptyList()) { cb ->
        if (opcoes.isEmpty()) { cb(emptyList()); return@naUi }
        val sel = BooleanArray(opcoes.size) { marcados }
        AlertDialog.Builder(act).setTitle(titulo)
            .setMultiChoiceItems(opcoes.toTypedArray(), sel) { _, w, v -> sel[w] = v }
            .setPositiveButton("Confirmar") { _, _ -> cb(sel.indices.filter { sel[it] }) }
            .setNegativeButton("Cancelar") { _, _ -> cb(emptyList()) }
            .setOnCancelListener { cb(emptyList()) }.show()
    }

    fun texto(titulo: String, dica: String = "", padrao: String = ""): String? = naUi<String?>(null) { cb ->
        val et = EditText(act)
        et.setText(padrao); et.hint = dica
        et.setTextColor(T.texto); et.setHintTextColor(T.texto3)
        val box = FrameLayout(act)
        box.setPadding(Ui.dp(act, 22), Ui.dp(act, 8), Ui.dp(act, 22), 0)
        box.addView(et)
        AlertDialog.Builder(act).setTitle(titulo).setView(box)
            .setPositiveButton("OK") { _, _ -> cb(et.text.toString()) }
            .setNegativeButton("Cancelar") { _, _ -> cb(null) }
            .setOnCancelListener { cb(null) }.show()
    }

    fun numero(titulo: String, padrao: Int, min: Int, max: Int): Int {
        val t = texto(titulo, "$min a $max", padrao.toString()) ?: return padrao
        return (t.trim().toIntOrNull() ?: padrao).coerceIn(min, max)
    }

    fun escolherArquivo(): Uri? = act.pedirResultado(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))?.data
    fun criarArquivo(nome: String, tipo: String): Uri? = act.pedirResultado(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(tipo).putExtra(Intent.EXTRA_TITLE, nome))?.data
    fun escolherPasta(): Uri? = act.pedirResultado(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))?.data

    /** Abre uma tela do Android e espera o usuario voltar para o app. */
    fun abrirEEsperar(intent: Intent): Boolean = act.abrirEEsperar(intent)

    fun abrirConfig(acao: String, pacote: String? = null) { act.runOnUiThread { Links.abrir(act, acao, pacote) } }

    /** Garante uma permissao: explica, abre a tela certa e confere na volta. */
    fun garantir(p: Permissao): Boolean {
        if (p.ok) return true
        if (Modo.permissoesAuto()) {
            if (!Priv.pronto() && Priv.rodando()) Priv.pedir()
            if (Priv.pronto()) {
                Priv.autoConfigurar(true)
                val ok = Acesso.lista().firstOrNull { it.nome == p.nome }?.ok ?: false
                if (ok) { ok("${p.nome}: liberado automaticamente"); return true }
            } else if (!Store.bool("shizuku_oferecido", false)) {
                Store.putBool("shizuku_oferecido", true)
                if (perguntar("Quer que o app libere esta e as outras permissões sozinho?\n\nPara isso ele usa o Shizuku (gratuito, sem root e sem PC). Leva 1 minuto, só na primeira vez.", "Configurar", "Fazer manualmente") && Priv.ativar(this)) {
                    val ok = Acesso.lista().firstOrNull { it.nome == p.nome }?.ok ?: false
                    if (ok) return true
                }
            }
        }
        if (!ask("${p.nome}\n\n${p.desc}\n\nVou abrir a tela do Android para você permitir.", "Permitir", "Agora não")) return false
        if (p.runtime != null) act.pedirPermissaoRuntime(p.runtime)
        else if (p.pedir != null) abrirEEsperar(p.pedir)
        val ok = Acesso.lista().firstOrNull { it.nome == p.nome }?.ok ?: false
        if (ok) ok("${p.nome}: permitido") else aviso("${p.nome}: ainda não permitido")
        return ok
    }

    fun garantirNome(nome: String): Boolean {
        val p = Acesso.lista().firstOrNull { it.nome == nome } ?: return false
        return garantir(p)
    }

    fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) { } }
}

class MainActivity : Activity() {
    private val pilha = ArrayList<() -> Unit>()
    @Volatile private var tarefaRodando = false
    private var naTarefa = false
    private var resultadoLatch: CountDownLatch? = null
    private var resultado: Intent? = null
    private var voltaLatch: CountDownLatch? = null
    private var permLatch: CountDownLatch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Letra.carregar()
        window.decorView.setBackgroundColor(T.bg)
        pilha.add { telaInicio() }
        mostrar()
        Thread {
            if (Sh.suExiste()) Sh.temRoot()
            runOnUiThread { if (!naTarefa && pilha.size == 1) mostrar() }
        }.start()
        try { rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(aoShizuku) } catch (e: Throwable) { }
        if (!Modo.definido()) { boasVindas(); return }
        if (intent?.getBooleanExtra("teclado", false) == true) executar("Teclado no jogo") { TecladoJogo.iniciar(it) }
        if (intent?.getBooleanExtra("pro", false) == true) abrirTela { telaPro() }
        intent?.getStringExtra("cupom")?.takeIf { it.isNotBlank() }?.let { Store.putStr("cupom_pendente", it) }
        when (intent?.getStringExtra("acao")) {
            "preparar" -> executar("Preparar e jogar") { Preparar.executar(it, null) }
            "sensibilidade" -> executar("Calculadora de sensibilidade") { Sensibilidade.executar(it) }
            "modojogo" -> executar("Modo Jogo") { ModoJogo.executar(it) }
            "mira" -> executar("Mira na tela") { MiraTela.executar(it) }
            "raiox" -> executar("Raio-X de desempenho") { RaioX.executar(it) }
            "teclado" -> executar("Teclado no jogo") { TecladoJogo.iniciar(it) }
            "campeonato" -> executar("Modo campeonato") { ModoJogo.executar(it, null, true) }
            "temperatura" -> abrirMenu { Menus.temperatura() }
            "memoria" -> executar("Liberar memória") { Fps.liberarRam(it) }
            "relatorio" -> executar("Histórico do Modo Jogo") { ModoJogo.historico(it) }
            "atualizar" -> executar("Atualizar o app") { Atualizador.procurar(it) }
            "pro" -> abrirTela { telaPro() }
        }
        Dono.agendar(this)
        Thread {
            Relatorio.enviar()
            Integridade.conferir()
            try { Mensagens.conferir(applicationContext) } catch (e: Throwable) { }
            if (Promo.atualizar()) runOnUiThread { atualizarInicio() }
            if (Dono.ligado()) Dono.conferir(this)
        }.start()
        Atualizador.agendar(this)
        Automatico.agendar(this)
        Thread { Conquistas.conferir() }.start()
        if (Vendedor.automatico()) Thread {
            val ult = Store.str("aviso_em").toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - ult > 30 * 60_000L) {
                Store.putStr("aviso_em", System.currentTimeMillis().toString())
                val a = Compra.aviso()
                Store.putStr("aviso_id", a?.first?.toString() ?: ""); Store.putStr("aviso_texto", a?.second ?: ""); Store.putStr("aviso_link", a?.third ?: "")
                runOnUiThread { atualizarInicio() }
            }
        }.start()
        perguntarMotivo()
        AvisoPro.agendar(this)
        if (Vendedor.automatico() && Store.str("lic_pedido").isNotEmpty()) Thread { if (Compra.conferirPendente()) runOnUiThread { if (!naTarefa) mostrar() } }.start()
        else if (Vendedor.automatico()) Thread { if (Compra.restaurar()) runOnUiThread { if (!naTarefa) mostrar() }; Compra.registrar() }.start()
        Atualizador.aoAbrir(this)
        Atualizador.novidadesSeAtualizou(this)
        if (!GameService.rodando && Sessao.pendente() && Store.str("sessao_cfg").isNotEmpty()) {
            // o Android encerrou o Modo Jogo no meio da partida: retoma a sessao de onde parou
            try { startForegroundService(android.content.Intent(this, GameService::class.java)) } catch (e: Exception) { Sessao.restaurar(this) }
        } else if (!GameService.rodando && Sessao.pendente()) {
            executar("Recuperando o Modo Jogo") { con ->
                con.aviso("Uma sessão do Modo Jogo não terminou direito. Restaurando o normal...")
                Sessao.restaurar(this)
                con.ok("Tudo restaurado")
            }
        }
    }

    private val aoShizuku = rikka.shizuku.Shizuku.OnBinderReceivedListener {
        Thread {
            if (Priv.permitido()) Priv.autoConfigurar()
            else if (Modo.definido() && Modo.permissoesAuto() && !Store.bool("shizuku_pedido", false)) { Store.putBool("shizuku_pedido", true); if (Priv.pedir()) Priv.autoConfigurar(true) }
            runOnUiThread { if (!naTarefa) mostrar() }
        }.start()
    }

    override fun onDestroy() {
        try { rikka.shizuku.Shizuku.removeBinderReceivedListener(aoShizuku) } catch (e: Throwable) { }
        super.onDestroy()
    }

    private fun boasVindas() {
        executar("Bem-vindo ao SerpyraToolkit") { con ->
            con.linha("Duas escolhas rápidas e o app passa a trabalhar sozinho. Dá para mudar depois em Acesso e permissões.", T.texto2)
            val p = con.escolher("Como liberar as permissões do app?", listOf(
                "Automático (recomendado): o app libera tudo sozinho pelo Shizuku",
                "Pedir uma por uma: abro as telas do Android para você permitir"))
            Modo.definirPermissoes(p != 1)
            val c = con.escolher("Como o app deve confirmar as ações?", listOf(
                "Automático (recomendado): faz sozinho e só pergunta antes de apagar, desinstalar ou reiniciar",
                "Perguntar antes de cada passo"))
            Modo.definir(c != 1)
            con.ok("Permissões: " + (if (Modo.permissoesAuto()) "automático" else "uma por uma") + " · Confirmações: " + Modo.nome())
            if (Modo.permissoesAuto()) {
                Store.putBool("shizuku_oferecido", true)
                if (Priv.ativar(con)) con.ok("Pronto! O app já consegue fazer tudo sozinho.")
                else con.info("Sem o Shizuku agora, o app pede as permissões quando precisar. Ative depois em Acesso e permissões.")
            }
            con.linha("")
            con.subtitulo("Primeiro Raio-X do seu celular")
            RaioX.executar(con)
            if (!Pro.ativo() && Vendedor.automatico() && !Store.bool("teste_usado", false) && Licenca.diaValidade() <= 0) {
                con.linha("")
                con.linha("Quer ver o celular no máximo? Teste o Pro grátis por ${Vendedor.TESTE_TXT}: botões no jogo, Tuner, Modo Frio e Modo Jogo sem limite.", T.amarelo)
                if (con.ask("Testar o Pro grátis agora?", "Testar grátis", "Depois", importante = true)) {
                    val (ok, m) = Compra.teste()
                    if (ok) con.ok(m) else con.aviso(m)
                }
            }
            con.linha("")
            con.ok("Tudo pronto! Toque em Concluir para começar.")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getStringExtra("acao") == "atualizar" && !naTarefa) executar("Atualizar o app") { Atualizador.procurar(it) }
        intent?.getStringExtra("cupom")?.takeIf { it.isNotBlank() }?.let { Store.putStr("cupom_pendente", it) }
        if (intent?.getStringExtra("acao") == "pro" && !naTarefa) abrirTela { telaPro() }
        if (naTarefa) return
        when (intent?.getStringExtra("acao")) {
            "modojogo" -> executar("Modo Jogo") { ModoJogo.executar(it) }
            "preparar" -> executar("Preparar e jogar") { Preparar.executar(it, null) }
            "raiox" -> executar("Raio-X de desempenho") { RaioX.executar(it) }
            "relatorio" -> executar("Histórico do Modo Jogo") { ModoJogo.historico(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        Atualizador.visivel = false
    }

    override fun onStop() {
        super.onStop()
        // saiu do app com versao nova esperando: atualiza sozinho daqui a pouco, sem atrapalhar
        if (!isChangingConfigurations) Atualizador.aoSair(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        Atualizador.visivel = true
        Atualizador.tela = java.lang.ref.WeakReference(this)
        if (Modo.definido()) Atualizador.aoAbrir(this)
        voltaLatch?.countDown()
        if (!naTarefa && pilha.isNotEmpty()) mostrar()
    }

    // ---------------- navegacao ----------------
    fun mostrar() {
        naTarefa = false
        val render: () -> Unit = pilha[pilha.size - 1]
        render()
    }

    /** Quando o Pro comprado vence sem renovar: uma pergunta rapida, uma vez por vencimento. */
    private fun perguntarMotivo() {
        val d = Licenca.diaValidade()
        if (!Vendedor.automatico() || d <= 0 || Licenca.ativo() || !Store.bool("lic_comprou", false) || Store.int("pesquisa_dia", 0) == d) return
        Store.putInt("pesquisa_dia", d)
        val motivos = arrayOf("Achei caro", "Não usei muito", "Não funcionou no meu celular", "Difícil de usar", "Só queria testar", "Outro motivo")
        AlertDialog.Builder(this).setTitle("Seu Pro venceu. Pode contar por que não renovou?")
            .setItems(motivos) { _, i -> Thread { Compra.pesquisa(i) }.start(); Toast.makeText(this, "Obrigado! Isso ajuda a melhorar o app.", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("Agora não", null).show()
    }

    /** Redesenha a tela inicial se ela estiver aberta (ex.: chegou aviso de atualizacao). */
    fun atualizarInicio() { if (!naTarefa && pilha.size == 1 && !isFinishing) mostrar() }

    fun abrirMenu(gerador: () -> Menu) { pilha.add { telaMenu(gerador()) }; mostrar() }
    fun abrirTela(render: () -> Unit) { pilha.add(render); mostrar() }
    fun abrirTelaAtual(render: () -> Unit) { if (pilha.isNotEmpty()) pilha[pilha.size - 1] = render else pilha.add(render); mostrar() }

    private fun voltar() {
        if (naTarefa) { mostrar(); return }
        if (pilha.size > 1) { pilha.removeAt(pilha.size - 1); mostrar() } else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tarefaRodando) {
            AlertDialog.Builder(this).setMessage("Uma tarefa ainda está em andamento. Toque em Parar ou espere ela terminar.").setPositiveButton("OK", null).show()
            return
        }
        voltar()
    }

    fun raiz(conteudo: View, rolar: Boolean = true): View {
        val f = FrameLayout(this)
        f.setBackgroundColor(T.bg)
        if (rolar) {
            val sv = ScrollView(this)
            sv.isFillViewport = true
            sv.isVerticalScrollBarEnabled = false
            sv.addView(conteudo)
            f.addView(sv)
        } else f.addView(conteudo)
        Ui.insets(f)
        return f
    }

    fun barraTopo(titulo: String, sub: String, podeVoltar: Boolean = true): View {
        val l = Ui.horizontal(this)
        l.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 14))
        if (podeVoltar) {
            val b = FrameLayout(this)
            b.background = Ui.clicavel(this, Ui.forma(this, T.surface2, 50, T.outline), 50)
            val iv = Ui.icone(this, R.drawable.ic_voltar, T.texto, 22)
            b.addView(iv, FrameLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22), Gravity.CENTER))
            b.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42))
            b.isClickable = true
            b.setOnClickListener { onBackPressedCompat() }
            l.addView(b)
            l.addView(Ui.espaco(this, 14, 1))
        }
        val col = Ui.vertical(this)
        col.addView(Ui.texto(this, titulo, 21f, T.texto, Ui.negrito))
        if (sub.isNotEmpty()) col.addView(Ui.texto(this, sub, 13f, T.texto2))
        l.addView(col, Ui.peso())
        return l
    }

    private fun onBackPressedCompat() {
        if (tarefaRodando) return
        voltar()
    }

    fun coluna(): LinearLayout {
        val c = Ui.vertical(this)
        c.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 28))
        return c
    }

    // ---------------- tela inicial ----------------
    private fun cartaoStat(rotulo: String, valor: String, sub: String, frac: Float, cor: Int, onClick: () -> Unit): View {
        val c = Ui.vertical(this)
        c.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14))
        c.background = Ui.clicavel(this, Ui.forma(this, T.surface, 18, T.outline), 18)
        val r = Ui.texto(this, rotulo.uppercase(), 10.5f, T.texto3, Ui.medio)
        r.letterSpacing = 0.1f
        c.addView(r)
        val v = Ui.texto(this, valor, 19f, T.texto, Ui.negrito)
        v.setPadding(0, Ui.dp(this, 6), 0, 0)
        v.maxLines = 1
        c.addView(v)
        val s = Ui.texto(this, sub, 11.5f, T.texto2)
        s.maxLines = 1
        c.addView(s)
        val b = Barra(this)
        b.frac = frac; b.cor = cor
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6))
        lp.topMargin = Ui.dp(this, 10)
        c.addView(b, lp)
        c.isClickable = true
        c.setOnClickListener { onClick() }
        return c
    }

    private fun telaInicio() {
        val col = coluna()
        // cabecalho
        val cab = Ui.horizontal(this)
        val tcol = Ui.vertical(this)
        tcol.addView(Ui.titulo1(this, "SerpyraToolkit"))
        tcol.addView(Ui.texto(this, "Versão ${BuildConfig.VERSION_NAME}  ·  desempenho, jogos e segurança", 13f, T.texto2))
        cab.addView(tcol, Ui.peso())
        val nivel = Acesso.nivel()
        val chip = Ui.chip(this, "Acesso: $nivel", if (nivel == "Padrão") T.azul else T.verde)
        chip.isClickable = true
        chip.setOnClickListener { abrirTela { telaPermissoes() } }
        cab.addView(chip)
        col.addView(cab)
        col.addView(Ui.espaco(this, 1, 14))

        // faixa de promocao (o dono muda pelo painel)
        Promo.faixa(this)?.let { col.addView(it, Ui.linhaLp(this, 0, 14)) }

        // aviso fixo de atualizacao
        Atualizador.pendente()?.let { up ->
            val av = Ui.horizontal(this)
            av.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12))
            av.background = Ui.clicavel(this, Ui.forma(this, Ui.alpha(T.azul, 0x26), 18, T.azul), 18)
            val tx = Ui.vertical(this)
            tx.addView(Ui.texto(this, "Atualização disponível: versão ${up.nome}", 14.5f, T.texto, Ui.negrito))
            val sozinho = Atualizador.automatica() && Store.bool("upd_sozinho_ok", false)
            tx.addView(Ui.texto(this, if (sozinho) "Instala sozinha quando você sair do app, sem perder nada. Toque para atualizar agora."
                else up.notas.lines().firstOrNull { it.isNotBlank() }?.removePrefix("- ") ?: "Toque para ver as novidades", 12f, T.texto2))
            av.addView(tx, Ui.peso())
            av.addView(Ui.chip(this, if (sozinho) "Agora" else "Atualizar", T.azul))
            av.isClickable = true
            av.setOnClickListener { Atualizador.oferecer(this, up) }
            col.addView(av, Ui.linhaLp(this, 0, 14))
        }

        // aviso do dono (promocao, novidade)
        val avTxt = Store.str("aviso_texto"); val avId = Store.str("aviso_id")
        if (avTxt.isNotEmpty() && avId != Store.str("aviso_fechado")) {
            val av = Ui.horizontal(this)
            av.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12))
            av.background = Ui.clicavel(this, Ui.forma(this, Ui.alpha(T.amarelo, 0x22), 18, T.amarelo), 18)
            av.addView(Ui.texto(this, "📣  $avTxt", 13.5f, T.texto, Ui.medio), Ui.peso())
            val x = Ui.texto(this, "  ✕  ", 16f, T.texto2, Ui.negrito)
            x.setOnClickListener { Store.putStr("aviso_fechado", avId); mostrar() }
            av.addView(x)
            val link = Store.str("aviso_link")
            av.isClickable = true
            av.setOnClickListener { if (link.startsWith("https://")) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } catch (e: Exception) { } else abrirTela { telaPro() } }
            col.addView(av, Ui.linhaLp(this, 0, 14))
        }

        // copia modificada: avisa e manda baixar a original
        if (Integridade.modificado()) {
            val av = Ui.vertical(this)
            av.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12))
            av.background = Ui.clicavel(this, Ui.forma(this, Ui.alpha(T.vermelho, 0x22), 18, T.vermelho), 18)
            av.addView(Ui.texto(this, "Esta cópia do app foi modificada", 14.5f, T.texto, Ui.negrito))
            av.addView(Ui.texto(this, "Cópias alteradas podem ter vírus e não liberam o Pro. Toque para baixar a original, grátis, em serpyratoolkit.pages.dev.", 12.5f, T.texto2))
            av.isClickable = true
            av.setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://serpyratoolkit.pages.dev"))) } catch (e: Exception) { } }
            col.addView(av, Ui.linhaLp(this, 0, 14))
        }

        // aba SHIZUKU (sempre visivel)
        col.addView(TutorialShizuku.cartao(this), Ui.linhaLp(this, 0, 14))

        // destaque: nota
        val nota = Store.int("nota_fps", -1)
        val hero = Ui.horizontal(this)
        hero.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18))
        hero.background = Ui.clicavel(this, Ui.gradiente(this, 0xFF0F2A22.toInt(), 0xFF101C30.toInt(), 24), 24)
        val anel = Anel(this)
        anel.valor = nota
        anel.legenda = "de 100"
        anel.cor = if (nota < 0) T.texto3 else if (nota < 60) T.vermelho else if (nota < 85) T.amarelo else T.verde
        hero.addView(anel, LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 92)))
        hero.addView(Ui.espaco(this, 16, 1))
        val hc = Ui.vertical(this)
        hc.addView(Ui.texto(this, "Nota de desempenho", 12.5f, T.texto2, Ui.medio))
        val estado = when { nota < 0 -> "Ainda não avaliado"; nota >= 85 -> "Excelente"; nota >= 60 -> "Dá para melhorar"; else -> "Precisa de atenção" }
        hc.addView(Ui.texto(this, estado, 19f, T.texto, Ui.negrito))
        val d = Ui.texto(this, "O Raio-X confere tudo que derruba o FPS e corrige com um toque.", 12.5f, T.texto2)
        d.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10))
        hc.addView(d)
        val cta = Ui.chip(this, if (nota < 0) "Fazer o Raio-X  ›" else "Refazer o Raio-X  ›", T.verde)
        if (nota >= 0) {
            val linhaCta = Ui.horizontal(this)
            linhaCta.addView(cta)
            linhaCta.addView(Ui.espaco(this, 8, 1))
            val comp = Ui.chip(this, "Compartilhar", T.azul)
            comp.isClickable = true
            comp.setOnClickListener { Cartao.compartilhar(this, nota) }
            linhaCta.addView(comp)
            hc.addView(linhaCta)
            val mq = Store.int("nota_melhor", -1)
            if (mq >= 0) { val t = Ui.texto(this, "Melhor que $mq% " + if (Store.bool("nota_do_modelo", false)) "dos celulares iguais ao seu" else "dos celulares no app", 12f, T.verde); t.setPadding(0, Ui.dp(this, 8), 0, 0); hc.addView(t) }
        } else hc.addView(cta)
        hero.addView(hc, Ui.peso())
        hero.isClickable = true
        hero.setOnClickListener { executar("Raio-X de desempenho") { RaioX.executar(it) } }
        col.addView(hero)

        // numeros
        col.addView(Ui.secao(this, "Agora"))
        val stats = Ui.horizontal(this)
        val (livre, total) = Sensores.armazenamento()
        val usado = if (total > 0) (total - livre).toFloat() / total else 0f
        val b = Sensores.bateria(this)
        val m = Sensores.memoria(this)
        val usoRam = if (m.totalMem > 0) (m.totalMem - m.availMem).toFloat() / m.totalMem else 0f
        val corBat = if (b.tempC >= 42) T.vermelho else if (b.tempC >= 38) T.amarelo else T.verde
        val gap = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        stats.addView(cartaoStat("Armazen.", fmtTam(livre), "livres de " + fmtTam(total), usado, if (usado > 0.9f) T.vermelho else T.azul) { abrirMenu { Menus.limpeza() } }, gap)
        stats.addView(Ui.espaco(this, 10, 1))
        stats.addView(cartaoStat("Bateria", f1(b.tempC) + " °C", "${b.nivel}%" + if (b.carregando) " · carregando" else "", b.nivel / 100f, corBat) { abrirMenu { Menus.temperatura() } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stats.addView(Ui.espaco(this, 10, 1))
        stats.addView(cartaoStat("Memória", fmtTam(m.availMem), "livres de " + fmtTam(m.totalMem), usoRam, if (usoRam > 0.85f) T.amarelo else T.roxo) { executar("Liberar memória") { Fps.liberarRam(it) } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(stats)

        // meus jogos
        CentralJogos.faixa(this)?.let { fx ->
            col.addView(Ui.secao(this, "Meus jogos"))
            col.addView(fx)
        }

        // acoes rapidas
        col.addView(Ui.secao(this, "Ações rápidas"))
        val ac = Ui.horizontal(this)
        ac.addView(Ui.botao(this, "Modo Jogo", T.verde, true, R.drawable.ic_jogo) { executar("Modo Jogo") { ModoJogo.executar(it) } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ac.addView(Ui.espaco(this, 10, 1))
        ac.addView(Ui.botao(this, "Tuner", T.verde, false, R.drawable.ic_fps) { abrirTela { telaTuner() } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(ac)
        col.addView(Ui.linhaMenu(this, R.drawable.ic_jogo, T.verde, "Preparar e jogar", "Memória, limpeza e Modo Jogo no último jogo, com um toque", "", T.verde) { executar("Preparar e jogar") { Preparar.executar(it, null) } }, Ui.linhaLp(this, 10, 0))
        col.addView(Ui.linhaMenu(this, R.drawable.ic_assistente, T.verde, "Assistente", "Raio-X, limpeza, memória, segurança e rede em sequência", "", T.verde) { executar("Assistente") { Menus.assistente(it) } }, Ui.linhaLp(this, 10, 0))

        // assinatura
        if (!Pro.ativo()) col.addView(Ui.linhaMenu(this, R.drawable.ic_chave, T.amarelo, "SerpyraToolkit Pro", "Botões no jogo, Tuner, mira e Modo Frio · teste grátis", "Testar", T.amarelo) { abrirTela { telaPro() } }, Ui.linhaLp(this, 10, 0))
        else if (Licenca.diasRestantes() <= 3) col.addView(Ui.linhaMenu(this, R.drawable.ic_chave, T.amarelo, "Seu Pro vence em ${Licenca.diasRestantes()} dia(s)", "Renove para não perder as funções", "Renovar", T.amarelo) { abrirTela { telaPro() } }, Ui.linhaLp(this, 10, 0))

        // ferramentas
        col.addView(Ui.secao(this, "Ferramentas"))
        val cats = Menus.categorias()
        var i = 0
        while (i < cats.size) {
            val linha = Ui.horizontal(this)
            linha.gravity = Gravity.TOP
            for (k in 0..1) {
                if (k == 1) linha.addView(Ui.espaco(this, 10, 1))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (i + k < cats.size) {
                    val it = cats[i + k]
                    linha.addView(Ui.bloco(this, it.icone, it.cor, it.titulo, it.desc) { abrirItem(it) }, lp)
                } else linha.addView(View(this), lp)
            }
            col.addView(linha, Ui.linhaLp(this, 0, 10))
            i += 2
        }
        val rod = Ui.texto(this, "Tudo o que o app muda pode ser desfeito.", 12f, T.texto3)
        rod.gravity = Gravity.CENTER
        rod.setPadding(0, Ui.dp(this, 14), 0, 0)
        col.addView(rod, Ui.linhaLp(this))
        setContentView(raiz(col))
    }

    fun abrirItem(it: Item) {
        val sub = it.sub
        val acao = it.acao
        val tela = it.tela
        when {
            sub != null -> abrirMenu(sub)
            acao != null -> executar(it.titulo, acao)
            tela != null -> tela(this)
        }
    }

    // ---------------- telas de menu ----------------
    private fun telaMenu(m: Menu) {
        val col = coluna()
        col.addView(barraTopo(m.titulo, m.subtitulo))
        for (it in m.itens) {
            col.addView(Ui.linhaMenu(this, it.icone, it.cor, it.titulo, it.desc, it.selo, it.corSelo) { abrirItem(it) })
        }
        setContentView(raiz(col))
    }

    // ---------------- permissoes / acesso ----------------
    fun telaPermissoes() {
        val col = coluna()
        col.addView(barraTopo("Acesso e permissões", "Nível atual: " + Acesso.nivel()))

        // modo automatico
        val sz = Ui.vertical(this)
        sz.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16))
        sz.background = Ui.gradiente(this, 0xFF0F2A22.toInt(), 0xFF101C30.toInt(), 20)
        val szT = Ui.horizontal(this)
        szT.addView(Ui.texto(this, "Modo automático (Shizuku)", 15.5f, T.texto, Ui.negrito), Ui.peso())
        val est = Priv.estado(this)
        szT.addView(Ui.chip(this, est, if (est == "Ativo") T.verde else T.amarelo))
        sz.addView(szT)
        val szD = Ui.texto(this, if (est == "Ativo") "O app libera as próprias permissões e aplica os ajustes sozinho, sem root e sem PC." else "Com o Shizuku (gratuito) o app libera todas as permissões e faz os ajustes sozinho, sem root e sem PC. Configura uma vez em 1 minuto.", 12.5f, T.texto2)
        szD.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12))
        sz.addView(szD)
        sz.addView(Ui.botao(this, if (est == "Ativo") "Liberar tudo de novo" else "Ativar modo automático", T.verde, true) { executar("Modo automático") { Priv.ativar(it) } })
        sz.addView(Ui.espaco(this, 1, 8))
        sz.addView(Ui.botao(this, "Vídeo e passo a passo do Shizuku", T.azul, false) { abrirTela { TutorialShizuku.tela(this) } })
        col.addView(sz, Ui.linhaLp(this, 0, 6))

        col.addView(Ui.secao(this, "Preferências"))
        col.addView(Ui.linhaMenu(this, R.drawable.ic_chave, T.verde, "Permissões", if (Modo.permissoesAuto()) "Automático: libera sozinho quando possível" else "Uma por uma pelas telas do Android", "Trocar", T.azul) {
            Modo.definirPermissoes(!Modo.permissoesAuto()); mostrar()
        })
        col.addView(Ui.linhaMenu(this, R.drawable.ic_assistente, T.verde, "Confirmações", if (Modo.auto()) "Automático: só pergunta antes de apagar, desinstalar ou reiniciar" else "Pergunta antes de cada passo", "Trocar", T.azul) {
            Modo.definir(!Modo.auto()); mostrar()
        })
        col.addView(Ui.secao(this, "Permissões do Android"))
        for (p in Acesso.lista()) {
            val c = Ui.horizontal(this)
            c.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14))
            c.background = Ui.forma(this, T.surface, 18, T.outline)
            val tc = Ui.vertical(this)
            tc.addView(Ui.texto(this, p.nome, 15f, T.texto, Ui.medio))
            val d = Ui.texto(this, p.desc, 12.5f, T.texto2); d.setPadding(0, Ui.dp(this, 2), 0, 0)
            tc.addView(d)
            c.addView(tc, Ui.peso())
            c.addView(Ui.espaco(this, 10, 1))
            if (p.ok) c.addView(Ui.chip(this, "Permitido", T.verde))
            else {
                val b = Ui.chip(this, "Permitir", T.azul)
                b.isClickable = true
                b.setOnClickListener {
                    if (Priv.pronto()) { Thread { Priv.autoConfigurar(true); runOnUiThread { mostrar() } }.start() }
                    else if (p.runtime != null) requestPermissions(arrayOf(p.runtime), 5)
                    else if (p.pedir != null) try { startActivity(p.pedir) } catch (e: Exception) { }
                }
                c.addView(b)
            }
            col.addView(c, Ui.linhaLp(this))
        }

        col.addView(Ui.secao(this, "Alternativa: pelo PC (opcional)"))
        val adb = Ui.vertical(this)
        adb.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16))
        adb.background = Ui.forma(this, T.surface, 18, T.outline)
        val st = if (Acesso.adb()) Ui.chip(this, "Ativo", T.verde) else Ui.chip(this, "Desligado", T.texto3)
        val topo = Ui.horizontal(this)
        topo.addView(Ui.texto(this, "Permissão avançada (ADB)", 15f, T.texto, Ui.medio), Ui.peso())
        topo.addView(st)
        adb.addView(topo)
        val ex = Ui.texto(this, "Libera os ajustes automáticos (animações, taxa da tela, DNS, Wi-Fi) sem root. É feito uma única vez, com o celular no cabo USB e o PC:\n\n1. Ative a Depuração USB (Opções do desenvolvedor).\n2. No PC, instale o Android Platform Tools.\n3. Rode o comando abaixo e reabra o app.", 12.5f, T.texto2)
        ex.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10))
        ex.setLineSpacing(0f, 1.15f)
        adb.addView(ex)
        val cmd = Ui.texto(this, Acesso.comandoAdb(), 12f, T.ciano, Typeface.MONOSPACE)
        cmd.setTextIsSelectable(true)
        cmd.background = Ui.forma(this, T.bg, 12, T.outline)
        cmd.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10))
        adb.addView(cmd)
        val copiar = Ui.botao(this, "Copiar comando", T.verde, false) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("adb", Acesso.comandoAdb()))
            AlertDialog.Builder(this).setMessage("Comando copiado.").setPositiveButton("OK", null).show()
        }
        adb.addView(copiar, Ui.linhaLp(this, 12, 0))
        col.addView(adb, Ui.linhaLp(this))

        col.addView(Ui.secao(this, "Root (opcional)"))
        val rc = Ui.vertical(this)
        rc.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16))
        rc.background = Ui.forma(this, T.surface, 18, T.outline)
        val rt = Ui.horizontal(this)
        rt.addView(Ui.texto(this, "Root", 15f, T.texto, Ui.medio), Ui.peso())
        rt.addView(if (Sh.rootConhecido()) Ui.chip(this, "Liberado", T.verde) else Ui.chip(this, "Não disponível", T.texto3))
        rc.addView(rt)
        val rd = Ui.texto(this, "O app NÃO precisa de root. Se o seu celular tiver (Magisk, KernelSU ou APatch), ele libera extras: medidor de FPS real, perfis de CPU e limpeza do cache de cada app.\n\n" + Sh.detalheRoot.ifEmpty { "Ainda não testado." }, 12.5f, T.texto2)
        rd.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10))
        rc.addView(rd)
        rc.addView(Ui.botao(this, "Testar root agora", T.verde, false) {
            Thread { Sh.verificarRoot(); runOnUiThread { mostrar() } }.start()
        }, Ui.linhaLp(this, 0, 0))
        col.addView(rc, Ui.linhaLp(this))
        setContentView(raiz(col))
    }

    // ---------------- tela de tarefa ----------------
    fun executar(titulo: String, acao: (Console) -> Unit) {
        naTarefa = true
        val col = Ui.vertical(this)
        col.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 14))
        val topo = Ui.horizontal(this)
        topo.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12))
        val tt = Ui.vertical(this)
        tt.addView(Ui.texto(this, titulo, 21f, T.texto, Ui.negrito))
        val status = Ui.chip(this, "Em andamento", T.azul)
        val stBox = Ui.horizontal(this)
        stBox.setPadding(0, Ui.dp(this, 6), 0, 0)
        stBox.addView(status)
        tt.addView(stBox)
        topo.addView(tt, Ui.peso())
        Ajuda.de(titulo)?.let { txt ->
            val aj = Ui.chip(this, "  ?  ", T.azul)
            aj.isClickable = true
            aj.setOnClickListener { AlertDialog.Builder(this).setTitle(titulo).setMessage(txt).setPositiveButton("Entendi", null).show() }
            topo.addView(aj)
        }
        col.addView(topo)
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pb.isIndeterminate = true
        pb.indeterminateTintList = ColorStateList.valueOf(T.verde)
        col.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 4)))
        col.addView(Ui.espaco(this, 1, 10))
        val card = FrameLayout(this)
        card.background = Ui.forma(this, T.surface, 20, T.outline)
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12))
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val tv = TextView(this)
        tv.setTextColor(T.texto)
        tv.textSize = 13.5f * Letra.escala
        tv.typeface = Ui.normal
        tv.setLineSpacing(0f, 1.18f)
        tv.setTextIsSelectable(true)
        sv.addView(tv)
        card.addView(sv)
        col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(Ui.espaco(this, 1, 12))
        val con = Console(this, tv, sv)
        con.tarefa = titulo
        val barra = FrameLayout(this)
        val parar = Ui.botao(this, "Parar", T.vermelho, false) {
            con.cancelado = true
        }
        barra.addView(parar)
        col.addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(raiz(col, false))
        tarefaRodando = true
        Thread {
            var erro = false
            try { acao(con) } catch (e: Throwable) { erro = true; Relatorio.falha(titulo, e); con.linha("✕  Algo deu errado aqui. Já mandamos o aviso para corrigir; tente de novo.", T.vermelho); Log2.registrar("erro em $titulo: $e") }
            runOnUiThread {
                tarefaRodando = false
                pb.isIndeterminate = false
                pb.progress = 100
                pb.progressTintList = ColorStateList.valueOf(if (erro) T.vermelho else T.verde)
                status.text = if (con.cancelado) "Interrompido" else if (erro) "Com erro" else "Concluído"
                status.setTextColor(if (erro) T.vermelho else if (con.cancelado) T.amarelo else T.verde)
                status.background = Ui.forma(this, Ui.alpha(if (erro) T.vermelho else if (con.cancelado) T.amarelo else T.verde, 0x24), 50)
                barra.removeAllViews()
                barra.addView(Ui.botao(this, "Concluir", T.verde, true) { mostrar() })
            }
        }.start()
    }

    // ---------------- ponte com telas do Android ----------------
    fun pedirResultado(i: Intent): Intent? {
        val l = CountDownLatch(1)
        resultadoLatch = l; resultado = null
        runOnUiThread {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(i, 77)
            } catch (e: Exception) { l.countDown() }
        }
        l.await()
        return resultado
    }

    fun abrirEEsperar(i: Intent): Boolean {
        val l = CountDownLatch(1)
        voltaLatch = l
        var ok = true
        runOnUiThread { try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { ok = false; l.countDown() } }
        Thread.sleep(800)
        l.await()
        voltaLatch = null
        return ok
    }

    fun pedirPermissaoRuntime(p: String) {
        val l = CountDownLatch(1)
        permLatch = l
        runOnUiThread { requestPermissions(arrayOf(p), 9) }
        l.await()
        permLatch = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permLatch?.countDown()
        if (!naTarefa) mostrar()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77) {
            resultado = if (resultCode == RESULT_OK) (data ?: Intent()) else null
            resultadoLatch?.countDown()
        }
    }

    fun temPermissao(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}
