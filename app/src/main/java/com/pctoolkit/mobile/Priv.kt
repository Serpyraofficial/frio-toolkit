package com.pctoolkit.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Modo automatico via Shizuku: o app roda comandos com o mesmo poder de um cabo ADB,
 * sem PC e sem root. Com isso ele libera as proprias permissoes e aplica os ajustes sozinho.
 */
object Priv {
    const val PKG = "moe.shizuku.privileged.api"
    private const val CODIGO = 7070
    @Volatile private var configurado = false

    fun instalado(ctx: Context): Boolean = Links.instalado(ctx, PKG)

    fun rodando(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun permitido(): Boolean = try {
        rodando() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    fun pronto(): Boolean = permitido()

    fun estado(ctx: Context): String = when {
        pronto() -> "Ativo"
        rodando() -> "Falta permitir"
        instalado(ctx) -> "Instalado, desligado"
        else -> "Não instalado"
    }

    /** Mostra o pedido do proprio Shizuku e espera a resposta (chamar fora da thread principal). */
    fun pedir(): Boolean {
        if (!rodando()) return false
        if (permitido()) return true
        val l = CountDownLatch(1)
        var ok = false
        val lis = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == CODIGO) { ok = grantResult == PackageManager.PERMISSION_GRANTED; l.countDown() }
            }
        }
        try {
            Shizuku.addRequestPermissionResultListener(lis)
            Shizuku.requestPermission(CODIGO)
            l.await(120, TimeUnit.SECONDS)
        } catch (e: Throwable) { Log2.registrar("shizuku pedir: $e") } finally {
            try { Shizuku.removeRequestPermissionResultListener(lis) } catch (e: Throwable) { }
        }
        return ok || permitido()
    }

    private val metodo by lazy {
        try {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            m
        } catch (e: Throwable) { null }
    }

    fun exec(cmd: String, timeoutS: Long = 60): Sh.R {
        if (!pronto()) return Sh.R(-1, "", "Shizuku indisponível")
        val m = metodo ?: return Sh.R(-1, "", "Shizuku sem newProcess")
        return try {
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            p.outputStream.close()
            val o = StringBuilder(); val e = StringBuilder()
            val to = Thread { try { p.inputStream.bufferedReader().forEachLine { synchronized(o) { o.append(it).append('\n') } } } catch (x: Exception) { } }
            val te = Thread { try { p.errorStream.bufferedReader().forEachLine { synchronized(e) { e.append(it).append('\n') } } } catch (x: Exception) { } }
            to.start(); te.start()
            val fim = p.waitFor(timeoutS, TimeUnit.SECONDS)
            if (!fim) p.destroy()
            to.join(2000); te.join(2000)
            Sh.R(if (fim) p.exitValue() else -2, synchronized(o) { o.toString().trim() }, synchronized(e) { e.toString().trim() })
        } catch (x: Throwable) { Sh.R(-1, "", x.message ?: "erro") }
    }

    /** Libera todas as permissoes do app de uma vez. Devolve quantas ficaram liberadas. */
    fun autoConfigurar(force: Boolean = false): Int {
        if (!pronto()) return 0
        if (configurado && !force) return 0
        val p = PcApp.ctx.packageName
        val c = ArrayList<String>()
        c.add("pm grant $p android.permission.WRITE_SECURE_SETTINGS")
        if (Build.VERSION.SDK_INT >= 33) c.add("pm grant $p android.permission.POST_NOTIFICATIONS")
        if (Build.VERSION.SDK_INT < 30) { c.add("pm grant $p android.permission.WRITE_EXTERNAL_STORAGE"); c.add("pm grant $p android.permission.READ_EXTERNAL_STORAGE") }
        else c.add("appops set $p MANAGE_EXTERNAL_STORAGE allow")
        c.add("appops set $p GET_USAGE_STATS allow")
        c.add("appops set $p SYSTEM_ALERT_WINDOW allow")
        c.add("cmd notification allow_dnd $p")
        c.add("dumpsys deviceidle whitelist +$p")
        var n = 0
        for (k in c) if (exec(k, 20).ok) n++
        configurado = true
        return n
    }

    fun abrirLoja(ctx: Context) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: Exception) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { }
        }
    }

    /** Passo a passo de ativacao, dentro de uma tarefa. Devolve true se ficou pronto. */
    fun ativar(con: Console): Boolean {
        val ctx = con.ctx
        con.subtitulo("Modo automático (Shizuku)")
        if (pronto()) { val n = autoConfigurar(true); con.ok("Shizuku ativo · $n permissões liberadas automaticamente"); return true }
        if (!instalado(ctx)) {
            con.linha("O Shizuku é um app gratuito que dá ao PC Toolkit o poder de configurar o celular sozinho, sem root e sem PC.", T.texto2)
            if (!con.ask("Instalar o Shizuku pela Play Store?", "Instalar", "Agora não", importante = true)) return false
            con.abrirEEsperar(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PKG")))
            if (!instalado(ctx)) { con.aviso("Shizuku ainda não instalado."); return false }
        }
        if (!rodando()) {
            con.linha("Agora ligue o Shizuku (só na primeira vez e depois de reiniciar o celular):", T.azul)
            if (Build.VERSION.SDK_INT >= 30) {
                con.item("1. No Shizuku, toque em “Parear” e siga o aviso: abra Opções do desenvolvedor > Depuração sem fio > Parear com código")
                con.item("2. Digite o código na notificação do Shizuku")
                con.item("3. Volte ao Shizuku e toque em “Iniciar”")
                con.info("Precisa estar conectado a um Wi-Fi (não precisa de internet). Se não tiver Opções do desenvolvedor: Sobre o telefone > toque 7 vezes em “Número da versão”.")
            } else con.item("Android 10 ou anterior: o Shizuku precisa ser iniciado uma vez pelo PC (o próprio app mostra o comando).")
            con.abrirEEsperar(ctx.packageManager.getLaunchIntentForPackage(PKG) ?: Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            var t = 0
            while (!rodando() && t < 10) { con.sleep(500); t++ }
            if (!rodando()) { con.aviso("O Shizuku ainda não está ligado. Quando ligar, volte aqui (Acesso e permissões)."); return false }
        }
        con.info("O Shizuku vai perguntar se permite o PC Toolkit: toque em “Permitir sempre”.")
        if (!pedir()) { con.aviso("Permissão do Shizuku negada."); return false }
        val n = autoConfigurar(true)
        con.ok("Modo automático ativo · $n permissões liberadas sem você mexer em nada")
        return true
    }
}

/** Como o app confirma as coisas. */
object Modo {
    fun definido(): Boolean = Store.str("modo_conf").isNotEmpty()
    fun auto(): Boolean = Store.str("modo_conf", "auto") == "auto"
    fun nome(): String = if (auto()) "Automático" else "Perguntar sempre"
    fun definir(auto: Boolean) = Store.putStr("modo_conf", if (auto) "auto" else "perguntar")
    /** Preferencia de permissoes: "auto" (Shizuku) ou "manual" (telas do Android). */
    fun permissoesAuto(): Boolean = Store.str("modo_perm", "auto") == "auto"
    fun definirPermissoes(auto: Boolean) = Store.putStr("modo_perm", if (auto) "auto" else "manual")
}

/** Acoes que o app faz sozinho quando tem o modo automatico; senao abre a tela certa. */
object Auto {
    fun desligarEconomia(con: Console): Boolean {
        if (Sh.privOk()) {
            Sh.root("cmd power set-mode 0 2>/dev/null; settings put global low_power 0", 15)
            con.sleep(800)
            if (!Sensores.economiaEnergia(con.ctx)) { con.ok("Economia de energia desligada (automático)"); return true }
        }
        con.abrirEEsperar(Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS))
        return !Sensores.economiaEnergia(con.ctx)
    }

    fun desligarMsaa(con: Console) {
        if (Sh.privOk()) { Sh.root("setprop debug.egl.force_msaa false; settings put global force_msaa 0 2>/dev/null", 10); con.ok("MSAA 4x forçado desligado (automático)"); return }
        con.abrirEEsperar(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }

    /** Desinstala (a escolha do usuario ja e a confirmacao). */
    fun desinstalar(con: Console, pkg: String, nome: String) {
        if (Sh.privOk()) {
            val r = Sh.root("pm uninstall --user 0 $pkg", 60)
            if (r.out.contains("Success")) { con.ok("Desinstalado: $nome"); return }
        }
        con.abrirEEsperar(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        if (!Links.instalado(con.ctx, pkg)) con.ok("Desinstalado: $nome")
    }

    /** Jogo sem restricao de bateria e no modo Desempenho do Android. */
    fun liberarJogo(con: Console, pkg: String): Boolean {
        if (!Sh.privOk()) return false
        Sh.root("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow; cmd appops set $pkg RUN_IN_BACKGROUND allow; dumpsys deviceidle whitelist +$pkg", 20)
        if (Build.VERSION.SDK_INT >= 31) Sh.root("cmd game mode performance $pkg 2>/dev/null", 10)
        con.ok("Bateria sem restrição e modo Desempenho para o jogo (automático)")
        return true
    }

    fun restringirSegundoPlano(con: Console, pkg: String, nome: String): Boolean {
        if (!Sh.privOk()) return false
        Ajuste.comandoRoot("bg:$pkg", "cmd appops set $pkg RUN_ANY_IN_BACKGROUND ignore", "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
        con.ok("$nome não roda mais escondido (reversível em Desfazer)")
        return true
    }

    fun temaEscuro(con: Console) {
        if (Sh.privOk()) { Sh.root("cmd uimode night yes", 10); con.ok("Tema escuro ligado (automático)"); return }
        con.abrirEEsperar(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS))
    }
}
