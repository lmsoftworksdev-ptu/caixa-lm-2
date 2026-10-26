package br.com.lmsoftworks.caixa2

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Uma versão nova publicada no GitHub. */
class VersaoNova(val codigo: Int, val nome: String, val urlApk: String, val tamanho: Long)

/**
 * Atualização pelo próprio app, sem cabo nem computador. O GitHub compila o app sempre que o NewPipe (o motor que fala com o
 * YouTube) lança versão nova e publica um "release" com o APK. Aqui o app confere o release mais recente: se o número da versão
 * for maior que o instalado, mostra "Atualização necessária"; ao tocar em "Atualizar" baixa o APK e instala sozinho.
 *
 * Confere uma vez por dia ao abrir e também quando a busca de vídeos falha (o motivo mais comum é o YouTube ter mudado algo).
 */
object Atualizador {
    /** "usuario/repositorio" do GitHub onde ficam os releases (precisa ser público). */
    const val REPOSITORIO = "lmsoftworksdev-ptu/caixa-lm-2"
    private const val TAG = "Atualizador"
    private const val UM_DIA = 24L * 60 * 60 * 1000
    private const val ESPERA_FALHA = 10L * 60 * 1000
    const val ACAO_INSTALACAO = "br.com.lmsoftworks.caixa2.INSTALACAO"

    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var conferindo = false
    var pendente: VersaoNova? = null; private set
    /** A activity liga aqui o aviso "Atualização necessária". */
    var aoHaAtualizacao: ((VersaoNova) -> Unit)? = null

    private fun prefs(c: Context) = c.getSharedPreferences("atualizador", Context.MODE_PRIVATE)

    fun versaoInstalada(c: Context): Int =
        try { c.packageManager.getPackageInfo(c.packageName, 0).versionCode } catch (e: Exception) { 0 }

    /** Ao abrir o app: confere no máximo uma vez por dia. */
    fun conferirAoAbrir(c: Context) {
        val ult = prefs(c).getLong("ultima", 0L)
        if (System.currentTimeMillis() - ult < UM_DIA) return
        conferir(c)
    }

    /** A busca de vídeos falhou: pode ser o YouTube que mudou. Confere se já existe motor novo (no máximo a cada 10 minutos). */
    fun aoFalhar(c: Context, e: Exception) {
        Log.w(TAG, "busca falhou: ${e.javaClass.simpleName}: ${e.message}")
        val ult = prefs(c).getLong("ultimaFalha", 0L)
        if (System.currentTimeMillis() - ult < ESPERA_FALHA) return
        prefs(c).edit().putLong("ultimaFalha", System.currentTimeMillis()).apply()
        conferir(c)
    }

    private fun conferir(c: Context) {
        if (REPOSITORIO.startsWith("SEU-USUARIO") || conferindo) return
        conferindo = true
        val app = c.applicationContext
        Thread {
            try {
                val con = URL("https://api.github.com/repos/$REPOSITORIO/releases/latest").openConnection() as HttpURLConnection
                con.connectTimeout = 8000; con.readTimeout = 10000
                con.setRequestProperty("Accept", "application/vnd.github+json")
                val json = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
                prefs(app).edit().putLong("ultima", System.currentTimeMillis()).apply()
                val codigo = json.getString("tag_name").filter { it.isDigit() }.toIntOrNull() ?: 0
                val assets = json.getJSONArray("assets")
                var url: String? = null; var tam = 0L
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) { url = a.getString("browser_download_url"); tam = a.optLong("size"); break }
                }
                if (url != null && codigo > versaoInstalada(app)) {
                    val v = VersaoNova(codigo, json.optString("name", "versão $codigo"), url, tam)
                    principal.post { pendente = v; aoHaAtualizacao?.invoke(v) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "nao foi possivel conferir atualizacao", e)
            } finally { conferindo = false }
        }.start()
    }

    /** Baixa o APK (mostrando o andamento) e entrega ao instalador do Android. */
    fun baixarEInstalar(c: Context, v: VersaoNova, aoProgresso: (Int) -> Unit, aoErro: (String) -> Unit) {
        val app = c.applicationContext
        Thread {
            try {
                val pasta = File(app.cacheDir, "atualizacao").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val arquivo = File(pasta, "CaixaLM2.apk")
                val con = URL(v.urlApk).openConnection() as HttpURLConnection
                con.connectTimeout = 10000; con.readTimeout = 20000
                val total = con.contentLengthLong.takeIf { it > 0 } ?: v.tamanho
                var lido = 0L
                con.inputStream.use { entrada ->
                    arquivo.outputStream().use { saida ->
                        val buf = ByteArray(64 * 1024)
                        var ultimo = -1
                        while (true) {
                            val n = entrada.read(buf)
                            if (n < 0) break
                            saida.write(buf, 0, n); lido += n
                            val pct = if (total > 0) (lido * 100 / total).toInt() else 0
                            if (pct != ultimo) { ultimo = pct; principal.post { aoProgresso(pct) } }
                        }
                    }
                }
                principal.post { aoProgresso(100) }
                instalar(app, arquivo)
            } catch (e: Exception) {
                Log.w(TAG, "falha ao baixar", e)
                principal.post { aoErro("Não consegui baixar. Confira a internet e tente de novo.") }
            }
        }.start()
    }

    private fun instalar(c: Context, apk: File) {
        val instalador = c.packageManager.packageInstaller
        val sessao = instalador.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        instalador.openSession(sessao).use { s ->
            apk.inputStream().use { entrada -> s.openWrite("CaixaLM2", 0, apk.length()).use { saida -> entrada.copyTo(saida); s.fsync(saida) } }
            val intent = PendingIntent.getBroadcast(c, sessao, Intent(ACAO_INSTALACAO).setPackage(c.packageName), PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(intent.intentSender)
        }
    }
}

/** Resposta do instalador: se o Android pedir confirmação, abre a janela dele; se falhar, registra. */
class InstalacaoReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmar = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmar?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmar != null) c.startActivity(confirmar)
            }
            PackageInstaller.STATUS_SUCCESS -> Log.d("Atualizador", "instalado")
            else -> Log.w("Atualizador", "instalacao falhou: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}

/** Depois de instalada a versão nova, o Android encerra o app: este receptor abre de novo (é a tela inicial da caixa). */
class AtualizadoReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        c.startActivity(Intent(c, InicioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Janela "Atualização necessária": cobre a tela, com o botão Atualizar e o andamento do download. */
class ModalAtualizacao(private val c: Context) : FrameLayout(c) {
    private val titulo = texto(c, "Atualização necessária", 38, Color.WHITE, true)
    private val detalhe = texto(c, "", 22, Color.parseColor("#D6E0F7"))
    private val andamento = FrameLayout(c)
    private val preenchido = View(c)
    private val botaoAtualizar: TextView
    private val botaoDepois: TextView
    private var versao: VersaoNova? = null
    private var baixando = false

    val aberto: Boolean get() = visibility == View.VISIBLE

    init {
        visibility = View.GONE
        setBackgroundColor(Color.parseColor("#E602060F"))
        isClickable = true
        val cartao = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = fundoArredondado(Color.parseColor("#F00D2048"), 28.d(), Cfg.LINHA)
            setPadding(48.d(), 40.d(), 48.d(), 36.d())
        }
        cartao.addView(icone(c, R.drawable.ic_reiniciar, 72, Color.WHITE), LinearLayout.LayoutParams(72.d(), 72.d()))
        cartao.addView(titulo, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 22.d() })
        detalhe.gravity = Gravity.CENTER
        cartao.addView(detalhe, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.d() })
        andamento.apply { background = fundoArredondado(Color.parseColor("#26FFFFFF"), 8.d()); visibility = View.GONE }
        preenchido.background = fundoArredondado(Color.WHITE, 8.d())
        andamento.addView(preenchido, LayoutParams(0, -1))
        cartao.addView(andamento, LinearLayout.LayoutParams(-1, 14.d()).apply { topMargin = 26.d() })
        botaoAtualizar = botao(c, "Atualizar", Cfg.COR_FIXA) { iniciar() }
        botaoDepois = botao(c, "Agora não", Cfg.LINHA) { fechar() }
        val botoes = LinearLayout(c).apply { gravity = Gravity.CENTER }
        botoes.addView(botaoDepois, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 16.d() })
        botoes.addView(botaoAtualizar)
        cartao.addView(botoes, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 30.d() })
        addView(cartao, LayoutParams(720.d(), -2, Gravity.CENTER))
    }

    fun mostrar(v: VersaoNova) {
        versao = v
        baixando = false
        detalhe.text = "Saiu uma versão nova do mecanismo de vídeos e músicas.\nAtualize para a busca continuar funcionando."
        andamento.visibility = View.GONE
        botaoAtualizar.text = "Atualizar"; botaoAtualizar.alpha = 1f
        botaoDepois.visibility = View.VISIBLE
        visibility = View.VISIBLE
    }

    fun fechar() { if (!baixando) visibility = View.GONE }

    private fun iniciar() {
        val v = versao ?: return
        if (baixando) return
        baixando = true
        botaoAtualizar.text = "Baixando…"; botaoAtualizar.alpha = .6f
        botaoDepois.visibility = View.GONE
        andamento.visibility = View.VISIBLE
        detalhe.text = "Baixando a atualização. Não desligue a caixa."
        Atualizador.baixarEInstalar(c, v, { pct ->
            preenchido.layoutParams = preenchido.layoutParams.apply { width = andamento.width * pct / 100 }
            if (pct >= 100) detalhe.text = "Instalando… o app vai reiniciar sozinho."
        }, { erro ->
            baixando = false
            detalhe.text = erro
            botaoAtualizar.text = "Tentar de novo"; botaoAtualizar.alpha = 1f
            botaoDepois.visibility = View.VISIBLE
            andamento.visibility = View.GONE
        })
    }
}
