package br.com.lmsoftworks.caixa2

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tela Bluetooth da v3, em duas colunas: à esquerda o título com o interruptor e o cartão "LM Sound" com o botão de procurar;
 * à direita os aparelhos (CONECTADO, PAREADOS, POR PERTO). A caixa aparece como "LM Sound" para os outros aparelhos.
 * Toque em um aparelho pareado para ver os detalhes; toque em um encontrado para parear.
 */
class TelaBluetooth(private val c: Context, private val bt: BluetoothControle) : FrameLayout(c) {

    private val interruptor = Interruptor(c)
    private val hero = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val lista = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val procurar = LinearLayout(c).apply { gravity = Gravity.CENTER; isClickable = true }
    private val principal = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
    private val detalhe = ModalV3(c)
    private var assinatura = ""
    private var detalheEstado = ""

    val emDetalhe: Boolean get() = detalhe.aberto

    init {
        principal.setPadding(Tema.MARGEM_LATERAL.d(), 40.d(), Tema.MARGEM_LATERAL.d(), Tema.MARGEM_BASE.d())

        val esquerda = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val topo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(texto(c, "Bluetooth", 44, Tema.TEXTO, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(interruptor)
        }
        esquerda.addView(topo, LinearLayout.LayoutParams(-1, 64.d()))
        hero.apply { background = cartaoVidro(); setPadding(30.d(), 30.d(), 30.d(), 30.d()) }
        esquerda.addView(hero, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 20.d() })
        principal.addView(esquerda, LinearLayout.LayoutParams(404.d(), -1))

        val rolagem = ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(lista) }
        val cartaoLista = FrameLayout(c).apply {
            background = cartaoVidro()
            setPadding(12.d(), 12.d(), 12.d(), 12.d())
            addView(rolagem, LayoutParams(-1, -1))
        }
        principal.addView(cartaoLista, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = 24.d() })
        addView(principal, LayoutParams(-1, -1))

        interruptor.aoMudar = { v -> bt.definirLigado(v) }
        procurar.setOnClickListener { bt.iniciarBusca() }
        procurar.efeitoToque()

        addView(detalhe, LayoutParams(-1, -1))
        atualizar(true)
    }

    fun reiniciar() {
        fecharDetalhe()
        bt.limparErro()
        atualizar(true)
    }

    fun atualizar(forcar: Boolean = false) {
        val aps = bt.aparelhos()
        val ass = buildString {
            append(bt.ligado).append('|').append(bt.visivel).append('|').append(bt.buscando).append('|').append(bt.erro).append('|').append(Cfg.AZUL)
            aps.forEach { append('|').append(it.endereco).append(it.nome).append(it.pareado).append(it.conectado).append(it.pareando) }
        }
        if (!forcar && ass == assinatura) return
        assinatura = ass
        interruptor.ligado = bt.ligado
        montarHero()
        montarLista(aps)
        if (emDetalhe && detalheEstado != estado()) fecharDetalhe()
    }

    private fun estado() = bt.aparelhos().joinToString("|") { it.endereco + it.pareado + it.conectado }

    private fun montarHero() {
        hero.removeAllViews()
        val gap = LinearLayout.LayoutParams(-2, -2).apply { topMargin = 12.d() }
        fun t(s: String, tam: Int, cor: Int, media: Boolean = false) = texto(c, s, tam, cor, media).apply { setLineSpacing(0f, 1.15f) }
        if (!bt.ligado) {
            hero.addView(AnelIcone(c, R.drawable.ic_caixa, 96, Tema.TEXTO3, Color.parseColor("#12FFFFFF"), 0))
            hero.addView(t("Desligado", 20, Tema.TEXTO3, true), gap)
            hero.addView(t("Ligue o Bluetooth para tocar a música do seu celular nesta caixa.", 18, Tema.TEXTO2), gap)
        } else {
            hero.addView(AnelIcone(c, R.drawable.ic_caixa, 96, Color.parseColor("#7FB4FF"), Color.parseColor("#293D8BFF"), 0))
            hero.addView(t(if (bt.visivel) "Visível como" else "Aparece como", 18, Tema.TEXTO2), gap)
            hero.addView(t("“${bt.nomeCaixa}”", 32, Tema.TEXTO, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4.d() })
            hero.addView(t("No celular, abra o Bluetooth e escolha ${bt.nomeCaixa}. Depois é só tocar a música lá.", 18, Tema.TEXTO2), gap)
        }
        val msg = bt.erro
        if (msg != null) hero.addView(t(msg, 18, Tema.VERMELHO_SUAVE), gap)
        hero.addView(View(c), LinearLayout.LayoutParams(1, 0, 1f))
        if (bt.ligado) {
            procurar.removeAllViews()
            procurar.background = fundoArredondado(Tema.BOTAO, Tema.RAIO_BOTAO.d(), Tema.BOTAO_BORDA)
            procurar.alpha = if (bt.buscando) .75f else 1f
            if (bt.buscando) {
                procurar.addView(ProgressBar(c).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(Color.WHITE) }, LinearLayout.LayoutParams(28.d(), 28.d()))
            } else procurar.addView(icone(c, R.drawable.ic_busca, 26, Tema.TEXTO))
            procurar.addView(texto(c, if (bt.buscando) "Procurando…" else "Procurar aparelhos", 22, Tema.TEXTO, true), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 10.d() })
            (procurar.parent as? android.view.ViewGroup)?.removeView(procurar)
            hero.addView(procurar, LinearLayout.LayoutParams(-1, 64.d()))
        }
    }

    private fun montarLista(aps: List<BluetoothControle.Aparelho>) {
        lista.removeAllViews()
        if (!bt.ligado) {
            lista.addView(FrameLayout(c).apply { addView(texto(c, "Bluetooth desligado", 22, Tema.TEXTO3), LayoutParams(-2, -2, Gravity.CENTER)) }, LinearLayout.LayoutParams(-1, 480.d()))
            return
        }
        val conectados = aps.filter { it.conectado }
        val pareados = aps.filter { it.pareado && !it.conectado }
        val perto = aps.filter { !it.pareado }
        fun grupo(titulo: String, itens: List<BluetoothControle.Aparelho>) {
            if (itens.isEmpty()) return
            lista.addView(rotuloSecao(c, titulo))
            itens.forEach { linha(it) }
        }
        grupo("Conectado", conectados)
        grupo("Pareados", pareados)
        grupo("Por perto", perto)
        if (aps.isEmpty() || (perto.isEmpty() && bt.buscando)) {
            lista.addView(texto(c, if (bt.buscando) "Procurando aparelhos…" else "Nenhum aparelho ainda. Pareie pelo seu celular escolhendo ${bt.nomeCaixa}.", 20, Tema.TEXTO3)
                .apply { setPadding(18.d(), 20.d(), 18.d(), 20.d()) })
        }
    }

    private fun linha(a: BluetoothControle.Aparelho) {
        val subtitulo = when {
            a.conectado -> "Tocando áudio nesta caixa"
            a.pareando -> "Pareando…"
            a.pareado -> "Pareado · toque para conectar"
            else -> "Toque para parear"
        }
        val cor = when { a.conectado -> Tema.OK; a.pareando -> Color.parseColor("#7FB4FF"); else -> Tema.TEXTO3 }
        val nome = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(texto(c, a.nome, 25, Tema.TEXTO, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(texto(c, subtitulo, 17, cor), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 3.d() })
        }
        val caixaIcone = FrameLayout(c).apply {
            background = fundoArredondado(Color.parseColor("#12FFFFFF"), 16.d())
            addView(icone(c, a.icone, 28, Tema.TEXTO), LayoutParams(28.d(), 28.d(), Gravity.CENTER))
        }
        val linha = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.d(), 0, 18.d(), 0)
            if (a.conectado) background = fundoArredondado(Color.argb(46, Color.red(Cfg.AZUL), Color.green(Cfg.AZUL), Color.blue(Cfg.AZUL)), 20.d())
            isClickable = true
            setOnClickListener { escolher(a) }
            addView(caixaIcone, LinearLayout.LayoutParams(52.d(), 52.d()).apply { rightMargin = 18.d() })
            addView(nome, LinearLayout.LayoutParams(0, -2, 1f))
            if (a.conectado) addView(icone(c, R.drawable.ic_bluetooth, 26, Tema.CONECTADO), LinearLayout.LayoutParams(26.d(), 26.d()))
        }
        lista.addView(linha, LinearLayout.LayoutParams(-1, 86.d()))
    }

    private fun escolher(a: BluetoothControle.Aparelho) {
        bt.limparErro()
        when {
            a.pareando -> { }
            a.pareado -> mostrarDetalhe(a)
            else -> bt.parear(a.endereco)
        }
        if (!emDetalhe) atualizar(true)
    }

    private fun fecharDetalhe() = detalhe.fechar()

    private fun mostrarDetalhe(a: BluetoothControle.Aparelho) {
        val conteudo = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val cab = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(AnelIcone(c, a.icone, 72, Color.parseColor("#7FB4FF"), Color.parseColor("#293D8BFF"), 0))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(texto(c, a.nome, 32, Tema.TEXTO, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(texto(c, if (a.conectado) "Conectado" else "Pareado", 19, if (a.conectado) Tema.OK else Tema.TEXTO3))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 6.d() })
        }
        conteudo.addView(cab)
        val grade = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        grade.addView(linhaDetalhe(c, "Endereço", a.endereco))
        grade.addView(linhaDetalhe(c, "Perfil", "Áudio (A2DP)"))
        conteudo.addView(grade, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 22.d(); bottomMargin = 26.d() })
        val botoes = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        botoes.addView(botao(c, "Esquecer", Tema.BOTAO, Tema.VERMELHO_SUAVE) { fecharDetalhe(); bt.esquecer(a.endereco) })
        botoes.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        botoes.addView(botao(c, "Fechar", Tema.BOTAO) { fecharDetalhe() })
        if (a.conectado) botoes.addView(botao(c, "Desconectar", Cfg.AZUL) { fecharDetalhe(); bt.desconectar(a.endereco) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
        else botoes.addView(botao(c, "Conectar", Cfg.AZUL) { fecharDetalhe(); bt.conectar(a.endereco) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
        conteudo.addView(botoes, LinearLayout.LayoutParams(-1, -2))
        detalheEstado = estado()
        detalhe.mostrar(680, conteudo)
    }

    /** Volta uma camada (janela de detalhes). Devolve true se tratou o botão Voltar. */
    fun voltar(): Boolean {
        if (emDetalhe) { fecharDetalhe(); return true }
        return false
    }
}
