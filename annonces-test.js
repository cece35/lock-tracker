// Tests du calcul de la page « annonces » (annonces.html) : node annonces-test.js
// Le script de la page est extrait du HTML et exécuté sans DOM (seul le cœur du calcul tourne).
const fs = require("fs");
const vm = require("vm");
const assert = require("assert");

const html = fs.readFileSync(__dirname + "/annonces.html", "utf8");
const code = html.slice(html.lastIndexOf("<script>") + 8, html.lastIndexOf("</script>"));
const sandbox = { module: { exports: {} }, URLSearchParams, Float64Array, Math, Date, Object, Number, isFinite };
vm.runInNewContext(code, sandbox);
const A = sandbox.module.exports;

let n = 0;
function test(name, fn) { fn(); n++; console.log("OK   " + name); }
const near = (a, b, eps, msg) => assert.ok(Math.abs(a - b) <= (eps ?? 1e-6), (msg || "") + " : " + a + " ≠ " + b);
const flat = A.buildClock(new Array(24).fill(1), Date.UTC(2026, 10, 20, 12));
const ctx = (o) => Object.assign({ minEur: 0.5, minPct: 5, horizonH: 48, budget: 0, hc: 0, clock: flat }, o);
const item = (o) => Object.assign({ n: "AK-47 | Test (Field-Tested)", p: "https://skinport.com/market?item=x&cat=Rifle",
  xl: 40, xs: 8.5, ask: 3, min: 3, xf: 2.99 }, o);
const L = (...prices) => prices.map(p => [Math.round(p * 100), 60, 0.25]);

// Exemple de référence : 5 annonces à 3 €, puis 3,50 € et 4,30 €, puis rien jusqu'à 9 € ; ventes vers 8,5 €.
const example = () => ({ q_t: 8, known_t: 8, tradable: L(3, 3, 3, 3, 3, 3.5, 4.3, 9), api_floor_t: null,
  sales_7d: 0, sales_t_7d: 10, sale_p10_p50_p90_7d: [820, 850, 920], arr_t_7d: 0, arr_t_q: null });

test("exemple : acheter les 7 (jusqu'à 4,30 €) et revendre à 8,99 €", () => {
  const lot = A.bestLot(item(), example(), ctx());
  assert.ok(lot, "aucun lot");
  assert.strictEqual(lot.k, 7);
  near(lot.R, 8.99, 1e-9, "revente");
  assert.strictEqual(lot.wallKind, "connue");
  assert.strictEqual(lot.capped, false);
  near(lot.cost, 22.8, 1e-9, "coût");
  near(lot.gain, 7 * 8.99 * 0.92 - 22.8, 1e-9, "gain");
  // rythme : 40^0,8 × 0,40 × ((8,99/8,5)/0,94)^-3,25 ventes/jour ; 7 ventes ≈ 32 h
  const rho = Math.pow(40, 0.8) * 0.40 * Math.pow((8.99 / 8.5) / 0.94, -3.25);
  near(lot.rho, rho, 1e-9, "rythme");
  near(lot.Tk, 7 * 24 / rho, 1e-6, "délai du dernier");
  near(lot.T1, 24 / rho, 1e-6, "délai du premier");
  assert.strictEqual(lot.units.length, 7);
  near(lot.units[6].gain, 8.99 * 0.92 - 4.3, 1e-9, "gain de la 7e");
});

test("exemple sous 24 h : même lot, revente baissée pour que les 7 partent à temps", () => {
  const lot = A.bestLot(item(), example(), ctx({ horizonH: 24 }));
  assert.ok(lot && lot.k === 7 && lot.lowered, "lot de 7, revente baissée");
  assert.ok(lot.R < 8.99 && lot.Tk <= 24 + 1e-6, "sous 8,99 € et dans les 24 h");
  const rho = A.rhoOf(lot.R + 0.01, 40, 8.5, A.famOf(item()));
  assert.ok(7 * 24 / rho > 24, "un centime de plus et le lot ne part plus à temps");
});

test("exemple : pas de lot pour un skin peu liquide (7 ventes prendraient > 48 h)", () => {
  assert.strictEqual(A.bestLot(item({ xl: 3 }), example(), ctx()), null);
});

test("budget : 20 € ne suffit pas pour les 7, 25 € oui", () => {
  assert.strictEqual(A.bestLot(item(), example(), ctx({ budget: 20 })), null);
  assert.strictEqual(A.bestLot(item(), example(), ctx({ budget: 25 })).k, 7);
});

test("plafond : un mur très au-dessus des ventes ne sert pas de prix de revente", () => {
  const b = example(); b.tradable = L(3, 3, 3, 3, 3, 3.5, 4.3, 30); // mur isolé à 30 €
  const lot = A.bestLot(item(), b, ctx());
  assert.strictEqual(lot.k, 7);
  near(lot.R, 9.2, 1e-9, "revente plafonnée au 90e centile des ventes");
  assert.strictEqual(lot.capped, true);
});

test("annonce inconnue au min de l'API incluse dans le lot", () => {
  const b = { q_t: 8, known_t: 3, tradable: L(3.5, 4.3, 9), api_floor_t: 300, sales_7d: 0, sales_t_7d: 10,
    sale_p10_p50_p90_7d: [820, 850, 920], arr_t_7d: 0, arr_t_q: null };
  const lot = A.bestLot(item(), b, ctx());
  assert.strictEqual(lot.k, 3);
  assert.strictEqual(lot.unknownIncluded, true);
  assert.strictEqual(lot.units[0].unknown, true);
  near(lot.R, 8.99, 1e-9);
  near(lot.coverage, 4 / 8, 1e-9, "couverture");
  assert.strictEqual(lot.unknownCount, 4);
});

test("toutes les annonces connues achetées, couverture incomplète : revente au plafond, mur inconnu", () => {
  const b = { q_t: 10, known_t: 2, tradable: L(3, 3.1), api_floor_t: null, sales_7d: 0, sales_t_7d: 6,
    sale_p10_p50_p90_7d: [350, 380, 400], arr_t_7d: 0, arr_t_q: null };
  const lot = A.bestLot(item({ xs: 3.9 }), b, ctx());
  assert.strictEqual(lot.k, 2);
  near(lot.R, 4.0, 1e-9);
  assert.strictEqual(lot.wallKind, "inconnue");
});

test("annonces inconnues estimées d'après l'API : elles passent devant (cas AWP | Atheris MW du 20/11)", () => {
  // 218 annonces tradables, min 6,03 € (pas vue), médiane des annonces 8,14 € ; on n'en connaît qu'une autre (7,37 €)
  const b = { q_t: 218, known_t: 1, min_t: 603, tradable: L(7.37), api_floor_t: 603, sales_7d: 1, sales_t_7d: 1,
    sale_p10_p50_p90_7d: [665, 665, 665], arr_t_7d: 0, arr_t_q: null };
  const it = item({ xs: 6.65, xl: 5.686, tmed: 8.14 });
  // ~109 × ln(R/6,03)/ln(8,14/6,03) annonces inconnues devant : des dizaines de jours de ventes
  near(A.unknownBelow(7.36, b, it, [{ p: 6.03 }, { p: 7.37 }]), 218 * 0.5 * Math.log(7.36 / 6.03) / Math.log(8.14 / 6.03) - 1, 1e-9);
  assert.strictEqual(A.bestLot(it, b, ctx({ hc: 30 })), null, "prudent : pas de lot");
  const opt = A.bestLot(it, b, ctx({ hc: 30, unknown: "ignore" }));
  assert.ok(opt && opt.k === 1 && opt.U === 0, "annonces inconnues ignorées : lot optimiste");
});

test("revente baissée pour que le lot parte dans le délai", () => {
  // mur lointain (20 €), plafond des ventes 12 € : à 12 € le lot de 3 est trop lent, on baisse jusqu'à tenir 48 h
  const b = { q_t: 4, known_t: 4, tradable: L(5, 5, 5, 20), api_floor_t: null, sales_7d: 0, sales_t_7d: 8,
    sale_p10_p50_p90_7d: [700, 800, 1200], arr_t_7d: 0, arr_t_q: null };
  const it = item({ xs: 8, xl: 6 });
  const lot = A.bestLot(it, b, ctx());
  assert.ok(lot && lot.k === 3 && lot.lowered, "lot de 3, revente baissée");
  assert.ok(lot.R < 12 && lot.Tk <= 48 + 1e-6, "sous le plafond et dans le délai");
  const slower = A.bestLot(it, b, ctx({ horizonH: 48 }));
  near(slower.R, lot.R, 1e-9);
  // un centime de plus et le lot ne part plus dans le délai
  const pr = A.famOf(it), lam = A.lambdaOf(it, b, 0);
  const rho = A.rhoOf(lot.R + 0.01, lam, 8, pr);
  assert.ok(A.hoursFor(3, rho, A.buildClock(new Array(24).fill(1), Date.UTC(2026, 10, 20, 12))) > 48, "c'est le plus haut prix possible");
  assert.ok(A.net(lot.R) - 5 >= 0.5 && (A.net(lot.R) - 5) / 5 >= 0.05, "marge respectée");
});

test("prix de revente minimal pour la marge", () => {
  near(A.minResale(3, { minEur: 0.5, minPct: 5 }), 3.81, 1e-9);     // (3 + 0,5) / 0,92 = 3,804… -> 3,81
  near(A.minResale(20, { minEur: 0.5, minPct: 5 }), 22.83, 1e-9);   // 21 / 0,92 = 22,826… -> 22,83
});

test("carnet tronqué (plus de 40 annonces connues) : jamais de lot sans mur connu", () => {
  const b = { q_t: 60, known_t: 50, tradable: L(3, 3.1, 3.2), api_floor_t: null, sales_7d: 0, sales_t_7d: 6,
    sale_p10_p50_p90_7d: [350, 380, 400], arr_t_7d: 0, arr_t_q: null };
  assert.strictEqual(A.bestLot(item({ xs: 3.9 }), b, ctx()), null);
});

test("marge minimale : chaque annonce achetée doit rapporter au moins la marge", () => {
  // avec 4 € de marge minimum, la 7e (4,30 € -> 8,27 € net = +3,97 €) ne passe plus : aucun k ne convient
  assert.strictEqual(A.bestLot(item(), example(), ctx({ minEur: 4 })), null);
  assert.strictEqual(A.bestLot(item(), example(), ctx({ minEur: 3.9 })).k, 7);
});

test("nouvelles annonces moins chères attendues : elles passent devant", () => {
  const b = example(); b.sales_7d = 40; b.arr_t_7d = 100; b.arr_t_q = [100, 150, 200, 250, 300, 350, 400, 450, 500, 600, 800];
  assert.strictEqual(A.bestLot(item(), b, ctx({ hc: 24 })), null, "100 annonces/jour sous 8,99 € : jamais vendu");
  b.arr_t_q = b.arr_t_q.map(x => x + 1000);          // toutes au-dessus de la revente : aucun effet
  assert.strictEqual(A.bestLot(item(), b, ctx({ hc: 24 })).k, 7);
});

test("λ : API et flux combinés selon les heures de flux", () => {
  near(A.lambdaOf({ xl: 40 }, { sales_7d: 0 }, 0), 40);
  near(A.lambdaOf({ xl: 0 }, { sales_7d: 30 }, 72), 30 / 6);          // 30 ventes en 72 h, a priori 0
  near(A.lambdaOf({ xl: 10 }, { sales_7d: 20 }, 24), (30 + 20) / 4);  // 72 h d'a priori à 10/j + 20 ventes en 24 h
});

test("fracBelow, horloge, Poisson", () => {
  const q = [12, 12, 12, 12, 12, 12, 19, 19, 19, 19, 19];
  near(A.fracBelow(q, 12), 0); near(A.fracBelow(q, 20), 1); near(A.fracBelow(q, 15), (5 + 3 / 7) / 10);
  near(A.hoursFor(3, 24, flat), 3, 1e-9);
  near(A.salesWithin(48, 24, flat), 48, 1e-9);
  assert.strictEqual(A.hoursFor(1, 0, flat), Infinity);
  near(A.probAtLeast(1, 1), 1 - Math.exp(-1));
  near(A.probAtLeast(2, 2), 1 - 3 * Math.exp(-2));
  // activité double de 18 h à 22 h UTC : à 18 h, il faut moins d'heures réelles qu'en moyenne
  const prof = new Array(24).fill(1); for (let h = 18; h < 22; h++) prof[h] = 2;
  const evening = A.buildClock(prof, Date.UTC(2026, 10, 20, 18));
  assert.ok(A.hoursFor(1, 24, evening) < 1, "le soir, une vente prend moins d'1 h");
});

test("trade-protect : marge z·σ·√(d+2), offres au-delà du seuil de jours exclues", () => {
  const it = item({ xf: 10, h24: 10, h7: 10, h30: 10, h90: 10, xp: 0.8, ask: 10.5, min: 8.5, xs: 10, xl: 5 });
  const b = { tp: [[850, 5, 0.2], [880, 1, 0.3], [800, 9, 0.1]] };
  const t = A.tpOffers(it, b, { z: 1, maxDays: 8, minEur: 0.3, minPct: 4, sell: "after", ageDays: 0 });
  assert.strictEqual(t.offers.length, 2, "l'offre à 9 jours est exclue");
  near(t.sigma.s, 0.015, 1e-12, "σ borné à 1,5 %/j");
  const b0 = t.best;
  near(b0.p, 8.5); near(b0.gapExp, 9.2 - 8.5, 1e-9);
  near(b0.marginPct, 1 - Math.exp(-0.015 * Math.sqrt(7)), 1e-12);
  near(b0.gap, 0.7 - 9.2 * (1 - Math.exp(-0.015 * Math.sqrt(7))), 1e-9);
  assert.strictEqual(A.tpOffers(it, b, { z: 1, maxDays: 8, minEur: 0.5, minPct: 5, sell: "after", ageDays: 0 }), null,
    "écart affiché sous la marge minimale");
  const z0 = A.tpOffers(it, b, { z: 0, maxDays: 8, minEur: 0.3, minPct: 4, sell: "after", ageDays: 0 });
  near(z0.best.margin, 0, 1e-12, "z = 0 : pas de marge");
  assert.strictEqual(A.tpOffers(it, b, { z: 1, maxDays: 8, minProb: 0.9, minEur: 0.3, minPct: 4, sell: "after", ageDays: 0 }), null,
    "proba de revente sous 48 h (80 %) sous le seuil de 90 % : exclu");
  assert.ok(A.tpOffers(it, b, { z: 1, maxDays: 8, minProb: 0.5, minEur: 0.3, minPct: 4, sell: "after", ageDays: 0 }), "80 % >= 50 %");
});

test("volatilité provisoire tirée des prix typiques 24 h / 7 j / 30 j / 90 j", () => {
  const s = A.sigmaOf({ h24: 10, h7: 10.5, h30: 11, h90: 12 }).s;
  const r = (a, b, dt) => Math.pow(Math.log(a / b), 2) / dt;
  near(s, Math.sqrt((r(10, 10.5, 3) + r(10.5, 11, 11.5) + r(11, 12, 30)) / 3), 1e-12);
  near(A.sigmaOf({ h24: 10 }).s, 0.03, 1e-12, "par défaut");
});

test("bandeau : flux coupé, silencieux ou carnet en retard", () => {
  const now = Date.UTC(2026, 10, 20, 20);
  assert.strictEqual(A.feedProblem({ feed_down_since: null, feed_last_event: now - 60e3, generated: now - 60e3 }, now), null);
  assert.strictEqual(A.feedProblem({ feed_down_since: now - 5 * 60e3, feed_last_event: now - 5 * 60e3, generated: now }, now).what, "Flux interrompu");
  assert.strictEqual(A.feedProblem({ feed_down_since: null, feed_last_event: now - 20 * 60e3, generated: now }, now).what, "Aucun événement du flux");
  assert.strictEqual(A.feedProblem({ feed_down_since: null, feed_last_event: now, generated: now - 11 * 60e3 }, now).what, "Carnet pas mis à jour");
});

console.log(n + " tests OK");
