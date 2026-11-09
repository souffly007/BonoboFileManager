# Bêta 19.3 — procédure USB physique restant à exécuter

**Aucun résultat de cette procédure n'est acquis par la compilation ou les tests simulés.** L'assistant n'a ni installé sur smartphone ni manipulé une clé réelle. La préparation de l'environnement de test appartient à l'équipe autorisée ; cette procédure ne demande aucune utilisation de données personnelles.

## Préparation

Utiliser une clé réservée aux essais et un dossier factice unique par série. Conserver une source factice indépendante sur un stockage de référence. Ne jamais formater ni nettoyer globalement un volume depuis cette procédure. Ne pas utiliser le NAS réel.

Préparer par génération en flux : un petit fichier, des fichiers de **2 147 483 648** et **2 147 483 649 octets**, et une arborescence avec sous-dossiers, fichiers vides et plusieurs fichiers différents. Ne pas charger les gros fichiers en mémoire. Calculer et conserver taille exacte et SHA-256 de chaque source ; pour l'arbre, garder la liste complète des chemins relatifs, types, tailles et empreintes. Vérifier que le support et son espace libre permettent le fichier et sa publication avec un temporaire supplémentaire.

Noter Android/OEM, version de l'application, matériel du support et de l'adaptateur, autorité/version du fournisseur, voie d'accès SAF ou chemin local, droits lecture/écriture et état du montage. Ne partager que des diagnostics anonymisés.

## Copie et publication

1. Copier local → SAF dans un dossier vide de test. Observer copie, vérification, publication, vérification finale puis résultat. Une barre de copie à 100 % ne signifie pas terminé.
2. Comparer source/destination : nombre d'octets, taille annoncée, taille réellement lue et SHA-256. Pour les dossiers, comparer aussi inventaire et types. Vérifier l'absence de temporaire restant après succès ; afficher les fichiers cachés.
3. Répéter SAF → local et SAF → SAF entre dossiers de test distincts, fichier seul puis arborescence. Conserver les sources de référence.
4. Créer un fichier destination factice au même nom, avec un contenu distinct dont l'empreinte est connue. Réessayer : refus de conflit, aucun écrasement, empreintes source et préexistant inchangées.
5. Tester un accès en lecture seule si l'environnement le permet sans changer les données hors essai. Un refus ou une vérification impossible doit rester un échec, avec source conservée.

## Éjection et reconnexion : preuve distincte obligatoire

1. Consigner le résultat **immédiat**, même si tout semble correct. Attendre le retour de toutes les opérations ; fermer les lecteurs/éditeurs et toute ressource ouverte sur la clé.
2. Demander l'éjection sûre via Android et attendre sa confirmation. Si elle est refusée ou indisponible, consigner l'échec ; ne pas qualifier le test d'éjection sûre réussie.
3. Déconnecter puis reconnecter le support. Actualiser la liste et les références ; si nécessaire, sélectionner à nouveau le dossier dans le sélecteur Android. Ne pas traiter un ancien identifiant inaccessible comme un fichier disparu.
4. Relire **intégralement** chaque destination et recalculer tailles et SHA-256 depuis les références actualisées. Pour l'arbre, refaire l'inventaire complet, pas uniquement sa racine.
5. Comparer aux empreintes de référence gardées avant transfert. Vérifier séparément la présence des sources et des éventuels fichiers préexistants de conflit.
6. Enregistrer « persistance observée après reconnexion » seulement pour les fichiers, le matériel et le cycle effectivement testés. Ni flush, ni fsync, ni succès immédiat ne remplace cette étape. Toute différence bloque la qualification de fiabilité ; conserver les sources et les traces.

## Annulation et résidus

Annuler un gros fichier pendant copie, puis une autre tentative pendant publication, dans des dossiers uniques. Attendre l'arrêt réel. Vérifier que la source est intacte, que les préexistants n'ont pas changé et que tout résidu est signalé avec son emplacement. Une annulation de dossier peut laisser un dossier SAF vide ou partiel : aucun nettoyage récursif automatique n'est attendu.

En cas de résidu, fermer puis rouvrir l'application : le panneau doit toujours permettre « Examiner le dossier ». Afficher les fichiers cachés. Comparer identité/emplacement/contenu avant toute suppression manuelle. Tester « Retirer ce signalement » sur un résidu factice : le document doit rester présent. Tester ensuite la suppression explicite sur un résidu dont l'appartenance à la série est certaine.

Une déconnexion brutale pendant une copie ne doit être essayée que sur cette clé sacrifiable et ces données factices. Ce cas reste séparé de l'éjection sûre ; il peut endommager le système de fichiers. Après reconnexion, contrôler toutes les sources, préexistants et résidus, sans relancer automatiquement la copie.

## Suppression et déplacement

Supprimer un fichier factice puis un dossier factice contenant plusieurs niveaux. Attendre le bilan et confirmer l'absence dans un parent encore accessible. Répéter le contrôle d'absence après éjection sûre et reconnexion. Un refus ou résultat indéterminé ne doit jamais être affiché comme un succès. La corbeille SAF reste indisponible.

Un MOVE vers SAF ou vers un volume amovible doit produire **« Copie effectuée, déplacement incomplet »** avec copie immédiatement vérifiée et source conservée. Vérifier les deux copies après reconnexion. Ne supprimer la source de référence que sur décision explicite de l'équipe après ces contrôles ; la bêta n'automatise pas cette décision.

Le renommage SAF doit être refusé explicitement en production, sans altérer le document, car aucun fournisseur réel n'est qualifié pour l'absence de remplacement. Le test d'URI modifiée appartient aux tests simulés/instrumentés ; il ne justifie pas de lever ce refus sur une clé réelle.

## Fiche de résultat par essai

| Champ | À renseigner |
|---|---|
| Série, version, environnement | Identifiant factice et configuration |
| Parcours / opération | Local→SAF, SAF→local, SAF→SAF ; copie/déplacement/suppression |
| Sources | Inventaire, octets et SHA-256 de référence |
| Résultat immédiat | Phases, résultat, tailles lues/annoncées, SHA-256 |
| Arrêt / résidus | État réel, emplacement, action manuelle éventuelle |
| Éjection | Demandée, confirmée/refusée, applications fermées |
| Reconnexion | Montage, droits et validité des références actualisées |
| Résultat après reconnexion | Inventaire complet, octets, SHA-256, absence des supprimés |
| Conclusion | Immédiat uniquement / persistance observée / anomalie / indéterminé |

Le cas ES Explorer nécessite une série distincte avec exceptions exactes et états de permissions/montage/identifiants avant et après. Ne pas conclure à une correction spécifique de cette bêta sans reproduction.
