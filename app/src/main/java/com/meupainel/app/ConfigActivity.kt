package com.meupainel.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Tela no celular para editar destinos, playlists, lembretes e o adaptador OBD2. */
class ConfigActivity : Activity() {
    private val campos = mutableMapOf<String, EditText>()
    private lateinit var avisoMidia: Button
    private lateinit var botaoObd: Button
    private lateinit var campoLimite: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(32))
        }

        fun texto(t: String, tamanho: Float = 14f, negrito: Boolean = false) =
            TextView(this).apply {
                text = t
                textSize = tamanho
                if (negrito) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, px(12), 0, px(4))
            }

        fun secao(chave: String, titulo: String, ajuda: String) {
            raiz.addView(texto(titulo, 18f, true))
            raiz.addView(texto(ajuda, 13f))
            val campo = EditText(this).apply {
                setText(Store.texto(this@ConfigActivity, chave))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
            }
            campos[chave] = campo
            raiz.addView(campo)
        }

        raiz.addView(texto("Meu Painel", 24f, true))
        raiz.addView(texto("O que você mudar aqui aparece na multimídia.\nListas: uma linha por item, no formato  Nome | valor"))

        secao(Store.DESTINOS, "Destinos favoritos",
            "Os dois primeiros viram os botões rápidos da tela inicial.\nEx.: Casa | Rua das Flores, 100, Cascavel")
        secao(Store.PLAYLISTS, "Atalhos de música (Spotify)",
            "Cole o link da playlist (Spotify → Compartilhar → Copiar link) ou escreva o que buscar.\nEx.: Treino | https://open.spotify.com/playlist/...")
        secao(Store.LEMBRETES, "Lembretes do carro",
            "Ex.: Troca de óleo | 15/12/2026")

        raiz.addView(texto("Sensor do carro (OBD2)", 18f, true))
        raiz.addView(texto("Pareie o adaptador ELM327 no Bluetooth do celular (senha 1234 ou 0000) e escolha ele aqui. Feche o Car Scanner antes de dirigir: o adaptador só atende um app por vez.", 13f))
        botaoObd = Button(this).apply { setOnClickListener { escolherAdaptador() } }
        raiz.addView(botaoObd)
        raiz.addView(texto("Avisar quando a temperatura passar de (°C):", 14f))
        campoLimite = EditText(this).apply {
            setText(Store.limite(this@ConfigActivity).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        raiz.addView(campoLimite)

        raiz.addView(Button(this).apply {
            text = "Salvar"
            setOnClickListener {
                campos.forEach { (chave, campo) -> Store.salvar(this@ConfigActivity, chave, campo.text.toString()) }
                campoLimite.text.toString().toIntOrNull()?.let { Store.salvarLimite(this@ConfigActivity, it) }
                Toast.makeText(this@ConfigActivity, "Salvo! Já aparece no Android Auto.", Toast.LENGTH_SHORT).show()
            }
        })

        avisoMidia = Button(this).apply {
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        raiz.addView(avisoMidia)

        setContentView(ScrollView(this).apply { addView(raiz) })
        pedirPermissoes()
    }

    private fun pedirPermissoes() {
        val faltando = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) faltando += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) faltando += Manifest.permission.POST_NOTIFICATIONS
        if (faltando.isNotEmpty()) requestPermissions(faltando.toTypedArray(), 10)
    }

    @SuppressLint("MissingPermission")
    private fun escolherAdaptador() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            pedirPermissoes()
            Toast.makeText(this, "Permita o acesso ao Bluetooth e toque de novo", Toast.LENGTH_LONG).show()
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Ligue o Bluetooth do celular", Toast.LENGTH_LONG).show()
            return
        }
        val pareados = adapter.bondedDevices.toList()
        if (pareados.isEmpty()) {
            Toast.makeText(this, "Pareie o adaptador OBD2 no Bluetooth primeiro", Toast.LENGTH_LONG).show()
            return
        }
        val nomes = pareados.map { (it.name ?: "Sem nome") + "\n" + it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Escolha o adaptador OBD2")
            .setItems(nomes) { _, i ->
                val d = pareados[i]
                Store.salvarObd(this, d.name ?: d.address, d.address)
                atualizar()
            }
            .show()
    }

    private fun atualizar() {
        avisoMidia.text = if (Midia.temPermissao(this))
            "Controle de música: liberado ✓"
        else
            "Liberar controle de música (acesso a notificações)"
        val nome = Store.obdNome(this)
        botaoObd.text = if (nome != null) "Adaptador: $nome (trocar)" else "Escolher adaptador OBD2"
    }

    override fun onResume() {
        super.onResume()
        atualizar()
    }
}
