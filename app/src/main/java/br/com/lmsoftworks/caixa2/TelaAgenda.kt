package br.com.lmsoftworks.caixa2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

/** Círculo de marcar da v3 (40): borda de 2,5 clara; marcado = cheio no destaque com o ✓ branco. */
class CirculoMarcar(c: Context) : View(c) {
    var marcado = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val v = Path()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(40.d(), 40.d())

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        if (marcado) {
            p.style = Paint.Style.FILL; p.color = Cfg.AZUL
            canvas.drawCircle(cx, cy, 20.d().toFloat(), p)
            p.style = Paint.Style.STROKE; p.color = Color.WHITE; p.strokeWidth = 3.d().toFloat(); p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            v.reset(); v.moveTo(cx - 8.d(), cy + 0.5f.d()); v.lineTo(cx - 2.5f.d(), cy + 6.d()); v.lineTo(cx + 8.5f.d(), cy - 6.d())
            canvas.drawPath(v, p)
        } else {
            p.style = Paint.Style.STROKE; p.color = Color.parseColor("#59FFFFFF"); p.strokeWidth = 2.5f.d()
            canvas.drawCircle(cx, cy, 18.5f.d(), p)
        }
    }
}

/** Seletor de abas da v3: pílula #12FFFFFF com 5 de folga; abas de 50 de altura (raio 25, texto 20 medium); a ativa fica no destaque. */
class AbasV3(c: Context, nomes: List<String>, private val aoEscolher: (Int) -> Unit) : LinearLayout(c) {
    private val abas = ArrayList<TextView>()
    var ativa = 0
        set(v) { field = v; pintar() }

    init {
        setPadding(5.d(), 5.d(), 5.d(), 5.d())
        background = fundoArredondado(Color.parseColor("#12FFFFFF"), 30.d())
        nomes.forEachIndexed { i, n ->
            val t = TextView(c).apply {
                text = n
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 20.d().toFloat())
                typeface = FONTE_MEDIA
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { aoEscolher(i) }
            }
            abas.add(t)
            addView(t, LayoutParams(0, 50.d(), 1f))
        }
        pintar()
    }

    fun pintar() {
        abas.forEachIndexed { i, t ->
            val on = i == ativa
            t.setTextColor(if (on) Color.WHITE else Tema.TEXTO2)
            t.background = if (on) fundoArredondado(Cfg.AZUL, 25.d()) else null
        }
    }
}

/**
 * Tela Alarmes, Lembretes e Listas da v3 (três abas): à esquerda o cartão com as abas, o botão "+ Novo" e a lista; à direita o editor do que
 * está selecionado. Tudo que muda é salvo na hora. Textos (lembrete, item, nome de lista) são digitados na tela de texto com o teclado único.
 */
class TelaAlarmes(
    private val c: Context,
    private val alarmes: AlarmeControle,
    private val lembretes: LembreteControle,
    private val listas: ListaControle,
    private val aoTestar: (Alarme) -> Unit
) : FrameLayout(c) {

    private val nomesAbas = listOf("Alarmes", "Lembretes", "Listas")
    private val ids = listOf("alarmes", "lembretes", "listas")
    private var aba = 0
    private var alarmeSel = -1
    private var lembreteSel = -1L
    private var listaSel = ""
    private var assinatura = ""

    private val abasView = AbasV3(c, nomesAbas) { i -> aba = i; atualizar() }
    private val botaoNovo = botao(c, "+ Novo", Cfg.AZUL) { novo() }
    private val lista = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val editor = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val principal = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
    private val telaTexto = TelaTexto(c)
    private val modal = ModalV3(c)

    val emTexto: Boolean get() = telaTexto.aberto
    val emModal: Boolean get() = modal.aberto
    private val destaque get() = Cfg.AZUL

    init {
        principal.setPadding(Tema.MARGEM_LATERAL.d(), 40.d(), Tema.MARGEM_LATERAL.d(), Tema.MARGEM_BASE.d())
        val esquerda = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = cartaoVidro()
            setPadding(24.d(), 24.d(), 24.d(), 16.d())
        }
        val topo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(abasView, LinearLayout.LayoutParams(0, -2, 1f))
            addView(botaoNovo, LinearLayout.LayoutParams(-2, 60.d()).apply { leftMargin = 12.d() })
        }
        esquerda.addView(topo, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.d() })
        esquerda.addView(RolagemFina(c).apply { addView(lista) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val direita = FrameLayout(c).apply {
            background = cartaoVidro()
            setPadding(30.d(), 26.d(), 30.d(), 22.d())
            addView(editor, LayoutParams(-1, -1))
        }
        principal.addView(esquerda, LinearLayout.LayoutParams(560.d(), -1))
        principal.addView(direita, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = 24.d() })
        addView(principal, LayoutParams(-1, -1))
        telaTexto.aoFechar = { principal.visibility = View.VISIBLE }
        addView(telaTexto, LayoutParams(-1, -1))
        addView(modal, LayoutParams(-1, -1))
        atualizar()
    }

    // ------------------------------------------------------------------ navegação

    /** Abre numa aba ("alarmes", "lembretes" ou "listas"), já com o item de [id] selecionado (se houver). */
    fun abrirAba(nome: String, id: Long? = null) {
        val i = ids.indexOf(nome).coerceAtLeast(0)
        aba = i
        if (id != null) when (i) { 0 -> alarmeSel = id.toInt(); 1 -> lembreteSel = id }
        atualizar()
    }

    fun voltar(): Boolean {
        if (modal.aberto) { modal.fechar(); return true }
        if (telaTexto.aberto) { telaTexto.fechar(); return true }
        return false
    }

    /** Refaz tudo com o que está salvo. */
    fun atualizar() {
        if (alarmes.porId(alarmeSel) == null) alarmeSel = alarmes.lista.firstOrNull()?.id ?: -1
        val todosL = lembretes.todos + lembretes.concluidos
        if (todosL.none { it.id == lembreteSel }) lembreteSel = lembretes.todos.firstOrNull()?.id ?: lembretes.concluidos.firstOrNull()?.id ?: -1L
        if (listaSel !in listas.nomes) listaSel = listas.nomes.firstOrNull() ?: ""
        editor.gravity = Gravity.NO_GRAVITY
        abasView.ativa = aba
        botaoNovo.background = fundoArredondado(Cfg.AZUL, Tema.RAIO_BOTAO.d())
        when (aba) { 0 -> { montarListaAlarmes(); montarEditorAlarme() }; 1 -> { montarListaLembretes(); montarEditorLembrete() }; else -> { montarListaListas(); montarEditorLista() } }
        assinatura = assinaturaAtual()
    }

    /** Chamado a cada meio segundo pela tela principal: se algo mudou por fora (um lembrete pela voz, o próximo aviso), redesenha. */
    fun tique() {
        if (telaTexto.aberto) return
        val a = assinaturaAtual()
        if (a != assinatura) atualizar()
    }

    private fun assinaturaAtual(): String = buildString {
        append(aba).append(Cfg.AZUL)
        lembretes.todos.forEach { append('|').append(it.id).append(it.quandoMs).append(it.texto) }
        lembretes.concluidos.forEach { append('|').append(it.id) }
        listas.nomes.forEach { n -> append('|').append(n).append(listas.itens(n).size) }
        alarmes.lista.forEach { append('|').append(it.id).append(it.ligado) }
    }

    private fun novo() {
        when (aba) {
            0 -> { alarmeSel = alarmes.novo().id; atualizar() }
            1 -> abrirTexto(R.drawable.ic_sino, "Escreva o que lembrar", "Salvar", "", "Escreva o que lembrar.") { t ->
                // entra para a próxima hora cheia, já selecionado
                lembreteSel = lembretes.novo(t, LembreteControle.proximaHoraCheia()).id
                atualizar()
            }
            else -> abrirTexto(R.drawable.ic_lista, "Nome da lista", "Criar", "", "Dê um nome para a lista.") { t ->
                listaSel = listas.criar(t); atualizar()
            }
        }
    }

    private fun abrirTexto(icone: Int, dica: String, acao: String, inicial: String, aviso: String, aoSalvar: (String) -> Unit) {
        principal.visibility = View.INVISIBLE
        telaTexto.abrir(
            iconeRes = icone, titulo = dica, dica = dica, acao = acao, inicial = inicial, maximo = 80,
            validar = { t -> if (t.isBlank()) aviso else null }
        ) { t -> principal.visibility = View.VISIBLE; aoSalvar(t.trim()) }
    }

    // ------------------------------------------------------------------ peças

    private fun rotuloEditor(esq: String, dir: String = ""): View = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(texto(c, esq.uppercase(), 15, Tema.TEXTO3, true).apply { letterSpacing = 1.6f / 15f }, LinearLayout.LayoutParams(0, -2, 1f))
        if (dir.isNotEmpty()) addView(texto(c, dir, 19, Tema.TEXTO2))
    }

    private fun seta(recurso: Int, descricao: String, w: Int, h: Int, aoClicar: () -> Unit): View = FrameLayout(c).apply {
        background = fundoArredondado(Color.parseColor("#14FFFFFF"), 18.d())
        isClickable = true
        contentDescription = descricao
        setOnClickListener { aoClicar() }
        efeitoToque()
        addView(icone(c, recurso, 26, Tema.TEXTO), LayoutParams(26.d(), 26.d(), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(w.d(), h.d())
    }

    /** Hora e minuto grandes (Light) com setas em cima e embaixo: a hora de 1 em 1, o minuto de 5 em 5. */
    private fun relogio(hora: Int, minuto: Int, tam: Int, larguraSeta: Int, alturaSeta: Int, aoMudar: (Int, Int) -> Unit): View {
        fun coluna(valor: Int, mais: () -> Unit, menos: () -> Unit) = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(seta(R.drawable.ic_seta_cima, "Aumentar", larguraSeta, alturaSeta, mais))
            addView(texto(c, "%02d".format(valor), tam, Tema.TEXTO).apply { typeface = FONTE_LEVE; fontFeatureSettings = "tnum" }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4.d(); bottomMargin = 4.d() })
            addView(seta(R.drawable.ic_seta_baixo, "Diminuir", larguraSeta, alturaSeta, menos))
        }
        return LinearLayout(c).apply {
            gravity = Gravity.CENTER
            addView(coluna(hora, { aoMudar((hora + 1) % 24, minuto) }, { aoMudar((hora + 23) % 24, minuto) }))
            addView(texto(c, ":", tam, Tema.TEXTO2).apply { typeface = FONTE_LEVE }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 14.d(); rightMargin = 14.d() })
            addView(coluna(minuto, { aoMudar(hora, (minuto + 5) % 60) }, { aoMudar(hora, (minuto + 55) % 60) }))
        }
    }

    /** As 7 bolinhas D S T Q Q S S (52, texto 20 medium); a marcada fica no destaque. */
    private fun bolinhasDias(mascara: Int, aoMudar: (Int) -> Unit): View {
        val letras = listOf("D", "S", "T", "Q", "Q", "S", "S")
        val nomes = listOf("domingo", "segunda", "terça", "quarta", "quinta", "sexta", "sábado")
        return LinearLayout(c).apply {
            gravity = Gravity.CENTER
            for (i in 0..6) {
                val on = (mascara shr i) and 1 == 1
                addView(TextView(c).apply {
                    text = letras[i]
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, 20.d().toFloat())
                    typeface = FONTE_MEDIA
                    gravity = Gravity.CENTER
                    background = fundoArredondado(if (on) destaque else Color.parseColor("#14FFFFFF"), 26.d(), if (on) 0 else Tema.BOTAO_BORDA)
                    isClickable = true
                    setOnClickListener { aoMudar(mascara xor (1 shl i)) }
                    efeitoToque()
                    contentDescription = nomes[i] + ": " + if (on) "ligado" else "desligado"
                }, LinearLayout.LayoutParams(52.d(), 52.d()).apply { leftMargin = 6.d(); rightMargin = 6.d() })
            }
        }
    }

    private fun chip(rotulo: String, ligado: Boolean, altura: Int = 46, aoSegurar: (() -> Unit)? = null, aoClicar: () -> Unit): View = TextView(c).apply {
        text = rotulo
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 19.d().toFloat())
        typeface = FONTE_MEDIA
        gravity = Gravity.CENTER
        setPadding(18.d(), 0, 18.d(), 0)
        background = fundoArredondado(if (ligado) destaque else Color.parseColor("#14FFFFFF"), (altura / 2).d(), if (ligado) 0 else Tema.BOTAO_BORDA)
        isClickable = true
        setOnClickListener { aoClicar() }
        if (aoSegurar != null) setOnLongClickListener { aoSegurar(); true }
        efeitoToque()
        layoutParams = LinearLayout.LayoutParams(-2, altura.d()).apply { leftMargin = 5.d(); rightMargin = 5.d() }
    }

    private fun vazio(msg: String): View = FrameLayout(c).apply {
        addView(texto(c, msg, 22, Tema.TEXTO3).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) }, LayoutParams(-2, -2, Gravity.CENTER))
    }

    private fun botoesRodape(vararg b: View): View = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        b.forEachIndexed { i, v ->
            if (i == 1) addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (i > 1) leftMargin = 12.d() })
        }
    }

    // ------------------------------------------------------------------ ALARMES

    private fun montarListaAlarmes() {
        lista.removeAllViews()
        if (alarmes.lista.isEmpty()) { lista.addView(vazio("Nenhum alarme ainda.\nToque em \"+ Novo\"."), LinearLayout.LayoutParams(-1, 380.d())); return }
        for (a in alarmes.lista.sortedWith(compareBy({ it.hora }, { it.minuto }))) {
            val sel = a.id == alarmeSel
            val interruptor = Interruptor(c).apply { ligado = a.ligado; aoMudar = { v -> a.ligado = v; alarmes.salvar(); atualizar() } }
            val info = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(texto(c, a.nome.ifBlank { "Alarme" }, 22, Tema.TEXTO, true))
                addView(texto(c, alarmes.descricaoDias(a), 17, Tema.TEXTO3), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 2.d() })
            }
            val linha = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16.d(), 0, 16.d(), 0)
                background = fundoArredondado(if (sel) Color.parseColor("#1AFFFFFF") else Color.TRANSPARENT, 20.d())
                isClickable = true
                setOnClickListener { alarmeSel = a.id; atualizar() }
                alpha = if (a.ligado) 1f else .55f
                addView(texto(c, "%02d:%02d".format(a.hora, a.minuto), 50, Tema.TEXTO).apply { typeface = FONTE_LEVE; fontFeatureSettings = "tnum" }, LinearLayout.LayoutParams(146.d(), -2))
                addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                addView(interruptor)
            }
            lista.addView(linha, LinearLayout.LayoutParams(-1, 86.d()))
        }
    }

    private fun montarEditorAlarme() {
        editor.removeAllViews()
        val a = alarmes.porId(alarmeSel)
        if (a == null) { editor.addView(vazio("Crie um alarme para editar"), LinearLayout.LayoutParams(-1, -1)); return }
        editor.addView(rotuloEditor("Editar alarme", alarmes.descricaoDias(a)))
        editor.addView(relogio(a.hora, a.minuto, 84, 96, 52) { h, m -> a.hora = h; a.minuto = m; salvarAlarme() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14.d() })
        editor.addView(bolinhasDias(a.dias) { m -> a.dias = m; salvarAlarme() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16.d() })
        // o nome é opcional: toque para marcar e toque de novo para desmarcar; o "+" abre a digitação de um nome seu (até 3 ficam guardados)
        // segurar um nome (os de fábrica também) abre "Editar / Excluir"
        val todosNomes = alarmes.nomes
        val linha1 = LinearLayout(c).apply { gravity = Gravity.CENTER }
        val linha2 = LinearLayout(c).apply { gravity = Gravity.CENTER }
        todosNomes.forEachIndexed { i, n ->
            (if (i < 4) linha1 else linha2).addView(chip(n, a.nome == n, 46, { opcoesNome(n) }) { a.nome = if (a.nome == n) "" else n; salvarAlarme() })
        }
        if (todosNomes.size < AlarmeControle.MAX_NOMES) linha2.addView(chip("+", false) {
            abrirTexto(R.drawable.ic_alarme, "Nome do alarme", "Adicionar", "", "Escreva o nome do alarme.") { t -> if (alarmes.adicionarNome(t)) a.nome = t.trim(); salvarAlarme() }
        })
        editor.addView(linha1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18.d() })
        if (linha2.childCount > 0) editor.addView(linha2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.d() })
        editor.addView(View(c), LinearLayout.LayoutParams(-1, 0, 1f))
        editor.addView(botoesRodape(
            botao(c, "Excluir", Tema.BOTAO, Tema.VERMELHO_SUAVE) { alarmes.excluir(a.id); atualizar() },
            botao(c, "Testar toque", Tema.BOTAO) { aoTestar(a) }
        ))
    }

    private fun salvarAlarme() { alarmes.salvar(); atualizar() }

    /** Janelinha de um nome de alarme (ao segurar): Editar ou Excluir. */
    private fun opcoesNome(nome: String) {
        val conteudo = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        conteudo.addView(texto(c, nome, 30, Tema.TEXTO, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        conteudo.addView(texto(c, "O que você quer fazer com esse nome?", 20, Tema.TEXTO2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.d(); bottomMargin = 24.d() })
        val botoes = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        botoes.addView(botao(c, "Excluir", Tema.BOTAO, Tema.VERMELHO_SUAVE) { modal.fechar(); alarmes.excluirNome(nome); atualizar() })
        botoes.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        botoes.addView(botao(c, "Cancelar", Tema.BOTAO) { modal.fechar() })
        botoes.addView(botao(c, "Editar", Cfg.AZUL) {
            modal.fechar()
            abrirTexto(R.drawable.ic_alarme, "Nome do alarme", "Salvar", nome, "Escreva o nome do alarme.") { t -> alarmes.renomearNome(nome, t); atualizar() }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12.d() })
        conteudo.addView(botoes, LinearLayout.LayoutParams(-1, -2))
        modal.mostrar(620, conteudo)
    }

    // ------------------------------------------------------------------ LEMBRETES

    private fun linhaLembrete(l: Lembrete): View {
        val sel = l.id == lembreteSel
        val circulo = CirculoMarcar(c).apply { marcado = l.concluido; isClickable = true; setOnClickListener { lembretes.alternarConcluido(l); atualizar() } }
        val textoL = texto(c, l.texto, 24, Tema.TEXTO, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; if (l.concluido) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG }
        val info = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(textoL)
            addView(texto(c, LembreteControle.descricao(l), 17, Tema.TEXTO3), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 3.d() })
        }
        return LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.d(), 0, 16.d(), 0)
            background = fundoArredondado(if (sel) Color.parseColor("#1AFFFFFF") else Color.TRANSPARENT, 20.d())
            isClickable = true
            setOnClickListener { lembreteSel = l.id; atualizar() }
            alpha = if (l.concluido) .55f else 1f
            addView(circulo, LinearLayout.LayoutParams(40.d(), 40.d()))
            addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 16.d() })
        }
    }

    private fun montarListaLembretes() {
        lista.removeAllViews()
        val ativos = lembretes.todos; val feitos = lembretes.concluidos
        if (ativos.isEmpty() && feitos.isEmpty()) { lista.addView(vazio("Nenhum lembrete.\nToque em \"+ Novo\" ou peça pela voz."), LinearLayout.LayoutParams(-1, 380.d())); return }
        ativos.forEach { lista.addView(linhaLembrete(it), LinearLayout.LayoutParams(-1, 86.d())) }
        if (feitos.isNotEmpty()) {
            lista.addView(rotuloSecao(c, "Concluídos"))
            feitos.forEach { lista.addView(linhaLembrete(it), LinearLayout.LayoutParams(-1, 86.d())) }
        }
    }

    private fun montarEditorLembrete() {
        editor.removeAllViews()
        val l = lembretes.porId(lembreteSel)
        if (l == null) { editor.addView(vazio("Crie um lembrete para editar"), LinearLayout.LayoutParams(-1, -1)); return }
        editor.gravity = Gravity.CENTER_HORIZONTAL
        // caixa com o texto e o lápis: abre a tela de texto
        val caixa = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = fundoArredondado(Color.parseColor("#0FFFFFFF"), 22.d(), Tema.BOTAO_BORDA)
            setPadding(24.d(), 0, 20.d(), 0)
            isClickable = true
            setOnClickListener { abrirTexto(R.drawable.ic_sino, "Escreva o que lembrar", "Salvar", l.texto, "Escreva o que lembrar.") { t -> l.texto = t; lembretes.salvar(); atualizar() } }
            efeitoToque()
            addView(texto(c, l.texto, 26, Tema.TEXTO, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(icone(c, R.drawable.ic_lapis, 28, Tema.TEXTO2), LinearLayout.LayoutParams(28.d(), 28.d()).apply { leftMargin = 14.d() })
        }
        editor.addView(caixa, LinearLayout.LayoutParams(-1, 84.d()))
        editor.addView(relogio(l.hora, l.minuto, 72, 90, 42) { h, m -> lembretes.definirHorario(l, h, m); atualizar() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 14.d() })
        editor.addView(bolinhasDias(l.dias) { m -> lembretes.definirDias(l, m); atualizar() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 14.d() })
        // atalhos numa linha: Hoje e Amanhã (uma vez); Todo dia, Dias úteis e Fim de semana marcam os dias
        val hoje = Calendar.getInstance()
        val diaDoAlvo = Calendar.getInstance().apply { timeInMillis = l.quandoMs }
        val ehHoje = l.dias == 0 && diaDoAlvo.get(Calendar.DAY_OF_YEAR) == hoje.get(Calendar.DAY_OF_YEAR) && diaDoAlvo.get(Calendar.YEAR) == hoje.get(Calendar.YEAR)
        val ehAmanha = l.dias == 0 && !ehHoje
        val atalhos = LinearLayout(c).apply { gravity = Gravity.CENTER }
        atalhos.addView(chip("Hoje", ehHoje, 44) { lembretes.definirDia(l, 0); atualizar() })
        atalhos.addView(chip("Amanhã", ehAmanha, 44) { lembretes.definirDia(l, 1); atualizar() })
        atalhos.addView(chip("Todo dia", l.dias == LembreteControle.TODOS, 44) { lembretes.definirDias(l, LembreteControle.TODOS); atualizar() })
        atalhos.addView(chip("Dias úteis", l.dias == LembreteControle.DIAS_UTEIS, 44) { lembretes.definirDias(l, LembreteControle.DIAS_UTEIS); atualizar() })
        atalhos.addView(chip("FDS", l.dias == LembreteControle.FIM_DE_SEMANA, 44) { lembretes.definirDias(l, LembreteControle.FIM_DE_SEMANA); atualizar() })
        editor.addView(atalhos, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 16.d() })
        editor.addView(View(c), LinearLayout.LayoutParams(-1, 0, 1f))
        editor.addView(botoesRodape(
            botao(c, "Excluir", Tema.BOTAO, Tema.VERMELHO_SUAVE) { lembretes.excluir(l.id); atualizar() },
            botao(c, if (l.concluido) "Reabrir" else "Concluído", Cfg.AZUL) { lembretes.alternarConcluido(l); atualizar() }
        ), LinearLayout.LayoutParams(-1, -2))
    }

    // ------------------------------------------------------------------ LISTAS

    private fun resumo(nome: String): String {
        val it = listas.itens(nome)
        val pend = it.count { !it.marcado }
        return when {
            it.isEmpty() -> "Lista vazia"
            pend == 0 -> "Tudo finalizado"
            else -> "$pend ${if (pend == 1) "item pendente" else "itens pendentes"} · ${it.size} no total"
        }
    }

    private fun montarListaListas() {
        lista.removeAllViews()
        if (listas.nomes.isEmpty()) { lista.addView(vazio("Nenhuma lista.\nToque em \"+ Novo\" ou diga\n\"adiciona leite na lista de compras\"."), LinearLayout.LayoutParams(-1, 380.d())); return }
        for (nome in listas.nomes) {
            val sel = nome == listaSel
            val quadro = FrameLayout(c).apply {
                background = fundoArredondado(Color.parseColor("#12FFFFFF"), 16.d())
                addView(icone(c, R.drawable.ic_lista, 26, Tema.TEXTO), LayoutParams(26.d(), 26.d(), Gravity.CENTER))
            }
            val info = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(texto(c, listas.nomeBonito(nome), 24, Tema.TEXTO, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(texto(c, resumo(nome), 17, Tema.TEXTO3), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 3.d() })
            }
            lista.addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16.d(), 0, 16.d(), 0)
                background = fundoArredondado(if (sel) Color.parseColor("#1AFFFFFF") else Color.TRANSPARENT, 20.d())
                isClickable = true
                setOnClickListener { listaSel = nome; atualizar() }
                addView(quadro, LinearLayout.LayoutParams(52.d(), 52.d()))
                addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 16.d() })
            }, LinearLayout.LayoutParams(-1, 86.d()))
        }
    }

    private fun linhaItem(nome: String, item: ItemLista): View {
        val circulo = CirculoMarcar(c).apply { marcado = item.marcado }
        val t = texto(c, item.texto, 23, Tema.TEXTO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; if (item.marcado) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG }
        val x = FrameLayout(c).apply {
            isClickable = true
            contentDescription = "Remover ${item.texto}"
            setOnClickListener { listas.remover(nome, item); atualizar() }
            addView(icone(c, R.drawable.ic_fechar, 22, Tema.TEXTO3), LayoutParams(22.d(), 22.d(), Gravity.CENTER))
        }
        return LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.d(), 0, 4.d(), 0)
            isClickable = true
            alpha = if (item.marcado) .55f else 1f
            setOnClickListener { listas.alternar(item); atualizar() }
            addView(circulo, LinearLayout.LayoutParams(40.d(), 40.d()))
            addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 16.d() })
            addView(x, LinearLayout.LayoutParams(48.d(), 48.d()))
        }
    }

    private fun montarEditorLista() {
        editor.removeAllViews()
        if (listaSel.isEmpty()) { editor.addView(vazio("Crie uma lista para começar"), LinearLayout.LayoutParams(-1, -1)); return }
        val nome = listaSel
        val topo = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(texto(c, listas.nomeBonito(nome), 30, Tema.TEXTO, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(botao(c, "+ Adicionar item", Cfg.AZUL) {
                abrirTexto(R.drawable.ic_lista, "Novo item", "Adicionar", "", "Escreva o item.") { t -> listas.adicionar(nome, t); atualizar() }
            })
        }
        editor.addView(topo, LinearLayout.LayoutParams(-1, -2))
        val corpo = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val itens = listas.itens(nome)
        val pend = itens.filter { !it.marcado }; val feitos = itens.filter { it.marcado }
        if (itens.isEmpty()) corpo.addView(vazio("A lista está vazia.\nToque em \"+ Adicionar item\"."), LinearLayout.LayoutParams(-1, 280.d()))
        pend.forEach { corpo.addView(linhaItem(nome, it), LinearLayout.LayoutParams(-1, 62.d())) }
        if (feitos.isNotEmpty()) {
            corpo.addView(rotuloSecao(c, "Finalizados").apply { setPadding(8.d(), 14.d(), 0, 6.d()) })
            feitos.forEach { corpo.addView(linhaItem(nome, it), LinearLayout.LayoutParams(-1, 62.d())) }
        }
        editor.addView(RolagemFina(c).apply { addView(corpo) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 12.d() })
        editor.addView(botoesRodape(
            botao(c, "Excluir lista", Tema.BOTAO, Tema.VERMELHO_SUAVE) { listas.excluir(nome); atualizar() },
            botao(c, "Limpar marcados", Tema.BOTAO) { listas.limparMarcados(nome); atualizar() }
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.d() })
    }
}
