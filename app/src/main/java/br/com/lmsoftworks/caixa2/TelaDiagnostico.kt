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
import android.os.Debug
import android.view.Choreographer
import android.view.ViewGroup
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
    private val desempenho = bloco("Desempenho")

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
        override fun run() {
            if (isShown) { atualizar(); iniciarMedicao() } else pararMedicao()
            handler.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ desempenho

    /** Medição dos quadros: só enquanto esta tela está aberta (pedir quadro o tempo todo já pesa um pouco, então não fica ligado fora daqui). */
    private var medindo = false
    private var ultimoQuadroNs = 0L
    private var janelaInicioMs = 0L
    private var quadros = 0
    private var somaMs = 0.0
    private var piorMs = 0.0
    private var lentos = 0          // intervalos acima de 33 ms (menos de 30 quadros por segundo)
    private var muitoLentos = 0     // acima de 100 ms (a pessoa percebe o tranco)
    private var resQuadros = ""
    private val aoQuadro = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!medindo) return
            if (ultimoQuadroNs != 0L) {
                val ms = (frameTimeNanos - ultimoQuadroNs) / 1_000_000.0
                quadros++; somaMs += ms
                if (ms > piorMs) piorMs = ms
                if (ms > 33.5) lentos++
                if (ms > 100.0) muitoLentos++
            }
            ultimoQuadroNs = frameTimeNanos
            val agora = SystemClock.uptimeMillis()
            if (agora - janelaInicioMs >= 5_000L && quadros > 0) {      // janela de 5 s: o resultado acompanha o que está acontecendo agora
                val s = (agora - janelaInicioMs) / 1000.0
                resQuadros = "Quadros por segundo: %.0f   Tempo médio: %.0f ms   Pior: %.0f ms\nQuadros lentos (>33 ms): %d de %d (%.0f%%)   Travadas (>100 ms): %d".format(
                    quadros / s, somaMs / quadros, piorMs, lentos, quadros, 100.0 * lentos / quadros, muitoLentos)
                janelaInicioMs = agora; quadros = 0; somaMs = 0.0; piorMs = 0.0; lentos = 0; muitoLentos = 0
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun iniciarMedicao() {
        if (medindo) return
        medindo = true; ultimoQuadroNs = 0L; janelaInicioMs = SystemClock.uptimeMillis(); resQuadros = ""
        quadros = 0; somaMs = 0.0; piorMs = 0.0; lentos = 0; muitoLentos = 0
        Choreographer.getInstance().postFrameCallback(aoQuadro)
    }

    private fun pararMedicao() {
        if (!medindo) return
        medindo = false
        Choreographer.getInstance().removeFrameCallback(aoQuadro)
    }

    private var memoriaEm = 0L
    private var resMemoria = ""
    private var cpuAntesMs = 0L
    private var cpuRelogioAntes = 0L
    private var resCpu = ""

    private fun lerArquivo(caminho: String): String = try { File(caminho).readText().trim() } catch (_: Throwable) { "" }

    private fun contarViews(v: View): Int = 1 + if (v is ViewGroup) (0 until v.childCount).sumOf { contarViews(v.getChildAt(it)) } else 0

    private fun atualizarDesempenho() {
        val agora = SystemClock.uptimeMillis()
        // CPU gasta por este app desde a última leitura, em % de um núcleo (a caixa tem 4)
        val cpuMs = android.os.Process.getElapsedCpuTime()
        if (cpuRelogioAntes != 0L && agora > cpuRelogioAntes) resCpu = "%.0f%%".format(100.0 * (cpuMs - cpuAntesMs) / (agora - cpuRelogioAntes))
        cpuAntesMs = cpuMs; cpuRelogioAntes = agora
        // memória e views custam mais para ler: a cada 5 s
        if (agora - memoriaEm >= 5_000L) {
            memoriaEm = agora
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val dm = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            val rt = Runtime.getRuntime()
            val threads = Regex("Threads:\\s*(\\d+)").find(lerArquivo("/proc/self/status"))?.groupValues?.get(1) ?: "?"
            val views = try { contarViews(rootView) } catch (_: Throwable) { -1 }
            resMemoria = "Memória livre: ${mi.availMem / 1048576} MB de ${mi.totalMem / 1048576} MB${if (mi.lowMemory) "  (POUCA!)" else ""}" +
                "\nEste app: ${dm.totalPss / 1024} MB (nativa ${dm.nativePss / 1024}, Java ${dm.dalvikPss / 1024}, heap ${(rt.totalMemory() - rt.freeMemory()) / 1048576} MB)" +
                "\nViews na tela: $views   Threads do app: $threads"
        }
        val carga = lerArquivo("/proc/loadavg").split(" ").take(3).joinToString(" ")
        val freq = lerArquivo("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq").toLongOrNull()?.let { "${it / 1000} MHz" } ?: "?"
        val freqMax = lerArquivo("/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq").toLongOrNull()?.let { "${it / 1000} MHz" } ?: "?"
        val sb = StringBuilder()
        if (resQuadros.isNotEmpty()) sb.append(resQuadros) else sb.append("Medindo quadros… (leva 5 s)")
        sb.append("\nCPU deste app: ${resCpu.ifEmpty { "…" }} de um núcleo   Carga do sistema: $carga   Processador: $freq (máx. $freqMax)")
        if (resMemoria.isNotEmpty()) sb.append("\n").append(resMemoria)
        sb.append("\nConsultas ao sistema (dumpsys): ${SessaoAudioAndroid.consultas} feitas, ${SessaoAudioAndroid.reaproveitadas} reaproveitadas\nTarefas em segundo plano: ${Tarefas.rodadas} já rodaram, ${Tarefas.vivas} threads reaproveitáveis agora")
        desempenho.text = sb.toString()
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
        pararMedicao()
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
        try { atualizarDesempenho() } catch (e: Throwable) { desempenho.text = "Erro ao medir o desempenho: ${e.javaClass.simpleName}" }
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
