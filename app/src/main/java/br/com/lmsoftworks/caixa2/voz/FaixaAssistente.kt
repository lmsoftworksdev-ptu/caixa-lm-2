package br.com.lmsoftworks.caixa2.voz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import br.com.lmsoftworks.caixa2.R
import br.com.lmsoftworks.caixa2.d
import br.com.lmsoftworks.caixa2.texto
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Brilho colorido em volta da esfera e as ondas de choque (desenhados por baixo do vídeo). */
private class BrilhoOndas(c: Context) : View(c) {
    var nivel = 0f; var rotacao = 0f; var raio = 0f; var entrada = 0f; var pensando = false
    val ondas = ArrayList<Float>()      // instantes (em segundos) em que cada onda nasceu
    var agora = 0f
    private val cores = intArrayOf(0xFF0A84FF.toInt(), 0xFFFF375F.toInt(), 0xFF64D2FF.toInt())
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }

    override fun onDraw(canvas: Canvas) {
        if (raio <= 0f) return
        val cx = width / 2f; val cy = height * 410f / 720f
        for (i in 0 until 3) {
            val a = rotacao * (0.6f + i * 0.25f) + i * 2.1f
            val ox = cos(a) * raio * 0.5f; val oy = sin(a * 1.1f) * raio * 0.4f
            val rr = raio * (3f + 0.8f * nivel)
            val alfa = ((0.2f + 0.2f * nivel) * entrada * 255).toInt().coerceIn(0, 255)
            val cor = cores[i]
            p.shader = RadialGradient(cx + ox, cy + oy, rr, intArrayOf((cor and 0xFFFFFF) or (alfa shl 24), (cor and 0xFFFFFF) or (alfa shl 24), cor and 0xFFFFFF),
                floatArrayOf(0f, 0.3f * raio / rr, 1f), Shader.TileMode.CLAMP)
            p.style = Paint.Style.FILL
            canvas.drawCircle(cx + ox, cy + oy, rr, p)
        }
        p.shader = null; p.style = Paint.Style.STROKE
        for (nasc in ondas) {
            val u = (agora - nasc) / 1.3f
            if (u !in 0f..1f) continue
            val e = 1f - (1f - u).pow(3)
            p.color = Color.argb((0.45f * (1 - u) * entrada * 255).toInt().coerceIn(0, 255), 0xCF, 0xE0, 0xFF)
            p.strokeWidth = (2f + 5f * (1 - u)).d().toFloat()
            canvas.drawCircle(cx, cy, raio * (1f + 1.5f * e), p)
        }
    }
}

/**
 * O assistente, como na animação de referência (animacao-assistente.html): ao chamar, o fundo desfoca e escurece e no centro aparece
 * a esfera de luz do vídeo (assistente_siri.mp4, misturado com o fundo no modo "screen"), que cresce e acelera com o volume da voz,
 * com brilho colorido em volta e ondas de choque quando a voz sobe de repente. O texto fica no alto: "Ouvindo…", "Pensando…" e a
 * resposta. Tocar na tela interrompe tudo.
 * [nivelMic] dá o volume do microfone (0..1); [alvo] é a parte da tela que será desfocada.
 */
@UnstableApi
class FaixaAssistente(private val c: Context, private val nivelMic: () -> Float, private val alvo: () -> View) : FrameLayout(c) {

    private companion object {
        const val RAIO = 165f            // raio da esfera em repouso (px do quadro de 1280x720)
        const val CRESCIMENTO = 0.22f    // quanto a esfera cresce com a voz
        const val SUAVIZAR = 0.2f
        const val LADO_VIDEO = 461       // 36% da largura
    }

    private val fundo = ImageView(c)
    private val escurecer = View(c)
    private val brilho = BrilhoOndas(c)
    private val video = TextureView(c)
    /** Caixa que leva o vídeo: é ELA que mistura (modo "screen") com o fundo; a mistura direto no TextureView falhava ao reabrir. */
    private val caixaVideo = FrameLayout(c)
    private val rotulo = texto(c, "", 26, Color.parseColor("#B8C6E6"))
    private val linha = texto(c, "", 40, Color.WHITE, true)
    private var player: ExoPlayer? = null

    private var estado = 0              // 0 fechado, 1 ouvindo, 2 pensando, 3 falando
    private var nivel = 0f
    private var nivelAnt = 0f
    private var rotacao = 0f
    private var tAbre = 0f
    private var tOnda = -10f
    private var tAnterior = 0f
    private var taxaAnt = 1f
    private var brilhoAnt = -1f
    private var brilhoAtual = 1.2f; private var corAtual = 1f

    /**
     * Mistura o vídeo com o fundo no modo "screen" (o preto some). O TextureView só aceita a pintura nova se for OUTRO objeto
     * (ele compara a referência), por isso cria uma pintura nova a cada vez; senão, depois de fechar e abrir, o retângulo preto aparecia.
     */
    private fun pintura(brilho: Float = brilhoAtual, saturacao: Float = corAtual): Paint {
        brilhoAtual = brilho; corAtual = saturacao
        val m = ColorMatrix().apply { setSaturation(saturacao) }
        m.postConcat(ColorMatrix().apply { setScale(brilho, brilho, brilho, 1f) })
        return Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN); colorFilter = ColorMatrixColorFilter(m) }
    }
    private val fechar = Runnable { esconder() }
    private val parar = Runnable { if (!aberta) player?.pause() }

    /** Tocou na tela: quem usa interrompe o assistente. */
    var aoToque: (() -> Unit)? = null

    // A tela do assistente fica SEMPRE "visível" (só transparente quando fechada): se ela sumisse de verdade (GONE), o Android
    // desmontava a camada do vídeo e, ao reabrir, aparecia um retângulo preto até a mistura ser aplicada de novo.
    private var abertoFlag = false
    val aberta: Boolean get() = abertoFlag

    init {
        alpha = 0f
        isClickable = false
        setOnClickListener { aoToque?.invoke() }
        fundo.scaleType = ImageView.ScaleType.FIT_XY
        addView(fundo, LayoutParams(-1, -1))
        escurecer.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 0.56f)
            gradientRadius = 900.d().toFloat()
            colors = intArrayOf(Color.argb(128, 26, 20, 70), Color.argb(209, 3, 5, 18))
        }
        addView(escurecer, LayoutParams(-1, -1))
        addView(brilho, LayoutParams(-1, -1))
        video.isOpaque = false
        caixaVideo.addView(video, LayoutParams(-1, -1))
        caixaVideo.alpha = 0f
        caixaVideo.setLayerType(View.LAYER_TYPE_HARDWARE, pintura())
        addView(caixaVideo, LayoutParams(LADO_VIDEO.d(), LADO_VIDEO.d(), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = (410 - LADO_VIDEO / 2).d() })
        val topo = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        linha.gravity = Gravity.CENTER
        linha.maxLines = 3; linha.ellipsize = android.text.TextUtils.TruncateAt.END
        rotulo.gravity = Gravity.CENTER
        topo.addView(rotulo, LinearLayout.LayoutParams(-1, -2))
        topo.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.d() })
        addView(topo, LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = 65.d(); leftMargin = 100.d(); rightMargin = 100.d() })
    }

    /** Deixa o vídeo da esfera carregado antes do primeiro chamado (a primeira animação aparece sem demora). */
    fun preparar() {
        if (player != null) return
        val p = ExoPlayer.Builder(c).build()
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.volume = 0f
        p.setVideoTextureView(video)
        p.setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(R.raw.assistente_siri)))
        p.prepare()
        p.playWhenReady = false
        player = p
    }

    /** Foto bem pequena da tela, esticada: fica desfocada. */
    private fun desfocar() {
        try {
            val v = alvo()
            if (v.width <= 0 || v.height <= 0) return
            val pequena = Bitmap.createBitmap(v.width / 24, v.height / 24, Bitmap.Config.RGB_565)
            val cv = Canvas(pequena)
            cv.scale(1f / 24f, 1f / 24f)
            v.draw(cv)
            fundo.setImageBitmap(Bitmap.createScaledBitmap(pequena, v.width / 6, v.height / 6, true))
        } catch (e: Throwable) { fundo.setImageDrawable(null) }
    }

    private fun abrir(novo: Int) {
        removeCallbacks(fechar)
        removeCallbacks(parar)
        animate().cancel()
        if (!aberta) {
            desfocar()
            preparar()
            abertoFlag = true
            isClickable = true
            animate().alpha(1f).setDuration(550).start()
            tAbre = SystemClock.uptimeMillis() / 1000f
            tAnterior = tAbre
            nivel = 0f; nivelAnt = 0f; tOnda = -10f; brilho.ondas.clear(); taxaAnt = 1f; brilhoAnt = -1f
            // o vídeo só mistura com o fundo (modo "screen") se a camada tiver a pintura: reaplica a cada abertura, antes do 1º quadro
            caixaVideo.alpha = 0f
            caixaVideo.setLayerPaint(pintura())
            player?.seekTo(0); player?.play()
            postOnAnimation(quadro)
        }
        estado = novo
    }

    fun ouvindo(dito: String = "") {
        abrir(1)
        rotulo.text = "Ouvindo…"; linha.text = ""
        linha.setTextColor(Color.WHITE)
    }

    fun pensando(dito: String) {
        abrir(2)
        rotulo.text = "Pensando…"; linha.text = dito
        linha.setTextColor(Color.WHITE)
    }

    fun respondendo(resposta: String, dito: String = "") {
        abrir(3)
        rotulo.text = ""; linha.text = resposta
        linha.setTextColor(Color.parseColor("#D6E4FF"))
    }

    /** Fecha sozinha depois de [ms]. */
    fun fecharEm(ms: Long) { removeCallbacks(fechar); postDelayed(fechar, ms) }

    fun esconder() {
        removeCallbacks(fechar)
        if (!aberta) return
        estado = 0
        abertoFlag = false
        isClickable = false
        animate().cancel()
        animate().alpha(0f).setDuration(550).withEndAction {
            fundo.setImageDrawable(null); caixaVideo.alpha = 0f
            postDelayed(parar, 700)
        }.start()
    }

    // ---------------------------------------------------------------- animação, quadro a quadro

    private fun fala(t: Float): Float {      // volume simulado da fala da caixa (a voz online não dá o nível real)
        val sil = Math.abs(sin(t * 7.3f) * sin(t * 2.9f + 1f)); val pausa = if (sin(t * 1.15f) > -0.35f) 1f else 0.12f
        return ((0.18f + 0.75f * sil) * pausa * (0.65f + 0.35f * sin(t * 1.7f))).coerceIn(0f, 1f)
    }

    private val quadro = object : Runnable {
        override fun run() {
            if (!aberta) return
            val t = SystemClock.uptimeMillis() / 1000f
            val dt = min(0.1f, t - tAnterior); tAnterior = t
            val pensando = estado == 2
            val alvoNivel = when (estado) {
                1 -> (nivelMic() * 1.3f).coerceIn(0f, 1f)
                3 -> fala(t) * 0.85f
                2 -> 0.1f
                else -> 0.04f
            }
            nivel += (alvoNivel - nivel) * SUAVIZAR
            rotacao += dt * (if (pensando) 2.4f else 0.8f + nivel * 1.6f)

            val k = ((t - tAbre) / 0.9f).coerceIn(0f, 1f)
            val e = 1f - (1f - k).pow(3)
            val mola = 1f - exp(-6f * k) * cos(9f * k)
            val r = RAIO * (0.45f + 0.55f * mola) * (if (pensando) 0.94f + 0.04f * sin(t * 5f) else 1f + CRESCIMENTO * nivel)

            // vídeo: tamanho, velocidade, brilho e cor seguem a voz
            val taxa = if (pensando) 2.2f else 0.85f + nivel * 1.5f
            if (Math.abs(taxa - taxaAnt) > 0.04f) { try { player?.setPlaybackSpeed(taxa) } catch (_: Throwable) {}; taxaAnt = taxa }
            caixaVideo.alpha = e
            caixaVideo.scaleX = r / RAIO; caixaVideo.scaleY = r / RAIO
            val b = 1.2f + 0.55f * nivel
            if (Math.abs(b - brilhoAnt) > 0.02f) {
                brilhoAnt = b
                caixaVideo.setLayerPaint(pintura(b, 1f + 0.35f * nivel))
            }

            if (nivel > 0.55f && nivelAnt <= 0.55f && t - tOnda > 0.35f && !pensando) { brilho.ondas.add(t); tOnda = t }
            nivelAnt = nivel
            brilho.ondas.removeAll { t - it > 1.3f }
            brilho.nivel = nivel; brilho.rotacao = rotacao; brilho.raio = r.d().toFloat(); brilho.entrada = e; brilho.pensando = pensando; brilho.agora = t
            brilho.invalidate()
            postOnAnimation(this)
        }
    }
}
