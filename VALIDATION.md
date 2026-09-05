# Rapport de validation — FR Unifié 16.2

Date : **6 septembre 2026**

## Contrôles automatiques

| Contrôle | Résultat |
|---|---|
| Spotless (`spotlessCheck`) | Réussi |
| Tests JVM déterministes | 5/5 réussis |
| Sondes réseau Nuvio/Stremio/imports | 4/4 réussies, aucune ignorée lors de la dernière exécution |
| Android Lint debug | Réussi — 0 erreur, 10 avertissements non bloquants |
| APK debug | Construit |
| APK release | Construit et signé |
| Vérification `apksigner` | Réussie, signature APK v2 |
| Génération du dépôt Aniyomi | Réussie (`index.json`, `index.min.json`, `repo.json`, APK et icône) |

## Résultats réseau réels

- **Nuvio / Gowaru** : Anime-Sama a renvoyé **4 liens** pour la sonde *One Piece*.
- **Stremio / Snixi** : **2 flux** jouables lors de la dernière sonde ; en-têtes HTTP présents et faux flux `/troll/master.m3u8` éliminé.
- **Import Nuvio** : dépôt Gowaru reconnu.
- **Import Stremio** : addon Snixi reconnu.
- **Imports CloudStream** :
  - AMSC French (mouradchaouche) : 7 plugins, 3 passerelles reconnues ;
  - CloudStream FR (Nikola17) : 4 plugins, 3 passerelles reconnues ;
  - Movix CloudStream : 1 plugin, 1 passerelle reconnue ;
  - Cs-Karma : 34 plugins, 2 passerelles reconnues.

Les réponses de services tiers restent susceptibles de varier. Snixi avait notamment renvoyé temporairement des listes vides pendant des essais antérieurs avant de réussir la sonde finale.

## APK validé

- Fichier : `FR-Unifie-Aniyomi-v16.2.apk`
- Package : `eu.kanade.tachiyomi.animeextension.fr.frunified`
- Version : `16.2` (`versionCode` 2)
- Android : minSdk 26, targetSdk 34
- Source Aniyomi : `6917344484142790022`
- SHA-256 APK : `39892369fbf96fb50a44d31b367a6db5eb40651004da2332c6d9ddc94124782d`
- Empreinte SHA-256 du certificat : `e2f3ec03556cebbf3d062687eb00282926c0f61d9b4eb8126d788cccf1b1da9e`

## Limite documentée

Aniyomi ne peut pas exécuter un binaire CloudStream `.cs3`. Le port importe le `repo.json`, inventorie ses plugins et active les moteurs Nuvio équivalents reconnus. Il ne prétend pas charger un APK CloudStream dans le processus Aniyomi.
