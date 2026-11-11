pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PhoneCaptureTracker"

// :app           – the PhoneCaptureTracker application (package com.phonecapture.tracker)
// :capture       – Android library with every capture feature; drop-in module for Mythos Tracker
// :capture-core  – pure Kotlin/JVM logic (scheduler, encryption, policies), unit-testable without Android
include(":app", ":capture", ":capture-core")
