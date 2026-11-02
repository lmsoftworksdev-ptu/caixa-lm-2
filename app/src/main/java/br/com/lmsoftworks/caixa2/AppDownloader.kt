package br.com.lmsoftworks.caixa2

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * O YouTube bloqueia ("Sign in to confirm you're not a bot") os endereços IPv6 da operadora; pelo IPv4 funciona. Só IPv4 (e, se o
 * nome não tiver IPv4, o que houver). O endereço do vídeo também fica preso ao IP de quem o pediu, por isso o player usa IPv4 também.
 */
object SoIpv4 : okhttp3.Dns {
    override fun lookup(hostname: String): List<java.net.InetAddress> {
        val todos = okhttp3.Dns.SYSTEM.lookup(hostname)
        val v4 = todos.filter { it is java.net.Inet4Address }
        return if (v4.isNotEmpty()) v4 else todos
    }
}

/** Ponte entre o NewPipe Extractor e o OkHttp. */
class AppDownloader private constructor(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val url = request.url()
        var data = request.dataToSend()
        // Tudo em português do Brasil: alguns pedidos do YouTube saem com idioma inglês e voltam com o título traduzido
        // ("Reserved Seat" em vez de "Cadeira Cativa"). O idioma de cada pedido é forçado para pt-BR aqui.
        if (data != null && url.contains("youtube.com")) {
            val texto = String(data, Charsets.UTF_8)
            val idioma = Regex(""""hl"\s*:\s*"([^"]*)"""")
            val achou = idioma.find(texto)?.groupValues?.get(1)
            if (achou != null && !achou.startsWith("pt")) {
                android.util.Log.d("Idioma", "pedido ${url.substringAfter("youtube.com").substringBefore('?')} vinha com hl=$achou -> pt-BR")
                data = idioma.replace(texto, "\"hl\":\"pt\"").toByteArray(Charsets.UTF_8)
            }
        }
        val body = data?.toRequestBody()

        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "pt-BR,pt;q=0.9")

        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("Muitas requisicoes (429)", url)
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString()
            )
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

        val instance: AppDownloader by lazy {
            AppDownloader(
                OkHttpClient.Builder()
                    .dns(SoIpv4)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .build()
            )
        }
    }
}
