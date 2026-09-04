// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.hilt) apply false
  alias(libs.plugins.room) apply false
  alias(libs.plugins.spotless)
}

spotless {
  kotlin {
    target("app/src/**/*.kt")
    ktfmt(libs.versions.ktfmt.get())
  }
  kotlinGradle {
    target("build.gradle.kts", "settings.gradle.kts", "app/build.gradle.kts")
    ktfmt(libs.versions.ktfmt.get())
  }
}
