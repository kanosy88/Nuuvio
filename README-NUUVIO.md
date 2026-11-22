# Nuuvio — fork personnel de Nuvio Desktop

Nuuvio est NuvioDesktop (NuvioMedia/NuvioDesktop) plus les fonctions Letterboxd et Journal de la branche
`perso`. Il s'installe **à côté** de l'app officielle, sans la remplacer.

- Dépôt : https://github.com/kanosy88/Nuuvio (branche par défaut : `perso`)
- Releases : https://github.com/kanosy88/Nuuvio/releases

## Coexistence avec Nuvio officiel

| | Nuvio officiel | Nuuvio |
|---|---|---|
| macOS | `Nuvio.app`, `com.nuvio.media.desktop` | `Nuuvio.app`, `io.github.kanosy88.nuuvio` |
| Windows | `Nuvio.exe`, MSI upgrade code `395990ee-…` | `Nuuvio.exe`, MSI upgrade code `4a44b078-…` |
| Linux | paquet `nuvio`, `/opt/nuvio` | paquet `nuuvio`, `/opt/nuuvio`, `nuuvio.desktop` |
| Données macOS | `~/Library/Application Support/Nuvio` | `~/Library/Application Support/Nuuvio` |
| Données Windows | `%APPDATA%\Nuvio`, `%LOCALAPPDATA%\Nuvio\Cache` | `%APPDATA%\Nuuvio`, `%LOCALAPPDATA%\Nuuvio\Cache` |
| Données Linux | `~/.config/nuvio`, `~/.cache/nuvio` | `~/.config/nuuvio`, `~/.cache/nuuvio` |

**Premier lancement** : si le dossier de données Nuuvio n'existe pas encore, Nuuvio **copie** (sans rien
déplacer ni modifier) le dossier Nuvio : réglages, addons, profils, compte, bibliothèque, journal, cookies
Letterboxd. Les installeurs téléchargés (`updates/`) ne sont pas copiés ; dans le cache, seul `letterboxd/`
(index des films, flux) est copié, le reste se reconstruit. Ensuite les deux apps vivent séparément.
La copie n'a lieu que si le dossier Nuuvio est **absent** (après « effacer les données locales », le dossier
reste vide et rien n'est recopié). Pour refaire la copie : quitter Nuuvio et supprimer le dossier Nuuvio.

Les cookies Letterboxd sont dans le trousseau macOS (service `nuuvio.letterboxd`, partagé par les deux
builds), en DPAPI dans le dossier de données sous Windows (copié), ou dans le Secret Service sous Linux.

Limites de coexistence :
- les liens `nuvio://` et `stremio://` (dont le retour de connexion Trakt) sont déclarés par les deux apps :
  le système choisit l'une des deux ;
- sous Linux, les deux apps ont la même classe de fenêtre (`com-nuvio-app-MainKt`) : le dock peut les regrouper ;
- le `local.properties` du fork ne contient que la config Supabase : pas de clés Trakt / MDBList / Simkl,
  pas de Sentry (aucun rapport de plantage n'est envoyé à l'équipe Nuvio).

## Mettre à jour (manuel uniquement)

### Sur Mac : `nuuvio-update`

```sh
nuuvio-update                  # merge le dernier tag upstream, build, Release, installe /Applications/Nuuvio.app
nuuvio-update --tag 0.1.38-alpha   # merge ce tag upstream précis
nuuvio-update --no-build       # installe la dernière Release existante, sans rien construire
nuuvio-update --no-build --release nuuvio-0.1.37-alpha-1
nuuvio-update --intel          # construit aussi le DMG Intel (automatique sur un Mac Intel)
```

Le script (`scripts/nuuvio-update`, lien `~/.local/bin/nuuvio-update`) demande `gh` connecté au compte
kanosy88. Il lance le workflow, attend sa fin (`gh run watch`, 20 à 40 min), télécharge le DMG de la
Release pour l'architecture du Mac, ferme Nuuvio s'il est ouvert, installe `Nuuvio.app` dans
`/Applications` (ou `~/Applications` si non accessible en écriture) et retire l'attribut de quarantaine.
En cas de conflit de merge : message clair + lien vers l'issue ouverte par le workflow ; rien n'est poussé.

### Depuis GitHub (n'importe quel OS)

Actions → **Nuuvio sync and release** → *Run workflow* (branche `perso`), champ facultatif `upstream_tag`.
Puis télécharger le binaire dans Releases.

### Ce que fait le workflow (`.github/workflows/nuuvio-release.yml`)

1. Récupère le tag upstream demandé (par défaut la dernière release non brouillon de NuvioMedia/NuvioDesktop).
2. Le **merge** dans `perso` (jamais de rebase ni de force push). Les fichiers `.github/workflows/` du fork
   sont conservés tels quels (les workflows upstream sont désactivés sur le fork, et le jeton du workflow
   ne peut pas pousser de modification de workflow). Conflit → échec + issue « [nuuvio-sync] … » listant
   les fichiers ; à résoudre à la main (`git merge <tag>` en local, `git push origin perso`).
3. Tests Letterboxd / Journal (Linux), en parallèle des builds :
   macOS arm64 (DMG, + Intel en option), Windows x64 (MSI), Linux x64 (DEB + AppImage).
4. Release `nuuvio-<tag upstream>-<n° de run>` avec les binaires (marquée « latest »).
5. Seulement ensuite, `perso` avance jusqu'au commit de merge (refusé si `perso` a bougé pendant le build).

Config : secret de dépôt `LOCAL_PROPERTIES_BASE64` (= `base64 < local.properties`, jamais affiché). Pour le
changer : `base64 < local.properties | gh secret set LOCAL_PROPERTIES_BASE64 --repo kanosy88/Nuuvio`.
Les binaires publiés contiennent cette config Supabase (choix assumé).

### Mise à jour intégrée à l'app

Le bandeau « mise à jour disponible » de l'app ne regarde que les Releases de **kanosy88/Nuuvio** (jamais
l'officiel). Une release est proposée si son tag est plus récent que la version installée (les builds du
workflow embarquent leur numéro de run : `0.1.37-alpha` + build 12 ↔ tag `nuuvio-0.1.37-alpha-12`).
Un build local (`./gradlew :composeApp:run`) n'a pas de numéro et se voit donc proposer la dernière Release.

## Installer sans signature

Les binaires ne sont pas signés (ni Apple, ni Microsoft).

- **macOS (Gatekeeper)** : `nuuvio-update` retire déjà la quarantaine. Avec un DMG téléchargé dans le
  navigateur : glisser `Nuuvio.app` dans Applications puis **clic droit → Ouvrir → Ouvrir** au premier
  lancement (ou Réglages Système → Confidentialité et sécurité → « Ouvrir quand même »), ou en terminal :
  `xattr -dr com.apple.quarantine /Applications/Nuuvio.app`.
- **Windows (SmartScreen)** : « Informations complémentaires » → « Exécuter quand même ».
- **Linux** : `sudo apt install ./Nuuvio-Linux-x86_64-*.deb`, ou `chmod +x Nuuvio-*.AppImage`.

## Git LFS et quota

Le lecteur natif embarque libmpv en Git LFS : 225 Mo au total (macOS arm64 52 Mo, macOS Intel 63 Mo,
Windows 110 Mo ; Linux utilise la libmpv du système, 0 Mo). Pour limiter la bande passante LFS :
- chaque job ne télécharge **que** les fichiers de son OS/architecture (`git lfs pull --include=…`), le job
  de merge et les tests n'en téléchargent aucun (`GIT_LFS_SKIP_SMUDGE=1`) ;
- `.git/lfs/objects` est mis en cache (actions/cache, clé = empreinte des pointeurs LFS) : tant que
  libmpv ne change pas upstream et que le cache sert au moins une fois par semaine, un run ne télécharge
  rien. Cache froid : 162 Mo par run (arm64 + Windows), 225 Mo avec `--intel` ;
- si un objet manque sur le serveur LFS du fork, le job le prend sur celui de NuvioMedia/NuvioDesktop.

Quota : le compte gratuit GitHub inclut un quota mensuel de bande passante et de stockage LFS ; pour un fork,
GitHub peut imputer l'usage au dépôt racine du réseau. Le jeton `gh` actuel n'a pas la portée `user` : le
quota n'a pas pu être lu automatiquement. Pour le consulter :
`gh auth refresh -h github.com -s user` puis `gh api /users/kanosy88/settings/billing/usage`, ou
https://github.com/settings/billing.

## Développement

- Branche de travail : `perso`. Les features du fork vivent dans `features/letterboxd/`, `features/journal/`,
  `core/build/NuuvioIdentity.kt`, `core/storage/NuuvioDataDirs.kt` ; le code upstream n'a que de petits points
  d'accroche (voir l'historique de `perso`).
- Lancer : `./gradlew :composeApp:run` (utilise aussi le dossier de données Nuuvio).
