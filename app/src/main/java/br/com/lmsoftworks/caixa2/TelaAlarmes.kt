package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Tela cheia por cima de tudo quando o alarme toca: sino balançando, hora grande, nome e dois botões.
 */
class TelaAlarmeTocando(private val c: Context, private val aoSoneca: () -> Unit, private val aoParar: () -> Unit) : FrameLayout(c) {

    private val sino = icone(c, R.drawable.ic_sino, 110, Color.WHITE)
    private val hora = texto(c, "", 150, Color.WHITE, true)
    private val nome = texto(c, "", 36, Color.parseColor("#FFD2EA"))

    init {
        visibility = View.GONE
        isClickable = true
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#FF4FA3"), Color.parseColor("#4A1F9E"))
        )
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(sino)
            addView(hora, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.d() })
            addView(nome, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 40.d() })
            val bs = LinearLayout(c).apply {
                gravity = Gravity.CENTER
                // largura fixa nos dois: sem isso o primeiro botão ocupa a linha toda e o Parar fica espremido
                addView(botaoComIcone(R.drawable.ic_lua, "Soneca 5 min", Color.parseColor("#33FFFFFF")) { aoSoneca() },
                    LinearLayout.LayoutParams(360.d(), 84.d()).apply { rightMargin = 24.d() })
                addView(botaoComIcone(R.drawable.ic_parar, "Parar", Cfg.VERMELHO, comTexto = false) { aoParar() }, LinearLayout.LayoutParams(120.d(), 84.d()))      // só o X; "Parar" fica na descrição para leitores de tela
            }
            addView(bs, LinearLayout.LayoutParams(-2, -2))      // largura própria (sem isso herdava a do relógio e cortava os botões)
        }
        addView(col, LayoutParams(-2, -2, Gravity.CENTER))
        sino.pivotX = 55.d().toFloat(); sino.pivotY = 8.d().toFloat()
    }

    /** Botão grande com ícone à esquerda do texto. */
    private fun botaoComIcone(recurso: Int, rotulo: String, cor: Int, comTexto: Boolean = true, aoClicar: () -> Unit): View =
        LinearLayout(c).apply {
            gravity = Gravity.CENTER
            setPadding(24.d(), 0, 24.d(), 0)
            background = fundoArredondado(cor, 22.d(), if (cor == Cfg.LINHA) Cfg.LINHA else 0)
            isClickable = true
            setOnClickListener { aoClicar() }
            efeitoToque()
            contentDescription = rotulo
            addView(icone(c, recurso, if (comTexto) 38 else 48, Color.WHITE))
            if (comTexto) addView(texto(c, rotulo, 28, Color.WHITE, true), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 14.d() })
        }

    fun mostrar(a: Alarme) {
        hora.text = "%02d:%02d".format(a.hora, a.minuto)
        nome.text = a.nome.ifBlank { "Alarme" }
        visibility = View.VISIBLE
        sino.animate().cancel()
        balancar(true)
    }

    fun esconder() {
        sino.animate().cancel()
        visibility = View.GONE
    }

    private fun balancar(para: Boolean) {
        if (visibility != View.VISIBLE) return
        sino.animate().rotation(if (para) 18f else -18f).setDuration(260).withEndAction { balancar(!para) }.start()
    }
}
