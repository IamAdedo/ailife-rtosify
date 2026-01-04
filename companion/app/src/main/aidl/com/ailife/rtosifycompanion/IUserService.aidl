package com.ailife.rtosifycompanion;

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
}
