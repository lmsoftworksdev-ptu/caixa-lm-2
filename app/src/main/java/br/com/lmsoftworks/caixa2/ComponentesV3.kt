package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Rótulo de seção da v3: 15, MAIÚSCULAS, espaçamento 1,6, cor terciária. */
fun rotuloSecao(c: Context, s: String): TextView =
    texto(c, s.uppercase(), 15, Tema.TEXTO3, true).apply {
        letterSpacing = 1.6f / 15f
        setPadding(18.d(), 16.d(), 18.d(), 8.d())
    }

/** Círculo com ícone e anel suave em volta (hero das telas Wi-Fi e Bluetooth): círculo de [tam], anel de 12 a mais. */
class AnelIcone(c: Context, private val recurso: Int, private val tam: Int, private val cor: Int, private val fundo: Int, private val anel: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val figura = c.getDrawable(recurso)!!.mutate()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((tam + 24).d(), (tam + 24).d())

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        if (anel != 0) { p.color = anel; canvas.drawCircle(cx, cy, cx, p) }
        p.color = fundo
        canvas.drawCircle(cx, cy, tam.d() / 2f, p)
        val t = (tam * .48f).d().toInt()
        figura.setBounds((cx - t / 2).toInt(), (cy - t / 2).toInt(), (cx + t / 2).toInt(), (cy + t / 2).toInt())
        figura.setTint(cor)
        figura.draw(canvas)
    }
}

/** Janela (modal) da v3: scrim escuro, cartão #0E1733 com borda e raio 32, abre em 300 ms (escala 0,96 -> 1 + opacidade). */
class ModalV3(private val c: Context) : FrameLayout(c) {
    val aberto: Boolean get() = visibility == View.VISIBLE

    init {
        visibility = View.GONE
        isClickable = true
        setBackgroundColor(Tema.SCRIM)
        setOnClickListener { fechar() }
    }

    fun mostrar(largura: Int, conteudo: View) {
        removeAllViews()
        val cartao = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(34.d(), 34.d(), 34.d(), 34.d())
            background = fundoArredondado(Tema.MODAL, Tema.RAIO_MODAL.d(), Tema.MODAL_BORDA)
            isClickable = true
            addView(conteudo, LinearLayout.LayoutParams(-1, -2))
        }
        addView(cartao, LayoutParams(largura.d(), -2, Gravity.CENTER))
        if (!aberto) {
            visibility = View.VISIBLE
            alpha = 0f
            cartao.scaleX = .96f; cartao.scaleY = .96f
            animate().alpha(1f).setDuration(Tema.MODAL_MS).setInterpolator(Tema.CURVA).start()
            cartao.animate().scaleX(1f).scaleY(1f).setDuration(Tema.MODAL_MS).setInterpolator(Tema.CURVA).start()
        }
    }

    fun fechar() {
        if (!aberto) return
        animate().cancel()
        visibility = View.GONE
        removeAllViews()
    }
}

/** Linha "chave  valor" dos detalhes (rótulo terciário 21, valor 21). */
fun linhaDetalhe(c: Context, rotulo: String, valor: String): LinearLayout = LinearLayout(c).apply {
    addView(texto(c, rotulo, 21, Tema.TEXTO3), LinearLayout.LayoutParams(190.d(), -2))
    addView(texto(c, valor, 21, Tema.TEXTO), LinearLayout.LayoutParams(0, -2, 1f))
    setPadding(4.d(), 6.d(), 4.d(), 6.d())
}

/** ScrollView da v3: sem barra de rolagem (a tela é de toque: basta deslizar) e sem o brilho de fim de rolagem. */
class RolagemFina(c: Context) : ScrollView(c) {
    init { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
}

/**
 * Aviso (toast) da v3: pílula no centro de baixo (44 da borda) com o texto em 24 medium; sobe 20 px com fade em 250 ms, fica 1,6 s
 * (mais um pouco se o texto for longo) e some. Fica por cima de tudo.
 */
class AvisoView(c: Context) : FrameLayout(c) {
    private val texto = texto(c, "", 24, Tema.TEXTO, true).apply { maxLines = 2; gravity = Gravity.CENTER }
    private val ocultar = Runnable { animate().alpha(0f).translationY(20.d().toFloat()).setDuration(250).withEndAction { visibility = View.GONE }.start() }

    init {
        visibility = View.GONE
        isClickable = false
        val pilula = LinearLayout(c).apply {
            gravity = Gravity.CENTER
            setPadding(32.d(), 18.d(), 32.d(), 18.d())
            background = fundoArredondado(Color.parseColor("#F00E1733"), 34.d(), Tema.MODAL_BORDA)
            addView(texto)
        }
        addView(pilula, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 44.d(); leftMargin = 60.d(); rightMargin = 60.d() })
    }

    fun mostrar(msg: String) {
        texto.text = msg
        removeCallbacks(ocultar)
        animate().cancel()
        visibility = View.VISIBLE
        alpha = 0f; translationY = 20.d().toFloat()
        animate().alpha(1f).translationY(0f).setDuration(250).setInterpolator(Tema.CURVA).start()
        postDelayed(ocultar, 1600L + (msg.length * 25L).coerceAtMost(1800L))
    }
}

/** Ponto único para avisar: usa o aviso da v3 e, se a tela ainda não existe, cai no Toast do Android. */
object Aviso {
    @Volatile var tela: AvisoView? = null

    fun mostrar(ctx: Context, msg: String) {
        val t = tela
        if (t != null) t.post { t.mostrar(msg) } else android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
