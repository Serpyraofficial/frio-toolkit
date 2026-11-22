package com.pctoolkit.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Componentes visuais do app (design system). */
object Ui {
    val medio: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val negrito: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val normal: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun forma(ctx: Context, cor: Int, raioDp: Int, borda: Int = 0, larguraBorda: Int = 1): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = dpf(ctx, raioDp.toFloat())
        g.setColor(cor)
        if (borda != 0) g.setStroke(dp(ctx, larguraBorda), borda)
        return g
    }

    fun gradiente(ctx: Context, c1: Int, c2: Int, raioDp: Int): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2))
        g.cornerRadius = dpf(ctx, raioDp.toFloat())
        return g
    }

    fun clicavel(ctx: Context, fundo: Drawable, raioDp: Int): Drawable {
        val mask = forma(ctx, 0xFFFFFFFF.toInt(), raioDp)
        return RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), fundo, mask)
    }

    fun alpha(cor: Int, a: Int): Int = (cor and 0x00FFFFFF) or (a shl 24)

    fun texto(ctx: Context, t: String, tamSp: Float, cor: Int = T.texto, tf: Typeface = normal): TextView {
        val v = TextView(ctx)
        v.text = t
        v.textSize = tamSp
        v.setTextColor(cor)
        v.typeface = tf
        v.includeFontPadding = true
        return v
    }

    fun icone(ctx: Context, res: Int, cor: Int, tamDp: Int): ImageView {
        val iv = ImageView(ctx)
        iv.setImageResource(res)
        iv.imageTintList = ColorStateList.valueOf(cor)
        iv.layoutParams = LinearLayout.LayoutParams(dp(ctx, tamDp), dp(ctx, tamDp))
        return iv
    }

    /** Icone dentro de um quadrado arredondado colorido. */
    fun bolha(ctx: Context, res: Int, cor: Int, tamDp: Int = 44): FrameLayout {
        val f = FrameLayout(ctx)
        f.background = forma(ctx, alpha(cor, 0x26), 14)
        val iv = ImageView(ctx)
        iv.setImageResource(res)
        iv.imageTintList = ColorStateList.valueOf(cor)
        val s = (tamDp * 0.52f).toInt()
        f.addView(iv, FrameLayout.LayoutParams(dp(ctx, s), dp(ctx, s), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(dp(ctx, tamDp), dp(ctx, tamDp))
        return f
    }

    fun chip(ctx: Context, t: String, cor: Int): TextView {
        val v = texto(ctx, t, 11f, cor, medio)
        v.letterSpacing = 0.04f
        v.background = forma(ctx, alpha(cor, 0x24), 50)
        v.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
        return v
    }

    fun linhaLp(ctx: Context, topo: Int = 0, baixo: Int = 10): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(ctx, topo), 0, dp(ctx, baixo))
        return lp
    }

    fun vertical(ctx: Context): LinearLayout { val l = LinearLayout(ctx); l.orientation = LinearLayout.VERTICAL; return l }
    fun horizontal(ctx: Context): LinearLayout { val l = LinearLayout(ctx); l.orientation = LinearLayout.HORIZONTAL; l.gravity = Gravity.CENTER_VERTICAL; return l }

    fun espaco(ctx: Context, larg: Int, alt: Int): View { val v = View(ctx); v.layoutParams = LinearLayout.LayoutParams(dp(ctx, larg), dp(ctx, alt)); return v }

    fun peso(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    fun titulo1(ctx: Context, t: String) = texto(ctx, t, 26f, T.texto, negrito)
    fun secao(ctx: Context, t: String): TextView {
        val v = texto(ctx, t.uppercase(), 12f, T.texto3, medio)
        v.letterSpacing = 0.12f
        v.setPadding(dp(ctx, 4), dp(ctx, 18), 0, dp(ctx, 10))
        return v
    }

    /** Linha de menu: icone, titulo, descricao, selo e seta. */
    fun linhaMenu(ctx: Context, icone: Int, cor: Int, titulo: String, desc: String, selo: String, corSelo: Int, onClick: () -> Unit): View {
        val box = horizontal(ctx)
        box.setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 12), dp(ctx, 14))
        box.background = clicavel(ctx, forma(ctx, T.surface, 18, T.outline), 18)
        box.addView(bolha(ctx, icone, cor, 42))
        box.addView(espaco(ctx, 14, 1))
        val col = vertical(ctx)
        val topo = horizontal(ctx)
        val tt = texto(ctx, titulo, 15.5f, T.texto, medio)
        tt.maxLines = 2
        topo.addView(tt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (selo.isNotEmpty()) { topo.addView(espaco(ctx, 8, 1)); topo.addView(chip(ctx, selo, corSelo)) }
        col.addView(topo)
        if (desc.isNotEmpty()) {
            val d = texto(ctx, desc, 13f, T.texto2)
            d.setPadding(0, dp(ctx, 3), 0, 0)
            d.setLineSpacing(0f, 1.12f)
            col.addView(d)
        }
        box.addView(col, peso())
        box.addView(espaco(ctx, 6, 1))
        box.addView(icone(ctx, R.drawable.ic_seta, T.texto3, 18))
        box.isClickable = true
        box.setOnClickListener { onClick() }
        box.layoutParams = linhaLp(ctx)
        return box
    }

    /** Bloco da grade da tela inicial. */
    fun bloco(ctx: Context, icone: Int, cor: Int, titulo: String, desc: String, onClick: () -> Unit): View {
        val box = vertical(ctx)
        box.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 14), dp(ctx, 16))
        box.background = clicavel(ctx, forma(ctx, T.surface, 20, T.outline), 20)
        box.addView(bolha(ctx, icone, cor, 42))
        val t = texto(ctx, titulo, 15f, T.texto, medio)
        t.setPadding(0, dp(ctx, 12), 0, dp(ctx, 2))
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        box.addView(t)
        val d = texto(ctx, desc, 12f, T.texto2)
        d.maxLines = 2
        d.ellipsize = TextUtils.TruncateAt.END
        d.minLines = 2
        box.addView(d)
        box.isClickable = true
        box.setOnClickListener { onClick() }
        return box
    }

    fun botao(ctx: Context, t: String, cor: Int, cheio: Boolean, icone: Int = 0, onClick: () -> Unit): LinearLayout {
        val b = horizontal(ctx)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        val fundo = if (cheio) forma(ctx, cor, 16) else forma(ctx, T.surface2, 16, T.outline)
        b.background = clicavel(ctx, fundo, 16)
        val corTexto = if (cheio) 0xFF06120D.toInt() else T.texto
        if (icone != 0) { b.addView(icone(ctx, icone, corTexto, 20)); b.addView(espaco(ctx, 8, 1)) }
        b.addView(texto(ctx, t, 15f, corTexto, medio))
        b.isClickable = true
        b.setOnClickListener { onClick() }
        return b
    }

    private fun margens(ins: WindowInsets): IntArray {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return intArrayOf(b.top, b.bottom, b.left, b.right)
        }
        return margensAntigas(ins)
    }

    @Suppress("DEPRECATION")
    private fun margensAntigas(ins: WindowInsets): IntArray =
        intArrayOf(ins.systemWindowInsetTop, ins.systemWindowInsetBottom, ins.systemWindowInsetLeft, ins.systemWindowInsetRight)

    /** Aplica as margens das barras do sistema (tela de ponta a ponta do Android 15). */
    fun insets(v: View) {
        v.setOnApplyWindowInsetsListener { view, ins ->
            val m = margens(ins)
            val top = m[0]; val bottom = m[1]; val left = m[2]; val right = m[3]
            view.setPadding(left, top, right, bottom)
            ins
        }
        v.requestApplyInsets()
    }
}

/** Anel de progresso com numero no centro (nota de FPS, armazenamento...). */
class Anel(ctx: Context) : View(ctx) {
    var valor: Int = -1
        set(v) { field = v; invalidate() }
    var cor: Int = T.verde
        set(v) { field = v; invalidate() }
    var legenda: String = ""
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val s = Math.min(width, height).toFloat()
        val esp = s * 0.085f
        val cx = width / 2f; val cy = height / 2f
        val r = RectF(cx - s / 2 + esp, cy - s / 2 + esp, cx + s / 2 - esp, cy + s / 2 - esp)
        p.style = Paint.Style.STROKE
        p.strokeWidth = esp
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0x22FFFFFF
        c.drawArc(r, 0f, 360f, false, p)
        if (valor >= 0) { p.color = cor; c.drawArc(r, -90f, 360f * valor.coerceIn(0, 100) / 100f, false, p) }
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.color = T.texto
        p.typeface = Ui.negrito
        p.textSize = s * 0.28f
        val t = if (valor >= 0) valor.toString() else "--"
        val yNum = if (legenda.isEmpty()) cy - (p.descent() + p.ascent()) / 2 else cy + s * 0.04f
        c.drawText(t, cx, yNum, p)
        if (legenda.isNotEmpty()) {
            p.typeface = Ui.medio
            p.textSize = s * 0.105f
            p.color = T.texto2
            c.drawText(legenda, cx, cy + s * 0.2f, p)
        }
    }
}

/** Barra de progresso fina e arredondada. */
class Barra(ctx: Context) : View(ctx) {
    var frac: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var cor: Int = T.verde
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val h = height.toFloat()
        p.color = 0x22FFFFFF
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), h / 2, h / 2, p)
        p.color = cor
        if (frac > 0f) c.drawRoundRect(RectF(0f, 0f, Math.max(h, width * frac), h), h / 2, h / 2, p)
    }
}
