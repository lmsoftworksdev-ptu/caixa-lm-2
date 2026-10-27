package br.com.lmsoftworks.caixa2.voz

import android.content.Context
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

        for ((modelo, busca) in candidatos) {
            var r = geminiUma(ctx, chave, modelo, pergunta, busca)
            // 503 = modelo sobrecarregado por um instante: tenta de novo antes de desistir.
            var vezes = 0
            while (r.texto == null && r.codigo == 503 && vezes++ < 2) {
                Thread.sleep(1500)
                r = geminiUma(ctx, chave, modelo, pergunta, busca)
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
            val ip = getJson(ctx, "https://ipwho.is/") ?: return null
            if (!ip.optBoolean("success", false)) return null
            nome = ip.optString("city")
            lat = ip.optDouble("latitude")
            lon = ip.optDouble("longitude")
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
                val (codigo, texto) = try {
                    Rede.cliente(ctx, leituraSeg = 15).newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
                } catch (e: Exception) {
                    Log.d(TAG, "transcrever $modelo: falha de rede: $e")
                    return null
                }
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
                if (codigo == 503 || codigo == 429) { Thread.sleep(1200); continue }
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
            "- \"video\": quer ASSISTIR um vídeo, clipe ou show (disse vídeo, clipe, assistir, ver, ao vivo);\n" +
            "- \"pergunta\": quer uma informação ou resposta (clima, notícias, quem é, quanto custa, quantos graus...).\n" +
            "Se for música ou vídeo, descubra QUAL é, corrigindo erros de reconhecimento e mesmo que o artista dito esteja errado " +
            "ou faltando (ex.: 'não quero me amarrar Solange Almeida' -> 'Não Quero Me Amarrar Aviões do Forró'). Em \"busca\" ponha o " +
            "melhor texto para procurar no YouTube, no formato 'Título Artista'. Se pedir só um artista, busca é o artista. " +
            "Se for pergunta, busca vazia. Responda SÓ o JSON: {\"intencao\":\"musica|video|pergunta\",\"busca\":\"...\"}"
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
            if (intencao !in setOf("musica", "video", "pergunta")) null else PedidoMidia(intencao, j.optString("busca", "").trim())
        } catch (e: Exception) {
            Log.d(TAG, "interpretarMidia falhou: $e")
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
            "informação: clima, notícias, quem é, quanto custa, quantos graus...) ou \"comando\" (controlar a caixa: volume, pausar, " +
            "próxima, luzes, alarme, que horas são, abrir uma tela...).\n" +
            "Se for música ou vídeo, descubra QUAL é, corrigindo erros de reconhecimento e mesmo que o artista dito esteja errado ou " +
            "faltando (ex.: 'não quero me amarrar Solange Almeida' -> 'Não Quero Me Amarrar Aviões do Forró'); em \"busca\" ponha o melhor " +
            "texto para procurar no YouTube ('Título Artista'; se pedir só um artista, o artista). Nos outros casos, busca vazia.\n" +
            "IMPORTANTE: se o áudio tiver só ruído, silêncio, música ao fundo ou voz que você não consegue entender com certeza, responda texto vazio " +
            "(\"texto\":\"\") e intencao \"comando\". NUNCA invente um pedido que não foi dito.
" +
            "Responda SÓ o JSON: {\"texto\":\"...\",\"intencao\":\"musica|video|pergunta|comando\",\"busca\":\"...\"}"
        for (modelo in listOf(MODELO_GEMINI_PADRAO, modeloConfig).distinct()) {
            var tentativas = 0
            while (tentativas++ < 2) {
                val corpo = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                        .put(JSONObject().put("text", instrucao))
                        .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", base64))))))
                    .put("generationConfig", JSONObject().put("temperature", 0.0).put("maxOutputTokens", 300).put("responseMimeType", "application/json"))
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$modelo:generateContent")
                    .header("x-goog-api-key", chave).header("content-type", "application/json")
                    .post(corpo.toString().toRequestBody("application/json".toMediaType())).build()
                val (codigo, texto) = try {
                    Rede.cliente(ctx, leituraSeg = 12).newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
                } catch (e: Exception) { Log.d(TAG, "entenderPedido $modelo: falha de rede: $e"); break }
                if (codigo == 200) {
                    try {
                        val partes = JSONObject(texto).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        val sb = StringBuilder()
                        if (partes != null) for (i in 0 until partes.length()) sb.append(partes.optJSONObject(i)?.optString("text").orEmpty())
                        val j = JSONObject(sb.toString().trim().removePrefix("```json").removeSuffix("```").trim())
                        val dito = j.optString("texto", "").trim()
                        val intencao = j.optString("intencao", "").lowercase()
                        val busca = j.optString("busca", "").trim()
                        return Entendimento(dito, if (intencao in setOf("musica", "video", "pergunta", "comando")) PedidoMidia(intencao, busca) else null)
                    } catch (e: Exception) { Log.d(TAG, "entenderPedido: resposta ilegivel: $e"); break }
                }
                Log.d(TAG, "entenderPedido $modelo: HTTP $codigo ${texto.replace(Regex("""\s+"""), " ").take(200)}")
                if (codigo == 401 || codigo == 403) return null
                if (codigo == 503 || codigo == 429) { Thread.sleep(800); continue }
                break
            }
        }
        return null
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
