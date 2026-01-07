package com.ailife.rtosify;
import android.os.ParcelFileDescriptor;

interface IUserService {
    void destroy() = 16777114; // Destroy method defined by Shizuku
    
    void exit() = 1; // Exit the service
    
    void reboot() = 2; // Reboot the device
    
    void shutdown() = 3; // Shutdown the device
    
    String listFiles(String path) = 4;
    boolean deleteFile(String path) = 5;
    boolean renameFile(String oldPath, String newPath) = 6;
    boolean moveFile(String src, String dst) = 7;
    boolean copyFile(String src, String dst) = 8;
    boolean makeDirectory(String path) = 9;
    boolean exists(String path) = 10;
    boolean isDirectory(String path) = 11;
    long getFileSize(String path) = 12;
    long getLastModified(String path) = 13;
    
    boolean installApp(String apkPath) = 14;
    boolean uninstallApp(String packageName) = 15;
    boolean installAppFromPfd(in ParcelFileDescriptor pfd) = 16;
    void setWifiEnabled(boolean enabled) = 17;
    void connectToWifi(String ssid, String password) = 18;
    String getWifiScanResults() = 19;
    void startWifiScan() = 20;
    void setMobileDataEnabled(boolean enabled) = 21;
    void enableBluetoothPan(boolean enabled) = 22;
    String getPrimaryClipText() = 23;
    void setPrimaryClipText(String text) = 24;
}
