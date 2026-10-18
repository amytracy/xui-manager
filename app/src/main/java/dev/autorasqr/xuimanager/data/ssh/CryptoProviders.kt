package dev.autorasqr.xuimanager.data.ssh

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Android ships a stripped-down provider registered as "BC" that lacks algorithms sshj needs
 * (Ed25519, X25519, OpenSSH key formats). Replace it with the full BouncyCastle provider.
 * It is appended rather than inserted first so platform crypto (Conscrypt, AndroidKeyStore) keeps priority;
 * sshj requests "BC" by name.
 */
object CryptoProviders {
    @Volatile private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
            installed = true
        }
    }
}
