package com.meupainel.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Lê a temperatura do líquido de arrefecimento (PID 01 05) de um adaptador
 * ELM327 Bluetooth. Roda numa thread própria.
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
    val ativo: Boolean get() = rodando
    private var thread: Thread? = null
    @Volatile private var socket: BluetoothSocket? = null
    private var alertou = false

    // Registro das últimas mensagens, para o botão "Testar OBD2"
    private val registro = ArrayDeque<String>()
    private val hora = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized private fun log(msg: String) {
        registro.addLast(hora.format(Date()) + "  " + msg)
        while (registro.size > 40) registro.removeFirst()
    }

    @Synchronized fun registroTexto(): String = registro.joinToString("\n")

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
                    log(status)
                    Thread.sleep(5000); continue
                }
                if (!temPermissao(ctx)) {
                    status = "Libere o Bluetooth no app"
                    log(status)
                    Thread.sleep(5000); continue
                }
                val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
                    ?: throw IOException("Celular sem Bluetooth")
                if (!adapter.isEnabled) throw IOException("Bluetooth do celular desligado")

                status = "Conectando ao OBD2…"
                log("Conectando em $endereco")
                val s = conectar(adapter, adapter.getRemoteDevice(endereco))
                socket = s
                log("Bluetooth conectado")
                val ins = s.inputStream
                val out = s.outputStream

                status = "Configurando OBD2…"
                enviar(ins, out, "ATZ", 8000)
                for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) enviar(ins, out, cmd, 4000)

                // Primeira consulta: o adaptador procura o protocolo do carro (pode levar ~10 s)
                status = "Procurando protocolo do carro…"
                val r0 = enviar(ins, out, "0100", 20000)
                if (r0.uppercase().contains("UNABLE") || r0.uppercase().contains("ERROR")) {
                    throw IOException("Carro não respondeu (ignição ligada?)")
                }

                conectado = true
                status = "OBD2 conectado"
                var falhas = 0
                while (rodando) {
                    val resp = enviar(ins, out, "0105", 10000)
                    val t = interpretar(resp)
                    if (t != null) {
                        falhas = 0
                        temperatura = t
                        status = "OBD2 conectado"
                        verificar(ctx, t, aoAlertar)
                    } else {
                        falhas++
                        status = "Sem resposta da temperatura (${resp.trim().take(20)})"
                        if (falhas >= 5) throw IOException("Temperatura não respondeu 5 vezes")
                    }
                    Thread.sleep(2000)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                conectado = false
                temperatura = null
                status = "OBD2: " + (e.message ?: "erro").take(60)
                log("ERRO: " + (e.message ?: e.javaClass.simpleName))
                fechar()
                try { Thread.sleep(5000) } catch (i: InterruptedException) { break }
            }
        }
        conectado = false
    }

    /** Tenta 3 jeitos de abrir a conexão (adaptadores baratos variam muito). */
    @SuppressLint("MissingPermission")
    private fun conectar(adapter: BluetoothAdapter, dev: BluetoothDevice): BluetoothSocket {
        try { adapter.cancelDiscovery() } catch (_: Exception) { }
        val tentativas = listOf<Pair<String, () -> BluetoothSocket>>(
            "seguro" to { dev.createRfcommSocketToServiceRecord(SPP) },
            "inseguro" to { dev.createInsecureRfcommSocketToServiceRecord(SPP) },
            "canal 1" to {
                dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(dev, 1) as BluetoothSocket
            }
        )
        var ultimo: Exception? = null
        for ((nome, criar) in tentativas) {
            var s: BluetoothSocket? = null
            try {
                s = criar()
                s.connect()
                log("Conexão $nome OK")
                return s
            } catch (e: Exception) {
                log("Conexão $nome falhou: ${e.message}")
                try { s?.close() } catch (_: Exception) { }
                ultimo = e
            }
        }
        throw IOException("Não conectou ao adaptador: ${ultimo?.message}")
    }

    private fun fechar() {
        try { socket?.close() } catch (_: IOException) { }
        socket = null
    }

    /** Envia um comando e lê até o prompt '>' do ELM327. */
    private fun enviar(ins: InputStream, out: OutputStream, cmd: String, prazoMs: Long): String {
        while (ins.available() > 0) ins.read() // descarta sobras
        out.write((cmd + "\r").toByteArray())
        out.flush()
        val sb = StringBuilder()
        val fim = System.currentTimeMillis() + prazoMs
        while (System.currentTimeMillis() < fim) {
            if (ins.available() > 0) {
                val c = ins.read()
                if (c < 0) throw IOException("Conexão encerrada")
                if (c == '>'.code) {
                    val r = sb.toString().replace("\r", " ").replace("\n", " ").trim()
                    log("$cmd → $r")
                    return r
                }
                sb.append(c.toChar())
            } else {
                Thread.sleep(20)
            }
        }
        log("$cmd → (sem resposta) ${sb.toString().trim()}")
        throw IOException("Adaptador não respondeu ao $cmd")
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
