package br.com.lmsoftworks.caixa2

import android.content.Context
import br.com.lmsoftworks.caixa2.voz.AcaoLista
import br.com.lmsoftworks.caixa2.voz.ComandoVoz
import org.json.JSONArray
import org.json.JSONObject

/** Listas faladas ("lista de compras", "lista de tarefas"...), guardadas no aparelho. */
class ListaControle(contexto: Context) {

    private val prefs = contexto.applicationContext.getSharedPreferences("listas", Context.MODE_PRIVATE)
    private val listas = LinkedHashMap<String, MutableList<String>>()

    init {
        try {
            val o = JSONObject(prefs.getString("listas", "{}"))
            for (nome in o.keys()) {
                val a = o.getJSONArray(nome)
                listas[nome] = MutableList(a.length()) { a.getString(it) }
            }
        } catch (_: Exception) { }
    }

    private fun salvar() {
        val o = JSONObject()
        listas.forEach { (nome, itens) -> o.put(nome, JSONArray(itens)) }
        prefs.edit().putString("listas", o.toString()).apply()
    }

    /** Faz o pedido e devolve o que a caixa fala de volta. */
    fun executar(c: ComandoVoz.Lista): String {
        val nome = c.nome
        val itens = listas.getOrPut(nome) { ArrayList() }
        return when (c.acao) {
            AcaoLista.ADICIONAR -> {
                val novos = c.itens.filter { n -> itens.none { it.equals(n, ignoreCase = true) } }
                itens.addAll(novos)
                salvar()
                when {
                    c.itens.isEmpty() -> "O que eu adiciono na lista de $nome?"
                    novos.isEmpty() -> "Já está na lista de $nome."
                    else -> "Adicionei ${juntar(novos)} na lista de $nome."
                }
            }
            AcaoLista.REMOVER -> {
                val tirados = c.itens.mapNotNull { n -> itens.firstOrNull { it.equals(n, ignoreCase = true) || it.contains(n, ignoreCase = true) } }.distinct()
                itens.removeAll(tirados.toSet())
                salvar()
                if (tirados.isEmpty()) "Não achei isso na lista de $nome." else "Tirei ${juntar(tirados)} da lista de $nome."
            }
            AcaoLista.LIMPAR -> {
                val n = itens.size
                itens.clear()
                salvar()
                if (n == 0) "A lista de $nome já está vazia." else "Limpei a lista de $nome."
            }
            AcaoLista.LER -> when (itens.size) {
                0 -> "A lista de $nome está vazia."
                1 -> "A lista de $nome tem 1 item: ${itens[0]}."
                else -> "A lista de $nome tem ${itens.size} itens: ${juntar(itens)}."
            }
        }
    }

    private fun juntar(l: List<String>) = if (l.size == 1) l[0] else l.dropLast(1).joinToString(", ") + " e " + l.last()
}
