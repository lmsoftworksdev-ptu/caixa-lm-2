package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SweepGradient
import android.graphics.Paint
import android.graphics.RectF
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

    /** Troca as duas cores do fundo global (as bolas de luz que passam atrás de todas as telas); quem liga isso é a InicioActivity. */
    var aoCor: ((Int, Int) -> Unit)? = null
    private val rotulo = texto(c, "TOCANDO DE", 15, Color.parseColor("#D6E0F7"), true).apply { letterSpacing = 1.6f / 15f }
    private val nome = texto(c, "", 68, Tema.TEXTO, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val arte = ArteSemDados(c, 13, 40, 1, 40, colorida = false)      // na tela aberta: sem a borda colorida (ela é do cartão da tela inicial)
    private val iconePlay = ImageView(c)
    private val botaoPlay = FrameLayout(c)
    private var tocandoDesenhado: Boolean? = null
    private var matizDesenhado = -1
    private var girando = false
    private var esperado: Boolean? = null
    private var esperadoAte = 0L

    init {
        isClickable = true      // não deixa o toque passar para o que está atrás


        val coluna = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        coluna.addView(rotulo)
        coluna.addView(nome, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.d() })

        val controles = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        controles.addView(botaoRedondo(R.drawable.ic_anterior, 76, 32, Color.parseColor("#26FFFFFF")) { musica.anterior() })
        botaoPlay.apply {
            background = fundoArredondado(Color.WHITE, 48.d())
            isClickable = true
            setOnClickListener {
                esperado = !(tocandoDesenhado ?: false)      // o que vai passar a valer: o contrário do que está na tela
                esperadoAte = SystemClock.uptimeMillis() + 5000L
                musica.alternar()
                this@PainelSemDados.removeCallbacks(passo); girando = true; this@PainelSemDados.postOnAnimation(passo)      // redesenha já
            }
            efeitoToque()
            addView(iconePlay, LayoutParams(44.d(), 44.d(), Gravity.CENTER))
        }
        iconePlay.setColorFilter(Color.parseColor("#0A1020"))
        controles.addView(botaoPlay, LinearLayout.LayoutParams(96.d(), 96.d()).apply { leftMargin = 28.d(); rightMargin = 28.d() })
        controles.addView(botaoRedondo(R.drawable.ic_proxima, 76, 32, Color.parseColor("#26FFFFFF")) { musica.proxima() })

        addView(arte, LayoutParams(TAM_BANNER.d(), TAM_BANNER.d()).apply { leftMargin = 64.d(); topMargin = (BANNER_Y - 64).d() })
        addView(coluna, LayoutParams(1072.d() - COLUNA_X.d(), (CONTROLES_Y - BANNER_Y - 24).d()).apply { leftMargin = COLUNA_X.d(); topMargin = (BANNER_Y - 64).d() })      // na altura do cartão, com o nome no meio
        addView(controles, LayoutParams(-2, 96.d()).apply { leftMargin = COLUNA_X.d(); topMargin = (CONTROLES_Y - 64).d() })      // mesmo y dos controles e do Menu
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

    /** Um quadro da animação: o fundo global troca de cor devagar (uma volta no círculo de cores a cada 90 s) e as barras mexem se está tocando. */
    private val passo = object : Runnable {
        override fun run() {
            if (!isShown) { girando = false; return }      // saiu da tela: para; o próximo atualizar() religa
            val agora = SystemClock.uptimeMillis()
            val real = musica.celularTocandoAgora(bt.celularTocando)
            // acabou de apertar o botão: o ícone muda já, sem esperar o celular confirmar (o áudio demora uns 5 s)
            val desejado = esperado
            val tocando = if (desejado != null && agora < esperadoAte && real != desejado) desejado else { esperado = null; real }
            if (tocando != tocandoDesenhado) {
                tocandoDesenhado = tocando
                iconePlay.setImageResource(if (tocando) R.drawable.ic_pausar else R.drawable.ic_tocar)
                arte.tocando = tocando
            }
            val matiz = (SystemClock.uptimeMillis() / 250L % 360L).toInt()      // 1 grau a cada 250 ms
            if (matiz != matizDesenhado) {
                matizDesenhado = matiz
                val a = Color.HSVToColor(floatArrayOf(matiz.toFloat(), 0.76f, 1f))
                val b = Color.HSVToColor(floatArrayOf(((matiz + 40) % 360).toFloat(), 0.80f, 0.62f))
                aoCor?.invoke(a, b)
            }
            if (tocando) postOnAnimation(this) else postDelayed(this, 250)
        }
    }
}

/** Equalizador espelhado: as barras crescem para cima e para baixo a partir do meio e ficam só como pontos quando está pausado. */
class BarrasMusica(c: Context, private val n: Int = 13) : View(c) {
    var tocando = false
        set(v) { field = v; invalidate() }
    private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
    private val retangulo = RectF()
    private val velocidade = floatArrayOf(2.1f, 3.4f, 2.7f, 4.0f, 2.3f, 3.1f, 3.7f, 2.5f, 3.3f, 2.9f, 3.8f, 2.2f, 3.5f, 2.6f, 3.2f)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // mais transparente nas pontas e branco no meio
        tinta.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.argb(110, 255, 255, 255), Color.WHITE, Color.argb(110, 255, 255, 255)),
            floatArrayOf(0f, .5f, 1f), android.graphics.Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        val w = width.toFloat(); val h = height.toFloat()
        val larg = w / (n * 1.7f - 0.7f)
        val vao = larg * 0.7f
        val meio = h / 2f
        for (i in 0 until n) {
            // as barras do meio são as mais altas, como num espectro de verdade
            val centro = 1f - kotlin.math.abs(i - (n - 1) / 2f) / ((n - 1) / 2f) * 0.55f
            val f = if (tocando) {
                val a = 0.5f + 0.5f * sin(t * velocidade[i % velocidade.size] + i * 1.7f)
                val b = 0.5f + 0.5f * sin(t * (velocidade[i % velocidade.size] * 0.43f) + i * 0.9f)
                (0.10f + 0.90f * (0.6f * a + 0.4f * b)) * centro
            } else 0.02f
            val meia = (h / 2f * f).coerceAtLeast(larg / 2f)
            val x = i * (larg + vao)
            retangulo.set(x, meio - meia, x + larg, meio + meia)
            canvas.drawRoundRect(retangulo, larg / 2, larg / 2, tinta)
        }
        if (tocando) postInvalidateOnAnimation()      // enquanto toca e está na tela, redesenha sozinha
    }
}

/**
 * A arte do "celular tocando sem mandar os dados": fundo translúcido e o equalizador dentro. No cartão da tela inicial a borda é um degradê
 * colorido que gira devagar enquanto toca (e fica parado e mais fraco quando pausa); na tela "Tocando de ..." a borda é só um filete.
 */
class ArteSemDados(c: Context, quantidade: Int, private val raioDp: Int, bordaDp: Int, margemDp: Int, private val colorida: Boolean = true) : FrameLayout(c) {
    private val ondas = BarrasMusica(c, quantidade)
    private val fundo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#22FFFFFF") }
    private val borda = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = bordaDp.d().toFloat(); if (!colorida) color = Tema.BOTAO_BORDA }
    private val area = RectF()
    private val matriz = Matrix()
    private var degrade: SweepGradient? = null
    var tocando = false
        set(v) { field = v; ondas.tocando = v; invalidate() }

    init {
        setWillNotDraw(false)
        addView(ondas, LayoutParams(-1, -1).apply { setMargins(margemDp.d(), margemDp.d(), margemDp.d(), margemDp.d()) })
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (!colorida) return      // sem a borda colorida: só o filete discreto de sempre
        degrade = SweepGradient(w / 2f, h / 2f, intArrayOf(
            Color.parseColor("#FF4D8D"), Color.parseColor("#FF9A3D"), Color.parseColor("#FFE14D"), Color.parseColor("#4DE38A"),
            Color.parseColor("#3DB8FF"), Color.parseColor("#8A5CFF"), Color.parseColor("#FF4D8D")
        ), null)
        borda.shader = degrade
    }

    override fun onDraw(canvas: Canvas) {
        val r = raioDp.d().toFloat()
        val m = borda.strokeWidth / 2f
        area.set(m, m, width - m, height - m)
        canvas.drawRoundRect(area, r, r, fundo)
        if (!colorida) { canvas.drawRoundRect(area, r, r, borda); return }
        val angulo = if (tocando) (SystemClock.uptimeMillis() % 12_000L) / 12_000f * 360f else 0f      // uma volta a cada 12 s
        matriz.setRotate(angulo, width / 2f, height / 2f)
        degrade?.setLocalMatrix(matriz)
        borda.alpha = if (tocando) 255 else 150
        canvas.drawRoundRect(area, r, r, borda)
        if (tocando) postInvalidateOnAnimation()
    }
}
