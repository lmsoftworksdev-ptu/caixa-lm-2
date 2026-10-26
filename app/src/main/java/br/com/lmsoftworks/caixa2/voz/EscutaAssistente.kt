package br.com.lmsoftworks.caixa2.voz

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Microfone do assistente, em uma única leitura contínua (sem "buraco" entre a palavra de ativação e o pedido):
 *  - modo CHAMADA: manda o áudio ao Vosk (offline) e entrega o JSON dele em [aoTexto];
 *  - modo PEDIDO ([gravarPedido]): guarda o áudio até a pessoa parar de falar e entrega um WAV em [aoPedido].
 * O áudio recebe ganho automático (até [GANHO_MAXIMO]x) para a voz chegar forte mesmo com o som da caixa alto.
 */
class EscutaAssistente(
    private val reconhecedor: Recognizer,
    private val comandos: Recognizer?,
    private val aoTexto: (campo: String, json: String) -> Unit
) {
    private companion object {
        const val TAG = "Assistente"
        const val TAXA = 16000
        const val GANHO_MAXIMO = 22f
        const val NIVEL_ALVO = 7000f
        const val NIVEL_SILENCIO = 25f
        const val FALA_MINIMA = 900f           // volume (RMS) acima do qual conta como "falando"
        const val SILENCIO_FINAL_MS = 1400L     // quanto silêncio depois da fala encerra o pedido
        const val ESPERA_FALA_MS = 9000L        // quanto espera a pessoa começar a falar
        const val PEDIDO_MAXIMO_MS = 16000L
    }

    @Volatile private var rodando = false
    private var thread: Thread? = null
    private val efeitos = ArrayList<android.media.audiofx.AudioEffect>()

    // estado do pedido em andamento (mexido só pela thread do microfone, pedido feito por qualquer uma)
    @Volatile private var pedindo = false
    private var aoPedido: ((ByteArray?, String?) -> Unit)? = null
    private var gravacao = ByteArrayOutputStream()
    private var inicioPedido = 0L
    private var falou = false
    private var silencioDesde = 0L
    private var pisoRuido = Float.MAX_VALUE     // o menor volume visto durante o pedido: o ruído de fundo da sala
    private var blocosVistos = 0

    /** Volume (0..1) do que o microfone ouve agora, para a tela animar a esfera. */
    @Volatile var nivel = 0f
        private set

    fun iniciar(): Boolean {
        val minimo = AudioRecord.getMinBufferSize(TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimo <= 0) return false
        val gr = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, TAXA, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minimo * 2, TAXA)
        )
        if (gr.state != AudioRecord.STATE_INITIALIZED) { gr.release(); return false }
        try {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(gr.audioSessionId)?.also { it.enabled = true; efeitos += it }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(gr.audioSessionId)?.also { it.enabled = true; efeitos += it }
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(gr.audioSessionId)?.also { it.enabled = true; efeitos += it }
            Log.d(TAG, "microfone: ${efeitos.size} efeitos ligados (eco/ruido/ganho)")
        } catch (e: Throwable) { Log.d(TAG, "microfone: sem efeitos: $e") }
        rodando = true
        thread = Thread({ laco(gr) }, "escuta-assistente").also { it.start() }
        return true
    }

    /** Passa a gravar o pedido; [fim] recebe o WAV (ou null se a pessoa não falou nada). */
    fun gravarPedido(fim: (ByteArray?, String?) -> Unit) {
        limpar = true          // zera os dois reconhecedores (pela thread do microfone) antes de começar o pedido
        aoPedido = fim
        gravacao = ByteArrayOutputStream()
        falou = false
        silencioDesde = 0L
        pisoRuido = Float.MAX_VALUE; blocosVistos = 0
        inicioPedido = System.currentTimeMillis()
        pedindo = true
    }

    /** Abandona o pedido em andamento (a pessoa tocou na tela ou chamou de novo). */
    fun cancelarPedido() {
        pedindo = false
        aoPedido = null
        limpar = true          // quem zera o reconhecedor é a thread do microfone (mexer nele de outra thread corrompe a memória)
    }

    @Volatile private var limpar = false

    private fun laco(gr: AudioRecord) {
        val bloco = ShortArray(1600) // 0,1 s
        var ganho = 1f
        try {
            gr.startRecording()
            while (rodando) {
                val n = gr.read(bloco, 0, bloco.size)
                if (n <= 0) continue
                if (limpar) { limpar = false; reconhecedor.reset(); comandos?.reset() }
                var soma = 0.0
                for (i in 0 until n) soma += bloco[i] * bloco[i].toDouble()
                val rmsBruto = sqrt(soma / n).toFloat()
                if (rmsBruto > NIVEL_SILENCIO) {
                    val desejado = min(GANHO_MAXIMO, max(1f, NIVEL_ALVO / rmsBruto))
                    ganho += (desejado - ganho) * 0.3f
                }
                if (ganho > 1.05f) for (i in 0 until n) {
                    val v = bloco[i] * ganho
                    bloco[i] = when { v > Short.MAX_VALUE -> Short.MAX_VALUE; v < Short.MIN_VALUE -> Short.MIN_VALUE; else -> v.toInt().toShort() }
                }
                nivel = min(1f, rmsBruto / 3000f)

                if (pedindo) { tratarPedido(bloco, n, rmsBruto); continue }
                val fim = reconhecedor.acceptWaveForm(bloco, n)
                if (fim) aoTexto("text", reconhecedor.result) else aoTexto("partial", reconhecedor.partialResult)
            }
        } catch (e: Throwable) {
            Log.d(TAG, "escuta parou: $e")
        } finally {
            try { gr.stop() } catch (_: Throwable) {}
            gr.release()
            efeitos.forEach { try { it.release() } catch (_: Throwable) {} }
            efeitos.clear()
            try { reconhecedor.close() } catch (_: Throwable) {}
            try { comandos?.close() } catch (_: Throwable) {}
        }
    }

    private fun tratarPedido(bloco: ShortArray, n: Int, rmsBruto: Float) {
        val agora = System.currentTimeMillis()
        // o ganho automático levanta o chiado: a decisão de "está falando" usa o volume ORIGINAL do microfone
        blocosVistos++
        if (blocosVistos > 2 && rmsBruto < pisoRuido) pisoRuido = rmsBruto
        // "falando" = bem acima do ruído de fundo da sala (que a própria sala define) e acima de um mínimo
        val falando = blocosVistos > 2 && rmsBruto > max(110f, pisoRuido * 2.5f)
        if (blocosVistos % 5 == 0) Log.d(TAG, "pedido: volume=${rmsBruto.toInt()} piso=${if (pisoRuido == Float.MAX_VALUE) -1 else pisoRuido.toInt()} falando=$falando")
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) { bytes[2 * i] = (bloco[i].toInt() and 0xff).toByte(); bytes[2 * i + 1] = (bloco[i].toInt() shr 8).toByte() }
        gravacao.write(bytes)
        comandos?.acceptWaveForm(bloco, n)          // em paralelo: se for um comando fixo, o app executa sem esperar a internet
        if (falando) { falou = true; silencioDesde = 0L } else if (silencioDesde == 0L) silencioDesde = agora
        val total = agora - inicioPedido
        val terminou = (falou && silencioDesde != 0L && agora - silencioDesde >= SILENCIO_FINAL_MS) ||
            (!falou && total >= ESPERA_FALA_MS) || total >= PEDIDO_MAXIMO_MS
        if (!terminou) return
        pedindo = false
        val pcm = gravacao.toByteArray()
        val cb = aoPedido; aoPedido = null
        reconhecedor.reset()
        val jsonComando = try { comandos?.finalResult } catch (_: Throwable) { null }
        try { comandos?.reset() } catch (_: Throwable) {}
        Log.d(TAG, "pedido gravado: ${total} ms, falou=$falou, ${pcm.size} bytes")
        cb?.invoke(if (falou) wav(pcm) else null, jsonComando)
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val saida = ByteArrayOutputStream()
        fun int32(v: Int) { saida.write(v and 0xff); saida.write((v shr 8) and 0xff); saida.write((v shr 16) and 0xff); saida.write((v shr 24) and 0xff) }
        fun int16(v: Int) { saida.write(v and 0xff); saida.write((v shr 8) and 0xff) }
        saida.write("RIFF".toByteArray()); int32(36 + pcm.size); saida.write("WAVEfmt ".toByteArray())
        int32(16); int16(1); int16(1); int32(TAXA); int32(TAXA * 2); int16(2); int16(16)
        saida.write("data".toByteArray()); int32(pcm.size); saida.write(pcm)
        return saida.toByteArray()
    }

    fun parar() {
        rodando = false
        try { thread?.join(800) } catch (_: InterruptedException) {}
        thread = null
    }
}
