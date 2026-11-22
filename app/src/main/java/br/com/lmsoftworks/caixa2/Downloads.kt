package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Downloads de músicas: só a lista inteira de um Mix (o que aparece em "Próximas"), para ouvir sem internet. Cada Mix vira uma pasta
 * (<app>/downloads/<id>/) com os áudios (01.bin, 02.bin...), a capa de cada faixa (01.jpg...), a capa do Mix (as quatro primeiras capas
 * juntas em um quadrado, capa.jpg) e o mix.json com os nomes. Vídeo não entra: é pesado demais.
 */
object Downloads {
    private const val TAG = "Downloads"
    /** Sobra mínima de espaço livre depois do download: abaixo disso o app avisa que a memória não basta. */
    const val RESERVA = 300L * 1024 * 1024
    private const val BYTES_POR_SEGUNDO = 20_000L      // áudio do YouTube (Opus 160 kb/s) ≈ 20 KB por segundo
    private const val PEDACO = 2L * 1024 * 1024

    class Faixa(val titulo: String, val canal: String, val duracaoSeg: Long, val arquivo: File, val capa: File?, val url: String = "")
    class Mix(val id: String, val nome: String, val criadoEm: Long, val faixas: List<Faixa>, val bytes: Long, val capa: File?, val pasta: File)

    /** O que está sendo baixado agora (null = nada). [fracao] vai de 0 a 1 no Mix todo. */
    class Andamento(val nome: String, val total: Int, val concluidas: Int, val fracao: Float)

    @Volatile var andamento: Andamento? = null; private set
    /** Mensagem do fim do último download ("Mix salvo" ou o erro): a tela mostra por uns segundos. */
    @Volatile var aviso: String? = null; private set
    @Volatile var avisoEm = 0L; private set

    private val ouvintes = CopyOnWriteArrayList<() -> Unit>()
    private val principal = Handler(Looper.getMainLooper())
    fun ouvir(o: () -> Unit) { ouvintes.add(o) }
    fun desouvir(o: () -> Unit) { ouvintes.remove(o) }
    private var ultimaNotificacao = 0L
    /** Avisa as telas. Durante o download no máximo ~1,5 vez por segundo: cada aviso redesenha barras e textos, e centenas deles travavam a tela. */
    private fun notificar(forcar: Boolean = true) {
        val agora = android.os.SystemClock.uptimeMillis()
        if (!forcar && agora - ultimaNotificacao < 700L) return
        ultimaNotificacao = agora
        principal.post { ouvintes.forEach { it() } }
    }

    private fun raiz(ctx: Context): File =
        File(ctx.applicationContext.getExternalFilesDir(null) ?: ctx.applicationContext.filesDir, "downloads").apply { mkdirs() }

    // ---------------------------------------------------------------- consulta

    /** A lista lida em memória: [jaBaixado] roda a cada meio segundo na tela e não pode reler os arquivos toda vez. */
    @Volatile private var guardados: List<Mix>? = null

    // As expressões são montadas uma vez só e a chave de cada título fica guardada: antes cada conferência montava dezenas de Regex e rodava o
    // normalizador de texto para cada música, na thread da tela, a cada meio segundo (era o engasgo da lista de Próximas).
    private val reAcento = Regex("""\p{M}+""")
    private val reParenteses = Regex("""\(.*?\)|\[.*?]""")
    private val reNaoAlfa = Regex("[^a-z0-9]+")
    private val reId = Regex("""[?&]v=([\w-]{11})""")
    private val chaves = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun chaveDe(titulo: String, canal: String): String {
        val bruta = "$titulo$canal"
        chaves[bruta]?.let { return it }
        val k = java.text.Normalizer.normalize((titulo + " " + canal).lowercase(), java.text.Normalizer.Form.NFD)
            .replace(reAcento, "").replace(reParenteses, " ").replace(reNaoAlfa, " ").trim()
        if (chaves.size > 4000) chaves.clear()
        chaves[bruta] = k
        return k
    }

    private fun idDe(url: String): String = reId.find(url)?.groupValues?.get(1) ?: ""

    private class ChavesMix(val ids: Set<String>, val titulos: Set<String>)
    private val chavesDosMix = java.util.concurrent.ConcurrentHashMap<String, ChavesMix>()
    @Volatile private var versao = 0
    private fun mudou() { guardados = null; chavesDosMix.clear(); versao++ }

    /**
     * Esta lista (a fila de "Próximas") já foi baixada? Sim quando pelo menos 60% das músicas dela estão em algum Mix baixado, conferindo pelo
     * código do vídeo (Mix baixados antes de existir o código são conferidos pelo título e artista). Pesado: não chamar na thread da tela
     * (use [jaBaixadoEmCache]).
     */
    fun jaBaixado(ctx: Context, fila: List<Resultado>): Boolean {
        if (fila.isEmpty()) return false
        if (fila[0].url.startsWith("file:")) return true      // é a lista de um Mix baixado tocando
        val mixes = guardados ?: listar(ctx)
        val minhas = fila.map { r -> idDe(r.url) to chaveDe(r.titulo, r.canal.removeSuffix(" - Topic")) }
        for (m in mixes) {
            val c = chavesDosMix.getOrPut(m.id) {
                ChavesMix(m.faixas.mapNotNull { idDe(it.url).ifEmpty { null } }.toHashSet(), m.faixas.map { chaveDe(it.titulo, it.canal) }.toHashSet())
            }
            val achadas = minhas.count { (id, k) -> (id.isNotEmpty() && id in c.ids) || k in c.titulos }
            if (achadas * 100 >= fila.size * 60) return true
        }
        return false
    }

    @Volatile private var jaBaixadoValor = false
    @Volatile private var jaBaixadoChave = ""

    /**
     * Para a tela (que pergunta a cada meio segundo): devolve o último resultado conhecido na hora e, só quando a fila mudou (ou um Mix
     * foi baixado ou excluído), refaz a conta em segundo plano.
     */
    fun jaBaixadoEmCache(ctx: Context, fila: List<Resultado>): Boolean {
        if (fila.isEmpty()) return false
        if (fila[0].url.startsWith("file:")) return true
        val chave = "${System.identityHashCode(fila)}|${fila.size}|$versao"
        if (chave != jaBaixadoChave) {
            jaBaixadoChave = chave
            val app = ctx.applicationContext
            Tarefas.executar {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                jaBaixadoValor = try { jaBaixado(app, fila) } catch (e: Exception) { false }
            }
        }
        return jaBaixadoValor
    }

    /** Os Mix baixados, do mais novo para o mais antigo. */
    fun listar(ctx: Context): List<Mix> {
        val pastas = raiz(ctx).listFiles { f -> f.isDirectory && !f.name.endsWith(".tmp") } ?: return emptyList()
        return pastas.mapNotNull { lerMix(it) }.sortedByDescending { it.criadoEm }.also { guardados = it }
    }

    private fun lerMix(pasta: File): Mix? = try {
        val j = JSONObject(File(pasta, "mix.json").readText())
        val faixas = j.getJSONArray("faixas").let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val arquivo = File(pasta, o.getString("arquivo")).takeIf { it.isFile } ?: return@mapNotNull null
                Faixa(o.optString("titulo"), o.optString("canal"), o.optLong("duracao"), arquivo, o.optString("capa").takeIf { it.isNotEmpty() }?.let { File(pasta, it) }?.takeIf { it.isFile }, o.optString("url"))
            }
        }
        if (faixas.isEmpty()) null
        else Mix(j.getString("id"), j.getString("nome"), j.optLong("criadoEm"), faixas, tamanho(pasta), File(pasta, "capa.jpg").takeIf { it.isFile }, pasta)
    } catch (e: Exception) { Log.w(TAG, "mix ilegível em ${pasta.name}", e); null }

    private fun tamanho(f: File): Long = if (f.isFile) f.length() else (f.listFiles()?.sumOf { tamanho(it) } ?: 0L)

    /** Quanto de memória os downloads ocupam (inclui o que está sendo baixado agora). */
    fun usado(ctx: Context): Long {
        // Pastas ".tmp" são downloads que não terminaram (o app fechou no meio, a internet caiu): sem nenhum download rodando elas são lixo e são
        // apagadas aqui; antes ficavam contando como "espaço usado" (e ocupando memória) mesmo com a lista de Downloads vazia.
        if (andamento == null) raiz(ctx).listFiles { f -> f.isDirectory && f.name.endsWith(".tmp") }?.forEach { it.deleteRecursively() }
        return tamanho(raiz(ctx))
    }

    fun livre(ctx: Context): Long = try { StatFs(raiz(ctx).path).availableBytes } catch (e: Exception) { 0L }
    fun total(ctx: Context): Long = try { StatFs(raiz(ctx).path).totalBytes } catch (e: Exception) { 0L }

    /** Quanto a lista deve ocupar (pela duração das faixas; as capas somam um pouco). */
    fun estimar(fila: List<Resultado>): Long = fila.sumOf { (if (it.duracaoSeg > 0) it.duracaoSeg else 210L) * BYTES_POR_SEGUNDO + 60_000L }

    fun cabe(ctx: Context, fila: List<Resultado>): Boolean = livre(ctx) - estimar(fila) > RESERVA

    fun excluir(mix: Mix) { mix.pasta.deleteRecursively(); mudou() }

    /** O Mix como lista tocável: o endereço de cada faixa é o arquivo no aparelho (o player toca direto, sem internet). */
    fun comoResultados(mix: Mix): List<Resultado> = mix.faixas.map {
        Resultado(it.titulo, it.canal, it.duracaoSeg, Uri.fromFile(it.arquivo).toString(), listOfNotNull(it.capa?.let { c -> Uri.fromFile(c).toString() }))
    }

    // ---------------------------------------------------------------- download

    // Duas faixas por vez e com a prioridade mais baixa: o Moto G3 tem pouca força e o download (TLS, decodificar capas, extrair os endereços)
    // tirava o fôlego da tela e do áudio.
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST); r.run() } }
    /** Velocidade máxima por faixa (bytes/s): baixar a toda velocidade era o que mais pesava. Antes 800 KB/s; agora 2 MB/s por faixa (a internet da casa aguenta), 2 faixas somam até uns 4 MB/s. */
    private const val LIMITE_BPS = 2_000_000L

    /** O botão Cancelar foi tocado: as faixas em andamento param e o que já foi baixado dessa lista é apagado. */
    @Volatile private var cancelado = false
    /** O cancelamento foi pedido e as faixas ainda estão parando: a barra fica parada (vermelha) em vez de correr até 100%. */
    @Volatile var cancelando = false; private set
    fun cancelar() {
        if (andamento == null) return
        cancelado = true
        cancelando = true
        notificar(true)
        Log.d(TAG, "download cancelado pela pessoa")
    }

    private fun definirAviso(texto: String) { aviso = texto; avisoEm = System.currentTimeMillis() }

    /** Começa a baixar a lista inteira como um Mix novo. false se já há um download em andamento ou a lista está vazia. */
    @Synchronized
    fun iniciar(ctx: Context, fila: List<Resultado>): Boolean {
        if (andamento != null || cancelando || fila.isEmpty()) return false
        val app = ctx.applicationContext
        val artista = fila[0].canal.removeSuffix(" - Topic").ifBlank { fila[0].titulo }
        val nome = "Mix de $artista"
        cancelado = false
        andamento = Andamento(nome, fila.size, 0, 0f)
        aviso = null
        notificar()
        Thread({ baixarMix(app, fila, nome) }, "download-mix").start()
        return true
    }

    private fun baixarMix(ctx: Context, fila: List<Resultado>, nome: String) {
        val id = System.currentTimeMillis().toString()
        val tmp = File(raiz(ctx), "$id.tmp").apply { mkdirs() }
        val n = fila.size
        val partes = AtomicIntegerArray(n)      // progresso de cada faixa, de 0 a 1000
        val concluidas = AtomicInteger(0)
        val ok = arrayOfNulls<Faixa>(n)
        val capas = arrayOfNulls<Bitmap>(n)
        val fim = CountDownLatch(n)
        fun atualizar(forcar: Boolean = false) {
            if (cancelado) return      // cancelando: o progresso não anda mais (antes as faixas paradas enchiam a barra até 100%)
            var soma = 0L
            for (i in 0 until n) soma += partes.get(i)
            andamento = Andamento(nome, n, concluidas.get(), soma / (n * 1000f))
            notificar(forcar)
        }
        for (i in 0 until n) pool.execute {
            val r = fila[i]
            val base = "%02d".format(i + 1)
            try {
                if (cancelado) error("cancelado")
                // uma extração por vez e com uma pausa depois: o extrator do YouTube gera muito lixo de memória, e várias seguidas deixavam a tela engasgando
                val fonte = synchronized(trava) { try { YouTube.resolver(r.url, soAudio = true) } finally { try { Thread.sleep(1200L) } catch (e: InterruptedException) { } } }
                if (cancelado) error("cancelado")
                val audio = fonte.unicaUrl ?: fonte.audioUrl ?: error("sem áudio")
                val arquivo = File(tmp, "$base.bin")
                var ultimo = -1
                baixarArquivo(audio, arquivo) { lido, total ->
                    val p = if (total > 0) (lido * 1000 / total).toInt().coerceIn(0, 999) else 500
                    if (p != ultimo) { ultimo = p; partes.set(i, p); if (p % 50 == 0) atualizar() }
                }
                val capa = baixarCapa(r)
                var arquivoCapa: File? = null
                if (capa != null) {
                    arquivoCapa = File(tmp, "$base.jpg")
                    try { FileOutputStream(arquivoCapa).use { capa.compress(Bitmap.CompressFormat.JPEG, 85, it) } } catch (e: Exception) { arquivoCapa = null }
                    if (i < 4) capas[i] = capa
                }
                ok[i] = Faixa(r.titulo, r.canal.removeSuffix(" - Topic"), r.duracaoSeg, arquivo, arquivoCapa, r.url)
            } catch (e: Exception) {
                Log.w(TAG, "faixa ${i + 1} '${r.titulo}' falhou", e)
                File(tmp, "$base.bin").delete()
            } finally {
                partes.set(i, 1000)
                concluidas.incrementAndGet()
                atualizar(forcar = true)
                fim.countDown()
            }
        }
        fim.await()
        if (cancelado) {
            tmp.deleteRecursively()
            andamento = null
            cancelando = false
            definirAviso("Download cancelado")
            notificar()
            return
        }
        val faixas = ok.filterNotNull()
        if (faixas.isEmpty()) {
            tmp.deleteRecursively()
            andamento = null
            definirAviso("Não consegui baixar este Mix. Confira a internet e tente de novo.")
            notificar()
            return
        }
        // a capa do Mix: as quatro primeiras capas juntas em um quadrado
        try {
            val quatro = capas.filterNotNull().take(4)
            if (quatro.isNotEmpty()) {
                val lado = 512
                val juntas = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
                val tela = Canvas(juntas)
                val meio = lado / 2
                for (k in 0 until 4) {
                    val b = quatro[k % quatro.size]      // com menos de quatro capas, repete as que há
                    val x = (k % 2) * meio; val y = (k / 2) * meio
                    tela.drawBitmap(b, Rect(0, 0, b.width, b.height), Rect(x, y, x + meio, y + meio), null)
                }
                FileOutputStream(File(tmp, "capa.jpg")).use { juntas.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                juntas.recycle()
            }
        } catch (e: Exception) { Log.w(TAG, "capa do Mix falhou", e) }
        val j = JSONObject().put("id", id).put("nome", nome).put("criadoEm", System.currentTimeMillis())
            .put("faixas", JSONArray().also { a -> faixas.forEach { f ->
                a.put(JSONObject().put("titulo", f.titulo).put("canal", f.canal).put("duracao", f.duracaoSeg)
                    .put("arquivo", f.arquivo.name).put("capa", f.capa?.name ?: "").put("url", f.url))
            } })
        File(tmp, "mix.json").writeText(j.toString())
        tmp.renameTo(File(raiz(ctx), id))
        mudou()
        andamento = null
        definirAviso(if (faixas.size == n) "$nome salvo em Downloads" else "$nome salvo (${faixas.size} de $n músicas)")
        notificar()
    }

    /** Baixa [url] em [destino], em pedaços de 2 MB como o player (o servidor do YouTube trava em pedidos longos de uma vez só). */
    private fun baixarArquivo(url: String, destino: File, aoProgresso: (Long, Long) -> Unit) {
        val uri = Uri.parse(url)
        val total = if (uri.host?.contains("googlevideo.com") == true) uri.getQueryParameter("clen")?.toLongOrNull() else null
        FileOutputStream(destino).use { saida ->
            if (total == null) {
                copiar(abrir(url, null), saida, 0L, -1L, aoProgresso)
                return
            }
            var pos = 0L
            var porCabecalho = false
            while (pos < total) {
                val ultimo = minOf(pos + PEDACO, total) - 1
                var con: HttpURLConnection = if (porCabecalho) abrir(url, "bytes=$pos-$ultimo")
                    else abrir(uri.buildUpon().appendQueryParameter("range", "$pos-$ultimo").build().toString(), null)
                if (con.responseCode !in 200..299 && !porCabecalho) { con.disconnect(); porCabecalho = true; con = abrir(url, "bytes=$pos-$ultimo") }
                if (con.responseCode !in 200..299) throw java.io.IOException("servidor respondeu ${con.responseCode}")
                pos += copiar(con, saida, pos, total, aoProgresso)
            }
        }
    }

    private fun abrir(url: String, faixa: String?): HttpURLConnection {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15_000; con.readTimeout = 20_000
        con.setRequestProperty("User-Agent", AppDownloader.USER_AGENT)
        if (faixa != null) con.setRequestProperty("Range", faixa)
        return con
    }

    private fun copiar(con: HttpURLConnection, saida: FileOutputStream, ja: Long, total: Long, aoProgresso: (Long, Long) -> Unit): Long {
        var lido = 0L
        val inicio = System.nanoTime()
        con.inputStream.use { entrada ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (cancelado) throw java.io.IOException("cancelado")
                val k = entrada.read(buf)
                if (k < 0) break
                saida.write(buf, 0, k)
                lido += k
                aoProgresso(ja + lido, total)
                // segura a velocidade: se baixou mais rápido que o limite, espera o que falta
                val previsto = lido * 1_000_000_000L / LIMITE_BPS
                val gasto = System.nanoTime() - inicio
                if (gasto < previsto) try { Thread.sleep((previsto - gasto) / 1_000_000L) } catch (e: InterruptedException) { }
            }
        }
        con.disconnect()
        return lido
    }

    /**
     * A capa quadrada da faixa em 300 px: a MESMA da lista de Próximas, sem procurar nada. A lista já descobriu a capa de música (YouTube.capaDeMusicaConhecida)
     * e já baixou a imagem (cache de Miniaturas): o download só pega as duas coisas prontas. Se a lista ainda não terminou esta faixa, espera
     * um pouco (sem buscar por conta própria: as buscas pesavam e travavam a caixa); sem capa de música, vale o quadro do vídeo.
     */
    private fun baixarCapa(r: Resultado): Bitmap? {
        var conhecida = YouTube.capaDeMusicaConhecida(r)
        var espera = 0
        while (conhecida == null && espera < 12 && !cancelado) { try { Thread.sleep(500L) } catch (e: InterruptedException) { break }; espera++; conhecida = YouTube.capaDeMusicaConhecida(r) }
        val enderecos = (conhecida ?: emptyList()) + r.miniaturas
        // 1) a imagem que a lista já baixou
        var bytes: ByteArray? = Miniaturas.doCache(enderecos)
        // 2) senão, um único pedido direto à primeira imagem
        if (bytes == null) for (u in enderecos) {
            try { bytes = URL(u).openConnection().apply { connectTimeout = 5000; readTimeout = 8000 }.getInputStream().use { it.readBytes() }; break } catch (e: Exception) { }
        }
        val dados = bytes ?: return null
        return try {
            val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(dados, 0, dados.size, limites)
            var amostra = 1
            while (minOf(limites.outWidth, limites.outHeight) / (amostra * 2) >= 300) amostra *= 2
            val b = BitmapFactory.decodeByteArray(dados, 0, dados.size, BitmapFactory.Options().apply { inSampleSize = amostra }) ?: return null
            val lado = minOf(b.width, b.height)
            val recorte = Bitmap.createBitmap(b, (b.width - lado) / 2, (b.height - lado) / 2, lado, lado)
            val pronta = if (lado == 300) recorte else Bitmap.createScaledBitmap(recorte, 300, 300, true)
            if (recorte !== pronta && recorte !== b) recorte.recycle()
            if (b !== pronta) b.recycle()
            pronta
        } catch (e: Exception) { null }
    }

    /** Só uma extração de endereço por vez (é a parte mais pesada de cada faixa: o extrator do YouTube roda código e baixa páginas). */
    private val trava = Any()
}
