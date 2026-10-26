package br.com.lmsoftworks.caixa2

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
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
import android.util.Log
import java.util.UUID

/**
 * Teste isolado da conexão com a fita, comandado por adb (sem usar o resto do app):
 *   adb shell am broadcast -a br.com.lmsoftworks.caixa2.TESTE_LED --es acao escanear|direto|auto|escrever|desligar
 * Tudo vai para o log com a etiqueta TesteLed.
 */
object TesteLed {
    private const val TAG = "TesteLed"
    private const val ACAO = "br.com.lmsoftworks.caixa2.TESTE_LED"
    private val UUID_ESCRITA = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")

    @Volatile var ativo = false
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var escrita: BluetoothGattCharacteristic? = null
    private var t0 = 0L
    private var scanCb: ScanCallback? = null

    private fun log(s: String) = Log.d(TAG, "[%5d ms] %s".format(SystemClock.uptimeMillis() - t0, s))

    fun registrar(ctx: Context, aoPausar: () -> Unit, aoRetomar: () -> Unit) {
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val acao = i.getStringExtra("acao") ?: return
                t0 = SystemClock.uptimeMillis()
                ativo = acao != "fim"
                if (acao == "fim") { aoRetomar(); log("teste encerrado, controle normal volta"); return }
                aoPausar()
                executar(c.applicationContext, acao)
            }
        }, IntentFilter(ACAO))
    }

    private fun executar(c: Context, acao: String) {
        val a = BluetoothAdapter.getDefaultAdapter()
        log("acao=$acao bluetooth=${a?.isEnabled}")
        when (acao) {
            "escanear" -> escanear(a, 10_000)
            "direto" -> conectar(c, a, false)
            "auto" -> conectar(c, a, true)
            "escrever" -> escrever()
            "desligar" -> { gatt?.disconnect(); log("disconnect() chamado") }
            "limpar" -> { try { gatt?.close() } catch (e: Exception) { }; gatt = null; escrita = null; log("gatt fechado") }
        }
    }

    private fun escanear(a: BluetoothAdapter, ms: Long) {
        val vistos = HashSet<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(tipo: Int, r: ScanResult) {
                val nome = r.scanRecord?.deviceName ?: ""
                if (vistos.add(r.device.address)) log("visto ${r.device.address} nome='$nome' rssi=${r.rssi} conectavel=${if (android.os.Build.VERSION.SDK_INT >= 26) "?" else "n/d"}")
                if (nome.startsWith("LEDnet")) log("FITA ${r.device.address} rssi=${r.rssi}")
            }
            override fun onScanFailed(codigo: Int) { log("busca falhou: $codigo") }
        }
        scanCb = cb
        a.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        handler.postDelayed({
            try { a.bluetoothLeScanner?.stopScan(cb) } catch (e: Exception) { }
            log("busca terminada, aparelhos vistos: ${vistos.size}")
        }, ms)
    }

    private fun conectar(c: Context, a: BluetoothAdapter, autoConnect: Boolean) {
        try { gatt?.close() } catch (e: Exception) { }
        val d: BluetoothDevice = a.getRemoteDevice(LedControle.ENDERECO)
        log("connectGatt autoConnect=$autoConnect estado_bond=${d.bondState}")
        gatt = d.connectGatt(c, autoConnect, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, novo: Int) {
                log("conexao status=$status estado=$novo (${if (novo == BluetoothProfile.STATE_CONNECTED) "CONECTADO" else "desconectado"})")
                if (novo == BluetoothProfile.STATE_CONNECTED) {
                    handler.postDelayed({ log("discoverServices -> ${g.discoverServices()}") }, 600)
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                log("servicos status=$status total=${g.services.size}")
                for (s in g.services) {
                    log("  servico ${s.uuid}")
                    for (ch in s.characteristics) {
                        log("    carac ${ch.uuid} props=0x${Integer.toHexString(ch.properties)}")
                        if (ch.uuid == UUID_ESCRITA) escrita = ch
                    }
                }
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                log("escrita concluida status=$status")
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                log("aviso da fita: ${ch.value?.size} bytes")
            }
        }, BluetoothDevice.TRANSPORT_LE)
        handler.postDelayed({
            log("fim da espera de 30 s: ${if (escrita != null) "tem característica de escrita" else "SEM característica de escrita"}")
        }, 30_000)
    }

    /** Liga a fita (comando 3b 23) e pinta de vermelho (31 ff 00 00), só para ver se a escrita chega. */
    private fun escrever() {
        val ch = escrita
        val g = gatt
        if (ch == null || g == null) { log("sem conexão/característica para escrever"); return }
        fun quadro(cnt: Int, tipo: Int, cmd: IntArray): ByteArray {
            val tam = cmd.size + 2
            val b = ArrayList<Int>()
            b.add(cnt shr 8); b.add(cnt and 0xff); b.add(0x80); b.add(0); b.add(0); b.add(tam - 1); b.add(tam); b.add(tipo)
            cmd.forEach { b.add(it) }; b.add(cmd.sum() and 0xff)
            return ByteArray(b.size) { b[it].toByte() }
        }
        ch.value = quadro(1, 0x0b, intArrayOf(0x3b, 0x23, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        log("write(ligar) -> ${g.writeCharacteristic(ch)}")
        handler.postDelayed({
            ch.value = quadro(2, 0x0b, intArrayOf(0x31, 0xff, 0, 0, 0, 0, 0x0f))
            log("write(vermelho) -> ${g.writeCharacteristic(ch)}")
        }, 400)
    }
}
