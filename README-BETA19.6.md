# Bonobo Files Explorateur 1.3.0-beta19.6

Version `1.3.0-beta19.6`, code 35. Cette préparation répond à l'audit [USB bêta 19.6](AUDIT-BETA19.6-USB.md).

## Corrections

- Le sélecteur distingue les volumes amovibles, leur racine SAF autorisée et les emplacements enregistrés. Un sous-dossier n'est plus choisi automatiquement en cliquant sur le volume. Une nouvelle sélection de racine ou de dossier passe par le sélecteur Android ; les autorisations existantes sont conservées.
- Le navigateur indique l'emplacement SAF autorisé et le document affiché. Les erreurs de lecture sont conservées séparément pour les deux panneaux ; une liste sans éléments visibles signale aussi le filtre des fichiers cachés. Une lecture locale qui échoue ne devient plus une liste vide.
- Quand le fournisseur ne renvoie pas de curseur, un message propose de réessayer ou de choisir un autre emplacement. Une autorisation persistante ne prouve pas que le fournisseur répond.
- Avant suppression, les chemins demandés doivent correspondre aux éléments affichés. Le rafraîchissement vise le dossier d'origine s'il est toujours affiché. Le bilan distingue l'observation immédiate de la persistance après remontage.
- Le refus de renommage SAF générique conserve la protection contre un remplacement non garanti et explique sa raison.

## Limites

Android peut interdire la sélection de la racine d'un volume : sélectionner alors un dossier autorisé. L'annulation du sélecteur ne permet pas de distinguer un refus d'Android d'une annulation volontaire. La classification de racine dépend de l'identité du volume exposée par le fournisseur Android ; si elle n'est pas identifiable, l'emplacement reste de portée inconnue. Les tests instrumentés doivent encore être exécutés sur un appareil et la persistance vérifiée sur une vraie clé. Les vérifications et la procédure figurent dans `validation/beta19.6/`.
