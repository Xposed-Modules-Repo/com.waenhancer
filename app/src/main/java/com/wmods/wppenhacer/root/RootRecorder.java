package com.wmods.wppenhacer.root;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class RootRecorder {

    private static final AtomicBoolean isRecording = new AtomicBoolean(true);

    // For each source, try to open an AudioRecord, log the result, and use the
    // first success
    private static int[] DOWNLINK_SOURCES = {
            MediaRecorder.AudioSource.VOICE_CALL, // 4 — both sides of call (best for VoIP on MIUI)
            8, // REMOTE_SUBMIX — captures app audio output
            MediaRecorder.AudioSource.VOICE_DOWNLINK, // 3 — telephony downlink only
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // 7 — processed VoIP mic (fallback)
    };

    @SuppressLint("MissingPermission")
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("RootRecorder: Missing arguments <uplink_path> <downlink_path>");
            return;
        }

        String upPath = args[0];
        String downPath = args[1];

        System.out.println("RootRecorder (UID 0) starting...");

        int sampleRate = 48000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int bufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        if (bufSize <= 0)
            bufSize = sampleRate;
        bufSize *= 8; // larger buffer for stability

        // UPLINK: MIC or VOICE_UPLINK
        AudioRecord uplink = new AudioRecord(MediaRecorder.AudioSource.VOICE_UPLINK, sampleRate,
                channelConfig, audioFormat, bufSize);
        if (uplink.getState() != AudioRecord.STATE_INITIALIZED) {
            uplink.release();
            uplink = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig,
                    audioFormat, bufSize);
            System.out.println("RootRecorder: VOICE_UPLINK failed, falling back to MIC");
        }

        // DOWNLINK: try each source until one initializes
        AudioRecord downlink = null;
        for (int src : DOWNLINK_SOURCES) {
            try {
                AudioRecord candidate = new AudioRecord(src, sampleRate, channelConfig,
                        audioFormat, bufSize);
                int state = candidate.getState();
                System.out.println("RootRecorder: Source " + src + " -> state=" + state);
                if (state == AudioRecord.STATE_INITIALIZED) {
                    downlink = candidate;
                    System.out.println("RootRecorder: Using downlink source=" + src);
                    break;
                }
                candidate.release();
            } catch (Exception e) {
                System.err.println("RootRecorder: Source " + src + " threw: " + e.getMessage());
            }
        }

        if (uplink.getState() != AudioRecord.STATE_INITIALIZED) {
            System.err.println("RootRecorder: No usable uplink, aborting.");
            uplink.release();
            if (downlink != null)
                downlink.release();
            return;
        }
        if (downlink == null) {
            System.err.println("RootRecorder: No usable downlink, proceeding with uplink only.");
            // Still record uplink to upPath, write empty downlink
            try {
                new java.io.File(downPath).createNewFile();
            } catch (Exception e) {
            }
            downlink = uplink; // placeholder so threads run
        }

        uplink.startRecording();
        if (downlink != uplink) {
            downlink.startRecording();
        }

        System.out.println("RootRecorder: Streams started. upPath=" + upPath + " downPath=" + downPath);

        final AudioRecord finalUplink = uplink;
        final AudioRecord finalDownlink = downlink;
        final int finalBufSize = bufSize;
        AtomicLong downBytes = new AtomicLong(0);

        Thread upThread = new Thread(() -> recordLoop(finalUplink, upPath, finalBufSize, null));
        Thread downThread = (finalDownlink != finalUplink)
                ? new Thread(() -> recordLoop(finalDownlink, downPath, finalBufSize, downBytes))
                : null;

        upThread.start();
        if (downThread != null)
            downThread.start();

        // Block until WaEnhancer stdin signals stop
        try {
            InputStream in = System.in;
            while (true) {
                int read = in.read();
                if (read == -1 || read == 'q')
                    break;
            }
        } catch (Exception e) {
            System.err.println("RootRecorder: Stdin error: " + e.getMessage());
        }

        System.out.println("RootRecorder: Stop signal received.");
        isRecording.set(false);

        try {
            uplink.stop();
            uplink.release();
            if (finalDownlink != finalUplink) {
                downlink.stop();
                downlink.release();
            }
            upThread.join(2000);
            if (downThread != null)
                downThread.join(2000);
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("RootRecorder: Exited cleanly. downlink_bytes=" + downBytes.get());
    }

    private static void recordLoop(AudioRecord record, String path, int bufSize, AtomicLong counter) {
        try (FileOutputStream fos = new FileOutputStream(path)) {
            byte[] buf = new byte[bufSize];
            while (isRecording.get() && record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                int read = record.read(buf, 0, buf.length);
                if (read > 0) {
                    fos.write(buf, 0, read);
                    if (counter != null)
                        counter.addAndGet(read);
                } else if (read < 0) {
                    System.err.println("RootRecorder: Stream read error code=" + read);
                    break;
                }
            }
            fos.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
