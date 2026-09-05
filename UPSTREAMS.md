# Suivi des projets amont

Dernière vérification : **6 septembre 2026**. Les références ci-dessous correspondent aux têtes distantes observées lors de la validation de cette version.

| Projet | Référence vérifiée | Utilisation dans FR Unifié |
|---|---:|---|
| [cloudstream-fr-unified](https://github.com/j97970293-lang/cloudstream-fr-unified) | `a406aeabb120` | Base fonctionnelle portée vers l’API Aniyomi v16 : catalogue commun, modèles de lecture et exécution Nuvio. |
| [Gowaru/gowaru-nuvio-providers](https://github.com/Gowaru/gowaru-nuvio-providers) | `c3ce6f43a1ba` | Dépôt Nuvio français principal, chargé dynamiquement ; 26 providers lors du contrôle. |
| [Snixi92/nuvio-french-providers](https://github.com/Snixi92/nuvio-french-providers) | `78472419ad7c` | Addon Stremio français proposé par défaut ; Nakios, Purstream, Movix, ToFlix, FrenchStream, Nakastream et Vstream. |
| [bluecxt/anime-extensions-french](https://github.com/bluecxt/anime-extensions-french) | `d6eddc7f413c` | Référence Aniyomi/AniZen française et contrôle de compatibilité des sources animées. |
| [Nikola17/cloudstream-frenchstream](https://github.com/Nikola17/cloudstream-frenchstream) | `3bc8570aeacc` | Dépôt CloudStream suivi et importable ; providers compatibles associés à leurs moteurs Nuvio. |
| [blizzx4644/Movix-cloudstream](https://github.com/blizzx4644/Movix-cloudstream) | `ac9a9c9b8348` | Dépôt CloudStream Movix suivi et passerelle vers le provider Nuvio `movix`. |
| [Kraptor123/Cs-Karma](https://github.com/Kraptor123/Cs-Karma) | `0467f84944bd` | Dépôt CloudStream suivi ; associations automatiques des providers connus. |
| [mouradchaouche/cloudstream-frenchrepo](https://github.com/mouradchaouche/cloudstream-frenchrepo) | `0da82ea5ee1c` | Dépôt CloudStream français suivi ; associations Wiflix/Flemmix, French Anime, Coflix, FsMirror/FrenchStream, etc. |
| [mouradchaouche/cs-repos](https://github.com/mouradchaouche/cs-repos) | `2328e06772a7` | Agrégateur contrôlé pour retrouver les dépôts CloudStream publics du même auteur. |

## Politique d’intégration

- Les manifests et bundles **Nuvio** sont téléchargés au moment de l’utilisation ; ils ne sont pas incorporés à l’APK.
- Les addons **Stremio** sont interrogés via leur contrat HTTP `manifest.json`, `stream` et `subtitles`.
- Les binaires **CloudStream `.cs3` ne sont pas exécutables dans Aniyomi**. L’import d’un `repo.json` inventorie les plugins et active les scrapeurs Nuvio équivalents lorsque leur identité est reconnue.
- Les dépôts CloudStream sans licence explicite ne sont pas recopiés dans ce dépôt. Seuls leurs métadonnées publiques et les noms nécessaires aux associations sont lus à l’exécution.
- Les références `HEAD` configurées permettent de recevoir les mises à jour amont sans republier l’APK, sous réserve que le format du manifest reste compatible.

## Validation réseau de référence

Au 6 septembre 2026 :

- Gowaru/Nuvio : test réel réussi sur *One Piece*, avec **4 liens** obtenus via Anime-Sama ;
- import réel : Nuvio, Stremio et les quatre dépôts CloudStream détectés correctement ; les quatre dépôts CloudStream ont au moins une passerelle reconnue ;
- Snixi/Stremio : manifest, route `stream` et lecture réelle validés ; la dernière sonde a obtenu **2 flux** avec leurs en-têtes HTTP. Le service avait renvoyé temporairement des listes vides lors de contrôles précédents, ce qui confirme qu’il peut être intermittent ;
- le parseur Stremio est aussi testé hors réseau pour les en-têtes HTTP, la qualité, la langue et le filtrage du faux flux `/troll/master.m3u8`.

La disponibilité des sites et services tiers peut changer indépendamment de l’extension.
