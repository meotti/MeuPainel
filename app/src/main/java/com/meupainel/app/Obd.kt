package com.meupainel.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Lê a temperatura do líquido de arrefecimento (PID 01 05) de um adaptador
 * ELM327 Bluetooth. Roda numa thread própria enquanto o Android Auto está aberto.
 * O Car Scanner precisa estar fechado: o adaptador só atende um app por vez.
 */
object Obd {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @Volatile var temperatura: Int? = null
        private set
    @Volatile var status: String = "OBD2 não configurado"
        private set
    @Volatile var conectado = false
        private set

    @Volatile private var rodando = false
    private var thread: Thread? = null
    @Volatile private var socket: BluetoothSocket? = null
    private var alertou = false

    fun iniciar(ctx: Context, aoAlertar: (temp: Int, limite: Int) -> Unit) {
        if (rodando) return
        rodando = true
        val app = ctx.applicationContext
        thread = Thread({ laco(app, aoAlertar) }, "obd2").apply {
            isDaemon = true
            start()
        }
    }

    fun parar() {
        rodando = false
        fechar()
        thread?.interrupt()
        thread = null
        conectado = false
    }

    private fun temPermissao(ctx: Context) =
        Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun laco(ctx: Context, aoAlertar: (Int, Int) -> Unit) {
        while (rodando) {
            try {
                val endereco = Store.obdEndereco(ctx)
                if (endereco == null) {
                    status = "Escolha o adaptador no app"
                    Thread.sleep(5000); continue
                }
                if (!temPermissao(ctx)) {
                    status = "Libere o Bluetooth no app"
                    Thread.sleep(5000); continue
                }
                val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
                    ?: throw IOException("Sem Bluetooth")
                status = "Conectando ao OBD2…"
                val s = conectar(adapter.getRemoteDevice(endereco))
                socket = s
                val ins = s.inputStream
                val out = s.outputStream

                enviar(ins, out, "ATZ", 5000)
                for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) enviar(ins, out, cmd, 3000)

                conectado = true
                status = "OBD2 conectado"
                while (rodando) {
                    val t = interpretar(enviar(ins, out, "0105", 10000))
                    if (t != null) {
                        temperatura = t
                        verificar(ctx, t, aoAlertar)
                    }
                    Thread.sleep(2000)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                conectado = false
                temperatura = null
                status = "OBD2 desconectado"
                fechar()
                try { Thread.sleep(5000) } catch (i: InterruptedException) { break }
            }
        }
        conectado = false
    }

    @SuppressLint("MissingPermission")
    private fun conectar(dev: BluetoothDevice): BluetoothSocket =
        try {
            dev.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
        } catch (e: IOException) {
            dev.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() }
        }

    private fun fechar() {
        try { socket?.close() } catch (_: IOException) { }
        socket = null
    }

    /** Envia um comando e lê até o prompt '>' do ELM327. */
    private fun enviar(ins: InputStream, out: OutputStream, cmd: String, prazoMs: Long): String {
        out.write((cmd + "\r").toByteArray())
        out.flush()
        val sb = StringBuilder()
        val fim = System.currentTimeMillis() + prazoMs
        while (System.currentTimeMillis() < fim) {
            if (ins.available() > 0) {
                val c = ins.read()
                if (c < 0) throw IOException("Conexão encerrada")
                if (c == '>'.code) return sb.toString()
                sb.append(c.toChar())
            } else {
                Thread.sleep(20)
            }
        }
        throw IOException("Sem resposta do OBD2")
    }

    /** "41 05 7B" → 0x7B − 40 = 83 °C */
    fun interpretar(resposta: String): Int? {
        val hex = resposta.uppercase().replace(Regex("[^0-9A-F]"), "")
        val i = hex.indexOf("4105")
        if (i < 0 || hex.length < i + 6) return null
        return hex.substring(i + 4, i + 6).toInt(16) - 40
    }

    private fun verificar(ctx: Context, t: Int, aoAlertar: (Int, Int) -> Unit) {
        val limite = Store.limite(ctx)
        if (t >= limite && !alertou) {
            alertou = true
            aoAlertar(t, limite)
        } else if (t < limite - 3) {
            alertou = false
        }
    }
}
