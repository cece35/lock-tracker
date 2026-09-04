import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

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

    record Result(String name, String marketPage, SalesVolumes volumes, double minWithLocked, double askRaw, double medianRaw) {}

    static String fetchUrl(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept-Encoding", "br")
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
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

        System.out.println("Récupération de l'historique des ventes (volume, toutes périodes)...");
        String jsonSales = fetchUrl(client, "https://api.skinport.com/v1/sales/history?app_id=730&currency=EUR");
        System.out.println("Récupération des prix actuels (avec trade-protect, pour le prix d'achat)...");
        String jsonItemsAll = fetchUrl(client, "https://api.skinport.com/v1/items?app_id=730&currency=EUR&tradable=false");
        System.out.println("Récupération des prix actuels (tradable uniquement, pour le prix de vente espéré)...");
        String jsonItemsTradable = fetchUrl(client, "https://api.skinport.com/v1/items?app_id=730&currency=EUR");

        List<Object> salesList = (List<Object>) new JsonParser(jsonSales).parse();
        List<Object> itemsAllList = (List<Object>) new JsonParser(jsonItemsAll).parse();
        List<Object> itemsTradableList = (List<Object>) new JsonParser(jsonItemsTradable).parse();

        Map<String, SalesVolumes> volumesByName = new HashMap<>();
        Map<String, String> marketPageByName = new HashMap<>();
        for (Object o : salesList) {
            Map<String, Object> item = (Map<String, Object>) o;
            String name = (String) item.get("market_hash_name");
            Map<String, Object> h24 = sub(item, "last_24_hours");
            Map<String, Object> h7 = sub(item, "last_7_days");
            Map<String, Object> h30 = sub(item, "last_30_days");
            Map<String, Object> h90 = sub(item, "last_90_days");
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
        Map<String, Double> minWithLocked = new HashMap<>();
        for (Object o : itemsAllList) {
            Map<String, Object> item = (Map<String, Object>) o;
            String name = (String) item.get("market_hash_name");
            Object min = item.get("min_price");
            if (min != null) minWithLocked.put(name, (Double) min);
        }

        // Prix de vente espéré (brut, avant taxe) = prix minimum parmi les annonces immédiatement
        // disponibles (tradable=true, donc hors trade-protect) : c'est le prix auquel il faudrait
        // s'aligner pour revendre l'item rapidement, une fois son propre trade-protect écoulé.
        Map<String, Double> minTradable = new HashMap<>();
        for (Object o : itemsTradableList) {
            Map<String, Object> item = (Map<String, Object>) o;
            String name = (String) item.get("market_hash_name");
            Object min = item.get("min_price");
            if (min != null) minTradable.put(name, (Double) min);
        }

        List<Result> results = new ArrayList<>();
        for (Map.Entry<String, Double> e : minWithLocked.entrySet()) {
            String name = e.getKey();
            if (isDoppler(name)) continue; // exclu : phases mélangées, écart non pertinent
            Double askRaw = minTradable.get(name);
            // pas d'annonce tradable actuellement -> pas de prix de vente espéré calculable, on ignore
            if (askRaw == null) continue;
            SalesVolumes vol = volumesByName.getOrDefault(name, new SalesVolumes(0, 0, 0, 0, 0, 0, 0, 0));
            String mp = marketPageByName.getOrDefault(name, "https://skinport.com/market?item=" + name.replace(" ", "%20"));

            // On ne tranche plus ici entre prix demandé et médiane : les deux valeurs brutes
            // sont envoyées telles quelles, le mode de calcul (auto / médiane / min listé) est
            // choisi côté client et peut être changé sans re-fetch.
            double weighted = weightedMedian(vol);

            results.add(new Result(name, mp, vol, e.getValue(), askRaw, weighted));
        }
        results.sort(Comparator.comparing(Result::name));

        StringBuilder data = new StringBuilder("[");
        boolean first = true;
        for (Result r : results) {
            if (!first) data.append(",");
            first = false;
            data.append("{\"n\":\"").append(jsonEscape(r.name())).append("\"")
                .append(",\"p\":\"").append(jsonEscape(r.marketPage())).append("\"")
                .append(",\"min\":").append(r.minWithLocked())
                .append(",\"ask\":").append(r.askRaw())
                .append(",\"wm\":").append(r.medianRaw())
                .append(",\"v24\":").append(r.volumes().v24h())
                .append(",\"v7\":").append(r.volumes().v7j())
                .append(",\"v30\":").append(r.volumes().v30j())
                .append(",\"v90\":").append(r.volumes().v90j())
                .append("}");
        }
        data.append("]");

        // Fichier séparé, structuré, pour être consommé par d'autres outils (dashboard tradelock)
        // sans avoir à re-parser le HTML. Mêmes données que celles déjà calculées ci-dessus,
        // aucun impact sur le calcul ni sur le rapport HTML.
        Path jsonOut = Path.of(outPath).resolveSibling("skins.json");
        Files.writeString(jsonOut, data.toString());

        String html = HTML_TEMPLATE
                .replace("__DATA__", data.toString())
                .replace("__COUNT__", String.valueOf(results.size()))
                .replace("__DATE__", new Date().toString());

        Files.writeString(Path.of(outPath), html);
        System.out.println("OK — " + results.size() + " items écrits dans " + outPath);
        System.out.println("Ouvre ce fichier dans un navigateur (ex: termux-open " + outPath + ")");
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
  #search { flex: 1; min-width: 160px; }
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
  .name a { color:var(--text); text-decoration:none; }
  .name a:hover { color:var(--accent); }
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
      <div class="tagline">écart prix de vente espéré vs prix minimum (trade-protect inclus) — __COUNT__ items — généré le __DATE__</div>
    </div>
    <a class="navlink" href="__DASHBOARD_URL__">→ dashboard</a>
  </header>

  <div class="notice">
    <b>Lien Skinport :</b> chaque lien pointe vers la page marché de l'item, trié par prix croissant
    (<code>sort=price&order=asc</code>) et filtré sur le trade-protect ≤ 8 jours (<code>lock=8</code>), avec
    l'usure (<code>exterior=</code>) et StatTrak™/Souvenir détectés automatiquement depuis le nom.<br>
    <b>Prix de vente espéré :</b> calculé selon le mode choisi dans "Mode prix" —
    <b>Auto</b> (par défaut) prend le prix minimum demandé parmi les annonces tradable, sauf si la médiane
    pondérée des ventes réelles (24h/7j/30j/90j, pondérée par volume) lui est <i>inférieure</i>, auquel cas
    c'est elle qui est utilisée (affiché avec <span style="color:var(--accent)">Ⓜ</span>) ; <b>Médiane</b>
    force toujours la médiane pondérée des ventes (retombe sur le prix minimum si aucune vente enregistrée) ;
    <b>Min listé</b> force toujours le prix minimum demandé, quelle que soit la médiane. Le prix net après
    commission Skinport (8% si &lt;1000€, 6% si ≥1000€) est celui utilisé pour l'écart ; le prix brut avant
    commission est affiché entre parenthèses, à titre indicatif uniquement. L'écart = prix de vente net −
    prix min listé (trade-protect inclus, ce qu'il faudrait payer pour acheter aujourd'hui).
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
      <span class="lbl">Prix (TP) :</span>
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
        <option value="auto">Auto (min, sauf si médiane plus basse)</option>
        <option value="median">Médiane des ventes</option>
        <option value="min">Min listé</option>
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
        <option value="min_asc">Prix min (bas → haut)</option>
        <option value="min_desc">Prix min (haut → bas)</option>
        <option value="exp_asc">Prix espéré (bas → haut)</option>
        <option value="exp_desc">Prix espéré (haut → bas)</option>
        <option value="vol_desc">Volume (haut → bas)</option>
        <option value="vol_asc">Volume (bas → haut)</option>
        <option value="name_asc">Nom (A-Z)</option>
        <option value="name_desc">Nom (Z-A)</option>
        <option value="cat_asc">Catégorie (A-Z)</option>
      </select>
    </div>
    <input type="text" id="search" placeholder="Filtrer par nom...">
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
        <th class="num">Min (TP)</th>
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
const DASHBOARD_URL = "__DASHBOARD_URL__";
const ALERTS_API = DASHBOARD_URL + "api/alerts";
const PRICEDROPS_API = DASHBOARD_URL + "api/pricedrops";
const EVENTS_URL = DASHBOARD_URL + "events";
const overrides = {}; // n -> valeur manuelle du prix de vente espéré (brut, avant taxe)
let volPeriod = "7";
let priceMode = "auto"; // "auto" | "median" | "min"
let sortMode = "gap_desc";
let searchTerm = "";
let minVol = 0;
let priceMin = 0, priceMax = Infinity;
let roiMin = -Infinity, roiMax = Infinity;
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
    volPeriod, priceMode, sortMode, searchTerm, minVol,
    priceMin: isFinite(priceMin) ? priceMin : null,
    priceMax: isFinite(priceMax) ? priceMax : null,
    roiMin: isFinite(roiMin) ? roiMin : null,
    roiMax: isFinite(roiMax) ? roiMax : null,
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
  if (f.priceMode !== undefined) priceMode = f.priceMode;
  if (f.sortMode !== undefined) sortMode = f.sortMode;
  if (f.searchTerm !== undefined) searchTerm = f.searchTerm;
  if (f.minVol !== undefined) minVol = f.minVol;
  priceMin = (f.priceMin === null || f.priceMin === undefined) ? 0 : f.priceMin;
  priceMax = (f.priceMax === null || f.priceMax === undefined) ? Infinity : f.priceMax;
  roiMin = (f.roiMin === null || f.roiMin === undefined) ? -Infinity : f.roiMin;
  roiMax = (f.roiMax === null || f.roiMax === undefined) ? Infinity : f.roiMax;
  gapMin = (f.gapMin === null || f.gapMin === undefined) ? -Infinity : f.gapMin;
  gapMax = (f.gapMax === null || f.gapMax === undefined) ? Infinity : f.gapMax;
  if (f.typeFilter !== undefined) typeFilter = f.typeFilter;
  if (f.hideAlerted !== undefined) hideAlerted = f.hideAlerted;
  if (f.newLowFilter !== undefined) newLowFilter = f.newLowFilter;

  document.querySelectorAll("#volPeriod button").forEach(b => b.classList.toggle("active", b.dataset.p === volPeriod));
  document.getElementById("sortSel").value = sortMode;
  document.getElementById("priceModeSel").value = priceMode;
  document.getElementById("search").value = searchTerm;
  document.getElementById("minVol").value = minVol || 0;
  document.getElementById("priceMin").value = f.priceMin ?? "";
  document.getElementById("priceMax").value = f.priceMax ?? "";
  document.getElementById("roiMin").value = f.roiMin ?? "";
  document.getElementById("roiMax").value = f.roiMax ?? "";
  document.getElementById("gapMin").value = f.gapMin ?? "";
  document.getElementById("gapMax").value = f.gapMax ?? "";
  document.getElementById("typeSel").value = typeFilter;
  document.getElementById("hideAlerted").checked = hideAlerted;
  document.getElementById("newLowSel").value = newLowFilter;
}

// Valeurs par défaut, utilisées par le bouton "Réinitialiser les filtres".
const FILTER_DEFAULTS = {
  volPeriod: "7", priceMode: "auto", sortMode: "gap_desc", searchTerm: "",
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
  url += "&stattrack=" + (nameLower.includes("stattrak") ? 1 : 0);
  url += "&souvenir=" + (nameLower.startsWith("souvenir") ? 1 : 0);
  return url;
}

// Prix de vente espéré brut (avant taxe), selon le mode choisi :
// - "auto"   : prix minimum demandé (ask), sauf si la médiane pondérée des ventes (wm) est
//              disponible et strictement inférieure -> on prend la médiane à la place.
// - "median" : toujours la médiane pondérée des ventes ; si aucune vente enregistrée (wm <= 0),
//              retombe sur le prix minimum demandé.
// - "min"    : toujours le prix minimum demandé, quelle que soit la médiane.
// Une valeur éditée manuellement (override) remplace tout le reste.
// Retourne { raw, usedMedian } pour piloter aussi le badge Ⓜ à l'affichage.
function rawExpectedOf(it) {
  if (overrides[it.n] !== undefined) return { raw: overrides[it.n], usedMedian: false };
  const hasWm = it.wm !== null && it.wm !== undefined && it.wm > 0;
  if (priceMode === "median") {
    return hasWm ? { raw: it.wm, usedMedian: true } : { raw: it.ask, usedMedian: false };
  }
  if (priceMode === "min") {
    return { raw: it.ask, usedMedian: false };
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
    if (it.min < priceMin || it.min > priceMax) return false;
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
    const gap = med - it.min;
    const roi = it.min > 0 ? (gap / it.min) * 100 : 0;
    return { it, rawMed: exp.raw, usedMedian: exp.usedMedian, med, gap, roi, vol: volumeOf(it, volPeriod) };
  }).filter(r => r.roi >= roiMin && r.roi <= roiMax && r.gap >= gapMin && r.gap <= gapMax);
  switch (sortMode) {
    case "gap_desc": rows.sort((a,b) => b.gap - a.gap); break;
    case "gap_asc": rows.sort((a,b) => a.gap - b.gap); break;
    case "roi_desc": rows.sort((a,b) => b.roi - a.roi); break;
    case "roi_asc": rows.sort((a,b) => a.roi - b.roi); break;
    case "min_asc": rows.sort((a,b) => a.it.min - b.it.min); break;
    case "min_desc": rows.sort((a,b) => b.it.min - a.it.min); break;
    case "exp_asc": rows.sort((a,b) => a.med - b.med); break;
    case "exp_desc": rows.sort((a,b) => b.med - a.med); break;
    case "vol_desc": rows.sort((a,b) => (b.vol ?? 0) - (a.vol ?? 0)); break;
    case "vol_asc": rows.sort((a,b) => (a.vol ?? 0) - (b.vol ?? 0)); break;
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

    const tdName = document.createElement("td");
    tdName.className = "name";
    const a = document.createElement("a");
    a.href = link; a.target = "_blank"; a.rel = "noopener";
    a.textContent = r.it.n;
    tdName.appendChild(a);
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

    const tdMin = document.createElement("td");
    tdMin.className = "num";
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
}

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
document.getElementById("search").addEventListener("input", (e) => { searchTerm = e.target.value.toLowerCase(); shown = PAGE; render(); saveFilters(); });
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
  sortMode = FILTER_DEFAULTS.sortMode;
  searchTerm = FILTER_DEFAULTS.searchTerm;
  minVol = FILTER_DEFAULTS.minVol;
  priceMin = FILTER_DEFAULTS.priceMin;
  priceMax = FILTER_DEFAULTS.priceMax;
  roiMin = FILTER_DEFAULTS.roiMin;
  roiMax = FILTER_DEFAULTS.roiMax;
  typeFilter = FILTER_DEFAULTS.typeFilter;
  gapMin = FILTER_DEFAULTS.gapMin;
  gapMax = FILTER_DEFAULTS.gapMax;
  hideAlerted = false;
  newLowFilter = FILTER_DEFAULTS.newLowFilter;

  document.querySelectorAll("#volPeriod button").forEach(b => b.classList.toggle("active", b.dataset.p === volPeriod));
  document.getElementById("sortSel").value = sortMode;
  document.getElementById("priceModeSel").value = priceMode;
  document.getElementById("search").value = "";
  document.getElementById("minVol").value = 0;
  document.getElementById("priceMin").value = "";
  document.getElementById("priceMax").value = "";
  document.getElementById("roiMin").value = "";
  document.getElementById("roiMax").value = "";
  document.getElementById("gapMin").value = "";
  document.getElementById("gapMax").value = "";
  document.getElementById("typeSel").value = "all";
  document.getElementById("hideAlerted").checked = false;
  document.getElementById("newLowSel").value = "all";

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
</script>
</body>
</html>
""";
}
