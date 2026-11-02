package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.sin

/**
 * Tela "Tocando em <celular>": o celular está tocando pelo Bluetooth mas não mandou o título nem a capa (o Bluetooth do Android daqui às
 * vezes recebe só parte dos dados do iPhone). Mostra o nome do aparelho, uma animação de música e os controles (anterior, play/pause,
 * próxima), com o fundo mudando de cor sozinho. Quem decide quando mostrar é a [TelaMusica].
 */
class PainelSemDados(private val c: Context, private val musica: MusicaControle, private val bt: BluetoothControle) : FrameLayout(c) {

    private val fundo = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.BLACK, Color.BLACK))
    private val rotulo = texto(c, "TOCANDO EM", 15, Color.parseColor("#D6E0F7"), true).apply { letterSpacing = 1.6f / 15f }
    private val nome = texto(c, "", 46, Tema.TEXTO, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val dica = texto(c, "O celular não mandou o título da música,\nmas o som está saindo por aqui.", 21, Tema.TEXTO2).apply { setLineSpacing(0f, 1.15f) }
    private val ondas = BarrasMusica(c)
    private val iconePlay = ImageView(c)
    private val botaoPlay = FrameLayout(c)
    private var tocandoDesenhado: Boolean? = null
    private var matizDesenhado = -1
    private var girando = false

    init {
        background = fundo
        isClickable = true      // não deixa o toque passar para o que está atrás

        val cartao = FrameLayout(c).apply { background = fundoArredondado(Color.parseColor("#22FFFFFF"), 40.d(), Tema.BOTAO_BORDA) }
        cartao.addView(ondas, LayoutParams(-1, -1).apply { setMargins(40.d(), 40.d(), 40.d(), 40.d()) })

        val coluna = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        coluna.addView(rotulo)
        coluna.addView(nome, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.d() })
        coluna.addView(dica, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 14.d() })

        val controles = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        controles.addView(botaoRedondo(R.drawable.ic_anterior, 76, 32, Color.parseColor("#26FFFFFF")) { musica.anterior() })
        botaoPlay.apply {
            background = fundoArredondado(Color.WHITE, 48.d())
            isClickable = true
            setOnClickListener { musica.alternar() }
            efeitoToque()
            addView(iconePlay, LayoutParams(44.d(), 44.d(), Gravity.CENTER))
        }
        iconePlay.setColorFilter(Color.parseColor("#0A1020"))
        controles.addView(botaoPlay, LinearLayout.LayoutParams(96.d(), 96.d()).apply { leftMargin = 28.d(); rightMargin = 28.d() })
        controles.addView(botaoRedondo(R.drawable.ic_proxima, 76, 32, Color.parseColor("#26FFFFFF")) { musica.proxima() })
        coluna.addView(controles, LinearLayout.LayoutParams(-2, 96.d()).apply { topMargin = 44.d() })

        val linha = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        linha.addView(cartao, LinearLayout.LayoutParams(368.d(), 368.d()))
        linha.addView(coluna, LinearLayout.LayoutParams(560.d(), -2).apply { leftMargin = 56.d() })
        addView(linha, LayoutParams(-2, -2, Gravity.CENTER))
    }

    private fun botaoRedondo(recurso: Int, tamanho: Int, tamIcone: Int, cor: Int, aoClicar: () -> Unit): View =
        FrameLayout(c).apply {
            background = fundoArredondado(cor, (tamanho / 2).d(), Tema.BOTAO_BORDA)
            isClickable = true
            setOnClickListener { aoClicar() }
            efeitoToque()
            addView(icone(c, recurso, tamIcone, Color.WHITE), LayoutParams(tamIcone.d(), tamIcone.d(), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(tamanho.d(), tamanho.d())
        }

    /** Chamada pela TelaMusica a cada meio segundo enquanto ela está aberta: atualiza o nome e liga a animação se ela parou. */
    fun atualizar() {
        val n = bt.nomeConectado() ?: "o celular"
        if (nome.text.toString() != n) nome.text = n
        if (!girando) { girando = true; postOnAnimation(passo) }
    }

    /** Um quadro da animação: o fundo troca de cor devagar (uma volta no círculo de cores a cada 90 s) e as barras mexem se está tocando. */
    private val passo = object : Runnable {
        override fun run() {
            if (!isShown) { girando = false; return }      // saiu da tela: para; o próximo atualizar() religa
            val tocando = bt.celularTocando
            if (tocando != tocandoDesenhado) {
                tocandoDesenhado = tocando
                iconePlay.setImageResource(if (tocando) R.drawable.ic_pausar else R.drawable.ic_tocar)
                ondas.tocando = tocando
            }
            val matiz = (SystemClock.uptimeMillis() / 250L % 360L).toInt()      // 1 grau a cada 250 ms
            if (matiz != matizDesenhado) {
                matizDesenhado = matiz
                val a = Color.HSVToColor(floatArrayOf(matiz.toFloat(), 0.62f, 0.36f))
                val b = Color.HSVToColor(floatArrayOf(((matiz + 70) % 360).toFloat(), 0.70f, 0.18f))
                fundo.colors = intArrayOf(a, b)
            }
            if (tocando) { ondas.invalidate(); postOnAnimation(this) } else postDelayed(this, 250)
        }
    }

    /** Barras de equalizador: sobem e descem sozinhas enquanto toca e ficam baixas quando está pausado. */
    private class BarrasMusica(c: Context) : View(c) {
        var tocando = false
            set(v) { field = v; invalidate() }
        private val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 235 }
        private val retangulo = RectF()
        private val n = 9
        private val velocidade = floatArrayOf(2.1f, 3.4f, 2.7f, 4.0f, 2.3f, 3.1f, 3.7f, 2.5f, 3.3f)

        override fun onDraw(canvas: Canvas) {
            val t = SystemClock.uptimeMillis() / 1000f
            val w = width.toFloat(); val h = height.toFloat()
            val larg = w / (n * 2 - 1)
            for (i in 0 until n) {
                val f = if (tocando) {
                    val a = 0.5f + 0.5f * sin(t * velocidade[i] + i * 1.7f)
                    val b = 0.5f + 0.5f * sin(t * (velocidade[i] * 0.43f) + i * 0.9f)
                    0.18f + 0.82f * (0.6f * a + 0.4f * b)
                } else 0.1f
                val x = i * 2 * larg
                retangulo.set(x, h * (1f - f), x + larg, h)
                canvas.drawRoundRect(retangulo, larg / 2, larg / 2, tinta)
            }
        }
    }
}
