// lock-tracker : écoute le Sale Feed (WebSocket public, gratuit, sans clé) de Skinport
// en continu, et retient pour chaque item la date de fin de trade-protect (champ "lock")
// de l'annonce encore verrouillée la moins chère (= celle qui fixe le "min_price avec
// trade-protect" utilisé par SkinGap). Expose ça via un petit endpoint HTTP JSON.
//
// A faire tourner 24h/24 sur une machine allumée en permanence (voir README.md).

const { io } = require("socket.io-client");
const parser = require("socket.io-msgpack-parser");
const express = require("express");
const fs = require("fs");

const DB_PATH = process.env.LOCKS_DB || "./locks-state.json";
const PORT = process.env.PORT || 8787;
const APPID = 730; // CS2
const CURRENCY = "EUR";

// état en mémoire : assetId -> { marketHashName, price (centimes), lock (ISO string) }
let assets = new Map();

function loadDb() {
  try {
    const raw = fs.readFileSync(DB_PATH, "utf8");
    assets = new Map(Object.entries(JSON.parse(raw)));
    console.log(`[lock-tracker] ${assets.size} annonces rechargées depuis ${DB_PATH}`);
  } catch {
    console.log("[lock-tracker] Pas d'état sauvegardé, démarrage à vide.");
  }
}

let saveTimer = null;
function scheduleSave() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    fs.writeFileSync(DB_PATH, JSON.stringify(Object.fromEntries(assets)));
  }, 5000);
}

function connect() {
  const socket = io("wss://skinport.com", {
    transports: ["websocket"],
    parser,
    reconnection: true,
    reconnectionDelay: 2000,
    reconnectionDelayMax: 30000,
  });

  socket.on("connect", () => {
    console.log("[lock-tracker] Connecté au Sale Feed Skinport");
    socket.emit("saleFeedJoin", { currency: CURRENCY, locale: "en", appid: APPID });
  });

  socket.on("saleFeed", (result) => {
    if (!result || !Array.isArray(result.sales)) return;

    if (result.eventType === "listed") {
      for (const s of result.sales) {
        if (!s.assetid || !s.lock || !s.marketHashName) continue;
        assets.set(s.assetid, {
          marketHashName: s.marketHashName,
          price: s.salePrice,
          lock: s.lock,
        });
      }
      scheduleSave();
    } else if (result.eventType === "sold") {
      for (const s of result.sales) {
        if (s.assetid) assets.delete(s.assetid);
      }
      scheduleSave();
    }
  });

  socket.on("disconnect", (reason) => console.log("[lock-tracker] Déconnecté :", reason));
  socket.on("connect_error", (err) => console.log("[lock-tracker] Erreur connexion, retry :", err.message));

  return socket;
}

// Purge horaire : une annonce dont le lock est passé depuis longtemps est soit devenue
// tradable (donc plus "verrouillée", plus pertinente ici), soit a été vendue/annulée sans
// qu'on capte l'event "sold" (Skinport n'envoie pas d'event pour les annulations).
setInterval(() => {
  const now = Date.now();
  for (const [id, a] of assets) {
    if (new Date(a.lock).getTime() < now - 24 * 3600 * 1000) assets.delete(id);
  }
}, 60 * 60 * 1000);

loadDb();
connect();

const app = express();

app.get("/locks.json", (_req, res) => {
  const now = Date.now();
  const byName = new Map(); // marketHashName -> { price, lock } (la moins chère encore verrouillée)
  for (const a of assets.values()) {
    if (new Date(a.lock).getTime() <= now) continue; // déjà déverrouillé
    const cur = byName.get(a.marketHashName);
    if (!cur || a.price < cur.price) byName.set(a.marketHashName, { price: a.price, lock: a.lock });
  }
  const out = {};
  for (const [name, v] of byName) out[name] = v.lock;
  res.json(out);
});

app.get("/health", (_req, res) => res.json({ ok: true, tracked: assets.size }));

app.listen(PORT, () => console.log(`[lock-tracker] API sur :${PORT} (GET /locks.json)`));
