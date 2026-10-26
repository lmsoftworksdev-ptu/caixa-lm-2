package br.com.lmsoftworks.caixa2

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

/**
 * Conexão Bluetooth LE com a fita, copiada do app oficial Zengge (com.zengge.blev2 1.4.7, biblioteca
 * zengge.hagallbjarkan: HBConnectionImpl, ZGHBDeviceHandler, DeviceScanner). O que é igual ao Zengge:
 *  - busca contínua (LOW_LATENCY, sem filtro) ligada o tempo todo, mesmo conectado;
 *  - só conecta depois de ver o anúncio da fita; o alvo é o BluetoothDevice do ScanResult;
 *  - connectGatt(autoConnect=false, TRANSPORT_LE) e discoverServices() logo que conecta;
 *  - ordem: serviços -> requestMtu(186) -> avisos (CCCD) -> 250 ms -> "conectado";
 *  - escrita estritamente serial (o próximo só sai depois do onCharacteristicWrite do anterior);
 *  - toda desconexão faz refresh() + disconnect() + close();
 *  - tempo máximo de 15 s para ficar pronta;
 *  - se um link que já funcionava cai, reconecta na hora (o laço do HBConnectionImpl faz uma nova tentativa).
 * Tudo que avisa o resto do app chega na thread principal.
 */
class FitaBle(
    contexto: Context,
    private val endereco: String,
    private val log: (String) -> Unit,
    private val liberado: () -> Boolean,
    private val ouvinte: Ouvinte
) {
    interface Ouvinte {
        /** Ligação pronta para escrever (onConnected do Zengge). */
        fun aoConectar()
        fun aoDesconectar()
        /** A fita mandou um aviso (FF02). Pode chegar antes de aoConectar. */
        fun aoAviso(dados: ByteArray)
        /** A escrita anterior foi confirmada: pode mandar a próxima. */
        fun aoEscritaConcluida()
        fun aoMudarEstado()
    }

    companion object {
        private val SERVICO = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")
        private val ESCRITA = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        private val AVISOS = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MTU_PEDIDO = 186                 // CipherSuite.TLS_RSA_WITH_CAMELLIA_128_CBC_SHA256 = 186
        private const val TEMPO_CONEXAO_MS = 15_000L       // syncConnect(15000) do ZGHBDeviceHandler
        private const val ATRASO_PRONTA_MS = 250L          // Thread.sleep(250) depois do MTU
        private const val PAUSA_ENTRE_TENTATIVAS_MS = 1_500L
        private const val VISTO_RECENTE_MS = 5_000L
        private const val SEM_ANUNCIO_MS = 20_000L         // sem ver a fita no ar por tanto tempo: tenta direto mesmo assim
        private const val ESCRITA_SEM_CONFIRMACAO_MS = 2_000L
    }

    private val ctx = contexto.applicationContext
    private val principal = Handler(Looper.getMainLooper())
    private val adaptador: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private var ativo = false
    private var conexao: Conexao? = null
    private var dispositivo: BluetoothDevice? = null
    private var ultimoVisto = 0L
    private var fimUltima = 0L
    private var esperandoDesde = 0L
    private var reconexaoImediata = false
    private var scanRodando = false
    private var proximoScan = 0L
    private var receptorRegistrado = false

    val pronta: Boolean get() = conexao?.pronta == true
    val tentando: Boolean get() = conexao != null && conexao?.pronta != true
    val recebeuAviso: Boolean get() = conexao?.recebeuAviso == true

    private fun agora() = SystemClock.uptimeMillis()

    // ---------------------------------------------------------------- ciclo de vida

    fun iniciar() {
        if (ativo) return
        ativo = true
        esperandoDesde = agora()
        if (!receptorRegistrado) {
            receptorRegistrado = true
            ctx.registerReceiver(receptorAdaptador, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        }
        principal.removeCallbacks(verificar)
        principal.post(verificar)
    }

    fun parar() {
        ativo = false
        principal.removeCallbacks(verificar)
        pararScan()
        conexao?.desconectar(true)
        conexao = null
        if (receptorRegistrado) {
            receptorRegistrado = false
            try { ctx.unregisterReceiver(receptorAdaptador) } catch (e: Exception) { }
        }
    }

    /** Botão "Conectar à fita": tenta já, sem esperar a pausa nem o anúncio. */
    fun tentarAgora() {
        val a = adaptador ?: return
        if (!ativo || conexao != null || !a.isEnabled || !liberado()) return
        reconexaoImediata = false
        abrir(dispositivo ?: a.getRemoteDevice(endereco))
    }

    /** Fecha a ligação atual do jeito do Zengge; o ciclo normal tenta de novo depois. */
    fun derrubar(motivo: String) {
        val c = conexao ?: return
        log("derrubando a ligacao: $motivo")
        c.desconectar(true)
    }

    /** Uma escrita por vez. Devolve false se a anterior ainda não foi confirmada ou se o Android recusou. */
    fun escrever(dados: ByteArray, comResposta: Boolean): Boolean = conexao?.escrever(dados, comResposta) ?: false

    // ---------------------------------------------------------------- decisão de conectar (ZGHBDeviceHandler.fetch)

    private val verificar = object : Runnable {
        override fun run() {
            if (!ativo) return
            val a = adaptador
            if (a != null && a.isEnabled) {
                if (!scanRodando && agora() >= proximoScan && !pronta) iniciarScan(a)
                decidir()
            }
            principal.postDelayed(this, 500)
        }
    }

    private fun decidir() {
        if (!ativo || conexao != null || !liberado()) return
        val a = adaptador ?: return
        if (!a.isEnabled) return
        val t = agora()
        val d = dispositivo
        if (reconexaoImediata) {
            reconexaoImediata = false
            log("link caiu: reconectando na hora, como o Zengge")
            abrir(d ?: a.getRemoteDevice(endereco))
            return
        }
        if (t - fimUltima < PAUSA_ENTRE_TENTATIVAS_MS) return
        if (d != null && t - ultimoVisto < VISTO_RECENTE_MS) {
            abrir(d)
        } else if (t - esperandoDesde > SEM_ANUNCIO_MS) {
            log("fita nao apareceu na busca ha ${SEM_ANUNCIO_MS / 1000} s: tentando direto")
            esperandoDesde = t
            abrir(d ?: a.getRemoteDevice(endereco))
        }
    }

    private fun abrir(d: BluetoothDevice) {
        val c = Conexao()
        conexao = c
        log("Connecting... ${d.address}")
        try {
            c.conectar(d)
        } catch (e: Exception) {
            log("falha ao chamar connectGatt: ${e.message}")
            conexao = null
            fimUltima = agora(); esperandoDesde = fimUltima
        }
        ouvinte.aoMudarEstado()
    }

    private fun conexaoEncerrada(c: Conexao) {
        principal.removeCallbacks(c.limite)
        if (conexao !== c) return
        conexao = null
        fimUltima = agora(); esperandoDesde = fimUltima
        proximoScan = 0
        if (c.foiPronta && !c.porMim && ativo) reconexaoImediata = true
        ouvinte.aoDesconectar()
        ouvinte.aoMudarEstado()
        if (ativo) principal.post { decidir() }
    }

    // ---------------------------------------------------------------- busca (DeviceScanner)

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(tipo: Int, r: ScanResult) {
            val d = r.device ?: return
            if (d.address != endereco) return
            val t = agora()
            if (t - ultimoVisto > 10_000) log("fita no ar: rssi=${r.rssi}")
            dispositivo = d
            ultimoVisto = t
            if (conexao == null) decidir()
        }

        override fun onBatchScanResults(lista: MutableList<ScanResult>) { lista.forEach { onScanResult(0, it) } }

        override fun onScanFailed(codigo: Int) {
            log("busca falhou: codigo=$codigo")
            // 1 = SCAN_FAILED_ALREADY_STARTED: a busca já está rodando, está tudo certo
            scanRodando = codigo == 1
            proximoScan = agora() + 3000
        }
    }

    private fun iniciarScan(a: BluetoothAdapter) {
        try {
            val s = a.bluetoothLeScanner
            if (s == null) { proximoScan = agora() + 2000; return }
            s.startScan(null, ScanSettings.Builder().setReportDelay(0L).setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
            scanRodando = true
            log("busca continua ligada (LOW_LATENCY, sem filtro)")
        } catch (e: Exception) {
            log("nao foi possivel ligar a busca: ${e.message}")
            proximoScan = agora() + 3000
        }
    }

    private fun pararScan() {
        if (!scanRodando) return
        scanRodando = false
        try { adaptador?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) { }
    }

    private val receptorAdaptador = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    log("Bluetooth desligando")
                    scanRodando = false
                    conexao?.desconectar(true)
                }
                BluetoothAdapter.STATE_ON -> { scanRodando = false; proximoScan = 0 }
            }
        }
    }

    // ---------------------------------------------------------------- uma ligação (HBConnectionImpl)

    private fun hex(b: ByteArray, max: Int = 40): String =
        b.take(max).joinToString(" ") { "%02x".format(it) } + if (b.size > max) " ..." else ""

    private inner class Conexao : BluetoothGattCallback() {
        private var gatt: BluetoothGatt? = null
        private val servicos = HashMap<UUID, BluetoothGattService>()
        private var estado = 0
        private var servicosOk = false
        private var desconectada = false
        private var encerrada = false
        private var emEscrita = false
        private var inicioEscrita = 0L
        @Volatile var pronta = false
        @Volatile var recebeuAviso = false
        @Volatile var foiPronta = false
        @Volatile var porMim = false

        val limite = Runnable {
            if (!pronta && !encerrada) {
                log("tempo esgotado (${TEMPO_CONEXAO_MS / 1000} s) sem ficar pronta")
                desconectar(false)
            }
        }

        fun conectar(d: BluetoothDevice) {
            principal.postDelayed(limite, TEMPO_CONEXAO_MS)
            gatt = d.connectGatt(ctx, false, this, BluetoothDevice.TRANSPORT_LE)
        }

        /** disconnect() do HBConnectionImpl. */
        fun desconectar(foiPedido: Boolean) {
            synchronized(this) {
                if (desconectada) return
                desconectada = true
                if (foiPedido) porMim = true
                aoDesconectada()
            }
        }

        /** onDisconnected() do HBConnectionImpl: limpa tudo e solta a ligação (refresh + disconnect + close). */
        private fun aoDesconectada() {
            synchronized(this) {
                if (encerrada) return
                encerrada = true
                log("onDisconnected.")
                servicosOk = false
                servicos.clear()
                emEscrita = false
                pronta = false
                liberar()
                estado = 0
            }
            principal.post { conexaoEncerrada(this) }
        }

        private fun liberar() {
            val g = gatt ?: return
            try {
                g.javaClass.getMethod("refresh").invoke(g)
            } catch (e: Exception) {
                log("refresh falhou: ${e.message}")
            }
            try { g.disconnect() } catch (e: Exception) { }
            try { g.close() } catch (e: Exception) { }
        }

        fun escrever(dados: ByteArray, comResposta: Boolean): Boolean {
            synchronized(this) {
                if (desconectada || encerrada || estado != 2) return false
                val t = SystemClock.uptimeMillis()
                if (emEscrita) {
                    if (t - inicioEscrita < ESCRITA_SEM_CONFIRMACAO_MS) return false
                    log("escrita sem confirmacao ha ${ESCRITA_SEM_CONFIRMACAO_MS / 1000} s: liberando a fila")
                    emEscrita = false
                }
                val g = gatt ?: return false
                val ch = servicos[SERVICO]?.getCharacteristic(ESCRITA) ?: return false
                ch.writeType = if (comResposta) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = dados
                // o fluxo de cor (36) passa ~9 vezes por segundo: não entra no arquivo de log
                if (!(dados.size > 8 && dados[8].toInt() == 0x36)) log("writer.bytes : ${hex(dados)}")
                val ok = g.writeCharacteristic(ch)
                if (ok) { emEscrita = true; inicioEscrita = t }
                return ok
            }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, novo: Int) {
            synchronized(this) {
                if (gatt == null) gatt = g
                if (novo != 2) estado = novo
                log(" connection state  $estado status $status")
                if (status == 0 && novo == 2 && !desconectada) {
                    servicosOk = false
                    servicos.clear()
                    if (g.discoverServices()) {
                        log(" discover services succeed. ")
                        return
                    }
                }
                aoDesconectada()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            synchronized(this) {
                if (status == 0 && !desconectada) {
                    servicosOk = true
                    for (s in g.services) {
                        servicos[s.uuid] = s
                        log("service ${s.uuid} (${s.characteristics.joinToString { it.uuid.toString().substring(4, 8) }})")
                    }
                    if (servicos[SERVICO] == null) {
                        log("servico FFFF nao encontrado")
                        aoDesconectada()
                        return
                    }
                    if (!g.requestMtu(MTU_PEDIDO)) {
                        aoDesconectada()
                        return
                    }
                    log(" request mtu succeed. ")
                    return
                }
                aoDesconectada()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (!servicosOk) return
            synchronized(this) {
                if (status == 0 && !desconectada) {
                    log(" onMtuChange $mtu")
                    val ch = servicos[SERVICO]?.getCharacteristic(AVISOS)
                    if (ch != null) {
                        if (!g.setCharacteristicNotification(ch, true)) {
                            log(" enable notification failed.")
                            aoDesconectada()
                            return
                        }
                        val d = ch.getDescriptor(CCCD)
                        if (d != null) {
                            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            log(" enable notification succeed. ${g.writeDescriptor(d)}")
                        }
                    }
                    estado = 2
                    principal.postDelayed({ sinalizarPronta() }, ATRASO_PRONTA_MS)
                    return
                }
                aoDesconectada()
            }
        }

        private fun sinalizarPronta() {
            synchronized(this) {
                if (desconectada || encerrada || pronta) return
                pronta = true
                foiPronta = true
            }
            principal.removeCallbacks(limite)
            log("onConnected.")
            this@FitaBle.pararScan()   // conectada, a busca não serve para nada e atrapalha o Wi-Fi e o som (mesma antena 2,4 GHz)
            ouvinte.aoConectar()
            ouvinte.aoMudarEstado()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v: ByteArray
            synchronized(this) {
                if (desconectada || estado != 2) { aoDesconectada(); return }
                v = c.value?.copyOf() ?: return
                if (!recebeuAviso) log("aviso da fita: ${hex(v, 24)}")
                recebeuAviso = true
            }
            principal.post { ouvinte.aoAviso(v) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            synchronized(this) {
                emEscrita = false
                if (status == 0 && !desconectada) {
                    principal.post { ouvinte.aoEscritaConcluida() }
                    return
                }
                log("escrita falhou: status=$status")
                aoDesconectada()
            }
        }
    }
}
