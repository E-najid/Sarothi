package com.ngi.sarothi.app.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.ngi.sarothi.app.di.AppGraph
import com.ngi.sarothi.core.crypto.BiometricKeyVault

/**
 * Two settings that are about the device rather than the data.
 *
 * Both are stored on the phone and not in the vault: a data plan and a fingerprint belong
 * to this handset, and following the SD card to another one would either spend someone's
 * allowance without asking or offer an unlock that cannot work.
 */
@Composable
fun SettingsScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var mobileData by remember { mutableStateOf(graph.allowMobileData) }
    var biometricTick by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }

    val availability = remember(biometricTick) { graph.biometrics.availability() }
    val hasCachedKey = remember(biometricTick) { graph.biometrics.hasCachedKey() }
    val unlocked = graph.vault.isUnlocked

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Download models over mobile data", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Off by default. The models are 60-220 MB each and a download that " +
                            "uses your data allowance without asking is not a reasonable " +
                            "default. Downloads pause and resume, so turning this on later " +
                            "continues from the bytes already on disk.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = mobileData,
                    onCheckedChange = { next ->
                        mobileData = next
                        graph.allowMobileData = next
                    },
                )
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Biometric unlock", style = MaterialTheme.typography.titleSmall)
                Text(
                    "A convenience, never a second way in. Your fingerprint or face unwraps " +
                        "the same key your passphrase derives, using a Keystore key that " +
                        "cannot leave this device. Nothing biometric is written to the SD " +
                        "card, and the passphrase still opens the vault with no biometrics " +
                        "involved.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    when (availability) {
                        BiometricKeyVault.Availability.AVAILABLE ->
                            if (hasCachedKey) "Enabled on this device." else "Hardware available; not enabled yet."
                        BiometricKeyVault.Availability.NOT_ENROLLED ->
                            "No fingerprint or face is enrolled in Android's own settings."
                        BiometricKeyVault.Availability.NO_HARDWARE ->
                            "This phone has no biometric hardware Sarothi can use."
                        BiometricKeyVault.Availability.UNKNOWN ->
                            "Biometric hardware is busy or unavailable right now."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )

                if (availability == BiometricKeyVault.Availability.AVAILABLE) {
                    if (!hasCachedKey) {
                        Button(
                            enabled = unlocked,
                            onClick = {
                                enableBiometrics(graph, context as FragmentActivity) { message ->
                                    note = message
                                    biometricTick++
                                }
                            },
                        ) { Text(if (unlocked) "Enable biometric unlock" else "Unlock the vault first") }
                    } else {
                        if (!unlocked) {
                            Button(
                                onClick = {
                                    unlockWithBiometrics(graph, context as FragmentActivity) { message ->
                                        note = message
                                        biometricTick++
                                    }
                                },
                            ) { Text("Unlock with biometrics") }
                        }
                        OutlinedButton(
                            onClick = {
                                graph.biometrics.invalidate()
                                note = "Removed. The wrapped key is gone; the passphrase is unaffected."
                                biometricTick++
                            },
                        ) { Text("Remove biometric unlock") }
                    }
                }

                note?.let { text -> Text(text, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/**
 * Wraps the key the vault is currently unlocked with, so a later unlock can recover it
 * without the passphrase. Requires an unlocked vault because there is nothing to wrap
 * otherwise -- and taking a key from anywhere but the open vault would mean storing
 * something the user never handed over.
 *
 * The callback captures [graph] and reaches the key through it at the moment of success.
 * `requireKey()` hands back the vault's own live array, so nothing here copies it, holds
 * it in a field, or zeroes it: zeroing would destroy the key of a vault that is still
 * open. There is deliberately no state at file scope for the same reason.
 */
private fun enableBiometrics(
    graph: AppGraph,
    activity: FragmentActivity,
    onResult: (String) -> Unit,
) {
    val cryptoObject = graph.biometrics.encryptCryptoObject()
    if (cryptoObject == null) {
        onResult("Could not prepare a Keystore cipher for biometric use.")
        return
    }
    if (!graph.vault.isUnlocked) {
        onResult("The vault is locked, so there is no key to wrap.")
        return
    }
    val prompt = BiometricPrompt(
        activity,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val wrapped = runCatching {
                    graph.biometrics.wrap(result.cryptoObject, graph.vault.requireKey())
                }.getOrDefault(false)
                onResult(
                    if (wrapped) {
                        "Biometric unlock enabled. Your passphrase is unchanged and still works."
                    } else {
                        "Could not wrap the key, so biometric unlock was not enabled."
                    },
                )
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult("Enabling did not complete: $errString")
            }

            override fun onAuthenticationFailed() {
                onResult("Not recognised. Try again.")
            }
        },
    )
    prompt.authenticate(promptInfo("Enable biometric unlock", "Confirm to wrap Sarothi's key."), cryptoObject)
}

/**
 * Unwraps the cached key after a successful biometric authentication and opens the vault
 * with it. The unwrap happens inside the prompt's success callback, so a failed
 * authentication cannot produce a key.
 */
private fun unlockWithBiometrics(
    graph: AppGraph,
    activity: FragmentActivity,
    onResult: (String) -> Unit,
) {
    val cryptoObject = graph.biometrics.unlockCryptoObject()
    if (cryptoObject == null) {
        onResult("No wrapped key on this device. Enable biometric unlock first, with the vault open.")
        return
    }
    val prompt = BiometricPrompt(
        activity,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val key = graph.biometrics.unwrap(result.cryptoObject)
                if (key == null) {
                    onResult("Authentication succeeded but the wrapped key could not be read.")
                    return
                }
                onResult(
                    runCatching { graph.vault.unlockWithKey(key) }
                        .fold(
                            onSuccess = {
                                graph.persona.refresh()
                                "Vault unlocked."
                            },
                            onFailure = { "${it.javaClass.simpleName}: ${it.message}" },
                        ),
                )
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult("Biometric unlock did not complete: $errString")
            }

            override fun onAuthenticationFailed() {
                onResult("Not recognised. Try again, or use your passphrase.")
            }
        },
    )
    prompt.authenticate(promptInfo("Unlock Sarothi", "Your passphrase still works, and always will."), cryptoObject)
}

private fun promptInfo(title: String, subtitle: String): BiometricPrompt.PromptInfo =
    BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        // BIOMETRIC_STRONG is required to authenticate a CryptoObject at all, and is what
        // makes the wrapped key meaningful: a weak unlock protecting a master key would be
        // the convenience layer pretending to be security.
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText("Use passphrase")
        .build()
