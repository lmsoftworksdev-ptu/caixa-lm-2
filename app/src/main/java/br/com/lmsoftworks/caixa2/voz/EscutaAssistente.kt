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
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Microfone do assistente. São DUAS threads, para nunca perder áudio:
 *  - a thread do MICROFONE só lê o áudio, aplica o ganho, grava o pedido e detecta quando a pessoa parou de falar. Não decodifica nada,
 *    então nunca fica para trás (antes, o reconhecimento na mesma thread atrasava a leitura e partes da fala se perdiam);
 *  - a thread do RECONHECIMENTO recebe os blocos por uma fila e roda o Vosk: a palavra de ativação (modo chamada) ou, durante o pedido,
 *    os comandos fixos e o texto ao vivo. O Vosk só é mexido por essa thread.
 * Ao fim do pedido entrega o WAV completo (para o Gemini) e o JSON do reconhecedor de comandos (para a via rápida).
 */
class EscutaAssistente(
    private val reconhecedor: Recognizer,
    private val comandos: Recognizer?,
    private val livre: Recognizer?,
    private val aoParcial: (String) -> Unit,
    private val aoTexto: (campo: String, json: String) -> Unit
) {
    private companion object {
        const val TAG = "Assistente"
        const val TAXA = 16000
        const val GANHO_MAXIMO = 28f
        const val NIVEL_ALVO = 7000f
        const val NIVEL_SILENCIO = 25f
        const val SILENCIO_FINAL_MS = 1200L     // quanto silêncio depois da fala encerra o pedido
        const val ESPERA_FALA_MS = 9000L        // quanto espera a pessoa começar a falar
        const val PEDIDO_MAXIMO_MS = 16000L
        const val BLOCOS_FALA_MINIMOS = 4       // menos que isso (0,4 s de voz) é ruído, não pedido: nada vai ao Gemini
    }

    private class Bloco(val dados: ShortArray?, val tipo: Int, val wav: ByteArray? = null, val fim: ((ByteArray?, String?) -> Unit)? = null) {
        companion object { const val CHAMADA = 0; const val PEDIDO = 1; const val ZERAR = 2; const val FIM = 3; const val ENCERRAR = 4 }
    }

    @Volatile private var rodando = false
    private var threadMic: Thread? = null
    private var threadVosk: Thread? = null
    private val fila = LinkedBlockingQueue<Bloco>()
    private val efeitos = ArrayList<android.media.audiofx.AudioEffect>()

    // estado do pedido em andamento (mexido só pela thread do microfone)
    @Volatile private var pedindo = false
    private var aoPedido: ((ByteArray?, String?) -> Unit)? = null
    private var gravacao = ByteArrayOutputStream()
    private var inicioPedido = 0L
    private var falou = false
    private var silencioDesde = 0L
    private var pisoRuido = Float.MAX_VALUE
    private var blocosVistos = 0
    private var blocosFala = 0
    @Volatile private var pedidoNovo = false

    // Porta de voz da CHAMADA: o reconhecimento só roda quando há som de fala (com uns blocos de antes e de depois). Na sala quieta o
    // Vosk fica parado, em vez de gastar um núcleo inteiro do processador o tempo todo.
    private var pisoSala = -1f
    private var restoVoz = 0
    private var enviandoChamada = false
    private val antes = java.util.ArrayDeque<ShortArray>()

    /** Volume (0..1) do que o microfone ouve agora, para a tela animar a esfera. */
    @Volatile var nivel = 0f
        private set

    fun iniciar(): Boolean {
        val minimo = AudioRecord.getMinBufferSize(TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimo <= 0) return false
        val gr = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, TAXA, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minimo * 4, TAXA * 2)
        )
        if (gr.state != AudioRecord.STATE_INITIALIZED) { gr.release(); return false }
        try {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(gr.audioSessionId)?.also { it.enabled = true; efeitos += it }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(gr.audioSessionId)?.also { it.enabled = true; efeitos += it }
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(gr.audioSessionId)?.also { it.enabled = true; efeitos += it }
            Log.d(TAG, "microfone: ${efeitos.size} efeitos ligados (eco/ruido/ganho)")
        } catch (e: Throwable) { Log.d(TAG, "microfone: sem efeitos: $e") }
        rodando = true
        threadVosk = Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT); lacoVosk() }, "escuta-vosk").also { it.start() }
        threadMic = Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO); lacoMicrofone(gr) }, "escuta-mic").also { it.start() }
        return true
    }

    /** Passa a gravar o pedido; [fim] recebe o WAV (ou null se não houve fala de verdade) e o JSON dos comandos fixos. */
    fun gravarPedido(fim: (ByteArray?, String?) -> Unit) {
        aoPedido = fim
        pedidoNovo = true           // a thread do microfone zera tudo e começa a gravar
        pedindo = true
    }

    /** Abandona o pedido em andamento (a pessoa tocou na tela ou chamou de novo). */
    fun cancelarPedido() {
        pedindo = false
        aoPedido = null
        fila.offer(Bloco(null, Bloco.ZERAR))
    }

    // ---------------------------------------------------------------- thread do microfone: ler, gravar, detectar fim da fala

    private fun lacoMicrofone(gr: AudioRecord) {
        val bloco = ShortArray(1600) // 0,1 s
        var ganho = 1f
        try {
            gr.startRecording()
            while (rodando) {
                val n = gr.read(bloco, 0, bloco.size)
                if (n <= 0) continue
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

                if (pedidoNovo) { pedidoNovo = false; iniciarGravacao() }
                if (pedindo) {
                    fila.offer(Bloco(bloco.copyOf(n), Bloco.PEDIDO))
                    tratarPedido(bloco, n, rmsBruto)
                } else {
                    // modo chamada, só com voz: guarda 3 blocos de antes e segue 8 blocos (0,8 s) depois de a voz parar
                    if (pisoSala < 0f) pisoSala = max(3f, rmsBruto)
                    pisoSala = if (rmsBruto < pisoSala) max(3f, rmsBruto) else pisoSala * 0.9995f + rmsBruto * 0.0005f
                    val voz = rmsBruto > max(70f, pisoSala * 2.2f)
                    if (voz) restoVoz = 8 else if (restoVoz > 0) restoVoz--
                    val copia = bloco.copyOf(n)
                    if (voz || restoVoz > 0) {
                        if (!enviandoChamada) { enviandoChamada = true; for (a in antes) fila.offer(Bloco(a, Bloco.CHAMADA)); antes.clear() }
                        if (fila.size > 25) fila.poll()
                        fila.offer(Bloco(copia, Bloco.CHAMADA))
                    } else {
                        enviandoChamada = false
                        antes.addLast(copia); if (antes.size > 3) antes.removeFirst()
                    }
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "microfone parou: $e")
        } finally {
            try { gr.stop() } catch (_: Throwable) {}
            gr.release()
            efeitos.forEach { try { it.release() } catch (_: Throwable) {} }
            efeitos.clear()
            fila.offer(Bloco(null, Bloco.ENCERRAR))      // acorda e encerra a thread do reconhecimento
        }
    }

    private fun iniciarGravacao() {
        gravacao = ByteArrayOutputStream()
        falou = false; silencioDesde = 0L; pisoRuido = Float.MAX_VALUE; blocosVistos = 0; blocosFala = 0
        inicioPedido = System.currentTimeMillis()
        fila.offer(Bloco(null, Bloco.ZERAR))      // o reconhecimento zera os reconhecedores antes do 1º bloco do pedido
    }

    private fun tratarPedido(bloco: ShortArray, n: Int, rmsBruto: Float) {
        val agora = System.currentTimeMillis()
        blocosVistos++
        if (blocosVistos > 2 && rmsBruto < pisoRuido) pisoRuido = rmsBruto
        // "falando" = bem acima do ruído de fundo da sala (que a própria sala define) e acima de um mínimo
        val falando = blocosVistos > 2 && rmsBruto > max(110f, pisoRuido * 2.5f)
        if (falando) blocosFala++
        if (blocosVistos % 10 == 0) Log.d(TAG, "pedido: volume=${rmsBruto.toInt()} piso=${if (pisoRuido == Float.MAX_VALUE) -1 else pisoRuido.toInt()} falando=$falando fila=${fila.size}")
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) { bytes[2 * i] = (bloco[i].toInt() and 0xff).toByte(); bytes[2 * i + 1] = (bloco[i].toInt() shr 8).toByte() }
        gravacao.write(bytes)
        if (falando) { falou = true; silencioDesde = 0L } else if (silencioDesde == 0L) silencioDesde = agora
        val total = agora - inicioPedido
        val terminou = (falou && silencioDesde != 0L && agora - silencioDesde >= SILENCIO_FINAL_MS) ||
            (!falou && total >= ESPERA_FALA_MS) || total >= PEDIDO_MAXIMO_MS
        if (!terminou) return
        pedindo = false
        val pcm = gravacao.toByteArray()
        val cb = aoPedido; aoPedido = null
        val voz = falou && blocosFala >= BLOCOS_FALA_MINIMOS
        Log.d(TAG, "pedido gravado: $total ms, audio=${pcm.size / 32} ms, blocos de voz=$blocosFala, voz=$voz")
        // quem entrega o resultado é a thread do reconhecimento, depois de terminar de decodificar tudo o que já chegou
        fila.offer(Bloco(null, Bloco.FIM, if (voz) wav(pcm) else null, cb))
    }

    // ---------------------------------------------------------------- thread do reconhecimento (Vosk)

    private fun lacoVosk() {
        var textoLivre = ""
        var ultimoParcial = ""
        try {
            while (true) {
                val b = fila.take()
                when (b.tipo) {
                    Bloco.ENCERRAR -> break
                    Bloco.CHAMADA -> {
                        if (pedindo) continue
                        val d = b.dados!!
                        val fim = reconhecedor.acceptWaveForm(d, d.size)
                        if (fim) aoTexto("text", reconhecedor.result) else aoTexto("partial", reconhecedor.partialResult)
                    }
                    Bloco.PEDIDO -> {
                        val d = b.dados!!
                        comandos?.acceptWaveForm(d, d.size)
                        // texto ao vivo; se o reconhecimento já está atrasado, pula esta parte (os comandos e o áudio completo continuam)
                        if (livre != null && fila.size < 12) {
                            val fechou = livre.acceptWaveForm(d, d.size)
                            val atual = lerJson(if (fechou) livre.result else livre.partialResult, if (fechou) "text" else "partial")
                            if (fechou && atual.isNotBlank()) textoLivre = (textoLivre + " " + atual).trim()
                            val mostrar = if (fechou) textoLivre else (textoLivre + " " + atual).trim()
                            if (mostrar != ultimoParcial) { ultimoParcial = mostrar; aoParcial(mostrar) }
                        }
                    }
                    Bloco.ZERAR -> {
                        reconhecedor.reset(); comandos?.reset(); livre?.reset(); textoLivre = ""; ultimoParcial = ""
                    }
                    Bloco.FIM -> {
                        val cb = b.fim ?: continue
                        val json = try { comandos?.finalResult } catch (_: Throwable) { null }
                        val vivo = try { livre?.finalResult } catch (_: Throwable) { null }
                        val dito = (textoLivre + " " + lerJson(vivo, "text")).trim()
                        val cmd = lerJson(json, "text")
                        Log.d(TAG, "reconhecimento offline: comandos=\"$cmd\" | livre=\"$dito\"")
                        // sem palavra nenhuma (só ruído): nada vai ao Gemini, que inventaria uma frase
                        val wav = if (b.wav != null && (dito.isNotBlank() || (cmd.isNotBlank() && cmd != "[unk]"))) b.wav else null
                        if (b.wav != null && wav == null) Log.d(TAG, "o reconhecimento offline não ouviu palavra nenhuma: pedido descartado (ruído)")
                        reconhecedor.reset(); comandos?.reset(); livre?.reset(); textoLivre = ""; ultimoParcial = ""
                        cb(wav, json)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "reconhecimento parou: $e")
        } finally {
            try { reconhecedor.close() } catch (_: Throwable) {}
            try { comandos?.close() } catch (_: Throwable) {}
            try { livre?.close() } catch (_: Throwable) {}
        }
    }

    private fun lerJson(json: String?, campo: String): String =
        try { org.json.JSONObject(json ?: "{}").optString(campo, "") } catch (_: Exception) { "" }

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
        try { threadMic?.join(1500) } catch (_: InterruptedException) {}
        try { threadVosk?.join(3000) } catch (_: InterruptedException) {}
        threadMic = null; threadVosk = null
    }
}
