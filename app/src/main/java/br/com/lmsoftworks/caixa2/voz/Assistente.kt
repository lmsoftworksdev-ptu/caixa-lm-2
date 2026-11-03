package br.com.lmsoftworks.caixa2.voz

import br.com.lmsoftworks.caixa2.Ajustes
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
        if (!Ajustes.sonsResposta) return
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
                val t = AudioTrack(Fala.CANAL, taxa, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, dados.size * 2, AudioTrack.MODE_STATIC)
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
        if (pensando != null || !Ajustes.sonsResposta) return
        try {
            val mp = android.media.MediaPlayer()
            val afd = ctx.resources.openRawResourceFd(br.com.lmsoftworks.caixa2.R.raw.som_pesquisando)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            @Suppress("DEPRECATION")
            mp.setAudioStreamType(Fala.CANAL)
            mp.isLooping = true
            mp.setVolume(0.22f, 0.22f)
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
    /** Fim da escuta (a pessoa parou de falar): o inverso da ativação, descendo. */
    fun fimEscuta() = tocar(listOf(880.0 to 90, 660.0 to 120))
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
    fun abafar(sim: Boolean): Long
    /** Alguém chamou a caixa: tira a tela do modo ocioso. */
    fun acordarTela()
    /** O que a caixa fala no fim das rotinas: lembretes de hoje (só de manhã) e o próximo alarme. Vazio se não há nada a dizer. */
    fun resumoRotina(manha: Boolean): String
    /** Guarda o lembrete e devolve a frase de confirmação ("Pronto, vou te lembrar de ligar para o João hoje às 15:00"). */
    fun agendarLembrete(texto: String, quandoMs: Long): String
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

        // O nome da caixa é "Alexa", como o brasileiro fala. O modelo não conhece "alexa" escrito assim: o que ele ouve é "alexia" ou "alexi";
        // as outras formas ficam na lista para pegar o que ele escrever diferente. Só o nome chama: "ok caixa", "LM" e parecidos não.
        private val VARIANTES_ALEXA = listOf(
            "alexia", "alexi", "a lexia", "alexa", "alexsa", "alexis", "alequisa", "alequiza", "alequissa", "ale quisa", "ale quiza",
            "quiza", "quisa", "quissa", "a quiza", "a quisa", "ale kisa", "ale quissa", "a lequisa", "a lequiza", "alecha", "alexi a", "alexis a", "alé quisa", "alé quiza"
        )
        private val PREFIXOS = listOf("ei ", "oi ", "ok ", "olá ")
        private val FRASES_CHAMADA = VARIANTES_ALEXA + PREFIXOS.flatMap { p -> VARIANTES_ALEXA.map { p + it } }
        private fun alt(l: List<String>) = l.joinToString("|") { Regex.escape(it) }
        private val REGEX_CHAMADA_FALANDO = Regex("""^(ei|oi|ok|olá) (${alt(VARIANTES_ALEXA.filter { it.length >= 6 })})$""")
        // Fragmentos curtos ("quiza", "quisa"...) ficam só na gramática como "isca"; sozinhos NÃO chamam (disparavam à toa).
        private val FRAGMENTOS = setOf("quiza", "quisa", "quissa", "a quiza", "a quisa", "a lequisa", "a lequiza")
        private val NOMES_FORTES = VARIANTES_ALEXA.filter { it !in FRAGMENTOS }
        private val REGEX_CHAMADA = Regex("""^(?:(?:ei|oi|ok|olá) (?:${alt(NOMES_FORTES)})|(?:${alt(NOMES_FORTES)}))$""")
        // Sensível: vale com confiança baixa e já na PRIMEIRA vez que a palavra aparece no texto parcial (antes eram 2 vezes seguidas).
        private const val CONFIANCA_MINIMA = 0.3
        private const val PARCIAIS_PARA_VALER = 1
        private val REGEX_PLAYLIST = Regex("""\b(playlist|mais tocad[ao]s?|as melhores|top \d+|sucessos)\b""")
        private fun semAcento(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        private const val ESPERA_ENTRE_CHAMADAS_MS = 1800L
        private val SO_NOME = setOf("alexia", "alexi", "alexa", "a lexia", "alexsa", "alexis", "alexi a", "alexis a")
        // Palavras parecidas que o reconhecimento pode confundir com o nome: ficam na gramática para absorvê-las (não chamam).
        private val ISCAS = listOf(
            "alice", "alexandre", "alessandra", "aline", "alô", "elias", "ela", "caixa", "a caixa", "de som", "caixa de som", "som", "ele", "eme",
            "ei", "oi", "ok", "olá", "e", "a", "o", "que", "de", "um", "uma", "não", "sim", "tá", "né", "é", "ah", "eh", "então", "ale", "leite"
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
    private var aoPesquisarBusca: (() -> Unit)? = null
    private var aoParcialBusca: ((String) -> Unit)? = null
    private var ultimoLivre = ""      // último texto ao vivo (offline) da busca por voz

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
        MIC_COMUNICACAO = try { File(ctx.getExternalFilesDir(null), "mic_comunicacao").exists() } catch (e: Throwable) { false }
        escuta?.parar()
        try {
            val gramatica = (FRASES_CHAMADA + ISCAS).joinToString(prefix = "[", postfix = ", \"[unk]\"]") { "\"$it\"" }
            val rec = Recognizer(m, TAXA, gramatica)
            rec.setWords(true)       // devolve a confiança de cada palavra
            // gramática só com os comandos fixos: reconhecidos offline, na hora (o que não bater vai para o Gemini)
            val gramaticaCmd = (FrasesRapidas.todas()).joinToString(prefix = "[", postfix = ", \"[unk]\"]") { "\"$it\"" }
            val cmd = try { Recognizer(m, TAXA, gramaticaCmd).also { it.setWords(true) } } catch (e: Throwable) { Log.w(TAG, "sem comandos rapidos", e); null }
            // O texto ao vivo (reconhecedor sem gramática) não roda em tempo real neste aparelho e acertava pouco: desligado.
            val livre: Recognizer? = try { Recognizer(m, TAXA) } catch (e: Throwable) { Log.w(TAG, "sem texto ao vivo", e); null }
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
            if (parciaisIguais < PARCIAIS_PARA_VALER || !REGEX_CHAMADA.matches(texto)) return
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
        if (destinoBusca != null) { if (texto.isNotBlank()) ultimoLivre = texto; busca?.invoke(texto) } else faixa.digitando(texto)
    }

    /** Chamada pelo botão do microfone da busca: ouve um pedido e entrega o texto a [aoTexto] em vez de executar. */
    fun ouvirParaBusca(aoComecar: () -> Unit, aoParcial: (String) -> Unit, aoTexto: (String?) -> Unit, aoPesquisar: () -> Unit = {}) {
        if (!ativo || escuta == null) { aoTexto(null); return }
        destinoBusca = aoTexto
        ultimoLivre = ""
        escuta?.textoAoVivo = true          // o que a pessoa fala vai aparecendo na barra de pesquisa
        aoParcialBusca = aoParcial
        aoOuvirBusca = aoComecar
        aoPesquisarBusca = aoPesquisar
        acordou()
    }

    /** O usuário tocou na tela: para de falar, de pesquisar e de ouvir, e fecha o assistente. */
    fun cancelar() {
        geracao++
        TonsVoz.pararPensando()
        Fala.parar()
        escuta?.cancelarPedido()
        val busca = destinoBusca; destinoBusca = null
        escuta?.textoAoVivo = false
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
        // o toquinho espera a música do celular parar (fade-out), para sair no volume da voz
        val atraso = acoes.abafar(true)
        if (atraso > 0) main.postDelayed({ if (minha == geracao) TonsVoz.ativacao() }, atraso) else TonsVoz.ativacao()
        estado = EstadoVoz.OUVINDO_COMANDO
        if (destinoBusca == null) { e.textoAoVivo = false; faixa.ouvindo() } else aoOuvirBusca?.invoke()
        resolvido = false
        e.gravarPedido(
            { wav -> main.post { if (minha == geracao) processarPedido(wav, minha) } },
            { json -> main.post { if (minha == geracao) comandoRapidoPronto(json) } }
        )
    }

    /** Comando fixo reconhecido offline (com confiança), ou null se o pedido tem que ir para o Gemini. */
    private fun comandoRapido(json: String?): Pair<String, ComandoVoz>? {
        if (json == null) return null
        val texto = lerCampo(json, "text").trim()
        if (texto.isBlank()) return null
        if (texto.contains("[unk]") || confianca(json) < 0.6) { Log.d(TAG, "comando rapido recusado: \"$texto\" confianca=${"%.2f".format(confianca(json))}"); return null }
        val c = InterpretadorVoz.interpretar(texto)
        if (c is ComandoVoz.Pergunta || c is ComandoVoz.Desconhecido || c is ComandoVoz.Tocar || c is ComandoVoz.Pesquisar) return null
        return texto to c
    }

    /** Já foi tratado (pelo comando rápido ou pelo Gemini): a outra resposta, se chegar depois, é ignorada. */
    private var resolvido = false

    /** O reconhecimento offline terminou: se for um comando fixo e o Gemini ainda não respondeu, executa agora e descarta o Gemini. */
    private fun comandoRapidoPronto(json: String?) {
        if (resolvido || destinoBusca != null) return
        val r = comandoRapido(json) ?: return
        resolvido = true
        geracao++            // a resposta do Gemini, se vier, fica obsoleta
        Log.d(TAG, "comando rapido: \"${r.first}\" -> ${r.second}")
        executarRapido(r.first, r.second)
    }

    private fun processarPedido(wav: ByteArray?, minha: Int) {
        val busca = destinoBusca
        if (wav == null) {
            if (busca != null) { destinoBusca = null; busca(null) } else resposta("Não ouvi nada.", "", erro = true)
            return
        }
        estado = EstadoVoz.PENSANDO
        if (busca != null) {
            // busca por voz: primeiro o sinal de que parou de ouvir, só depois o som de "pesquisando"
            aoPesquisarBusca?.invoke()
            TonsVoz.fimEscuta()
            main.postDelayed({ if (minha == geracao && destinoBusca != null) TonsVoz.comecarPensando(ctx) }, 400)
        } else TonsVoz.comecarPensando(ctx)
        if (busca == null) faixa.pensando("")
        Thread {
            // busca por voz só precisa do texto; o pedido normal já vem interpretado (intenção + qual música/vídeo)
            val e = try { Respostas.entenderPedido(ctx, wav) } catch (ex: Throwable) { Log.w(TAG, "entendimento falhou", ex); null }
            main.post { if (minha == geracao) { entendimento = e; terminouTranscricao(e?.texto, minha) } else Log.d(TAG, "resposta do Gemini ignorada: a caixa foi chamada de novo (texto=${e?.texto})") }
        }.start()
    }

    private var entendimento: Respostas.Entendimento? = null
    /** "desligar a caixa" espera um "sim" antes de agir. */
    private var pendente: ComandoVoz.DesligarCaixa? = null

    /** Comandos que mostram o resultado na própria tela (volume, luz, tocador...): só um toquinho e o assistente some, sem falar. */
    private fun silencioso(c: ComandoVoz) = c is ComandoVoz.Volume || c is ComandoVoz.Luz || c is ComandoVoz.Pausar || c is ComandoVoz.Continuar ||
        c is ComandoVoz.Parar || c is ComandoVoz.Proxima || c is ComandoVoz.Anterior || c is ComandoVoz.Recomecar || c is ComandoVoz.PularTempo ||
        c is ComandoVoz.Ir || c is ComandoVoz.Voltar || c is ComandoVoz.Embaralhar || c is ComandoVoz.Soneca

    /**
     * Comando de controle (volume, luz, pausar, próxima...): faz a ação e pronto, sem a caixa repetir o que foi feito ("Volume 1").
     * Só fala quando há o que dizer de verdade (um erro, uma resposta, "só consigo repetir músicas que eu mesma toco").
     */
    private fun deveFalar(c: ComandoVoz, frase: String?): Boolean = when {
        frase == null -> !silencioso(c)
        c is ComandoVoz.Volume || c is ComandoVoz.Luz || c is ComandoVoz.Embaralhar || c is ComandoVoz.Soneca -> false
        c is ComandoVoz.Repetir -> !(frase.startsWith("Repetindo") || frase.startsWith("Repetição"))
        else -> true
    }

    private fun executarRapido(texto: String, c: ComandoVoz) {
        TonsVoz.pararPensando()
        pendente?.let { p ->
            pendente = null
            if (c is ComandoVoz.Sim) { acoes.executar(p, texto); fechar(); return }
            if (c is ComandoVoz.Nao || c is ComandoVoz.Cancelar) { resposta("Certo, cancelado.", texto, falar = "Certo, cancelado."); return }
        }
        when (c) {
            is ComandoVoz.Cancelar, is ComandoVoz.Nao, is ComandoVoz.Sim -> { fechar(); return }
            is ComandoVoz.Rotina -> { rodarRotina(c, geracao); return }
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
        if (!deveFalar(c, frase)) { fechar(); return }
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
        resolvido = true
        Log.d(TAG, "transcricao: ${texto?.let { "\"$it\"" } ?: "FALHOU (sem resposta do Gemini)"}")
        val busca = destinoBusca
        if (busca != null) {
            destinoBusca = null
            escuta?.textoAoVivo = false
            TonsVoz.pararPensando()
            estado = EstadoVoz.ESPERANDO_CHAMADA
            acoes.abafar(false)
            // se o Gemini não respondeu (sem internet, limite), vale o que o reconhecimento offline entendeu enquanto a pessoa falava
            val final = texto?.takeIf { it.isNotBlank() } ?: ultimoLivre.takeIf { it.isNotBlank() }
            if (final == null) TonsVoz.erro()
            busca(final)
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
            is ComandoVoz.Lembrete -> criarLembrete(texto, minha)
            is ComandoVoz.Rotina -> rodarRotina(comando, minha)
            // sem verbo de tocar: o Gemini decide se é um pedido de música/vídeo (pelo título, artista...) ou uma pergunta
            is ComandoVoz.Pergunta -> resolverMidia(texto, comando.texto, null, minha)
            // "tocar X": o Gemini já descobriu qual música é (corrige artista errado, nome mal ouvido)
            is ComandoVoz.Tocar -> resolverMidia(texto, comando.consulta, comando, minha)
            else -> {
                val frase = acoes.executar(comando, texto)
                if (!deveFalar(comando, frase)) { fechar(); return }
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
            val busca = pedido?.busca?.takeIf { it.isNotBlank() } ?: consulta
            // playlist, gênero ou "mais tocadas": toca a playlist inteira (a lista vira a fila); sem playlist boa, cai na busca normal
            val querPlaylist = pedido?.intencao == "playlist" || REGEX_PLAYLIST.containsMatchIn(semAcento(dito))
            if (querPlaylist && (tocar == null || tocar.soAudio || tocar.modoIndefinido)) {
                val pl = try { YouTube.playlist(busca) } catch (e: Exception) { Log.w(TAG, "playlist falhou", e); null }
                Log.d(TAG, "playlist: \"$busca\" -> ${pl?.first} (${pl?.second?.size ?: 0} faixas)")
                if (minha != geracao) return@Thread
                if (pl != null) {
                    main.post {
                        if (minha != geracao) return@post
                        TonsVoz.pararPensando()
                        faixa.esconder()
                        acoes.tocar(pl.second, true)
                        estado = EstadoVoz.RESPONDENDO
                        Fala.falar(ctx, "Tocando a playlist ${TextosVoz.tituloFalado(pl.first)}") { fim(0) }
                    }
                    return@Thread
                }
            }
            val soAudio = when {
                tocar != null && !tocar.modoIndefinido -> tocar.soAudio
                pedido != null -> pedido.intencao == "musica" || pedido.intencao == "playlist"
                else -> !acoes.videoAberto()
            }
            // vídeo: "videoclipe" na frente da pesquisa faz o YouTube trazer primeiro o clipe oficial
            val consulta = if (!soAudio && !Regex("(?i)clipe|show|ao vivo").containsMatchIn(busca)) "videoclipe $busca" else busca
            val lista = try { YouTube.buscar(consulta, soAudio).let { if (soAudio) it else YouTube.preferirOficial(it, busca) } } catch (e: Exception) { Log.w(TAG, "busca falhou", e); null }
            main.post {
                if (minha != geracao) return@post
                if (lista == null) { resposta("Não consegui buscar agora.", dito, erro = true); return@post }
                if (lista.isEmpty()) { resposta("Não encontrei $busca.", dito, erro = true); return@post }
                // entendeu: tira a tela do assistente na hora e abre a música/vídeo; a caixa só confirma em voz alta
                TonsVoz.pararPensando()
                faixa.esconder()
                acoes.tocar(lista.take(1), soAudio)
                val gerundio = tocar?.gerundio ?: if (soAudio) "Tocando" else "Reproduzindo"
                estado = EstadoVoz.RESPONDENDO
                Fala.falar(ctx, "$gerundio ${TextosVoz.tituloFalado(lista[0].titulo)}") { fim(0) }
            }
        }.start()
    }

    /**
     * "Bom dia": acende a luz quente, fala a data, o clima de hoje, os lembretes do dia e o próximo alarme.
     * "Boa noite": apaga a luz, pausa a música e fala o próximo alarme.
     */
    private fun rodarRotina(c: ComandoVoz.Rotina, minha: Int) {
        estado = EstadoVoz.PENSANDO
        faixa.pensando("")
        if (c.manha) {
            acoes.executar(ComandoVoz.Luz(AcaoLuz.QUENTE), "")
            Thread {
                val clima = try { Respostas.climaDeHoje(ctx) } catch (e: Throwable) { null }
                main.post {
                    if (minha != geracao) return@post
                    val dia = java.text.SimpleDateFormat("EEEE, d 'de' MMMM", java.util.Locale("pt", "BR")).format(java.util.Date())
                    val frase = buildString {
                        append("Bom dia! Hoje é $dia. ")
                        if (clima != null) append(clima).append(' ')
                        append(acoes.resumoRotina(true))
                    }.trim()
                    resposta(frase, "", falar = frase)
                }
            }.start()
        } else {
            acoes.executar(ComandoVoz.Luz(AcaoLuz.APAGAR_SUAVE), "")
            acoes.executar(ComandoVoz.Pausar, "")
            val frase = "Boa noite! Estou apagando as luzes e pausei a música. ${acoes.resumoRotina(false)} Durma bem.".replace("  ", " ")
            resposta(frase, "", falar = frase)
        }
    }

    /** "Me lembra de X às 15h": o Gemini separa o quê e o quando; a caixa guarda e confirma em voz alta. */
    private fun criarLembrete(dito: String, minha: Int) {
        estado = EstadoVoz.PENSANDO
        faixa.pensando(dito)
        Thread {
            val l = try { Respostas.interpretarLembrete(ctx, dito) } catch (e: Throwable) { null }
            main.post {
                if (minha != geracao) return@post
                if (l == null) {
                    resposta("Não entendi quando lembrar. Diga, por exemplo: me lembra de ligar para o João às 3 da tarde.", dito, erro = true)
                } else {
                    val frase = acoes.agendarLembrete(l.texto, l.quandoMs)
                    resposta(frase, dito, falar = frase)
                }
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
        // o volume e a música só voltam depois que a tela do assistente fechou de vez (fecha em esperaMs e some em ~550 ms), e só
        // se ele não foi chamado de novo nesse meio-tempo
        val g = geracao
        Handler(Looper.getMainLooper()).postDelayed({ if (g == geracao) acoes.abafar(false) }, esperaMs + 650L)
        faixa.fecharEm(esperaMs)
        if (ativo && escuta != null) estado = EstadoVoz.ESPERANDO_CHAMADA
    }
}
