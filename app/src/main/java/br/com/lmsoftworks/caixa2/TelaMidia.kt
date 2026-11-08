package br.com.lmsoftworks.caixa2

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.net.URL
import java.util.concurrent.Executors

/**
 * Baixa e guarda as miniaturas dos vídeos e capas das músicas. Três camadas: memória (já decodificadas, por tamanho em bytes), disco
 * (os JPEG baixados, em pasta temporária que é apagada quando o app abre) e a internet. Poucos downloads ao mesmo tempo, em segundo plano.
 */
object Miniaturas {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(chave: String, b: Bitmap) = b.byteCount
    }
    private val fila = Executors.newFixedThreadPool(4) { r -> Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() } }
    private val filaRapida = Executors.newFixedThreadPool(8) { r -> Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() } }
    private val principal = Handler(Looper.getMainLooper())
    private var pasta: java.io.File? = null
    private var gravadas = 0

    /** Prepara a pasta temporária no cache do app. Começa vazia a cada abertura: as miniaturas só ficam guardadas enquanto o app roda. */
    fun iniciar(ctx: android.content.Context) {
        if (pasta != null) return
        pasta = java.io.File(ctx.applicationContext.cacheDir, "miniaturas").apply { deleteRecursively(); mkdirs() }
    }

    /** Apaga as miniaturas guardadas (memória e disco); devolve quantos bytes liberou do disco. */
    fun limparTudo(): Long {
        cache.evictAll()
        val p = pasta ?: return 0L
        val tam = p.listFiles()?.sumOf { it.length() } ?: 0L
        p.listFiles()?.forEach { it.delete() }
        return tam
    }

    private fun nomeDe(url: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Para cartões pequenos, o YouTube tem a versão de 320x180 (uns 10 KB) no lugar da de 1280x720: baixa bem mais rápido. */
    private fun ajustar(urls: List<String>, ladoMax: Int): List<String> {
        val primeira = urls.firstOrNull() ?: return urls
        if (ladoMax > 240 || !primeira.contains("i.ytimg.com/vi/") || !primeira.endsWith("hq720.jpg")) return urls
        return listOf(primeira.replace("hq720.jpg", "mqdefault.jpg")) + urls
    }

    /**
     * Tenta cada endereço da lista, do melhor para o pior, até um funcionar. [ladoMax] é a largura a partir da qual a imagem ainda é
     * reduzida pela metade ao decodificar: cartões pequenos (Mídia) usam um valor baixo, para não estourar a memória do Moto G3.
     */
    fun carregar(urls: List<String>, alvo: ImageView, ladoMax: Int = 480, rapido: Boolean = false) {
        val lista = ajustar(urls, ladoMax)
        val url = lista.firstOrNull()
        alvo.tag = url
        if (url == null) { alvo.setImageDrawable(null); return }
        val chave = "$ladoMax|$url"
        cache.get(chave)?.let { alvo.setImageBitmap(it); return }      // já na memória: aparece na hora, sem piscar
        alvo.setImageDrawable(null)
        (if (rapido) filaRapida else fila).execute {
            try {
                val arquivo = pasta?.let { java.io.File(it, nomeDe(url)) }
                var bytes: ByteArray? = try { arquivo?.takeIf { it.isFile && it.length() > 0 }?.readBytes() } catch (e: Exception) { null }
                if (bytes == null) {
                    // se nenhum endereço responder, tenta de novo (a rede da caixa oscila): até 4 rodadas, com pausa crescente
                    // rádios: logos quebrados são comuns; uma só tentativa e tempos curtos, para não segurar a fila das outras imagens
                    for (rodada in 0 until (if (rapido) 1 else 4)) {
                        for (u in lista) {
                            try { bytes = URL(u).openConnection().apply { connectTimeout = if (rapido) 3000 else 5000; readTimeout = if (rapido) 5000 else 8000 }.getInputStream().use { it.readBytes() }; break } catch (e: Exception) { }
                        }
                        if (bytes != null || rapido) break
                        Thread.sleep(1500L * (rodada + 1))
                    }
                    if (bytes == null) return@execute
                    try { arquivo?.writeBytes(bytes!!); if (++gravadas % 25 == 0) aparar() } catch (e: Exception) { }
                }
                val dados = bytes!!
                val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(dados, 0, dados.size, limites)
                var amostra = 1
                while (limites.outWidth / (amostra * 2) >= ladoMax) amostra *= 2
                val opcoes = BitmapFactory.Options().apply { inSampleSize = amostra; inPreferredConfig = Bitmap.Config.RGB_565 }
                val b = BitmapFactory.decodeByteArray(dados, 0, dados.size, opcoes) ?: return@execute
                cache.put(chave, b)
                principal.post { if (alvo.tag == url) alvo.setImageBitmap(b) }
            } catch (e: Exception) { }
        }
    }

    /** A pasta temporária não passa de uns 60 MB: acima disso apaga os arquivos mais antigos. */
    private fun aparar() {
        val arquivos = pasta?.listFiles() ?: return
        var total = arquivos.sumOf { it.length() }
        if (total <= 60L * 1024 * 1024) return
        for (f in arquivos.sortedBy { it.lastModified() }) { total -= f.length(); f.delete(); if (total <= 40L * 1024 * 1024) break }
    }
}

/**
 * Menu Mídia: barra de pesquisa com as etiquetas Música e Vídeo ao lado (o foco da caixa é música) e fileiras que rolam para os lados,
 * numa coluna que rola para baixo. Sem pesquisa mostra o que está em alta e, abaixo, uma fileira por gênero; com pesquisa, só o tipo
 * escolhido. Tocar num vídeo ou numa música chama [aoVideo] e [aoMusica] (a música toca a partir da fileira, só aquele nicho).
 */
class TelaMidia(
    private val c: Context,
    private val aoVideo: (List<Resultado>, Int, String) -> Unit,
    private val aoMusica: (List<Resultado>, Int, String) -> Unit,
    private val aoFalha: (Exception) -> Unit
) : FrameLayout(c) {

    companion object {
        private const val LIMITE = 12
        private const val LARGURA_VIDEO = 236
        private const val LADO_MUSICA = 128
        private const val VALIDADE_ALTA_MS = 30 * 60 * 1000L
        private const val LIMITE_GENERO = 20
        /** Gêneros das fileiras "em alta": nome na tela e o que se pesquisa para ele. */
        private val GENEROS = listOf(
            "Sertanejo" to "sertanejo", "Forró" to "forró", "Funk" to "funk", "Pagode" to "pagode",
            "Hip-hop" to "rap hip hop brasil", "Pop" to "pop brasil", "Rock" to "rock nacional", "Gospel" to "gospel"
        )
    }

    private val principal = Handler(Looper.getMainLooper())
    private var consulta = ""
    private var seq = 0
    private var altaV: List<Resultado>? = null
    private var altaM: List<Resultado>? = null
    private var altaEm = 0L
    private var res: List<Resultado>? = null      // resultado da pesquisa (só do tipo escolhido)
    private var falhaAltaV = false
    private var falhaAltaM = false
    /** Música (padrão: é o foco da caixa) ou Vídeo: decide o em alta, os gêneros e a pesquisa. */
    private var modoMusica = true
    private val prefs = c.getSharedPreferences("caixa_midia", Context.MODE_PRIVATE)
    private val generos = HashMap<String, List<Resultado>>()      // "m|Sertanejo" ou "v|Sertanejo"
    private val generosPedidos = HashSet<String>()
    private val tentativas = HashMap<String, Int>()
    private val buscasGuardadas = HashMap<String, List<Resultado>>()      // pesquisa já feita: voltar a ela não busca de novo
    private var tentativasAlta = 0
    private val rolagemM = ScrollView(c)
    private val rolagemV = ScrollView(c)
    private val areaM = LinearLayout(c)
    private val areaV = LinearLayout(c)
    private val fileirasM = ArrayList<HorizontalScrollView>()
    private val fileirasV = ArrayList<HorizontalScrollView>()
    private val slotsM = ArrayList<Pair<String, FrameLayout>>()
    private val slotsV = ArrayList<Pair<String, FrameLayout>>()
    private val prontoUmaVez = BooleanArray(2)      // a primeira carga de cada tipo terminou: dali em diante a troca de tipo é imediata
    private val carregandoTela = FrameLayout(c)
    private val mostrandoBusca = BooleanArray(2)      // [0] música, [1] vídeo: a coluna guardada está mostrando resultado de pesquisa
    // as telas de Música e de Vídeo ficam montadas e guardadas: trocar de tipo só mostra uma e esconde a outra (sem recarregar)
    private val rolagem: ScrollView get() = if (modoMusica) rolagemM else rolagemV
    private val area: LinearLayout get() = if (modoMusica) areaM else areaV
    private val fileiras: ArrayList<HorizontalScrollView> get() = if (modoMusica) fileirasM else fileirasV
    private val slots: ArrayList<Pair<String, FrameLayout>> get() = if (modoMusica) slotsM else slotsV
    private val falhaGenero = HashSet<String>()
    private val pilulas = ArrayList<Triple<FrameLayout, ImageView, TextView>>()      // (tem que vir antes do init, que monta as etiquetas)
    private var carregando = false
    private var erro: String? = null
    private var piscar: ValueAnimator? = null

    private val textoBusca = texto(c, "", 23, Tema.TEXTO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val dica = texto(c, "Pesquisar músicas", 23, Tema.TEXTO3)
    private val botaoLimpar = FrameLayout(c)
    private val microfone = FrameLayout(c)
    /** O microfone que aparece em cima do teclado (centralizado acima do 5 e do 6) quando o teclado está aberto. */
    private val microfoneTeclado = FrameLayout(c)
    /** "Abrir Wi-Fi" do estado sem internet. */
    var aoAbrirWifi: (() -> Unit)? = null
    /** Quem trata o toque no microfone (liga a escuta do assistente). */
    var aoMicrofone: (() -> Unit)? = null
    private var pulsoMic: ValueAnimator? = null
    private val barra = FrameLayout(c)
    private val teclado = FrameLayout(c)
    private val tecladoV3 = TecladoView(c)
    private var tecladoAberto = false

    private var cursorVisivel = true
    private val piscarCursor = object : Runnable {
        override fun run() { cursorVisivel = !cursorVisivel; escreverBusca(); if (tecladoAberto) principal.postDelayed(this, 530) }
    }
    private fun escreverBusca(t: String = consulta) { textoBusca.text = comCursor(t, tecladoAberto, cursorVisivel) }

    init {
        // ---- barra de pesquisa ----
        barra.apply {
            background = fundoArredondado(Color.parseColor("#26FFFFFF"), 42.d(), Cfg.LINHA)
            isClickable = true
            setOnClickListener { abrirTeclado() }
            efeitoToque()
        }
        val lupa = icone(c, R.drawable.ic_busca, 28, Color.parseColor("#D6E0F7"))
        barra.addView(lupa, LayoutParams(28.d(), 28.d(), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 26.d() })
        barra.addView(dica, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 74.d() })
        barra.addView(textoBusca, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = 74.d(); rightMargin = 70.d() })
        botaoLimpar.apply {
            background = fundoArredondado(Color.parseColor("#26FFFFFF"), 24.d())
            visibility = View.GONE
            isClickable = true
            setOnClickListener { consulta = ""; atualizarBarra(); buscar() }
            addView(icone(c, R.drawable.ic_fechar, 24, Color.WHITE), LayoutParams(24.d(), 24.d(), Gravity.CENTER))
        }
        barra.addView(botaoLimpar, LayoutParams(48.d(), 48.d(), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = 8.d() })
        microfone.apply {
            background = fundoArredondado(Color.parseColor("#33FFFFFF"), 24.d())
            isClickable = true
            setOnClickListener { aoMicrofone?.invoke() ?: avisoBusca("Busca por voz indisponível") }
            addView(icone(c, R.drawable.ic_mic, 28, Color.WHITE), LayoutParams(28.d(), 28.d(), Gravity.CENTER))
        }

        // ---- conteúdo ----
        for ((r, a) in listOf(rolagemM to areaM, rolagemV to areaV)) {
            a.orientation = LinearLayout.VERTICAL
            r.apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isFillViewport = true
                addView(a, LayoutParams(-1, -2))
                setOnScrollChangeListener { _, _, _, _, _ -> verificarVisiveis() }      // as fileiras de gênero só carregam quando chegam perto
            }
        }
        modoMusica = true      // a Mídia sempre abre em Música (o foco da caixa), nunca no Vídeo que ficou da última vez
        dica.text = textoDica()
        val topo = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        topo.addView(barra, LinearLayout.LayoutParams(0, 84.d(), 1f))
        topo.addView(montarEtiquetas(), LinearLayout.LayoutParams(-2, 84.d()).apply { leftMargin = 16.d() })
        val coluna = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        coluna.addView(topo, LinearLayout.LayoutParams(-1, 84.d()).apply { leftMargin = 48.d(); rightMargin = 48.d() })
        coluna.addView(rolagemM, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 8.d() })
        coluna.addView(rolagemV, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 8.d() })
        rolagemM.visibility = if (modoMusica) View.VISIBLE else View.GONE
        rolagemV.visibility = if (modoMusica) View.GONE else View.VISIBLE
        addView(coluna, LayoutParams(-1, -1).apply { topMargin = 32.d(); bottomMargin = 6.d() })      // a barra de busca fica em y 96 (a tela começa em 64)

        carregandoTela.apply {
            isClickable = true      // enquanto carrega, nada embaixo recebe toque: a navegação só libera quando está tudo pronto
            val caixa = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            caixa.addView(ProgressBar(c).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }, LinearLayout.LayoutParams(60.d(), 60.d()))
            caixa.addView(texto(c, "Carregando", 26, Tema.TEXTO, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 18.d() })
            addView(caixa, LayoutParams(-2, -2, Gravity.CENTER))
        }
        montarTeclado()
        addView(teclado, LayoutParams(-1, (720 - 192).d(), Gravity.BOTTOM))      // começa abaixo da barra e das etiquetas (que terminam em y 180)
        teclado.visibility = View.GONE
        addView(carregandoTela, LayoutParams(-1, -1))
        renderizar()
    }

    // ------------------------------------------------------------------ dados

    /** Chamada quando o menu aparece: carrega o "em alta" na primeira vez e de tempos em tempos. */
    fun aoMostrar() {
        val velho = System.currentTimeMillis() - altaEm > VALIDADE_ALTA_MS
        if (velho) { generos.clear(); generosPedidos.clear() }      // depois de um tempo, os gêneros também se renovam
        if (altaDoModo() == null || velho || erro != null) carregarAlta()
    }

    private fun altaDoModo() = if (modoMusica) altaM else altaV

    private fun temInternet(): Boolean {
        val n = (c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo
        return n != null && n.isConnected
    }

    /** O "em alta" do tipo escolhido: as paradas do próprio YouTube (sem plano B montado por buscas: se não vier, a fileira avisa). */
    private fun carregarAlta() {
        val minha = ++seq
        val musica = modoMusica
        carregando = true; erro = null
        renderizar()
        var r: List<Resultado>? = null; var falha: Exception? = null
        val t0 = android.os.SystemClock.uptimeMillis()
        Thread {
            try { r = YouTube.emAlta(musica) } catch (e: Exception) { falha = e }
            android.util.Log.d("MidiaTempo", "em alta " + (if (musica) "música" else "vídeo") + ": " + (android.os.SystemClock.uptimeMillis() - t0) + " ms, " + (r?.size ?: -1) + " itens")
            principal.post {
                if (minha != seq) return@post
                carregando = false
                if (r == null && !temInternet()) { falhou(falha) }
                else {
                    if (musica) { altaM = r.orEmpty().take(LIMITE); falhaAltaM = r == null } else { altaV = r.orEmpty().take(LIMITE); falhaAltaV = r == null }
                    altaEm = System.currentTimeMillis()
                    if (r == null && falha != null) aoFalha(falha!!)
                }
                renderizar()
                // falhou: tenta de novo sozinho em alguns segundos, até carregar
                if (r == null) principal.postDelayed({ if (consulta.isBlank() && !carregando && modoMusica == musica) { erro = null; carregarAlta() } }, 3_000L)
            }
        }.start()
    }

    private fun falhou(e: Exception?) {
        erro = if (!temInternet()) "Sem internet" else "Não consegui buscar agora"
        if (e != null && temInternet()) aoFalha(e)
    }

    private fun buscar() {
        val q = consulta.trim()
        val minha = ++seq
        rolagem.scrollTo(0, 0)
        if (q.isEmpty()) {
            res = null; carregando = false; erro = null
            if (altaDoModo() == null) carregarAlta() else renderizar()
            return
        }
        val musica = modoMusica
        val guardada = buscasGuardadas[(if (musica) "m|" else "v|") + q.lowercase()]
        if (guardada != null) { res = guardada; carregando = false; erro = null; renderizar(); return }
        carregando = true; erro = null
        renderizar()
        var r: List<Resultado>? = null; var falha: Exception? = null
        val t0 = android.os.SystemClock.uptimeMillis()
        Thread {
            try { r = YouTube.buscar(q, musica) } catch (e: Exception) { falha = e }
            android.util.Log.d("MidiaTempo", "pesquisa de " + (if (musica) "músicas" else "vídeos") + " '" + q + "': " + (android.os.SystemClock.uptimeMillis() - t0) + " ms, " + (r?.size ?: -1) + " itens")
            principal.post {
                if (minha != seq) return@post
                carregando = false
                if (r == null) { falhou(falha); res = null }
                else { res = r.orEmpty().take(LIMITE_GENERO); if (!res.isNullOrEmpty()) buscasGuardadas[(if (musica) "m|" else "v|") + q.lowercase()] = res!! }
                renderizar()
            }
        }.start()
    }

    // ------------------------------------------------------------------ etiquetas Música / Vídeo

    private fun montarEtiquetas(): View {
        val caixa = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = fundoArredondado(Color.parseColor("#26FFFFFF"), 42.d(), Cfg.LINHA)
            setPadding(6.d(), 6.d(), 6.d(), 6.d())
        }
        for ((rotulo, recurso, musica) in listOf(Triple("Música", R.drawable.ic_music, true), Triple("Vídeo", R.drawable.ic_video, false))) {
            val pilula = FrameLayout(c).apply { isClickable = true; setOnClickListener { trocarTipo(musica) } }
            val linha = LinearLayout(c).apply { gravity = Gravity.CENTER }
            val ic = icone(c, recurso, 28, Color.WHITE)
            val tx = texto(c, rotulo, 24, Color.WHITE, true)
            linha.addView(ic, LinearLayout.LayoutParams(28.d(), 28.d()))
            linha.addView(tx, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 8.d() })
            pilula.addView(linha, FrameLayout.LayoutParams(-2, -1, Gravity.CENTER))
            pilulas.add(Triple(pilula, ic, tx))
            caixa.addView(pilula, LinearLayout.LayoutParams(-2, -1).apply { if (!musica) leftMargin = 2.d() })
            pilula.setPadding(26.d(), 0, 26.d(), 0)
        }
        pintarEtiquetas()
        return caixa
    }

    private fun pintarEtiquetas() {
        pilulas.forEachIndexed { i, (pilula, ic, tx) ->
            val ativa = (i == 0) == modoMusica
            pilula.background = if (ativa) fundoArredondado(Color.WHITE, 36.d()) else null
            val cor = if (ativa) Color.parseColor("#0A1020") else Color.parseColor("#CCFFFFFF")
            ic.setColorFilter(cor); tx.setTextColor(cor)
        }
    }

    /** Música ou Vídeo: o em alta, os gêneros e a pesquisa passam a ser só desse tipo. */
    private fun trocarTipo(musica: Boolean) {
        if (musica == modoMusica) return
        modoMusica = musica
        pintarEtiquetas()
        dica.text = textoDica()
        rolagemM.visibility = if (musica) View.VISIBLE else View.GONE
        rolagemV.visibility = if (musica) View.GONE else View.VISIBLE
        res = null
        carregando = false
        if (consulta.isNotBlank()) { buscar(); return }
        if (altaDoModo() == null) { carregarAlta(); return }
        // a tela deste tipo já está montada e guardada: só reconstrói se estava mostrando uma pesquisa ou ainda está vazia
        if (area.childCount == 0 || mostrandoBusca[if (musica) 0 else 1]) renderizar() else { rolagem.post { verificarVisiveis() }; atualizarProntidao() }
    }

    /** Chamada logo que o app abre: deixa o em alta do tipo escolhido pronto antes de a pessoa abrir a Mídia. */
    fun preCarregar() {
        // as três primeiras linhas de cada tipo: o em alta e os dois primeiros gêneros, de Música E de Vídeo (ficam guardados na memória)
        precarregarAlta(true); precarregarAlta(false)
        for ((nome, termo) in GENEROS.take(2)) {
            carregarGenero("m|$nome", termo, true)
            carregarGenero("v|$nome", termo, false)
        }
    }

    /** Busca o em alta de um tipo sem mexer na tela: guarda o resultado e só desenha se for o tipo que está na tela. */
    private fun precarregarAlta(musica: Boolean) {
        if ((if (musica) altaM else altaV) != null) return
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val r = try { YouTube.emAlta(musica) } catch (e: Exception) { null }
            principal.post {
                if (r == null) { principal.postDelayed({ precarregarAlta(musica) }, 3_000L * (++tentativasAlta).coerceAtMost(20)); return@post }      // tenta de novo sozinho
                if (musica) altaM = r.take(LIMITE) else altaV = r.take(LIMITE)
                altaEm = System.currentTimeMillis()
                if (modoMusica == musica && !carregando && consulta.isBlank()) renderizar()
            }
        }.start()
    }

    // ------------------------------------------------------------------ desenho das fileiras

    /** [nicho] é o tema da fileira ("sertanejo", a pesquisa...): quem toca monta a fila com playlists desse tema. */
    private fun cartoes(lista: List<Resultado>, musica: Boolean, nicho: String, grande: Boolean = false): List<View> =
        lista.mapIndexed { i, r -> if (musica) cartaoMusica(r, grande) { aoMusica(lista, i, nicho) } else cartaoVideo(r, grande) { aoVideo(lista, i, nicho) } }

    private fun renderizar() {
        piscar?.cancel(); piscar = null
        area.removeAllViews(); fileiras.clear(); slots.clear()
        val q = consulta.trim()
        erro?.let { mostrarErro(it); atualizarProntidao(); return }
        val musica = modoMusica
        if (carregando) {
            area.addView(cabecalho(if (q.isEmpty()) (if (musica) "Músicas em alta" else "Vídeos em alta") else (if (musica) "Músicas" else "Vídeos"), ""), cabLp())
            area.addView(fileira(if (q.isEmpty()) List(if (musica) 12 else 6) { esqueleto(true, musica = musica) } else List(3) { esqueleto(true, grande = true, musica = musica) }), fileiraLp())      // na pesquisa o esqueleto já tem o tamanho das capas grandes
            piscar = ValueAnimator.ofFloat(.35f, .75f).apply {
                duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { v -> fileiras.forEach { f -> (f.getChildAt(0) as LinearLayout).let { l -> for (i in 0 until l.childCount) l.getChildAt(i).alpha = v.animatedValue as Float } } }
                start()
            }
            return
        }
        // pesquisa com resultado: só o tipo escolhido
        val achados = res
        mostrandoBusca[if (musica) 0 else 1] = q.isNotEmpty() && !achados.isNullOrEmpty()
        if (q.isNotEmpty() && !achados.isNullOrEmpty()) {
            area.addView(cabecalho(if (musica) "Músicas" else "Vídeos", ""), cabLp())
            area.addView(fileira(cartoes(achados, musica, q, grande = true)), fileiraLp())      // na pesquisa há uma fileira só: as capas ocupam o espaço da tela
            return
        }
        // sem pesquisa (ou sem resultado): o em alta do tipo escolhido e, abaixo, uma fileira por gênero
        val alta = altaDoModo().orEmpty()
        area.addView(cabecalho(if (musica) "Músicas em alta" else "Vídeos em alta", if (q.isNotEmpty()) "Nada encontrado para “$q”" else ""), cabLp())
        if (alta.isEmpty()) area.addView(avisoLinha("Não consegui carregar o em alta agora. Toque para tentar de novo.") { if (musica) altaM = null else altaV = null; carregarAlta() })
        else area.addView(fileira(cartoes(alta, musica, if (musica) "músicas mais tocadas brasil" else "clipes mais vistos brasil")), fileiraLp())
        for ((nome, _) in GENEROS) {
            val chave = (if (musica) "m|" else "v|") + nome
            area.addView(cabecalho(nome, ""), cabLp().apply { topMargin = 36.d() })      // só o nome do gênero, com mais espaço entre eles
            val slot = FrameLayout(c)
            area.addView(slot, fileiraLp())
            slots.add(chave to slot)
            preencherSlot(chave)
        }
        area.addView(View(c), LinearLayout.LayoutParams(-1, 28.d()))      // folga no fim da coluna
        area.post { area.post { verificarVisiveis() } }
        atualizarProntidao()
    }

    /** Nada trava a Mídia enquanto carrega: os cartões de espera aparecem e o conteúdo entra conforme chega (sem tela de bloqueio). */
    private fun atualizarProntidao() {
        carregandoTela.visibility = View.GONE
        rolagem.alpha = 1f
    }

    private fun avisoLinha(msg: String, aoToque: () -> Unit): View = texto(c, msg, 20, Tema.TEXTO3).apply {
        setPadding(52.d(), 10.d(), 48.d(), 22.d()); isClickable = true; setOnClickListener { aoToque() }
    }

    /** Põe no espaço do gênero o que ele tem agora: as músicas ou vídeos, o aviso de falha ou o esqueleto de espera. */
    private fun preencherSlot(chave: String) {
        val slot = slots.firstOrNull { it.first == chave }?.second ?: return
        slot.removeAllViews()
        val musica = chave.startsWith("m|")
        val lista = generos[chave]
        when {
            lista != null -> slot.addView(fileira(cartoes(lista, musica, GENEROS.firstOrNull { it.first == chave.substringAfter('|') }?.second ?: chave.substringAfter('|'))))
            chave in falhaGenero -> slot.addView(avisoLinha("Não carregou. Toque para tentar de novo.") { falhaGenero.remove(chave); generosPedidos.remove(chave); verificarVisiveis() })
            else -> slot.addView(fileira(List(if (musica) 12 else 8) { esqueleto(true, musica = musica) }, registrar = false).apply { alpha = .5f })
        }
    }

    /** Pede os gêneros que estão perto da tela (a coluna rola para baixo; os de baixo só carregam quando chegam perto). */
    private fun verificarVisiveis() {
        if (carregando || erro != null || rolagem.height == 0) return      // tela escondida (sem medida): nada perto de aparecer
        val limite = rolagem.scrollY + rolagem.height + 500.d()
        for ((chave, slot) in slots.toList()) {
            if (generos[chave] != null || chave in generosPedidos || chave in falhaGenero || slot.top > limite) continue
            val nome = chave.substringAfter('|')
            val termo = GENEROS.firstOrNull { it.first == nome }?.second ?: continue
            carregarGenero(chave, termo, chave.startsWith("m|"))
        }
    }

    private fun carregarGenero(chave: String, termo: String, musica: Boolean) {
        if (!generosPedidos.add(chave)) return
        val t0 = android.os.SystemClock.uptimeMillis()
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val r = try { YouTube.generoEmAlta(termo, musica) } catch (e: Exception) { null }
            android.util.Log.d("MidiaTempo", "gênero " + chave + ": " + (android.os.SystemClock.uptimeMillis() - t0) + " ms, " + (r?.size ?: -1) + " itens")
            principal.post {
                if (r.isNullOrEmpty()) {
                    // sem aviso nem toque: fica o esqueleto e o app tenta de novo sozinho, com pausa crescente, até carregar
                    val n = (tentativas[chave] ?: 0) + 1
                    tentativas[chave] = n
                    generosPedidos.remove(chave)
                    principal.postDelayed({ if (generos[chave] == null) carregarGenero(chave, termo, musica) }, (if (n > 6) 60_000L else minOf(2_000L * n, 12_000L)))
                } else { tentativas.remove(chave); generos[chave] = r; preencherSlot(chave) }
                atualizarProntidao()
            }
        }.start()
    }

    private fun cabLp() = LinearLayout.LayoutParams(-1, -2).apply { leftMargin = 52.d(); rightMargin = 48.d(); topMargin = 18.d(); bottomMargin = 10.d() }
    private fun fileiraLp() = LinearLayout.LayoutParams(-1, -2)

    private fun cabecalho(titulo: String, conta: String): View = LinearLayout(c).apply {
        gravity = Gravity.BOTTOM
        addView(texto(c, titulo, 23, Tema.TEXTO, true))
        if (conta.isNotEmpty()) addView(texto(c, conta, 19, Color.parseColor("#FFB3BC")), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 14.d(); bottomMargin = 2.d() })
    }

    private fun fileira(cartoes: List<View>, registrar: Boolean = true): HorizontalScrollView {
        val linha = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(48.d(), 0, 30.d(), 0) }
        cartoes.forEach { linha.addView(it) }
        return HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            addView(linha, LayoutParams(-2, -2))
            if (registrar) fileiras.add(this)
        }
    }

    private fun arredondar(v: View, raio: Int) {
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, raio.d().toFloat()) }
        }
    }

    private fun esqueleto(video: Boolean, grande: Boolean = false, musica: Boolean = false): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        // música: quadrado do tamanho do cartão de música; vídeo: 16:9 do tamanho do cartão de vídeo
        val largura = if (musica) (if (grande) 297 else 133) else if (grande) 528 else if (video) LARGURA_VIDEO else LADO_MUSICA
        val capa = View(c).apply { background = fundoArredondado(Color.parseColor("#33FFFFFF"), if (video) 16.d() else 18.d()) }
        addView(capa, LinearLayout.LayoutParams(largura.d(), (if (musica) largura else if (grande) 297 else if (video) LARGURA_VIDEO * 9 / 16 else LADO_MUSICA).d()))
        val l1 = View(c).apply { background = fundoArredondado(Color.parseColor("#33FFFFFF"), 6.d()) }
        val l2 = View(c).apply { background = fundoArredondado(Color.parseColor("#33FFFFFF"), 6.d()) }
        addView(l1, LinearLayout.LayoutParams((largura - 30).d(), 16.d()).apply { topMargin = 14.d() })
        addView(l2, LinearLayout.LayoutParams((largura - 80).d(), 14.d()).apply { topMargin = 8.d() })
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18.d() }
    }

    private fun cartaoVideo(r: Resultado, grande: Boolean = false, aoToque: () -> Unit): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val lw = if (grande) 528 else LARGURA_VIDEO      // pesquisa: capa grande (16:9) em vez da miniatura
        val lh = if (grande) 297 else 133
        isClickable = true
        setOnClickListener { aoToque() }
        efeitoToque()
        val quadro = FrameLayout(c).apply { background = fundoArredondado(Color.parseColor("#22FFFFFF"), 18.d()) }
        val imagem = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        arredondar(quadro, 18)
        quadro.addView(imagem, FrameLayout.LayoutParams(-1, -1))
        Miniaturas.carregar(r.miniaturas, imagem, if (grande) 640 else 240)
        quadro.addView(texto(c, r.duracaoTexto, 15, Color.WHITE, true).apply {
            background = fundoArredondado(Color.parseColor("#B8000000"), 7.d())
            setPadding(8.d(), 3.d(), 8.d(), 3.d())
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = 8.d(); bottomMargin = 8.d() })
        addView(quadro, LinearLayout.LayoutParams(lw.d(), lh.d()))
        addView(texto(c, r.titulo, if (grande) 24 else 20, Color.WHITE, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(lw.d(), -2).apply { topMargin = 9.d() })
        val sub = listOf(r.canal, r.visualizacoesTexto).filter { it.isNotEmpty() }.joinToString(" · ")
        addView(texto(c, sub, 17, Tema.TEXTO3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(lw.d(), -2).apply { topMargin = 3.d() })
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18.d() }
    }

    private fun cartaoMusica(r: Resultado, grande: Boolean = false, aoToque: () -> Unit): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val lw = if (grande) 297 else 133      // a capa da música é quadrada: o cartão tem o tamanho dela, sem faixas dos lados
        val lh = lw
        isClickable = true
        setOnClickListener { aoToque() }
        efeitoToque()
        val quadro = FrameLayout(c).apply { background = fundoArredondado(Color.parseColor("#22FFFFFF"), 18.d()) }
        val imagem = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        arredondar(quadro, 18)
        quadro.addView(imagem, FrameLayout.LayoutParams(-1, -1))
        Miniaturas.carregar(r.miniaturas, imagem, if (grande) 640 else 240)
        addView(quadro, LinearLayout.LayoutParams(lw.d(), lh.d()))
        addView(texto(c, r.titulo, if (grande) 24 else 20, Color.WHITE, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(lw.d(), -2).apply { topMargin = 9.d() })
        addView(texto(c, r.canal.removeSuffix(" - Topic"), 17, Tema.TEXTO3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(lw.d(), -2).apply { topMargin = 3.d() })
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18.d() }
    }

    private fun mostrarErro(msg: String) {
        val caixa = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        caixa.addView(AnelIcone(c, R.drawable.ic_wifi_off, 96, Tema.VERMELHO_SUAVE, Color.parseColor("#24FF4D5E"), 0))
        caixa.addView(texto(c, msg, 34, Tema.TEXTO, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.d() })
        caixa.addView(texto(c, "Confira a internet da caixa e tente de novo.", 22, Tema.TEXTO2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 8.d() })
        val botoes = LinearLayout(c).apply {
            gravity = Gravity.CENTER
            addView(botao(c, "Tentar de novo", Cfg.AZUL) { erro = null; if (consulta.isBlank()) carregarAlta() else buscar() })
            addView(botao(c, "Abrir Wi-Fi", Tema.BOTAO) { aoAbrirWifi?.invoke() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 14.d() })
        }
        caixa.addView(botoes, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 24.d() })
        area.addView(caixa, LinearLayout.LayoutParams(-1, -1))
    }

    // ------------------------------------------------------------------ barra e teclado

    private fun atualizarBarra() {
        escreverBusca()
        dica.visibility = if (consulta.isEmpty()) View.VISIBLE else View.GONE
        botaoLimpar.visibility = if (consulta.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Quanto a voz está alta agora (0 a 1): o microfone vermelho cresce e brilha junto com ela. */
    var nivelMic: (() -> Float)? = null

    private fun pintarMic(cor: Int, escala: Float) {
        microfone.background = fundoArredondado(cor, 24.d())
        microfoneTeclado.background = fundoArredondado(cor, 32.d())
        microfone.scaleX = escala; microfone.scaleY = escala; microfoneTeclado.scaleX = escala; microfoneTeclado.scaleY = escala
    }

    private fun misturar(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    /**
     * Busca por voz, ouvindo: o microfone fica vermelho e acompanha a voz (cresce e brilha mais quando a pessoa fala, respira
     * devagar quando está quieto). Ao terminar de falar, [pesquisandoVoz] troca para verde.
     */
    fun ouvindoVoz(sim: Boolean) {
        pulsoMic?.cancel(); pulsoMic = null
        principal.removeCallbacks(restaurarDica)
        if (sim) {
            // o teclado fica como está: o que a pessoa fala vai aparecendo na barra de pesquisa
            consulta = ""; escreverBusca(""); dica.text = "Ouvindo…"; dica.visibility = View.VISIBLE     // a barra espera o texto ao vivo
            val apagado = misturar(Tema.ERRO, Color.BLACK, 0.35f)
            var suave = 0f
            val t0 = android.os.SystemClock.uptimeMillis()
            pulsoMic = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000; repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    val n = (nivelMic?.invoke() ?: 0f).coerceIn(0f, 1f)
                    suave += (n - suave) * (if (n > suave) 0.5f else 0.15f)      // sobe rápido com a voz e desce devagar
                    val respiro = 0.04f * Math.sin((android.os.SystemClock.uptimeMillis() - t0) / 1000.0 * 2 * Math.PI * 0.9).toFloat()
                    pintarMic(misturar(apagado, Tema.ERRO, (0.35f + 0.65f * suave).coerceIn(0f, 1f)), 1.04f + respiro + 0.45f * suave)
                }
                start()
            }
        } else {
            pintarMic(Color.parseColor("#33FFFFFF"), 1f)
            restaurarDica.run()
        }
    }

    /** Terminou de falar: o microfone fica verde, respirando, e a barra mostra "Pesquisando…" até o resultado chegar. */
    fun pesquisandoVoz() {
        pulsoMic?.cancel(); pulsoMic = null
        principal.removeCallbacks(restaurarDica)
        dica.text = "Pesquisando…"
        if (consulta.isEmpty()) dica.visibility = View.VISIBLE
        pulsoMic = ValueAnimator.ofFloat(1f, 1.12f).apply {
            duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { pintarMic(Tema.OK, it.animatedValue as Float) }
            start()
        }
    }

    /** Busca por voz: as palavras vão aparecendo na barra enquanto a pessoa fala. */
    fun mostrarParcial(texto: String) {
        consulta = texto
        escreverBusca(texto)
        dica.visibility = View.GONE
        botaoLimpar.visibility = View.GONE
    }

    /** Escreve [texto] na barra e pesquisa (usado pela busca por voz). */
    fun pesquisar(texto: String) {
        consulta = texto
        atualizarBarra()
        fecharTeclado()
        buscar()
    }

    /** Mensagem curta na barra (ex.: a busca por voz não ouviu nada); volta ao texto normal em 2 s. */
    fun avisoBusca(msg: String) {
        consulta = ""; escreverBusca("")
        dica.text = msg
        dica.visibility = View.VISIBLE
        principal.removeCallbacks(restaurarDica)
        principal.postDelayed(restaurarDica, 2200)
    }
    private val restaurarDica = Runnable { dica.text = textoDica(); atualizarBarra() }
    private fun textoDica() = if (modoMusica) "Pesquisar músicas" else "Pesquisar vídeos"

    private fun digitar(s: String) { consulta += s; mudouTexto() }
    private fun apagar() { if (consulta.isNotEmpty()) { consulta = consulta.dropLast(1); mudouTexto() } }
    private fun mudouTexto() { atualizarBarra() }      // a busca só roda ao tocar em "Buscar"

    private fun montarTeclado() {
        // sobe de baixo cobrindo de y 218 até o fim (fundo sólido, cantos de cima 30), sem botão de recolher
        teclado.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#080D1E"))
            cornerRadii = floatArrayOf(30.d().toFloat(), 30.d().toFloat(), 30.d().toFloat(), 30.d().toFloat(), 0f, 0f, 0f, 0f)
        }
        teclado.isClickable = true
        teclado.setPadding(0, 0, 0, 0)
        tecladoV3.acao = "Buscar"
        tecladoV3.aoDigitar = { s -> digitar(s) }
        tecladoV3.aoApagar = { apagar() }
        tecladoV3.aoAcao = { fecharTeclado(); buscar() }
        microfoneTeclado.apply {
            background = fundoArredondado(Color.parseColor("#33FFFFFF"), 32.d())
            isClickable = true
            setOnClickListener { aoMicrofone?.invoke() ?: avisoBusca("Busca por voz indisponível") }
            efeitoToque()
            addView(icone(c, R.drawable.ic_mic, 30, Color.WHITE), LayoutParams(30.d(), 30.d(), Gravity.CENTER))
        }
        teclado.addView(microfoneTeclado, LayoutParams(56.d(), 56.d(), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 8.d() })
        teclado.addView(tecladoV3, LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = 12.d() })
    }

    fun abrirTeclado() {
        if (tecladoAberto) return
        tecladoAberto = true
        cursorVisivel = true; escreverBusca(); principal.removeCallbacks(piscarCursor); principal.postDelayed(piscarCursor, 530)
        tecladoV3.acao = "Buscar"      // remonta o teclado: a tecla Buscar pega a cor de destaque do menu de agora
        tecladoV3.capitalizar(consulta.isEmpty() && false)
        teclado.visibility = View.VISIBLE
        teclado.post {
            teclado.translationY = teclado.height.toFloat()
            teclado.animate().translationY(0f).setDuration(Tema.TECLADO_MS).setInterpolator(Tema.CURVA).start()
        }
        barra.background = fundoArredondado(Color.parseColor("#33FFFFFF"), 42.d(), Color.parseColor("#88FFFFFF"))
    }

    fun fecharTeclado() {
        if (!tecladoAberto) return
        tecladoAberto = false
        principal.removeCallbacks(piscarCursor); escreverBusca()
        barra.background = fundoArredondado(Color.parseColor("#26FFFFFF"), 42.d(), Cfg.LINHA)
        teclado.animate().translationY(teclado.height.toFloat()).setDuration(Tema.TECLADO_MS).setInterpolator(Tema.CURVA).withEndAction { if (!tecladoAberto) teclado.visibility = View.GONE }.start()
    }

    private var engolirToque = false

    /** O teclado está aberto (a Activity fecha ao tocar no cabeçalho ou ao puxar a borda para sair). */
    val tecladoVisivel: Boolean get() = tecladoAberto

    /** Com o teclado aberto, tocar fora dele (e fora da barra) só fecha o teclado: o toque não aciona o que está embaixo. */
    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            engolirToque = false
            if (tecladoAberto) {
                val p = IntArray(2)
                teclado.getLocationOnScreen(p)
                val b = IntArray(2)
                barra.getLocationOnScreen(b)
                val fora = e.rawY < p[1] && !(e.rawY >= b[1] && e.rawY <= b[1] + barra.height)
                if (fora) { fecharTeclado(); engolirToque = true }
            }
        }
        if (engolirToque) {
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) engolirToque = false
            return true
        }
        return super.dispatchTouchEvent(e)
    }

    /** O teclado fecha primeiro quando o usuário volta. Devolve true se havia teclado aberto. */
    fun voltar(): Boolean {
        if (tecladoAberto) { fecharTeclado(); return true }
        // mostrando uma pesquisa: voltar leva de novo ao menu da Mídia (em alta e gêneros), não ao menu principal
        if (consulta.isNotBlank()) { consulta = ""; atualizarBarra(); buscar(); return true }
        return false
    }

    /** Tem uma pesquisa na tela (resultados, carregando ou "nada encontrado"): o gesto de voltar volta ao menu da Mídia. */
    val emBusca: Boolean get() = consulta.isNotBlank()

    /**
     * O gesto de voltar (puxar da borda esquerda) não pode começar em cima do teclado nem de uma fileira que já rolou:
     * ali o arrasto para a direita é para rolar ou digitar.
     */
    fun areaProtegida(x: Float, y: Float): Boolean {
        val p = IntArray(2)
        // o teclado não protege a borda: o gesto de voltar começa em x < 36, onde não há teclas
        if (tecladoAberto) return false      // com o teclado aberto, as fileiras ficam escondidas atrás dele
        for (f in fileiras) {
            f.getLocationOnScreen(p)
            if (y >= p[1] && y <= p[1] + f.height && f.scrollX > 0) return true
        }
        return false
    }
}
