package com.pctoolkit.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout

/** "Meus jogos": cada jogo com tudo num lugar so (Modo Jogo, botoes, Tuner, mira). */
object CentralJogos {
    /** Jogos para a tela inicial: os mais usados primeiro (os que ja tem botoes/perfil ou foram abertos pelo Modo Jogo). */
    private var cache: List<Jogo>? = null
    private var cacheEm = 0L
    fun lista(ctx: Context): List<Jogo> {
        val agora = System.currentTimeMillis()
        val todos = cache?.takeIf { agora - cacheEm < 60_000 } ?: Jogos.instalados(ctx).also { cache = it; cacheEm = agora }
        val uso = Store.str("jogos_uso").split(",").filter { it.isNotBlank() }
        return todos.sortedWith(compareBy<Jogo>({ j -> val i = uso.indexOf(j.pkg); if (i < 0) 999 else i }, { !(PerfisControle.tem(it.pkg) || Tuner.perfil(it.pkg).aplicado) }, { it.nome.lowercase() }))
    }

    fun usado(pkg: String) {
        val uso = Store.str("jogos_uso").split(",").filter { it.isNotBlank() && it != pkg }
        Store.putStr("jogos_uso", (listOf(pkg) + uso).take(20).joinToString(","))
    }

    fun menu(act: MainActivity, jogo: Jogo) {
        usado(jogo.pkg)
        val tem = PerfisControle.tem(jogo.pkg)
        val perfil = Tuner.perfil(jogo.pkg)
        val pro = if (Pro.ativo()) "" else "  (Pro)"
        val ops = arrayOf(
            "▶  Jogar com o Modo Jogo (recomendado)",
            (if (tem) "⌨  Jogar com os meus botões na tela" else "⌨  Criar botões na tela") + pro,
            "⚙  Tuner: resolução, FPS e desempenho" + pro,
            "⊕  Mira na tela" + pro,
            "↗  Só abrir o jogo",
            "🔋  Bateria sem restrição e outros ajustes"
        )
        val sub = listOfNotNull(
            if (tem) "${PerfisControle.carregar(jogo.pkg).size} botões salvos" else null,
            if (perfil.aplicado) "Tuner: " + perfil.resumo() else null
        ).joinToString(" · ")
        android.app.AlertDialog.Builder(act).setTitle(jogo.nome + if (sub.isNotEmpty()) "\n$sub" else "")
            .setItems(ops) { _, i ->
                when (i) {
                    0 -> act.executar("Modo Jogo") { ModoJogo.executar(it, jogo.pkg) }
                    1 -> act.executar("Teclado e controles") { TecladoJogo.iniciar(it, jogo.pkg) }
                    2 -> act.abrirTela { act.telaPerfil(jogo.pkg, jogo.nome) }
                    3 -> act.executar("Mira na tela") { con -> MiraTela.executar(con); if (MiraService.rodando) { abrir(con.ctx, jogo.pkg); con.ok("Abrindo ${jogo.nome}") } }
                    4 -> abrir(act, jogo.pkg)
                    5 -> act.executar("Ajustes para um jogo") { Fps.porJogo(it, jogo) }
                }
            }.setNegativeButton("Fechar", null).show()
    }

    fun abrir(ctx: Context, pkg: String) {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    }

    /** Faixa horizontal com os jogos, para a tela inicial. */
    fun faixa(act: MainActivity): View? {
        val jogos = lista(act).take(12)
        if (jogos.isEmpty()) return null
        val hs = HorizontalScrollView(act)
        hs.isHorizontalScrollBarEnabled = false
        val l = Ui.horizontal(act)
        l.gravity = Gravity.TOP
        for (j in jogos) {
            val c = Ui.vertical(act)
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.setPadding(Ui.dp(act, 6), Ui.dp(act, 10), Ui.dp(act, 6), Ui.dp(act, 8))
            c.background = Ui.clicavel(act, Ui.forma(act, T.surface, 18, T.outline), 18)
            val ic = ImageView(act)
            val d: Drawable? = try { act.packageManager.getApplicationIcon(j.pkg) } catch (e: Exception) { null }
            ic.setImageDrawable(d)
            c.addView(ic, LinearLayout.LayoutParams(Ui.dp(act, 48), Ui.dp(act, 48)))
            val n = Ui.texto(act, j.nome, 11.5f, T.texto, Ui.medio)
            n.maxLines = 2; n.gravity = Gravity.CENTER; n.setPadding(0, Ui.dp(act, 6), 0, 0)
            c.addView(n, LinearLayout.LayoutParams(Ui.dp(act, 76), ViewGroup.LayoutParams.WRAP_CONTENT))
            val selos = ArrayList<String>()
            if (PerfisControle.tem(j.pkg)) selos.add("botões")
            if (Tuner.perfil(j.pkg).aplicado) selos.add("tuner")
            if (selos.isNotEmpty()) { val s = Ui.texto(act, selos.joinToString(" · "), 10f, T.verde, Ui.medio); s.gravity = Gravity.CENTER; c.addView(s) }
            c.isClickable = true
            c.setOnClickListener { menu(act, j) }
            val lp = LinearLayout.LayoutParams(Ui.dp(act, 92), ViewGroup.LayoutParams.WRAP_CONTENT); lp.marginEnd = Ui.dp(act, 10)
            l.addView(c, lp)
        }
        hs.addView(l)
        return hs
    }
}

/** Teste da tela de toque: mostra cada dedo, quantos a tela aceita juntos e a frequência de leitura do toque. */
class TesteToqueView(ctx: Context) : View(ctx) {
    private val dens = ctx.resources.displayMetrics.density
    private val cores = intArrayOf(T.verde, T.azul, T.laranja, T.roxo, T.amarelo, T.vermelho, T.ciano, 0xFFFF4FD8.toInt(), 0xFFFFFFFF.toInt(), 0xFF8CE99A.toInt())
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.texto; textSize = 15f * ctx.resources.displayMetrics.scaledDensity }
    private val pontos = HashMap<Int, Pair<Float, Float>>()
    private val rastro = ArrayList<FloatArray>()   // x, y, cor
    private var maxDedos = 0
    private val tempos = ArrayList<Long>()
    private var hz = 0
    var aoMudar: (Int, Int, Int) -> Unit = { _, _, _ -> }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val agora = SystemClock.uptimeMillis()
        if (e.actionMasked == MotionEvent.ACTION_MOVE) {
            for (h in 0 until e.historySize) tempos.add(e.getHistoricalEventTime(h))
            tempos.add(e.eventTime)
            while (tempos.isNotEmpty() && agora - tempos[0] > 1000) tempos.removeAt(0)
            if (tempos.size > 5) hz = ((tempos.size - 1) * 1000L / (tempos.last() - tempos.first()).coerceAtLeast(1)).toInt()
        }
        pontos.clear()
        if (e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until e.pointerCount) {
                if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex) continue
                pontos[e.getPointerId(i)] = Pair(e.getX(i), e.getY(i))
                rastro.add(floatArrayOf(e.getX(i), e.getY(i), (e.getPointerId(i) % cores.size).toFloat()))
            }
        }
        if (rastro.size > 1500) rastro.subList(0, rastro.size - 1500).clear()
        maxDedos = maxOf(maxDedos, pontos.size)
        aoMudar(pontos.size, maxDedos, hz)
        invalidate()
        return true
    }

    fun limpar() { rastro.clear(); maxDedos = 0; hz = 0; tempos.clear(); invalidate(); aoMudar(0, 0, 0) }

    override fun onDraw(c: Canvas) {
        p.style = Paint.Style.FILL
        for (r in rastro) { p.color = Ui.alpha(cores[r[2].toInt()], 0x55); c.drawCircle(r[0], r[1], 3f * dens, p) }
        for ((id, xy) in pontos) {
            val cor = cores[id % cores.size]
            p.color = Ui.alpha(cor, 0x44); c.drawCircle(xy.first, xy.second, 46f * dens, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f * dens; p.color = cor; c.drawCircle(xy.first, xy.second, 46f * dens, p); p.style = Paint.Style.FILL
            c.drawLine(0f, xy.second, width.toFloat(), xy.second, Paint().apply { color = Ui.alpha(cor, 0x66); strokeWidth = dens })
            c.drawLine(xy.first, 0f, xy.first, height.toFloat(), Paint().apply { color = Ui.alpha(cor, 0x66); strokeWidth = dens })
            c.drawText("${id + 1}", xy.first - 5 * dens, xy.second - 54 * dens, txt)
        }
    }
}

fun MainActivity.telaTesteToque() {
    val f = android.widget.FrameLayout(this)
    f.setBackgroundColor(T.bg)
    val v = TesteToqueView(this)
    f.addView(v, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val info = Ui.vertical(this)
    info.setPadding(Ui.dp(this, 18), Ui.dp(this, 40), Ui.dp(this, 18), Ui.dp(this, 12))
    info.addView(Ui.texto(this, "Teste da tela de toque", 20f, T.texto, Ui.negrito))
    val dica = Ui.texto(this, "Coloque vários dedos e arraste pela tela. As bordas e os cantos também contam.", 13f, T.texto2)
    info.addView(dica)
    val nums = Ui.texto(this, "Dedos agora: 0 · Máximo: 0 · Leitura: –", 15f, T.verde, Ui.medio)
    nums.setPadding(0, Ui.dp(this, 8), 0, 0)
    info.addView(nums)
    v.aoMudar = { agora, max, hz -> nums.text = "Dedos agora: $agora · Máximo: $max · Leitura: " + (if (hz > 0) "$hz Hz" else "arraste um dedo") }
    f.addView(info, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val baixo = Ui.horizontal(this)
    baixo.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), Ui.dp(this, 24))
    baixo.addView(Ui.botao(this, "Limpar", T.verde, false) { v.limpar() }, Ui.peso())
    baixo.addView(Ui.espaco(this, 10, 1))
    baixo.addView(Ui.botao(this, "Sair", T.verde, true) { onBackPressed() }, Ui.peso())
    f.addView(baixo, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    val leg = Ui.texto(this, "Jogos de tiro rodam melhor com leitura alta (120 Hz ou mais). Se um ponto da tela não marcar, pode ser defeito na tela.", 11.5f, T.texto3)
    leg.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), Ui.dp(this, 92))
    f.addView(leg, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    setContentView(f)
}
