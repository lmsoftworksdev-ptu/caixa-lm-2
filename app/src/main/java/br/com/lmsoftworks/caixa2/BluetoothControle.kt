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
    val nomeCaixa: String get() = adaptador?.name ?: NOME_CAIXA
    val algumConectado: Boolean get() = conectados.isNotEmpty()

    fun nomeConectado(): String? =
        conectados.firstNotNullOfOrNull { end -> adaptador?.bondedDevices?.firstOrNull { it.address == end }?.let(::nomeDe) }

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            when (i.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
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
                BluetoothDevice.ACTION_ACL_CONNECTED -> if (d != null && d.bondState == BluetoothDevice.BOND_BONDED) conectados.add(d.address)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (d != null) conectados.remove(d.address)
                ACAO_SINK -> if (d != null) {
                    when (i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                        BluetoothProfile.STATE_CONNECTING -> aoConectando?.invoke()
                        BluetoothProfile.STATE_CONNECTED -> { if (conectados.add(d.address)) aoConectado?.invoke() }
                        BluetoothProfile.STATE_DISCONNECTED -> conectados.remove(d.address)
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
        }
        ctx.registerReceiver(receptor, f)
        ctx.registerReceiver(receptorPareamento, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply { priority = 1000 })
        if (!a.isEnabled) a.enable() else aoLigar()
        aoMudar()
    }

    fun parar() {
        try { ctx.unregisterReceiver(receptor) } catch (e: IllegalArgumentException) { }
        try { ctx.unregisterReceiver(receptorPareamento) } catch (e: IllegalArgumentException) { }
        handler.removeCallbacksAndMessages(null)
        try { adaptador?.cancelDiscovery() } catch (e: Exception) { }
        adaptador?.closeProfileProxy(PERFIL_A2DP_SINK, perfilSink)
        perfilSink = null
    }

    private fun aoLigar() {
        val a = adaptador ?: return
        if (a.name != NOME_CAIXA) a.name = NOME_CAIXA
        // aparelhos já ligados e pareados
        a.bondedDevices?.forEach { if (estaConectado(it)) conectados.add(it.address) }
        if (perfilSink == null) {
            a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    perfilSink = proxy
                    proxy.connectedDevices.forEach { conectados.add(it.address) }
                    aoMudar()
                }
                override fun onServiceDisconnected(profile: Int) { perfilSink = null }
            }, PERFIL_A2DP_SINK)
        }
        aoMudar()
    }

    private fun estaConectado(d: BluetoothDevice): Boolean = try {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as Boolean
    } catch (e: Exception) { false }

    fun definirLigado(v: Boolean) {
        val a = adaptador ?: return
        if (v) a.enable() else a.disable()
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

    fun conectar(endereco: String) {
        val d = achar(endereco) ?: return
        try {
            perfilSink?.javaClass?.getMethod("connect", BluetoothDevice::class.java)?.invoke(perfilSink, d)
        } catch (e: Exception) { Log.w(TAG, "falha ao conectar", e); erro = "Não foi possível conectar" }
        aoMudar()
    }

    fun desconectar(endereco: String) {
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
        aoMudar()
    }

    fun limparErro() { erro = null }
}
