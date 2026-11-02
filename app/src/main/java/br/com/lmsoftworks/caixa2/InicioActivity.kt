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
    private lateinit var fundoCapa: FundoCapa      // capa desfocada da tela Tocando agora, por cima do fundo
    private lateinit var barra: BarraTopo
    private lateinit var painelVolume: PainelVolume
    private lateinit var modalEnergia: ModalEnergia
    private lateinit var cobertura: CoberturaEnergia
    private lateinit var inicio: TelaInicio
    private lateinit var telaWifi: TelaWifi
    private lateinit var telaBluetooth: TelaBluetooth
    private lateinit var bluetooth: BluetoothControle

    /** O celular acabou de conectar ou de começar a tocar: reafirma o volume da caixa depois que ele manda o dele. */
    private fun impor() {
        painelVolume.imporPor(3000L)      // por 3 segundos o volume que o celular manda de partida não vale: o da caixa prevalece
    }
    private lateinit var musica: MusicaControle
    private lateinit var led: LedControle
    private lateinit var telaLed: TelaLed
    private lateinit var telaMusica: TelaMusica
    private lateinit var telaMidia: TelaMidia
    private lateinit var telaReceitas: TelaReceitas
    private lateinit var telaVideo: TelaVideo
    private lateinit var playerMidia: PlayerMidia
    private lateinit var modalAtualizacao: ModalAtualizacao
    private lateinit var faixaAssistente: br.com.lmsoftworks.caixa2.voz.FaixaAssistente
    private lateinit var assistente: br.com.lmsoftworks.caixa2.voz.Assistente
    private lateinit var alarmes: AlarmeControle
    private lateinit var telaAlarmes: TelaAlarmes
    private lateinit var telaConfig: TelaConfig
    private lateinit var telaPrevisao: TelaPrevisao
    private var previsao: br.com.lmsoftworks.caixa2.voz.Respostas.PrevisaoCompleta? = null
    private var previsaoEm = 0L
    /** Wi-Fi, Bluetooth e Alarmes abertos pela tela Configurações: o "voltar" deles leva de volta para ela. */
    private var origemConfig = false
    private lateinit var telaAlarmeTocando: TelaAlarmeTocando
    private var alarmeTocando: Alarme? = null
    private var alarmeEhTeste = false
    private lateinit var telaOciosa: TelaOciosa
    private var ocioso = false
    private var engolindo = false                  // o toque que acordou a tela não aciona nada embaixo
    private var ultimoToque = android.os.SystemClock.uptimeMillis()
    private var capaAnalisada: android.graphics.Bitmap? = null
    private var corCapa: Pair<Int, Int>? = null
    /** A cor da capa como ela é (a que vai para a fita): o fundo da tela usa a versão mais clara. */
    private var corCapaFiel: Int? = null
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

    private var climaEm = 0L
    private var climaBuscando = false
    private var climaFalhas = 0
    private var climaMostrado = false

    /** A cada 30 minutos busca a previsão e mostra na tela inicial. Se a internet ainda não voltou (logo depois de ligar a caixa), tenta de novo em 15 s, 30 s, 1 min... */
    private fun atualizarClimaInicio() {
        val agora = android.os.SystemClock.uptimeMillis()
        if (climaBuscando || (climaEm != 0L && agora - climaEm < 30 * 60_000L)) return
        climaBuscando = true
        climaEm = agora
        Thread {
            val c = try { br.com.lmsoftworks.caixa2.voz.Respostas.climaHoje(this) } catch (e: Throwable) { null }
            relogio.post {
                climaBuscando = false
                if (c != null) { inicio.definirClima(c); climaMostrado = true; climaFalhas = 0 }
                else {
                    val espera = Math.min(15_000L shl Math.min(climaFalhas, 3), 120_000L)      // 15 s, 30 s, 60 s, 2 min...
                    climaFalhas++
                    climaEm = android.os.SystemClock.uptimeMillis() - 30 * 60_000L + espera
                }
            }
        }.start()
    }

    private lateinit var lembretes: LembreteControle
    private lateinit var listas: ListaControle

    private lateinit var dormir: HoraDeDormir

    /** Chegou a hora de um lembrete: toca o aviso, mostra na tela e fala em voz alta. */
    private fun dispararLembrete(l: Lembrete) {
        val atraso = System.currentTimeMillis() - l.quandoMs > 10 * 60_000L      // a caixa estava desligada na hora
        avisarFalando((if (atraso) "Lembrete atrasado: " else "Lembrete: ") + l.texto)
    }

    /** Aviso da caixa (lembrete, hora de dormir...): dois toquinhos, o texto na tela e a fala em voz alta; a música baixa enquanto isso. */
    private fun avisarFalando(frase: String) {
        sairOcioso()
        br.com.lmsoftworks.caixa2.voz.TonsVoz.confirmacao()
        relogio.postDelayed({ br.com.lmsoftworks.caixa2.voz.TonsVoz.confirmacao() }, 900)
        faixaAssistente.respondendo(frase)
        faixaAssistente.fecharEm(9000)
        acoesAssistente.abafar(true)
        relogio.postDelayed({ br.com.lmsoftworks.caixa2.voz.Fala.falar(this, frase) { acoesAssistente.abafar(false) } }, 1500)
    }

    private val tique = object : Runnable {
        override fun run() {
            inicio.atualizar()
            if (atual == "musica") telaMusica.atualizar()
            if (atual == "config") atualizarConfig()
            if (atual == "alarmes") telaAlarmes.tique()
            barra.atualizarHora()
            if (atual == "inicio") aplicarTema("inicio")      // o fundo do Início acompanha a hora do dia
            alarmes.verificar()?.let { tocarAlarme(it) }
            if (alarmeTocando == null) lembretes.devidos().forEach { dispararLembrete(it) }
            if (alarmeTocando == null && dormir.chegouAHora()) avisarFalando("Está na hora de dormir. Boa noite!")
            if (led.modo == "ambilight" && !telaVideo.aberto) atualizarLedDaMusica()
            inicio.definirProximoAlarme(alarmes.proximoTexto(), alarmes.proxima()?.first?.nome?.ifBlank { null })
            // a faixa fechada com o gesto só fica escondida enquanto parada: tocou de novo, o cartão volta e o pause não o esconde mais
            val agoraMs = android.os.SystemClock.uptimeMillis()
            val tocandoAgora = if (musica.temMusica) musica.tocando else musica.celularTocandoAgora(bluetooth.celularTocando)
            val chaveMusica = if (musica.temMusica) musica.titulo + musica.artista else "sem-dados"
            if (tocandoAgora && agoraMs >= musicaDispensadaAte) musicaDispensada = ""
            val dispensada = agoraMs < musicaDispensadaAte || (musicaDispensada == chaveMusica && !tocandoAgora)
            // Celular conectado e som saindo, mas o celular não mandou título nem capa (AVRCP vazio): mostra um cartão com o nome do
            // aparelho para a pessoa saber que a caixa está tocando e poder pausar. Ele fica (também pausado) até o aparelho sair ou fecharem.
            // A tela da música só existe com algo tocando: sem música e sem celular tocando, não fica na frente do menu da mídia.
            if (atual == "musica" && !musica.temMusica && !musica.emLocal && !telaMusica.semDadosAtivo()) {
                if (vazioDesde == 0L) vazioDesde = agoraMs else if (agoraMs - vazioDesde > 400L && !girando) ir("inicio")
            } else vazioDesde = 0L
            if (agoraMs - ultimaSincBt > 3000L) { ultimaSincBt = agoraMs; bluetooth.sincronizarTocando() }      // o aviso de "tocando" pode ter saído antes de o app abrir
            val semDados = !musica.temMusica && bluetooth.algumConectado && (bluetooth.celularTocando || musica.tocando) && !playerMidia.ativo && !telaVideo.aberto
            if (semDados) { if (semDadosDesde == 0L) semDadosDesde = agoraMs } else semDadosDesde = 0L
            if (semDadosDesde != 0L && agoraMs - semDadosDesde > 2500L) cartaoSemDados = true      // passou a tocar sem mandar os dados
            if (!telaMusica.semDadosAtivo()) cartaoSemDados = false      // o aparelho saiu ou os dados chegaram
            val genericoVisivel = cartaoSemDados && !dispensada
            if (dispensada) abriuSemDados = false      // fecharam o player: o próximo play abre a tela de novo
            // Sem os dados da música, a tela "Tocando em <celular>" abre sozinha ao dar play, como a de "Tocando agora" abriria.
            if (genericoVisivel && !abriuSemDados) {
                abriuSemDados = true
                if (atual == "inicio" && agoraMs > semAbrirMusicaAte) ir("musica")
            }
            if (semDados) semDadosVistoEm = agoraMs else if (agoraMs - semDadosVistoEm > 8000L) abriuSemDados = false      // o áudio do iPhone cai e volta: só conta como outra vez depois de 8 s parado
            if (musica.temMusica && !dispensada) inicio.definirMusica(musica.titulo, musica.artista, musica.capa, musica.tocando)
            else if (genericoVisivel) inicio.definirMusicaSemDados(bluetooth.nomeConectado() ?: "Bluetooth", tocandoAgora)
            else inicio.definirMusica(null, null)
            val proximoLembrete = lembretes.todos.firstOrNull()
            inicio.definirLembrete(proximoLembrete?.texto, proximoLembrete?.let { LembreteControle.quando(it.quandoMs) })
            atualizarClimaInicio()
            if (!ocioso) {
                if (android.os.SystemClock.uptimeMillis() - ultimoToque > Ajustes.ociosoMs() && !ocupado()) entrarOcioso()
            } else {
                telaOciosa.atualizar()
                if (tocandoMusica()) sairOcioso()      // alguém começou a tocar: acorda
            }
            relogio.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).let { if (it.isStreamMute(android.media.AudioManager.STREAM_MUSIC)) it.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0) }      // a versão anterior podia deixar a caixa muda
        bootNovo = aparelhoAcabouDeLigar()
        if (bootNovo) aquecerAudio()
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        Cfg.iniciar(this)
        Ajustes.iniciar(this)
        aplicarBrilho()

        wifi = WifiControle(this) { aoMudarWifi() }
        fundo = FundoView(this)
        fundoCapa = FundoCapa(this).also { it.aoCobrir = { cobre -> fundo.sobreCapa = cobre } }
        bluetooth = BluetoothControle(this) { aoMudarBluetooth() }
        // Bluetooth desligando ou desligado: o app solta o serviço dele. Ligou de novo: volta a ler a música do celular.
        bluetooth.aoSincronizar = { impor() }
        bluetooth.aoEstadoAdaptador = { st ->
            if (st == android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF || st == android.bluetooth.BluetoothAdapter.STATE_OFF) musica.desligar()
            else if (st == android.bluetooth.BluetoothAdapter.STATE_ON) musica.iniciar()
        }
        bluetooth.aoAntesDeDesligar = { musica.desligar() }
        bluetooth.aoTravado = {
            Aviso.mostrar(this, "O Bluetooth do aparelho travou: reiniciar a caixa resolve")
            modalEnergia.abrir()
        }
        musica = MusicaControle(this) { aoMudarMusica() }
        musica.bluetoothLigado = { bluetooth.ligado }
        led = LedControle(this) { aoMudarLed() }
        painelVolume = PainelVolume(this) { p, mudo -> barra.definirVolume(p, mudo) }
        cobertura = CoberturaEnergia(this)
        modalEnergia = ModalEnergia(this) { desligar -> sequenciaEnergia(desligar) }
        painelVolume.aoMostrar = { sairOcioso() }
        br.com.lmsoftworks.caixa2.voz.Fala.aoMexerNoVolume = { painelVolume.ignorarMudancas() }
        barra = BarraTopo(this, { ir("wifi") }, { ir("bluetooth") }, { ir("led") }, { painelVolume.alternar() }, { painelVolume.fechar(); modalEnergia.abrir() })
        barra.colocarPilulaVolume(painelVolume.pilula)
        gesto = GestoVoltar(this)
        barra.translationZ = 30f; painelVolume.translationZ = 20f; gesto.translationZ = 30f      // acima das telas que deslizam
        inicio = TelaInicio(this) { abrir(it) }
        inicio.aoFecharMusica = {
            musicaDispensada = if (musica.temMusica) musica.titulo + musica.artista else "sem-dados"
            musicaDispensadaAte = android.os.SystemClock.uptimeMillis() + 1500      // dá tempo de o player confirmar que parou
            if (musica.emLocal) playerMidia.parar() else musica.pausar()
            inicio.definirMusica(null, null)
        }
        inicio.aoPlayPause = { semAbrirMusicaAte = android.os.SystemClock.uptimeMillis() + 4000; musica.alternar() }      // play/pause no Início não abre a tela da música
        telaWifi = TelaWifi(this, wifi)
        telaWifi.visibility = View.GONE
        telaBluetooth = TelaBluetooth(this, bluetooth)
        telaBluetooth.visibility = View.GONE
        telaMusica = TelaMusica(this, musica, bluetooth, { ir("bluetooth") }, { ir("midia") }) { videoDaMusica() }
        telaMusica.fundoCapa = fundoCapa
        telaMusica.semDados.aoCor = { a, b -> if (atual == "musica") fundo.tema(a, b, 1.9f) }      // sem os dados da música: as cores do fundo mudam sozinhas
        telaMusica.visibility = View.GONE
        playerMidia = PlayerMidia(this) { aoMudarPlayer() }
        playerMidia.celularTocando = { bluetooth.celularConfirmado }
        musica.local = playerMidia
        telaMidia = TelaMidia(this, { lista, i -> abrirVideo(listOf(lista[i]), 0) }, { lista, i -> tocarMusicaDoYoutube(listOf(lista[i]), 0) }, { e -> Atualizador.aoFalhar(this, e) })
        telaMidia.aoAbrirWifi = { ir("wifi") }
        telaMidia.visibility = View.GONE
        telaReceitas = TelaReceitas(this)
        telaReceitas.visibility = View.GONE
        telaVideo = TelaVideo(this, playerMidia, { painelVolume.porcento }, { p -> painelVolume.definirPorcento(p) }, { seletorParaMusica() }) { fecharVideoAnimado() }
        faixaAssistente = br.com.lmsoftworks.caixa2.voz.FaixaAssistente(this, { assistente.nivelMicrofone }, { conteudo })
        faixaAssistente.aoToque = { assistente.cancelar() }
        assistente = br.com.lmsoftworks.caixa2.voz.Assistente(this, faixaAssistente, acoesAssistente)
        telaMidia.nivelMic = { assistente.nivelMicrofone }
        telaMidia.aoMicrofone = {
            assistente.ouvirParaBusca({ telaMidia.ouvindoVoz(true) }, { parcial -> telaMidia.mostrarParcial(parcial) }, { texto ->
                telaMidia.ouvindoVoz(false)
                if (texto != null) telaMidia.pesquisar(texto) else telaMidia.avisoBusca("Não ouvi. Toque no microfone e fale de novo.")
            }, { telaMidia.pesquisandoVoz() })
        }
        telaLed = TelaLed(this, led)
        telaLed.visibility = View.GONE
        alarmes = AlarmeControle(this) { aoMudarAlarmes() }
        lembretes = LembreteControle(this)
        listas = ListaControle(this)
        dormir = HoraDeDormir(this)
        telaAlarmes = TelaAlarmes(this, alarmes, lembretes, listas, dormir) { a -> tocarAlarme(a, teste = true) }
        telaAlarmes.visibility = View.GONE
        telaConfig = TelaConfig(this) { abrir(it) }
        telaConfig.lerVolume = { Math.round(painelVolume.porcento / 10f) }
        telaConfig.definirVolume = { n -> painelVolume.definirSilencioso(n * 10, true) }
        telaConfig.aoMudarBrilho = { aplicarBrilho() }
        telaConfig.aoVerificarAtualizacao = { Atualizador.verificarAgora(this) { msg -> Aviso.mostrar(this, msg) } }
        telaConfig.aoEnergia = { modalEnergia.abrir() }
        // prioridade: se o celular começar a tocar, o que toca na própria caixa (YouTube) pára
        bluetooth.aoCelularTocando = {
            if (!musica.appRecente()) celularAssumiu()
            impor()
        }
        // o estado "tocando" do celular (AVRCP) vale mesmo quando o áudio dele fica caindo e voltando (o iPhone faz isso): pausa o vídeo
        musica.celularSomTocando = { bluetooth.celularTocando }
        playerMidia.aoComecou = { musica.appComecou() }
        musica.aoCelularComecou = { celularAssumiu() }
        // o volume da caixa prevalece: o celular manda o dele ao conectar e ao começar a tocar, e logo depois o da caixa volta
        bluetooth.aoConectado = { painelVolume.imporPor(3000L) }
        bluetooth.aoDesconectado = {
            // o aparelho saiu (a pessoa desligou o Bluetooth do celular ou se afastou): o cartão de música some e a tela de música volta a
            // pedir para conectar um celular. Não muda de tela.
            if (!Ajustes.restaurarRedes && !modalEnergia.aberto) {
                musica.celularDesconectou()
                abriuSemDados = false      // se o celular voltar e der play, a tela "Tocando de ..." abre de novo
                if (atual == "musica" && !musica.emLocal) ir("inicio")      // o player fecha sozinho e volta para o início
            }
        }
        telaConfig.buscarCidade = { nome, volta -> Thread { val r = try { br.com.lmsoftworks.caixa2.voz.Respostas.buscarCidade(this, nome) } catch (e: Throwable) { null }; relogio.post { volta(r) } }.start() }
        telaConfig.aoMudarFuso = { alarmes.reagendar(); aoMudarAlarmes(); climaEm = 0L }
        telaConfig.buscarFusoAutomatico = { FusoHorario.buscarAutomatico(this, relogio) { telaConfig.aoMudarFuso(); if (atual == "config") telaConfig.atualizar() } }
        telaConfig.aoMudarCidade = { climaEm = 0L; previsao = null }      // o clima e a previsão buscam de novo, na cidade nova
        telaConfig.aoMudarNome = { bluetooth.aplicarNome() }
        telaConfig.aoRestaurar = { aplicarBrilho(); bluetooth.aplicarNome(); climaEm = 0L; previsao = null }
        telaConfig.lerRegistro = { volta ->
            Thread {
                val txt = try {
                    val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "400", "-s", "Assistente:D", "VozCaixa:D", "Midia:D", "CaixaLed:D", "CaixaBt:D", "Atualizador:D", "AndroidRuntime:E"))
                    p.inputStream.bufferedReader().readLines().filter { !it.startsWith("---") }.takeLast(70).joinToString("\n") { it.replace(Regex("^\\d\\d-\\d\\d "), "") }
                } catch (e: Throwable) { "Não consegui ler o registro: ${e.message}" }
                relogio.post { volta(txt) }
            }.start()
        }
        telaConfig.salvarRegistro = { txt ->
            try { val f = java.io.File(getExternalFilesDir(null), "registro.txt"); f.writeText(txt); f.absolutePath } catch (e: Throwable) { "" }
        }
        telaConfig.versao = try { "" + packageManager.getPackageInfo(packageName, 0).versionName + " (" + Atualizador.versaoInstalada(this) + ")" } catch (e: Exception) { "" }
        telaPrevisao = TelaPrevisao(this) { buscarPrevisao() }
        telaPrevisao.visibility = View.GONE
        telaConfig.visibility = View.GONE
        telaAlarmeTocando = TelaAlarmeTocando(this, { pararAlarme(true) }, { pararAlarme(false) })
        telaOciosa = TelaOciosa(this)
        modalAtualizacao = ModalAtualizacao(this)
        Atualizador.aoHaAtualizacao = { v -> if (alarmeTocando == null) modalAtualizacao.mostrar(v) }

        conteudo = FrameLayout(this).apply {
            alpha = 0f
            addView(fundoCapa, FrameLayout.LayoutParams(-1, -1))      // a capa desfocada fica ATRÁS das manchas de luz
            addView(fundo, FrameLayout.LayoutParams(-1, -1))
            addView(inicio, FrameLayout.LayoutParams(-1, -1))      // a v2 posiciona tudo no quadro inteiro de 1280x720
            addView(telaWifi, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64.d() })
            addView(telaBluetooth, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64.d() })
            addView(telaMusica, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64.d() })
            addView(telaMidia, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64.d() })
            addView(telaReceitas, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64.d() })
            addView(telaLed, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64.d() })
            addView(telaAlarmes, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64.d() })
            addView(telaConfig, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64.d() })
            addView(AvisoView(this@InicioActivity).also { Aviso.tela = it; it.translationZ = 40f }, FrameLayout.LayoutParams(-1, -1))
            addView(telaPrevisao, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64.d() })
            addView(painelVolume, FrameLayout.LayoutParams(-1, -1))
            addView(barra, FrameLayout.LayoutParams(-1, 104.d()))
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
                    // espera a animação de giro do Android terminar antes de mostrar a interface
                    conteudo.animate().alpha(1f)
                        .setStartDelay(Cfg.ATRASO_ENTRADA_MS).setDuration(Cfg.FADE_ENTRADA_MS).start()
                    // o fundo fica preto (sem imagem) até a interface aparecer em fade; o som começa junto, do início
                    if (bootNovo) relogio.postDelayed({ tocarSomDeInicio() }, Cfg.ATRASO_ENTRADA_MS)
                }
            }
        }
        setContentView(raiz)
        aplicarTema("inicio")
        aoMudarWifi()
        aoMudarBluetooth()
        aoMudarLed()
    }

    override fun onStart() {
        super.onStart()
        restaurarRedesSeDesligadas()
        if (Ajustes.fusoAuto) telaConfig.buscarFusoAutomatico()      // o fuso do lugar, pela internet (o do Android daqui tem horário de verão antigo)
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
        barra.definirWifi(wifi.ligado, wifi.ligado && wifi.conectadoSsid != null)
        if (atual == "wifi") telaWifi.atualizar()
        if (wifi.ligado && wifi.conectadoSsid != null && !climaMostrado && !climaBuscando) { climaEm = 0L; climaFalhas = 0 }      // a internet chegou: busca o clima já
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
        val corFita = led.corDestaque(t)
        fundo.tema(corFita, Color.rgb((Color.red(corFita) * .45f).toInt(), (Color.green(corFita) * .45f).toInt(), (Color.blue(corFita) * .45f).toInt()))
        val antes = Cfg.AZUL
        Cfg.AZUL = if (corFita == Color.WHITE) Cfg.COR_FIXA else corFita      // o destaque da tela é a cor da fita (branca: azul)
        if (Cfg.AZUL != antes) telaLed.atualizar()      // os cartões e deslizadores seguem a cor sem precisar mexer neles
    }

    private fun aoMudarLed() {
        barra.definirLed(led.conectado, led.ligado, led.conectando)
        if (atual == "led") telaLed.atualizar()
    }

    /** Até esse instante a música que começar a tocar NÃO abre sozinha a tela Tocando agora (foi o play do cartão do Início). */
    private var semAbrirMusicaAte = 0L
    private var ultimaSincBt = 0L
    private var vazioDesde = 0L
    private var cartaoSemDados = false          // o cartão do Início com o nome do aparelho (celular tocando sem mandar os dados) está valendo
    private var semDadosVistoEm = 0L
    private var abriuSemDados = false        // a tela "Tocando em ..." (celular tocando sem mandar os dados) já abriu nesta vez
    private var semDadosDesde = 0L          // desde quando o som sai pelo Bluetooth sem o celular mandar os dados da música
    private var musicaDispensada = ""          // faixa fechada com o gesto no cartão do Início: o cartão só volta quando ela tocar de novo
    private var musicaDispensadaAte = 0L

    private fun aoMudarMusica() {
        if (atual == "musica") { telaMusica.atualizar(); aplicarTema("musica"); atualizarLedDaMusica() }   // a cor da capa pode chegar depois
        // quando alguém começa a tocar pelo Bluetooth, abre sozinho a tela Tocando agora
        // (sem os dados da música, o estado "tocando" do iPhone oscila: quem abre a tela nesse caso é o tique, uma vez por vez que toca)
        if (musica.tocando && !tocandoAntes && musica.temMusica && atual == "inicio" && android.os.SystemClock.uptimeMillis() > semAbrirMusicaAte) ir("musica")
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
        "receitas" -> telaReceitas
        "led" -> telaLed
        "alarmes" -> telaAlarmes
        "config" -> telaConfig
        "clima" -> telaPrevisao
        else -> inicio
    }

    private fun abrir(id: String) {
        if (id == "lembretes") { telaAlarmes.abrirAba("lembretes", lembretes.todos.firstOrNull()?.id); ir("alarmes") }
        else if (id == "alarme-proximo") { telaAlarmes.abrirAba("alarmes", alarmes.proxima()?.first?.id?.toLong()); ir("alarmes") }
        else if (id == "midia") ir(if (musica.temMusica || telaMusica.semDadosAtivo()) "musica" else "midia")      // tocando algo (com ou sem os dados da música): Tocando agora; senão, a busca
        else if (id == "wifi" || id == "bluetooth" || id == "musica" || id == "led" || id == "alarmes" || id == "config" || id == "clima" || id == "receitas") ir(id)
        else Aviso.mostrar(this, "Em breve")
    }

    /** Tela de música: o fundo e o destaque seguem a cor predominante da capa; sem capa (ou capa cinza), o azul padrão. */
    private fun temaMusica(): Triple<String, String, String> {
        val capa = musica.capa
        if (capa !== capaAnalisada) {
            capaAnalisada = capa
            corCapa = capa?.let { try { CorCapa.analisar(it) } catch (e: Exception) { null } }
            corCapaFiel = capa?.let { try { CorCapa.analisarFiel(it) } catch (e: Exception) { null } }
        }
        // sem música: as cores do Bluetooth
        if (!musica.temMusica) return Triple("#3D6BFF", "#2B2FA8", "#4C7DFF")
        if (corCapa == null) {
            // sem capa (ou capa sem cor): segue a cor do banner padrão que o próprio app cria para a música
            val h = CapaView.matiz(musica.titulo + musica.artista)
            fun cor(i: Int) = String.format("#%06X", 0xFFFFFF and i)
            return Triple(cor(CorCapa.hsl(h, .82f, .48f)), cor(CorCapa.hsl(h + 35f, .72f, .52f)), cor(CorCapa.hsl(h, .8f, .56f)))
        }
        val c = corCapa!!
        fun hex(i: Int) = String.format("#%06X", 0xFFFFFF and i)
        return Triple(hex(c.first), hex(c.second), hex(c.first))
    }

    /** Cores do fundo e cor de destaque de cada menu. */
    private fun aplicarTema(menu: String) {
        // (cor 1 do fundo, cor 2 do fundo, cor de destaque do menu) — interface v3
        if (menu == "inicio") {
            val h = Tema.fundoDaHora(Tema.horaAgora())
            fundo.tema(h.cor1, h.cor2, h.brilho)
            inicio.definirSombra(h.sombra)
            corFundoMenu = h.cor1
            Cfg.AZUL = Color.parseColor("#3D8BFF")
            return
        }
        val (a, b, destaque) = when (menu) {
            "wifi" -> Triple("#14C9B0", "#0A5E7C", "#14B8A6")
            "bluetooth" -> Triple("#3D6BFF", "#2B2FA8", "#4C7DFF")
            "musica" -> temaMusica()
            "midia" -> Triple("#FF5A4E", "#7A1E5A", "#FF5A5F")
            "receitas" -> Triple("#FF9A3D", "#8A2E1A", "#FF9A3D")
            "led" -> Triple("#2F7BFF", "#2A1A7A", "#2F7BFF")
            "alarmes" -> Triple("#8B5CFF", "#3A1FA8", "#8B5CFF")
            "config" -> Triple("#5B6B8C", "#1A2340", "#3D8BFF")
            // previsão do tempo: o fundo segue o tempo de hoje (sol, nublado ou chuva)
            "clima" -> when (telaPrevisao.tipoHoje) {
                "sol" -> Triple("#FFB547", "#1F5BFF", "#3D8BFF")
                "chuva" -> Triple("#3D7BFF", "#1A2E6E", "#3D8BFF")
                "lua" -> Triple("#5A3CE0", "#232A9A", "#3D8BFF")
                else -> Triple("#7C8FB8", "#2B3A66", "#3D8BFF")
            }
            else -> Triple("#3D6BFF", "#2B2FA8", "#4C7DFF")
        }
        // Tocando agora: o fundo é mais claro e forte (as manchas ganham mais brilho) para a cor da capa aparecer
        fundo.tema(Color.parseColor(a), Color.parseColor(b), if (menu == "musica") 1.9f else 1f)
        corFundoMenu = Color.parseColor(a)
        Cfg.AZUL = Color.parseColor(destaque)
    }

    /** Parte leve da troca de menu: só o que precisa estar certo já no primeiro quadro do giro. */
    /** O conteúdo de alguns menus é montado com a cor de destaque do menu: refaz ANTES do giro, para já abrir na cor certa. */
    private fun preencher(destino: String) {
        when (destino) {
            "led" -> telaLed.atualizar()
            "alarmes" -> telaAlarmes.atualizar()
            "config" -> { atualizarConfig(); telaConfig.atualizar() }
        }
    }

    private fun mudarEstado(destino: String) {
        // saiu da tela da música de propósito: ela não volta sozinha por alguns segundos, para a pessoa navegar à vontade
        if (atual == "musica" && destino != "musica") semAbrirMusicaAte = maxOf(semAbrirMusicaAte, android.os.SystemClock.uptimeMillis() + 8000L)
        atual = destino
        if (destino == "musica") fundoCapa.definir(if (musica.temMusica) musica.capa else null)
        fundoCapa.exibir(destino == "musica")
        aplicarTema(destino)
        preencher(destino)
        gesto.ativo = destino != "inicio"
        barra.mostrarHora(destino != "inicio")
        atualizarLedDaMusica()
    }

    /** Ambilight (modo da fita): na tela de música ela acompanha a cor da capa; no vídeo, a cor da imagem (ver [tique]). Fora disso, volta à cor escolhida. */
    /** Cor de fundo do menu atual: é a que o Ambilight da fita segue quando não há capa de música nem vídeo na tela. */
    private var corFundoMenu: Int? = null

    private fun atualizarLedDaMusica() {
        var cor: Int? = if (atual == "led") null else corFundoMenu
        if (atual == "musica" && musica.temMusica) {
            val tema = temaMusica()
            cor = Color.parseColor(tema.third)      // a fita segue a MESMA cor da barra de progresso (Cfg.AZUL); sem capa colorida, a do banner padrão. No vídeo ela segue as cores da tela (ver [tique])
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
        if (destino == "midia") telaMidia.aoMostrar()
        if (destino == "clima") {
            // previsão guardada por 15 minutos; senão busca de novo (mostra a rodinha só se ainda não havia nada)
            if (previsao != null && android.os.SystemClock.uptimeMillis() - previsaoEm < 15 * 60_000L) telaPrevisao.definir(previsao) else buscarPrevisao()
        }
    }

    private var buscandoPrevisao = false

    /** Busca a previsão completa (agora, 7 horas, 15 dias) em segundo plano e entrega à tela. */
    private fun buscarPrevisao() {
        if (buscandoPrevisao) return
        buscandoPrevisao = true
        telaPrevisao.mostrarCarregando()
        Thread {
            val p = try { br.com.lmsoftworks.caixa2.voz.Respostas.previsaoCompleta(this) } catch (e: Throwable) { null }
            relogio.post {
                buscandoPrevisao = false
                if (p != null) { previsao = p; previsaoEm = android.os.SystemClock.uptimeMillis() }
                telaPrevisao.definir(p ?: previsao)
                if (atual == "clima") aplicarTema("clima")      // o fundo segue o tempo de hoje
            }
        }.start()
    }

    private fun metade(): Float = conteudo.width / 2f

    /** Gira o cubo de [desde] até [ate] graus (640 ms no giro inteiro; nos parciais, proporcional, no mínimo 240 ms). */
    private fun animarCubo(de: View, para: View, dir: Int, desde: Float, ate: Float, aoFim: () -> Unit) {
        val dur = if (desde == 0f && ate == 90f) 500L else maxOf(200L, (500f * Math.abs(ate - desde) / 90f).toLong())
        // giro inteiro (abrir menu, botão voltar): começa e termina devagar (ease in-out cúbico).
        // Terminar um arrasto: o dedo já vinha rápido, então a caixa continua na mesma velocidade e só freia (sem "paradinha").
        val interpolador = if (desde == 0f && ate == 90f) android.view.animation.PathInterpolator(0.3f, 0.7f, 0.2f, 1f)
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
        // INVISIBLE (e não GONE): esconder sem GONE não pede nova medida e posição de todas as telas, que era o tranco do fim do giro
        if (ficouNoDestino) de.visibility = View.INVISIBLE else para.visibility = View.INVISIBLE
        val ficou = if (ficouNoDestino) para else de
        val saiu = if (ficouNoDestino) de else para
        Cubo.limpar(saiu)      // a que saiu já está escondida: limpar é de graça
        girando = false
        // O quadro em que o giro termina fica livre: nada pesado nele (era isso que dava a "paradinha" no encaixe). A tela que ficou
        // continua como camada pronta (a imagem é idêntica à final) e só depois volta ao desenho normal, com o fundo e o trabalho do menu.
        relogio.postDelayed({ if (!girando) Cubo.limpar(ficou) }, 160)
        if (apareceu && !ocioso) relogio.postDelayed({ if (!girando && !ocioso) fundo.iniciar() }, 300)
        if (ficouNoDestino) relogio.postDelayed({ if (!girando) aoChegar(atual) }, 220)      // só agora o trabalho pesado, com a tela já parada
    }

    private fun ir(destinoPedido: String) {
        // não existe tela de "nenhuma música tocando": sem música e sem celular tocando, quem pediu a tela da música vai para a Mídia
        val destino = if (destinoPedido == "musica" && !musica.temMusica && !musica.emLocal && !telaMusica.semDadosAtivo()) "midia" else destinoPedido
        if (destino == atual || girando) return
        val de: View = tela(atual)
        val para: View = tela(destino)
        val dir = if (destino == "inicio" || destino == destinoVoltar()) 1 else -1      // voltar gira para a direita; entrar, para o outro lado
        origemConfig = atual == "config" && (destino == "wifi" || destino == "bluetooth" || destino == "alarmes")
        mudarEstado(destino)
        if (!apareceu) { de.visibility = View.GONE; para.visibility = View.VISIBLE; aoChegar(destino); return }
        girando = true
        Cubo.preparar(de, para)
        Cubo.aplicar(de, para, 0f, dir, metade())
        animarCubo(de, para, dir, 0f, 90f) { terminarGiro(de, para, true) }
    }

    /** Para onde o "voltar" leva: música do YouTube volta à busca (Mídia); Mídia e os demais menus voltam à tela inicial. */
    private fun destinoVoltar() = when {
        atual == "musica" && musica.emLocal -> "midia"
        origemConfig && (atual == "wifi" || atual == "bluetooth" || atual == "alarmes") -> "config"
        else -> "inicio"
    }

    /** O estado que a tela Configurações mostra embaixo de cada item. */
    private fun atualizarConfig() {
        telaConfig.definirEstado("wifi", if (!wifi.ligado) "Desligado" else wifi.conectadoSsid?.let { "Conectado a $it" } ?: "Sem rede conectada")
        telaConfig.definirEstado("bluetooth", if (!bluetooth.ligado) "Desligado" else bluetooth.nomeConectado()?.let { "Conectado a $it" } ?: "Visível como “${bluetooth.nomeCaixa}”")
        telaConfig.definirEstado("led", when { led.online -> "Conectada" + if (!led.ligado) " · desligada" else ""; led.conectandoParaTela -> "Conectando…"; else -> "Sem conexão" })
    }

    /** Há uma camada aberta dentro da tela (detalhes, senha do Wi-Fi, teclado da Mídia): o gesto de voltar fecha só ela e fica no mesmo menu. */
    private fun emDetalheAtual() = (atual == "wifi" && (telaWifi.emDetalhe || telaWifi.emSenha)) || (atual == "bluetooth" && telaBluetooth.emDetalhe) ||
        (atual == "midia" && telaMidia.tecladoVisivel) || (atual == "receitas" && telaReceitas.emDetalhe) || (atual == "alarmes" && (telaAlarmes.emTexto || telaAlarmes.emModal)) || (atual == "config" && telaConfig.emTexto)

    // ------------------------------------------------------------------ assistente de voz

    /** O que o assistente pode mexer no app. Tudo roda na thread principal. */
    private val acoesAssistente = object : br.com.lmsoftworks.caixa2.voz.AcoesAssistente {
        override fun acordarTela() { sairOcioso() }

        override fun resumoRotina(manha: Boolean): String {
            val partes = ArrayList<String>()
            if (manha) {
                val fimDoDia = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59) }.timeInMillis
                val hoje = lembretes.todos.filter { it.quandoMs <= fimDoDia }
                if (hoje.isNotEmpty()) partes.add("Você tem ${hoje.size} ${if (hoje.size == 1) "lembrete" else "lembretes"} hoje: " +
                    hoje.take(4).joinToString(", ") { "${it.texto}, ${LembreteControle.quando(it.quandoMs)}" } + ".")
            }
            alarmes.proximoTexto()?.let { partes.add("Seu próximo alarme é ${it.replaceFirstChar { c -> c.lowercase() }}.") }
            return partes.joinToString(" ")
        }

        override fun agendarLembrete(texto: String, quandoMs: Long): String {
            lembretes.novo(texto, quandoMs)
            return "Pronto, vou te lembrar de $texto ${LembreteControle.quando(quandoMs)}"
        }

        private val focoVoz = android.media.AudioManager.OnAudioFocusChangeListener { }
        private var focoPedido = false
        private var modoPausa = false
        private var modoPausaApp = false
        private var geracaoPausa = 0
        private var geracaoFimPausa = 0

        override fun abafar(sim: Boolean): Long {
            // música do celular (Bluetooth): fade-out, pausa e voz no volume dela; vídeo/música do app: baixa o player
            // o aviso de "celular tocando" do Bluetooth só chega quando o estado muda: se o app abriu com a música já tocando, ele não
            // veio. Por isso também vale o aparelho dizer que há música saindo agora.
            val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val celular = bluetooth.celularTocando || (bluetooth.algumConectado && audio.isMusicActive)
            var espera = 0L
            if (sim) {
                geracaoFimPausa++           // chamou de novo antes de a música voltar: cancela o fechamento pendente
                if (playerMidia.ativo) {
                    // música/vídeo do app: desce em fade e pausa; a voz e os toquinhos saem no volume da voz
                    if (!modoPausaApp) {
                        val e = playerMidia.pausarParaAssistente()
                        if (e > 0) {
                            modoPausaApp = true
                            espera = e + 100L
                            relogio.postDelayed({ if (modoPausaApp) painelVolume.volumeDaVoz() }, e)
                        }
                    }
                } else if (!celular) {
                    painelVolume.volumeDaVoz()      // nada tocando: toquinhos, "pesquisando" e voz saem no volume da voz
                } else {
                    if (!modoPausa) {
                        modoPausa = true
                        val g = ++geracaoPausa
                        espera = (audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) - 1).coerceAtLeast(0) * 60L + 100L + 800L
                        painelVolume.pausaInicio {
                            if (g == geracaoPausa) {
                                // foco exclusivo e passageiro: o receptor Bluetooth da caixa pausa o celular
                                val r = audio.requestAudioFocus(focoVoz, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                                focoPedido = r == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                                android.util.Log.d("CaixaBt", "foco para pausar a musica do celular: ${if (focoPedido) "concedido" else "negado"}")
                                if (!focoPedido) painelVolume.pausaMeio(2)
                                else relogio.postDelayed({ if (g == geracaoPausa) painelVolume.pausaMeio() }, 800)
                            }
                        }
                    }
                }
            } else if (modoPausa) {
                // espera o toquinho de fim tocar no volume da voz; depois a música volta e o volume sobe em fade
                val g = ++geracaoFimPausa
                relogio.postDelayed({
                    if (g == geracaoFimPausa && modoPausa) {
                        geracaoPausa++
                        modoPausa = false
                        if (focoPedido) { audio.abandonAudioFocus(focoVoz); focoPedido = false }
                        painelVolume.pausaFim()
                    }
                }, 350)
            } else if (modoPausaApp) {
                val g = ++geracaoFimPausa
                relogio.postDelayed({
                    if (g == geracaoFimPausa && modoPausaApp) {
                        modoPausaApp = false
                        painelVolume.abafarSistema(false, fade = false)      // o volume volta com a música ainda parada
                        playerMidia.retomarDoAssistente()
                    }
                }, 350)
            } else {
                val g = ++geracaoFimPausa
                relogio.postDelayed({ if (g == geracaoFimPausa) painelVolume.abafarSistema(false) }, 350)
            }
            playerMidia.volume(1f)
            return espera
        }

        override fun videoAberto() = telaVideo.aberto

        override fun tocar(lista: List<Resultado>, soAudio: Boolean) {
            musica.marcarPorVoz()      // o Bluetooth devolve o som ao celular no fim da fala: o app não cede a isso
            if (soAudio) { videoDeMusica = false; if (telaVideo.aberto) fecharVideo(); tocarMusicaDoYoutube(lista, 0) } else abrirVideo(lista, 0)
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
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.CancelarTimer -> {
                // "cancelar o temporizador" também cancela o de sono (o reconhecimento rápido às vezes não ouve a palavra "sono")
                val tinhaTimer = timerFim != 0L
                val tinhaSono = cancelarSono()
                cancelarTimer()
                when {
                    tinhaTimer -> "Cancelei o timer."
                    tinhaSono -> "Cancelei o temporizador de sono."
                    else -> "Não há nenhum timer ligado."
                }
            }
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
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.TemporizadorSono -> {
                agendarSono(c.segundos)
                "Pronto, vou desligar o som daqui a ${textoDuracao(c.segundos)}."
            }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.CancelarSono ->
                if (cancelarSono()) "Cancelei o temporizador de sono." else "Não há nenhum temporizador de sono."
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.Lista -> listas.executar(c)
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.HoraDeDormir -> {
                dormir.definir(c.hora, c.minuto)
                "Combinado! Todo dia às %02d:%02d eu aviso que é hora de dormir.".format(c.hora, c.minuto)
            }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.DesligarHoraDeDormir -> { dormir.desligar(); "Pronto, desliguei o aviso de hora de dormir." }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.ConsultarHoraDeDormir ->
                if (dormir.ativa) "Sua hora de dormir é às %02d:%02d.".format(dormir.hora, dormir.minuto) else "Você ainda não definiu uma hora de dormir."
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.ListarLembretes -> {
                val todos = lembretes.todos
                if (todos.isEmpty()) "Você não tem nenhum lembrete."
                else (if (todos.size == 1) "Você tem 1 lembrete: " else "Você tem ${todos.size} lembretes: ") +
                    todos.take(5).joinToString(". ") { "${it.texto}, ${LembreteControle.quando(it.quandoMs)}" }
            }
            is br.com.lmsoftworks.caixa2.voz.ComandoVoz.ApagarLembretes -> {
                val n = lembretes.apagarTodos()
                if (n == 0) "Você não tem nenhum lembrete." else if (n == 1) "Apaguei o lembrete." else "Apaguei os $n lembretes."
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
                    musicaPedida -> { if (telaVideo.aberto) fecharVideo(); tocarMusicaDoYoutube(lista.take(1), 0) }
                    else -> abrirVideo(lista.take(1), 0)
                }
            }
        }.start()
    }

    // ---- temporizador de sono: no fim, o volume desce em ~18 s, a música para e o volume volta ao que era ----
    private val sonoAcabou = Runnable { iniciarFadeSono() }
    private var fadeSono: Runnable? = null
    private var volumeAntesDoSono = -1

    private var sonoAgendadoEm = 0L

    private fun agendarSono(segundos: Int) {
        cancelarSono()
        sonoAgendadoEm = android.os.SystemClock.uptimeMillis()
        relogio.postDelayed(sonoAcabou, segundos * 1000L)
    }

    /** Cancela o temporizador de sono (agendado ou no meio do fade, devolvendo o volume). true se havia um. */
    private fun cancelarSono(): Boolean {
        var tinha = sonoAgendadoEm != 0L
        relogio.removeCallbacks(sonoAcabou)
        fadeSono?.let { relogio.removeCallbacks(it); fadeSono = null; tinha = true }
        if (volumeAntesDoSono >= 0) { painelVolume.definirSilencioso(volumeAntesDoSono); volumeAntesDoSono = -1 }
        sonoAgendadoEm = 0L
        return tinha
    }

    private fun iniciarFadeSono() {
        sonoAgendadoEm = 0L
        val original = painelVolume.porcento
        volumeAntesDoSono = original
        val passos = 12
        var i = 0
        val r = object : Runnable {
            override fun run() {
                i++
                if (i >= passos) {
                    fadeSono = null
                    if (telaVideo.aberto) { if (playerMidia.tocando) playerMidia.alternar() } else musica.pausar()
                    painelVolume.definirSilencioso(volumeAntesDoSono.coerceAtLeast(0))
                    volumeAntesDoSono = -1
                    return
                }
                painelVolume.definirSilencioso(original * (passos - i) / passos)
                relogio.postDelayed(this, 1500)
            }
        }
        fadeSono = r
        relogio.post(r)
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
            br.com.lmsoftworks.caixa2.voz.AcaoVolume.AUMENTAR -> { painelVolume.definirSilencioso((n + 1).coerceIn(0, 10) * 10, true); "Volume ${minOf(10, n + 1)}" }
            br.com.lmsoftworks.caixa2.voz.AcaoVolume.DIMINUIR -> { painelVolume.definirSilencioso((n - 1).coerceIn(0, 10) * 10, true); "Volume ${maxOf(0, n - 1)}" }
            br.com.lmsoftworks.caixa2.voz.AcaoVolume.MAXIMO -> { painelVolume.definirSilencioso(100, true); "Volume máximo" }
            br.com.lmsoftworks.caixa2.voz.AcaoVolume.MINIMO -> { painelVolume.definirSilencioso(10, true); "Volume mínimo" }
            br.com.lmsoftworks.caixa2.voz.AcaoVolume.NIVEL -> { painelVolume.definirSilencioso(c.nivel.coerceIn(0, 10) * 10, true); "Volume ${c.nivel}" }
            br.com.lmsoftworks.caixa2.voz.AcaoVolume.MUDO -> { if (n > 0) painelVolume.alternarMudo(); null }
            br.com.lmsoftworks.caixa2.voz.AcaoVolume.DESMUTAR -> { if (n == 0) painelVolume.alternarMudo(); "Som ligado" }
        }
    }

    // ---- fade-out da luz (rotina "boa noite"): o brilho desce aos poucos (a fita não aguenta cores em rajada) e então apaga ----
    private var fadeLed: Runnable? = null

    private fun cancelarFadeLed() {
        val r = fadeLed ?: return
        relogio.removeCallbacks(r)
        fadeLed = null
        led.definirEscalaFade(1f)
    }

    /** O brilho desce até ZERO em ~3,5 s (curva ao quadrado, que parece linear para o olho) e só então apaga: sem pulo no fim. */
    private fun apagarLuzSuave() {
        if (!led.ligado) return
        cancelarFadeLed()
        val passos = 14
        var i = 0
        val r = object : Runnable {
            override fun run() {
                i++
                if (i >= passos) {
                    fadeLed = null
                    led.definirEscalaFade(0f)
                    led.definirLigado(false)
                    led.definirEscalaFade(1f)           // apagada; o brilho guardado continua o de antes
                    return
                }
                val k = 1f - i / passos.toFloat()
                led.definirEscalaFade(k * k)
                relogio.postDelayed(this, 250)
            }
        }
        fadeLed = r
        relogio.post(r)
    }

    private fun comandoLuz(c: br.com.lmsoftworks.caixa2.voz.ComandoVoz.Luz): String {
        if (c.acao != br.com.lmsoftworks.caixa2.voz.AcaoLuz.APAGAR_SUAVE) cancelarFadeLed()
        return comandoLuzAgora(c)
    }

    private fun comandoLuzAgora(c: br.com.lmsoftworks.caixa2.voz.ComandoVoz.Luz): String = when (c.acao) {
        br.com.lmsoftworks.caixa2.voz.AcaoLuz.APAGAR_SUAVE -> { apagarLuzSuave(); "Luzes apagando" }
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
        if (playerMidia.ehVideo) telaVideo.atualizar() else { musica.sincronizarLocal(); adiantarClipe() }
    }

    /** A tela que estava por baixo quando o vídeo abriu (a busca, ou o início quando o pedido veio por voz): é para onde o vídeo volta. */
    private var origemVideo = "midia"

    /** O vídeo aberto veio de uma música (seletor música <-> vídeo): ao sair, a música volta no mesmo segundo. */
    private var videoDeMusica = false
    private var filaDaMusica: List<Resultado> = emptyList()
    private var indiceDaMusica = 0
    private var buscandoClipe = false
    private var faixaDaMusica: Resultado? = null
    private var clipeAbertoUrl = ""
    private var capaDaMusica: android.graphics.Bitmap? = null
    private var musicaTocavaAntes = false
    private var pedidoClipe = 0
    private val clipesProntos = java.util.concurrent.ConcurrentHashMap<String, Resultado>()
    private var ultimoPrefetch = ""

    private fun normalizar(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    private val palavrasComuns = setOf("feat", "com", "part", "video", "oficial", "official", "clipe", "audio", "lyrics", "letra", "the", "and", "dos", "das")
    private fun palavras(s: String): Set<String> = normalizar(s).split(" ").filter { it.length > 2 && it !in palavrasComuns }.toSet()
    private fun ehAoVivo(s: String): Boolean = normalizar(s).let { it.contains("ao vivo") || it.contains(" live") || it.startsWith("live") || it.contains("acustico") }

    /**
     * Acha o vídeo que corresponde à faixa: entre os resultados de 30 s a 15 min, pontua quem tem as palavras do título e o artista,
     * é "ao vivo" só se a faixa for, e tem a duração mais próxima da da faixa (a mais forte pista de ser a mesma gravação).
     */
    private fun acharClipe(titulo: String, artista: String, duracaoSeg: Long = 0L): Resultado? = try {
        val lista = YouTube.buscar("videoclipe $titulo $artista", false).filter { it.duracaoSeg in 30..900 }
        val pt = palavras(titulo.replace(Regex("\\(.*?\\)"), " ")); val pa = palavras(artista)
        val aoVivo = ehAoVivo(titulo)
        lista.withIndex().maxByOrNull { (pos, r) ->
            val rt = normalizar(r.titulo + " " + r.canal)
            var pts = YouTube.pontosOficial(r, "$titulo $artista", pos)
            if (pt.isNotEmpty()) { val ach = pt.count { rt.contains(it) }; pts += 4.0 * ach / pt.size - (if (ach * 2 < pt.size) 3.0 else 0.0) }
            if (pa.isNotEmpty() && pa.any { rt.contains(it) }) pts += 2.0
            pts += if (ehAoVivo(r.titulo) == aoVivo) 1.0 else -3.0
            if (duracaoSeg > 0) {
                val dif = kotlin.math.abs(r.duracaoSeg - duracaoSeg)
                pts += when { dif <= 8 -> 4.0; dif <= 20 -> 2.0; dif <= 45 -> 0.0; else -> -2.0 }
            }
            pts
        }?.value
    } catch (e: Exception) { null }

    /**
     * Quem deu play por último manda: o celular começou a tocar de verdade enquanto o app tocava (música ou vídeo). O app pára e a tela
     * vai para o Tocando agora do celular (se o vídeo estava aberto, ele esmaece e sai; da Mídia ou do Início, abre a música).
     */
    private fun celularAssumiu() {
        if (!(playerMidia.ativo && playerMidia.tocando)) return
        val noVideo = telaVideo.aberto
        if (musica.emLocal) playerMidia.parar() else playerMidia.pausar()
        if (girando) return
        if (noVideo) {
            val de = tela(atual)
            relogio.removeCallbacks(amostradorVideo)
            telaVideo.esmaecerSaida {
                videoDeMusica = false
                telaVideo.mostrarSeletor(false)
                telaVideo.fechar()
                playerMidia.parar()
                if (de !== telaMusica) de.visibility = View.GONE
            }
            if (de !== telaMusica) de.visibility = View.INVISIBLE
            telaMusica.visibility = View.VISIBLE
            mudarEstado("musica")
            telaMusica.atualizar()
            relogio.postDelayed({ aoChegar("musica") }, 420)
        } else if (atual == "midia" || atual == "inicio") {
            ir("musica")
        }
    }

    /** Música do app tocando: já deixa o clipe achado e o endereço dele resolvido, para o seletor abrir sem demora. */
    private fun adiantarClipe() {
        val faixa = playerMidia.item ?: return
        if (playerMidia.ehVideo || !playerMidia.tocando || faixa.url == ultimoPrefetch) return
        ultimoPrefetch = faixa.url
        clipesProntos[faixa.url]?.let { playerMidia.prefetchVideo(it); return }      // veio de um vídeo: o vínculo já é exato
        val tit = faixa.titulo; val art = faixa.canal.removeSuffix(" - Topic"); val dur = faixa.duracaoSeg
        Thread {
            try { Thread.sleep(4000) } catch (e: Exception) { }
            val achado = acharClipe(tit, art, dur) ?: return@Thread
            relogio.post { clipesProntos[faixa.url] = achado; playerMidia.prefetchVideo(achado) }
        }.start()
    }

    private fun abrirVideo(lista: List<Resultado>, i: Int, inicioMs: Long = 0L) {
        videoDeMusica = false
        telaVideo.mostrarSeletor(true)      // todo vídeo tem o seletor: a nota leva para Tocando agora com o áudio dele
        if (!telaVideo.aberto) origemVideo = atual
        telaMidia.fecharTeclado()
        if (musica.tocando) musica.pausar()
        musica.pausarCelular()      // o celular que estiver tocando pára (mesmo que ele não avise que está tocando)
        musica.appVaiTocar()        // e o que ele devolver enquanto o vídeo carrega é eco do Bluetooth
        val entrando = !telaVideo.aberto && apareceu
        playerMidia.tocarVideo(lista, i, inicioMs)
        telaVideo.abrir(esmaecendo = entrando)
        relogio.removeCallbacks(amostradorVideo); relogio.post(amostradorVideo)
    }

    private fun fecharVideo() {
        val voltarMusica = videoDeMusica
        val pos = playerMidia.posicaoMs
        val estavaTocando = playerMidia.tocando
        videoDeMusica = false
        telaVideo.mostrarSeletor(false)
        telaVideo.fechar()
        pedidoClipe++        // um clipe que ainda estava sendo procurado não abre mais
        val faixa = faixaDaMusica
        when {
            // trocou de vídeo dentro do player: a música que volta é a do vídeo que estava passando (pesquisada como música)
            voltarMusica && playerMidia.item != null && playerMidia.ehVideo && playerMidia.item?.url != clipeAbertoUrl ->
                musicaDoVideo(playerMidia.item!!, pos, estavaTocando, irParaMusica = false)
            // o clipe ainda nem tinha começado: a música segue de onde parou
            voltarMusica && faixa != null && !playerMidia.ehVideo -> if (musicaTocavaAntes) musica.tocar()
            voltarMusica && filaDaMusica.isNotEmpty() -> musica.tocarDoYoutube(filaDaMusica, indiceDaMusica, pos, estavaTocando)
            else -> playerMidia.parar()
        }
    }

    /**
     * Toque na nota do seletor dentro do player. Vídeo que veio de uma música: volta para a música (como antes). Vídeo aberto pela
     * Mídia (ou por voz): vira a música de Tocando agora, pesquisando o mesmo nome como música (não reaproveita o áudio do vídeo).
     */
    private fun seletorParaMusica() {
        if (videoDeMusica) { fecharVideoAnimado(); return }
        val item = playerMidia.item ?: return
        if (!playerMidia.ehVideo || girando) return
        pedidoClipe++
        val pos = playerMidia.posicaoMs
        playerMidia.pausar()
        musicaDoVideo(item, pos, true, irParaMusica = true)      // o vídeo esmaece sobre Tocando agora quando a música estiver pronta
    }

    /** Tira da legenda do vídeo o que não é o nome da música: "(Clipe Oficial)", "[Official Video]", "Lyric Video"... */
    private fun nomeDaMusica(tituloVideo: String): String =
        tituloVideo.replace(Regex("""[\(\[][^\)\]]*[\)\]]"""), " ")
            .replace(Regex("""(?i)(clipe oficial|v[ií]deo ?clipe|v[ií]deo oficial|official (music )?video|official audio|lyric video)"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

    /**
     * Vídeo que vira música: música é música, vídeo é vídeo. Pesquisa o mesmo nome na busca de MÚSICAS do YouTube (a gravação oficial)
     * e toca a primeira, no mesmo segundo; o vínculo com o vídeo fica guardado, então o ícone de vídeo reabre exatamente esse vídeo.
     */
    private fun musicaDoVideo(v: Resultado, inicioMs: Long, tocar: Boolean, irParaMusica: Boolean) {
        Thread {
            val consulta = ("áudio " + nomeDaMusica(v.titulo) + " " + v.canal.removeSuffix(" - Topic")).trim()
            val lista = try { YouTube.buscar(consulta, true) } catch (e: Exception) { emptyList() }
            relogio.post {
                if (lista.isEmpty()) {
                    // sem música para esse vídeo: ele continua na tela, pausado
                    Aviso.mostrar(this, "Não achei a música deste vídeo")
                    if (!irParaMusica) playerMidia.parar()
                } else {
                    clipesProntos[lista[0].url] = v
                    telaMidia.fecharTeclado()
                    // a imagem do vídeo serve de capa até a capa da música chegar (nada de quadrado de cor sólida)
                    playerMidia.capa?.let { playerMidia.usarCapaProvisoria(lista[0].url, it) }
                    if (irParaMusica && telaVideo.aberto) {
                        // Tocando agora fica por baixo e o vídeo esmaece sobre ela
                        val de = tela(atual)
                        relogio.removeCallbacks(amostradorVideo)
                        telaVideo.esmaecerSaida {
                            videoDeMusica = false
                            telaVideo.mostrarSeletor(false)
                            telaVideo.fechar()
                            if (de !== telaMusica) de.visibility = View.GONE
                        }
                        // o menu de trás NÃO aparece: só o vídeo esmaecendo e, por baixo dele, direto a tela da música
                        if (de !== telaMusica) de.visibility = View.INVISIBLE
                        telaMusica.visibility = View.VISIBLE
                        mudarEstado("musica")
                        musica.tocarDoYoutube(lista, 0, inicioMs, tocar)
                        telaMusica.atualizar()
                        relogio.postDelayed({ aoChegar("musica") }, 420)
                    } else {
                        musica.tocarDoYoutube(lista, 0, inicioMs, tocar)
                        if (irParaMusica) ir("musica")
                    }
                }
            }
        }.start()
    }

    /** Seletor da tela Tocando agora: abre o clipe da mesma faixa no mesmo segundo; a música para. */
    private fun videoDaMusica() {
        if (!musica.emLocal || buscandoClipe || telaVideo.aberto) return
        val faixa = playerMidia.item ?: return
        val pos = playerMidia.posicaoMs
        filaDaMusica = playerMidia.fila; indiceDaMusica = playerMidia.indice
        faixaDaMusica = faixa; capaDaMusica = playerMidia.capa
        musicaTocavaAntes = playerMidia.tocando
        val titulo = musica.titulo; val artista = musica.artista
        val meuPedido = ++pedidoClipe
        // já abre na tela de carregamento do vídeo (a música pausa), sem aviso escrito
        origemVideo = atual
        musica.pausar()
        videoDeMusica = true
        telaVideo.mostrarSeletor(true)
        telaVideo.esperar(faixa.titulo, "$artista · Clipe oficial")
        fun abrirClipe(achado: Resultado) {
            if (meuPedido != pedidoClipe || !telaVideo.aberto) return
            clipeAbertoUrl = achado.url
            abrirVideo(listOf(achado.copy(titulo = titulo, canal = "$artista · Clipe oficial")), 0, pos)
            videoDeMusica = true
            telaVideo.mostrarSeletor(true)
        }
        val pronto = clipesProntos[faixa.url]
        if (pronto != null) { abrirClipe(pronto); return }
        buscandoClipe = true
        Thread {
            val achado = acharClipe(titulo, artista, faixa.duracaoSeg)
            relogio.post {
                buscandoClipe = false
                if (meuPedido != pedidoClipe) return@post
                if (achado == null) {
                    Aviso.mostrar(this, "Não achei o clipe desta música")
                    telaVideo.fechar(); videoDeMusica = false; telaVideo.mostrarSeletor(false)
                    if (musicaTocavaAntes) musica.tocar()
                } else { clipesProntos[faixa.url] = achado; abrirClipe(achado) }
            }
        }.start()
    }


    /** Fecha o vídeo esmaecendo sobre a tela de baixo (botão X, tecla voltar e gesto de voltar). */
    private fun fecharVideoAnimado() {
        if (girando || !apareceu) { fecharVideo(); return }
        girando = true
        tela(origemVideo).visibility = View.VISIBLE
        telaVideo.esmaecerSaida { girando = false; fecharVideo() }
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
            if (destinoCubo.isNotEmpty() && destinoCubo != "inicio") aplicarTema(atual)
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
        Animacao.pausada = true
        painelVolume.fechar()
        telaOciosa.atualizar()
        telaOciosa.alpha = 0f
        telaOciosa.visibility = View.VISIBLE
        telaOciosa.animate().alpha(1f).setDuration(600).start()
        fundo.parar()
        val lp = window.attributes; lp.screenBrightness = if (Ajustes.escurecerOcioso) 0.05f else Ajustes.brilho / 100f; window.attributes = lp
    }

    /** Brilho da tela escolhido em Configurações (fora do modo ocioso). */
    private fun aplicarBrilho() {
        val lp = window.attributes; lp.screenBrightness = Ajustes.brilho / 100f; window.attributes = lp
    }

    private fun sairOcioso() {
        ultimoToque = android.os.SystemClock.uptimeMillis()
        if (!ocioso) return
        ocioso = false
        Animacao.pausada = false
        telaOciosa.animate().cancel()
        telaOciosa.visibility = View.GONE
        if (apareceu) fundo.iniciar()
        aplicarBrilho()
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
        // teclado da Mídia aberto: tocar no cabeçalho fecha o teclado (o toque segue para o botão tocado)
        if (e.actionMasked == MotionEvent.ACTION_DOWN && atual == "midia" && telaMidia.tecladoVisivel && e.rawY < 104.d()) telaMidia.fecharTeclado()
        if (e.actionMasked == MotionEvent.ACTION_DOWN && atual == "receitas" && telaReceitas.tecladoVisivel && e.rawY < 104.d()) telaReceitas.fecharTeclado()
        if (girando && !capturado) return true       // durante a animação, toques são ignorados
        if ((atual != "inicio" || telaVideo.aberto) && !modalEnergia.aberto && !modalAtualizacao.aberto && !faixaAssistente.aberta && alarmeTocando == null) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    arrastando = e.rawX < BORDA_GESTO.d() && !(atual == "midia" && !telaVideo.aberto && telaMidia.areaProtegida(e.rawX, e.rawY)) && !(atual == "receitas" && telaReceitas.areaProtegida(e.rawX, e.rawY))
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
                        modoCubo = !emDetalheAtual() && !telaVideo.aberto      // com vídeo aberto o gesto fecha por esmaecimento
                        if (modoCubo) {
                            if (telaVideo.aberto) { destinoCubo = ""; deCubo = telaVideo; paraCubo = tela(origemVideo); telaVideo.cobrir(true) }
                            else { destinoCubo = destinoVoltar(); deCubo = tela(atual); paraCubo = tela(destinoCubo) }
                            if (destinoCubo.isNotEmpty() && destinoCubo != "inicio") { aplicarTema(destinoCubo); preencher(destinoCubo) }      // a tela de destino já aparece na cor dela
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

    private fun aoMudarAlarmes() { inicio.definirProximoAlarme(alarmes.proximoTexto(), alarmes.proxima()?.first?.nome?.ifBlank { null }) }

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
        musicaTocavaNoAlarme = musica.tocando
        videoTocavaNoAlarme = telaVideo.aberto && playerMidia.tocando
        if (musica.tocando) musica.pausar()
        if (videoTocavaNoAlarme) playerMidia.pausar()
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val canal = android.media.AudioManager.STREAM_MUSIC
        val limite = Math.round(audio.getStreamMaxVolume(canal) * PainelVolume.LIMITE_REAL)
        val piso = Math.round(limite * Ajustes.pisoAlarme())
        volumeAntesDoAlarme = audio.getStreamVolume(canal)
        painelVolume.suspender(true)      // o alarme sobe o volume sozinho: a caixa não corrige enquanto ele toca
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
            painelVolume.suspender(false)
        }
        if (soneca && !alarmeEhTeste) alarmes.adiar(a)
        alarmeEhTeste = false
        // o que tocava antes do alarme volta a tocar do mesmo ponto (com um fade-in curto)
        if (musicaTocavaNoAlarme) { musicaTocavaNoAlarme = false; musica.tocar() }
        if (videoTocavaNoAlarme) { videoTocavaNoAlarme = false; if (!playerMidia.tocando) playerMidia.alternar() }
    }

    private var musicaTocavaNoAlarme = false
    private var videoTocavaNoAlarme = false

    /** Toca o som de início só quando o aparelho acabou de ligar (não quando o app é só reaberto ou reinstalado). */
    private fun aparelhoAcabouDeLigar(): Boolean {
        val prefs = getSharedPreferences("sistema", MODE_PRIVATE)
        val boot = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        if (Math.abs(boot - prefs.getLong("boot", 0L)) <= 15_000) return false
        prefs.edit().putLong("boot", boot).apply()
        return true
    }

    private var bootNovo = false
    private var aquecimento: android.media.AudioTrack? = null

    /**
     * A saída de áudio "dorme" e engole o começo do primeiro som. Por isso, assim que o app abre, toca um silêncio contínuo
     * para acordá-la; o som de início sai inteiro quando a interface começa a aparecer, e o silêncio para quando ele termina.
     */
    private fun aquecerAudio() {
        try {
            val taxa = 44100
            val faixa = android.media.AudioTrack(
                android.media.AudioManager.STREAM_MUSIC, taxa, android.media.AudioFormat.CHANNEL_OUT_STEREO,
                android.media.AudioFormat.ENCODING_PCM_16BIT, taxa * 4, android.media.AudioTrack.MODE_STREAM
            )
            aquecimento = faixa
            faixa.play()
            Thread {
                val silencio = ByteArray(taxa / 5 * 4)
                try { while (aquecimento === faixa) faixa.write(silencio, 0, silencio.size) } catch (_: Throwable) {}
            }.start()
        } catch (_: Throwable) { aquecimento = null }
    }

    private fun pararAquecimento() {
        val f = aquecimento ?: return
        aquecimento = null
        try { f.stop(); f.release() } catch (_: Throwable) {}
    }

    /** Toca o som de início do começo ao fim (só chamado quando o aparelho acabou de ligar). */
    private fun tocarSomDeInicio() {
        Som.tocar(this, R.raw.som_inicio) { relogio.postDelayed({ pararAquecimento() }, 300) }
    }

    /**
     * Desligar ou reiniciar: cobre a tela com a nossa (por cima da janela do Android), apaga a fita, solta o celular que
     * está tocando, toca o som de desligar e só então manda o aparelho desligar ou reiniciar.
     */
    private fun sequenciaEnergia(desligar: Boolean) {
        painelVolume.fechar()
        cobertura.mostrar(if (desligar) "Desligando…" else "Reiniciando…")
        // Tudo termina direito ANTES de desligar, para a caixa voltar limpa: Alexa e microfone, música e vídeo, a fita, o Bluetooth
        // (aparelhos desconectados e serviço desligado de vez) e o Wi-Fi. Os ajustes já ficam gravados; Wi-Fi e Bluetooth voltam sozinhos.
        assistente.parar()
        playerMidia.parar()
        musica.desligar()
        Ajustes.definirRestaurarRedes(true)
        led.apagarParaDesligar {
            bluetooth.desligarLimpo {
                wifi.desligarLimpo {
                    Som.tocar(this, R.raw.som_desligar) { executarEnergia(desligar) }
                }
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
            cobertura.esconder(); led.retomar(); recuperarDepoisDeFalhaDeEnergia()
            modalEnergia.falhou("O aparelho não deu permissão de energia a este app. É preciso instalá-lo como app de sistema.")
        } catch (e: Exception) {
            cobertura.esconder(); led.retomar(); recuperarDepoisDeFalhaDeEnergia()
            modalEnergia.falhou("O aparelho recusou o comando (${e.javaClass.simpleName}).")
        }
    }

    /** O aparelho não desligou: volta tudo o que foi fechado (rede, Bluetooth, Alexa). */
    private fun recuperarDepoisDeFalhaDeEnergia() {
        Ajustes.definirRestaurarRedes(false)
        wifi.definirLigado(true)
        bluetooth.definirLigado(true)
        assistente.iniciar()
        musica.iniciar()
    }

    /** Ao ligar depois de um desligamento do app: Wi-Fi e Bluetooth voltam sozinhos, como estavam. */
    private fun restaurarRedesSeDesligadas() {
        if (!Ajustes.restaurarRedes) return
        Ajustes.definirRestaurarRedes(false)
        wifi.definirLigado(true)
        bluetooth.definirLigado(true)
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
            "receitas" -> if (!telaReceitas.voltar()) ir("inicio")
            "wifi" -> if (!telaWifi.voltar()) ir("inicio")
            "bluetooth" -> if (!telaBluetooth.voltar()) ir("inicio")
            "musica" -> ir(destinoVoltar())
            "led" -> ir("inicio")
            "alarmes" -> if (!telaAlarmes.voltar()) ir("inicio")
            "clima" -> ir("inicio")
            "config" -> if (!telaConfig.voltar()) ir("inicio")
        }
    }
}
