package br.com.lmsoftworks.caixa2

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL

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
    var aoDiagnostico: () -> Unit = {}
    var versao: String = ""
    /** Procura a cidade (em segundo plano) e devolve (nome, latitude, longitude) ou null. */
    var buscarCidade: (String, (Triple<String, Double, Double>?) -> Unit) -> Unit = { _, volta -> volta(null) }
    var aoMudarCidade: () -> Unit = {}
    /** O fuso horário mudou: o app refaz o que depende da hora (alarmes, lembretes, relógio). */
    var aoMudarFuso: () -> Unit = {}
    var buscarFusoAutomatico: () -> Unit = {}
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
        Secao("hora", "Data e hora", "Fuso horário", R.drawable.ic_alarme),
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
    private var testeInternetEmAndamento = false
    private var resultadoTesteInternet = "Toque em Medir para testar a internet."


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


    private fun abrirAjustesAndroid() {
        val itens = listOf(
            "Wi-Fi" to android.provider.Settings.ACTION_WIFI_SETTINGS,
            "Bluetooth" to android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
            "Localização" to android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "Aplicativos" to android.provider.Settings.ACTION_APPLICATION_SETTINGS,
            "Tela" to android.provider.Settings.ACTION_DISPLAY_SETTINGS,
            "Som" to android.provider.Settings.ACTION_SOUND_SETTINGS,
            "Data e hora" to android.provider.Settings.ACTION_DATE_SETTINGS,
            "Armazenamento" to android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "Segurança" to android.provider.Settings.ACTION_SECURITY_SETTINGS,
            "Acessibilidade" to android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
            "Sobre o aparelho" to android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS
        )
        val conteudo = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        conteudo.addView(texto(c, "Configurações do Android", 30, Tema.TEXTO, true))
        val opcoesSistema = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        for ((nome, acao) in itens) {
            opcoesSistema.addView(botao(c, nome, Tema.BOTAO) {
                try {
                    c.startActivity(Intent(acao).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    Toast.makeText(c, "Essa tela não está disponível.", Toast.LENGTH_LONG).show()
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.d() })
        }
        conteudo.addView(RolagemFina(c).apply { addView(opcoesSistema) }, LinearLayout.LayoutParams(-1, 500.d()).apply { topMargin = 12.d() })
        modal.mostrar(700, conteudo)
    }
    /** Mede o download real por uma transferência curta, sem bloquear a tela. */
    private fun iniciarTesteInternet() {
        testeInternetEmAndamento = true
        resultadoTesteInternet = "Baixando dados de teste (até 5 MB)…"
        montar()
        Thread {
            val resultado = try {
                val conexao = (URL("https://speed.cloudflare.com/__down?bytes=5000000").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                    useCaches = false
                    instanceFollowRedirects = true
                }
                try {
                    val codigo = conexao.responseCode
                    if (codigo !in 200..299) throw java.io.IOException("HTTP $codigo")
                    val inicio = SystemClock.elapsedRealtime()
                    var total = 0L
                    val buffer = ByteArray(32768)
                    conexao.inputStream.use { entrada ->
                        while (total < 5_000_000L) {
                            val lidos = entrada.read(buffer, 0, minOf(buffer.size.toLong(), 5_000_000L - total).toInt())
                            if (lidos < 0) break
                            total += lidos
                        }
                    }
                    val duracaoMs = (SystemClock.elapsedRealtime() - inicio).coerceAtLeast(1L)
                    if (total == 0L) throw java.io.IOException("Resposta vazia")
                    val mbps = total * 8.0 / duracaoMs / 1000.0
                    "Download: ${String.format(java.util.Locale("pt", "BR"), "%.1f", mbps)} Mbps"
                } finally {
                    conexao.disconnect()
                }
            } catch (_: Throwable) {
                "Falha no teste. Confira a conexão e tente novamente."
            }
            post {
                testeInternetEmAndamento = false
                resultadoTesteInternet = resultado
                if (atual == "sistema") montar()
            }
        }.start()
    }

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
                opcoes.addView(linha("Nivelar o áudio", "Músicas e vídeos no mesmo nível de volume",
                    interruptor(Ajustes.nivelarAudio) { Ajustes.definirNivelarAudio(it) }))
                opcoes.addView(linha("Sons do teclado", "Um toquinho a cada tecla apertada",
                    interruptor(Ajustes.sonsTeclado) { Ajustes.definirSonsTeclado(it) }))
                opcoes.addView(linha("Volume do alarme", "O mínimo que o alarme garante ao tocar",
                    escolhas(listOf("Suave", "Médio", "Alto"), Ajustes.volumeAlarme) { i -> Ajustes.definirVolumeAlarme(i); montar() }))
            }
            "assistente" -> {
                opcoes.addView(linha("Mostrar resposta na tela", "A resposta escrita no alto da tela do assistente",
                    interruptor(Ajustes.mostrarResposta) { Ajustes.definirMostrarResposta(it) }))
                opcoes.addView(linha("Volume da voz", "Da resposta, dos toquinhos e dos sons de ligar e desligar; não muda o volume da música",
                    deslizador((Ajustes.volumeVoz - 1) / 9f, { "${1 + Math.round(it * 9)}" }) { f ->
                        Ajustes.definirVolumeVoz(1 + Math.round(f * 9)); br.com.lmsoftworks.caixa2.voz.Fala.aplicarVolume(c)
                    }))
                opcoes.addView(linha("Sensibilidade do microfone", "Quanto mais alto, de mais longe ele ouve a palavra de ativação",
                    deslizador((Ajustes.sensMic - 1) / 9f, { "${1 + Math.round(it * 9)}" }) { f -> Ajustes.definirSensMic(1 + Math.round(f * 9)) }))
            }
            "hora" -> {
                val agora = java.util.Calendar.getInstance()
                val formato = java.text.SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy  ·  HH:mm", java.util.Locale("pt", "BR")).apply { timeZone = java.util.TimeZone.getDefault() }
                opcoes.addView(linha("Agora", "Hora usada pela caixa", texto(c, formato.format(agora.time).replaceFirstChar { it.uppercase() }, 21, Tema.TEXTO2)))
                opcoes.addView(linha("Fuso horário automático",
                    if (Ajustes.fusoAuto) "Pela internet: ${FusoHorario.nome(FusoHorario.minutos())}" else "Desligado: você escolhe o fuso abaixo",
                    interruptor(Ajustes.fusoAuto) { ligado ->
                        Ajustes.definirFusoAuto(ligado); FusoHorario.aplicar()
                        if (ligado) buscarFusoAutomatico()
                        aoMudarFuso(); montar()
                    }))
                if (!Ajustes.fusoAuto) {
                    fun mudar(passo: Int) { Ajustes.definirFusoManual(Ajustes.fusoManualMin + passo); FusoHorario.aplicar(); aoMudarFuso(); montar() }
                    opcoes.addView(linha("Fuso horário", "Diferença para a hora de Greenwich (Brasília é UTC−3)", LinearLayout(c).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(FrameLayout(c).apply { background = fundoArredondado(Tema.BOTAO, Tema.RAIO_BOTAO.d(), Tema.BOTAO_BORDA); isClickable = true; setOnClickListener { mudar(-30) }; efeitoToque(); addView(icone(c, R.drawable.ic_menos, 26, Color.WHITE), FrameLayout.LayoutParams(26.d(), 26.d(), Gravity.CENTER)) }, LinearLayout.LayoutParams(60.d(), 60.d()))
                        addView(texto(c, FusoHorario.nome(Ajustes.fusoManualMin), 23, Tema.TEXTO, true).apply { gravity = Gravity.CENTER },
                            LinearLayout.LayoutParams(130.d(), -2))
                        addView(FrameLayout(c).apply { background = fundoArredondado(Tema.BOTAO, Tema.RAIO_BOTAO.d(), Tema.BOTAO_BORDA); isClickable = true; setOnClickListener { mudar(30) }; efeitoToque(); addView(icone(c, R.drawable.ic_mais, 26, Color.WHITE), FrameLayout.LayoutParams(26.d(), 26.d(), Gravity.CENTER)) }, LinearLayout.LayoutParams(60.d(), 60.d()))
                    }))
                    opcoes.addView(linha("Brasília", "UTC−3, sem horário de verão", botao(c, "Usar", Cfg.AZUL) {
                        Ajustes.definirFusoManual(-180); FusoHorario.aplicar(); aoMudarFuso(); montar()
                    }))
                }
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
                opcoes.addView(linha("Configurações do Android", "Wi-Fi, Bluetooth, localização e opções do sistema", botao(c, "Abrir", Tema.BOTAO) { abrirAjustesAndroid() }))
                opcoes.addView(linha("Nome do aparelho", "Como a caixa aparece no Bluetooth", LinearLayout(c).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(estadoTexto(Ajustes.nomeAparelho, Tema.TEXTO), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 14.d() })
                    addView(botao(c, "Mudar", Tema.BOTAO) { pedirNome() })
                }))
                opcoes.addView(linha("Versão", versao.ifBlank { "—" }, botao(c, "Verificar atualização", Tema.BOTAO) { aoVerificarAtualizacao() }))
                opcoes.addView(linha("Velocidade da internet", resultadoTesteInternet,
                    botao(c, if (testeInternetEmAndamento) "Medindo…" else "Medir", Cfg.AZUL) {
                        if (!testeInternetEmAndamento) iniciarTesteInternet()
                    }))
                opcoes.addView(linha("Teste de conexões", "USB (OTG), Wi-Fi, Bluetooth e redes em tempo real", botao(c, "Abrir", Tema.BOTAO) { aoDiagnostico() }))
                opcoes.addView(linha("Registro do app", "As últimas linhas do diagnóstico, para achar problemas", botao(c, "Ver registro", Tema.BOTAO) { abrirRegistro() }))
                opcoes.addView(linha("Limpar cache", "Apaga miniaturas guardadas, arquivos temporários e registros antigos", botao(c, "Limpar", Tema.BOTAO) {
                    Aviso.mostrar(c, "Limpando…")
                    Thread {
                        val livre = try { Limpeza.executar(c) } catch (e: Throwable) { -1L }
                        post { Aviso.mostrar(c, if (livre < 0) "Não consegui limpar tudo." else if (livre == 0L) "Já estava tudo limpo." else "Limpeza concluída: " + Limpeza.texto(livre) + " liberados") }
                    }.start()
                }))
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
        Aviso.mostrar(c, "Procurando…")
        buscarCidade(nome) { r ->
            if (r == null) Aviso.mostrar(c, "Não achei essa cidade. Confira o nome.")
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
            Aviso.mostrar(c, if (onde.isEmpty()) "Não consegui salvar." else "Salvo em $onde")
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
