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
        const val LIMITE_REAL = 1.0f
        const val NIVEL_ABAFADO = 2
        private const val LARGURA_DP = 540
        private const val ALTURA_DP = 76
    }

    private val audio = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val canal = AudioManager.STREAM_MUSIC
    private val barra = BarraVolume(c)
    private val rotulo: TextView = texto(c, "", 30, Color.WHITE, true)
    private val botaoMudo = BotaoRedondo(c, R.drawable.ic_volume_2, "Mudo", 36)
    private var anterior = 0
    private var registrado = false
    private val handler = Handler(Looper.getMainLooper())
    private val fecharSozinho = Runnable { fechar() }
    private var ultimoPorcento = -1
    private var animador: ValueAnimator? = null
    private var abrindo = false
    /** Toques seguidos fora da pílula (zera quando se mexe no volume ou ela abre de novo). */
    private var toquesFora = 0
    private var abafado = -1
    private var abafadoAlvo = -1
    private var ignorarAte = 0L

    /**
     * Baixa o volume do aparelho (a música do celular) enquanto o assistente ouve e fala, e devolve depois. Não abre a pílula
     * nem mexe na barra: é só um abaixar temporário. Só devolve se ninguém mexeu no volume nesse meio-tempo.
     */
    /** Mudanças de volume feitas pelo app (voz do assistente): a barra e a pílula não reagem nos próximos instantes. */
    fun ignorarMudancas() { ignorarAte = android.os.SystemClock.uptimeMillis() + 1500 }

    /** Põe o volume do aparelho no "Volume da voz" enquanto o assistente está na tela; [abafarSistema] (false) devolve depois. */
    fun volumeDaVoz() {
        if (abafado >= 0) return
        val atual = audio.getStreamVolume(canal)
        val daVoz = Math.round(audio.getStreamMaxVolume(canal) * Ajustes.volumeVoz / 10f).coerceAtLeast(1)
        abafado = atual; abafadoAlvo = daVoz
        ignorarAte = android.os.SystemClock.uptimeMillis() + 1500
        audio.setStreamVolume(canal, daVoz, 0)
    }

    /** [fade] falso: devolve o volume na hora (usado quando a música ainda está pausada e vai voltar com o fade dela). */
    fun abafarSistema(sim: Boolean, fade: Boolean = true) {
        if (sim) {
            if (abafado >= 0) return
            val atual = audio.getStreamVolume(canal)
            if (atual <= NIVEL_ABAFADO) return
            abafado = atual
            abafadoAlvo = NIVEL_ABAFADO
            degrau(atual, NIVEL_ABAFADO)
        } else if (abafado >= 0) {
            val volta = abafado
            abafado = -1
            // só devolve se ninguém mexeu no volume durante o abafado: o volume está onde o app o deixou (nível abafado ou da voz)
            val atual = audio.getStreamVolume(canal)
            if (atual == abafadoAlvo || atual <= NIVEL_ABAFADO) {
                if (fade) degrau(atual, volta)
                else { ignorarAte = android.os.SystemClock.uptimeMillis() + 1500; audio.setStreamVolume(canal, volta, 0); handler.postDelayed({ atualizar() }, 200) }
            }
            else handler.postDelayed({ atualizar() }, 200)
        }
    }

    /**
     * Música do celular pausada pelo assistente: (1) fade-out do volume até quase zero e [aoSilencio] (aí o app pede a pausa);
     * (2) [pausaMeio] devolve o volume do sistema, já com a música parada, para a voz sair nele; (3) [pausaFim] a música volta
     * e o volume sobe em fade até o que estava.
     */
    fun pausaInicio(aoSilencio: () -> Unit) {
        val atual = audio.getStreamVolume(canal)
        if (abafado >= 0 || atual <= 1) { aoSilencio(); return }
        abafado = atual; abafadoAlvo = 1
        degrau(atual, 1, 60L)
        handler.postDelayed({ aoSilencio() }, (atual - 1) * 60L + 100L)
    }

    /** [nivel] só quando a pausa não foi concedida: a música segue tocando, então fica abaixada nesse nível. */
    fun pausaMeio(nivel: Int = -1) {
        if (abafado < 0) return
        handler.removeCallbacks(passoFade)
        ignorarAte = android.os.SystemClock.uptimeMillis() + 1500
        // com a música parada a voz (e os toquinhos) saem no "Volume da voz" de Configurações
        val daVoz = Math.round(audio.getStreamMaxVolume(canal) * Ajustes.volumeVoz / 10f).coerceAtLeast(1)
        audio.setStreamVolume(canal, if (nivel >= 0) Math.min(nivel, abafado) else daVoz, 0)
        if (nivel >= 0) abafadoAlvo = Math.min(nivel, abafado)
    }

    fun pausaFim() {
        if (abafado < 0) return
        val volta = abafado
        abafado = -1
        handler.removeCallbacks(passoFade)
        ignorarAte = android.os.SystemClock.uptimeMillis() + 1500
        audio.setStreamVolume(canal, 1, 0)
        degrau(1, volta, 150L)
    }

    /** Fade do volume do aparelho: um nível a cada 120 ms, sem abrir a pílula. */
    private fun degrau(de: Int, para: Int, passoMs: Long = 120L) {
        handler.removeCallbacks(passoFade)
        fadeAtual = de; fadeAlvo = para; fadeMs = passoMs
        passoFade.run()
    }
    private var fadeAtual = 0
    private var fadeAlvo = 0
    private var fadeMs = 120L
    private val passoFade = object : Runnable {
        override fun run() {
            ignorarAte = android.os.SystemClock.uptimeMillis() + 1500
            if (fadeAtual != fadeAlvo) fadeAtual += if (fadeAlvo > fadeAtual) 1 else -1
            audio.setStreamVolume(canal, fadeAtual, 0)
            if (fadeAtual != fadeAlvo) handler.postDelayed(this, fadeMs)
            else handler.postDelayed({ if (abafado < 0) atualizar() }, 300)     // fade acabou: mostra o número final de uma vez
        }
    }
    /** Quem apertou "+" (ou a tecla) já no volume máximo: o app toca o aviso. */
    var aoNoLimite: (() -> Unit)? = null
    /** O controle abriu (por toque ou por mudança de fora): a tela sai do modo ocioso. */
    var aoMostrar: (() -> Unit)? = null

    /** A pílula que fica na barra, ao lado do botão de volume (largura 0 = recolhida). */
    val pilula = FrameLayout(c)

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", canal) != canal) return
            // mudanças do próprio app (assistente abaixando/voltando o volume) não aparecem na tela
            if (abafado >= 0 || android.os.SystemClock.uptimeMillis() < ignorarAte) return
            // mudou por fora (celular que toca): só atualiza o número; a pílula abre apenas quando se toca no volume ou numa tecla
            atualizar()
        }
    }

    private val limite: Int get() = Math.round(audio.getStreamMaxVolume(canal) * LIMITE_REAL).coerceAtLeast(1)

    val porcento: Int get() = Math.round(audio.getStreamVolume(canal).coerceAtMost(limite) * 100f / limite)

    init {
        visibility = View.GONE
        // fora da pílula: um toque só NÃO fecha (a bolinha é pequena e errar o toque é comum); o segundo toque seguido fora fecha
        addView(View(c).apply {
            isClickable = true
            setOnClickListener {
                toquesFora++
                if (toquesFora >= 2) fechar()
                else { handler.removeCallbacks(fecharSozinho); handler.postDelayed(fecharSozinho, 4000) }
            }
        }, LayoutParams(-1, -1))

        val menos = BotaoRedondo(c, R.drawable.ic_menos, "Diminuir volume", 36).apply { pintar(Color.WHITE, false); setOnClickListener { passo(-1) } }
        val mais = BotaoRedondo(c, R.drawable.ic_mais, "Aumentar volume", 36).apply { pintar(Color.WHITE, false); setOnClickListener { passo(+1) } }
        botaoMudo.setOnClickListener { alternarMudo() }
        barra.aoMudar = { f -> definir(Math.round(f * 10) / 10f) }
        botaoMudo.pintar(Color.WHITE, false)
        // dentro da pílula os botões não têm círculo próprio: só o ícone
        for (b in listOf(botaoMudo, menos, mais)) b.background = null

        // o botão de volume da barra (64 px) fica sobre o começo da pílula; o conteúdo começa depois dele
        val conteudo = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(c), LinearLayout.LayoutParams(76.d(), 1))
            addView(botaoMudo, LinearLayout.LayoutParams(56.d(), ALTURA_DP.d()))
            addView(menos, LinearLayout.LayoutParams(56.d(), ALTURA_DP.d()))
            addView(barra, LinearLayout.LayoutParams(0, ALTURA_DP.d(), 1f).apply { leftMargin = 6.d(); rightMargin = 6.d() })
            addView(mais, LinearLayout.LayoutParams(56.d(), ALTURA_DP.d()))
            addView(rotulo, LinearLayout.LayoutParams(56.d(), LayoutParams.WRAP_CONTENT))
            rotulo.gravity = Gravity.CENTER
        }
        // o conteúdo tem largura fixa; só a pílula cresce, e o que passa da borda é cortado (efeito de abrir)
        pilula.apply {
            background = fundoArredondado(Color.parseColor("#EB0D152C"), (ALTURA_DP / 2).d())
            visibility = View.GONE
            alpha = 0f
            addView(conteudo, FrameLayout.LayoutParams(LARGURA_DP.d(), ALTURA_DP.d()))
        }
    }

    fun iniciar() {
        if (Ajustes.volumeCaixa < 0) Ajustes.definirVolumeCaixa(porcento)
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
        toquesFora = 0
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
            duration = 380
            interpolator = Tema.CURVA
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

    private fun definir(fracao: Float, guardar: Boolean = true) {
        val idx = Math.round(fracao * limite).coerceIn(0, limite)
        // volume pedido com o assistente na tela (por voz): é o volume que vale quando ele fecha, e não o que estava antes
        if (abafado >= 0 && guardar) { abafado = idx; abafadoAlvo = idx }
        audio.setStreamVolume(canal, idx, 0)
        if (guardar) Ajustes.definirVolumeCaixa(Math.round(fracao * 100))
        atualizar()
    }

    /**
     * O volume da caixa prevalece sobre o do celular: quando o celular conecta ou começa a tocar ele manda o volume dele (volume
     * absoluto do Bluetooth); aqui o da caixa volta, e o celular acompanha. Depois disso, mexer no celular vale normalmente.
     */
    fun imporVolumeDaCaixa() {
        val alvo = Ajustes.volumeCaixa
        if (alvo < 0 || abafado >= 0) return
        val idx = Math.round(alvo / 100f * limite)
        if (audio.getStreamVolume(canal) == idx) return
        ignorarAte = android.os.SystemClock.uptimeMillis() + 1500
        audio.setStreamVolume(canal, idx, 0)
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

    /** Muda o volume (0 a 100) sem abrir a pílula: usado no fade do temporizador de sono. */
    fun definirSilencioso(p: Int, guardar: Boolean = false) = definir(p.coerceIn(0, 100) / 100f, guardar)

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
        toquesFora = 0
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

/** Barra do volume da v2: trilho de 10 px (branco 12%) e preenchimento branco; toque ou arrasto escolhe o nível. */
class BarraVolume(c: Context) : View(c) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    var valor = 0f
        set(v) { if (!arrastando) { field = v.coerceIn(0f, 1f); invalidate() } }
    var aoMudar: ((Float) -> Unit)? = null
    private var arrastando = false
    private var atual = 0f

    private fun fracaoDe(x: Float) = (x / width).coerceIn(0f, 1f)

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); arrastando = true; atual = fracaoDe(e.x); aoMudar?.invoke(atual); invalidate() }
            android.view.MotionEvent.ACTION_MOVE -> { atual = fracaoDe(e.x); aoMudar?.invoke(atual); invalidate() }
            android.view.MotionEvent.ACTION_UP -> { atual = fracaoDe(e.x); arrastando = false; valor = atual; aoMudar?.invoke(atual); invalidate() }
            android.view.MotionEvent.ACTION_CANCEL -> { arrastando = false; invalidate() }
        }
        return true
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val f = if (arrastando) atual else valor
        val meio = height / 2f
        val r = 2.d().toFloat()
        val bolinha = 15.d().toFloat()
        p.color = Color.parseColor("#29FFFFFF")
        canvas.drawRoundRect(0f, meio - r, width.toFloat(), meio + r, r, r, p)
        p.color = Color.WHITE
        canvas.drawRoundRect(0f, meio - r, width * f, meio + r, r, r, p)
        canvas.drawCircle((width * f).coerceIn(bolinha, width - bolinha), meio, bolinha, p)
    }
}
