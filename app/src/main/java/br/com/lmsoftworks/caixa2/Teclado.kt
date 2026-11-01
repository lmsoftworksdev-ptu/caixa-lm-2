package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Teclado único da v3 (Mídia, senha do Wi-Fi, lembretes e listas): teclas de 100 x 80 (raio 18, texto 30), 10 de espaço;
 * linhas de números, qwertyuiop, asdfghjklç, ⇧ zxcvbnm ⌫ e, embaixo, "?123" (150), espaço (560) e a ação (220, no destaque).
 */
class TecladoView(c: Context) : LinearLayout(c) {
    var aoDigitar: ((String) -> Unit)? = null
    var aoApagar: (() -> Unit)? = null
    /** Toque na tecla de ação (Buscar, Conectar, Salvar...). */
    var aoAcao: (() -> Unit)? = null

    /** Rótulo da tecla de ação. */
    var acao: String = "OK"
        set(v) { field = v; montar() }

    private var maiuscula = false
    private var simbolos = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        montar()
    }

    /** Liga o ⇧ (a próxima letra sai maiúscula): usado quando o texto está vazio. */
    fun capitalizar(sim: Boolean) { if (maiuscula != sim) { maiuscula = sim; montar() } }

    private fun tecla(rotulo: String, largura: Int, destaque: Boolean = false, ativa: Boolean = false, tamanho: Int = 30, acao: () -> Unit): TextView =
        TextView(context).apply {
            text = rotulo
            setTextColor(Tema.TEXTO)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, tamanho.d().toFloat())
            typeface = if (destaque) FONTE_MEDIA else FONTE_NORMAL
            gravity = Gravity.CENTER
            val normal = fundoArredondado(if (destaque || ativa) Cfg.AZUL else Color.parseColor("#1AFFFFFF"), 18.d())
            val premida = fundoArredondado(if (destaque || ativa) Cfg.AZUL else Color.parseColor("#47FFFFFF"), 18.d())
            background = normal
            isClickable = true
            setOnClickListener { acao() }
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> v.background = premida
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.background = normal
                }
                false
            }
            layoutParams = LayoutParams(largura.d(), 80.d()).apply { setMargins(5.d(), 0, 5.d(), 0) }
        }

    private fun linha(vararg itens: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            itens.forEach { addView(it) }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 5.d(); bottomMargin = 5.d() }
        }

    private fun caracteres(s: String): Array<View> =
        s.map { ch ->
            val mostrado = if (!simbolos && maiuscula) ch.uppercaseChar() else ch
            tecla(mostrado.toString(), 100) {
                aoDigitar?.invoke(mostrado.toString())
                if (maiuscula) { maiuscula = false; montar() }
            } as View
        }.toTypedArray()

    private fun montar() {
        removeAllViews()
        val apagar = tecla("⌫", 150) { aoApagar?.invoke() }
        if (simbolos) {
            addView(linha(*caracteres("1234567890")))
            addView(linha(*caracteres("!@#$%^&*()")))
            addView(linha(*caracteres("-_=+[]{}:;")))
            addView(linha(*caracteres("'\"<>,.?/"), apagar))
        } else {
            addView(linha(*caracteres("1234567890")))
            addView(linha(*caracteres("qwertyuiop")))
            addView(linha(*caracteres("asdfghjklç")))
            val shift = tecla("⇧", 150, ativa = maiuscula) { maiuscula = !maiuscula; montar() }
            addView(linha(shift, *caracteres("zxcvbnm"), apagar))
        }
        val modo = tecla(if (simbolos) "abc" else "?123", 150, tamanho = 26) { simbolos = !simbolos; montar() }
        val espaco = tecla("", 560) { aoDigitar?.invoke(" ") }
        val acaoTecla = tecla(acao, 220, destaque = true, tamanho = 28) { aoAcao?.invoke() }
        addView(linha(modo, espaco, acaoTecla))
    }
}
