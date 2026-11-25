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
const L3 = L;

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

test("budget : 14 € ne suffit pour aucun niveau entier, 20 € pour les 6 premières, 25 € pour les 7", () => {
  assert.strictEqual(A.bestLot(item(), example(), ctx({ budget: 14 })), null);
  const six = A.bestLot(item(), example(), ctx({ budget: 20 }));
  assert.strictEqual(six.k, 6);
  near(six.R, 4.29, 1e-9, "1 ct sous l'annonce à 4,30 €");
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

test("marge minimale : gain du lot en €, marge de chaque annonce en %", () => {
  // lot des 7 : 7 × 8,27 € net − 22,80 € = +35,09 € ; la 7e (4,30 € -> 8,27 € net) rapporte +92 %
  assert.strictEqual(A.bestLot(item(), example(), ctx({ minEur: 36 })), null);
  assert.strictEqual(A.bestLot(item(), example(), ctx({ minEur: 35 })).k, 7);
  assert.strictEqual(A.bestLot(item(), example(), ctx({ minPct: 100 })), null);
  assert.strictEqual(A.bestLot(item(), example(), ctx({ minPct: 90 })).k, 7);
});

test("nouvelles annonces moins chères attendues : elles passent devant", () => {
  const b = example(); b.sales_7d = 40; b.arr_t_7d = 100; b.arr_t_q = [100, 150, 200, 250, 300, 350, 400, 450, 500, 600, 800];
  assert.strictEqual(A.bestLot(item(), b, ctx({ hc: 24 })), null, "100 annonces/jour sous 8,99 € : jamais vendu");
  b.arr_t_q = b.arr_t_q.map(x => x + 1000);          // toutes au-dessus de la revente : aucun effet
  assert.strictEqual(A.bestLot(item(), b, ctx({ hc: 24 })).k, 7);
});

// Lot réel du 23/11 16:00 : 65 Recoil Case tradables à 0,13 € d'un coup, plancher du marché à 0,20 € juste avant.
const recoil = (o) => Object.assign({ n: "Recoil Case", p: "https://skinport.com/market?item=Recoil-Case&cat=Container",
  xl: 69, xs: 0.16, tmed: 0.65, ask: 0.13, min: 0.13, xf: 0.16 }, o);
const recoilBook = (o) => Object.assign({ q_t: 47277, known_t: 86, min_t: 13, levels_t: [[13, 65, 10], [20, 21, 3000]],
  tradable: new Array(40).fill(0).map(() => [13, 10, null]), api_floor_t: null, ufloor_t: 20, ufloor_age_min: 10,
  sales_7d: 114, sales_t_7d: 114, sales_t_lv: [[13, 65], [19, 19], [20, 30]], sale_p10_p50_p90_7d: [13, 13, 20],
  arr_t_7d: 0, arr_t_q: null }, o);

test("conteneurs : vitesse de revente = moitié des ventes tradables vues à ce prix ou plus, par jour", () => {
  const b = recoilBook();
  near(A.fungibleRate(b, 0.19, 96), 0.5 * 49 / 4, 1e-9);       // 19 + 30 ventes ≥ 0,19 € en 4 jours
  near(A.fungibleRate(b, 0.14, 96), 0.5 * 49 / 4, 1e-9);
  near(A.fungibleRate(b, 0.13, 96), 0.5 * 114 / 4, 1e-9);
  near(A.fungibleRate(b, 0.21, 96), 0, 1e-9);
  assert.strictEqual(A.fungibleRate(b, 0.19, 12), null, "moins de 24 h de flux : modèle habituel");
});

test("niveaux de prix : un lot de 65 au même prix n'est plus caché par la limite des 40 annonces", () => {
  const L = A.levelsOf(recoilBook());
  assert.deepStrictEqual(L.map(l => [l.p, l.n]), [[0.13, 65], [0.2, 21]]);
  assert.deepStrictEqual(JSON.parse(JSON.stringify(A.levelsOf({ tradable: L3(3, 3, 3.5), api_floor_t: 250 }).map(l => [l.p, l.n, l.unknown]))),
    [[2.5, 1, true], [3, 2, false], [3.5, 1, false]], "ancien format : annonces regroupées par prix");
  // 65 caisses × (0,19 € × 0,92 − 0,13 €) = +2,91 € ; moitié de 49 ventes ≥ 0,19 € en 4 jours : ≈ 10,6 jours
  assert.strictEqual(A.bestLot(recoil(), recoilBook(), ctx({ hc: 96 })), null, "pas revendu sous 48 h");
  assert.strictEqual(A.bestLot(recoil(), recoilBook(), ctx({ hc: 96, horizonH: 168 })), null, "pas sous 7 jours non plus");
  const b = recoilBook({ sales_t_7d: 214, sales_t_lv: [[13, 65], [19, 119], [20, 30]] });   // 149 ventes ≥ 0,19 € en 4 j
  const lot = A.bestLot(recoil(), b, ctx({ hc: 96, horizonH: 168 }));
  assert.ok(lot, "lot sous 7 jours");
  assert.strictEqual(lot.k, 65);
  near(lot.R, 0.19, 1e-9, "1 ct sous les annonces connues à 0,20 €");
  near(lot.gain, 65 * 0.19 * 0.92 - 65 * 0.13, 1e-9, "gain");
  assert.ok(lot.emp, "vitesse mesurée dans le flux");
  near(lot.Tk, 65 * 24 / (0.5 * 149 / 4), 1e-6, "délai du dernier exemplaire");
  assert.strictEqual(lot.units.length, 65);
});

test("plancher des annonces inconnues : mur au-dessus d'un lot quand rien n'est connu plus haut", () => {
  const b = recoilBook({ known_t: 65, levels_t: [[13, 65, 10]], sales_t_7d: 214, sales_t_lv: [[13, 65], [19, 119], [20, 30]] });
  const lot = A.bestLot(recoil(), b, ctx({ hc: 96, horizonH: 168 }));
  assert.strictEqual(lot.wallKind, "inconnues");
  near(lot.R, 0.19, 1e-9);
  assert.strictEqual(lot.U, 0, "aucune annonce inconnue sous le plancher");
  // plancher relevé il y a plus de 12 h : ignoré, l'estimation prudente place des milliers d'inconnues devant
  assert.strictEqual(A.bestLot(recoil(), Object.assign({}, b, { ufloor_age_min: 800 }),
    ctx({ hc: 96, horizonH: 168 })), null);
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

test("items à pattern : charms, Case Hardened, Heat Treated, Fade (pas Marble Fade, Doppler…)", () => {
  const fam = n => A.patternFamily(n);
  assert.strictEqual(fam("Charm | Baby Karat T"), "charm");
  assert.strictEqual(fam("Souvenir Charm | Austin 2025 Highlight | Some Play"), "charm");
  assert.strictEqual(fam("Desert Eagle | Heat Treated (Field-Tested)"), "ht");
  assert.strictEqual(fam("AK-47 | Case Hardened (Minimal Wear) [Tier 2]"), "ch", "version Skinport dans la clé");
  assert.strictEqual(fam("★ Hydra Gloves | Case Hardened (Field-Tested)"), "ch");
  assert.strictEqual(fam("★ StatTrak™ Karambit | Fade (Factory New)"), "fade");
  for (const n of ["★ Karambit | Marble Fade (Factory New)", "Glock-18 | Amber Fade (Factory New)", "★ Karambit | Doppler (Factory New) [Phase 2]",
    "AK-47 | Redline (Field-Tested)", "Sticker Slab | Lucky 13 (Foil)", "Recoil Case", "★ Karambit"])
    assert.strictEqual(fam(n), null, n);
  assert.ok(A.isPatternItem("Charm | Semi-Precious") && !A.isPatternItem("AK-47 | Slate (Field-Tested)"));
});

test("patterns : affichage du # et de ce qu'est devenue l'annonce", () => {
  assert.strictEqual(A.fmtPatX("charm", 72352), "#72 352");
  assert.strictEqual(A.fmtPatX("charm", 512), "#512");
  assert.strictEqual(A.fmtPatX("ht", 62.7), "62.7 % bleu");
  assert.strictEqual(A.fmtPatX("fade", 98.25, true), "98.3 %");
  assert.strictEqual(A.fmtPatX("ht", 57), "57 % bleu");
  const t = Date.UTC(2026, 10, 21, 16, 1), c = (o) => Object.assign({ t, p: 0.8, st: "listed", st_t: null, st_p: null }, o);
  assert.deepStrictEqual([A.patStatusOf(c({ st: "sold", st_t: t + 37 * 60e3, st_p: 0.8 }), t).txt, A.patStatusOf(c({ st: "sold", st_t: t + 37 * 60e3, st_p: 0.8 }), t).sub],
    ["vendue en 37 min", "à 0.80 €"]);
  assert.strictEqual(A.patStatusOf(c({ st: "relisted", st_t: t + 2 * 3600e3, st_p: 5.14 }), t).txt, "remise à 5.14 €");
  assert.strictEqual(A.patStatusOf(c({ st: "gone", st_t: t + 30 * 3600e3 }), t).sub, "plus dans l'API après 30 h");
  const l = A.patStatusOf(c({}), t + 3 * 86400e3);
  assert.strictEqual(l.key, "listed");
  assert.strictEqual(l.sub, "vue il y a 3 j 0 h");
});

test("heure de mise en vente : aujourd'hui, hier, date (heure locale)", () => {
  const at = (d, h, m) => new Date(2026, 10, d, h, m).getTime();
  assert.strictEqual(A.fmtWhen(at(25, 14, 32), at(25, 15, 0)), "14:32");
  assert.strictEqual(A.fmtWhen(at(25, 0, 5), at(25, 23, 59)), "00:05");
  assert.strictEqual(A.fmtWhen(at(24, 23, 10), at(25, 0, 30)), "hier 23:10");
  assert.strictEqual(A.fmtWhen(at(24, 9, 5), at(26, 0, 30)), "24/11 09:05");
});

test("mise en vente : vue dans le flux, prix changé, remise en vente, min API estimé", () => {
  const at = (d, h, m) => new Date(2026, 10, d, h, m).getTime();
  const t = at(25, 14, 32), now = t + 37 * 60e3;
  const v = A.listedInfo({ p: 25.07, t, t1: t, pp: null }, now);
  assert.deepStrictEqual([v.main, v.sub, v.t], ["14:32", "il y a 37 min", t]);
  assert.strictEqual(A.listedInfo({ p: 25.07, t, t1: at(20, 10, 21), pp: 3162 }, now).sub, "prix changé, était 31.62 €");
  assert.strictEqual(A.listedInfo({ p: 31.62, t, t1: t - 3600e3, pp: 3162 }, now).sub, "remise en vente");
  // l'Asiimov du 25/11 : min API descendu de 27,16 à 26,00 € sur la photo de 11:40
  const S = at(25, 11, 40), R = at(25, 9, 10);
  const u = A.listedInfo({ unknown: true, when: [S, S, false] }, now);
  assert.deepStrictEqual([u.main, u.sub, u.t], ["≈ 11:40", "photo API", S]);
  const r = A.listedInfo({ unknown: true, when: [S, R, false] }, now);
  assert.deepStrictEqual([r.main, r.sub, r.t], ["≤ 11:40", "après 09:10", R], "cachée par une moins chère : entre R et S, triée à R");
  assert.strictEqual(A.listedInfo({ unknown: true, when: [S, at(20, 3, 50), true] }, now).sub, "peut-être bien avant");
  assert.strictEqual(A.listedInfo({ unknown: true, when: null }, now).main, "—");
  assert.strictEqual(A.listedInfo({ p: 3, t: null }, now).main, "—", "ancien book.json sans heures");
});

test("lot et trade-protect : heure de chaque annonce, la plus récente du lot", () => {
  const T = new Date(2026, 10, 25, 9, 0).getTime();
  const b = example();
  b.tradable = b.tradable.map((x, i) => x.concat([T + i * 600e3, T + i * 600e3, null]));
  const lot = A.bestLot(item(), b, ctx());
  assert.strictEqual(lot.k, 7);
  assert.deepStrictEqual([...lot.units.map(u => u.t)], [0, 1, 2, 3, 4, 5, 6].map(i => T + i * 600e3));   // copie : tableau du bac à sable
  assert.strictEqual(lot.tNewest, T + 6 * 600e3);
  const li = A.lotListedInfo(lot, T + 3 * 3600e3);
  assert.deepStrictEqual([li.main, li.sub], ["10:00", "la plus ancienne 09:00"]);
  const S = T - 3600e3;
  const bu = { q_t: 8, known_t: 3, tradable: L(3.5, 4.3, 9).map(x => x.concat([T, T, null])), api_floor_t: 300,
    api_floor_t_when: [S, S, false], sales_7d: 0, sales_t_7d: 10, sale_p10_p50_p90_7d: [820, 850, 920], arr_t_7d: 0, arr_t_q: null };
  const lu = A.bestLot(item(), bu, ctx());
  assert.strictEqual(lu.units[0].unknown, true);
  assert.deepStrictEqual(lu.units[0].when, [S, S, false], "annonce inconnue : heure estimée transmise");
  const it = item({ xf: 10, h24: 10, h7: 10, h30: 10, h90: 10, xp: 0.8, ask: 10.5, min: 8.5, xs: 10, xl: 5 });
  const tp = A.tpOffers(it, { tp: [[850, 5, 0.2, T, T - 86400e3, 900]] }, { z: 1, maxDays: 8, minEur: 0.3, minPct: 4, sell: "after", ageDays: 0 });
  assert.deepStrictEqual([tp.best.t, tp.best.t1, tp.best.pp], [T, T - 86400e3, 900]);
  assert.strictEqual(A.listedInfo(tp.best, T + 60e3).sub, "prix changé, était 9.00 €");
});

console.log(n + " tests OK");
