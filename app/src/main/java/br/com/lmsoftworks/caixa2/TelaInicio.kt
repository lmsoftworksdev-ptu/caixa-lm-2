package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Tela Início: hora grande, data, saudação e os 5 botões. */
class TelaInicio(c: Context, private val aoAbrir: (String) -> Unit) : LinearLayout(c) {

    private val relogio = texto(c, "00:00", 170, Cfg.TEXTO, true)
    private val data = texto(c, "", 26, Color.WHITE).apply { alpha = .9f }
    private val ola = texto(c, "", 22, Color.parseColor("#D6E0F7"))
    private val proximo = texto(c, "", 24, Color.parseColor("#FFD2EA"))
    private val formatoData = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR"))

    init {
        orientation = VERTICAL
        setPadding(48.d(), 24.d(), 48.d(), 40.d())

        relogio.letterSpacing = -0.025f
        relogio.gravity = Gravity.CENTER
        relogio.setShadowLayer(40.d().toFloat(), 0f, 6.d().toFloat(), Color.parseColor("#662F7BFF"))

        val topo = LinearLayout(c).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(relogio)
            addView(data, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 2.d() })
            addView(ola, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 8.d() })
            addView(proximo, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 8.d() })
        }
        addView(topo, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val botoes = LinearLayout(c).apply { orientation = HORIZONTAL; clipChildren = false }
        clipChildren = false
        val lista = listOf(
            arrayOf("midia", "Mídia", "#FF6B8A", "#6A35D9"),
            arrayOf("led", "Luzes LED", "#FFB757", "#E0560F"),
            arrayOf("wifi", "Wi-Fi", "#47DFEB", "#0C8AA0"),
            arrayOf("alarmes", "Alarmes", "#FF7AC0", "#B81F7A")
        )
        val icones = mapOf(
            "midia" to R.drawable.ic_video,
            "led" to R.drawable.ic_bulb, "wifi" to R.drawable.ic_wifi,
            "alarmes" to R.drawable.ic_alarme
        )
        for ((id, nome, c1, c2) in lista) {
            val chip = ChipIcone(c, icones.getValue(id), Color.parseColor(c1), Color.parseColor(c2)).apply {
                contentDescription = nome
                setOnClickListener { aoAbrir(id) }
            }
            val coluna = FrameLayout(c).apply { clipChildren = false; addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) }
            botoes.addView(coluna, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(botoes, LayoutParams(LayoutParams.MATCH_PARENT, 176.d()))
        atualizar()
    }

    /** Abaixo do "Como posso ajudar?": o próximo alarme (some se não há nenhum ligado). */
    fun definirProximoAlarme(t: String?) {
        proximo.text = if (t == null) "" else "⏰  $t"
        proximo.visibility = if (t == null) android.view.View.GONE else android.view.View.VISIBLE
    }

    fun atualizar() {
        val agora = Date()
        val cal = Calendar.getInstance()
        relogio.text = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        data.text = formatoData.format(agora).replaceFirstChar { it.uppercase() }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        ola.text = (if (h < 12) "Bom dia" else if (h < 18) "Boa tarde" else "Boa noite") + "! Como posso ajudar?"
    }
}
