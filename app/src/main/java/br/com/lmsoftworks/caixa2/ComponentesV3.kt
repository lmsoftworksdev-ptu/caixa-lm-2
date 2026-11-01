package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
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
