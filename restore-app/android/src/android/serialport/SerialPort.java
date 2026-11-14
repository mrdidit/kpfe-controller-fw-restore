package android.serialport;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Exact JNI field/method boundary for the hash-pinned vendor ARM64 library. */
public final class SerialPort {
    private FileDescriptor mFd;
    private final FileInputStream input;
    private final FileOutputStream output;
    private boolean closed;

    private static native FileDescriptor open(String path, int baud, int stop, int data,
                                             int parity, int flow, int flags);
    public native void close();

    @SuppressWarnings("restricted")
    public static void loadVerifiedLibrary(String absolutePath) { System.load(absolutePath); }

    public SerialPort() throws IOException {
        this("/dev/ttyHS1");
    }

    public SerialPort(String path) throws IOException {
        if (!"/dev/ttyHS1".equals(path) && !"/dev/ttyHS5".equals(path))
            throw new IOException("Unsupported controller UART");
        mFd = open(path, 115200, 1, 8, 0, 0, 0);
        if (mFd == null) throw new IOException("Stock native UART open returned null");
        input = new FileInputStream(mFd);
        output = new FileOutputStream(mFd);
    }

    public FileDescriptor descriptor() { return mFd; }
    public FileInputStream input() { return input; }
    public FileOutputStream output() { return output; }

    /** Stock order: input close, output close, reader interrupt, then JNI close. */
    public synchronized void closeOwned(Runnable interruptReader) throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try { input.close(); } catch (IOException error) { failure = error; }
        try { output.close(); } catch (IOException error) { if (failure == null) failure = error; }
        if (interruptReader != null) interruptReader.run();
        // No intervening descriptor opens: JNI close does not itself invalidate mFd.
        close();
        if (failure != null) throw failure;
    }
}
