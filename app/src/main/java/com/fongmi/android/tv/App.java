package com.fongmi.android.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;

import com.fongmi.android.tv.Setting;
// import com.fongmi.android.tv.event.EventIndex; // 暂时注释，如果不存在则删除
import com.fongmi.android.tv.ui.activity.CrashActivity;
import com.fongmi.android.tv.utils.CacheCleaner;
import com.fongmi.android.tv.utils.UpdateInstaller;
import com.fongmi.android.tv.utils.WebDAVSyncManager;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.hook.Hook;
import com.github.catvod.Init;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.google.gson.Gson;
import com.orhanobut.logger.AndroidLogAdapter;
import com.orhanobut.logger.LogAdapter;
import com.orhanobut.logger.Logger;
import com.orhanobut.logger.PrettyFormatStrategy;

import org.greenrobot.eventbus.EventBus;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cat.ereza.customactivityoncrash.config.CaocConfig;

public class App extends Application {

    private final ExecutorService executor;
    private final Handler handler;
    private static App instance;
    private Activity activity;
    private final Gson gson;
    private final long time;
    private Hook hook;
    private final Runnable cleanTask;
    private final Runnable syncTask;
    private boolean appJustLaunched;

    public App() {
        instance = this;
        executor = Executors.newFixedThreadPool(Constant.THREAD_POOL);
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
        time = System.currentTimeMillis();
        gson = new Gson();
        cleanTask = this::checkCacheClean;
        syncTask = this::checkWebDAVSync;
        appJustLaunched = true;
    }

    public static App get() {
        return instance;
    }

    public static Gson gson() {
        return get().gson;
    }

    public static long time() {
        return get().time;
    }

    public static Activity activity() {
        return get().activity;
    }
    
    public static boolean isAppJustLaunched() {
        return get().appJustLaunched;
    }
    
    public static void setAppLaunched() {
        get().appJustLaunched = false;
    }

    public static void execute(Runnable runnable) {
        get().executor.execute(runnable);
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        get().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) get().handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        get().handler.removeCallbacks(runnable);
    }

    public static void removeCallbacks(Runnable... runnable) {
        for (Runnable r : runnable) get().handler.removeCallbacks(r);
    }

    public void setHook(Hook hook) {
        this.hook = hook;
    }

    private void setActivity(Activity activity) {
        this.activity = activity;
    }

    private LogAdapter getLogAdapter() {
        return new AndroidLogAdapter(PrettyFormatStrategy.newBuilder().methodCount(0).showThreadInfo(false).tag("").build()) {
            @Override
            public boolean isLoggable(int priority, String tag) {
                return true;
            }
        };
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Init.set(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.addLogAdapter(getLogAdapter());
        OkHttp.get().setProxy(Setting.getProxy());
        OkHttp.get().setDoh(Doh.objectFrom(Setting.getDoh()));
        // EventBus.builder().addIndex(new EventIndex()).installDefaultEventBus(); // 暂时注释，如果EventIndex不存在则删除
        EventBus.getDefault(); // 使用默认EventBus
        CaocConfig.Builder.create().backgroundMode(CaocConfig.BACKGROUND_MODE_SILENT).errorActivity(CrashActivity.class).apply();
        // Ensure default notification channel exists for foreground playback service (TV flavor too)
        Notify.createChannel();
        
        // 初始化自动缓存清理
        initCacheCleaner();
        
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                if (activity != activity()) setActivity(activity);
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                if (activity != activity()) setActivity(activity);
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                if (activity != activity()) setActivity(activity);
                // 应用回到前台时检查缓存
                checkCacheClean();
                // 检查是否有待安装的更新文件（用户从设置页面返回后）
                checkPendingInstall();
                // 检查WebDAV自动同步
                checkWebDAVSync();
                // 自动检查更新（如果启用）
                checkAutoUpdate(activity);
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                if (activity == activity()) setActivity(null);
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                if (activity == activity()) setActivity(null);
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
                if (activity == activity()) setActivity(null);
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            }
        });
    }

    private void initCacheCleaner() {
        CacheCleaner cleaner = CacheCleaner.get();
        cleaner.setCacheThreshold(200 * 1024 * 1024); // 固定使用200MB阈值
        
        // 定期检查缓存 (每30分钟)
        post(cleanTask, 30 * 60 * 1000);
    }
    
    private void checkCacheClean() {
        CacheCleaner.get().checkAndClean();
        // 每30分钟定期检查缓存
        post(cleanTask, 30 * 60 * 1000);
    }
    
    /**
     * 检查是否有待安装的更新文件
     * 当用户从设置页面授予安装权限后返回时，自动安装
     */
    private void checkPendingInstall() {
        UpdateInstaller installer = UpdateInstaller.get();
        if (installer.hasPendingInstall()) {
            Logger.d("App: 检测到待安装文件且权限已授予，自动安装");
            boolean success = installer.autoRetryInstall();
            if (success) {
                Notify.show("正在安装更新...");
            } else {
                Logger.e("App: 自动安装失败");
            }
        }
    }
    
    /**
     * 检查并执行WebDAV自动同步
     */
    private void checkWebDAVSync() {
        WebDAVSyncManager manager = WebDAVSyncManager.get();
        if (manager.isConfigured()) {
            // 应用启动时，如果已配置WebDAV，立即执行一次同步（下载远程数据）
            // 这样新设备配置后，下次启动应用时就能看到其他设备的历史记录
            App.execute(() -> {
                try {
                    Logger.d("App: 应用启动，执行WebDAV同步");
                    // 先上传本地记录
                    manager.uploadHistory();
                    // 再下载远程记录并合并
                    manager.downloadHistory();
                    Logger.d("App: WebDAV同步完成");
                } catch (Exception e) {
                    Logger.e("App: WebDAV同步失败: " + e.getMessage());
                }
            });
            
            // 如果启用了自动同步，设置定期同步
            if (Setting.isWebDAVAutoSync()) {
                int interval = Setting.getWebDAVSyncInterval();
                // 延迟执行下次同步，避免影响启动速度
                post(syncTask, interval * 60 * 1000L);
            }
        }
    }
    
    /**
     * 执行WebDAV同步
     */
    private void doWebDAVSync() {
        App.execute(() -> {
            WebDAVSyncManager manager = WebDAVSyncManager.get();
            if (manager.isConfigured()) {
                Logger.d("App: 开始WebDAV自动同步");
                manager.syncAll();
                // 设置下次同步
                int interval = Setting.getWebDAVSyncInterval();
                post(syncTask, interval * 60 * 1000L);
            }
        });
    }
    
    /**
     * 自动检查更新（如果启用）
     */
    private void checkAutoUpdate(Activity activity) {
        // 检查是否启用自动更新检查
        if (!Setting.getAutoUpdateCheck()) {
            return;
        }
        
        // 检查是否启用更新功能
        if (!Setting.getUpdate()) {
            return;
        }
        
        // 延迟一小段时间，避免影响应用启动速度
        post(() -> {
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                Logger.d("App: 开始自动检查更新");
                Updater.create().auto().release().start(activity);
            }
        }, 2000); // 延迟2秒
    }
    

    @Override
    public PackageManager getPackageManager() {
        return hook != null ? hook : getBaseContext().getPackageManager();
    }

    @Override
    public String getPackageName() {
        return hook != null ? hook.getPackageName() : getBaseContext().getPackageName();
    }
}