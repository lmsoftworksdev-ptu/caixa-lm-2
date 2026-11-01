package br.com.lmsoftworks.caixa2.voz

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import br.com.lmsoftworks.caixa2.Ajustes
import java.io.File
import java.util.Locale

/** A caixa falando ("Tocando saia rodada"). Usa o motor de voz do Google do aparelho. */
object Fala {
    private const val TAG = "VozCaixa"
    private const val TEMPO_MAXIMO_MS = 6000L

    /** true enquanto a caixa esta falando (a escuta da chamada fica mais rigida nesse periodo). */
    @Volatile
    var falando = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var pronto = false
    private var contador = 0
    private val aoTerminar = HashMap<String, () -> Unit>()

    // ---- a voz (fala, toquinhos e som de pesquisa) sai num canal próprio, no volume "Volume da voz" de Configurações; a música
    // (do celular ou do app) é que abaixa em fade até o nível 2 enquanto o assistente está aberto, e volta depois. ----
    private var contexto: Context? = null
    @Volatile var CANAL = android.media.AudioManager.STREAM_ALARM       // voz, toquinhos e som de pesquisa
    private const val MUSICA = android.media.AudioManager.STREAM_MUSIC
    private const val NIVEL_MUSICA_ABAIXADA = 2                     // de 0 a 10
    var aoMexerNoVolume: (() -> Unit)? = null
    private var volumeAntes = -1
    private var volumeFixado = -1

    /** Põe o canal da voz no nível de Configurações (de 1 a 10). O canal do alarme é forte: o 10 vai a 70% dele. */
    fun aplicarVolume(ctx: Context? = contexto) {
        ctx ?: return
        try {
            val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val alvo = Math.round(audio.getStreamMaxVolume(CANAL) * 0.7f * Ajustes.volumeVoz / 10f).coerceAtLeast(1)
            if (audio.getStreamVolume(CANAL) != alvo) audio.setStreamVolume(CANAL, alvo, 0)
        } catch (e: Throwable) { Log.d(TAG, "volume da voz: $e") }
    }

    private var ultimoPorApp = -1
    private var rampa: Runnable? = null

    /** Leva o volume do aparelho até [para] de um em um nível, em ~[ms] (fade); [aoFim] roda ao chegar. */
    private fun ir(audio: android.media.AudioManager, para: Int, ms: Long, aoFim: () -> Unit = {}) {
        rampa?.let { main.removeCallbacks(it) }
        val passos = Math.abs(para - audio.getStreamVolume(MUSICA))
        if (passos == 0) { ultimoPorApp = para; rampa = null; aoFim(); return }
        val intervalo = (ms / passos).coerceAtLeast(30L)
        val r = object : Runnable {
            override fun run() {
                try {
                    val atual = audio.getStreamVolume(MUSICA)
                    if (atual == para) { ultimoPorApp = para; rampa = null; aoFim(); return }
                    val prox = if (para > atual) atual + 1 else atual - 1
                    aoMexerNoVolume?.invoke()
                    audio.setStreamVolume(MUSICA, prox, 0)
                    ultimoPorApp = prox
                    if (prox == para) { rampa = null; aoFim() } else main.postDelayed(this, intervalo)
                } catch (e: Throwable) { rampa = null }
            }
        }
        rampa = r
        main.post(r)
    }

    private fun fixarVolume() {
        val ctx = contexto ?: return
        try {
            val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val alvo = Math.round(audio.getStreamMaxVolume(MUSICA) * NIVEL_MUSICA_ABAIXADA / 10f).coerceAtLeast(1)
            if (volumeAntes < 0) volumeAntes = audio.getStreamVolume(MUSICA)
            volumeFixado = alvo
            // com o assistente aberto a música abaixa em fade; numa fala avulsa o volume muda na hora
            if (mantido) ir(audio, alvo, 450) else if (audio.getStreamVolume(MUSICA) != alvo) { aoMexerNoVolume?.invoke(); audio.setStreamVolume(MUSICA, alvo, 0); ultimoPorApp = alvo }
        } catch (e: Throwable) { Log.d(TAG, "volume da voz: $e") }
    }

    /** Enquanto o assistente está aberto (ouvindo, pensando, falando) a música fica abaixada. */
    @Volatile private var mantido = false

    fun prenderVolume() { mantido = true; fixarVolume() }

    fun soltarVolume() { mantido = false; devolverVolume() }

    private fun devolverVolume() {
        val ctx = contexto ?: return
        if (mantido || volumeAntes < 0) return
        try {
            val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val antes = volumeAntes
            // só devolve se ninguém mexeu no volume enquanto o assistente estava aberto; a música volta em fade
            if (audio.getStreamVolume(MUSICA) == ultimoPorApp) ir(audio, antes, 900)
        } catch (e: Throwable) { Log.d(TAG, "volume da voz: $e") }
        volumeAntes = -1; volumeFixado = -1
    }

    /** Chame cedo (ex.: ao ligar a voz) para o motor ja estar pronto na primeira resposta. */
    fun preparar(ctx: Context) {
        contexto = ctx.applicationContext
        aplicarVolume()
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                t.setSpeechRate(1.45f)
                val r = t.setLanguage(Locale("pt", "BR"))
                pronto = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                Log.d(TAG, "fala: idioma pt-BR resultado=$r pronto=$pronto")
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = terminou(utteranceId)
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = terminou(utteranceId)
                })
            } else {
                Log.d(TAG, "fala: motor de voz indisponivel (status=$status)")
            }
        }
    }

    /** Fala [texto] e chama [fim] ao terminar. Se a voz nao estiver disponivel, chama [fim] na hora. */
    fun falar(ctx: Context, texto: String, fim: () -> Unit) {
        preparar(ctx)
        val t = tts
        if (t == null || !pronto) {
            // sem motor de voz em português no aparelho: usa a voz do Google Tradutor, pela internet
            falarOnline(ctx.applicationContext, texto, fim)
            return
        }
        geracaoOnline++
        val id = "fala-${contador++}"
        falando = true
        aplicarVolume()
        aoTerminar.clear() // QUEUE_FLUSH abaixo corta a fala anterior: ela nao deve avisar fim nenhum
        aoTerminar[id] = fim
        // Seguranca: nunca trava o fluxo, mas deixa tempo para respostas longas.
        main.postDelayed({ terminou(id) }, TEMPO_MAXIMO_MS + texto.length * 90L)
        val r = t.speak(texto, TextToSpeech.QUEUE_FLUSH, Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, CANAL) }, id)
        if (r != TextToSpeech.SUCCESS) terminou(id)
    }

    // ---------------- voz online (Google Tradutor, pt-BR), usada quando o aparelho não tem voz em português ----------------

    private var geracaoOnline = 0
    private var tocador: android.media.MediaPlayer? = null

    private fun dividir(texto: String, max: Int): List<String> {
        val partes = ArrayList<String>()
        var atual = StringBuilder()
        for (frase in Regex("""(?<=[.!?,;])\s+""").split(texto)) {
            if (atual.isNotEmpty() && atual.length + frase.length + 1 > max) { partes.add(atual.toString()); atual = StringBuilder() }
            if (frase.length > max) { frase.chunked(max).forEach { partes.add(it) } } else { if (atual.isNotEmpty()) atual.append(' '); atual.append(frase) }
        }
        if (atual.isNotEmpty()) partes.add(atual.toString())
        return partes
    }

    private fun falarOnline(ctx: Context, texto: String, fim: () -> Unit) {
        val minha = ++geracaoOnline
        falando = true
        aplicarVolume()
        aoTerminar.clear()
        Thread {
            val arquivos = ArrayList<File>()
            try {
                for ((i, parte) in dividir(texto, 180).withIndex()) {
                    if (minha != geracaoOnline) return@Thread
                    val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=pt-BR&q=" + java.net.URLEncoder.encode(parte, "UTF-8")
                    val con = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 7.1.2) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                    con.connectTimeout = 6000; con.readTimeout = 10000
                    val f = File(ctx.cacheDir, "fala_${minha}_$i.mp3")
                    con.inputStream.use { ent -> f.outputStream().use { ent.copyTo(it) } }
                    arquivos.add(f)
                }
            } catch (e: Exception) {
                Log.d(TAG, "fala online falhou: $e")
            }
            main.post {
                if (minha != geracaoOnline) { arquivos.forEach { it.delete() }; return@post }
                tocarArquivos(arquivos, 0, minha, fim)
            }
        }.start()
    }

    private fun tocarArquivos(arquivos: List<File>, i: Int, minha: Int, fim: () -> Unit) {
        if (minha != geracaoOnline) { arquivos.forEach { it.delete() }; return }
        if (i >= arquivos.size) {
            arquivos.forEach { it.delete() }
            falando = false
            devolverVolume()
            fim()
            return
        }
        try {
            val mp = android.media.MediaPlayer()
            tocador = mp
            @Suppress("DEPRECATION") mp.setAudioStreamType(CANAL)
            mp.setDataSource(arquivos[i].path)
            mp.setOnCompletionListener { m -> m.release(); if (tocador === m) tocador = null; tocarArquivos(arquivos, i + 1, minha, fim) }
            mp.setOnErrorListener { m, _, _ -> m.release(); if (tocador === m) tocador = null; tocarArquivos(arquivos, arquivos.size, minha, fim); true }
            mp.prepare()
            mp.start()
            // fala natural, de gente: mais rápida, sem ficar fina (a voz do Tradutor é lenta)
            try { mp.playbackParams = mp.playbackParams.setSpeed(1.55f).setPitch(1.0f) } catch (_: Throwable) {}
        } catch (e: Exception) {
            Log.d(TAG, "nao consegui tocar a fala online: $e")
            tocarArquivos(arquivos, arquivos.size, minha, fim)
        }
    }

    /** Para de falar agora (ex.: a pessoa disse "Alexa" de novo no meio da resposta). */
    fun parar() {
        falando = false
        devolverVolume()
        geracaoOnline++
        try { tocador?.stop(); tocador?.release() } catch (_: Throwable) {}
        tocador = null
        aoTerminar.clear() // ninguem mais deve ser avisado do fim desta fala
        try {
            tts?.stop()
        } catch (_: Throwable) {
        }
    }

    private fun terminou(id: String?) {
        if (id == null) return
        main.post {
            aoTerminar.remove(id)?.invoke()
            if (aoTerminar.isEmpty()) { falando = false; devolverVolume() }
        }
    }
}
