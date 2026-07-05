package com.example.aggregator

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.annotations.SerializedName
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Secure storage plumbing for the Aggregator (mCard).
 *
 * The WHOLE Room DB is encrypted with SQLCipher. The passphrase is derived from
 * the patient's PIN + patientId via PBKDF2 and held ONLY in memory (never written
 * to disk) — see AggregatorSession. This honours the "PIN-derived key" decision.
 *
 * Tradeoff (documented): because the key lives only in memory, a background
 * CloudSyncWorker can open the DB only while the app process is still alive after
 * an unlock. If the process was killed, sync resumes the next time the patient
 * unlocks the app. See CloudSyncWorker for the guard.
 *
 * Design ref: CREDENTIALS_AND_STORAGE_PLAN.md §5–6.
 */
object PinCrypto {
    private const val PREFS = "aggregator_secure_prefs"
    private const val KEY_SALT = "pbkdf2_salt"
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256

    fun getOrCreateSalt(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SALT, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP)).apply()
        return salt
    }

    fun deriveKey(pin: String, ownerId: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec("$pin:$ownerId".toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}

/** In-memory session: the DB passphrase and the unlocked credential. */
object AggregatorSession {
    @Volatile var passphrase: ByteArray? = null
    @Volatile var credential: CredentialEntity? = null

    val isUnlocked get() = passphrase != null

    /** Derive and hold the DB key. Does not open the DB yet. */
    fun provision(context: Context, pin: String, patientId: String) {
        val salt = PinCrypto.getOrCreateSalt(context)
        val newPassphrase = PinCrypto.deriveKey(pin, patientId, salt)
        // The DB is a process-wide singleton bound to the passphrase it was first
        // opened with. If the key changes (different PIN/patient, or a retry after a
        // failed attempt), drop the cached handle so getInstance() reopens with THIS
        // key — otherwise a correct PIN would be silently ignored until process death.
        val current = passphrase
        if (current == null || !current.contentEquals(newPassphrase)) {
            AggregatorDatabase.reset()
        }
        passphrase = newPassphrase
    }

    fun lock() {
        passphrase = null
        credential = null
        AggregatorDatabase.reset()
    }
}

/** The patient's credential as returned by the backend register/rotate response. */
data class Credentials(
    @SerializedName("privateKey")    val privateKey: String?,
    @SerializedName("publicKey")     val publicKey: String?,
    @SerializedName("certificate")   val certificate: String?,
    @SerializedName("caCertificate") val caCertificate: String?
)

/**
 * Save / load the patient credential in the encrypted DB. Requires the session to
 * be provisioned (AggregatorSession.provision) first.
 */
object CredentialStore {

    fun loadIntoSession(context: Context): Boolean {
        return try {
            val cred = AggregatorDatabase.getInstance(context).credentialDao().getCredential()
            AggregatorSession.credential = cred
            cred != null
        } catch (e: Exception) {
            Log.e("CredentialStore", "loadIntoSession failed (locked or wrong PIN)", e)
            false
        }
    }

    fun saveFromServer(context: Context, patientId: String, creds: Credentials): Boolean {
        val priv = creds.privateKey
        val pub = creds.publicKey
        val cert = creds.certificate
        val ca = creds.caCertificate
        if (priv.isNullOrBlank() || pub.isNullOrBlank() || cert.isNullOrBlank() || ca.isNullOrBlank()) {
            Log.w("CredentialStore", "Incomplete credentials from server; not saving.")
            return false
        }
        return try {
            val entity = CredentialEntity(
                ownerId = patientId,
                role = "patient",
                privateKeyB64 = priv,
                publicKeyB64 = pub,
                certPem = cert,
                caCertPem = ca
            )
            AggregatorDatabase.getInstance(context).credentialDao().upsert(entity)
            AggregatorSession.credential = entity
            true
        } catch (e: Exception) {
            Log.e("CredentialStore", "saveFromServer failed", e)
            false
        }
    }
}
