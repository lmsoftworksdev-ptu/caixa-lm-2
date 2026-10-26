package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.media3.common.util.UnstableApi

/** Rodinha de "carregando" (arco branco girando). */
class Carregando(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
    private val area = RectF()
    private var angulo = 0f
    private val gira = object : Runnable {
        override fun run() { angulo = (angulo + 9f) % 360f; invalidate(); postOnAnimation(this) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postOnAnimation(gira) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(gira) }

    override fun onDraw(canvas: Canvas) {
        p.strokeWidth = 6.d().toFloat()
        val m = p.strokeWidth
        area.set(m, m, width - m, height - m)
        canvas.drawArc(area, angulo, 270f, false, p)
    }
}

/**
 * Player de vídeo em tela cheia, por cima de tudo. Um toque mostra ou esconde os controles (play/pause, barra de tempo e o X
 * para fechar). Os controles somem sozinhos depois de alguns segundos tocando.
 */
@UnstableApi
class TelaVideo(private val c: Context, private val player: PlayerMidia, private val aoFechar: () -> Unit) : FrameLayout(c) {

    private val imagem = SurfaceView(c)
    private val cortina = View(c).apply { setBackgroundColor(Color.BLACK) }
    private var cortinaForcada = false
    private val controles = FrameLayout(c)
    private val titulo = texto(c, "", 30, Color.WHITE, true)
    private val canal = texto(c, "", 19, Color.parseColor("#D6E0F7"))
    private val botaoPlay = FrameLayout(c)
    private val iconePlay = ImageView(c)
    private val barra = BarraProgresso(c)
    private val volume = BarraProgresso(c)
    private var botaoAnterior: View? = null
    private var botaoProxima: View? = null
    private val tempoAtual = texto(c, "0:00", 20, Color.WHITE)
    private val tempoTotal = texto(c, "", 20, Color.WHITE)
    private val rodinha = Carregando(c)
    private val mensagem = LinearLayout(c)
    private val textoErro = texto(c, "", 28, Color.WHITE, true)
    private val handler = Handler(Looper.getMainLooper())
    private val esconder = Runnable { mostrarControles(false) }
    private val tique = object : Runnable {
        override fun run() { atualizarTempo(); handler.postDelayed(this, 500) }
    }
    private var controlesVisiveis = true

    val aberto: Boolean get() = visibility == View.VISIBLE

    init {
        visibility = View.GONE
        setBackgroundColor(Color.BLACK)
        isClickable = true
        addView(imagem, LayoutParams(-1, -1))
        addView(cortina, LayoutParams(-1, -1))

        // ---- controles (mesmo desenho do protótipo: título em cima; barra, botões, tempo e volume embaixo) ----
        val degradeTopo = View(c).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT)) }
        val degradeBase = View(c).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.parseColor("#E6000000"), Color.TRANSPARENT)) }
        controles.addView(degradeTopo, LayoutParams(-1, 130.d(), Gravity.TOP))
        controles.addView(degradeBase, LayoutParams(-1, 250.d(), Gravity.BOTTOM))

        titulo.maxLines = 1; titulo.ellipsize = TextUtils.TruncateAt.END
        canal.maxLines = 1; canal.ellipsize = TextUtils.TruncateAt.END
        val textos = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; addView(titulo); addView(canal, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4.d() }) }
        controles.addView(textos, LayoutParams(-1, -2, Gravity.TOP).apply { leftMargin = 40.d(); rightMargin = 40.d(); topMargin = 22.d() })

        fun redondo(recurso: Int, lado: Int, icone: Int, fundo: Int, aoClicar: () -> Unit): FrameLayout = FrameLayout(c).apply {
            background = fundoArredondado(fundo, (lado / 2).d(), if (fundo == Color.WHITE) 0 else Cfg.LINHA)
            isClickable = true
            setOnClickListener { aoClicar(); agendarEsconder() }
            efeitoToque()
            addView(icone(c, recurso, icone, Color.WHITE), LayoutParams(icone.d(), icone.d(), Gravity.CENTER))
        }
        val vidro = Color.parseColor("#22FFFFFF")
        val anterior = redondo(R.drawable.ic_anterior, 68, 30, vidro) { player.anterior() }
        val voltar10 = redondo(R.drawable.ic_voltar10, 68, 30, vidro) { player.pular(-10_000) }
        botaoPlay.apply {
            background = fundoArredondado(Color.WHITE, 42.d())      // igual ao da música: fundo branco, ícone preto
            isClickable = true
            setOnClickListener { player.alternar(); agendarEsconder() }
            efeitoToque()
            iconePlay.setColorFilter(Color.BLACK)
            addView(iconePlay, LayoutParams(40.d(), 40.d(), Gravity.CENTER))
        }
        val avancar10 = redondo(R.drawable.ic_avancar10, 68, 30, vidro) { player.pular(10_000) }
        val proxima = redondo(R.drawable.ic_proxima, 68, 30, vidro) { player.proxima() }
        botaoAnterior = anterior; botaoProxima = proxima
        val linhaBotoes = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val lp68 = { LinearLayout.LayoutParams(68.d(), 68.d()).apply { rightMargin = 22.d() } }
        linhaBotoes.addView(anterior, lp68())
        linhaBotoes.addView(voltar10, lp68())
        linhaBotoes.addView(botaoPlay, LinearLayout.LayoutParams(84.d(), 84.d()).apply { rightMargin = 22.d() })
        linhaBotoes.addView(avancar10, lp68())
        linhaBotoes.addView(proxima, lp68())
        linhaBotoes.addView(tempoAtual.apply { setPadding(10.d(), 0, 0, 0) }, LinearLayout.LayoutParams(-2, -2))
        linhaBotoes.addView(tempoTotal, LinearLayout.LayoutParams(-2, -2))
        linhaBotoes.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        linhaBotoes.addView(icone(c, R.drawable.ic_volume_2, 34, Color.WHITE), LinearLayout.LayoutParams(34.d(), 34.d()).apply { rightMargin = 6.d() })
        linhaBotoes.addView(volume, LinearLayout.LayoutParams(200.d(), 44.d()))

        barra.aoArrastar = { f -> if (player.duracaoMs > 0) tempoAtual.text = formatar((f * player.duracaoMs).toLong()); agendarEsconder() }
        barra.aoSoltar = { f -> if (player.duracaoMs > 0) player.buscar((f * player.duracaoMs).toLong()) }
        val audio = c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val limite = Math.round(audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) * PainelVolume.LIMITE_REAL).coerceAtLeast(1)
        volume.fracao = Math.min(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC), limite).toFloat() / limite
        volume.aoArrastar = { f -> audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(f * limite), 0); agendarEsconder() }
        volume.aoSoltar = volume.aoArrastar

        val base = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(barra, LinearLayout.LayoutParams(-1, 44.d()))
            addView(linhaBotoes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.d() })
        }
        controles.addView(base, LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = 30.d(); rightMargin = 40.d(); bottomMargin = 26.d() })
        addView(controles, LayoutParams(-1, -1))

        // ---- carregando / erro ----
        addView(rodinha, LayoutParams(70.d(), 70.d(), Gravity.CENTER))
        mensagem.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(textoErro)
            addView(botao(c, "Tentar de novo", Cfg.AZUL) { player.alternar() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 20.d() })
        }
        addView(mensagem, LayoutParams(-2, -2, Gravity.CENTER))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) mostrarControles(!controlesVisiveis)
        return true
    }

    fun abrir() {
        visibility = View.VISIBLE
        player.ligarImagem(imagem)
        mostrarControles(true)
        handler.removeCallbacks(tique); handler.post(tique)
        atualizar()
    }

    /** Cobre a imagem de preto (a superfície de vídeo não gira junto com a tela no efeito de cubo). */
    fun cobrir(sim: Boolean) { cortinaForcada = sim; cortina.visibility = if (sim || !player.primeiroQuadro) View.VISIBLE else View.GONE }

    private val amostra = android.graphics.Bitmap.createBitmap(48, 27, android.graphics.Bitmap.Config.ARGB_8888)
    private var amostrando = false
    private var pretosSeguidos = 0

    /**
     * Copia um quadro pequeno da imagem do vídeo e entrega a cor que mais aparece (a mesma conta da capa do álbum; se o quadro
     * for cinza ou escuro, a cor média). Usado pelo modo Ambilight da fita.
     */
    fun amostrarCor(aoCor: (Int) -> Unit) {
        if (!player.primeiroQuadro) { aoCor(Color.BLACK); return }      // carregando outro vídeo: tela preta, fita apagada
        if (amostrando || !player.tocando || player.carregando || cortinaForcada) return
        amostrando = true
        try {
            android.view.PixelCopy.request(imagem, amostra, { resultado ->
                amostrando = false
                if (resultado != android.view.PixelCopy.SUCCESS) return@request
                var r = 0L; var g = 0L; var b = 0L
                val px = IntArray(48 * 27)
                amostra.getPixels(px, 0, 48, 0, 0, 48, 27)
                for (p in px) { r += Color.red(p); g += Color.green(p); b += Color.blue(p) }
                val n = px.size
                // Cor média do quadro, com mais saturação e brilho: muda de forma contínua (a "cor que mais aparece" pulava de uma
                // cor para outra entre dois quadros e a fita piscava). Tela preta: brilho zero, a fita apaga.
                val hsv = FloatArray(3)
                Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
                hsv[1] = Math.min(1f, hsv[1] * 1.5f)
                hsv[2] = Math.min(1f, hsv[2] * 2f)
                // um quadro preto isolado (a cópia da imagem às vezes vem vazia) não pode apagar a fita: só vale se se repetir
                if (hsv[2] < 0.05f) { pretosSeguidos++; if (pretosSeguidos < 3) return@request } else pretosSeguidos = 0
                aoCor(Color.HSVToColor(hsv))
            }, handler)
        } catch (e: Exception) { amostrando = false }
    }

    fun fechar() {
        cortinaForcada = false
        handler.removeCallbacks(tique); handler.removeCallbacks(esconder)
        player.ligarImagem(null)
        visibility = View.GONE
    }

    private fun mostrarControles(mostrar: Boolean) {
        controlesVisiveis = mostrar
        handler.removeCallbacks(esconder)
        controles.animate().cancel()
        if (mostrar) { controles.visibility = View.VISIBLE; controles.animate().alpha(1f).setDuration(150).start(); agendarEsconder() }
        else controles.animate().alpha(0f).setDuration(220).withEndAction { if (!controlesVisiveis) controles.visibility = View.GONE }.start()
    }

    private fun agendarEsconder() {
        handler.removeCallbacks(esconder)
        if (player.tocando) handler.postDelayed(esconder, 3500)
    }

    private fun formatar(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)
    }

    private fun atualizarTempo() {
        val dur = player.duracaoMs
        tempoAtual.text = formatar(player.posicaoMs)
        tempoTotal.text = if (dur > 0) " / " + formatar(dur) else ""
        barra.fracao = if (dur > 0) player.posicaoMs.toFloat() / dur else 0f
        barra.ativa = dur > 0
    }

    /** Chamada quando o player muda de estado. */
    fun atualizar() {
        if (!aberto) return
        cortina.visibility = if (cortinaForcada || !player.primeiroQuadro) View.VISIBLE else View.GONE
        val r = player.item
        titulo.text = r?.titulo ?: ""
        canal.text = r?.canal ?: ""
        iconePlay.setImageResource(if (player.tocando) R.drawable.ic_pausar else R.drawable.ic_tocar)
        val erro = player.erro
        rodinha.visibility = if (player.carregando && erro == null) View.VISIBLE else View.GONE
        mensagem.visibility = if (erro != null) View.VISIBLE else View.GONE
        textoErro.text = erro ?: ""
        botaoAnterior?.alpha = if (player.temAnterior || player.posicaoMs > 4000) 1f else .4f
        botaoProxima?.alpha = if (player.temProxima) 1f else .4f
        botaoPlay.visibility = View.VISIBLE      // sempre aparece: pausa/play enquanto toca; play no meio quando parado ou carregando
        if (erro != null || player.carregando) { mostrarControles(true); controles.alpha = 1f }
        else if (player.tocando) agendarEsconder()
        atualizarTempo()
    }
}
