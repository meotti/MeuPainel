package com.meupainel.app

import android.content.Context

data class Destino(val nome: String, val endereco: String)
data class Playlist(val nome: String, val valor: String)
data class Lembrete(val nome: String, val data: String)

/** Guarda as configurações no celular. Listas: uma linha por item, "Nome | valor". */
object Store {
    private const val PREFS = "meu_painel"
    const val DESTINOS = "destinos"
    const val PLAYLISTS = "playlists"
    const val LEMBRETES = "lembretes"
    private const val OBD_ENDERECO = "obd_endereco"
    private const val OBD_NOME = "obd_nome"
    private const val LIMITE = "limite_temp"

    private val padrao = mapOf(
        DESTINOS to "Casa | Rua Exemplo, 123, Sua Cidade\nTrabalho | Av. Exemplo, 456, Sua Cidade",
        PLAYLISTS to "Músicas curtidas | spotify:collection:tracks\nRock nacional | rock nacional",
        LEMBRETES to "Troca de óleo | 15/12/2026\nIPVA | 20/01/2027"
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun texto(ctx: Context, chave: String): String =
        prefs(ctx).getString(chave, padrao[chave]) ?: ""

    fun salvar(ctx: Context, chave: String, valor: String) {
        prefs(ctx).edit().putString(chave, valor.trim()).apply()
    }

    private fun pares(ctx: Context, chave: String): List<Pair<String, String>> =
        texto(ctx, chave).lines().mapNotNull { linha ->
            val partes = linha.split("|", limit = 2)
            if (partes.size == 2 && partes[0].isNotBlank() && partes[1].isNotBlank())
                partes[0].trim() to partes[1].trim()
            else null
        }

    fun destinos(ctx: Context) = pares(ctx, DESTINOS).map { Destino(it.first, it.second) }
    fun playlists(ctx: Context) = pares(ctx, PLAYLISTS).map { Playlist(it.first, it.second) }
    fun lembretes(ctx: Context) = pares(ctx, LEMBRETES).map { Lembrete(it.first, it.second) }

    fun obdEndereco(ctx: Context): String? = prefs(ctx).getString(OBD_ENDERECO, null)
    fun obdNome(ctx: Context): String? = prefs(ctx).getString(OBD_NOME, null)
    fun salvarObd(ctx: Context, nome: String, endereco: String) {
        prefs(ctx).edit().putString(OBD_NOME, nome).putString(OBD_ENDERECO, endereco).apply()
    }

    fun limite(ctx: Context): Int = prefs(ctx).getInt(LIMITE, 105)
    fun salvarLimite(ctx: Context, valor: Int) {
        prefs(ctx).edit().putInt(LIMITE, valor.coerceIn(80, 130)).apply()
    }
}
