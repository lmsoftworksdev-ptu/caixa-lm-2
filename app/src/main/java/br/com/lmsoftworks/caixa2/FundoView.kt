package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.max

/**
 * Fundo animado da interface v2: o degradê de manchas à deriva. Duas camadas, cada uma com duas manchas elípticas de cor
 * (centro na cor -> transparente), MAIORES que a tela (1,5x) e movidas bem devagar (deslocamento, escala e rotação, ida e
 * volta). Sem desfoque: o suave vem dos degradês. Cada camada é um Bitmap pequeno, redesenhado só enquanto as cores trocam
 * (transição de 1,4 s), e esticado na tela. As cores [tema] (g0, g1) mudam por menu, pela capa da música ou pela fita.
 */
class FundoView(c: Context) : View(c) {

    private val ESCALA_ANTIGA = 1.5f
    private val ESCALA_CAMADA = 1.8f
    private val base = Color.parseColor("#0A0612")
    private val largura = 288                  // cada camada é desenhada em 288 x 162 e esticada para 1,5x a tela
    private val altura = 162
    private val imgA = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
    private val imgB = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
    private val telaA = Canvas(imgA)
    private val telaB = Canvas(imgB)
    private val pMancha = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pImagem = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pVeu = Paint()
    private val pMascara = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val destino = RectF()
    private val matriz = Matrix()

    private var cor0 = Color.parseColor("#2A62FF")
    private var cor1 = Color.parseColor("#7A3CFF")
    private var de0 = cor0
    private var de1 = cor1
    private var para0 = cor0
    private var para1 = cor1
    private var inicioTroca = 0L
    private var desenhadoCom0 = 0
    private var desenhadoCom1 = 0
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

    /** Define as duas cores do menu atual; o fundo caminha até elas em 1,4 s. */
    fun tema(a: Int, b: Int) {
        if (a == para0 && b == para1) return
        de0 = cor0; de1 = cor1
        para0 = a; para1 = b
        inicioTroca = SystemClock.uptimeMillis()
    }

    private fun mistura(a: Int, b: Int, f: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
    )

    /** Uma mancha elíptica: [cx],[cy] e [rx],[ry] em fração da camada; da cor ao transparente em [fim] do raio. */
    private fun mancha(tela: Canvas, cx0: Float, cy0: Float, rx0: Float, ry0: Float, cor: Int, fim: Float) {
        // as manchas foram desenhadas para uma camada de 1,5x a tela; a camada agora é de 1,8x (cobre a tela em qualquer ponto do
        // movimento, sem cantos vazios): o tamanho e a posição das manchas, em pixels da tela, continuam os mesmos
        val k = ESCALA_ANTIGA / ESCALA_CAMADA
        val cx = .5f + (cx0 - .5f) * k; val cy = .5f + (cy0 - .5f) * k
        val rx = rx0 * k; val ry = ry0 * k
        val raio = rx * largura
        val sh = RadialGradient(0f, 0f, raio, intArrayOf(cor, Color.argb(0, Color.red(cor), Color.green(cor), Color.blue(cor))), floatArrayOf(0f, fim), Shader.TileMode.CLAMP)
        matriz.reset()
        matriz.setScale(1f, (ry * altura) / raio)
        matriz.postTranslate(cx * largura, cy * altura)
        sh.setLocalMatrix(matriz)
        pMancha.shader = sh
        tela.drawRect(0f, 0f, largura.toFloat(), altura.toFloat(), pMancha)
    }

    private fun desenharCamadas() {
        imgA.eraseColor(Color.TRANSPARENT); imgB.eraseColor(Color.TRANSPARENT)
        // camada A (a 1ª mancha da lista fica por cima: desenha-se a 2ª primeiro)
        mancha(telaA, .80f, .72f, .60f, .70f, cor1, .72f)
        mancha(telaA, .28f, .38f, .55f, .65f, cor0, .72f)
        // camada B
        mancha(telaB, .20f, .85f, .50f, .60f, cor0, .70f)
        mancha(telaB, .70f, .25f, .45f, .55f, cor1, .70f)
        suavizarBordas(telaA); suavizarBordas(telaB)
        desenhadoCom0 = cor0; desenhadoCom1 = cor1
    }

    /** As bordas de cada camada somem aos poucos (14%): quando ela se move e gira, a beirada nunca aparece como um corte reto. */
    private fun suavizarBordas(tela: Canvas) {
        val f = .14f
        val cores = intArrayOf(0, -1, -1, 0)
        val pos = floatArrayOf(0f, f, 1f - f, 1f)
        pMascara.shader = LinearGradient(0f, 0f, largura.toFloat(), 0f, cores, pos, Shader.TileMode.CLAMP)
        tela.drawRect(0f, 0f, largura.toFloat(), altura.toFloat(), pMascara)
        pMascara.shader = LinearGradient(0f, 0f, 0f, altura.toFloat(), cores, pos, Shader.TileMode.CLAMP)
        tela.drawRect(0f, 0f, largura.toFloat(), altura.toFloat(), pMascara)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        pVeu.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.argb(46, 5, 6, 14), Color.argb(87, 5, 6, 14), Shader.TileMode.CLAMP)
    }

    private fun suave(x: Float) = x * x * (3f - 2f * x)

    /** Camada que vai e volta (meio ciclo = [meio] s) entre dois estados de deslocamento, escala e giro. */
    private fun camada(canvas: Canvas, img: Bitmap, t: Float, meio: Float, x0: Float, y0: Float, e0: Float, g0: Float, x1: Float, y1: Float, e1: Float, g1: Float, alfa: Int) {
        val ciclo = t % (2f * meio)
        val u = if (ciclo < meio) ciclo / meio else 2f - ciclo / meio
        val k = suave(u)
        val lw = width * ESCALA_CAMADA; val lh = height * ESCALA_CAMADA
        canvas.save()
        // o deslocamento é em % de uma camada de 1,5x (como no protótipo)
        canvas.translate(width / 2f + (x0 + (x1 - x0) * k) * width * ESCALA_ANTIGA, height / 2f + (y0 + (y1 - y0) * k) * height * ESCALA_ANTIGA)
        val s = e0 + (e1 - e0) * k
        canvas.scale(s, s)
        canvas.rotate(g0 + (g1 - g0) * k)
        destino.set(-lw / 2f, -lh / 2f, lw / 2f, lh / 2f)
        pImagem.alpha = alfa
        canvas.drawBitmap(img, null, destino, pImagem)
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        // transição de cor de 1,4 s
        val f = if (inicioTroca == 0L) 1f else ((SystemClock.uptimeMillis() - inicioTroca) / 1400f).coerceIn(0f, 1f)
        if (f < 1f || cor0 != para0 || cor1 != para1) { cor0 = mistura(de0, para0, suave(f)); cor1 = mistura(de1, para1, suave(f)) }
        if (cor0 != desenhadoCom0 || cor1 != desenhadoCom1) desenharCamadas()
        val t = (SystemClock.elapsedRealtime() - inicio) / 1000f
        canvas.drawColor(base)
        camada(canvas, imgA, t, 12f, -.16f, -.12f, 1.0f, -14f, .18f, .14f, 1.35f, 16f, 255)
        camada(canvas, imgB, t, 15f, .18f, .12f, 1.3f, 14f, -.18f, -.14f, 1.0f, -16f, 217)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pVeu)
    }
}
