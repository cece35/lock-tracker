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
