import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Base64;

public class SkinGap {

    // ---------------------------------------------------------------
    // Mini parseur JSON (identique à la version console, inchangé)
    // ---------------------------------------------------------------
    static class JsonParser {
        private final String s;
        private int pos = 0;
        JsonParser(String s) { this.s = s; }
        Object parse() { skipWs(); return parseValue(); }
        private void skipWs() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }
        private Object parseValue() {
            skipWs();
            char c = s.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't') { pos += 4; return Boolean.TRUE; }
            if (c == 'f') { pos += 5; return Boolean.FALSE; }
            if (c == 'n') { pos += 4; return null; }
            return parseNumber();
        }
        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; skipWs();
            if (s.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                pos++;
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                pos++;
                break;
            }
            return map;
        }
        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; skipWs();
            if (s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                pos++;
                break;
            }
            return list;
        }
        private String parseString() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (s.charAt(pos) != '"') {
                char c = s.charAt(pos);
                if (c == '\\') {
                    pos++;
                    char esc = s.charAt(pos);
                    switch (esc) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(pos + 1, pos + 5), 16));
                            pos += 4;
                            break;
                        default: sb.append(esc);
                    }
                    pos++;
                } else {
                    sb.append(c);
                    pos++;
                }
            }
            pos++;
            return sb.toString();
        }
        private Object parseNumber() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            return Double.parseDouble(s.substring(start, pos));
        }
    }

    record SalesVolumes(int v24h, int v7j, int v30j, int v90j, double m24h, double m7j, double m30j, double m90j) {}

    // Statistiques agrégées d'un marché Skinport (/v1/items) pour un market_hash_name donné.
    // "T" (tradable=true, immédiatement disponible) ou "G" (tradable=false, superset incluant
    // le trade-lock) selon la liste d'origine — voir prompt_prix_revente_skinport_corrige.md §1.
    record MarketStats(double min, double median, double mean, double quantity, double suggested, double max) {
        static final MarketStats EMPTY = new MarketStats(0, 0, 0, 0, 0, 0);
    }

    // Sortie du modèle de prix de revente (voir prompt_prix_revente_skinport_corrige.md).
    // Tous les prix sont BRUTS (avant la taxe Skinport de 8%/6%, appliquée séparément par
    // withTax() côté JS et skinportNet() côté serveur Java) — §14 du prompt.
    record PriceModelResult(
            double fairValue, double confidence,
            double veryFast, double fast, double normal, double patient,
            double liquidity, double disagreement, double trend
    ) {
        static final PriceModelResult EMPTY = new PriceModelResult(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    record Result(String name, String marketPage, SalesVolumes volumes, double minWithLocked, double askRaw,
                  double medianRaw, String image, MarketStats tradable, MarketStats global, PriceModelResult model,
                  String version, FastPrice fast, FastPrice fastBuy, Double secondTradable) {}

    // Clé d'un item Skinport. Depuis l'ajout du champ "version" à l'API (/v1/items,
    // /v1/sales/history, /v1/sales/out-of-stock), un même market_hash_name peut apparaître
    // plusieurs fois : une entrée par phase Doppler (Phase 1-4, Ruby, Sapphire, Black Pearl,
    // Emerald) et, dans l'historique des ventes, des variantes "Blue Gem" / "Tier 1" / "Tier 2"
    // / "7th Max"... des Case Hardened, Marble Fade, etc. Indexer par le seul nom faisait
    // écraser l'historique normal par celui de la variante rare (ex. AK-47 | Case Hardened (FT) :
    // médiane 7j 163 € pour l'item normal, 386 € pour "Tier 2" -> faux écart énorme).
    // Items sans version : la clé reste le nom exact (rétro-compatible avec skins.json, le
    // dashboard et l'extension). Items avec version : "nom [version]".
    static String itemKey(String name, Object version) {
        if (version == null) return name;
        String v = String.valueOf(version);
        return v.isBlank() || v.equals("default") ? name : name + " [" + v + "]";
    }

    // En-tête Authorization Skinport (Basic clientId:clientSecret), ou null si les secrets ne
    // sont pas configurés. Envoyé aussi sur les endpoints publics (/v1/items, /v1/sales/history) :
    // d'après la doc Skinport (Rate Limits), avec une clé API le quota (8 req / 5 min) est compté
    // PAR CLÉ ; sans clé il est compté par réseau (toutes les IPv4 d'un même /24 partagent le
    // même quota). Les runners GitHub Actions partagent leurs plages d'IP avec d'autres projets
    // qui interrogent aussi Skinport -> des 429 "gratuits" possibles sans la clé.
    static String AUTH_HEADER = null;

    static String fetchUrl(HttpClient client, String url) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept-Encoding", "br")
                .GET();
        if (AUTH_HEADER != null) rb.header("Authorization", AUTH_HEADER);
        HttpResponse<byte[]> response = client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            // 429 : la doc précise que chaque requête envoyée pendant le blocage le prolonge
            // d'une fenêtre complète -> on échoue tout de suite, sans retry.
            throw new RuntimeException("Erreur HTTP " + response.statusCode() + " pour " + url);
        }
        Path tmp = Files.createTempFile("skinport", ".br");
        Files.write(tmp, response.body());
        ProcessBuilder pb = new ProcessBuilder("brotli", "-d", "-c", tmp.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String json = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        Files.deleteIfExists(tmp);
        return json;
    }

    // Variante authentifiée de fetchUrl, pour les endpoints /v1/account/* qui exigent
    // l'en-tête Authorization (Basic base64(clientId:clientSecret) — Skinport n'utilise PAS
    // un vrai flux OAuth2 avec token, juste du Basic Auth classique sur chaque requête).
    static String fetchUrlAuth(HttpClient client, String url, String authHeader) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept-Encoding", "br")
                .header("Authorization", authHeader)
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Erreur HTTP " + response.statusCode() + " pour " + url
                    + " — vérifie SKINPORT_CLIENT_ID / SKINPORT_CLIENT_SECRET");
        }
        Path tmp = Files.createTempFile("skinport-acc", ".br");
        Files.write(tmp, response.body());
        ProcessBuilder pb = new ProcessBuilder("brotli", "-d", "-c", tmp.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String json = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        Files.deleteIfExists(tmp);
        return json;
    }

    // Récupère la totalité des transactions du compte (toutes les pages, 100 par page = le
    // max autorisé par l'API), triées desc (défaut Skinport = plus récent en premier).
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> fetchAllTransactions(HttpClient client, String authHeader) throws Exception {
        List<Map<String, Object>> all = new ArrayList<>();
        int page = 1;
        while (true) {
            String url = "https://api.skinport.com/v1/account/transactions?page=" + page
                    + "&limit=100&order=desc";
            Map<String, Object> resp = (Map<String, Object>) new JsonParser(fetchUrlAuth(client, url, authHeader)).parse();
            List<Object> data = (List<Object>) resp.get("data");
            for (Object o : data) all.add((Map<String, Object>) o);
            Map<String, Object> pagination = (Map<String, Object>) resp.get("pagination");
            int pages = asInt(pagination.get("pages"));
            if (page >= pages || data.isEmpty()) break;
            page++;
        }
        return all;
    }

    // Stats agrégées par item (market_hash_name) à partir de l'historique de transactions.
    // "enStock" est une APPROXIMATION (achetés - vendus) : Skinport n'expose aucun endpoint
    // pour lister les items actuellement possédés/en vente, donc impossible de savoir avec
    // certitude si un exemplaire précis a été revendu (les asset_id changent après un trade).
    // totalGagne / gagne (par item) sont NETS (après commission Skinport, cf. totalFrais) —
    // c'est le montant réellement crédité sur le compte, cohérent avec ce qu'affiche Skinport.
    record ItemAccountStats(String name, int achetes, int vendus, double totalDepense,
                             double totalGagne, int enStock, String image) {}

    record GlobalAccountStats(double totalDepense, double totalGagne, double totalRetire,
                               double totalFrais, double benefice, int nbTransactions) {}

    // Solde du compte vendeur (/v1/account/balance) : current = disponible, pending = ventes pas
    // encore réglées par le prestataire de paiement, held = ventes d'items encore sous trade
    // protection (bloquées jusqu'à la fin de la protection). Renvoie null si indisponible
    // (compte sans compte vendeur -> 400 validation_error, ou endpoint en erreur) : non bloquant.
    @SuppressWarnings("unchecked")
    static Map<String, Object> fetchBalance(HttpClient client, String authHeader) {
        try {
            Map<String, Object> resp = (Map<String, Object>) new JsonParser(fetchUrlAuth(client,
                    "https://api.skinport.com/v1/account/balance?currency=EUR", authHeader)).parse();
            Object data = resp.get("data");
            return data instanceof Map ? (Map<String, Object>) data : null;
        } catch (Exception e) {
            System.err.println("Solde du compte indisponible : " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static String buildAccountStatsJson(List<Map<String, Object>> transactions, Map<String, String> imageByName,
                                        Map<String, Object> balance) {
        Map<String, double[]> perItem = new LinkedHashMap<>(); // name -> [achetes, vendus, depense, gagneNet]
        double totalDepense = 0, totalGagneNet = 0, totalRetire = 0, totalFrais = 0;

        for (Map<String, Object> tx : transactions) {
            String type = (String) tx.get("type");
            String subType = (String) tx.get("sub_type");
            double txFee = asDouble(tx.get("fee"));
            totalFrais += txFee;

            if ("withdraw".equals(type)) {
                totalRetire += asDouble(tx.get("amount"));
                continue;
            }

            List<Object> items = (List<Object>) tx.get("items");
            if (items == null) continue;

            boolean isPurchase = "purchase".equals(type);
            boolean isSale = "credit".equals(type) && "item".equals(subType);
            if (!isPurchase && !isSale) continue;

            // Pour une vente, "amount" par item est BRUT (avant commission Skinport) ; la
            // commission ("fee") est portée au niveau de la transaction, pas de l'item. Quand
            // une tx groupe plusieurs items, on répartit la commission au prorata du montant
            // brut de chaque item pour obtenir un "gagné" net cohérent par item ET au global.
            double txAmountTotal = 0;
            if (isSale) {
                for (Object o : items) {
                    txAmountTotal += asDouble(((Map<String, Object>) o).get("amount"));
                }
            }

            for (Object o : items) {
                Map<String, Object> item = (Map<String, Object>) o;
                String name = (String) item.get("market_hash_name");
                double amount = asDouble(item.get("amount"));
                double[] agg = perItem.computeIfAbsent(name, k -> new double[4]);
                if (isPurchase) {
                    agg[0] += 1;
                    agg[2] += amount;
                    totalDepense += amount;
                } else {
                    double part = txAmountTotal > 0 ? amount / txAmountTotal : 0;
                    double net = amount - txFee * part;
                    agg[1] += 1;
                    agg[3] += net;
                    totalGagneNet += net;
                }
            }
        }

        List<ItemAccountStats> items = new ArrayList<>();
        for (Map.Entry<String, double[]> e : perItem.entrySet()) {
            double[] a = e.getValue();
            items.add(new ItemAccountStats(e.getKey(), (int) a[0], (int) a[1], a[2], a[3],
                    Math.max(0, (int) a[0] - (int) a[1]), imageByName.getOrDefault(e.getKey(), "")));
        }
        items.sort(Comparator.comparing(ItemAccountStats::name));

        GlobalAccountStats global = new GlobalAccountStats(totalDepense, totalGagneNet, totalRetire,
                totalFrais, totalGagneNet - totalDepense, transactions.size());

        String generatedAt = java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Paris"))
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.FRANCE));

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"generatedAt\":\"").append(generatedAt).append("\"");
        sb.append(",\"global\":{")
          .append("\"totalDepense\":").append(global.totalDepense())
          .append(",\"totalGagne\":").append(global.totalGagne())
          .append(",\"totalRetire\":").append(global.totalRetire())
          .append(",\"totalFrais\":").append(global.totalFrais())
          .append(",\"benefice\":").append(global.benefice())
          .append(",\"nbTransactions\":").append(global.nbTransactions())
          .append("}");
        if (balance != null) {
            sb.append(",\"balance\":{")
              .append("\"current\":").append(asDouble(balance.get("current")))
              .append(",\"pending\":").append(asDouble(balance.get("pending")))
              .append(",\"held\":").append(asDouble(balance.get("held")))
              .append("}");
        }
        sb.append(",\"items\":[");
        boolean first = true;
        for (ItemAccountStats it : items) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"n\":\"").append(jsonEscape(it.name())).append("\"")
              .append(",\"achetes\":").append(it.achetes())
              .append(",\"vendus\":").append(it.vendus())
              .append(",\"depense\":").append(it.totalDepense())
              .append(",\"gagne\":").append(it.totalGagne())
              .append(",\"enStock\":").append(it.enStock())
              .append(",\"img\":\"").append(jsonEscape(it.image())).append("\"")
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    // Fetch simple, sans décompression brotli (contrairement à fetchUrl, dédié à l'API
    // Skinport) — utilisé pour le catalogue d'images externe (raw.githubusercontent.com).
    static String fetchPlain(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Erreur HTTP " + response.statusCode() + " pour " + url);
        }
        return response.body();
    }

    static double asDouble(Object o) { return o == null ? 0.0 : (Double) o; }

    // Médiane pondérée par le volume de chaque fenêtre (24h/7j/30j/90j). Les fenêtres étant
    // cumulatives (24h ⊂ 7j ⊂ 30j ⊂ 90j), ce n'est pas une vraie médiane sans recouvrement,
    // mais une moyenne pondérée par liquidité qui reflète où l'item se vend réellement,
    // par opposition au prix demandé (souvent optimiste) des annonces en cours.
    // Retourne -1 si aucune vente enregistrée sur les 4 fenêtres.
    static double weightedMedian(SalesVolumes v) {
        double totalVol = v.v24h() + v.v7j() + v.v30j() + v.v90j();
        if (totalVol <= 0) return -1;
        double sum = v.m24h() * v.v24h() + v.m7j() * v.v7j() + v.m30j() * v.v30j() + v.m90j() * v.v90j();
        return sum / totalVol;
    }

    static int asInt(Object o) { return o == null ? 0 : ((Double) o).intValue(); }

    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    // ===================================================================
    // Modèle de prix de revente (4 vitesses) — voir formule_prix_4_vitesses.md à la racine
    // du dépôt (remplace l'ancien modèle multi-facteurs, jugé imprévisible en pratique).
    // Résumé :
    //
    // L = Tmin (marché tradable, prix le plus bas actuellement disponible)
    // M = Tmedian (médiane du marché tradable actuel)
    // H = moyenne des médianes de vente 24h/7j/30j/90j pondérée par leur volume (fenêtres
    //     à volume=0 ou médiane absente simplement ignorées — voir weightedMedian())
    //
    // BASE = min(H, L) si un historique existe, sinon L : une anomalie historique haute ne
    //        peut jamais faire monter le prix au-dessus du marché actuellement disponible.
    // SPREAD = max(0, M - L) : l'espace entre le prix le plus bas et la médiane du marché.
    //
    // VERY_FAST = min(M, BASE)
    // FAST      = min(M, BASE + 0.15 * SPREAD)
    // NORMAL    = min(M, BASE + 0.35 * SPREAD)
    // PATIENT   = min(M, BASE + 0.60 * SPREAD)
    //
    // Les coefficients 0.15/0.35/0.60 sont centralisés ici (PRICE_SPEED_COEFFS), pas
    // dupliqués en dur ailleurs dans le code.
    // ===================================================================
    static final double[] PRICE_SPEED_COEFFS = {0.00, 0.15, 0.35, 0.60}; // VERY_FAST, FAST, NORMAL, PATIENT

    static PriceModelResult computePriceModel(SalesVolumes v, MarketStats t, MarketStats g) {
        double L = t.min();
        double M = t.median();
        double Q = t.quantity();

        // Si L est absent, aucun prix espéré ne doit être calculé (aucune annonce tradable
        // sur laquelle baser le modèle).
        if (L <= 0) return PriceModelResult.EMPTY;

        // H : moyenne pondérée par volume des médianes 24h/7j/30j/90j (weightedMedian ignore
        // déjà nativement les fenêtres à volume nul). -1 si aucune fenêtre exploitable.
        double H = weightedMedian(v);
        boolean hasHistory = H > 0;

        double BASE = hasHistory ? Math.min(H, L) : L;

        double veryFast, fast, normal, patient;
        if (M > 0) {
            double SPREAD = Math.max(0, M - L);
            veryFast = Math.min(M, BASE + PRICE_SPEED_COEFFS[0] * SPREAD);
            fast     = Math.min(M, BASE + PRICE_SPEED_COEFFS[1] * SPREAD);
            normal   = Math.min(M, BASE + PRICE_SPEED_COEFFS[2] * SPREAD);
            patient  = Math.min(M, BASE + PRICE_SPEED_COEFFS[3] * SPREAD);
        } else {
            // Pas de médiane tradable exploitable : pas de spread possible, les 4 prix
            // retombent sur BASE (qui vaut L si en plus aucun historique n'est disponible).
            veryFast = fast = normal = patient = BASE;
        }

        // Le reste (confiance/liquidité/tendance/désaccord) reste purement informatif pour
        // la page de détail d'un skin — sans effet sur les 4 prix calculés ci-dessus.
        double totalSalesVol = v.v24h() + v.v7j() + v.v30j() + v.v90j();
        double confidence = clamp(
                0.5 * clamp(Math.log(1 + totalSalesVol) / Math.log(1 + 30), 0, 1)
                        + 0.5 * clamp(Math.log(1 + Q) / Math.log(1 + 30), 0, 1),
                0, 1);
        double liquidity = clamp((Math.log(1 + v.v7j()) + Math.log(1 + Q)) / 8.0, 0, 1);
        double trend = (v.m24h() > 0 && v.m7j() > 0)
                ? clamp(Math.log(v.m24h() / v.m7j()), -0.5, 0.5) : 0;
        double disagreement = (hasHistory && M > 0) ? Math.abs(Math.log(M) - Math.log(H)) : 0;

        return new PriceModelResult(BASE, confidence, veryFast, fast, normal, patient,
                liquidity, disagreement, trend);
    }

    // ===================================================================
    // Prix "Très rapide" v2 — prix auquel l'item part en 24-48 h. Calibré le 17/11/2026 sur le
    // flux live Skinport (chaque annonce / vente en temps réel) croisé avec l'API REST ; détails,
    // chiffres et limites dans le README (section "Implémentation actuelle").
    //
    // Ce que les données ont montré (skins d'armes, couteaux, gants) :
    //  - deux files : les items tradables se vendent au min tradable L, les items sous trade-lock
    //    au min verrouillé G (~10 % moins cher) ; l'historique REST mélange les deux ;
    //  - l'annonce TRADABLE la moins chère est achetée au taux λ·A(r), λ = ventes/jour de l'item,
    //    r = prix / médiane des prix payés récemment ; A baisse quand le prix dépasse la médiane
    //    (élasticité-prix B) et plafonne à A_MAX sous R_LO·médiane (baisser davantage n'accélère
    //    plus la vente) ;
    //  - λ estimé par fenêtres disjointes pondérées par récence prédit bien les ventes observées
    //    (l'ancien "ventes 24 h, sinon 7 j / 7..." surestimait la liquidité d'environ 33 %).
    //
    // Calcul : P(vendu sous 48 h au prix P) = 1 - exp(-2 · λ · A(P / médiane)). On prend le prix le
    // plus haut qui atteint 80 % (≈ 55 % sous 24 h) ; si c'est impossible même à R_LO·médiane, on
    // garde R_LO·médiane (meilleure chance réelle, probabilité affichée) ; jamais au-dessus du
    // concurrent tradable - 1 centime (une annonce moins chère passerait avant). Le concurrent dépend
    // de ce qu'on revend : le Min tradable pour un item déjà possédé ou une offre trade-protect
    // (achetable tout de suite, livrée à la fin de sa protection) ("xf") ; la 2e annonce tradable si
    // l'on achète le Min lui-même ("xb").
    //
    // λ et la médiane viennent des 4 fenêtres Skinport (24 h / 7 j / 30 j / 90 j), qui se
    // RECOUVRENT : on les redécoupe en périodes disjointes (0-1 j, 1-7 j, 7-30 j, 30-90 j) pondérées
    // par ancienneté (demi-vie adaptative : la plus courte qui donne assez de ventes) ; la médiane
    // est celle d'un mélange, pondéré pareil, de lois log-normales asymétriques ajustées sur
    // min / médiane / max de chaque fenêtre.
    // ===================================================================
    record SalesWindows(double[] n, double[] lo, double[] med, double[] hi) {
        // index 0..3 = 24 h, 7 j, 30 j, 90 j ; lo/med/hi = 0 si aucune vente dans la fenêtre
        static SalesWindows of(Map<String, Object> h24, Map<String, Object> h7, Map<String, Object> h30, Map<String, Object> h90) {
            List<Map<String, Object>> ws = List.of(h24, h7, h30, h90);
            double[] n = new double[4], lo = new double[4], med = new double[4], hi = new double[4];
            for (int i = 0; i < 4; i++) {
                n[i] = asInt(ws.get(i).get("volume"));
                lo[i] = asDouble(ws.get(i).get("min"));
                med[i] = asDouble(ws.get(i).get("median"));
                hi[i] = asDouble(ws.get(i).get("max"));
            }
            return new SalesWindows(n, lo, med, hi);
        }
        static final SalesWindows EMPTY = new SalesWindows(new double[4], new double[4], new double[4], new double[4]);
    }

    // price = prix brut conseillé (0 si incalculable), lambda = ventes/jour estimées,
    // prob48h = probabilité estimée de vente sous 48 h à ce prix, mode = branche du calcul.
    // salesMedian = prix "typique" des dernières ventes (médiane pondérée par récence, 0 si aucune).
    record FastPrice(double price, double lambda, double prob48h, String mode, double salesMedian) {
        static final FastPrice NONE = new FastPrice(0, 0, 0, "none", 0);
    }

    static final class FastSellModel {
        static final double HORIZON_DAYS = 2.0;   // objectif : vendu sous 48 h...
        static final double TARGET_PROB = 0.80;   // ...avec 80 % de chances (≈ 55 % sous 24 h)
        static final double TICK = 0.01;          // pas de prix Skinport
        static final double[] AGES = {0.5, 4.0, 18.5, 60.0};   // âge moyen (j) des périodes disjointes
        static final double[] DURS = {1.0, 6.0, 23.0, 60.0};   // durée (j) de ces périodes
        static final double[] TAUS = {2, 4, 8, 16, 32, 64, Double.POSITIVE_INFINITY}; // demi-vies essayées
        static final double N_MIN = 4.0;          // ventes "effectives" minimum avant d'élargir
        static final double SIGMA0 = 0.06;        // dispersion a priori des prix de vente (log)
        static final double K_PRIOR = 2.0;        // poids de l'a priori, en nombre de ventes
        // Acceptation MESURÉE : une annonce TRADABLE en tête de file (moins chère que toutes les autres
        // annonces tradables) au prix P est achetée au taux λ^LAMBDA_EXP · A(r), r = P / médiane des
        // prix payés récemment :
        //     A(r) = aMax                     si r <= rLo   (baisser davantage n'accélère plus)
        //     A(r) = aMax · (r / rLo)^(-b)    sinon         (élasticité-prix b)
        // Recalibré le 18/11/2026 sur une JOURNÉE entière (photo complète du marché, puis ventes des 24 h
        // suivantes, 17 903 items, 1 705 ventes ; validé hors échantillon) : la 1re calibration (1 h 10
        // de flux live en soirée, aMax 0,80 / rLo 0,89 / b 3,0) surestimait les ventes d'environ 50 %.
        // Deux familles : les stickers et caisses réagissent beaucoup moins au prix que les skins.
        // LAMBDA_EXP < 1 : sur les items très liquides, plus de concurrence en tête de file.
        record Params(double aMax, double rLo, double b) {}
        // skins d'armes, couteaux, gants, charms, agents, music kits... : IC 95 % rLo [0,91 ; 0,96], b [3 ; 4]
        static final Params SKINS = new Params(0.40, 0.94, 3.25);
        // stickers et caisses (rLo borné à 0,85 : sans borne il partirait vers 0,3, ce qui brade l'item)
        static final Params STACK = new Params(0.21, 0.85, 1.5);
        static final double LAMBDA_EXP = 0.80;
        static final double NO_SALES_DISCOUNT = 0.20;  // aucune vente sur 90 j
        // Plus AUCUNE annonce tradable après l'achat (le Min était la seule) : pas de concurrent pour
        // plafonner, on borne au 90e centile des prix payés récemment au lieu d'extrapoler A(r).
        static final double Q_CEIL = 0.90;

        static double erfc(double x) { // Numerical Recipes erfcc, erreur relative < 1.2e-7
            double z = Math.abs(x);
            double t = 1.0 / (1.0 + 0.5 * z);
            double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                    + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                    + t * (-0.82215223 + t * 0.17087277)))))))));
            return x >= 0 ? r : 2.0 - r;
        }

        static double phi(double x) { return 0.5 * erfc(-x / Math.sqrt(2.0)); }

        static double phiInv(double p) { // Acklam
            final double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
            final double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01};
            final double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
            final double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
            final double pl = 0.02425;
            if (p < pl) {
                double q = Math.sqrt(-2 * Math.log(p));
                return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
            }
            if (p > 1 - pl) {
                double q = Math.sqrt(-2 * Math.log(1 - p));
                return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
            }
            double q = p - 0.5, r = q * q;
            return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
        }

        // Composante du mélange : {poids, log-médiane, sigma bas, sigma haut}
        static double[] windowDist(double n, double lo, double med, double hi) {
            if (n <= 0 || !(med > 0)) return null;
            double xm = Math.log(med), sl, sh;
            if (n >= 2 && lo > 0 && hi > 0) {
                double z = phiInv((n - 0.375) / (n + 0.25)); // position attendue du max de n ventes (Blom)
                sl = (xm - Math.log(Math.min(lo, med))) / z;
                sh = (Math.log(Math.max(hi, med)) - xm) / z;
                sl = (n * sl + K_PRIOR * SIGMA0) / (n + K_PRIOR);
                sh = (n * sh + K_PRIOR * SIGMA0) / (n + K_PRIOR);
            } else {
                sl = sh = SIGMA0;
            }
            sl = Math.min(Math.max(sl, 0.005), 0.6);
            sh = Math.min(Math.max(sh, 0.005), 0.6);
            return new double[]{0, xm, sl, sh};
        }

        static double cdf(double x, List<double[]> comps) {
            double s = 0;
            for (double[] c : comps) s += c[0] * phi((x - c[1]) / (x < c[1] ? c[2] : c[3]));
            return s;
        }

        static double quantile(double p, List<double[]> comps) {
            double lo = -20, hi = 20;
            for (int i = 0; i < 80; i++) {
                double mid = (lo + hi) / 2;
                if (cdf(mid, comps) < p) lo = mid; else hi = mid;
            }
            return (lo + hi) / 2;
        }

        static double floorCents(double p) { return Math.max(TICK, Math.floor(p * 100 + 1e-9) / 100); }

        // Famille de paramètres d'après la catégorie de la page marché ("...&cat=Sticker" ou
        // ".../market/sticker?...") ; tout ce qui n'est ni sticker ni caisse prend SKINS.
        static Params paramsFor(String marketPage) {
            String cat = "";
            if (marketPage != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("[?&]cat=([^&]+)|/market/([^/?&]+)").matcher(marketPage);
                if (m.find()) cat = (m.group(1) != null ? m.group(1) : m.group(2)).toLowerCase(Locale.ROOT);
            }
            return (cat.equals("sticker") || cat.equals("container")) ? STACK : SKINS;
        }

        static double accept(double r, Params pr) {
            return r <= pr.rLo() ? pr.aMax() : pr.aMax() * Math.pow(r / pr.rLo(), -pr.b());
        }

        /** L = min tradable (annonces achetables tout de suite), G = min toutes annonces (trade-lock inclus). */
        // Prix "Très rapide" dans le marché actuel : il faut passer sous le Min tradable L (1 centime
        // en dessous). C'est le bon prix pour revendre un item qui n'est PAS cette annonce : item
        // déjà en inventaire, ou offre trade-protect (achetable tout de suite, revendue une fois livrée ; absente du marché
        // tradable, elle ne retire pas L).
        static FastPrice compute(SalesWindows s, double L, double G, Params pr) {
            if (!(L > 0)) return FastPrice.NONE;
            return price(s, L - TICK, G > 0 ? Math.min(G, L) : L, false, pr);
        }

        // Prix "Très rapide" pour qui ACHÈTE l'annonce tradable la moins chère (L) : une fois achetée
        // elle ne fait plus concurrence, la revente doit seulement passer sous la 2e annonce tradable
        // L2. Sans ça, l'annonce achetée servirait de plafond à sa propre revente et l'écart serait
        // toujours négatif (≤ -8 % après commission). L'API ne donne que min / médiane / moyenne /
        // max / quantité, ce qui fixe L2 exactement jusqu'à 3 annonces (voir secondTradable) ; avec
        // une seule annonce il n'y a plus de concurrent tradable (plafond Q_CEIL). null si L2 est
        // inconnu (plus de 3 annonces) : l'appelant garde alors compute(), plafonné sous L (prudent).
        static FastPrice computeAfterBuyingFront(SalesWindows s, double L, double G, MarketStats t, Params pr) {
            if (!(L > 0)) return null;
            double front = G > 0 ? Math.min(G, L) : L;
            if (Math.round(t.quantity()) == 1) return price(s, Double.POSITIVE_INFINITY, front, true, pr);
            Double l2 = secondTradable(t);
            return l2 == null ? null : price(s, l2 - TICK, front, false, pr);
        }

        // 2e prix tradable le plus bas quand l'API le donne exactement : 2 annonces -> max_price,
        // 3 annonces -> median_price (vérifié le 18/11/2026 : médiane = moyenne des deux prix à 2
        // annonces, valeur du milieu à 3). null sinon (1 annonce, plus de 3, ou données incohérentes).
        static Double secondTradable(MarketStats t) {
            long q = Math.round(t.quantity());
            double l2 = q == 2 ? t.max() : q == 3 ? t.median() : Double.NaN;
            return (l2 > 0 && l2 >= t.min()) ? l2 : null;
        }

        // Cœur du calcul. cap = prix maximum (1 centime sous le concurrent tradable, +∞ s'il n'y en a
        // pas) ; front = référence de la branche "aucune vente" ; ceilQ = borne au centile Q_CEIL.
        private static FastPrice price(SalesWindows s, double cap, double front, boolean ceilQ, Params pr) {
            double[] n = s.n();
            double[] c = {n[0], Math.max(0, n[1] - n[0]), Math.max(0, n[2] - n[1]), Math.max(0, n[3] - n[2])};
            double[] w = new double[4];
            double neff = 0;
            for (double tau : TAUS) {
                neff = 0;
                for (int k = 0; k < 4; k++) {
                    w[k] = Double.isInfinite(tau) ? 1.0 : Math.exp(-AGES[k] / tau);
                    neff += w[k] * c[k];
                }
                if (neff >= N_MIN || Double.isInfinite(tau)) break;
            }
            double den = 0;
            for (int k = 0; k < 4; k++) den += w[k] * DURS[k];
            double lambda = den > 0 ? neff / den : 0;

            // Poids de mélange des fenêtres CUMULATIVES équivalents à la pondération des périodes
            // disjointes : Σ w_k·c_k·F_(k) = n1(w0-w1)F_24h + n7(w1-w2)F_7j + n30(w2-w3)F_30j + n90·w3·F_90j
            double[] beta = {n[0] * (w[0] - w[1]), n[1] * (w[1] - w[2]), n[2] * (w[2] - w[3]), n[3] * w[3]};
            List<double[]> comps = new ArrayList<>();
            double tot = 0;
            for (int k = 0; k < 4; k++) {
                if (beta[k] <= 0) continue;
                double[] wd = windowDist(n[k], s.lo()[k], s.med()[k], s.hi()[k]);
                if (wd == null) continue;
                wd[0] = beta[k];
                tot += beta[k];
                comps.add(wd);
            }
            if (comps.isEmpty() || tot <= 0) {
                // Aucune vente sur 90 jours : rien ne garantit une vente en 48 h ; seule une forte
                // décote sous la tête de file peut attirer un acheteur opportuniste.
                return new FastPrice(floorCents(Math.min(cap, front * (1 - NO_SALES_DISCOUNT))), 0, 0, "no-sales", 0);
            }
            for (double[] cp : comps) cp[0] /= tot;

            double med = Math.exp(quantile(0.5, comps));   // prix "typique" récent (pondéré par récence)
            double xStar = -Math.log(1 - TARGET_PROB);       // nb moyen d'achats requis sur l'horizon
            double lamEff = Math.pow(lambda, LAMBDA_EXP);
            double xMax = HORIZON_DAYS * lamEff * pr.aMax(); // ... et le maximum atteignable (prix <= rLo·med)
            double r;
            String mode;
            if (pr.b() <= 0) {
                r = Double.POSITIVE_INFINITY;                // pas d'effet prix : la tête de file suffit
                mode = "front";
            } else if (xMax >= xStar) {
                r = pr.rLo() * Math.pow(xMax / xStar, 1 / pr.b()); // plus haut prix qui atteint la cible
                mode = "target";
            } else {
                r = pr.rLo();                                // cible inatteignable : meilleure chance réelle
                mode = "best-effort";
            }
            double p = med * r;
            if (ceilQ) {
                double q = Math.exp(quantile(Q_CEIL, comps));
                if (p > q) { p = q; mode += "+q90"; }
            }
            if (p >= cap) { p = cap; mode += "+cap"; }
            p = floorCents(p);
            double prob = 1 - Math.exp(-HORIZON_DAYS * lamEff * accept(p / med, pr));
            return new FastPrice(p, lambda, prob, mode, med);
        }
    }

    // Doppler : Skinport agrège toutes les phases (1-4, Ruby/Sapphire/Black Pearl, Gamma Doppler...)
    // sous le même market_hash_name générique "Doppler", sans distinction de phase dans le prix.
    // Le "min" et le "prix espéré" peuvent donc chacun venir d'une phase différente -> écart non fiable.
    static boolean isDoppler(String name) {
        return name.toLowerCase(Locale.ROOT).contains("doppler");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sub(Map<String, Object> item, String key) {
        Object v = item.get(key);
        return v == null ? Map.of() : (Map<String, Object>) v;
    }

    static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        String outPath = args.length > 0 ? args[0] : "skingap.html";

        HttpClient client = HttpClient.newHttpClient();

        String clientId = System.getenv("SKINPORT_CLIENT_ID");
        String clientSecret = System.getenv("SKINPORT_CLIENT_SECRET");
        boolean hasCredentials = clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        if (hasCredentials) {
            AUTH_HEADER = "Basic " + Base64.getEncoder().encodeToString(
                    (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        }

        System.out.println("Récupération de l'historique des ventes (volume, toutes périodes)...");
        String jsonSales = fetchUrl(client, "https://api.skinport.com/v1/sales/history?app_id=730&currency=EUR");
        System.out.println("Récupération des prix actuels (avec trade-protect, pour le prix d'achat)...");
        String jsonItemsAll = fetchUrl(client, "https://api.skinport.com/v1/items?app_id=730&currency=EUR&tradable=false");
        System.out.println("Récupération des prix actuels (tradable uniquement, pour le prix de vente espéré)...");
        // tradable=true est INDISPENSABLE : sans ce paramètre l'API applique sa valeur par défaut
        // (false) et renvoie toutes les annonces, trade-protect compris — exactement comme l'appel
        // précédent. Jusqu'au 18/11/2026 le "Min tradable" était donc en fait le min trade-protect
        // inclus (Min et Min (TP) identiques sur tous les items, 19 % des items concernés).
        String jsonItemsTradable = fetchUrl(client, "https://api.skinport.com/v1/items?app_id=730&currency=EUR&tradable=true");

        List<Object> salesList = (List<Object>) new JsonParser(jsonSales).parse();
        List<Object> itemsAllList = (List<Object>) new JsonParser(jsonItemsAll).parse();
        List<Object> itemsTradableList = (List<Object>) new JsonParser(jsonItemsTradable).parse();

        // Catalogue d'images (projet public ByMykel/CSGO-API, indexé par market_hash_name —
        // même format de nom que Skinport pour les skins d'armes). Échec non bloquant :
        // si indisponible, on continue sans image plutôt que de faire échouer tout le run.
        System.out.println("Récupération du catalogue d'images...");
        Map<String, String> imageByName = new HashMap<>();
        try {
            String jsonImages = fetchPlain(client,
                    "https://raw.githubusercontent.com/ByMykel/CSGO-API/main/public/api/en/skins_not_grouped.json");
            List<Object> imagesList = (List<Object>) new JsonParser(jsonImages).parse();
            for (Object o : imagesList) {
                Map<String, Object> item = (Map<String, Object>) o;
                Object mhn = item.get("market_hash_name");
                Object img = item.get("image");
                if (mhn == null || img == null) continue;
                // Le catalogue a une entrée (et une image) par phase Doppler, champ "phase" —
                // mêmes libellés que le champ "version" de Skinport (Phase 1, Ruby, ...).
                imageByName.put(itemKey((String) mhn, item.get("phase")), (String) img);
                imageByName.putIfAbsent((String) mhn, (String) img);
            }
            System.out.println("Catalogue d'images chargé (" + imageByName.size() + " entrées).");
        } catch (Exception e) {
            System.err.println("Catalogue d'images indisponible, on continue sans : " + e.getMessage());
        }

        Map<String, SalesVolumes> volumesByName = new HashMap<>();
        Map<String, SalesWindows> windowsByName = new HashMap<>();
        Map<String, String> marketPageByName = new HashMap<>();
        for (Object o : salesList) {
            Map<String, Object> item = (Map<String, Object>) o;
            String name = itemKey((String) item.get("market_hash_name"), item.get("version"));
            Map<String, Object> h24 = sub(item, "last_24_hours");
            Map<String, Object> h7 = sub(item, "last_7_days");
            Map<String, Object> h30 = sub(item, "last_30_days");
            Map<String, Object> h90 = sub(item, "last_90_days");
            windowsByName.put(name, SalesWindows.of(h24, h7, h30, h90));
            volumesByName.put(name, new SalesVolumes(
                    asInt(h24.get("volume")), asInt(h7.get("volume")),
                    asInt(h30.get("volume")), asInt(h90.get("volume")),
                    asDouble(h24.get("median")), asDouble(h7.get("median")),
                    asDouble(h30.get("median")), asDouble(h90.get("median"))
            ));
            Object mp = item.get("market_page");
            if (mp != null) marketPageByName.put(name, (String) mp);
        }

        // Prix d'achat actuel = prix minimum, trade-protect inclus (ce qu'on paierait pour acheter aujourd'hui,
        // y compris les items encore verrouillés qui sont souvent moins chers).
        // Marché global (§8, "G") : toutes les annonces, y compris sous trade-lock.
        Map<String, MarketStats> globalStats = new HashMap<>();
        Map<String, String> versionByKey = new HashMap<>();
        for (Object o : itemsAllList) {
            Map<String, Object> item = (Map<String, Object>) o;
            String name = itemKey((String) item.get("market_hash_name"), item.get("version"));
            if (item.get("version") != null) versionByKey.put(name, String.valueOf(item.get("version")));
            Object mp0 = item.get("market_page");
            // La market_page des variantes contient déjà le filtre de phase (&phase=N) : on la
            // préfère à celle de l'historique, au cas où celui-ci n'aurait pas l'entrée.
            if (mp0 != null) marketPageByName.putIfAbsent(name, (String) mp0);
            Object min = item.get("min_price");
            if (min == null) continue;
            globalStats.put(name, new MarketStats(
                    (Double) min,
                    item.get("median_price") != null ? (Double) item.get("median_price") : 0,
                    item.get("mean_price") != null ? (Double) item.get("mean_price") : 0,
                    item.get("quantity") != null ? (Double) item.get("quantity") : 0,
                    item.get("suggested_price") != null ? (Double) item.get("suggested_price") : 0,
                    item.get("max_price") != null ? (Double) item.get("max_price") : 0
            ));
        }
        Map<String, Double> minWithLocked = new HashMap<>();
        for (Map.Entry<String, MarketStats> e : globalStats.entrySet()) minWithLocked.put(e.getKey(), e.getValue().min());

        // Prix de vente espéré (brut, avant taxe) = prix minimum parmi les annonces immédiatement
        // disponibles (tradable=true, donc hors trade-protect) : c'est le prix auquel il faudrait
        // s'aligner pour revendre l'item rapidement, une fois son propre trade-protect écoulé.
        // Marché tradable (§3/§6, "T") : c'est le signal le plus important pour la revente.
        Map<String, MarketStats> tradableStats = new HashMap<>();
        for (Object o : itemsTradableList) {
            Map<String, Object> item = (Map<String, Object>) o;
            String name = itemKey((String) item.get("market_hash_name"), item.get("version"));
            Object min = item.get("min_price");
            if (min == null) continue;
            tradableStats.put(name, new MarketStats(
                    (Double) min,
                    item.get("median_price") != null ? (Double) item.get("median_price") : 0,
                    item.get("mean_price") != null ? (Double) item.get("mean_price") : 0,
                    item.get("quantity") != null ? (Double) item.get("quantity") : 0,
                    item.get("suggested_price") != null ? (Double) item.get("suggested_price") : 0,
                    item.get("max_price") != null ? (Double) item.get("max_price") : 0
            ));
        }
        Map<String, Double> minTradable = new HashMap<>();
        for (Map.Entry<String, MarketStats> e : tradableStats.entrySet()) minTradable.put(e.getKey(), e.getValue().min());

        List<Result> results = new ArrayList<>();
        for (Map.Entry<String, Double> e : minWithLocked.entrySet()) {
            String name = e.getKey();
            // Doppler : historiquement exclu car Skinport mélangeait toutes les phases sous le
            // même nom. L'API renvoie désormais une entrée par phase (champ "version") : on garde
            // donc les Doppler dont la phase est connue, et on n'exclut plus qu'une éventuelle
            // entrée agrégée sans phase.
            if (isDoppler(name) && !versionByKey.containsKey(name)) continue;
            Double askRaw = minTradable.get(name);
            // pas d'annonce tradable actuellement -> pas de prix de vente espéré calculable, on ignore
            if (askRaw == null) continue;
            SalesVolumes vol = volumesByName.getOrDefault(name, new SalesVolumes(0, 0, 0, 0, 0, 0, 0, 0));
            String mp = marketPageByName.getOrDefault(name, "https://skinport.com/market?item=" + name.replace(" ", "%20"));

            // On ne tranche plus ici entre prix demandé et médiane : les deux valeurs brutes
            // sont envoyées telles quelles, le mode de calcul (auto / médiane / min listé) est
            // choisi côté client et peut être changé sans re-fetch.
            double weighted = weightedMedian(vol);
            String baseName = name.contains(" [") ? name.substring(0, name.lastIndexOf(" [")) : name;
            String image = imageByName.getOrDefault(name, imageByName.getOrDefault(baseName, ""));
            MarketStats tStats = tradableStats.getOrDefault(name, MarketStats.EMPTY);
            MarketStats gStats = globalStats.getOrDefault(name, MarketStats.EMPTY);
            PriceModelResult model = computePriceModel(vol, tStats, gStats);

            SalesWindows windows = windowsByName.getOrDefault(name, SalesWindows.EMPTY);
            // xf : revente au marché actuel (concurrent = Min tradable) — c'est le bon prix pour une
            // offre trade-protect achetée (elle n'est pas dans le marché tradable, revendue une fois livrée) et
            // pour un item déjà en inventaire. xb : revente si on achète le Min tradable lui-même
            // (concurrent = 2e annonce tradable), seulement quand l'API permet de la connaître.
            FastSellModel.Params fastParams = FastSellModel.paramsFor(mp);
            FastPrice fast = FastSellModel.compute(windows, askRaw, e.getValue(), fastParams);
            FastPrice fastBuy = FastSellModel.computeAfterBuyingFront(windows, askRaw, e.getValue(), tStats, fastParams);
            results.add(new Result(name, mp, vol, e.getValue(), askRaw, weighted, image, tStats, gStats, model,
                    versionByKey.get(name), fast, fastBuy, FastSellModel.secondTradable(tStats)));
        }
        results.sort(Comparator.comparing(Result::name));

        StringBuilder data = new StringBuilder("[");
        boolean first = true;
        for (Result r : results) {
            if (!first) data.append(",");
            first = false;
            data.append("{\"n\":\"").append(jsonEscape(r.name())).append("\"")
                .append(",\"p\":\"").append(jsonEscape(r.marketPage())).append("\"")
                .append(",\"i\":\"").append(jsonEscape(r.image())).append("\"")
                .append(",\"min\":").append(r.minWithLocked())
                .append(",\"ask\":").append(r.askRaw())
                .append(",\"wm\":").append(r.medianRaw())
                .append(",\"v24\":").append(r.volumes().v24h())
                .append(",\"v7\":").append(r.volumes().v7j())
                .append(",\"v30\":").append(r.volumes().v30j())
                .append(",\"v90\":").append(r.volumes().v90j())
                // Médianes brutes par fenêtre (nécessaires pour le sélecteur de période sur la
                // page détail du dashboard — distinct de "wm" qui est la médiane pondérée globale).
                .append(",\"h24\":").append(r.volumes().m24h())
                .append(",\"h7\":").append(r.volumes().m7j())
                .append(",\"h30\":").append(r.volumes().m30j())
                .append(",\"h90\":").append(r.volumes().m90j())
                // Modèle 4 vitesses (voir prompt_prix_revente_skinport_corrige.md) — tous ces
                // prix sont BRUTS, avant la taxe Skinport (appliquée à l'affichage par withTax()
                // côté JS / skinportNet() côté serveur), conformément au §14 du prompt.
                .append(",\"fv\":").append(r.model().fairValue())
                .append(",\"conf\":").append(r.model().confidence())
                .append(",\"pvf\":").append(r.model().veryFast())
                .append(",\"pft\":").append(r.model().fast())
                .append(",\"pnm\":").append(r.model().normal())
                .append(",\"ppt\":").append(r.model().patient())
                .append(",\"liq\":").append(r.model().liquidity())
                .append(",\"dis\":").append(r.model().disagreement())
                .append(",\"trd\":").append(r.model().trend())
                // Profondeur de marché : quantité listée immédiatement tradable (qt), quantité
                // totale y compris trade-lock (qg, superset de qt) et médiane du marché tradable
                // (tmed). Sert au "stock en jours" (qt / ventes par jour) côté dashboard.
                .append(",\"qt\":").append((int) r.tradable().quantity())
                .append(",\"qg\":").append((int) r.global().quantity())
                .append(",\"tmed\":").append(r.tradable().median())
                // Prix "Très rapide" v2 (vente visée sous 24-48 h, voir FastSellModel) : prix brut
                // conseillé, ventes/jour estimées, probabilité estimée de vente sous 48 h, branche.
                .append(",\"xf\":").append(r.fast().price())
                .append(",\"xl\":").append(Math.round(r.fast().lambda() * 1000) / 1000.0)
                .append(",\"xp\":").append(Math.round(r.fast().prob48h() * 1000) / 1000.0)
                .append(",\"xm\":\"").append(r.fast().mode()).append("\"")
                // Prix typique des dernières ventes (médiane pondérée par récence) sur lequel repose le calcul.
                .append(",\"xs\":").append(Math.round(r.fast().salesMedian() * 100) / 100.0);
            // Revente "Très rapide" si l'on achète le Min tradable lui-même (xb, proba xbp) :
            // présent seulement quand la 2e annonce tradable est connue (<= 3 annonces) ; l2 = son prix.
            if (r.fastBuy() != null) {
                data.append(",\"xb\":").append(r.fastBuy().price())
                    .append(",\"xbp\":").append(Math.round(r.fastBuy().prob48h() * 1000) / 1000.0);
            }
            if (r.secondTradable() != null) data.append(",\"l2\":").append(r.secondTradable());
            if (r.version() != null) {
                data.append(",\"ver\":\"").append(jsonEscape(r.version())).append("\"");
            }
            data.append("}");
        }
        data.append("]");

        // Fichier séparé, structuré, pour être consommé par d'autres outils (dashboard tradelock)
        // sans avoir à re-parser le HTML. Mêmes données que celles déjà calculées ci-dessus,
        // aucun impact sur le calcul ni sur le rapport HTML.
        Path jsonOut = Path.of(outPath).resolveSibling("skins.json");
        Files.writeString(jsonOut, data.toString());

        // Horodatage "généré le" figé dans le HTML au moment de la génération (contrairement
        // au reste du dashboard, qui tourne côté navigateur et s'adapte tout seul) : on force
        // donc explicitement le fuseau français, sans quoi ça affiche l'heure UTC du runner
        // GitHub Actions, quel que soit le fuseau de la personne qui regarde ensuite.
        String generatedAt = java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Paris"))
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.FRANCE));
        String html = HTML_TEMPLATE
                .replace("__DATA__", data.toString())
                .replace("__COUNT__", String.valueOf(results.size()))
                .replace("__DATE__", generatedAt);

        Files.writeString(Path.of(outPath), html);
        System.out.println("OK — " + results.size() + " items écrits dans " + outPath);
        System.out.println("Ouvre ce fichier dans un navigateur (ex: termux-open " + outPath + ")");

        // Stats de compte Skinport (achats/ventes/bénéfice) — nécessite SKINPORT_CLIENT_ID et
        // SKINPORT_CLIENT_SECRET (Repository secrets du repo GitHub Actions). Non bloquant :
        // si absents (ex. run local sans les secrets), on saute cette partie proprement.
        if (hasCredentials) {
            System.out.println("Récupération des transactions du compte Skinport...");
            List<Map<String, Object>> transactions = fetchAllTransactions(client, AUTH_HEADER);
            Map<String, Object> balance = fetchBalance(client, AUTH_HEADER);
            String accountJson = buildAccountStatsJson(transactions, imageByName, balance);
            Path accountOut = Path.of(outPath).resolveSibling("account-stats.json");
            Files.writeString(accountOut, accountJson);
            System.out.println("OK — " + transactions.size() + " transactions écrites dans " + accountOut);
        } else {
            System.out.println("SKINPORT_CLIENT_ID / SKINPORT_CLIENT_SECRET absents — stats de compte ignorées.");
        }
    }

    // ---------------------------------------------------------------
    // Template HTML/JS — même thème visuel que la démo, mais pour la
    // liste complète, avec recherche, tri, override de médiane, et
    // sélecteur de période séparé pour le volume.
    // ---------------------------------------------------------------
    static final String HTML_TEMPLATE = """
<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>skingap</title>
<style>
  @import url('https://cdnjs.cloudflare.com/ajax/libs/ibm-plex/6.0.0/css/ibm-plex-mono.min.css');
  :root {
    --bg: #0e0d0b; --surface: #17150f; --border: #2c281f; --text: #ece7dc;
    --muted: #8f897a; --accent: #c98a2c; --accent-dim: #8a642a;
    --positive: #8caa7e; --negative: #b3705c;
  }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--text); font-family:'IBM Plex Mono', ui-monospace, monospace; font-size:13px; line-height:1.5; }
  .wrap { max-width: 980px; margin: 0 auto; padding: 24px 14px 60px; }
  header { border-bottom:1px solid var(--border); padding-bottom:14px; margin-bottom:14px; display:flex; justify-content:space-between; align-items:baseline; flex-wrap:wrap; gap:8px; }
  .wordmark { font-size:16px; } .wordmark span { color:var(--accent); }
  .tagline { color:var(--muted); font-size:12px; margin-top:4px; }
  .navlink { color:var(--muted); font-size:12px; text-decoration:none; border:1px solid var(--border); padding:5px 10px; border-radius:3px; white-space:nowrap; }
  .navlink:hover { color:var(--accent); border-color:var(--accent-dim); }
  .notice { background:var(--surface); border:1px solid var(--border); border-left:2px solid var(--accent-dim); padding:10px 12px; margin:14px 0 18px; color:var(--muted); font-size:11.5px; }
  .notice b { color:var(--text); font-weight:400; }
  .controls { display:flex; flex-wrap:wrap; gap:10px; align-items:center; margin-bottom:14px; }
  .grp { display:flex; align-items:center; gap:6px; }
  .grp .lbl { color:var(--muted); font-size:11.5px; }
  .seg { display:flex; border:1px solid var(--border); border-radius:3px; overflow:hidden; }
  .seg button { background:transparent; border:none; color:var(--muted); font-family:inherit; font-size:12px; padding:6px 10px; cursor:pointer; border-right:1px solid var(--border); }
  .seg button:last-child { border-right:none; }
  .seg button.active { background:var(--accent); color:#12100b; }
  input[type=text], input[type=number], select {
    background: var(--surface); border:1px solid var(--border); color:var(--text);
    font-family:inherit; font-size:12.5px; padding:7px 10px; border-radius:3px;
  }
  #search { flex: 1; min-width: 160px; padding-right: 26px; }
  .count { color:var(--muted); font-size:11.5px; margin-bottom:10px; }
  .cat-block { margin-bottom:14px; }
  .cat-header { display:flex; align-items:center; gap:10px; margin-bottom:6px; }
  .cat-toggle { color:var(--accent); font-size:11px; cursor:pointer; text-decoration:underline; }
  .cats { display:flex; flex-wrap:wrap; gap:4px 10px; }
  .cat-item { display:flex; align-items:center; gap:4px; font-size:11.5px; color:var(--muted); }
  .cat-item input { accent-color: var(--accent); }
  .cat-item.checked { color:var(--text); }
  table { width:100%; border-collapse:collapse; }
  thead th { text-align:left; color:var(--muted); font-weight:400; font-size:11px; border-bottom:1px solid var(--border); padding:6px 8px; }
  tbody td { padding:7px 8px; border-bottom:1px solid var(--border); vertical-align:middle; }
  tbody tr:hover { background: var(--surface); }
  tbody tr.row-highlighted { background: var(--accent-dim); }
  .name a { color:var(--text); text-decoration:none; }
  .name a:hover { color:var(--accent); }
  .name-cell { display:flex; align-items:center; gap:8px; }
  .item-thumb { width:36px; height:27px; object-fit:contain; background:var(--surface); border-radius:3px; flex-shrink:0; }
  .search-wrap { position:relative; flex:1; min-width:160px; display:flex; }
  .search-wrap input { flex:1; }
  .search-clear {
    position:absolute; right:6px; top:50%; transform:translateY(-50%);
    color:var(--muted); cursor:pointer; font-size:13px; line-height:1; padding:2px 4px;
    display:none;
  }
  .search-clear:hover { color:var(--accent); }
  .search-clear.show { display:block; }
  .num { text-align:right; white-space:nowrap; }
  .diff { font-weight:600; }
  .diff.neg { color:var(--negative); }
  .diff.pos { color:var(--positive); }
  .med-val { cursor:pointer; border-bottom:1px dashed var(--muted); }
  .med-val.overridden { color:var(--accent); border-bottom-color: var(--accent); }
  .med-raw { color:var(--muted); font-size:10.5px; margin-left:4px; }
  .median-badge { color:var(--accent); font-size:11px; margin-left:3px; cursor:help; }
  .alert-badge { color:var(--accent); font-size:11.5px; margin-right:5px; cursor:help; }
  .drop-badge { color:var(--accent); font-size:11.5px; margin-right:5px; cursor:help; }
  .detail-link { color:var(--muted); text-decoration:none; font-size:14px; margin-right:6px; }
  .detail-link:hover { color:var(--accent); }
  .med-input { width:70px; background:var(--surface); border:1px solid var(--accent-dim); color:var(--text); font-family:inherit; font-size:12.5px; padding:3px 5px; border-radius:3px; }
  .reset-med { color:var(--muted); font-size:10px; cursor:pointer; margin-left:4px; }
  .skin-link { color:var(--muted); text-decoration:none; font-size:14px; }
  .skin-link:hover { color:var(--accent); }
  .reset-btn { background:var(--surface); border:1px solid var(--border); color:var(--muted); font-family:inherit; font-size:11.5px; padding:7px 10px; border-radius:3px; cursor:pointer; white-space:nowrap; }
  .reset-btn:hover { color:var(--accent); border-color:var(--accent-dim); }
  #more { display:block; margin:18px auto 0; background:var(--surface); border:1px solid var(--border); color:var(--text); font-family:inherit; padding:9px 18px; border-radius:3px; cursor:pointer; }
  footer { margin-top:26px; color:var(--muted); font-size:11px; border-top:1px solid var(--border); padding-top:12px; }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div>
      <div class="wordmark">skin<span>gap</span></div>
      <div class="tagline">écart prix de vente espéré vs prix d'achat (Min tradable ou offre trade-protect) — __COUNT__ items — généré le __DATE__</div>
    </div>
    <a class="navlink" id="dashLink" href="__DASHBOARD_URL__">→ dashboard</a>
  </header>

  <div class="notice">
    <b>Lien Skinport :</b> chaque lien pointe vers la page marché de l'item, trié par prix croissant
    (<code>sort=price&order=asc</code>) et filtré sur le trade-protect ≤ 8 jours (<code>lock=8</code>), avec
    l'usure (<code>exterior=</code>) et StatTrak™/Souvenir détectés automatiquement depuis le nom.<br>
    <b>Prix de vente espéré :</b> calculé selon le mode choisi dans "Mode prix" (l'extension doit lire le
    champ <code>xf</code> de skins.json pour avoir le même "Très rapide") —
    <b>Très rapide</b> (par défaut) : part des <i>dernières ventes</i> (prix typique pondéré par récence et
    nombre de ventes par jour) et prend le prix le plus haut qui laisse ~80 % de chances de vendre sous
    48 h, d'après une calibration sur une journée entière de ventes réelles ; si c'est hors d'atteinte
    (item peu liquide), le prix qui donne la meilleure chance, avec sa probabilité. Si une annonce tradable
    concurrente est moins chère, on passe 1 centime dessous, sinon elle se vend avant : mesuré sur 24 h,
    quand le Min est sous les ventes passées, les ventes du lendemain se font au niveau du Min, pas des
    ventes passées. Le concurrent est la <i>2e</i> annonce tradable si l'on achète le Min (connue jusqu'à
    3 annonces ; au-delà, prudence : plafond sous le Min), le Min tradable si l'on achète une offre
    trade-protect (détail au survol du prix) ;
    <b>Auto</b> : prix min listé, sauf si la médiane
    pondérée lui est <i>inférieure</i> (affiché avec <span style="color:var(--accent)">Ⓜ</span>) ;
    <b>Médiane</b> : toujours la médiane pondérée (prix min listé si aucune vente) ; <b>Min listé</b> :
    toujours le prix min listé. Le prix net après commission Skinport (8% si &lt;1000€, 6% si ≥1000€) est
    celui utilisé pour l'écart ; le prix brut est affiché entre parenthèses, à titre indicatif.
    <b>Écart</b> = prix de vente net − prix d'achat, choisi dans "Achat" : <b>Min tradable</b> (par défaut :
    annonce hors trade-protect, achetable maintenant) ou <b>Min (TP)</b> (offre la moins chère, trade-protect
    compris : achetable tout de suite, mais l'item n'arrive qu'à la fin de sa protection ; ces offres se
    vendent ~8 % moins cher que les tradables, d'où les deux niveaux de prix dans l'historique des ventes).
    La colonne <b>Min (TP)</b> est surlignée quand une offre
    trade-protect est moins chère que le Min tradable.
  </div>

  <div class="controls">
    <div class="grp">
      <span class="lbl">Volume :</span>
      <div class="seg" id="volPeriod">
        <button data-p="24">24h</button>
        <button data-p="7" class="active">7j</button>
        <button data-p="30">30j</button>
        <button data-p="90">90j</button>
      </div>
    </div>
    <div class="grp">
      <span class="lbl">Vol. min :</span>
      <input type="number" id="minVol" min="0" step="1" value="0" style="width:56px">
    </div>
    <div class="grp">
      <span class="lbl">Prix d'achat :</span>
      <input type="number" id="priceMin" min="0" step="0.01" placeholder="min" style="width:64px">
      <span class="lbl">–</span>
      <input type="number" id="priceMax" min="0" step="0.01" placeholder="max" style="width:64px">
    </div>
    <div class="grp">
      <span class="lbl">Écart € :</span>
      <input type="number" id="gapMin" step="0.01" placeholder="min" style="width:64px">
      <span class="lbl">–</span>
      <input type="number" id="gapMax" step="0.01" placeholder="max" style="width:64px">
    </div>
    <div class="grp">
      <span class="lbl">ROI % :</span>
      <input type="number" id="roiMin" step="0.1" placeholder="min" style="width:56px">
      <span class="lbl">–</span>
      <input type="number" id="roiMax" step="0.1" placeholder="max" style="width:56px">
    </div>
    <div class="grp" title="Mode Très rapide : probabilité estimée de vente sous 48 h au prix espéré">
      <span class="lbl">Proba 48h ≥ % :</span>
      <input type="number" id="probMin" min="0" max="100" step="5" placeholder="0" style="width:56px">
    </div>
    <div class="grp">
      <span class="lbl">Type :</span>
      <select id="typeSel">
        <option value="all">Tous</option>
        <option value="normal">Normal</option>
        <option value="st">StatTrak™</option>
        <option value="souvenir">Souvenir</option>
      </select>
    </div>
    <div class="grp">
      <span class="lbl">Mode prix :</span>
      <select id="priceModeSel">
        <option value="veryfast">Très rapide</option>
        <option value="auto">Auto (min, sauf si médiane plus basse)</option>
        <option value="median">Médiane des ventes</option>
        <option value="min">Min listé</option>
      </select>
    </div>
    <div class="grp" title="Prix d'achat utilisé pour l'écart et le ROI">
      <span class="lbl">Achat :</span>
      <select id="buySel">
        <option value="ask">Min tradable (achetable maintenant)</option>
        <option value="tp">Min (TP) : offre trade-protect (livrée en fin de protection)</option>
      </select>
    </div>
    <div class="grp">
      <label class="lbl" style="display:flex; align-items:center; gap:5px; cursor:pointer;">
        <input type="checkbox" id="hideAlerted" style="accent-color:var(--accent)">
        Masquer déjà alertés
      </label>
    </div>
    <div class="grp">
      <span class="lbl">Nouveau prix bas (24h) :</span>
      <select id="newLowSel">
        <option value="all">Tous</option>
        <option value="only">Seulement</option>
        <option value="hide">Masquer</option>
      </select>
    </div>
    <div class="grp">
      <span class="lbl">Trier :</span>
      <select id="sortSel">
        <option value="gap_desc">Écart (haut → bas)</option>
        <option value="gap_asc">Écart (bas → haut)</option>
        <option value="roi_desc">ROI % (haut → bas)</option>
        <option value="roi_asc">ROI % (bas → haut)</option>
        <option value="min_asc">Prix d'achat (bas → haut)</option>
        <option value="min_desc">Prix d'achat (haut → bas)</option>
        <option value="exp_asc">Prix espéré (bas → haut)</option>
        <option value="exp_desc">Prix espéré (haut → bas)</option>
        <option value="vol_desc">Volume (haut → bas)</option>
        <option value="vol_asc">Volume (bas → haut)</option>
        <option value="stock_asc">Jours de stock (bas → haut)</option>
        <option value="name_asc">Nom (A-Z)</option>
        <option value="name_desc">Nom (Z-A)</option>
        <option value="cat_asc">Catégorie (A-Z)</option>
      </select>
    </div>
    <div class="search-wrap">
      <input type="text" id="search" placeholder="Filtrer par nom..." list="itemNamesList" autocomplete="off">
      <datalist id="itemNamesList"></datalist>
      <span class="search-clear" id="searchClear" title="Effacer le filtre">✕</span>
    </div>
    <button type="button" id="resetFilters" class="reset-btn">↺ Réinitialiser les filtres</button>
  </div>

  <div class="cat-block">
    <div class="cat-header">
      <span class="lbl">Catégories :</span>
      <span class="cat-toggle" id="catAll">Tout</span>
      <span class="cat-toggle" id="catNone">Aucun</span>
    </div>
    <div class="cats" id="catList"></div>
  </div>

  <div class="count" id="count"></div>

  <table>
    <thead>
      <tr>
        <th>Item</th>
        <th class="num">Prix espéré</th>
        <th class="num">Volume</th>
        <th class="num" title="Annonces immédiatement tradables (et, au survol, nombre de jours de ventes que ce stock représente au rythme actuel)">Stock</th>
        <th class="num">Min</th>
        <th class="num" title="Prix min avec trade-protect : indicatif, hors calculs">Min (TP)</th>
        <th class="num">Écart</th>
        <th class="num">ROI %</th>
        <th></th>
      </tr>
    </thead>
    <tbody id="rows"></tbody>
  </table>
  <button id="more">Afficher plus</button>

  <footer>
    Prix éditée manuellement = reste appliqué (affiché en orange, cliquable pour réinitialiser).
  </footer>
</div>

<script>
const DATA = __DATA__;
// Capturé pour comparaison au retour d'onglet (voir visibilitychange plus bas) — pas besoin
// d'attendre que ça réponde pour afficher la page, ça arrive en tâche de fond.
let PAGE_LAST_MODIFIED = null;
fetch(location.pathname, { method: "HEAD", cache: "no-store" })
  .then(r => { PAGE_LAST_MODIFIED = r.headers.get("Last-Modified"); })
  .catch(() => {});
// Injecté par le workflow (secret DASHBOARD_URL) : ne JAMAIS remettre l'URL en dur ici, le
// dépôt est public et l'URL contient le segment secret qui protège le dashboard.
// Normalisé avec un "/" final, quelle que soit la forme du secret.
const DASHBOARD_URL = "__DASHBOARD_URL__".replace(/\\/?$/, "/");
const ALERTS_API = DASHBOARD_URL + "api/alerts";
const PRICEDROPS_API = DASHBOARD_URL + "api/pricedrops";
const EVENTS_URL = DASHBOARD_URL + "events";
const overrides = {}; // n -> valeur manuelle du prix de vente espéré (brut, avant taxe)
let volPeriod = "7";
let priceMode = "veryfast"; // "veryfast" | "auto" | "median" | "min"  (mêmes modes que l'extension)
let buyMode = "ask"; // "ask" : achat au Min tradable | "tp" : achat de l'offre la moins chère, trade-protect compris
let sortMode = "gap_desc";
let searchTerm = "";
let minVol = 0;
let priceMin = 0, priceMax = Infinity;
let roiMin = -Infinity, roiMax = Infinity;
let probMin = 0; // en %, filtre sur "xp" (proba de vente sous 48 h), mode Très rapide uniquement
let typeFilter = "all";
let gapMin = -Infinity, gapMax = Infinity;
let hideAlerted = false;
let newLowFilter = "all"; // "all" | "only" | "hide" — filtre sur le badge "nouveau prix bas (24h)"
let alertedSkins = new Set(); // noms (minuscule) déjà présents dans les alertes du dashboard
let priceDrops = new Map(); // nom (minuscule) -> dropped_at_ms, skins avec un nouveau prix bas actif (24h)
let selectedCats = null; // Set<string> — rempli au chargement avec toutes les catégories trouvées
let shown = 50;
const PAGE = 50;

// Filtres persistés en localStorage (même origine que le dashboard) pour ne pas les
// perdre en naviguant entre skingap et le dashboard, ou en rafraîchissant la page.
const FILTERS_KEY = "skingap_filters_v1";

function serializeFilters() {
  return {
    volPeriod, priceMode, buyMode, sortMode, searchTerm, minVol,
    priceMin: isFinite(priceMin) ? priceMin : null,
    priceMax: isFinite(priceMax) ? priceMax : null,
    roiMin: isFinite(roiMin) ? roiMin : null,
    roiMax: isFinite(roiMax) ? roiMax : null,
    probMin,
    gapMin: isFinite(gapMin) ? gapMin : null,
    gapMax: isFinite(gapMax) ? gapMax : null,
    typeFilter, hideAlerted, newLowFilter,
    selectedCats: selectedCats ? [...selectedCats] : null
  };
}

function saveFilters() {
  try { localStorage.setItem(FILTERS_KEY, JSON.stringify(serializeFilters())); } catch (e) {}
}

function loadFilters() {
  try {
    const raw = localStorage.getItem(FILTERS_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch (e) { return null; }
}

function applySavedFilters(f) {
  if (f.volPeriod !== undefined) volPeriod = f.volPeriod;
  if (f.priceMode !== undefined) priceMode = ["veryfast", "auto", "median", "min"].includes(f.priceMode) ? f.priceMode : "veryfast";
  if (f.buyMode !== undefined) buyMode = ["ask", "tp"].includes(f.buyMode) ? f.buyMode : "ask";
  if (f.sortMode !== undefined) sortMode = f.sortMode;
  if (f.searchTerm !== undefined) searchTerm = f.searchTerm;
  if (f.minVol !== undefined) minVol = f.minVol;
  priceMin = (f.priceMin === null || f.priceMin === undefined) ? 0 : f.priceMin;
  priceMax = (f.priceMax === null || f.priceMax === undefined) ? Infinity : f.priceMax;
  roiMin = (f.roiMin === null || f.roiMin === undefined) ? -Infinity : f.roiMin;
  roiMax = (f.roiMax === null || f.roiMax === undefined) ? Infinity : f.roiMax;
  probMin = f.probMin || 0;
  gapMin = (f.gapMin === null || f.gapMin === undefined) ? -Infinity : f.gapMin;
  gapMax = (f.gapMax === null || f.gapMax === undefined) ? Infinity : f.gapMax;
  if (f.typeFilter !== undefined) typeFilter = f.typeFilter;
  if (f.hideAlerted !== undefined) hideAlerted = f.hideAlerted;
  if (f.newLowFilter !== undefined) newLowFilter = f.newLowFilter;

  document.querySelectorAll("#volPeriod button").forEach(b => b.classList.toggle("active", b.dataset.p === volPeriod));
  document.getElementById("sortSel").value = sortMode;
  document.getElementById("priceModeSel").value = priceMode;
  document.getElementById("buySel").value = buyMode;
  document.getElementById("search").value = searchTerm;
  document.getElementById("minVol").value = minVol || 0;
  document.getElementById("priceMin").value = f.priceMin ?? "";
  document.getElementById("priceMax").value = f.priceMax ?? "";
  document.getElementById("roiMin").value = f.roiMin ?? "";
  document.getElementById("roiMax").value = f.roiMax ?? "";
  document.getElementById("probMin").value = probMin || "";
  document.getElementById("gapMin").value = f.gapMin ?? "";
  document.getElementById("gapMax").value = f.gapMax ?? "";
  document.getElementById("typeSel").value = typeFilter;
  document.getElementById("hideAlerted").checked = hideAlerted;
  document.getElementById("newLowSel").value = newLowFilter;
  updateSearchClearVisibility();
}

// Valeurs par défaut, utilisées par le bouton "Réinitialiser les filtres".
const FILTER_DEFAULTS = {
  volPeriod: "7", priceMode: "veryfast", buyMode: "ask", sortMode: "gap_desc", searchTerm: "",
  minVol: 0, priceMin: 0, priceMax: Infinity, roiMin: -Infinity, roiMax: Infinity,
  typeFilter: "all", gapMin: -Infinity, gapMax: Infinity, newLowFilter: "all"
};

function itemType(name) {
  const l = name.toLowerCase();
  if (l.includes("stattrak")) return "st";
  if (l.startsWith("souvenir")) return "souvenir";
  return "normal";
}

// Catégorie Skinport (Rifle, Knife, Gloves, Sticker, Agent, Container, Music Kit, ...) :
// déjà présente dans le paramètre "cat" de l'URL de la page marché ("p"), fournie par
// l'API Skinport elle-même — pas besoin de la deviner depuis le nom.
function catOf(it) {
  try {
    const qs = it.p.split("?")[1] || "";
    const params = new URLSearchParams(qs);
    return params.get("cat") || "Autre";
  } catch {
    return "Autre";
  }
}

function volumeOf(item, p) {
  return item["v" + p];
}
// Taxe Skinport : 8% sous 1000€, 6% à partir de 1000€, prélevée au vendeur sur le prix de vente
// (l'acheteur ne paie aucune commission — donc on soustrait, on n'ajoute pas).
function taxRate(rawPrice) {
  return rawPrice < 1000 ? 0.08 : 0.06;
}
function withTax(rawPrice) {
  return rawPrice * (1 - taxRate(rawPrice));
}

const EXTERIOR_CODE = {
  "factory new": 2,
  "minimal wear": 4,
  "field-tested": 3,
  "field tested": 3,
  "well-worn": 5,
  "well worn": 5,
  "battle-scarred": 1,
  "battle scarred": 1
};

function buildLink(it) {
  let url = it.p + (it.p.includes("?") ? "&" : "?") + "sort=price&order=asc&lock=8";
  const name = it.n;
  const nameLower = name.toLowerCase();
  for (const [label, code] of Object.entries(EXTERIOR_CODE)) {
    if (nameLower.includes(label)) { url += "&exterior=" + code; break; }
  }
  // Toujours explicite (0 ou 1), jamais omis : certaines armes sont moins chères en
  // StatTrak™ que sans, et Skinport inclut ces annonces par défaut si le paramètre
  // est absent, ce qui fausse le tri par prix.
  // Attention : le paramètre Skinport est "stattrak" (sans le c de "stattrack",
  // erreur qui faisait que ce filtre n'avait jamais aucun effet).
  url += "&stattrak=" + (nameLower.includes("stattrak") ? 1 : 0);
  url += "&souvenir=" + (nameLower.startsWith("souvenir") ? 1 : 0);
  // Toujours explicites à 0 aussi : sans quoi Skinport peut inclure des annonces avec
  // stickers/charms/nametag ou des skins vanilla dans le tri par prix.
  url += "&stickers=0&charms=0&nametag=0&vanilla=0";
  return url;
}

// Prix de vente espéré BRUT :
// - "veryfast" : prix "xf" calculé par FastSellModel (SkinGap.java) — le plus haut prix qui donne ~80 %
//                de chances de vente sous 48 h d'après le rythme de ventes (λ) et la distribution des prix
//                payés récemment, plafonné 1 centime sous le min tradable. Ancien calcul (médiane pondérée
//                −2 %, −15 % si < 0,5 vente/jour, plafonné à 0,1 % sous le min) seulement si "xf" manque.
// - "auto"     : prix min listé (ask), sauf si la médiane pondérée lui est inférieure.
// - "median"   : médiane pondérée, à défaut le prix min listé.
// - "min"      : toujours le prix min listé.
// Une valeur éditée manuellement (override) remplace tout le reste.
// Retourne { raw, usedMedian } pour piloter aussi le badge Ⓜ à l'affichage.
function dailyRate(it) {
  if (it.v24 > 0) return it.v24;
  if (it.v7 > 0) return it.v7 / 7;
  if (it.v30 > 0) return it.v30 / 30;
  if (it.v90 > 0) return it.v90 / 90;
  return 0;
}

// Nombre de jours de ventes que représente le stock tradable actuel ; null si pas de ventes.
function stockDays(it) {
  const dr = (it.xl !== undefined && it.xl > 0) ? it.xl : dailyRate(it);
  if (it.qt === undefined || dr <= 0) return null;
  return it.qt / dr;
}

// Prix d'achat : Min tradable (achetable tout de suite) ou offre la moins chère trade-protect compris
// (it.min <= it.ask ; égaux quand aucune offre trade-protect n'est moins chère que le Min tradable).
function hasCheaperTp(it) { return it.min > 0 && it.min < it.ask - 1e-9; }
function buyPrice(it) { return (buyMode === "tp" && hasCheaperTp(it)) ? it.min : it.ask; }

// Revente "Très rapide" (calculée côté Java) adaptée à ce qu'on achète. On ne peut pas se servir de
// l'annonce qu'on achète comme concurrente de sa propre revente :
//  - offre trade-protect sous le Min tradable : elle n'est pas dans le marché tradable, le concurrent
//    reste le Min tradable -> "xf" (proba "xp") ;
//  - Min tradable : une fois acheté, le concurrent est la 2e annonce tradable -> "xb" (proba "xbp"),
//    présent quand l'API permet de la connaître (<= 3 annonces), sinon "xf" (plafond sous le Min, prudent).
// Retourne null si skins.json ne contient pas le prix v2.
function fastOf(it) {
  if (!(it.xf > 0)) return null;
  let price, prob, comp, compLabel;
  if (buyMode === "tp" && hasCheaperTp(it)) {
    price = it.xf; prob = it.xp || 0; comp = it.ask; compLabel = "le Min tradable";
  } else if (it.xb !== undefined && it.xb > 0) {
    price = it.xb; prob = it.xbp || 0;
    if (it.l2 !== undefined) { comp = it.l2; compLabel = "la 2e annonce tradable (le Min est celle qu'on achète)"; }
    else { comp = null; compLabel = "aucune autre annonce tradable"; }
  } else {
    price = it.xf; prob = it.xp || 0; comp = it.ask;
    compLabel = "le Min tradable (2e annonce inconnue au-delà de 3 annonces : prudent)";
  }
  const capped = comp !== null && price >= comp - 0.0101;
  const base = it.xs > 0 ? "prix typique des dernières ventes ≈ " + it.xs.toFixed(2) + " € · " : "";
  const why = base + (capped
    ? "plafonné 1 ct sous " + compLabel + " (" + comp.toFixed(2) + " €), sinon elle se vend avant"
    : comp === null ? compLabel + " : prix tiré des ventes"
      : "prix tiré des ventes, déjà sous " + compLabel + " (" + comp.toFixed(2) + " €)");
  return { price, prob, why };
}

function rawExpectedOf(it) {
  if (overrides[it.n] !== undefined) return { raw: overrides[it.n], usedMedian: false };
  const hasWm = it.wm !== null && it.wm !== undefined && it.wm > 0;
  if (priceMode === "median") {
    return hasWm ? { raw: it.wm, usedMedian: true } : { raw: it.ask, usedMedian: false };
  }
  if (priceMode === "min") {
    return { raw: it.ask, usedMedian: false };
  }
  if (priceMode === "veryfast") {
    // v2 (nov. 2026) : prix calculé côté Java par FastSellModel (vente visée sous 24-48 h),
    // exporté dans skins.json ("xf"). Repli sur l'ancien calcul si le champ est absent.
    const f = fastOf(it);
    if (f) return { raw: f.price, usedMedian: false };
    const base = hasWm ? it.wm : it.ask;
    let raw = base * (1 - 0.02);
    if (dailyRate(it) < 0.5) raw *= 0.85;
    const cap = it.ask * 0.999;
    const usedMedian = hasWm && raw < cap;
    raw = Math.min(raw, cap);
    raw = Math.max(0.01, Math.round(raw * 100) / 100);
    return { raw: raw, usedMedian: usedMedian };
  }
  // auto
  if (hasWm && it.wm < it.ask) return { raw: it.wm, usedMedian: true };
  return { raw: it.ask, usedMedian: false };
}

function computeRows() {
  let rows = DATA.filter(it => {
    if (searchTerm && !it.n.toLowerCase().includes(searchTerm)) return false;
    const exp = rawExpectedOf(it);
    if (exp.raw === null || exp.raw === undefined) return false;
    const vol = volumeOf(it, volPeriod);
    if ((vol ?? 0) < minVol) return false;
    const buy = buyPrice(it);
    if (buy < priceMin || buy > priceMax) return false;
    if (probMin > 0 && priceMode === "veryfast") {
      const f = fastOf(it);
      if (f && f.prob * 100 < probMin) return false;
    }
    if (typeFilter !== "all" && itemType(it.n) !== typeFilter) return false;
    if (selectedCats && !selectedCats.has(catOf(it))) return false;
    if (hideAlerted && alertedSkins.has(it.n.toLowerCase())) return false;
    if (newLowFilter === "only" && !priceDrops.has(it.n.toLowerCase())) return false;
    if (newLowFilter === "hide" && priceDrops.has(it.n.toLowerCase())) return false;
    return true;
  });
  rows = rows.map(it => {
    const exp = rawExpectedOf(it);
    const med = withTax(exp.raw);
    const buy = buyPrice(it);
    const gap = med - buy;
    const roi = buy > 0 ? (gap / buy) * 100 : 0;
    return { it, rawMed: exp.raw, usedMedian: exp.usedMedian, med, gap, roi, vol: volumeOf(it, volPeriod) };
  }).filter(r => r.roi >= roiMin && r.roi <= roiMax && r.gap >= gapMin && r.gap <= gapMax);
  switch (sortMode) {
    case "gap_desc": rows.sort((a,b) => b.gap - a.gap); break;
    case "gap_asc": rows.sort((a,b) => a.gap - b.gap); break;
    case "roi_desc": rows.sort((a,b) => b.roi - a.roi); break;
    case "roi_asc": rows.sort((a,b) => a.roi - b.roi); break;
    case "min_asc": rows.sort((a,b) => buyPrice(a.it) - buyPrice(b.it)); break;
    case "min_desc": rows.sort((a,b) => buyPrice(b.it) - buyPrice(a.it)); break;
    case "exp_asc": rows.sort((a,b) => a.med - b.med); break;
    case "exp_desc": rows.sort((a,b) => b.med - a.med); break;
    case "vol_desc": rows.sort((a,b) => (b.vol ?? 0) - (a.vol ?? 0)); break;
    case "vol_asc": rows.sort((a,b) => (a.vol ?? 0) - (b.vol ?? 0)); break;
    case "stock_asc": rows.sort((a,b) => (stockDays(a.it) ?? Infinity) - (stockDays(b.it) ?? Infinity)); break;
    case "name_desc": rows.sort((a,b) => b.it.n.localeCompare(a.it.n)); break;
    case "cat_asc": rows.sort((a,b) => catOf(a.it).localeCompare(catOf(b.it)) || a.it.n.localeCompare(b.it.n)); break;
    default: rows.sort((a,b) => a.it.n.localeCompare(b.it.n)); // name_asc
  }
  return rows;
}

function render() {
  const rows = computeRows();
  document.getElementById("count").textContent = rows.length + " item(s) correspondant(s)";
  const tbody = document.getElementById("rows");
  tbody.innerHTML = "";
  const slice = rows.slice(0, shown);
  for (const r of slice) {
    const tr = document.createElement("tr");
    const isOverride = overrides[r.it.n] !== undefined;
    const gapClass = r.gap >= 0 ? "pos" : "neg";
    const link = buildLink(r.it);
    tr.dataset.name = r.it.n;
    if (r.it.n === highlightedName) tr.classList.add("row-highlighted");

    const tdName = document.createElement("td");
    tdName.className = "name";
    const nameCell = document.createElement("div");
    nameCell.className = "name-cell";
    if (r.it.i) {
      const thumb = document.createElement("img");
      thumb.className = "item-thumb";
      thumb.src = r.it.i; thumb.alt = ""; thumb.loading = "lazy"; thumb.decoding = "async";
      nameCell.appendChild(thumb);
    }
    const a = document.createElement("a");
    a.href = link; a.target = "_blank"; a.rel = "noopener";
    a.textContent = r.it.n;
    nameCell.appendChild(a);
    tdName.appendChild(nameCell);
    tr.appendChild(tdName);

    const tdMed = document.createElement("td");
    tdMed.className = "num";
    if (alertedSkins.has(r.it.n.toLowerCase())) {
      const alertBadge = document.createElement("span");
      alertBadge.className = "alert-badge";
      alertBadge.textContent = "⏱";
      alertBadge.title = "Déjà présent dans les alertes du dashboard";
      tdMed.appendChild(alertBadge);
    }
    if (priceDrops.has(r.it.n.toLowerCase())) {
      const dropBadge = document.createElement("span");
      dropBadge.className = "drop-badge";
      dropBadge.textContent = "🔻";
      dropBadge.title = "Nouveau prix bas (min TP en baisse par rapport au push précédent, dans les 24 dernières heures)";
      tdMed.appendChild(dropBadge);
    }
    const span = document.createElement("span");
    span.className = "med-val" + (isOverride ? " overridden" : "");
    span.textContent = r.med.toFixed(2) + " €";
    span.title = "Cliquer pour modifier manuellement (valeur brute, sans taxe)";
    const fast = (priceMode === "veryfast" && !isOverride) ? fastOf(r.it) : null;
    if (fast) {
      span.title = "≈ " + Math.round(fast.prob * 100) + " % de chances de vente sous 48 h à ce prix · "
        + (r.it.xl || 0).toFixed(2) + " vente(s)/jour estimée(s) · " + fast.why + " — " + span.title;
      if (fast.prob < 0.5) span.style.opacity = "0.6"; // vente sous 48 h peu probable même à ce prix
    }
    span.onclick = () => startEdit(r.it.n, r.rawMed, tdMed);
    tdMed.appendChild(span);
    if (!isOverride && r.usedMedian) {
      const badge = document.createElement("span");
      badge.className = "median-badge";
      badge.textContent = "Ⓜ";
      badge.title = "Médiane pondérée des ventes utilisée (mode Auto : prix demandé plus haut que ce que le marché paie réellement — ou mode Médiane)";
      tdMed.appendChild(badge);
    }
    const raw = document.createElement("span");
    raw.className = "med-raw";
    raw.textContent = "(" + r.rawMed.toFixed(2) + " €)";
    tdMed.appendChild(raw);
    if (isOverride) {
      const reset = document.createElement("span");
      reset.className = "reset-med";
      reset.textContent = "✕";
      reset.title = "Revenir au prix calculé";
      reset.onclick = (e) => { e.stopPropagation(); delete overrides[r.it.n]; render(); };
      tdMed.appendChild(reset);
    }
    tr.appendChild(tdMed);

    const tdVol = document.createElement("td");
    tdVol.className = "num";
    tdVol.textContent = (r.vol ?? "-");
    tr.appendChild(tdVol);

    // Stock tradable + "jours de stock" (stock / ventes par jour) : plus c'est bas, plus la
    // revente sera rapide (peu de concurrence pour beaucoup d'acheteurs). Absent des anciens
    // skins.json -> "-".
    const tdStock = document.createElement("td");
    tdStock.className = "num";
    if (r.it.qt !== undefined) {
      tdStock.textContent = r.it.qt;
      const dr = dailyRate(r.it);
      const days = stockDays(r.it);
      if (days !== null) {
        tdStock.title = days.toFixed(1) + " jour(s) de ventes au rythme actuel (" + dr.toFixed(2) + " vente/j)"
          + (r.it.qg > r.it.qt ? " · " + r.it.qg + " annonces au total, trade-lock inclus" : "");
        if (days > 30) tdStock.style.color = "var(--negative)";
        else if (days < 3) tdStock.style.color = "var(--positive)";
      } else {
        tdStock.title = "Aucune vente sur 90 jours";
      }
    } else {
      tdStock.textContent = "-";
    }
    tr.appendChild(tdStock);

    const tdAsk = document.createElement("td");
    tdAsk.className = "num";
    tdAsk.textContent = r.it.ask.toFixed(2) + " €";
    tr.appendChild(tdAsk);

    // Min avec trade-protect : prix d'achat en mode "Achat : Min (TP)" ; surligné quand une offre
    // trade-protect est moins chère que le Min tradable.
    const tdMin = document.createElement("td");
    tdMin.className = "num";
    if (hasCheaperTp(r.it)) {
      tdMin.style.color = "var(--accent)";
      tdMin.title = "Offre trade-protect " + ((1 - r.it.min / r.it.ask) * 100).toFixed(1)
        + " % sous le Min tradable (achetable maintenant, livrée à la fin de la protection)";
    } else {
      tdMin.style.opacity = "0.55";
      tdMin.title = "Aucune offre trade-protect sous le Min tradable";
    }
    tdMin.textContent = r.it.min.toFixed(2) + " €";
    tr.appendChild(tdMin);

    const tdGap = document.createElement("td");
    tdGap.className = "num diff " + gapClass;
    tdGap.textContent = (r.gap >= 0 ? "+" : "") + r.gap.toFixed(2) + " €";
    tr.appendChild(tdGap);

    const tdRoi = document.createElement("td");
    tdRoi.className = "num diff " + gapClass;
    tdRoi.textContent = (r.roi >= 0 ? "+" : "") + r.roi.toFixed(1) + " %";
    tr.appendChild(tdRoi);
    // Très rapide : un écart n'a de sens que si la revente sous 48 h est probable. Items peu
    // liquides (souvent une seule annonce tradable) : écart grisé, filtre "Proba 48h" pour les masquer.
    if (fast && fast.prob < 0.5) {
      for (const td of [tdGap, tdRoi]) {
        td.style.opacity = "0.45";
        td.title = "Vente sous 48 h peu probable (≈ " + Math.round(fast.prob * 100) + " %) : écart incertain";
      }
    }

    const tdLink = document.createElement("td");
    const detailLink = document.createElement("a");
    detailLink.href = DASHBOARD_URL + "skin?name=" + encodeURIComponent(r.it.n);
    detailLink.className = "detail-link"; detailLink.textContent = "ⓘ"; detailLink.title = "Voir le détail de ce skin";
    tdLink.appendChild(detailLink);
    const a2 = document.createElement("a");
    a2.href = link; a2.target = "_blank"; a2.rel = "noopener";
    a2.className = "skin-link"; a2.textContent = "↗";
    tdLink.appendChild(a2);
    tr.appendChild(tdLink);

    tbody.appendChild(tr);
  }
  document.getElementById("more").style.display = rows.length > shown ? "block" : "none";
  // Un item demandé via ?highlight=... peut être hors de la page affichée (shown) si la
  // liste est longue : on affiche plus de lignes jusqu'à le trouver, avant de le surligner.
  applyPendingHighlight();
}

// Surbrillance de la ligne d'un item — au clic dessus (comme le survol) ou à l'ouverture
// d'un lien vers ?highlight=Nom (ex: depuis une notif). Un clic ailleurs sur la page l'enlève.
let pendingHighlightName = new URLSearchParams(location.search).get("highlight");
let highlightedName = null; // conservé entre les render() (tri, filtres, "afficher plus"…) —
                             // seul un clic ailleurs sur la page (voir plus bas) l'efface.

function highlightRow(tr) {
  highlightedName = tr.dataset.name;
  const prev = document.querySelector("#rows tr.row-highlighted");
  if (prev && prev !== tr) prev.classList.remove("row-highlighted");
  tr.classList.add("row-highlighted");
}

function clearHighlight() {
  highlightedName = null;
  const prev = document.querySelector("#rows tr.row-highlighted");
  if (prev) prev.classList.remove("row-highlighted");
}

function applyPendingHighlight() {
  if (!pendingHighlightName) return;
  const tr = [...document.querySelectorAll("#rows tr")].find(r => r.dataset.name === pendingHighlightName);
  if (!tr) {
    // Pas encore affiché : on affiche plus de lignes (comme "Afficher plus") et on réessaie.
    const rows = computeRows();
    if (shown < rows.length) { shown += PAGE; render(); }
    return;
  }
  highlightRow(tr);
  tr.scrollIntoView({ behavior: "smooth", block: "center" });
  pendingHighlightName = null; // ne réessaie plus après un render ultérieur (filtres, etc.)
}

document.getElementById("rows").addEventListener("click", (e) => {
  const tr = e.target.closest("tr");
  if (!tr) return;
  if (e.target.closest(".detail-link")) return; // navigue dans le même onglet, pas la peine de surligner avant de quitter
  if (e.target.closest(".med-val, .med-input")) return; // l'édition du prix espéré gère déjà son propre focus/scroll
  highlightRow(tr);
});
document.addEventListener("click", (e) => {
  if (e.target.closest("#rows tr")) return;
  clearHighlight();
});

function startEdit(name, rawMed, cell) {
  // la valeur éditée est le prix de vente espéré brut (sans taxe) ; la taxe est réappliquée à l'affichage
  const input = document.createElement("input");
  input.type = "number"; input.step = "0.01";
  input.className = "med-input";
  input.value = rawMed.toFixed(2);
  cell.innerHTML = "";
  cell.appendChild(input);
  input.focus(); input.select();
  function commit() {
    const v = parseFloat(input.value);
    if (!isNaN(v)) overrides[name] = v;
    // Après édition, le tri peut faire descendre l'item plus bas dans la liste (voire sous
    // la pagination "Afficher plus") — on le retrouve et on scrolle dessus pour ne pas le perdre.
    pendingHighlightName = name;
    render();
  }
  input.onblur = commit;
  input.onkeydown = (e) => { if (e.key === "Enter") commit(); };
}

document.getElementById("volPeriod").addEventListener("click", (e) => {
  const b = e.target.closest("button[data-p]"); if (!b) return;
  volPeriod = b.dataset.p;
  document.querySelectorAll("#volPeriod button").forEach(x => x.classList.toggle("active", x===b));
  render(); saveFilters();
});
document.getElementById("sortSel").addEventListener("change", (e) => { sortMode = e.target.value; shown = PAGE; render(); saveFilters(); });
document.getElementById("priceModeSel").addEventListener("change", (e) => { priceMode = e.target.value; shown = PAGE; render(); saveFilters(); });
document.getElementById("buySel").addEventListener("change", (e) => { buyMode = e.target.value; shown = PAGE; render(); saveFilters(); });
function updateSearchClearVisibility() {
  document.getElementById("searchClear").classList.toggle("show", document.getElementById("search").value.length > 0);
}
document.getElementById("search").addEventListener("input", (e) => {
  searchTerm = e.target.value.toLowerCase();
  updateSearchClearVisibility();
  shown = PAGE; render(); saveFilters();
});
document.getElementById("searchClear").addEventListener("click", () => {
  const input = document.getElementById("search");
  input.value = "";
  searchTerm = "";
  updateSearchClearVisibility();
  input.focus();
  shown = PAGE; render(); saveFilters();
});
document.getElementById("minVol").addEventListener("input", (e) => {
  const v = parseInt(e.target.value, 10);
  minVol = isNaN(v) ? 0 : v;
  shown = PAGE; render(); saveFilters();
});
document.getElementById("priceMin").addEventListener("input", (e) => {
  const v = parseFloat(e.target.value);
  priceMin = isNaN(v) ? 0 : v;
  shown = PAGE; render(); saveFilters();
});
document.getElementById("priceMax").addEventListener("input", (e) => {
  const v = parseFloat(e.target.value);
  priceMax = isNaN(v) ? Infinity : v;
  shown = PAGE; render(); saveFilters();
});
document.getElementById("roiMin").addEventListener("input", (e) => {
  const v = parseFloat(e.target.value);
  roiMin = isNaN(v) ? -Infinity : v;
  shown = PAGE; render(); saveFilters();
});
document.getElementById("roiMax").addEventListener("input", (e) => {
  const v = parseFloat(e.target.value);
  roiMax = isNaN(v) ? Infinity : v;
  shown = PAGE; render(); saveFilters();
});
document.getElementById("probMin").addEventListener("input", (e) => {
  const v = parseFloat(e.target.value);
  probMin = isNaN(v) ? 0 : v;
  shown = PAGE; render(); saveFilters();
});
document.getElementById("gapMin").addEventListener("input", (e) => {
  const v = parseFloat(e.target.value);
  gapMin = isNaN(v) ? -Infinity : v;
  shown = PAGE; render(); saveFilters();
});
document.getElementById("gapMax").addEventListener("input", (e) => {
  const v = parseFloat(e.target.value);
  gapMax = isNaN(v) ? Infinity : v;
  shown = PAGE; render(); saveFilters();
});
document.getElementById("typeSel").addEventListener("change", (e) => {
  typeFilter = e.target.value;
  shown = PAGE; render(); saveFilters();
});
document.getElementById("hideAlerted").addEventListener("change", (e) => {
  hideAlerted = e.target.checked;
  shown = PAGE; render(); saveFilters();
});
document.getElementById("newLowSel").addEventListener("change", (e) => {
  newLowFilter = e.target.value;
  shown = PAGE; render(); saveFilters();
});
document.getElementById("more").addEventListener("click", () => { shown += PAGE; render(); });

document.getElementById("resetFilters").addEventListener("click", () => {
  volPeriod = FILTER_DEFAULTS.volPeriod;
  priceMode = FILTER_DEFAULTS.priceMode;
  buyMode = FILTER_DEFAULTS.buyMode;
  sortMode = FILTER_DEFAULTS.sortMode;
  searchTerm = FILTER_DEFAULTS.searchTerm;
  minVol = FILTER_DEFAULTS.minVol;
  priceMin = FILTER_DEFAULTS.priceMin;
  priceMax = FILTER_DEFAULTS.priceMax;
  roiMin = FILTER_DEFAULTS.roiMin;
  roiMax = FILTER_DEFAULTS.roiMax;
  probMin = 0;
  typeFilter = FILTER_DEFAULTS.typeFilter;
  gapMin = FILTER_DEFAULTS.gapMin;
  gapMax = FILTER_DEFAULTS.gapMax;
  hideAlerted = false;
  newLowFilter = FILTER_DEFAULTS.newLowFilter;

  document.querySelectorAll("#volPeriod button").forEach(b => b.classList.toggle("active", b.dataset.p === volPeriod));
  document.getElementById("sortSel").value = sortMode;
  document.getElementById("priceModeSel").value = priceMode;
  document.getElementById("buySel").value = buyMode;
  document.getElementById("search").value = "";
  document.getElementById("minVol").value = 0;
  document.getElementById("priceMin").value = "";
  document.getElementById("priceMax").value = "";
  document.getElementById("roiMin").value = "";
  document.getElementById("roiMax").value = "";
  document.getElementById("probMin").value = "";
  document.getElementById("gapMin").value = "";
  document.getElementById("gapMax").value = "";
  document.getElementById("typeSel").value = "all";
  document.getElementById("hideAlerted").checked = false;
  document.getElementById("newLowSel").value = "all";
  updateSearchClearVisibility();

  selectedCats = new Set(DATA.map(catOf));
  document.querySelectorAll("#catList input").forEach(cb => cb.checked = true);
  document.querySelectorAll("#catList .cat-item").forEach(el => el.classList.add("checked"));

  shown = PAGE;
  render();
  saveFilters();
});

// Catégories : construites dynamiquement à partir des données reçues (vraiment toutes les
// catégories présentes — armes, couteaux, gants, stickers, agents, caisses, music kits, etc.),
// pas une liste codée en dur qui pourrait en oublier. Si des filtres sauvegardés existent,
// on ne coche que les catégories qu'ils contenaient encore parmi celles disponibles aujourd'hui.
function initCategories(savedSelectedCats) {
  const cats = [...new Set(DATA.map(catOf))].sort((a, b) => a.localeCompare(b));
  if (savedSelectedCats) {
    const savedSet = new Set(savedSelectedCats);
    selectedCats = new Set(cats.filter(c => savedSet.has(c)));
  } else {
    selectedCats = new Set(cats);
  }
  const list = document.getElementById("catList");
  list.innerHTML = "";
  for (const cat of cats) {
    const checked = selectedCats.has(cat);
    const label = document.createElement("label");
    label.className = "cat-item" + (checked ? " checked" : "");
    const cb = document.createElement("input");
    cb.type = "checkbox";
    cb.checked = checked;
    cb.addEventListener("change", () => {
      if (cb.checked) selectedCats.add(cat); else selectedCats.delete(cat);
      label.classList.toggle("checked", cb.checked);
      shown = PAGE; render(); saveFilters();
    });
    label.appendChild(cb);
    label.appendChild(document.createTextNode(cat));
    list.appendChild(label);
  }
  document.getElementById("catAll").addEventListener("click", () => {
    selectedCats = new Set(cats);
    list.querySelectorAll("input").forEach(cb => cb.checked = true);
    list.querySelectorAll(".cat-item").forEach(el => el.classList.add("checked"));
    shown = PAGE; render(); saveFilters();
  });
  document.getElementById("catNone").addEventListener("click", () => {
    selectedCats = new Set();
    list.querySelectorAll("input").forEach(cb => cb.checked = false);
    list.querySelectorAll(".cat-item").forEach(el => el.classList.remove("checked"));
    shown = PAGE; render(); saveFilters();
  });
}

// Catalogue des alertes déjà créées dans le dashboard (même origine) : sert au badge ⏱
// et au filtre "Masquer déjà alertés". Échec silencieux si le dashboard n'est pas
// accessible (ex: test local de skingap.html sans serveur tradelock) — le badge et le
// filtre restent simplement inactifs dans ce cas.
async function loadAlertedSkins() {
  try {
    const res = await fetch(ALERTS_API);
    if (!res.ok) return;
    const list = await res.json();
    alertedSkins = new Set(list.map(a => a.skin_name.toLowerCase()));
    render();
  } catch (e) {
    console.error("alertes dashboard indisponibles", e);
  }
}

// Skins avec un badge "nouveau prix bas" actif (24h) — même origine que le dashboard,
// échec silencieux si indisponible (le badge/filtre restent simplement inactifs).
async function loadPriceDrops() {
  try {
    const res = await fetch(PRICEDROPS_API);
    if (!res.ok) return;
    const list = await res.json();
    priceDrops = new Map(list.map(d => [d.name.toLowerCase(), d.dropped_at_ms]));
    render();
  } catch (e) {
    console.error("badges prix bas indisponibles", e);
  }
}

// Autocomplete du champ de recherche par nom, comme le champ "nom du skin" du dashboard
// (liste native <datalist>, pas de fetch supplémentaire : DATA est déjà chargé).
document.getElementById("itemNamesList").innerHTML =
  DATA.map(it => `<option value="${it.n.replace(/"/g, "&quot;")}"></option>`).join("");

const savedFilters = loadFilters();
if (savedFilters) applySavedFilters(savedFilters);
initCategories(savedFilters ? savedFilters.selectedCats : null);
loadAlertedSkins();
loadPriceDrops();
render();

// Mise à jour en direct via SSE (même origine que le dashboard) : un "alerts" rafraîchit
// juste le badge ⏱ / le filtre associé ; un "static" veut dire qu'un nouveau skingap.html
// (et skins.json) a été poussé — comme les données (DATA) sont figées dans le HTML généré
// au moment du push, la façon la plus sûre de refléter le nouveau contenu est de recharger
// la page plutôt que de re-fetch skins.json dans l'ancien DATA.
function connectSSE() {
  try {
    const es = new EventSource(EVENTS_URL);
    es.addEventListener("alerts", () => { loadAlertedSkins(); loadPriceDrops(); });
    es.addEventListener("static", () => location.reload());
  } catch (e) {
    console.error("SSE indisponible", e);
  }
}
// Repli si la connexion SSE échoue ou n'a jamais pu s'établir (proxy, réseau, etc.).
setInterval(loadAlertedSkins, 5 * 60 * 1000);
setInterval(loadPriceDrops, 5 * 60 * 1000);
connectSSE();

// Le SSE se coupe souvent quand l'onglet passe en arrière-plan (mobile en particulier),
// donc un push GitHub Actions pendant ce temps ne déclenche aucun reload : "Min (TP)"
// restait figé jusqu'au prochain rafraîchissement automatique (5 min) ou reload manuel.
// Au retour au premier plan, on force un reload complet pour être sûr d'avoir les
// dernières données (DATA est figé dans le HTML généré, un simple re-fetch ne suffit pas).
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState !== "visible") return;
  // Avant : reload() systématique au retour au premier plan → toute la page (DATA compris,
  // souvent 10000+ items) était reparsée et redessinée à chaque fois, d'où le délai perçu de
  // plusieurs secondes (bien plus lourd que les quelques images encore à charger). La plupart
  // du temps, aucun nouveau push n'a eu lieu depuis le dernier chargement : on vérifie via une
  // requête HEAD légère (Last-Modified) et on ne recharge que si le fichier a effectivement
  // changé ; sinon on se contente de rafraîchir les badges ⏱ / 🔻 et de reconnecter le SSE.
  fetch(location.pathname, { method: "HEAD", cache: "no-store" })
    .then(r => {
      const lastMod = r.headers.get("Last-Modified");
      if (lastMod && PAGE_LAST_MODIFIED && lastMod !== PAGE_LAST_MODIFIED) {
        location.reload();
      } else {
        loadAlertedSkins(); loadPriceDrops();
      }
    })
    .catch(() => { /* pas grave, le SSE + le refresh 5 min prennent le relais */ });
});
</script>
<script>setTimeout(()=>location.reload(),300000)</script></body>
</html>
""";
}
