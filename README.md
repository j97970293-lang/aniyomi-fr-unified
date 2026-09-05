# FR Unifié pour Aniyomi

Port Aniyomi de l’extension CloudStream **FR Unifié**.

L’extension affiche un catalogue unique et propre :

- **TMDB en français** pour les films et séries ;
- **AniList**, avec repli **MyAnimeList/Jikan**, puis TMDB si ces deux API sont temporairement indisponibles, pour les animés ;
- recherche unifiée et dédoublonnée ;
- saisons et épisodes TMDB ;
- liens VF/VOSTFR récupérés en parallèle par les scrapeurs **Nuvio** configurables ;
- addons **Stremio** configurables, avec conservation des en-têtes de lecture ;
- import de dépôts **CloudStream** et association automatique de leurs providers compatibles à Nuvio ;
- fenêtre multi-sélection pour activer ou désactiver chaque source ;
- sous-titres externes, dont OpenSubtitles v3.

## Différence importante avec la version CloudStream

CloudStream expose la liste de ses providers chargés au plugin. Aniyomi charge au contraire chaque extension dans un APK/classloader isolé : une extension ne dispose pas d’une API stable pour appeler les autres extensions installées.

Cette version ne tente donc pas une réflexion fragile sur Aniyomi. Elle conserve le même résultat côté utilisateur, mais récupère les liens directement avec :

1. les dépôts de scrapeurs Nuvio ;
2. les addons Stremio saisis dans les réglages ;
3. les passerelles Nuvio reconnues lors de l’import d’un dépôt CloudStream.

Un fichier CloudStream `.cs3` est un APK destiné à un autre hôte et ne peut pas être exécuté dans Aniyomi. L’import accepte donc le `repo.json`, inventorie ses plugins puis active les équivalents disponibles. Les extensions Aniyomi françaises déjà installées restent indépendantes.

## APK de test

Le build local produit :

```text
src/fr/frunified/build/outputs/apk/debug/aniyomi-fr.frunified-v16.2-debug.apk
```

Il s’agit d’un APK de test signé avec la clé Android debug. Pour publier des mises à jour, utilisez une clé de signature permanente comme indiqué plus bas.

## Installation

1. Téléchargez l’APK sur l’appareil Android.
2. Ouvrez-le et autorisez l’installation depuis cette source si Android le demande.
3. Dans Aniyomi, ouvrez **Parcourir → Extensions Anime**.
4. Faites confiance à l’extension si Aniyomi affiche la demande, puis ouvrez **FR Unifié**.
5. Dans les réglages, touchez **Gérer les sources actives** pour ouvrir la fenêtre Nuvio/Stremio/CloudStream.
6. Touchez **Ajouter Nuvio / Stremio / CloudStream** pour importer une URL `manifest.json`, un addon Stremio ou un `repo.json` CloudStream.

Compatibilité de compilation : **API d’extension Aniyomi 16**, Android 8.0 minimum.

## Réglages principaux

- **Gérer les sources actives** : charge tous les scrapeurs, y compris ceux déjà désactivés, puis permet de cocher individuellement les sources Nuvio, les passerelles CloudStream→Nuvio et les addons Stremio.
- **Ajouter Nuvio / Stremio / CloudStream** : détecte le type de l’URL, l’enregistre et propose d’ouvrir immédiatement le sélecteur.
- **Catalogue TMDB** / **Catalogue AniList-MAL** : active les volets de recherche.
- **Dépôts Nuvio** : une URL `manifest.json` par ligne.
- **Dépôts/Scrapeurs désactivés** : une URL exacte de dépôt ou un identifiant Nuvio par ligne.
- **Priorité des flux** : motifs tels que `VF,VOSTFR,1080,HD`.
- **Addons Stremio** : une URL de manifeste par ligne.
- **Dépôts CloudStream suivis** : références `repo.json` ; les plugins reconnus passent par Nuvio.
- **Sous-titres externes** : OpenSubtitles v3 et les addons configurés.
- **Clés API avancées** : lignes `NOM=valeur`, injectées dans `process.env` des bundles Nuvio.

## Compilation locale

Prérequis : JDK 17 et Android SDK 34.

```bash
export JAVA_HOME=/chemin/vers/jdk-17
export ANDROID_HOME=/chemin/vers/android-sdk
./gradlew :src:fr:frunified:spotlessCheck
./gradlew :src:fr:frunified:testDebugUnitTest :src:fr:frunified:lintDebug
./gradlew :src:fr:frunified:assembleDebug
```

### Tests réseau facultatifs

Les tests unitaires ordinaires sont déterministes et ne dépendent pas des sites tiers. Pour lancer en plus les sondes réelles :

```bash
FR_UNIFIED_NETWORK_TEST=1 ./gradlew :src:fr:frunified:testDebugUnitTest \
  --tests '*NuvioNetworkSmokeTest*' \
  --tests '*StremioNetworkSmokeTest*' \
  --tests '*ExternalSourceImporterNetworkTest*'
```

Le test Stremio contrôle toujours que le manifest et la route sont conformes. Si Snixi est joignable mais renvoie temporairement une liste vide pour toutes les sondes, le contrôle de présence d’un flux est marqué ignoré plutôt que d’attribuer cette indisponibilité amont au parseur. Le parseur lui-même reste couvert par un test déterministe.

Les révisions amont et le dernier état des sondes sont consignés dans [`UPSTREAMS.md`](UPSTREAMS.md). Le détail de cette version figure dans [`CHANGELOG.md`](CHANGELOG.md) et [`VALIDATION.md`](VALIDATION.md).

## Publication d’un dépôt Aniyomi

Le workflow `publish.yml` compile un APK release, construit `index.json`/`index.min.json`, puis publie le tout sur la branche `repo`.

Configurez ces secrets GitHub :

- `SIGNING_KEY` : contenu Base64 du fichier JKS ;
- `KEY_STORE_PASSWORD` ;
- `ALIAS` ;
- `KEY_PASSWORD`.

Exemple pour encoder la clé :

```bash
base64 -w 0 signingkey.jks
```

Lancez ensuite le workflow **Publish Aniyomi repository**. L’URL à ajouter dans Aniyomi sera :

```text
https://raw.githubusercontent.com/UTILISATEUR/DEPOT/repo/index.min.json
```

Ne perdez pas la clé JKS : Android refusera une mise à jour signée avec une autre clé.

## Structure

```text
src/fr/frunified/
├── build.gradle
├── libs/rhino-nuvio-1.9.1.jar
├── res/
└── src/
    ├── com/frunified/rhino/resources/RhinoMessages.kt
    └── eu/kanade/tachiyomi/animeextension/fr/frunified/
        ├── FrUnified.kt
        ├── TmdbCatalog.kt
        ├── AniListCatalog.kt
        ├── JikanCatalog.kt
        ├── NuvioClient.kt
        ├── StremioClient.kt
        └── ExternalSourceImporter.kt
```

Consultez aussi `UPSTREAMS.md` pour le suivi des versions et `src/fr/frunified/test/` pour les tests déterministes et les sondes réseau facultatives.

## Remarques

L’extension n’héberge aucun média. Elle interroge des catalogues publics et relaie les résultats fournis par les sources que l’utilisateur active. La disponibilité et les droits applicables dépendent de ces services et du pays de l’utilisateur.

Le moteur Rhino intégré provient de Mozilla Rhino (MPL-2.0), avec les adaptations déjà utilisées par le projet CloudStream FR Unifié. Consultez `NOTICE.md` et `third_party/rhino/PATCHES.md` pour les attributions, les modifications et la reconstruction du composant.
