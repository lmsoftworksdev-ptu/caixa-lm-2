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
    private const val TEMPO_MAXIMO_MS = 7000L
    private val principal = Handler(Looper.getMainLooper())

    /** Toca [recurso] e chama [aoFim] uma única vez quando terminar (ou se falhar, ou depois de 7 s: nunca trava o fluxo). */
    fun tocar(ctx: Context, recurso: Int, aoFim: () -> Unit = {}) {
        var chamado = false
        val fim = { if (!chamado) { chamado = true; aoFim() } }
        principal.postDelayed({ fim() }, TEMPO_MAXIMO_MS)
        try {
            val mp = MediaPlayer()
            val afd = ctx.resources.openRawResourceFd(recurso)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            @Suppress("DEPRECATION")
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setOnCompletionListener { it.release(); fim() }
            mp.setOnErrorListener { p, _, _ -> p.release(); fim(); true }
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
