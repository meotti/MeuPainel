package com.meupainel.app

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/** Só existe para o Android liberar o controle das sessões de mídia (Spotify). */
class MidiaListener : NotificationListenerService()

data class InfoMidia(val titulo: String, val artista: String, val app: String, val tocando: Boolean)

object Midia {
    private const val SPOTIFY = "com.spotify.music"

    fun temPermissao(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    private fun controlador(ctx: Context): MediaController? {
        if (!temPermissao(ctx)) return null
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return null
        val sessoes = try {
            msm.getActiveSessions(ComponentName(ctx, MidiaListener::class.java))
        } catch (e: SecurityException) {
            return null
        }
        return sessoes.firstOrNull { it.packageName == SPOTIFY } ?: sessoes.firstOrNull()
    }

    private fun semControle(ctx: Context) =
        if (!temPermissao(ctx)) "Libere o acesso a notificações no app Meu Painel"
        else "Toque algo no Spotify uma vez para ativar"

    /** Aceita link do Spotify (open.spotify.com/...), URI (spotify:...) ou um texto de busca. */
    private fun normalizar(valor: String): String {
        val m = Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?(playlist|album|artist|track)/([A-Za-z0-9]+)")
            .find(valor)
        return if (m != null) "spotify:${m.groupValues[1]}:${m.groupValues[2]}" else valor
    }

    fun tocar(ctx: Context, p: Playlist): String {
        val c = controlador(ctx) ?: return semControle(ctx)
        val v = normalizar(p.valor)
        if (v.startsWith("spotify:")) c.transportControls.playFromUri(Uri.parse(v), null)
        else c.transportControls.playFromSearch(v, null)
        return "Tocando ${p.nome}"
    }

    fun playPausa(ctx: Context): String {
        val c = controlador(ctx) ?: return semControle(ctx)
        return if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause(); "Pausado"
        } else {
            c.transportControls.play(); "Tocando"
        }
    }

    fun proxima(ctx: Context): String {
        val c = controlador(ctx) ?: return semControle(ctx)
        c.transportControls.skipToNext()
        return "Próxima"
    }

    fun anterior(ctx: Context): String {
        val c = controlador(ctx) ?: return semControle(ctx)
        c.transportControls.skipToPrevious()
        return "Anterior"
    }

    fun info(ctx: Context): InfoMidia? {
        val c = controlador(ctx) ?: return null
        val md = c.metadata ?: return null
        val titulo = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artista = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val app = if (c.packageName == SPOTIFY) "Spotify" else "Música"
        return InfoMidia(titulo, artista, app, c.playbackState?.state == PlaybackState.STATE_PLAYING)
    }

    fun tocandoAgora(ctx: Context): String? =
        info(ctx)?.let { listOf(it.titulo, it.artista).filter { s -> s.isNotBlank() }.joinToString(" — ") }
}
