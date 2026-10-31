package com.meupainel.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class PainelEstado(
    val saudacao: String,
    val data: String,
    val temp: Int?,
    val obdStatus: String,
    val obdOk: Boolean,
    val limite: Int,
    val musica: String,
    val artista: String,
    val tocando: Boolean,
    val destino1: String,
    val destino2: String,
    val lembreteNome: String,
    val lembreteQuando: String,
    val bateria: String
)

enum class Alvo { ANTERIOR, PLAY, PROXIMA, DESTINO1, DESTINO2, MAIS_DESTINOS, PAINEL, MUSICA }

/**
 * Desenha a tela inicial (mostrador de temperatura + cartões) na superfície
 * do Android Auto. Coordenadas de projeto: 944 x 600, escaladas para a tela real.
 */
class Renderer {
    private val W = 944f
    private val H = 600f

    private val alvos = LinkedHashMap<Alvo, RectF>()
    private var escala = 1f
    private var dx = 0f
    private var dy = 0f

    var acento: Int = cor("#8AB4F8")

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tNormal = Typeface.create("sans-serif", Typeface.NORMAL)
    private val tMedio = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val tCond = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private val fundo = cor("#0B0C0F")
    private val cartao = cor("#16181D")
    private val botao = cor("#23262C")
    private val trilho = cor("#262A31")
    private val texto = cor("#ECEDEF")
    private val cinza = cor("#A3A9B4")

    private fun cor(hex: String) = Color.parseColor(hex)

    fun desenhar(c: Canvas, area: Rect, e: PainelEstado) {
        c.drawColor(fundo)
        if (area.width() <= 0 || area.height() <= 0) return
        escala = min(area.width() / W, area.height() / H)
        dx = area.left + (area.width() - W * escala) / 2f
        dy = area.top + (area.height() - H * escala) / 2f
        alvos.clear()

        c.save()
        c.translate(dx, dy)
        c.scale(escala, escala)

        // Cabeçalho
        val wSaud = escrever(c, e.saudacao, 28f, 58f, 30f, texto, tMedio)
        escrever(c, e.data, 28f + wSaud + 14f, 58f, 18f, cinza, tNormal)

        desenharMostrador(c, e)
        desenharMusica(c, e)
        desenharIrPara(c, e)
        desenharResumo(c, e)

        c.restore()
    }

    /** Converte um toque na tela para o botão desenhado ali. */
    fun tocar(x: Float, y: Float): Alvo? {
        val px = (x - dx) / escala
        val py = (y - dy) / escala
        alvos.entries.firstOrNull { it.value.contains(px, py) }?.let { return it.key }
        return if (resumoArea.contains(px, py)) Alvo.PAINEL else null
    }

    // ---------- Mostrador ----------

    private fun desenharMostrador(c: Canvas, e: PainelEstado) {
        val card = RectF(28f, 80f, 498f, 572f)
        caixa(c, card, cartao, 28f)

        espacado(c, "ARREFECIMENTO", 52f, 124f, 16f, cinza)
        val corObd = if (e.obdOk) cor("#7FD8A6") else cor("#F6B26B")
        val wStatus = medir(e.obdStatus, 15f, tNormal)
        escrever(c, e.obdStatus, 474f - wStatus, 124f, 15f, cinza, tNormal)
        p.style = Paint.Style.FILL; p.color = corObd
        c.drawCircle(474f - wStatus - 12f, 119f, 4f, p)

        val (statusTxt, statusCor, statusFundo) = estadoTemp(e.temp, e.limite)
        val cx = 263f
        val cy = 344f
        val r = 160f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)

        p.style = Paint.Style.STROKE
        p.strokeWidth = 22f
        p.strokeCap = Paint.Cap.ROUND
        p.color = trilho
        c.drawArc(oval, 180f, 180f, false, p)
        // Zona vermelha a partir do limite
        val fLim = ((e.limite - 50f) / 80f).coerceIn(0f, 1f)
        p.color = cor("#4A2226")
        c.drawArc(oval, 180f + 180f * fLim, 180f * (1f - fLim), false, p)

        val t = e.temp
        if (t != null) {
            val f = ((t - 50f) / 80f).coerceIn(0.005f, 1f)
            p.color = statusCor
            c.drawArc(oval, 180f, 180f * f, false, p)
            val ang = PI * (1 - f)
            val px = cx + r * cos(ang).toFloat()
            val py = cy - r * sin(ang).toFloat()
            p.style = Paint.Style.FILL; p.color = fundo
            c.drawCircle(px, py, 15f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = statusCor
            c.drawCircle(px, py, 15f, p)
        }
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT

        centralizar(c, "50", cx - r, cy + 30f, 16f, cinza, tNormal)
        centralizar(c, "90", cx, cy - r - 22f, 16f, cinza, tNormal)
        centralizar(c, "130", cx + r, cy + 30f, 16f, cinza, tNormal)

        val numero = t?.toString() ?: "--"
        val wNum = medir(numero, 96f, tCond)
        val wUni = medir("°C", 44f, tCond)
        val x0 = cx - (wNum + wUni) / 2f
        escrever(c, numero, x0, 330f, 96f, texto, tCond)
        escrever(c, "°C", x0 + wNum, 330f, 44f, cinza, tCond)

        // Selo de status + limite
        val sub = "alerta em ${e.limite} °C"
        val wPill = medir(statusTxt, 22f, tMedio) + 44f
        val wSub = medir(sub, 16f, tNormal)
        val total = wPill + 12f + wSub
        val xp = cx - total / 2f
        val pill = RectF(xp, 508f, xp + wPill, 552f)
        caixa(c, pill, statusFundo, 22f)
        escrever(c, statusTxt, xp + 22f, 538f, 22f, statusCor, tMedio)
        escrever(c, sub, xp + wPill + 12f, 536f, 16f, cinza, tNormal)

        alvos[Alvo.PAINEL] = card
    }

    private fun estadoTemp(t: Int?, limite: Int): Triple<String, Int, Int> = when {
        t == null -> Triple("Sem leitura", cinza, botao)
        t >= limite + 10 -> Triple("Muito quente", cor("#FF8A80"), cor("#4A2226"))
        t >= limite -> Triple("Atenção", cor("#F6B26B"), cor("#4A3520"))
        t < 70 -> Triple("Aquecendo", cor("#8AB4F8"), cor("#26324A"))
        else -> Triple("Normal", cor("#7FD8A6"), cor("#1F3A2C"))
    }

    // ---------- Música ----------

    private fun desenharMusica(c: Canvas, e: PainelEstado) {
        caixa(c, RectF(514f, 80f, 916f, 246f), cartao, 28f)
        val capa = RectF(534f, 98f, 598f, 162f)
        caixa(c, capa, cor("#26324A"), 14f)
        // nota musical
        p.style = Paint.Style.STROKE; p.strokeWidth = 3.5f; p.color = acento
        p.strokeCap = Paint.Cap.ROUND
        c.drawLine(572f, 114f, 572f, 142f, p)
        c.drawLine(572f, 114f, 584f, 118f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(565f, 143f, 7f, p)
        p.strokeCap = Paint.Cap.BUTT

        escrever(c, cortar(e.musica, 282f, 22f, tMedio), 614f, 126f, 22f, texto, tMedio)
        escrever(c, cortar(e.artista, 282f, 17f, tNormal), 614f, 153f, 17f, cinza, tNormal)
        alvos[Alvo.MUSICA] = RectF(514f, 80f, 916f, 170f)

        val b1 = RectF(534f, 176f, 648f, 228f)
        val b2 = RectF(658f, 176f, 772f, 228f)
        val b3 = RectF(782f, 176f, 896f, 228f)
        caixa(c, b1, botao, 26f)
        caixa(c, b2, acento, 26f)
        caixa(c, b3, botao, 26f)
        iconeAnterior(c, b1.centerX(), b1.centerY(), texto)
        if (e.tocando) iconePausa(c, b2.centerX(), b2.centerY(), fundo)
        else iconePlay(c, b2.centerX(), b2.centerY(), fundo)
        iconeProxima(c, b3.centerX(), b3.centerY(), texto)
        alvos[Alvo.ANTERIOR] = b1
        alvos[Alvo.PLAY] = b2
        alvos[Alvo.PROXIMA] = b3
    }

    // ---------- Ir para ----------

    private fun desenharIrPara(c: Canvas, e: PainelEstado) {
        caixa(c, RectF(514f, 262f, 916f, 378f), cartao, 28f)
        espacado(c, "IR PARA", 534f, 296f, 16f, cinza)
        val bs = listOf(
            Alvo.DESTINO1 to (RectF(534f, 310f, 648f, 362f) to e.destino1),
            Alvo.DESTINO2 to (RectF(658f, 310f, 772f, 362f) to e.destino2),
            Alvo.MAIS_DESTINOS to (RectF(782f, 310f, 896f, 362f) to "Mais")
        )
        for ((alvo, par) in bs) {
            val (r, nome) = par
            caixa(c, r, botao, 26f)
            centralizar(c, cortar(nome, r.width() - 16f, 19f, tMedio), r.centerX(), r.centerY() + 7f, 19f, texto, tMedio)
            alvos[alvo] = r
        }
    }

    // ---------- Resumo ----------

    private fun desenharResumo(c: Canvas, e: PainelEstado) {
        val r = RectF(514f, 394f, 916f, 572f)
        caixa(c, r, cartao, 28f)
        escrever(c, cortar(e.lembreteNome, 170f, 16f, tNormal), 534f, 470f, 16f, cinza, tNormal)
        escrever(c, cortar(e.lembreteQuando, 170f, 22f, tMedio), 534f, 500f, 22f, texto, tMedio)
        escrever(c, "Celular", 725f, 470f, 16f, cinza, tNormal)
        escrever(c, cortar(e.bateria, 170f, 22f, tMedio), 725f, 500f, 22f, texto, tMedio)
        resumoArea = r
    }

    private var resumoArea = RectF()

    // ---------- Ajudantes de desenho ----------

    private fun caixa(c: Canvas, r: RectF, cor: Int, raio: Float) {
        p.style = Paint.Style.FILL
        p.color = cor
        c.drawRoundRect(r, raio, raio, p)
    }

    private fun prepararTexto(tamanho: Float, cor: Int, tf: Typeface) {
        p.style = Paint.Style.FILL
        p.textSize = tamanho
        p.color = cor
        p.typeface = tf
        p.letterSpacing = 0f
        p.textAlign = Paint.Align.LEFT
    }

    private fun escrever(c: Canvas, s: String, x: Float, y: Float, tamanho: Float, cor: Int, tf: Typeface): Float {
        prepararTexto(tamanho, cor, tf)
        c.drawText(s, x, y, p)
        return p.measureText(s)
    }

    private fun espacado(c: Canvas, s: String, x: Float, y: Float, tamanho: Float, cor: Int) {
        prepararTexto(tamanho, cor, tMedio)
        p.letterSpacing = 0.12f
        c.drawText(s, x, y, p)
        p.letterSpacing = 0f
    }

    private fun centralizar(c: Canvas, s: String, cx: Float, y: Float, tamanho: Float, cor: Int, tf: Typeface) {
        prepararTexto(tamanho, cor, tf)
        p.textAlign = Paint.Align.CENTER
        c.drawText(s, cx, y, p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun medir(s: String, tamanho: Float, tf: Typeface): Float {
        prepararTexto(tamanho, texto, tf)
        return p.measureText(s)
    }

    private fun cortar(s: String, maxW: Float, tamanho: Float, tf: Typeface): String {
        if (medir(s, tamanho, tf) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && medir("$t…", tamanho, tf) > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private fun iconePlay(c: Canvas, x: Float, y: Float, cor: Int) {
        p.style = Paint.Style.FILL; p.color = cor
        val path = Path().apply { moveTo(x - 7f, y - 11f); lineTo(x + 11f, y); lineTo(x - 7f, y + 11f); close() }
        c.drawPath(path, p)
    }

    private fun iconePausa(c: Canvas, x: Float, y: Float, cor: Int) {
        p.style = Paint.Style.FILL; p.color = cor
        c.drawRoundRect(RectF(x - 9f, y - 11f, x - 3f, y + 11f), 2f, 2f, p)
        c.drawRoundRect(RectF(x + 3f, y - 11f, x + 9f, y + 11f), 2f, 2f, p)
    }

    private fun iconeProxima(c: Canvas, x: Float, y: Float, cor: Int) {
        p.style = Paint.Style.FILL; p.color = cor
        val path = Path().apply { moveTo(x - 9f, y - 10f); lineTo(x + 5f, y); lineTo(x - 9f, y + 10f); close() }
        c.drawPath(path, p)
        c.drawRect(x + 6f, y - 10f, x + 9f, y + 10f, p)
    }

    private fun iconeAnterior(c: Canvas, x: Float, y: Float, cor: Int) {
        p.style = Paint.Style.FILL; p.color = cor
        val path = Path().apply { moveTo(x + 9f, y - 10f); lineTo(x - 5f, y); lineTo(x + 9f, y + 10f); close() }
        c.drawPath(path, p)
        c.drawRect(x - 9f, y - 10f, x - 6f, y + 10f, p)
    }
}
