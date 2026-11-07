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

class TelaRadios(
    private val c: Context,
    private val aoTocar: (List<Radios.Estacao>, Int) -> Unit,
    private val aoTocarTv: (List<TV.Canal>, Int) -> Unit
) : FrameLayout(c) {
    private val principal = Handler(Looper.getMainLooper())
    private val telaTexto = TelaTexto(c)
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

    val emTexto: Boolean get() = telaTexto.aberto

    fun voltar(): Boolean {
        if (telaTexto.aberto) { telaTexto.fechar(); return true }
        return false
    }

    init {
        addView(raiz, LayoutParams(-1, -1))
        raiz.addView(texto(c, if (TV_ATIVA) "Rádio / TV" else "Rádios", 44, Tema.TEXTO, true), LayoutParams(-2, -2).apply { leftMargin = Tema.MARGEM_LATERAL.d(); topMargin = 24.d() })

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
        val barra = FrameLayout(c).apply {
            background = fundoArredondado(Color.parseColor("#26FFFFFF"), 32.d(), Cfg.LINHA)
            isClickable = true
            setOnClickListener { abrirTeclado() }
            efeitoToque()
        }
        barra.addView(icone(c, R.drawable.ic_busca, 28, Color.parseColor("#D6E0F7")), LayoutParams(28.d(), 28.d(), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 26.d() })
        barra.addView(dica, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 74.d() })
        barra.addView(textoBusca, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 74.d(); rightMargin = 24.d() })
        textoBusca.maxLines = 1; textoBusca.ellipsize = TextUtils.TruncateAt.END
        raiz.addView(barra, LayoutParams(-1, 64.d()).apply { leftMargin = Tema.MARGEM_LATERAL.d(); rightMargin = Tema.MARGEM_LATERAL.d(); topMargin = 100.d() })

        // países
        val faixa = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        chipsPaises.orientation = LinearLayout.HORIZONTAL
        chipsPaises.setPadding(Tema.MARGEM_LATERAL.d(), 0, Tema.MARGEM_LATERAL.d(), 0)
        faixa.addView(chipsPaises)
        raiz.addView(faixa, LayoutParams(-1, 52.d()).apply { topMargin = 176.d() })

        // categorias (só na TV)
        faixaCategorias.apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; visibility = View.GONE }
        chipsCategorias.orientation = LinearLayout.HORIZONTAL
        chipsCategorias.setPadding(Tema.MARGEM_LATERAL.d(), 0, Tema.MARGEM_LATERAL.d(), 0)
        faixaCategorias.addView(chipsCategorias)
        raiz.addView(faixaCategorias, LayoutParams(-1, 46.d()).apply { topMargin = 238.d() })

        // resultados
        rolagem.addView(lista, LayoutParams(-1, -2))
        raiz.addView(rolagem, LayoutParams(-1, -1).apply { topMargin = 242.d(); leftMargin = Tema.MARGEM_LATERAL.d(); rightMargin = Tema.MARGEM_LATERAL.d() })

        telaTexto.aoFechar = { raiz.visibility = View.VISIBLE }
        addView(telaTexto, LayoutParams(-1, -1))
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

    private fun atualizarCampoBusca() {
        val q = if (tv) consultaTv else consulta
        textoBusca.text = q
        dica.text = if (tv) "Nome do canal" else "Cidade, estado ou nome da rádio"
        dica.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
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

    private fun abrirTeclado() {
        raiz.visibility = View.INVISIBLE
        telaTexto.abrir(
            iconeRes = R.drawable.ic_busca, titulo = if (tv) "Buscar canais" else "Buscar rádios",
            dica = if (tv) "Nome do canal" else "Cidade, estado, país ou nome da rádio", acao = "Buscar",
            inicial = if (tv) consultaTv else consulta, maximo = 60
        ) { t ->
            raiz.visibility = View.VISIBLE
            val pais = Radios.paisDoTexto(t)
            if (tv) {
                if (pais != null) { paisTv = pais.codigo; consultaTv = ""; categoria = null; atualizarCampoBusca(); montarChips(); buscar() }
                else { consultaTv = t.trim(); atualizarCampoBusca(); aplicarFiltroTv() }
            } else {
                if (pais != null) { codigoPais = pais.codigo; consulta = "" } else consulta = t.trim()
                atualizarCampoBusca(); montarChips(); buscar()
            }
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
            Thread {
                val r = TV.canais(c, pais)
                principal.post {
                    if (meu != pedido) return@post
                    if (r == null) { aviso("Não consegui buscar os canais. Confira a internet da caixa."); return@post }
                    canaisDoPais = r
                    if (categoria != null && categoria !in TV.categoriasDe(r)) categoria = null
                    montarCategorias(); aplicarFiltroTv()
                }
            }.start()
            return
        }
        val pais = codigoPais; val q = consulta
        Thread {
            val r = Radios.buscar(c, pais, q)
            principal.post {
                if (meu != pedido) return@post
                when {
                    r == null -> aviso("Não consegui buscar as rádios. Confira a internet da caixa.")
                    r.isEmpty() -> aviso(if (q.isEmpty()) "Nenhuma rádio encontrada." else "Nenhuma rádio encontrada para “$q”. Tente outra cidade ou outro nome.")
                    else -> mostrar(r)
                }
            }
        }.start()
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
