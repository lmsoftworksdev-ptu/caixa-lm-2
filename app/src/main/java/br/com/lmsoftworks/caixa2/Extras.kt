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
 * Modo ocioso: tela preta com só a hora, grande, na fonte da tela inicial e em cinza-azulado escuro. A hora muda de lugar alguns pixels a
 * cada minuto, para não marcar a tela.
 */
class TelaOciosa(c: Context) : FrameLayout(c) {
    private val hora = texto(c, "", 210, Color.parseColor("#6F7A93")).apply {
        typeface = Tema.OUTFIT          // a mesma fonte da hora grande da tela inicial
        letterSpacing = -4f / 208f
        fontFeatureSettings = "tnum"
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
    /**
     * A cor que vai para a fita (Ambilight): a cor mais forte da capa como ela é, sem realce. O fundo da tela é que fica mais claro e
     * vivo; a fita tem que ser fiel à capa. Só sobe o brilho se a cor for tão escura que a fita não mostraria nada.
     */
    fun analisarFiel(capa: Bitmap): Int? {
        val c = analisar(capa, fiel = true) ?: return null
        val hsv = FloatArray(3)
        Color.colorToHSV(c.first, hsv)
        if (hsv[2] < 0.35f) hsv[2] = 0.35f
        return Color.HSVToColor(hsv)
    }

    fun analisar(capa: Bitmap): Pair<Int, Int>? = analisar(capa, fiel = false)

    private fun analisar(capa: Bitmap, fiel: Boolean): Pair<Int, Int>? {
        // Lê três pontos da capa: o centro e os cantos de cima (esquerdo e direito). Cada ponto é a média de um quadradinho.
        val lado = Math.min(capa.width, capa.height)
        val q = Math.max(2, lado / 7)
        fun ponto(fx: Float, fy: Float): Int {
            val cx = (capa.width * fx).toInt(); val cy = (capa.height * fy).toInt()
            var r = 0L; var g = 0L; var bl = 0L; var n = 0
            var y = cy - q / 2
            while (y < cy + q / 2) {
                var x = cx - q / 2
                while (x < cx + q / 2) {
                    val px = capa.getPixel(x.coerceIn(0, capa.width - 1), y.coerceIn(0, capa.height - 1))
                    r += Color.red(px); g += Color.green(px); bl += Color.blue(px); n++
                    x += 2
                }
                y += 2
            }
            return Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
        }
        val pontos = listOf(ponto(.5f, .5f), ponto(.2f, .2f), ponto(.8f, .2f))
        val hsv = FloatArray(3)
        // quanto mais saturada e clara, mais "forte" é a cor
        fun forca(c: Int): Float { Color.colorToHSV(c, hsv); return hsv[1] * Math.min(1f, hsv[2] * 1.4f) }
        val ordenados = pontos.sortedByDescending { forca(it) }
        if (forca(ordenados[0]) < 0.12f) return null          // capa quase toda cinza, preta ou branca
        val c1 = ordenados[0]
        Color.colorToHSV(c1, hsv); val h1 = hsv[0]; val s1 = hsv[1]
        // a segunda cor é a mais forte das outras que seja de OUTRO matiz; se todas forem do mesmo matiz (capa de uma cor só), o degradê
        // é do próprio matiz, mais fundo. Nunca se inventa um matiz que a capa não tem (antes o vermelho ganhava laranja).
        val outra = ordenados.drop(1).firstOrNull { c ->
            Color.colorToHSV(c, hsv)
            val dif = Math.min(Math.abs(hsv[0] - h1), 360f - Math.abs(hsv[0] - h1))
            forca(c) >= 0.18f && dif >= 22f
        }
        val c2 = if (outra != null) realcar(outra) else Color.HSVToColor(floatArrayOf(h1, Math.min(1f, s1.coerceAtLeast(0.62f) + 0.1f), 0.62f))
        if (fiel) return c1 to c1
        return realcar(c1) to c2
    }

    /** Deixa a cor viva e clara (o fundo antes ficava escuro demais): saturação e brilho sobem, mantendo o matiz da capa. */
    private fun realcar(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.62f)
        hsv[2] = hsv[2].coerceAtLeast(0.88f)
        return Color.HSVToColor(hsv)
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
 * Troca de tela em formato de caixa (cubo 3D): a tela de saída e a de destino são duas faces vizinhas de um cubo que gira.
 * [dir] = +1 voltar (a atual sai pela direita e a anterior entra pela esquerda), -1 avançar (ao contrário).
 * Cada face fica escura quando está de lado e some/aparece suavemente nas pontas, para não sobrar uma tirinha da outra tela.
 */
object Cubo {
    /** Ajuste do sentido da rotação no Android (testado por captura de tela). */
    private const val SINAL = 1f

    private val tintaA = Paint()
    private val tintaB = Paint()

    private fun suave(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /**
     * Cada face vira uma "camada de hardware": o conteúdo é desenhado UMA vez numa textura e o giro, a transparência e o
     * escurecimento são aplicados pela placa de vídeo, sem redesenhar a tela a cada quadro (era o que travava).
     */
    fun preparar(a: View, b: View) {
        for (v in listOf(a, b)) {
            v.visibility = View.VISIBLE
            v.cameraDistance = 8000f
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
    }

    private var nivelA = -1
    private var nivelB = -1

    private fun escurecer(v: View, tinta: Paint, quanto: Float) {
        // em degraus de ~3%: o filtro só é trocado quando o escurecimento muda de verdade (menos trabalho a cada quadro do giro)
        val nivel = (quanto * 32f).toInt().coerceIn(0, 32)
        val ultimo = if (tinta === tintaA) nivelA else nivelB
        if (ultimo == nivel) return
        if (tinta === tintaA) nivelA = nivel else nivelB = nivel
        tinta.colorFilter = PorterDuffColorFilter(Color.argb((255f * nivel / 32f).toInt().coerceIn(0, 255), 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
        v.setLayerPaint(tinta)
    }

    /**
     * [grau] de 0 a 90; [metade] = metade da largura da tela, em pixels. As duas faces giram em volta da PRÓPRIA ARESTA que têm em
     * comum (a da tela que sai e a da que entra ficam no mesmo ponto), então as bordas ficam coladas em qualquer ângulo e com qualquer
     * perspectiva. A aresta se move como a quina de um cubo de verdade: x = L/2 + dir·(L/2)·(sen − cos).
     */
    fun aplicar(a: View, b: View, grau: Float, dir: Int, metade: Float) {
        val g = grau.coerceIn(0f, 90f)
        val r = Math.toRadians(g.toDouble())
        val l = metade * 2f
        val aresta = metade + dir * metade * (sin(r) - cos(r)).toFloat()
        val f = min(1f, g / 90f)
        val h = a.height / 2f
        // a que sai: a aresta é a borda dela do lado para onde ela vai (esquerda se voltar, direita se avançar)
        a.pivotX = if (dir > 0) 0f else l; a.pivotY = h
        a.translationX = aresta - a.pivotX
        a.rotationY = SINAL * dir * g
        // a que entra: a aresta é a borda do lado de onde ela vem
        b.pivotX = if (dir > 0) l else 0f; b.pivotY = h
        b.translationX = aresta - b.pivotX
        b.rotationY = SINAL * dir * (g - 90f)
        // (sem translationZ de propósito: Z negativo fazia o fundo animado ser desenhado POR CIMA das telas, e elas sumiam no giro)
        a.alpha = 1f - suave((f - .72f) / .28f)        // a que sai some nos últimos 28% do giro
        b.alpha = suave(f / .22f)                       // a que entra só aparece depois de 22% do giro
        escurecer(a, tintaA, .55f * f)                  // a que sai escurece até 45% do brilho
        escurecer(b, tintaB, .55f * (1f - f))           // a que entra clareia de 45% a 100%
    }

    fun limpar(v: View) {
        v.translationX = 0f; v.translationZ = 0f; v.rotationY = 0f; v.alpha = 1f
        v.pivotX = v.width / 2f; v.pivotY = v.height / 2f
        v.setLayerPaint(null)
        nivelA = -1; nivelB = -1
        v.setLayerType(View.LAYER_TYPE_NONE, null)
    }
}
