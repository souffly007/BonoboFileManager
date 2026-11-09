# Bonobo Files Explorateur — bêta 19.1 corrective

Version préparée : 1.3.0-beta19.1, versionCode 30. La valeur précédente a été vérifiée dans app/build.gradle.kts : 29, version 1.3.0-beta19. La Release est livrée sans signature ; la bêta 19 préparée reste conservée.

## Correction Téléchargements

Défaut : la tuile prédéfinie Téléchargements appelait loadCategory, qui listait Download sans définir ce dossier comme chemin courant. pasteClipboard refusait ensuite cette catégorie virtuelle, avant toute copie.

DashboardScreen résout désormais uniquement cette tuile prédéfinie vers Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS), puis appelle onNavigateToPath. Le mécanisme existant dans AppNavigation sélectionne le panneau 1, ouvre le dossier avec openFolder(resetNavigationRoot = true), efface la catégorie virtuelle et remplace le chemin précédemment visité. Le collage continue à transmettre navigationPath() au moteur existant, qui classe la destination comme StorageRef.Local. Il n'utilise pas le précédent dossier comme repli.

HomeFolderShortcut est une petite règle pure de résolution testable : elle ne s'applique qu'au titre Téléchargements sans bookmarkPath. Les signets enregistrés, y compris un signet nommé Téléchargements et les URI SAF, conservent le parcours précédent. Les autres catégories ne sont pas converties en dossiers. La protection générale contre le collage dans une catégorie virtuelle reste inchangée.

Aucun droit Android supplémentaire ni contournement n'est introduit. Ouvrir le raccourci n'implique pas que l'écriture soit autorisée : si Android refuse la lecture ou la création dans Download, les contrôles et erreurs du moteur existant restent applicables. Aucun dossier alternatif n'est choisi silencieusement.

Production modifiée : DashboardScreen.kt, nouveau HomeFolderShortcut.kt, numéro de version dans app/build.gradle.kts. Aucun autre fichier de production n'a été modifié dans ce lot (comparaison SHA-256 avant/après archivée dans validation/beta19.1).

## Vérification de la progression après annulation

Aucune reprise de transfert ajoutée. Aucun défaut de réutilisation des compteurs n'a été établi par le test JVM du coordinateur. Le code de production du coordinateur et de l'affichage n'a donc pas été modifié.

Le test utilise le coordinateur réel pour COPY et MOVE : un premier transfert rapporte 40 octets avec une émission encore tamponnée, puis est annulé. Après son arrêt et l'acquittement du résultat, une nouvelle opération possède un nouvel identifiant, PREPARING, zéro octet global/courant, zéro fichier terminé et zéro racine traitée, des totaux initialement inconnus, aucun résultat précédent, aucune annulation ou notification acquittée héritée, aucune vitesse/estimation héritée. À l'entrée en copie, la fraction est zéro ; les nouveaux octets restent propres au second transfert. Le résultat historique du premier est conservé.

Le moteur n'est pas réécrit : lecture depuis le début, absence de reprise par offset, refus des destinations déjà existantes et nettoyage prudent des fichiers partiels inchangés. La source est conservée lorsque le transfert ne peut pas être confirmé. L'impression visuelle du testeur après annulation reste à vérifier sur appareil réel ; aucun correctif visuel spéculatif n'est appliqué.

## Tests ciblés

JVM : Beta191Test, deux méthodes, dont une boucle COPY/MOVE. Vérifie la résolution de Download, l'exclusion des autres catégories et signets SAF/locaux, puis la réinitialisation complète du coordinateur après annulation avec progression tamponnée.

Android, compilés seulement :

- AdaptiveExplorerTest : clic sur la tuile Téléchargements depuis l'accueil après ouverture d'un autre dossier et d'une catégorie ; vérifie le dossier réel Download et la disparition de la catégorie.
- AdaptiveExplorerTest : une catégorie Images refuse toujours le collage avant création d'un nouveau transfert.
- TransferPanelTest : ancien affichage à 40 %, état annulé, nouvelle préparation puis affichage à 0 % et 7 %, sans ancien nom/progression.
- SafIntegrationTest : moteur local/SAF réel obtenu par Hilt, copie puis déplacement d'une source du fournisseur synthétique vers un dossier Download isolé dans le cache. Vérifie le contenu, la conservation de la source pour COPY, sa suppression après MOVE et l'absence d'écriture dans le dossier précédent. Une destination SAF non admissible reste refusée et conserve la source.

Ces fixtures ne valident pas une clé USB physique. Le test de refus SAF exerce les protections existantes, dont le refus d'équivalence opaque ; il ne constitue pas à lui seul un essai de permission révoquée sur une vraie clé. Les anciens tests de permissions et d'intégrité restent inclus dans les compilations/suites correspondantes.

## Validation et limites

Résultats définitifs, rapports JVM Debug/Release, Lint Debug/Release, journal Gradle et empreinte APK : validation/beta19.1/RESULTATS.md.

Aucun émulateur lancé ni smartphone utilisé. Tests instrumentés non exécutés, uniquement compilés. USB physique vers le Download réel, déplacement réel, permissions Android constructeur et animation effective de la barre après annulation restent à confirmer sur appareil. Aucune conclusion de reprise réelle n'est annoncée.

Corbeille, moteurs d'intégrité, indépendance des panneaux, acquittement persistant, moteurs SMB/SFTP/WebDAV et signets SAF inchangés. Aucun développement FTP/FTPS ou fiche USB. Aucun accès NAS, publication ou modification d'autres applications. Aucun changement de configuration de signature ni clé Release ; Gradle utilise la signature de développement habituelle pour les compilations Debug, la Release demeure non signée.

Risques résiduels : accès à Download refusé par Android, disponibilité du dossier selon le volume et le profil, différences de fournisseurs SAF, validation visuelle et matérielle encore absente. Le changement d'ouverture est limité au raccourci prédéfini Téléchargements.

Travail arrêté après validation automatisée disponible et préparation de l'APK Release non signée.
