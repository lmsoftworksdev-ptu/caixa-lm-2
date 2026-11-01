package br.com.lmsoftworks.caixa2

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Tela cheia por cima de tudo quando o alarme toca (v3): fundo radial roxo-escuro, sino balançando, hora grande, nome, o botão
 * "Soneca 5 min" e o X vermelho para parar.
 */
class TelaAlarmeTocando(private val c: Context, private val aoSoneca: () -> Unit, private val aoParar: () -> Unit) : FrameLayout(c) {

    private val sino = icone(c, R.drawable.ic_sino, 110, Color.parseColor("#D9C8FF"))
    private val hora = texto(c, "", 150, Color.WHITE).apply { typeface = FONTE_LEVE; fontFeatureSettings = "tnum" }
    private val nome = texto(c, "", 36, Color.parseColor("#D9C8FF"))
    private var balanco: ValueAnimator? = null

    init {
        visibility = View.GONE
        isClickable = true
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(Color.parseColor("#3E2390"), Color.parseColor("#1C1150"), Color.parseColor("#070516"))
            gradientRadius = 760.d().toFloat()
            setGradientCenter(.5f, .45f)
        }
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(sino)
            addView(hora, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.d() })
            addView(nome, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 44.d() })
            val bs = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                // "Soneca 5 min": pílula translúcida de 380 x 88; o X vermelho redondo de 88 pára o alarme
                addView(botaoSoneca(), LinearLayout.LayoutParams(380.d(), 88.d()).apply { rightMargin = 24.d() })
                addView(botaoParar(), LinearLayout.LayoutParams(88.d(), 88.d()))
            }
            addView(bs, LinearLayout.LayoutParams(-2, -2))
        }
        addView(col, LayoutParams(-2, -2, Gravity.CENTER))
        sino.pivotX = 55.d().toFloat(); sino.pivotY = 9.d().toFloat()      // gira em torno de 8% do topo
    }

    private fun botaoSoneca(): View = LinearLayout(c).apply {
        gravity = Gravity.CENTER
        background = fundoArredondado(Color.parseColor("#24FFFFFF"), 44.d(), Color.parseColor("#2EFFFFFF"))
        isClickable = true
        contentDescription = "Soneca de 5 minutos"
        setOnClickListener { aoSoneca() }
        efeitoToque()
        addView(icone(c, R.drawable.ic_lua, 36, Color.WHITE), LinearLayout.LayoutParams(36.d(), 36.d()))
        addView(texto(c, "Soneca 5 min", 28, Color.WHITE, true), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 14.d() })
    }

    private fun botaoParar(): View = FrameLayout(c).apply {
        background = fundoArredondado(Tema.ERRO, 44.d())
        isClickable = true
        contentDescription = "Parar o alarme"
        setOnClickListener { aoParar() }
        efeitoToque()
        addView(icone(c, R.drawable.ic_fechar, 40, Color.WHITE), LayoutParams(40.d(), 40.d(), Gravity.CENTER))
    }

    fun mostrar(a: Alarme) {
        hora.text = "%02d:%02d".format(a.hora, a.minuto)
        nome.text = a.nome.ifBlank { "Alarme" }
        visibility = View.VISIBLE
        balanco?.cancel()
        // balança de -14° a +14° (um vaivém por segundo)
        balanco = ValueAnimator.ofFloat(-14f, 14f).apply {
            duration = 500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { sino.rotation = it.animatedValue as Float }
            start()
        }
    }

    fun esconder() {
        balanco?.cancel(); balanco = null
        sino.rotation = 0f
        visibility = View.GONE
    }
}
