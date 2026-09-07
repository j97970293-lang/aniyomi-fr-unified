# Suivi des projets amont

Dernière vérification : **6 septembre 2026**. Les références sont les têtes distantes observées pendant la validation de FR Unifié 16.7.

| Projet | Référence vérifiée | Utilisation |
|---|---:|---|
| [Secozzi/aniyomi-extensions](https://github.com/Secozzi/aniyomi-extensions) | `da265eff8291` | Référence Aniyomi demandée, en particulier pour le contrat Stremio : ressources, catalogues, métadonnées, épisodes et flux. |
| [Gowaru/gowaru-nuvio-providers](https://github.com/Gowaru/gowaru-nuvio-providers) | `c3ce6f43a1ba` | Dépôt Nuvio français principal. |
| [D3adlyRocket/Anime-Nuvio](https://github.com/D3adlyRocket/Anime-Nuvio) | `8b2ff46e87b2` | Providers anime multilingues proposés par défaut. |
| [D3adlyRocket/All-in-One-Nuvio](https://github.com/D3adlyRocket/All-in-One-Nuvio) | `716057b2a0d5` | Dépôt optionnel fourni par l’utilisateur, vérifié pour l’import et l’affichage de ses 61 providers ; **non intégré par défaut**. |
| [yoruix/nuvio-providers](https://github.com/yoruix/nuvio-providers) | `ed38d1aee002` | Providers films, séries et animés internationaux. |
| [phisher98/phisher-nuvio-providers](https://github.com/phisher98/phisher-nuvio-providers) | `91cc7194f2b8` | Providers multilingues supplémentaires. |
| [fmustafayaman/turkish-nuvio](https://github.com/fmustafayaman/turkish-nuvio) | `e5a31058b4a1` | Providers turcs. |
| [Snixi92/nuvio-french-providers](https://github.com/Snixi92/nuvio-french-providers) | `78472419ad7c` | Addon Stremio de flux français proposé par défaut. |
| [TMDB Addon / ElfHosted](https://tmdb.elfhosted.com/) | service HTTP | Catalogues Stremio localisés, métadonnées, vidéos et pagination. |
| [cloudstream-fr-unified](https://github.com/j97970293-lang/cloudstream-fr-unified) | `a406aeabb120` | Origine historique de certaines logiques de catalogue et de l’environnement Nuvio ; aucune intégration CloudStream active dans 16.7. |

## Politique d’intégration

- Les manifests et bundles **Nuvio** sont téléchargés à l’exécution ; ils ne sont pas incorporés à l’APK.
- Les addons **Stremio** sont interrogés via leurs ressources déclarées (`catalog`, `meta`, `stream`, `subtitles`) ; chaque entrée `catalogs[]` est exposée séparément et les hosters `stream` sont chargés à la demande.
- Les entrées sont filtrées par ressource, type et préfixes d’identifiant déclarés par le manifest, avec variantes IMDb/TMDB et `series`/`tv` lorsque nécessaire.
- Un dépôt Nuvio ajouté manuellement n’est jamais promu dans les valeurs par défaut ; ses providers deviennent néanmoins visibles dans le sélecteur avant l’application des langues de lecture.
- Les fichiers CloudStream `.cs3` et les `repo.json` ne sont plus importés ni suivis par l’extension.
- Les références `HEAD` permettent de recevoir les mises à jour amont sans republier l’APK, sous réserve que leur format reste compatible.
- Chaque projet, bundle et service reste soumis à ses propres conditions et peut devenir indisponible indépendamment de FR Unifié.

## État des validations réseau

Au 6 septembre 2026 :

- le chemin Stremio localisé a chargé les **12 catalogues** du manifest TMDB, appliqué réellement l’option `genre=2026` au catalogue « Année », recherché **One Piece**, puis chargé sa fiche et ses épisodes ;
- le chemin Stremio de lecture Snixi a exposé son hoster avec Nuvio désactivé et rendu deux flux avec leurs en-têtes lors d’une première passe ; la relecture finale a conservé le contrat mais l’amont a momentanément renvoyé une liste vide ;
- le compteur Jikan paginé a atteint la dernière page de **One Piece** et ne retombe plus artificiellement à 24 épisodes ;
- les sources françaises recommandées restent couvertes par les sondes Kotlin/Rhino ; les liens refusés explicitement en HTTP 403 sont écartés ;
- les bundles internationaux utilisant `for…of` passent désormais par une transformation compatible avec Rhino ; le bundle turc `fullhdfilm` franchit la compilation et atteint son site, qui répondait 403 pendant la validation ;
- les providers Yoruix `vixsrc` et `vidlink` ont franchi le chargement du manifest, l’évaluation Rhino et l’appel TMDB, mais n’ont pas renvoyé de lien pour la sonde choisie ;
- certains bundles Phisher attendent encore un module Node/Cheerio absent du runtime Android ; ils restent sélectionnables, mais leur indisponibilité ne bloque pas la publication.

Ces observations sont un état ponctuel, pas une garantie de disponibilité future.
