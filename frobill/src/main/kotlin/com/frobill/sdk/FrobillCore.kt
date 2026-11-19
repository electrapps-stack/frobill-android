package com.frobill.sdk

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Locale

/**
 * Statut renvoyé à l'application à la fin du parcours de paiement.
 *
 * Ce statut est **indicatif** : confirmez toujours le paiement depuis votre
 * serveur (webhook `payment.succeeded` ou `GET /payment-intents/{id}`).
 */
enum class FrobillStatus(
    /** Valeur textuelle, identique à celle du paramètre `status` du lien de retour. */
    val value: String,
) {
    /** Le paiement a été accepté. */
    SUCCEEDED("succeeded"),

    /** Le paiement a échoué. */
    FAILED("failed"),

    /** Le client a annulé depuis la page de paiement. */
    CANCELED("canceled"),

    /** L'intention de paiement a expiré. */
    EXPIRED("expired"),

    /** Le client a fermé le navigateur lui-même, sans lien de retour. */
    CLOSED("closed"),

    /** Le lien de retour ne contient pas de statut reconnu. */
    UNKNOWN("unknown");

    companion object {
        /** Retrouve un statut à partir de sa valeur textuelle ; [UNKNOWN] si elle est inconnue. */
        @JvmStatic
        fun fromValue(value: String?): FrobillStatus = values().firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/**
 * Résultat d'un parcours de paiement.
 *
 * @property status statut indicatif du paiement.
 * @property intentId identifiant de l'intention de paiement (paramètre `frobill_intent`).
 * @property reference référence du paiement (paramètre `reference`).
 * @property error `null` en temps normal ; [ERROR_BROWSER_UNAVAILABLE] si aucun navigateur
 * n'a pu être ouvert (le statut vaut alors [FrobillStatus.CLOSED]).
 */
data class FrobillResult @JvmOverloads constructor(
    val status: FrobillStatus,
    val intentId: String? = null,
    val reference: String? = null,
    val error: String? = null,
) {
    /** `true` si le statut indicatif est [FrobillStatus.SUCCEEDED]. */
    val isSucceeded: Boolean get() = status == FrobillStatus.SUCCEEDED

    companion object {
        /** Aucun navigateur n'est installé ou activé sur l'appareil. */
        const val ERROR_BROWSER_UNAVAILABLE = "browser_unavailable"
    }
}

/** Le `checkout_url` n'est pas une page de paiement Frobill autorisée. */
class FrobillInvalidCheckoutUrlException(reason: String) :
    IllegalArgumentException("checkout_url refusé : $reason")

/**
 * Logique pure du SDK : aucune dépendance à Android, testable sur la JVM.
 */
object FrobillCore {
    /** Hôte de la page de paiement Frobill en production. */
    const val DEFAULT_HOST = "pay.frobill.com"

    private val CHECKOUT_PATH = Regex("^/pay/[A-Za-z0-9_-]+/?$")
    private val FORBIDDEN_CHARS = Regex("[\\s\\\\\\u0000-\\u001f\\u007f]")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")
    private val FROM_FROBILL = listOf(
        FrobillStatus.SUCCEEDED,
        FrobillStatus.FAILED,
        FrobillStatus.CANCELED,
        FrobillStatus.EXPIRED,
    )

    /**
     * Vérifie que [checkoutUrl] est une page de paiement Frobill et renvoie l'adresse.
     *
     * Conditions : schéma `https`, hôte égal à [allowedHost] (par défaut
     * `pay.frobill.com`), aucun identifiant dans l'adresse, chemin `/pay/<id>`.
     * [allowedHost] peut contenir un port (`localhost:8443`) pour les tests.
     *
     * @throws FrobillInvalidCheckoutUrlException si l'adresse est refusée.
     */
    @JvmStatic
    @JvmOverloads
    fun validateCheckoutUrl(checkoutUrl: String?, allowedHost: String = DEFAULT_HOST): String {
        if (checkoutUrl.isNullOrEmpty()) throw FrobillInvalidCheckoutUrlException("adresse vide.")
        if (FORBIDDEN_CHARS.containsMatchIn(checkoutUrl)) {
            throw FrobillInvalidCheckoutUrlException(
                "caractères interdits (espace, barre oblique inversée ou caractère de contrôle).",
            )
        }
        val uri = try {
            URI(checkoutUrl)
        } catch (e: URISyntaxException) {
            throw FrobillInvalidCheckoutUrlException("adresse illisible.")
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) {
            throw FrobillInvalidCheckoutUrlException("le schéma doit être https.")
        }
        if (uri.rawUserInfo != null || uri.rawAuthority?.contains('@') == true) {
            throw FrobillInvalidCheckoutUrlException("l'adresse ne doit pas contenir d'identifiants.")
        }
        // `host` est nul quand l'autorité n'est pas un nom d'hôte valide.
        val host = uri.host?.lowercase(Locale.ROOT)
        if (host.isNullOrEmpty()) throw FrobillInvalidCheckoutUrlException("hôte manquant ou invalide.")
        val hostAndPort = if (uri.port != -1 && uri.port != 443) "$host:${uri.port}" else host
        if (hostAndPort != allowedHost.trim().lowercase(Locale.ROOT)) {
            throw FrobillInvalidCheckoutUrlException(
                "l'hôte « $hostAndPort » n'est pas autorisé (attendu : « $allowedHost »).",
            )
        }
        // `rawPath` : le chemin est comparé tel qu'il est écrit, sans décodage.
        if (!CHECKOUT_PATH.matches(uri.rawPath.orEmpty())) {
            throw FrobillInvalidCheckoutUrlException("le chemin doit être de la forme /pay/<id>.")
        }
        return checkoutUrl
    }

    /**
     * Transforme le lien de retour
     * `monapp://frobill/retour?frobill_intent=<id>&status=<statut>&reference=<réf>`
     * en [FrobillResult].
     *
     * Un statut absent ou inconnu donne [FrobillStatus.UNKNOWN] : interrogez alors
     * votre serveur. Si [expectedScheme] est fourni et que le lien utilise un autre
     * schéma, le résultat est aussi [FrobillStatus.UNKNOWN], sans identifiant.
     */
    @JvmStatic
    @JvmOverloads
    fun parseReturnUrl(returnUrl: String?, expectedScheme: String? = null): FrobillResult {
        if (returnUrl.isNullOrEmpty()) return FrobillResult(FrobillStatus.UNKNOWN)
        val scheme = returnUrl.substringBefore(':', missingDelimiterValue = "")
        if (!SCHEME.matches(scheme)) return FrobillResult(FrobillStatus.UNKNOWN)
        if (expectedScheme != null && !scheme.equals(expectedScheme, ignoreCase = true)) {
            return FrobillResult(FrobillStatus.UNKNOWN)
        }
        val query = returnUrl.substringBefore('#').substringAfter('?', missingDelimiterValue = "")
        val params = parseQuery(query)
        val rawStatus = params["status"]
        val status = FROM_FROBILL.firstOrNull { it.value == rawStatus } ?: FrobillStatus.UNKNOWN
        return FrobillResult(
            status = status,
            intentId = params["frobill_intent"],
            reference = params["reference"],
        )
    }

    /** Paramètres décodés, vides exclus ; la première occurrence l'emporte. */
    private fun parseQuery(query: String): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val index = pair.indexOf('=')
            val rawKey = if (index == -1) pair else pair.substring(0, index)
            val rawValue = if (index == -1) "" else pair.substring(index + 1)
            try {
                val key = URLDecoder.decode(rawKey, "UTF-8")
                val value = URLDecoder.decode(rawValue, "UTF-8").trim()
                if (value.isNotEmpty() && !params.containsKey(key)) params[key] = value
            } catch (e: IllegalArgumentException) {
                // Encodage invalide : paramètre ignoré.
            }
        }
        return params
    }
}
