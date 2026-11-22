package br.com.lmsoftworks.caixa2

import android.app.AlarmManager
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * O relógio do aparelho depois de uma queda de energia. A Caixa não tem pilha no relógio (o RTC marca 1970): ao ligar sem aviso, a hora
 * volta para a data de fabricação do sistema (fevereiro de 2019) e só é corrigida quando o Android consegue a hora da internet, o que ele
 * demorava ou não fazia. Com a hora errada TODA conexão segura (HTTPS) falha, porque os certificados "ainda não valem": o Wi-Fi aparecia
 * conectado, mas nada abria até desligar e ligar o Wi-Fi.
 *
 * Aqui o app não espera o Android: assim que abre, se a hora está antes de quando o app foi instalado (impossível), pede a hora a servidores
 * NTP (UDP, que não depende de certificado), acerta o relógio e confere se a internet funciona.
 */
object RelogioRede {
    private const val TAG = "CaixaRelogio"
    private val SERVIDORES = listOf("a.st1.ntp.br", "b.st1.ntp.br", "pool.ntp.org", "time.google.com", "time.cloudflare.com", "br.pool.ntp.org")
    private const val DIFERENCA_NTP_UNIX_S = 2_208_988_800L      // de 1900 (NTP) a 1970 (Unix)
    @Volatile private var rodando = false

    /** A hora do aparelho está errada: é anterior a quando o app foi instalado ou atualizado, o que não pode acontecer com a hora certa. */
    fun relogioInvalido(ctx: Context): Boolean {
        val agora = System.currentTimeMillis()
        val instalado = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime } catch (e: Exception) { 0L }
        return agora < instalado - 60_000L || agora < 1_735_689_600_000L      // antes de 2025 também é inválido
    }

    /** Pergunta a hora a um servidor NTP; null se não respondeu. */
    private fun horaDe(servidor: String): Long? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = 3_000
            val endereco = InetAddress.getByName(servidor)
            val pedido = ByteArray(48).also { it[0] = 0x1B }      // versão 3, modo cliente
            val antes = System.nanoTime()
            socket.send(DatagramPacket(pedido, pedido.size, endereco, 123))
            val resposta = ByteArray(48)
            socket.receive(DatagramPacket(resposta, resposta.size))
            val ida = (System.nanoTime() - antes) / 1_000_000L
            fun inteiro(i: Int) = ((resposta[i].toLong() and 0xff) shl 24) or ((resposta[i + 1].toLong() and 0xff) shl 16) or
                ((resposta[i + 2].toLong() and 0xff) shl 8) or (resposta[i + 3].toLong() and 0xff)
            val segundos = inteiro(40); val fracao = inteiro(44)
            if (segundos == 0L || (resposta[1].toInt() and 0xff) == 0) null      // sem estrato: resposta inválida
            else (segundos - DIFERENCA_NTP_UNIX_S) * 1000L + fracao * 1000L / 0x100000000L + ida / 2
        }
    } catch (e: Exception) { null }

    /** Acerta o relógio do aparelho pela internet. true se conseguiu. */
    private fun acertar(ctx: Context): Boolean {
        for (s in SERVIDORES) {
            val hora = horaDe(s) ?: continue
            if (hora < 1_735_689_600_000L) continue      // resposta absurda
            try {
                (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setTime(hora)
                Log.d(TAG, "relógio acertado por $s: ${java.util.Date(hora)}")
                return true
            } catch (e: Exception) { Log.w(TAG, "não consegui acertar o relógio", e); return false }
        }
        return false
    }

    /** A internet de verdade funciona? (uma conexão segura de teste: falha com a hora errada) */
    private fun internetFunciona(): Boolean = try {
        val con = URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection
        con.connectTimeout = 5_000; con.readTimeout = 5_000; con.instanceFollowRedirects = false
        val ok = con.responseCode == 204
        con.disconnect(); ok
    } catch (e: Exception) { false }

    /**
     * Chamada quando o app abre: se a hora está errada, tenta acertá-la a cada 4 s (o Wi-Fi pode ainda estar conectando), por até 3 minutos; depois
     * confere a internet e, se ainda não funcionar, liga e desliga o Wi-Fi uma vez (o mesmo que a pessoa fazia na mão).
     */
    fun garantir(ctx: Context) {
        val app = ctx.applicationContext
        if (rodando || !relogioInvalido(app)) return
        rodando = true
        Thread({
            try {
                Log.w(TAG, "relógio inválido (${java.util.Date()}): pedindo a hora à internet")
                var acertou = false
                for (tentativa in 0 until 45) {
                    if (!relogioInvalido(app)) { acertou = true; break }      // o Android já acertou sozinho
                    if (acertar(app)) { acertou = true; break }
                    try { Thread.sleep(4_000L) } catch (e: InterruptedException) { return@Thread }
                }
                if (!acertou) { Log.w(TAG, "não consegui a hora em 3 minutos"); return@Thread }
                // com a hora certa a internet deve voltar na hora; se não, o Wi-Fi é reiniciado uma vez
                for (tentativa in 0 until 4) {
                    if (internetFunciona()) { Log.d(TAG, "internet ok depois de acertar o relógio"); return@Thread }
                    try { Thread.sleep(4_000L) } catch (e: InterruptedException) { return@Thread }
                }
                Log.w(TAG, "sem internet mesmo com a hora certa: reiniciando o Wi-Fi")
                val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (wm.isWifiEnabled) {
                    wm.isWifiEnabled = false
                    try { Thread.sleep(2_500L) } catch (e: InterruptedException) { return@Thread }
                    wm.isWifiEnabled = true
                }
            } finally { rodando = false }
        }, "relogio-rede").start()
    }
}
