package br.com.lmsoftworks.caixa2

import android.content.Context
import android.util.Log
import br.com.lmsoftworks.caixa2.voz.Rede
import okhttp3.Request
import org.json.JSONObject
import java.util.TimeZone

/**
 * Fuso horário do app. O Android deste aparelho é antigo e ainda aplica o horário de verão do Brasil (que acabou em 2019), então a
 * hora do sistema aparece 1 hora adiantada. Aqui o app usa o fuso escolhido por ele mesmo: automático (a diferença da hora de Greenwich
 * do lugar, lida pela internet) ou manual (escolhida em Configurações > Data e hora). Vale para a hora na tela, a data, os alarmes e os lembretes.
 */
object FusoHorario {
    private const val TAG = "FusoHorario"
    const val NAO_SEI = 99999

    /** O fuso que o aparelho tinha antes de o app mexer. */
    private val zonaSistema: TimeZone = TimeZone.getDefault()

    /** Diferença para Greenwich, em minutos (-180 = UTC−3), do jeito que está configurado agora. */
    fun minutos(): Int =
        if (Ajustes.fusoAuto) Ajustes.fusoAutoMin.takeIf { it != NAO_SEI } ?: (zonaSistema.getOffset(System.currentTimeMillis()) / 60000)
        else Ajustes.fusoManualMin

    private fun zona(min: Int): TimeZone =
        TimeZone.getTimeZone("GMT" + (if (min < 0) "-" else "+") + "%02d:%02d".format(Math.abs(min) / 60, Math.abs(min) % 60))

    /** Põe o fuso escolhido como o do app (vale para tudo o que o app calcular a partir daqui). */
    fun aplicar() {
        val semDado = Ajustes.fusoAuto && Ajustes.fusoAutoMin == NAO_SEI
        TimeZone.setDefault(if (semDado) zonaSistema else zona(minutos()))
    }

    /** "UTC−3", "UTC+5:30". */
    fun nome(min: Int): String {
        val sinal = if (min < 0) "−" else "+"
        val h = Math.abs(min) / 60
        val m = Math.abs(min) % 60
        return "UTC$sinal$h" + (if (m != 0) ":%02d".format(m) else "")
    }

    /** Pergunta pela internet qual é a diferença do lugar onde a caixa está; [aoMudar] roda (no thread principal) se o fuso mudou. */
    fun buscarAutomatico(ctx: Context, principal: android.os.Handler, aoMudar: () -> Unit) {
        Thread {
            try {
                val req = Request.Builder().url("https://ipwho.is/?fields=success,timezone").header("User-Agent", "CaixaLM").build()
                val corpo = Rede.cliente(ctx, leituraSeg = 10).newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return@Thread
                val j = JSONObject(corpo)
                if (!j.optBoolean("success", false)) return@Thread
                val seg = j.optJSONObject("timezone")?.optInt("offset", Int.MIN_VALUE) ?: Int.MIN_VALUE
                if (seg == Int.MIN_VALUE) return@Thread
                val min = seg / 60
                val antes = minutos()
                Ajustes.definirFusoAutoMin(min)
                aplicar()
                Log.d(TAG, "fuso automático: ${nome(min)}")
                if (minutos() != antes || TimeZone.getDefault().getOffset(System.currentTimeMillis()) != zonaSistema.getOffset(System.currentTimeMillis())) principal.post { aoMudar() }
            } catch (e: Exception) { Log.d(TAG, "não consegui o fuso: $e") }
        }.start()
    }
}
