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

/**
 * Texto de uma linha que, se for maior que o campo, desliza sozinho (devagar, com uma pausa no começo) para o usuário ler tudo.
 * As pontas somem num degradê (desfoque) em vez de serem cortadas de uma vez.
 */
class TextoRolante(c: Context, private val texto: String, tamanho: Int, cor: Int, media: Boolean = false) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = tamanho.d().toFloat(); color = cor; typeface = if (media) FONTE_MEDIA else FONTE_NORMAL
    }
    private val fade = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
    private val largura = p.measureText(texto)
    private val folga = 70.d().toFloat()
    private val borda = 30.d().toFloat()
    private val inicio = android.os.SystemClock.uptimeMillis()
    private var gradDir: LinearGradient? = null
    private var gradEsq: LinearGradient? = null

    private companion object { const val VELOCIDADE = 38f; const val PAUSA_MS = 1800L }      // px por segundo

    override fun onMeasure(w: Int, h: Int) {
        val m = p.fontMetricsInt
        setMeasuredDimension(MeasureSpec.getSize(w), m.descent - m.ascent + 2.d())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        gradDir = LinearGradient(w - borda, 0f, w.toFloat(), 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        gradEsq = LinearGradient(0f, 0f, borda, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val base = -p.fontMetrics.ascent + 1.d()
        if (largura <= width) { canvas.drawText(texto, 0f, base, p); return }
        val ciclo = largura + folga
        val periodo = PAUSA_MS + (ciclo / VELOCIDADE * 1000f).toLong()
        val m = (android.os.SystemClock.uptimeMillis() - inicio) % periodo
        val off = if (m < PAUSA_MS) 0f else (m - PAUSA_MS) / 1000f * VELOCIDADE
        val camada = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawText(texto, -off, base, p)
        canvas.drawText(texto, -off + ciclo, base, p)
        fade.shader = gradDir
        canvas.drawRect(width - borda, 0f, width.toFloat(), height.toFloat(), fade)
        if (off > 0f) { fade.shader = gradEsq; canvas.drawRect(0f, 0f, borda, height.toFloat(), fade) }
        canvas.restoreToCount(camada)
        if (isShown && !Animacao.pausada) postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postInvalidateOnAnimation() }
}

/** Capa pequena (88) com cantos arredondados: a imagem da música ou o degradê da faixa. */
class CapaMini(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val area = RectF()
    var imagem: Bitmap? = null
        set(v) { field = v; invalidate() }
    var semente = ""
        set(v) { field = v; invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(88.d(), 88.d())

    override fun onDraw(canvas: Canvas) {
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        val raio = 18.d().toFloat()
        val img = imagem
        if (img != null) {
            val sh = android.graphics.BitmapShader(img, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            sh.setLocalMatrix(android.graphics.Matrix().apply { setScale(width / img.width.toFloat(), height / img.height.toFloat()) })
            p.shader = sh
        } else {
            val h = CapaView.matiz(semente)
            p.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), CorCapa.hsl(h, .82f, .48f), CorCapa.hsl(h + 35f, .72f, .32f), Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(area, raio, raio, p)
        p.shader = null
        // sem imagem: o mesmo ícone de música da tela Tocando agora, por cima do degradê
        if (img == null) nota?.let {
            val t = 44.d()
            val cx = width / 2; val cy = height / 2
            it.setBounds(cx - t / 2, cy - t / 2, cx + t / 2, cy + t / 2)
            it.setTint(Color.argb(190, 255, 255, 255))
            it.draw(canvas)
        }
    }
    private val nota = c.getDrawable(R.drawable.ic_music)?.mutate()
}

/** Tipo de ícone animado do clima ("sol", "nublado" ou "chuva") para o código do tempo do Open-Meteo. */
fun Respostas.ClimaHoje.tipo(): String = tipoDoCodigo(codigo, noite)

fun Respostas.ClimaHoje.descricao(): String = descricaoDoCodigo(codigo, noite)

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

    private companion object { const val LARGURA_FILEIRA = 694 }

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
    private var musicaCapa: Bitmap? = null; private var musicaTocando = false
    private var semDadosModo = false      // o celular toca sem mandar os dados: o cartão mostra a arte do equalizador e só o nome do aparelho
    /** Toque no play/pause do cartão de música. */
    var aoPlayPause: (() -> Unit)? = null
    private var chave = ""
    private var padV = 20      // espaço de cima/baixo de cada linha do cartão (menor quando a lista de compras está presente, para caber)
    private var avisoId: String? = null; private var avisoTitulo: String? = null; private var avisoDetalhe: String? = null
    private var listaNome: String? = null; private var listaItens: List<String> = emptyList()
    private val corAviso = Color.parseColor("#FF6B7A")
    private val corLista = Color.parseColor("#7CE0A0")

    init {
        clipChildren = false
        clipToPadding = false
        // a hora e a data ficam centralizadas sobre a fileira dos 6 botões (6 × 104 + 5 × 14 = 694)
        addView(relogio, LayoutParams(LARGURA_FILEIRA.d(), -2).apply { leftMargin = 64.d(); topMargin = 78.d() })
        addView(data, LayoutParams(LARGURA_FILEIRA.d(), -2).apply { leftMargin = 64.d(); topMargin = 321.d() })

        val fila = LinearLayout(c).apply { clipChildren = false; clipToPadding = false }
        val botoes = listOf(
            Triple("midia", "Mídia", "#FF7A5C" to "#D3264B"), Triple("radios", "Rádios", "#5FDCEB" to "#1B8FB8"), Triple("amplificador", "Equalizador", "#FF7AC0" to "#C2257D"),
            Triple("alarmes", "Alarmes", "#B48CFF" to "#6232E0"), Triple("receitas", "Receitas", "#FF9A3D" to "#E8731F"),
            Triple("config", "Configurações", "#9AA6BE" to "#46506A")
        )
        val icones = mapOf("amplificador" to R.drawable.ic_equalizador, "radios" to R.drawable.ic_radio, "alarmes" to R.drawable.ic_alarme,
            "receitas" to R.drawable.ic_chef, "config" to R.drawable.ic_config)
        for ((i, b) in botoes.withIndex()) {
            val icone: View = if (b.first == "midia") IconeMidia(c) else ImageView(c).apply { setImageResource(icones.getValue(b.first)); setColorFilter(Color.WHITE) }
            val bt = BotaoInicio(c, Color.parseColor(b.third.first), Color.parseColor(b.third.second), icone, b.second)
            bt.setOnClickListener { aoAbrir(b.first) }
            fila.addView(bt, LinearLayout.LayoutParams(104.d(), 104.d()).apply { if (i > 0) leftMargin = 14.d() })
        }
        addView(fila, LayoutParams(-2, -2).apply { leftMargin = 64.d(); topMargin = (720 - 62 - 104).d() })

        // o cartão ocupa todo o espaço que sobrou até a margem direita (1224)
        val esquerdaCartao = 64 + LARGURA_FILEIRA + 48
        addView(cartao, LayoutParams((1224 - esquerdaCartao).d(), (720 - 104 - 62).d()).apply { leftMargin = esquerdaCartao.d(); topMargin = 104.d() })
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
        setPadding(0, padV.d(), 0, padV.d())
        isClickable = true
        addView(icone, LinearLayout.LayoutParams(56.d(), 56.d()))
        addView(miolo, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 18.d() })
        setOnClickListener { aoAbrir(id) }
        efeitoToque()
    }

    private fun textoLinha(a: String, b: String): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextoRolante(c, a, 25, Tema.TEXTO, true), LinearLayout.LayoutParams(-1, -2))
        addView(TextoRolante(c, b, 18, Tema.TEXTO2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 3.d() })
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
                addView(TextoRolante(c, cl.descricao(), 25, Tema.TEXTO),
                    LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 12.d(); bottomMargin = 4.d() })
            }
            addView(topo)
            if (cl.cidade.isNotBlank()) addView(TextoRolante(c, cl.cidade, 18, Color.parseColor("#DDE5F7")), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 3.d() })
            val detalhe = listOfNotNull(cl.min?.let { "Mín $it°" }, cl.max?.let { "Máx $it°" }, cl.chuva?.let { "Chuva $it%" }).joinToString(" · ")
            if (detalhe.isNotEmpty()) addView(TextoRolante(c, detalhe, 18, Tema.TEXTO2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 3.d() })
        }
        return linha("clima", caixaIcone(cor, fundo, IconeClima(c, tipo, 46)), false, miolo)
    }

    private fun linhaMusica(titulo: String): View {
        val capa: View = if (semDadosModo) ArteSemDados(c, 9, 18, 2, 14).apply { tocando = musicaTocando }
        else CapaMini(c).apply { imagem = musicaCapa; semente = titulo + (musicaArtista ?: "") }
        val textos = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextoRolante(c, titulo, if (semDadosModo) 30 else 25, Tema.TEXTO, true), LinearLayout.LayoutParams(-1, -2))
            // o artista fica parado (se não couber, termina em "…"); sem os dados da música, só o nome do aparelho
            if (!semDadosModo) addView(texto(c, musicaArtista ?: "Tocando agora", 18, Tema.TEXTO2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = 3.d() })
        }
        val play = FrameLayout(c).apply {
            background = fundoArredondado(Color.WHITE, 30.d())
            isClickable = true
            contentDescription = if (musicaTocando) "Pausar" else "Tocar"
            setOnClickListener { aoPlayPause?.invoke() }
            efeitoToque()
            addView(icone(c, if (musicaTocando) R.drawable.ic_pausar else R.drawable.ic_tocar, 30, Color.parseColor("#0A1020")), LayoutParams(30.d(), 30.d(), Gravity.CENTER))
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, padV.d(), 0, padV.d())
            isClickable = true
            addView(capa, LinearLayout.LayoutParams(88.d(), 88.d()))
            addView(textos, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 16.d(); rightMargin = 12.d() })
            addView(play, LinearLayout.LayoutParams(60.d(), 60.d()))
            setOnClickListener { aoAbrir("musica") }
            deslizarParaFechar(this)
        }
    }

    /** Pede para fechar o player (a música para): o usuário arrastou o cartão de música para a esquerda. */
    var aoFecharMusica: (() -> Unit)? = null

    /** Arrastar o cartão de música para a esquerda (mais de ~120 px) o fecha, como dispensar uma notificação; um toque simples abre a música. */
    private fun deslizarParaFechar(v: View) {
        var x0 = 0f; var y0 = 0f; var capturado = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; capturado = false; false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (!capturado && dx < -18.d() && Math.abs(dx) > Math.abs(dy)) {
                        capturado = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        view.cancelLongPress(); view.isPressed = false
                    }
                    if (capturado) {
                        view.translationX = Math.min(0f, dx)
                        view.alpha = (1f + dx / view.width).coerceIn(.2f, 1f)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP -> if (capturado) {
                    capturado = false
                    if (view.translationX < -120.d()) {
                        view.animate().translationX(-view.width.toFloat()).alpha(0f).setDuration(160)
                            .withEndAction { aoFecharMusica?.invoke() }.start()
                    } else view.animate().translationX(0f).alpha(1f).setDuration(160).start()
                    true
                } else false
                MotionEvent.ACTION_CANCEL -> { if (capturado) { capturado = false; view.animate().translationX(0f).alpha(1f).setDuration(160).start() }; false }
                else -> false
            }
        }
    }

    /** Refaz o cartão só com os itens que existem; quem some libera o lugar e os de baixo sobem (sem buraco no meio). */
    private fun refazer() {
        val nova = "${clima?.temp}|${clima?.codigo}|${clima?.min}|${clima?.max}|${clima?.chuva}|${clima?.cidade}|$alarmeTxt|$alarmeNome|$lembreteTxt|$lembreteQuando|$musicaTxt|$musicaArtista|$musicaTocando|${musicaCapa?.hashCode()}|$semDadosModo|$avisoId|$avisoTitulo|$avisoDetalhe|$listaNome|${listaItens.joinToString("¦")}"
        if (nova == chave) return
        chave = nova
        cartao.removeAllViews()
        val linhas = ArrayList<View>()
        padV = if (listaNome != null && listaItens.isNotEmpty()) 12 else 20
        // aviso de falha: sempre no alto, para ser visto
        if (avisoId != null && avisoTitulo != null) {
            val id = "aviso:$avisoId"
            linhas.add(linha(id, caixaIcone(corAviso, Color.parseColor("#33FF4D5E"), icone(c, R.drawable.ic_alerta, 28, corAviso)), false, textoLinha(avisoTitulo!!, avisoDetalhe ?: "")))
        }
        clima?.let { linhas.add(linhaClima(it)) }
        // tocando agora: cartão logo abaixo do clima (miniatura, título, artista e play/pause); alarme e lembrete ficam depois
        musicaTxt?.let { linhas.add(linhaMusica(it)) }
        alarmeTxt?.let {
            linhas.add(linha("alarme-proximo", caixaIcone(corAlarme, Color.parseColor("#2E8B5CFF"), icone(c, R.drawable.ic_alarme, 28, corAlarme)), false,
                textoLinha(it, "Próximo alarme" + (alarmeNome?.let { n -> " · $n" } ?: ""))))
        }
        lembreteTxt?.let {
            linhas.add(linha("lembretes", caixaIcone(corLembrete, Color.parseColor("#29FFBE5A"), icone(c, R.drawable.ic_sino, 28, corLembrete)), false,
                textoLinha(it, "Lembrete" + (lembreteQuando?.let { q -> " · ${q.replaceFirstChar { ch -> ch.lowercase() }}" } ?: ""))))
        }
        val temLista = listaNome != null && listaItens.isNotEmpty()
        for ((i, l) in linhas.withIndex()) {
            cartao.addView(l, LinearLayout.LayoutParams(-1, -2))
            if (i < linhas.size - 1 || temLista) cartao.addView(View(c).apply { setBackgroundColor(Cfg.DIVISOR) }, LinearLayout.LayoutParams(-1, 1.d().coerceAtLeast(1)))
        }
        montarLista()
    }

    /** Mostra a lista de compras no fim do cartão: só os itens pendentes; o que não couber rola com o dedo. */
    private fun montarLista() {
        val nome = listaNome ?: return
        if (listaItens.isEmpty()) return
        val caixa = CartaoLista(nome, listaItens)
        cartao.addView(caixa, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** Cartão da lista de compras: título e contagem em cima, e os itens numa área que rola. Ocupa só o espaço que sobrou no cartão (até 190). */
    private inner class CartaoLista(nome: String, itens: List<String>) : LinearLayout(c) {
        init {
            orientation = VERTICAL
            isClickable = true
            setPadding(0, 12.d(), 0, 12.d())
            val cab = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(caixaIcone(corLista, Color.parseColor("#2A7CE0A0"), icone(c, R.drawable.ic_lista, 24, corLista)), LinearLayout.LayoutParams(44.d(), 44.d()))
                addView(texto(c, nome, 25, Tema.TEXTO, true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 16.d() })
                addView(texto(c, if (itens.size == 1) "1 item" else "${itens.size} itens", 18, Tema.TEXTO2))
            }
            addView(cab, LinearLayout.LayoutParams(-1, -2))
            val rol = RolagemFina(c)
            val coluna = LinearLayout(c).apply { orientation = VERTICAL }
            for (it in itens) coluna.addView(
                texto(c, "•  " + it.replaceFirstChar { ch -> ch.uppercase() }, 22, Tema.TEXTO2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = 7.d() }
            )
            rol.addView(coluna, FrameLayout.LayoutParams(-1, -2))
            rol.setOnClickListener { aoAbrir("listas") }
            addView(rol, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 4.d(); leftMargin = 60.d() })
            setOnClickListener { aoAbrir("listas") }
            efeitoToque()
        }

        override fun onMeasure(w: Int, h: Int) {
            val max = 190.d()
            val modo = MeasureSpec.getMode(h); val tam = MeasureSpec.getSize(h)
            super.onMeasure(w, if (modo == MeasureSpec.EXACTLY) MeasureSpec.makeMeasureSpec(Math.min(tam, max), MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
        }
    }

    /** Próximo alarme: [t] chega como "Amanhã às 07:00"; [nome] é o rótulo do alarme. */
    fun definirProximoAlarme(t: String?, nome: String? = null) { alarmeTxt = t; alarmeNome = nome; refazer() }

    /** Clima de hoje (some se ainda não há previsão). */
    fun definirClima(cl: Respostas.ClimaHoje?) { clima = cl; refazer() }

    /** Aviso de falha (ver [Falhas]); [id] nulo some com ele. */
    fun definirAviso(id: String?, titulo: String?, detalhe: String?) { avisoId = id; avisoTitulo = titulo; avisoDetalhe = detalhe; refazer() }

    /** Lista de compras: [nome] nulo (ou sem itens) some com o cartão; [itens] são os pendentes. */
    fun definirLista(nome: String?, itens: List<String>) { listaNome = nome; listaItens = itens; refazer() }

    /** Próximo lembrete: [texto] e quando ("hoje às 09:00"); some se não há nenhum. */
    fun definirLembrete(texto: String?, quando: String?) { lembreteTxt = texto; lembreteQuando = quando; refazer() }

    /** Tocando agora: [titulo] e [artista]; nulo some com o item. */
    fun definirMusica(titulo: String?, artista: String?, capa: Bitmap? = null, tocando: Boolean = false) {
        musicaTxt = titulo?.ifBlank { null }; musicaArtista = artista?.ifBlank { null }; musicaCapa = capa; musicaTocando = tocando
        semDadosModo = false
        refazer()
    }

    /** O celular toca mas não mandou os dados da música: o cartão mostra a arte do equalizador e só o nome do aparelho. */
    fun definirMusicaSemDados(nome: String, tocando: Boolean) {
        musicaTxt = nome; musicaArtista = null; musicaCapa = null; musicaTocando = tocando
        semDadosModo = true
        refazer()
    }

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
        formatoData.timeZone = java.util.TimeZone.getDefault()      // o fuso pode mudar em Configurações
        val d = formatoData.format(agora).replaceFirstChar { it.uppercase() }
        if (data.text.toString() != d) data.text = d
    }
}
