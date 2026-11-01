package br.com.lmsoftworks.caixa2

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Calendar

/** Botão redondo com um ícone dentro (e, se pedir, um círculo girando no lugar do ícone). Usado em telas ainda não redesenhadas. */
class BotaoRedondo(c: Context, recurso: Int, private val descricao: String, tamIcone: Int = 33) : FrameLayout(c) {
    private val icone = ImageView(c).apply { setImageResource(recurso) }
    private val girando = ProgressBar(c).apply { isIndeterminate = true; visibility = View.GONE }

    init {
        isClickable = true
        efeitoToque()
        addView(icone, LayoutParams(tamIcone.d(), tamIcone.d(), Gravity.CENTER))
        addView(girando, LayoutParams(tamIcone.d(), tamIcone.d(), Gravity.CENTER))
        contentDescription = descricao
    }

    fun trocarIcone(recurso: Int) = icone.setImageResource(recurso)

    /** [cor] do ícone e do círculo (o fundo fica levemente tingido dessa cor quando [avermelhado]). */
    fun pintar(cor: Int, avermelhado: Boolean) {
        icone.setColorFilter(cor)
        girando.indeterminateTintList = ColorStateList.valueOf(cor)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (avermelhado) Color.argb(0x1F, 0xFF, 0x3B, 0x4E) else Color.argb(0x12, 255, 255, 255))
            setStroke(1.d().coerceAtLeast(1), Cfg.LINHA)
        }
    }

    fun carregando(v: Boolean) {
        girando.visibility = if (v) View.VISIBLE else View.GONE
        icone.visibility = if (v) View.GONE else View.VISIBLE
    }

    fun conexao(conectado: Boolean) {
        pintar(if (conectado) AZUL_OK else Cfg.VERMELHO, !conectado)
        contentDescription = descricao + ": " + if (conectado) "conectado" else "desconectado"
    }

    companion object { val AZUL_OK = Color.parseColor("#3DA5FF") }
}

/** A barra diagonal que "corta" um ícone sem conexão: da mesma cor do ícone. */
class CorteView(c: Context) : View(c) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val m = width * .1f
        p.color = Color.WHITE; p.strokeWidth = 3.d().toFloat()      // só a barra, na cor do ícone (sem contorno)
        canvas.drawLine(m, m, width - m, height - m, p)
    }
}

/**
 * Ícone da barra de status da v2: só o desenho (32 px), sem círculo, numa área de toque de 56 x 56. Conectado = branco;
 * sem conexão = branco com uma barra diagonal cortando o ícone; desligado pelo usuário = branco a 45%.
 */
class IconeStatus(c: Context, recurso: Int, private val descricao: String, tam: Int = 32) : FrameLayout(c) {
    private val icone = ImageView(c).apply { setImageResource(recurso) }
    private val corte = CorteView(c).apply { visibility = View.GONE }

    init {
        isClickable = true
        efeitoToque()
        contentDescription = descricao
        addView(icone, LayoutParams(tam.d(), tam.d(), Gravity.CENTER))
        addView(corte, LayoutParams(tam.d(), tam.d(), Gravity.CENTER))
    }

    fun trocarIcone(recurso: Int) = icone.setImageResource(recurso)

    fun estado(cor: Int, cortado: Boolean, alfa: Float = 1f, texto: String = "") {
        icone.setColorFilter(cor)
        icone.alpha = alfa
        corte.visibility = if (cortado) View.VISIBLE else View.GONE
        if (texto.isNotEmpty()) contentDescription = "$descricao: $texto"
    }
}

/**
 * Barra de cima da v2 (altura 92, sobre todas as telas, sem fundo). Esquerda: só ícones de estado (Wi-Fi, Bluetooth, fita de
 * LED) e o volume, sempre branco. Direita: a hora (só nos menus) e o botão de energia. Branco = conectado; vermelho + pontinho =
 * sem conexão. Para voltar, desliza do canto esquerdo para a direita.
 */
class BarraTopo(
    c: Context,
    aoAbrirWifi: () -> Unit,
    aoAbrirBluetooth: () -> Unit,
    aoAbrirVolume: () -> Unit,
    aoAbrirEnergia: () -> Unit
) : FrameLayout(c) {

    private val wifi = IconeStatus(c, R.drawable.ic_wifi, "Wi-Fi", TAM_ICONE)
    private val bluetooth = IconeStatus(c, R.drawable.ic_bluetooth, "Bluetooth", TAM_ICONE)
    private val volume = IconeStatus(c, R.drawable.ic_volume_2, "Volume", TAM_ICONE)
    private val energia = IconeStatus(c, R.drawable.ic_power, "Desligar ou reiniciar", TAM_ICONE)
    private val hora: TextView = texto(c, "", 30, Cfg.TX).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        letterSpacing = 0.02f
    }

    /** Coloca a pílula do volume atrás do botão de volume (ela cresce para a direita a partir dele). */
    fun colocarPilulaVolume(pilula: View) {
        addView(pilula, indexOfChild(volume), LayoutParams(0, AREA.d()).apply { leftMargin = X_VOLUME.d(); topMargin = 14.d() })
    }

    companion object {
        /** Ícones de status: todos do mesmo tamanho (desenho de 40 px numa área de toque de 64 x 64). */
        const val TAM_ICONE = 40
        const val AREA = 64
        const val X_WIFI = 46
        const val X_BLUETOOTH = 114
        const val X_VOLUME = 182
    }

    /** Posição do botão de volume na barra. */
    val volumeX: Int get() = volume.left

    init {
        wifi.setOnClickListener { aoAbrirWifi() }
        bluetooth.setOnClickListener { aoAbrirBluetooth() }
        volume.setOnClickListener { aoAbrirVolume() }
        energia.setOnClickListener { aoAbrirEnergia() }

        addView(wifi, LayoutParams(AREA.d(), AREA.d()).apply { leftMargin = X_WIFI.d(); topMargin = 14.d() })
        addView(bluetooth, LayoutParams(AREA.d(), AREA.d()).apply { leftMargin = X_BLUETOOTH.d(); topMargin = 14.d() })
        addView(volume, LayoutParams(AREA.d(), AREA.d()).apply { leftMargin = X_VOLUME.d(); topMargin = 14.d() })
        addView(hora, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply { rightMargin = 118.d(); topMargin = 24.d() })
        addView(energia, LayoutParams(AREA.d(), AREA.d(), Gravity.END or Gravity.TOP).apply { rightMargin = 36.d(); topMargin = 14.d() })

        wifi.estado(Color.WHITE, true)
        bluetooth.estado(Color.WHITE, true)
        volume.estado(Color.WHITE, false)
        energia.estado(Color.WHITE, false)
        atualizarHora()
        mostrarHora(false)
    }

    /** [ligado] = Wi-Fi ligado; [conectado] = tem rede. Desligado mostra o ícone riscado. */
    fun definirWifi(ligado: Boolean, conectado: Boolean) {
        wifi.trocarIcone(if (ligado) R.drawable.ic_wifi else R.drawable.ic_wifi_off)
        if (conectado) wifi.estado(Color.WHITE, false, 1f, "conectado") else wifi.estado(Color.WHITE, true, 1f, if (ligado) "sem rede" else "desligado")
    }

    fun definirBluetooth(conectado: Boolean) {
        if (conectado) bluetooth.estado(Color.WHITE, false, 1f, "conectado") else bluetooth.estado(Color.WHITE, true, 1f, "desconectado")
    }

    /** A fita de LED não tem mais ícone na barra: o estado dela ("conectada ou não") fica dentro do menu de luzes. */

    /** O volume não é uma conexão: fica sempre branco; o ícone mostra o nível (0 a 10). [mudo] = cortado. */
    fun definirVolume(porcento: Int, mudo: Boolean) {
        val nivel = Math.round(porcento / 10f)
        volume.trocarIcone(
            when {
                mudo || nivel == 0 -> R.drawable.ic_volume_x
                nivel < 4 -> R.drawable.ic_volume_0
                nivel < 8 -> R.drawable.ic_volume_1
                else -> R.drawable.ic_volume_2
            }
        )
        volume.contentDescription = if (mudo) "Volume: mudo" else "Volume: nível $nivel"
    }

    /** A hora da barra só aparece nos menus; na tela Início a hora grande já está na tela. */
    fun mostrarHora(v: Boolean) { hora.visibility = if (v) View.VISIBLE else View.GONE }

    fun atualizarHora() {
        val cal = Calendar.getInstance()
        hora.text = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }
}
