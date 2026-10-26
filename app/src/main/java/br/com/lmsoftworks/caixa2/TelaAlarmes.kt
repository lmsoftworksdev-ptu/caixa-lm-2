package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tela Alarmes: a lista à esquerda e o editor à direita. Tudo que muda é salvo na hora.
 * Alarme sem nenhum dia marcado toca uma vez e se desliga sozinho.
 */
class TelaAlarmes(private val c: Context, private val alarmes: AlarmeControle, private val aoTestar: (Alarme) -> Unit) : FrameLayout(c) {

    private val lista = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val editor = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private var selecionado = -1
    private val destaque get() = Cfg.AZUL

    init {
        val principal = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48.d(), 24.d(), 48.d(), 36.d())
        }
        // ----- esquerda: lista
        val esquerda = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26.d(), 22.d(), 26.d(), 18.d())
            background = fundoArredondado(Cfg.PAINEL, 24.d(), Cfg.LINHA)
        }
        val topo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(texto(c, "Alarmes", 42, Cfg.TEXTO, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(botao(c, "+ Novo alarme", Cfg.COR_FIXA) { selecionado = alarmes.novo().id; atualizar() })
        }
        esquerda.addView(topo, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12.d() })
        esquerda.addView(ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(lista) }, LinearLayout.LayoutParams(-1, 0, 1f))

        // ----- direita: editor
        val direita = FrameLayout(c).apply {
            setPadding(26.d(), 22.d(), 26.d(), 18.d())
            background = fundoArredondado(Cfg.PAINEL, 24.d(), Cfg.LINHA)
            addView(editor, LayoutParams(-1, -1))
        }
        principal.addView(esquerda, LinearLayout.LayoutParams(0, -1, 1f))
        principal.addView(direita, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = 24.d() })
        addView(principal, LayoutParams(-1, -1))
        atualizar()
    }

    /** Redesenha a lista e o editor com o que está salvo. */
    fun atualizar() {
        if (alarmes.porId(selecionado) == null) selecionado = alarmes.lista.firstOrNull()?.id ?: -1
        montarLista()
        montarEditor()
    }

    // ------------------------------------------------------------------ lista

    private fun montarLista() {
        lista.removeAllViews()
        if (alarmes.lista.isEmpty()) {
            lista.addView(texto(c, "Nenhum alarme ainda.\nToque em \"+ Novo alarme\".", 22, Cfg.MUDO),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = 24.d() })
            return
        }
        for (a in alarmes.lista.sortedWith(compareBy({ it.hora }, { it.minuto }))) {
            val sel = a.id == selecionado
            val interruptor = Interruptor(c).apply {
                ligado = a.ligado
                aoMudar = { v -> a.ligado = v; alarmes.salvar(); atualizar() }
            }
            val info = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(texto(c, a.nome, 22, if (a.ligado) Color.WHITE else Cfg.MUDO))
                addView(texto(c, alarmes.descricaoDias(a), 18, Cfg.MUDO), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 2.d() })
            }
            val linha = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18.d(), 12.d(), 18.d(), 12.d())
                background = fundoArredondado(if (sel) Color.parseColor("#1CFFFFFF") else Color.TRANSPARENT, 18.d())
                isClickable = true
                setOnClickListener { selecionado = a.id; atualizar() }
                efeitoToque()
                alpha = if (a.ligado) 1f else .6f
                addView(texto(c, "%02d:%02d".format(a.hora, a.minuto), 46, Color.WHITE, true), LinearLayout.LayoutParams(170.d(), -2))
                addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                addView(interruptor)
            }
            lista.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 6.d() })
        }
    }

    // ------------------------------------------------------------------ editor

    private fun montarEditor() {
        editor.removeAllViews()
        val a = alarmes.porId(selecionado)
        if (a == null) {
            editor.gravity = Gravity.CENTER
            editor.addView(texto(c, "Crie um alarme para editar", 24, Cfg.MUDO))
            return
        }
        editor.gravity = Gravity.NO_GRAVITY
        editor.addView(texto(c, "Editar alarme", 30, Cfg.TEXTO, true))

        // hora e minuto, com setas grandes: os dois de 1 em 1
        val relogio = LinearLayout(c).apply {
            gravity = Gravity.CENTER
            addView(coluna("%02d".format(a.hora), { a.hora = (a.hora + 1) % 24; mudou(a) }, { a.hora = (a.hora + 23) % 24; mudou(a) }))
            addView(texto(c, ":", 64, Color.WHITE, true), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 14.d(); rightMargin = 14.d() })
            addView(coluna("%02d".format(a.minuto), { a.minuto = (a.minuto + 1) % 60; mudou(a) }, { a.minuto = (a.minuto + 59) % 60; mudou(a) }))
        }
        editor.addView(relogio, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.d() })

        // dias da semana: D S T Q Q S S
        val dias = LinearLayout(c).apply { gravity = Gravity.CENTER }
        val letras = listOf("D", "S", "T", "Q", "Q", "S", "S")
        for (i in 0..6) {
            val on = (a.dias shr i) and 1 == 1
            dias.addView(TextView(c).apply {
                text = letras[i]
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 22.d().toFloat())
                typeface = FONTE_MEDIA
                gravity = Gravity.CENTER
                background = fundoArredondado(if (on) destaque else Color.parseColor("#1AFFFFFF"), 28.d(), if (on) 0 else Cfg.LINHA)
                isClickable = true
                setOnClickListener { a.dias = a.dias xor (1 shl i); mudou(a) }
                efeitoToque()
                contentDescription = "Dia da semana ${i + 1}: " + if (on) "ligado" else "desligado"
            }, LinearLayout.LayoutParams(56.d(), 56.d()).apply { leftMargin = 6.d(); rightMargin = 6.d() })
        }
        editor.addView(dias, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.d() })

        // nomes prontos
        val nomes = LinearLayout(c).apply { gravity = Gravity.CENTER }
        for (n in AlarmeControle.NOMES) {
            val on = a.nome == n
            nomes.addView(TextView(c).apply {
                text = n
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 19.d().toFloat())
                typeface = FONTE_NORMAL
                gravity = Gravity.CENTER
                setPadding(16.d(), 10.d(), 16.d(), 10.d())
                background = fundoArredondado(if (on) destaque else Color.parseColor("#1AFFFFFF"), 22.d(), if (on) 0 else Cfg.LINHA)
                isClickable = true
                setOnClickListener { a.nome = n; mudou(a) }
                efeitoToque()
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 5.d(); rightMargin = 5.d() })
        }
        editor.addView(nomes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14.d() })

        editor.addView(View(c), LinearLayout.LayoutParams(-1, 0, 1f))
        val botoes = LinearLayout(c).apply { gravity = Gravity.CENTER }
        botoes.addView(botao(c, "Excluir", Color.parseColor("#B3142A")) { alarmes.excluir(a.id); atualizar() })
        editor.addView(botoes, LinearLayout.LayoutParams(-1, -2))
    }

    private fun mudou(a: Alarme) { alarmes.salvar(); atualizar() }

    /** Uma coluna do relógio: seta para cima, número grande, seta para baixo. */
    private fun coluna(valor: String, mais: () -> Unit, menos: () -> Unit): View =
        LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(seta(R.drawable.ic_seta_cima, "Aumentar", mais), LinearLayout.LayoutParams(110.d(), 60.d()))
            addView(texto(c, valor, 76, Color.WHITE, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4.d(); bottomMargin = 4.d() })
            addView(seta(R.drawable.ic_seta_baixo, "Diminuir", menos), LinearLayout.LayoutParams(110.d(), 60.d()))
        }

    private fun seta(recurso: Int, descricao: String, aoClicar: () -> Unit): View =
        BotaoRedondo(c, recurso, descricao, 36).apply {
            pintar(Color.WHITE, false)
            background = fundoArredondado(Color.parseColor("#22FFFFFF"), 18.d())
            setOnClickListener { aoClicar() }
        }
}

/**
 * Tela cheia por cima de tudo quando o alarme toca: sino balançando, hora grande, nome e dois botões.
 */
class TelaAlarmeTocando(private val c: Context, private val aoSoneca: () -> Unit, private val aoParar: () -> Unit) : FrameLayout(c) {

    private val sino = icone(c, R.drawable.ic_sino, 110, Color.WHITE)
    private val hora = texto(c, "", 150, Color.WHITE, true)
    private val nome = texto(c, "", 36, Color.parseColor("#FFD2EA"))

    init {
        visibility = View.GONE
        isClickable = true
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#FF4FA3"), Color.parseColor("#4A1F9E"))
        )
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(sino)
            addView(hora, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.d() })
            addView(nome, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 40.d() })
            val bs = LinearLayout(c).apply {
                gravity = Gravity.CENTER
                // largura fixa nos dois: sem isso o primeiro botão ocupa a linha toda e o Parar fica espremido
                addView(botaoComIcone(R.drawable.ic_lua, "Soneca 5 min", Color.parseColor("#33FFFFFF")) { aoSoneca() },
                    LinearLayout.LayoutParams(360.d(), 84.d()).apply { rightMargin = 24.d() })
                addView(botaoComIcone(R.drawable.ic_parar, "Parar", Cfg.VERMELHO, comTexto = false) { aoParar() }, LinearLayout.LayoutParams(120.d(), 84.d()))      // só o X; "Parar" fica na descrição para leitores de tela
            }
            addView(bs, LinearLayout.LayoutParams(-2, -2))      // largura própria (sem isso herdava a do relógio e cortava os botões)
        }
        addView(col, LayoutParams(-2, -2, Gravity.CENTER))
        sino.pivotX = 55.d().toFloat(); sino.pivotY = 8.d().toFloat()
    }

    /** Botão grande com ícone à esquerda do texto. */
    private fun botaoComIcone(recurso: Int, rotulo: String, cor: Int, comTexto: Boolean = true, aoClicar: () -> Unit): View =
        LinearLayout(c).apply {
            gravity = Gravity.CENTER
            setPadding(24.d(), 0, 24.d(), 0)
            background = fundoArredondado(cor, 22.d(), if (cor == Cfg.LINHA) Cfg.LINHA else 0)
            isClickable = true
            setOnClickListener { aoClicar() }
            efeitoToque()
            contentDescription = rotulo
            addView(icone(c, recurso, if (comTexto) 38 else 48, Color.WHITE))
            if (comTexto) addView(texto(c, rotulo, 28, Color.WHITE, true), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 14.d() })
        }

    fun mostrar(a: Alarme) {
        hora.text = "%02d:%02d".format(a.hora, a.minuto)
        nome.text = a.nome
        visibility = View.VISIBLE
        sino.animate().cancel()
        balancar(true)
    }

    fun esconder() {
        sino.animate().cancel()
        visibility = View.GONE
    }

    private fun balancar(para: Boolean) {
        if (visibility != View.VISIBLE) return
        sino.animate().rotation(if (para) 18f else -18f).setDuration(260).withEndAction { balancar(!para) }.start()
    }
}
