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
import android.os.Looper
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
        val icone: Int
    )

    companion object {
        private const val TAG = "CaixaBt"
        const val NOME_CAIXA = "LM Sound"
        private const val PERFIL_A2DP_SINK = 11
        private const val ACAO_SINK = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
        private const val ACAO_TOCANDO = "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED"
        private const val A2DP_TOCANDO = 10          // BluetoothA2dp.STATE_PLAYING (11 = não está tocando)
        private const val DURACAO_BUSCA_MS = 12_000L
    }

    private val ctx = contexto.applicationContext
    private val adaptador: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val achados = LinkedHashMap<String, BluetoothDevice>()
    private val conectados = HashSet<String>()
    private var pareandoCom: String? = null
    private var perfilSink: BluetoothProfile? = null

    var erro: String? = null; private set
    /** Ganchos para os sons: um aparelho começou a conectar / terminou de conectar. */
    var aoConectando: (() -> Unit)? = null
    var aoConectado: (() -> Unit)? = null

    val existe: Boolean get() = adaptador != null
    val ligado: Boolean get() = adaptador?.isEnabled == true
    val visivel: Boolean get() = adaptador?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
    val buscando: Boolean get() = adaptador?.isDiscovering == true
    val nomeCaixa: String get() = adaptador?.name ?: Ajustes.nomeAparelho
    val algumConectado: Boolean get() = conectados.isNotEmpty()

    /** O celular está mandando áudio agora (estado do A2DP, que chega mesmo quando título e capa não chegam). */
    @Volatile var celularTocando = false; private set
    /** O celular está tocando há mais de 6 s seguidos: sons curtos (o clique de bloquear a tela do iPhone, por exemplo) não contam. */
    @Volatile var celularConfirmado = false; private set
    /** O celular começou a tocar: quem estiver tocando na própria caixa (YouTube) deve parar. */
    var aoCelularTocando: (() -> Unit)? = null
    /** O celular começou a mandar áudio (na hora, sem esperar a confirmação): ele manda também o volume dele de partida. */
    var aoSincronizar: (() -> Unit)? = null
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
                    aoEstadoAdaptador?.invoke(i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1))
                    if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF && ligarPendente) {
                        ligarPendente = false
                        handler.postDelayed({ adaptador?.let { if (!it.isEnabled) { it.enable(); vigiarLigamento() } } }, 2500)      // um respiro antes da nova conexão
                    }
                    when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_ON -> aoLigar()
                        BluetoothAdapter.STATE_OFF -> { achados.clear(); conectados.clear(); pareandoCom = null }
                    }
                }
                BluetoothDevice.ACTION_FOUND -> if (d != null) achados[d.address] = d
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    if (d != null) {
                        val estado = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        if (estado == BluetoothDevice.BOND_BONDING) pareandoCom = d.address
                        else {
                            if (pareandoCom == d.address) pareandoCom = null
                            if (estado == BluetoothDevice.BOND_NONE &&
                                i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1) == BluetoothDevice.BOND_BONDING) {
                                erro = "Não foi possível parear com ${nomeDe(d)}"
                            }
                            if (estado == BluetoothDevice.BOND_BONDED) achados.remove(d.address)
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> if (d != null && d.bondState == BluetoothDevice.BOND_BONDED) { conectados.add(d.address); garantirPerfil(d) }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (d != null) perdeu(d.address)
                ACAO_TOCANDO -> {
                    val tocando = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == A2DP_TOCANDO
                    Log.d(TAG, "áudio do celular: ${if (tocando) "tocando" else "parado"}")
                    mudouTocando(tocando)
                }
                ACAO_SINK -> if (d != null) {
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
        val f = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
            addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACAO_SINK)
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
        try { ctx.unregisterReceiver(receptor) } catch (e: IllegalArgumentException) { }
        try { ctx.unregisterReceiver(receptorPareamento) } catch (e: IllegalArgumentException) { }
        handler.removeCallbacksAndMessages(null)
        try { adaptador?.cancelDiscovery() } catch (e: Exception) { }
        adaptador?.closeProfileProxy(PERFIL_A2DP_SINK, perfilSink)
        perfilSink = null
    }

    /**
     * O celular conectou (ACL) e já pode estar tocando, mas a ligação do perfil de ÁUDIO/CONTROLE (A2DP + AVRCP) com a caixa às vezes
     * não sobe junto: o som toca, porém título e capa nunca chegam e o aparelho aparece só como "pareado". Se, uns segundos depois, o
     * perfil não estiver conectado nem conectando, a própria caixa pede a conexão (o mesmo que tocar no aparelho na tela Bluetooth).
     */
    private fun garantirPerfil(d: BluetoothDevice, tentativa: Int = 0) {
        handler.postDelayed({
            try {
                val p = perfilSink
                if (p != null && d.bondState == BluetoothDevice.BOND_BONDED && estaConectado(d)) {
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
        if (a.name != Ajustes.nomeAparelho) a.name = Ajustes.nomeAparelho
        // aparelhos já ligados e pareados
        a.bondedDevices?.forEach { if (estaConectado(it)) { conectados.add(it.address); garantirPerfil(it) } }
        if (perfilSink == null) {
            a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    perfilSink = proxy
                    proxy.connectedDevices.forEach { conectados.add(it.address) }
                    sincronizarTocando()
                    aoMudar()
                }
                override fun onServiceDisconnected(profile: Int) { perfilSink = null }
            }, PERFIL_A2DP_SINK)
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
        if (tocando) {
            aoSincronizar?.invoke()
            handler.postDelayed({ if (celularTocando) { celularConfirmado = true; aoCelularTocando?.invoke() } }, 1200)
        } else celularConfirmado = false
    }

    /**
     * Lê do perfil de áudio se o celular está tocando AGORA. O aviso de "começou a tocar" só vem na hora da mudança: se o app abriu depois
     * (instalação, reinício), ele não sabia que o celular já estava tocando e a tela dizia "Nenhuma música tocando".
     */
    fun sincronizarTocando() {
        val p = perfilSink ?: return
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
                try { a.disable() } catch (e: Exception) { Log.w(TAG, "disable falhou", e) }
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

    fun definirLigado(v: Boolean) {
        val a = adaptador ?: return
        val st = try { a.state } catch (e: Exception) { BluetoothAdapter.STATE_OFF }
        if (v && st == BluetoothAdapter.STATE_TURNING_OFF) { ligarPendente = true; return }      // ainda desligando: espera acabar
        if (v && (st == BluetoothAdapter.STATE_ON || st == BluetoothAdapter.STATE_TURNING_ON)) return
        if (v) { a.enable(); vigiarLigamento() }
        else {
            // solta a ligação do app com o serviço de Bluetooth e só então manda desligar
            aoAntesDeDesligar?.invoke()
            handler.postDelayed({ try { a.disable() } catch (e: Exception) { Log.w(TAG, "disable falhou", e) } }, 700)
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

    /**
     * Procura aparelhos por perto por 12 segundos. Só roda quando o usuário pede: o Wi-Fi e o
     * Bluetooth dividem a mesma antena, e a busca derruba o Wi-Fi por alguns segundos.
     */
    fun iniciarBusca() {
        val a = adaptador ?: return
        if (a.isEnabled && !a.isDiscovering) {
            a.startDiscovery()
            handler.postDelayed({ try { a.cancelDiscovery() } catch (e: Exception) { } }, DURACAO_BUSCA_MS)
        }
    }

    private fun nomeDe(d: BluetoothDevice): String = d.name?.takeIf { it.isNotBlank() } ?: d.address

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
        conectados.contains(d.address), pareandoCom == d.address, iconeDe(d)
    )

    /** Pareados (conectados primeiro) e, depois, os encontrados na busca. */
    fun aparelhos(): List<Aparelho> {
        val a = adaptador ?: return emptyList()
        val pareados = (a.bondedDevices ?: emptySet()).map { montar(it) }
            .sortedWith(compareByDescending<Aparelho> { it.conectado }.thenBy { it.nome.lowercase() })
        val novos = achados.values.filter { it.bondState != BluetoothDevice.BOND_BONDED }
            .map { montar(it) }.sortedBy { it.nome.lowercase() }
        return pareados + novos
    }

    private fun achar(endereco: String): BluetoothDevice? =
        adaptador?.getRemoteDevice(endereco)

    fun parear(endereco: String) {
        erro = null
        val d = achar(endereco) ?: return
        try { adaptador?.cancelDiscovery() } catch (e: Exception) { }
        pareandoCom = endereco
        if (!d.createBond()) { pareandoCom = null; erro = "Não foi possível iniciar o pareamento" }
        aoMudar()
    }

    /** Avisa quando o último aparelho conectado saiu (a tela decide o que mostrar). */
    var aoDesconectado: (() -> Unit)? = null

    private fun perdeu(endereco: String) {
        if (conectados.remove(endereco) && conectados.isEmpty()) aoDesconectado?.invoke()
    }

    /** Aparelhos que a própria pessoa (ou o desligamento da caixa) desconectou de propósito: a caixa não corre atrás deles. */
    private val desconectadosPorMim = HashSet<String>()

    fun conectar(endereco: String) {
        desconectadosPorMim.remove(endereco)
        val d = achar(endereco) ?: return
        try {
            perfilSink?.javaClass?.getMethod("connect", BluetoothDevice::class.java)?.invoke(perfilSink, d)
        } catch (e: Exception) { Log.w(TAG, "falha ao conectar", e); erro = "Não foi possível conectar" }
        aoMudar()
    }

    fun desconectar(endereco: String) {
        desconectadosPorMim.add(endereco)
        val d = achar(endereco) ?: return
        try {
            perfilSink?.javaClass?.getMethod("disconnect", BluetoothDevice::class.java)?.invoke(perfilSink, d)
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
