# Procédure de test USB réel — bêta 19.6

Utiliser une clé et des fichiers de test dédiés. Noter le modèle du téléphone, la version Android, le système de fichiers, les étapes et les messages ; comparer les mêmes dossiers physiques dans les deux applications.

1. Connecter la clé. Ouvrir USB / OTG et noter les volumes amovibles détectés. Sélectionner un sous-dossier puis revenir au sélecteur : vérifier son libellé et la liste des seuls fichiers accessibles. Autoriser un second sous-dossier du même volume et vérifier que les deux choix restent distincts.
2. Choisir explicitement la racine du volume dans le sélecteur Android si celui-ci l'autorise. Vérifier que la racine et les dossiers autorisés portent des libellés distincts, puis comparer leur contenu à un autre explorateur. Si Android bloque la racine, vérifier les explications et choisir un dossier ; une annulation volontaire peut produire le même message.
3. Ouvrir un dossier réellement vide, puis un dossier ne contenant que des fichiers cachés, avec et sans affichage des fichiers cachés. Vérifier les deux panneaux. Une erreur de lecture ne doit pas être présentée comme un dossier vide.
4. Déconnecter et reconnecter la clé, puis essayer l'accès précédent. En cas d'erreur, tester « Réessayer » puis « Choisir un autre emplacement » sans redémarrer. Répéter après passage par un autre explorateur ; relever les événements sans supposer de causalité. Vérifier que les autres autorisations sont encore visibles.
5. Dans un dossier de test, sélectionner un fichier ou dossier dont l'identité et le contenu sont connus ; confirmer la suppression une seule fois. Vérifier le dossier affiché, puis éjecter et reconnecter la clé et vérifier le même emplacement dans les deux explorateurs. Ne pas supprimer d'autre élément pour résoudre une incohérence.
6. Essayer de renommer un fichier SAF de test : le refus doit expliquer la protection contre le remplacement et conserver le fichier. Vérifier aussi la navigation dans chaque panneau et le retour à l'emplacement autorisé.

Conserver les journaux techniques et une chronologie distincte des actions. Signaler tout résultat inattendu avant une nouvelle manipulation des données.
