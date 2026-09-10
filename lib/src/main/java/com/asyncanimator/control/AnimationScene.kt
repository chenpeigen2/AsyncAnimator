package com.asyncanimator.control

/** Caller-supplied device facts. No guessed navigation mode or OEM configuration lookup. */
data class AppExitScene(
    val threeButtonNavigation: Boolean,
    val landscape: Boolean,
    val adaptiveLowAnimation: Boolean,
    val largeDisplayInLargeMode: Boolean = false
)

/** Portable snapshot of the gesture inputs consumed by the controller decision tree. */
data class GestureScene(
    val landscape: Boolean = false,
    val splitScreen: Boolean = false,
    val recentContinuation: Boolean = false,
    val tablet: Boolean = false,
    val homeAndOverviewSame: Boolean = false,
    val baseActivityPackage: String? = null,
    val topActivityPackage: String? = null
)
