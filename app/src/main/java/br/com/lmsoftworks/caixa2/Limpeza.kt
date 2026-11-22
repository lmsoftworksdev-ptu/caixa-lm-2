package br.com.lmsoftworks.caixa2

import android.content.Context
import java.io.File

/**
 * Limpeza geral do app (botão em Configurações > Sistema): miniaturas guardadas, arquivos temporários (cache, atualização baixada),
 * registros salvos e o registro do sistema. Não mexe em ajustes, alarmes, lembretes nem no modelo de voz.
 */
object Limpeza {
    private fun tamanho(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { tamanho(it) } ?: 0L

    /** Roda fora da thread da tela. Devolve quantos bytes foram liberados. */
    fun executar(ctx: Context): Long {
        var livre = 0L
        livre += Miniaturas.limparTudo()
        // cache do app: tudo, menos a pasta das miniaturas (ela segue em uso)
        for (pasta in listOfNotNull(ctx.cacheDir, ctx.externalCacheDir)) {
            pasta.listFiles()?.forEach { if (it.name != "miniaturas") { livre += tamanho(it); it.deleteRecursively() } }
        }
        // registros e diagnósticos salvos pelo app (o modelo de voz, que também fica nessa pasta, não é tocado)
        ctx.getExternalFilesDir(null)?.listFiles()?.forEach {
            if (it.isFile && (it.name.startsWith("registro") || it.name.endsWith(".log"))) { livre += it.length(); it.delete() }
        }
        ctx.filesDir.listFiles()?.forEach {
            if (it.isFile && (it.name.endsWith(".log") || it.name.endsWith(".tmp"))) { livre += it.length(); it.delete() }
        }
        try { Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor() } catch (e: Throwable) { }
        System.gc()
        return livre
    }

    fun texto(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> (bytes / 1024).toString() + " KB"
        else -> "nada além do que já estava limpo"
    }
}
