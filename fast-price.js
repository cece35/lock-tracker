// Prix "Très rapide" v2 — portage JavaScript EXACT de SkinGap.FastSellModel (SkinGap.java).
// Pour l'extension Skinport Sniper : le plus simple est de lire le champ "xf" de skins.json
// (déjà calculé toutes les 5 min). Ce fichier sert si l'extension doit recalculer elle-même à
// partir des données brutes de l'API Skinport (mêmes entrées, même résultat au centime près).
//
// Entrées :
//   sales : objet de /v1/sales/history pour l'item (last_24_hours, last_7_days, last_30_days,
//           last_90_days, chacun avec volume / min / median / max)
//   L     : prix min des annonces immédiatement tradables (/v1/items?tradable=true, min_price —
//           le paramètre tradable=true est obligatoire : sans lui l'API renvoie toutes les annonces)
//   G     : prix min de toutes les annonces, trade-lock inclus (/v1/items?tradable=false, min_price)
//   marketPage : market_page de l'item (sert à choisir la famille de paramètres : stickers et
//           caisses à part) ; facultatif, par défaut paramètres "skins"
// Sortie : { price, lambda, prob48h, mode, salesMedian }  (price = prix brut conseillé, 0 si
//          incalculable ; salesMedian = prix typique des dernières ventes, base du calcul)
//
// Quel prix utiliser ? Il ne faut jamais que l'annonce qu'on achète serve de plafond à sa propre
// revente :
//   - offre trade-protect achetée à son déblocage (moins chère que L) ou item déjà en inventaire :
//     fastSellPrice(sales, L, G, marketPage)  -> plafond 1 centime sous L ("xf" de skins.json) ;
//   - achat de l'annonce tradable la moins chère (prix L) :
//     fastSellPriceAfterBuyingFront(sales, L, G, tradable) -> plafond sous la 2e annonce tradable
//     ("xb" de skins.json), avec tradable = l'objet /v1/items?tradable=true de l'item ; renvoie null
//     quand la 2e annonce est inconnue (plus de 3 annonces) : utiliser alors fastSellPrice.

const FAST = {
  HORIZON_DAYS: 2.0,
  TARGET_PROB: 0.80,
  TICK: 0.01,
  AGES: [0.5, 4.0, 18.5, 60.0],
  DURS: [1.0, 6.0, 23.0, 60.0],
  TAUS: [2, 4, 8, 16, 32, 64, Infinity],
  N_MIN: 4.0,
  SIGMA0: 0.06,
  K_PRIOR: 2.0,
  // Acceptation recalibrée le 18/11/2026 sur une journée entière (voir SkinGap.java / README).
  SKINS: { aMax: 0.40, rLo: 0.94, b: 3.25 },   // skins, couteaux, gants, charms, agents...
  STACK: { aMax: 0.21, rLo: 0.85, b: 1.5 },    // stickers et caisses
  LAMBDA_EXP: 0.80,
  NO_SALES_DISCOUNT: 0.20,
  Q_CEIL: 0.90,   // plus aucune annonce tradable après l'achat : borne au 90e centile des prix payés
};

function fastErfc(x) {
  const z = Math.abs(x);
  const t = 1.0 / (1.0 + 0.5 * z);
  const r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
    + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
    + t * (-0.82215223 + t * 0.17087277)))))))));
  return x >= 0 ? r : 2.0 - r;
}

function fastPhi(x) { return 0.5 * fastErfc(-x / Math.sqrt(2.0)); }

function fastPhiInv(p) {
  const a = [-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00];
  const b = [-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01];
  const c = [-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00];
  const d = [7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00];
  const pl = 0.02425;
  if (p < pl) {
    const q = Math.sqrt(-2 * Math.log(p));
    return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
  }
  if (p > 1 - pl) {
    const q = Math.sqrt(-2 * Math.log(1 - p));
    return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
  }
  const q = p - 0.5, r = q * q;
  return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
}

function fastWindowDist(n, lo, med, hi) {
  if (!(n > 0) || !(med > 0)) return null;
  const xm = Math.log(med);
  let sl, sh;
  if (n >= 2 && lo > 0 && hi > 0) {
    const z = fastPhiInv((n - 0.375) / (n + 0.25));
    sl = (xm - Math.log(Math.min(lo, med))) / z;
    sh = (Math.log(Math.max(hi, med)) - xm) / z;
    sl = (n * sl + FAST.K_PRIOR * FAST.SIGMA0) / (n + FAST.K_PRIOR);
    sh = (n * sh + FAST.K_PRIOR * FAST.SIGMA0) / (n + FAST.K_PRIOR);
  } else {
    sl = sh = FAST.SIGMA0;
  }
  sl = Math.min(Math.max(sl, 0.005), 0.6);
  sh = Math.min(Math.max(sh, 0.005), 0.6);
  return [0, xm, sl, sh];
}

function fastCdf(x, comps) {
  let s = 0;
  for (const c of comps) s += c[0] * fastPhi((x - c[1]) / (x < c[1] ? c[2] : c[3]));
  return s;
}

function fastQuantile(p, comps) {
  let lo = -20, hi = 20;
  for (let i = 0; i < 80; i++) {
    const mid = (lo + hi) / 2;
    if (fastCdf(mid, comps) < p) lo = mid; else hi = mid;
  }
  return (lo + hi) / 2;
}

function fastFloorCents(p) { return Math.max(FAST.TICK, Math.floor(p * 100 + 1e-9) / 100); }

function fastParamsFor(marketPage) {
  const m = /[?&]cat=([^&]+)|\/market\/([^/?&]+)/.exec(marketPage || "");
  const cat = m ? (m[1] !== undefined ? m[1] : m[2]).toLowerCase() : "";
  return (cat === "sticker" || cat === "container") ? FAST.STACK : FAST.SKINS;
}

function fastAccept(r, pr) { return r <= pr.rLo ? pr.aMax : pr.aMax * Math.pow(r / pr.rLo, -pr.b); }

function fastSellPrice(sales, L, G, marketPage) {
  if (!(L > 0)) return { price: 0, lambda: 0, prob48h: 0, mode: "none", salesMedian: 0 };
  return fastPriceCore(sales, L - FAST.TICK, G > 0 ? Math.min(G, L) : L, false, fastParamsFor(marketPage));
}

// 2e prix tradable quand l'API le donne exactement : 2 annonces -> max_price, 3 -> median_price.
function fastSecondTradable(tradable) {
  if (!tradable) return null;
  const q = Math.round(tradable.quantity || 0);
  const l2 = q === 2 ? tradable.max_price : q === 3 ? tradable.median_price : NaN;
  return (l2 > 0 && l2 >= (tradable.min_price || 0)) ? l2 : null;
}

function fastSellPriceAfterBuyingFront(sales, L, G, tradable, marketPage) {
  if (!(L > 0) || !tradable) return null;
  const pr = fastParamsFor(marketPage !== undefined ? marketPage : tradable.market_page);
  const front = G > 0 ? Math.min(G, L) : L;
  if (Math.round(tradable.quantity || 0) === 1) return fastPriceCore(sales, Infinity, front, true, pr);
  const l2 = fastSecondTradable(tradable);
  return l2 === null ? null : fastPriceCore(sales, l2 - FAST.TICK, front, false, pr);
}

function fastPriceCore(sales, cap, front, ceilQ, pr) {
  const keys = ["last_24_hours", "last_7_days", "last_30_days", "last_90_days"];
  const n = [], lo = [], med = [], hi = [];
  for (const k of keys) {
    const w = (sales && sales[k]) || {};
    n.push(w.volume || 0); lo.push(w.min || 0); med.push(w.median || 0); hi.push(w.max || 0);
  }
  const c = [n[0], Math.max(0, n[1] - n[0]), Math.max(0, n[2] - n[1]), Math.max(0, n[3] - n[2])];
  let w = [0, 0, 0, 0], neff = 0;
  for (const tau of FAST.TAUS) {
    neff = 0;
    for (let k = 0; k < 4; k++) {
      w[k] = tau === Infinity ? 1.0 : Math.exp(-FAST.AGES[k] / tau);
      neff += w[k] * c[k];
    }
    if (neff >= FAST.N_MIN || tau === Infinity) break;
  }
  let den = 0;
  for (let k = 0; k < 4; k++) den += w[k] * FAST.DURS[k];
  const lambda = den > 0 ? neff / den : 0;
  const beta = [n[0] * (w[0] - w[1]), n[1] * (w[1] - w[2]), n[2] * (w[2] - w[3]), n[3] * w[3]];
  const comps = [];
  let tot = 0;
  for (let k = 0; k < 4; k++) {
    if (beta[k] <= 0) continue;
    const wd = fastWindowDist(n[k], lo[k], med[k], hi[k]);
    if (!wd) continue;
    wd[0] = beta[k];
    tot += beta[k];
    comps.push(wd);
  }
  if (!comps.length || tot <= 0) {
    return { price: fastFloorCents(Math.min(cap, front * (1 - FAST.NO_SALES_DISCOUNT))), lambda: 0, prob48h: 0, mode: "no-sales", salesMedian: 0 };
  }
  for (const cp of comps) cp[0] /= tot;
  const medPrice = Math.exp(fastQuantile(0.5, comps)); // prix typique récent
  const xStar = -Math.log(1 - FAST.TARGET_PROB);
  const lamEff = Math.pow(lambda, FAST.LAMBDA_EXP);
  const xMax = FAST.HORIZON_DAYS * lamEff * pr.aMax;
  let r, mode;
  if (pr.b <= 0) { r = Infinity; mode = "front"; }
  else if (xMax >= xStar) { r = pr.rLo * Math.pow(xMax / xStar, 1 / pr.b); mode = "target"; }
  else { r = pr.rLo; mode = "best-effort"; }
  let p = medPrice * r;
  if (ceilQ) {
    const q = Math.exp(fastQuantile(FAST.Q_CEIL, comps));
    if (p > q) { p = q; mode += "+q90"; }
  }
  if (p >= cap) { p = cap; mode += "+cap"; }
  p = fastFloorCents(p);
  const prob = 1 - Math.exp(-FAST.HORIZON_DAYS * lamEff * fastAccept(p / medPrice, pr));
  return { price: p, lambda, prob48h: prob, mode, salesMedian: medPrice };
}

if (typeof module !== "undefined") {
  module.exports = { fastSellPrice, fastSellPriceAfterBuyingFront, fastSecondTradable, fastParamsFor, FAST };
}
