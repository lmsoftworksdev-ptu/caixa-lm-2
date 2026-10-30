package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Modo ocioso: tela preta com só a hora, grande, fina e em cinza-azulado escuro. A hora muda de lugar alguns pixels a
 * cada minuto, para não marcar a tela.
 */
class TelaOciosa(c: Context) : FrameLayout(c) {
    private val hora = texto(c, "", 210, Color.parseColor("#6F7A93")).apply {
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    }

    init {
        visibility = View.GONE
        setBackgroundColor(Color.BLACK)
        isClickable = true
        addView(hora, LayoutParams(-2, -2, Gravity.CENTER))
    }

    fun atualizar() {
        val cal = Calendar.getInstance()
        hora.text = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val m = cal.get(Calendar.MINUTE)
        hora.translationX = (((m * 37) % 61) - 30).d().toFloat()
        hora.translationY = (((m * 53) % 41) - 20).d().toFloat()
    }
}

/**
 * Cor predominante da capa do álbum. Reduz a capa a 32x32, ignora cinzas e tons muito escuros ou muito claros, agrupa
 * o resto em 12 faixas de matiz e fica com a faixa de maior saturação somada. Capa quase toda cinza, preta ou branca: null
 * (a tela usa o azul padrão). Devolve a cor principal (hsl(h, 82%, 48%)) e a secundária (hsl(h+35, 72%, 32%)).
 */
object CorCapa {
    fun analisar(capa: Bitmap): Pair<Int, Int>? {
        val p = Bitmap.createScaledBitmap(capa, 32, 32, true)
        val soma = FloatArray(12); val somaMatiz = FloatArray(12)
        val hsv = FloatArray(3)
        for (y in 0 until 32) for (x in 0 until 32) {
            Color.colorToHSV(p.getPixel(x, y), hsv)
            if (hsv[1] < 0.22f || hsv[2] < 0.2f || (hsv[2] > 0.95f && hsv[1] < 0.35f)) continue
            val faixa = (hsv[0] / 30f).toInt().coerceIn(0, 11)
            soma[faixa] += hsv[1]
            somaMatiz[faixa] += hsv[0] * hsv[1]
        }
        if (p !== capa) p.recycle()
        var melhor = 0
        for (i in 1..11) if (soma[i] > soma[melhor]) melhor = i
        if (soma[melhor] < 6f) return null       // quase toda cinza, preta ou branca
        val h = somaMatiz[melhor] / soma[melhor]
        return hsl(h, 0.82f, 0.48f) to hsl(h + 35f, 0.72f, 0.32f)
    }

    fun hsl(h0: Float, s: Float, l: Float): Int {
        val h = ((h0 % 360f) + 360f) % 360f
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color.rgb(((r + m) * 255f).toInt().coerceIn(0, 255), ((g + m) * 255f).toInt().coerceIn(0, 255), ((b + m) * 255f).toInt().coerceIn(0, 255))
    }
}

/**
 * Troca de tela da v2: deslize lateral. [dir] = -1 avançar (a tela nova entra da direita por cima e a antiga recua 30% para a
 * esquerda, escurecendo até 60%); +1 voltar (a atual sai pela direita e a anterior vem de -30%, clareando). O nome "Cubo" ficou
 * para não mexer nos chamadores.
 */
object Cubo {
    private val tintaA = Paint()
    private val tintaB = Paint()

    /** Cada tela vira uma "camada de hardware": o conteúdo é desenhado UMA vez numa textura e o deslize é feito pela placa de vídeo. */
    fun preparar(a: View, b: View) {
        for (v in listOf(a, b)) {
            v.visibility = View.VISIBLE
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
    }

    private fun escurecer(v: View, tinta: Paint, quanto: Float) {
        tinta.colorFilter = PorterDuffColorFilter(Color.argb((255f * quanto).toInt().coerceIn(0, 255), 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
        v.setLayerPaint(tinta)
    }

    /** [grau] de 0 a 90 (o progresso do deslize); [metade] = metade da largura da tela, em pixels. */
    fun aplicar(a: View, b: View, grau: Float, dir: Int, metade: Float) {
        val f = grau.coerceIn(0f, 90f) / 90f
        val l = metade * 2f
        a.alpha = 1f; b.alpha = 1f; a.rotationY = 0f; b.rotationY = 0f
        if (dir < 0) {
            a.translationX = -0.3f * l * f
            b.translationX = l * (1f - f)
            a.translationZ = 0f; b.translationZ = 4f
            escurecer(a, tintaA, .6f * f); escurecer(b, tintaB, 0f)
        } else {
            a.translationX = l * f
            b.translationX = -0.3f * l * (1f - f)
            a.translationZ = 4f; b.translationZ = 0f
            escurecer(a, tintaA, 0f); escurecer(b, tintaB, .6f * (1f - f))
        }
    }

    fun limpar(v: View) {
        v.translationX = 0f; v.translationZ = 0f; v.rotationY = 0f; v.alpha = 1f
        v.setLayerPaint(null)
        v.setLayerType(View.LAYER_TYPE_NONE, null)
    }
}
