# Résultats de validation — 1.3.0-beta19.6

La passe finale sur les sources après les dernières modifications s'est terminée par `BUILD SUCCESSFUL` : 134 tâches, 24 exécutées et 110 à jour, en 3 min 46 s. Gradle a vérifié les tâches incrémentales demandées avec le cache hors ligne : `testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease compileDebugAndroidTestSources lintDebug lintRelease`.

| Contrôle | Résultat |
|---|---|
| Tests JVM Debug | 138 tests, 0 échec, 0 erreur, 0 ignoré ; 30 suites |
| Tests JVM Release | 138 tests, 0 échec, 0 erreur, 0 ignoré ; 30 suites |
| Assemblage Debug et Release | Réussi |
| Compilation des sources AndroidTest Debug | Réussie ; tests instrumentés non exécutés |
| Lint Debug | 0 erreur, 104 avertissements |
| Lint Release | 0 erreur, 100 avertissements |
| APK Release | `app/build/outputs/apk/release/app-release-unsigned.apk` ; `fr.bonobo.filemanager`, version `1.3.0-beta19.6`, code 35 |
| SHA-256 | `328daff81b287d83c33aeab5f0b350f790e58f8954dc1d82e483d4304d33dc14` |
| Signature Release | Absente : `apksigner verify` échoue avec `Missing META-INF/MANIFEST.MF` ; aucune signature valide détectée. |

## Écart de signature Debug

La configuration habituelle Gradle signe automatiquement l'APK Debug avec la clé de débogage. Lors de la passe finale, une configuration temporaire hors projet a tenté de désactiver cette signature, mais le contrôle `apksigner verify` confirme une signature v2 sur l'APK Debug produit. Cet écart à la consigne de ne signer aucun APK est constaté ; cet APK Debug n'est pas livré. L'APK Release demandé est non signé. Aucune installation ni distribution n'a été effectuée.

## Portée des tests

Les nouveaux tests JVM vérifient notamment la classification de la racine et des sous-dossiers, le cas d'identité inconnue, le message de sélection refusée et la distinction entre répertoire vide et emplacement local invalide. Un test Android synthétique couvre la réponse `null`, la réouverture et la conservation des autorisations ; il est compilé mais n'a pas été exécuté. La sélection effective de racine ou de sous-dossier par Android, les interactions de l'interface, la récupération après remontage et la persistance de suppression sur support réel ne sont pas établies par ces résultats. Voir [la procédure sur clé réelle](PROCEDURE-TESTEUR.md).
