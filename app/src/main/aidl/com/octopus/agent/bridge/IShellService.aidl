package com.octopus.agent.bridge;
import android.os.ParcelFileDescriptor;

// Narrow, local-only probe API. No arbitrary shell command or default-screen capture.
interface IShellService {
    String diagnostics() = 0;
    String createDisplay(int width, int height, int density) = 1;
    String launch(String handle, String packageName) = 2;
    ParcelFileDescriptor capture(String handle) = 3;
    void closeDisplay(String handle) = 4;
    void bindControl(String handle, String sessionId, long epoch, boolean agentAllowed) = 5;
    void calendarView(String handle, String sessionId, long epoch, long millis) = 6;
    void calendarInsert(String handle, String sessionId, long epoch, String draftJson) = 7;
    void bindHuman(String handle, String targetJson) = 8;
    boolean injectTouch(String handle, String targetJson, String packetJson) = 9;
    void setCapturePolicy(String handle, long intervalMillis, boolean enabled) = 10;
    void destroy() = 16777114;
}
