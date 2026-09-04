package com.ngi.sarothi.plugins.shopping

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.google.gson.JsonObject
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
import com.ngi.sarothi.plugins.common.Digits
import com.ngi.sarothi.plugins.common.LaunchOutcome
import com.ngi.sarothi.plugins.common.doubleOrAsk
import com.ngi.sarothi.plugins.common.launchForResult
import com.ngi.sarothi.plugins.common.textOrAsk

/**
 * Pays through a UPI app.
 *
 * Sarothi does not have — and deliberately does not try to have — a payment
 * credential of its own. What it can do is build a correct `upi://pay` request and
 * hand it to whichever UPI app the user has, which then asks for the user's own
 * PIN. That keeps the money-moving step inside the app the user already trusts,
 * with their own authentication, and leaves Sarothi holding nothing it could leak.
 *
 * Every parameter is confirmed before the intent is fired, and the amount is never
 * inferred: a missing amount pauses the task and asks.
 */
class UpiPaymentPlugin : Plugin {
    override val name = "upi_payment"
    override val description =
        "Start a UPI payment by handing a upi://pay request to the user's own payment app, which then " +
            "asks for their PIN. Sarothi stores no payment credentials and cannot complete a payment " +
            "itself. The payee id, amount and note are always shown for confirmation first. Needs a " +
            "UPI app installed; in Bangladesh that usually means a bank app or Google Pay."
    override val category = PluginCategory.SHOPPING
    override val sensitivity = Sensitivity.CRITICAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "payee_vpa" to JsonSchema.Property.Text("The payee's UPI id, e.g. 'shop@okhdfcbank'. Never guess one."),
            "payee_name" to JsonSchema.Property.Text("The payee's name as it should appear."),
            "amount" to JsonSchema.Property.Number("Amount in rupees, e.g. 249.50. Never inferred.", minimum = 1.0, maximum = 100000.0),
            "note" to JsonSchema.Property.Text("A short note for the payee.", maxLength = 80),
            "transaction_ref" to JsonSchema.Property.Text("Optional reference, e.g. an order id.", maxLength = 40),
            "currency" to JsonSchema.Property.Text("Only INR is supported by UPI.", enum = listOf("INR"), default = "INR"),
        ),
        required = listOf("payee_vpa", "amount"),
    )

    override val example = """{"payee_vpa":"shop@okhdfcbank","payee_name":"Corner Shop","amount":249.50,"note":"Order 8812"}"""

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val handler = resolveUpiApp(context.appContext.packageManager)
        return if (handler != null) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                reason = "No app on this phone can handle a upi://pay request, so Sarothi cannot start " +
                    "a UPI payment.",
                fixAction = "Install a UPI app (a bank app or Google Pay). For bKash or Nagad, use " +
                    "open_payment_app instead — those are not UPI.",
            )
        }
    }

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview {
        val payee = params.stringOrNull("payee_vpa") ?: "(no payee given)"
        val name = params.stringOrNull("payee_name")
        val amount = params.get("amount")?.takeIf { it.isJsonPrimitive }?.asDouble
        val note = params.stringOrNull("note")
        val reference = params.stringOrNull("transaction_ref")
        return ConfirmationPreview(
            title = "Start this payment?",
            detailLines = buildList {
                add("Payee: $payee" + if (name != null) " ($name)" else "")
                add(
                    "Amount: " + if (amount == null) {
                        "(not given — Sarothi will stop and ask rather than choose one)"
                    } else {
                        "₹" + "%.2f".format(amount)
                    },
                )
                note?.let { add("Note: $it") }
                reference?.let { add("Reference: $it") }
                add(
                    "Sarothi will open your payment app with these details filled in. You still enter " +
                        "your own PIN there; nothing is sent until you do.",
                )
            },
            reason = ConfirmationReason.PAYMENT,
            allowRemember = false,
        )
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val payeeVpa = params.textOrAsk(
            "payee_vpa",
            "What is the payee's UPI id (the address like name@bank)? Sarothi will not guess one.",
        )
        val amount = params.doubleOrAsk(
            "amount",
            "How much should be paid, exactly? Sarothi will not choose an amount.",
        )
        val payeeName = params.stringOrNull("payee_name")?.takeIf { it.isNotBlank() }
        val note = params.stringOrNull("note")?.takeIf { it.isNotBlank() }?.take(80)
        val reference = params.stringOrNull("transaction_ref")?.takeIf { it.isNotBlank() }?.take(40)
        val currency = params.stringOrNull("currency")?.takeIf { it.isNotBlank() } ?: "INR"

        if (!payeeVpa.contains('@')) {
            return PluginResult.Failure(
                summaryForUser = "\"$payeeVpa\" does not look like a UPI id — those always contain an " +
                    "@, like name@bank. Sarothi will not send money to a guess.",
                errorClass = "InvalidPayeeException",
                retriable = true,
            )
        }
        if (amount <= 0.0) {
            return PluginResult.Failure(
                "The amount has to be greater than zero.", "InvalidAmountException", retriable = true,
            )
        }
        if (currency != "INR") {
            return PluginResult.Unavailable(
                PluginAvailability.unavailable(
                    reason = "UPI only moves Indian rupees. '$currency' is not something a upi://pay " +
                        "request can carry.",
                    fixAction = "For Bangladeshi taka use open_payment_app with bKash or Nagad, which " +
                        "Sarothi opens but cannot fill in.",
                ),
            )
        }

        val uri = Uri.parse(buildString {
            append("upi://pay?pa=").append(Uri.encode(payeeVpa))
            append("&cu=INR")
            append("&am=").append("%.2f".format(amount))
            payeeName?.let { append("&pn=").append(Uri.encode(it)) }
            note?.let { append("&tn=").append(Uri.encode(it)) }
            reference?.let { append("&tr=").append(Uri.encode(it)) }
        })
        val intent = Intent(Intent.ACTION_VIEW, uri)
        resolveUpiApp(context.appContext.packageManager)?.let { intent.setPackage(it) }

        return when (val outcome = context.appContext.launchForResult(intent)) {
            LaunchOutcome.Started -> PluginResult.Success(
                summaryForUser = "Opened your UPI app with a payment of ₹${"%.2f".format(amount)} to " +
                    "$payeeVpa" + (payeeName?.let { " ($it)" } ?: "") + ". Nothing is paid until you " +
                    "enter your PIN there.",
                data = Json.obj {
                    addProperty("payee_vpa", payeeVpa)
                    addProperty("payee_name", payeeName ?: "")
                    addProperty("amount", amount)
                    addProperty("currency", "INR")
                    note?.let { addProperty("note", it) }
                    reference?.let { addProperty("reference", it) }
                    addProperty("completed", false)
                    addProperty("needs_user_pin", true)
                },
                spoken = "পেমেন্ট অ্যাপ খুলে দিয়েছি। পিন দিলে টাকা যাবে।",
            )
            is LaunchOutcome.NoHandler -> PluginResult.Failure(
                summaryForUser = outcome.reason,
                errorClass = "ActivityNotFoundException",
                retriable = false,
            )
            is LaunchOutcome.Refused -> PluginResult.Failure(outcome.reason, "SecurityException", retriable = false)
        }
    }

    private fun resolveUpiApp(manager: PackageManager): String? {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay?pa=probe@upi&cu=INR&am=1"))
        val handlers = runCatching { manager.queryIntentActivities(probe, 0) }.getOrDefault(emptyList())
        // Prefer an app the user has actually installed and used over a generic
        // resolver; the first entry Android returns is already ordered that way.
        return handlers.firstOrNull()?.activityInfo?.packageName
    }
}

/**
 * Opens a mobile financial service app.
 *
 * bKash, Nagad, Rocket and Upay are not UPI and expose no public deep link that
 * fills in an amount, so all Sarothi can honestly do is open the app to the right
 * place and tell the user what to type. It says that plainly instead of pretending
 * the amount was entered.
 */
class OpenPaymentAppPlugin : Plugin {
    override val name = "open_payment_app"
    override val description =
        "Open a mobile financial service app — bKash, Nagad, Rocket or Upay — for send money, cash out, " +
            "add money or bill pay. Sarothi opens the app and tells the user what to enter; these apps " +
            "expose no public link that fills in an amount, so nothing is typed for them. If the app is " +
            "not installed it says so."
    override val category = PluginCategory.SHOPPING
    override val sensitivity = Sensitivity.SENSITIVE

    override val parameters = JsonSchema(
        properties = mapOf(
            "provider" to JsonSchema.Property.Text("Which service.", enum = PROVIDERS.keys.toList()),
            "action" to JsonSchema.Property.Text(
                "What the user wants to do, so Sarothi can tell them which screen to pick.",
                enum = listOf("send_money", "cash_out", "add_money", "bill_pay", "balance", "open"),
                default = "open",
            ),
            "amount" to JsonSchema.Property.Number("Amount to mention in the instructions. Never entered automatically.", minimum = 1.0),
            "recipient" to JsonSchema.Property.Text("Account or number to mention in the instructions."),
        ),
        required = listOf("provider"),
    )

    override val example = """{"provider":"bkash","action":"send_money","amount":500,"recipient":"01712345678"}"""

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview {
        val provider = params.stringOrNull("provider") ?: "(unspecified)"
        val action = params.stringOrNull("action") ?: "open"
        val amount = params.get("amount")?.takeIf { it.isJsonPrimitive }?.asDouble
        val recipient = params.stringOrNull("recipient")
        return ConfirmationPreview(
            title = "Open $provider?",
            detailLines = buildList {
                add("Service: $provider")
                add("Screen to use: ${action.replace('_', ' ')}")
                recipient?.let { add("Recipient: $it") }
                amount?.let { add("Amount: ৳" + Digits.toBangla("%.2f".format(it))) }
                add(
                    "Sarothi opens the app only. You enter the amount and your PIN yourself — it cannot " +
                        "and will not type into $provider.",
                )
            },
            reason = ConfirmationReason.PAYMENT,
            allowRemember = false,
        )
    }

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val manager = context.appContext.packageManager
        val installed = PROVIDERS.values.filter { provider ->
            manager.getLaunchIntentForPackage(provider.packageName) != null ||
                provider.alternativePackages.any { manager.getLaunchIntentForPackage(it) != null }
        }
        return if (installed.isNotEmpty()) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                reason = "None of bKash, Nagad, Rocket or Upay is installed on this phone.",
                fixAction = "Install one of them, or use upi_payment if a UPI app is present.",
            )
        }
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val providerName = params.textOrAsk("provider", "Which service — bKash, Nagad, Rocket or Upay?")
            .trim().lowercase()
        val provider = PROVIDERS[providerName]
            ?: return PluginResult.Failure(
                summaryForUser = "\"$providerName\" is not a service Sarothi knows. It can open: " +
                    PROVIDERS.keys.joinToString(),
                errorClass = "UnknownProviderException",
                retriable = true,
            )
        val action = params.stringOrNull("action") ?: "open"
        val amount = params.get("amount")?.takeIf { it.isJsonPrimitive }?.asDouble
        val recipient = params.stringOrNull("recipient")?.takeIf { it.isNotBlank() }
            ?.let { Digits.toWestern(it) }

        val manager = context.appContext.packageManager
        val candidates = listOf(provider.packageName) + provider.alternativePackages
        val installedPackage = candidates.firstOrNull { manager.getLaunchIntentForPackage(it) != null }
        if (installedPackage == null) {
            return PluginResult.Unavailable(
                PluginAvailability.unavailable(
                    reason = "${provider.displayName} is not installed on this phone.",
                    fixAction = "Install ${provider.displayName} from the Play Store, or use a different service.",
                ),
            )
        }

        val deepLink = provider.deepLinks[action]
        val intent = if (deepLink != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).setPackage(installedPackage)
        } else {
            manager.getLaunchIntentForPackage(installedPackage)
                ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(installedPackage)
        }

        val deepLinkWorked = if (deepLink != null) {
            context.appContext.launchForResult(intent) == LaunchOutcome.Started
        } else {
            false
        }
        val launchIntent = if (deepLinkWorked) {
            intent
        } else {
            manager.getLaunchIntentForPackage(installedPackage)
                ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(installedPackage)
        }
        if (!deepLinkWorked) {
            val outcome = context.appContext.launchForResult(launchIntent)
            if (outcome != LaunchOutcome.Started) {
                val reason = when (outcome) {
                    is LaunchOutcome.NoHandler -> outcome.reason
                    is LaunchOutcome.Refused -> outcome.reason
                    LaunchOutcome.Started -> "Unknown failure"
                }
                return PluginResult.Failure(
                    summaryForUser = "${provider.displayName} is installed but would not open: $reason",
                    errorClass = "AppLaunchFailedException",
                    retriable = false,
                )
            }
        }

        val instructions = buildString {
            append("In ").append(provider.displayName).append(", go to ")
            append(provider.actionLabels[action] ?: "the main menu")
            append('.')
            recipient?.let { append(" Recipient: ").append(Digits.toBangla(it)).append(" (").append(it).append(").") }
            amount?.let { append(" Amount: ৳").append(Digits.toBangla("%.2f".format(it))).append('.') }
            append(" Then confirm with your own PIN. Sarothi did not type any of this into the app.")
        }

        return PluginResult.Success(
            summaryForUser = "Opened ${provider.displayName}. $instructions",
            data = Json.obj {
                addProperty("provider", providerName)
                addProperty("package", installedPackage)
                addProperty("action", action)
                addProperty("deep_link_used", deepLinkWorked)
                recipient?.let { addProperty("recipient", it) }
                amount?.let { addProperty("amount", it) }
                addProperty("instructions", instructions)
                addProperty("completed", false)
            },
            spoken = "${provider.displayName} খুলে দিয়েছি। পিন দিয়ে নিশ্চিত করুন।",
        )
    }

    private class Provider(
        val displayName: String,
        val packageName: String,
        val alternativePackages: List<String>,
        val deepLinks: Map<String, String>,
        val actionLabels: Map<String, String>,
    )

    private companion object {
        /**
         * Package names and deep links.
         *
         * The deep links here are the ones the providers publish; where a provider
         * publishes none, the map is empty and Sarothi opens the app's launcher
         * activity and says so in `deep_link_used=false`. It never claims to have
         * navigated somewhere it did not.
         */
        val PROVIDERS = mapOf(
            "bkash" to Provider(
                displayName = "bKash",
                packageName = "com.bKash.customerapp",
                alternativePackages = listOf("com.bkash.customerapp"),
                deepLinks = emptyMap(),
                actionLabels = mapOf(
                    "send_money" to "Send Money",
                    "cash_out" to "Cash Out",
                    "add_money" to "Add Money",
                    "bill_pay" to "Payment",
                    "balance" to "My bKash (balance)",
                    "open" to "the main menu",
                ),
            ),
            "nagad" to Provider(
                displayName = "Nagad",
                packageName = "com.kona.account",
                alternativePackages = listOf("com.nagad.app"),
                deepLinks = emptyMap(),
                actionLabels = mapOf(
                    "send_money" to "Send Money",
                    "cash_out" to "Cash Out",
                    "add_money" to "Add Money",
                    "bill_pay" to "Bill Pay",
                    "balance" to "the balance screen",
                    "open" to "the main menu",
                ),
            ),
            "rocket" to Provider(
                displayName = "Rocket",
                packageName = "com.dutchbanglabank.rocket",
                alternativePackages = listOf("com.dbbl.mbs.apps.main"),
                deepLinks = emptyMap(),
                actionLabels = mapOf(
                    "send_money" to "Send Money",
                    "cash_out" to "Cash Out",
                    "add_money" to "Deposit",
                    "bill_pay" to "Bill Pay",
                    "balance" to "Balance",
                    "open" to "the main menu",
                ),
            ),
            "upay" to Provider(
                displayName = "Upay",
                packageName = "com.upay.app",
                alternativePackages = listOf("com.bdtg.upay"),
                deepLinks = emptyMap(),
                actionLabels = mapOf(
                    "send_money" to "Send Money",
                    "cash_out" to "Cash Out",
                    "add_money" to "Add Money",
                    "bill_pay" to "Bill Pay",
                    "balance" to "the balance screen",
                    "open" to "the main menu",
                ),
            ),
        )
    }
}

/**
 * Searches for a product.
 *
 * Sarothi has no product database and no marketplace API key, and inventing prices
 * would be the worst possible failure. So this does one of two real things: opens
 * a marketplace app's own search screen with the query filled in, or falls back to
 * a web search over marketplace domains. Both return links for the user to open,
 * never a price Sarothi made up.
 */
class ShoppingSearchPlugin : Plugin {
    override val name = "shopping_search"
    override val description =
        "Look for a product to buy. With open_in_app=true it opens a marketplace app's search with the " +
            "query filled in; otherwise it searches the web for the product on Daraz, Amazon and " +
            "similar sites and returns links. Sarothi has no product database, so it never states a " +
            "price on its own — every price comes from a linked page."
    override val category = PluginCategory.SHOPPING
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "query" to JsonSchema.Property.Text("What to look for, e.g. 'smartphone under 15000 taka'."),
            "marketplace" to JsonSchema.Property.Text(
                "Which app to open.",
                enum = MARKETPLACES.keys.toList(),
                default = "daraz",
            ),
            "open_in_app" to JsonSchema.Property.Flag("Open the marketplace app instead of doing a web search.", default = false),
            "max_price" to JsonSchema.Property.Number("Budget ceiling, mentioned in the search query so results are relevant.", minimum = 0.0),
        ),
        required = listOf("query"),
    )

    override val example = """{"query":"স্যামসাং গ্যালাক্সি এ১৫","open_in_app":true}"""

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val anyApp = MARKETPLACES.values.any { provider ->
            context.appContext.packageManager.getLaunchIntentForPackage(provider.packageName) != null
        }
        return if (anyApp || context.network.isOnline()) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                reason = "No marketplace app is installed and the phone is offline, so there is nothing " +
                    "to search.",
                fixAction = "Install Daraz or turn on a network connection.",
            )
        }
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val query = params.textOrAsk("query", "What should Sarothi look for?")
        val openInApp = params.get("open_in_app")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val marketplaceName = params.stringOrNull("marketplace")?.trim()?.lowercase() ?: "daraz"
        val maxPrice = params.get("max_price")?.takeIf { it.isJsonPrimitive }?.asDouble

        val effectiveQuery = if (maxPrice != null && maxPrice > 0) {
            "$query under ${Digits.toBangla(maxPrice.toInt().toString())} টাকা"
        } else {
            query
        }

        if (openInApp) {
            val marketplace = MARKETPLACES[marketplaceName]
                ?: return PluginResult.Failure(
                    summaryForUser = "\"$marketplaceName\" is not a marketplace Sarothi knows. It can " +
                        "open: ${MARKETPLACES.keys.joinToString()}",
                    errorClass = "UnknownMarketplaceException",
                    retriable = true,
                )
            val manager = context.appContext.packageManager
            if (manager.getLaunchIntentForPackage(marketplace.packageName) == null) {
                return PluginResult.Unavailable(
                    PluginAvailability.unavailable(
                        reason = "${marketplace.displayName} is not installed on this phone.",
                        fixAction = "Install ${marketplace.displayName}, or use open_in_app=false for a web search.",
                    ),
                )
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(marketplace.searchUrl(effectiveQuery)))
                .setPackage(marketplace.packageName)
            val outcome = context.appContext.launchForResult(intent)
            if (outcome != LaunchOutcome.Started) {
                // The search deep link did not resolve; fall back to the launcher so
                // the user is at least in the app, and say that the query was not
                // filled in rather than implying it was.
                val launcher = manager.getLaunchIntentForPackage(marketplace.packageName)
                    ?: return PluginResult.Failure(
                        summaryForUser = "${marketplace.displayName} is installed but would not open.",
                        errorClass = "AppLaunchFailedException",
                        retriable = false,
                    )
                val launched = context.appContext.launchForResult(launcher)
                return if (launched == LaunchOutcome.Started) {
                    PluginResult.Success(
                        summaryForUser = "Opened ${marketplace.displayName}, but its search link did not " +
                            "work, so type this into the search box yourself: \"$effectiveQuery\".",
                        data = Json.obj {
                            addProperty("marketplace", marketplaceName)
                            addProperty("query", effectiveQuery)
                            addProperty("query_filled_in", false)
                            addProperty("search_url", marketplace.searchUrl(effectiveQuery))
                        },
                        spoken = "${marketplace.displayName} খুলে দিয়েছি। সার্চ বক্সে লিখুন।",
                    )
                } else {
                    PluginResult.Failure(
                        summaryForUser = "${marketplace.displayName} would not open.",
                        errorClass = "AppLaunchFailedException",
                        retriable = false,
                    )
                }
            }
            return PluginResult.Success(
                summaryForUser = "Opened ${marketplace.displayName} searching for \"$effectiveQuery\". " +
                    "Prices shown there are the marketplace's, not Sarothi's.",
                data = Json.obj {
                    addProperty("marketplace", marketplaceName)
                    addProperty("query", effectiveQuery)
                    addProperty("query_filled_in", true)
                    addProperty("search_url", marketplace.searchUrl(effectiveQuery))
                },
                spoken = "${marketplace.displayName}-এ খুঁজে দিয়েছি।",
            )
        }

        if (!context.network.isOnline()) {
            return PluginResult.Unavailable(
                PluginAvailability.unavailable(
                    reason = "A web product search needs an internet connection and this phone is offline.",
                    fixAction = "Turn on Wi-Fi or mobile data, or install a marketplace app.",
                ),
            )
        }

        val searches = MARKETPLACES.values.filter { it.webSearchDomain != null }.take(4)
        val data = Json.obj {
            addProperty("query", effectiveQuery)
            add("search_links", Json.arr {
                searches.forEach { marketplace ->
                    add(Json.obj {
                        addProperty("marketplace", marketplace.displayName)
                        addProperty("url", marketplace.webSearch(effectiveQuery))
                    })
                }
                add(Json.obj {
                    addProperty("marketplace", "DuckDuckGo")
                    addProperty("url", "https://duckduckgo.com/?q=" + Uri.encode("$effectiveQuery price"))
                })
            })
            addProperty("note", "Sarothi has no product database. Open a link to see current prices; " +
                "it will not state one from memory.")
        }
        return PluginResult.Success(
            summaryForUser = "Sarothi cannot quote prices itself. Here are ${searches.size + 1} search " +
                "links for \"$effectiveQuery\" — open one to see what it costs now.",
            data = data,
        )
    }

    private class Marketplace(
        val displayName: String,
        val packageName: String,
        val webSearchDomain: String?,
        private val searchTemplate: String?,
    ) {
        fun searchUrl(query: String): String =
            searchTemplate?.replace("{q}", Uri.encode(query)) ?: "https://duckduckgo.com/?q=${Uri.encode(query)}"

        fun webSearch(query: String): String =
            if (webSearchDomain != null) {
                "https://duckduckgo.com/?q=${Uri.encode(query)}+site%3A${Uri.encode(webSearchDomain)}"
            } else {
                "https://duckduckgo.com/?q=${Uri.encode(query)}"
            }
    }

    private companion object {
        val MARKETPLACES = mapOf(
            "daraz" to Marketplace(
                displayName = "Daraz",
                packageName = "com.daraz.android",
                webSearchDomain = "daraz.com.bd",
                searchTemplate = "https://www.daraz.com.bd/catalog/?q={q}",
            ),
            "amazon" to Marketplace(
                displayName = "Amazon",
                packageName = "in.amazon.mShop.android.shopping",
                webSearchDomain = "amazon.in",
                searchTemplate = "https://www.amazon.in/s?k={q}",
            ),
            "flipkart" to Marketplace(
                displayName = "Flipkart",
                packageName = "com.flipkart.android",
                webSearchDomain = "flipkart.com",
                searchTemplate = "https://www.flipkart.com/search?q={q}",
            ),
            "evaly" to Marketplace(
                displayName = "Evaly",
                packageName = "com.evaly.evalyshop",
                webSearchDomain = "evaly.com.bd",
                searchTemplate = "https://evaly.com.bd/search?q={q}",
            ),
        )
    }
}

/**
 * Tracks a parcel.
 *
 * Real tracking needs each courier's API credentials, which Sarothi does not ship
 * with and cannot invent. What it does have is the courier's own public tracking
 * page, which it opens with the number filled in — and it says plainly that it
 * cannot read the status itself unless the user then asks it to read the screen.
 */
class TrackOrderPlugin : Plugin {
    override val name = "track_order"
    override val description =
        "Open a courier's tracking page with a tracking number filled in, for Pathao, Steadfast, " +
            "RedX, Sundarban, SA Paribahan or eCourier. Sarothi has no courier API keys, so it cannot " +
            "read the status itself: it opens the page. Ask it to read_screen afterwards if you want " +
            "the status read out."
    override val category = PluginCategory.SHOPPING
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "courier" to JsonSchema.Property.Text("Which courier.", enum = COURIERS.keys.toList()),
            "tracking_number" to JsonSchema.Property.Text("The tracking or consignment number."),
        ),
        required = listOf("courier", "tracking_number"),
    )

    override val example = """{"courier":"pathao","tracking_number":"PAO-1234567"}"""

    override suspend fun availability(context: PluginContext): PluginAvailability =
        if (context.network.isOnline()) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                reason = "Tracking opens a courier's website, and this phone is offline.",
                fixAction = "Turn on Wi-Fi or mobile data.",
            )
        }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val courierName = params.textOrAsk("courier", "Which courier is it — Pathao, Steadfast, RedX, Sundarban, SA Paribahan or eCourier?")
            .trim().lowercase()
        val courier = COURIERS[courierName]
            ?: return PluginResult.Failure(
                summaryForUser = "\"$courierName\" is not a courier Sarothi has a tracking page for. " +
                    "It knows: ${COURIERS.keys.joinToString()}",
                errorClass = "UnknownCourierException",
                retriable = true,
            )
        val tracking = params.textOrAsk("tracking_number", "What is the tracking number?")
            .trim()
        if (tracking.length < 4) {
            return PluginResult.Failure(
                summaryForUser = "\"$tracking\" is too short to be a tracking number, and Sarothi will " +
                    "not pad it out with guesses.",
                errorClass = "InvalidTrackingNumberException",
                retriable = true,
            )
        }

        val url = courier.trackingUrl(tracking)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return when (val outcome = context.appContext.launchForResult(intent)) {
            LaunchOutcome.Started -> PluginResult.Success(
                summaryForUser = "Opened ${courier.displayName} tracking for $tracking. Sarothi cannot " +
                    "read the courier's API, so the status is on that page — ask it to read the screen " +
                    "if you want it read out.",
                data = Json.obj {
                    addProperty("courier", courierName)
                    addProperty("tracking_number", tracking)
                    addProperty("url", url)
                    addProperty("status_read", false)
                    addProperty("reason_status_not_read", "No courier API credentials are bundled with Sarothi.")
                },
                spoken = "ট্র্যাকিং পেজ খুলে দিয়েছি।",
            )
            is LaunchOutcome.NoHandler -> PluginResult.Failure(
                summaryForUser = "No browser on this phone could open $url. ${outcome.reason}",
                errorClass = "ActivityNotFoundException",
                retriable = false,
                data = Json.obj { addProperty("url", url) },
            )
            is LaunchOutcome.Refused -> PluginResult.Failure(outcome.reason, "SecurityException", retriable = false)
        }
    }

    private class Courier(val displayName: String, private val template: String) {
        fun trackingUrl(trackingNumber: String): String =
            template.replace("{n}", Uri.encode(trackingNumber))
    }

    private companion object {
        val COURIERS = mapOf(
            "pathao" to Courier("Pathao", "https://pathao.com/parcel/tracking/?consignment_id={n}"),
            "steadfast" to Courier("Steadfast", "https://steadfast.com.bd/tracking/{n}"),
            "redx" to Courier("RedX", "https://redx.com.bd/tracking/?invoice_id={n}"),
            "sundarban" to Courier("Sundarban Courier", "https://www.sundarbancourierltd.com/track/{n}"),
            "sa_paribahan" to Courier("SA Paribahan", "https://www.saparibahan.com/track/{n}"),
            "ecourier" to Courier("eCourier", "https://www.ecourier.com.bd/tracking/{n}"),
        )
    }
}
