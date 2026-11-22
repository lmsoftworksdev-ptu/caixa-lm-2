package br.com.lmsoftworks.caixa2

import android.os.SystemClock
import android.util.Log

/** Localiza faixas no mixer do Android 7 da caixa. */
internal object SessaoAudioAndroid {
    private val faixa = Regex("""\b(yes|no)\s+(\d+)\s+(\d+)\s+[0-9a-fA-F]{8}\s+[0-9a-fA-F]{8}\s+(\d+)\s""")
    private val processoBluetooth = Regex("""\b(\d+):com\.android\.bluetooth/""")
    private var pidBluetooth = -1
    private var pidConsultadoEm = -10_000L

    /** Quantas vezes o dumpsys foi realmente chamado e quantas respostas guardadas foram reaproveitadas (aparece no Teste de conexões). */
    @Volatile var consultas = 0
        private set
    @Volatile var reaproveitadas = 0
        private set

    /** Uma linha da tabela de faixas do mixer. */
    private class Faixa(val ativa: Boolean, val pid: Int, val stream: Int, val sessao: Int)

    private var faixas: List<Faixa> = emptyList()
    private var faixasEm = -10_000L

    /** Uma resposta do mixer vale por este tempo para todos que perguntarem (o dumpsys abre um processo novo: é caro nesta caixa). */
    private const val VALIDADE_FAIXAS_MS = 1_200L

    fun externa(pidDoApp: Int): Int = acharFaixa { pid, _ -> pid != pidDoApp }

    @Synchronized
    fun bluetooth(): Int {
        val agora = SystemClock.uptimeMillis()
        if (agora - pidConsultadoEm >= 10_000L) {
            pidBluetooth = consultar("activity", "services", "com.android.bluetooth", padrao = -1) { linhas ->
                linhas.firstNotNullOfOrNull { processoBluetooth.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1
            }
            pidConsultadoEm = agora
        }
        if (pidBluetooth <= 0) return -1
        val sessao = acharFaixa { pid, stream -> pid == pidBluetooth && stream == 3 }
        if (sessao <= 0 && agora - pidConsultadoEm >= 1_000L) pidConsultadoEm = -10_000L
        return sessao
    }

    private fun acharFaixa(aceitar: (Int, Int) -> Boolean): Int =
        faixasAtuais().firstOrNull { it.ativa && it.sessao > 0 && aceitar(it.pid, it.stream) }?.sessao ?: -1

    @Synchronized
    private fun faixasAtuais(): List<Faixa> {
        val agora = SystemClock.uptimeMillis()
        if (agora - faixasEm < VALIDADE_FAIXAS_MS) { reaproveitadas++; return faixas }
        faixas = consultar("media.audio_flinger", padrao = emptyList()) { linhas ->
            linhas.mapNotNull { linha ->
                val d = faixa.find(linha)?.groupValues ?: return@mapNotNull null
                Faixa(d[1] == "yes", d[2].toInt(), d[3].toInt(), d[4].toInt())
            }.toList()
        }
        faixasEm = SystemClock.uptimeMillis()
        return faixas
    }

    private fun <T> consultar(vararg args: String, padrao: T, ler: (Sequence<String>) -> T): T = try {
        consultas++
        val processo = ProcessBuilder(listOf("/system/bin/dumpsys", "-t", "2") + args)
            .redirectErrorStream(true).start()
        try {
            processo.inputStream.bufferedReader().useLines(ler)
        } finally {
            processo.destroy()
        }
    } catch (e: Exception) {
        Log.w("SessaoAudioAndroid", "Não foi possível consultar o mixer", e)
        padrao
    }
}
