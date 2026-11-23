package com.pctoolkit.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Mira na tela: desenhada por cima do jogo, sem tocar em nada (os toques passam direto para o jogo). */
object MiraTela {
    val ESTILOS = listOf("cruz" to "Cruz", "ponto" to "Ponto", "cruzponto" to "Cruz com ponto", "circulo" to "Círculo com ponto", "t" to "T (sem a parte de cima)", "x" to "X", "imagem" to "Imagem sua (da galeria)")
    val CORES = listOf(0xFF3DDC97.toInt() to "Verde", 0xFFFF3B4E.toInt() to "Vermelho", 0xFF38D6E8.toInt() to "Ciano", 0xFFF5E642.toInt() to "Amarelo", 0xFFFFFFFF.toInt() to "Branco", 0xFFFF4FD8.toInt() to "Rosa")
    val TAMANHOS = listOf(14 to "Pequena", 22 to "Média", 32 to "Grande")

    fun estilo() = Store.str("mira_estilo").ifEmpty { "cruzponto" }
    fun cor() = Store.int("mira_cor", 0xFF3DDC97.toInt())
    fun tamanho() = Store.int("mira_tam", 22)
    fun dx() = Store.int("mira_dx", 0)
    fun dy() = Store.int("mira_dy", 0)

    fun executar(con: Console) {
        val ctx = con.ctx
        con.titulo("Mira na tela")
        con.linha("Coloca uma mira fixa no centro da tela, por cima de qualquer jogo. Ela não bloqueia os toques.", T.texto2)
        if (!Pro.exigir(con, "Mira na tela")) return
        if (MiraService.rodando) {
            val i = con.escolher("A mira está ligada", listOf("Mudar estilo, cor e tamanho", "Ajustar a posição", "Desligar a mira"))
            when (i) {
                0 -> { configurar(con); ctx.startService(Intent(ctx, MiraService::class.java).setAction("atualizar")); con.ok("Mira atualizada") }
                1 -> { posicao(con); ctx.startService(Intent(ctx, MiraService::class.java).setAction("atualizar")); con.ok("Posição salva") }
                2 -> { ctx.startService(Intent(ctx, MiraService::class.java).setAction("parar")); con.ok("Mira desligada") }
            }
            return
        }
        if (!con.garantirNome("Sobrepor a outros apps")) { con.erro("Sem a permissão de sobreposição a mira não aparece por cima do jogo."); return }
        if (Store.str("mira_estilo").isEmpty() || con.ask("Escolher estilo, cor e tamanho agora?", "Escolher", "Usar a última", importante = true)) configurar(con)
        ctx.startForegroundService(Intent(ctx, MiraService::class.java))
        con.ok("Mira ligada: ${ESTILOS.firstOrNull { it.first == estilo() }?.second ?: ""}, ${CORES.firstOrNull { it.first == cor() }?.second ?: ""}")
        con.info("Para desligar: toque em Desligar na notificação “Mira na tela” ou abra esta opção de novo.")
        con.info("Se a mira não ficar bem no centro do jogo, use “Ajustar a posição”.")
    }

    private fun configurar(con: Console) {
        val e = con.escolher("Estilo da mira", ESTILOS.map { it.second })
        if (e >= 0 && ESTILOS[e].first == "imagem") {
            con.info("Escolha uma imagem de mira, de preferência PNG com fundo transparente.")
            val uri = con.escolherArquivo()
            val bmp = try { uri?.let { u -> con.ctx.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it) } } } catch (ex: Exception) { null }
            if (bmp == null) { con.erro("Não consegui abrir essa imagem. Mantive a mira anterior."); return }
            val lado = 256
            val esc = android.graphics.Bitmap.createScaledBitmap(bmp, lado, (lado * bmp.height / bmp.width.coerceAtLeast(1)).coerceIn(16, 512), true)
            java.io.File(con.ctx.filesDir, "mira.png").outputStream().use { esc.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            Store.putStr("mira_estilo", "imagem")
            val t = con.escolher("Tamanho", TAMANHOS.map { it.second }); if (t >= 0) Store.putInt("mira_tam", TAMANHOS[t].first)
            return
        }
        if (e >= 0) Store.putStr("mira_estilo", ESTILOS[e].first)
        val c = con.escolher("Cor", CORES.map { it.second }); if (c >= 0) Store.putInt("mira_cor", CORES[c].first)
        val t = con.escolher("Tamanho", TAMANHOS.map { it.second }); if (t >= 0) Store.putInt("mira_tam", TAMANHOS[t].first)
    }

    private fun posicao(con: Console) {
        Store.putInt("mira_dx", con.numero("Mover para os lados (negativo = esquerda), em pontos", dx(), -300, 300))
        Store.putInt("mira_dy", con.numero("Mover para cima ou para baixo (negativo = cima), em pontos", dy(), -300, 300))
    }
}

class MiraService : Service() {
    companion object { @Volatile var rodando = false; private const val ID = 5050; private const val CANAL = "mira" }
    private var vista: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "parar") { tirar(); rodando = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Mira na tela", NotificationManager.IMPORTANCE_LOW))
        val parar = PendingIntent.getService(this, 1, Intent(this, MiraService::class.java).setAction("parar"), PendingIntent.FLAG_IMMUTABLE)
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("acao", "mira"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CANAL).setSmallIcon(R.drawable.ic_jogo).setContentTitle("Mira na tela").setContentText("Toque para mudar a mira")
            .setContentIntent(abrir).setOngoing(true).setColor(T.verde)
            .addAction(Notification.Action.Builder(null, "Desligar", parar).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        } catch (e: Exception) { Log2.registrar("mira fgs: $e"); stopSelf(); return START_NOT_STICKY }
        if (!Pro.ativo() || !android.provider.Settings.canDrawOverlays(this)) { tirar(); rodando = false; stopSelf(); return START_NOT_STICKY }
        tirar(); por(); rodando = true
        return START_STICKY
    }

    private fun por() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = MiraView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 31) lp.alpha = 0.8f   // Android 12+ só deixa o toque passar com a janela um pouco transparente
        try { wm.addView(v, lp); vista = v } catch (e: Exception) { Log2.registrar("mira: $e") }
    }

    private fun tirar() {
        val v = vista ?: return
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) { }
        vista = null
    }

    override fun onDestroy() { tirar(); rodando = false; super.onDestroy() }

    private class MiraView(ctx: Context) : View(ctx) {
        private val dens = ctx.resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val sombra = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; color = 0x99000000.toInt() }

        private var img: android.graphics.Bitmap? = null
        private var imgEm = 0L

        override fun onDraw(c: Canvas) {
            val cx = width / 2f + MiraTela.dx() * dens
            val cy = height / 2f + MiraTela.dy() * dens
            if (MiraTela.estilo() == "imagem") {
                val f = java.io.File(context.filesDir, "mira.png")
                if (img == null || f.lastModified() != imgEm) { img = try { android.graphics.BitmapFactory.decodeFile(f.path) } catch (e: Exception) { null }; imgEm = f.lastModified() }
                val b = img ?: return
                val meio = MiraTela.tamanho() * dens * 1.5f
                val alt = meio * b.height / b.width.coerceAtLeast(1)
                c.drawBitmap(b, null, android.graphics.RectF(cx - meio, cy - alt, cx + meio, cy + alt), null)
                return
            }
            val r = MiraTela.tamanho() * dens / 2f
            val gap = r * 0.35f
            val esp = (1.6f + MiraTela.tamanho() / 14f) * dens
            p.color = MiraTela.cor()
            for ((tinta, extra) in listOf(sombra to esp + 1.6f * dens, p to esp)) {
                tinta.strokeWidth = extra
                tinta.style = Paint.Style.STROKE
                when (MiraTela.estilo()) {
                    "ponto" -> { tinta.style = Paint.Style.FILL; c.drawCircle(cx, cy, esp * 1.3f + (extra - esp) / 2, tinta) }
                    "cruz", "cruzponto" -> {
                        c.drawLine(cx - r, cy, cx - gap, cy, tinta); c.drawLine(cx + gap, cy, cx + r, cy, tinta)
                        c.drawLine(cx, cy - r, cx, cy - gap, tinta); c.drawLine(cx, cy + gap, cx, cy + r, tinta)
                        if (MiraTela.estilo() == "cruzponto") { tinta.style = Paint.Style.FILL; c.drawCircle(cx, cy, esp * 0.9f + (extra - esp) / 2, tinta) }
                    }
                    "circulo" -> { c.drawCircle(cx, cy, r * 0.8f, tinta); tinta.style = Paint.Style.FILL; c.drawCircle(cx, cy, esp * 0.9f + (extra - esp) / 2, tinta) }
                    "t" -> { c.drawLine(cx - r, cy, cx - gap, cy, tinta); c.drawLine(cx + gap, cy, cx + r, cy, tinta); c.drawLine(cx, cy + gap, cx, cy + r, tinta) }
                    "x" -> { val d = r * 0.72f; val g = gap * 0.72f
                        c.drawLine(cx - d, cy - d, cx - g, cy - g, tinta); c.drawLine(cx + g, cy + g, cx + d, cy + d, tinta)
                        c.drawLine(cx + d, cy - d, cx + g, cy - g, tinta); c.drawLine(cx - g, cy + g, cx - d, cy + d, tinta) }
                }
            }
        }
    }
}
