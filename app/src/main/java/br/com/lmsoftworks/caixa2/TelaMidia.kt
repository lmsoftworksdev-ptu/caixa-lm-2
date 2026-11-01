package br.com.lmsoftworks.caixa2

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.URL
import java.util.concurrent.Executors

/** Baixa e guarda as miniaturas dos vídeos e capas das músicas (poucas ao mesmo tempo, para não pesar no G3). */
object Miniaturas {
    private val cache = LruCache<String, Bitmap>(48)
    private val fila = Executors.newFixedThreadPool(3)
    private val principal = Handler(Looper.getMainLooper())

    /** Tenta cada endereço da lista, do melhor para o pior, até um funcionar. */
    fun carregar(urls: List<String>, alvo: ImageView) {
        val url = urls.firstOrNull()
        alvo.tag = url
        alvo.setImageDrawable(null)
        if (url == null) return
        cache.get(url)?.let { alvo.setImageBitmap(it); return }
        fila.execute {
            try {
                var bytes: ByteArray? = null
                for (u in urls) {
                    try { bytes = URL(u).openConnection().apply { connectTimeout = 5000; readTimeout = 8000 }.getInputStream().use { it.readBytes() }; break } catch (e: Exception) { }
                }
                if (bytes == null) return@execute
                val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, limites)
                var amostra = 1
                while (limites.outWidth / (amostra * 2) >= 480) amostra *= 2
                val opcoes = BitmapFactory.Options().apply { inSampleSize = amostra; inPreferredConfig = Bitmap.Config.RGB_565 }
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opcoes) ?: return@execute
                cache.put(url, b)
                principal.post { if (alvo.tag == url) alvo.setImageBitmap(b) }
            } catch (e: Exception) { }
        }
    }
}

/**
 * Menu Mídia: barra de pesquisa e duas fileiras que rolam só para os lados (vídeos em cima, músicas embaixo). Sem pesquisa
 * mostra o que está em alta. A tela não rola para baixo. Tocar num vídeo ou numa música chama [aoVideo] e [aoMusica].
 */
class TelaMidia(
    private val c: Context,
    private val aoVideo: (List<Resultado>, Int) -> Unit,
    private val aoMusica: (List<Resultado>, Int) -> Unit,
    private val aoFalha: (Exception) -> Unit
) : FrameLayout(c) {

    companion object {
        private const val LIMITE = 12
        private const val LARGURA_VIDEO = 236
        private const val LADO_MUSICA = 128
        private const val VALIDADE_ALTA_MS = 30 * 60 * 1000L
    }

    private val principal = Handler(Looper.getMainLooper())
    private var consulta = ""
    private var seq = 0
    private var altaV: List<Resultado>? = null
    private var altaM: List<Resultado>? = null
    private var altaEm = 0L
    private var resV: List<Resultado>? = null
    private var resM: List<Resultado>? = null
    private var carregando = false
    private var erro: String? = null
    private var piscar: ValueAnimator? = null

    private val textoBusca = texto(c, "", 23, Tema.TEXTO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val dica = texto(c, "Pesquisar vídeos e músicas", 23, Tema.TEXTO3)
    private val botaoLimpar = FrameLayout(c)
    private val microfone = FrameLayout(c)
    /** O microfone que aparece em cima do teclado (centralizado acima do 5 e do 6) quando o teclado está aberto. */
    private val microfoneTeclado = FrameLayout(c)
    /** "Abrir Wi-Fi" do estado sem internet. */
    var aoAbrirWifi: (() -> Unit)? = null
    /** Quem trata o toque no microfone (liga a escuta do assistente). */
    var aoMicrofone: (() -> Unit)? = null
    private var pulsoMic: ValueAnimator? = null
    private val barra = FrameLayout(c)
    private val area = LinearLayout(c)
    private val teclado = FrameLayout(c)
    private val tecladoV3 = TecladoView(c)
    private val fileiras = ArrayList<HorizontalScrollView>()
    private var tecladoAberto = false

    init {
        // ---- barra de pesquisa ----
        barra.apply {
            background = fundoArredondado(Color.parseColor("#26FFFFFF"), 32.d(), Cfg.LINHA)
            isClickable = true
            setOnClickListener { abrirTeclado() }
            efeitoToque()
        }
        val lupa = icone(c, R.drawable.ic_busca, 28, Color.parseColor("#D6E0F7"))
        barra.addView(lupa, LayoutParams(28.d(), 28.d(), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 26.d() })
        barra.addView(dica, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 74.d() })
        barra.addView(textoBusca, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 74.d(); rightMargin = 70.d() })
        botaoLimpar.apply {
            background = fundoArredondado(Color.parseColor("#26FFFFFF"), 24.d())
            visibility = View.GONE
            isClickable = true
            setOnClickListener { consulta = ""; atualizarBarra(); buscar() }
            addView(icone(c, R.drawable.ic_fechar, 24, Color.WHITE), LayoutParams(24.d(), 24.d(), Gravity.CENTER))
        }
        barra.addView(botaoLimpar, LayoutParams(48.d(), 48.d(), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = 8.d() })
        microfone.apply {
            background = fundoArredondado(Color.parseColor("#33FFFFFF"), 24.d())
            isClickable = true
            setOnClickListener { aoMicrofone?.invoke() ?: avisoBusca("Busca por voz indisponível") }
            addView(icone(c, R.drawable.ic_mic, 28, Color.WHITE), LayoutParams(28.d(), 28.d(), Gravity.CENTER))
        }

        // ---- conteúdo ----
        area.orientation = LinearLayout.VERTICAL
        val coluna = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        coluna.addView(barra, LinearLayout.LayoutParams(-1, 64.d()).apply { leftMargin = 48.d(); rightMargin = 48.d() })
        coluna.addView(area, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 8.d() })
        addView(coluna, LayoutParams(-1, -1).apply { topMargin = 32.d(); bottomMargin = 6.d() })      // a barra de busca fica em y 96 (a tela começa em 64)

        montarTeclado()
        addView(teclado, LayoutParams(-1, (720 - 166).d(), Gravity.BOTTOM))
        teclado.visibility = View.GONE
        renderizar()
    }

    // ------------------------------------------------------------------ dados

    /** Chamada quando o menu aparece: carrega o "em alta" na primeira vez e de tempos em tempos. */
    fun aoMostrar() {
        val velho = System.currentTimeMillis() - altaEm > VALIDADE_ALTA_MS
        if (altaV == null || altaM == null || velho || erro != null) carregarAlta()
    }

    private fun temInternet(): Boolean {
        val n = (c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo
        return n != null && n.isConnected
    }

    private fun carregarAlta() {
        val minha = ++seq
        carregando = true; erro = null
        renderizar()
        var v: List<Resultado>? = null; var m: List<Resultado>? = null; var falha: Exception? = null
        val tv = Thread { try { v = YouTube.emAlta(false) } catch (e: Exception) { falha = e } }
        val tm = Thread { try { m = YouTube.emAlta(true) } catch (e: Exception) { falha = e } }
        Thread {
            tv.start(); tm.start(); tv.join(); tm.join()
            principal.post {
                if (minha != seq) return@post
                carregando = false
                if (v == null && m == null) { falhou(falha) }
                else { altaV = v.orEmpty().take(LIMITE); altaM = m.orEmpty().take(LIMITE); altaEm = System.currentTimeMillis() }
                renderizar()
            }
        }.start()
    }

    private fun falhou(e: Exception?) {
        erro = if (!temInternet()) "Sem internet" else "Não consegui buscar agora"
        if (e != null && temInternet()) aoFalha(e)
    }

    private fun buscar() {
        val q = consulta.trim()
        val minha = ++seq
        if (q.isEmpty()) {
            resV = null; resM = null; carregando = false; erro = null
            if (altaV == null) carregarAlta() else renderizar()
            return
        }
        carregando = true; erro = null
        renderizar()
        var v: List<Resultado>? = null; var m: List<Resultado>? = null; var falha: Exception? = null
        val tv = Thread { try { v = YouTube.buscar(q, false) } catch (e: Exception) { falha = e } }
        val tm = Thread { try { m = YouTube.buscar(q, true) } catch (e: Exception) { falha = e } }
        Thread {
            tv.start(); tm.start(); tv.join(); tm.join()
            principal.post {
                if (minha != seq) return@post
                carregando = false
                if (v == null && m == null) { falhou(falha); resV = null; resM = null }
                else { resV = v.orEmpty().take(LIMITE); resM = m.orEmpty().take(LIMITE) }
                renderizar()
            }
        }.start()
    }

    // ------------------------------------------------------------------ desenho das fileiras

    private fun renderizar() {
        piscar?.cancel(); piscar = null
        area.removeAllViews(); fileiras.clear()
        val q = consulta.trim()
        erro?.let { mostrarErro(it); return }
        if (carregando) {
            area.addView(cabecalho(if (q.isEmpty()) "Vídeos em alta" else "Vídeos", ""), cabLp())
            area.addView(fileira(List(6) { esqueleto(true) }), fileiraLp())
            area.addView(cabecalho(if (q.isEmpty()) "Músicas em alta" else "Músicas", ""), cabLp())
            area.addView(fileira(List(8) { esqueleto(false) }), fileiraLp())
            piscar = ValueAnimator.ofFloat(.35f, .75f).apply {
                duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { v -> fileiras.forEach { f -> (f.getChildAt(0) as LinearLayout).let { l -> for (i in 0 until l.childCount) l.getChildAt(i).alpha = v.animatedValue as Float } } }
                start()
            }
            return
        }
        val v: List<Resultado>; val m: List<Resultado>
        val tituloV: String; val tituloM: String; val contaV: String; val contaM: String
        if (q.isEmpty()) {
            v = altaV.orEmpty(); m = altaM.orEmpty()
            tituloV = "Vídeos em alta"; tituloM = "Músicas em alta"; contaV = ""; contaM = ""
        } else if (resV.isNullOrEmpty() && resM.isNullOrEmpty()) {
            v = altaV.orEmpty(); m = altaM.orEmpty()
            tituloV = "Vídeos em alta"; tituloM = "Músicas em alta"; contaV = "Nada encontrado para “$q”"; contaM = ""
        } else {
            v = resV.orEmpty(); m = resM.orEmpty()
            tituloV = "Vídeos"; tituloM = "Músicas"
            contaV = ""
            contaM = ""
        }
        val vv = if (v.isEmpty() && q.isNotEmpty()) altaV.orEmpty() else v
        val mm = if (m.isEmpty() && q.isNotEmpty()) altaM.orEmpty() else m
        area.addView(cabecalho(tituloV, contaV), cabLp())
        area.addView(fileira(vv.mapIndexed { i, r -> cartaoVideo(r) { aoVideo(vv, i) } }), fileiraLp())
        area.addView(cabecalho(tituloM, contaM), cabLp())
        area.addView(fileira(mm.mapIndexed { i, r -> cartaoMusica(r) { aoMusica(mm, i) } }), fileiraLp())
    }

    private fun cabLp() = LinearLayout.LayoutParams(-1, -2).apply { leftMargin = 52.d(); rightMargin = 48.d(); topMargin = 18.d(); bottomMargin = 10.d() }
    private fun fileiraLp() = LinearLayout.LayoutParams(-1, -2)

    private fun cabecalho(titulo: String, conta: String): View = LinearLayout(c).apply {
        gravity = Gravity.BOTTOM
        addView(texto(c, titulo, 23, Tema.TEXTO, true))
        if (conta.isNotEmpty()) addView(texto(c, conta, 19, Color.parseColor("#FFB3BC")), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 14.d(); bottomMargin = 2.d() })
    }

    private fun fileira(cartoes: List<View>): HorizontalScrollView {
        val linha = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(48.d(), 0, 30.d(), 0) }
        cartoes.forEach { linha.addView(it) }
        return HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            addView(linha, LayoutParams(-2, -2))
            fileiras.add(this)
        }
    }

    private fun arredondar(v: View, raio: Int) {
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, raio.d().toFloat()) }
        }
    }

    private fun esqueleto(video: Boolean): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val largura = if (video) LARGURA_VIDEO else LADO_MUSICA
        val capa = View(c).apply { background = fundoArredondado(Color.parseColor("#33FFFFFF"), if (video) 16.d() else 18.d()) }
        addView(capa, LinearLayout.LayoutParams(largura.d(), (if (video) LARGURA_VIDEO * 9 / 16 else LADO_MUSICA).d()))
        val l1 = View(c).apply { background = fundoArredondado(Color.parseColor("#33FFFFFF"), 6.d()) }
        val l2 = View(c).apply { background = fundoArredondado(Color.parseColor("#33FFFFFF"), 6.d()) }
        addView(l1, LinearLayout.LayoutParams((largura - 30).d(), 16.d()).apply { topMargin = 14.d() })
        addView(l2, LinearLayout.LayoutParams((largura - 80).d(), 14.d()).apply { topMargin = 8.d() })
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18.d() }
    }

    private fun cartaoVideo(r: Resultado, aoToque: () -> Unit): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        setOnClickListener { aoToque() }
        efeitoToque()
        val quadro = FrameLayout(c).apply { background = fundoArredondado(Color.parseColor("#22FFFFFF"), 18.d()) }
        val imagem = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        arredondar(quadro, 18)
        quadro.addView(imagem, FrameLayout.LayoutParams(-1, -1))
        Miniaturas.carregar(r.miniaturas, imagem)
        quadro.addView(texto(c, r.duracaoTexto, 15, Color.WHITE, true).apply {
            background = fundoArredondado(Color.parseColor("#B8000000"), 7.d())
            setPadding(8.d(), 3.d(), 8.d(), 3.d())
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = 8.d(); bottomMargin = 8.d() })
        addView(quadro, LinearLayout.LayoutParams(LARGURA_VIDEO.d(), 133.d()))
        addView(texto(c, r.titulo, 20, Color.WHITE, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(LARGURA_VIDEO.d(), -2).apply { topMargin = 9.d() })
        val sub = listOf(r.canal, r.visualizacoesTexto).filter { it.isNotEmpty() }.joinToString(" · ")
        addView(texto(c, sub, 17, Tema.TEXTO3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(LARGURA_VIDEO.d(), -2).apply { topMargin = 3.d() })
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18.d() }
    }

    private fun cartaoMusica(r: Resultado, aoToque: () -> Unit): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        setOnClickListener { aoToque() }
        efeitoToque()
        val quadro = FrameLayout(c).apply { background = fundoArredondado(Color.parseColor("#22FFFFFF"), 18.d()) }
        val imagem = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        arredondar(quadro, 18)
        quadro.addView(imagem, FrameLayout.LayoutParams(-1, -1))
        Miniaturas.carregar(r.miniaturas, imagem)
        val play = FrameLayout(c).apply {
            background = fundoArredondado(Color.WHITE, 20.d())
            addView(icone(c, R.drawable.ic_tocar, 20, Color.parseColor("#0A1020")), LayoutParams(20.d(), 20.d(), Gravity.CENTER))
        }
        quadro.addView(play, FrameLayout.LayoutParams(40.d(), 40.d(), Gravity.BOTTOM or Gravity.END).apply { rightMargin = 8.d(); bottomMargin = 8.d() })
        addView(quadro, LinearLayout.LayoutParams(LADO_MUSICA.d(), LADO_MUSICA.d()))
        addView(texto(c, r.titulo, 18, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(LADO_MUSICA.d(), -2).apply { topMargin = 9.d() })
        addView(texto(c, r.canal.removeSuffix(" - Topic"), 16, Tema.TEXTO3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(LADO_MUSICA.d(), -2).apply { topMargin = 3.d() })
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18.d() }
    }

    private fun mostrarErro(msg: String) {
        val caixa = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        caixa.addView(AnelIcone(c, R.drawable.ic_wifi_off, 96, Tema.VERMELHO_SUAVE, Color.parseColor("#24FF4D5E"), 0))
        caixa.addView(texto(c, msg, 34, Tema.TEXTO, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.d() })
        caixa.addView(texto(c, "Confira a internet da caixa e tente de novo.", 22, Tema.TEXTO2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 8.d() })
        val botoes = LinearLayout(c).apply {
            gravity = Gravity.CENTER
            addView(botao(c, "Tentar de novo", Cfg.AZUL) { erro = null; if (consulta.isBlank()) carregarAlta() else buscar() })
            addView(botao(c, "Abrir Wi-Fi", Tema.BOTAO) { aoAbrirWifi?.invoke() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 14.d() })
        }
        caixa.addView(botoes, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 24.d() })
        area.addView(caixa, LinearLayout.LayoutParams(-1, -1))
    }

    // ------------------------------------------------------------------ barra e teclado

    private fun atualizarBarra() {
        textoBusca.text = consulta
        dica.visibility = if (consulta.isEmpty()) View.VISIBLE else View.GONE
        botaoLimpar.visibility = if (consulta.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Busca por voz: o microfone fica vermelho, pulsando, e a barra mostra "Ouvindo…". */
    fun ouvindoVoz(sim: Boolean) {
        pulsoMic?.cancel(); pulsoMic = null
        principal.removeCallbacks(restaurarDica)
        if (sim) {
            // o teclado fica como está: o que a pessoa fala vai aparecendo na barra de pesquisa
            microfone.background = fundoArredondado(Tema.ERRO, 24.d())
            microfoneTeclado.background = fundoArredondado(Tema.ERRO, 32.d())
            consulta = ""; textoBusca.text = ""; dica.visibility = View.VISIBLE     // a barra espera o texto ao vivo
            pulsoMic = ValueAnimator.ofFloat(1f, 1.15f).apply {
                duration = 600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    val v = it.animatedValue as Float
                    microfone.scaleX = v; microfone.scaleY = v; microfoneTeclado.scaleX = v; microfoneTeclado.scaleY = v
                }
                start()
            }
        } else {
            microfone.scaleX = 1f; microfone.scaleY = 1f; microfoneTeclado.scaleX = 1f; microfoneTeclado.scaleY = 1f
            microfone.background = fundoArredondado(Color.parseColor("#33FFFFFF"), 24.d())
            microfoneTeclado.background = fundoArredondado(Color.parseColor("#33FFFFFF"), 32.d())
            restaurarDica.run()
        }
    }

    /** Busca por voz: as palavras vão aparecendo na barra enquanto a pessoa fala. */
    fun mostrarParcial(texto: String) {
        consulta = texto
        textoBusca.text = texto
        dica.visibility = View.GONE
        botaoLimpar.visibility = View.GONE
    }

    /** Escreve [texto] na barra e pesquisa (usado pela busca por voz). */
    fun pesquisar(texto: String) {
        consulta = texto
        atualizarBarra()
        fecharTeclado()
        buscar()
    }

    /** Mensagem curta na barra (ex.: a busca por voz não ouviu nada); volta ao texto normal em 2 s. */
    fun avisoBusca(msg: String) {
        consulta = ""; textoBusca.text = ""
        dica.text = msg
        dica.visibility = View.VISIBLE
        principal.removeCallbacks(restaurarDica)
        principal.postDelayed(restaurarDica, 2200)
    }
    private val restaurarDica = Runnable { dica.text = "Pesquisar vídeos e músicas"; atualizarBarra() }

    private fun digitar(s: String) { consulta += s; mudouTexto() }
    private fun apagar() { if (consulta.isNotEmpty()) { consulta = consulta.dropLast(1); mudouTexto() } }
    private fun mudouTexto() { atualizarBarra() }      // a busca só roda ao tocar em "Buscar"

    private fun montarTeclado() {
        // sobe de baixo cobrindo de y 218 até o fim (fundo sólido, cantos de cima 30), sem botão de recolher
        teclado.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#080D1E"))
            cornerRadii = floatArrayOf(30.d().toFloat(), 30.d().toFloat(), 30.d().toFloat(), 30.d().toFloat(), 0f, 0f, 0f, 0f)
        }
        teclado.isClickable = true
        teclado.setPadding(0, 0, 0, 0)
        tecladoV3.acao = "Buscar"
        tecladoV3.aoDigitar = { s -> digitar(s) }
        tecladoV3.aoApagar = { apagar() }
        tecladoV3.aoAcao = { fecharTeclado(); buscar() }
        microfoneTeclado.apply {
            background = fundoArredondado(Color.parseColor("#33FFFFFF"), 32.d())
            isClickable = true
            setOnClickListener { aoMicrofone?.invoke() ?: avisoBusca("Busca por voz indisponível") }
            efeitoToque()
            addView(icone(c, R.drawable.ic_mic, 30, Color.WHITE), LayoutParams(30.d(), 30.d(), Gravity.CENTER))
        }
        teclado.addView(microfoneTeclado, LayoutParams(64.d(), 64.d(), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 16.d() })
        teclado.addView(tecladoV3, LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = 12.d() })
    }

    fun abrirTeclado() {
        if (tecladoAberto) return
        tecladoAberto = true
        tecladoV3.capitalizar(consulta.isEmpty() && false)
        teclado.visibility = View.VISIBLE
        teclado.post {
            teclado.translationY = teclado.height.toFloat()
            teclado.animate().translationY(0f).setDuration(Tema.TECLADO_MS).setInterpolator(Tema.CURVA).start()
        }
        barra.background = fundoArredondado(Color.parseColor("#33FFFFFF"), 33.d(), Color.parseColor("#88FFFFFF"))
    }

    fun fecharTeclado() {
        if (!tecladoAberto) return
        tecladoAberto = false
        barra.background = fundoArredondado(Color.parseColor("#26FFFFFF"), 33.d(), Cfg.LINHA)
        teclado.animate().translationY(teclado.height.toFloat()).setDuration(Tema.TECLADO_MS).setInterpolator(Tema.CURVA).withEndAction { if (!tecladoAberto) teclado.visibility = View.GONE }.start()
    }

    private var engolirToque = false

    /** O teclado está aberto (a Activity fecha ao tocar no cabeçalho ou ao puxar a borda para sair). */
    val tecladoVisivel: Boolean get() = tecladoAberto

    /** Com o teclado aberto, tocar fora dele (e fora da barra) só fecha o teclado: o toque não aciona o que está embaixo. */
    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            engolirToque = false
            if (tecladoAberto) {
                val p = IntArray(2)
                teclado.getLocationOnScreen(p)
                val b = IntArray(2)
                barra.getLocationOnScreen(b)
                val fora = e.rawY < p[1] && !(e.rawY >= b[1] && e.rawY <= b[1] + barra.height)
                if (fora) { fecharTeclado(); engolirToque = true }
            }
        }
        if (engolirToque) {
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) engolirToque = false
            return true
        }
        return super.dispatchTouchEvent(e)
    }

    /** O teclado fecha primeiro quando o usuário volta. Devolve true se havia teclado aberto. */
    fun voltar(): Boolean { if (tecladoAberto) { fecharTeclado(); return true }; return false }

    /**
     * O gesto de voltar (puxar da borda esquerda) não pode começar em cima do teclado nem de uma fileira que já rolou:
     * ali o arrasto para a direita é para rolar ou digitar.
     */
    fun areaProtegida(x: Float, y: Float): Boolean {
        val p = IntArray(2)
        // o teclado não protege a borda: o gesto de voltar começa em x < 36, onde não há teclas
        if (tecladoAberto) return false      // com o teclado aberto, as fileiras ficam escondidas atrás dele
        for (f in fileiras) {
            f.getLocationOnScreen(p)
            if (y >= p[1] && y <= p[1] + f.height && f.scrollX > 0) return true
        }
        return false
    }
}
