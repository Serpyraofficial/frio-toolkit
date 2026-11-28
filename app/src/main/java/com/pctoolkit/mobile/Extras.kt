package com.pctoolkit.mobile

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ============================================================
// Modelos prontos de botoes (ponto de partida; o usuario ajusta no editor)
// ============================================================
object Modelos {
    class B(val rotulo: String, val x: Float, val y: Float, val tecla: Int)
    class Modelo(val nome: String, val desc: String, val analogico: Pair<Float, Float>?, val botoes: List<B>)

    private const val SP = KeyEvent.KEYCODE_SPACE
    val LISTA = listOf(
        Modelo("Free Fire", "Analógico, tiro, pulo, agachar, deitar, recarregar, mira e gel", 0.17f to 0.68f, listOf(
            B("Tiro", 0.82f, 0.58f, KeyEvent.KEYCODE_J), B("Pulo", 0.92f, 0.52f, SP), B("Agach.", 0.87f, 0.83f, KeyEvent.KEYCODE_C),
            B("Deitar", 0.94f, 0.73f, KeyEvent.KEYCODE_Z), B("Recarr.", 0.74f, 0.83f, KeyEvent.KEYCODE_R), B("Mira", 0.93f, 0.33f, KeyEvent.KEYCODE_K),
            B("Gel", 0.66f, 0.87f, KeyEvent.KEYCODE_G))),
        Modelo("PUBG Mobile", "Analógico, tiro duplo, pulo, agachar, deitar, recarregar, mira e inclinar", 0.15f to 0.70f, listOf(
            B("Tiro", 0.86f, 0.60f, KeyEvent.KEYCODE_J), B("Tiro E", 0.10f, 0.42f, KeyEvent.KEYCODE_H), B("Pulo", 0.93f, 0.62f, SP),
            B("Agach.", 0.90f, 0.87f, KeyEvent.KEYCODE_C), B("Deitar", 0.96f, 0.80f, KeyEvent.KEYCODE_Z), B("Recarr.", 0.77f, 0.87f, KeyEvent.KEYCODE_R),
            B("Mira", 0.94f, 0.47f, KeyEvent.KEYCODE_K), B("Q", 0.13f, 0.30f, KeyEvent.KEYCODE_Q), B("E", 0.20f, 0.30f, KeyEvent.KEYCODE_E))),
        Modelo("Call of Duty Mobile", "Analógico, tiro, mira, pulo, agachar, recarregar, granada e trocar arma", 0.15f to 0.70f, listOf(
            B("Tiro", 0.86f, 0.62f, KeyEvent.KEYCODE_J), B("Mira", 0.94f, 0.48f, KeyEvent.KEYCODE_K), B("Pulo", 0.94f, 0.64f, SP),
            B("Agach.", 0.90f, 0.86f, KeyEvent.KEYCODE_C), B("Recarr.", 0.79f, 0.84f, KeyEvent.KEYCODE_R), B("Granada", 0.95f, 0.33f, KeyEvent.KEYCODE_G),
            B("Arma", 0.50f, 0.88f, KeyEvent.KEYCODE_1))),
        Modelo("Roblox", "Analógico e pulo", 0.15f to 0.72f, listOf(B("Pulo", 0.90f, 0.78f, SP))),
        Modelo("Mobile Legends / MOBA", "Analógico, ataque, 3 habilidades e voltar à base", 0.15f to 0.72f, listOf(
            B("Atq", 0.88f, 0.82f, KeyEvent.KEYCODE_J), B("1", 0.76f, 0.86f, KeyEvent.KEYCODE_1), B("2", 0.80f, 0.68f, KeyEvent.KEYCODE_2),
            B("3", 0.90f, 0.60f, KeyEvent.KEYCODE_3), B("Base", 0.55f, 0.90f, KeyEvent.KEYCODE_B))),
        Modelo("Tiro genérico", "Analógico, tiro, mira, pulo, agachar e recarregar", 0.15f to 0.70f, listOf(
            B("Tiro", 0.85f, 0.60f, KeyEvent.KEYCODE_J), B("Mira", 0.93f, 0.45f, KeyEvent.KEYCODE_K), B("Pulo", 0.93f, 0.66f, SP),
            B("Agach.", 0.89f, 0.85f, KeyEvent.KEYCODE_C), B("Recarr.", 0.77f, 0.85f, KeyEvent.KEYCODE_R)))
    )

    fun sugerido(nomeJogo: String, pkg: String): Int {
        val s = (nomeJogo + " " + pkg).lowercase()
        return when {
            "free fire" in s || "freefire" in s -> 0
            "pubg" in s || "tencent.ig" in s || "battlegrounds" in s -> 1
            "call of duty" in s || "codm" in s || "activision" in s -> 2
            "roblox" in s -> 3
            "legends" in s || "moonton" in s || "moba" in s -> 4
            else -> -1
        }
    }

    fun criar(m: Modelo): MutableList<Controle> {
        val l = ArrayList<Controle>()
        m.analogico?.let { (x, y) -> l.add(Controle(tipo = "analogico", x = x, y = y, tx = x, ty = y, tam = 60, analog = "toque", raio = 0.08f, opac = 0.55f)) }
        for (b in m.botoes) l.add(Controle(tipo = "toque", x = b.x, y = b.y, tx = b.x, ty = b.y, tam = 52, rotulo = b.rotulo, tecla = b.tecla, opac = 0.6f))
        return l
    }

    /** Pergunta o modelo e salva para o jogo. Devolve true se salvou. */
    fun escolherEAplicar(con: Console, jogo: Jogo): Boolean {
        val sug = sugerido(jogo.nome, jogo.pkg)
        val ordem = if (sug >= 0) listOf(sug) + LISTA.indices.filter { it != sug } else LISTA.indices.toList()
        val i = con.escolher("Modelo de botões para ${jogo.nome}", ordem.map { LISTA[it].nome + (if (it == sug) "  (recomendado)" else "") + "\n" + LISTA[it].desc })
        if (i < 0) return false
        val m = LISTA[ordem[i]]
        if (PerfisControle.tem(jogo.pkg) && !con.ask("Trocar os botões que você já tem neste jogo pelo modelo ${m.nome}?", "Trocar", "Cancelar", importante = true)) return false
        PerfisControle.salvar(jogo.pkg, criar(m))
        Conquistas.marcar("modelo")
        con.ok("Modelo ${m.nome} salvo com ${m.botoes.size + if (m.analogico != null) 1 else 0} botões")
        con.info("Cada jogo coloca os botões num lugar um pouco diferente: o editor vai abrir para você conferir a mira ⊕ de cada botão em cima do botão do jogo.")
        con.info("Com teclado ou controle físico: Espaço pula, J atira, K mira, R recarrega, C agacha. Na tela, toque direto no jogo; para Turbo num botão, toque nele no editor e escolha Turbo (o app afasta o círculo da mira sozinho).")
        return true
    }
}

// ============================================================
// Backup das configuracoes (botoes, Tuner, mira, historico...)
// ============================================================
object Backup {
    private val FORA = listOf("lic_", "upd_", "versao_vista", "teste_", "sessao_", "shizuku_", "dnd_", "frio_orig", "perf_fixo", "sync_pausada", "eco_", "camp_", "frio_turbo", "frio_fps")
    private val ARQUIVOS = listOf("sessoes.json")

    fun exportar(con: Console) {
        con.titulo("Salvar backup")
        con.linha("Guarda num arquivo os seus botões, perfis do Tuner, mira, jogos adicionados e o histórico. Serve para levar para outro celular ou reinstalar sem perder nada.", T.texto2)
        val o = JSONObject().put("app", "SerpyraToolkit").put("versao", BuildConfig.VERSION_NAME).put("data", agora())
        val p = JSONObject()
        for ((k, v) in Store.tudo()) {
            if (FORA.any { k.startsWith(it) }) continue
            when (v) {
                is String -> p.put(k, JSONObject().put("t", "s").put("v", v))
                is Int -> p.put(k, JSONObject().put("t", "i").put("v", v))
                is Boolean -> p.put(k, JSONObject().put("t", "b").put("v", v))
                is Long -> p.put(k, JSONObject().put("t", "l").put("v", v))
                is Float -> p.put(k, JSONObject().put("t", "f").put("v", v.toDouble()))
                is Set<*> -> p.put(k, JSONObject().put("t", "c").put("v", JSONArray(v.map { it.toString() })))
            }
        }
        o.put("config", p)
        val arq = JSONObject()
        for (n in ARQUIVOS) arq.put(n, Store.lerArr(n))
        o.put("arquivos", arq)
        val dia = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val uri = con.criarArquivo("SerpyraToolkit-backup-$dia.json", "application/json") ?: run { con.linha("Cancelado."); return }
        try {
            con.ctx.contentResolver.openOutputStream(uri)!!.use { it.write(o.toString(1).toByteArray()) }
            val botoes = p.keys().asSequence().count { it.startsWith("ctl_") }
            con.ok("Backup salvo: $botoes jogo(s) com botões, ${p.length()} ajustes e ${Store.lerArr("sessoes.json").length()} sessões do histórico")
            con.info("Para usar em outro celular: instale o app lá e toque em Restaurar backup.")
            Conquistas.marcar("backup")
        } catch (e: Exception) { con.erro("Não consegui salvar: " + e.message) }
    }

    fun importar(con: Console) {
        con.titulo("Restaurar backup")
        con.linha("Escolha o arquivo SerpyraToolkit-backup que você salvou. O Pro não vem no backup: no celular novo use “Trocou de celular?” na tela do Pro.", T.texto2)
        val uri = con.escolherArquivo() ?: run { con.linha("Cancelado."); return }
        val o = try { JSONObject(con.ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }) } catch (e: Exception) { con.erro("Esse arquivo não é um backup do SerpyraToolkit."); return }
        if (o.optString("app") != "SerpyraToolkit") { con.erro("Esse arquivo não é um backup do SerpyraToolkit."); return }
        con.info("Backup de ${o.optString("data")} (versão ${o.optString("versao")})")
        if (!con.ask("Substituir as configurações atuais pelas do backup?", "Restaurar", "Cancelar", importante = true)) return
        val p = o.optJSONObject("config") ?: JSONObject()
        var n = 0
        for (k in p.keys()) {
            if (FORA.any { k.startsWith(it) }) continue
            val e = p.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "s" -> Store.putStr(k, e.optString("v"))
                "i" -> Store.putInt(k, e.optInt("v"))
                "b" -> Store.putBool(k, e.optBoolean("v"))
                "l" -> Store.putLong(k, e.optLong("v"))
                "f" -> Store.putFloat(k, e.optDouble("v").toFloat())
                "c" -> { val a = e.optJSONArray("v") ?: JSONArray(); Store.putConjunto(k, (0 until a.length()).map { a.optString(it) }.toSet()) }
                else -> continue
            }
            n++
        }
        val arq = o.optJSONObject("arquivos")
        if (arq != null) for (nome in ARQUIVOS) arq.optJSONArray(nome)?.let { Store.salvarArr(nome, it) }
        con.ok("$n ajustes restaurados. Seus botões, perfis e mira já estão de volta.")
    }
}

// ============================================================
// Modo bateria: jogar longe da tomada
// ============================================================
object Economia {
    var ultimo = ""
    fun ativo() = Store.str("eco_orig").isNotEmpty()

    /** Baixa o brilho, trava a tela em 60 Hz e (se der) poe o jogo no modo bateria do Android. */
    fun ligar(ctx: Context, pkg: String?): String {
        if (!Ajuste.podeSystem()) return "este celular não deixou mudar o brilho e a taxa da tela por comando (ative o modo completo)"
        if (!ativo()) {
            val o = JSONObject().put("modo", Ajuste.ler("system", "screen_brightness_mode")).put("brilho", Ajuste.ler("system", "screen_brightness"))
                .put("peak", Ajuste.ler("system", "peak_refresh_rate")).put("min", Ajuste.ler("system", "min_refresh_rate")).put("pkg", pkg ?: "")
            Store.putStr("eco_orig", o.toString())
        }
        val okModo = Ajuste.gravar("system", "screen_brightness_mode", "0")
        val b = Ajuste.ler("system", "screen_brightness").toIntOrNull() ?: 128
        val okBrilho = okModo && Ajuste.gravar("system", "screen_brightness", (b * 6 / 10).coerceIn(25, 255).toString())
        val okHz = Ajuste.gravar("system", "peak_refresh_rate", "60.0")
        Ajuste.gravar("system", "min_refresh_rate", "60.0")
        if (pkg != null && Build.VERSION.SDK_INT >= 33 && Sh.privOk()) Sh.root("cmd game mode battery $pkg 2>/dev/null", 10)
        if (!okBrilho && !okHz) { desligar(); return "o Android não aceitou mudar o brilho nem a taxa da tela" }
        ultimo = listOfNotNull(if (okBrilho) "brilho 40% menor" else null, if (okHz) "tela em 60 Hz" else null).joinToString(" e ")
        return ""
    }

    fun desligar() {
        val s = Store.str("eco_orig"); if (s.isEmpty()) return
        try {
            val o = JSONObject(s)
            fun volta(k: String, j: String) { val v = o.optString(j); Ajuste.gravar("system", k, v.ifEmpty { null }) }
            volta("screen_brightness", "brilho"); volta("screen_brightness_mode", "modo"); volta("peak_refresh_rate", "peak"); volta("min_refresh_rate", "min")
            val pk = o.optString("pkg")
            if (pk.isNotEmpty() && Build.VERSION.SDK_INT >= 33 && Sh.privOk()) Sh.root("cmd game mode standard $pk 2>/dev/null", 10)
        } catch (e: Exception) { }
        Store.putStr("eco_orig", ""); Store.putBool("eco_sessao", false)
    }

    fun executar(con: Console) {
        con.titulo("Modo bateria (longe da tomada)")
        con.linha("Para a partida durar mais: brilho 40% menor, tela em 60 Hz e o jogo no modo economia do Android. Desligue quando voltar para a tomada.", T.texto2)
        if (ativo()) {
            if (con.ask("O modo bateria está ligado. Desligar e voltar tudo ao normal?", "Desligar", "Manter")) { desligar(); con.ok("Tudo de volta ao normal") }
            return
        }
        if (!Ajuste.pode() && !Priv.ativar(con)) { con.erro("O modo bateria precisa do modo completo."); return }
        val jogo = if (con.ask("Também deixar um jogo no modo economia do Android?", "Escolher jogo", "Só o celular")) Jogos.escolher(con) else null
        val r = ligar(con.ctx, jogo?.pkg)
        if (r.isNotEmpty()) { con.erro("Não deu: $r"); return }
        con.ok("Modo bateria ligado: $ultimo" + if (jogo != null) " (${jogo.nome} em economia)" else "")
        con.info("Para desligar, abra esta opção de novo.")
    }
}

// ============================================================
// Teste de internet para jogos
// ============================================================
object InternetJogos {
    fun executar(con: Console) {
        if (!Pro.exigir(con, "Internet para jogos")) return
        val ctx = con.ctx
        con.titulo("Internet para jogos")
        con.linha("Mede o atraso (ping), a oscilação e a perda de pacotes, e diz se o lag vem do Wi-Fi ou da internet.", T.texto2)
        val tipo = Rede.tipo(ctx)
        con.info("Conexão: $tipo")
        val freq = Rede.frequencia(ctx); val rssi = Rede.rssi(ctx)
        if (freq != null) con.info("Wi-Fi em " + (if (freq > 4900) "5 GHz" else "2,4 GHz") + (if (rssi != null) " · sinal $rssi dBm" else ""))
        val gw = Rede.gateway(ctx)
        con.linha("")
        con.linha("Medindo o roteador...", T.texto2)
        val local = if (gw != null) Rede.medirPing(gw, 20) else null
        if (con.cancelado) return
        con.linha("Medindo a internet (Cloudflare e Google)...", T.texto2)
        val net1 = Rede.medirPing("1.1.1.1", 30)
        val net2 = Rede.medirPing("8.8.8.8", 20)
        if (con.cancelado) return
        val net = listOfNotNull(net1, net2).minByOrNull { it.media }
        con.linha("")
        if (local != null) con.linha("Roteador: ${f0(local.media)} ms · oscilação ${f0(local.jitter)} ms · perda ${local.perda}%", cor(local.media, 10.0, 30.0))
        if (net == null) { con.erro("Sem resposta da internet. Confira se está conectado."); return }
        con.linha("Internet: ${f0(net.media)} ms (mín ${f0(net.min)} · máx ${f0(net.max)}) · oscilação ${f0(net.jitter)} ms · perda ${net.perda}%", cor(net.media, 60.0, 120.0))
        con.linha("")
        val nota = when {
            net.perda >= 5 || net.media > 150 || net.jitter > 40 -> 0
            net.perda >= 1 || net.media > 80 || net.jitter > 15 -> 1
            else -> 2
        }
        when (nota) {
            2 -> con.ok("Ótima para jogar online")
            1 -> con.aviso("Dá para jogar, mas pode ter pequenos travamentos")
            else -> con.erro("Ruim para jogar online: vai ter lag")
        }
        val culpaWifi = local != null && (local.media > 25 || local.jitter > 15 || local.perda > 0)
        if (culpaWifi) {
            con.aviso("O problema está no Wi-Fi (entre o celular e o roteador), não na internet.")
            if (freq != null && freq < 4900) con.item("Troque para a rede 5 GHz do roteador (muitas vezes termina com “5G”): tem menos interferência")
            if (rssi != null && rssi < -70) con.item("O sinal está fraco: jogue mais perto do roteador ou sem paredes no meio")
            con.item("Desligue o Wi-Fi de outros aparelhos que estejam baixando ou vendo vídeo")
        } else if (nota < 2) {
            con.info("O Wi-Fi está bem; o atraso vem da internet/operadora.")
            con.item("Evite horários de pico (19h–23h) ou teste os dados móveis 4G/5G")
            con.item("Em Rede > DNS, use o DNS mais rápido")
            con.item("Feche downloads e atualizações da Play Store durante a partida")
        }
        Store.putStr("internet_ultimo", "${agora()} · ${f0(net.media)} ms · oscilação ${f0(net.jitter)} ms · perda ${net.perda}%")
    }

    private fun cor(v: Double, bom: Double, ruim: Double) = if (v <= bom) T.verde else if (v <= ruim) T.amarelo else T.vermelho
}

// ============================================================
// Gravar a tela (o painel do Modo Jogo aparece no video)
// ============================================================
object Gravador {
    private const val PASTA = "/sdcard/Movies/SerpyraToolkit"
    fun gravando(): Boolean = Sh.privOk() && Sh.root("pidof screenrecord", 5).out.isNotBlank()

    fun executar(con: Console) {
        if (!Pro.exigir(con, "Gravar a tela")) return
        con.titulo("Gravar a tela")
        con.linha("Grava até 3 minutos por vez. Ligue o painel do Modo Jogo antes para o FPS e a temperatura aparecerem no vídeo.", T.texto2)
        if (!Sh.privOk() && !Priv.ativar(con)) { con.erro("Gravar pela ferramenta precisa do modo completo. Você também pode usar o gravador de tela do próprio Android."); return }
        if (gravando()) {
            if (con.ask("Está gravando agora. Parar e salvar?", "Parar", "Continuar")) { Sh.root("pkill -INT screenrecord", 5); con.sleep(1500); con.ok("Vídeo salvo na pasta Filmes/SerpyraToolkit") }
            return
        }
        val q = con.escolher("Qualidade", listOf("Normal (arquivo menor)", "Alta (mais nítido)"))
        if (q < 0) return
        val nome = "$PASTA/jogo-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".mp4"
        val taxa = if (q == 1) 12_000_000 else 6_000_000
        Sh.root("mkdir -p $PASTA; nohup screenrecord --bit-rate $taxa --time-limit 180 ${Sh.q(nome)} >/dev/null 2>&1 &", 10)
        con.sleep(1200)
        if (gravando()) {
            con.ok("Gravando! Abra o jogo. Para antes dos 3 minutos, abra esta opção de novo.")
            con.info("O vídeo fica em Filmes/SerpyraToolkit na galeria.")
        } else con.erro("O Android não deixou gravar desta vez. Use o gravador de tela das configurações rápidas.")
    }
}

// ============================================================
// Conquistas (pequenas metas)
// ============================================================
object Conquistas {
    val LISTA = listOf(
        Triple("raiox", "Primeiro Raio-X", "Faça o Raio-X de desempenho"),
        Triple("sessao", "Primeira partida", "Jogue uma vez pelo Modo Jogo"),
        Triple("horas10", "10 horas de jogo", "Some 10 horas pelo Modo Jogo"),
        Triple("frio", "Gelado", "Termine uma partida com o Modo Frio ligado"),
        Triple("modelo", "Pronto para jogar", "Use um modelo pronto de botões"),
        Triple("backup", "Precavido", "Salve um backup das configurações"),
        Triple("nota90", "Celular afiado", "Chegue a 90 pontos no Raio-X"),
        Triple("campeonato", "Modo campeonato", "Jogue uma partida no Modo campeonato")
    )

    fun tem(id: String) = Store.conjunto("conquistas").contains(id)

    fun marcar(id: String) {
        val s = Store.conjunto("conquistas")
        if (!s.add(id)) return
        Store.putConjunto("conquistas", s)
        val c = LISTA.firstOrNull { it.first == id } ?: return
        try {
            val ctx = PcApp.ctx
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("conquistas", "Conquistas", NotificationManager.IMPORTANCE_LOW))
            nm.notify(7300 + LISTA.indexOf(c), Notification.Builder(ctx, "conquistas").setSmallIcon(R.drawable.ic_jogo)
                .setContentTitle("Conquista: ${c.second} 🏆").setContentText(c.third).setAutoCancel(true).setColor(T.amarelo).build())
        } catch (e: Exception) { }
    }

    /** Confere as conquistas que dependem do historico. */
    fun conferir() {
        val a = Store.lerArr("sessoes.json")
        if (a.length() > 0) marcar("sessao")
        var min = 0
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); min += o.optInt("dur"); if (o.optInt("frio") > 0) marcar("frio"); if (o.optBoolean("campeonato")) marcar("campeonato") }
        if (min >= 600) marcar("horas10")
        val nota = Store.int("nota_fps", -1)
        if (nota >= 0) marcar("raiox")
        if (nota >= 90) marcar("nota90")
    }

    fun executar(con: Console) {
        conferir()
        con.titulo("Conquistas")
        val feitas = LISTA.count { tem(it.first) }
        con.linha("$feitas de ${LISTA.size} conquistadas", T.amarelo)
        con.linha("")
        for ((id, nome, desc) in LISTA) con.item((if (tem(id)) "🏆 " else "○ ") + nome + " — " + desc, if (tem(id)) T.verde else T.texto3)
    }
}

// ============================================================
// Cuidados automaticos: limpeza semanal, alerta de calor fora do jogo e app pesado
// ============================================================
object Automatico {
    private const val JOB = 4242
    fun limpezaLigada() = Store.bool("auto_limpeza", false)
    fun calorLigado() = Store.bool("auto_calor", false)
    fun pesadoLigado() = Store.bool("auto_pesado", false)

    fun agendar(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (!limpezaLigada() && !calorLigado() && !pesadoLigado()) { js.cancel(JOB); return }
        if (js.getPendingJob(JOB) != null) return
        val info = JobInfo.Builder(JOB, ComponentName(ctx, CuidadoJob::class.java))
            .setPeriodic(30 * 60_000L).setPersisted(true).build()
        try { js.schedule(info) } catch (e: Exception) { Log2.registrar("job: $e") }
    }

    fun executar(con: Console) {
        con.titulo("Cuidados automáticos")
        con.linha("O app cuida do celular sozinho, em segundo plano, sem gastar bateria (confere a cada 30 minutos).", T.texto2)
        val nomes = listOf(
            "Limpeza semanal: apaga o cache dos apps uma vez por semana e avisa quanto liberou",
            "Alerta de calor fora do jogo: avisa se a bateria passar de ${Store.int("limite_bateria", 43)} °C",
            "Alerta de app pesado: avisa quando um app gasta muito processador em segundo plano"
        )
        val atuais = listOfNotNull(if (limpezaLigada()) 0 else null, if (calorLigado()) 1 else null, if (pesadoLigado()) 2 else null)
        con.info("Ligados agora: " + if (atuais.isEmpty()) "nenhum" else atuais.joinToString(", ") { listOf("limpeza", "calor", "app pesado")[it] })
        val sel = con.varios("O que deixar ligado?", nomes, false, importante = true)
        Store.putBool("auto_limpeza", 0 in sel); Store.putBool("auto_calor", 1 in sel); Store.putBool("auto_pesado", 2 in sel)
        if ((0 in sel || 2 in sel) && !Sh.privOk()) con.aviso("A limpeza e o alerta de app pesado funcionam melhor com o modo completo ligado.")
        if (sel.isNotEmpty() && !Acesso.notificacoes()) con.garantirNome("Notificações")
        agendar(con.ctx)
        con.ok(if (sel.isEmpty()) "Cuidados automáticos desligados" else "Pronto: ${sel.size} cuidado(s) ligado(s)")
    }

    /** Roda no job (fora da tela). */
    fun rodar(ctx: Context) {
        val agora = System.currentTimeMillis()
        if (calorLigado() && !GameService.rodando) {
            val b = Sensores.bateria(ctx)
            val lim = Store.int("limite_bateria", 43)
            val ult = Store.str("auto_calor_em").toLongOrNull() ?: 0L
            if (b.tempC >= lim && agora - ult > 2 * 3600_000L) {
                Store.putStr("auto_calor_em", agora.toString())
                notificar(ctx, 7401, "Celular quente: ${f1(b.tempC)} °C", "Tire da capinha, pare de carregar e feche apps pesados. Toque para ver dicas.", "temperatura")
            }
        }
        if (limpezaLigada() && Sh.privOk()) {
            val ult = Store.str("auto_limpeza_em").toLongOrNull() ?: 0L
            if (agora - ult > 7 * 86_400_000L) {
                Store.putStr("auto_limpeza_em", agora.toString())
                val antes = Sensores.armazenamento().first
                Sh.root("pm trim-caches 999G", 120)
                try { ctx.cacheDir.listFiles()?.forEach { if (it.name != "atualizacao.apk") it.deleteRecursively() } } catch (e: Exception) { }
                val lib = Sensores.armazenamento().first - antes
                if (lib > 20L shl 20) notificar(ctx, 7402, "Limpeza semanal: ${fmtTam(lib)} liberados", "O cache dos apps foi limpo. Nada pessoal foi apagado.", null)
            }
        }
        if (pesadoLigado() && Sh.privOk() && !GameService.rodando) {
            val ult = Store.str("auto_pesado_em").toLongOrNull() ?: 0L
            if (agora - ult > 3 * 3600_000L) {
                val r = Sh.root("top -b -n 1 -m 8 -o %CPU,NAME 2>/dev/null", 15).out
                val pesado = r.lines().mapNotNull { l ->
                    val p = l.trim().split(Regex("\\s+")); if (p.size < 2) null else p[0].toDoubleOrNull()?.let { it to p[1] }
                }.firstOrNull { (cpu, nome) -> cpu >= 30 && nome.contains('.') && !nome.startsWith("com.android.systemui") && nome != ctx.packageName && !nome.startsWith("android.") }
                if (pesado != null) {
                    Store.putStr("auto_pesado_em", agora.toString())
                    val nome = Jogos.nome(ctx, pesado.second.substringBefore(':'))
                    notificar(ctx, 7403, "$nome está pesando o celular", "Usando ${f0(pesado.first)}% do processador em segundo plano. Toque para liberar memória.", "memoria")
                }
            }
        }
    }

    private fun notificar(ctx: Context, id: Int, t: String, s: String, acao: String?) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("cuidados", "Cuidados automáticos", NotificationManager.IMPORTANCE_DEFAULT))
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); if (acao != null) i.putExtra("acao", acao)
            val pi = PendingIntent.getActivity(ctx, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(id, Notification.Builder(ctx, "cuidados").setSmallIcon(R.drawable.ic_jogo).setContentTitle(t).setContentText(s)
                .setStyle(Notification.BigTextStyle().bigText(s)).setContentIntent(pi).setAutoCancel(true).setColor(T.verde).build())
        } catch (e: Exception) { }
    }
}

class CuidadoJob : JobService() {
    override fun onStartJob(p: JobParameters?): Boolean {
        Thread { try { Automatico.rodar(applicationContext) } catch (e: Throwable) { Log2.registrar("cuidado: $e") }; jobFinished(p, false) }.start()
        return true
    }
    override fun onStopJob(p: JobParameters?): Boolean = false
}

// ============================================================
// Letra maior
// ============================================================
object Letra {
    @Volatile var escala = 1f
    fun carregar() { escala = Store.int("letra_pct", 100) / 100f }
    fun executar(con: Console) {
        con.titulo("Tamanho da letra")
        val ops = listOf(100 to "Normal", 115 to "Grande", 130 to "Maior")
        val i = con.escolher("Tamanho da letra do app (agora: ${ops.firstOrNull { it.first == Store.int("letra_pct", 100) }?.second ?: "Normal"})", ops.map { it.second })
        if (i < 0) return
        Store.putInt("letra_pct", ops[i].first); carregar()
        con.ok("Letra: ${ops[i].second}. Volte para a tela inicial para ver.")
    }
}

// ============================================================
// Ajuda das telas
// ============================================================
object Ajuda {
    private val TEXTOS = mapOf(
        "Modo Jogo" to "Escolha o jogo e marque o que ativar. O app prepara o celular, abre o jogo e mostra um painel. Quando você fecha o jogo, tudo volta ao normal e aparece o relatório da partida.",
        "Modo campeonato" to "Liga tudo de uma vez para uma partida importante: bloqueia notificações e chamadas, trava o brilho, libera memória, mostra o ping e avisa se a internet oscilar.",
        "Teclado e controles" to "Crie botões por cima do jogo. Comece por um modelo pronto (Free Fire, PUBG, COD...) e arraste o que ficar fora do lugar. Cada botão aperta um ponto do jogo e pode ter turbo ou uma tecla do teclado/controle físico.",
        "Teclado no jogo" to "Crie botões por cima do jogo. Comece por um modelo pronto (Free Fire, PUBG, COD...) e arraste o que ficar fora do lugar.",
        "Raio-X de desempenho" to "Confere tudo que derruba o FPS (memória, calor, animações, apps em segundo plano) e dá uma nota de 0 a 100. Cada item vermelho tem um botão para corrigir.",
        "Internet para jogos" to "Ping é o atraso até o servidor (quanto menor, melhor). Oscilação é quanto o ping varia: acima de 15 ms causa travadas. Perda de pacotes acima de 1% causa teleporte e tiros que não contam.",
        "Mira na tela" to "Uma mira fixa no centro da tela, por cima do jogo. Ela não bloqueia os toques. Use Ajustar a posição se o centro do jogo for diferente.",
        "Modo bateria (longe da tomada)" to "Baixa o brilho e trava a tela em 60 Hz para a bateria durar mais durante o jogo. Desligue quando voltar para a tomada.",
        "Cuidados automáticos" to "O app confere o celular a cada 30 minutos em segundo plano e só avisa quando precisa. Não gasta bateria perceptível.",
        "Atualizar o app" to "O app baixa a versão nova do site oficial, confere se o arquivo está perfeito e pede para o Android instalar por cima. Nada se perde."
    )
    fun de(titulo: String): String? = TEXTOS[titulo]
}

// ============================================================
// Ajuda e contato
// ============================================================
object Suporte {
    const val EMAIL = "serpyrasuporte@gmail.com"
    private val FAQ = listOf(
        "Os botões não apertam nada no jogo" to "Ative o modo completo em Acesso e permissões e confira se o Shizuku está rodando. Depois de reiniciar o celular, o Shizuku precisa ser ligado de novo.",
        "O Modo Jogo encerrou sozinho" to "Permita “Acesso ao uso” e deixe o app sem restrição de bateria (Configurações > Apps > SerpyraToolkit > Bateria > Sem restrição).",
        "Paguei e a chave não chegou" to "Olhe a caixa de spam. Na tela do Pro, toque em “Já comprei” e digite o e-mail da compra: a chave é recuperada na hora.",
        "Troquei de celular" to "Na tela do Pro, use “Trocou de celular?”, digite o e-mail da compra e o código que chega no e-mail. Dá para trocar uma vez a cada 30 dias.",
        "A atualização não instala" to "Permita “Instalar apps desta fonte” para o SerpyraToolkit quando o Android pedir. Se falhar, baixe pelo site serpyratoolkit.pages.dev e instale por cima: nada se perde.",
        "O jogo ficou travando depois de usar o app" to "Use Manutenção > Desfazer alterações para voltar tudo ao padrão, e reinicie o celular."
    )

    fun executar(con: Console) {
        con.titulo("Ajuda e contato")
        val i = con.escolher("Qual é a dúvida?", FAQ.map { it.first } + "Nenhuma dessas: falar com o suporte")
        if (i < 0) return
        if (i < FAQ.size) {
            con.subtitulo(FAQ[i].first)
            con.linha(FAQ[i].second)
            con.linha("")
            if (!con.ask("Resolveu?", "Resolveu", "Ainda preciso de ajuda", importante = true)) contato(con)
            return
        }
        contato(con)
    }

    private fun contato(con: Console) {
        val dados = "Código do celular: ${Licenca.codigoFormatado()}\nVersão do app: ${BuildConfig.VERSION_NAME}\nAndroid ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\nPro: " + (if (Licenca.ativo()) "ativo" else "não") + "\n\nDescreva o problema aqui:\n"
        val i = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, "Suporte SerpyraToolkit").putExtra(Intent.EXTRA_TEXT, dados).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { con.ctx.startActivity(i); con.ok("Abrindo o e-mail com os dados do seu celular já preenchidos") }
        catch (e: Exception) { con.info("Mande um e-mail para $EMAIL com estes dados:"); dados.lines().filter { it.isNotBlank() }.forEach { con.item(it) } }
    }
}
