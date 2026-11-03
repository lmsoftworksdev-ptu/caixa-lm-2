package br.com.lmsoftworks.caixa2

import android.util.Log
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
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
data class Fonte(val videoUrl: String?, val audioUrl: String?, val unicaUrl: String?, val relacionados: List<Resultado> = emptyList(), val musica: Boolean = true, val visualizacoes: Long = -1, val publicadoEm: Long = 0L)

object YouTube {
    private val service get() = ServiceList.YouTube

    /** Velocidade de download medida pelo player (bits/s, média suavizada); 0 = ainda não medida. */
    @Volatile var bandaBps = 0L
    fun medirBanda(bps: Long) {
        if (bps <= 0) return
        bandaBps = if (bandaBps == 0L) bps else (bandaBps * 0.5 + bps * 0.5).toLong()
    }

    /** O vídeo é sempre HD (720p, ou 1080p quando não há 720p): decisão do dono da caixa, mesmo com a internet lenta. */
    private fun alturaAlvo(): Int = 720

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

    /**
     * O "Mix" do YouTube para um vídeo (a lista de "Mix - <artista>" que aparece ao lado no site): playlist automática com o endereço
     * do vídeo mais list=RD<código>. Funciona sem conta. Bloqueante: chame fora da thread principal. Vazio se não vier.
     */
    fun mix(urlVideo: String, musica: Boolean): List<Resultado> {
        garantirInicio()
        val id = Regex("[?&]v=([\\w-]{11})").find(urlVideo)?.groupValues?.get(1) ?: return emptyList()
        val info = PlaylistInfo.getInfo(service, "https://www.youtube.com/watch?v=$id&list=RD$id")
        return info.relatedItems.filter { it.duration > 0 && (!musica || it.duration in 60..900) }.map { paraResultado(it) }
    }

    private fun semAcento(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    private val PALAVRAS_DE_PEDIDO = setOf("videoclipe", "clipe", "oficial", "official", "video", "audio", "musica", "the", "and", "dos", "das", "com", "feat")

    /**
     * Quanto um resultado parece ser o vídeo OFICIAL do que foi pedido: vídeo oficial costuma estar no canal do próprio artista (o nome
     * do canal tem palavras do pedido) ou no VEVO, ter "oficial" no título e vir entre os primeiros. Cover, karaokê, letra, remix etc.
     * perdem pontos, a não ser que a pessoa tenha pedido isso. [posicao] = lugar do resultado na busca (0 = primeiro).
     */
    fun pontosOficial(r: Resultado, consulta: String, posicao: Int): Double {
        val pedido = semAcento(consulta).split(" ").filter { it.length > 2 && it !in PALAVRAS_DE_PEDIDO }.toSet()
        val canal = semAcento(r.canal.removeSuffix(" - Topic"))
        val titulo = semAcento(r.titulo)
        var pts = 0.0
        val palavrasCanal = canal.replace("vevo", " ").split(" ").filter { it.length > 2 && it !in PALAVRAS_DE_PEDIDO }
        if (palavrasCanal.isNotEmpty() && palavrasCanal.all { it in pedido || semAcento(consulta).contains(it) }) pts += 4.0       // canal do artista
        else if (palavrasCanal.any { it in pedido }) pts += 2.0
        if (canal.contains("vevo")) pts += 1.5
        if (Regex("\\b(oficial|official)\\b").containsMatchIn(titulo)) pts += 1.5
        for (ruim in listOf("karaoke", "cover", "remix", "reaction", "tutorial", "instrumental", "slowed", "sped up", "nightcore", "legendado", "playback", "aula", "como tocar")) {
            if (titulo.contains(ruim) && !semAcento(consulta).contains(ruim)) pts -= 3.0
        }
        if (titulo.contains("lyric") || titulo.contains("letra")) { if (!semAcento(consulta).let { it.contains("lyric") || it.contains("letra") }) pts -= 1.5 }
        pts += (1.5 - 0.3 * posicao).coerceAtLeast(0.0)      // quem veio primeiro na busca leva uma vantagem pequena
        return pts
    }

    /** A mesma lista, com os vídeos que parecem oficiais na frente. */
    fun preferirOficial(lista: List<Resultado>, consulta: String): List<Resultado> =
        lista.withIndex().sortedByDescending { (i, r) -> pontosOficial(r, consulta, i) }.map { it.value }

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

    private val EM_INGLES = listOf(
        "official music video" to "Videoclipe Oficial", "official lyric video" to "Vídeo com Letra Oficial", "official video" to "Vídeo Oficial",
        "official audio" to "Áudio Oficial", "official visualizer" to "Visualizador Oficial", "official trailer" to "Trailer Oficial",
        "official performance video" to "Vídeo da Apresentação", "performance video" to "Vídeo da Apresentação", "music video" to "Videoclipe",
        "lyric video" to "Vídeo com Letra", "lyrics video" to "Vídeo com Letra", "lyrics" to "Letra", "visualizer" to "Visualizador",
        "behind the scenes" to "Bastidores", "full album" to "Álbum Completo", "remastered" to "Remasterizado", "acoustic" to "Acústico",
        "live" to "Ao Vivo", "audio" to "Áudio", "official" to "Oficial"
    ).map { (en, pt) -> Regex("""(?<![\p{L}])${Regex.escape(en)}(?![\p{L}])""", RegexOption.IGNORE_CASE) to pt }

    /** Passa para português as marcas em inglês dos títulos ("(Official Video)" vira "(Vídeo Oficial)"); nome de música e de artista ficam como são. */
    fun emPortugues(titulo: String): String {
        var t = titulo
        for ((re, pt) in EM_INGLES) t = re.replace(t, pt)
        return t
    }

    private val FORA_DE_MUSICA = listOf(
        "react", "reacao", "reagindo", "reaction", "podcast", "entrevista", "corte", "cortes", "tutorial", "aula", "como fazer", "como tocar",
        "vlog", "gameplay", "review", "analise", "bastidores", "making of", "shorts", "compilado", "melhores momentos", "meme", "humor",
        "stand up", "noticia", "jornal", "trailer", "episodio", "capitulo", "dublado", "resumo", "explicando", "explicacao", "historia de",
        "minuto", "polemica", "treta", "fofoca", "debate", "palestra", "curso", "unboxing", "teste de"
    )
    private val SINAL_DE_MUSICA = listOf("clipe", "videoclipe", "official", "oficial", "video", "audio", "ao vivo", "lyric", "letra", "dvd", "acustico", "feat", "part.")

    /**
     * Esse vídeo é música (clipe, áudio, show)? Entra quem tem de 1,5 a 12 minutos, não tem cara de reação, entrevista, aula, corte
     * etc., e tem sinal de música (clipe, oficial, "Artista - Música", canal do artista, VEVO...) ou é do mesmo canal do que está tocando.
     */
    fun pareceMusica(titulo: String, canal: String, duracaoSeg: Long, canalAtual: String): Boolean {
        if (duracaoSeg in 1..89 || duracaoSeg > 720) return false
        val t = java.text.Normalizer.normalize(titulo.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        if (FORA_DE_MUSICA.any { t.contains(it) }) return false
        val c = java.text.Normalizer.normalize(canal.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        if (canalAtual.isNotEmpty() && canal.removeSuffix(" - Topic").equals(canalAtual.removeSuffix(" - Topic"), true)) return true
        if (SINAL_DE_MUSICA.any { t.contains(it) }) return true
        if (c.contains("vevo") || c.endsWith("topic") || c.contains("oficial") || c.contains("official") || c.contains("music") || c.contains("musica") || c.contains("records") || c.contains("gravadora")) return true
        return Regex("""\S+ [-–|] \S+""").containsMatchIn(t)      // "Artista - Música"
    }

    /**
     * "Em alta": as paradas do próprio YouTube (YouTube Charts, Brasil), sem plano B montado por buscas: se o serviço não responder, a
     * tela avisa em vez de mostrar uma lista inventada. Vídeos = "Em alta" nos videoclipes; músicas = as mais ouvidas da semana.
     */
    fun emAlta(musica: Boolean): List<Resultado> {
        val r = parada(musica)
        if (r.size < 4) throw java.io.IOException("parada vazia")
        return r
    }

    /**
     * Uma fileira de gênero ("Sertanejo em alta"): o que o YouTube traz primeiro para o gênero deste ano, só músicas (ou só clipes).
     * Bloqueante: chame fora da thread principal.
     */
    fun generoEmAlta(termo: String, musica: Boolean): List<Resultado> {
        val consulta = if (musica) "$termo mais tocadas 2026" else "$termo clipe oficial 2026"
        val vistos = HashSet<String>()
        return buscar(consulta, musica)
            .filter { if (musica) it.duracaoSeg in 60..600 else it.duracaoSeg in 90..720 }
            .filter { vistos.add(it.titulo.lowercase() + "|" + it.canal.lowercase()) }
            .take(20)
    }

    /**
     * YouTube Charts (charts.youtube.com), Brasil: "Em alta" nos videoclipes (os que mais crescem agora) e as músicas mais ouvidas.
     * É o mesmo serviço que o site de paradas do YouTube usa; devolve as 12 primeiras.
     */
    private fun parada(musica: Boolean): List<Resultado> {
        val tipo = if (musica) "TRACKS" else "VIDEOS"
        val corpo = org.json.JSONObject()
            .put("context", org.json.JSONObject().put("client", org.json.JSONObject()
                .put("clientName", "WEB_MUSIC_ANALYTICS").put("clientVersion", "2.0").put("hl", "pt").put("gl", "BR").put("theme", "MUSIC")))
            .put("browseId", "FEmusic_analytics_charts_home")
            .put("query", "perspective=CHART&chart_params_country_code=br&chart_params_chart_type=$tipo&chart_params_period_type=WEEKLY")
        val con = java.net.URL("https://charts.youtube.com/youtubei/v1/browse?alt=json&key=AIzaSyBUPetSUmoZL-OhlxA7wSac5XinrygCqMo").openConnection() as java.net.HttpURLConnection
        con.requestMethod = "POST"; con.doOutput = true
        con.connectTimeout = 8000; con.readTimeout = 12000
        con.setRequestProperty("Content-Type", "application/json")
        con.setRequestProperty("Referer", "https://charts.youtube.com/")
        con.setRequestProperty("Origin", "https://charts.youtube.com")
        con.setRequestProperty("User-Agent", AppDownloader.USER_AGENT)
        con.outputStream.use { it.write(corpo.toString().toByteArray()) }
        val texto = con.inputStream.bufferedReader().use { it.readText() }
        val conteudo = org.json.JSONObject(texto).getJSONObject("contents").getJSONObject("sectionListRenderer")
            .getJSONArray("contents").getJSONObject(0).getJSONObject("musicAnalyticsSectionRenderer").getJSONObject("content")
        val saida = ArrayList<Resultado>()
        fun miniaturas(id: String) = listOf("https://i.ytimg.com/vi/$id/hq720.jpg", "https://i.ytimg.com/vi/$id/hqdefault.jpg")
        if (!musica) {
            val listas = conteudo.getJSONArray("videos")
            // "Em alta" (TRENDING_CHART); se não vier, as mais vistas
            var escolhida: org.json.JSONArray? = null
            for (preferida in listOf("TRENDING_CHART", "TOP_VIEWS_CHART")) {
                for (i in 0 until listas.length()) if (listas.getJSONObject(i).optString("listType") == preferida) { escolhida = listas.getJSONObject(i).getJSONArray("videoViews"); break }
                if (escolhida != null && escolhida.length() > 0) break
            }
            if (escolhida != null) for (i in 0 until minOf(escolhida.length(), 12)) {
                val v = escolhida.getJSONObject(i)
                val id = v.optString("id")
                if (id.isEmpty()) continue
                saida.add(Resultado(emPortugues(v.optString("title")), v.optString("channelName"), v.optLong("videoDuration", 0), "https://www.youtube.com/watch?v=$id", miniaturas(id), v.optString("viewCount").toLongOrNull() ?: -1L))
            }
        } else {
            val tipos = conteudo.getJSONArray("trackTypes")
            val faixas = tipos.getJSONObject(0).getJSONArray("trackViews")
            for (i in 0 until minOf(faixas.length(), 12)) {
                val f = faixas.getJSONObject(i)
                // a versão "só áudio" oficial (capa do álbum, sem vinheta) quando existir
                val id = f.optString("atvExternalVideoId").ifEmpty { f.optString("encryptedVideoId") }
                if (id.isEmpty()) continue
                val artistas = f.optJSONArray("artists")
                val nomes = (0 until (artistas?.length() ?: 0)).joinToString(", ") { artistas!!.getJSONObject(it).optString("name") }
                saida.add(Resultado(emPortugues(f.optString("name")), nomes, 200, "https://www.youtube.com/watch?v=$id", miniaturas(id), f.optString("viewCount").toLongOrNull() ?: -1L))
            }
        }
        return saida
    }

    private fun executarBusca(consulta: String, filtro: String): List<Resultado> {
        val handler = service.searchQHFactory.fromQuery(consulta, listOf(filtro), "")
        val info = SearchInfo.getInfo(service, handler)
        return info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map { paraResultado(it) }
    }

    private fun paraResultado(it: StreamInfoItem) = Resultado(
        titulo = emPortugues(it.name ?: "(sem titulo)"),
        canal = it.uploaderName ?: "",
        duracaoSeg = it.duration,
        url = it.url,
        miniaturas = melhoresMiniaturas(it.thumbnails.map { t -> t.url }, it.url),
        visualizacoes = it.viewCount
    )

    /**
     * Bloqueante: procura uma playlist ("as melhores do sertanejo", "forró mais tocadas"...) e devolve o nome dela com as
     * músicas (até 50, só as que têm duração: sem transmissões ao vivo). Tenta as 3 primeiras; null se nenhuma servir.
     */
    fun playlist(consulta: String): Pair<String, List<Resultado>>? {
        garantirInicio()
        val handler = service.searchQHFactory.fromQuery(consulta, listOf(YoutubeSearchQueryHandlerFactory.PLAYLISTS), "")
        val achadas = SearchInfo.getInfo(service, handler).relatedItems.filterIsInstance<PlaylistInfoItem>()
        for (p in achadas.take(3)) {
            try {
                val itens = PlaylistInfo.getInfo(service, p.url).relatedItems
                    .filter { it.duration > 0 }.take(50).map { paraResultado(it) }
                if (itens.size >= 3) return (p.name ?: consulta) to itens
            } catch (e: Exception) { Log.w("Midia", "playlist ${p.url} falhou: $e") }
        }
        return null
    }

    /**
     * Bloqueante: uma playlist de [consulta] que ainda não foi usada ([usadas] guarda os endereços): (nome, endereço, itens com duração).
     * Serve para emendar playlists do mesmo tema; null se nenhuma nova servir.
     */
    fun playlistNova(consulta: String, usadas: Set<String>, exigirArtista: String? = null): Triple<String, String, List<Resultado>>? {
        garantirInicio()
        val handler = service.searchQHFactory.fromQuery(consulta, listOf(YoutubeSearchQueryHandlerFactory.PLAYLISTS), "")
        val achadas = SearchInfo.getInfo(service, handler).relatedItems.filterIsInstance<PlaylistInfoItem>()
        for (p in achadas.take(8)) {
            if (p.url in usadas) continue
            try {
                var itens = PlaylistInfo.getInfo(service, p.url).relatedItems.filter { it.duration > 0 }.take(50).map { paraResultado(it) }
                if (exigirArtista != null) {
                    // playlist DO artista: o nome dela traz o artista e a maioria das faixas também (nada de playlist misturada)
                    val a = semAcento(exigirArtista)
                    if (!semAcento(p.name ?: "").contains(a)) continue
                    val dele = itens.filter { semAcento(it.titulo + " " + it.canal).contains(a) }
                    if (dele.size < 5 || dele.size * 100 < itens.size * 60) continue
                    itens = dele
                }
                if (itens.size >= 5) return Triple(p.name ?: consulta, p.url, itens)
            } catch (e: Exception) { Log.w("Midia", "playlist ${p.url} falhou: $e") }
        }
        return null
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
            Resultado(emPortugues(it.name ?: "(sem titulo)"), it.uploaderName ?: "", it.duration, it.url,
                melhoresMiniaturas(it.thumbnails.map { t -> t.url }, it.url), it.viewCount)
        }

    /** Bloqueante: chame fora da thread principal. */
    fun resolver(url: String, soAudio: Boolean): Fonte {
        garantirInicio()
        val info = StreamInfo.getInfo(service, url)
        val categoria = try { info.category.orEmpty().lowercase() } catch (e: Exception) { "" }
        val ehMusica = categoria.contains("mus") || pareceMusica(info.name.orEmpty(), info.uploaderName.orEmpty(), info.duration, "")
        val publicado = try { info.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0L } catch (e: Throwable) { 0L }
        return resolverDe(info, soAudio).copy(relacionados = try { relacionadosDe(info) } catch (e: Exception) { emptyList() }, musica = ehMusica,
            visualizacoes = info.viewCount, publicadoEm = publicado)
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
        val alvo = alturaAlvo()
        Log.d("Midia", "qualidade escolhida: até ${alvo}p (banda ${bandaBps / 1_000_000.0} Mbps)")
        val hd = if (alvo >= 720) h264.filter { it.height >= 720 }.minByOrNull { it.height }
                 else h264.filter { it.height <= alvo }.maxByOrNull { it.height } ?: h264.minByOrNull { it.height }
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
