package com.supernova.networkswitch;

interface IRootController {
    boolean compatibilityCheck(int subId);
    int getCurrentNetworkMode(int subId);
    void setNetworkMode(int subId, int networkMode);

    /** 1 = voice over LTE available through IMS, 0 = not available, -1 = unknown. */
    int getVolteState(int subId);

    /** Human-readable dump of the IMS-related telephony calls this device exposes. */
    String getImsDiagnostics(int subId);
}
