package br.com.lmsoftworks.caixa2

import android.content.Context
import android.util.Log
import br.com.lmsoftworks.caixa2.voz.Rede
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Rádios da internet de todo o mundo, pelo Radio Browser (radio-browser.info): banco aberto e gratuito, mantido pela comunidade, sem conta nem chave.
 * Aqui só a busca: país, cidade/estado ou nome da rádio. Quem toca é o player do app.
 */
object Radios {
    private const val TAG = "Radios"
    private const val BASE = "https://all.api.radio-browser.info/json"

    class Estacao(
        val uuid: String, val nome: String, val url: String, val logo: String, val pais: String, val codigoPais: String,
        val estado: String, val tags: String, val codec: String, val bitrate: Int
    ) {
        /** "Paracatu · Minas Gerais · Brasil" (o que houver), para a lista e para a tela de tocando. */
        fun local(): String = listOf(estado, nomeDoPais(codigoPais, pais)).filter { it.isNotBlank() }.joinToString(" · ")
        /** "MP3 128 kbps · sertanejo, pop". */
        fun detalhe(): String {
            val tec = listOf(codec.takeIf { it.isNotBlank() && !it.equals("UNKNOWN", true) }, bitrate.takeIf { it > 0 }?.let { "$it kbps" }).filterNotNull().joinToString(" ")
            val gen = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(", ")
            return listOf(tec, gen).filter { it.isNotBlank() }.joinToString(" · ")
        }
    }

    class Pais(val nome: String, val codigo: String)

    /** Os países mais pedidos, com o nome em português, para os botões da tela. */
    val POPULARES = listOf(
        Pais("Brasil", "BR"), Pais("Portugal", "PT"), Pais("Estados Unidos", "US"), Pais("Argentina", "AR"), Pais("México", "MX"),
        Pais("Espanha", "ES"), Pais("França", "FR"), Pais("Itália", "IT"), Pais("Alemanha", "DE"), Pais("Reino Unido", "GB"),
        Pais("Japão", "JP"), Pais("Chile", "CL"), Pais("Colômbia", "CO"), Pais("Uruguai", "UY")
    )

    // nomes em português (sem acento) de outros países, para o campo de busca reconhecer "Canadá", "Japão"...
    private val ALIAS = mapOf(
        "brasil" to "BR", "portugal" to "PT", "estados unidos" to "US", "eua" to "US", "argentina" to "AR", "mexico" to "MX", "espanha" to "ES",
        "franca" to "FR", "italia" to "IT", "alemanha" to "DE", "reino unido" to "GB", "inglaterra" to "GB", "japao" to "JP", "chile" to "CL",
        "colombia" to "CO", "uruguai" to "UY", "paraguai" to "PY", "bolivia" to "BO", "peru" to "PE", "equador" to "EC", "venezuela" to "VE",
        "canada" to "CA", "australia" to "AU", "nova zelandia" to "NZ", "india" to "IN", "china" to "CN", "russia" to "RU", "turquia" to "TR",
        "grecia" to "GR", "holanda" to "NL", "paises baixos" to "NL", "belgica" to "BE", "suica" to "CH", "austria" to "AT", "suecia" to "SE",
        "noruega" to "NO", "dinamarca" to "DK", "finlandia" to "FI", "irlanda" to "IE", "polonia" to "PL", "ucrania" to "UA", "egito" to "EG",
        "africa do sul" to "ZA", "angola" to "AO", "mocambique" to "MZ", "cabo verde" to "CV", "coreia do sul" to "KR", "israel" to "IL",
        "arabia saudita" to "SA", "cuba" to "CU", "costa rica" to "CR", "panama" to "PA", "republica dominicana" to "DO", "marrocos" to "MA"
    )

    /** O país em português quando o conhecemos (o Radio Browser manda em inglês: "Brazil"). */
    fun nomeDoPais(codigo: String, ingles: String): String {
        POPULARES.firstOrNull { it.codigo.equals(codigo, true) }?.let { return it.nome }
        val alias = ALIAS.entries.firstOrNull { it.value.equals(codigo, true) }?.key ?: return ingles
        return alias.split(" ").joinToString(" ") { w -> if (w == "do" || w == "de" || w == "da") w else w.replaceFirstChar { it.uppercase() } }
    }

    private fun sem(s: String) = Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** O que foi digitado é o nome de um país? Devolve o país (nome, código) ou null. */
    fun paisDoTexto(texto: String): Pais? {
        val t = sem(texto)
        if (t.length < 3) return null
        ALIAS[t]?.let { cod -> return POPULARES.firstOrNull { it.codigo == cod } ?: Pais(texto.trim().replaceFirstChar { it.uppercase() }, cod) }
        return null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun pegar(ctx: Context, url: String): JSONArray? = try {
        val req = Request.Builder().url(url).header("User-Agent", "CaixaLM2/1.0").build()
        Rede.cliente(ctx, leituraSeg = 15).newCall(req).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "HTTP ${r.code} em $url"); null } else JSONArray(r.body?.string() ?: "[]")
        }
    } catch (e: Exception) { Log.w(TAG, "falha em $url", e); null }

    private fun ler(a: JSONArray): List<Estacao> {
        val saida = ArrayList<Estacao>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            if (url.isBlank()) continue
            saida.add(Estacao(
                o.optString("stationuuid"), o.optString("name").trim().ifBlank { "Rádio" }, url, o.optString("favicon"),
                o.optString("country"), o.optString("countrycode"), o.optString("state"), o.optString("tags"), o.optString("codec"), o.optInt("bitrate")
            ))
        }
        return saida
    }

    /**
     * Procura rádios. [codigoPais] (BR, PT...) limita ao país; [consulta] procura no nome da rádio e no estado/cidade. Sem consulta: as mais ouvidas
     * do país (ou do mundo). Bloqueante: chame fora da thread da tela. null se a internet falhar.
     */
    fun buscar(ctx: Context, codigoPais: String?, consulta: String, limite: Int = 150): List<Estacao>? {
        val pais = if (codigoPais.isNullOrBlank()) "" else "&countrycode=${enc(codigoPais)}"
        val comum = "&hidebroken=true&order=clickcount&reverse=true&limit=$limite$pais"
        val q = consulta.trim()
        if (q.isEmpty()) return pegar(ctx, "$BASE/stations/search?name=$comum")?.let { ler(it) }
        val porNome = pegar(ctx, "$BASE/stations/search?name=${enc(q)}$comum")
        val porEstado = pegar(ctx, "$BASE/stations/search?state=${enc(q)}$comum")
        if (porNome == null && porEstado == null) return null
        val vistos = HashSet<String>()
        val saida = ArrayList<Estacao>()
        for (a in listOfNotNull(porNome, porEstado)) for (e in ler(a)) if (vistos.add(e.uuid.ifBlank { e.url })) saida.add(e)
        return saida
    }

    /** Conta uma audição no Radio Browser (é assim que ele ordena as mais ouvidas). Sem esperar resposta. */
    fun registrarAudicao(ctx: Context, uuid: String) {
        if (uuid.isBlank()) return
        Thread { pegar(ctx, "$BASE/url/${enc(uuid)}") }.start()
    }
}
