package br.com.lmsoftworks.caixa2

import android.content.Context
import android.util.Log
import br.com.lmsoftworks.caixa2.voz.Rede
import okhttp3.Request
import java.text.Normalizer

/**
 * Canais de TV aberta e gratuita do Brasil e de outros países, pelas listas públicas do projeto iptv-org (iptv-org.github.io), uma lista por país
 * (no Brasil são uns 85 KB). Aqui só a lista; quem toca é o player de vídeo do app. Parte dos endereços cai ou muda com o tempo: o que falhar
 * mostra "canal indisponível".
 */
object TV {
    private const val TAG = "TV"
    private const val BASE = "https://iptv-org.github.io/iptv/countries"

    class Canal(val nome: String, val url: String, val logo: String, val categorias: List<String>, val qualidade: String, val restrito: Boolean) {
        /** "Notícias · 720p". */
        fun detalhe(): String = (categorias.take(2).map { nomeDaCategoria(it) } + listOfNotNull(qualidade.takeIf { it.isNotBlank() })).joinToString(" · ")
    }

    private val CATEGORIAS = mapOf(
        "General" to "Geral", "News" to "Notícias", "Sports" to "Esportes", "Entertainment" to "Variedades", "Kids" to "Infantil",
        "Movies" to "Filmes", "Series" to "Séries", "Music" to "Música", "Religious" to "Religioso", "Documentary" to "Documentários",
        "Culture" to "Cultura", "Education" to "Educação", "Lifestyle" to "Estilo de vida", "Comedy" to "Comédia", "Animation" to "Animação",
        "Legislative" to "Legislativo", "Public" to "Público", "Outdoor" to "Natureza", "Business" to "Negócios", "Weather" to "Clima",
        "Science" to "Ciência", "Travel" to "Viagens", "Cooking" to "Culinária", "Family" to "Família", "Classic" to "Clássicos",
        "Auto" to "Carros", "Relax" to "Relax", "Shop" to "Compras", "Interactive" to "Interativo", "Undefined" to "Outros"
    )

    fun nomeDaCategoria(c: String) = CATEGORIAS[c] ?: c

    private val cache = HashMap<String, List<Canal>>()

    private fun sem(s: String) = Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Os canais do país (BR, PT...). Bloqueante: chame fora da thread da tela. null se a internet falhar. */
    fun canais(ctx: Context, codigoPais: String): List<Canal>? {
        synchronized(cache) { cache[codigoPais]?.let { return it } }
        val texto = try {
            val req = Request.Builder().url("$BASE/${codigoPais.lowercase()}.m3u").header("User-Agent", "CaixaLM2/1.0").build()
            Rede.cliente(ctx, leituraSeg = 20).newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.w(TAG, "HTTP ${r.code}"); null } else r.body?.string()
            }
        } catch (e: Exception) { Log.w(TAG, "falha na lista de $codigoPais", e); null } ?: return null
        val lista = ler(texto, codigoPais)
        synchronized(cache) { cache[codigoPais] = lista }
        return lista
    }

    private fun atributo(linha: String, nome: String): String {
        val i = linha.indexOf("$nome=\""); if (i < 0) return ""
        val ini = i + nome.length + 2
        val fim = linha.indexOf('"', ini)
        return if (fim < 0) "" else linha.substring(ini, fim)
    }

    private fun ler(m3u: String, pais: String): List<Canal> {
        val saida = ArrayList<Canal>()
        val linhas = m3u.lineSequence().map { it.trim() }.toList()
        var i = 0
        while (i < linhas.size) {
            val l = linhas[i++]
            if (!l.startsWith("#EXTINF")) continue
            var exigeCabecalho = false
            var url = ""
            while (i < linhas.size) {
                val p = linhas[i++]
                if (p.startsWith("#EXTVLCOPT")) { exigeCabecalho = true; continue }      // pede referência/agente especial: não dá para tocar daqui
                if (p.startsWith("#")) continue
                url = p; break
            }
            if (exigeCabecalho || !(url.startsWith("http://") || url.startsWith("https://"))) continue
            if (url.contains(".mpd", true)) continue      // DASH não é suportado
            var nome = l.substringAfterLast(",").trim()
            var qualidade = ""
            Regex("\\((\\d{3,4}p)\\)").find(nome)?.let { qualidade = it.groupValues[1] }
            val restrito = nome.contains("[Geo-blocked]", true)
            nome = nome.replace(Regex("\\s*\\((\\d{3,4}p)\\)"), "").replace(Regex("\\s*\\[[^\\]]*\\]"), "").trim()
            if (nome.isBlank()) continue
            val categorias = atributo(l, "group-title").split(";").map { it.trim() }.filter { it.isNotEmpty() && it != "XXX" }
            if (atributo(l, "group-title").contains("XXX")) continue
            saida.add(Canal(nome, url, atributo(l, "tvg-logo"), categorias, qualidade, restrito))
        }
        // fora do país de origem os "restritos" não abrem; no Brasil (onde a caixa fica) abrem
        val visiveis = if (pais == "BR") saida else saida.filter { !it.restrito }
        return visiveis.distinctBy { it.url }.sortedBy { sem(it.nome) }
    }

    /** Filtra a lista pela categoria (null = todas) e pelo texto digitado no nome do canal. */
    fun filtrar(lista: List<Canal>, categoria: String?, consulta: String): List<Canal> {
        val q = sem(consulta)
        return lista.filter { c -> (categoria == null || categoria in c.categorias) && (q.isEmpty() || sem(c.nome).contains(q)) }
    }

    /** As categorias que existem na lista, das mais comuns para as menos. */
    fun categoriasDe(lista: List<Canal>): List<String> =
        lista.flatMap { it.categorias }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.filter { it != "Undefined" }.take(12)
}
