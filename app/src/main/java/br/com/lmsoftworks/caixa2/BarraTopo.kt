package br.com.lmsoftworks.caixa2

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Calendar

/** Botão redondo de 44 px com um ícone dentro (e, se pedir, um círculo girando no lugar do ícone). */
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

    /** Azul quando conectado, vermelho quando não; [estado] vai para a descrição de acessibilidade. */
    fun conexao(conectado: Boolean) {
        pintar(if (conectado) AZUL_OK else Cfg.VERMELHO, !conectado)
        contentDescription = descricao + ": " + if (conectado) "conectado" else "desconectado"
    }

    companion object { val AZUL_OK = Color.parseColor("#3DA5FF") }
}

/**
 * Barra de cima. Esquerda: só ícones de estado (Wi-Fi, Bluetooth, fita de LED) e o volume, sempre branco.
 * Direita: a hora e o botão de energia. Azul = conectado, vermelho = desconectado.
 * Para voltar, desliza do canto esquerdo para a direita.
 */
class BarraTopo(
    c: Context,
    aoAbrirWifi: () -> Unit,
    aoAbrirBluetooth: () -> Unit,
    aoAbrirLed: () -> Unit,
    aoAbrirVolume: () -> Unit,
    aoAbrirEnergia: () -> Unit
) : LinearLayout(c) {

    private val wifi = BotaoRedondo(c, R.drawable.ic_wifi, "Wi-Fi")
    private val bluetooth = BotaoRedondo(c, R.drawable.ic_bluetooth, "Bluetooth")
    private val led = BotaoRedondo(c, R.drawable.ic_bulb, "Fita de LED")
    private val volume = BotaoRedondo(c, R.drawable.ic_volume_2, "Volume")
    private val energia = BotaoRedondo(c, R.drawable.ic_power, "Desligar ou reiniciar")
    private val hora: TextView = texto(c, "", 28, Cfg.TEXTO, true)

    /** Coloca a pílula do volume logo depois do botão de volume (ela cresce para a direita, na própria barra). */
    fun colocarPilulaVolume(pilula: View) {
        val i = indexOfChild(volume)
        addView(pilula, i + 1, LayoutParams(0, 66.d()).apply { rightMargin = 16.d() })
    }

    /** Posição do botão de volume na barra (para o painel abrir logo abaixo dele). */
    val volumeX: Int get() = volume.left

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(28.d(), 0, 28.d(), 0)
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#CC050D22"), Color.TRANSPARENT)
        )

        wifi.setOnClickListener { aoAbrirWifi() }
        bluetooth.setOnClickListener { aoAbrirBluetooth() }
        led.setOnClickListener { aoAbrirLed() }
        volume.setOnClickListener { aoAbrirVolume() }
        energia.setOnClickListener { aoAbrirEnergia() }

        for (b in listOf(wifi, bluetooth, led, volume)) {
            addView(b, LayoutParams(66.d(), 66.d()).apply { rightMargin = 16.d() })
        }
        addView(View(c), LayoutParams(0, 1, 1f))
        addView(hora, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = 20.d() })
        addView(energia, LayoutParams(66.d(), 66.d()))

        wifi.conexao(false); bluetooth.conexao(false); led.conexao(false)
        volume.pintar(Color.WHITE, false)
        energia.pintar(Cfg.TEXTO, false)
        atualizarHora()
        mostrarHora(false)
    }

    fun definirWifi(conectado: Boolean) = wifi.conexao(conectado)

    fun definirBluetooth(conectado: Boolean) {
        bluetooth.trocarIcone(if (conectado) R.drawable.ic_bluetooth_conectado else R.drawable.ic_bluetooth)
        bluetooth.conexao(conectado)
    }

    /** Fita de LED: azul quando respondeu, vermelho quando não; um círculo girando enquanto tenta conectar. */
    fun definirLed(online: Boolean, carregando: Boolean) {
        led.conexao(online)
        led.carregando(carregando && !online)
        if (carregando && !online) {
            led.pintar(Cfg.MUDO, false)
            led.contentDescription = "Fita de LED: conectando"
        }
    }

    /** O volume não é uma conexão: fica sempre branco; o ícone mostra o nível. [mudo] = cortado. */
    fun definirVolume(porcento: Int, mudo: Boolean) {
        volume.trocarIcone(
            when {
                mudo || porcento == 0 -> R.drawable.ic_volume_x
                porcento < 34 -> R.drawable.ic_volume_0
                porcento < 67 -> R.drawable.ic_volume_1
                else -> R.drawable.ic_volume_2
            }
        )
        volume.contentDescription = if (mudo) "Volume: mudo" else "Volume: $porcento por cento"
    }

    /** A hora da barra só aparece nos menus; na tela Início a hora grande já está na tela. */
    fun mostrarHora(v: Boolean) { hora.visibility = if (v) View.VISIBLE else View.GONE }

    fun atualizarHora() {
        val cal = Calendar.getInstance()
        hora.text = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }
}
