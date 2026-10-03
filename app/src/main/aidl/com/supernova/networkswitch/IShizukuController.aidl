package com.supernova.networkswitch;

import com.supernova.networkswitch.IImsEventListener;

interface IShizukuController {
    boolean compatibilityCheck(int subId);
    int getCurrentNetworkMode(int subId);
    void setNetworkMode(int subId, int networkMode);
    void destroy();

    /** 1 = voice over LTE available through IMS, 0 = not available, -1 = unknown. */
    int getVolteState(int subId);

    /** Human-readable dump of the IMS-related telephony calls this device exposes. */
    String getImsDiagnostics(int subId);

    /**
     * Registers IMS registration and capability callbacks for [subId] and forwards every
     * change to [listener]. Replaces a previous registration. Returns false when the
     * platform refuses the registration.
     */
    boolean startImsEvents(int subId, IImsEventListener listener);

    void stopImsEvents();
}
