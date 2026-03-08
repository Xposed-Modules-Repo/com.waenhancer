package com.wmods.wppenhacer.xposed.features.media;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.wmods.wppenhacer.xposed.bridge.WaeIIFace;
import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.FeatureLoader;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.Utils;

import org.luckypray.dexkit.query.enums.StringMatchType;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class CallRecording extends Feature {

    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isCallConnected = new AtomicBoolean(false);

    private final AtomicReference<File> outputFileRef = new AtomicReference<>();
    private final AtomicReference<File> tempUplinkFile = new AtomicReference<>();
    private final AtomicReference<File> tempDownlinkFile = new AtomicReference<>();

    // Persistent output streams — kept open for the entire recording session
    private volatile FileOutputStream uplinkOutputStream = null;
    private volatile FileOutputStream downlinkOutputStream = null;
    private final Object uplinkStreamLock = new Object();
    private final Object downlinkStreamLock = new Object();

    private final AtomicReference<String> currentPhoneNumber = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> delayedStartFuture = new AtomicReference<>();
    private final ScheduledExecutorService delayedStartScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "WaEnhancer-CallDelayedStart");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean permissionGranted = new AtomicBoolean(false);

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final short CHANNELS = 1;
    private static final short BITS_PER_SAMPLE = 16;

    // Diagnostic counters — log periodically to confirm hooks are actively firing
    private final AtomicLong uplinkWriteCount = new AtomicLong(0);
    private final AtomicLong downlinkWriteCount = new AtomicLong(0);
    private static final long DIAG_LOG_INTERVAL = 200;

    public CallRecording(@NonNull ClassLoader loader, @NonNull XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("call_recording_enable", false)) {
            XposedBridge.log("WaEnhancer: Call Recording is disabled");
            return;
        }

        XposedBridge.log("WaEnhancer: Call Recording feature initializing...");
        hookCallStateChanges();
    }

    private void hookCallStateChanges() {
        int hooksInstalled = 0;

        try {
            var clsCallEventCallback = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith,
                    "VoiceServiceEventCallback");
            if (clsCallEventCallback != null) {
                XposedBridge.log("WaEnhancer: Found VoiceServiceEventCallback: " + clsCallEventCallback.getName());

                try {
                    XposedBridge.hookAllMethods(clsCallEventCallback, "fieldstatsReady", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            XposedBridge.log("WaEnhancer: fieldstatsReady - stopping recording if active");
                            stopRecording();
                        }
                    });
                    hooksInstalled++;
                } catch (Throwable e) {
                    XposedBridge.log("WaEnhancer: Could not hook fieldstatsReady: " + e.getMessage());
                }

                XposedBridge.hookAllMethods(clsCallEventCallback, "soundPortCreated", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        XposedBridge.log("WaEnhancer: soundPortCreated - will record after 3s");
                        extractPhoneNumberFromCallback(param.thisObject);
                        isCallConnected.set(true);
                        scheduleDelayedStart();
                    }
                });
                hooksInstalled++;
            }
        } catch (Throwable e) {
            XposedBridge.log("WaEnhancer: Could not hook VoiceServiceEventCallback: " + e.getMessage());
        }

        // Hook VoipActivity onDestroy for call end
        try {
            var voipActivityClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.Contains,
                    "VoipActivity");
            if (voipActivityClass != null && Activity.class.isAssignableFrom(voipActivityClass)) {
                XposedBridge.log("WaEnhancer: Found VoipActivity: " + voipActivityClass.getName());

                XposedBridge.hookAllMethods(voipActivityClass, "onDestroy", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        handleCallEnded("VoipActivity.onDestroy");
                    }
                });
                hooksInstalled++;
            }
        } catch (Throwable e) {
            XposedBridge.log("WaEnhancer: Could not hook VoipActivity: " + e.getMessage());
        }

        // --- JAVA PCM HOOKS FOR WEBRTC ---
        // Hook AudioRecord.read() — captures UPLINK (microphone / our voice)
        XposedBridge.hookAllMethods(android.media.AudioRecord.class, "read", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isRecording.get())
                    return;
                FileOutputStream fos = uplinkOutputStream;
                if (fos == null)
                    return;

                int bytesRead = (Integer) param.getResult();
                if (bytesRead <= 0)
                    return;

                Object firstArg = param.args[0];
                byte[] byteData = null;
                int dataLen = bytesRead;

                if (firstArg instanceof byte[]) {
                    byteData = (byte[]) firstArg;
                    dataLen = bytesRead;
                } else if (firstArg instanceof short[]) {
                    short[] shortData = (short[]) firstArg;
                    int offsetInShorts = (param.args.length > 1) ? (Integer) param.args[1] : 0;
                    dataLen = bytesRead * 2;
                    byteData = new byte[dataLen];
                    ByteBuffer.wrap(byteData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            .put(shortData, offsetInShorts, bytesRead);
                } else if (firstArg instanceof ByteBuffer) {
                    ByteBuffer buffer = (ByteBuffer) firstArg;
                    byteData = new byte[bytesRead];
                    int oldPos = buffer.position();
                    buffer.position(oldPos - bytesRead);
                    buffer.get(byteData, 0, bytesRead);
                    buffer.position(oldPos);
                    dataLen = bytesRead;
                }

                if (byteData != null && dataLen > 0) {
                    long count = uplinkWriteCount.incrementAndGet();
                    if (count == 1 || count % DIAG_LOG_INTERVAL == 0) {
                        XposedBridge.log("WaEnhancer: AudioRecord.read hook fired #" + count + " bytes=" + dataLen);
                    }
                    synchronized (uplinkStreamLock) {
                        FileOutputStream currentFos = uplinkOutputStream;
                        if (currentFos != null) {
                            try {
                                currentFos.write(byteData, 0, dataLen);
                            } catch (Exception e) {
                                // Stream may have been closed concurrently
                            }
                        }
                    }
                }
            }
        });

        // Hook AudioTrack.write() — captures DOWNLINK (other party's voice)
        XposedBridge.hookAllMethods(android.media.AudioTrack.class, "write", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!isRecording.get())
                    return;
                FileOutputStream fos = downlinkOutputStream;
                if (fos == null)
                    return;

                Object firstArg = param.args[0];
                int sizeInBytes = 0;
                byte[] byteData = null;

                if (firstArg instanceof byte[]) {
                    int offset = (Integer) param.args[1];
                    sizeInBytes = (Integer) param.args[2];
                    byteData = (byte[]) firstArg;
                    if (offset != 0) {
                        byte[] trimmed = new byte[sizeInBytes];
                        System.arraycopy(byteData, offset, trimmed, 0, sizeInBytes);
                        byteData = trimmed;
                    }
                } else if (firstArg instanceof short[]) {
                    int offsetInShorts = (Integer) param.args[1];
                    int sizeInShorts = (Integer) param.args[2];
                    sizeInBytes = sizeInShorts * 2;
                    short[] shortData = (short[]) firstArg;
                    byteData = new byte[sizeInBytes];
                    ByteBuffer.wrap(byteData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            .put(shortData, offsetInShorts, sizeInShorts);
                } else if (firstArg instanceof float[]) {
                    int offsetInFloats = (Integer) param.args[1];
                    int sizeInFloats = (Integer) param.args[2];
                    float[] floatData = (float[]) firstArg;
                    sizeInBytes = sizeInFloats * 2;
                    byteData = new byte[sizeInBytes];
                    ByteBuffer bb = ByteBuffer.wrap(byteData).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < sizeInFloats; i++) {
                        float sample = floatData[offsetInFloats + i];
                        sample = Math.max(-1.0f, Math.min(1.0f, sample));
                        short pcmSample = (short) (sample * 32767);
                        bb.putShort(pcmSample);
                    }
                } else if (firstArg instanceof ByteBuffer) {
                    sizeInBytes = (Integer) param.args[1];
                    ByteBuffer buffer = (ByteBuffer) firstArg;
                    byteData = new byte[sizeInBytes];
                    int oldPos = buffer.position();
                    buffer.get(byteData, 0, sizeInBytes);
                    buffer.position(oldPos);
                }

                if (byteData != null && sizeInBytes > 0) {
                    long count = downlinkWriteCount.incrementAndGet();
                    if (count == 1 || count % DIAG_LOG_INTERVAL == 0) {
                        XposedBridge.log("WaEnhancer: AudioTrack.write hook fired #" + count + " bytes=" + sizeInBytes);
                    }
                    synchronized (downlinkStreamLock) {
                        FileOutputStream currentFos = downlinkOutputStream;
                        if (currentFos != null) {
                            try {
                                currentFos.write(byteData, 0, sizeInBytes);
                            } catch (Exception e) {
                                // Stream may have been closed concurrently
                            }
                        }
                    }
                }
            }
        });
        hooksInstalled += 2;

        XposedBridge.log("WaEnhancer: Call Recording initialized with " + hooksInstalled + " hooks");
    }

    private void handleCallEnded(@NonNull String reason) {
        XposedBridge.log("WaEnhancer: Call ended by " + reason);
        isCallConnected.set(false);
        cancelDelayedStart();
        stopRecording();
    }

    private void scheduleDelayedStart() {
        cancelDelayedStart();
        ScheduledFuture<?> future = delayedStartScheduler.schedule(() -> {
            if (!isCallConnected.get()) {
                XposedBridge.log("WaEnhancer: Delayed start cancelled, call not connected");
                return;
            }
            if (isRecording.get()) {
                XposedBridge.log("WaEnhancer: Delayed start ignored, already recording");
                return;
            }
            startRecording();
        }, 3, TimeUnit.SECONDS);
        delayedStartFuture.set(future);
    }

    private void cancelDelayedStart() {
        ScheduledFuture<?> future = delayedStartFuture.getAndSet(null);
        if (future != null) {
            future.cancel(true);
        }
    }

    private void extractPhoneNumberFromCallback(Object callback) {
        try {
            Object callInfo = XposedHelpers.callMethod(callback, "getCallInfo");
            if (callInfo == null)
                return;

            Object peerJid = XposedHelpers.getObjectField(callInfo, "peerJid");
            var userJid = new FMessageWpp.UserJid(peerJid);
            if (!userJid.isNull()) {
                String phone = "+" + userJid.getPhoneNumber();
                currentPhoneNumber.set(phone);
                XposedBridge.log("WaEnhancer: Found phone from UserJid: " + phone);
                return;
            }
            Object participantsObj = XposedHelpers.getObjectField(callInfo, "participants");
            if (participantsObj instanceof Map participants) {
                for (Object key : participants.keySet()) {
                    var userJid2 = new FMessageWpp.UserJid(key);
                    if (!userJid2.isNull()) {
                        String phone = "+" + userJid2.getPhoneNumber();
                        currentPhoneNumber.set(phone);
                        XposedBridge.log("WaEnhancer: Found phone from single participant: " + phone);
                        return;
                    }
                }
            }
        } catch (Throwable e) {
            XposedBridge.log("WaEnhancer: extractPhoneNumber error: " + e.getMessage());
        }
    }

    private void grantVoiceCallPermission() {
        if (permissionGranted.get())
            return;

        try {
            String packageName = FeatureLoader.mApp.getPackageName();
            XposedBridge.log("WaEnhancer: Granting CAPTURE_AUDIO_OUTPUT via root");

            String[] commands = {
                    "pm grant " + packageName + " android.permission.CAPTURE_AUDIO_OUTPUT",
                    "appops set " + packageName + " RECORD_AUDIO allow",
            };

            for (String cmd : commands) {
                try {
                    Process process = Runtime.getRuntime().exec(new String[] { "su", "-c", cmd });
                    int exitCode = process.waitFor();
                    XposedBridge.log("WaEnhancer: " + cmd + " exit: " + exitCode);
                } catch (Exception e) {
                    XposedBridge.log("WaEnhancer: Root failed: " + e.getMessage());
                }
            }

            permissionGranted.set(true);
        } catch (Throwable e) {
            XposedBridge.log("WaEnhancer: grantVoiceCallPermission error: " + e.getMessage());
        }
    }

    private synchronized void startRecording() {
        if (isRecording.get()) {
            XposedBridge.log("WaEnhancer: Already recording");
            return;
        }

        String phoneNumber = currentPhoneNumber.get();
        if (!shouldRecord(phoneNumber)) {
            XposedBridge.log("WaEnhancer: Skipping recording due to privacy settings for: " + phoneNumber);
            return;
        }

        if (!isCallConnected.get()) {
            XposedBridge.log("WaEnhancer: Skipping recording, call is not connected");
            return;
        }

        try {
            if (ContextCompat.checkSelfPermission(FeatureLoader.mApp,
                    Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                XposedBridge.log("WaEnhancer: No RECORD_AUDIO permission");
                return;
            }

            // Use upstream's OutputTarget pattern for robust bridge fallback
            WaeIIFace bridge = null;
            try {
                bridge = WppCore.getClientBridge();
            } catch (Throwable t) {
                XposedBridge
                        .log("WaEnhancer: Could not get client bridge, using app context storage: " + t.getMessage());
            }

            File cacheDir = FeatureLoader.mApp.getCacheDir();
            String packageName = FeatureLoader.mApp.getPackageName();
            String appName = packageName.contains("w4b") ? "WA Business" : "WhatsApp";
            String settingsPath = prefs.getString("call_recording_path",
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
            File parentDir = new File(settingsPath, "WA Call Recordings");
            File appDir = new File(parentDir, appName);

            if (bridge != null) {
                if (!appDir.exists() && !appDir.mkdirs()) {
                    boolean dirCreated = bridge.createDir(appDir.getAbsolutePath());
                    if (!dirCreated && !appDir.exists()) {
                        throw new IOException("Could not create output directory: " + appDir.getAbsolutePath());
                    }
                }
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = (phoneNumber != null && !phoneNumber.isEmpty())
                    ? "Call_" + phoneNumber.replaceAll("[^+0-9]", "") + "_" + timestamp + ".wav"
                    : "Call_" + timestamp + ".wav";

            // Create temp PCM files in cache
            File up = new File(cacheDir, "uplink_" + System.currentTimeMillis() + ".pcm");
            File down = new File(cacheDir, "downlink_" + System.currentTimeMillis() + ".pcm");
            tempUplinkFile.set(up);
            tempDownlinkFile.set(down);

            // Resolve final output path using upstream's bridge fallback pattern
            OutputTarget outputTarget = openOutputTarget(bridge, appDir, fileName);
            outputFileRef.set(outputTarget.file);

            // Close the output target stream for now — we'll write the final WAV after
            // mixing
            try {
                outputTarget.outputStream.close();
            } catch (Exception ignored) {
            }
            if (outputTarget.parcelFileDescriptor != null) {
                try {
                    outputTarget.parcelFileDescriptor.close();
                } catch (Exception ignored) {
                }
            }

            boolean useRoot = prefs.getBoolean("call_recording_use_root", false);
            if (useRoot) {
                grantVoiceCallPermission();
            }

            // Open persistent output streams BEFORE setting isRecording = true
            synchronized (uplinkStreamLock) {
                uplinkOutputStream = new FileOutputStream(up);
            }
            synchronized (downlinkStreamLock) {
                downlinkOutputStream = new FileOutputStream(down);
            }

            // Reset diagnostic counters
            uplinkWriteCount.set(0);
            downlinkWriteCount.set(0);

            // NOW set recording flag — hooks will start writing data
            if (!isRecording.compareAndSet(false, true)) {
                closeStreams();
                return;
            }

            XposedBridge.log("WaEnhancer: Recording started — Output to: " + outputTarget.file.getAbsolutePath());
            XposedBridge.log("WaEnhancer: Uplink PCM: " + up.getAbsolutePath());
            XposedBridge.log("WaEnhancer: Downlink PCM: " + down.getAbsolutePath());

            if (prefs.getBoolean("call_recording_toast", false)) {
                Utils.showToast("Recording started", Toast.LENGTH_SHORT);
            }

        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: startRecording error: " + e.getMessage());
            isRecording.set(false);
            closeStreams();
        }
    }

    private synchronized void stopRecording() {
        cancelDelayedStart();
        XposedBridge.log("WaEnhancer: stopRecording called, isRecording=" + isRecording.get());
        if (!isRecording.getAndSet(false)) {
            XposedBridge.log("WaEnhancer: stopRecording early-exit (was not recording)");
            return;
        }

        try {
            // Close streams first — ensures all buffered data is flushed
            closeStreams();

            File upPcm = tempUplinkFile.getAndSet(null);
            File downPcm = tempDownlinkFile.getAndSet(null);
            File finalOut = outputFileRef.getAndSet(null);

            XposedBridge.log("WaEnhancer: Recording stopped. Uplink writes: " + uplinkWriteCount.get()
                    + ", Downlink writes: " + downlinkWriteCount.get());

            if (upPcm != null) {
                XposedBridge.log("WaEnhancer: Uplink PCM size: " + upPcm.length() + " bytes");
            }
            if (downPcm != null) {
                XposedBridge.log("WaEnhancer: Downlink PCM size: " + downPcm.length() + " bytes");
            }

            if (finalOut != null && upPcm != null && downPcm != null
                    && (upPcm.length() > 0 || downPcm.length() > 0)) {

                XposedBridge.log("WaEnhancer: Mixing PCM streams to WAV...");

                File finalUpPcm = upPcm;
                File finalDownPcm = downPcm;
                new Thread(() -> {
                    try {
                        mixPcmToWav(finalUpPcm, finalDownPcm, finalOut);
                        if (prefs.getBoolean("call_recording_toast", false)) {
                            Utils.showToast("Recording saved!", Toast.LENGTH_SHORT);
                        }
                    } catch (Exception e) {
                        XposedBridge.log("WaEnhancer: Mix error: " + e.getMessage());
                        if (prefs.getBoolean("call_recording_toast", false)) {
                            Utils.showToast("Recording failed", Toast.LENGTH_SHORT);
                        }
                    } finally {
                        finalUpPcm.delete();
                        finalDownPcm.delete();
                    }
                }, "WaEnhancer-AudioMixer").start();

            } else {
                XposedBridge.log("WaEnhancer: Missing PCM files or both empty, skipping mix.");
                if (upPcm != null)
                    upPcm.delete();
                if (downPcm != null)
                    downPcm.delete();
            }

            currentPhoneNumber.set(null);
        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: stopRecording exception: " + e.getMessage());
        }
    }

    private void closeStreams() {
        synchronized (uplinkStreamLock) {
            if (uplinkOutputStream != null) {
                try {
                    uplinkOutputStream.flush();
                    uplinkOutputStream.close();
                } catch (Exception e) {
                    /* ignore */ }
                uplinkOutputStream = null;
            }
        }
        synchronized (downlinkStreamLock) {
            if (downlinkOutputStream != null) {
                try {
                    downlinkOutputStream.flush();
                    downlinkOutputStream.close();
                } catch (Exception e) {
                    /* ignore */ }
                downlinkOutputStream = null;
            }
        }
    }

    /**
     * Mix two raw PCM files (16-bit LE mono) into a single WAV file.
     * Samples are added together with clamping to prevent clipping.
     */
    private void mixPcmToWav(File uplinkPcm, File downlinkPcm, File outputWav) throws IOException {
        long upLen = uplinkPcm.exists() ? uplinkPcm.length() : 0;
        long downLen = downlinkPcm.exists() ? downlinkPcm.length() : 0;
        long maxLen = Math.max(upLen, downLen);

        if (maxLen == 0) {
            XposedBridge.log("WaEnhancer: Both PCM files empty, nothing to mix.");
            return;
        }

        File cacheOut = new File(FeatureLoader.mApp.getCacheDir(),
                "mixed_" + System.currentTimeMillis() + ".wav");

        try (FileOutputStream wavOut = new FileOutputStream(cacheOut)) {
            writeWavHeader(wavOut, maxLen);

            FileInputStream upIn = upLen > 0 ? new FileInputStream(uplinkPcm) : null;
            FileInputStream downIn = downLen > 0 ? new FileInputStream(downlinkPcm) : null;

            try {
                byte[] upBuf = new byte[8192];
                byte[] downBuf = new byte[8192];
                byte[] mixBuf = new byte[8192];
                long remaining = maxLen;

                while (remaining > 0) {
                    int toRead = (int) Math.min(remaining, upBuf.length);

                    int upRead = 0;
                    if (upIn != null) {
                        upRead = upIn.read(upBuf, 0, toRead);
                        if (upRead < 0)
                            upRead = 0;
                    }

                    int downRead = 0;
                    if (downIn != null) {
                        downRead = downIn.read(downBuf, 0, toRead);
                        if (downRead < 0)
                            downRead = 0;
                    }

                    int mixLen = Math.max(upRead, downRead);
                    if (mixLen == 0)
                        break;

                    if (upRead < mixLen) {
                        for (int i = upRead; i < mixLen; i++)
                            upBuf[i] = 0;
                    }
                    if (downRead < mixLen) {
                        for (int i = downRead; i < mixLen; i++)
                            downBuf[i] = 0;
                    }

                    // Mix 16-bit LE samples with clamping
                    for (int i = 0; i + 1 < mixLen; i += 2) {
                        short upSample = (short) ((upBuf[i] & 0xFF) | (upBuf[i + 1] << 8));
                        short downSample = (short) ((downBuf[i] & 0xFF) | (downBuf[i + 1] << 8));

                        int mixed = upSample + downSample;
                        if (mixed > 32767)
                            mixed = 32767;
                        if (mixed < -32768)
                            mixed = -32768;

                        mixBuf[i] = (byte) (mixed & 0xFF);
                        mixBuf[i + 1] = (byte) ((mixed >> 8) & 0xFF);
                    }

                    wavOut.write(mixBuf, 0, mixLen);
                    remaining -= mixLen;
                }
            } finally {
                if (upIn != null)
                    try {
                        upIn.close();
                    } catch (Exception e) {
                        /* ignore */ }
                if (downIn != null)
                    try {
                        downIn.close();
                    } catch (Exception e) {
                        /* ignore */ }
            }
        }

        // Update WAV header with actual data size
        long actualDataSize = cacheOut.length() - 44;
        try (RandomAccessFile raf = new RandomAccessFile(cacheOut, "rw")) {
            raf.seek(4);
            writeIntLE(raf, (int) (actualDataSize + 36));
            raf.seek(40);
            writeIntLE(raf, (int) actualDataSize);
        }

        XposedBridge.log("WaEnhancer: WAV mixing complete. Size: " + cacheOut.length() + " bytes");

        moveFileToOutput(cacheOut, outputWav);
    }

    private void moveFileToOutput(File source, File dest) {
        // Try direct rename
        if (source.renameTo(dest)) {
            XposedBridge.log("WaEnhancer: Moved WAV to: " + dest.getAbsolutePath());
            Utils.scanFile(dest);
            return;
        }

        // Try direct copy
        try (FileInputStream fis = new FileInputStream(source);
                FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = fis.read(buf)) > 0) {
                fos.write(buf, 0, read);
            }
            fos.flush();
            XposedBridge.log("WaEnhancer: Copied WAV to: " + dest.getAbsolutePath());
            Utils.scanFile(dest);
            source.delete();
            return;
        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: Direct copy failed: " + e.getMessage());
        }

        // Fall back to root copy
        try {
            String cpCmd = String.format("cp \"%s\" \"%s\" && chmod 666 \"%s\"",
                    source.getAbsolutePath(),
                    dest.getAbsolutePath(),
                    dest.getAbsolutePath());
            XposedBridge.log("WaEnhancer: Executing root copy: " + cpCmd);

            Process cpProcess = Runtime.getRuntime().exec(new String[] { "su", "-c", cpCmd });
            int cpRc = cpProcess.waitFor();

            if (cpRc == 0) {
                XposedBridge.log("WaEnhancer: Root copy success => " + dest.getAbsolutePath());
                Utils.scanFile(dest);
            } else {
                XposedBridge.log("WaEnhancer: Root copy failed with code " + cpRc);
            }
        } catch (Exception ex) {
            XposedBridge.log("WaEnhancer: Root copy exception: " + ex.getMessage());
        }
        source.delete();
    }

    // ---- Upstream OutputTarget pattern for robust file output ----

    @NonNull
    private OutputTarget openOutputTarget(WaeIIFace bridge, @NonNull File preferredDir, @NonNull String fileName)
            throws IOException {
        File preferredFile = new File(preferredDir, fileName);
        if (bridge != null) {
            try {
                ParcelFileDescriptor parcelFileDescriptor = bridge.openFile(preferredFile.getAbsolutePath(), true);
                if (parcelFileDescriptor != null) {
                    FileOutputStream outputStream = new FileOutputStream(parcelFileDescriptor.getFileDescriptor());
                    return new OutputTarget(preferredFile, parcelFileDescriptor, outputStream);
                }
                XposedBridge.log("WaEnhancer: Bridge openFile returned null, fallback to Android/data path");
            } catch (Throwable t) {
                XposedBridge
                        .log("WaEnhancer: Bridge openFile failed, fallback to Android/data path: " + t.getMessage());
            }
        }

        File appExternalDir = FeatureLoader.mApp.getExternalFilesDir(null);
        if (appExternalDir == null) {
            throw new IOException("Could not resolve app external files directory");
        }
        File fallbackDir = new File(appExternalDir, "Recordings");
        if (!fallbackDir.exists() && !fallbackDir.mkdirs()) {
            throw new IOException("Could not create fallback recording directory: " + fallbackDir.getAbsolutePath());
        }

        File fallbackFile = new File(fallbackDir, fileName);
        FileOutputStream fallbackStream = new FileOutputStream(fallbackFile);
        XposedBridge.log("WaEnhancer: Recording fallback path in Android/data: " + fallbackFile.getAbsolutePath());
        return new OutputTarget(fallbackFile, null, fallbackStream);
    }

    private static final class OutputTarget {
        @NonNull
        private final File file;
        private final ParcelFileDescriptor parcelFileDescriptor;
        @NonNull
        private final FileOutputStream outputStream;

        private OutputTarget(@NonNull File file, ParcelFileDescriptor parcelFileDescriptor,
                @NonNull FileOutputStream outputStream) {
            this.file = file;
            this.parcelFileDescriptor = parcelFileDescriptor;
            this.outputStream = outputStream;
        }
    }

    private void writeWavHeader(FileOutputStream out, long dataSize) throws IOException {
        long clampedDataSize = Math.min(dataSize, 0xFFFFFFFFL);
        long totalDataLen = clampedDataSize + 36;
        long byteRate = (long) SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;

        byte[] header = new byte[44];
        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16;
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1;
        header[21] = 0;
        header[22] = (byte) CHANNELS;
        header[23] = 0;
        header[24] = (byte) (SAMPLE_RATE & 0xff);
        header[25] = (byte) ((SAMPLE_RATE >> 8) & 0xff);
        header[26] = (byte) ((SAMPLE_RATE >> 16) & 0xff);
        header[27] = (byte) ((SAMPLE_RATE >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (CHANNELS * BITS_PER_SAMPLE / 8);
        header[33] = 0;
        header[34] = 16;
        header[35] = 0;
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (clampedDataSize & 0xff);
        header[41] = (byte) ((clampedDataSize >> 8) & 0xff);
        header[42] = (byte) ((clampedDataSize >> 16) & 0xff);
        header[43] = (byte) ((clampedDataSize >> 24) & 0xff);

        out.write(header);
    }

    private void writeIntLE(RandomAccessFile raf, int value) throws IOException {
        raf.write(value & 0xFF);
        raf.write((value >> 8) & 0xFF);
        raf.write((value >> 16) & 0xFF);
        raf.write((value >> 24) & 0xFF);
    }

    private boolean shouldRecord(String phoneNumber) {
        try {
            int mode = Integer.parseInt(prefs.getString("call_recording_mode", "0"));
            if (mode == 0)
                return true;

            String blacklist = prefs.getString("call_recording_blacklist", "[]");
            String whitelist = prefs.getString("call_recording_whitelist", "[]");

            if (mode == 2) {
                if (phoneNumber == null)
                    return true;
                String cleanPhone = phoneNumber.replaceAll("[^0-9]", "");
                return !isNumberInList(cleanPhone, blacklist);
            } else if (mode == 3) {
                if (whitelist.equals("[]") || whitelist.isEmpty())
                    return false;
                if (phoneNumber == null)
                    return false;
                String cleanPhone = phoneNumber.replaceAll("[^0-9]", "");
                return isNumberInList(cleanPhone, whitelist);
            } else if (mode == 1) {
                return true;
            }
        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: shouldRecord check error: " + e.getMessage());
        }
        return true;
    }

    private boolean isNumberInList(String phone, String jsonList) {
        if (TextUtils.isEmpty(jsonList) || jsonList.equals("[]"))
            return false;
        try {
            String content = jsonList.substring(1, jsonList.length() - 1);
            if (content.isEmpty())
                return false;

            String[] numbers = content.split(", ");
            for (String num : numbers) {
                String cleanNum = num.trim().replaceAll("[^0-9]", "");
                if (cleanNum.equals(phone))
                    return true;
            }
        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: Error parsing list: " + e.getMessage());
        }
        return false;
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Call Recording";
    }
}
