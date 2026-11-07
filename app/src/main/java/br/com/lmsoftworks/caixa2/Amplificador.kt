package br.com.lmsoftworks.caixa2

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log

/**
 * Equalizador da caixa, para TUDO que ela toca. O som do app (música e vídeo) passa por um equalizador de software dentro do player
 * ([EqualizadorSoftware]): sem estalo. O som do celular por Bluetooth, que o app não toca, é equalizado pelo Equalizer e pelo BassBoost do Android na
 * saída geral; esses efeitos só ficam ligados enquanto é o celular que está tocando (senão o som do app seria equalizado duas vezes e os efeitos
 * ligariam e desligariam a cada faixa). Ajustes guardados.
 */
object Amplificador {
    private const val TAG = "Amplificador"
    private const val ARQUIVO = "amplificador"

    class Predefinicao(val nome: String, val db: FloatArray)      // curva em dB, em 5 pontos (graves ... agudos); vale para qualquer número de bandas

    val PREDEFINICOES = listOf(
        Predefinicao("Plano", floatArrayOf(0f, 0f, 0f, 0f, 0f)),
        Predefinicao("Graves", floatArrayOf(7f, 5f, 0f, 0f, 0f)),
        Predefinicao("Voz", floatArrayOf(-3f, 0f, 4f, 5f, 1f)),
        Predefinicao("Sertanejo", floatArrayOf(1f, 3f, 4f, 4f, 2f)),
        Predefinicao("Pop", floatArrayOf(-1f, 3f, 5f, 3f, -1f)),
        Predefinicao("Rock", floatArrayOf(5f, 3f, -2f, 3f, 5f)),
        Predefinicao("Eletrônico", floatArrayOf(6f, 3f, 0f, 3f, 6f))
    )

    @Volatile var disponivel = true; private set      // o equalizador de software sempre existe
    /** Sobe a cada mudança: o equalizador do player confere e refaz os filtros. */
    @Volatile var versao = 0; private set
    /** O som que sai agora é o do celular por Bluetooth: os efeitos do Android estão ligados na saída geral. */
    private var fonteBluetooth = false
    var ligado = true; private set
    /** Índice da predefinição escolhida, ou -1 quando os controles foram mexidos à mão. */
    var predefinicao = 0; private set
    var graves = 0; private set      // 0..1000

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    var nBandas = 5; private set
    var minMb = -1500; private set
    var maxMb = 1500; private set
    var centrosHz = IntArray(5) { intArrayOf(60, 230, 910, 3600, 14000)[it] }; private set
    var niveis = IntArray(5); private set      // em milibéis (100 = 1 dB)
    private var prefs: android.content.SharedPreferences? = null

    fun iniciar(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)
        val p = prefs!!
        ligado = p.getBoolean("ligado", true)
        predefinicao = p.getInt("predefinicao", 0)
        graves = p.getInt("graves", 0)
        val salvo = p.getString("niveis", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
        if (salvo != null && salvo.size == nBandas) niveis = salvo.toIntArray() else if (predefinicao in PREDEFINICOES.indices) calcularPredefinicao(predefinicao)
        aplicar()
        Log.d(TAG, "iniciado: bandas=$nBandas faixa=$minMb..$maxMb centros=${centrosHz.toList()}")
    }

    private fun guardar() {
        prefs?.edit()?.putBoolean("ligado", ligado)?.putInt("predefinicao", predefinicao)?.putInt("graves", graves)?.putString("niveis", niveis.joinToString(","))?.apply()
    }

    private fun calcularPredefinicao(i: Int) {
        val curva = PREDEFINICOES[i].db
        for (b in 0 until nBandas) {
            val x = if (nBandas == 1) 0f else b.toFloat() / (nBandas - 1) * (curva.size - 1)
            val a = x.toInt().coerceAtMost(curva.size - 2); val f = x - a
            val db = curva[a] * (1 - f) + curva[a + 1] * f
            niveis[b] = (db * 100).toInt().coerceIn(minMb, maxMb)
        }
        graves = when (i) { 1 -> 500; 6 -> 350; 5 -> 200; else -> 0 }
    }

    /** Efeitos do Android na saída geral (sessão 0): só existem quando o menu pede "também no celular (Bluetooth)". */
    private fun criarEfeitosDoSistema() {
        try { if (eq == null) eq = Equalizer(0, 0) } catch (t: Throwable) { Log.w(TAG, "equalizador do sistema indisponivel", t) }
        try { if (bass == null) bass = BassBoost(0, 0) } catch (t: Throwable) { Log.w(TAG, "graves do sistema indisponivel", t) }
    }

    private fun liberarEfeitosDoSistema() {
        try { eq?.enabled = false; eq?.release() } catch (_: Throwable) { }
        try { bass?.enabled = false; bass?.release() } catch (_: Throwable) { }
        eq = null; bass = null
    }

    private fun aplicar() {
        versao++      // o equalizador do player refaz os filtros
        if (!fonteBluetooth) return
        try {
            val e = eq
            if (e != null) {
                val n = e.numberOfBands.toInt()
                val mn = e.bandLevelRange[0].toInt(); val mx = e.bandLevelRange[1].toInt()
                for (b in 0 until n) {
                    val origem = if (nBandas == n) niveis[b] else niveis[(b * (nBandas - 1) / maxOf(1, n - 1)).coerceIn(0, nBandas - 1)]
                    e.setBandLevel(b.toShort(), (if (ligado) origem else 0).coerceIn(mn, mx).toShort())
                }
                e.enabled = ligado
            }
            bass?.let { b -> b.setStrength((if (ligado) graves else 0).toShort()); b.enabled = ligado && graves > 0 }
        } catch (t: Throwable) { Log.w(TAG, "nao consegui aplicar", t) }
    }

    /** Quem manda: a tela principal, quando o som que sai passa a ser o do celular por Bluetooth (verdadeiro) ou o do app (falso). */
    fun definirFonteBluetooth(v: Boolean) {
        if (v == fonteBluetooth) return
        fonteBluetooth = v
        if (v) criarEfeitosDoSistema() else liberarEfeitosDoSistema()
        aplicar()
        Log.d(TAG, "som do ${if (v) "celular (Bluetooth): efeitos do Android ligados" else "app: equalizador do player"}")
    }

    fun definirLigado(v: Boolean) { ligado = v; aplicar(); guardar() }

    fun escolherPredefinicao(i: Int) {
        if (i !in PREDEFINICOES.indices) return
        predefinicao = i
        calcularPredefinicao(i)
        aplicar(); guardar()
    }

    /** Mexeu numa banda (milibéis): a predefinição deixa de valer. */
    fun definirBanda(b: Int, mb: Int, guardarJa: Boolean) {
        if (b !in 0 until nBandas) return
        niveis[b] = mb.coerceIn(minMb, maxMb)
        predefinicao = -1
        aplicar(); if (guardarJa) guardar()
    }

    fun definirGraves(v: Int, guardarJa: Boolean) {
        graves = v.coerceIn(0, 1000)
        predefinicao = -1
        aplicar(); if (guardarJa) guardar()
    }

    fun nomeDaFaixa(b: Int): String {
        val hz = centrosHz.getOrElse(b) { 0 }
        return if (hz >= 1000) (if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format("%.1f kHz", hz / 1000f).replace('.', ',')) else "$hz Hz"
    }
}
