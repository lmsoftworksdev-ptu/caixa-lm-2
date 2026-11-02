package br.com.lmsoftworks.caixa2

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Menu Receitas: pesquisa uma receita e mostra os resultados (foto, nome e o site de onde vem) em duas fileiras que rolam para os
 * lados. Tocar numa receita abre os ingredientes e o modo de preparo. As receitas vêm de vários sites (ver [Receitas]).
 */
class TelaReceitas(private val c: Context) : FrameLayout(c) {

    private companion object {
        const val LARGURA = 440
        const val ALTURA_FOTO = 330
        val SUGESTOES = listOf("Bolo de cenoura", "Frango assado", "Lasanha", "Brigadeiro", "Pão de queijo", "Feijoada", "Strogonoff", "Panqueca")
    }

    private val principal = Handler(Looper.getMainLooper())
    private var consulta = ""
    private var seq = 0
    private var resultados: List<Receita>? = null
    private var carregando = false
    private var erro: String? = null
    private var piscar: ValueAnimator? = null

    private val textoBusca = texto(c, "", 23, Tema.TEXTO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val dica = texto(c, "Pesquisar receitas", 23, Tema.TEXTO3)
    private val botaoLimpar = FrameLayout(c)
    private val barra = FrameLayout(c)
    private val area = LinearLayout(c)
    private val teclado = FrameLayout(c)
    private val tecladoV3 = TecladoView(c)
    private val detalhe = FrameLayout(c)
    private val fileiras = ArrayList<HorizontalScrollView>()
    /** Barra de pesquisa + lista: some enquanto uma receita está aberta. */
    private val principalView = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private var tecladoAberto = false

    private var cursorVisivel = true
    private val piscarCursor = object : Runnable {
        override fun run() { cursorVisivel = !cursorVisivel; escreverBusca(); if (tecladoAberto) principal.postDelayed(this, 530) }
    }
    private fun escreverBusca(t: String = consulta) { textoBusca.text = comCursor(t, tecladoAberto, cursorVisivel) }
    private var detalheAberto = false
    private var seqDetalhe = 0

    init {
        barra.apply {
            background = fundoArredondado(Color.parseColor("#26FFFFFF"), 32.d(), Cfg.LINHA)
            isClickable = true
            setOnClickListener { abrirTeclado() }
            efeitoToque()
        }
        barra.addView(icone(c, R.drawable.ic_busca, 28, Color.parseColor("#D6E0F7")),
            LayoutParams(28.d(), 28.d(), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 26.d() })
        barra.addView(dica, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 74.d() })
        barra.addView(textoBusca, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 74.d(); rightMargin = 70.d() })
        botaoLimpar.apply {
            background = fundoArredondado(Color.parseColor("#26FFFFFF"), 24.d())
            visibility = View.GONE
            isClickable = true
            setOnClickListener { consulta = ""; resultados = null; erro = null; atualizarBarra(); renderizar() }
            addView(icone(c, R.drawable.ic_fechar, 24, Color.WHITE), LayoutParams(24.d(), 24.d(), Gravity.CENTER))
        }
        barra.addView(botaoLimpar, LayoutParams(48.d(), 48.d(), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = 8.d() })

        area.orientation = LinearLayout.VERTICAL
        val coluna = principalView
        coluna.addView(barra, LinearLayout.LayoutParams(-1, 64.d()).apply { leftMargin = 48.d(); rightMargin = 48.d() })
        coluna.addView(area, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 8.d() })
        addView(coluna, LayoutParams(-1, -1).apply { topMargin = 32.d(); bottomMargin = 6.d() })

        montarTeclado()
        addView(teclado, LayoutParams(-1, (720 - 166).d(), Gravity.BOTTOM))
        teclado.visibility = View.GONE

        detalhe.apply { isClickable = true; visibility = View.GONE }
        addView(detalhe, LayoutParams(-1, -1))
        renderizar()
    }

    // ------------------------------------------------------------------ dados

    private fun temInternet(): Boolean {
        val n = (c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo
        return n != null && n.isConnected
    }

    private fun buscar() {
        val q = consulta.trim()
        val minha = ++seq
        if (q.isEmpty()) { resultados = null; carregando = false; erro = null; renderizar(); return }
        carregando = true; erro = null
        renderizar()
        Thread {
            val lista = try { Receitas.buscar(c, q) } catch (e: Exception) { null }
            principal.post {
                if (minha != seq) return@post
                carregando = false
                if (lista == null || (lista.isEmpty() && !temInternet())) erro = if (!temInternet()) "Sem internet" else "Não consegui buscar agora"
                else resultados = lista
                renderizar()
            }
        }.start()
    }

    /** Escreve [texto] na barra e pesquisa. */
    fun pesquisar(texto: String) {
        consulta = texto
        atualizarBarra()
        fecharTeclado()
        buscar()
    }

    // ------------------------------------------------------------------ lista

    private fun renderizar() {
        piscar?.cancel(); piscar = null
        area.removeAllViews(); fileiras.clear()
        erro?.let { mostrarMensagem(it, "Confira a internet da caixa e tente de novo.", true); return }
        if (carregando) {
            area.addView(fileira(List(4) { esqueleto() }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16.d() })
            piscar = ValueAnimator.ofFloat(.35f, .75f).apply {
                duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { v -> fileiras.forEach { f -> (f.getChildAt(0) as LinearLayout).let { l -> for (i in 0 until l.childCount) l.getChildAt(i).alpha = v.animatedValue as Float } } }
                start()
            }
            return
        }
        val lista = resultados
        if (lista == null) { mostrarInicio(); return }
        if (lista.isEmpty()) { mostrarMensagem("Nenhuma receita encontrada", "Tente outro nome, por exemplo “bolo de chocolate”.", false); return }
        // uma linha só, que rola da direita para a esquerda até o fim
        area.addView(fileira(lista.map { cartao(it) }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16.d() })
    }

    private fun mostrarInicio() {
        val caixa = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        caixa.addView(AnelIcone(c, R.drawable.ic_chef, 96, Color.parseColor("#FFB05A"), Color.parseColor("#26FF9A3D"), 0))
        caixa.addView(texto(c, "O que vamos cozinhar?", 34, Tema.TEXTO, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.d() })
        caixa.addView(texto(c, "Pesquise uma receita ou escolha uma sugestão.", 22, Tema.TEXTO2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 8.d() })
        val chips = LinearLayout(c).apply { gravity = Gravity.CENTER; orientation = LinearLayout.VERTICAL }
        for (linha in SUGESTOES.chunked(4)) {
            val l = LinearLayout(c).apply { gravity = Gravity.CENTER }
            linha.forEachIndexed { i, s ->
                l.addView(botao(c, s, Tema.BOTAO) { pesquisar(s) }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = 12.d() })
            }
            chips.addView(l, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 12.d() })
        }
        caixa.addView(chips, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 14.d() })
        area.addView(caixa, LinearLayout.LayoutParams(-1, -1))
    }

    private fun mostrarMensagem(titulo: String, sub: String, comTentar: Boolean) {
        val caixa = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        caixa.addView(AnelIcone(c, if (comTentar) R.drawable.ic_wifi_off else R.drawable.ic_busca, 96, Tema.VERMELHO_SUAVE, Color.parseColor("#24FF4D5E"), 0))
        caixa.addView(texto(c, titulo, 34, Tema.TEXTO, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.d() })
        caixa.addView(texto(c, sub, 22, Tema.TEXTO2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 8.d() })
        if (comTentar) caixa.addView(botao(c, "Tentar de novo", Cfg.AZUL) { erro = null; buscar() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 24.d() })
        area.addView(caixa, LinearLayout.LayoutParams(-1, -1))
    }

    private fun fileira(cartoes: List<View>): HorizontalScrollView {
        val linha = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(48.d(), 0, 30.d(), 0) }
        cartoes.forEach { linha.addView(it) }
        return HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(linha, LayoutParams(-2, -2))
            fileiras.add(this)
        }
    }

    private fun arredondar(v: View, raio: Int) {
        v.clipToOutline = true
        v.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, view.width, view.height, raio.d().toFloat()) }
        }
    }

    private fun esqueleto(): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(View(c).apply { background = fundoArredondado(Color.parseColor("#33FFFFFF"), 18.d()) }, LinearLayout.LayoutParams(LARGURA.d(), ALTURA_FOTO.d()))
        addView(View(c).apply { background = fundoArredondado(Color.parseColor("#33FFFFFF"), 6.d()) }, LinearLayout.LayoutParams((LARGURA - 30).d(), 18.d()).apply { topMargin = 12.d() })
        addView(View(c).apply { background = fundoArredondado(Color.parseColor("#33FFFFFF"), 6.d()) }, LinearLayout.LayoutParams((LARGURA - 120).d(), 16.d()).apply { topMargin = 8.d() })
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18.d() }
    }

    private fun cartao(r: Receita): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        setOnClickListener { abrirReceita(r) }
        efeitoToque()
        val quadro = FrameLayout(c).apply { background = fundoArredondado(Color.parseColor("#22FFFFFF"), 18.d()) }
        arredondar(quadro, 18)
        val imagem = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        quadro.addView(imagem, FrameLayout.LayoutParams(-1, -1))
        if (r.imagem.isNotEmpty()) Miniaturas.carregar(listOf(r.imagem, r.imagemReserva).filter { it.isNotEmpty() }, imagem)
        addView(quadro, LinearLayout.LayoutParams(LARGURA.d(), ALTURA_FOTO.d()))
        addView(texto(c, r.titulo, 24, Color.WHITE, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(LARGURA.d(), -2).apply { topMargin = 12.d() })
        addView(texto(c, r.fonte, 19, Tema.TEXTO3).apply { maxLines = 1 }, LinearLayout.LayoutParams(LARGURA.d(), -2).apply { topMargin = 3.d() })
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18.d() }
    }

    // ------------------------------------------------------------------ receita aberta

    private fun abrirReceita(r: Receita) {
        val minha = ++seqDetalhe
        detalheAberto = true
        detalhe.visibility = View.VISIBLE
        principalView.visibility = View.INVISIBLE
        montarDetalhe(null, r)        // a página já aparece montada, com brilho passando nas caixas enquanto a receita carrega
        detalhe.alpha = 0f
        detalhe.animate().alpha(1f).setDuration(180).start()
        Thread {
            val d = Receitas.detalhe(c, r)
            principal.post {
                if (minha != seqDetalhe || !detalheAberto) return@post
                if (d == null) { fecharDetalhe(); mostrarAviso("Não consegui abrir esta receita") } else montarDetalhe(d, r)
            }
        }.start()
    }

    private fun mostrarAviso(msg: String) { Aviso.mostrar(c, msg) }

    private fun botaoFechar(): View = FrameLayout(c).apply {
        background = fundoArredondado(Color.parseColor("#33FFFFFF"), 28.d())
        isClickable = true
        setOnClickListener { fecharDetalhe() }
        efeitoToque()
        addView(icone(c, R.drawable.ic_fechar, 28, Color.WHITE), LayoutParams(28.d(), 28.d(), Gravity.CENTER))
    }

    /** [d] nulo = ainda carregando: a foto e o nome já são os da lista; o resto são caixas com brilho passando. */
    private fun montarDetalhe(d: ReceitaDetalhe?, r: Receita) {
        detalhe.removeAllViews()
        // cabeçalho: foto, nome e dados (começa abaixo dos ícones da barra de cima, na mesma altura da barra de pesquisa)
        val cab = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val quadro = FrameLayout(c).apply { background = fundoArredondado(Color.parseColor("#22FFFFFF"), 18.d()) }
        arredondar(quadro, 18)
        val imagem = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        quadro.addView(imagem, FrameLayout.LayoutParams(-1, -1))
        val foto = d?.imagem ?: r.imagem
        if (foto.isNotEmpty()) Miniaturas.carregar(listOf(foto, r.imagemReserva).filter { it.isNotEmpty() }, imagem)
        cab.addView(quadro, LinearLayout.LayoutParams(150.d(), 100.d()))
        val info = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        info.addView(texto(c, d?.titulo ?: r.titulo, 30, Color.WHITE, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (d != null) {
            val meta = listOf(d.fonte, d.tempo, d.porcoes).filter { it.isNotEmpty() }.joinToString("  ·  ")
            info.addView(texto(c, meta, 19, Tema.TEXTO2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.d() })
        } else info.addView(BlocoBrilho(c, 8), LinearLayout.LayoutParams(260.d(), 18.d()).apply { topMargin = 10.d() })
        cab.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 22.d(); rightMargin = 80.d() })
        detalhe.addView(cab, LayoutParams(-1, 100.d()).apply { topMargin = 32.d(); leftMargin = 48.d(); rightMargin = 48.d() })

        // duas colunas: o título de cada uma fica parado e só a descrição rola
        val ing = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(24.d(), 0, 24.d(), 24.d()) }
        val pre = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(24.d(), 0, 24.d(), 24.d()) }
        if (d != null) {
            for (i in d.ingredientes) ing.addView(texto(c, "•  $i", 20, Color.WHITE), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.d() })
            d.passos.forEachIndexed { n, p ->
                val l = LinearLayout(c)
                l.addView(texto(c, "${n + 1}", 20, Color.parseColor("#FFB05A"), true), LinearLayout.LayoutParams(34.d(), -2))
                l.addView(texto(c, p, 20, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
                pre.addView(l, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.d() })
            }
        } else {
            for (n in 0 until 7) ing.addView(BlocoBrilho(c, 8), LinearLayout.LayoutParams(if (n % 3 == 2) 260.d() else 340.d(), 20.d()).apply { topMargin = 16.d() })
            for (n in 0 until 6) {
                pre.addView(BlocoBrilho(c, 8), LinearLayout.LayoutParams(-1, 20.d()).apply { topMargin = 18.d() })
                pre.addView(BlocoBrilho(c, 8), LinearLayout.LayoutParams(560.d(), 20.d()).apply { topMargin = 8.d() })
            }
        }
        fun coluna(titulo: String, conteudo: View) = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = fundoArredondado(Color.parseColor("#14FFFFFF"), 24.d(), Tema.BOTAO_BORDA)
            addView(texto(c, titulo, 22, Color.parseColor("#FFB05A"), true), LinearLayout.LayoutParams(-1, -2).apply { leftMargin = 24.d(); topMargin = 18.d(); bottomMargin = 2.d() })
            addView(ScrollView(c).apply {
                isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
                addView(conteudo)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val colunas = LinearLayout(c)
        colunas.addView(coluna("Ingredientes:", ing), LinearLayout.LayoutParams(0, -1, 4f))
        colunas.addView(coluna("Modo de Preparo:", pre), LinearLayout.LayoutParams(0, -1, 6f).apply { leftMargin = 18.d() })
        detalhe.addView(colunas, LayoutParams(-1, -1).apply { topMargin = 148.d(); leftMargin = 48.d(); rightMargin = 48.d(); bottomMargin = 24.d() })
        detalhe.addView(botaoFechar(), LayoutParams(56.d(), 56.d(), Gravity.TOP or Gravity.END).apply { topMargin = 34.d(); rightMargin = 48.d() })
    }

    private fun fecharDetalhe() {
        if (!detalheAberto) return
        detalheAberto = false
        seqDetalhe++
        principalView.visibility = View.VISIBLE
        detalhe.animate().alpha(0f).setDuration(160).withEndAction { if (!detalheAberto) { detalhe.visibility = View.GONE; detalhe.removeAllViews() } }.start()
    }

    // ------------------------------------------------------------------ barra e teclado

    private fun atualizarBarra() {
        escreverBusca()
        dica.visibility = if (consulta.isEmpty()) View.VISIBLE else View.GONE
        botaoLimpar.visibility = if (consulta.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun montarTeclado() {
        teclado.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#080D1E"))
            cornerRadii = floatArrayOf(30.d().toFloat(), 30.d().toFloat(), 30.d().toFloat(), 30.d().toFloat(), 0f, 0f, 0f, 0f)
        }
        teclado.isClickable = true
        tecladoV3.acao = "Buscar"
        tecladoV3.aoDigitar = { s -> consulta += s; atualizarBarra() }
        tecladoV3.aoApagar = { if (consulta.isNotEmpty()) { consulta = consulta.dropLast(1); atualizarBarra() } }
        tecladoV3.aoAcao = { fecharTeclado(); buscar() }
        teclado.addView(tecladoV3, LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = 12.d() })
    }

    fun abrirTeclado() {
        if (tecladoAberto) return
        tecladoAberto = true
        cursorVisivel = true; escreverBusca(); principal.removeCallbacks(piscarCursor); principal.postDelayed(piscarCursor, 530)
        tecladoV3.acao = "Buscar"      // remonta o teclado: a tecla Buscar pega a cor de destaque do menu de agora
        tecladoV3.capitalizar(false)
        teclado.visibility = View.VISIBLE
        teclado.post {
            teclado.translationY = teclado.height.toFloat()
            teclado.animate().translationY(0f).setDuration(Tema.TECLADO_MS).setInterpolator(Tema.CURVA).start()
        }
        barra.background = fundoArredondado(Color.parseColor("#33FFFFFF"), 33.d(), Color.parseColor("#88FFFFFF"))
    }

    fun fecharTeclado() {
        if (!tecladoAberto) return
        tecladoAberto = false
        principal.removeCallbacks(piscarCursor); escreverBusca()
        barra.background = fundoArredondado(Color.parseColor("#26FFFFFF"), 33.d(), Cfg.LINHA)
        teclado.animate().translationY(teclado.height.toFloat()).setDuration(Tema.TECLADO_MS).setInterpolator(Tema.CURVA)
            .withEndAction { if (!tecladoAberto) teclado.visibility = View.GONE }.start()
    }

    private var engolirToque = false
    val tecladoVisivel: Boolean get() = tecladoAberto
    /** Há uma camada aberta (a receita ou o teclado): o gesto de voltar fecha só ela. */
    val emDetalhe: Boolean get() = detalheAberto || tecladoAberto

    /** Com o teclado aberto, tocar fora dele (e fora da barra) só fecha o teclado. */
    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            engolirToque = false
            if (tecladoAberto && !detalheAberto) {
                val p = IntArray(2); teclado.getLocationOnScreen(p)
                val b = IntArray(2); barra.getLocationOnScreen(b)
                val fora = e.rawY < p[1] && !(e.rawY >= b[1] && e.rawY <= b[1] + barra.height)
                if (fora) { fecharTeclado(); engolirToque = true }
            }
        }
        if (engolirToque) {
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) engolirToque = false
            return true
        }
        return super.dispatchTouchEvent(e)
    }

    /** Voltar: fecha a receita, depois o teclado. Devolve true se havia algo aberto. */
    fun voltar(): Boolean {
        if (detalheAberto) { fecharDetalhe(); return true }
        if (tecladoAberto) { fecharTeclado(); return true }
        return false
    }

    /** Quando o menu aparece. */
    fun aoMostrar() {}

    /** O gesto de voltar não pode começar numa fileira que já rolou. */
    fun areaProtegida(x: Float, y: Float): Boolean {
        if (tecladoAberto || detalheAberto) return false
        val p = IntArray(2)
        for (f in fileiras) {
            f.getLocationOnScreen(p)
            if (y >= p[1] && y <= p[1] + f.height && f.scrollX > 0) return true
        }
        return false
    }
}

/** Caixa de carregamento: uma faixa mais clara passa por dentro dela enquanto o conteúdo não chega. */
class BlocoBrilho(c: Context, private val raio: Int) : View(c) {
    private val fundo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#22FFFFFF") }
    private val faixa = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val area = android.graphics.RectF()
    private val matriz = android.graphics.Matrix()
    private var gradiente: android.graphics.LinearGradient? = null
    private var fase = 0f
    private var animador: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        gradiente = android.graphics.LinearGradient(0f, 0f, w * 0.6f, 0f,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#40FFFFFF"), Color.TRANSPARENT), null, android.graphics.Shader.TileMode.CLAMP)
        faixa.shader = gradiente
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animador = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { fase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { animador?.cancel(); animador = null; super.onDetachedFromWindow() }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = raio.d().toFloat()
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(area, r, r, fundo)
        val g = gradiente ?: return
        canvas.save()
        val caminho = android.graphics.Path().apply { addRoundRect(area, r, r, android.graphics.Path.Direction.CW) }
        canvas.clipPath(caminho)
        matriz.setTranslate(-width * 0.6f + fase * width * 1.6f, 0f)
        g.setLocalMatrix(matriz)
        canvas.drawRect(area, faixa)
        canvas.restore()
    }
}
