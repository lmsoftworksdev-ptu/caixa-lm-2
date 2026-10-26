package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.max

/**
 * Parâmetros visuais. Tudo é desenhado em "pixels de projeto" de uma tela 1280x720
 * (o mesmo tamanho do protótipo) e convertido para a tela real com [d].
 */
object Cfg {
    // ----- Cores (iguais ao protótipo) -----
    val FUNDO = Color.parseColor("#07122B")
    val PAINEL = Color.parseColor("#CC0D2048")
    val LINHA = Color.parseColor("#1FFFFFFF")
    /** Cor de destaque (botões, interruptores). Troca junto com o menu atual. */
    var AZUL = Color.parseColor("#2F7BFF")
    /** Cor padrão (a do Bluetooth) para barras e botões que não devem mudar com o tema. */
    val COR_FIXA = Color.parseColor("#3D8BFF")
    val AZUL_CLARO = Color.parseColor("#6AA8FF")
    val VERMELHO = Color.parseColor("#FF3B4E")
    val TEXTO = Color.parseColor("#EAF1FF")
    val MUDO = Color.parseColor("#8FA6D4")
    val OK = Color.parseColor("#29D391")

    // ----- Fundo animado -----
    const val MATIZ = 215f        // tom base do degradê (graus)
    const val MATIZ_VAR = 12f     // quanto o tom sobe e desce
    const val FPS = 30            // quadros por segundo do fundo

    // ----- Tempos -----
    const val ATRASO_ENTRADA_MS = 900L   // espera o giro do Android antes de mostrar a interface
    const val FADE_ENTRADA_MS = 700L
    const val SCAN_WIFI_MS = 10_000L     // de quanto em quanto tempo procura redes
    const val TEMPO_CONEXAO_MS = 25_000L // desiste de conectar depois disso

    /** Escala: tela real dividida pelo projeto de 1280 px de largura. */
    var u = 1f

    fun iniciar(c: Context) {
        val m = DisplayMetrics()
        (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        u = max(m.widthPixels, m.heightPixels) / 1280f
    }
}

fun Int.d(): Int = (this * Cfg.u).toInt()
fun Float.d(): Float = this * Cfg.u
