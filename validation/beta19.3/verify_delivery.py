"""Read-only validation of build outputs, then archive/copy the unsigned delivery. No install."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "validation/beta19.3"
LOG = (OUT / "gradle-final.log").read_text()
assert "BUILD SUCCESSFUL" in LOG and "BUILD FAILED" not in LOG
index = json.loads((OUT / "code-sha256.json").read_text())
assert all(hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == sha for name, sha in index.items())

result = {"source_files": len(index), "variants": {}}
for variant in ("Debug", "Release"):
    test_dir = ROOT / f"app/build/test-results/test{variant}UnitTest"
    reports = list(test_dir.glob("TEST-*.xml"))
    assert reports
    suites = [ET.parse(path).getroot() for path in reports]
    counts = {key: sum(int(suite.get(key, "0")) for suite in suites)
              for key in ("tests", "failures", "errors", "skipped")}
    assert counts["failures"] == counts["errors"] == counts["skipped"] == 0
    assert counts["tests"] == 123
    archive = OUT / f"jvm-{variant.lower()}"
    archive.mkdir(exist_ok=True)
    for report in reports:
        shutil.copy2(report, archive / report.name)
    lint_xml = ROOT / f"app/build/reports/lint-results-{variant.lower()}.xml"
    issues = ET.parse(lint_xml).getroot().findall("issue")
    counts["lint_errors"] = sum(issue.get("severity") in ("Error", "Fatal") for issue in issues)
    counts["lint_warnings"] = sum(issue.get("severity") == "Warning" for issue in issues)
    assert counts["lint_errors"] == 0
    for suffix in ("xml", "txt", "html"):
        report = lint_xml.with_suffix("." + suffix)
        shutil.copy2(report, OUT / report.name)
    result["variants"][variant] = counts

apk = ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk"
data = apk.read_bytes()
with zipfile.ZipFile(apk) as package:
    jar_signatures = [name for name in package.namelist() if name.upper().startswith("META-INF/")
                      and name.upper().endswith((".RSA", ".DSA", ".EC", ".SF"))]
    assert not jar_signatures
eocd = data.rfind(b"PK\x05\x06")
assert eocd >= 0
central_offset = struct.unpack_from("<I", data, eocd + 16)[0]
assert data[central_offset:central_offset + 4] == b"PK\x01\x02"
assert data[central_offset - 16:central_offset] != b"APK Sig Block 42"

sdk_tools = Path("/var/home/Snoopy63/Android/Sdk/build-tools/35.0.0")
env = dict(os.environ, JAVA_HOME="/var/home/Snoopy63/.jdks/jbr-21.0.11")
env["PATH"] = env["JAVA_HOME"] + "/bin:" + env.get("PATH", "")
signing = subprocess.run([str(sdk_tools / "apksigner"), "verify", "--verbose", str(apk)],
                         text=True, capture_output=True, env=env)
(OUT / "apksigner-unsigned-check.txt").write_text(signing.stdout + signing.stderr)
assert signing.returncode == 1 and "DOES NOT VERIFY" in signing.stdout + signing.stderr
badging = subprocess.run([str(sdk_tools / "aapt"), "dump", "badging", str(apk)],
                         text=True, capture_output=True, check=True, env=env).stdout
package_line = next(line for line in badging.splitlines() if line.startswith("package:"))
assert "name='fr.bonobo.filemanager'" in package_line
assert "versionCode='32'" in package_line and "versionName='1.3.0-beta19.3'" in package_line
(OUT / "apk-package.txt").write_text(package_line + "\n")

delivery = ROOT / "app/release/bonobo-files-explorateur-1.3.0-beta19.3-unsigned.apk"
if delivery.exists():
    assert delivery.read_bytes() == data
else:
    with delivery.open("xb") as target:
        target.write(data)
assert delivery.read_bytes() == data
shutil.copy2(apk.parent / "output-metadata.json", OUT / "output-metadata.json")
result["apk"] = {"path": str(delivery.relative_to(ROOT)), "bytes": len(data),
                 "sha256": hashlib.sha256(data).hexdigest(), "package": package_line,
                 "jar_signatures": jar_signatures, "apk_signing_block": False,
                 "apksigner_returncode": signing.returncode, "identical_to_build": True}
result["android_tests"] = "compiled only; not executed"
result["physical_usb_tests"] = "not performed"
(OUT / "checks.json").write_text(json.dumps(result, indent=2) + "\n")
(OUT / "APK-SHA256.txt").write_text(f"{result['apk']['sha256']}  {delivery.name}\n")
debug = result["variants"]["Debug"]
release = result["variants"]["Release"]
duration = next(line for line in LOG.splitlines() if line.startswith("BUILD SUCCESSFUL"))
(OUT / "RESULTATS.md").write_text(f"""# Validation et livraison — bêta 19.3

21 octobre 2026. Passe finale : **{duration}**. Version **1.3.0-beta19.3**, versionCode **32**.

| Contrôle | Résultat final |
|---|---|
| JVM Debug | {debug['tests']} tests, {debug['failures']} échec, {debug['errors']} erreur, {debug['skipped']} ignoré |
| JVM Release | {release['tests']} tests, {release['failures']} échec, {release['errors']} erreur, {release['skipped']} ignoré |
| APK Debug | Compilation réussie |
| APK Release | Compilation réussie ; non signée |
| APK AndroidTest Debug | Compilation réussie ; tests non exécutés |
| Lint Debug | {debug['lint_errors']} erreur, {debug['lint_warnings']} avertissements |
| Lint Release | {release['lint_errors']} erreur, {release['lint_warnings']} avertissements |
| USB physique après éjection/reconnexion | Non testé |

Commande finale, avec le JDK local et accès autorisé au cache Gradle existant hors sandbox :

```sh
JAVA_HOME=/var/home/Snoopy63/.jdks/jbr-21.0.11 ./gradlew --offline --no-daemon --max-workers=2 testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease assembleDebugAndroidTest lintDebug lintRelease
```

Journal final : [gradle-final.log](gradle-final.log). Rapports archivés dans `jvm-debug/`, `jvm-release/` et les fichiers `lint-results-*.xml/txt/html`. Aucun contrôle Lint désactivé ; les avertissements restent présents. Les suites existantes sont complétées par **21 tests JVM** de fiabilité. Les tests du vrai adaptateur Android et de son fournisseur privé restent **compilés uniquement**, sans lancement d'émulateur ni installation sur smartphone.

## APK livrée

[Télécharger la Release non signée](../../{result['apk']['path']}).

- Package : `fr.bonobo.filemanager` ; versionName `1.3.0-beta19.3`, versionCode `32`, vérifiés par aapt.
- Taille : **{len(data):,} octets**.
- SHA-256 : `{result['apk']['sha256']}`.
- Copie livrée identique octet pour octet à `app/build/outputs/apk/release/app-release-unsigned.apk`.
- `apksigner verify --verbose` : code retour **1**, `DOES NOT VERIFY`, attendu pour cette Release non signée.
- Inspection ZIP et structure : aucune signature JAR ni bloc APK Signing Block. Aucun appel de signature Release.

Preuves : [checks.json](checks.json), [APK-SHA256.txt](APK-SHA256.txt), [apk-package.txt](apk-package.txt), [apksigner-unsigned-check.txt](apksigner-unsigned-check.txt), [output-metadata.json](output-metadata.json). Les anciens APK et les métadonnées de livraison précédentes ne sont pas remplacés.

## Périmètre et passages intermédiaires

[code-sha256.json](code-sha256.json) indexe les **{len(index)} fichiers de code/configuration** du lot, avec contrôle de leurs empreintes après la compilation finale. Le workspace était déjà largement modifié ; aucune remise à zéro ni tentative de réattribuer les changements préexistants. Les empreintes de `FileRepositoryImpl`, `TransferEngineFactory` et `OperationDialog` restent identiques à l'archive bêta 19.2. Les configurations de signature ne sont pas changées ; la compilation Debug utilise le mécanisme Gradle habituel.

Le premier lancement Gradle a rencontré le cache hors sandbox en lecture seule, puis l'accès autorisé a permis la compilation. Une erreur de compilation du nouveau journal (résolution de `entries` dans `Properties.apply`) a été corrigée. Une passe intermédiaire a ensuite échoué sur une indentation signalée par Lint ; le bloc a été simplifié et les contrôles relancés sans suppression de règle. Deux passes complètes réussies ont précédé la dernière : l'une avant le renforcement du test d'écriture partielle, l'autre avant l'ajout du reçu d'intention couvrant une création sans URI retournée. Ces journaux sont conservés séparément. **Les résultats du tableau proviennent de la passe finale avec ces corrections.**

## Limites et tests physiques restants

Le test de 2 147 483 649 octets est un flux généré avec puits et relecture synthétiques, en mémoire bornée : ni fichier de 2 Gio matérialisé par SAF ni support USB réel. Une petite copie factice matérialisée est également testée. Les fournisseurs simulés ne prouvent pas le comportement OEM ou la persistance du matériel.

Restrictions de sécurité livrées : publication SAF progressive par création et seconde écriture, sans renommage remplaçant ; renommage SAF refusé en production faute de fournisseur qualifié ; source conservée pour toute destination SAF, amovible ou non identifiable avec déplacement indiqué incomplet ; aucun nettoyage automatique de dossier SAF, même apparemment vide. Les créations sans identité retournée restent explicitement non confirmées et à examiner, sans suppression par nom.

Le détail des corrections et des limites est dans [README-BETA19.3.md](../../README-BETA19.3.md). La [procédure physique](PROCEDURE-TESTS-PHYSIQUES.md) impose des fichiers factices, une source de référence conservée, l'éjection sûre, la reconnexion, l'actualisation des références, puis la relecture complète et SHA-256 de chaque destination. Aucun succès de flush/fsync n'est présenté comme cette preuve. Le problème ES Explorer reste séparé, sans correction revendiquée.

Aucun NAS réel, smartphone installé, publication, autre application modifiée, développement FTP/FTPS ni fiche USB. **Travail arrêté après validation automatisée, vérification de l'artefact non signé et archivage de la livraison.**
""")
print(json.dumps(result, indent=2))
