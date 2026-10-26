package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
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

/** Capa do álbum: a imagem recortada com cantos arredondados, ou um degradê com a nota musical. */
class CapaView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val area = RectF()
    private val nota: Drawable? = c.getDrawable(R.drawable.ic_music)?.mutate()
    private var aparecer = 1f
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

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(340.d(), 340.d())

    override fun onDraw(canvas: Canvas) {
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        val raio = 26.d().toFloat()
        val img = imagem
        if (img == null || aparecer < 1f) {
            val h = ((semente.hashCode() and 0x7fffffff) % 360).toFloat()
            val c1 = Color.HSVToColor(floatArrayOf(h, .7f, .85f))
            val c2 = Color.HSVToColor(floatArrayOf((h + 50f) % 360f, .8f, .35f))
            p.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), c1, c2, Shader.TileMode.CLAMP)
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

/** Barra de progresso: toque ou arraste para escolher o ponto da música. */
class BarraProgresso(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var fracao = 0f
        set(v) { if (!arrastando) { field = v.coerceIn(0f, 1f); invalidate() } }
    var aoArrastar: ((Float) -> Unit)? = null   // enquanto o dedo desliza
    var aoSoltar: ((Float) -> Unit)? = null     // quando o dedo sai
    var ativa = true
    var corPreenchida = Color.WHITE
    private var arrastando = false
    private var fracaoDedo = 0f

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), 44.d())

    private fun fracaoDe(x: Float) = ((x - 12.d()) / (width - 24.d()).toFloat()).coerceIn(0f, 1f)

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
        val altura = if (arrastando) 12.d() else 8.d()
        val meio = height / 2f
        val esq = 12.d().toFloat()
        val dir = (width - 12.d()).toFloat()
        val r = altura / 2f
        val f = if (arrastando) fracaoDedo else fracao
        p.color = Color.parseColor("#33FFFFFF")
        canvas.drawRoundRect(esq, meio - r, dir, meio + r, r, r, p)
        p.color = corPreenchida        // a linha do tempo fica branca, não segue a cor da capa
        val x = esq + (dir - esq) * f
        canvas.drawRoundRect(esq, meio - r, x, meio + r, r, r, p)
        if (ativa) {
            p.color = Color.WHITE
            canvas.drawCircle(x, meio, if (arrastando) 13.d().toFloat() else 9.d().toFloat(), p)
        }
    }
}

/**
 * Tela "Tocando agora": capa, título, artista, tempo e botões. Aparece sozinha quando alguém
 * começa a tocar pelo Bluetooth.
 */
class TelaMusica(
    private val c: Context,
    private val musica: MusicaControle,
    private val bt: BluetoothControle,
    private val aoAbrirBluetooth: () -> Unit,
    private val aoAbrirBusca: () -> Unit
) : FrameLayout(c) {

    private val capa = CapaView(c)
    private val titulo = texto(c, "", 40, Color.WHITE, true)
    private val artista = texto(c, "", 26, Color.WHITE)
    private val album = texto(c, "", 20, Color.WHITE)
    private val barra = BarraProgresso(c)
    private val tempoAtual = texto(c, "0:00", 19, Color.WHITE)
    private val tempoTotal = texto(c, "", 19, Color.WHITE)
    private val botaoPlay = FrameLayout(c)
    private val iconePlay = ImageView(c)
    private val conteudo = LinearLayout(c)
    private val vazio = LinearLayout(c)
    private val tituloVazio = texto(c, "", 38, Color.WHITE, true)
    private val dicaVazio = texto(c, "", 22, Color.WHITE)
    private val botaoBluetooth = botao(c, "Abrir Bluetooth", Cfg.AZUL) { aoAbrirBluetooth() }
    private var ultimoEstado = ""

    init {
        // ----- Tocando -----
        conteudo.apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(72.d(), 24.d(), 72.d(), 40.d())
        }
        conteudo.addView(capa, LinearLayout.LayoutParams(340.d(), 340.d()))

        val coluna = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        titulo.maxLines = 2; titulo.ellipsize = TextUtils.TruncateAt.END
        artista.maxLines = 1; artista.ellipsize = TextUtils.TruncateAt.END
        album.maxLines = 1; album.ellipsize = TextUtils.TruncateAt.END
        coluna.addView(titulo)
        coluna.addView(artista, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.d() })
        coluna.addView(album, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.d() })
        barra.aoArrastar = { f -> if (musica.duracaoMs > 0) tempoAtual.text = formatar((f * musica.duracaoMs).toLong()) }
        barra.aoSoltar = { f -> if (musica.duracaoMs > 0) musica.buscar((f * musica.duracaoMs).toLong()) }
        coluna.addView(barra, LinearLayout.LayoutParams(-1, 44.d()).apply { topMargin = 22.d() })
        val tempos = LinearLayout(c).apply {
            addView(tempoAtual, LinearLayout.LayoutParams(0, -2, 1f))
            addView(tempoTotal)
        }
        coluna.addView(tempos, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 0 })
        tempos.setPadding(12.d(), 0, 12.d(), 0)

        val controles = LinearLayout(c).apply { gravity = Gravity.CENTER }
        controles.addView(botaoRedondo(R.drawable.ic_anterior, 68, 30, Color.parseColor("#22FFFFFF")) { musica.anterior() })
        botaoPlay.apply {
            background = fundoArredondado(Color.WHITE, 42.d())      // botão de play/pause sempre branco, com o ícone preto
            isClickable = true
            setOnClickListener { musica.alternar() }
            efeitoToque()
            addView(iconePlay, LayoutParams(40.d(), 40.d(), Gravity.CENTER))
        }
        iconePlay.setColorFilter(Color.BLACK)
        controles.addView(botaoPlay, LinearLayout.LayoutParams(84.d(), 84.d()).apply { leftMargin = 28.d(); rightMargin = 28.d() })
        controles.addView(botaoRedondo(R.drawable.ic_proxima, 68, 30, Color.parseColor("#22FFFFFF")) { musica.proxima() })
        coluna.addView(controles, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 30.d() })

        conteudo.addView(coluna, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 48.d() })
        addView(conteudo, LayoutParams(-1, -1))

        // botão no canto: vai para a busca de vídeos e músicas
        val menu = texto(c, "Menu", 24, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = fundoArredondado(Color.parseColor("#33FFFFFF"), 30.d(), Cfg.LINHA)
            isClickable = true
            setOnClickListener { aoAbrirBusca() }
            efeitoToque()
        }
        addView(menu, LayoutParams(150.d(), 60.d(), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = 26.d(); rightMargin = 40.d() })

        // ----- Nada tocando -----
        vazio.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        vazio.addView(icone(c, R.drawable.ic_bluetooth, 96, Color.WHITE), LinearLayout.LayoutParams(96.d(), 96.d()))
        vazio.addView(tituloVazio, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 24.d() })
        vazio.addView(dicaVazio, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 10.d() })
        vazio.addView(botaoBluetooth, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 28.d() })
        addView(vazio, LayoutParams(-1, -1))

        atualizar(true)
    }

    private fun botaoRedondo(recurso: Int, tamanho: Int, tamIcone: Int, fundo: Int, aoClicar: () -> Unit): View =
        FrameLayout(c).apply {
            background = fundoArredondado(fundo, (tamanho / 2).d(), Cfg.LINHA)
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
        conteudo.visibility = if (tem) View.VISIBLE else View.GONE
        vazio.visibility = if (tem) View.GONE else View.VISIBLE
        if (!tem) {
            // sem música: a mensagem depende de haver ou não um celular conectado
            val celular = bt.algumConectado
            tituloVazio.text = if (celular) "Nenhuma música tocando" else "Nada tocando"
            dicaVazio.text = if (celular) "Abra o app de música no celular, escolha uma música e dê play."
                             else "Conecte um celular pelo Bluetooth e toque uma música."
            botaoBluetooth.visibility = if (celular) View.GONE else View.VISIBLE
            return
        }

        val chave = "${musica.titulo}|${musica.artista}|${musica.album}|${musica.tocando}|${musica.capa?.hashCode()}|${Cfg.AZUL}"
        if (forcar || chave != ultimoEstado) {
            ultimoEstado = chave
            titulo.text = musica.titulo
            artista.text = musica.artista.ifEmpty { "Artista desconhecido" }
            album.text = musica.album
            album.visibility = if (musica.album.isEmpty()) View.GONE else View.VISIBLE
            capa.imagem = musica.capa
            capa.semente = musica.titulo + musica.artista
            iconePlay.setImageResource(if (musica.tocando) R.drawable.ic_pausar else R.drawable.ic_tocar)
            botaoPlay.background = fundoArredondado(Color.WHITE, 42.d())
        }
        val pos = musica.posicaoMs()
        // O celular não aceita pular para um ponto da música pelo Bluetooth (testado), então a barra só mostra o tempo.
        barra.ativa = (PODE_BUSCAR || musica.emLocal) && musica.duracaoMs > 0
        tempoAtual.text = formatar(pos)
        if (musica.duracaoMs > 0) {
            tempoTotal.text = formatar(musica.duracaoMs)
            barra.fracao = pos.toFloat() / musica.duracaoMs
        } else {
            tempoTotal.text = ""
            barra.fracao = 0f
        }
    }
}
