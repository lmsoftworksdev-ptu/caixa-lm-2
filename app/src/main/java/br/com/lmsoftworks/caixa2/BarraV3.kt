package br.com.lmsoftworks.caixa2

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Calendar

/**
 * Botão redondo da barra da v3: 60 x 60, ícone de 28, fundo #0DFFFFFF com borda de 1 px. Estados: azul (conectado), vermelho
 * (desconectado, fundo e borda avermelhados), branco (neutro) e anel azul girando em volta enquanto conecta (1 volta/s).
 */
class BotaoBarra(c: Context, recurso: Int, private val descricao: String) : View(c) {
    private var figura: Drawable = c.getDrawable(recurso)!!.mutate()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var corIcone = Color.WHITE
    private var erro = false
    private var anel: ValueAnimator? = null
    private var giro = 0f
    private var girando = false

    init { isClickable = true; efeitoToque(); contentDescription = descricao }

    fun trocarIcone(recurso: Int) { context.getDrawable(recurso)?.mutate()?.let { figura = it }; invalidate() }

    /** [cor] do ícone; [comErro] pinta fundo e borda de vermelho; [conectando] mostra o anel girando. */
    fun estado(cor: Int, comErro: Boolean, conectando: Boolean = false, texto: String = "") {
        corIcone = cor; erro = comErro
        if (texto.isNotEmpty()) contentDescription = "$descricao: $texto"
        if (conectando != girando) {
            girando = conectando
            anel?.cancel()
            if (conectando) {
                anel = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 1000; repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { giro = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); anel?.cancel(); girando = false }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = width / 2f - .5f
        p.style = Paint.Style.FILL
        p.color = if (erro) Color.parseColor("#1FFF4D5E") else Color.parseColor("#0DFFFFFF")
        canvas.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.d().coerceAtLeast(1).toFloat()
        p.color = if (erro) Color.parseColor("#4DFF4D5E") else Color.parseColor("#1AFFFFFF")
        canvas.drawCircle(cx, cy, r, p)
        if (girando) {
            p.strokeWidth = 2.5f.d(); p.strokeCap = Paint.Cap.ROUND
            p.color = Tema.CONECTADO
            val folga = 1.25f.d()
            canvas.drawArc(folga, folga, width - folga, height - folga, giro, 100f, false, p)
            p.strokeCap = Paint.Cap.BUTT
        }
        val t = 36.d()
        figura.setBounds((cx - t / 2).toInt(), (cy - t / 2).toInt(), (cx + t / 2).toInt(), (cy + t / 2).toInt())
        figura.setTint(corIcone)
        figura.draw(canvas)
    }
}

/**
 * Barra de cima da v3 (altura 104, sobre todas as telas, degradê #8C050A18 -> transparente). Esquerda: Wi-Fi, Bluetooth, fita de
 * LED e volume (botões redondos de 60, 12 entre eles). Direita: a hora (só nos menus) e o botão de energia.
 */
class BarraTopo(
    c: Context,
    aoAbrirWifi: () -> Unit,
    aoAbrirBluetooth: () -> Unit,
    aoAbrirLed: () -> Unit,
    aoAbrirVolume: () -> Unit,
    aoAbrirEnergia: () -> Unit
) : FrameLayout(c) {

    private val wifi = BotaoBarra(c, R.drawable.ic_wifi, "Wi-Fi")
    private val bluetooth = BotaoBarra(c, R.drawable.ic_bluetooth, "Bluetooth")
    private val led = BotaoBarra(c, R.drawable.ic_bulb, "Fita de LED")
    private val volume = BotaoBarra(c, R.drawable.ic_volume_2, "Volume")
    private val energia = BotaoBarra(c, R.drawable.ic_power, "Desligar ou reiniciar")
    private val hora: TextView = texto(c, "", 38, Tema.TEXTO).apply { letterSpacing = 0.02f; fontFeatureSettings = "tnum" }
    /** Velocidade de download ao vivo (↓), ao lado da hora. */
    private val rede: TextView = texto(c, "", 21, Tema.TEXTO2).apply { fontFeatureSettings = "tnum" }

    /** Coloca a pílula do volume atrás do botão de volume (ela cresce para a direita a partir dele). */
    fun colocarPilulaVolume(pilula: View) {
        addView(pilula, indexOfChild(volume), LayoutParams(0, TAM.d()).apply { leftMargin = X_VOLUME.d(); topMargin = TOPO.d() })
    }

    companion object {
        const val TAM = 76
        const val TOPO = 14
        const val X_WIFI = 36
        const val X_BLUETOOTH = 126
        const val X_LED = 216
        const val X_VOLUME = 306      // Wi-Fi, Bluetooth, luz (LED) e volume
    }

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#8C050A18"), Color.TRANSPARENT))
        wifi.setOnClickListener { aoAbrirWifi() }
        bluetooth.setOnClickListener { aoAbrirBluetooth() }
        led.setOnClickListener { aoAbrirLed() }
        volume.setOnClickListener { aoAbrirVolume() }
        energia.setOnClickListener { aoAbrirEnergia() }

        for ((v, x) in listOf(wifi to X_WIFI, bluetooth to X_BLUETOOTH, led to X_LED, volume to X_VOLUME))
            addView(v, LayoutParams(TAM.d(), TAM.d()).apply { leftMargin = x.d(); topMargin = TOPO.d() })
        addView(hora, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply { rightMargin = 134.d(); topMargin = 28.d() })
        addView(rede, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply { rightMargin = 134.d(); topMargin = 38.d() })
        addView(energia, LayoutParams(TAM.d(), TAM.d(), Gravity.END or Gravity.TOP).apply { rightMargin = 36.d(); topMargin = TOPO.d() })

        wifi.estado(Tema.ERRO, true)
        bluetooth.estado(Tema.ERRO, true)
        led.estado(Tema.ERRO, true)
        volume.estado(Color.WHITE, false)
        energia.estado(Color.WHITE, false)
        atualizarHora()
        mostrarHora(false)
    }

    /** [ligado] = Wi-Fi ligado; [conectado] = tem rede. Sem conexão: vermelho com o ícone wifi-off. */
    fun definirWifi(ligado: Boolean, conectado: Boolean) {
        wifi.trocarIcone(R.drawable.ic_wifi)      // sem conexão: o mesmo ícone, só vermelho (sem a barra cortando)
        if (conectado) wifi.estado(Tema.CONECTADO, false, false, "conectado") else wifi.estado(Tema.ERRO, true, false, if (ligado) "sem rede" else "desligado")
    }

    /** Sempre o mesmo símbolo; só a cor muda. */
    fun definirBluetooth(conectado: Boolean) {
        if (conectado) bluetooth.estado(Tema.CONECTADO, false, false, "conectado") else bluetooth.estado(Tema.ERRO, true, false, "desconectado")
    }

    /** Azul = conectada e ligada; vermelha = desconectada, conectando/reconectando ou com as luzes desligadas (sem anel girando). */
    fun definirLed(conectada: Boolean, ligada: Boolean, conectando: Boolean) {
        when {
            conectando -> led.estado(Tema.ERRO, true, false, "conectando")
            conectada && ligada -> led.estado(Tema.CONECTADO, false, false, "conectada")
            conectada -> led.estado(Tema.ERRO, true, false, "desligada")
            else -> led.estado(Tema.ERRO, true, false, "desconectada")
        }
    }

    /** O volume é sempre branco; o ícone mostra o nível (0 a 10). [mudo] = cortado. */
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
    fun mostrarHora(v: Boolean) { hora.visibility = if (v) View.VISIBLE else View.GONE; posicionarRede() }

    /** O indicador fica à esquerda da hora (ou no lugar dela, na tela Início, onde a hora da barra não aparece). */
    private fun posicionarRede() {
        val lp = rede.layoutParams as LayoutParams
        val margem = 134.d() + if (hora.visibility == View.VISIBLE && hora.width > 0) hora.width + 26.d() else 0
        if (lp.rightMargin != margem) { lp.rightMargin = margem; rede.layoutParams = lp }
    }

    /** Chamada a cada meio segundo, depois de [UsoRede.amostrar]. */
    fun atualizarRede() { rede.text = UsoRede.texto; posicionarRede() }

    fun atualizarHora() {
        val cal = Calendar.getInstance()
        hora.text = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }
}
