Bonobo Files Explorateur — 1.3.0-beta13-final

Dernière bêta prévue avant 1.3.0 stable.

Corrections / améliorations ciblées :
- conserve les corrections SMB de beta12c ;
- SMB -> mémoire interne : la copie/déplacement en attente reste disponible quand on revient dans l'explorateur local ;
- bannière + bouton « Copier ici / Déplacer ici » dans le dossier local de destination ;
- visionneuse images améliorée : swipe gauche/droite, boutons précédent/suivant, compteur, préchargement voisin ;
- quand une image est zoomée, le geste horizontal déplace l'image et ne change pas accidentellement de photo.

Tests prioritaires :
1. SMB : Copier un fichier -> Accueil -> Mémoire interne -> sous-dossier -> Copier ici.
2. SMB : Déplacer un fichier -> Mémoire interne -> Déplacer ici.
3. Images : ouvrir une photo au milieu d'un dossier puis swipe / flèches dans les deux sens.
4. Image zoomée : vérifier qu'un déplacement horizontal ne change pas de photo.
