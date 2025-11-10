package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.Setting;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Backup;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.db.AppDatabase;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Logger;
import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 同步码管理器（无需WebDAV账号）
 * 使用公开的HTTP存储服务，通过同步码区分用户
 * 
 * 方案：使用GitHub Gist作为存储
 * - 用户创建一个公开的GitHub Gist
 * - 通过同步码作为文件名的一部分来区分不同用户
 * - 所有知道同步码的设备可以共享数据
 */
public class SyncCodeManager {
    
    private static final String HISTORY_FILE_PREFIX = "xmbox_history_";
    private static final String SETTINGS_FILE_PREFIX = "xmbox_settings_";
    private static final String BACKUP_FILE_PREFIX = "xmbox_backup_";
    private static final String FILE_SUFFIX = ".json";
    
    private static SyncCodeManager instance;
    private String syncCode;
    private String gistId;  // GitHub Gist ID
    private String gistToken;  // GitHub Personal Access Token（用于上传，可选）
    
    public static SyncCodeManager get() {
        if (instance == null) {
            instance = new SyncCodeManager();
        }
        return instance;
    }
    
    private SyncCodeManager() {
        loadConfig();
    }
    
    /**
     * 加载配置
     */
    private void loadConfig() {
        syncCode = Setting.getWebDAVSyncCode();
        gistId = Setting.getWebDAVGistId();
        gistToken = Setting.getWebDAVGistToken();
    }
    
    /**
     * 检查是否已配置
     */
    public boolean isConfigured() {
        return !TextUtils.isEmpty(syncCode) && !TextUtils.isEmpty(gistId);
    }
    
    /**
     * 生成同步码
     * @return 8位随机同步码（字母+数字）
     */
    public static String generateSyncCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random random = new Random();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            code.append(chars.charAt(random.nextInt(chars.length())));
        }
        return code.toString();
    }
    
    /**
     * 获取文件URL（GitHub Gist raw URL）
     */
    private String getFileUrl(String prefix) {
        // GitHub Gist raw URL格式：
        // https://gist.githubusercontent.com/{username}/{gist_id}/raw/{filename}
        // 文件名格式：{prefix}{syncCode}.json
        // 例如：xmbox_history_ABC123XYZ.json
        
        // 如果用户提供了完整的Gist raw URL
        String gistRawUrl = Setting.getWebDAVGistRawUrl();
        if (!TextUtils.isEmpty(gistRawUrl)) {
            String filename = prefix + syncCode + FILE_SUFFIX;
            return gistRawUrl + "/" + filename;
        }
        
        // 否则需要从Gist ID构建（需要知道username）
        // 这里简化处理，要求用户提供完整的raw URL
        return null;
    }
    
    /**
     * 上传观看记录
     */
    public boolean uploadHistory() {
        if (!isConfigured()) {
            Logger.e("SyncCode: 未配置，无法上传观看记录");
            return false;
        }
        
        try {
            // 获取所有观看记录
            List<History> historyList = AppDatabase.get().getHistoryDao().findAll();
            String json = App.gson().toJson(historyList);
            
            // 上传到GitHub Gist
            String fileUrl = getFileUrl(HISTORY_FILE_PREFIX);
            if (fileUrl == null) {
                Logger.e("SyncCode: 无法构建文件URL，请配置Gist Raw URL");
                return false;
            }
            
            // 使用GitHub Gist API更新文件
            boolean success = updateGistFile(fileUrl, json);
            if (success) {
                Logger.d("SyncCode: 观看记录上传成功，共 " + historyList.size() + " 条");
            }
            return success;
        } catch (Exception e) {
            Logger.e("SyncCode: 观看记录上传失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 下载观看记录
     */
    public boolean downloadHistory() {
        if (!isConfigured()) {
            Logger.e("SyncCode: 未配置，无法下载观看记录");
            return false;
        }
        
        try {
            String fileUrl = getFileUrl(HISTORY_FILE_PREFIX);
            if (fileUrl == null) {
                return false;
            }
            
            // 从GitHub Gist下载文件
            String json = downloadGistFile(fileUrl);
            if (TextUtils.isEmpty(json)) {
                Logger.d("SyncCode: 观看记录文件不存在，跳过下载");
                return false;
            }
            
            Type listType = new TypeToken<List<History>>(){}.getType();
            List<History> remoteHistoryList = App.gson().fromJson(json, listType);
            
            // 智能合并（与WebDAV相同的逻辑）
            if (!remoteHistoryList.isEmpty()) {
                List<History> localHistoryList = AppDatabase.get().getHistoryDao().findAll();
                
                Map<String, History> localMap = new HashMap<>();
                for (History local : localHistoryList) {
                    localMap.put(local.getKey(), local);
                }
                
                List<History> toInsert = new java.util.ArrayList<>();
                List<History> toUpdate = new java.util.ArrayList<>();
                
                for (History remote : remoteHistoryList) {
                    History local = localMap.get(remote.getKey());
                    
                    if (local == null) {
                        toInsert.add(remote);
                    } else {
                        if (remote.getCreateTime() > local.getCreateTime()) {
                            toUpdate.add(remote);
                        } else if (remote.getCreateTime() == local.getCreateTime() && remote.getPosition() > local.getPosition()) {
                            toUpdate.add(remote);
                        }
                    }
                }
                
                if (!toInsert.isEmpty()) {
                    AppDatabase.get().getHistoryDao().insert(toInsert);
                    Logger.d("SyncCode: 新增 " + toInsert.size() + " 条观看记录");
                }
                if (!toUpdate.isEmpty()) {
                    AppDatabase.get().getHistoryDao().update(toUpdate);
                    Logger.d("SyncCode: 更新 " + toUpdate.size() + " 条观看记录");
                }
                
                Logger.d("SyncCode: 观看记录合并完成");
                return true;
            }
            
            return false;
        } catch (Exception e) {
            Logger.e("SyncCode: 观看记录下载失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 从GitHub Gist下载文件
     */
    private String downloadGistFile(String fileUrl) {
        try {
            Response response = OkHttp.newCall(fileUrl).execute();
            if (response.isSuccessful()) {
                return response.body().string();
            }
            return "";
        } catch (Exception e) {
            Logger.e("SyncCode: 下载文件失败: " + e.getMessage());
            return "";
        }
    }
    
    /**
     * 更新GitHub Gist文件
     * 注意：GitHub Gist需要通过API更新，不能直接PUT文件
     */
    private boolean updateGistFile(String fileUrl, String content) {
        // GitHub Gist需要通过REST API更新
        // 这里简化处理，实际需要调用GitHub Gist API
        // POST https://api.github.com/gists/{gist_id}
        
        if (TextUtils.isEmpty(gistToken)) {
            Logger.w("SyncCode: 未提供GitHub Token，无法上传（Gist需要Token才能更新）");
            // 可以提示用户：同步码模式需要GitHub Token才能上传
            return false;
        }
        
        try {
            // 构建GitHub Gist API请求
            String apiUrl = "https://api.github.com/gists/" + gistId;
            String filename = HISTORY_FILE_PREFIX + syncCode + FILE_SUFFIX;
            
            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            Map<String, Object> files = new HashMap<>();
            Map<String, String> fileContent = new HashMap<>();
            fileContent.put("content", content);
            files.put(filename, fileContent);
            requestBody.put("files", files);
            
            String jsonBody = App.gson().toJson(requestBody);
            
            RequestBody body = RequestBody.create(
                MediaType.parse("application/json; charset=utf-8"),
                jsonBody
            );
            
            Request request = new Request.Builder()
                .url(apiUrl)
                .method("PATCH", body)
                .header("Authorization", "Bearer " + gistToken)
                .header("Accept", "application/vnd.github.v3+json")
                .build();
            
            Response response = OkHttp.client().newCall(request).execute();
            boolean success = response.isSuccessful();
            response.close();
            
            return success;
        } catch (Exception e) {
            Logger.e("SyncCode: 更新Gist失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 同步观看记录
     */
    public boolean syncHistory() {
        if (!isConfigured()) {
            return false;
        }
        
        App.execute(() -> {
            uploadHistory();
            downloadHistory();
        });
        
        return true;
    }
    
    /**
     * 重新加载配置
     */
    public void reloadConfig() {
        loadConfig();
    }
}

