package br.com.lmsoftworks.caixa2

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Equalizador DENTRO do player (software): filtros biquad nas faixas do menu, aplicados ao som da música e do vídeo do app antes de ele sair.
 * Não usa os efeitos do Android (que ligam e desligam a cada faixa e fazem estalo no começo do som). Com tudo em 0 dB o som passa sem mexer.
 */
@UnstableApi
class EqualizadorSoftware : BaseAudioProcessor() {
    private class Biquad(var b0: Float = 1f, var b1: Float = 0f, var b2: Float = 0f, var a1: Float = 0f, var a2: Float = 0f)

    private var taxa = 0
    private var canais = 0
    private var versao = -1
    private val filtros = ArrayList<Biquad>()
    private var estados = Array(0) { FloatArray(0) }       // [canal][2 * filtro]: z1 e z2
    private var ganhoPrevio = 1f
    private var processar = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        taxa = inputAudioFormat.sampleRate
        canais = inputAudioFormat.channelCount
        versao = -1
        return inputAudioFormat
    }

    override fun onFlush() { estados = Array(canais) { FloatArray(2 * filtros.size) } }

    /** Refaz os filtros quando o menu mudou algo (os estados dos filtros são mantidos para não dar salto no som). */
    private fun atualizar() {
        versao = Amplificador.versao
        val novos = ArrayList<Biquad>()
        var maiorAlta = 0f
        if (Amplificador.ligado && taxa > 0) {
            for (b in 0 until Amplificador.nBandas) {
                val db = Amplificador.niveis[b] / 100f
                if (Math.abs(db) < 0.05f) continue
                novos.add(pico(Amplificador.centrosHz[b].toFloat(), db, 0.9f))
                if (db > maiorAlta) maiorAlta = db
            }
            val graves = Amplificador.graves / 1000f * 10f      // até +10 dB de realce nos graves
            if (graves > 0.05f) { novos.add(prateleiraGrave(110f, graves)); if (graves > maiorAlta) maiorAlta = graves }
        }
        // folga contra distorção: baixa o volume geral um pouco do quanto os realces somam
        ganhoPrevio = if (maiorAlta > 0f) 10.0.pow(-(maiorAlta * 0.6) / 20.0).toFloat() else 1f
        if (novos.size != filtros.size) estados = Array(canais) { FloatArray(2 * novos.size) }
        filtros.clear(); filtros.addAll(novos)
        processar = filtros.isNotEmpty()
    }

    private fun pico(f0: Float, db: Float, q: Float): Biquad {
        val a = 10.0.pow(db / 40.0)
        val w0 = 2.0 * PI * f0 / taxa
        val alfa = sin(w0) / (2.0 * q)
        val b0 = 1 + alfa * a; val b1 = -2 * cos(w0); val b2 = 1 - alfa * a
        val a0 = 1 + alfa / a; val a1 = -2 * cos(w0); val a2 = 1 - alfa / a
        return Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())
    }

    private fun prateleiraGrave(f0: Float, db: Float): Biquad {
        val a = 10.0.pow(db / 40.0)
        val w0 = 2.0 * PI * f0 / taxa
        val cosw = cos(w0)
        val alfa = sin(w0) / 2.0 * sqrt(2.0)       // S = 1
        val dois = 2.0 * sqrt(a) * alfa
        val b0 = a * ((a + 1) - (a - 1) * cosw + dois)
        val b1 = 2 * a * ((a - 1) - (a + 1) * cosw)
        val b2 = a * ((a + 1) - (a - 1) * cosw - dois)
        val a0 = (a + 1) + (a - 1) * cosw + dois
        val a1 = -2 * ((a - 1) + (a + 1) * cosw)
        val a2 = (a + 1) + (a - 1) * cosw - dois
        return Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val tam = inputBuffer.remaining()
        if (tam == 0) return
        if (versao != Amplificador.versao) atualizar()
        val saida = replaceOutputBuffer(tam)
        if (!processar) { saida.put(inputBuffer); saida.flip(); return }
        val entrada = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        saida.order(ByteOrder.nativeOrder())
        val n = entrada.remaining() / canais
        val nf = filtros.size
        for (i in 0 until n) {
            for (c in 0 until canais) {
                var x = entrada.get().toFloat() * ganhoPrevio
                val z = estados[c]
                for (k in 0 until nf) {
                    val f = filtros[k]
                    val y = f.b0 * x + z[2 * k]
                    z[2 * k] = f.b1 * x - f.a1 * y + z[2 * k + 1]
                    z[2 * k + 1] = f.b2 * x - f.a2 * y
                    x = y
                }
                saida.putShort(x.coerceIn(-32768f, 32767f).toInt().toShort())
            }
        }
        inputBuffer.position(inputBuffer.limit())
        saida.flip()
    }
}
