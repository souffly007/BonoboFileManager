# Bêta 16 — préparation de la signature Release

Date : 11 octobre 2026. Aucune APK signée produite pendant ces vérifications. Aucun keystore ouvert, créé ou modifié ; aucun mot de passe demandé par l’assistant, lu ou enregistré.

## Configuration confirmée

| Élément | Release |
| --- | --- |
| Application ID / package | `fr.bonobo.filemanager` |
| Version | `1.3.0-beta16` |
| versionCode | **26**, supérieur au code **25** de la bêta 15 archivée |
| DEBUG | `false` |
| Suffixes Debug | Aucun : `.adaptive` et `-debug` restent limités à Debug |
| Android | minSdk 26, targetSdk 35 |
| Minification | Désactivée, configuration existante conservée |
| Signature Release dans Gradle | Non configurée ; l’assistant Android Studio permettra de sélectionner le keystore existant au moment de signer |

Valeurs vérifiées dans `app/build.gradle.kts`, dans le manifeste Release fusionné et dans `BuildConfig.java` généré pour Release. Aucun changement de configuration nécessaire à ce stade.

La configuration existante contient `checkReleaseBuilds=false`. Cela désactive le contrôle Lint automatique associé à certains assemblages Release ; **une analyse `lintRelease` explicite a été lancée** pour cette vérification. Aucun assouplissement ou nouvelle suppression Lint ajouté.

## Corrections bêta 16 dans Release

Les quatre fichiers applicatifs concernés sont dans `app/src/main`, sans remplacement `app/src/release` ni condition `BuildConfig.DEBUG` entourant ces corrections :

- `util/SafFiles.kt` : confirmation de création, relecture SHA-256 après fermeture du flux, vérification récursive, annulation et confirmation avant suppression de la source.
- `data/repository/FileRepositoryImpl.kt` : copie/déplacement via le transfert vérifié, pour fichiers locaux et SAF.
- `presentation/ui/main/MainViewModel.kt` : actualisation des panneaux au retour au premier plan.
- `presentation/ui/main/MainScreen.kt` : observation de `ON_RESUME`, réessai de l’accès et nouvelle sélection USB / OTG.

La compilation Release a généré les classes correspondantes. Leur bytecode contient `SHA-256`, `verifyTree`, l’appel à `SafFiles`, `refreshAfterResume` et l’observation de `ON_RESUME`. Le fournisseur synthétique `TestDocumentsProvider` et les activités de test sont exclus du manifeste et des classes applicatives Release.

La base Room reste `bonobo_database`, version 4, avec la migration 3 → 4 existante ; aucun repli de migration destructif n’est configuré dans `DatabaseModule`. Aucune donnée applicative n’a été ouverte ou modifiée pendant cette préparation. Un remplacement réel sur une installation contenant des données n’a pas été exécuté.

## Vérifications exécutées, sans production d’APK

```sh
./gradlew :app:compileReleaseKotlin :app:processReleaseManifest :app:testReleaseUnitTest :app:lintRelease --offline
```

- Compilation Kotlin/Java Release et fusion du manifeste : **réussies**.
- **48 tests JVM Release réussis**, zéro échec, erreur ou test ignoré.
- **Lint Release : zéro erreur, 90 avertissements**. Les 92 avertissements précédents concernaient la variante Debug ; les périmètres des variantes diffèrent.
- Aucune tâche `assembleRelease`, `packageRelease`, génération de bundle ou signature d’APK demandée. Les ressources et classes intermédiaires ont été générées pour les vérifications.
- Les tests Android SAF déjà validés en Debug ne sont pas présentés comme une exécution sur une APK Release signée : cette dernière n’existe pas encore.

Preuves archivées dans [release-check](release-check/) : journal de compilation, XML des tests, rapport Lint, manifeste et BuildConfig. Aucun mot de passe ni fichier de keystore ne figure dans ces preuves.

## Comparer votre certificat localement

La bêta 15 archivée a été revérifiée directement :

- APK : `app/release/bonobo files explorateur v1.3.0-beta15.apk`.
- Package : `fr.bonobo.filemanager`, code 25.
- Signature valide, certificat `CN=ROBERT-FONT Franck`.
- Empreinte **SHA-256 du certificat** à retrouver :

```text
a2723a3cc2b325db18d8f4b662ec262efbcf6954d8ace8a78e59b9176a7e55f1
```

Dans un terminal local privé, remplacez seulement le chemin et l’alias par ceux d’Android Studio :

```sh
keytool -list -v -keystore "/chemin/vers/votre-keystore" -alias "votre-alias-existant"
```

Cette commande consulte le keystore, sans le modifier. N’ajoutez **ni `-storepass` ni `-keypass`**. Si l’outil requiert une authentification, saisissez-la exclusivement dans son invite locale masquée, sans partage d’écran ni journalisation du terminal. Ne communiquez aucun mot de passe dans la conversation.

Vérifiez que l’alias est une entrée de clé privée (`PrivateKeyEntry`). Comparez le SHA-256 du **certificat de la clé, premier certificat de la chaîne**, avec l’empreinte ci-dessus. Ignorez les deux-points et la différence majuscules/minuscules. Ne comparez pas uniquement le nom du propriétaire, un certificat d’autorité dans la chaîne, ni le SHA-256 du fichier APK.

Si l’alias est oublié, `keytool -list -v -keystore "/chemin/vers/votre-keystore"` affiche les entrées disponibles ; sélectionnez ensuite l’alias dont le certificat correspond. Voir la [documentation Oracle de keytool](https://docs.oracle.com/en/java/javase/21/docs/specs/man/keytool.html).

## Génération ultérieure depuis Android Studio

Ces étapes sont préparées pour votre utilisation ; elles **n’ont pas été exécutées**.

1. Après concordance du certificat, ouvrir **Build → Generate Signed Bundle / APK**, choisir **APK**, puis le module **app**.
2. Sélectionner le **keystore personnel existant** et l’**alias vérifié**. Ne pas utiliser **Create new**.
3. Saisir les secrets seulement dans les champs locaux masqués de l’assistant. Ne pas cocher **Remember passwords** si cette option est proposée ; ne rien copier dans Gradle, les propriétés du projet, un script ou un journal.
4. Choisir la variante **release** et un dossier de sortie dédié, par exemple `app/release/beta16-signee`, afin de conserver les anciennes APK. Si des schémas de signature sont proposés, inclure **V2**, comme pour la bêta 15 archivée.
5. Terminer la génération uniquement après validation du certificat. Utiliser **Locate** pour repérer le fichier produit : ne pas confondre cet APK avec l’artefact Debug `.adaptive`.

Procédure fondée sur la [documentation de signature Android Studio](https://developer.android.com/studio/publish/app-signing).

## Contrôles après votre future génération

Depuis la racine du projet, remplacer le chemin de l’APK dans ces commandes de lecture seule :

```sh
/var/home/Snoopy63/Android/Sdk/build-tools/35.0.0/aapt dump badging "/chemin/vers/APK-release-signee.apk" | head -n 1
/var/home/Snoopy63/Android/Sdk/build-tools/35.0.0/apksigner verify --verbose --print-certs "/chemin/vers/APK-release-signee.apk"
sha256sum "/chemin/vers/APK-release-signee.apk"
```

Résultat attendu : package `fr.bonobo.filemanager`, versionCode `26`, versionName `1.3.0-beta16`, signature valide et SHA-256 du certificat identique à celui de bêta 15. Le SHA-256 du fichier APK sera nouveau et devra être conservé séparément. Aucun de ces contrôles ne demande le mot de passe du keystore.

## Possibilité de mise à jour directe

**Package et progression de version sont compatibles.** Si votre alias possède exactement le certificat de la bêta 15 archivée, et si le testeur utilise cette bêta 15, une APK Release signée avec cette clé est compatible avec une mise à jour directe, sans désinstallation. La correspondance du certificat personnel reste à effectuer localement ; elle n’est pas confirmée par le simple fait d’avoir retrouvé le chemin du keystore.

Le contrôle final de l’APK signée et un essai de remplacement sur un émulateur isolé pourront être réalisés ensuite avant diffusion. Aucun essai de remplacement, aucune installation sur smartphone et aucune publication sur souffly.app n’ont été effectués ici.
