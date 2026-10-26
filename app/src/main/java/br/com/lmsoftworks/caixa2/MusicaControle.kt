package br.com.lmsoftworks.caixa2

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.net.URL
import java.net.URLEncoder

/**
 * O que está tocando pelo Bluetooth: título, artista, álbum, tempo e botões, lidos da sessão de
 * mídia que o receptor A2DP do Android cria. A capa não vem pelo Bluetooth nesta versão do
 * Android, então ela é buscada na internet (iTunes) pelo nome da música.
 */
class MusicaControle(contexto: Context, private val aoMudar: () -> Unit) {

    companion object {
        private const val TAG = "CaixaMusica"
        private val SERVICO = ComponentName("com.android.bluetooth", "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService")
    }

    private val ctx = contexto.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var navegador: MediaBrowser? = null
    private var controle: MediaController? = null

    var titulo = ""; private set
    var artista = ""; private set
    var album = ""; private set
    var duracaoMs = 0L; private set
    var capa: Bitmap? = null; private set
    var estado = PlaybackState.STATE_NONE; private set
    private var posicaoBase = 0L
    private var atualizadoEm = 0L
    private var velocidade = 1f
    private var chaveCapa = ""
    private val cacheMemoria = LruCache<String, Bitmap>(8)
    private val pasta = File(ctx.cacheDir, "capas2").apply { mkdirs() }      // capas2: descarta as capas erradas guardadas antes da busca criteriosa
    private val esperarBusca = Runnable { buscarPendente() }
    private var pendente: Array<String>? = null

    /** O player do YouTube (menu Mídia). Quando ele está tocando uma música, ele manda no lugar do celular. */
    var local: PlayerMidia? = null
    val emLocal: Boolean get() = local?.let { it.ativo && !it.ehVideo } == true

    val temMusica: Boolean get() = titulo.isNotEmpty()
    val tocando: Boolean get() = estado == PlaybackState.STATE_PLAYING || estado == PlaybackState.STATE_BUFFERING
    val conectado: Boolean get() = controle != null

    /** posição atual em ms, calculada a partir do último aviso do celular */
    fun posicaoMs(): Long {
        if (emLocal) return local!!.posicaoMs
        if (!tocando) return posicaoBase
        val p = posicaoBase + ((SystemClock.elapsedRealtime() - atualizadoEm) * velocidade).toLong()
        return if (duracaoMs > 0) p.coerceIn(0, duracaoMs) else p
    }

    private val retorno = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) { if (emLocal) return; lerMetadados(m); aoMudar() }
        override fun onPlaybackStateChanged(s: PlaybackState?) {
            if (emLocal) {
                // o celular voltou a tocar: ele passa a mandar de novo e a música do YouTube para
                if (s?.state != PlaybackState.STATE_PLAYING) return
                local?.parar()
                lerMetadados(controle?.metadata)
            }
            lerEstado(s); aoMudar()
        }
        override fun onSessionDestroyed() { desligar(); iniciar() }
    }

    private val conexao = object : MediaBrowser.ConnectionCallback() {
        override fun onConnected() {
            try {
                val c = MediaController(ctx, navegador!!.sessionToken)
                controle = c
                c.registerCallback(retorno, handler)
                lerMetadados(c.metadata)
                lerEstado(c.playbackState)
            } catch (e: Exception) { Log.w(TAG, "nao foi possivel abrir a sessao", e) }
            aoMudar()
        }
        override fun onConnectionFailed() {
            Log.w(TAG, "falha ao conectar na sessao de midia do Bluetooth")
            handler.postDelayed({ iniciar() }, 5000)
        }
        override fun onConnectionSuspended() { desligar(); handler.postDelayed({ iniciar() }, 2000) }
    }

    /** Aquece DNS e conexão segura para a primeira capa chegar mais rápido. */
    private fun aquecer() {
        Thread {
            try {
                val con = URL("https://itunes.apple.com/search?term=a&limit=1").openConnection() as HttpURLConnection
                con.connectTimeout = 4000; con.readTimeout = 4000
                con.inputStream.use { it.readBytes() }
            } catch (e: Exception) { }
        }.start()
    }

    fun iniciar() {
        if (navegador != null) return
        aquecer()
        try {
            navegador = MediaBrowser(ctx, SERVICO, conexao, null).also { it.connect() }
        } catch (e: Exception) { Log.w(TAG, "sem acesso a sessao de midia", e) }
    }

    fun desligar() {
        try { controle?.unregisterCallback(retorno) } catch (e: Exception) { }
        try { navegador?.disconnect() } catch (e: Exception) { }
        controle = null
        navegador = null
    }

    /** Quando não há música, o celular manda textos como "Not Supported" no lugar do título: isso conta como vazio. */
    private fun textoReal(s: String?): String {
        val t = s?.trim() ?: return ""
        val sem = t.lowercase().replace(Regex("[^a-z]"), "")
        return if (sem in setOf("notsupported", "notprovided", "notavailable", "na", "none", "null")) "" else t
    }

    private fun lerMetadados(m: MediaMetadata?) {
        val novoTitulo = textoReal(m?.getString(MediaMetadata.METADATA_KEY_TITLE))
        val novoArtista = textoReal(m?.getString(MediaMetadata.METADATA_KEY_ARTIST))
        titulo = novoTitulo
        artista = novoArtista
        album = textoReal(m?.getString(MediaMetadata.METADATA_KEY_ALBUM))
        duracaoMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val nativa = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val novoAlbum = textoReal(m?.getString(MediaMetadata.METADATA_KEY_ALBUM))
        val chave = "$novoArtista|$novoTitulo|$novoAlbum"
        if (nativa != null) { capa = nativa; chaveCapa = chave }
        else if (chave != chaveCapa) {
            chaveCapa = chave
            capa = cacheMemoria.get(chave) ?: lerDoDisco(chave)?.also { cacheMemoria.put(chave, it) }
            if (capa == null && novoTitulo.isNotEmpty()) {
                // o celular manda título e artista em avisos separados: espera um instante para buscar uma vez só
                pendente = arrayOf(novoArtista, novoTitulo, novoAlbum, chave)
                handler.removeCallbacks(esperarBusca)
                handler.postDelayed(esperarBusca, 300)
            }
        }
    }

    private fun lerEstado(s: PlaybackState?) {
        estado = s?.state ?: PlaybackState.STATE_NONE
        posicaoBase = s?.position?.takeIf { it >= 0 } ?: 0L
        atualizadoEm = s?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime()
        velocidade = s?.playbackSpeed ?: 1f
    }

    private fun buscarPendente() {
        val p = pendente ?: return
        pendente = null
        if (p[3] != chaveCapa) return
        buscarCapa(p[0], p[1], p[2], p[3])
    }

    private fun arquivoDe(chave: String): File {
        val h = MessageDigest.getInstance("MD5").digest(chave.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(pasta, "$h.jpg")
    }

    private fun lerDoDisco(chave: String): Bitmap? {
        val f = arquivoDe(chave)
        return if (f.exists()) BitmapFactory.decodeFile(f.path, opcoes()) else null
    }

    private fun opcoes() = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

    // ---------------------------------------------------------------- capa do álbum
    //
    // O celular NÃO manda a imagem da capa pelo Bluetooth (o perfil da caixa só recebe título, artista e álbum). Então o app
    // procura a capa no iTunes pelo nome. Antes ele pegava o PRIMEIRO resultado, que às vezes era outra gravação (cover,
    // karaokê, coletânea). Agora ele pede vários resultados, dá nota a cada um (título, artista e álbum parecidos com os que o
    // celular mostra) e só aceita um que combine; se nenhum combinar, fica sem capa em vez de mostrar uma errada.

    private fun norm(s: String): String {
        val sem = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return sem.replace(Regex("\\(.*?\\)|\\[.*?]"), " ").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /** 3 = iguais; 2 = um contém o outro; até 1,5 = palavras em comum; 0 = nada a ver. */
    private fun parecido(nosso: String, deles: String): Double {
        val a = norm(nosso); val b = norm(deles)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 3.0
        if (a.contains(b) || b.contains(a)) return 2.0
        val ta = a.split(" ").toSet(); val tb = b.split(" ").toSet()
        return 1.5 * ta.intersect(tb).size / ta.union(tb).size
    }

    private val SUSPEITAS = listOf("karaoke", "tribute", "cover", "instrumental", "lullaby", "originally performed", "made famous", "8 bit", "8-bit", "playback")

    private fun notaDoResultado(r: JSONObject, artista: String, titulo: String, album: String): Double {
        val musica = r.optString("trackName"); val quem = r.optString("artistName"); val disco = r.optString("collectionName")
        val pTitulo = parecido(titulo, musica)
        val pArtista = if (artista.isBlank()) 2.0 else parecido(artista, quem)
        if (pTitulo < 2.0 || pArtista < 1.0) return -1.0            // não é a mesma música/artista: descarta
        var nota = 2 * pTitulo + 2 * pArtista
        if (album.isNotBlank()) nota += parecido(album, disco)
        val nossoTexto = norm("$titulo $artista $album")
        val textoDeles = norm("$musica $quem $disco")
        for (palavra in SUSPEITAS) if (textoDeles.contains(palavra) && !nossoTexto.contains(palavra)) nota -= 4
        return nota
    }

    private fun consultar(termo: String, pais: String?): List<JSONObject> {
        val t = URLEncoder.encode(termo, "UTF-8")
        val url = URL("https://itunes.apple.com/search?term=$t&entity=song&limit=15" + (if (pais != null) "&country=$pais" else ""))
        val con = url.openConnection() as HttpURLConnection
        con.connectTimeout = 4000; con.readTimeout = 6000
        val arr = JSONObject(con.inputStream.bufferedReader().use { it.readText() }).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** Procura a capa no iTunes (sem chave), em segundo plano, escolhendo o resultado que mais combina; guarda no cache. */
    private fun buscarCapa(artista: String, titulo: String, album: String, chave: String) {
        Thread {
            var imagem: Bitmap? = null
            try {
                var melhor: JSONObject? = null
                for (pais in listOf("BR", null)) {            // loja do Brasil primeiro, depois a padrão
                    val achados = consultar("$artista $titulo".trim(), pais)
                    var notaMelhor = 0.0
                    for (r in achados) {
                        val n = notaDoResultado(r, artista, titulo, album)
                        if (n > notaMelhor) { notaMelhor = n; melhor = r }
                    }
                    if (melhor != null) break
                }
                val arte = melhor?.optString("artworkUrl100") ?: ""
                if (arte.isNotEmpty()) {
                    val c2 = URL(arte.replace("100x100bb", "400x400bb")).openConnection() as HttpURLConnection
                    c2.connectTimeout = 4000; c2.readTimeout = 8000
                    val bytes = c2.inputStream.use { it.readBytes() }
                    imagem = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opcoes())
                    if (imagem != null) try { arquivoDe(chave).writeBytes(bytes) } catch (e: Exception) { }
                } else Log.d(TAG, "nenhuma capa combina com '$artista - $titulo ($album)': fica sem capa")
            } catch (e: Exception) {
                Log.w(TAG, "capa nao encontrada", e)
            }
            handler.post {
                if (imagem != null) cacheMemoria.put(chave, imagem)
                if (chave == chaveCapa && imagem != null) { capa = imagem; aoMudar() }
            }
        }.start()
    }

    // ---- música do YouTube (menu Mídia) ----

    /** Toca uma música do YouTube; a do celular, se estiver tocando, é pausada. */
    fun tocarDoYoutube(lista: List<Resultado>, i: Int) {
        controle?.transportControls?.pause()
        local?.tocarMusica(lista, i)
    }

    /** O player do YouTube mudou: copia o que ele toca para os mesmos campos que a tela de música já lê. */
    fun sincronizarLocal() {
        val l = local ?: return
        val r = l.item
        if (r != null && !l.ehVideo) {
            titulo = r.titulo
            artista = r.canal.removeSuffix(" - Topic")
            album = ""
            duracaoMs = l.duracaoMs
            capa = l.capa
            estado = when {
                l.erro != null -> PlaybackState.STATE_ERROR
                l.tocando -> PlaybackState.STATE_PLAYING
                l.carregando -> PlaybackState.STATE_BUFFERING
                else -> PlaybackState.STATE_PAUSED
            }
            posicaoBase = l.posicaoMs
            atualizadoEm = SystemClock.elapsedRealtime()
            velocidade = 1f
            chaveCapa = "yt|" + r.url
        } else if (chaveCapa.startsWith("yt|")) {
            // acabou a música do YouTube: volta a mostrar o que o celular estiver tocando
            chaveCapa = ""
            lerMetadados(controle?.metadata)
            lerEstado(controle?.playbackState)
        }
        aoMudar()
    }

    // ---- botões ----
    fun tocar() { if (emLocal) { if (!local!!.tocando) local!!.alternar() } else controle?.transportControls?.play() }
    fun pausar() { if (emLocal) { if (local!!.tocando) local!!.alternar() } else controle?.transportControls?.pause() }
    fun alternar() { if (emLocal) local!!.alternar() else if (tocando) pausar() else tocar() }
    fun proxima() { if (emLocal) local!!.proxima() else controle?.transportControls?.skipToNext() }
    fun anterior() { if (emLocal) local!!.anterior() else controle?.transportControls?.skipToPrevious() }

    /** Pula para um ponto da música (em ms). A tela já mostra o novo ponto enquanto o celular responde. */
    fun buscar(ms: Long) {
        if (emLocal) { local!!.buscar(ms); return }
        posicaoBase = ms
        atualizadoEm = SystemClock.elapsedRealtime()
        controle?.transportControls?.seekTo(ms)
        aoMudar()
    }
}
