package com.pctoolkit.mobile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File
import java.util.Locale

/** APARENCIA, MANUTENCAO, BACKUP e DESFAZER. */
object Sistema {
    // ---------------- aparencia ----------------
    fun aparencia(con: Console) {
        con.titulo("Aparência e fluidez")
        con.linha("Equilíbrio entre visual bonito e sistema rápido.", T.texto2)
        val anim = Ajuste.ler("global", "animator_duration_scale").ifEmpty { "1" }
        val blur = Ajuste.ler("global", "disable_window_blurs")
        con.linha("Animações: ${anim}x" + if (Build.VERSION.SDK_INT >= 31) " · Desfoque de janelas: " + if (blur == "1") "desligado" else "ligado" else "")
        val op = con.escolher("Estilo", listOf("Máximo desempenho (sem animações e sem desfoque)", "Equilibrado (animações 0.5x)", "Visual completo (padrão do Android)"))
        if (op < 0) return
        val v = listOf("0", "0.5", "1")[op]
        if (Ajuste.pode()) {
            for (k in listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) Ajuste.mudar("global", k, v)
            if (Build.VERSION.SDK_INT >= 31) Ajuste.mudar("global", "disable_window_blurs", if (op == 0) "1" else "0")
            con.ok("Estilo aplicado (reversível em Desfazer)")
        } else {
            con.linha("Vou abrir as Opções do desenvolvedor. Ajuste as três “Escala de animação” para ${v}x" + if (op == 0 && Build.VERSION.SDK_INT >= 31) " e ligue “Desativar desfoque de janelas”." else ".", T.azul)
            con.info("Se não abrir: Configurações > Sobre o telefone > toque 7 vezes em “Número da versão”.")
            con.abrirEEsperar(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        if (op == 0) {
            con.info("Tema escuro economiza bateria em telas AMOLED.")
            if (con.ask("Ligar o tema escuro?")) Auto.temaEscuro(con)
        }
    }

    // ---------------- manutencao ----------------
    fun saude(con: Console) {
        val ctx = con.ctx
        con.titulo("Saúde do celular")
        con.linha("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", T.azul)
        con.info("Processador: ${Sensores.soc()} · ${Runtime.getRuntime().availableProcessors()} núcleos")
        con.info("Atualização de segurança: ${Build.VERSION.SECURITY_PATCH}")
        val b = Sensores.bateria(ctx)
        con.subtitulo("Bateria")
        con.linha("Saúde informada: " + Sensores.nomeSaude(b.saude), if (b.saude == android.os.BatteryManager.BATTERY_HEALTH_GOOD) T.verde else T.amarelo)
        b.ciclos?.let { con.linha("Ciclos de carga: $it", if (it > 800) T.vermelho else if (it > 500) T.amarelo else T.verde); if (it > 500) con.info("Acima de ~500 ciclos a bateria costuma ter 80% ou menos da capacidade original.") }
        con.info("Tensão: ${b.tensaoMv} mV · Temperatura: ${f1(b.tempC)} °C")
        con.subtitulo("Uso")
        val horas = SystemClock.elapsedRealtime() / 3_600_000
        con.linha("Ligado há: " + if (horas >= 24) "${horas / 24} dias e ${horas % 24} h" else "$horas h", if (horas >= 168) T.amarelo else T.texto)
        if (horas >= 168) con.aviso("Mais de uma semana sem reiniciar. Reiniciar limpa a memória e resolve lentidão e travadinhas.")
        val (livre, total) = Sensores.armazenamento()
        con.linha("Armazenamento: ${fmtTam(livre)} livres de ${fmtTam(total)}", if (total > 0 && livre * 100 / total < 10) T.vermelho else T.texto)
        val m = Sensores.memoria(ctx)
        con.linha("Memória: ${fmtTam(m.availMem)} livres de ${fmtTam(m.totalMem)}")
        val ns = Store.str("seg_ultimo")
        if (ns.isNotEmpty()) con.info("Última verificação de segurança: $ns")
    }

    fun atualizacoes(con: Console) {
        con.titulo("Atualizações")
        con.item("Sistema: atualização de segurança de ${Build.VERSION.SECURITY_PATCH}")
        if (con.ask("Procurar atualização do sistema?")) con.abrirEEsperar(Intent("android.settings.SYSTEM_UPDATE_SETTINGS"))
        if (con.ask("Abrir a Play Store para atualizar os apps? (perfil > Gerenciar apps)")) {
            try { con.abrirEEsperar(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${con.ctx.packageName}"))) } catch (e: Exception) { Links.abrirPacote(con.ctx, "com.android.vending") }
        }
        con.info("Jogos atualizados costumam trazer correções de desempenho e de aquecimento.")
    }

    fun reiniciar(con: Console) {
        con.titulo("Reiniciar")
        if (Sh.privOk()) { if (con.ask("Reiniciar o celular agora?", importante = true)) Sh.root("svc power reboot || reboot", 10); return }
        con.linha("Um app comum não pode reiniciar o celular. Segure o botão de ligar (ou Ligar + Volume para cima) e toque em Reiniciar.", T.texto2)
    }

    // ---------------- backup ----------------
    private fun criar(cr: android.content.ContentResolver, pai: Uri, mime: String, nome: String): Uri? = try { DocumentsContract.createDocument(cr, pai, mime, nome) } catch (e: Exception) { null }

    fun backup(con: Console) {
        val ctx = con.ctx
        con.titulo("Backup")
        con.linha("Copia fotos, vídeos, documentos e downloads para um pendrive, cartão SD ou pasta que você escolher, e confere a cópia.", T.texto2)
        if (!con.garantirNome("Acesso aos arquivos")) return
        val base = Limpeza.raiz
        val opcoes = listOf("DCIM" to "Câmera (DCIM)", "Pictures" to "Imagens e prints", "Movies" to "Vídeos", "Documents" to "Documentos", "Download" to "Downloads", "Music" to "Músicas",
            "Android/media/com.whatsapp/WhatsApp/Media" to "Mídias do WhatsApp").filter { File(base, it.first).exists() }
        val sel = con.varios("O que copiar?", opcoes.map { it.second + "  ·  " + fmtTam(tamanho(File(base, it.first))) }, true, importante = true)
        if (sel.isEmpty()) return
        con.info("Escolha a pasta de destino (pendrive, cartão SD ou outra pasta).")
        val tree = con.escolherPasta() ?: run { con.linha("Cancelado."); return }
        try { ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) { }
        val cr = ctx.contentResolver
        val raizDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val pasta = criar(cr, raizDoc, DocumentsContract.Document.MIME_TYPE_DIR, "Backup SerpyraToolkit " + agora().replace("/", "-").replace(":", "h")) ?: run { con.erro("Não consegui criar a pasta no destino."); return }
        var arquivos = 0; var bytes = 0L; var falhas = 0
        fun copiar(orig: File, destPai: Uri) {
            if (con.cancelado) return
            if (orig.isDirectory) {
                val d = criar(cr, destPai, DocumentsContract.Document.MIME_TYPE_DIR, orig.name) ?: run { falhas++; return }
                for (f in orig.listFiles() ?: emptyArray()) copiar(f, d)
            } else {
                val u = criar(cr, destPai, "application/octet-stream", orig.name) ?: run { falhas++; return }
                try {
                    var n = 0L
                    cr.openOutputStream(u)?.use { os -> orig.inputStream().use { n = it.copyTo(os) } }
                    if (n == orig.length()) { arquivos++; bytes += n } else falhas++
                } catch (e: Exception) { falhas++ }
                if (arquivos % 200 == 0 && arquivos > 0) con.info("... $arquivos arquivos (${fmtTam(bytes)})")
            }
        }
        for (i in sel) {
            val (cam, nome) = opcoes[i]
            con.info("Copiando $nome...")
            copiar(File(base, cam), pasta)
        }
        // inventario de apps
        val inv = criar(cr, pasta, "text/plain", "apps-instalados.txt")
        if (inv != null) try { cr.openOutputStream(inv)?.use { os -> os.write(Jogos.todosApps(ctx).joinToString("\n") { it.nome + "  (" + it.pkg + ")" }.toByteArray()) } } catch (e: Exception) { }
        con.linha("")
        if (falhas == 0 && !con.cancelado) con.ok("Backup conferido: $arquivos arquivos, ${fmtTam(bytes)}") else con.aviso("$arquivos copiados (${fmtTam(bytes)}), $falhas falharam")
        con.info("Também salvei a lista dos seus apps (apps-instalados.txt). Conversas do WhatsApp: use o backup do próprio WhatsApp.")
        Store.putStr("ultimo_backup", agora() + " · " + fmtTam(bytes))
    }

    private fun tamanho(f: File): Long { var t = 0L; Limpeza.andar(f, null, { t += it.length() }); return t }

    fun restaurarFabrica(con: Console) {
        con.titulo("Restauração de fábrica")
        con.linha("Apaga TUDO e deixa o celular como novo. Resolve lentidão grave e vírus que não saem.", T.amarelo)
        val ub = Store.str("ultimo_backup")
        con.item("Último backup feito pelo app: " + ub.ifEmpty { "nenhum" }, if (ub.isEmpty()) T.vermelho else T.verde)
        con.item("Confira se a sua conta Google está sincronizada (Configurações > Google > Backup)")
        con.item("Faça o backup do WhatsApp dentro do próprio WhatsApp")
        con.item("Anote as senhas das contas dos jogos (ou vincule ao Google/Facebook)")
        if (!con.ask("Já fez tudo isso e quer abrir a tela de restauração do Android?", "Abrir", "Ainda não", importante = true)) return
        con.abrirEEsperar(Intent(Settings.ACTION_PRIVACY_SETTINGS))
        con.info("Procure “Redefinir” / “Restaurar” / “Gerenciamento geral > Redefinir”, se não estiver nesta tela.")
    }

    // ---------------- desfazer ----------------
    fun desfazer(con: Console) {
        con.titulo("Desfazer alterações")
        val n = Ajuste.quantos()
        if (n == 0) { con.linha("Nada para desfazer: o app não mudou nenhuma configuração."); return }
        con.linha("$n configuração(ões) alteradas pelo SerpyraToolkit.")
        if (!Ajuste.pode()) { con.aviso("Para desfazer automaticamente o app precisa do Modo avançado (ADB) ou root, os mesmos usados para mudar."); return }
        if (!con.ask("Voltar tudo ao original?", importante = true)) return
        val r = Ajuste.desfazer { con.ok("Restaurado: $it") }
        con.linha("")
        con.ok("$r item(ns) restaurado(s)")
    }

    fun info(con: Console) {
        con.titulo("Sobre o SerpyraToolkit")
        con.linha("Versão 5.1", T.verde)
        con.linha("Funciona sem root. Níveis de acesso:", T.texto2)
        con.item("Padrão: permissões do Android (arquivos, uso, não perturbe, sobreposição)")
        con.item("Automático (Shizuku): libera as permissões e faz os ajustes sozinho, sem PC")
        con.item("Avançado (ADB): o mesmo, com um comando pelo PC")
        con.item("Root (opcional): também libera o limite de CPU")
        con.linha("")
        con.linha("Nível atual: ${Acesso.nivel()}", T.azul)
        con.info(String.format(Locale.US, "Configurações alteradas: %d", Ajuste.quantos()))
    }
}
