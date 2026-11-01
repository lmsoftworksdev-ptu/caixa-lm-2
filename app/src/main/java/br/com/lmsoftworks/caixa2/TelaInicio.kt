package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.com.lmsoftworks.caixa2.voz.Respostas
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Tela Início da v2, alinhada à esquerda: relógio gigante, data e saudação; à direita uma coluna de 3 cartões (clima, próximo
 * alarme, lembrete) que somem quando não há nada para mostrar (a coluna nunca fica com buraco); embaixo à esquerda o dock de
 * vidro com os 3 botões de ícone (Mídia, LED, Configurações), sem rótulo, cada um com um pontinho na cor do menu.
 */
class TelaInicio(private val c: Context, private val aoAbrir: (String) -> Unit) : FrameLayout(c) {

    private val relogio = texto(c, "00:00", 270, Cfg.TX).apply { typeface = FONTE_LEVE; letterSpacing = -0.05f }
    private val data = texto(c, "", 42, Cfg.TX).apply { typeface = FONTE_LEVE }
    private val formatoData = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR"))

    /** Um cartão da coluna da direita: rótulo (ícone + CAIXA ALTA), valor grande e linha de apoio. */
    private inner class Cartao(emojiRotulo: String, tamValor: Int, leve: Boolean, espacamentoValor: Float = 0f) : LinearLayout(c) {
        val emoji = texto(c, emojiRotulo, 22, Cfg.TX)
        val emojiGrande = texto(c, "", 64, Cfg.TX).apply { visibility = View.GONE }
        val rotulo = texto(c, "", 17, Cfg.TX_MUDO, true).apply { letterSpacing = .14f }
        val valor = texto(c, "", tamValor, Cfg.TX).apply { if (leve) typeface = FONTE_LEVE; letterSpacing = espacamentoValor }
        val apoio = texto(c, "", 21, Cfg.TX2)

        init {
            orientation = VERTICAL
            setPadding(28.d(), 22.d(), 28.d(), 22.d())
            background = fundoArredondado(Cfg.SUPERFICIE, 30.d())
            val linha = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(emoji, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 10.d() })
                addView(rotulo)
            }
            addView(linha)
            val linhaValor = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(valor, LinearLayout.LayoutParams(0, -2, 1f))
                addView(emojiGrande, LinearLayout.LayoutParams(-2, -2))
            }
            addView(linhaValor, LayoutParams(-1, -2).apply { topMargin = 6.d() })
            addView(apoio, LayoutParams(-2, -2).apply { topMargin = 2.d() })
            visibility = View.GONE
        }
    }

    private val cartaoClima = Cartao("☁️", 84, true, -0.02f)
    private val cartaoAlarme = Cartao("⏰", 40, true, -0.02f)
    private val cartaoLembrete = Cartao("🔔", 32, false)

    init {
        addView(relogio, LayoutParams(-2, -2).apply { leftMargin = 64.d(); topMargin = 82.d() })
        addView(data, LayoutParams(-2, -2).apply { leftMargin = 72.d(); topMargin = 372.d() })

        val coluna = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(cartaoClima, LinearLayout.LayoutParams(-1, -2))
            addView(cartaoAlarme, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16.d() })
            addView(cartaoLembrete, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16.d() })
        }
        addView(coluna, LayoutParams(360.d(), -2).apply { leftMargin = (1280 - 64 - 360).d(); topMargin = 118.d() })

        addView(montarDock(), LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = 64.d(); bottomMargin = 52.d() })
        atualizar()
    }

    private fun montarDock(): View {
        val botoes = listOf(
            arrayOf("midia", "Mídia", "#FF5C7A"), arrayOf("led", "Luzes LED", "#FFB84D"), arrayOf("config", "Configurações", "#B7BACB")
        )
        val icones = mapOf("midia" to R.drawable.ic_video, "led" to R.drawable.ic_bulb, "config" to R.drawable.ic_config)
        val dock = LinearLayout(c).apply {
            setPadding(12.d(), 12.d(), 12.d(), 12.d())
            background = fundoArredondado(Color.parseColor("#1AFFFFFF"), 70.d())
        }
        for ((i, b) in botoes.withIndex()) {
            val botao = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                contentDescription = b[1]
                addView(ImageView(c).apply { setImageResource(icones.getValue(b[0])); setColorFilter(Color.WHITE) }, LinearLayout.LayoutParams(50.d(), 50.d()))
                addView(View(c).apply { background = fundoArredondado(Color.parseColor(b[2]), 4.d()) }, LinearLayout.LayoutParams(8.d(), 8.d()).apply { topMargin = 12.d() })
                setOnClickListener { aoAbrir(b[0]) }
                // ao tocar: fundo branco 20% e escala 0,95
                setOnTouchListener { v, e ->
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> { v.background = fundoArredondado(Color.parseColor("#33FFFFFF"), 58.d()); v.animate().scaleX(.95f).scaleY(.95f).setDuration(120).start() }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.background = null; v.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }
                    }
                    false
                }
            }
            dock.addView(botao, LinearLayout.LayoutParams(112.d(), 112.d()).apply { if (i > 0) leftMargin = 6.d() })
        }
        return dock
    }

    /** Cartão do próximo alarme: [t] chega como "Amanhã às 07:00"; o valor é a hora e o apoio é o dia. */
    fun definirProximoAlarme(t: String?) {
        if (t == null) { cartaoAlarme.visibility = View.GONE; return }
        val m = Regex("""^(.*) às (\d{2}:\d{2})$""").find(t)
        cartaoAlarme.rotulo.text = "PRÓXIMO ALARME"
        cartaoAlarme.valor.text = m?.groupValues?.get(2) ?: t
        cartaoAlarme.apoio.text = m?.groupValues?.get(1) ?: ""
        cartaoAlarme.apoio.visibility = if (m == null) View.GONE else View.VISIBLE
        cartaoAlarme.visibility = View.VISIBLE
    }

    /** Cartão do clima (some se ainda não há previsão). */
    fun definirClima(cl: Respostas.ClimaHoje?) {
        if (cl == null) { cartaoClima.visibility = View.GONE; return }
        cartaoClima.rotulo.text = cl.cidade.ifBlank { "CLIMA" }.uppercase()
        cartaoClima.valor.text = "${cl.temp}°"
        cartaoClima.emoji.text = cl.emoji()
        cartaoClima.emojiGrande.text = cl.emoji()
        cartaoClima.emojiGrande.visibility = View.VISIBLE
        cartaoClima.apoio.text = listOfNotNull(
            cl.chuva?.let { "Chuva $it%" }, cl.min?.let { "mín $it°" }, cl.max?.let { "máx $it°" }
        ).joinToString(" · ")
        cartaoClima.visibility = View.VISIBLE
    }

    /** Cartão do próximo lembrete: [texto] e quando ("Hoje às 09:00"); some se não há nenhum. */
    fun definirLembrete(texto: String?, quando: String?) {
        if (texto == null) { cartaoLembrete.visibility = View.GONE; return }
        cartaoLembrete.rotulo.text = "LEMBRETE"
        cartaoLembrete.valor.text = texto
        cartaoLembrete.valor.maxLines = 2
        cartaoLembrete.apoio.text = quando?.replaceFirstChar { it.uppercase() } ?: ""
        cartaoLembrete.visibility = View.VISIBLE
    }

    fun atualizar() {
        val agora = Date()
        val cal = Calendar.getInstance()
        relogio.text = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        data.text = formatoData.format(agora).replaceFirstChar { it.uppercase() }
    }
}
