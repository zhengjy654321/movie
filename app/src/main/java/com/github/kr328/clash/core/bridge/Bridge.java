package com.github.kr328.clash.core.bridge;
import kotlinx.coroutines.CompletableDeferred;
import kotlin.Unit;

public final class Bridge {
    static { System.loadLibrary("bridge"); }
    public native void nativeInit(String home,String version,int sdk);
    public native void nativeLoad(CompletableDeferred<Unit> result,String path);
    public native void nativeUpdateProvider(CompletableDeferred<Unit> result,String type,String name);
    public native void nativeStartTun(int fd,String stack,String gateway,String portal,String dns,TunInterface handler);
    public native void nativeStopTun();
    public native void nativeReset();
    public native String nativeQueryGroup(String name,String sort);
    public native boolean nativePatchSelector(String group,String node);
    public native void nativeHealthCheckAll();
}
