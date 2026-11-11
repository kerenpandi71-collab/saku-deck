package com.saku.deck;

import android.os.Bundle;

// Runs inside the Shizuku helper process (shell permission).
interface IDeckService {
    // Required by Shizuku: called when the helper is unbound/destroyed.
    void destroy() = 16777114;

    // args: "surface" (Surface), "width", "height", "dpi". Returns "displayId" or "error".
    Bundle createDisplay(in Bundle args) = 1;

    // args: optional "surface". Missing surface pauses drawing.
    Bundle setSurface(in Bundle args) = 2;

    Bundle resizeDisplay(int width, int height, int dpi) = 3;

    void releaseDisplay() = 4;

    // component is "package/activityClass". Returns the activity manager output.
    String launchApp(String component, int displayId) = 5;

    // event: "event" (KeyEvent or MotionEvent).
    oneway void injectEvent(in Bundle event, int displayId) = 6;

    String runShell(String command) = 7;

    int getUid() = 8;

    String getInputStatus() = 9;

    // Task overview is scoped to the virtual display, never the physical display.
    String getOpenTasks(int displayId) = 10;
    String focusOpenTask(int displayId, int taskId) = 11;
    String closeOpenTask(int displayId, int taskId) = 12;
}
