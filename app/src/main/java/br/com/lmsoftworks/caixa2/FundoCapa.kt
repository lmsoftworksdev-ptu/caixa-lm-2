package br.com.lmsoftworks.caixa2

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/** Liga o fundo com a capa desfocada na tela Tocando agora. Falso volta ao fundo de manchas de luz de sempre. */
const val FUNDO_COM_CAPA = false

/**
 * Fundo da tela Tocando agora (estilo YouTube Music na TV): a capa da música muito desfocada, ocupando a tela inteira e deslizando bem
 * devagar da direita para a esquerda e de volta. Fica atrás do [FundoView] (as manchas de luz passam por cima dela); só aparece na tela de música ([exibir]).
 *
 * O desfoque é feito UMA vez por capa, numa imagem pequena (120 px de altura) fora da thread principal; depois a imagem é só ampliada pela placa
 * de vídeo e a faixa (duas cópias da capa, ~1,6x a largura da tela) anda por translação (sem redesenhar nada). Sem capa, não mostra nada e o fundo de manchas
 * aparece. Enquanto a capa cobre a tela, [aoCobrir] avisa para o fundo de manchas ficar transparente e passar por cima dela.
 */
class FundoCapa(c: Context) : FrameLayout(c) {
    private val faixa = FrameLayout(c)
    private var origem: Bitmap? = null          // a capa que está (ou vai estar) no fundo
    private var geracao = 0                      // descarta desfoques de capas que já foram trocadas
    private var animador: ObjectAnimator? = null
    private var exibindo = false
    private var cobrindo = false
    private var razao = 2.8f                     // largura / altura da faixa que anda

    /** Chamado quando há capa atrás do fundo de manchas (verdadeiro) ou deixa de haver (falso). */
    var aoCobrir: ((Boolean) -> Unit)? = null

    init {
        alpha = 0f
        visibility = View.GONE
        addView(faixa, LayoutParams(-1, -1))
    }

    /** Mostra ou esconde o fundo (a tela de música está aberta ou não): esmaece e, escondido, a animação para. */
    fun exibir(sim: Boolean) {
        if (!FUNDO_COM_CAPA || sim == exibindo) return
        exibindo = sim
        animate().cancel()
        if (sim) {
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(450).withEndAction { verificar() }.start()
        } else {
            animate().alpha(0f).setDuration(350).withEndAction { if (!exibindo) visibility = View.GONE; verificar() }.start()
        }
        verificar()
    }

    /** Troca a capa do fundo (null = sem capa): a nova entra em esmaecimento por cima da antiga. Pode ser chamada à vontade; a mesma capa não faz nada. */
    fun definir(capa: Bitmap?) {
        if (!FUNDO_COM_CAPA || capa === origem) return
        origem = capa
        val minha = ++geracao
        if (capa == null) { sair(); return }
        val proporcao = razao
        Thread {
            val pronta = try { desfocar(capa, proporcao) } catch (e: Throwable) { null }
            post { if (minha == geracao && pronta != null) entrar(pronta, minha) }
        }.start()
    }

    private fun entrar(imagem: Bitmap, minha: Int) {
        val camada = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(imagem); alpha = 0f }
        faixa.addView(camada, LayoutParams(-1, -1))
        camada.animate().alpha(1f).setDuration(900).withEndAction {
            // a nova já está inteira: as de baixo saem da árvore
            if (minha == geracao) {
                while (faixa.childCount > 1 && faixa.getChildAt(0) !== camada) faixa.removeViewAt(0)
                verificar()
            }
        }.start()
        verificar()
    }

    /** Sem capa: as camadas esmaecem e o fundo de manchas volta a aparecer. */
    private fun sair() {
        for (i in 0 until faixa.childCount) {
            val v = faixa.getChildAt(i)
            v.animate().alpha(0f).setDuration(600).withEndAction { faixa.removeView(v); verificar() }.start()
        }
        verificar()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w <= 0) return
        // duas cópias da capa (uma normal e uma espelhada), cada uma com 1,4x a altura da tela: sobra ~0,57 da largura para andar
        val larg = maxOf((h * 1.4f).toInt() * 2, (w * 1.4f).toInt())
        faixa.layoutParams = LayoutParams(larg, h)
        razao = larg.toFloat() / h
        animador?.cancel()
        // ida e volta lentas (25 s por trecho), com partida e chegada suaves; o sentido da ida é da direita para a esquerda
        animador = ObjectAnimator.ofFloat(faixa, View.TRANSLATION_X, 0f, -(larg - w).toFloat()).apply {
            duration = 25_000
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
        verificar()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); verificar() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); verificar() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); verificar() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); verificar() }

    /** Só anima com a tela de música à vista e com capa; senão fica parado (sem gastar processador). */
    private fun verificar() {
        val aparece = exibindo && isAttachedToWindow && isShown && windowVisibility == View.VISIBLE
        val a = animador
        if (a != null) {
            if (aparece && faixa.childCount > 0) { if (!a.isStarted) a.start() else if (a.isPaused) a.resume() }
            else if (a.isStarted && !a.isPaused) a.pause()
        }
        // com capa a caminho (ou já na tela), o fundo de manchas fica transparente e as manchas passam por cima dela
        val cobre = aparece && origem != null
        if (cobre != cobrindo) { cobrindo = cobre; aoCobrir?.invoke(cobre) }
    }

    // ----- desfoque -----

    /**
     * Capa -> imagem pequena e suavizada, na proporção da faixa (sem esticar a capa): a capa quadrada é ampliada um pouco além da altura
     * e repetida lado a lado, cada cópia espelhada em relação à vizinha (sem emenda), e só então vem o desfoque (forte, mas ainda se percebe a capa). As cores são realçadas e o
     * brilho levado para um nível escuro (o texto e os botões continuam legíveis).
     */
    private fun desfocar(capa: Bitmap, razao: Float): Bitmap {
        val h = 120
        val w = (h * razao).toInt().coerceIn(h, 4 * h)
        val lado = (h * 1.4f).toInt()
        // dois passos de redução, para não "pular" pixels da capa grande
        val base = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(capa, 256, 256, true), lado, lado, true)
        val largo = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val tela = Canvas(largo)
        val topo = -(lado - h) / 2f
        var x = 0
        var i = 0
        while (x < w) {
            val m = Matrix()
            if (i % 2 == 0) m.setTranslate(x.toFloat(), topo) else { m.setScale(-1f, 1f); m.postTranslate((x + lado).toFloat(), topo) }
            tela.drawBitmap(base, m, null)
            x += lado; i++
        }
        var a = IntArray(w * h)
        largo.getPixels(a, 0, w, 0, 0, w, h)
        var b = IntArray(w * h)
        repeat(3) {
            passada(a, b, w, h, 6, true)
            passada(b, a, w, h, 6, false)
        }
        // realça as cores (o desfoque as deixava acinzentadas) e leva o brilho médio para perto de um alvo escuro: capa clara escurece
        // bastante, capa escura só clareia um pouco; assim o texto branco lê bem com qualquer capa
        var soma = 0f
        for (p in a) soma += lum(p)
        val media = (soma / a.size).coerceAtLeast(.02f)
        val ganho = (.24f / media).coerceIn(.45f, 1.3f)
        for (k in a.indices) {
            val p = a[k]
            val l = lum(p)
            fun canal(v: Int) = (((l * 255f + (v - l * 255f) * 1.25f) * ganho)).toInt().coerceIn(0, 255)
            a[k] = Color.rgb(canal(Color.red(p)), canal(Color.green(p)), canal(Color.blue(p)))
        }
        val fim = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fim.setPixels(a, 0, w, 0, 0, w, h)
        return fim
    }

    private fun lum(p: Int) = (Color.red(p) * .299f + Color.green(p) * .587f + Color.blue(p) * .114f) / 255f

    /** Uma passada de desfoque de caixa (média de 2·raio+1 vizinhos, bordas repetidas) numa direção. */
    private fun passada(de: IntArray, para: IntArray, larg: Int, alt: Int, raio: Int, horizontal: Boolean) {
        val n = if (horizontal) larg else alt
        val linhas = if (horizontal) alt else larg
        val passoI = if (horizontal) 1 else larg
        val passoL = if (horizontal) larg else 1
        val dividir = 2 * raio + 1
        for (l in 0 until linhas) {
            val base = l * passoL
            for (i in 0 until n) {
                var r = 0; var g = 0; var bl = 0
                for (k in -raio..raio) {
                    val p = de[base + (i + k).coerceIn(0, n - 1) * passoI]
                    r += Color.red(p); g += Color.green(p); bl += Color.blue(p)
                }
                para[base + i * passoI] = Color.rgb(r / dividir, g / dividir, bl / dividir)
            }
        }
    }
}
