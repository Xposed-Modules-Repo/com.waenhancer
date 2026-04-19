package com.waenhancer.xposed.features.customization;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.BaseAdapter;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.db.MessageStore;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.core.devkit.UnobfuscatorCache;
import com.waenhancer.xposed.utils.DebugUtils;
import com.waenhancer.xposed.utils.ReflectionUtils;
import com.waenhancer.xposed.utils.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SeparateGroup extends Feature {

    public static final int CHATS = 200;
    public static final int STATUS = 300;
    public static final int GROUPS = 500;
    
    public static ArrayList<Integer> tabs = new ArrayList<>();
    public static HashMap<Integer, Object> tabInstances = new HashMap<>();
    
    private volatile int chatTabId = CHATS;
    private volatile boolean featureEnabled;
    private volatile boolean loggedTabListSearchShape = false;
    private volatile boolean statusFallbackMode = false;
    private volatile int fallbackGroupTabId = STATUS;

    public SeparateGroup(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    public void doHook() throws Exception {
        var cFragClass = XposedHelpers.findClass("com.whatsapp.conversationslist.ConversationsFragment", classLoader);
        var homeActivityClass = WppCore.getHomeActivityClass(classLoader);

        // Enable for testing - will be disabled by default in production
        if (!prefs.getBoolean("separategroups", true)) return;

        featureEnabled = true;
        statusFallbackMode = false;
        fallbackGroupTabId = STATUS;
        
        XposedBridge.log("SeparateGroup: Feature enabled, starting hooks");

        try {
            // Hook the tab list initialization
            hookTabList(homeActivityClass);
            
            // Add listener for activity resume to inject groups tab at runtime
            WppCore.addListenerActivity((activity, state) -> {
                if (state != WppCore.ActivityChangeState.ChangeType.RESUMED) return;
                try {
                    injectGroupsTab(activity, null);
                } catch (Throwable throwable) {
                    XposedBridge.log("SeparateGroup: listener inject failed: " + throwable);
                }
            });

            // Try immediate injection if activity is available
            try {
                var currentActivity = WppCore.getCurrentActivity();
                if (currentActivity != null) {
                    injectGroupsTab(currentActivity, null);
                }
            } catch (Throwable throwable) {
                XposedBridge.log("SeparateGroup: immediate inject failed: " + throwable);
            }

            // Setting group icon
            hookTabIcon();

            // Setting up fragments
            hookTabInstance(cFragClass);

            // Setting group tab name
            hookTabName();

            // Setting tab count
            hookTabCount();
        } catch (Exception e) {
            XposedBridge.log("SeparateGroup: Error during hook setup: " + e);
            e.printStackTrace();
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Separate Group";
    }

    private void hookTabList(@NonNull Class<?> home) throws Exception {
        var onCreateTabList = Unobfuscator.loadTabListMethod(classLoader);
        logDebug(Unobfuscator.getMethodDescriptor(onCreateTabList));

        XposedBridge.hookMethod(onCreateTabList, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!featureEnabled) return;
                try {
                    injectGroupsTab(param.thisObject, null);
                } catch (Throwable throwable) {
                    XposedBridge.log("SeparateGroup: hookTabList inject failed: " + throwable);
                }
            }
        });
    }

    private void hookTabCount() throws Exception {
        var runMethod = Unobfuscator.loadTabCountMethod(classLoader);
        logDebug(Unobfuscator.getMethodDescriptor(runMethod));

        var enableCountMethod = Unobfuscator.loadEnableCountTabMethod(classLoader);
        var constructor1 = Unobfuscator.loadEnableCountTabConstructor1(classLoader);
        var constructor2 = Unobfuscator.loadEnableCountTabConstructor2(classLoader);
        var constructor3 = Unobfuscator.loadEnableCountTabConstructor3(classLoader);
        constructor3.setAccessible(true);

        logDebug(Unobfuscator.getMethodDescriptor(enableCountMethod));
        XposedBridge.hookMethod(enableCountMethod, new XC_MethodHook() {
            @Override
            @SuppressLint({"Range", "Recycle"})
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                var indexTab = (int) param.args[2];
                var resolvedChatTabId = resolveChatTabId();
                if (indexTab == tabs.indexOf(resolvedChatTabId)) {

                    var chatCount = 0;
                    var groupCount = 0;
                    synchronized (SeparateGroup.class) {
                        var db = MessageStore.getInstance().getDatabase();
                        var sql = "SELECT * FROM chat WHERE unseen_message_count != 0";
                        var cursor = db.rawQuery(sql, null);
                        while (cursor.moveToNext()) {
                            int jid = cursor.getInt(cursor.getColumnIndex("jid_row_id"));
                            int groupType = cursor.getInt(cursor.getColumnIndex("group_type"));
                            int archived = cursor.getInt(cursor.getColumnIndex("archived"));
                            int chatLocked = cursor.getInt(cursor.getColumnIndex("chat_lock"));
                            if (archived != 0 || (groupType != 0 && groupType != 6) || chatLocked != 0)
                                continue;
                            var sql2 = "SELECT * FROM jid WHERE _id == ?";
                            var cursor1 = db.rawQuery(sql2, new String[]{String.valueOf(jid)});
                            if (!cursor1.moveToFirst()) continue;
                            var server = cursor1.getString(cursor1.getColumnIndex("server"));
                            if (server.equals("g.us")) {
                                groupCount++;
                            } else {
                                chatCount++;
                            }
                            cursor1.close();
                        }
                        cursor.close();
                    }
                    if (tabs.contains(resolvedChatTabId) && tabInstances.containsKey(resolvedChatTabId)) {
                        var instance12 = chatCount <= 0 ? constructor3.newInstance() : constructor2.newInstance(chatCount);
                        var instance22 = constructor1.newInstance(instance12);
                        param.args[1] = instance22;
                    }
                    if (tabs.contains(GROUPS) && tabInstances.containsKey(GROUPS)) {
                        var instance2 = groupCount <= 0 ? constructor3.newInstance() : constructor2.newInstance(groupCount);
                        var instance1 = constructor1.newInstance(instance2);
                        enableCountMethod.invoke(param.thisObject, param.args[0], instance1, tabs.indexOf(GROUPS));
                    }
                }
            }
        });
    }

    private void hookTabIcon() throws Exception {
        var iconTabMethod = Unobfuscator.loadIconTabMethod(classLoader);
        logDebug(Unobfuscator.getMethodDescriptor(iconTabMethod));
        var menuAddAndroidX = Unobfuscator.loadAddMenuAndroidX(classLoader);
        logDebug(menuAddAndroidX);

        XposedBridge.hookMethod(iconTabMethod, new XC_MethodHook() {
                    private XC_MethodHook.Unhook hooked;

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            var activity = WppCore.getCurrentActivity();
                            injectGroupsTab(activity != null ? activity : param.thisObject, null);
                        } catch (Throwable throwable) {
                            XposedBridge.log("SeparateGroup: icon hook inject failed: " + throwable);
                        }
                        hooked = XposedBridge.hookMethod(menuAddAndroidX, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.args.length > 2 && ((int) param.args[1]) == GROUPS) {
                                    MenuItem menuItem = (MenuItem) param.getResult();
                                    menuItem.setIcon(Utils.getID("home_tab_communities_selector", "drawable"));
                                }
                            }
                        });
                    }

                    @SuppressLint("ResourceType")
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (hooked != null) {
                            hooked.unhook();
                        }
                    }
                }
        );
    }

    @SuppressLint("ResourceType")
    private void hookTabName() throws Exception {
        var tabNameMethod = Unobfuscator.loadTabNameMethod(classLoader);
        logDebug("TAB NAME", Unobfuscator.getMethodDescriptor(tabNameMethod));
        XposedBridge.hookMethod(tabNameMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    var activity = WppCore.getCurrentActivity();
                    injectGroupsTab(activity != null ? activity : param.thisObject, null);
                } catch (Throwable throwable) {
                    XposedBridge.log("SeparateGroup: tab name hook inject failed: " + throwable);
                }
                var tab = (int) param.args[0];
                if (isGroupsTabId(tab)) {
                    param.setResult(UnobfuscatorCache.getInstance().getString("groups"));
                }
            }
        });
    }

    private void hookTabInstance(Class<?> cFrag) throws Exception {
        var getTabMethod = Unobfuscator.loadGetTabMethod(classLoader);
        logDebug(Unobfuscator.getMethodDescriptor(getTabMethod));

        var methodTabInstance = Unobfuscator.loadTabFragmentMethod(classLoader);
        logDebug(Unobfuscator.getMethodDescriptor(methodTabInstance));

        var recreateFragmentMethod = Unobfuscator.loadRecreateFragmentConstructor(classLoader);

        var pattern = Pattern.compile("android:switcher:\\d+:(\\d+)");

        Class<?> FragmentClass = Unobfuscator.loadFragmentClass(classLoader);

        XposedBridge.hookMethod(recreateFragmentMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var string = "";
                if (param.args[0] instanceof Bundle bundle) {
                    var state = bundle.getParcelable("state");
                    if (state == null) return;
                    string = state.toString();
                } else {
                    string = param.args[2].toString();
                }
                var matcher = pattern.matcher(string);
                if (matcher.find()) {
                    var tabId = Integer.parseInt(matcher.group(1));
                    if (isGroupsTabId(tabId) || tabId == resolveChatTabId()) {
                        var fragmentField = ReflectionUtils.getFieldByType(param.thisObject.getClass(), FragmentClass);
                        var convFragment = ReflectionUtils.getObjectField(fragmentField, param.thisObject);
                        tabInstances.remove(tabId);
                        tabInstances.put(tabId, convFragment);
                    }
                }
            }
        });

        XposedBridge.hookMethod(getTabMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (tabs == null || tabs.isEmpty()) return;
                var index = (int) param.args[0];
                if (index < 0 || index >= tabs.size()) return;
                var tabId = tabs.get(index).intValue();
                if (isGroupsTabId(tabId) || tabId == resolveChatTabId()) {
                    var convFragment = cFrag.newInstance();
                    param.setResult(convFragment);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (tabs == null || tabs.isEmpty()) return;
                var index = (int) param.args[0];
                if (index < 0 || index >= tabs.size()) return;
                var tabId = tabs.get(index).intValue();
                tabInstances.remove(tabId);
                tabInstances.put(tabId, param.getResult());
            }
        });

        XposedBridge.hookMethod(methodTabInstance, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var chatsList = (List) param.getResult();
                var resultList = filterChat(param.thisObject, chatsList);
                param.setResult(resultList);

                // Inject again when the fragment list is already alive
                try {
                    Object activityTarget = WppCore.getCurrentActivity();
                    if (activityTarget == null) {
                        activityTarget = XposedHelpers.callMethod(param.thisObject, "getActivity");
                    }
                    if (activityTarget != null) {
                        injectGroupsTab(activityTarget, null);
                    }
                } catch (Throwable throwable) {
                    XposedBridge.log("SeparateGroup: methodTabInstance inject failed: " + throwable);
                }
            }
        });

        var fabintMethod = Unobfuscator.loadFabMethod(classLoader);
        logDebug(Unobfuscator.getMethodDescriptor(fabintMethod));

        XposedBridge.hookMethod(fabintMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (Objects.equals(tabInstances.get(GROUPS), param.thisObject)
                        || (statusFallbackMode && Objects.equals(tabInstances.get(fallbackGroupTabId), param.thisObject))
                        || Objects.equals(tabInstances.get(STATUS), param.thisObject)) {
                    param.setResult(GROUPS);
                }
            }
        });

        var publishResultsMethod = Unobfuscator.loadGetFiltersMethod(classLoader);
        logDebug(Unobfuscator.getMethodDescriptor(publishResultsMethod));

        XposedBridge.hookMethod(publishResultsMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                var filters = param.args[1];
                var chatsList = (List) XposedHelpers.getObjectField(filters, "values");
                var baseField = ReflectionUtils.getFieldByExtendType(publishResultsMethod.getDeclaringClass(), BaseAdapter.class);
                if (baseField == null) return;
                var convField = ReflectionUtils.getFieldByType(baseField.getType(), cFrag);
                Object thiz = convField.get(baseField.get(param.thisObject));
                if (thiz == null) return;
                var resultList = filterChat(thiz, chatsList);
                XposedHelpers.setObjectField(filters, "values", resultList);
                XposedHelpers.setIntField(filters, "count", resultList.size());

                try {
                    Object activityTarget = WppCore.getCurrentActivity();
                    if (activityTarget == null) {
                        activityTarget = XposedHelpers.callMethod(thiz, "getActivity");
                    }
                    if (activityTarget != null) {
                        injectGroupsTab(activityTarget, null);
                    }
                } catch (Throwable throwable) {
                    XposedBridge.log("SeparateGroup: publishResults inject failed: " + throwable);
                }
            }
        });
    }

    private List filterChat(Object convFragment, List chatsList) {
        try {
            var resolvedTabId = resolveChatTabId();
            if (convFragment instanceof java.util.LinkedHashSet) {
                XposedBridge.log("SeparateGroup: filterChat skipping LinkedHashSet");
                return chatsList;
            }
            if (tabInstances.isEmpty()) {
                XposedBridge.log("SeparateGroup: filterChat - tabInstances empty, returning all chats");
                return chatsList;
            }

            XposedBridge.log("SeparateGroup: filterChat called with fragment=" + convFragment.getClass().getSimpleName() + ", resolvedTabId=" + resolvedTabId + ", tabInstances=" + tabInstances.keySet());

            if (tabInstances.containsKey(GROUPS) && Objects.equals(tabInstances.get(GROUPS), convFragment)) {
                XposedBridge.log("SeparateGroup: Filtering for GROUPS tab");
                return filterChatByGroup(chatsList, true);
            }
            if (Objects.equals(tabInstances.get(resolvedTabId), convFragment) || Objects.equals(tabInstances.get(CHATS), convFragment)) {
                XposedBridge.log("SeparateGroup: Filtering for CHATS tab (resolvedTabId=" + resolvedTabId + ")");
                return filterChatByGroup(chatsList, false);
            }
            if (statusFallbackMode && tabInstances.containsKey(fallbackGroupTabId) && Objects.equals(tabInstances.get(fallbackGroupTabId), convFragment)) {
                XposedBridge.log("SeparateGroup: Filtering for fallbackGroupTabId=" + fallbackGroupTabId);
                return filterChatByGroup(chatsList, true);
            }
            
            XposedBridge.log("SeparateGroup: filterChat - no matching tabInstance found, returning all chats");
        } catch (Throwable ignored) {
            XposedBridge.log("SeparateGroup: filterChat exception: " + ignored);
        }
        return chatsList;
    }

    private List filterChatByGroup(List chats, boolean isGroups) {
        try {
            var result = new ArrayList();
            synchronized (SeparateGroup.class) {
                var db = MessageStore.getInstance().getDatabase();
                for (Object chat : chats) {
                    try {
                        // Get jid from chat object
                        var jidObj = ReflectionUtils.getObjectField(
                            ReflectionUtils.getFieldByType(chat.getClass(), "com.whatsapp.jid.Jid"),
                            chat
                        );
                        if (jidObj == null) continue;

                        // Get raw string from jid
                        var jidStr = jidObj.toString();
                        
                        var sql = "SELECT * FROM jid WHERE raw_string = ?";
                        var cursor = db.rawQuery(sql, new String[]{jidStr});
                        if (!cursor.moveToFirst()) {
                            cursor.close();
                            continue;
                        }

                        var server = cursor.getString(cursor.getColumnIndex("server"));
                        var isGroup = server.equals("g.us");
                        cursor.close();

                        if ((isGroups && isGroup) || (!isGroups && !isGroup)) {
                            result.add(chat);
                        }
                    } catch (Throwable e) {
                        // Skip this chat on error
                        continue;
                    }
                }
            }
            return result;
        } catch (Throwable throwable) {
            XposedBridge.log("SeparateGroup: filterChatByGroup error: " + throwable);
            return chats;
        }
    }

    private void injectGroupsTab(@NonNull Object homeInstance, java.lang.reflect.Field preferredField) {
        if (!featureEnabled) return;

        Object listOwner = homeInstance;
        java.lang.reflect.Field listField = preferredField;
        if (listField == null) {
            var listRef = findTabListRef(homeInstance);
            if (listRef != null) {
                listOwner = listRef.owner;
                listField = listRef.field;
            }
        }

        if (listField == null || listOwner == null) {
            if (!loggedTabListSearchShape) {
                loggedTabListSearchShape = true;
                XposedBridge.log("SeparateGroup: Tab-list search failed for class " + homeInstance.getClass().getName());
            }
            return;
        }

        try {
            var listObj = listField.get(listOwner);
            if (!(listObj instanceof List<?> rawList)) {
                XposedBridge.log("SeparateGroup: Tab list field is null or not a List");
                return;
            }

            XposedBridge.log("SeparateGroup: Found tab list with " + rawList.size() + " items");

            boolean allNumeric = true;
            for (Object item : rawList) {
                if (!(item instanceof Integer) && !(item instanceof Number)) {
                    allNumeric = false;
                    break;
                }
            }
            if (!rawList.isEmpty() && !allNumeric) {
                XposedBridge.log("SeparateGroup: Tabs are object-backed (not numeric)");
                enableStatusFallback(rawList, "field object-backed list");
                return;
            }

            ArrayList<Integer> mutableTabs = new ArrayList<>();
            for (Object item : rawList) {
                Integer parsed = extractTabId(item);
                if (parsed != null) {
                    mutableTabs.add(parsed);
                }
            }

            tabs = mutableTabs;
            statusFallbackMode = false;
            var resolvedChatTabId = resolveChatTabId();
            XposedBridge.log("SeparateGroup: Current tabs before injection: " + tabs + " (chatTabId=" + resolvedChatTabId + ")");

            if (!tabs.isEmpty() && tabs.contains(resolvedChatTabId) && !tabs.contains(GROUPS)) {
                tabs.add(tabs.indexOf(resolvedChatTabId) + 1, GROUPS);
                XposedBridge.log("SeparateGroup: Injected GROUPS tab. Current tabs: " + tabs);
            } else if (!tabs.isEmpty() && !tabs.contains(GROUPS)) {
                tabs.add(1 <= tabs.size() ? 1 : 0, GROUPS);
                XposedBridge.log("SeparateGroup: Injected GROUPS tab at fallback position. Current tabs: " + tabs);
            } else if (tabs.isEmpty()) {
                XposedBridge.log("SeparateGroup: Skipping injection (list is empty)");
            } else if (!tabs.contains(resolvedChatTabId)) {
                XposedBridge.log("SeparateGroup: Skipping injection (resolved chat tab not found). Current tabs: " + tabs);
            } else {
                XposedBridge.log("SeparateGroup: Skipping injection (GROUPS tab already present). Current tabs: " + tabs);
            }
        } catch (Throwable throwable) {
            XposedBridge.log("SeparateGroup: Injection failed: " + throwable);
            throwable.printStackTrace();
        }
    }

    private void enableStatusFallback(List rawList, String reason) {
        statusFallbackMode = true;
        fallbackGroupTabId = STATUS;
        XposedBridge.log("SeparateGroup: Enabling status fallback mode due to: " + reason);
        
        ArrayList<Object> mutableTabs = new ArrayList<>(rawList);
        if (!mutableTabs.contains(GROUPS)) {
            mutableTabs.add(1, GROUPS);
        }
        
        // Only update if we can extract at least one numeric ID
        for (Object item : mutableTabs) {
            if (extractTabId(item) != null) {
                tabs = new ArrayList<>();
                for (Object obj : mutableTabs) {
                    Integer id = extractTabId(obj);
                    if (id != null) {
                        tabs.add(id);
                    }
                }
                break;
            }
        }
    }

    private int resolveChatTabId() {
        if (tabs == null || tabs.isEmpty()) {
            return chatTabId;
        }
        if (tabs.contains(chatTabId)) {
            return chatTabId;
        }
        if (tabs.contains(CHATS)) {
            chatTabId = CHATS;
            return chatTabId;
        }
        for (Integer id : tabs) {
            if (id != null && id != GROUPS && id != STATUS) {
                chatTabId = id;
                return chatTabId;
            }
        }
        chatTabId = tabs.get(0);
        return chatTabId;
    }

    private TabListRef findTabListRef(@NonNull Object root) {
        TabListRef best = findTabListRefOnObject(root, false);
        long bestRank = rankTabListRef(best);

        TabListRef emptyFallback = null;
        Class<?> cursor = root.getClass();
        while (cursor != null && cursor != Object.class) {
            for (var field : cursor.getDeclaredFields()) {
                field.setAccessible(true);
                try {
                    var nested = field.get(root);
                    if (nested == null) continue;
                    if (nested == root) continue;
                    var nestedRef = findTabListRefOnObject(nested, false);
                    long nestedRank = rankTabListRef(nestedRef);
                    if (nestedRank > bestRank) {
                        best = nestedRef;
                        bestRank = nestedRank;
                    }
                    if (emptyFallback == null) {
                        emptyFallback = findTabListRefOnObject(nested, true);
                    }
                } catch (Throwable ignored) {
                }
            }
            cursor = cursor.getSuperclass();
        }

        if (best != null) {
            return best;
        }
        return emptyFallback;
    }

    private TabListRef findTabListRefOnObject(@NonNull Object owner, boolean allowEmptyFallback) {
        Class<?> cursor = owner.getClass();
        TabListRef bestNumericRef = null;
        int bestNumericCount = -1;
        TabListRef bestNonEmptyRef = null;
        int bestNonEmptySize = -1;
        TabListRef emptyFallback = null;
        
        while (cursor != null && cursor != Object.class) {
            for (var field : cursor.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                try {
                    var value = field.get(owner);
                    if (!(value instanceof List<?> raw)) continue;
                    if (raw.isEmpty()) {
                        if (emptyFallback == null) {
                            emptyFallback = new TabListRef(owner, field);
                        }
                        continue;
                    }

                    int numericCount = 0;
                    for (Object item : raw) {
                        if (extractTabId(item) != null) {
                            numericCount++;
                        }
                    }

                    if (numericCount > bestNumericCount) {
                        bestNumericCount = numericCount;
                        bestNumericRef = new TabListRef(owner, field);
                    }
                    if (raw.size() > bestNonEmptySize) {
                        bestNonEmptySize = raw.size();
                        bestNonEmptyRef = new TabListRef(owner, field);
                    }
                } catch (Throwable ignored) {
                }
            }
            cursor = cursor.getSuperclass();
        }

        if (bestNumericRef != null && bestNumericCount >= 2) {
            return bestNumericRef;
        }
        if (bestNonEmptyRef != null) {
            return bestNonEmptyRef;
        }
        return allowEmptyFallback ? emptyFallback : null;
    }

    private long rankTabListRef(TabListRef ref) {
        if (ref == null) return 0;
        try {
            var value = ref.field.get(ref.owner);
            if (!(value instanceof List<?> list)) return 0;
            if (list.isEmpty()) return 1;
            
            long score = list.size() * 100;
            int numericCount = 0;
            for (Object item : list) {
                if (extractTabId(item) != null) {
                    numericCount++;
                }
            }
            score += numericCount * 1000;
            return score;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private Integer extractTabId(Object item) {
        if (item == null) return null;
        if (item instanceof Integer tabId) return tabId;
        if (item instanceof Number number) return number.intValue();

        try {
            var intMethod = XposedHelpers.findMethodExactIfExists(item.getClass(), "intValue");
            if (intMethod != null) {
                Object value = intMethod.invoke(item);
                if (value instanceof Number number) return number.intValue();
            }
        } catch (Throwable ignored) {
        }

        Class<?> cursor = item.getClass();
        while (cursor != null && cursor != Object.class) {
            for (var field : cursor.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Class<?> type = field.getType();
                    if (type == int.class) {
                        int v = field.getInt(item);
                        if (v > 0 && v < 5000) return v;
                    } else if (Number.class.isAssignableFrom(type)) {
                        Object value = field.get(item);
                        if (value instanceof Number number) {
                            int v = number.intValue();
                            if (v > 0 && v < 5000) return v;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            cursor = cursor.getSuperclass();
        }

        return null;
    }

    private boolean isGroupsTabId(int tabId) {
        return tabId == GROUPS || tabId == 500;
    }

    private static final class TabListRef {
        final Object owner;
        final java.lang.reflect.Field field;

        TabListRef(Object owner, java.lang.reflect.Field field) {
            this.owner = owner;
            this.field = field;
        }
    }
}
