package com.frobill.sdk

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract

/**
 * Demande de paiement.
 *
 * @property checkoutUrl `checkout_url` renvoyé par VOTRE serveur (`https://pay.frobill.com/pay/<id>`).
 * @property allowedHost hôte accepté pour [checkoutUrl] ; à ne changer que pour des tests.
 */
data class FrobillCheckoutRequest @JvmOverloads constructor(
    val checkoutUrl: String,
    val allowedHost: String = FrobillCore.DEFAULT_HOST,
)

/**
 * Contrat `ActivityResult` : ouvre la page de paiement dans un onglet Chrome
 * Custom Tabs et renvoie un [FrobillResult].
 *
 * ```kotlin
 * private val payer = registerForActivityResult(FrobillCheckoutContract()) { result -> … }
 * payer.launch(FrobillCheckoutRequest(checkoutUrl))
 * ```
 *
 * `launch()` lève une [FrobillInvalidCheckoutUrlException] si l'adresse est refusée.
 * Le statut renvoyé est indicatif : confirmez le paiement auprès de votre serveur.
 */
class FrobillCheckoutContract : ActivityResultContract<FrobillCheckoutRequest, FrobillResult>() {
    override fun createIntent(context: Context, input: FrobillCheckoutRequest): Intent {
        val url = FrobillCore.validateCheckoutUrl(input.checkoutUrl, input.allowedHost)
        return FrobillCheckoutActivity.createIntent(context, url, input.allowedHost)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): FrobillResult =
        FrobillCheckoutActivity.resultFromIntent(resultCode, intent)
}

/** Lanceur renvoyé par [Frobill.registerForCheckout]. */
class FrobillCheckoutLauncher internal constructor(
    private val launcher: ActivityResultLauncher<FrobillCheckoutRequest>,
) {
    /**
     * Ouvre la page de paiement.
     *
     * @throws FrobillInvalidCheckoutUrlException si [checkoutUrl] est refusé.
     */
    @JvmOverloads
    fun launch(checkoutUrl: String, allowedHost: String = FrobillCore.DEFAULT_HOST) {
        launcher.launch(FrobillCheckoutRequest(checkoutUrl, allowedHost))
    }

    /** Libère le lanceur (inutile dans une activité ou un fragment : c'est automatique). */
    fun unregister() = launcher.unregister()
}

/** Point d'entrée à callback du SDK Frobill pour Android. */
object Frobill {
    /**
     * Prépare un paiement et renvoie le lanceur à utiliser au moment de payer.
     *
     * À appeler pendant l'initialisation de l'activité ou du fragment (propriété
     * ou `onCreate`), comme tout `registerForActivityResult`.
     *
     * ```kotlin
     * private val frobill = Frobill.registerForCheckout(this) { result -> … }
     * frobill.launch(checkoutUrl)
     * ```
     */
    @JvmStatic
    fun registerForCheckout(
        caller: ActivityResultCaller,
        callback: ActivityResultCallback<FrobillResult>,
    ): FrobillCheckoutLauncher =
        FrobillCheckoutLauncher(caller.registerForActivityResult(FrobillCheckoutContract(), callback))
}
