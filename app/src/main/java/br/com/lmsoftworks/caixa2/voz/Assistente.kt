package br.com.lmsoftworks.caixa2.voz

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import br.com.lmsoftworks.caixa2.Resultado
import br.com.lmsoftworks.caixa2.YouTube
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** Sons curtos e suaves do assistente, gerados na hora (sem arquivos): ativação, confirmação e erro. */
object TonsVoz {
    private fun tocar(notas: List<Pair<Double, Int>>) {
        Thread {
            try {
                val taxa = 22050
                val amostras = ArrayList<Short>()
                for ((freq, ms) in notas) {
                    val n = taxa * ms / 1000
                    for (i in 0 until n) {
                        val env = minOf(1.0, i / (taxa * 0.01), (n - i) / (taxa * 0.04))
                        amostras.add((sin(2 * PI * freq * i / taxa) * 9000 * env).toInt().toShort())
                    }
                }
                val dados = amostras.toShortArray()
                val t = AudioTrack(AudioManager.STREAM_MUSIC, taxa, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, dados.size * 2, AudioTrack.MODE_STATIC)
                t.write(dados, 0, dados.size)
                t.play()
                Thread.sleep(notas.sumOf { it.second }.toLong() + 120)
                t.release()
            } catch (_: Throwable) {}
        }.start()
    }

    private var pensando: android.media.MediaPlayer? = null

    /** O som de "pensando" do projeto anterior (som_pesquisando.mp3): fica repetindo, baixinho, até a caixa terminar de pensar. */
    fun comecarPensando(ctx: Context) {
        if (pensando != null) return
        try {
            val mp = android.media.MediaPlayer()
            val afd = ctx.resources.openRawResourceFd(br.com.lmsoftworks.caixa2.R.raw.som_pesquisando)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            @Suppress("DEPRECATION")
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.isLooping = true
            mp.setVolume(0.12f, 0.12f)
            mp.setOnPreparedListener { if (pensando === it) it.start() }
            pensando = mp
            mp.prepareAsync()
        } catch (e: Throwable) { Log.d("Assistente", "som de pensando falhou: $e") }
    }

    fun pararPensando() {
        val mp = pensando ?: return
        pensando = null
        try { mp.stop() } catch (_: Throwable) {}
        mp.release()
    }

    fun ativacao() = tocar(listOf(660.0 to 90, 880.0 to 120))
    fun confirmacao() = tocar(listOf(784.0 to 80, 988.0 to 110))
    fun erro() = tocar(listOf(520.0 to 100, 392.0 to 140))
}

/** Tudo que o assistente precisa fazer no app (a tela principal implementa). Sempre chamado na thread principal. */
interface AcoesAssistente {
    /** Executa o pedido e devolve a frase que a caixa deve falar (null = só executa, em silêncio). */
    fun executar(comando: ComandoVoz, textoOuvido: String): String?
    /** Toca o resultado da busca do pedido por voz (música: só o áudio; vídeo: tela cheia). */
    fun tocar(lista: List<Resultado>, soAudio: Boolean)
    /** Tem um vídeo aberto agora? (um pedido sem "música" ou "vídeo" vale para o que já está na tela) */
    fun videoAberto(): Boolean
    /** Abaixa (true) ou volta (false) o volume do que estiver tocando enquanto o assistente escuta e fala. */
    fun abafar(sim: Boolean)
    /** Alguém chamou a caixa: tira a tela do modo ocioso. */
    fun acordarTela()
}

/**
 * O assistente de voz ("Alexa"):
 *  1) CHAMADA, offline (Vosk): fica escutando só a palavra de ativação, com pouca CPU e sem internet;
 *  2) PEDIDO: grava a fala até a pessoa parar e o Gemini (Google) transforma o áudio em texto;
 *  3) EXECUÇÃO: o interpretador entende o texto (tocar, volume, luzes...) ou, se for uma pergunta, pesquisa na internet;
 *  4) RESPOSTA: a faixa mostra o que foi dito e a caixa fala a resposta em português do Brasil.
 * O modelo do Vosk fica em <externo>/Android/data/<pacote>/files/model (e as chaves em gemini.txt / tavily.txt, na mesma pasta).
 */
class Assistente(private val ctx: Context, private val faixa: FaixaAssistente, private val acoes: AcoesAssistente) {

    companion object {
        private const val TAG = "Assistente"
        private const val TAXA = 16000f
        @Volatile private var modelo: Model? = null

        // "Alexa" como o brasileiro fala; "caixa" só vale com ei/oi/ok/olá na frente (a palavra solta aparece em qualquer conversa).
        private val VARIANTES_ALEXA = listOf(
            "alexia", "alexi", "a lexia", "alexa", "alexsa", "alequisa", "alequiza", "alequissa", "ale quisa", "ale quiza",
            "quiza", "quisa", "quissa", "a quiza", "a quisa", "ale kisa", "ale quissa", "a lequisa", "a lequiza", "alecha", "alexi a", "alexis a", "alé quisa", "alé quiza"
        )
        private val VARIANTES_CAIXA = listOf("caixa", "caixa de som", "ele eme")
        private val PREFIXOS = listOf("ei ", "oi ", "ok ", "olá ")
        private val FRASES_CHAMADA = VARIANTES_ALEXA + PREFIXOS.flatMap { p -> (VARIANTES_ALEXA + VARIANTES_CAIXA).map { p + it } }
        private fun alt(l: List<String>) = l.joinToString("|") { Regex.escape(it) }
        private val REGEX_CHAMADA_FALANDO = Regex("""^(ei|oi|ok|olá) (${alt(VARIANTES_ALEXA.filter { it.length >= 6 } + VARIANTES_CAIXA)})$""")
        // Fragmentos curtos ("quiza", "quisa"...) ficam só na gramática como "isca"; sozinhos NÃO chamam (disparavam à toa).
        private val FRAGMENTOS = setOf("quiza", "quisa", "quissa", "a quiza", "a quisa", "a lequisa", "a lequiza")
        private val NOMES_FORTES = VARIANTES_ALEXA.filter { it !in FRAGMENTOS }
        private val REGEX_CHAMADA = Regex("""^(?:(?:ei|oi|ok|olá) (?:${alt(NOMES_FORTES + VARIANTES_CAIXA)})|(?:${alt(NOMES_FORTES)}))$""")
        private const val CONFIANCA_MINIMA = 0.55
        private const val ESPERA_ENTRE_CHAMADAS_MS = 2500L
        private val SO_NOME = setOf("alexia", "alexi", "alexa", "a lexia", "alexsa", "alexi a", "alexis a")
        private val ISCAS = listOf(
            "alice", "caixa", "a caixa", "de som", "caixa de som", "som", "ele", "eme", "ei", "oi", "ok", "olá",
            "e", "a", "o", "que", "de", "um", "uma", "não", "sim", "tá", "né", "é", "ah", "eh", "então", "ale", "leite"
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var escuta: EscutaAssistente? = null
    private var ativo = false
    private var carregando = false
    @Volatile var estado = EstadoVoz.DESLIGADA; private set
    /** Cada pedido novo sobe o número: respostas de pedidos antigos (internet lenta) são ignoradas. */
    private var geracao = 0
    /** Se não for null, o pedido atual é uma busca por voz: o texto vai para cá e nada é executado. */
    private var destinoBusca: ((String?) -> Unit)? = null
    private var aoOuvirBusca: (() -> Unit)? = null
    private var aoParcialBusca: ((String) -> Unit)? = null

    val nivelMicrofone: Float get() = escuta?.nivel ?: 0f

    private fun pastaModelo() = File(ctx.getExternalFilesDir(null), "model")
    private fun modeloInstalado() = File(pastaModelo(), "am/final.mdl").isFile || File(pastaModelo(), "final.mdl").isFile

    fun iniciar() {
        if (ativo) return
        ativo = true
        YouTube.aquecer()
        faixa.preparar()
        Fala.preparar(ctx)
        if (!modeloInstalado()) { estado = EstadoVoz.SEM_MODELO; Log.w(TAG, "modelo de voz ausente em ${pastaModelo()}"); return }
        if (modelo != null) comecarChamada() else carregarModelo()
    }

    fun parar() {
        ativo = false
        geracao++
        TonsVoz.pararPensando()
        escuta?.parar(); escuta = null
        Fala.parar()
        acoes.abafar(false)
        faixa.esconder()
        estado = EstadoVoz.DESLIGADA
    }

    private fun carregarModelo() {
        if (carregando) return
        carregando = true
        estado = EstadoVoz.CARREGANDO
        Thread {
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val m = Model(pastaModelo().absolutePath)
                main.post { modelo = m; carregando = false; if (ativo) comecarChamada() }
            } catch (e: Throwable) {
                Log.w(TAG, "nao consegui carregar o modelo de voz", e)
                main.post { carregando = false; estado = EstadoVoz.ERRO }
            }
        }.start()
    }

    private fun comecarChamada() {
        val m = modelo ?: return
        escuta?.parar()
        try {
            val gramatica = (FRASES_CHAMADA + ISCAS).joinToString(prefix = "[", postfix = ", \"[unk]\"]") { "\"$it\"" }
            val rec = Recognizer(m, TAXA, gramatica)
            rec.setWords(true)       // devolve a confiança de cada palavra
            // gramática só com os comandos fixos: reconhecidos offline, na hora (o que não bater vai para o Gemini)
            val gramaticaCmd = (FrasesRapidas.todas()).joinToString(prefix = "[", postfix = ", \"[unk]\"]") { "\"$it\"" }
            val cmd = try { Recognizer(m, TAXA, gramaticaCmd).also { it.setWords(true) } } catch (e: Throwable) { Log.w(TAG, "sem comandos rapidos", e); null }
            val livre = try { Recognizer(m, TAXA) } catch (e: Throwable) { Log.w(TAG, "sem texto ao vivo", e); null }
            val e = EscutaAssistente(rec, cmd, livre, { parcial -> main.post { aoPalavras(parcial) } }) { campo, json -> verificarChamada(json, campo) }
            if (!e.iniciar()) { rec.close(); cmd?.close(); livre?.close(); estado = EstadoVoz.ERRO; Log.w(TAG, "microfone ocupado ou sem permissao"); return }
            escuta = e
            estado = EstadoVoz.ESPERANDO_CHAMADA
            Log.d(TAG, "esperando a chamada (Alexa)")
        } catch (e: Throwable) {
            Log.w(TAG, "falha ao iniciar a escuta", e)
            estado = EstadoVoz.ERRO
        }
    }

    private fun lerCampo(json: String?, campo: String): String =
        try { JSONObject(json ?: "{}").optString(campo, "") } catch (_: Exception) { "" }

    /** Menor confiança entre as palavras reconhecidas (0..1); 1 se o Vosk não informou. */
    private fun confianca(json: String?): Double = try {
        val arr = JSONObject(json ?: "{}").optJSONArray("result")
        var menor = 1.0
        if (arr != null) for (i in 0 until arr.length()) menor = minOf(menor, arr.getJSONObject(i).optDouble("conf", 1.0))
        menor
    } catch (_: Exception) { 1.0 }

    private var ultimaChamada = 0L
    private var ultimoParcial = ""
    private var parciaisIguais = 0

    private fun verificarChamada(json: String?, campo: String) {
        val texto = lerCampo(json, campo)
        if (texto.isBlank() || texto == "[unk]") { parciaisIguais = 0; return }
        // Frase terminada: vale com confiança. Parcial (mais rápido, ~0,3 s): só se a MESMA palavra estável aparecer 3 vezes seguidas.
        if (campo == "partial") {
            if (texto == ultimoParcial) parciaisIguais++ else { ultimoParcial = texto; parciaisIguais = 1 }
            if (parciaisIguais < 3 || !REGEX_CHAMADA.matches(texto)) return
        }
        val conf = if (campo == "partial") 1.0 else confianca(json)
        parciaisIguais = 0; ultimoParcial = ""
        Log.d(TAG, "chamada ($campo): \"$texto\" confianca=${"%.2f".format(conf)}")
        if (!REGEX_CHAMADA.matches(texto) || conf < CONFIANCA_MINIMA) return
        val agora = System.currentTimeMillis()
        if (agora - ultimaChamada < ESPERA_ENTRE_CHAMADAS_MS) return
        ultimaChamada = agora
        // Enquanto a caixa FALA o microfone ouve a voz dela: só vale "ei/oi/ok + nome" completo (ou o nome sozinho, terminado).
        val soNome = texto in SO_NOME
        if (Fala.falando && !soNome && !REGEX_CHAMADA_FALANDO.matches(texto)) return
        // vale também no meio de uma resposta ou de uma pesquisa: a caixa para o que está fazendo e escuta de novo
        main.post { if (ativo && (estado == EstadoVoz.ESPERANDO_CHAMADA || estado == EstadoVoz.RESPONDENDO || estado == EstadoVoz.PENSANDO)) acordou() }
    }

    // ---------------------------------------------------------------- pedido

    /** O que a pessoa está falando AGORA (do reconhecedor offline): aparece na tela letra a letra, antes do texto final do Gemini. */
    private fun aoPalavras(texto: String) {
        if (estado != EstadoVoz.OUVINDO_COMANDO) return
        val busca = aoParcialBusca
        if (destinoBusca != null) busca?.invoke(texto) else faixa.digitando(texto)
    }

    /** Chamada pelo botão do microfone da busca: ouve um pedido e entrega o texto a [aoTexto] em vez de executar. */
    fun ouvirParaBusca(aoComecar: () -> Unit, aoParcial: (String) -> Unit, aoTexto: (String?) -> Unit) {
        if (!ativo || escuta == null) { aoTexto(null); return }
        destinoBusca = aoTexto
        aoParcialBusca = aoParcial
        aoOuvirBusca = aoComecar
        acordou()
    }

    /** O usuário tocou na tela: para de falar, de pesquisar e de ouvir, e fecha o assistente. */
    fun cancelar() {
        geracao++
        TonsVoz.pararPensando()
        Fala.parar()
        escuta?.cancelarPedido()
        val busca = destinoBusca; destinoBusca = null
        acoes.abafar(false)
        faixa.esconder()
        estado = if (escuta != null) EstadoVoz.ESPERANDO_CHAMADA else estado
        busca?.invoke(null)
    }

    private fun acordou() {
        val e = escuta ?: return
        e.cancelarPedido()
        TonsVoz.pararPensando()
        entendimento = null
        val minha = ++geracao
        Fala.parar()
        acoes.acordarTela()
        acoes.abafar(true)
        TonsVoz.ativacao()
        estado = EstadoVoz.OUVINDO_COMANDO
        if (destinoBusca == null) faixa.ouvindo() else aoOuvirBusca?.invoke()
        e.gravarPedido { wav, jsonCmd -> main.post { if (minha == geracao) processarPedido(wav, jsonCmd, minha) } }
    }

    /** Comando fixo reconhecido offline (com confiança), ou null se o pedido tem que ir para o Gemini. */
    private fun comandoRapido(json: String?): Pair<String, ComandoVoz>? {
        if (json == null) return null
        val texto = lerCampo(json, "text").trim()
        if (texto.isBlank() || texto.contains("[unk]")) return null
        if (confianca(json) < 0.6) return null
        val c = InterpretadorVoz.interpretar(texto)
        if (c is ComandoVoz.Pergunta || c is ComandoVoz.Desconhecido || c is ComandoVoz.Tocar || c is ComandoVoz.Pesquisar) return null
        return texto to c
    }

    private fun processarPedido(wav: ByteArray?, jsonCmd: String?, minha: Int) {
        val busca = destinoBusca
        if (busca == null && wav != null) {
            val r = comandoRapido(jsonCmd)
            if (r != null) { Log.d(TAG, "comando rapido: \"${r.first}\" -> ${r.second}"); executarRapido(r.first, r.second); return }
        }
        if (wav == null) {
            if (busca != null) { destinoBusca = null; busca(null) } else resposta("Não ouvi nada.", "", erro = true)
            return
        }
        estado = EstadoVoz.PENSANDO
        TonsVoz.comecarPensando(ctx)
        if (busca == null) faixa.pensando("")
        Thread {
            // busca por voz só precisa do texto; o pedido normal já vem interpretado (intenção + qual música/vídeo)
            val e = try { Respostas.entenderPedido(ctx, wav) } catch (ex: Throwable) { Log.w(TAG, "entendimento falhou", ex); null }
            main.post { if (minha == geracao) { entendimento = e; terminouTranscricao(e?.texto, minha) } }
        }.start()
    }

    private var entendimento: Respostas.Entendimento? = null
    /** "desligar a caixa" espera um "sim" antes de agir. */
    private var pendente: ComandoVoz.DesligarCaixa? = null

    /** Comandos que mostram o resultado na própria tela (volume, luz, tocador...): só um toquinho e o assistente some, sem falar. */
    private fun silencioso(c: ComandoVoz) = c is ComandoVoz.Volume || c is ComandoVoz.Luz || c is ComandoVoz.Pausar || c is ComandoVoz.Continuar ||
        c is ComandoVoz.Parar || c is ComandoVoz.Proxima || c is ComandoVoz.Anterior || c is ComandoVoz.Recomecar || c is ComandoVoz.PularTempo ||
        c is ComandoVoz.Ir || c is ComandoVoz.Voltar || c is ComandoVoz.Embaralhar || c is ComandoVoz.Soneca || c is ComandoVoz.CancelarTimer

    private fun executarRapido(texto: String, c: ComandoVoz) {
        TonsVoz.pararPensando()
        pendente?.let { p ->
            pendente = null
            if (c is ComandoVoz.Sim) { acoes.executar(p, texto); fechar(); return }
            if (c is ComandoVoz.Nao || c is ComandoVoz.Cancelar) { resposta("Certo, cancelado.", texto, falar = "Certo, cancelado."); return }
        }
        when (c) {
            is ComandoVoz.Cancelar, is ComandoVoz.Nao, is ComandoVoz.Sim -> { fechar(); return }
            is ComandoVoz.DesligarCaixa -> {
                pendente = c
                val frase = if (c.reiniciar) "Quer reiniciar a caixa? Diga sim ou não." else "Quer desligar a caixa? Diga sim ou não."
                estado = EstadoVoz.RESPONDENDO
                faixa.respondendo(frase)
                Fala.falar(ctx, frase) { if (ativo && pendente != null && escuta != null) acordou() }
                return
            }
            else -> {}
        }
        val frase = acoes.executar(c, texto)
        if (silencioso(c) && (frase == null || c is ComandoVoz.Volume || c is ComandoVoz.Luz)) { fechar(); return }
        resposta(frase ?: "Pronto", texto, falar = frase)
    }

    /** Termina na hora: toquinho de confirmação, a tela do assistente some e o volume da música volta. */
    private fun fechar() {
        TonsVoz.confirmacao()
        faixa.esconder()
        acoes.abafar(false)
        if (ativo && escuta != null) estado = EstadoVoz.ESPERANDO_CHAMADA
    }

    private fun terminouTranscricao(texto: String?, minha: Int) {
        Log.d(TAG, "transcricao: ${texto?.let { "\"$it\"" } ?: "FALHOU (sem resposta do Gemini)"}")
        val busca = destinoBusca
        if (busca != null) {
            destinoBusca = null
            TonsVoz.pararPensando()
            estado = EstadoVoz.ESPERANDO_CHAMADA
            acoes.abafar(false)
            if (texto.isNullOrBlank()) TonsVoz.erro() else TonsVoz.confirmacao()
            busca(texto?.takeIf { it.isNotBlank() })
            return
        }
        when {
            texto == null -> resposta("Não consegui entender o pedido agora. Tente de novo.", "", erro = true)
            texto.isBlank() -> resposta("Não consegui entender.", "", erro = true)
            else -> executarTexto(texto, minha)
        }
    }

    private fun executarTexto(texto: String, minha: Int) {
        val comando = InterpretadorVoz.interpretar(texto)
        if (pendente != null || comando is ComandoVoz.DesligarCaixa || comando is ComandoVoz.Sim || comando is ComandoVoz.Nao || comando is ComandoVoz.Cancelar) {
            executarRapido(texto, comando); return
        }
        Log.d(TAG, "pedido: \"$texto\" -> $comando")
        when (comando) {
            is ComandoVoz.Desconhecido -> resposta("Não consegui entender.", texto, erro = true)
            // sem verbo de tocar: o Gemini decide se é um pedido de música/vídeo (pelo título, artista...) ou uma pergunta
            is ComandoVoz.Pergunta -> resolverMidia(texto, comando.texto, null, minha)
            // "tocar X": o Gemini já descobriu qual música é (corrige artista errado, nome mal ouvido)
            is ComandoVoz.Tocar -> resolverMidia(texto, comando.consulta, comando, minha)
            else -> {
                val frase = acoes.executar(comando, texto)
                resposta(frase ?: "Pronto", texto, falar = frase)
            }
        }
    }

    /**
     * Pedido de mídia (ou que pode ser): pergunta ao Gemini qual música/vídeo é, busca no YouTube e toca o primeiro resultado.
     * Se o Gemini achar que é uma pergunta (e o interpretador não tinha um verbo de tocar), responde pesquisando.
     */
    private fun resolverMidia(dito: String, consulta: String, tocar: ComandoVoz.Tocar?, minha: Int) {
        estado = EstadoVoz.PENSANDO
        faixa.pensando(dito)
        val jaInterpretado = entendimento?.pedido
        Thread {
            val pedido = jaInterpretado ?: try { Respostas.interpretarMidia(ctx, dito) } catch (e: Throwable) { null }
            Log.d(TAG, "interpretacao: ${pedido?.intencao} / ${pedido?.busca}")
            if (minha != geracao) return@Thread
            val ehPergunta = tocar == null && (pedido == null || pedido.intencao == "pergunta" || pedido.intencao == "comando")
            if (ehPergunta) { main.post { if (minha == geracao) responderPergunta(consulta, dito, minha) }; return@Thread }
            val soAudio = when {
                tocar != null && !tocar.modoIndefinido -> tocar.soAudio
                pedido != null -> pedido.intencao == "musica"
                else -> !acoes.videoAberto()
            }
            val busca = pedido?.busca?.takeIf { it.isNotBlank() } ?: consulta
            val lista = try { YouTube.buscar(busca, soAudio) } catch (e: Exception) { Log.w(TAG, "busca falhou", e); null }
            main.post {
                if (minha != geracao) return@post
                if (lista == null) { resposta("Não consegui buscar agora.", dito, erro = true); return@post }
                if (lista.isEmpty()) { resposta("Não encontrei $busca.", dito, erro = true); return@post }
                // entendeu: tira a tela do assistente na hora e abre a música/vídeo; a caixa só confirma em voz alta
                TonsVoz.pararPensando()
                faixa.esconder()
                acoes.tocar(lista, soAudio)
                val gerundio = tocar?.gerundio ?: if (soAudio) "Tocando" else "Reproduzindo"
                estado = EstadoVoz.RESPONDENDO
                Fala.falar(ctx, "$gerundio ${TextosVoz.tituloFalado(lista[0].titulo)}") { fim(0) }
            }
        }.start()
    }

    private fun responderPergunta(pergunta: String, dito: String, minha: Int) {
        estado = EstadoVoz.PENSANDO
        faixa.pensando(dito)
        var entregue = false
        fun entregar(r: String?) {
            if (entregue || minha != geracao) return
            entregue = true
            resposta(r ?: "Não encontrei nada sobre isso.", dito, falar = r ?: "Não encontrei nada sobre isso.")
        }
        main.postDelayed({ entregar("Não consegui pesquisar agora.") }, 25_000)
        Thread {
            val r = try { Respostas.responder(ctx, pergunta) } catch (e: Throwable) { Log.w(TAG, "erro ao pesquisar", e); null }
            main.post { entregar(r) }
        }.start()
    }

    /** Mostra a resposta na faixa e (se [falar] não for null) fala em voz alta; ao terminar, volta o volume. */
    private fun resposta(texto: String, dito: String, erro: Boolean = false, falar: String? = null) {
        TonsVoz.pararPensando()
        estado = EstadoVoz.RESPONDENDO
        faixa.respondendo(texto, dito)
        if (erro) TonsVoz.erro() else TonsVoz.confirmacao()
        val aFalar = falar ?: if (erro) texto else null
        if (aFalar == null) { fim(2600); return }
        val inicio = System.currentTimeMillis()
        // sem motor de voz em português a fala termina na hora: a faixa fica um tempo na tela para dar para ler
        Fala.falar(ctx, aFalar) { fim(maxOf(1200L, 2800L - (System.currentTimeMillis() - inicio))) }
    }

    private fun fim(esperaMs: Long) {
        acoes.abafar(false)
        faixa.fecharEm(esperaMs)
        if (ativo && escuta != null) estado = EstadoVoz.ESPERANDO_CHAMADA
    }
}
