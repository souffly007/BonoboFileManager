# Recette courte — bêta 18

Utiliser uniquement des fichiers jetables et, pour le réseau, un serveur de laboratoire. Cette recette est à effectuer par le testeur après signature ; elle n’a pas été exécutée ici.

1. Vérifier dans À propos la version 1.3.0-beta18. Mettre à jour avec la même signature, sans désinstaller pour conserver les données.
2. Copier plusieurs fichiers et un dossier vers un dossier neuf : contrôler contenus, compteurs, progression et phase de vérification. Refaire en déplacement ; les sources doivent disparaître uniquement après vérification.
3. Créer un conflit de nom sur un élément : le contenu existant reste intact, l’erreur est individuelle et les autres éléments peuvent terminer. La source en erreur reste présente.
4. Annuler un gros transfert et pendant la vérification : « annulation demandée » peut durer jusqu’au retour du moteur. Les éléments suivants ne démarrent plus ; contrôler les éventuelles sorties partielles et les sources.
5. Mettre un fichier et un dossier de test en corbeille, restaurer puis tenter une seconde restauration : aucun doublon automatique. Tester destination disparue, conflit et dossier choisi ; la copie en corbeille reste conservée en cas d’erreur ou de doute.
6. Sur USB de test, vérifier copie/déplacement, retour depuis une autre application et réautorisation SAF si nécessaire. Une destination SAF non identifiable peut être refusée ; aucun démontage/remontage automatique.
7. Changer de dossier, tourner l’écran et vérifier les thèmes. Arrêter le processus pendant un transfert jetable puis relancer : signalement d’interruption, aucune reprise ni suppression automatique. Contrôler manuellement sources et destinations avant de relancer.
8. Facultatif, réseau de laboratoire : SMB→SMB et fichiers SMB/SFTP/WebDAV→local selon les routes offertes. Une relecture échouée conserve la source ; WebDAV sans ETag fort refuse le déplacement. Aucun NAS réel.

Pour chaque anomalie : noter Android, type de stockage/protocole, action, phase, message et présence des fichiers avant/après. Ne pas transmettre de noms personnels, URI, identifiants ou mots de passe.
