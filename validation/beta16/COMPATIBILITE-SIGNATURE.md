# Contrôle avant livraison au testeur — 11 octobre 2026

**Livraison de remplacement arrêtée. Aucune nouvelle APK construite ou signée pendant ce contrôle.**

Contrôle direct des binaires avec Android SDK Build Tools 35.0.0 : `aapt dump badging` et `apksigner verify --verbose --print-certs`. Les signatures des deux APK disponibles sont valides (schéma v2, un signataire chacune).

| APK | Package | Version / code | Certificat SHA-256 |
| --- | --- | --- | --- |
| Bêta 14 | Non vérifiable : APK absente des archives examinées du projet et des copies temporaires | Non vérifié | Non vérifié |
| `app/release/bonobo files explorateur v1.3.0-beta15.apk` | `fr.bonobo.filemanager` | `1.3.0-beta15` / 25 | `a2723a3cc2b325db18d8f4b662ec262efbcf6954d8ace8a78e59b9176a7e55f1` |
| `app/release/bonobo-files-explorateur-1.3.0-beta16-debug.apk` | `fr.bonobo.filemanager.adaptive` | `1.3.0-beta16-debug` / 26 | `1723fc2d56b64317505fd6925763e52399905010847c23fc95c9eaf6b3b2ca60` |

Certificat de la bêta 15 : `CN=ROBERT-FONT Franck`.
Certificat de la bêta 16 Debug : `C=US, O=Android, CN=Android Debug`.

Empreintes SHA-256 des APK (distinctes des empreintes des certificats) :

- Bêta 15 archivée : `865524c69c518127d2fdcf282d51c749a54c646bd57b94427f51c1fb1cdb2884`.
- Bêta 16 Debug : `1fc1b72ec57149bedd1cbff7cc867b4c54a68f0bcb4979c8457b98b86a9a9314`.

## Conclusion

La bêta 16 Debug préparée précédemment ne remplace pas la bêta 15 Release archivée : le package et le certificat diffèrent. Son installation constituerait une application séparée, sans reprise automatique des données de `fr.bonobo.filemanager`.

Le rapport précédent décrivait la voie Debug de bêta 15, et non l’identité du binaire Release effectivement archivé. Cette distinction est maintenant explicitement corrigée. L’APK Debug reste un artefact de validation sur émulateur ; elle ne doit pas être présentée comme une mise à jour de la bêta 15 Release du testeur.

Il n’existe pas de configuration de signature Release dans `app/build.gradle.kts`, ni de fichier `.jks` ou `.keystore` retrouvé dans le projet. Le certificat public contenu dans la bêta 15 permet la comparaison, mais ne permet pas de signer une nouvelle APK. Aucune clé n’a été créée et aucune tentative de signature de remplacement n’a été faite.

L’identité réelle de l’application actuellement installée chez le testeur n’a pas été lue sur son téléphone. Il faut confirmer qu’il a installé l’APK bêta 15 archivée ci-dessus, ou fournir le binaire exact utilisé. L’APK bêta 14 est également nécessaire pour vérifier son package et son certificat directement.

## Options avant reprise

1. Si le testeur utilise la bêta 15 Release archivée : retrouver la clé privée de signature existante (keystore et alias) dont le certificat SHA-256 est `a2723a3cc2b325db18d8f4b662ec262efbcf6954d8ace8a78e59b9176a7e55f1`. Avec cette clé et le package `fr.bonobo.filemanager`, une bêta 16 de code 26 pourra être construite, contrôlée et testée en mise à jour dans un émulateur isolé. Ne pas transmettre de mot de passe de keystore dans le rapport ou les journaux.
2. Si le testeur utilise une autre APK : obtenir le fichier exact ou ses métadonnées de package, version et certificat avant toute construction. Si elle utilise réellement le package `.adaptive` et le certificat Debug ci-dessus, la voie Debug peut être compatible, mais cela n’est pas établi pour l’installation du testeur.
3. Si la clé privée de la version installée est indisponible : aucune nouvelle clé ne peut remplacer simplement cette signature pour une mise à jour ordinaire. Une application séparée ou une désinstallation avec stratégie de sauvegarde/migration serait un autre projet à décider explicitement ; aucune de ces actions n’est effectuée ici.

Aucune donnée utilisateur modifiée, aucune désinstallation, aucun accès au smartphone, aucune installation et aucune publication sur souffly.app pendant ce contrôle.
