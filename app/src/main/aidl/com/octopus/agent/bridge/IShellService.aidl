package com.octopus.agent.bridge;
import android.os.ParcelFileDescriptor;

// Narrow, local-only probe API. No arbitrary shell command or default-screen capture.
interface IShellService {
    String diagnostics() = 0;
    String createDisplay(int width, int height, int density) = 1;
    String launch(String handle, String packageName) = 2;
    ParcelFileDescriptor capture(String handle) = 3;
    void closeDisplay(String handle) = 4;
    void destroy() = 16777114;
}
