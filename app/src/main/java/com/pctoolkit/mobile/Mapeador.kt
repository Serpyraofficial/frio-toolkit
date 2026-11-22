package com.pctoolkit.mobile

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Teclado/controle fisico -> toques na tela (estilo MuMu Player).
 * So age quando os controles do PC Toolkit estao abertos e a tecla foi colocada num botao de toque;
 * qualquer outra tecla passa direto para o jogo.
 */
class Mapeador : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val s = TecladoService.instancia ?: return false
        if (!TecladoService.rodando) return false
        return try { s.teclaFisica(event) } catch (e: Exception) { false }
    }

    companion object {
        private fun componente() = ComponentName(PcApp.ctx, Mapeador::class.java).flattenToString()

        fun ativo(): Boolean {
            val l = Settings.Secure.getString(PcApp.ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return l.split(':').any { it.equals(componente(), true) }
        }

        fun ativar(con: Console): Boolean {
            if (ativo()) { con.ok("Teclado/controle físico já ativo"); return true }
            if (Sh.privOk() || Acesso.adb()) {
                val atual = Settings.Secure.getString(PcApp.ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                val novo = (atual.split(':').filter { it.isNotBlank() } + componente()).joinToString(":")
                Ajuste.gravar("secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, novo)
                Ajuste.gravar("secure", Settings.Secure.ACCESSIBILITY_ENABLED, "1")
                con.sleep(800)
                if (ativo()) { con.ok("Teclado/controle físico ativado (automático)"); return true }
            }
            con.linha("Vou abrir Acessibilidade: entre em “PC Toolkit — teclas” e ative.", T.azul)
            if (Build.VERSION.SDK_INT >= 33) con.info("Se aparecer “Configuração restrita”: abra Informações do app do PC Toolkit > ⋮ > Permitir configurações restritas e tente de novo.")
            con.abrirEEsperar(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return ativo()
        }
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
