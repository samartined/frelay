package com.cfr.flipperrelay;

interface Transport extends AutoCloseable {
    interface Listener {
        void bytes(byte[] data);
        void failed(String message);
        /** Human-readable progress while opening the transport. */
        default void stage(String message) {}
    }
    void open() throws Exception;
    void write(byte[] data) throws Exception;
    void close();
}
