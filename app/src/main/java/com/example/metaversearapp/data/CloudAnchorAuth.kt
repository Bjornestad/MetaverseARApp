package com.example.metaversearapp.data

import android.util.Base64
import com.example.metaversearapp.BuildConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in")   val expiresIn: Int = 3600,
)

/**
 * Manages OAuth 2.0 access-token generation from a Google service account.
 *
 * Used for ARCore Cloud Anchor keyless authentication, which allows anchors
 * to be hosted with a TTL up to 365 days (vs. 1 day with a plain API key).
 *
 * **Setup** — add the following to `local.properties` (never commit this file):
 * ```
 * ARCORE_CLIENT_EMAIL=my-sa@my-project.iam.gserviceaccount.com
 * ARCORE_PRIVATE_KEY=-----BEGIN PRIVATE KEY-----\nMIIEv...\n-----END PRIVATE KEY-----\n
 * ```
 * The private key must be on a single line with literal `\n` as the line separator.
 *
 * Tokens are cached in memory and refreshed 5 minutes before expiry.  The
 * app never stores the token persistently — a fresh token is fetched on each
 * cold start when the first Cloud Anchor operation is triggered.
 *
 * If credentials are absent ([isConfigured] == false) [getToken] returns null
 * and ARCore falls back to the API key baked into the manifest (1-day TTL).
 */
object CloudAnchorAuth {

    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val SCOPE     = "https://www.googleapis.com/auth/arcore"
    private const val GRANT     = "urn:ietf:params:oauth:grant-type:jwt-bearer"

    private var cachedToken: String? = null
    private var tokenExpiry: Long    = 0L

    /**
     * True when both `ARCORE_CLIENT_EMAIL` and `ARCORE_PRIVATE_KEY` are
     * present in BuildConfig.  When false the app runs in API_KEY mode.
     */
    val isConfigured: Boolean
        get() = BuildConfig.ARCORE_CLIENT_EMAIL.isNotBlank() &&
                BuildConfig.ARCORE_PRIVATE_KEY.isNotBlank()

    /**
     * Returns a valid Bearer token for the ARCore API, using the cached value
     * when still fresh.  Returns `null` if credentials are not configured or
     * the token exchange fails (network error, bad key, etc.).
     */
    suspend fun getToken(): String? {
        if (!isConfigured) return null
        val nowSec = System.currentTimeMillis() / 1000L
        val cached = cachedToken
        if (cached != null && nowSec < tokenExpiry - 300L) return cached
        return try {
            val resp  = exchangeToken(buildJwt(nowSec))
            cachedToken = resp.accessToken
            tokenExpiry = nowSec + resp.expiresIn
            resp.accessToken
        } catch (_: Exception) {
            null
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private suspend fun exchangeToken(jwt: String): TokenResponse {
        val client = HttpClient(Android) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true }, contentType = ContentType.Any)
            }
        }
        return try {
            client.submitForm(
                url = TOKEN_URL,
                formParameters = Parameters.build {
                    append("grant_type", GRANT)
                    append("assertion",  jwt)
                }
            ).body()
        } finally {
            client.close()
        }
    }

    /**
     * Builds an RS256-signed JWT asserting the service account's identity and
     * requesting the ARCore scope.
     */
    private fun buildJwt(nowSec: Long): String {
        val header  = b64Json("""{"alg":"RS256","typ":"JWT"}""")
        val payload = b64Json(
            """{"iss":"${BuildConfig.ARCORE_CLIENT_EMAIL}","scope":"$SCOPE",""" +
            """"aud":"$TOKEN_URL","iat":$nowSec,"exp":${nowSec + 3600}}"""
        )
        val unsigned = "$header.$payload"
        return "$unsigned.${sign(unsigned)}"
    }

    /**
     * Signs [data] with the service account's RSA private key using SHA-256.
     * The private key is PKCS#8 PEM from the service account JSON, stored in
     * BuildConfig with literal `\n` as line separators.
     */
    private fun sign(data: String): String {
        val pemBody = BuildConfig.ARCORE_PRIVATE_KEY
            .replace("\\n", "\n")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\n", "")
            .trim()
        val keyBytes = Base64.decode(pemBody, Base64.DEFAULT)
        val key      = KeyFactory.getInstance("RSA")
                           .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        return Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(data.toByteArray(Charsets.UTF_8))
            b64Bytes(sign())
        }
    }

    /** URL-safe, no-padding Base64 of a UTF-8 string (for JWT header/payload). */
    private fun b64Json(s: String) =
        Base64.encodeToString(
            s.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

    /** URL-safe, no-padding Base64 of raw bytes (for JWT signature). */
    private fun b64Bytes(bytes: ByteArray) =
        Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
}
