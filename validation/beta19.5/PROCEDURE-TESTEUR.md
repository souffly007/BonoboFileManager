# Procédure courte — bêta 19.5

Utiliser uniquement une clé USB de test et des fichiers factices, après signature par le circuit habituel du testeur. Conserver une copie source. Ne pas utiliser le NAS réel.

1. Vérifier que le diagnostic est désactivé au démarrage. L’activer volontairement, sélectionner le dossier USB/SAF et exporter le journal uniquement à la fin.
2. Copier un dossier factice assez grand pour permettre une annulation pendant l’écriture. Annuler, attendre l’arrêt effectif, puis ouvrir « créations à contrôler ». Noter si un dossier partiel est conservé ; vérifier que la source et tout élément préexistant restent intacts.
3. Relancer vers le même nom : attendre un conflit explicite, sans écrasement. Choisir un nouveau nom ou examiner puis résoudre manuellement le résidu ; relancer et vérifier la copie.
4. Supprimer un fichier factice puis un dossier factice depuis un menu et depuis le double panneau. Comparer `DELETE_REQUEST` à `DELETE_END` ; aucun succès si `DELETE_VERIFY` est indéterminé. Tester un refus de permission avec des données factices.
5. Revenir d’ES Explorer, rouvrir la racine USB et naviguer dans un dossier. Noter séparément les actions et le résultat ; relever `NAV_*` et `SAF_RESELECT` sans attribuer automatiquement la panne à l’autre application.
6. Déplacer un fichier factice vers USB : vérifier la copie et le message de déplacement incomplet ; vérifier que la source reste présente.
7. Éjecter proprement, déconnecter et reconnecter la clé, puis relire les fichiers et comparer taille et SHA-256 à la source de référence. La vérification immédiate de l’application ne remplace pas cette étape.
8. Exporter manuellement le journal, vérifier qu’il ne contient aucun nom, chemin, URI ou contenu factice, puis effacer avec confirmation. Réessayer avec le diagnostic désactivé : aucun nouvel événement.

Documenter Android, fournisseur de documents et résultat observé sans identifiant matériel ni données personnelles. Ne pas installer l’APK non signée directement ; aucune installation ou distribution n’a été effectuée pendant la préparation.
