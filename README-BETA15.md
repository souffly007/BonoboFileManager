# Bonobo Files Explorateur — bêta 15

Version préparée le 10 octobre 2026 : **1.3.0-beta15-debug**, code 25.
APK Debug : `app/build/outputs/apk/debug/app-debug.apk`.
Application Debug : `fr.bonobo.filemanager.adaptive` (suffixe existant conservé ; installation distincte de la version Release).
SHA-256 : `8efe9184342da704dcc9330ecdd544611b70e4b2c3bb37dc794915cbabd863e1`.

Aucune publication, aucun APK envoyé sur Internet, aucune modification du site souffly.app. Les changements déjà présents dans le dossier de travail ont été conservés. Cette préparation attend une validation sur smartphone.

## Diagnostic et changements

La bêta 14 découvrait les supports depuis les dossiers privés externes de l'application, puis essayait de naviguer avec `java.io.File`. Elle affichait tous ces supports comme des cartes SD. Une clé exposée seulement par le fournisseur de documents Android pouvait donc manquer, même si le gestionnaire Android y accédait.

- Entrée **Stockage USB / OTG** à l'accueil : interrogation des `StorageVolume` amovibles, description et état fournis par Android. Ouverture directe lorsqu'un chemin est effectivement lisible, sinon demande d'accès au volume via SAF. Android ne distingue pas systématiquement les cartes SD des clés USB dans cette API ; les raccourcis locaux portent désormais le nom « Stockage amovible ».
- Bouton **Sélectionner manuellement** avec `ACTION_OPEN_DOCUMENT_TREE`, toujours disponible. Les dossiers déjà autorisés apparaissent dans cette entrée, y compris ceux d'autres fournisseurs SAF.
- Conservation des seules permissions lecture/écriture renvoyées par Android, uniquement lorsque le drapeau de persistance est présent. Si la permission ne peut pas être conservée, un message explique son caractère temporaire.
- Navigation et remontée depuis des URI opaques, sans transformer les identifiants de documents en chemins locaux. Fil d'Ariane simplifié pour SAF : retour à la racine et bouton parent.
- Création de fichiers/dossiers, copie et déplacement récursifs entre stockage local et SAF ou entre dossiers SAF, renommage et suppression définitive. Respect des capacités annoncées par le fournisseur ; erreurs visibles pour les refus et la lecture seule. Aucun écrasement volontaire d'un nom existant.
- Copie avant suppression pour les déplacements. Contrôle de longueur lorsque le fournisseur connaît la taille ; une lecture interrompue conserve la source et tente de supprimer la destination partielle. Un échec de nettoyage peut néanmoins laisser un fichier partiel sur une clé déconnectée : à vérifier après reconnexion.
- Protection contre les noms dangereux, les liens symboliques locaux et la copie d'un dossier dans lui-même ou un descendant. Parcours de secours sur Android 8–10 ou si le fournisseur ne propose pas `isChildDocument`.
- Actualisation lors des événements de montage/retrait tant que l'explorateur est affiché. Les autorisations ne sont pas effacées au retrait ; reconnecter puis actualiser, ou rouvrir l'entrée USB / OTG. Aucun filtre d'intention d'insertion USB ni lancement automatique.
- Favoris, historique et raccourcis SAF conservés comme URI ; un raccourci SAF indisponible n'est pas supprimé automatiquement. Partage de fichiers SAF avec permission de lecture temporaire.
- Remontée corrigée des `Result.failure` dans les actions copier/déplacer/renommer/supprimer, ainsi que des erreurs des opérations groupées.

## Intégration Android et sécurité

Filtres `ACTION_VIEW`, `ACTION_SEND` et `ACTION_SEND_MULTIPLE`, sans filtre générique `*/*` : PDF, EPUB, texte simple, CSV, JSON, ZIP/RAR/7z, JPEG/PNG/WebP/GIF, MP4, MP3, DOCX/XLSX/PPTX et ODT/ODS/ODP. Les formats Office binaires DOC/XLS/PPT ne sont pas annoncés.

Une ouverture unique utilise le lecteur correspondant. Un partage multiple ouvre un dossier temporaire dans l'explorateur. Les URI `content://`, `EXTRA_STREAM` et `ClipData` sont lus pendant la vie de l'activité réceptrice. Les flux sont copiés dans le cache privé avec noms nettoyés et répertoires uniques, puis traités par les lecteurs existants. Limites : 32 URI, 256 Mio par fichier, 512 Mio par partage. Les autres schémas sont refusés ; une application externe ne peut pas faire ouvrir les fournisseurs privés de Bonobo sous les droits de Bonobo.

**Les lecteurs ouvrent une copie temporaire pour les documents SAF ou reçus. Une modification dans l'éditeur de cette copie ne modifie pas le document source.** Le message affiché à la réception et ce rapport précisent cette limite ; les fichiers du cache ne constituent pas une importation durable. L'ouverture SAF utilise la même liste explicite de MIME ; un fournisseur qui renvoie un MIME générique ou incorrect peut empêcher l'ouverture interne malgré une navigation/copie possible.

Android peut proposer « Toujours » / une application par défaut pour un type compatible suivant sa version, l'application émettrice et les choix déjà enregistrés. Bonobo ne force ni ne modifie ces choix. Il ne remplace pas DocumentsUI, n'offre pas de sélecteur de dossiers aux autres applications et ne fournit aucun DocumentsProvider en production. Le fournisseur synthétique ajouté est exclusivement dans l'APK de tests.

## Fichiers concernés par cette livraison

Modifiés pour cette tâche (certains avaient déjà des modifications locales) :

- `app/build.gradle.kts` : version et suffixe Debug.
- `app/src/main/AndroidManifest.xml` : filtres des fichiers compatibles.
- `app/src/main/java/fr/bonobo/filemanager/MainActivity.kt` : réception des intentions et importation temporaire.
- `app/src/main/java/fr/bonobo/filemanager/data/repository/FileRepositoryImpl.kt` : branche SAF, contrôle d'accès local, opérations et favoris/historique.
- `app/src/main/java/fr/bonobo/filemanager/util/ShareUtils.kt` : partage d'URI SAF.
- `app/src/main/java/fr/bonobo/filemanager/presentation/components/BreadcrumbNav.kt` : navigation SAF.
- `app/src/main/java/fr/bonobo/filemanager/presentation/ui/dashboard/DashboardScreen.kt` : entrée USB / OTG et raccourcis.
- `app/src/main/java/fr/bonobo/filemanager/presentation/ui/main/MainViewModel.kt` : destinations SAF, parents, actualisation et remontée des erreurs.
- `app/src/main/java/fr/bonobo/filemanager/presentation/ui/main/MainScreen.kt` : retrait/reconnexion, ouverture temporaire et partage SAF.
- `app/src/main/java/fr/bonobo/filemanager/presentation/ui/navigation/AppNavigation.kt` : acheminement vers les lecteurs ou l'explorateur.

Ajoutés :

- `app/src/main/java/fr/bonobo/filemanager/util/SafFiles.kt` : adaptateur de documents SAF.
- `app/src/main/java/fr/bonobo/filemanager/util/IncomingFiles.kt` : contrôle des intentions, formats et flux reçus.
- `app/src/main/java/fr/bonobo/filemanager/presentation/components/UsbStoragePicker.kt` : sélection du volume/dossier et permissions.
- `app/src/test/java/fr/bonobo/filemanager/AndroidIntegrationTest.kt` : cinq tests JVM de sécurité, flux et politique de persistance.
- `app/src/androidTest/AndroidManifest.xml` : déclaration du fournisseur de test uniquement.
- `app/src/androidTest/java/fr/bonobo/filemanager/saf/TestDocumentsProvider.java` : fournisseur synthétique de test.
- `app/src/androidTest/java/fr/bonobo/filemanager/saf/SafIntegrationTest.kt` : quatre scénarios instrumentés.
- `README-BETA15.md` et `validation/beta15/build.log` : rapport et preuve de compilation.

## Validation

Commande finale :

```sh
./gradlew assembleDebug testDebugUnitTest assembleDebugAndroidTest lintDebug --offline
```

- **Compilation Debug réussie**, APK généré.
- **48 tests JVM réussis**, 0 échec, 0 erreur, 0 ignoré. Les cinq nouveaux tests couvrent les noms, les formats, les limites de flux, les erreurs d'écriture et la restriction des permissions persistantes aux drapeaux réellement accordés.
- Suites existantes exécutées : opérations/use cases, copie et sécurité des fichiers/archives, transferts et téléchargements, favoris, disposition de l'explorateur, SMB, serveur FTPS, utilisateurs de session et signature TLS. Ces tests ne remplacent pas des essais réseau réels SMB/SFTP/HTTPS ni une validation des lecteurs/thèmes sur téléphone.
- **APK de tests Android compilé**. Les quatre nouveaux scénarios vérifient sur un fournisseur synthétique les opérations SAF, les conflits de noms, les dossiers récursifs, les refus de lecture seule, les écritures échouées, un déplacement à lecture tronquée, les flux `content://`, les schémas interdits et le refus d'une persistance sans permission offerte.
- **Six scénarios instrumentés validés lors de la reprise du 10 octobre 2026**, sur la copie isolée de `SyncZen-Recette-API35` : contexte Android, rotation/recréation et copie entre panneaux, quatre scénarios SAF. Résultats obtenus en exécutions ciblées après correction de la configuration et du fournisseur synthétique. Voir [le rapport de reprise](validation/beta15/REPRISE.md). La persistance positive après relance/redémarrage, les vraies permissions temporaires interapplications et le retrait physique restent à tester sur smartphone.
- **OTG physique non validé** : aucune clé USB réelle accessible à cette session.
- **Lint validé : 0 erreur, 92 avertissements**, avec `abortOnError=true`. Les corrections déjà présentes à la reprise mutualisent les destinations cloud dans `RemoteDownloadTarget` (branche API 29 et repli antérieur) et contrôlent la permission de notification avant les mises à jour dans les services. La compilation incrémentale de la fixture corrigée et Lint ont réussi. Journaux : `validation/beta15/lint-resume.log` et `validation/beta15/test-fixture-build5.log`.
- `git diff --check` global signale des espaces finaux dans des modifications locales préexistantes de `AddRemoteDialog.kt` et `NetworkScreen.kt`, laissées intactes.

Rapports générés : `app/build/reports/tests/testDebugUnitTest/index.html` et `app/build/reports/lint-results-debug.html`. Journal final conservé dans `validation/beta15/build.log`.

## Limites Android et opérations

L'accès dépend du montage Android, du système de fichiers et du fournisseur. Bonobo n'ajoute pas de pilote USB, de droits root ou de contournement. Les droits peuvent être limités à la lecture, révoqués, temporaires ou perdus si un fournisseur change l'identifiant du support. Android 11+ restreint les racines de certains stockages ainsi que `Android/data` et `Android/obb` : sélectionner un sous-dossier autorisé lorsque la racine n'est pas proposée.

SAF n'offre pas de transaction générale : un déplacement utilise copie puis suppression ; si la suppression échoue, la copie demeure et l'erreur est affichée. La comparaison des noms avant création dépend du fournisseur et n'est pas une réservation atomique contre une écriture concurrente. Certains fournisseurs peuvent normaliser les noms. La corbeille locale ne prend pas en charge SAF : le message invite à utiliser la suppression définitive. Compression, chiffrement, comparaison, extraction vers une destination SAF et transfert réseau depuis une URI SAF ne sont pas ajoutés à cette bêta ; leurs fonctions locales existantes restent disponibles.

## Test simple pour l'utilisateur de la bêta 14

Utiliser une copie de fichiers de test et conserver les originaux ailleurs.

1. Installer l'APK Debug préparé ; ouvrir Bonobo, puis brancher la même clé et vérifier que le gestionnaire Android y accède encore.
2. Ouvrir **Stockage USB / OTG**. Essayer le volume affiché. S'il manque ou ne s'ouvre pas, choisir **Sélectionner manuellement**, afficher le menu des emplacements Android, sélectionner la clé (ou un sous-dossier), puis **Utiliser ce dossier / Autoriser**.
3. Créer `Test Bonobo`, copier un petit fichier depuis la mémoire interne vers ce dossier, le renommer, ouvrir un PDF ou une image compatible puis supprimer le fichier de test. Tester la copie inverse et le déplacement dans les deux sens. Vérifier les résultats dans le gestionnaire Android.
4. Répéter une copie avec un nom déjà présent : vérifier l'erreur et l'intégrité du fichier existant. Si possible, sélectionner un emplacement en lecture seule : navigation autorisée, écriture refusée avec un message.
5. Fermer puis relancer Bonobo et rouvrir le dossier autorisé ; redémarrer le téléphone et répéter. Si un message indique un accès temporaire, la sélection devra être refaite. Ajouter aussi un favori au dossier USB et le rouvrir.
6. Retirer la clé lorsque l'explorateur est ouvert, puis pendant une copie d'un gros fichier de test. Vérifier le message d'erreur, l'intégrité de la source, l'absence d'écrasement et les éventuels fichiers partiels. Reconnecter, actualiser et vérifier l'accès sans supprimer les favoris/permissions.
7. Depuis une autre application, utiliser **Ouvrir avec** pour un PDF/JPEG/EPUB compatible et choisir Bonobo. Essayer **Toujours** si Android le propose. Utiliser ensuite **Partager** pour un fichier puis plusieurs fichiers compatibles. Vérifier le lecteur ou le dossier reçu ; une édition concerne la copie temporaire seulement.
8. Vérifier mémoire interne, carte SD, favoris locaux/réseau, copie/déplacement/suppression habituels, SMB, client/serveur SFTP, partage HTTPS, lecteurs, thème et paramètres.

En cas d'échec, relever le modèle de téléphone, la version Android, le format de la clé (FAT32/exFAT/NTFS si connu), l'étape exacte, le message affiché et si la sélection manuelle présente la clé. Ne communiquer aucun fichier personnel ni identifiant réseau.
