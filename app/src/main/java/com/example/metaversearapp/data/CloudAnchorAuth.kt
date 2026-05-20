package com.example.metaversearapp.data

// ─────────────────────────────────────────────────────────────────────────────
// CloudAnchorAuth — SAVED FOR LATER, NOT CURRENTLY WIRED UP
//
// This implements OAuth 2.0 service-account token exchange for the ARCore REST
// API (up to 365-day anchor TTL).  The ARCore *Android SDK* does not accept
// injected tokens — it only supports API_KEY (manifest) or KEYLESS (Play
// Integrity).  This code would be used if/when switching to direct REST calls
// for hosting/resolving, or once Keyless Play Integrity is confirmed working.
//
// To re-enable: remove the block comments below and add to local.properties:
//   ARCORE_CLIENT_EMAIL=my-sa@my-project.iam.gserviceaccount.com
//   ARCORE_PRIVATE_KEY=-----BEGIN PRIVATE KEY-----\nMIIEv...\n-----END PRIVATE KEY-----\n
// ─────────────────────────────────────────────────────────────────────────────

/*
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

object CloudAnchorAuth {

    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val SCOPE     = "https://www.googleapis.com/auth/arcore"
    private const val GRANT     = "urn:ietf:params:oauth:grant-type:jwt-bearer"

    private var cachedToken: String? = null
    private var tokenExpiry: Long    = 0L

    val isConfigured: Boolean
        get() = BuildConfig.ARCORE_CLIENT_EMAIL.isNotBlank() &&
                BuildConfig.ARCORE_PRIVATE_KEY.isNotBlank()

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

    private fun buildJwt(nowSec: Long): String {
        val header  = b64Json("""{"alg":"RS256","typ":"JWT"}""")
        val payload = b64Json(
            """{"iss":"${BuildConfig.ARCORE_CLIENT_EMAIL}","scope":"$SCOPE",""" +
            """"aud":"$TOKEN_URL","iat":$nowSec,"exp":${nowSec + 3600}}"""
        )
        val unsigned = "$header.$payload"
        return "$unsigned.${sign(unsigned)}"
    }

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

    private fun b64Json(s: String) =
        Base64.encodeToString(
            s.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

    private fun b64Bytes(bytes: ByteArray) =
        Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
}
*/
