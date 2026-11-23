package com.pctoolkit.mobile

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.net.HttpURLConnection
import java.net.URL

/** Aba SHIZUKU: video, foto do que ativar e passo a passo. */
object TutorialShizuku {
    const val VIDEO_ID = "PL8Nkw6W3wk"
    const val VIDEO = "https://youtube.com/shorts/$VIDEO_ID"

    fun abrirVideo(act: MainActivity) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(VIDEO))
        try { act.startActivity(i) } catch (e: Exception) { act.startActivity(Intent.createChooser(i, "Abrir vídeo")) }
    }

    private fun arredondar(v: View, raioDp: Int) {
        val r = Ui.dpf(v.context, raioDp.toFloat())
        v.outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, r) } }
        v.clipToOutline = true
    }

    /** Cartao na tela inicial. Sempre visivel; destaca quando ainda falta ligar. */
    fun cartao(act: MainActivity): View {
        val est = Priv.estado(act)
        val ok = est == "Ativo"
        val c = Ui.horizontal(act)
        c.setPadding(Ui.dp(act, 16), Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14))
        val cor = if (ok) T.verde else T.amarelo
        c.background = Ui.clicavel(act, Ui.forma(act, Ui.alpha(cor, 0x22), 18, cor), 18)
        val tx = Ui.vertical(act)
        tx.addView(Ui.texto(act, "SHIZUKU", 15.5f, T.texto, Ui.negrito))
        tx.addView(Ui.texto(act, if (ok) "Ligado: o app faz tudo sozinho. Toque para ver o tutorial."
            else "Ligue em 2 minutos: vídeo, foto do que ativar e passo a passo.", 12.5f, T.texto2))
        c.addView(tx, Ui.peso())
        c.addView(Ui.espaco(act, 10, 1))
        c.addView(Ui.chip(act, if (ok) "Ativo ✓" else est, cor))
        c.isClickable = true
        c.setOnClickListener { act.abrirTela { tela(act) } }
        return c
    }

    fun tela(act: MainActivity) {
        val col = act.coluna()
        val est = Priv.estado(act)
        col.addView(act.barraTopo("SHIZUKU", "Situação: $est"))

        // situacao + acao principal
        val sz = Ui.vertical(act)
        sz.setPadding(Ui.dp(act, 16), Ui.dp(act, 16), Ui.dp(act, 16), Ui.dp(act, 16))
        sz.background = Ui.gradiente(act, 0xFF0F2A22.toInt(), 0xFF101C30.toInt(), 20)
        val d = Ui.texto(act, when (est) {
            "Ativo" -> "Tudo certo. O Shizuku está ligado e o SerpyraToolkit libera as permissões e aplica os ajustes sozinho."
            "Falta permitir" -> "O Shizuku está ligado. Falta só permitir o SerpyraToolkit nele."
            "Instalado, desligado" -> "O Shizuku está instalado, mas desligado. Siga os passos abaixo para ligar (depois de reiniciar o celular, é preciso tocar em Iniciar de novo)."
            else -> "O Shizuku é um app gratuito que dá ao SerpyraToolkit o poder de ajustar o celular sozinho, sem root e sem computador. Os botões no jogo, o FPS real e vários ajustes precisam dele."
        }, 13.5f, T.texto)
        d.setPadding(0, 0, 0, Ui.dp(act, 12))
        sz.addView(d)
        when (est) {
            "Não instalado" -> sz.addView(Ui.botao(act, "Instalar o Shizuku (Play Store)", T.verde, true) {
                try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Priv.PKG}"))) }
                catch (e: Exception) { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${Priv.PKG}"))) }
            })
            "Instalado, desligado" -> sz.addView(Ui.botao(act, "Abrir o Shizuku", T.verde, true) {
                act.packageManager.getLaunchIntentForPackage(Priv.PKG)?.let { act.startActivity(it) }
            })
            else -> sz.addView(Ui.botao(act, if (est == "Ativo") "Liberar tudo de novo" else "Permitir o SerpyraToolkit", T.verde, true) {
                act.executar("Modo automático") { Priv.ativar(it) }
            })
        }
        col.addView(sz, Ui.linhaLp(act, 0, 6))

        // video
        col.addView(Ui.secao(act, "Vídeo tutorial"))
        val fr = FrameLayout(act)
        fr.background = Ui.forma(act, 0xFF101722.toInt(), 18, T.outline)
        arredondar(fr, 18)
        val capa = ImageView(act)
        capa.scaleType = ImageView.ScaleType.CENTER_CROP
        fr.addView(capa, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val play = Ui.texto(act, "▶", 30f, 0xFF0A0E14.toInt(), Ui.negrito)
        play.gravity = Gravity.CENTER
        play.setPadding(Ui.dp(act, 6), 0, 0, 0)
        play.background = Ui.forma(act, T.verde, 40)
        fr.addView(play, FrameLayout.LayoutParams(Ui.dp(act, 72), Ui.dp(act, 72), Gravity.CENTER))
        val leg = Ui.texto(act, "Como ligar o Shizuku · toque para assistir", 13f, T.texto, Ui.medio)
        leg.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8))
        leg.background = Ui.forma(act, 0xCC0A0E14.toInt(), 0)
        fr.addView(leg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        fr.isClickable = true
        fr.foreground = Ui.clicavel(act, Ui.forma(act, 0, 18), 18)
        fr.setOnClickListener { abrirVideo(act) }
        col.addView(fr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 200)).apply { bottomMargin = Ui.dp(act, 6) })
        Thread {
            try {
                val c = URL("https://i.ytimg.com/vi/$VIDEO_ID/hqdefault.jpg").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 10000
                val bm = c.inputStream.use { BitmapFactory.decodeStream(it) }; c.disconnect()
                if (bm != null) act.runOnUiThread { capa.setImageBitmap(bm) }
            } catch (e: Exception) { }
        }.start()

        // foto
        col.addView(Ui.secao(act, "O que ativar"))
        val fd = Ui.texto(act, "Em Opções do desenvolvedor, ligue tudo que está marcado em verde. Atenção: o 3 (Instalar via USB) aparece desligado nesta foto, mas precisa ficar LIGADO.", 12.5f, T.texto2)
        fd.setPadding(0, 0, 0, Ui.dp(act, 10))
        col.addView(fd)
        val foto = ImageView(act)
        foto.setImageResource(R.drawable.shizuku_exemplo)
        foto.adjustViewBounds = true
        foto.scaleType = ImageView.ScaleType.FIT_CENTER
        arredondar(foto, 16)
        col.addView(foto, Ui.linhaLp(act, 0, 6))
        col.addView(Ui.texto(act, "1 Depuração USB · 2 Depuração por Wi-Fi · 3 Instalar via USB · 4 Depuração USB (Config. de segurança) · 5 Desativar o tempo limite da autorização adb. Os itens 3 e 4 só existem na Xiaomi/Redmi/Poco.", 12f, T.texto3))

        // passo a passo
        col.addView(Ui.secao(act, "Passo a passo"))
        val passos = listOf(
            "Liberar as Opções do desenvolvedor" to "Configurações > Sobre o telefone > toque 7 vezes em “Número da versão” (na Xiaomi: “Versão do SO”/“Versão MIUI”).",
            "Ligar os itens 1, 3, 4 e 5 da foto" to "Em Opções do desenvolvedor (na Xiaomi fica em Configurações adicionais). Os itens 3 e 4 só existem na Xiaomi e podem pedir a conta Xiaomi e o chip no celular. O item 3 é o que deixa o app se atualizar sozinho.",
            "Ligar o item 2: Depuração por Wi-Fi" to "Conectado a um Wi-Fi (não precisa ter internet). Toque no nome para entrar e ligue a chave lá dentro.",
            "Parear o Shizuku" to "Abra o Shizuku e toque em “Parear”. Em Depuração por Wi-Fi, toque em “Parear dispositivo com código de pareamento” e digite o código na notificação do Shizuku.",
            "Iniciar" to "Volte ao Shizuku e toque em “Iniciar”. Aparece “Shizuku está em execução”.",
            "Permitir o SerpyraToolkit" to "Volte aqui e toque no botão verde lá em cima. Quando o Shizuku perguntar, toque em “Permitir sempre”."
        )
        passos.forEachIndexed { n, (t, desc) ->
            val l = Ui.horizontal(act)
            l.gravity = Gravity.TOP
            l.setPadding(Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14))
            l.background = Ui.forma(act, T.surface, 16, T.outline)
            val num = Ui.texto(act, "${n + 1}", 14f, 0xFF0A0E14.toInt(), Ui.negrito)
            num.gravity = Gravity.CENTER
            num.background = Ui.forma(act, T.verde, 14)
            l.addView(num, LinearLayout.LayoutParams(Ui.dp(act, 28), Ui.dp(act, 28)))
            l.addView(Ui.espaco(act, 12, 1))
            val tc = Ui.vertical(act)
            tc.addView(Ui.texto(act, t, 14.5f, T.texto, Ui.medio))
            val dd = Ui.texto(act, desc, 12.5f, T.texto2); dd.setPadding(0, Ui.dp(act, 3), 0, 0)
            tc.addView(dd)
            l.addView(tc, Ui.peso())
            col.addView(l, Ui.linhaLp(act, 0, 8))
        }
        val aviso = Ui.texto(act, "Depois de reiniciar o celular, o Shizuku desliga. É só abrir o Shizuku e tocar em “Iniciar” de novo (com o Wi-Fi ligado). Não precisa parear outra vez.", 12.5f, T.amarelo)
        aviso.setPadding(Ui.dp(act, 14), Ui.dp(act, 12), Ui.dp(act, 14), Ui.dp(act, 12))
        aviso.background = Ui.forma(act, Ui.alpha(T.amarelo, 0x1A), 14, Ui.alpha(T.amarelo, 0x66))
        col.addView(aviso, Ui.linhaLp(act, 4, 12))

        // atalhos
        col.addView(Ui.secao(act, "Atalhos"))
        col.addView(Ui.linhaMenu(act, R.drawable.ic_chave, T.azul, "Abrir Opções do desenvolvedor", "Para ligar os itens da foto", "Abrir", T.azul) {
            try { act.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            catch (e: Exception) { act.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
        })
        col.addView(Ui.linhaMenu(act, R.drawable.ic_chave, T.azul, "Abrir Sobre o telefone", "Para liberar as Opções do desenvolvedor", "Abrir", T.azul) {
            act.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        })
        col.addView(Ui.linhaMenu(act, R.drawable.ic_jogo, T.verde, "Assistir ao vídeo", "Tutorial curto no YouTube", "Ver", T.verde) { abrirVideo(act) })
        act.setContentView(act.raiz(col))
    }
}
