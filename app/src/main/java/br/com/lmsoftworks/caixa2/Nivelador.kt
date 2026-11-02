package br.com.lmsoftworks.caixa2

import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Visualizer
import android.os.Handler
import android.util.Log

/**
 * Nivelamento de áudio das músicas e vídeos tocados pelo app: algumas faixas vêm mais baixas que outras. Enquanto a faixa toca,
 * o nível dela é medido (Visualizer) e as mais baixas são levantadas (LoudnessEnhancer) até o nível das mais fortes; as que já
 * são fortes ficam como estão (o nivelamento nunca abaixa). Tudo acontece antes do volume da caixa, então vale em qualquer volume.
 *
 * O nível medido já inclui o ganho que estamos dando, por isso ele é descontado para achar o nível original da faixa.
 */
class Nivelador(private val handler: Handler, private val medindoLimpo: () -> Boolean) {

    private companion object {
        const val TAG = "Nivelador"
        const val ALVO_DB = -14.0          // nível médio (RMS) em que as faixas ficam, em dB abaixo do máximo
        const val MAXIMO_MB = 900          // no máximo +9 dB
        const val PASSO_MB = 40            // quanto o ganho muda por medida (~4 dB por segundo): sem estalos
        const val QUADROS_INICIAIS = 8     // medidas antes de começar a mexer (~1 s)
        const val QUADROS_FIXOS = 40       // até aqui a média é simples; depois ela se ajusta devagar
    }

    private var sessao = -1
    private var realce: LoudnessEnhancer? = null
    private var medidor: Visualizer? = null
    @Volatile private var ganhoMb = 0
    private var energia = 0.0
    private var quadros = 0

    /** Liga o nivelamento na saída de áudio [id] do player (refaz se o player mudou). */
    fun ligar(id: Int) {
        if (id <= 0) return
        if (id == sessao && realce != null) return
        desligar()
        try {
            realce = LoudnessEnhancer(id).apply { setTargetGain(0); enabled = true }
            medidor = Visualizer(id).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, taxa: Int) { if (waveform != null) medir(waveform) }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, taxa: Int) {}
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
            sessao = id
            ganhoMb = 0
            Log.d(TAG, "ligado na sessão $id")
        } catch (e: Throwable) {
            Log.d(TAG, "não consegui ligar: $e")
            desligar()
        }
    }

    /** Começou outra faixa: mede de novo (o ganho volta ao normal aos poucos, enquanto a nova medida não chega). */
    fun novaFaixa() { energia = 0.0; quadros = 0 }

    fun desligar() {
        try { medidor?.enabled = false; medidor?.release() } catch (_: Throwable) {}
        try { realce?.release() } catch (_: Throwable) {}
        medidor = null; realce = null; sessao = -1; ganhoMb = 0
    }

    private fun medir(w: ByteArray) {
        if (!Ajustes.nivelarAudio) { mover(0); return }
        if (!medindoLimpo()) return                 // durante fade ou com o volume do player abaixado a medida não vale
        var soma = 0.0
        for (b in w) { val x = ((b.toInt() and 0xFF) - 128) / 128.0; soma += x * x }
        val rms2 = soma / w.size
        if (rms2 < 1e-5) return                     // silêncio
        // nível original da faixa: tira o ganho que já estamos dando
        val original = rms2 / Math.pow(10.0, ganhoMb / 1000.0)
        quadros++
        energia = if (quadros <= QUADROS_FIXOS) energia + (original - energia) / quadros else energia + (original - energia) * 0.03
        if (quadros < QUADROS_INICIAIS) return
        val nivelDb = 10 * Math.log10(energia)
        val desejado = Math.round((ALVO_DB - nivelDb) * 100).toInt().coerceIn(0, MAXIMO_MB)
        if (quadros % 25 == 0) Log.d(TAG, "nível da faixa %.1f dB -> ganho %d mB (alvo %d)".format(nivelDb, ganhoMb, desejado))
        mover(desejado)
    }

    private fun mover(desejado: Int) {
        val novo = when {
            desejado > ganhoMb -> Math.min(desejado, ganhoMb + PASSO_MB)
            desejado < ganhoMb -> Math.max(desejado, ganhoMb - PASSO_MB)
            else -> return
        }
        ganhoMb = novo
        handler.post { try { realce?.setTargetGain(novo) } catch (_: Throwable) {} }
    }
}
