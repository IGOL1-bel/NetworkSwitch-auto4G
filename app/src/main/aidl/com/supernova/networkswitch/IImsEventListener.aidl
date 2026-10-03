package com.supernova.networkswitch;

/**
 * Receives IMS change notifications from the privileged process (Shizuku or root).
 * One-way, so a slow app process can never block telephony callbacks over there.
 */
oneway interface IImsEventListener {
    /** IMS registration or MmTel capability changed on the watched subscription. */
    void onImsChanged();
}
