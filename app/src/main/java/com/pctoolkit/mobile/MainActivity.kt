package com.pctoolkit.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CountDownLatch

class Item(
    val titulo: String,
    val desc: String = "",
    val cor: Int = C.texto,
    val sub: (() -> Menu)? = null,
    val acao: ((Console) -> Unit)? = null,
    val direto: ((MainActivity) -> Unit)? = null
)

class Menu(val titulo: String, val subtitulo: String = "", val itens: List<Item>)

/**
 * Console: a "janela do PowerShell" do app. As tarefas rodam numa thread separada e
 * podem perguntar coisas ao usuario (Sim/Nao, listas, texto) de forma sequencial,
 * exatamente como o toolkit do PC faz com Read-Host.
 */
class Console(private val act: MainActivity, private val tv: TextView, private val scroll: ScrollView) {
    private val buf = SpannableStringBuilder()
    @Volatile var cancelado = false

    private fun add(t: String, cor: Int) {
        act.runOnUiThread {
            val ini = buf.length
            buf.append(t).append('\n')
            buf.setSpan(ForegroundColorSpan(cor), ini, buf.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (buf.length > 200_000) buf.delete(0, buf.length - 150_000)
            tv.text = buf
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    fun linha(t: String = "", cor: Int = C.texto) = add(t, cor)
    fun titulo(t: String) { add("", C.texto); add("=== $t ===", C.ciano) }
    fun ok(t: String) = add("  [ok]  $t", C.verde)
    fun aviso(t: String) = add("  [!!]  $t", C.amarelo)
    fun erro(t: String) = add("  [X]  $t", C.vermelho)
    fun info(t: String) = add("  $t", C.cinza)

    private fun <T> naUi(padrao: T, bloco: (cb: (T) -> Unit) -> Unit): T {
        if (cancelado) return padrao
        val latch = CountDownLatch(1)
        var r = padrao
        var feito = false
        act.runOnUiThread {
            try {
                bloco { v -> if (!feito) { feito = true; r = v; latch.countDown() } }
            } catch (e: Exception) { latch.countDown() }
        }
        latch.await()
        return r
    }

    fun ask(pergunta: String): Boolean = naUi(false) { cb ->
        AlertDialog.Builder(act)
            .setMessage(pergunta)
            .setPositiveButton("Sim") { _, _ -> cb(true) }
            .setNegativeButton("Nao") { _, _ -> cb(false) }
            .setOnCancelListener { cb(false) }
            .show()
    }

    /** Escolha unica. -1 = cancelou. */
    fun escolher(titulo: String, opcoes: List<String>): Int = naUi(-1) { cb ->
        AlertDialog.Builder(act)
            .setTitle(titulo)
            .setItems(opcoes.toTypedArray()) { _, w -> cb(w) }
            .setNegativeButton("Cancelar") { _, _ -> cb(-1) }
            .setOnCancelListener { cb(-1) }
            .show()
    }

    /** Escolha multipla. */
    fun varios(titulo: String, opcoes: List<String>, marcados: Boolean = false): List<Int> = naUi(emptyList()) { cb ->
        if (opcoes.isEmpty()) { cb(emptyList()); return@naUi }
        val sel = BooleanArray(opcoes.size) { marcados }
        AlertDialog.Builder(act)
            .setTitle(titulo)
            .setMultiChoiceItems(opcoes.toTypedArray(), sel) { _, w, v -> sel[w] = v }
            .setPositiveButton("OK") { _, _ -> cb(sel.indices.filter { sel[it] }) }
            .setNegativeButton("Cancelar") { _, _ -> cb(emptyList()) }
            .setOnCancelListener { cb(emptyList()) }
            .show()
    }

    fun texto(titulo: String, dica: String = "", padrao: String = ""): String? = naUi<String?>(null) { cb ->
        val et = EditText(act)
        et.setText(padrao)
        et.hint = dica
        AlertDialog.Builder(act)
            .setTitle(titulo)
            .setView(et)
            .setPositiveButton("OK") { _, _ -> cb(et.text.toString()) }
            .setNegativeButton("Cancelar") { _, _ -> cb(null) }
            .setOnCancelListener { cb(null) }
            .show()
    }

    fun numero(titulo: String, padrao: Int, min: Int, max: Int): Int {
        val t = texto(titulo, "$min a $max", padrao.toString()) ?: return padrao
        val v = t.trim().toIntOrNull() ?: padrao
        return v.coerceIn(min, max)
    }

    fun escolherArquivo(): Uri? = act.escolherArquivoBloqueando()

    fun abrir(intent: Intent) {
        act.runOnUiThread {
            try { act.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { erro("Nao consegui abrir: " + (e.message ?: "")) }
        }
    }

    fun abrirConfig(acao: String, fallback: String = android.provider.Settings.ACTION_SETTINGS) {
        act.runOnUiThread {
            try { act.startActivity(Intent(acao).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { try { act.startActivity(Intent(fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { } }
        }
    }

    /** Exige root; se nao houver, explica e devolve false. */
    fun exigeRoot(): Boolean {
        if (Sh.temRoot()) return true
        erro("Esta funcao precisa de ROOT e o acesso foi negado.")
        info("Abra o Magisk/KernelSU/APatch e permita o 'PC Toolkit Mobile' em Superusuario, depois tente de novo.")
        Sh.verificarRoot()
        return false
    }

    fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) { } }
}

class MainActivity : Activity() {
    private val pilha = ArrayList<() -> Menu>()
    private var tarefaRodando = false
    private var arquivoLatch: CountDownLatch? = null
    private var arquivoUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.fundo
        window.navigationBarColor = C.fundo
        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1) } catch (e: Exception) { }
        }
        pilha.add { Menus.principal() }
        mostrarMenu()
        // Recupera uma sessao do Modo Jogo que nao terminou (app fechado a forca, celular reiniciado...)
        if (!GameService.rodando && Undo.sessao.quantos() > 0) {
            executar("Recuperando sessao do Modo Jogo") { con ->
                con.aviso("Encontrei uma sessao do Modo Jogo que nao terminou direito. Restaurando o estado normal...")
                if (Sh.temRoot()) {
                    val n = Undo.sessao.desfazer { con.info("restaurado: $it") }
                    con.ok("$n ajustes restaurados")
                    Sessao.restaurarSync()
                } else con.erro("Sem root agora: abra de novo quando o root estiver liberado.")
            }
        }
    }

    // ---------------- telas ----------------
    private fun botao(texto: String, desc: String, cor: Int, onClick: () -> Unit): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14), dp(12), dp(14), dp(12))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(10).toFloat()
        bg.setColor(C.painel)
        bg.setStroke(dp(1), 0xFF30363D.toInt())
        box.background = bg
        val t = TextView(this)
        t.text = texto
        t.setTextColor(cor)
        t.textSize = 16f
        t.typeface = Typeface.DEFAULT_BOLD
        box.addView(t)
        if (desc.isNotEmpty()) {
            val d = TextView(this)
            d.text = desc
            d.setTextColor(C.cinza)
            d.textSize = 13f
            box.addView(d)
        }
        box.isClickable = true
        box.isFocusable = true
        box.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(8))
        box.layoutParams = lp
        return box
    }

    fun mostrarMenu() {
        val gerador: () -> Menu = pilha[pilha.size - 1]
        val m: Menu = gerador()
        val sv = ScrollView(this)
        sv.setBackgroundColor(C.fundo)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(20), dp(16), dp(24))
        val h = TextView(this)
        h.text = m.titulo
        h.setTextColor(C.ciano)
        h.textSize = 22f
        h.typeface = Typeface.DEFAULT_BOLD
        col.addView(h)
        if (m.subtitulo.isNotEmpty()) {
            val s = TextView(this)
            s.text = m.subtitulo
            s.setTextColor(C.cinza)
            s.textSize = 13f
            s.setPadding(0, dp(4), 0, dp(12))
            col.addView(s)
        } else {
            val e = View(this)
            e.layoutParams = LinearLayout.LayoutParams(1, dp(12))
            col.addView(e)
        }
        for (item in m.itens) {
            val sub = item.sub
            val acao = item.acao
            val direto = item.direto
            col.addView(botao(item.titulo, item.desc, item.cor) {
                when {
                    sub != null -> { pilha.add(sub); mostrarMenu() }
                    acao != null -> executar(item.titulo, acao)
                    direto != null -> direto(this)
                }
            })
        }
        if (pilha.size > 1) col.addView(botao("< Voltar", "", C.cinza) { voltar() })
        sv.addView(col)
        setContentView(sv)
    }

    fun executar(titulo: String, acao: (Console) -> Unit) {
        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setBackgroundColor(C.fundo)
        raiz.setPadding(dp(12), dp(16), dp(12), dp(8))
        val h = TextView(this)
        h.text = titulo
        h.setTextColor(C.ciano)
        h.textSize = 18f
        h.typeface = Typeface.DEFAULT_BOLD
        raiz.addView(h)
        val sv = ScrollView(this)
        val tv = TextView(this)
        tv.setTextColor(C.texto)
        tv.typeface = Typeface.MONOSPACE
        tv.textSize = 12.5f
        tv.setTextIsSelectable(true)
        tv.setPadding(0, dp(8), 0, dp(8))
        sv.addView(tv)
        raiz.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val btn = Button(this)
        raiz.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(raiz)
        val con = Console(this, tv, sv)
        btn.text = "PARAR"
        btn.setOnClickListener {
            con.cancelado = true
            btn.text = "Parando..."
            btn.isEnabled = false
        }
        tarefaRodando = true
        Thread {
            try { acao(con) } catch (e: Throwable) { con.erro("Erro inesperado: " + (e.message ?: e.javaClass.simpleName)); Log2.registrar("erro em $titulo: $e") }
            runOnUiThread {
                tarefaRodando = false
                btn.text = "Concluido - voltar ao menu"
                btn.isEnabled = true
                btn.gravity = Gravity.CENTER
                btn.setOnClickListener { mostrarMenu() }
            }
        }.start()
    }

    private fun voltar() {
        if (pilha.size > 1) { pilha.removeAt(pilha.size - 1); mostrarMenu() } else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tarefaRodando) {
            AlertDialog.Builder(this).setMessage("Uma tarefa ainda esta rodando. Espere ela terminar para voltar.").setPositiveButton("OK", null).show()
            return
        }
        voltar()
    }

    // ---------------- seletor de arquivo (VirusTotal) ----------------
    fun escolherArquivoBloqueando(): Uri? {
        val l = CountDownLatch(1)
        arquivoLatch = l
        arquivoUri = null
        runOnUiThread {
            try {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                @Suppress("DEPRECATION")
                startActivityForResult(i, 77)
            } catch (e: Exception) { l.countDown() }
        }
        l.await()
        return arquivoUri
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77) {
            arquivoUri = if (resultCode == RESULT_OK) data?.data else null
            arquivoLatch?.countDown()
        }
    }

    fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
