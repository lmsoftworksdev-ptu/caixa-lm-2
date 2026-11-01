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
    /** Procura a cidade (em segundo plano) e devolve (nome, latitude, longitude) ou null. */
    var buscarCidade: (String, (Triple<String, Double, Double>?) -> Unit) -> Unit = { _, volta -> volta(null) }
    var aoMudarCidade: () -> Unit = {}
    var aoMudarNome: () -> Unit = {}
    var aoRestaurar: () -> Unit = {}
    /** Pede as últimas linhas do registro do app (em segundo plano) e entrega o texto. */
    var lerRegistro: ((String) -> Unit) -> Unit = { it("") }
    /** Salva o texto num arquivo e devolve onde ficou. */
    var salvarRegistro: (String) -> String = { "" }

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
    private val telaTexto = TelaTexto(c)
    private val modal = ModalV3(c)
    private lateinit var raizView: View

    val emTexto: Boolean get() = telaTexto.aberto || modal.aberto

    /** Voltar (borda ou tecla): fecha a janela ou a digitação, se houver. */
    fun voltar(): Boolean {
        if (modal.aberto) { modal.fechar(); return true }
        if (telaTexto.aberto) { telaTexto.fechar(); return true }
        return false
    }

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
        raizView = raiz
        addView(raiz, LayoutParams(-1, -1))
        telaTexto.aoFechar = { raizView.visibility = View.VISIBLE }
        addView(telaTexto, LayoutParams(-1, -1))
        addView(modal, LayoutParams(-1, -1))
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
                opcoes.addView(linha("Cidade do clima", if (Ajustes.cidadeFixa) "Fixa em ${Ajustes.cidadeNome}" else "Automática, pela internet", LinearLayout(c).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    if (Ajustes.cidadeFixa) addView(botao(c, "Automática", Tema.BOTAO) { Ajustes.usarCidadeAutomatica(); aoMudarCidade(); montar() })
                    addView(botao(c, "Mudar", Cfg.AZUL) { pedirCidade() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 10.d() })
                }))
            }
            "som" -> {
                opcoes.addView(linha("Volume", "O mesmo da barra de cima", deslizador(lerVolume() / 10f, { "${Math.round(it * 10)}" }) { f -> definirVolume(Math.round(f * 10)) }))
                opcoes.addView(linha("Sons de resposta", "Toquinhos de quando a caixa começa e termina de ouvir",
                    interruptor(Ajustes.sonsResposta) { Ajustes.definirSons(it) }))
                opcoes.addView(linha("Aviso de volume máximo", "O \"plim\" ao apertar + no máximo (a música abaixa enquanto toca)",
                    interruptor(Ajustes.avisoVolumeMax) { Ajustes.definirAvisoVolumeMax(it) }))
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
                opcoes.addView(linha("Nome do aparelho", "Como a caixa aparece no Bluetooth", LinearLayout(c).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(estadoTexto(Ajustes.nomeAparelho, Tema.TEXTO), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 14.d() })
                    addView(botao(c, "Mudar", Tema.BOTAO) { pedirNome() })
                }))
                opcoes.addView(linha("Versão", versao.ifBlank { "—" }, botao(c, "Verificar atualização", Tema.BOTAO) { aoVerificarAtualizacao() }))
                opcoes.addView(linha("Registro do app", "As últimas linhas do diagnóstico, para achar problemas", botao(c, "Ver registro", Tema.BOTAO) { abrirRegistro() }))
                opcoes.addView(linha("Restaurar ajustes", "Volta brilho, ocioso, sons, cidade e nome ao padrão", botao(c, "Restaurar", Tema.BOTAO, Tema.VERMELHO_SUAVE) { confirmarRestaurar() }))
                opcoes.addView(linha("Energia", "Desligar ou reiniciar a caixa", botao(c, "Energia", Tema.BOTAO) { aoEnergia() }))
            }
        }
    }

    // ------------------------------------------------------------------ digitação e janelas

    private fun pedirTexto(iconeRes: Int, dica: String, acao: String, inicial: String, aviso: String, maximo: Int, aoSalvar: (String) -> Unit) {
        raizView.visibility = View.INVISIBLE
        telaTexto.abrir(iconeRes = iconeRes, titulo = dica, dica = dica, acao = acao, inicial = inicial, maximo = maximo,
            validar = { t -> if (t.isBlank()) aviso else null }) { t -> raizView.visibility = View.VISIBLE; aoSalvar(t.trim()) }
    }

    private fun pedirCidade() = pedirTexto(R.drawable.ic_cloud, "Nome da cidade (ex.: Paracatu)", "Buscar", "", "Escreva o nome da cidade.", 60) { nome ->
        Toast.makeText(c, "Procurando…", Toast.LENGTH_SHORT).show()
        buscarCidade(nome) { r ->
            if (r == null) Toast.makeText(c, "Não achei essa cidade. Confira o nome.", Toast.LENGTH_LONG).show()
            else { Ajustes.definirCidade(r.first, r.second, r.third); aoMudarCidade(); montar() }
        }
    }

    private fun pedirNome() = pedirTexto(R.drawable.ic_caixa, "Nome da caixa", "Salvar", Ajustes.nomeAparelho, "Escreva um nome.", 24) { nome ->
        Ajustes.definirNome(nome); aoMudarNome(); montar()
    }

    private fun confirmarRestaurar() {
        val conteudo = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        conteudo.addView(texto(c, "Restaurar ajustes?", 30, Tema.TEXTO, true))
        conteudo.addView(texto(c, "Brilho, modo ocioso, sons, voz, cidade do clima e nome da caixa voltam ao padrão. Alarmes, lembretes e listas não mudam.", 20, Tema.TEXTO2).apply { setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.d(); bottomMargin = 24.d() })
        val bt = LinearLayout(c).apply { gravity = Gravity.END }
        bt.addView(botao(c, "Cancelar", Tema.BOTAO) { modal.fechar() })
        bt.addView(botao(c, "Restaurar", Cfg.AZUL) { modal.fechar(); Ajustes.restaurar(); aoRestaurar(); montar() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
        conteudo.addView(bt, LinearLayout.LayoutParams(-1, -2))
        modal.mostrar(620, conteudo)
    }

    private fun abrirRegistro() {
        val corpo = texto(c, "Lendo o registro…", 14, Tema.TEXTO2).apply { typeface = android.graphics.Typeface.MONOSPACE; setLineSpacing(0f, 1.1f) }
        val conteudo = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        conteudo.addView(texto(c, "Registro do app", 28, Tema.TEXTO, true))
        conteudo.addView(RolagemFina(c).apply { addView(corpo) }, LinearLayout.LayoutParams(-1, 380.d()).apply { topMargin = 12.d(); bottomMargin = 18.d() })
        val bt = LinearLayout(c).apply { gravity = Gravity.END }
        var registro = ""
        bt.addView(botao(c, "Salvar arquivo", Tema.BOTAO) {
            val onde = salvarRegistro(registro)
            Toast.makeText(c, if (onde.isEmpty()) "Não consegui salvar." else "Salvo em $onde", Toast.LENGTH_LONG).show()
        })
        bt.addView(botao(c, "Fechar", Cfg.AZUL) { modal.fechar() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
        conteudo.addView(bt, LinearLayout.LayoutParams(-1, -2))
        modal.mostrar(1040, conteudo)
        lerRegistro { t -> registro = t; corpo.text = if (t.isBlank()) "Nada no registro ainda." else t }
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
