/* HomeX – Liniensymbole (24×24, Strichstärke 1.8, Farbe = currentColor) */
"use strict";

const CLOUD_HOCH = "M7 15h10a4 4 0 0 0 .5-8 6 6 0 0 0-11.4 1.5A3.3 3.3 0 0 0 7 15Z";

const ICONS = {
  // Geräte
  licht: '<path d="M9 18h6M10 21h4"/><path d="M12 3a6 6 0 0 0-3.6 10.8c.7.5 1.1 1.3 1.1 2.2V17h5v-1c0-.9.4-1.7 1.1-2.2A6 6 0 0 0 12 3Z"/>',
  steckdose: '<path d="M9 2v5M15 2v5M12 17v5"/><path d="M6 7h12v4a6 6 0 0 1-12 0Z"/>',
  rollladen: '<path d="M3.5 3h17M5 3v13M19 3v13M5 7h14M5 11h14M5 15h14M12 16v4"/><circle cx="12" cy="21" r=".6" fill="currentColor"/>',
  klima: '<rect x="3" y="4" width="18" height="8" rx="2"/><path d="M6 9h12M7.5 15.5c0 1.5-1 2-1 3.5M12 15.5c0 1.5-1 2-1 3.5M16.5 15.5c0 1.5-1 2-1 3.5"/>',
  sensor: '<path d="M14 14.8V5a2 2 0 0 0-4 0v9.8a4 4 0 1 0 4 0Z"/>',
  geraet: '<rect x="5" y="5" width="14" height="14" rx="3"/><circle cx="12" cy="12" r="2.5"/>',

  // Szenen
  sonne: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  mond: '<path d="M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5Z"/>',
  aus: '<path d="M12 3v8"/><path d="M7 6.3a8 8 0 1 0 10 0"/>',
  film: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M7 5v14M17 5v14M3 9.5h4M3 14.5h4M17 9.5h4M17 14.5h4"/>',
  haus: '<path d="M3 11 12 4l9 7"/><path d="M5 10v10h14V10M10 20v-6h4v6"/>',
  weg: '<path d="M13 4H6v16h7M10 12h11M18 9l3 3-3 3"/>',
  kaffee: '<path d="M4 9h12v5a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5Z"/><path d="M16 11h1.5a2.5 2.5 0 0 1 0 5H16M8 3v3M12 3v3"/>',
  stern: '<path d="m12 3 2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9Z"/>',
  schnee: '<path d="M12 2v20M3.5 7l17 10M20.5 7l-17 10M9.5 3.5 12 6l2.5-2.5M9.5 20.5 12 18l2.5 2.5"/>',
  feuer: '<path d="M12 21a6 6 0 0 0 6-6c0-4-3-6-4-10-2 2-3 4-3 6-1-1-1.5-2-1.5-3C7 10 6 12.5 6 15a6 6 0 0 0 6 6Z"/>',

  // Wetter
  klar: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  "klar-nacht": '<path d="M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5Z"/>',
  wolkig: '<path d="M8 2.5V4M3.4 4.4l1 1M2 9h1.5M12.6 4.4l-1 1"/><path d="M5.3 11.5A3 3 0 1 1 10.9 8"/><path d="M9 20h9a3.5 3.5 0 0 0 .4-7 5 5 0 0 0-9.5 1.3A2.9 2.9 0 0 0 9 20Z"/>',
  "wolkig-nacht": '<path d="M10.5 7.2A4.5 4.5 0 0 1 4 11a4.5 4.5 0 0 0 4.7-7.5A4.5 4.5 0 0 1 10.5 7.2Z"/><path d="M9 20h9a3.5 3.5 0 0 0 .4-7 5 5 0 0 0-9.5 1.3A2.9 2.9 0 0 0 9 20Z"/>',
  bedeckt: '<path d="M7 18h10a4 4 0 0 0 .5-8 6 6 0 0 0-11.4 1.5A3.3 3.3 0 0 0 7 18Z"/>',
  nebel: '<path d="M4 8h16M3 12h18M5 16h14M8 20h8"/>',
  regen: '<path d="' + CLOUD_HOCH + '"/><path d="m8.5 18-1 2.5M12.5 18l-1 2.5M16.5 18l-1 2.5"/>',
  gewitter: '<path d="' + CLOUD_HOCH + '"/><path d="m13 15-2.5 3.5h3.5L11.5 22"/>',

  // Navigation
  uebersicht: '<path d="m8 2.5 4 2.3v4.6L8 11.7 4 9.4V4.8ZM16 2.5l4 2.3v4.6l-4 2.3-4-2.3V4.8ZM12 12l4 2.3v4.6L12 21.2l-4-2.3v-4.6Z"/>',
  energie: '<path d="M13 2 4.5 13.5H11L10 22l8.5-11.5H12Z"/>',
  automation: '<circle cx="12" cy="13" r="8"/><path d="M12 9v4l2.5 2M9.5 2h5"/>',
  aktivitaet: '<path d="M3 12h4l3-8 4 16 3-8h4"/>',
  system: '<path d="M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1"/><circle cx="15" cy="6" r="2"/><circle cx="9" cy="12" r="2"/><circle cx="17" cy="18" r="2"/>',

  // Energie
  batterie: '<rect x="2.5" y="7" width="16" height="10" rx="2"/><path d="M21.5 10.5v3"/>',
  netz: '<path d="m8 22 4-19 4 19M5.5 8h13M6.5 13h11M9 17.5l6-4.5M15 17.5 9 13"/>',

  // Bedienung
  schliessen: '<path d="M6 6l12 12M18 6 6 18"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  minus: '<path d="M5 12h14"/>',
  kopieren: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M4 16V6a2 2 0 0 1 2-2h10"/>',
  start: '<path d="M7 4.5v15L19.5 12Z"/>',
  hoch: '<path d="m6 15 6-6 6 6"/>',
  runter: '<path d="m6 9 6 6 6-6"/>',
  stopp: '<rect x="7" y="7" width="10" height="10" rx="1.5"/>',
  tropfen: '<path d="M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11Z"/>',
  wind: '<path d="M3 8h11a3 3 0 1 0-3-3M3 12h15a3 3 0 1 1-3 3M3 16h7"/>',
  thermometer: '<path d="M14 14.8V5a2 2 0 0 0-4 0v9.8a4 4 0 1 0 4 0Z"/>',
  warnung: '<path d="M12 3 2 20h20Z"/><path d="M12 10v4M12 17v.01"/>',
};

/** SVG-Markup für ein Symbol. Unbekannte Namen bekommen ein neutrales Gerätesymbol. */
function icon(name, klasse = "icon") {
  return `<svg class="${klasse}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" ` +
    `stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">${ICONS[name] || ICONS.geraet}</svg>`;
}
