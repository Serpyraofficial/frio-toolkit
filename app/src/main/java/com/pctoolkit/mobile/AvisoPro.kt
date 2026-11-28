package com.pctoolkit.mobile

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Lembrete 2 dias antes do Pro vencer (e no dia), para a pessoa renovar sem perder as funções. */
object AvisoPro {
    private const val DIA = 86_400_000L

    fun agendar(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 77, Intent(ctx, AvisoProReceiver::class.java).setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        val d = Licenca.diaValidade()
        if (d <= 0 || !Licenca.ativo() || d > 20000) return   // chave sem prazo (administrador) não precisa de aviso
        val fim = (d + 1L) * DIA
        val agora = System.currentTimeMillis()
        val quando = listOf(fim - 3 * DIA + 12 * 3_600_000L, fim - 12 * 3_600_000L, fim + DIA + 12 * 3_600_000L).firstOrNull { it > agora + 60_000L } ?: return
        try { am.set(AlarmManager.RTC, quando, pi) } catch (e: Exception) { }
    }
}

class AvisoProReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Licenca.fimTeste() > 0 || Store.str("lic_ate_tipo") == "cortesia") return   // teste e cortesia nao recebem lembrete de renovacao
        val venceu = !Licenca.ativo()
        if (venceu && (Licenca.diaValidade() <= 0 || System.currentTimeMillis() / DIA_MS - Licenca.diaValidade() > 6)) return
        val dias = Licenca.diasRestantes()
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pro", "Assinatura Pro", NotificationManager.IMPORTANCE_DEFAULT))
        val abrir = PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java).putExtra("pro", true).putExtra("cupom", "RENOVA10").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val txt = when {
            venceu -> "Seu Pro venceu. Volte com 10% de desconto: o cupom RENOVA10 já vai colocado, vale por poucos dias."
            dias <= 0 -> "Seu Pro vence hoje. Renove com 10% de desconto (cupom RENOVA10, já colocado) e continue com os botões, o Tuner e o Modo Frio."
            else -> "Seu Pro vence em $dias dia(s). Renove agora com 10% de desconto (cupom RENOVA10): os dias se somam."
        }
        try {
            nm.notify(7070, Notification.Builder(c, "pro").setSmallIcon(R.drawable.ic_chave).setContentTitle("SerpyraToolkit Pro").setContentText(txt)
                .setStyle(Notification.BigTextStyle().bigText(txt)).setContentIntent(abrir).setAutoCancel(true).setColor(T.amarelo).build())
        } catch (e: Exception) { }
        if (!venceu) AvisoPro.agendar(c)
    }
    private companion object { const val DIA_MS = 86_400_000L }
}
