package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View

/**
 * Disco de vinil que fica atrás da capa na tela Tocando agora. O disco em si (sulcos e rótulo) fica PARADO; o que gira é o brilho: duas
 * faixas de luz opostas rodam em volta do disco, no sentido horário (o sentido em que um vinil gira), como o reflexo numa vitrola.
 * Tocando, o brilho roda a uns 30 quadros por segundo e só enquanto a tela está à vista; pausado, ele para exatamente onde está, e o
 * disco continua no lugar. A cor do rótulo vem da capa.
 */
class VinilView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var angulo = 0f
    private var tocando = false
    private var rodando = false
    private var ultimo = 0L
    private var chave = ""
    private var corRotulo = Color.parseColor("#E0527A")
    private var brilho: SweepGradient? = null
    private var fundo: RadialGradient? = null

    private val passo = object : Runnable {
        override fun run() {
            if (!isShown || !tocando) { rodando = false; return }
            val agora = SystemClock.uptimeMillis()
            val dt = if (ultimo == 0L) 0.033f else ((agora - ultimo) / 1000f).coerceAtMost(0.1f)
            ultimo = agora
            angulo = (angulo + dt * 110f) % 360f      // horário, uma volta a cada 3,3 s
            invalidate()
            postDelayed(this, 33L)
        }
    }

    /** A lista de Próximas está por cima: o vinil fica parado (o giro redesenhava a cada quadro uma tela que já está coberta). */
    var pausado = false
        set(v) { if (field != v) { field = v; conferir() } }

    private fun conferir() {
        if (isAttachedToWindow && isShown && tocando && !pausado) { if (!rodando) { rodando = true; ultimo = 0L; post(passo) } }
        else { rodando = false; removeCallbacks(passo) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); conferir() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); rodando = false; removeCallbacks(passo) }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); conferir() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); conferir() }

    /** [tocando] liga o giro do brilho; [nova] muda quando a faixa muda (troca a cor do rótulo). */
    fun definir(tocando: Boolean, nova: String) {
        if (nova != chave) { chave = nova; corRotulo = CorCapa.hsl(CapaView.matiz(nova), .8f, .5f); invalidate() }
        if (tocando != this.tocando) { this.tocando = tocando; conferir() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val r = minOf(w, h) / 2f
        fundo = RadialGradient(w / 2f, h / 2f, r, intArrayOf(Color.parseColor("#232838"), Color.parseColor("#0C0E15")), floatArrayOf(0.2f, 1f), Shader.TileMode.CLAMP)
        // duas faixas de luz opostas (a 0° e a 180°), macias nas pontas
        val t = Color.TRANSPARENT; val l = Color.argb(96, 255, 255, 255)
        brilho = SweepGradient(w / 2f, h / 2f,
            intArrayOf(t, l, t, t, l, t, t),
            floatArrayOf(0f, 0.06f, 0.14f, 0.5f, 0.56f, 0.64f, 1f))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f - 1f
        p.style = Paint.Style.FILL
        p.shader = fundo
        canvas.drawCircle(cx, cy, r, p)
        p.shader = null

        // sulcos parados
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f.d().coerceAtLeast(1f)
        p.color = Color.argb(20, 255, 255, 255)
        var f = 0.46f
        while (f < 0.98f) { canvas.drawCircle(cx, cy, r * f, p); f += 0.055f }

        // rótulo parado
        p.style = Paint.Style.FILL
        p.color = corRotulo
        canvas.drawCircle(cx, cy, r * 0.32f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f.d().coerceAtLeast(1.5f)
        p.color = Color.argb(70, 255, 255, 255)
        canvas.drawCircle(cx, cy, r * 0.32f, p)
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#0C0E15")
        canvas.drawCircle(cx, cy, r * 0.035f, p)

        // o brilho: só ele gira
        canvas.save()
        canvas.rotate(angulo, cx, cy)
        p.shader = brilho
        canvas.drawCircle(cx, cy, r, p)
        p.shader = null
        canvas.restore()
    }
}
