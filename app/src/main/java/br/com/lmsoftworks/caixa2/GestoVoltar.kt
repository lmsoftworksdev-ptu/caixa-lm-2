package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Indicador do gesto de voltar: só uma barrinha discreta no canto esquerdo das telas internas. */
class GestoVoltar(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progresso = 0f      // 0 a 1 durante o arraste
    var ativo = false               // mostra a barrinha (telas internas)
        set(v) { field = v; invalidate() }

    fun arrastar(dx: Float) {
        progresso = (dx / DISTANCIA.d()).coerceIn(0f, 1f)
        invalidate()
    }

    fun soltar() {
        progresso = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!ativo) return
        val meio = height / 2f
        p.color = Color.argb(70, 255, 255, 255)
        canvas.drawRoundRect(0f, meio - 44.d(), 7.d().toFloat(), meio + 44.d(), 4.d().toFloat(), 4.d().toFloat(), p)
    }

    companion object { const val DISTANCIA = 150 }
}
