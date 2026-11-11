package com.alexliu07.nopausead;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 去除哔哩哔哩「暂停视频后弹出的暂停页广告」（简称暂停广告）。
 *
 * 9.17.0 的暂停广告链路（已通过反编译确认）：
 *
 *   用户暂停 / 双击暂停
 *     -> PausedPageServiceImpl.j(PausedPageAdSource)          // 日志 event=pause_intent
 *     -> PausedPageServiceImpl$pause$1
 *     -> PausedPageController.F(...)
 *     -> PausedPageController.start(...)                      // 内部先检查 getEnabled()
 *     -> 请求广告 -> 倒计时 3 秒 -> 全屏暂停广告
 *
 * 其中 getEnabled() 的实现在
 *   kntr.app.ad.biz.videodetail.pausedpage.h.getEnabled() ->
 *   kntr.base.config.KConfig.ff("dd_ad_pausedpage_enable", true)
 *
 * 本模块的做法：
 *   1) 主钩子：让 PausedPageServiceImpl 里「唯一参数类型为 PausedPageAdSource、返回 void」
 *      的暂停入口方法直接空实现 —— 只掐掉「暂停」触发的广告，
 *      「视频结束页广告」（VIEW_END_PAGE）走另一条路径，不受影响。
 *   2) 兼容钩子：老版 theseus 播放页用 DDConfig.getPausePageEnable() 判断，
 *      一并返回 false。
 *   3) 兜底：如果主钩子没找到（B 站改了结构），退回改 KConfig 里的开关
 *      dd_ad_pausedpage_enable （注意：这种情况下结束页暂停广告也会一起消失）。
 */
public final class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "NoPauseAd: ";

    private static final String PKG_CN = "tv.danmaku.bili";
    private static final String PKG_INTL = "com.bilibili.app.in";

    private static final String CLS_PAUSED_PAGE_SERVICE_IMPL =
            "com.bilibili.ship.theseus2.united.page.pausedpage.PausedPageServiceImpl";
    private static final String CLS_DD_CONFIG = "com.bilibili.adcommon.config.DDConfig";
    private static final String CLS_KCONFIG = "kntr.base.config.KConfig";
    private static final String KEY_PAUSED_PAGE_ENABLE = "dd_ad_pausedpage_enable";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PKG_CN.equals(lpparam.packageName) && !PKG_INTL.equals(lpparam.packageName)) {
            return;
        }
        ClassLoader cl = lpparam.classLoader;

        boolean pauseIntentHooked = hookPauseIntent(cl);
        boolean legacyHooked = hookLegacyConfig(cl);
        if (!pauseIntentHooked) {
            hookKConfigSwitch(cl);
        }

        XposedBridge.log(TAG + "loaded for " + lpparam.packageName
                + " (pauseIntent=" + pauseIntentHooked + ", legacyConfig=" + legacyHooked + ")");
    }

    /**
     * 主钩子：PausedPageServiceImpl 的暂停入口。
     * 方法名在混淆版本里可能是 j / k / ...，所以按签名匹配：
     * 非静态、返回 void、只有一个参数且参数类型名以 PausedPageAdSource 结尾。
     */
    private static boolean hookPauseIntent(ClassLoader cl) {
        boolean hooked = false;
        try {
            Class<?> service = XposedHelpers.findClass(CLS_PAUSED_PAGE_SERVICE_IMPL, cl);
            for (Method m : service.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != void.class) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 1) continue;
                if (!params[0].getName().endsWith("PausedPageAdSource")) continue;

                XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING);
                XposedBridge.log(TAG + "hook " + service.getSimpleName() + "."
                        + m.getName() + "(" + params[0].getSimpleName() + ") -> no-op");
                hooked = true;
            }
            if (!hooked) {
                XposedBridge.log(TAG + "no pause-intent method matched in " + CLS_PAUSED_PAGE_SERVICE_IMPL);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hookPauseIntent failed: " + t);
        }
        return hooked;
    }

    /** 老版 theseus 播放页的开关：com.bilibili.adcommon.config.DDConfig#getPausePageEnable() */
    private static boolean hookLegacyConfig(ClassLoader cl) {
        try {
            Class<?> ddConfig = XposedHelpers.findClass(CLS_DD_CONFIG, cl);
            XposedBridge.hookAllMethods(ddConfig, "getPausePageEnable",
                    XC_MethodReplacement.returnConstant(Boolean.FALSE));
            XposedBridge.log(TAG + "hook " + CLS_DD_CONFIG + ".getPausePageEnable() -> false");
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hookLegacyConfig skipped: " + t);
            return false;
        }
    }

    /**
     * 兜底：直接把 KConfig 的暂停广告开关改成 false。
     * 只在主钩子失效时启用（它同时会干掉结束页广告）。
     */
    private static void hookKConfigSwitch(ClassLoader cl) {
        try {
            Class<?> kConfig = XposedHelpers.findClass(CLS_KCONFIG, cl);
            XposedBridge.hookAllMethods(kConfig, "ff", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object[] args = param.args;
                    if (args != null && args.length >= 1 && KEY_PAUSED_PAGE_ENABLE.equals(args[0])) {
                        param.setResult(Boolean.FALSE);
                    }
                }
            });
            XposedBridge.log(TAG + "fallback: " + CLS_KCONFIG + ".ff(\""
                    + KEY_PAUSED_PAGE_ENABLE + "\") -> false");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hookKConfigSwitch failed: " + t);
        }
    }
}