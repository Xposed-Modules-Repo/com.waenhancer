package com.waenhancer.xposed.features.others;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.components.FMessageWpp;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.utils.ReflectionUtils;
import com.waenhancer.xposed.utils.ResId;
import com.waenhancer.xposed.utils.Utils;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class GroupAdmin extends Feature {

    public GroupAdmin(@NonNull ClassLoader classLoader, @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("admin_grp", false)) return;
        var jidFactory = Unobfuscator.loadJidFactory(classLoader);
        var grpAdmin1 = Unobfuscator.loadGroupAdminMethod(classLoader);
        var grpcheckAdmin = Unobfuscator.loadGroupCheckAdminMethod(classLoader);
        var hooked = new XC_MethodHook() {
            @SuppressLint("ResourceType")
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    XposedBridge.log("GroupAdmin HOOK: afterHookedMethod triggered, thisObject=" + (param.thisObject != null ? param.thisObject.getClass().getName() : "null") + " args=" + (param.args != null ? param.args.length : 0));
                    var targetObj = param.thisObject;
                    if (targetObj == null && param.args != null) {
                        for (Object arg : param.args) {
                            if (arg instanceof android.view.View) {
                                targetObj = arg;
                                break;
                            }
                        }
                    }
                    if (targetObj == null) {
                        XposedBridge.log("GroupAdmin HOOK: No View target found!");
                        return;
                    }

                    Object fMessageObj = XposedHelpers.callMethod(targetObj, "getFMessage");
                    XposedBridge.log("GroupAdmin HOOK: fMessageObj=" + (fMessageObj != null ? fMessageObj.getClass().getName() : "null"));
                    if (fMessageObj == null) return;
                    var fMessage = new FMessageWpp(fMessageObj);
                    var userJid = fMessage.getUserJid();
                    XposedBridge.log("GroupAdmin HOOK: userJid=" + (userJid != null ? userJid.toString() : "null"));
                    var chatCurrentJid = WppCore.getCurrentUserJid();
                    if (chatCurrentJid == null || !chatCurrentJid.isGroup()) return;
                    XposedBridge.log("GroupAdmin HOOK: In a group chat, checking admin status...");
                    var field = ReflectionUtils.getFieldByType(targetObj.getClass(), grpcheckAdmin.getDeclaringClass());
                    if (field == null) {
                        XposedBridge.log("GroupAdmin HOOK: field for grpcheckAdmin not found in " + targetObj.getClass().getName());
                        return;
                    }
                    var grpParticipants = field.get(targetObj);
                    var jidGrp = jidFactory.invoke(null, chatCurrentJid.getUserRawString());
                    var result = grpcheckAdmin.invoke(grpParticipants, jidGrp, userJid.userJid);
                    XposedBridge.log("GroupAdmin HOOK: isAdmin result=" + result);
                    var view = (View) targetObj;
                    var context = view.getContext();
                    ImageView iconAdmin;
                    if ((iconAdmin = view.findViewById(0x7fff0010)) == null) {
                        var nameGroup = (LinearLayout) view.findViewById(Utils.getID("name_in_group", "id"));
                        if (nameGroup == null) {
                            XposedBridge.log("GroupAdmin HOOK: name_in_group view not found!");
                            return;
                        }
                        var view1 = new LinearLayout(context);
                        view1.setOrientation(LinearLayout.HORIZONTAL);
                        view1.setGravity(Gravity.CENTER_VERTICAL);
                        var nametv = nameGroup.getChildAt(0);
                        iconAdmin = new ImageView(context);
                        var size = Utils.dipToPixels(16);
                        iconAdmin.setLayoutParams(new LinearLayout.LayoutParams(size, size));
                        iconAdmin.setImageResource(ResId.drawable.admin);
                        iconAdmin.setId(0x7fff0010);
                        nameGroup.removeView(nametv);
                        view1.addView(nametv);
                        view1.addView(iconAdmin);
                        nameGroup.addView(view1, 0);
                    }
                    iconAdmin.setVisibility(result != null && (boolean) result ? View.VISIBLE : View.GONE);
                    XposedBridge.log("GroupAdmin HOOK: Icon visibility set to " + (result != null && (boolean) result ? "VISIBLE" : "GONE"));
                } catch (Throwable t) {
                    XposedBridge.log("GroupAdmin HOOK ERROR: " + t.getMessage());
                    XposedBridge.log(t);
                }
            }
        };
        XposedBridge.hookMethod(grpAdmin1, hooked);
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "GroupAdmin";
    }
}
