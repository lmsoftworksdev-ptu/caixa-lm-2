package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Controle vertical de uma banda do equalizador: [valor] de 0 (embaixo) a 1 (em cima). [centrado]: o trecho colorido parte do meio (0 dB). */
class Fader(c: Context, private val centrado: Boolean) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var valor = 0.5f
        set(v) { if (!arrastando) { field = v.coerceIn(0f, 1f); invalidate() } }
    var aoMudar: ((Float, Boolean) -> Unit)? = null      // valor e "soltou o dedo?"
    var ativo = true
        set(v) { field = v; alpha = if (v) 1f else .45f }
    private var arrastando = false
    private var atual = 0f
    private val pad get() = 26.d().toFloat()

    private fun valorDe(y: Float) = (1f - (y - pad) / (height - 2 * pad)).coerceIn(0f, 1f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!ativo) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); arrastando = true; atual = valorDe(e.y); aoMudar?.invoke(atual, false); invalidate() }
            MotionEvent.ACTION_MOVE -> { atual = valorDe(e.y); aoMudar?.invoke(atual, false); invalidate() }
            MotionEvent.ACTION_UP -> { atual = valorDe(e.y); arrastando = false; valor = atual; aoMudar?.invoke(atual, true); invalidate() }
            MotionEvent.ACTION_CANCEL -> { arrastando = false; invalidate() }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val f = if (arrastando) atual else valor
        val x = width / 2f
        val topo = pad
        val base = height - pad
        val y = base - (base - topo) * f
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 8.d().toFloat()
        p.color = Tema.DESLIGADO
        canvas.drawLine(x, topo, x, base, p)
        p.color = Cfg.AZUL
        val de = if (centrado) (topo + base) / 2f else base
        canvas.drawLine(x, de, x, y, p)
        if (centrado) { p.color = Color.parseColor("#59FFFFFF"); canvas.drawLine(x - 16.d(), (topo + base) / 2f, x + 16.d(), (topo + base) / 2f, p.also { it.strokeWidth = 2.d().toFloat() }) }
        p.color = Color.WHITE
        canvas.drawCircle(x, y, 15.d().toFloat(), p)
    }
}

/**
 * Menu Equalizador (amplificador): equalizador de 5 bandas e reforço de graves, com predefinições. Vale para tudo que a caixa toca (música e vídeo do app e o
 * som do celular por Bluetooth).
 */
class TelaAmplificador(private val c: Context) : FrameLayout(c) {
    private val chips = ArrayList<TextView>()
    private val faders = ArrayList<Fader>()
    private val valores = ArrayList<TextView>()
    private lateinit var faderGraves: Fader
    private lateinit var valorGraves: TextView
    private val interruptor = Interruptor(c)

    init {
        addView(texto(c, "Equalizador", 44, Tema.TEXTO, true), LayoutParams(-2, -2).apply { leftMargin = Tema.MARGEM_LATERAL.d(); topMargin = 28.d() })
        addView(texto(c, "Ajuste o som da caixa: graves, médios e agudos", 21, Tema.TEXTO3), LayoutParams(-2, -2).apply { leftMargin = Tema.MARGEM_LATERAL.d(); topMargin = 88.d() })

        // liga e desliga
        val ligar = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        ligar.addView(texto(c, "Ligado", 24, Tema.TEXTO2), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 16.d() })
        ligar.addView(interruptor, LinearLayout.LayoutParams(76.d(), 42.d()))
        interruptor.ligado = Amplificador.ligado
        interruptor.aoMudar = { v -> Amplificador.definirLigado(v); interruptor.ligado = v; atualizar() }
        addView(ligar, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = Tema.MARGEM_LATERAL.d(); topMargin = 40.d() })

        // predefinições
        val linhaChips = LinearLayout(c)
        for ((i, p) in Amplificador.PREDEFINICOES.withIndex()) {
            val chip = texto(c, p.nome, 22, Tema.TEXTO, true).apply {
                gravity = Gravity.CENTER
                setPadding(26.d(), 0, 26.d(), 0)
                isClickable = true
                setOnClickListener { Amplificador.escolherPredefinicao(i); atualizar() }
                efeitoToque()
            }
            chips.add(chip)
            linhaChips.addView(chip, LinearLayout.LayoutParams(-2, 56.d()).apply { if (i > 0) leftMargin = 12.d() })
        }
        addView(linhaChips, LayoutParams(-2, -2).apply { leftMargin = Tema.MARGEM_LATERAL.d(); topMargin = 142.d() })

        // cartão com as faixas
        val cartao = LinearLayout(c).apply {
            background = cartaoVidro()
            orientation = LinearLayout.HORIZONTAL
            setPadding(24.d(), 20.d(), 24.d(), 16.d())
        }
        val total = Amplificador.nBandas + 1
        for (b in 0 until Amplificador.nBandas) cartao.addView(coluna(Amplificador.nomeDaFaixa(b), false, b), LinearLayout.LayoutParams(0, -1, 1f))
        cartao.addView(coluna("Graves", true, -1), LinearLayout.LayoutParams(0, -1, 1f))
        addView(cartao, LayoutParams(-1, -1).apply { leftMargin = Tema.MARGEM_LATERAL.d(); rightMargin = Tema.MARGEM_LATERAL.d(); topMargin = 222.d(); bottomMargin = Tema.MARGEM_BASE.d() })

        if (!Amplificador.disponivel) {
            addView(texto(c, "O equalizador não está disponível neste aparelho.", 22, Tema.ERRO), LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = Tema.MARGEM_LATERAL.d(); bottomMargin = 8.d() })
        }
        atualizar()
    }

    private fun coluna(nome: String, graves: Boolean, banda: Int): View {
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val valor = texto(c, "", 24, Tema.TEXTO, true).apply { gravity = Gravity.CENTER }
        val fader = Fader(c, centrado = !graves)
        col.addView(valor, LinearLayout.LayoutParams(-1, -2))
        col.addView(fader, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 6.d(); bottomMargin = 6.d() })
        col.addView(texto(c, nome, 21, Tema.TEXTO2).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        if (graves) {
            faderGraves = fader; valorGraves = valor
            fader.aoMudar = { f, solto -> Amplificador.definirGraves((f * 1000).toInt(), solto); atualizarValores(); if (solto) atualizarChips() }
        } else {
            faders.add(fader); valores.add(valor)
            fader.aoMudar = { f, solto ->
                Amplificador.definirBanda(banda, (Amplificador.minMb + f * (Amplificador.maxMb - Amplificador.minMb)).toInt(), solto)
                atualizarValores(); if (solto) atualizarChips()
            }
        }
        return col
    }

    private fun atualizarChips() {
        for ((i, chip) in chips.withIndex()) {
            val sel = i == Amplificador.predefinicao
            chip.background = fundoArredondado(if (sel) Cfg.AZUL else Tema.BOTAO, 28.d(), if (sel) Cfg.AZUL else Tema.BOTAO_BORDA)
            chip.alpha = if (Amplificador.ligado) 1f else .5f
        }
    }

    private fun atualizarValores() {
        for ((b, v) in valores.withIndex()) {
            val db = Amplificador.niveis[b] / 100f
            v.text = (if (db > 0.05f) "+" else "") + (if (Math.abs(db - Math.round(db)) < 0.05f) Math.round(db).toString() else String.format("%.1f", db).replace('.', ',')) + " dB"
        }
        valorGraves.text = (Amplificador.graves / 10).toString() + "%"
    }

    /** Põe na tela o que o equalizador tem agora (ao abrir o menu e depois de cada mudança de predefinição). */
    fun atualizar() {
        interruptor.ligado = Amplificador.ligado
        val ativo = Amplificador.ligado && Amplificador.disponivel
        for ((b, f) in faders.withIndex()) {
            f.valor = (Amplificador.niveis[b] - Amplificador.minMb).toFloat() / (Amplificador.maxMb - Amplificador.minMb)
            f.ativo = ativo
        }
        faderGraves.valor = Amplificador.graves / 1000f
        faderGraves.ativo = ativo
        atualizarValores()
        atualizarChips()
    }
}
