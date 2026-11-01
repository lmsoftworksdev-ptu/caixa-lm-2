package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ícones animados da v3 (sol girando e pulsando, nuvem balançando, chuva caindo, equalizador). Cada um é uma View que só
 * redesenha enquanto está visível e [Animacao.pausada] for falso (parado no modo ocioso).
 */
object Animacao {
    /** Modo ocioso: todos os ícones animados param. */
    @Volatile var pausada = false
}

class IconeAnimado(c: Context, private val tipo: String, private val cor: Int, private val tam: Int = 30, private val animado: Boolean = true) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pf = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nuvem = Path().apply {
        moveTo(17.5f, 19f); lineTo(9f, 19f)
        arcTo(RectF(2f, 5f, 16f, 19f), 90f, 253.4f)
        lineTo(17.5f, 10f)
        arcTo(RectF(13f, 10f, 22f, 19f), 270f, 180f)
        close()
    }
    private val nuvemChuva = Path().apply {
        moveTo(4f, 14.9f)
        arcTo(RectF(2f, 3f, 16f, 17f), 135.6f, 207.8f)
        lineTo(17.5f, 8f)
        arcTo(RectF(13f, 8f, 22f, 17f), 270f, 146f)
    }
    private val raios = floatArrayOf(
        12f, 2f, 12f, 4f, 12f, 20f, 12f, 22f, 4.93f, 4.93f, 6.34f, 6.34f, 17.66f, 17.66f, 19.07f, 19.07f,
        2f, 12f, 4f, 12f, 20f, 12f, 22f, 12f, 6.34f, 17.66f, 4.93f, 19.07f, 19.07f, 4.93f, 17.66f, 6.34f
    )

    private fun ativo() = isShown && !Animacao.pausada

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(tam.d(), tam.d())

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (animado) postInvalidateDelayed(33) }

    private fun suave(x: Float) = .5f - .5f * cos(x * 2f * PI.toFloat())     // 0 -> 1 -> 0 em um ciclo (ease in-out)

    override fun onDraw(canvas: Canvas) {
        val t = if (animado) SystemClock.uptimeMillis() else 0L          // parado (listas longas): desenha uma vez só
        val e = width / 24f
        p.color = cor
        p.strokeWidth = 2f * e
        canvas.save()
        canvas.scale(e, e)
        p.strokeWidth = 2f
        when (tipo) {
            "sol" -> {
                val pulso = 3f + 6f * suave((t % 3000) / 3000f)
                for (i in 0 until 3) {
                    pf.color = Color.argb(60 - i * 18, Color.red(cor), Color.green(cor), Color.blue(cor))
                    canvas.drawCircle(12f, 12f, 4f + pulso * (1f - i * .3f), pf)
                }
                canvas.save()
                canvas.rotate((t % 14000) / 14000f * 360f, 12f, 12f)
                var i = 0
                while (i < raios.size) { canvas.drawLine(raios[i], raios[i + 1], raios[i + 2], raios[i + 3], p); i += 4 }
                canvas.restore()
                canvas.drawCircle(12f, 12f, 4f, p)
            }
            "nublado" -> {
                val dx = -1.2f + 2.4f * suave((t % 4000) / 4000f)
                canvas.translate(dx, 0f)
                canvas.drawPath(nuvem, p)
            }
            "chuva" -> {
                canvas.drawPath(nuvemChuva, p)
                for (k in 0 until 3) {
                    val f = (((t - (k * 350L)) % 1100L) + 1100L) % 1100L / 1100f
                    val a = if (f < .5f) f * 2f else (1f - f) * 2f
                    val dy = -3f + 7f * f
                    p.alpha = (255 * a).toInt().coerceIn(0, 255)
                    val x = floatArrayOf(8f, 12f, 16f)[k]; val y0 = floatArrayOf(15f, 17f, 15f)[k]
                    canvas.drawLine(x, y0 + dy, x, y0 + 3f + dy, p)
                }
                p.alpha = 255
            }
            "eq" -> {
                canvas.restore(); canvas.save()          // em px do projeto (o ícone é um quadro de 56)
                val atrasos = floatArrayOf(0f, -.4f, -.7f, -.2f)
                val base = height.toFloat()
                pf.color = cor
                val larg = 4.d().toFloat(); val gap = 3.d().toFloat()
                val total = 4 * larg + 3 * gap
                var x = (width - total) / 2f
                for (k in 0 until 4) {
                    val f = ((((t / 1000f) + atrasos[k]) % 1f) + 1f) % 1f
                    val h = (5f + 17f * suave(f)).d()
                    val topo = (height + 22.d()) / 2f - h
                    canvas.drawRoundRect(x, topo, x + larg, topo + h, 2.d().toFloat(), 2.d().toFloat(), pf)
                    x += larg + gap
                }
            }
        }
        canvas.restore()
        if (animado && ativo()) postInvalidateDelayed(33)      // 30 quadros por segundo: leve para várias animações juntas
    }

    override fun onVisibilityAggregated(v: Boolean) { super.onVisibilityAggregated(v); if (v && animado) postInvalidateDelayed(33) }
}

/** Ícone "Mídia" da v3: retângulo arredondado com o triângulo de play vazado (só contorno, cantos retos). */
class IconeMidia(c: Context, private val cor: Int = Color.WHITE) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tri = Path().apply { moveTo(9.6f, 7.8f); rLineTo(0f, 8.4f); rLineTo(7f, -4.2f); close() }
    var traco = 1.9f

    override fun onDraw(canvas: Canvas) {
        val e = width / 24f
        canvas.scale(e, e)
        p.color = cor; p.strokeWidth = traco
        p.strokeJoin = Paint.Join.ROUND
        canvas.drawRoundRect(RectF(1.5f, 3f, 22.5f, 21f), 4.5f, 4.5f, p)
        p.strokeJoin = Paint.Join.MITER
        canvas.drawPath(tri, p)
    }
}
