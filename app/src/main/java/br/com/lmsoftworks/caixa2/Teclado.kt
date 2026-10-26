package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Teclado na tela para digitar a senha do Wi-Fi: letras, números e símbolos. */
class TecladoView(c: Context) : LinearLayout(c) {
    var aoDigitar: ((String) -> Unit)? = null
    var aoApagar: (() -> Unit)? = null

    private var maiuscula = false
    private var simbolos = false

    init {
        orientation = VERTICAL
        montar()
    }

    private fun tecla(rotulo: String, peso: Float, tamanho: Int = 26, acao: () -> Unit): TextView =
        TextView(context).apply {
            text = rotulo
            setTextColor(Cfg.TEXTO)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, tamanho.d().toFloat())
            typeface = FONTE_NORMAL
            gravity = Gravity.CENTER
            background = fundoArredondado(Color.parseColor("#18FFFFFF"), 12.d(), Cfg.LINHA)
            isClickable = true
            setOnClickListener { acao() }
            efeitoToque()
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, peso).apply {
                setMargins(4.d(), 0, 4.d(), 0)
            }
        }

    private fun linha(vararg itens: TextView, laterais: Float = 0f): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            if (laterais > 0f) addView(android.view.View(context), LayoutParams(0, 1, laterais))
            itens.forEach { addView(it) }
            if (laterais > 0f) addView(android.view.View(context), LayoutParams(0, 1, laterais))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, 4.d(), 0, 4.d()) }
        }

    private fun caracteres(s: String, peso: Float = 1f): Array<TextView> =
        s.map { ch ->
            val mostrado = if (!simbolos && maiuscula) ch.uppercaseChar() else ch
            tecla(mostrado.toString(), peso) {
                aoDigitar?.invoke(mostrado.toString())
                if (maiuscula) { maiuscula = false; montar() }
            }
        }.toTypedArray()

    private fun montar() {
        removeAllViews()
        if (simbolos) {
            addView(linha(*caracteres("!@#$%^&*()")))
            addView(linha(*caracteres("-_=+[]{};:")))
            addView(linha(*caracteres("'\",.<>/?\\|")))
            addView(linha(*caracteres("~`"), laterais = 4f))
        } else {
            addView(linha(*caracteres("1234567890")))
            addView(linha(*caracteres("qwertyuiop")))
            addView(linha(*caracteres("asdfghjkl"), laterais = .5f))
            val maius = tecla("Aa", 1.5f) { maiuscula = !maiuscula; montar() }
            if (maiuscula) maius.background = fundoArredondado(Cfg.AZUL, 12.d())
            val apagar = tecla("⌫", 1.5f) { aoApagar?.invoke() }
            addView(linha(maius, *caracteres("zxcvbnm"), apagar))
        }
        val modo = tecla(if (simbolos) "abc" else "#+=", 1.5f, 22) { simbolos = !simbolos; montar() }
        val espaco = tecla("", 5f) { aoDigitar?.invoke(" ") }
        val ponto = tecla(".", 1f) { aoDigitar?.invoke(".") }
        addView(linha(modo, espaco, ponto))
    }
}
