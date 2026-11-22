package com.pctoolkit.mobile

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class Achado(val sev: Int, val titulo: String, val detalhe: String, val acao: String = "", val alvo: String = "")

/** SEGURANCA sem root: apps perigosos, espioes, acesso remoto, configuracoes de risco, APKs de mod e VirusTotal. */
object Seguranca {
    private val lojas = setOf("com.android.vending", "com.sec.android.app.samsungapps", "com.huawei.appmarket", "com.xiaomi.market", "com.xiaomi.mipicks",
        "com.amazon.venezia", "com.heytap.market", "com.oppo.market", "com.vivo.appstore", "com.motorola.ccc.ota", "org.fdroid.fdroid", "com.aurora.store", "com.google.android.feedback",
        "com.android.packageinstaller.ota", "com.google.android.packageinstaller")
    private val rxMalNome = Regex("xmrig|miner|coinhive|spynote|spymax|cerberus|anubis|hydra\\.|ermac|godfather|sova|flubot|teabot|joker|harly|" +
        "mspy|flexispy|hoverwatch|truthspy|spyic|cocospy|xnspy|thetruthspy|spyzie|ikeymonitor|clevguard|kidsguard|eyezy|umobix|cell\\.?tracker|spy\\.?phone|" +
        "stalker|keylog", RegexOption.IGNORE_CASE)
    private val rxRemoto = Regex("anydesk|teamviewer|rustdesk|airdroid|splashtop|remotepc|ammyy|supremo|chrome\\.remotedesktop|vnc|ultraviewer|hoptodesk|getscreen", RegexOption.IGNORE_CASE)

    fun sevNome(s: Int) = when { s >= 3 -> "ALTO"; s == 2 -> "MÉDIO"; s == 1 -> "BAIXO"; else -> "INFO" }
    fun sevCor(s: Int) = when { s >= 3 -> T.vermelho; s == 2 -> T.amarelo; s == 1 -> T.ciano; else -> T.cinza }

    fun instalador(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName else instaladorAntigo(pm, pkg)
    } catch (e: Exception) { null }

    @Suppress("DEPRECATION")
    private fun instaladorAntigo(pm: PackageManager, pkg: String): String? = pm.getInstallerPackageName(pkg)

    private fun foraDaLoja(pm: PackageManager, pkg: String): Boolean { val i = instalador(pm, pkg); return i == null || !lojas.contains(i) }

    private fun scanApps(ctx: Context, r: MutableList<Achado>) {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val pkgs: List<PackageInfo> = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        val acess = Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services") ?: ""
        val notif = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admins = (dpm.activeAdmins ?: emptyList()).map { it.packageName }.toSet()
        for (p in pkgs) {
            val ai = p.applicationInfo ?: continue
            val pkg = p.packageName
            if (pkg == ctx.packageName) continue
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue
            val nome = pm.getApplicationLabel(ai).toString()
            val fora = foraDaLoja(pm, pkg)
            val perms = (p.requestedPermissions ?: emptyArray()).toSet()
            val semIcone = pm.getLaunchIntentForPackage(pkg) == null
            val mot = ArrayList<String>()
            var sev = 0
            if (rxMalNome.containsMatchIn(pkg) || rxMalNome.containsMatchIn(nome)) { sev = 3; mot.add("nome de malware, minerador ou app espião conhecido") }
            val ehAcess = acess.contains("$pkg/")
            val ehAdmin = admins.contains(pkg)
            val ehNotif = notif.contains("$pkg/")
            if (ehAcess && fora) { sev = maxOf(sev, 3); mot.add("instalado fora da loja e com ACESSIBILIDADE ligada (pode ler a tela e clicar por você: técnica nº 1 de golpes bancários)") }
            if (ehAdmin && fora) { sev = maxOf(sev, 3); mot.add("fora da loja e é ADMINISTRADOR do aparelho (dificulta desinstalar)") }
            if (ehNotif && fora) { sev = maxOf(sev, 2); mot.add("fora da loja e lê todas as suas notificações (códigos de banco/SMS)") }
            val leSms = perms.contains("android.permission.READ_SMS") || perms.contains("android.permission.RECEIVE_SMS")
            if (leSms && fora) { sev = maxOf(sev, 2); mot.add("fora da loja e pede para ler SMS") }
            if (semIcone && fora && (ehAcess || ehAdmin || ehNotif || leSms)) { sev = 3; mot.add("e está ESCONDIDO (sem ícone)") }
            if (perms.contains("android.permission.REQUEST_INSTALL_PACKAGES") && fora && sev > 0) mot.add("pode instalar outros apps")
            if (rxRemoto.containsMatchIn(pkg)) { sev = maxOf(sev, 2); mot.add("app de ACESSO REMOTO: alguém pode ver e controlar o celular (golpe do falso suporte)") }
            if (sev >= 2) {
                val inst = instalador(pm, pkg) ?: "desconhecido (APK instalado manualmente)"
                r.add(Achado(sev, "App suspeito: $nome", "Pacote: $pkg\nInstalado por: $inst\nMotivo: " + mot.joinToString("; "), if (ehAcess) "app+acess" else if (ehAdmin) "app+admin" else "app", pkg))
            }
        }
    }

    private fun scanConfig(ctx: Context, r: MutableList<Achado>) {
        fun g(k: String) = Ajuste.ler("global", k)
        if (g("package_verifier_enable") == "0") r.add(Achado(3, "Play Protect DESLIGADO", "Malware costuma pedir para desligar. Ligue de novo.", "playprotect"))
        val shizuku = Priv.rodando()
        if (shizuku && (g("adb_wifi_enabled") == "1" || g("adb_enabled") == "1")) r.add(Achado(0, "Depuração ligada (usada pelo Shizuku)", "Necessária para o modo automático. Não deixe ninguém parear computadores com o seu celular."))
        else if (g("adb_wifi_enabled") == "1") r.add(Achado(3, "Depuração sem fio (ADB por Wi-Fi) LIGADA", "Um computador pareado na mesma rede controla o celular. Desligue se não estiver usando.", "dev"))
        if (!shizuku && g("adb_enabled") == "1") r.add(Achado(1, "Depuração USB ligada", "Um computador conectado pode instalar apps. Desligue quando não estiver usando" + if (Acesso.adb()) " (o Modo avançado do SerpyraToolkit continua funcionando)." else ".", "dev"))
        val proxy = g("http_proxy")
        if (proxy.isNotEmpty() && proxy != ":0") r.add(Achado(2, "Proxy global configurado: $proxy", "Todo o tráfego passa por esse servidor. Se não foi você, remova.", "set", "global:http_proxy::0"))
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) r.add(Achado(2, "Celular SEM bloqueio de tela", "Quem pegar o celular tem acesso a tudo, inclusive apps de banco.", "abrir", Settings.ACTION_SECURITY_SETTINGS))
        try {
            val p = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(Build.VERSION.SECURITY_PATCH)
            if (p != null) {
                val meses = ((System.currentTimeMillis() - p.time) / (30L * 86_400_000)).toInt()
                if (meses >= 6) r.add(Achado(if (meses >= 12) 2 else 1, "Atualização de segurança de ${Build.VERSION.SECURITY_PATCH} ($meses meses)", "Falhas corrigidas depois disso continuam abertas neste celular. Procure atualizações.", "abrir", "android.settings.SYSTEM_UPDATE_SETTINGS"))
            }
        } catch (e: Exception) { }
        if (Sh.rootConhecido()) r.add(Achado(0, "Celular com ROOT", "Mantenha o gerenciador de root atualizado e NEGUE pedidos de superusuário de apps que você não reconhece."))
    }

    private fun scanArquivos(r: MutableList<Achado>) {
        if (!Acesso.arquivos()) { r.add(Achado(0, "APKs baixados não verificados", "Permita o Acesso aos arquivos para procurar APKs de mod/cheat perigosos.", "perm")); return }
        val rxRisco = Regex("mod|hack|cheat|crack|inject|menu|aimbot|esp|unlock|premium|diamante|robux|vip", RegexOption.IGNORE_CASE)
        val base = Limpeza.raiz
        val pastas = listOf(File(base, "Download"), File(base, "Telegram"), File(base, "Android/media/org.telegram.messenger"), File(base, "WhatsApp/Media/WhatsApp Documents"), File(base, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"))
        var n = 0
        for (d in pastas) if (d.exists()) Limpeza.andar(d, null, { f ->
            val nm = f.name.lowercase()
            if (nm.endsWith(".apk") || nm.endsWith(".xapk") || nm.endsWith(".apks")) {
                n++
                if (rxRisco.containsMatchIn(f.name)) r.add(Achado(2, "APK de mod/cheat/crack: ${f.name}", "Arquivo: ${f.absolutePath.removePrefix(base.absolutePath)}\nÉ o disfarce mais comum de vírus que rouba contas. Verifique na VirusTotal antes de instalar.", "arquivo", f.absolutePath))
            }
        })
        if (n > 0) r.add(Achado(0, "$n instalador(es) .apk baixados", "Antes de instalar qualquer um: Segurança > Verificar arquivo na VirusTotal."))
    }

    fun cacar(con: Console) {
        val ctx = con.ctx
        con.titulo("Verificação de segurança")
        con.linha("Procura apps espiões, golpes de acesso remoto, apps com poderes perigosos instalados fora da loja e configurações de risco.", T.texto2)
        val r = ArrayList<Achado>()
        con.info("Analisando apps instalados..."); scanApps(ctx, r)
        con.info("Conferindo configurações..."); scanConfig(ctx, r)
        con.info("Procurando APKs baixados..."); scanArquivos(r)
        val ord = r.sortedByDescending { it.sev }
        val graves = ord.count { it.sev >= 2 }
        con.subtitulo("Resultado")
        if (ord.isEmpty()) con.ok("Nada suspeito encontrado.")
        for (a in ord) { con.linha("[${sevNome(a.sev)}] ${a.titulo}", sevCor(a.sev)); for (l in a.detalhe.lines()) con.info(l) }
        con.linha("")
        if (graves == 0) con.ok("Nenhum risco médio ou alto.") else con.aviso("$graves item(ns) merecem atenção.")
        Store.putStr("seg_ultimo", agora() + " · " + if (graves == 0) "sem riscos" else "$graves riscos")
        Store.salvarArr("seg_relatorio.json", org.json.JSONArray().also { a -> ord.forEach { a.put(JSONObject().put("sev", it.sev).put("t", it.titulo).put("d", it.detalhe)) } })
        val acionaveis = ord.filter { it.acao.isNotEmpty() && it.sev >= 1 }
        if (acionaveis.isEmpty()) return
        val sel = con.varios("Resolver agora", acionaveis.map { "[${sevNome(it.sev)}] ${it.titulo}" }, true)
        for (i in sel) { if (con.cancelado) break; tratar(con, acionaveis[i]) }
    }

    private fun tratar(con: Console, a: Achado) {
        val ctx = con.ctx
        when (a.acao) {
            "app", "app+acess", "app+admin" -> {
                if (a.acao == "app+acess") { con.linha("Primeiro desligue a acessibilidade dele (senão ele pode impedir a remoção).", T.amarelo); con.abrirEEsperar(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                if (a.acao == "app+admin") { con.linha("Primeiro tire ele de administrador: Segurança > Apps de administração do dispositivo.", T.amarelo); con.abrirEEsperar(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                val op = con.escolher(a.titulo, listOf("Desinstalar", "Abrir informações do app", if (Sh.privOk()) "Quarentena: desativar o app (reversível)" else "Ignorar"))
                when (op) {
                    0 -> Auto.desinstalar(con, a.alvo, a.titulo.removePrefix("App suspeito: "))
                    1 -> con.abrirEEsperar(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.alvo)))
                    2 -> if (Sh.privOk()) { Ajuste.comandoRoot("q:${a.alvo}", "pm disable-user --user 0 ${a.alvo}", "pm enable ${a.alvo}"); con.ok("Desativado (reative em Desfazer)") }
                }
                if (!Links.instalado(ctx, a.alvo)) con.ok("Removido")
            }
            "playprotect" -> if (Ajuste.pode()) { Ajuste.mudar("global", "package_verifier_enable", "1"); con.ok("Play Protect ligado (automático)") } else { con.linha("Abrindo a Play Store: toque na foto do perfil > Play Protect > Configurações > ligue “Verificar apps”.", T.azul); if (!Links.abrirPacote(ctx, "com.android.vending")) con.abrirConfig(Settings.ACTION_SECURITY_SETTINGS); con.sleep(1500) }
            "dev" -> con.abrirEEsperar(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            "set" -> {
                val p = a.alvo.split(':', limit = 3)
                if (p.size == 3 && Ajuste.pode()) { Ajuste.mudar(p[0], p[1], p[2]); con.ok("Corrigido (reversível em Desfazer)") }
                else con.abrirEEsperar(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            "arquivo" -> {
                val f = File(a.alvo)
                when (con.escolher(f.name, listOf("Verificar na VirusTotal", "Mover para a Lixeira", "Ignorar"))) {
                    0 -> verificarVt(con, f.name, f.length()) { f.inputStream() }
                    1 -> if (Lixeira.mover(f)) con.ok("Movido para a Lixeira")
                }
            }
            "perm" -> con.garantirNome("Acesso aos arquivos")
            "abrir" -> con.abrirEEsperar(Intent(a.alvo))
        }
    }

    fun ultimoRelatorio(con: Console) {
        con.titulo("Último relatório")
        con.linha(Store.str("seg_ultimo").ifEmpty { "Nenhuma verificação feita ainda." }, T.texto2)
        val a = Store.lerArr("seg_relatorio.json")
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); con.linha("[${sevNome(o.optInt("sev"))}] ${o.optString("t")}", sevCor(o.optInt("sev"))); con.info(o.optString("d").replace("\n", " · ")) }
    }

    fun blindagem(con: Console) {
        val ctx = con.ctx
        con.titulo("Blindagem")
        con.linha("Fecha as portas que golpes e vírus usam para entrar. Passo a passo:", T.texto2)
        con.subtitulo("1. Play Protect")
        con.info(if (Ajuste.ler("global", "package_verifier_enable") == "0") "DESLIGADO: ligue na Play Store > perfil > Play Protect." else "Ligado ✓")
        con.subtitulo("2. Instalar apps desconhecidos")
        con.info("Só deixe permitido para quem você realmente usa para instalar APKs. Tire do navegador, WhatsApp e Telegram.")
        if (con.ask("Abrir a lista de apps que podem instalar outros apps?")) con.abrirEEsperar(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
        con.subtitulo("3. Acessibilidade")
        con.info("Só leitores de tela e apps que você conhece devem ter acessibilidade. Golpes pedem isso para “funcionar”.")
        if (con.ask("Revisar a acessibilidade agora?")) con.abrirEEsperar(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        con.subtitulo("4. Bloqueio de tela")
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) { con.aviso("Sem bloqueio de tela."); if (con.ask("Configurar agora?")) con.abrirEEsperar(Intent(Settings.ACTION_SECURITY_SETTINGS)) } else con.ok("Ativo")
        con.subtitulo("5. Atualização do sistema")
        con.info("Atualização de segurança: ${Build.VERSION.SECURITY_PATCH}")
        if (con.ask("Procurar atualizações?")) con.abrirEEsperar(Intent("android.settings.SYSTEM_UPDATE_SETTINGS"))
        con.subtitulo("Regras de ouro")
        con.item("Mod, cheat e “diamante grátis” são a isca nº 1 de vírus que roubam contas")
        con.item("Ninguém de banco ou “suporte” pede para instalar AnyDesk/TeamViewer")
        con.item("Verifique todo APK na VirusTotal antes de instalar")
        con.item("Use uma conta secundária do jogo para testar qualquer coisa duvidosa")
    }

    fun caixaDeAreia(con: Console) {
        con.titulo("Testar um app com segurança")
        con.linha("Se precisar abrir um app duvidoso, isole ele do resto do celular:", T.texto2)
        con.item("Pasta Segura (Samsung) ou Segundo Espaço (Xiaomi): o app fica num perfil separado, sem ver suas fotos e contas")
        con.item("Perfil de trabalho com o app “Shelter” (F-Droid) em qualquer Android")
        con.item("Espaço privado (Android 15+): Configurações > Segurança e privacidade > Espaço privado")
        con.item("Não faça login em contas reais dentro do app testado")
        con.item("Depois, desinstale e rode a Verificação de segurança")
    }

    // ---------------- VirusTotal ----------------
    private fun vtChave(con: Console): String? {
        val c = Store.str("vt_api_key")
        if (c.length >= 40) return c
        con.linha("A VirusTotal analisa o arquivo com cerca de 70 antivírus. Precisa de uma chave gratuita:", T.texto2)
        con.item("Crie uma conta em virustotal.com, abra o seu perfil > API key e copie a chave")
        val k = con.texto("Chave da API da VirusTotal", "cole aqui")?.trim() ?: return null
        if (k.length < 40) { con.erro("Chave inválida."); return null }
        Store.putStr("vt_api_key", k)
        return k
    }

    fun configurarVt(con: Console) { Store.putStr("vt_api_key", ""); if (vtChave(con) != null) con.ok("Chave salva") }

    private fun sha256(s: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        s.use { val b = ByteArray(65536); while (true) { val n = it.read(b); if (n <= 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { String.format("%02x", it) }
    }

    private fun httpGet(url: String, chave: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("x-apikey", chave)
        c.connectTimeout = 15000; c.readTimeout = 30000
        val code = c.responseCode
        val body = try { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
        c.disconnect()
        return Pair(code, body)
    }

    private fun httpUpload(chave: String, nome: String, abrir: () -> InputStream): String? {
        val limite = "----pctoolkit" + System.nanoTime()
        val c = URL("https://www.virustotal.com/api/v3/files").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setChunkedStreamingMode(64 * 1024)
        c.setRequestProperty("x-apikey", chave)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$limite")
        c.connectTimeout = 20000; c.readTimeout = 120000
        c.outputStream.use { os ->
            os.write(("--$limite\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + nome.replace("\"", "") + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray())
            abrir().use { it.copyTo(os) }
            os.write("\r\n--$limite--\r\n".toByteArray())
        }
        val code = c.responseCode
        val body = try { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
        c.disconnect()
        if (code >= 300) return null
        return JSONObject(body).optJSONObject("data")?.optString("id")
    }

    private fun verificarVt(con: Console, nome: String, tamanho: Long, abrir: () -> InputStream): Int {
        val chave = vtChave(con) ?: return -1
        con.info("Calculando a impressão digital (SHA-256)...")
        val hash = sha256(abrir())
        con.info("Consultando a VirusTotal...")
        var (code, body) = httpGet("https://www.virustotal.com/api/v3/files/$hash", chave)
        if (code == 401) { con.erro("Chave inválida. Configure de novo."); Store.putStr("vt_api_key", ""); return -1 }
        if (code == 429) { con.aviso("Limite da conta gratuita (4 por minuto). Espere 1 minuto."); return -1 }
        if (code == 404) {
            con.aviso("Arquivo ainda desconhecido pela VirusTotal.")
            if (tamanho > 32L * 1024 * 1024) { con.info("Tem mais de 32 MB: envie pelo site virustotal.com."); return -1 }
            if (!con.ask("Enviar o arquivo para análise da VirusTotal agora? (1 a 3 minutos)", importante = true)) return -1
            val id = httpUpload(chave, nome, abrir) ?: run { con.erro("Falha no envio."); return -1 }
            con.info("Enviado. Aguardando o resultado...")
            val fim = System.currentTimeMillis() + 5 * 60_000
            while (System.currentTimeMillis() < fim && !con.cancelado) {
                Thread.sleep(10_000)
                val (c2, b2) = httpGet("https://www.virustotal.com/api/v3/analyses/$id", chave)
                if (c2 == 200 && JSONObject(b2).optJSONObject("data")?.optJSONObject("attributes")?.optString("status") == "completed") break
            }
            val r = httpGet("https://www.virustotal.com/api/v3/files/$hash", chave)
            code = r.first; body = r.second
        }
        if (code != 200) { con.erro("Não consegui ler o resultado (HTTP $code)."); return -1 }
        val at = JSONObject(body).getJSONObject("data").getJSONObject("attributes")
        val st = at.getJSONObject("last_analysis_stats")
        val mal = st.optInt("malicious"); val sus = st.optInt("suspicious")
        val tot = mal + sus + st.optInt("undetected") + st.optInt("harmless")
        con.subtitulo(nome)
        con.linha("Malicioso: $mal/$tot · Suspeito: $sus/$tot")
        val v = when {
            mal == 0 && sus == 0 -> { con.ok("Nenhum antivírus detectou nada. Sinal forte de que está limpo."); 0 }
            mal + sus <= 2 -> { con.aviso("Poucas detecções: pode ser falso positivo (comum em mods). Teste isolado (Testar app com segurança)."); 1 }
            else -> {
                con.erro("VÁRIOS antivírus detectaram. Risco real: NÃO instale nem abra.")
                val res = at.optJSONObject("last_analysis_results")
                if (res != null) { var n = 0; for (k in res.keys()) { val o = res.getJSONObject(k); if ((o.optString("category") == "malicious") && n < 6) { con.info("$k: " + o.optString("result")); n++ } } }
                2
            }
        }
        con.info("Relatório: virustotal.com/gui/file/$hash")
        return v
    }

    fun verificarArquivoVt(con: Console) {
        val ctx = con.ctx
        con.titulo("Verificar na VirusTotal")
        val opc = con.escolher("O que verificar?", listOf("Um arquivo (APK baixado etc.)", "Um app já instalado", "Trocar a chave da API"))
        when (opc) {
            0 -> {
                val uri = con.escolherArquivo() ?: return
                var nome = "arquivo"; var tam = 0L
                try {
                    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val iN = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (iN >= 0) nome = c.getString(iN) ?: nome
                            val iS = c.getColumnIndex(android.provider.OpenableColumns.SIZE); if (iS >= 0) tam = c.getLong(iS)
                        }
                    }
                } catch (e: Exception) { }
                verificarVt(con, nome, tam) { ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("sem acesso") }
            }
            1 -> {
                val apps = Jogos.todosApps(ctx)
                val i = con.escolher("Qual app?", apps.map { it.nome })
                if (i < 0) return
                val f = File(ctx.packageManager.getApplicationInfo(apps[i].pkg, 0).sourceDir)
                if (verificarVt(con, apps[i].nome + ".apk", f.length()) { f.inputStream() } == 2 && con.ask("Desinstalar ${apps[i].nome} agora?", importante = true)) Auto.desinstalar(con, apps[i].pkg, apps[i].nome)
            }
            2 -> configurarVt(con)
        }
    }
}
