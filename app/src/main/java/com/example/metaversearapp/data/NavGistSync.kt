package com.example.metaversearapp.data

import com.example.metaversearapp.BuildConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val GIST_API_BASE     = "https://api.github.com/gists"
private const val NAVGRAPH_FILENAME = "navgraph.json"

// ── GitHub Gist API data models ───────────────────────────────────────────────

@Serializable
private data class GistResponse(
    val id: String? = null,
    val files: Map<String, GistFile> = emptyMap()
)

@Serializable
private data class GistFile(
    val filename: String? = null,
    val content: String? = null,
    @SerialName("raw_url") val rawUrl: String? = null,
    val truncated: Boolean = false
)

@Serializable
private data class GistPatchRequest(
    val files: Map<String, GistFileContent>
)

@Serializable
private data class GistFileContent(
    val content: String
)

// ── Sync object ───────────────────────────────────────────────────────────────

/**
 * Handles uploading and downloading the nav graph to/from a GitHub Gist.
 *
 * Secrets are injected at build time from `local.properties` via BuildConfig:
 *   - `BuildConfig.GITHUB_TOKEN`      — Personal Access Token with Gists read+write
 *   - `BuildConfig.NAV_GRAPH_GIST_ID` — ID of the target Gist (from the URL)
 *
 * The file stored in the Gist is always named `navgraph.json`.
 */
object NavGistSync {

    // encodeDefaults = true ensures fields like floor = "1" are always written to JSON,
    // even though "1" is the Kotlin default value. Without this, default-value fields
    // are silently omitted, making it impossible to distinguish "floor 1" from "no floor".
    private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val parseJson  = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun buildClient() = HttpClient(Android) {
        install(ContentNegotiation) {
            json(parseJson, contentType = ContentType.Any)
        }
    }

    /**
     * Accepts either a bare 32-character Gist ID or any URL format that contains one
     * (e.g. https://gist.github.com/user/ID or the raw download URL).
     */
    private fun resolveGistId(configValue: String): String {
        // A Gist ID is exactly 32 lowercase hex characters
        val hexId = Regex("[0-9a-f]{32}").find(configValue)?.value
        return hexId ?: configValue.trim()
    }

    /**
     * Serialises [nodes] + [edges] + [floorAltitudes] and PATCHes them to the configured Gist.
     * Requires [BuildConfig.GITHUB_TOKEN] and [BuildConfig.NAV_GRAPH_GIST_ID].
     *
     * @return [Result.success] on HTTP 2xx, [Result.failure] otherwise.
     */
    suspend fun upload(
        nodes: List<NavNode>,
        edges: List<NavEdge>,
        floorAltitudes: Map<String, Double> = emptyMap()
    ): Result<Unit> {
        val gistId = resolveGistId(BuildConfig.NAV_GRAPH_GIST_ID)
        val token  = BuildConfig.GITHUB_TOKEN
        if (gistId.isBlank() || token.isBlank()) {
            return Result.failure(
                IllegalStateException(
                    "NAV_GRAPH_GIST_ID or GITHUB_TOKEN is not set in local.properties"
                )
            )
        }

        val payload = prettyJson.encodeToString(NavGraphExport(nodes, edges, floorAltitudes))
        val body    = GistPatchRequest(
            files = mapOf(NAVGRAPH_FILENAME to GistFileContent(content = payload))
        )

        return try {
            val client = buildClient()
            val response = client.patch("$GIST_API_BASE/$gistId") {
                header(HttpHeaders.Authorization,  "Bearer $token")
                header(HttpHeaders.Accept,         "application/vnd.github+json")
                header("X-GitHub-Api-Version",     "2022-11-28")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            client.close()
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(RuntimeException("Upload failed: HTTP ${response.status.value}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads the nav graph from the configured Gist.
     * No auth is required for public Gists; the token is used if available.
     * Automatically follows GitHub's `raw_url` if the file content is truncated.
     *
     * @return [Result.success] with a [NavGraphExport], or [Result.failure] with the error.
     */
    suspend fun download(): Result<NavGraphExport> {
        val gistId = resolveGistId(BuildConfig.NAV_GRAPH_GIST_ID)
        if (gistId.isBlank()) {
            return Result.failure(
                IllegalStateException("NAV_GRAPH_GIST_ID is not set in local.properties")
            )
        }

        return try {
            val client = buildClient()
            val token  = BuildConfig.GITHUB_TOKEN

            // Fetch the Gist metadata (includes file content for small files)
            val gist: GistResponse = client.get("$GIST_API_BASE/$gistId") {
                header(HttpHeaders.Accept,      "application/vnd.github+json")
                header("X-GitHub-Api-Version",  "2022-11-28")
                if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
            }.body()

            val file = gist.files[NAVGRAPH_FILENAME]
                ?: return Result.failure(
                    IllegalStateException("$NAVGRAPH_FILENAME not found in Gist $gistId")
                )

            // GitHub truncates large files in the metadata response — fetch raw in that case
            val content = if (file.truncated) {
                val rawUrl = file.rawUrl
                    ?: return Result.failure(
                        IllegalStateException("File is truncated but raw_url is missing")
                    )
                client.get(rawUrl) {
                    if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
                }.body<String>()
            } else {
                file.content
                    ?: return Result.failure(IllegalStateException("File content is null"))
            }

            client.close()
            Result.success(parseJson.decodeFromString<NavGraphExport>(content))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
