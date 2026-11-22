package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout

/**
 * Digitação por voz dos teclados (Receitas, Rádios, listas, lembretes, senha do Wi-Fi...): a tela principal liga [ouvir] ao assistente de
 * voz; cada teclado só chama [ouvir] e recebe o texto, com os números já em algarismos (o reconhecimento entrega "três", e aqui vira "3").
 */
object EntradaVoz {
    /** (aoComecar, aoParcial, aoTexto, aoPesquisar): começa a ouvir. [aoTexto] recebe null se não ouviu nada; [aoPesquisar] avisa que a pessoa terminou de falar. Ligado em InicioActivity. */
    var ouvir: ((() -> Unit, (String) -> Unit, (String?) -> Unit, () -> Unit) -> Unit)? = null
    /** Volume da voz no microfone (0 a 1), para o botão pulsar com a fala. Ligado em InicioActivity. */
    var nivel: (() -> Float)? = null

    private fun misturar(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    /**
     * A animação do microfone, igual à da Mídia: vermelho pulsando com a voz enquanto ouve; verde respirando quando a pessoa termina de
     * falar (até o resultado chegar); normal no fim.
     */
    class AnimacaoMic(private val botao: FrameLayout, private val tamanho: Int = 56) {
        private var anim: android.animation.ValueAnimator? = null

        private fun pintar(cor: Int, escala: Float) {
            botao.background = fundoArredondado(cor, (tamanho / 2).d())
            botao.scaleX = escala; botao.scaleY = escala
        }

        fun ouvindo() {
            anim?.cancel()
            val apagado = misturar(Tema.ERRO, Color.BLACK, 0.35f)
            var suave = 0f
            val t0 = android.os.SystemClock.uptimeMillis()
            anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000; repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener {
                    val n = (nivel?.invoke() ?: 0f).coerceIn(0f, 1f)
                    suave += (n - suave) * (if (n > suave) 0.5f else 0.15f)      // sobe rápido com a voz e desce devagar
                    val respiro = 0.04f * Math.sin((android.os.SystemClock.uptimeMillis() - t0) / 1000.0 * 2 * Math.PI * 0.9).toFloat()
                    pintar(misturar(apagado, Tema.ERRO, (0.35f + 0.65f * suave).coerceIn(0f, 1f)), 1.04f + respiro + 0.35f * suave)
                }
                start()
            }
        }

        fun pesquisando() {
            anim?.cancel()
            anim = android.animation.ValueAnimator.ofFloat(1f, 1.12f).apply {
                duration = 700; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener { pintar(Tema.OK, it.animatedValue as Float) }
                start()
            }
        }

        fun parar() {
            anim?.cancel(); anim = null
            pintar(Color.parseColor("#33FFFFFF"), 1f)
        }
    }

    /** Botão redondo do microfone, no mesmo desenho do da Mídia. */
    fun botao(c: Context, tamanho: Int = 56, aoClicar: () -> Unit): FrameLayout = FrameLayout(c).apply {
        background = fundoArredondado(Color.parseColor("#33FFFFFF"), (tamanho / 2).d())
        isClickable = true
        contentDescription = "Falar"
        setOnClickListener { aoClicar() }
        efeitoToque()
        addView(icone(c, R.drawable.ic_mic, tamanho / 2 + 2, Color.WHITE), FrameLayout.LayoutParams((tamanho / 2 + 2).d(), (tamanho / 2 + 2).d(), Gravity.CENTER))
    }

    /** Liga ou desliga o destaque do botão enquanto ouve. */
    fun destacar(botao: FrameLayout, ouvindo: Boolean, tamanho: Int = 56) {
        botao.background = fundoArredondado(if (ouvindo) Cfg.AZUL else Color.parseColor("#33FFFFFF"), (tamanho / 2).d())
    }

    // ------------------------------------------------------------------ números por extenso -> algarismos

    private val unidades = mapOf(
        "zero" to 0, "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "três" to 3, "tres" to 3, "quatro" to 4,
        "cinco" to 5, "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9
    )
    private val adolescentes = mapOf(
        "dez" to 10, "onze" to 11, "doze" to 12, "treze" to 13, "quatorze" to 14, "catorze" to 14, "quinze" to 15,
        "dezesseis" to 16, "dezasseis" to 16, "dezessete" to 17, "dezassete" to 17, "dezoito" to 18, "dezenove" to 19, "dezanove" to 19
    )
    private val dezenas = mapOf(
        "vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50, "sessenta" to 60, "setenta" to 70, "oitenta" to 80, "noventa" to 90
    )
    private val centenas = mapOf(
        "cem" to 100, "cento" to 100, "duzentos" to 200, "duzentas" to 200, "trezentos" to 300, "trezentas" to 300, "quatrocentos" to 400,
        "quatrocentas" to 400, "quinhentos" to 500, "quinhentas" to 500, "seiscentos" to 600, "seiscentas" to 600, "setecentos" to 700,
        "setecentas" to 700, "oitocentos" to 800, "oitocentas" to 800, "novecentos" to 900, "novecentas" to 900
    )

    private fun limpa(p: String) = p.lowercase().trim(',', '.', '!', '?', ';', ':')

    private fun eNumero(p: String): Boolean {
        val w = limpa(p)
        return w in unidades || w in adolescentes || w in dezenas || w in centenas || w == "mil"
    }

    /** Até 99 a partir de [i]: devolve (valor, próximo) ou null. */
    private fun ate99(p: List<String>, i: Int): Pair<Int, Int>? {
        if (i >= p.size) return null
        val w = limpa(p[i])
        adolescentes[w]?.let { return it to i + 1 }
        dezenas[w]?.let { d ->
            if (i + 2 < p.size && limpa(p[i + 1]) == "e") {
                val u = unidades[limpa(p[i + 2])]
                if (u != null && u in 1..9) return d + u to i + 3
            }
            return d to i + 1
        }
        unidades[w]?.let { return it to i + 1 }
        return null
    }

    /** Até 999 a partir de [i]: devolve (valor, próximo) ou null. */
    private fun ate999(p: List<String>, i: Int): Pair<Int, Int>? {
        if (i >= p.size) return null
        val c = centenas[limpa(p[i])]
        if (c != null) {
            if (i + 2 < p.size && limpa(p[i + 1]) == "e") ate99(p, i + 2)?.let { (v, j) -> return c + v to j }
            return c to i + 1
        }
        return ate99(p, i)
    }

    /**
     * Troca os números falados por algarismos: "três" -> "3", "vinte e um" -> "21", "três quatro cinco" -> "345", "dois mil e vinte" -> "2020".
     * "um/uma" sozinhos continuam como palavra (artigo: "uma receita de bolo"), exceto em [senha] (aí tudo vira número e sem espaços).
     */
    fun numerosEmDigitos(texto: String, senha: Boolean = false): String {
        val p = texto.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val saida = ArrayList<String>()
        var i = 0
        while (i < p.size) {
            val w = limpa(p[i])
            if (!eNumero(w)) { saida.add(p[i]); i++; continue }
            // "mil" sozinho ou "N mil [e] resto"
            var valor = 0L
            var j = i
            val primeiro = ate999(p, i)
            if (w == "mil") { valor = 1000; j = i + 1 }
            else if (primeiro != null && primeiro.second < p.size && limpa(p[primeiro.second]) == "mil") { valor = primeiro.first * 1000L; j = primeiro.second + 1 }
            else if (primeiro != null) {
                // números soltos (0 a 9) em fila são dígitos de um código: "três quatro cinco" -> 345
                val ehUnidade = w in unidades
                if (ehUnidade && primeiro.first in 0..9) {
                    var k = i; val dig = StringBuilder()
                    while (k < p.size && limpa(p[k]) in unidades) { dig.append(unidades.getValue(limpa(p[k]))); k++ }
                    if (dig.length > 1 || senha || (w != "um" && w != "uma")) saida.add(dig.toString()) else saida.add(p[i])
                    i = k; continue
                }
                saida.add(primeiro.first.toString()); i = primeiro.second; continue
            } else { saida.add(p[i]); i++; continue }
            // resto depois do "mil" (com ou sem "e")
            var k = j
            if (k < p.size && limpa(p[k]) == "e" && ate999(p, k + 1) != null) k++
            ate999(p, k)?.let { (v, prox) -> valor += v; j = prox }
            saida.add(valor.toString()); i = j
        }
        val junto = saida.joinToString(" ")
        return if (senha) junto.replace(" ", "") else junto
    }
}
