package br.com.lmsoftworks.caixa2

import android.net.TrafficStats
import android.os.SystemClock

/** Velocidade de download da internet do aparelho ao vivo. Quem manda medir é a tela principal, a cada meio segundo. */
object UsoRede {
    @Volatile var texto = ""; private set
    private var bytesRx = -1L
    private var tAnterior = 0L
    private var velRx = 0f

    private fun formatar(bps: Float): String =
        if (bps < 1024f) "0 KB/s" else if (bps < 1024f * 1024f) "${(bps / 1024f).toInt()} KB/s" else String.format("%.1f MB/s", bps / (1024f * 1024f)).replace('.', ',')

    /** Atualiza o número só a cada 2 segundos (mais devagar, para dar tempo de ler); só o download. */
    fun amostrar() {
        val agora = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        if (rx < 0) { texto = ""; return }      // o aparelho não informa
        if (bytesRx < 0) { bytesRx = rx; tAnterior = agora; return }
        if (agora - tAnterior < 2000L) return
        val dt = (agora - tAnterior) / 1000f
        velRx = velRx * 0.4f + ((rx - bytesRx) / dt) * 0.6f      // média suave: o número não pula
        texto = "↓ " + formatar(velRx)
        bytesRx = rx; tAnterior = agora
    }
}
