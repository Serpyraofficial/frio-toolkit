package com.pctoolkit.mobile

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import org.json.JSONObject

class Permissao(val nome: String, val desc: String, val ok: Boolean, val pedir: Intent?, val runtime: String? = null)

/** Niveis de acesso do app: Padrao (permissoes do Android) -> ADB (1 comando no PC) -> Root (opcional). */
object Acesso {
    private val ctx get() = PcApp.ctx
    private val pkg get() = ctx.packageName

    fun arquivos(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun uso(): Boolean {
        val ao = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val modo = if (Build.VERSION.SDK_INT >= 29) ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), pkg)
        else modoAntigo(ao)
        return modo == AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    private fun modoAntigo(ao: AppOpsManager): Int = ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), pkg)

    fun naoPerturbe(): Boolean = (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
    fun sobreposicao(): Boolean = Settings.canDrawOverlays(ctx)
    fun notificacoes(): Boolean = Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
    fun adb(): Boolean = ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun nivel(): String = when {
        Sh.rootConhecido() -> "Root"
        adb() -> "Avançado (ADB)"
        else -> "Padrão"
    }

    fun comandoAdb(): String = "adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"

    fun lista(): List<Permissao> {
        val pArq = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$pkg")) else null
        return listOf(
            Permissao("Acesso aos arquivos", "Analisar e limpar o armazenamento, arquivos grandes, duplicados e backup.", arquivos(), pArq,
                if (Build.VERSION.SDK_INT < 30) Manifest.permission.WRITE_EXTERNAL_STORAGE else null),
            Permissao("Acesso ao uso", "Tamanho real de cada app/jogo, apps esquecidos e o que roda em segundo plano.", uso(), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)),
            Permissao("Não perturbe", "O Modo Jogo silencia notificações enquanto você joga.", naoPerturbe(), Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)),
            Permissao("Sobrepor a outros apps", "Painel flutuante com temperatura, bateria e RAM durante o jogo.", sobreposicao(), Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))),
            Permissao("Notificações", "Acompanhar o Modo Jogo e os testes pela notificação.", notificacoes(), null, if (Build.VERSION.SDK_INT >= 33) "android.permission.POST_NOTIFICATIONS" else null)
        )
    }
}

/** Atalhos para telas do Android (o jeito oficial de mudar o que um app comum nao pode mudar sozinho). */
object Links {
    fun abrir(ctx: Context, acao: String, pacote: String? = null, fallback: String = Settings.ACTION_SETTINGS): Boolean {
        val i = Intent(acao).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pacote != null) i.data = Uri.parse("package:$pacote")
        return try { ctx.startActivity(i); true } catch (e: Exception) {
            try { ctx.startActivity(Intent(fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e2: Exception) { false }
        }
    }

    fun app(ctx: Context, pkg: String) = abrir(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    fun desinstalar(ctx: Context, pkg: String) = try {
        ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }

    fun abrirPacote(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
    }

    fun instalado(ctx: Context, pkg: String): Boolean = try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
}

/** Ferramenta de jogo do fabricante (Game Booster, Game Turbo, Game Space...). */
object FerramentaFabricante {
    private val conhecidas = listOf(
        "com.samsung.android.game.gamehome" to "Game Launcher (Samsung)",
        "com.samsung.android.game.gametools" to "Game Booster (Samsung)",
        "com.xiaomi.joyose" to "Game Turbo (Xiaomi)",
        "com.miui.securitycenter" to "Game Turbo / Seguranca (Xiaomi)",
        "com.motorola.gamemode" to "Moto Gametime (Motorola)",
        "com.oplus.games" to "Game Space (Oppo/Realme/OnePlus)",
        "com.coloros.gamespace" to "Game Space (Oppo/Realme)",
        "com.oneplus.gamespace" to "Game Space (OnePlus)",
        "com.asus.gamewidget" to "Game Genie (ASUS)",
        "com.huawei.gameassistant" to "Assistente de jogos (Huawei)",
        "com.transsion.gamemode" to "Game Mode (Infinix/Tecno)"
    )

    fun encontrar(ctx: Context): Pair<String, String>? {
        for ((p, n) in conhecidas) if (Links.instalado(ctx, p) && ctx.packageManager.getLaunchIntentForPackage(p) != null) return Pair(p, n)
        for ((p, n) in conhecidas) if (Links.instalado(ctx, p)) return Pair(p, n)
        return null
    }
}

/**
 * Ajustes de configuracao do Android com DESFAZER.
 * Funciona quando o app tem a permissao avancada (1 comando ADB, sem root) ou root.
 */
object Ajuste {
    private val ctx get() = PcApp.ctx
    private const val ARQ = "undo.json"

    fun pode(): Boolean = Acesso.adb() || Sh.rootConhecido()

    fun ler(ns: String, key: String): String {
        val cr = ctx.contentResolver
        val v = try {
            when (ns) {
                "global" -> Settings.Global.getString(cr, key)
                "secure" -> Settings.Secure.getString(cr, key)
                else -> Settings.System.getString(cr, key)
            }
        } catch (e: Exception) { null }
        return v ?: ""
    }

    private fun gravarCru(ns: String, key: String, valor: String?): Boolean {
        if (Acesso.adb()) {
            try {
                val cr = ctx.contentResolver
                val ok = when (ns) {
                    "global" -> Settings.Global.putString(cr, key, valor)
                    "secure" -> Settings.Secure.putString(cr, key, valor)
                    else -> Settings.System.putString(cr, key, valor)
                }
                if (ok) return true
            } catch (e: Exception) { }
        }
        if (Sh.rootConhecido()) {
            val r = if (valor == null) Sh.root("settings delete $ns $key") else Sh.root("settings put $ns $key ${Sh.q(valor)}")
            return r.ok
        }
        return false
    }

    @Synchronized
    private fun registrar(o: JSONObject) {
        val a = Store.lerArr(ARQ)
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("k") == o.getString("k")) return
        a.put(o); Store.salvarArr(ARQ, a)
    }

    /** Muda uma configuracao guardando o valor original. */
    fun mudar(ns: String, key: String, valor: String): Boolean {
        val antigo = ler(ns, key)
        registrar(JSONObject().put("k", "set:$ns:$key").put("tipo", "set").put("ns", ns).put("key", key).put("old", antigo))
        return gravarCru(ns, key, valor)
    }

    /** Comando root reversivel. */
    fun comandoRoot(chave: String, aplicar: String, reverter: String): Boolean {
        if (!Sh.rootConhecido()) return false
        registrar(JSONObject().put("k", "cmd:$chave").put("tipo", "cmd").put("cmd", reverter))
        return Sh.root(aplicar).ok
    }

    fun quantos(): Int = Store.lerArr(ARQ).length()

    fun desfazer(log: (String) -> Unit): Int {
        val a = Store.lerArr(ARQ)
        var n = 0
        for (i in a.length() - 1 downTo 0) {
            val o = a.getJSONObject(i)
            if (o.optString("tipo") == "set") {
                val old = o.optString("old")
                if (gravarCru(o.getString("ns"), o.getString("key"), if (old.isEmpty()) null else old)) { n++; log(o.getString("key")) }
            } else if (Sh.rootConhecido()) { Sh.root(o.optString("cmd")); n++; log(o.optString("k")) }
        }
        Store.apagar(ARQ)
        return n
    }
}
