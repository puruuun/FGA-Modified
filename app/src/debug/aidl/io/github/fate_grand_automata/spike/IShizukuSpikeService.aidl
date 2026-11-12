package io.github.fate_grand_automata.spike;

import android.os.ParcelFileDescriptor;

interface IShizukuSpikeService {
    void destroy() = 16777114;
    int getUid() = 1;
    int getPid() = 2;
    String getChaldeaState() = 3;
    String readUserDataInfo() = 4;
    ParcelFileDescriptor openUserDataPipe(String fileName) = 5;
    byte[] readUserDataChunk(String fileName, int offset, int length) = 6;
    String rewriteIdentically() = 7;
    String forceStopChaldea() = 8;
}
