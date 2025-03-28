package com.espressif.espblufi.response;

import java.util.Locale;

public class BlufiVersionResponse {
    private final int[] mVersionValues = {0, 0};

    public void setVersionValues(int bigVer, int smallVer) {
        mVersionValues[0] = bigVer;
        mVersionValues[1] = smallVer;
    }

    public String getVersionString() {
        return String.format(Locale.ENGLISH, "V%d.%d", mVersionValues[0], mVersionValues[1]);
    }
}
