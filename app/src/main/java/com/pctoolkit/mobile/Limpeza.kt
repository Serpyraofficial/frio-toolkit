package com.pctoolkit.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject

/** SECAO 1: LIMPEZA (com root). */
object Limpeza {
    private fun somaKb(saida: String): Long {
        var t = 0L
        for (l in saida.lines()) {
            val n = l.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: continue
            t += n
        }
        return t * 1024
    }

    /** Pacotes rodando agora (nao limpamos o cache deles para nao travar). */
    private fun rodando(): Set<String> {
        val out = Sh.root("ps -A -o NAME 2>/dev/null || ps", 20).out
        return out.lines().map { it.trim().substringBefore(':') }.filter { it.contains('.') }.toSet()
    }

    fun analisar(con: Console, ctx: Context) {
        con.titulo("ANALISAR O ARMAZENAMENTO (nao apaga nada)")
        if (!con.exigeRoot()) return
        val (livre, total) = Sensores.armazenamento()
        con.linha("Armazenamento interno: livre ${fmtTam(livre)} de ${fmtTam(total)}", C.amarelo)
        val rel = StringBuilder("PC Toolkit Mobile - relatorio de armazenamento - ${agora()}\n\n")
        fun secao(t: String, cmd: String, n: Int) {
            con.linha(""); con.linha("=== $t ===", C.ciano); rel.append("\n=== $t ===\n")
            val out = Sh.root(cmd, 240).out.lines().filter { it.isNotBlank() }
            val itens = out.mapNotNull { l ->
                val p = l.trim().split(Regex("\\s+"), 2)
                val kb = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                Pair(kb * 1024, p.getOrNull(1) ?: "")
            }.sortedByDescending { it.first }.take(n)
            for ((b, nome) in itens) {
                var mostrar = nome.replace(Sd.base, "/sdcard")
                if (nome.startsWith("/data/data/")) mostrar = Jogos.nome(ctx, nome.removePrefix("/data/data/")) + "  (" + nome + ")"
                val txt = String.format(java.util.Locale.US, "%10s   %s", fmtTam(b), mostrar)
                con.linha(txt); rel.append(txt).append('\n')
            }
        }
        con.info("Isso pode levar alguns minutos...")
        secao("Maiores pastas do armazenamento", "du -sk ${Sd.base}/* 2>/dev/null", 15)
        secao("Maiores pastas de dados de apps (Android/data e Android/obb)", "du -sk ${Sd.base}/Android/data/* ${Sd.base}/Android/obb/* 2>/dev/null", 15)
        secao("Apps com mais dados internos", "du -sk /data/data/* 2>/dev/null", 15)
        secao("Caches que da para limpar com seguranca", "du -sk /data/data/*/cache /data/data/*/code_cache ${Sd.base}/Android/data/*/cache 2>/dev/null", 15)
        con.linha(""); con.linha("=== 20 maiores arquivos (acima de 200 MB) ===", C.ciano)
        for (f in arquivosGrandes(200, Sd.base, 0).take(20)) con.linha(String.format(java.util.Locale.US, "%10s   %s", fmtTam(f.tam), f.caminho.replace(Sd.base, "/sdcard")))
        val destino = "${Sd.base}/Download/Relatorio-Armazenamento.txt"
        Sh.gravar(destino, rel.toString())
        con.ok("Relatorio salvo em Download/Relatorio-Armazenamento.txt")
    }

    /** Limpeza padrao / profunda / simulacao. */
    fun limpar(con: Console, ctx: Context, profundo: Boolean, simular: Boolean) {
        if (simular) con.titulo("SIMULACAO DE LIMPEZA PROFUNDA (nada sera apagado)")
        else if (profundo) con.titulo("LIMPEZA PROFUNDA") else con.titulo("LIMPEZA PADRAO")
        if (!con.exigeRoot()) return
        con.linha("Vou limpar:")
        con.linha("  - Cache e code_cache de TODOS os apps (interno e Android/data), menos dos que estao abertos")
        con.linha("  - Miniaturas da galeria, temporarios e a Lixeira do toolkit")
        if (profundo) {
            con.linha("  - Logs do sistema (logcat, tombstones, ANR, dropbox) - pergunto antes")
            con.linha("  - Cache dos JOGOS (inclui shaders) - pergunto antes: apagar causa engasgos nas primeiras partidas")
            con.linha("  - Otimizacao dos apps (ART) - pergunto antes")
        }
        con.info("NAO mexo em: fotos, videos, documentos, conversas, contas, saves de jogos, Download.")
        if (!simular && !con.ask("Continuar?")) return
        val (antes, _) = Sensores.armazenamento()
        val jogos = Jogos.instalados(ctx).map { it.pkg }.toSet()
        val abertos = rodando()
        var total = 0L
        var locais = 0

        // 1) caches de apps (pula os abertos e os jogos)
        con.info("Medindo caches dos apps...")
        val pulados = ArrayList<String>()
        val dirs = Sh.root("ls -d /data/data/* /data/user_de/0/* ${Sd.base}/Android/data/* 2>/dev/null", 30).out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val alvos = ArrayList<String>()
        for (d in dirs) {
            val pkg = d.substringAfterLast('/')
            if (pkg == ctx.packageName) continue
            if (abertos.contains(pkg)) { if (!pulados.contains(pkg)) pulados.add(pkg); continue }
            if (jogos.contains(pkg)) continue
            alvos.add(d)
        }
        // processa em lotes para nao estourar o tamanho do comando
        for (lote in alvos.chunked(40)) {
            val caminhos = lote.joinToString(" ") { Sh.q("$it/cache") + " " + Sh.q("$it/code_cache") }
            val b = somaKb(Sh.root("du -sk $caminhos 2>/dev/null", 120).out)
            total += b
            if (b > 0) locais++
            if (!simular) Sh.root(lote.joinToString("; ") { "rm -rf ${Sh.q(it)}/cache/* ${Sh.q(it)}/code_cache/* 2>/dev/null" }, 180)
        }
        val tagCache = if (simular) "[sim]" else "[ok] "
        con.linha("  $tagCache Cache de apps: ~${fmtTam(total)}", if (simular) C.ciano else C.verde)
        if (pulados.isNotEmpty()) con.aviso("Apps abertos (pulei para nao travar): " + pulados.take(8).joinToString(", ") { Jogos.nome(ctx, it) } + if (pulados.size > 8) "..." else "")

        // 2) miniaturas, temporarios, lixeira
        val outros = listOf(
            Pair("Miniaturas da galeria", "${Sd.base}/DCIM/.thumbnails ${Sd.base}/Pictures/.thumbnails"),
            Pair("Temporarios de apps", "${Sd.base}/.tmp ${Sd.base}/Android/data/*/files/tmp"),
            Pair("Lixeira do toolkit", Sd.lixeira)
        )
        for ((nome, cam) in outros) {
            val b = somaKb(Sh.root("du -sk $cam 2>/dev/null", 60).out)
            if (b <= 0) continue
            total += b; locais++
            if (!simular) {
                Sh.root(cam.split(" ").joinToString("; ") { "rm -rf $it/* 2>/dev/null" }, 120)
                if (nome.startsWith("Lixeira")) Store.apagar("lixeira.json")
            }
            con.linha("  $tagCache $nome: ~${fmtTam(b)}", if (simular) C.ciano else C.verde)
        }

        // 3) itens avancados (profunda)
        if (profundo) {
            val logs = "/data/tombstones /data/anr /data/system/dropbox"
            val bl = somaKb(Sh.root("du -sk $logs 2>/dev/null", 60).out)
            val bj = somaKb(Sh.root(jogos.joinToString(" ") { "/data/data/$it/cache ${Sd.base}/Android/data/$it/cache" }.ifEmpty { "/nada" }.let { "du -sk $it 2>/dev/null" }, 120).out)
            if (simular) {
                con.linha("  [sim] Logs do sistema: ~${fmtTam(bl)} (pergunta na limpeza real)", C.ciano)
                con.linha("  [sim] Cache dos jogos: ~${fmtTam(bj)} (pergunta na limpeza real; nao recomendado)", C.ciano)
                total += bl
            } else {
                con.linha("")
                con.linha("=== ITENS AVANCADOS (cada um pede confirmacao) ===", C.magenta)
                if (bl > 0) {
                    con.linha("Logs do sistema (tombstones, ANR, dropbox, logcat): ${fmtTam(bl)}", C.amarelo)
                    con.info("So atrapalha se voce estiver investigando um travamento/crash.")
                    if (con.ask("Limpar os logs do sistema?")) {
                        Sh.root("rm -rf /data/tombstones/* /data/anr/* /data/system/dropbox/* 2>/dev/null; logcat -c 2>/dev/null", 60)
                        total += bl; con.ok("Logs limpos")
                    }
                }
                if (bj > 50L * 1024 * 1024) {
                    con.linha("")
                    con.linha("Cache dos JOGOS (inclui cache de shaders): ${fmtTam(bj)}", C.amarelo)
                    con.linha("ATENCAO: apagar NAO aumenta o FPS. O jogo recompila os shaders e as primeiras partidas ficam com MAIS engasgos.")
                    con.linha("So vale se um jogo estiver bugado/crashando depois de atualizar, ou se faltar muito espaco.")
                    if (con.ask("Apagar o cache dos jogos mesmo assim?")) {
                        for (pkg in jogos) {
                            if (abertos.contains(pkg)) continue
                            Sh.root("rm -rf /data/data/$pkg/cache/* ${Sd.base}/Android/data/$pkg/cache/* 2>/dev/null", 60)
                        }
                        total += bj; con.ok("Cache dos jogos apagado")
                    }
                }
                con.linha("")
                con.linha("Otimizar os apps (compilacao ART, equivale ao CompactOS/limpeza de componentes): deixa apps e jogos", C.amarelo)
                con.linha("abrindo mais rapido. Demora de 2 a 15 minutos e esquenta um pouco o celular.")
                if (con.ask("Otimizar os apps agora?")) Manutencao.dexopt(con)
            }
        }

        if (!simular) {
            Sh.root("pm trim-caches 999G 2>/dev/null", 120)
            con.ok("O Android tambem liberou o cache que ele gerencia (pm trim-caches)")
        }

        con.linha("")
        if (simular) {
            con.linha("=====================================", C.ciano)
            con.linha("Estimativa: ~${fmtTam(total)} em $locais locais. Nada foi apagado.", C.ciano)
            con.linha("=====================================", C.ciano)
            return
        }
        val (depois, _) = Sensores.armazenamento()
        Log2.registrar("Limpeza concluida: ${fmtTam(depois - antes)}")
        con.linha("=====================================", C.verde)
        con.linha("Espaco livre antes:  ${fmtTam(antes)}", C.verde)
        con.linha("Espaco livre agora:  ${fmtTam(depois)}", C.verde)
        con.linha("Liberado:            ${fmtTam(depois - antes)}", C.verde)
        con.linha("=====================================", C.verde)
        con.info("Ainda cheio? Veja o tamanho dos jogos, os arquivos grandes e os apps instalados (menu Limpeza).")
    }

    class Arq(val tam: Long, val data: Long, val caminho: String)

    fun arquivosGrandes(mb: Int, raiz: String, diasMin: Int): List<Arq> {
        val idade = if (diasMin > 0) "-mtime +$diasMin" else ""
        val out = Sh.root("find ${Sh.q(raiz)} -type f -size +${mb}M $idade -not -path '*/.PCToolkit-Lixeira/*' -exec stat -c '%s|%Y|%n' {} + 2>/dev/null", 300).out
        val r = ArrayList<Arq>()
        for (l in out.lines()) {
            val p = l.split('|', limit = 3)
            if (p.size < 3) continue
            val t = p[0].toLongOrNull() ?: continue
            r.add(Arq(t, (p[1].toLongOrNull() ?: 0L) * 1000, p[2]))
        }
        return r.sortedByDescending { it.tam }
    }

    private fun avisoArquivo(c: String): String = when {
        c.contains("/Android/data/") || c.contains("/Android/obb/") -> "  [dados de app/jogo - apagar pode corromper o jogo]"
        c.contains("/DCIM/") || c.contains("/Pictures/") -> "  [foto/video - confira antes]"
        c.contains("/WhatsApp/") || c.contains("com.whatsapp") -> "  [WhatsApp]"
        else -> ""
    }

    private fun selecionarParaLixeira(con: Console, arqs: List<Arq>) {
        if (arqs.isEmpty()) { con.ok("Nada encontrado."); return }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        for ((i, a) in arqs.withIndex()) con.linha(String.format(java.util.Locale.US, "%3d) %9s  %s  %s%s", i + 1, fmtTam(a.tam), fmt.format(java.util.Date(a.data)), a.caminho.replace(Sd.base, "/sdcard"), avisoArquivo(a.caminho)))
        val sel = con.varios("Mandar para a Lixeira do toolkit (da para restaurar)", arqs.map { fmtTam(it.tam) + "  " + it.caminho.substringAfterLast('/') })
        if (sel.isEmpty()) { con.linha("Nada movido."); return }
        val soma = sel.sumOf { arqs[it].tam }
        if (!con.ask("Mover ${sel.size} arquivo(s) (${fmtTam(soma)}) para a Lixeira do toolkit?")) return
        Sh.root("mkdir -p ${Sh.q(Sd.lixeira)}", 10)
        for (i in sel) {
            val a = arqs[i]
            val dest = Sd.lixeira + "/" + System.currentTimeMillis() + "-" + a.caminho.substringAfterLast('/')
            if (Sh.root("mv ${Sh.q(a.caminho)} ${Sh.q(dest)}", 120).ok) {
                Store.adicionar("lixeira.json", JSONObject().put("orig", a.caminho).put("dest", dest).put("tam", a.tam).put("data", agora()), 2000)
                con.ok("Movido: " + a.caminho.substringAfterLast('/'))
            } else con.aviso("Nao consegui mover: " + a.caminho)
        }
        con.linha("O espaco so e liberado quando a Lixeira for esvaziada.", C.verde)
        if (con.ask("Esvaziar a Lixeira do toolkit agora?")) esvaziarLixeira(con)
    }

    fun revisarGrandes(con: Console) {
        con.titulo("REVISAR ARQUIVOS GRANDES")
        if (!con.exigeRoot()) return
        val mb = con.numero("Tamanho minimo em MB", 300, 20, 20000)
        con.info("Procurando...")
        selecionarParaLixeira(con, arquivosGrandes(mb, Sd.base, 0).take(40))
    }

    fun revisarDownloads(con: Console) {
        con.titulo("REVISAR DOWNLOADS ANTIGOS")
        if (!con.exigeRoot()) return
        val d = con.numero("Mostrar arquivos mais antigos que quantos dias?", 90, 1, 3650)
        selecionarParaLixeira(con, arquivosGrandes(1, "${Sd.base}/Download", d).take(40))
    }

    fun lixeira(con: Console) {
        con.titulo("LIXEIRA DO TOOLKIT")
        if (!con.exigeRoot()) return
        val a = Store.lerArr("lixeira.json")
        if (a.length() == 0) { con.linha("Lixeira vazia."); return }
        val itens = (0 until a.length()).map { a.getJSONObject(it) }
        for ((i, o) in itens.withIndex()) con.linha(String.format(java.util.Locale.US, "%3d) %9s  %s  (%s)", i + 1, fmtTam(o.optLong("tam")), o.optString("orig").replace(Sd.base, "/sdcard"), o.optString("data")))
        when (con.escolher("O que fazer?", listOf("Restaurar itens", "Esvaziar (apaga de vez)", "Voltar"))) {
            0 -> {
                val sel = con.varios("Restaurar", itens.map { it.optString("orig").substringAfterLast('/') })
                val resto = org.json.JSONArray()
                for ((i, o) in itens.withIndex()) {
                    if (sel.contains(i)) {
                        val orig = o.optString("orig")
                        Sh.root("mkdir -p ${Sh.q(orig.substringBeforeLast('/'))}; mv ${Sh.q(o.optString("dest"))} ${Sh.q(orig)}", 120)
                        con.ok("Restaurado: $orig")
                    } else resto.put(o)
                }
                Store.salvarArr("lixeira.json", resto)
            }
            1 -> esvaziarLixeira(con)
        }
    }

    private fun esvaziarLixeira(con: Console) {
        Sh.root("rm -rf ${Sh.q(Sd.lixeira)}", 120)
        Store.apagar("lixeira.json")
        con.ok("Lixeira esvaziada")
    }

    fun tamanhoJogos(con: Console, ctx: Context) {
        con.titulo("QUANTO CADA JOGO OCUPA")
        if (!con.exigeRoot()) return
        val jogos = Jogos.instalados(ctx)
        if (jogos.isEmpty()) { con.linha("Nenhum jogo encontrado."); return }
        val lista = ArrayList<Pair<Jogo, Long>>()
        for ((i, j) in jogos.withIndex()) {
            con.info("medindo ${i + 1}/${jogos.size}: ${j.nome}...")
            val cmd = "du -sk " + Jogos.pastas(ctx, j.pkg).joinToString(" ") { Sh.q(it) } + " 2>/dev/null"
            lista.add(Pair(j, somaKb(Sh.root(cmd, 120).out)))
        }
        con.linha("")
        for ((j, t) in lista.sortedByDescending { it.second }) con.linha(String.format(java.util.Locale.US, "%10s   %s", fmtTam(t), j.nome))
        con.linha("")
        con.linha("Total em jogos: ${fmtTam(lista.sumOf { it.second })}", C.amarelo)
        val sel = con.varios("Desinstalar algum? (abre a tela do Android para confirmar)", lista.sortedByDescending { it.second }.map { fmtTam(it.second) + "  " + it.first.nome })
        val ord = lista.sortedByDescending { it.second }
        for (i in sel) con.abrir(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + ord[i].first.pkg)))
    }

    fun appsInstalados(con: Console, ctx: Context) {
        con.titulo("APPS INSTALADOS (os maiores primeiro)")
        if (!con.exigeRoot()) return
        val apps = Jogos.todosApps(ctx)
        con.info("Medindo ${apps.size} apps...")
        val lista = ArrayList<Pair<Jogo, Long>>()
        for (lote in apps.chunked(15)) {
            for (a in lote) {
                val cmd = "du -sk " + Jogos.pastas(ctx, a.pkg).joinToString(" ") { Sh.q(it) } + " 2>/dev/null"
                lista.add(Pair(a, somaKb(Sh.root(cmd, 60).out)))
            }
        }
        val ord = lista.sortedByDescending { it.second }.take(40)
        for ((a, t) in ord) con.linha(String.format(java.util.Locale.US, "%10s   %s", fmtTam(t), a.nome))
        val sel = con.varios("Desinstalar (abre a confirmacao do Android)", ord.map { fmtTam(it.second) + "  " + it.first.nome })
        for (i in sel) con.abrir(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + ord[i].first.pkg)))
        if (sel.isEmpty() && con.ask("Abrir a lista de apps das Configuracoes?")) con.abrirConfig(Settings.ACTION_APPLICATION_SETTINGS)
    }
}
