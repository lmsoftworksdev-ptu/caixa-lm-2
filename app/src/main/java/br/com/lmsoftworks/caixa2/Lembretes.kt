package br.com.lmsoftworks.caixa2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Um lembrete falado: a caixa avisa em voz alta no horário marcado. [dias] é a repetição (bit 0 = domingo ... bit 6 = sábado);
 * 0 = uma vez só. [quandoMs] é sempre a PRÓXIMA vez. Lembrete de uma vez que já avisou fica [concluido] (some do início, mas
 * continua na lista para ser reaberto).
 */
class Lembrete(val id: Long, var texto: String, var quandoMs: Long, var dias: Int = 0, var concluido: Boolean = false) {
    val hora: Int get() = Calendar.getInstance().apply { timeInMillis = quandoMs }.get(Calendar.HOUR_OF_DAY)
    val minuto: Int get() = Calendar.getInstance().apply { timeInMillis = quandoMs }.get(Calendar.MINUTE)
}

/** Lista de lembretes, guardada no aparelho (sobrevive a reiniciar). A tela principal chama [devidos] a cada meio segundo. */
class LembreteControle(contexto: Context) {

    private val prefs = contexto.applicationContext.getSharedPreferences("lembretes", Context.MODE_PRIVATE)
    private val lista = ArrayList<Lembrete>()

    init {
        try {
            val a = JSONArray(prefs.getString("itens", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                lista.add(Lembrete(o.getLong("id"), o.getString("texto"), o.getLong("quando"), o.optInt("dias", 0), o.optBoolean("ok", false)))
            }
        } catch (_: Exception) { }
    }

    /** Os que ainda vão avisar, do mais próximo ao mais distante. */
    val todos: List<Lembrete> get() = lista.filter { !it.concluido }.sortedBy { it.quandoMs }

    /** Os já concluídos, os mais recentes primeiro. */
    val concluidos: List<Lembrete> get() = lista.filter { it.concluido }.sortedByDescending { it.quandoMs }

    fun porId(id: Long): Lembrete? = lista.firstOrNull { it.id == id }

    fun novo(texto: String, quandoMs: Long, dias: Int = 0): Lembrete {
        val l = Lembrete(System.currentTimeMillis(), texto, quandoMs, dias)
        lista.add(l)
        salvar()
        return l
    }

    /** Os que já chegaram na hora: os de uma vez ficam concluídos, os repetidos passam para a próxima vez. Devolve-os para a caixa avisar. */
    fun devidos(agora: Long = System.currentTimeMillis()): List<Lembrete> {
        val d = lista.filter { !it.concluido && it.quandoMs <= agora }
        if (d.isEmpty()) return d
        val avisar = d.map { Lembrete(it.id, it.texto, it.quandoMs, it.dias) }
        for (l in d) { if (l.dias == 0) l.concluido = true else l.quandoMs = proxima(l.hora, l.minuto, l.dias, agora) }
        salvar()
        return avisar
    }

    fun excluir(id: Long) { lista.removeAll { it.id == id }; salvar() }

    fun apagarTodos(): Int { val n = lista.size; lista.clear(); salvar(); return n }

    /** Muda a hora e o minuto: os repetidos vão para a próxima vez; o de uma vez fica no mesmo dia (ou amanhã, se já passou). */
    fun definirHorario(l: Lembrete, hora: Int, minuto: Int) {
        val agora = System.currentTimeMillis()
        if (l.dias != 0) l.quandoMs = proxima(hora, minuto, l.dias, agora)
        else {
            val c = Calendar.getInstance().apply { timeInMillis = l.quandoMs; set(Calendar.HOUR_OF_DAY, hora); set(Calendar.MINUTE, minuto); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            if (c.timeInMillis <= agora) c.add(Calendar.DAY_OF_YEAR, 1)
            l.quandoMs = c.timeInMillis
        }
        reabrirSeFuturo(l)
        salvar()
    }

    /** Hoje (0) ou amanhã (1), uma vez só, na hora que já estava marcada. */
    fun definirDia(l: Lembrete, diasAdiante: Int) {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, l.hora); set(Calendar.MINUTE, l.minuto); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, diasAdiante)
        }
        l.dias = 0; l.quandoMs = c.timeInMillis
        reabrirSeFuturo(l)
        salvar()
    }

    /** Repetição pelos dias da semana (máscara); 0 vira "uma vez" na próxima vez da hora marcada. */
    fun definirDias(l: Lembrete, dias: Int) {
        val h = l.hora; val m = l.minuto
        l.dias = dias
        l.quandoMs = if (dias != 0) proxima(h, m, dias, System.currentTimeMillis()) else proxima(h, m, TODOS, System.currentTimeMillis())
        reabrirSeFuturo(l)
        salvar()
    }

    /** Concluído <-> aberto. Reabrir um que já passou o coloca na próxima hora cheia (ou na próxima vez, se repete). */
    fun alternarConcluido(l: Lembrete) {
        if (!l.concluido) { l.concluido = true }
        else {
            l.concluido = false
            val agora = System.currentTimeMillis()
            if (l.quandoMs <= agora) l.quandoMs = if (l.dias != 0) proxima(l.hora, l.minuto, l.dias, agora) else proximaHoraCheia(agora)
        }
        salvar()
    }

    private fun reabrirSeFuturo(l: Lembrete) { if (l.quandoMs > System.currentTimeMillis()) l.concluido = false }

    fun salvar() {
        val a = JSONArray()
        lista.forEach { a.put(JSONObject().put("id", it.id).put("texto", it.texto).put("quando", it.quandoMs).put("dias", it.dias).put("ok", it.concluido)) }
        prefs.edit().putString("itens", a.toString()).apply()
    }

    companion object {
        const val TODOS = 0b1111111
        const val DIAS_UTEIS = 0b0111110          // segunda a sexta
        const val FIM_DE_SEMANA = 0b1000001       // domingo e sábado

        fun proximaHoraCheia(desde: Long = System.currentTimeMillis()): Long =
            Calendar.getInstance().apply { timeInMillis = desde; add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

        /** A próxima vez, depois de [desde], em que [hora]:[minuto] cai num dos dias da máscara. */
        fun proxima(hora: Int, minuto: Int, dias: Int, desde: Long): Long {
            val c = Calendar.getInstance().apply { timeInMillis = desde; set(Calendar.HOUR_OF_DAY, hora); set(Calendar.MINUTE, minuto); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            for (i in 0..8) {
                val dow = c.get(Calendar.DAY_OF_WEEK) - 1
                if (c.timeInMillis > desde && (dias == 0 || (dias shr dow) and 1 == 1)) return c.timeInMillis
                c.add(Calendar.DAY_OF_YEAR, 1)
            }
            return c.timeInMillis
        }

        /** "Hoje às 09:00", "Amanhã às 09:00", "Todos os dias às 18:00", "Dias úteis às 07:30"... (como aparece na lista). */
        fun descricao(l: Lembrete): String {
            val hora = String.format("%02d:%02d", l.hora, l.minuto)
            return when {
                l.dias == TODOS -> "Todos os dias às $hora"
                l.dias == DIAS_UTEIS -> "Dias úteis às $hora"
                l.dias == FIM_DE_SEMANA -> "Fim de semana às $hora"
                l.dias != 0 -> (0..6).filter { (l.dias shr it) and 1 == 1 }.joinToString(", ") { listOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb")[it] } + " às $hora"
                else -> quando(l.quandoMs).replaceFirstChar { it.uppercase() }
            }
        }

        /** "daqui a 20 minutos", "hoje às 15:00", "amanhã às 09:00" ou "dia 05/11 às 10:00" (do jeito que a caixa fala). */
        fun quando(quandoMs: Long, agoraMs: Long = System.currentTimeMillis()): String {
            val min = ((quandoMs - agoraMs) / 60_000L).toInt()
            if (min in 1..119) return if (min == 1) "daqui a 1 minuto" else "daqui a $min minutos"
            val alvo = Calendar.getInstance().apply { timeInMillis = quandoMs }
            val hoje = Calendar.getInstance().apply { timeInMillis = agoraMs }
            fun dia(c: Calendar) = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
            val amanha = (hoje.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            val hora = SimpleDateFormat("HH:mm", Locale.US).format(Date(quandoMs))
            return when (dia(alvo)) {
                dia(hoje) -> "hoje às $hora"
                dia(amanha) -> "amanhã às $hora"
                else -> "dia " + SimpleDateFormat("dd/MM", Locale.US).format(Date(quandoMs)) + " às $hora"
            }
        }
    }
}

/** "Minha hora de dormir é às 22h": todo dia, nesse horário, a caixa avisa em voz alta. Guardado no aparelho. */
class HoraDeDormir(contexto: Context) {

    private val prefs = contexto.applicationContext.getSharedPreferences("dormir", Context.MODE_PRIVATE)

    val ativa: Boolean get() = prefs.getBoolean("ativa", false)
    val hora: Int get() = prefs.getInt("hora", 22)
    val minuto: Int get() = prefs.getInt("minuto", 0)

    fun definir(h: Int, m: Int) {
        prefs.edit().putBoolean("ativa", true).putInt("hora", h).putInt("minuto", m).putString("ultimo", "").apply()
    }

    fun desligar() { prefs.edit().putBoolean("ativa", false).apply() }

    /** true uma única vez por dia, no minuto marcado. */
    fun chegouAHora(): Boolean {
        if (!ativa) return false
        val c = Calendar.getInstance()
        if (c.get(Calendar.HOUR_OF_DAY) != hora || c.get(Calendar.MINUTE) != minuto) return false
        val hoje = SimpleDateFormat("yyyyMMdd", Locale.US).format(c.time)
        if (prefs.getString("ultimo", "") == hoje) return false
        prefs.edit().putString("ultimo", hoje).apply()
        return true
    }
}
