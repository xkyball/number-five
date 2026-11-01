# Build- und Laufzeitabhängigkeiten

Der Gradle-Wrapper stammt aus der offiziellen Gradle-8.11.1-Distribution. Deren Lizenztext ist unverändert als `Gradle-Apache-LICENSE.txt` enthalten. Wrapper- und Distributionsprüfsummen sowie offizielle Bezugsquellen stehen in `../verification/build/toolchain.json`.

Android SDK/JDK/Gradle-Distribution und lokale Dependency-Caches werden nicht mit diesem Projekt ausgeliefert. Das Projekt bezieht seine festgelegten Abhängigkeiten aus offiziellen Registries. Das Debug-APK enthält die durch Gradle aufgelösten AndroidX-/Compose-/Kotlin-Laufzeitanteile. Ein öffentlicher Release muss deren aktuelle Lizenzhinweise und Anforderungen anhand des endgültigen Dependency-Graphen bewahren; dieses Projekt ist eine private Entwicklungshilfe, keine abgeschlossene Release-/Lizenzfreigabe.

Schriften und grafische Assets haben gesonderte Hinweise in `ASSET_RIGHTS.md` und `OFL-BarlowCondensed.txt`. Die Herkunft eines Assets ersetzt keine bestätigten Rechte für eine öffentliche Weitergabe.

Die Netzwerk-Vertragstests verwenden zusätzlich die festgelegte Abhängigkeit `org.json:json:20240303` ausschließlich auf der JVM. Im Android-APK nutzt der Decoder die vom Android-System bereitgestellte `org.json`-API; die JVM-Testbibliothek wird nicht als zusätzliche Laufzeitbibliothek verpackt.

Die isolierte SONAR-Vorbereitung ergänzt `com.squareup.okhttp3:okhttp:4.12.0` für den WebSocket-Transport. Der aufgelöste Graph enthält außerdem `com.squareup.okio:okio-jvm:3.6.0`. Beide Maven-POMs erklären Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt). Die vorhandene unveränderte Apache-2.0-Lizenzkopie liegt als `Gradle-Apache-LICENSE.txt` bei. Diese Versions-/Lizenznotiz ersetzt nicht die Prüfung sämtlicher transitiver Notices vor einer öffentlichen APK-Veröffentlichung.

No5 Home und die passive Knopfdiagnose verwenden native Android-Views und die bereits festgelegte Kotlin-Stdlib 2.2.10. Deren im Buildcache geprüfte offizielle Maven-POM nennt Apache License 2.0. Es wurden keine zusätzlichen UI-/OEM-Laufzeitbibliotheken eingeführt. Die Symbole sind eigene VectorDrawables; die Diagnose enthält keine Firmware-JAR und keine Klassenimplementierungen unter `android.qf`.
