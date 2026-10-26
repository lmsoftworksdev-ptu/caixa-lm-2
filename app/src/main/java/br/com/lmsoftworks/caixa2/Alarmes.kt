package br.com.lmsoftworks.caixa2

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Um alarme. [dias] é uma máscara de 7 bits: bit 0 = domingo ... bit 6 = sábado; 0 = toca uma vez só. */
class Alarme(val id: Int, var hora: Int, var minuto: Int, var dias: Int, var nome: String, var ligado: Boolean)

/**
 * Lista de alarmes, guardada no aparelho (SharedPreferences), com a regra de quando tocar. A tela confere a hora a cada
 * meio segundo ([verificar]); o AlarmManager do Android é um reforço que acorda o app no horário do próximo alarme.
 */
class AlarmeControle(contexto: Context, private val aoMudar: () -> Unit) {

    companion object {
        val NOMES = listOf("Acordar", "Dormir", "Remédio", "Trabalho")
        const val DIAS_UTEIS = 0b0111110           // segunda a sexta (bits 1 a 5)
        const val TODOS = 0b1111111
        const val SONECA_MS = 5 * 60_000L
        private val ABREV = listOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb")
    }

    private val ctx = contexto.applicationContext
    private val prefs = ctx.getSharedPreferences("alarmes", Context.MODE_PRIVATE)
    val lista = ArrayList<Alarme>()
    private var soneca: Pair<Int, Long>? = null     // (id do alarme, instante em que volta a tocar)

    init { carregar() }

    private fun carregar() {
        try {
            val arr = JSONArray(prefs.getString("lista", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                lista.add(Alarme(o.getInt("id"), o.getInt("h"), o.getInt("m"), o.getInt("d"), o.getString("n"), o.getBoolean("l")))
            }
        } catch (e: Exception) { lista.clear() }
        val id = prefs.getInt("soneca_id", -1); val t = prefs.getLong("soneca_t", 0L)
        if (id >= 0 && t > 0) soneca = id to t
    }

    /** Guarda no aparelho, reagenda o reforço e avisa a tela. */
    fun salvar() {
        val arr = JSONArray()
        for (a in lista) arr.put(JSONObject().put("id", a.id).put("h", a.hora).put("m", a.minuto).put("d", a.dias).put("n", a.nome).put("l", a.ligado))
        val e = prefs.edit().putString("lista", arr.toString())
        val s = soneca
        if (s != null) e.putInt("soneca_id", s.first).putLong("soneca_t", s.second) else e.remove("soneca_id").remove("soneca_t")
        e.apply()
        reagendar()
        aoMudar()
    }

    fun porId(id: Int): Alarme? = lista.firstOrNull { it.id == id }

    fun novo(): Alarme {
        val id = (lista.maxOfOrNull { it.id } ?: 0) + 1
        val a = Alarme(id, 7, 0, DIAS_UTEIS, "Acordar", true)
        lista.add(a)
        salvar()
        return a
    }

    fun excluir(id: Int) {
        lista.removeAll { it.id == id }
        if (soneca?.first == id) soneca = null
        salvar()
    }

    // ------------------------------------------------------------ texto

    fun descricaoDias(a: Alarme): String = when {
        a.dias == 0 -> "Uma vez"
        a.dias == TODOS -> "Todos os dias"
        a.dias == DIAS_UTEIS -> "Dias úteis"
        else -> (0..6).filter { (a.dias shr it) and 1 == 1 }.joinToString(", ") { ABREV[it] }
    }

    // ------------------------------------------------------------ quando toca

    private fun chaveMinuto(c: Calendar) = "%d-%03d-%02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.DAY_OF_YEAR), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))

    /** Chamada a cada ~0,5 s: devolve o alarme que deve tocar agora (ou null). Nunca toca duas vezes no mesmo minuto. */
    fun verificar(): Alarme? {
        val agora = System.currentTimeMillis()
        val s = soneca
        if (s != null && agora >= s.second) {
            soneca = null; salvar()
            porId(s.first)?.let { return it }
        }
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY); val m = cal.get(Calendar.MINUTE); val dow = cal.get(Calendar.DAY_OF_WEEK) - 1
        val chave = chaveMinuto(cal)
        for (a in lista) {
            if (!a.ligado || a.hora != h || a.minuto != m) continue
            if (a.dias != 0 && (a.dias shr dow) and 1 == 0) continue
            if (prefs.getString("ult_${a.id}", "") == chave) continue
            prefs.edit().putString("ult_${a.id}", chave).apply()
            if (a.dias == 0) { a.ligado = false; salvar() }       // sem dias marcados: toca uma vez e se desliga
            return a
        }
        return null
    }

    /** Soneca: o mesmo alarme volta a tocar em 5 minutos. */
    fun adiar(a: Alarme) {
        soneca = a.id to System.currentTimeMillis() + SONECA_MS
        salvar()
    }

    /** Próximo instante em que algum alarme vai tocar (inclui a soneca), com o alarme dono dele. */
    fun proxima(): Pair<Alarme, Long>? {
        val agora = System.currentTimeMillis()
        var melhor: Pair<Alarme, Long>? = null
        for (a in lista) {
            if (!a.ligado) continue
            for (desloc in 0..7) {
                val c = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, desloc)
                    set(Calendar.HOUR_OF_DAY, a.hora); set(Calendar.MINUTE, a.minuto); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                if (c.timeInMillis <= agora) continue
                val dow = c.get(Calendar.DAY_OF_WEEK) - 1
                if (a.dias == 0 || (a.dias shr dow) and 1 == 1) {
                    if (melhor == null || c.timeInMillis < melhor.second) melhor = a to c.timeInMillis
                    break
                }
            }
        }
        val s = soneca
        if (s != null) porId(s.first)?.let { if (melhor == null || s.second < melhor.second) melhor = it to s.second }
        return melhor
    }

    /** "Hoje às 07:00", "Amanhã às 07:00" ou "Seg às 07:00"; null se não há alarme ligado. */
    fun proximoTexto(): String? {
        val (_, t) = proxima() ?: return null
        val alvo = Calendar.getInstance().apply { timeInMillis = t }
        val hoje = Calendar.getInstance()
        val dias = ((alvo.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis.let { it } -
            hoje.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) / 86_400_000L).toInt()
        val hora = Calendar.getInstance().apply { timeInMillis = t }
        val quando = when (dias) { 0 -> "Hoje"; 1 -> "Amanhã"; else -> ABREV[hora.get(Calendar.DAY_OF_WEEK) - 1] }
        return "$quando às %02d:%02d".format(hora.get(Calendar.HOUR_OF_DAY), hora.get(Calendar.MINUTE))
    }

    // ------------------------------------------------------------ reforço do sistema

    /** Pede ao Android para acordar o app no horário do próximo alarme (caso o app tenha sido fechado). */
    fun reagendar() {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, AlarmeReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
        val p = proxima()
        if (p == null) { am.cancel(pi); return }
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.second, pi) } catch (e: Exception) { am.set(AlarmManager.RTC_WAKEUP, p.second, pi) }
    }
}

/** O Android chama isto no horário do próximo alarme; ele traz a tela do app para a frente, que então toca o alarme. */
class AlarmeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        c.startActivity(
            Intent(c, InicioActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("alarme", true)
        )
    }
}

/** Toque do alarme: arpejo de sino gerado na hora (sem arquivo), repetindo a cada 2,2 s até [parar]. */
object SomAlarme {
    private var faixa: AudioTrack? = null

    fun iniciar() {
        parar()
        val taxa = 22050
        val total = (taxa * 2.2).toInt()
        val pcm = ShortArray(total)
        val notas = doubleArrayOf(523.25, 659.25, 783.99, 1046.5, 783.99, 1046.5)
        for ((n, freq) in notas.withIndex()) {
            val ini = (n * 0.16 * taxa).toInt()
            val duracao = (0.55 * taxa).toInt()
            for (k in 0 until duracao) {
                val idx = ini + k
                if (idx >= total) break
                val t = k / taxa.toDouble()
                val amp = 0.32 * exp(-t * 5.0) * min(1.0, k / (0.01 * taxa))
                pcm[idx] = (pcm[idx] + sin(2 * PI * freq * t) * amp * 32767).toInt().coerceIn(-32767, 32767).toShort()
            }
        }
        @Suppress("DEPRECATION")
        val f = AudioTrack(AudioManager.STREAM_MUSIC, taxa, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, total * 2, AudioTrack.MODE_STATIC)
        f.write(pcm, 0, total)
        f.setLoopPoints(0, total, -1)
        f.play()
        faixa = f
    }

    fun parar() {
        val f = faixa ?: return
        faixa = null
        try { f.stop() } catch (e: Exception) { }
        f.release()
    }
}
