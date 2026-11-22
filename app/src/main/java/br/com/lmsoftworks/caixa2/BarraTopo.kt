package br.com.lmsoftworks.caixa2

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Calendar

/** Botão redondo com um ícone dentro (e, se pedir, um círculo girando no lugar do ícone). Usado em telas ainda não redesenhadas. */
class BotaoRedondo(c: Context, recurso: Int, private val descricao: String, tamIcone: Int = 33) : FrameLayout(c) {
    private val icone = ImageView(c).apply { setImageResource(recurso) }
    private val girando = ProgressBar(c).apply { isIndeterminate = true; visibility = View.GONE }

    init {
        isClickable = true
        efeitoToque()
        addView(icone, LayoutParams(tamIcone.d(), tamIcone.d(), Gravity.CENTER))
        addView(girando, LayoutParams(tamIcone.d(), tamIcone.d(), Gravity.CENTER))
        contentDescription = descricao
    }

    fun trocarIcone(recurso: Int) = icone.setImageResource(recurso)

    /** [cor] do ícone e do círculo (o fundo fica levemente tingido dessa cor quando [avermelhado]). */
    fun pintar(cor: Int, avermelhado: Boolean) {
        icone.setColorFilter(cor)
        girando.indeterminateTintList = ColorStateList.valueOf(cor)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (avermelhado) Color.argb(0x1F, 0xFF, 0x3B, 0x4E) else Color.argb(0x12, 255, 255, 255))
            setStroke(1.d().coerceAtLeast(1), Cfg.LINHA)
        }
    }

    fun carregando(v: Boolean) {
        girando.visibility = if (v) View.VISIBLE else View.GONE
        icone.visibility = if (v) View.GONE else View.VISIBLE
    }

    fun conexao(conectado: Boolean) {
        pintar(if (conectado) AZUL_OK else Cfg.VERMELHO, !conectado)
        contentDescription = descricao + ": " + if (conectado) "conectado" else "desconectado"
    }

    companion object { val AZUL_OK = Color.parseColor("#3DA5FF") }
}

/** A barra diagonal que "corta" um ícone sem conexão: da mesma cor do ícone. */
class CorteView(c: Context) : View(c) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val m = width * .1f
        p.color = Color.WHITE; p.strokeWidth = 3.d().toFloat()      // só a barra, na cor do ícone (sem contorno)
        canvas.drawLine(m, m, width - m, height - m, p)
    }
}

/**
 * Ícone da barra de status da v2: só o desenho (32 px), sem círculo, numa área de toque de 56 x 56. Conectado = branco;
 * sem conexão = branco com uma barra diagonal cortando o ícone; desligado pelo usuário = branco a 45%.
 */
class IconeStatus(c: Context, recurso: Int, private val descricao: String, tam: Int = 32) : FrameLayout(c) {
    private val icone = ImageView(c).apply { setImageResource(recurso) }
    private val corte = CorteView(c).apply { visibility = View.GONE }

    init {
        isClickable = true
        efeitoToque()
        contentDescription = descricao
        addView(icone, LayoutParams(tam.d(), tam.d(), Gravity.CENTER))
        addView(corte, LayoutParams(tam.d(), tam.d(), Gravity.CENTER))
    }

    fun trocarIcone(recurso: Int) = icone.setImageResource(recurso)

    fun estado(cor: Int, cortado: Boolean, alfa: Float = 1f, texto: String = "") {
        icone.setColorFilter(cor)
        icone.alpha = alfa
        corte.visibility = if (cortado) View.VISIBLE else View.GONE
        if (texto.isNotEmpty()) contentDescription = "$descricao: $texto"
    }
}

