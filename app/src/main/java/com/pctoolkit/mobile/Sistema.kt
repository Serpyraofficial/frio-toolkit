package com.pctoolkit.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import java.io.File

/** SECAO 8: APARENCIA E DESEMPENHO INTELIGENTE. */
object Aparencia {
    fun nomeEstilo(i: Int) = when (i) { 0 -> "MAXIMO DESEMPENHO"; 1 -> "EQUILIBRADO"; else -> "VISUAL + RAPIDO" }

    class Hw(val ramGb: Int, val ghz: Double, val hz: Float, val pontos: Int, val estilo: Int)

    fun hardware(ctx: Context): Hw {
        val ram = (Sensores.memoria(ctx).totalMem / (1024L * 1024 * 1024)).toInt() + 1
        val pols = if (Sh.temRoot()) Cpu.politicas() else emptyList()
        val ghz = (pols.maxOfOrNull { it.max } ?: 0) / 1_000_000.0
        val (_, hz) = Sensores.tela(ctx)
        var p = 0
        if (ram >= 8) p += 2 else if (ram >= 6) p += 1
        if (ghz >= 2.8) p += 2 else if (ghz >= 2.2) p += 1
        if (hz >= 119) p += 1
        val e = when { p >= 4 -> 2; p >= 2 -> 1; else -> 0 }
        return Hw(ram, ghz, hz, p, e)
    }

    fun aplicar(idx: Int, ctx: Context): Boolean {
        val u = Undo.global
        val anim = if (idx == 0) "0" else "0.5"
        for (k in listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) u.setting("global", k, anim)
        if (Build.VERSION.SDK_INT >= 31) u.setting("global", "disable_window_blurs", if (idx == 2) "0" else "1")
        if (Build.VERSION.SDK_INT >= 31 && idx == 0) u.setting("secure", "accessibility_reduce_transparency", "1")
        val (_, max) = Sensores.tela(ctx)
        if (max > 61) u.setting("system", "peak_refresh_rate", String.format(java.util.Locale.US, "%.1f", max))
        Log2.registrar("Aparencia: estilo $idx")
        return true
    }

    fun executar(con: Console, ctx: Context) {
        con.titulo("APARENCIA E DESEMPENHO INTELIGENTE")
        con.linha("Ajusta animacoes, desfoque, transparencia e taxa da tela conforme o SEU hardware.")
        if (!con.exigeRoot()) return
        val hw = hardware(ctx)
        con.linha("Seu hardware:", C.ciano)
        con.linha("  RAM: ${hw.ramGb} GB  |  CPU ate " + String.format(java.util.Locale.US, "%.2f GHz", hw.ghz) + "  |  tela " + String.format(java.util.Locale.US, "%.0f Hz", hw.hz))
        con.linha("  Pontuacao: ${hw.pontos}/5 -> estilo recomendado: ${nomeEstilo(hw.estilo)}", C.amarelo)
        con.linha("Estado atual: animacoes " + Sh.setting("global", "animator_duration_scale").ifEmpty { "1.0" } + "x, desfoque " + (if (Sh.setting("global", "disable_window_blurs") == "1") "desligado" else "ligado"), C.cinza)
        val op = con.escolher("Escolha o estilo", listOf(
            "AUTOMATICO - o recomendado para o seu celular (${nomeEstilo(hw.estilo)})",
            "MAXIMO DESEMPENHO - sem animacoes, sem desfoque, menos transparencia",
            "EQUILIBRADO - animacoes rapidas (0.5x), sem desfoque",
            "VISUAL + RAPIDO - animacoes rapidas, mantem o desfoque"
        ))
        if (op < 0) return
        val idx = if (op == 0) hw.estilo else op - 1
        if (aplicar(idx, ctx)) con.ok("Estilo aplicado: ${nomeEstilo(idx)} (vale na hora)")
        if (con.ask("Usar TEMA ESCURO? Em tela OLED gasta menos bateria e esquenta menos.")) {
            val atual = Sh.root("cmd uimode night", 10).out.lowercase()
            val antes = if (atual.contains("yes")) "yes" else if (atual.contains("auto")) "auto" else "no"
            Undo.global.comando("uimode", "cmd uimode night yes", "cmd uimode night $antes")
            con.ok("Tema escuro ligado")
        }
        con.linha("")
        con.linha("Memoria virtual (equivale ao arquivo de paginacao do Windows):", C.ciano)
        val swaps = Sh.ler("/proc/swaps").lines().drop(1).filter { it.isNotBlank() }
        if (swaps.isEmpty()) con.aviso("Sem memoria virtual (zram/swap). Em celular com pouca RAM, os apps recarregam toda hora.")
        else for (s in swaps) { val p = s.trim().split(Regex("\\s+")); con.linha("  ${p[0]}: ${fmtTam((p.getOrNull(2)?.toLongOrNull() ?: 0L) * 1024)}") }
        con.info("'Expansao de RAM'/'RAM Plus' do fabricante: ajuda com 4-6 GB de RAM; com 8 GB+ pode desligar para menos escrita no armazenamento.")
        if (hw.ramGb <= 4) con.info("Seu celular tem pouca RAM: mantenha a memoria virtual ligada.")
    }
}

/** SECAO 6: MANUTENCAO. */
object Manutencao {
    fun dexopt(con: Console) {
        if (!con.exigeRoot()) return
        con.info("Otimizando (compilando) os apps... pode levar de 2 a 15 minutos. Nao feche o app.")
        val r = Sh.root("cmd package bg-dexopt-job 2>&1", 1800)
        if (r.ok) con.ok("Apps otimizados") else con.aviso("O Android nao concluiu a otimizacao: " + (r.out + r.err).take(160))
    }

    fun reparar(con: Console, ctx: Context) {
        con.titulo("REPARAR / OTIMIZAR O SISTEMA")
        con.linha("Equivale ao DISM + SFC do Windows: recompila os apps (ART), libera caches do sistema e mostra quem anda travando.")
        if (!con.exigeRoot()) return
        if (!con.ask("Executar agora? (pode levar alguns minutos)")) return
        con.linha("[1/3] Liberando caches do sistema...", C.amarelo)
        Sh.root("pm trim-caches 999G", 120); con.ok("Caches do sistema liberados")
        con.linha("[2/3] Otimizando os apps (ART)...", C.amarelo)
        dexopt(con)
        con.linha("[3/3] Apps que mais travaram/fecharam sozinhos recentemente:", C.amarelo)
        val crashes = HashMap<String, Int>()
        for (tag in listOf("data_app_crash", "data_app_anr", "system_app_crash")) {
            val out = Sh.root("dumpsys dropbox $tag --print 2>/dev/null | grep '^Process:'", 60).out
            for (l in out.lines()) { val p = l.removePrefix("Process:").trim(); if (p.isNotEmpty()) crashes[p] = (crashes[p] ?: 0) + 1 }
        }
        if (crashes.isEmpty()) con.ok("Nenhum travamento registrado.")
        for ((p, n) in crashes.entries.sortedByDescending { it.value }.take(10)) con.linha("  $n x  " + Jogos.nome(ctx, p) + " ($p)", if (n >= 3) C.amarelo else C.texto)
        if (crashes.values.any { it >= 3 }) con.info("App que trava muito: limpe os dados dele, atualize ou reinstale.")
    }

    fun trim(con: Console) {
        con.titulo("OTIMIZAR ARMAZENAMENTO (TRIM)")
        con.linha("Equivale ao 'Otimizar unidades' do Windows para SSD: o armazenamento do celular (UFS/eMMC) volta a gravar rapido.")
        if (!con.exigeRoot()) return
        val r = Sh.root("sm fstrim 2>&1", 900)
        if (r.ok) con.ok("TRIM concluido") else con.aviso("O Android nao aceitou o comando agora (ele tambem faz isso sozinho de madrugada carregando).")
    }

    fun atualizacoes(con: Console, ctx: Context) {
        con.titulo("ATUALIZACOES E DRIVER DE VIDEO")
        con.linha("Sistema: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, patch de seguranca " + (if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "?"))
        con.linha("No celular o 'driver de video' vem junto com a atualizacao do sistema. Alguns aparelhos tambem")
        con.linha("recebem o driver de jogos atualizavel (Game Driver) pela loja: use Ajustes por jogo para ativar.")
        con.abrirConfig("android.settings.SYSTEM_UPDATE_SETTINGS", Settings.ACTION_DEVICE_INFO_SETTINGS)
        if (con.ask("Abrir a Play Store para atualizar os apps/jogos tambem?")) {
            val i = ctx.packageManager.getLaunchIntentForPackage("com.android.vending")
            if (i != null) con.abrir(i)
        }
    }
}

/** SECAO 2: MEGAZORD - backup conferido + restauracao de fabrica. */
object Megazord {
    @Volatile var backupOk: String? = null

    private fun volumesExternos(ctx: Context): List<Pair<String, String>> {
        val r = ArrayList<Pair<String, String>>()
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (v in sm.storageVolumes) {
            if (v.isPrimary) continue
            val uuid = v.uuid ?: continue
            val nome = v.getDescription(ctx)
            r.add(Pair("/mnt/media_rw/$uuid", "$nome ($uuid)"))
        }
        return r
    }

    private fun checagens(con: Console, ctx: Context) {
        con.titulo("CHECAGENS")
        val b = Sensores.bateria(ctx)
        if (b.nivel >= 50 || b.carregando) con.ok("Bateria ${b.nivel}%" + if (b.carregando) " (carregando)" else "") else con.aviso("Bateria em ${b.nivel}%: coloque para carregar antes de restaurar.")
        if (Sh.temRoot()) con.ok("Root liberado") else con.aviso("Sem root: o backup fica limitado")
        con.aviso("Confirme que voce sabe a senha da conta Google (e Samsung/Xiaomi, se tiver): depois da restauracao o celular pede (protecao FRP).")
        con.info("WhatsApp: faca o backup PELO PROPRIO WhatsApp (Configuracoes > Conversas > Backup) - so a midia e copiada aqui.")
        con.info("Fotos: se usa Google Fotos com backup ligado, confira se esta tudo sincronizado.")
        val (livre, total) = Sensores.armazenamento()
        con.linha("Armazenamento: ${fmtTam(total - livre)} usados de ${fmtTam(total)}")
    }

    private fun inventario(con: Console, ctx: Context, pasta: String) {
        Sh.root("mkdir -p ${Sh.q(pasta)}", 10)
        val pm = ctx.packageManager
        val apps = Jogos.todosApps(ctx).joinToString("\n") { it.nome + "  [" + it.pkg + "]  instalado por: " + (Seguranca.instalador(pm, it.pkg) ?: "?") }
        Sh.gravar("$pasta/apps-instalados.txt", apps)
        con.ok("Lista de apps instalados salva")
        val jogos = Jogos.instalados(ctx).joinToString("\n") { it.nome + "  [" + it.pkg + "]" }
        Sh.gravar("$pasta/jogos-instalados.txt", jogos)
        con.ok("Lista de jogos salva")
        val wifi = Sh.root("cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml 2>/dev/null || cat /data/misc/wifi/WifiConfigStore.xml 2>/dev/null", 15).out
        val redes = Regex("<string name=\"SSID\">&quot;(.*?)&quot;</string>").findAll(wifi).map { it.groupValues[1] }.toList()
        if (redes.isNotEmpty()) { Sh.gravar("$pasta/redes-wifi.txt", redes.joinToString("\n")); con.ok("Nomes das redes Wi-Fi salvos (${redes.size})") }
        val check = listOf(
            "CHECKLIST POS-RESTAURACAO", "========================", "${Build.MANUFACTURER} ${Build.MODEL} - Android ${Build.VERSION.RELEASE}", "",
            "1. Configure o celular e entre na conta Google (tenha a senha e o 2o fator por perto).",
            "2. Atualize o sistema (Configuracoes > Atualizacao) ate nao ter mais nada.",
            "3. Restaure o WhatsApp pelo backup do proprio WhatsApp ANTES de copiar a midia de volta.",
            "4. Reinstale so os apps que usa, consultando apps-instalados.txt (de preferencia pela Play Store).",
            "5. Jogos: reinstale pela loja; o progresso costuma voltar pela conta do jogo (Google Play Games, Facebook, conta propria).",
            "6. Copie de volta as pastas do backup (DCIM, Pictures, Download...).",
            "7. Root: reinstale o Magisk/KernelSU/APatch se quiser (a restauracao pode remover).",
            "8. Depois rode o PC Toolkit Mobile: Assistente (opcao 1) deixa limpeza, FPS e seguranca prontos."
        ).joinToString("\n")
        Sh.gravar("$pasta/CHECKLIST-POS-RESTAURACAO.txt", check)
        con.ok("Checklist pos-restauracao salvo")
    }

    fun preparar(con: Console, ctx: Context) {
        backupOk = null
        checagens(con, ctx)
        if (!con.exigeRoot()) return
        con.titulo("DESTINO DO BACKUP")
        con.linha("O backup precisa sair do celular: cartao SD, pen drive (OTG) ou copiar a pasta para o PC pelo cabo.", C.amarelo)
        val vols = volumesExternos(ctx)
        val ops = vols.map { "Cartao/pen drive: " + it.second } + listOf("Pasta no armazenamento interno (voce copia para o PC pelo cabo depois)")
        val e = con.escolher("Destino", ops)
        if (e < 0) return
        val raiz = if (e < vols.size) vols[e].first else Sd.base
        val livre = Sh.root("df -k ${Sh.q(raiz)} | tail -1", 15).out.trim().split(Regex("\\s+")).getOrNull(3)?.toLongOrNull()?.times(1024) ?: 0L
        val fontes = listOf("DCIM", "Pictures", "Movies", "Music", "Download", "Documents", "Recordings", "Audiobooks", "Android/media/com.whatsapp/WhatsApp/Media", "Android/media/org.telegram.messenger")
            .filter { Sh.root("[ -d ${Sh.q(Sd.base + "/" + it)} ]", 10).ok }
        con.info("Medindo o que sera copiado...")
        val tams = fontes.map { f -> Pair(f, (Sh.root("du -sk ${Sh.q(Sd.base + "/" + f)} 2>/dev/null", 300).out.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L) * 1024) }
        val sel = con.varios("O que copiar?", tams.map { it.first + "  (" + fmtTam(it.second) + ")" }, true)
        if (sel.isEmpty()) { con.linha("Nada selecionado."); return }
        val total = sel.sumOf { tams[it].second }
        con.linha("Total a copiar: ${fmtTam(total)} | livre no destino: ${fmtTam(livre)}", C.ciano)
        if (raiz != Sd.base && total * 105 / 100 > livre) { con.erro("Nao cabe no destino. Desmarque pastas ou use um cartao maior."); return }
        if (raiz == Sd.base) con.aviso("Backup no armazenamento interno: COPIE a pasta para o PC antes de restaurar, senao ela sera apagada junto!")
        if (!con.ask("Iniciar o backup?")) return
        val dest = raiz + "/Backup-Celular-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        con.titulo("INVENTARIO")
        inventario(con, ctx, "$dest/_Inventario")
        con.titulo("BACKUP (pode levar bastante tempo; deixe a tela ligada)")
        var tudoOk = true
        for (i in sel) {
            val f = tams[i].first
            if (con.cancelado) { tudoOk = false; break }
            val alvo = "$dest/" + f.replace('/', '_')
            con.linha("  Copiando $f (${fmtTam(tams[i].second)})...")
            Sh.root("mkdir -p ${Sh.q(alvo)} && cp -r ${Sh.q(Sd.base + "/" + f)}/. ${Sh.q(alvo)}/", 7200)
            val nOrig = Sh.root("find ${Sh.q(Sd.base + "/" + f)} -type f | wc -l", 300).out.trim().toIntOrNull() ?: -1
            val nDest = Sh.root("find ${Sh.q(alvo)} -type f | wc -l", 300).out.trim().toIntOrNull() ?: -2
            if (nOrig == nDest) con.ok("$f: $nDest arquivos conferidos") else { con.aviso("$f: diferenca (origem $nOrig / destino $nDest arquivos)"); tudoOk = false }
        }
        con.linha("")
        if (tudoOk) { backupOk = dest; con.linha("BACKUP CONFERIDO COM SUCESSO em: ${dest.replace(Sd.base, "/sdcard")}", C.verde); con.info("Abra alguns arquivos para conferir antes de restaurar.") }
        else con.linha("O backup teve diferencas. Nao restaure ate resolver (feche apps e rode de novo).", C.vermelho)
    }

    fun redefinir(con: Console) {
        con.titulo("RESTAURACAO DE FABRICA (apaga TUDO do celular)")
        val b = backupOk
        if (b != null) con.linha("Backup conferido nesta sessao: ${b.replace(Sd.base, "/sdcard")}", C.verde) else con.linha("Nenhum backup conferido nesta sessao.", C.vermelho)
        con.linha("Sera APAGADO: todos os apps, jogos, fotos, conversas, contas e configuracoes.", C.amarelo)
        con.linha("Mantenha carregando. Leva de 10 a 30 minutos.")
        if (!con.ask("Seus arquivos importantes estao salvos FORA do celular (e voce conferiu)?")) { con.linha("Cancelado. Faca o Passo 1 primeiro."); return }
        val t = con.texto("Para continuar, digite exatamente REDEFINIR") ?: ""
        if (t != "REDEFINIR") { con.linha("Cancelado."); return }
        con.linha("")
        con.linha("Abrindo as Configuracoes. Siga:", C.ciano)
        con.linha("  Sistema (ou Gerenciamento geral) > Opcoes de redefinicao / Redefinir > Apagar todos os dados (restaurar padrao)")
        con.info("Faco pela tela oficial do Android de proposito: e a forma segura (apaga a criptografia e protege seus dados).")
        con.abrirConfig(Settings.ACTION_SETTINGS)
    }
}
