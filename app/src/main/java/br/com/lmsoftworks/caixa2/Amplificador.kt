package br.com.lmsoftworks.caixa2

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * O player e o Bluetooth usam os mesmos efeitos nativos, cada um na própria sessão de áudio.
 * O player recorre a [EqualizadorSoftware] se a ROM não aceitar os efeitos na sessão dele.
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
    /** A sessão do Bluetooth pode continuar com os efeitos armados durante uma pausa. */
    private var fonteBluetooth = false
    private val principal = Handler(Looper.getMainLooper())
    private var sessaoBluetooth = -1
    private var consultandoBluetooth = false
    private var consultarDepoisDe = 0L
    private var tentarRapidoAte = 0L
    private val reconsultaBluetooth = Runnable { definirFonteBluetooth(true) }
    var ligado = true; private set
    /** Índice da predefinição escolhida, ou -1 quando os controles foram mexidos à mão. */
    var predefinicao = 0; private set
    var graves = 0; private set      // 0..1000
    /** Reforço de volume: ganho digital além do máximo do aparelho, de 0 a [REFORCO_MAX_DB] dB (guardado de 0 a 1000). Independe das predefinições. */
    var reforco = 0; private set
    const val REFORCO_MAX_DB = 10f      // acima disso distorce demais (testado na caixa com 20 dB)
    /** Quanto do Volume+ vale no som do celular (Bluetooth), para ficar no mesmo nível da música do app. */
    const val GANHO_BT = 0.3f
    /** Esta ROM não consegue criar o efeito de ganho do Android (erro -3): depois da 1ª falha não tenta mais, e o ganho sai por outro caminho. */
    @Volatile private var ganhoNativoOk = true
    val reforcoDb: Float get() = reforco / 1000f * REFORCO_MAX_DB
    /** O que a pessoa montou à mão (faixas e graves): guardado à parte, para escolher uma predefinição não apagar o ajuste dela. */
    private var niveisPersonalizados = IntArray(5)
    private var gravesPersonalizados = 0

    private var efeitosBluetooth: EfeitosSessao? = null
    private val sessoesLocais = LinkedHashSet<SessaoLocal>()
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
        reforco = p.getInt("reforco", 0).coerceIn(0, 1000)
        val salvo =p.getString("niveis", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
        if (salvo != null && salvo.size == nBandas) niveis = salvo.toIntArray() else if (predefinicao in PREDEFINICOES.indices) calcularPredefinicao(predefinicao)
        val salvoPers = p.getString("niveisPers", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
        if (salvoPers != null && salvoPers.size == nBandas) { niveisPersonalizados = salvoPers.toIntArray(); gravesPersonalizados = p.getInt("gravesPers", 0) }
        else if (predefinicao == -1) { niveisPersonalizados = niveis.copyOf(); gravesPersonalizados = graves }      // quem já tinha ajustado à mão não perde o ajuste
        aplicar()
        Log.d(TAG, "iniciado: bandas=$nBandas faixa=$minMb..$maxMb centros=${centrosHz.toList()}")
    }

    private fun guardar() {
        prefs?.edit()?.putBoolean("ligado", ligado)?.putInt("predefinicao", predefinicao)?.putInt("graves", graves)?.putInt("reforco", reforco)?.putString("niveis", niveis.joinToString(","))
            ?.putString("niveisPers", niveisPersonalizados.joinToString(","))?.putInt("gravesPers", gravesPersonalizados)?.apply()
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

    private class EfeitosSessao(id: Int) {
        val eq = try { Equalizer(0, id) } catch (e: RuntimeException) { Log.w(TAG, "equalizador indisponível na sessão $id", e); null }
        val bass = try { BassBoost(0, id) } catch (e: RuntimeException) { Log.w(TAG, "graves indisponíveis na sessão $id", e); null }
        val ganho = if (!ganhoNativoOk) null else try { LoudnessEnhancer(id) } catch (e: RuntimeException) {
            ganhoNativoOk = false
            Log.w(TAG, "reforço de volume nativo indisponível (sessão $id): o ganho usa o equalizador (Bluetooth) ou o player do app")
            null
        }

        fun aplicar(): Boolean = try {
            eq?.let { e ->
                val n = e.numberOfBands.toInt()
                val mn = e.bandLevelRange[0].toInt(); val mx = e.bandLevelRange[1].toInt()
                // Sem o efeito de ganho nativo, o reforço de volume sobe TODAS as faixas do equalizador por igual (até o máximo delas)
                // No Bluetooth o equalizador não tem limitador: +10 dB em todas as faixas soava MUITO mais alto que a música do app, onde o limitador de
                // picos corta boa parte do ganho. GANHO_BT iguala os dois (estimativa: ajuste de ouvido, é só este número).
                val extra = if (ligado && ganho == null) (reforcoDb * 100f * GANHO_BT).toInt() else 0
                for (b in 0 until n) {
                    val origem = if (nBandas == n) niveis[b] else niveis[(b * (nBandas - 1) / maxOf(1, n - 1)).coerceIn(0, nBandas - 1)]
                    e.setBandLevel(b.toShort(), ((if (ligado) origem else 0) + extra).coerceIn(mn, mx).toShort())
                }
                e.enabled = ligado
            }
            bass?.let { b -> b.setStrength((if (ligado) graves else 0).toShort()); b.enabled = ligado && graves > 0 }
            ganho?.let { g -> g.setTargetGain((if (ligado) reforcoDb * 100f else 0f).toInt()); g.enabled = ligado && reforco > 0 }
            true
        } catch (t: RuntimeException) { Log.w(TAG, "nao consegui aplicar", t); false }

        fun liberar() {
            try { eq?.release() } catch (_: RuntimeException) {}
            try { bass?.release() } catch (_: RuntimeException) {}
            try { ganho?.release() } catch (_: RuntimeException) {}
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    internal class SessaoLocal(private val software: EqualizadorSoftware) {
        private var id = 0
        private var efeitos: EfeitosSessao? = null

        fun ligar(sessao: Int) {
            if (id == sessao && efeitos != null) return
            desligar()
            if (sessao <= 0) return
            val novos = EfeitosSessao(sessao)
            // sem o efeito de ganho nativo, o equalizador de software do player cuida de tudo (inclusive do reforço de volume)
            if (novos.eq?.hasControl() != true || novos.bass?.hasControl() != true || novos.ganho?.hasControl() != true || !novos.aplicar()) {
                novos.liberar()
                return
            }
            id = sessao
            efeitos = novos
            sessoesLocais.add(this)
            software.efeitosNativos = true
            Log.d(TAG, "Player sessão $sessao: equalizador nativo e reforço de graves")
        }

        fun atualizar() {
            if (efeitos?.aplicar() == false) desligar()
        }

        fun desligar() {
            efeitos?.liberar()
            efeitos = null
            id = 0
            sessoesLocais.remove(this)
            software.efeitosNativos = false
        }
    }

    private fun aplicar() {
        versao++
        efeitosBluetooth?.aplicar()
        sessoesLocais.toList().forEach { it.atualizar() }
    }

    /** Quem manda: a tela principal, quando o som que sai passa a ser o do celular por Bluetooth (verdadeiro) ou o do app (falso). */
    fun definirFonteBluetooth(v: Boolean, consultarAgora: Boolean = false) {
        if (v != fonteBluetooth) {
            fonteBluetooth = v
            consultarDepoisDe = 0L
            if (!v) {
                principal.removeCallbacks(reconsultaBluetooth)
                efeitosBluetooth?.liberar()
                efeitosBluetooth = null
                sessaoBluetooth = -1
            }
        }
        if (v && consultarAgora) {
            consultarDepoisDe = 0L
            tentarRapidoAte = SystemClock.uptimeMillis() + 1_500L
        }
        if (!v || consultandoBluetooth || SystemClock.uptimeMillis() < consultarDepoisDe) return
        consultandoBluetooth = true
        Tarefas.executar {
            val sessao = SessaoAudioAndroid.bluetooth()
            principal.post {
                consultandoBluetooth = false
                if (fonteBluetooth) {
                    val agora = SystemClock.uptimeMillis()
                    val tentarRapido = sessao <= 0 && agora < tentarRapidoAte
                    consultarDepoisDe = agora + if (sessao > 0) 6000L else if (tentarRapido) 180L else 1000L
                    if (tentarRapido) principal.postDelayed(reconsultaBluetooth, 180L)
                    if (sessao > 0 && (sessao != sessaoBluetooth || efeitosBluetooth == null)) {
                        efeitosBluetooth?.liberar()
                        sessaoBluetooth = sessao
                        val efeitos = EfeitosSessao(sessao)
                        efeitosBluetooth = efeitos.takeIf { it.eq != null || it.bass != null || it.ganho != null }
                        efeitos.aplicar()
                        Log.d(TAG, "Bluetooth sessão $sessao: equalizador=${efeitos.eq?.enabled}, graves=${efeitos.bass?.enabled}")
                    }
                }
            }
        }
    }

    fun definirLigado(v: Boolean) { ligado = v; aplicar(); guardar() }

    fun escolherPredefinicao(i: Int) {
        if (i !in PREDEFINICOES.indices) return
        predefinicao = i
        calcularPredefinicao(i)
        aplicar(); guardar()
    }

    /** Volta para o ajuste que a pessoa montou à mão (faixas e graves). */
    fun escolherPersonalizado() {
        predefinicao = -1
        niveis = niveisPersonalizados.copyOf()
        graves = gravesPersonalizados
        aplicar(); guardar()
    }

    /** Mexeu numa banda (milibéis): vira o ajuste Personalizado (a predefinição deixa de valer). */
    fun definirBanda(b: Int, mb: Int, guardarJa: Boolean) {
        if (b !in 0 until nBandas) return
        niveis[b] = mb.coerceIn(minMb, maxMb)
        predefinicao = -1
        niveisPersonalizados = niveis.copyOf(); gravesPersonalizados = graves
        aplicar(); if (guardarJa) guardar()
    }

    /** Reforço de volume (0 a 1000). Não mexe na predefinição nem no Personalizado. */
    fun definirReforco(v: Int, guardarJa: Boolean) {
        reforco = v.coerceIn(0, 1000)
        aplicar(); if (guardarJa) guardar()
    }

    fun definirGraves(v: Int, guardarJa: Boolean) {
        graves = v.coerceIn(0, 1000)
        predefinicao = -1
        niveisPersonalizados = niveis.copyOf(); gravesPersonalizados = graves
        aplicar(); if (guardarJa) guardar()
    }

    fun nomeDaFaixa(b: Int): String {
        val hz = centrosHz.getOrElse(b) { 0 }
        return if (hz >= 1000) (if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format("%.1f kHz", hz / 1000f).replace('.', ',')) else "$hz Hz"
    }
}
