package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.max

/**
 * Parâmetros visuais. Tudo é desenhado em "pixels de projeto" de uma tela 1280x720
 * (o mesmo tamanho do protótipo) e convertido para a tela real com [d].
 */
object Cfg {
    // ----- Cores (iguais ao protótipo) -----
    val FUNDO = Color.parseColor("#050A18")
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

    // ----- Interface v2 -----
    val TX = Color.parseColor("#F6F6FA")          // texto principal
    val TX2 = Color.parseColor("#B7BACB")         // texto secundário
    val TX_MUDO = Color.parseColor("#8A8DA0")
    val SUPERFICIE = Color.parseColor("#12FFFFFF")        // tiles, faixas (7%)
    val SUPERFICIE2 = Color.parseColor("#1FFFFFFF")       // forte (12%)
    val DIVISOR = Color.parseColor("#17FFFFFF")
    val VERM2 = Color.parseColor("#FF4D5E")
    val OK2 = Color.parseColor("#3DDC97")

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
        Tema.carregarFontes(c)
    }
}

/** Tokens da interface v3 (cores, raios, tamanhos e durações do protótipo). Medidas em px de projeto (1280x720). */
object Tema {
    val FUNDO = Color.parseColor("#050A18")
    val CARTAO = Color.parseColor("#C70D152C")
    val CARTAO_BORDA = Color.parseColor("#17FFFFFF")
    val CARTAO_TOPO = Color.parseColor("#21FFFFFF")
    val MODAL = Color.parseColor("#0E1733")
    val MODAL_BORDA = Color.parseColor("#1AFFFFFF")
    val TEXTO = Color.parseColor("#F4F7FF")
    val TEXTO2 = Color.parseColor("#AEBBD8")
    val TEXTO3 = Color.parseColor("#7F90B5")
    val CONECTADO = Color.parseColor("#3DA5FF")
    val ERRO = Color.parseColor("#FF4D5E")
    val OK = Color.parseColor("#2FD39A")
    val VERMELHO_SUAVE = Color.parseColor("#FF8A96")
    val SCRIM = Color.parseColor("#B802050E")
    val BOTAO = Color.parseColor("#14FFFFFF")
    val BOTAO_BORDA = Color.parseColor("#1AFFFFFF")
    val DESLIGADO = Color.parseColor("#29FFFFFF")

    const val RAIO_CARTAO = 28
    const val RAIO_BOTAO = 20
    const val RAIO_MODAL = 32
    const val MARGEM_LATERAL = 48
    const val MARGEM_TOPO = 104
    const val MARGEM_BASE = 40

    const val CUBO_MS = 640L
    const val MODAL_MS = 300L
    const val TECLADO_MS = 340L
    const val INTERRUPTOR_MS = 280L

    /** Curva padrão do protótipo. */
    val CURVA: android.view.animation.Interpolator = android.view.animation.PathInterpolator(.2f, .8f, .2f, 1f)

    /** Outfit SemiBold (relógio do Início e temperatura da Previsão). Cai para a Roboto média se faltar o arquivo. */
    var OUTFIT: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private set

    fun carregarFontes(c: Context) {
        try { OUTFIT = Typeface.createFromAsset(c.assets, "fonts/Outfit-SemiBold.ttf") } catch (e: Exception) { }
    }

    /** Cor de fundo (duas cores, brilho e sombra do relógio) do Início para uma hora decimal (0..24). */
    class FundoHora(val cor1: Int, val cor2: Int, val brilho: Float, val sombra: Int)

    private class Ponto(val h: Float, val c1: String, val c2: String, val b: Float, val r: Int, val g: Int, val bl: Int)

    private val PONTOS = listOf(
        Ponto(0f, "#4A35D0", "#1A2080", 1.40f, 110, 90, 255),
        Ponto(5f, "#4A35D0", "#1A2080", 1.40f, 110, 90, 255),
        Ponto(7f, "#FF9A4D", "#3A7BFF", 1.45f, 255, 170, 90),
        Ponto(10f, "#3D8BFF", "#7FC4FF", 1.40f, 61, 139, 255),
        Ponto(14.5f, "#FF8A3D", "#2F7BFF", 1.45f, 255, 150, 80),
        Ponto(18f, "#7A4CFF", "#FF6F96", 1.45f, 200, 110, 200),
        Ponto(20.5f, "#5A3CE0", "#232A9A", 1.40f, 120, 100, 255),
        Ponto(24f, "#4A35D0", "#1A2080", 1.40f, 110, 90, 255)
    )

    private fun mix(a: Int, b: Int, f: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
    )

    fun fundoDaHora(hora: Float): FundoHora {
        val h = hora.coerceIn(0f, 24f)
        var i = 0
        while (i < PONTOS.size - 2 && h > PONTOS[i + 1].h) i++
        val a = PONTOS[i]; val b = PONTOS[i + 1]
        val f = ((h - a.h) / (b.h - a.h)).coerceIn(0f, 1f)
        val sa = Color.rgb(a.r, a.g, a.bl); val sb = Color.rgb(b.r, b.g, b.bl)
        return FundoHora(
            mix(Color.parseColor(a.c1), Color.parseColor(b.c1), f),
            mix(Color.parseColor(a.c2), Color.parseColor(b.c2), f),
            a.b + (b.b - a.b) * f,
            mix(sa, sb, f)
        )
    }

    fun horaAgora(): Float {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.HOUR_OF_DAY) + c.get(java.util.Calendar.MINUTE) / 60f
    }
}

fun Int.d(): Int = (this * Cfg.u).toInt()
fun Float.d(): Float = this * Cfg.u
