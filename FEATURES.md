# 📋 Geplante Features – v2

## 🚀 Kernfunktionen
- 🔄 **Periodischer Scan** der Welt (Standard: alle 20 Ticks) auf gedroppte Items.
- 📊 **Schwellwert-Erkennung**: Cleanup wird nur aktiviert, wenn mehr als X Entities existieren.
- 🗑️ **Intelligentes Löschen**:
    - Entfernt nur Items, die älter als eine bestimmte Zeit (Standard: 5 Minuten) sind.
    - Maximal Y Items pro Zyklus, um Lag-Spikes zu vermeiden.
- 💾 **Persistenz**:
    - Items werden zwischen Zyklen verfolgt (UUID, Position, Alter, Typ).
    - Persistente Speicherung (Welt-spezifisch) → nur noch existierende Items werden im nächsten Scan berücksichtigt.

## ⚙️ Filter-System
- **Wählbarer Filtermodus**:
    - **Blacklist-Modus** → Alle Items dürfen gelöscht werden, *außer* den definierten.
    - **Whitelist-Modus** → Es werden *nur* die definierten Items gelöscht.
- **Region-Whitelist** *(geplant)*: Möglichkeit, bestimmte Regionen oder Chunks vor Cleanup zu schützen.

## 🛡️ Sicherheit
- Schutz für benannte Items (optional).
- Schutz für Items in unmittelbarer Nähe von Spielern (Radius konfigurierbar).

## 🔧 Konfiguration
- Einfache **TOML-Config** pro Serverinstanz:
    - Scan-Intervall
    - Item-Limit für Cleanup
    - Max. Löschungen pro Zyklus
    - Mindestalter von Items
    - Filtermodus (Whitelist / Blacklist)
    - Definierte Filterliste

## 💬 Komfort & Verwaltung
- **Commands**:
    - `/cleanup now [force]` – sofortiger Cleanup
    - `/cleanup stats` – aktuelle Statistiken anzeigen
    - `/cleanup dryrun` – zeigt, was gelöscht werden würde
- **Benachrichtigungen** (Chat oder Toast, optional, rate-limitiert).
- **Statistiken/Metriken**:
    - Anzahl gelöschter Items
    - Laufzeiten pro Scan
    - Historie (optional JSON-Log)

## 🔌 Interoperabilität
- **Events/Hooks**:
    - `CleanupPreEvent` / `CleanupPostEvent` für andere Mods oder Server-Skripte.
- **Tag-Unterstützung**: Filterlisten können auch Minecraft-Tags (`#tag`) verwenden.

## ✅ Tests & Stabilität
- Unit-Tests für Filter- und Policy-Logik.
- GameTests für kontrollierte Item-Szenarien.
- Lasttests (z. B. mehrere tausend Items).  
