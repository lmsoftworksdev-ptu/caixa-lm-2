package br.com.lmsoftworks.caixa2.voz

import android.content.Context
import br.com.lmsoftworks.caixa2.Ajustes
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Respostas a perguntas, pela internet.
 *
 *  1) Se existir a chave da Anthropic no celular (arquivo claude.txt, veja [lerConfigClaude]), pergunta para
 *     a IA com busca na web: ela pesquisa e responde em uma ou duas frases (como um "Google falado").
 *  2) Sem chave (ou se falhar): previsao do tempo pelo Open-Meteo e o resto pela Wikipedia.
 *
 * Bloqueante: chame fora da thread principal.
 */
object Respostas {
    private const val TAG = "VozCaixa"
    private const val UA = "CaixaDeSomLM/0.1 (caixa de som pessoal)"

    // ---------------- ponto de entrada ----------------

    /** Resposta curta para falar, ou null se nao achou nada. */
    fun responder(ctx: Context, pergunta: String): String? {
        // 1) Gemini (Google): busca no Google e responde direto. Plano gratuito, chave em gemini.txt.
        // 0) Tavily: busca na web que ja devolve a resposta pronta (chave em tavily.txt).
        lerConfig(ctx, "tavily.txt", "tvly-", "")?.let { (chave, _) ->
            tavily(ctx, chave, pergunta)?.let { return it }
            Log.d(TAG, "Tavily indisponivel")
        }

        val comGemini = lerConfig(ctx, "gemini.txt", "", MODELO_GEMINI_PADRAO)
        if (comGemini != null) {
            val (chave, modelo) = comGemini
            gemini(ctx, chave, modelo, pergunta)?.let { return it }
            Log.d(TAG, "Gemini indisponivel")
            // Tem chave mas a busca falhou: nao inventa resposta com a Wikipedia (traz texto solto).
            if (PALAVRAS_CLIMA.any { pergunta.lowercase().contains(it) }) {
                clima(ctx, pergunta.lowercase().trim())?.let { return it }
            }
            return null
        }
        // 2) Claude (Anthropic), se houver chave em claude.txt.
        lerConfigClaude(ctx)?.let { (chave, modelo) ->
            claude(ctx, chave, modelo, pergunta)?.let { return it }
            Log.d(TAG, "IA indisponivel; usando Wikipedia/tempo")
        }
        // 3) Sem chave: tempo (Open-Meteo) e Wikipedia.

        val q = pergunta.lowercase().trim()
        if (PALAVRAS_CLIMA.any { q.contains(it) }) {
            clima(ctx, q)?.let { return it }
        }
        return wikipedia(ctx, q)
    }

    // ---------------- Tavily (busca na web com resposta pronta) ----------------

    /**
     * POST https://api.tavily.com/search  (Authorization: Bearer tvly-...).
     * Com include_answer a Tavily pesquisa na web e devolve "answer" ja resumido; sem ele usamos o
     * trecho do melhor resultado.
     */
    private fun tavily(ctx: Context, chave: String, pergunta: String): String? {
        val corpo = JSONObject()
            .put("query", pergunta)
            .put("search_depth", "basic")
            .put("include_answer", "basic")
            .put("max_results", 3)
            .put("topic", "general")
            .put("country", "brazil")

        val req = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Authorization", "Bearer $chave")
            .header("content-type", "application/json")
            .post(corpo.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val (codigo, texto) = try {
            Rede.cliente(ctx, leituraSeg = 30).newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: Exception) {
            Log.d(TAG, "Tavily: falha de rede: $e")
            return null
        }
        if (codigo != 200) {
            Log.d(TAG, "Tavily respondeu HTTP $codigo: ${texto.replace(Regex("""\s+"""), " ").take(600)}")
            return null
        }
        return try {
            val j = JSONObject(texto)
            val resposta = j.optString("answer", "").trim()
            val bruto = if (resposta.isNotBlank() && resposta != "null") resposta
            else j.optJSONArray("results")?.optJSONObject(0)?.optString("content", "").orEmpty()
            Log.d(TAG, "Tavily: ${bruto.take(160)}")
            var final = bruto
            if (pareceIngles(final)) {
                // A Tavily responde em ingles: traduz (Gemini, se houver) ou usa o trecho original da fonte.
                val trad = lerConfig(ctx, "gemini.txt", "", MODELO_GEMINI_PADRAO)?.let { (k, _) ->
                    geminiUma(ctx, k, "gemini-flash-lite-latest", "Traduza para português do Brasil, em no máximo duas frases curtas, só o texto traduzido: $final", false, 7L).texto
                }
                final = trad ?: j.optJSONArray("results")?.let { arr ->
                    (0 until arr.length()).map { arr.optJSONObject(it)?.optString("content", "").orEmpty() }
                        .firstOrNull { it.isNotBlank() && !pareceIngles(it) }
                } ?: final
            }
            curtoParaFala(final).ifBlank { null }
        } catch (e: Exception) {
            Log.d(TAG, "Tavily: resposta ilegivel: $e")
            null
        }
    }

    private fun pareceIngles(t: String): Boolean {
        val p = t.lowercase().split(Regex("""[^a-zçãõáéíóúâêô]+""")).filter { it.isNotEmpty() }
        if (p.size < 4) return false
        val en = setOf("the", "is", "are", "of", "and", "to", "in", "for", "with", "that", "according", "sources", "was", "will")
        val pt = setOf("o", "a", "os", "as", "de", "do", "da", "em", "que", "e", "para", "com", "uma", "um", "foi", "será")
        return p.count { it in en } > p.count { it in pt }
    }

    /** Ate 2 frases (~350 caracteres), sem formatacao: bom para falar em voz alta. */
    private fun curtoParaFala(t: String): String {
        val limpo = limparParaFala(t)
        val frases = Regex("""(?<=[.!?])\s+""").split(limpo)
        val sb = StringBuilder()
        for (f in frases.take(2)) {
            if (sb.isNotEmpty() && sb.length + f.length > 220) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(f)
        }
        return sb.toString().take(320)
    }

    // ---------------- Gemini / Google (opcional, chave gratuita) ----------------

    /** Apelido do Google que sempre aponta para o modelo "flash" atual (rapido). Troque na linha 2 do arquivo. */
    private const val MODELO_GEMINI_PADRAO = "gemini-flash-lite-latest"      // o "gemini-flash-latest" ficou travado (sem resposta); o lite responde em ~1 s

    /**
     * Le <externo>/Android/data/<pacote>/files/<arquivo> :
     *   linha 1: a chave (precisa comecar com [prefixoChave])
     *   linha 2 (opcional): o nome do modelo
     */
    private fun lerConfig(ctx: Context, arquivo: String, prefixoChave: String, modeloPadrao: String): Pair<String, String>? {
        return try {
            val f = File(ctx.getExternalFilesDir(null), arquivo)
            if (!f.isFile) return null
            val linhas = f.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            // Chaves novas do Google comecam com "AQ." (as antigas com "AIza"): so exige tamanho minimo.
            val chave = linhas.getOrNull(0)?.takeIf { it.startsWith(prefixoChave) && it.length >= 20 } ?: return null
            chave to (linhas.getOrNull(1) ?: modeloPadrao)
        } catch (e: Throwable) {
            null
        }
    }

    private fun promptDeVoz(ctx: Context): String {
        val agora = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", Locale("pt", "BR")).format(Date())
        val cidade = cidadeAproximada(ctx)
        return buildString {
            append("Você é a assistente de voz de uma caixa de som. Responda sempre em português do Brasil, ")
            append("de forma direta, em no máximo duas frases curtas, como se estivesse falando em voz alta. ")
            append("Não use markdown, listas, asteriscos, emojis nem links. Escreva números e unidades de modo fácil de falar. ")
            append("Se precisar de informação atual, pesquise na internet. Se não achar, diga isso em uma frase. ")
            append("Data e hora agora: $agora.")
            if (cidade != null) append(" Cidade aproximada do usuário (pelo IP): $cidade.")
        }
    }

    private class ResultadoGemini(val codigo: Int, val texto: String?)

    /** Combinacao (modelo, com busca no Google?) que ja funcionou: e a primeira a ser tentada. */
    private var combinacaoBoa: Pair<String, Boolean>? = null

    /** Quando a busca do Google respondeu 429 (sem cota): até quando pular a busca. */
    @Volatile private var buscaSemCotaAte = 0L

    /**
     * Tenta varias combinacoes ate uma responder: o plano gratuito nao da cota para todos os modelos
     * nem para a busca do Google (erro 429). Com busca = informacao atual; sem busca = conhecimento do modelo.
     */
    private fun gemini(ctx: Context, chave: String, modeloConfig: String, pergunta: String): String? {
        val candidatos = buildList {
            // So "lembra" a combinacao com busca: sem busca, a proxima pergunta tenta a busca de novo.
            combinacaoBoa?.takeIf { it.second }?.let { add(it) }
            add(modeloConfig to true)
            add("gemini-flash-lite-latest" to true)
            add(modeloConfig to false)
            add("gemini-flash-lite-latest" to false)
            add("gemini-2.5-flash" to false)
            add("gemini-2.5-flash-lite" to false)
        }.distinct()

        val semBusca = System.currentTimeMillis() < buscaSemCotaAte
        for ((modelo, busca) in candidatos) {
            if (busca && semBusca) continue      // a busca do Google estourou a cota há pouco: nem tenta (economiza alguns segundos)
            val ultimo = java.util.concurrent.atomic.AtomicReference<ResultadoGemini>()
            val achou = corrida(4000L, 2, 18000L) {
                val x = geminiUma(ctx, chave, modelo, pergunta, busca, 12)
                ultimo.set(x)
                if (x.texto != null) x else null
            }
            var r = achou ?: ultimo.get() ?: ResultadoGemini(-1, null)
            if (busca && r.codigo == 429) buscaSemCotaAte = System.currentTimeMillis() + 10 * 60_000L
            // 503 = modelo sobrecarregado por um instante: tenta de novo antes de desistir.
            var vezes = 0
            while (r.texto == null && r.codigo == 503 && vezes++ < 2) {
                Thread.sleep(1500)
                r = geminiUma(ctx, chave, modelo, pergunta, busca, 12)
            }
            if (r.texto != null) {
                if (combinacaoBoa != modelo to busca) Log.d(TAG, "Gemini: funcionou com $modelo (busca no Google: $busca)")
                combinacaoBoa = modelo to busca
                return r.texto
            }
            if (r.codigo == 401 || r.codigo == 403) return null // chave recusada: nao adianta insistir
        }
        return null
    }

    private fun geminiUma(ctx: Context, chave: String, modelo: String, pergunta: String, comBusca: Boolean, limiteSeg: Long = 40): ResultadoGemini {
        val sistema = promptDeVoz(ctx) +
            if (comBusca) "" else " A busca em tempo real não está disponível nesta resposta: responda com o que você sabe, sem mencionar internet ou conexão. Só se a pergunta exigir um dado de agora (placar, preço, notícia), diga em poucas palavras que não tem essa informação atualizada."
        val corpo = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sistema))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", pergunta)))
                )
            )
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1200).put("temperature", 0.3))
        if (comBusca) corpo.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$modelo:generateContent")
            .header("x-goog-api-key", chave)
            .header("content-type", "application/json")
            .post(corpo.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val (codigo, texto) = try {
            Rede.cliente(ctx, leituraSeg = limiteSeg).newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: Exception) {
            Log.d(TAG, "Gemini $modelo: falha de rede: $e")
            return ResultadoGemini(-1, null)
        }
        if (codigo != 200) {
            Log.d(TAG, "Gemini $modelo (busca=$comBusca) respondeu HTTP $codigo: ${texto.replace(Regex("""\s+"""), " ").take(900)}")
            return ResultadoGemini(codigo, null)
        }
        return try {
            val partes = JSONObject(texto).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (partes != null) for (i in 0 until partes.length()) sb.append(partes.optJSONObject(i)?.optString("text").orEmpty())
            ResultadoGemini(200, limparParaFala(sb.toString()).ifBlank { null })
        } catch (e: Exception) {
            Log.d(TAG, "Gemini $modelo: resposta ilegivel: $e")
            ResultadoGemini(200, null)
        }
    }

    // ---------------- IA da Anthropic (opcional, precisa de chave) ----------------

    private const val MODELO_PADRAO = "claude-opus-5-5"

    /**
     * Le <externo>/Android/data/<pacote>/files/claude.txt :
     *   linha 1: a chave (sk-ant-...)
     *   linha 2 (opcional): o modelo, ex.: claude-sonnet-5-5 (mais rapido e barato)
     */
    private fun lerConfigClaude(ctx: Context): Pair<String, String>? {
        return try {
            val f = File(ctx.getExternalFilesDir(null), "claude.txt")
            if (!f.isFile) return null
            val linhas = f.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            val chave = linhas.getOrNull(0)?.takeIf { it.startsWith("sk-ant-") } ?: return null
            chave to (linhas.getOrNull(1) ?: MODELO_PADRAO)
        } catch (e: Throwable) {
            null
        }
    }

    private fun tipoBusca(modelo: String): String =
        // Variante com filtragem dinamica so nos modelos grandes; os outros usam a basica.
        if (modelo.contains("haiku") || modelo.contains("4-5") || modelo.contains("3-")) "web_search_20250305"
        else "web_search_20260209"

    private var cidadeCache: String? = null

    private fun cidadeAproximada(ctx: Context): String? {
        cidadeCache?.let { return it }
        val ip = getJson(ctx, "https://ipwho.is/") ?: return null
        if (!ip.optBoolean("success", false)) return null
        return ip.optString("city").ifBlank { null }?.also { cidadeCache = it }
    }

    private fun claude(ctx: Context, chave: String, modelo: String, pergunta: String): String? {
        val sistema = promptDeVoz(ctx)

        val mensagens = JSONArray().put(JSONObject().put("role", "user").put("content", pergunta))
        var usarFallback = true

        repeat(4) {
            val corpo = JSONObject()
                .put("model", modelo)
                .put("max_tokens", 1024)
                .put("system", sistema)
                .put("output_config", JSONObject().put("effort", "low"))
                .put(
                    "tools",
                    JSONArray().put(
                        JSONObject().put("type", tipoBusca(modelo)).put("name", "web_search").put("max_uses", 3)
                    )
                )
                .put("messages", mensagens)
            if (usarFallback) corpo.put("fallbacks", "default")

            val req = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", chave)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .apply { if (usarFallback) header("anthropic-beta", "server-side-fallback-2026-07-01") }
                .post(corpo.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val (codigo, texto) = try {
                Rede.cliente(ctx, leituraSeg = 60).newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
            } catch (e: Exception) {
                Log.d(TAG, "falha ao falar com a IA: $e")
                return null
            }

            if (codigo == 400 && usarFallback) {
                // Alguns modelos/contas nao aceitam o recurso de reserva: tenta de novo sem ele.
                Log.d(TAG, "IA recusou o pedido (400); tentando sem fallbacks: ${texto.take(200)}")
                usarFallback = false
                return@repeat
            }
            if (codigo != 200) {
                Log.d(TAG, "IA respondeu HTTP $codigo: ${texto.take(300)}")
                return null
            }

            val resp = try { JSONObject(texto) } catch (e: Exception) { return null }
            val conteudo = resp.optJSONArray("content") ?: return null

            if (resp.optString("stop_reason") == "pause_turn") {
                // A busca na web ainda nao terminou: devolve o que veio e continua.
                mensagens.put(JSONObject().put("role", "assistant").put("content", conteudo))
                return@repeat
            }

            val sb = StringBuilder()
            for (i in 0 until conteudo.length()) {
                val b = conteudo.optJSONObject(i) ?: continue
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            val resposta = limparParaFala(sb.toString())
            return resposta.ifBlank { null }
        }
        return null
    }

    private fun limparParaFala(t: String): String =
        t.replace(Regex("""[*_#`>]+"""), "")
            .replace(Regex("""\[(.*?)]\((.*?)\)"""), "$1")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(600)

    // ---------------- Tempo ----------------

    private val PALAVRAS_CLIMA = listOf(
        "tempo", "clima", "previsão", "previsao", "temperatura", "chover", "chuva", "graus", "frio", "calor"
    )

    /** A previsão de hoje em números, para a tela inicial. */
    class ClimaHoje(val cidade: String, val temp: Int, val min: Int?, val max: Int?, val chuva: Int?, val codigo: Int, val noite: Boolean = false) {
        /** "⛅  24°  ·  mín 18° / máx 29°  ·  chuva 10%  ·  Cidade" */
        fun texto(): String = buildString {
            append(emoji(codigo)).append("  ").append(temp).append("°")
            if (min != null && max != null) append("  ·  mín ").append(min).append("° / máx ").append(max).append("°")
            if (chuva != null && chuva > 0) append("  ·  chuva ").append(chuva).append("%")
            if (cidade.isNotBlank()) append("  ·  ").append(cidade)
        }
        /** O emoji do tempo (☀️ ⛅ ☁️ 🌧️ ⛈️ ...). */
        fun emoji(): String = emoji(codigo)
        private fun emoji(c: Int) = when (c) {
            0 -> "☀️"; 1, 2 -> "⛅"; 3 -> "☁️"; 45, 48 -> "🌫️"; in 51..67 -> "🌧️"; in 71..77 -> "❄️"; in 80..82 -> "🌦️"; in 95..99 -> "⛈️"; else -> "🌡️"
        }
    }

    /** Previsão de hoje pela cidade aproximada (IP), sem passar pela fala. null se a internet falhar. */
    fun climaHoje(ctx: Context): ClimaHoje? {
        val loc = localizacao(ctx) ?: return null
        val p = previsaoCompleta(ctx, loc) ?: return null
        val hoje = p.dias.firstOrNull()
        return ClimaHoje(p.cidade, p.temp, hoje?.min, hoje?.max, hoje?.chuva, p.codigo, !p.dia)
    }

    /** Onde a caixa está para o clima: a cidade escolhida em Configurações ou, se não houver, a aproximada pelo IP. (nome, latitude, longitude) */
    fun localizacao(ctx: Context): Triple<String, Double, Double>? {
        if (Ajustes.cidadeFixa) return Triple(Ajustes.cidadeNome, Ajustes.cidadeLat, Ajustes.cidadeLon)
        val ip = getJson(ctx, "https://ipwho.is/") ?: return null
        if (!ip.optBoolean("success", false)) return null
        return Triple(ip.optString("city"), ip.optDouble("latitude"), ip.optDouble("longitude"))
    }

    /** Procura uma cidade pelo nome (Open-Meteo): devolve (nome com o estado, latitude, longitude) ou null se não achar. Bloqueante. */
    fun buscarCidade(ctx: Context, nome: String): Triple<String, Double, Double>? {
        val g = getJson(ctx, "https://geocoding-api.open-meteo.com/v1/search?name=${enc(nome)}&count=1&language=pt&format=json") ?: return null
        val r = g.optJSONArray("results")?.optJSONObject(0) ?: return null
        val estado = r.optString("admin1")
        val exibe = r.optString("name") + if (estado.isNotBlank() && !estado.equals(r.optString("name"), true)) " · $estado" else ""
        return Triple(exibe, r.optDouble("latitude"), r.optDouble("longitude"))
    }

    /** Uma hora da previsão (próximas horas). */
    class HoraPrevisao(val hora: Int, val temp: Int, val codigo: Int, val dia: Boolean = true)
    /** Um dia da previsão: [indice] 0 = hoje, 1 = amanhã...; [dia] é a data no formato yyyy-MM-dd. */
    class DiaPrevisao(val indice: Int, val dia: String, val min: Int, val max: Int, val chuva: Int, val codigo: Int)
    /** A tela de Previsão do tempo: agora, próximas horas e 15 dias. [vento] em km/h. */
    class PrevisaoCompleta(
        val cidade: String, val temp: Int, val codigo: Int, val vento: Int,
        val min: Int, val max: Int, val chuva: Int, val horas: List<HoraPrevisao>, val dias: List<DiaPrevisao>, val dia: Boolean = true
    )

    // Os três modelos globais de previsão (europeu, americano e alemão) quase nunca concordam num ponto só: a média deles erra menos que qualquer um.
    private val MODELOS = listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless")

    private fun mediaSerie(o: JSONObject, chave: String, i: Int): Double? {
        var soma = 0.0; var n = 0
        for (m in MODELOS) { val a = o.optJSONArray("${chave}_$m") ?: continue; if (i < a.length() && !a.isNull(i)) { soma += a.optDouble(i); n++ } }
        return if (n == 0) null else soma / n
    }

    private fun algumCodigoAlto(o: JSONObject, i: Int): Boolean {
        for (m in MODELOS) { val a = o.optJSONArray("weather_code_$m") ?: continue; if (i < a.length() && !a.isNull(i) && a.optInt(i) >= 95) return true }
        return false
    }

    /** O código do tempo a partir da média dos modelos: chuva pela quantidade (mm), senão pela nebulosidade (%). */
    private fun codigoDaMedia(nuvens: Double?, chuvaMm: Double?, chance: Double?, trovoada: Boolean): Int {
        if (chuvaMm != null && chuvaMm >= 0.2 && (chance == null || chance >= 30)) {
            return if (trovoada) 95 else when { chuvaMm >= 4.0 -> 65; chuvaMm >= 1.0 -> 63; else -> 61 }
        }
        return when { nuvens == null -> 3; nuvens < 12 -> 0; nuvens < 35 -> 1; nuvens < 60 -> 2; else -> 3 }
    }

    /** Previsão completa (agora, 36 horas, 15 dias) pela cidade escolhida ou aproximada (IP), pela média de três modelos. null se a internet falhar. */
    fun previsaoCompleta(ctx: Context): PrevisaoCompleta? = localizacao(ctx)?.let { previsaoCompleta(ctx, it) }

    private fun previsaoCompleta(ctx: Context, loc: Triple<String, Double, Double>): PrevisaoCompleta? {
        val lat = loc.second; val lon = loc.third
        val m = getJson(
            ctx,
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&models=${MODELOS.joinToString(",")}" +
                "&current=temperature_2m,cloud_cover,precipitation,weather_code,wind_speed_10m,is_day" +
                "&hourly=temperature_2m,cloud_cover,precipitation,precipitation_probability,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,cloud_cover_mean,weather_code" +
                "&wind_speed_unit=kmh&timezone=auto&forecast_days=15"
        ) ?: return null
        val atual = m.optJSONObject("current") ?: return null
        val d = m.optJSONObject("daily") ?: return null
        val h = m.optJSONObject("hourly") ?: return null
        val datas = d.optJSONArray("time") ?: return null
        val dias = ArrayList<DiaPrevisao>()
        for (i in 0 until datas.length()) {
            val mn = mediaSerie(d, "temperature_2m_min", i); val mx = mediaSerie(d, "temperature_2m_max", i)
            if (mn == null || mx == null) continue      // além do alcance dos modelos
            val chance = mediaSerie(d, "precipitation_probability_max", i)
            val soma = mediaSerie(d, "precipitation_sum", i) ?: 0.0
            val nuvens = mediaSerie(d, "cloud_cover_mean", i) ?: 50.0
            val choveu = (chance ?: 0.0) >= 40 || soma >= 1.0
            val cod = when {
                !choveu -> codigoDaMedia(nuvens, null, null, false)
                algumCodigoAlto(d, i) -> 95
                soma >= 10.0 -> 63
                else -> 80      // chuva provável de pouco volume: pancadas
            }
            dias.add(DiaPrevisao(i, datas.getString(i), Math.round(mn).toInt(), Math.round(mx).toInt(), Math.round(chance ?: 0.0).toInt(), cod))
        }
        // próximas horas: a partir da hora atual (a primeira é "agora", com a temperatura de agora)
        val tempos = h.optJSONArray("time")
        val agoraTxt = atual.optString("time").take(13)        // yyyy-MM-ddTHH
        var inicio = 0
        if (tempos != null) for (i in 0 until tempos.length()) if (tempos.getString(i).startsWith(agoraTxt)) { inicio = i; break }
        // com vários modelos o Open-Meteo devolve o "current" já sem o nome do modelo (um só): o "agora" vem da média da série por hora
        val ehDiaAgora = atual.optInt("is_day", 1) == 1
        val tempAgora = mediaSerie(h, "temperature_2m", inicio) ?: (if (atual.has("temperature_2m")) atual.optDouble("temperature_2m") else return null)
        val nuvensAgora = mediaSerie(h, "cloud_cover", inicio); val chuvaAgora = mediaSerie(h, "precipitation", inicio)
        val trovoadaAgora = algumCodigoAlto(h, inicio)
        val horas = ArrayList<HoraPrevisao>()
        if (tempos != null) for (k in 0 until 36) {
            val i = inicio + k
            if (i >= tempos.length()) break
            val hora = tempos.getString(i).substring(11, 13).toInt()
            val temp = if (k == 0) Math.round(tempAgora).toInt() else Math.round(mediaSerie(h, "temperature_2m", i) ?: continue).toInt()
            val ehDia = if (k == 0) ehDiaAgora else (mediaSerie(h, "is_day", i) ?: 1.0) >= .5
            val cod = if (k == 0) codigoDaMedia(nuvensAgora, chuvaAgora, null, trovoadaAgora)
                      else codigoDaMedia(mediaSerie(h, "cloud_cover", i), mediaSerie(h, "precipitation", i), mediaSerie(h, "precipitation_probability", i), algumCodigoAlto(h, i))
            horas.add(HoraPrevisao(hora, temp, cod, ehDia))
        }
        val hoje = dias.firstOrNull()
        val vento = atual.optDouble("wind_speed_10m", 0.0)
        return PrevisaoCompleta(
            loc.first, Math.round(tempAgora).toInt(), codigoDaMedia(nuvensAgora, chuvaAgora, null, trovoadaAgora),
            Math.round(vento).toInt(), hoje?.min ?: 0, hoje?.max ?: 0, hoje?.chuva ?: 0, horas, dias, ehDiaAgora
        )
    }


    /** A previsão de hoje direto do Open-Meteo, pela cidade aproximada (IP). Sem busca na web: serve para as rotinas ("bom dia"). */
    fun climaDeHoje(ctx: Context): String? = clima(ctx, "tempo")

    private fun clima(ctx: Context, q: String): String? {
        val nome: String
        val lat: Double
        val lon: Double

        val cidade = extrairCidade(q)
        if (cidade != null) {
            val g = getJson(
                ctx, "https://geocoding-api.open-meteo.com/v1/search?name=${enc(cidade)}&count=1&language=pt&format=json"
            )
            val r = g?.optJSONArray("results")?.optJSONObject(0) ?: return null
            nome = r.optString("name")
            lat = r.optDouble("latitude")
            lon = r.optDouble("longitude")
        } else {
            val loc = localizacao(ctx) ?: return null
            nome = loc.first
            lat = loc.second
            lon = loc.third
        }

        val m = getJson(
            ctx,
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=1"
        ) ?: return null

        val atual = m.optJSONObject("current") ?: return null
        val diario = m.optJSONObject("daily")
        val temp = Math.round(atual.optDouble("temperature_2m")).toInt()
        val desc = descricaoTempo(atual.optInt("weather_code"))
        val max = diario?.optJSONArray("temperature_2m_max")?.optDouble(0)?.let { Math.round(it).toInt() }
        val min = diario?.optJSONArray("temperature_2m_min")?.optDouble(0)?.let { Math.round(it).toInt() }
        val chuva = diario?.optJSONArray("precipitation_probability_max")?.optInt(0)

        val sb = StringBuilder()
        sb.append("Em $nome, agora faz $temp graus, $desc.")
        if (max != null && min != null) sb.append(" Hoje, mínima de $min e máxima de $max.")
        if (chuva != null) sb.append(" Chance de chuva de $chuva por cento.")
        return sb.toString()
    }

    private fun extrairCidade(q: String): String? {
        val m = Regex("""\b(?:em|para|na|no|de|da|do)\s+([a-zçãõáéíóúâêôà ]{3,40})$""").find(q) ?: return null
        var c = m.groupValues[1].trim()
        for (lixo in listOf("hoje", "amanhã", "amanha", "agora", "esta semana", "essa semana")) {
            c = c.removeSuffix(lixo).trim()
        }
        return c.ifBlank { null }?.takeIf { it !in setOf("hoje", "tempo", "clima", "para") }
    }

    private fun descricaoTempo(codigo: Int): String = when (codigo) {
        0 -> "céu limpo"
        1, 2 -> "parcialmente nublado"
        3 -> "nublado"
        45, 48 -> "com neblina"
        in 51..57 -> "com garoa"
        in 61..67 -> "com chuva"
        in 71..77 -> "com neve"
        in 80..82 -> "com pancadas de chuva"
        in 95..99 -> "com trovoadas"
        else -> "tempo instável"
    }

    // ---------------- Wikipedia ----------------

    private val PREFIXOS = listOf(
        Regex("""^(me )?(diga|fale|fala|conte|conta|explique|explica)( um pouco)?( sobre| de| do| da)? """),
        Regex("""^quem (é|e|foi|era|são|sao|foram) """),
        Regex("""^o que (é|e|são|sao|foi|era|significa) """),
        Regex("""^(qual|quais) (é|e|era|são|sao|foi) """),
        Regex("""^(onde|quando) (fica|ficam|foi|nasceu|morreu|aconteceu) """),
        Regex("""^quantos habitantes tem """),
        Regex("""^(pesquise|pesquisar|procure|busque) (na internet |sobre |por )?""")
    )

    private fun wikipedia(ctx: Context, q: String): String? {
        var assunto = q
        for (p in PREFIXOS) assunto = p.replace(assunto, "")
        assunto = assunto.trim().ifBlank { q }

        val busca = getJson(
            ctx,
            "https://pt.wikipedia.org/w/api.php?action=query&list=search&srsearch=${enc(assunto)}" +
                "&srlimit=1&format=json&utf8=1"
        )
        val titulo = busca?.optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)?.optString("title")
        if (titulo.isNullOrBlank()) return null

        val resumo = getJson(ctx, "https://pt.wikipedia.org/api/rest_v1/page/summary/${enc(titulo)}")
        val texto = resumo?.optString("extract").orEmpty()
        if (texto.isBlank()) return null
        return resumirParaFala(texto)
    }

    /** Tira parenteses (datas, pronuncias) e fica com ate 2 frases / ~320 caracteres. */
    private fun resumirParaFala(texto: String): String {
        val limpo = Normalizer.normalize(texto, Normalizer.Form.NFC)
            .replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("""\s+"""), " ")
            .replace(" ,", ",")
            .trim()
        val frases = Regex("""(?<=[.!?])\s+(?=[A-ZÁÉÍÓÚÂÊÔÃÕÇ])""").split(limpo)
        val sb = StringBuilder()
        for (f in frases.take(2)) {
            if (sb.isNotEmpty() && sb.length + f.length > 320) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(f)
        }
        return sb.toString().take(400)
    }

    // ---------------- util ----------------

    /**
     * Transforma o áudio do pedido ("tocar anavitória") em texto, pelo Gemini (a mesma chave do gemini.txt).
     * [wav] = áudio WAV 16 kHz mono. Devolve "" se não houve fala e null se não conseguiu (sem internet, chave recusada...).
     */
    fun transcrever(ctx: Context, wav: ByteArray): String? {
        val (chave, modeloConfig) = lerConfig(ctx, "gemini.txt", "", MODELO_GEMINI_PADRAO) ?: run {
            Log.d(TAG, "transcrever: sem chave do Gemini (gemini.txt)")
            return null
        }
        val base64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
        val instrucao = "Transcreva exatamente o que a pessoa disse neste áudio, em português do Brasil. " +
            "É um pedido para uma caixa de som inteligente (tocar música ou vídeo, perguntas, luzes, volume). " +
            "Responda SÓ com o texto falado, sem aspas, sem explicações. Se não houver fala clara, responda apenas: VAZIO"
        for (modelo in listOf("gemini-flash-lite-latest", modeloConfig).distinct()) {
            var tentativas = 0
            while (tentativas++ < 2) {
                val corpo = JSONObject().put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put("role", "user").put(
                            "parts",
                            JSONArray()
                                .put(JSONObject().put("text", instrucao))
                                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", base64)))
                        )
                    )
                ).put("generationConfig", JSONObject().put("temperature", 0.0).put("maxOutputTokens", 200))
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$modelo:generateContent")
                    .header("x-goog-api-key", chave)
                    .header("content-type", "application/json")
                    .post(corpo.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val inicioMs = System.currentTimeMillis()
                val (codigo, texto) = try {
                    Rede.cliente(ctx, leituraSeg = 8).newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
                } catch (e: Exception) {
                    Log.d(TAG, "transcrever $modelo: falha de rede apos ${System.currentTimeMillis() - inicioMs} ms: $e")
                    if (tentativas < 2) continue
                    return null
                }
                Log.d(TAG, "transcrever $modelo: HTTP $codigo em ${System.currentTimeMillis() - inicioMs} ms")
                if (codigo == 200) {
                    return try {
                        val partes = JSONObject(texto).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        val sb = StringBuilder()
                        if (partes != null) for (i in 0 until partes.length()) sb.append(partes.optJSONObject(i)?.optString("text").orEmpty())
                        val t = sb.toString().trim().trim('"', '“', '”')
                        if (t.equals("VAZIO", true) || t.equals("VAZIO.", true)) "" else t
                    } catch (e: Exception) { null }
                }
                Log.d(TAG, "transcrever $modelo: HTTP $codigo ${texto.replace(Regex("""\s+"""), " ").take(300)}")
                if (codigo == 401 || codigo == 403) return null
                if (codigo == 503 || codigo == 429) { Thread.sleep(500); continue }
                break
            }
        }
        return null
    }

    /** O que a pessoa quer: [intencao] = "musica", "video" ou "pergunta"; [busca] = o melhor texto para procurar no YouTube. */
    class PedidoMidia(val intencao: String, val busca: String)

    /**
     * A caixa é voltada para música: o Gemini descobre QUAL música/vídeo a pessoa quer, mesmo com artista trocado ou faltando
     * ("não quero me amarrar Solange Almeida" -> "Não Quero Me Amarrar Aviões do Forró"), e se o pedido nem é de mídia
     * (uma pergunta). Devolve null se não conseguiu (sem internet, chave...): quem chama usa o texto como veio.
     */
    fun interpretarMidia(ctx: Context, dito: String): PedidoMidia? {
        val (chave, _) = lerConfig(ctx, "gemini.txt", "", MODELO_GEMINI_PADRAO) ?: return null
        val instrucao = "Você é o cérebro de uma caixa de som inteligente brasileira, voltada para MÚSICA. A pessoa disse (transcrição " +
            "de voz, pode ter erros de reconhecimento): \"$dito\".\n" +
            "Decida a intenção:\n" +
            "- \"musica\": quer ouvir uma música, artista, álbum ou playlist (inclui só dizer o nome da música ou do artista);\n" +
            "- \"playlist\": quer uma PLAYLIST ou um gênero/estilo inteiro, sem música específica (disse playlist, sertanejo, forró, rock, " +
            "mais tocadas, as melhores, sucessos...); em busca ponha como procurar a playlist (ex.: 'as melhores do sertanejo', 'forró mais tocadas');\n" +
            "- \"video\": quer ASSISTIR um vídeo, clipe ou show (disse vídeo, clipe, assistir, ver, ao vivo);\n" +
            "- \"pergunta\": quer uma informação ou resposta (clima, notícias, quem é, quanto custa, quantos graus...).\n" +
            "Se for música ou vídeo, descubra QUAL é, corrigindo erros de reconhecimento e mesmo que o artista dito esteja errado " +
            "ou faltando (ex.: 'não quero me amarrar Solange Almeida' -> 'Não Quero Me Amarrar Aviões do Forró'). Em \"busca\" ponha o " +
            "melhor texto para procurar no YouTube, no formato 'Título Artista'. Se pedir só um artista, busca é o artista. " +
            "Se for pergunta, busca vazia. Responda SÓ o JSON: {\"intencao\":\"musica|video|playlist|pergunta\",\"busca\":\"...\"}"
        val corpo = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", instrucao)))))
            .put("generationConfig", JSONObject().put("temperature", 0.1).put("maxOutputTokens", 200).put("responseMimeType", "application/json"))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODELO_GEMINI_PADRAO:generateContent")
            .header("x-goog-api-key", chave)
            .header("content-type", "application/json")
            .post(corpo.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val (codigo, texto) = Rede.cliente(ctx, leituraSeg = 12).newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
            if (codigo != 200) { Log.d(TAG, "interpretarMidia HTTP $codigo"); return null }
            val partes = JSONObject(texto).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (partes != null) for (i in 0 until partes.length()) sb.append(partes.optJSONObject(i)?.optString("text").orEmpty())
            val j = JSONObject(sb.toString().trim().removePrefix("```json").removeSuffix("```").trim())
            val intencao = j.optString("intencao", "").lowercase()
            if (intencao !in setOf("musica", "video", "playlist", "pergunta")) null else PedidoMidia(intencao, j.optString("busca", "").trim())
        } catch (e: Exception) {
            Log.d(TAG, "interpretarMidia falhou: $e")
            null
        }
    }

    /** O que lembrar ([texto], curto, no infinitivo) e quando ([quandoMs], instante no futuro). */
    class PedidoLembrete(val texto: String, val quandoMs: Long)

    /**
     * Pede ao Gemini para separar "o quê" e "quando" de um pedido de lembrete falado ("me lembra de ligar para o João amanhã às 3 da tarde").
     * O Gemini recebe a data e a hora de agora para resolver "daqui a 20 minutos", "sexta", "às 15h"... Devolve null se não achou o quando.
     */
    fun interpretarLembrete(ctx: Context, dito: String): PedidoLembrete? {
        val (chave, _) = lerConfig(ctx, "gemini.txt", "", MODELO_GEMINI_PADRAO) ?: return null
        val agora = java.util.Date()
        val dia = java.text.SimpleDateFormat("EEEE, dd/MM/yyyy", java.util.Locale("pt", "BR")).format(agora)
        val hora = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(agora)
        val instrucao = "Hoje é $dia e agora são $hora (horário de Brasília). Uma pessoa pediu a uma caixa de som um lembrete (transcrição de voz, " +
            "pode ter erros): \"$dito\".\n" +
            "Responda o que deve ser lembrado e QUANDO. \"texto\": curto, no infinitivo, sem \"me lembra de\" (ex.: \"ligar para o João\"). " +
            "\"quando\": data e hora no formato yyyy-MM-dd HH:mm, sempre NO FUTURO. Regras: \"daqui a X minutos/horas\" soma ao horário de agora; " +
            "só a hora (\"às 3\") vale para a próxima vez que ela chegar (se disse \"da tarde\" ou \"da noite\", some 12 horas); \"amanhã\", dias da " +
            "semana e \"dia 5\" valem para a próxima data; sem hora mas com período: manhã = 08:00, tarde = 15:00, noite = 20:00; só o dia sem hora = 09:00. " +
            "Se não der para saber QUANDO, deixe \"quando\" vazio. Responda SÓ o JSON: {\"texto\":\"...\",\"quando\":\"...\"}"
        val corpo = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", instrucao)))))
            .put("generationConfig", JSONObject().put("temperature", 0.0).put("maxOutputTokens", 200).put("responseMimeType", "application/json"))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODELO_GEMINI_PADRAO:generateContent")
            .header("x-goog-api-key", chave)
            .header("content-type", "application/json")
            .post(corpo.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val (codigo, texto) = Rede.cliente(ctx, leituraSeg = 12).newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
            if (codigo != 200) { Log.d(TAG, "interpretarLembrete HTTP $codigo"); return null }
            val partes = JSONObject(texto).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (partes != null) for (i in 0 until partes.length()) sb.append(partes.optJSONObject(i)?.optString("text").orEmpty())
            val j = JSONObject(sb.toString().trim().removePrefix("```json").removeSuffix("```").trim())
            val oQue = j.optString("texto", "").trim()
            val quando = j.optString("quando", "").trim()
            Log.d(TAG, "lembrete: \"$oQue\" em \"$quando\"")
            if (oQue.isEmpty() || quando.isEmpty()) return null
            val ms = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse(quando)?.time ?: return null
            if (ms < System.currentTimeMillis() - 30_000L) null else PedidoLembrete(oQue, ms)
        } catch (e: Exception) {
            Log.d(TAG, "interpretarLembrete falhou: $e")
            null
        }
    }

    /** Resultado de [entenderPedido]: o que foi dito e, se for mídia, qual (já interpretado). */
    class Entendimento(val texto: String, val pedido: PedidoMidia?)

    /**
     * UMA chamada ao Gemini faz as duas coisas: transcreve o áudio do pedido e já decide a intenção (música, vídeo ou pergunta)
     * e qual música/vídeo é (corrigindo artista errado ou nome mal ouvido). Mais rápido que duas chamadas em sequência.
     * Devolve null se não conseguiu (sem internet, chave recusada, travou); texto vazio = sem fala clara.
     */
    fun entenderPedido(ctx: Context, wav: ByteArray): Entendimento? {
        val (chave, modeloConfig) = lerConfig(ctx, "gemini.txt", "", MODELO_GEMINI_PADRAO) ?: return null
        val base64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
        val instrucao = "Você é o cérebro de uma caixa de som inteligente brasileira, voltada para MÚSICA. O áudio é um pedido falado " +
            "em português do Brasil. Faça duas coisas:\n" +
            "1) \"texto\": transcreva exatamente o que a pessoa disse (vazio se não houver fala clara).\n" +
            "2) Decida a \"intencao\": \"musica\" (quer ouvir música, artista, álbum ou playlist; inclui só dizer o nome da música ou do " +
            "artista), \"video\" (quer ASSISTIR vídeo, clipe ou show: disse vídeo, clipe, assistir, ver, ao vivo), \"pergunta\" (quer uma " +
            "informação: clima, notícias, quem é, quanto custa, quantos graus...), \"playlist\" (quer uma PLAYLIST ou um gênero/estilo " +
            "inteiro, sem música específica: playlist, sertanejo, forró, rock, mais tocadas, as melhores, sucessos; em busca ponha como " +
            "procurar a playlist, ex.: 'as melhores do sertanejo') ou \"comando\" (controlar a caixa: volume, pausar, " +
            "próxima, luzes, alarme, que horas são, abrir uma tela...).\n" +
            "Se for música ou vídeo, descubra QUAL é, corrigindo erros de reconhecimento e mesmo que o artista dito esteja errado ou " +
            "faltando (ex.: 'não quero me amarrar Solange Almeida' -> 'Não Quero Me Amarrar Aviões do Forró'); em \"busca\" ponha o melhor " +
            "texto para procurar no YouTube ('Título Artista'; se pedir só um artista, o artista). Nos outros casos, busca vazia.\n" +
            "IMPORTANTE: se o áudio tiver só ruído, silêncio, música ao fundo ou voz que você não consegue entender com certeza, responda texto vazio " +
            "(\"texto\":\"\") e intencao \"comando\". NUNCA invente um pedido que não foi dito.\n" +
            "Responda SÓ o JSON: {\"texto\":\"...\",\"intencao\":\"musica|video|playlist|pergunta|comando\",\"busca\":\"...\"}"
        for (modelo in listOf(MODELO_GEMINI_PADRAO, modeloConfig).distinct()) {
            val r = corrida(3500L, 3, 15000L) { entenderUma(ctx, chave, modelo, instrucao, base64) }
            if (r != null) return r
        }
        return null
    }

    /** Uma tentativa de entender o pedido; null se falhou (rede, limite, resposta ilegível). */
    private fun entenderUma(ctx: Context, chave: String, modelo: String, instrucao: String, base64: String): Entendimento? {
        val corpo = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("text", instrucao))
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", base64))))))
            .put("generationConfig", JSONObject().put("temperature", 0.0).put("maxOutputTokens", 300).put("responseMimeType", "application/json"))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$modelo:generateContent")
            .header("x-goog-api-key", chave).header("content-type", "application/json")
            .post(corpo.toString().toRequestBody("application/json".toMediaType())).build()
        val inicioMs = System.currentTimeMillis()
        val (codigo, texto) = try {
            Rede.cliente(ctx, leituraSeg = 12).newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: Exception) { Log.d(TAG, "entenderPedido $modelo: falha de rede: $e"); return null }
        Log.d(TAG, "entenderPedido $modelo: HTTP $codigo em ${System.currentTimeMillis() - inicioMs} ms")
        if (codigo != 200) { Log.d(TAG, "entenderPedido $modelo: ${texto.replace(Regex("""\s+"""), " ").take(200)}"); return null }
        return try {
            val partes = JSONObject(texto).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (partes != null) for (i in 0 until partes.length()) sb.append(partes.optJSONObject(i)?.optString("text").orEmpty())
            val j = JSONObject(sb.toString().trim().removePrefix("```json").removeSuffix("```").trim())
            val dito = j.optString("texto", "").trim()
            val intencao = j.optString("intencao", "").lowercase()
            val busca = j.optString("busca", "").trim()
            Entendimento(dito, if (intencao in setOf("musica", "video", "playlist", "pergunta", "comando")) PedidoMidia(intencao, busca) else null)
        } catch (e: Exception) { Log.d(TAG, "entenderPedido: resposta ilegivel: $e"); null }
    }

    /**
     * Pede a mesma coisa em paralelo quando a primeira tentativa demora: começa uma, e a cada [escalonaMs] sem resposta lança outra
     * (até [tentativas]); uma que falha dispara a próxima na hora. Devolve a primeira que der certo, ou null depois de [totalMs].
     * Acaba com as respostas que travam por muitos segundos de vez em quando.
     */
    private fun <T : Any> corrida(escalonaMs: Long, tentativas: Int, totalMs: Long, tarefa: () -> T?): T? {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(tentativas)
        val fila = java.util.concurrent.ExecutorCompletionService<T?>(pool)
        try {
            val limite = System.currentTimeMillis() + totalMs
            var lancadas = 0
            var terminadas = 0
            fun lancar() { fila.submit(java.util.concurrent.Callable<T?> { tarefa() }); lancadas++ }
            lancar()
            while (terminadas < lancadas || lancadas < tentativas) {
                val restante = limite - System.currentTimeMillis()
                if (restante <= 0) return null
                val espera = if (lancadas < tentativas) Math.min(escalonaMs, restante) else restante
                val f = fila.poll(espera, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (f == null) { if (lancadas < tentativas) lancar(); continue }
                terminadas++
                val v = try { f.get() } catch (e: Exception) { null }
                if (v != null) return v
                if (lancadas < tentativas) lancar()
            }
            return null
        } finally { pool.shutdown() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun getJson(ctx: Context, url: String): JSONObject? = try {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        Rede.cliente(ctx, leituraSeg = 10).newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                Log.d(TAG, "resposta HTTP ${r.code} em $url")
                null
            } else JSONObject(r.body?.string() ?: "{}")
        }
    } catch (e: Exception) {
        Log.d(TAG, "falha ao buscar $url: $e")
        null
    }
}
