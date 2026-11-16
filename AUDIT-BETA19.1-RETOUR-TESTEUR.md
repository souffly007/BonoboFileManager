# Audit prioritaire — retour bêta 19.1

Date : 17 octobre 2026. Phase de diagnostic uniquement. Aucun correctif appliqué.

## Périmètre et valeur des preuves

Analyse du workspace courant : versionName 1.3.0-beta19.1, versionCode 30. Le dépôt comporte de nombreuses modifications préexistantes, suivies et non suivies. HEAD : `0d51b9c05c4e58707b2f75ddff62f7ed71d9b1a0`. Ce HEAD seul ne décrit donc pas le code audité. L'identité binaire entre l'APK du testeur et ces sources n'est pas établie ; relever version et empreinte de son APK avant validation finale.

Les observations du testeur sont prises comme symptômes réels, pas comme preuve de leur cause. Aucun smartphone, émulateur ou support USB physique n'a été utilisé pour cet audit. Les scénarios Android ne sont pas déclarés reproduits. Les tests JVM emploient des moteurs simulés et, pour les vérifications de fichiers, des répertoires temporaires et des données factices. Les tests instrumentés existants sont analysés, pas exécutés.

Aucun accès NAS, installation, signature, publication, changement de version, développement FTP/FTPS ou fiche USB. Seul ce rapport est ajouté aux sources de travail ; Gradle peut produire ses fichiers de build habituels.

## Diagnostic des six problèmes

### 1. Déplacer copie mais conserve la source USB — gravité élevée

**Cause du cas testeur non confirmée.** `MainScreen.handleOption("MOVE")` appelle `MainViewModel.setClipboard(..., true)`, puis `pasteClipboard` crée une requête MOVE. La sélection Couper (`copySelected(true)`) produit le même mode. Il n'y a pas de moteur de suppression différent entre ces deux gestes. Vérifier si « Couper validé » signifie seulement mise en presse-papiers ou déplacement réellement terminé avec disparition de la source.

Parcours : `TransferEngineFactory.local().execute` → `SafFiles.move` → `requireDelete` pour une source SAF → `copy` → `verifyTree` → contrôle d'annulation → phase DELETING → `deleteDocument` SAF ou `FileUtils.deleteRecursively` local.

Une absence de flag DELETE dès le départ doit refuser le déplacement avant copie. Une destination créée puis une source conservée est compatible avec : vérification finale refusée, source modifiée, annulation, perte de permission/disponibilité après précontrôle, ou suppression refusée après copie vérifiée. Ces possibilités ne sont pas départagées sans message et phase. Le flag annoncé ne prouve pas la réussite future de l'opération.

Une suppression refusée après vérification donne FAILED, conserve la destination et laisse l'élément dans le presse-papiers. Une nouvelle tentative au même nom rencontre alors un conflit, sans remplacement. La présentation ne distingue pas structurellement « copie vérifiée, suppression source échouée » des autres échecs : l'information repose sur le texte de l'exception. Si la suppression d'un dossier a commencé, elle peut être partielle ; ne pas promettre une source intégralement conservée dans ce cas.

**À relever :** URI SAF ou chemin local pour la clé, opération demandée, dernière phase, texte exact dans Détails, présence/contenu de la destination et de la source après actualisation des deux panneaux. Aucun passage à COPY silencieux identifié dans ce parcours.

### 2. Téléphone vers USB impossible en double panneau — gravité élevée

**Refus déterministe confirmé dans le code, application au cas réel à confirmer.** `TransferEngineFactory.local().execute` exige un alias local pour toute destination SAF, y compris un fichier simple. Sinon : « Équivalence SAF indéterminée : destination refusée », avant création.

`SafFiles.localAlias` et `StorageEquivalence.local` ne reconnaissent que `com.android.externalstorage.documents` et un volume résolu. Les volumes amovibles ne sont ajoutés à la table que sur API 30 ou supérieure. Sur API 26–29, une destination USB SAF ne peut donc pas obtenir cet alias par cette table. Sur API 30+, un autre fournisseur ou un volume non résolu reste refusé, même avec une autorisation d'écriture valable. Ce refus n'est pas une preuve de refus Android.

`COPY_OTHER` capture le fichier, active l'autre panneau puis colle dans `navigationPath()`. Les callbacks des menus activent explicitement le panneau source. Le parcours est symétrique dans le code ; pas de défaut d'orientation établi par lecture. Le panneau 1 refuse aussi tout collage si une catégorie virtuelle est ouverte. Tester séparément ce refus de navigation et celui d'équivalence.

`UsbStoragePicker` peut ouvrir soit une URI SAF enregistrée, soit un chemin brut du volume lorsque celui-ci est lisible. Le fait que ce chemin soit lisible ne démontre pas qu'il soit inscriptible. Deux essais visuellement identiques peuvent donc emprunter des accès différents.

**Proposition :** enregistrer le motif exact du refus puis revoir la preuve d'équivalence, avec protection contre les alias et les copies de dossiers dans eux-mêmes. Ne pas retirer globalement ce garde-fou pour rendre l'essai passant.

### 3. Corbeille et suppression impossibles — gravité élevée

**Corbeille SAF : cause confirmée.** `FileRepositoryImpl.moveToTrash` refuse explicitement tout `content://`, fichier comme dossier : « La corbeille locale ne prend pas en charge SAF ». `OperationDialog.DeleteDialog` et `MultiDeleteDialog` proposent pourtant cette action sans tenir compte du stockage. Défaut de capacités présentées dans l'interface, pas preuve d'un droit manquant.

**Présentation trompeuse confirmée.** Les callbacks de `MainScreen` affichent immédiatement les Snackbar « Déplacé(s) vers la corbeille » / « supprimé(s) définitivement » après lancement asynchrone des méthodes du ViewModel, sans attendre leur résultat. Un refus peut donc être accompagné d'une annonce de succès. Les erreurs du ViewModel sont affichées séparément dans `state.error`.

| Action | Fichier local | Dossier local | Fichier USB SAF | Dossier USB SAF |
|---|---|---|---|---|
| Corbeille | `Files.move` vers `.bonobo_trash` interne | Même parcours | Refus explicite | Refus explicite |
| Suppression définitive | `FileUtils.deleteRecursively` | Récursion Kotlin | Flag DELETE puis `deleteDocument` | Même appel ; suppression du contenu confiée au fournisseur |

La corbeille locale crée `.bonobo_trash` sans vérifier le retour de `mkdirs`, puis encode le chemin absolu dans le nom de destination. Permissions, déplacement entre volumes si la clé est ouverte en chemin brut, longueur du nom et accès au dossier de corbeille sont des hypothèses d'échec local à tester, pas des causes établies du retour. Une suppression récursive locale peut échouer après avoir déjà supprimé certains enfants.

La suppression définitive SAF est implémentée ; son échec généralisé n'est pas expliqué par le refus de corbeille. Il faut distinguer flag absent, autorisation révoquée/lecture seule, exception du fournisseur, retour false et dossier déjà partiellement supprimé. Une opération de transfert active bloque volontairement les suppressions via `transferBusy`; un résultat terminé encore visible ne les bloque pas.

### 4. Clé inaccessible après ES Explorer — gravité élevée

**Cause inconnue : aucun Logcat de l'incident disponible.** Aucune preuve qu'ES Explorer révoque les droits Bonobo. Ne pas confondre grant persisté, grant effectivement utilisable, volume monté et fournisseur répondant.

`SafFiles.list` transforme une SecurityException en demande de sélection du dossier ; les autres erreurs deviennent un message d'indisponibilité avec cause. `UsbStoragePicker` réutilise un grant enregistré sans sonder sa validité avant ouverture. Il propose une sélection manuelle. `MainViewModel.refreshAfterResume`, `refresh` et `reopenStorageRoot` relisent les dossiers ; aucun mécanisme identifié ne remonte physiquement un volume ni ne répare le fournisseur Android.

Le sélecteur accepte un grant en lecture seule et signale les grants non persistables. Cela suffit pour l'affichage, pas pour créer ou supprimer. Un grant sauvegardé n'est pas une sonde de santé.

Comparer : actualisation → retour à la racine → sélection manuelle du même dossier → fermeture/réouverture de Bonobo → débranchement/rebranchement en dernier recours. Si le sélecteur Android ne voit plus le support ou échoue également, conserver les erreurs système et documenter la limite du fournisseur observée. Sans ces traces, impossible d'affirmer que le débranchement est obligatoire.

### 5. Progression trop rapide après annulation — gravité moyenne, risque élevé de destination partielle

**Réutilisation de compteurs non établie.** `TransferCoordinator.submit` crée un UUID neuf et un nouvel état ; une opération active ou un ancien job encore en terminaison interdit le démarrage. Les compteurs tamponnés sont vidés à l'arrêt ; `TransferRate` est neuf pour chaque exécution. Le test `Beta191Test` couvre précisément annulation puis relance COPY/MOVE avec émission tamponnée.

`SafFiles.copy` ouvre la source depuis le début, sans offset ni reprise automatique. Les octets de progression sont comptés lors des écritures, avant la vérification. Une barre à 100 % n'est pas une opération terminée. Un cache Android/fournisseur ou le cache du support peut expliquer une vitesse différente, mais aucune mesure du cas réel ne le prouve. Un conflit rapide avec une destination restante peut aussi être pris pour une copie rapide : examiner les octets de la nouvelle opération et son résultat.

**Destination finale utilisée directement : confirmé.** Le fichier/dossier est créé sous son nom final avant transfert. Une autre application peut donc voir une destination encore incomplète. Pas de protocole temporaire puis publication atomique. Les conflits existants sont refusés.

Sur erreur ou annulation, le code tente de supprimer un fichier créé ; il ne supprime jamais récursivement le dossier créé, pour éviter d'effacer des ajouts externes. Un dossier partiel peut donc rester. Pour un fichier, le nettoyage peut être refusé par le fournisseur. L'annulation relance son exception sans le message détaillé de nettoyage : le résultat UNCERTAIN demande seulement de contrôler la destination. Une création qui réussit côté fournisseur puis échoue pendant sa confirmation se trouve avant le bloc de nettoyage et peut aussi laisser une entrée.

Intégrité : `copy` ferme la sortie puis relit la destination, compare le nombre d'octets et SHA-256 avec le flux copié. Les répertoires sont contrôlés par noms/types et empreintes récursives. `move` refait une comparaison de l'arbre source/destination avant suppression. Cela protège contre une copie non confirmée ; une source modifiée entre la dernière vérification et la suppression reste une fenêtre de concurrence à étudier. Aucun contrôle logiciel par relecture ne démontre à lui seul la persistance après retrait physique.

### 6. Résultat visible jusqu'à fermeture — gravité faible pour ce comportement

**Comportement prévu confirmé.** `TransferPanel` conserve la carte tant que le résultat n'est pas acquitté ; aucun délai de fermeture automatique. « Fermer » appelle `dismiss`, qui écrit `acknowledged=true` avant de masquer. « Détails » acquitte également un résultat terminé : subtilité UX à valider.

`TransferJournal.read` conserve l'acquittement d'une opération inactive. Aucun lien entre reconnexion USB et remise à zéro de cet acquittement identifié. Les tests `Beta19Test` couvrent relectures répétées et échec d'écriture du journal. Une reconnexion physique avec cycle de vie Android reste à tester.

Un résultat non fermé ne bloque pas un nouveau transfert : les contrôles portent sur l'activité et la terminaison du job. Le nouvel état remplace la carte précédente ; l'historique en mémoire est borné à huit résultats et le disque ne conserve que le dernier résumé. Ce n'est pas un historique exhaustif. Si l'écriture d'acquittement échoue, la carte reste visible avec une explication.

## Matrice à exécuter sans ambiguïté

Chaque cellule ci-dessous doit être déclinée en fichier vide, fichier texte connu, fichier binaire suffisamment long pour annuler, dossier vide et dossier imbriqué. Utiliser uniquement un dossier `BONOBO_AUDIT_<identifiant>` nouvellement créé pour le test et un nom de destination neuf par essai.

| Source → destination | Copier | Couper/coller | Déplacer | Annuler puis recommencer |
|---|---|---|---|---|
| Local → local | Parcours implémenté | MOVE | MOVE | Début réel à zéro ; conflit si entrée restante |
| USB SAF → local | Parcours implémenté ; garde d'alias pour dossiers | MOVE + précontrôle DELETE | Même moteur | Nettoyage fichier tenté ; dossier partiel possible |
| Local → USB SAF | Refus si alias destination inconnu | Même garde + MOVE | Même moteur | Refus d'entrée existante ; pas de reprise |
| USB SAF → USB SAF | Même garde destination ; contrôle descendants | MOVE + DELETE | Même moteur | Même politique ; tester même arbre et arbres distincts |

Statut : matrice tracée dans le code, pas validée matériellement. Répéter chaque combinaison avec source panneau 1/destination panneau 2, puis source panneau 2/destination panneau 1. Exercer « Copier vers l'autre panneau » et le collage manuel séparément. Pour local→local, utiliser deux répertoires locaux distincts ; pour USB→USB, distinguer même clé et deux clés si le matériel le permet. Ajouter le cas panneau 1 sur catégorie virtuelle.

Avant chaque essai : relever OS/API, version, type d'accès (SAF ou brut), droits lecture/écriture, capacités create/delete et disponibilité. Après : relever UUID, phase, octets, résultat par élément, arborescence et SHA-256 des seuls fichiers factices. Pour COPY la source doit rester identique ; pour MOVE elle ne peut disparaître qu'après destination vérifiée. Pour refus ou annulation avant suppression, conserver la source. Vérifier les deux panneaux après actualisation, pas seulement leur affichage en cache.

Annuler pendant préparation, écriture, vérification, puis si possible avant suppression. Attendre l'arrêt effectif avant relance. Vérifier présence de destination, taille et empreinte, puis relancer avec un nom neuf pour mesurer une nouvelle copie. Tester séparément le même nom pour vérifier le conflit. Ne pas effacer aveuglément une destination partielle qui aurait pu recevoir des données d'une autre application.

## Procédure Logcat pour le testeur

À réaliser sur la version déjà installée, avec une clé de test sans données personnelles. Ne pas installer une nouvelle application pour ce diagnostic. Ne pas transmettre de bugreport complet, liste de fichiers, URI complète, UUID de volume, numéro de série, comptes ou captures de dossiers personnels.

1. Noter Android/API, modèle, version Bonobo et les heures des actions. Créer uniquement le dossier factice. Faire un accès Bonobo réussi ; noter si le chemin est SAF ou brut sans communiquer le chemin.
2. Dans Android Studio Logcat, sélectionner le processus Bonobo existant et capturer une courte fenêtre autour de l'échec. Alternative sur le poste du testeur : `adb shell pidof fr.bonobo.filemanager`, puis `adb logcat --pid=<PID> -v threadtime > bonobo-audit-local.txt` ; arrêter avec Ctrl-C. Pour une Debug déjà présente, le package est `fr.bonobo.filemanager.adaptive`. Reprendre le PID si le processus redémarre.
3. Ouvrir le dossier factice dans ES Explorer, revenir dans Bonobo, actualiser puis tester les étapes de récupération indiquées plus haut. Noter les messages de Bonobo/Détails, car les exceptions capturées dans `SafFiles` ne sont pas explicitement journalisées par ce code : Logcat peut ne pas contenir leur pile.
4. Si nécessaire, capturer localement une seconde fenêtre courte pour le fournisseur/système : `adb logcat -v threadtime -s DocumentsProvider ExternalStorageProvider DocumentsUI StorageManagerService vold > usb-systeme-local.txt`. Les tags varient selon le constructeur ; une capture vide ne prouve pas l'absence d'erreur. Ne pas filtrer les seules traces Bonobo pour conclure sur un fournisseur exécuté dans un autre processus.
5. Avant tout partage, extraire manuellement seulement heure relative, classe d'exception, opération, code d'erreur et message expurgé. Remplacer noms/chemins/URI/identifiants par `<SOURCE_TEST>`, `<DEST_TEST>`, `<VOLUME>`. Conserver les fichiers bruts localement. Ne pas partager les sorties brutes de `dumpsys`.

Rechercher notamment SecurityException (autorisation), FileNotFoundException (document absent ou accès impossible, à préciser), IllegalArgumentException (URI/document ou requête refusée), RemoteException/DeadObjectException (communication fournisseur), IOException et codes EACCES/EROFS/ENODEV/EIO. Aucun de ces événements n'a été observé pendant cet audit ; ce sont des indices à collecter. Un code seul ne suffit pas à identifier ES Explorer comme cause.

## Correctifs proposés, soumis à validation

1. **P0 — préserver l'intégrité :** maintenir toute vérification avant suppression. Ajouter des résultats distincts pour copie vérifiée/suppression refusée, annulation et destination partielle ; enregistrer le résultat du nettoyage. Étudier la création temporaire et la publication finale en tenant compte des capacités réelles du fournisseur, des conflits et des ajouts externes. Ne pas annoncer une atomicité universelle SAF.
2. **P1 — USB en écriture :** reproduire le refus d'équivalence sur le vrai moteur et revoir sa portée sans perdre les protections d'alias. Séparer permission, capacité fournisseur et preuve de non-descendance ; ne pas déduire le droit d'écriture d'un accès en lecture.
3. **P1 — suppression et corbeille :** annoncer le succès après résultat effectif ; ne plus proposer la corbeille locale pour SAF. Présenter les capacités de suppression et le refus exact. Vérifier création de corbeille et noms longs locaux. Aucune bascule implicite vers suppression définitive.
4. **P1 — accès après ES :** recueillir les traces puis proposer une nouvelle autorisation si adaptée. Ne pas promettre de réparation d'un fournisseur ou montage système hors du contrôle de l'application.
5. **P2 — progression :** mesurer UUID, octets écrits et vérifiés, temps et phase sur clé réelle avant toute correction de compteur. Distinguer conflit immédiat, copie accélérée par cache et ancien affichage.
6. **P2 — résultat :** conserver l'acquittement durable ; clarifier le fait que Détails acquitte aussi. Valider reconnexion et nouveau transfert sans fermer la carte.

## Tests automatisés à ajouter après validation

- Moteur réel via `TransferEngineFactory` et fournisseur synthétique : matrice local/SAF/SAF, fichier/dossier, API 29 et 30+, alias connu/inconnu. Les tests actuels de `SafFiles` ou du repository contournent le garde-fou de la factory ; leur réussite ne valide pas l'écriture SAF depuis l'interface.
- Compose/ViewModel : deux orientations, COPY_OTHER, copie manuelle, Couper et Déplacer, destination capturée, catégorie virtuelle refusée.
- Suppression : flag DELETE absent avant copie, permission perdue après vérification, deleteDocument false/exception, suppression de dossier partielle. Vérifier distinction de résultat et absence de faux succès Snackbar.
- Corbeille : refus SAF visible sans annonce de succès, local fichier/dossier, dossier inaccessible, échec de création, nom long, chemin brut sur autre volume. Uniquement fixtures isolées.
- Annulation du moteur réel : création, écriture, relecture, entre vérification et suppression ; fichier nettoyé ou nettoyage refusé, dossier partiel, confirmation de création échouée, conflit à la relance, UUID et compteurs neufs. Altération de même taille détectée par SHA-256 et source non supprimée.
- Journal/Compose : fermer, recréer le processus, simuler reprise de cycle de vie et reconnexion, démarrer sans acquitter, échec d'écriture du journal. Vérifier disparition durable et disponibilité d'une opération suivante.

## Scénarios exigeant une vraie clé USB

Tous les cas de montage/fournisseur après ES Explorer ; permissions Android/constructeur persistées et temporaires ; copies dans les deux sens et orientations ; USB→USB ; suppression définitive fichier/dossier ; suppression source après MOVE ; révocation/déconnexion ; débit après annulation avec clé branchée puis reconnectée ; persistance après éjection sûre ; reconnexion avec résultat acquitté et non acquitté. Un fournisseur synthétique ne valide ni le contrôleur USB, ni le système de fichiers de la clé, ni les caches matériels.

## Validation exécutée

Tests ciblés réexécutés le 17 octobre : **42 tests JVM Release, 0 échec, 0 erreur, 0 ignoré**, BUILD SUCCESSFUL. Suites : Beta191Test (2), Beta19Test (5), TransferCoordinatorTest (19), TransferInventoryTest (3), Beta18IntegrityTest (10), ExplorerLayoutTest (3). Rapports XML : `app/build/test-results/testReleaseUnitTest/`.

Commande : `JAVA_HOME=/var/home/Snoopy63/.jdks/jbr-21.0.11 ./gradlew --offline --no-daemon --max-workers=2 testReleaseUnitTest` avec six filtres `--tests fr.bonobo.filemanager.<Suite>`. Première tentative avec un cache temporaire : wrapper Gradle absent, téléchargement bloqué par le sandbox ; aucun test exécuté à cette tentative. Exécution réussie avec le cache habituel et autorisation hors sandbox, en mode offline. Aucun test instrumenté, aucune installation ni tâche de signature exécutés.

Ces tests confirment notamment les règles de compteurs, acquittement, sélection des panneaux et intégrité des fixtures ; ils ne reproduisent pas le fournisseur Android et les six incidents sur clé réelle. Les archives `validation/beta19.1/RESULTATS.md` rapportent 95 tests JVM Debug et Release réussis au 12 octobre ; elles indiquent explicitement que les tests Android étaient seulement compilés. Ces archives ne constituent pas une reproduction actuelle des six incidents.

Attendre la validation de ce rapport et du périmètre de correction avant toute modification de code.
