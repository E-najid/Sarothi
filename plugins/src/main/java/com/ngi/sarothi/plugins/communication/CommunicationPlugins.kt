package com.ngi.sarothi.plugins.communication

import android.Manifest
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log
import com.google.gson.JsonObject
import com.ngi.sarothi.core.plugin.JsonSchema
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.plugin.PluginAvailability
import com.ngi.sarothi.core.plugin.PluginCategory
import com.ngi.sarothi.core.plugin.PluginContext
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.plugin.pluginContext
import com.ngi.sarothi.core.plugin.ConfirmationPreview
import com.ngi.sarothi.core.safety.ConfirmationReason
import com.ngi.sarothi.core.screen.NotificationFeed
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import com.ngi.sarothi.plugins.common.Digits
import com.ngi.sarothi.plugins.common.LaunchOutcome
import com.ngi.sarothi.plugins.common.launchForResult
import com.ngi.sarothi.plugins.common.textOrAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A contact match: the name as stored, and one of its numbers or addresses. */
internal data class ContactMatch(
    val displayName: String,
    val value: String,
    val kind: String,
    val contactId: String,
)

/**
 * Looks people up in the phone's contacts.
 *
 * Every communication plugin goes through this rather than accepting a bare name
 * and hoping: "call Rina" has to become a real number, and if two Rinas exist the
 * user is asked which one instead of Sarothi picking.
 */
internal suspend fun findContacts(context: Context, query: String, kind: ContactKind): List<ContactMatch> =
    withContext(Dispatchers.IO) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return@withContext emptyList()

        val projection = when (kind) {
            ContactKind.PHONE -> arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.TYPE,
            )
            ContactKind.EMAIL -> arrayOf(
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.TYPE,
            )
        }
        val uri = when (kind) {
            ContactKind.PHONE -> ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            ContactKind.EMAIL -> ContactsContract.CommonDataKinds.Email.CONTENT_URI
        }
        val selection = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?"
        val args = arrayOf("%$needle%")

        val cursor: Cursor = runCatching {
            context.contentResolver.query(uri, projection, selection, args, null)
        }.getOrElse { failure ->
            Log.w(TAG, "Contacts query failed", failure)
            throw SecurityException(
                "Android refused to read contacts (${failure.javaClass.simpleName}). " +
                    "Grant the Contacts permission and try again.",
            )
        } ?: return@withContext emptyList()

        cursor.use { rows ->
            val nameColumn = rows.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val valueColumn = rows.getColumnIndex(
                when (kind) {
                    ContactKind.PHONE -> ContactsContract.CommonDataKinds.Phone.NUMBER
                    ContactKind.EMAIL -> ContactsContract.CommonDataKinds.Email.ADDRESS
                },
            )
            val idColumn = rows.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val typeColumn = rows.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
            if (nameColumn < 0 || valueColumn < 0) return@use emptyList()

            val matches = mutableListOf<ContactMatch>()
            while (rows.moveToNext()) {
                val name = rows.getString(nameColumn) ?: continue
                val raw = rows.getString(valueColumn) ?: continue
                val value = when (kind) {
                    ContactKind.PHONE -> Digits.toDiallable(raw)
                    ContactKind.EMAIL -> raw.trim()
                }
                if (value.isEmpty()) continue
                val typeLabel = if (typeColumn >= 0 && !rows.isNull(typeColumn)) {
                    val type = rows.getInt(typeColumn)
                    when (kind) {
                        ContactKind.PHONE -> ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                            context.resources, type,
                        ).toString()
                        ContactKind.EMAIL -> ContactsContract.CommonDataKinds.Email.getTypeLabel(
                            context.resources, type,
                        ).toString()
                    }
                } else {
                    kind.name.lowercase()
                }
                matches += ContactMatch(
                    displayName = name,
                    value = value,
                    kind = typeLabel,
                    contactId = if (idColumn >= 0) rows.getString(idColumn) ?: "" else "",
                )
            }
            matches.distinctBy { it.value }
        }
    }

internal enum class ContactKind { PHONE, EMAIL }

private const val TAG = "SarothiComms"

/**
 * Resolves `recipient` — which may be a contact name or a literal number — into a
 * single destination, or asks the user to choose.
 */
internal suspend fun resolveRecipient(
    context: Context,
    raw: String,
    field: String,
    kind: ContactKind,
): String {
    val trimmed = raw.trim()
    if (kind == ContactKind.PHONE && Digits.isProbablyPhoneNumber(trimmed)) {
        return Digits.toDiallable(trimmed)
    }
    if (kind == ContactKind.EMAIL && trimmed.contains('@') && !trimmed.contains(' ')) {
        return trimmed
    }

    val matches = findContacts(context, trimmed, kind)
    return when {
        matches.isEmpty() -> throw com.ngi.sarothi.core.error.MissingInformationException(
            field = field,
            questionForUser = "Sarothi could not find \"$trimmed\" in your contacts, and it will not " +
                "guess a ${if (kind == ContactKind.PHONE) "phone number" else "email address"}. " +
                "Please give the exact ${if (kind == ContactKind.PHONE) "number" else "address"}.",
        )
        matches.size == 1 -> matches.first().value
        else -> throw com.ngi.sarothi.core.error.MissingInformationException(
            field = field,
            questionForUser = "There are ${matches.size} contacts matching \"$trimmed\". Which one?",
            choices = matches.take(6).map { "${it.displayName} (${it.kind}): ${it.value}" },
        )
    }
}

/** Sends an SMS through the platform SmsManager. Always confirms first. */
class SendSmsPlugin : Plugin {
    override val name = "send_sms"
    override val description =
        "Send an SMS to a phone number or a contact name. Sarothi shows the user the number and the " +
            "full text and waits for confirmation before sending — there is no way to send silently. " +
            "Use it for short messages; for anything long or formatted use send_email or open_whatsapp."
    override val category = PluginCategory.COMMUNICATION
    override val sensitivity = Sensitivity.SENSITIVE
    override val requiredPermissions = listOf(Manifest.permission.SEND_SMS)

    override val parameters = JsonSchema(
        properties = mapOf(
            "recipient" to JsonSchema.Property.Text("A contact name or a phone number, e.g. 'রিনা' or '+8801712345678'."),
            "message" to JsonSchema.Property.Text("The exact message text. Never invented: ask if unsure."),
        ),
        required = listOf("recipient", "message"),
    )

    override val example = """{"recipient":"রিনা","message":"আমি ১০ মিনিট দেরিতে পৌঁছাবো"}"""

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val manager = smsManager(context.appContext)
        return if (manager == null) {
            PluginAvailability.unavailable(
                "This device exposes no SMS manager, so Sarothi cannot send text messages directly. " +
                    "A tablet or a device without a telephony stack does this.",
                fixAction = "Use open_whatsapp or send_email instead.",
            )
        } else {
            PluginAvailability.READY
        }
    }

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview {
        val recipient = params.stringOrNull("recipient") ?: "(unknown recipient)"
        val message = params.stringOrNull("message") ?: ""
        return ConfirmationPreview(
            title = "Send this SMS?",
            detailLines = listOf(
                "To: $recipient",
                "Message (${message.length} characters):",
                message.ifBlank { "(empty)" },
            ),
            reason = ConfirmationReason.OUTBOUND_MESSAGE,
            // A sent SMS cannot be recalled, so this must be approved every time.
            allowRemember = false,
        )
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val rawRecipient = params.textOrAsk(
            "recipient",
            "Who should Sarothi send the SMS to? A contact name or a phone number.",
        )
        val message = params.textOrAsk("message", "What exactly should the SMS say? Sarothi will not write it for you.")

        val number = resolveRecipient(context.appContext, rawRecipient, "recipient", ContactKind.PHONE)
        val manager = smsManager(context.appContext)
            ?: return PluginResult.Unavailable(
                PluginAvailability.unavailable("This device has no SMS manager."),
            )

        val parts = runCatching { manager.divideMessage(message) }.getOrElse {
            return PluginResult.Failure(
                "Android could not split the message for sending: ${it.message}",
                "SmsException",
                retriable = false,
            )
        }

        val sent = runCatching {
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                manager.sendTextMessage(number, null, message, null, null)
            }
        }
        return sent.fold(
            onSuccess = {
                PluginResult.Success(
                    summaryForUser = "Sent an SMS to $number (${parts.size} part(s), ${message.length} characters).",
                    data = Json.obj {
                        addProperty("recipient", number)
                        addProperty("requested_recipient", rawRecipient)
                        addProperty("parts", parts.size)
                        addProperty("characters", message.length)
                    },
                    spoken = "মেসেজ পাঠিয়ে দিয়েছি।",
                    memorable = listOf("sent SMS to $number"),
                )
            },
            onFailure = { failure ->
                Log.w(TAG, "SMS send failed", failure)
                PluginResult.Failure(
                    summaryForUser = "Android refused to send the SMS to $number: " +
                        "${failure.javaClass.simpleName}: ${failure.message}",
                    errorClass = failure.javaClass.simpleName,
                    retriable = false,
                )
            },
        )
    }

    private fun smsManager(context: Context): SmsManager? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }
    }.getOrNull()
}

/** Places a call. Direct dialling always confirms; opening the dialler does not. */
class MakeCallPlugin : Plugin {
    override val name = "make_call"
    override val description =
        "Call a phone number or a contact name. With dial_only=true (the default) Sarothi opens the " +
            "phone app with the number ready and the user presses call. With dial_only=false Sarothi " +
            "places the call itself, which always asks for confirmation first."
    override val category = PluginCategory.COMMUNICATION
    override val sensitivity = Sensitivity.SENSITIVE

    override val parameters = JsonSchema(
        properties = mapOf(
            "recipient" to JsonSchema.Property.Text("A contact name or phone number."),
            "dial_only" to JsonSchema.Property.Flag(
                "true opens the dialler without calling; false places the call (needs the Phone permission).",
                default = true,
            ),
        ),
        required = listOf("recipient"),
    )

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview {
        val recipient = params.stringOrNull("recipient") ?: "(unknown)"
        val dialOnly = params.get("dial_only")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        return ConfirmationPreview(
            title = if (dialOnly) "Open the dialler?" else "Place this call now?",
            detailLines = listOf(
                "Number or contact: $recipient",
                if (dialOnly) {
                    "Sarothi will open your phone app with this number filled in. You still press call."
                } else {
                    "Sarothi will start the call immediately, without the dialler screen."
                },
            ),
            reason = ConfirmationReason.OUTBOUND_MESSAGE,
            allowRemember = false,
        )
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val raw = params.textOrAsk("recipient", "Who should Sarothi call?")
        val dialOnly = params.get("dial_only")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        val number = resolveRecipient(context.appContext, raw, "recipient", ContactKind.PHONE)

        val intent = if (dialOnly) {
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        } else {
            Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        }
        return when (val outcome = context.appContext.launchForResult(intent)) {
            LaunchOutcome.Started -> PluginResult.Success(
                summaryForUser = if (dialOnly) "Opened the dialler with $number." else "Calling $number.",
                data = Json.obj {
                    addProperty("number", number)
                    addProperty("requested", raw)
                    addProperty("dial_only", dialOnly)
                },
                spoken = if (dialOnly) "ডায়ালার খুলে দিয়েছি।" else "কল করছি।",
            )
            is LaunchOutcome.NoHandler -> PluginResult.Failure(outcome.reason, "ActivityNotFoundException")
            is LaunchOutcome.Refused -> PluginResult.Failure(
                summaryForUser = if (!dialOnly && outcome.reason.contains("SecurityException")) {
                    "Android blocked the direct call. Grant the Phone permission, or use dial_only=true " +
                        "so you can press call yourself."
                } else {
                    outcome.reason
                },
                errorClass = "SecurityException",
                retriable = false,
            )
        }
    }
}

/** Composes an email in the user's own mail app. */
class SendEmailPlugin : Plugin {
    override val name = "send_email"
    override val description =
        "Open the user's email app with a message ready to send, including to/cc/subject/body. Sarothi " +
            "does not send it: the user presses Send in their own app, which is the confirmation. Use " +
            "it for anything longer or more formal than an SMS."
    override val category = PluginCategory.COMMUNICATION
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "to" to JsonSchema.Property.List("Recipient email addresses or contact names.", items = JsonSchema.Property.Text("One address or name")),
            "cc" to JsonSchema.Property.List("Copy recipients.", items = JsonSchema.Property.Text("One address or name")),
            "subject" to JsonSchema.Property.Text("Subject line."),
            "body" to JsonSchema.Property.Text("The message body."),
        ),
        required = listOf("to", "subject", "body"),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val toRaw = params.getAsJsonArray("to")?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
            ?: emptyList()
        if (toRaw.isEmpty()) {
            return PluginResult.NeedsUserInput(
                question = "Who should the email go to?",
                field = "to",
            )
        }
        val subject = params.textOrAsk("subject", "What should the subject line be?")
        val body = params.textOrAsk("body", "What should the email say?")
        val ccRaw = params.getAsJsonArray("cc")?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
            ?: emptyList()

        val to = toRaw.map { raw ->
            runCatching { resolveRecipient(context.appContext, raw, "to", ContactKind.EMAIL) }
                .getOrElse { failure ->
                    return PluginResult.Failure(
                        summaryForUser = failure.message ?: "Could not resolve the recipient '$raw'.",
                        errorClass = failure.javaClass.simpleName,
                        retriable = true,
                    )
                }
        }
        val cc = ccRaw.mapNotNull { raw ->
            runCatching { resolveRecipient(context.appContext, raw, "cc", ContactKind.EMAIL) }.getOrNull()
        }

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, to.toTypedArray())
            if (cc.isNotEmpty()) putExtra(Intent.EXTRA_CC, cc.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        return when (val outcome = context.appContext.launchForResult(intent)) {
            LaunchOutcome.Started -> PluginResult.Success(
                summaryForUser = "Opened your email app with a draft to ${to.joinToString()} " +
                    "(subject: \"$subject\"). Nothing is sent until you press Send.",
                data = Json.obj {
                    add("to", Json.arr { to.forEach { add(it) } })
                    add("cc", Json.arr { cc.forEach { add(it) } })
                    addProperty("subject", subject)
                    addProperty("body_characters", body.length)
                    addProperty("sent", false)
                },
                spoken = "ইমেইল খসড়া তৈরি করে দিয়েছি।",
            )
            is LaunchOutcome.NoHandler -> PluginResult.Failure(
                summaryForUser = outcome.reason,
                errorClass = "ActivityNotFoundException",
                retriable = false,
            )
            is LaunchOutcome.Refused -> PluginResult.Failure(outcome.reason, "SecurityException")
        }
    }
}

/** Opens WhatsApp with a message ready, or says plainly that it is not installed. */
class WhatsAppPlugin : Plugin {
    override val name = "open_whatsapp"
    override val description =
        "Open WhatsApp with a message to a number already typed in. The user presses Send in WhatsApp, " +
            "so nothing leaves the phone without them seeing it. Needs WhatsApp installed; if it is " +
            "not, this says so instead of falling back to something else."
    override val category = PluginCategory.COMMUNICATION
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "recipient" to JsonSchema.Property.Text("A contact name or phone number. International format works best (+880…)."),
            "message" to JsonSchema.Property.Text("Message text to pre-fill. Empty opens the chat only."),
        ),
        required = listOf("recipient"),
    )

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val installed = context.appContext.packageManager
            .getLaunchIntentForPackage(WHATSAPP_PACKAGE) != null ||
            context.appContext.packageManager.getLaunchIntentForPackage(WHATSAPP_BUSINESS_PACKAGE) != null
        return if (installed) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                "WhatsApp is not installed on this phone.",
                fixAction = "Install WhatsApp, or use send_sms instead.",
            )
        }
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val raw = params.textOrAsk("recipient", "Who should the WhatsApp message go to?")
        val message = params.stringOrNull("message") ?: ""
        val number = resolveRecipient(context.appContext, raw, "recipient", ContactKind.PHONE)

        // wa.me wants digits only, with a country code and no '+'.
        val diallable = Digits.toDiallable(number).filter { it.isDigit() }
        val uri = buildString {
            append("https://wa.me/").append(diallable)
            if (message.isNotBlank()) {
                append("?text=").append(Uri.encode(message))
            }
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            val business = context.appContext.packageManager.getLaunchIntentForPackage(WHATSAPP_BUSINESS_PACKAGE)
            setPackage(
                when {
                    context.appContext.packageManager.getLaunchIntentForPackage(WHATSAPP_PACKAGE) != null -> WHATSAPP_PACKAGE
                    business != null -> WHATSAPP_BUSINESS_PACKAGE
                    else -> null // let Android resolve; the availability check already passed
                },
            )
        }
        return when (val outcome = context.appContext.launchForResult(intent)) {
            LaunchOutcome.Started -> PluginResult.Success(
                summaryForUser = "Opened WhatsApp with $number and the message ready. Press Send in " +
                    "WhatsApp to send it.",
                data = Json.obj {
                    addProperty("number", diallable)
                    addProperty("requested", raw)
                    addProperty("prefilled_characters", message.length)
                    addProperty("sent", false)
                },
                spoken = "হোয়াটসঅ্যাপ খুলে দিয়েছি।",
            )
            is LaunchOutcome.NoHandler -> PluginResult.Failure(
                summaryForUser = "WhatsApp could not open that link: ${outcome.reason}",
                errorClass = "ActivityNotFoundException",
                retriable = false,
            )
            is LaunchOutcome.Refused -> PluginResult.Failure(outcome.reason, "SecurityException")
        }
    }

    private companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"
        const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    }
}

/** Finds contacts by name so the user is never asked to spell out a number. */
class FindContactPlugin : Plugin {
    override val name = "find_contact"
    override val description =
        "Look up a contact by name and return their phone numbers and email addresses. Use it before " +
            "send_sms, make_call or send_email when the user gave a name rather than a number, and to " +
            "check for duplicates."
    override val category = PluginCategory.COMMUNICATION
    override val sensitivity = Sensitivity.READ_ONLY
    override val requiredPermissions = listOf(Manifest.permission.READ_CONTACTS)

    override val parameters = JsonSchema(
        properties = mapOf(
            "name" to JsonSchema.Property.Text("The name to search for."),
            "kind" to JsonSchema.Property.Text("Phone numbers, email addresses, or both.", enum = listOf("phone", "email", "both"), default = "both"),
        ),
        required = listOf("name"),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val name = params.textOrAsk("name", "Whose contact details should Sarothi look up?")
        val kind = params.stringOrNull("kind") ?: "both"

        val phones = if (kind == "email") emptyList() else findContacts(context.appContext, name, ContactKind.PHONE)
        val emails = if (kind == "phone") emptyList() else findContacts(context.appContext, name, ContactKind.EMAIL)

        if (phones.isEmpty() && emails.isEmpty()) {
            return PluginResult.Failure(
                summaryForUser = "No contact matches \"$name\". Sarothi will not guess a number or address.",
                errorClass = "ContactNotFoundException",
                retriable = true,
            )
        }
        val data = Json.obj {
            add("phones", Json.arr {
                phones.forEach { match ->
                    add(Json.obj {
                        addProperty("name", match.displayName)
                        addProperty("number", match.value)
                        addProperty("type", match.kind)
                    })
                }
            })
            add("emails", Json.arr {
                emails.forEach { match ->
                    add(Json.obj {
                        addProperty("name", match.displayName)
                        addProperty("address", match.value)
                        addProperty("type", match.kind)
                    })
                }
            })
            addProperty("count", phones.size + emails.size)
        }
        return PluginResult.Success(
            "${phones.size + emails.size} match(es) for \"$name\": " +
                (phones + emails).take(4).joinToString("; ") { "${it.displayName} — ${it.value}" },
            data,
        )
    }
}

/** Reads the notifications Sarothi has seen since its service was bound. */
class ReadNotificationsPlugin : Plugin {
    override val name = "read_notifications"
    override val description =
        "List recent notifications Sarothi has seen, newest first, optionally from one app. Works from " +
            "the accessibility service's notification events, so it only covers notifications that " +
            "arrived while Sarothi was running; it cannot read history from before that."
    override val category = PluginCategory.COMMUNICATION
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "package_name" to JsonSchema.Property.Text("Only notifications from this app, e.g. com.whatsapp."),
            "limit" to JsonSchema.Property.Integer("How many to return.", minimum = 1, maximum = 50, default = 15),
        ),
    )

    override suspend fun availability(context: PluginContext): PluginAvailability =
        if (context.screen.availability().accessibilityConnected) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                "Notifications arrive through Sarothi's accessibility service, which is not connected.",
                fixAction = "Turn Sarothi on in Settings → Accessibility.",
            )
        }

    override suspend fun execute(params: JsonObject): PluginResult {
        val limit = params.get("limit")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 50) ?: 15
        val packageName = params.stringOrNull("package_name")?.takeIf { it.isNotBlank() }

        val seen = if (packageName != null) {
            NotificationFeed.fromPackage(packageName, limit)
        } else {
            NotificationFeed.recent(limit)
        }

        if (seen.isEmpty()) {
            return PluginResult.Success(
                summaryForUser = if (packageName != null) {
                    "No notifications from $packageName since Sarothi started watching."
                } else {
                    "No notifications have arrived since Sarothi started watching. Notifications from " +
                        "before that are not visible to it."
                },
                data = Json.obj { addProperty("count", 0) },
            )
        }

        val data = Json.obj {
            add("notifications", Json.arr {
                seen.forEach { notification ->
                    add(Json.obj {
                        addProperty("app", notification.packageName)
                        notification.title?.let { addProperty("title", it) }
                        notification.text?.let { addProperty("text", it) }
                        addProperty("received_at", notification.receivedAtEpochMillis)
                    })
                }
            })
            addProperty("count", seen.size)
        }
        return PluginResult.Success(
            "${seen.size} recent notification(s): " +
                seen.take(3).joinToString("; ") { "${it.packageName}: ${it.title ?: it.text ?: ""}" },
            data,
        )
    }
}
