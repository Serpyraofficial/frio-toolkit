package com.pctoolkit.mobile

import android.content.Intent
import android.provider.Settings
import java.io.File
import java.util.Locale

/** TEMPERATURA: diagnostico, monitor, teste de calor, dicas e alerta (sem root). */
object Temperatura {
    fun diagnostico(con: Console) {
        val ctx = con.ctx
        con.titulo("Diagnóstico de temperatura")
        val b = Sensores.bateria(ctx)
        con.linha("Bateria: ${f1(b.tempC)} °C", Sensores.corBateria(b.tempC))
        con.info(when { b.tempC < 35 -> "Normal (fria)."; b.tempC < 39 -> "Normal em uso."; b.tempC < 43 -> "Quente: o celular pode começar a limitar o desempenho."; else -> "MUITO QUENTE: o desempenho já está limitado e a bateria envelhece mais rápido." })
        val st = Sensores.statusTermico(ctx)
        if (st >= 0) con.linha("Estado térmico do sistema: " + Sensores.nomeStatusTermico(st), if (st >= 2) T.vermelho else if (st == 1) T.amarelo else T.verde)
        Sensores.folgaTermica(ctx)?.let { con.linha(String.format(Locale.US, "Folga até limitar: %.0f%%", ((1 - it) * 100).coerceIn(0f, 100f)), if (it > 0.85f) T.amarelo else T.texto) }
        if (b.carregando) con.aviso("Carregando: jogar na tomada é a causa nº 1 de superaquecimento.")
        b.correnteMa?.let { con.info("Consumo agora: ${Math.abs(it)} mA" + if (b.carregando) " (entrando)" else "") }
        val c = Sensores.clocks(); val cm = Sensores.clocksMax()
        if (c.isNotEmpty() && cm.isNotEmpty()) con.info("CPU agora: até ${c.max()} MHz de ${cm.max()} MHz possíveis")
        val zonas = zonasTermicas()
        if (zonas.isNotEmpty()) {
            con.subtitulo("Sensores")
            for ((n, t) in zonas.sortedByDescending { it.second }.take(8)) con.linha(String.format(Locale.US, "%-18s %.1f °C", n, t), if (t >= 70) T.vermelho else if (t >= 55) T.amarelo else T.texto2)
        }
    }

    /** Zonas termicas que o Android deixa ler sem root (varia por fabricante). */
    fun zonasTermicas(): List<Pair<String, Double>> {
        val r = ArrayList<Pair<String, Double>>()
        val vistos = HashSet<String>()
        for (i in 0 until 80) {
            val d = File("/sys/class/thermal/thermal_zone$i")
            if (!d.exists()) continue
            try {
                val tipo = File(d, "type").readText().trim()
                val v = File(d, "temp").readText().trim().toDoubleOrNull() ?: continue
                val c = if (v > 1000) v / 1000 else if (v > 200) v / 10 else v
                if (c < 5 || c > 130) continue
                val nome = when {
                    tipo.contains("cpu", true) || tipo.contains("tsens", true) -> "CPU"
                    tipo.contains("gpu", true) -> "GPU"
                    tipo.contains("batt", true) -> "Bateria"
                    tipo.contains("skin", true) || tipo.contains("back", true) -> "Carcaça"
                    tipo.contains("modem", true) -> "Modem"
                    else -> continue
                }
                val k = nome + f0(c)
                if (vistos.add(k)) r.add(Pair("$nome ($tipo)".take(18), c))
            } catch (e: Exception) { }
        }
        return r
    }

    fun monitor(con: Console) {
        val ctx = con.ctx
        con.titulo("Monitor em tempo real")
        con.linha("Atualiza a cada 3 s. Toque em Parar para encerrar.", T.texto2)
        var max = 0.0
        while (!con.cancelado) {
            val b = Sensores.bateria(ctx)
            max = maxOf(max, b.tempC)
            con.linha(java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date()) + "  " + Sensores.resumo(ctx), Sensores.corBateria(b.tempC))
            con.sleep(3000)
        }
        con.linha("Máxima no período: ${f1(max)} °C", T.azul)
    }

    fun estresse(con: Console) {
        val ctx = con.ctx
        con.titulo("Teste de calor")
        con.linha("Põe o processador no máximo por alguns minutos e mostra quanto o celular esquenta e se ele perde força.", T.texto2)
        val min = con.numero("Duração (minutos)", 3, 1, 15)
        val b0 = Sensores.bateria(ctx)
        if (b0.nivel in 0..19 && !b0.carregando) { con.aviso("Bateria baixa. Carregue antes."); return }
        con.linha("Início: ${f1(b0.tempC)} °C · ${b0.nivel}%")
        val nuc = Runtime.getRuntime().availableProcessors()
        val fim = System.currentTimeMillis() + min * 60_000L
        val contador = java.util.concurrent.atomic.AtomicLong()
        val ts = (0 until nuc).map {
            Thread {
                var x = 1.0
                while (System.currentTimeMillis() < fim && !con.cancelado) { for (k in 0 until 200000) x = Math.sin(x) + Math.sqrt(x + k); contador.incrementAndGet() }
                if (x == 42.0) contador.incrementAndGet()
            }
        }
        ts.forEach { it.start() }
        var primeiro = -1L; var ultimo = 0L; var ant = 0L; var maxT = b0.tempC
        while (ts.any { it.isAlive }) {
            con.sleep(10_000)
            val c = contador.get(); val ritmo = c - ant; ant = c
            if (primeiro < 0 && ritmo > 0) primeiro = ritmo
            if (ritmo > 0) ultimo = ritmo
            val b = Sensores.bateria(ctx)
            maxT = maxOf(maxT, b.tempC)
            con.linha(String.format(Locale.US, "%s °C · desempenho %3.0f%% · %s", f1(b.tempC), if (primeiro > 0) ritmo * 100.0 / primeiro else 100.0, Sensores.nomeStatusTermico(Sensores.statusTermico(ctx))), Sensores.corBateria(b.tempC))
        }
        val b1 = Sensores.bateria(ctx)
        con.linha("")
        con.linha("Subiu ${f1(b1.tempC - b0.tempC)} °C (máx ${f1(maxT)} °C) · usou ${(b0.nivel - b1.nivel).coerceAtLeast(0)}% de bateria", T.azul)
        if (primeiro > 0) {
            val pct = ultimo * 100.0 / primeiro
            con.linha(String.format(Locale.US, "Desempenho no fim: %.0f%% do início", pct), if (pct >= 85) T.verde else if (pct >= 70) T.amarelo else T.vermelho)
            if (pct < 75) con.aviso("O celular perde bastante força quando esquenta. Veja as Dicas de refrigeração.")
            else con.ok("Refrigeração boa para jogar por bastante tempo.")
        }
    }

    fun dicas(con: Console) {
        con.titulo("Dicas para o celular esquentar menos")
        con.item("Não jogue carregando: carregar + jogar é o que mais esquenta (e gasta a bateria).", T.texto)
        con.item("Tire a capinha nas partidas longas; capinhas grossas seguram o calor.")
        con.item("Baixe a resolução de renderização ou as sombras: mesmo FPS, bem menos calor.")
        con.item("FPS travado em 60 costuma ser mais estável do que 90/120 em celulares que esquentam.")
        con.item("Brilho no máximo esquenta a tela; use o automático.")
        con.item("Evite sol direto e lugares abafados (carro, cama, travesseiro).")
        con.item("Ventoinha para celular (cooler com placa Peltier) ajuda muito em sessões longas.")
        con.item("Dados móveis com sinal fraco esquentam mais que Wi-Fi.")
        con.item("Mantenha o Android e o jogo atualizados: atualizações corrigem consumo exagerado.")
        if (Sh.rootConhecido()) con.info("Você tem root: em Temperatura > Limitar a CPU dá para segurar o processador e esfriar.")
    }

    fun limite(con: Console) {
        con.titulo("Alerta de calor")
        val atual = Store.int("limite_bateria", 43)
        val n = con.numero("Avisar quando a bateria chegar a (°C)", atual, 36, 50)
        Store.putInt("limite_bateria", n)
        con.ok("O Modo Jogo vai vibrar e avisar a partir de $n °C")
        con.info("Referência: até 40 °C tranquilo · 43 °C já limita na maioria · acima de 45 °C faça uma pausa.")
    }

    fun economia(con: Console) {
        con.titulo("Economia de energia")
        con.linha("Status: " + if (Sensores.economiaEnergia(con.ctx)) "LIGADA (limita o desempenho)" else "desligada", if (Sensores.economiaEnergia(con.ctx)) T.amarelo else T.verde)
        con.abrirEEsperar(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
    }

    // ---------------- extra com root ----------------
    fun limitarCpu(con: Console) {
        con.titulo("Limitar a CPU (root)")
        if (!Sh.rootConhecido()) { con.linha("Precisa de root. Sem root, use a Economia de energia ou o modo “Equilibrado” da ferramenta de jogos do fabricante."); return }
        val op = con.escolher("Perfil", listOf("Máximo (padrão)", "Equilibrado frio (85%)", "Silencioso (70%)"))
        if (op < 0) return
        val pct = listOf(100, 85, 70)[op]
        val pols = Sh.root("ls -d /sys/devices/system/cpu/cpufreq/policy*", 10).out.lines().filter { it.isNotBlank() }
        for (p in pols) {
            val max = Sh.root("cat $p/cpuinfo_max_freq", 5).out.trim().toLongOrNull() ?: continue
            val cur = Sh.root("cat $p/scaling_max_freq", 5).out.trim()
            Ajuste.comandoRoot("cpu:$p", "echo ${max * pct / 100} > $p/scaling_max_freq", "echo $cur > $p/scaling_max_freq")
        }
        val p0 = pols.firstOrNull()
        val conferido = p0 == null || run {
            val max = Sh.root("cat $p0/cpuinfo_max_freq", 5).out.trim().toLongOrNull() ?: 0L
            val agora = Sh.root("cat $p0/scaling_max_freq", 5).out.trim().toLongOrNull() ?: 0L
            max == 0L || Math.abs(agora - max * pct / 100) <= max / 20
        }
        if (conferido) con.ok("CPU limitada a $pct% (confirmado; volta ao reiniciar ou em Desfazer)")
        else con.erro("O sistema deste celular não aceitou o limite (alguns fabricantes travam a frequência).")
    }
}
