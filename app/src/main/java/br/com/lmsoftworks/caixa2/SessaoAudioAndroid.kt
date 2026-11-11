package br.com.lmsoftworks.caixa2

import android.os.SystemClock
import android.util.Log

/** Localiza faixas no mixer do Android 7 da caixa. */
internal object SessaoAudioAndroid {
    private val faixa = Regex("""\b(yes|no)\s+(\d+)\s+(\d+)\s+[0-9a-fA-F]{8}\s+[0-9a-fA-F]{8}\s+(\d+)\s""")
    private val processoBluetooth = Regex("""\b(\d+):com\.android\.bluetooth/""")
    private var pidBluetooth = -1
    private var pidConsultadoEm = -10_000L

    fun externa(pidDoApp: Int): Int = acharFaixa { pid, _ -> pid != pidDoApp }

    @Synchronized
    fun bluetooth(): Int {
        val agora = SystemClock.uptimeMillis()
        if (agora - pidConsultadoEm >= 10_000L) {
            pidBluetooth = consultar("activity", "services", "com.android.bluetooth") { linhas ->
                linhas.firstNotNullOfOrNull { processoBluetooth.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1
            }
            pidConsultadoEm = agora
        }
        if (pidBluetooth <= 0) return -1
        val sessao = acharFaixa { pid, stream -> pid == pidBluetooth && stream == 3 }
        if (sessao <= 0 && agora - pidConsultadoEm >= 1_000L) pidConsultadoEm = -10_000L
        return sessao
    }

    private fun acharFaixa(aceitar: (Int, Int) -> Boolean): Int = consultar("media.audio_flinger") { linhas ->
        linhas.mapNotNull { linha ->
            val dados = faixa.find(linha)?.groupValues ?: return@mapNotNull null
            if (dados[1] == "yes" && aceitar(dados[2].toInt(), dados[3].toInt()))
                dados[4].toInt().takeIf { it > 0 }
            else null
        }.firstOrNull() ?: -1
    }

    private fun consultar(vararg args: String, ler: (Sequence<String>) -> Int): Int = try {
        val processo = ProcessBuilder(listOf("/system/bin/dumpsys", "-t", "2") + args)
            .redirectErrorStream(true).start()
        try {
            processo.inputStream.bufferedReader().useLines(ler)
        } finally {
            processo.destroy()
        }
    } catch (e: Exception) {
        Log.w("SessaoAudioAndroid", "Não foi possível consultar o mixer", e)
        -1
    }
}
