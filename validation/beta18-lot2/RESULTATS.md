# Validation — bêta 18 lot 2

Validation effectuée le 11 octobre 2026 dans Bonobo Files Explorateur uniquement. Tests JVM sur données temporaires, doubles de moteurs et serveurs de fixtures loopback. Aucun NAS réel, aucune installation sur téléphone, aucun démarrage d'émulateur, aucune publication. Version 1.3.0-beta17 / code 27 conservée ; Release non signée, pas de nouvelle clé de signature.

La commande finale s'est terminée avec **code 0, BUILD SUCCESSFUL**, durée 1 min 52 s :

```sh
./gradlew testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease assembleDebugAndroidTest lintDebug lintRelease --offline --max-workers=1
```

| Vérification | Résultat réellement obtenu |
| --- | --- |
| JVM Debug | 88 tests, 0 échec, 0 erreur, 0 ignoré |
| JVM Release | 88 tests, 0 échec, 0 erreur, 0 ignoré |
| Compilation APK Debug | Réussie : app/build/outputs/apk/debug/app-debug.apk |
| Compilation APK Release | Réussie, non signée : app/build/outputs/apk/release/app-release-unsigned.apk |
| Compilation instrumentation Debug | Réussie : app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk |
| Lint Debug | 0 erreur / 92 avertissements |
| Lint Release | 0 erreur / 90 avertissements |
| Instrumentation Android | 20 tests NON EXÉCUTÉS ; blocage 139 antérieur, aucune tentative supplémentaire |
| Service dataSync, rotation réelle, USB physique | NON EXÉCUTÉS ; compilation seule ne prouve pas leur fonctionnement |
| Intégration SMB/SFTP sur serveur de laboratoire | NON EXÉCUTÉE ; accès simulés/primitives et machine d'états testés en JVM |
| HTTP WebDAV loopback | 4 tests existants du lot 1 exécutés, inclus dans les 88 par variante |

22 nouveaux tests JVM s'ajoutent aux 66 du lot 1. Les passages intermédiaires ont également réussi (80, 84 puis 85 tests avant les trois tests d'inventaire complémentaires). Les derniers changements de comptage, jonctions et normalisation SMB sont compris dans la validation finale. Aucun échec de test/compilation/Lint à résoudre dans ce lot.

Les tests ajoutés couvrent : exécution séquentielle et refus d'un second lot ; destination capturée ; conservation du résultat de chaque élément ; erreurs partielles et suppression non confirmée ; annulation avant démarrage, pendant vérification et pendant un appel simulé non interruptible ; éléments non démarrés conservés ; état encore actif avant fermeture réelle ; 100 % des octets distinct de fin vérifiée ; tailles inconnues, fichiers vides, compteurs au-delà de 4 Gio et débordements ; débit/ETA avec horloge monotone ; invalidation de totaux périmés ; racines couvertes non rejouées ; journal actif/illisible sans reprise et sans secrets ; échec de persistance avant toute copie ; inventaire sans lecture de contenu, cycle, refus du fournisseur/point de réanalyse et profondeur maximale sans débordement de pile.

La comparaison des diagnostics Lint avec les rapports du lot 1, par identifiant/message/fichiers en ignorant les numéros de ligne, donne **0 diagnostic ajouté et 0 supprimé**, dans chaque variante. Cela ne remplace pas une recette Android.

Preuves archivées : [journal final](gradle-validation.log), journaux intermédiaires et compilation Kotlin, XML JUnit dans jvm-debug/ et jvm-release/, rapports Lint XML/HTML/TXT, [empreintes des APK](APK-SHA256.txt), snapshots source-before.json/source-after.json. La [liste exacte des 22 fichiers applicatifs](FICHIERS.txt) reflète ce lot malgré les changements antérieurs déjà présents dans le dépôt. PLAN-BETA18.md est annoté pour renvoyer aux réalisations actuelles ; README-BETA18-LOT2.md et ce dossier de validation sont ajoutés. Les archives des lots antérieurs ne sont pas remplacées.

Les hashes de [PROTECTIONS-INCHANGEES.txt](PROTECTIONS-INCHANGEES.txt) confirment notamment : configuration Gradle/version/signature, journal de restauration, TrashRestorer/StorageEquivalence, clients SFTP/WebDAV, base Room et ses migrations inchangés. SafFiles et IntegrityVerifier ajoutent des événements d'observation et des points de contrôle d'annulation sans retirer les assertions de copie/relecture. SMB réutilise son vérificateur pour l'inspection préalable et la relecture protégée du lot 1.

Le [blocage Android](BLOCAGE-ANDROID.md) et les [limites/régressions de portée](../../README-BETA18-LOT2.md) restent explicites : destinations SAF indéterminées refusées, profondeur supérieure à 128 refusée, opérations réseau non prises en charge non ajoutées, erreurs rapportées par racine de dossier, journal de résumé sans historique détaillé ni reprise, dépendance aux délais réseau et absence de transaction atomique entre copie/vérification/suppression. La restriction des destinations opaques peut réduire les routes accessibles par rapport au moteur USB direct ; les thèmes et le service sont compilés mais leur recette reste ouverte.

Arrêt après le lot 2 et sa validation automatisée. Aucun déploiement ou lot suivant commencé.
