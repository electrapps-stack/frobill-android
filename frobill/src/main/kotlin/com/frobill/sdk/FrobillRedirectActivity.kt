package com.frobill.sdk

import android.app.Activity
import android.os.Bundle

/**
 * Reçoit le lien de retour `monapp://frobill/retour?…` envoyé par Frobill.
 *
 * À déclarer dans le manifeste de l'APPLICATION avec le schéma d'URL de
 * l'application (voir le README). Elle transmet le lien à
 * [FrobillCheckoutActivity], ce qui ferme l'onglet du navigateur, puis se termine.
 */
class FrobillRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(FrobillCheckoutActivity.createReturnIntent(this, intent?.data))
        finish()
    }
}
