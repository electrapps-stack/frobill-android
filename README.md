# Frobill pour Android

SDK Android officiel de [Frobill](https://frobill.com), en Kotlin. Il ouvre la page de paiement hébergée dans un onglet Chrome Custom Tabs et renvoie le résultat à votre application.

```kotlin
private val frobill = Frobill.registerForCheckout(this) { result ->
    // result.status, result.intentId, result.reference
}

frobill.launch(checkoutUrl) // checkoutUrl fourni par VOTRE serveur
```

## Principe

**Votre application ne contient jamais la clé secrète Frobill** (`sk_test_…` / `sk_live_…`). Elle reste sur votre serveur.

1. L'application demande à **votre serveur** de créer une intention de paiement. Le serveur appelle l'API Frobill avec la clé secrète, en donnant comme `return_url` et `cancel_url` un lien de l'application : `monapp://frobill/retour`.
2. Le serveur renvoie le `checkout_url` (`https://pay.frobill.com/pay/<id>`) à l'application.
3. Le SDK ouvre ce `checkout_url` dans le navigateur sécurisé du système (iOS : `ASWebAuthenticationSession` ; Android : Chrome Custom Tabs). Ce n'est pas une WebView : l'application ne voit rien de ce que le client saisit.
4. À la fin, Frobill redirige vers `monapp://frobill/retour?frobill_intent=<id>&status=<statut>&reference=<réf>`. Le SDK intercepte ce lien, ferme le navigateur et renvoie le résultat.
5. L'application demande à votre serveur de **confirmer** le paiement. Le statut reçu dans l'application est indicatif : un lien de retour peut être forgé.

### Déclarer le schéma d'URL chez Frobill

Dans le tableau de bord Frobill : **Développeurs → Clés API → Applications mobiles**, ajoutez le schéma de votre application (`monapp`). Sans cela, l'API refuse le `return_url` avec l'erreur `app_scheme_not_allowed`.

Choisissez un schéma propre à votre application (par exemple l'identifiant du paquet, `com.exemple.monapp`) : un schéma trop courant pourrait être déclaré par une autre application installée sur le téléphone.

## Installation

**Depuis GitHub (recommandé)** — clonez le dépôt et incluez le module `frobill` dans votre projet (détails ci-dessous) :

```bash
git clone --branch v1.0.0 https://github.com/electrapps-stack/frobill-android.git
```

La bibliothèque n'est pas encore publiée sur Maven Central. Deux possibilités.

**Comme module de votre projet** — dans `settings.gradle.kts` :

```kotlin
include(":frobill")
project(":frobill").projectDir = file("../sdks/android/frobill")
```

puis dans `app/build.gradle.kts` :

```kotlin
dependencies {
    implementation(project(":frobill"))
}
```

**Par le dépôt Maven local** — dans `sdks/android` :

```sh
./gradlew :frobill:publishToMavenLocal
```

puis, dans votre projet, ajoutez `mavenLocal()` aux dépôts et :

```kotlin
dependencies {
    implementation("com.frobill:frobill-android:1.0.0")
}
```

Prérequis : `minSdk` 21, AndroidX, une activité `ComponentActivity` (`AppCompatActivity`, `FragmentActivity`…) ou un `Fragment`. Le module est compilé avec `compileSdk` 34, Kotlin 1.9.24 et le plugin Android Gradle 8.5.2 ; il dépend de `androidx.browser:browser:1.8.0` et `androidx.activity:activity:1.9.1`.

## Configuration du manifeste

À copier dans le `<application>` du fichier `AndroidManifest.xml` de **votre application**, en remplaçant `monapp` par votre schéma :

```xml
<activity
    android:name="com.frobill.sdk.FrobillRedirectActivity"
    android:exported="true"
    android:theme="@android:style/Theme.Translucent.NoTitleBar">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <!-- Correspond à return_url = monapp://frobill/retour -->
        <data
            android:scheme="monapp"
            android:host="frobill" />
    </intent-filter>
</activity>
```

C'est la seule déclaration nécessaire. L'activité interne `FrobillCheckoutActivity` et le bloc `<queries>` sont apportés par le manifeste de la bibliothèque. Si votre `return_url` n'utilise pas l'hôte `frobill`, adaptez `android:host` (ou retirez-le).

`FrobillRedirectActivity` se contente de transmettre le lien au paiement en cours ; si aucun paiement n'est en cours, le lien est ignoré.

## Côté serveur

Exemple avec Node.js 18+ et Express. Adaptez-le à votre langage : ce sont deux appels HTTP.

```js
import express from 'express';

const app = express();
app.use(express.json());

const FROBILL_API = 'https://pay.frobill.com/api/v1';
const headers = {
  Authorization: `Bearer ${process.env.FROBILL_SECRET_KEY}`, // sk_test_… ou sk_live_…
  'Content-Type': 'application/json',
};

// 1. L'application demande à payer une commande.
app.post('/api/paiements', async (req, res) => {
  const commande = await trouverCommande(req.body.commandeId, req.user); // votre code

  const reponse = await fetch(`${FROBILL_API}/payment-intents`, {
    method: 'POST',
    headers: { ...headers, 'Idempotency-Key': `commande-${commande.id}` },
    body: JSON.stringify({
      amount: commande.montant, // unités mineures : 2500 = 25,00 USD
      currency: 'USD',
      description: `Commande #${commande.id}`,
      reference: `CMD-${commande.id}`,
      return_url: 'monapp://frobill/retour',
      cancel_url: 'monapp://frobill/retour',
    }),
  });
  const intention = await reponse.json();
  if (!reponse.ok) return res.status(502).json({ erreur: intention.error?.code });

  await enregistrerIntention(commande.id, intention.id); // votre code
  res.json({ intentId: intention.id, checkoutUrl: intention.checkout_url });
});

// 2. Après le retour dans l'application : le serveur vérifie lui-même le statut.
app.get('/api/paiements/:intentId', async (req, res) => {
  // Vérifiez que cette intention appartient bien à une commande de l'utilisateur connecté.
  const commande = await trouverCommandeParIntention(req.params.intentId, req.user); // votre code
  if (!commande) return res.sendStatus(404);

  const reponse = await fetch(`${FROBILL_API}/payment-intents/${encodeURIComponent(req.params.intentId)}`, { headers });
  const intention = await reponse.json();
  if (intention.status === 'succeeded') await marquerPayee(commande.id); // votre code
  res.json({ status: intention.status }); // requires_payment, processing, succeeded, failed, canceled, expired
});
```

Pour ne dépendre d'aucun retour de l'application, écoutez aussi le webhook signé `payment.succeeded` : il arrive même si le client ne revient jamais dans l'application.

## Dans l'application

Exemple complet dans une activité, avec les coroutines (`androidx.lifecycle:lifecycle-runtime-ktx`) et `HttpURLConnection`. Remplacez les appels réseau par votre client HTTP habituel.

```kotlin
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.frobill.sdk.Frobill
import com.frobill.sdk.FrobillInvalidCheckoutUrlException
import com.frobill.sdk.FrobillResult
import com.frobill.sdk.FrobillStatus
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val SERVEUR = "https://api.maboutique.exemple"

class PaiementActivity : AppCompatActivity() {
    private var intentId: String? = null

    // À enregistrer pendant l'initialisation de l'activité, pas au moment du clic.
    private val frobill = Frobill.registerForCheckout(this) { result -> surRetour(result) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intentId = savedInstanceState?.getString("intentId")
        // … votre interface ; au clic sur « Payer » : payer("1042")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("intentId", intentId) // l'activité peut être recréée pendant le paiement
    }

    private fun payer(commandeId: String) = lifecycleScope.launch {
        // 1. Votre serveur crée l'intention de paiement.
        val paiement = appeler("POST", "/api/paiements", JSONObject().put("commandeId", commandeId))
        intentId = paiement.getString("intentId")

        // 2. Le SDK ouvre la page de paiement ; le résultat arrive dans surRetour().
        try {
            frobill.launch(paiement.getString("checkoutUrl"))
        } catch (e: FrobillInvalidCheckoutUrlException) {
            Toast.makeText(this@PaiementActivity, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun surRetour(result: FrobillResult) {
        if (result.status == FrobillStatus.CANCELED) return
        if (result.error == FrobillResult.ERROR_BROWSER_UNAVAILABLE) {
            Toast.makeText(this, "Aucun navigateur n'est disponible sur cet appareil.", Toast.LENGTH_LONG).show()
            return
        }
        // 3. Votre serveur confirme. Le statut reçu ici n'est qu'indicatif.
        //    On utilise l'identifiant reçu du serveur à l'étape 1, pas celui du lien de retour.
        val id = intentId ?: return
        lifecycleScope.launch {
            val statut = appeler("GET", "/api/paiements/$id").getString("status")
            val message = if (statut == "succeeded") "Paiement confirmé" else "Paiement non confirmé ($statut)"
            Toast.makeText(this@PaiementActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun appeler(methode: String, chemin: String, corps: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val connexion = URL(SERVEUR + chemin).openConnection() as HttpURLConnection
            try {
                connexion.requestMethod = methode
                connexion.setRequestProperty("Authorization", "Bearer $jetonUtilisateur") // session de VOTRE application
                if (corps != null) {
                    connexion.doOutput = true
                    connexion.setRequestProperty("Content-Type", "application/json")
                    connexion.outputStream.use { it.write(corps.toString().toByteArray()) }
                }
                JSONObject(connexion.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connexion.disconnect()
            }
        }
}
```

(`jetonUtilisateur` est le jeton de session de votre propre application ; ajoutez la gestion d'erreurs réseau qui convient à votre projet.)

Un paiement Mobile Money peut rester `processing` quelques instants : dans ce cas, réinterrogez votre serveur (toutes les 5 secondes, par exemple) ou attendez le webhook.

### Variante : contrat `ActivityResult`

```kotlin
private val payer = registerForActivityResult(FrobillCheckoutContract()) { result: FrobillResult ->
    // …
}

payer.launch(FrobillCheckoutRequest(checkoutUrl))
```

Avec Jetpack Compose :

```kotlin
val payer = rememberLauncherForActivityResult(FrobillCheckoutContract()) { result -> /* … */ }
Button(onClick = { payer.launch(FrobillCheckoutRequest(checkoutUrl)) }) { Text("Payer") }
```

## API

| Élément | Rôle |
| --- | --- |
| `Frobill.registerForCheckout(caller, callback)` | Enregistre le callback et renvoie un `FrobillCheckoutLauncher`. |
| `FrobillCheckoutLauncher.launch(checkoutUrl, allowedHost = "pay.frobill.com")` | Ouvre la page de paiement. |
| `FrobillCheckoutContract` | Contrat `ActivityResultContract<FrobillCheckoutRequest, FrobillResult>`. |
| `FrobillCheckoutRequest(checkoutUrl, allowedHost = "pay.frobill.com")` | Entrée du contrat. |
| `FrobillResult(status, intentId, reference, error)` | Résultat ; `error` vaut `null` ou `"browser_unavailable"`. |
| `FrobillCore.validateCheckoutUrl(url, allowedHost)` / `FrobillCore.parseReturnUrl(url)` | Logique pure, sans dépendance à Android. |

`checkoutUrl` doit être en `https://`, sur l'hôte autorisé, avec un chemin `/pay/<id>`. Sinon `launch()` lève une `FrobillInvalidCheckoutUrlException` et rien n'est ouvert. `allowedHost` n'est à changer que pour des tests (`staging.exemple.test`, `localhost:8443`).

## Statuts renvoyés

`result.status` est un `FrobillStatus` (`SUCCEEDED`, `FAILED`, `CANCELED`, `EXPIRED`, `CLOSED`, `UNKNOWN`) ; `result.status.value` donne le texte ci-dessous.

| Statut | Signification | Que faire |
| --- | --- | --- |
| `succeeded` | Frobill annonce un paiement accepté. | Confirmer auprès de votre serveur, puis afficher le succès. |
| `failed` | Le paiement a échoué. | Proposer de réessayer. |
| `canceled` | Le client a annulé depuis la page de paiement. | Revenir au panier. |
| `expired` | L'intention a expiré (60 minutes par défaut). | Créer une nouvelle intention. |
| `closed` | Le client a fermé le navigateur lui-même ; aucun lien de retour reçu. | Interroger votre serveur : le paiement a pu aboutir quand même (Mobile Money validé après la fermeture, par exemple). |
| `unknown` | Lien de retour reçu sans statut reconnu. | Interroger votre serveur. |

Dans tous les cas, seul votre serveur fait foi (`GET /payment-intents/{id}` ou webhook `payment.succeeded`).

Cas particulier : si aucun navigateur ne peut être ouvert, le statut vaut `closed` et `result.error` vaut `"browser_unavailable"`.

### Redirection automatique et Chrome

Sur la page de succès, Frobill redirige vers l'application automatiquement après quelques secondes, et affiche un bouton pour y revenir tout de suite. Chrome peut bloquer une redirection automatique vers une application quand elle n'est pas déclenchée par un geste de l'utilisateur : la page reste alors affichée et **c'est le bouton qui ramène le client dans l'application**. Ce comportement vient de Chrome, pas du SDK. Le résultat renvoyé est le même dans les deux cas.

## Dépannage

| Symptôme | Cause probable |
| --- | --- |
| L'API répond `app_scheme_not_allowed` à la création de l'intention | Le schéma n'est pas déclaré dans le tableau de bord (Développeurs → Clés API → Applications mobiles). |
| `FrobillInvalidCheckoutUrlException` | Vous passez autre chose que le `checkout_url` renvoyé par l'API, ou vous testez sur un autre hôte sans `allowedHost`. |
| Le paiement aboutit, la page propose de revenir, mais rien ne se passe | `FrobillRedirectActivity` absente du manifeste, `android:exported="true"` manquant, ou schéma / hôte du filtre différents de ceux du `return_url`. Test : `adb shell am start -a android.intent.action.VIEW -d "monapp://frobill/retour?status=succeeded"` doit ouvrir votre application. |
| La page de succès reste affichée | Chrome a bloqué la redirection automatique : le client touche le bouton de retour (voir ci-dessus). |
| `IllegalStateException` à l'appel de `registerForCheckout` | L'enregistrement est fait trop tard (après `onStart`). Déclarez le lanceur comme propriété de l'activité ou du fragment. |
| Statut `closed` avec `error = "browser_unavailable"` | Aucun navigateur installé ou activé sur l'appareil (fréquent sur un émulateur sans Chrome). |
| Statut `closed` alors que le client a payé | Il a fermé l'onglet avant la redirection. Interrogez toujours votre serveur. |
| Une application tierce envoie un faux lien `monapp://frobill/retour?status=succeeded` | C'est possible avec tout schéma d'URL : c'est pourquoi le statut est indicatif et que la confirmation passe par votre serveur. |

## Compiler et tester

```sh
./gradlew :frobill:testDebugUnitTest   # tests JVM de la logique pure
./gradlew :frobill:assembleRelease     # produit frobill/build/outputs/aar/frobill-release.aar
```

Le SDK Android doit être indiqué par `ANDROID_HOME` ou par un fichier `local.properties` (`sdk.dir=…`).

## Licence

MIT — voir [LICENSE](LICENSE).
