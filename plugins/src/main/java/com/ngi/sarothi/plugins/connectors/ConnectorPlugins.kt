package com.ngi.sarothi.plugins.connectors

import android.net.Uri
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ngi.sarothi.core.crypto.SecretStore
import com.ngi.sarothi.core.plugin.JsonSchema
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.plugin.PluginAvailability
import com.ngi.sarothi.core.plugin.PluginCategory
import com.ngi.sarothi.core.plugin.PluginContext
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.plugin.pluginContext
import com.ngi.sarothi.core.safety.ConfirmationPreview
import com.ngi.sarothi.core.safety.ConfirmationReason
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import com.ngi.sarothi.plugins.common.textOrAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shortens a credential for display without revealing it. */
internal fun mask(secret: String): String =
    if (secret.length <= 6) "•".repeat(secret.length)
    else secret.take(3) + "…" + secret.takeLast(2)

/**
 * GitHub over its REST API.
 *
 * Authentication is a personal access token the user pastes in Settings →
 * Connectors, stored in [SecretStore] — Android Keystore-backed
 * EncryptedSharedPreferences, device-local, never on the SD card and never in a
 * log. Without a token this reports unavailable rather than making anonymous
 * calls that would silently hit a much lower rate limit and look like a GitHub
 * outage.
 */
class GithubPlugin : Plugin {
    override val name = "github"
    override val description =
        "Work with GitHub: list or search issues, read an issue's comments, create an issue, list " +
            "repositories. Uses a personal access token the user adds in Settings → Connectors; the " +
            "token stays in Android's Keystore-backed store on this device and is never written to the " +
            "SD card or logged."
    override val category = PluginCategory.CONNECTOR
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "action" to JsonSchema.Property.Text(
                "What to do.",
                enum = listOf("whoami", "list_repos", "list_issues", "read_issue", "create_issue", "search_code"),
            ),
            "owner" to JsonSchema.Property.Text("Repository owner, e.g. 'E-najid'."),
            "repo" to JsonSchema.Property.Text("Repository name, e.g. 'Sarothi'."),
            "number" to JsonSchema.Property.Integer("Issue number, for read_issue.", minimum = 1),
            "title" to JsonSchema.Property.Text("Title, for create_issue."),
            "body" to JsonSchema.Property.Text("Body text, for create_issue."),
            "query" to JsonSchema.Property.Text("Search text, for search_code or to filter issues."),
            "state" to JsonSchema.Property.Text("Issue state filter.", enum = listOf("open", "closed", "all"), default = "open"),
            "limit" to JsonSchema.Property.Integer("How many results.", minimum = 1, maximum = 50, default = 15),
        ),
        required = listOf("action"),
    )

    override val example = """{"action":"list_issues","owner":"E-najid","repo":"Sarothi","state":"open"}"""

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val token = context.secrets.getString(SecretStore.KEY_GITHUB_TOKEN)
        return when {
            !context.network.isOnline() -> PluginAvailability.unavailable(
                reason = "GitHub is reached over the internet and this phone is offline.",
                fixAction = "Turn on Wi-Fi or mobile data.",
            )
            token.isNullOrBlank() -> PluginAvailability.unavailable(
                reason = "No GitHub personal access token is saved on this device.",
                fixAction = "Settings → Connectors → GitHub, then paste a token with the scopes you " +
                    "want Sarothi to have. Sarothi cannot create one for you.",
            )
            else -> PluginAvailability.READY
        }
    }

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview? {
        val action = params.stringOrNull("action") ?: return null
        if (action != "create_issue") return null
        val owner = params.stringOrNull("owner") ?: "?"
        val repo = params.stringOrNull("repo") ?: "?"
        val title = params.stringOrNull("title") ?: "(no title)"
        return ConfirmationPreview(
            title = "Create this public GitHub issue?",
            detailLines = listOf(
                "Repository: $owner/$repo",
                "Title: $title",
                "Body: " + (params.stringOrNull("body") ?: "(empty)"),
                "Anyone who can see the repository will see this, under your account.",
            ),
            reason = ConfirmationReason.OUTBOUND_MESSAGE,
            allowRemember = false,
        )
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val action = params.textOrAsk("action", "What should Sarothi do on GitHub?")
        val token = context.secrets.getString(SecretStore.KEY_GITHUB_TOKEN)
        if (token.isNullOrBlank()) {
            return PluginResult.Unavailable(availability(context))
        }
        val limit = params.get("limit")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 50) ?: 15
        val owner = params.stringOrNull("owner")?.takeIf { it.isNotBlank() }
        val repo = params.stringOrNull("repo")?.takeIf { it.isNotBlank() }
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
        )

        val path = when (action) {
            "whoami" -> "https://api.github.com/user"
            "list_repos" -> "https://api.github.com/user/repos?per_page=$limit&sort=pushed"
            "list_issues" -> {
                val (o, r) = ownerRepo(owner, repo) ?: return missingRepositoryFailure(action)
                val state = params.stringOrNull("state") ?: "open"
                "https://api.github.com/repos/$o/$r/issues?state=$state&per_page=$limit"
            }
            "read_issue" -> {
                val (o, r) = ownerRepo(owner, repo) ?: return missingRepositoryFailure(action)
                val number = params.get("number")?.takeIf { it.isJsonPrimitive }?.asLong
                    ?: throw com.ngi.sarothi.core.error.MissingInformationException(
                        field = "number",
                        questionForUser = "Which issue number should Sarothi read?",
                    )
                "https://api.github.com/repos/$o/$r/issues/$number"
            }
            "create_issue" -> {
                val (o, r) = ownerRepo(owner, repo) ?: return missingRepositoryFailure(action)
                "https://api.github.com/repos/$o/$r/issues"
            }
            "search_code" -> {
                val query = params.textOrAsk("query", "What should Sarothi search GitHub for?")
                val scoped = if (owner != null && repo != null) "$query repo:$owner/$repo" else query
                "https://api.github.com/search/code?q=" + java.net.URLEncoder.encode(scoped, "UTF-8") +
                    "&per_page=$limit"
            }
            else -> return PluginResult.Failure(
                summaryForUser = "\"$action\" is not a GitHub action Sarothi supports. It can do: " +
                    "whoami, list_repos, list_issues, read_issue, create_issue, search_code.",
                errorClass = "UnknownActionException",
                retriable = true,
            )
        }

        val body: ByteArray? = if (action == "create_issue") {
            val title = params.textOrAsk("title", "What should the issue be titled?")
            val issueBody = params.stringOrNull("body") ?: ""
            Json.obj {
                addProperty("title", title)
                addProperty("body", issueBody)
            }.toString().toByteArray(Charsets.UTF_8)
        } else {
            null
        }

        val response = withContext(Dispatchers.IO) {
            runCatching {
                if (body == null) context.http.get(path, headers)
                else context.http.post(path, body, "application/json", headers)
            }
        }
        return response.fold(
            onSuccess = { result ->
                if (!result.isSuccess) return httpFailure(action, result.statusCode, result.bodyText())
                val payload = runCatching { JsonParser.parseString(result.bodyText()) }.getOrNull()
                    ?: return PluginResult.Failure(
                        "GitHub returned something that is not JSON for '$action'.",
                        "MalformedResponseException",
                        retriable = true,
                    )
                summarise(action, payload, mask(token))
            },
            onFailure = { failure ->
                PluginResult.Failure(
                    summaryForUser = "Sarothi could not reach GitHub: ${failure.javaClass.simpleName}: " +
                        "${failure.message}",
                    errorClass = failure.javaClass.simpleName,
                    retriable = true,
                )
            },
        )
    }

    private fun ownerRepo(owner: String?, repo: String?): Pair<String, String>? =
        if (!owner.isNullOrBlank() && !repo.isNullOrBlank()) owner to repo else null

    private fun missingRepositoryFailure(action: String) = PluginResult.Failure(
        summaryForUser = "'$action' needs both an owner and a repository, for example owner=E-najid and " +
            "repo=Sarothi.",
        errorClass = "MissingRepositoryException",
        retriable = true,
    )

    private fun httpFailure(action: String, status: Int, body: String): PluginResult {
        val message = runCatching {
            JsonParser.parseString(body).asJsonObject.stringOrNull("message")
        }.getOrNull()
        return when (status) {
            401 -> PluginResult.Unavailable(
                PluginAvailability.unavailable(
                    reason = "GitHub rejected the saved token (HTTP 401). It is expired, revoked, or " +
                        "does not have the scope '$action' needs.",
                    fixAction = "Settings → Connectors → GitHub and replace the token.",
                ),
            )
            403 -> PluginResult.Failure(
                summaryForUser = "GitHub refused this (HTTP 403): ${message ?: "forbidden"}. It is " +
                    "usually a missing token scope or an exhausted rate limit.",
                errorClass = "ForbiddenException",
                retriable = false,
            )
            404 -> PluginResult.Failure(
                summaryForUser = "GitHub has no such resource (HTTP 404): ${message ?: "not found"}. " +
                    "Check the owner, repository and number.",
                errorClass = "NotFoundException",
                retriable = true,
            )
            422 -> PluginResult.Failure(
                summaryForUser = "GitHub rejected the request as invalid (HTTP 422): ${message ?: ""}",
                errorClass = "ValidationFailedException",
                retriable = true,
            )
            else -> PluginResult.Failure(
                summaryForUser = "GitHub answered HTTP $status for '$action': ${message ?: ""}",
                errorClass = "HttpErrorException",
                retriable = status in 500..599 || status == 429,
            )
        }
    }

    private fun summarise(action: String, payload: com.google.gson.JsonElement, tokenMask: String): PluginResult {
        return when (action) {
            "whoami" -> {
                val user = payload.asJsonObject
                PluginResult.Success(
                    summaryForUser = "Signed in to GitHub as ${user.stringOrNull("login")} " +
                        "(${user.stringOrNull("name") ?: "no name set"}), token $tokenMask.",
                    data = Json.obj {
                        addProperty("login", user.stringOrNull("login") ?: "")
                        addProperty("name", user.stringOrNull("name") ?: "")
                        addProperty("public_repos", user.get("public_repos")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0)
                        user.stringOrNull("html_url")?.let { addProperty("url", it) }
                        addProperty("token", tokenMask)
                    },
                )
            }
            "list_repos" -> {
                val repos = payload.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
                PluginResult.Success(
                    summaryForUser = "${repos.size} repository(ies)" +
                        if (repos.isNotEmpty()) ": " + repos.take(5).joinToString { it.stringOrNull("full_name") ?: "?" } else ".",
                    data = Json.obj {
                        add("repos", Json.arr {
                            repos.forEach { entry ->
                                add(Json.obj {
                                    addProperty("full_name", entry.stringOrNull("full_name") ?: "")
                                    addProperty("private", entry.get("private")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false)
                                    addProperty("pushed_at", entry.stringOrNull("pushed_at") ?: "")
                                    entry.stringOrNull("description")?.let { addProperty("description", it) }
                                    entry.stringOrNull("html_url")?.let { addProperty("url", it) }
                                })
                            }
                        })
                        addProperty("count", repos.size)
                    },
                )
            }
            "list_issues" -> {
                val issues = payload.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
                    // The issues endpoint also returns pull requests; they have a
                    // "pull_request" key and are not issues.
                    .filterNot { it.has("pull_request") }
                PluginResult.Success(
                    summaryForUser = if (issues.isEmpty()) "No open issues match."
                    else "${issues.size} issue(s); newest is #${issues.first().get("number")?.asInt} " +
                        "\"${issues.first().stringOrNull("title")}\"",
                    data = Json.obj {
                        add("issues", Json.arr {
                            issues.forEach { entry ->
                                add(Json.obj {
                                    addProperty("number", entry.get("number")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1)
                                    addProperty("title", entry.stringOrNull("title") ?: "")
                                    addProperty("state", entry.stringOrNull("state") ?: "")
                                    addProperty("author", entry.getAsJsonObject("author")?.stringOrNull("login") ?: "")
                                    addProperty("comments", entry.get("comments")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0)
                                    addProperty("updated_at", entry.stringOrNull("updated_at") ?: "")
                                    entry.stringOrNull("html_url")?.let { addProperty("url", it) }
                                })
                            }
                        })
                        addProperty("count", issues.size)
                    },
                )
            }
            "read_issue" -> {
                val issue = payload.asJsonObject
                PluginResult.Success(
                    summaryForUser = "#${issue.get("number")?.asInt} \"${issue.stringOrNull("title")}\" " +
                        "(${issue.stringOrNull("state")}), by " +
                        "${issue.getAsJsonObject("author")?.stringOrNull("login") ?: "unknown"}",
                    data = Json.obj {
                        addProperty("number", issue.get("number")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1)
                        addProperty("title", issue.stringOrNull("title") ?: "")
                        addProperty("state", issue.stringOrNull("state") ?: "")
                        addProperty("body", issue.stringOrNull("body") ?: "")
                        addProperty("author", issue.getAsJsonObject("author")?.stringOrNull("login") ?: "")
                        addProperty("comments", issue.get("comments")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0)
                        addProperty("created_at", issue.stringOrNull("created_at") ?: "")
                        add("labels", Json.arr {
                            issue.getAsJsonArray("labels")?.forEach { label ->
                                if (label.isJsonObject) add(label.asJsonObject.stringOrNull("name") ?: "")
                            }
                        })
                        issue.stringOrNull("html_url")?.let { addProperty("url", it) }
                    },
                )
            }
            "create_issue" -> {
                val issue = payload.asJsonObject
                PluginResult.Success(
                    summaryForUser = "Created issue #${issue.get("number")?.asInt} " +
                        "\"${issue.stringOrNull("title")}\" — ${issue.stringOrNull("html_url")}",
                    data = Json.obj {
                        addProperty("number", issue.get("number")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1)
                        addProperty("title", issue.stringOrNull("title") ?: "")
                        addProperty("url", issue.stringOrNull("html_url") ?: "")
                        addProperty("repository", issue.stringOrNull("repository_url") ?: "")
                    },
                    spoken = "ইস্যু তৈরি করে দিয়েছি।",
                    memorable = listOf("opened GitHub issue #${issue.get("number")?.asInt} ${issue.stringOrNull("title")}"),
                )
            }
            else -> {
                val root = payload.asJsonObject
                val items = root.getAsJsonArray("items")?.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
                    ?: emptyList()
                PluginResult.Success(
                    summaryForUser = "${root.get("total_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: items.size} " +
                        "match(es) on GitHub; showing ${items.size}.",
                    data = Json.obj {
                        add("results", Json.arr {
                            items.forEach { entry ->
                                add(Json.obj {
                                    addProperty("name", entry.stringOrNull("name") ?: "")
                                    addProperty("path", entry.stringOrNull("path") ?: "")
                                    addProperty("repository", entry.getAsJsonObject("repository")?.stringOrNull("full_name") ?: "")
                                    entry.stringOrNull("html_url")?.let { addProperty("url", it) }
                                })
                            }
                        })
                        addProperty("total", root.get("total_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: items.size)
                    },
                )
            }
        }
    }
}

/**
 * Telegram bot messaging.
 *
 * Uses the public Bot API with a bot token the user creates with @BotFather. The
 * token lives in [SecretStore]. Sarothi can only message chats the bot is already
 * in — Telegram's own restriction, not a shortcut — and `getUpdates` is how it
 * discovers which chats those are.
 */
class TelegramBotPlugin : Plugin {
    override val name = "telegram_bot"
    override val description =
        "Send a message through a Telegram bot, or list the chats that bot can reach. The bot token " +
            "(from @BotFather) is saved in Settings → Connectors and stays in this device's Keystore-" +
            "backed store. A bot can only message chats it has already been added to; Sarothi cannot " +
            "message an arbitrary person."
    override val category = PluginCategory.CONNECTOR
    override val sensitivity = Sensitivity.SENSITIVE

    override val parameters = JsonSchema(
        properties = mapOf(
            "action" to JsonSchema.Property.Text("What to do.", enum = listOf("whoami", "list_chats", "send_message")),
            "chat_id" to JsonSchema.Property.Text("The chat to send to, from list_chats."),
            "text" to JsonSchema.Property.Text("The message text."),
        ),
        required = listOf("action"),
    )

    override val example = """{"action":"send_message","chat_id":"123456789","text":"Task finished"}"""

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val token = context.secrets.getString(TOKEN_KEY)
        return when {
            !context.network.isOnline() -> PluginAvailability.unavailable(
                reason = "Telegram is reached over the internet and this phone is offline.",
            )
            token.isNullOrBlank() -> PluginAvailability.unavailable(
                reason = "No Telegram bot token is saved on this device.",
                fixAction = "Create a bot with @BotFather, then paste its token in Settings → Connectors → Telegram.",
            )
            else -> PluginAvailability.READY
        }
    }

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview? {
        val action = params.stringOrNull("action") ?: return null
        if (action != "send_message") return null
        return ConfirmationPreview(
            title = "Send this Telegram message?",
            detailLines = listOf(
                "Chat id: " + (params.stringOrNull("chat_id") ?: "(not given)"),
                "Text: " + (params.stringOrNull("text") ?: "(empty)"),
                "It goes out through your bot, to everyone in that chat, immediately.",
            ),
            reason = ConfirmationReason.OUTBOUND_MESSAGE,
            allowRemember = false,
        )
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val action = params.textOrAsk("action", "What should Sarothi do with the Telegram bot?")
        val token = context.secrets.getString(TOKEN_KEY)
        if (token.isNullOrBlank()) return PluginResult.Unavailable(availability(context))

        val url = when (action) {
            "whoami" -> "https://api.telegram.org/bot$token/getMe"
            "list_chats" -> "https://api.telegram.org/bot$token/getUpdates?limit=100"
            "send_message" -> "https://api.telegram.org/bot$token/sendMessage"
            else -> return PluginResult.Failure(
                "\"$action\" is not a Telegram action Sarothi supports. It can do: whoami, list_chats, send_message.",
                "UnknownActionException",
                retriable = true,
            )
        }

        val body = if (action == "send_message") {
            val chatId = params.textOrAsk("chat_id", "Which chat should the message go to? Use list_chats to see the ids.")
            val text = params.textOrAsk("text", "What should the Telegram message say?")
            if (text.length > MAX_MESSAGE_LENGTH) {
                return PluginResult.Failure(
                    summaryForUser = "Telegram accepts at most $MAX_MESSAGE_LENGTH characters per " +
                        "message and this has ${text.length}. Split it.",
                    errorClass = "MessageTooLongException",
                    retriable = true,
                )
            }
            Json.obj {
                addProperty("chat_id", chatId)
                addProperty("text", text)
            }.toString().toByteArray(Charsets.UTF_8)
        } else {
            null
        }

        val response = withContext(Dispatchers.IO) {
            runCatching {
                if (body == null) context.http.get(url) else context.http.post(url, body, "application/json")
            }
        }.getOrElse { failure ->
            return PluginResult.Failure(
                "Sarothi could not reach Telegram: ${failure.javaClass.simpleName}: ${failure.message}",
                failure.javaClass.simpleName,
                retriable = true,
            )
        }
        val payload = runCatching { JsonParser.parseString(response.bodyText()).asJsonObject }.getOrNull()
        if (payload == null) {
            return PluginResult.Failure(
                "Telegram answered HTTP ${response.statusCode} with something that is not JSON.",
                "MalformedResponseException",
                retriable = response.statusCode in 500..599,
            )
        }
        val ok = payload.get("ok")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        if (!ok) {
            val description = payload.stringOrNull("description") ?: "Telegram refused the request"
            val errorCode = payload.get("error_code")?.takeIf { it.isJsonPrimitive }?.asInt
            return if (errorCode == 401) {
                PluginResult.Unavailable(
                    PluginAvailability.unavailable(
                        reason = "Telegram rejected the saved bot token (401). It is wrong or revoked.",
                        fixAction = "Settings → Connectors → Telegram and paste a fresh token from @BotFather.",
                    ),
                )
            } else {
                PluginResult.Failure(
                    summaryForUser = "Telegram refused that${errorCode?.let { " (error $it)" } ?: ""}: $description",
                    errorClass = "TelegramApiException",
                    retriable = errorCode == 429 || (errorCode ?: 0) in 500..599,
                )
            }
        }

        return when (action) {
            "whoami" -> {
                val me = payload.getAsJsonObject("result")
                PluginResult.Success(
                    summaryForUser = "Bot @${me.stringOrNull("username")} (\"${me.stringOrNull("first_name")}\").",
                    data = Json.obj {
                        addProperty("username", me.stringOrNull("username") ?: "")
                        addProperty("first_name", me.stringOrNull("first_name") ?: "")
                        addProperty("id", me.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: -1L)
                    },
                )
            }
            "list_chats" -> {
                val updates = payload.getAsJsonArray("result")?.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
                    ?: emptyList()
                val chats = linkedMapOf<Long, String>()
                updates.forEach { update ->
                    val message = update.getAsJsonObject("message") ?: update.getAsJsonObject("channel_post")
                        ?: update.getAsJsonObject("edited_message") ?: return@forEach
                    val chat = message.getAsJsonObject("chat") ?: return@forEach
                    val id = chat.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return@forEach
                    val title = chat.stringOrNull("title")
                        ?: chat.stringOrNull("username")
                        ?: listOfNotNull(chat.stringOrNull("first_name"), chat.stringOrNull("last_name"))
                            .filter { it.isNotBlank() }.joinToString(" ")
                    chats[id] = title.ifBlank { "chat $id" }
                }
                if (chats.isEmpty()) {
                    return PluginResult.Success(
                        summaryForUser = "Telegram returned no updates for this bot, so there are no " +
                            "chats Sarothi can see. A bot only learns about a chat once someone " +
                            "messages it; send your bot a message and try again.",
                        data = Json.obj { addProperty("count", 0) },
                    )
                }
                PluginResult.Success(
                    summaryForUser = "${chats.size} chat(s) this bot can reach: " +
                        chats.entries.take(5).joinToString("; ") { "${it.value} (${it.key})" },
                    data = Json.obj {
                        add("chats", Json.arr {
                            chats.forEach { (id, title) ->
                                add(Json.obj {
                                    addProperty("id", id.toString())
                                    addProperty("title", title)
                                })
                            }
                        })
                        addProperty("count", chats.size)
                    },
                )
            }
            else -> {
                val sent = payload.getAsJsonObject("result")
                val chatTitle = sent?.getAsJsonObject("chat")?.let { chat ->
                    chat.stringOrNull("title") ?: chat.stringOrNull("username")
                }
                PluginResult.Success(
                    summaryForUser = "Sent the Telegram message to " +
                        (chatTitle ?: sent?.getAsJsonObject("chat")?.get("id")?.asString ?: "that chat") + ".",
                    data = Json.obj {
                        addProperty("message_id", sent?.get("message_id")?.takeIf { it.isJsonPrimitive }?.asLong ?: -1L)
                        addProperty("chat_id", sent?.getAsJsonObject("chat")?.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: -1L)
                        chatTitle?.let { addProperty("chat_title", it) }
                        addProperty("text", sent?.stringOrNull("text") ?: "")
                    },
                    spoken = "টেলিগ্রামে পাঠিয়ে দিয়েছি।",
                )
            }
        }
    }

    private companion object {
        /** Device-local secret key. Not the vault: a bot token must not travel on an SD card. */
        const val TOKEN_KEY = "connector.telegram.botToken"
        const val MAX_MESSAGE_LENGTH = 4096
    }
}

/**
 * Posts to an arbitrary webhook.
 *
 * A general escape hatch, and therefore the one plugin here whose destination is
 * entirely user-supplied. The URL has to be https unless the user has explicitly
 * allowed plain http for it, the payload is shown in full before sending, and the
 * whole thing is audited — a webhook is an outbound message to a server nobody
 * else has vetted.
 */
class WebhookPlugin : Plugin {
    override val name = "webhook"
    override val description =
        "POST JSON to a webhook URL the user has configured, e.g. an IFTTT, n8n or Home Assistant " +
            "endpoint. Plain http is refused unless the user has explicitly allowed that host in " +
            "Settings → Connectors, because a token in an http URL is readable by anyone on the path."
    override val category = PluginCategory.CONNECTOR
    override val sensitivity = Sensitivity.SENSITIVE

    override val parameters = JsonSchema(
        properties = mapOf(
            "name" to JsonSchema.Property.Text("Which configured webhook to use, from Settings → Connectors → Webhooks."),
            "payload" to JsonSchema.Property.Record("The JSON body to send.", fields = JsonSchema(properties = emptyMap())),
            "text_value" to JsonSchema.Property.Text("A single text value, sent as {\"text\": …} when no payload is given."),
        ),
        required = listOf("name"),
    )

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview {
        val name = params.stringOrNull("name") ?: "(unspecified)"
        val payload = params.get("payload")
        val text = params.stringOrNull("text_value")
        return ConfirmationPreview(
            title = "Send this webhook?",
            detailLines = buildList {
                add("Webhook: $name")
                add(
                    "Body: " + when {
                        payload != null && payload.isJsonObject -> payload.toString().take(400)
                        text != null -> """{"text":"$text"}"""
                        else -> "(empty)"
                    },
                )
                add("The destination URL is configured by the user and is not shown to the model.")
                add("This leaves the phone and cannot be recalled.")
            },
            reason = ConfirmationReason.OUTBOUND_MESSAGE,
            allowRemember = false,
        )
    }

    override suspend fun availability(context: PluginContext): PluginAvailability = when {
        !context.network.isOnline() -> PluginAvailability.unavailable(
            reason = "A webhook needs an internet connection and this phone is offline.",
        )
        context.config.all().isEmpty() -> PluginAvailability.unavailable(
            reason = "No webhooks are configured yet.",
            fixAction = "Settings → Connectors → Webhooks, add a name and its URL.",
        )
        else -> PluginAvailability.READY
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val name = params.textOrAsk(
            "name",
            "Which configured webhook should Sarothi post to?",
            choices = webhookNames(context),
        )
        val configured = webhookNames(context)
        if (configured.isEmpty()) {
            return PluginResult.Unavailable(availability(context))
        }
        if (name !in configured) {
            return PluginResult.Failure(
                summaryForUser = "There is no webhook called \"$name\". Configured ones: ${configured.joinToString()}. " +
                    "Sarothi will not post to a URL it was not given.",
                errorClass = "UnknownWebhookException",
                retriable = true,
            )
        }
        val url = context.config.string("url.$name")
            ?: return PluginResult.Failure(
                "The webhook \"$name\" has a name saved but no URL.",
                "MisconfiguredWebhookException",
                retriable = false,
            )

        if (url.startsWith("http://")) {
            val allowedHosts = context.config.string("allow_insecure_hosts")?.split(',')
                ?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: emptyList()
            val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
            if (host == null || host !in allowedHosts) {
                return PluginResult.Failure(
                    summaryForUser = "That webhook uses plain http, so anything in the request could be " +
                        "read on the way. Sarothi refuses it unless you add '$host' to " +
                        "'allow_insecure_hosts' in Settings → Connectors → Webhooks.",
                    errorClass = "InsecureWebhookException",
                    retriable = false,
                )
            }
        } else if (!url.startsWith("https://")) {
            return PluginResult.Failure(
                summaryForUser = "\"$url\" is not an http or https address.",
                errorClass = "MalformedUrlException",
                retriable = false,
            )
        }

        val body = when {
            params.get("payload")?.isJsonObject == true -> params.getAsJsonObject("payload").toString()
            else -> {
                val text = params.stringOrNull("text_value")
                    ?: throw com.ngi.sarothi.core.error.MissingInformationException(
                        field = "payload",
                        questionForUser = "What should the webhook body contain?",
                    )
                Json.obj { addProperty("text", text) }.toString()
            }
        }
        val headers = buildMap {
            context.config.string("header.$name")?.let { value -> put("X-Sarothi-Webhook", value) }
        }

        val response = withContext(Dispatchers.IO) {
            runCatching { context.http.post(url, body.toByteArray(Charsets.UTF_8), "application/json", headers) }
        }.getOrElse { failure ->
            return PluginResult.Failure(
                "Sarothi could not reach that webhook: ${failure.javaClass.simpleName}: ${failure.message}",
                failure.javaClass.simpleName,
                retriable = true,
            )
        }
        val responseBody = response.bodyText().take(600)
        return if (response.isSuccess) {
            PluginResult.Success(
                summaryForUser = "Posted to the \"$name\" webhook; it answered HTTP ${response.statusCode}.",
                data = Json.obj {
                    addProperty("webhook", name)
                    addProperty("status", response.statusCode)
                    addProperty("bytes_sent", body.toByteArray(Charsets.UTF_8).size)
                    addProperty("response_preview", responseBody)
                    // The URL itself is not echoed back: it usually contains a token.
                    addProperty("url_host", runCatching { Uri.parse(url).host }.getOrNull() ?: "")
                },
            )
        } else {
            PluginResult.Failure(
                summaryForUser = "The \"$name\" webhook answered HTTP ${response.statusCode}: $responseBody",
                errorClass = "HttpErrorException",
                retriable = response.statusCode in 500..599 || response.statusCode == 429,
            )
        }
    }

    private fun webhookNames(context: PluginContext): List<String> =
        context.config.all().keys.filter { it.startsWith("url.") }.map { it.removePrefix("url.") }.sorted()
}
