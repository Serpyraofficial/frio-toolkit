package com.pctoolkit.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** LIMPEZA sem root: arquivos (Acesso aos arquivos) + cache dos apps pela tela oficial do Android. */
object Limpeza {
    @Suppress("DEPRECATION")
    val raiz: File get() = Environment.getExternalStorageDirectory()
    private val lixeira: File get() = File(raiz, ".SerpyraToolkit-Lixeira")
    private val extImg = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng", "raw")
    private val extVid = setOf("mp4", "mkv", "mov", "3gp", "avi", "webm", "m4v", "ts")
    private val extAud = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr", "wma")
    private val extDoc = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt", "csv", "epub")
    private val extZip = setOf("zip", "rar", "7z", "tar", "gz", "xz", "obb")

    private fun pular(f: File): Boolean {
        val p = f.absolutePath
        return p.startsWith(raiz.absolutePath + "/Android/data") || p.startsWith(raiz.absolutePath + "/Android/obb") || p.startsWith(lixeira.absolutePath)
    }

    /** Percorre o armazenamento interno (sem entrar em Android/data e Android/obb). */
    fun andar(inicio: File, con: Console?, aoArquivo: (File) -> Unit, aoPasta: ((File) -> Unit)? = null) {
        val pilha = ArrayDeque<File>()
        pilha.addLast(inicio)
        var n = 0
        while (pilha.isNotEmpty()) {
            if (con?.cancelado == true) return
            val d = pilha.removeLast()
            val filhos = d.listFiles() ?: continue
            aoPasta?.invoke(d)
            for (f in filhos) {
                if (pular(f)) continue
                if (f.isDirectory) pilha.addLast(f) else { aoArquivo(f); n++ }
                if (n > 0 && n % 20000 == 0) con?.info("... $n arquivos analisados")
            }
        }
    }

    private fun ext(f: File) = f.name.substringAfterLast('.', "").lowercase()

    private fun exigirArquivos(con: Console): Boolean = con.garantirNome("Acesso aos arquivos")

    // ---------------------------------------------------------------
    fun analisar(con: Console) {
        if (!Pro.exigir(con, "Analisar armazenamento")) return
        con.titulo("Análise do armazenamento")
        val (livre, total) = Sensores.armazenamento()
        con.linha("Livre: ${fmtTam(livre)} de ${fmtTam(total)} (${if (total > 0) (livre * 100 / total) else 0}%)", T.azul)
        if (!exigirArquivos(con)) return
        con.info("Lendo os arquivos... (pode levar alguns segundos)")
        val cat = linkedMapOf("Fotos" to 0L, "Vídeos" to 0L, "Áudios" to 0L, "Documentos" to 0L, "APKs" to 0L, "Compactados" to 0L, "Outros" to 0L)
        val pastas = HashMap<String, Long>()
        val base = raiz.absolutePath
        andar(raiz, con, { f ->
            val t = f.length()
            val k = when (ext(f)) {
                in extImg -> "Fotos"; in extVid -> "Vídeos"; in extAud -> "Áudios"; in extDoc -> "Documentos"; "apk", "xapk", "apks" -> "APKs"; in extZip -> "Compactados"; else -> "Outros"
            }
            cat[k] = (cat[k] ?: 0L) + t
            val rel = f.absolutePath.removePrefix("$base/")
            val topo = if (rel.startsWith("Android/media/")) rel.split('/').take(3).joinToString("/") else rel.substringBefore('/')
            if (topo != rel) pastas[topo] = (pastas[topo] ?: 0L) + t
        })
        con.subtitulo("Por tipo")
        val soma = cat.values.sum().coerceAtLeast(1)
        for ((k, v) in cat.entries.sortedByDescending { it.value }) if (v > 0) con.linha(String.format("%-12s %9s  %s", k, fmtTam(v), barra(v.toDouble() / soma)))
        con.subtitulo("Pastas que mais ocupam")
        for ((k, v) in pastas.entries.sortedByDescending { it.value }.take(12)) con.linha(String.format("%9s   %s", fmtTam(v), nomePasta(k)))
        if (Acesso.uso()) {
            con.subtitulo("Apps que mais ocupam")
            val lst = Jogos.todosApps(con.ctx).mapNotNull { a -> Jogos.tamanho(con.ctx, a.pkg)?.let { Pair(a, it) } }
            var cache = 0L
            for ((_, t) in lst) cache += t.cache
            for ((a, t) in lst.sortedByDescending { it.second.total }.take(12)) con.linha(String.format("%9s   %s  (cache %s)", fmtTam(t.total), a.nome, fmtTam(t.cache)))
            con.linha("")
            con.linha("Cache somado de todos os apps: ${fmtTam(cache)} (dá para limpar na Limpeza rápida)", T.verde)
        } else con.info("Para ver quanto cada app ocupa, permita o \"Acesso ao uso\" (tela Acesso e permissões).")
    }

    private fun nomePasta(k: String) = when {
        k.startsWith("Android/media/com.whatsapp") -> "WhatsApp (mídias)"
        k.startsWith("Android/media/org.telegram") -> "Telegram (mídias)"
        k == "DCIM" -> "DCIM (câmera)"
        else -> k
    }

    fun barra(fr: Double): String { val n = (fr * 20).toInt().coerceIn(0, 20); return "█".repeat(n) + "░".repeat(20 - n) }

    // ---------------------------------------------------------------
    class Lixo(val nome: String, val arquivos: MutableList<File> = ArrayList(), var pastas: MutableList<File> = ArrayList()) {
        val tam: Long get() = arquivos.sumOf { it.length() }
    }

    fun rapida(con: Console, simular: Boolean) {
        con.titulo(if (simular) "Simulação de limpeza" else "Limpeza rápida")
        con.linha("Procura lixo que pode ser apagado com segurança e limpa o cache dos apps pela tela oficial do Android.", T.texto2)
        con.info("Não mexe em fotos, vídeos, documentos, conversas nem saves de jogos.")
        if (!exigirArquivos(con)) return
        val (antes, _) = Sensores.armazenamento()
        val mini = Lixo("Miniaturas da galeria (recriadas sozinhas)")
        val tmp = Lixo("Temporários e logs")
        val status = Lixo("Status vistos do WhatsApp")
        val apks = Lixo("Instaladores .apk já baixados")
        val vazias = ArrayList<File>()
        con.info("Procurando...")
        andar(raiz, con, { f ->
            val p = f.absolutePath
            val n = f.name.lowercase()
            when {
                p.contains("/.thumbnails/") -> mini.arquivos.add(f)
                p.contains("/WhatsApp/Media/.Statuses/") -> status.arquivos.add(f)
                n.endsWith(".tmp") || n.endsWith(".temp") || n.startsWith("~$") || (n.endsWith(".log") && !p.contains("/Documents/")) -> tmp.arquivos.add(f)
                n.endsWith(".apk") || n.endsWith(".xapk") || n.endsWith(".apks") -> apks.arquivos.add(f)
            }
        }, { d -> if (d != raiz && (d.list()?.isEmpty() == true)) vazias.add(d) })
        var total = 0L
        for (g in listOf(mini, tmp, status)) {
            if (g.arquivos.isEmpty()) continue
            val t = g.tam
            if (simular) { con.item("${g.nome}: ${g.arquivos.size} arquivos, ${fmtTam(t)}", T.texto); total += t; continue }
            var apagados = 0; var bytes = 0L
            for (f in g.arquivos) { val tam = f.length(); if (f.delete()) { apagados++; bytes += tam } }
            con.item("${g.nome}: $apagados de ${g.arquivos.size} apagados, ${fmtTam(bytes)}", if (apagados == g.arquivos.size) T.texto else T.amarelo)
            total += bytes
        }
        if (vazias.isNotEmpty()) {
            con.item("Pastas vazias: " + if (simular) "${vazias.size}" else "${vazias.count { it.delete() }} de ${vazias.size} apagadas")
        }
        if (apks.arquivos.isNotEmpty()) {
            con.item("Instaladores .apk: ${apks.arquivos.size}, ${fmtTam(apks.tam)}", T.amarelo)
            if (!simular) {
                val sel = con.varios("Apagar quais instaladores? (o app instalado continua)", apks.arquivos.map { it.name + "  ·  " + fmtTam(it.length()) }, true, importante = true)
                for (i in sel) { val f = apks.arquivos[i]; val tam = f.length(); if (f.delete()) total += tam else con.aviso("Não consegui apagar ${f.name}") }
            } else total += apks.tam
        }
        if (Lixeira.tamanho() > 0) {
            con.item("Lixeira do SerpyraToolkit: ${fmtTam(Lixeira.tamanho())}")
            if (!simular && con.ask("Esvaziar a Lixeira do SerpyraToolkit (${fmtTam(Lixeira.tamanho())})?", importante = true)) { total += Lixeira.tamanho(); Lixeira.esvaziar() }
        }

        // cache de todos os apps
        con.subtitulo("Cache dos apps")
        var cache = -1L
        if (Acesso.uso()) {
            cache = 0L
            for (a in Jogos.todosApps(con.ctx)) cache += Jogos.tamanho(con.ctx, a.pkg)?.cache ?: 0L
            con.linha("Cache somado: ${fmtTam(cache)}")
        }
        if (simular) {
            con.linha("")
            con.linha("Estimativa: ~${fmtTam(total + cache.coerceAtLeast(0))} podem ser liberados. Nada foi apagado.", T.verde)
            return
        }
        con.info("O cache dos jogos guarda shaders: apagar não aumenta o FPS e as primeiras partidas podem engasgar um pouco.")
        if (Sh.privOk()) {
            if (con.ask("Limpar o cache de todos os apps agora?")) {
                val a0 = Sensores.armazenamento().first
                val r = Sh.root("pm trim-caches 999999999999", 120)
                val lib = Sensores.armazenamento().first - a0
                if (r.ok) con.ok("Cache dos apps limpo: ${fmtTam(lib.coerceAtLeast(0))} liberados") else con.erro("O Android recusou limpar o cache: ${r.err.take(80)}")
            }
        } else if (Build.VERSION.SDK_INT >= 30) {
            if (con.ask("Limpar o cache de TODOS os apps? O Android vai pedir confirmação.")) {
                val r = (con.ctx as MainActivity).pedirResultado(Intent(StorageManager.ACTION_CLEAR_APP_CACHE))
                if (r != null) con.ok("Cache dos apps liberado") else con.aviso("Limpeza do cache cancelada")
            }
        } else {
            con.info("Neste Android a limpeza do cache é por app: abrindo Armazenamento.")
            con.abrirConfig(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        }
        val (depois, _) = Sensores.armazenamento()
        con.linha("")
        con.linha("Liberado: ${fmtTam((depois - antes).coerceAtLeast(0))}", T.verde)
        Log2.registrar("Limpeza: ${depois - antes}")
    }

    // ---------------------------------------------------------------
    private fun escolherParaLixeira(con: Console, arqs: List<File>) {
        if (arqs.isEmpty()) { con.ok("Nada encontrado."); return }
        val fmt = java.text.SimpleDateFormat("dd/MM/yy", java.util.Locale.US)
        for (a in arqs) con.item(fmtTam(a.length()) + "  ·  " + a.absolutePath.removePrefix(raiz.absolutePath + "/") + "  ·  " + fmt.format(java.util.Date(a.lastModified())))
        val sel = con.varios("Mover para a Lixeira do SerpyraToolkit (dá para restaurar)", arqs.map { it.name + "  ·  " + fmtTam(it.length()) })
        if (sel.isEmpty()) { con.linha("Nada movido."); return }
        var n = 0; var t = 0L
        for (i in sel) { val f = arqs[i]; val tam = f.length(); if (Lixeira.mover(f)) { n++; t += tam } else con.aviso("Não consegui mover: ${f.name}") }
        con.ok("$n arquivo(s) na Lixeira (${fmtTam(t)})")
        if (con.ask("Esvaziar a Lixeira agora para liberar o espaço?", importante = true)) { Lixeira.esvaziar(); con.ok("Lixeira esvaziada") }
    }

    fun grandes(con: Console) {
        if (!Pro.exigir(con, "Arquivos grandes")) return
        con.titulo("Arquivos grandes")
        if (!exigirArquivos(con)) return
        val mb = con.numero("Tamanho mínimo (MB)", 100, 10, 50000)
        val l = ArrayList<File>()
        andar(raiz, con, { if (it.length() > mb * 1024L * 1024) l.add(it) })
        escolherParaLixeira(con, l.sortedByDescending { it.length() }.take(60))
    }

    fun downloadsAntigos(con: Console) {
        if (!Pro.exigir(con, "Downloads antigos")) return
        con.titulo("Downloads antigos")
        if (!exigirArquivos(con)) return
        val dias = con.numero("Mais antigos que quantos dias?", 60, 1, 3650)
        val lim = System.currentTimeMillis() - dias * 86_400_000L
        val l = ArrayList<File>()
        @Suppress("DEPRECATION")
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        andar(dl, con, { if (it.lastModified() < lim) l.add(it) })
        escolherParaLixeira(con, l.sortedByDescending { it.length() }.take(80))
    }

    private fun hashParcial(f: File, completo: Boolean): String {
        val md = MessageDigest.getInstance("MD5")
        FileInputStream(f).use { s ->
            val b = ByteArray(65536)
            if (completo) { while (true) { val n = s.read(b); if (n <= 0) break; md.update(b, 0, n) } }
            else { val n = s.read(b); if (n > 0) md.update(b, 0, n) }
        }
        return md.digest().joinToString("") { String.format("%02x", it) }
    }

    fun duplicados(con: Console) {
        if (!Pro.exigir(con, "Arquivos duplicados")) return
        con.titulo("Arquivos duplicados")
        con.linha("Encontra cópias idênticas (mesmo conteúdo) de fotos, vídeos, músicas e documentos.", T.texto2)
        if (!exigirArquivos(con)) return
        val porTam = HashMap<Long, MutableList<File>>()
        andar(raiz, con, { f -> if (f.length() >= 200 * 1024) porTam.getOrPut(f.length()) { ArrayList() }.add(f) })
        con.info("Comparando conteúdos...")
        val grupos = ArrayList<List<File>>()
        for ((_, l) in porTam) {
            if (l.size < 2 || con.cancelado) continue
            val p1 = l.groupBy { try { hashParcial(it, false) } catch (e: Exception) { it.absolutePath } }
            for ((_, l2) in p1) {
                if (l2.size < 2) continue
                val p2 = l2.groupBy { try { hashParcial(it, true) } catch (e: Exception) { it.absolutePath } }
                for ((_, l3) in p2) if (l3.size >= 2) grupos.add(l3.sortedBy { it.lastModified() })
            }
        }
        if (grupos.isEmpty()) { con.ok("Nenhum duplicado encontrado."); return }
        val copias = ArrayList<File>()
        var desperdicio = 0L
        for (g in grupos.sortedByDescending { it[0].length() * (it.size - 1) }) {
            con.item("${g.size} cópias de ${g[0].name} (${fmtTam(g[0].length())} cada)")
            for (c in g.drop(1)) { copias.add(c); desperdicio += c.length() }
        }
        con.linha("")
        con.linha("Espaço desperdiçado: ${fmtTam(desperdicio)}. Vou manter a cópia mais antiga de cada grupo.", T.amarelo)
        val sel = con.varios("Mover as cópias extras para a Lixeira", copias.map { it.absolutePath.removePrefix(raiz.absolutePath + "/") + "  ·  " + fmtTam(it.length()) }, true, importante = true)
        var n = 0
        for (i in sel) if (Lixeira.mover(copias[i])) n++
        con.ok("$n cópia(s) movidas para a Lixeira")
        if (n > 0 && con.ask("Esvaziar a Lixeira agora?", importante = true)) { Lixeira.esvaziar(); con.ok("Lixeira esvaziada") }
    }

    fun tamanhoJogos(con: Console) {
        if (!Pro.exigir(con, "Quanto cada jogo ocupa")) return
        con.titulo("Quanto cada jogo ocupa")
        if (!con.garantirNome("Acesso ao uso")) return
        val l = Jogos.instalados(con.ctx).mapNotNull { j -> Jogos.tamanho(con.ctx, j.pkg)?.let { Pair(j, it) } }.sortedByDescending { it.second.total }
        if (l.isEmpty()) { con.linha("Nenhum jogo encontrado."); return }
        for ((j, t) in l) con.item("${j.nome}: ${fmtTam(t.total)}  (app ${fmtTam(t.app)} · dados ${fmtTam(t.dados)} · cache ${fmtTam(t.cache)})")
        con.linha("")
        con.linha("Total em jogos: ${fmtTam(l.sumOf { it.second.total })}", T.azul)
        val sel = con.varios("Desinstalar algum?", l.map { it.first.nome + "  ·  " + fmtTam(it.second.total) })
        for (i in sel) Auto.desinstalar(con, l[i].first.pkg, l[i].first.nome)
    }

    fun esquecidos(con: Console) {
        if (!Pro.exigir(con, "Apps esquecidos")) return
        con.titulo("Apps esquecidos")
        con.linha("Apps que você não abre há muito tempo e só ocupam espaço (e às vezes rodam em segundo plano).", T.texto2)
        if (!con.garantirNome("Acesso ao uso")) return
        val dias = con.numero("Sem uso há quantos dias?", 30, 7, 365)
        val uso = Jogos.ultimoUso(con.ctx, 365)
        val lim = System.currentTimeMillis() - dias * 86_400_000L
        val pm = con.ctx.packageManager
        val l = Jogos.todosApps(con.ctx).filter { a ->
            val ai = try { pm.getApplicationInfo(a.pkg, 0) } catch (e: Exception) { null }
            ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && (uso[a.pkg] ?: 0L) < lim
        }.map { Pair(it, Jogos.tamanho(con.ctx, it.pkg)?.total ?: 0L) }.sortedByDescending { it.second }
        if (l.isEmpty()) { con.ok("Nenhum app esquecido. Tudo em uso!"); return }
        val fmt = java.text.SimpleDateFormat("dd/MM/yy", java.util.Locale.US)
        for ((a, t) in l) { val u = uso[a.pkg]; con.item("${a.nome}  ·  ${fmtTam(t)}  ·  " + (if (u != null && u > 0) "último uso ${fmt.format(java.util.Date(u))}" else "sem uso registrado")) }
        con.linha("")
        con.linha("Total: ${fmtTam(l.sumOf { it.second })}", T.amarelo)
        val sel = con.varios("Desinstalar quais?", l.map { it.first.nome + "  ·  " + fmtTam(it.second) })
        for (i in sel) Auto.desinstalar(con, l[i].first.pkg, l[i].first.nome)
    }

    fun appsMaiores(con: Console) {
        if (!Pro.exigir(con, "Apps que mais ocupam")) return
        con.titulo("Apps que mais ocupam")
        if (!con.garantirNome("Acesso ao uso")) return
        val l = Jogos.todosApps(con.ctx).mapNotNull { a -> Jogos.tamanho(con.ctx, a.pkg)?.let { Pair(a, it) } }.sortedByDescending { it.second.total }.take(40)
        for ((a, t) in l) con.item("${a.nome}: ${fmtTam(t.total)}  (cache ${fmtTam(t.cache)})")
        val sel = con.varios("Abrir para limpar dados/cache ou desinstalar", l.map { it.first.nome + "  ·  " + fmtTam(it.second.total) })
        for (i in sel) con.abrirEEsperar(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + l[i].first.pkg)))
    }

    fun lixeiraMenu(con: Console) {
        con.titulo("Lixeira do SerpyraToolkit")
        val itens = Lixeira.itens()
        if (itens.isEmpty()) { con.linha("A Lixeira está vazia."); return }
        for (o in itens) con.item(o.optString("nome") + "  ·  " + fmtTam(o.optLong("tam")) + "  ·  " + o.optString("data"))
        when (con.escolher("O que fazer?", listOf("Restaurar arquivos", "Esvaziar (apagar de vez)"))) {
            0 -> {
                val sel = con.varios("Restaurar", itens.map { it.optString("nome") })
                var n = 0
                for (i in sel) if (Lixeira.restaurar(itens[i])) n++
                con.ok("$n restaurado(s)")
            }
            1 -> { Lixeira.esvaziar(); con.ok("Lixeira esvaziada") }
        }
    }
}

/** Lixeira propria: arquivos movidos podem ser restaurados ao lugar original. */
object Lixeira {
    private const val IDX = "lixeira.json"
    private val dir: File get() = File(Limpeza.raiz, ".SerpyraToolkit-Lixeira")

    fun mover(f: File): Boolean {
        dir.mkdirs()
        val dest = File(dir, System.currentTimeMillis().toString() + "-" + f.name)
        val tam = f.length()
        val ok = f.renameTo(dest) || (try { f.copyTo(dest); f.delete() } catch (e: Exception) { false })
        if (ok) Store.adicionar(IDX, JSONObject().put("orig", f.absolutePath).put("dest", dest.absolutePath).put("nome", f.name).put("tam", tam).put("data", agora()), 5000)
        return ok
    }

    fun itens(): List<JSONObject> { val a = Store.lerArr(IDX); return (0 until a.length()).map { a.getJSONObject(it) }.filter { File(it.optString("dest")).exists() } }
    fun tamanho(): Long = itens().sumOf { it.optLong("tam") }

    fun restaurar(o: JSONObject): Boolean {
        val orig = File(o.optString("orig"))
        orig.parentFile?.mkdirs()
        val ok = File(o.optString("dest")).renameTo(orig)
        if (ok) { val a = Store.lerArr(IDX); val n = org.json.JSONArray(); for (i in 0 until a.length()) if (a.getJSONObject(i).optString("dest") != o.optString("dest")) n.put(a.getJSONObject(i)); Store.salvarArr(IDX, n) }
        return ok
    }

    fun esvaziar() { dir.deleteRecursively(); Store.apagar(IDX) }
}
