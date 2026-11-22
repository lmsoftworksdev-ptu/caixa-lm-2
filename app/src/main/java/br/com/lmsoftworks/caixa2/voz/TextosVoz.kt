package br.com.lmsoftworks.caixa2.voz

/** O que a caixa fala de volta depois de entender o pedido (null = fica em silencio). */
enum class EstadoVoz { DESLIGADA, CARREGANDO, SEM_MODELO, ESPERANDO_CHAMADA, OUVINDO_COMANDO, PENSANDO, RESPONDENDO, ERRO }

object FrasesVoz {
    fun confirmacao(c: ComandoVoz): String? = when (c) {
        // A fala de "Tocando <titulo real>" sai da tela do player, depois da busca (ve PlayerActivity).
        is ComandoVoz.Tocar -> null
        is ComandoVoz.Pesquisar -> "Pesquisando ${InterpretadorVoz.consultaFalada(c.consulta)}"
        is ComandoVoz.Desconhecido -> "Não consegui entender."
        is ComandoVoz.Falar -> c.texto
        is ComandoVoz.Pergunta -> null // respondida pela internet (ver Voz)
        is ComandoVoz.Repetir -> if (c.ligar) "Repetindo" else "Repetição desligada"
        // Pausar, continuar, parar, próxima, anterior, volume (tratado a parte)...: em silencio.
        else -> null
    }
}

object TextosVoz {
    /**
     * Titulo do video/musica pronto para falar: sem (Official Video), [4K], tudo depois de "|", sem "&".
     * Ex.: "Solange Almeida - Fogo (Ao Vivo) [HD] | DVD 2025" -> "Solange Almeida - Fogo".
     */
    fun tituloFalado(titulo: String): String {
        var s = titulo
            .replace(Regex("""\([^)]*\)|\[[^\]]*]"""), " ")
            .replace(Regex("""\s*[|•·].*$"""), "")
            .replace("&", " e ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (s.length > 90) s = s.take(90).substringBeforeLast(' ')
        return s.ifBlank { titulo.take(90) }
    }
}
