# FR Unifié pour Aniyomi

**FR Unifié 16.14** est une extension Aniyomi autonome qui réunit catalogues, fiches, épisodes et sources de lecture dans une seule interface.

## Fonctions principales

- interface **bilingue français/anglais** (« Langue de l'application » dans la section Général) : FR Unifié n'est pas réservé aux utilisateurs français ;
- catalogues **TMDB**, **AniList**, **Jikan/MyAnimeList** et **Stremio**, activés indépendamment par cases à cocher dans un seul popup **Catalogues** ;
- détection automatique de **chaque entrée `catalogs[]`** de chaque manifest Stremio, exposée séparément dans les filtres avec ses options (genre, année, langue…) ;
- recherche, fiches, saisons et épisodes Stremio, y compris `meta.streams` et les flux TV directs ;
- serveurs Stremio visibles et chargés à la demande même lorsque Nuvio est désactivé, avec conversion des identifiants IMDb/TMDB et des types `series`/`tv` ;
- choix de l’ordre des moteurs : **Nuvio puis Stremio** ou **Stremio puis Nuvio** ;
- sources Nuvio activables individuellement, issues de dépôts français et internationaux, et interrogées **en parallèle** (2 à 6 à la fois) ;
- **classement des sources à l’aide de flèches** (haut, bas, monter, descendre) au lieu de la saisie manuelle, et drapeaux de langue affichés dans tous les sélecteurs ;
- **organisation des saisons réglable** : classique (fiche → saisons → épisodes), fusionnée (une seule fiche avec toutes les saisons) ou séparée (une fiche « Titre — Saison N » dès le catalogue) ;
- langues de catalogue et de providers configurables ;
- **titres de flux lisibles** au format `(VF) 1080p · flemmix · Nuvio · Uqload` (langue, qualité, source, moteur, détail) et serveurs regroupés `Nuvio · flemmix : VF, VOSTFR` / `Stremio · addon` ;
- **classement des langues et des qualités à l’aide de flèches** : un seul ordre (VF, VFF, VFQ, MULTI, VOSTFR, VO, 1080p, 4K, 1440p, 720p…) qui décide de l’ordre des flux et du flux lu automatiquement ;
- **recherche rapide** facultative (TMDB + AniList, 6 s par appel) et repli TMDB sans année ;
- **mise à jour automatique quotidienne des sources Nuvio**, avec action manuelle et bilan chiffré ;
- transmission des en-têtes HTTP nécessaires au lecteur ;
- contrôle léger des flux à la découverte, **revérification au clic avant lecture**, et rejet des refus HTTP (403…) ainsi que des **pages HTML/popups** ;
- **DNS personnalisé avec DoH** (HTTPS, puis UDP 53) lorsque le DNS de l’appareil ne résout pas certains sites ;
- sous-titres externes Stremio/OpenSubtitles ;
- réglages avancés conservés : ordre des providers, ordre des critères en texte, concurrence, clés API, User-Agent, Referer et cookies.

CloudStream n’est plus intégré : les fichiers `.cs3` et les `repo.json` sont refusés. Utilisez un manifest **Nuvio** ou **Stremio** directement exécutable.

## Correction des séries en cours

Depuis la version 16.6, une série n’est plus limitée à 24 épisodes lorsqu’AniList ne fournit pas de total définitif. Elle utilise, dans l’ordre disponible :

1. le total annoncé par le catalogue ;
2. le prochain épisode AniList moins un ;
3. la dernière page réelle de l’API Jikan.

Le chemin Jikan a été contrôlé sur **One Piece** et restitue bien plus que 24 épisodes.

## Catalogues et langues

Les langues proposées sont :

- français (`fr-FR`) ;
- anglais (`en-US`) ;
- espagnol (`es-ES`) ;
- allemand (`de-DE`) ;
- italien (`it-IT`) ;
- portugais (`pt-BR`) ;
- japonais (`ja-JP`) ;
- hindi (`hi-IN`) ;
- turc (`tr-TR`) ;
- indonésien (`id-ID`) ;
- polonais (`pl-PL`) ;
- arabe (`ar-SA`).

Plusieurs langues peuvent être cochées. La langue principale pilote TMDB et les fiches ; les locales Stremio correspondantes sont ajoutées au sélecteur de catalogues.

Un manifest Stremio n’est plus réduit à une seule rangée : toutes ses entrées `catalogs[]` deviennent des choix distincts dans les filtres de recherche. Après l’ajout d’un manifest ou le changement de catalogue, utilisez **Réinitialiser les filtres** afin de charger la liste et ses options propres.

## Sources Nuvio

Les dépôts proposés couvrent actuellement :

- Gowaru ;
- D3adlyRocket / Anime-Nuvio ;
- Yoruix ;
- Phisher ;
- Turkish Nuvio.

Sur une nouvelle installation, **tous les providers des dépôts par défaut sont autorisés, français ou non** : aucune langue ne bloque plus l’exécution d’un site. Une source peut être décochée individuellement. **Movix est exclu par défaut** tant que ses flux de test répondent 403, mais reste visible dans le sélecteur.

Le sélecteur affiche les providers de **tous les dépôts ajoutés**, même s’ils sont désactivés dans le manifest ou si leur type n’est pas encore reconnu ; les filtres de type et d’activation ne s’appliquent qu’à leur exécution. Le **dépôt d’origine** de chaque site est affiché sous son nom, comme dans l’application NuviO, pour distinguer un site français d’un site international. **Appuyer longuement sur un site supprime le dépôt entier** dont il vient. Lorsqu’un dépôt ajouté contient le même identifiant qu’un dépôt par défaut, sa variante la plus récemment ajoutée est celle qui apparaît et s’exécute. Les dépôts saisis manuellement restent optionnels et ne sont pas ajoutés aux valeurs par défaut de l’extension.

La langue d’un site n’est plus une condition d’exécution : elle participe seulement au **classement des flux** (VF, VOSTFR, VO, EN, TR…). Vous pouvez ajouter vos propres langues classables depuis le dialogue « Classer les flux ».

### Configuration des sources (clés API, jetons…)

Certaines sources de dépôts tiers demandent leurs propres variables d’environnement (clé API, jeton, domaine). Quand un manifest les déclare (`env` / `requiredEnv`), le dialogue **Configurer les sources** les liste avec leurs valeurs par défaut ; les valeurs saisies sont injectées dans `process.env` avant chaque exécution. Une source dont une clé obligatoire est vide n’est pas lancée et est signalée « ⚙️ à configurer » dans le sélecteur et le diagnostic. Les clés API génériques (communes à toutes les sources) restent dans **Paramètres avancés**.

### Dépôt All-in-One-Nuvio optionnel

Le dépôt demandé peut être ajouté manuellement depuis **Nuvio → ajouter un dépôt**, avec une seule saisie d’URL :

```text
https://raw.githubusercontent.com/D3adlyRocket/All-in-One-Nuvio/refs/heads/main/manifest.json
```

Une fois l’import confirmé, ouvrez **Nuvio → choisir les sources** : les **61 plugins** actuellement déclarés apparaissent individuellement dans le sélecteur. Ce dépôt a été testé pour l’import, mais il reste volontairement absent de la configuration par défaut.

Les bundles internationaux utilisent parfois des fonctions Node ou des sites qui changent sans préavis. Le moteur apporte des polyfills Rhino, abaisse les boucles `for…of` et corrige plusieurs incompatibilités de portée, mais la disponibilité d’un provider tiers n’est jamais garantie.

## Flux, langues et qualités (16.14)

Chaque flux est présenté de la même façon, quel que soit le moteur :

```text
(VF) 1080p · flemmix · Nuvio · Uqload
(VOSTFR) 720p · French Streaming Providers · Stremio · Vidmoly
```

La langue entre parenthèses et la qualité viennent en tête, puis la source (provider Nuvio ou addon Stremio), le moteur et un détail court (lecteur, release). Les serveurs sont regroupés par moteur et par source, avec les langues qu’ils proposent : `Nuvio · flemmix : VF, VOSTFR`.

Le réglage **Lecture → Classer langues et qualités avec les flèches** remplace les anciens motifs de priorité. Un seul ordre mélange langues et qualités ; par défaut :

```text
VF, VFF, VFQ, MULTI, VOSTFR, VO, 1080p, 4K, 1440p, 720p, 480p, 360p
```

Le premier critère satisfait par un flux décide de sa place, le suivant départage : une VF 720p passe avant une VOSTFR 1080p, une VF 1080p avant une VF 720p. Pour privilégier la qualité, montez `1080p` ou `4K` au-dessus des langues. Seul le flux classé n° 1 est marqué « préféré » et lancé automatiquement par Aniyomi. Les motifs personnalisés des versions précédentes sont convertis à la première ouverture.

## Installation

### Depuis le dépôt Aniyomi

Ajoutez cette URL de dépôt dans Aniyomi :

```text
https://raw.githubusercontent.com/j97970293-lang/aniyomi-fr-unified/repo/index.min.json
```

Puis installez **FR Unifié** depuis **Parcourir → Extensions Anime**.

### Depuis l’APK

Le fichier de version est nommé :

```text
FR-Unifie-Aniyomi-v16.14.apk
```

Android peut demander l’autorisation d’installer depuis la source utilisée. Lors du premier lancement, Aniyomi peut aussi demander de faire confiance au certificat de l’extension.

Compatibilité : **API d’extension Aniyomi 16**, **Android 8.0 / API 26 minimum**. La version release est destinée notamment aux appareils sous Android 8.1.

## Réglages conseillés

1. Dans **Catalogues**, activez les services souhaités et choisissez les langues.
2. Dans les filtres de recherche, choisissez **Stremio**, puis l’une des entrées détectées dans `catalogs[]` et, si disponible, son option de genre/année/langue.
3. Si vous voulez uniquement Stremio, décochez TMDB, AniList et Jikan dans le popup **Catalogues** et gardez **Catalogue Stremio** actif.
4. Choisissez la priorité **Nuvio/Stremio**, puis **classez langues et qualités avec les flèches** (VF d’abord par défaut). Activez la **recherche rapide** si les recherches vous paraissent lentes.
5. Dans **Nuvio**, ouvrez le sélecteur pour voir tous les plugins ajoutés (avec leur drapeau et leur dépôt), puis **classez-les avec les flèches**. La langue ne bloque plus l’exécution : tous les sites activés partent, et leur langue est classée parmi les flux.
6. Dans le popup **Options de recherche des sources**, réglez le parallélisme (3 recommandé, jusqu’à 6) et le **flux maximum par site** (illimité par défaut, comme dans NuviO) ; gardez la **mise à jour automatique des sources** active, ou lancez **Mettre à jour les sources maintenant** après l’ajout d’un dépôt.
7. Choisissez l’**organisation des saisons** souhaitée (classique par défaut) : fusionnée pour ouvrir directement tous les épisodes, séparée pour découper les séries dès le catalogue.
8. Si certains sites ne se résolvent pas sur votre téléphone, ajoutez un **DNS personnalisé** (ex. `1.1.1.1`, utilisé en DoH puis UDP) dans la section Réseau, puis testez-le.
9. Dans **Stremio**, activez les addons désirés.
10. Utilisez les actions d’ajout propres à Nuvio ou Stremio pour coller un nouveau manifest ; il n’existe pas de champ d’import générique redondant.

## Sauvegarde et restauration

La section **Sauvegarde** ne demande plus de lien :

- **Créer une sauvegarde** : copier dans le presse-papiers, partager vers une application ou un dossier de votre choix, ou enregistrer directement dans le dossier Téléchargements du téléphone ;
- **Restaurer une sauvegarde** : choisir un fichier `fr-unified-backup-*.json` du dossier Téléchargements, coller le JSON, ou saisir un lien HTTPS ;
- **Synchronisation par lien (facultatif)** : lien HTTPS vers un JSON, restauré automatiquement chaque jour au lancement si vous l’activez.

## Diagnostic

Le diagnostic Nuvio teste le chemin réel Kotlin → Rhino → réseau. **Tous les sites activés sont interrogés jusqu’au bout, en parallèle** : il n’y a plus de mode de recherche ni d’arrêt quand une VF est trouvée, chaque site renvoie tous ses liens (bornés par « flux maximum par site », illimité par défaut). Au clic, les liens Nuvio sont revérifiés. Les hosters des deux moteurs restent disponibles dans l’ordre choisi. Un addon Stremio lent est abandonné après 15 secondes sans bloquer la liste.

## Compilation locale

Prérequis : JDK 17 et Android SDK 34.

```bash
export JAVA_HOME=/chemin/vers/jdk-17
export ANDROID_HOME=/chemin/vers/android-sdk

./gradlew :src:fr:frunified:spotlessCheck
./gradlew :src:fr:frunified:testDebugUnitTest
./gradlew :src:fr:frunified:lintDebug
./gradlew :src:fr:frunified:assembleDebug
```

Sur une machine peu dotée en mémoire, exécutez ces commandes séquentiellement avec un seul worker.

### Tests réseau facultatifs

Les tests ordinaires restent déterministes. Les sondes réelles sont opt-in :

```bash
FR_UNIFIED_NETWORK_TEST=1 ./gradlew :src:fr:frunified:testDebugUnitTest \
  --tests '*NuvioNetworkSmokeTest*' \
  --tests '*NuvioProviderHealthTest*' \
  --tests '*StremioNetworkSmokeTest*' \
  --tests '*StremioCatalogNetworkTest*' \
  --tests '*JikanCatalogNetworkTest*'
```

Un provider international précis peut être essayé avec :

```bash
FR_UNIFIED_INTERNATIONAL_NUVIO_REPO='https://…/manifest.json' \
FR_UNIFIED_INTERNATIONAL_NUVIO_IDS='provider-a,provider-b' \
./gradlew :src:fr:frunified:testDebugUnitTest \
  --tests '*NuvioInternationalProviderTest*'
```

## Publication

Le workflow `.github/workflows/publish.yml` construit l’APK release signé, génère `index.json`/`index.min.json`, puis publie le dépôt Aniyomi sur la branche `repo`.

Secrets requis :

- `SIGNING_KEY` ;
- `KEY_STORE_PASSWORD` ;
- `ALIAS` ;
- `KEY_PASSWORD`.

Ne perdez pas la clé de signature : Android refusera une mise à jour signée avec un autre certificat.

## Références et responsabilité

Le comportement Stremio suit notamment l’extension de référence du dépôt [`Secozzi/aniyomi-extensions`](https://github.com/Secozzi/aniyomi-extensions). Les révisions amont et le dernier état des contrôles sont consignés dans [`UPSTREAMS.md`](UPSTREAMS.md) et [`VALIDATION.md`](VALIDATION.md).

L’extension n’héberge aucun média. Elle interroge des catalogues et relaie les résultats des sources activées par l’utilisateur. Leur disponibilité et les règles applicables dépendent des services tiers et du pays de l’utilisateur.

Mozilla Rhino est distribué sous MPL-2.0 ; consultez [`NOTICE.md`](NOTICE.md) et `third_party/rhino/PATCHES.md`.
