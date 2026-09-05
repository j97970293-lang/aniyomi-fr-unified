# Attributions et composants tiers

## Base Aniyomi

La structure Gradle et le contrat d’extension utilisent le projet Aniyomi Extensions et l’API `extensions-lib` v16 :

- <https://github.com/aniyomiorg/aniyomi-extensions>
- <https://github.com/aniyomiorg/aniyomi-extensions-lib>
- dépôt de base complémentaire : <https://github.com/Secozzi/aniyomi-extensions>

Les fichiers dérivés de cette base sont distribués selon la licence Apache 2.0 présente dans `LICENSE`.

## FR Unifié CloudStream

Ce port dérive de l’extension CloudStream FR Unifié :

- <https://github.com/j97970293-lang/cloudstream-fr-unified>

Le port remplace les API CloudStream par l’API d’extension Aniyomi, tout en conservant la logique de catalogue et l’environnement d’exécution Nuvio.

## Mozilla Rhino 1.9.1

L’APK embarque une version adaptée et relocalisée de Mozilla Rhino 1.9.1. Rhino est distribué selon la **Mozilla Public License 2.0**.

- source upstream : <https://github.com/mozilla/rhino/tree/Rhino1_9_1_Release>
- texte de licence : `third_party/rhino/MPL-2.0.txt`
- modifications et procédure de reconstruction : `third_party/rhino/PATCHES.md`

## Dépôts Nuvio

L’extension télécharge à l’exécution les manifests et bundles choisis par l’utilisateur. Les dépôts proposés par défaut sont :

- <https://github.com/Gowaru/gowaru-nuvio-providers>
- <https://github.com/z7kx/z7kx-nuvio-provider>
- <https://github.com/phisher98/phisher-nuvio-providers>

Ces bundles ne sont pas incorporés au code source ni à l’APK. Chaque dépôt et chaque provider reste soumis à ses propres auteurs, conditions et licences.

## Addon Stremio et dépôts CloudStream suivis

L’addon Stremio proposé par défaut est fourni par :

- <https://github.com/Snixi92/nuvio-french-providers>

Les dépôts CloudStream de mouradchaouche, Nikola17, blizzx4644 et Kraptor123 sont uniquement inspectés pour leurs métadonnées publiques et l’association de noms à des moteurs Nuvio compatibles. Leurs binaires `.cs3` et leur code ne sont ni incorporés ni exécutés dans l’APK. Consultez `UPSTREAMS.md` pour les URL et révisions contrôlées.

## Services de métadonnées

L’extension peut interroger TMDB, AniList, Jikan/MyAnimeList, Stremio et OpenSubtitles. Elle n’est ni approuvée ni certifiée par ces services. Le produit utilise l’API TMDB mais n’est ni approuvé ni certifié par TMDB.
