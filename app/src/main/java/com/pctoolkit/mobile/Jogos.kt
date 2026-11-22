package com.pctoolkit.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

data class Jogo(val pkg: String, val nome: String, val origem: String)

/** Deteccao de jogos instalados (equivale a Steam/Epic/GOG... do PC: aqui e a categoria Jogo do Android + editoras conhecidas). */
object Jogos {
    private val rxEditoras = Regex(
        "pubg|tencent\\.ig|freefire|dts\\.freefire|garena|supercell|mojang|roblox|epicgames|activision|callofduty|mihoyo|hoyoverse|cognosphere|" +
            "gameloft|riotgames|netease|netmarble|nexon|moonton|mobile\\.legends|levelinfinite|krafton|ubisoft|rockstargames|bandainamco|sega|" +
            "squareenix|square_enix|com\\.ea\\.|kiloo|halfbrick|miniclip|nianticlabs|playrix|com\\.king\\.|zynga|scopely|ninjakiwi|axlebolt|" +
            "madfingergames|innersloth|pixonic|wildlife|kurogame|yostar|dreamotion|com\\.gof\\.|fortnite|brawlstars|clashroyale|clashofclans",
        RegexOption.IGNORE_CASE
    )

    fun instalados(ctx: Context): List<Jogo> {
        val pm = ctx.packageManager
        val extras = Store.set("jogos_extra")
        val r = ArrayList<Jogo>()
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(0)
        for (ai in apps) {
            if (ai.packageName == ctx.packageName) continue
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue
            val porCat = ai.category == ApplicationInfo.CATEGORY_GAME
            @Suppress("DEPRECATION")
            val porFlag = (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            val porNome = rxEditoras.containsMatchIn(ai.packageName)
            val extra = extras.contains(ai.packageName)
            if (porCat || porFlag || porNome || extra) {
                val origem = when {
                    extra -> "adicionado por voce"
                    porCat || porFlag -> "categoria Jogo"
                    else -> "editora de jogos"
                }
                r.add(Jogo(ai.packageName, pm.getApplicationLabel(ai).toString(), origem))
            }
        }
        return r.sortedBy { it.nome.lowercase() }
    }

    fun todosApps(ctx: Context): List<Jogo> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(0)
        return apps.filter { it.packageName != ctx.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { Jogo(it.packageName, pm.getApplicationLabel(it).toString(), "app") }
            .sortedBy { it.nome.lowercase() }
    }

    fun nome(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    /** Escolher o jogo (lista + ultimo jogo + outro app). */
    fun escolher(con: Console, ctx: Context, titulo: String = "Escolha o jogo"): Jogo? {
        con.info("Procurando jogos instalados...")
        val lista = instalados(ctx)
        val ult = Store.str("ultimo_jogo")
        val opcoes = ArrayList<String>()
        val alvo = ArrayList<Jogo?>()
        if (ult.isNotEmpty() && lista.any { it.pkg == ult }) {
            val j = lista.first { it.pkg == ult }
            opcoes.add("Ultimo jogo: ${j.nome}"); alvo.add(j)
        }
        for (j in lista) { opcoes.add("${j.nome}   [${j.origem}]"); alvo.add(j) }
        opcoes.add("+ Outro app (lista de todos os apps)"); alvo.add(null)
        if (lista.isEmpty()) con.aviso("Nenhum jogo encontrado automaticamente. Use '+ Outro app'.")
        val i = con.escolher(titulo, opcoes)
        if (i < 0) return null
        val j = alvo[i]
        if (j != null) { Store.putStr("ultimo_jogo", j.pkg); return j }
        val todos = todosApps(ctx)
        val k = con.escolher("Todos os apps", todos.map { it.nome + "  (" + it.pkg + ")" })
        if (k < 0) return null
        val esc = todos[k]
        if (con.ask("Lembrar '${esc.nome}' como jogo nas proximas vezes?")) {
            val s = Store.set("jogos_extra"); s.add(esc.pkg); Store.putSet("jogos_extra", s)
        }
        Store.putStr("ultimo_jogo", esc.pkg)
        return Jogo(esc.pkg, esc.nome, "adicionado por voce")
    }

    fun abrir(ctx: Context, pkg: String): Boolean {
        val i: Intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (e: Exception) { false }
    }

    /** Abre o jogo pelo root (funciona mesmo a partir do servico em segundo plano). */
    fun abrirRoot(ctx: Context, pkg: String): Boolean {
        val comp = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
        if (comp != null && Sh.root("am start -n ${Sh.q(comp)} 2>&1", 20).let { it.ok && !it.out.contains("Error") }) return true
        if (Sh.root("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1", 30).ok) return true
        return abrir(ctx, pkg)
    }

    /** Pastas do jogo para medir tamanho. */
    fun pastas(ctx: Context, pkg: String): List<String> {
        val r = ArrayList<String>()
        try {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            val src = ai.sourceDir
            if (src != null) r.add(java.io.File(src).parent ?: src)
            r.add(ai.dataDir)
        } catch (e: Exception) { }
        r.add("${Sd.base}/Android/data/$pkg")
        r.add("${Sd.base}/Android/obb/$pkg")
        return r
    }
}

/** Armazenamento interno visto pelo root (o mesmo que /sdcard, mas sempre acessivel ao su). */
object Sd {
    const val base = "/data/media/0"
    const val lixeira = "$base/.PCToolkit-Lixeira"
}
