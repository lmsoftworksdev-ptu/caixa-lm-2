package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Lista lateral "Próximas": o que vem depois do que está tocando (a fila, que o Mix do YouTube vai enchendo). Tocar numa linha pula
 * para ela. Serve para a tela da música e para o player de vídeo; [larguraMini] x [alturaMini] é o tamanho da miniatura de cada linha.
 */
class ListaProximas(
    private val c: Context,
    titulo: String,
    private val larguraMini: Int,
    private val alturaMini: Int,
    private val aoEscolher: (Int) -> Unit,
    private val aoFechar: () -> Unit
) : FrameLayout(c) {

    private val lista = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val rolagem = ScrollView(c).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(lista) }

    init {
        visibility = View.GONE
        isClickable = true      // o toque na lista não passa para o que está atrás
        background = fundoArredondado(Color.parseColor("#EB0A1020"), 28.d(), Tema.BOTAO_BORDA)
        val cabecalho = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(24.d(), 14.d(), 10.d(), 6.d()) }
        cabecalho.addView(texto(c, titulo, 24, Tema.TEXTO, true), LinearLayout.LayoutParams(0, -2, 1f))
        val fechar = FrameLayout(c).apply {
            isClickable = true; setOnClickListener { aoFechar() }; efeitoToque()
            addView(icone(c, R.drawable.ic_fechar, 36, Color.WHITE), LayoutParams(36.d(), 36.d(), Gravity.CENTER))
        }
        cabecalho.addView(fechar, LinearLayout.LayoutParams(76.d(), 76.d()))
        val coluna = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        coluna.addView(cabecalho)
        coluna.addView(rolagem, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(coluna, LayoutParams(-1, -1))
    }

    private var x0 = 0f
    private var y0 = 0f
    private var arrastando = false

    /** Arrastar a lista para a direita fecha: o gesto mais rápido que o X (a lista acompanha o dedo e some). */
    override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; arrastando = false }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - x0; val dy = e.rawY - y0
                if (dx > 36.d() && dx > Math.abs(dy) * 1.5f) { arrastando = true; return true }
            }
        }
        return false
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!arrastando) return true
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> translationX = Math.max(0f, e.rawX - x0)
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                val fechar = e.actionMasked == android.view.MotionEvent.ACTION_UP && e.rawX - x0 > 130.d()
                arrastando = false
                if (fechar) { translationX = 0f; aoFechar() } else animate().translationX(0f).setDuration(160).start()
            }
        }
        return true
    }

    private var itemAtual = ""      // índice|endereço do que toca: quando muda, a lista rola até ele
    private var tamanho = -1

    /**
     * Mostra a fila inteira ao redor do que toca (até 30 anteriores e 40 próximas), com o atual destacado. Quando o item que toca muda,
     * a lista rola sozinha até ele (os anteriores continuam ali, para voltar a um deles); quando só a fila cresce, fica onde a pessoa estava.
     */
    fun atualizar(fila: List<Resultado>, indice: Int) {
        val item = indice.toString() + "|" + (fila.getOrNull(indice)?.url ?: "")
        if (item == itemAtual && fila.size == tamanho) return
        val mudouItem = item != itemAtual
        itemAtual = item; tamanho = fila.size
        val y = rolagem.scrollY
        lista.removeAllViews()
        val de = maxOf(indice - 30, 0)
        for (i in de until minOf(fila.size, maxOf(indice, 0) + 41)) lista.addView(linha(fila[i], i, i == indice))
        if (mudouItem) {
            // rola até o atual, deixando o anterior à vista logo acima
            rolagem.post { lista.getChildAt(indice - de)?.let { rolagem.smoothScrollTo(0, maxOf(it.top - 92.d(), 0)) } }
        } else rolagem.post { rolagem.scrollTo(0, y) }
    }

    private fun linha(r: Resultado, i: Int, atual: Boolean): View = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(18.d(), 8.d(), 14.d(), 8.d())
        if (atual) background = fundoArredondado(Color.parseColor("#26FFFFFF"), 16.d())
        isClickable = true
        setOnClickListener { if (!atual) aoEscolher(i) }
        efeitoToque()
        val imagem = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = fundoArredondado(Color.parseColor("#22FFFFFF"), 10.d()) }
        Miniaturas.carregar(r.miniaturas, imagem, 240)
        addView(imagem, LinearLayout.LayoutParams(larguraMini.d(), alturaMini.d()))
        val textos = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(texto(c, r.titulo, 18, Color.WHITE, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        val sub = r.canal.removeSuffix(" - Topic")      // o que toca agora só fica destacado (sem texto)
        textos.addView(texto(c, sub, 15, if (atual) Cfg.AZUL else Tema.TEXTO3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 2.d() })
        addView(textos, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 14.d() })
    }
}
