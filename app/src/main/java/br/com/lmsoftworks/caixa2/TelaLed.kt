package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

/** Deslizador horizontal. [matiz] = barra de cores (arco-íris); senão, barra de brilho. */
class Deslizante(c: Context, private val matiz: Boolean, private val fina: Boolean = false) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var valor = 0f
        set(v) { if (!arrastando) { field = v.coerceIn(0f, 1f); invalidate() } }
    var aoMudar: ((Float, Boolean) -> Unit)? = null   // valor e "soltou o dedo?"
    private var arrastando = false
    private var gradiente: LinearGradient? = null
    private var atual = 0f

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), 48.d())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (matiz) {
            val cores = intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED)
            gradiente = LinearGradient(16.d().toFloat(), 0f, (w - 16.d()).toFloat(), 0f, cores, null, Shader.TileMode.CLAMP)
        }
    }

    private fun fracaoDe(x: Float) = ((x - 16.d()) / (width - 32.d()).toFloat()).coerceIn(0f, 1f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
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
        val esq = 16.d().toFloat()
        val dir = (width - 16.d()).toFloat()
        val r = if (fina) 2.d().toFloat() else 9.d().toFloat()      // fina: linha de ~3 px (barra do volume)
        val x = esq + (dir - esq) * f
        if (fina) {
            p.color = Color.parseColor("#40FFFFFF")
            canvas.drawRoundRect(esq, meio - r, dir, meio + r, r, r, p)
            p.color = Color.WHITE
            canvas.drawRoundRect(esq, meio - r, x, meio + r, r, r, p)
            canvas.drawCircle(x, meio, if (arrastando) 14.d().toFloat() else 11.d().toFloat(), p)
            return
        }
        if (matiz) {
            p.shader = gradiente
            canvas.drawRoundRect(esq, meio - r, dir, meio + r, r, r, p)
            p.shader = null
        } else {
            p.color = Color.parseColor("#33FFFFFF")
            canvas.drawRoundRect(esq, meio - r, dir, meio + r, r, r, p)
            p.color = Cfg.COR_FIXA
            canvas.drawRoundRect(esq, meio - r, x, meio + r, r, r, p)
        }
        p.color = Color.WHITE
        canvas.drawCircle(x, meio, if (arrastando) 17.d().toFloat() else 14.d().toFloat(), p)
    }
}

/** Borda do card Luzes LED: uma fitinha que pisca na cor e no ritmo do efeito escolhido (a mesma cor que a fita mostra). */
class BordaLed(c: Context, private val led: LedControle) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = android.graphics.RectF()
    private var rodando = false
    private val passo = object : Runnable {
        override fun run() {
            if (!rodando) return
            if (isShown) invalidate()
            postDelayed(this, 40)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); rodando = true; post(passo) }
    override fun onDetachedFromWindow() { rodando = false; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        val c = led.corFinal(t)
        val raio = 24.d().toFloat()
        p.style = Paint.Style.STROKE
        // contorno discreto sempre visível, para o card não sumir quando a fita está apagada
        p.strokeWidth = 2.d().toFloat(); p.color = Cfg.LINHA
        r.set(1.d().toFloat(), 1.d().toFloat(), width - 1.d().toFloat(), height - 1.d().toFloat())
        canvas.drawRoundRect(r, raio, raio, p)
        if (c == Color.BLACK) return
        // brilho em volta (camadas translúcidas) e a fita acesa por cima
        for (k in 3 downTo 1) {
            p.strokeWidth = (2 + 3 * k).d().toFloat()
            p.color = Color.argb(34, Color.red(c), Color.green(c), Color.blue(c))
            r.set(2.d().toFloat(), 2.d().toFloat(), width - 2.d().toFloat(), height - 2.d().toFloat())
            canvas.drawRoundRect(r, raio, raio, p)
        }
        p.strokeWidth = 3.d().toFloat(); p.color = c
        canvas.drawRoundRect(r, raio, raio, p)
    }
}

/** Bolinha de uma cor, com anel branco quando é a selecionada. */
class BolinhaSolida(c: Context, private val cor: Int) : View(c) {
    var selecionada = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val r = android.graphics.RectF()

    override fun onDraw(canvas: android.graphics.Canvas) {
        val m = 4.d().toFloat()
        r.set(m, m, width - m, height - m)
        p.style = android.graphics.Paint.Style.FILL
        p.color = cor; canvas.drawOval(r, p)
        if (selecionada) {
            p.style = android.graphics.Paint.Style.STROKE
            p.strokeWidth = 3.d().toFloat()
            p.color = Color.WHITE
            r.inset(-2.d().toFloat(), -2.d().toFloat())
            canvas.drawOval(r, p)
        }
    }
}

/** Bolinha dividida ao meio: esquerda = cor dos graves, direita = cor das batidas médias. */
class BolinhaDupla(c: Context, private val esq: Int, private val dir: Int) : View(c) {
    var selecionada = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val r = android.graphics.RectF()

    override fun onDraw(canvas: android.graphics.Canvas) {
        val m = 3.d().toFloat()
        r.set(m, m, width - m, height - m)
        p.style = android.graphics.Paint.Style.FILL
        p.color = esq; canvas.drawArc(r, 90f, 180f, true, p)
        p.color = dir; canvas.drawArc(r, 270f, 180f, true, p)
        if (selecionada) {
            p.style = android.graphics.Paint.Style.STROKE
            p.strokeWidth = 3.d().toFloat()
            p.color = Color.WHITE
            canvas.drawOval(r, p)
        }
    }
}

/** Tela Luzes LED: ligar, cor, efeito e brilho, com a prévia ao lado. */
class TelaLed(private val c: Context, private val led: LedControle) : FrameLayout(c) {

    private val interruptor = Interruptor(c)
    private val matiz = Deslizante(c, true)
    private val brilho = Deslizante(c, false)
    private val rotuloBrilho = texto(c, "", 20, Color.WHITE)
    private val sens = Deslizante(c, false)
    private val rotuloSens = texto(c, "", 20, Color.WHITE)
    private val amostras = LinearLayout(c)
    private val pilulas = LinearLayout(c)
    private val duplas = listOf("#FF0000" to "#0000FF", "#0000FF" to "#00FF00", "#FF0000" to "#00FF00", "#FF0000" to "#FFFFFF", "#0000FF" to "#FFFFFF")
    private val bolinhasDuplas = ArrayList<Triple<BolinhaDupla, Int, Int>>()
    private val bolinhasSolidas = ArrayList<Pair<BolinhaSolida, Int>>()
    // azul, vermelho e verde PUROS (só um canal da fita); as outras são misturas vivas, sem ser lavadas
    private val cores = listOf("#0000FF", "#FF0000", "#00FF00", "#FF6A00", "#FFD000", "#8A00FF", "#FF0080", "#00FFFF", "#FFFFFF")

    init {
        val principal = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48.d(), 24.d(), 48.d(), 36.d())
        }
        val esquerda = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26.d(), 22.d(), 26.d(), 14.d())
            background = fundoArredondado(Cfg.PAINEL, 24.d(), Cfg.LINHA)
        }
        val topo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(texto(c, "Luzes LED", 40, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(interruptor)
        }
        esquerda.addView(topo, LinearLayout.LayoutParams(-1, -2))

        esquerda.addView(espaco(), LinearLayout.LayoutParams(-1, 0, 0.8f))
        esquerda.addView(rotulo("Cor"))
        cores.forEach { hex ->
            val cor = Color.parseColor(hex)
            val amostra = BolinhaSolida(c, cor).apply {
                isClickable = true
                setOnClickListener { led.definirCor(cor) }
                efeitoToque()
            }
            bolinhasSolidas.add(amostra to cor)
            amostras.addView(amostra, LinearLayout.LayoutParams(44.d(), 44.d()).apply { rightMargin = 20.d() })
        }
        // bolinhas de duas cores (metade/metade), só no Rítmico: a metade esquerda responde aos graves, a direita às batidas médias
        duplas.forEach { (a, b) ->
            val ca = Color.parseColor(a); val cb = Color.parseColor(b)
            val bolinha = BolinhaDupla(c, ca, cb).apply {
                isClickable = true
                setOnClickListener { led.definirDupla(ca, cb) }
                efeitoToque()
            }
            amostras.addView(bolinha, LinearLayout.LayoutParams(44.d(), 44.d()).apply { rightMargin = 20.d() })
            bolinhasDuplas.add(Triple(bolinha, ca, cb))
        }
        esquerda.addView(amostras, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4.d() })
        matiz.aoMudar = { f, _ -> led.definirCor(Color.HSVToColor(floatArrayOf(f * 360f, 1f, 1f))) }
        esquerda.addView(matiz, LinearLayout.LayoutParams(-1, 48.d()).apply { topMargin = 28.d() })

        esquerda.addView(espaco(), LinearLayout.LayoutParams(-1, 0, 1f))
        esquerda.addView(rotulo("Efeito"))
        LedControle.MODOS.forEach { (id, nome) ->
            val pil = TextView(c).apply {
                text = nome
                setTextColor(Color.WHITE)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 21.d().toFloat())
                typeface = FONTE_NORMAL
                gravity = Gravity.CENTER
                setPadding(22.d(), 12.d(), 22.d(), 12.d())
                tag = id
                isClickable = true
                setOnClickListener { led.definirModo(id) }
                efeitoToque()
            }
            pilulas.addView(pil, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 10.d() })
        }
        esquerda.addView(pilulas, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4.d() })

        esquerda.addView(espaco(), LinearLayout.LayoutParams(-1, 0, 1f))
        esquerda.addView(rotuloBrilho, LinearLayout.LayoutParams(-2, -2))
        brilho.aoMudar = { f, _ -> led.definirBrilho((5 + f * 95).toInt()) }
        esquerda.addView(brilho, LinearLayout.LayoutParams(-1, 48.d()))

        esquerda.addView(espaco(), LinearLayout.LayoutParams(-1, 0, 1f))
        esquerda.addView(rotuloSens, LinearLayout.LayoutParams(-2, -2))
        sens.aoMudar = { f, _ -> led.definirSensibilidade((f * 100).toInt()) }
        esquerda.addView(sens, LinearLayout.LayoutParams(-1, 48.d()))
        esquerda.addView(espaco(), LinearLayout.LayoutParams(-1, 0, 0.4f))

        // a borda do card é a "fitinha": pisca do jeito do efeito escolhido
        val cartao = FrameLayout(c)
        cartao.addView(esquerda, LayoutParams(-1, -1))
        cartao.addView(BordaLed(c, led), LayoutParams(-1, -1))
        principal.addView(cartao, LinearLayout.LayoutParams(-1, -1))
        addView(principal, LayoutParams(-1, -1))

        interruptor.aoMudar = { v -> led.definirLigado(v) }
        atualizar()
    }

    private fun espaco() = View(c)

    private fun rotulo(s: String) =
        texto(c, s, 19, Color.WHITE).apply { setPadding(0, 10.d(), 0, 0) }

    private fun hueDe(cor: Int): Float {
        val hsv = FloatArray(3); Color.colorToHSV(cor, hsv); return hsv[0]
    }

    fun atualizar() {
        interruptor.ligado = led.ligado
        matiz.valor = hueDe(led.cor) / 360f
        brilho.valor = (led.brilho - 5) / 95f
        rotuloBrilho.text = "Brilho  ${led.brilho}%"
        sens.valor = led.sensibilidade / 100f
        rotuloSens.text = "Sensibilidade  ${led.sensibilidade}%"
        val verSens = if (led.modo == "ritmo") View.VISIBLE else View.GONE
        sens.visibility = verSens; rotuloSens.visibility = verSens
        for ((v, cor) in bolinhasSolidas) v.selecionada = led.cor2 == 0 && led.cor == cor && led.modo != "arco"
        for ((v, a, b) in bolinhasDuplas) {
            v.visibility = verSens
            v.selecionada = led.cor == a && led.cor2 == b
        }
        for (i in 0 until pilulas.childCount) {
            val v = pilulas.getChildAt(i)
            val sel = v.tag == led.modo
            v.background = fundoArredondado(if (sel) Cfg.COR_FIXA else Color.parseColor("#14FFFFFF"), 30.d(), Cfg.LINHA)
        }
    }
}
