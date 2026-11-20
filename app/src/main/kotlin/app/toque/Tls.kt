package app.toque

import android.content.Context
import java.net.HttpURLConnection
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Certificats racine récents ajoutés à ceux d'Android 6 (tablette du robot), qui ne connaît pas
 * ISRG Root X1 (Let's Encrypt, téléchargements GitHub) ni, selon les versions, USERTrust ECC
 * (api.github.com). Un certificat est accepté si Android OU ces racines le reconnaissent : la
 * vérification reste complète (chaîne, dates, nom du serveur), seule la liste des racines grandit.
 * Racines dans assets/certs (empreintes SHA-256 dans le README de ce dossier).
 */
object Tls {
    private var context: Context? = null
    private var log: Logbook? = null
    private var factory: SSLSocketFactory? = null
    private var built = false

    /** À appeler une fois au démarrage ; les racines ne sont lues qu'à la première connexion HTTPS. */
    fun init(context: Context, log: Logbook) {
        this.context = context.applicationContext
        this.log = log
    }

    /** Sans effet (racines d'Android seules) si les certificats ne peuvent pas être lus. */
    @Synchronized
    private fun factory(): SSLSocketFactory? {
        if (built) return factory
        built = true
        val context = context ?: return null
        try {
            val cf = CertificateFactory.getInstance("X.509")
            val extra = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            val names = context.assets.list("certs").orEmpty().filter { it.endsWith(".pem") }
            for (name in names) {
                context.assets.open("certs/$name").use { input ->
                    extra.setCertificateEntry(name, cf.generateCertificate(input))
                }
            }
            val system = trustManager(null)
            val bundled = trustManager(extra)
            val combined = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    system.checkClientTrusted(chain, authType)

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try {
                        system.checkServerTrusted(chain, authType)
                    } catch (e: CertificateException) {
                        bundled.checkServerTrusted(chain, authType)
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + bundled.acceptedIssuers
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(combined), null)
            factory = ctx.socketFactory
        } catch (e: Exception) {
            log?.write("tls", "racines ajoutées indisponibles : $e")
        }
        return factory
    }

    private fun trustManager(store: KeyStore?): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(store)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Applique ces racines à une connexion HTTPS (sans effet en HTTP ou si [init] a échoué). */
    fun apply(c: HttpURLConnection): HttpURLConnection {
        if (c is HttpsURLConnection) factory()?.let { c.sslSocketFactory = it }
        return c
    }
}
