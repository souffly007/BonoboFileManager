# Bonobo Files Explorateur — bêta 19.3 : fiabilité USB

Version **1.3.0-beta19.3**, versionCode **32**. Phase B autorisée après validation de [l'audit préalable](DIAGNOSTIC-BETA19.3-USB-PHASE-A.md). Release non signée ; voir [résultats et empreinte](validation/beta19.3/RESULTATS.md).

## Écriture, vérification et publication SAF

Les fichiers destinés à SAF passent d'abord par un document temporaire unique `.bonobo-<UUID>-<suffixe>`. La création contrôle le parent, le conflit de nom, l'identité retournée, son absence dans l'inventaire préalable, puis le nom, le type et l'unicité dans le parent après création. Un retour correspondant à un préexistant connu n'autorise ni écriture ni nettoyage. Une intention est enregistrée avant l'appel de création, puis un reçu d'identité avant la requête de confirmation : le fournisseur peut créer puis échouer sans retourner d'URI. Une création non confirmée reste à examiner dans le parent indiqué et n'est jamais supprimée automatiquement par son seul nom.

`VerifiedStreamCopy` conserve un tampon de 64 Kio, compte les octets sur 64 bits, calcule SHA-256, contrôle la taille source lorsqu'elle est connue et ferme les flux avec `use`. Les erreurs de lecture, écriture, vidage, synchronisation et fermeture empêchent le succès. Les exceptions secondaires de fermeture restent attachées à l'erreur primaire. Après fermeture, le document est rouvert et son nombre d'octets, SHA-256 et sa taille annoncée si disponible sont contrôlés. Une taille inconnue n'est pas assimilée à zéro.

La sortie SAF est acquise par `openFileDescriptor` et confiée à un unique `AutoCloseOutputStream`. Le moteur demande `FileDescriptor.sync()` lorsqu'un `fstat` identifie un fichier régulier. Il ne synchronise pas un pipe/socket ; une erreur de synchronisation d'un fichier régulier fait échouer la copie. Les erreurs distantes détectables sont contrôlées avant fermeture ; une notification ultérieure ou non exposée par le fournisseur reste hors de cette garantie. Pour une destination locale, le canal est ouvert sans suivre les liens, son identité est contrôlée avant écriture et `force(true)` est demandé. **Aucun de ces appels ne prouve une persistance après déconnexion.**

La publication utilise ensuite `createDocument` pour réserver une nouvelle identité sous le nom final, puis recopie le temporaire vérifié dans cette nouvelle destination, ferme et vérifie à nouveau. Une comparaison supplémentaire source/destination précède le retour. Aucun renommage potentiellement remplaçant n'est employé pour publier. Un conflit, un refus, une identité préexistante, une taille ou une empreinte incorrecte empêchent le succès.

Cette publication est **progressive, non atomique** : le nom final peut être visible pendant la seconde écriture. Elle suppose le respect du contrat de création de nouveaux documents par le fournisseur. Elle demande deux écritures et plusieurs lectures, et temporaire + destination finale peuvent occuper ensemble deux fois la taille du fichier courant. La seconde écriture apparaît sous « Publication » avec une indication d'activité ; elle n'ajoute pas une seconde fois les octets aux compteurs de copie.

Les dossiers destination sont créés et suivis, puis chaque fichier est publié séparément. Il n'y a pas de publication atomique d'arbre ni de dossier temporaire racine à supprimer récursivement. Les inventaires, refus de cycles, de liens locaux et d'alias détectés vers la source restent en place.

## Suppression

`SafMutations` distingue **confirmée**, **refusée** et **indéterminée**. Le parent doit être accessible et contenir l'identité attendue avant tout appel destructif. Le flag DELETE et le retour SAF restent contrôlés. Après acquittement, le parent est relu : ni l'identité ni une entrée au même nom ne doivent subsister. Un parent inaccessible, un acquittement sans disparition ou une absence ambiguë ne sont jamais un succès.

Lorsque le parent n'est pas déjà connu, `findDocumentPath` peut fournir des identifiants de parents à vérifier par listing. L'application ne construit pas un parent en découpant un identifiant opaque. Si le fournisseur ne donne pas de parent vérifiable, elle refuse la suppression et demande une actualisation.

La suppression explicite d'un dossier reste exécutée par le fournisseur et peut être partielle si celui-ci échoue. Un refus ne signifie donc pas que tous les enfants sont intacts. Le bilan le rappelle. Un état observable affiche « Suppression en cours · attente de confirmation ». Le lot vérifie aussi l'annulation avant chaque nouvel élément. La corbeille SAF et les sélections mixtes vers la corbeille restent refusées sans substitution destructive.

## Renommage et identités

L'adaptateur récupère l'URI retournée, refuse une identité préexistante connue, met à jour les références internes et vérifie nom/type/unicité/ancienne identité dans le parent. La référence retournée reste conservée même si la confirmation échoue : pas de nouvelle tentative sur l'ancien identifiant. Les alias de références ne doivent pas former de cycle.

**Restriction de cette livraison : le renommage SAF est refusé en production.** Le flag RENAME ne garantit pas un renommage atomique sans remplacement ; aucun fournisseur réel n'a été qualifié pour cette propriété. Le document reste intact et le refus est explicite. Le chemin de renommage avec nouvelle URI est testé par interface simulée et compilé contre le fournisseur privé de tests, seul environnement où cette capacité est explicitement activée. Aucun contournement automatique ni liste de confiance fondée sur un simple nom de fournisseur n'a été ajouté.

## Annulation, nettoyage et résolution

Chaque transfert enregistre ses créations dans `filesDir/usb-operation-artifacts`, avec URI/chemin, parent, nom, type, identité, statut de confirmation et rôle temporaire/publication. Ce journal est privé et synchronisé ; il ne contient pas de contenu utilisateur et n'est jamais rejoué pour supprimer après redémarrage.

Le nettoyage suit les reçus en ordre inverse. Il exige une création confirmée et une correspondance d'identité, chemin, nom et type. Il vérifie aussi la disparition après suppression. Une entrée au même nom avec une autre identité, une perte de droits ou une création non confirmée reste signalée. Aucun nettoyage par recherche de nom de remplacement n'est autorisé.

**Aucun dossier SAF n'est automatiquement supprimé par le nettoyage**, même s'il semble vide : `deleteDocument` pourrait supprimer un ajout externe apparu entre le listing et l'appel. Les répertoires locaux ne peuvent être retirés que vides via `Files.delete`, sans récursion. Un dossier SAF laissé par une erreur reste un résidu explicite.

Le nettoyage d'une copie annulée s'exécute en contexte non annulable pour terminer la fermeture et les contrôles. L'annulation reste coopérative : un appel fournisseur bloqué peut retarder l'arrêt. Le résultat distingue « sans résidu suivi » et « avec résidu », donne les emplacements et conserve les causes. Il ne garantit pas l'absence d'un document que le fournisseur aurait créé sans jamais en retourner l'identité.

Un panneau « créations à contrôler » reste disponible après un échec et après redémarrage, en distinguant les identités connues et les créations non confirmées. « Examiner le dossier » ouvre le parent ; l'utilisateur peut afficher les fichiers cachés, examiner puis utiliser la suppression explicite avec confirmation. « Retirer ce signalement » retire seulement le reçu et conserve le fichier. Aucune reprise ni suppression automatique, aucun écrasement silencieux.

## Déplacements

`VerifiedMove` exige la vérification finale avant toute suppression. Un échec de vérification conserve la source. Une suppression refusée ou indéterminée laisse la destination et produit **« Copie effectuée, déplacement incomplet »**, sans DONE ni comptabilisation comme déplacement terminé. Si un fournisseur a déjà supprimé une partie d'un dossier avant de refuser, l'application le signale ; elle ne peut reconstituer une transaction absente du fournisseur.

**Mode prudent : toute destination SAF, tout volume local amovible et tout volume non identifiable conserve la source.** La copie peut être vérifiée immédiatement, mais le déplacement reste incomplet car sa persistance après reconnexion n'a pas été testée. Cela inclut les destinations SAF correspondant au stockage interne : la restriction est volontairement plus large que le seul USB. Aucune finalisation destructive automatique après reconnexion n'est ajoutée.

L'utilisateur doit conserver la source jusqu'aux tests physiques, puis décider explicitement d'une éventuelle suppression. Une nouvelle tentative au même nom rencontre un conflit, sans écrasement. Pour une destination locale reconnue non amovible, le parcours de suppression après vérification reste disponible.

## Interface et anciens points d'entrée

Phases visibles : copie, vérification, publication, suppression, terminé, annulation et erreur/résultat indéterminé. Les phases longues sans mesure fiable gardent une barre indéterminée. Le résultat rappelle la vérification immédiate et l'absence de test de persistance. Les détails conservent les emplacements et les erreurs d'annulation.

Le service historique, sans appelant identifié dans le parcours courant, n'ignore plus les `Result` : il signale une erreur au lieu de la laisser disparaître. Le worker conserve un diagnostic borné dans ses données d'échec. Le moteur de transfert géré reste le parcours principal.

## Tests et limites

Les 21 tests de `Beta193SafReliabilityTest` exercent les mêmes composants purs que l'adaptateur : écriture acceptant un bloc de 64 Kio puis échouant, flush/sync/close en erreur, erreurs supprimées, métadonnées et SHA incorrects, fermeture lors d'une annulation réelle de coroutine, publication refusée, création effective puis exception sans retour d'identité, conflit/identité préexistante, URI de renommage modifiée, suppression refusée/indéterminée/partielle, résidus persistants et acquittement non destructif, absence de suppression prématurée et déplacement incomplet.

Le test de **2 147 483 649 octets** génère les données par blocs, vérifie les compteurs et les empreintes avec un puits synthétique, puis une relecture synthétique. Il ne matérialise pas un fichier de 2 Gio et ne simule pas la durabilité matérielle. Un autre test matérialise une petite copie factice et la rouvre. Les tests Android du fournisseur privé sont enrichis et adaptés au maintien prudent des sources ; leur compilation n'est pas leur exécution.

Limites restantes : matériel/OEM et fournisseurs non qualifiés, caches, disponibilité des permissions et identifiants après remontage, comportement exact des notifications distantes et des appels bloqués, coût de la publication double et des journaux par création. Les contrôles ne créent pas de transaction globale et ne peuvent éliminer toutes les courses avec des modifications externes ou un fournisseur non conforme. Le diagnostic après ES Explorer reste séparé : aucune solution spécifique revendiquée sans traces et reproduction.

Les résultats définitifs sont dans [RESULTATS.md](validation/beta19.3/RESULTATS.md). Les tests physiques restent à faire suivant [la procédure dédiée](validation/beta19.3/PROCEDURE-TESTS-PHYSIQUES.md). Aucun NAS réel, smartphone installé, publication, autre application, FTP/FTPS ou fiche USB. Les clés de signature ne sont pas modifiées ; la Release livrée reste non signée. Travail arrêté après validation automatisée et préparation des artefacts.
