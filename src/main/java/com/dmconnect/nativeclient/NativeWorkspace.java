package com.dmconnect.nativeclient;

import com.dmconnect.model.ConnectionProfile;

/** A long-lived non-JDBC client owned by one connected profile. */
public interface NativeWorkspace extends AutoCloseable {
    ConnectionProfile profile();

    /** Forces the lazy client to contact the server and authenticate. */
    void verify();

    @Override
    void close();
}
