package com.wmods.wppenhacer.xposed.features.media;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

public class AutoStatusForward extends Feature {

    private static Field quotedContextFieldCache = null;
    private static Method getQuotedKeyMethodCache = null;
    private static boolean scannedForQuoted = false;

    public AutoStatusForward(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Exception {
        if (FMessageWpp.TYPE == null)
            return;
        log("AutoStatusForward – hooking all constructors of " + FMessageWpp.TYPE.getName());

        XposedBridge.hookAllConstructors(FMessageWpp.TYPE, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    prefs.reload();
                    if (!prefs.getBoolean("auto_status_forward", false))
                        return;
                    handleFMessage(param.thisObject);
                } catch (Throwable t) {
                    // silent catch to prevent WA crashes
                }
            }
        });
    }

    private void handleFMessage(Object fMessageObj) {
        if (fMessageObj == null)
            return;
        FMessageWpp incoming;
        try {
            incoming = new FMessageWpp(fMessageObj);
        } catch (Throwable t) {
            return;
        }

        FMessageWpp.Key key = incoming.getKey();
        if (key == null || key.isFromMe)
            return;
        FMessageWpp.UserJid senderJid = key.remoteJid;
        if (senderJid == null || senderJid.isNull() || senderJid.isGroup())
            return;

        // FAST PATH: Check rules first so we don't do expensive checks if not matching
        String text = incoming.getMessageStr();
        if (!matchesRules(text))
            return;

        log("AutoStatusForward – text match: «" + text + "». Checking if status reply...");

        FMessageWpp quotedStatus = extractQuotedStatus(fMessageObj);
        if (quotedStatus == null)
            return;

        log("AutoStatusForward – IS a status reply! Forwarding to " + senderJid.getPhoneNumber());

        final FMessageWpp statusToSend = quotedStatus;
        final String replyText = text;
        final FMessageWpp.UserJid recipient = senderJid;

        CompletableFuture.runAsync(() -> {
            try {
                forwardStatus(statusToSend, replyText, recipient);
            } catch (Throwable t) {
                log("AutoStatusForward – forward err: " + t);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Safely and quickly extract quoted status without crashing
    // -------------------------------------------------------------------------

    private FMessageWpp extractQuotedStatus(Object fMessageObj) {
        try {
            if (!scannedForQuoted)
                scanForQuotedContextInfo(fMessageObj.getClass());

            Object rawQuotedKey = null;

            // Strategy 1: Method returning Key
            if (getQuotedKeyMethodCache != null) {
                try {
                    rawQuotedKey = getQuotedKeyMethodCache.invoke(fMessageObj);
                } catch (Throwable ignored) {
                }
            }

            // Strategy 2: ContextInfo field containing Key inside it
            if (rawQuotedKey == null && quotedContextFieldCache != null) {
                try {
                    Object contextInfo = quotedContextFieldCache.get(fMessageObj);
                    if (contextInfo != null) {
                        for (Field f : contextInfo.getClass().getDeclaredFields()) {
                            if (FMessageWpp.Key.TYPE.isAssignableFrom(f.getType())) {
                                f.setAccessible(true);
                                rawQuotedKey = f.get(contextInfo);
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            if (rawQuotedKey == null)
                return null;

            FMessageWpp.Key msgKey = new FMessageWpp.Key(rawQuotedKey);
            if (msgKey.remoteJid != null) {
                String phone = msgKey.remoteJid.getPhoneNumber();
                if (phone != null && (phone.equals("status") || phone.contains("broadcast"))) {
                    Object q = WppCore.getFMessageFromKey(rawQuotedKey);
                    if (q != null)
                        return new FMessageWpp(q);
                    log("AutoStatusForward – status replied to but not in cache");
                    // Still return a dummy to trigger fallback
                    return new FMessageWpp(fMessageObj); // just return something non-null
                }
            }
        } catch (Throwable t) {
            log("AutoStatusForward – extractQuotedStatus err: " + t);
        }
        return null;
    }

    private synchronized void scanForQuotedContextInfo(Class<?> fMessageClass) {
        if (scannedForQuoted)
            return;
        List<Field> fields = getAllFields(fMessageClass);
        List<Method> methods = getAllMethods(fMessageClass);

        // 1. Find method returning Key that isn't the main key
        for (Method m : methods) {
            if (m.getParameterCount() == 0 && FMessageWpp.Key.TYPE.isAssignableFrom(m.getReturnType())) {
                if (!m.getName().equals("A1J") && !m.getName().equals("A1K") && !m.getName().equals("getKey")) { // ignore
                                                                                                                 // obvious
                                                                                                                 // main
                                                                                                                 // key
                                                                                                                 // methods
                    log("AutoStatusForward – found potential quoted key method: " + m.getName());
                    getQuotedKeyMethodCache = m;
                    m.setAccessible(true);
                    break;
                }
            }
        }

        // 2. Find field holding ContextInfo (looks like an object with a Key inside it)
        if (getQuotedKeyMethodCache == null) {
            for (Field f : fields) {
                Class<?> type = f.getType();
                if (type.isPrimitive() || type.getName().startsWith("java.") || type.getName().startsWith("android."))
                    continue;
                // See if this type has a Key field
                boolean hasKey = false;
                for (Field nestedF : type.getDeclaredFields()) {
                    if (FMessageWpp.Key.TYPE.isAssignableFrom(nestedF.getType())) {
                        hasKey = true;
                        break;
                    }
                }
                if (hasKey) {
                    log("AutoStatusForward – found quoted context field: " + f.getName() + " of type "
                            + type.getName());
                    quotedContextFieldCache = f;
                    f.setAccessible(true);
                    break;
                }
            }
        }
        scannedForQuoted = true;
        log("AutoStatusForward – scanForQuotedContextInfo completed.");
    }

    private List<Field> getAllFields(Class<?> c) {
        List<Field> list = new ArrayList<>();
        while (c != null && c != Object.class) {
            list.addAll(Arrays.asList(c.getDeclaredFields()));
            c = c.getSuperclass();
        }
        return list;
    }

    private List<Method> getAllMethods(Class<?> c) {
        List<Method> list = new ArrayList<>();
        while (c != null && c != Object.class) {
            list.addAll(Arrays.asList(c.getDeclaredMethods()));
            c = c.getSuperclass();
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // Rule matching
    // -------------------------------------------------------------------------

    private boolean matchesRules(String messageText) {
        String json = prefs.getString("auto_status_forward_rules_json", "[]");
        try {
            JSONArray arr = new JSONArray(json);
            if (arr.length() == 0)
                return true; // catch-all
            if (TextUtils.isEmpty(messageText))
                return false;
            String lower = messageText.trim().toLowerCase();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject rule = arr.getJSONObject(i);
                String type = rule.optString("type", "contains").toLowerCase();
                String ruleText = rule.optString("text", "").trim().toLowerCase();
                if (ruleText.isEmpty())
                    continue;
                if ("equals".equals(type) && lower.equals(ruleText))
                    return true;
                if (!"equals".equals(type) && lower.contains(ruleText))
                    return true;
            }
        } catch (Exception e) {
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Forward
    // -------------------------------------------------------------------------

    private void forwardStatus(FMessageWpp statusMsg, String replyText, FMessageWpp.UserJid recipientJid)
            throws Exception {
        String jidRaw = recipientJid.getPhoneRawString();
        if (jidRaw == null)
            return;
        String name = WppCore.getContactName(recipientJid);
        if (TextUtils.isEmpty(name))
            name = recipientJid.getPhoneNumber();
        Utils.showToast("📤 Auto-forwarding status to " + name, Toast.LENGTH_SHORT);

        if (statusMsg.isMediaFile())
            forwardMediaStatus(statusMsg, jidRaw);
        else
            forwardTextStatus("[Status reply from " + name + "]: " + replyText, jidRaw);
    }

    private void forwardTextStatus(String text, String jidRaw) {
        try {
            Class<?> cls = findMediaComposerClass();
            Intent intent = new Intent();
            intent.setClassName(Utils.getApplication().getPackageName(), cls.getName());
            intent.putExtra("jids", new ArrayList<>(Collections.singleton(jidRaw)));
            intent.putExtra(Intent.EXTRA_TEXT, text);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Utils.getApplication().startActivity(intent);
        } catch (Exception e) {
        }
    }

    private void forwardMediaStatus(FMessageWpp status, String jidRaw) {
        var file = status.getMediaFile();
        if (file == null || !file.exists()) {
            Utils.showToast("⚠️ Status media not cached.", Toast.LENGTH_LONG);
            return;
        }
        try {
            Uri uri;
            try {
                uri = FileProvider.getUriForFile(Utils.getApplication(),
                        Utils.getApplication().getPackageName() + ".fileprovider", file);
            } catch (Exception e) {
                uri = Uri.fromFile(file);
            }
            Class<?> cls = findMediaComposerClass();
            Intent intent = new Intent();
            intent.setClassName(Utils.getApplication().getPackageName(), cls.getName());
            intent.putExtra("jids", new ArrayList<>(Collections.singleton(jidRaw)));
            intent.putExtra(Intent.EXTRA_STREAM, new ArrayList<>(Collections.singleton(uri)));
            String caption = status.getMessageStr();
            if (!TextUtils.isEmpty(caption))
                intent.putExtra(Intent.EXTRA_TEXT, caption);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            Utils.getApplication().startActivity(intent);
        } catch (Exception e) {
        }
    }

    private Class<?> findMediaComposerClass() throws Exception {
        try {
            return Unobfuscator.getClassByName("MediaComposerActivity", classLoader);
        } catch (Exception ignored) {
        }
        for (String c : new String[] { "com.whatsapp.mediacomposer.MediaComposerActivity",
                "com.whatsapp.compose.MediaComposerActivity" }) {
            try {
                return classLoader.loadClass(c);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new Exception("MediaComposerActivity not found");
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Auto Status Forward";
    }
}
