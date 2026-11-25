# Muse 图标：SF Symbols（SFCompose）

Muse 的图标全部来自 [Nevodev/SFCompose](https://github.com/Nevodev/SFCompose) 发布到 Maven Central 的公开目录，不再使用 Feather，也不需要本地导入 Apple 素材、不需要离线转换脚本。

## 依赖与打包

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.nevodev.sfcompose")   // 0.1.2
}
```

- 运行库与目录：`io.github.nevodev:sfcompose:0.1.2`、`io.github.nevodev:sfcompose-catalog:0.1.2`（约 11 550 个条目），由插件自动加入，不用在 `dependencies` 里再写。
- 插件（`prepareReleaseSfcompose` / `reportReleaseSfcompose`）扫描代码里的 `SFSymbols.*` 引用，**只把被引用的符号数据打进 APK**。`ui/Glyphs.kt` 里列了哪些条目，安装包里就只有哪些，不会因为引入整个目录把包撑大。
- `ui/Glyphs.kt` 的 `Glyph(...)` 已改为直接渲染 `StaticSymbol`，其余界面的调用写法没变。

## 版本门槛（为什么升级了 AGP）

| 约束 | 数值 | 后果 |
|---|---|---|
| catalog AAR `minCompileSdk` | 37.1 | `compileSdk = 37` + `compileSdkMinor = 1`，需要 `platforms;android-37.1`（SDK 预览通道） |
| catalog / runtime AAR `minSdk` | 28 | 应用 `minSdk` 从 26 抬到 28（Android 9） |
| Compose 1.13.0（BOM 2026.11.01） | AGP ≥ 9.1.0 | AGP 8.13 会直接拒绝，必须升到 9.1.0 |
| AGP 9.1.0 | Gradle ≥ 9.3.1 | wrapper 与 CI 都改成 9.3.1 |
| SFCompose 编译用的 Kotlin | 2.4.21 | AGP 9 内置 Kotlin，需在根 `buildscript` 里把 KGP classpath 显式抬到 2.4.21 |

## 授权提示

SFCompose 自身代码是 Apache-2.0，但[第三方声明](https://github.com/Nevodev/SFCompose/blob/master/THIRD_PARTY_NOTICES.md)明确排除了从 Apple CoreGlyphs 导入的图形、几何和生成数据——那部分权利仍归 Apple，[SF Symbols 许可](https://developer.apple.com/sf-symbols/)本身也限制在 Apple 平台使用。个人编译自用问题不大；如果要公开分发带这些图标的 APK，请先自行确认授权。
