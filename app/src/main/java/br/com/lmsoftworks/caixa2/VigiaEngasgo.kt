package br.com.lmsoftworks.caixa2

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Diagnóstico de engasgos: a cada 150 ms pede à thread da tela uma tarefa mínima. Se ela não responder em 250 ms, anota no log (tag "Engasgo")
 * o que a thread da tela está fazendo naquele momento (a pilha), uma vez aos 250 ms e outra aos 700 ms. Custa quase nada e mostra exatamente
 * qual código segura a tela.
 */
object VigiaEngasgo {
    @Volatile private var rodando = false

    fun iniciar() {
        if (rodando) return
        rodando = true
        val principal = Handler(Looper.getMainLooper())
        val thread = Looper.getMainLooper().thread
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            while (true) {
                val feito = AtomicBoolean(false)
                val inicio = SystemClock.uptimeMillis()
                principal.post { feito.set(true) }
                try { Thread.sleep(250) } catch (e: InterruptedException) { return@Thread }
                if (!feito.get()) {
                    registrar(thread, "parada há 250 ms")
                    try { Thread.sleep(450) } catch (e: InterruptedException) { return@Thread }
                    if (!feito.get()) registrar(thread, "ainda parada (700 ms)")
                    while (!feito.get() && SystemClock.uptimeMillis() - inicio < 10_000L) try { Thread.sleep(50) } catch (e: InterruptedException) { return@Thread }
                    Log.w("Engasgo", "a tela voltou a responder depois de ${SystemClock.uptimeMillis() - inicio} ms")
                }
                try { Thread.sleep(150) } catch (e: InterruptedException) { return@Thread }
            }
        }, "vigia-engasgo").apply { isDaemon = true }.start()
    }

    private fun registrar(thread: Thread, quando: String) {
        val pilha = thread.stackTrace.take(16).joinToString("\n    ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        Log.w("Engasgo", "tela $quando em:\n    $pilha")
    }
}
