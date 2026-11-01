package br.com.lmsoftworks.caixa2

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tela Configurações (v2): título grande à esquerda e, à direita, a lista (sem cartão) com Wi-Fi, Bluetooth e Alarmes, cada um
 * com o estado atual embaixo do nome. Tocar abre a tela correspondente; o "voltar" de lá volta para esta.
 */
class TelaConfig(private val c: Context, private val aoAbrir: (String) -> Unit) : FrameLayout(c) {

    private class Linha(val id: String, val nome: String, val icone: Int) {
        lateinit var estado: TextView
    }

    private val linhas = listOf(
        Linha("wifi", "Wi-Fi", R.drawable.ic_wifi),
        Linha("bluetooth", "Bluetooth", R.drawable.ic_bluetooth),
        Linha("alarmes", "Alarmes", R.drawable.ic_alarme)
    )

    init {
        val titulo = texto(c, "Configurações", 62, Cfg.TX).apply { typeface = FONTE_LEVE; letterSpacing = -0.03f }
        val sub = texto(c, "Rede, Bluetooth e alarmes da caixa", 25, Cfg.TX2)
        val esquerda = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(titulo)
            addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 18.d() })
        }
        addView(esquerda, LayoutParams(420.d(), -2).apply { leftMargin = 64.d(); topMargin = 120.d() })

        val lista = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        for (l in linhas) lista.addView(linha(l), LinearLayout.LayoutParams(-1, -2))
        val rolagem = ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(lista) }
        addView(rolagem, LayoutParams(-1, -1).apply { leftMargin = 520.d(); rightMargin = 64.d(); topMargin = 96.d(); bottomMargin = 40.d() })
    }

    private fun linha(l: Linha): View {
        val nome = texto(c, l.nome, 30, Cfg.TX)
        l.estado = texto(c, "", 20, Cfg.TX_MUDO)
        val textos = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(nome)
            addView(l.estado, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 3.d() })
        }
        val corpo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.d(), 0, 8.d(), 0)
            isClickable = true
            addView(icone(c, l.icone, 40, Cfg.TX2), LinearLayout.LayoutParams(40.d(), 40.d()).apply { rightMargin = 24.d() })
            addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
            addView(icone(c, R.drawable.ic_seta_direita, 30, Cfg.TX_MUDO), LinearLayout.LayoutParams(30.d(), 30.d()))
            setOnClickListener { aoAbrir(l.id) }
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> v.setBackgroundColor(Cfg.SUPERFICIE)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.setBackgroundColor(0)
                }
                false
            }
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(corpo, LinearLayout.LayoutParams(-1, 96.d()))
            addView(View(c).apply { setBackgroundColor(Cfg.DIVISOR) }, LinearLayout.LayoutParams(-1, 1.d().coerceAtLeast(1)))
        }
    }

    /** Atualiza o estado de cada item ("Conectado a ...", "Desligado"...). */
    fun definirEstado(id: String, texto: String) {
        linhas.firstOrNull { it.id == id }?.estado?.text = texto
    }
}
