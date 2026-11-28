package com.pctoolkit.mobile

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer
import java.util.Locale

/** Servico de Acessibilidade: so e usado para tocar nas Configuracoes no lugar da pessoa ao ativar o modo completo. */
class AutoService : AccessibilityService() {
    companion object {
        @Volatile var inst: AutoService? = null
        fun ativo(ctx: Context): Boolean {
            if (inst != null) return true
            val lista = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return lista.contains(ComponentName(ctx, AutoService::class.java).flattenToString(), true)
        }
    }
    override fun onServiceConnected() { inst = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) { }
    override fun onInterrupt() { }
    override fun onUnbind(intent: Intent?): Boolean { inst = null; return super.onUnbind(intent) }
    override fun onDestroy() { inst = null; super.onDestroy() }
}

/** Le a tela das Configuracoes e toca nos lugares certos, em qualquer marca. */
object Robo {
    private fun norm(s: CharSequence?): String = Normalizer.normalize(s?.toString() ?: "", Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    /** Os textos que as Configuracoes DESTE celular usam (na lingua e com o nome da marca), mais os conhecidos. */
    fun textos(ctx: Context, recursos: List<String>, conhecidos: List<String>): List<String> {
        val r = ArrayList<String>()
        for (pkg in listOf("com.android.settings", "com.miui.securitycenter", "com.samsung.android.settings")) {
            val res = try { ctx.packageManager.getResourcesForApplication(pkg) } catch (e: Exception) { null } ?: continue
            for (n in recursos) {
                val id = res.getIdentifier(n, "string", pkg)
                if (id != 0) try { res.getString(id).takeIf { it.isNotBlank() && it.length < 60 }?.let { r.add(it) } } catch (e: Exception) { }
            }
        }
        r.addAll(conhecidos)
        r.addAll(Store.str("auto_palavras_extra").split("|").filter { it.isNotBlank() })
        return r.map { it.replace("%s", "").trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun raiz(): AccessibilityNodeInfo? = try { AutoService.inst?.rootInActiveWindow } catch (e: Exception) { null }

    private fun todos(n: AccessibilityNodeInfo?, saida: MutableList<AccessibilityNodeInfo>, prof: Int = 0) {
        if (n == null || prof > 40) return
        saida.add(n)
        for (i in 0 until n.childCount) todos(n.getChild(i), saida, prof + 1)
    }

    fun textosNaTela(): List<String> { val l = ArrayList<AccessibilityNodeInfo>(); todos(raiz(), l); return l.mapNotNull { (it.text ?: it.contentDescription)?.toString() } }

    /** Acha o item cujo texto bate com algum dos nomes (primeiro igual, depois "comeca com"). */
    fun achar(nomes: List<String>): AccessibilityNodeInfo? {
        val l = ArrayList<AccessibilityNodeInfo>(); todos(raiz(), l)
        val alvo = nomes.map { norm(it) }.filter { it.length >= 3 }
        for (a in alvo) l.firstOrNull { norm(it.text ?: it.contentDescription) == a }?.let { return it }
        for (a in alvo) l.firstOrNull { val t = norm(it.text ?: it.contentDescription); t.startsWith(a) && t.length <= a.length + 25 }?.let { return it }
        return null
    }

    fun clicar(n: AccessibilityNodeInfo?): Boolean {
        var x = n
        var k = 0
        while (x != null && k < 8) { if (x.isClickable && x.isEnabled) return x.performAction(AccessibilityNodeInfo.ACTION_CLICK); x = x.parent; k++ }
        return false
    }

    /** O interruptor da mesma linha do item (se houver). */
    fun interruptorDe(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var linha = n; var k = 0
        while (linha != null && k < 5) {
            val l = ArrayList<AccessibilityNodeInfo>(); todos(linha, l)
            l.firstOrNull { it.isCheckable && (it.className?.toString()?.contains("Switch") == true || it.className?.toString()?.contains("CheckBox") == true || it.isCheckable) }?.let { return it }
            linha = linha.parent; k++
        }
        return null
    }

    fun rolar(): Boolean {
        val l = ArrayList<AccessibilityNodeInfo>(); todos(raiz(), l)
        return l.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
    }

    fun procurar(nomes: List<String>, rolagens: Int = 12, cancelado: () -> Boolean = { false }): AccessibilityNodeInfo? {
        for (i in 0..rolagens) {
            if (cancelado()) return null
            achar(nomes)?.let { return it }
            if (!rolar()) { Thread.sleep(500); achar(nomes)?.let { return it }; if (i > 2) return null }
            Thread.sleep(450)
        }
        return null
    }

    fun voltar() { try { AutoService.inst?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch (e: Exception) { } }

    /** O codigo de pareamento (6 numeros) e o IP:porta mostrados na janela. */
    fun codigoEPorta(): Pair<String, Int>? {
        val t = textosNaTela()
        val cod = t.firstNotNullOfOrNull { Regex("^\\s*(\\d{6})\\s*$").find(it)?.groupValues?.get(1) } ?: t.firstNotNullOfOrNull { Regex("(?<![\\d.:])(\\d{6})(?![\\d.:])").find(it)?.groupValues?.get(1) } ?: return null
        val porta = t.firstNotNullOfOrNull { Regex("\\d{1,3}(?:\\.\\d{1,3}){3}:(\\d{2,5})").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1
        return Pair(cod, porta)
    }
}

/** O botao "Ativar modo completo": tenta tudo sozinho; se nao der, guia pela notificacao. */
object ModoCompleto {
    private val NUMERO_VERSAO = listOf("Número da versão", "Número de compilação", "Build number", "Número de versión", "Versão do MIUI", "MIUI version", "Versión de MIUI",
        "Versão do HyperOS", "HyperOS version", "Versão do SO", "OS version", "Versão do sistema operacional", "Versão do software", "Software version")
    private val INFO_SOFTWARE = listOf("Informações do software", "Software information", "Información de software", "Informações de software")
    private val DEPURACAO_SEM_FIO = listOf("Depuração sem fio", "Depuração via Wi-Fi", "Wireless debugging", "Depuración inalámbrica", "Depuración por Wi-Fi")
    private val USAR = listOf("Usar depuração sem fio", "Use wireless debugging", "Usar depuración inalámbrica", "Depuração sem fio")
    private val PERMITIR = listOf("Permitir", "Allow", "Sempre permitir nesta rede", "Always allow on this network", "Permitir siempre en esta red", "OK", "Ativar")
    private val PAREAR = listOf("Parear o dispositivo com um código de pareamento", "Parear dispositivo com código de pareamento", "Pair device with pairing code",
        "Vincular dispositivo con código de sincronización", "Vincular dispositivo con un código de vinculación", "Parear com código", "Emparejar dispositivo con código")

    fun ativo(): Boolean = Priv.permitido() || Interno.pronto()

    fun executar(con: Console): Boolean {
        val ctx = con.ctx
        con.titulo("Ativar modo completo")
        if (ativo()) { con.ok("O modo completo já está ativo."); Priv.autoConfigurar(true); return true }
        if (!Interno.suportado()) {
            con.aviso("No Android ${Build.VERSION.RELEASE} não existe a Depuração sem fio. Para o modo completo neste celular, use o Shizuku iniciado pelo computador ou root.")
            return Priv.ativar(con)
        }
        if (Interno.pareado()) {
            con.linha("Reconectando…", T.texto2)
            if (Interno.religar(ctx)) { con.ok("Modo completo ativo de novo."); return true }
        }
        if (Rede.tipo(ctx) != "Wi-Fi") {
            con.aviso("Conecte o celular a um Wi-Fi (não precisa ter internet). O Android só libera a Depuração sem fio no Wi-Fi.")
            con.abrirEEsperar(Intent(Settings.ACTION_WIFI_SETTINGS))
            if (Rede.tipo(ctx) != "Wi-Fi") { con.erro("Ainda sem Wi-Fi. Tente de novo quando estiver conectado."); return false }
        }
        con.linha("São 3 passos rápidos, só na primeira vez. Depois o app se reconecta sozinho.", T.texto2)
        return guiado(con)
    }

    private fun ligarAcessibilidade(con: Console) {
        val ctx = con.ctx
        con.subtitulo("1. Permita o assistente do app")
        if (Build.VERSION.SDK_INT >= 33 && !Store.bool("restrito_ok", false)) {
            con.linha("Como o app não veio da Play Store, o Android pede um toque antes: na tela que vai abrir, toque nos 3 pontinhos (⋮) lá em cima e em “Permitir configurações restritas”. Se não aparecer, pode voltar.", T.azul)
            con.abrirEEsperar(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            Store.putBool("restrito_ok", true)
        }
        con.linha("Agora toque em “SerpyraToolkit” (pode estar em “Apps instalados”) e ligue a chave. Ele só é usado para tocar nas Configurações no seu lugar.", T.azul)
        con.abrirEEsperar(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        var t = 0
        while (!AutoService.ativo(con.ctx) && t < 6) { con.sleep(500); t++ }
        if (AutoService.ativo(ctx)) con.ok("Assistente ligado") else con.info("Sem o assistente, seguimos pelo passo a passo (dá certo do mesmo jeito).")
    }

    private fun esperar(ms: Long, ate: () -> Boolean): Boolean {
        val fim = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fim) { if (ate()) return true; Thread.sleep(300) }
        return ate()
    }

    private fun automatico(con: Console): Boolean {
        val ctx = con.ctx
        val parar = { con.cancelado }
        try {
            // espera o servico conectar de verdade
            if (!esperar(4000) { AutoService.inst != null }) return false
            con.subtitulo("2. Ligando as Opções do desenvolvedor")
            if (!Interno.opcoesDevLigadas(ctx)) {
                ctx.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Thread.sleep(1500)
                val nomes = Robo.textos(ctx, listOf("build_number", "miui_version", "device_miui_version", "os_version", "software_version"), NUMERO_VERSAO)
                var n = Robo.procurar(nomes, 10, parar)
                if (n == null) { Robo.procurar(Robo.textos(ctx, listOf("software_info", "software_information"), INFO_SOFTWARE), 8, parar)?.let { Robo.clicar(it); Thread.sleep(1200); n = Robo.procurar(nomes, 8, parar) } }
                if (n == null) { falhou("numero da versao"); voltarAoApp(ctx); return false }
                for (i in 0 until 8) { if (Interno.opcoesDevLigadas(ctx)) break; Robo.clicar(n); Thread.sleep(220) }
                con.info("Se o celular pedir a senha ou o PIN da tela, digite.")
                if (!esperar(40_000) { Interno.opcoesDevLigadas(ctx) }) { falhou("opcoes dev nao ligaram"); voltarAoApp(ctx); return false }
            }
            con.ok("Opções do desenvolvedor ligadas")

            con.subtitulo("3. Ligando a Depuração sem fio")
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Thread.sleep(1500)
            val sf = Robo.textos(ctx, listOf("enable_adb_wireless", "adb_wireless_debugging", "wireless_debugging_title", "adb_wireless_settings"), DEPURACAO_SEM_FIO)
            val item = Robo.procurar(sf, 25, parar) ?: run { falhou("depuracao sem fio"); voltarAoApp(ctx); return false }
            val chave = Robo.interruptorDe(item)
            if (chave != null && !chave.isChecked && !Interno.depuracaoSemFioLigada(ctx)) {
                Robo.clicar(chave); Thread.sleep(1200)
                Robo.achar(Robo.textos(ctx, listOf("adb_wireless_allow_button", "allow"), PERMITIR))?.let { Robo.clicar(it); Thread.sleep(1000) }
            }
            // entra na pagina da Depuracao sem fio
            Robo.achar(sf)?.let { Robo.clicar(it) }; Thread.sleep(1500)
            if (!Interno.depuracaoSemFioLigada(ctx)) {
                Robo.achar(Robo.textos(ctx, listOf("adb_wireless_main_switch_title"), USAR))?.let { u -> (Robo.interruptorDe(u) ?: u).let { Robo.clicar(it) } }
                Thread.sleep(1200)
                Robo.achar(Robo.textos(ctx, listOf("adb_wireless_allow_button", "allow"), PERMITIR))?.let { Robo.clicar(it); Thread.sleep(1000) }
            }
            if (!esperar(8000) { Interno.depuracaoSemFioLigada(ctx) }) { falhou("nao ligou adb_wifi"); voltarAoApp(ctx); return false }
            con.ok("Depuração sem fio ligada")

            con.subtitulo("4. Conectando")
            val par = Robo.procurar(Robo.textos(ctx, listOf("adb_pair_method_code_title", "adb_pairing_device_with_code"), PAREAR), 6, parar)
                ?: run { falhou("parear com codigo"); voltarAoApp(ctx); return false }
            Robo.clicar(par)
            var cp: Pair<String, Int>? = null
            esperar(6000) { cp = Robo.codigoEPorta(); cp != null }
            val dados = cp ?: run { falhou("codigo na tela"); voltarAoApp(ctx); return false }
            val ok = Interno.parear(ctx, dados.first, dados.second)
            Robo.voltar(); Thread.sleep(300)
            voltarAoApp(ctx)
            if (!ok) { falhou("pareamento recusado"); return false }
            con.ok("Modo completo ativado! O app agora faz tudo sozinho, sem o Shizuku.")
            con.info("Depois de reiniciar o celular, ele se reconecta sozinho quando estiver no Wi-Fi.")
            return true
        } catch (e: Exception) {
            Relatorio.falha("modo completo: automacao", e); voltarAoApp(ctx); return false
        }
    }

    private fun falhou(etapa: String) {
        Uso.modoCompleto(false, etapa)
        Relatorio.falha("modo completo: " + etapa, Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " · tela: " + Robo.textosNaTela().take(12).joinToString(" | ").take(140))
    }

    private fun voltarAoApp(ctx: Context) {
        try { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } catch (e: Exception) { }
    }

    /** Passo a passo com o codigo digitado na notificacao (funciona em qualquer marca). */
    fun guiado(con: Console): Boolean {
        val ctx = con.ctx
        val xiaomi = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }
        // onde digitar o codigo: caixinha por cima das Configuracoes (melhor) e notificacao (reserva)
        if (!Acesso.notificacoes()) { con.info("Permita as notificações do app: o campo do código também aparece nelas."); con.garantirNome("Notificações") }
        if (!ctx.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) {
            con.linha("As notificações do SerpyraToolkit estão desligadas neste celular. Ligue na tela que vai abrir e volte.", T.azul)
            con.abrirEEsperar(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
        if (!Settings.canDrawOverlays(ctx)) { con.info("Permita “Sobrepor a outros apps”: assim aparece uma caixinha por cima das Configurações para você digitar o código."); con.garantirNome("Sobrepor a outros apps") }
        con.subtitulo("Passo 1")
        if (!Interno.opcoesDevLigadas(ctx)) {
            con.linha(if (xiaomi) "Ligue as Opções do desenvolvedor: em Configurações > Sobre o telefone, toque 7 vezes em “Versão do SO” (ou “Versão do MIUI”/“Versão do HyperOS”)." else "Ligue as Opções do desenvolvedor: na tela que vai abrir, toque 7 vezes em “Número da versão” (na Samsung, fica em Informações do software).", T.azul)
            con.abrirEEsperar(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        } else con.ok("Opções do desenvolvedor já ligadas")
        con.subtitulo("Passo 2")
        if (xiaomi) con.linha("Nas Opções do desenvolvedor (Configurações > Configurações adicionais > Opções do desenvolvedor), ligue primeiro a “Depuração USB”. Depois ligue a “Depuração sem fio” e toque no nome dela para entrar.", T.azul)
        else con.linha("Nas Opções do desenvolvedor, ligue a “Depuração sem fio” e toque no nome dela para entrar.", T.azul)
        con.subtitulo("Passo 3")
        con.linha("Toque em “Parear o dispositivo com um código de pareamento”. Com os 6 números na tela, digite-os na caixinha do SerpyraToolkit que aparece por cima (ou na notificação do app).", T.azul)
        Interno.pedirCodigoNaNotificacao(ctx)
        CodigoFlutuante.mostrar(ctx)
        try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        con.linha("Aguardando o código… (até 5 minutos)", T.texto2)
        val fim = System.currentTimeMillis() + 5 * 60_000L
        while (System.currentTimeMillis() < fim && !con.cancelado) { if (Interno.pronto()) break; con.sleep(1000) }
        CodigoFlutuante.fechar()
        if (Interno.pronto()) {
            Uso.modoCompleto(true, "")
            try { ctx.getSystemService(android.app.NotificationManager::class.java).cancel(Interno.NOTIF) } catch (e: Exception) { }
            con.ok("Modo completo ativado! O app agora faz tudo sozinho, sem o Shizuku.")
            return true
        }
        con.aviso("Não conectou ainda. Você pode tentar de novo em Acesso e permissões > Ativar modo completo.")
        falhou("guiado sem conexao")
        return false
    }
}

/** Caixinha por cima das Configuracoes para digitar o codigo de pareamento sem sair da tela do codigo. */
object CodigoFlutuante {
    private var vista: android.view.View? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    fun mostrar(ctx: Context) {
        if (!Settings.canDrawOverlays(ctx)) return
        ui.post {
            if (vista != null) return@post
            try {
                val wm = ctx.getSystemService(android.view.WindowManager::class.java)
                val caixa = android.widget.LinearLayout(ctx)
                caixa.orientation = android.widget.LinearLayout.VERTICAL
                val p = Ui.dp(ctx, 14)
                caixa.setPadding(p, p, p, p)
                caixa.background = Ui.forma(ctx, 0xF2101C30.toInt(), 18, T.verde)
                val topo = android.widget.LinearLayout(ctx)
                topo.addView(Ui.texto(ctx, "SerpyraToolkit · código de pareamento", 13.5f, T.texto, Ui.negrito), Ui.peso())
                val x = Ui.texto(ctx, "  ✕  ", 16f, T.texto2, Ui.negrito)
                x.setOnClickListener { fechar() }
                topo.addView(x)
                caixa.addView(topo)
                val msg = Ui.texto(ctx, "Digite os 6 números que aparecem na tela e toque em Conectar.", 12f, T.texto2)
                caixa.addView(msg)
                val linha = android.widget.LinearLayout(ctx)
                val et = android.widget.EditText(ctx)
                et.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                et.hint = "000000"; et.textSize = 20f
                et.setTextColor(T.texto); et.setHintTextColor(T.texto3)
                et.filters = arrayOf(android.text.InputFilter.LengthFilter(6))
                linha.addView(et, Ui.peso())
                val bt = Ui.chip(ctx, "Conectar", T.verde)
                bt.isClickable = true
                bt.setOnClickListener {
                    val cod = et.text.toString().filter { it.isDigit() }
                    if (cod.length != 6) { msg.text = "O código tem 6 números."; msg.setTextColor(T.amarelo); return@setOnClickListener }
                    msg.text = "Conectando…"; msg.setTextColor(T.azul); bt.isEnabled = false
                    Thread {
                        val ok = Interno.parear(ctx.applicationContext, cod)
                        ui.post {
                            if (ok) { msg.text = "Pronto! Modo completo ativado."; msg.setTextColor(T.verde); ui.postDelayed({ fechar(); try { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } catch (e: Exception) { } }, 1500) }
                            else { msg.text = "Não deu certo. Confira se a janela do código ainda está aberta (o código muda a cada vez) e tente de novo."; msg.setTextColor(T.vermelho); bt.isEnabled = true; et.setText("") }
                        }
                    }.start()
                }
                linha.addView(bt)
                caixa.addView(linha)
                val lp = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    android.graphics.PixelFormat.TRANSLUCENT)
                lp.gravity = android.view.Gravity.TOP
                lp.y = Ui.dp(ctx, 40)
                lp.softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                wm.addView(caixa, lp)
                vista = caixa
            } catch (e: Exception) { Relatorio.falha("modo completo: caixinha", e) }
        }
    }

    fun fechar() {
        ui.post {
            val v = vista ?: return@post
            try { v.context.getSystemService(android.view.WindowManager::class.java).removeView(v) } catch (e: Exception) { }
            vista = null
        }
    }
}
