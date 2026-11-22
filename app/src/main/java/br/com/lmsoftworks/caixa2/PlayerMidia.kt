package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
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
    /** O que toca é uma rádio da internet (fluxo ao vivo, sem duração nem fila). */
    val ehRadio: Boolean get() = !ehVideo && item?.let { ehFluxoDireto(it.url) && !ehArquivoLocal(it.url) } == true
    /** O que toca é um canal de TV ao vivo (vídeo de endereço direto, fora do YouTube). */
    val ehCanalTv: Boolean get() = ehVideo && item?.let { ehFluxoDireto(it.url) } == true
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

    /** A qualidade da imagem que está sendo mostrada agora, como "720p · H.264"; vazio se ainda não há imagem. */
    val qualidadeVideo: String get() {
        val f = player?.videoFormat ?: return ""
        val altura = minOf(f.width, f.height).takeIf { it > 0 } ?: return ""      // pela menor medida: 1280x720 e 720x1280 são os dois "720p"
        val codec = when (f.sampleMimeType) { "video/avc" -> "H.264"; "video/x-vnd.on2.vp9" -> "VP9"; "video/av01" -> "AV1"; "video/hevc" -> "H.265"; else -> "" }
        return "${altura}p" + if (codec.isNotEmpty()) " · $codec" else ""
    }

    /** Cria o ExoPlayer antes de precisar: montá-lo na primeira troca música <-> vídeo travava a tela e o esmaecimento saía sem animação. */
    fun aquecer() { criar() }

    private fun criar(): ExoPlayer {
        player?.let { return it }
        val p = novoExo()
        player = p
        superficie?.let { p.setVideoSurfaceView(it) }
        return p
    }

    /** O mesmo controle de carga de sempre, com um teto opcional do que guardar à frente (a pré-carga do próximo vídeo guarda só uns 20 s). */
    private class ControleCarga : DefaultLoadControl(
        androidx.media3.exoplayer.upstream.DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
        50_000, 60_000, 10_000, 20_000,      // só começa com 10 s guardados; depois de uma parada, espera 20 s; guarda no máximo 60 s à frente
        28 * 1024 * 1024, false, 0, false      // o Moto G3 tem 1 GB: com 56 MB de buffer o sistema matava o app por falta de memória
    ) {
        @Volatile var tetoUs = Long.MAX_VALUE
        /** Só som (música do YouTube): o fluxo é leve (~20 KB/s), então começa com 3 s guardados em vez de 10 s. Nas paradas depois de começar, a regra de sempre. */
        @Volatile var somLeve = false
        override fun shouldStartPlayback(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean =
            if (somLeve && !parameters.rebuffering) parameters.bufferedDurationUs >= 3_000_000L else super.shouldStartPlayback(parameters)
        override fun shouldContinueLoading(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean =
            if (parameters.bufferedDurationUs >= tetoUs) false else super.shouldContinueLoading(parameters)
    }

    private var ultimoControle: ControleCarga? = null
    private val efeitosLocais = HashMap<ExoPlayer, Amplificador.SessaoLocal>()
    private val controlesDe = HashMap<ExoPlayer, ControleCarga>()

    /** Endereço do item que está de fato carregado no player agora (null enquanto o novo ainda está sendo achado): ver onRenderedFirstFrame. */
    @Volatile private var urlNoPlayer: String? = null

    private fun novoExo(): ExoPlayer {
        val controle = ControleCarga()
        ultimoControle = controle
        val software = EqualizadorSoftware()
        val efeitos = Amplificador.SessaoLocal(software)
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                // SEM o contexto: com ele o ExoPlayer registra um "ouvinte de aparelhos de áudio" que, a cada mudança de saída (fone Bluetooth
                // saindo), faz o Android listar as portas de áudio NA TELA PRINCIPAL; com o Bluetooth desligando isso levou 10 s (ANR de 21/11).
                // A saída é sempre PCM comum, não precisa de passagem direta (HDMI/surround).
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder()
                    .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(software))
                    .build()
        }
        val p = ExoPlayer.Builder(ctx, renderers).setLoadControl(controle).build()
        efeitosLocais[p] = efeitos
        controlesDe[p] = controle
        p.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                efeitosLocais[p]?.ligar(audioSessionId)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (p !== player) return
                if (state == Player.STATE_BUFFERING) Log.d(TAG, "carregando: posicao=${p.currentPosition / 1000}s, guardado adiante=${(p.bufferedPosition - p.currentPosition) / 1000}s")
                if (state == Player.STATE_BUFFERING) carregando = true
                else if (state == Player.STATE_READY) { carregando = false; if (p.currentPosition > 20_000) recuperacoes = 0 }
                if (state == Player.STATE_ENDED) {
                    if (proximoPlayer != null && preparada != null) { concluirFade(); return }      // a próxima já estava carregada: segue nela
                    if (temProxima) avancar() else { tocando = false; aoMudar() }
                    return
                }
                aoMudar()
            }
            // Só vale o primeiro quadro do vídeo que foi PEDIDO: ao trocar de vídeo, o player antigo ainda desenha (e, ao religar a
            // superfície, avisa "primeiro quadro" de novo) e a tela mostrava a imagem velha enquanto o vídeo novo carregava.
            override fun onRenderedFirstFrame() { if (p === player && urlNoPlayer != null && urlNoPlayer == item?.url) { primeiroQuadro = true; aoMudar() } }
            override fun onMediaMetadataChanged(m: androidx.media3.common.MediaMetadata) {
                // rádio: a música que está passando agora (o fluxo manda "Artista - Música") aparece no lugar do nome da cidade
                if (p !== player || !ehRadio) return
                val t = m.title?.toString()?.trim().orEmpty()
                if (t.isNotEmpty() && t != item?.titulo) { item = item?.copy(canal = t); aoMudar() }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) efeitosLocais[p]?.ligar(p.audioSessionId)
                if (p === player) {
                    tocando = isPlaying
                    if (isPlaying) {
                        if (fadeAoComecarMs > 0) {
                            Log.d(TAG, "Reprodução começou com fade-in de ${fadeAoComecarMs} ms")
                            rampa(0f, 1f, fadeAoComecarMs) { }
                        }
                        aoComecou?.invoke()
                    }
                    aoMudar()
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                if (p !== player) { if (p === proximoPlayer) cancelarFade() else if (p === preVideo?.player) liberarPreVideo(); return }
                Log.w(TAG, "erro no player", e)
                // A thread do player ficou presa no decodificador de vídeo (flush/troca de superfície sem resposta): esse player não volta mais, e
                // tentar de novo nele deixava o vídeo carregando para sempre. Descarta e segue com um player novo.
                // Falha do próprio decodificador (HW_OVERLOAD, "Decoder failed"): o player fica com o decodificador quebrado ("Disable failed" ao
                // parar) e não o devolve ao sistema; as tentativas seguintes no mesmo player falhavam todas. Também é descartado.
                if (generateSequence<Throwable>(e) { it.cause }.any { it is androidx.media3.exoplayer.ExoTimeoutException || it is androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException }) {
                    Log.w(TAG, "player travado no decodificador: trocando por um novo")
                    player = null
                    liberarExo(p)
                }
                // endereço vencido ou rede que caiu: pede um novo e retoma de onde parou (até 3 vezes)
                val r = item
                if (r != null && recuperacoes < 3) { recuperacoes++; retomarEm = p.currentPosition; iniciarItem(r, ehVideo, true); return }
                carregando = false; tocando = false; erro = if (ehCanalTv) "Canal indisponível no momento" else "Não foi possível reproduzir"; aoMudar()
            }
        })
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onBandwidthEstimate(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, ms: Int, bytes: Long, bps: Long) {
                YouTube.medirBanda(bps); Log.d(TAG, "rede: ${bps / 1_000_000.0} Mbps estimados")
            }
        })
        return p
    }

    private fun liberarExo(p: ExoPlayer?) {
        if (p == null) return
        efeitosLocais.remove(p)?.desligar()
        controlesDe.remove(p)
        val t0 = SystemClock.uptimeMillis()
        try { p.release() } catch (_: RuntimeException) {}
        val ms = SystemClock.uptimeMillis() - t0
        if (ms > 200L) Log.w(TAG, "liberar o player levou $ms ms (o decodificador pode ter ficado preso)")
    }

    // ---------------------------------------------------------------- pedidos

    /** [inicioMs] começa já nesse ponto; [tocar] = false deixa pausado (usado na troca música <-> vídeo do mesmo clipe). */
    fun tocarMusica(lista: List<Resultado>, i: Int, inicioMs: Long = 0L, tocar: Boolean = true) {
        Log.d("FilaDiag", "tocarMusica: ${lista.size} itens, i=$i, '${lista.getOrNull(i)?.titulo}' (fila antes: ${fila.size}, '${fila.firstOrNull()?.titulo}')")
        fila = lista; indice = i
        comecarTocando = tocar
        iniciarItem(lista[i], false)
        retomarEm = inicioMs
    }

    fun tocarVideo(lista: List<Resultado>, i: Int, inicioMs: Long = 0L, tocar: Boolean = true) {
        fila = lista; indice = i
        comecarTocando = tocar
        iniciarItem(lista[i], true)
        retomarEm = inicioMs
    }

    private var comecarTocando = true

    // ---------------------------------------------------------------- troca música <-> clipe sem esperar

    private val videoPre = LinkedHashMap<String, Fonte>()
    private val videoPreEm = HashMap<String, Long>()      // quando o endereço foi descoberto (os do YouTube vencem em umas 6 horas)
    private val resolvendoVideo = HashSet<String>()
    private var resolvidoEm = 0L                           // quando terminou o último pedido (um de cada vez, com pausa)
    private var falhasSeguidas = 0
    private val VALIDADE_ENDERECO_MS = 90L * 60_000L

    /** O endereço guardado deste vídeo, se ainda vale. */
    private fun enderecoGuardado(url: String): Fonte? {
        val f = videoPre[url] ?: return null
        if (SystemClock.uptimeMillis() - (videoPreEm[url] ?: 0L) > VALIDADE_ENDERECO_MS) { videoPre.remove(url); videoPreEm.remove(url); return null }
        return f
    }

    /** O endereço guardado da faixa de som: o mesmo pré-pedido do vídeo traz o áudio junto, então a música também abre sem esperar os ~2 s. */
    private fun audioGuardado(url: String): Fonte? {
        val f = enderecoGuardado(url) ?: return null
        val a = f.audioUrl ?: return null
        return f.copy(videoUrl = null, audioUrl = null, unicaUrl = a)
    }

    /** Resolve o endereço do vídeo antes de ele ser pedido, para ele abrir mais rápido. */
    fun prefetchVideo(r: Resultado) {
        if (enderecoGuardado(r.url) != null || !resolvendoVideo.add(r.url)) return
        Tarefas.executar {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            var f: Fonte? = null
            try { f = YouTube.resolver(r.url, soAudio = false) } catch (e: Exception) { }
            handler.post {
                resolvendoVideo.remove(r.url)
                resolvidoEm = SystemClock.uptimeMillis()
                if (f != null) {
                    falhasSeguidas = 0
                    videoPre[r.url] = f; videoPreEm[r.url] = SystemClock.uptimeMillis()
                    while (videoPre.size > 80) { val velho = videoPre.keys.first(); videoPre.remove(velho); videoPreEm.remove(velho) }
                } else falhasSeguidas++
            }
        }
    }

    /**
     * Descobre o endereço dos vídeos da lista perto do atual, um de cada vez e com pausa entre eles (o YouTube bloqueia quem pede demais de
     * uma vez): primeiro os que vêm depois, depois os de antes. Se um pedido falhar, espera mais antes do próximo.
     */
    private fun resolverFila() {
        if (resolvendoVideo.isNotEmpty()) return
        val agora = SystemClock.uptimeMillis()
        // 5 s entre pedidos (uns 12 por minuto, bem abaixo de uma pessoa trocando de vídeo sem parar); depois de uma falha, 1 minuto; depois de duas, 3
        val pausa = when { falhasSeguidas >= 2 -> 180_000L; falhasSeguidas == 1 -> 60_000L; else -> 5_000L }
        if (agora - resolvidoEm < pausa) return
        // só uma janela perto do atual (25 à frente, 5 atrás): a janela anda conforme os vídeos passam
        val ordem = (indice + 1 until minOf(fila.size, indice + 26)) + (indice - 1 downTo maxOf(indice - 5, 0))
        val alvo = ordem.map { fila[it] }.firstOrNull { enderecoGuardado(it.url) == null } ?: return
        prefetchVideo(alvo)
    }

    // ---------------------------------------------------------------- pré-carga do próximo vídeo
    //
    // Enquanto um vídeo toca, o endereço dos próximos é descoberto antes (etapa 1) e, se a internet tem folga (o vídeo atual já tem 20 s
    // guardados à frente), o seguinte começa a carregar em outro player, parado, com no máximo 20 s (etapa 2). Na troca a imagem passa
    // para ele e o vídeo começa na hora. Com internet lenta a etapa 2 nunca começa, para não tirar banda do vídeo que está passando.

    private class PreVideo(val item: Resultado, val fonte: Fonte, val player: ExoPlayer, val controle: ControleCarga)

    private var preVideo: PreVideo? = null

    private fun liberarPreVideo() {
        val pv = preVideo ?: return
        preVideo = null
        liberarExo(pv.player)
    }

    private fun verificarPreVideo() {
        val p = player ?: return
        if (ehCanalTv || !tocando || erro != null || !primeiroQuadro || repetindo || p.currentPosition < 3000) return      // canal de TV: nada de pré-carga
        val proximo = fila.getOrNull(indice + 1) ?: relacionadosNovos().firstOrNull() ?: return
        // etapa 1: descobre o endereço dos dois próximos (só texto, quase não gasta internet)
        prefetchVideo(proximo)      // o seguinte primeiro, sem pausa
        resolverFila()
        // etapa 2: só com folga de internet
        if (preVideo != null) { if (preVideo!!.item.url != proximo.url) liberarPreVideo(); return }
        val aFrente = p.bufferedPosition - p.currentPosition
        if (aFrente < 20_000) return
        // memória: o segundo player e o segundo decodificador só entram se sobrar bastante; com pouca memória o sistema mata o app (isso já aconteceu)
        val mem = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mem)
        if (mem.lowMemory || mem.availMem < 300L * 1024 * 1024) return
        val fonte = enderecoGuardado(proximo.url) ?: return
        val novo = novoExo()
        val controle = ultimoControle ?: return
        controle.tetoUs = 20_000_000L
        novo.volume = 0f
        novo.setMediaSource(origemDe(fonte))
        novo.playWhenReady = false
        novo.prepare()
        preVideo = PreVideo(proximo, fonte, novo, controle)
        Log.d(TAG, "pré-carga: ${proximo.titulo} (o atual tem ${aFrente / 1000}s guardados)")
    }

    /** O vídeo pedido já está carregado em outro player: ele assume a imagem e o som, e começa na hora. */
    private fun assumirPreVideo(pv: PreVideo, r: Resultado) {
        val antigo = player
        val novo = pv.player
        superficie?.let { antigo?.clearVideoSurfaceView(it) }
        player = novo
        pv.controle.tetoUs = Long.MAX_VALUE      // daqui em diante guarda o que precisar, como qualquer vídeo
        urlNoPlayer = r.url
        // O decodificador do vídeo ANTERIOR é liberado ANTES de o novo receber a imagem (é isso que abre o decodificador do novo). Antes os dois
        // ficavam abertos juntos por um instante e, com um vídeo de 60 quadros no meio, passavam do que o chip do G3 aguenta (HW_OVERLOAD): o novo
        // não abria e, pior, o decodificador antigo ficava preso e TODOS os vídeos seguintes falhavam até reiniciar o app.
        liberarExo(antigo)
        superficie?.let { novo.setVideoSurfaceView(it) }
        relacionados = pv.fonte.relacionados; modoMusica = pv.fonte.musica; infoExtra = infoDe(pv.fonte)
        novo.repeatMode = Player.REPEAT_MODE_OFF
        val vaiTocar = comecarTocando
        comecarTocando = true
        cancelarRampa(); fracaoVolume = 1f
        if (vaiTocar) tocarComFade(novo, 600L) else novo.volume = volumeBase
        nivelador.novaFaixa(); nivelador.ligar(novo.audioSessionId)
        carregando = novo.playbackState != Player.STATE_READY
        tocando = novo.isPlaying
        Log.d(TAG, "pré-carga usada: ${r.titulo} (${novo.bufferedPosition / 1000}s já guardados)")
        aoMudar()
    }

    /** Troca o que está tocando: o som atual desce em fade (500 ms) antes de a nova faixa ou vídeo entrar. */
    private fun iniciarItem(r: Resultado, video: Boolean, recuperando: Boolean = false) {
        // As sugestões do YouTube que ainda estão guardadas são as da faixa ANTERIOR. Elas só eram limpas depois do fade de saída (0,5 s), e nesse
        // tempo a fila de uma faixa só ganhava as sugestões antigas no fim: a lista de próximas misturava o estilo velho com o novo.
        if (!recuperando) { relacionados = emptyList(); chaveRelacionados = null }
        val atual = player
        if (!recuperando && atual != null && atual.isPlaying && fracaoVolume > 0.05f) {
            if (emFade) cancelarFade()
            // o card já mostra a faixa nova enquanto a antiga some no volume (senão ficava a antiga por meio segundo e só depois trocava)
            item = r; ehVideo = video; erro = null; carregando = true
            infoExtra = ""
            capa = capas.get(r.url) ?: capaProvisoria?.takeIf { it.first == r.url }?.second
            primeiroQuadro = false
            aoMudar()
            rampa(fracaoVolume, 0f, 500L) { val ret = retomarEm; iniciarItemAgora(r, video, recuperando); retomarEm = ret }
            return
        }
        iniciarItemAgora(r, video, recuperando)
    }

    private fun iniciarItemAgora(r: Resultado, video: Boolean, recuperando: Boolean = false) {
        retomarAposAssistente = false
        if (!recuperando) { recuperacoes = 0; retomarEm = 0L }
        // pega o foco de áudio JÁ, antes de achar o endereço da faixa (leva uns segundos): senão, quando o assistente fecha e solta o dele, o
        // foco volta para o Bluetooth, que manda "tocar" ao celular, e a música do celular volta por cima da que foi pedida
        try { audioMgr.requestAudioFocus(foco, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN) } catch (e: Throwable) { }
        val minha = ++geracao
        cancelarRampa(); fracaoVolume = 1f
        // retomando depois de um erro (endereço vencido, 403): descarta o endereço guardado, senão o mesmo endereço ruim é usado de novo nas 3 tentativas
        if (recuperando) { videoPre.remove(r.url); videoPreEm.remove(r.url) }
        val pronta = if (recuperando) null else preparada?.takeIf { it.item.url == r.url && !video }
        val videoPronto = if (recuperando) null else if (video) enderecoGuardado(r.url) else audioGuardado(r.url)
        cancelarFade()
        item = r; ehVideo = video; erro = null; carregando = true; tocando = false
        infoExtra = ""      // visualizações e data só aparecem quando o dado verdadeiro chegar (o da lista de busca pode vir errado)
        // a capa já conhecida (ou a provisória) fica na tela: sem passar por um quadrado de cor sólida
        capa = capas.get(r.url) ?: capaProvisoria?.takeIf { it.first == r.url }?.second
        relacionados = emptyList(); primeiroQuadro = false
        val pv = preVideo
        if (pv != null) {
            preVideo = null
            if (video && !recuperando && pv.item.url == r.url && pv.player.bufferedPosition > 3000) {
                carregarCapa(r, minha)
                assumirPreVideo(pv, r)
                return
            }
            liberarExo(pv.player)
        }
        player?.stop(); player?.clearMediaItems()
        urlNoPlayer = null
        aoMudar()
        carregarCapa(r, minha)
        Tarefas.executar {
            try {
                val t0 = SystemClock.uptimeMillis()
                // música é música e vídeo é vídeo: o áudio vem sempre da própria faixa pedida
                val fonte = if (ehFluxoDireto(r.url)) Fonte(null, null, r.url, musica = true) else (pronta?.fonte ?: videoPronto ?: YouTube.resolver(r.url, soAudio = !video))
                Log.d(TAG, "faixa '${r.titulo}': abrir ${if (video) "o vídeo" else "o áudio"} ${SystemClock.uptimeMillis() - t0} ms")
                if (!ehFluxoDireto(r.url)) Falhas.youtubeOk()
                handler.post { if (minha == geracao) { relacionados = fonte.relacionados; modoMusica = fonte.musica; infoExtra = infoDe(fonte); tocarFonte(fonte); aoMudar() } }
            } catch (e: Exception) {
                Log.w(TAG, "nao foi possivel abrir ${r.url}", e)
                if (!ehFluxoDireto(r.url)) Falhas.youtubeFalhou(e)
                handler.post { if (minha == geracao) { carregando = false; erro = "Não foi possível reproduzir"; aoMudar() } }
            }
        }
    }

    /** Capas já baixadas (por endereço da faixa): ao voltar para uma música, a capa aparece na hora, sem o quadrado de cor sólida. */
    private val capas = android.util.LruCache<String, Bitmap>(8)
    private var capaProvisoria: Pair<String, Bitmap>? = null

    /** Capa para mostrar enquanto a de verdade ainda não chegou (ex.: a imagem do vídeo de onde a música veio). */
    fun usarCapaProvisoria(url: String, b: Bitmap) { capaProvisoria = url to b }

    private fun carregarCapa(r: Resultado, minha: Int) {
        capas.get(r.url)?.let { capa = it; return }
        if (r.miniaturas.isEmpty()) return
        val ehMusicaAgora = !ehVideo
        Tarefas.executar {
            try {
                var bytes: ByteArray? = null
                // música: primeiro a capa de música de verdade (quadrada, do YouTube Música); o quadro do vídeo fica de reserva
                // a mesma regra da lista de Próximas (ver Miniaturas.carregarDeMusica); se a busca da capa de música falhar, tenta de novo antes de desistir
                var daMusica: List<String> = emptyList()
                if (ehMusicaAgora && !ehArquivoLocal(r.url)) {
                    var achada: List<String>? = null
                    for (tentativa in 0 until 3) { achada = YouTube.capaDeMusicaOuNull(r, prioritario = true); if (achada != null) break; try { Thread.sleep(2500L) } catch (e: InterruptedException) { } }
                    daMusica = achada ?: emptyList()
                }
                val enderecos = daMusica + r.miniaturas
                for (url in enderecos) {
                    try { bytes = URL(url).openConnection().apply { connectTimeout = 5000; readTimeout = 8000 }.getInputStream().use { it.readBytes() }; break } catch (e: Exception) { }
                }
                if (bytes == null) return@executar
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size) ?: return@executar
                // o YouTube manda 16:9 (às vezes com tarja preta em cima e embaixo): recorta o centro, quadrado, para a capa
                val lado = minOf(b.width, b.height)
                val recorte = Bitmap.createBitmap(b, (b.width - lado) / 2, (b.height - lado) / 2, lado, lado)
                // a capa aparece em 400 px: 512 basta (a original tem até 720 px e ocupava 2 MB cada)
                val quadrada = if (recorte.width > 512) Bitmap.createScaledBitmap(recorte, 512, 512, true).also { if (recorte !== b) recorte.recycle() } else recorte
                if (b !== quadrada && b !== recorte) b.recycle()
                handler.post { capas.put(r.url, quadrada); if (minha == geracao) { capa = quadrada; aoMudar() } }
            } catch (e: Exception) { Log.w(TAG, "sem capa", e) }
        }
    }

    /** O player começou a tocar (música ou vídeo): quem dá play por último manda, então o celular pára. */
    var aoComecou: (() -> Unit)? = null

    /** O celular está mandando áudio agora? (informado pela tela principal) */
    var celularTocando: () -> Boolean = { true }

    private val audioMgr = contexto.applicationContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    // Perdeu o foco para outro app (o Bluetooth da caixa pega o foco quando o celular começa a tocar): pausa, para não saírem dois sons juntos.
    private val foco = android.media.AudioManager.OnAudioFocusChangeListener { mudanca ->
        if (mudanca == android.media.AudioManager.AUDIOFOCUS_LOSS) handler.post {
            // o celular bloqueado ou desbloqueado também mexe no foco de áudio da caixa sem tocar nada: só pausa se ele está tocando de verdade
            if (!celularTocando()) { Log.d(TAG, "perdeu o foco de áudio, mas o celular não está tocando: segue tocando"); return@post }
            Log.d(TAG, "perdeu o foco de áudio (o celular está tocando): pausando")
            if (player?.isPlaying == true) pausarSuave()
        }
    }

    /** Nivelamento de áudio (ver [Nivelador]): só mede quando o som está limpo, sem fade e com o volume do player inteiro. */
    private val nivelador = Nivelador(handler) { !emFade && saindo.not() && (player?.volume ?: 0f) >= volumeBase * 0.97f && volumeBase > 0.9f }

    /** "1,2 mi de visualizações  ·  12 de março de 2023": mostrado ao lado do nome do canal no player de vídeo. */
    @Volatile var infoExtra: String = ""; private set

    private fun infoDe(f: Fonte): String {
        val vistas = Resultado("", "", 0, "", emptyList(), f.visualizacoes).visualizacoesTexto
        val data = if (f.publicadoEm > 0) java.text.SimpleDateFormat("d 'de' MMMM 'de' yyyy", java.util.Locale("pt", "BR")).format(java.util.Date(f.publicadoEm)) else ""
        return listOf(vistas, data).filter { it.isNotEmpty() }.joinToString("  ·  ")
    }

    private fun tocarFonte(fonte: Fonte) {
        // pede o foco de áudio: o A2DP da caixa perde o dele e manda "pausar" ao celular
        try { audioMgr.requestAudioFocus(foco, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN) } catch (e: Throwable) { }
        val p = criar()
        p.playWhenReady = false
        controlesDe[p]?.somLeve = !ehVideo && !ehFluxoDireto(item?.url.orEmpty())
        val origem = origemDe(fonte)
        p.repeatMode = if (repetindo && !ehVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !ehVideo).setMaxVideoSize(if (ehCanalTv) 1280 else Int.MAX_VALUE, if (ehCanalTv) 720 else Int.MAX_VALUE).build()      // canal ao vivo: no máximo 720p (o aparelho e a internet agradecem)
        p.setMediaSource(origem, retomarEm)
        urlNoPlayer = item?.url
        retomarEm = 0L
        cancelarRampa(); fracaoVolume = 1f
        p.prepare()
        nivelador.novaFaixa(); nivelador.ligar(p.audioSessionId)
        val vaiTocar = comecarTocando
        comecarTocando = true
        if (vaiTocar) tocarComFade(p, 1000L) else p.volume = volumeBase
    }

    /** Rádio da internet: a URL do fluxo (mp3, aac, HLS...) não é do YouTube. */
    private fun ehFluxoDireto(url: String) = !(url.contains("youtube.com") || url.contains("youtu.be"))

    /** Música de um Mix baixado (Downloads): o endereço é um arquivo no aparelho, que toca sem internet e não é uma rádio. */
    private fun ehArquivoLocal(url: String) = url.startsWith("file:")

    private fun origemRadio(url: String): MediaSource {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 7.1) CaixaLM2/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
        return androidx.media3.exoplayer.source.DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)).createMediaSource(MediaItem.fromUri(url))
    }

    private fun origemDe(fonte: Fonte): MediaSource {
        val origem: MediaSource = if (fonte.unicaUrl != null && ehFluxoDireto(fonte.unicaUrl)) {
            origemRadio(fonte.unicaUrl)
        } else if (fonte.unicaUrl != null) {
            fabricaPara(fonte.unicaUrl).createMediaSource(MediaItem.fromUri(fonte.unicaUrl))
        } else {
            MergingMediaSource(
                fabricaPara(fonte.videoUrl!!).createMediaSource(MediaItem.fromUri(fonte.videoUrl)),
                fabricaPara(fonte.audioUrl!!).createMediaSource(MediaItem.fromUri(fonte.audioUrl))
            )
        }
        return origem
    }

    /** O "nome de aplicativo" das requisições ao YouTube: o mesmo do extrator (a partir da versão 0.27 ele só gera endereços do cliente web). */
    private fun agenteDe(url: String): String = AppDownloader.USER_AGENT

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
        if (emFade) cancelarFade()
        if (erro != null) { item?.let { iniciarItem(it, ehVideo) }; return }
        if (saindo) { cancelarRampa(); p.pause(); restaurarVolume(); return }      // segundo toque durante o fade: para na hora
        if (p.isPlaying) pausarSuave()
        else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            tocarComFade(p, 250L)
        }
    }

    // ---------------------------------------------------------------- fade ao pausar e ao trocar de música

    private var rampaAtual: Runnable? = null
    private var fadeAoComecarMs = 0L
    private var fracaoVolume = 1f
    private var saindo = false

    private fun cancelarRampa() {
        rampaAtual?.let { handler.removeCallbacks(it) }
        rampaAtual = null
        fadeAoComecarMs = 0L
        saindo = false
    }

    private fun tocarComFade(p: ExoPlayer, duracaoMs: Long) {
        cancelarRampa()
        fadeAoComecarMs = duracaoMs
        fracaoVolume = 0f
        p.volume = 0f
        p.play()
    }

    private fun restaurarVolume() { fracaoVolume = 1f; player?.volume = volumeBase }

    /** Muda o volume do player de [de] a [para] (fração do volume base) em [ms]; no fim chama [fim]. */
    private fun rampa(de: Float, para: Float, ms: Long, fim: () -> Unit) {
        cancelarRampa()
        saindo = para < de
        val t0 = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                val f = ((SystemClock.uptimeMillis() - t0).toFloat() / ms).coerceIn(0f, 1f)
                fracaoVolume = de + (para - de) * f
                player?.volume = fracaoVolume * volumeBase
                if (f >= 1f) { rampaAtual = null; saindo = false; fim() } else handler.postDelayed(this, 25)
            }
        }
        rampaAtual = r
        r.run()
    }

    /** Pausa com o som descendo em 300 ms, em vez do corte seco. */
    private fun pausarSuave() {
        val p = player ?: return
        rampa(fracaoVolume, 0f, 300L) { p.pause(); restaurarVolume() }
    }

    /** Antes de desligar a caixa: o som desce até o silêncio em [ms] e só então [fim] é chamado. Sem nada tocando, chama [fim] na hora. */
    fun sumirParaDesligar(ms: Long, fim: () -> Unit) {
        val p = player
        if (p == null || !p.isPlaying) { fim(); return }
        rampa(fracaoVolume, 0f, ms) { fim() }
    }

    /** Troca de faixa pedida pela pessoa: o som atual desce em 250 ms e só então a nova começa. */
    private fun trocarSuave(acao: () -> Unit) {
        val p = player
        if (p == null || !p.isPlaying) { acao(); return }
        rampa(fracaoVolume, 0f, 250L) { acao() }
    }

    /** As palavras que identificam a música no título: sem (parênteses), [colchetes], "clipe oficial", "ao vivo" e afins. */
    private val ruidoTitulo = setOf("clipe", "oficial", "official", "video", "videoclipe", "audio", "lyrics", "lyric", "letra", "ao", "vivo", "live", "hd", "4k", "dvd", "com", "feat", "part", "the", "and", "dos", "das")
    private val reParenteses = Regex("""[\(\[][^\)\]]*[\)\]]""")      // as expressões são montadas uma vez só (montar a cada chamada pesava no fim da lista)
    private val reAcentos = Regex("""\p{M}+""")
    private val reNaoAlfanum = Regex("[^a-z0-9]+")
    private val palavrasGuardadas = android.util.LruCache<String, Set<String>>(200)

    private fun palavrasDoTitulo(t: String): Set<String> = palavrasGuardadas.get(t) ?: run {
        java.text.Normalizer.normalize(t.lowercase().replace(reParenteses, " "), java.text.Normalizer.Form.NFD)
            .replace(reAcentos, "").replace(reNaoAlfanum, " ").trim().split(" ")
            .filter { it.length > 2 && it !in ruidoTitulo }.toSet()
    }.also { palavrasGuardadas.put(t, it) }

    /** É outra versão de uma música que já tocou nesta fila (mesmas palavras do título)? */
    private fun repetida(r: Resultado): Boolean {
        val p = palavrasDoTitulo(r.titulo)
        if (p.isEmpty()) return false
        return fila.take(indice + 1).any { f ->
            f.url == r.url || palavrasDoTitulo(f.titulo).let { q -> q.isNotEmpty() && p.count { it in q } * 100 >= 60 * Math.min(p.size, q.size) }
        }
    }

    /** O que o YouTube sugere depois, sem as outras versões do que já tocou: segue com músicas diferentes do mesmo estilo. */
    private fun relacionadosNovos(): List<Resultado> {
        // o resultado só muda quando mudam a lista de sugestões, a fila, a faixa ou o modo; fora isso reaproveita (esta conta roda várias vezes por segundo)
        val rel = relacionados
        val chave = listOf(System.identityHashCode(rel), indice, fila.size, item?.url, modoMusica)
        if (chave == chaveRelacionados) return resultadoRelacionados
        val r = rel.filter { !repetida(it) && (!modoMusica || YouTube.pareceMusica(it.titulo, it.canal, it.duracaoSeg, item?.canal.orEmpty())) }
        chaveRelacionados = chave; resultadoRelacionados = r
        return r
    }
    private var chaveRelacionados: List<Any?>? = null
    private var resultadoRelacionados: List<Resultado> = emptyList()

    /** O que está tocando é música: as sugestões também têm que ser (nada de reação, entrevista, aula...). Outros assuntos seguem livres. */
    private var modoMusica = true

    /** Pula para o item [i] da fila (toque na lista de próximas). */
    fun irPara(i: Int) {
        if (i !in fila.indices || i == indice) return
        trocarSuave { indice = i; iniciarItem(fila[i], ehVideo) }
    }

    /** Põe no fim da fila os itens de uma playlist do mesmo tema, sem o que já está na fila nem outra versão do que já tocou. */
    fun anexarFila(itens: List<Resultado>): Int {
        val novos = itens.filter { n -> fila.none { it.url == n.url } && !repetida(n) }
        if (novos.isNotEmpty()) fila = fila + novos
        Log.d("FilaDiag", "anexarFila: +${novos.size} (fila ${fila.size}, atual '${item?.titulo}', 1º novo '${novos.firstOrNull()?.titulo}')")
        return novos.size
    }

    val temProxima: Boolean get() = indice + 1 < fila.size || relacionadosNovos().isNotEmpty()
    val temAnterior: Boolean get() = indice > 0

    /** Próximo da lista; no fim dela, segue pelas sugestões do YouTube (como o autoplay de lá). */
    fun proxima() { trocarSuave { avancar() } }

    private fun avancar() {
        if (indice + 1 >= fila.size && relacionadosNovos().isNotEmpty()) { fila = fila + relacionadosNovos(); relacionados = emptyList() }
        if (indice + 1 < fila.size) { indice++; iniciarItem(fila[indice], ehVideo) }
    }

    fun anterior() {
        if (ehRadio || ehCanalTv) { if (indice > 0) trocarSuave { indice--; iniciarItem(fila[indice], ehVideo) }; return }      // rádio ao vivo: não há começo para voltar
        if (posicaoMs > 4000 || indice == 0) player?.seekTo(0)
        else trocarSuave { indice--; iniciarItem(fila[indice], ehVideo) }
    }

    fun pular(ms: Long) { player?.let { it.seekTo((it.currentPosition + ms).coerceIn(0, duracaoMs.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)) }; aoMudar() }

    fun buscar(ms: Long) { if (emFade) cancelarFade(); player?.seekTo(ms.coerceAtLeast(0)); aoMudar() }

    fun pausar() { if (emFade) cancelarFade(); if (player?.isPlaying == true) pausarSuave() else player?.pause() }

    var repetindo = false; private set

    /** Repete a música atual sem parar (sem emenda com a próxima). */
    fun repetir(ligar: Boolean) {
        repetindo = ligar
        cancelarFade()
        player?.repeatMode = if (ligar) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /** Embaralha o que ainda vai tocar da fila (a música atual continua). */
    fun embaralhar() {
        cancelarFade()
        if (fila.size - indice > 2) fila = fila.take(indice + 1) + fila.drop(indice + 1).shuffled()
    }

    private var retomarAposAssistente = false

    /** O assistente foi chamado: a música (ou vídeo) desce em fade e pausa. Devolve em quantos ms terminou (0 se não estava tocando). */
    fun pausarParaAssistente(): Long {
        val p = player ?: return 0L
        if (!p.isPlaying) return 0L
        if (emFade) cancelarFade()
        retomarAposAssistente = true
        rampa(fracaoVolume, 0f, 400L) { p.pause(); restaurarVolume() }
        return 450L
    }

    /** A pessoa mandou pausar ou parar com o assistente aberto: a música que ele pausou NÃO volta a tocar quando ele fechar. */
    fun cancelarRetomadaDoAssistente() { retomarAposAssistente = false }

    /** O assistente saiu da tela: volta a tocar de onde parou, com o som subindo em fade. */
    fun retomarDoAssistente() {
        if (!retomarAposAssistente) return
        retomarAposAssistente = false
        val p = player ?: return
        if (p.isPlaying) return
        tocarComFade(p, 1000L)
    }

    /** Volume do player (0..1): o assistente abaixa enquanto escuta e fala. */
    fun volume(v: Float) { volumeBase = v; if (!emFade) player?.volume = v * fracaoVolume }

    // ---------------------------------------------------------------- emenda entre músicas (crossfade)
    //
    // Perto do fim de uma música a próxima já é preparada; nos últimos 3 s a atual baixa e a próxima sobe, tocando juntas, sem parada.

    private class Preparada(val item: Resultado, val fonte: Fonte)

    private var volumeBase = 1f
    private var preparada: Preparada? = null
    private var preparando = false
    private var proximoPlayer: ExoPlayer? = null
    private var emFade = false
    private var fadeInicio = 0L
    private var fadeDuracao = 3000L
    private val duracaoFade = 3000L

    private val vigia = object : Runnable {
        override fun run() {
            try { verificarEmenda() } catch (e: Throwable) { Log.w(TAG, "emenda", e) }
            handler.postDelayed(this, 200)
        }
    }

    init { handler.post(vigia) }

    private fun proximoDaFila(): Resultado? = fila.getOrNull(indice + 1) ?: relacionadosNovos().firstOrNull()

    private fun verificarEmenda() {
        val p = player ?: return
        if (ehVideo) { verificarPreVideo(); return }
        if (!tocando || item == null || erro != null || repetindo) return
        // última faixa da fila: a lista das próximas já se forma sozinha com as sugestões (como nos vídeos), uma vez só
        if (indice + 1 >= fila.size) {
            val novos = relacionadosNovos()
            if (novos.isNotEmpty()) { fila = fila + novos; relacionados = emptyList(); aoMudar() }
        }
        if (!ehFluxoDireto(item?.url.orEmpty()) && p.currentPosition >= 3000) {
            // como no vídeo: descobre antes o endereço das próximas faixas (só texto, quase não gasta internet), para trocar sem esperar
            if (falhasSeguidas == 0) proximoDaFila()?.let { prefetchVideo(it) }
            resolverFila()
        }
        if (emFade) { passoDoFade(); return }
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur < 25_000) return
        val resta = dur - p.currentPosition
        val prox = proximoDaFila() ?: return
        // 25 s antes do fim: descobre o endereço da próxima (demora uns 2 s) e já deixa ela carregando, muda, pausada
        if (resta < 25_000 && preparada?.item?.url != prox.url && !preparando) {
            preparando = true
            val minha = geracao
            Tarefas.executar {
                val f = if (ehFluxoDireto(prox.url)) Fonte(null, null, prox.url, musica = true) else (audioGuardado(prox.url) ?: try { YouTube.resolver(prox.url, soAudio = true) } catch (e: Exception) { null })
                handler.post {
                    preparando = false
                    if (f != null && minha == geracao && !ehVideo) { preparada = Preparada(prox, f); prepararProximo() }
                }
            }
        }
        // nos últimos 3 s, com a próxima já carregada, começa a emenda
        val novo = proximoPlayer
        if (resta <= duracaoFade + 200 && novo != null && novo.playbackState == Player.STATE_READY) iniciarFade(resta.coerceAtLeast(1200L))
    }

    /** Carrega a próxima música em outro player, sem tocar, para ela já estar pronta (sem pausa) na hora da emenda. */
    private fun prepararProximo() {
        val pre = preparada ?: return
        if (proximoPlayer != null) return
        val novo = novoExo()
        novo.volume = 0f
        novo.setMediaSource(origemDe(pre.fonte))
        novo.playWhenReady = false
        novo.prepare()
        proximoPlayer = novo
    }

    private fun iniciarFade(duracao: Long) {
        val novo = proximoPlayer ?: return
        novo.playWhenReady = true
        emFade = true
        fadeInicio = SystemClock.uptimeMillis()
        fadeDuracao = duracao
        Log.d(TAG, "emenda: começando ${preparada?.item?.titulo} em $duracao ms (próxima: estado=${novo.playbackState}, duração=${novo.duration / 1000}s)")
    }

    private fun passoDoFade() {
        val atual = player ?: return
        val novo = proximoPlayer ?: return
        val f = ((SystemClock.uptimeMillis() - fadeInicio).toFloat() / fadeDuracao).coerceIn(0f, 1f)
        atual.volume = (1f - f) * volumeBase
        novo.volume = f * volumeBase
        if (f >= 1f || atual.playbackState == Player.STATE_ENDED) concluirFade()
    }

    private fun concluirFade() {
        val antigo = player ?: return
        val novo = proximoPlayer ?: return
        val pre = preparada ?: return
        proximoPlayer = null; emFade = false; preparada = null
        novo.playWhenReady = true
        if (indice + 1 >= fila.size) { fila = fila + relacionadosNovos(); relacionados = emptyList() }
        player = novo
        urlNoPlayer = pre.item.url
        liberarExo(antigo)
        nivelador.novaFaixa(); nivelador.ligar(novo.audioSessionId)
        infoExtra = infoDe(pre.fonte)
        indice++
        item = pre.item
        relacionados = pre.fonte.relacionados; modoMusica = pre.fonte.musica
        capa = null; erro = null; carregando = false; recuperacoes = 0
        novo.volume = volumeBase
        tocando = novo.isPlaying || novo.playWhenReady
        val minha = ++geracao
        carregarCapa(pre.item, minha)
        Log.d(TAG, "emenda concluída: ${pre.item.titulo} (duração ${novo.duration / 1000}s, posição ${novo.currentPosition / 1000}s, estado ${novo.playbackState})")
        aoMudar()
    }

    /** Descarta a próxima já carregada e volta o volume da atual (o usuário pulou, pausou ou mexeu na barra). */
    private fun cancelarFade() {
        preparada = null
        liberarExo(proximoPlayer)
        proximoPlayer = null
        if (emFade) { emFade = false; player?.volume = volumeBase }
    }

    /** A imagem do vídeo vai para esta superfície (some da tela quando é null). */
    fun ligarImagem(v: SurfaceView?) {
        val antiga = superficie
        superficie = v
        if (v != null) player?.setVideoSurfaceView(v) else if (antiga != null) player?.clearVideoSurfaceView(antiga)
    }

    fun parar() {
        geracao++
        cancelarRampa()
        cancelarFade()
        liberarPreVideo()
        player?.stop(); player?.clearMediaItems()
        item = null; fila = emptyList(); indice = -1; carregando = false; tocando = false; erro = null; capa = null; ehVideo = false; primeiroQuadro = false
        aoMudar()
    }

    fun liberar() {
        geracao++
        cancelarRampa()
        cancelarFade()
        liberarPreVideo()
        handler.removeCallbacks(vigia)
        nivelador.desligar()
        liberarExo(player); player = null
    }
}
