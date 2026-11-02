package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Teclado único da v3 (Mídia, senha do Wi-Fi, lembretes e listas): teclas de 100 x 80 (raio 18, texto 30), 10 de espaço;
 * linhas de números, qwertyuiop, asdfghjklç, ⇧ zxcvbnm ⌫ e, embaixo, "?123" (150), espaço (560) e a ação (220, no destaque).
 */
class TecladoView(c: Context) : LinearLayout(c) {
    var aoDigitar: ((String) -> Unit)? = null
    var aoApagar: (() -> Unit)? = null
    /** Toque na tecla de ação (Buscar, Conectar, Salvar...). */
    var aoAcao: (() -> Unit)? = null

    /** Rótulo da tecla de ação. */
    var acao: String = "OK"
        set(v) { if (v != field || Cfg.AZUL != corMontada) { field = v; montar() } }      // só refaz se mudou o rótulo ou a cor do menu

    private var maiuscula = false
    private var simbolos = false
    private var corMontada = Cfg.AZUL
    /** As teclas de letra (e o ⇧): trocar entre maiúscula e minúscula só troca o texto delas, sem refazer o teclado inteiro. */
    private val letras = ArrayList<Pair<TextView, Char>>()
    private var teclaShift: TextView? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        montar()
    }

    companion object {
        private val visiveis = HashSet<TecladoView>()
        /** Avisa (true/false) quando o primeiro teclado aparece e quando o último some: o fundo animado para enquanto se digita. */
        var aoMudarVisibilidade: ((Boolean) -> Unit)? = null
    }

    private fun conferirVisibilidade() {
        val antes = visiveis.isNotEmpty()
        if (isShown && isAttachedToWindow) visiveis.add(this) else visiveis.remove(this)
        val agora = visiveis.isNotEmpty()
        if (agora != antes) aoMudarVisibilidade?.invoke(agora)
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); conferirVisibilidade() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); conferirVisibilidade() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); val antes = visiveis.isNotEmpty(); visiveis.remove(this); if (antes && visiveis.isEmpty()) aoMudarVisibilidade?.invoke(false) }

    /** Liga o ⇧ (a próxima letra sai maiúscula): usado quando o texto está vazio. */
    fun capitalizar(sim: Boolean) { if (maiuscula != sim) { maiuscula = sim; atualizarLetras() } }

    private fun tecla(rotulo: String, largura: Int, destaque: Boolean = false, ativa: Boolean = false, tamanho: Int = 30, repetir: Boolean = false, acao: () -> Unit): TextView =
        TextView(context).apply {
            text = rotulo
            setTextColor(Tema.TEXTO)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, tamanho.d().toFloat())
            typeface = if (destaque) FONTE_MEDIA else FONTE_NORMAL
            gravity = Gravity.CENTER
            tag = ativa      // tecla "ligada" (o ⇧): fica na cor de destaque
            // os fundos são criados uma vez por estado (ligada ou não): criar dois a cada toque engasgava a digitação
            val fundosN = arrayOfNulls<android.graphics.drawable.Drawable>(2)
            val fundosP = arrayOfNulls<android.graphics.drawable.Drawable>(2)
            fun normal(v: View): android.graphics.drawable.Drawable { val i = if (v.tag == true) 1 else 0; return fundosN[i] ?: fundoArredondado(if (destaque || i == 1) Cfg.AZUL else Color.parseColor("#1AFFFFFF"), 18.d()).also { fundosN[i] = it } }
            fun premida(v: View): android.graphics.drawable.Drawable { val i = if (v.tag == true) 1 else 0; return fundosP[i] ?: fundoArredondado(if (destaque || i == 1) Cfg.AZUL else Color.parseColor("#47FFFFFF"), 18.d()).also { fundosP[i] = it } }
            background = normal(this)
            isClickable = true
            // tecla que repete (apagar): segurando, depois de um instante ela se repete sozinha e vai acelerando
            var repetiu = false
            var intervalo = 90L
            val repeticao = object : Runnable {
                override fun run() {
                    repetiu = true
                    acao(); SomTeclado.tocar()
                    intervalo = Math.max(35L, (intervalo * 0.92).toLong())
                    postDelayed(this, intervalo)
                }
            }
            setOnClickListener { if (repetiu) repetiu = false else acao() }
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.background = premida(v); SomTeclado.tocar()
                        if (repetir) { repetiu = false; intervalo = 90L; removeCallbacks(repeticao); postDelayed(repeticao, 420) }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.background = normal(v); removeCallbacks(repeticao) }
                }
                false
            }
            layoutParams = LayoutParams(largura.d(), 80.d()).apply { setMargins(5.d(), 0, 5.d(), 0) }
        }

    private fun linha(vararg itens: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            itens.forEach { addView(it) }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 5.d(); bottomMargin = 5.d() }
        }

    private fun caracteres(s: String): Array<View> =
        s.map { ch ->
            val mostrado = if (!simbolos && maiuscula) ch.uppercaseChar() else ch
            val t = tecla(mostrado.toString(), 100) {
                val digitado = if (!simbolos && maiuscula) ch.uppercaseChar() else ch
                aoDigitar?.invoke(digitado.toString())
                if (maiuscula) { maiuscula = false; atualizarLetras() }
            }
            if (!simbolos) letras.add(t to ch)
            t as View
        }.toTypedArray()

    /** Maiúscula ligada ou desligada: só troca o texto das letras e a cor do ⇧ (bem mais leve que refazer o teclado). */
    private fun atualizarLetras() {
        for ((t, ch) in letras) t.text = (if (maiuscula) ch.uppercaseChar() else ch).toString()
        teclaShift?.let { it.tag = maiuscula; it.background = fundoArredondado(if (maiuscula) Cfg.AZUL else Color.parseColor("#1AFFFFFF"), 18.d()) }
    }

    private fun montar() {
        corMontada = Cfg.AZUL
        letras.clear(); teclaShift = null
        removeAllViews()
        val apagar = tecla("⌫", 150, repetir = true) { aoApagar?.invoke() }
        if (simbolos) {
            addView(linha(*caracteres("1234567890")))
            addView(linha(*caracteres("!@#$%^&*()")))
            addView(linha(*caracteres("-_=+[]{}:;")))
            addView(linha(*caracteres("'\"<>,.?/"), apagar))
        } else {
            addView(linha(*caracteres("1234567890")))
            addView(linha(*caracteres("qwertyuiop")))
            addView(linha(*caracteres("asdfghjklç")))
            val shift = tecla("⇧", 150, ativa = maiuscula) { maiuscula = !maiuscula; atualizarLetras() }
            teclaShift = shift
            addView(linha(shift, *caracteres("zxcvbnm"), apagar))
        }
        val modo = tecla(if (simbolos) "abc" else "?123", 150, tamanho = 26) { simbolos = !simbolos; montar() }
        val espaco = tecla("", 560) { aoDigitar?.invoke(" ") }
        val acaoTecla = tecla(acao, 220, destaque = true, tamanho = 28) { aoAcao?.invoke() }
        addView(linha(modo, espaco, acaoTecla))
    }
}

/**
 * Toquinho de tecla: um estalo curto de 18 ms gerado no próprio app (gravado uma vez como WAV) e tocado por um SoundPool. O SoundPool toca na
 * hora, sem travar a thread da tela, e várias teclas seguidas tocam juntas sem se cortar (as AudioTrack paradas e recarregadas a cada tecla
 * atrasavam o som quando se digitava rápido).
 */
object SomTeclado {
    private const val TAXA = 22050
    private var pool: android.media.SoundPool? = null
    private var id = 0
    @Volatile private var pronto = false

    private fun gerarWav(): ByteArray {
        val n = TAXA * 18 / 1000
        val pcm = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val t = i / TAXA.toDouble()
            pcm.putShort((Math.sin(2 * Math.PI * 1700.0 * t) * Math.exp(-t * 260.0) * 9000).toInt().toShort())
        }
        val dados = pcm.array()
        val cab = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        cab.put("RIFF".toByteArray()).putInt(36 + dados.size).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        cab.putInt(16).putShort(1).putShort(1).putInt(TAXA).putInt(TAXA * 2).putShort(2).putShort(16)
        cab.put("data".toByteArray()).putInt(dados.size)
        return cab.array() + dados
    }

    /** Chamada ao abrir o app: grava o estalo e carrega no SoundPool. */
    fun iniciar(ctx: android.content.Context) {
        if (pool != null) return
        try {
            val arquivo = java.io.File(ctx.applicationContext.filesDir, "estalo.wav")
            if (!arquivo.isFile) arquivo.writeBytes(gerarWav())
            @Suppress("DEPRECATION")
            val p = android.media.SoundPool(6, android.media.AudioManager.STREAM_MUSIC, 0)
            p.setOnLoadCompleteListener { _, _, status -> pronto = status == 0 }
            id = p.load(arquivo.absolutePath, 1)
            pool = p
        } catch (e: Throwable) { }
    }

    // O SoundPool.play() bloqueia a thread que o chama enquanto o áudio do aparelho responde (medido na caixa: a thread da tela ficava parada em
    // play() durante a digitação, e era isso que travava as teclas e o fundo). Por isso toca numa thread própria; se o áudio atrasar, os
    // estalos que não deram tempo de tocar são descartados em vez de formar fila.
    private val pendentes = java.util.concurrent.atomic.AtomicInteger(0)
    private val fila = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); r.run() } }

    fun tocar() {
        if (!Ajustes.sonsTeclado || !pronto) return
        if (pendentes.get() >= 2) return
        pendentes.incrementAndGet()
        fila.execute {
            try { pool?.play(id, 1f, 1f, 1, 0, 1f) } catch (e: Throwable) { } finally { pendentes.decrementAndGet() }
        }
    }
}

/** Barrinha de digitação: um traço fino desenhado colado ao fim do texto (sem o espaço que o caractere "│" deixa dos dois lados). */
private class BarraDigitacao(private val visivel: Boolean) : android.text.style.ReplacementSpan() {
    private val largura = Math.max(2, 2.d())
    override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
        fm?.let { val m = paint.fontMetricsInt; it.ascent = m.ascent; it.descent = m.descent; it.top = m.top; it.bottom = m.bottom }
        return largura
    }
    override fun draw(canvas: android.graphics.Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
        if (!visivel) return
        val m = paint.fontMetrics
        val p = android.graphics.Paint(paint).apply { color = Color.WHITE; style = android.graphics.Paint.Style.FILL }
        canvas.drawRect(x, y + m.ascent * 0.92f, x + largura, y + m.descent * 0.9f, p)
    }
}

/** O texto de uma barra de pesquisa com a barrinha de digitação (piscando) no fim, enquanto o teclado está aberto. */
fun comCursor(texto: String, teclado: Boolean, visivel: Boolean): CharSequence {
    if (!teclado) return texto
    return android.text.SpannableStringBuilder(texto).append("​").apply {
        setSpan(BarraDigitacao(visivel), length - 1, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
