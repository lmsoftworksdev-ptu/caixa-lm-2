package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/**
 * Deslizador da v3. [matiz] = barra de cores (arco-íris, 16 de altura, bolinha de 34 com borda branca de 4); senão, trilho de 6 com o
 * trecho preenchido no destaque e bolinha branca de 30.
 */
class Deslizante(c: Context, private val matiz: Boolean) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var valor = 0f
        set(v) { if (!arrastando) { field = v.coerceIn(0f, 1f); invalidate() } }
    var aoMudar: ((Float, Boolean) -> Unit)? = null   // valor e "soltou o dedo?"
    /** Falso: não responde ao toque (ex.: Sensibilidade fora do Rítmico). */
    var ativo = true
    private var arrastando = false
    private var gradiente: LinearGradient? = null
    private var atual = 0f

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), 44.d())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (matiz) {
            val cores = intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED)
            gradiente = LinearGradient(17.d().toFloat(), 0f, (w - 17).d().toFloat(), 0f, cores, null, Shader.TileMode.CLAMP)
        }
    }

    private fun fracaoDe(x: Float) = ((x - 17.d()) / (width - 34.d()).toFloat()).coerceIn(0f, 1f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!ativo) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                arrastando = true; atual = fracaoDe(e.x); aoMudar?.invoke(atual, false); invalidate()
            }
            MotionEvent.ACTION_MOVE -> { atual = fracaoDe(e.x); aoMudar?.invoke(atual, false); invalidate() }
            MotionEvent.ACTION_UP -> {
                atual = fracaoDe(e.x); arrastando = false; valor = atual
                aoMudar?.invoke(atual, true); invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { arrastando = false; invalidate() }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val f = if (arrastando) atual else valor
        val meio = height / 2f
        val esq = 17.d().toFloat()
        val dir = (width - 17).d().toFloat()
        val x = esq + (dir - esq) * f
        p.style = Paint.Style.FILL
        if (matiz) {
            val r = 8.d().toFloat()
            p.shader = gradiente
            canvas.drawRoundRect(esq, meio - r, dir, meio + r, r, r, p)
            p.shader = null
            val k = 17.d().toFloat()
            p.color = Color.WHITE
            canvas.drawCircle(x, meio, k, p)
            p.color = Color.HSVToColor(floatArrayOf(f * 360f, 1f, 1f))
            canvas.drawCircle(x, meio, k - 4.d(), p)
            return
        }
        val r = 3.d().toFloat()
        p.color = Tema.DESLIGADO
        canvas.drawRoundRect(esq, meio - r, dir, meio + r, r, r, p)
        p.color = Cfg.AZUL
        canvas.drawRoundRect(esq, meio - r, x, meio + r, r, r, p)
        val k = (if (arrastando) 17 else 15).d().toFloat()
        p.color = Color.argb(70, 0, 0, 0)
        canvas.drawCircle(x, meio + 2.d(), k, p)
        p.color = Color.WHITE
        canvas.drawCircle(x, meio, k, p)
    }
}

/** Borda do cartão Luzes LED: traço de 2 px na cor da fita e, em volta, um traço largo a 25% (brilho suave); no Rítmico o brilho pulsa. */
class BordaLed(c: Context, private val led: LedControle) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private var rodando = false
    private var nivel = 0f
    private val passo = object : Runnable {
        override fun run() {
            if (!rodando) return
            if (isShown && !Animacao.pausada) invalidate()
            postDelayed(this, 40)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); rodando = true; post(passo) }
    override fun onDetachedFromWindow() { rodando = false; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        val c = led.corFinal(t)
        val raio = Tema.RAIO_CARTAO.d().toFloat()
        // contorno discreto sempre visível, para o cartão não sumir quando a fita está apagada
        p.strokeWidth = 2.d().toFloat()
        p.color = Tema.CARTAO_BORDA
        r.set(1.d().toFloat(), 1.d().toFloat(), width - 1.d().toFloat(), height - 1.d().toFloat())
        canvas.drawRoundRect(r, raio, raio, p)
        if (!led.ligado || c == Color.BLACK && led.modo != "ritmo") return
        val cheia = if (led.modo == "ritmo") led.cor else led.corDestaque(t)
        // só a linha de borda de 2 px na cor da fita (sem brilho em volta, nem pulsando no ritmo)
        p.strokeWidth = 2.d().toFloat()
        p.color = cheia
        canvas.drawRoundRect(r, raio, raio, p)
    }
}

/** Faixa de prévia da fita: 36 segmentos animados ao vivo com o efeito escolhido, dentro de uma pílula escura de 30 de altura. */
class PreviaFita(c: Context, private val led: LedControle) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seg = RectF()
    private var rodando = false
    private var nivel = 0f
    private val passo = object : Runnable {
        override fun run() {
            if (!rodando) return
            if (isShown && !Animacao.pausada) invalidate()
            postDelayed(this, 40)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); rodando = true; post(passo) }
    override fun onDetachedFromWindow() { rodando = false; super.onDetachedFromWindow() }

    private fun escala(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt().coerceIn(0, 255), (Color.green(c) * f).toInt().coerceIn(0, 255), (Color.blue(c) * f).toInt().coerceIn(0, 255))

    override fun onDraw(canvas: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        val h = height.toFloat(); val w = width.toFloat()
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#66000000")
        canvas.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
        val n = 36
        val gap = 6.d().toFloat()
        val margem = 14.d().toFloat()
        val larg = (w - 2 * margem - gap * (n - 1)) / n
        val alt = h - 12.d()
        val topo = (h - alt) / 2
        val ligada = led.ligado && led.online
        val fita = led.corFinal(t)
        val brilho = led.brilho / 100f
        if (led.modo == "ritmo") nivel = if (fita != Color.BLACK) 1f else nivel * .8f
        for (i in 0 until n) {
            val x = margem + i * (larg + gap)
            seg.set(x, topo, x + larg, topo + alt)
            var cor = Color.parseColor("#0FFFFFFF")
            var brilha = false
            if (ligada) {
                when (led.modo) {
                    "arco" -> { cor = escala(Color.HSVToColor(floatArrayOf((i / n.toFloat() * 360f + t * 60f) % 360f, 1f, 1f)), brilho); brilha = true }
                    "ambilight" -> {
                        val hsv = FloatArray(3); Color.colorToHSV(if (fita == Color.BLACK) led.cor else fita, hsv)
                        hsv[0] = (hsv[0] + (i / (n - 1f) - .5f) * 70f + 360f) % 360f
                        cor = escala(Color.HSVToColor(hsv), if (fita == Color.BLACK) .15f else brilho); brilha = fita != Color.BLACK
                    }
                    "ritmo" -> {
                        val dist = abs(i - (n - 1) / 2f) / ((n - 1) / 2f)
                        if (dist <= nivel) {
                            val base = if (i < n / 2 || led.cor2 == 0) led.cor else led.cor2
                            cor = escala(base, brilho); brilha = true
                        }
                    }
                    else -> { cor = fita; brilha = fita != Color.BLACK }
                }
            }
            if (brilha) {
                p.color = Color.argb(60, Color.red(cor), Color.green(cor), Color.blue(cor))
                canvas.drawRoundRect(seg.left - 3.d(), seg.top - 3.d(), seg.right + 3.d(), seg.bottom + 3.d(), 6.d().toFloat(), 6.d().toFloat(), p)
            }
            p.color = cor
            canvas.drawRoundRect(seg, 4.d().toFloat(), 4.d().toFloat(), p)
        }
    }
}

/** Bolinha de uma cor (50), com anel branco de 3 a 7 de distância quando é a selecionada. [vista] é a cor desenhada; a da fita é outra. */
class BolinhaSolida(c: Context, private val vista: Int) : View(c) {
    var selecionada = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(64.d(), 64.d())

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        p.style = Paint.Style.FILL; p.color = vista
        canvas.drawCircle(cx, cy, 25.d().toFloat(), p)
        if (selecionada) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 3.d().toFloat(); p.color = Color.WHITE
            canvas.drawCircle(cx, cy, 30.d().toFloat(), p)
        }
    }
}

/** Bolinha dividida ao meio: esquerda = cor dos graves, direita = cor das batidas médias. */
class BolinhaDupla(c: Context, private val esq: Int, private val dir: Int) : View(c) {
    var selecionada = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(64.d(), 64.d())

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val k = 25.d().toFloat()
        r.set(cx - k, cy - k, cx + k, cy + k)
        p.style = Paint.Style.FILL
        p.color = esq; canvas.drawArc(r, 90f, 180f, true, p)
        p.color = dir; canvas.drawArc(r, 270f, 180f, true, p)
        if (selecionada) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 3.d().toFloat(); p.color = Color.WHITE
            canvas.drawCircle(cx, cy, 30.d().toFloat(), p)
        }
    }
}

/** Ícone de um efeito (ondas, arco-íris, monitor, ponto), desenhado em um quadro de 24. */
class IconeEfeito(c: Context, private val tipo: String) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.WHITE }
    private val area = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(30.d(), 30.d())

    override fun onDraw(canvas: Canvas) {
        val e = width / 24f
        canvas.scale(e, e)
        p.strokeWidth = 2f
        when (tipo) {
            "ritmo" -> { // ondas do som: cinco barras de alturas diferentes
                val xs = floatArrayOf(3f, 7.5f, 12f, 16.5f, 21f); val hs = floatArrayOf(4f, 10f, 16f, 8f, 5f)
                for (i in xs.indices) canvas.drawLine(xs[i], 12f - hs[i] / 2, xs[i], 12f + hs[i] / 2, p)
            }
            "arco" -> { // três arcos concêntricos
                for (k in 0 until 3) { val r = 9f - k * 3f; area.set(12f - r, 17f - r, 12f + r, 17f + r); canvas.drawArc(area, 180f, 180f, false, p) }
            }
            "ambilight" -> { // monitor
                canvas.drawRoundRect(RectF(2.5f, 4f, 21.5f, 16f), 2f, 2f, p)
                canvas.drawLine(8f, 20f, 16f, 20f, p); canvas.drawLine(12f, 16f, 12f, 20f, p)
            }
            else -> { p.style = Paint.Style.FILL; canvas.drawCircle(12f, 12f, 5f, p); p.style = Paint.Style.STROKE; canvas.drawCircle(12f, 12f, 9f, p) }
        }
    }
}

/**
 * Tela Luzes LED da v3: um cartão cheio com borda na cor da fita; no topo o título, o estado e o interruptor; depois a faixa de prévia
 * ao vivo, a escolha de cor (e matiz, só no Fixo) e a grade 2 x 2 de efeitos; embaixo, Brilho e Sensibilidade (só no Rítmico).
 */
class TelaLed(private val c: Context, private val led: LedControle) : FrameLayout(c) {

    private val interruptor = Interruptor(c)
    private val matiz = Deslizante(c, true)
    private val brilho = Deslizante(c, false)
    private val sens = Deslizante(c, false)
    private val subtitulo = texto(c, "", 21, Tema.TEXTO2)
    private val rotuloBrilho = texto(c, "", 20, Tema.TEXTO, true)
    private val rotuloSens = texto(c, "", 20, Tema.TEXTO, true)
    private val rotuloCor = rotuloSecao(c, "Cor").apply { setPadding(0, 0, 0, 8.d()) }
    private val amostras = LinearLayout(c)
    private val dicaCor = texto(c, "", 21, Tema.TEXTO3).apply { setLineSpacing(0f, 1.15f) }
    private val duplasLinha = LinearLayout(c)
    private val grade = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val botoesEfeito = ArrayList<Pair<String, FrameLayout>>()
    private val colunaSens = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    private val duplas = listOf("#FF0000" to "#0000FF", "#0000FF" to "#00FF00", "#FF0000" to "#00FF00", "#FF0000" to "#FFFFFF", "#0000FF" to "#FFFFFF")
    private val bolinhasDuplas = ArrayList<Triple<BolinhaDupla, Int, Int>>()
    private val bolinhasSolidas = ArrayList<Pair<BolinhaSolida, Int>>()
    // o que a bolinha mostra (cores do protótipo) e o que é mandado à fita (azul, vermelho e verde PUROS: só um canal da fita)
    private val vistas = listOf("#2F6BFF", "#FF2D3D", "#22DD55", "#FF7A1A", "#FFD21A", "#9B3CFF", "#FF2D95", "#16E0F0", "#FFFFFF")
    private val cores = listOf("#0000FF", "#FF0000", "#00FF00", "#FF6A00", "#FFD000", "#8A00FF", "#FF0080", "#00FFFF", "#FFFFFF")
    private val descricoes = mapOf("ritmo" to "Pulsa com a música", "arco" to "Todas as cores", "ambilight" to "Segue a tela", "fixo" to "Uma cor só")

    init {
        val principal = FrameLayout(c).apply { setPadding(Tema.MARGEM_LATERAL.d(), 40.d(), Tema.MARGEM_LATERAL.d(), Tema.MARGEM_BASE.d()) }
        val cartao = FrameLayout(c).apply { background = cartaoVidro() }
        val conteudo = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(36.d(), 30.d(), 36.d(), 30.d()) }

        // ----- topo: título, estado e interruptor -----
        val titulos = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(texto(c, "Luzes LED", 44, Tema.TEXTO, true))
            addView(subtitulo, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 2.d() })
        }
        val topo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(titulos, LinearLayout.LayoutParams(0, -2, 1f))
            addView(interruptor)
        }
        conteudo.addView(topo, LinearLayout.LayoutParams(-1, -2))
        conteudo.addView(PreviaFita(c, led), LinearLayout.LayoutParams(-1, 30.d()).apply { topMargin = 16.d() })

        // ----- duas colunas: Cor | Efeito -----
        val colunas = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val esquerda = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        esquerda.addView(rotuloCor)
        vistas.forEachIndexed { i, hex ->
            val envio = Color.parseColor(cores[i])
            val bolinha = BolinhaSolida(c, Color.parseColor(hex)).apply {
                isClickable = true
                setOnClickListener { led.definirCor(envio) }
                efeitoToque()
            }
            bolinhasSolidas.add(bolinha to envio)
            amostras.addView(bolinha)
        }
        duplas.forEach { (a, b) ->
            val ca = Color.parseColor(a); val cb = Color.parseColor(b)
            val bolinha = BolinhaDupla(c, ca, cb).apply {
                isClickable = true
                setOnClickListener { led.definirDupla(ca, cb) }
                efeitoToque()
            }
            duplasLinha.addView(bolinha)
            bolinhasDuplas.add(Triple(bolinha, ca, cb))
        }
        esquerda.addView(dicaCor, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.d(); rightMargin = 24.d() })
        esquerda.addView(amostras, LinearLayout.LayoutParams(-2, -2))
        esquerda.addView(duplasLinha, LinearLayout.LayoutParams(-2, -2))
        matiz.aoMudar = { f, _ -> led.definirCor(Color.HSVToColor(floatArrayOf(f * 360f, 1f, 1f))) }
        esquerda.addView(matiz, LinearLayout.LayoutParams(-1, 44.d()).apply { topMargin = 12.d(); rightMargin = 24.d() })
        colunas.addView(esquerda, LinearLayout.LayoutParams(0, -2, 1.18f))

        val direita = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        direita.addView(rotuloSecao(c, "Efeito").apply { setPadding(0, 0, 0, 8.d()) })
        val ids = LedControle.MODOS.map { it.first }
        for (linhaIdx in 0 until 2) {
            val linha = LinearLayout(c)
            for (col in 0 until 2) {
                val (id, nome) = LedControle.MODOS[linhaIdx * 2 + col]
                val botao = FrameLayout(c).apply {
                    isClickable = true
                    setOnClickListener { led.definirModo(id) }
                    efeitoToque()
                    val miolo = LinearLayout(c).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(IconeEfeito(c, id), LinearLayout.LayoutParams(30.d(), 30.d()).apply { rightMargin = 12.d() })
                        addView(LinearLayout(c).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(texto(c, nome, 21, Tema.TEXTO, true))
                            addView(texto(c, descricoes.getValue(id), 15, Tema.TEXTO2))
                        })
                    }
                    addView(miolo, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 16.d() })
                }
                botoesEfeito.add(id to botao)
                linha.addView(botao, LinearLayout.LayoutParams(0, 84.d(), 1f).apply { if (col == 0) rightMargin = 12.d() })
            }
            grade.addView(linha, LinearLayout.LayoutParams(-1, 84.d()).apply { if (linhaIdx == 1) topMargin = 12.d() })
        }
        direita.addView(grade, LinearLayout.LayoutParams(-1, -2))
        colunas.addView(direita, LinearLayout.LayoutParams(0, -2, 1f))
        conteudo.addView(colunas, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 22.d() })

        // ----- embaixo: Brilho e Sensibilidade lado a lado -----
        conteudo.addView(View(c), LinearLayout.LayoutParams(-1, 0, 1f))
        val sliders = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val colunaBrilho = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(rotuloBrilho, LinearLayout.LayoutParams(-2, -2))
            addView(brilho, LinearLayout.LayoutParams(-1, 44.d()))
        }
        brilho.aoMudar = { f, _ -> led.definirBrilho((5 + f * 95).toInt()) }
        colunaSens.addView(rotuloSens, LinearLayout.LayoutParams(-2, -2))
        colunaSens.addView(sens, LinearLayout.LayoutParams(-1, 44.d()))
        sens.aoMudar = { f, _ -> led.definirSensibilidade((1 + Math.round(f * 9)) * 10) }
        sliders.addView(colunaBrilho, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 28.d() })
        sliders.addView(colunaSens, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 28.d() })
        conteudo.addView(sliders, LinearLayout.LayoutParams(-1, -2))

        cartao.addView(conteudo, LayoutParams(-1, -1))
        cartao.addView(BordaLed(c, led), LayoutParams(-1, -1))
        principal.addView(cartao, LayoutParams(-1, -1))
        addView(principal, LayoutParams(-1, -1))

        interruptor.aoMudar = { v -> led.definirLigado(v) }
        atualizar()
    }

    private fun hueDe(cor: Int): Float {
        val hsv = FloatArray(3); Color.colorToHSV(cor, hsv); return hsv[0]
    }

    fun atualizar() {
        // o destaque é a cor principal do efeito atual, calculada aqui (antes dependia da última troca de tema e ficava com a cor velha)
        val corFita = led.corDestaque(SystemClock.uptimeMillis() / 1000f)
        Cfg.AZUL = if (corFita == Color.WHITE) Cfg.COR_FIXA else corFita
        interruptor.ligado = led.ligado
        interruptor.invalidate()
        val nomeModo = led.nomeModo()
        subtitulo.text = when {
            led.online -> "Fita conectada · $nomeModo" + if (!led.ligado) " · desligada" else ""
            led.conectandoParaTela -> "Conectando à fita…"
            else -> "Fita desconectada" + if (!led.ligado) " · desligada" else ""
        }
        subtitulo.setTextColor(if (led.online) Tema.TEXTO2 else if (led.conectandoParaTela) Tema.TEXTO2 else Tema.VERMELHO_SUAVE)
        matiz.valor = hueDe(led.cor) / 360f
        brilho.valor = (led.brilho - 5) / 95f
        rotuloBrilho.text = "Brilho  ${led.brilho}%"
        val nivelSens = (led.sensibilidade / 10).coerceIn(1, 10)
        sens.valor = (nivelSens - 1) / 9f
        rotuloSens.text = "Sensibilidade  $nivelSens"
        val ritmo = led.modo == "ritmo"
        colunaSens.alpha = if (ritmo) 1f else .35f          // só vale no Rítmico
        sens.ativo = ritmo
        // cor: no Rítmico aparecem as bolinhas divididas; a barra de matiz é só do Fixo
        val escolheCor = led.modo == "fixo" || ritmo
        amostras.visibility = if (escolheCor) View.VISIBLE else View.GONE
        duplasLinha.visibility = if (ritmo) View.VISIBLE else View.GONE
        matiz.visibility = if (led.modo == "fixo") View.VISIBLE else View.GONE
        rotuloCor.visibility = if (escolheCor) View.VISIBLE else View.GONE
        dicaCor.visibility = if (escolheCor) View.GONE else View.VISIBLE
        dicaCor.text = if (led.modo == "ambilight") "No Ambilight a fita segue as cores que estão na tela." else "No Arco-íris a fita passa por todas as cores."
        rotuloCor.text = if (ritmo) "COR · SÓ UMA, OU DUAS (GRAVES | BATIDAS)" else "COR"
        for ((v, cor) in bolinhasSolidas) v.selecionada = led.cor2 == 0 && led.cor == cor && led.modo != "arco"
        for ((v, a, b) in bolinhasDuplas) v.selecionada = led.cor == a && led.cor2 == b
        val destaque = Cfg.AZUL
        for ((id, botao) in botoesEfeito) {
            val sel = id == led.modo
            botao.background = if (sel)
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 20.d().toFloat()
                    setColor(Color.argb(77, Color.red(destaque), Color.green(destaque), Color.blue(destaque)))
                    setStroke(1.5f.d().toInt().coerceAtLeast(1), destaque)
                }
            else fundoArredondado(Tema.BOTAO, 20.d(), Tema.BOTAO_BORDA)
        }
    }
}
