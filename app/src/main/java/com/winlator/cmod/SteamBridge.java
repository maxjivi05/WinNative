package com.winlator.cmod;

import android.content.Context;
import android.util.Log;
import java.lang.reflect.Method;

/**
 * Java bridge to call Kotlin Steam classes via reflection,
 * avoiding KSP NullPointerException when scanning Java files 
 * that import Kotlin companion objects.
 */
public class SteamBridge {
    private static final String TAG = "SteamBridge";

    public static String getAppDirPath(int appId) {
        try {
            Class<?> companion = Class.forName("com.winlator.cmod.steam.service.SteamService$Companion");
            Object instance = Class.forName("com.winlator.cmod.steam.service.SteamService")
                    .getField("Companion").get(null);
            Method method = companion.getMethod("getAppDirPath", int.class);
            return (String) method.invoke(instance, appId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to call SteamService.getAppDirPath", e);
            return "";
        }
    }

    public static boolean extractSteam(Context context) {
        try {
            Class<?> clazz = Class.forName("com.winlator.cmod.steam.SteamClientManager");
            Object instance = clazz.getField("INSTANCE").get(null);
            Method method = clazz.getMethod("extractSteam", Context.class);
            return (Boolean) method.invoke(instance, context);
        } catch (Exception e) {
            Log.e(TAG, "Failed to call SteamClientManager.extractSteam", e);
            return false;
        }
    }

    public static boolean isSteamDownloaded(Context context) {
        try {
            Class<?> clazz = Class.forName("com.winlator.cmod.steam.SteamClientManager");
            Object instance = clazz.getField("INSTANCE").get(null);
            Method method = clazz.getMethod("isSteamDownloaded", Context.class);
            return (Boolean) method.invoke(instance, context);
        } catch (Exception e) {
            Log.e(TAG, "Failed to call SteamClientManager.isSteamDownloaded", e);
            return false;
        }
    }

    public static boolean isSteamInstalled(Context context) {
        try {
            Class<?> clazz = Class.forName("com.winlator.cmod.steam.SteamClientManager");
            Object instance = clazz.getField("INSTANCE").get(null);
            Method method = clazz.getMethod("isSteamInstalled", Context.class);
            return (Boolean) method.invoke(instance, context);
        } catch (Exception e) {
            Log.e(TAG, "Failed to call SteamClientManager.isSteamInstalled", e);
            return false;
        }
    }
}
