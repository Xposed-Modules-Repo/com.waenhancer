package com.waenhancer.activities;

import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.waenhancer.R;
import com.waenhancer.activities.base.BaseActivity;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.List;

public class CallRecordingSettingsActivity extends BaseActivity {

    private static final String TAG = "WaEnhancer";
    private SharedPreferences prefs;

    // UI Elements
    private RadioGroup radioGroupMethod;
    private RadioButton rbStandard, rbAlsa, rbCaf, rbMsm;
    private LinearLayout audioSourceContainer, deviceTypeContainer;
    private TextView tvAudioSource, tvDeviceType;

    // Data lists
    private String[] allAudioSourceEntries;
    private String[] allAudioSourceValues;
    private List<String> standardAudioEntries = new ArrayList<>();
    private List<String> standardAudioValues = new ArrayList<>();

    private List<String> deviceIdentifiers = new ArrayList<>();
    private List<String> deviceDisplayNames = new ArrayList<>();
    private java.util.Map<String, String> deviceAlsaNodes = new java.util.HashMap<>();
    private java.util.Map<String, String> deviceCafNodes = new java.util.HashMap<>();

    private int selectedAudioSourceIndex = 0;
    private int selectedDeviceTypeIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call_recording_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.call_recording_settings);
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        radioGroupMethod = findViewById(R.id.radio_group_method);
        rbStandard = findViewById(R.id.rb_standard);
        rbAlsa = findViewById(R.id.rb_alsa);
        rbCaf = findViewById(R.id.rb_caf);
        rbMsm = findViewById(R.id.rb_msm);

        audioSourceContainer = findViewById(R.id.audio_source_container);
        deviceTypeContainer = findViewById(R.id.device_type_container);

        tvAudioSource = findViewById(R.id.tv_audio_source);
        tvDeviceType = findViewById(R.id.tv_device_type);

        allAudioSourceEntries = getResources().getStringArray(R.array.audio_sources_entries);
        allAudioSourceValues = getResources().getStringArray(R.array.audio_sources_values);

        for (int i = 0; i < allAudioSourceValues.length; i++) {
            int val = Integer.parseInt(allAudioSourceValues[i]);
            if (val < 100) {
                standardAudioEntries.add(allAudioSourceEntries[i]);
                standardAudioValues.add(allAudioSourceValues[i]);
            }
        }

        loadDeviceXml();
        setupUI();
    }

    private void loadDeviceXml() {
        try {
            XmlResourceParser parser = getResources().getXml(R.xml.devices);
            int eventType = parser.getEventType();
            String currentAlsaDevice = "hw:0,0";
            String currentCafDevice = "hw:0,0";

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    if (parser.getName().equals("AlsaConfig")) {
                        currentAlsaDevice = parser.getAttributeValue(null, "device");
                    } else if (parser.getName().equals("CafConfig")) {
                        currentCafDevice = parser.getAttributeValue(null, "device");
                    } else if (parser.getName().equals("Device")) {
                        String id = parser.getAttributeValue(null, "device");
                        String desc = parser.getAttributeValue(null, "title");
                        if (id != null && desc != null) {
                            deviceIdentifiers.add(id);
                            deviceDisplayNames.add(desc + " (" + id + ")");
                            deviceAlsaNodes.put(id, currentAlsaDevice != null ? currentAlsaDevice : "hw:0,0");
                            deviceCafNodes.put(id, currentCafDevice != null ? currentCafDevice : "hw:0,0");
                        }
                    }
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing devices.xml", e);
        }
    }

    private void setupUI() {
        int savedSourceValue = prefs.getInt("call_recording_audio_source", 0);
        String savedDeviceId = prefs.getString("call_recording_device_id", "default");

        if ("default".equals(savedDeviceId) && !deviceIdentifiers.isEmpty()) {
            String hw = android.os.Build.HARDWARE.toLowerCase();
            String board = android.os.Build.BOARD.toLowerCase();
            String device = android.os.Build.DEVICE.toLowerCase();
            String model = android.os.Build.MODEL.toLowerCase();

            boolean matched = false;
            for (String id : deviceIdentifiers) {
                String lid = id.toLowerCase();
                if (hw.contains(lid) || board.contains(lid) || device.contains(lid) || model.contains(lid)) {
                    savedDeviceId = id;
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                if (hw.contains("mt") || board.contains("mt")) { // MediaTek
                    if (deviceIdentifiers.contains("mtk"))
                        savedDeviceId = "mtk";
                } else if (hw.contains("msm") || hw.contains("sdm") || hw.contains("sm8")) { // Qualcomm Snapdragon
                    if (deviceIdentifiers.contains("msm89xx_10"))
                        savedDeviceId = "msm89xx_10";
                    else if (deviceIdentifiers.contains("msm89xx"))
                        savedDeviceId = "msm89xx";
                } else if (hw.contains("exynos") || hw.contains("universal")) { // Samsung Exynos
                    if (deviceIdentifiers.contains("exynos9"))
                        savedDeviceId = "exynos9";
                }
            }

            if ("default".equals(savedDeviceId))
                savedDeviceId = deviceIdentifiers.get(0);
        }

        // Restore active selections
        selectedDeviceTypeIndex = deviceIdentifiers.indexOf(savedDeviceId);
        if (selectedDeviceTypeIndex < 0)
            selectedDeviceTypeIndex = 0;
        tvDeviceType.setText(deviceDisplayNames.isEmpty() ? "None" : deviceDisplayNames.get(selectedDeviceTypeIndex));

        if (savedSourceValue == 100) {
            rbCaf.setChecked(true);
            toggleContainers(true);
        } else if (savedSourceValue == 101) {
            rbAlsa.setChecked(true);
            toggleContainers(true);
        } else if (savedSourceValue == 102) {
            rbMsm.setChecked(true);
            toggleContainers(true);
        } else {
            rbStandard.setChecked(true);
            toggleContainers(false);
            selectedAudioSourceIndex = standardAudioValues.indexOf(String.valueOf(savedSourceValue));
            if (selectedAudioSourceIndex < 0)
                selectedAudioSourceIndex = 0;
            tvAudioSource.setText(
                    standardAudioEntries.isEmpty() ? "None" : standardAudioEntries.get(selectedAudioSourceIndex));
        }

        radioGroupMethod.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_standard) {
                toggleContainers(false);
            } else {
                toggleContainers(true);
                checkRootAccess();
            }
        });

        tvAudioSource.setOnClickListener(
                v -> showBottomSheet("Select Audio Source", standardAudioEntries, selectedAudioSourceIndex, false,
                        index -> {
                            selectedAudioSourceIndex = index;
                            tvAudioSource.setText(standardAudioEntries.get(index));
                        }));

        tvDeviceType.setOnClickListener(
                v -> showBottomSheet("Select Device Type", deviceDisplayNames, selectedDeviceTypeIndex, true, index -> {
                    selectedDeviceTypeIndex = index;
                    tvDeviceType.setText(deviceDisplayNames.get(index));
                }));
    }

    private interface BottomSheetCallback {
        void onItemSelected(int position);
    }

    private void showBottomSheet(String title, List<String> items, int selectedIndex, boolean showSearch,
            BottomSheetCallback callback) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.bottom_sheet_radio_list, null);

        TextView tvTitle = view.findViewById(R.id.bs_title);
        tvTitle.setText(title);

        RadioGroup radioGroup = view.findViewById(R.id.bs_radio_group);
        android.widget.EditText etSearch = view.findViewById(R.id.bs_search);

        for (int i = 0; i < items.size(); i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(items.get(i));
            rb.setId(View.generateViewId());
            rb.setPadding(0, 32, 0, 32);
            radioGroup.addView(rb);
            if (i == selectedIndex) {
                radioGroup.check(rb.getId());
            }
        }

        if (showSearch) {
            etSearch.setVisibility(View.VISIBLE);
            etSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String query = s.toString().toLowerCase();
                    for (int i = 0; i < radioGroup.getChildCount(); i++) {
                        View child = radioGroup.getChildAt(i);
                        if (child instanceof RadioButton) {
                            if (((RadioButton) child).getText().toString().toLowerCase().contains(query)) {
                                child.setVisibility(View.VISIBLE);
                            } else {
                                child.setVisibility(View.GONE);
                            }
                        }
                    }
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {
                }
            });
        }

        radioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int index = group.indexOfChild(view.findViewById(checkedId));
            callback.onItemSelected(index);
            dialog.dismiss();
        });

        dialog.setContentView(view);
        dialog.show();
    }

    private void toggleContainers(boolean isRoot) {
        if (isRoot) {
            audioSourceContainer.setVisibility(View.GONE);
            deviceTypeContainer.setVisibility(View.VISIBLE);
        } else {
            audioSourceContainer.setVisibility(View.VISIBLE);
            deviceTypeContainer.setVisibility(View.GONE);
        }
    }

    private void checkRootAccess() {
        new Thread(() -> {
            boolean hasRoot = false;
            try {
                Process process = Runtime.getRuntime().exec("su -c id");
                int exitCode = process.waitFor();
                hasRoot = (exitCode == 0);
            } catch (Exception e) {
                Log.e(TAG, "Root check exception: " + e.getMessage());
            }

            final boolean rootGranted = hasRoot;
            runOnUiThread(() -> {
                if (!rootGranted) {
                    Toast.makeText(this, R.string.root_access_denied, Toast.LENGTH_LONG).show();
                    rbStandard.setChecked(true); // Revert to standard API
                } else {
                    Toast.makeText(this, R.string.root_access_granted, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = prefs.edit();

        int selectedMethodId = radioGroupMethod.getCheckedRadioButtonId();
        if (selectedMethodId == R.id.rb_standard) {
            if (selectedAudioSourceIndex >= 0 && selectedAudioSourceIndex < standardAudioValues.size()) {
                int audioSourceValue = Integer.parseInt(standardAudioValues.get(selectedAudioSourceIndex));
                editor.putInt("call_recording_audio_source", audioSourceValue);
            }
            editor.putBoolean("call_recording_use_root", false);
        } else {
            int rootValue = 101; // default to ALSA
            if (selectedMethodId == R.id.rb_caf)
                rootValue = 100;
            else if (selectedMethodId == R.id.rb_msm)
                rootValue = 102;

            editor.putInt("call_recording_audio_source", rootValue);
            editor.putBoolean("call_recording_use_root", true);

            if (selectedDeviceTypeIndex >= 0 && selectedDeviceTypeIndex < deviceIdentifiers.size()) {
                String devId = deviceIdentifiers.get(selectedDeviceTypeIndex);
                editor.putString("call_recording_device_id", devId);
                editor.putString("call_recording_alsa_node", deviceAlsaNodes.get(devId));
                editor.putString("call_recording_caf_node", deviceCafNodes.get(devId));
            }
        }

        editor.apply();
        Toast.makeText(this, "Settings Saved!", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_call_recording, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == R.id.action_save) {
            saveSettings();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
