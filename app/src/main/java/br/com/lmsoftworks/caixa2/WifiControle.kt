package br.com.lmsoftworks.caixa2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Fala com o Wi-Fi de verdade: procura redes, conecta, desconecta e esquece. */
class WifiControle(contexto: Context, private val aoMudar: () -> Unit) {

    class Rede(
        val ssid: String,
        val nivel: Int,            // 0 a 4 barras
        val seguranca: String,     // "Aberta", "WPA2", "WEP"...
        val protegida: Boolean,
        val suportada: Boolean     // redes corporativas (EAP) ainda não
    )

    private val ctx = contexto.applicationContext
    private val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())

    var redes: List<Rede> = emptyList(); private set
    var conectadoSsid: String? = null; private set
    var conectando: String? = null; private set
    var erro: String? = null; private set
    var erroSsid: String? = null; private set
    var procurar = false           // só procura redes enquanto a tela de Wi-Fi está aberta

    private var idTentativa = -1
    private val arquivoLog = java.io.File(ctx.filesDir, "wifi.log")
    private val formatoLog = java.text.SimpleDateFormat("dd HH:mm:ss", java.util.Locale.US)

    /** Diário do Wi-Fi (files/wifi.log): cada mudança de estado com a hora, o roteador e o sinal, para achar o padrão das quedas. */
    private fun registrar(msg: String) {
        try {
            if (arquivoLog.length() > 200_000) arquivoLog.delete()
            val i = wm.connectionInfo
            arquivoLog.appendText("${formatoLog.format(java.util.Date())} $msg | bssid=${i?.bssid} rssi=${i?.rssi}\n")
        } catch (e: Exception) { }
    }

    val ligado: Boolean get() = wm.isWifiEnabled || wm.wifiState == WifiManager.WIFI_STATE_ENABLING

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> lerResultados()
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    if (!ligado) { redes = emptyList(); conectadoSsid = null; conectando = null }
                    else varrer()
                }
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val ni = i.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                    if (ni != null) registrar("rede ${ni.detailedState}")
                    if (ni != null && ni.isConnected) {
                        handler.removeCallbacks(confirmaDesconexao)
                        conectadoSsid = ssidAtual()
                        if (conectando != null && conectadoSsid == conectando) conectando = null
                        erro = null
                    } else if (ni != null && ni.state == NetworkInfo.State.DISCONNECTED) {
                        // trocar de roteador (mesmo nome) passa por "desconectado" por uma fração de segundo: só vale se durar
                        handler.removeCallbacks(confirmaDesconexao)
                        handler.postDelayed(confirmaDesconexao, 8000)
                    }
                }
                WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                    registrar("supplicant ${i.getParcelableExtra<android.net.wifi.SupplicantState>(WifiManager.EXTRA_NEW_STATE)}")
                    val err = i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1)
                    if (err == WifiManager.ERROR_AUTHENTICATING && conectando != null) {
                        val ssid = conectando
                        if (idTentativa >= 0) { wm.removeNetwork(idTentativa); wm.saveConfiguration() }
                        falhar(ssid, "Senha incorreta")
                    }
                }
            }
            aoMudar()
        }
    }

    private val confirmaDesconexao = Runnable {
        if (ssidAtual() == null) { conectadoSsid = null; aoMudar() }
    }

    private val ciclo = object : Runnable {
        override fun run() {
            if (procurar && ligado && conectando == null) varrer()
            handler.postDelayed(this, Cfg.SCAN_WIFI_MS)
        }
    }

    fun iniciar() {
        val f = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        }
        ctx.registerReceiver(receptor, f)
        conectadoSsid = ssidAtual()
        lerResultados()
        handler.post(ciclo)
        aoMudar()
    }

    fun parar() {
        try { ctx.unregisterReceiver(receptor) } catch (e: IllegalArgumentException) { }
        handler.removeCallbacksAndMessages(null)
    }

    fun definirLigado(v: Boolean) {
        wm.isWifiEnabled = v
        if (!v) { conectadoSsid = null; conectando = null; redes = emptyList() }
        aoMudar()
    }

    fun varrer() {
        if (ligado) wm.startScan()
    }

    private fun ssidAtual(): String? {
        val info = wm.connectionInfo ?: return null
        if (info.supplicantState != SupplicantState.COMPLETED) return null
        val s = info.ssid?.trim('"') ?: return null
        return if (s.isEmpty() || s == "<unknown ssid>") null else s
    }

    private fun lerResultados() {
        val bruto = try { wm.scanResults } catch (e: SecurityException) { Log.w("CaixaWifi", "sem permissao", e); null }
        Log.d("CaixaWifi", "resultados: " + (bruto?.size ?: -1))
        val lista = bruto ?: return
        val melhor = HashMap<String, android.net.wifi.ScanResult>()
        for (r in lista) {
            val s = r.SSID
            if (s.isNullOrEmpty()) continue
            val a = melhor[s]
            if (a == null || r.level > a.level) melhor[s] = r
        }
        redes = melhor.values.map { r ->
            val cap = r.capabilities ?: ""
            val tipo = when {
                cap.contains("EAP") -> "Corporativa"
                cap.contains("WPA2") || cap.contains("RSN") -> "WPA2"
                cap.contains("WPA") -> "WPA"
                cap.contains("WEP") -> "WEP"
                else -> "Aberta"
            }
            Rede(r.SSID, WifiManager.calculateSignalLevel(r.level, 5).coerceIn(1, 4), tipo,
                tipo != "Aberta", tipo != "Corporativa")
        }.sortedByDescending { it.nivel }
    }

    private fun idSalvo(ssid: String): Int =
        wm.configuredNetworks?.firstOrNull { it.SSID == "\"$ssid\"" }?.networkId ?: -1

    fun salva(ssid: String): Boolean = idSalvo(ssid) >= 0

    fun conectar(rede: Rede, senha: String?) {
        erro = null; erroSsid = null
        conectando = rede.ssid
        var id = idSalvo(rede.ssid)
        if (senha != null || id < 0) {
            val cfg = WifiConfiguration()
            cfg.SSID = "\"${rede.ssid}\""
            when (rede.seguranca) {
                "Aberta" -> cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                "WEP" -> {
                    cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    cfg.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                    cfg.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
                    cfg.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                    cfg.wepKeys[0] = "\"${senha ?: ""}\""
                    cfg.wepTxKeyIndex = 0
                }
                else -> {
                    cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    val s = senha ?: ""
                    cfg.preSharedKey = if (s.length == 64) s else "\"$s\""
                }
            }
            if (id >= 0) { cfg.networkId = id; wm.updateNetwork(cfg) } else id = wm.addNetwork(cfg)
        }
        if (id < 0) { falhar(rede.ssid, "Não foi possível salvar a rede"); return }
        idTentativa = id
        wm.saveConfiguration()
        wm.disconnect()
        wm.enableNetwork(id, true)
        wm.reconnect()
        handler.postDelayed({
            if (conectando == rede.ssid) falhar(rede.ssid, "Tempo esgotado. Confira a senha e o sinal")
            aoMudar()
        }, Cfg.TEMPO_CONEXAO_MS)
        aoMudar()
    }

    private fun falhar(ssid: String?, mensagem: String) {
        conectando = null
        erro = mensagem
        erroSsid = ssid
    }

    fun esquecer(ssid: String) {
        val id = idSalvo(ssid)
        if (id >= 0) { wm.removeNetwork(id); wm.saveConfiguration() }
        if (conectadoSsid == ssid) { wm.disconnect(); conectadoSsid = null }
        erro = null
        aoMudar()
    }

    fun limparErro() { erro = null; erroSsid = null }

    fun ip(): String {
        val n = wm.connectionInfo?.ipAddress ?: 0
        return String.format("%d.%d.%d.%d", n and 0xff, n shr 8 and 0xff, n shr 16 and 0xff, n shr 24 and 0xff)
    }

    fun nivelAtual(): Int {
        val r = wm.connectionInfo?.rssi ?: return 0
        return WifiManager.calculateSignalLevel(r, 5).coerceIn(1, 4)
    }
}
