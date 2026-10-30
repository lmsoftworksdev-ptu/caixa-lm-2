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

/** A barra diagonal que "corta" um ícone sem conexão: branca, com contorno escuro para aparecer sobre o desenho. */
class CorteView(c: Context) : View(c) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val m = width * .1f
        p.color = Color.BLACK; p.strokeWidth = 8.d().toFloat()
        canvas.drawLine(m, m, width - m, height - m, p)
        p.color = Color.WHITE; p.strokeWidth = 3.d().toFloat()
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
    aoAbrirLed: () -> Unit,
    aoAbrirVolume: () -> Unit,
    aoAbrirEnergia: () -> Unit
) : FrameLayout(c) {

    private val wifi = IconeStatus(c, R.drawable.ic_wifi, "Wi-Fi")
    private val bluetooth = IconeStatus(c, R.drawable.ic_bluetooth, "Bluetooth")
    private val led = IconeStatus(c, R.drawable.ic_bulb, "Fita de LED")
    private val volume = IconeStatus(c, R.drawable.ic_volume_2, "Volume", 30)
    private val energia = IconeStatus(c, R.drawable.ic_power, "Desligar ou reiniciar")
    private val hora: TextView = texto(c, "", 30, Cfg.TX).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        letterSpacing = 0.02f
    }

    /** Coloca a pílula do volume atrás do botão de volume (ela cresce para a direita a partir dele). */
    fun colocarPilulaVolume(pilula: View) {
        addView(pilula, indexOfChild(volume), LayoutParams(0, 64.d()).apply { leftMargin = 240.d(); topMargin = 14.d() })
    }

    /** Posição do botão de volume na barra. */
    val volumeX: Int get() = volume.left

    init {
        wifi.setOnClickListener { aoAbrirWifi() }
        bluetooth.setOnClickListener { aoAbrirBluetooth() }
        led.setOnClickListener { aoAbrirLed() }
        volume.setOnClickListener { aoAbrirVolume() }
        energia.setOnClickListener { aoAbrirEnergia() }

        addView(wifi, LayoutParams(56.d(), 56.d()).apply { leftMargin = 58.d(); topMargin = 18.d() })
        addView(bluetooth, LayoutParams(56.d(), 56.d()).apply { leftMargin = 118.d(); topMargin = 18.d() })
        addView(led, LayoutParams(56.d(), 56.d()).apply { leftMargin = 174.d(); topMargin = 18.d() })
        addView(volume, LayoutParams(64.d(), 64.d()).apply { leftMargin = 240.d(); topMargin = 14.d() })
        addView(hora, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply { rightMargin = 112.d(); topMargin = 26.d() })
        addView(energia, LayoutParams(56.d(), 56.d(), Gravity.END or Gravity.TOP).apply { rightMargin = 40.d(); topMargin = 18.d() })

        wifi.estado(Color.WHITE, true)
        bluetooth.estado(Color.WHITE, true)
        led.estado(Color.WHITE, true)
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

    /** Fita de LED: branca quando respondeu; vermelha com pontinho enquanto não responde (sem girar); apagada pelo usuário: branca a 45%. */
    fun definirLed(online: Boolean, ligada: Boolean) {
        when {
            !ligada -> led.estado(Color.WHITE, false, .45f, "desligada")
            online -> led.estado(Color.WHITE, false, 1f, "conectada")
            else -> led.estado(Color.WHITE, true, 1f, "sem conexão")
        }
    }

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
