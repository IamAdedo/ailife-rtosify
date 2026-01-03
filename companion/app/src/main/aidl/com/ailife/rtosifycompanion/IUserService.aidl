package com.ailife.rtosifycompanion;

interface IUserService {
    void destroy() = 16777114; // Destroy method defined by Shizuku
    
    void exit() = 1; // Exit the service
    
    void reboot() = 2; // Reboot the device
    
    void shutdown() = 3; // Shutdown the device
}
