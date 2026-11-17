// Prix "Très rapide" v2 — portage JavaScript EXACT de SkinGap.FastSellModel (SkinGap.java).
// Pour l'extension Skinport Sniper : le plus simple est de lire le champ "xf" de skins.json
// (déjà calculé toutes les 5 min). Ce fichier sert si l'extension doit recalculer elle-même à
// partir des données brutes de l'API Skinport (mêmes entrées, même résultat au centime près).
//
// Entrées :
//   sales : objet de /v1/sales/history pour l'item (last_24_hours, last_7_days, last_30_days,
//           last_90_days, chacun avec volume / min / median / max)
//   L     : prix min des annonces immédiatement tradables (/v1/items?tradable=true, min_price)
//   G     : prix min de toutes les annonces, trade-lock inclus (/v1/items, min_price)
// Sortie : { price, lambda, prob48h, mode }  (price = prix brut conseillé, 0 si incalculable)

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
  A_MAX: 0.80,
  R_LO: 0.89,
  B: 3.0,
  LAMBDA_EXP: 0.80,
  NO_SALES_DISCOUNT: 0.20,
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

function fastAccept(r) { return r <= FAST.R_LO ? FAST.A_MAX : FAST.A_MAX * Math.pow(r / FAST.R_LO, -FAST.B); }

function fastSellPrice(sales, L, G) {
  if (!(L > 0)) return { price: 0, lambda: 0, prob48h: 0, mode: "none" };
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
  const cap = L - FAST.TICK;
  if (!comps.length || tot <= 0) {
    const front = G > 0 ? Math.min(G, L) : L;
    return { price: fastFloorCents(Math.min(cap, front * (1 - FAST.NO_SALES_DISCOUNT))), lambda: 0, prob48h: 0, mode: "no-sales" };
  }
  for (const cp of comps) cp[0] /= tot;
  const medPrice = Math.exp(fastQuantile(0.5, comps)); // prix typique récent
  const xStar = -Math.log(1 - FAST.TARGET_PROB);
  const lamEff = Math.pow(lambda, FAST.LAMBDA_EXP);
  const xMax = FAST.HORIZON_DAYS * lamEff * FAST.A_MAX;
  let r, mode;
  if (FAST.B <= 0) { r = Infinity; mode = "front"; }
  else if (xMax >= xStar) { r = FAST.R_LO * Math.pow(xMax / xStar, 1 / FAST.B); mode = "target"; }
  else { r = FAST.R_LO; mode = "best-effort"; }
  let p = medPrice * r;
  if (p >= cap) { p = cap; mode += "+cap"; }
  p = fastFloorCents(p);
  const prob = 1 - Math.exp(-FAST.HORIZON_DAYS * lamEff * fastAccept(p / medPrice));
  return { price: p, lambda, prob48h: prob, mode };
}

if (typeof module !== "undefined") module.exports = { fastSellPrice, FAST };
