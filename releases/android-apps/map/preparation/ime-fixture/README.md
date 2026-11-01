# Nur MAP-Instrumentation

Die beiden unveränderten Kotlin-Quelldateien stammen aus Commit67bdaa3c771956b1961a0de6aa6c04a6602312b3 des Spezialtastatur-Tests. MAP kompiliert den aktuellen Painter nur in sein androidTest-APK (optional ursprünglichen Painter über no5.keyboardBaseline=true). Der Copy-Task ersetzt nur die ungenutzte IME-Service-Theme-Ressourcenbindung. Keine IME-Serviceinstallation oder Aktivierung durch MAP; keine Änderung der laufenden IME. Diese lokalen Fixturepfade ersetzen die ursprüngliche Abhängigkeit auf das separate systemwindow-ime-test-Projekt.
