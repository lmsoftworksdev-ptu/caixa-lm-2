package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.media3.common.util.UnstableApi

/** Rodinha de "carregando" (arco branco girando). */
class Carregando(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
    private val area = RectF()
    private var angulo = 0f
    private val gira = object : Runnable {
        override fun run() { angulo = (angulo + 9f) % 360f; invalidate(); postOnAnimation(this) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postOnAnimation(gira) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(gira) }

    override fun onDraw(canvas: Canvas) {
        p.strokeWidth = 6.d().toFloat()
        val m = p.strokeWidth
        area.set(m, m, width - m, height - m)
        canvas.drawArc(area, angulo, 270f, false, p)
    }
}

/**
 * Player de vídeo em tela cheia, por cima de tudo. Um toque mostra ou esconde os controles (play/pause, barra de tempo e o X
 * para fechar). Os controles somem sozinhos depois de alguns segundos tocando.
 */
@UnstableApi
class TelaVideo(
    private val c: Context,
    private val player: PlayerMidia,
    /** Volume do aparelho em porcentagem (0 a 100): o mesmo da pílula de volume da barra de cima. */
    private val lerVolume: () -> Int,
    private val definirVolume: (Int) -> Unit,
    /** Toque na nota do seletor (vídeo que veio de uma música): volta para Tocando agora no mesmo segundo. */
    private val aoVoltarMusica: () -> Unit,
    private val aoFechar: () -> Unit
) : FrameLayout(c) {

    private val imagem = SurfaceView(c)
    private var lateralAberta = false
    private val lateral = ListaProximas(c, "Próximos", 144, 81, { i -> player.irPara(i) }) { alternarLateral(false) }
    private var botaoLista: View? = null
    private var linhaBotoesRef: LinearLayout? = null
    private val tempoCanto = texto(c, "", 22, Color.WHITE)      // o tempo sozinho no canto de baixo, no player reduzido
    private var espacador: View? = null
    private var botaoVoltar10: View? = null
    private var botaoAvancar10: View? = null
    private var iconeVolume: View? = null
    private val cortina = View(c).apply { setBackgroundColor(Color.BLACK) }
    private var cortinaForcada = false
    private val controles = FrameLayout(c)
    private val titulo = texto(c, "", 32, Color.WHITE, true)
    private val canal = texto(c, "", 20, Color.parseColor("#D6E0F7"))
    private val seletor = SeletorMidia(c, { aoVoltarMusica() }, {}).apply { ativo = "video"; visibility = View.GONE }
    private val botaoPlay = FrameLayout(c)
    private val iconePlay = ImageView(c)
    private val barra = BarraProgresso(c)
    private val volume = BarraProgresso(c)
    private var botaoAnterior: View? = null
    private var botaoProxima: View? = null
    private val tempoAtual = texto(c, "0:00", 20, Color.WHITE)
    private val tempoTotal = texto(c, "", 20, Color.WHITE)
    private val rodinha = Carregando(c)
    private val mensagem = LinearLayout(c)
    private val textoErro = texto(c, "", 28, Color.WHITE, true)
    private val handler = Handler(Looper.getMainLooper())
    private val esconder = Runnable { mostrarControles(false) }
    private val tique = object : Runnable {
        override fun run() { atualizarTempo(); volume.fracao = lerVolume() / 100f; botaoLista?.visibility = if (player.fila.size > 1 && !lateralAberta) View.VISIBLE else View.GONE; if (lateralAberta) lateral.atualizar(player.fila, player.indice); handler.postDelayed(this, 500) }
    }
    private var controlesVisiveis = true

    val aberto: Boolean get() = visibility == View.VISIBLE

    init {
        visibility = View.GONE
        setBackgroundColor(Color.BLACK)
        isClickable = true
        addView(imagem, LayoutParams(-1, -1))
        addView(cortina, LayoutParams(-1, -1))

        // ---- controles (mesmo desenho do protótipo: título em cima; barra, botões, tempo e volume embaixo) ----
        val degradeTopo = View(c).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT)) }
        val degradeBase = View(c).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.parseColor("#E6000000"), Color.TRANSPARENT)) }
        controles.addView(degradeTopo, LayoutParams(-1, 130.d(), Gravity.TOP))
        controles.addView(degradeBase, LayoutParams(-1, 300.d(), Gravity.BOTTOM))

        titulo.maxLines = 1; titulo.ellipsize = TextUtils.TruncateAt.END
        canal.maxLines = 1; canal.ellipsize = TextUtils.TruncateAt.END
        val textos = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; addView(titulo); addView(canal, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4.d() }) }
        controles.addView(textos, LayoutParams(-1, -2, Gravity.TOP).apply { leftMargin = 40.d(); rightMargin = 300.d(); topMargin = 22.d() })
        controles.addView(seletor, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = 48.d(); topMargin = 30.d() })
        barra.corPreenchida = Tema.ERRO      // o trecho já assistido é vermelho; a bolinha, branca

        fun redondo(recurso: Int, lado: Int, icone: Int, fundo: Int, aoClicar: () -> Unit): FrameLayout = FrameLayout(c).apply {
            background = fundoArredondado(fundo, (lado / 2).d(), if (fundo == Color.WHITE) 0 else Cfg.LINHA)
            isClickable = true
            setOnClickListener { aoClicar(); agendarEsconder() }
            efeitoToque()
            addView(icone(c, recurso, icone, Color.WHITE), LayoutParams(icone.d(), icone.d(), Gravity.CENTER))
        }
        val vidro = Color.parseColor("#22FFFFFF")
        val anterior = redondo(R.drawable.ic_anterior, 92, 40, vidro) { player.anterior() }
        val voltar10 = redondo(R.drawable.ic_voltar10, 92, 40, vidro) { player.pular(-10_000) }
        botaoPlay.apply {
            background = fundoArredondado(Color.WHITE, 56.d())      // igual ao da música: fundo branco, ícone preto
            isClickable = true
            setOnClickListener { player.alternar(); agendarEsconder() }
            efeitoToque()
            iconePlay.setColorFilter(Color.BLACK)
            addView(iconePlay, LayoutParams(54.d(), 54.d(), Gravity.CENTER))
        }
        val avancar10 = redondo(R.drawable.ic_avancar10, 92, 40, vidro) { player.pular(10_000) }
        val proxima = redondo(R.drawable.ic_proxima, 92, 40, vidro) { player.proxima() }
        botaoAnterior = anterior; botaoProxima = proxima
        botaoVoltar10 = voltar10; botaoAvancar10 = avancar10
        val linhaBotoes = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        linhaBotoesRef = linhaBotoes
        val lp68 = { LinearLayout.LayoutParams(92.d(), 92.d()).apply { rightMargin = 20.d() } }
        linhaBotoes.addView(anterior, lp68())
        linhaBotoes.addView(voltar10, lp68())
        linhaBotoes.addView(botaoPlay, LinearLayout.LayoutParams(112.d(), 112.d()).apply { rightMargin = 20.d() })
        linhaBotoes.addView(avancar10, lp68())
        linhaBotoes.addView(proxima, lp68())
        val lista = redondo(R.drawable.ic_lista, 92, 40, vidro) { alternarLateral(!lateralAberta) }
        botaoLista = lista
        linhaBotoes.addView(lista, lp68())
        linhaBotoes.addView(tempoAtual.apply { setPadding(10.d(), 0, 0, 0) }, LinearLayout.LayoutParams(-2, -2))
        linhaBotoes.addView(tempoTotal, LinearLayout.LayoutParams(-2, -2))
        espacador = View(c)
        linhaBotoes.addView(espacador, LinearLayout.LayoutParams(0, 1, 1f))
        iconeVolume = icone(c, R.drawable.ic_volume_2, 34, Color.WHITE)
        linhaBotoes.addView(iconeVolume, LinearLayout.LayoutParams(34.d(), 34.d()).apply { rightMargin = 6.d() })
        linhaBotoes.addView(volume, LinearLayout.LayoutParams(200.d(), 60.d()))

        barra.aoArrastar = { f -> if (player.duracaoMs > 0) tempoAtual.text = formatar((f * player.duracaoMs).toLong()); agendarEsconder() }
        barra.aoSoltar = { f -> if (player.duracaoMs > 0) player.buscar((f * player.duracaoMs).toLong()) }
        // a barra de volume do vídeo é a MESMA do aparelho: lê e grava pelo controle de volume do app (níveis de 0 a 10)
        volume.fracao = lerVolume() / 100f
        volume.aoArrastar = { f -> definirVolume(Math.round(f * 10) * 10); agendarEsconder() }
        volume.aoSoltar = volume.aoArrastar

        val base = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(barra, LinearLayout.LayoutParams(-1, 60.d()))
            addView(linhaBotoes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.d() })
        }
        tempoCanto.visibility = View.GONE
        controles.addView(tempoCanto, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = 48.d(); bottomMargin = 74.d() })
        controles.addView(base, LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = 30.d(); rightMargin = 40.d(); bottomMargin = 26.d() })
        addView(controles, LayoutParams(-1, -1))
        addView(lateral, LayoutParams(PROXIMAS_LARGURA.d(), -1, Gravity.END).apply { topMargin = 16.d(); bottomMargin = 16.d(); rightMargin = 16.d() })

        // ---- carregando / erro ----
        addView(rodinha, LayoutParams(70.d(), 70.d(), Gravity.CENTER))
        mensagem.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(textoErro)
            addView(botao(c, "Tentar de novo", Cfg.AZUL) { player.alternar() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 20.d() })
        }
        addView(mensagem, LayoutParams(-2, -2, Gravity.CENTER))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) { if (lateralAberta) alternarLateral(false) else mostrarControles(!controlesVisiveis) }      // tocar fora da lista fecha a lista
        return true
    }

    /**
     * Botão da lista: comprime o vídeo (sempre em 16:9) para a esquerda e abre a barra lateral com os próximos. Os controles ficam do
     * mesmo tamanho do vídeo e o volume sai deles para caberem.
     */
    private fun alternarLateral(abrir: Boolean) {
        lateralAberta = abrir
        val largura = if (abrir) (1280 - PROXIMAS_LARGURA - 32).d() else -1      // o vídeo continua em 16:9
        val altura = if (abrir) ((1280 - PROXIMAS_LARGURA - 32) * 9 / 16).d() else -1
        val g = Gravity.START or Gravity.CENTER_VERTICAL
        imagem.layoutParams = LayoutParams(largura, altura, g)
        cortina.layoutParams = LayoutParams(largura, altura, g)
        controles.layoutParams = LayoutParams(if (abrir) (1280 - PROXIMAS_LARGURA - 24).d() else -1, -1, Gravity.START)
        botaoVoltar10?.visibility = if (abrir) View.GONE else View.VISIBLE
        botaoAvancar10?.visibility = if (abrir) View.GONE else View.VISIBLE
        lateral.visibility = if (abrir) View.VISIBLE else View.GONE
        // o círculo de carregando e a mensagem de erro ficam no centro da imagem reduzida (não da tela inteira)
        val desvio = if (abrir) -((1280 - (1280 - PROXIMAS_LARGURA - 32)) / 2).d().toFloat() else 0f
        rodinha.translationX = desvio; mensagem.translationX = desvio
        // com a lista aberta os botões ficam centralizados na largura do vídeo reduzido (sem o volume e sem o espaço que o empurrava para a esquerda)
        espacador?.visibility = if (abrir) View.GONE else View.VISIBLE
        // player reduzido: só anterior, play/pause e próximo; sem o botão da lista (tocar na tela fecha a lista) e com o tempo no canto
        botaoLista?.visibility = if (abrir || player.fila.size <= 1) View.GONE else View.VISIBLE
        tempoAtual.visibility = if (abrir) View.GONE else View.VISIBLE
        tempoTotal.visibility = if (abrir) View.GONE else View.VISIBLE
        tempoCanto.visibility = if (abrir) View.VISIBLE else View.GONE
        if (abrir) atualizarTempo()
        linhaBotoesRef?.gravity = if (abrir) Gravity.CENTER else Gravity.CENTER_VERTICAL
        volume.visibility = if (abrir) View.GONE else View.VISIBLE
        iconeVolume?.visibility = if (abrir) View.GONE else View.VISIBLE
        if (abrir) lateral.atualizar(player.fila, player.indice)
        agendarEsconder()
    }

    /** Só aparece quando o vídeo veio de uma música (Tocando agora). */
    fun mostrarSeletor(v: Boolean) { seletor.visibility = if (v) View.VISIBLE else View.GONE }

    private var esperando = false

    /** Abre já na tela de carregamento (rodinha e título), enquanto o clipe ainda está sendo achado. */
    fun esperar(tituloEspera: String, canalEspera: String) {
        esperando = true
        val entrando = visibility != View.VISIBLE
        visibility = View.VISIBLE
        cortina.visibility = View.VISIBLE
        if (entrando) { imagem.visibility = View.GONE; alpha = 0f; animate().alpha(1f).setDuration(380).withEndAction { imagem.visibility = View.VISIBLE }.start() }
        titulo.text = tituloEspera; canal.text = canalEspera
        iconePlay.setImageResource(R.drawable.ic_tocar)
        rodinha.visibility = View.VISIBLE
        mensagem.visibility = View.GONE
        mostrarControles(true)
        controles.alpha = 1f
    }

    /**
     * [esmaecendo]: a tela entra por esmaecimento sobre o que estava embaixo. A superfície do vídeo não acompanha a transparência da
     * tela, então ela só entra depois do esmaecimento, com a cortina preta cobrindo até lá.
     */
    fun abrir(esmaecendo: Boolean = false) {
        esperando = false
        if (lateralAberta) alternarLateral(false)
        visibility = View.VISIBLE
        if (esmaecendo) {
            imagem.visibility = View.GONE; cortinaForcada = true; cortina.visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(380).withEndAction { imagem.visibility = View.VISIBLE; player.ligarImagem(imagem); cortinaForcada = false; atualizar() }.start()
        } else { imagem.visibility = View.VISIBLE; player.ligarImagem(imagem) }
        mostrarControles(true)
        handler.removeCallbacks(tique); handler.post(tique)
        atualizar()
    }

    /** Cobre a imagem de preto (a superfície de vídeo não gira junto com a tela no efeito de cubo). */
    fun cobrir(sim: Boolean) { cortinaForcada = sim; cortina.visibility = if (sim || !player.primeiroQuadro) View.VISIBLE else View.GONE }

    private val amostra = android.graphics.Bitmap.createBitmap(48, 27, android.graphics.Bitmap.Config.ARGB_8888)
    private var amostrando = false
    private var pretosSeguidos = 0

    /**
     * Copia um quadro pequeno da imagem do vídeo e entrega a cor que mais aparece (a mesma conta da capa do álbum; se o quadro
     * for cinza ou escuro, a cor média). Usado pelo modo Ambilight da fita.
     */
    fun amostrarCor(aoCor: (Int) -> Unit) {
        if (!player.primeiroQuadro) { aoCor(Color.BLACK); return }      // carregando outro vídeo: tela preta, fita apagada
        if (amostrando || !player.tocando || player.carregando || cortinaForcada) return
        amostrando = true
        try {
            android.view.PixelCopy.request(imagem, amostra, { resultado ->
                amostrando = false
                if (resultado != android.view.PixelCopy.SUCCESS) return@request
                var r = 0L; var g = 0L; var b = 0L
                val px = IntArray(48 * 27)
                amostra.getPixels(px, 0, 48, 0, 0, 48, 27)
                for (p in px) { r += Color.red(p); g += Color.green(p); b += Color.blue(p) }
                val n = px.size
                // Cor média do quadro, com mais saturação e brilho: muda de forma contínua (a "cor que mais aparece" pulava de uma
                // cor para outra entre dois quadros e a fita piscava). Tela preta: brilho zero, a fita apaga.
                val hsv = FloatArray(3)
                Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
                hsv[1] = Math.min(1f, hsv[1] * 1.5f)
                hsv[2] = Math.min(1f, hsv[2] * 2f)
                // um quadro preto isolado (a cópia da imagem às vezes vem vazia) não pode apagar a fita: só vale se se repetir
                if (hsv[2] < 0.05f) { pretosSeguidos++; if (pretosSeguidos < 3) return@request } else pretosSeguidos = 0
                aoCor(Color.HSVToColor(hsv))
            }, handler)
        } catch (e: Exception) { amostrando = false }
    }

    /** Sai por esmaecimento, mostrando o que está embaixo; [fim] roda quando a tela já sumiu. */
    fun esmaecerSaida(fim: () -> Unit) {
        player.ligarImagem(null)
        imagem.visibility = View.GONE; cortinaForcada = true; cortina.visibility = View.VISIBLE
        animate().alpha(0f).setDuration(380).withEndAction { fim(); alpha = 1f; imagem.visibility = View.VISIBLE }.start()
    }

    fun fechar() {
        esperando = false
        cortinaForcada = false
        handler.removeCallbacks(tique); handler.removeCallbacks(esconder)
        player.ligarImagem(null)
        visibility = View.GONE
    }

    private fun mostrarControles(mostrar: Boolean) {
        controlesVisiveis = mostrar
        handler.removeCallbacks(esconder)
        controles.animate().cancel()
        if (mostrar) { controles.visibility = View.VISIBLE; controles.animate().alpha(1f).setDuration(150).start(); agendarEsconder() }
        else controles.animate().alpha(0f).setDuration(220).withEndAction { if (!controlesVisiveis) controles.visibility = View.GONE }.start()
    }

    private fun agendarEsconder() {
        handler.removeCallbacks(esconder)
        if (player.tocando) handler.postDelayed(esconder, 3500)
    }

    private fun formatar(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)
    }

    private fun atualizarTempo() {
        val dur = player.duracaoMs
        tempoAtual.text = formatar(player.posicaoMs)
        if (lateralAberta) tempoCanto.text = formatar(player.posicaoMs) + (if (dur > 0) " / " + formatar(dur) else "")
        tempoTotal.text = if (dur > 0) " / " + formatar(dur) else ""
        barra.fracao = if (dur > 0) player.posicaoMs.toFloat() / dur else 0f
        barra.ativa = dur > 0
    }

    /** Chamada quando o player muda de estado. */
    fun atualizar() {
        if (!aberto || esperando) return
        cortina.visibility = if (cortinaForcada || !player.primeiroQuadro) View.VISIBLE else View.GONE
        val r = player.item
        titulo.text = r?.titulo ?: ""
        canal.text = listOfNotNull(r?.canal?.takeIf { it.isNotEmpty() }, player.infoExtra.takeIf { it.isNotEmpty() }).joinToString("  ·  ")
        iconePlay.setImageResource(if (player.tocando) R.drawable.ic_pausar else R.drawable.ic_tocar)
        val erro = player.erro
        rodinha.visibility = if (player.carregando && erro == null) View.VISIBLE else View.GONE
        mensagem.visibility = if (erro != null) View.VISIBLE else View.GONE
        textoErro.text = erro ?: ""
        botaoAnterior?.alpha = if (player.temAnterior || player.posicaoMs > 4000) 1f else .4f
        botaoProxima?.alpha = if (player.temProxima) 1f else .4f
        botaoLista?.visibility = if (player.fila.size > 1 && !lateralAberta) View.VISIBLE else View.GONE
        // vídeo muito longo (mais de 10 min) não é uma música: o botão da nota fica indisponível
        seletor.musicaDisponivel = player.duracaoMs <= 0 || player.duracaoMs <= 600_000L
        botaoPlay.visibility = View.VISIBLE      // sempre aparece: pausa/play enquanto toca; play no meio quando parado ou carregando
        if (erro != null || player.carregando) { mostrarControles(true); controles.alpha = 1f }
        else if (player.tocando) agendarEsconder()
        atualizarTempo()
    }
}
