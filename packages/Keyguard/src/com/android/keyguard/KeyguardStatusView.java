/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.keyguard;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.graphics.Typeface;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.TextView;

import com.android.internal.widget.LockPatternUtils;

import java.util.Arrays;
import java.util.Locale;

public class KeyguardStatusView extends GridLayout implements
        OmniJawsClient.OmniJawsObserver {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusView";

    private final LockPatternUtils mLockPatternUtils;
    private final AlarmManager mAlarmManager;

    private TextView mAlarmStatusView;
    private TextClock mDateView;
    private TextClock mClockView;
    private TextView mOwnerInfo;
    private View mWeatherView;
    private TextView mWeatherCity;
    private ImageView mWeatherConditionImage;
    private Drawable mWeatherConditionDrawable;
    private TextView mWeatherCurrentTemp;
    private TextView mWeatherConditionText;
    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherData;
    private boolean mWeatherEnabled;

    private boolean mShowWeather;
    private boolean mShowConditionIcon;
    private boolean mShowLocation;
    private int mIconNameValue = -1;

    private SettingsObserver mSettingsObserver;

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onTimeChanged() {
            refresh();
            refreshClockColors();
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                if (DEBUG) Slog.v(TAG, "refresh statusview showing:" + showing);
                refresh();
                updateOwnerInfo();
                refreshClockColors();
            }
        }

        @Override
        public void onStartedWakingUp() {
            setEnableMarquee(true);
            refreshClockColors();
        }

        @Override
        public void onFinishedGoingToSleep(int why) {
            setEnableMarquee(false);
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            refresh();
            updateOwnerInfo();
            refreshClockColors();
        }
    };

    public KeyguardStatusView(Context context) {
        this(context, null, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mLockPatternUtils = new LockPatternUtils(getContext());
        mWeatherClient = new OmniJawsClient(mContext);
        mWeatherEnabled = mWeatherClient.isOmniJawsEnabled();
        refreshClockColors();
    }

    private void setEnableMarquee(boolean enabled) {
        if (DEBUG) Log.v(TAG, (enabled ? "Enable" : "Disable") + " transport text marquee");
        if (mAlarmStatusView != null) mAlarmStatusView.setSelected(enabled);
        if (mOwnerInfo != null) mOwnerInfo.setSelected(enabled);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mAlarmStatusView = (TextView) findViewById(R.id.alarm_status);
        mDateView = (TextClock) findViewById(R.id.date_view);
        mClockView = (TextClock) findViewById(R.id.clock_view);
        mDateView.setShowCurrentUserTime(true);
        mClockView.setShowCurrentUserTime(true);
        mOwnerInfo = (TextView) findViewById(R.id.owner_info);
        mWeatherView = findViewById(R.id.keyguard_weather_view);
        mWeatherCity = (TextView) findViewById(R.id.city);
        mWeatherConditionImage = (ImageView) findViewById(R.id.weather_image);
        mWeatherCurrentTemp = (TextView) findViewById(R.id.current_temp);
        mWeatherConditionText = (TextView) findViewById(R.id.condition);

        boolean shouldMarquee = KeyguardUpdateMonitor.getInstance(mContext).isDeviceInteractive();
        setEnableMarquee(shouldMarquee);
        refresh();
        refreshClockColors();
        updateOwnerInfo();

        // Disable elegant text height because our fancy colon makes the ymin value huge for no
        // reason.
        mClockView.setElegantTextHeight(false);
        mSettingsObserver = new SettingsObserver(new Handler());
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_big_font_size));
        // Some layouts like burmese have a different margin for the clock
        MarginLayoutParams layoutParams = (MarginLayoutParams) mClockView.getLayoutParams();
        layoutParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.bottom_text_spacing_digital);
        mClockView.setLayoutParams(layoutParams);
        mClockView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); 
        mDateView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
        mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
    }

    private int getLockClockFont() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCK_CLOCK_FONTS, 0);
    }

    private int getLockClockColor() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_CLOCK_COLOR, 0xFFFFFFFF);
    }

    private int getLockClockDateColor() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_CLOCK_DATE_COLOR, 0xFFFFFFFF);
    }

    private int getLockClockOwnerColor() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_OWNER_INFO_COLOR, 0xFFFFFFFF);
    }

    private int getLockClockAlarmColor() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_ALARM_COLOR, 0xFFFFFFFF);
    }

    public void refreshTime() {
        mDateView.setFormat24Hour(Patterns.dateView);
        mDateView.setFormat12Hour(Patterns.dateView);

        mClockView.setFormat12Hour(Patterns.clockView12);
        mClockView.setFormat24Hour(Patterns.clockView24);
    }

    private void refresh() {
        AlarmManager.AlarmClockInfo nextAlarm =
                mAlarmManager.getNextAlarmClock(UserHandle.USER_CURRENT);
        Patterns.update(mContext, nextAlarm != null);

        refreshTime();
        refreshAlarmStatus(nextAlarm);
	refreshLockFont();
	refreshClockColors();
        updateSettings();
    }

    void refreshAlarmStatus(AlarmManager.AlarmClockInfo nextAlarm) {
        if (nextAlarm != null) {
            String alarm = formatNextAlarm(mContext, nextAlarm);
            mAlarmStatusView.setText(alarm);
            mAlarmStatusView.setContentDescription(
                    getResources().getString(R.string.keyguard_accessibility_next_alarm, alarm));
            mAlarmStatusView.setVisibility(View.VISIBLE);
        } else {
            mAlarmStatusView.setVisibility(View.GONE);
        }
    }

    public static String formatNextAlarm(Context context, AlarmManager.AlarmClockInfo info) {
        if (info == null) {
            return "";
        }
        String skeleton = DateFormat.is24HourFormat(context, ActivityManager.getCurrentUser())
                ? "EHm"
                : "Ehma";
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return DateFormat.format(pattern, info.getTriggerTime()).toString();
    }

    private void updateOwnerInfo() {
        if (mOwnerInfo == null) return;
        String ownerInfo = getOwnerInfo();
        if (!TextUtils.isEmpty(ownerInfo)) {
            mOwnerInfo.setVisibility(View.VISIBLE);
            mOwnerInfo.setText(ownerInfo);
        } else {
            mOwnerInfo.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mInfoCallback);
        mWeatherEnabled = mWeatherClient.isOmniJawsEnabled();
        mWeatherClient.addObserver(this);
        mSettingsObserver.observe();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mInfoCallback);
        mSettingsObserver.unobserve();
        mWeatherClient.removeObserver(this);
        mWeatherClient.cleanupObserver();
    }

    private String getOwnerInfo() {
        String info = null;
        if (mLockPatternUtils.isDeviceOwnerInfoEnabled()) {
            // Use the device owner information set by device policy client via
            // device policy manager.
            info = mLockPatternUtils.getDeviceOwnerInfo();
        } else {
            // Use the current user owner information if enabled.
            final boolean ownerInfoEnabled = mLockPatternUtils.isOwnerInfoEnabled(
                    KeyguardUpdateMonitor.getCurrentUser());
            if (ownerInfoEnabled) {
                info = mLockPatternUtils.getOwnerInfo(KeyguardUpdateMonitor.getCurrentUser());
            }
        }
        return info;
    }

    @Override
    public void weatherUpdated() {
        queryAndUpdateWeather();
    }

    public void queryAndUpdateWeather() {
        try {
            mWeatherEnabled = mWeatherClient.isOmniJawsEnabled();

            if (mWeatherEnabled && mShowWeather) {
                mWeatherClient.queryWeather();
                mWeatherData = mWeatherClient.getWeatherInfo();
                mWeatherCity.setText(mWeatherData.city);
                mWeatherConditionImage.setImageDrawable(
                    mWeatherClient.getWeatherConditionImage(mWeatherData.conditionCode));
                mWeatherCurrentTemp.setText(mWeatherData.temp + mWeatherData.tempUnits);
                mWeatherConditionText.setText(mWeatherData.condition);
            }
            updateSettings();
        } catch(Exception e) {
            // Do nothing
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private void refreshLockFont() {
        final Resources res = getContext().getResources();
        boolean isPrimary = UserHandle.getCallingUserId() == UserHandle.USER_OWNER;
        int lockClockFont = isPrimary ? getLockClockFont() : 0;

        if (lockClockFont == 0) {
            mClockView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        if (lockClockFont == 1) {
            mClockView.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
        }
        if (lockClockFont == 2) {
            mClockView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        }
        if (lockClockFont == 3) {
            mClockView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
        }
        if (lockClockFont == 4) {
            mClockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        }
        if (lockClockFont == 5) {
            mClockView.setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
        }
        if (lockClockFont == 6) {
            mClockView.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        }
        if (lockClockFont == 7) {
            mClockView.setTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
        }
        if (lockClockFont == 8) {
            mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        }
        if (lockClockFont == 9) {
            mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
        }
        if (lockClockFont == 10) {
            mClockView.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
        }
        if (lockClockFont == 11) {
            mClockView.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.ITALIC));
        }
        if (lockClockFont == 12) {
            mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        }
        if (lockClockFont == 13) {
            mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        }
        if (lockClockFont == 14) {
            mClockView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        if (lockClockFont == 15) {
            mClockView.setTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
        }
        if (lockClockFont == 16) {
            mClockView.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        }
        if (lockClockFont == 17) {
            mClockView.setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
        }
        if (lockClockFont == 18) {
            mClockView.setTypeface(Typeface.create("cursive", Typeface.NORMAL));
        }
        if (lockClockFont == 19) {
            mClockView.setTypeface(Typeface.create("cursive", Typeface.BOLD));
        }
        if (lockClockFont == 20) {
            mClockView.setTypeface(Typeface.create("casual", Typeface.NORMAL));
        }
        if (lockClockFont == 21) {
            mClockView.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        }
        if (lockClockFont == 22) {
            mClockView.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        }
        if (lockClockFont == 23) {
            mClockView.setTypeface(Typeface.create("serif", Typeface.BOLD));
        }
        if (lockClockFont == 24) {
            mClockView.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
        }
    }

    private void refreshClockColors() {
        final Resources res = getContext().getResources();
        boolean isPrimary = UserHandle.getCallingUserId() == UserHandle.USER_OWNER;
        int clockColor = isPrimary ? getLockClockColor() : 0xFFFFFFFF;
        int clockDateColor = isPrimary ? getLockClockDateColor() : 0xFFFFFFFF;
        int ownerInfoColor = isPrimary ? getLockClockOwnerColor() : 0xFFFFFFFF;
        int alarmColor = isPrimary ? getLockClockAlarmColor() : 0xFFFFFFFF;

        if (mClockView != null) {
            mClockView.setTextColor(clockColor);
        }
        if (mDateView != null) {
            mDateView.setTextColor(clockDateColor);
        }
        if (mOwnerInfo != null) {
            mOwnerInfo.setTextColor(ownerInfoColor);
        }
        if (mAlarmStatusView != null) {
            mAlarmStatusView.setTextColor(alarmColor);
        }
    }

	private void updateSettings() {
        final ContentResolver resolver = getContext().getContentResolver();
        final Resources res = getContext().getResources();
        AlarmManager.AlarmClockInfo nextAlarm =
                mAlarmManager.getNextAlarmClock(UserHandle.USER_CURRENT); 
        boolean showAlarm = Settings.System.getIntForUser(resolver,
                Settings.System.HIDE_LOCKSCREEN_ALARM, 1, UserHandle.USER_CURRENT) == 1;
        boolean showClock = Settings.System.getIntForUser(resolver,
                Settings.System.HIDE_LOCKSCREEN_CLOCK, 1, UserHandle.USER_CURRENT) == 1;
        boolean showDate = Settings.System.getIntForUser(resolver,
                Settings.System.HIDE_LOCKSCREEN_DATE, 1, UserHandle.USER_CURRENT) == 1;
        boolean showWeather = mShowWeather && mWeatherEnabled;

        View weatherPanel = findViewById(R.id.weather_panel);
        TextView noWeatherInfo = (TextView) findViewById(R.id.no_weather_info_text);
        int primaryTextColor =
                res.getColor(R.color.keyguard_default_primary_text_color);
        // primaryTextColor with a transparency of 70%
        int secondaryTextColor = (179 << 24) | (primaryTextColor & 0x00ffffff);
        int defaultIconColor =
                res.getColor(R.color.keyguard_default_icon_color);

        if (showClock) {
            mClockView = (TextClock) findViewById(R.id.clock_view);
            mClockView.setVisibility(View.VISIBLE);
        } else {
            mClockView = (TextClock) findViewById(R.id.clock_view);
            mClockView.setVisibility(View.GONE);
        }
        if (showDate) {
            mDateView = (TextClock) findViewById(R.id.date_view);
            mDateView.setVisibility(View.VISIBLE);
        } else {
            mDateView = (TextClock) findViewById(R.id.date_view);
            mDateView.setVisibility(View.GONE);
        }
        if (showAlarm && nextAlarm != null) {
            mAlarmStatusView = (TextView) findViewById(R.id.alarm_status);
            mAlarmStatusView.setVisibility(View.VISIBLE);
        } else {
            mAlarmStatusView = (TextView) findViewById(R.id.alarm_status);
            mAlarmStatusView.setVisibility(View.GONE);
        }
        if (mWeatherView != null) {
            mWeatherView.setVisibility(showWeather ?
                View.VISIBLE : View.GONE);
        }
        if (noWeatherInfo != null) {
            noWeatherInfo.setTextColor(primaryTextColor);
            noWeatherInfo.setVisibility(mShowWeather && !mWeatherEnabled ?
                View.VISIBLE : View.GONE);
        }
        if (weatherPanel != null) {
            weatherPanel.setVisibility(showWeather ?
                View.VISIBLE : View.GONE);
        }
        if (mWeatherConditionText != null) {
            mWeatherConditionText.setTextColor(primaryTextColor);
            mWeatherConditionText.setVisibility(showWeather ?
                View.VISIBLE : View.GONE);
        }
        if (mWeatherConditionImage != null) {
            mWeatherConditionImage.setVisibility(showWeather && mShowConditionIcon ?
                View.VISIBLE : View.GONE);
        }
        if (mWeatherCurrentTemp != null) {
            mWeatherCurrentTemp.setTextColor(primaryTextColor);
            mWeatherCurrentTemp.setVisibility(showWeather ?
                View.VISIBLE : View.GONE);
        }
        if (mWeatherCity != null) {
            mWeatherCity.setTextColor(primaryTextColor);
            mWeatherCity.setVisibility(showWeather && mShowLocation ?
                View.VISIBLE : View.GONE);
        }
    }

    // DateFormat.getBestDateTimePattern is extremely expensive, and refresh is called often.
    // This is an optimization to ensure we only recompute the patterns when the inputs change.
    private static final class Patterns {
        static String dateView;
        static String clockView12;
        static String clockView24;
        static String cacheKey;

        static void update(Context context, boolean hasAlarm) {
            final Locale locale = Locale.getDefault();
            final Resources res = context.getResources();
            final ContentResolver resolver = context.getContentResolver();
            final boolean showAlarm = Settings.System.getIntForUser(resolver,
                    Settings.System.HIDE_LOCKSCREEN_ALARM, 1, UserHandle.USER_CURRENT) == 1;
            final String dateViewSkel = res.getString(hasAlarm && showAlarm
                    ? R.string.abbrev_wday_month_day_no_year_alarm
                    : R.string.abbrev_wday_month_day_no_year);
            final String clockView12Skel = res.getString(R.string.clock_12hr_format);
            final String clockView24Skel = res.getString(R.string.clock_24hr_format);
            final String key = locale.toString() + dateViewSkel + clockView12Skel + clockView24Skel;
            if (key.equals(cacheKey)) return;
            if (res.getBoolean(com.android.internal.R.bool.config_dateformat)) {
                final String dateformat = Settings.System.getString(context.getContentResolver(),
                        Settings.System.DATE_FORMAT);
                dateView = dateformat;
            } else {
                dateView = DateFormat.getBestDateTimePattern(locale, dateViewSkel);
            }

            clockView12 = DateFormat.getBestDateTimePattern(locale, clockView12Skel);
            if(!context.getResources().getBoolean(R.bool.config_showAmpm)){
                // CLDR insists on adding an AM/PM indicator even though it wasn't in the skeleton
                // format.  The following code removes the AM/PM indicator if we didn't want it.
                if (!clockView12Skel.contains("a")) {
                    clockView12 = clockView12.replaceAll("a", "").trim();
                }
            }

            clockView24 = DateFormat.getBestDateTimePattern(locale, clockView24Skel);

            // Use fancy colon.
            clockView24 = clockView24.replace(':', '\uee01');
            clockView12 = clockView12.replace(':', '\uee01');

            cacheKey = key;
        }
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCK_SCREEN_WEATHER_CONDITION_ICON), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCK_SCREEN_SHOW_WEATHER), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                   Settings.System.OMNIJAWS_WEATHER_ICON_PACK), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                   Settings.System.LOCK_SCREEN_SHOW_WEATHER_LOCATION), false, this, UserHandle.USER_ALL);

            update();
        }

        void unobserve() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(Settings.System.getUriFor(
                    Settings.System.LOCK_SCREEN_SHOW_WEATHER))) {
                update();
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.LOCK_SCREEN_WEATHER_CONDITION_ICON))) {
                update();
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.OMNIJAWS_WEATHER_ICON_PACK))) {
                update();
            }  else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.LOCK_SCREEN_SHOW_WEATHER_LOCATION))) {
               update();
            }
        }

        public void update() {
            ContentResolver resolver = mContext.getContentResolver();

            mShowWeather = Settings.System.getInt(resolver,
                 Settings.System.LOCK_SCREEN_SHOW_WEATHER, 0) == 1;
            mShowConditionIcon = Settings.System.getInt(resolver,
                 Settings.System.LOCK_SCREEN_WEATHER_CONDITION_ICON, 1) == 1;
            mShowLocation = Settings.System.getInt(resolver,
                 Settings.System.LOCK_SCREEN_SHOW_WEATHER_LOCATION, 1) == 1;

            mWeatherEnabled = mWeatherClient.isOmniJawsEnabled();

            if (mShowWeather && !mWeatherEnabled) {
                 mWeatherClient.setOmniJawsEnabled(true);
            } else if (!mShowWeather && mWeatherEnabled) {
                try {
                    // Disable OmniJaws if not required
                    String[] tiles = Settings.Secure.getStringForUser(resolver,
                            Settings.Secure.QS_TILES, UserHandle.USER_CURRENT).split(",");
                    boolean weatherTileEnabled = Arrays.asList(tiles).contains("weather");

                    if (!weatherTileEnabled) {
                        mWeatherClient.setOmniJawsEnabled(false);
                    }
                } catch (Exception e) {
                    // Do nothing
                }
            }
            queryAndUpdateWeather();
        }
    }
}
