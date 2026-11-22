package com.pctoolkit.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/** SECAO FPS: otimizacao do Android, graficos, ajustes por jogo, tela, RAM, inicializacao, diagnostico. */
object Fps {

    // ---------------------------------------------------------------
    // Pacote de ajustes de jogo (equivale ao Aplicar-TweaksJogo do PC)
    // ---------------------------------------------------------------
    fun aplicarTweaksJogo(con: Console, ctx: Context) {
        val u = Undo.global
        u.setting("global", "window_animation_scale", "0.5")
        u.setting("global", "transition_animation_scale", "0.5")
        u.setting("global", "animator_duration_scale", "0.5")
        if (Build.VERSION.SDK_INT >= 31) u.setting("global", "disable_window_blurs", "1")
        u.setting("global", "wifi_scan_always_enabled", "0")
        u.setting("global", "ble_scan_always_enabled", "0")
        val (_, max) = Sensores.tela(ctx)
        if (max > 61) u.setting("system", "peak_refresh_rate", String.format(java.util.Locale.US, "%.1f", max))
        if (Sh.root("getprop debug.egl.force_msaa", 10).out.trim() == "true") u.prop("debug.egl.force_msaa", "false")
        val prof = Sh.root("getprop debug.hwui.profile", 10).out.trim()
        if (prof.isNotEmpty() && prof != "false") u.prop("debug.hwui.profile", "false")
        var n = 0
        for (j in Jogos.instalados(ctx)) { otimizarPacote(j.pkg, u); n++ }
        con.ok("Animacoes 0.5x, sem desfoque, Wi-Fi sem varredura em segundo plano, tela no Hz maximo")
        con.ok("$n jogos: Modo de Jogo DESEMPENHO, fora da otimizacao de bateria e sempre 'ativos'")
    }

    /** Ajustes de sistema para um pacote de jogo (reversiveis). */
    fun otimizarPacote(pkg: String, u: UndoLog) {
        if (Build.VERSION.SDK_INT >= 31) {
            val atual = Sh.root("cmd game mode get $pkg 2>/dev/null", 10).out.lowercase()
            if (!atual.contains("performance")) u.comando("game:$pkg", "cmd game mode performance $pkg", "cmd game mode standard $pkg")
        }
        val wl = Sh.root("dumpsys deviceidle whitelist 2>/dev/null", 15).out
        if (!wl.contains(",$pkg,")) u.comando("idle:$pkg", "dumpsys deviceidle whitelist +$pkg", "dumpsys deviceidle whitelist -$pkg")
        val bk = Sh.root("am get-standby-bucket $pkg 2>/dev/null", 10).out.trim().toIntOrNull()
        if (bk != null && bk > 10) u.comando("bucket:$pkg", "am set-standby-bucket $pkg active", "am set-standby-bucket $pkg $bk")
    }

    fun otimizarAndroid(con: Console, ctx: Context) {
        con.titulo("OTIMIZAR O ANDROID PARA JOGOS")
        if (!con.exigeRoot()) return
        con.linha("Vou aplicar (tudo reversivel no Desfazer):")
        con.linha("  - Perfil de CPU/temperatura (voce escolhe: Maximo FPS, Equilibrado frio ou Silencioso)")
        con.linha("  - Modo de Jogo do Android em DESEMPENHO para todos os jogos (Android 12+)")
        con.linha("  - Jogos fora da otimizacao de bateria e no grupo 'ativo' (o Android nao congela nem mata o jogo)")
        con.linha("  - Animacoes em 0.5x e sem desfoque de janelas (sistema mais leve e responsivo)")
        con.linha("  - Wi-Fi/Bluetooth sem varredura em segundo plano (evita picos de ping a cada minuto)")
        con.linha("  - Tela no Hz maximo; desliga 'Forcar 4x MSAA' e 'Perfil de renderizacao da GPU' se estiverem ligados")
        con.linha("Perguntas no final: economia de energia, sincronizacao automatica.")
        if (!con.ask("Continuar?")) return
        val e = Temperatura.escolherPerfil(con)
        if (e != null) {
            val n = Cpu.aplicarPerfil(e.first, e.second, Undo.global)
            if (n > 0) con.ok("Perfil de CPU: ${e.first}") else con.aviso("O kernel recusou o perfil de CPU (comum em alguns fabricantes).")
        }
        aplicarTweaksJogo(con, ctx)
        if (Sh.setting("global", "low_power") == "1" && con.ask("A ECONOMIA DE ENERGIA esta ligada (corta muito o FPS). Desligar?")) {
            Undo.global.setting("global", "low_power", "0"); con.ok("Economia de energia desligada")
        }
        Log2.registrar("Otimizar Android aplicado")
        con.linha("")
        con.linha("Pronto. Para reverter: FPS > Desfazer.", C.verde)
    }

    // ---------------------------------------------------------------
    // Graficos dos jogos
    // ---------------------------------------------------------------
    private val rxAntiCheatSensivel = Regex("com\\.tencent\\.ig|com\\.pubg|pubgm|vng\\.pubg|rekoo|krafton|com\\.dts\\.freefire|garena|activision|riotgames|levelinfinite", RegexOption.IGNORE_CASE)

    class Cfg(val nome: String, val tipo: String, val arq: String, val pkg: String)

    private fun configs(ctx: Context): List<Cfg> {
        val r = ArrayList<Cfg>()
        val mc = Sh.root("ls ${Sd.base}/Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftpe/options.txt /data/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftpe/options.txt 2>/dev/null", 15).out
        for (l in mc.lines().filter { it.isNotBlank() }) r.add(Cfg("Minecraft Bedrock (options.txt)", "mcb", l.trim(), "com.mojang.minecraftpe"))
        val rb = Sh.root("find /data/data/com.roblox.client -name 'GlobalBasicSettings_*.xml' 2>/dev/null", 30).out
        for (l in rb.lines().filter { it.isNotBlank() }) r.add(Cfg("Roblox (" + l.substringAfterLast('/') + ")", "rbx", l.trim(), "com.roblox.client"))
        val ue = Sh.root("find ${Sd.base}/Android/data -path '*Saved/Config/Android*' -name GameUserSettings.ini 2>/dev/null", 60).out
        for (l in ue.lines().filter { it.isNotBlank() }) {
            val pkg = l.removePrefix("${Sd.base}/Android/data/").substringBefore('/')
            if (rxAntiCheatSensivel.containsMatchIn(pkg)) continue
            r.add(Cfg("Unreal Engine: " + Jogos.nome(ctx, pkg), "ue", l.trim(), pkg))
        }
        return r
    }

    private fun backup(arq: String) {
        val bak = "$arq.bak-fps"
        if (!Sh.root("[ -f ${Sh.q(bak)} ]", 10).ok) Sh.root("cp -p ${Sh.q(arq)} ${Sh.q(bak)}", 20)
        Undo.global.registrar("arq:$arq", "[ -f ${Sh.q(bak)} ] && cat ${Sh.q(bak)} > ${Sh.q(arq)} && rm -f ${Sh.q(bak)}")
    }

    private fun setChaveDois(linhas: MutableList<String>, chave: String, valor: String): Int {
        var n = 0
        for (i in linhas.indices) if (linhas[i].startsWith("$chave:")) { linhas[i] = "$chave:$valor"; n++ }
        return n
    }

    private fun setIni(linhas: MutableList<String>, chave: String, valor: String): Int {
        var n = 0
        val rx = Regex("^\\s*" + Regex.escape(chave) + "\\s*=")
        for (i in linhas.indices) if (rx.containsMatchIn(linhas[i])) { linhas[i] = "$chave=$valor"; n++ }
        return n
    }

    fun otimizarGraficos(con: Console, ctx: Context) {
        con.titulo("OTIMIZAR GRAFICOS DOS JOGOS")
        if (!con.exigeRoot()) return
        con.linha("Duas formas:")
        con.linha("  A) Arquivo de configuracao do proprio jogo: Minecraft Bedrock, Roblox e jogos Unreal Engine")
        con.linha("  B) Intervencao do Android (qualquer jogo, Android 13+): escala de resolucao e limite de FPS")
        con.info("Jogos com anti-cheat sensivel (PUBG/BGMI, Free Fire, CoD, Riot) sao pulados no modo A: editar o arquivo pode dar ban.")
        val modo = con.escolher("Qual?", listOf("A) Arquivos de configuracao dos jogos", "B) Escala de resolucao / limite de FPS (qualquer jogo)"))
        if (modo == 1) { intervencao(con, ctx, null); return }
        if (modo != 0) return
        val cands = configs(ctx)
        if (cands.isEmpty()) {
            con.aviso("Nao encontrei arquivos compativeis. Abra o jogo uma vez (para ele criar o arquivo), feche e rode de novo.")
            con.info("Para outros jogos use a opcao B ou baixe a qualidade no menu do jogo.")
            return
        }
        val sel = con.varios("Quais otimizar?", cands.map { it.nome }, true)
        if (sel.isEmpty()) return
        val preset = if (con.escolher("Preset", listOf("MINIMO (maximo FPS)", "BAIXO (equilibrado)")) == 1) "Baixo" else "Minimo"
        val fps = con.numero("Limite de FPS (0 = sem limite; 60 = menos calor)", 0, 0, 240)
        for (i in sel) {
            val c = cands[i]
            if (Sh.root("pidof ${c.pkg}", 10).out.isNotBlank()) {
                if (con.ask("${c.nome}: o jogo esta aberto e sobrescreveria as mudancas. Fechar o jogo agora?")) Sh.root("am force-stop ${c.pkg}", 15)
                else { con.aviso("Pulei ${c.nome}"); continue }
            }
            val txt = Sh.ler(c.arq)
            if (txt.isEmpty()) { con.aviso("${c.nome}: nao consegui ler o arquivo"); continue }
            backup(c.arq)
            var n = 0
            var novo = txt
            when (c.tipo) {
                "mcb" -> {
                    val l = txt.lines().toMutableList()
                    n += setChaveDois(l, "gfx_viewdistance", if (preset == "Baixo") "160" else "96")
                    for (k in listOf("gfx_fancygraphics", "gfx_smoothlighting", "gfx_fancyskies", "gfx_transparentleaves", "gfx_vsync", "gfx_fancybubbles", "gfx_renderclouds")) n += setChaveDois(l, k, "0")
                    n += setChaveDois(l, "gfx_max_framerate", fps.toString())
                    novo = l.joinToString("\n")
                }
                "rbx" -> {
                    val q = if (preset == "Baixo") "3" else "1"
                    for (nome in listOf("SavedQualityLevel", "GraphicsQualityLevel", "QualityLevel")) {
                        val rx = Regex("(<(?:token|int) name=\"$nome\">)\\d+(</(?:token|int)>)")
                        n += rx.findAll(novo).count()
                        novo = rx.replace(novo, "$1$q$2")
                    }
                }
                "ue" -> {
                    val l = txt.lines().toMutableList()
                    val q = if (preset == "Baixo") "1" else "0"
                    n += setIni(l, "sg.ResolutionQuality", if (preset == "Baixo") "100.000000" else "75.000000")
                    for (g in listOf("ViewDistanceQuality", "AntiAliasingQuality", "ShadowQuality", "PostProcessQuality", "TextureQuality", "EffectsQuality", "FoliageQuality", "ShadingQuality", "GlobalIlluminationQuality", "ReflectionQuality")) n += setIni(l, "sg.$g", q)
                    n += setIni(l, "bUseVSync", "False")
                    n += setIni(l, "FrameRateLimit", if (fps > 0) "$fps.000000" else "0.000000")
                    novo = l.joinToString("\n")
                }
            }
            if (n > 0 && Sh.gravar(c.arq, novo)) con.ok("${c.nome}: $n ajustes aplicados (backup .bak-fps)") else con.aviso("${c.nome}: nenhuma chave conhecida encontrada")
        }
        con.linha("")
        con.linha("Para reverter: FPS > Desfazer.", C.amarelo)
    }

    /** Intervencao do Modo de Jogo do Android: escala de resolucao e limite de FPS para qualquer jogo. */
    fun intervencao(con: Console, ctx: Context, jogoPre: Jogo?) {
        if (Build.VERSION.SDK_INT < 33) { con.aviso("Escala de resolucao/limite de FPS pelo sistema exige Android 13 ou mais novo."); return }
        val j = jogoPre ?: Jogos.escolher(con, ctx) ?: return
        val esc = con.escolher("Escala de resolucao para ${j.nome}", listOf("100% (nativa)", "90%", "80% (recomendado: quase igual e bem mais leve)", "70%", "60%", "50%"))
        if (esc < 0) return
        val escala = listOf("1.0", "0.9", "0.8", "0.7", "0.6", "0.5")[esc]
        val f = con.escolher("Limite de FPS pelo sistema", listOf("Sem limite", "30", "45", "60", "90", "120"))
        val fpsArg = when (f) { 1 -> " --fps 30"; 2 -> " --fps 45"; 3 -> " --fps 60"; 4 -> " --fps 90"; 5 -> " --fps 120"; else -> "" }
        Undo.global.registrar("gameset:${j.pkg}", "cmd game reset ${j.pkg}")
        val r = Sh.root("cmd game set --mode 2 --downscale $escala$fpsArg ${j.pkg}", 15)
        if (r.ok && !r.out.contains("rror")) {
            con.ok("${j.nome}: escala $escala$fpsArg (vale na proxima vez que abrir o jogo)")
            con.info("Se o jogo ficar estranho, desfaca em FPS > Desfazer. Nem todo jogo aceita a intervencao.")
        } else con.aviso("O Android recusou (" + (r.err + " " + r.out).trim().take(150) + "). Esse aparelho/jogo pode nao suportar.")
    }

    // ---------------------------------------------------------------
    // Ajustes por jogo
    // ---------------------------------------------------------------
    fun ajustesPorJogo(con: Console, ctx: Context) {
        con.titulo("AJUSTES POR JOGO")
        if (!con.exigeRoot()) return
        val j = Jogos.escolher(con, ctx) ?: return
        con.linha("Jogo: ${j.nome} (${j.pkg})", C.verde)
        val ops = listOf(
            "Modo de Jogo DESEMPENHO + fora da otimizacao de bateria + sempre ativo",
            "Driver de video atualizavel (Game Driver) para este jogo - pode dar mais FPS/estabilidade",
            "Escala de resolucao / limite de FPS pelo sistema (Android 13+)",
            "Restringir este jogo em segundo plano quando voce sai (economiza bateria/RAM)"
        )
        val sel = con.varios("O que aplicar?", ops)
        val u = Undo.global
        if (sel.contains(0)) { otimizarPacote(j.pkg, u); con.ok("Modo DESEMPENHO, sem otimizacao de bateria, grupo ativo") }
        if (sel.contains(1)) {
            for (k in listOf("updatable_driver_production_opt_in_apps", "game_driver_opt_in_apps")) {
                val atual = Sh.setting("global", k)
                val lista = atual.split(',').filter { it.isNotBlank() }.toMutableList()
                if (!lista.contains(j.pkg)) { lista.add(j.pkg); u.setting("global", k, lista.joinToString(",")) }
            }
            con.ok("Driver atualizavel ativado para o jogo (so tem efeito se o fabricante distribui esse driver)")
        }
        if (sel.contains(2)) intervencao(con, ctx, j)
        if (sel.contains(3)) { u.comando("bg:${j.pkg}", "cmd appops set ${j.pkg} RUN_ANY_IN_BACKGROUND ignore", "cmd appops set ${j.pkg} RUN_ANY_IN_BACKGROUND allow"); con.ok("Restrito em segundo plano") }
        Log2.registrar("Ajustes por jogo: ${j.pkg}")
    }

    // ---------------------------------------------------------------
    // Tela: Hz maximo e limite de FPS ideal
    // ---------------------------------------------------------------
    fun tela(con: Console, ctx: Context) {
        con.titulo("TELA: TAXA DE ATUALIZACAO E LIMITE DE FPS IDEAL")
        val (atual, max) = Sensores.tela(ctx)
        con.linha(String.format(java.util.Locale.US, "Tela agora: %.0f Hz  |  maxima suportada: %.0f Hz", atual, max), if (max > atual + 1) C.amarelo else C.verde)
        if (Sh.temRoot()) {
            con.info("peak_refresh_rate = " + Sh.setting("system", "peak_refresh_rate").ifEmpty { "(padrao)" } + "  |  min_refresh_rate = " + Sh.setting("system", "min_refresh_rate").ifEmpty { "(padrao)" })
        }
        con.linha("")
        con.linha("Limite de FPS recomendado (configure DENTRO do jogo):", C.ciano)
        con.linha(String.format(java.util.Locale.US, "  Tela de %.0f Hz -> escolha %s FPS no jogo se o celular aguentar sem esquentar.", max, if (max >= 119) "120 ou 90" else if (max >= 89) "90" else "60"))
        con.linha("  Esquentou ou o FPS oscila muito? Use 60: fica mais constante e frio (o Modo Jogo mostra se e calor).")
        con.info("No celular quem limita o FPS e o jogo: se ele so oferece 60, a tela de 120 Hz nao muda o FPS do jogo.")
        if (max > 61) {
            if (con.ask(String.format(java.util.Locale.US, "Deixar a tela liberada para ate %.0f Hz (peak_refresh_rate)?", max))) {
                if (!con.exigeRoot()) return
                Undo.global.setting("system", "peak_refresh_rate", String.format(java.util.Locale.US, "%.1f", max))
                con.ok("Tela liberada ate o maximo")
                if (con.ask(String.format(java.util.Locale.US, "FORCAR %.0f Hz sempre (min_refresh_rate)? Mais fluido em jogos que o Android baixa sozinho para 60, mas gasta mais bateria.", max))) {
                    Undo.global.setting("system", "min_refresh_rate", String.format(java.util.Locale.US, "%.1f", max))
                    con.ok("Hz maximo forcado")
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Liberar RAM
    // ---------------------------------------------------------------
    fun liberarRam(con: Console, ctx: Context) {
        con.titulo("LIBERAR RAM")
        if (!con.exigeRoot()) return
        val a = Sensores.memoria(ctx)
        Sh.root("am kill-all 2>/dev/null; sync; echo 3 > /proc/sys/vm/drop_caches", 30)
        con.sleep(1500)
        val b = Sensores.memoria(ctx)
        con.ok("RAM disponivel: ${fmtTam(a.availMem)} -> ${fmtTam(b.availMem)} (de ${fmtTam(b.totalMem)})")
        con.info("Fechei apps em segundo plano que estavam so ocupando memoria e limpei o cache de arquivos.")
        con.info("Efeito temporario: ajuda quando a RAM esta apertada logo antes de abrir o jogo.")
    }

    // ---------------------------------------------------------------
    // Inicializacao: apps que abrem sozinhos ao ligar
    // ---------------------------------------------------------------
    fun inicializacao(con: Console, ctx: Context) {
        con.titulo("APPS QUE ABREM COM O CELULAR")
        if (!con.exigeRoot()) return
        con.info("Cada um consome RAM/bateria e atrasa o celular. Desativar NAO desinstala: da para reativar aqui.")
        val pm = ctx.packageManager
        val comps = LinkedHashMap<String, ComponentName>()
        for (acao in listOf(Intent.ACTION_BOOT_COMPLETED, "android.intent.action.LOCKED_BOOT_COMPLETED", "android.intent.action.QUICKBOOT_POWERON")) {
            @Suppress("DEPRECATION")
            val lst = pm.queryBroadcastReceivers(Intent(acao), PackageManager.MATCH_DISABLED_COMPONENTS)
            for (ri in lst) {
                val ai = ri.activityInfo ?: continue
                if ((ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
                if (ai.packageName == ctx.packageName) continue
                val cn = ComponentName(ai.packageName, ai.name)
                comps[cn.flattenToString()] = cn
            }
        }
        if (comps.isEmpty()) { con.ok("Nenhum app de usuario abre sozinho com o celular."); return }
        val lista = comps.values.toList().sortedBy { Jogos.nome(ctx, it.packageName).lowercase() }
        val rotulos = lista.map {
            val st = pm.getComponentEnabledSetting(it)
            val ativo = st != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            (if (ativo) "[ATIVO]  " else "[desativado]  ") + Jogos.nome(ctx, it.packageName) + "  (" + it.shortClassName + ")"
        }
        for (r in rotulos) con.linha(r, if (r.startsWith("[ATIVO]")) C.texto else C.cinza)
        val sel = con.varios("Alternar (ativo <-> desativado)", rotulos)
        for (i in sel) {
            val cn = lista[i]
            val f = cn.flattenToString()
            val ativo = pm.getComponentEnabledSetting(cn) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (ativo) { Undo.global.comando("boot:$f", "pm disable ${Sh.q(f)}", "pm enable ${Sh.q(f)}"); con.ok("Desativado: ${Jogos.nome(ctx, cn.packageName)}") }
            else { Sh.root("pm enable ${Sh.q(f)}", 15); con.ok("Reativado: ${Jogos.nome(ctx, cn.packageName)}") }
        }
        if (con.ask("Quer tambem RESTRINGIR apps em segundo plano (eles param de rodar quando voce nao esta usando)?")) {
            val apps = Jogos.todosApps(ctx)
            val s2 = con.varios("Restringir em segundo plano (ex.: redes sociais, lojas, apps que voce quase nao usa)", apps.map { it.nome })
            for (i in s2) {
                val p = apps[i].pkg
                Undo.global.comando("bg:$p", "cmd appops set $p RUN_ANY_IN_BACKGROUND ignore", "cmd appops set $p RUN_ANY_IN_BACKGROUND allow")
                con.ok("Restrito: ${apps[i].nome}")
            }
        }
    }

    // ---------------------------------------------------------------
    // Diagnostico completo
    // ---------------------------------------------------------------
    fun diagnostico(con: Console, ctx: Context) {
        con.titulo("DIAGNOSTICO DO CELULAR")
        val recs = ArrayList<String>()
        con.linha("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}  |  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL else Build.HARDWARE
        con.linha("Processador: $soc  |  ${Runtime.getRuntime().availableProcessors()} nucleos")
        val root = Sh.temRoot()
        con.linha("Root: " + if (root) "SIM" else "NAO (a maioria das correcoes precisa de root)", if (root) C.verde else C.vermelho)
        if (root) con.linha("Kernel: " + Sh.root("uname -r", 10).out)
        val m = Sensores.memoria(ctx)
        con.linha("RAM: ${fmtTam(m.availMem)} livre de ${fmtTam(m.totalMem)}" + if (m.lowMemory) "  (POUCA MEMORIA AGORA)" else "")
        if (m.totalMem < 5L * 1024 * 1024 * 1024) recs.add("Menos de 6 GB de RAM: feche apps antes de jogar (Modo Jogo faz isso) e use graficos medios.")
        val (livre, total) = Sensores.armazenamento()
        val pct = if (total > 0) livre * 100 / total else 100
        con.linha("Armazenamento: ${fmtTam(livre)} livre de ${fmtTam(total)} ($pct%)")
        if (pct < 10) recs.add("Armazenamento com so $pct% livre: o celular fica lento e os jogos travam. Rode a Limpeza profunda.")
        val (hz, maxHz) = Sensores.tela(ctx)
        con.linha(String.format(java.util.Locale.US, "Tela: %.0f Hz (maximo %.0f Hz)", hz, maxHz))
        if (maxHz > hz + 1) recs.add("A tela nao esta no Hz maximo: FPS > Tela corrige.")
        val b = Sensores.bateria(ctx)
        con.linha(String.format(java.util.Locale.US, "Bateria: %d%%, %.1f C%s", b.nivel, b.tempC, if (b.carregando) ", carregando" else ""))
        if (root) {
            val ciclos = Sh.ler("/sys/class/power_supply/battery/cycle_count").trim()
            val cf = Sh.ler("/sys/class/power_supply/battery/charge_full").trim().toLongOrNull()
            val cd = Sh.ler("/sys/class/power_supply/battery/charge_full_design").trim().toLongOrNull()
            if (ciclos.isNotEmpty()) con.linha("  Ciclos de carga: $ciclos")
            if (cf != null && cd != null && cd > 0) {
                val saude = cf * 100 / cd
                con.linha("  Saude da bateria: $saude% da capacidade original", if (saude < 80) C.amarelo else C.texto)
                if (saude < 80) recs.add("Bateria com $saude% da capacidade: bateria gasta esquenta mais e o celular limita o desempenho.")
            }
        }
        val st = Sensores.statusTermico(ctx)
        con.linha("Estado termico: " + Sensores.nomeStatusTermico(st))
        if (st >= 2) recs.add("O celular ja esta limitando o desempenho por calor: veja o menu Temperatura.")
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isPowerSaveMode) { con.linha("Economia de energia: LIGADA (corta FPS)", C.amarelo); recs.add("Desligue a economia de energia para jogar.") }
        if (root) {
            con.linha("CPU:")
            for (p in Cpu.politicas()) con.linha("  CPUs ${p.cpus}: ${p.gov}, ${p.curMax / 1000} de ${p.max / 1000} MHz")
            con.linha("Animacoes: " + Sh.setting("global", "animator_duration_scale").ifEmpty { "1.0 (padrao)" })
            con.linha("Maiores consumidores de RAM agora:")
            val ps = Sh.root("ps -A -o RSS,NAME 2>/dev/null | sort -rn | head -7", 15).out
            for (l in ps.lines().filter { it.isNotBlank() }) {
                val p = l.trim().split(Regex("\\s+"), 2)
                val kb = p[0].toLongOrNull() ?: continue
                con.linha(String.format(java.util.Locale.US, "  %9s  %s", fmtTam(kb * 1024), Jogos.nome(ctx, p.getOrElse(1) { "" })))
            }
        }
        con.linha("")
        if (recs.isEmpty()) con.ok("Nada critico encontrado.") else { con.linha("Recomendacoes:", C.amarelo); for (r in recs) con.linha("  - $r", C.amarelo) }
        con.linha("")
        con.linha("Para uma NOTA de FPS e correcao automatica: FPS > Raio-X de FPS.", C.ciano)
    }

    // ---------------------------------------------------------------
    // Benchmark (roda dentro do servico para continuar medindo com o jogo na frente)
    // ---------------------------------------------------------------
    fun benchmark(con: Console, ctx: Context) {
        con.titulo("MEDIR FPS / BENCHMARK ANTES x DEPOIS")
        if (!con.exigeRoot()) return
        con.linha("Mede o FPS REAL do jogo pelo contador de quadros do Android (SurfaceFlinger), CPU, GPU e temperatura.")
        con.info("Teste justo: meca no MESMO lugar do jogo, mesmo tempo, antes e depois de otimizar. O app compara sozinho.")
        if (!Sensores.contadorFuncionando()) {
            con.aviso("O contador de quadros nao respondeu neste aparelho. Ainda meco CPU/GPU/temperatura, mas sem FPS.")
            if (!con.ask("Continuar mesmo assim?")) return
        }
        if (GameService.rodando) { con.aviso("Ja existe uma sessao/medicao rodando."); return }
        val j = Jogos.escolher(con, ctx) ?: return
        val seg = con.numero("Duracao da medicao em segundos", 60, 20, 600)
        val atraso = con.numero("Segundos para voce entrar na partida antes de comecar", 25, 5, 180)
        val rot = con.texto("Nome desta medicao (ex: antes, depois, perfil frio)", "", "") ?: ""
        val i = Intent(ctx, GameService::class.java)
            .putExtra("modo", "bench").putExtra("pkg", j.pkg).putExtra("nome", j.nome)
            .putExtra("seg", seg).putExtra("atraso", atraso).putExtra("rotulo", rot)
        ctx.startForegroundService(i)
        con.ok("Abrindo ${j.nome}. A medicao comeca em $atraso s e dura $seg s. Um aviso (vibracao + notificacao) marca o fim.")
        con.info("Voce pode voltar para ca depois: o resultado aparece aqui e no Historico.")
        con.sleep(3000)
        while (GameService.rodando && !con.cancelado) con.sleep(1000)
        val a = Store.lerArr("bench.json")
        if (a.length() == 0) return
        val o = a.getJSONObject(a.length() - 1)
        if (o.optString("pkg") != j.pkg) return
        mostrarResultado(con, o)
        val ant = (a.length() - 2 downTo 0).map { a.getJSONObject(it) }.firstOrNull { it.optString("pkg") == j.pkg }
        if (ant != null && ant.optDouble("fps", 0.0) > 0 && o.optDouble("fps", 0.0) > 0) {
            val dF = (o.getDouble("fps") - ant.getDouble("fps")) / ant.getDouble("fps") * 100
            val dM = if (ant.optDouble("min", 0.0) > 0) (o.optDouble("min", 0.0) - ant.getDouble("min")) / ant.getDouble("min") * 100 else 0.0
            val cor = if (dF > 2) C.verde else if (dF < -2) C.vermelho else C.cinza
            con.linha("")
            con.linha("COMPARADO COM A MEDICAO ANTERIOR (${ant.optString("data")} ${ant.optString("rotulo")}):", C.ciano)
            con.linha(String.format(java.util.Locale.US, "  FPS medio: %.1f -> %.1f (%+.1f%%)", ant.getDouble("fps"), o.getDouble("fps"), dF), cor)
            con.linha(String.format(java.util.Locale.US, "  FPS no pior momento: %.1f -> %.1f (%+.1f%%)", ant.optDouble("min", 0.0), o.optDouble("min", 0.0), dM), cor)
            con.info("Diferencas de ate ~3% sao variacao normal.")
        }
    }

    fun mostrarResultado(con: Console, o: JSONObject) {
        con.linha("")
        con.linha("=== RESULTADO: ${o.optString("nome")} ===", C.verde)
        val fps = o.optDouble("fps", 0.0)
        if (fps > 0) {
            con.linha(String.format(java.util.Locale.US, "  FPS medio:            %.1f", fps), C.verde)
            con.linha(String.format(java.util.Locale.US, "  FPS no pior momento:  %.1f   (o que voce sente como travadinha)", o.optDouble("min", 0.0)))
            con.linha(String.format(java.util.Locale.US, "  Estabilidade:         %d%%", o.optInt("estab", 0)), if (o.optInt("estab", 0) >= 80) C.verde else C.amarelo)
        } else con.linha("  FPS: nao disponivel neste aparelho", C.cinza)
        con.linha(String.format(java.util.Locale.US, "  CPU (nucleo mais usado): %d%%  |  GPU: %s  |  bateria ate %.1f C  |  queda de clock: %d%%",
            o.optInt("core", 0), if (o.has("gpu")) o.optInt("gpu").toString() + "%" else "n/d", o.optDouble("picoBat", 0.0), o.optInt("queda", 0)))
        mostrarGargalo(con, o.optString("gargalo"))
    }

    fun mostrarGargalo(con: Console, g: String) {
        when (g) {
            "TELA" -> con.linha("GARGALO: nenhum - o jogo ja roda no limite da tela/do jogo. Otimo!", C.verde)
            "GPU" -> { con.linha("GARGALO: PLACA DE VIDEO (GPU no limite).", C.amarelo); con.info("Mais FPS: baixe resolucao/escala (FPS > Graficos, opcao B), sombras e efeitos.") }
            "CPU" -> { con.linha("GARGALO: CPU (a GPU fica esperando).", C.amarelo); con.info("Mais FPS: Modo Jogo com TURBO, perfil MAXIMO FPS, feche apps, baixe distancia de visao/efeitos de multidao.") }
            "TERMICO" -> { con.linha("GARGALO: CALOR - o celular reduziu o clock durante o jogo.", C.vermelho); con.info("Tire a capinha, nao jogue carregando, use perfil FRIO e limite o FPS em 60: fica mais constante.") }
            "LIMITE" -> { con.linha("Nem CPU nem GPU no limite: o FPS esta preso pelo proprio jogo.", C.ciano); con.info("Procure a opcao de FPS/'taxa de quadros' no jogo e escolha 60/90/120.") }
            else -> {}
        }
    }

    fun historico(con: Console) {
        con.titulo("HISTORICO")
        con.linha("Medicoes de FPS (benchmark):", C.amarelo)
        val b = Store.lerArr("bench.json")
        if (b.length() == 0) con.info("nenhuma ainda (FPS > Medir FPS)")
        for (i in maxOf(0, b.length() - 15) until b.length()) {
            val o = b.getJSONObject(i)
            con.linha(String.format(java.util.Locale.US, "  %s  %-20s %-10s FPS %5.1f  pior %5.1f  estab %3d%%  %s", o.optString("data"), o.optString("nome").take(20), o.optString("rotulo").take(10), o.optDouble("fps", 0.0), o.optDouble("min", 0.0), o.optInt("estab"), o.optString("gargalo")))
        }
        con.linha("")
        con.linha("Sessoes do Modo Jogo:", C.amarelo)
        val s = Store.lerArr("sessoes.json")
        if (s.length() == 0) con.info("nenhuma ainda")
        for (i in maxOf(0, s.length() - 15) until s.length()) {
            val o = s.getJSONObject(i)
            con.linha(String.format(java.util.Locale.US, "  %s  %-20s %3d min  %-10s bateria ate %4.1f C  FPS %5.1f  %s", o.optString("data"), o.optString("nome").take(20), o.optInt("dur"), o.optString("perfil"), o.optDouble("picoBat", 0.0), o.optDouble("fps", 0.0), o.optString("gargalo")))
        }
    }
}
