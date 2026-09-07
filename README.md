# FR Unifié pour Aniyomi

**FR Unifié 16.7** est une extension Aniyomi autonome qui réunit catalogues, fiches, épisodes et sources de lecture dans une seule interface.

## Fonctions principales

- catalogues **TMDB**, **AniList**, **Jikan/MyAnimeList** et **Stremio** ;
- activation indépendante de TMDB, AniList et Jikan, ou désactivation globale des catalogues principaux ;
- détection automatique de **chaque entrée `catalogs[]`** de chaque manifest Stremio, exposée séparément dans les filtres avec ses options (genre, année, langue…) ;
- recherche, fiches, saisons et épisodes Stremio, y compris `meta.streams` et les flux TV directs ;
- serveurs Stremio visibles et chargés à la demande même lorsque Nuvio est désactivé, avec conversion des identifiants IMDb/TMDB et des types `series`/`tv` ;
- choix de l’ordre des moteurs : **Nuvio puis Stremio** ou **Stremio puis Nuvio** ;
- sources Nuvio activables individuellement, issues de dépôts français et internationaux, et interrogées simultanément par lots bornés ;
- langues de catalogue et de providers configurables ;
- titres de flux indiquant autant que possible la langue (VF, VFF, VFQ, MULTI, VOSTFR), le lecteur et la qualité ;
- transmission des en-têtes HTTP nécessaires au lecteur ;
- contrôle léger des flux avant lecture et rejet des refus HTTP explicites, notamment les 403 ;
- sous-titres externes Stremio/OpenSubtitles ;
- réglages avancés conservés : ordre des providers, motifs de priorité, concurrence, clés API, User-Agent, Referer et cookies.

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

Sur une nouvelle installation, tous les providers compatibles avec les langues sélectionnées sont autorisés. Une source peut être décochée individuellement. **Movix est exclu par défaut** tant que ses flux de test répondent 403, mais reste visible dans le sélecteur.

Le sélecteur affiche les providers de **tous les dépôts ajoutés**, même si leur langue n’est pas encore activée pour la lecture, s’ils sont désactivés dans le manifest ou si leur type n’est pas encore reconnu. Les filtres de langue, de type et d’activation ne s’appliquent qu’à leur exécution. Lorsqu’un dépôt ajouté contient le même identifiant qu’un dépôt par défaut, sa variante la plus récemment ajoutée est celle qui apparaît et s’exécute. Les dépôts saisis manuellement restent optionnels et ne sont pas ajoutés aux valeurs par défaut de l’extension.

### Dépôt All-in-One-Nuvio optionnel

Le dépôt demandé peut être ajouté manuellement depuis **Nuvio → ajouter un dépôt**, avec une seule saisie d’URL :

```text
https://raw.githubusercontent.com/D3adlyRocket/All-in-One-Nuvio/refs/heads/main/manifest.json
```

Une fois l’import confirmé, ouvrez **Nuvio → choisir les sources** : les **61 plugins** actuellement déclarés apparaissent individuellement dans le sélecteur. Ce dépôt a été testé pour l’import, mais il reste volontairement absent de la configuration par défaut.

Les bundles internationaux utilisent parfois des fonctions Node ou des sites qui changent sans préavis. Le moteur apporte des polyfills Rhino, abaisse les boucles `for…of` et corrige plusieurs incompatibilités de portée, mais la disponibilité d’un provider tiers n’est jamais garantie.

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
FR-Unifie-Aniyomi-v16.7.apk
```

Android peut demander l’autorisation d’installer depuis la source utilisée. Lors du premier lancement, Aniyomi peut aussi demander de faire confiance au certificat de l’extension.

Compatibilité : **API d’extension Aniyomi 16**, **Android 8.0 / API 26 minimum**. La version release est destinée notamment aux appareils sous Android 8.1.

## Réglages conseillés

1. Dans **Catalogues**, activez les services souhaités et choisissez les langues.
2. Dans les filtres de recherche, choisissez **Stremio**, puis l’une des entrées détectées dans `catalogs[]` et, si disponible, son option de genre/année/langue.
3. Si vous voulez uniquement Stremio, désactivez **Catalogues principaux** et gardez **Catalogue Stremio** actif.
4. Choisissez la priorité **Nuvio/Stremio**.
5. Dans **Nuvio**, ouvrez le sélecteur pour voir tous les plugins ajoutés, puis choisissez les langues réellement exécutées.
6. Réglez au besoin l’ordre des providers, les motifs de priorité et le nombre de scrapeurs simultanés (3 recommandé).
7. Dans **Stremio**, activez les addons désirés.
8. Utilisez les actions d’ajout propres à Nuvio ou Stremio pour coller un nouveau manifest ; il n’existe pas de champ d’import générique redondant.

Le diagnostic Nuvio teste le chemin réel Kotlin → Rhino → réseau. Les providers sont lancés simultanément par lots bornés, et une réussite VOSTFR ne stoppe pas la recherche tant qu’une VF peut encore être trouvée. Les hosters des deux moteurs restent disponibles dans l’ordre choisi.

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
