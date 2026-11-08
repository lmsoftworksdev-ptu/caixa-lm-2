package br.com.lmsoftworks.caixa2

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Teste de conexões (Configurações > Sistema > Teste de conexões): mostra em tempo real o que está ligado à caixa — dispositivos USB (OTG)
 * com nome e código, Wi-Fi (sinal, redes achadas), Bluetooth, interfaces de rede (uma placa/adaptador novo aparece aqui) e a energia.
 * Atualiza sozinho a cada segundo; quando um dispositivo USB entra ou sai, avisa na hora.
 */
class TelaDiagnostico(private val c: Context) : FrameLayout(c) {
    private val handler = Handler(Looper.getMainLooper())
    private val coluna = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val rolagem = RolagemFina(c)

    private val usb = bloco("USB (OTG)")
    private val wifi = bloco("Wi-Fi")
    private val bt = bloco("Bluetooth")
    private val redes = bloco("Interfaces de rede")
    private val energia = bloco("Energia e sistema")

    private val vistos = HashSet<String>()
    private var primeiraVez = true
    private var ultimaVarredura = 0L
    private val interfacesIniciais = HashSet<String>()

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            val nome = d?.let { rotulo(it) } ?: "dispositivo"
            if (i.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) Aviso.mostrar(c, "USB conectado: $nome") else Aviso.mostrar(c, "USB removido: $nome")
            atualizar()
        }
    }
    private var registrado = false

    init {
        addView(texto(c, "Teste de conexões", 44, Tema.TEXTO, true), LayoutParams(-2, -2).apply { leftMargin = Tema.MARGEM_LATERAL.d(); topMargin = 24.d() })
        addView(texto(c, "Atualiza sozinho. Ligue um dispositivo no USB (OTG) e ele aparece aqui.", 21, Tema.TEXTO3),
            LayoutParams(-2, -2).apply { leftMargin = Tema.MARGEM_LATERAL.d(); topMargin = 84.d() })
        rolagem.addView(coluna, LayoutParams(-1, -2))
        addView(rolagem, LayoutParams(-1, -1).apply { topMargin = 130.d(); leftMargin = Tema.MARGEM_LATERAL.d(); rightMargin = Tema.MARGEM_LATERAL.d() })
    }

    private fun bloco(titulo: String): TextView {
        val conteudo = texto(c, "…", 20, Tema.TEXTO2).apply { setLineSpacing(0f, 1.12f) }
        val caixa = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = fundoArredondado(Tema.BOTAO, 22.d(), Tema.BOTAO_BORDA)
            setPadding(26.d(), 18.d(), 26.d(), 20.d())
            addView(texto(c, titulo, 24, Tema.TEXTO, true))
            addView(conteudo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.d() })
        }
        coluna.addView(caixa, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14.d() })
        return conteudo
    }

    private fun hex4(v: Int) = String.format("%04x", v)

    private fun nomeClasse(k: Int) = when (k) {
        UsbConstants.USB_CLASS_PER_INTERFACE -> "por interface"
        UsbConstants.USB_CLASS_AUDIO -> "áudio"
        UsbConstants.USB_CLASS_COMM -> "comunicação"
        UsbConstants.USB_CLASS_HID -> "teclado/mouse (HID)"
        UsbConstants.USB_CLASS_MASS_STORAGE -> "armazenamento"
        UsbConstants.USB_CLASS_HUB -> "hub"
        UsbConstants.USB_CLASS_CDC_DATA -> "dados"
        UsbConstants.USB_CLASS_WIRELESS_CONTROLLER -> "sem fio (Bluetooth/Wi-Fi)"
        UsbConstants.USB_CLASS_VENDOR_SPEC -> "específica do fabricante (Wi-Fi, rede...)"
        else -> "0x" + Integer.toHexString(k)
    }

    private fun rotulo(d: UsbDevice): String = "${d.productName ?: "sem nome"} [${hex4(d.vendorId)}:${hex4(d.productId)}]"

    // ------------------------------------------------------------------ atualização

    private val tique = object : Runnable {
        override fun run() { if (isShown) atualizar(); handler.postDelayed(this, 1000) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try {
            c.registerReceiver(receptor, IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) })
            registrado = true
        } catch (_: Throwable) { }
        handler.post(tique)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tique)
        if (registrado) { try { c.unregisterReceiver(receptor) } catch (_: Throwable) { }; registrado = false }
        super.onDetachedFromWindow()
    }

    /** Chamada quando a tela abre: zera o que já foi visto, para avisar só do que mudar depois. */
    fun aoMostrar() { vistos.clear(); primeiraVez = true; interfacesIniciais.clear(); atualizar() }

    fun atualizar() {
        try { atualizarUsb() } catch (e: Throwable) { usb.text = "Erro ao ler o USB: ${e.javaClass.simpleName}" }
        try { atualizarWifi() } catch (e: Throwable) { wifi.text = "Erro ao ler o Wi-Fi: ${e.javaClass.simpleName}" }
        try { atualizarBt() } catch (e: Throwable) { bt.text = "Erro ao ler o Bluetooth: ${e.javaClass.simpleName}" }
        try { atualizarRedes() } catch (e: Throwable) { redes.text = "Erro ao ler as redes: ${e.javaClass.simpleName}" }
        try { atualizarEnergia() } catch (e: Throwable) { energia.text = "Erro ao ler a energia: ${e.javaClass.simpleName}" }
    }

    private fun atualizarUsb() {
        val um = c.getSystemService(Context.USB_SERVICE) as UsbManager
        val lista = um.deviceList.values.toList()
        val host = try { File("/sys/bus/usb/devices/usb1").exists() } catch (_: Throwable) { false }
        val sb = StringBuilder()
        sb.append(if (host) "Porta em modo HOST (OTG ativo)" else "Porta em modo normal (ligada ao computador/carregador)")
        sb.append("\n")
        if (lista.isEmpty()) sb.append("Nenhum dispositivo USB conectado.")
        else for (d in lista) {
            val interfaces = (0 until d.interfaceCount).joinToString(", ") { nomeClasse(d.getInterface(it).interfaceClass) }
            sb.append("\n• ${d.productName ?: "(sem nome)"}   [${hex4(d.vendorId)}:${hex4(d.productId)}]")
            sb.append("\n   Fabricante: ${d.manufacturerName ?: "?"}")
            sb.append("\n   Classe: ${nomeClasse(d.deviceClass)}")
            if (interfaces.isNotEmpty()) sb.append("\n   Interfaces: $interfaces")
            sb.append("\n")
            if (vistos.add(d.deviceName) && !primeiraVez) Aviso.mostrar(c, "USB conectado: ${rotulo(d)}")
        }
        // dispositivo que saiu
        val atuais = lista.map { it.deviceName }.toSet()
        if (vistos.retainAll(atuais) && !primeiraVez) Aviso.mostrar(c, "USB removido")
        primeiraVez = false
        usb.text = sb.toString().trimEnd()
    }

    private fun atualizarWifi() {
        val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val sb = StringBuilder()
        if (!wm.isWifiEnabled) { wifi.text = "Wi-Fi desligado"; return }
        val info = wm.connectionInfo
        val conectado = info != null && info.networkId != -1 && info.bssid != null && info.bssid != "00:00:00:00:00:00"
        if (conectado) {
            sb.append("Conectado a ${info.ssid?.trim('"')}")
            sb.append("\nSinal: ${info.rssi} dBm   Velocidade: ${info.linkSpeed} Mbps   ${info.frequency} MHz")
        } else sb.append("Ligado, mas sem rede conectada")
        val agora = SystemClock.uptimeMillis()
        if (agora - ultimaVarredura > 10_000L) { ultimaVarredura = agora; try { wm.startScan() } catch (_: Throwable) { } }
        val achadas = try { wm.scanResults.orEmpty() } catch (_: Throwable) { emptyList() }
        sb.append("\nRedes achadas: ${achadas.size}")
        for (r in achadas.sortedByDescending { it.level }.take(6)) sb.append("\n   ${r.SSID.ifBlank { "(oculta)" }}   ${r.level} dBm   ${r.frequency} MHz")
        if (achadas.isEmpty()) sb.append("   (nenhuma — se for sempre assim, é a antena)")
        wifi.text = sb.toString()
    }

    private fun atualizarBt() {
        val ad = BluetoothAdapter.getDefaultAdapter()
        if (ad == null) { bt.text = "Sem Bluetooth neste aparelho"; return }
        val estado = when (ad.state) {
            BluetoothAdapter.STATE_ON -> "ligado"; BluetoothAdapter.STATE_OFF -> "desligado"
            BluetoothAdapter.STATE_TURNING_ON -> "ligando…"; BluetoothAdapter.STATE_TURNING_OFF -> "desligando…"; else -> "?"
        }
        val sb = StringBuilder("Bluetooth $estado   Nome: ${ad.name}")
        if (ad.state == BluetoothAdapter.STATE_ON) {
            val pareados = try { ad.bondedDevices.orEmpty() } catch (_: Throwable) { emptySet() }
            sb.append("\nPareados: ${pareados.size}")
            for (d in pareados.take(5)) sb.append("\n   ${d.name ?: d.address}")
        }
        bt.text = sb.toString()
    }

    private fun atualizarRedes() {
        val sb = StringBuilder()
        val atuais = HashSet<String>()
        for (n in NetworkInterface.getNetworkInterfaces().toList().sortedBy { it.name }) {
            if (n.isLoopback) continue
            atuais.add(n.name)
            val ip = n.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
            val nova = if (interfacesIniciais.isNotEmpty() && n.name !in interfacesIniciais) "   ← NOVA" else ""
            sb.append("${n.name}   ${if (n.isUp) "ativa" else "desligada"}   ${ip ?: "sem IP"}$nova\n")
        }
        if (interfacesIniciais.isEmpty()) interfacesIniciais.addAll(atuais)
        redes.text = sb.toString().trimEnd().ifEmpty { "Nenhuma interface de rede ativa" }
    }

    private fun atualizarEnergia() {
        val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val sb = StringBuilder()
        if (b != null) {
            val mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            val temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            val plug = when (b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) { 1 -> "tomada"; 2 -> "USB"; 4 -> "sem fio"; 0 -> "nenhuma"; else -> "?" }
            sb.append("Tensão: ${if (mv > 0) "%.2f V".format(mv / 1000.0) else "?"}   Temperatura: ${if (temp > 0) "%.1f °C".format(temp / 10.0) else "?"}")
            sb.append("\nFonte externa: $plug   Bateria presente: ${if (b.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) "sim" else "não"}   Nível: ${b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)}%")
        }
        val seg = SystemClock.elapsedRealtime() / 1000
        sb.append("\nLigado há ${seg / 60} min ${seg % 60} s   Hora do aparelho: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale("pt", "BR")).format(java.util.Date())}")
        energia.text = sb.toString()
    }
}
