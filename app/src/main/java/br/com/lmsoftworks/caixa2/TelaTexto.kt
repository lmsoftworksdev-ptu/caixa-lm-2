package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tela de texto em tela cheia da v3 (senha do Wi-Fi, novo/editar lembrete, novo item, nova lista): título no alto, caixa de
 * texto com cursor piscando, botão de ação, X, linha de aviso e o mesmo teclado logo abaixo.
 */
class TelaTexto(private val c: Context) : FrameLayout(c) {

    private val iconeTitulo = ImageView(c)
    private val titulo = texto(c, "", 34, Tema.TEXTO, true)
    private val subtitulo = texto(c, "", 34, Tema.TEXTO2, true)
    private val caixaTexto = texto(c, "", 30, Tema.TEXTO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.START; gravity = Gravity.CENTER_VERTICAL }
    private val olho = ImageView(c)
    private val aviso = texto(c, "", 19, Tema.VERMELHO_SUAVE)
    private val botaoAcao: TextView
    private val teclado = TecladoView(c)
    private val caixa = FrameLayout(c)
    private val handler = Handler(Looper.getMainLooper())

    private var dica = ""
    private var ehSenha = false
    private var mostrar = false
    private var conteudo = ""
    private var maximo = 100
    private var cursor = true
    private var capitalizar = true
    private var validar: (String) -> String? = { null }
    private var confirmar: (String) -> Unit = {}
    var aoFechar: (() -> Unit)? = null

    val aberto: Boolean get() = visibility == View.VISIBLE

    private val piscar = object : Runnable {
        override fun run() { cursor = !cursor; desenharTexto(); if (aberto) handler.postDelayed(this, 530) }
    }

    init {
        visibility = View.GONE
        isClickable = true
        // painel escuro que começa logo abaixo da barra de cima (a tela fica 64 px abaixo do topo): a barra nunca é cortada
        addView(View(c).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FA050916"))
                cornerRadii = floatArrayOf(30.d().toFloat(), 30.d().toFloat(), 30.d().toFloat(), 30.d().toFloat(), 0f, 0f, 0f, 0f)
            }
        }, LayoutParams(-1, -1).apply { topMargin = 40.d() })
        val coluna = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(48.d(), 36.d(), 48.d(), 0) }

        val linhaTitulo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(iconeTitulo, LinearLayout.LayoutParams(36.d(), 36.d()).apply { rightMargin = 14.d() })
            addView(titulo); addView(subtitulo, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
        }
        // o título fica de fora para liberar espaço (a barra de cima fica visível); a dica dentro da caixa diz o que digitar

        caixa.addView(caixaTexto, LayoutParams(-1, -1).apply { leftMargin = 26.d(); rightMargin = 26.d() })
        olho.apply {
            setPadding(14.d(), 14.d(), 14.d(), 14.d())
            setColorFilter(Tema.TEXTO2)
            isClickable = true
            setOnClickListener { mostrar = !mostrar; desenharTexto() }
        }
        caixa.addView(olho, LayoutParams(60.d(), 60.d(), Gravity.END))
        botaoAcao = botao(c, "OK", Cfg.AZUL) { enviar() }
        val x = FrameLayout(c).apply {
            background = fundoArredondado(Tema.BOTAO, Tema.RAIO_BOTAO.d(), Tema.BOTAO_BORDA)
            isClickable = true
            setOnClickListener { fechar() }
            efeitoToque()
            addView(icone(c, R.drawable.ic_fechar, 28, Tema.TEXTO), LayoutParams(28.d(), 28.d(), Gravity.CENTER))
        }
        val linhaCampo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(caixa, LinearLayout.LayoutParams(0, 60.d(), 1f))
            addView(x, LinearLayout.LayoutParams(60.d(), 60.d()).apply { leftMargin = 14.d() })
        }
        coluna.addView(linhaCampo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16.d() })
        coluna.addView(aviso, LinearLayout.LayoutParams(-1, 28.d()).apply { topMargin = 4.d(); leftMargin = 6.d() })
        coluna.addView(teclado, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 2.d() })
        addView(coluna, LayoutParams(-1, -1))

        teclado.aoDigitar = { s -> if (conteudo.length < maximo) { conteudo += s; aviso.text = ""; aposMudar() } }
        teclado.aoApagar = { if (conteudo.isNotEmpty()) { conteudo = conteudo.dropLast(1); aposMudar() } }
        teclado.aoAcao = { enviar() }
    }

    private fun aposMudar() {
        desenharTexto()
        teclado.capitalizar(capitalizar && conteudo.isEmpty())
    }

    private fun desenharTexto() {
        val mostra = if (ehSenha && !mostrar) "•".repeat(conteudo.length) else conteudo
        val cur = if (cursor) "|" else " "
        if (conteudo.isEmpty()) {
            caixaTexto.setTextColor(Tema.TEXTO3)
            caixaTexto.letterSpacing = 0f
            caixaTexto.text = dica
        } else {
            caixaTexto.setTextColor(Tema.TEXTO)
            caixaTexto.letterSpacing = if (ehSenha && !mostrar) .15f else 0f
            caixaTexto.text = mostra + cur
        }
        olho.setImageResource(if (mostrar) R.drawable.ic_olho_fechado else R.drawable.ic_olho)
    }

    /**
     * Abre a tela. [titulo] e [destaque] (ex.: "Senha para" + nome da rede); [dica] aparece com a caixa vazia; [acao] é o rótulo
     * do botão; [validar] devolve a mensagem de erro (ou null se pode enviar).
     */
    fun abrir(
        iconeRes: Int, titulo: String, destaque: String = "", dica: String, acao: String, senha: Boolean = false,
        inicial: String = "", maiusculaNoInicio: Boolean = !senha, maximo: Int = 100,
        validar: (String) -> String? = { null }, aoConfirmar: (String) -> Unit
    ) {
        iconeTitulo.setImageResource(iconeRes); iconeTitulo.setColorFilter(Tema.TEXTO)
        this.titulo.text = titulo; subtitulo.text = destaque
        this.dica = dica; ehSenha = senha; mostrar = false; conteudo = inicial; this.maximo = maximo
        capitalizar = maiusculaNoInicio
        this.validar = validar; confirmar = aoConfirmar
        botaoAcao.text = acao
        botaoAcao.background = fundoArredondado(Cfg.AZUL, Tema.RAIO_BOTAO.d())
        teclado.acao = acao
        caixa.background = GradientDrawable().apply {
            cornerRadius = 18.d().toFloat()
            setColor(Color.parseColor("#0DFFFFFF"))
            setStroke(1.5f.d().toInt().coerceAtLeast(1), Cfg.AZUL)
        }
        olho.visibility = if (senha) View.VISIBLE else View.GONE
        aviso.text = ""
        cursor = true
        teclado.capitalizar(capitalizar && conteudo.isEmpty())
        desenharTexto()
        if (!aberto) {
            visibility = View.VISIBLE
            alpha = 0f; scaleX = .96f; scaleY = .96f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Tema.MODAL_MS).setInterpolator(Tema.CURVA).start()
        }
        handler.removeCallbacks(piscar); handler.postDelayed(piscar, 530)
    }

    private fun enviar() {
        val erro = validar(conteudo)
        if (erro != null) { aviso.text = erro; return }
        val t = conteudo
        esconder()
        confirmar(t)
    }

    private fun esconder() {
        handler.removeCallbacks(piscar)
        animate().cancel()
        visibility = View.GONE
        conteudo = ""
    }

    /** X ou voltar: fecha sem enviar. */
    fun fechar() {
        if (!aberto) return
        esconder()
        aoFechar?.invoke()
    }
}
