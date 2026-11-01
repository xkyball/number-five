# No5 simulator 0.7.10

Run from the bundle root with `python3 -m simulator --weather-off`, then open http://127.0.0.1:8080/.

The gateway starts READY. START begins the voyage. Simulator settings now offers explicit environment tests: SUNNY, OVERCAST, FOG, TWILIGHT and custom scene UTC/wind/cloud/visibility. Presets edit a draft; Apply acknowledges the revision. Select Gateway weather in Android and use ANIMATION + DAY. Direct Open-Meteo remains independent.

RETURN TO LIVE + Apply returns to the real gateway provider. Weather sharing must be ON for external requests. HELM/AUDIO/SONAR are synthetic. Weather is either explicitly SIMULATED or a real provider forecast; no automatic synthetic fallback exists. Voyage Reset retains applied settings; process restart starts live.

Python standard library only for runtime. Optional contract tests use the existing development dependency in `tools/gateway-contract-requirements.txt`.

[Environment setup](../docs/SIMULATOR_ENVIRONMENT.md) · [Control API](../docs/SIMULATOR_CONTROL_API.md) · [Executed verification](../verification/STATUS.md)

Audio Console V2: optional FRONT controls and explicit synthetic meter groups are supplied
through the existing gateway. The simulator still plays no sound and has no hardware
output. Use AUDIO in Preview 0.7.10 / Build20 to test FRONT/REAR/SUB, Master and
Sound Setup. The five legacy fader keys stay unchanged for older No5 clients.
