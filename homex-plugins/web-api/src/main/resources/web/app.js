/* HomeX Dashboard – spricht mit dem WebAPI-Plugin (gleicher Server, Pfad /api). */
"use strict";

const API = "";                       // gleicher Server wie das Dashboard
const TOKEN_KEY = "homex-token";
const SVG = "http://www.w3.org/2000/svg";

const MODI = [
  ["kuehlen", "Kühlen"], ["heizen", "Heizen"], ["entfeuchten", "Entfeuchten"],
  ["lueften", "Lüften"], ["auto", "Auto"],
];
const LUEFTER = [
  ["auto", "Auto"], ["sehr_leise", "Sehr leise"], ["leise", "Leise"],
  ["mittel", "Mittel"], ["hoch", "Hoch"], ["sehr_hoch", "Sehr hoch"],
];
const TEMP_MIN = 16, TEMP_MAX = 30;

const geraete = new Map();             // id → {id, name, type, states}
const sollWartet = new Map();          // id → {wert, timer}  (Temperatur wird gesammelt gesendet)
let token = null;
let quelle = null;                     // EventSource

const $ = (id) => document.getElementById(id);

// =====================================================================
// Start
// =====================================================================

document.addEventListener("DOMContentLoaded", () => {
  // Token aus dem Link übernehmen (z. B. QR-Code: http://homex:8080/?token=...)
  const url = new URL(location.href);
  if (url.searchParams.has("token")) {
    speichern(url.searchParams.get("token"));
    url.searchParams.delete("token");
    history.replaceState(null, "", url.pathname + url.search);
  }
  token = lesen();

  flussAufbauen();
  uhr();
  setInterval(uhr, 15000);

  $("login-form").addEventListener("submit", anmelden);
  $("abmelden").addEventListener("click", abmelden);

  if (token) laden(); else loginZeigen();

  if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
});

function lesen() { try { return localStorage.getItem(TOKEN_KEY); } catch { return token; } }
function speichern(t) { token = t; try { t ? localStorage.setItem(TOKEN_KEY, t) : localStorage.removeItem(TOKEN_KEY); } catch {} }

function uhr() {
  $("uhr").textContent = new Date().toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit" });
}

// =====================================================================
// Anmeldung
// =====================================================================

function loginZeigen(meldung) {
  const f = $("login-fehler");
  f.hidden = !meldung;
  f.textContent = meldung || "";
  const d = $("login");
  if (!d.open) d.showModal();
  $("token").focus();
}

async function anmelden(e) {
  e.preventDefault();
  const t = $("token").value.trim();
  if (!t) return;
  speichern(t);
  try {
    await api("/api/info");
    $("login").close();
    $("token").value = "";
    laden();
  } catch (err) {
    speichern(null);
    loginZeigen(err.status === 401
      ? "Dieser Zugangsschlüssel passt nicht. Prüfe ihn in plugins/WebAPI/config.properties."
      : "HomeX ist nicht erreichbar. Läuft der Server?");
  }
}

function abmelden() {
  speichern(null);
  if (quelle) quelle.close();
  quelle = null;
  geraete.clear();
  allesZeichnen();
  status("start", "Abgemeldet");
  loginZeigen();
}

// =====================================================================
// Daten & Live-Verbindung
// =====================================================================

async function api(pfad, optionen = {}) {
  const res = await fetch(API + pfad, {
    ...optionen,
    headers: { "Authorization": "Bearer " + token, "Content-Type": "application/json", ...(optionen.headers || {}) },
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
    const [info, liste] = await Promise.all([api("/api/info"), api("/api/devices")]);
    pluginsZeigen(info);
    geraete.clear();
    liste.forEach((d) => geraete.set(d.id, d));
    allesZeichnen();
    live();
  } catch (err) {
    if (err.status === 401) { speichern(null); loginZeigen("Bitte melde dich erneut an."); return; }
    status("weg", "HomeX nicht erreichbar");
    setTimeout(laden, 5000);
  }
}

function live() {
  if (quelle) quelle.close();
  quelle = new EventSource(API + "/api/events?token=" + encodeURIComponent(token));

  quelle.onopen = () => status("live", "Live");
  quelle.onerror = async () => {
    status("weg", "Verbindung unterbrochen, neuer Versuch …");
    // EventSource verrät keinen Statuscode – prüfen, ob der Token noch gilt
    try { await api("/api/info"); } catch (err) {
      if (err.status === 401) { quelle.close(); speichern(null); loginZeigen("Bitte melde dich erneut an."); }
    }
  };

  quelle.addEventListener("snapshot", (e) => {
    geraete.clear();
    JSON.parse(e.data).forEach((d) => geraete.set(d.id, d));
    allesZeichnen();
  });
  quelle.addEventListener("state", (e) => {
    const { id, key, value } = JSON.parse(e.data);
    const d = geraete.get(id);
    if (!d) return;
    if (key === "solltemp" && sollWartet.has(id)) return;   // der Nutzer dreht gerade
    d.states[key] = value;
    geraetZeichnen(d);
  });
  quelle.addEventListener("device", (e) => {
    const d = JSON.parse(e.data);
    geraete.set(d.id, d);
    allesZeichnen();
  });
  quelle.addEventListener("removed", (e) => {
    const { id } = JSON.parse(e.data);
    geraete.delete(id);
    document.querySelectorAll(`[data-id="${CSS.escape(id)}"]`).forEach((el) => el.remove());
    allesZeichnen();
  });
  quelle.addEventListener("plugins", () => api("/api/info").then(pluginsZeigen).catch(() => {}));
}

function status(zustand, text) {
  const el = $("verbindung");
  el.dataset.zustand = zustand;
  el.textContent = text;
}

function pluginsZeigen(info) {
  const aktiv = info.plugins.filter((p) => p.enabled).map((p) => p.name);
  $("plugins").textContent = `HomeX ${info.version}, Plugins: ${aktiv.length ? aktiv.join(", ") : "keine"}`;
}

/** Steuerbefehl senden. Die Anzeige ändert sich sofort und wird bei einem Fehler zurückgesetzt. */
async function senden(id, key, value) {
  const d = geraete.get(id);
  if (!d) return;
  const vorher = d.states[key];
  d.states[key] = value;
  geraetZeichnen(d);
  try {
    const antwort = await api("/api/devices/" + encodeURIComponent(id), {
      method: "POST",
      body: JSON.stringify({ key, value }),
    });
    geraete.set(id, antwort.device);
  } catch (err) {
    d.states[key] = vorher;
    if (err.daten && err.daten.device) geraete.set(id, err.daten.device);
    hinweis(`${d.name}: ${beschreibung(key)} wurde nicht geändert. Das Gerät ist nicht erreichbar oder der Wert ist nicht erlaubt.`);
  }
  geraetZeichnen(geraete.get(id));
}

function beschreibung(key) {
  return { power: "Ein/Aus", solltemp: "Temperatur", modus: "Modus", luefter: "Lüfter", helligkeit: "Helligkeit" }[key] || key;
}

let hinweisTimer;
function hinweis(text) {
  const el = $("hinweis");
  el.textContent = text;
  el.classList.add("zeigen");
  clearTimeout(hinweisTimer);
  hinweisTimer = setTimeout(() => el.classList.remove("zeigen"), 5000);
}

// =====================================================================
// Zeichnen
// =====================================================================

function nachTyp(typ) { return [...geraete.values()].filter((d) => d.type === typ).sort((a, b) => a.name.localeCompare(b.name, "de")); }

function allesZeichnen() {
  const solar = nachTyp("SOLAR_INVERTER")[0];
  energieZeichnen(solar);

  const klimas = nachTyp("CLIMATE");
  $("klima").hidden = klimas.length === 0;
  liste($("klima-liste"), klimas, klimaZeile);

  const lichter = nachTyp("LIGHT");
  $("licht").hidden = lichter.length === 0;
  liste($("licht-liste"), lichter, lichtKachel);

  const rest = [...geraete.values()]
    .filter((d) => !["CLIMATE", "LIGHT"].includes(d.type) && d !== solar)
    .sort((a, b) => a.name.localeCompare(b.name, "de"));
  $("weitere").hidden = rest.length === 0;
  liste($("weitere-liste"), rest, geraetKachel);

  $("nichts").hidden = geraete.size > 0 || !token;
}

/** Hält die Reihenfolge der Elemente in einem Container passend zur Liste; erstellt fehlende, entfernt alte. */
function liste(container, eintraege, erstellen) {
  const ids = new Set(eintraege.map((d) => d.id));
  [...container.children].forEach((el) => { if (!ids.has(el.dataset.id)) el.remove(); });
  eintraege.forEach((d, i) => {
    let el = container.querySelector(`:scope > [data-id="${CSS.escape(d.id)}"]`);
    if (!el) el = erstellen(d);
    if (container.children[i] !== el) container.insertBefore(el, container.children[i] || null);
    el.aktualisieren(d);
  });
}

function geraetZeichnen(d) {
  if (!d) return;
  if (d.type === "SOLAR_INVERTER") { allesZeichnen(); return; }
  const el = document.querySelector(`[data-id="${CSS.escape(d.id)}"]`);
  if (el && el.aktualisieren) el.aktualisieren(d); else allesZeichnen();
}

// ------------------------------------------------------------ Format

const zahl = (v, stellen = 0, fest = true) =>
  Number(v).toLocaleString("de-DE", { maximumFractionDigits: stellen, minimumFractionDigits: fest ? stellen : 0 });
const istZahl = (v) => typeof v === "number" && isFinite(v);

function leistung(w) {
  if (!istZahl(w)) return ["–", ""];
  const a = Math.abs(w);
  return a >= 1000 ? [zahl(a / 1000, 1), "kW"] : [zahl(a), "W"];
}

function el(tag, attrs = {}, ...kinder) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") e.className = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v);
  }
  kinder.flat().forEach((k) => e.append(k));
  return e;
}

function svg(tag, attrs = {}) {
  const e = document.createElementNS(SVG, tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  return e;
}

// =====================================================================
// Energiefluss
// =====================================================================

const KNOTEN = {
  sonne:    { x: 70,  y: 66,  r: 44, name: "Sonne" },
  batterie: { x: 70,  y: 236, r: 44, name: "Batterie" },
  netz:     { x: 350, y: 150, r: 44, name: "Netz" },
  haus:     { x: 210, y: 150, r: 66, name: "Haus" },
};
const fluss = {};

function hex(x, y, r) {
  const p = [];
  for (let i = 0; i < 6; i++) {
    const a = (Math.PI / 180) * (60 * i - 90);
    p.push(`${(x + r * Math.cos(a)).toFixed(1)},${(y + r * Math.sin(a)).toFixed(1)}`);
  }
  return `M${p.join("L")}Z`;
}

function flussAufbauen() {
  const root = $("fluss");
  root.style.display = "none";            // erst zeigen, wenn Solardaten da sind
  const h = KNOTEN.haus;
  // Leitungen zuerst (liegen unter den Hexagonen)
  for (const k of ["sonne", "batterie", "netz"]) {
    const n = KNOTEN[k];
    const d = `M${n.x},${n.y}L${h.x},${h.y}`;
    root.append(svg("path", { d, class: "leitung" }));
    fluss[k + "Strom"] = svg("path", { d, class: "strom " + k });
    root.append(fluss[k + "Strom"]);
  }
  for (const [k, n] of Object.entries(KNOTEN)) {
    const g = svg("g", { class: "knoten " + k });
    g.append(svg("path", { d: hex(n.x, n.y, n.r), class: "knoten-form" }));
    const wert = svg("text", { x: n.x, y: n.y + (k === "haus" ? 6 : 4), class: "wert" });
    const name = svg("text", { x: n.x, y: n.y + (k === "haus" ? 32 : 24), class: "name" });
    name.textContent = n.name;
    g.append(wert, name);
    fluss[k] = g;
    fluss[k + "Wert"] = wert;
    root.append(g);
  }
  // Zusatztexte außerhalb der Knoten (Richtung des Stroms in Worten)
  fluss.netzZusatz = svg("text", { x: KNOTEN.netz.x, y: KNOTEN.netz.y + 66, class: "zusatz netz" });
  fluss.batterieZusatz = svg("text", { x: KNOTEN.batterie.x, y: KNOTEN.batterie.y - 56, class: "zusatz batterie" });
  root.append(fluss.netzZusatz, fluss.batterieZusatz);
}

function wertSetzen(textEl, [z, einheit]) {
  textEl.textContent = z;
  if (einheit) {
    const t = svg("tspan", { "font-size": "0.55em", dx: "3" });
    t.textContent = einheit;
    textEl.append(t);
  }
}

/** Animiert eine Leitung: Richtung und Geschwindigkeit hängen von der Leistung ab. */
function strom(pfad, watt, rueckwaerts) {
  const an = istZahl(watt) && Math.abs(watt) >= 20;
  pfad.classList.toggle("an", an);
  pfad.classList.toggle("rueck", !!rueckwaerts);
  if (an) pfad.style.setProperty("--dauer", Math.max(0.35, 2.4 - Math.abs(watt) / 2500).toFixed(2) + "s");
}

function energieZeichnen(solar) {
  const s = solar ? solar.states : null;
  $("fluss").style.display = s ? "" : "none";
  $("energie-fakten").hidden = !s;
  $("energie-leer").hidden = !!s;
  if (!s) return;

  const pv = s.pv_w ?? s.ac_w;
  const bezug = s.netzbezug_w, einsp = s.einspeisung_w;
  const netz = istZahl(bezug) || istZahl(einsp) ? (bezug || 0) - (einsp || 0) : null;   // + = Bezug
  const laden = s.batterie_laden_w || 0, entladen = s.batterie_entladen_w || 0;
  const batt = laden - entladen;                                                            // + = lädt

  wertSetzen(fluss.sonneWert, leistung(pv));
  wertSetzen(fluss.hausWert, leistung(s.verbrauch_w));
  wertSetzen(fluss.netzWert, leistung(netz));
  wertSetzen(fluss.batterieWert, istZahl(s.batterie_soc) ? [zahl(s.batterie_soc), "%"] : ["–", ""]);

  fluss.netzZusatz.textContent = !istZahl(netz) || Math.abs(netz) < 20 ? "" : netz > 0 ? "Bezug" : "Einspeisung";
  fluss.batterieZusatz.textContent = Math.abs(batt) < 20 ? "" : (batt > 0 ? "lädt " : "entlädt ") + leistung(batt).join(" ");

  fluss.sonne.classList.toggle("aktiv", istZahl(pv) && pv >= 20);
  fluss.netz.classList.toggle("aktiv", istZahl(netz) && Math.abs(netz) >= 20);
  fluss.batterie.classList.toggle("aktiv", Math.abs(batt) >= 20);

  strom(fluss.sonneStrom, pv, false);
  strom(fluss.netzStrom, netz, istZahl(netz) && netz < 0);     // Einspeisung: Haus → Netz
  strom(fluss.batterieStrom, batt, batt > 0);                   // Laden: Haus → Batterie

  // Fakten rechts
  const fakten = [];
  if (istZahl(s.ertrag_heute_kwh)) fakten.push(["Heute erzeugt", zahl(s.ertrag_heute_kwh, 1), "kWh", "var(--sonne)"]);
  if (istZahl(s.verbrauch_w) && s.verbrauch_w > 0 && istZahl(bezug)) {
    const eigen = Math.max(0, Math.min(100, 100 * (1 - bezug / s.verbrauch_w)));
    fakten.push(["Eigenversorgung jetzt", zahl(eigen), "%", "var(--batterie)"]);
  }
  if (istZahl(s.ertrag_gesamt_kwh)) fakten.push(["Gesamt erzeugt", zahl(s.ertrag_gesamt_kwh / 1000, 2), "MWh", "var(--linie)"]);
  const wr = s.status === "OFFLINE" ? ["nicht erreichbar", "", "var(--heiss)"]
    : s.status === "OK" ? [istZahl(s.temperatur_c) ? zahl(s.temperatur_c, 1) : "OK", istZahl(s.temperatur_c) ? "°C" : "", "var(--linie)"]
    : [String(s.status || "–").toLowerCase(), "", "var(--heiss)"];
  fakten.push(["Wechselrichter", ...wr]);

  const dl = $("energie-fakten");
  dl.replaceChildren(...fakten.map(([titel, wert, einheit, farbe]) => {
    const div = el("div", { style: `--farbe:${farbe}` }, el("dt", {}, titel), el("dd", {}, wert, einheit ? el("small", {}, einheit) : ""));
    return div;
  }));
}

// =====================================================================
// Klima
// =====================================================================

function klimaZeile(d) {
  const name = el("strong");
  const info = el("span");
  const ist = el("div", { class: "klima-ist", "aria-label": "Raumtemperatur" });
  const sollWert = el("output", { "aria-live": "polite" });
  const minus = el("button", { class: "rund", type: "button", "aria-label": "Temperatur senken", onclick: () => soll(d.id, -1) }, "−");
  const plus = el("button", { class: "rund", type: "button", "aria-label": "Temperatur erhöhen", onclick: () => soll(d.id, +1) }, "+");
  const schalter = el("button", {
    class: "schalter-knopf", type: "button", role: "switch",
    onclick: () => senden(d.id, "power", !geraete.get(d.id).states.power),
  });
  const modi = MODI.map(([wert, text]) =>
    el("button", { type: "button", "data-wert": wert, onclick: () => senden(d.id, "modus", wert) }, text));
  const luefter = el("select", { "aria-label": "Lüfterstufe", onchange: (e) => senden(d.id, "luefter", e.target.value) },
    LUEFTER.map(([wert, text]) => el("option", { value: wert }, text)));

  const zeile = el("article", { class: "klima", "data-id": d.id },
    el("div", { class: "klima-name" }, name, info),
    ist,
    el("div", { class: "soll" }, minus, sollWert, plus),
    el("div", { class: "schalter" }, schalter),
    el("div", { class: "modi", role: "group", "aria-label": "Modus" }, modi),
    el("label", { class: "luefter" }, "Lüfter", luefter),
  );

  zeile.aktualisieren = (g) => {
    const s = g.states;
    const online = s.online !== false;
    const an = s.power === true;
    const modus = s.modus || "";
    zeile.classList.toggle("an", an && online);
    zeile.classList.toggle("offline", !online);
    zeile.dataset.modus = modus;

    name.textContent = g.name.replace(/^Klima\s+/i, "");
    info.textContent = !online ? "nicht erreichbar" : an ? (MODI.find(([m]) => m === modus) || [, modus])[1] : "aus";

    ist.replaceChildren(istZahl(s.ist_temp) ? zahl(s.ist_temp) : "–", el("small", {}, "°C"));

    const warte = sollWartet.get(g.id);
    const sollT = warte ? warte.wert : s.solltemp;
    sollWert.replaceChildren(istZahl(sollT) ? zahl(sollT) + "°" : "–", el("small", {}, "Soll"));
    minus.disabled = !online || !istZahl(sollT) || sollT <= TEMP_MIN;
    plus.disabled = !online || !istZahl(sollT) || sollT >= TEMP_MAX;

    schalter.setAttribute("aria-checked", String(an));
    schalter.setAttribute("aria-label", `${name.textContent} ${an ? "ausschalten" : "einschalten"}`);
    schalter.disabled = !online;

    modi.forEach((b) => { b.setAttribute("aria-pressed", String(b.dataset.wert === modus)); b.disabled = !online; });
    if (s.luefter && document.activeElement !== luefter) luefter.value = s.luefter;
    luefter.disabled = !online;
  };
  return zeile;
}

/** +/- sammeln und erst nach einer kurzen Pause senden – sonst gehen bei 5 Klicks 5 Cloud-Befehle raus. */
function soll(id, schritt) {
  const d = geraete.get(id);
  const w = sollWartet.get(id);
  const basis = w ? w.wert : d.states.solltemp;
  if (!istZahl(basis)) return;
  const neu = Math.max(TEMP_MIN, Math.min(TEMP_MAX, basis + schritt));
  if (w) clearTimeout(w.timer);
  const timer = setTimeout(() => { sollWartet.delete(id); senden(id, "solltemp", neu); }, 700);
  sollWartet.set(id, { wert: neu, timer });
  geraetZeichnen(d);
}

// =====================================================================
// Licht & weitere Geräte
// =====================================================================

function lichtKachel(d) {
  const name = el("strong");
  const schalter = el("button", {
    class: "schalter-knopf", type: "button", role: "switch",
    onclick: () => senden(d.id, "power", !geraete.get(d.id).states.power),
  });
  const regler = el("input", { type: "range", min: "1", max: "100", step: "1", "aria-label": "Helligkeit" });
  regler.addEventListener("change", () => senden(d.id, "helligkeit", Number(regler.value)));
  const kachel = el("article", { class: "licht", "data-id": d.id }, name, schalter, regler);

  kachel.aktualisieren = (g) => {
    const an = g.states.power === true;
    kachel.classList.toggle("aus", !an);
    name.textContent = g.name;
    schalter.setAttribute("aria-checked", String(an));
    schalter.setAttribute("aria-label", `${g.name} ${an ? "ausschalten" : "einschalten"}`);
    regler.hidden = !istZahl(g.states.helligkeit);
    if (document.activeElement !== regler && istZahl(g.states.helligkeit)) regler.value = g.states.helligkeit;
  };
  return kachel;
}

function geraetKachel(d) {
  const name = el("strong");
  const dl = el("dl");
  const kachel = el("article", { class: "geraet", "data-id": d.id }, name, dl);
  kachel.aktualisieren = (g) => {
    name.textContent = g.name;
    dl.replaceChildren(...Object.entries(g.states).flatMap(([k, v]) => [
      el("dt", {}, k.replace(/_/g, " ")),
      el("dd", {}, typeof v === "boolean" ? (v ? "ja" : "nein") : istZahl(v) ? zahl(v, 2, false) : String(v)),
    ]));
  };
  return kachel;
}
