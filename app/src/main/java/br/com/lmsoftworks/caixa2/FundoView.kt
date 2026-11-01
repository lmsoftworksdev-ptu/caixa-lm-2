package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Fundo animado da interface v3: 6 manchas redondas (raio 1,5x o do protótipo) de luz (cor 1 / cor 2 da tela, somadas) desenhadas num bitmap de
 * 128 x 72 (quadro de 320 x 180 com escala 0,4) e ampliadas para a tela com filtro bilinear. Por cima, o véu radial.
 * A cor caminha para o alvo a cada quadro: atual += (alvo - atual) * 0,045.
 */
class FundoView(c: Context) : View(c) {

    private val quadroL = 320f
    private val quadroA = 180f
    private val img = Bitmap.createBitmap(128, 72, Bitmap.Config.ARGB_8888)
    private val tela = Canvas(img)
    private val pMancha = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD); isDither = true }
    private val pImagem = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pVeu = Paint()
    private val origem = Rect(0, 0, 128, 72)
    private val destino = Rect()
    private val matriz = Matrix()

    // posições base (fração do quadro) e raios (no quadro de 320 x 180)
    private val bx = floatArrayOf(.15f, .42f, .70f, .90f, .28f, .58f)
    private val by = floatArrayOf(.30f, .70f, .28f, .72f, .88f, .10f)
    private val br = floatArrayOf(138f, 156f, 144f, 132f, 108f, 105f)

    private val atual = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f)   // r1 g1 b1 r2 g2 b2 brilho
    private val alvo = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f)
    private var iniciado = false
    private var shader1: Shader? = null
    private var shader2: Shader? = null
    private var cor1Shader = 0
    private var cor2Shader = 0
    private var rodando = false
    private val inicio = SystemClock.elapsedRealtime()

    private val passo = object : Runnable {
        override fun run() {
            if (!rodando) return
            invalidate()
            postDelayed(this, 1000L / Cfg.FPS)
        }
    }

    fun iniciar() {
        if (rodando) return
        rodando = true
        post(passo)
    }

    fun parar() { rodando = false }

    /** Define as duas cores da tela atual (e o brilho das manchas, só diferente de 1 no Início); o fundo caminha até elas. */
    fun tema(a: Int, b: Int, brilho: Float = 1f) {
        alvo[0] = Color.red(a).toFloat(); alvo[1] = Color.green(a).toFloat(); alvo[2] = Color.blue(a).toFloat()
        alvo[3] = Color.red(b).toFloat(); alvo[4] = Color.green(b).toFloat(); alvo[5] = Color.blue(b).toFloat()
        alvo[6] = brilho
        if (!iniciado) { System.arraycopy(alvo, 0, atual, 0, 7); iniciado = true }
    }

    private fun gradienteUnitario(cor: Int): Shader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(Color.argb(255, Color.red(cor), Color.green(cor), Color.blue(cor)),
            Color.argb(140, Color.red(cor), Color.green(cor), Color.blue(cor)), Color.argb(0, Color.red(cor), Color.green(cor), Color.blue(cor))),
        floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP
    )

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        destino.set(0, 0, w, h)
        val r = hypot(w.toFloat(), h.toFloat()) / 2f
        pVeu.shader = RadialGradient(
            w / 2f, h / 2f, r, Color.parseColor("#26050A18"), Color.parseColor("#B3050A18"), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (!iniciado) { canvas.drawColor(Tema.FUNDO); return }
        for (i in 0 until 7) atual[i] += (alvo[i] - atual[i]) * .045f
        val c1 = Color.rgb(atual[0].toInt(), atual[1].toInt(), atual[2].toInt())
        val c2 = Color.rgb(atual[3].toInt(), atual[4].toInt(), atual[5].toInt())
        if (shader1 == null || c1 != cor1Shader) { shader1 = gradienteUnitario(c1); cor1Shader = c1 }
        if (shader2 == null || c2 != cor2Shader) { shader2 = gradienteUnitario(c2); cor2Shader = c2 }
        val brilho = atual[6]
        val t = (SystemClock.elapsedRealtime() - inicio).toFloat()

        img.eraseColor(Tema.FUNDO)
        tela.save()
        tela.scale(.4f, .4f)
        for (i in 0 until 6) {
            val sp = 1f + (i % 4) * .15f
            val x = quadroL * bx[i] + sin(t * .00056f * sp + i * 1.9f) * quadroL * .15f
            val y = quadroA * by[i] + cos(t * .0007f * sp + i * 2.4f) * quadroA * .24f
            val raio = br[i] * (1f + .2f * sin(t * .001f + i * 1.3f))
            val alfa = ((.27f + .13f * sin(t * .0013f + i * 1.1f)) * brilho).coerceIn(0f, 1f)
            val sh = if (i % 2 == 0) shader1 else shader2
            matriz.setScale(raio, raio)
            matriz.postTranslate(x, y)
            sh!!.setLocalMatrix(matriz)
            pMancha.shader = sh
            pMancha.alpha = (alfa * 255f).toInt()
            tela.drawCircle(x, y, raio, pMancha)
        }
        tela.restore()

        canvas.drawBitmap(img, origem, destino, pImagem)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pVeu)
    }
}
