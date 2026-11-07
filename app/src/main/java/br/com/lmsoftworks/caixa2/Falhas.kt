package br.com.lmsoftworks.caixa2

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Aviso de falhas: em vez de falhar calado, a caixa mostra um aviso no cartão da tela inicial quando algo importante deixa de funcionar:
 *  - o YouTube parou de responder (3 vídeos seguidos sem abrir por um erro que não é da internet: o YouTube mudou algo, falta atualizar o app);
 *  - a previsão do tempo não vem mesmo com internet;
 *  - o app fechou sozinho por um erro (o registro fica salvo em "falhas.log").
 * O aviso some sozinho quando o problema passa (um vídeo abriu, a previsão chegou) ou quando a pessoa o dispensa.
 */
object Falhas {
    private const val TAG = "Falhas"
    private const val SEGUIDAS_YOUTUBE = 3
    private const val SEGUIDAS_CLIMA = 4
    private const val LIMITE_LOG = 100_000L

    class Aviso(val id: String, val titulo: String, val detalhe: String)

    private var app: Context? = null
    @Volatile private var youtubeSeguidas = 0
    @Volatile private var climaSeguidas = 0
    private val hora = SimpleDateFormat("dd/MM HH:mm:ss", Locale("pt", "BR"))

    private fun prefs() = app?.getSharedPreferences("falhas", Context.MODE_PRIVATE)
    private fun arquivo() = File(app!!.filesDir, "falhas.log")

    /** Chamada ao abrir o app: guarda o contexto e registra erros que derrubam o app. */
    fun iniciar(contexto: Context) {
        app = contexto.applicationContext
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                gravar("O APP FECHOU (${t.name})", e)
                prefs()?.edit()?.putLong("travouEm", System.currentTimeMillis())?.putString("travouMotivo", e.javaClass.simpleName)?.commit()
            } catch (_: Throwable) { }
            anterior?.uncaughtException(t, e)
        }
    }

    /** Escreve no registro (falhas.log, na pasta do app): hora, de onde veio e as primeiras linhas do erro. */
    @Synchronized
    fun gravar(origem: String, e: Throwable?) {
        if (app == null) return
        try {
            val f = arquivo()
            if (f.length() > LIMITE_LOG) f.writeText(f.readText().takeLast((LIMITE_LOG / 2).toInt()))
            val pilha = e?.let { "\n" + it.stackTraceToString().lines().take(10).joinToString("\n") } ?: ""
            f.appendText("${hora.format(Date())}  $origem${e?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: ""}$pilha\n\n")
        } catch (_: Throwable) { }
        Log.w(TAG, "$origem: ${e?.message}")
    }

    private fun temInternet(): Boolean = try {
        val cm = app?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetworkInfo?.isConnected == true
    } catch (_: Throwable) { false }

    private fun deRede(e: Throwable): Boolean {
        var x: Throwable? = e
        while (x != null) { if (x is IOException) return true; x = x.cause }
        return false
    }

    // ---------------------------------------------------------------- YouTube

    /** Um vídeo/busca do YouTube falhou. Só conta o que não é da internet e não é "este vídeo não está disponível". */
    fun youtubeFalhou(e: Throwable) {
        gravar("YouTube", e)
        if (deRede(e) || e is org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException) return
        youtubeSeguidas++
    }

    /** Um vídeo do YouTube abriu: o serviço está bom. */
    fun youtubeOk() { youtubeSeguidas = 0 }

    // ---------------------------------------------------------------- previsão do tempo

    fun climaFalhou() { climaSeguidas++; if (climaSeguidas == SEGUIDAS_CLIMA) gravar("Previsão do tempo sem resposta", null) }
    fun climaOk() { climaSeguidas = 0 }

    // ---------------------------------------------------------------- o que mostrar

    /** O aviso mais importante agora, ou null se está tudo bem. */
    fun atual(): Aviso? {
        val p = prefs()
        val travouEm = p?.getLong("travouEm", 0L) ?: 0L
        if (youtubeSeguidas >= SEGUIDAS_YOUTUBE && temInternet()) return Aviso("youtube", "YouTube sem resposta", "Toque para procurar uma atualização do app")
        if (travouEm > 0 && System.currentTimeMillis() - travouEm < 24L * 3600_000L)
            return Aviso("travou", "O app fechou sozinho", "Às ${SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(travouEm))} · toque para dispensar")
        if (climaSeguidas >= SEGUIDAS_CLIMA && temInternet()) return Aviso("clima", "Sem previsão do tempo", "Não consegui buscar · toque para tentar de novo")
        return null
    }

    /** A pessoa dispensou o aviso (só vale para o que não some sozinho). */
    fun dispensar(id: String) {
        when (id) {
            "travou" -> prefs()?.edit()?.putLong("travouEm", 0L)?.apply()
            "youtube" -> youtubeSeguidas = 0
            "clima" -> climaSeguidas = 0
        }
    }
}
