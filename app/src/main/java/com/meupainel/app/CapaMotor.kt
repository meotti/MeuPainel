package com.meupainel.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Estado de cor/texto conforme a temperatura (mesmas regras da tela desenhada). */
data class EstadoMotor(val texto: String, val cor: Int, val fundo: Int)

fun estadoMotor(t: Int?, limite: Int): EstadoMotor = when {
    t == null -> EstadoMotor("Sem leitura", Color.parseColor("#A3A9B4"), Color.parseColor("#23262C"))
    t >= limite + 10 -> EstadoMotor("Muito quente", Color.parseColor("#FF8A80"), Color.parseColor("#4A2226"))
    t >= limite -> EstadoMotor("Atenção", Color.parseColor("#F6B26B"), Color.parseColor("#4A3520"))
    t < 70 -> EstadoMotor("Aquecendo", Color.parseColor("#8AB4F8"), Color.parseColor("#26324A"))
    else -> EstadoMotor("Normal", Color.parseColor("#7FD8A6"), Color.parseColor("#1F3A2C"))
}

/** Desenha o mostrador de temperatura como "capa de álbum" (512 x 512). */
object CapaMotor {
    private const val S = 512

    fun desenhar(t: Int?, limite: Int): Bitmap {
        val bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val est = estadoMotor(t, limite)

        c.drawColor(Color.parseColor("#0B0C0F"))

        val cx = 256f
        val cy = 300f
        val r = 190f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)

        p.style = Paint.Style.STROKE
        p.strokeWidth = 36f
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.parseColor("#262A31")
        c.drawArc(oval, 180f, 180f, false, p)

        val fLim = ((limite - 50f) / 80f).coerceIn(0f, 1f)
        p.color = Color.parseColor("#4A2226")
        c.drawArc(oval, 180f + 180f * fLim, 180f * (1f - fLim), false, p)

        if (t != null) {
            val f = ((t - 50f) / 80f).coerceIn(0.005f, 1f)
            p.color = est.cor
            c.drawArc(oval, 180f, 180f * f, false, p)
            val ang = PI * (1 - f)
            val px = cx + r * cos(ang).toFloat()
            val py = cy - r * sin(ang).toFloat()
            p.style = Paint.Style.FILL
            p.color = Color.parseColor("#0B0C0F")
            c.drawCircle(px, py, 24f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 8f
            p.color = est.cor
            c.drawCircle(px, py, 24f, p)
        }

        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT
        p.textAlign = Paint.Align.LEFT

        // Número grande
        val cond = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        val numero = t?.toString() ?: "--"
        p.typeface = cond
        p.textSize = 170f
        val wNum = p.measureText(numero)
        p.textSize = 72f
        val wUni = p.measureText("°C")
        val x0 = cx - (wNum + wUni) / 2f
        p.color = Color.parseColor("#ECEDEF")
        p.textSize = 170f
        c.drawText(numero, x0, 300f, p)
        p.color = Color.parseColor("#A3A9B4")
        p.textSize = 72f
        c.drawText("°C", x0 + wNum, 300f, p)

        // Selo de status
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        p.textSize = 44f
        val wTxt = p.measureText(est.texto)
        val selo = RectF(cx - wTxt / 2f - 32f, 370f, cx + wTxt / 2f + 32f, 446f)
        p.color = est.fundo
        c.drawRoundRect(selo, 38f, 38f, p)
        p.color = est.cor
        p.textAlign = Paint.Align.CENTER
        c.drawText(est.texto, cx, 424f, p)

        p.textSize = 26f
        p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        p.color = Color.parseColor("#A3A9B4")
        c.drawText("MOTOR · alerta em $limite °C", cx, 492f, p)

        return bmp
    }
}
