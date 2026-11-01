# Tatsächliche HELM-Headerassets

Quelle: `xkyball/no5`, Commit `5c6436c8314f65fb91154d40dff8c787ac5caeb4`, 0.7.18 Build28.

`scene_day.json` und `scene_night.json` enthalten die unveränderten ausgewählten Knoten
aus `app/src/main/assets/helm/scene_*.json`: `brand-divider`, `theme-switch-shell`,
`header-sun`, `header-wifi-icon` und die zwei äußeren Schulterpfade. `aperture` ist der
unveränderte `media-aperture`-Clippfad. Der native Canvas clippt die Zeichnung auf den Header.

`ocean.png` ist die unveränderte Originaldatei. Der Header übernimmt HELMs IMAGE-Präferenz
mit demselben Quellcrop und der Alpha-Maske des `OceanBackgroundRenderer`.
Kein GL-/Ocean-Renderer, Netzwerkclient oder MainActivity wurde übernommen.

Headertext, Fonts und Theme-Schalter stammen aus dem tatsächlichen aktiven
`HelmScene.drawHeaderFallback()`-/`drawThemeSwitch()`-Pfad. Herkunft und SHA-256-Hashes
stehen in `evidence/helm-header-source-comparison.json` des isolierten Projekts.
