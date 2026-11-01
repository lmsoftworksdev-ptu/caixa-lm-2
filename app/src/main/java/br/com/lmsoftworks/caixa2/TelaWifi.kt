package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Tela Wi-Fi em uma coluna só: toque em uma rede. Rede com senha abre o teclado em tela
 * cheia; rede aberta ou já salva conecta direto; rede salva ou conectada abre os detalhes.
 */
class TelaWifi(private val c: Context, private val wifi: WifiControle) : FrameLayout(c) {

    private val interruptor = Interruptor(c)
    private val lista = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val aviso = texto(c, "", 20, Cfg.VERMELHO)
    private val principal = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    // Senha (tela de texto em tela cheia)
    private val telaTexto = TelaTexto(c)

    // Detalhes de uma rede (janela no meio)
    private val detalhe = FrameLayout(c)

    private var redeSenha: WifiControle.Rede? = null
    private var assinatura = ""
    private var detalheEstado = ""

    val emSenha: Boolean get() = telaTexto.aberto
    val emDetalhe: Boolean get() = detalhe.visibility == View.VISIBLE

    init {
        // ----- Principal -----
        principal.setPadding(48.d(), 24.d(), 48.d(), 36.d())
        val cartao = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26.d(), 24.d(), 26.d(), 14.d())
            background = fundoArredondado(Cfg.PAINEL, 24.d(), Cfg.LINHA)
        }
        val topo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(texto(c, "Wi-Fi", 42, Cfg.TEXTO, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(interruptor)
        }
        cartao.addView(topo, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 22.d() })
        cartao.addView(aviso, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 6.d() })
        val rolagem = ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(lista) }
        cartao.addView(rolagem, LinearLayout.LayoutParams(-1, 0, 1f))
        interruptor.aoMudar = { v -> wifi.definirLigado(v) }
        aviso.visibility = View.GONE

        principal.addView(cartao, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(principal, LayoutParams(-1, -1))

        telaTexto.aoFechar = { cancelarSenha() }
        addView(telaTexto, LayoutParams(-1, -1))

        // ----- Janela de detalhes -----
        detalhe.setBackgroundColor(Color.parseColor("#99000000"))
        detalhe.isClickable = true
        detalhe.visibility = View.GONE
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

    // ---------------------------------------------------------------- lista

    fun reiniciar() {
        if (emSenha) { telaTexto.fechar(); principal.visibility = View.VISIBLE }
        fecharDetalhe()
        wifi.limparErro()
        atualizar(true)
    }

    fun atualizar(forcar: Boolean = false) {
        val ass = buildString {
            append(wifi.ligado).append('|').append(wifi.conectadoSsid).append('|').append(wifi.conectando)
            append('|').append(wifi.erro).append('|').append(wifi.erroSsid)
            wifi.redes.forEach { append('|').append(it.ssid).append(it.nivel) }
        }
        if (!forcar && ass == assinatura) return
        assinatura = ass
        interruptor.ligado = wifi.ligado
        val msg = wifi.erro
        if (msg != null) {
            aviso.text = "${wifi.erroSsid ?: "Rede"}: $msg"
            aviso.visibility = View.VISIBLE
        } else {
            aviso.visibility = View.GONE
        }
        montarLista()
        // a janela de detalhes só fecha se a conexão mudar enquanto ela está aberta
        if (emDetalhe && detalheEstado != estadoConexao()) fecharDetalhe()
    }

    private fun vazio(s: String) =
        texto(c, s, 22, Cfg.MUDO).apply { setPadding(16.d(), 30.d(), 16.d(), 30.d()) }

    private fun montarLista() {
        lista.removeAllViews()
        if (!wifi.ligado) { lista.addView(vazio("Wi-Fi desligado")); return }
        if (wifi.redes.isEmpty()) { lista.addView(vazio("Procurando redes…")); return }
        // a rede conectada fica no topo
        val ordenadas = wifi.redes.sortedByDescending { it.ssid == wifi.conectadoSsid }
        for (r in ordenadas) {
            val conectada = r.ssid == wifi.conectadoSsid
            val tentando = r.ssid == wifi.conectando
            val subtitulo = when {
                conectada -> "Conectado"
                tentando -> "Conectando…"
                !r.suportada -> "Corporativa (não suportada)"
                wifi.salva(r.ssid) -> "Salva"
                r.protegida -> "Protegida (${r.seguranca})"
                else -> "Aberta"
            }
            val cor = when { conectada -> Cfg.OK; tentando -> Cfg.AZUL_CLARO; else -> Cfg.MUDO }
            val nome = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(texto(c, r.ssid, 26, Cfg.TEXTO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(texto(c, subtitulo, 18, cor), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4.d() })
            }
            val linha = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18.d(), 14.d(), 18.d(), 14.d())
                if (conectada) background = fundoArredondado(Color.parseColor("#262F7BFF"), 16.d())
                isClickable = true
                setOnClickListener { escolher(r) }
                addView(nome, LinearLayout.LayoutParams(0, -2, 1f))
                if (r.protegida) addView(icone(c, R.drawable.ic_lock, 24, Cfg.MUDO),
                    LinearLayout.LayoutParams(24.d(), 24.d()).apply { rightMargin = 18.d() })
                addView(BarrasSinal(c).also { it.nivel = r.nivel })
            }
            lista.addView(linha, LinearLayout.LayoutParams(-1, -2))
            lista.addView(View(c).apply { setBackgroundColor(Cfg.LINHA) }, LinearLayout.LayoutParams(-1, 1.d().coerceAtLeast(1)))
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

    private fun fecharDetalhe() {
        detalhe.visibility = View.GONE
        detalhe.removeAllViews()
    }

    private fun mostrarDetalhe(r: WifiControle.Rede) {
        detalhe.removeAllViews()
        val conectada = r.ssid == wifi.conectadoSsid
        val caixa = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32.d(), 28.d(), 32.d(), 28.d())
            background = fundoArredondado(Color.parseColor("#FF0D2048"), 26.d(), Cfg.LINHA)
        }
        caixa.addView(texto(c, r.ssid, 30, Cfg.TEXTO, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        caixa.addView(texto(c, if (conectada) "Conectado" else "Rede salva", 22, if (conectada) Cfg.OK else Cfg.MUDO),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4.d(); bottomMargin = 18.d() })
        if (conectada) {
            listOf("Endereço IP: ${wifi.ip()}", "Sinal: ${wifi.nivelAtual()}/4", "Segurança: ${r.seguranca}").forEach {
                caixa.addView(texto(c, it, 22, Cfg.MUDO), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 8.d() })
            }
        }
        val botoes = LinearLayout(c).apply { gravity = Gravity.END }
        botoes.addView(botao(c, "Fechar", Cfg.LINHA) { fecharDetalhe() })
        if (!conectada) botoes.addView(botao(c, "Conectar", Cfg.AZUL) { fecharDetalhe(); wifi.conectar(r, null) },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
        botoes.addView(botao(c, "Esquecer rede", Cfg.VERMELHO) { fecharDetalhe(); wifi.esquecer(r.ssid) },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
        caixa.addView(botoes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20.d() })
        detalhe.addView(caixa, LayoutParams(640.d(), -2, Gravity.CENTER))
        detalheEstado = estadoConexao()
        detalhe.visibility = View.VISIBLE
    }

    /** Volta uma camada (detalhes, teclado). Devolve true se tratou o botão Voltar. */
    fun voltar(): Boolean {
        if (emDetalhe) { fecharDetalhe(); return true }
        if (emSenha) { cancelarSenha(); return true }
        return false
    }
}
