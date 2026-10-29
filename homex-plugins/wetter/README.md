# Wetter – Wetter und PV-Prognose für HomeX

Holt alle 15 Minuten das Wetter für dein Haus von [Open-Meteo](https://open-meteo.com). Das ist für private
Nutzung kostenlos und braucht keine Anmeldung.

Damit bekommst du:

- **Jetzt:** Temperatur, gefühlte Temperatur, Luftfeuchte, Wind, Wetterlage
- **Heute und morgen:** Tiefst- und Höchstwert, Regenwahrscheinlichkeit
- **Sonne:** Sonnenauf- und -untergang, Sonnenenergie pro m², Einstrahlung je Stunde
- **PV-Prognose:** grobe Schätzung des Solarertrags für heute und morgen, wenn du `anlage.kwp` einträgst

## Einrichten

1. Trag deinen Standort in `homex.properties` ein (liegt neben der `HomeX.jar`):
   ```properties
   standort.breitengrad=52.52
   standort.laengengrad=13.40
   ```
2. Optional: In `plugins/Wetter/config.properties` die Leistung deiner Anlage eintragen, z. B. `anlage.kwp=10`.
3. HomeX neu starten.

Ohne Standort wartet das Plugin einfach. Im Dashboard steht dann ein Hinweis.

Die PV-Prognose rechnet „Einstrahlung × kWp × 0,8“. Ausrichtung und Neigung deiner Module kennt sie nicht, sie
ist also nur ein Anhaltspunkt. Echte Werte kommen vom SmaSolar-Plugin.

In der Konsole zeigt `wetter` alle Werte. Das Gerät heißt `wetter`. Du kannst es also auch in Regeln nutzen,
z. B. `wenn wetter.temperatur_c > 26 für 30 min | klima-wohnzimmer power an`.
