// Top-level build file where you can add configuration options common to all sub-projects/modules.
// AGP 9.0 enables built-in Kotlin by default: the org.jetbrains.kotlin.android plugin is no longer needed.
// The Compose compiler plugin (org.jetbrains.kotlin.plugin.compose) is still required and must match KGP.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
