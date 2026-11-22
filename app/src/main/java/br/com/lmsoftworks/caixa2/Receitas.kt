package br.com.lmsoftworks.caixa2

import android.content.Context
import android.util.Log
import br.com.lmsoftworks.caixa2.voz.Rede
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Uma receita da lista de resultados. */
class Receita(val titulo: String, val fonte: String, val url: String, val imagem: String, val descricao: String = "", val imagemReserva: String = "")

/** A receita aberta: ingredientes e modo de preparo. */
class ReceitaDetalhe(
    val titulo: String, val fonte: String, val imagem: String, val tempo: String, val porcoes: String,
    val ingredientes: List<String>, val passos: List<String>
)

/**
 * Receitas de vários sites. Cada fonte sabe buscar uma lista (título, foto, endereço) e, ao abrir, a receita completa vem dos
 * dados estruturados que os sites publicam na própria página (schema.org "Recipe"), o que é bem mais estável que raspar o desenho
 * da página. Se uma fonte cair, as outras continuam respondendo.
 */
object Receitas {
    private const val TAG = "Receitas"
    private const val UA = "Mozilla/5.0 (Linux; Android 7.1.2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    private fun baixar(ctx: Context, url: String): String? {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "pt-BR,pt;q=0.9").build()
            Rede.cliente(ctx, leituraSeg = 12).newCall(req).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else { Log.d(TAG, "HTTP ${r.code} em $url"); null }
            }
        } catch (e: Exception) { Log.d(TAG, "falha em $url: $e"); null }
    }

    /** Texto sem marcas HTML; alguns sites mandam as letras com acento "escritas duas vezes" (&amp;oacute;), então repete até assentar. */
    private fun limpar(s: String): String {
        var t = s
        repeat(3) {
            val novo = android.text.Html.fromHtml(t, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            if (novo == t) return@repeat
            t = novo
        }
        return t.replace(Regex("""\s+"""), " ").trim()
    }

    // ---------------------------------------------------------------- busca

    /** Busca em todas as fontes ao mesmo tempo e intercala os resultados (um de cada site por vez), sem repetir o mesmo nome. */
    fun buscar(ctx: Context, consulta: String): List<Receita> {
        val q = URLEncoder.encode(consulta.trim(), "UTF-8")
        val fontes: List<() -> List<Receita>> = listOf(
            { tudoGostoso(ctx, q) }, { receiteria(ctx, q) }, { mealDb(ctx, q) }
        )
        val respostas = arrayOfNulls<List<Receita>>(fontes.size)
        val threads = fontes.mapIndexed { i, f ->
            Thread { respostas[i] = try { f() } catch (e: Exception) { Log.d(TAG, "fonte $i: $e"); emptyList() } }.also { it.start() }
        }
        threads.forEach { it.join(20000) }
        val listas = respostas.map { it.orEmpty() }
        val saida = ArrayList<Receita>()
        val vistos = HashSet<String>()
        var i = 0
        while (listas.any { i < it.size }) {
            for (l in listas) l.getOrNull(i)?.let { if (vistos.add(chaveDe(it.titulo))) saida.add(it) }
            i++
        }
        Log.d(TAG, "busca \"$consulta\": ${listas.map { it.size }} -> ${saida.size}")
        return saida
    }

    private val PEQUENAS = setOf("de", "da", "do", "das", "dos", "e", "com", "sem", "em", "na", "no", "nas", "nos", "para", "a", "o", "ao", "à", "ou", "por")

    /** "lasanha de abobrinha" -> "Lasanha de Abobrinha": primeira letra de cada palavra em maiúscula (menos "de", "com", "e"...). */
    fun nomeBonito(t: String): String {
        val palavras = t.trim().split(Regex("""\s+"""))
        return palavras.mapIndexed { i, p ->
            val minus = p.lowercase()
            if (i > 0 && minus in PEQUENAS) minus else minus.replaceFirstChar { it.uppercase() }
        }.joinToString(" ")
    }

    private fun chaveDe(t: String) =
        java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("""[^a-z0-9]+"""), "")

    // Tudo Gostoso: cada resultado é um <article class="news-card"> com foto, título (link para /receita/) e um resumo.
    private fun tudoGostoso(ctx: Context, q: String): List<Receita> {
        val html = baixar(ctx, "https://www.tudogostoso.com.br/busca?q=$q") ?: return emptyList()
        val saida = ArrayList<Receita>()
        val linkTitulo = Regex("""<a href="(https://www\.tudogostoso\.com\.br/receita/[^"]+)"[^>]*aria-label="([^"]*)"""")
        for (bloco in html.split("<article class=\"news-card").drop(1)) {
            val a = linkTitulo.find(bloco) ?: continue
            val img = Regex("""<img[^>]*\ssrc="([^"]+)"""").find(bloco)?.groupValues?.get(1)
                ?.replace(Regex("""/images/\d+-\d+/"""), "/images/640-480/") ?: ""
            val resumo = Regex("""class="news-card-text">([^<]*)""").find(bloco)?.groupValues?.get(1)?.let { limpar(it) } ?: ""
            saida.add(Receita(nomeBonito(limpar(a.groupValues[2])), "Tudo Gostoso", a.groupValues[1], img, resumo))
            if (saida.size >= 12) break
        }
        return saida
    }

    // Receiteria: cartões com o link da receita, a foto logo depois e o título no atributo data-ra-title.
    private fun receiteria(ctx: Context, q: String): List<Receita> {
        val html = baixar(ctx, "https://www.receiteria.com.br/?s=$q") ?: return emptyList()
        val saida = ArrayList<Receita>()
        val cartao = Regex("""<a href="(https://www\.receiteria\.com\.br/receita/[^"]+)"[^>]*data-ra-title="([^"]*)"[^>]*>\s*<div[^>]*>\s*<img src="([^"]+)"""")
        for (m in cartao.findAll(html)) {
            val miniatura = m.groupValues[3]
            saida.add(Receita(nomeBonito(limpar(m.groupValues[2])), "Receiteria", m.groupValues[1], miniatura.replace(Regex("""-\d+x\d+(\.\w+)$"""), "$1"), "", miniatura))
            if (saida.size >= 12) break
        }
        return saida
    }

    // TheMealDB: API aberta e estável (receitas em inglês); só aparece quando a busca bate com algum nome de lá.
    private fun mealDb(ctx: Context, q: String): List<Receita> {
        val json = baixar(ctx, "https://www.themealdb.com/api/json/v1/1/search.php?s=$q") ?: return emptyList()
        val arr = JSONObject(json).optJSONArray("meals") ?: return emptyList()
        val saida = ArrayList<Receita>()
        for (i in 0 until minOf(arr.length(), 8)) {
            val m = arr.getJSONObject(i)
            saida.add(Receita(nomeBonito(m.optString("strMeal")), "TheMealDB", "mealdb:" + m.optString("idMeal"), m.optString("strMealThumb")))
        }
        return saida
    }

    // ---------------------------------------------------------------- receita completa

    fun detalhe(ctx: Context, r: Receita): ReceitaDetalhe? = try {
        if (r.url.startsWith("mealdb:")) detalheMealDb(ctx, r) else detalheSchema(ctx, r)
    } catch (e: Exception) { Log.d(TAG, "detalhe falhou: $e"); null }

    private fun detalheSchema(ctx: Context, r: Receita): ReceitaDetalhe? {
        val html = baixar(ctx, r.url) ?: return null
        val script = Regex("""<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        for (m in script.findAll(html)) {
            val rec = acharRecipe(m.groupValues[1].trim()) ?: continue
            val ing = rec.optJSONArray("recipeIngredient")?.let { a -> (0 until a.length()).map { limpar(a.optString(it)) } }
                .orEmpty().filter { it.isNotEmpty() }
            val passos = passosDe(rec.opt("recipeInstructions"))
            if (ing.isEmpty() && passos.isEmpty()) continue
            val rendimento = rec.opt("recipeYield").let { y -> if (y is JSONArray) y.optString(0) else y?.toString() ?: "" }
            return ReceitaDetalhe(
                nomeBonito(limpar(rec.optString("name", r.titulo))), r.fonte, r.imagem,
                tempoLegivel(rec.optString("totalTime", rec.optString("cookTime", ""))), limpar(rendimento), ing, passos
            )
        }
        return null
    }

    private fun acharRecipe(texto: String): JSONObject? {
        val raiz: Any = try { if (texto.startsWith("[")) JSONArray(texto) else JSONObject(texto) } catch (e: Exception) { return null }
        fun busca(o: Any?): JSONObject? {
            when (o) {
                is JSONObject -> {
                    val tipo = o.opt("@type")
                    if (tipo == "Recipe" || (tipo is JSONArray && (0 until tipo.length()).any { tipo.optString(it) == "Recipe" })) return o
                    o.optJSONArray("@graph")?.let { g -> busca(g)?.let { return it } }
                }
                is JSONArray -> for (i in 0 until o.length()) busca(o.opt(i))?.let { return it }
            }
            return null
        }
        return busca(raiz)
    }

    private fun passosDe(o: Any?): List<String> {
        val saida = ArrayList<String>()
        fun add(x: Any?) {
            when (x) {
                is String -> limpar(x).takeIf { it.isNotEmpty() }?.let { saida.add(it) }
                is JSONArray -> for (i in 0 until x.length()) add(x.opt(i))
                is JSONObject -> if (x.has("itemListElement")) add(x.opt("itemListElement")) else add(x.optString("text", x.optString("name", "")))
            }
        }
        add(o)
        return saida
    }

    private fun tempoLegivel(iso: String): String {
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""").find(iso) ?: return ""
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val min = m.groupValues[2].toIntOrNull() ?: 0
        return when { h > 0 && min > 0 -> "$h h $min min"; h > 0 -> "$h h"; min > 0 -> "$min min"; else -> "" }
    }

    private fun detalheMealDb(ctx: Context, r: Receita): ReceitaDetalhe? {
        val id = r.url.removePrefix("mealdb:")
        val m = JSONObject(baixar(ctx, "https://www.themealdb.com/api/json/v1/1/lookup.php?i=$id") ?: return null)
            .optJSONArray("meals")?.optJSONObject(0) ?: return null
        val ing = (1..20).mapNotNull { i ->
            val n = m.optString("strIngredient$i").trim()
            val med = m.optString("strMeasure$i").trim()
            if (n.isEmpty() || n == "null") null else listOf(med, n).filter { it.isNotEmpty() }.joinToString(" ")
        }
        val passos = m.optString("strInstructions").split(Regex("""\r?\n+""")).map { it.trim() }.filter { it.isNotEmpty() }
        return ReceitaDetalhe(nomeBonito(r.titulo), r.fonte, r.imagem, "", "", ing, passos)
    }
}
