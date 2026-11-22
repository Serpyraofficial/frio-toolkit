package com.pctoolkit.mobile

import android.content.Context
import android.os.Build

class Politica(
    val dir: String, val govs: List<String>, val freqs: List<Int>, val max: Int, val min: Int,
    val gov: String, val curMax: Int, val curMin: Int, val cpus: String
)

/** Perfis de CPU: equivalente aos planos de energia do Windows (MAXIMO FPS / EQUILIBRADO FRIO / SILENCIOSO). */
object Cpu {
    fun politicas(): List<Politica> {
        val sc = "for p in \$(ls -d /sys/devices/system/cpu/cpufreq/policy* 2>/dev/null || ls -d /sys/devices/system/cpu/cpu[0-9]*/cpufreq 2>/dev/null); do " +
            "echo \"P=\$p\"; echo \"G=\$(cat \$p/scaling_available_governors 2>/dev/null)\"; echo \"F=\$(cat \$p/scaling_available_frequencies 2>/dev/null)\"; " +
            "echo \"X=\$(cat \$p/cpuinfo_max_freq 2>/dev/null)\"; echo \"N=\$(cat \$p/cpuinfo_min_freq 2>/dev/null)\"; echo \"CG=\$(cat \$p/scaling_governor 2>/dev/null)\"; " +
            "echo \"CX=\$(cat \$p/scaling_max_freq 2>/dev/null)\"; echo \"CN=\$(cat \$p/scaling_min_freq 2>/dev/null)\"; echo \"R=\$(cat \$p/related_cpus 2>/dev/null)\"; done"
        val out = Sh.root(sc, 20).out
        val r = ArrayList<Politica>()
        var m = HashMap<String, String>()
        fun fecha() {
            val d = m["P"] ?: return
            r.add(Politica(
                d,
                (m["G"] ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() },
                (m["F"] ?: "").split(Regex("\\s+")).mapNotNull { it.toIntOrNull() },
                (m["X"] ?: "0").trim().toIntOrNull() ?: 0,
                (m["N"] ?: "0").trim().toIntOrNull() ?: 0,
                (m["CG"] ?: "").trim(),
                (m["CX"] ?: "0").trim().toIntOrNull() ?: 0,
                (m["CN"] ?: "0").trim().toIntOrNull() ?: 0,
                (m["R"] ?: "").trim()
            ))
        }
        for (l in out.lines()) {
            val i = l.indexOf('=')
            if (i <= 0) continue
            val k = l.substring(0, i)
            if (k == "P") { fecha(); m = HashMap() }
            m[k] = l.substring(i + 1)
        }
        fecha()
        return r
    }

    private fun freqAlvo(p: Politica, pct: Int): Int {
        val alvo = (p.max.toLong() * pct / 100).toInt()
        val cand = p.freqs.filter { it <= alvo }.maxOrNull()
        return cand ?: alvo
    }

    /** Aplica o perfil guardando os valores originais em [log]. Devolve quantas escritas deram certo. */
    fun aplicarPerfil(perfil: String, pctCustom: Int, log: UndoLog): Int {
        val pols = politicas()
        var ok = 0
        for (p in pols) {
            log.registrar("sys:${p.dir}/scaling_governor", "echo ${Sh.q(p.gov)} > ${p.dir}/scaling_governor")
            log.registrar("sys:${p.dir}/scaling_min_freq", "echo ${p.curMin} > ${p.dir}/scaling_min_freq")
            log.registrar("sys:${p.dir}/scaling_max_freq", "echo ${p.curMax} > ${p.dir}/scaling_max_freq")
            when (perfil) {
                "Maximo" -> {
                    if (Sh.root("echo ${p.max} > ${p.dir}/scaling_max_freq").ok) ok++
                    if (p.govs.contains("performance") && Sh.root("echo performance > ${p.dir}/scaling_governor").ok) ok++
                }
                else -> {
                    val pct = when (perfil) { "Frio" -> 85; "Silencioso" -> 70; else -> pctCustom.coerceIn(30, 100) }
                    if (p.gov == "performance") {
                        val g = when {
                            p.govs.contains("schedutil") -> "schedutil"
                            p.govs.contains("interactive") -> "interactive"
                            else -> p.govs.firstOrNull { it != "performance" } ?: ""
                        }
                        if (g.isNotEmpty()) Sh.root("echo $g > ${p.dir}/scaling_governor")
                    }
                    if (Sh.root("echo ${freqAlvo(p, pct)} > ${p.dir}/scaling_max_freq").ok) ok++
                }
            }
        }
        if (perfil == "Maximo") {
            val g = "/sys/class/kgsl/kgsl-3d0/devfreq"
            val disp = Sh.ler("$g/available_governors")
            if (disp.contains("performance")) { if (log.sysfs("$g/governor", "performance")) ok++ }
        }
        Store.putStr("perfil_ativo", perfil)
        Log2.registrar("Perfil de CPU aplicado: $perfil")
        return ok
    }

    /** Nucleos "grandes" (maior clock maximo) - usados para prender o jogo. Devolve mascara hex para taskset. */
    fun mascaraNucleosGrandes(): Pair<String, Int>? {
        val pols = politicas()
        if (pols.size < 2) return null
        val maxF = pols.maxOf { it.max }
        val grandes = pols.filter { it.max >= maxF * 0.8 }
        if (grandes.size == pols.size) return null
        var mask = 0L
        var n = 0
        for (p in grandes) {
            for (tok in p.cpus.split(Regex("\\s+"))) {
                val c = tok.toIntOrNull() ?: continue
                if (c < 63) { mask = mask or (1L shl c); n++ }
            }
        }
        if (mask == 0L) return null
        return Pair(java.lang.Long.toHexString(mask), n)
    }
}

object Temperatura {
    fun diagnostico(con: Console, ctx: Context) {
        con.titulo("DIAGNOSTICO TERMICO")
        if (!con.exigeRoot()) return
        con.linha("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}  |  Android ${Build.VERSION.RELEASE}")
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL else Build.HARDWARE
        con.linha("Processador (SoC): $soc")
        val a = Sensores.ler()
        val b = Sensores.bateria(ctx)
        val tc = Sensores.tempCpu(a)
        con.linha("")
        con.linha("Agora:", C.amarelo)
        con.linha("  CPU: " + (if (tc != null) String.format(java.util.Locale.US, "%.0f C", tc) else "n/d"), Sensores.corTemp(tc))
        con.linha(String.format(java.util.Locale.US, "  Bateria: %.1f C (%d%%%s)", b.tempC, b.nivel, if (b.carregando) ", carregando" else ""), Sensores.corBateria(b.tempC))
        val st = Sensores.statusTermico(ctx)
        con.linha("  Estado termico do Android: " + Sensores.nomeStatusTermico(st), if (st >= 2) C.vermelho else C.verde)
        if (a.gpu != null) con.linha("  Placa de video (GPU): ${a.gpu}% de uso")
        con.info("Referencia: bateria ate 40 C ok | 40-45 C quente | 45+ C o celular corta desempenho e carga.")
        con.info("CPU: ate ~70 C jogando e bom | 70-85 C quente | 90+ C throttling forte.")
        con.linha("")
        con.linha("Sensores mais quentes:", C.amarelo)
        for (z in a.zonas.sortedByDescending { it.c }.take(8)) con.linha(String.format(java.util.Locale.US, "  %5.1f C  %s", z.c, z.tipo), Sensores.corTemp(z.c))
        con.linha("")
        con.linha("Clusters da CPU (governador / clock maximo permitido / maximo do chip):", C.amarelo)
        for (p in Cpu.politicas()) {
            val lim = if (p.max > 0) p.curMax * 100 / p.max else 100
            con.linha("  CPUs ${p.cpus}: ${p.gov}  |  ${p.curMax / 1000} / ${p.max / 1000} MHz  (${lim}%)", if (lim < 95) C.amarelo else C.texto)
        }
        con.linha("")
        con.linha("Quem mais usa a CPU agora:", C.amarelo)
        val top = Sh.root("top -b -n 1 -m 8 2>/dev/null", 15).out.lines().filter { it.isNotBlank() }
        val ini = top.indexOfFirst { it.contains("PID") && it.contains("CPU") }
        if (ini >= 0) for (l in top.drop(ini).take(9)) con.info(l.trim().take(110)) else con.info("(top indisponivel)")
        con.linha("")
        con.linha("O que fazer:", C.ciano)
        var dicas = 0
        if (b.tempC >= 42 || st >= 2 || (tc != null && tc >= 85)) {
            con.linha("  - Esta quente: use o perfil EQUILIBRADO FRIO e limite o FPS do jogo (60 em vez de 90/120)."); dicas++
            con.linha("  - Tire a capinha para jogar, nao jogue carregando, e evite sol/lugar abafado."); dicas++
        }
        if (b.carregando && b.tempC >= 38) { con.linha("  - Jogar carregando esquenta MUITO a bateria. Se der, carregue antes."); dicas++ }
        if (dicas == 0) con.linha("  - Tudo certo. Rode o Teste de estresse para ver como o celular se comporta sob carga.")
    }

    fun monitor(con: Console, ctx: Context) {
        con.titulo("MONITOR AO VIVO")
        if (!con.exigeRoot()) return
        con.info("Atualiza a cada 2 s. Toque em PARAR para sair. Deixe rodando e abra o jogo: o monitor continua.")
        val fps = Sensores.contadorFuncionando()
        if (!fps) con.info("(O contador de FPS do SurfaceFlinger nao responde neste aparelho: FPS nao sera mostrado.)")
        var ant = Sensores.ler()
        var pico = 0.0
        var picoBat = 0.0
        while (!con.cancelado) {
            con.sleep(2000)
            val a = Sensores.ler()
            val d = Sensores.delta(ant, a)
            ant = a
            val tc = Sensores.tempCpu(a)
            if (tc != null && tc > pico) pico = tc
            val b = Sensores.bateria(ctx)
            if (b.tempC > picoBat) picoBat = b.tempC
            val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            con.linha("$hora  " + Sensores.linha(ctx, a, if (fps) d else Delta(null, d.usoCpu, d.nucleoMax)), Sensores.corTemp(tc))
        }
        con.linha("")
        con.linha(String.format(java.util.Locale.US, "Pico nesta sessao: CPU %.0f C | bateria %.1f C", pico, picoBat), C.ciano)
    }

    fun escolherPerfil(con: Console): Pair<String, Int>? {
        val i = con.escolher("Perfil de energia e temperatura", listOf(
            "MAXIMO FPS - CPU no clock maximo (governador performance) + GPU no maximo. O mais quente.",
            "EQUILIBRADO FRIO - CPU limitada a 85%: bem mais fria, perde pouco FPS na maioria dos jogos",
            "SILENCIOSO / BATERIA - CPU limitada a 70%: fria e economica; FPS cai em jogos pesados",
            "PERSONALIZADO - voce define o limite (30 a 100%)",
            "Nao mexer"
        ))
        return when (i) {
            0 -> Pair("Maximo", 100)
            1 -> Pair("Frio", 85)
            2 -> Pair("Silencioso", 70)
            3 -> Pair("Custom", con.numero("Limite maximo da CPU em %", 80, 30, 100))
            else -> null
        }
    }

    fun aplicarPerfilMenu(con: Console) {
        if (!con.exigeRoot()) return
        val e = escolherPerfil(con) ?: run { con.linha("Nada alterado."); return }
        val n = Cpu.aplicarPerfil(e.first, e.second, Undo.global)
        if (n > 0) con.ok("Perfil ${e.first} aplicado ($n ajustes). Vale ate reiniciar o celular ou voce restaurar.")
        else con.aviso("O kernel deste aparelho recusou as mudancas (alguns fabricantes travam o controle de CPU).")
        con.info("Dica: abra o Monitor ao vivo e jogue para comparar as temperaturas.")
    }

    fun restaurarPerfil(con: Console) {
        if (!con.exigeRoot()) return
        val n = Undo.global.desfazerPrefixo("sys:/sys/") { }
        Store.putStr("perfil_ativo", "")
        if (n > 0) con.ok("CPU/GPU de volta ao original ($n valores)") else con.linha("Nada para restaurar.")
    }

    fun estresse(con: Console, ctx: Context) {
        con.titulo("TESTE DE ESTRESSE DA CPU")
        if (!con.exigeRoot()) return
        con.linha("Coloca todos os nucleos em 100% e mede se a refrigeracao do celular aguenta.")
        con.linha("Para sozinho se a CPU chegar a 92 C ou a bateria a 46 C. Tire a capinha para um teste justo.")
        val dur = con.numero("Duracao em segundos", 60, 20, 300)
        if (!con.ask("Iniciar o teste de $dur segundos?")) return
        val n = Runtime.getRuntime().availableProcessors()
        val rodando = java.util.concurrent.atomic.AtomicBoolean(true)
        val fios = (1..n).map {
            Thread {
                var x = 1.0
                while (rodando.get()) { for (k in 0 until 200000) x = Math.sqrt(x + k) * 1.0000001; if (x < 0) con.linha("") }
            }.also { t -> t.isDaemon = true; t.start() }
        }
        val freqs = ArrayList<Int>()
        var pico = 0.0
        var picoBat = 0.0
        var abortou = false
        var ant = Sensores.ler()
        val fim = System.currentTimeMillis() + dur * 1000L
        try {
            while (System.currentTimeMillis() < fim && !con.cancelado) {
                con.sleep(2000)
                val a = Sensores.ler()
                val d = Sensores.delta(ant, a); ant = a
                val tc = Sensores.tempCpu(a)
                val b = Sensores.bateria(ctx)
                if (tc != null && tc > pico) pico = tc
                if (b.tempC > picoBat) picoBat = b.tempC
                if (a.freqs.isNotEmpty()) freqs.add(a.freqs.max())
                val resta = ((fim - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                con.linha(String.format(java.util.Locale.US, "%4ds  ", resta) + Sensores.linha(ctx, a, Delta(null, d.usoCpu, d.nucleoMax)), Sensores.corTemp(tc))
                if ((tc != null && tc >= 92) || b.tempC >= 46) { abortou = true; break }
            }
        } finally { rodando.set(false); fios.forEach { it.join(500) } }
        con.titulo("RESULTADO")
        con.linha(String.format(java.util.Locale.US, "  Pico: CPU %.0f C | bateria %.1f C", pico, picoBat))
        var queda = 0
        if (freqs.size >= 8) {
            val ini = freqs.take(3).average(); val f = freqs.takeLast(3).average()
            if (ini > 0) queda = ((ini - f) / ini * 100).toInt()
            con.linha("  Clock maximo: ${(ini / 1000).toInt()} MHz no inicio -> ${(f / 1000).toInt()} MHz no fim (queda de $queda%)")
        }
        when {
            abortou -> { con.aviso("Teste interrompido por seguranca: chegou no limite de temperatura."); con.linha("  Use o perfil EQUILIBRADO FRIO e limite o FPS dos jogos.", C.amarelo) }
            queda >= 20 -> { con.aviso("O celular reduziu bastante o clock por calor (throttling)."); con.linha("  Normal em celular fino. Perfil FRIO + FPS limitado deixa o desempenho mais constante.", C.amarelo) }
            else -> con.ok("A CPU manteve o desempenho durante o teste.")
        }
        Log2.registrar("Estresse: pico $pico queda $queda")
    }

    fun dicas(con: Console) {
        con.titulo("DICAS DE REFRIGERACAO (o que realmente baixa a temperatura)")
        con.linha("O app reduz o CALOR GERADO (clock, FPS, processos). A parte fisica e com voce:")
        con.linha(" 1. TIRE A CAPINHA para jogar: e o maior ganho em muitos celulares.")
        con.linha(" 2. NAO JOGUE CARREGANDO: carregar + jogar e a combinacao que mais esquenta (e gasta a bateria).")
        con.linha(" 3. LIMITE O FPS: 60 FPS esquenta bem menos que 90/120 e o desempenho fica mais constante.")
        con.linha(" 4. BRILHO E REDE: brilho no maximo e 5G/sinal fraco aquecem; prefira Wi-Fi bom.")
        con.linha(" 5. AMBIENTE: longe do sol, do carro quente e de cobertor/travesseiro.")
        con.linha(" 6. COOLER DE CELULAR (peltier/ventoinha): ajuda muito em sessoes longas.")
        con.linha(" 7. RESOLUCAO: jogos com escala de resolucao em 75-85% esquentam menos quase sem perder nitidez.")
        con.info("Referencia: bateria 45+ C = o Android corta desempenho e a carga. Acima de 50 C e perigoso para a bateria.")
    }

    fun configurarLimite(con: Console) {
        val atual = Store.int("limite_bateria", 44)
        con.linha("Limite atual da protecao termica do Modo Jogo: $atual C (temperatura da BATERIA).")
        con.linha("Se a bateria chegar nesse valor durante o jogo, o Modo Jogo troca sozinho para o perfil EQUILIBRADO FRIO.")
        val v = con.numero("Novo limite em C", atual, 38, 48)
        Store.putInt("limite_bateria", v)
        con.ok("Limite salvo: $v C")
    }
}
