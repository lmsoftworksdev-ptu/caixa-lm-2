package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Peças pequenas e reutilizáveis da interface. */

val FONTE_NORMAL: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
val FONTE_MEDIA: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

fun fundoArredondado(cor: Int, raio: Int, borda: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(cor)
        cornerRadius = raio.toFloat()
        if (borda != 0) setStroke(1.d().coerceAtLeast(1), borda)
    }

fun gradiente(c1: Int, c2: Int, raio: Int): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply {
        cornerRadius = raio.toFloat()
        setStroke(1.d().coerceAtLeast(1), Cfg.LINHA)
    }

fun texto(c: Context, s: String, tamanho: Int, cor: Int, media: Boolean = false): TextView =
    TextView(c).apply {
        text = s
        setTextColor(cor)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, tamanho.d().toFloat())
        typeface = if (media) FONTE_MEDIA else FONTE_NORMAL
        includeFontPadding = false
    }

fun icone(c: Context, recurso: Int, tamanho: Int, cor: Int): ImageView =
    ImageView(c).apply {
        setImageResource(recurso)
        setColorFilter(cor)
        layoutParams = LinearLayout.LayoutParams(tamanho.d(), tamanho.d())
    }

/** Faz o botão encolher um pouco enquanto o dedo está em cima. */
fun View.efeitoToque() {
    setOnTouchListener { v, e ->
        when (e.action) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(.96f).scaleY(.96f).setDuration(80).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
        }
        false
    }
}

fun botao(c: Context, rotulo: String, cor: Int, aoClicar: () -> Unit): TextView =
    TextView(c).apply {
        text = rotulo
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 22.d().toFloat())
        typeface = FONTE_NORMAL
        gravity = Gravity.CENTER
        setPadding(28.d(), 14.d(), 28.d(), 14.d())
        background = fundoArredondado(cor, 16.d(), if (cor == Cfg.LINHA) Cfg.LINHA else 0)
        isClickable = true
        setOnClickListener { aoClicar() }
        efeitoToque()
    }

/** Interruptor liga/desliga (64 x 34 no projeto). */
class Interruptor(c: Context) : View(c) {
    var ligado = true
        set(v) { field = v; invalidate() }
    var aoMudar: ((Boolean) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init { setOnClickListener { aoMudar?.invoke(!ligado) } }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(64.d(), 34.d())

    override fun onDraw(canvas: Canvas) {
        val a = height.toFloat()
        p.color = if (ligado) Cfg.AZUL else Color.parseColor("#2AFFFFFF")
        canvas.drawRoundRect(0f, 0f, width.toFloat(), a, a / 2, a / 2, p)
        p.color = Color.WHITE
        val cx = if (ligado) width - 17.d() else 17.d()
        canvas.drawCircle(cx.toFloat(), a / 2, 13.d().toFloat(), p)
    }
}

/** Quatro barrinhas de força do sinal (0 a 4). */
class BarrasSinal(c: Context) : View(c) {
    var nivel = 0
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val alturas = intArrayOf(8, 12, 17, 22)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(32.d(), 22.d())

    override fun onDraw(canvas: Canvas) {
        for (i in 0 until 4) {
            p.color = if (i < nivel) Cfg.AZUL_CLARO else Color.parseColor("#33FFFFFF")
            val x = (i * 8).d().toFloat()
            val topo = (height - alturas[i].d()).toFloat()
            canvas.drawRoundRect(x, topo, x + 5.d(), height.toFloat(), 2.d().toFloat(), 2.d().toFloat(), p)
        }
    }
}

/**
 * Botão da tela Início: só o ícone branco dentro de um quadrado arredondado (128 x 128 no projeto),
 * com degradê diagonal, brilho suave no topo e sombra embaixo.
 */
class ChipIcone(c: Context, private val recurso: Int, private val cor1: Int, private val cor2: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val figura = c.getDrawable(recurso)?.mutate()
    private val area = RectF()
    private var sombra: Bitmap? = null
    private var degrade: LinearGradient? = null
    private var brilho: LinearGradient? = null
    private var escurecer: LinearGradient? = null

    init { isClickable = true; efeitoToqueChip() }

    private fun efeitoToqueChip() {
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(.94f).scaleY(.94f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
            }
            false
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(128.d(), 128.d())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        area.set(0f, 0f, w.toFloat(), h.toFloat())
        val raio = 38.d().toFloat()
        // sombra desfocada: desenhada uma vez numa imagem
        val folga = 60.d()
        val img = Bitmap.createBitmap(w + 2 * folga, h + 2 * folga, Bitmap.Config.ARGB_8888)
        val cv = Canvas(img)
        val ps = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(170, 0, 0, 0)
            maskFilter = BlurMaskFilter(19.d().toFloat(), BlurMaskFilter.Blur.NORMAL)
        }
        cv.drawRoundRect(RectF(folga.toFloat(), folga + 18.d().toFloat(), folga + w.toFloat(), folga + h + 18.d().toFloat()), raio, raio, ps)
        sombra = img
        degrade = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), cor1, cor2, Shader.TileMode.CLAMP)
        brilho = LinearGradient(0f, 0f, 0f, h * .45f, Color.argb(102, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        escurecer = LinearGradient(0f, h * .55f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(51, 0, 0, 0), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val raio = 38.d().toFloat()
        val folga = 60.d()
        sombra?.let { canvas.drawBitmap(it, -folga.toFloat(), -folga.toFloat(), null) }
        p.shader = degrade
        canvas.drawRoundRect(area, raio, raio, p)
        p.shader = escurecer
        canvas.drawRoundRect(area, raio, raio, p)
        p.shader = brilho                       // fio de luz no topo
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f.d().coerceAtLeast(1f)
        canvas.drawRoundRect(RectF(area.left + .75f, area.top + .75f, area.right - .75f, area.bottom - .75f), raio, raio, p)
        p.style = Paint.Style.FILL
        p.shader = null
        figura?.let {
            val t = 66.d()
            val cx = width / 2
            val cy = height / 2
            it.setBounds(cx - t / 2, cy - t / 2, cx + t / 2, cy + t / 2)
            it.setTint(Color.WHITE)
            it.draw(canvas)
        }
    }
}
