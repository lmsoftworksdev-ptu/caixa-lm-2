package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Tela Configurações da v3: à esquerda o título e as cinco seções (Tela, Som, Assistente, Conexões, Sistema); à direita as opções da
 * seção escolhida, uma por linha de 84 com divisor. Wi-Fi, Bluetooth e Fita de LED abrem a própria tela (e voltam para cá).
 */
class TelaConfig(private val c: Context, private val aoAbrir: (String) -> Unit) : FrameLayout(c) {

    // ligações com o resto do app (a tela principal preenche)
    var lerVolume: () -> Int = { 0 }                 // 0 a 10
    var definirVolume: (Int) -> Unit = {}            // 0 a 10
    var aoMudarBrilho: () -> Unit = {}
    var aoVerificarAtualizacao: () -> Unit = {}
    var aoEnergia: () -> Unit = {}
    var versao: String = ""

    private class Secao(val id: String, val nome: String, val descricao: String, val icone: Int)

    private val secoes = listOf(
        Secao("tela", "Tela", "Brilho e modo ocioso", R.drawable.ic_bulb),
        Secao("som", "Som", "Volume e avisos", R.drawable.ic_volume_2),
        Secao("assistente", "Assistente", "Resposta e microfone", R.drawable.ic_mic),
        Secao("conexoes", "Conexões", "Wi-Fi, Bluetooth e fita", R.drawable.ic_wifi),
        Secao("sistema", "Sistema", "Versão e energia", R.drawable.ic_config)
    )
    private var atual = "tela"
    private val estados = HashMap<String, String>()
    private val menu = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val opcoes = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    init {
        val raiz = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Tema.MARGEM_LATERAL.d(), 40.d(), Tema.MARGEM_LATERAL.d(), Tema.MARGEM_BASE.d())
        }
        val esquerda = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        esquerda.addView(texto(c, "Configurações", 44, Tema.TEXTO, true), LinearLayout.LayoutParams(-2, 64.d()))
        esquerda.addView(menu, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16.d() })
        raiz.addView(esquerda, LinearLayout.LayoutParams(360.d(), -1))
        val direita = FrameLayout(c).apply {
            background = cartaoVidro()
            setPadding(30.d(), 10.d(), 30.d(), 10.d())
            addView(RolagemFina(c).apply { addView(opcoes) }, LayoutParams(-1, -1))
        }
        raiz.addView(direita, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = 24.d() })
        addView(raiz, LayoutParams(-1, -1))
        montar()
    }

    // ------------------------------------------------------------------ menu das seções

    private fun montarMenu() {
        menu.removeAllViews()
        for (s in secoes) {
            val sel = s.id == atual
            val textos = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(texto(c, s.nome, 23, Tema.TEXTO, true))
                addView(texto(c, s.descricao, 16, Tema.TEXTO3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 2.d() })
            }
            menu.addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16.d(), 0, 12.d(), 0)
                background = fundoArredondado(if (sel) Color.parseColor("#1AFFFFFF") else Color.TRANSPARENT, 20.d())
                isClickable = true
                setOnClickListener { atual = s.id; montar() }
                efeitoToque()
                addView(icone(c, s.icone, 28, if (sel) Cfg.AZUL else Tema.TEXTO2), LinearLayout.LayoutParams(28.d(), 28.d()).apply { rightMargin = 16.d() })
                addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, 80.d()).apply { bottomMargin = 6.d() })
        }
    }

    // ------------------------------------------------------------------ linhas das opções

    private fun linha(titulo: String, sub: String? = null, direita: View, larguraDireita: Int = -2, aoClicar: (() -> Unit)? = null): View {
        val textos = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(texto(c, titulo, 24, Tema.TEXTO, true))
            if (sub != null) addView(texto(c, sub, 17, Tema.TEXTO3), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 2.d() })
        }
        val linha = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            if (aoClicar != null) { isClickable = true; setOnClickListener { aoClicar() }; efeitoToque() }
            addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
            addView(direita, LinearLayout.LayoutParams(larguraDireita, -2))
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(linha, LinearLayout.LayoutParams(-1, 84.d()))
            addView(View(c).apply { setBackgroundColor(Cfg.DIVISOR) }, LinearLayout.LayoutParams(-1, 1.d().coerceAtLeast(1)))
        }
    }

    private fun deslizador(valor: Float, rotulo: (Float) -> String, aoMudar: (Float) -> Unit): View {
        val ro = texto(c, rotulo(valor), 21, Tema.TEXTO2).apply { gravity = Gravity.END }
        val d = Deslizante(c, false).apply {
            this.valor = valor
            this.aoMudar = { f, _ -> ro.text = rotulo(f); aoMudar(f) }
        }
        return LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(d, LinearLayout.LayoutParams(300.d(), 44.d()))
            addView(ro, LinearLayout.LayoutParams(76.d(), -2))
        }
    }

    private fun escolhas(nomes: List<String>, sel: Int, aoEscolher: (Int) -> Unit): View = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        nomes.forEachIndexed { i, n ->
            val on = i == sel
            addView(TextView(c).apply {
                text = n
                setTextColor(Color.WHITE)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 19.d().toFloat())
                typeface = FONTE_MEDIA
                gravity = Gravity.CENTER
                setPadding(18.d(), 0, 18.d(), 0)
                background = fundoArredondado(if (on) Cfg.AZUL else Color.parseColor("#14FFFFFF"), 23.d(), if (on) 0 else Tema.BOTAO_BORDA)
                isClickable = true
                setOnClickListener { aoEscolher(i) }
                efeitoToque()
            }, LinearLayout.LayoutParams(-2, 46.d()).apply { leftMargin = 6.d() })
        }
    }

    private fun interruptor(ligado: Boolean, aoMudar: (Boolean) -> Unit): View = Interruptor(c).apply {
        this.ligado = ligado
        this.aoMudar = { v -> this.ligado = v; aoMudar(v) }
    }

    private fun seta(): View = icone(c, R.drawable.ic_seta_direita, 28, Tema.TEXTO3)

    private fun estadoTexto(s: String, cor: Int = Tema.TEXTO2): View = texto(c, s, 20, cor).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.END }

    // ------------------------------------------------------------------ conteúdo de cada seção

    private fun montar() {
        montarMenu()
        opcoes.removeAllViews()
        when (atual) {
            "tela" -> {
                opcoes.addView(linha("Brilho da tela", null, deslizador((Ajustes.brilho - 5) / 95f, { "${(5 + it * 95).toInt()}%" }) { f ->
                    Ajustes.definirBrilho((5 + f * 95).toInt()); aoMudarBrilho()
                }))
                val tempos = listOf("30 s" to 30, "1 min" to 60, "5 min" to 300, "Nunca" to 0)
                opcoes.addView(linha("Modo ocioso", "Tela preta só com a hora, depois de um tempo sem uso",
                    escolhas(tempos.map { it.first }, tempos.indexOfFirst { it.second == Ajustes.ociosoSeg }.let { if (it < 0) 1 else it }) { i -> Ajustes.definirOcioso(tempos[i].second); montar() }))
                opcoes.addView(linha("Escurecer no modo ocioso", "Baixa o brilho da tela quando fica ociosa",
                    interruptor(Ajustes.escurecerOcioso) { Ajustes.definirEscurecer(it) }))
            }
            "som" -> {
                opcoes.addView(linha("Volume", "O mesmo da barra de cima", deslizador(lerVolume() / 10f, { "${Math.round(it * 10)}" }) { f -> definirVolume(Math.round(f * 10)) }))
                opcoes.addView(linha("Sons de resposta", "Toquinhos de quando a caixa começa e termina de ouvir",
                    interruptor(Ajustes.sonsResposta) { Ajustes.definirSons(it) }))
                opcoes.addView(linha("Volume do alarme", "O mínimo que o alarme garante ao tocar",
                    escolhas(listOf("Suave", "Médio", "Alto"), Ajustes.volumeAlarme) { i -> Ajustes.definirVolumeAlarme(i); montar() }))
            }
            "assistente" -> {
                opcoes.addView(linha("Mostrar resposta na tela", "A resposta escrita no alto da tela do assistente",
                    interruptor(Ajustes.mostrarResposta) { Ajustes.definirMostrarResposta(it) }))
                opcoes.addView(linha("Sensibilidade do microfone", "Quanto mais alto, de mais longe ele ouve a palavra de ativação",
                    deslizador((Ajustes.sensMic - 1) / 9f, { "${1 + Math.round(it * 9)}" }) { f -> Ajustes.definirSensMic(1 + Math.round(f * 9)) }))
            }
            "conexoes" -> {
                opcoes.addView(linha("Wi-Fi", null, LinearLayout(c).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(estadoTexto(estados["wifi"] ?: ""), LinearLayout.LayoutParams(300.d(), -2))
                    addView(seta(), LinearLayout.LayoutParams(28.d(), 28.d()).apply { leftMargin = 10.d() })
                }) { aoAbrir("wifi") })
                opcoes.addView(linha("Bluetooth", null, LinearLayout(c).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(estadoTexto(estados["bluetooth"] ?: ""), LinearLayout.LayoutParams(300.d(), -2))
                    addView(seta(), LinearLayout.LayoutParams(28.d(), 28.d()).apply { leftMargin = 10.d() })
                }) { aoAbrir("bluetooth") })
                opcoes.addView(linha("Fita de LED", null, LinearLayout(c).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(estadoTexto(estados["led"] ?: ""), LinearLayout.LayoutParams(300.d(), -2))
                    addView(seta(), LinearLayout.LayoutParams(28.d(), 28.d()).apply { leftMargin = 10.d() })
                }) { aoAbrir("led") })
            }
            else -> {
                opcoes.addView(linha("Nome do aparelho", "Como a caixa aparece no Bluetooth", estadoTexto("LM Sound", Tema.TEXTO)))
                opcoes.addView(linha("Versão", versao.ifBlank { "—" }, botao(c, "Verificar atualização", Tema.BOTAO) { aoVerificarAtualizacao() }))
                opcoes.addView(linha("Energia", "Desligar ou reiniciar a caixa", botao(c, "Energia", Tema.BOTAO) { aoEnergia() }))
            }
        }
    }

    /** Estado de cada item de Conexões ("Conectado a ...", "Desligado"...). */
    fun definirEstado(id: String, texto: String) {
        if (estados[id] == texto) return
        estados[id] = texto
        if (atual == "conexoes") montar()
    }

    /** Quando a tela abre: refaz tudo (o volume, por exemplo, pode ter mudado pela barra). */
    fun atualizar() = montar()
}
