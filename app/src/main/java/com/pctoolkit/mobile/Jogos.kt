package com.pctoolkit.mobile

import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.storage.StorageManager

data class Jogo(val pkg: String, val nome: String, val origem: String)
data class TamanhoApp(val app: Long, val dados: Long, val cache: Long) { val total get() = app + dados }

/** Jogos instalados (categoria Jogo do Android + editoras conhecidas + os que voce adicionar). */
object Jogos {
    private val rxEditoras = Regex(
        "pubg|tencent\\.ig|freefire|dts\\.freefire|garena|supercell|mojang|roblox|epicgames|activision|callofduty|mihoyo|hoyoverse|cognosphere|" +
            "gameloft|riotgames|netease|netmarble|nexon|moonton|mobile\\.legends|levelinfinite|krafton|ubisoft|rockstargames|bandainamco|sega|" +
            "squareenix|square_enix|com\\.ea\\.|kiloo|halfbrick|miniclip|nianticlabs|playrix|com\\.king\\.|zynga|scopely|ninjakiwi|axlebolt|" +
            "madfingergames|innersloth|pixonic|wildlife|kurogame|yostar|com\\.gof\\.|brawlstars|clashroyale|clashofclans",
        RegexOption.IGNORE_CASE
    )

    fun instalados(ctx: Context): List<Jogo> {
        val pm = ctx.packageManager
        val extras = Store.conjunto("jogos_extra")
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
                val origem = when { extra -> "adicionado por você"; porCat || porFlag -> "jogo"; else -> "editora de jogos" }
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

    fun escolher(con: Console, titulo: String = "Escolha o jogo"): Jogo? {
        val ctx = con.ctx
        val lista = instalados(ctx)
        val ult = Store.str("ultimo_jogo")
        val opcoes = ArrayList<String>()
        val alvo = ArrayList<Jogo?>()
        val u = lista.firstOrNull { it.pkg == ult }
        if (u != null) { opcoes.add("★  ${u.nome} (último)"); alvo.add(u) }
        for (j in lista) { opcoes.add(j.nome); alvo.add(j) }
        opcoes.add("+  Outro app..."); alvo.add(null)
        val i = con.escolher(titulo, opcoes)
        if (i < 0) return null
        val j = alvo[i]
        if (j != null) { Store.putStr("ultimo_jogo", j.pkg); return j }
        val todos = todosApps(ctx)
        val k = con.escolher("Todos os apps", todos.map { it.nome })
        if (k < 0) return null
        val esc = todos[k]
        if (con.ask("Lembrar \"${esc.nome}\" como jogo nas próximas vezes?")) {
            val s = Store.conjunto("jogos_extra"); s.add(esc.pkg); Store.putConjunto("jogos_extra", s)
        }
        Store.putStr("ultimo_jogo", esc.pkg)
        return Jogo(esc.pkg, esc.nome, "adicionado por você")
    }

    /** Tamanho real do app (precisa de Acesso ao uso). */
    fun tamanho(ctx: Context, pkg: String): TamanhoApp? = try {
        val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
        TamanhoApp(s.appBytes, s.dataBytes, s.cacheBytes)
    } catch (e: Exception) { null }

    /** Ultimo uso de cada app nos ultimos [dias] (precisa de Acesso ao uso). */
    fun ultimoUso(ctx: Context, dias: Int): Map<String, Long> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val fim = System.currentTimeMillis()
        val ini = fim - dias * 86_400_000L
        val r = HashMap<String, Long>()
        try {
            for (s in usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, ini, fim)) {
                val t = s.lastTimeUsed
                if (t > (r[s.packageName] ?: 0L)) r[s.packageName] = t
            }
        } catch (e: Exception) { }
        return r
    }

    /** Tempo de tela de cada app hoje (ms). */
    fun usoHoje(ctx: Context): Map<String, Long> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        val r = HashMap<String, Long>()
        try {
            for ((k, v) in usm.queryAndAggregateUsageStats(cal.timeInMillis, System.currentTimeMillis())) r[k] = v.totalTimeInForeground
        } catch (e: Exception) { }
        return r
    }
}
