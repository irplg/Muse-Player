buildscript {
    dependencies {
        // AGP 9 内置 Kotlin 支持，但默认 KGP 低于 SFCompose 编译时使用的 2.4.21，
        // 会让符号库的 Kotlin 元数据版本过新而读不了，所以显式抬到同一版本。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.1.0" apply false
    // Compose 编译器插件仍然要单独应用，AGP 9 不会自动带上。
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("io.github.nevodev.sfcompose") version "0.1.2" apply false
}
