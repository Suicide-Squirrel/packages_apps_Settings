/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.settings.notification;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.preference.SeekBarVolumizer;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.SearchIndexableResource;
import android.provider.Settings;
import android.support.v7.preference.Preference;
import android.support.v7.preference.Preference.OnPreferenceChangeListener;
import android.support.v7.preference.TwoStatePreference;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.internal.widget.LockPatternUtils;
import com.android.settings.R;
import com.android.settings.RestrictedListPreference.RestrictedItem;
import com.android.settings.RingtonePreference;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.Utils;
import com.android.settings.dashboard.SummaryLoader;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.search.Indexable;
import com.android.settingslib.RestrictedLockUtils;
import com.android.settingslib.RestrictedPreference;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS;
import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS;
import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

public class SoundSettings extends SettingsPreferenceFragment implements Indexable {
    private static final String TAG = "SoundSettings";

    private static final String KEY_MEDIA_VOLUME = "media_volume";
    private static final String KEY_ALARM_VOLUME = "alarm_volume";
    private static final String KEY_RING_VOLUME = "ring_volume";
    private static final String KEY_NOTIFICATION_VOLUME = "notification_volume";
    private static final String KEY_PHONE_RINGTONE = "ringtone";
    private static final String KEY_NOTIFICATION_RINGTONE = "notification_ringtone";
    private static final String KEY_ALARM_RINGTONE = "alarm_ringtone";
    private static final String KEY_VIBRATE_WHEN_RINGING = "vibrate_when_ringing";
    private static final String KEY_ZEN_MODE = "zen_mode";
    private static final String KEY_CELL_BROADCAST_SETTINGS = "cell_broadcast_settings";
	private static final String KEY_LOCK_SCREEN_NOTIFICATIONS = "lock_screen_notifications";
    private static final String KEY_LOCK_SCREEN_PROFILE_NOTIFICATIONS =
            "lock_screen_notifications_profile";
    private static final String KEY_VOLBTN_MUSIC_CONTROLS = "volbtn_music_controls";

    private static final String SELECTED_PREFERENCE_KEY = "selected_preference";
    private static final int REQUEST_CODE = 200;

    private static final String[] RESTRICTED_KEYS = {
        KEY_MEDIA_VOLUME,
        KEY_ALARM_VOLUME,
        KEY_RING_VOLUME,
        KEY_NOTIFICATION_VOLUME,
        KEY_ZEN_MODE,
    };

    private static final int SAMPLE_CUTOFF = 2000;  // manually cap sample playback at 2 seconds

    private final VolumePreferenceCallback mVolumeCallback = new VolumePreferenceCallback();
    private final H mHandler = new H();
    private final SettingsObserver mSettingsObserver = new SettingsObserver();
    private final Receiver mReceiver = new Receiver();
    private final ArrayList<VolumeSeekBarPreference> mVolumePrefs = new ArrayList<>();

    private Context mContext;
    private boolean mVoiceCapable;
    private Vibrator mVibrator;
    private AudioManager mAudioManager;
    private VolumeSeekBarPreference mRingOrNotificationPreference;

    private Preference mPhoneRingtonePreference;
    private Preference mNotificationRingtonePreference;
    private Preference mAlarmRingtonePreference;
    private TwoStatePreference mVibrateWhenRinging;
    private TwoStatePreference mVlbtnMusicControl;
    private ComponentName mSuppressor;
    private int mRingerMode = -1;
	
	private RestrictedDropDownPreference mLockscreen;
    private RestrictedDropDownPreference mLockscreenProfile;
    private boolean mSecure;
    private boolean mSecureProfile;
    private int mLockscreenSelectedValue;
    private int mLockscreenSelectedValueProfile;
    private int mProfileChallengeUserId;

    private PackageManager mPm;
    private UserManager mUserManager;
    private RingtonePreference mRequestPreference;

    @Override
    protected int getMetricsCategory() {
        return MetricsEvent.SOUND;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = getActivity();
        mPm = getPackageManager();
        mUserManager = UserManager.get(getContext());
        mVoiceCapable = Utils.isVoiceCapable(mContext);
		mProfileChallengeUserId = Utils.getManagedProfileId(
                UserManager.get(mContext), UserHandle.myUserId());

        final LockPatternUtils utils = new LockPatternUtils(getActivity());
        final boolean isUnified =
                !utils.isSeparateProfileChallengeEnabled(mProfileChallengeUserId);

        mSecure = utils.isSecure(UserHandle.myUserId());
        mSecureProfile = (mProfileChallengeUserId != UserHandle.USER_NULL)
                && (utils.isSecure(mProfileChallengeUserId) || (isUnified && mSecure));

        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mVibrator = (Vibrator) getActivity().getSystemService(Context.VIBRATOR_SERVICE);
        if (mVibrator != null && !mVibrator.hasVibrator()) {
            mVibrator = null;
        }

        addPreferencesFromResource(R.xml.sound_settings);

        initVolumePreference(KEY_MEDIA_VOLUME, AudioManager.STREAM_MUSIC,
                com.android.internal.R.drawable.ic_audio_media_mute);
        initVolumePreference(KEY_ALARM_VOLUME, AudioManager.STREAM_ALARM,
                com.android.internal.R.drawable.ic_audio_alarm_mute);
        if (mVoiceCapable) {
            mRingOrNotificationPreference =
                    initVolumePreference(KEY_RING_VOLUME, AudioManager.STREAM_RING,
                            com.android.internal.R.drawable.ic_audio_ring_notif_mute);
            removePreference(KEY_NOTIFICATION_VOLUME);
        } else {
            mRingOrNotificationPreference =
                    initVolumePreference(KEY_NOTIFICATION_VOLUME, AudioManager.STREAM_NOTIFICATION,
                            com.android.internal.R.drawable.ic_audio_ring_notif_mute);
            removePreference(KEY_RING_VOLUME);
        }

        // Enable link to CMAS app settings depending on the value in config.xml.
        boolean isCellBroadcastAppLinkEnabled = this.getResources().getBoolean(
                com.android.internal.R.bool.config_cellBroadcastAppLinks);
        try {
            if (isCellBroadcastAppLinkEnabled) {
                if (mPm.getApplicationEnabledSetting("com.android.cellbroadcastreceiver")
                        == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    isCellBroadcastAppLinkEnabled = false;  // CMAS app disabled
                }
            }
        } catch (IllegalArgumentException ignored) {
            isCellBroadcastAppLinkEnabled = false;  // CMAS app not installed
        }
        if (!mUserManager.isAdminUser() || !isCellBroadcastAppLinkEnabled ||
                RestrictedLockUtils.hasBaseUserRestriction(mContext,
                        UserManager.DISALLOW_CONFIG_CELL_BROADCASTS, UserHandle.myUserId())) {
            removePreference(KEY_CELL_BROADCAST_SETTINGS);
        }
        initRingtones();
        initVolumeButtonMusicControl();
        initVibrateWhenRinging();
        updateRingerMode();
        updateEffectsSuppressor();
		initLockscreenNotifications();

        if (mProfileChallengeUserId != UserHandle.USER_NULL) {
            addPreferencesFromResource(R.xml.configure_notification_settings_profile);
            initLockscreenNotificationsForProfile();
        }

        if (savedInstanceState != null) {
            String selectedPreference = savedInstanceState.getString(SELECTED_PREFERENCE_KEY, null);
            if (!TextUtils.isEmpty(selectedPreference)) {
                mRequestPreference = (RingtonePreference) findPreference(selectedPreference);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        lookupRingtoneNames();
        mSettingsObserver.register(true);
        mReceiver.register(true);
        updateRingOrNotificationPreference();
        updateEffectsSuppressor();
        updateVibrateWhenRinging();
        for (VolumeSeekBarPreference volumePref : mVolumePrefs) {
            volumePref.onActivityResume();
        }

        final EnforcedAdmin admin = RestrictedLockUtils.checkIfRestrictionEnforced(mContext,
                UserManager.DISALLOW_ADJUST_VOLUME, UserHandle.myUserId());
        final boolean hasBaseRestriction = RestrictedLockUtils.hasBaseUserRestriction(mContext,
                UserManager.DISALLOW_ADJUST_VOLUME, UserHandle.myUserId());
        for (String key : RESTRICTED_KEYS) {
            Preference pref = findPreference(key);
            if (pref != null) {
                pref.setEnabled(!hasBaseRestriction);
            }
            if (pref instanceof RestrictedPreference && !hasBaseRestriction) {
                ((RestrictedPreference) pref).setDisabledByAdmin(admin);
            }
        }
        RestrictedPreference broadcastSettingsPref = (RestrictedPreference) findPreference(
                KEY_CELL_BROADCAST_SETTINGS);
        if (broadcastSettingsPref != null) {
            broadcastSettingsPref.checkRestrictionAndSetDisabled(
                    UserManager.DISALLOW_CONFIG_CELL_BROADCASTS);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        for (VolumeSeekBarPreference volumePref : mVolumePrefs) {
            volumePref.onActivityPause();
        }
        mVolumeCallback.stopSample();
        mSettingsObserver.register(false);
        mReceiver.register(false);
    }
	
	private void initLockscreenNotifications() {
        mLockscreen = (RestrictedDropDownPreference) getPreferenceScreen().findPreference(
                KEY_LOCK_SCREEN_NOTIFICATIONS);
        if (mLockscreen == null) {
            Log.i(TAG, "Preference not found: " + KEY_LOCK_SCREEN_NOTIFICATIONS);
            return;
        }

        ArrayList<CharSequence> entries = new ArrayList<>();
        ArrayList<CharSequence> values = new ArrayList<>();
        entries.add(getString(R.string.lock_screen_notifications_summary_disable));
        values.add(Integer.toString(R.string.lock_screen_notifications_summary_disable));

        String summaryShowEntry = getString(R.string.lock_screen_notifications_summary_show);
        String summaryShowEntryValue = Integer.toString(
                R.string.lock_screen_notifications_summary_show);
        entries.add(summaryShowEntry);
        values.add(summaryShowEntryValue);
        setRestrictedIfNotificationFeaturesDisabled(summaryShowEntry, summaryShowEntryValue,
                KEYGUARD_DISABLE_SECURE_NOTIFICATIONS | KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);

        if (mSecure) {
            String summaryHideEntry = getString(R.string.lock_screen_notifications_summary_hide);
            String summaryHideEntryValue = Integer.toString(
                    R.string.lock_screen_notifications_summary_hide);
            entries.add(summaryHideEntry);
            values.add(summaryHideEntryValue);
            setRestrictedIfNotificationFeaturesDisabled(summaryHideEntry, summaryHideEntryValue,
                    KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);
        }

        mLockscreen.setEntries(entries.toArray(new CharSequence[entries.size()]));
        mLockscreen.setEntryValues(values.toArray(new CharSequence[values.size()]));
        updateLockscreenNotifications();
        if (mLockscreen.getEntries().length > 1) {
            mLockscreen.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    final int val = Integer.parseInt((String) newValue);
                    if (val == mLockscreenSelectedValue) {
                        return false;
                    }
                    final boolean enabled =
                            val != R.string.lock_screen_notifications_summary_disable;
                    final boolean show = val == R.string.lock_screen_notifications_summary_show;
                    Settings.Secure.putInt(getContentResolver(),
                            Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, show ? 1 : 0);
                    Settings.Secure.putInt(getContentResolver(),
                            Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, enabled ? 1 : 0);
                    mLockscreenSelectedValue = val;
                    return true;
                }
            });
        } else {
            // There is one or less option for the user, disable the drop down.
            mLockscreen.setEnabled(false);
        }
    }

    // === Lockscreen (public / private) notifications ===
    private void initLockscreenNotificationsForProfile() {
        mLockscreenProfile = (RestrictedDropDownPreference) getPreferenceScreen()
                .findPreference(KEY_LOCK_SCREEN_PROFILE_NOTIFICATIONS);
        if (mLockscreenProfile == null) {
            Log.i(TAG, "Preference not found: " + KEY_LOCK_SCREEN_PROFILE_NOTIFICATIONS);
            return;
        }
        ArrayList<CharSequence> entries = new ArrayList<>();
        ArrayList<CharSequence> values = new ArrayList<>();
        entries.add(getString(R.string.lock_screen_notifications_summary_disable_profile));
        values.add(Integer.toString(R.string.lock_screen_notifications_summary_disable_profile));

        String summaryShowEntry = getString(
                R.string.lock_screen_notifications_summary_show_profile);
        String summaryShowEntryValue = Integer.toString(
                R.string.lock_screen_notifications_summary_show_profile);
        entries.add(summaryShowEntry);
        values.add(summaryShowEntryValue);
        setRestrictedIfNotificationFeaturesDisabled(summaryShowEntry, summaryShowEntryValue,
                KEYGUARD_DISABLE_SECURE_NOTIFICATIONS | KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);

        if (mSecureProfile) {
            String summaryHideEntry = getString(
                    R.string.lock_screen_notifications_summary_hide_profile);
            String summaryHideEntryValue = Integer.toString(
                    R.string.lock_screen_notifications_summary_hide_profile);
            entries.add(summaryHideEntry);
            values.add(summaryHideEntryValue);
            setRestrictedIfNotificationFeaturesDisabled(summaryHideEntry, summaryHideEntryValue,
                    KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);
        }

        mLockscreenProfile.setOnPreClickListener(
                (Preference p) -> Utils.startQuietModeDialogIfNecessary(mContext,
                        UserManager.get(mContext),
                        mProfileChallengeUserId)
        );

        mLockscreenProfile.setEntries(entries.toArray(new CharSequence[entries.size()]));
        mLockscreenProfile.setEntryValues(values.toArray(new CharSequence[values.size()]));
        updateLockscreenNotificationsForProfile();
        if (mLockscreenProfile.getEntries().length > 1) {
            mLockscreenProfile.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    final int val = Integer.parseInt((String) newValue);
                    if (val == mLockscreenSelectedValueProfile) {
                        return false;
                    }
                    final boolean enabled =
                            val != R.string.lock_screen_notifications_summary_disable_profile;
                    final boolean show =
                            val == R.string.lock_screen_notifications_summary_show_profile;
                    Settings.Secure.putIntForUser(getContentResolver(),
                            Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
                            show ? 1 : 0, mProfileChallengeUserId);
                    Settings.Secure.putIntForUser(getContentResolver(),
                            Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                            enabled ? 1 : 0, mProfileChallengeUserId);
                    mLockscreenSelectedValueProfile = val;
                    return true;
                }
            });
        } else {
            // There is one or less option for the user, disable the drop down.
            mLockscreenProfile.setEnabled(false);
        }
    }

    private void setRestrictedIfNotificationFeaturesDisabled(CharSequence entry,
            CharSequence entryValue, int keyguardNotificationFeatures) {
        EnforcedAdmin admin = RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, keyguardNotificationFeatures, UserHandle.myUserId());
        if (admin != null && mLockscreen != null) {
            RestrictedDropDownPreference.RestrictedItem item =
                    new RestrictedDropDownPreference.RestrictedItem(entry, entryValue, admin);
            mLockscreen.addRestrictedItem(item);
        }
        if (mProfileChallengeUserId != UserHandle.USER_NULL) {
            EnforcedAdmin profileAdmin = RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                    mContext, keyguardNotificationFeatures, mProfileChallengeUserId);
            if (profileAdmin != null && mLockscreenProfile != null) {
                RestrictedDropDownPreference.RestrictedItem item =
                        new RestrictedDropDownPreference.RestrictedItem(
                                entry, entryValue, profileAdmin);
                mLockscreenProfile.addRestrictedItem(item);
            }
        }
    }

    private void updateLockscreenNotifications() {
        if (mLockscreen == null) {
            return;
        }
        final boolean enabled = getLockscreenNotificationsEnabled(UserHandle.myUserId());
        final boolean allowPrivate = !mSecure
                || getLockscreenAllowPrivateNotifications(UserHandle.myUserId());
        mLockscreenSelectedValue = !enabled ? R.string.lock_screen_notifications_summary_disable :
                allowPrivate ? R.string.lock_screen_notifications_summary_show :
                R.string.lock_screen_notifications_summary_hide;
        mLockscreen.setValue(Integer.toString(mLockscreenSelectedValue));
    }

    private void updateLockscreenNotificationsForProfile() {
        if (mProfileChallengeUserId == UserHandle.USER_NULL) {
            return;
        }
        if (mLockscreenProfile == null) {
            return;
        }
        final boolean enabled = getLockscreenNotificationsEnabled(mProfileChallengeUserId);
        final boolean allowPrivate = !mSecureProfile
                || getLockscreenAllowPrivateNotifications(mProfileChallengeUserId);
        mLockscreenSelectedValueProfile = !enabled
                ? R.string.lock_screen_notifications_summary_disable_profile
                        : (allowPrivate ? R.string.lock_screen_notifications_summary_show_profile
                                : R.string.lock_screen_notifications_summary_hide_profile);
        mLockscreenProfile.setValue(Integer.toString(mLockscreenSelectedValueProfile));
    }

    private boolean getLockscreenNotificationsEnabled(int userId) {
        return Settings.Secure.getIntForUser(getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 0, userId) != 0;
    }

    private boolean getLockscreenAllowPrivateNotifications(int userId) {
        return Settings.Secure.getIntForUser(getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0, userId) != 0;
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference instanceof RingtonePreference) {
            mRequestPreference = (RingtonePreference) preference;
            mRequestPreference.onPrepareRingtonePickerIntent(mRequestPreference.getIntent());
            startActivityForResult(preference.getIntent(), REQUEST_CODE);
            return true;
        } else if (preference == findPreference(KEY_CELL_BROADCAST_SETTINGS)) {
            final Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(new ComponentName(
                 "com.android.cellbroadcastreceiver",
                 "com.android.cellbroadcastreceiver.CellBroadcastSettings"));

            if (mContext.getPackageManager()
                        .queryIntentActivities(intent, 0).isEmpty())  {
                Log.d(TAG, "Activity com.android.cellbroadcastreceiver" +
                                ".CellBroadcastSettings does not exist");
                return false;
            }
            startActivity(intent);
            return true;
        }

        return super.onPreferenceTreeClick(preference);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (mRequestPreference != null) {
            mRequestPreference.onActivityResult(requestCode, resultCode, data);
            mRequestPreference = null;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mRequestPreference != null) {
            outState.putString(SELECTED_PREFERENCE_KEY, mRequestPreference.getKey());
        }
    }

    // === Volumes ===

    private VolumeSeekBarPreference initVolumePreference(String key, int stream, int muteIcon) {
        final VolumeSeekBarPreference volumePref = (VolumeSeekBarPreference) findPreference(key);
        volumePref.setCallback(mVolumeCallback);
        volumePref.setStream(stream);
        mVolumePrefs.add(volumePref);
        volumePref.setMuteIcon(muteIcon);
        return volumePref;
    }

    private void updateRingOrNotificationPreference() {
        mRingOrNotificationPreference.showIcon(mSuppressor != null
                ? com.android.internal.R.drawable.ic_audio_ring_notif_mute
                : mRingerMode == AudioManager.RINGER_MODE_VIBRATE || wasRingerModeVibrate()
                ? com.android.internal.R.drawable.ic_audio_ring_notif_vibrate
                : com.android.internal.R.drawable.ic_audio_ring_notif);
    }

    private boolean wasRingerModeVibrate() {
        return mVibrator != null && mRingerMode == AudioManager.RINGER_MODE_SILENT
                && mAudioManager.getLastAudibleStreamVolume(AudioManager.STREAM_RING) == 0;
    }

    private void updateRingerMode() {
        final int ringerMode = mAudioManager.getRingerModeInternal();
        if (mRingerMode == ringerMode) return;
        mRingerMode = ringerMode;
        updateRingOrNotificationPreference();
    }

    private void updateEffectsSuppressor() {
        final ComponentName suppressor = NotificationManager.from(mContext).getEffectsSuppressor();
        if (Objects.equals(suppressor, mSuppressor)) return;
        mSuppressor = suppressor;
        if (mRingOrNotificationPreference != null) {
            final String text = suppressor != null ?
                    mContext.getString(com.android.internal.R.string.muted_by,
                            getSuppressorCaption(suppressor)) : null;
            mRingOrNotificationPreference.setSuppressionText(text);
        }
        updateRingOrNotificationPreference();
    }

    private String getSuppressorCaption(ComponentName suppressor) {
        final PackageManager pm = mContext.getPackageManager();
        try {
            final ServiceInfo info = pm.getServiceInfo(suppressor, 0);
            if (info != null) {
                final CharSequence seq = info.loadLabel(pm);
                if (seq != null) {
                    final String str = seq.toString().trim();
                    if (str.length() > 0) {
                        return str;
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "Error loading suppressor caption", e);
        }
        return suppressor.getPackageName();
    }

    private final class VolumePreferenceCallback implements VolumeSeekBarPreference.Callback {
        private SeekBarVolumizer mCurrent;

        @Override
        public void onSampleStarting(SeekBarVolumizer sbv) {
            if (mCurrent != null && mCurrent != sbv) {
                mCurrent.stopSample();
            }
            mCurrent = sbv;
            if (mCurrent != null) {
                mHandler.removeMessages(H.STOP_SAMPLE);
                mHandler.sendEmptyMessageDelayed(H.STOP_SAMPLE, SAMPLE_CUTOFF);
            }
        }

        @Override
        public void onStreamValueChanged(int stream, int progress) {
            // noop
        }

        public void stopSample() {
            if (mCurrent != null) {
                mCurrent.stopSample();
            }
        }
    };


    // === Phone & notification ringtone ===

    private void initRingtones() {
        mPhoneRingtonePreference = getPreferenceScreen().findPreference(KEY_PHONE_RINGTONE);
        if (mPhoneRingtonePreference != null && !mVoiceCapable) {
            getPreferenceScreen().removePreference(mPhoneRingtonePreference);
            mPhoneRingtonePreference = null;
        }
        mNotificationRingtonePreference =
                getPreferenceScreen().findPreference(KEY_NOTIFICATION_RINGTONE);
        mAlarmRingtonePreference = getPreferenceScreen().findPreference(KEY_ALARM_RINGTONE);
    }

    private void lookupRingtoneNames() {
        AsyncTask.execute(mLookupRingtoneNames);
    }

    private final Runnable mLookupRingtoneNames = new Runnable() {
        @Override
        public void run() {
            if (mPhoneRingtonePreference != null) {
                final CharSequence summary = updateRingtoneName(
                        mContext, RingtoneManager.TYPE_RINGTONE);
                if (summary != null) {
                    mHandler.obtainMessage(H.UPDATE_PHONE_RINGTONE, summary).sendToTarget();
                }
            }
            if (mNotificationRingtonePreference != null) {
                final CharSequence summary = updateRingtoneName(
                        mContext, RingtoneManager.TYPE_NOTIFICATION);
                if (summary != null) {
                    mHandler.obtainMessage(H.UPDATE_NOTIFICATION_RINGTONE, summary).sendToTarget();
                }
            }
            if (mAlarmRingtonePreference != null) {
                final CharSequence summary =
                        updateRingtoneName(mContext, RingtoneManager.TYPE_ALARM);
                if (summary != null) {
                    mHandler.obtainMessage(H.UPDATE_ALARM_RINGTONE, summary).sendToTarget();
                }
            }
        }
    };

    private static CharSequence updateRingtoneName(Context context, int type) {
        if (context == null) {
            Log.e(TAG, "Unable to update ringtone name, no context provided");
            return null;
        }
        Uri ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(context, type);
        CharSequence summary = context.getString(com.android.internal.R.string.ringtone_unknown);
        // Is it a silent ringtone?
        if (ringtoneUri == null) {
            summary = context.getString(com.android.internal.R.string.ringtone_silent);
        } else {
            Cursor cursor = null;
            try {
                if (MediaStore.AUTHORITY.equals(ringtoneUri.getAuthority())) {
                    // Fetch the ringtone title from the media provider
                    cursor = context.getContentResolver().query(ringtoneUri,
                            new String[] { MediaStore.Audio.Media.TITLE }, null, null, null);
                } else if (ContentResolver.SCHEME_CONTENT.equals(ringtoneUri.getScheme())) {
                    cursor = context.getContentResolver().query(ringtoneUri,
                            new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
                }
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        summary = cursor.getString(0);
                    }
                }
            } catch (SQLiteException sqle) {
                // Unknown title for the ringtone
            } catch (IllegalArgumentException iae) {
                // Some other error retrieving the column from the provider
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return summary;
    }

    private void initVolumeButtonMusicControl() {
        mVlbtnMusicControl = (TwoStatePreference) getPreferenceScreen().findPreference(
                KEY_VOLBTN_MUSIC_CONTROLS);
        if (mVlbtnMusicControl == null) {
            Log.i(TAG, "Preference not found: " + KEY_VOLBTN_MUSIC_CONTROLS);
            return;
        }
        mVlbtnMusicControl.setPersistent(false);
        updateVolumeButtonMusicControl();
        mVlbtnMusicControl.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                final boolean val = (Boolean) newValue;
                return Settings.System.putInt(getContentResolver(),
                        Settings.System.VOLBTN_MUSIC_CONTROLS, val ? 1 : 0);
            }
        });
    }

    // === Vibrate when ringing ===

    private void initVibrateWhenRinging() {
        mVibrateWhenRinging =
                (TwoStatePreference) getPreferenceScreen().findPreference(KEY_VIBRATE_WHEN_RINGING);
        if (mVibrateWhenRinging == null) {
            Log.i(TAG, "Preference not found: " + KEY_VIBRATE_WHEN_RINGING);
            return;
        }
        if (!mVoiceCapable) {
            getPreferenceScreen().removePreference(mVibrateWhenRinging);
            mVibrateWhenRinging = null;
            return;
        }
        mVibrateWhenRinging.setPersistent(false);
        updateVibrateWhenRinging();
        mVibrateWhenRinging.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                final boolean val = (Boolean) newValue;
                return Settings.System.putInt(getContentResolver(),
                        Settings.System.VIBRATE_WHEN_RINGING,
                        val ? 1 : 0);
            }
        });
    }

    private void updateVolumeButtonMusicControl() {
        if (mVlbtnMusicControl == null) return;
        mVlbtnMusicControl.setChecked(Settings.System.getInt(getContentResolver(),
                Settings.System.VOLBTN_MUSIC_CONTROLS, 1) != 0);
    }

    private void updateVibrateWhenRinging() {
        if (mVibrateWhenRinging == null) return;
        mVibrateWhenRinging.setChecked(Settings.System.getInt(getContentResolver(),
                Settings.System.VIBRATE_WHEN_RINGING, 0) != 0);
    }

    // === Callbacks ===

    private final class SettingsObserver extends ContentObserver {
        private final Uri VIBRATE_WHEN_RINGING_URI =
                Settings.System.getUriFor(Settings.System.VIBRATE_WHEN_RINGING);
		private final Uri LOCK_SCREEN_PRIVATE_URI =
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);
        private final Uri LOCK_SCREEN_SHOW_URI =
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS);
        private final Uri VOLBTN_MUSIC_CONTROLS_URI =
                Settings.System.getUriFor(Settings.System.VOLBTN_MUSIC_CONTROLS);

        public SettingsObserver() {
            super(mHandler);
        }

        public void register(boolean register) {
            final ContentResolver cr = getContentResolver();
            if (register) {
                cr.registerContentObserver(VIBRATE_WHEN_RINGING_URI, false, this);
				cr.registerContentObserver(LOCK_SCREEN_PRIVATE_URI, false, this);
                cr.registerContentObserver(LOCK_SCREEN_SHOW_URI, false, this);
            } else {
                cr.unregisterContentObserver(this);
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            if (VIBRATE_WHEN_RINGING_URI.equals(uri)) {
                updateVibrateWhenRinging();
            } else if (VOLBTN_MUSIC_CONTROLS_URI.equals(uri)) {
                updateVolumeButtonMusicControl();
            }
			if (LOCK_SCREEN_PRIVATE_URI.equals(uri) || LOCK_SCREEN_SHOW_URI.equals(uri)) {
                updateLockscreenNotifications();
                if (mProfileChallengeUserId != UserHandle.USER_NULL) {
                    updateLockscreenNotificationsForProfile();
                }
            }
        }
    }

    private final class H extends Handler {
        private static final int UPDATE_PHONE_RINGTONE = 1;
        private static final int UPDATE_NOTIFICATION_RINGTONE = 2;
        private static final int STOP_SAMPLE = 3;
        private static final int UPDATE_EFFECTS_SUPPRESSOR = 4;
        private static final int UPDATE_RINGER_MODE = 5;
        private static final int UPDATE_ALARM_RINGTONE = 6;

        private H() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case UPDATE_PHONE_RINGTONE:
                    mPhoneRingtonePreference.setSummary((CharSequence) msg.obj);
                    break;
                case UPDATE_NOTIFICATION_RINGTONE:
                    mNotificationRingtonePreference.setSummary((CharSequence) msg.obj);
                    break;
                case STOP_SAMPLE:
                    mVolumeCallback.stopSample();
                    break;
                case UPDATE_EFFECTS_SUPPRESSOR:
                    updateEffectsSuppressor();
                    break;
                case UPDATE_RINGER_MODE:
                    updateRingerMode();
                    break;
                case UPDATE_ALARM_RINGTONE:
                    mAlarmRingtonePreference.setSummary((CharSequence) msg.obj);
                    break;
            }
        }
    }

    private class Receiver extends BroadcastReceiver {
        private boolean mRegistered;

        public void register(boolean register) {
            if (mRegistered == register) return;
            if (register) {
                final IntentFilter filter = new IntentFilter();
                filter.addAction(NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED);
                filter.addAction(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);
                mContext.registerReceiver(this, filter);
            } else {
                mContext.unregisterReceiver(this);
            }
            mRegistered = register;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED.equals(action)) {
                mHandler.sendEmptyMessage(H.UPDATE_EFFECTS_SUPPRESSOR);
            } else if (AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION.equals(action)) {
                mHandler.sendEmptyMessage(H.UPDATE_RINGER_MODE);
            }
        }
    }

    // === Summary ===

    private static class SummaryProvider extends BroadcastReceiver
            implements SummaryLoader.SummaryProvider {

        private final Context mContext;
        private final AudioManager mAudioManager;
        private final SummaryLoader mSummaryLoader;
        private final int maxVolume;

        public SummaryProvider(Context context, SummaryLoader summaryLoader) {
            mContext = context;
            mSummaryLoader = summaryLoader;
            mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_RING);
        }

        @Override
        public void setListening(boolean listening) {
            if (listening) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
                filter.addAction(AudioManager.STREAM_DEVICES_CHANGED_ACTION);
                filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
                filter.addAction(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);
                filter.addAction(AudioManager.STREAM_MUTE_CHANGED_ACTION);
                filter.addAction(NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED);
                mContext.registerReceiver(this, filter);
            } else {
                mContext.unregisterReceiver(this);
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final int ringerMode = mAudioManager.getRingerMode();
            int resId;
            String percent = "";
            if (ringerMode == mAudioManager.RINGER_MODE_SILENT) {
                resId = R.string.sound_settings_summary_silent;
            } else if (ringerMode == mAudioManager.RINGER_MODE_VIBRATE){
                resId = R.string.sound_settings_summary_vibrate;
            }
            else {
                percent =  NumberFormat.getPercentInstance().format(
                        (double) mAudioManager.getStreamVolume(
                                AudioManager.STREAM_RING) / maxVolume);
                resId = R.string.sound_settings_summary;
            }
            mSummaryLoader.setSummary(this, mContext.getString(resId, percent));
        }
    }

    public static final SummaryLoader.SummaryProviderFactory SUMMARY_PROVIDER_FACTORY
            = new SummaryLoader.SummaryProviderFactory() {
        @Override
        public SummaryLoader.SummaryProvider createSummaryProvider(Activity activity,
                SummaryLoader summaryLoader) {
            return new SummaryProvider(activity, summaryLoader);
        }
    };

    // === Indexing ===

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider() {

        public List<SearchIndexableResource> getXmlResourcesToIndex(
                Context context, boolean enabled) {
            final SearchIndexableResource sir = new SearchIndexableResource(context);
            sir.xmlResId = R.xml.sound_settings;
            return Arrays.asList(sir);
        }

        public List<String> getNonIndexableKeys(Context context) {
            final ArrayList<String> rt = new ArrayList<String>();
            if (Utils.isVoiceCapable(context)) {
                rt.add(KEY_NOTIFICATION_VOLUME);
            } else {
                rt.add(KEY_RING_VOLUME);
                rt.add(KEY_PHONE_RINGTONE);
                rt.add(KEY_VIBRATE_WHEN_RINGING);
            }

            final PackageManager pm = context.getPackageManager();
            final UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);

            // Enable link to CMAS app settings depending on the value in config.xml.
            boolean isCellBroadcastAppLinkEnabled = context.getResources().getBoolean(
                    com.android.internal.R.bool.config_cellBroadcastAppLinks);
            try {
                if (isCellBroadcastAppLinkEnabled) {
                    if (pm.getApplicationEnabledSetting("com.android.cellbroadcastreceiver")
                            == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        isCellBroadcastAppLinkEnabled = false;  // CMAS app disabled
                    }
                }
            } catch (IllegalArgumentException ignored) {
                isCellBroadcastAppLinkEnabled = false;  // CMAS app not installed
            }
            if (!um.isAdminUser() || !isCellBroadcastAppLinkEnabled) {
                rt.add(KEY_CELL_BROADCAST_SETTINGS);
            }

            return rt;
        }
    };
}
