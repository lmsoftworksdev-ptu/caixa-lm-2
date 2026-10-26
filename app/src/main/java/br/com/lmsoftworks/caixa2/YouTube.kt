package br.com.lmsoftworks.caixa2

import android.util.Log
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import kotlin.math.abs

data class Resultado(
    val titulo: String,
    val canal: String,
    val duracaoSeg: Long,
    val url: String,
    val miniaturas: List<String> = emptyList(),
    val visualizacoes: Long = -1
) {
    /** A melhor imagem primeiro; as seguintes são reserva se ela não existir. */
    val miniatura: String? get() = miniaturas.firstOrNull()

    /** "1,2 mi de visualizações", "860 mil de visualizações"; vazio se o YouTube não informou. */
    val visualizacoesTexto: String
        get() = when {
            visualizacoes < 0 -> ""
            visualizacoes >= 1_000_000 -> "%.1f mi".format(visualizacoes / 1_000_000.0).replace('.', ',').replace(",0 ", " ") + " de visualizações"
            visualizacoes >= 1_000 -> "${visualizacoes / 1_000} mil de visualizações"
            else -> "$visualizacoes visualizações"
        }

    val duracaoTexto: String
        get() = if (duracaoSeg <= 0) "AO VIVO" else {
            val h = duracaoSeg / 3600
            val m = (duracaoSeg % 3600) / 60
            val s = duracaoSeg % 60
            if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
}

/** O que o player precisa para tocar: uma ou duas URLs (video + audio separados). */
data class Fonte(val videoUrl: String?, val audioUrl: String?, val unicaUrl: String?, val relacionados: List<Resultado> = emptyList())

object YouTube {
    private val service get() = ServiceList.YouTube

    @Volatile
    private var iniciado = false

    /** Deixa o NewPipe pronto antes do primeiro pedido (a primeira busca fica mais rápida). */
    fun aquecer() { Thread { try { garantirInicio() } catch (_: Throwable) {} }.start() }

    private fun garantirInicio() {
        if (iniciado) return
        synchronized(this) {
            if (iniciado) return
            NewPipe.init(
                AppDownloader.instance,
                Localization("pt", "BR"),
                ContentCountry("BR")
            )
            iniciado = true
        }
    }

    /** Bloqueante: chame fora da thread principal. */
    fun buscar(consulta: String, musica: Boolean): List<Resultado> {
        garantirInicio()
        val filtro = if (musica) YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        else YoutubeSearchQueryHandlerFactory.VIDEOS

        var itens = executarBusca(consulta, filtro)
        // Se a busca de musicas nao devolver nada, cai para a busca comum.
        if (itens.isEmpty() && musica) {
            itens = executarBusca(consulta, YoutubeSearchQueryHandlerFactory.VIDEOS)
        }
        return itens
    }

    /** "Em alta": o YouTube não tem mais a página de tendências, então usamos buscas que trazem o que está mais visto. */
    fun emAlta(musica: Boolean): List<Resultado> =
        buscar(if (musica) "músicas mais tocadas do brasil" else "vídeos em alta brasil hoje", musica)

    private fun executarBusca(consulta: String, filtro: String): List<Resultado> {
        val handler = service.searchQHFactory.fromQuery(consulta, listOf(filtro), "")
        val info = SearchInfo.getInfo(service, handler)
        return info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map {
                Resultado(
                    titulo = it.name ?: "(sem titulo)",
                    canal = it.uploaderName ?: "",
                    duracaoSeg = it.duration,
                    url = it.url,
                    miniaturas = melhoresMiniaturas(it.thumbnails.map { t -> t.url }, it.url),
                    visualizacoes = it.viewCount
                )
            }
    }

    /**
     * O YouTube manda miniaturas pequenas na busca. O mesmo endereço, com outro nome de arquivo (hq720.jpg: 1280x720), traz a
     * versão grande; capas de música (googleusercontent) aceitam o tamanho no fim do endereço.
     */
    private fun melhoresMiniaturas(originais: List<String>, urlVideo: String): List<String> {
        val lista = ArrayList<String>()
        val id = Regex("[?&]v=([\\w-]{11})").find(urlVideo)?.groupValues?.get(1)
        val base = originais.lastOrNull()
        if (base != null && (base.contains("googleusercontent.com") || base.contains("ggpht.com"))) {
            lista.add(base.replace(Regex("=w\\d+-h\\d+.*$"), "=w544-h544-l90-rj"))
        } else if (id != null) {
            lista.add("https://i.ytimg.com/vi/$id/hq720.jpg")
        }
        if (base != null) lista.add(base)
        return lista
    }

    private fun relacionadosDe(info: StreamInfo): List<Resultado> =
        info.relatedItems.filterIsInstance<StreamInfoItem>().take(15).map {
            Resultado(it.name ?: "(sem titulo)", it.uploaderName ?: "", it.duration, it.url,
                melhoresMiniaturas(it.thumbnails.map { t -> t.url }, it.url), it.viewCount)
        }

    /** Bloqueante: chame fora da thread principal. */
    fun resolver(url: String, soAudio: Boolean): Fonte {
        garantirInicio()
        val info = StreamInfo.getInfo(service, url)
        return resolverDe(info, soAudio).copy(relacionados = try { relacionadosDe(info) } catch (e: Exception) { emptyList() })
    }

    private fun resolverDe(info: StreamInfo, soAudio: Boolean): Fonte {

        // So a faixa de audio ORIGINAL (nunca a dublada automatica); sem marcacao = a unica faixa do video.
        val todos = info.audioStreams.filter { it.content.isNotEmpty() }
        val originais = todos.filter {
            val t = it.audioTrackType
            t == null || t == org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL
        }
        val audio = (if (originais.isNotEmpty()) originais else todos)
            .minByOrNull { abs(it.averageBitrate - 128) }

        if (soAudio) {
            val a = audio?.content
            if (a != null) return Fonte(null, null, a)
            info.hlsUrl?.takeIf { it.isNotEmpty() }?.let { return Fonte(null, null, it) }
            error("Nenhum audio disponivel")
        }

        // Diagnostico: o que o YouTube ofereceu para este video.
        Log.d(
            "Midia",
            "streams so-video: " + info.videoOnlyStreams.joinToString { "${it.height}p/${it.format?.name}/${it.codec}" } +
                " | combinados: " + info.videoStreams.joinToString { "${it.height}p/${it.format?.name}" } +
                " | audio: " + (audio?.averageBitrate ?: -1)
        )

        // 1) HD: video e audio separados, H.264 (que o G3 decodifica em hardware), na altura mais perto
        //    de 720p (a tela horizontal do G3 e 1280x720), no maximo 1080p. Se nao houver 720p,
        //    prefere a de cima (1080p) a uma de baixa resolucao.
        val h264 = info.videoOnlyStreams.filter {
            it.content.isNotEmpty() && it.height <= 1080 &&
                it.format == org.schabi.newpipe.extractor.MediaFormat.MPEG_4 // H.264 (mp4)
        }
        // A MELHOR imagem disponivel (ate 1080p), sempre HD: nunca abaixo de 720p.
        // A tela da caixa é 1280x720: 1080p não mostra nada a mais e tem o dobro de dados (travava o vídeo ao carregar). Usa 720p;
        // só sobe para 1080p se não houver 720p.
        val hd = h264.filter { it.height >= 720 }.minByOrNull { it.height }
        if (hd != null && audio != null) return Fonte(hd.content, audio.content, null)

        // 1b) Sem H.264 HD (alguns videos so oferecem VP9 em HD): VP9 em 720p. O G3 nao tem decodificador
        //     de hardware para VP9 (usa o de software), entao ficamos em 720p para nao perder quadros.
        val vp9 = info.videoOnlyStreams
            .filter {
                it.content.isNotEmpty() && it.format == org.schabi.newpipe.extractor.MediaFormat.WEBM &&
                    it.height in 720..720
            }
            .maxByOrNull { it.height }
        if (vp9 != null && audio != null) {
            Log.d("Midia", "sem H.264 HD: usando VP9 ${vp9.height}p")
            return Fonte(vp9.content, audio.content, null)
        }

        // 2) Fluxo ja combinado (audio + video; normalmente 360p), se nao houver HD.
        val combinado = info.videoStreams
            .filter { it.content.isNotEmpty() }
            .maxByOrNull { it.height }
        if (combinado != null) return Fonte(null, null, combinado.content)

        // 2b) Qualquer video separado que exista (mesmo abaixo de HD).
        val qualquer = h264.maxByOrNull { it.height }
        if (qualquer != null && audio != null) return Fonte(qualquer.content, audio.content, null)

        // 3) Ao vivo / HLS
        info.hlsUrl?.takeIf { it.isNotEmpty() }?.let { return Fonte(null, null, it) }
        error("Nenhum video disponivel")
    }
}
