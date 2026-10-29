/* HomeX Dashboard – spricht mit dem WebAPI-Plugin (gleicher Server, Pfad /api). */
"use strict";

const API = "";
const TOKEN_KEY = "homex-token";
const CLIENT = "Dashboard";

const SEITEN = [
  { pfad: "", titel: "Übersicht", icon: "uebersicht" },
  { pfad: "energie", titel: "Energie", icon: "energie" },
  { pfad: "klima", titel: "Klima", icon: "klima" },
  { pfad: "automation", titel: "Automation", icon: "automation" },
  { pfad: "aktivitaet", titel: "Aktivität", icon: "aktivitaet" },
  { pfad: "system", titel: "System", icon: "system" },
];

const MODI = [["kuehlen", "Kühlen"], ["heizen", "Heizen"], ["entfeuchten", "Entfeuchten"], ["lueften", "Lüften"], ["auto", "Auto"]];
const MODUS_FARBE = { kuehlen: "var(--kuehl)", entfeuchten: "var(--kuehl)", heizen: "var(--heiss)", auto: "var(--violett)", lueften: "#7FD8FF" };
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

  $("nav-liste").replaceChildren(...SEITEN.map((s) =>
    h("li", {}, h("a", { href: "#/" + s.pfad, "data-pfad": s.pfad }, ic(s.icon), h("span", {}, s.titel),
      s.pfad === "aktivitaet" ? h("span", { class: "nav-zahl", id: "nav-fehler" }) : null))));

  $("login-form").addEventListener("submit", anmelden);
  $("blatt-zu").innerHTML = icon("schliessen");
  $("blatt-zu").addEventListener("click", () => $("blatt").close());
  $("blatt").addEventListener("click", (e) => { if (e.target === $("blatt")) $("blatt").close(); });   // Klick daneben
  $("blatt").addEventListener("close", () => { blattBeobachter = null; });

  window.addEventListener("hashchange", zeigen);
  let resizeTimer;
  window.addEventListener("resize", () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(zeigen, 200); });

  if (zustand.token) laden(); else loginZeigen();
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
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

function live() {
  zustand.quelle?.close();
  const q = new EventSource(API + "/api/events?token=" + encodeURIComponent(zustand.token));
  zustand.quelle = q;
  q.onopen = () => status("live", "Live verbunden");
  q.onerror = async () => {
    status("weg", "Verbindung unterbrochen …");
    try { await api("/api/info"); } catch (err) {
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
  const seite = SEITEN.find((s) => s.pfad === pfad) || SEITEN[0];
  document.querySelectorAll("#nav-liste a").forEach((a) => {
    if (a.dataset.pfad === seite.pfad) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
  });
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
  const geraete = [...zustand.geraete.values()].filter((d) => !NICHT_IN_RAEUMEN.has(d.type));
  const raeume = new Map();
  for (const d of geraete) {
    const r = d.room || "Ohne Raum";
    if (!raeume.has(r)) raeume.set(r, []);
    raeume.get(r).push(d);
  }
  const reihenfolge = [...raeume.keys()].sort((a, b) =>
    a === "Ohne Raum" ? 1 : b === "Ohne Raum" ? -1 : a.localeCompare(b, "de"));

  const unter = h("p", { class: "kopf-unter" });
  const aktualisiereUnter = () => {
    const an = [...zustand.geraete.values()].filter((d) => d.type === "LIGHT" && d.states.power === true).length;
    const datum = new Date().toLocaleDateString("de-DE", { weekday: "long", day: "numeric", month: "long" });
    unter.textContent = `${zustand.info?.haus || "Mein Zuhause"}, ${datum}. ` +
      (an === 0 ? "Alle Lichter sind aus." : an === 1 ? "1 Licht ist an." : `${an} Lichter sind an.`);
  };
  geraete.forEach((d) => beobachten(d.id, aktualisiereUnter));

  const kopfEl = h("header", { class: "kopf" },
    h("div", {}, h("h1", {}, gruss()), unter),
    h("div", { class: "kopf-rechts" }, wetterChip()));

  const szenen = nachTyp("SCENE");
  const haupt = h("div", {},
    szenen.length ? h("div", { class: "szenen", role: "group", "aria-label": "Szenen" }, szenen.map(szeneKnopf)) : null,
    geraete.length
      ? h("section", { class: "abschnitt" },
          h("div", { class: "raeume" }, reihenfolge.map((r) => raumKarte(r, raeume.get(r)))),
          h("p", { class: "hinweis-zeile" }, "Tippen schaltet. Gedrückt halten oder Rechtsklick öffnet die Einstellungen."))
      : h("div", { class: "leer-gross abschnitt" },
          h("h3", {}, "Noch keine Geräte"),
          h("p", {}, "Lege Plugins wie DummyGeraete, HisenseKlima oder SmaSolar in den Ordner plugins/ und starte HomeX neu.")));

  return h("div", {}, kopfEl, h("div", { class: "uebersicht" }, haupt,
    h("aside", { class: "seitenspalte" }, energiePanel(), aktivitaetPanel())));
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

function raumKarte(raum, geraete) {
  geraete.sort((a, b) => typRang(a) - typRang(b) || a.name.localeCompare(b.name, "de"));
  const neben = h("span", { class: "neben" });
  const zaehlen = () => {
    const schaltbar = geraete.filter((d) => "power" in d.states && d.type !== "CLIMATE");
    const an = schaltbar.filter((d) => d.states.power === true).length;
    neben.textContent = schaltbar.length ? (an ? `${an} von ${schaltbar.length} an` : "alles aus") : "";
    neben.classList.toggle("aktiv", an > 0);
  };
  geraete.forEach((d) => beobachten(d.id, zaehlen));
  return h("section", { class: "raum", "aria-label": raum },
    h("div", { class: "raum-kopf" }, h("h3", {}, raum), neben),
    wabenfeld(geraete));
}

function typRang(d) { return ({ LIGHT: 0, SWITCH: 1, SHUTTER: 2, CLIMATE: 3 })[d.type] ?? 4; }

// ----------------------------------------------------------------- Waben

/** Ordnet die Waben als Bienenwabe an: jede zweite Reihe um eine halbe Wabe versetzt. */
function wabenfeld(geraete) {
  const feld = h("div", { class: "wabenfeld" });
  const waben = geraete.map((d, i) => { const w = wabe(d); w.style.setProperty("--n", i); return w; });
  feld.append(...waben);
  const anordnen = () => {
    const cs = getComputedStyle(feld);
    const wb = parseFloat(cs.getPropertyValue("--wb")) || 112;
    const gap = parseFloat(cs.getPropertyValue("--luecke")) || 8;
    const halb = (wb + gap) / 2;
    const n = Math.max(1, Math.floor((feld.clientWidth / halb - 1) / 2));
    feld.style.setProperty("--halbe", 2 * n + 1);
    waben.forEach((w, i) => {
      const r = Math.floor(i / n), c = i % n;
      w.style.setProperty("--spalte", 2 * c + 1 + (r % 2));
      w.style.setProperty("--zeile", r + 1);
    });
  };
  const ro = new ResizeObserver(anordnen);
  ro.observe(feld);
  seitenAufraeumen.push(() => ro.disconnect());
  return feld;
}

/** "Klima Wohnzimmer" im Raum Wohnzimmer → "Klima" (der Raum steht ja schon darüber) */
function kurzName(d) {
  if (!d.room) return d.name;
  const rest = d.name.replace(new RegExp("\\s*" + d.room.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\s*", "i"), " ").trim();
  return rest || d.name;
}

function art(d) {
  return ({ LIGHT: "licht", SWITCH: "steckdose", SHUTTER: "rollladen", CLIMATE: "klima", SENSOR: "sensor" })[d.type] || "geraet";
}

function wabe(d) {
  const a = art(d);
  const name = h("span", { class: "wabe-name" });
  const wert = h("span", { class: "wabe-wert" });
  const lamellen = a === "rollladen" ? h("span", { class: "wabe-lamellen" }) : null;
  const kern = h("span", { class: "wabe-kern" }, lamellen, h("span", { html: icon(a === "sensor" ? "sensor" : a), style: { display: "contents" } }), name, wert);
  const b = h("button", { class: "wabe", type: "button", "data-art": a, "data-id": d.id }, h("span", { class: "wabe-rand" }), kern);

  // Tippen = schalten, gedrückt halten / Rechtsklick = Einstellungen
  let halteTimer = null, gehalten = false;
  b.addEventListener("pointerdown", (e) => {
    if (e.button !== 0) return;
    gehalten = false;
    halteTimer = setTimeout(() => { gehalten = true; blattOeffnen(d.id); }, 480);
  });
  const abbrechen = () => clearTimeout(halteTimer);
  b.addEventListener("pointerup", abbrechen);
  b.addEventListener("pointerleave", abbrechen);
  b.addEventListener("pointercancel", abbrechen);
  b.addEventListener("contextmenu", (e) => { e.preventDefault(); abbrechen(); blattOeffnen(d.id); });
  b.addEventListener("click", () => {
    if (gehalten) { gehalten = false; return; }
    tippen(zustand.geraete.get(d.id), b);
  });

  beobachten(d.id, (g) => {
    if (!g) return;
    const s = g.states;
    const offline = s.online === false;
    let an = false, text = "", staerke = 1;
    switch (g.type) {
      case "LIGHT":
        an = s.power === true;
        staerke = an && istZahl(s.helligkeit) ? 0.25 + 0.75 * s.helligkeit / 100 : 1;
        text = an ? (istZahl(s.helligkeit) ? s.helligkeit + " %" : "an") : "aus";
        break;
      case "SWITCH":
        an = s.power === true; text = an ? "an" : "aus"; break;
      case "SHUTTER": {
        const pos = istZahl(s.position) ? s.position : 100;
        const faehrt = s.bewegung && s.bewegung !== "steht";
        an = faehrt;
        b.classList.toggle("faehrt", !!faehrt);
        lamellen.style.setProperty("--zu", (100 - pos) * 0.86 + "%");
        text = faehrt ? (s.bewegung === "faehrt_auf" ? "fährt hoch" : "fährt runter") : pos >= 100 ? "offen" : pos <= 0 ? "zu" : pos + " % offen";
        break;
      }
      case "CLIMATE":
        an = s.power === true && !offline;
        b.dataset.modus = s.modus || "";
        text = offline ? "offline" : (istZahl(s.ist_temp) ? zahl(s.ist_temp) + "°" : "") + (an ? " " + (MODI.find((m) => m[0] === s.modus)?.[1] || "") : " aus");
        break;
      default: {
        const zahlWert = Object.entries(s).find(([, v]) => istZahl(v));
        text = zahlWert ? zahl(zahlWert[1], 1) : "";
      }
    }
    name.textContent = kurzName(g);
    wert.textContent = text.trim();
    b.classList.toggle("an", an);
    b.classList.toggle("offline", offline);
    b.style.setProperty("--staerke", staerke.toFixed(2));
    const umschaltbar = g.type === "LIGHT" || g.type === "SWITCH";
    if (umschaltbar) b.setAttribute("aria-pressed", String(an)); else b.removeAttribute("aria-pressed");
    b.setAttribute("aria-label", `${g.name}${g.room ? ", " + g.room : ""}: ${wert.textContent}` +
      (umschaltbar ? "" : ". Antippen öffnet die Einstellungen"));
  });
  return b;
}

/** Was passiert beim Antippen einer Wabe? */
function tippen(d, el) {
  if (!d) return;
  const s = d.states;
  if (d.type === "LIGHT" || d.type === "SWITCH") return senden(d.id, "power", !(s.power === true), el);
  if (d.type === "SHUTTER") {
    if (s.bewegung && s.bewegung !== "steht") return senden(d.id, "fahren", "stopp", el);
    return senden(d.id, "fahren", (s.position ?? 100) >= 50 ? "zu" : "auf", el);
  }
  blattOeffnen(d.id);
}

// ----------------------------------------------------------------- Seitenspalte

function energiePanel() {
  const s = solar(), w = wetter();
  const inhalt = h("div", { class: "mini-energie" });
  const zeichnen = () => {
    const ss = solar()?.states, ws = wetter()?.states;
    const zeilen = [];
    if (ss && ss.status !== "WARTET" && ss.status !== "START") {
      zeilen.push(miniZeile("sonne", "var(--sonne)", "Solar jetzt", leistung(ss.pv_w ?? ss.ac_w).join(" ")));
      if (istZahl(ss.verbrauch_w)) zeilen.push(miniZeile("haus", "var(--text)", "Verbrauch", leistung(ss.verbrauch_w).join(" ")));
      if (istZahl(ss.batterie_soc)) zeilen.push(miniZeile("batterie", "var(--violett)", "Batterie", zahl(ss.batterie_soc) + " %"));
    } else {
      zeilen.push(h("p", { class: "leer" }, ss ? "Die Solaranlage wartet noch auf ihren Zugang." : "Noch keine Solaranlage verbunden."));
    }
    if (ws && istZahl(ws.heute_pv_prognose_kwh)) zeilen.push(miniZeile("wolkig", "var(--sonne)", "PV-Prognose heute", "≈ " + ws.heute_pv_prognose_kwh + " kWh"));
    else if (ws && istZahl(ws.heute_sonnenenergie_kwh_m2)) zeilen.push(miniZeile("klar", "var(--sonne)", "Sonne heute", ws.heute_sonnenenergie_kwh_m2 + " kWh/m²"));
    inhalt.replaceChildren(...zeilen);
  };
  if (s) beobachten(s.id, zeichnen);
  if (w) beobachten(w.id, zeichnen);
  zeichnen();
  return h("section", { class: "panel" }, h("h2", {}, "Energie", h("a", { href: "#/energie" }, "Details")), inhalt);
}

function miniZeile(sym, farbe, text, wert) {
  return h("div", { class: "mini-energie-zeile" }, h("span", { html: icon(sym), style: { display: "contents", color: farbe } }),
    h("span", {}, text), h("strong", {}, wert));
}

function aktivitaetPanel() {
  const liste = h("ul", { class: "feed" });
  const zeichnen = () => liste.replaceChildren(...zustand.aktivitaet.slice(0, 8).map(feedEintrag));
  beobachten("__aktivitaet", zeichnen);
  return h("section", { class: "panel" }, h("h2", {}, "Zuletzt passiert", h("a", { href: "#/aktivitaet" }, "Alles")), liste);
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
  teile.push(h("section", { class: "abschnitt energie-oben" }, svg, fakten));
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

function klimaKarte(d) {
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

  const karte = h("article", { class: "klima-karte", "data-id": d.id },
    h("div", { class: "klima-name" }, titel, info), ist,
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

function seiteAutomation() {
  const szenen = nachTyp("SCENE");
  const regeln = [...nachTyp("AUTOMATION")].sort((a, b) => (b.states.aktiv === true) - (a.states.aktiv === true));
  const aktiv = regeln.filter((r) => r.states.aktiv === true).length;
  const teile = [kopf("Automation", `${szenen.length} Szenen, ${aktiv} von ${regeln.length} Regeln aktiv`)];

  if (!szenen.length && !regeln.length) {
    teile.push(h("div", { class: "leer-gross" }, h("h3", {}, "Noch keine Szenen und Regeln"),
      h("p", {}, "Lege das Plugin Automation in den Ordner plugins/ und starte HomeX neu. Es bringt Beispiele mit.")));
    return h("div", {}, teile);
  }

  teile.push(abschnitt("Szenen", "Mehrere Geräte mit einem Klick", h("div", { class: "szenen-gitter" }, szenen.map(szenenKarte))));
  teile.push(abschnitt("Regeln", "HomeX handelt selbst", h("div", { class: "regeln" }, regeln.map(regelZeile))));
  teile.push(h("div", { class: "anleitung" },
    h("p", {}, "Szenen und Regeln stehen in ", h("code", {}, "plugins/Automation/szenen.txt"), " und ",
      h("code", {}, "regeln.txt"), ". Die Dateien erklären ihr Format selbst."),
    h("p", {}, "Nach dem Bearbeiten in der Konsole ", h("code", {}, "automation neu"),
      " tippen. Die Geräte-IDs für deine Befehle findest du unter System.")));
  return h("div", {}, teile);
}

function szenenKarte(d) {
  const info = h("p");
  const knopf = h("button", { class: "knopf", type: "button" }, h("span", { html: icon("start"), style: { display: "contents" } }), "Ausführen");
  const karte = h("article", { class: "szenen-karte", "data-symbol": d.states.symbol },
    h("span", { class: "szene-hex", style: { "--farbe": szenenFarbe(d.states.symbol) } }, h("span", { html: icon(d.states.symbol || "stern") })),
    h("h3", {}, d.name), info, knopf);
  karte.style.setProperty("--farbe", szenenFarbe(d.states.symbol));
  knopf.addEventListener("click", async () => {
    knopf.disabled = true; karte.classList.add("laeuft");
    await szeneStarten(d.id);
    knopf.disabled = false; karte.classList.remove("laeuft");
  });
  beobachten(d.id, (g) => {
    const s = g.states;
    info.textContent = `${s.befehle} Befehl${s.befehle === 1 ? "" : "e"}` + (s.zuletzt ? `, zuletzt ${wannText(isoZuMs(s.zuletzt))}` : "");
  });
  return karte;
}

function szenenFarbe(sym) {
  return ({ sonne: "var(--sonne)", kaffee: "var(--sonne)", feuer: "var(--sonne)", aus: "var(--heiss)", weg: "var(--heiss)", schnee: "var(--kuehl)" })[sym] || "var(--violett)";
}

function regelZeile(d) {
  const titel = h("h3"), satz = h("p", { class: "regel-satz" }), info = h("div", { class: "regel-info" });
  const schalter = h("button", { class: "schalter", type: "button", role: "switch" });
  const zeile = h("article", { class: "regel", "data-id": d.id }, schalter, h("div", {}, titel, satz),
    h("button", { class: "knopf klein", type: "button", title: "Führt die Aktion sofort einmal aus" },
      h("span", { html: icon("start"), style: { display: "contents" } }), "Testen"), info);
  schalter.addEventListener("click", () => senden(d.id, "aktiv", !(zustand.geraete.get(d.id).states.aktiv === true), zeile));
  zeile.querySelector(".knopf").addEventListener("click", async () => {
    if (await senden(d.id, "ausfuehren", 1, zeile)) melden(`Regel „${d.name}“ wird ausgeführt. Was passiert, zeigt die Aktivität.`, true);
  });
  beobachten(d.id, (g) => {
    const s = g.states, an = s.aktiv === true;
    zeile.classList.toggle("pausiert", !an);
    titel.textContent = g.name;
    satz.replaceChildren(h("b", {}, wannGross(s.wann)), " → ", s.was);
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
