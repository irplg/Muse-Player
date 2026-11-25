# Muse 的 release 构建开着 R8（见 app/build.gradle.kts）。
#
# 应用自身、Media3、OkHttp、Kotlin 协程都不依赖反射，库自带 consumer rules，
# 目前不需要额外 keep。唯一要注意的是不要对 com.nevoit.sfcompose 整体加 -keep：
# 那会把公开目录里 1.15 万个未引用的符号类全部留下，安装包立刻涨到 45MB。
#
# 如果以后新增了靠反射/序列化反查类名的代码，把对应模型类在这里 keep 住即可。
