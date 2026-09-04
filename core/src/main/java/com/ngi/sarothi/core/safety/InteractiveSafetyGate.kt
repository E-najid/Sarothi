package com.ngi.sarothi.core.safety

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.storage.VaultManager
import com.ngi.sarothi.core.storage.VaultPaths
import com.ngi.sarothi.core.util.Ids
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.arrayOrNull
import com.ngi.sarothi.core.util.stringOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/** One confirmation waiting for a human, exposed to the UI layer. */
data class PendingConfirmation(
    val id: String,
    val request: ConfirmationRequest,
    val requestedAt: Long,
) {
    internal val decision = CompletableDeferred<ConfirmationDecision>()
}

/**
 * Stops the agent and asks the user.
 *
 * The gate is the single choke point between "the model decided" and "the phone
 * did something". Its rules are deliberately not configurable:
 *
 *  - [Sensitivity.CRITICAL] actions are never remembered as "always allow";
 *  - an unattended task (schedule or notification rule) can never be granted a
 *    sensitive action at all, because there is nobody to ask;
 *  - a request nobody answers within [timeoutMillis] is a denial, not an approval;
 *  - approvals are remembered per plugin+action, never globally.
 *
 * Remembered approvals live in the vault (`plugins_config/safety_approvals.json`,
 * encrypted) so they travel with the user's data and can be reviewed and revoked
 * in Settings → Safety.
 */
class InteractiveSafetyGate(
    private val vault: VaultManager,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : SafetyGate {

    private val mutex = Mutex()
    private val pendingById = LinkedHashMap<String, PendingConfirmation>()

    private val _pending = MutableStateFlow<List<PendingConfirmation>>(emptyList())

    /** The UI observes this and shows a dialog for the first entry. */
    val pending: StateFlow<List<PendingConfirmation>> = _pending.asStateFlow()

    @Volatile
    private var sessionApprovals: MutableSet<String> = mutableSetOf()

    override suspend fun requireConfirmation(request: ConfirmationRequest): ConfirmationDecision {
        val key = keyFor(request.pluginName, request.action)

        if (request.sensitivity == Sensitivity.CRITICAL && request.allowRemember) {
            // A caller asked to make a critical action rememberable. That is not
            // permitted; the request still goes to the user, just without the option.
            Log.w(TAG, "Refusing 'allowRemember' for CRITICAL action ${request.action}")
        }
        val mayRemember = request.allowRemember && request.sensitivity != Sensitivity.CRITICAL

        if (request.unattended) {
            return ConfirmationDecision.DENY.also {
                Log.i(TAG, "Unattended task denied ${request.pluginName}/${request.action} (${request.reason})")
            }
        }

        if (isRemembered(request.pluginName, request.action) || key in sessionApprovals) {
            return ConfirmationDecision.ALLOW_ALWAYS
        }

        val pending = PendingConfirmation(Ids.newId("confirm"), request, System.currentTimeMillis())
        mutex.withLock {
            pendingById[pending.id] = pending
            _pending.value = pendingById.values.toList()
        }

        val decision = withTimeoutOrNull(timeoutMillis) { pending.decision.await() }
            ?: ConfirmationDecision.NO_ANSWER

        mutex.withLock {
            pendingById.remove(pending.id)
            _pending.value = pendingById.values.toList()
        }

        when {
            decision == ConfirmationDecision.ALLOW_ALWAYS && mayRemember -> remember(key, request)
            decision == ConfirmationDecision.ALLOW_ALWAYS && !mayRemember ->
                sessionApprovals += key // downgraded: remembered for this process only
            decision == ConfirmationDecision.ALLOW_FOR_SESSION -> sessionApprovals += key
            else -> Unit
        }
        return decision
    }

    /** Answers a pending request. Called by the confirmation dialog. */
    fun answer(confirmationId: String, decision: ConfirmationDecision): Boolean {
        val pending = pendingById[confirmationId] ?: return false
        pending.decision.complete(decision)
        return true
    }

    /** Dismisses every pending request as unanswered (app backgrounded, task cancelled). */
    fun dismissAll() {
        pendingById.values.forEach { it.decision.complete(ConfirmationDecision.NO_ANSWER) }
        pendingById.clear()
        _pending.value = emptyList()
    }

    override fun isRemembered(pluginName: String, action: String): Boolean {
        val key = keyFor(pluginName, action)
        return key in sessionApprovals || loadRemembered().containsKey(key)
    }

    override suspend fun forgetAllRemembered() {
        mutex.withLock {
            sessionApprovals = mutableSetOf()
            if (vault.isUnlocked) {
                vault.writeEncryptedJson(APPROVALS_PATH, Json.obj {
                    addProperty("schema_version", 1)
                    add("approvals", JsonArray())
                })
            }
        }
    }

    override suspend fun remembered(): List<RememberedApproval> = mutex.withLock {
        val durable = if (vault.isUnlocked) {
            loadRemembered().values.map { it.approval }
        } else {
            emptyList()
        }
        val session = sessionApprovals.map { key ->
            val (plugin, action) = key.split(KEY_SEPARATOR, limit = 2)
            RememberedApproval(plugin, action, "this session", "session")
        }
        (durable + session).sortedWith(compareBy({ it.pluginName }, { it.action }))
    }

    private data class Stored(val approval: RememberedApproval, val sensitivity: String)

    private fun loadRemembered(): Map<String, Stored> {
        if (!vault.isUnlocked) return emptyMap()
        val json = runCatching { vault.readEncryptedJson(APPROVALS_PATH) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Stored>()
        json.arrayOrNull("approvals")?.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val entry = element.asJsonObject
            val plugin = entry.stringOrNull("plugin") ?: return@forEach
            val action = entry.stringOrNull("action") ?: return@forEach
            out[keyFor(plugin, action)] = Stored(
                approval = RememberedApproval(
                    pluginName = plugin,
                    action = action,
                    grantedAt = entry.stringOrNull("granted_at") ?: "",
                    scope = "durable",
                ),
                sensitivity = entry.stringOrNull("sensitivity") ?: Sensitivity.SENSITIVE.name.lowercase(),
            )
        }
        return out
    }

    private fun remember(key: String, request: ConfirmationRequest) {
        if (!vault.isUnlocked) {
            // Without the key the approval cannot be persisted. Falling back to a
            // session approval is the safe direction: less memory, not more.
            sessionApprovals += key
            return
        }
        val existing = loadRemembered().toMutableMap()
        existing[key] = Stored(
            approval = RememberedApproval(
                pluginName = request.pluginName,
                action = request.action,
                grantedAt = Instant.now().toString(),
                scope = "durable",
            ),
            sensitivity = request.sensitivity.name.lowercase(),
        )
        val document = JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("updated_at", Instant.now().toString())
            add("approvals", JsonArray().also { array ->
                existing.values.sortedBy { it.approval.pluginName + it.approval.action }.forEach { stored ->
                    array.add(Json.obj {
                        addProperty("plugin", stored.approval.pluginName)
                        addProperty("action", stored.approval.action)
                        addProperty("granted_at", stored.approval.grantedAt)
                        addProperty("sensitivity", stored.sensitivity)
                    })
                }
            })
        }
        runCatching { vault.writeEncryptedJson(APPROVALS_PATH, document) }
            .onFailure { Log.w(TAG, "Could not persist the approval for $key", it) }
    }

    /** Revokes one durable approval from the Settings screen. */
    suspend fun forget(pluginName: String, action: String) {
        mutex.withLock {
            sessionApprovals -= keyFor(pluginName, action)
            if (!vault.isUnlocked) return@withLock
            val existing = loadRemembered().toMutableMap()
            existing.remove(keyFor(pluginName, action))
            val document = JsonObject().apply {
                addProperty("schema_version", 1)
                addProperty("updated_at", Instant.now().toString())
                add("approvals", JsonArray().also { array ->
                    existing.values.forEach { stored ->
                        array.add(Json.obj {
                            addProperty("plugin", stored.approval.pluginName)
                            addProperty("action", stored.approval.action)
                            addProperty("granted_at", stored.approval.grantedAt)
                            addProperty("sensitivity", stored.sensitivity)
                        })
                    }
                })
            }
            runCatching { vault.writeEncryptedJson(APPROVALS_PATH, document) }
        }
    }

    /** Drops session approvals; called when the vault locks or the task ends. */
    fun clearSessionApprovals() {
        sessionApprovals = mutableSetOf()
    }

    private fun keyFor(pluginName: String, action: String) = "$pluginName$KEY_SEPARATOR$action"

    companion object {
        private const val TAG = "SarothiSafety"
        private const val KEY_SEPARATOR = "::"
        private const val DEFAULT_TIMEOUT_MILLIS = 120_000L
        val APPROVALS_PATH = VaultPaths.pluginConfigPath("safety_approvals")
    }
}
