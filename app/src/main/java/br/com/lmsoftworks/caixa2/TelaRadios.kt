package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Menu Rádio / TV. Rádio: rádios da internet do mundo todo (ver [Radios]). TV: canais abertos e gratuitos por país (ver [TV]). Em cada um:
 * escolha o país (botões ou digitando o nome), procure pelo nome (e, nas rádios, também por cidade ou estado) e toque numa da lista.
 */
/** A TV (canais abertos do mundo) está pronta mas desligada: para trazer de volta o seletor Rádio | TV, troque por true. */
const val TV_ATIVA = false

/** Sem o título "Rádios" no alto, a barra de busca sobe (antes em 100) e o teclado começa logo abaixo dela. */
private const val TOPO_BARRA = 28
private const val ALTURA_TECLADO = 656 - TOPO_BARRA - 64 - 8

class TelaRadios(
    private val c: Context,
    private val aoTocar: (List<Radios.Estacao>, Int) -> Unit,
    private val aoTocarTv: (List<TV.Canal>, Int) -> Unit
) : FrameLayout(c) {
    private val principal = Handler(Looper.getMainLooper())
    // teclado igual ao das Receitas: sobe de baixo só até a barra de busca da própria tela e escreve nela (sem caixa de texto própria)
    private var animacaoMic: EntradaVoz.AnimacaoMic? = null
    private var microfoneTeclado: FrameLayout? = null
    private val botaoLimpar = FrameLayout(c)      // o X da barra: limpa a pesquisa e volta à lista normal      // (declarado antes do init, que usa)
    private val teclado = FrameLayout(c)
    private val tecladoV3 = TecladoView(c)
    private var tecladoAberto = false
    private var edicao = ""            // o que está na barra enquanto o teclado está aberto
    private var posCursor = 0
    private fun pos() = posCursor.coerceIn(0, edicao.length)
    private var cursorVisivel = true
    private val piscarCursor = object : Runnable {
        override fun run() { cursorVisivel = !cursorVisivel; atualizarCampoBusca(); if (tecladoAberto) principal.postDelayed(this, 530) }
    }
    private var engolirToque = false
    /** A barra de busca guarda onde o dedo tocou (x), para o cursor ir para o ponto do texto que foi tocado. */
    private val barra = object : FrameLayout(c) {
        var ultimoX = 0f
        override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN || e.actionMasked == android.view.MotionEvent.ACTION_UP) ultimoX = e.x
            return super.dispatchTouchEvent(e)
        }
    }
    private val raiz = FrameLayout(c)
    private val chipsPaises = LinearLayout(c)
    private val chipsCategorias = LinearLayout(c)
    private val faixaCategorias = HorizontalScrollView(c)
    private val lista = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val rolagem = RolagemFina(c)
    private val textoBusca = texto(c, "", 24, Tema.TEXTO)
    private val dica = texto(c, "", 24, Tema.TEXTO3)
    private val botaoRadio = texto(c, "Rádio", 22, Tema.TEXTO, true)
    private val botaoTv = texto(c, "TV", 22, Tema.TEXTO, true)

    // rádio
    private var codigoPais: String? = "BR"
    private var consulta = ""
    private var exibidas: List<Radios.Estacao> = emptyList()      // a lista na tela vira a fila do player: anterior/próxima passam de rádio em rádio

    // TV
    private var tv = false
    private var paisTv = "BR"
    private var consultaTv = ""
    private var categoria: String? = null
    private var canaisDoPais: List<TV.Canal> = emptyList()
    private var canaisExibidos: List<TV.Canal> = emptyList()

    private var pedido = 0
    private var iniciou = false

    val emTexto: Boolean get() = tecladoAberto

    fun voltar(): Boolean {
        if (tecladoAberto) { fecharTeclado(); return true }
        return false
    }

    init {
        addView(raiz, LayoutParams(-1, -1))

        // seletor Rádio | TV
        val seletor = LinearLayout(c).apply { background = fundoArredondado(Tema.BOTAO, 26.d(), Tema.BOTAO_BORDA) }
        for ((b, ehTv) in listOf(botaoRadio to false, botaoTv to true)) {
            b.gravity = Gravity.CENTER
            b.isClickable = true
            b.setOnClickListener { if (tv != ehTv) trocarModo(ehTv) }
            seletor.addView(b, LinearLayout.LayoutParams(130.d(), 52.d()))
        }
        if (TV_ATIVA) raiz.addView(seletor, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = Tema.MARGEM_LATERAL.d(); topMargin = 30.d() })

        // barra de busca (toque abre o teclado)
        barra.apply {
            background = fundoArredondado(Color.parseColor("#26FFFFFF"), 32.d(), Cfg.LINHA)
            isClickable = true
            setOnClickListener { aoTocarBarra() }
            efeitoToque()
        }
        barra.addView(icone(c, R.drawable.ic_busca, 28, Color.parseColor("#D6E0F7")), LayoutParams(28.d(), 28.d(), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 26.d() })
        barra.addView(dica, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 74.d() })
        barra.addView(textoBusca, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 74.d(); rightMargin = 70.d() })
        botaoLimpar.apply {
            background = fundoArredondado(Color.parseColor("#26FFFFFF"), 24.d())
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                edicao = ""; posCursor = 0
                if (tv) consultaTv = "" else { consulta = ""; if (codigoPais == null) codigoPais = "BR" }
                atualizarCampoBusca()
                if (tv) aplicarFiltroTv() else { montarChips(); buscar() }
            }
            addView(icone(c, R.drawable.ic_fechar, 24, Color.WHITE), LayoutParams(24.d(), 24.d(), Gravity.CENTER))
        }
        barra.addView(botaoLimpar, LayoutParams(48.d(), 48.d(), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = 8.d() })
        textoBusca.maxLines = 1; textoBusca.ellipsize = TextUtils.TruncateAt.END
        raiz.addView(barra, LayoutParams(-1, 64.d()).apply { leftMargin = Tema.MARGEM_LATERAL.d(); rightMargin = Tema.MARGEM_LATERAL.d(); topMargin = TOPO_BARRA.d() })

        // países
        val faixa = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        chipsPaises.orientation = LinearLayout.HORIZONTAL
        chipsPaises.setPadding(Tema.MARGEM_LATERAL.d(), 0, Tema.MARGEM_LATERAL.d(), 0)
        faixa.addView(chipsPaises)
        raiz.addView(faixa, LayoutParams(-1, 52.d()).apply { topMargin = (TOPO_BARRA + 76).d() })

        // categorias (só na TV)
        faixaCategorias.apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; visibility = View.GONE }
        chipsCategorias.orientation = LinearLayout.HORIZONTAL
        chipsCategorias.setPadding(Tema.MARGEM_LATERAL.d(), 0, Tema.MARGEM_LATERAL.d(), 0)
        faixaCategorias.addView(chipsCategorias)
        raiz.addView(faixaCategorias, LayoutParams(-1, 46.d()).apply { topMargin = (TOPO_BARRA + 138).d() })

        // resultados
        rolagem.addView(lista, LayoutParams(-1, -2))
        raiz.addView(rolagem, LayoutParams(-1, -1).apply { topMargin = (TOPO_BARRA + 142).d(); leftMargin = Tema.MARGEM_LATERAL.d(); rightMargin = Tema.MARGEM_LATERAL.d() })

        // teclado: começa logo abaixo da barra de busca e vai até o fim da tela
        teclado.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#080D1E"))
            cornerRadii = floatArrayOf(30.d().toFloat(), 30.d().toFloat(), 30.d().toFloat(), 30.d().toFloat(), 0f, 0f, 0f, 0f)
        }
        teclado.isClickable = true
        teclado.visibility = View.GONE
        tecladoV3.acao = "Buscar"
        tecladoV3.aoDigitar = { s ->
            if (edicao.length < 60) { val p = pos(); edicao = edicao.substring(0, p) + s + edicao.substring(p); posCursor = p + s.length; cursorVisivel = true; atualizarCampoBusca() }
        }
        tecladoV3.aoApagar = { val p = pos(); if (p > 0) { edicao = edicao.removeRange(p - 1, p); posCursor = p - 1; cursorVisivel = true; atualizarCampoBusca() } }
        tecladoV3.aoAcao = { val t = edicao; fecharTeclado(); executarBusca(t) }
        val microfone = EntradaVoz.botao(c) { ouvirVoz() }
        microfoneTeclado = microfone
        teclado.addView(microfone, LayoutParams(56.d(), 56.d(), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 8.d() })
        teclado.addView(tecladoV3, LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = 12.d() })
        addView(teclado, LayoutParams(-1, ALTURA_TECLADO.d(), Gravity.BOTTOM))
        pintarSeletor(); atualizarCampoBusca(); montarChips()
    }

    /** Chamada quando o menu aparece: a primeira vez já mostra as rádios mais ouvidas do país escolhido. */
    fun aoMostrar() { if (!iniciou) { iniciou = true; buscar() } }

    private fun pintarSeletor() {
        for ((b, sel) in listOf(botaoRadio to !tv, botaoTv to tv)) {
            b.background = if (sel) fundoArredondado(Cfg.AZUL, 26.d()) else null
            b.setTextColor(if (sel) Color.WHITE else Tema.TEXTO2)
        }
    }

    private fun trocarModo(paraTv: Boolean) {
        tv = paraTv
        pintarSeletor()
        faixaCategorias.visibility = if (tv) View.VISIBLE else View.GONE
        (rolagem.layoutParams as LayoutParams).topMargin = (if (tv) 296 else 242).d()
        rolagem.requestLayout()
        atualizarCampoBusca(); montarChips(); buscar()
    }

    private fun consultaAtual() = if (tv) consultaTv else consulta

    private fun atualizarCampoBusca() {
        val q = if (tecladoAberto) edicao else consultaAtual()
        textoBusca.text = if (tecladoAberto) comCursor(q, true, cursorVisivel, pos()) else q
        dica.text = if (tv) "Nome do canal" else "Cidade, estado ou nome da rádio"
        dica.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
        botaoLimpar.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Toque na barra: abre o teclado e leva o cursor ao ponto tocado (no fim, se o toque foi depois do texto). */
    private fun aoTocarBarra() {
        if (!tecladoAberto) { edicao = consultaAtual(); posCursor = edicao.length }
        if (edicao.isNotEmpty()) {
            val layout = textoBusca.layout
            if (layout != null) {
                val x = barra.ultimoX - textoBusca.left - textoBusca.totalPaddingLeft
                var desenhado = layout.getOffsetForHorizontal(0, x.coerceAtLeast(0f))
                if (tecladoAberto && desenhado > pos()) desenhado--      // a letra invisível do cursor não conta
                posCursor = desenhado.coerceIn(0, edicao.length)
            }
        }
        cursorVisivel = true
        abrirTeclado()
    }

    private fun chip(rotulo: String, selecionado: Boolean, i: Int, ao: () -> Unit, h: Int = 52): TextView = texto(c, rotulo, if (h < 50) 20 else 22, Tema.TEXTO, true).apply {
        gravity = Gravity.CENTER
        setPadding(26.d(), 0, 26.d(), 0)
        background = fundoArredondado(if (selecionado) Cfg.AZUL else Tema.BOTAO, (h / 2).d(), if (selecionado) Cfg.AZUL else Tema.BOTAO_BORDA)
        isClickable = true
        setOnClickListener { ao() }
        efeitoToque()
    }

    private fun montarChips() {
        chipsPaises.removeAllViews()
        val itens = if (tv) Radios.POPULARES else listOf(Radios.Pais("Mundo", "")) + Radios.POPULARES
        for ((i, p) in itens.withIndex()) {
            val sel = if (tv) paisTv == p.codigo else (codigoPais ?: "") == p.codigo
            val v = chip(p.nome, sel, i, {
                // escolher o país SUBSTITUI a pesquisa (não procura o texto dentro do país)
                if (tv) { paisTv = p.codigo; consultaTv = ""; categoria = null } else { codigoPais = p.codigo.ifBlank { null }; consulta = "" }
                atualizarCampoBusca(); montarChips(); buscar()
            })
            chipsPaises.addView(v, LinearLayout.LayoutParams(-2, 52.d()).apply { if (i > 0) leftMargin = 10.d() })
        }
    }

    private fun montarCategorias() {
        chipsCategorias.removeAllViews()
        val itens = listOf<String?>(null) + TV.categoriasDe(canaisDoPais)
        for ((i, cat) in itens.withIndex()) {
            val v = chip(if (cat == null) "Todos" else TV.nomeDaCategoria(cat), categoria == cat, i, {
                categoria = cat; montarCategorias(); aplicarFiltroTv()
            }, 46)
            chipsCategorias.addView(v, LinearLayout.LayoutParams(-2, 46.d()).apply { if (i > 0) leftMargin = 10.d() })
        }
    }

    /** Microfone do teclado: o que for falado aparece na barra e a busca já roda (números falados viram algarismos). */
    private fun ouvirVoz() {
        val mic = microfoneTeclado
        val ligado = EntradaVoz.ouvir ?: run { Aviso.mostrar(c, "Busca por voz indisponível"); return }
        val anim = animacaoMic ?: mic?.let { EntradaVoz.AnimacaoMic(it) }.also { animacaoMic = it }
        ligado({ anim?.ouvindo() }, { parcial -> edicao = parcial; posCursor = parcial.length; atualizarCampoBusca() }, { texto ->
            anim?.parar()
            if (!texto.isNullOrBlank()) { fecharTeclado(); executarBusca(texto) } else Aviso.mostrar(c, "Não ouvi. Toque no microfone e fale de novo.")
        }, { anim?.pesquisando() })
    }

    private fun abrirTeclado() {
        if (tecladoAberto) { atualizarCampoBusca(); return }
        tecladoAberto = true
        cursorVisivel = true; atualizarCampoBusca(); principal.removeCallbacks(piscarCursor); principal.postDelayed(piscarCursor, 530)
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
        principal.removeCallbacks(piscarCursor); atualizarCampoBusca()
        barra.background = fundoArredondado(Color.parseColor("#26FFFFFF"), 32.d(), Cfg.LINHA)
        teclado.animate().translationY(teclado.height.toFloat()).setDuration(Tema.TECLADO_MS).setInterpolator(Tema.CURVA)
            .withEndAction { if (!tecladoAberto) teclado.visibility = View.GONE }.start()
    }

    /** Com o teclado aberto, tocar fora dele (e fora da barra) só fecha o teclado. */
    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            engolirToque = false
            if (tecladoAberto) {
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

    /** O texto foi enviado (tecla Buscar): país, rádio ou canal. */
    private fun executarBusca(t: String) {

            val pais = Radios.paisDoTexto(t)
            if (tv) {
                if (pais != null) { paisTv = pais.codigo; consultaTv = ""; categoria = null; atualizarCampoBusca(); montarChips(); buscar() }
                else { consultaTv = t.trim(); atualizarCampoBusca(); aplicarFiltroTv() }
            } else {
                if (pais != null) { codigoPais = pais.codigo; consulta = "" }
                else {
                    consulta = t.trim()
                    // pesquisar um nome vale para o MUNDO TODO: antes ficava preso ao país escolhido (o chip BR, por exemplo) e não achava rádios de fora
                    if (consulta.isNotEmpty()) codigoPais = null
                }
                atualizarCampoBusca(); montarChips(); buscar()
            }
    }

    private fun aviso(msg: String) {
        lista.removeAllViews()
        lista.addView(texto(c, msg, 24, Tema.TEXTO2).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, 220.d()))
    }

    private fun carregando() {
        lista.removeAllViews()
        lista.addView(LinearLayout(c).apply {
            gravity = Gravity.CENTER
            addView(ProgressBar(c).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }, LinearLayout.LayoutParams(56.d(), 56.d()))
        }, LinearLayout.LayoutParams(-1, 220.d()))
        rolagem.scrollTo(0, 0)
    }

    private fun buscar() {
        val meu = ++pedido
        carregando()
        if (tv) {
            val pais = paisTv
            Tarefas.executar {
                val r = TV.canais(c, pais)
                principal.post {
                    if (meu != pedido) return@post
                    if (r == null) { aviso("Não consegui buscar os canais. Confira a internet da caixa."); return@post }
                    canaisDoPais = r
                    if (categoria != null && categoria !in TV.categoriasDe(r)) categoria = null
                    montarCategorias(); aplicarFiltroTv()
                }
            }
            return
        }
        val pais = codigoPais; val q = consulta
        Tarefas.executar {
            val r = Radios.buscar(c, pais, q)
            principal.post {
                if (meu != pedido) return@post
                when {
                    r == null -> aviso("Não consegui buscar as rádios. Confira a internet da caixa.")
                    r.isEmpty() -> aviso(if (q.isEmpty()) "Nenhuma rádio encontrada." else "Nenhuma rádio encontrada para “$q”. Tente outra cidade ou outro nome.")
                    else -> mostrar(r)
                }
            }
        }
    }

    private fun aplicarFiltroTv() {
        val r = TV.filtrar(canaisDoPais, categoria, consultaTv)
        rolagem.scrollTo(0, 0)
        if (r.isEmpty()) aviso(if (consultaTv.isEmpty()) "Nenhum canal encontrado." else "Nenhum canal encontrado para “$consultaTv”.")
        else mostrarTv(r)
    }

    private fun mostrar(r: List<Radios.Estacao>) {
        lista.removeAllViews()
        exibidas = r
        for (e in r) lista.addView(linha(e.nome, listOf(e.local(), e.detalhe()), e.logo, false) { val i = exibidas.indexOf(e); aoTocar(exibidas, if (i < 0) 0 else i) },
            LinearLayout.LayoutParams(-1, 88.d()).apply { bottomMargin = 8.d() })
    }

    private fun mostrarTv(r: List<TV.Canal>) {
        lista.removeAllViews()
        canaisExibidos = r      // a lista na tela vira a fila do player: anterior/próximo passam de canal em canal
        for (e in r) lista.addView(linha(e.nome, listOf(e.detalhe()), e.logo, true) { val i = canaisExibidos.indexOf(e); aoTocarTv(canaisExibidos, if (i < 0) 0 else i) },
            LinearLayout.LayoutParams(-1, 88.d()).apply { bottomMargin = 8.d() })
    }

    private fun linha(nome: String, subs: List<String>, logoUrl: String, tvLogo: Boolean, ao: () -> Unit): View {
        val l = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = fundoArredondado(Tema.BOTAO, 22.d(), Tema.BOTAO_BORDA)
            setPadding(14.d(), 0, 18.d(), 0)
            isClickable = true
            setOnClickListener { ao() }
            efeitoToque()
        }
        val logo = ImageView(c).apply {
            scaleType = if (tvLogo) ImageView.ScaleType.CENTER_INSIDE else ImageView.ScaleType.CENTER_CROP
            if (tvLogo) setPadding(6.d(), 6.d(), 6.d(), 6.d())      // logos de canal têm formatos variados: inteiras, sem cortar
            background = fundoArredondado(Color.parseColor("#22FFFFFF"), 14.d())
        }
        logo.clipToOutline = true
        logo.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, view.width, view.height, 14.d().toFloat()) }
        }
        if (logoUrl.isNotBlank()) Miniaturas.carregar(listOf(logoUrl), logo, 240, rapido = true)
        l.addView(logo, LinearLayout.LayoutParams(62.d(), 62.d()))
        val textos = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(texto(c, nome, 24, Tema.TEXTO, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        val sub = subs.filter { it.isNotBlank() }.joinToString("   ·   ")
        textos.addView(texto(c, sub, 18, Tema.TEXTO3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.d() })
        l.addView(textos, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 18.d(); rightMargin = 12.d() })
        l.addView(icone(c, R.drawable.ic_tocar, 34, Cfg.AZUL), LinearLayout.LayoutParams(34.d(), 34.d()))
        return l
    }
}
