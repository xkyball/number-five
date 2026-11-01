package com.no5.helm

/** One physical-stage contract for the cardless fascia and its touch surfaces. */
internal object DashboardGeometry {
    val helm = AudioNativeRect(24f,144f,1256f,740f)
    val sonar = AudioNativeRect(1300f,144f,676f,740f)
    val audio = AudioNativeRect(280f,908f,1416f,136f)
    val content = listOf(helm,sonar,audio)
    val echogram = AudioNativeRect(1304f,232f,626f,380f)
    val history = AudioNativeRect(484f,550f,340f,48f)
    val audioDetail = AudioNativeRect(280f,918f,300f,104f)
    val sonarDetail = AudioNativeRect(1304f,148f,650f,576f)
    val actions = linkedMapOf(
        "dashboard-range-down" to AudioNativeRect(1304f,788f,96f,96f),
        "dashboard-range-up" to AudioNativeRect(1408f,788f,96f,96f),
        "dashboard-gain-down" to AudioNativeRect(1530f,788f,96f,96f),
        "dashboard-gain-up" to AudioNativeRect(1634f,788f,96f,96f),
        "dashboard-freeze" to AudioNativeRect(1780f,788f,184f,96f),
        "dashboard-previous" to AudioNativeRect(604f,930f,96f,96f),
        "dashboard-play-pause" to AudioNativeRect(716f,922f,112f,112f),
        "dashboard-next" to AudioNativeRect(844f,930f,96f,96f),
        "dashboard-fader-down" to AudioNativeRect(1106f,930f,96f,96f),
        "dashboard-fader" to AudioNativeRect(1210f,930f,272f,96f),
        "dashboard-fader-up" to AudioNativeRect(1490f,930f,96f,96f),
        "dashboard-mute" to AudioNativeRect(1594f,930f,96f,96f),
    )
}
