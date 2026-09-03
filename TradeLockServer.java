import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Service unique : dashboard d'alertes trade lock CS2 + notifications ntfy + hébergement
 * du rapport SkinGap. Java pur (com.sun.net.httpserver), sans dépendance externe, dans le
 * même esprit que SkinGap.java (parseur JSON écrit à la main).
 *
 * Tout est monté sous un préfixe secret (config: path.secret) pour éviter qu'un tiers qui
 * scanne l'IP publique du VM ne tombe dessus — pas de login, juste une URL à bookmarker.
 *
 * Lancement : java TradeLockServer.java config.properties
 */
public class TradeLockServer {

    // -----------------------------------------------------------------
    // Config
    // -----------------------------------------------------------------
    static Properties CONFIG = new Properties();
    static int PORT;
    static String PATH_SECRET;
    static String NTFY_TOPIC;
    static int REMINDER_MINUTES_BEFORE;
    static Path DATA_FILE;
    static Path STATIC_DIR;

    static void loadConfig(String path) throws IOException {
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            CONFIG.load(in);
        }
        PORT = Integer.parseInt(CONFIG.getProperty("port", "8080"));
        PATH_SECRET = CONFIG.getProperty("path.secret");
        if (PATH_SECRET == null || PATH_SECRET.isBlank()) {
            throw new RuntimeException("path.secret manquant dans config.properties — génère-en un (voir README).");
        }
        NTFY_TOPIC = CONFIG.getProperty("ntfy.topic");
        if (NTFY_TOPIC == null || NTFY_TOPIC.isBlank()) {
            throw new RuntimeException("ntfy.topic manquant dans config.properties.");
        }
        REMINDER_MINUTES_BEFORE = Integer.parseInt(CONFIG.getProperty("reminder.minutes.before", "60"));
        DATA_FILE = Path.of(CONFIG.getProperty("data.file", "alerts.json"));
        STATIC_DIR = Path.of(CONFIG.getProperty("static.dir", "static"));
    }

    // -----------------------------------------------------------------
    // Mini parseur / writer JSON (même esprit que SkinGap.java)
    // -----------------------------------------------------------------
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
                pos++; // ':'
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
            String num = s.substring(start, pos);
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            return Long.parseLong(num);
        }
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

    // -----------------------------------------------------------------
    // Modèle
    // -----------------------------------------------------------------
    static class Alert {
        String id;
        String skinName;
        long tradelockEndMs;
        String notes;
        boolean notified;
        boolean reminded;
        long createdAtMs;

        String toJson() {
            return "{"
                    + "\"id\":\"" + jsonEscape(id) + "\","
                    + "\"skin_name\":\"" + jsonEscape(skinName) + "\","
                    + "\"tradelock_end_ms\":" + tradelockEndMs + ","
                    + "\"notes\":" + (notes == null ? "null" : "\"" + jsonEscape(notes) + "\"") + ","
                    + "\"notified\":" + notified + ","
                    + "\"reminded\":" + reminded + ","
                    + "\"created_at_ms\":" + createdAtMs
                    + "}";
        }

        @SuppressWarnings("unchecked")
        static Alert fromMap(Map<String, Object> m) {
            Alert a = new Alert();
            a.id = (String) m.get("id");
            a.skinName = (String) m.get("skin_name");
            a.tradelockEndMs = ((Number) m.get("tradelock_end_ms")).longValue();
            a.notes = (String) m.get("notes");
            a.notified = Boolean.TRUE.equals(m.get("notified"));
            a.reminded = Boolean.TRUE.equals(m.get("reminded"));
            Object created = m.get("created_at_ms");
            a.createdAtMs = created == null ? System.currentTimeMillis() : ((Number) created).longValue();
            return a;
        }
    }

    // -----------------------------------------------------------------
    // Store : liste en mémoire + persistance JSON sur disque (écriture atomique)
    // -----------------------------------------------------------------
    static class AlertStore {
        private final List<Alert> alerts = new ArrayList<>();
        private final ReentrantLock lock = new ReentrantLock();

        @SuppressWarnings("unchecked")
        void load() throws IOException {
            lock.lock();
            try {
                alerts.clear();
                if (!Files.exists(DATA_FILE)) return;
                String content = Files.readString(DATA_FILE, StandardCharsets.UTF_8);
                if (content.isBlank()) return;
                List<Object> list = (List<Object>) new JsonParser(content).parse();
                for (Object o : list) alerts.add(Alert.fromMap((Map<String, Object>) o));
            } finally {
                lock.unlock();
            }
        }

        void saveLocked() throws IOException {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < alerts.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(alerts.get(i).toJson());
            }
            sb.append("]");
            // écriture atomique : fichier temporaire puis rename, pour ne jamais laisser
            // alerts.json à moitié écrit si le process est tué en plein milieu.
            Path tmp = DATA_FILE.resolveSibling(DATA_FILE.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, DATA_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        Alert create(String skinName, long tradelockEndMs, String notes) throws IOException {
            lock.lock();
            try {
                Alert a = new Alert();
                a.id = UUID.randomUUID().toString();
                a.skinName = skinName;
                a.tradelockEndMs = tradelockEndMs;
                a.notes = notes;
                a.notified = false;
                a.reminded = false;
                a.createdAtMs = System.currentTimeMillis();
                alerts.add(a);
                saveLocked();
                return a;
            } finally {
                lock.unlock();
            }
        }

        boolean delete(String id) throws IOException {
            lock.lock();
            try {
                boolean removed = alerts.removeIf(a -> a.id.equals(id));
                if (removed) saveLocked();
                return removed;
            } finally {
                lock.unlock();
            }
        }

        List<Alert> listSortedByEnd() {
            lock.lock();
            try {
                List<Alert> copy = new ArrayList<>(alerts);
                copy.sort(Comparator.comparingLong(a -> a.tradelockEndMs));
                return copy;
            } finally {
                lock.unlock();
            }
        }

        // Renvoie les alertes qui ont besoin d'une notif (rappel ou finale) à cet instant,
        // et marque immédiatement les flags correspondants pour éviter les doublons si le
        // scheduler tourne deux fois de suite avant qu'un envoi ntfy ne soit confirmé.
        List<Object[]> pollDue(long now) throws IOException {
            lock.lock();
            try {
                List<Object[]> due = new ArrayList<>(); // {Alert, isFinal(boolean)}
                boolean changed = false;
                for (Alert a : alerts) {
                    if (a.notified) continue;
                    if (now >= a.tradelockEndMs) {
                        due.add(new Object[]{a, Boolean.TRUE});
                        a.notified = true;
                        a.reminded = true;
                        changed = true;
                    } else if (!a.reminded && now >= a.tradelockEndMs - REMINDER_MINUTES_BEFORE * 60_000L) {
                        due.add(new Object[]{a, Boolean.FALSE});
                        a.reminded = true;
                        changed = true;
                    }
                }
                if (changed) saveLocked();
                return due;
            } finally {
                lock.unlock();
            }
        }
    }

    static final AlertStore STORE = new AlertStore();

    // -----------------------------------------------------------------
    // Lien Skinport (portage de buildLink() de SkinGap.java)
    // -----------------------------------------------------------------
    static final Map<String, Integer> EXTERIOR_CODE = Map.of(
            "factory new", 2, "minimal wear", 4, "field-tested", 3, "field tested", 3,
            "well-worn", 5, "well worn", 5, "battle-scarred", 1, "battle scarred", 1
    );

    static String buildSkinportLink(String skinName) {
        String base = "https://skinport.com/market?item=" + java.net.URLEncoder.encode(skinName, StandardCharsets.UTF_8);
        StringBuilder url = new StringBuilder(base + "&sort=price&order=asc&lock=8");
        String lower = skinName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Integer> e : EXTERIOR_CODE.entrySet()) {
            if (lower.contains(e.getKey())) { url.append("&exterior=").append(e.getValue()); break; }
        }
        if (lower.contains("stattrak")) url.append("&stattrack=1");
        if (lower.startsWith("souvenir")) url.append("&souvenir=1");
        return url.toString();
    }

    // -----------------------------------------------------------------
    // Prix Skinport (best-effort, ne doit jamais faire échouer une notif)
    // -----------------------------------------------------------------
    @SuppressWarnings("unchecked")
    static Double fetchCurrentMinPrice(String skinName) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.skinport.com/v1/items?app_id=730&currency=EUR"))
                    .header("Accept-Encoding", "identity") // évite d'avoir besoin d'un binaire brotli sur le VM
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return null;
            List<Object> items = (List<Object>) new JsonParser(resp.body()).parse();
            for (Object o : items) {
                Map<String, Object> item = (Map<String, Object>) o;
                String name = (String) item.get("market_hash_name");
                if (name != null && name.equalsIgnoreCase(skinName)) {
                    Object min = item.get("min_price");
                    return min == null ? null : ((Number) min).doubleValue();
                }
            }
            return null;
        } catch (Exception e) {
            System.err.println("[prix] échec récupération prix pour \"" + skinName + "\" : " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------
    // Notification ntfy
    // -----------------------------------------------------------------
    static void sendNtfy(Alert a, boolean isFinal) {
        try {
            String link = buildSkinportLink(a.skinName);
            Double price = fetchCurrentMinPrice(a.skinName);
            String priceLine = price == null ? "Prix indisponible (vérifie le nom exact du skin)"
                    : "Prix min. actuel sur Skinport : " + String.format(Locale.FRANCE, "%.2f €", price);

            String title = isFinal
                    ? "🔓 Trade lock terminé : " + a.skinName
                    : "⏰ Trade lock bientôt fini (" + REMINDER_MINUTES_BEFORE + " min) : " + a.skinName;
            String message = priceLine
                    + (a.notes != null && !a.notes.isBlank() ? "\nNote : " + a.notes : "");

            StringBuilder body = new StringBuilder("{");
            body.append("\"topic\":\"").append(jsonEscape(NTFY_TOPIC)).append("\",");
            body.append("\"title\":\"").append(jsonEscape(title)).append("\",");
            body.append("\"message\":\"").append(jsonEscape(message)).append("\",");
            body.append("\"priority\":").append(isFinal ? 5 : 3).append(",");
            body.append("\"tags\":[\"").append(isFinal ? "unlock" : "alarm_clock").append("\"],");
            body.append("\"actions\":[{\"action\":\"view\",\"label\":\"Ouvrir sur Skinport\",\"url\":\"")
                    .append(jsonEscape(link)).append("\",\"clear\":false}]");
            body.append("}");

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://ntfy.sh/"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                System.err.println("[ntfy] réponse inattendue " + resp.statusCode() + " : " + resp.body());
            } else {
                System.out.println("[ntfy] notif envoyée pour \"" + a.skinName + "\" (final=" + isFinal + ")");
            }
        } catch (Exception e) {
            System.err.println("[ntfy] échec d'envoi pour \"" + a.skinName + "\" : " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    // Scheduler : boucle simple, vérifie toutes les 60s
    // -----------------------------------------------------------------
    static void startScheduler() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    long now = System.currentTimeMillis();
                    List<Object[]> due = STORE.pollDue(now);
                    for (Object[] pair : due) {
                        Alert a = (Alert) pair[0];
                        boolean isFinal = (Boolean) pair[1];
                        sendNtfy(a, isFinal);
                    }
                } catch (Exception e) {
                    System.err.println("[scheduler] erreur : " + e.getMessage());
                }
                try { Thread.sleep(60_000); } catch (InterruptedException ignored) {}
            }
        }, "scheduler");
        t.setDaemon(true);
        t.start();
    }

    // -----------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------
    static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static void sendHtml(HttpExchange ex, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : "config.properties";
        loadConfig(configPath);
        STORE.load();
        Files.createDirectories(STATIC_DIR);

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        String prefix = "/" + PATH_SECRET;

        // Dashboard
        server.createContext(prefix + "/", ex -> {
            try {
                if (!ex.getRequestURI().getPath().equals(prefix + "/")) {
                    ex.sendResponseHeaders(404, -1);
                    return;
                }
                if (!"GET".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); return; }
                sendHtml(ex, 200, DASHBOARD_HTML.replace("__PREFIX__", prefix));
            } finally {
                ex.close();
            }
        });

        // Fichiers statiques (skingap.html généré par cron, etc.) sous __PREFIX__/static/...
        server.createContext(prefix + "/static/", ex -> {
            try {
                String p = ex.getRequestURI().getPath().substring((prefix + "/static/").length());
                if (p.isBlank() || p.contains("..")) { ex.sendResponseHeaders(400, -1); return; }
                Path file = STATIC_DIR.resolve(p).normalize();
                if (!file.startsWith(STATIC_DIR) || !Files.exists(file) || Files.isDirectory(file)) {
                    ex.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] bytes = Files.readAllBytes(file);
                String ct = p.endsWith(".html") ? "text/html; charset=utf-8" : "application/octet-stream";
                ex.getResponseHeaders().set("Content-Type", ct);
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
            } finally {
                ex.close();
            }
        });

        // API alertes
        server.createContext(prefix + "/api/alerts", ex -> {
            try {
                String method = ex.getRequestMethod();
                String path = ex.getRequestURI().getPath();
                String sub = path.substring((prefix + "/api/alerts").length()); // "" ou "/{id}"

                if (method.equals("GET") && sub.isEmpty()) {
                    List<Alert> alerts = STORE.listSortedByEnd();
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < alerts.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(alerts.get(i).toJson());
                    }
                    sb.append("]");
                    sendJson(ex, 200, sb.toString());

                } else if (method.equals("POST") && sub.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) new JsonParser(readBody(ex)).parse();
                    String skinName = (String) body.get("skin_name");
                    Object endMsObj = body.get("tradelock_end_ms");
                    String notes = (String) body.get("notes");
                    if (skinName == null || skinName.isBlank() || endMsObj == null) {
                        sendJson(ex, 400, "{\"error\":\"skin_name et tradelock_end_ms sont requis\"}");
                        return;
                    }
                    long endMs = ((Number) endMsObj).longValue();
                    Alert a = STORE.create(skinName.trim(), endMs, notes);
                    sendJson(ex, 201, a.toJson());

                } else if (method.equals("DELETE") && sub.startsWith("/")) {
                    String id = sub.substring(1);
                    boolean removed = STORE.delete(id);
                    sendJson(ex, removed ? 200 : 404, "{\"deleted\":" + removed + "}");

                } else {
                    ex.sendResponseHeaders(404, -1);
                }
            } catch (Exception e) {
                try { sendJson(ex, 500, "{\"error\":\"" + jsonEscape(e.getMessage() == null ? "erreur serveur" : e.getMessage()) + "\"}"); }
                catch (IOException ignored) {}
            } finally {
                ex.close();
            }
        });

        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        startScheduler();

        System.out.println("TradeLockServer démarré sur le port " + PORT);
        System.out.println("Dashboard : http://<IP_DU_VM>:" + PORT + prefix + "/");
    }

    // -----------------------------------------------------------------
    // Dashboard HTML/JS embarqué — même identité visuelle que SkinGap.java
    // -----------------------------------------------------------------
    static final String DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>tradelock</title>
<style>
  @import url('https://cdnjs.cloudflare.com/ajax/libs/ibm-plex/6.0.0/css/ibm-plex-mono.min.css');
  :root {
    --bg: #0e0d0b; --surface: #17150f; --border: #2c281f; --text: #ece7dc;
    --muted: #8f897a; --accent: #c98a2c; --accent-dim: #8a642a;
    --positive: #8caa7e; --negative: #b3705c;
  }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--text); font-family:'IBM Plex Mono', ui-monospace, monospace; font-size:13px; line-height:1.5; }
  .wrap { max-width: 760px; margin: 0 auto; padding: 24px 14px 60px; }
  header { border-bottom:1px solid var(--border); padding-bottom:14px; margin-bottom:18px; display:flex; justify-content:space-between; align-items:baseline; flex-wrap:wrap; gap:8px; }
  .wordmark { font-size:16px; } .wordmark span { color:var(--accent); }
  .navlink { color:var(--muted); font-size:12px; text-decoration:none; border:1px solid var(--border); padding:5px 10px; border-radius:3px; }
  .navlink:hover { color:var(--accent); border-color:var(--accent-dim); }
  .tagline { color:var(--muted); font-size:12px; margin-top:4px; }
  form.add { background:var(--surface); border:1px solid var(--border); border-radius:4px; padding:14px; margin-bottom:22px; display:flex; flex-wrap:wrap; gap:10px; align-items:flex-end; }
  .field { display:flex; flex-direction:column; gap:4px; }
  .field label { color:var(--muted); font-size:11px; }
  input[type=text], input[type=datetime-local] {
    background: var(--bg); border:1px solid var(--border); color:var(--text);
    font-family:inherit; font-size:12.5px; padding:7px 9px; border-radius:3px;
  }
  #skinName { min-width: 220px; }
  #notes { min-width: 160px; }
  button.submit { background:var(--accent); color:#12100b; border:none; font-family:inherit; font-weight:600; font-size:12.5px; padding:8px 16px; border-radius:3px; cursor:pointer; }
  button.submit:hover { background:#d99a3c; }
  #formErr { color:var(--negative); font-size:11.5px; width:100%; }
  .count { color:var(--muted); font-size:11.5px; margin-bottom:10px; }
  .card { background:var(--surface); border:1px solid var(--border); border-radius:4px; padding:12px 14px; margin-bottom:10px; display:flex; justify-content:space-between; align-items:center; gap:10px; }
  .card.done { opacity:0.55; }
  .card .left { display:flex; flex-direction:column; gap:3px; min-width:0; }
  .skin-name { font-size:13.5px; }
  .skin-name a { color:var(--text); text-decoration:none; }
  .skin-name a:hover { color:var(--accent); }
  .meta { color:var(--muted); font-size:11px; }
  .countdown { font-size:13px; font-weight:600; white-space:nowrap; }
  .countdown.soon { color:var(--accent); }
  .countdown.over { color:var(--positive); }
  .status-badge { font-size:10px; padding:2px 6px; border-radius:8px; border:1px solid var(--border); color:var(--muted); }
  .status-badge.pending { color:var(--accent); border-color:var(--accent-dim); }
  .status-badge.reminded { color:var(--accent); border-color:var(--accent-dim); }
  .status-badge.done { color:var(--positive); }
  .right { display:flex; align-items:center; gap:10px; flex-shrink:0; }
  .del { color:var(--muted); cursor:pointer; font-size:14px; background:none; border:none; }
  .del:hover { color:var(--negative); }
  .empty { color:var(--muted); font-size:12px; padding:20px 0; text-align:center; }
  footer { margin-top:26px; color:var(--muted); font-size:11px; border-top:1px solid var(--border); padding-top:12px; }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div>
      <div class="wordmark">trade<span>lock</span></div>
      <div class="tagline">alertes de fin de trade lock CS2</div>
    </div>
    <a class="navlink" href="__PREFIX__/static/skingap.html">→ skingap</a>
  </header>

  <form class="add" id="addForm">
    <div class="field">
      <label for="skinName">Nom du skin</label>
      <input type="text" id="skinName" placeholder="AK-47 | Redline (Field-Tested)" required>
    </div>
    <div class="field">
      <label for="endTime">Fin du trade lock</label>
      <input type="datetime-local" id="endTime" required>
    </div>
    <div class="field">
      <label for="notes">Notes (optionnel)</label>
      <input type="text" id="notes" placeholder="prix payé, lien...">
    </div>
    <button type="submit" class="submit">Ajouter</button>
    <div id="formErr"></div>
  </form>

  <div class="count" id="count"></div>
  <div id="list"></div>

  <footer>Actualisation auto toutes les 5 min · dernière synchro : <span id="lastSync">—</span></footer>
</div>

<script>
const PREFIX = "__PREFIX__";
const API = PREFIX + "/api/alerts";
let alerts = [];

const EXTERIOR_CODE = {
  "factory new": 2, "minimal wear": 4, "field-tested": 3, "field tested": 3,
  "well-worn": 5, "well worn": 5, "battle-scarred": 1, "battle scarred": 1
};
function buildLink(name) {
  let url = "https://skinport.com/market?item=" + encodeURIComponent(name) + "&sort=price&order=asc&lock=8";
  const lower = name.toLowerCase();
  for (const [label, code] of Object.entries(EXTERIOR_CODE)) {
    if (lower.includes(label)) { url += "&exterior=" + code; break; }
  }
  if (lower.includes("stattrak")) url += "&stattrack=1";
  if (lower.startsWith("souvenir")) url += "&souvenir=1";
  return url;
}

function fmtCountdown(ms) {
  if (ms <= 0) return "terminé";
  const s = Math.floor(ms / 1000);
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (d > 0) return d + "j " + h + "h " + m + "min";
  if (h > 0) return h + "h " + m + "min " + sec + "s";
  return m + "min " + sec + "s";
}

function render() {
  document.getElementById("count").textContent = alerts.length + " alerte(s)";
  const list = document.getElementById("list");
  if (alerts.length === 0) {
    list.innerHTML = '<div class="empty">Aucune alerte pour le moment.</div>';
    return;
  }
  list.innerHTML = "";
  const now = Date.now();
  for (const a of alerts) {
    const remaining = a.tradelock_end_ms - now;
    const card = document.createElement("div");
    card.className = "card" + (a.notified ? " done" : "");

    const left = document.createElement("div");
    left.className = "left";
    const nameDiv = document.createElement("div");
    nameDiv.className = "skin-name";
    const link = document.createElement("a");
    link.href = buildLink(a.skin_name); link.target = "_blank"; link.rel = "noopener";
    link.textContent = a.skin_name + " ↗";
    nameDiv.appendChild(link);
    left.appendChild(nameDiv);
    const meta = document.createElement("div");
    meta.className = "meta";
    const endDate = new Date(a.tradelock_end_ms);
    meta.textContent = "fin : " + endDate.toLocaleString("fr-FR") + (a.notes ? " · " + a.notes : "");
    left.appendChild(meta);
    card.appendChild(left);

    const right = document.createElement("div");
    right.className = "right";
    const cd = document.createElement("div");
    cd.className = "countdown" + (a.notified ? " over" : (remaining < 3600000 ? " soon" : ""));
    cd.textContent = fmtCountdown(remaining);
    right.appendChild(cd);

    const badge = document.createElement("div");
    if (a.notified) { badge.className = "status-badge done"; badge.textContent = "notifié"; }
    else if (a.reminded) { badge.className = "status-badge reminded"; badge.textContent = "rappel envoyé"; }
    else { badge.className = "status-badge pending"; badge.textContent = "en attente"; }
    right.appendChild(badge);

    const del = document.createElement("button");
    del.className = "del"; del.textContent = "✕"; del.title = "Supprimer";
    del.onclick = () => deleteAlert(a.id);
    right.appendChild(del);

    card.appendChild(right);
    list.appendChild(card);
  }
}

async function fetchAlerts() {
  try {
    const res = await fetch(API);
    alerts = await res.json();
    document.getElementById("lastSync").textContent = new Date().toLocaleTimeString("fr-FR");
    render();
  } catch (e) {
    console.error("sync failed", e);
  }
}

async function deleteAlert(id) {
  await fetch(API + "/" + id, { method: "DELETE" });
  fetchAlerts();
}

document.getElementById("addForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const errEl = document.getElementById("formErr");
  errEl.textContent = "";
  const skinName = document.getElementById("skinName").value.trim();
  const endVal = document.getElementById("endTime").value;
  const notes = document.getElementById("notes").value.trim();
  if (!skinName || !endVal) return;
  const endMs = new Date(endVal).getTime();
  if (isNaN(endMs)) { errEl.textContent = "Date invalide."; return; }
  const res = await fetch(API, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ skin_name: skinName, tradelock_end_ms: endMs, notes: notes || null })
  });
  if (!res.ok) { errEl.textContent = "Erreur lors de l'ajout."; return; }
  document.getElementById("addForm").reset();
  fetchAlerts();
});

// countdown en direct (chaque seconde), resynchro serveur toutes les 5 min
setInterval(render, 1000);
setInterval(fetchAlerts, 5 * 60 * 1000);
fetchAlerts();
</script>
</body>
</html>
""";
}
