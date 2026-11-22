package br.com.lmsoftworks.caixa2

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Trabalho curto em segundo plano (rede, disco, consultas ao sistema). Em vez de abrir uma thread nova a cada pedido (cada uma custa memória
 * e tempo para nascer), as threads ficam vivas por 30 s e são reaproveitadas. Sem limite de threads: uma tarefa que espera a rede nunca
 * trava as outras. Quem precisa de um laço longo (monitor do LED) continua com a sua thread própria.
 */
object Tarefas {
    private val contador = AtomicInteger()
    private val fabrica = ThreadFactory { r -> Thread(r, "caixa-tarefa-${contador.incrementAndGet()}") }
    private val pool = Executors.newCachedThreadPool(fabrica).also { (it as java.util.concurrent.ThreadPoolExecutor).setKeepAliveTime(30, java.util.concurrent.TimeUnit.SECONDS) }

    /** Threads de tarefa vivas agora e quantas tarefas já rodaram (aparecem no Teste de conexões). */
    val vivas: Int get() = (pool as java.util.concurrent.ThreadPoolExecutor).poolSize
    val rodadas: Long get() = (pool as java.util.concurrent.ThreadPoolExecutor).completedTaskCount

    fun executar(bloco: () -> Unit) {
        pool.execute {
            try { bloco() } catch (e: Throwable) { Falhas.gravar("Tarefa em segundo plano", e) }
        }
    }
}
