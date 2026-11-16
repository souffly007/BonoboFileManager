# Fiabilité USB — diagnostic préalable bêta 19.3

21 octobre 2026 — Phase A, audit statique du workspace actuel. Aucune modification de code, version, signature ou configuration. Aucune installation, publication, connexion NAS ou manipulation de données personnelles. Les nombreuses modifications préexistantes du workspace sont laissées intactes.

## Conclusion pour décision

La cause matérielle du retour testeur n'est pas établie. Le code contient cependant des lacunes vérifiables permettant un résultat trop optimiste : absence de contrôle de disparition après suppression, nettoyage fondé sur le même acquittement, nouvelle URI de renommage ignorée. La copie contrôle déjà le contenu après fermeture, mais ne teste jamais un démontage/remontage. L'absence de synchronisation explicite est un point à traiter, pas une preuve de la cause de l'incident.

**Recommandation prioritaire : ne pas supprimer automatiquement une source sur la seule foi d'une relecture immédiate lorsque la destination USB présente un risque de persistance non qualifié.** Prévoir une copie vérifiée avec source conservée et une finalisation explicite après contrôle approprié. La politique exacte de finalisation des déplacements USB doit être validée avant implémentation ; ajouter fsync ne suffit pas à lever cette réserve.

## Parcours réellement utilisés

- Coller / Déplacer : `MainViewModel` → `TransferManager` / `TransferCoordinator` → `TransferEngineFactory.local()` → `SafFiles.copy/move`.
- Repository : `FileRepositoryImpl.copyFile/moveFile` appelle également `SafFiles`, y compris pour des chemins locaux. L'audit ne doit donc pas assimiler toute opération USB à une URI SAF.
- Supprimer : `MainViewModel.removeItems` → `DeletionBatch` → repository → `SafFiles.delete` pour SAF, `FileUtils.deleteRecursively` pour les chemins locaux.
- `UsbStoragePicker` peut ouvrir un chemin local de volume amovible en l'absence de grant SAF enregistré. La politique de fiabilité doit couvrir cette branche aussi.
- `FileOperationService` appelle les use cases mais ignore leurs `Result`. Il est déclaré au manifeste ; aucun appelant trouvé dans les sources principales. `FileOperationWorker` distingue succès/échec mais perd les détails. Ne pas attribuer au service l'incident observé sans preuve qu'il intervient.
- Aucun usage de `DocumentFile` trouvé dans le moteur principal ; les mutations SAF utilisent directement `DocumentsContract`.

## Constats et risques

### 1. Copie et ressources : protections présentes, persistance inconnue

Référence : `SafFiles.kt:240–285`.

La copie utilise un tampon de 64 Kio, un compteur `Long` avec `Math.addExact`, un SHA-256 calculé sur le flux source et une relecture complète de destination. La taille source est contrôlée si disponible. Les flux sont imbriqués dans `use` : sortie fermée avant entrée, puis destination rouverte. Les exceptions de fermeture effectivement lancées sont propagées ; si une autre erreur existe déjà, les erreurs de fermeture peuvent être dans `suppressed`. Aucun chargement complet du fichier en mémoire n'est visible dans ce chemin. L'inventaire des dossiers reste, lui, proportionnel au nombre d'entrées.

Le compteur représente les octets acceptés par les appels `write`, pas des octets physiquement persistés. La relecture valide le nombre d'octets et le contenu exposés immédiatement par le fournisseur. Le listing final vérifie nom/identité/type, mais ne compare pas explicitement `COLUMN_SIZE` destination au compteur. Une taille inconnue doit rester distincte de zéro.

`flush()` est appelé ; aucun `FileDescriptor.sync` / `Os.fsync` n'est utilisé pour ces données. Les seuls `fd.sync()` trouvés concernent des journaux internes, pas les fichiers transférés. Le moteur n'acquiert pas explicitement de `ParcelFileDescriptor` pour piloter une synchronisation ou examiner ses possibilités de signalement d'erreurs distantes. Le commentaire actuel sur les erreurs de fermeture ne constitue pas une garantie sur toutes les erreurs différées du fournisseur.

**Conséquence :** une copie peut passer tous ces contrôles puis disparaître après reconnexion si le fournisseur ou la chaîne de stockage n'a pas persisté les données/métadonnées. C'est une hypothèse compatible avec le retour testeur, pas une reproduction. Aucune preuve d'un dépassement à 2 Go n'est trouvée dans la boucle ; son fonctionnement réel au-delà de ce seuil reste à tester.

### 2. Suppression fichier et dossier : confirmation insuffisante

Référence : `SafFiles.kt:124–128`, `FileRepositoryImpl.kt:134–175`, `DeletionBatch.kt`.

Le flag DELETE et le booléen de `DocumentsContract.deleteDocument` sont contrôlés. En revanche, aucune requête après suppression ne confirme l'absence dans un parent encore accessible. Un acquittement positif avec document toujours visible peut donc devenir un succès affiché. Les références de `parents` ne sont pas invalidées.

La suppression de dossier SAF est confiée en un appel au fournisseur ; ce n'est pas une récursion contrôlée par l'application. Le fournisseur peut échouer après avoir retiré certains enfants. Le bilan existant prévient d'une suppression partielle en cas d'erreur, mais ne sait pas la mesurer et ne détecte pas un fournisseur qui acquitte sans terminer.

Ne pas transformer un refus de permission, une déconnexion, un curseur nul ou un ancien identifiant invalide en preuve d'absence. Il faut un parent répondant et des identités fiables ; même ce contrôle reste immédiat.

La corbeille SAF est déjà refusée au repository ; `DeletionBatch` refuse aussi une sélection mixte sans appel destructif. Cette protection doit être conservée. Aucune mise artificielle en corbeille n'est identifiée dans ce parcours.

`MainViewModel.removeItems:293–312` attend le résultat avant bilan. Cependant `deletionBusy` est un champ privé, pas un état observable de l'interface ; ce chemin n'active pas d'indicateur d'attente pendant l'opération. Les actions concurrentes sont bloquées, sans progression dédiée.

### 3. Déplacement : suppression source trop dépendante du contrôle immédiat

Référence : `SafFiles.kt:150–167`, `TransferCoordinator.kt:116–143`.

Le moteur exige DELETE sur une source SAF, copie, revérifie source/destination puis supprime la source. Une exception réelle de suppression produit un échec et conserve la destination. Le coordinateur ne compte alors pas l'élément DONE. Cette protection existe déjà ; elle ne doit pas être présentée comme absente.

Deux limites restent critiques : la vérification finale peut relire des caches ; un acquittement de suppression sans disparition peut conduire à DONE. Une suppression de dossier peut déjà avoir détruit certains enfants avant son échec : on ne peut pas promettre de conserver intégralement les deux arbres après cet échec.

Prévoir le résultat explicite « Copie effectuée, déplacement incomplet », avec état précis de la source. Ne jamais nettoyer la copie vérifiée parce que la suppression source a échoué. Sans transaction du fournisseur, une conservation intégrale des deux arbres ne peut être garantie après le début d'une suppression récursive ; la politique prudente est de différer cette étape destructive.

Une modification externe de la source entre vérification et suppression reste possible : aucune transaction ni verrou fournisseur ne la rend atomique. Un contrôle supplémentaire réduit le risque mais ne l'annule pas.

### 4. Annulation et destinations partielles : causes établies

Référence : `SafFiles.kt:108–122`, `219–221`, `287–297`.

1. Le document est créé sous son nom final, avant le `try` de copie. Si `createDocument` crée effectivement un document puis que la confirmation échoue (listing indisponible, nom/type inattendu), aucun reçu de création n'est retourné au nettoyage.
2. Les fichiers créés et identifiés sont nettoyés en cas d'erreur ; les dossiers ne le sont volontairement pas, pour éviter une suppression récursive d'ajouts externes. Des enfants copiés avant l'erreur peuvent rester.
3. Le résultat `cleaned` est fondé sur l'absence d'exception de `delete`, sans preuve de disparition. Le message « fichier incomplet supprimé » peut donc être trop affirmatif.
4. Le diagnostic ne fournit pas de résultat structuré contenant l'URI exacte et le parent de chaque résidu. Il n'existe pas dans ce parcours d'action dédiée de résolution des fichiers partiels.
5. L'annulation est coopérative entre lectures/écritures. Un appel fournisseur bloqué n'est pas interrompu par les seuls `ensureActive`. Aucun `CancellationSignal` explicite n'est utilisé dans ce chemin.

Le refus des conflits, la création exclusive locale et la comparaison des identifiants avant/après création sont utiles pour protéger les préexistants. Cependant un remplacement concurrent ou une réutilisation d'identité reste une limite ; sans identité suffisamment certaine, le nettoyage doit refuser de supprimer. Ne pas remplacer le nettoyage actuel par une suppression récursive générale.

### 5. Renommage et références SAF

Référence : `SafFiles.kt:129–134`.

Le retour non nul de `renameDocument` est contrôlé mais l'URI retournée est jetée. Android peut changer l'identifiant et invalider l'ancienne URI. Le nom et l'identité après renommage ne sont pas confirmés ; les références de parent, sélection, presse-papiers et résultats ne sont pas mises à jour par ce moteur. C'est également un obstacle à une publication fiable par renommage de fichier temporaire.

La création, à l'inverse, reliste déjà le parent et retourne l'URI confirmée. Les identifiants sont traités comme opaques dans le moteur ; conserver ce principe.

### 6. Après ES Explorer : diagnostic indépendant

`UsbStoragePicker` distingue grants et volumes, permet une nouvelle sélection et indique qu'un grant ne prouve pas la disponibilité. Il ne valide pas systématiquement le grant sauvegardé avant ouverture. `SafFiles.failure:81–86` journalise LIST/COPY, la classe de l'exception et sa cause immédiate, sans pile ni erreurs supprimées. DELETE/RENAME ne passent pas systématiquement par ce diagnostic.

Il manque les traces exactes avant/après ES Explorer : Android/OEM, autorité et version du fournisseur, état du volume, droits lecture/écriture, validité de la racine et des anciens identifiants, phase, exception et chaîne de causes. Hypothèses distinctes : révocation de droits, remontage, fournisseur indisponible ou identifiants périmés. Aucune n'est confirmée ; aucune réparation spécifique ne doit être promise.

## Plan proposé après validation

1. Introduire des résultats structurés : copie terminée, vérification immédiate réussie, synchronisation demandée/réussie/non disponible/échouée, publication confirmée, suppression confirmée/non confirmée, déplacement incomplet et résidus. Aucun état logiciel ne doit signifier « persistance après reconnexion testée » sans ce test effectif.
2. Isoler l'accès SAF derrière une interface injectable pour simuler les appels, métadonnées, identités, flux, fermetures et descripteurs. Conserver les tests instrumentés du vrai adaptateur Android.
3. Étudier une acquisition explicite de descripteur compatible avec les fournisseurs utilisés. Un seul propriétaire doit fermer chaque ressource ; conserver les erreurs primaires et supprimées. Après vidage des buffers applicatifs, demander la synchronisation lorsque le descripteur le permet, avant fermeture, puis rouvrir pour vérifier. Distinguer fichier réel, pipe/socket, opération non prise en charge et échec réel. Ne pas réessayer en tronquant la destination ni contourner SAF par un chemin supposé.
4. Contrôler taille annoncée destination lorsqu'elle est disponible, nombre d'octets relus et SHA-256. Conserver les compteurs 64 bits. Pour MOVE, maintenir la revérification de source et différer toute destruction selon la politique USB validée.
5. Utiliser si possible un temporaire unique associé à l'opération. Enregistrer immédiatement son identité et son parent ; publication seulement après fermeture et vérification. Contrôler l'URI retournée et relister après renommage. SAF ne fournit pas universellement un renommage atomique sans remplacement : si les garanties manquent ou si un conflit apparaît, conserver la source et le temporaire, signaler l'état, refuser toute substitution destructive.
6. Après suppression, confirmer l'absence par un parent accessible ; invalider les références connues. Pour un dossier, inventorier et produire un bilan partiel si nécessaire, sans lancer de nouvelle suppression lorsque le résultat est indéterminé. Ajouter un état d'attente visible dès le lancement.
7. Tenir un registre des seules créations de l'opération. Nettoyer les fichiers identifiés, puis uniquement les répertoires identifiés et vides après contrôle. Ne jamais supprimer un préexistant ni un ajout externe. En cas de doute, conserver et fournir emplacement, état et action explicite « examiner / supprimer le partiel » avec nouvelle confirmation d'identité.
8. Conserver les résidus et erreurs de nettoyage après annulation ou arrêt du processus. Une création dont l'identité est incertaine doit être signalée comme telle, sans suppression par simple nom. L'annulation reste « arrêt demandé » tant que fermeture et nettoyage ne sont pas terminés.
9. Compléter les diagnostics locaux, protégés et limités aux opérations concernées. Export éventuel sur action utilisateur, avec anonymisation des chemins/identifiants. Examiner le service historique et préserver les causes dans le worker s'ils restent utilisables.

## Validation à réaliser en phase suivante

Les contrôles ci-dessous sont un plan, pas des résultats exécutés aujourd'hui. Aucun test ajouté ni tâche Gradle lancée pendant cette phase préalable. Les résultats bêta 19.2 archivés indiquent 102 tests JVM par variante et compilation AndroidTest, mais ne valent ni validation de ce diagnostic ni test matériel. Les tests Android SAF existants sont déclarés non exécutés dans `validation/beta19.2/RESULTATS.md`.

| Cas simulé | Attendu à vérifier |
|---|---|
| Écriture partielle / interruption / espace épuisé | Pas de succès ; source intacte ; résidu identifié ou absence confirmée |
| Erreur uniquement au flush ou close, avec erreur primaire concurrente | Échec et conservation des causes/suppressed ; toutes les ressources fermées |
| Contenu perdu/corrompu, même taille, taille annoncée fausse/inconnue, lecture refusée | SHA-256 et compteurs contrôlés ; inconnue distincte de zéro ; aucun MOVE terminé |
| Suppression refusée, retour positif sans disparition | Échec ou état non confirmé ; aucun succès affiché |
| Dossier partiellement supprimé puis erreur | Bilan partiel, destination conservée, aucune promesse de source intacte |
| Annulation pendant copie, vérification, publication et nettoyage | Pas de nouvelle destruction source ; résidus exacts ; pas d'annulation propre si nettoyage non confirmé |
| Création réelle puis erreur de confirmation | Reçu conservé si identité certaine ; sinon anomalie explicite sans suppression hasardeuse |
| Renommage refusé, URI changée, nom inattendu ou retour trompeur | Source conservée, temporaire traçable, références actualisées seulement après confirmation |
| Révocation de droits / racine indisponible / ancien ID invalide | Inaccessibilité distincte de l'absence ; aucun nettoyage d'un remplaçant |
| Conflit avant création et pendant publication ; ajout externe au dossier partiel | Aucun écrasement ni suppression d'un élément extérieur à l'opération |
| MOVE copie vérifiée puis suppression refusée | « Copie effectuée, déplacement incomplet » ; deux fichiers conservés si refus sans destruction |
| Descripteur régulier / pipe / erreur distante / sync refusée | Propriété et fermeture correctes, résultat sync explicite, aucune garantie physique inventée |

Le fournisseur synthétique actuel couvre notamment refus d'écriture, perte de contenu, lecture refusée, pipe interrompu, permissions refusées, créations incorrectes et conflits. Il ne constitue pas une couverture déterministe des erreurs uniquement à la fermeture, de la suppression acquittée sans effet, de la suppression récursive partielle et des courses de publication. Les tests JVM bêta 19.2 simulent surtout des moteurs : ils ne valident pas le comportement réel de `SafFiles`.

### Gros fichiers factices

Prévoir des tailles exactes 2 147 483 648 et 2 147 483 649 octets, et une taille supérieure si l'espace disponible le permet. Génération déterministe par blocs, sans `readBytes` ni allocation proportionnelle à la taille ; comparer compteurs 64 bits, taille destination, SHA-256 attendu/source/destination et fermeture des ressources. Vérifier local→SAF, SAF→local et SAF→SAF, fichier seul et dossier, avec annulation à des seuils connus. Contrôler l'espace avant génération ; utiliser seulement des répertoires factices isolés.

Un flux généré JVM est utile pour les seuils mais ne remplace pas une copie matérialisée par le vrai adaptateur. Un fournisseur simulant une perte après « remontage » teste la réaction du logiciel, pas la persistance d'une clé réelle.

Après correction : exécuter `testDebugUnitTest testReleaseUnitTest assembleDebugAndroidTest lintDebug lintRelease`, archiver les nouveaux journaux et résultats, distinguer compilation et exécution AndroidTest. Aucun smartphone à installer. Une exécution instrumentée éventuelle doit utiliser un environnement de test autorisé, pas un appareil personnel implicite.

### Vérification physique distincte

Sur un support de test dédié et avec uniquement les données factices : enregistrer taille/SHA-256 et résultat immédiat, fermer toutes les ressources, éjecter via Android, déconnecter/reconnecter, rouvrir le fournisseur avec références actualisées puis refaire inventaire, tailles et SHA-256. Répéter copie, dossier, suppression et interruption. Conserver la source de référence jusqu'à cette validation. Noter matériel, fournisseur, Android, format du volume et anomalies. Ces essais restent à effectuer dans un cadre autorisé ; aucune persistance physique n'a été testée pendant la phase A.

## Références Android consultées

- [ContentResolver](https://developer.android.com/reference/android/content/ContentResolver) : modes dépendants du fournisseur ; un descripteur peut être un pipe/socket, et `w` ne garantit pas universellement la troncature.
- [ParcelFileDescriptor](https://developer.android.com/reference/android/os/ParcelFileDescriptor) : propriété/fermeture, `canDetectErrors`, `checkError` et leurs limites. Leur disponibilité ne garantit pas qu'un fournisseur accuse toute écriture différée.
- [DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract) : retours de mutations et nouvelle identité possible après renommage.
- [FileDescriptor.sync](https://developer.android.com/reference/java/io/FileDescriptor#sync()) : synchronisation des buffers en aval du descripteur. À interpréter avec les limites du fournisseur et du support ; ne constitue pas ici une preuve de persistance après reconnexion.

**Point d'arrêt :** diagnostic et plan livrés pour validation. Aucun développement de bêta 19.3 engagé.
