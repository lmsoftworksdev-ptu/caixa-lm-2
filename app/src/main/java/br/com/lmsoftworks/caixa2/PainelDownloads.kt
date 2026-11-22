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

/** "1,2 GB", "340 MB", "12 KB". */
fun tamanhoLegivel(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0).replace('.', ',')
    bytes >= 1_000_000L -> "${bytes / 1_000_000} MB"
    else -> "${bytes / 1_000} KB"
}

/**
 * Aba Downloads da Mídia: os Mix baixados (capa de quatro quadrinhos, nome, quantas músicas e quanto ocupam, e a lixeira para excluir),
 * no alto o quanto de memória os downloads usam e, se há um download rodando, o progresso dele. Tocar num Mix toca ele inteiro, sem internet.
 */
class PainelDownloads(private val c: Context, private val modal: ModalV3, private val aoTocar: (Downloads.Mix) -> Unit) : FrameLayout(c) {

    private val usoTexto = texto(c, "", 19, Tema.TEXTO2)
    private val andamentoTexto = texto(c, "", 18, Tema.TEXTO2)
    private val andamentoTrilho = FrameLayout(c)
    private val andamentoPreenchimento = View(c)
    private val lista = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val rolagem = ScrollView(c).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(lista) }
    private val vazio = texto(c, "Nenhum Mix baixado ainda.\nNa tela Tocando agora, abra Próximas e toque no ícone de download para guardar a lista inteira.", 21, Tema.TEXTO3).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }
    private val botaoCancelar: android.widget.TextView = botao(c, "Cancelar download", Tema.BOTAO) { Downloads.cancelar(); botaoCancelar.text = "Cancelando…"; botaoCancelar.isEnabled = false }
    private val andamentoCaixa = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private var mixes: List<Downloads.Mix> = emptyList()
    private var assinatura = ""
    private var usoEm = 0L
    private var barraVermelha = false

    init {
        val coluna = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val topo = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        topo.addView(texto(c, "Downloads", 34, Tema.TEXTO, true), LinearLayout.LayoutParams(0, -2, 1f))
        topo.addView(usoTexto)
        coluna.addView(topo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.d() })
        andamentoTrilho.background = fundoArredondado(Color.parseColor("#33FFFFFF"), 4.d())
        andamentoPreenchimento.background = fundoArredondado(Tema.OK, 4.d())
        andamentoTrilho.addView(andamentoPreenchimento, LayoutParams(0, -1))
        val linhaAndamento = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        linhaAndamento.addView(andamentoTexto, LinearLayout.LayoutParams(0, -2, 1f))
        linhaAndamento.addView(botaoCancelar, LinearLayout.LayoutParams(-2, 56.d()).apply { leftMargin = 12.d() })
        andamentoCaixa.addView(linhaAndamento)
        andamentoCaixa.addView(andamentoTrilho, LinearLayout.LayoutParams(-1, 8.d()).apply { topMargin = 8.d() })
        coluna.addView(andamentoCaixa, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14.d() })
        coluna.addView(rolagem, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 10.d() })
        addView(coluna, LayoutParams(-1, -1))
        addView(vazio, LayoutParams(-2, -2, Gravity.CENTER))
    }

    /** Chamada depois de excluir um Mix (quem toca precisa parar se era ele). */
    var aoExcluido: ((Downloads.Mix) -> Unit)? = null

    private val ouvinte: () -> Unit = { atualizar() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); Downloads.ouvir(ouvinte); atualizar(true) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); Downloads.desouvir(ouvinte) }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); if (visibility == View.VISIBLE && isAttachedToWindow) atualizar(true) }

    /** Relê a pasta (Mix novos, excluídos), o uso de memória e o progresso. [forcar] redesenha a lista mesmo sem mudança. */
    fun atualizar(forcar: Boolean = false) {
        val a = Downloads.andamento
        // memória: só o texto (espaço usado pelos downloads e espaço livre)
        // (percorrer a pasta a cada aviso de progresso pesava: durante o download só atualiza a cada 3 s)
        val agora = android.os.SystemClock.uptimeMillis()
        if (forcar || a == null || agora - usoEm > 3_000L) {
            usoEm = agora
            usoTexto.text = "Espaço usado: ${tamanhoLegivel(Downloads.usado(c))}  ·  Espaço livre: ${tamanhoLegivel(Downloads.livre(c))}"
        }
        if (a != null) {
            if (andamentoCaixa.visibility != View.VISIBLE) { botaoCancelar.text = "Cancelar download"; botaoCancelar.isEnabled = true }
            andamentoCaixa.visibility = View.VISIBLE
            val cancelando = Downloads.cancelando
            if (cancelando != barraVermelha) { barraVermelha = cancelando; andamentoPreenchimento.background = fundoArredondado(if (cancelando) Cfg.VERMELHO else Tema.OK, 4.d()) }
            if (cancelando) { andamentoTexto.text = ""; return }      // barra vermelha e parada: nada de correr até 100%
            andamentoTexto.text = "Baixando ${a.nome} · ${(a.fracao * 100).toInt()}% (${a.concluidas} de ${a.total})"
            andamentoTrilho.post { andamentoPreenchimento.layoutParams = (andamentoPreenchimento.layoutParams as LayoutParams).apply { width = (andamentoTrilho.width * a.fracao).toInt() }; andamentoPreenchimento.requestLayout() }
        } else andamentoCaixa.visibility = View.GONE
        if (a != null && !forcar) return      // durante o download só o progresso muda; a lista é relida quando ele termina
        val novos = Downloads.listar(c)
        val ass = novos.joinToString("|") { it.id + it.bytes }
        if (!forcar && ass == assinatura) return
        assinatura = ass
        mixes = novos
        lista.removeAllViews()
        vazio.visibility = if (novos.isEmpty() && a == null) View.VISIBLE else View.GONE
        novos.forEach { lista.addView(linha(it), LinearLayout.LayoutParams(-1, 112.d()).apply { bottomMargin = 8.d() }) }
    }

    private fun linha(m: Downloads.Mix): View {
        val capa = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = fundoArredondado(Color.parseColor("#22FFFFFF"), 16.d()); clipToOutline = true }
        m.capa?.let { Miniaturas.carregar(listOf(android.net.Uri.fromFile(it).toString()), capa, 240) }
        val textos = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(texto(c, m.nome, 25, Tema.TEXTO, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        textos.addView(texto(c, "${m.faixas.size} músicas · ${tamanhoLegivel(m.bytes)}", 18, Tema.TEXTO3), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4.d() })
        val lixeira = FrameLayout(c).apply {
            background = fundoArredondado(Color.parseColor("#26FFFFFF"), 44.d())
            isClickable = true; efeitoToque()
            setOnClickListener { confirmarExclusao(m) }
            addView(icone(c, R.drawable.ic_lixeira, 42, Tema.VERMELHO_SUAVE), LayoutParams(42.d(), 42.d(), Gravity.CENTER))
        }
        return LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.d(), 8.d(), 10.d(), 8.d())
            background = fundoArredondado(Color.parseColor("#14FFFFFF"), 22.d())
            isClickable = true; efeitoToque()
            setOnClickListener { aoTocar(m) }
            addView(capa, LinearLayout.LayoutParams(96.d(), 96.d()))
            addView(textos, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 18.d() })
            addView(lixeira, LinearLayout.LayoutParams(88.d(), 88.d()))
        }
    }

    /** Janela "Excluir este Mix?": só apaga da memória depois do "Excluir". */
    private fun confirmarExclusao(m: Downloads.Mix) {
        val conteudo = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        conteudo.addView(texto(c, "Excluir este Mix?", 32, Tema.TEXTO, true))
        conteudo.addView(texto(c, "${m.nome} (${m.faixas.size} músicas, ${tamanhoLegivel(m.bytes)}) será apagado da memória da caixa.", 20, Tema.TEXTO2),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.d(); bottomMargin = 24.d() })
        val botoes = LinearLayout(c).apply { gravity = Gravity.END }
        botoes.addView(botao(c, "Cancelar", Tema.BOTAO) { modal.fechar() })
        botoes.addView(botao(c, "Excluir", Tema.ERRO) { modal.fechar(); aoExcluido?.invoke(m); Downloads.excluir(m); atualizar(true) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
        conteudo.addView(botoes)
        modal.mostrar(680, conteudo)
    }
}
