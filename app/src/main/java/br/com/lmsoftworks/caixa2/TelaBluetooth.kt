package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tela Bluetooth da v3, em duas colunas: à esquerda o título com o interruptor e o cartão "LM Sound";
 * à direita os aparelhos (CONECTADO, PAREADOS, POR PERTO). A caixa aparece como "LM Sound" para os outros aparelhos.
 * Toque em um aparelho pareado para ver os detalhes; toque em um encontrado para parear.
 */
/** Cor de destaque do menu Bluetooth: fixa, para a tela já nascer pronta (antes seguia o menu aberto antes dela). */
private val DESTAQUE_BT = Color.parseColor("#4C7DFF")

class TelaBluetooth(private val c: Context, private val bt: BluetoothControle) : FrameLayout(c) {

    private val interruptor = Interruptor(c).apply { corFixa = DESTAQUE_BT }
    private val hero = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val lista = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
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

        val rolagem = RolagemFina(c).apply { addView(lista) }
        val cartaoLista = FrameLayout(c).apply {
            background = cartaoVidro()
            setPadding(12.d(), 12.d(), 12.d(), 12.d())
            addView(rolagem, LayoutParams(-1, -1))
        }
        principal.addView(cartaoLista, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = 24.d() })
        addView(principal, LayoutParams(-1, -1))

        interruptor.aoMudar = { v ->
            pendente = v; pendenteAte = android.os.SystemClock.uptimeMillis() + 14_000L
            interruptor.bloqueado = true
            bt.definirLigado(v)
            atualizar(true)
            removeCallbacks(tiquePendente); postDelayed(tiquePendente, 300)
        }

        addView(detalhe, LayoutParams(-1, -1))
        atualizar(true)
    }

    fun reiniciar() {
        fecharDetalhe()
        bt.limparErro()
        bt.sincronizar()
        atualizar(true)
    }

    /** O que a pessoa pediu (ligar/desligar) e ainda não terminou: o botão e os textos já mostram isso. */
    private var pendente: Boolean? = null
    private var pendenteAte = 0L
    private val tiquePendente = object : Runnable { override fun run() { if (pendente != null) { atualizar(true); postDelayed(this, 300) } } }

    fun atualizar(forcar: Boolean = false) {
        pendente?.let { if ((bt.ligado == it && !(!it && bt.desligandoDeVez)) || android.os.SystemClock.uptimeMillis() > pendenteAte) { pendente = null; interruptor.bloqueado = false } }
        if (bt.desligandoDeVez) interruptor.bloqueado = true else if (pendente == null) interruptor.bloqueado = false
        val aps = bt.aparelhos()
        val ass = buildString {
            append(pendente).append('|')
            append(bt.ligado).append(bt.desligandoDeVez).append('|').append(bt.visivel).append('|').append(bt.buscando).append('|').append(bt.erro).append('|').append(DESTAQUE_BT)
            append('|').append(bt.transmissor).append('|').append(bt.trocandoModo).append('|').append(bt.modosDisponiveis)
            aps.forEach { append('|').append(it.endereco).append(it.nome).append(it.pareado).append(it.conectado).append(it.pareando).append(it.conectando) }
        }
        if (!forcar && ass == assinatura) return
        assinatura = ass
        interruptor.ligado = pendente ?: bt.ligado
        interruptor.isEnabled = !bt.trocandoModo
        montarHero()
        montarLista(aps)
        if (emDetalhe && detalheEstado != estado()) fecharDetalhe()
    }

    private fun estado() = bt.aparelhos().joinToString("|") { it.endereco + it.pareado + it.conectado }

    private fun montarHero() {
        hero.removeAllViews()
        val gap = LinearLayout.LayoutParams(-2, -2).apply { topMargin = 12.d() }
        fun t(s: String, tam: Int, cor: Int, media: Boolean = false) = texto(c, s, tam, cor, media).apply { setLineSpacing(0f, 1.15f) }
        val p = pendente
        if (p != null) {
            hero.addView(AnelIcone(c, R.drawable.ic_caixa, 96, Color.parseColor("#7FB4FF"), Color.parseColor("#293D8BFF"), 0))
            hero.addView(t(if (p) "Ligando…" else "Desligando…", 20, Color.parseColor("#7FB4FF"), true), gap)
        } else if (!bt.ligado) {
            hero.addView(AnelIcone(c, R.drawable.ic_caixa, 96, Tema.TEXTO3, Color.parseColor("#12FFFFFF"), 0))
            hero.addView(t("Desligado", 20, Tema.TEXTO3, true), gap)
            hero.addView(t("Ligue o Bluetooth para tocar a música do seu celular nesta caixa.", 18, Tema.TEXTO2), gap)
        } else {
            hero.addView(AnelIcone(c, R.drawable.ic_caixa, 96, Color.parseColor("#7FB4FF"), Color.parseColor("#293D8BFF"), 0))
            hero.addView(t("Visível como", 18, Tema.TEXTO2), gap)
            hero.addView(t("“${bt.nomeCaixa}”", 32, Tema.TEXTO, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4.d() })
            val ajuda = if (bt.transmissor) "Selecione a soundbar ou o fone em Por perto para enviar o áudio da Caixa."
                else "No celular, abra o Bluetooth e escolha ${bt.nomeCaixa}. Depois é só tocar a música lá."
            hero.addView(t(ajuda, 18, Tema.TEXTO2), gap)
        }
        val msg = bt.erro
        if (msg != null) hero.addView(t(msg, 18, Tema.VERMELHO_SUAVE), gap)
        if (bt.trocandoModo) hero.addView(t("Trocando o modo Bluetooth…", 18, Tema.TEXTO2), gap)
        if (bt.modosDisponiveis) {
            hero.addView(View(c), LinearLayout.LayoutParams(1, 0, 1f))
            val seletor = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }      // um botão embaixo do outro: o texto não quebra
            for ((nome, transmissor) in listOf("Receptor" to false, "Transmissor" to true)) {
                val ativo = bt.transmissor == transmissor
                val escolha = botao(c, nome, if (ativo) DESTAQUE_BT else Tema.BOTAO) { confirmarModo(nome, transmissor) }
                escolha.isEnabled = !bt.trocandoModo && !ativo
                seletor.addView(escolha, LinearLayout.LayoutParams(-1, 50.d()).apply {
                    if (transmissor) topMargin = 8.d()
                })
            }
            hero.addView(seletor, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.d() })
        }
    }

    private fun montarLista(aps: List<BluetoothControle.Aparelho>) {
        lista.removeAllViews()
        pendente?.let {
            lista.addView(FrameLayout(c).apply {
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(Carregando(c), LinearLayout.LayoutParams(48.d(), 48.d()))
                    addView(texto(c, if (it) "Ligando…" else "Desligando…", 22, Tema.TEXTO2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 14.d() })
                }, LayoutParams(-2, -2, Gravity.CENTER))
            }, LinearLayout.LayoutParams(-1, 480.d()))
            return
        }
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
        // "Por perto" sempre aparece, com o botão Atualizar (refaz a busca do zero)
        lista.addView(LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(rotuloSecao(c, "Por perto"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(botao(c, "Atualizar", Tema.BOTAO) { bt.limparErro(); bt.atualizarBusca(); atualizar(true) }.apply { minimumHeight = 44.d(); setPadding(22.d(), 0, 22.d(), 0); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 19.d().toFloat()) },
                LinearLayout.LayoutParams(-2, 44.d()).apply { topMargin = 8.d(); rightMargin = 10.d() })
        }, LinearLayout.LayoutParams(-1, -2))
        perto.forEach { linha(it) }
        if (bt.buscando || perto.isEmpty()) {
            lista.addView(texto(c, if (bt.buscando) "Procurando aparelhos…" else "Nenhum aparelho por perto. Coloque-o em modo de pareamento.", 20, Tema.TEXTO3)
                .apply { setPadding(18.d(), 20.d(), 18.d(), 20.d()) })
        }
    }

    private fun linha(a: BluetoothControle.Aparelho) {
        val subtitulo = when {
            a.conectado && bt.transmissor -> "Saída de áudio da Caixa"
            a.conectado -> "Tocando áudio nesta caixa"
            a.conectando -> "Conectando…"
            a.pareando -> "Pareando…"
            a.pareado -> "Pareado · toque para conectar"
            else -> "Toque para parear"
        }
        val cor = when { a.conectado -> Tema.OK; a.pareando || a.conectando -> Color.parseColor("#7FB4FF"); else -> Tema.TEXTO3 }
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
            if (a.conectado || a.conectando || a.pareando) background = fundoArredondado(Color.argb(if (a.conectado) 46 else 30, Color.red(DESTAQUE_BT), Color.green(DESTAQUE_BT), Color.blue(DESTAQUE_BT)), 20.d())
            isClickable = true
            setOnClickListener { escolher(a) }
            addView(caixaIcone, LinearLayout.LayoutParams(52.d(), 52.d()).apply { rightMargin = 18.d() })
            addView(nome, LinearLayout.LayoutParams(0, -2, 1f))
            if (a.conectando || a.pareando) addView(Carregando(c), LinearLayout.LayoutParams(30.d(), 30.d()))      // rodinha no canto: está conectando/pareando
            else if (a.conectado) addView(icone(c, R.drawable.ic_bluetooth, 26, Tema.CONECTADO), LinearLayout.LayoutParams(26.d(), 26.d()))
        }
        lista.addView(linha, LinearLayout.LayoutParams(-1, 86.d()))
    }

    private fun escolher(a: BluetoothControle.Aparelho) {
        if (bt.trocandoModo) return
        bt.limparErro()
        when {
            a.pareando || a.conectando -> { }
            a.pareado -> mostrarDetalhe(a)
            else -> bt.parear(a.endereco)
        }
        if (!emDetalhe) atualizar(true)
    }

    private fun fecharDetalhe() = detalhe.fechar()

    private fun confirmarModo(nome: String, transmissor: Boolean) {
        val conteudo = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        conteudo.addView(texto(c, "Ativar modo $nome?", 32, Tema.TEXTO, true))
        conteudo.addView(texto(c, "A Caixa vai reiniciar para aplicar o novo modo Bluetooth.", 20, Tema.TEXTO2),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.d(); bottomMargin = 24.d() })
        val botoes = LinearLayout(c).apply { gravity = Gravity.END }
        botoes.addView(botao(c, "Cancelar", Tema.BOTAO) { fecharDetalhe() })
        botoes.addView(botao(c, "Trocar e reiniciar", DESTAQUE_BT) { fecharDetalhe(); bt.definirModo(transmissor) },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
        conteudo.addView(botoes)
        detalheEstado = estado()
        detalhe.mostrar(680, conteudo)
    }

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
        if (a.conectado) botoes.addView(botao(c, "Desconectar", DESTAQUE_BT) { fecharDetalhe(); bt.desconectar(a.endereco) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
        else botoes.addView(botao(c, "Conectar", DESTAQUE_BT) { fecharDetalhe(); bt.conectar(a.endereco) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
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
