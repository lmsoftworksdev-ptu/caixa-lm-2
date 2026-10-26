package br.com.lmsoftworks.caixa2

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import kotlin.math.min

/**
 * Baixa o video do YouTube em PEDACOS pequenos (como o NewPipe faz).
 *
 * O servidor do YouTube (googlevideo) segura e "trava" a entrega quando o pedido e um fluxo unico e
 * longo; com pedidos curtos em sequencia ele entrega rapido e sem paradas. O tamanho total vem do
 * parametro clen= da propria URL.
 *
 * Primeiro tenta pedir cada pedaco pelo parametro de URL &range=inicio-fim (formato usado pelo
 * YouTube); se o servidor recusar, cai para o cabecalho HTTP "Range".
 */
@UnstableApi
class YoutubeChunkedDataSource(
    private val fabricaHttp: HttpDataSource.Factory,
    private val tamanhoPedaco: Long = 3L * 1024 * 1024
) : DataSource {

    private val ouvintes = ArrayList<TransferListener>()
    private var http: HttpDataSource? = null
    private var dataSpec: DataSpec? = null

    private var posicao = 0L // proxima posicao absoluta a entregar
    private var fim = 0L // fim absoluto (exclusivo) do que foi pedido
    private var fimPedaco = 0L // fim absoluto (exclusivo) do pedaco aberto
    private var contador = 0
    private var usarCabecalho = false

    override fun addTransferListener(transferListener: TransferListener) {
        ouvintes.add(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        posicao = dataSpec.position
        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
            ?: throw IOException("URL sem clen: nao da para baixar em pedacos")
        fim = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else total
        try {
            abrirPedaco()
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (!usarCabecalho && e.responseCode in setOf(400, 403, 416)) {
                Log.d("Midia", "pedaco por &range= recusado (${e.responseCode}); usando cabecalho Range")
                usarCabecalho = true
                abrirPedaco()
            } else {
                throw e
            }
        }
        return fim - posicao
    }

    private fun abrirPedaco() {
        val spec = dataSpec ?: throw IOException("fechado")
        val inicio = posicao
        val ultimo = min(inicio + tamanhoPedaco, fim) - 1
        fimPedaco = ultimo + 1

        http?.close()
        http = null
        val h = fabricaHttp.createDataSource()
        ouvintes.forEach { h.addTransferListener(it) }

        val pedido = if (!usarCabecalho) {
            val uri: Uri = spec.uri.buildUpon()
                .appendQueryParameter("range", "$inicio-$ultimo")
                .appendQueryParameter("rn", (++contador).toString())
                .build()
            spec.buildUpon().setUri(uri).setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build()
        } else {
            spec.buildUpon().setPosition(inicio).setLength(ultimo - inicio + 1).build()
        }
        h.open(pedido)
        http = h
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var tentativas = 0
        while (true) {
            if (posicao >= fim) return C.RESULT_END_OF_INPUT
            if (posicao >= fimPedaco) abrirPedaco()
            val h = http ?: throw IOException("fechado")
            val quero = min(length.toLong(), fimPedaco - posicao).toInt()
            val n = h.read(buffer, offset, quero)
            if (n == C.RESULT_END_OF_INPUT) {
                // O servidor entregou menos do que o esperado: abre outro pedaco de onde parou.
                if (++tentativas > 3) throw IOException("pedaco do YouTube vazio em $posicao")
                fimPedaco = posicao // forca reabrir
                continue
            }
            posicao += n
            return n
        }
    }

    override fun getUri(): Uri? = http?.uri ?: dataSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = http?.responseHeaders ?: emptyMap()

    override fun close() {
        http?.close()
        http = null
        dataSpec = null
    }
}
