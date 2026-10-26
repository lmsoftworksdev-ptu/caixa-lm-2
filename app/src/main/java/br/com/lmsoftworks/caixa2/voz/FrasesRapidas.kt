package br.com.lmsoftworks.caixa2.voz

/**
 * Os comandos que a caixa entende NA HORA, sem internet e sem pensar: o reconhecedor offline (Vosk) recebe só estas frases como
 * gramática e, se o que foi dito bater com uma delas, o comando é executado direto. O que não bate (nome de música, pergunta)
 * segue para o Gemini. Palavras que não existam no vocabulário do modelo são ignoradas pelo Vosk, sem estragar as outras.
 */
object FrasesRapidas {
    private val NUMEROS = listOf("zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez")
    private val DEZENAS = listOf("dez", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa", "cem")
    private val CORES = listOf("azul", "vermelha", "verde", "laranja", "amarela", "roxa", "rosa", "branca", "ciano")

    fun todas(): List<String> {
        val l = ArrayList<String>()
        // ---- volume ----
        for (n in NUMEROS) l += "volume $n"
        l += listOf("volume máximo", "volume mínimo", "aumentar o volume", "aumentar volume", "diminuir o volume", "diminuir volume",
            "abaixar o volume", "baixar o volume", "mais alto", "mais baixo", "mais volume", "menos volume",
            "mudo", "silenciar", "tirar do mudo", "ligar o som")
        // ---- o que está tocando ----
        l += listOf("pausar", "pausa", "pause", "continuar", "continua", "retomar", "play", "parar", "pare", "próxima", "próximo", "pular",
            "próxima música", "pular música", "anterior", "música anterior", "voltar a música", "recomeçar", "do começo", "de novo",
            "repetir", "repetir esta música", "parar de repetir", "embaralhar", "modo aleatório",
            "o que está tocando", "que música é essa", "que música é esta", "qual é a música")
        for (t in listOf("dez segundos", "quinze segundos", "vinte segundos", "trinta segundos", "um minuto")) {
            l += "avançar $t"; l += "voltar $t"
        }
        // ---- luzes ----
        l += listOf("ligar as luzes", "ligar a luz", "acender a luz", "acender as luzes", "apagar as luzes", "apagar a luz",
            "desligar as luzes", "desligar a luz", "modo ambilight", "ambilight", "modo rítmico", "modo ritmo", "modo arco íris",
            "modo fixo", "luz mais fraca", "luz mais forte", "luz quente", "luz de leitura")
        for (c in CORES) { l += "luz $c"; l += "acender a luz $c"; l += "ligar a luz $c" }
        for (d in DEZENAS) { l += "brilho $d"; l += "brilho $d por cento" }
        // ---- timer ("timer" não existe no vocabulário do modelo: aceita também "temporizador") ----
        for (nome in listOf("timer", "temporizador")) {
            for (m in listOf("um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez", "quinze", "vinte", "trinta", "quarenta", "cinquenta"))
                l += "$nome de $m minutos"
            l += listOf("$nome de um minuto", "$nome de meia hora", "$nome de uma hora", "$nome de dez segundos", "$nome de trinta segundos",
                "cancelar o $nome", "parar o $nome", "quanto falta no $nome", "quanto falta para o $nome")
        }
        // ---- hora e data ----
        l += listOf("que horas são", "que horas são agora", "que dia é hoje", "qual é a data", "qual a data de hoje")
        // ---- telas ----
        for (t in listOf("o wifi", "o wi fi", "o bluetooth", "as luzes", "os alarmes", "a mídia", "o menu", "o menu de busca", "o início")) l += "abrir $t"
        l += listOf("voltar", "tela inicial", "ir para o início")
        // ---- conversa e controle do assistente ----
        l += listOf("oi", "olá", "bom dia", "boa tarde", "boa noite", "obrigado", "obrigada", "valeu", "quem é você", "o que você sabe fazer",
            "o que você faz", "ajuda", "cancelar", "esquece", "sim", "não", "confirmar", "soneca")
        // ---- sistema ----
        l += listOf("desligar a caixa", "reiniciar a caixa")
        return l.distinct()
    }
}
