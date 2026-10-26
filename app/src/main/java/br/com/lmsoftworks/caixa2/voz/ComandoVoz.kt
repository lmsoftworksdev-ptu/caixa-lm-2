package br.com.lmsoftworks.caixa2.voz

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** O que a pessoa pediu por voz. */
sealed class ComandoVoz {
    /** "pesquisar musica fulano": abre a lista de resultados. */
    data class Pesquisar(val consulta: String, val musica: Boolean) : ComandoVoz()

    /**
     * "tocar fulano" / "reproduzir video fulano": ja toca o primeiro resultado.
     * [gerundio] e o verbo que a caixa fala de volta ("Tocando", "Reproduzindo"...).
     */
    data class Tocar(
        val consulta: String,
        val soAudio: Boolean,
        val gerundio: String = "Tocando",
        /** Nao disse "video" nem "musica" (ex.: "mudar para X"): vale o modo que ja esta tocando. */
        val modoIndefinido: Boolean = false
    ) : ComandoVoz()

    /** "desligar": toca o som de desligar e fecha o app. */
    object Desligar : ComandoVoz()

    // --- controle da reproducao (tela do player) ---
    object Pausar : ComandoVoz()
    object Continuar : ComandoVoz()
    /** "parar": para de vez e volta para a lista. */
    object Parar : ComandoVoz()
    object Proxima : ComandoVoz()
    object Anterior : ComandoVoz()
    object Recomecar : ComandoVoz()
    data class Repetir(val ligar: Boolean) : ComandoVoz()
    /** Segundos para pular; negativo = voltar. */
    data class PularTempo(val segundos: Int) : ComandoVoz()
    object OQueToca : ComandoVoz()

    // --- valem em qualquer tela ---
    data class Volume(val acao: AcaoVolume, val nivel: Int = 0) : ComandoVoz()
    /** Resposta pronta que a caixa so fala (horas, data, ajuda). */
    data class Falar(val texto: String) : ComandoVoz()

    object Voltar : ComandoVoz()

    /** Fita de LED: [acao] = ligar, desligar, cor, modo ou brilho. */
    data class Luz(val acao: AcaoLuz, val cor: Int = 0, val modo: String = "", val brilho: Int = 0) : ComandoVoz()

    /** "abrir o wi-fi": [destino] = inicio, wifi, bluetooth, alarmes, led, midia. */
    data class Ir(val destino: String) : ComandoVoz()

    /** "criar alarme as 7 e meia". */
    data class CriarAlarme(val hora: Int, val minuto: Int) : ComandoVoz()

    /** Qualquer outra coisa dita sem "tocar"/"reproduzir": a caixa pesquisa e responde falando. */
    data class Pergunta(val texto: String) : ComandoVoz()

    data class Desconhecido(val texto: String) : ComandoVoz()
}

enum class AcaoVolume { AUMENTAR, DIMINUIR, MAXIMO, MINIMO, NIVEL, MUDO, DESMUTAR }

enum class AcaoLuz { LIGAR, DESLIGAR, COR, MODO, BRILHO }

object InterpretadorVoz {

    private val VERBOS_PESQUISA = setOf("pesquisar", "pesquisa", "procurar", "procura", "buscar", "busca")
    private val VERBOS_TOCAR = setOf(
        "tocar", "toca", "toque", "colocar", "coloca", "bota", "botar", "por", "põe", "poe",
        "ouvir", "escutar", "escuta"
    )

    /** "mudar para X", "trocar para X", "passar para X"... (no meio da reproducao ou fora dela). */
    private val VERBOS_TROCAR = setOf(
        "mudar", "muda", "mude", "trocar", "troca", "troque", "passar", "passa", "passe",
        "ir", "vai", "abrir", "abre", "abra", "mostrar", "mostra", "mostre"
    )
    private val LIGACOES = setOf("para", "pra", "pro", "o", "a", "os", "as", "de", "do", "da", "um", "uma")
    private val ABERTURAS_EDUCADAS = listOf(
        "por favor ", "eu quero ", "quero ", "gostaria de ", "eu gostaria de ", "você pode ", "voce pode ", "pode "
    )
    // "reproduzir" abre VIDEO (o Google as vezes entende "produzir"). "tocar" e so musica/audio.
    private val VERBOS_ASSISTIR = setOf(
        "reproduzir", "reproduza", "reproduz", "produzir", "produza", "produz",
        "assistir", "assiste", "assista", "ver"
    )
    private val PAUSAR = setOf("pausar", "pausa", "pause", "pausado")
    private val PARAR = setOf("parar", "pare", "para", "stop", "chega", "cancelar", "cancela")
    private val CONTINUAR = setOf("continuar", "continua", "continue", "retomar", "retoma", "play", "resume")
    private val DESLIGAR = setOf("desligar", "desliga", "desligue", "encerrar", "encerra", "tchau")
    private val VOLTAR = setOf("voltar", "volta", "menu", "inicio", "início", "sair")
    private val PALAVRAS_MUSICA = setOf("musica", "música", "musicas", "músicas", "som")
    private val PALAVRAS_VIDEO = setOf("video", "vídeo", "videos", "vídeos")
    private val ARTIGOS_INICIAIS = setOf("a", "o", "as", "os", "uma", "um", "de", "da", "do", "pela", "pelo")

    /** "volume dez" -> "volume 10": o Gemini às vezes escreve o número por extenso. */
    private fun numerosPorExtenso(t: String): String {
        if (!t.contains("volume")) return t
        val m = mapOf("zero" to "0", "um" to "1", "uma" to "1", "dois" to "2", "duas" to "2", "três" to "3", "tres" to "3", "quatro" to "4",
            "cinco" to "5", "seis" to "6", "sete" to "7", "oito" to "8", "nove" to "9", "dez" to "10")
        return t.split(" ").joinToString(" ") { m[it] ?: it }
    }

    private val RE_NIVEL = Regex("""volume\s+(?:no\s+|em\s+|para\s+|pra\s+|na\s+)?(\d{1,3})""")
    private val RE_TEMPO = Regex("""(\d{1,3})\s*(segundo|segundos|minuto|minutos)""")

    /** True se a frase e so um verbo de busca/toque ("tocar"), faltando dizer o que. */
    fun soVerbo(textoBruto: String): Boolean {
        val palavras = limpar(textoBruto).split(" ").filter { it.isNotEmpty() }
        if (palavras.isEmpty() || palavras.size > 2) return false
        val verbo = palavras.first()
        val ehVerbo = verbo in VERBOS_PESQUISA || verbo in VERBOS_TOCAR || verbo in VERBOS_ASSISTIR
        val resto = palavras.drop(1)
        return ehVerbo && resto.all { it in PALAVRAS_MUSICA || it in PALAVRAS_VIDEO || it in ARTIGOS_INICIAIS }
    }

    fun interpretar(textoBruto: String): ComandoVoz {
        // Restos da chamada ("ei caixa DE SOM"): tira do comeco.
        var texto = limpar(textoBruto)
        for (sobra in listOf("de som", "caixa de som", "caixa")) {
            if (texto == sobra) { texto = ""; break }
            if (texto.startsWith("$sobra ")) texto = texto.removePrefix("$sobra ").trim()
        }
        // "por favor", "eu quero", "quero", "pode"... no comeco nao mudam o pedido.
        for (a in ABERTURAS_EDUCADAS) if (texto.startsWith(a)) texto = texto.removePrefix(a).trim()
        if (texto.isEmpty()) return ComandoVoz.Desconhecido(textoBruto)

        val palavras = texto.split(" ").filter { it.isNotEmpty() }
        val primeira = palavras.first()
        val resto = palavras.drop(1)
        fun tem(p: String) = texto.contains(p)
        fun temPalavra(vararg ps: String) = palavras.any { it in ps }

        // ---------- luzes, navegação e alarme (antes de "desligar", que também apaga a luz) ----------
        interpretarLuz(texto, palavras)?.let { return it }
        interpretarNavegacao(texto, palavras)?.let { return it }
        RE_ALARME.find(texto)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = when (val g = m.groupValues[2]) { "" -> 0; "meia" -> 30; else -> g.toIntOrNull() ?: 0 }
            if (h in 0..23 && min in 0..59) return ComandoVoz.CriarAlarme(h, min)
        }

        // ---------- comandos de sistema (frases fixas, antes de tudo) ----------
        if (primeira in DESLIGAR) return ComandoVoz.Desligar

        // Volume
        RE_NIVEL.find(numerosPorExtenso(texto))?.let { return ComandoVoz.Volume(AcaoVolume.NIVEL, it.groupValues[1].toInt().coerceIn(0, 10)) }
        if (tem("volume maximo") || tem("volume máximo") || tem("volume no maximo") || tem("volume no máximo"))
            return ComandoVoz.Volume(AcaoVolume.MAXIMO)
        if (tem("volume minimo") || tem("volume mínimo")) return ComandoVoz.Volume(AcaoVolume.MINIMO)
        if (temPalavra("desmutar", "desmuta") || tem("tirar o mudo") || tem("tira o mudo") || tem("voltar o som") || tem("ligar o som"))
            return ComandoVoz.Volume(AcaoVolume.DESMUTAR)
        if (temPalavra("mudo", "silenciar", "silencia", "silencio", "silêncio", "mutar", "muta"))
            return ComandoVoz.Volume(AcaoVolume.MUDO)
        val falaDeVolume = tem("volume") || temPalavra("som") || tem("mais alto") || tem("mais baixo")
        if (falaDeVolume) {
            if (temPalavra("aumentar", "aumenta", "aumente", "subir", "sobe", "suba", "elevar", "eleva") || tem("mais alto") ||
                tem("mais forte") || tem("mais volume")
            ) return ComandoVoz.Volume(AcaoVolume.AUMENTAR)
            if (temPalavra("diminuir", "diminui", "diminua", "abaixar", "abaixa", "abaixe", "baixar", "baixa", "baixe", "descer", "desce") ||
                tem("mais baixo") || tem("menos volume") || tem("mais fraco")
            ) return ComandoVoz.Volume(AcaoVolume.DIMINUIR)
        }

        // Conversa curta
        CONVERSA.entries.firstOrNull { texto == it.key }?.let { return ComandoVoz.Falar(it.value) }

        // Horas, data e ajuda ("que horas sao", "quantas horas sao", "me diga as horas"...)
        val pedeTocar = primeira in VERBOS_TOCAR || primeira in VERBOS_ASSISTIR || primeira in VERBOS_PESQUISA
        // Pergunta sobre o futuro ("quando sera o proximo debate") vai para a pesquisa, nao e relogio/proxima.
        val perguntaLonga = palavras.size > 4 || temPalavra("sera", "será", "vai", "quando", "quem", "onde")
        if (!pedeTocar && !perguntaLonga && (temPalavra("horas", "hora") || tem("que horas")))
            return ComandoVoz.Falar(fraseHoras())
        if (!pedeTocar && !perguntaLonga && (tem("que dia") || temPalavra("data") || tem("dia de hoje") || tem("hoje e que dia") ||
                tem("hoje é que dia"))
        ) return ComandoVoz.Falar(fraseData())
        if (texto == "ajuda" || tem("o que voce sabe") || tem("o que você sabe") || tem("o que voce faz") ||
            tem("o que você faz") || tem("lista de comandos") || tem("quais comandos") || texto == "comandos"
        ) return ComandoVoz.Falar(FRASE_AJUDA)

        // Controle da reproducao
        if (tem("parar de repetir") || tem("nao repetir") || tem("não repetir") || tem("desativar repeticao") ||
            tem("desativar repetição") || tem("sem repetir")
        ) return ComandoVoz.Repetir(false)
        if (primeira in setOf("repetir", "repete", "repita") || tem("em loop") || tem("repetir musica") || tem("repetir música"))
            return ComandoVoz.Repetir(true)

        if (tem("o que esta tocando") || tem("o que está tocando") || tem("que musica e essa") || tem("que música é essa") ||
            tem("qual a musica") || tem("qual a música") || tem("qual e a musica") || tem("qual é a música") ||
            tem("o que ta tocando") || tem("o que tá tocando")
        ) return ComandoVoz.OQueToca

        RE_TEMPO.find(texto)?.let { m ->
            val n = m.groupValues[1].toInt()
            val s = if (m.groupValues[2].startsWith("minuto")) n * 60 else n
            if (primeira in setOf("avancar", "avançar", "avanca", "avança", "adiantar", "adianta", "pular", "pula", "pule"))
                return ComandoVoz.PularTempo(s)
            if (primeira in setOf("voltar", "volta", "retroceder", "retrocede", "recuar", "recua"))
                return ComandoVoz.PularTempo(-s)
        }

        if (tem("do comeco") || tem("do começo") || temPalavra("recomecar", "recomeçar", "recomeca", "recomeça", "reiniciar", "reinicia") ||
            tem("de novo") || tem("outra vez")
        ) return ComandoVoz.Recomecar

        if ((!perguntaLonga && temPalavra("anterior")) || tem("voltar a musica") || tem("voltar a música") || tem("voltar musica") || tem("voltar música"))
            return ComandoVoz.Anterior
        if ((!perguntaLonga && temPalavra("proxima", "próxima", "proximo", "próximo", "seguinte")) ||
            (primeira in setOf("pular", "pula", "pule", "avancar", "avançar") && resto.size <= 2)
        ) return ComandoVoz.Proxima

        if (palavras.size <= 3 && primeira in PAUSAR) return ComandoVoz.Pausar
        if (palavras.size <= 3 && primeira in PARAR) return ComandoVoz.Parar
        if (palavras.size <= 3 && (primeira in CONTINUAR || tem("voltar a tocar"))) return ComandoVoz.Continuar

        return when {
            palavras.size == 1 && primeira in VERBOS_TOCAR -> ComandoVoz.Continuar
            palavras.size == 1 && primeira in VOLTAR -> ComandoVoz.Voltar
            primeira in VERBOS_PESQUISA -> {
                val (tipo, consulta) = separarTipo(resto)
                if (consulta.isEmpty()) ComandoVoz.Desconhecido(textoBruto)
                else ComandoVoz.Pesquisar(consulta, musica = tipo != Tipo.VIDEO)
            }
            // "mudar para X": troca para X; "mudar para outro video" = proximo da lista.
            primeira in VERBOS_TROCAR -> {
                val sem = resto.dropWhile { it in LIGACOES }
                val (tipo, consulta) = separarTipo(sem)
                when {
                    consulta.isEmpty() && tipo == Tipo.NENHUM -> ComandoVoz.Desconhecido(textoBruto)
                    consulta.isEmpty() || consulta.startsWith("outr") || consulta.startsWith("próxim") ||
                        consulta.startsWith("proxim") -> ComandoVoz.Proxima
                    else -> ComandoVoz.Tocar(
                        consulta,
                        soAudio = tipo != Tipo.VIDEO,
                        gerundio = "Mudando para",
                        modoIndefinido = tipo == Tipo.NENHUM
                    )
                }
            }
            // "tocar ..." = musica (so audio), a menos que diga "video".
            primeira in VERBOS_TOCAR -> {
                val (tipo, consulta) = separarTipo(resto)
                if (consulta.isEmpty()) ComandoVoz.Desconhecido(textoBruto)
                else ComandoVoz.Tocar(consulta, soAudio = tipo != Tipo.VIDEO, gerundio = gerundioDe(primeira))
            }
            // "reproduzir ..." = video, a menos que diga "musica" ("reproduzir musica X" toca so o audio).
            primeira in VERBOS_ASSISTIR -> {
                val (tipo, consulta) = separarTipo(resto)
                if (consulta.isEmpty()) ComandoVoz.Desconhecido(textoBruto)
                else ComandoVoz.Tocar(consulta, soAudio = tipo == Tipo.MUSICA, gerundio = gerundioDe(primeira))
            }
            // Sem verbo de tocar: e uma pergunta/pedido de informacao -> pesquisa na internet e responde.
            else -> ComandoVoz.Pergunta(texto)
        }
    }

    private val RE_ALARME = Regex("""(?:criar|crie|colocar|coloca|põe|poe|marcar|marca|programar|programa|definir|define|ajustar|ajusta|configurar|configura)\s+(?:um\s+|o\s+)?alarme\s+(?:para\s+|pra\s+)?(?:as\s+|às\s+)?(\d{1,2})(?:\s+(?:horas?\s+)?(?:e\s+)?(\d{2}|meia))?""")

    private val CORES = mapOf(
        "azul" to 0xFF0000FF.toInt(), "vermelha" to 0xFFFF0000.toInt(), "vermelho" to 0xFFFF0000.toInt(),
        "verde" to 0xFF00FF00.toInt(), "laranja" to 0xFFFF6A00.toInt(), "amarela" to 0xFFFFD000.toInt(), "amarelo" to 0xFFFFD000.toInt(),
        "roxa" to 0xFF8A00FF.toInt(), "roxo" to 0xFF8A00FF.toInt(), "violeta" to 0xFF8A00FF.toInt(), "rosa" to 0xFFFF0080.toInt(),
        "ciano" to 0xFF00FFFF.toInt(), "branca" to 0xFFFFFFFF.toInt(), "branco" to 0xFFFFFFFF.toInt()
    )

    private fun interpretarLuz(texto: String, palavras: List<String>): ComandoVoz? {
        fun tem(vararg ps: String) = palavras.any { it in ps }
        val falaLuz = tem("luz", "luzes", "led", "leds", "fita", "lampada", "lâmpada", "ambilight") || texto.contains("arco iris")
        if (!falaLuz) return null
        // pergunta sobre outra coisa ("qual a cor da luz do sol") vai para a pesquisa
        if (tem("qual", "quem", "quando", "onde", "por", "porque", "quanto")) return null
        val apagar = tem("apagar", "apaga", "apague", "desligar", "desliga", "desligue", "apagada")
        val acender = tem("acender", "acende", "acenda", "ligar", "liga", "ligue", "ligada")
        for ((nome, cor) in CORES) if (nome in palavras) return ComandoVoz.Luz(AcaoLuz.COR, cor = cor)
        if (tem("ambilight")) return ComandoVoz.Luz(AcaoLuz.MODO, modo = "ambilight")
        if (texto.contains("arco iris") || tem("arcoiris")) return ComandoVoz.Luz(AcaoLuz.MODO, modo = "arco")
        if (tem("ritmo", "ritmica", "ritmico", "rítmica", "rítmico", "musica") && !apagar) return ComandoVoz.Luz(AcaoLuz.MODO, modo = "ritmo")
        if (tem("fixo", "fixa")) return ComandoVoz.Luz(AcaoLuz.MODO, modo = "fixo")
        Regex("""(\d{1,3})\s*(?:por\s*cento|%)?""").find(texto)?.let { m ->
            if (tem("brilho", "intensidade", "forca", "força")) return ComandoVoz.Luz(AcaoLuz.BRILHO, brilho = m.groupValues[1].toInt().coerceIn(5, 100))
        }
        if (apagar) return ComandoVoz.Luz(AcaoLuz.DESLIGAR)
        if (acender) return ComandoVoz.Luz(AcaoLuz.LIGAR)
        return null
    }

    private fun interpretarNavegacao(texto: String, palavras: List<String>): ComandoVoz? {
        val abre = palavras.firstOrNull() in setOf("abrir", "abre", "abra", "ir", "vai", "va", "vá", "mostrar", "mostra", "mostre", "ver")
        if (!abre || palavras.size > 5) return null
        val t = " $texto "
        return when {
            t.contains(" wifi ") || t.contains(" wi fi ") -> ComandoVoz.Ir("wifi")
            t.contains(" bluetooth ") -> ComandoVoz.Ir("bluetooth")
            t.contains(" alarme ") || t.contains(" alarmes ") -> ComandoVoz.Ir("alarmes")
            t.contains(" luzes ") || t.contains(" luz ") -> ComandoVoz.Ir("led")
            t.contains(" midia ") || t.contains(" mídia ") || t.contains(" menu de busca ") -> ComandoVoz.Ir("midia")
            t.contains(" inicio ") || t.contains(" início ") || t.contains(" menu ") || t.contains(" tela inicial ") -> ComandoVoz.Ir("inicio")
            else -> null
        }
    }

    private val CONVERSA = mapOf(
        "oi" to "Olá!",
        "ola" to "Olá!",
        "olá" to "Olá!",
        "bom dia" to "Bom dia!",
        "boa tarde" to "Boa tarde!",
        "boa noite" to "Boa noite!",
        "obrigado" to "De nada!",
        "obrigada" to "De nada!",
        "valeu" to "De nada!",
        "como voce esta" to "Estou ótima, obrigada!",
        "como você está" to "Estou ótima, obrigada!",
        "tudo bem" to "Tudo ótimo por aqui!",
        "quem e voce" to "Eu sou a sua caixa de som.",
        "quem é você" to "Eu sou a sua caixa de som."
    )

    private fun gerundioDe(verbo: String): String = when (verbo) {
        in setOf("reproduzir", "reproduza", "reproduz", "produzir", "produza", "produz") -> "Reproduzindo"
        in setOf("assistir", "assiste", "assista", "ver") -> "Assistindo"
        in setOf("colocar", "coloca", "bota", "botar", "por", "põe", "poe") -> "Colocando"
        else -> "Tocando"
    }

    private val PALAVRAS_FRACAS = setOf(
        "a", "o", "as", "os", "um", "uma", "de", "da", "do", "das", "dos", "no", "na", "nos", "nas", "e",
        "musica", "música", "musicas", "músicas", "video", "vídeo", "videos", "vídeos"
    )

    /**
     * Versao curta do pedido para a caixa FALAR: sem "musica", "do", "da"... e sem anos soltos.
     * "santa nossoi do rbd" -> "santa nossoi rbd".
     */
    fun consultaFalada(consulta: String): String {
        val palavras = limpar(consulta).split(" ").filter {
            it.isNotEmpty() && it !in PALAVRAS_FRACAS && !(it.length == 4 && it.all { c -> c.isDigit() })
        }
        return palavras.take(6).joinToString(" ").ifEmpty { consulta }
    }

    // ---------- respostas prontas ----------

    /** "São 22 horas e 58 minutos" (e nao "22 e 58"). */
    private fun fraseHoras(): String {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        val abertura = when (h) {
            0 -> if (m == 0) "É meia-noite" else "É meia-noite"
            1 -> "É uma hora"
            12 -> if (m == 0) "É meio-dia" else "É meio-dia"
            else -> "São $h horas"
        }
        val minutos = when (m) {
            0 -> ""
            1 -> " e um minuto"
            else -> " e $m minutos"
        }
        return abertura + minutos
    }

    private fun fraseData(): String {
        val f = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", Locale("pt", "BR"))
        return "Hoje é " + f.format(Date())
    }

    private const val FRASE_AJUDA =
        "Eu toco música, reproduzo vídeos, controlo o volume, pauso, continuo, passo para a próxima, " +
            "volto para a anterior, repito, e digo as horas e a data. Diga, por exemplo: tocar uma música, " +
            "reproduzir um vídeo, aumentar o volume, próxima, ou desligar."

    // ---------- apoio ----------

    private enum class Tipo { MUSICA, VIDEO, NENHUM }

    /** Tira "musica"/"video" e artigos do comeco: "uma musica de fulano" -> fulano. */
    private fun separarTipo(palavras: List<String>): Pair<Tipo, String> {
        var tipo = Tipo.NENHUM
        val lista = palavras.toMutableList()
        while (lista.isNotEmpty()) {
            val p = lista.first()
            when {
                p in PALAVRAS_MUSICA -> { tipo = Tipo.MUSICA; lista.removeAt(0) }
                p in PALAVRAS_VIDEO -> { tipo = Tipo.VIDEO; lista.removeAt(0) }
                p in ARTIGOS_INICIAIS -> lista.removeAt(0)
                else -> break
            }
        }
        return tipo to lista.joinToString(" ")
    }

    private fun limpar(t: String): String =
        Normalizer.normalize(t.lowercase().trim(), Normalizer.Form.NFC)
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
