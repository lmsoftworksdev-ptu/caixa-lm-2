package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Quadradinho da música que continua tocando quando a pessoa sai da tela Tocando agora (fica no canto de baixo do menu Mídia, como a
 * miniatura do vídeo): a capa, um botão de pausar/tocar e o "×" para esconder. Tocar na capa volta para Tocando agora.
 */
class MiniMusica(c: Context, aoAlternar: () -> Unit, aoOcultar: () -> Unit) : FrameLayout(c) {
    private val imagem = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val nota = icone(c, R.drawable.ic_music, 64, Color.argb(190, 255, 255, 255))
    private val estado = icone(c, R.drawable.ic_pausar, 24, Color.WHITE)
    private var semente = ""
    private var ultimaCapa: Bitmap? = null

    init {
        visibility = View.GONE
        contentDescription = "Voltar para Tocando agora"
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 18.d().toFloat()) }
        }
        addView(imagem, LayoutParams(-1, -1))
        addView(nota, LayoutParams(64.d(), 64.d(), Gravity.CENTER))

        val botaoEstado = FrameLayout(c).apply {
            background = fundoArredondado(0xDD141A26.toInt(), 22.d())
            contentDescription = "Pausar ou tocar"
            setOnClickListener { aoAlternar() }
            addView(estado, LayoutParams(24.d(), 24.d(), Gravity.CENTER))
        }
        addView(botaoEstado, LayoutParams(44.d(), 44.d(), Gravity.BOTTOM or Gravity.START).apply { leftMargin = 8.d(); bottomMargin = 8.d() })

        val fechar = texto(c, "×", 32, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = fundoArredondado(0xDD141A26.toInt(), 22.d())
            contentDescription = "Ocultar miniatura"
            setOnClickListener { aoOcultar() }
        }
        addView(fechar, LayoutParams(44.d(), 44.d(), Gravity.TOP or Gravity.END).apply { topMargin = 8.d(); rightMargin = 8.d() })
    }

    /** [capa] pode ser nula (sem imagem: degradê da faixa com a nota); [tocando] troca o ícone do botão. */
    fun definir(capa: Bitmap?, tocando: Boolean, chave: String) {
        if (chave != semente) {
            semente = chave
            val h = CapaView.matiz(chave)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(CorCapa.hsl(h, .82f, .48f), CorCapa.hsl(h + 35f, .72f, .32f)))
        }
        if (capa !== ultimaCapa || imagem.drawable == null && capa != null) { ultimaCapa = capa; imagem.setImageBitmap(capa) }
        nota.visibility = if (capa == null) View.VISIBLE else View.GONE
        estado.setImageResource(if (tocando) R.drawable.ic_pausar else R.drawable.ic_tocar)
    }
}
