package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.com.lmsoftworks.caixa2.voz.Respostas
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Tipo de ícone animado do clima ("sol", "nublado" ou "chuva") para o código do tempo do Open-Meteo. */
fun Respostas.ClimaHoje.tipo(): String = when (codigo) {
    0, 1 -> "sol"
    in 51..67, in 80..82, in 95..99 -> "chuva"
    else -> "nublado"
}

fun Respostas.ClimaHoje.descricao(): String = when (codigo) {
    0, 1 -> "Ensolarado"
    2 -> "Parcialmente nublado"
    3 -> "Nublado"
    45, 48 -> "Neblina"
    in 51..57 -> "Garoa"
    in 61..67 -> "Chuva"
    in 71..77 -> "Neve"
    in 80..82 -> "Pancadas de chuva"
    in 95..99 -> "Tempestade"
    else -> "Tempo"
}

/**
 * Botão do Início da v3: quadrado de 104 com degradê a 145°, linha clara no topo interno, leve escurecido embaixo e reflexo
 * colorido desfocado abaixo (desenhado uma vez como bitmap). Sem texto; o ícone fica no centro (50, traço 1,9).
 */
class BotaoInicio(c: Context, private val cor1: Int, private val cor2: Int, icone: View, descricao: String) : FrameLayout(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val area = RectF()
    private var reflexo: Bitmap? = null
    private var degrade: LinearGradient? = null
    private var topo: LinearGradient? = null
    private var base: LinearGradient? = null
    private val folga = 40.d()

    init {
        setWillNotDraw(false)
        isClickable = true
        contentDescription = descricao
        clipChildren = false
        addView(icone, LayoutParams(50.d(), 50.d(), Gravity.CENTER))
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(.92f).scaleY(.92f).setDuration(120).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            false
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(104.d(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(104.d(), MeasureSpec.EXACTLY))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        area.set(0f, 0f, w.toFloat(), h.toFloat())
        // gradiente a 145° (CSS): direção (sen, -cos) = (.574, .819)
        val dx = .574f * w * .5f * 1.35f; val dy = .819f * h * .5f * 1.35f
        degrade = LinearGradient(w / 2f - dx, h / 2f - dy, w / 2f + dx, h / 2f + dy, cor1, cor2, Shader.TileMode.CLAMP)
        topo = LinearGradient(0f, 0f, 0f, 14.d().toFloat(), Color.parseColor("#73FFFFFF"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        base = LinearGradient(0f, h - 24.d().toFloat(), 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(46, 0, 0, 0), Shader.TileMode.CLAMP)
        // reflexo: elipse da cor (alfa 55%) 22 abaixo, desfocada, desenhada uma vez
        val fx = folga + 40.d()
        val img = Bitmap.createBitmap(w + 2 * fx, 30.d() + 2 * fx, Bitmap.Config.ARGB_8888)
        val pc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, Color.red(cor2), Color.green(cor2), Color.blue(cor2))
            maskFilter = BlurMaskFilter(14.d().toFloat(), BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(img).drawOval(RectF(fx + 14.d().toFloat(), fx.toFloat(), fx + w - 14.d().toFloat(), fx + 30.d().toFloat()), pc)
        reflexo = img
    }

    override fun onDraw(canvas: Canvas) {
        val raio = 32.d().toFloat()
        reflexo?.let { val fx = folga + 40.d(); canvas.drawBitmap(it, -fx.toFloat(), (height - 8.d() - fx).toFloat(), null) }
        p.shader = degrade; p.style = Paint.Style.FILL
        canvas.drawRoundRect(area, raio, raio, p)
        p.shader = base
        canvas.drawRoundRect(area, raio, raio, p)
        p.shader = topo; p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f.d().coerceAtLeast(1f)
        canvas.drawRoundRect(RectF(.75f, .75f, width - .75f, height - .75f), raio, raio, p)
        p.shader = null; p.style = Paint.Style.FILL
    }
}

/**
 * Tela Início da v3: relógio (Outfit 208) centralizado sobre a fileira de 6 botões, data abaixo, e à direita um cartão de
 * informações de altura fixa que empilha, a partir do topo, só os itens que existem (clima, próximo alarme, próximo lembrete,
 * tocando agora). Toda linha é tocável.
 */
class TelaInicio(private val c: Context, private val aoAbrir: (String) -> Unit) : FrameLayout(c) {

    private val relogio = texto(c, "00:00", 208, Color.WHITE).apply {
        typeface = Tema.OUTFIT
        letterSpacing = -4f / 208f
        gravity = Gravity.CENTER
        fontFeatureSettings = "tnum"
        setShadowLayer(40.d().toFloat(), 0f, 6.d().toFloat(), Color.argb(97, 61, 139, 255))
    }
    private val data = texto(c, "", 25, Color.parseColor("#C9D4EE")).apply { gravity = Gravity.CENTER; letterSpacing = .01f }
    private val formatoData = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR"))
    private var sombraAtual = -1

    // ----- itens do cartão -----
    private class Item(val id: String, val linha: LinearLayout, val titulo: TextView, val sub: TextView)

    private val cartao = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = cartaoVidro()
        setPadding(26.d(), 6.d(), 26.d(), 6.d())
    }

    private val corAlarme = Color.parseColor("#C3A8FF")
    private val corLembrete = Color.parseColor("#FFC877")
    private val corMusica = Color.parseColor("#5BE3A6")

    private var clima: Respostas.ClimaHoje? = null
    private var climaViews: Array<View>? = null
    private var alarmeTxt: String? = null; private var alarmeNome: String? = null
    private var lembreteTxt: String? = null; private var lembreteQuando: String? = null
    private var musicaTxt: String? = null; private var musicaArtista: String? = null
    private var chave = ""

    init {
        clipChildren = false
        clipToPadding = false
        addView(relogio, LayoutParams(714.d(), -2).apply { leftMargin = 64.d(); topMargin = 78.d() })
        addView(data, LayoutParams(714.d(), -2).apply { leftMargin = 64.d(); topMargin = 321.d() })

        val fila = LinearLayout(c).apply { clipChildren = false; clipToPadding = false }
        val botoes = listOf(
            Triple("midia", "Mídia", "#FF7A5C" to "#D3264B"), Triple("led", "Luzes LED", "#FFC94D" to "#EE7A16"),
            Triple("alarmes", "Alarmes", "#B48CFF" to "#6232E0"), Triple("wifi", "Wi-Fi", "#3DE6C6" to "#0B8E9E"),
            Triple("bluetooth", "Bluetooth", "#6E9BFF" to "#2443D6"), Triple("config", "Configurações", "#9AA6BE" to "#46506A")
        )
        val icones = mapOf("led" to R.drawable.ic_bulb, "alarmes" to R.drawable.ic_alarme, "wifi" to R.drawable.ic_wifi,
            "bluetooth" to R.drawable.ic_bluetooth, "config" to R.drawable.ic_config)
        for ((i, b) in botoes.withIndex()) {
            val icone: View = if (b.first == "midia") IconeMidia(c) else ImageView(c).apply { setImageResource(icones.getValue(b.first)); setColorFilter(Color.WHITE) }
            val bt = BotaoInicio(c, Color.parseColor(b.third.first), Color.parseColor(b.third.second), icone, b.second)
            bt.setOnClickListener { aoAbrir(b.first) }
            fila.addView(bt, LinearLayout.LayoutParams(104.d(), 104.d()).apply { if (i > 0) leftMargin = 18.d() })
        }
        addView(fila, LayoutParams(-2, -2).apply { leftMargin = 64.d(); topMargin = (720 - 62 - 104).d() })

        addView(cartao, LayoutParams(420.d(), (720 - 104 - 62).d()).apply { leftMargin = 804.d(); topMargin = 104.d() })
        atualizar()
        refazer()
    }

    // ----- construção das linhas -----
    private fun caixaIcone(cor: Int, fundo: Int, conteudo: View): FrameLayout = FrameLayout(c).apply {
        background = fundoArredondado(fundo, 18.d())
        addView(conteudo, LayoutParams(conteudo.layoutParams?.width ?: -2, conteudo.layoutParams?.height ?: -2, Gravity.CENTER))
    }

    private fun linha(id: String, icone: View, corte: Boolean, miolo: View): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 20.d(), 0, 20.d())
        isClickable = true
        addView(icone, LinearLayout.LayoutParams(56.d(), 56.d()))
        addView(miolo, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 18.d() })
        setOnClickListener { aoAbrir(id) }
        efeitoToque()
    }

    private fun textoLinha(a: String, b: String): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(texto(c, a, 25, Tema.TEXTO, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        addView(texto(c, b, 18, Tema.TEXTO2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 3.d() })
    }

    private fun linhaClima(cl: Respostas.ClimaHoje): View {
        val tipo = cl.tipo()
        val (cor, fundo) = when (tipo) {
            "sol" -> Color.parseColor("#FFC94D") to Color.parseColor("#29FFBE3C")
            "chuva" -> Color.parseColor("#9CC8FF") to Color.parseColor("#294FAAFF")
            else -> Color.parseColor("#C9D4EE") to Color.parseColor("#24C8D4EE")
        }
        val miolo = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            val topo = LinearLayout(c).apply {
                gravity = Gravity.BOTTOM
                addView(texto(c, "${cl.temp}°", 44, Tema.TEXTO).apply { typeface = FONTE_LEVE })
                addView(texto(c, cl.descricao(), 25, Tema.TEXTO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 12.d(); bottomMargin = 4.d() })
            }
            addView(topo)
            if (cl.cidade.isNotBlank()) addView(texto(c, cl.cidade, 18, Color.parseColor("#DDE5F7")), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 3.d() })
            val detalhe = listOfNotNull(cl.min?.let { "Mín $it°" }, cl.max?.let { "Máx $it°" }, cl.chuva?.let { "Chuva $it%" }).joinToString(" · ")
            if (detalhe.isNotEmpty()) addView(texto(c, detalhe, 18, Tema.TEXTO2).apply { maxLines = 1 }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 3.d() })
        }
        return linha("clima", caixaIcone(cor, fundo, IconeAnimado(c, tipo, cor, 30)), false, miolo)
    }

    /** Refaz o cartão só com os itens que existem; quem some libera o lugar e os de baixo sobem (sem buraco no meio). */
    private fun refazer() {
        val nova = "${clima?.temp}|${clima?.codigo}|${clima?.min}|${clima?.max}|${clima?.chuva}|${clima?.cidade}|$alarmeTxt|$alarmeNome|$lembreteTxt|$lembreteQuando|$musicaTxt|$musicaArtista"
        if (nova == chave) return
        chave = nova
        cartao.removeAllViews()
        val linhas = ArrayList<View>()
        clima?.let { linhas.add(linhaClima(it)) }
        alarmeTxt?.let {
            linhas.add(linha("alarmes", caixaIcone(corAlarme, Color.parseColor("#2E8B5CFF"), icone(c, R.drawable.ic_alarme, 28, corAlarme)), false,
                textoLinha(it, "Próximo alarme" + (alarmeNome?.let { n -> " · $n" } ?: ""))))
        }
        lembreteTxt?.let {
            linhas.add(linha("lembretes", caixaIcone(corLembrete, Color.parseColor("#29FFBE5A"), icone(c, R.drawable.ic_sino, 28, corLembrete)), false,
                textoLinha(it, "Lembrete" + (lembreteQuando?.let { q -> " · ${q.replaceFirstChar { ch -> ch.lowercase() }}" } ?: ""))))
        }
        musicaTxt?.let {
            val eq = IconeAnimado(c, "eq", corMusica, 56)
            linhas.add(linha("musica", FrameLayout(c).apply {
                background = fundoArredondado(Color.parseColor("#2946DC96"), 18.d())
                addView(eq, LayoutParams(56.d(), 56.d()))
            }, false, textoLinha(it, (musicaArtista?.let { a -> "$a · " } ?: "") + "tocando agora")))
        }
        for ((i, l) in linhas.withIndex()) {
            cartao.addView(l, LinearLayout.LayoutParams(-1, -2))
            if (i < linhas.size - 1) cartao.addView(View(c).apply { setBackgroundColor(Cfg.DIVISOR) }, LinearLayout.LayoutParams(-1, 1.d().coerceAtLeast(1)))
        }
    }

    /** Próximo alarme: [t] chega como "Amanhã às 07:00"; [nome] é o rótulo do alarme. */
    fun definirProximoAlarme(t: String?, nome: String? = null) { alarmeTxt = t; alarmeNome = nome; refazer() }

    /** Clima de hoje (some se ainda não há previsão). */
    fun definirClima(cl: Respostas.ClimaHoje?) { clima = cl; refazer() }

    /** Próximo lembrete: [texto] e quando ("hoje às 09:00"); some se não há nenhum. */
    fun definirLembrete(texto: String?, quando: String?) { lembreteTxt = texto; lembreteQuando = quando; refazer() }

    /** Tocando agora: [titulo] e [artista]; nulo some com o item. */
    fun definirMusica(titulo: String?, artista: String?) { musicaTxt = titulo?.ifBlank { null }; musicaArtista = artista?.ifBlank { null }; refazer() }

    /** Cor da sombra do relógio (muda com a hora do dia). */
    fun definirSombra(rgb: Int) {
        if (rgb == sombraAtual) return
        sombraAtual = rgb
        relogio.setShadowLayer(40.d().toFloat(), 0f, 6.d().toFloat(), Color.argb(97, Color.red(rgb), Color.green(rgb), Color.blue(rgb)))
    }

    fun atualizar() {
        val agora = Date()
        val cal = Calendar.getInstance()
        val hm = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        if (relogio.text.toString() != hm) relogio.text = hm
        val d = formatoData.format(agora).replaceFirstChar { it.uppercase() }
        if (data.text.toString() != d) data.text = d
    }
}
