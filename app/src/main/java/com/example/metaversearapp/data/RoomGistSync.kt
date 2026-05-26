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

private const val GIST_API_BASE  = "https://api.github.com/gists"
private const val ROOMS_FILENAME = "rooms.json"

// ── GitHub Gist API data models ───────────────────────────────────────────────
// Prefixed with "Rooms" to avoid JVM-level name collision with the identically
// shaped private classes in NavGistSync.kt (same package → same JVM namespace).

@Serializable
private data class RoomsGistResponse(
    val id: String? = null,
    val files: Map<String, RoomsGistFile> = emptyMap()
)

@Serializable
private data class RoomsGistFile(
    val filename: String? = null,
    val content: String? = null,
    @SerialName("raw_url") val rawUrl: String? = null,
    val truncated: Boolean = false
)

@Serializable
private data class RoomsGistPatchRequest(val files: Map<String, RoomsGistFileContent>)

@Serializable
private data class RoomsGistFileContent(val content: String)

// ── Sync object ───────────────────────────────────────────────────────────────

/**
 * Uploads and downloads the room list as `rooms.json` inside the same Gist
 * used by [NavGistSync] (identified by [BuildConfig.NAV_GRAPH_GIST_ID]).
 * Patching only touches the file listed in the request, so navgraph.json
 * and rooms.json never overwrite each other.
 *
 * The file contains a JSON array of [Room] objects: `[{id, name, floor, lat, lon, alt}, …]`.
 *
 * Secrets are injected at build time from `local.properties`:
 *   - `BuildConfig.GITHUB_TOKEN`      — PAT with Gists read + write
 *   - `BuildConfig.NAV_GRAPH_GIST_ID` — ID (or URL) of the shared Gist
 */
object RoomGistSync {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private fun buildClient() = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true }, contentType = ContentType.Any)
        }
    }

    private fun resolveGistId(raw: String): String =
        Regex("[0-9a-f]{32}").find(raw)?.value ?: raw.trim()

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Downloads and parses the room list from the configured Gist.
     * Returns an empty list (not a failure) when the Gist file does not exist yet
     * or is empty — this lets the first [addRoom] call bootstrap the file.
     */
    suspend fun download(): Result<List<Room>> {
        val gistId = resolveGistId(BuildConfig.NAV_GRAPH_GIST_ID)
        if (gistId.isBlank()) {
            return Result.failure(IllegalStateException("NAV_GRAPH_GIST_ID is not set in local.properties"))
        }

        return try {
            val client = buildClient()
            val token  = BuildConfig.GITHUB_TOKEN

            val gist: RoomsGistResponse = client.get("$GIST_API_BASE/$gistId") {
                header(HttpHeaders.Accept,     "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
            }.body()

            val file = gist.files[ROOMS_FILENAME]
            if (file == null) {
                client.close()
                return Result.success(emptyList()) // file not yet created — first run
            }

            val content = if (file.truncated) {
                val rawUrl = file.rawUrl
                    ?: return Result.failure(IllegalStateException("File truncated but raw_url missing"))
                client.get(rawUrl) {
                    if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
                }.body<String>()
            } else {
                file.content ?: return Result.success(emptyList())
            }

            client.close()
            if (content.isBlank()) return Result.success(emptyList())
            Result.success(json.decodeFromString<List<Room>>(content))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Replaces the entire rooms list in the Gist with [rooms].
     */
    suspend fun upload(rooms: List<Room>): Result<Unit> {
        val gistId = resolveGistId(BuildConfig.NAV_GRAPH_GIST_ID)
        val token  = BuildConfig.GITHUB_TOKEN
        if (gistId.isBlank() || token.isBlank()) {
            return Result.failure(
                IllegalStateException("NAV_GRAPH_GIST_ID or GITHUB_TOKEN not set in local.properties")
            )
        }

        val payload = json.encodeToString(rooms)
        val body    = RoomsGistPatchRequest(mapOf(ROOMS_FILENAME to RoomsGistFileContent(payload)))

        return try {
            val client   = buildClient()
            val response = client.patch("$GIST_API_BASE/$gistId") {
                header(HttpHeaders.Authorization,  "Bearer $token")
                header(HttpHeaders.Accept,         "application/vnd.github+json")
                header("X-GitHub-Api-Version",     "2022-11-28")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            client.close()
            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(RuntimeException("Upload failed: HTTP ${response.status.value}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Upsert single room ────────────────────────────────────────────────────

    /**
     * Downloads the current list, replaces any existing entry with the same [room.id],
     * appends if new, then uploads the merged list.
     *
     * Call this after the admin places a door node in the recording screen.
     * Network errors are surfaced as [Result.failure] — the caller decides whether
     * to surface them to the UI or silently swallow them.
     */
    suspend fun addRoom(room: Room): Result<Unit> {
        val existing = download().getOrDefault(emptyList())
        val merged   = existing.filter { it.id != room.id } + room
        return upload(merged)
    }

    /**
     * Downloads the current list and removes the entry with [roomId], then uploads.
     */
    suspend fun removeRoom(roomId: String): Result<Unit> {
        val existing = download().getOrDefault(emptyList())
        return upload(existing.filter { it.id != roomId })
    }
}
