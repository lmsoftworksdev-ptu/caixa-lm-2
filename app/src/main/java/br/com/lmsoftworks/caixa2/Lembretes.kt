package br.com.lmsoftworks.caixa2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Um lembrete falado: a caixa avisa em voz alta no horário marcado. */
class Lembrete(val id: Long, val texto: String, val quandoMs: Long)

/** Lista de lembretes, guardada no aparelho (sobrevive a reiniciar). A tela principal chama [devidos] a cada meio segundo. */
class LembreteControle(contexto: Context) {

    private val prefs = contexto.applicationContext.getSharedPreferences("lembretes", Context.MODE_PRIVATE)
    private val lista = ArrayList<Lembrete>()

    init {
        try {
            val a = JSONArray(prefs.getString("itens", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                lista.add(Lembrete(o.getLong("id"), o.getString("texto"), o.getLong("quando")))
            }
        } catch (_: Exception) { }
    }

    /** Todos, do mais próximo ao mais distante. */
    val todos: List<Lembrete> get() = lista.sortedBy { it.quandoMs }

    fun novo(texto: String, quandoMs: Long): Lembrete {
        val l = Lembrete(System.currentTimeMillis(), texto, quandoMs)
        lista.add(l)
        salvar()
        return l
    }

    /** Os que já chegaram na hora: saem da lista e são devolvidos para a caixa avisar. */
    fun devidos(agora: Long = System.currentTimeMillis()): List<Lembrete> {
        val d = lista.filter { it.quandoMs <= agora }
        if (d.isNotEmpty()) { lista.removeAll(d.toSet()); salvar() }
        return d
    }

    fun apagarTodos(): Int { val n = lista.size; lista.clear(); salvar(); return n }

    private fun salvar() {
        val a = JSONArray()
        lista.forEach { a.put(JSONObject().put("id", it.id).put("texto", it.texto).put("quando", it.quandoMs)) }
        prefs.edit().putString("itens", a.toString()).apply()
    }

    companion object {
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
