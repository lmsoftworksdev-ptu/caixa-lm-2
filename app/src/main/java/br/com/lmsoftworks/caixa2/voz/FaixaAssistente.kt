package br.com.lmsoftworks.caixa2.voz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import br.com.lmsoftworks.caixa2.Cfg
import br.com.lmsoftworks.caixa2.d
import br.com.lmsoftworks.caixa2.texto
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Esfera colorida (4 manchas de luz que se mexem; mexem mais forte quando a caixa ouve ou pensa). */
class EsferaAssistente(c: Context, private val nivel: () -> Float) : View(c) {
    var modo = 0          // 0 parada, 1 ouvindo, 2 pensando/falando
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cores = intArrayOf(0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF375F.toInt(), 0xFF64D2FF.toInt())
    private val fases = floatArrayOf(0f, 1.6f, 3.2f, 4.8f)

    override fun onDraw(canvas: Canvas) {
        val t = android.os.SystemClock.uptimeMillis() / 1000f
        val amp = when (modo) {
            1 -> 0.35f + 0.65f * (0.4f * abs(sin(t * 8) * sin(t * 3.1f)) + 0.6f * nivel().coerceIn(0f, 1f))
            2 -> 0.4f + 0.1f * sin(t * 6)
            else -> 0.25f
        }
        val s = width / 220f
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
        for (i in 0 until 4) {
            val sp = if (modo == 1) 2.4f else 1f
            val r = (44 + amp * 20) * s
            val x = width / 2f + cos(t * sp * .9f + fases[i]) * (14 + amp * 20) * s
            val y = height / 2f + sin(t * sp * 1.1f + fases[i] * 1.3f) * (14 + amp * 20) * s
            p.shader = RadialGradient(x, y, r, (cores[i] and 0x00FFFFFF) or 0xDD000000.toInt(), cores[i] and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, r, p)
        }
        p.shader = null; p.xfermode = null
        postInvalidateOnAnimation()
    }
}

/**
 * O assistente, no estilo da Siri: ao chamar, o resto da tela fica desfocado e escurecido e só a esfera colorida aparece, no meio.
 * Enquanto a caixa responde, o texto da resposta aparece em letras discretas embaixo da esfera. Tocar na tela interrompe tudo.
 * [alvo] é a parte da tela que será desfocada (uma foto bem pequena dela, esticada de volta, dá o desfoque).
 */
class FaixaAssistente(c: Context, nivel: () -> Float, private val alvo: () -> View) : FrameLayout(c) {
    private val fundo = ImageView(c)
    private val escurecer = View(c)
    private val esfera = EsferaAssistente(c, nivel)
    private val legenda = texto(c, "", 30, Color.WHITE, true)
    private val fechar = Runnable { esconder() }

    /** Tocou na tela: quem usa interrompe o assistente. */
    var aoToque: (() -> Unit)? = null

    val aberta: Boolean get() = visibility == View.VISIBLE

    init {
        visibility = View.GONE
        isClickable = true
        setOnClickListener { aoToque?.invoke() }
        fundo.scaleType = ImageView.ScaleType.FIT_XY
        addView(fundo, LayoutParams(-1, -1))
        escurecer.setBackgroundColor(Color.parseColor("#B8050A1C"))
        addView(escurecer, LayoutParams(-1, -1))
        addView(esfera, LayoutParams(400.d(), 400.d(), Gravity.CENTER).apply { bottomMargin = 60.d() })
        legenda.gravity = Gravity.CENTER
        legenda.maxLines = 3; legenda.ellipsize = TextUtils.TruncateAt.END
        legenda.setShadowLayer(12.d().toFloat(), 0f, 2.d().toFloat(), Color.BLACK)
        addView(legenda, LayoutParams(940.d(), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 70.d() })
    }

    /** Foto bem pequena da tela, esticada: fica desfocada. */
    private fun desfocar() {
        try {
            val v = alvo()
            if (v.width <= 0 || v.height <= 0) return
            val pequena = Bitmap.createBitmap(v.width / 24, v.height / 24, Bitmap.Config.RGB_565)
            val cv = Canvas(pequena)
            cv.scale(1f / 24f, 1f / 24f)
            v.draw(cv)
            val media = Bitmap.createScaledBitmap(pequena, v.width / 6, v.height / 6, true)
            fundo.setImageBitmap(media)
        } catch (e: Throwable) { fundo.setImageDrawable(null) }
    }

    private fun subir() {
        removeCallbacks(fechar)
        if (aberta) { animate().cancel(); alpha = 1f }
        if (!aberta) {
            desfocar()
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(260).start()
        }
    }

    fun ouvindo(dito: String = "") {
        subir()
        esfera.modo = 1
        legenda.text = ""
    }

    fun pensando(dito: String) {
        subir()
        esfera.modo = 2
        legenda.text = ""
    }

    fun respondendo(resposta: String, dito: String = "") {
        subir()
        esfera.modo = 2
        legenda.text = resposta
    }

    /** Fecha sozinha depois de [ms]. */
    fun fecharEm(ms: Long) { removeCallbacks(fechar); postDelayed(fechar, ms) }

    fun esconder() {
        removeCallbacks(fechar)
        if (!aberta) return
        animate().cancel()
        animate().alpha(0f).setDuration(260).withEndAction { visibility = View.GONE; esfera.modo = 0; fundo.setImageDrawable(null); alpha = 1f }.start()
    }
}
