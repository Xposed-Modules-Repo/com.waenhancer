package com.waenhancer.preference;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.waenhancer.R;
import com.google.android.material.slider.Slider;

public class FloatSeekBarPreference extends Preference {

    private float minValue;
    private float maxValue;
    private float valueSpacing;
    private String format;

    private Slider slider;
    private TextView textView;

    private float defaultValue = 0F;
    private float newValue = 0F;

    public FloatSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs, defStyleAttr, defStyleRes);
    }

    public FloatSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public FloatSeekBarPreference(Context context, AttributeSet attrs) {
        this(context, attrs, androidx.preference.R.attr.seekBarPreferenceStyle);
    }

    public FloatSeekBarPreference(Context context) {
        this(context, null);
    }

    @Override
    protected Object onGetDefaultValue(TypedArray ta, int index) {
        defaultValue = ta.getFloat(index, 0F);
        return defaultValue;
    }

    @Override
    protected void onSetInitialValue(Object defaultValue) {
        float def = 0;
        if (defaultValue instanceof Float) {
            def = (Float) defaultValue;
        } else if (defaultValue instanceof String) {
            try {
                def = Float.parseFloat((String) defaultValue);
            } catch (Exception ignored) {}
        } else {
            def = this.defaultValue;
        }

        try {
            newValue = getPersistedFloat(def);
        } catch (Exception e) {
            // If it fails (e.g. ClassCastException), try to get it as an int and convert
            try {
                newValue = (float) getPersistedInt((int) def);
                // Also persist it as float for next time
                persistFloat(newValue);
            } catch (Exception e2) {
                newValue = def;
            }
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        holder.itemView.setClickable(false);
        slider = (Slider) holder.findViewById(R.id.seekbar);
        textView = (TextView) holder.findViewById(R.id.seekbar_value);

        slider.setValueFrom(minValue);
        slider.setValueTo(maxValue);
        slider.setStepSize(valueSpacing);
        slider.setValue(newValue);
        slider.setEnabled(isEnabled());

        slider.addOnChangeListener((slider, value, fromUser) -> {
            textView.setText(String.format(format, value));
        });

        slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider slider) {}

            @Override
            public void onStopTrackingTouch(Slider slider) {
                persistFloat(slider.getValue());
            }
        });

        textView.setText(String.format(format, newValue));
    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        setWidgetLayoutResource(R.layout.pref_float_seekbar);

        TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.FloatSeekBarPreference, defStyleAttr, defStyleRes);
        minValue = ta.getFloat(R.styleable.FloatSeekBarPreference_minValue, 0F);
        maxValue = ta.getFloat(R.styleable.FloatSeekBarPreference_maxValue, 1F);
        valueSpacing = ta.getFloat(R.styleable.FloatSeekBarPreference_valueSpacing, .1F);
        format = ta.getString(R.styleable.FloatSeekBarPreference_format);
        if (format == null) {
            format = "%3.1f";
        }
        ta.recycle();
    }

    public float getValue() {
        return (slider != null) ? slider.getValue() : 0F;
    }

    public void setValue(float value) {
        newValue = value;
        persistFloat(value);
        notifyChanged();
    }
}