package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar

/**
 * Sons da caixa (arquivos em res/raw, copiados do projeto antigo; ver sons/LEIA-ME.txt). Tocam no canal de música, então
 * respeitam o volume e o limite de 87% do amplificador.
 */
object Som {
    private const val TAG = "CaixaSom"
    private const val TEMPO_MAXIMO_MS = 15_000L      // só uma rede de segurança: os sons têm poucos segundos
    private const val FOLGA_FIM_MS = 500L      // depois do fim do arquivo, o resto ainda está no buffer da saída de áudio: espera esvaziar
    /** Quem toca precisa ficar preso aqui: um MediaPlayer sem ninguém segurando é recolhido pelo coletor de lixo e o som corta no meio. */
    private val tocando = HashSet<MediaPlayer>()
    private val principal = Handler(Looper.getMainLooper())

    /**
     * Põe o volume do aparelho no "Volume da voz" de Configurações enquanto o som toca (como a resposta do assistente) e devolve o volume da
     * música depois. Devolve a função que devolve o volume. A barra de volume não acompanha essas trocas.
     */
    private fun volumeDaVoz(ctx: Context): () -> Unit {
        try {
            val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val canal = AudioManager.STREAM_MUSIC
            val antes = audio.getStreamVolume(canal)
            val alvo = Math.round(audio.getStreamMaxVolume(canal) * Ajustes.volumeVoz / 10f).coerceAtLeast(1)
            if (antes != alvo) { br.com.lmsoftworks.caixa2.voz.Fala.aoMexerNoVolume?.invoke(); audio.setStreamVolume(canal, alvo, 0) }
            return {
                try {
                    // só devolve se ninguém mexeu no volume enquanto o som tocava
                    if (antes != alvo && audio.getStreamVolume(canal) == alvo) { br.com.lmsoftworks.caixa2.voz.Fala.aoMexerNoVolume?.invoke(); audio.setStreamVolume(canal, antes, 0) }
                } catch (_: Throwable) { }
            }
        } catch (e: Throwable) { Log.w(TAG, "volume da voz", e) }
        return {}
    }

    /**
     * Toca [recurso] e chama [aoFim] uma única vez quando terminar (ou se falhar, ou depois de 15 s: nunca trava o fluxo).
     * Com [volumeDaVoz] o som sai no "Volume da voz" (o dos sons de ligar e desligar), não no volume da música.
     */
    fun tocar(ctx: Context, recurso: Int, volumeDaVoz: Boolean = false, aoFim: () -> Unit = {}) {
        var chamado = false
        val devolver = if (volumeDaVoz) volumeDaVoz(ctx) else ({})
        val fim = { if (!chamado) { chamado = true; devolver(); aoFim() } }
        principal.postDelayed({ fim() }, TEMPO_MAXIMO_MS)
        try {
            val mp = MediaPlayer()
            tocando.add(mp)
            val afd = ctx.resources.openRawResourceFd(recurso)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            @Suppress("DEPRECATION")
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setOnCompletionListener { p -> principal.postDelayed({ tocando.remove(p); p.release(); fim() }, FOLGA_FIM_MS) }      // soltar o player e avisar "terminou" só depois da folga: fazer isso na hora cortava o final do som
            mp.setOnErrorListener { p, _, _ -> tocando.remove(p); p.release(); fim(); true }
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
        } catch (e: Throwable) {
            Log.w(TAG, "som falhou", e)
            fim()
        }
    }
}

/**
 * Tela preta por cima de TUDO (inclusive da janela "Reiniciando…" do Android) durante o desligar e o reiniciar, para só
 * aparecer a nossa. Precisa da permissão de sobrepor telas (SYSTEM_ALERT_WINDOW, liberada para o app).
 */
class CoberturaEnergia(private val c: Context) {
    private var vista: View? = null

    fun mostrar(texto: String) {
        if (vista != null) return
        val v = FrameLayout(c).apply {
            setBackgroundColor(Color.BLACK)
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ProgressBar(c).apply { isIndeterminate = true }, LinearLayout.LayoutParams(56.d(), 56.d()))
                addView(texto(c, texto, 22, Cfg.MUDO), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 18.d() })
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        for (tipo in intArrayOf(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR, WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY, WindowManager.LayoutParams.TYPE_PHONE)) {
            try {
                val lp = WindowManager.LayoutParams(
                    -1, -1, tipo,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.OPAQUE
                )
                wm.addView(v, lp)
                vista = v
                Log.d("CaixaSom", "cobertura de energia no tipo $tipo")
                return
            } catch (e: Throwable) {
                Log.w("CaixaSom", "cobertura tipo $tipo recusada: ${e.message}")
            }
        }
    }

    fun esconder() {
        val v = vista ?: return
        vista = null
        try { (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Throwable) { }
    }
}
