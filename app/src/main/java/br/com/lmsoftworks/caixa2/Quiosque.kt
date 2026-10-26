package br.com.lmsoftworks.caixa2

import android.content.Context
import android.util.Log

/**
 * Modo quiosque: pede ao Android para desligar a barra de status, as configurações rápidas,
 * as notificações e os botões Voltar/Início/Recentes. Funciona porque o app é do sistema
 * (permissão STATUS_BAR). Precisa ser pedido de novo sempre que o app volta para a frente.
 */
object Quiosque {
    private const val EXPANDIR = 0x00010000
    private const val ICONES_NOTIFICACAO = 0x00020000
    private const val ALERTAS = 0x00040000
    private const val TICKER = 0x00080000
    private const val INFO_SISTEMA = 0x00100000
    private const val INICIO = 0x00200000
    private const val VOLTAR = 0x00400000
    private const val RELOGIO = 0x00800000
    private const val RECENTES = 0x01000000
    private const val BUSCA = 0x02000000
    private const val TUDO = EXPANDIR or ICONES_NOTIFICACAO or ALERTAS or TICKER or INFO_SISTEMA or
        INICIO or VOLTAR or RELOGIO or RECENTES or BUSCA
    private const val TUDO2 = 1 or 2 or 4   // configurações rápidas, ícones do sistema, cortina

    fun aplicar(c: Context) {
        try {
            val gerente = c.getSystemService("statusbar") ?: return
            val cls = Class.forName("android.app.StatusBarManager")
            cls.getMethod("disable", Int::class.javaPrimitiveType).invoke(gerente, TUDO)
            try {
                cls.getMethod("disable2", Int::class.javaPrimitiveType).invoke(gerente, TUDO2)
            } catch (e: Exception) { Log.w("Quiosque", "disable2 indisponivel", e) }
        } catch (e: Exception) {
            Log.w("Quiosque", "nao foi possivel travar as barras", e)
        }
    }
}
