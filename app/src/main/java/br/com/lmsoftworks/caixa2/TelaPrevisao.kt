package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import br.com.lmsoftworks.caixa2.voz.Respostas
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** O tipo do ícone para o código do tempo (WMO, do Open-Meteo): sol, lua, parcial, parcialnoite, nublado, neblina, garoa, chuva, pancadas, trovoada ou neve. */
fun tipoDoCodigo(codigo: Int, noite: Boolean = false): String = when (codigo) {
    0, 1 -> if (noite) "lua" else "sol"
    2 -> if (noite) "parcialnoite" else "parcial"
    3 -> "nublado"
    45, 48 -> "neblina"
    in 51..57 -> "garoa"
    in 61..67 -> "chuva"
    in 71..77, 85, 86 -> "neve"
    in 80..82 -> if (noite) "chuva" else "pancadas"
    in 95..99 -> "trovoada"
    else -> "nublado"
}

fun descricaoDoCodigo(codigo: Int, noite: Boolean = false): String = when (codigo) {
    0 -> if (noite) "Céu limpo" else "Ensolarado"
    1 -> "Pouco nublado"
    2 -> "Parcialmente nublado"
    3 -> "Nublado"
    45, 48 -> "Neblina"
    in 51..55 -> "Garoa"
    56, 57 -> "Garoa gelada"
    61, 63 -> "Chuva"
    65 -> "Chuva forte"
    66, 67 -> "Chuva gelada"
    in 71..77 -> "Neve"
    80 -> "Pancadas de chuva"
    81, 82 -> "Pancadas fortes"
    85, 86 -> "Pancadas de neve"
    95 -> "Trovoadas"
    96, 99 -> "Trovoadas com granizo"
    else -> "Tempo"
}

/** Cores do ícone e do quadradinho por tipo de tempo (as mesmas do cartão do Início). */
fun coresDoTipo(tipo: String): Pair<Int, Int> = when (tipo) {
    "sol" -> Color.parseColor("#FFC94D") to Color.parseColor("#29FFBE3C")
    "chuva", "garoa", "pancadas" -> Color.parseColor("#9CC8FF") to Color.parseColor("#294FAAFF")
    "parcial" -> Color.parseColor("#FFD27A") to Color.parseColor("#29FFBE3C")
    "parcialnoite" -> Color.parseColor("#C8D4FF") to Color.parseColor("#2A7C8CFF")
    "trovoada" -> Color.parseColor("#FFD166") to Color.parseColor("#29FFC94D")
    "neve" -> Color.parseColor("#E6F0FF") to Color.parseColor("#26DCE8FF")
    "lua" -> Color.parseColor("#C8D4FF") to Color.parseColor("#2A7C8CFF")
    else -> Color.parseColor("#C9D4EE") to Color.parseColor("#24C8D4EE")
}

/** Barra de faixa de temperatura de um dia: trilho fino e, em cima, a faixa mín–máx na escala dos 15 dias (degradê azul -> âmbar). */
class FaixaTemp(c: Context, private val min: Int, private val max: Int, private val gmin: Int, private val gmax: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), 6.d())

    override fun onDraw(canvas: Canvas) {
        val r = 3.d().toFloat()
        p.color = Color.parseColor("#1AFFFFFF")
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, p)
        val escala = (gmax - gmin).coerceAtLeast(1).toFloat()
        val a = (min - gmin) / escala * width
        val b = ((max - gmin) / escala * width).coerceAtLeast(a + 8.d())
        p.color = Color.WHITE      // o alfa do trilho não pode ficar no pincel (ele também apaga o degradê)
        p.shader = LinearGradient(a, 0f, b, 0f, Color.parseColor("#5EB8FF"), Color.parseColor("#FFC94D"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(a, 0f, b, height.toFloat(), r, r, p)
        p.shader = null
    }
}

/**
 * Tela Previsão do tempo da v3 (abre só pelo cartão do clima no Início). Esquerda: hoje (ícone animado, temperatura em Outfit 96, descrição,
 * cidade e data, Máx, Mín, Chuva e Vento) e as próximas 7 horas. Direita: os próximos 15 dias, com a faixa de temperatura de cada um.
 */
class TelaPrevisao(private val c: Context, private val aoTentarDeNovo: () -> Unit) : FrameLayout(c) {

    private val esquerda = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val dias = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val conteudo = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
    private val carregando = Carregando(c)
    private val erro = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE }
    private var previsao: Respostas.PrevisaoCompleta? = null
    private val nomesDias = listOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb")

    init {
        conteudo.setPadding(Tema.MARGEM_LATERAL.d(), 40.d(), Tema.MARGEM_LATERAL.d(), Tema.MARGEM_BASE.d())
        val cartaoEsq = FrameLayout(c).apply { background = cartaoVidro(); setPadding(30.d(), 28.d(), 30.d(), 24.d()); addView(esquerda, LayoutParams(-1, -1)) }
        conteudo.addView(cartaoEsq, LinearLayout.LayoutParams(500.d(), -1))
        val direita = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = cartaoVidro()
            setPadding(30.d(), 14.d(), 24.d(), 14.d())
            addView(rotuloSecao(c, "Próximos 15 dias").apply { setPadding(0, 10.d(), 0, 6.d()) })
            addView(RolagemFina(c).apply { addView(dias) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        conteudo.addView(direita, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = 24.d() })
        addView(conteudo, LayoutParams(-1, -1))
        conteudo.visibility = View.INVISIBLE

        addView(carregando, LayoutParams(70.d(), 70.d(), Gravity.CENTER))
        erro.addView(texto(c, "Sem previsão agora", 34, Tema.TEXTO, true))
        erro.addView(texto(c, "Confira a internet da caixa e tente de novo.", 22, Tema.TEXTO2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 8.d() })
        erro.addView(botao(c, "Tentar de novo", Cfg.AZUL) { mostrarCarregando(); aoTentarDeNovo() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 24.d() })
        addView(erro, LayoutParams(-2, -2, Gravity.CENTER))
    }

    fun mostrarCarregando() {
        erro.visibility = View.GONE
        if (previsao == null) { conteudo.visibility = View.INVISIBLE; carregando.visibility = View.VISIBLE }
    }

    /** Recebe a previsão (null = falhou: mostra a mensagem se ainda não havia nada). */
    fun definir(p: Respostas.PrevisaoCompleta?) {
        carregando.visibility = View.GONE
        if (p == null) {
            if (previsao == null) { conteudo.visibility = View.INVISIBLE; erro.visibility = View.VISIBLE }
            return
        }
        previsao = p
        erro.visibility = View.GONE
        conteudo.visibility = View.VISIBLE
        montarHoje(p)
        montarDias(p)
    }

    val tipoHoje: String? get() = previsao?.let { tipoDoCodigo(it.codigo, !it.dia) }

    private fun bloco(icone: Int, rotulo: String, valor: String): View = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = fundoArredondado(Color.parseColor("#0FFFFFFF"), 18.d())
        setPadding(16.d(), 12.d(), 16.d(), 12.d())
        addView(icone(c, icone, 24, Tema.TEXTO2), LinearLayout.LayoutParams(24.d(), 24.d()).apply { rightMargin = 12.d() })
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(texto(c, rotulo, 15, Tema.TEXTO3))
            addView(texto(c, valor, 23, Tema.TEXTO, true))
        })
    }

    private fun montarHoje(p: Respostas.PrevisaoCompleta) {
        esquerda.removeAllViews()
        val tipo = tipoDoCodigo(p.codigo, !p.dia)
        val (cor, fundo) = coresDoTipo(tipo)
        val topo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(c).apply {
                background = fundoArredondado(fundo, 34.d())
                addView(IconeClima(c, tipo, 92), LayoutParams(92.d(), 92.d(), Gravity.CENTER))
            }, LinearLayout.LayoutParams(112.d(), 112.d()))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(texto(c, "${p.temp}°", 96, Tema.TEXTO).apply { typeface = Tema.OUTFIT; letterSpacing = -3f / 96f })
                addView(texto(c, descricaoDoCodigo(p.codigo, !p.dia), 26, Tema.TEXTO).apply { maxLines = 1 }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = -6.d() })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 22.d() })
        }
        esquerda.addView(topo, LinearLayout.LayoutParams(-1, -2))
        val data = SimpleDateFormat("d 'de' MMMM", Locale("pt", "BR")).format(Date())
        esquerda.addView(texto(c, (if (p.cidade.isNotBlank()) "${p.cidade} · " else "") + "hoje, $data", 20, Tema.TEXTO3), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 14.d() })
        val grade = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        fun linha(a: View, b: View) = LinearLayout(c).apply {
            addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6.d() })
            addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 6.d() })
        }
        grade.addView(linha(bloco(R.drawable.ic_seta_cima, "Máx", "${p.max}°"), bloco(R.drawable.ic_seta_baixo, "Mín", "${p.min}°")))
        grade.addView(linha(bloco(R.drawable.ic_gota, "Chuva", "${p.chuva}%"), bloco(R.drawable.ic_vento, "Vento", "${p.vento} km/h")), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.d() })
        esquerda.addView(grade, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20.d() })
        esquerda.addView(View(c), LinearLayout.LayoutParams(-1, 0, 1f))
        esquerda.addView(rotuloSecao(c, "Próximas horas").apply { setPadding(0, 10.d(), 0, 12.d()) })
        // próximas horas: uma linha que rola para o lado (hora, ícone e temperatura lado a lado), até a manhã do dia seguinte e além
        val horas = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        p.horas.forEachIndexed { i, h ->
            val tipoH = tipoDoCodigo(h.codigo, !h.dia)
            val (corH, _) = coresDoTipo(tipoH)
            horas.addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = fundoArredondado(Color.parseColor("#0FFFFFFF"), 18.d())
                setPadding(14.d(), 10.d(), 14.d(), 10.d())
                addView(texto(c, if (i == 0) "Agora" else "${h.hora}h", 17, Tema.TEXTO3))
                addView(IconeAnimado(c, tipoH, corH, 24, animado = false), LinearLayout.LayoutParams(24.d(), 24.d()).apply { leftMargin = 10.d(); rightMargin = 10.d() })
                addView(texto(c, "${h.temp}°", 20, Tema.TEXTO, true))
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8.d() })
        }
        esquerda.addView(android.widget.HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(horas, LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun montarDias(p: Respostas.PrevisaoCompleta) {
        dias.removeAllViews()
        val gmin = p.dias.minOfOrNull { it.min } ?: 0
        val gmax = p.dias.maxOfOrNull { it.max } ?: 1
        for (d in p.dias) {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, d.indice) }
            val nome = when (d.indice) { 0 -> "Hoje"; 1 -> "Amanhã"; else -> nomesDias[cal.get(Calendar.DAY_OF_WEEK) - 1] + " " + cal.get(Calendar.DAY_OF_MONTH) }
            val (cor, _) = coresDoTipo(tipoDoCodigo(d.codigo))
            dias.addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(texto(c, nome, 22, Tema.TEXTO, d.indice == 0), LinearLayout.LayoutParams(118.d(), -2))
                addView(IconeAnimado(c, tipoDoCodigo(d.codigo), cor, 28, animado = false), LinearLayout.LayoutParams(28.d(), 28.d()))
                addView(texto(c, if (d.chuva >= 20) "${d.chuva}%" else "", 17, Color.parseColor("#8FC2FF")).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(64.d(), -2).apply { leftMargin = 16.d() })
                // mínima e máxima do dia, escritas (sem barra)
                addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
                addView(texto(c, "Mín ${d.min}°", 20, Tema.TEXTO2), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 22.d() })
                addView(texto(c, "Máx ${d.max}°", 21, Tema.TEXTO, true), LinearLayout.LayoutParams(-2, -2))
                setPadding(0, 0, 8.d(), 0)
            }, LinearLayout.LayoutParams(-1, 62.d()))
            dias.addView(View(c).apply { setBackgroundColor(Cfg.DIVISOR) }, LinearLayout.LayoutParams(-1, 1.d().coerceAtLeast(1)))
        }
    }
}
