package com.pctoolkit.mobile

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Roda FORA do app, com o poder do ADB (iniciado pelo modo completo com app_process).
 * Recebe toques e teclas do app por uma conexao local e entrega ao Android, como o Shizuku faria.
 */
object ServidorEntrada {
    private var injetar: Method? = null
    private var im: Any? = null

    private fun prepararInjecao() {
        val alvo = try { Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null) } catch (e: Throwable) { null }
            ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
        im = alvo
        injetar = alvo!!.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val porta = args.getOrNull(0)?.toIntOrNull() ?: return
        val token = args.getOrNull(1) ?: return
        try { android.os.Looper.prepareMainLooper() } catch (e: Throwable) { }
        prepararInjecao()
        val ss = ServerSocket(porta, 2, InetAddress.getByName("127.0.0.1"))
        println("PRONTO")
        System.out.flush()
        while (true) {
            val s = try { ss.accept() } catch (e: Throwable) { break }
            Thread { atender(s, token) }.start()
        }
    }

    private fun atender(s: Socket, token: String) {
        try {
            s.tcpNoDelay = true
            val ent = DataInputStream(BufferedInputStream(s.getInputStream()))
            val sai = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            if (ent.readUTF() != token) { s.close(); return }
            sai.writeByte(1); sai.flush()
            while (true) {
                val tipo = ent.readByte().toInt()
                val ev: InputEvent = when (tipo) {
                    1 -> {
                        val desde = ent.readLong(); val acao = ent.readInt(); val cod = ent.readInt(); val rep = ent.readInt(); val meta = ent.readInt()
                        KeyEvent(desde, SystemClock.uptimeMillis(), acao, cod, rep, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD)
                    }
                    2 -> {
                        val desde = ent.readLong(); val acao = ent.readInt(); val n = ent.readInt()
                        val props = Array(n) { MotionEvent.PointerProperties() }
                        val coords = Array(n) { MotionEvent.PointerCoords() }
                        for (i in 0 until n) {
                            props[i].id = ent.readInt(); props[i].toolType = MotionEvent.TOOL_TYPE_FINGER
                            coords[i].x = ent.readFloat(); coords[i].y = ent.readFloat(); coords[i].pressure = 1f; coords[i].size = 0.05f
                            coords[i].touchMajor = 12f; coords[i].touchMinor = 12f
                        }
                        MotionEvent.obtain(desde, SystemClock.uptimeMillis(), acao, n, props, coords, 0, 0, 1f, 1f, 0x7E0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    }
                    else -> return
                }
                try { injetar?.invoke(im, ev, 0) } catch (e: Throwable) { }
                if (ev is MotionEvent) ev.recycle()
            }
        } catch (e: Throwable) { } finally { try { s.close() } catch (e: Throwable) { } }
    }
}

/** Lado do app: liga o servidor pelo modo completo e manda os eventos para ele. */
object EntradaInterna {
    @Volatile private var proc: Process? = null
    @Volatile private var sock: Socket? = null
    @Volatile private var sai: DataOutputStream? = null

    @Synchronized
    fun pronto(): Boolean {
        if (sai != null && sock?.isConnected == true && sock?.isClosed == false) return true
        fechar()
        if (!Interno.pronto() && !Interno.conectar(PcApp.ctx)) return false
        val porta = 20000 + (Math.random() * 20000).toInt()
        val token = java.util.UUID.randomUUID().toString()
        val apk = PcApp.ctx.applicationInfo.sourceDir
        val p = Interno.processo("CLASSPATH=$apk app_process /system/bin com.pctoolkit.mobile.ServidorEntrada $porta $token") ?: return false
        proc = p
        // espera o "PRONTO"
        val ok = try {
            val r = p.inputStream.bufferedReader()
            var linha: String? = null
            val fim = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < fim) { linha = r.readLine() ?: break; if (linha.contains("PRONTO")) break }
            linha?.contains("PRONTO") == true
        } catch (e: Exception) { false }
        if (!ok) { Log2.registrar("servidor de entrada nao iniciou"); fechar(); return false }
        return try {
            val s = Socket("127.0.0.1", porta); s.tcpNoDelay = true
            val o = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            o.writeUTF(token); o.flush()
            if (s.getInputStream().read() != 1) { s.close(); fechar(); false } else { sock = s; sai = o; true }
        } catch (e: Exception) { Log2.registrar("entrada: $e"); fechar(); false }
    }

    fun fechar() {
        try { sock?.close() } catch (e: Exception) { }
        try { proc?.destroy() } catch (e: Exception) { }
        sock = null; sai = null; proc = null
    }

    /** Manda o evento. Devolve false se a conexao caiu (o chamador tenta de novo). */
    fun enviar(e: InputEvent): Boolean {
        val o = sai ?: return false
        return try {
            when (e) {
                is KeyEvent -> { o.writeByte(1); o.writeLong(e.downTime); o.writeInt(e.action); o.writeInt(e.keyCode); o.writeInt(e.repeatCount); o.writeInt(e.metaState) }
                is MotionEvent -> {
                    o.writeByte(2); o.writeLong(e.downTime); o.writeInt(e.action); o.writeInt(e.pointerCount)
                    for (i in 0 until e.pointerCount) { o.writeInt(e.getPointerId(i)); o.writeFloat(e.getX(i)); o.writeFloat(e.getY(i)) }
                }
                else -> return false
            }
            o.flush(); true
        } catch (x: Exception) { synchronized(this) { fechar() }; false }
    }
}
