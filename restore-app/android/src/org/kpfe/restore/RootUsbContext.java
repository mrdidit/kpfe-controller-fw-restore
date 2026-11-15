package org.kpfe.restore;

import android.content.Context;
import android.os.Looper;
import android.system.Os;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Framework context for a standalone root client; never grants or opens USB. */
final class RootUsbContext {
    private RootUsbContext() { }

    @SuppressWarnings("deprecation")
    static Context create() throws IOException, ReflectiveOperationException {
        if (Os.getuid() != 0) throw new IOException("Root USB context requires UID 0.");
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class<?> type = Class.forName("android.app.ActivityThread");
        Method systemMain = type.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getContext = type.getDeclaredMethod("getSystemContext");
        getContext.setAccessible(true);
        Context context = (Context)getContext.invoke(thread);
        if (context == null) throw new IOException("Android system context is unavailable.");
        Method currentThread = type.getDeclaredMethod("currentActivityThread");
        currentThread.setAccessible(true);
        if (currentThread.invoke(null) != thread)
            throw new IOException("Root USB context belongs to an unexpected framework thread.");

        // systemMain supplies the context, but this app_process is a Binder client,
        // not system_server. Android 14 UsbDevice rejects remote serial-reader
        // Binder proxies when ActivityThread.isSystem() is true. Empty USB lists
        // hide this error. Correct only our process-local classification; leave
        // UID, context attribution and USB permissions unchanged. Do not attach
        // another application to ActivityManager through attach(false).
        Field systemThread = type.getDeclaredField("mSystemThread");
        systemThread.setAccessible(true);
        systemThread.setBoolean(thread, false);
        Method isSystem = type.getDeclaredMethod("isSystem");
        isSystem.setAccessible(true);
        if (!Boolean.FALSE.equals(isSystem.invoke(null)))
            throw new IOException("Root USB client classification could not be verified.");
        return context;
    }
}
