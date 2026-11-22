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

    private val botaoBaixar = FrameLayout(c)
    private val iconeBaixar = icone(c, R.drawable.ic_download, 34, Color.WHITE)
    private val painelProgresso = LinearLayout(c)
    private val textoProgresso = texto(c, "", 16, Tema.TEXTO2)
    private val trilho = FrameLayout(c)
    private val preenchimento = View(c)

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
        // botão de download (só aparece quando quem usa a lista define [aoBaixar]: a tela da música)
        botaoBaixar.apply {
            visibility = View.GONE
            isClickable = true; setOnClickListener { if (!jaBaixado && Downloads.andamento == null) aoBaixar?.invoke() }; efeitoToque()
            addView(iconeBaixar, LayoutParams(34.d(), 34.d(), Gravity.CENTER))
        }
        cabecalho.addView(botaoBaixar, LinearLayout.LayoutParams(76.d(), 76.d()))
        cabecalho.addView(fechar, LinearLayout.LayoutParams(76.d(), 76.d()))
        // progresso do download do Mix: logo abaixo do título, na parte de cima da lista
        trilho.background = fundoArredondado(Color.parseColor("#33FFFFFF"), 4.d())
        preenchimento.background = fundoArredondado(Cfg.AZUL, 4.d())
        trilho.addView(preenchimento, LayoutParams(0, -1))
        painelProgresso.apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            // área maior e com fundo leve: a tela é pequena e tocar nela abre a aba Downloads
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 88.d()
            background = fundoArredondado(Color.parseColor("#1AFFFFFF"), 18.d())
            setPadding(24.d(), 14.d(), 24.d(), 14.d())
            addView(textoProgresso)
            addView(trilho, LinearLayout.LayoutParams(-1, 8.d()).apply { topMargin = 6.d() })
        }
        val coluna = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        coluna.addView(cabecalho)
        coluna.addView(painelProgresso, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = 12.d(); rightMargin = 12.d(); bottomMargin = 8.d() })
        coluna.addView(rolagem, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(coluna, LayoutParams(-1, -1))
    }

    /** Toque na faixa do download em andamento (título e barra): abre a aba Downloads. Null = a faixa não é tocável. */
    var aoAbrirDownloads: (() -> Unit)? = null
        set(v) { field = v; painelProgresso.isClickable = v != null; painelProgresso.setOnClickListener(if (v != null) View.OnClickListener { v.invoke() } else null) }

    /** Quem trata o toque no botão de download (null = o botão não aparece). */
    var aoBaixar: (() -> Unit)? = null
        set(v) { field = v; botaoBaixar.visibility = if (v != null) View.VISIBLE else View.GONE }
    /** Esta lista já foi baixada (ou é a de um Mix baixado): o botão fica verde e bloqueado, para não baixar o mesmo Mix duas vezes. */
    var jaBaixado = false
        set(v) { if (field != v) { field = v; mostrarProgresso() } }

    private val ouvinteDownload: () -> Unit = { mostrarProgresso() }
    private val esconderAviso = Runnable { mostrarProgresso() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Downloads.ouvir(ouvinteDownload); mostrarProgresso() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); Downloads.desouvir(ouvinteDownload) }

    /** A barra de progresso (e o percentual) do download em andamento; no fim, a mensagem "Mix salvo" por uns segundos. */
    private fun mostrarProgresso() {
        removeCallbacks(esconderAviso)
        val a = Downloads.andamento
        iconeBaixar.alpha = if (a != null) 0.35f else 1f
        iconeBaixar.setColorFilter(if (jaBaixado && a == null) Tema.OK else Color.WHITE)      // verde = já está nos downloads
        if (a != null) {
            painelProgresso.visibility = View.VISIBLE
            val pct = (a.fracao * 100).toInt().coerceIn(0, 100)
            textoProgresso.text = "Baixando ${a.nome} · $pct% (${a.concluidas} de ${a.total})"
            textoProgresso.setTextColor(Tema.TEXTO2)
            trilho.visibility = View.VISIBLE
            trilho.post { preenchimento.layoutParams = (preenchimento.layoutParams as LayoutParams).apply { width = (trilho.width * a.fracao).toInt() }; preenchimento.requestLayout() }
            return
        }
        val aviso = Downloads.aviso
        val restante = 5_000L - (System.currentTimeMillis() - Downloads.avisoEm)
        if (aviso != null && restante > 0) {
            painelProgresso.visibility = View.VISIBLE
            textoProgresso.text = aviso
            textoProgresso.setTextColor(Tema.OK)
            trilho.visibility = View.GONE      // a barra só aparece enquanto baixa; no fim fica só a mensagem
            postDelayed(esconderAviso, restante)
        } else painelProgresso.visibility = View.GONE
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
    private var identidadeFila = 0

    /**
     * Mostra a fila inteira ao redor do que toca (até 30 anteriores e 40 próximas), com o atual destacado. Quando o item que toca muda,
     * a lista rola sozinha até ele (os anteriores continuam ali, para voltar a um deles); quando só a fila cresce, fica onde a pessoa estava.
     */
    fun atualizar(fila: List<Resultado>, indice: Int) {
        val item = indice.toString() + "|" + (fila.getOrNull(indice)?.url ?: "")
        // a fila é trocada por uma lista nova a cada mudança: o endereço dela entra na comparação (só o item e o tamanho podiam coincidir com os de uma fila antiga)
        val identidade = System.identityHashCode(fila)
        if (item == itemAtual && fila.size == tamanho && identidade == identidadeFila) return
        val mudouItem = item != itemAtual
        android.util.Log.d("FilaDiag", "lista Próximas: indice=$indice, ${fila.size} itens, 1º='${fila.firstOrNull()?.titulo}', atual='${fila.getOrNull(indice)?.titulo}'")
        itemAtual = item; tamanho = fila.size; identidadeFila = identidade
        val y = rolagem.scrollY
        lista.removeAllViews()
        // só uma janela perto do que toca (5 antes e 30 depois): 70 linhas de uma vez travavam a tela ao abrir (cada uma cria vistas e fundos)
        val de = maxOf(indice - 5, 0)
        for (i in de until minOf(fila.size, maxOf(indice, 0) + 31)) lista.addView(linha(fila[i], i, i == indice))
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
        // miniatura quadrada = lista da música: procura a capa de música de verdade; a de vídeo (16:9) fica como está
        if (larguraMini == alturaMini) Miniaturas.carregarDeMusica(r, imagem, 240) else Miniaturas.carregar(r.miniaturas, imagem, 240)
        addView(imagem, LinearLayout.LayoutParams(larguraMini.d(), alturaMini.d()))
        val textos = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(texto(c, r.titulo, 18, Color.WHITE, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        val sub = r.canal.removeSuffix(" - Topic")      // o que toca agora só fica destacado (sem texto)
        textos.addView(texto(c, sub, 15, if (atual) Cfg.AZUL else Tema.TEXTO3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 2.d() })
        addView(textos, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 14.d() })
    }
}
