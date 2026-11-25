# Muse · 安卓音乐播放器

Apple Music 风格逐字歌词动画 + 网易云登录 + 本地音乐 + 一起听，界面是 **Material 3 Expressive × iOS 风格**。
完成构建后可得到通用 APK：`app-release.apk`（不拆 ABI、不出 AAB）。当前版本 0.2.0，已在本机用 JDK 17 + Gradle 8.13 + compileSdk 36 实际编译通过。

## 功能
- **歌词动画**：YRC 高亮按唱词时码连续推进，用渐变柔化亮暗交界；较长的词、句及唱词时长会延长上浮效果。保留折行、翻译和点词跳转。进度条只在已播放区绘制波浪，不再叠加直线底轨；未播放尾段保留淡色轨道。减少动态效果时保持静态。
- **登录网易云**：我的 → 手机验证码登录，内嵌打开 music.163.com/login，用手机号 + 短信验证码完成登录；页面登录成功后自动嗅探 Cookie（MUSIC_U）写回本机，无需手动复制。也保留扫码登录（另一台设备上的网易云 App 扫二维码）。凭据只保存在本机。
- **本地音乐**：扫描手机里的音频，后台播放、通知栏/锁屏/耳机控制。本地歌曲的歌词会按「歌名 歌手」去网易云自动匹配（同目录同名 .lrc 能读到时优先用）。
- **首页与搜索**：登录后首页头图打开「我喜欢的音乐」，取其第一首曲目的封面；卡片说明区用同一图片模糊处理。网易云精选公开歌单排在个人歌单之前。搜索首艺人卡按第一首歌曲的第一位艺人 ID 获取头像，不用专辑图替代；点歌手/专辑打开独立详情。
- **一起听（实验）**：创建/加入房间、邀请、成员列表、播放/暂停/切歌/拖动上报及远端指令同步；官方协议可能不兼容，尚不能保证覆盖网易云一起听全部功能。
- **空间音频**：设置里的开关。Android 12L 起若设备声明了 Spatializer，就只把音轨标记为可空间化、交给系统做双耳渲染；没有系统空间音频（含安卓 10 及更早）时走软件立体声外化——中/侧分离展宽 + 0.28ms 耳间延时 + 700Hz 头部遮蔽低通 + 软限幅，戴上耳机就能听出声场从颅内推到颅外。系统接管时自动停用软件处理，不叠加两次。
- **播放与界面**：封面采样的缓慢流体背景、逐字歌词辉光及系统减少动画适配；分区首页、悬浮迷你播放器、设置/关于页。请求日志只可从「我的 → 设置 → 导出日志」保存，不显示调试面板。界面图标使用成熟的 Compose Icons Feather 单色线性图标（MIT），统一 24dp tint，视觉接近 SF Symbols 的干净线条风格；未使用渐变色或双色图标。来源：[Compose Icons](https://github.com/DevSrSouza/compose-icons)、[Feather](https://github.com/feathericons/feather)。

## 怎么得到 APK（三选一，推荐 A）

### A. GitHub 云端打包（不用装任何东西）
1. 在 GitHub 新建一个仓库，把本文件夹里的**所有文件**（包含隐藏的 `.github` 文件夹）上传/推送上去。
   - 网页上传看不到 `.github`？在仓库页点 *Add file → Create new file*，文件名填 `.github/workflows/build-apk.yml`，把本包里同名文件的内容粘贴进去。
2. 打开仓库的 **Actions** 页 → 选 **Build APK** → **Run workflow**（推送到 main 也会自动跑）。
3. 跑完（约 4～8 分钟）在运行详情页最下面的 **Artifacts** 下载 `Muse-apk`，解压得到 `app-release.apk`，传到手机安装（需允许“安装未知应用”）。

### B. Android Studio
用 Android Studio（Narwhal 或更新）打开本文件夹 → 等 Gradle 同步 → *Build → Build Bundle(s)/APK(s) → Build APK(s)*，
或命令行 `gradle :app:assembleRelease`（需要 JDK 17 + Gradle 8.13），产物在 `app/build/outputs/apk/release/app-release.apk`。

## 诚实说明（请先读）
- **验证范围**：所有应用 Kotlin 源文件均已通过 Kotlin 2.2.20 / Compose 编译器插件的独立编译检查；逐词动效另通过 19 项时序断言。未执行 Gradle APK 构建、真机界面验证或双设备房间同步测试。
- **一起听依赖非官方接口**：房主/成员双向同步逻辑已修正，但未经双设备实测。仅当服务器心跳返回明确播放指令时可接收；官方多人/易盾协议、聊天/互动等能力未实现，不能保证官方 App 互通。日志仅记录接口状态摘要；从设置导出前请人工检查隐私。
- 手机验证码登录走的是网易云自己的网页登录页（WebView），应用不读取、不注入任何表单，只在该域名下轮询 Cookie 里是否出现 MUSIC_U；退出登录时一并清空 WebView 的 Cookie，避免下次进登录页被自动带回原账号。
- 空间音频的软件通路是「立体声外化」而不是头部追踪或杜比全景声：它改变的是耳机下的声场宽度和左右串扰，对音箱外放几乎没有意义；系统空间音频仍在系统设置里单独开关。
- 网易云接口是非官方、逆向得到的，可能随时变化，也可能不符合其服务条款，请自行评估后用于个人学习/自用。
- 歌曲能否播放取决于你账号的会员/版权；不能播的歌会在播放页显示原因。

## 视觉参考与图标来源

- [Apple Music 新发现](https://music.apple.com/cn/new)：参考首页内容分区；非 Apple 官方产品。
- [Apple Music 官网](https://www.apple.com/apple-music/)：参考播放器视觉层级。
- [AMLL Core](https://www.npmjs.com/package/@applemusic-like-lyrics/core/v/0.7.0)：参考按词时间分开驱动点亮、浮动与辉光的设计，未嵌入 Web 库；没有逐词时间的歌词不会伪造逐词动画。
- [AndroidX Compose Animation](https://github.com/androidx/androidx/tree/androidx-main/compose/animation)：使用项目已有的开源 Compose 动画库做页面转场与一起听弹窗。独立搜索保留本机历史；一起听入口位于播放控制区。宽屏播放器分栏，歌单和本地音乐在 720dp 起使用双列。
- 默认构建使用 [Feather](https://github.com/feathericons/feather) 单色图标（MIT）。如拥有相应素材授权，可按 [SFCompose 本地构建说明](SFCompose-ICONS.md) 启用真实 SFCompose 目录中的单色符号；默认源码包不含 Apple 图形，无渐变图标。

## 目录
```
app/src/main/java/app/muse/player/
  data/    Netease(接口+加密) Lyrics(LRC/YRC解析) LocalMusic Together(一起听) Models
  player/  PlayerHub(队列/播放器) PlaybackService(通知栏/锁屏) SpatialAudio(空间音频处理链)
  ui/      AppleLyrics(歌词动画) NowPlaying Screens Chrome Theme Art Glyphs
           PhoneLoginScreen(手机验证码登录 WebView + Cookie 嗅探)
.github/workflows/build-apk.yml   云端打包脚本
```
