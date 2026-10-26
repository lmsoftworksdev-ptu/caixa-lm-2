package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Anel que se esvazia durante a contagem de 5 segundos, com o número no meio. */
private class AnelContagem(c: Context, private val cor: Int) : View(c) {
    var inicio = 0L
    var total = 5000L
    var numero = 5
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8.d().toFloat(); strokeCap = Paint.Cap.ROUND }
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = FONTE_MEDIA; textSize = 62.d().toFloat() }
    private val r = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(170.d(), 170.d())

    override fun onDraw(canvas: Canvas) {
        val m = 8.d().toFloat()
        r.set(m, m, width - m, height - m)
        p.color = Color.parseColor("#26FFFFFF"); canvas.drawOval(r, p)
        val resta = 1f - ((SystemClock.uptimeMillis() - inicio).toFloat() / total).coerceIn(0f, 1f)
        p.color = cor
        canvas.drawArc(r, -90f, 360f * resta, false, p)
        canvas.drawText(numero.toString(), width / 2f, height / 2f + t.textSize * 0.35f, t)
        if (resta > 0f) postInvalidateOnAnimation()
    }
}

/**
 * Janela de energia: Desligar / Reiniciar / Cancelar. Depois de escolher, uma contagem de 5 segundos (com Cancelar) evita
 * desligar sem querer. Ao zerar, mostra "Desligando…" ou "Reiniciando…" em tela preta e chama [executar].
 */
class ModalEnergia(private val c: Context, private val executar: (Boolean) -> Unit) : FrameLayout(c) {

    private val handler = Handler(Looper.getMainLooper())
    private val cartao = LinearLayout(c)
    private var contagem: Runnable? = null
    private val vermelho = Color.parseColor("#FF5A6A")
    private val azul = Color.parseColor("#6AA8FF")

    init {
        visibility = View.GONE
        setBackgroundColor(Color.parseColor("#E602060F"))
        isClickable = true      // engole os toques para não passarem para a tela de baixo
        cartao.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(34.d(), 34.d(), 34.d(), 28.d())
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#13306F"), Color.parseColor("#08153A"))).apply {
                cornerRadius = 32.d().toFloat(); setStroke(1.d().coerceAtLeast(1), Color.parseColor("#26FFFFFF"))
            }
        }
        addView(cartao, LayoutParams(620.d(), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    val aberto: Boolean get() = visibility == View.VISIBLE

    fun abrir() {
        if (aberto) return
        if (cartao.parent == null) addView(cartao, LayoutParams(620.d(), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setBackgroundColor(Color.parseColor("#E602060F"))
        visibility = View.VISIBLE
        alpha = 0f; animate().alpha(1f).setDuration(220).start()
        mostrarEscolha()
    }

    fun fechar() {
        contagem?.let { handler.removeCallbacks(it) }
        contagem = null
        visibility = View.GONE
    }

    private fun mostrarEscolha() {
        cartao.removeAllViews()
        cartao.addView(texto(c, "Energia", 32, Color.WHITE, true))
        cartao.addView(texto(c, "O que você quer fazer com a caixa?", 20, Color.parseColor("#B8C7EA")),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.d(); bottomMargin = 26.d() })
        val linha = LinearLayout(c)
        linha.addView(botaoGrande(R.drawable.ic_power, "Desligar", "#FF5A6A", "#B3142A") { contar(true) },
            LinearLayout.LayoutParams(0, 170.d(), 1f).apply { rightMargin = 9.d() })
        linha.addView(botaoGrande(R.drawable.ic_reiniciar, "Reiniciar", "#4F93FF", "#1747B0") { contar(false) },
            LinearLayout.LayoutParams(0, 170.d(), 1f).apply { leftMargin = 9.d() })
        cartao.addView(linha, LinearLayout.LayoutParams(-1, -2))
        cartao.addView(link("Cancelar") { fechar() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 18.d() })
    }

    private fun botaoGrande(icone: Int, rotulo: String, c1: String, c2: String, aoClicar: () -> Unit): View =
        LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(c1), Color.parseColor(c2))).apply { cornerRadius = 26.d().toFloat() }
            isClickable = true
            setOnClickListener { aoClicar() }
            efeitoToque()
            addView(icone(c, icone, 54, Color.WHITE))
            addView(texto(c, rotulo, 24, Color.WHITE, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 14.d() })
        }

    private fun link(rotulo: String, aoClicar: () -> Unit): TextView =
        texto(c, rotulo, 22, Color.parseColor("#CFD9F2")).apply {
            setPadding(30.d(), 12.d(), 30.d(), 12.d())
            isClickable = true
            setOnClickListener { aoClicar() }
            efeitoToque()
        }

    private fun contar(desligar: Boolean) {
        cartao.removeAllViews()
        val verbo = if (desligar) "Desligando" else "Reiniciando"
        cartao.addView(texto(c, if (desligar) "Desligar" else "Reiniciar", 32, Color.WHITE, true))
        val aviso = texto(c, "$verbo em 5 segundos", 20, Color.parseColor("#B8C7EA"))
        cartao.addView(aviso, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.d(); bottomMargin = 20.d() })
        val anel = AnelContagem(c, if (desligar) vermelho else azul).apply { inicio = SystemClock.uptimeMillis() }
        cartao.addView(anel, LinearLayout.LayoutParams(170.d(), 170.d()))
        cartao.addView(link("Cancelar") { fechar() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 16.d() })

        var n = 5
        val passo = object : Runnable {
            override fun run() {
                n--
                if (n <= 0) { contagem = null; executando(desligar); return }
                anel.numero = n
                aviso.text = "$verbo em $n ${if (n == 1) "segundo" else "segundos"}"
                handler.postDelayed(this, 1000)
            }
        }
        contagem = passo
        handler.postDelayed(passo, 1000)
    }

    /** Tela preta com "Desligando…" / "Reiniciando…"; um instante depois, chama de fato o desligar ou o reiniciar. */
    private fun executando(desligar: Boolean) {
        removeAllViews()
        setBackgroundColor(Color.BLACK)
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ProgressBar(c).apply { isIndeterminate = true }, LinearLayout.LayoutParams(56.d(), 56.d()))
            addView(texto(c, if (desligar) "Desligando…" else "Reiniciando…", 22, Cfg.MUDO),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = 18.d() })
        }
        addView(col, LayoutParams(-2, -2, Gravity.CENTER))
        handler.post { executar(desligar) }
    }

    /** Se o aparelho recusou (falta de permissão), volta ao normal e mostra o motivo. */
    fun falhou(motivo: String) {
        removeAllViews()
        if (cartao.parent == null) addView(cartao, LayoutParams(620.d(), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setBackgroundColor(Color.parseColor("#E602060F"))
        cartao.removeAllViews()
        cartao.addView(texto(c, "Não foi possível", 30, Color.WHITE, true))
        cartao.addView(texto(c, motivo, 20, Color.parseColor("#B8C7EA")).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = 10.d(); bottomMargin = 18.d() })
        cartao.addView(link("Fechar") { fechar() })
    }
}
