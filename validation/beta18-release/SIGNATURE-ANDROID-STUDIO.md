# Générer l’APK signée dans Android Studio

Projet préparé : package `fr.bonobo.filemanager`, version `1.3.0-beta18`, code **28**. L’APK fournie est non signée. Aucun keystore n’a été ouvert, créé ou modifié pendant cette préparation.

1. Ouvrir ce projet dans Android Studio et laisser la synchronisation Gradle terminer.
2. **Build → Generate Signed Bundle / APK** (ou **Generate Signed App Bundle or APK**), choisir **APK**, puis **Next**.
3. Choisir le module **app**. Dans **Key store path**, sélectionner votre keystore personnel existant ; sélectionner son alias habituel. Ne pas utiliser **Create new**.
4. Saisir les mots de passe uniquement dans les champs locaux de l’assistant. Utiliser la même clé que pour la version distribuée pour permettre une mise à jour sans désinstallation.
5. **Next** : choisir **release** et un dossier dédié, par exemple `app/release/beta18-signee`. Si les options de signature sont affichées, inclure **V2**, comme dans l’APK bêta 16 archivée.
6. **Finish / Create**, puis **Locate** dans la notification de fin pour retrouver l’APK signée. L’assistant reconstruit et signe la variante Release depuis les sources préparées ; il ne faut pas sélectionner la variante Debug `.adaptive`.
7. Vérifier l’APK produite avec les commandes ci-dessous, en remplaçant le chemin. Attendu : package indiqué ci-dessus, code 28, version bêta 18, signature valide avec le même certificat que votre APK distribuée.

```sh
/var/home/Snoopy63/Android/Sdk/build-tools/35.0.0/aapt dump badging "/chemin/APK-signee.apk"
/var/home/Snoopy63/Android/Sdk/build-tools/35.0.0/apksigner verify --verbose --print-certs "/chemin/APK-signee.apk"
sha256sum "/chemin/APK-signee.apk"
```

Ces vérifications lisent l’APK, sans accéder au keystore. Le dossier de sortie dédié préserve les anciennes archives. Aucun APK signé n’a été généré ici, aucune installation ni publication effectuée.
