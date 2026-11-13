package io.github.fate_grand_automata.capture.chaldea;

import android.os.ParcelFileDescriptor;

// Results are small JSON texts (see ChaldeaSyncResult / ChaldeaStateResult); they never hold account data.
interface IChaldeaSyncService {
    // Transaction code reserved by Shizuku for destroy().
    void destroy() = 16777114;

    // {"state": NotRunning|Visible|Background|Unknown, "package": ...}
    String state() = 1;

    // am force-stop, then polls up to about 3 s; returns the same shape as state().
    String forceStop() = 2;

    // Checks Chaldea is not running, picks the base file and streams its bytes to backupFd (closed afterwards).
    String prepare(in ParcelFileDescriptor backupFd) = 3;

    // Edits and writes both files in place; see ChaldeaSyncEngine.
    String commit(String patchJson, String expectedSha256, boolean allowCreate, String expectedPackage, String expectedBundleSha256) = 4;

    // Puts the stored base bytes (streamed through backupFd, SHA-256 expectedSha256) back into both files.
    String restore(in ParcelFileDescriptor backupFd, String expectedSha256, String expectedPackage) = 5;

    // Reads Chaldea's game/version.json and game/servants.*.json; returns compact costume/transform JSON or {"unchanged": true} or {"error": "..."}.
    String gameData(String knownVersion) = 6;
}
