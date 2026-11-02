package br.com.lmsoftworks.caixa2

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar

/** "Conectando a Fulano": cobre a tela enquanto a caixa termina de preparar a conexão Bluetooth com o celular. */
class ModalConexao(private val c: Context) : FrameLayout(c) {
    private val titulo = texto(c, "", 34, Color.WHITE, true)
    private val detalhe = texto(c, "Preparando a conexão…", 21, Color.parseColor("#D6E0F7"))

    val aberto: Boolean get() = visibility == View.VISIBLE

    init {
        visibility = View.GONE
        setBackgroundColor(Tema.SCRIM)
        isClickable = true
        val cartao = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = fundoArredondado(Tema.MODAL, Tema.RAIO_MODAL.d(), Tema.MODAL_BORDA)
            setPadding(48.d(), 40.d(), 48.d(), 38.d())
        }
        cartao.addView(icone(c, R.drawable.ic_bluetooth, 64, Color.WHITE), LinearLayout.LayoutParams(64.d(), 64.d()))
        titulo.gravity = Gravity.CENTER
        cartao.addView(titulo, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 20.d() })
        cartao.addView(detalhe, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 8.d() })
        val giro = ProgressBar(c).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        cartao.addView(giro, LinearLayout.LayoutParams(44.d(), 44.d()).apply { topMargin = 26.d() })
        addView(cartao, LayoutParams(620.d(), -2, Gravity.CENTER))
    }

    fun mostrar(nome: String) {
        titulo.text = "Conectando a $nome"
        visibility = View.VISIBLE
    }

    fun fechar() { visibility = View.GONE }
}
