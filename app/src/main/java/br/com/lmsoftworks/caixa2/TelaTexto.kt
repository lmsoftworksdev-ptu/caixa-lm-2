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
    private val microfone: FrameLayout = EntradaVoz.botao(c, 60) { ouvirVoz() }
    private val animacaoMic = EntradaVoz.AnimacaoMic(microfone, 60)
    private val painel = FrameLayout(c)
    private val handler = Handler(Looper.getMainLooper())

    private var dica = ""
    private var ehSenha = false
    private var mostrar = false
    private var conteudo = ""
    /** Onde está o cursor dentro do texto (0 = antes da primeira letra). Tocar no texto o leva até o ponto tocado. */
    private var posCursor = 0
    private fun pos() = posCursor.coerceIn(0, conteudo.length)
    private var ultimoX = 0f
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
        // fora do painel: um toque fecha (igual aos teclados da Mídia e das Receitas, sem botão X); o menu de trás continua à vista
        addView(View(c).apply { isClickable = true; setOnClickListener { fechar() } }, LayoutParams(-1, -1))
        val coluna = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(48.d(), 36.d(), 48.d(), 0) }

        val linhaTitulo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(iconeTitulo, LinearLayout.LayoutParams(36.d(), 36.d()).apply { rightMargin = 14.d() })
            addView(titulo); addView(subtitulo, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
        }
        // o título fica de fora para liberar espaço (a barra de cima fica visível); a dica dentro da caixa diz o que digitar

        caixa.addView(caixaTexto, LayoutParams(-1, -1).apply { leftMargin = 26.d(); rightMargin = 26.d() })
        // tocar no texto leva o cursor até o ponto tocado (no fim, se o toque foi depois do texto)
        caixa.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP) { ultimoX = e.x; cursorPeloToque() }
            true
        }
        olho.apply {
            setPadding(14.d(), 14.d(), 14.d(), 14.d())
            setColorFilter(Tema.TEXTO2)
            isClickable = true
            setOnClickListener { mostrar = !mostrar; desenharTexto() }
        }
        caixa.addView(olho, LayoutParams(60.d(), 60.d(), Gravity.END))
        botaoAcao = botao(c, "OK", Cfg.AZUL) { enviar() }
        val linhaCampo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(caixa, LinearLayout.LayoutParams(0, 60.d(), 1f))
            addView(microfone, LinearLayout.LayoutParams(60.d(), 60.d()).apply { leftMargin = 14.d() })
        }
        coluna.addView(linhaCampo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16.d() })
        coluna.addView(aviso, LinearLayout.LayoutParams(-1, 28.d()).apply { topMargin = 4.d(); leftMargin = 6.d() })
        coluna.addView(teclado, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 2.d() })
        // o campo e o teclado ficam num painel que sobe de baixo (altura só do conteúdo), como nos outros menus
        addView(painel.apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FA050916"))
                cornerRadii = floatArrayOf(30.d().toFloat(), 30.d().toFloat(), 30.d().toFloat(), 30.d().toFloat(), 0f, 0f, 0f, 0f)
            }
            isClickable = true
            addView(coluna, LayoutParams(-1, -2).apply { bottomMargin = 12.d() })
        }, LayoutParams(-1, -2, Gravity.BOTTOM))

        teclado.aoDigitar = { s -> if (conteudo.length < maximo) { val p = pos(); conteudo = conteudo.substring(0, p) + s + conteudo.substring(p); posCursor = p + s.length; cursor = true; aviso.text = ""; aposMudar() } }
        teclado.aoApagar = { val p = pos(); if (p > 0) { conteudo = conteudo.removeRange(p - 1, p); posCursor = p - 1; cursor = true; aposMudar() } }
        teclado.aoAcao = { enviar() }
    }

    /**
     * Microfone: o que for falado entra no texto onde está o cursor (números falados viram algarismos). Na senha do Wi-Fi o texto entra
     * LIMPO, sem espaços e sem esconder em bolinhas, para a pessoa conferir se o reconhecimento acertou.
     */
    private fun ouvirVoz() {
        val ligado = EntradaVoz.ouvir ?: run { aviso.text = "Voz indisponível agora."; return }
        ligado({ animacaoMic.ouvindo() }, { }, { falado ->
            animacaoMic.parar()
            if (falado.isNullOrBlank()) { aviso.text = "Não ouvi. Toque no microfone e fale de novo."; return@ligado }
            val novo = EntradaVoz.numerosEmDigitos(falado, ehSenha)
            val p = pos()
            val cabe = (maximo - conteudo.length).coerceAtLeast(0)
            val inserir = novo.take(cabe)
            conteudo = conteudo.substring(0, p) + inserir + conteudo.substring(p)
            posCursor = p + inserir.length
            if (ehSenha) mostrar = true      // a senha falada aparece em claro: dá para ver se ficou certa
            cursor = true; aviso.text = ""
            aposMudar()
        }, { animacaoMic.pesquisando() })
    }

    private fun cursorPeloToque() {
        if (conteudo.isEmpty()) return
        val layout = caixaTexto.layout ?: return
        if (layout.getEllipsisCount(0) > 0) { posCursor = conteudo.length; cursor = true; desenharTexto(); return }      // texto comprido cortado no começo: vai para o fim
        val x = ultimoX - caixaTexto.left - caixaTexto.totalPaddingLeft
        var desenhado = layout.getOffsetForHorizontal(0, x.coerceAtLeast(0f))
        if (desenhado > pos()) desenhado--      // a letra invisível do cursor não conta
        posCursor = desenhado.coerceIn(0, conteudo.length)
        cursor = true
        desenharTexto()
    }

    private fun aposMudar() {
        desenharTexto()
        teclado.capitalizar(capitalizar && conteudo.isEmpty())
    }

    private fun desenharTexto() {
        val mostra = if (ehSenha && !mostrar) "•".repeat(conteudo.length) else conteudo
        if (conteudo.isEmpty()) {
            caixaTexto.setTextColor(Tema.TEXTO3)
            caixaTexto.letterSpacing = 0f
            caixaTexto.text = dica
        } else {
            caixaTexto.setTextColor(Tema.TEXTO)
            caixaTexto.letterSpacing = if (ehSenha && !mostrar) .15f else 0f
            caixaTexto.text = comCursor(mostra, true, cursor, pos())
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
        this.dica = dica; ehSenha = senha; mostrar = false; conteudo = inicial; posCursor = inicial.length; this.maximo = maximo
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
            alpha = 1f
            painel.post {
                painel.translationY = painel.height.toFloat()
                painel.animate().translationY(0f).setDuration(Tema.TECLADO_MS).setInterpolator(Tema.CURVA).start()
            }
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
        animate().cancel(); painel.animate().cancel(); painel.translationY = 0f
        visibility = View.GONE
        conteudo = ""; posCursor = 0
    }

    /** X ou voltar: fecha sem enviar. */
    fun fechar() {
        if (!aberto) return
        esconder()
        aoFechar?.invoke()
    }
}
