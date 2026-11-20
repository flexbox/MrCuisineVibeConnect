# Recettes Cookomix : codes d'erreur

Toque récupère les recettes de cookomix.com en lisant les pages publiques du site (voir
[README.fr.md](../README.fr.md)). Si le site est en panne, refuse les requêtes ou change de format,
la synchro **s'arrête proprement** et affiche un message de la forme :

> Recettes Cookomix non mises à jour, **CKX-05** : pages de recettes illisibles (le site a peut-être
> changé de format) (24 pages lues). Les recettes déjà enregistrées restent disponibles.

Le même message est gardé dans *Réglages › Recettes Cookomix* (« Dernière synchro ») et écrit
dans le journal (*Réglages › Outils › Journal*, lignes `cookomix`).

## Ce qui est garanti dans tous les cas

- **Aucune recette déjà enregistrée n'est supprimée** quand une synchro s'arrête sur une erreur.
  Les suppressions (recettes retirées du site) ne se font qu'à la fin d'une synchro complète et
  normale.
- **Une page illisible n'efface pas sa recette** : la version déjà enregistrée reste, et la page
  est relue à la synchro suivante.
- **Toque ne plante pas** : une page au format inattendu est classée « illisible », jamais traitée
  comme une erreur fatale ; toute erreur imprévue donne le code CKX-07.
- **Rien n'est perdu de la progression** : les recettes déjà converties pendant la synchro
  interrompue sont enregistrées, et la synchro suivante reprend où elle s'était arrêtée.
- **Si Toque s'arrête pendant une synchro** (coupure de courant, mise à jour, plantage), la
  synchro reprend toute seule au démarrage suivant, 3 fois de suite au plus ; au-delà, Réglages
  affiche « Synchronisation interrompue 3 fois de suite : relancez-la à la main. ». Une synchro arrêtée
  sur un code CKX ne reprend pas toute seule : relancez-la, ou attendez la synchro nocturne si
  elle est activée.
- Les recettes Lidl ne sont jamais touchées par la synchro Cookomix.

## Codes

| Code | Signification | Quand | Ce qu'il faut faire |
|---|---|---|---|
| CKX-01 | cookomix.com injoignable | Pas d'Internet sur le robot, DNS, site en panne, délai dépassé (5 échecs de suite). | Vérifier le Wi-Fi du robot ; réessayer plus tard. La synchro nocturne, si elle est activée (désactivée par défaut), réessaie toute seule. |
| CKX-02 | Le site répond par une erreur HTTP | Réponse autre que 200 : 403 (accès bloqué), 404, 429 (trop de requêtes), 5xx (serveur en difficulté), après les reprises automatiques. | Réessayer plus tard. Si l'erreur 403 ou 429 persiste plusieurs jours, le site bloque peut-être les robots : ouvrir une issue avec le journal. |
| CKX-03 | Liste des recettes illisible | Le sitemap d'index ne liste plus de sitemap de recettes, ou l'un d'eux ne contient aucune recette (site restructuré, page de maintenance). | Réessayer plus tard ; si cela dure, le site a changé : une mise à jour de Toque est nécessaire. |
| CKX-04 | Liste anormalement courte | Le site liste moins de la moitié des recettes connues, dès que 100 recettes sont connues (sitemap tronqué, maintenance). Rien n'est supprimé. | Réessayer plus tard. Si le site a vraiment retiré la moitié de ses recettes, ouvrir une issue. |
| CKX-05 | Pages de recettes illisibles | 10 pages illisibles de suite, ou au moins 30 % des pages lues (sur 20 au moins) : la structure des pages a changé (JSON-LD, pas-à-pas `stepbystep`, champs des réglages). Les verdicts négatifs de la synchro sont annulés. | Une mise à jour de Toque est nécessaire : ouvrir une issue avec le journal (les lignes `CKX-05` donnent les pages et la cause). Quelques pages illisibles isolées n'arrêtent pas la synchro : elles sont signalées dans le résultat et relues la fois suivante. |
| CKX-06 | Trop de recettes refusées d'un coup | Au moins 60 % des pages lues (sur 20 au moins) donnent des recettes incompatibles (normalement presque aucune) : le format des réglages a sans doute changé (nouvelles valeurs de vitesse ou de température). Les verdicts négatifs de la synchro sont annulés. | Une mise à jour de Toque est nécessaire : ouvrir une issue avec le journal (les lignes `cookomix` donnent la raison de chaque refus). |
| CKX-07 | Erreur inattendue | Toute autre erreur pendant la synchro (le détail est dans le journal, avec la trace). | Réessayer ; si cela se reproduit, ouvrir une issue avec le journal. |

## Seuils

Les seuils de CKX-04, CKX-05 et CKX-06 sont définis dans
[`Cookomix.kt`](../core/src/main/kotlin/app/toque/core/recipes/Cookomix.kt) (`listLooksWrong`,
`Breaker`) et couverts par les tests de
[`CookomixTest.kt`](../core/src/test/kotlin/app/toque/core/CookomixTest.kt). Sur les 2 437
recettes du site (novembre 2026), aucune page n'était illisible et aucune recette incompatible :
les seuils laissent une large marge avant de se déclencher.

Ouvrir une issue : https://github.com/flexbox/MrCuisineVibeConnect/issues
