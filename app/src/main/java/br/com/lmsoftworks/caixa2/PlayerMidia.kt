package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.net.URL

/**
 * O player do YouTube: um só ExoPlayer que toca música (só o áudio, com fila) ou vídeo (imagem + áudio juntados, 720p ou mais).
 * Tudo que a tela precisa saber (o que toca, se está carregando, se deu erro) fica em propriedades; [aoMudar] avisa quando muda.
 */
@UnstableApi
class PlayerMidia(contexto: Context, private val aoMudar: () -> Unit) {

    companion object { private const val TAG = "Midia" }

    private val ctx = contexto.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var geracao = 0                    // cada pedido novo invalida os anteriores que ainda estão resolvendo a URL

    var fila: List<Resultado> = emptyList(); private set
    var indice = -1; private set
    var item: Resultado? = null; private set
    var ehVideo = false; private set
    var carregando = false; private set
    var erro: String? = null; private set
    var capa: Bitmap? = null; private set
    var tocando = false; private set
    /** Já apareceu um quadro do vídeo atual? Antes disso a tela fica preta (a superfície ainda guarda o último quadro do vídeo anterior). */
    var primeiroQuadro = false; private set
    private var recuperacoes = 0
    private var retomarEm = 0L
    private var relacionados: List<Resultado> = emptyList()      // o que o YouTube sugere depois: toca quando a fila acaba
    private var superficie: SurfaceView? = null

    val ativo: Boolean get() = item != null
    val duracaoMs: Long get() = player?.duration?.takeIf { it > 0 } ?: ((item?.duracaoSeg ?: 0L) * 1000L)
    val posicaoMs: Long get() = player?.currentPosition ?: 0L
    val sessaoAudio: Int get() = player?.audioSessionId ?: 0

    private fun criar(): ExoPlayer {
        player?.let { return it }
        val controle = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 120_000, 10_000, 20_000)      // só começa com 10 s guardados; depois de uma parada, espera 20 s
            .setTargetBufferBytes(56 * 1024 * 1024)
            .build()
        val p = ExoPlayer.Builder(ctx).setLoadControl(controle).build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) Log.d(TAG, "carregando: posicao=${p.currentPosition / 1000}s, guardado adiante=${(p.bufferedPosition - p.currentPosition) / 1000}s")
                if (state == Player.STATE_BUFFERING) carregando = true
                else if (state == Player.STATE_READY) { carregando = false; if (p.currentPosition > 20_000) recuperacoes = 0 }
                if (state == Player.STATE_ENDED) {
                    if (temProxima) proxima() else { tocando = false; aoMudar() }
                    return
                }
                aoMudar()
            }
            override fun onRenderedFirstFrame() { primeiroQuadro = true; aoMudar() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { tocando = isPlaying; aoMudar() }
            override fun onPlayerError(e: PlaybackException) {
                Log.w(TAG, "erro no player", e)
                // endereço vencido ou rede que caiu: pede um novo e retoma de onde parou (até 3 vezes)
                val r = item
                if (r != null && recuperacoes < 3) { recuperacoes++; retomarEm = p.currentPosition; iniciarItem(r, ehVideo, true); return }
                carregando = false; tocando = false; erro = "Não foi possível reproduzir"; aoMudar()
            }
        })
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onBandwidthEstimate(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, ms: Int, bytes: Long, bps: Long) {
                Log.d(TAG, "rede: ${bps / 1_000_000.0} Mbps estimados")
            }
        })
        player = p
        superficie?.let { p.setVideoSurfaceView(it) }
        return p
    }

    // ---------------------------------------------------------------- pedidos

    fun tocarMusica(lista: List<Resultado>, i: Int) {
        fila = lista; indice = i
        iniciarItem(lista[i], false)
    }

    fun tocarVideo(lista: List<Resultado>, i: Int) {
        fila = lista; indice = i
        iniciarItem(lista[i], true)
    }

    private fun iniciarItem(r: Resultado, video: Boolean, recuperando: Boolean = false) {
        if (!recuperando) { recuperacoes = 0; retomarEm = 0L }
        val minha = ++geracao
        item = r; ehVideo = video; erro = null; carregando = true; tocando = false; capa = null; relacionados = emptyList(); primeiroQuadro = false
        player?.stop(); player?.clearMediaItems()
        aoMudar()
        carregarCapa(r, minha)
        Thread {
            try {
                val fonte = YouTube.resolver(r.url, soAudio = !video)
                handler.post { if (minha == geracao) { relacionados = fonte.relacionados; tocarFonte(fonte); aoMudar() } }
            } catch (e: Exception) {
                Log.w(TAG, "nao foi possivel abrir ${r.url}", e)
                handler.post { if (minha == geracao) { carregando = false; erro = "Não foi possível abrir"; aoMudar() } }
            }
        }.start()
    }

    private fun carregarCapa(r: Resultado, minha: Int) {
        if (r.miniaturas.isEmpty()) return
        Thread {
            try {
                var bytes: ByteArray? = null
                for (url in r.miniaturas) {
                    try { bytes = URL(url).openConnection().apply { connectTimeout = 5000; readTimeout = 8000 }.getInputStream().use { it.readBytes() }; break } catch (e: Exception) { }
                }
                if (bytes == null) return@Thread
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size) ?: return@Thread
                // o YouTube manda 16:9 (às vezes com tarja preta em cima e embaixo): recorta o centro, quadrado, para a capa
                val lado = minOf(b.width, b.height)
                val quadrada = Bitmap.createBitmap(b, (b.width - lado) / 2, (b.height - lado) / 2, lado, lado)
                handler.post { if (minha == geracao) { capa = quadrada; aoMudar() } }
            } catch (e: Exception) { Log.w(TAG, "sem capa", e) }
        }.start()
    }

    private fun tocarFonte(fonte: Fonte) {
        val p = criar()
        val origem: MediaSource = if (fonte.unicaUrl != null) {
            fabricaPara(fonte.unicaUrl).createMediaSource(MediaItem.fromUri(fonte.unicaUrl))
        } else {
            MergingMediaSource(
                fabricaPara(fonte.videoUrl!!).createMediaSource(MediaItem.fromUri(fonte.videoUrl)),
                fabricaPara(fonte.audioUrl!!).createMediaSource(MediaItem.fromUri(fonte.audioUrl))
            )
        }
        p.setMediaSource(origem, retomarEm)
        retomarEm = 0L
        p.prepare()
        p.playWhenReady = true
    }

    /** As URLs do YouTube só aceitam o "nome de aplicativo" (User-Agent) do cliente que as gerou (c= na URL). */
    private fun agenteDe(url: String): String {
        val cliente = Uri.parse(url).getQueryParameter("c").orEmpty()
        return try {
            when (cliente) {
                "ANDROID", "ANDROID_VR", "ANDROID_TESTSUITE" -> org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getAndroidUserAgent(null)
                "IOS" -> org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getIosUserAgent(null)
                "VISIONOS" -> org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getVisionOsUserAgent(null)
                else -> AppDownloader.USER_AGENT
            }
        } catch (e: Throwable) { AppDownloader.USER_AGENT }
    }

    private fun fabricaPara(url: String): ProgressiveMediaSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(agenteDe(url))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val uri = Uri.parse(url)
        val emPedacos = uri.host?.contains("googlevideo.com") == true && uri.getQueryParameter("clen") != null
        val dados: androidx.media3.datasource.DataSource.Factory =
            if (emPedacos) androidx.media3.datasource.DataSource.Factory { YoutubeChunkedDataSource(http) }
            else DefaultDataSource.Factory(ctx, http)
        return ProgressiveMediaSource.Factory(dados)
    }

    // ---------------------------------------------------------------- controles

    fun alternar() {
        val p = player ?: return
        if (erro != null) { item?.let { iniciarItem(it, ehVideo) }; return }
        if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
    }

    val temProxima: Boolean get() = indice + 1 < fila.size || relacionados.isNotEmpty()
    val temAnterior: Boolean get() = indice > 0

    /** Próximo da lista; no fim dela, segue pelas sugestões do YouTube (como o autoplay de lá). */
    fun proxima() {
        if (indice + 1 >= fila.size && relacionados.isNotEmpty()) { fila = fila + relacionados; relacionados = emptyList() }
        if (indice + 1 < fila.size) { indice++; iniciarItem(fila[indice], ehVideo) }
    }

    fun anterior() {
        if (posicaoMs > 4000 || indice == 0) player?.seekTo(0)
        else { indice--; iniciarItem(fila[indice], ehVideo) }
    }

    fun pular(ms: Long) { player?.let { it.seekTo((it.currentPosition + ms).coerceIn(0, duracaoMs.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)) }; aoMudar() }

    fun buscar(ms: Long) { player?.seekTo(ms.coerceAtLeast(0)); aoMudar() }

    fun pausar() { player?.pause() }

    /** Volume do player (0..1): o assistente abaixa enquanto escuta e fala. */
    fun volume(v: Float) { player?.volume = v }

    /** A imagem do vídeo vai para esta superfície (some da tela quando é null). */
    fun ligarImagem(v: SurfaceView?) {
        val antiga = superficie
        superficie = v
        if (v != null) player?.setVideoSurfaceView(v) else if (antiga != null) player?.clearVideoSurfaceView(antiga)
    }

    fun parar() {
        geracao++
        player?.stop(); player?.clearMediaItems()
        item = null; fila = emptyList(); indice = -1; carregando = false; tocando = false; erro = null; capa = null; ehVideo = false; primeiroQuadro = false
        aoMudar()
    }

    fun liberar() {
        geracao++
        player?.release(); player = null
    }
}
