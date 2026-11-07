package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
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
 * Ícone do tempo, grande e colorido: desenhado com caminhos vetoriais num quadro de 100 x 100 e ampliado para o tamanho da tela (nítido em qualquer
 * tamanho). Sol amarelo com degradê, nuvens brancas com sombra azulada, gotas azuis, raio amarelo, lua clara. Anima de leve (raios girando, nuvem
 * balançando, gotas caindo). [tipo] é o mesmo de [tipoDoCodigo].
 */
class IconeClima(c: Context, private val tipo: String, private val tam: Int, private val animado: Boolean = true) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val traco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    // nuvem: três círculos e uma base arredondada, unidos (x de 17 a 88, y de 22 a 73)
    private val nuvem = Path().apply {
        addCircle(34f, 56f, 17f, Path.Direction.CW)
        val b = Path().apply { addCircle(52f, 44f, 22f, Path.Direction.CW) }
        val c3 = Path().apply { addCircle(72f, 55f, 16f, Path.Direction.CW) }
        val base = Path().apply { addRoundRect(RectF(17f, 54f, 88f, 73f), 9.5f, 9.5f, Path.Direction.CW) }
        op(b, Path.Op.UNION); op(c3, Path.Op.UNION); op(base, Path.Op.UNION)
    }
    private val lua = Path().apply {
        addCircle(50f, 50f, 22f, Path.Direction.CW)
        val corte = Path().apply { addCircle(62f, 43f, 18.5f, Path.Direction.CW) }
        op(corte, Path.Op.DIFFERENCE)
    }
    private val raio = Path().apply {
        moveTo(55f, 60f); lineTo(41f, 80f); lineTo(51f, 80f); lineTo(45f, 97f); lineTo(67f, 73f); lineTo(56f, 73f); lineTo(62f, 60f); close()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(tam.d(), tam.d())
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (animado) postInvalidateDelayed(33) }
    override fun onVisibilityAggregated(v: Boolean) { super.onVisibilityAggregated(v); if (v && animado) postInvalidateDelayed(33) }

    private fun suave(x: Float) = .5f - .5f * cos(x * 2f * PI.toFloat())

    private fun desenharNuvem(canvas: Canvas, dx: Float, dy: Float, esc: Float, topo: Int, base: Int) {
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(esc, esc, 52f, 48f)
        p.shader = LinearGradient(0f, 22f, 0f, 73f, topo, base, Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        canvas.drawPath(nuvem, p)
        p.shader = null
        canvas.restore()
    }

    private fun desenharSol(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Long) {
        // raios girando devagar
        canvas.save()
        canvas.rotate((t % 24000L) / 24000f * 360f, cx, cy)
        traco.color = Color.parseColor("#FFC247"); traco.strokeWidth = r * .24f
        for (k in 0 until 8) {
            val a = k * PI.toFloat() / 4f
            canvas.drawLine(cx + cos(a) * r * 1.45f, cy + sin(a) * r * 1.45f, cx + cos(a) * r * 1.9f, cy + sin(a) * r * 1.9f, traco)
        }
        canvas.restore()
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx - r * .3f, cy - r * .3f, r * 1.4f, intArrayOf(Color.parseColor("#FFE580"), Color.parseColor("#FFB429")), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, p)
        p.shader = null
    }

    private fun desenharLua(canvas: Canvas, dx: Float, dy: Float, esc: Float) {
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(esc, esc, 50f, 50f)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(30f, 28f, 70f, 72f, Color.parseColor("#FFF6CF"), Color.parseColor("#EFD27A"), Shader.TileMode.CLAMP)
        canvas.drawPath(lua, p)
        p.shader = null
        canvas.restore()
    }

    private fun estrela(canvas: Canvas, x: Float, y: Float, r: Float, alfa: Float) {
        traco.color = Color.WHITE; traco.alpha = (255 * alfa).toInt().coerceIn(0, 255); traco.strokeWidth = r * .55f
        canvas.drawLine(x - r, y, x + r, y, traco); canvas.drawLine(x, y - r, x, y + r, traco)
        traco.alpha = 255
    }

    private fun gotas(canvas: Canvas, t: Long, x0: Float, y0: Float, n: Int, forte: Boolean) {
        traco.color = Color.parseColor("#6FB6FF"); traco.strokeWidth = 4.2f
        val xs = floatArrayOf(x0, x0 + 16f, x0 + 32f, x0 + 8f, x0 + 24f)
        for (k in 0 until n) {
            val f = (((t - k * 260L) % 900L) + 900L) % 900L / 900f
            val a = if (f < .15f) f / .15f else if (f > .75f) (1f - f) / .25f else 1f
            val dy = f * 12f
            traco.alpha = (255 * a).toInt().coerceIn(0, 255)
            val x = xs[k % xs.size] - dy * .3f
            val y = y0 + (if (k % 2 == 0) 0f else 7f) + dy
            canvas.drawLine(x, y, x - 3f, y + (if (forte) 10f else 7f), traco)
        }
        traco.alpha = 255
    }

    override fun onDraw(canvas: Canvas) {
        val t = if (animado) SystemClock.uptimeMillis() else 0L
        canvas.save()
        val e = width / 100f
        canvas.scale(e, e)
        val bal = if (animado) -1.6f + 3.2f * suave((t % 6000L) / 6000f) else 0f      // balanço da nuvem
        val claro = Color.WHITE; val clarob = Color.parseColor("#C9D6EE")
        val escuro = Color.parseColor("#C2CEE4"); val escurob = Color.parseColor("#8C9ABA")

        when (tipo) {
            "sol" -> desenharSol(canvas, 50f, 50f, 19f, t)
            "lua" -> { desenharLua(canvas, bal * .5f, 0f, 1f); estrela(canvas, 74f, 26f, 4f, .5f + .5f * suave((t % 3000L) / 3000f)); estrela(canvas, 26f, 72f, 3f, .5f + .5f * suave(((t + 1200L) % 3000L) / 3000f)) }
            "parcial" -> { desenharSol(canvas, 38f, 36f, 14f, t); desenharNuvem(canvas, 6f + bal, 12f, .92f, claro, clarob) }
            "parcialnoite" -> { desenharLua(canvas, -18f, -18f, .6f); desenharNuvem(canvas, 6f + bal, 12f, .92f, claro, clarob) }
            "nublado" -> { desenharNuvem(canvas, -14f - bal, -8f, .75f, Color.parseColor("#B4C1DA"), Color.parseColor("#8896B5")); desenharNuvem(canvas, 4f + bal, 8f, .95f, claro, clarob) }
            "neblina" -> {
                desenharNuvem(canvas, bal, -10f, .95f, claro, clarob)
                traco.color = Color.parseColor("#AEBBD8"); traco.strokeWidth = 5f
                canvas.drawLine(26f, 73f, 76f, 73f, traco); canvas.drawLine(34f, 83f, 84f, 83f, traco); canvas.drawLine(22f, 93f, 62f, 93f, traco)
            }
            "garoa" -> { desenharNuvem(canvas, bal, -8f, .95f, escuro, escurob); gotas(canvas, t, 36f, 68f, 3, false) }
            "chuva" -> { desenharNuvem(canvas, bal, -8f, .95f, escuro, escurob); gotas(canvas, t, 32f, 66f, 5, true) }
            "pancadas" -> { desenharSol(canvas, 32f, 30f, 11f, t); desenharNuvem(canvas, 8f + bal, 2f, .9f, escuro, escurob); gotas(canvas, t, 38f, 70f, 4, true) }
            "trovoada" -> {
                desenharNuvem(canvas, bal, -12f, .95f, Color.parseColor("#AEBAD4"), Color.parseColor("#6F7C9B"))
                val flash = if (animado) 0.55f + .45f * suave((t % 1800L) / 1800f) else 1f
                p.style = Paint.Style.FILL
                p.shader = LinearGradient(0f, 60f, 0f, 97f, Color.parseColor("#FFE36B"), Color.parseColor("#FFB020"), Shader.TileMode.CLAMP)
                p.alpha = (255 * flash).toInt(); canvas.drawPath(raio, p); p.shader = null; p.alpha = 255
            }
            "neve" -> {
                desenharNuvem(canvas, bal, -10f, .95f, escuro, escurob)
                p.style = Paint.Style.FILL; p.color = Color.WHITE
                val xs = floatArrayOf(34f, 50f, 66f, 42f, 58f)
                for (k in 0 until 5) {
                    val f = (((t - k * 380L) % 2400L) + 2400L) % 2400L / 2400f
                    val a = if (f < .15f) f / .15f else if (f > .8f) (1f - f) / .2f else 1f
                    p.alpha = (255 * a).toInt().coerceIn(0, 255)
                    canvas.drawCircle(xs[k] + sin(f * 6f + k) * 2f, 68f + (if (k % 2 == 0) 0f else 7f) + f * 14f, 3.2f, p)
                }
                p.alpha = 255
            }
            else -> desenharNuvem(canvas, bal, 0f, 1f, claro, clarob)
        }
        canvas.restore()
        if (animado && isShown && !Animacao.pausada) postInvalidateDelayed(33)
    }
}
