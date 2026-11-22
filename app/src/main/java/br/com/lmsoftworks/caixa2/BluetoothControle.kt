package br.com.lmsoftworks.caixa2

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Bluetooth da caixa. A caixa RECEBE áudio de outros celulares (perfil A2DP Sink): fica visível
 * para pareamento, aceita o pareamento sozinha e mostra quem está conectado.
 */
class BluetoothControle(contexto: Context, private val aoMudar: () -> Unit) {

    class Aparelho(
        val endereco: String,
        val nome: String,
        val pareado: Boolean,
        val conectado: Boolean,
        val pareando: Boolean,
        val icone: Int,
        /** A caixa pediu a conexão deste aparelho e ela ainda não terminou (a tela mostra uma rodinha). */
        val conectando: Boolean = false
    )

    companion object {
        private const val TAG = "CaixaBt"
        const val NOME_CAIXA = "LM Sound"
        private const val PERFIL_A2DP_SINK = 11
        private const val ACAO_SINK = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
        private const val ACAO_SOURCE = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
        private const val ACAO_TOCANDO = "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED"
        private const val A2DP_TOCANDO = 10          // BluetoothA2dp.STATE_PLAYING (11 = não está tocando)
        private const val CONFIRMAR_AUDIO_MS = 2_500L
    }

    private val ctx = contexto.applicationContext
    private val adaptador: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val handler = Handler(Looper.getMainLooper())

    // ----- Foto do estado do Bluetooth, tirada FORA da tela principal -----
    // Cada pergunta ao serviço de Bluetooth do Android (isEnabled, getBondState, getRemoteClass…) é uma chamada que pode levar
    // centenas de milissegundos quando ele está ocupado (logo depois de ligar, durante a busca). Feitas na tela principal, travavam
    // o toque por vários segundos (log de 21/11: "Skipped 221 frames", Engasgo em BinderProxy.transact). A tela só lê esta foto.
    private val threadFoto = HandlerThread("bt-foto").apply { start() }
    private val hFoto = Handler(threadFoto.looper)
    @Volatile private var ligadoC = adaptador?.isEnabled == true
    @Volatile private var visivelC = false
    @Volatile private var buscandoC = false
    @Volatile private var nomeC: String = Ajustes.nomeAparelho
    @Volatile private var aparelhosC: List<Aparelho> = emptyList()
    private var assinaturaFoto = ""
    private var fotoAgendada = false
    private val achados = LinkedHashMap<String, BluetoothDevice>()
    /** Quando cada aparelho da lista "Por perto" foi visto pela última vez: quem some da busca por um tempo sai da lista. */
    private val vistoEm = HashMap<String, Long>()
    private val nomes = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var telaAberta = false
    private val conectados = HashSet<String>()
    private var pareandoCom: String? = null
    private var conectandoCom: String? = null
    private var perfilAudio: BluetoothProfile? = null
    private var perfilAudioId = PERFIL_A2DP_SINK
    private val getProp by lazy {
        runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }.getOrNull()
    }
    private fun propriedade(nome: String): String = runCatching { getProp?.invoke(null, nome) as? String }.getOrNull().orEmpty()
    val modosDisponiveis: Boolean get() = propriedade("init.svc.caixa_bt_modos") == "running"
    var transmissor = false; private set
    /** O modo de agora lido direto do sistema (vale antes de [iniciar] e depois de uma troca de modo). */
    fun modoTransmissorAgora(): Boolean = propriedade("persist.service.bt.a2dp.sink") == "false"
    private var pedidoModo = 0L
    private var prazoModo = 0L
    val trocandoModo: Boolean get() = pedidoModo != 0L
    var aoReiniciarParaTrocarModo: (() -> Unit)? = null
    /** A troca de modo foi pedida (a tela mostra "carregando" já) e, se não der certo, avisa para tirar o carregando. */
    var aoTrocaIniciada: (() -> Unit)? = null
    var aoTrocaFalhou: (() -> Unit)? = null
    private val conferirModo = object : Runnable {
        override fun run() {
            val resultado = Settings.Global.getString(ctx.contentResolver, "caixa_bt_modo_estado")
            if (resultado == "$pedidoModo:ok" || resultado == "$pedidoModo:falhou" || SystemClock.uptimeMillis() >= prazoModo) {
                val preparado = resultado == "$pedidoModo:ok"
                if (!preparado) { erro = "A troca do modo Bluetooth não foi concluída."; aoTrocaFalhou?.invoke() }
                pedidoModo = 0L
                aoMudar()
                if (preparado) aoReiniciarParaTrocarModo?.invoke()
            } else handler.postDelayed(this, 500)
        }
    }

    /** A mensagem de erro some sozinha 3 segundos depois de aparecer (antes ficava na tela até a pessoa mexer em outro aparelho). */
    var erro: String? = null
        private set(v) {
            field = v
            handler.removeCallbacks(apagarErro)
            if (v != null) handler.postDelayed(apagarErro, 3_000L)
        }
    private val apagarErro = Runnable { erro = null; aoMudar() }
    /** Ganchos para os sons: um aparelho começou a conectar / terminou de conectar. */
    var aoConectando: (() -> Unit)? = null
    var aoConectado: (() -> Unit)? = null

    val existe: Boolean get() = adaptador != null
    val ligado: Boolean get() = ligadoC
    val visivel: Boolean get() = visivelC
    val buscando: Boolean get() = buscandoC
    val nomeCaixa: String get() = nomeC
    val algumConectado: Boolean get() = conectados.isNotEmpty()

    /** O celular está mandando áudio agora (estado do A2DP, que chega mesmo quando título e capa não chegam). */
    @Volatile var celularTocando = false; private set
    /** Só confirma áudio contínuo: os cliques e notificações do celular também geram eventos A2DP. */
    @Volatile var celularConfirmado = false; private set
    /** O celular começou a tocar: quem estiver tocando na própria caixa (YouTube) deve parar. */
    var aoCelularTocando: (() -> Unit)? = null
    /** O celular começou a mandar áudio (na hora, sem esperar a confirmação): ele manda também o volume dele de partida. */
    var aoSincronizar: (() -> Unit)? = null
    private val confirmarAudio = Runnable {
        if (celularTocando && algumConectado) {
            celularConfirmado = true
            Log.d(TAG, "áudio contínuo do celular confirmado")
            aoCelularTocando?.invoke()
        }
    }
    /** O estado do adaptador mudou (BluetoothAdapter.STATE_*): a tela solta ou retoma o que prende o serviço de Bluetooth do Android. */
    var aoEstadoAdaptador: ((Int) -> Unit)? = null
    /** Antes de desligar o Bluetooth: solta tudo o que o app mantém ligado ao serviço dele (sem isso o Android daqui pode travar). */
    var aoAntesDeDesligar: (() -> Unit)? = null
    /** O Bluetooth não voltou depois de ligado: o serviço dele do Android travou e só reiniciar a caixa resolve. */
    var aoTravado: (() -> Unit)? = null

    fun nomeConectado(): String? =
        conectados.firstNotNullOfOrNull { end -> adaptador?.bondedDevices?.firstOrNull { it.address == end }?.let(::nomeDe) }

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            when (i.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    ligadoC = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON
                    pedirFoto()
                    aoEstadoAdaptador?.invoke(i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1))
                    if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF && ligarPendente) {
                        ligarPendente = false
                        handler.postDelayed({ adaptador?.let { if (!it.isEnabled) { it.enable(); vigiarLigamento() } } }, esperaParaLigar(2500))      // um respiro antes da nova conexão
                    }
                    when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_ON -> aoLigar()
                        BluetoothAdapter.STATE_OFF -> {
                            mudouTocando(false)
                            fecharPerfil()
                            achados.clear(); vistoEm.clear()
                            conectados.clear()
                            pareandoCom = null
                        }
                    }
                }
                BluetoothDevice.ACTION_FOUND -> if (d != null && telaAberta) {
                    achados[d.address] = d
                    vistoEm[d.address] = SystemClock.uptimeMillis()
                    guardarNome(d, i.getStringExtra(BluetoothDevice.EXTRA_NAME))
                }
                BluetoothDevice.ACTION_NAME_CHANGED -> if (d != null) guardarNome(d, i.getStringExtra(BluetoothDevice.EXTRA_NAME))
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    if (d != null) {
                        val estado = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        val escolhido = pareandoCom == d.address
                        if (estado == BluetoothDevice.BOND_BONDING) { pareandoCom = d.address; limitarPareamento(d.address) }
                        else {
                            if (pareandoCom == d.address) pareandoCom = null
                            handler.removeCallbacks(limitePareamento)
                            if (estado == BluetoothDevice.BOND_NONE &&
                                i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1) == BluetoothDevice.BOND_BONDING) {
                                erro = "Não foi possível parear com ${nomeDe(d)}"
                            }
                            if (estado == BluetoothDevice.BOND_BONDED) {
                                achados.remove(d.address)
                                if (escolhido && transmissor) handler.postDelayed({
                                    if (transmissor && !trocandoModo) conectar(d.address)
                                }, 800L)
                            }
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> if (!transmissor && d != null && d.bondState == BluetoothDevice.BOND_BONDED) { conectados.add(d.address); garantirPerfil(d) }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (d != null) perdeu(d.address)
                ACAO_TOCANDO -> if (!transmissor) {
                    val tocando = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == A2DP_TOCANDO
                    Log.d(TAG, "áudio do celular: ${if (tocando) "tocando" else "parado"}")
                    mudouTocando(tocando)
                }
                ACAO_SINK, ACAO_SOURCE -> if (d != null && (i.action == ACAO_SOURCE) == transmissor) {
                    when (i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                        BluetoothProfile.STATE_CONNECTING -> aoConectando?.invoke()
                        BluetoothProfile.STATE_CONNECTED -> { desconectadosPorMim.remove(d.address); conectados.add(d.address); aoConectado?.invoke() }      // o ACL já pode ter adicionado: o aviso vale mesmo assim
                        BluetoothProfile.STATE_DISCONNECTED -> perdeu(d.address)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED, BluetoothAdapter.ACTION_DISCOVERY_STARTED,
                BluetoothAdapter.ACTION_SCAN_MODE_CHANGED, BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED -> { }
            }
            aoMudar()
        }
    }

    /** Aceita sozinha os pedidos de pareamento, sem abrir a janela do Android. */
    private val receptorPareamento = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val variante = i.getIntExtra("android.bluetooth.device.extra.PAIRING_VARIANT", -1)
            try {
                when (variante) {
                    0 -> d.setPin("0000".toByteArray())
                    1 -> { /* senha digitada no outro lado: não suportado */ }
                    else -> BluetoothDevice::class.java
                        .getMethod("setPairingConfirmation", Boolean::class.javaPrimitiveType)
                        .invoke(d, true)
                }
                abortBroadcast()
            } catch (e: Exception) {
                Log.w(TAG, "nao foi possivel confirmar o pareamento", e)
            }
        }
    }

    fun iniciar() {
        val a = adaptador ?: return
        atualizarModo()
        if (trocandoModo) handler.post(conferirModo)
        val f = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
            addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACAO_SINK)
            addAction(ACAO_SOURCE)
            addAction(ACAO_TOCANDO)
        }
        ctx.registerReceiver(receptor, f)
        ctx.registerReceiver(receptorPareamento, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply { priority = 1000 })
        if (!a.isEnabled) { a.enable(); vigiarLigamento() } else aoLigar()
        aoMudar()
    }

    /** Se 15 s depois de pedir para ligar o Bluetooth ele nem está ligando, o serviço dele travou. */
    private fun vigiarLigamento() {
        handler.postDelayed({
            val a = adaptador ?: return@postDelayed
            val st = try { a.state } catch (e: Exception) { BluetoothAdapter.STATE_OFF }
            if (st != BluetoothAdapter.STATE_ON && st != BluetoothAdapter.STATE_TURNING_ON) { Log.w(TAG, "Bluetooth não ligou: serviço travado"); aoTravado?.invoke() }
        }, 15000)
    }

    fun parar() {
        definirTelaAberta(false)
        try { ctx.unregisterReceiver(receptor) } catch (e: IllegalArgumentException) { }
        try { ctx.unregisterReceiver(receptorPareamento) } catch (e: IllegalArgumentException) { }
        mudouTocando(false)
        handler.removeCallbacksAndMessages(null)
        try { adaptador?.cancelDiscovery() } catch (e: Exception) { }
        fecharPerfil()
    }

    private fun fecharPerfil() {
        perfilAudio?.let { adaptador?.closeProfileProxy(perfilAudioId, it) }
        perfilAudio = null
    }

    private fun atualizarModo() {
        transmissor = propriedade("persist.service.bt.a2dp.sink") == "false"
    }

    fun definirModo(usarComoTransmissor: Boolean) {
        if (usarComoTransmissor == transmissor || trocandoModo || !modosDisponiveis) return
        val id = System.currentTimeMillis()
        val modo = if (usarComoTransmissor) "transmissor" else "receptor"
        try {
            check(Settings.Global.putString(ctx.contentResolver, "caixa_bt_modo_pedido", "$id:$modo"))
            erro = null
            pedidoModo = id
            prazoModo = SystemClock.uptimeMillis() + 40_000L      // o serviço de sistema confere o pedido a cada 10 s (era 3 s)
            aoTrocaIniciada?.invoke()
            handler.post(conferirModo)
        } catch (e: Exception) {
            erro = "Não foi possível solicitar a troca de modo."
            Log.w(TAG, erro, e)
        }
        aoMudar()
    }

    /**
     * O celular conectou (ACL) e já pode estar tocando, mas a ligação do perfil de ÁUDIO/CONTROLE (A2DP + AVRCP) com a caixa às vezes
     * não sobe junto: o som toca, porém título e capa nunca chegam e o aparelho aparece só como "pareado". Se, uns segundos depois, o
     * perfil não estiver conectado nem conectando, a própria caixa pede a conexão (o mesmo que tocar no aparelho na tela Bluetooth).
     */
    private fun garantirPerfil(d: BluetoothDevice, tentativa: Int = 0) {
        handler.postDelayed({
            try {
                val p = perfilAudio
                if (!transmissor && !trocandoModo && p != null && d.bondState == BluetoothDevice.BOND_BONDED && estaConectado(d)) {
                    val estado = p.javaClass.getMethod("getConnectionState", BluetoothDevice::class.java).invoke(p, d) as Int
                    if (estado != BluetoothProfile.STATE_CONNECTED && estado != BluetoothProfile.STATE_CONNECTING) {
                        Log.d(TAG, "perfil de áudio ausente (estado $estado): conectando ${nomeDe(d)}")
                        p.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(p, d)
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "garantirPerfil falhou", e) }
            if (tentativa < 2) garantirPerfil(d, tentativa + 1)
        }, if (tentativa == 0) 2500L else 6000L)
    }

    /** O nome da caixa mudou em Configurações: aplica no Bluetooth (se estiver ligado). */
    fun aplicarNome() {
        val a = adaptador ?: return
        if (a.isEnabled && a.name != Ajustes.nomeAparelho) a.name = Ajustes.nomeAparelho
    }

    private fun aoLigar() {
        val a = adaptador ?: return
        atualizarModo()
        if (a.name != Ajustes.nomeAparelho) a.name = Ajustes.nomeAparelho
        // aparelhos já ligados e pareados
        if (!transmissor) a.bondedDevices?.forEach { if (estaConectado(it)) { conectados.add(it.address); garantirPerfil(it) } }
        val perfil = if (transmissor) BluetoothProfile.A2DP else PERFIL_A2DP_SINK
        if (perfilAudioId != perfil) {
            fecharPerfil()
            perfilAudioId = perfil
        }
        if (perfilAudio == null) {
            a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != perfilAudioId || !ligado) {
                        a.closeProfileProxy(profile, proxy)
                        return
                    }
                    perfilAudio = proxy
                    proxy.connectedDevices.forEach { conectados.add(it.address) }
                    sincronizarTocando()
                    aoMudar()
                }
                override fun onServiceDisconnected(profile: Int) { if (profile == perfilAudioId) perfilAudio = null }
            }, perfil)
        }
        if (telaAberta) {
            definirVisivel(true)
            iniciarBusca()
        }
        aoMudar()
    }

    /**
     * O áudio do celular começou ou parou (pelo aviso do Bluetooth OU pela leitura periódica: o que chegar primeiro). Os dois passam por
     * aqui para que quem assume a prioridade (o app pára quando o celular começa) rode sempre, e não só quando o aviso ganha a corrida.
     */
    private fun mudouTocando(tocando: Boolean) {
        if (tocando == celularTocando) return
        celularTocando = tocando
        handler.removeCallbacks(confirmarAudio)
        if (tocando) {
            aoSincronizar?.invoke()
            handler.postDelayed(confirmarAudio, CONFIRMAR_AUDIO_MS)
        } else celularConfirmado = false
    }

    /**
     * Lê do perfil de áudio se o celular está tocando AGORA. O aviso de "começou a tocar" só vem na hora da mudança: se o app abriu depois
     * (instalação, reinício), ele não sabia que o celular já estava tocando e a tela dizia "Nenhuma música tocando".
     */
    fun sincronizarTocando() {
        if (transmissor) return
        val p = perfilAudio ?: return
        try {
            val tocando = p.connectedDevices.any { d -> p.javaClass.getMethod("isA2dpPlaying", BluetoothDevice::class.java).invoke(p, d) as Boolean }
            if (tocando != celularTocando) {
                Log.d(TAG, "áudio do celular: ${if (tocando) "tocando" else "parado"} (lido do perfil)")
                mudouTocando(tocando)
                aoMudar()
            }
        } catch (e: Exception) { Log.w(TAG, "isA2dpPlaying indisponível", e) }
    }

    private fun estaConectado(d: BluetoothDevice): Boolean = try {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as Boolean
    } catch (e: Exception) { false }

    /** Pediram para ligar enquanto ele ainda estava desligando: liga só depois que desligar de vez e passar um tempo (nova conexão limpa). */
    private var ligarPendente = false

    private var ultimoDisable = 0L

    /**
     * O Android 7.1 só avisa "desligado" quando a pilha entra em BLE_ON, mas ainda leva ~6 s (BLE_ON → BLE_TURNING_OFF → OFF) para
     * terminar. Ligar nesse intervalo estoura o "waitForMonitoredOnOff" (5 s) e o sistema reinicia o serviço à força (visto no log
     * de 21/11: disable às 20:30:12, enable às 20:30:14, reinício forçado, conexão do iPhone só às 20:30:55). Por isso o enable espera.
     */
    private fun esperaParaLigar(minimo: Long): Long {
        if (!desligandoDeVez) return minimo
        val falta = ultimoDisable + 8000L - System.currentTimeMillis()
        return if (falta > minimo) falta else minimo
    }

    /** Verdadeiro do pedido de desligar até a pilha do Bluetooth terminar de desligar DE VERDADE (a tela fica em "Desligando…" até lá). */
    @Volatile var desligandoDeVez = false; private set

    private fun marcarDesligando() {
        ultimoDisable = System.currentTimeMillis()
        desligandoDeVez = true
        val vigia = object : Runnable {
            override fun run() {
                val passou = System.currentTimeMillis() - ultimoDisable
                if ((passou > 1500 && bleDesligado()) || passou > 9000) { desligandoDeVez = false; aoMudar() } else handler.postDelayed(this, 250)
            }
        }
        handler.postDelayed(vigia, 500)
    }

    /** Estado interno do Bluetooth (o público some o BLE_TURNING_OFF). Se a leitura falhar, vale o limite de tempo. */
    private fun bleDesligado(): Boolean = try {
        val a = adaptador ?: return true
        (BluetoothAdapter::class.java.getMethod("getLeState").invoke(a) as Int) == BluetoothAdapter.STATE_OFF
    } catch (e: Exception) { false }

    /**
     * Desliga o Bluetooth do jeito limpo e só chama [aoFim] quando ele está de fato desligado (ou depois de 8 s): desconecta os
     * aparelhos, solta tudo o que o app mantém ligado ao serviço dele e só então manda desligar.
     */
    fun desligarLimpo(aoFim: () -> Unit) {
        val a = adaptador
        if (a == null || !a.isEnabled) { aoFim(); return }
        aoAntesDeDesligar?.invoke()
        desconectarTodos {
            handler.postDelayed({
                try { marcarDesligando(); a.disable() } catch (e: Exception) { Log.w(TAG, "disable falhou", e) }
                val inicio = System.currentTimeMillis()
                val espera = object : Runnable {
                    override fun run() {
                        val st = try { a.state } catch (e: Exception) { BluetoothAdapter.STATE_OFF }
                        if (st == BluetoothAdapter.STATE_OFF || System.currentTimeMillis() - inicio > 8000) aoFim() else handler.postDelayed(this, 200)
                    }
                }
                handler.postDelayed(espera, 300)
            }, 500)
        }
    }

    /** Ao abrir a tela: lê já quem está conectado, para a lista aparecer certa no primeiro quadro. */
    fun sincronizar() {
        val a = adaptador ?: return
        if (!ligado) return
        val perfil = perfilAudio; val tx = transmissor
        hFoto.post {
            val achou = ArrayList<String>()
            try {
                perfil?.connectedDevices?.forEach { achou.add(it.address) }
                if (!tx) a.bondedDevices?.forEach { if (estaConectado(it)) achou.add(it.address) }
            } catch (e: Exception) { Log.w(TAG, "sincronizar falhou", e) }
            handler.post { if (conectados.addAll(achou)) aoMudar(); pedirFoto() }
        }
    }

    fun definirLigado(v: Boolean) {
        if (trocandoModo) return
        val a = adaptador ?: return
        val st = try { a.state } catch (e: Exception) { BluetoothAdapter.STATE_OFF }
        if (v && st == BluetoothAdapter.STATE_TURNING_OFF) { ligarPendente = true; return }      // ainda desligando: espera acabar
        if (v && (st == BluetoothAdapter.STATE_ON || st == BluetoothAdapter.STATE_TURNING_ON)) return
        if (v) { handler.postDelayed({ try { a.enable() } catch (e: Exception) { Log.w(TAG, "enable falhou", e) }; vigiarLigamento() }, esperaParaLigar(60L)) }
        else {
            // solta a ligação do app com o serviço de Bluetooth e só então manda desligar
            aoAntesDeDesligar?.invoke()
            handler.postDelayed({ try { marcarDesligando(); a.disable() } catch (e: Exception) { Log.w(TAG, "disable falhou", e) } }, 700)
        }
        aoMudar()
    }

    /** Deixa a caixa aparecer (ou não) na lista de Bluetooth dos outros aparelhos. */
    fun definirVisivel(v: Boolean) {
        val a = adaptador ?: return
        if (!a.isEnabled) return
        try {
            if (v) {
                try { BluetoothAdapter::class.java.getMethod("setDiscoverableTimeout", Int::class.javaPrimitiveType).invoke(a, 0) }
                catch (e: Exception) { Log.w(TAG, "sem setDiscoverableTimeout", e) }
            }
            val modo = if (v) BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE else BluetoothAdapter.SCAN_MODE_CONNECTABLE
            BluetoothAdapter::class.java.getMethod("setScanMode", Int::class.javaPrimitiveType).invoke(a, modo)
        } catch (e: Exception) {
            Log.w(TAG, "nao foi possivel mudar a visibilidade", e)
        }
        aoMudar()
    }

    fun definirTelaAberta(aberta: Boolean) {
        if (telaAberta == aberta) return
        telaAberta = aberta
        if (aberta) {
            achados.clear(); vistoEm.clear()
            definirVisivel(true)
            iniciarBusca()
            handler.removeCallbacks(vigiaBusca); handler.postDelayed(vigiaBusca, 3_000L)
        } else {
            handler.removeCallbacks(vigiaBusca)
            handler.removeCallbacks(buscaAtrasada)
            try { adaptador?.cancelDiscovery() } catch (e: RuntimeException) { Log.w(TAG, "não foi possível encerrar a busca", e) }
            definirVisivel(false)
        }
    }

    /**
     * A busca do Android dura só uns 12 s e para sozinha: a lista ficava parada, sem achar quem ligou depois (a pessoa tinha que desligar o
     * Bluetooth e voltar ao menu). Enquanto a tela está aberta, de 3 em 3 s confere e liga a busca de novo se ela parou. Não busca enquanto
     * pareia ou conecta (a busca atrapalha e deixa a conexão lenta) nem com um aparelho já tocando no modo Transmissor (picotaria o som);
     * nesses casos o botão Atualizar da tela faz uma busca quando a pessoa quiser.
     */
    private val vigiaBusca = object : Runnable {
        override fun run() {
            if (!telaAberta) return
            val tocandoParaFora = transmissor && conectados.isNotEmpty()
            if (ligado && !trocandoModo && pareandoCom == null && conectandoCom == null && !tocandoParaFora) iniciarBusca(silencioso = true)
            handler.postDelayed(this, 3_000L)
        }
    }

    private val buscaAtrasada = Runnable { iniciarBusca() }

    /** Botão Atualizar: esquece o que foi achado, para a busca atual e começa outra do zero. */
    fun atualizarBusca() {
        val a = adaptador ?: return
        if (!a.isEnabled || trocandoModo) return
        achados.clear(); vistoEm.clear()
        try { a.cancelDiscovery() } catch (e: RuntimeException) { Log.w(TAG, "cancelDiscovery falhou", e) }
        handler.removeCallbacks(buscaAtrasada)
        handler.postDelayed(buscaAtrasada, 800L)      // o cancelamento leva um instante para valer
        aoMudar()
    }

    private fun iniciarBusca(silencioso: Boolean = false) {
        val a = adaptador ?: return
        if (!a.isEnabled || a.isDiscovering) return
        try {
            if (!a.startDiscovery() && !silencioso) erro = "Não foi possível iniciar a busca Bluetooth."
        } catch (e: RuntimeException) {
            if (!silencioso) erro = "Não foi possível iniciar a busca Bluetooth."
            Log.w(TAG, "startDiscovery falhou", e)
        }
        aoMudar()
    }

    /**
     * Um pareamento que não termina em 15 s é cancelado: quando o aparelho não responde ao chamado (ex.: soundbar fora do modo de pareamento
     * ou ocupada com outro aparelho) o Android espera uns 30 s antes de desistir e a tela ficava um tempão em "Pareando…".
     */
    private val limitePareamento = Runnable {
        val fim = pareandoCom ?: return@Runnable
        val d = achar(fim) ?: return@Runnable
        if (d.bondState != BluetoothDevice.BOND_BONDING) return@Runnable
        Log.w(TAG, "pareamento com ${nomeDe(d)} sem resposta em 15 s: cancelando")
        try { BluetoothDevice::class.java.getMethod("cancelBondProcess").invoke(d) } catch (e: Exception) { Log.w(TAG, "cancelBondProcess falhou", e) }
        pareandoCom = null
        erro = "${nomeDe(d)} não respondeu. Deixe-o em modo de pareamento, perto da caixa, e tente de novo."
        aoMudar()
    }

    private fun limitarPareamento(endereco: String) {
        handler.removeCallbacks(limitePareamento)
        handler.postDelayed(limitePareamento, 15_000L)
    }

    private fun guardarNome(d: BluetoothDevice, anunciado: String? = null) {
        val nome = anunciado?.trim().takeUnless { it.isNullOrEmpty() } ?: d.name?.trim()
        if (!nome.isNullOrEmpty() && !nome.equals(d.address, ignoreCase = true)) nomes[d.address] = nome
    }

    private fun nomeDe(d: BluetoothDevice): String {
        val conhecido = nomes[d.address]
        if (conhecido != null) return conhecido
        guardarNome(d)
        return nomes[d.address] ?: "Dispositivo Bluetooth · ${d.address.takeLast(5)}"
    }

    private fun iconeDe(d: BluetoothDevice): Int {
        val maior = d.bluetoothClass?.majorDeviceClass
        return when (maior) {
            BluetoothClass.Device.Major.PHONE -> R.drawable.ic_celular
            BluetoothClass.Device.Major.COMPUTER -> R.drawable.ic_notebook
            BluetoothClass.Device.Major.AUDIO_VIDEO -> R.drawable.ic_fone
            else -> R.drawable.ic_bluetooth
        }
    }

    private fun montar(d: BluetoothDevice) = Aparelho(
        d.address, nomeDe(d), d.bondState == BluetoothDevice.BOND_BONDED,
        conectados.contains(d.address), pareandoCom == d.address, iconeDe(d),
        conectandoCom == d.address && !conectados.contains(d.address)
    )

    /** Pareados (conectados primeiro) e, depois, os encontrados na busca. Devolve a última foto (rápido); uma nova é tirada em segundo plano. */
    fun aparelhos(): List<Aparelho> {
        conectandoCom?.let { if (conectados.contains(it)) conectandoCom = null }      // já conectou: a rodinha some
        pedirFoto()
        return aparelhosC
    }

    /** Pede uma foto nova (junta pedidos seguidos). Os dados da tela principal são copiados aqui; as chamadas lentas rodam na outra thread. */
    private fun pedirFoto() {
        if (fotoAgendada) return
        fotoAgendada = true
        handler.postDelayed({
            fotoAgendada = false
            val a = adaptador ?: return@postDelayed
            val ach = achados.values.toList(); val visto = HashMap(vistoEm); val con = HashSet(conectados)
            val pare = pareandoCom; val cone = conectandoCom
            hFoto.post {
                val lig = try { a.isEnabled } catch (e: Exception) { false }
                val vis = try { a.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE } catch (e: Exception) { false }
                val bus = try { a.isDiscovering } catch (e: Exception) { false }
                val nom = try { a.name } catch (e: Exception) { null } ?: Ajustes.nomeAparelho
                val lista = if (!lig) emptyList() else try {
                    fun montar(d: BluetoothDevice) = Aparelho(d.address, nomeDe(d), d.bondState == BluetoothDevice.BOND_BONDED,
                        con.contains(d.address), pare == d.address, iconeDe(d), cone == d.address && !con.contains(d.address))
                    val pareados = (a.bondedDevices ?: emptySet()).map { montar(it) }
                        .sortedWith(compareByDescending<Aparelho> { it.conectado }.thenBy { it.nome.lowercase() })
                    val agora = SystemClock.uptimeMillis()
                    val novos = ach.filter { it.bondState != BluetoothDevice.BOND_BONDED && agora - (visto[it.address] ?: agora) < 90_000L }
                        .map { montar(it) }.sortedBy { it.nome.lowercase() }
                    pareados + novos
                } catch (e: Exception) { Log.w(TAG, "foto dos aparelhos falhou", e); null }
                handler.post {
                    visivelC = vis; buscandoC = bus; nomeC = nom
                    if (lista != null) aparelhosC = lista
                    val ass = buildString {
                        append(vis).append(bus).append(nom)
                        aparelhosC.forEach { append('|').append(it.endereco).append(it.nome).append(it.pareado).append(it.conectado).append(it.pareando).append(it.conectando).append(it.icone) }
                    }
                    if (ass != assinaturaFoto) { assinaturaFoto = ass; aoMudar() }
                }
            }
        }, 120L)
    }

    private fun achar(endereco: String): BluetoothDevice? =
        adaptador?.getRemoteDevice(endereco)

    fun parear(endereco: String) {
        if (trocandoModo) return
        erro = null
        val d = achar(endereco) ?: return
        try { adaptador?.cancelDiscovery() } catch (e: Exception) { }
        pareandoCom = endereco
        limitarPareamento(endereco)
        if (!d.createBond()) { pareandoCom = null; handler.removeCallbacks(limitePareamento); erro = "Não foi possível iniciar o pareamento" }
        aoMudar()
    }

    /** Avisa quando o último aparelho conectado saiu (a tela decide o que mostrar). */
    var aoDesconectado: (() -> Unit)? = null

    private fun perdeu(endereco: String) {
        if (conectados.remove(endereco) && conectados.isEmpty()) {
            mudouTocando(false)
            if (!transmissor) aoDesconectado?.invoke()
        }
    }

    /** Aparelhos que a própria pessoa (ou o desligamento da caixa) desconectou de propósito: a caixa não corre atrás deles. */
    private val desconectadosPorMim = HashSet<String>()

    fun conectar(endereco: String) {
        if (trocandoModo) return
        desconectadosPorMim.remove(endereco)
        val d = achar(endereco) ?: return
        try { adaptador?.cancelDiscovery() } catch (e: Exception) { }      // a busca atrapalha a conexão: para antes de chamar o aparelho
        conectandoCom = endereco      // mostra a rodinha no aparelho até conectar (ou desistir em 25 s)
        handler.postDelayed({ if (conectandoCom == endereco) { conectandoCom = null; aoMudar() } }, 25_000L)
        try {
            val p = perfilAudio
            if (p?.javaClass?.getMethod("connect", BluetoothDevice::class.java)?.invoke(p, d) != true) {
                conectandoCom = null
                erro = "Não foi possível conectar ${nomeDe(d)} neste modo."
            }
        } catch (e: Exception) { Log.w(TAG, "falha ao conectar", e); conectandoCom = null; erro = "Não foi possível conectar" }
        aoMudar()
    }

    fun desconectar(endereco: String) {
        desconectadosPorMim.add(endereco)
        val d = achar(endereco) ?: return
        try {
            perfilAudio?.let { it.javaClass.getMethod("disconnect", BluetoothDevice::class.java).invoke(it, d) }
        } catch (e: Exception) { Log.w(TAG, "falha ao desconectar", e) }
        aoMudar()
    }

    /** Antes de desligar: solta todos os aparelhos que estão tocando e avisa em [aoFim] (no máximo 3 s depois). */
    fun desconectarTodos(aoFim: () -> Unit) {
        val lista = conectados.toList()
        if (lista.isEmpty()) { aoFim(); return }
        lista.forEach { desconectar(it) }
        val inicio = System.currentTimeMillis()
        val espera = object : Runnable {
            override fun run() {
                if (conectados.isEmpty() || System.currentTimeMillis() - inicio > 3000) aoFim()
                else handler.postDelayed(this, 150)
            }
        }
        handler.postDelayed(espera, 150)
    }

    fun esquecer(endereco: String) {
        val d = achar(endereco) ?: return
        try {
            BluetoothDevice::class.java.getMethod("removeBond").invoke(d)
        } catch (e: Exception) { Log.w(TAG, "falha ao esquecer", e); erro = "Não foi possível esquecer o aparelho" }
        conectados.remove(endereco)
        desconectadosPorMim.add(endereco)
        aoMudar()
    }

    fun limparErro() { erro = null }
}
