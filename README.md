# B站去暂停广告（NoPauseAd）

一个极简 LSPosed 模块：去掉哔哩哔哩 **暂停视频后弹出的暂停页广告**（9.x 起出现，暂停 3 秒左右弹出）。

- 包名：`com.alexliu07.nopausead`
- 作用域：`tv.danmaku.bili`（国内版）、`com.bilibili.app.in`（国际版），已内置 `xposedscope`，装好后 LSPosed 里会自动勾选
- 已构建好的 APK：`../dist/NoPauseAd-1.0.apk`
- 支持 Android 5.0+（minSdk 21），已在 B 站 9.17.0 上按反编译结果核对

## 原理（基于 9.17.0 反编译确认）

暂停广告的链路：

```
用户暂停 / 双击暂停
  -> PausedPageServiceImpl.j(PausedPageAdSource)      // 日志 event=pause_intent
  -> PausedPageServiceImpl$pause$1
  -> PausedPageController.F(source)
  -> PausedPageController.start(source)                // 先检查 getEnabled()
  -> 请求广告 -> 倒计时 3 秒 -> 全屏暂停广告
```

开关最终落在：

```
kntr.app.ad.biz.videodetail.pausedpage.h.getEnabled()
  -> kntr.base.config.KConfig.ff("dd_ad_pausedpage_enable", true)
```

模块做了三件事：

1. **主钩子**：把 `PausedPageServiceImpl` 中「非静态、返回 void、唯一参数类型以 `PausedPageAdSource` 结尾」的方法
   （当前是 `j`）替换成空实现。这样暂停意图根本不会进入广告流程，
   而「视频结束页」走的是另一条路径（`prepareViewEndPagePausedPage`），不受影响。
   用签名匹配而不是写死方法名，B 站小版本改名也能命中。
2. **老版兼容**：`com.bilibili.adcommon.config.DDConfig.getPausePageEnable()` 返回 `false`
   （老 theseus 播放页的开关；这类页面上的暂停页广告也会一起没有）。
3. **兜底**：如果主钩子没命中（B 站大改结构），改为把 `KConfig.ff("dd_ad_pausedpage_enable")` 强制成 `false`。
   注意这种情况下「结束页暂停广告」也会一起消失。

## 安装

1. 安装 `NoPauseAd-1.0.apk`。
2. LSPosed 里启用模块，作用域勾选「哔哩哔哩」（模块已声明，通常自动勾选）。
3. 强制停止哔哩哔哩再打开（不是清后台，是「强行停止」）。

## 验证是否生效

```bash
adb logcat -s LSPosed-Bridge:* | findstr NoPauseAd
```

看到类似输出即生效：

```
NoPauseAd: hook PausedPageServiceImpl.j(PausedPageAdSource) -> no-op
NoPauseAd: hook com.bilibili.adcommon.config.DDConfig.getPausePageEnable() -> false
NoPauseAd: loaded for tv.danmaku.bili (pauseIntent=true, legacyConfig=true)
```

之后暂停视频，等 3 秒以上，不应再出现广告。

## 自己构建

本地（需要 JDK 17+ 和 Android SDK）：

```bash
cd nopausead
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions：把 `nopausead/` 推到仓库，`.github/workflows/build.yml` 已就绪，
Actions → Build APK → Run workflow → 下载 artifact。

> 本目录的 APK 是我用 ``aapt2 + javac + d8 + apksigner`` 直接打包的（debug 证书，v1+v2+v3 签名）。
> 要长期使用/覆盖安装，建议换成自己的 keystore 重新签名。

## 兼容性说明

- 只对 **暂停/双击暂停** 生效；「视频结束页广告」在正常路径下保留。
- B 站更新后如果类名或包名大改，`hookPauseIntent` 会失败并自动走 `KConfig` 兜底（结束页广告也会一起消失）。
- 个别页面（老 theseus 播放页）由 `DDConfig` 开关控制，暂停页广告也会被关掉。
- 只是隐藏/拦截客户端展示，不修改服务端请求逻辑，不涉及账号风险。