package com.pctoolkit.mobile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.KeyEvent

/**
 * Teclado/controle fisico -> toques na tela (estilo MuMu Player), SEM servico de acessibilidade:
 * le as teclas direto do sistema de entrada pelo Shizuku (getevent), como um cabo ADB faria.
 * So age nas teclas colocadas em botoes de toque (ou W A S D / setas no analogico de arraste);
 * todas as outras teclas continuam chegando normalmente ao jogo.
 */
object Mapeador {
    @Volatile private var proc: Process? = null
    @Volatile private var thread: Thread? = null

    fun ativo(): Boolean = Store.bool("fisico_on", false) && Sh.privOk()

    fun ativar(con: Console): Boolean {
        if (!Sh.privOk()) { con.aviso("Precisa do modo completo para ler o teclado/controle físico."); return false }
        Store.putBool("fisico_on", true)
        con.ok("Teclado/controle físico ativado: as teclas dos seus botões de toque viram toques no jogo")
        return true
    }

    fun desativar() { Store.putBool("fisico_on", false); parar() }

    private val nomes = mapOf(
        "SPACE" to KeyEvent.KEYCODE_SPACE, "ENTER" to KeyEvent.KEYCODE_ENTER, "TAB" to KeyEvent.KEYCODE_TAB, "ESC" to KeyEvent.KEYCODE_ESCAPE,
        "BACKSPACE" to KeyEvent.KEYCODE_DEL, "DELETE" to KeyEvent.KEYCODE_FORWARD_DEL, "CAPSLOCK" to KeyEvent.KEYCODE_CAPS_LOCK,
        "LEFTSHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT, "RIGHTSHIFT" to KeyEvent.KEYCODE_SHIFT_RIGHT, "LEFTCTRL" to KeyEvent.KEYCODE_CTRL_LEFT,
        "RIGHTCTRL" to KeyEvent.KEYCODE_CTRL_RIGHT, "LEFTALT" to KeyEvent.KEYCODE_ALT_LEFT, "RIGHTALT" to KeyEvent.KEYCODE_ALT_RIGHT,
        "LEFTMETA" to KeyEvent.KEYCODE_META_LEFT, "UP" to KeyEvent.KEYCODE_DPAD_UP, "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT, "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT, "HOME" to KeyEvent.KEYCODE_MOVE_HOME, "END" to KeyEvent.KEYCODE_MOVE_END,
        "PAGEUP" to KeyEvent.KEYCODE_PAGE_UP, "PAGEDOWN" to KeyEvent.KEYCODE_PAGE_DOWN, "INSERT" to KeyEvent.KEYCODE_INSERT,
        "COMMA" to KeyEvent.KEYCODE_COMMA, "DOT" to KeyEvent.KEYCODE_PERIOD, "SLASH" to KeyEvent.KEYCODE_SLASH, "SEMICOLON" to KeyEvent.KEYCODE_SEMICOLON,
        "APOSTROPHE" to KeyEvent.KEYCODE_APOSTROPHE, "LEFTBRACE" to KeyEvent.KEYCODE_LEFT_BRACKET, "RIGHTBRACE" to KeyEvent.KEYCODE_RIGHT_BRACKET,
        "MINUS" to KeyEvent.KEYCODE_MINUS, "EQUAL" to KeyEvent.KEYCODE_EQUALS, "GRAVE" to KeyEvent.KEYCODE_GRAVE, "BACKSLASH" to KeyEvent.KEYCODE_BACKSLASH,
        "BTN_SOUTH" to KeyEvent.KEYCODE_BUTTON_A, "BTN_A" to KeyEvent.KEYCODE_BUTTON_A, "BTN_EAST" to KeyEvent.KEYCODE_BUTTON_B, "BTN_B" to KeyEvent.KEYCODE_BUTTON_B,
        "BTN_NORTH" to KeyEvent.KEYCODE_BUTTON_X, "BTN_X" to KeyEvent.KEYCODE_BUTTON_X, "BTN_WEST" to KeyEvent.KEYCODE_BUTTON_Y, "BTN_Y" to KeyEvent.KEYCODE_BUTTON_Y,
        "BTN_TL" to KeyEvent.KEYCODE_BUTTON_L1, "BTN_TR" to KeyEvent.KEYCODE_BUTTON_R1, "BTN_TL2" to KeyEvent.KEYCODE_BUTTON_L2, "BTN_TR2" to KeyEvent.KEYCODE_BUTTON_R2,
        "BTN_START" to KeyEvent.KEYCODE_BUTTON_START, "BTN_SELECT" to KeyEvent.KEYCODE_BUTTON_SELECT
    )

    private fun codigo(nome: String): Int {
        nomes[nome]?.let { return it }
        if (nome.startsWith("BTN_")) return 0
        val n = nome.removePrefix("KEY_")
        nomes[n]?.let { return it }
        if (Regex("F([1-9]|1[0-2])").matches(n)) return KeyEvent.KEYCODE_F1 + n.substring(1).toInt() - 1
        if (n.length == 1) return KeyEvent.keyCodeFromString("KEYCODE_$n")
        return 0
    }

    /** Comeca a ler as teclas fisicas (chamado pelo servico dos controles). */
    fun iniciar(aoTeclar: (KeyEvent) -> Unit) {
        if (!ativo() || thread?.isAlive == true) return
        val t = Thread {
            try {
                val p = Priv.processo("getevent -ql") ?: return@Thread
                proc = p
                val rx = Regex("EV_KEY\\s+(\\S+)\\s+(DOWN|UP|REPEAT)")
                val inicio = HashMap<Int, Long>()
                p.inputStream.bufferedReader().forEachLine { l ->
                    val m = rx.find(l) ?: return@forEachLine
                    val nome = m.groupValues[1]
                    if (nome.startsWith("BTN_TOUCH") || nome.startsWith("BTN_TOOL")) return@forEachLine
                    val c = codigo(nome)
                    if (c <= 0) return@forEachLine
                    val agora = android.os.SystemClock.uptimeMillis()
                    val acao = if (m.groupValues[2] == "UP") KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN
                    if (m.groupValues[2] == "DOWN") inicio[c] = agora
                    val rep = if (m.groupValues[2] == "REPEAT") 1 else 0
                    aoTeclar(KeyEvent(inicio[c] ?: agora, agora, acao, c, rep))
                }
            } catch (e: Exception) { Log2.registrar("mapeador: $e") }
        }
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun parar() {
        try { proc?.destroy() } catch (e: Exception) { }
        proc = null
        thread = null
    }
}

/** Bloco nas Configuracoes rapidas: liga/esconde os controles com o jogo aberto. */
class TecladoTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { it.state = if (TecladoService.rodando) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; it.updateTile() }
    }

    override fun onClick() {
        super.onClick()
        if (TecladoService.rodando) {
            startService(Intent(this, TecladoService::class.java).setAction("alternar"))
        } else {
            Thread {
                val fg = Tela.appNaFrente()
                val i = Intent(this, TecladoService::class.java)
                if (fg != null && fg != packageName && !fg.contains("launcher") && !fg.contains("systemui")) i.putExtra("pkg", fg)
                try { startForegroundService(i) } catch (e: Exception) {
                    Log2.registrar("tile: $e")
                    val a = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("teclado", true)
                    try {
                        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, a, PendingIntent.FLAG_IMMUTABLE))
                        else abrirAntigo(a)
                    } catch (x: Exception) { }
                }
            }.start()
        }
        qsTile?.let { it.state = Tile.STATE_ACTIVE; it.updateTile() }
    }

    @Suppress("DEPRECATION")
    private fun abrirAntigo(a: Intent) = startActivityAndCollapse(a)
}
