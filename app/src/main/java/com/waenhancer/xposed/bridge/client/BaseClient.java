package com.waenhancer.xposed.bridge.client;

import com.waenhancer.xposed.bridge.WaeIIFace;

import java.util.concurrent.CompletableFuture;

public abstract class BaseClient {

    public abstract WaeIIFace getService();

    public abstract CompletableFuture<Boolean> connect();

    public abstract void tryReconnect();

}
