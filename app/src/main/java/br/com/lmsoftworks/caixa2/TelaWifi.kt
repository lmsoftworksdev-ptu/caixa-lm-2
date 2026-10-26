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

    // Teclado (tela cheia)
    private val camada = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val tituloSenha = texto(c, "", 28, Cfg.MUDO)
    private val campoSenha = TextView(c)
    private val avisoSenha = texto(c, "", 18, Cfg.VERMELHO)
    private val botaoOlho = android.widget.ImageView(c)
    private val teclado = TecladoView(c)

    // Detalhes de uma rede (janela no meio)
    private val detalhe = FrameLayout(c)

    private var redeSenha: WifiControle.Rede? = null
    private var senha = ""
    private var mostrar = false
    private var assinatura = ""
    private var detalheEstado = ""

    val emSenha: Boolean get() = camada.visibility == View.VISIBLE
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

        // ----- Camada da senha -----
        camada.setPadding(48.d(), 24.d(), 48.d(), 28.d())
        val linhaTopo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(tituloSenha, LinearLayout.LayoutParams(0, -2, 1f))
        }
        camada.addView(linhaTopo, LinearLayout.LayoutParams(-1, -2))

        campoSenha.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 28.d().toFloat())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.d(), 0, 20.d(), 0)
            background = fundoArredondado(Color.parseColor("#050D22"), 14.d(), Cfg.LINHA)
        }
        botaoOlho.apply {
            setPadding(16.d(), 16.d(), 16.d(), 16.d())
            setColorFilter(Cfg.TEXTO)
            background = fundoArredondado(Color.parseColor("#14FFFFFF"), 14.d(), Cfg.LINHA)
            isClickable = true
            setOnClickListener { mostrar = !mostrar; atualizarCampo() }
            efeitoToque()
        }
        val linhaCampo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(campoSenha, LinearLayout.LayoutParams(0, 62.d(), 1f))
            addView(botaoOlho, LinearLayout.LayoutParams(62.d(), 62.d()).apply { leftMargin = 12.d() })
            addView(botao(c, "Conectar", Cfg.AZUL) { enviarSenha() },
                LinearLayout.LayoutParams(-2, 62.d()).apply { leftMargin = 12.d() })
        }
        camada.addView(linhaCampo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14.d() })
        camada.addView(avisoSenha, LinearLayout.LayoutParams(-1, 28.d()).apply { topMargin = 4.d() })
        camada.addView(teclado, LinearLayout.LayoutParams(-1, 0, 1f))
        camada.visibility = View.GONE
        addView(camada, LayoutParams(-1, -1))

        teclado.aoDigitar = { s -> if (senha.length < 63) { senha += s; avisoSenha.text = ""; atualizarCampo() } }
        teclado.aoApagar = { if (senha.isNotEmpty()) { senha = senha.dropLast(1); atualizarCampo() } }

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
        senha = ""
        mostrar = false
        avisoSenha.text = ""
        tituloSenha.text = "Senha de ${r.ssid}"
        atualizarCampo()
        principal.visibility = View.INVISIBLE
        camada.visibility = View.VISIBLE
    }

    fun cancelarSenha() {
        camada.visibility = View.GONE
        principal.visibility = View.VISIBLE
        redeSenha = null
        senha = ""
        atualizar(true)
    }

    private fun enviarSenha() {
        val r = redeSenha ?: return
        if (r.seguranca != "WEP" && senha.length < 8) {
            avisoSenha.text = "A senha precisa ter pelo menos 8 caracteres"
            return
        }
        wifi.conectar(r, senha)
        camada.visibility = View.GONE
        principal.visibility = View.VISIBLE
        redeSenha = null
        senha = ""
        atualizar(true)
    }

    private fun atualizarCampo() {
        if (senha.isEmpty()) {
            campoSenha.setTextColor(Cfg.MUDO)
            campoSenha.letterSpacing = 0f
            campoSenha.text = "Digite a senha"
        } else {
            campoSenha.setTextColor(Cfg.TEXTO)
            campoSenha.letterSpacing = if (mostrar) 0f else 0.15f
            campoSenha.text = if (mostrar) senha else "•".repeat(senha.length)
        }
        botaoOlho.setImageResource(if (mostrar) R.drawable.ic_olho_fechado else R.drawable.ic_olho)
    }

    // ---------------------------------------------------------------- lista

    fun reiniciar() {
        if (emSenha) { camada.visibility = View.GONE; principal.visibility = View.VISIBLE }
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
