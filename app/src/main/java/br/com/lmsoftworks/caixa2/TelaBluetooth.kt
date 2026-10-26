package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Tela Bluetooth em uma coluna: a caixa aparece como "LM Sound" para os outros aparelhos.
 * Toque em um aparelho pareado para ver os detalhes; toque em um encontrado para parear.
 */
class TelaBluetooth(private val c: Context, private val bt: BluetoothControle) : FrameLayout(c) {

    private val interruptor = Interruptor(c)
    private val lista = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val aviso = texto(c, "", 20, Cfg.VERMELHO)
    private val dica = texto(c, "", 20, Cfg.MUDO)
    private val procurar = botao(c, "Procurar aparelhos", Cfg.LINHA) { bt.iniciarBusca() }
    private val detalhe = FrameLayout(c)
    private var assinatura = ""
    private var detalheEstado = ""

    val emDetalhe: Boolean get() = detalhe.visibility == View.VISIBLE

    init {
        val principal = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48.d(), 24.d(), 48.d(), 36.d())
        }
        val cartao = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26.d(), 24.d(), 26.d(), 14.d())
            background = fundoArredondado(Cfg.PAINEL, 24.d(), Cfg.LINHA)
        }
        val topo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(texto(c, "Bluetooth", 42, Cfg.TEXTO, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(interruptor)
        }
        cartao.addView(topo, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.d() })
        cartao.addView(dica, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.d() })
        cartao.addView(procurar, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 10.d() })
        cartao.addView(aviso, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 6.d() })
        aviso.visibility = View.GONE
        val rolagem = ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(lista) }
        cartao.addView(rolagem, LinearLayout.LayoutParams(-1, 0, 1f))
        principal.addView(cartao, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(principal, LayoutParams(-1, -1))

        interruptor.aoMudar = { v -> bt.definirLigado(v) }

        detalhe.setBackgroundColor(Color.parseColor("#99000000"))
        detalhe.isClickable = true
        detalhe.visibility = View.GONE
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
            append(bt.ligado).append('|').append(bt.visivel).append('|').append(bt.buscando).append('|').append(bt.erro)
            aps.forEach { append('|').append(it.endereco).append(it.nome).append(it.pareado).append(it.conectado).append(it.pareando) }
        }
        if (!forcar && ass == assinatura) return
        assinatura = ass
        interruptor.ligado = bt.ligado
        procurar.visibility = if (bt.ligado) View.VISIBLE else View.GONE
        procurar.text = if (bt.buscando) "Procurando…" else "Procurar aparelhos"
        procurar.alpha = if (bt.buscando) .6f else 1f
        dica.text = when {
            !bt.ligado -> "Bluetooth desligado"
            bt.visivel -> "Visível como “${bt.nomeCaixa}”. No seu celular, abra o Bluetooth e escolha ${bt.nomeCaixa}."
            else -> "Aparece como “${bt.nomeCaixa}” só para aparelhos já pareados."
        }
        val msg = bt.erro
        aviso.visibility = if (msg != null) View.VISIBLE else View.GONE
        aviso.text = msg ?: ""
        montarLista(aps)
        if (emDetalhe && detalheEstado != estado()) fecharDetalhe()
    }

    private fun estado() = bt.aparelhos().joinToString("|") { it.endereco + it.pareado + it.conectado }

    private fun rotulo(s: String) =
        texto(c, s, 18, Cfg.MUDO).apply { setPadding(18.d(), 16.d(), 0, 6.d()) }

    private fun montarLista(aps: List<BluetoothControle.Aparelho>) {
        lista.removeAllViews()
        if (!bt.ligado) return
        val conectados = aps.filter { it.conectado }
        val pareados = aps.filter { it.pareado && !it.conectado }
        val perto = aps.filter { !it.pareado }
        fun grupo(titulo: String, itens: List<BluetoothControle.Aparelho>) {
            if (itens.isEmpty()) return
            lista.addView(rotulo(titulo))
            itens.forEach { linha(it) }
        }
        grupo("CONECTADO", conectados)
        grupo("PAREADOS", pareados)
        grupo("POR PERTO", perto)
        if (aps.isEmpty() || (perto.isEmpty() && bt.buscando)) {
            lista.addView(texto(c, if (bt.buscando) "Procurando aparelhos…" else "Nenhum aparelho ainda. Pareie pelo seu celular escolhendo ${bt.nomeCaixa}.", 20, Cfg.MUDO)
                .apply { setPadding(18.d(), 20.d(), 0, 20.d()) })
        }
    }

    private fun linha(a: BluetoothControle.Aparelho) {
        val subtitulo = when {
            a.conectado -> "Conectado"
            a.pareando -> "Pareando…"
            a.pareado -> "Pareado"
            else -> "Toque para parear"
        }
        val cor = when { a.conectado -> Cfg.OK; a.pareando -> Cfg.AZUL_CLARO; else -> Cfg.MUDO }
        val nome = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(texto(c, a.nome, 26, Cfg.TEXTO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(texto(c, subtitulo, 18, cor), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4.d() })
        }
        val linha = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.d(), 14.d(), 18.d(), 14.d())
            if (a.conectado) background = fundoArredondado(Color.parseColor("#262F7BFF"), 16.d())
            isClickable = true
            setOnClickListener { escolher(a) }
            addView(icone(c, a.icone, 32, if (a.conectado) Cfg.OK else Cfg.MUDO),
                LinearLayout.LayoutParams(32.d(), 32.d()).apply { rightMargin = 20.d() })
            addView(nome, LinearLayout.LayoutParams(0, -2, 1f))
        }
        lista.addView(linha, LinearLayout.LayoutParams(-1, -2))
        lista.addView(View(c).apply { setBackgroundColor(Cfg.LINHA) }, LinearLayout.LayoutParams(-1, 1.d().coerceAtLeast(1)))
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

    private fun fecharDetalhe() {
        detalhe.visibility = View.GONE
        detalhe.removeAllViews()
    }

    private fun mostrarDetalhe(a: BluetoothControle.Aparelho) {
        detalhe.removeAllViews()
        val caixa = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32.d(), 28.d(), 32.d(), 28.d())
            background = fundoArredondado(Color.parseColor("#FF0D2048"), 26.d(), Cfg.LINHA)
        }
        caixa.addView(texto(c, a.nome, 30, Cfg.TEXTO, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        caixa.addView(texto(c, if (a.conectado) "Conectado" else "Pareado", 22, if (a.conectado) Cfg.OK else Cfg.MUDO),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4.d(); bottomMargin = 18.d() })
        caixa.addView(texto(c, "Endereço: ${a.endereco}", 20, Cfg.MUDO))
        val botoes = LinearLayout(c).apply { gravity = Gravity.END }
        botoes.addView(botao(c, "Fechar", Cfg.LINHA) { fecharDetalhe() })
        if (a.conectado) {
            botoes.addView(botao(c, "Desconectar", Cfg.AZUL) { fecharDetalhe(); bt.desconectar(a.endereco) },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
        } else {
            botoes.addView(botao(c, "Conectar", Cfg.AZUL) { fecharDetalhe(); bt.conectar(a.endereco) },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
        }
        botoes.addView(botao(c, "Esquecer aparelho", Cfg.VERMELHO) { fecharDetalhe(); bt.esquecer(a.endereco) },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
        caixa.addView(botoes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24.d() })
        detalhe.addView(caixa, LayoutParams(760.d(), -2, Gravity.CENTER))
        detalheEstado = estado()
        detalhe.visibility = View.VISIBLE
    }

    /** Volta uma camada (janela de detalhes). Devolve true se tratou o botão Voltar. */
    fun voltar(): Boolean {
        if (emDetalhe) { fecharDetalhe(); return true }
        return false
    }
}
