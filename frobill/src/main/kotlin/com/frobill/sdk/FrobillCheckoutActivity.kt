package com.frobill.sdk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Activité interne, transparente, qui encadre le paiement :
 *
 * 1. premier `onResume` : ouvre la page de paiement dans un onglet Custom Tabs ;
 * 2. [FrobillRedirectActivity] reçoit le lien de retour et le lui transmet
 *    (`onNewIntent`), ce qui ferme l'onglet ;
 * 3. `onResume` suivant : s'il y a un lien de retour, il est analysé ; sinon le
 *    client a fermé l'onglet lui-même → statut `closed`.
 *
 * N'utilisez pas cette activité directement : passez par [FrobillCheckoutContract]
 * ou [Frobill.registerForCheckout].
 */
class FrobillCheckoutActivity : Activity() {
    private var browserStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browserStarted = savedInstanceState?.getBoolean(STATE_BROWSER_STARTED) ?: false
        if (savedInstanceState == null && intent?.hasExtra(EXTRA_CHECKOUT_URL) != true) {
            // Lien de retour reçu alors qu'aucun paiement n'est en cours : rien à faire.
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (!browserStarted) {
            browserStarted = true
            openBrowser()
            return
        }
        val returnUri = intent?.data
        if (returnUri != null) {
            finishWith(FrobillCore.parseReturnUrl(returnUri.toString()))
        } else {
            finishWith(FrobillResult(FrobillStatus.CLOSED))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_BROWSER_STARTED, browserStarted)
    }

    private fun openBrowser() {
        val url = try {
            // Seconde validation, au plus près de l'ouverture du navigateur.
            FrobillCore.validateCheckoutUrl(
                intent?.getStringExtra(EXTRA_CHECKOUT_URL),
                intent?.getStringExtra(EXTRA_ALLOWED_HOST) ?: FrobillCore.DEFAULT_HOST,
            )
        } catch (e: FrobillInvalidCheckoutUrlException) {
            finishWith(FrobillResult(FrobillStatus.CLOSED))
            return
        }
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
                .launchUrl(this, Uri.parse(url))
        } catch (e: ActivityNotFoundException) {
            finishWith(FrobillResult(FrobillStatus.CLOSED, error = FrobillResult.ERROR_BROWSER_UNAVAILABLE))
        }
    }

    private fun finishWith(result: FrobillResult) {
        val data = Intent()
            .putExtra(RESULT_STATUS, result.status.value)
            .putExtra(RESULT_INTENT_ID, result.intentId)
            .putExtra(RESULT_REFERENCE, result.reference)
            .putExtra(RESULT_ERROR, result.error)
        setResult(RESULT_OK, data)
        finish()
    }

    internal companion object {
        private const val EXTRA_CHECKOUT_URL = "com.frobill.sdk.extra.CHECKOUT_URL"
        private const val EXTRA_ALLOWED_HOST = "com.frobill.sdk.extra.ALLOWED_HOST"
        private const val STATE_BROWSER_STARTED = "com.frobill.sdk.state.BROWSER_STARTED"
        private const val RESULT_STATUS = "com.frobill.sdk.result.STATUS"
        private const val RESULT_INTENT_ID = "com.frobill.sdk.result.INTENT_ID"
        private const val RESULT_REFERENCE = "com.frobill.sdk.result.REFERENCE"
        private const val RESULT_ERROR = "com.frobill.sdk.result.ERROR"

        /** Intention qui démarre un paiement. */
        fun createIntent(context: Context, checkoutUrl: String, allowedHost: String): Intent =
            Intent(context, FrobillCheckoutActivity::class.java)
                .putExtra(EXTRA_CHECKOUT_URL, checkoutUrl)
                .putExtra(EXTRA_ALLOWED_HOST, allowedHost)

        /** Intention qui transmet le lien de retour au paiement en cours et ferme l'onglet. */
        fun createReturnIntent(context: Context, returnUri: Uri?): Intent =
            Intent(context, FrobillCheckoutActivity::class.java)
                .setData(returnUri)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** Reconstruit le résultat renvoyé par l'activité. */
        fun resultFromIntent(resultCode: Int, data: Intent?): FrobillResult {
            if (resultCode != RESULT_OK || data == null) return FrobillResult(FrobillStatus.CLOSED)
            return FrobillResult(
                status = FrobillStatus.fromValue(data.getStringExtra(RESULT_STATUS)),
                intentId = data.getStringExtra(RESULT_INTENT_ID),
                reference = data.getStringExtra(RESULT_REFERENCE),
                error = data.getStringExtra(RESULT_ERROR),
            )
        }
    }
}
