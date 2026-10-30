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
        val puxando = progresso > 0f
        // alça: 6 x 100 px na borda esquerda (branco 20%); enquanto o dedo puxa, vira 20 px de largura e 55% de brilho
        val larg = (if (puxando) 20 else 6).d().toFloat()
        p.color = if (puxando) Color.argb(140, 255, 255, 255) else Color.argb(51, 255, 255, 255)
        canvas.drawRoundRect(-8.d().toFloat(), meio - 50.d(), larg, meio + 50.d(), 4.d().toFloat(), 4.d().toFloat(), p)
    }

    companion object { const val DISTANCIA = 150 }
}
