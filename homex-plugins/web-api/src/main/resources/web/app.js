/* HomeX Dashboard – spricht mit dem WebAPI-Plugin (gleicher Server, Pfad /api). */
"use strict";

const API = "";
const TOKEN_KEY = "homex-token";
const CLIENT = "Dashboard";

/** Seiten der App. "sichtbar" blendet Seiten aus, für die es (noch) keine Geräte gibt. */
const SEITEN = [
  { pfad: "", titel: "Übersicht", icon: "haus" },
  { pfad: "energie", titel: "Energie", icon: "energie" },
  { pfad: "klima", titel: "Klima", icon: "klima" },
  { pfad: "automation", titel: "Automation", icon: "automation", sichtbar: () => nachTyp("SCENE", "AUTOMATION").length > 0 },
  { pfad: "aktivitaet", titel: "Verlauf", icon: "aktivitaet" },
  { pfad: "system", titel: "System", icon: "system" },
];
const seiteSichtbar = (s) => !s.sichtbar || s.sichtbar();

const MODI = [["kuehlen", "Kühlen"], ["heizen", "Heizen"], ["entfeuchten", "Entfeuchten"], ["lueften", "Lüften"], ["auto", "Auto"]];
const MODUS_FARBE = { kuehlen: "var(--kuehl)", entfeuchten: "var(--kuehl)", heizen: "var(--heiss)", auto: "var(--messing)", lueften: "var(--batterie)" };
const LUEFTER = [["auto", "Auto"], ["sehr_leise", "Sehr leise"], ["leise", "Leise"], ["mittel", "Mittel"], ["hoch", "Hoch"], ["sehr_hoch", "Sehr hoch"]];
const NICHT_IN_RAEUMEN = new Set(["SCENE", "AUTOMATION", "WEATHER", "SOLAR_INVERTER", "POWER_METER", "BATTERY"]);

const zustand = {
  token: null,
  info: null,
  geraete: new Map(),          // id → Gerät
  aktivitaet: [],
  quelle: null,                // EventSource
  erststart: true,
  aktivFilter: "alle",
};

/** Aktualisierer der aktuell sichtbaren Ansicht: Geräte-ID → Funktionen, die bei Änderungen laufen */
let beobachter = new Map();
let seitenAufraeumen = [];

const $ = (id) => document.getElementById(id);

// =====================================================================
// Kleine Helfer
// =====================================================================

function h(tag, attrs = {}, ...kinder) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === "class") e.className = v;
    else if (k === "html") e.innerHTML = v;
    else if (k === "style" && typeof v === "object") {
      for (const [sk, sv] of Object.entries(v)) sk.startsWith("--") ? e.style.setProperty(sk, sv) : (e.style[sk] = sv);
    }
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? "" : v);
  }
  for (const k of kinder.flat(Infinity)) if (k != null && k !== false) e.append(k);
  return e;
}
const ic = (name, klasse) => h("span", { html: icon(name, klasse), style: { display: "contents" } });

const istZahl = (v) => typeof v === "number" && isFinite(v);
const zahl = (v, st = 0, fest = false) =>
  Number(v).toLocaleString("de-DE", { maximumFractionDigits: st, minimumFractionDigits: fest ? st : 0 });
function leistung(w) {
  if (!istZahl(w)) return ["–", ""];
  const a = Math.abs(w);
  return a >= 1000 ? [zahl(a / 1000, 1, true), "kW"] : [zahl(a), "W"];
}
const uhrzeit = (d) => d.toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit" });
function wannText(ms) {
  if (!ms) return null;
  const d = new Date(ms), heute = new Date();
  const gestern = new Date(); gestern.setDate(heute.getDate() - 1);
  if (d.toDateString() === heute.toDateString()) return "heute " + uhrzeit(d);
  if (d.toDateString() === gestern.toDateString()) return "gestern " + uhrzeit(d);
  return d.toLocaleDateString("de-DE", { day: "numeric", month: "short" }) + " " + uhrzeit(d);
}
const isoZuMs = (s) => (s ? new Date(s).getTime() : null);

function nachTyp(...typen) {
  return [...zustand.geraete.values()].filter((d) => typen.includes(d.type))
    .sort((a, b) => a.name.localeCompare(b.name, "de"));
}
const solar = () => nachTyp("SOLAR_INVERTER")[0];
const wetter = () => nachTyp("WEATHER")[0];

function lesen() { try { return localStorage.getItem(TOKEN_KEY); } catch { return zustand.token; } }
function merken(t) { zustand.token = t; try { t ? localStorage.setItem(TOKEN_KEY, t) : localStorage.removeItem(TOKEN_KEY); } catch {} }

let meldungTimer;
function melden(text, ok = false) {
  const el = $("meldung");
  el.textContent = text;
  el.classList.toggle("ok", ok);
  el.classList.add("zeigen");
  clearTimeout(meldungTimer);
  meldungTimer = setTimeout(() => el.classList.remove("zeigen"), ok ? 3000 : 6000);
}

// =====================================================================
// Start, Anmeldung, Navigation
// =====================================================================

document.addEventListener("DOMContentLoaded", () => {
  const url = new URL(location.href);
  if (url.searchParams.has("token")) {           // Link für Tablets: http://homex:8080/?token=…
    merken(url.searchParams.get("token"));
    url.searchParams.delete("token");
    history.replaceState(null, "", url.pathname + url.search + url.hash);
  }
  zustand.token = lesen();


  $("login-form").addEventListener("submit", anmelden);
  $("blatt-zu").innerHTML = icon("schliessen");
  $("blatt-zu").addEventListener("click", () => $("blatt").close());
  $("blatt").addEventListener("click", (e) => { if (e.target === $("blatt")) $("blatt").close(); });   // Klick daneben
  $("blatt").addEventListener("close", () => { blattBeobachter = null; });

  window.addEventListener("hashchange", zeigen);
  let resizeTimer;
  window.addEventListener("resize", () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(zeigen, 200); });

  if (zustand.token) laden(); else loginZeigen();
  appAktuellHalten();
});

function loginZeigen(meldung) {
  $("login-fehler").hidden = !meldung;
  $("login-fehler").textContent = meldung || "";
  if (!$("login").open) $("login").showModal();
  $("token").focus();
}

async function anmelden(e) {
  e.preventDefault();
  const t = $("token").value.trim();
  if (!t) return;
  merken(t);
  try {
    await api("/api/info");
    $("login").close();
    $("token").value = "";
    laden();
  } catch (err) {
    merken(null);
    loginZeigen(err.status === 401
      ? "Dieser Zugangsschlüssel passt nicht. Prüfe ihn in plugins/WebAPI/config.properties."
      : "HomeX ist nicht erreichbar. Läuft der Server?");
  }
}

function abmelden() {
  merken(null);
  zustand.quelle?.close();
  zustand.quelle = null;
  zustand.geraete.clear();
  status("start", "Abgemeldet");
  zeigen();
  loginZeigen();
}

function status(z, text) {
  const el = $("nav-status");
  el.dataset.zustand = z;
  el.textContent = text;
}

// =====================================================================
// Daten
// =====================================================================

async function api(pfad, optionen = {}) {
  const res = await fetch(API + pfad, {
    ...optionen,
    headers: { Authorization: "Bearer " + zustand.token, "Content-Type": "application/json", "X-HomeX-Client": CLIENT,
      ...(optionen.headers || {}) },
  });
  let daten = null;
  try { daten = await res.json(); } catch {}
  if (!res.ok) {
    const err = new Error((daten && daten.fehler) || "HTTP " + res.status);
    err.status = res.status;
    err.daten = daten;
    throw err;
  }
  return daten;
}

async function laden() {
  try {
    const [info, liste, akt] = await Promise.all([api("/api/info"), api("/api/devices"), api("/api/activity?limit=150")]);
    zustand.info = info;
    zustand.geraete = new Map(liste.map((d) => [d.id, d]));
    zustand.aktivitaet = akt;
    zeigen();
    live();
  } catch (err) {
    if (err.status === 401) { merken(null); loginZeigen("Bitte melde dich erneut an."); return; }
    status("weg", "HomeX nicht erreichbar");
    zeigenOffline();
    setTimeout(laden, 5000);
  }
}

// =====================================================================
// Handy-App: immer aktuell bleiben
// =====================================================================

let letzteNachricht = 0;
let versteckSeit = 0;

/**
 * Auf dem Handy wird die App im Hintergrund eingefroren und die Live-Verbindung bricht still ab.
 * Deshalb: beim Zurückkehren sofort neu verbinden, eine eingeschlafene Verbindung erkennen
 * und neue Versionen des Dashboards automatisch übernehmen.
 */
function appAktuellHalten() {
  // 1. Neue Dashboard-Version: der Service Worker holt sie, die Seite lädt einmal neu
  if ("serviceWorker" in navigator) {
    const hatteSchonEinen = !!navigator.serviceWorker.controller;
    navigator.serviceWorker.register("sw.js", { updateViaCache: "none" }).then((reg) => {
      document.addEventListener("visibilitychange", () => { if (!document.hidden) reg.update().catch(() => {}); });
    }).catch(() => {});
    let neuGeladen = false;
    navigator.serviceWorker.addEventListener("controllerchange", () => {
      if (hatteSchonEinen && !neuGeladen) { neuGeladen = true; location.reload(); }
    });
  }

  // 2. Zurück aus dem Hintergrund → alles frisch holen und neu verbinden
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) { versteckSeit = Date.now(); return; }
    if (!zustand.token) return;
    const lange = versteckSeit && Date.now() - versteckSeit > 15_000;
    if (lange || !zustand.quelle || zustand.quelle.readyState !== EventSource.OPEN) auffrischen();
  });
  window.addEventListener("online", () => zustand.token && auffrischen());
  window.addEventListener("pageshow", (e) => { if (e.persisted && zustand.token) auffrischen(); });

  // 3. Wächter: der Server meldet sich alle 20 s – bleibt das aus, ist die Verbindung tot
  setInterval(() => {
    if (!zustand.token || document.hidden || !zustand.quelle) return;
    if (letzteNachricht && Date.now() - letzteNachricht > 50_000) auffrischen();
  }, 10_000);
}

let auffrischenLaeuft = false;
async function auffrischen() {
  if (auffrischenLaeuft) return;
  auffrischenLaeuft = true;
  status("start", "Aktualisiere …");
  try {
    const [info, akt] = await Promise.all([api("/api/info"), api("/api/activity?limit=150")]);
    zustand.info = info;
    zustand.aktivitaet = akt;
    melde("__info"); melde("__aktivitaet"); fehlerZaehler();
    live();                              // der Snapshot beim Verbinden bringt alle Geräte auf Stand
  } catch (err) {
    if (err.status === 401) { merken(null); loginZeigen("Bitte melde dich erneut an."); }
    else status("weg", "HomeX nicht erreichbar");
  } finally {
    auffrischenLaeuft = false;
  }
}

function live() {
  zustand.quelle?.close();
  const q = new EventSource(API + "/api/events?token=" + encodeURIComponent(zustand.token));
  zustand.quelle = q;
  letzteNachricht = Date.now();
  const lebt = () => { letzteNachricht = Date.now(); };
  ["snapshot", "state", "device", "removed", "activity", "plugins", "ping"].forEach((t) => q.addEventListener(t, lebt));
  q.onopen = () => { lebt(); status("live", "Live verbunden"); };
  q.onerror = async () => {
    status("weg", "Verbindung unterbrochen …");
    try {
      await api("/api/info");
      // Server ist erreichbar, aber der Live-Kanal ist endgültig zu (z. B. vom Server rausgeworfen) →
      // nicht auf den Wächter (bis zu 60 s) warten, sondern gleich neu verbinden
      if (q.readyState === EventSource.CLOSED && zustand.quelle === q) setTimeout(() => {
        if (zustand.quelle === q && !document.hidden) auffrischen();
      }, 2000);
    } catch (err) {
      if (err.status === 401) { q.close(); merken(null); loginZeigen("Bitte melde dich erneut an."); }
    }
  };
  q.addEventListener("snapshot", (e) => {
    const vorher = strukturSchluessel();
    const liste = JSON.parse(e.data);
    const ids = new Set(liste.map((d) => d.id));
    [...zustand.geraete.keys()].forEach((id) => { if (!ids.has(id)) zustand.geraete.delete(id); });
    liste.forEach(uebernehmen);
    if (strukturSchluessel() !== vorher) zeigen(); else zustand.geraete.forEach((d) => melde(d.id));
  });
  q.addEventListener("state", (e) => {
    const { id, key, value, source, changed } = JSON.parse(e.data);
    const d = zustand.geraete.get(id);
    if (!d) return;
    d.states[key] = value;
    d.changed = changed;
    d.source = source;
    melde(id);
  });
  q.addEventListener("device", (e) => {
    uebernehmen(JSON.parse(e.data));
    zeigen();
  });
  q.addEventListener("removed", (e) => {
    zustand.geraete.delete(JSON.parse(e.data).id);
    zeigen();
  });
  q.addEventListener("activity", (e) => {
    const eintrag = JSON.parse(e.data);
    zustand.aktivitaet.unshift(eintrag);
    zustand.aktivitaet.length = Math.min(zustand.aktivitaet.length, 400);
    melde("__aktivitaet");
    fehlerZaehler();
  });
  q.addEventListener("plugins", () => api("/api/info").then((i) => { zustand.info = i; melde("__info"); }).catch(() => {}));
}

/** Übernimmt neue Gerätedaten in das bestehende Objekt, damit alle Ansichten dasselbe Objekt sehen. */
function uebernehmen(neu) {
  const alt = zustand.geraete.get(neu.id);
  if (!alt) { zustand.geraete.set(neu.id, neu); return neu; }
  for (const k of Object.keys(alt)) if (!(k in neu)) delete alt[k];
  return Object.assign(alt, neu);
}

/** Ändert sich die Zusammensetzung (Geräte, Räume), muss die Seite neu aufgebaut werden. */
function strukturSchluessel() {
  return [...zustand.geraete.values()].map((d) => d.id + ":" + (d.room || "")).sort().join("|");
}

/** Benachrichtigt alles, was gerade ein Gerät anzeigt. */
function melde(id) {
  beobachter.get(id)?.forEach((f) => f(zustand.geraete.get(id)));
  if (blattBeobachter && blattBeobachter.id === id) blattBeobachter.f(zustand.geraete.get(id));
}

function beobachten(id, f) {
  if (!beobachter.has(id)) beobachter.set(id, new Set());
  beobachter.get(id).add(f);
  f(zustand.geraete.get(id));
}

/**
 * Steuerbefehl senden. Die Anzeige zeigt "wartet", bis HomeX antwortet – erst dann ändert sich der Zustand.
 * So sieht man sofort, ob der Befehl wirklich angekommen ist.
 */
async function senden(id, key, value, element) {
  const d = zustand.geraete.get(id);
  if (!d) return false;
  element?.classList.add("wartet");
  try {
    const antwort = await api("/api/devices/" + encodeURIComponent(id), { method: "POST", body: JSON.stringify({ key, value }) });
    uebernehmen(antwort.device);
    melde(id);
    return true;
  } catch (err) {
    if (err.daten?.device) { uebernehmen(err.daten.device); melde(id); }
    if (element) {
      element.classList.remove("fehler");
      void element.offsetWidth;
      element.classList.add("fehler");
      setTimeout(() => element.classList.remove("fehler"), 900);
    }
    melden(err.status === 401 ? "Bitte melde dich erneut an."
      : `${d.name}: Befehl wurde nicht ausgeführt. Den Grund zeigt die HomeX-Konsole.`);
    return false;
  } finally {
    element?.classList.remove("wartet");
  }
}

function fehlerZaehler() {
  const el = $("nav-fehler");
  if (!el) return;
  const seit = Date.now() - 3600e3;
  const n = zustand.aktivitaet.filter((a) => a.art === "fehler" && isoZuMs(a.zeit) > seit).length;
  el.textContent = n ? String(n) : "";
  el.title = n ? n + " Fehler in der letzten Stunde" : "";
}

// =====================================================================
// Seiten
// =====================================================================

function zeigen() {
  beobachter = new Map();
  seitenAufraeumen.forEach((f) => f());
  seitenAufraeumen = [];
  const pfad = location.hash.replace(/^#\/?/, "");
  const seite = SEITEN.find((s) => s.pfad === pfad && seiteSichtbar(s)) || SEITEN[0];
  $("nav-liste").replaceChildren(...SEITEN.filter(seiteSichtbar).map((s) =>
    h("li", {},
      h("a", { href: "#/" + s.pfad, "data-pfad": s.pfad, "aria-current": s.pfad === seite.pfad ? "page" : null },
        ic(s.icon), h("span", {}, s.titel),
        s.pfad === "aktivitaet" ? h("span", { class: "nav-zahl", id: "nav-fehler" }) : null))));

  document.title = (seite.pfad ? seite.titel + " – " : "") + "HomeX";

  const ziel = $("seite");
  if (!zustand.token) { ziel.replaceChildren(); return; }
  const inhalt = ({ "": seiteUebersicht, energie: seiteEnergie, klima: seiteKlima, automation: seiteAutomation,
    aktivitaet: seiteAktivitaet, system: seiteSystem })[seite.pfad]();
  ziel.replaceChildren(inhalt);
  fehlerZaehler();
  if (zustand.erststart) {
    ziel.classList.add("erststart");
    setTimeout(() => ziel.classList.remove("erststart"), 1500);
    zustand.erststart = false;
  }
}

function zeigenOffline() {
  $("seite").replaceChildren(h("div", { class: "leer-gross" },
    h("h3", {}, "HomeX ist gerade nicht erreichbar"),
    h("p", {}, "Prüfe, ob HomeX läuft. Das Dashboard verbindet sich automatisch neu.")));
}

function kopf(titel, unter, ...rechts) {
  return h("header", { class: "kopf" },
    h("div", {}, h("h1", {}, titel), unter ? h("p", { class: "kopf-unter" }, unter) : null),
    rechts.length ? h("div", { class: "kopf-rechts" }, rechts) : null);
}

function abschnitt(titel, neben, ...inhalt) {
  return h("section", { class: "abschnitt" },
    h("div", { class: "abschnitt-kopf" }, h("h2", {}, titel), neben ? h("span", { class: "neben" }, neben) : null),
    inhalt);
}

// ----------------------------------------------------------------- Übersicht

function gruss() {
  const s = new Date().getHours();
  return s < 5 ? "Gute Nacht" : s < 11 ? "Guten Morgen" : s < 17 ? "Guten Tag" : s < 22 ? "Guten Abend" : "Gute Nacht";
}

function seiteUebersicht() {
  const datum = new Date().toLocaleDateString("de-DE", { weekday: "long", day: "numeric", month: "long" });
  const kopfEl = h("header", { class: "kopf" },
    h("div", {}, h("p", { class: "kopf-ueber" }, zustand.info?.haus || "Mein Zuhause"), h("h1", {}, gruss()),
      h("p", { class: "kopf-unter" }, datum)),
    h("div", { class: "kopf-rechts" }, wetterChip()));

  const klimas = nachTyp("CLIMATE");
  const sonstige = [...zustand.geraete.values()]
    .filter((d) => !NICHT_IN_RAEUMEN.has(d.type) && d.type !== "CLIMATE")
    .sort((a, b) => (a.room || "~").localeCompare(b.room || "~", "de") || a.name.localeCompare(b.name, "de"));

  const teile = [kopfEl, solarKarte()];
  teile.push(abschnitt("Klima", klimaZusammenfassung(klimas),
    klimas.length ? h("div", { class: "klimas" }, klimas.map((d) => klimaKarte(d, true)))
      : h("div", { class: "leer-gross" }, h("h3", {}, "Noch keine Klimaanlage verbunden"),
          h("p", {}, "Richte die ConnectLife-Bridge ein (installation/klima-bridge.sh), dann erscheinen deine Klimas hier."))));
  if (sonstige.length) teile.push(abschnitt("Geräte", null, h("div", { class: "panel liste" }, sonstige.map(geraetZeile))));
  return h("div", { class: "uebersicht" }, teile);
}

function klimaZusammenfassung(klimas) {
  const el = h("span");
  const zaehlen = () => {
    const an = klimas.filter((d) => zustand.geraete.get(d.id)?.states.power === true).length;
    el.textContent = klimas.length ? (an ? `${an} von ${klimas.length} laufen` : "alle aus") : "";
  };
  klimas.forEach((d) => beobachten(d.id, zaehlen));
  return el;
}

/** Die große Solar-Karte oben in der Übersicht: was gerade reinkommt und wohin es geht. */
function solarKarte() {
  const karte = h("a", { class: "solar-karte", href: "#/energie", "aria-label": "Energie – Details öffnen" });
  const zeichnen = () => {
    const g = solar();
    const s = g?.states;
    const ws = wetter()?.states;
    if (!s || ["WARTET", "START"].includes(s.status)) {
      karte.replaceChildren(
        h("div", { class: "solar-kopf" }, h("span", { class: "solar-titel" }, ic("sonne"), "Solaranlage")),
        h("p", { class: "solar-warten" }, !g ? "Noch keine Solaranlage verbunden."
          : "Wartet auf die Verbindung zum Wechselrichter. Sobald Modbus aktiv ist: homex befehl sma quelle modbus"));
      return;
    }
    const offline = s.status === "OFFLINE";
    const pv = s.pv_w ?? s.ac_w;
    const netz = istZahl(s.netzbezug_w) || istZahl(s.einspeisung_w) ? (s.netzbezug_w || 0) - (s.einspeisung_w || 0) : null;
    const batt = (s.batterie_laden_w || 0) - (s.batterie_entladen_w || 0);
    const [pz, pe] = leistung(pv);
    const unter = [];
    if (istZahl(s.ertrag_heute_kwh)) unter.push(`Heute ${zahl(s.ertrag_heute_kwh, 1)} kWh erzeugt`);
    if (ws && istZahl(ws.heute_pv_prognose_kwh)) unter.push(`Prognose ≈ ${ws.heute_pv_prognose_kwh} kWh`);
    if (offline) unter.unshift("Wechselrichter gerade nicht erreichbar");

    const kachel = (cls, sym, titel, wert, zusatz, balken) => h("div", { class: "solar-kachel " + cls },
      h("span", { class: "kachel-titel" }, ic(sym), titel),
      h("strong", {}, wert[0], h("small", {}, wert[1])),
      balken != null ? h("span", { class: "balken", style: { "--wert": Math.max(0, Math.min(100, balken)) + "%" } }) : null,
      h("span", { class: "kachel-zusatz" }, zusatz || "\u00a0"));

    const eigen = istZahl(s.verbrauch_w) && s.verbrauch_w > 0 && istZahl(s.netzbezug_w)
      ? Math.max(0, Math.min(100, 100 * (1 - s.netzbezug_w / s.verbrauch_w))) : null;

    karte.classList.toggle("offline", offline);
    karte.replaceChildren(
      h("div", { class: "solar-kopf" },
        h("span", { class: "solar-titel" }, ic("sonne"), "Solar jetzt"),
        h("span", { class: "solar-mehr" }, "Details")),
      h("div", { class: "solar-gross" }, h("strong", {}, pz), h("span", {}, pe)),
      h("p", { class: "solar-unter" }, unter.join(" · ") || "\u00a0"),
      h("div", { class: "solar-kacheln" },
        kachel("haus", "haus", "Hausverbrauch", leistung(s.verbrauch_w),
          eigen != null ? `${zahl(eigen)} % aus eigenem Strom` : null),
        kachel("batterie", "batterie", "Batterie", istZahl(s.batterie_soc) ? [zahl(s.batterie_soc), "%"] : ["–", ""],
          Math.abs(batt) < 20 ? "ruht" : (batt > 0 ? "lädt " : "entlädt ") + leistung(batt).join(" "),
          istZahl(s.batterie_soc) ? s.batterie_soc : null),
        kachel("netz", "netz", "Netz", leistung(netz),
          !istZahl(netz) ? null : Math.abs(netz) < 20 ? "kein Austausch" : netz > 0 ? "Bezug" : "Einspeisung")));
  };
  const g = solar(), w = wetter();
  if (g) beobachten(g.id, zeichnen);
  if (w) beobachten(w.id, zeichnen);
  zeichnen();
  return karte;
}

function wetterChip() {
  const w = wetter();
  if (!w) return null;
  const chip = h("a", { class: "chip", href: "#/energie" });
  beobachten(w.id, (g) => {
    const s = g.states;
    if (s.status !== "OK" || !istZahl(s.temperatur_c)) {
      chip.replaceChildren(ic("bedeckt"), h("span", {}, s.status === "WARTET" ? "Wetter: Standort fehlt" : "Wetter nicht verfügbar"));
      return;
    }
    chip.replaceChildren(ic(s.symbol || "bedeckt"), h("strong", {}, zahl(s.temperatur_c, 1) + "°"), h("span", {}, s.wetter || ""));
  });
  return chip;
}

function szeneKnopf(d) {
  const b = h("button", { class: "szene", type: "button", "data-symbol": d.states.symbol || "stern" },
    h("span", { class: "szene-hex" }, h("span", { html: icon(d.states.symbol || "stern") })),
    h("span", {}, d.name));
  b.addEventListener("click", () => szeneStarten(d.id, b));
  return b;
}

async function szeneStarten(id, el) {
  el?.classList.add("laeuft");
  const ok = await senden(id, "ausfuehren", 1);
  el?.classList.remove("laeuft");
  const d = zustand.geraete.get(id);
  if (ok) melden(`Szene „${d.name}“: ${d.states.ergebnis || "ausgeführt"}`, true);
}

function art(d) {
  return ({ LIGHT: "licht", SWITCH: "steckdose", SHUTTER: "rollladen", CLIMATE: "klima", SENSOR: "sensor" })[d.type] || "geraet";
}

/** Eine Zeile für Lichter, Steckdosen, Rollläden usw. – Schalter rechts, Antippen öffnet die Details. */
function geraetZeile(d) {
  const a = art(d);
  const name = h("strong"), info = h("span");
  const umschaltbar = d.type === "LIGHT" || d.type === "SWITCH";
  const schalter = umschaltbar ? h("button", { class: "schalter", type: "button", role: "switch" }) : null;
  const zeile = h("div", { class: "geraet-zeile", "data-id": d.id },
    h("button", { class: "geraet-oeffnen", type: "button" }, h("span", { class: "geraet-symbol", html: icon(a === "sensor" ? "sensor" : a) }),
      h("span", { class: "geraet-text" }, name, info)),
    schalter);
  zeile.querySelector(".geraet-oeffnen").addEventListener("click", () => blattOeffnen(d.id));
  schalter?.addEventListener("click", () => senden(d.id, "power", !(zustand.geraete.get(d.id).states.power === true), zeile));
  beobachten(d.id, (g) => {
    if (!g) return;
    const s = g.states;
    let text = "";
    if (umschaltbar) text = s.power === true ? (istZahl(s.helligkeit) ? `an, ${s.helligkeit} %` : "an") : "aus";
    else if (g.type === "SHUTTER") {
      const pos = istZahl(s.position) ? s.position : 100;
      text = s.bewegung && s.bewegung !== "steht" ? (s.bewegung === "faehrt_auf" ? "fährt hoch" : "fährt runter")
        : pos >= 100 ? "offen" : pos <= 0 ? "geschlossen" : `${pos} % offen`;
    } else {
      const z = Object.entries(s).find(([, v]) => istZahl(v));
      text = z ? `${z[0].replace(/_/g, " ")} ${zahl(z[1], 1)}` : "";
    }
    name.textContent = g.name;
    info.textContent = [g.room, text].filter(Boolean).join(" · ");
    zeile.classList.toggle("an", s.power === true);
    if (schalter) {
      schalter.setAttribute("aria-checked", String(s.power === true));
      schalter.setAttribute("aria-label", `${g.name} ${s.power === true ? "ausschalten" : "einschalten"}`);
    }
  });
  return zeile;
}

function feedEintrag(a) {
  const d = isoZuMs(a.zeit) ? new Date(isoZuMs(a.zeit)) : null;
  const an = /eingeschaltet|aktiviert/.test(a.text);
  return h("li", { "data-art": a.art, "data-an": String(an) },
    h("time", { datetime: a.zeit }, d ? uhrzeit(d) : ""), h("span", { class: "punkt", "aria-hidden": "true" }),
    h("span", { class: "text" }, a.text, a.quelle ? h("span", { class: "quelle" }, a.quelle) : null));
}

// ----------------------------------------------------------------- Energie

function seiteEnergie() {
  const s = solar(), w = wetter();
  const wartet = !s || ["WARTET", "START"].includes(s.states.status);
  const teile = [kopf("Energie", s ? "Solaranlage, Batterie und Hausverbrauch" : "Noch keine Solaranlage verbunden", wetterChip())];

  if (wartet) {
    teile.push(h("div", { class: "wartet-hinweis" }, ic("warnung"),
      h("div", {}, h("strong", {}, s ? "Die Solaranlage wartet auf ihren Zugang" : "Kein Wechselrichter angemeldet"),
        h("p", {}, s?.states.fehler ? wannGross(s.states.fehler) + ". " : "",
          "Sobald Modbus am Wechselrichter aktiv ist, tippe in der HomeX-Konsole ", h("code", {}, "sma quelle modbus"),
          ". Mit Cloud-Zugang: ", h("code", {}, "sma quelle cloud"), ". Danach erscheinen hier die Live-Werte."))));
  }

  // Energiefluss
  const svg = flussAufbauen();
  const fakten = h("dl", { class: "kennzahlen" });
  teile.push(h("section", { class: "abschnitt panel energie-oben" }, svg, fakten));
  const zeichnen = () => flussZeichnen(svg, fakten);
  if (s) beobachten(s.id, zeichnen); else zeichnen();

  // Diagramme
  const verlaufBox = h("div");
  const prognoseBox = h("div");
  teile.push(h("section", { class: "abschnitt diagramme" },
    h("div", { class: "panel" }, h("h2", {}, "Heute"), verlaufBox),
    h("div", { class: "panel" }, h("h2", {}, "Sonnenprognose heute"), prognoseBox)));
  requestAnimationFrame(() => { verlaufZeichnen(verlaufBox, s); prognoseZeichnen(prognoseBox); });
  const timer = setInterval(() => verlaufZeichnen(verlaufBox, solar()), 60_000);
  seitenAufraeumen.push(() => clearInterval(timer));
  if (w) beobachten(w.id, () => prognoseBox.isConnected && prognoseZeichnen(prognoseBox));

  // Wetter
  if (w) teile.push(abschnitt("Wetter", null, wetterPanel(w)));
  return h("div", {}, teile);
}

const KNOTEN = {
  sonne: { x: 70, y: 66, r: 44, name: "Sonne", sym: "sonne" },
  batterie: { x: 70, y: 236, r: 44, name: "Batterie", sym: "batterie" },
  netz: { x: 350, y: 150, r: 44, name: "Netz", sym: "netz" },
  haus: { x: 210, y: 150, r: 66, name: "Haus", sym: "haus" },
};

function hexPfad(x, y, r) {
  const p = [];
  for (let i = 0; i < 6; i++) {
    const a = (Math.PI / 180) * (60 * i - 90);
    p.push(`${(x + r * Math.cos(a)).toFixed(1)},${(y + r * Math.sin(a)).toFixed(1)}`);
  }
  return `M${p.join("L")}Z`;
}

function flussAufbauen() {
  const NS = "http://www.w3.org/2000/svg";
  const s = (tag, attrs) => { const e = document.createElementNS(NS, tag); for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v); return e; };
  const svg = s("svg", { viewBox: "0 0 420 300", class: "fluss", role: "img", "aria-label": "Energiefluss im Haus" });
  const teile = {};
  const hk = KNOTEN.haus;
  for (const k of ["sonne", "batterie", "netz"]) {
    const n = KNOTEN[k];
    const d = `M${n.x},${n.y}L${hk.x},${hk.y}`;
    svg.append(s("path", { d, class: "leitung" }));
    teile[k + "Strom"] = s("path", { d, class: "strom " + k });
    svg.append(teile[k + "Strom"]);
  }
  for (const [k, n] of Object.entries(KNOTEN)) {
    const g = s("g", { class: "knoten " + k });
    g.append(s("path", { d: hexPfad(n.x, n.y, n.r), class: "knoten-form" }));
    const wert = s("text", { x: n.x, y: n.y + (k === "haus" ? 6 : 4), class: "wert" });
    const name = s("text", { x: n.x, y: n.y + (k === "haus" ? 30 : 23), class: "name" });
    name.textContent = n.name;
    g.append(wert, name);
    teile[k] = g; teile[k + "Wert"] = wert;
    svg.append(g);
  }
  teile.netzZusatz = s("text", { x: KNOTEN.netz.x, y: KNOTEN.netz.y + 66, class: "zusatz" });
  teile.batterieZusatz = s("text", { x: KNOTEN.batterie.x, y: KNOTEN.batterie.y - 56, class: "zusatz" });
  svg.append(teile.netzZusatz, teile.batterieZusatz);
  svg._teile = teile;
  return svg;
}

function flussZeichnen(svg, fakten) {
  const t = svg._teile;
  const g = solar();
  const s = g && !["WARTET", "START"].includes(g.states.status) ? g.states : null;
  svg.classList.toggle("ruht", !s);
  const wert = (el, [z, e]) => {
    el.textContent = z;
    if (e) { const sp = document.createElementNS("http://www.w3.org/2000/svg", "tspan"); sp.setAttribute("font-size", ".55em"); sp.setAttribute("dx", "3"); sp.textContent = e; el.append(sp); }
  };
  const pv = s ? (s.pv_w ?? s.ac_w) : null;
  const netz = s && (istZahl(s.netzbezug_w) || istZahl(s.einspeisung_w)) ? (s.netzbezug_w || 0) - (s.einspeisung_w || 0) : null;
  const batt = s ? (s.batterie_laden_w || 0) - (s.batterie_entladen_w || 0) : 0;
  wert(t.sonneWert, leistung(pv));
  wert(t.hausWert, leistung(s?.verbrauch_w));
  wert(t.netzWert, leistung(netz));
  wert(t.batterieWert, s && istZahl(s.batterie_soc) ? [zahl(s.batterie_soc), "%"] : ["–", ""]);
  t.netzZusatz.textContent = !istZahl(netz) || Math.abs(netz) < 20 ? "" : netz > 0 ? "Bezug" : "Einspeisung";
  t.batterieZusatz.textContent = Math.abs(batt) < 20 ? "" : (batt > 0 ? "lädt " : "entlädt ") + leistung(batt).join(" ");
  const strom = (pfad, w, rueck) => {
    const an = istZahl(w) && Math.abs(w) >= 20;
    pfad.classList.toggle("an", an);
    pfad.classList.toggle("rueck", !!rueck);
    if (an) pfad.style.setProperty("--dauer", Math.max(0.35, 2.4 - Math.abs(w) / 2500).toFixed(2) + "s");
  };
  t.sonne.classList.toggle("aktiv", istZahl(pv) && pv >= 20);
  t.netz.classList.toggle("aktiv", istZahl(netz) && Math.abs(netz) >= 20);
  t.batterie.classList.toggle("aktiv", Math.abs(batt) >= 20);
  strom(t.sonneStrom, pv, false);
  strom(t.netzStrom, netz, istZahl(netz) && netz < 0);
  strom(t.batterieStrom, batt, batt > 0);

  const ws = wetter()?.states;
  const zeilen = [];
  if (s && istZahl(s.ertrag_heute_kwh)) zeilen.push(["Heute erzeugt", zahl(s.ertrag_heute_kwh, 1), "kWh", "var(--sonne)"]);
  if (s && istZahl(s.verbrauch_w) && s.verbrauch_w > 0 && istZahl(s.netzbezug_w))
    zeilen.push(["Eigenversorgung jetzt", zahl(Math.max(0, Math.min(100, 100 * (1 - s.netzbezug_w / s.verbrauch_w)))), "%", "var(--violett)"]);
  if (ws && istZahl(ws.heute_pv_prognose_kwh)) zeilen.push(["Prognose heute", "≈ " + ws.heute_pv_prognose_kwh, "kWh", "var(--sonne)"]);
  if (ws && istZahl(ws.morgen_pv_prognose_kwh)) zeilen.push(["Prognose morgen", "≈ " + ws.morgen_pv_prognose_kwh, "kWh", "var(--kante-hell)"]);
  if (s && istZahl(s.ertrag_gesamt_kwh)) zeilen.push(["Gesamt erzeugt", zahl(s.ertrag_gesamt_kwh / 1000, 2), "MWh", "var(--kante-hell)"]);
  if (s) zeilen.push(["Wechselrichter", s.status === "OK" ? (istZahl(s.temperatur_c) ? zahl(s.temperatur_c, 1) : "OK") : String(s.status).toLowerCase(),
    s.status === "OK" && istZahl(s.temperatur_c) ? "°C" : "", s.status === "OK" ? "var(--kante-hell)" : "var(--heiss)"]);
  if (!zeilen.length) zeilen.push(["Live-Werte", "–", "", "var(--kante-hell)"]);
  fakten.replaceChildren(...zeilen.map(([dt, dd, e, f]) =>
    h("div", { style: { "--farbe": f } }, h("dt", {}, dt), h("dd", {}, dd, e ? h("small", {}, e) : null))));
}

async function verlaufZeichnen(box, s) {
  const mitternacht = new Date(); mitternacht.setHours(0, 0, 0, 0);
  const von = mitternacht.getTime(), bis = von + 24 * 3600e3;
  let reihen = [];
  if (s) {
    try {
      const holen = (key) => api(`/api/history?id=${encodeURIComponent(s.id)}&key=${key}&stunden=24`)
        .then((r) => r.punkte.filter((p) => p[0] >= von));
      const [pv, verbrauch] = await Promise.all([holen("pv_w"), holen("verbrauch_w")]);
      reihen = [
        { name: "PV-Erzeugung", kurz: "PV", farbe: "var(--reihe-pv)", punkte: pv },
        { name: "Hausverbrauch", kurz: "Verbrauch", farbe: "var(--reihe-verbrauch)", punkte: verbrauch, flaeche: false },
      ];
    } catch {}
  }
  const hatDaten = reihen.some((r) => r.punkte.length);
  const legende = h("div", { class: "legende" },
    reihen.length ? reihen.map((r) => h("span", {}, h("i", { style: { background: r.farbe } }), r.name)) : null,
    hatDaten ? h("button", { class: "knopf klein tabelle-umschalten", type: "button", onclick: () => tabelleUmschalten(box, reihen) }, "Als Tabelle") : null);
  const diagramm = h("div");
  box.replaceChildren(legende, diagramm);
  Charts.linien(diagramm, reihen.length ? reihen : [], {
    von, bis, einheit: "W", format: (v) => leistung(v).join(" "), titel: "Leistung heute",
    leer: s ? "Noch keine Messwerte – sie erscheinen, sobald die Anlage Daten liefert" : "Keine Solaranlage verbunden",
  });
}

function tabelleUmschalten(box, reihen) {
  const vorhanden = box.querySelector(".tabelle-box");
  if (vorhanden) { vorhanden.remove(); return; }
  const zeiten = [...new Set(reihen.flatMap((r) => r.punkte.map((p) => p[0])))].sort((a, b) => b - a).slice(0, 120);
  const wert = (r, t) => { const p = r.punkte.find((x) => x[0] === t); return p ? leistung(p[1]).join(" ") : "–"; };
  box.append(h("div", { class: "tabelle-box" }, h("table", { class: "daten" },
    h("thead", {}, h("tr", {}, h("th", {}, "Uhrzeit"), reihen.map((r) => h("th", { class: "zahl" }, r.name)))),
    h("tbody", {}, zeiten.map((t) => h("tr", {}, h("td", {}, uhrzeit(new Date(t))), reihen.map((r) => h("td", { class: "zahl" }, wert(r, t)))))))));
}

function prognoseZeichnen(box) {
  const w = wetter()?.states;
  box.replaceChildren();
  if (!w || !Array.isArray(w.strahlung_heute)) {
    box.append(h("p", { class: "leer" }, w?.status === "WARTET"
      ? "Trag deinen Standort in homex.properties ein, dann zeigt HomeX hier, wann heute die Sonne am stärksten scheint."
      : "Keine Wetterdaten. Dafür braucht es das Plugin Wetter und einen Standort in homex.properties."));
    return;
  }
  const unter = istZahl(w.heute_pv_prognose_kwh)
    ? `Erwarteter Ertrag ≈ ${w.heute_pv_prognose_kwh} kWh (grobe Schätzung)`
    : istZahl(w.heute_sonnenenergie_kwh_m2) ? `${w.heute_sonnenenergie_kwh_m2} kWh Sonnenenergie pro m²` : "";
  const d = h("div");
  box.append(h("p", { class: "leer", style: { marginBottom: "10px" } }, unter), d);
  Charts.saeulen(d, w.strahlung_heute, { farbe: "var(--reihe-pv)", name: "Einstrahlung", einheit: "W/m²",
    jetzt: new Date().getHours(), titel: "Sonneneinstrahlung je Stunde" });
}

function wetterPanel(w) {
  const box = h("div", { class: "panel" });
  beobachten(w.id, (g) => {
    const s = g.states;
    if (s.status !== "OK") {
      box.replaceChildren(h("p", { class: "leer" }, s.fehler || "Noch keine Wetterdaten."));
      return;
    }
    const tag = (titel, p) => h("div", {}, h("dt", {}, titel),
      h("dd", {}, h("span", { html: icon(s[p + "symbol"] || "bedeckt"), style: { display: "contents" } }), " ",
        `${s[p + "min_c"] ?? "–"}° / ${s[p + "max_c"] ?? "–"}°`, istZahl(s[p + "regen_prozent"]) ? h("small", {}, s[p + "regen_prozent"] + " % Regen") : null));
    box.replaceChildren(h("dl", { class: "kennzahlen" },
      h("div", { style: { "--farbe": "var(--kuehl)" } }, h("dt", {}, "Jetzt"), h("dd", {}, zahl(s.temperatur_c, 1) + "°", h("small", {}, s.wetter))),
      h("div", { style: { "--farbe": "var(--kante-hell)" } }, h("dt", {}, "Gefühlt"), h("dd", {}, zahl(s.gefuehlt_c, 1) + "°", h("small", {}, `Wind ${s.wind_kmh ?? "–"} km/h`))),
      tag("Heute", "heute_"), tag("Morgen", "morgen_"),
      h("div", { style: { "--farbe": "var(--sonne)" } }, h("dt", {}, "Sonne"), h("dd", {}, s.sonnenaufgang || "–", h("small", {}, "bis " + (s.sonnenuntergang || "–")))),
      h("div", { style: { "--farbe": "var(--kante-hell)" } }, h("dt", {}, "Aktualisiert"), h("dd", {}, s.aktualisiert || "–", h("small", {}, "Open-Meteo")))));
  });
  return box;
}

// ----------------------------------------------------------------- Klima

function seiteKlima() {
  const klimas = nachTyp("CLIMATE");
  const teile = [kopf("Klima", klimas.length ? `${klimas.length} Klimaanlage${klimas.length === 1 ? "" : "n"}` : "Noch keine Klimaanlage verbunden", wetterChip())];
  if (!klimas.length) {
    teile.push(h("div", { class: "leer-gross" }, h("h3", {}, "So kommen deine Klimas hierher"),
      h("p", {}, "1. Starte die ConnectLife-Bridge (homex-plugins/hisense-klima/bridge/docker-compose.yml) mit deinen ConnectLife-Zugangsdaten."),
      h("p", {}, "2. Trag ihre Adresse in plugins/HisenseKlima/config.properties bei bridge.url ein und tippe in der Konsole reload."),
      h("p", {}, "HomeX findet die Geräte dann selbst und legt sie mit ihren Räumen an.")));
  } else {
    teile.push(h("div", { class: "klimas" }, klimas.map(klimaKarte)));
  }
  return h("div", {}, teile);
}

const sollWartet = new Map();

/** kompakt = in der Übersicht: ohne Lüfter/Extras; Antippen des Namens öffnet alle Einstellungen. */
function klimaKarte(d, kompakt = false) {
  const titel = h("h3"), info = h("p");
  const ist = h("div", { class: "klima-ist", "aria-label": "Raumtemperatur" });
  const sollWert = h("output", { "aria-live": "polite" });
  const minus = h("button", { class: "rund-knopf", type: "button", "aria-label": "Temperatur senken", html: icon("minus"), onclick: () => soll(d.id, -1) });
  const plus = h("button", { class: "rund-knopf", type: "button", "aria-label": "Temperatur erhöhen", html: icon("plus"), onclick: () => soll(d.id, +1) });
  const schalter = h("button", { class: "schalter", type: "button", role: "switch" });
  schalter.addEventListener("click", () => senden(d.id, "power", !(zustand.geraete.get(d.id).states.power === true), karte));
  const modi = MODI.map(([w, t]) => h("button", { type: "button", "data-wert": w, style: { "--farbe": MODUS_FARBE[w] }, onclick: () => senden(d.id, "modus", w, karte) }, t));
  const luefter = h("select", { "aria-label": "Lüfterstufe", onchange: (e) => senden(d.id, "luefter", e.target.value, karte) },
    LUEFTER.map(([w, t]) => h("option", { value: w }, t)));
  const extras = ["eco", "turbo", "leise"].map((k) => {
    const sw = h("button", { class: "schalter", type: "button", role: "switch", "aria-label": k });
    sw.addEventListener("click", () => senden(d.id, k, !(zustand.geraete.get(d.id).states[k] === true), karte));
    return [k, sw];
  });

  const karte = h("article", { class: "klima-karte" + (kompakt ? " kompakt" : ""), "data-id": d.id },
    h(kompakt ? "button" : "div", { class: "klima-name", type: kompakt ? "button" : null, onclick: kompakt ? () => blattOeffnen(d.id) : null }, titel, info), ist,
    h("div", { class: "klima-soll" }, minus, sollWert, plus),
    h("div", { class: "klima-schalter" }, schalter),
    h("div", { class: "klima-modi segmente", role: "group", "aria-label": "Modus" }, modi),
    h("label", { class: "klima-luefter" }, "Lüfter", luefter),
    h("div", { class: "klima-extras" }, extras.map(([k, sw]) => h("label", {}, sw, ({ eco: "Eco", turbo: "Turbo", leise: "Leise" })[k]))));

  beobachten(d.id, (g) => {
    if (!g) return;
    const s = g.states, online = s.online !== false, an = s.power === true;
    karte.classList.toggle("an", an && online);
    karte.classList.toggle("offline", !online);
    karte.dataset.modus = s.modus || "";
    titel.textContent = g.name.replace(/^Klima\s+/i, "");
    info.textContent = !online ? "nicht erreichbar" : an ? (MODI.find((m) => m[0] === s.modus)?.[1] || s.modus || "an") : "aus";
    ist.replaceChildren(istZahl(s.ist_temp) ? zahl(s.ist_temp) : "–", h("small", {}, "°C"));
    const sollT = sollWartet.get(g.id)?.wert ?? s.solltemp;
    sollWert.replaceChildren(istZahl(sollT) ? zahl(sollT) + "°" : "–", h("small", {}, "Soll"));
    minus.disabled = !online || !istZahl(sollT) || sollT <= 16;
    plus.disabled = !online || !istZahl(sollT) || sollT >= 30;
    schalter.setAttribute("aria-checked", String(an));
    schalter.setAttribute("aria-label", `${titel.textContent} ${an ? "ausschalten" : "einschalten"}`);
    schalter.style.setProperty("--an", MODUS_FARBE[s.modus] || "var(--kuehl)");
    schalter.disabled = !online;
    modi.forEach((b) => { b.setAttribute("aria-pressed", String(b.dataset.wert === s.modus)); b.disabled = !online; });
    if (s.luefter && document.activeElement !== luefter) luefter.value = s.luefter;
    luefter.disabled = !online;
    extras.forEach(([k, sw]) => { sw.setAttribute("aria-checked", String(s[k] === true)); sw.disabled = !online || !(k in s); sw.closest("label").hidden = !(k in s); });
  });
  return karte;
}

/** Mehrere Klicks auf +/− werden gesammelt und als ein Befehl gesendet (die Klima-Cloud ist langsam). */
function soll(id, schritt, el) {
  const d = zustand.geraete.get(id);
  const w = sollWartet.get(id);
  const basis = w ? w.wert : d.states.solltemp;
  if (!istZahl(basis)) return;
  const neu = Math.max(16, Math.min(30, basis + schritt));
  if (w) clearTimeout(w.timer);
  const timer = setTimeout(() => { sollWartet.delete(id); senden(id, "solltemp", neu, el); }, 700);
  sollWartet.set(id, { wert: neu, timer });
  melde(id);
}

// ----------------------------------------------------------------- Automation

const VERWALTUNG = "automation";
const regelGeraete = () => nachTyp("AUTOMATION").filter((d) => d.states.verwaltung !== true);
const idOhne = (id, vorsilbe) => id.startsWith(vorsilbe) ? id.slice(vorsilbe.length) : id;
const TAGE = [["mo", "Mo"], ["di", "Di"], ["mi", "Mi"], ["do", "Do"], ["fr", "Fr"], ["sa", "Sa"], ["so", "So"]];
const SYMBOLE = ["stern", "sonne", "mond", "haus", "weg", "aus", "schnee", "feuer", "kaffee", "film"];
const WERT_NAMEN = {
  pv_w: "Solarleistung (W)", einspeisung_w: "Einspeisung (W)", netzbezug_w: "Netzbezug (W)", verbrauch_w: "Hausverbrauch (W)",
  batterie_soc: "Batterie-Ladestand (%)", batterie_laden_w: "Batterie lädt (W)", batterie_entladen_w: "Batterie entlädt (W)",
  ac_w: "Wechselrichter-Leistung (W)", ertrag_heute_kwh: "Ertrag heute (kWh)", ist_temp: "Raumtemperatur (°C)",
  solltemp: "Solltemperatur (°C)", power: "Eingeschaltet", modus: "Modus", temperatur_c: "Außentemperatur (°C)",
  gefuehlt_c: "Gefühlte Temperatur (°C)", wind_kmh: "Wind (km/h)", helligkeit: "Helligkeit (%)", position: "Position (%)",
};
const OPS = [[">", "größer als"], ["<", "kleiner als"], [">=", "mindestens"], ["<=", "höchstens"], ["=", "gleich"], ["!=", "ungleich"]];

/** Was man mit einem Gerät in einer Szene oder Regel machen kann: [Schlüssel, Name, Werte | {min,max} | null] */
function aktionenFuer(d) {
  const anAus = [["an", "An"], ["aus", "Aus"]];
  switch (d?.type) {
    case "CLIMATE": return [["power", "Ein/Aus", anAus], ["modus", "Modus", MODI], ["solltemp", "Solltemperatur", { min: 16, max: 30, einheit: "°C" }],
      ["luefter", "Lüfter", LUEFTER], ["eco", "Eco", anAus], ["turbo", "Turbo", anAus], ["leise", "Leise", anAus]];
    case "LIGHT": return [["power", "Ein/Aus", anAus], ...("helligkeit" in d.states ? [["helligkeit", "Helligkeit", { min: 1, max: 100, einheit: "%" }]] : [])];
    case "SWITCH": return [["power", "Ein/Aus", anAus]];
    case "SHUTTER": return [["position", "Position", { min: 0, max: 100, einheit: "% offen" }]];
    default: return Object.keys(d?.states || {}).map((k) => [k, WERT_NAMEN[k] || k, null]);
  }
}
const steuerbar = () => [...zustand.geraete.values()]
  .filter((d) => ["CLIMATE", "LIGHT", "SWITCH", "SHUTTER", "OTHER"].includes(d.type))
  .sort((a, b) => a.name.localeCompare(b.name, "de"));

async function verwalten(key, wert, element) {
  if (!zustand.geraete.get(VERWALTUNG)) { melden("Das Automation-Plugin läuft nicht."); return false; }
  element?.classList.add("wartet");
  try {
    const antwort = await api("/api/devices/" + VERWALTUNG, { method: "POST", body: JSON.stringify({ key, value: wert }) });
    uebernehmen(antwort.device);
    return true;
  } catch (err) {
    melden(err.status === 401 ? "Bitte melde dich erneut an." : (err.daten?.device?.states?.fehler || "Konnte nicht gespeichert werden."));
    return false;
  } finally {
    element?.classList.remove("wartet");
  }
}

function seiteAutomation() {
  const szenen = nachTyp("SCENE");
  const regeln = regelGeraete().sort((a, b) => (b.states.aktiv === true) - (a.states.aktiv === true));
  const aktiv = regeln.filter((r) => r.states.aktiv === true).length;
  const kann = !!zustand.geraete.get(VERWALTUNG);
  const neu = (text, f) => h("button", { class: "knopf", type: "button", onclick: f, disabled: !kann }, ic("plus"), text);
  const teile = [kopf("Automation", `${szenen.length} Szene${szenen.length === 1 ? "" : "n"}, ${aktiv} von ${regeln.length} Regel${regeln.length === 1 ? "" : "n"} aktiv`,
    neu("Neue Szene", () => szenenEditor(null)), neu("Neue Regel", () => regelEditor(null)))];

  teile.push(abschnitt("Szenen", "Mehrere Geräte mit einem Klick", szenen.length
    ? h("div", { class: "szenen-gitter" }, szenen.map(szenenKarte))
    : h("div", { class: "leer-gross" }, h("h3", {}, "Noch keine Szenen"),
      h("p", {}, "Eine Szene schaltet mehrere Geräte auf einmal, z. B. „Alle Klimas aus“ oder „Abends heizen“."),
      h("button", { class: "knopf haupt", type: "button", style: { marginTop: "14px" }, disabled: !kann, onclick: () => szenenEditor(null) }, ic("plus"), "Szene anlegen"))));
  teile.push(abschnitt("Regeln", "HomeX handelt selbst", regeln.length
    ? h("div", { class: "regeln" }, regeln.map(regelZeile))
    : h("div", { class: "leer-gross" }, h("h3", {}, "Noch keine Regeln"),
      h("p", {}, "Eine Regel schaltet von selbst: zu einer Uhrzeit, bei Sonnenauf- oder -untergang oder wenn ein Wert eine Grenze erreicht. ",
        "Zum Beispiel: Wenn mehr als 2000 W eingespeist werden, die Klima im Wohnzimmer einschalten."),
      h("button", { class: "knopf haupt", type: "button", style: { marginTop: "14px" }, disabled: !kann, onclick: () => regelEditor(null) }, ic("plus"), "Regel anlegen"))));
  return h("div", {}, teile);
}

function bearbeitenKnoepfe(bearbeiten, loeschen) {
  return h("div", { class: "bearbeiten-knoepfe" },
    h("button", { class: "rund-knopf klein", type: "button", title: "Bearbeiten", "aria-label": "Bearbeiten", onclick: bearbeiten }, ic("stift")),
    h("button", { class: "rund-knopf klein gefahr", type: "button", title: "Löschen", "aria-label": "Löschen", onclick: loeschen }, ic("muell")));
}

function szenenKarte(d) {
  const info = h("p");
  const knopf = h("button", { class: "knopf", type: "button" }, ic("start"), "Ausführen");
  const karte = h("article", { class: "szenen-karte", "data-symbol": d.states.symbol },
    h("span", { class: "szene-hex", style: { "--farbe": szenenFarbe(d.states.symbol) } }, h("span", { html: icon(d.states.symbol || "stern") })),
    h("h3", {}, d.name), info,
    bearbeitenKnoepfe(() => szenenEditor(d.id), () => loeschenFragen("Szene", d, "szene-loeschen", idOhne(d.id, "szene-"))),
    knopf);
  karte.style.setProperty("--farbe", szenenFarbe(d.states.symbol));
  knopf.addEventListener("click", async () => {
    knopf.disabled = true; karte.classList.add("laeuft");
    await szeneStarten(d.id);
    knopf.disabled = false; karte.classList.remove("laeuft");
  });
  beobachten(d.id, (g) => {
    if (!g) return;
    const s = g.states;
    info.textContent = `${s.befehle} Aktion${s.befehle === 1 ? "" : "en"}` + (s.zuletzt ? `, zuletzt ${wannText(isoZuMs(s.zuletzt))}` : "");
  });
  return karte;
}

function szenenFarbe(sym) {
  return ({ sonne: "var(--sonne)", kaffee: "var(--sonne)", feuer: "var(--heiss)", aus: "var(--heiss)", weg: "var(--heiss)", schnee: "var(--kuehl)" })[sym] || "var(--messing)";
}

function regelZeile(d) {
  const titel = h("h3"), satz = h("p", { class: "regel-satz" }), info = h("div", { class: "regel-info" });
  const schalter = h("button", { class: "schalter", type: "button", role: "switch" });
  const testen = h("button", { class: "knopf klein", type: "button", title: "Führt die Aktion sofort einmal aus" }, ic("start"), "Testen");
  const zeile = h("article", { class: "regel", "data-id": d.id }, schalter, h("div", {}, titel, satz),
    h("div", { class: "regel-knoepfe" }, testen,
      bearbeitenKnoepfe(() => regelEditor(d.id), () => loeschenFragen("Regel", d, "regel-loeschen", idOhne(d.id, "regel-")))), info);
  schalter.addEventListener("click", () => senden(d.id, "aktiv", !(zustand.geraete.get(d.id).states.aktiv === true), zeile));
  testen.addEventListener("click", async () => {
    if (await senden(d.id, "ausfuehren", 1, zeile)) melden(`Regel „${d.name}“ wird ausgeführt. Was passiert, zeigt der Verlauf.`, true);
  });
  beobachten(d.id, (g) => {
    if (!g) return;
    const s = g.states, an = s.aktiv === true;
    zeile.classList.toggle("pausiert", !an);
    titel.textContent = g.name;
    satz.replaceChildren(h("b", {}, wannLesbar(s)), " → ", wasLesbar(s.was));
    schalter.setAttribute("aria-checked", String(an));
    schalter.setAttribute("aria-label", `Regel ${g.name} ${an ? "pausieren" : "aktivieren"}`);
    const teile = [];
    if (an && s.naechste) teile.push(h("span", {}, "Nächstes Mal " + s.naechste));
    if (s.zuletzt) teile.push(h("span", {}, `Zuletzt ${wannText(isoZuMs(s.zuletzt))} (${s.ergebnis || "ausgeführt"})`));
    if (an && s.hinweis) teile.push(h("span", { class: /fehlt|nicht da|keinen/.test(s.hinweis) ? "warn" : "" }, s.hinweis));
    if (!an) teile.push(h("span", {}, "Pausiert"));
    info.replaceChildren(...teile);
  });
  return zeile;
}

const wannGross = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : "");
const geraetName = (id) => zustand.geraete.get(id)?.name || id;

function tageLesbar(t) {
  const tage = (t || "").split(",").filter(Boolean);
  if (!tage.length || tage.length === 7) return "";
  if (tage.join() === "mo,di,mi,do,fr") return " werktags";
  if (tage.join() === "sa,so") return " am Wochenende";
  return " " + tage.map((x) => TAGE.find((d) => d[0] === x)?.[1] || x).join(", ");
}

/** "Um 06:30 werktags", "Wenn Einspeisung (W) bei Solaranlage größer als 2000 seit 10 min" */
function wannLesbar(s) {
  if (s.e_uhrzeit) return "Um " + s.e_uhrzeit + tageLesbar(s.e_tage);
  if (s.e_sonne) return "Bei Sonnen" + s.e_sonne + (s.e_versatz ? ` ${s.e_versatz > 0 ? "+" : "−"}${Math.abs(s.e_versatz)} min` : "") + tageLesbar(s.e_tage);
  if (s.e_geraet) {
    const op = OPS.find((o) => o[0] === s.e_op)?.[1] || s.e_op;
    return `Wenn ${geraetName(s.e_geraet)}: ${WERT_NAMEN[s.e_key] || s.e_key} ${op} ${s.e_wert}` + (s.e_dauer ? ` (seit ${s.e_dauer} min)` : "");
  }
  return wannGross(s.wann);
}

function aktionLesbar(a) {
  if (a.startsWith("szene ")) return "Szene „" + a.slice(6) + "“";
  const [ziel, key, ...rest] = a.split(/\s+/);
  const wert = rest.join(" ");
  const d = zustand.geraete.get(ziel);
  const name = ziel === "typ:klima" ? "Alle Klimas" : geraetName(ziel);
  const def = aktionenFuer(d || { type: ziel === "typ:klima" ? "CLIMATE" : "" }).find((x) => x[0] === key);
  const wertText = Array.isArray(def?.[2]) ? (def[2].find((w) => w[0] === wert)?.[1] || wert) : wert + (def?.[2]?.einheit ? " " + def[2].einheit : "");
  return key === "power" ? `${name} ${wert === "an" ? "einschalten" : "ausschalten"}` : `${name}: ${def?.[1] || key} ${wertText}`;
}
const wasLesbar = (was) => (was || "").split(";").map((a) => a.trim()).filter(Boolean).map(aktionLesbar).join(", ");

function loeschenFragen(art, d, key, id) {
  editorOeffnen(`${art} löschen?`, d.name, "muell", [h("p", {}, `„${d.name}“ wird gelöscht. Das lässt sich nicht rückgängig machen.`)],
    `${art} löschen`, async (knopf) => {
      if (await verwalten(key, id, knopf)) { melden(`${art} „${d.name}“ gelöscht.`, true); return true; }
      return false;
    }, true);
}

/** Öffnet das Seitenblatt als Formular. speichern() liefert true, wenn es geschlossen werden darf. */
function editorOeffnen(titel, unter, symbol, teile, knopfText, speichern, gefahr = false) {
  blattBeobachter = null;
  $("blatt-titel").textContent = titel;
  $("blatt-unter").textContent = unter || "";
  $("blatt-symbol").replaceChildren(h("span", { class: "szene-hex", style: { "--farbe": gefahr ? "var(--heiss)" : "var(--messing)" } }, h("span", { html: icon(symbol) })));
  const form = h("form", { class: "editor", novalidate: true }, teile);
  const knopf = h("button", { class: "knopf " + (gefahr ? "gefahr-voll" : "haupt"), type: "submit" }, knopfText);
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    knopf.disabled = true;
    try { if (await speichern(knopf)) $("blatt").close(); } finally { knopf.disabled = false; }
  });
  $("blatt-inhalt").replaceChildren(form);
  $("blatt-fuss").replaceChildren(h("div", { class: "editor-fuss" },
    h("button", { class: "knopf", type: "button", onclick: () => $("blatt").close() }, "Abbrechen"), knopf));
  knopf.setAttribute("form", "");
  knopf.addEventListener("click", (e) => { e.preventDefault(); form.requestSubmit(); });
  if (!$("blatt").open) $("blatt").showModal();
}

const feld = (titel, ...inhalt) => h("label", { class: "feld" }, h("span", { class: "feld-titel" }, titel), inhalt);
const auswahl = (optionen, wert, attrs = {}) => {
  const s = h("select", attrs, optionen.map(([w, t]) => h("option", { value: w }, t)));
  if (wert != null) s.value = String(wert);
  return s;
};

function tageWahl(start) {
  const gewaehlt = new Set((start || "mo,di,mi,do,fr,sa,so").split(",").filter(Boolean));
  const knoepfe = TAGE.map(([w, t]) => h("button", { type: "button", "aria-pressed": String(gewaehlt.has(w)),
    onclick: (e) => { gewaehlt.has(w) ? gewaehlt.delete(w) : gewaehlt.add(w); e.currentTarget.setAttribute("aria-pressed", String(gewaehlt.has(w))); } }, t));
  const el = h("div", { class: "segmente tage" }, knoepfe);
  el.wert = () => {
    const t = TAGE.map((x) => x[0]).filter((x) => gewaehlt.has(x));
    if (t.length === 7) return "";
    if (t.join() === "mo,di,mi,do,fr") return "werktags";
    if (t.join() === "sa,so") return "wochenende";
    return t.join(",");
  };
  el.leer = () => gewaehlt.size === 0;
  return el;
}

/** Eine Zeile "Gerät → Aktion → Wert". mitSzenen: in Regeln darf auch eine Szene gestartet werden. */
function aktionZeile(start, mitSzenen, entfernen) {
  const geraete = steuerbar();
  const klimas = geraete.filter((d) => d.type === "CLIMATE");
  const ziele = [
    ...(klimas.length > 1 ? [["typ:klima", "Alle Klimas"]] : []),
    ...geraete.map((d) => [d.id, d.name + (d.room && !d.name.includes(d.room) ? ` (${d.room})` : "")]),
    ...(mitSzenen ? nachTyp("SCENE").map((d) => ["szene:" + d.name, "Szene: " + d.name]) : []),
  ];
  let [ziel, key, ...rest] = (start || "").split(/\s+/);
  let wert = rest.join(" ");
  if (start?.startsWith("szene ")) { ziel = "szene:" + start.slice(6); key = null; }
  const zielWahl = auswahl(ziele, ziel || ziele[0]?.[0], { "aria-label": "Gerät" });
  const keyWahl = h("select", { "aria-label": "Was" });
  const wertBox = h("span", { class: "wert-box" });
  let wertEl = null;

  const zeichneWert = () => {
    const def = aktionenFuer(geraetFuer(zielWahl.value)).find((x) => x[0] === keyWahl.value);
    const art = def?.[2];
    if (Array.isArray(art)) wertEl = auswahl(art, art.some((w) => w[0] === wert) ? wert : art[0][0], { "aria-label": "Wert" });
    else if (art) wertEl = h("input", { type: "number", min: art.min, max: art.max, step: 1, value: wert !== "" && !isNaN(wert) ? wert : (art.min + art.max) / 2 | 0, "aria-label": "Wert", inputmode: "numeric" });
    else wertEl = h("input", { type: "text", value: wert, "aria-label": "Wert" });
    wertBox.replaceChildren(...[wertEl, art?.einheit ? h("span", { class: "einheit" }, art.einheit) : null].filter(Boolean));
  };
  const zeichneKey = () => {
    const istSzene = zielWahl.value.startsWith("szene:");
    keyWahl.hidden = wertBox.hidden = istSzene;
    if (istSzene) return;
    const defs = aktionenFuer(geraetFuer(zielWahl.value));
    keyWahl.replaceChildren(...defs.map(([k, t]) => h("option", { value: k }, t)));
    if (defs.some((x) => x[0] === key)) keyWahl.value = key;
    zeichneWert();
  };
  zielWahl.addEventListener("change", () => { key = null; wert = ""; zeichneKey(); });
  keyWahl.addEventListener("change", () => { key = keyWahl.value; wert = ""; zeichneWert(); });
  zeichneKey();

  const el = h("div", { class: "aktion-zeile" }, zielWahl, keyWahl, wertBox,
    h("button", { class: "rund-knopf klein", type: "button", "aria-label": "Aktion entfernen", title: "Entfernen", onclick: () => entfernen(el) }, ic("schliessen")));
  el.text = () => {
    if (zielWahl.value.startsWith("szene:")) return "szene " + zielWahl.value.slice(6);
    const w = String(wertEl?.value ?? "").trim();
    return w ? `${zielWahl.value} ${keyWahl.value} ${w}` : null;
  };
  return el;
}
const geraetFuer = (ziel) => ziel === "typ:klima" ? { type: "CLIMATE", states: {} } : zustand.geraete.get(ziel);

function aktionenListe(start, mitSzenen) {
  const liste = h("div", { class: "aktionen" });
  const entfernen = (el) => { if (liste.children.length > 1) el.remove(); };
  const dazu = (s) => liste.append(aktionZeile(s, mitSzenen, entfernen));
  (start.length ? start : [null]).forEach(dazu);
  const el = h("div", {}, liste,
    h("button", { class: "knopf klein", type: "button", style: { marginTop: "10px" }, onclick: () => dazu(null) }, ic("plus"), "Aktion hinzufügen"));
  el.texte = () => [...liste.children].map((z) => z.text());
  return el;
}

const nameOk = (n) => n && !/[|;\n]/.test(n);

function szenenEditor(id) {
  if (!steuerbar().length) { melden("Es gibt noch keine Geräte, die eine Szene schalten könnte."); return; }
  const d = id ? zustand.geraete.get(id) : null;
  const name = h("input", { type: "text", value: d?.name || "", maxlength: 40, placeholder: "z. B. Alle Klimas aus", required: true, autocomplete: "off" });
  let symbol = d?.states.symbol || "stern";
  const symbole = h("div", { class: "symbol-wahl" }, SYMBOLE.map((s) => h("button", { type: "button", "aria-pressed": String(s === symbol), "aria-label": s,
    html: icon(s), onclick: (e) => { symbol = s; symbole.querySelectorAll("button").forEach((b) => b.setAttribute("aria-pressed", String(b === e.currentTarget))); } })));
  const aktionen = aktionenListe((d?.states.liste || "").split("\n").filter(Boolean), false);
  editorOeffnen(d ? "Szene bearbeiten" : "Neue Szene", "Mehrere Geräte mit einem Klick", "stern",
    [feld("Name", name), h("div", { class: "feld" }, h("span", { class: "feld-titel" }, "Symbol"), symbole),
      h("div", { class: "feld" }, h("span", { class: "feld-titel" }, "Was soll passieren?"), aktionen)],
    d ? "Speichern" : "Szene anlegen", async (knopf) => {
      const n = name.value.trim();
      if (!nameOk(n)) { melden("Bitte einen Namen eingeben (ohne | und ;)."); name.focus(); return false; }
      const texte = aktionen.texte();
      if (texte.some((t) => !t)) { melden("Bei einer Aktion fehlt noch der Wert."); return false; }
      const ok = await verwalten("szene-speichern", [d ? idOhne(d.id, "szene-") : "", `${n} | ${symbol}`, ...texte].join("\n"), knopf);
      if (ok) melden(`Szene „${n}“ gespeichert.`, true);
      return ok;
    });
}

function regelEditor(id) {
  const d = id ? zustand.geraete.get(id) : null;
  const s = d?.states || {};
  const name = h("input", { type: "text", value: d?.name || "", maxlength: 40, placeholder: "z. B. Solarstrom zum Kühlen", required: true, autocomplete: "off" });
  let art = s.e_geraet ? "wert" : s.e_sonne ? "sonne" : "zeit";

  // Uhrzeit
  const uhr = h("input", { type: "time", value: s.e_uhrzeit || "07:00", required: true });
  const tageZeit = tageWahl(s.e_uhrzeit ? s.e_tage : null);
  // Sonne
  const sonne = auswahl([["untergang", "Sonnenuntergang"], ["aufgang", "Sonnenaufgang"]], s.e_sonne || "untergang");
  const versatz = h("input", { type: "number", step: 5, min: -240, max: 240, value: s.e_versatz || 0, inputmode: "numeric" });
  const tageSonne = tageWahl(s.e_sonne ? s.e_tage : null);
  // Messwert
  const quellen = [...zustand.geraete.values()].filter((g) => !["SCENE", "AUTOMATION"].includes(g.type)
    && Object.values(g.states).some((v) => istZahl(v) || typeof v === "boolean")).sort((a, b) => a.name.localeCompare(b.name, "de"));
  const quelle = auswahl(quellen.map((g) => [g.id, g.name]), s.e_geraet || zustand.geraete.get("solar")?.id || quellen[0]?.id);
  const wertKey = h("select");
  const op = auswahl(OPS, s.e_op || ">");
  const vergleich = h("span", { class: "wert-box" });
  let vergleichEl;
  const dauer = h("input", { type: "number", min: 0, max: 600, step: 1, value: s.e_dauer ?? 5, inputmode: "numeric" });
  const zeichneVergleich = (start) => {
    const v = zustand.geraete.get(quelle.value)?.states[wertKey.value];
    vergleichEl = typeof v === "boolean" ? auswahl([["an", "an"], ["aus", "aus"]], start || "an")
      : h("input", { type: "number", step: "any", value: start ?? (istZahl(v) ? Math.round(v) : ""), inputmode: "decimal", required: true });
    op.querySelectorAll("option").forEach((o) => { o.hidden = typeof v === "boolean" && !["=", "!="].includes(o.value); });
    if (typeof v === "boolean" && !["=", "!="].includes(op.value)) op.value = "=";
    vergleich.replaceChildren(vergleichEl);
  };
  const zeichneKeys = (startKey, startWert) => {
    const g = zustand.geraete.get(quelle.value);
    const keys = Object.entries(g?.states || {}).filter(([k, v]) => (istZahl(v) || typeof v === "boolean") && !k.startsWith("e_"))
      .map(([k]) => [k, WERT_NAMEN[k] || k]).sort((a, b) => (b[0] in WERT_NAMEN) - (a[0] in WERT_NAMEN));
    wertKey.replaceChildren(...keys.map(([k, t]) => h("option", { value: k }, t)));
    if (keys.some((k) => k[0] === startKey)) wertKey.value = startKey;
    zeichneVergleich(startWert);
  };
  quelle.addEventListener("change", () => zeichneKeys(null));
  wertKey.addEventListener("change", () => zeichneVergleich());
  zeichneKeys(s.e_key || "einspeisung_w", s.e_wert);

  const teile = {
    zeit: h("div", { class: "editor-gruppe" }, feld("Uhrzeit", uhr), h("div", { class: "feld" }, h("span", { class: "feld-titel" }, "An diesen Tagen"), tageZeit)),
    sonne: h("div", { class: "editor-gruppe" }, feld("Ereignis", sonne), feld("Minuten davor (−) oder danach (+)", versatz),
      h("div", { class: "feld" }, h("span", { class: "feld-titel" }, "An diesen Tagen"), tageSonne)),
    wert: h("div", { class: "editor-gruppe" }, feld("Gerät", quelle), feld("Messwert", wertKey),
      h("div", { class: "zwei" }, feld("Bedingung", op), feld("Wert", vergleich)),
      feld("Mindestens so lange erfüllt (Minuten)", dauer),
      h("p", { class: "hinweis-klein" }, "Die Regel löst einmal aus, wenn die Bedingung so lange erfüllt ist. Wieder auslösen kann sie erst, wenn die Bedingung zwischendurch nicht mehr erfüllt war.")),
  };
  const artKnoepfe = [["zeit", "Uhrzeit"], ["sonne", "Sonne"], ["wert", "Messwert"]].map(([w, t]) =>
    h("button", { type: "button", "data-wert": w, onclick: () => { art = w; artZeigen(); } }, t));
  const artZeigen = () => {
    artKnoepfe.forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.wert === art)));
    Object.entries(teile).forEach(([k, el]) => { el.hidden = k !== art; });
  };
  artZeigen();
  const aktionen = aktionenListe((s.was || "").split(";").map((x) => x.trim()).filter(Boolean), true);

  editorOeffnen(d ? "Regel bearbeiten" : "Neue Regel", "HomeX schaltet von selbst", "automation",
    [feld("Name", name),
      h("div", { class: "feld" }, h("span", { class: "feld-titel" }, "Wann?"), h("div", { class: "segmente" }, artKnoepfe)),
      ...Object.values(teile),
      h("div", { class: "feld" }, h("span", { class: "feld-titel" }, "Was soll passieren?"), aktionen)],
    d ? "Speichern" : "Regel anlegen", async (knopf) => {
      const n = name.value.trim();
      if (!nameOk(n)) { melden("Bitte einen Namen eingeben (ohne | und ;)."); name.focus(); return false; }
      let wann;
      if (art === "zeit") {
        if (!uhr.value) { melden("Bitte eine Uhrzeit wählen."); return false; }
        if (tageZeit.leer()) { melden("Bitte mindestens einen Tag wählen."); return false; }
        wann = `um ${uhr.value} ${tageZeit.wert()}`;
      } else if (art === "sonne") {
        if (tageSonne.leer()) { melden("Bitte mindestens einen Tag wählen."); return false; }
        const v = Math.round(Number(versatz.value) || 0);
        wann = `bei sonnen${sonne.value}${v ? ` ${v > 0 ? "+" : "-"}${Math.abs(v)} min` : ""} ${tageSonne.wert()}`;
      } else {
        const w = String(vergleichEl.value).trim().replace(",", ".");
        if (!quelle.value || !wertKey.value || w === "") { melden("Bitte Gerät, Messwert und Vergleichswert angeben."); return false; }
        const m = Math.max(0, Math.round(Number(dauer.value) || 0));
        wann = `wenn ${quelle.value}.${wertKey.value} ${op.value} ${w}${m ? ` für ${m} min` : ""}`;
      }
      const texte = aktionen.texte();
      if (texte.some((t) => !t)) { melden("Bei einer Aktion fehlt noch der Wert."); return false; }
      const ok = await verwalten("regel-speichern", [d ? idOhne(d.id, "regel-") : "", `${n} | ${wann.trim()} | ${texte.join("; ")}`].join("\n"), knopf);
      if (ok) melden(`Regel „${n}“ gespeichert.`, true);
      return ok;
    });
}


// ----------------------------------------------------------------- Aktivität

function seiteAktivitaet() {
  const filter = [["alle", "Alles"], ["aenderung", "Schaltvorgänge"], ["fehler", "Fehler"], ["system", "System"]];
  const knoepfe = filter.map(([w, t]) => h("button", { type: "button", "data-wert": w,
    onclick: () => { zustand.aktivFilter = w; zeichnen(); } }, t));
  const liste = h("div");
  const zeichnen = () => {
    knoepfe.forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.wert === zustand.aktivFilter)));
    const eintraege = zustand.aktivitaet.filter((a) => zustand.aktivFilter === "alle" || a.art === zustand.aktivFilter);
    if (!eintraege.length) { liste.replaceChildren(h("p", { class: "leer" }, "Hier ist noch nichts passiert.")); return; }
    const tage = new Map();
    for (const a of eintraege) {
      const ms = isoZuMs(a.zeit);
      const tag = wannText(ms)?.split(" ")[0] || "";
      const label = tag === "heute" ? "Heute" : tag === "gestern" ? "Gestern" : new Date(ms).toLocaleDateString("de-DE", { weekday: "long", day: "numeric", month: "long" });
      if (!tage.has(label)) tage.set(label, []);
      tage.get(label).push(a);
    }
    liste.replaceChildren(...[...tage].map(([t, as]) => h("section", {}, h("h3", { class: "feed-tag" }, t),
      h("ul", { class: "feed gross" }, as.map(feedEintrag)))));
  };
  beobachten("__aktivitaet", zeichnen);
  return h("div", {}, kopf("Aktivität", "Was im Haus passiert ist, mit Auslöser. Die letzten 400 Ereignisse."),
    h("div", { class: "filter segmente", role: "group", "aria-label": "Filter" }, knoepfe), liste);
}

// ----------------------------------------------------------------- System

function seiteSystem() {
  const info = zustand.info || {};
  const werte = h("dl", { class: "werte" });
  const plugins = h("ul", { class: "plugin-liste" });
  const zeichnen = () => {
    const i = zustand.info || {};
    const s = i.laeuft_seit_s || 0;
    const laeuft = s > 86400 ? Math.floor(s / 86400) + " Tage" : s > 3600 ? Math.floor(s / 3600) + " Std." : Math.max(1, Math.floor(s / 60)) + " Min.";
    werte.replaceChildren(...[["Haus", i.haus], ["HomeX-Version", i.version], ["Läuft seit", laeuft], ["Geräte", zustand.geraete.size],
      ["Standort", i.standort ? "eingetragen" : "fehlt (homex.properties)"]].flatMap(([k, v]) => [h("dt", {}, k), h("dd", {}, String(v ?? "–"))]));
    plugins.replaceChildren(...(i.plugins || []).map((p) => h("li", { class: p.enabled ? "an" : "" }, p.name, h("small", {}, p.version))));
  };
  beobachten("__info", zeichnen);

  const link = location.origin + location.pathname + "?token=" + encodeURIComponent(zustand.token || "");
  const geraete = [...zustand.geraete.values()].sort((a, b) => a.type.localeCompare(b.type) || a.id.localeCompare(b.id));

  return h("div", {}, kopf("System", "HomeX, Plugins und Geräte"),
    h("div", { class: "system-gitter" },
      h("section", { class: "panel" }, h("h2", {}, "HomeX"), werte),
      h("section", { class: "panel" }, h("h2", {}, "Plugins"), plugins),
      h("section", { class: "panel" }, h("h2", {}, "Auf anderen Geräten öffnen"),
        h("p", { class: "leer", style: { marginBottom: "12px" } },
          "Dieser Link meldet ein Tablet oder Handy direkt an. Gib ihn nur an Leute im Haushalt weiter."),
        h("div", { style: { display: "flex", gap: "8px", flexWrap: "wrap" } },
          h("button", { class: "knopf", type: "button", onclick: () => kopieren(link, "Link kopiert") }, h("span", { html: icon("kopieren"), style: { display: "contents" } }), "Link kopieren"),
          h("button", { class: "knopf", type: "button", onclick: abmelden }, "Abmelden")))),
    abschnitt("Geräte", "Die IDs brauchst du für Szenen und Regeln",
      h("div", { class: "panel", style: { overflowX: "auto" } }, h("table", { class: "daten geraete-tabelle" },
        h("thead", {}, h("tr", {}, h("th", {}, "ID"), h("th", {}, "Name"), h("th", {}, "Raum"), h("th", {}, "Typ"))),
        h("tbody", {}, geraete.map((d) => h("tr", {},
          h("td", {}, h("span", { class: "mono" }, d.id),
            h("button", { class: "kopier-knopf", type: "button", "aria-label": "ID kopieren", html: icon("kopieren"), onclick: () => kopieren(d.id, "ID kopiert") })),
          h("td", {}, d.name), h("td", {}, d.room || "–"), h("td", {}, typName(d.type)))))))));
}

function typName(t) {
  return ({ LIGHT: "Licht", SWITCH: "Schalter", SHUTTER: "Rollladen", CLIMATE: "Klima", SENSOR: "Sensor", POWER_METER: "Stromzähler",
    SOLAR_INVERTER: "Wechselrichter", BATTERY: "Batterie", WEATHER: "Wetter", SCENE: "Szene", AUTOMATION: "Regel" })[t] || t;
}

async function kopieren(text, ok) {
  try { await navigator.clipboard.writeText(text); melden(ok, true); }
  catch { prompt("Zum Kopieren markieren:", text); }
}

// =====================================================================
// Detail-Blatt
// =====================================================================

let blattBeobachter = null;

function blattOeffnen(id) {
  const d = zustand.geraete.get(id);
  if (!d) return;
  const a = art(d);
  $("blatt-titel").textContent = d.name;
  $("blatt-unter").textContent = [d.room, typName(d.type)].filter(Boolean).join(", ");
  $("blatt-symbol").replaceChildren(h("span", { class: "szene-hex", style: { "--farbe": "var(--sonne)" } }, h("span", { html: icon(a === "sensor" ? "sensor" : a) })));
  const inhalt = $("blatt-inhalt");
  const fuss = $("blatt-fuss");
  const bauer = { LIGHT: blattLicht, SWITCH: blattSchalter, SHUTTER: blattRollladen, CLIMATE: blattKlima }[d.type] || blattAllgemein;
  const { teile, aktualisieren } = bauer(d);
  inhalt.replaceChildren(...teile);
  const fussZeichnen = (g) => fuss.replaceChildren(
    h("span", {}, g.changed ? `Zuletzt geändert ${wannText(g.changed)}${g.source ? " durch " + g.source : ""}` : "Noch keine Änderung"),
    h("span", {}, "ID für Szenen und Regeln: ", h("span", { class: "mono" }, g.id)));
  blattBeobachter = { id, f: (g) => { if (g) { aktualisieren(g); fussZeichnen(g); } } };
  blattBeobachter.f(d);
  if (!$("blatt").open) $("blatt").showModal();
}

function grossSchalter(d, farbe, textAn, textAus) {
  const b = h("button", { class: "gross-schalter", type: "button", style: { "--farbe": farbe } });
  b.addEventListener("click", () => senden(d.id, "power", !(zustand.geraete.get(d.id).states.power === true), b));
  b.aktualisieren = (g) => {
    const an = g.states.power === true;
    b.setAttribute("aria-pressed", String(an));
    b.replaceChildren(h("div", {}, h("strong", {}, an ? textAn : textAus), h("span", {}, an ? "Antippen zum Ausschalten" : "Antippen zum Einschalten")),
      h("span", { class: "schalter", role: "presentation", "aria-checked": String(an), style: { "--an": farbe } }));
  };
  return b;
}

function blattLicht(d) {
  const schalter = grossSchalter(d, "var(--sonne)", "Ist an", "Ist aus");
  const teile = [schalter];
  let regler, anzeige;
  if ("helligkeit" in d.states) {
    anzeige = h("output");
    regler = h("input", { type: "range", min: 1, max: 100, step: 1, "aria-label": "Helligkeit", style: { "--farbe": "var(--sonne)" } });
    regler.addEventListener("input", () => { anzeige.textContent = regler.value + " %"; });
    regler.addEventListener("change", () => senden(d.id, "helligkeit", Number(regler.value), schalter));
    teile.push(h("div", {}, h("div", { class: "feld-titel" }, h("span", {}, "Helligkeit"), anzeige), regler,
      h("div", { class: "schnell" }, [10, 30, 60, 100].map((p) =>
        h("button", { class: "knopf klein", type: "button", onclick: () => senden(d.id, "helligkeit", p, schalter) }, p + " %")))));
  }
  return { teile, aktualisieren: (g) => {
    schalter.aktualisieren(g);
    if (regler && document.activeElement !== regler && istZahl(g.states.helligkeit)) {
      regler.value = g.states.helligkeit; anzeige.textContent = g.states.helligkeit + " %";
    }
  } };
}

function blattSchalter(d) {
  const schalter = grossSchalter(d, "var(--violett)", "Ist an", "Ist aus");
  return { teile: [schalter], aktualisieren: (g) => schalter.aktualisieren(g) };
}

function blattRollladen(d) {
  const lamellen = h("span", { class: "wabe-lamellen" });
  const bild = h("div", { class: "fenster-bild", "aria-hidden": "true" }, lamellen);
  const anzeige = h("output");
  const regler = h("input", { type: "range", min: 0, max: 100, step: 5, "aria-label": "Position (0 = zu, 100 = offen)", style: { "--farbe": "var(--kuehl)" } });
  regler.addEventListener("input", () => { anzeige.textContent = regler.value + " % offen"; });
  regler.addEventListener("change", () => senden(d.id, "position", Number(regler.value), bild));
  const knopf = (icn, text, wert) => h("button", { class: "knopf", type: "button", onclick: () => senden(d.id, "fahren", wert, bild) },
    h("span", { html: icon(icn), style: { display: "contents" } }), text);
  const teile = [
    h("div", { class: "fenster" }, bild, h("div", { class: "fenster-knoepfe" }, knopf("hoch", "Öffnen", "auf"), knopf("stopp", "Anhalten", "stopp"), knopf("runter", "Schließen", "zu"))),
    h("div", {}, h("div", { class: "feld-titel" }, h("span", {}, "Position"), anzeige), regler),
  ];
  return { teile, aktualisieren: (g) => {
    const pos = istZahl(g.states.position) ? g.states.position : 100;
    lamellen.style.setProperty("--zu", (100 - pos) + "%");
    const faehrt = g.states.bewegung && g.states.bewegung !== "steht";
    if (document.activeElement !== regler) { regler.value = pos; anzeige.textContent = faehrt ? (g.states.bewegung === "faehrt_auf" ? "fährt hoch … " : "fährt runter … ") + pos + " %" : pos + " % offen"; }
  } };
}

function blattKlima(d) {
  const soll = h("output");
  const ist = h("p");
  const minus = h("button", { class: "rund-knopf", type: "button", "aria-label": "Temperatur senken", html: icon("minus"), onclick: () => klimaSoll(-1) });
  const plus = h("button", { class: "rund-knopf", type: "button", "aria-label": "Temperatur erhöhen", html: icon("plus"), onclick: () => klimaSoll(1) });
  const klimaSoll = (s) => soll_(d.id, s);
  const soll_ = (id, s) => soll(id, s, schalter);
  const schalter = grossSchalter(d, "var(--kuehl)", "Läuft", "Ist aus");
  const modi = MODI.map(([w, t]) => h("button", { type: "button", "data-wert": w, style: { "--farbe": MODUS_FARBE[w] }, onclick: () => senden(d.id, "modus", w, schalter) }, t));
  const luefter = h("select", { "aria-label": "Lüfterstufe", onchange: (e) => senden(d.id, "luefter", e.target.value, schalter) },
    LUEFTER.map(([w, t]) => h("option", { value: w }, t)));
  return {
    teile: [schalter, h("div", { class: "klima-gross" }, h("div", { class: "soll-gross" }, minus, soll, plus), ist),
      h("div", {}, h("div", { class: "feld-titel" }, h("span", {}, "Modus")), h("div", { class: "segmente" }, modi)),
      h("label", { class: "feld-titel" }, h("span", {}, "Lüfter"), luefter)],
    aktualisieren: (g) => {
      const s = g.states;
      schalter.style.setProperty("--farbe", MODUS_FARBE[s.modus] || "var(--kuehl)");
      schalter.aktualisieren(g);
      const t = sollWartet.get(g.id)?.wert ?? s.solltemp;
      soll.textContent = istZahl(t) ? zahl(t) + "°" : "–";
      ist.textContent = istZahl(s.ist_temp) ? `Im Raum ${zahl(s.ist_temp)} °C` : "Raumtemperatur unbekannt";
      modi.forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.wert === s.modus)));
      if (s.luefter && document.activeElement !== luefter) luefter.value = s.luefter;
    },
  };
}

function blattAllgemein() {
  const dl = h("dl", { class: "werte" });
  return { teile: [dl], aktualisieren: (g) => dl.replaceChildren(...Object.entries(g.states).filter(([, v]) => !Array.isArray(v))
    .flatMap(([k, v]) => [h("dt", {}, k.replace(/_/g, " ")), h("dd", {}, typeof v === "boolean" ? (v ? "ja" : "nein") : istZahl(v) ? zahl(v, 2) : String(v))])) };
}
