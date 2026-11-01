# Number Five · offene Technik fürs Boot

**Ein Boot, neun offene Teilprojekte. / One boat, nine open projects.**

Project website: https://no5.christophhoffmann.net/de · [English](https://no5.christophhoffmann.net/en)

Number Five connects native Android instruments, charts, audio, a Raspberry Pi gateway, experimental sonar and practical hardware builds. This is a personal development project by Christoph, built with substantial AI assistance and on existing open-source foundations. It is still evolving; a source package is not a completed on-water system acceptance.

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
