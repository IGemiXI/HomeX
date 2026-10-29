# DummyGeraete – Test-Geräte für HomeX

Lichter, Dimmer, Steckdosen und Rollläden **ohne echte Hardware**. Sie verhalten sich wie echte Geräte:
Sie brauchen kurz für die Antwort, Rollläden fahren hörbar langsam, und auf Wunsch schlägt ein Teil der
Befehle fehl. **Jeder Schaltvorgang erscheint in der Konsole** mit Ergebnis und Auslöser:

```
[DummyGeraete] OK     Deckenlicht (Küche): an   ← Dashboard
[DummyGeraete] OK     Stehlampe (Wohnzimmer): Helligkeit 15 %   ← Szene Kino
[DummyGeraete] …      Rollladen (Schlafzimmer): fährt runter auf 0 %
[DummyGeraete] OK     Rollladen (Schlafzimmer): Position 0 % (zu)   ← Szene Gute Nacht
[DummyGeraete] FEHLER Kaffeemaschine (Küche): an – Gerät antwortet nicht (simulierter Fehler)   ← Konsole
```

So lassen sich Dashboard, Szenen und Regeln komplett ausprobieren. Sobald die echten Geräte angebunden sind,
löschst du die Test-Geräte aus der Config oder nimmst die `DummyGeraete.jar` aus `plugins/`.

## Geräte anlegen

In `plugins/DummyGeraete/config.properties`, eine Zeile pro Gerät:

```properties
geraet.licht-wohnzimmer = licht, Deckenlicht, Wohnzimmer
geraet.stehlampe-wohnzimmer = dimmer, Stehlampe, Wohnzimmer
geraet.steckdose-kaffee = steckdose, Kaffeemaschine, Küche
geraet.rollladen-wohnzimmer = rollladen, Rollladen, Wohnzimmer
```

Format: `geraet.<id> = <typ>, <Name>, <Raum>`. Typen sind `licht`, `dimmer`, `steckdose` und `rollladen`.
Die ID brauchst du für Szenen und Regeln.

| Einstellung | Bedeutung |
|---|---|
| `verzoegerung-ms` | Antwortzeit pro Befehl (Standard 200) |
| `fehlerquote` | Prozent der Befehle, die absichtlich scheitern (Standard 0) |
| `rollladen-fahrzeit-s` | Sekunden von ganz zu bis ganz offen (Standard 12) |

Der Zustand wird beim Herunterfahren gespeichert. Nach einem Neustart ist also noch an, was vorher an war.

## Steuern

| Gerät | Befehle |
|---|---|
| Licht, Steckdose | `power an\|aus\|um` |
| Dimmer | zusätzlich `helligkeit 0–100` (0 = aus) |
| Rollladen | `position 0–100` (0 = zu, 100 = offen), `fahren auf\|zu\|stopp` |

In der Konsole: `device licht-kueche power an`. Mit `dummy` siehst du alle Test-Geräte, mit `dummy fehler 30`
lässt du bis zum Neustart 30 % der Befehle scheitern.
