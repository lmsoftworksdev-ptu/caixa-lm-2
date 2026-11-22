package br.com.lmsoftworks.caixa2

import android.content.Context
import br.com.lmsoftworks.caixa2.voz.AcaoLista
import br.com.lmsoftworks.caixa2.voz.ComandoVoz
import org.json.JSONArray
import org.json.JSONObject

/** Um item de lista: o texto e se já foi marcado (no carrinho, comprado, feito). */
class ItemLista(var texto: String, var marcado: Boolean = false)

/**
 * Listas ("lista de compras", "lista de tarefas"...), guardadas no aparelho. Pela voz e pela tela. O nome é guardado em minúsculas
 * (é assim que o assistente entende "lista de compras"); a tela mostra com a primeira letra maiúscula.
 */
class ListaControle(contexto: Context) {

    private val prefs = contexto.applicationContext.getSharedPreferences("listas", Context.MODE_PRIVATE)
    private val listas = LinkedHashMap<String, MutableList<ItemLista>>()

    init {
        try {
            val o = JSONObject(prefs.getString("listas", "{}"))
            for (nome in o.keys()) {
                val a = o.getJSONArray(nome)
                // formato novo: {"t":texto,"m":marcado}; o antigo era só o texto
                listas[nome] = MutableList(a.length()) { i ->
                    val x = a.get(i)
                    if (x is JSONObject) ItemLista(x.getString("t"), x.optBoolean("m", false)) else ItemLista(x.toString())
                }
            }
        } catch (_: Exception) { }
    }

    fun salvar() {
        val o = JSONObject()
        listas.forEach { (nome, itens) -> o.put(nome, JSONArray().also { a -> itens.forEach { a.put(JSONObject().put("t", it.texto).put("m", it.marcado)) } }) }
        prefs.edit().putString("listas", o.toString()).apply()
    }

    // ------------------------------------------------------------ pela tela

    val nomes: List<String> get() = listas.keys.toList()

    fun itens(nome: String): List<ItemLista> = listas[nome].orEmpty()

    fun criar(nome: String): String {
        val n = nome.trim().lowercase()
        if (n.isNotEmpty()) listas.getOrPut(n) { ArrayList() }
        salvar()
        return n
    }

    fun excluir(nome: String) { listas.remove(nome); salvar() }

    fun adicionar(nome: String, texto: String) {
        val t = texto.trim()
        if (t.isEmpty()) return
        listas.getOrPut(nome) { ArrayList() }.add(ItemLista(t))
        salvar()
    }

    fun alternar(item: ItemLista) { item.marcado = !item.marcado; salvar() }

    fun remover(nome: String, item: ItemLista) { listas[nome]?.remove(item); salvar() }

    fun limparMarcados(nome: String) { listas[nome]?.removeAll { it.marcado }; salvar() }

    fun nomeBonito(nome: String) = nome.replaceFirstChar { it.uppercase() }

    // ------------------------------------------------------------ pela voz

    /** Faz o pedido e devolve o que a caixa fala de volta. */
    fun executar(c: ComandoVoz.Lista): String {
        val nome = c.nome
        val itens = listas.getOrPut(nome) { ArrayList() }
        return when (c.acao) {
            AcaoLista.ADICIONAR -> {
                val novos = c.itens.filter { n -> itens.none { it.texto.equals(n, ignoreCase = true) } }
                itens.addAll(novos.map { ItemLista(it) })
                salvar()
                when {
                    c.itens.isEmpty() -> "O que eu adiciono na lista de $nome?"
                    novos.isEmpty() -> "Já está na lista de $nome."
                    else -> "Adicionei ${juntar(novos)} na lista de $nome."
                }
            }
            AcaoLista.REMOVER -> {
                val tirados = c.itens.mapNotNull { n -> itens.firstOrNull { it.texto.equals(n, ignoreCase = true) || it.texto.contains(n, ignoreCase = true) } }.distinct()
                itens.removeAll(tirados.toSet())
                salvar()
                if (tirados.isEmpty()) "Não achei isso na lista de $nome." else "Tirei ${juntar(tirados.map { it.texto })} da lista de $nome."
            }
            AcaoLista.LIMPAR -> {
                val n = itens.size
                itens.clear()
                salvar()
                if (n == 0) "A lista de $nome já está vazia." else "Limpei a lista de $nome."
            }
            AcaoLista.LER -> {
                val pendentes = itens.filter { !it.marcado }.map { it.texto }
                when (pendentes.size) {
                    0 -> if (itens.isEmpty()) "A lista de $nome está vazia." else "Tudo da lista de $nome já está marcado."
                    1 -> "A lista de $nome tem 1 item: ${pendentes[0]}."
                    else -> "A lista de $nome tem ${pendentes.size} itens: ${juntar(pendentes)}."
                }
            }
        }
    }

    private fun juntar(l: List<String>) = if (l.size == 1) l[0] else l.dropLast(1).joinToString(", ") + " e " + l.last()
}
