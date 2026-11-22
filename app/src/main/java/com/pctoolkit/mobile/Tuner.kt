package com.pctoolkit.mobile

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * TUNER DE JOGOS (estilo Magic Tuner), usando os mecanismos reais do Android:
 *  - Game Mode / intervencoes do GameManager (Android 12+): modo Desempenho/Bateria,
 *    escala de resolucao do jogo (downscale) e limite de FPS (Android 13+);
 *  - escolha do driver grafico por jogo (ANGLE, driver de jogo atualizavel ou do sistema);
 *  - resolucao da tela inteira durante a sessao do Modo Jogo (funciona em qualquer celular);
 *  - renderizador do sistema (HWUI).
 * Tudo exige o modo automatico (Shizuku) ou root, porque sao comandos de nivel ADB.
 */
object Tuner {
    data class Perfil(
        var modo: String = "desempenho",   // padrao | desempenho | bateria
        var escala: Double = 1.0,          // 1.0 .. 0.5
        var fps: Int = 0,                  // 0 = sem limite
        var driver: String = "padrao",     // padrao | angle | jogo | sistema
        var resGlobal: Int = 0,            // 0 = nao mexe; 50..95 = % da resolucao da tela durante o Modo Jogo
        var turbo: Boolean = false,
        var aplicado: Boolean = false
    ) {
        fun json(): JSONObject = JSONObject().put("modo", modo).put("escala", escala).put("fps", fps).put("driver", driver).put("res", resGlobal).put("turbo", turbo).put("ok", aplicado)
        fun resumo(): String {
            if (!aplicado) return "Sem ajustes"
            val p = ArrayList<String>()
            p.add(when (modo) { "desempenho" -> "Desempenho"; "bateria" -> "Bateria"; else -> "Padrão" })
            if (escala < 0.999) p.add("${(escala * 100).toInt()}% resolução")
            p.add(if (fps > 0) "$fps FPS" else "FPS livre")
            if (driver != "padrao") p.add(nomeDriver(driver))
            if (resGlobal in 1..99) p.add("tela $resGlobal%")
            if (turbo) p.add("Turbo")
            return p.joinToString(" · ")
        }
    }

    val ESCALAS = listOf(1.0 to "Alta (100%)", 0.85 to "Média (85%)", 0.7 to "Baixa (70%)", 0.5 to "Ultra baixa (50%)")
    val LIMITES = listOf(0, 30, 45, 60, 90, 120)
    fun nomeDriver(d: String) = when (d) { "angle" -> "ANGLE (Vulkan)"; "jogo" -> "Driver de jogo"; "sistema" -> "Driver do sistema"; else -> "Padrão" }

    private fun chave(pkg: String) = "tuner_$pkg"

    fun perfil(pkg: String): Perfil {
        val s = Store.str(chave(pkg))
        if (s.isEmpty()) return Perfil()
        return try {
            val o = JSONObject(s)
            Perfil(o.optString("modo", "desempenho"), o.optDouble("escala", 1.0), o.optInt("fps", 0), o.optString("driver", "padrao"), o.optInt("res", 0), o.optBoolean("turbo", false), o.optBoolean("ok", false))
        } catch (e: Exception) { Perfil() }
    }

    fun salvar(pkg: String, p: Perfil) = Store.putStr(chave(pkg), p.json().toString())

    // ---------------- suporte do aparelho ----------------
    fun suportaGameMode() = Build.VERSION.SDK_INT >= 31
    fun suportaFps() = Build.VERSION.SDK_INT >= 33

    fun temAngle(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        if (Sh.getprop("ro.gfx.angle.supported") == "true") return true
        if (listOf("/system/lib64/libEGL_angle.so", "/system/lib/libEGL_angle.so", "/vendor/lib64/egl/libEGL_angle.so").any { File(it).exists() }) return true
        return listOf("com.android.angle", "com.google.android.angle").any { Links.instalado(PcApp.ctx, it) }
    }

    fun temDriverJogo(): Boolean = Build.VERSION.SDK_INT >= 29 && (Sh.getprop("ro.gfx.driver.0").isNotEmpty() || Sh.getprop("ro.gfx.driver.1").isNotEmpty())

    fun ehJogo(ctx: Context, pkg: String): Boolean = try {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        @Suppress("DEPRECATION")
        ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
    } catch (e: Exception) { false }

    private fun chavesDriver(): Triple<String, String, String> =
        if (Build.VERSION.SDK_INT >= 31) Triple("updatable_driver_production_opt_in_apps", "updatable_driver_production_opt_out_apps", "updatable_driver_prerelease_opt_in_apps")
        else Triple("game_driver_opt_in_apps", "game_driver_opt_out_apps", "game_driver_prerelease_opt_in_apps")

    private fun lista(v: String): MutableList<String> = v.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }.toMutableList()

    // ---------------- aplicar ----------------
    fun aplicar(con: Console, pkg: String, nome: String, p: Perfil) {
        val ctx = con.ctx
        con.titulo("Tuner · $nome")
        if (!Pro.exigir(con, "O Tuner de jogos")) return
        if (!Sh.privOk()) {
            con.aviso("O Tuner precisa do modo automático (Shizuku) ou root: são os mesmos comandos que o Magic Tuner usa.")
            if (!Priv.ativar(con)) return
        }
        con.info("Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
        // evita que o Google sobrescreva as configuracoes do GameManager
        Sh.root("device_config set_sync_disabled_for_tests persistent 2>/dev/null", 10)

        // 1) Game Mode + intervencoes
        con.subtitulo("Modo de jogo, resolução e FPS")
        val querInterv = p.escala < 0.999 || p.fps > 0
        var modo = p.modo
        if (querInterv && modo == "padrao") { modo = "desempenho"; con.info("Escala e limite de FPS só funcionam nos modos Desempenho/Bateria: usando Desempenho.") }
        if (!suportaGameMode()) {
            con.aviso("Este Android (${Build.VERSION.RELEASE}) não tem o Game Mode (precisa do Android 12+). Use a resolução da tela no Modo Jogo, que funciona em qualquer versão.")
        } else {
            if (!ehJogo(ctx, pkg)) con.aviso("O Android não marca este app como jogo: o Game Mode pode ser ignorado nele.")
            val esc = String.format(Locale.US, "%.2f", p.escala)
            val fpsTxt = if (p.fps > 0 && suportaFps()) ",fps=${p.fps}" else ""
            val num = if (modo == "bateria") 3 else 2
            val outro = if (num == 2) 3 else 2
            if (modo == "padrao") {
                if (suportaFps()) Sh.root("cmd game reset $pkg 2>/dev/null", 10)
                Sh.root("device_config delete game_overlay $pkg 2>/dev/null; cmd game mode standard $pkg", 10)
                con.ok("Modo padrão (sem intervenções)")
            } else {
                val cfg = "mode=$num,downscaleFactor=$esc$fpsTxt:mode=$outro,downscaleFactor=1.0"
                Sh.root("device_config put game_overlay $pkg \"$cfg\"", 10)
                if (suportaFps()) {
                    val a = StringBuilder("cmd game set --mode $num --downscale ${if (p.escala < 0.999) esc else "disable"}")
                    a.append(" --fps ").append(if (p.fps > 0) p.fps.toString() else "disable")
                    a.append(" $pkg")
                    val r = Sh.root(a.toString() + " 2>&1", 15)
                    if (r.out.isNotBlank() && !r.ok) con.info("cmd game: " + r.out.lines().first().take(120))
                }
                val rm = Sh.root("cmd game mode ${if (num == 2) "performance" else "battery"} $pkg 2>&1", 10)
                if (rm.out.contains("not supported", true)) con.aviso("O jogo não declarou suporte a este modo; o Android pode limitar as intervenções.")
                // verificacao
                val atual = Sh.root("cmd game mode $pkg 2>/dev/null; dumpsys game 2>/dev/null | grep -A6 \"$pkg\" | head -12", 15).out
                val modoOk = atual.contains(if (num == 2) "performance" else "battery", true) || atual.contains("mode=$num") || atual.contains("Mode: $num")
                con.linha("Modo: " + (if (num == 2) "Desempenho" else "Bateria") + if (modoOk) "  ✓ confirmado" else "", if (modoOk) T.verde else T.amarelo)
                if (p.escala < 0.999) con.linha("Resolução do jogo: ${(p.escala * 100).toInt()}%" + if (atual.contains("downscale", true) || atual.contains(esc)) "  ✓ confirmado" else "", T.verde)
                if (p.fps > 0) con.linha(if (suportaFps()) "Limite de FPS: ${p.fps}" else "Limite de FPS: precisa do Android 13+", if (suportaFps()) T.verde else T.amarelo)
                if (!modoOk) con.info("Alguns fabricantes (ex.: Samsung com o Game Booster) usam um sistema próprio e podem ignorar parte disso.")
            }
        }

        // 2) driver grafico
        con.subtitulo("Driver gráfico")
        if (Build.VERSION.SDK_INT < 29) con.info("Troca de driver exige Android 10+.")
        else {
            val (optIn, optOut, pre) = chavesDriver()
            val angPk = lista(Ajuste.ler("global", "angle_gl_driver_selection_pkgs"))
            val angVal = lista(Ajuste.ler("global", "angle_gl_driver_selection_values"))
            val idx = angPk.indexOf(pkg)
            if (idx >= 0) { angPk.removeAt(idx); if (idx < angVal.size) angVal.removeAt(idx) }
            val ins = lista(Ajuste.ler("global", optIn)); ins.remove(pkg)
            val outs = lista(Ajuste.ler("global", optOut)); outs.remove(pkg)
            val pres = lista(Ajuste.ler("global", pre)); pres.remove(pkg)
            var d = p.driver
            if (d == "angle" && !temAngle()) { con.aviso("Este celular não tem o ANGLE instalado: mantendo o driver padrão."); d = "padrao" }
            if (d == "jogo" && !temDriverJogo()) { con.aviso("Este celular não tem driver de jogo atualizável: mantendo o padrão."); d = "padrao" }
            when (d) {
                "angle" -> { angPk.add(pkg); angVal.add("angle") }
                "jogo" -> ins.add(pkg)
                "sistema" -> { outs.add(pkg); angPk.add(pkg); angVal.add("native") }
            }
            while (angVal.size < angPk.size) angVal.add("default")
            Ajuste.mudar("global", "angle_gl_driver_selection_pkgs", angPk.joinToString(","))
            Ajuste.mudar("global", "angle_gl_driver_selection_values", angVal.take(angPk.size).joinToString(","))
            Ajuste.mudar("global", optIn, ins.joinToString(","))
            Ajuste.mudar("global", optOut, outs.joinToString(","))
            Ajuste.mudar("global", pre, pres.joinToString(","))
            con.ok("Driver: " + nomeDriver(d))
            if (d == "angle") con.info("ANGLE traduz OpenGL para Vulkan. Em alguns jogos dá mais FPS e estabilidade, em outros piora: teste com o Medidor de FPS.")
            p.driver = d
        }

        // 3) extras da sessao
        if (p.resGlobal in 1..99 || p.turbo) {
            con.subtitulo("Durante o Modo Jogo")
            if (p.resGlobal in 1..99) con.ok("Resolução da tela em ${p.resGlobal}% enquanto você joga (volta ao normal no fim)")
            if (p.turbo) con.ok("Turbo: desempenho fixo do Android durante a partida")
            con.info("Esses dois só valem quando o jogo é aberto pelo Modo Jogo.")
        }

        Sh.root("am force-stop $pkg", 10)
        p.aplicado = true
        salvar(pkg, p)
        con.linha("")
        con.ok("Perfil salvo e aplicado. O jogo foi fechado para reabrir já com as mudanças.")
        con.info("Os ajustes ficam gravados no Android: valem mesmo abrindo o jogo pelo ícone, até você resetar.")
        Log2.registrar("tuner $pkg: ${p.json()}")
    }

    fun resetar(con: Console, pkg: String, nome: String) {
        con.titulo("Resetar · $nome")
        if (!Sh.privOk()) { con.aviso("Precisa do modo automático."); return }
        if (suportaFps()) Sh.root("cmd game reset $pkg 2>/dev/null", 10)
        if (suportaGameMode()) Sh.root("device_config delete game_overlay $pkg 2>/dev/null; cmd game mode standard $pkg 2>/dev/null", 10)
        if (Build.VERSION.SDK_INT >= 29) {
            val (optIn, optOut, pre) = chavesDriver()
            for (k in listOf(optIn, optOut, pre)) { val l = lista(Ajuste.ler("global", k)); if (l.remove(pkg)) Ajuste.mudar("global", k, l.joinToString(",")) }
            val angPk = lista(Ajuste.ler("global", "angle_gl_driver_selection_pkgs"))
            val angVal = lista(Ajuste.ler("global", "angle_gl_driver_selection_values"))
            val i = angPk.indexOf(pkg)
            if (i >= 0) { angPk.removeAt(i); if (i < angVal.size) angVal.removeAt(i); Ajuste.mudar("global", "angle_gl_driver_selection_pkgs", angPk.joinToString(",")); Ajuste.mudar("global", "angle_gl_driver_selection_values", angVal.joinToString(",")) }
        }
        Sh.root("am force-stop $pkg", 10)
        Store.putStr("tuner_$pkg", "")
        con.ok("$nome voltou ao padrão do Android")
    }

    // ---------------- renderizador do sistema ----------------
    fun renderizador(con: Console) {
        con.titulo("Renderizador do sistema")
        con.linha("Escolhe o motor que desenha a interface do Android e dos apps (HWUI). Não muda o motor interno de jogos Unity/Unreal; para eles use o driver gráfico no Tuner.", T.texto2)
        if (!Sh.privOk()) { if (!Priv.ativar(con)) return }
        val atual = Sh.getprop("debug.hwui.renderer").ifEmpty { "padrão" }
        con.linha("Atual: $atual")
        val ops = listOf("Padrão" to "", "OpenGL (SkiaGL)" to "skiagl", "Vulkan (SkiaVK)" to "skiavk")
        val i = con.escolher("Renderizador", ops.map { it.first })
        if (i < 0) return
        val v = ops[i].second
        Sh.root("setprop debug.hwui.renderer \"$v\"", 10)
        con.ok("Renderizador: ${ops[i].first}")
        con.info("Vale para apps abertos depois disso e volta ao padrão ao reiniciar o celular. Se algo ficar estranho, escolha Padrão.")
    }

    // ---------------- resolucao global (sessao) ----------------
    fun aplicarResolucao(pct: Int): Boolean {
        if (pct !in 40..99 || !Sh.privOk()) return false
        val out = Sh.root("wm size; wm density", 10).out
        val m = Regex("Physical size: (\\d+)x(\\d+)").find(out) ?: return false
        val d = Regex("Physical density: (\\d+)").find(out)
        val w = m.groupValues[1].toInt() * pct / 100
        val h = m.groupValues[2].toInt() * pct / 100
        var cmd = "wm size ${w}x${h}"
        if (d != null) cmd += "; wm density ${d.groupValues[1].toInt() * pct / 100}"
        val ok = Sh.root(cmd, 10).ok
        if (ok) Store.putBool("res_global", true)
        return ok
    }

    fun restaurarResolucao() {
        if (!Store.bool("res_global", false)) return
        Sh.root("wm size reset; wm density reset", 10)
        Store.putBool("res_global", false)
    }
}
