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

class Achado(val sev: Int, val cat: String, val titulo: String, val detalhe: String, val acao: String = "", val alvo: String = "")

/**
 * SECAO 7: CACADOR DE AMEACAS para Android (heuristico).
 * Procura: apps instalados fora da loja com permissoes perigosas, servicos de acessibilidade/administradores
 * suspeitos, stalkerware, mineradores (CPU e conexoes com pools), acesso remoto, depuracao USB aberta,
 * Play Protect desligado, SELinux permissivo, proxy, arquivo hosts. Nada e removido sem confirmacao;
 * a "quarentena" desativa o app (reversivel).
 */
object Seguranca {
    private val lojas = setOf("com.android.vending", "com.sec.android.app.samsungapps", "com.huawei.appmarket", "com.xiaomi.market", "com.xiaomi.mipicks",
        "com.amazon.venezia", "com.heytap.market", "com.oppo.market", "com.vivo.appstore", "com.motorola.ccc.ota", "org.fdroid.fdroid", "com.aurora.store", "com.google.android.feedback")
    private val rxMalNome = Regex("xmrig|miner|coinhive|spynote|spymax|cerberus|anubis|hydra\\.|alien|ermac|godfather|sova|flubot|teabot|joker|harly|" +
        "mspy|flexispy|hoverwatch|truthspy|spyic|cocospy|xnspy|thetruthspy|spyzie|ikeymonitor|clevguard|kidsguard|eyezy|umobix|cell\\.?tracker|spy\\.?phone|" +
        "monitor\\.?app|stalker|keylog", RegexOption.IGNORE_CASE)
    private val rxRemoto = Regex("anydesk|teamviewer|rustdesk|carriez|airdroid|splashtop|remotepc|ammyy|supremo|chrome\\.remotedesktop|vnc|ultraviewer|hoptodesk|getscreen", RegexOption.IGNORE_CASE)
    private val portasMiner = setOf(3333, 3334, 4444, 5555, 7777, 14433, 14444, 45560, 45700, 19999, 10128, 10300, 10343, 3357)

    private var achados = ArrayList<Achado>()
    private val chaves = HashSet<String>()

    private fun add(a: Achado) {
        val k = a.cat + "|" + a.titulo
        if (chaves.add(k)) achados.add(a)
    }

    fun sevNome(s: Int) = when { s >= 3 -> "ALTO"; s == 2 -> "MEDIO"; s == 1 -> "BAIXO"; else -> "INFO" }
    fun sevCor(s: Int) = when { s >= 3 -> C.vermelho; s == 2 -> C.amarelo; s == 1 -> C.ciano; else -> C.cinza }

    fun instalador(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName else instaladorAntigo(pm, pkg)
    } catch (e: Exception) { null }

    @Suppress("DEPRECATION")
    private fun instaladorAntigo(pm: PackageManager, pkg: String): String? = pm.getInstallerPackageName(pkg)

    private fun foraDaLoja(pm: PackageManager, pkg: String): Boolean {
        val i = instalador(pm, pkg)
        return i == null || !lojas.contains(i)
    }

    // ---------------- 1) apps ----------------
    private fun scanApps(con: Console, ctx: Context, soMinerador: Boolean) {
        con.info("[1] Analisando apps instalados e permissoes...")
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val pkgs: List<PackageInfo> = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        val acess = (Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services") ?: "")
        val notif = (Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "")
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admins = (dpm.activeAdmins ?: emptyList()).map { it.packageName }.toSet()
        val sms = try { Settings.Secure.getString(ctx.contentResolver, "sms_default_application") ?: "" } catch (e: Exception) { "" }
        val instaladores = if (Sh.temRoot()) Sh.root("cmd appops query-op REQUEST_INSTALL_PACKAGES allow 2>/dev/null", 20).out.lines().map { it.trim() }.toSet() else emptySet()
        for (p in pkgs) {
            val ai = p.applicationInfo ?: continue
            val pkg = p.packageName
            if (pkg == ctx.packageName) continue
            val sistema = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (sistema) continue
            val nome = pm.getApplicationLabel(ai).toString()
            val fora = foraDaLoja(pm, pkg)
            val perms = (p.requestedPermissions ?: emptyArray()).toSet()
            val semIcone = pm.getLaunchIntentForPackage(pkg) == null
            val mot = ArrayList<String>()
            var sev = 0
            if (rxMalNome.containsMatchIn(pkg) || rxMalNome.containsMatchIn(nome)) { sev = 3; mot.add("nome de malware/minerador/app espiao conhecido") }
            if (soMinerador) {
                if (sev > 0) add(Achado(sev, "App", "App suspeito: $nome", "Pacote: $pkg\nMotivo: " + mot.joinToString("; "), "app", pkg))
                continue
            }
            val ehAcess = acess.contains("$pkg/")
            val ehNotif = notif.contains("$pkg/")
            val ehAdmin = admins.contains(pkg)
            if (ehAcess) { if (fora) { sev = maxOf(sev, 3); mot.add("instalado FORA da loja e com ACESSIBILIDADE ligada (pode ler a tela, digitar e clicar por voce: tecnica nº 1 de trojans bancarios)") } else mot.add("tem acessibilidade ligada") }
            if (ehAdmin) { if (fora) { sev = maxOf(sev, 3); mot.add("fora da loja e e ADMINISTRADOR do aparelho (dificulta desinstalar)") } else mot.add("e administrador do aparelho") }
            if (ehNotif && fora) { sev = maxOf(sev, 2); mot.add("fora da loja e LE todas as suas notificacoes (codigos de banco/SMS)") }
            val leSms = perms.contains("android.permission.READ_SMS") || perms.contains("android.permission.RECEIVE_SMS")
            if (leSms && fora && pkg != sms) { sev = maxOf(sev, 2); mot.add("fora da loja e pede para ler SMS") }
            if (semIcone && fora && (ehAcess || ehAdmin || ehNotif || leSms)) { sev = 3; mot.add("e esta ESCONDIDO (sem icone na tela inicial)") }
            if (instaladores.contains(pkg) && fora) { sev = maxOf(sev, 2); mot.add("fora da loja e pode INSTALAR outros apps") }
            if (rxRemoto.containsMatchIn(pkg)) { sev = maxOf(sev, 2); mot.add("app de ACESSO REMOTO: alguem pode ver/controlar o celular (golpe comum: 'suporte' por telefone)") }
            if (sev >= 2) {
                val inst = instalador(pm, pkg) ?: "desconhecido (APK instalado manualmente)"
                add(Achado(sev, "App", "App suspeito: $nome", "Pacote: $pkg\nInstalado por: $inst\nMotivo: " + mot.joinToString("; "), if (ehAcess) "app+acess" else "app", pkg))
            }
        }
    }

    // ---------------- 2) CPU ----------------
    private fun scanCpu(con: Console, ctx: Context) {
        if (!Sh.temRoot()) return
        con.info("[2] Medindo consumo de CPU (evite usar o celular agora)...")
        Sh.root("top -b -n 1 -d 1 >/dev/null 2>&1", 10)
        val top = Sh.root("top -b -n 1 -d 3 -m 10 2>/dev/null", 20).out.lines()
        val i0 = top.indexOfFirst { it.contains("PID") && it.contains("CPU") }
        if (i0 < 0) return
        val cab = top[i0].trim().split(Regex("\\s+"))
        val ic = cab.indexOfFirst { it.contains("CPU") }
        val pm = ctx.packageManager
        for (l in top.drop(i0 + 1)) {
            val p = l.trim().split(Regex("\\s+"))
            if (ic < 0 || p.size <= ic) continue
            val cpu = p[ic].replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: continue
            if (cpu < 25) continue
            val nome = p.last()
            if (nome == "top" || nome == "su" || nome.contains("pctoolkit") || nome.startsWith("kworker") || nome.startsWith("surfaceflinger") || nome.startsWith("system_server")) continue
            val pkg = nome.substringBefore(':')
            val fora = pkg.contains('.') && try { pm.getApplicationInfo(pkg, 0); foraDaLoja(pm, pkg) } catch (e: Exception) { false }
            val sev = if (fora) 3 else 1
            add(Achado(sev, "CPU", "Consumo alto de CPU: $nome (${cpu.toInt()}%)", "Motivo: " + if (fora) "app instalado fora da loja usando muita CPU com o celular parado (comportamento tipico de minerador)" else "uso alto agora; se continuar com o celular parado, investigue", if (fora) "app" else "", if (fora) pkg else ""))
        }
    }

    // ---------------- 3) rede ----------------
    private fun scanRede(con: Console, ctx: Context) {
        if (!Sh.temRoot()) return
        con.info("[3] Analisando conexoes de rede...")
        val out = Sh.root("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null", 15).out
        val pm = ctx.packageManager
        for (l in out.lines()) {
            val p = l.trim().split(Regex("\\s+"))
            if (p.size < 8 || !p[0].endsWith(":")) continue
            val st = p[3]
            val uid = p[7].toIntOrNull() ?: continue
            if (uid < 10000) continue
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: continue
            if (st == "01") {
                val porta = p[2].substringAfterLast(':').toIntOrNull(16) ?: continue
                if (portasMiner.contains(porta)) add(Achado(3, "Rede", "Conexao em porta de pool de mineracao: " + Jogos.nome(ctx, pkg), "Pacote: $pkg\nPorta remota: $porta\nMotivo: porta tipica de minerador de criptomoeda", "app", pkg))
            } else if (st == "0A") {
                val ip = p[1].substringBefore(':')
                val todas = ip.trim('0').isEmpty()
                if (todas && foraDaLoja(pm, pkg)) {
                    val porta = p[1].substringAfterLast(':').toIntOrNull(16) ?: 0
                    add(Achado(2, "Rede", "App fora da loja aceitando conexoes externas: " + Jogos.nome(ctx, pkg), "Pacote: $pkg\nPorta aberta: $porta\nMotivo: possivel backdoor/acesso remoto", "app", pkg))
                }
            }
        }
    }

    // ---------------- 4) configuracoes ----------------
    private fun scanConfig(con: Console, ctx: Context) {
        con.info("[4] Conferindo Play Protect, depuracao, SELinux, proxy, hosts e bloqueio de tela...")
        val root = Sh.temRoot()
        fun g(k: String) = if (root) Sh.setting("global", k) else (Settings.Global.getString(ctx.contentResolver, k) ?: "")
        if (g("package_verifier_enable") == "0") add(Achado(3, "Protecao", "Play Protect (verificacao de apps) DESLIGADO", "Motivo: malware costuma pedir para desligar. Ligue de novo (opcao de correcao).", "set", "global:package_verifier_enable:1"))
        if (g("adb_wifi_enabled") == "1") add(Achado(3, "Acesso", "DEPURACAO SEM FIO (ADB por Wi-Fi) LIGADA", "Motivo: qualquer computador pareado na mesma rede controla o celular. Desligue se nao estiver usando.", "set", "global:adb_wifi_enabled:0"))
        if (g("adb_enabled") == "1") add(Achado(2, "Acesso", "Depuracao USB ligada", "Motivo: um computador conectado pode instalar apps e ler dados. Desligue quando nao estiver usando.", "set", "global:adb_enabled:0"))
        val proxy = g("http_proxy")
        if (proxy.isNotEmpty() && proxy != ":0") add(Achado(2, "Rede", "Proxy global configurado: $proxy", "Motivo: todo o trafego passa por esse servidor. Se nao foi voce, remova.", "set", "global:http_proxy::0"))
        if (root) {
            if (Sh.root("getenforce", 10).out.trim().equals("Permissive", true)) add(Achado(3, "Protecao", "SELinux em modo PERMISSIVO", "Motivo: desliga a principal barreira entre os apps do Android. ROMs/kernels mal configurados fazem isso.", "selinux", ""))
            val keys = Sh.root("[ -s /data/misc/adb/adb_keys ] && wc -l < /data/misc/adb/adb_keys", 10).out.trim().toIntOrNull()
            if (keys != null && keys > 0) add(Achado(1, "Acesso", "$keys computador(es) autorizado(s) para depuracao USB", "Motivo: esses computadores entram sem pedir permissao. Revogue se nao reconhece.", "adbkeys", ""))
            val hosts = Sh.ler("/system/etc/hosts").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.contains("localhost") }
            val ruins = hosts.filter { it.contains(Regex("google|play|android|samsung|bank|banco|itau|bradesco|nubank|caixa|santander|whatsapp", RegexOption.IGNORE_CASE)) && !it.startsWith("0.0.0.0") && !it.startsWith("127.") }
            if (ruins.isNotEmpty()) add(Achado(3, "Rede", "Arquivo hosts redireciona sites importantes", "Linhas: " + ruins.take(5).joinToString(" | ") + "\nMotivo: pode mandar voce para site falso de banco/Google.", "", ""))
            else if (hosts.size > 50) add(Achado(0, "Rede", "Arquivo hosts com ${hosts.size} linhas (provavel bloqueador de anuncios)", "", "", ""))
        }
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) add(Achado(2, "Protecao", "Celular SEM bloqueio de tela (PIN/senha/digital)", "Motivo: quem pegar o celular tem acesso a tudo, inclusive aos apps de banco.", "abrir", Settings.ACTION_SECURITY_SETTINGS))
        if (root) add(Achado(0, "Protecao", "Celular com ROOT", "Mantenha o gerenciador de root (Magisk/KernelSU/APatch) atualizado e NEGUE pedidos de superusuario de apps que voce nao reconhece.", "", ""))
    }

    // ---------------- 5) arquivos ----------------
    private fun scanArquivos(con: Console) {
        if (!Sh.temRoot()) return
        con.info("[5] Procurando arquivos suspeitos (Download, /data/local/tmp)...")
        val tmp = Sh.root("ls -la /data/local/tmp 2>/dev/null", 15).out.lines().filter { it.startsWith("-") && it.contains("x") }
        for (l in tmp) {
            val nome = l.trim().split(Regex("\\s+")).last()
            val sev = if (rxMalNome.containsMatchIn(nome)) 3 else 1
            add(Achado(sev, "Arquivo", "Executavel em /data/local/tmp: $nome", "Motivo: lugar usado por ferramentas de ADB e tambem por malware. Se nao reconhece, apague.", "rm", "/data/local/tmp/$nome"))
        }
        val apks = Sh.root("find ${Sd.base}/Download ${Sd.base}/Telegram ${Sd.base}/Android/media/org.telegram.messenger -maxdepth 4 -type f -iname '*.apk' 2>/dev/null", 60).out.lines().filter { it.isNotBlank() }
        val rxRisco = Regex("mod|hack|cheat|crack|inject|menu|aimbot|esp|unlock|premium|free.?fire|diamante|robux|apk.?pure", RegexOption.IGNORE_CASE)
        for (a in apks) {
            val n = a.substringAfterLast('/')
            if (rxRisco.containsMatchIn(n)) add(Achado(2, "Arquivo", "APK de mod/cheat/crack: $n", "Arquivo: ${a.replace(Sd.base, "/sdcard")}\nMotivo: e o disfarce mais comum de trojan/stealer. Verifique na VirusTotal (Blindagem) antes de instalar.", "rm", a))
        }
        if (apks.isNotEmpty()) add(Achado(0, "Arquivo", "${apks.size} arquivo(s) .apk baixados", "Antes de instalar qualquer um: Seguranca > Blindagem > Verificar arquivo na VirusTotal.", "", ""))
    }

    // ---------------- tratar ----------------
    private fun tratar(con: Console, ctx: Context, a: Achado) {
        when (a.acao) {
            "app", "app+acess" -> {
                val op = con.escolher(a.titulo, listOf("QUARENTENA: desativar o app (reversivel)", "Desinstalar", "Ignorar"))
                if (op == 0) quarentena(con, ctx, a.alvo, a.titulo)
                if (op == 1) {
                    if (a.acao == "app+acess") removerAcessibilidade(ctx, a.alvo)
                    if (Sh.temRoot() && con.ask("Desinstalar ${a.alvo} direto (sem a tela do Android)?")) { Sh.root("pm uninstall --user 0 ${a.alvo}", 60); con.ok("Desinstalado") }
                    else con.abrir(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.alvo)))
                }
            }
            "set" -> {
                val p = a.alvo.split(':', limit = 3)
                if (p.size == 3 && con.ask("Corrigir? (" + p[1] + " = " + p[2] + ", reversivel no Desfazer)")) {
                    if (con.exigeRoot()) { Undo.global.setting(p[0], p[1], p[2]); con.ok("Corrigido") }
                }
            }
            "selinux" -> if (con.ask("Voltar o SELinux para ENFORCING agora? (vale ate reiniciar; se a ROM travar, reinicie)")) { Sh.root("setenforce 1", 10); con.ok("SELinux enforcing") }
            "adbkeys" -> if (con.ask("Revogar TODOS os computadores autorizados para depuracao USB?")) { Sh.root("rm -f /data/misc/adb/adb_keys", 10); con.ok("Autorizacoes revogadas") }
            "rm" -> if (con.ask("Mover para a Lixeira do toolkit: ${a.alvo.substringAfterLast('/')}?")) {
                Sh.root("mkdir -p ${Sh.q(Sd.lixeira)}; mv ${Sh.q(a.alvo)} ${Sh.q(Sd.lixeira + "/" + System.currentTimeMillis() + "-" + a.alvo.substringAfterLast('/'))}", 30)
                con.ok("Movido para a Lixeira do toolkit")
            }
            "abrir" -> con.abrirConfig(a.alvo)
            else -> con.info("Item informativo: siga a orientacao do motivo.")
        }
    }

    private fun removerAcessibilidade(ctx: Context, pkg: String) {
        if (!Sh.temRoot()) return
        val atual = Sh.setting("secure", "enabled_accessibility_services")
        val novo = atual.split(':').filter { it.isNotBlank() && !it.startsWith("$pkg/") }.joinToString(":")
        Sh.root("settings put secure enabled_accessibility_services ${Sh.q(novo)}", 10)
    }

    fun quarentena(con: Console, ctx: Context, pkg: String, motivo: String) {
        if (!con.exigeRoot()) return
        removerAcessibilidade(ctx, pkg)
        Sh.root("am force-stop $pkg; pm disable-user --user 0 $pkg", 30)
        Store.adicionar("quarentena.json", JSONObject().put("pkg", pkg).put("nome", Jogos.nome(ctx, pkg)).put("data", agora()).put("motivo", motivo), 500)
        con.ok("Em quarentena (desativado e parado): $pkg")
        try {
            val src = ctx.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).sourceDir
            con.info("SHA256 do APK: " + sha256(File(src).inputStream()) + "  (pesquise em virustotal.com)")
        } catch (e: Exception) { }
        Log2.registrar("Quarentena: $pkg")
    }

    fun menuQuarentena(con: Console, ctx: Context) {
        con.titulo("QUARENTENA")
        val a = Store.lerArr("quarentena.json")
        if (a.length() == 0) { con.linha("Quarentena vazia."); return }
        val itens = (0 until a.length()).map { a.getJSONObject(it) }
        for ((i, o) in itens.withIndex()) { con.linha("${i + 1}) ${o.optString("nome")} (${o.optString("pkg")})  - ${o.optString("data")}"); con.info("   motivo: " + o.optString("motivo")) }
        val op = con.escolher("O que fazer?", listOf("Restaurar (reativar)", "Desinstalar de vez", "Voltar"))
        if (op < 0 || op == 2) return
        if (!con.exigeRoot()) return
        val sel = con.varios(if (op == 0) "Restaurar" else "Desinstalar", itens.map { it.optString("nome") })
        val resto = org.json.JSONArray()
        for ((i, o) in itens.withIndex()) {
            if (!sel.contains(i)) { resto.put(o); continue }
            val pkg = o.optString("pkg")
            if (op == 0) { Sh.root("pm enable $pkg", 20); con.ok("Restaurado: $pkg") }
            else { Sh.root("pm uninstall --user 0 $pkg", 60); con.ok("Desinstalado: $pkg") }
        }
        Store.salvarArr("quarentena.json", resto)
    }

    fun cacar(con: Console, ctx: Context, modo: String) {
        achados = ArrayList(); chaves.clear()
        con.titulo("CACADOR DE AMEACAS - varredura $modo")
        con.linha("Procura: apps espioes/trojans bancarios, mineradores, acesso remoto, permissoes perigosas,")
        con.linha("protecoes desligadas e computadores com acesso ao celular.")
        con.linha("E uma analise heuristica: pode haver falso positivo (mods, apps de outras lojas). Nada e removido sem", C.amarelo)
        con.linha("a sua confirmacao, e a quarentena e reversivel.", C.amarelo)
        if (!con.ask("Iniciar a varredura?")) return
        if (!Sh.temRoot()) con.aviso("Sem root a varredura fica limitada (sem CPU, rede e arquivos).")
        scanApps(con, ctx, modo == "Minerador")
        scanCpu(con, ctx)
        scanRede(con, ctx)
        if (modo != "Minerador") scanConfig(con, ctx)
        if (modo == "Completa" || modo == "Minerador") scanArquivos(con)

        val lista = achados.sortedByDescending { it.sev }
        val altos = lista.count { it.sev >= 3 }; val medios = lista.count { it.sev == 2 }; val baixos = lista.count { it.sev == 1 }
        con.linha("")
        con.linha("================ RESULTADO ================", C.ciano)
        if (lista.isEmpty()) con.ok("Nenhum sinal de ameaca encontrado por esta varredura.")
        val rel = StringBuilder("PC Toolkit Mobile - relatorio de seguranca ($modo) - ${agora()}\n${Build.MANUFACTURER} ${Build.MODEL}\n\n")
        for ((i, a) in lista.withIndex()) {
            con.linha(String.format(java.util.Locale.US, "%3d) [%s] %s", i + 1, sevNome(a.sev), a.titulo), sevCor(a.sev))
            for (l in a.detalhe.lines()) if (l.isNotBlank()) con.info("      $l")
            rel.append("${i + 1}) [${sevNome(a.sev)}] ${a.titulo}\n").append(a.detalhe.prependIndent("      ")).append("\n")
        }
        con.linha("")
        con.linha("Resumo: $altos ALTO | $medios MEDIO | $baixos BAIXO", sevCor(if (altos > 0) 3 else if (medios > 0) 2 else if (baixos > 0) 1 else 0))
        try { File(ctx.filesDir, "ultimo-relatorio-seguranca.txt").writeText(rel.toString()) } catch (e: Exception) { }
        if (Sh.temRoot()) Sh.gravar("${Sd.base}/Download/relatorio-seguranca.txt", rel.toString())
        Log2.registrar("Cacador ($modo): $altos alto, $medios medio, $baixos baixo")
        val acion = lista.filter { it.acao.isNotEmpty() }
        if (acion.isNotEmpty()) {
            val sel = con.varios("Tratar quais itens?", acion.map { "[" + sevNome(it.sev) + "] " + it.titulo }, false)
            for (i in sel) { con.linha(""); con.linha("--- " + acion[i].titulo, sevCor(acion[i].sev)); tratar(con, ctx, acion[i]) }
            if (sel.isNotEmpty()) con.ok("Tratamento concluido. Rode a varredura de novo depois de reiniciar para confirmar que nada voltou.")
        }
        if (altos > 0) {
            con.linha("")
            con.linha("SE HOUVE ACHADOS ALTO:", C.vermelho)
            con.linha("  1) Troque as senhas (e-mail, banco, Google, redes sociais) de OUTRO aparelho e ative a verificacao em 2 etapas.")
            con.linha("  2) Rode o Play Protect (menu Seguranca > Protecao do Google).")
            con.linha("  3) Se voltar mesmo assim: backup e restauracao de fabrica (menu Megazord).")
        }
    }

    fun ultimoRelatorio(con: Console, ctx: Context) {
        val f = File(ctx.filesDir, "ultimo-relatorio-seguranca.txt")
        if (!f.exists()) { con.linha("Ainda nao ha relatorio."); return }
        for (l in f.readLines()) con.linha(l)
    }

    fun playProtect(con: Console) {
        con.titulo("PROTECAO DO GOOGLE (Play Protect) - equivalente ao Windows Defender")
        con.linha("O Play Protect verifica os apps instalados com a base do Google. Vou abrir a tela dele:")
        con.linha("toque em 'Verificar' e confirme que 'Verificar apps com o Play Protect' esta LIGADO.")
        val i = Intent().setClassName("com.google.android.gms", "com.google.android.gms.security.settings.VerifyAppsSettingsActivity")
        try { con.abrir(i) } catch (e: Exception) { con.abrirConfig(Settings.ACTION_SECURITY_SETTINGS) }
        con.info("Sem Google no celular? Use a Blindagem > VirusTotal para verificar apps instalados.")
    }

    // ---------------- BLINDAGEM ----------------
    fun protegerAgora(con: Console, ctx: Context) {
        con.titulo("PROTEGER AGORA")
        if (!con.exigeRoot()) return
        val u = Undo.global
        u.setting("global", "package_verifier_enable", "1")
        u.setting("global", "verifier_verify_adb_installs", "1")
        u.setting("global", "upload_apk_enable", "1")
        con.ok("Play Protect ligado, inclusive para apps instalados por cabo/arquivo, e envio de apps desconhecidos para analise")
        if (Sh.setting("global", "adb_wifi_enabled") == "1" && con.ask("Desligar a depuracao SEM FIO?")) { u.setting("global", "adb_wifi_enabled", "0"); con.ok("Depuracao sem fio desligada") }
        if (Sh.setting("global", "adb_enabled") == "1" && con.ask("Desligar a depuracao USB? (religue quando for usar o PC)")) { u.setting("global", "adb_enabled", "0"); con.ok("Depuracao USB desligada") }
        val pm = ctx.packageManager
        val inst = Sh.root("cmd appops query-op REQUEST_INSTALL_PACKAGES allow 2>/dev/null", 20).out.lines().map { it.trim() }.filter { it.contains('.') && !lojas.contains(it) }
        if (inst.isNotEmpty()) {
            con.linha("")
            con.linha("Apps que podem INSTALAR outros apps (navegador/Telegram/WhatsApp baixando APK de mod e a porta de entrada nº 1):", C.amarelo)
            val sel = con.varios("Tirar a permissao de 'instalar apps desconhecidos' de quais?", inst.map { Jogos.nome(ctx, it) }, true)
            for (i in sel) { u.comando("inst:${inst[i]}", "cmd appops set ${inst[i]} REQUEST_INSTALL_PACKAGES deny", "cmd appops set ${inst[i]} REQUEST_INSTALL_PACKAGES allow"); con.ok("Bloqueado: " + Jogos.nome(ctx, inst[i])) }
        }
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure && con.ask("O celular esta SEM bloqueio de tela. Abrir a tela para configurar PIN/digital?")) con.abrirConfig(Settings.ACTION_SECURITY_SETTINGS)
        if (Sh.root("getenforce", 10).out.trim().equals("Permissive", true)) con.aviso("SELinux PERMISSIVO: a ROM/kernel deixa a protecao desligada. Use Seguranca > Varredura para corrigir (temporario) ou troque de kernel.")
        con.linha("")
        con.linha("Agora vou procurar APKs de mod/cheat/crack baixados...", C.ciano)
        achados = ArrayList(); chaves.clear()
        scanArquivos(con)
        val ap = achados.filter { it.cat == "Arquivo" && it.sev >= 2 }
        if (ap.isEmpty()) con.ok("Nenhum APK suspeito em Download/Telegram.")
        for (a in ap) { con.linha("[MEDIO] " + a.titulo, C.amarelo); con.info("  " + a.detalhe.lines().first()) }
        if (ap.isNotEmpty()) con.info("Verifique cada um na opcao VirusTotal antes de instalar.")
        Log2.registrar("Proteger agora aplicado")
    }

    fun caixaDeAreia(con: Console) {
        con.titulo("CAIXA DE AREIA: testar um app suspeito ISOLADO")
        con.linha("No PC era o Windows Sandbox. No Android o equivalente e instalar o app num PERFIL SEPARADO:")
        con.linha("  - Android 15+: 'Espaco privado' (Configuracoes > Seguranca e privacidade > Espaco privado)")
        con.linha("  - Qualquer Android: perfil de trabalho com o app Shelter ou Island (F-Droid / Play Store)")
        con.linha("  - Ou um usuario Convidado (Configuracoes > Sistema > Varios usuarios)")
        con.linha("Assim o app nao ve seus contatos, fotos, contas e apps de banco do perfil principal.", C.cinza)
        con.linha("IMPORTANTE: NAO de acessibilidade, administrador ou leitura de notificacoes a um app que voce esta testando.", C.amarelo)
        if (con.ask("Abrir as configuracoes de usuarios agora?")) con.abrirConfig("android.settings.USER_SETTINGS")
    }

    // ---------------- VirusTotal ----------------
    private fun sha256(ins: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        ins.use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n <= 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { String.format("%02x", it) }
    }

    private fun vtChave(con: Console): String? {
        var k = Store.str("vt_api_key")
        if (k.isEmpty()) {
            con.info("Pegue sua chave GRATUITA em virustotal.com -> crie uma conta -> icone do perfil -> API Key.")
            k = (con.texto("Cole a API key da VirusTotal", "chave de 64 caracteres") ?: "").trim()
            if (k.isEmpty()) return null
            Store.putStr("vt_api_key", k)
            con.ok("Chave salva.")
        }
        return k
    }

    fun configurarVt(con: Console) {
        Store.putStr("vt_api_key", "")
        if (vtChave(con) == null) con.linha("Cancelado.")
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
        c.requestMethod = "POST"
        c.doOutput = true
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
        con.info("Calculando o SHA256...")
        val hash = sha256(abrir())
        con.info("SHA256: $hash")
        con.info("Consultando a VirusTotal...")
        var (code, body) = httpGet("https://www.virustotal.com/api/v3/files/$hash", chave)
        if (code == 401) { con.erro("Chave invalida. Configure de novo."); Store.putStr("vt_api_key", ""); return -1 }
        if (code == 429) { con.aviso("Limite da conta gratuita (4 por minuto). Espere 1 minuto."); return -1 }
        if (code == 404) {
            con.aviso("Arquivo ainda desconhecido pela VirusTotal.")
            if (tamanho > 32L * 1024 * 1024) { con.info("Tem mais de 32 MB: envie pelo site virustotal.com."); return -1 }
            if (!con.ask("Enviar o arquivo para analise agora? (1 a 3 minutos)")) return -1
            val id = httpUpload(chave, nome, abrir) ?: run { con.erro("Falha no envio."); return -1 }
            con.info("Enviado. Aguardando o resultado...")
            val fim = System.currentTimeMillis() + 5 * 60_000
            while (System.currentTimeMillis() < fim) {
                Thread.sleep(10_000)
                val (c2, b2) = httpGet("https://www.virustotal.com/api/v3/analyses/$id", chave)
                if (c2 == 200 && JSONObject(b2).optJSONObject("data")?.optJSONObject("attributes")?.optString("status") == "completed") break
            }
            val r = httpGet("https://www.virustotal.com/api/v3/files/$hash", chave)
            code = r.first; body = r.second
        }
        if (code != 200) { con.erro("Nao consegui ler o resultado (HTTP $code). Veja: https://www.virustotal.com/gui/file/$hash"); return -1 }
        val at = JSONObject(body).getJSONObject("data").getJSONObject("attributes")
        val st = at.getJSONObject("last_analysis_stats")
        val mal = st.optInt("malicious"); val sus = st.optInt("suspicious")
        val tot = mal + sus + st.optInt("undetected") + st.optInt("harmless")
        con.linha("")
        con.linha("Arquivo: $nome", C.ciano)
        con.linha("Malicioso: $mal/$tot   Suspeito: $sus/$tot")
        val v = when {
            mal == 0 && sus == 0 -> { con.linha("RESULTADO: nenhum antivirus detectou nada. Sinal forte de que esta limpo.", C.verde); 0 }
            mal + sus <= 2 -> { con.linha("RESULTADO: poucas deteccoes. Pode ser falso positivo (comum em mods). Prefira a Caixa de Areia.", C.amarelo); 1 }
            else -> {
                con.linha("RESULTADO: VARIOS antivirus detectaram. Risco real - NAO instale/abra.", C.vermelho)
                val res = at.optJSONObject("last_analysis_results")
                if (res != null) {
                    var n = 0
                    for (k in res.keys()) {
                        val o = res.getJSONObject(k)
                        if ((o.optString("category") == "malicious" || o.optString("category") == "suspicious") && n < 8) { con.info("  - $k: " + o.optString("result")); n++ }
                    }
                }
                2
            }
        }
        con.info("Relatorio completo: https://www.virustotal.com/gui/file/$hash")
        return v
    }

    fun verificarArquivoVt(con: Console, ctx: Context) {
        con.titulo("VERIFICAR ARQUIVO NA VIRUSTOTAL (~70 antivirus)")
        val opc = con.escolher("O que verificar?", listOf("Um arquivo (APK baixado, etc.)", "Um app JA INSTALADO"))
        if (opc == 0) {
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
            val v = verificarVt(con, nome, tam) { ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("sem acesso") }
            if (v == 2) con.info("Apague o arquivo (ou use Limpeza > Revisar Downloads).")
        } else if (opc == 1) {
            val apps = Jogos.todosApps(ctx)
            val i = con.escolher("Qual app?", apps.map { it.nome })
            if (i < 0) return
            val ai = ctx.packageManager.getApplicationInfo(apps[i].pkg, 0)
            val f = File(ai.sourceDir)
            val v = verificarVt(con, apps[i].nome + ".apk", f.length()) { f.inputStream() }
            if (v == 2 && con.ask("Colocar ${apps[i].nome} em QUARENTENA (desativar) agora?")) quarentena(con, ctx, apps[i].pkg, "Confirmado por varios antivirus na VirusTotal")
        }
    }
}
