# Validation et livraison — bêta 19.4

Version **1.3.0-beta19.4**, versionCode **33**. Validation automatisée le 23 octobre 2026. Journal technique local volontaire ajouté à la bêta 19.3, sans changement des règles de transfert USB.

| Contrôle | Résultat |
|---|---|
| JVM Debug | 131 tests, 0 échec, 0 erreur, 0 ignoré |
| JVM Release | 131 tests, 0 échec, 0 erreur, 0 ignoré |
| APK Debug et Release | Compilation réussie |
| Tests Android Debug | APK compilée, tests non exécutés |
| Lint Debug | 0 erreur, 88 avertissements |
| Lint Release | 0 erreur, 86 avertissements |
| Version APK | 1.3.0-beta19.4 / 33, contrôlée avec aapt |
| Signature Release | Absente : apksigner code 1, aucune signature JAR ni bloc APK |
| Essais USB / interface sur appareil | Non exécutés |

## Livraison

[APK Release non signée](../../app/release/bonobo-files-explorateur-1.3.0-beta19.4-unsigned.apk). Taille : **25150070 octets**. SHA-256 : `5330f197f6ed875f7fbc7fe182482f96a8029bdaf0b3204094d74d0a6cb5fcc6`. Identique octet pour octet à la sortie Gradle. Une APK non signée nécessite le circuit de signature habituel du testeur avant installation ; aucune clé Release n’a été utilisée ou modifiée.

[Preuves APK et totaux](checks.json), [sortie aapt](apk-package.txt), [vérification de signature](apksigner-unsigned-check.txt), [empreinte](APK-SHA256.txt), [métadonnées](output-metadata.json), rapports JVM dans jvm-debug et jvm-release, rapports lint-results-debug/release.

## Diagnostic et confidentialité

Paramètres → Diagnostic technique : interrupteur désactivé par défaut et à chaque redémarrage du processus ; export ACTION_CREATE_DOCUMENT ; effacement confirmé. Stockage privé noBackupFilesDir, deux segments de 500 000 octets, maximum total 1 000 000 octets. Le plafond et la rotation sont testés avec un seuil réduit. API fermée : enums, UUID temporaires, compteurs et durées ; aucune entrée texte libre, URI, nom, empreinte, exception ou stack trace.

Les 8 tests TechnicalJournalTest couvrent absence de création de fichiers désactivé, activation/désactivation, rotation et export, effacement des deux segments, panne d’export, signature de l’API et format exporté, panne disque sans effet sur copie/vérification, SHA MISMATCH sans empreinte, puis producteur non bloqué pendant un export bloqué et une saturation de file. Certains tests couvrent plusieurs propriétés. Les 123 tests antérieurs, dont les tests de fiabilité USB et le flux synthétique > 2 Gio, restent passants dans les deux variantes.

[Audit de confidentialité](AUDIT-CONFIDENTIALITE.md). Aucun consommateur tiers ne reçoit les événements : stockage exclusivement java.io, file java.util.concurrent, export Android explicite. Aucun ajout de SDK, permission ou accès réseau. La permission INTERNET et les fonctions réseau préexistantes restent présentes. Le journal de reçus USB de sécurité préexistant est indépendant et n’est pas exporté.

## Exécution et périmètre

Commande finale avec JDK local et accès au cache Gradle hors sandbox, nécessaire car le cache est en lecture seule dans la sandbox :

```sh
JAVA_HOME=/var/home/Snoopy63/.jdks/jbr-21.0.11 ./gradlew --offline --no-daemon --max-workers=2 testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease assembleDebugAndroidTest lintDebug lintRelease
```

[Journal final](gradle-final.log). La première passe est archivée séparément dans gradle-intermediate.log : le test de saturation utilisait un helper d’attente dont la tâche pouvait être rejetée par la file pleine. Le helper de test utilise désormais une insertion explicite ; aucun changement de la politique non bloquante de production. Les résultats ci-dessus proviennent de la passe finale. Les empreintes des 14 fichiers du lot figurent dans code-sha256.json et ont été revérifiées après compilation. Le workspace contenait de nombreuses modifications antérieures, conservées sans remise à zéro.

Aucune règle de sécurité USB assouplie : publication progressive sans remplacement, fermeture et vérifications conservées, renommage SAF refusé en production, source conservée en mode prudent, nettoyage limité aux reçus. Aucun accès au NAS, aucune installation smartphone, aucune publication, aucune modification de clés de signature.

## Limites et suite testeur

La file bornée abandonne des événements sous charge ; une écriture déjà commencée peut finir après désactivation. L’export photographie uniquement les segments déjà écrits ; ses copies externes survivent à l’effacement local. Les événements sont volontairement sans identifiant matériel ni nom d’application. USB_RECONNECTED indique une nouvelle connexion observée dans le même processus, sans prouver l’identité du support. Les droits persistants globaux ne prouvent pas l’accès effectif à un document.

Aucun accès à un autre explorateur : les événements APP_RESUMED et SAF_FAILURE servent à corréler une reproduction manuelle, sans en attribuer automatiquement la cause. L’absence d’impact notable sur de gros fichiers est soutenue par l’absence de travail par bloc et le test de file bloquée ; les performances réelles USB restent à mesurer. Interface, fournisseur OEM, reconnexion et durabilité physique restent à vérifier selon la [procédure testeur](PROCEDURE-TESTEUR.md). Les tests Android sont compilés uniquement.

[Détail de version](../../README-BETA19.4.md).
