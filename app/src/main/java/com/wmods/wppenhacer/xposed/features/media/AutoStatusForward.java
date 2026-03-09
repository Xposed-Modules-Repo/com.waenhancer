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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * AutoStatusForward – forwards the status that a contact replied to,
 * when their reply message matches one of the configured keyword rules.
 *
 * Rules are stored as a JSON array by StatusForwardRulesActivity:
 * [{"type":"contains","text":"send me"}, {"type":"equals","text":"hello"}]
 * All matching is always case-insensitive.
 *
 * If no rules are configured, ANY reply to a status triggers the forward
 * (catch-all).
 *
 * Only messages where the QUOTED context points to a status (status@broadcast)
 * are considered – normal messages without a status quote are ignored.
 */
public class AutoStatusForward extends Feature {

    public AutoStatusForward(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Exception {
        if (!prefs.getBoolean("auto_status_forward", false))
            return;

        // Reuse the same "MessageHandler/start" method that DndMode hooks.
        // It is called after every incoming message is stored/processed.
        var messageHandlerMethod = Unobfuscator.loadDndModeMethod(classLoader);
        logDebug("AutoStatusForward – MessageHandler method: " + messageHandlerMethod);

        XposedBridge.hookMethod(messageHandlerMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    handleIncomingMessage(param);
                } catch (Throwable t) {
                    logDebug("AutoStatusForward – handleIncomingMessage error: " + t.getMessage());
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Core logic
    // -------------------------------------------------------------------------

    private void handleIncomingMessage(XC_MethodHook.MethodHookParam param) throws Throwable {
        // The FMessage object may be in param.args or in a field of param.thisObject.
        Object fMessageObj = extractFMessage(param);
        if (fMessageObj == null)
            return;

        FMessageWpp incoming = new FMessageWpp(fMessageObj);
        FMessageWpp.Key incomingKey = incoming.getKey();

        // Must be a received message (not sent by me).
        if (incomingKey == null || incomingKey.isFromMe)
            return;

        // The sending contact's JID (the one who replied).
        FMessageWpp.UserJid senderJid = incomingKey.remoteJid;
        if (senderJid == null || senderJid.isNull())
            return;
        if (senderJid.isGroup())
            return; // Only handle 1-to-1 replies for now.

        // ---- Check whether this message is a reply to a status ----
        FMessageWpp quotedStatus = extractQuotedStatusFMessage(fMessageObj);
        if (quotedStatus == null)
            return; // Not a status reply – ignore.

        // ---- Check keyword rules ----
        String incomingText = incoming.getMessageStr();
        if (!matchesRules(incomingText))
            return;

        // ---- Auto-forward the replied-to status to the sender ----
        CompletableFuture.runAsync(() -> {
            try {
                forwardStatusToSender(quotedStatus, senderJid);
            } catch (Throwable t) {
                logDebug("AutoStatusForward – forwardStatusToSender error: " + t.getMessage());
                XposedBridge.log("AutoStatusForward error: " + t.getMessage());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Extract the FMessage object from the hook parameters
    // -------------------------------------------------------------------------

    private Object extractFMessage(XC_MethodHook.MethodHookParam param) {
        if (param.args == null)
            return null;
        for (Object arg : param.args) {
            if (arg != null && FMessageWpp.TYPE.isInstance(arg))
                return arg;
        }
        // Try grabbing from fields of the declared class instance
        if (param.thisObject != null) {
            var field = ReflectionUtils.findFieldUsingFilterIfExists(
                    param.thisObject.getClass(),
                    f -> FMessageWpp.TYPE.isAssignableFrom(f.getType()));
            if (field != null) {
                return ReflectionUtils.getObjectField(field, param.thisObject);
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Detect status reply by inspecting the quoted / context message key
    // -------------------------------------------------------------------------

    /**
     * WhatsApp embeds the "context" (quoted message) inside FMessage fields.
     * We look for a field that holds an object containing a message key whose
     * remoteJid equals "status@broadcast". If found, we return the quoted
     * FMessage (fetched from the cache), otherwise null.
     */
    private FMessageWpp extractQuotedStatusFMessage(Object fMessageObj) {
        try {
            Class<?> fMessageClass = fMessageObj.getClass();
            // Walk all fields of FMessage looking for an object that contains
            // a message key whose remoteJid is the status broadcast JID.
            for (Field field : getAllFields(fMessageClass)) {
                field.setAccessible(true);
                Object fieldValue = field.get(fMessageObj);
                if (fieldValue == null)
                    continue;

                // Is the field itself a message key?
                if (FMessageWpp.Key.TYPE != null && FMessageWpp.Key.TYPE.isInstance(fieldValue)) {
                    FMessageWpp.UserJid quotedJid = extractRemoteJidFromKey(fieldValue);
                    if (quotedJid != null && isStatusJid(quotedJid)) {
                        // The key might point to the original status FMessage.
                        Object quotedFMessage = WppCore.getFMessageFromKey(fieldValue);
                        if (quotedFMessage != null) {
                            return new FMessageWpp(quotedFMessage);
                        }
                    }
                }

                // Or is the field a wrapper that contains a key?
                try {
                    Field keyField = ReflectionUtils.findFieldUsingFilterIfExists(
                            fieldValue.getClass(),
                            f -> FMessageWpp.Key.TYPE != null && FMessageWpp.Key.TYPE.isAssignableFrom(f.getType()));
                    if (keyField != null) {
                        keyField.setAccessible(true);
                        Object keyObj = keyField.get(fieldValue);
                        if (keyObj != null) {
                            FMessageWpp.UserJid quotedJid = extractRemoteJidFromKey(keyObj);
                            if (quotedJid != null && isStatusJid(quotedJid)) {
                                Object quotedFMessage = WppCore.getFMessageFromKey(keyObj);
                                if (quotedFMessage != null) {
                                    return new FMessageWpp(quotedFMessage);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable t) {
            logDebug("AutoStatusForward – extractQuotedStatusFMessage: " + t.getMessage());
        }
        return null;
    }

    private FMessageWpp.UserJid extractRemoteJidFromKey(Object keyObj) {
        try {
            Object rawJid = XposedHelpers.getObjectField(keyObj, "A00");
            if (rawJid == null)
                return null;
            return new FMessageWpp.UserJid(rawJid);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isStatusJid(FMessageWpp.UserJid jid) {
        String phone = jid.getPhoneNumber();
        if (phone == null)
            return false;
        return phone.equals("status") || phone.equals("status@broadcast");
    }

    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    // -------------------------------------------------------------------------
    // Keyword rule matching
    // -------------------------------------------------------------------------

    /**
     * Returns true if the incoming message text matches at least one rule.
     * Rules are a JSON array stored by StatusForwardRulesActivity.
     * All comparisons are case-insensitive. Catch-all if rules list is empty.
     */
    private boolean matchesRules(String messageText) {
        String json = prefs.getString(
                "auto_status_forward_rules_json", "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            if (arr.length() == 0)
                return true; // catch-all
            if (TextUtils.isEmpty(messageText))
                return false;
            String lowerMsg = messageText.trim().toLowerCase();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject rule = arr.getJSONObject(i);
                String type = rule.optString("type", "contains").toLowerCase();
                String text = rule.optString("text", "").trim().toLowerCase();
                if (text.isEmpty())
                    continue;
                if ("equals".equals(type) && lowerMsg.equals(text))
                    return true;
                if (!"equals".equals(type) && lowerMsg.contains(text))
                    return true;
            }
        } catch (Exception e) {
            logDebug("AutoStatusForward – matchesRules error: " + e.getMessage());
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Forward the status to the sender using MediaComposerActivity / text intent
    // -------------------------------------------------------------------------

    private void forwardStatusToSender(FMessageWpp statusMessage, FMessageWpp.UserJid senderJid) throws Exception {
        String senderJidRaw = senderJid.getPhoneRawString();
        if (senderJidRaw == null)
            return;

        // Show a toast so the user knows the auto-forward fired.
        String senderName = WppCore.getContactName(senderJid);
        if (TextUtils.isEmpty(senderName)) {
            senderName = senderJid.getPhoneNumber();
        }
        final String displayName = senderName;
        Utils.showToast(
                "📤 Auto-forwarding status to " + displayName,
                Toast.LENGTH_SHORT);

        boolean isMedia = statusMessage.isMediaFile();

        if (!isMedia) {
            // Text-only status
            forwardTextStatus(statusMessage, senderJidRaw);
        } else {
            forwardMediaStatus(statusMessage, senderJidRaw);
        }
    }

    private void forwardTextStatus(FMessageWpp statusMessage, String recipientJidRaw) throws Exception {
        String text = statusMessage.getMessageStr();
        if (TextUtils.isEmpty(text)) {
            logDebug("AutoStatusForward – text status has no text, skipping.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        // Target the recipient directly via MediaComposerActivity
        try {
            var clazz = Unobfuscator.getClassByName("MediaComposerActivity", classLoader);
            intent = new Intent();
            intent.setClassName(Utils.getApplication().getPackageName(), clazz.getName());
            intent.putExtra("jids", new ArrayList<>(Collections.singleton(recipientJidRaw)));
            intent.putExtra(Intent.EXTRA_TEXT, text);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Utils.getApplication().startActivity(intent);
        } catch (Exception e) {
            logDebug("AutoStatusForward – forwardTextStatus fallback: " + e.getMessage());
        }
    }

    private void forwardMediaStatus(FMessageWpp statusMessage, String recipientJidRaw) throws Exception {
        var file = statusMessage.getMediaFile();
        if (file == null || !file.exists()) {
            logDebug("AutoStatusForward – media file not cached, cannot auto-forward.");
            Utils.showToast("⚠️ Status media not cached yet, cannot auto-forward.", Toast.LENGTH_LONG);
            return;
        }

        Uri mediaUri;
        try {
            String authority = Utils.getApplication().getPackageName() + ".fileprovider";
            mediaUri = FileProvider.getUriForFile(Utils.getApplication(), authority, file);
        } catch (IllegalArgumentException e) {
            mediaUri = Uri.fromFile(file);
        }

        Intent intent = new Intent();
        var clazz = Unobfuscator.getClassByName("MediaComposerActivity", classLoader);
        intent.setClassName(Utils.getApplication().getPackageName(), clazz.getName());
        intent.putExtra("jids", new ArrayList<>(Collections.singleton(recipientJidRaw)));
        intent.putExtra(Intent.EXTRA_STREAM, new ArrayList<>(Collections.singleton(mediaUri)));

        // Include caption if present
        String caption = statusMessage.getMessageStr();
        if (!TextUtils.isEmpty(caption)) {
            intent.putExtra(Intent.EXTRA_TEXT, caption);
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        Utils.getApplication().startActivity(intent);
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Auto Status Forward";
    }
}
