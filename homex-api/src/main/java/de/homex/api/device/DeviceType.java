package de.homex.api.device;

public enum DeviceType {
    LIGHT,          // Licht (an/aus, optional "helligkeit")
    SWITCH,         // Schalter / Steckdose
    SHUTTER,        // Rollladen / Jalousie ("position" 0 = zu … 100 = offen)
    CLIMATE,        // Klimaanlage / Heizung
    SENSOR,         // Messwerte (Temperatur, Feuchte …)
    POWER_METER,    // Stromzähler
    SOLAR_INVERTER, // Wechselrichter / Solaranlage
    BATTERY,        // Batteriespeicher
    WEATHER,        // Wetterdaten
    SCENE,          // Szene (mehrere Befehle auf einmal)
    AUTOMATION,     // Regel / Automation
    OTHER
}
