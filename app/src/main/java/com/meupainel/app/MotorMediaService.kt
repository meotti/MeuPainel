package com.meupainel.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Meu Painel como "app de música" no Android Auto.
 * A capa do "tocando agora" é o mostrador de temperatura do motor, atualizado a cada 2 s.
 * Não toca áudio nenhum: o Spotify continua tocando normalmente.
 */
class MotorMediaService : MediaBrowserServiceCompat() {

    companion object {
        private const val RAIZ = "raiz"
        private const val ABA_MOTOR = "aba_motor"
        private const val ABA_PLAYLISTS = "aba_playlists"
        private const val ITEM_MOTOR = "motor"
        private const val ACAO_PLAY_PAUSA = "spotify_play_pausa"
        private const val ACAO_PROXIMA = "spotify_proxima"
    }

    private lateinit var sessao: MediaSessionCompat
    private val handler = Handler(Looper.getMainLooper())
    private var mostrando = true
    private var ultimaTemp: Int? = Int.MIN_VALUE
    private var ultimoStatus = ""
    private var tts: TextToSpeech? = null
    private var ttsPronto = false

    private val tick = object : Runnable {
        override fun run() {
            atualizar(false)
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sessao = MediaSessionCompat(this, "MeuPainel").apply {
            setCallback(callback)
            isActive = true
        }
        sessionToken = sessao.sessionToken

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ttsPronto = true
            }
        }

        Obd.iniciar(this) { t, lim -> handler.post { alertar(t, lim) } }
        atualizar(true)
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        Obd.parar()
        tts?.shutdown()
        sessao.release()
        super.onDestroy()
    }

    // ---------- Navegação (abas e listas) ----------

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        // Pede ao Android Auto para mostrar a primeira aba como lista.
        val extras = Bundle().apply {
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
        }
        return BrowserRoot(RAIZ, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        val itens = mutableListOf<MediaItem>()
        when (parentId) {
            RAIZ -> {
                itens += pasta(ABA_MOTOR, "Motor")
                itens += pasta(ABA_PLAYLISTS, "Playlists")
            }
            ABA_MOTOR -> {
                val t = Obd.temperatura
                val lim = Store.limite(this)
                val est = estadoMotor(t, lim)
                itens += tocavel(
                    ITEM_MOTOR,
                    if (t != null) "$t °C · ${est.texto}" else "-- °C · ${Obd.status}",
                    "Toque para mostrar o mostrador · alerta em $lim °C"
                )
                lembretes().forEachIndexed { i, (nome, quando) ->
                    itens += tocavel("lembrete:$i", nome, quando)
                }
            }
            ABA_PLAYLISTS -> {
                Store.playlists(this).forEachIndexed { i, p ->
                    itens += tocavel("pl:$i", p.nome, "Tocar no Spotify")
                }
            }
        }
        result.sendResult(itens)
    }

    private fun pasta(id: String, titulo: String) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(titulo).build(),
        MediaItem.FLAG_BROWSABLE
    )

    private fun tocavel(id: String, titulo: String, sub: String) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(titulo).setSubtitle(sub).build(),
        MediaItem.FLAG_PLAYABLE
    )

    private fun lembretes(): List<Pair<String, String>> {
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val hoje = LocalDate.now()
        return Store.lembretes(this).map { l ->
            val d = try { LocalDate.parse(l.data, fmt) } catch (e: Exception) { null }
            val quando = if (d == null) "Data inválida" else {
                val dias = ChronoUnit.DAYS.between(hoje, d)
                when {
                    dias > 1 -> "Em $dias dias (${l.data})"
                    dias == 1L -> "Amanhã"
                    dias == 0L -> "Hoje"
                    else -> "Venceu há ${-dias} dias"
                }
            }
            l.nome to quando
        }
    }

    // ---------- Botões ----------

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            mostrando = true
            atualizar(true)
        }

        override fun onPause() {
            mostrando = false
            atualizar(true)
        }

        override fun onStop() = onPause()

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (mediaId != null && mediaId.startsWith("pl:")) {
                val i = mediaId.removePrefix("pl:").toIntOrNull() ?: return
                Store.playlists(this@MotorMediaService).getOrNull(i)?.let {
                    Midia.tocar(this@MotorMediaService, it)
                }
                return
            }
            onPlay()
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            when (action) {
                ACAO_PLAY_PAUSA -> Midia.playPausa(this@MotorMediaService)
                ACAO_PROXIMA -> Midia.proxima(this@MotorMediaService)
            }
        }
    }

    // ---------- Atualização da capa ----------

    private fun atualizar(forcar: Boolean) {
        val t = Obd.temperatura
        val status = Obd.status
        if (!forcar && t == ultimaTemp && status == ultimoStatus) return
        val mudouLinha = t != ultimaTemp || status != ultimoStatus
        ultimaTemp = t
        ultimoStatus = status

        val lim = Store.limite(this)
        val est = estadoMotor(t, lim)
        val titulo = when {
            t == null -> "-- °C"
            t >= lim -> "$t °C · MOTOR QUENTE"
            else -> "$t °C"
        }
        val sub = if (t == null) status else "Motor ${est.texto.lowercase()} · alerta em $lim °C"

        sessao.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, ITEM_MOTOR)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, titulo)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, titulo)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, sub)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, sub)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Meu Painel")
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, CapaMotor.desenhar(t, lim))
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
                .build()
        )

        val estado = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
            )
            .setState(
                if (mostrando) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f
            )
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(ACAO_PLAY_PAUSA, "Spotify: tocar/pausar", R.drawable.ic_musica).build()
            )
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(ACAO_PROXIMA, "Spotify: próxima", R.drawable.ic_proxima).build()
            )
            .build()
        sessao.setPlaybackState(estado)

        if (mudouLinha) notifyChildrenChanged(ABA_MOTOR)
    }

    // ---------- Alerta de voz ----------

    private fun alertar(temp: Int, limite: Int) {
        atualizar(true)
        val falar = tts ?: return
        if (!ttsPronto) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val foco = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        am.requestAudioFocus(foco)
        falar.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { am.abandonAudioFocusRequest(foco) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { am.abandonAudioFocusRequest(foco) }
        })
        falar.speak(
            "Atenção. Motor quente, $temp graus. Pare em um local seguro assim que possível.",
            TextToSpeech.QUEUE_FLUSH, null, "alerta"
        )
    }
}
