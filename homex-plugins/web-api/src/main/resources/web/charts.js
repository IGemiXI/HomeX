/* HomeX – kleine SVG-Diagramme ohne Bibliothek: Liniendiagramm (Verlauf) und Säulendiagramm (Prognose).
   Farben kommen als CSS-Variablen; Texte bleiben in Textfarben, nur die Linien tragen die Serienfarbe. */
"use strict";

const Charts = (() => {
  const NS = "http://www.w3.org/2000/svg";
  const RAND = { oben: 12, rechts: 64, unten: 26, links: 44 };

  function el(tag, attrs = {}, text) {
    const e = document.createElementNS(NS, tag);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    if (text != null) e.textContent = text;
    return e;
  }

  /** "schöne" Obergrenze für die y-Achse: 1, 2, 2.5, 5 × 10^n */
  function obergrenze(max) {
    if (!(max > 0)) return 1;
    const p = Math.pow(10, Math.floor(Math.log10(max)));
    for (const f of [1, 2, 2.5, 5, 10]) if (f * p >= max) return f * p;
    return 10 * p;
  }

  const zahl = (v, st = 0) => Number(v).toLocaleString("de-DE", { maximumFractionDigits: st });
  const uhr = (t) => new Date(t).toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit" });

  function tooltip(container) {
    let tip = container.querySelector(".diagramm-tip");
    if (!tip) {
      tip = document.createElement("div");
      tip.className = "diagramm-tip";
      tip.hidden = true;
      container.append(tip);
    }
    return tip;
  }

  /**
   * Liniendiagramm über die Zeit.
   * reihen: [{ name, farbe (CSS-Wert), punkte: [[ms, wert], …] }]
   * opt: { von, bis (ms), einheit, format(v) → Text, leer: Text ohne Daten }
   */
  function linien(container, reihen, opt) {
    container.classList.add("diagramm");
    container.querySelector("svg")?.remove();
    const breite = Math.max(260, container.clientWidth);
    const hoehe = opt.hoehe || 220;
    const fmt = opt.format || ((v) => zahl(v) + " " + (opt.einheit || ""));
    const alle = reihen.flatMap((r) => r.punkte);
    const svg = el("svg", { width: breite, height: hoehe, viewBox: `0 0 ${breite} ${hoehe}`, role: "img",
      "aria-label": opt.titel || "Diagramm" });
    container.prepend(svg);

    const x0 = RAND.links, x1 = breite - RAND.rechts, y0 = hoehe - RAND.unten, y1 = RAND.oben;
    const von = opt.von, bis = opt.bis;
    const max = obergrenze(Math.max(0, ...alle.map((p) => p[1])) * 1.05);
    const X = (t) => x0 + ((t - von) / (bis - von)) * (x1 - x0);
    const Y = (v) => y0 - (v / max) * (y0 - y1);

    // Raster und Achsen (zurückhaltend)
    for (let i = 0; i <= 4; i++) {
      const v = (max / 4) * i, y = Y(v);
      svg.append(el("line", { x1: x0, x2: x1, y1: y, y2: y, class: i === 0 ? "achse" : "raster" }));
      if (alle.length) svg.append(el("text", { x: x0 - 8, y: y + 4, class: "achsentext", "text-anchor": "end" }, kurz(v, opt)));
    }
    const stunde = 3600e3;
    const schritt = (bis - von) > 30 * stunde ? 6 * stunde : 3 * stunde;
    for (let t = von; t <= bis; t += schritt) {      // ab "von" (lokale Mitternacht), nicht ab UTC
      svg.append(el("text", { x: X(t), y: hoehe - 6, class: "achsentext", "text-anchor": "middle" }, uhr(t)));
    }

    if (!alle.length) {
      svg.append(el("text", { x: (x0 + x1) / 2, y: (y0 + y1) / 2, class: "diagramm-leer", "text-anchor": "middle" },
        opt.leer || "Noch keine Daten"));
      return;
    }

    // Linien (2 px), dünne Fläche unter der ersten Reihe
    reihen.forEach((r, i) => {
      if (!r.punkte.length) return;
      const d = r.punkte.map((p, j) => `${j ? "L" : "M"}${X(p[0]).toFixed(1)},${Y(p[1]).toFixed(1)}`).join("");
      if (i === 0 && r.flaeche !== false) {
        const f = `${d}L${X(r.punkte.at(-1)[0]).toFixed(1)},${y0}L${X(r.punkte[0][0]).toFixed(1)},${y0}Z`;
        svg.append(el("path", { d: f, fill: r.farbe, opacity: ".12" }));
      }
      svg.append(el("path", { d, fill: "none", stroke: r.farbe, "stroke-width": 2, "stroke-linejoin": "round",
        "stroke-linecap": "round" }));
    });

    // Direkte Beschriftung am Linienende (Name in Textfarbe, Marker in Serienfarbe)
    const belegt = [];
    reihen.forEach((r) => {
      if (!r.punkte.length) return;
      const [t, v] = r.punkte.at(-1);
      let y = Y(v) + 4;
      while (belegt.some((b) => Math.abs(b - y) < 14)) y += 14;
      belegt.push(y);
      svg.append(el("circle", { cx: X(t), cy: Y(v), r: 4, fill: r.farbe, stroke: "var(--panel)", "stroke-width": 2 }));
      svg.append(el("text", { x: X(t) + 9, y, class: "direktlabel" }, r.kurz || r.name));
    });

    // Hover: Fadenkreuz + Tooltip
    const tip = tooltip(container);
    const linie = el("line", { y1: y1, y2: y0, class: "fadenkreuz", visibility: "hidden" });
    svg.append(linie);
    const flaeche = el("rect", { x: x0, y: y1, width: x1 - x0, height: y0 - y1, fill: "transparent" });
    svg.append(flaeche);
    const zeigen = (ev) => {
      const box = svg.getBoundingClientRect();
      const px = ev.clientX - box.left;
      const t = von + ((px - x0) / (x1 - x0)) * (bis - von);
      const zeilen = reihen.map((r) => {
        let best = null;
        for (const p of r.punkte) if (!best || Math.abs(p[0] - t) < Math.abs(best[0] - t)) best = p;
        return best && Math.abs(best[0] - t) < 20 * 60e3 ? { r, p: best } : null;
      }).filter(Boolean);
      if (!zeilen.length) { tip.hidden = true; linie.setAttribute("visibility", "hidden"); return; }
      const tx = X(zeilen[0].p[0]);
      linie.setAttribute("x1", tx); linie.setAttribute("x2", tx); linie.setAttribute("visibility", "visible");
      tip.innerHTML = `<strong>${uhr(zeilen[0].p[0])}</strong>` + zeilen.map(({ r, p }) =>
        `<span><i style="background:${r.farbe}"></i>${r.name}<b>${fmt(p[1])}</b></span>`).join("");
      tip.hidden = false;
      const links = Math.min(Math.max(tx + 12, 0), breite - tip.offsetWidth - 4);
      tip.style.left = (tx + 12 + tip.offsetWidth > breite ? tx - tip.offsetWidth - 12 : links) + "px";
      tip.style.top = "8px";
    };
    flaeche.addEventListener("pointermove", zeigen);
    flaeche.addEventListener("pointerdown", zeigen);
    flaeche.addEventListener("pointerleave", () => { tip.hidden = true; linie.setAttribute("visibility", "hidden"); });
  }

  /** Säulen pro Stunde (z. B. Sonneneinstrahlung heute). werte: Zahlen [0..23], jetzt: aktuelle Stunde */
  function saeulen(container, werte, opt) {
    container.classList.add("diagramm");
    container.querySelector("svg")?.remove();
    const breite = Math.max(260, container.clientWidth);
    const hoehe = opt.hoehe || 160;
    const svg = el("svg", { width: breite, height: hoehe, viewBox: `0 0 ${breite} ${hoehe}`, role: "img",
      "aria-label": opt.titel || "Diagramm" });
    container.prepend(svg);
    const x0 = RAND.links, x1 = breite - 12, y0 = hoehe - RAND.unten, y1 = RAND.oben;
    const max = obergrenze(Math.max(1, ...werte));
    const Y = (v) => y0 - (v / max) * (y0 - y1);
    const w = (x1 - x0) / werte.length;

    for (let i = 0; i <= 2; i++) {
      const v = (max / 2) * i;
      svg.append(el("line", { x1: x0, x2: x1, y1: Y(v), y2: Y(v), class: i === 0 ? "achse" : "raster" }));
      svg.append(el("text", { x: x0 - 8, y: Y(v) + 4, class: "achsentext", "text-anchor": "end" }, kurz(v, opt)));
    }
    const tip = tooltip(container);
    werte.forEach((v, h) => {
      const x = x0 + h * w + 1, bw = Math.max(2, w - 2);                // 2 px Abstand zwischen Säulen
      if (h % 3 === 0) svg.append(el("text", { x: x0 + h * w + w / 2, y: hoehe - 6, class: "achsentext",
        "text-anchor": "middle" }, String(h).padStart(2, "0")));
      if (v <= 0) return;
      const y = Y(v), r = Math.min(4, bw / 2, y0 - y);
      // oben abgerundet, unten an der Grundlinie gerade
      const d = `M${x},${y0}V${y + r}Q${x},${y} ${x + r},${y}H${x + bw - r}Q${x + bw},${y} ${x + bw},${y + r}V${y0}Z`;
      const s = el("path", { d, fill: opt.farbe, opacity: h === opt.jetzt ? 1 : 0.72 });
      svg.append(s);
      // Trefferfläche größer als die Säule
      const hit = el("rect", { x: x0 + h * w, y: y1, width: w, height: y0 - y1, fill: "transparent" });
      hit.addEventListener("pointerenter", () => {
        tip.innerHTML = `<strong>${String(h).padStart(2, "0")}–${String(h + 1).padStart(2, "0")} Uhr</strong>` +
          `<span><i style="background:${opt.farbe}"></i>${opt.name}<b>${zahl(v)} ${opt.einheit}</b></span>`;
        tip.hidden = false;
        const tx = x0 + h * w + w / 2;
        tip.style.left = Math.min(Math.max(0, tx - tip.offsetWidth / 2), breite - tip.offsetWidth) + "px";
        tip.style.top = "0px";
        s.setAttribute("opacity", 1);
      });
      hit.addEventListener("pointerleave", () => { tip.hidden = true; s.setAttribute("opacity", h === opt.jetzt ? 1 : 0.72); });
      svg.append(hit);
    });
  }

  function kurz(v, opt) {
    if (opt.einheit === "W" && v >= 1000) return zahl(v / 1000, 1) + " kW";
    return zahl(v, v < 10 && v % 1 ? 1 : 0) + (opt.einheitAchse ? " " + opt.einheit : "");
  }

  return { linien, saeulen };
})();
