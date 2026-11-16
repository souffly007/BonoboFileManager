# Validation bêta 19.1 — 12 octobre 2026

BUILD SUCCESSFUL. VersionName 1.3.0-beta19.1, versionCode 30 (précédente bêta 19 : 29). Contrôlé également dans les métadonnées de l'APK Release.

Commande exécutée avec le JDK local, dépendances offline et cache Gradle autorisé :

```sh
JAVA_HOME=/var/home/Snoopy63/.jdks/jbr-21.0.11 ./gradlew --offline --max-workers=2 testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease assembleDebugAndroidTest lintDebug lintRelease
```

- JVM Debug : 95 tests, 0 échecs, 0 erreurs, 0 ignorés.
- Lint Debug : 0 erreur, 92 avertissements.
- JVM Release : 95 tests, 0 échecs, 0 erreurs, 0 ignorés.
- Lint Release : 0 erreur, 90 avertissements.

Compilations APK Debug et Release réussies. Compilation des tests Android réussie. Aucun émulateur lancé, aucun test instrumenté exécuté et aucun appareil réel utilisé. Lint conserve des avertissements non bloquants ; aucun mécanisme de vérification n'a été désactivé.

Deux nouvelles méthodes JVM : résolution du raccourci et exclusions des catégories/signets ; annulation puis nouvelle opération COPY/MOVE avec compteurs tamponnés, identifiant neuf et compteurs réinitialisés. Les 93 tests JVM de bêta 19 restent inclus.

Quatre nouveaux tests Android compilés : ouverture réelle de Download depuis l'accueil après un ancien dossier/catégorie ; refus du collage dans une catégorie virtuelle ; affichage de la nouvelle progression après annulation ; copie/déplacement depuis un fournisseur SAF synthétique vers Download isolé avec conservation des sources en cas de refus.

L'APK livré est app/release/bonobo-files-explorateur-1.3.0-beta19.1-unsigned.apk. Le contrôle apksigner retourne 1 (« DOES NOT VERIFY », absence de MANIFEST.MF de signature), attendu pour une Release non signée. Une inspection binaire confirme aussi l'absence de bloc de signature APK v2/v3 et de signatures JAR. Empreinte : APK-SHA256.txt. Métadonnées : output-metadata.json.

Périmètre contrôlé par SHA-256 avant/après des fichiers de production existants : DashboardScreen.kt seulement ; ajout de HomeFolderShortcut.kt et mise à jour Gradle du numéro de version. MainViewModel, coordinateur, journal, affichage de transfert, intégrité, corbeille, panneaux, SAF et moteurs réseau inchangés. Détails : production-scope.json. Les nombreux changements déjà présents dans le dépôt n'ont pas été annulés.

Limites : USB physique, Download réel avec les permissions du testeur, déplacement réel, transitions animées sur appareil et tests instrumentés non validés. Les tests SAF compilés utilisent un fournisseur synthétique et des données de cache isolées. Aucun NAS, téléphone, fichier personnel, publication ni modification de clé Release. Debug suit la configuration de développement Gradle existante ; aucune signature Release effectuée.

Rapport : ../../README-BETA19.1.md. Travail terminé après cette validation automatisée et préparation de l'artefact.
