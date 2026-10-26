package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.os.SystemClock
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.Log
import android.view.View
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Fundo animado: faixas largas de luz desfocadas cruzando o centro da tela. As cores mudam suavemente conforme o menu ([tema]). O desfoque vem de desenhar em
 * uma imagem pequena e esticá-la com filtro, o que é leve para o celular.
 */
class FundoView(c: Context) : View(c) {

    // ----- Parâmetros ajustáveis -----
    private val larguraImagem = 256       // tamanho da imagem pequena (quanto menor, mais desfocado)
    private val alturaImagem = 144
    private val faixas = 6
    private val raioDesfoque = 7f         // desfoque real aplicado na imagem pequena (1 a 25)
    private val suavidadeCor = 0.045f     // quanto a cor se aproxima da cor alvo a cada quadro de 60 fps
    private val saturacao = 1.35f
    private val velocidadeBase = 0.32f
    private val corVeu1 = Color.parseColor("#66050C1F")
    private val corVeu2 = Color.parseColor("#B3050C1F")

    private val imagem = Bitmap.createBitmap(larguraImagem, alturaImagem, Bitmap.Config.ARGB_8888)
    private val tela = Canvas(imagem)
    private val pFaixa = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val pBase = Paint()
    private val pImagem = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturacao) })
    }
    private val pVeu = Paint()
    // desfoque de verdade, feito pelo RenderScript do Android (rápido e sem biblioteca extra)
    private var rs: RenderScript? = null
    private var blur: ScriptIntrinsicBlur? = null
    private var entrada: Allocation? = null
    private var saida: Allocation? = null
    private val imagemDesfocada = Bitmap.createBitmap(larguraImagem, alturaImagem, Bitmap.Config.ARGB_8888)
    private var desfocou = false

    init {
        try {
            val r = RenderScript.create(c.applicationContext)
            rs = r
            blur = ScriptIntrinsicBlur.create(r, Element.U8_4(r)).apply { setRadius(raioDesfoque) }
            entrada = Allocation.createFromBitmap(r, imagem)
            saida = Allocation.createFromBitmap(r, imagemDesfocada)
            blur!!.setInput(entrada)
        } catch (e: Exception) {
            Log.w("Fundo", "sem RenderScript, fundo sem desfoque extra", e)
            blur = null
        }
    }

    private fun desfocar() {
        val b = blur
        desfocou = false
        if (b != null) {
            try {
                entrada!!.copyFrom(imagem)
                b.forEach(saida)
                saida!!.copyTo(imagemDesfocada)
                desfocou = true
            } catch (e: Exception) {
                Log.w("Fundo", "falha no desfoque", e)
                blur = null
            }
        }
    }

    private val caminho = Path()
    private val destino = Rect()

    private var alvo1 = floatArrayOf(11f, 59f, 156f)
    private var alvo2 = floatArrayOf(6f, 33f, 79f)
    private val cor1 = alvo1.clone()
    private val cor2 = alvo2.clone()
    private var gradientes: Array<LinearGradient>? = null
    private var chaveGradientes = -1
    private var ultimo = SystemClock.uptimeMillis()
    private val inicio = ultimo
    private var rodando = false

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
        ultimo = SystemClock.uptimeMillis()
        post(passo)
    }

    fun parar() { rodando = false }

    /** Define as duas cores do menu atual; o fundo caminha até elas aos poucos. */
    fun tema(a: Int, b: Int) {
        alvo1 = rgb(a)
        alvo2 = rgb(b)
    }

    private fun rgb(c: Int) = floatArrayOf(Color.red(c).toFloat(), Color.green(c).toFloat(), Color.blue(c).toFloat())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val raio = w * 0.9f
        val veu = RadialGradient(w / 2f, h / 2f, raio, corVeu1, corVeu2, Shader.TileMode.CLAMP)
        veu.setLocalMatrix(Matrix().apply { setScale(1f, (h * 0.8f) / raio, w / 2f, h / 2f) })
        pVeu.shader = veu
        destino.set((-0.06f * w).toInt(), (-0.06f * h).toInt(), (1.06f * w).toInt(), (1.06f * h).toInt())
    }

    private fun aproximar(atual: FloatArray, alvo: FloatArray, f: Float) {
        for (i in 0..2) atual[i] += (alvo[i] - atual[i]) * f
    }

    private fun montarGradientes(p1: IntArray, p2: IntArray) {
        val chave = (p1[0] shl 21) xor (p1[1] shl 14) xor (p1[2] shl 7) xor (p2[0] shl 11) xor (p2[1] shl 3) xor p2[2]
        if (chave == chaveGradientes && gradientes != null) return
        chaveGradientes = chave
        gradientes = Array(faixas) { k ->
            val m = if (k % 2 == 1) p2 else p1
            val n = if (k % 2 == 1) p1 else p2
            LinearGradient(
                0f, 0f, 640f, 0f,
                intArrayOf(
                    Color.argb(0, m[0], m[1], m[2]), Color.argb(107, m[0], m[1], m[2]),
                    Color.argb(102, n[0], n[1], n[2]), Color.argb(0, n[0], n[1], n[2])
                ),
                floatArrayOf(0f, .25f, .6f, 1f), Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val agora = SystemClock.uptimeMillis()
        val dt = ((agora - ultimo) / 1000f).coerceIn(0f, 0.2f)
        ultimo = agora
        val t = (agora - inicio) / 1000f
        val f = 1f - (1f - suavidadeCor).pow(dt * 60f)
        aproximar(cor1, alvo1, f)
        aproximar(cor2, alvo2, f)
        val p1 = intArrayOf(cor1[0].toInt(), cor1[1].toInt(), cor1[2].toInt())
        val p2 = intArrayOf(cor2[0].toInt(), cor2[1].toInt(), cor2[2].toInt())
        montarGradientes(p1, p2)

        // ----- imagem pequena (projeto de 640 x 360) -----
        tela.save()
        tela.scale(larguraImagem / 640f, alturaImagem / 360f)
        pBase.color = Color.rgb((p1[0] * .08f + 4).toInt(), (p1[1] * .08f + 8).toInt(), (p1[2] * .1f + 22).toInt())
        tela.drawRect(0f, 0f, 640f, 360f, pBase)
        for (k in 0 until faixas) {
            val amp = 34f + k * 9f
            val fase = k * 1.3f
            val vel = velocidadeBase + k * .07f
            pFaixa.strokeWidth = 54f + k * 12f
            pFaixa.shader = gradientes!![k]
            caminho.reset()
            var x = -20f
            while (x <= 660f) {
                val y = 180f + (k - 2.5f) * 14f +
                    sin(x * .011f + t * vel + fase) * amp +
                    sin(x * .023f - t * vel * 1.4f + fase * 2) * amp * .45f
                if (x == -20f) caminho.moveTo(x, y) else caminho.lineTo(x, y)
                x += 10f
            }
            tela.drawPath(caminho, pFaixa)
        }
        tela.restore()

        // ----- esticada na tela (isso é o desfoque) + véu escuro para o texto ficar legível -----
        desfocar()
        canvas.drawBitmap(if (desfocou) imagemDesfocada else imagem, null, destino, pImagem)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pVeu)
    }
}
