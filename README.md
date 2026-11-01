# Number Five · offene Technik fürs Boot

**Ein Boot, neun offene Teilprojekte. / One boat, nine open projects.**

Project website: https://no5.christophhoffmann.net/de · [English](https://no5.christophhoffmann.net/en)

Number Five verbindet native Android-Instrumente, Karten, Audio, Pi-Dienste, Sonar und eigene Hardware zu einem offenen Smart-Boat-Baukasten. Eine funktionierende Version ist ein Ergebnis und der Anfang der nächsten Verbesserung. Erfahrungen aus Nutzung, Einbau und Fertigung fließen in die gemeinsamen Quellen zurück. Entwickelt mit KI-Unterstützung und auf den ausdrücklich genannten offenen Grundlagen.

Number Five connects native Android instruments, charts, audio, Pi services, sonar and custom hardware in an open Smart-Boat building kit. A working version is an achievement and the starting point for the next improvement. Experience from use, installation and fabrication feeds back into the shared sources. Developed with AI assistance and on the credited open foundations.

[Philosophie](https://no5.christophhoffmann.net/de/philosophy) · [Philosophy](https://no5.christophhoffmann.net/en/philosophy)

## What is here

- This bilingual Sites/Vinext website (`app/`, `content/`, `public/`).
- Source snapshots under `releases/`, each with its own scope, provenance, licences and build notes. Most download archives are in `public/downloads/`; the large Android source archive is a GitHub release asset.
- Public evidence notes and image provenance under `docs/`.
- The [BBN introduction](https://github.com/orgs/bareboat-necessities/discussions/562) is published in “Show and tell”. A separate German OBP introduction is prepared.

The nine website modules explain system/data, HELM, audio, displays/controls, maps/routes, existing plotter formats, sonar, sensors/electronics, and enclosures/installation. Experimental active noise control is a separate research note. Existing private working repositories have not been made public or replaced by this repository.

## Website development

Node >=22.13.0. Use the included lockfile:

```sh
npm ci
npm run check
npm run test:core
npm run build
npm run dev -- --hostname 127.0.0.1 --port 5185
```

`docs/DEVELOPMENT.md` explains local D1, owner access, validation and publication. No task opens a browser automatically. The site runs a server-side contact inbox; no mail service or automatic notification is configured.

## Rights

New original website software: GPL-3.0-only. New original hardware contributions: CERN-OHL-S-2.0. Original documentation and images: CC-BY-SA-4.0. Existing notices and upstream licences remain in force for their respective files. See [LICENSING.md](LICENSING.md), [THIRD_PARTY.md](THIRD_PARTY.md) and each source package. Commercial reuse follows those licences; no rights to third-party charts, keys, OEM firmware or Spotify content are supplied.

## Mitmachen / Contribute

Nachbauen, messen, drucken, löten, Teile beschaffen und verständlich dokumentieren hilft genauso wie Code. Read [CONTRIBUTING.md](CONTRIBUTING.md), or use the [private contact form](https://no5.christophhoffmann.net/de/contact).
