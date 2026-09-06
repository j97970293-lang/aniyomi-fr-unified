# Attributions et composants tiers

## Base Aniyomi

La structure Gradle et le contrat d’extension utilisent le projet Aniyomi Extensions et l’API `extensions-lib` v16 :

- <https://github.com/aniyomiorg/aniyomi-extensions>
- <https://github.com/aniyomiorg/aniyomi-extensions-lib>
- référence complémentaire : <https://github.com/Secozzi/aniyomi-extensions>

Les fichiers dérivés de cette base sont distribués selon la licence Apache 2.0 présente dans `LICENSE`.

## Origine de FR Unifié

Certaines logiques initiales dérivent du projet FR Unifié historique :

- <https://github.com/j97970293-lang/cloudstream-fr-unified>

La version Aniyomi 16.6 est autonome et ne charge, n’importe ni n’exécute aucun plugin CloudStream. Elle utilise directement les contrats Aniyomi, Nuvio et Stremio.

## Mozilla Rhino 1.9.1

L’APK embarque une version adaptée et relocalisée de Mozilla Rhino 1.9.1. Rhino est distribué selon la **Mozilla Public License 2.0**.

- source amont : <https://github.com/mozilla/rhino/tree/Rhino1_9_1_Release>
- texte de licence : `third_party/rhino/MPL-2.0.txt`
- modifications et procédure de reconstruction : `third_party/rhino/PATCHES.md`

## Dépôts Nuvio

L’extension télécharge à l’exécution les manifests et bundles choisis par l’utilisateur. Les familles proposées par défaut sont :

- <https://github.com/Gowaru/gowaru-nuvio-providers>
- <https://github.com/D3adlyRocket/Anime-Nuvio>
- <https://github.com/yoruix/nuvio-providers>
- <https://github.com/phisher98/phisher-nuvio-providers>
- <https://github.com/fmustafayaman/turkish-nuvio>

Ces bundles ne sont pas incorporés au code source ni à l’APK. Chaque dépôt et provider reste soumis à ses propres auteurs, conditions et licences.

## Addons Stremio

Les addons proposés par défaut ou utilisés comme référence comprennent :

- <https://github.com/Snixi92/nuvio-french-providers>
- <https://tmdb.elfhosted.com/>

Les manifests et routes HTTP sont interrogés à l’exécution ; leur code serveur n’est pas distribué dans l’APK.

## Services de métadonnées

L’extension peut interroger TMDB, AniList, Jikan/MyAnimeList, Stremio et OpenSubtitles. Elle n’est ni approuvée ni certifiée par ces services. Le produit utilise l’API TMDB mais n’est ni approuvé ni certifié par TMDB.
