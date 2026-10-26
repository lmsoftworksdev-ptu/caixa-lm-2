package br.com.lmsoftworks.caixa2

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.Calendar
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Igual ao Log do Android, mas também grava num arquivo (files/led.log) que sobrevive ao reinício da caixa. */
private object Log {
    @Volatile var arquivo: java.io.File? = null
    private val formato = java.text.SimpleDateFormat("dd HH:mm:ss.SSS", java.util.Locale.US)

    @Synchronized private fun grava(nivel: String, msg: String) {
        val f = arquivo ?: return
        if (msg.startsWith("som:")) return
        try {
            if (f.length() > 300_000) f.delete()
            f.appendText("${formato.format(java.util.Date())} $nivel $msg\n")
        } catch (e: Exception) { }
    }

    fun d(tag: String, msg: String) { android.util.Log.d(tag, msg); grava("D", msg) }
    fun w(tag: String, msg: String, e: Throwable? = null) {
        if (e != null) android.util.Log.w(tag, msg, e) else android.util.Log.w(tag, msg)
        grava("W", msg)
    }
}

/**
 * Controle da fita de LED (Zengge, Bluetooth LE). O protocolo foi decifrado do app oficial e está em
 * docs/protocolo-led.md. Aqui ficam a conexão, o estado (cor, efeito, brilho, ligado) e os efeitos,
 * que o app calcula e manda para a fita umas 10 vezes por segundo.
 */
class LedControle(contexto: Context, private val aoMudar: () -> Unit) {

    companion object {
        private const val TAG = "CaixaLed"
        const val ENDERECO = "E4:98:BB:50:6A:F0"       // LEDnetWF020033506AF0 (visto na busca real)
        private val UUID_ESCRITA = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        private val UUID_AVISOS = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        private val UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val INTERVALO_EFEITO_MS = 100L
        private const val INTERVALO_RITMO_MS = 50L
        private const val DIFERENCA_MIN = 12          // só reenvia a cor se mudou o bastante
        private const val ESPACO_ENVIO_MS = 20L
        private const val ESPACO_COR_MS = 110L        // o app oficial manda ~9 por segundo sem a fita travar (capturado)
        private const val SUAVIZAR = 0x06            // último byte do quadro 36 = tempo do fade da fita. Zengge: 0x14. No Rítmico: 0x06 (0x02/0x03 ficaram secos demais; o resto da suavidade vem do seguidor do app)
        private const val SUAVIZAR_FADE = 0x14        // Fade: um passo a cada 110 ms e a fita suaviza por mais tempo que isso, então os passos se encadeiam (0x0c deixava degraus)
        private const val TAU_AMBI_MS = 400L
        private const val SUAVIZAR_AMBI = 0x14      // fade curto da fita: a suavização principal é feita no app
        private const val SUAVIZAR_LENTO = 0x14      // arco-íris e respirar: fade longo do Zengge, sem degraus visíveis
        private const val SEM_AVISO_MS = 3_000L       // fita boa avisa em ~0,2 s; ligação aberta e muda por tanto tempo: solta e reconecta
        private const val ESPACO_PULSO_MS = 40L       // um pulso de batida não espera os 110 ms normais: sai quase na hora
        private const val SEGURAR_MS = 110L         // tempo mínimo do pulso aceso (a fita recebe uma cor a cada ~110 ms)
        private const val BRILHO_SEGUNDA = 0.7f      // a segunda cor do Rítmico nunca passa de 70% do brilho escolhido; a principal chega a 100%
        private const val TAU_MS = 120.0              // o brilho de um ataque cai pela metade a cada ~85 ms (120 ms de constante)
        private const val REPETIR_MS = 1000L        // no modo contínuo, repete a última cor se nada mudou
        val MODOS = listOf("ritmo" to "Rítmico", "arco" to "Arco-íris", "ambilight" to "Ambilight", "fixo" to "Fixo")
    }

    private val ctx = contexto.applicationContext
    init {
        Log.arquivo = java.io.File(ctx.filesDir, "led.log")
        Log.d("CaixaLed", "---- app iniciado ----")
    }
    private val prefs = ctx.getSharedPreferences("led", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val fita: FitaBle = FitaBle(ctx, ENDERECO, { Log.d(TAG, it) }, { !TesteLed.ativo }, object : FitaBle.Ouvinte {
        override fun aoConectar() = aoPronto()
        override fun aoDesconectar() = aoPerder()
        override fun aoAviso(dados: ByteArray) = aoAvisoDaFita()
        override fun aoEscritaConcluida() { handler.postDelayed({ enviando = false; bombear() }, ESPACO_ENVIO_MS) }
        override fun aoMudarEstado() { conectando = fita.tentando; aoMudar() }
    })
    private var contador = 0
    private val fila = ArrayDeque<ByteArray>()
    private var corPendente: ByteArray? = null
    private var enviando = false
    private var inicioEnvio = 0L
    private var contEnvios = 0
    private var contFalhas = 0
    private var falhasSeguidas = 0
    private var ultimoEnvioCor = 0L
    private var ultimoContador = 0L
    private var ativo = false
    private var recebeuAviso = false
    private var semConexaoDesde = 0L
    private var ultimaCorEnviada = -1
    private var visualizador: Visualizer? = null
    @Volatile private var nivelAudio = 0f
    private var pico = 1f
    private var mediaGrave = 0f
    private var ultimaBatida = 0L
    private val ataqueGrave = Ataque(250, 0.01f)
    private val ataqueMedio = Ataque(400, 0.012f)
    private var ultimoQuadro = 0L
    /** Faixa de áudio do próprio app (música ou vídeo do YouTube): o dumpsys ignora o app, então ela é informada por aqui. */
    @Volatile var sessaoExterna = -1
    @Volatile private var sessaoForcada = -1          // só no teste do ritmo (arquivo tocado pelo próprio app)
    private var inicioTeste = 0L
    @Volatile private var seguraAte = 0L

    /**
     * Gatilho de ataque de uma faixa: dispara só quando a energia dá um SALTO seco e forte (o "taque" do bumbo ou da
     * caixa), não quando fica alta por muito tempo (baixo, voz). Calibrado com os níveis reais gravados no aparelho
     * (teste-ritmo/bateria.py). Devolve a força do ataque (0 = nenhum).
     */
    private class Ataque(private val intervaloMin: Long, private val piso: Float) {
        private val historico = FloatArray(3)
        private var n = 0
        var pico = 1e-6f; var ultima = 0L

        fun processa(v: Float, agora: Long, fator: Float, fracao: Float): Float {
            pico = max(v, pico * 0.99f)
            val ref = if (n >= 3) (historico[0] + historico[1] + historico[2]) / 3f else Float.MAX_VALUE
            val bate = agora - ultima >= intervaloMin && v > ref * fator && v > pico * fracao && v > piso
            historico[n % 3] = v; n++
            if (!bate) return 0f
            ultima = agora
            return min(1f, v / pico)
        }
    }

    /** Na hora da batida: manda o pulso para a fita sem esperar o próximo ciclo de 50 ms. */
    private fun dispararPulso(agora: Long) {
        instanteBatida = agora
        handler.post {
            if (conectado && ligado && modo == "ritmo") {
                val c = corFinal(SystemClock.uptimeMillis() / 1000f)
                if (enviarCor(c, true)) ultimaCorEnviada = c
            }
        }
    }

    private fun registrarBatida(banda: String, forca: Float, grave: Float, medio: Float) {
        android.util.Log.d("RitmoTeste", "t=%d %s forca=%.2f grave=%.1f medio=%.1f".format(SystemClock.uptimeMillis() - inicioTeste, banda, forca, grave, medio))
    }
    private var sessaoAudio = -1
    private var monitorAtivo = false
    private var ultimoRegistro = 0L

    // ----- estado (guardado no aparelho) -----
    var cor: Int = prefs.getInt("cor", Color.parseColor("#2F7BFF")); private set
    var cor2: Int = prefs.getInt("cor2", 0); private set       // 0 = sem segunda cor; no Rítmico ela acende nas batidas médias
    @Volatile private var batidaMedia = false
    // o efeito Fade foi retirado a pedido do usuário: quem tinha ele salvo volta para Fixo
    var modo: String = (prefs.getString("modo", "fixo") ?: "fixo").let { if (it == "respira") "fixo" else it }; private set
    var brilho: Int = prefs.getInt("brilho", 80); private set
    var ligado: Boolean = prefs.getBoolean("ligado", true); private set
    var sensibilidade: Int = prefs.getInt("sensibilidade", 50); private set
    var conectado = false; private set      // ligação Bluetooth pronta para escrever
    var conectando = false; private set
    // "online" só quando a fita realmente respondeu (aviso do FF02), para a tela não mostrar conectado à toa
    val online: Boolean get() = conectado && recebeuAviso
    val conectandoParaTela: Boolean get() = conectando || (conectado && !recebeuAviso)
    var erro: String? = null; private set

    fun nomeModo(): String = MODOS.firstOrNull { it.first == modo }?.second ?: "Fixo"

    /** Cor que a fita mostra agora (com brilho e efeito aplicados). A prévia da tela usa a mesma. */
    fun corFinal(t: Float): Int {
        if (!ligado) return Color.BLACK
        val base = brilho / 100f
        return when (modo) {
            "arco" -> escalar(Color.HSVToColor(floatArrayOf((t * 40f) % 360f, 1f, 1f)), base)
            "ritmo" -> if (cor2 != 0 && batidaMedia) escalar(cor2, base * BRILHO_SEGUNDA * nivelAudio) else escalar(cor, base * nivelAudio)    // nivelAudio é 1 (aceso) ou 0 (apagado): piscada seca
            "respira" -> escalar(cor, base * fadeCurva(t))
            "ambilight" -> escalar(ambiSuave(), base)
            else -> escalar(cor, base)
        }
    }

    // ---- Ambilight: a fita segue a cor da capa da música ou do vídeo na tela, com um fade longo entre as cores ----
    /** Cor alvo vinda da tela (capa ou vídeo). null = nada tocando: a fita volta à cor escolhida pelo usuário. Preto = apagar. Não é gravada. */
    @Volatile private var alvoAmbiente: Int? = null
    private var ambiAtual = -1
    private var ambiEm = 0L

    fun definirCorAmbiente(c: Int?) { alvoAmbiente = c }

    /** Aproxima a cor mostrada do alvo aos poucos (constante de tempo ~0,4 s: a troca leva cerca de 1 s): é o "fade in / fade out" do Ambilight. */
    private fun ambiSuave(): Int {
        val alvo = alvoAmbiente ?: cor
        val agora = SystemClock.uptimeMillis()
        if (ambiAtual == -1) ambiAtual = alvo
        val dt = (agora - ambiEm).coerceIn(0L, 500L)
        ambiEm = agora
        val k = 1f - Math.exp(-dt / TAU_AMBI_MS.toDouble()).toFloat()
        fun passo(a: Int, b: Int) = (a + (b - a) * k).toInt().coerceIn(0, 255)
        ambiAtual = Color.rgb(passo(Color.red(ambiAtual), Color.red(alvo)), passo(Color.green(ambiAtual), Color.green(alvo)), passo(Color.blue(ambiAtual), Color.blue(alvo)))
        return ambiAtual
    }

    /** Respiração do Fade: onda lenta (~4,5 s) com curva perceptual, que desce bem mais baixo que a antiga (50%) e sobe sem "empacar" no pico. */
    private fun fadeCurva(t: Float): Float {
        val onda = 0.5f + 0.5f * sin(t * 1.4f)
        return 0.12f + 0.88f * Math.pow(onda.toDouble(), 1.8).toFloat()
    }

    /** Cor "cheia" do modo atual, sem brilho (para fundo e destaque da interface). */
    fun corDestaque(t: Float): Int =
        if (modo == "arco") Color.HSVToColor(floatArrayOf((t * 40f) % 360f, .9f, 1f)) else cor

    private fun mudouMuito(a: Int, b: Int): Boolean {
        if (b < 0) return true
        val d = max(max(Math.abs(Color.red(a) - Color.red(b)), Math.abs(Color.green(a) - Color.green(b))), Math.abs(Color.blue(a) - Color.blue(b)))
        // apagar de vez (preto) sempre vale, mesmo com diferença pequena
        val minimo = if (modo == "ritmo") DIFERENCA_MIN else if (modo == "ambilight") 8 else 3   // efeitos suaves mandam passos pequenos
        return d >= minimo || (a == Color.BLACK && b != Color.BLACK)
    }

    private fun escalar(c: Int, f: Float): Int {
        val g = max(0f, min(1f, f))
        return Color.rgb((Color.red(c) * g).toInt(), (Color.green(c) * g).toInt(), (Color.blue(c) * g).toInt())
    }

    // ---------------------------------------------------------------- ciclo de vida

    private var receptorRegistrado = false

    /**
     * Ao desligar ou reiniciar a caixa, apaga a fita antes: ela guarda o último estado e, sem isso, voltaria acesa
     * (no último efeito) enquanto a caixa ainda está ligando.
     */
    private val receptorDesligar = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            if (!conectado) return
            Log.d(TAG, "caixa desligando: apagando a fita")
            ativo = false                       // para os efeitos
            fila.clear(); corPendente = null
            val apagar = quadro(0x0b, intArrayOf(0x3b, 0x24, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
            repeat(8) {
                if (fita.escrever(apagar, false)) {
                    try { Thread.sleep(500) } catch (e: Exception) { }   // dá tempo de o comando sair antes de o Bluetooth cair
                    Log.d(TAG, "fita apagada, pode desligar")
                    return
                }
                try { Thread.sleep(100) } catch (e: Exception) { }
            }
        }
    }

    // ----- teste do ritmo por adb: o próprio app toca um arquivo e registra no logcat (etiqueta RitmoTeste) cada batida achada
    //   adb shell am broadcast -a br.com.lmsoftworks.caixa2.TESTE_RITMO --es arquivo /caminho/teste.wav   (sem "arquivo" = parar)
    private var tocadorTeste: android.media.MediaPlayer? = null
    private var modoAntesTeste: String? = null

    private val receptorTeste = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            val arq = i.getStringExtra("arquivo")
            pararTeste()
            if (arq == null) return
            try {
                val mp = android.media.MediaPlayer()
                mp.setDataSource(arq); mp.prepare()
                mp.setOnCompletionListener { pararTeste() }
                tocadorTeste = mp
                modoAntesTeste = modo
                sessaoForcada = mp.audioSessionId
                definirLigado(true); definirModo("ritmo")
                handler.postDelayed({ trocarSessao(sessaoForcada) }, 300)
                handler.postDelayed({ inicioTeste = SystemClock.uptimeMillis(); mp.start(); android.util.Log.d("RitmoTeste", "inicio sens=$sensibilidade") }, 1500)
            } catch (e: Exception) { android.util.Log.d("RitmoTeste", "falhou: ${e.message}") }
        }
    }

    private fun pararTeste() {
        val mp = tocadorTeste ?: return
        tocadorTeste = null
        try { mp.stop() } catch (e: Exception) { }
        mp.release()
        sessaoForcada = -1; inicioTeste = 0L
        modoAntesTeste?.let { definirModo(it) }
        modoAntesTeste = null
        android.util.Log.d("RitmoTeste", "fim")
    }

    /**
     * Antes de desligar ou reiniciar: para os efeitos e apaga a fita (3b 24), insistindo até o comando sair, e avisa em
     * [aoFim] uns instantes depois (para o comando chegar antes de o Bluetooth cair).
     */
    fun apagarParaDesligar(aoFim: () -> Unit) {
        handler.removeCallbacks(salvarDepois)
        salvarAgora(true)          // a próxima vez que ligar volta do mesmo jeito
        ativo = false
        fila.clear(); corPendente = null
        if (!conectado) { aoFim(); return }
        val apagar = quadro(0x0b, intArrayOf(0x3b, 0x24, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        var tentativas = 0
        val tenta = object : Runnable {
            override fun run() {
                if (fita.escrever(apagar, false) || ++tentativas > 12) {
                    Log.d(TAG, "fita apagada para desligar (tentativas=$tentativas)")
                    handler.postDelayed({ aoFim() }, 600)
                } else handler.postDelayed(this, 100)
            }
        }
        handler.post(tenta)
    }

    /** Se o desligar não aconteceu (sem permissão), volta o controle da fita. */
    fun retomar() {
        if (ativo) return
        ativo = true
        handler.postDelayed(ciclo, INTERVALO_EFEITO_MS)
        handler.postDelayed(vigia, 2000)
        aplicar()
    }

    fun iniciar() {
        if (!receptorRegistrado) {
            ctx.registerReceiver(receptorTeste, android.content.IntentFilter("br.com.lmsoftworks.caixa2.TESTE_RITMO"))
            receptorRegistrado = true
            ctx.registerReceiver(receptorDesligar, android.content.IntentFilter(android.content.Intent.ACTION_SHUTDOWN))
        }
        ativo = true
        semConexaoDesde = SystemClock.uptimeMillis()
        fita.iniciar()
        handler.postDelayed(ciclo, INTERVALO_EFEITO_MS)
        handler.postDelayed(vigia, 2000)
        atualizarAudio()
    }

    fun parar() {
        ativo = false
        salvarAgora()
        handler.removeCallbacksAndMessages(null)
        pararAudio()
        fita.parar()
        conectado = false; conectando = false; recebeuAviso = false
        fila.clear(); corPendente = null; enviando = false
    }

    /** Mostra "Fita não encontrada" se faz tempo que não há ligação, e some com isso quando a fita responde. */
    private val vigia = object : Runnable {
        override fun run() {
            if (!ativo) return
            if (!online && !conectado && SystemClock.uptimeMillis() - semConexaoDesde > 15_000 && erro == null) {
                erro = "Fita não encontrada. Confira se ela está ligada na tomada."
                aoMudar()
            }
            handler.postDelayed(this, 2000)
        }
    }

    private val ciclo = object : Runnable {
        override fun run() {
            if (!ativo) return
            // o Fade manda em intervalos fixos: só quando mudava 3+ pontos ele engasgava perto do pico da onda
            if (conectado && ligado && modo != "fixo") {
                val c = corFinal(SystemClock.uptimeMillis() / 1000f)
                val parado = SystemClock.uptimeMillis() - ultimoEnvioCor > REPETIR_MS
                if (mudouMuito(c, ultimaCorEnviada) || (modoContinuo() && parado) || modo == "respira") {   if (enviarCor(c)) ultimaCorEnviada = c }
            }
            handler.postDelayed(this, if (modo == "ritmo") INTERVALO_RITMO_MS else if (modo == "respira") ESPACO_COR_MS else INTERVALO_EFEITO_MS)
        }
    }

    // ---------------------------------------------------------------- Bluetooth LE
    //
    // A conexão agora vive em FitaBle.kt, copiada do app oficial Zengge (ver o comentário da classe).
    // Aqui só ficam as reações aos eventos dela.

    fun conectar() {
        if (TesteLed.ativo || conectando || conectado) return
        fita.iniciar()
        fita.tentarAgora()
    }

    fun tentarDeNovo() {
        erro = null
        semConexaoDesde = SystemClock.uptimeMillis()
        conectar()
        aoMudar()
    }

    /** A ligação caiu ou foi encerrada: limpa a fila; a FitaBle decide quando tentar de novo. */
    private fun aoPerder() {
        handler.removeCallbacks(semAviso)
        val estava = conectado
        conectado = false; recebeuAviso = false
        fila.clear(); corPendente = null; enviando = false
        ultimaCorEnviada = -1
        emContinuo = false
        semConexaoDesde = SystemClock.uptimeMillis()
        if (estava) Log.d(TAG, "ligacao perdida")
        aoMudar()
    }

    /** A fita mandou um aviso pelo FF02: só agora ela está de fato respondendo. */
    private fun aoAvisoDaFita() {
        handler.removeCallbacks(semAviso)
        if (recebeuAviso) return
        recebeuAviso = true
        erro = null
        Log.d(TAG, "fita respondeu (aviso recebido)")
        aoMudar()
    }

    /** Ligação aberta e aviso nunca veio: solta a ligação do jeito do Zengge e deixa a FitaBle tentar de novo. */
    private val semAviso = Runnable {
        if (conectado && !recebeuAviso) {
            Log.w(TAG, "fita nao respondeu em ${SEM_AVISO_MS / 1000} s")
            fita.derrubar("sem aviso da fita")
        }
    }

    /** Conversa inicial igual à do app oficial, e depois aplica o estado guardado. */
    private fun aoPronto() {
        if (conectado) return
        Log.d(TAG, "fita pronta")
        conectado = true; conectando = false; erro = null
        recebeuAviso = fita.recebeuAviso      // a fita às vezes avisa sozinha antes de ficarmos prontos
        contador = 0
        emContinuo = false
        enviando = false
        handler.removeCallbacks(semAviso)
        if (!recebeuAviso) handler.postDelayed(semAviso, SEM_AVISO_MS)
        enfileirar(quadro(0x07, intArrayOf(0x22)))
        val h = Calendar.getInstance()
        val hora = intArrayOf(
            0x10, h.get(Calendar.YEAR) / 100, h.get(Calendar.YEAR) % 100, h.get(Calendar.MONTH) + 1,
            h.get(Calendar.DAY_OF_MONTH), h.get(Calendar.HOUR_OF_DAY), h.get(Calendar.MINUTE),
            // dia da semana como o app oficial: segunda = 1 ... domingo = 7 (o código antigo mandava 0 para domingo)
            h.get(Calendar.SECOND), (h.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1, 0x00, 0x0f
        )
        enfileirar(quadro(0x0b, hora))
        enfileirar(quadro(0x0b, hora))
        enfileirar(quadro(0x0a, intArrayOf(0x81, 0x8a, 0x8b)))
        aplicar()
        aoMudar()
    }

    // ---------------------------------------------------------------- envio

    /** [contador 2 bytes] 80 00 00 [tamanho-1] [tamanho] [tipo] [comando...] [soma do comando] */
    private fun quadro(tipo: Int, cmd: IntArray): ByteArray {
        contador = (contador + 1) and 0xffff
        val tam = cmd.size + 2
        val soma = cmd.sum() and 0xff
        val b = ArrayList<Int>()
        b.add(contador shr 8); b.add(contador and 0xff)
        b.add(0x80); b.add(0x00); b.add(0x00)
        b.add(tam - 1); b.add(tam)
        b.add(tipo)
        cmd.forEach { b.add(it) }
        b.add(soma)
        return ByteArray(b.size) { b[it].toByte() }
    }

    /** A fita parou de responder: fecha a conexão por inteiro, avisa a tela e tenta de novo em instantes. */
    private fun perderConexao() {
        fita.derrubar("envio travado")
    }

    /** Só o relógio é escrito com resposta (WriteReq), como no Zengge; todo o resto é WriteCmd. */
    // (no teste de 25/10 a fita nunca confirmou o relógio com resposta e o Android ficou ocupado: vai sem resposta)
    private fun precisaResposta(q: ByteArray) = false

    private fun enfileirar(q: ByteArray) { fila.add(q); bombear() }

    /** Efeitos mudam a cor muitas vezes por segundo: usam o modo música da fita (comando 36), como o app oficial. */
    private fun modoContinuo() = modo == "ritmo" || modo == "arco" || modo == "respira" || modo == "ambilight"
    private var emContinuo = false

    private var pendenteClara = false
    private var pendenteUrgente = false
    @Volatile private var instanteBatida = 0L
    private var somaAtraso = 0L; private var nAtraso = 0; private var maxAtraso = 0L
    private var contClaras = 0
    @Volatile private var contBatidas = 0

    /** Devolve false se ficou de fora: um pulso aceso do Rítmico que ainda não saiu não pode ser trocado por "apagado" (a batida sumiria). */
    private fun enviarCor(c: Int, urgente: Boolean = false): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val clara = max(r, max(g, b)) > 40
        if (modo == "ritmo" && corPendente != null && pendenteClara && !clara) return false
        pendenteClara = clara
        pendenteUrgente = urgente && modo == "ritmo"
        // último byte do quadro 36 = tempo do fade da fita: curto no rítmico, longo nos efeitos suaves
        val fade = if (modo == "ritmo") SUAVIZAR else if (modo == "ambilight") SUAVIZAR_AMBI else if (modo == "respira") SUAVIZAR_FADE else SUAVIZAR_LENTO
        corPendente = if (modoContinuo()) quadro(0x0b, intArrayOf(0x36, 0xd1, r, g, b, 0x00, 0x00, fade))
        else quadro(0x0b, intArrayOf(0x31, r, g, b, 0x00, 0x00, 0x0f))
        bombear()
        return true
    }

    private fun bombear() {
        // se o aviso de "escrita terminada" não vier, não fica travado para sempre (a FitaBle solta a sua em 2 s)
        if (enviando && SystemClock.uptimeMillis() - inicioEnvio > 2200) enviando = false
        if (enviando || !conectado) return
        val agora = SystemClock.uptimeMillis()
        var deFila = true
        val proximo = fila.poll() ?: run {
            deFila = false
            val p = corPendente ?: return
            val espera = (if (pendenteUrgente) ESPACO_PULSO_MS else ESPACO_COR_MS) - (agora - ultimoEnvioCor)
            if (espera > 0) { handler.postDelayed({ bombear() }, espera); return }
            corPendente = null
            if (pendenteClara) {
                contClaras++
                if (pendenteUrgente && instanteBatida != 0L) {
                    val a = agora - instanteBatida
                    if (a in 0..2000) { somaAtraso += a; nAtraso++; if (a > maxAtraso) maxAtraso = a }
                    instanteBatida = 0L
                }
            }
            pendenteUrgente = false
            pendenteClara = false
            ultimoEnvioCor = agora
            p
        }
        enviando = true
        inicioEnvio = SystemClock.uptimeMillis()
        if (!fita.escrever(proximo, precisaResposta(proximo))) {
            contFalhas++
            falhasSeguidas++
            enviando = false
            // o Android recusou (ainda ocupado): comandos de verdade (ligar, modo...) voltam para o começo da fila
            if (deFila) fila.addFirst(proximo)
            if (falhasSeguidas >= 12) {
                Log.w(TAG, "envio travado, reconectando")
                falhasSeguidas = 0
                perderConexao()
            } else handler.postDelayed({ bombear() }, 50)
        } else { contEnvios++; falhasSeguidas = 0 }
        val agora2 = SystemClock.uptimeMillis()
        if (agora2 - ultimoContador > 3000) {
            ultimoContador = agora2
            Log.d(TAG, "envios=$contEnvios falhas=$contFalhas fila=${fila.size} batidas=$contBatidas pulsosEnviados=$contClaras atrasoMedio=${if (nAtraso > 0) somaAtraso / nAtraso else -1}ms atrasoMax=${maxAtraso}ms")
            contBatidas = 0; contClaras = 0; somaAtraso = 0; nAtraso = 0; maxAtraso = 0
            contEnvios = 0; contFalhas = 0
        }
    }

    /** Manda para a fita o estado atual (ligado/desligado e a cor). */
    private fun aplicar() {
        if (!conectado) return
        if (!ligado) {
            enfileirar(quadro(0x0b, intArrayOf(0x3b, 0x24, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
            emContinuo = false
            ultimaCorEnviada = -1
            return
        }
        enfileirar(quadro(0x0b, intArrayOf(0x3b, 0x23, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        if (modoContinuo() && !emContinuo) {
            enfileirar(quadro(0x0b, intArrayOf(0x37, 0x00, 0x00))) // como o app oficial antes do fluxo 36
            emContinuo = true
        } else if (!modoContinuo() && emContinuo) {
            emContinuo = false                                     // o comando de cor fixa (31) já tira a fita do fluxo
        }
        val c = corFinal(SystemClock.uptimeMillis() / 1000f)
        ultimaCorEnviada = c
        enviarCor(c)
    }

    // ---------------------------------------------------------------- ações da tela

    private val salvarDepois = Runnable { salvarAgora() }

    /** [sincrono] = grava no disco já (usado antes de desligar ou reiniciar, quando o app pode ser morto a qualquer instante). */
    private fun salvarAgora(sincrono: Boolean = false) {
        val e = prefs.edit().putInt("cor2", cor2).putInt("sensibilidade", sensibilidade).putInt("cor", cor).putString("modo", modo).putInt("brilho", brilho).putBoolean("ligado", ligado)
        if (sincrono) { if (!e.commit()) Log.w(TAG, "nao foi possivel gravar o estado da fita") } else e.apply()
    }

    /** Guarda no aparelho com um pequeno atraso, para não gravar a cada movimento do dedo. */
    private fun guardar() {
        handler.removeCallbacks(salvarDepois)
        handler.postDelayed(salvarDepois, 400)
    }

    fun definirCor(c: Int) {
        cor = c
        cor2 = 0
        if (modo == "arco") modo = "fixo"
        val estavaDesligado = !ligado
        ligado = true
        guardar(); atualizarAudio()
        if (estavaDesligado || (emContinuo && modo == "fixo")) aplicar()
        else if (conectado && modo == "fixo") enviarCor(corFinal(0f))
        aoMudar()
    }

    /** Duas cores no Rítmico: [grave] acende nos graves (bumbo) e [agudo] nas batidas médias (caixa, palmas). */
    fun definirDupla(grave: Int, agudo: Int) {
        cor = grave
        cor2 = agudo
        ligado = true
        guardar(); atualizarAudio()
        aoMudar()
    }

    fun definirModo(m: String) {
        modo = m
        guardar(); atualizarAudio(); aplicar(); aoMudar()
    }

    fun definirBrilho(b: Int) {
        brilho = b.coerceIn(5, 100)
        guardar()
        if (conectado && ligado && modo == "fixo") enviarCor(corFinal(0f))
        aoMudar()
    }

    fun definirSensibilidade(s: Int) {
        sensibilidade = s.coerceIn(0, 100)
        guardar(); aoMudar()
    }

    fun definirLigado(v: Boolean) {
        ligado = v
        guardar(); atualizarAudio(); aplicar(); aoMudar()
    }

    // ---------------------------------------------------------------- áudio (efeito Rítmico)

    private fun atualizarAudio() {
        if (ligado && modo == "ritmo") iniciarAudio() else pararAudio()
    }

    /**
     * Lê o sinal de áudio que sai da caixa (a música do Bluetooth), sem usar o microfone.
     * O Android só deixa analisar o som de uma "sessão" de áudio por vez, e o receptor Bluetooth usa uma
     * sessão própria que muda a cada conexão; então a cada poucos segundos procuramos qual sessão está
     * tocando e ligamos o analisador nela.
     */
    private fun iniciarAudio() {
        if (monitorAtivo) return
        monitorAtivo = true
        Thread {
            while (monitorAtivo) {
                val achada = if (sessaoForcada > 0) sessaoForcada else if (sessaoExterna > 0) sessaoExterna else acharSessaoTocando()
                if (achada != sessaoAudio) handler.post { trocarSessao(achada) }
                try { Thread.sleep(if (achada > 0) 4000 else 1500) } catch (e: InterruptedException) { }
            }
        }.start()
    }

    /** Pergunta ao Android (dumpsys) qual faixa de áudio está tocando agora e devolve a sessão dela. */
    private fun acharSessaoTocando(): Int {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("dumpsys", "media.audio_flinger"))
            val regra = Regex("""\b(yes|no)\s+(\d+)\s+(\d+)\s+[0-9a-fA-F]{8}\s+[0-9a-fA-F]{8}\s+(\d+)\s+(\d+)\s""")
            val minhaPid = android.os.Process.myPid()
            var sessao = -1
            proc.inputStream.bufferedReader().useLines { linhas ->
                for (l in linhas) {
                    val m = regra.find(l) ?: continue
                    if (m.groupValues[1] == "yes" && m.groupValues[2].toInt() != minhaPid && sessao < 0) {
                        sessao = m.groupValues[4].toInt()
                    }
                }
            }
            proc.waitFor()
            return sessao
        } catch (e: Exception) {
            Log.w(TAG, "nao foi possivel consultar o audio", e)
            return -1
        }
    }

    private fun trocarSessao(nova: Int) {
        if (!monitorAtivo) return
        liberarAnalisador()
        sessaoAudio = nova
        nivelAudio = 0f
        if (nova <= 0) return
        try {
            val v = Visualizer(nova)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1]       // maior tamanho: separa melhor o grave
            // por padrão o Visualizer normaliza cada quadro pelo próprio pico (trecho calmo vira "alto"): queremos o nível real
            try { v.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED } catch (e: Exception) { Log.w(TAG, "scalingMode", e) }
            val taxaHz = (v.samplingRate / 1000).coerceAtLeast(8000)
            Log.d(TAG, "analisando a sessao $nova: tamanho=${v.captureSize} taxa=$taxaHz")
            // coeficientes dos filtros de uma polo: grave (até ~150 Hz) e "tudo acima de ~1,5 kHz" (caixa, palmas)
            val aGrave = (1.0 - Math.exp(-2 * Math.PI * 150 / taxaHz)).toFloat()
            val aMedio = (1.0 - Math.exp(-2 * Math.PI * 1500 / taxaHz)).toFloat()
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, taxa: Int) {}
                // Forma de onda, não espectro: o espectro de 8 bits do Visualizer satura nos graves fortes e deixa bumbo e
                // baixo contínuo iguais. Na forma de onda, o bumbo é um pulo de energia bem claro (testado com áudio gerado).
                override fun onWaveFormDataCapture(vis: Visualizer?, onda: ByteArray?, taxa: Int) {
                    if (onda == null || onda.isEmpty()) return
                    var y = 0f; var z = 0f; var somaG = 0f; var somaM = 0f
                    for (b in onda) {
                        val x = ((b.toInt() and 0xff) - 128) / 128f   // bytes sem sinal, silêncio = 128 (conferido no aparelho)
                        y += aGrave * (x - y)
                        z += aMedio * (x - z)
                        somaG += y * y
                        val d = x - z
                        somaM += d * d
                    }
                    val grave = sqrt(somaG / onda.size)
                    val medio = sqrt(somaM / onda.size)
                    val agora = SystemClock.uptimeMillis()
                    val s = sensibilidade / 100f
                    if (false) android.util.Log.d("RitmoBytes", onda.take(48).joinToString(" ") { (it.toInt() and 0xff).toString() })
                    android.util.Log.d("RitmoQuadro", "%d %.4f %.4f %d %d".format(agora - inicioTeste, grave, medio, onda.minOrNull() ?: 0, onda.maxOrNull() ?: 0))
                    // sensibilidade maior = bate com menos salto de energia
                    // depois de um ataque o brilho cai sozinho, exponencialmente (TAU_MS); só ataques novos acendem de novo
                    val dt = if (ultimoQuadro == 0L) 50L else (agora - ultimoQuadro).coerceIn(1L, 500L)
                    ultimoQuadro = agora
                    nivelAudio *= Math.exp(-dt / TAU_MS).toFloat()
                    if (nivelAudio < 0.03f) nivelAudio = 0f
                    val fg = ataqueGrave.processa(grave, agora, 1.6f - 0.6f * s, 1.05f - 0.40f * s)
                    val fm = if (agora - ataqueGrave.ultima < 150) 0f else ataqueMedio.processa(medio, agora, 2.6f - 1.2f * s, 1.05f - 0.40f * s)
                    if (fg > 0f || fm > 0f) {
                        batidaMedia = fg == 0f
                        nivelAudio = max(nivelAudio, 0.55f + 0.45f * max(fg, fm))
                        contBatidas++
                        dispararPulso(agora)
                        registrarBatida(if (batidaMedia) "medio" else "grave", max(fg, fm), grave, medio)
                    }
                    if (agora - ultimoRegistro > 2000) {
                        ultimoRegistro = agora
                        Log.d(TAG, "som: grave=%.3f medio=%.3f nivel=%.2f sens=%d".format(grave, medio, nivelAudio, sensibilidade))
                    }
                }
            }, Visualizer.getMaxCaptureRate(), true, false)
            v.enabled = true
            visualizador = v
        } catch (e: Exception) {
            Log.w(TAG, "nao foi possivel analisar a sessao $nova", e)
            visualizador = null
        }
    }

    private fun liberarAnalisador() {
        try { visualizador?.enabled = false; visualizador?.release() } catch (e: Exception) { }
        visualizador = null
    }

    private fun pararAudio() {
        monitorAtivo = false
        liberarAnalisador()
        sessaoAudio = -1
        nivelAudio = 0f
    }

    fun limparErro() { erro = null }
}
