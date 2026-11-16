# Validation automatisée — 1.3.0-beta19.5

`versionCode 34`. La validation finale a été exécutée le 28 octobre 2026 sur l’état livré. Commande :

```sh
JAVA_HOME=/var/home/Snoopy63/.jdks/jbr-21.0.11 ./gradlew --offline --no-daemon --max-workers=2 testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease assembleDebugAndroidTest lintDebug lintRelease
```

**Résultat : `BUILD SUCCESSFUL`**, 153 tâches, 46 exécutées et 107 à jour, en 4 min 50 s. Le cache Gradle a nécessité une exécution hors sandbox. La passe précédente avait déjà réussi tous ces contrôles ; la passe finale intègre les derniers ajustements de diagnostic et d’interface.

| Contrôle | Résultat final |
|---|---|
| Tests JVM Debug | 134 tests, 0 échec, 0 erreur, 0 ignoré ; 29 suites |
| Tests JVM Release | 134 tests, 0 échec, 0 erreur, 0 ignoré ; 29 suites |
| Compilation et assemblage Debug | Réussis |
| Compilation et assemblage Release | Réussis |
| Compilation AndroidTest Debug | Réussie ; tests instrumentés non exécutés |
| Lint Debug | 0 erreur, 88 avertissements |
| Lint Release | 0 erreur, 86 avertissements |
| Version APK (`aapt dump badging`) | `1.3.0-beta19.5`, code `34`, application `fr.bonobo.filemanager` |
| Signature APK Release | Absente : `apksigner verify` indique `DOES NOT VERIFY`, `Missing META-INF/MANIFEST.MF` ; aucun fichier de signature JAR dans l’APK |

Les XML des tests sont archivés dans [jvm-debug](jvm-debug/) et [jvm-release](jvm-release/). Les rapports complets de Lint sont [Debug](lint-results-debug.xml) et [Release](lint-results-release.xml). Les [métadonnées APK](output-metadata.json) confirment version, code et nom de sortie.

## APK préparée

[Release non signée](../../app/release/bonobo-files-explorateur-1.3.0-beta19.5-unsigned.apk) : **25 150 070 octets**, SHA-256 `e414b0fc282c6e17c3775a9eefd3ab99cbd3b81622e37dbd4fe3030b13a33dff`. La copie de livraison est identique octet pour octet à `app/build/outputs/apk/release/app-release-unsigned.apk` (`cmp` réussi). Aucune clé de signature n’a été utilisée ou modifiée.

## Tests de régression ajoutés

`Beta195ReliabilityTest` vérifie : annulation en cours d’écriture avec fermeture de sortie avant nettoyage ; suppression du seul fichier créé et identifié ; nettoyage refusé avec reçus persistants ; reprise au même nom refusée avant création ; nouveau nom accepté sans supprimer le résidu ; suppression acquittée par un fournisseur sans disparition, résultat final indéterminé ; événements catégoriels sans nom factice ni URI. Le test de déplacement bêta 19.3 a été ajusté au message demandé. Les tests antérieurs couvrent notamment les refus SAF, le fournisseur synthétique, les gros flux, les reçus et la rotation du journal.

## Limites et essais physiques restants

Les AndroidTest ont été compilés seulement. Aucun essai sur clé USB réelle, aucune installation sur smartphone et aucune interaction avec ES Explorer ou un NAS n’ont été réalisés. Le testeur doit encore vérifier : annulation pendant un appel fournisseur éventuellement bloquant, refus de nettoyage, présence et résolution manuelle d’un dossier partiel, suppression de fichiers et dossiers sur le fournisseur réel, navigation après retour d’un autre explorateur, impact du diagnostic sur gros fichiers, puis taille et SHA-256 **après éjection et reconnexion**. La [procédure courte](PROCEDURE-TESTEUR.md) décrit ces étapes. Une vérification immédiate ne prouve pas la durabilité physique.

La [note de corrections](../../README-BETA19.5.md) décrit les choix et limites. La bêta est préparée ; aucune distribution ni publication n’a été effectuée. Attendre le feu vert.
