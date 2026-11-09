# Bonobo Files Explorateur — préparation bêta 17

Version **1.3.0-beta17**, code **27**, préparée le 11 octobre 2026. Travail limité à Bonobo. Les modifications préexistantes du dépôt ont été conservées.

La compilation et les tests JVM sont validés. **La recette Android reste à exécuter : l’émulateur disponible plante avant de devenir accessible.** Aucun résultat de bêta 16 n’est présenté comme un résultat de bêta 17.

## Corbeille : diagnostic et correction

Deux défauts du code expliquent des restaurations refusées :

- La destination décodée devait obligatoirement appartenir au stockage interne principal. Les chemins d’un volume amovible, pourtant enregistrés lors de la mise en corbeille, étaient donc rejetés. Cette restriction est retirée ; la disponibilité et les droits réels de la destination restent contrôlés par le transfert.
- Pour les entrées anciennes sans séparateur `_#_`, le décodage coupait le chemin au premier `_`. Ce caractère appartient à l’alphabet Base64 URL-safe : le résultat pouvait être tronqué. Ces entrées sont désormais considérées ambiguës ; aucun chemin n’est deviné.

Le fichier réellement utilisé comme source est `item.path`, jamais le nom affiché. Il doit être un enfant réel de `.bonobo_trash`, encore accessible et sans lien symbolique. Le format actuel `trash_<chemin Base64 URL-safe>_#_<nom>` est décodé intégralement. Les chemins relatifs, caractères de contrôle, chemins dans la corbeille et destinations déjà présentes sont refusés. Le parent manquant est créé seulement si Android le permet. Les noms affichés des anciennes entrées ambiguës sont conservés afin d’éviter une nouvelle interprétation incorrecte.

`TrashRestorer` réutilise la **copie vérifiée de bêta 16** pour les fichiers et dossiers : fermeture des flux, relecture, comparaison taille/SHA-256 et vérification récursive. Aucun succès n’est affiché avant son retour confirmé. Aucun fichier existant n’est volontairement remplacé.

**La restauration conserve systématiquement la source dans la corbeille, même après succès.** Une suppression récursive n’est pas transactionnelle : elle pourrait échouer après avoir supprimé une partie d’un dossier. Conserver la copie de sécurité garantit ici qu’un échec de restauration ne détruit pas l’entrée. Un message explique qu’elle peut être supprimée explicitement après contrôle. La seconde restauration vers la même destination produit un conflit.

Si l’origine est ambiguë, invalide, inaccessible ou occupée, un dialogue propose **Choisir un dossier** via le sélecteur Android. Le secours fonctionne vers un dossier SAF et utilise les mêmes vérifications ; annuler le sélecteur ou le transfert ne supprime pas la source. Pour les anciennes entrées ambiguës, le nom conservé peut encore comporter le préfixe historique : renommer le fichier récupéré après contrôle.

La mise en corbeille d’une source SAF reste explicitement refusée : une URI de document n’est jamais transformée en chemin local. Cette bêta n’ajoute pas de stockage de métadonnées d’origine SAF ni de corbeille SAF. Le secours SAF concerne les sources locales encore présentes dans la corbeille.

## Sélection multiple

Les actions **Copier la sélection** et **Déplacer la sélection** apparaissent dans la barre de sélection. Le presse-papiers contient une liste stable de fichiers/dossiers, et la sélection est quittée pour permettre de naviguer. Le collage capture le dossier de destination et refuse les vues de catégorie qui ne représentent pas un dossier.

`BatchTransfer` exécute les éléments successivement et affiche numéro/total et nom en cours. Chaque échec est rattaché au nom de l’élément ; les suivants continuent. Les conflits sont refusés, sans écrasement ni renommage automatique. Les éléments réussis sont retirés du presse-papiers ; les éléments en échec ou non démarrés y restent pour une nouvelle destination ou un nouvel essai. Un bouton permet d’annuler le transfert ; l’annulation n’est pas transformée en réussite et arrête le lot.

Les déplacements passent par le mécanisme inchangé de bêta 16 : copie et vérification complètes avant suppression de chaque source. Les fonctions existantes de suppression et renommage groupés sont conservées. L’affichage du presse-papiers indique le nombre d’éléments. Les panneaux ouverts sont relus après l’opération.

## USB après une application tierce

Le code de bêta 16 relit déjà les panneaux à `ON_RESUME`, interroge réellement le fournisseur SAF et propose réessai et nouvelle sélection Android. Ces protections sont conservées, ainsi que les vérifications des transferts. Les URI restent opaques ; aucun identifiant de document n’est reconstruit à partir d’un nom.

Une action **Rouvrir la racine du volume** est ajoutée aux erreurs : elle abandonne les relations de navigation mémorisées et relit la racine du panneau actif. Elle permet de récupérer lorsque le sous-dossier ou son identifiant est devenu invalide mais que la racine est encore accessible. Aucun démontage, remontage ou retrait automatique d’autorisation persistante n’est effectué.

**La cause précise après ES Explorer PRO n’est pas démontrée.** Le montage Android, une permission perdue, un document retiré ou un fournisseur devenu indisponible restent à départager sur le téléphone. Si le gestionnaire de fichiers Android ne peut lui non plus ouvrir la clé, Bonobo ne dispose pas d’une réparation sûre du montage : fermer les applications utilisant la clé, puis effectuer une reconnexion manuelle du support. Si Android y accède, choisir à nouveau son dossier dans Bonobo et accorder les droits nécessaires. Aucune garantie de récupération sans rebranchement n’est annoncée.

## Validation

Commandes utilisées, hors ligne avec les dépendances déjà présentes :

```sh
./gradlew assembleDebug assembleRelease testDebugUnitTest testReleaseUnitTest assembleDebugAndroidTest lintDebug lintRelease --offline
```

Les résultats définitifs et empreintes se trouvent dans [validation/beta17/RESULTATS.md](validation/beta17/RESULTATS.md).

Nouveaux tests JVM : décodage d’origine sur volume amovible, entrées anciennes/invalides, conflits sans écrasement, destination dans la corbeille ou relative, progression/résultats par élément, poursuite après exception et annulation avant l’élément suivant.

Quatre nouveaux tests Android compilés :

1. Restauration de fichier et dossier, conflit, ancienne entrée ambiguë, secours SAF et écriture perdue avec source conservée.
2. Déplacement groupé fichier/dossier avec conflit intermédiaire et source en échec conservée.
3. Annulation pendant une lecture SAF lente au milieu d’un lot ; source courante et source non démarrée conservées.
4. Sélection multiple via la barre d’actions, navigation et copie réelle d’un fichier et d’un dossier.

Les douze tests Android existants restent présents, notamment les permissions SAF réellement révoquées, les documents invalides, le retour d’une application synthétique, les transferts dans les deux sens et les échecs de fournisseurs. **Les seize scénarios ne sont pas exécutés dans cette session.** L’émulateur 37.2.12 a quitté avec le code 139 dans quatre configurations graphiques sur la copie isolée `/tmp/bonobo-beta15-avd`. Les journaux sont conservés. Le smartphone détecté n’a reçu aucune commande d’installation ni d’instrumentation.

Échecs intermédiaires : premier wrapper configuré sur un cache temporaire absent et réseau interdit ; relance sur les caches existants. Première compilation refusée pour deux imports d’icônes manquants, corrigés. Deux anciens tests FTPS locaux ont dépassé leur délai sur une exécution ; la relance complète Debug a réussi. Lint a également rencontré une `ConcurrentModificationException` interne dans `GradleDetector` lors d’un lancement avec plusieurs workers ; la validation a été relancée avec `--max-workers=1`, sans désactiver de détecteur. Aucun test n’a été retiré ou désactivé pour obtenir le résultat final.

## Limites et précautions de livraison

- Aucune clé physique ni ES Explorer PRO n’a été testé ; les défauts propres au fournisseur du téléphone restent possibles. Aucun NAS réel n’a été contacté.
- La progression est par élément, sans estimation du temps ou du nombre d’octets. Le presse-papiers et le lot ne sont pas persistés après arrêt du processus.
- Une restauration réussie conserve sa copie de sécurité dans la corbeille, ce qui consomme temporairement plus d’espace.
- Le format historique du nom de corbeille encode encore le chemin complet : les très longs chemins peuvent dépasser la longueur de nom autorisée lors de la mise en corbeille. L’échec ne déclenche pas une suppression de la source.
- Un fournisseur défaillant ou une annulation peut laisser une destination partielle. Les dossiers partiels ne sont pas supprimés récursivement automatiquement, pour préserver d’éventuels ajouts concurrents.
- Les déplacements groupés héritent des limites de bêta 16 : pas de transaction SAF universelle ; une erreur pendant la suppression d’une source déjà vérifiée peut laisser cette source partiellement supprimée. La copie confirmée reste à destination. La restauration évite cette phase entièrement.
- La vérification confirme les octets visibles par Android, sans garantir leur persistance physique après retrait d’une clé. Les représentations local/SAF d’un même dossier et les modifications concurrentes restent des limites.
- Release est compilée **sans signature**. Aucune clé de signature n’a été créée ou modifiée. Debug conserve la configuration existante, package `fr.bonobo.filemanager.adaptive` ; il ne constitue pas une mise à jour de la Release installée. Ne pas diffuser la Release non signée.

## Procédure courte pour le testeur

Utiliser uniquement des fichiers et dossiers de test, avec une référence gardée ailleurs, une fois une build signée compatible préparée séparément.

1. Mettre un fichier et un dossier imbriqué en corbeille puis restaurer. Contrôler contenu et arborescence. La copie de sécurité doit rester dans la corbeille. Restaurer à nouveau : conflit, destination inchangée.
2. Sur une ancienne entrée refusée, choisir un autre dossier Android. Annuler une première fois : source intacte. Refaire, contrôler la copie récupérée, puis seulement supprimer manuellement l’entrée de corbeille si souhaité.
3. Sélectionner plusieurs fichiers/dossiers, **Copier**, naviguer, coller. Refaire avec **Déplacer** et un conflit parmi les éléments : les autres terminent, la source en conflit reste et son nom figure dans l’erreur/presse-papiers.
4. Annuler un lot contenant un gros fichier. Les éléments terminés peuvent déjà être copiés/déplacés ; les sources de l’élément interrompu et des suivants doivent rester accessibles. Contrôler les éventuelles destinations partielles.
5. Utiliser la clé dans ES Explorer PRO puis revenir. En cas d’erreur : **Réessayer l’accès**, **Rouvrir la racine du volume**, puis nouvelle sélection USB/OTG. Comparer avec le gestionnaire Android. Si les deux échouent, fermer les applications et reconnecter manuellement la clé.

Pour un incident, relever version Android, modèle, format de clé, accès local ou SAF, sens du transfert, étape et message, sans transmettre de données personnelles.

Arrêt après compilation et validation automatisée disponible. Aucun accès NAS, aucune installation sur smartphone, aucune modification d’autres applications, aucune suppression de données personnelles, aucune publication sur souffly.app.
