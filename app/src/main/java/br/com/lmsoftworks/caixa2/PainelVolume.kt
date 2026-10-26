package br.com.lmsoftworks.caixa2

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Controle do volume de verdade do aparelho (canal de música: a música do Bluetooth, vídeos e sons).
 * Ao tocar no botão da barra, uma pílula se abre PARA A DIREITA, na própria linha de cima (sem descer nem cobrir a tela):
 * mudo, "−", barra fina, "+" e a porcentagem. Fecha ao tocar fora. Esta classe é só o "tapa-toque" invisível que fecha
 * ao tocar fora; a pílula em si é [pilula], que a [BarraTopo] coloca ao lado do botão.
 * [aoMudar] recebe a porcentagem e se está mudo, para a barra de cima desenhar o ícone certo.
 */
class PainelVolume(private val c: Context, private val aoMudar: (Int, Boolean) -> Unit) : FrameLayout(c) {

    companion object {
        /** O amplificador da caixa distorce acima de 87% do volume do aparelho: o 100% do app é esse 87% real. */
        const val LIMITE_REAL = 0.87f
        private const val LARGURA_DP = 500
        private const val ALTURA_DP = 66
    }

    private val audio = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val canal = AudioManager.STREAM_MUSIC
    private val barra = Deslizante(c, false, true)
    private val rotulo: TextView = texto(c, "", 22, Color.WHITE, true)
    private val botaoMudo = BotaoRedondo(c, R.drawable.ic_volume_2, "Mudo", 30)
    private var anterior = 0
    private var registrado = false
    private val handler = Handler(Looper.getMainLooper())
    private val fecharSozinho = Runnable { fechar() }
    private var ultimoPorcento = -1
    private var animador: ValueAnimator? = null
    private var abrindo = false
    /** Quem apertou "+" (ou a tecla) já no volume máximo: o app toca o aviso. */
    var aoNoLimite: (() -> Unit)? = null
    /** O controle abriu (por toque ou por mudança de fora): a tela sai do modo ocioso. */
    var aoMostrar: (() -> Unit)? = null

    /** A pílula que fica na barra, ao lado do botão de volume (largura 0 = recolhida). */
    val pilula = FrameLayout(c)

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", canal) != canal) return
            val antes = ultimoPorcento
            atualizar()
            // mudou por fora (celular que toca, tecla): mostra o nosso controle no lugar do indicador do Android
            if (antes >= 0 && ultimoPorcento != antes) mostrarTemporario()
        }
    }

    private val limite: Int get() = Math.round(audio.getStreamMaxVolume(canal) * LIMITE_REAL).coerceAtLeast(1)

    val porcento: Int get() = Math.round(audio.getStreamVolume(canal).coerceAtMost(limite) * 100f / limite)

    init {
        visibility = View.GONE
        // fora da pílula: um toque em qualquer lugar fecha
        addView(View(c).apply { isClickable = true; setOnClickListener { fechar() } }, LayoutParams(-1, -1))

        val menos = BotaoRedondo(c, R.drawable.ic_menos, "Diminuir volume", 30).apply { pintar(Color.WHITE, false); setOnClickListener { passo(-1) } }
        val mais = BotaoRedondo(c, R.drawable.ic_mais, "Aumentar volume", 30).apply { pintar(Color.WHITE, false); setOnClickListener { passo(+1) } }
        botaoMudo.setOnClickListener { alternarMudo() }
        barra.aoMudar = { f, _ -> definir(f) }
        botaoMudo.pintar(Color.WHITE, false)
        // dentro da pílula os botões não têm círculo próprio: só o ícone
        for (b in listOf(botaoMudo, menos, mais)) b.background = null

        val conteudo = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.d(), 0, 20.d(), 0)
            addView(botaoMudo, LinearLayout.LayoutParams(54.d(), 54.d()))
            addView(menos, LinearLayout.LayoutParams(54.d(), 54.d()))
            addView(barra, LinearLayout.LayoutParams(0, ALTURA_DP.d(), 1f).apply { leftMargin = 4.d(); rightMargin = 4.d() })
            addView(mais, LinearLayout.LayoutParams(54.d(), 54.d()))
            addView(rotulo, LinearLayout.LayoutParams(74.d(), LayoutParams.WRAP_CONTENT))
            rotulo.gravity = Gravity.END
        }
        // o conteúdo tem largura fixa; só a pílula cresce, e o que passa da borda é cortado (efeito de abrir)
        pilula.apply {
            background = fundoArredondado(Color.argb(0x12, 255, 255, 255), (ALTURA_DP / 2).d(), Cfg.LINHA)
            visibility = View.GONE
            alpha = 0f
            addView(conteudo, FrameLayout.LayoutParams(LARGURA_DP.d(), ALTURA_DP.d()))
        }
    }

    fun iniciar() {
        if (!registrado) {
            registrado = true
            c.registerReceiver(receptor, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        }
        atualizar()
    }

    fun parar() {
        if (registrado) { registrado = false; try { c.unregisterReceiver(receptor) } catch (e: Exception) { } }
    }

    val aberto: Boolean get() = abrindo

    fun alternar() { if (aberto) fechar() else mostrarTemporario() }

    /** Abre a pílula e fecha sozinha depois de uns segundos sem mexer. */
    fun mostrarTemporario() {
        aoMostrar?.invoke()
        atualizar()
        if (!abrindo) animar(true)
        handler.removeCallbacks(fecharSozinho)
        handler.postDelayed(fecharSozinho, 4000)
    }

    fun fechar() {
        handler.removeCallbacks(fecharSozinho)
        if (abrindo) animar(false)
    }

    private fun animar(abrir: Boolean) {
        abrindo = abrir
        animador?.cancel()
        if (abrir) { pilula.visibility = View.VISIBLE; visibility = View.VISIBLE }
        val de = (pilula.layoutParams?.width ?: 0).coerceAtLeast(0)
        val para = if (abrir) LARGURA_DP.d() else 0
        animador = ValueAnimator.ofInt(de, para).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val w = it.animatedValue as Int
                pilula.layoutParams = pilula.layoutParams.apply { width = w }
                pilula.alpha = (w.toFloat() / LARGURA_DP.d()).coerceIn(0f, 1f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (!abrindo) { pilula.visibility = View.GONE; visibility = View.GONE }
                }
            })
            start()
        }
    }

    private fun definir(fracao: Float) {
        audio.setStreamVolume(canal, Math.round(fracao * limite).coerceIn(0, limite), 0)
        atualizar()
    }

    /** Volume em níveis de 0 a 10 (é o que a tela mostra e o que se pede por voz: "volume 7"). */
    val nivel: Int get() = Math.round(porcento / 10f)

    /** Cada toque em + ou − (e cada tecla) muda um NÍVEL (de 0 a 10). */
    private fun passo(sentido: Int) {
        if (sentido > 0 && nivel >= 10) aoNoLimite?.invoke()
        definir((nivel + sentido).coerceIn(0, 10) / 10f)
    }

    fun definirNivel(n: Int) {
        definir(n.coerceIn(0, 10) / 10f)
        mostrarTemporario()
    }

    /** Define o volume em porcentagem (0 a 100, em relação ao limite) e mostra a pílula. Usado pelo assistente de voz. */
    fun definirPorcento(p: Int) {
        definir(p.coerceIn(0, 100) / 100f)
        mostrarTemporario()
    }

    fun alternarMudo() {
        val atual = audio.getStreamVolume(canal)
        if (atual > 0) { anterior = atual; audio.setStreamVolume(canal, 0, 0) }
        else audio.setStreamVolume(canal, if (anterior > 0) anterior.coerceAtMost(limite) else limite / 2, 0)
        atualizar()
    }

    /** Ajusta o volume (tecla do aparelho) sem o indicador do Android e mostra o nosso controle. */
    fun ajustarPorTecla(sentido: Int) {
        passo(if (sentido > 0) 1 else -1)
        mostrarTemporario()
    }

    /** Lê o volume real do aparelho (ele também muda pelo celular que toca a música) e redesenha. */
    fun atualizar() {
        // o celular que toca pode mandar 100% (volume absoluto do Bluetooth): trazemos de volta para o limite
        if (audio.getStreamVolume(canal) > limite) audio.setStreamVolume(canal, limite, 0)
        val p = porcento
        val mudo = audio.getStreamVolume(canal) == 0
        ultimoPorcento = p
        barra.valor = p / 100f
        rotulo.text = "$nivel"
        botaoMudo.trocarIcone(if (mudo) R.drawable.ic_volume_x else R.drawable.ic_volume_2)
        aoMudar(p, mudo)
        if (abrindo) { handler.removeCallbacks(fecharSozinho); handler.postDelayed(fecharSozinho, 4000) }
    }
}
