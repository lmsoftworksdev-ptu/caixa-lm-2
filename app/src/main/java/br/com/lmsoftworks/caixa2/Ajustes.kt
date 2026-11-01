package br.com.lmsoftworks.caixa2

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.pow

/**
 * Ajustes da tela Configurações, guardados no aparelho. Os valores ficam em variáveis (@Volatile) para serem lidos de qualquer thread
 * (o microfone, por exemplo) sem mexer no arquivo a cada leitura.
 */
object Ajustes {
    private var prefs: SharedPreferences? = null

    /** Brilho da tela, de 5 a 100%. */
    @Volatile var brilho = 100; private set
    /** Tempo sem toque até o modo ocioso, em segundos (0 = nunca). */
    @Volatile var ociosoSeg = 60; private set
    /** No modo ocioso, baixa o brilho da tela (senão só fica preto com a hora). */
    @Volatile var escurecerOcioso = true; private set
    /** Toquinhos de ativação, confirmação e erro do assistente. */
    @Volatile var sonsResposta = true; private set
    /** Volume mínimo do alarme: 0 = suave, 1 = médio, 2 = alto. */
    @Volatile var volumeAlarme = 1; private set
    /** Palavra de ativação escolhida. Por enquanto só "Alexa" funciona (as outras entram numa versão futura). */
    @Volatile var palavra = "Alexa"; private set
    /** Mostra, no alto da tela do assistente, a resposta escrita. */
    @Volatile var mostrarResposta = true; private set
    /** Sensibilidade do microfone para ouvir a palavra de ativação, de 1 a 10. */
    @Volatile var sensMic = 5; private set

    fun iniciar(c: Context) {
        val p = c.applicationContext.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
        prefs = p
        brilho = p.getInt("brilho", 100).coerceIn(5, 100)
        ociosoSeg = p.getInt("ociosoSeg", 60)
        escurecerOcioso = p.getBoolean("escurecerOcioso", true)
        sonsResposta = p.getBoolean("sonsResposta", true)
        volumeAlarme = p.getInt("volumeAlarme", 1).coerceIn(0, 2)
        palavra = p.getString("palavra", "Alexa") ?: "Alexa"
        mostrarResposta = p.getBoolean("mostrarResposta", true)
        sensMic = p.getInt("sensMic", 5).coerceIn(1, 10)
    }

    private fun guardar(f: SharedPreferences.Editor.() -> Unit) { prefs?.edit()?.apply(f)?.apply() }

    fun definirBrilho(v: Int) { brilho = v.coerceIn(5, 100); guardar { putInt("brilho", brilho) } }
    fun definirOcioso(seg: Int) { ociosoSeg = seg; guardar { putInt("ociosoSeg", seg) } }
    fun definirEscurecer(v: Boolean) { escurecerOcioso = v; guardar { putBoolean("escurecerOcioso", v) } }
    fun definirSons(v: Boolean) { sonsResposta = v; guardar { putBoolean("sonsResposta", v) } }
    fun definirVolumeAlarme(v: Int) { volumeAlarme = v.coerceIn(0, 2); guardar { putInt("volumeAlarme", volumeAlarme) } }
    fun definirMostrarResposta(v: Boolean) { mostrarResposta = v; guardar { putBoolean("mostrarResposta", v) } }
    fun definirSensMic(v: Int) { sensMic = v.coerceIn(1, 10); guardar { putInt("sensMic", sensMic) } }

    /** Tempo até o modo ocioso em milissegundos (Long.MAX_VALUE = nunca entra). */
    fun ociosoMs(): Long = if (ociosoSeg <= 0) Long.MAX_VALUE else ociosoSeg * 1000L

    /** Fração do volume máximo que o alarme garante ao tocar. */
    fun pisoAlarme(): Float = when (volumeAlarme) { 0 -> .25f; 1 -> .45f; else -> .8f }

    /** Multiplica o limite de voz da chamada: sensibilidade maior = limite menor (ouve de mais longe). 5 = como era antes. */
    fun fatorMic(): Float = 2.0.pow((5 - sensMic) / 5.0).toFloat()
}
