package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Capa do álbum (368 x 368, raio 30, sombra suave): a imagem recortada com cantos arredondados, ou o degradê da faixa com a nota. */
class CapaView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val area = RectF()
    private val nota: Drawable? = c.getDrawable(R.drawable.ic_music)?.mutate()
    private var aparecer = 1f
    private var sombra: Bitmap? = null
    private val folga = 70.d()
    var imagem: Bitmap? = null
        set(v) {
            val mudou = field !== v
            field = v
            if (v != null && mudou) {
                aparecer = 0f
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 350
                    addUpdateListener { aparecer = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
            invalidate()
        }
    var semente = ""
        set(v) { field = v; invalidate() }

    /** Matiz (0..360) do degradê da capa sem imagem: o fundo da tela usa o mesmo. */
    companion object { fun matiz(semente: String) = ((semente.hashCode() and 0x7fffffff) % 360).toFloat() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(TAM_BANNER.d(), TAM_BANNER.d())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // sombra "0 24 60 #73000000", desenhada uma vez
        val img = Bitmap.createBitmap(w + 2 * folga, h + 2 * folga, Bitmap.Config.ARGB_8888)
        val ps = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#73000000"); maskFilter = BlurMaskFilter(30.d().toFloat(), BlurMaskFilter.Blur.NORMAL) }
        Canvas(img).drawRoundRect(RectF(folga.toFloat(), folga + 24.d().toFloat(), (folga + w).toFloat(), (folga + h + 24.d()).toFloat()), 30.d().toFloat(), 30.d().toFloat(), ps)
        sombra = img
    }

    override fun onDraw(canvas: Canvas) {
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        val raio = 30.d().toFloat()
        sombra?.let { canvas.drawBitmap(it, -folga.toFloat(), -folga.toFloat(), null) }
        val img = imagem
        if (img == null || aparecer < 1f) {
            val h = matiz(semente)
            p.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                CorCapa.hsl(h, .82f, .48f), CorCapa.hsl(h + 35f, .72f, .32f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(area, raio, raio, p)
            p.shader = null
            nota?.let {
                val t = 150.d()
                val cx = width / 2
                val cy = height / 2
                it.setBounds(cx - t / 2, cy - t / 2, cx + t / 2, cy + t / 2)
                it.setTint(Color.argb(190, 255, 255, 255))
                it.draw(canvas)
            }
        }
        if (img != null) {
            val sh = BitmapShader(img, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            sh.setLocalMatrix(Matrix().apply { setScale(width / img.width.toFloat(), height / img.height.toFloat()) })
            p.shader = sh
            p.alpha = (255 * aparecer).toInt()
            canvas.drawRoundRect(area, raio, raio, p)
            p.shader = null
            p.alpha = 255
        }
    }
}

/** Liga o toque na barra de progresso quando o Bluetooth permitir pular para um ponto da música. */
const val PODE_BUSCAR = false

/** Barra de progresso: trilho de 6, só o preenchido tem cor ([corPreenchida]) e a bolinha da ponta é branca. Toque ou arraste escolhe o ponto. */
class BarraProgresso(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var fracao = 0f
        set(v) { if (!arrastando) { field = v.coerceIn(0f, 1f); invalidate() } }
    var aoArrastar: ((Float) -> Unit)? = null   // enquanto o dedo desliza
    var aoSoltar: ((Float) -> Unit)? = null     // quando o dedo sai
    var ativa = true
    var corPreenchida = Color.WHITE
        set(v) { field = v; invalidate() }
    private var arrastando = false
    private var fracaoDedo = 0f

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), 44.d())

    private fun fracaoDe(x: Float) = ((x - 15.d()) / (width - 30.d()).toFloat()).coerceIn(0f, 1f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!ativa) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                arrastando = true
                fracaoDedo = fracaoDe(e.x); aoArrastar?.invoke(fracaoDedo); invalidate()
            }
            MotionEvent.ACTION_MOVE -> { fracaoDedo = fracaoDe(e.x); aoArrastar?.invoke(fracaoDedo); invalidate() }
            MotionEvent.ACTION_UP -> {
                fracaoDedo = fracaoDe(e.x)
                arrastando = false
                fracao = fracaoDedo
                aoSoltar?.invoke(fracaoDedo)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { arrastando = false; invalidate() }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val altura = if (arrastando) 8.d() else 6.d()
        val meio = height / 2f
        val esq = 15.d().toFloat()          // a bolinha (raio 15) não passa da borda da barra
        val dir = (width - 15.d()).toFloat()
        val r = altura / 2f
        val f = if (arrastando) fracaoDedo else fracao
        p.color = Color.parseColor("#29FFFFFF")
        canvas.drawRoundRect(esq, meio - r, dir, meio + r, r, r, p)
        p.color = corPreenchida
        val x = esq + (dir - esq) * f
        canvas.drawRoundRect(esq, meio - r, x, meio + r, r, r, p)
        if (ativa) {
            p.color = Color.WHITE
            canvas.drawCircle(x, meio, 15.d().toFloat(), p)
        }
    }
}

/**
 * Seletor música <-> vídeo da v3: pílula com dois botões de 64 x 48 (nota musical | vídeo). O ativo fica branco com ícone escuro;
 * [videoDisponivel] = false deixa o botão de vídeo a 35% e sem resposta.
 */
class SeletorMidia(c: Context, private val aoMusica: () -> Unit, private val aoVideo: () -> Unit) : LinearLayout(c) {
    private val bMusica = FrameLayout(c)
    private val bVideo = FrameLayout(c)
    private val iMusica = ImageView(c).apply { setImageResource(R.drawable.ic_music) }
    private val iVideo = ImageView(c).apply { setImageResource(R.drawable.ic_video) }

    /** Qual dos dois está ativo: "musica" ou "video". */
    var ativo = "musica"
        set(v) { field = v; pintar() }
    var videoDisponivel = true
        set(v) { field = v; pintar() }
    /** Falso quando o vídeo não é uma música (muito longo): o botão da nota fica a 35% e sem resposta. */
    var musicaDisponivel = true
        set(v) { field = v; pintar() }

    init {
        setPadding(5.d(), 5.d(), 5.d(), 5.d())
        background = fundoArredondado(Color.parseColor("#1AFFFFFF"), 42.d(), Color.parseColor("#1FFFFFFF"))
        for ((b, i) in listOf(bMusica to iMusica, bVideo to iVideo)) {
            b.addView(i, FrameLayout.LayoutParams(38.d(), 38.d(), Gravity.CENTER))
            addView(b, LayoutParams(100.d(), 72.d()))
            b.isClickable = true
            b.efeitoToque()
        }
        bMusica.contentDescription = "Ouvir só a música"
        bVideo.contentDescription = "Ver o vídeo"
        bMusica.setOnClickListener { if (musicaDisponivel && ativo != "musica") aoMusica() }
        bVideo.setOnClickListener { if (videoDisponivel && ativo != "video") aoVideo() }
        pintar()
    }

    private fun pintar() {
        fun estilo(b: FrameLayout, i: ImageView, ligado: Boolean) {
            b.background = if (ligado) fundoArredondado(Color.WHITE, 36.d()) else null
            i.setColorFilter(if (ligado) Color.parseColor("#0A1020") else Color.WHITE)
        }
        estilo(bMusica, iMusica, ativo == "musica")
        estilo(bVideo, iVideo, ativo == "video")
        bVideo.alpha = if (videoDisponivel) 1f else .35f
        bMusica.alpha = if (musicaDisponivel) 1f else .35f
    }
}

/**
 * Tela "Tocando agora" da v3: capa de 368, rótulo, título, artista, álbum, barra de progresso, controles e o seletor música <-> vídeo.
 * Aparece sozinha quando alguém começa a tocar pelo Bluetooth.
 */
class TelaMusica(
    private val c: Context,
    private val musica: MusicaControle,
    private val bt: BluetoothControle,
    private val aoAbrirBluetooth: () -> Unit,
    private val aoAbrirBusca: () -> Unit,
    private val aoAbrirVideo: () -> Unit
) : FrameLayout(c) {

    private val capa = CapaView(c)
    private val rotulo = texto(c, "", 15, Color.parseColor("#C9D4EE"), true).apply { letterSpacing = 1.6f / 15f }
    private val titulo = texto(c, "", 46, Tema.TEXTO, true)
    private val tituloBox = FrameLayout(c)      // o título é um texto rolante: se for maior que o espaço, desliza com as pontas em degradê
    private val artista = texto(c, "", 28, Color.parseColor("#E3EAFB"))
    private val album = texto(c, "", 21, Tema.TEXTO2)
    private val barra = BarraProgresso(c)
    private val tempos = LinearLayout(c)
    private val tempoAtual = texto(c, "0:00", 19, Tema.TEXTO2).apply { fontFeatureSettings = "tnum" }
    private val tempoTotal = texto(c, "", 19, Tema.TEXTO2).apply { fontFeatureSettings = "tnum" }
    private val botaoPlay = FrameLayout(c)
    private val iconePlay = ImageView(c)
    private val conteudo = FrameLayout(c)
    private val seletor = SeletorMidia(c, {}, { aoAbrirVideo() })
    private var ultimoEstado = ""
    /** O celular toca mas não manda título nem capa: tela "Tocando em <celular>" com animação e controles. */
    val semDados = PainelSemDados(c, musica, bt)
    private var sessaoSemDados = false
    private var proximasAbertas = false
    private val proximas = ListaProximas(c, "Próximas", 76, 76, { i -> musica.irParaLocal(i) }) { alternarProximas(false) }
    private val botaoProximas = LinearLayout(c)
    private val avisoErro = LinearLayout(c)      // "Não foi possível reproduzir" + Tentar de novo, quando a música do app não abre
    private val fundoProximas = View(c)      // cobre a tela por trás da lista: tocar fora dela fecha

    /** Fundo com a capa desfocada (fica atrás de todas as telas; quem monta é a InicioActivity). */
    var fundoCapa: FundoCapa? = null

    init {
        // posições em px de projeto; a tela fica 64 abaixo do topo, então y local = y do protótipo - 64
        conteudo.clipChildren = false      // a sombra da capa passa dos limites dela: cortada na borda de baixo, formava um fundo quadrado atrás dos cantos arredondados
        conteudo.addView(capa, LayoutParams(TAM_BANNER.d(), TAM_BANNER.d()).apply { leftMargin = 64.d(); topMargin = (BANNER_Y - 64).d() })

        val textos = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        titulo.maxLines = 2; titulo.ellipsize = TextUtils.TruncateAt.END
        artista.maxLines = 1; artista.ellipsize = TextUtils.TruncateAt.END
        album.maxLines = 1; album.ellipsize = TextUtils.TruncateAt.END
        textos.addView(rotulo)
        textos.addView(tituloBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.d() })
        textos.addView(artista, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.d() })
        textos.addView(album, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.d() })
        conteudo.addView(textos, LayoutParams((TEXTO_FIM - COLUNA_X).d(), -2).apply { leftMargin = COLUNA_X.d(); topMargin = (BANNER_Y - 64).d() })

        barra.aoArrastar = { f -> if (musica.duracaoMs > 0) tempoAtual.text = formatar((f * musica.duracaoMs).toLong()) }
        barra.aoSoltar = { f -> if (musica.duracaoMs > 0) musica.buscar((f * musica.duracaoMs).toLong()) }
        conteudo.addView(barra, LayoutParams(1216.d() - COLUNA_X.d() + 24.d(), 44.d()).apply { leftMargin = COLUNA_X.d() - 12.d(); topMargin = (BARRA_Y - 22 - 64).d() })
        tempos.apply { addView(tempoAtual, LinearLayout.LayoutParams(0, -2, 1f)); addView(tempoTotal) }
        conteudo.addView(tempos, LayoutParams(1216.d() - COLUNA_X.d(), -2).apply { leftMargin = COLUNA_X.d(); topMargin = (BARRA_Y + 24 - 64).d() })

        val controles = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        controles.addView(botaoRedondo(R.drawable.ic_anterior, 76, 32, Color.parseColor("#26FFFFFF")) { musica.anterior() })
        botaoPlay.apply {
            background = fundoArredondado(Color.WHITE, 48.d())      // botão de play/pause sempre branco, com o ícone escuro
            isClickable = true
            setOnClickListener { musica.alternar() }
            efeitoToque()
            addView(iconePlay, LayoutParams(44.d(), 44.d(), Gravity.CENTER))
        }
        iconePlay.setColorFilter(Color.parseColor("#0A1020"))
        controles.addView(botaoPlay, LinearLayout.LayoutParams(96.d(), 96.d()).apply { leftMargin = 28.d(); rightMargin = 28.d() })
        controles.addView(botaoRedondo(R.drawable.ic_proxima, 76, 32, Color.parseColor("#26FFFFFF")) { musica.proxima() })
        conteudo.addView(controles, LayoutParams(-2, 96.d()).apply { leftMargin = COLUNA_X.d(); topMargin = (CONTROLES_Y - 64).d() })

        addView(conteudo, LayoutParams(-1, -1))
        semDados.visibility = View.GONE
        addView(semDados, LayoutParams(-1, -1))      // por cima do conteúdo e por baixo do seletor e do Menu

        // seletor música <-> vídeo no canto superior direito (x 1216, y 132)
        addView(seletor, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = 64.d(); topMargin = (BANNER_Y - 2 - 64).d() })

        // botão no canto: vai para a busca de vídeos e músicas
        val menu = LinearLayout(c).apply {
            gravity = Gravity.CENTER
            tag = "menuMidia"
            background = fundoArredondado(Tema.BOTAO, 30.d(), Tema.BOTAO_BORDA)
            isClickable = true
            setOnClickListener { aoAbrirBusca() }
            efeitoToque()
            addView(icone(c, R.drawable.ic_midia, 28, Tema.TEXTO), LinearLayout.LayoutParams(28.d(), 28.d()))      // o ícone diz que é o menu da mídia
            addView(texto(c, "Menu", 24, Tema.TEXTO, true), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 10.d() })
        }
        addView(menu, LayoutParams(194.d(), 60.d(), Gravity.TOP or Gravity.END).apply { topMargin = (CONTROLES_Y + 48 - 30 - 64).d(); rightMargin = 64.d() })      // o mesmo centro dos controles
        // "Próximas": a lista do que vem depois (só quando a caixa é quem toca); abre uma lista ao lado e comprime o resto da tela
        botaoProximas.apply {
            gravity = Gravity.CENTER
            background = fundoArredondado(Tema.BOTAO, 30.d(), Tema.BOTAO_BORDA)
            isClickable = true
            visibility = View.GONE
            setOnClickListener { alternarProximas(true) }
            efeitoToque()
            addView(icone(c, R.drawable.ic_lista, 26, Tema.TEXTO), LinearLayout.LayoutParams(26.d(), 26.d()))
            addView(texto(c, "Próximas", 22, Tema.TEXTO, true), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 8.d() })
        }
        addView(botaoProximas, LayoutParams(184.d(), 60.d(), Gravity.TOP or Gravity.END).apply { topMargin = (CONTROLES_Y + 48 - 30 - 64).d(); rightMargin = (64 + 194 + 14).d() })
        fundoProximas.apply { visibility = View.GONE; isClickable = true; setOnClickListener { alternarProximas(false) } }
        addView(fundoProximas, LayoutParams(-1, -1))
        addView(proximas, LayoutParams(PROXIMAS_LARGURA.d(), -1, Gravity.END).apply { topMargin = 36.d(); bottomMargin = 22.d(); rightMargin = 20.d() })


        avisoErro.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            background = fundoArredondado(Color.parseColor("#CC0A1020"), 30.d(), Tema.BOTAO_BORDA)
            setPadding(28.d(), 0, 12.d(), 0)
            addView(texto(c, "Não foi possível reproduzir", 22, Tema.TEXTO, true))
            addView(botao(c, "Tentar de novo", Cfg.AZUL) { musica.tentarDeNovoLocal() }, LinearLayout.LayoutParams(-2, 48.d()).apply { leftMargin = 18.d() })
        }
        addView(avisoErro, LayoutParams(-2, 60.d(), Gravity.BOTTOM or Gravity.START).apply { leftMargin = COLUNA_X.d(); bottomMargin = 24.d() })
        atualizar(true)
    }

    private fun botaoRedondo(recurso: Int, tamanho: Int, tamIcone: Int, fundo: Int, aoClicar: () -> Unit): View =
        FrameLayout(c).apply {
            background = fundoArredondado(fundo, (tamanho / 2).d(), Tema.BOTAO_BORDA)
            isClickable = true
            setOnClickListener { aoClicar() }
            efeitoToque()
            addView(icone(c, recurso, tamIcone, Color.WHITE), LayoutParams(tamIcone.d(), tamIcone.d(), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(tamanho.d(), tamanho.d())
        }

    private fun formatar(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format("%d:%02d", s / 60, s % 60)
    }

    /** Chamada quando algo muda e também a cada meio segundo para o tempo andar. */
    fun atualizar(forcar: Boolean = false) {
        val tem = musica.temMusica
        // o celular tocou sem mandar os dados: a tela "Tocando em ..." fica até o aparelho sair ou os dados chegarem
        if (tem || !bt.algumConectado) sessaoSemDados = false else if (bt.celularTocando || musica.tocando) sessaoSemDados = true
        val painel = !tem && sessaoSemDados
        semDados.visibility = if (painel) View.VISIBLE else View.GONE
        if (painel) semDados.atualizar()
        fundoCapa?.definir(if (tem) musica.capa else null)
        conteudo.visibility = if (tem) View.VISIBLE else View.GONE
        seletor.visibility = if ((tem || painel) && !proximasAbertas) View.VISIBLE else View.GONE
        if (painel) { seletor.ativo = "musica"; seletor.videoDisponivel = false }
        findMenu()?.visibility = if (tem || painel) View.VISIBLE else View.GONE
        val local = tem && musica.emLocal && musica.filaLocal.size > 1 && !musica.ehRadio
        if (!local && proximasAbertas) alternarProximas(false)
        botaoProximas.visibility = if (local && !proximasAbertas) View.VISIBLE else View.GONE
        if (proximasAbertas) proximas.atualizar(musica.filaLocal, musica.indiceLocal)
        avisoErro.visibility = if (tem && musica.erroLocal != null) View.VISIBLE else View.GONE
        if (painel) return
        if (!tem) return      // sem música e sem celular tocando não há tela: quem manda é a InicioActivity (volta ao início)

        val chave = "${musica.titulo}|${musica.artista}|${musica.album}|${musica.tocando}|${musica.capa?.hashCode()}|${Cfg.AZUL}|${musica.emLocal}"
        if (forcar || chave != ultimoEstado) {
            ultimoEstado = chave
            tituloBox.removeAllViews(); tituloBox.addView(TextoRolante(c, musica.titulo, 46, Tema.TEXTO, true))
            artista.text = musica.artista.ifEmpty { "Artista desconhecido" }
            album.text = musica.album
            album.visibility = if (musica.album.isEmpty()) View.GONE else View.VISIBLE
            rotulo.text = "TOCANDO AGORA · " + if (musica.ehRadio) "RÁDIO AO VIVO" else if (musica.emLocal) "YOUTUBE" else "BLUETOOTH"
            capa.imagem = musica.capa
            capa.semente = musica.titulo + musica.artista
            iconePlay.setImageResource(if (musica.tocando) R.drawable.ic_pausar else R.drawable.ic_tocar)
            seletor.ativo = "musica"
            seletor.videoDisponivel = musica.emLocal && !musica.ehRadio       // só a música tocada pelo app tem o clipe para abrir
            barra.corPreenchida = Cfg.AZUL                  // a cor do trecho tocado é a da capa
        }
        val pos = musica.posicaoMs()
        // O celular não aceita pular para um ponto da música pelo Bluetooth (testado), então a barra só mostra o tempo.
        barra.ativa = (PODE_BUSCAR || musica.emLocal) && musica.duracaoMs > 0 && !musica.ehRadio
        barra.visibility = if (musica.ehRadio) View.INVISIBLE else View.VISIBLE      // rádio ao vivo: sem barra nem tempos
        tempos.visibility = if (musica.ehRadio) View.INVISIBLE else View.VISIBLE
        tempoAtual.text = formatar(pos)
        if (musica.duracaoMs > 0) {
            tempoTotal.text = formatar(musica.duracaoMs)
            barra.fracao = pos.toFloat() / musica.duracaoMs
        } else {
            tempoTotal.text = ""
            barra.fracao = 0f
        }
    }

    /** Abre ou fecha a lista de próximas: a tela comprime para a esquerda e a lista ocupa o lado direito. */
    private fun alternarProximas(abrir: Boolean) {
        proximasAbertas = abrir
        conteudo.pivotX = 0f; conteudo.pivotY = conteudo.height / 2f
        val escala = if (abrir) 0.66f else 1f
        conteudo.animate().scaleX(escala).scaleY(escala).setDuration(260).start()
        proximas.visibility = if (abrir) View.VISIBLE else View.GONE
        fundoProximas.visibility = if (abrir) View.VISIBLE else View.GONE
        if (abrir) proximas.atualizar(musica.filaLocal, musica.indiceLocal)
        botaoProximas.visibility = View.GONE
        seletor.visibility = if (abrir) View.GONE else seletor.visibility
        // o botão Menu encolhe junto com a tela (mesmo ponto fixo) e fica no canto de baixo à direita dela; fica acima do fundo que fecha a lista
        findMenu()?.let { m ->
            m.pivotX = -m.left.toFloat(); m.pivotY = conteudo.height / 2f - m.top
            m.animate().scaleX(escala).scaleY(escala).setDuration(260).start()
            if (abrir) m.bringToFront()
        }
    }

    /** O celular está tocando sem mandar os dados da música (a tela "Tocando de ..." é a que vale). Também liga a sessão, se ainda não estava. */
    fun semDadosAtivo(): Boolean {
        if (musica.temMusica || !bt.algumConectado) { sessaoSemDados = false; return false }
        if (bt.celularTocando || musica.tocando) sessaoSemDados = true
        return sessaoSemDados
    }

    private fun findMenu(): View? = (0 until childCount).map { getChildAt(it) }.firstOrNull { it.tag == "menuMidia" }
}

/**
 * Medidas (em px de projeto, y da tela inteira) da tela "Tocando agora" e da "Tocando de ...": tudo cabe na altura do banner, do topo dele
 * até a base: textos no alto, barra de progresso, tempos e, por último, os controles e o Menu alinhados com a base do banner.
 */
const val TAM_BANNER = 400
const val BANNER_Y = 192                       // topo do banner: o conjunto (banner + textos + controles) fica no meio da área abaixo do cabeçalho (64 a 720)
const val COLUNA_X = 64 + TAM_BANNER + 48      // onde começam os textos e os controles
const val BARRA_Y = BANNER_Y + 254            // centro da barra de progresso
const val CONTROLES_Y = BANNER_Y + TAM_BANNER - 96      // topo dos controles (96 de altura): a base deles é a base do banner

/** Largura da lista "Próximas" (a mesma na tela da música e no player de vídeo). */
const val PROXIMAS_LARGURA = 440

/** Onde terminam os textos (título, artista): antes do seletor música/vídeo, que fica no canto de cima à direita. */
const val TEXTO_FIM = 990
