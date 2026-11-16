# Audit de la bêta 17 — Bonobo Files Explorateur

Audit du 11 octobre 2026. Version conservée : **1.3.0-beta17, code 27**. Aucun changement du code applicatif, des tests, des dépendances ou des clés. Les APK ES Explorer n’ont été ni ouverts ni utilisés : aucune intégration de code, ressource ou bibliothèque issue de ces APK.

## Conclusion

La bêta 17 comporte une restauration par copie vérifiée qui conserve la source, et des transferts local/SAF groupés utilisant les protections de bêta 16. **Elle ne peut pas être considérée comme entièrement validée sur Android** : ses 16 tests instrumentés restent bloqués par un plantage natif de l’émulateur avant disponibilité ADB.

Les suites JVM ont été réellement réexécutées : **52/52 Debug et 52/52 Release**. Les empreintes des deux APK archivés correspondent au rapport de livraison. Les preuves et les scénarios Android impossibles à exécuter sont détaillés dans [les résultats de cet audit](validation/beta17-audit/RESULTATS.md). Les succès de compilation/Lint de la préparation initiale ne sont pas confondus avec de nouvelles exécutions de Lint.

Avant de développer le gestionnaire commun, traiter les lacunes de sécurité et de cohérence ci-dessous. Il s’agit de recommandations, **pas de corrections déjà appliquées**.

## 1. Corbeille : comportement observé

Sources examinées : `util/TrashPaths.kt`, `util/TrashRestorer.kt`, `util/SafFiles.kt`, `data/repository/FileRepositoryImpl.kt`, `presentation/ui/main/MainViewModel.kt` et `MainScreen.kt` sous `app/src/main/java/fr/bonobo/filemanager/`.

| Sujet | État réel du code | Conséquence |
| --- | --- | --- |
| Origine enregistrée | Chemin local absolu encodé en Base64 URL-safe dans le nom `trash_<origine>_#_<nom>` | Pas de métadonnées séparées, de version de format ou de statut de restauration |
| Anciennes entrées | Sans `_#_`, restauration automatique refusée | Dossier choisi proposé ; chemin historique ambigu jamais deviné |
| Source réelle | `item.path`, enfant canonique direct de la corbeille, accessible et sans lien symbolique | Le nom affiché ne sert pas de chemin source |
| Destination d’origine | Chemin canonique, absolu, hors corbeille locale ; nom existant refusé | Les volumes amovibles ne sont plus systématiquement exclus |
| Parent disparu | `mkdirs()` tenté | Aucun contrôle explicite de l’identité du volume attendu avant recréation du parent |
| Dossier choisi | URI SAF du sélecteur Android, copie vérifiée | Le contrôle « hors corbeille » ne s’applique qu’aux destinations locales |
| Vérification | SHA-256/taille après fermeture, relecture des fichiers et comparaison récursive pour les dossiers | Succès seulement après confirmation visible via Android |
| Source après succès | Toujours conservée | Sécurité en cas d’échec, mais consommation d’espace et répétition possible |
| Conflit | Refus sans remplacement demandé ; création exclusive locale et contrôle du document SAF créé | Pas de rapprochement avec une restauration antérieure |
| Annulation | Moteur coopérativement annulable | Pas de bouton ni de Job de restauration exposé dans l’interface |
| Mise en corbeille SAF | Refus explicite | Le secours SAF restaure une source locale ; aucune corbeille SAF native |

### Lacunes à traiter, classées par priorité

**P0 — Empêcher un dossier restauré dans sa propre corbeille via une autre représentation.** `restoreTo` refuse un parent local sous `.bonobo_trash`, mais laisse passer un parent SAF. Le moteur ne compare les descendants que local→local et SAF→SAF : un alias local→SAF du même dossier n’est pas couvert. Risque identifié par lecture du code, non reproduit sur Android. Il faut un contrôle d’appartenance entre représentations connues ; si un cas de dossier reste ambigu, le refuser plutôt que parcourir aveuglément une destination ajoutée dans la source.

**P0 — Auditer également la mise en corbeille depuis un autre volume.** `moveToTrash` utilise encore `Files.move` vers la corbeille principale, sans la relecture SHA-256 du moteur sécurisé. Ce n’est pas la voie vérifiée de bêta 16. Le fonctionnement des fichiers et dossiers entre systèmes de fichiers n’est pas validé ici ; ne pas annoncer une garantie de transfert inter-volume pour cette opération.

**P1 — Rendre les restaurations répétées cohérentes.** Vers la même destination, le conflit protège le fichier existant. Vers deux dossiers différents, la même entrée peut être copiée plusieurs fois. Aucun journal ne mémorise la destination confirmée. Les noms des entrées doublonnées lors de la mise en corbeille reçoivent un suffixe, mais l’origine encodée reste identique : ces entrées se concurrencent à la restauration automatique.

**P1 — Protéger la portée de l’origine.** Le contrôle actuel accepte tout chemin absolu canonique hors corbeille accessible à l’application. Une entrée placée dans cette corbeille partagée n’est pas une preuve fiable de provenance. Une future origine doit être liée à un stockage autorisé et à des métadonnées privées ; les entrées anciennes non fiables doivent rester candidates au secours manuel, sans écriture automatique dans un emplacement interne arbitraire de l’application.

**P1 — Distinguer parent manquant et volume disparu.** Valider le stockage attendu avant toute création. Recréer un sous-dossier sur un volume confirmé peut être proposé ; recréer un chemin sous un ancien point de montage ne démontre pas que le bon support est revenu.

**P1 — Éviter des opérations concurrentes sur la même entrée.** La restauration n’est pas enregistrée comme opération active. Un utilisateur peut déclencher plusieurs restaurations, supprimer l’entrée ou vider la corbeille pendant une lecture. La conservation systématique de la source ne protège pas contre cette suppression indépendante. Verrouiller l’entrée et demander confirmation explicite avant toute suppression définitive. Le bouton « vider » appelle actuellement directement `emptyTrash`.

**P2 — Remplacer les noms contenant le chemin complet.** Un identifiant court avec métadonnées séparées évitera la limite de longueur du nom, préservera le nom original exact et permettra de distinguer erreur, copie vérifiée et nettoyage demandé. Migration non destructive, ancienne entrée conservée si lecture impossible.

### Comportement final proposé

1. **Par défaut : restaurer par copie vérifiée, garder la copie de sécurité.** Afficher précisément « copie restaurée et vérifiée, original conservé dans la corbeille ». Aucune suppression automatique ajoutée.
2. Mémoriser un identifiant d’entrée, origine typée, nom original, destination effective et état `NON_RESTAURE / EN_COURS / RESTAURE_VERIFIE / INCERTAIN`. Enregistrer le reçu seulement après contrôle du contenu et de la présence au parent ; ces métadonnées privées ne vont pas dans Logcat.
3. Au nouvel essai, vérifier la destination mémorisée. Si elle correspond toujours, afficher « déjà restauré » sans nouvelle copie. Si elle a disparu ou changé, garder la source et proposer un dossier choisi. Une seconde copie volontaire exige une action explicite.
4. En cas de conflit, proposer annuler, autre dossier ou autre nom explicitement choisi. Aucun écrasement, aucune fusion de dossiers et aucune interprétation d’un simple nom identique comme preuve de succès.
5. Origine ambiguë, volume absent, URI invalide ou permission insuffisante : proposer un secours si la source est accessible. Si la source ne l’est plus, expliquer l’impossibilité sans l’enlever de la liste par une suppression.
6. Ajouter progression, annulation et exclusion des suppressions concurrentes. Une annulation conserve la source et signale les sorties partielles ; elle n’annonce pas un succès.
7. « Retirer la copie de sécurité » reste une suppression définitive **demandée et confirmée par l’utilisateur**, après nouvelle vérification de la destination et de l’identité de l’entrée. Ne pas promettre une suppression récursive transactionnelle : un échec de nettoyage doit rester visible et ne doit pas lancer de nettoyage global.

## 2. USB : état réel et diagnostic proposé

Le code relit les panneaux à `ON_RESUME` et reçoit les événements de montage/retrait. `UsbStoragePicker` interroge les volumes amovibles et les autorisations SAF persistantes. Il privilégie un répertoire local lisible ou ouvre le sélecteur Android. Les permissions temporaires sont annoncées, pas supprimées automatiquement.

`SafFiles` interroge réellement le fournisseur. Il distingue dans son texte une chaîne de causes contenant `SecurityException`, mais regroupe les autres erreurs. Une `SecurityException` ne prouve pas à elle seule qu’une permission a été révoquée : le fournisseur peut refuser l’opération malgré un octroi Android.

« Rouvrir la racine du volume » vide `MainViewModel.safParents` et relit la racine mémorisée. Cela ne récupère pas une racine invalide, ne renouvelle pas une autorisation et ne vide pas `SafFiles.parents`, utilisé pour le renommage. La catégorie du panneau principal peut également empêcher certaines relectures en fin de transfert vers le second panneau. Ces caches et les tokens de chargement doivent être coordonnés.

### Matrice de diagnostic à implémenter ultérieurement

| Dimension indépendante | Observation prévue | Conclusion permise et action |
| --- | --- | --- |
| Support physique | Événement USB et périphérique de classe stockage, si exposés par Android | Présent/absent seulement si observation fiable ; sinon **inconnu**. Un volume logique absent ne prouve pas un retrait physique |
| Volume logique | `StorageManager.storageVolumes`, état exact `StorageVolume.state` | Monté, monté en lecture seule, non monté, en vérification, retiré ou état inconnu. Aucun remount |
| Chemin local | État du volume puis lecture du parent ; permission de stockage effective | Lecture possible ou refusée ; ne pas déduire le montage d’un `canRead()` isolé |
| Autorisation SAF | Octroi temporaire effectif, octroi persistant lu/écrit, état précédemment confirmé | Accordée, non accordée, perdue depuis confirmation ou inconnue ; permission persistante absente ≠ accès forcément refusé |
| URI de document | Interrogation du document puis du parent/racine, avec permission encore effective | Document introuvable alors que racine répond : identifiant invalide/disparu probable ; proposer racine ou nouvelle sélection |
| Cache | Réinterrogation réelle et comparaison au dernier chargement/token | Cache périmé uniquement si le fournisseur répond avec un résultat différent ; invalider par panneau/arbre |
| Fournisseur | Réponse nulle, erreur d’E/S, exception distante, absence de réponse dans le délai | Erreur de fournisseur ou accès indéterminé, pas diagnostic arbitraire « clé retirée » |

Les sondes restent en lecture seule : pas de fichier créé pour tester les droits, pas de suppression ni de modification des octrois. Utiliser des délais bornés et la cancellation disponible du fournisseur ; si la sonde expire, rapporter « indéterminé » plutôt que bloquer l’interface.

Les dimensions restent séparées : une clé peut être présente et non montée, ou montée avec une URI invalide. Un fournisseur SAF n’est pas nécessairement un USB et il n’existe pas de correspondance universelle entre arbre SAF et volume physique.

Les API Android imposent ces limites : [StorageVolume](https://developer.android.com/reference/android/os/storage/StorageVolume) expose l’état logique ; un octroi SAF persistant ne maintient pas l’accès à un document déplacé ou supprimé selon la [documentation SAF](https://developer.android.com/training/data-storage/shared/documents-files).

### Logcat sans données personnelles

Prévoir une classe `StorageDiagnostics`, non ajoutée dans cet audit, avec tags fixes `BonoboStorage` / `BonoboTransfer`. Événements structurés : `RESUME`, `MEDIA_EVENT`, `ROOT_PROBE`, `GRANT_CHECK`, `QUERY_RESULT`, `RECOVERY_REQUEST`, `TRANSFER_RESULT`.

Champs autorisés : identifiant aléatoire de session, panneau, type LOCAL/SAF/REMOTE, code d’état, permissions lecture/écriture booléennes, durée, numéro de tentative, catégorie d’exception autorisée et nombre d’éléments. Échantillonnage et durée de conservation limités ; diagnostics détaillés activés volontairement.

**Interdits dans les logs :** chemin, nom de fichier, URI, identifiant de document, UUID ou nom du volume, nom d’utilisateur, hôte/IP/partage réseau, identifiants, mot de passe, contenu, hash de contenu, extras d’Intent et messages bruts d’exception. Pas de `Log.e(..., exception)` sur une exception pouvant contenir ces valeurs. Un hash stable d’URI n’est pas une anonymisation suffisante ; utiliser des tokens aléatoires limités à la session.

Exemple de format proposé : `event=QUERY_RESULT session=R7 panel=2 backend=SAF result=DOCUMENT_UNAVAILABLE grant_read=true elapsed_ms=28`.

Collecte de recette ciblée, sans vider le journal ni exporter tout Logcat : `adb -s <appareil explicitement choisi> logcat -v threadtime -s BonoboStorage:I BonoboTransfer:I`. Cette commande est une procédure future ; aucune commande n’a été envoyée au téléphone durant cet audit. Les nouveaux extraits de logs d’émulateur archivés sont filtrés : pas de clé publique ADB ni de chemin utilisateur. Les anciens logs de bêta 17 ne doivent pas être partagés bruts sans nettoyage.

### Limites de récupération

Aucune cause précise après ES Explorer PRO n’est démontrée. Aucun accès exclusif imposé par une application tierce ne peut être promis comme résolu par Bonobo. Séquence manuelle : réessayer, rouvrir la racine, choisir de nouveau le dossier Android et contrôler l’accès dans le gestionnaire système. Si ce dernier échoue aussi, fermer les applications utilisant la clé puis reconnecter manuellement le support. Ne pas retirer automatiquement les octrois, démonter, remonter ou modifier les autres applications.

## 3. Émulateur et validation Android

Même émulateur **37.2.12**, même image API 35 x86_64 et même copie d’AVD isolée que précédemment. KVM est déclaré utilisable par `-accel-check`. Aucun dump exploitable n’est disponible via `coredumpctl` pour le QEMU concerné. `qemu-img check`, sans option de réparation, ne trouve pas d’erreur structurelle dans le disque QCOW2 ; cela ne vérifie pas le système de fichiers invité.

Deux nouveaux essais : CPU logiciel TCG sans accélération, puis AVD en lecture seule avec GLDirectMem/GLDMA désactivés. Tous deux terminent avec le code 139 avant disponibilité ADB. Les quatre essais historiques graphiques ont le même résultat. Le warning de plage mémoire protégée apparaît aussi dans le log bêta 16 qui a **réussi à démarrer** : il n’est donc pas une preuve de cause. Le seul diagnostic établi est un plantage natif de l’environnement d’émulation, avant lancement des tests, pas un crash de Bonobo.

L’AVD original n’est pas réinitialisé, aucun `wipe-data`, aucune réparation de disque ni suppression de snapshot effectués. L’essai normal peut écrire les journaux/disques habituels de la copie isolée ; le second essai est lancé en lecture seule. Sources et version inchangées, preuve dans [INTEGRITE.json](validation/beta17-audit/INTEGRITE.json).

Étapes supplémentaires possibles, non exécutées : comparer le démarrage d’une **nouvelle copie isolée** réutilisant les paramètres API 35 avec un nouveau disque invité, puis essayer un binaire officiel d’émulateur séparé sous `/tmp` si nécessaire. Ne pas remplacer le SDK partagé ni effacer l’AVD actuel. Les options utilisées sont décrites dans la [documentation de lancement de l’émulateur](https://developer.android.com/studio/run/emulator-commandline).

## 4. Décision technique

Avant bêta 18 : valider les seize tests Android sur un environnement fonctionnel et ajouter les tests des lacunes P0/P1. Préserver les protections local/SAF de bêta 16. Les déplacements réseau actuels ne doivent pas être assimilés à des déplacements avec relecture vérifiée.

La [proposition d’architecture bêta 18](PLAN-BETA18.md) décrit la réutilisation des moteurs, les étapes, les fichiers prioritaires et la stratégie de tests. **Aucune implémentation bêta 18 n’a commencé.** Aucun NAS, aucune installation sur téléphone, aucune modification d’un autre projet, aucune publication.
