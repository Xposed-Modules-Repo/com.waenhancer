package com.wmods.wppenhacer.xposed.features.media;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class CallRecording extends Feature {

    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isCallConnected = new AtomicBoolean(false);
    private final AtomicReference<File> outputFileRef = new AtomicReference<>();
    private final AtomicReference<String> currentPhoneNumber = new AtomicReference<>();

    // Recording state objects
    private MediaRecorder mediaRecorder;
    private Process rootAudioProcess;

    private final AtomicReference<ScheduledFuture<?>> delayedStartFuture = new AtomicReference<>();
    private final ScheduledExecutorService delayedStartScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "WaEnhancer-CallDelayedStart");
        thread.setDaemon(true);
        return thread;
    });

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
        try {
            var clsCallEventCallback = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith,
                    "VoiceServiceEventCallback");
            if (clsCallEventCallback != null) {
                try {
                    XposedBridge.hookAllMethods(clsCallEventCallback, "fieldstatsReady", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            stopRecording();
                        }
                    });
                } catch (Throwable e) {
                    XposedBridge.log("WaEnhancer: Could not hook fieldstatsReady: " + e.getMessage());
                }

                XposedBridge.hookAllMethods(clsCallEventCallback, "soundPortCreated", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        extractPhoneNumberFromCallback(param.thisObject);
                        isCallConnected.set(true);
                        scheduleDelayedStart();
                    }
                });
            }
        } catch (Throwable e) {
            XposedBridge.log("WaEnhancer: Could not hook VoiceServiceEventCallback: " + e.getMessage());
        }

        try {
            var voipActivityClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.Contains,
                    "VoipActivity");
            if (voipActivityClass != null && Activity.class.isAssignableFrom(voipActivityClass)) {
                XposedBridge.hookAllMethods(voipActivityClass, "onDestroy", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        handleCallEnded("VoipActivity.onDestroy");
                    }
                });
            }
        } catch (Throwable e) {
            XposedBridge.log("WaEnhancer: Could not hook VoipActivity: " + e.getMessage());
        }
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
            if (!isCallConnected.get())
                return;
            if (isRecording.get())
                return;
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
                currentPhoneNumber.set("+" + userJid.getPhoneNumber());
                return;
            }
            Object participantsObj = XposedHelpers.getObjectField(callInfo, "participants");
            if (participantsObj instanceof Map participants) {
                for (Object key : participants.keySet()) {
                    var userJid2 = new FMessageWpp.UserJid(key);
                    if (!userJid2.isNull()) {
                        currentPhoneNumber.set("+" + userJid2.getPhoneNumber());
                        return;
                    }
                }
            }
        } catch (Throwable e) {
            XposedBridge.log("WaEnhancer: extractPhoneNumber error: " + e.getMessage());
        }
    }

    private synchronized void startRecording() {
        if (isRecording.get())
            return;

        String phoneNumber = currentPhoneNumber.get();
        if (!shouldRecord(phoneNumber))
            return;
        if (!isCallConnected.get())
            return;

        try {
            if (ContextCompat.checkSelfPermission(FeatureLoader.mApp,
                    Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                XposedBridge.log("WaEnhancer: No RECORD_AUDIO permission");
                return;
            }

            WaeIIFace bridge = null;
            try {
                bridge = WppCore.getClientBridge();
            } catch (Throwable t) {
                XposedBridge.log("WaEnhancer: Could not get client bridge: " + t.getMessage());
            }

            String packageName = FeatureLoader.mApp.getPackageName();
            String appName = packageName.contains("w4b") ? "WA Business" : "WhatsApp";
            String settingsPath = prefs.getString("call_recording_path",
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
            File parentDir = new File(settingsPath, "WA Call Recordings");
            File appDir = new File(parentDir, appName);

            if (bridge != null) {
                if (!appDir.exists() && !appDir.mkdirs()) {
                    bridge.createDir(appDir.getAbsolutePath());
                }
            }

            int audioSource = prefs.getInt("call_recording_audio_source", 0);
            String extension = audioSource >= 100 ? ".wav" : ".amr";

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = (phoneNumber != null && !phoneNumber.isEmpty())
                    ? "Call_" + phoneNumber.replaceAll("[^+0-9]", "") + "_" + timestamp + extension
                    : "Call_" + timestamp + extension;

            OutputTarget outputTarget = openOutputTarget(bridge, appDir, fileName);
            outputFileRef.set(outputTarget.file);

            // Close the streams from OutputTarget, MediaRecorder handles its own writing
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

            boolean useRoot = prefs.getBoolean("call_recording_use_root", false) || audioSource >= 100;

            if (useRoot) {
                startRootRecording(audioSource, outputTarget.file);
            } else {
                startStandardRecording(audioSource, outputTarget.file);
            }

            isRecording.set(true);

            if (prefs.getBoolean("call_recording_toast", false)) {
                Utils.showToast("Recording started", Toast.LENGTH_SHORT);
            }

        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: startRecording error: " + e.getMessage());
            isRecording.set(false);
        }
    }

    private void startStandardRecording(int source, File outFile) throws IOException {
        int correctedSource = source;
        if (source == 0)
            correctedSource = MediaRecorder.AudioSource.VOICE_CALL;
        else if (source == 1)
            correctedSource = MediaRecorder.AudioSource.VOICE_DOWNLINK;
        else if (source == 2)
            correctedSource = MediaRecorder.AudioSource.VOICE_UPLINK;
        else if (source == 3)
            correctedSource = MediaRecorder.AudioSource.MIC;
        else if (source == 4)
            correctedSource = MediaRecorder.AudioSource.DEFAULT;
        else if (source == 5)
            correctedSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        else if (source == 6)
            correctedSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;
        else if (source == 7)
            correctedSource = MediaRecorder.AudioSource.UNPROCESSED;

        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(correctedSource);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
        mediaRecorder.setOutputFile(outFile.getAbsolutePath());
        mediaRecorder.prepare();
        mediaRecorder.start();
        XposedBridge.log("WaEnhancer: Standard MediaRecorder started with source: " + correctedSource);
    }

    private void startRootRecording(int source, File outFile) {
        String alsaNode = prefs.getString("call_recording_alsa_node", "hw:0,0");
        String cafNode = prefs.getString("call_recording_caf_node", "hw:0,0");
        String cmd;

        if (source == 101) { // ALSA
            cmd = "alsa_arecord -D " + alsaNode + " -f S16_LE -c 1 -r 8000 " + outFile.getAbsolutePath();
        } else { // CAF or MSM
            String tinycapArgs = "";
            if (cafNode.startsWith("hw:")) {
                String[] parts = cafNode.replace("hw:", "").split(",");
                if (parts.length == 2) {
                    tinycapArgs = "-D " + parts[0] + " -d " + parts[1];
                }
            } else if (cafNode.startsWith("plughw:")) {
                String[] parts = cafNode.replace("plughw:", "").split(",");
                if (parts.length == 2) {
                    tinycapArgs = "-D " + parts[0] + " -d " + parts[1];
                }
            }
            if (tinycapArgs.isEmpty()) {
                tinycapArgs = "-D 0 -d 0";
            }
            cmd = "tinycap " + outFile.getAbsolutePath() + " " + tinycapArgs + " -r 8000 -c 1 -b 16";
        }

        try {
            rootAudioProcess = Runtime.getRuntime().exec(new String[] { "su", "-c", cmd });
            XposedBridge.log("WaEnhancer: Root recording started with cmd: " + cmd);
        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: Root recording error: " + e.getMessage());
        }
    }

    private synchronized void stopRecording() {
        if (!isRecording.getAndSet(false))
            return;

        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                XposedBridge.log("WaEnhancer: MediaRecorder stopped successfully");
            }

            if (rootAudioProcess != null) {
                rootAudioProcess.destroy();
                rootAudioProcess = null;
                Runtime.getRuntime().exec(new String[] { "su", "-c", "killall alsa_arecord tinycap" });
                XposedBridge.log("WaEnhancer: Root recording process killed");
            }

            File finalOut = outputFileRef.getAndSet(null);

            if (finalOut != null && finalOut.exists() && finalOut.length() > 0) {
                Utils.scanFile(finalOut);
                if (prefs.getBoolean("call_recording_toast", false)) {
                    Utils.showToast("Recording saved!", Toast.LENGTH_SHORT);
                }
            } else if (finalOut != null) {
                finalOut.delete();
            }

            currentPhoneNumber.set(null);
        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: stopRecording exception: " + e.getMessage());
        }
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
                if (num.trim().replaceAll("[^0-9]", "").equals(phone))
                    return true;
            }
        } catch (Exception e) {
        }
        return false;
    }

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
            } catch (Throwable t) {
            }
        }

        File appExternalDir = FeatureLoader.mApp.getExternalFilesDir(null);
        if (appExternalDir == null)
            throw new IOException("Could not resolve app external files directory");
        File fallbackDir = new File(appExternalDir, "Recordings");
        if (!fallbackDir.exists() && !fallbackDir.mkdirs())
            throw new IOException("Fallback dir failed");

        File fallbackFile = new File(fallbackDir, fileName);
        return new OutputTarget(fallbackFile, null, new FileOutputStream(fallbackFile));
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

    @NonNull
    @Override
    public String getPluginName() {
        return "Call Recording";
    }
}
