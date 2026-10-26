package br.com.lmsoftworks.caixa2

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast

/**
 * Tela principal da caixa (launcher). Fica preta até o Android girar para a horizontal,
 * depois a interface aparece com um fade.
 */
class InicioActivity : Activity() {

    private lateinit var conteudo: FrameLayout
    private lateinit var fundo: FundoView
    private lateinit var barra: BarraTopo
    private lateinit var painelVolume: PainelVolume
    private lateinit var modalEnergia: ModalEnergia
    private lateinit var cobertura: CoberturaEnergia
    private lateinit var inicio: TelaInicio
    private lateinit var telaWifi: TelaWifi
    private lateinit var telaBluetooth: TelaBluetooth
    private lateinit var bluetooth: BluetoothControle
    private lateinit var musica: MusicaControle
    private lateinit var led: LedControle
    private lateinit var telaLed: TelaLed
    private lateinit var telaMusica: TelaMusica
    private lateinit var telaMidia: TelaMidia
    private lateinit var telaVideo: TelaVideo
    private lateinit var playerMidia: PlayerMidia
    private lateinit var modalAtualizacao: ModalAtualizacao
    private lateinit var faixaAssistente: br.com.lmsoftworks.caixa2.voz.FaixaAssistente
    private lateinit var assistente: br.com.lmsoftworks.caixa2.voz.Assistente
    private lateinit var alarmes: AlarmeControle
    private lateinit var telaAlarmes: TelaAlarmes
    private lateinit var telaAlarmeTocando: TelaAlarmeTocando
    private var alarmeTocando: Alarme? = null
    private var alarmeEhTeste = false
    private lateinit var telaOciosa: TelaOciosa
    private var ocioso = false
    private var engolindo = false                  // o toque que acordou a tela não aciona nada embaixo
    private var ultimoToque = android.os.SystemClock.uptimeMillis()
    private var capaAnalisada: android.graphics.Bitmap? = null
    private var corCapa: Pair<Int, Int>? = null
    private var girando = false
    private var modoCubo = false
    private var grauAtual = 0f
    private var tArraste = 0L
    private var deCubo: View? = null
    private var paraCubo: View? = null
    private var volumeAntesDoAlarme = -1
    private val pararAlarmeSozinho = Runnable { pararAlarme(false) }
    private var tocandoAntes = false
    private lateinit var wifi: WifiControle
    private lateinit var gesto: GestoVoltar
    private var destinoCubo = "inicio"
    private var arrastando = false
    private var capturado = false
    private var x0 = 0f
    private var y0 = 0f
    private var atual = "inicio"
    private var apareceu = false
    private val relogio = Handler(Looper.getMainLooper())

    private val tique = object : Runnable {
        override fun run() {
            inicio.atualizar()
            if (atual == "musica") telaMusica.atualizar()
            barra.atualizarHora()
            alarmes.verificar()?.let { tocarAlarme(it) }
            if (led.modo == "ambilight" && !telaVideo.aberto) atualizarLedDaMusica()
            inicio.definirProximoAlarme(alarmes.proximoTexto())
            if (!ocioso) {
                if (android.os.SystemClock.uptimeMillis() - ultimoToque > IDLE_MS && !ocupado()) entrarOcioso()
            } else {
                telaOciosa.atualizar()
                if (tocandoMusica()) sairOcioso()      // alguém começou a tocar: acorda
            }
            relogio.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        Cfg.iniciar(this)

        wifi = WifiControle(this) { aoMudarWifi() }
        fundo = FundoView(this)
        bluetooth = BluetoothControle(this) { aoMudarBluetooth() }
        musica = MusicaControle(this) { aoMudarMusica() }
        led = LedControle(this) { aoMudarLed() }
        painelVolume = PainelVolume(this) { p, mudo -> barra.definirVolume(p, mudo) }
        cobertura = CoberturaEnergia(this)
        modalEnergia = ModalEnergia(this) { desligar -> sequenciaEnergia(desligar) }
        painelVolume.aoNoLimite = { Som.tocar(this, R.raw.som_volume_max) }
        painelVolume.aoMostrar = { sairOcioso() }
        barra = BarraTopo(this, { ir("wifi") }, { ir("bluetooth") }, { ir("led") }, { painelVolume.alternar() }, { painelVolume.fechar(); modalEnergia.abrir() })
        barra.colocarPilulaVolume(painelVolume.pilula)
        gesto = GestoVoltar(this)
        inicio = TelaInicio(this) { abrir(it) }
        telaWifi = TelaWifi(this, wifi)
        telaWifi.visibility = View.GONE
        telaBluetooth = TelaBluetooth(this, bluetooth)
        telaBluetooth.visibility = View.GONE
        telaMusica = TelaMusica(this, musica, bluetooth, { ir("bluetooth") }, { ir("midia") })
        telaMusica.visibility = View.GONE
        playerMidia = PlayerMidia(this) { aoMudarPlayer() }
        musica.local = playerMidia
        telaMidia = TelaMidia(this, { lista, i -> abrirVideo(lista, i) }, { lista, i -> tocarMusicaDoYoutube(lista, i) }, { e -> Atualizador.aoFalhar(this, e) })
        telaMidia.visibility = View.GONE
        telaVideo = TelaVideo(this, playerMidia, { painelVolume.porcento }, { p -> painelVolume.definirPorcento(p) }) { fecharVideoAnimado() }
        faixaAssistente = br.com.lmsoftworks.caixa2.voz.FaixaAssistente(this, { assistente.nivelMicrofone }, { conteudo })
        faixaAssistente.aoToque = { assistente.cancelar() }
        assistente = br.com.lmsoftworks.caixa2.voz.Assistente(this, faixaAssistente, acoesAssistente)
        telaMidia.aoMicrofone = {
            assistente.ouvirParaBusca({ telaMidia.ouvindoVoz(true) }, { parcial -> telaMidia.mostrarParcial(parcial) }, { texto ->
                telaMidia.ouvindoVoz(false)
                if (texto != null) telaMidia.pesquisar(texto)
            })
        }
        telaLed = TelaLed(this, led)
        telaLed.visibility = View.GONE
        alarmes = AlarmeControle(this) { aoMudarAlarmes() }
        telaAlarmes = TelaAlarmes(this, alarmes) { a -> tocarAlarme(a, teste = true) }
        telaAlarmes.visibility = View.GONE
        telaAlarmeTocando = TelaAlarmeTocando(this, { pararAlarme(true) }, { pararAlarme(false) })
        telaOciosa = TelaOciosa(this)
        modalAtualizacao = ModalAtualizacao(this)
        Atualizador.aoHaAtualizacao = { v -> if (alarmeTocando == null) modalAtualizacao.mostrar(v) }

        conteudo = FrameLayout(this).apply {
            alpha = 0f
            addView(fundo, FrameLayout.LayoutParams(-1, -1))
            addView(inicio, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64.d() })
            addView(telaWifi, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64.d() })
            addView(telaBluetooth, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64.d() })
            addView(telaMusica, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64.d() })
            addView(telaMidia, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64.d() })
            addView(telaLed, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64.d() })
            addView(telaAlarmes, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64.d() })
            addView(painelVolume, FrameLayout.LayoutParams(-1, -1))
            addView(barra, FrameLayout.LayoutParams(-1, 84.d()))
            addView(gesto, FrameLayout.LayoutParams(-1, -1))
        }

        // Tudo preto até a tela estar na horizontal; no boot o Android ainda pode mostrar o
        // app em pé por um instante antes de girar.
        val raiz = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(conteudo, FrameLayout.LayoutParams(-1, -1))
            addView(telaVideo, FrameLayout.LayoutParams(-1, -1))
            addView(faixaAssistente, FrameLayout.LayoutParams(-1, -1))
            addView(telaAlarmeTocando, FrameLayout.LayoutParams(-1, -1))
            addView(telaOciosa, FrameLayout.LayoutParams(-1, -1))
            addView(modalEnergia, FrameLayout.LayoutParams(-1, -1))
            addView(modalAtualizacao, FrameLayout.LayoutParams(-1, -1))
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (v.width > v.height && !apareceu) {
                    apareceu = true
                    fundo.iniciar()
                    relogio.postDelayed({ tocarSomDeInicio() }, 1500)
                    // espera a animação de giro do Android terminar antes de mostrar a interface
                    conteudo.animate().alpha(1f)
                        .setStartDelay(Cfg.ATRASO_ENTRADA_MS).setDuration(Cfg.FADE_ENTRADA_MS).start()
                }
            }
        }
        setContentView(raiz)
        aplicarTema("inicio")
        TesteLed.registrar(this, { led.parar() }, { led.iniciar() })
        aoMudarWifi()
        aoMudarBluetooth()
        aoMudarLed()
        // gatilho de teste pelo adb, para ver a tela do alarme tocando sem mexer na tela:
        //   adb shell am broadcast -a br.com.lmsoftworks.caixa2.TESTE_ALARME
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) { tocarAlarme(Alarme(0, 7, 0, 0, "Acordar", true), teste = true) }
        }, android.content.IntentFilter("br.com.lmsoftworks.caixa2.TESTE_ALARME"))
        // abre um menu como se tivesse tocado nele: adb shell am broadcast -a br.com.lmsoftworks.caixa2.TESTE_IR --es para wifi
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) { ir(i.getStringExtra("para") ?: "inicio") }
        }, android.content.IntentFilter("br.com.lmsoftworks.caixa2.TESTE_IR"))
        // mostra a animação do assistente: adb shell am broadcast -a br.com.lmsoftworks.caixa2.TESTE_ASSISTENTE --es estado ouvindo|pensando|falando|fechar
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                when (i.getStringExtra("estado")) {
                    "ouvindo" -> faixaAssistente.ouvindo()
                    "pensando" -> faixaAssistente.pensando("toca anitta")
                    "falando" -> faixaAssistente.respondendo("São 23 horas e 58 minutos.")
                    else -> faixaAssistente.esconder()
                }
            }
        }, android.content.IntentFilter("br.com.lmsoftworks.caixa2.TESTE_ASSISTENTE"))
        // pula para 35 s antes do fim da música atual (testa a emenda): adb shell am broadcast -a br.com.lmsoftworks.caixa2.TESTE_FIM
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                relogio.post { playerMidia.buscar(playerMidia.duracaoMs - 35_000) }
            }
        }, android.content.IntentFilter("br.com.lmsoftworks.caixa2.TESTE_FIM"))
        // toca o primeiro resultado de uma busca: adb shell am broadcast -a br.com.lmsoftworks.caixa2.TESTE_MIDIA --es tipo video --es q "texto"
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                val musicaTeste = i.getStringExtra("tipo") == "musica"
                val q = i.getStringExtra("q") ?: "musica"
                Thread {
                    try {
                        var lista = YouTube.buscar(q, musicaTeste)
                        // q2 = uma segunda busca cujo 1º resultado entra na fila logo depois (para testar a emenda com faixas diferentes)
                        i.getStringExtra("q2")?.let { q2 -> lista = listOf(lista[0]) + YouTube.buscar(q2, musicaTeste && i.getStringExtra("q2video") == null).take(1) }
                        relogio.post { if (musicaTeste) tocarMusicaDoYoutube(lista, 0) else abrirVideo(lista, 0) }
                    } catch (e: Exception) { android.util.Log.w("Midia", "teste falhou", e) }
                }.start()
            }
        }, android.content.IntentFilter("br.com.lmsoftworks.caixa2.TESTE_MIDIA"))
        // idem para o cubo: adb shell am broadcast -a br.com.lmsoftworks.caixa2.TESTE_CUBO --ei grau 40 --ei dir -1   (grau -1 = limpar)
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                val grau = i.getIntExtra("grau", -1); val dir = i.getIntExtra("dir", -1)
                val deV = tela(i.getStringExtra("de") ?: atual); val paraV = tela(i.getStringExtra("para") ?: "wifi")
                if (grau < 0) {
                    for (v in listOf("inicio", "wifi", "bluetooth", "musica", "midia", "led", "alarmes")) { val t = tela(v); Cubo.limpar(t); t.visibility = if (v == atual) View.VISIBLE else View.GONE }
                    girando = false; return
                }
                girando = true
                Cubo.preparar(deV, paraV); Cubo.aplicar(deV, paraV, grau.toFloat(), dir, metade())
            }
        }, android.content.IntentFilter("br.com.lmsoftworks.caixa2.TESTE_CUBO"))
    }

    override fun onStart() {
        super.onStart()
        alarmes.reagendar()
        aoMudarAlarmes()
        Quiosque.aplicar(this)
        wifi.iniciar()
        bluetooth.iniciar()
        musica.iniciar()
        painelVolume.iniciar()
        led.iniciar()
        relogio.post(temaLed)
        if (apareceu) fundo.iniciar()
        relogio.post(tique)
        assistente.iniciar()
        Atualizador.conferirAoAbrir(this)
        Atualizador.pendente?.let { if (!modalAtualizacao.aberto) modalAtualizacao.mostrar(it) }
    }

    override fun onStop() {
        super.onStop()
        assistente.parar()
        fundo.parar()
        relogio.removeCallbacksAndMessages(null)
        wifi.parar()
        bluetooth.parar()
        musica.desligar()
        painelVolume.parar()
        led.parar()
    }

    private fun aoMudarWifi() {
        barra.definirWifi(wifi.ligado && wifi.conectadoSsid != null)
        if (atual == "wifi") telaWifi.atualizar()
    }

    /** Enquanto a tela de LED está aberta, o fundo e o destaque acompanham a cor da fita. */
    private val temaLed = object : Runnable {
        override fun run() {
            if (atual == "led") aplicarTemaLed()
            relogio.postDelayed(this, 120)
        }
    }

    private fun aplicarTemaLed() {
        val t = android.os.SystemClock.uptimeMillis() / 1000f
        val c1 = led.corDestaque(t)
        val hsv = FloatArray(3)
        Color.colorToHSV(c1, hsv)
        val c2 = Color.HSVToColor(floatArrayOf((hsv[0] + 45f) % 360f, hsv[1], hsv[2] * 0.55f))
        fundo.tema(c1, c2)
        Cfg.AZUL = Cfg.COR_FIXA   // o fundo acompanha a fita, mas os botões ficam na cor padrão
    }

    private fun aoMudarLed() {
        barra.definirLed(led.online, !led.online && led.erro == null)
        if (atual == "led") telaLed.atualizar()
    }

    private fun aoMudarMusica() {
        if (atual == "musica") { telaMusica.atualizar(); aplicarTema("musica"); atualizarLedDaMusica() }   // a cor da capa pode chegar depois
        // quando alguém começa a tocar pelo Bluetooth, abre sozinho a tela Tocando agora
        if (musica.tocando && !tocandoAntes && atual == "inicio") ir("musica")
        tocandoAntes = musica.tocando
    }

    private fun aoMudarBluetooth() {
        barra.definirBluetooth(bluetooth.ligado && bluetooth.nomeConectado() != null)
        if (atual == "bluetooth") telaBluetooth.atualizar()
    }

    private fun tela(nome: String): View = when (nome) {
        "wifi" -> telaWifi
        "bluetooth" -> telaBluetooth
        "musica" -> telaMusica
        "midia" -> telaMidia
        "led" -> telaLed
        "alarmes" -> telaAlarmes
        else -> inicio
    }

    private fun abrir(id: String) {
        if (id == "midia") ir(if (musica.temMusica) "musica" else "midia")      // tocando algo: Tocando agora; senão, a busca
        else if (id == "wifi" || id == "bluetooth" || id == "musica" || id == "led" || id == "alarmes") ir(id)
        else Toast.makeText(this, "Em breve", Toast.LENGTH_SHORT).show()
    }

    /** Tela de música: o fundo e o destaque seguem a cor predominante da capa; sem capa (ou capa cinza), o azul padrão. */
    private fun temaMusica(): Triple<String, String, String> {
        val capa = musica.capa
        if (capa !== capaAnalisada) {
            capaAnalisada = capa
            corCapa = capa?.let { try { CorCapa.analisar(it) } catch (e: Exception) { null } }
        }
        // sem capa (música trocando) ou capa sem cor: fundo cinza, e a fita apaga enquanto isso
        val c = corCapa ?: return Triple("#5B6270", "#232733", "#8A93A5")
        fun hex(i: Int) = String.format("#%06X", 0xFFFFFF and i)
        return Triple(hex(c.first), hex(c.second), hex(c.first))
    }

    /** Cores do fundo e cor de destaque de cada menu. */
    private fun aplicarTema(menu: String) {
        val (a, b, destaque) = when (menu) {
            "wifi" -> Triple("#12C7D6", "#2F7BFF", "#12C7D6")
            "bluetooth" -> Triple("#3D8BFF", "#1A3FA8", "#3D8BFF")
            "musica" -> temaMusica()
            "midia" -> Triple("#2F7BFF", "#FF3B4E", "#2F7BFF")
            "led" -> Triple("#2F7BFF", "#8A5CFF", "#2F7BFF")
            "alarmes" -> Triple("#FF4FA3", "#7A3CFF", "#FF4FA3")
            else -> Triple("#0B3B9C", "#06214F", "#2F7BFF")
        }
        fundo.tema(Color.parseColor(a), Color.parseColor(b))
        Cfg.AZUL = Color.parseColor(destaque)
    }

    /** Parte leve da troca de menu: só o que precisa estar certo já no primeiro quadro do giro. */
    private fun mudarEstado(destino: String) {
        atual = destino
        aplicarTema(destino)
        gesto.ativo = destino != "inicio"
        barra.mostrarHora(destino != "inicio")
        atualizarLedDaMusica()
    }

    /** Ambilight (modo da fita): na tela de música ela acompanha a cor da capa; no vídeo, a cor da imagem (ver [tique]). Fora disso, volta à cor escolhida. */
    private fun atualizarLedDaMusica() {
        var cor: Int? = null
        if (atual == "musica" && musica.temMusica) {
            val tema = temaMusica()
            cor = if (corCapa == null) Color.BLACK else Color.parseColor(tema.first)      // preto = fita apagada
        }
        if (!telaVideo.aberto) led.definirCorAmbiente(cor)
    }

    /**
     * Parte pesada da troca de menu (buscar redes, mudar a visibilidade do Bluetooth, montar listas). Roda DEPOIS do giro:
     * se rodasse no começo, travava o primeiro quadro da animação, sempre no mesmo ponto.
     */
    private fun aoChegar(destino: String) {
        wifi.procurar = destino == "wifi"
        if (destino == "wifi") { telaWifi.reiniciar(); wifi.varrer() }
        if (destino == "bluetooth") { telaBluetooth.reiniciar(); bluetooth.definirVisivel(true) }
        else bluetooth.definirVisivel(false)
        if (destino == "led") telaLed.atualizar()
        if (destino == "alarmes") telaAlarmes.atualizar()
        if (destino == "midia") telaMidia.aoMostrar()
    }

    private fun metade(): Float = conteudo.width / 2f

    /** Gira o cubo de [desde] até [ate] graus (640 ms no giro inteiro; nos parciais, proporcional, no mínimo 240 ms). */
    private fun animarCubo(de: View, para: View, dir: Int, desde: Float, ate: Float, aoFim: () -> Unit) {
        val dur = if (desde == 0f && ate == 90f) 640L else maxOf(240L, (640f * Math.abs(ate - desde) / 90f).toLong())
        // giro inteiro (abrir menu, botão voltar): começa e termina devagar (ease in-out cúbico).
        // Terminar um arrasto: o dedo já vinha rápido, então a caixa continua na mesma velocidade e só freia (sem "paradinha").
        val interpolador = if (desde == 0f && ate == 90f) android.view.animation.PathInterpolator(0.65f, 0f, 0.35f, 1f)
                           else android.view.animation.PathInterpolator(0f, 0f, 0.2f, 1f)
        var t0 = -1L
        fundo.parar()      // o fundo animado fica parado durante o giro (menos redesenho); volta no fim
        // laço próprio (um quadro por vez), sem depender da escala de animação do sistema
        val passo = object : Runnable {
            override fun run() {
                // o relógio do giro começa no PRIMEIRO quadro: a criação das camadas não "come" o começo da animação
                if (t0 < 0) t0 = android.os.SystemClock.uptimeMillis()
                val x = ((android.os.SystemClock.uptimeMillis() - t0).toFloat() / dur).coerceIn(0f, 1f)
                Cubo.aplicar(de, para, desde + (ate - desde) * interpolador.getInterpolation(x), dir, metade())
                if (x < 1f) conteudo.postOnAnimation(this) else aoFim()
            }
        }
        conteudo.postOnAnimation(passo)
    }

    private fun terminarGiro(de: View, para: View, ficouNoDestino: Boolean) {
        if (ficouNoDestino) de.visibility = View.GONE else para.visibility = View.GONE
        Cubo.limpar(de); Cubo.limpar(para)
        girando = false
        if (apareceu && !ocioso) fundo.iniciar()
        if (ficouNoDestino) aoChegar(atual)      // só agora o trabalho pesado, com a tela já parada
    }

    private fun ir(destino: String) {
        if (destino == atual || girando) return
        val de: View = tela(atual)
        val para: View = tela(destino)
        val dir = if (destino == "inicio" || destino == destinoVoltar()) 1 else -1      // voltar gira para a direita; entrar, para o outro lado
        mudarEstado(destino)
        if (!apareceu) { de.visibility = View.GONE; para.visibility = View.VISIBLE; aoChegar(destino); return }
        girando = true
        Cubo.preparar(de, para)
        Cubo.aplicar(de, para, 0f, dir, metade())
        animarCubo(de, para, dir, 0f, 90f) { terminarGiro(de, para, true) }
    }

    /** Para onde o "voltar" leva: música do YouTube volta à busca (Mídia); Mídia e os demais menus voltam à tela inicial. */
    private fun destinoVoltar() = if (atual == "musica" && musica.emLocal) "midia" else "inicio"

    private fun emDetalheAtual() = (atual == "wifi" && telaWifi.emDetalhe) || (atual == "bluetooth" && telaBluetooth.emDetalhe)

    // ------------------------------------------------------------------ assistente de voz

    /** O que o assistente pode mexer no app. Tudo roda na thread principal. */
    private val acoesAssistente = object : br.com.lmsoftworks.caixa2.voz.AcoesAssistente {
        override fun acordarTela() { sairOcioso() }

        override fun abafar(sim: Boolean) { playerMidia.volume(if (sim) 0.2f else 1f) }

        override fun videoAberto() = telaVideo.aberto

        override fun tocar(lista: List<Resultado>, soAudio: Boolean) {
            if (soAudio) { if (telaVideo.aberto) fecharVideo(); tocarMusicaDoYoutube(lista, 0) } else abrirVideo(lista, 0)
        }

        override fun executar(comando: br.com.lmsoftworks.caixa2.voz.ComandoVoz, textoOuvido: String): String? =
            executarComando(comando)
    }

    private fun executarComando(c: br.com.lmsoftworks.caixa2.voz.ComandoVoz): String? {
        val noVideo = telaVideo.aberto
        return when (c) {
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Pesquisar -> {
                ir("midia"); telaMidia.pesquisar(c.consulta)
                "Pesquisando ${br.com.lmsoftworks.caixa2.voz.InterpretadorVoz.consultaFalada(c.consulta)}"
            }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Pausar -> { if (noVideo) { if (playerMidia.tocando) playerMidia.alternar() } else musica.pausar(); null }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Continuar -> { if (noVideo) { if (!playerMidia.tocando) playerMidia.alternar() } else musica.tocar(); null }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Parar -> {
                if (alarmeTocando != null) pararAlarme(false)
                else if (noVideo) fecharVideoAnimado() else { musica.pausar(); if (musica.emLocal) playerMidia.parar() }
                null
            }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Proxima -> { if (noVideo) playerMidia.proxima() else musica.proxima(); null }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Anterior -> { if (noVideo) playerMidia.anterior() else musica.anterior(); null }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Recomecar -> { if (noVideo) playerMidia.buscar(0) else musica.buscar(0); null }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.PularTempo -> { if (noVideo || musica.emLocal) playerMidia.pular(c.segundos * 1000L); null }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.OQueToca ->
                if (noVideo) playerMidia.item?.titulo?.let { "Está passando $it" } ?: "Não há nada passando."
                else if (musica.temMusica) "Está tocando ${musica.titulo}" + (if (musica.artista.isNotEmpty()) ", de ${musica.artista}" else "") else "Não há nada tocando."
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Repetir ->
                if (musica.emLocal) { playerMidia.repetir(c.ligar); if (c.ligar) "Repetindo esta música" else "Repetição desligada" } else "Só consigo repetir músicas que eu mesma toco."
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Embaralhar -> { if (musica.emLocal) { playerMidia.embaralhar(); "Fila embaralhada" } else null }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Soneca -> { if (alarmeTocando != null) pararAlarme(true); null }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Timer -> { iniciarTimer(c.segundos); "Timer de ${textoDuracao(c.segundos)}" }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.CancelarTimer -> { cancelarTimer(); null }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.TempoTimer ->
                if (timerFim == 0L) "Não há nenhum timer ligado." else "Faltam ${textoDuracao(((timerFim - android.os.SystemClock.uptimeMillis()) / 1000).toInt().coerceAtLeast(1))}"
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.DesligarCaixa -> { sequenciaEnergia(!c.reiniciar); null }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Volume -> aplicarVolumeVoz(c)
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Falar -> c.texto
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Voltar -> { if (noVideo) fecharVideoAnimado() else ir("inicio"); null }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Ir -> { ir(c.destino); null }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Luz -> comandoLuz(c)
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.CriarAlarme -> {
                val a = alarmes.novo(); a.hora = c.hora; a.minuto = c.minuto; a.ligado = true; alarmes.salvar()
                "Alarme criado para %d:%02d".format(c.hora, c.minuto)
            }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Desligar -> "Para desligar a caixa, use o botão de energia."
            else -> null
        }
    }

    /** Pede à busca do YouTube e toca o primeiro resultado (música: só o áudio e tela de música; vídeo: tela cheia). */
    private fun buscarETocar(consulta: String, soAudio: Boolean, modoIndefinido: Boolean) {
        val musicaPedida = if (modoIndefinido) !telaVideo.aberto else soAudio
        Thread {
            val lista = try { YouTube.buscar(consulta, musicaPedida) } catch (e: Exception) { emptyList() }
            relogio.post {
                when {
                    lista.isEmpty() -> faixaAssistente.respondendo("Não encontrei $consulta.")
                    musicaPedida -> { if (telaVideo.aberto) fecharVideo(); tocarMusicaDoYoutube(lista, 0) }
                    else -> abrirVideo(lista, 0)
                }
            }
        }.start()
    }

    // ---- timer por voz ----
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerFim = 0L
    private val timerAcabou = Runnable {
        timerFim = 0L
        sairOcioso()
        br.com.lmsoftworks.caixa2.voz.TonsVoz.confirmacao()
        timerHandler.postDelayed({ br.com.lmsoftworks.caixa2.voz.TonsVoz.confirmacao() }, 900)
        timerHandler.postDelayed({ br.com.lmsoftworks.caixa2.voz.TonsVoz.confirmacao() }, 1800)
        faixaAssistente.respondendo("O timer terminou")
        faixaAssistente.fecharEm(6000)
        br.com.lmsoftworks.caixa2.voz.Fala.falar(this, "O timer terminou") {}
    }

    private fun iniciarTimer(segundos: Int) {
        timerHandler.removeCallbacks(timerAcabou)
        timerFim = android.os.SystemClock.uptimeMillis() + segundos * 1000L
        timerHandler.postDelayed(timerAcabou, segundos * 1000L)
    }

    private fun cancelarTimer() { timerHandler.removeCallbacks(timerAcabou); timerFim = 0L }

    private fun textoDuracao(s: Int): String = when {
        s >= 3600 && s % 3600 == 0 -> if (s / 3600 == 1) "1 hora" else "${s / 3600} horas"
        s >= 60 -> { val m = (s + 30) / 60; if (m == 1) "1 minuto" else "$m minutos" }
        else -> "$s segundos"
    }

    private fun aplicarVolumeVoz(c: br.com.lmsoftworks.caixa2.voz.ComandoVoz.Volume): String? {
        val n = painelVolume.nivel
        return when (c.acao) {
            br.com.lmsoftworks.caixa2.voz.AcaoVolume.AUMENTAR -> { painelVolume.definirNivel(n + 1); "Volume ${minOf(10, n + 1)}" }
            br.com.lmsoftworks.caixa2.voz.AcaoVolume.DIMINUIR -> { painelVolume.definirNivel(n - 1); "Volume ${maxOf(0, n - 1)}" }
            br.com.lmsoftworks.caixa2.voz.AcaoVolume.MAXIMO -> { painelVolume.definirNivel(10); "Volume máximo" }
            br.com.lmsoftworks.caixa2.voz.AcaoVolume.MINIMO -> { painelVolume.definirNivel(1); "Volume mínimo" }
            br.com.lmsoftworks.caixa2.voz.AcaoVolume.NIVEL -> { painelVolume.definirNivel(c.nivel); "Volume ${c.nivel}" }
            br.com.lmsoftworks.caixa2.voz.AcaoVolume.MUDO -> { if (n > 0) painelVolume.alternarMudo(); null }
            br.com.lmsoftworks.caixa2.voz.AcaoVolume.DESMUTAR -> { if (n == 0) painelVolume.alternarMudo(); "Som ligado" }
        }
    }

    private fun comandoLuz(c: br.com.lmsoftworks.caixa2.voz.ComandoVoz.Luz): String = when (c.acao) {
        br.com.lmsoftworks.caixa2.voz.AcaoLuz.LIGAR -> { led.definirLigado(true); "Luzes acesas" }
        br.com.lmsoftworks.caixa2.voz.AcaoLuz.DESLIGAR -> { led.definirLigado(false); "Luzes apagadas" }
        br.com.lmsoftworks.caixa2.voz.AcaoLuz.COR -> { led.definirModo("fixo"); led.definirCor(c.cor); "Pronto" }
        br.com.lmsoftworks.caixa2.voz.AcaoLuz.MODO -> { led.definirLigado(true); led.definirModo(c.modo); "Modo ${led.nomeModo()}" }
        br.com.lmsoftworks.caixa2.voz.AcaoLuz.BRILHO -> { led.definirBrilho(c.brilho); "Brilho ${c.brilho}%" }
        br.com.lmsoftworks.caixa2.voz.AcaoLuz.MAIS_FRACA -> { led.definirLigado(true); led.definirBrilho(led.brilho - 20); "Luz mais fraca" }
        br.com.lmsoftworks.caixa2.voz.AcaoLuz.MAIS_FORTE -> { led.definirLigado(true); led.definirBrilho(led.brilho + 20); "Luz mais forte" }
        br.com.lmsoftworks.caixa2.voz.AcaoLuz.QUENTE -> { led.definirModo("fixo"); led.definirCor(0xFFFFA040.toInt()); led.definirLigado(true); "Luz quente" }
        br.com.lmsoftworks.caixa2.voz.AcaoLuz.LEITURA -> { led.definirModo("fixo"); led.definirCor(0xFFFFD9A0.toInt()); led.definirBrilho(90); led.definirLigado(true); "Luz de leitura" }
    }

    // ------------------------------------------------------------------ mídia (YouTube)

    /** O player do YouTube mudou: atualiza a tela de vídeo, a de música, a fita (ritmo) e o fundo. */
    /** Com o vídeo aberto e o Ambilight ligado, lê a cor da imagem 4 vezes por segundo. */
    private val amostradorVideo = object : Runnable {
        override fun run() {
            if (!telaVideo.aberto) return
            if (led.modo == "ambilight") telaVideo.amostrarCor { led.definirCorAmbiente(it) }
            relogio.postDelayed(this, 250)
        }
    }

    private fun aoMudarPlayer() {
        if (playerMidia.ehVideo && led.modo == "ambilight" && !playerMidia.primeiroQuadro) led.definirCorAmbiente(Color.BLACK)
        led.sessaoExterna = if (playerMidia.tocando) playerMidia.sessaoAudio else -1
        if (playerMidia.ehVideo) telaVideo.atualizar() else musica.sincronizarLocal()
    }

    /** A tela que estava por baixo quando o vídeo abriu (a busca, ou o início quando o pedido veio por voz): é para onde o vídeo volta. */
    private var origemVideo = "midia"

    private fun abrirVideo(lista: List<Resultado>, i: Int) {
        if (!telaVideo.aberto) origemVideo = atual
        telaMidia.fecharTeclado()
        if (musica.tocando) musica.pausar()
        playerMidia.tocarVideo(lista, i)
        telaVideo.abrir()
        relogio.removeCallbacks(amostradorVideo); relogio.post(amostradorVideo)
    }

    private fun fecharVideo() {
        telaVideo.fechar()
        playerMidia.parar()
    }

    /** Fecha o vídeo girando o cubo de volta para a busca (botão X e tecla voltar). */
    private fun fecharVideoAnimado() {
        if (girando || !apareceu) { fecharVideo(); return }
        girando = true
        destinoCubo = ""
        telaVideo.cobrir(true)
        val debaixo = tela(origemVideo)
        Cubo.preparar(telaVideo, debaixo)
        Cubo.aplicar(telaVideo, debaixo, 0f, 1, metade())
        animarCubo(telaVideo, debaixo, 1, 0f, 90f) { terminarGiro(telaVideo, debaixo, true); fecharVideo() }
    }

    private fun tocarMusicaDoYoutube(lista: List<Resultado>, i: Int) {
        telaMidia.fecharTeclado()
        musica.tocarDoYoutube(lista, i)
        ir("musica")
    }

    /** Soltou o dedo: passou de ~28 graus ou foi rápido, termina o giro; senão, volta para a tela de antes. */
    private fun soltarCubo() {
        val de = deCubo ?: return
        val para = paraCubo ?: return
        deCubo = null; paraCubo = null
        val vel = grauAtual / maxOf(1L, android.os.SystemClock.uptimeMillis() - tArraste)
        if (grauAtual > 28f || vel > 0.12f) {
            if (destinoCubo.isNotEmpty()) mudarEstado(destinoCubo)
            animarCubo(de, para, 1, grauAtual, 90f) { terminarGiro(de, para, true); if (destinoCubo.isEmpty()) fecharVideo() }
        } else {
            animarCubo(de, para, 1, grauAtual, 0f) {
                terminarGiro(de, para, false)
                if (destinoCubo.isEmpty()) { tela(origemVideo).visibility = View.VISIBLE; telaVideo.cobrir(false) }      // desistiu: o vídeo continua
            }
        }
    }

    // ------------------------------------------------------------------ modo ocioso

    /** Tem música saindo? Vale o estado que o celular informa OU o próprio Android dizer que o canal de música está ativo
     *  (o estado do celular às vezes não chega, e a tela ociosa não pode entrar com música tocando). */
    private fun tocandoMusica() = musica.tocando || (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).isMusicActive

    private fun ocupado() = tocandoMusica() || telaVideo.aberto || alarmeTocando != null || modalEnergia.aberto || modalAtualizacao.aberto || painelVolume.aberto || girando

    /** 60 s sem toque e sem nada tocando: tela preta só com a hora, fundo animado parado e brilho baixo. */
    private fun entrarOcioso() {
        if (ocioso) return
        ocioso = true
        painelVolume.fechar()
        telaOciosa.atualizar()
        telaOciosa.alpha = 0f
        telaOciosa.visibility = View.VISIBLE
        telaOciosa.animate().alpha(1f).setDuration(600).start()
        fundo.parar()
        val lp = window.attributes; lp.screenBrightness = 0.05f; window.attributes = lp
    }

    private fun sairOcioso() {
        ultimoToque = android.os.SystemClock.uptimeMillis()
        if (!ocioso) return
        ocioso = false
        telaOciosa.animate().cancel()
        telaOciosa.visibility = View.GONE
        if (apareceu) fundo.iniciar()
        val lp = window.attributes; lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; window.attributes = lp
    }

    /** Deslizar do canto esquerdo para a direita volta uma tela: a caixa gira acompanhando o dedo. */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        ultimoToque = android.os.SystemClock.uptimeMillis()
        // tela ociosa: o primeiro toque só acorda e não aciona nada embaixo
        if (ocioso || engolindo) {
            if (ocioso && e.actionMasked == MotionEvent.ACTION_DOWN) { sairOcioso(); engolindo = true }
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) engolindo = false
            return true
        }
        if (girando && !capturado) return true       // durante a animação, toques são ignorados
        if ((atual != "inicio" || telaVideo.aberto) && !modalEnergia.aberto && !modalAtualizacao.aberto && !faixaAssistente.aberta && alarmeTocando == null) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    arrastando = e.rawX < BORDA_GESTO.d() && !(atual == "midia" && !telaVideo.aberto && telaMidia.areaProtegida(e.rawX, e.rawY))
                    capturado = false
                    x0 = e.rawX; y0 = e.rawY
                }
                MotionEvent.ACTION_MOVE -> if (arrastando) {
                    val dx = e.rawX - x0
                    val dy = Math.abs(e.rawY - y0)
                    if (!capturado && dx > 16.d() && dx > dy) {
                        capturado = true
                        val cancela = MotionEvent.obtain(e)
                        cancela.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancela)   // solta o toque que os botões já tinham recebido
                        cancela.recycle()
                        modoCubo = !emDetalheAtual()
                        if (modoCubo) {
                            if (telaVideo.aberto) { destinoCubo = ""; deCubo = telaVideo; paraCubo = tela(origemVideo); telaVideo.cobrir(true) }
                            else { destinoCubo = destinoVoltar(); deCubo = tela(atual); paraCubo = tela(destinoCubo) }
                            Cubo.preparar(deCubo!!, paraCubo!!)
                            fundo.parar()      // fundo parado enquanto o dedo gira a caixa
                            girando = true; grauAtual = 0f; tArraste = android.os.SystemClock.uptimeMillis()
                        }
                    }
                    if (capturado) {
                        if (modoCubo) {
                            grauAtual = (dx / conteudo.width * 150f).coerceIn(0f, 90f)
                            Cubo.aplicar(deCubo!!, paraCubo!!, grauAtual, 1, metade())
                        } else {
                            gesto.arrastar(dx)
                            if (dx > GestoVoltar.DISTANCIA.d()) {
                                arrastando = false; capturado = false
                                gesto.soltar()
                                onBackPressed()
                            }
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val tratou = capturado
                    arrastando = false; capturado = false
                    gesto.soltar()
                    if (tratou) { if (modoCubo) soltarCubo(); return true }
                }
            }
        }
        return super.dispatchTouchEvent(e)
    }

    /** As teclas de volume mexem no volume sem o indicador nativo do Android: aparece o nosso painel. */
    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        ultimoToque = android.os.SystemClock.uptimeMillis(); if (ocioso) sairOcioso()
        if (e.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (e.action == android.view.KeyEvent.ACTION_DOWN)
                painelVolume.ajustarPorTecla(if (e.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { esconderBarras(); Quiosque.aplicar(this) }
    }

    private fun esconderBarras() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    // ------------------------------------------------------------------ alarmes

    private fun aoMudarAlarmes() { inicio.definirProximoAlarme(alarmes.proximoTexto()) }

    /** O Android trouxe o app para a frente no horário de um alarme: confere e toca. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        alarmes.verificar()?.let { tocarAlarme(it) }
    }

    /**
     * Alarme tocando: tela cheia por cima de tudo, a música do celular é pausada e o volume sobe ao mínimo audível
     * (40% do limite) se estiver baixo ou mudo; o volume volta ao que era quando o alarme para. Para sozinho em 1 minuto.
     */
    private fun tocarAlarme(a: Alarme, teste: Boolean = false) {
        if (alarmeTocando != null) return
        alarmeTocando = a
        alarmeEhTeste = teste
        sairOcioso()                                   // alarme acorda a tela
        painelVolume.fechar(); modalEnergia.fechar()
        if (musica.tocando) musica.pausar()
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val canal = android.media.AudioManager.STREAM_MUSIC
        val limite = Math.round(audio.getStreamMaxVolume(canal) * PainelVolume.LIMITE_REAL)
        val piso = Math.round(limite * 0.4f)
        volumeAntesDoAlarme = audio.getStreamVolume(canal)
        if (volumeAntesDoAlarme < piso) audio.setStreamVolume(canal, piso, 0)
        telaAlarmeTocando.mostrar(a)
        telaAlarmeTocando.bringToFront()
        SomAlarme.iniciar()
        relogio.removeCallbacks(pararAlarmeSozinho)
        relogio.postDelayed(pararAlarmeSozinho, if (teste) 10_000L else 60_000L)
    }

    private fun pararAlarme(soneca: Boolean) {
        val a = alarmeTocando ?: return
        alarmeTocando = null
        relogio.removeCallbacks(pararAlarmeSozinho)
        SomAlarme.parar()
        telaAlarmeTocando.esconder()
        if (volumeAntesDoAlarme >= 0) {
            (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).setStreamVolume(android.media.AudioManager.STREAM_MUSIC, volumeAntesDoAlarme, 0)
            volumeAntesDoAlarme = -1
        }
        if (soneca && !alarmeEhTeste) alarmes.adiar(a)
        alarmeEhTeste = false
    }

    /** Toca o som de início só quando o aparelho acabou de ligar (não quando o app é só reaberto ou reinstalado). */
    private fun tocarSomDeInicio() {
        val prefs = getSharedPreferences("sistema", MODE_PRIVATE)
        val boot = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        if (Math.abs(boot - prefs.getLong("boot", 0L)) > 15_000) {
            prefs.edit().putLong("boot", boot).apply()
            Som.tocar(this, R.raw.som_inicio)
        }
    }

    /**
     * Desligar ou reiniciar: cobre a tela com a nossa (por cima da janela do Android), apaga a fita, solta o celular que
     * está tocando, toca o som de desligar e só então manda o aparelho desligar ou reiniciar.
     */
    private fun sequenciaEnergia(desligar: Boolean) {
        painelVolume.fechar()
        cobertura.mostrar(if (desligar) "Desligando…" else "Reiniciando…")
        led.apagarParaDesligar {
            bluetooth.desconectarTodos {
                Som.tocar(this, R.raw.som_desligar) { executarEnergia(desligar) }
            }
        }
    }

    /**
     * Desliga ou reinicia o aparelho de verdade. Precisa das permissões SHUTDOWN e REBOOT, que só valem se o app estiver
     * instalado em /system/priv-app (ver docs/PASSAGEM-NOVO-CHAT.md). Sem elas, o modal volta e explica.
     */
    private fun executarEnergia(desligar: Boolean) {
        try {
            if (desligar) {
                startActivity(
                    android.content.Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN")   // nome que a ShutdownActivity do Android 7 atende
                        .putExtra("android.intent.extra.KEY_CONFIRM", false)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                (getSystemService(POWER_SERVICE) as android.os.PowerManager).reboot(null)
            }
        } catch (e: SecurityException) {
            cobertura.esconder(); led.retomar()
            modalEnergia.falhou("O aparelho não deu permissão de energia a este app. É preciso instalá-lo como app de sistema.")
        } catch (e: Exception) {
            cobertura.esconder(); led.retomar()
            modalEnergia.falhou("O aparelho recusou o comando (${e.javaClass.simpleName}).")
        }
    }

    companion object { const val BORDA_GESTO = 56; const val IDLE_MS = 60_000L }

    // Home é o launcher: Voltar só volta dentro do app e nunca sai dele
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (alarmeTocando != null) return                 // o alarme só para pelos botões
        if (modalAtualizacao.aberto) { modalAtualizacao.fechar(); return }
        if (modalEnergia.aberto) { modalEnergia.fechar(); return }
        if (painelVolume.aberto) { painelVolume.fechar(); return }
        if (telaVideo.aberto) { fecharVideoAnimado(); return }
        when (atual) {
            "midia" -> if (!telaMidia.voltar()) ir("inicio")
            "wifi" -> if (!telaWifi.voltar()) ir("inicio")
            "bluetooth" -> if (!telaBluetooth.voltar()) ir("inicio")
            "musica" -> ir(destinoVoltar())
            "led" -> ir("inicio")
            "alarmes" -> ir("inicio")
        }
    }
}
