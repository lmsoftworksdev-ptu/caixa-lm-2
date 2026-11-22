package br.com.lmsoftworks.caixa2.voz

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Detector da palavra de chamada "Alexa" pelo openWakeWord (github.com/dscripka/openWakeWord, código Apache-2.0; os modelos prontos são
 * CC BY-NC-SA 4.0, só para uso não comercial). Três modelos TFLite em sequência, a cada 80 ms (1280 amostras a 16 kHz):
 *   1. espectrograma mel: as últimas 1760 amostras viram 8 quadros de 32 números (depois x/10 + 2);
 *   2. embedding: os últimos 76 quadros mel viram um vetor de 96 números;
 *   3. classificador "alexa": os últimos 16 vetores viram uma nota de 0 a 1.
 * Foi validado fora do aparelho com voz sintética ("Alexa" ~0,99; outras frases ~0).
 * Use sempre da MESMA thread: os intérpretes do TFLite não são seguros entre threads.
 */
class OpenWakeWord(private val ctx: Context) {
    private companion object {
        const val TAG = "Assistente"
        const val CHUNK = 1280
        const val JANELA_MEL = CHUNK + 160 * 3      // 1760 amostras
        const val QUADROS_MEL = 8                    // quadros novos por chunk
        const val BINS = 32
        const val QUADROS_EMB = 76
        const val DIM_EMB = 96
        const val VETORES = 16
    }

    private fun carregar(nome: String): ByteBuffer {
        val bytes = ctx.assets.open("oww/$nome").use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
    }

    private val mel: Interpreter
    private val emb: Interpreter
    private val cls: Interpreter

    init {
        val umaThread = Interpreter.Options().setNumThreads(1)
        mel = Interpreter(carregar("melspectrogram.tflite"), Interpreter.Options().setNumThreads(1).setUseXNNPACK(false))
        // o modelo de mel já vem com a entrada fixada em [1, 1760] (o original tem tamanho dinâmico e quebra ao alocar no Android)
        emb = Interpreter(carregar("embedding_model.tflite"), umaThread)
        cls = Interpreter(carregar("alexa_v0.1.tflite"), Interpreter.Options().setNumThreads(1))
        Log.d(TAG, "openWakeWord carregado")
    }

    // entradas e saídas reaproveitadas (nada é alocado a cada chunk)
    private val entradaMel = ByteBuffer.allocateDirect(JANELA_MEL * 4).order(ByteOrder.nativeOrder())
    private val saidaMel = ByteBuffer.allocateDirect(QUADROS_MEL * BINS * 4).order(ByteOrder.nativeOrder())
    private val entradaEmb = ByteBuffer.allocateDirect(QUADROS_EMB * BINS * 4).order(ByteOrder.nativeOrder())
    private val saidaEmb = ByteBuffer.allocateDirect(DIM_EMB * 4).order(ByteOrder.nativeOrder())
    private val entradaCls = ByteBuffer.allocateDirect(VETORES * DIM_EMB * 4).order(ByteOrder.nativeOrder())
    private val saidaCls = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

    private val pendente = ShortArray(CHUNK)
    private var nPendente = 0
    private val bruto = FloatArray(JANELA_MEL)      // as últimas 1760 amostras
    private var nBruto = 0
    private val espectro = Array(QUADROS_EMB) { FloatArray(BINS) { 1f } }      // os últimos 76 quadros mel (começa com 1, como no original)
    private val vetores = Array(VETORES) { FloatArray(DIM_EMB) }
    private var nVetores = 0

    /** Tempo médio gasto por chunk (ms), para o registro. */
    var msMedio = 0f; private set

    /** Entrega amostras de qualquer tamanho; devolve a maior nota dos chunks completados (ou -1 se nenhum completou). */
    fun alimentar(dados: ShortArray, n: Int): Float {
        var maior = -1f
        var i = 0
        while (i < n) {
            val levar = minOf(CHUNK - nPendente, n - i)
            System.arraycopy(dados, i, pendente, nPendente, levar)
            nPendente += levar; i += levar
            if (nPendente == CHUNK) {
                val t0 = android.os.SystemClock.elapsedRealtimeNanos()
                val nota = processarChunk()
                val ms = (android.os.SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000f
                msMedio = if (msMedio == 0f) ms else msMedio * 0.95f + ms * 0.05f
                if (nota > maior) maior = nota
                nPendente = 0
            }
        }
        return maior
    }

    private fun processarChunk(): Float {
        // desliza a janela bruta de 1760 amostras
        System.arraycopy(bruto, CHUNK, bruto, 0, JANELA_MEL - CHUNK)
        for (k in 0 until CHUNK) bruto[JANELA_MEL - CHUNK + k] = pendente[k].toFloat()
        nBruto = minOf(nBruto + CHUNK, JANELA_MEL)
        if (nBruto < JANELA_MEL) return -1f      // ainda sem as 1760 amostras

        // 1) mel
        entradaMel.rewind()
        for (v in bruto) entradaMel.putFloat(v)
        entradaMel.rewind(); saidaMel.rewind()
        mel.run(entradaMel, saidaMel)
        saidaMel.rewind()
        // desloca os 76 quadros e põe os 8 novos no fim
        System.arraycopy(espectro, QUADROS_MEL, espectro, 0, QUADROS_EMB - QUADROS_MEL)
        for (q in 0 until QUADROS_MEL) {
            val quadro = FloatArray(BINS)
            for (b in 0 until BINS) quadro[b] = saidaMel.float / 10f + 2f
            espectro[QUADROS_EMB - QUADROS_MEL + q] = quadro
        }

        // 2) embedding dos últimos 76 quadros
        entradaEmb.rewind()
        for (q in 0 until QUADROS_EMB) for (b in 0 until BINS) entradaEmb.putFloat(espectro[q][b])
        entradaEmb.rewind(); saidaEmb.rewind()
        emb.run(entradaEmb, saidaEmb)
        saidaEmb.rewind()
        System.arraycopy(vetores, 1, vetores, 0, VETORES - 1)
        val novo = FloatArray(DIM_EMB)
        for (d in 0 until DIM_EMB) novo[d] = saidaEmb.float
        vetores[VETORES - 1] = novo
        nVetores = minOf(nVetores + 1, VETORES)
        if (nVetores < VETORES) return -1f

        // 3) classificador
        entradaCls.rewind()
        for (v in vetores) for (x in v) entradaCls.putFloat(x)
        entradaCls.rewind(); saidaCls.rewind()
        cls.run(entradaCls, saidaCls)
        saidaCls.rewind()
        return saidaCls.float
    }

    /** Esquece o som que já passou (depois de uma chamada ou de um pedido): sem isso a "Alexa" ainda guardada nos últimos 1,3 s chamava de novo. */
    fun reiniciar() {
        nPendente = 0; nBruto = 0; nVetores = 0
        for (q in espectro) java.util.Arrays.fill(q, 1f)
        for (v in vetores) java.util.Arrays.fill(v, 0f)
        java.util.Arrays.fill(bruto, 0f)
    }

    fun fechar() {
        try { mel.close() } catch (_: Throwable) { }
        try { emb.close() } catch (_: Throwable) { }
        try { cls.close() } catch (_: Throwable) { }
    }
}
