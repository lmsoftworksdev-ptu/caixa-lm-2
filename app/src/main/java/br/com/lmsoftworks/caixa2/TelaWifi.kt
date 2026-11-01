package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Tela Wi-Fi da v3, em duas colunas: à esquerda o título com o interruptor e o cartão de estado; à direita a lista de redes
 * (REDE ATUAL e REDES DISPONÍVEIS). Rede protegida nova abre a tela de senha; rede salva ou conectada abre a janela de detalhes.
 */
class TelaWifi(private val c: Context, private val wifi: WifiControle) : FrameLayout(c) {

    private val interruptor = Interruptor(c)
    private val hero = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val lista = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val principal = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }

    private val telaTexto = TelaTexto(c)
    private val detalhe = ModalV3(c)

    private var redeSenha: WifiControle.Rede? = null
    private var assinatura = ""
    private var detalheEstado = ""

    val emSenha: Boolean get() = telaTexto.aberto
    val emDetalhe: Boolean get() = detalhe.aberto

    init {
        principal.setPadding(Tema.MARGEM_LATERAL.d(), 40.d(), Tema.MARGEM_LATERAL.d(), Tema.MARGEM_BASE.d())

        // ----- coluna da esquerda -----
        val esquerda = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val topo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(texto(c, "Wi-Fi", 44, Tema.TEXTO, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(interruptor)
        }
        esquerda.addView(topo, LinearLayout.LayoutParams(-1, 64.d()))
        hero.apply { background = cartaoVidro(); setPadding(30.d(), 30.d(), 30.d(), 30.d()) }
        esquerda.addView(hero, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 20.d() })
        principal.addView(esquerda, LinearLayout.LayoutParams(404.d(), -1))

        // ----- coluna da direita -----
        val rolagem = ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(lista) }
        val cartaoLista = FrameLayout(c).apply {
            background = cartaoVidro()
            setPadding(12.d(), 12.d(), 12.d(), 12.d())
            addView(rolagem, LayoutParams(-1, -1))
        }
        principal.addView(cartaoLista, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = 24.d() })
        addView(principal, LayoutParams(-1, -1))

        interruptor.aoMudar = { v -> wifi.definirLigado(v) }

        telaTexto.aoFechar = { cancelarSenha() }
        addView(telaTexto, LayoutParams(-1, -1))
        addView(detalhe, LayoutParams(-1, -1))

        atualizar(true)
    }

    // ---------------------------------------------------------------- senha

    private fun abrirSenha(r: WifiControle.Rede) {
        redeSenha = r
        principal.visibility = View.INVISIBLE
        telaTexto.abrir(
            iconeRes = R.drawable.ic_lock, titulo = "Senha para", destaque = r.ssid, dica = "Digite a senha", acao = "Conectar",
            senha = true, maximo = 63,
            validar = { t -> if (r.seguranca != "WEP" && t.length < 8) "A senha precisa ter pelo menos 8 caracteres." else null }
        ) { t ->
            wifi.conectar(r, t)
            principal.visibility = View.VISIBLE
            redeSenha = null
            atualizar(true)
        }
    }

    fun cancelarSenha() {
        telaTexto.fechar()
        principal.visibility = View.VISIBLE
        redeSenha = null
        atualizar(true)
    }

    // ---------------------------------------------------------------- estado

    fun reiniciar() {
        if (emSenha) { telaTexto.fechar(); principal.visibility = View.VISIBLE }
        fecharDetalhe()
        wifi.limparErro()
        atualizar(true)
    }

    fun atualizar(forcar: Boolean = false) {
        val ass = buildString {
            append(wifi.ligado).append('|').append(wifi.conectadoSsid).append('|').append(wifi.conectando)
            append('|').append(wifi.erro).append('|').append(wifi.erroSsid).append('|').append(Cfg.AZUL)
            wifi.redes.forEach { append('|').append(it.ssid).append(it.nivel) }
        }
        if (!forcar && ass == assinatura) return
        assinatura = ass
        interruptor.ligado = wifi.ligado
        montarHero()
        montarLista()
        // a janela de detalhes só fecha se a conexão mudar enquanto ela está aberta
        if (emDetalhe && detalheEstado != estadoConexao()) fecharDetalhe()
    }

    private val qualidade = arrayOf("Fraco", "Fraco", "Bom", "Muito bom", "Excelente")

    private fun montarHero() {
        hero.removeAllViews()
        val ssid = wifi.conectadoSsid
        fun cartaoTexto(s: String, tam: Int, cor: Int, media: Boolean = false) =
            texto(c, s, tam, cor, media).apply { setLineSpacing(0f, 1.15f) }
        val gap = LinearLayout.LayoutParams(-2, -2).apply { topMargin = 12.d() }
        if (!wifi.ligado) {
            hero.addView(AnelIcone(c, R.drawable.ic_wifi_off, 96, Tema.TEXTO3, Color.parseColor("#12FFFFFF"), 0))
            hero.addView(cartaoTexto("Desligado", 20, Tema.TEXTO3, true), gap)
            hero.addView(cartaoTexto("Ligue o Wi-Fi para ouvir músicas, vídeos e usar o assistente.", 18, Tema.TEXTO2), gap)
        } else if (ssid != null) {
            hero.addView(AnelIcone(c, R.drawable.ic_wifi, 96, Cfg.AZUL, Color.argb(41, Color.red(Cfg.AZUL), Color.green(Cfg.AZUL), Color.blue(Cfg.AZUL)),
                Color.argb(15, Color.red(Cfg.AZUL), Color.green(Cfg.AZUL), Color.blue(Cfg.AZUL))))
            hero.addView(cartaoTexto("● Conectado", 20, Tema.OK, true), gap)
            hero.addView(cartaoTexto(ssid, 32, Tema.TEXTO, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, gap)
            hero.addView(cartaoTexto("Sinal ${qualidade[wifi.nivelAtual().coerceIn(0, 4)].lowercase()} · IP ${wifi.ip()}", 18, Tema.TEXTO2), gap)
        } else {
            hero.addView(AnelIcone(c, R.drawable.ic_wifi_off, 96, Tema.VERMELHO_SUAVE, Color.parseColor("#24FF4D5E"), 0))
            hero.addView(cartaoTexto("Sem conexão", 20, Tema.VERMELHO_SUAVE, true), gap)
            hero.addView(cartaoTexto("Escolha uma rede ao lado para conectar.", 18, Tema.TEXTO2), gap)
        }
        val msg = wifi.erro
        if (msg != null) hero.addView(cartaoTexto("${wifi.erroSsid ?: "Rede"}: $msg", 18, Tema.VERMELHO_SUAVE), gap)
    }

    private fun vazio(s: String): View = FrameLayout(c).apply {
        addView(texto(c, s, 22, Tema.TEXTO3), LayoutParams(-2, -2, Gravity.CENTER))
        minimumHeight = 400.d()
    }

    private fun montarLista() {
        lista.removeAllViews()
        if (!wifi.ligado) { lista.addView(vazio("Wi-Fi desligado"), LinearLayout.LayoutParams(-1, 480.d())); return }
        if (wifi.redes.isEmpty()) { lista.addView(vazio("Procurando redes…"), LinearLayout.LayoutParams(-1, 480.d())); return }
        // a rede conectada fica no topo
        val ordenadas = wifi.redes.sortedByDescending { it.ssid == wifi.conectadoSsid }
        for ((i, r) in ordenadas.withIndex()) {
            val conectada = r.ssid == wifi.conectadoSsid
            if (i == 0 && conectada) lista.addView(rotuloSecao(c, "Rede atual"))
            if ((i == 0 && !conectada) || (i == 1 && ordenadas[0].ssid == wifi.conectadoSsid)) lista.addView(rotuloSecao(c, "Redes disponíveis"))
            val tentando = r.ssid == wifi.conectando
            val subtitulo = when {
                conectada -> "Conectado"
                tentando -> "Conectando…"
                !r.suportada -> "Corporativa (não suportada)"
                wifi.salva(r.ssid) -> "Salva"
                r.protegida -> "Protegida (${r.seguranca})"
                else -> "Aberta"
            }
            val cor = when { conectada -> Tema.OK; tentando -> Color.parseColor("#7FB4FF"); else -> Tema.TEXTO3 }
            val nome = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(texto(c, r.ssid, 25, Tema.TEXTO, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(texto(c, subtitulo, 17, cor), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 3.d() })
            }
            val linha = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18.d(), 0, 18.d(), 0)
                if (conectada) background = fundoArredondado(Color.argb(46, Color.red(Cfg.AZUL), Color.green(Cfg.AZUL), Color.blue(Cfg.AZUL)), 20.d())
                isClickable = true
                setOnClickListener { escolher(r) }
                addView(nome, LinearLayout.LayoutParams(0, -2, 1f))
                if (r.protegida) addView(icone(c, R.drawable.ic_lock, 22, Tema.TEXTO3), LinearLayout.LayoutParams(22.d(), 22.d()).apply { rightMargin = 14.d() })
                addView(BarrasSinal(c).also { it.nivel = r.nivel })
            }
            lista.addView(linha, LinearLayout.LayoutParams(-1, 86.d()))
        }
    }

    private fun escolher(r: WifiControle.Rede) {
        wifi.limparErro()
        val salva = wifi.salva(r.ssid)
        when {
            !r.suportada -> Toast.makeText(c, "Redes corporativas ainda não são suportadas", Toast.LENGTH_SHORT).show()
            r.ssid == wifi.conectando -> { }
            r.ssid == wifi.conectadoSsid || salva -> mostrarDetalhe(r)
            r.protegida -> abrirSenha(r)
            else -> wifi.conectar(r, null)
        }
        if (!emDetalhe) atualizar(true)
    }

    // ---------------------------------------------------------------- detalhes

    private fun estadoConexao() = "${wifi.conectadoSsid}|${wifi.conectando}"

    private fun fecharDetalhe() = detalhe.fechar()

    private fun mostrarDetalhe(r: WifiControle.Rede) {
        val conectada = r.ssid == wifi.conectadoSsid
        val corAnel = Cfg.AZUL
        val conteudo = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val cab = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(AnelIcone(c, R.drawable.ic_wifi, 72, corAnel, Color.argb(41, Color.red(corAnel), Color.green(corAnel), Color.blue(corAnel)), 0))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(texto(c, r.ssid, 32, Tema.TEXTO, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(texto(c, if (conectada) "Conectado" else "Salva", 19, if (conectada) Tema.OK else Tema.TEXTO3))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 6.d() })
        }
        conteudo.addView(cab)
        val grade = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        if (conectada) grade.addView(linhaDetalhe(c, "Endereço IP", wifi.ip()))
        grade.addView(linhaDetalhe(c, "Sinal", qualidade[(if (conectada) wifi.nivelAtual() else r.nivel).coerceIn(0, 4)]))
        grade.addView(linhaDetalhe(c, "Segurança", if (r.protegida) r.seguranca else "Aberta"))
        conteudo.addView(grade, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 22.d(); bottomMargin = 26.d() })
        val botoes = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        botoes.addView(botao(c, "Esquecer", Tema.BOTAO, Tema.VERMELHO_SUAVE) { fecharDetalhe(); wifi.esquecer(r.ssid) })
        botoes.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        botoes.addView(botao(c, "Fechar", Tema.BOTAO) { fecharDetalhe() })
        if (!conectada) botoes.addView(botao(c, "Conectar", Cfg.AZUL) { fecharDetalhe(); wifi.conectar(r, null) },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
        conteudo.addView(botoes, LinearLayout.LayoutParams(-1, -2))
        detalheEstado = estadoConexao()
        detalhe.mostrar(680, conteudo)
    }

    /** Volta uma camada (detalhes, senha). Devolve true se tratou o botão Voltar. */
    fun voltar(): Boolean {
        if (emDetalhe) { fecharDetalhe(); return true }
        if (emSenha) { cancelarSenha(); return true }
        return false
    }
}
