# PROMPT — Modèle de prix de revente Skinport (arbitrage)

## Objectif

Je développe un programme d'arbitrage sur Skinport.

Le programme repère des skins affichés à un prix suffisamment bas, puis, juste avant leur achat, recalcule si l'opération reste rentable.

Le point central du programme est l'estimation du **prix de revente attendu sur Skinport**.

Je veux 4 prix de revente correspondant à 4 vitesses de vente :

1. `VERY_FAST` — objectif : vendre très rapidement
2. `FAST` — objectif : vendre rapidement, idéalement autour de 1 jour
3. `NORMAL` — objectif : compromis, dans mon usage environ 1–2 jours
4. `PATIENT` — objectif : accepter d'attendre davantage pour maximiser la marge

Le modèle doit utiliser UNIQUEMENT l'état actuel du marché et l'historique disponible.

### Règle fondamentale

**Ne PAS prévoir l'évolution du prix dans le temps.**

L'API ne fournit pas la durée restante du trade-lock d'un item précis. Le programme ne doit donc pas essayer de prédire le marché à +1 jour, +2 jours, etc.

Au moment où le programme vérifie l'opportunité d'achat, il veut simplement répondre :

> « Si j'achète cet item au prix actuel et que je le remets ensuite en vente sur Skinport, quels sont les 4 prix auxquels il serait raisonnable de le proposer MAINTENANT, selon la vitesse de vente désirée ? »

---

# 1. Données disponibles

Le programme interroge Skinport toutes les ~5 minutes.

## A. `/v1/sales/history`

Pour chaque `market_hash_name` :

- `last_24_hours`
- `last_7_days`
- `last_30_days`
- `last_90_days`

Chaque fenêtre contient :

- `min`
- `max`
- `avg`
- `median`
- `volume`

Ces fenêtres se chevauchent.

IMPORTANT :
ne jamais additionner ou pondérer naïvement `V24`, `V7`, `V30`, `V90` comme s'il s'agissait de volumes indépendants.

Le même trade appartient à plusieurs fenêtres.

## B. `/v1/items?tradable=true`

Marché actuellement immédiatement tradable :

- `min_price`
- `max_price`
- `mean_price`
- `median_price`
- `suggested_price`
- `quantity`

Ce marché est le signal le plus important pour la revente immédiate.

## C. `/v1/items?tradable=false`

ATTENTION :

`tradable=false` ne signifie PAS « uniquement les items sous trade-lock ».

D'après le document de référence :

- `tradable=false` = toutes les annonces, y compris les items sous trade-lock
- `tradable=true` = uniquement les annonces immédiatement tradables

Donc le deuxième appel est un SUPerset du marché tradable.

On peut comparer les deux marchés, mais on ne dispose pas d'un endpoint donnant directement :

```text
locked_min
locked_median
locked_mean
locked_quantity
```

pour les seuls items verrouillés.

Il ne faut donc surtout pas inventer ces valeurs.

Le marché `tradable=false` peut néanmoins fournir des informations sur la pression d'offre globale et sur la différence entre l'ensemble du stock et le stock immédiatement tradable.

---

# 2. Modèle économique exact

Le scénario est le suivant :

```text
Un vendeur met son skin en vente sur Skinport
        ↓
le skin est dans l'inventaire Skinport
        ↓
il peut être sous trade-lock
        ↓
mon programme repère une offre peu chère
        ↓
j'attends / vérifie jusqu'au moment où elle devient achetable
        ↓
juste avant l'achat :
   je recalcule toutes les données
        ↓
si l'achat reste rentable :
   j'achète
        ↓
je remets le skin en vente sur Skinport
```

Il ne faut donc PAS inclure le temps de trade-lock dans le calcul du prix espéré.

---

# 3. Priorité des données

Pour le prix de revente, utiliser approximativement cette hiérarchie :

### Très important

1. Marché `tradable=true`
2. Médiane des ventes récentes
3. Volume des ventes récentes
4. Dispersion du marché actuel

### Important

5. Tendance récente entre 24 h / 7 j / 30 j
6. Quantité actuellement tradable
7. Comparaison marché global (`tradable=false`) vs marché tradable

### Faible

8. `suggested_price`
9. anciennes fenêtres 30/90 jours lorsqu'elles sont très différentes des données récentes
10. `min` / `max` historiques

---

# 4. Ne pas confondre FAIR VALUE et PRIX DE VENTE

Calculer d'abord :

```text
fair_value
```

qui représente la meilleure estimation actuelle de la valeur du skin.

Puis calculer séparément :

```text
sell_price_very_fast
sell_price_fast
sell_price_normal
sell_price_patient
```

Les 4 prix doivent être différents parce que je suis prêt à sacrifier de la marge pour vendre plus rapidement.

---

# 5. Fair value historique

Construire une estimation robuste à partir des médianes historiques.

Ne pas utiliser directement :

```text
(M24*V24 + M7*V7 + M30*V30 + M90*V90)
/
(V24+V7+V30+V90)
```

car les fenêtres se chevauchent.

Possibilité 1 :

utiliser uniquement les médianes avec des poids de récence, puis corriger les poids en fonction de la confiance apportée par les volumes.

Possibilité 2, préférable si pertinent :

reconstruire des intervalles non chevauchants :

```text
volume_0_1d   = V24
volume_1_7d   = V7  - V24
volume_7_30d  = V30 - V7
volume_30_90d = V90 - V30
```

puis utiliser ces intervalles pour mesurer la récence des observations.

Le modèle doit choisir la meilleure méthode statistique.

La combinaison finale peut être une moyenne géométrique/logarithmique afin d'éviter qu'une fenêtre extrême domine excessivement.

---

# 6. Marché actuel tradable

Le marché `tradable=true` doit jouer un rôle majeur.

Variables :

```text
Tmin
Tmedian
Tmean
Tsuggested
Tquantity
```

### Important

`Tmin` n'est pas forcément la fair value.

Un seul vendeur peut être très agressif.

Donc :

- `Tmin` = excellent signal pour VERY_FAST
- `Tmedian` = meilleur signal du niveau général du marché
- `Tmean` = signal secondaire
- `Tsuggested` = signal secondaire/faible

Détecter les situations du type :

```text
Tmin << Tmedian
```

mais ne pas supprimer automatiquement `Tmin`.

Une anomalie réelle peut représenter une opportunité.

Le système doit distinguer :

```text
prix actuellement très bas car le marché est réellement bas
```

de :

```text
un seul listing anormalement bas
```

---

# 7. Tendance : uniquement pour le niveau ACTUEL, pas pour prédire le futur

La tendance récente peut servir à savoir si le marché actuel est en train de monter ou descendre.

Elle peut comparer :

```text
M24
M7
M30
```

par exemple via :

```text
trend_short  = ln(M24 / M7)
trend_medium = ln(M7 / M30)
```

Mais :

**NE PAS utiliser cette tendance pour projeter un prix à la date de fin du trade-lock.**

Elle doit uniquement servir de correction prudente de la fair value actuelle.

Une tendance récente forte basée sur très peu de ventes doit avoir une faible confiance.

---

# 8. Utilisation de `tradable=false`

Le marché global peut être comparé au marché tradable :

```text
Gmin
Gmedian
Gmean
Gquantity
```

avec :

```text
Tmin
Tmedian
Tmean
Tquantity
```

Attention : puisque le marché global contient aussi les items tradables, il ne faut pas interpréter directement :

```text
Gquantity - Tquantity
```

comme une mesure parfaite de « quantité locked » sans vérifier la sémantique exacte des données retournées.

Si la différence est utilisable comme approximation, la traiter comme un signal incertain et faible.

Utiliser surtout les écarts de niveau de prix entre marché global et marché tradable comme information secondaire.

---

# 9. Liquidité

Créer un `liquidity_score` à partir notamment de :

```text
V24
V7
Tquantity
```

et de la stabilité des prix historiques.

Un skin très liquide :

- peut supporter un prix proche de la fair value ;
- a généralement une meilleure probabilité de vente ;
- permet d'utiliser un prix patient plus proche de la fair value.

Un skin peu liquide :

- nécessite un prix plus agressif pour vendre rapidement ;
- doit avoir des intervalles plus importants entre FAST et PATIENT ;
- doit recevoir une confiance plus faible.

---

# 10. Les quatre prix

Les 4 prix doivent être dérivés du même état de marché.

## VERY_FAST

Objectif :

> maximiser la probabilité de vente rapide.

Le prix doit être proche de la concurrence immédiatement tradable.

Point de départ :

```text
VERY_FAST ≈ Tmin - tick
```

où `tick` vaut généralement 0,01 € ou un incrément configurable.

Mais si `Tmin` est détecté comme anomalie extrême, ne pas descendre automatiquement jusqu'à lui.

Créer une protection basée sur :

```text
Tmin / fair_value
Tmin / Tmedian
```

## FAST

Objectif :

> vendre rapidement, avec un peu plus de marge que VERY_FAST.

Le prix doit généralement se situer entre `Tmin` et `Tmedian`.

Forme possible :

```text
FAST = Tmin + k_fast * (Tmedian - Tmin)
```

où `k_fast` dépend de la liquidité et de la confiance.

## NORMAL

Objectif :

> compromis prix / vitesse.

Le prix doit être proche de la meilleure estimation actuelle :

```text
NORMAL ≈ fair_value
```

mais doit rester cohérent avec `Tmin` et `Tmedian`.

Pour mon utilisation habituelle, NORMAL doit être calibré pour une vente visée autour de 1–2 jours, mais sans projection temporelle : il s'agit d'un niveau de prix et non d'une garantie de délai.

## PATIENT

Objectif :

> accepter plus d'attente pour obtenir une meilleure marge.

Le prix peut être supérieur à `fair_value`.

La prime ne doit pas être fixe.

Elle doit dépendre au minimum de :

- liquidité ;
- dispersion des listings ;
- différence entre `Tmin` et `Tmedian` ;
- confiance de l'estimation ;
- tendance actuelle.

---

# 11. Très important : la vitesse ne doit PAS être codée seulement comme un pourcentage

Éviter un système simpliste du type :

```text
VERY_FAST = fair_value * 0.95
FAST      = fair_value * 0.98
NORMAL    = fair_value
PATIENT   = fair_value * 1.05
```

Le même pourcentage ne convient pas à tous les skins.

Exemple :

### Marché A

```text
100 €
101 €
102 €
102 €
103 €
```

Mettre +5 % est probablement trop agressif.

### Marché B

```text
80 €
95 €
96 €
98 €
100 €
```

La profondeur et la structure du marché sont complètement différentes.

Les quatre prix doivent donc être adaptés à la structure réelle des annonces.

---

# 12. Prix relatifs au marché

Le modèle doit idéalement utiliser des quantiles / percentiles implicites du marché actuel.

Avec seulement les statistiques agrégées fournies par l'API, il n'est pas possible de reconstruire la distribution exacte des listings.

Il faut donc utiliser ce qui est effectivement disponible :

```text
Tmin
Tmedian
Tmean
Tmax
Tquantity
```

et non inventer un carnet d'ordres détaillé.

---

# 13. Gestion des cas extrêmes

Le modèle doit avoir des garde-fous.

### Cas 1 : aucune vente récente

Si :

```text
V24 = 0
```

ne pas considérer `M24` comme une information disponible.

Même logique pour les autres fenêtres lorsqu'elles ont `volume=0` et les champs à `null`.

### Cas 2 : très faible volume

Réduire fortement la confiance.

### Cas 3 : marché tradable vide

Ne pas faire :

```text
Tmin = 0
```

Le modèle doit basculer davantage vers l'historique et retourner une confiance faible.

### Cas 4 : un minimum aberrant

Ne pas laisser un seul listing complètement anormal déterminer les quatre prix.

### Cas 5 : historique et marché actuel très différents

Ne pas faire une moyenne aveugle.

Détecter explicitement :

```text
market_disagreement
```

et réduire `confidence_score`.

---

# 14. Sortie du modèle

Pour chaque skin :

```text
fair_value
confidence_score

price_very_fast
price_fast
price_normal
price_patient

liquidity_score
market_disagreement
trend_score
```

Puis calculer le bénéfice potentiel à partir du prix d'achat :

```text
net_sale_price = sell_price * (1 - selling_fee_rate)

profit = net_sale_price - purchase_price

ROI = profit / purchase_price
```

Les frais doivent rester configurables.

---

# 15. Décision d'achat

Le programme trouve une offre :

```text
purchase_price
```

Il recalcule alors immédiatement :

```text
price_very_fast
price_fast
price_normal
price_patient
```

et leurs profits nets.

Le programme peut ensuite imposer des filtres comme :

```text
minimum_profit
minimum_ROI
minimum_confidence
strategy = VERY_FAST | FAST | NORMAL | PATIENT
```

Exemple :

```text
purchase_price = 90 €

NORMAL = 105 €

frais = 8 %

net = 96.60 €

profit = 6.60 €
```

L'achat est intéressant uniquement si cela satisfait les règles de stratégie.

---

# 16. Ce modèle doit être optimisé par backtesting

Les coefficients ne doivent pas être considérés comme définitifs.

Le programme tourne toutes les ~5 minutes.

Stocker :

```text
timestamp
market_hash_name

Tmin
Tmedian
Tmean
Tquantity

Gmin
Gmedian
Gmean
Gquantity

M24
M7
M30
M90

V24
V7
V30
V90
```

Puis utiliser les données futures observées uniquement pour mesurer si les prix proposés par le modèle étaient bons.

Le backtesting doit répondre à des questions comme :

```text
Si le modèle proposait 99 € alors que le marché était à 100 €,
combien de temps avant qu'une vente passe à ce niveau ?

Si le modèle proposait 103 €,
quelle était la probabilité de vente rapide ?

Quel niveau maximise réellement :
profit × probabilité de vente ?
```

Ainsi les paramètres de `VERY_FAST`, `FAST`, `NORMAL`, `PATIENT` peuvent progressivement être appris à partir des données réelles de Skinport.

---

# 17. Objectif final

Le modèle ne cherche PAS à prédire le prix futur du skin.

Il cherche à estimer la relation :

```text
état actuel du marché
        ↓
fair_value actuelle
        ↓
4 niveaux de prix
        ↓
différentes probabilités / vitesses de vente
```

avec comme priorité pratique :

```text
FAST / NORMAL
```

car l'objectif habituel est de revendre dans environ 1–2 jours, tout en acceptant qu'il n'existe aucune garantie de délai.

La qualité du modèle est plus importante que sa simplicité.

Ne pas simplement reprendre les coefficients proposés ici.
Proposer la meilleure méthode statistique possible avec les informations réellement disponibles, sans inventer de données que l'API ne fournit pas.

---

# Implémentation actuelle — prix « Très rapide » v2 (novembre 2026)

Objectif : le prix auquel l'item **se vend en 24-48 h**, calculé toutes les 5 minutes par
`FastSellModel` (`SkinGap.java`) et exporté dans `skins.json` :

| champ | sens |
|---|---|
| `xf` | prix brut conseillé (avant commission Skinport) |
| `xl` | ventes par jour estimées (λ) |
| `xp` | probabilité estimée de vente sous 48 h à ce prix |
| `xm` | branche du calcul (`target`, `best-effort`, `+cap`, `no-sales`) |
| `xb` | prix de revente si l'on achète le Min tradable lui-même (plafond : 2e annonce tradable) |
| `xbp` | probabilité de vente sous 48 h au prix `xb` |
| `l2` | prix de la 2e annonce tradable (seulement à 2 ou 3 annonces, où l'API le donne exactement) |

Le site (mode « Très rapide »), la page détail et le flux live du serveur utilisent `xf`.
L'extension doit lire `xf` dans `skins.json`, ou recalculer avec `fast-price.js` (portage
JavaScript exact, vérifié identique au centime sur tous les items).

## Ce que les données réelles ont montré

Mesures faites le 17/11/2026 sur le flux live Skinport (WebSocket officiel : chaque annonce et
chaque vente en temps réel) croisé avec une photo de l'API REST prise au même instant :

1. **Deux files d'attente séparées.** Les ventes d'items tradables se font au min tradable `L`
   (écart médian 0,0 %), celles d'items sous trade-lock au min verrouillé `G` (~10 % moins cher).
   L'historique REST mélange les deux.
2. **Le rythme de ventes λ** estimé par fenêtres disjointes pondérées par récence prédit bien les
   ventes observées sur les skins (104 attendues / 96 observées). L'ancien taux (ventes 24 h,
   sinon 7 j / 7…) surestimait d'environ 33 %.
3. **La tête de file tradable est achetée à un rythme λ·A(r)**, où r = prix / médiane des prix
   payés récemment. A diminue quand le prix monte au-dessus de la médiane (élasticité-prix),
   mais baisser sous ~0,89 × médiane n'accélère plus la vente.
4. **Être doublé** par une annonce moins chère est rare tant que le prix est proche de la médiane.

## Calcul

1. λ et la médiane récente `med` : fenêtres 24 h / 7 j / 30 j / 90 j redécoupées en périodes
   disjointes, pondérées par ancienneté (demi-vie adaptative), mélange de lois log-normales.
2. Probabilité de vente sous 48 h au prix P : `1 − exp(−2 · λ · A(P / med))`.
3. Prix = le plus haut qui atteint 80 % de chances sous 48 h ; si c'est impossible (item trop
   peu liquide), le prix qui donne la meilleure chance réelle (`best-effort`, probabilité < 80 %
   affichée) ; jamais au-dessus de l'annonce tradable concurrente − 1 centime (voir ci-dessous).

## Quel concurrent plafonne la revente ? (correctif du 18/11/2026)

L'annonce qu'on achète ne peut pas servir de plafond à sa propre revente. Le plafond dépend donc
de ce qu'on achète :

| on achète… | concurrent après l'achat | champ |
|---|---|---|
| une offre trade-protect moins chère que le Min tradable (achetable tout de suite, revendue une fois livrée) | le Min tradable | `xf` |
| un item qu'on a déjà (dashboard) | le Min tradable | `xf` |
| le Min tradable lui-même | la **2e** annonce tradable | `xb` |

L'API (`/v1/items`) ne donne que min / médiane / moyenne / max / quantité : la 2e annonce est
connue exactement à 2 annonces (= max) et à 3 annonces (= médiane). Avec une seule annonce
tradable, il ne reste aucun concurrent : le prix est borné au 90e centile des prix payés
récemment. Au-delà de 3 annonces, la 2e annonce est inconnue et `xb` est absent : on garde `xf`
(plafond sous le Min, prudent).

Conséquence, vérifiée sur une photo complète du marché (18/11/2026, 24 928 items, paramètres
recalibrés) : en achetant le Min tradable, l'écart « Très rapide » ne peut être positif que si la
2e annonce est nettement plus chère. C'est arrivé sur 85 items, tous très peu liquides (aucun avec
≥ 30 % de chances de vente sous 48 h). Côté tradable, le marché ne laisse donc pas d'annonce sous
son prix de revente rapide. Les écarts positifs viennent des offres trade-protect : 567 items en
avaient une sous le prix net de revente rapide, dont 169 avec ≥ 50 % de chances de vente sous
48 h (107 avec ≥ 80 %). Sur la page, le sélecteur
« Achat » choisit le prix d'achat : « Min tradable » (achetable maintenant, par défaut) ou
« Min (TP) » (offre trade-protect : achetable tout de suite, l'item n'arrive qu'à la fin de sa
protection).

**Deux niveaux de prix dans l'historique des ventes.** Les items encore sous trade-protect peuvent
être achetés : flux live du 17/11, 25 % des ventes (34-35 % pour fusils, pistolets, gants), à
~8 % sous le prix des ventes tradables du même item sur le même créneau (rapport médian 0,917).
Dans l'historique de l'API, elles occupent le quart bas (≈ 25e centile) ; comme les ventes
tradables sont majoritaires, la médiane tombe sur leur niveau (vente tradable réelle / médiane de
l'historique : 1,006 sur 79 items liquides). Le modèle garde donc la médiane comme prix typique ;
un centile plus haut (75e) ne prédit qu'à peine mieux les ventes du lendemain (+10 de
log-vraisemblance sur 17 903 items) et surestime le niveau tradable d'environ 5 %.

**Bug corrigé le même jour :** l'appel « tradable » de l'API n'avait pas `tradable=true`. Or
sans ce paramètre l'API renvoie toutes les annonces, trade-protect compris. Le « Min tradable »
était donc le min trade-protect compris : `Min` et `Min (TP)` étaient identiques pour tous les
items, et le prix « Très rapide » était plafonné sous l'offre trade-protect au lieu du Min
tradable. 19 % des items étaient concernés (offre TP ~9,5 % moins chère) ; sur 1 845 d'entre eux,
le prix était trop bas (médiane −6,7 %, P90 −26 %).

## Paramètres et validation

`A(r) = aMax` si `r ≤ rLo`, sinon `aMax · (r / rLo)^-b` ; taux d'achat = `λ^0,8 · A(r)`.

| famille | aMax | rLo | b |
|---|---|---|---|
| skins, couteaux, gants, charms, agents, music kits… | 0,40 | 0,94 | 3,25 |
| stickers et caisses | 0,21 | 0,85 | 1,5 |

**Recalibré le 18/11/2026 sur une journée entière.** Méthode : une photo complète du marché
(17/11 17:24 UTC), puis les ventes réellement faites dans les 24 h suivantes (photo du 18/11
18:10 UTC) ; pour chaque item, une annonce placée 1 centime sous le Min tradable aurait-elle été
achetée ? 17 903 items, 1 705 ventes, maximum de vraisemblance, validation croisée (appris sur un
item sur deux, testé sur les autres : log-vraisemblance −2 056 contre −2 287 avec les anciens
paramètres). La 1re calibration (1 h 10 de flux live en soirée, aMax 0,80 / rLo 0,89 / b 3,0)
surestimait les ventes d'environ 50 % : elle avait été faite à l'heure de pointe. Prédit / observé
après recalibration, skins : 282 / 257 (< 0,3 vente/j), 477 / 467 (0,3-1), 425 / 463 (1-3),
195 / 199 (3-10). Intervalles à 95 % (skins) : rLo [0,91 ; 0,96], b [3 ; 4]. Pour les stickers,
rLo est borné à 0,85 (sans borne il descendrait vers 0,3, c'est-à-dire brader l'item, pour un gain
de vraisemblance négligeable).

Conséquences : 2,4 % des items peuvent se vendre sous 48 h avec ≥ 80 % de chances (il faut
environ 2,4 ventes/jour pour un skin). Pour les autres, le prix est celui qui donne la meilleure
chance (0,94 × prix typique des dernières ventes pour un skin), et la probabilité affichée le dit.

## « Le prix devrait suivre les dernières ventes, pas les annonces » — testé

Hypothèse : s'il y a des annonces moins chères que les dernières ventes, elles vont partir, et
l'item se revendra au niveau des ventes. Test sur les 24 h entre les deux photos. Sur 218 items
dont le Min tradable était au moins 6 % sous les dernières ventes :

- seuls 26 % ont eu une vente médiane, le lendemain, au niveau des ventes passées ;
- même en prenant la vente la plus chère de la journée, seuls 39 % atteignent ce niveau ;
- le Min du lendemain était inchangé (médiane).

Les annonces moins chères sont donc, le plus souvent, le nouveau prix du marché. Si l'on enlève
le plafond « Min − 1 centime » pour vendre au prix des ventes, les items liquides se vendent
dans 12 % des cas en 24 h, contre 55 % avec le plafond. Le prix « Très rapide » part donc des
ventes, mais reste plafonné 1 centime sous l'annonce tradable concurrente. Le prix des ventes
passées reste atteignable avec de la patience : c'est le mode « Médiane ».

Limites : une seule journée de recul (un mardi-mercredi de novembre) ; à refaire de temps en temps
avec la même méthode (deux photos complètes à 24 h d'écart suffisent).

## Revente sous trade-protect : deux prix espérés (19/11/2026)

Les items encore sous trade-protect peuvent être vendus tout de suite : l'acheteur les reçoit à la
fin de la protection. Ils se vendent moins cher. Mesures sur le flux live, deux enregistrements :
mardi 17/11 de 18 h à 21 h et jeudi 19/11 de 1 h à 14 h (heure française). Skins = armes, couteaux,
gants, agents : 2 324 ventes, dont 600 protégées (26 %).

| protection restante | ventes protégées | prix / prix typique des ventes | prix / Min tradable |
|---|---|---|---|
| < 1 jour | 57 | 0,934 | 0,929 |
| 1-2 jours | 41 | 0,955 | 0,903 |
| 2-3 jours | 40 | 0,950 | 0,906 |
| 3-4 jours | 45 | 0,948 | 0,882 |
| 4-5 jours | 58 | 0,959 | 0,928 |
| 5-6 jours | 57 | 0,964 | 0,911 |
| 6-7 jours | 140 | 0,941 | 0,876 |
| 7-8 jours (acheté il y a moins d'un jour) | 162 | 0,902 | 0,877 |

(Ventes tradables : 1,000 × le prix typique, 1,000 × le Min tradable.)

Régression médiane sur les 600 ventes : prix ≈ `(0,964 − 0,006 × j) × prix typique des ventes`.
Cela donne 0,96 à 0 jour et 0,92 à 7 jours, avec un intervalle à 90 % de 0,916 à 0,931 à 7 jours.
Le premier réglage (`0,958 − 0,0035 × j`, mesuré sur la seule soirée du 17/11) surestimait le prix à
7 jours d'environ 1 %. Entre 1 et 6 jours, les acheteurs ne paient guère plus pour une protection
plus courte (0,95-0,96). La décote se creuse surtout pour les items revendus juste après l'achat
(7-8 jours restants, 0,90), qui sont aussi les reventes les plus pressées.

**Rythme de vente et moment de la journée.** On regarde l'offre protégée la moins chère (moins
chère que le Min tradable) et on se demande si elle est achetée dans la fenêtre. La prévision
utilise le modèle tradable, avec comme référence la décote à 4,8 jours (durée restante médiane des
offres achetées), soit 0,935 × le prix typique. L'activité du moment se mesure sur le front
tradable de tous les items : ventes observées / prévues par le modèle, calibré sur une journée
entière.

| fenêtre (heure française), skins | activité du moment | offre protégée : achetées / prévues | κ = rapport des deux |
|---|---|---|---|
| jeudi 1 h-3 h | × 0,50 | 34 / 89,7 = 0,38 | 0,76 |
| jeudi 3 h-11 h (téléphone en veille, moins fiable) | × 0,42 | 83 / 337,7 = 0,25 | 0,59 |
| jeudi 11 h-13 h | × 1,00 | 51 / 82,9 = 0,62 | 0,61 |
| jeudi 13 h-14 h | × 1,28 | 8 / 30,6 = 0,26 | 0,20 |
| mardi 18 h-21 h | × 1,51 | 131 / 136,8 = 0,96 | 0,63 |

Une offre protégée part donc à ~0,6 × le rythme d'une annonce tradable au même prix relatif. La
moyenne de la journée, pondérée par l'activité, donne **κ = 0,60** (intervalle à 90 % de 0,54 à
0,67 ; skins 0,60, stickers et caisses 0,55). Le premier réglage était 0,66, mesuré sur la seule
soirée, la plus active.

Effets de l'heure :
- **Activité** : le marché va environ 3 fois plus vite le soir que la nuit, pour les ventes
  tradables comme protégées. Les probabilités affichées sont des moyennes sur 48 h. Mettre en vente
  en fin d'après-midi ou le soir aide.
- **Part des ventes protégées** : elle est plus forte la nuit (30-48 % des ventes de skins entre
  1 h et 8 h) qu'à midi (~15 %).
- **Prix** : la nuit, les ventes protégées se font ~3,5 % moins cher (0,89 × le prix typique avec
  au moins 5 jours restants, contre 0,93 en journée et le soir).

Le modèle ne tient pas compte de l'heure : sur 48 h, toutes les heures y passent.

À noter : l'historique des ventes renvoyé par l'API (`/v1/sales/history`) était identique à l'octet
de 1 h à 14 h le 19/11. Skinport ne le rafraîchit visiblement que rarement, et le « prix typique des
ventes » peut donc avoir plusieurs heures de retard.

Calcul du prix « Très rapide » sous trade-protect, avec `j` jours de protection restants :
- même méthode que le tradable ;
- référence : `(0,964 − 0,006 × j) × prix typique des ventes` ;
- rythme : × 0,60 ;
- plafond : 1 centime sous l'offre la moins chère, protégée ou non.

`skins.json` donne `xt` et `xtp` pour 7 jours (protection fraîche, juste après un achat). La page
recalcule pour n'importe quel nombre de jours à partir de `xs`, `xl`, `ask` et `min`, et
`fast-price.js` fournit `fastTpPrice`. Les trois donnent le même prix au centime (vérifié sur
19 129 items).

Sur la page, le sélecteur « Revente » choisit le prix espéré : tradable après la protection, ou sous
trade-protect tout de suite, avec les jours restants. L'autre prix est affiché en petit, et
l'infobulle donne le gain net d'attendre la fin de la protection, en € et en % par jour. Pour un skin
liquide (au moins 1 vente par jour), attendre rapporte en général ~1,2 %/jour avec 7 jours restants,
~2 %/jour à 3 jours et ~4,5 %/jour à 1 jour. Vendre sous trade-protect ne vaut donc le coup que si
l'argent rapporte plus ailleurs, ou si le prix risque de baisser.

Limites :
- **Couverture horaire** : 15 h de flux sur deux jours ; il manque l'après-midi (14 h-18 h) et la
  fin de soirée (21 h-1 h).
- **Stickers et caisses** : trop peu de ventes protégées pour une décote propre. Les caisses sortent
  à 0,88 le 19/11 mais à 0,99 le 17/11, dominées par quelques achats en gros. Elles gardent donc la
  décote des skins.

# Page « annonces » (`annonces.html`, novembre 2026)

Elle est accessible par le bouton « → annonces » de la page des prix. C'est une page statique du dépôt, copiée sur le VM à chaque exécution du workflow, à côté de `skingap.html`. Elle lit en relatif trois fichiers :
- `skins.json` : le modèle de prix ;
- `book.json` : le carnet d'annonces du VM, reconstruit depuis le flux live Skinport et recalé toutes les 5 min sur une photo de l'API. Un lien dans le dossier des fichiers statiques du serveur pointe vers `~/skinport-data/book/book.json`.
- `patterns.json` : les offres de l'onglet « Patterns rares » (voir plus bas).

Elle ne contient ni données ni secret.

## Lots tradables

Pour chaque skin, la page essaie d'acheter les k annonces les moins chères.

**Revente visée** : 1 centime sous l'annonce suivante (le « mur »).
- Elle est plafonnée au haut des ventes récentes : 90e centile des ventes tradables vues dans le flux, ou à défaut le prix « Très rapide » sans concurrent.
- Si le lot ne part pas dans le délai, la revente est baissée tant que la marge tient.

**File d'attente.** Nos exemplaires partent l'un après l'autre :
- au rythme `λ^0,8 · A(prix / prix typique)` du modèle « Très rapide » ;
- avec λ = l'estimation de l'API complétée par les ventes vues dans le flux ;
- modulé par l'activité selon l'heure, mesurée dans le flux.

Deux sortes d'annonces passent devant nous :
- les nouvelles annonces moins chères attendues (rythme mesuré dans le flux) ;
- les annonces inconnues moins chères, estimées d'après l'API. La moitié des annonces coûte moins que la médiane, réparties entre le min et la médiane. Un réglage permet de les ignorer.

**Choix du lot** : le k qui maximise le gain total, avec la marge minimale (en € et en %, après commission) sur chaque annonce, le délai (24 ou 48 h) et le budget.

## Offres trade-protect

Les jours restants de chaque offre sont exacts, lus dans le carnet.
- **Écart affiché** = écart attendu − z·σ·√(jours restants + 2).
- **σ** (volatilité journalière) est pour l'instant provisoire : tirée des prix typiques des ventes 24 h / 7 j / 30 j / 90 j, avec un plancher de 1,5 %/jour. Les photos horaires prendront le relais quand on aura une semaine de données.
- **Filtre** : seuls les items revendables sous 48 h avec au moins 50 % de chances (`xp`) sont affichés.

## Patterns rares (25/11/2026)

Charms, Case Hardened, Heat Treated et Fade valent plus ou moins cher selon le # de l'exemplaire (template du charm, % de bleu, % de fade). Leurs ventes mélangent exemplaires ordinaires et recherchés, donc un exemplaire ordinaire paraît « sous le marché ».
- Ils sont **exclus** des lots, des offres trade-protect (`isPatternItem`) et des alertes de prix du VM (`deals.py`).
- Sur la page des prix, ils sont **masqués par défaut** (case « Masquer charms et skins à pattern ») ; affichés, ils portent un `#` et leur écart est grisé.

L'onglet **Patterns rares** montre l'inverse : une annonce vue dans le flux dont le # se vend cher, mise en vente au prix d'un exemplaire ordinaire. Il lit `patterns.json`, écrit chaque minute sur le VM par `~/skinport-watch/patterns.py`, qui envoie aussi l'alerte ntfy (annonce de moins de 30 min). Un lien dans le dossier des fichiers statiques pointe vers `~/skinport-data/book/patterns.json`.
- **Valeur** : la plus basse des ventes comparables vues dans le flux (30 jours), ramenée au prix suggéré actuel. Comparables : même arme et finition (une autre usure compte pour moitié) ou même charm ; % de bleu entre x − 12 et x, % de fade entre x − 3 et x (annonces à 90 % ou plus), template à 5 000 près.
- Seules comptent les ventes « au prix du pattern » : au moins 2 × le prix suggéré (1,2 × pour Fade, 1,4 × le prix habituel du charm).
- Il faut 2 ventes comparables (1 pour Heat Treated) et un gain net d'au moins 2 € et 30 %.
- Rejoué minute par minute sur le flux du 20 au 25/11 : 8 notifications, toutes des Heat Treated très bleus au prix suggéré. Achetés par d'autres 36 à 40 min plus tard pour 4 d'entre elles, 1 min, 3 h et 24 h pour trois autres ; une annonce n'est pas encore partie.

## Carnet et maintenance

Un bandeau signale un flux coupé ou un carnet en retard.

Les constantes du modèle (`FS`, `TP`) doivent rester identiques à `FastSellModel`, à la page des prix et à `fast-price.js`.

Tests du calcul : `node annonces-test.js`.
