package br.com.lmsoftworks.caixa2.voz

import android.content.Context
import br.com.lmsoftworks.caixa2.R
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Cliente HTTPS que funciona no Android 6.
 *
 * O Android 6 nao conhece o certificado raiz "ISRG Root X1" (Let's Encrypt), usado por muitos sites
 * (Wikipedia, Open-Meteo...). Sem ele toda conexao falha com "Trust anchor for certification path not
 * found". Aqui o certificado (res/raw/isrg_root_x1.pem, oficial) e somado aos que o aparelho ja confia.
 */
object Rede {

    @Volatile
    private var base: OkHttpClient? = null

    fun cliente(ctx: Context, leituraSeg: Long = 10): OkHttpClient {
        val b = base ?: synchronized(this) {
            base ?: criar(ctx.applicationContext).also { base = it }
        }
        return b.newBuilder().readTimeout(leituraSeg, TimeUnit.SECONDS).build()
    }

    private fun criar(ctx: Context): OkHttpClient {
        val padrao = gerenciador(null)
        val extra = try {
            val cert = ctx.resources.openRawResource(R.raw.isrg_root_x1).use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("isrg_root_x1", cert)
            }
            gerenciador(ks)
        } catch (e: Throwable) {
            null
        }

        val combinado = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                padrao.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                try {
                    padrao.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    if (extra == null) throw e
                    extra.checkServerTrusted(chain, authType)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                padrao.acceptedIssuers + (extra?.acceptedIssuers ?: emptyArray())
        }

        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(combinado), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, combinado)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun gerenciador(ks: KeyStore?): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(ks)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
