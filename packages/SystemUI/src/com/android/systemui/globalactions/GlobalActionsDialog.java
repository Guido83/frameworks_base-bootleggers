/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.globalactions;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraAccessException;
import android.Manifest;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityEvent;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.colorextraction.drawable.GradientDrawable;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.util.EmergencyAffordanceManager;
import com.android.internal.util.ScreenshotHelper;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.Dependency;
import com.android.systemui.HardwareUiLayout;
import com.android.systemui.Interpolators;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.GlobalActions.GlobalActionsManager;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.util.EmergencyDialerConstants;
import com.android.systemui.volume.SystemUIInterpolators.LogAccelerateInterpolator;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Helper to show the global actions dialog.  Each item is an {@link Action} that
 * may show depending on whether the keyguard is showing, and whether the device
 * is provisioned.
 */
class GlobalActionsDialog implements DialogInterface.OnDismissListener,
        DialogInterface.OnClickListener, DialogInterface.OnShowListener {

    static public final String SYSTEM_DIALOG_REASON_KEY = "reason";
    static public final String SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS = "globalactions";
    static public final String SYSTEM_DIALOG_REASON_DREAM = "dream";

    private static final String TAG = "GlobalActionsDialog";

    private static final boolean SHOW_SILENT_TOGGLE = true;

    /* Valid settings for global actions keys.
     * see config.xml config_globalActionList */
    private static final String GLOBAL_ACTION_KEY_POWER = "power";
    private static final String GLOBAL_ACTION_KEY_AIRPLANE = "airplane";
    private static final String GLOBAL_ACTION_KEY_BUGREPORT = "bugreport";
    private static final String GLOBAL_ACTION_KEY_SILENT = "silent";
    private static final String GLOBAL_ACTION_KEY_USERS = "users";
    private static final String GLOBAL_ACTION_KEY_SETTINGS = "settings";
    private static final String GLOBAL_ACTION_KEY_LOCKDOWN = "lockdown";
    private static final String GLOBAL_ACTION_KEY_VOICEASSIST = "voiceassist";
    private static final String GLOBAL_ACTION_KEY_ASSIST = "assist";
    private static final String GLOBAL_ACTION_KEY_RESTART = "restart";
    private static final String GLOBAL_ACTION_KEY_LOGOUT = "logout";
    private static final String GLOBAL_ACTION_KEY_EMERGENCY = "emergency";
    private static final String GLOBAL_ACTION_KEY_SCREENSHOT = "screenshot";

    private static final String GLOBAL_ACTION_KEY_REBOOT_RECOVERY = "reboot_recovery";
    private static final String GLOBAL_ACTION_KEY_REBOOT_BOOTLOADER = "reboot_bootloader";
    private static final String GLOBAL_ACTION_KEY_REBOOT_SYSTEMUI = "reboot_systemui";
    private static final String GLOBAL_ACTION_KEY_SCREENRECORD = "screenrecord";
    private static final String GLOBAL_ACTION_KEY_FLASHLIGHT = "flashlight";

    private final Context mContext;
    private final GlobalActionsManager mWindowManagerFuncs;
    private final AudioManager mAudioManager;
    private final IDreamManager mDreamManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardManager mKeyguardManager;

    private ArrayList<Action> mItems;
    private ActionsDialog mDialog;

    private Action mSilentModeAction;
    private ToggleAction mAirplaneModeOn;

    private MyAdapter mAdapter;

    private boolean mKeyguardShowing = false;
    private boolean mDeviceProvisioned = false;
    private ToggleAction.State mAirplaneState = ToggleAction.State.Off;
    private boolean mIsWaitingForEcmExit = false;
    private boolean mHasTelephony;
    private boolean mHasVibrator;
    private boolean mHasLogoutButton;
    private boolean mHasLockdownButton;
    private boolean mSeparatedEmergencyButtonEnabled;
    private final boolean mShowSilentToggle;
    private final EmergencyAffordanceManager mEmergencyAffordanceManager;
    private final ScreenshotHelper mScreenshotHelper;
    private boolean mFlashlightEnabled = false;

    private String[] mDefaultMenuActions;
    private String[] mRootMenuActions;
    private String[] mRebootMenuActions;
    private String[] mCurrentMenuActions;
    private boolean mRebootMenu;

    private BitSet mAirplaneModeBits;
    private final List<PhoneStateListener> mPhoneStateListeners = new ArrayList<>();

    /**
     * @param context everything needs a context :(
     */
    public GlobalActionsDialog(Context context, GlobalActionsManager windowManagerFuncs) {
        mContext = new ContextThemeWrapper(context, com.android.systemui.R.style.qs_theme);
        mWindowManagerFuncs = windowManagerFuncs;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.getService(DreamService.DREAM_SERVICE));
        mDevicePolicyManager = (DevicePolicyManager) mContext.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        mLockPatternUtils = new LockPatternUtils(mContext);
        mKeyguardManager = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        context.registerReceiver(mBroadcastReceiver, filter);

        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mHasTelephony = cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

        // get notified of phone state changes
        SubscriptionManager.from(mContext).addOnSubscriptionsChangedListener(
                new SubscriptionManager.OnSubscriptionsChangedListener() {
            @Override
            public void onSubscriptionsChanged() {
                super.onSubscriptionsChanged();
                setupAirplaneModeListeners();
            }
        });
        setupAirplaneModeListeners();
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), true,
                mAirplaneModeObserver);
        Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        mHasVibrator = vibrator != null && vibrator.hasVibrator();

        mShowSilentToggle = SHOW_SILENT_TOGGLE && !mContext.getResources().getBoolean(
                R.bool.config_useFixedVolume);

        mEmergencyAffordanceManager = new EmergencyAffordanceManager(context);
        mScreenshotHelper = new ScreenshotHelper(context);

        // Set the initial status of airplane mode toggle
        mAirplaneState = getUpdatedAirplaneToggleState();
    }

    /**
     * Since there are two ways of handling airplane mode (with telephony, we depend on the internal
     * device telephony state), and MSIM devices do not report phone state for missing SIMs, we
     * need to dynamically setup listeners based on subscription changes.
     *
     * So if there is _any_ active SIM in the device, we can depend on the phone state,
     * otherwise fall back to {@link Settings.Global#AIRPLANE_MODE_ON}.
     */
    private void setupAirplaneModeListeners() {
        TelephonyManager telephonyManager =
                (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);

        for (PhoneStateListener listener : mPhoneStateListeners) {
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE);
        }
        mPhoneStateListeners.clear();

        final List<SubscriptionInfo> subInfoList = SubscriptionManager.from(mContext)
                .getActiveSubscriptionInfoList();
        if (subInfoList != null) {
            mHasTelephony = true;
            mAirplaneModeBits = new BitSet(subInfoList.size());
            for (int i = 0; i < subInfoList.size(); i++) {
                final int finalI = i;
                PhoneStateListener subListener = new PhoneStateListener(subInfoList.get(finalI)
                        .getSubscriptionId()) {
                    @Override
                    public void onServiceStateChanged(ServiceState serviceState) {
                        final boolean inAirplaneMode = serviceState.getState()
                                == ServiceState.STATE_POWER_OFF;
                        mAirplaneModeBits.set(finalI, inAirplaneMode);

                        // we're in airplane mode if _any_ of the subscriptions say we are
                        mAirplaneState = mAirplaneModeBits.cardinality() > 0
                                ? ToggleAction.State.On : ToggleAction.State.Off;

                        mAirplaneModeOn.updateState(mAirplaneState);
                        if (mAdapter != null) {
                            mAdapter.notifyDataSetChanged();
                        }
                    }
                };
                mPhoneStateListeners.add(subListener);
                telephonyManager.listen(subListener, PhoneStateListener.LISTEN_SERVICE_STATE);
            }
        } else {
            mHasTelephony = false;
        }

        mDefaultMenuActions = mContext.getResources().getStringArray(
                com.android.internal.R.array.config_globalActionsList);
        mRebootMenuActions = mContext.getResources().getStringArray(
                com.android.internal.R.array.config_rebootActionsList);
        settingsChanged();
    }

    /**
     * Show the global actions dialog (creating if necessary)
     *
     * @param keyguardShowing True if keyguard is showing
     */
    public void showDialog(boolean keyguardShowing, boolean isDeviceProvisioned) {
        mKeyguardShowing = keyguardShowing;
        mDeviceProvisioned = isDeviceProvisioned;
        mRebootMenu = false;
        mCurrentMenuActions = mRootMenuActions;
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
            // Show delayed, so that the dismiss of the previous dialog completes
            mHandler.sendEmptyMessage(MESSAGE_SHOW);
        } else {
            handleShow();
        }
    }

    /**
     * Dismiss the global actions dialog, if it's currently shown
     */
    public void dismissDialog() {
        mHandler.removeMessages(MESSAGE_DISMISS);
        mHandler.sendEmptyMessage(MESSAGE_DISMISS);
    }

    private void awakenIfNecessary() {
        if (mDreamManager != null) {
            try {
                if (mDreamManager.isDreaming()) {
                    mDreamManager.awaken();
                }
            } catch (RemoteException e) {
                // we tried
            }
        }
    }

    private void handleShow() {
        awakenIfNecessary();
        mDialog = createDialog();
        prepareDialog();

        // If we only have 1 item and it's a simple press action, just do this action.
        if (mAdapter.getCount() == 1
                && mAdapter.getItem(0) instanceof SinglePressAction
                && !(mAdapter.getItem(0) instanceof LongPressAction)) {
            ((SinglePressAction) mAdapter.getItem(0)).onPress();
        } else {
            WindowManager.LayoutParams attrs = mDialog.getWindow().getAttributes();
            attrs.setTitle("ActionsDialog");
            attrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            mDialog.getWindow().setAttributes(attrs);
            mDialog.show();
            mWindowManagerFuncs.onGlobalActionsShown();
        }
    }

    public void settingsChanged() {
        final String globalAction = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.GLOBAL_ACTIONS_LIST, UserHandle.USER_CURRENT);

        if (globalAction != null) {
            mRootMenuActions = globalAction.split(",");
        } else {
            mRootMenuActions = mDefaultMenuActions;
        }
    }

    private boolean advancedRebootEnabled(Context context) {
        boolean advancedRebootEnabled = Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.ADVANCED_REBOOT, 1, UserHandle.USER_CURRENT) == 1;
        return advancedRebootEnabled;
    }

    private boolean isActionVisible(Action action) {
        if (mKeyguardShowing) {
            KeyguardManager km = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
            boolean locked = km.inKeyguardRestrictedInputMode() && km.isKeyguardSecure();

            if (!locked && !action.showDuringKeyguard()) {
                return false;
            }
            if (locked && !action.showDuringRestrictedKeyguard()) {
                return false;
            }
        }
        if (!mDeviceProvisioned && !action.showBeforeProvisioning()) {
            return false;
        }
        return true;
    }

    private List<Action> getCurrentRebootMenuItems() {
        List<Action> items = new ArrayList<Action>();

        String[] rebootMenuActions = mContext.getResources().getStringArray(
                    com.android.internal.R.array.config_rebootActionsList);
        for (int i = 0; i < rebootMenuActions.length; i++) {
            String actionKey = rebootMenuActions[i];
            if (advancedRebootEnabled(mContext) && GLOBAL_ACTION_KEY_REBOOT_RECOVERY.equals(actionKey)) {
                RebootRecoveryAction a = new RebootRecoveryAction();
                if (isActionVisible(a)) {
                    items.add(a);
                }
            } else if (advancedRebootEnabled(mContext) && GLOBAL_ACTION_KEY_REBOOT_BOOTLOADER.equals(actionKey)) {
                RebootBootloaderAction a = new RebootBootloaderAction();
                if (isActionVisible(a)) {
                    items.add(a);
                }
            } else if (advancedRebootEnabled(mContext) && GLOBAL_ACTION_KEY_REBOOT_SYSTEMUI.equals(actionKey)) {
                RebootSystemUIAction a = new RebootSystemUIAction();
                if (isActionVisible(a)) {
                    items.add(a);
                }
            }
        }
        return items;
    }

    private boolean showRebootSubmenu() {
        List<Action> rebootMenuItems = getCurrentRebootMenuItems();
        return rebootMenuItems.size() > 0;
    }

    private void buildMenuList() {
        mItems = new ArrayList<Action>();
        mSeparatedEmergencyButtonEnabled = false;

        ArraySet<String> addedKeys = new ArraySet<String>();
        for (int i = 0; i < mCurrentMenuActions.length; i++) {
            String actionKey = mCurrentMenuActions[i];
            if (addedKeys.contains(actionKey)) {
                // If we already have added this, don't add it again.
                continue;
            }
            if (GLOBAL_ACTION_KEY_POWER.equals(actionKey)) {
                if (Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.GLOBAL_ACTIONS_POWER, 1) == 1) {
                    mItems.add(new PowerAction());
                }
            } else if (GLOBAL_ACTION_KEY_AIRPLANE.equals(actionKey)) {
                if (Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.GLOBAL_ACTIONS_AIRPLANE, 0) == 1) {
                    mItems.add(mAirplaneModeOn);
                }
            } else if (GLOBAL_ACTION_KEY_BUGREPORT.equals(actionKey)) {
                if (Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.BUGREPORT_IN_POWER_MENU, 0) != 0 && isCurrentUserOwner()) {
                    mItems.add(new BugReportAction());
                }
            } else if (GLOBAL_ACTION_KEY_SILENT.equals(actionKey)) {
                if (Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.GLOBAL_ACTIONS_SOUNDPANEL, 0) == 1) {
                    mItems.add(mSilentModeAction);
                }
            } else if (GLOBAL_ACTION_KEY_USERS.equals(actionKey)) {
                if (Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.GLOBAL_ACTIONS_USERS, 0) == 1) {
                    addUsersToMenu(mItems);
                }
            } else if (GLOBAL_ACTION_KEY_SETTINGS.equals(actionKey)) {
                if (Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.GLOBAL_ACTIONS_SETTINGS, 0) != 0) {
                    mItems.add(getSettingsAction());
                }
            } else if (GLOBAL_ACTION_KEY_LOCKDOWN.equals(actionKey)) {
                if (Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.GLOBAL_ACTIONS_LOCKDOWN, 0) != 0) {
                    mItems.add(getLockdownAction());
                    mHasLockdownButton = true;
                }
            } else if (GLOBAL_ACTION_KEY_VOICEASSIST.equals(actionKey)) {
                mItems.add(getVoiceAssistAction());
            } else if (GLOBAL_ACTION_KEY_ASSIST.equals(actionKey)) {
                mItems.add(getAssistAction());
            } else if (GLOBAL_ACTION_KEY_RESTART.equals(actionKey)) {
                if (Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.GLOBAL_ACTIONS_RESTART, 1) == 1) {
                    mItems.add(new RestartAction());
                }
            } else if (advancedRebootEnabled(mContext) && GLOBAL_ACTION_KEY_REBOOT_RECOVERY.equals(actionKey)) {
                mItems.add(new RebootRecoveryAction());
            } else if (advancedRebootEnabled(mContext) && GLOBAL_ACTION_KEY_REBOOT_BOOTLOADER.equals(actionKey)) {
                mItems.add(new RebootBootloaderAction());
            } else if (advancedRebootEnabled(mContext) && GLOBAL_ACTION_KEY_REBOOT_SYSTEMUI.equals(actionKey)) {
                mItems.add(new RebootSystemUIAction());
            } else if (GLOBAL_ACTION_KEY_SCREENSHOT.equals(actionKey)) {
                if (Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.GLOBAL_ACTIONS_SCREENSHOT, 0) == 1) {
                    mItems.add(new ScreenshotAction());
                }
            } else if (GLOBAL_ACTION_KEY_SCREENRECORD.equals(actionKey)) {
                if (Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.GLOBAL_ACTIONS_SCREENRECORD, 0) == 1) {
                    mItems.add(getScreenrecordAction());
                }
            } else if (GLOBAL_ACTION_KEY_FLASHLIGHT.equals(actionKey)) {
                if (Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.GLOBAL_ACTIONS_FLASHLIGHT, 0) == 1) {
                    mItems.add(getFlashlightToggleAction());
                }
            } else if (GLOBAL_ACTION_KEY_EMERGENCY.equals(actionKey)) {
                if (mSeparatedEmergencyButtonEnabled
                        && !mEmergencyAffordanceManager.needsEmergencyAffordance()) {
                    mItems.add(new EmergencyDialerAction());
                }
            } else {
                Log.e(TAG, "Invalid global action key " + actionKey);
            }

            // Add here so we don't add more than one.
            addedKeys.add(actionKey);
        }

        if (mEmergencyAffordanceManager.needsEmergencyAffordance()) {
            mItems.add(getEmergencyAction());
        }
    }

    /**
     * Create the global actions dialog.
     *
     * @return A new dialog.
     */
    private ActionsDialog createDialog() {
        // Simple toggle style if there's no vibrator, otherwise use a tri-state
        if (!mHasVibrator) {
            mSilentModeAction = new SilentModeToggleAction();
        } else {
            mSilentModeAction = new SilentModeTriStateAction(mContext, mAudioManager, mHandler);
        }
        mAirplaneModeOn = new ToggleAction(
                R.drawable.ic_lock_airplane_mode,
                R.drawable.ic_lock_airplane_mode_off,
                R.string.global_actions_toggle_airplane_mode) {

            void onToggle(boolean on) {
                if (mHasTelephony && Boolean.parseBoolean(
                        SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE))) {
                    mIsWaitingForEcmExit = true;
                    // Launch ECM exit dialog
                    Intent ecmDialogIntent =
                            new Intent(TelephonyIntents.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS, null);
                    ecmDialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(ecmDialogIntent);
                } else {
                    changeAirplaneModeSystemSetting(on);
                }
            }

            @Override
            protected void changeStateFromPress(boolean buttonOn) {
                if (!mHasTelephony) return;

                // In ECM mode airplane state cannot be changed
                if (!(Boolean.parseBoolean(
                        SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE)))) {
                    mState = buttonOn ? State.TurningOn : State.TurningOff;
                    mAirplaneState = mState;
                }
            }

            public boolean showDuringKeyguard() {
                return true;
            }

            public boolean showBeforeProvisioning() {
                return false;
            }
        };
        onAirplaneModeChanged();

        buildMenuList();

        mAdapter = new MyAdapter();

        OnItemLongClickListener onItemLongClickListener = (parent, view, position, id) -> {
            final Action action = mAdapter.getItem(position);
            if (action instanceof LongPressAction) {
                mDialog.dismiss();
                return ((LongPressAction) action).onLongPress();
            }
            return false;
        };
        ActionsDialog dialog = new ActionsDialog(mContext, this, mAdapter, onItemLongClickListener,
                mSeparatedEmergencyButtonEnabled);
        dialog.setCanceledOnTouchOutside(false); // Handled by the custom class.
        dialog.setKeyguardShowing(mKeyguardShowing);

        dialog.setOnDismissListener(this);
        dialog.setOnShowListener(this);

        return dialog;
    }

    private boolean shouldDisplayLockdown() {
        int userId = getCurrentUser().id;
        // Lockdown is meaningless without a place to go.
        if (!mKeyguardManager.isDeviceSecure(userId)) {
            return false;
        }

        // Only show the lockdown button if the device isn't locked down (for whatever reason).
        int state = mLockPatternUtils.getStrongAuthForUser(userId);
        return (state == STRONG_AUTH_NOT_REQUIRED
                || state == SOME_AUTH_REQUIRED_AFTER_USER_REQUEST);
    }

    private final class PowerAction extends SinglePressAction implements LongPressAction {
        private PowerAction() {
            super(R.drawable.ic_lock_power_off,
                    R.string.global_action_power_off);
        }

        @Override
        public boolean onLongPress() {
            UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            if (!um.hasUserRestriction(UserManager.DISALLOW_SAFE_BOOT)) {
                mWindowManagerFuncs.reboot(true, null);
                return true;
            }
            return false;
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            // shutdown by making sure radio and power are handled accordingly.
            mWindowManagerFuncs.shutdown();
        }
    }

    private class EmergencyDialerAction extends SinglePressAction {
        private EmergencyDialerAction() {
            super(R.drawable.ic_faster_emergency,
                    R.string.global_action_emergency);
        }

        @Override
        public void onPress() {
            MetricsLogger.action(mContext, MetricsEvent.ACTION_EMERGENCY_DIALER_FROM_POWER_MENU);
            Intent intent = new Intent(EmergencyDialerConstants.ACTION_DIAL);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra(EmergencyDialerConstants.EXTRA_ENTRY_TYPE,
                    EmergencyDialerConstants.ENTRY_TYPE_POWER_MENU);
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }
    }

    private final class RestartAction extends SinglePressAction implements LongPressAction {
        private RestartAction() {
            super(R.drawable.ic_restart, com.android.systemui.R.string.global_action_reboot);
            if (mRebootMenu) {
                mMessageResId = com.android.systemui.R.string.global_action_reboot_sub;
            } else if (showRebootSubmenu() && advancedRebootEnabled(mContext)) {
                mMessageResId = com.android.systemui.R.string.global_action_reboot_more;
            }
        }

        @Override
        public boolean onLongPress() {
            UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            if (!um.hasUserRestriction(UserManager.DISALLOW_SAFE_BOOT)) {
                mWindowManagerFuncs.reboot(true, null);
                return true;
            }
            return false;
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            if (!mRebootMenu && showRebootSubmenu()) {
                mRebootMenu = true;
                mCurrentMenuActions = mRebootMenuActions;
                handleShow();
            } else {
                mHandler.sendEmptyMessage(MESSAGE_DISMISS);
                if (!mRebootMenu && !dismissDialogEnabled()) {
                    doShowAlert();
                } else {
                    doReboot();
                }
            }
        }

        private boolean dismissDialogEnabled() {
            boolean dismissDialogEnabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.GLOBAL_ACTION_DNAA, 0, UserHandle.USER_CURRENT) == 1;
            return dismissDialogEnabled;
        }

        private void doShowAlert() {
            AlertDialog.Builder b = new  AlertDialog.Builder(mContext);
            View content = LayoutInflater.from(mContext).inflate(
                    com.android.systemui.R.layout.global_action_dismissable_dialog, null);
            final CheckBox dontShowAgain = (CheckBox) content.findViewById(com.android.systemui.R.id.global_action_skip);
            b.setTitle(com.android.systemui.R.string.global_action_reboot);
            b.setView(content);
            b.setMessage(com.android.systemui.R.string.global_action_dismissable_dialog_text);
            b.setPositiveButton(R.string.dlg_ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Settings.Secure.putIntForUser(mContext.getContentResolver(),
                            Settings.Secure.GLOBAL_ACTION_DNAA, dontShowAgain.isChecked() ? 1 : 0,
                            UserHandle.USER_CURRENT);
                    dialog.dismiss();
                    doReboot();
                }
            });
            b.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            });
            b.setNeutralButton(com.android.systemui.R.string.global_action_settings, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    Intent globalActionSettings = new Intent();
                    globalActionSettings.setClassName("com.android.settings", "com.android.settings.Settings$GlobalActionsSettingsActivity");
                    globalActionSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(globalActionSettings);
                }
            });
            AlertDialog d = b.create();
            d.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            d.show();
        }

        private void doReboot() {
            mWindowManagerFuncs.reboot(false, null);
        }
    }

    private final class RebootRecoveryAction extends SinglePressAction {
        private RebootRecoveryAction() {
            super(com.android.systemui.R.drawable.ic_restart_recovery, com.android.systemui.R.string.global_action_reboot_recovery);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showDuringRestrictedKeyguard() {
            return false;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            mWindowManagerFuncs.reboot(false, PowerManager.REBOOT_RECOVERY);
        }
    }

    private final class RebootBootloaderAction extends SinglePressAction {
        private RebootBootloaderAction() {
            super(com.android.systemui.R.drawable.ic_restart_bootloader, com.android.systemui.R.string.global_action_reboot_bootloader);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showDuringRestrictedKeyguard() {
            return false;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            mWindowManagerFuncs.reboot(false, PowerManager.REBOOT_BOOTLOADER);
        }
    }

    private final class RebootSystemUIAction extends SinglePressAction {
        private RebootSystemUIAction() {
            super(com.android.systemui.R.drawable.ic_restart_systemui, com.android.systemui.R.string.global_action_reboot_systemui);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showDuringRestrictedKeyguard() {
            return false;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            restartSystemUI();
        }
    }

    private Action getFlashlightToggleAction() {
        return new SinglePressAction(com.android.systemui.R.drawable.ic_lock_flashlight,
                com.android.systemui.R.string.global_action_flashlight) {

            public void onPress() {
                try {
                    CameraManager cameraManager = (CameraManager)
                            mContext.getSystemService(Context.CAMERA_SERVICE);
                    for (final String cameraId : cameraManager.getCameraIdList()) {
                        CameraCharacteristics characteristics =
                            cameraManager.getCameraCharacteristics(cameraId);
                        int orient = characteristics.get(CameraCharacteristics.LENS_FACING);
                        if (orient == CameraCharacteristics.LENS_FACING_BACK) {
                            cameraManager.setTorchMode(cameraId, !mFlashlightEnabled);
                            mFlashlightEnabled = !mFlashlightEnabled;
                        }
                    }
                } catch (CameraAccessException e) {
                }
            }

            public boolean showDuringKeyguard() {
                return true;
            }

            public boolean showBeforeProvisioning() {
                return false;
            }
        };
    }

    private Action getScreenrecordAction() {
        return new SinglePressAction(com.android.systemui.R.drawable.ic_lock_screenrecord,
                com.android.systemui.R.string.global_action_screenrecord) {
            @Override
            public void onPress() {
                takeScreenrecord();
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private class ScreenshotAction extends SinglePressAction {
        public ScreenshotAction() {
            super(R.drawable.ic_screenshot, R.string.global_action_screenshot);
        }

        @Override
        public void onPress() {
            // Add a little delay before executing, to give the
            // dialog a chance to go away before it takes a
            // screenshot.
            // TODO: instead, omit global action dialog layer
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mScreenshotHelper.takeScreenshot(1, true, true, mHandler);
                    MetricsLogger.action(mContext,
                            MetricsEvent.ACTION_SCREENSHOT_POWER_MENU);
                }
            }, 500);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }
    }

    private class BugReportAction extends SinglePressAction implements LongPressAction {

        public BugReportAction() {
            super(R.drawable.ic_lock_bugreport, R.string.bugreport_title);
        }

        @Override
        public void onPress() {
            // don't actually trigger the bugreport if we are running stability
            // tests via monkey
            if (ActivityManager.isUserAMonkey()) {
                return;
            }
            // Add a little delay before executing, to give the
            // dialog a chance to go away before it takes a
            // screenshot.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Take an "interactive" bugreport.
                        MetricsLogger.action(mContext,
                                MetricsEvent.ACTION_BUGREPORT_FROM_POWER_MENU_INTERACTIVE);
                        ActivityManager.getService().requestBugReport(
                                ActivityManager.BUGREPORT_OPTION_INTERACTIVE);
                    } catch (RemoteException e) {
                    }
                }
            }, 500);
        }

        @Override
        public boolean onLongPress() {
            // don't actually trigger the bugreport if we are running stability
            // tests via monkey
            if (ActivityManager.isUserAMonkey()) {
                return false;
            }
            try {
                // Take a "full" bugreport.
                MetricsLogger.action(mContext, MetricsEvent.ACTION_BUGREPORT_FROM_POWER_MENU_FULL);
                ActivityManager.getService().requestBugReport(
                        ActivityManager.BUGREPORT_OPTION_FULL);
            } catch (RemoteException e) {
            }
            return false;
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }

        @Override
        public String getStatus() {
            return mContext.getString(
                    R.string.bugreport_status,
                    Build.VERSION.RELEASE,
                    Build.ID);
        }
    }

    private final class LogoutAction extends SinglePressAction {
        private LogoutAction() {
            super(R.drawable.ic_logout, R.string.global_action_logout);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }

        @Override
        public void onPress() {
            // Add a little delay before executing, to give the dialog a chance to go away before
            // switching user
            mHandler.postDelayed(() -> {
                try {
                    int currentUserId = getCurrentUser().id;
                    ActivityManager.getService().switchUser(UserHandle.USER_SYSTEM);
                    ActivityManager.getService().stopUser(currentUserId, true /*force*/, null);
                } catch (RemoteException re) {
                    Log.e(TAG, "Couldn't logout user " + re);
                }
            }, 500);
        }
    }

    private Action getSettingsAction() {
        return new SinglePressAction(com.android.systemui.R.drawable.ic_lock_settings,
                R.string.global_action_settings) {

            @Override
            public void onPress() {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivity(intent);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private Action getEmergencyAction() {
        Drawable emergencyIcon = mContext.getDrawable(R.drawable.emergency_icon);
        if(!mSeparatedEmergencyButtonEnabled) {
            // use un-colored legacy treatment
            emergencyIcon.setTintList(null);
        }

        return new SinglePressAction(R.drawable.emergency_icon,
                R.string.global_action_emergency) {
            @Override
            public void onPress() {
                mEmergencyAffordanceManager.performEmergencyCall();
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private Action getAssistAction() {
        return new SinglePressAction(R.drawable.ic_action_assist_focused,
                R.string.global_action_assist) {
            @Override
            public void onPress() {
                Intent intent = new Intent(Intent.ACTION_ASSIST);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivity(intent);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private Action getVoiceAssistAction() {
        return new SinglePressAction(R.drawable.ic_voice_search,
                R.string.global_action_voice_assist) {
            @Override
            public void onPress() {
                Intent intent = new Intent(Intent.ACTION_VOICE_ASSIST);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivity(intent);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private Action getLockdownAction() {
        return new SinglePressAction(com.android.systemui.R.drawable.ic_lock_lock,
                R.string.global_action_lockdown) {

            @Override
            public void onPress() {
                new LockPatternUtils(mContext)
                        .requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN,
                                UserHandle.USER_ALL);
                try {
                    WindowManagerGlobal.getWindowManagerService().lockNow(null);
                    // Lock profiles (if any) on the background thread.
                    final Handler bgHandler = new Handler(Dependency.get(Dependency.BG_LOOPER));
                    bgHandler.post(() -> lockProfiles());
                } catch (RemoteException e) {
                    Log.e(TAG, "Error while trying to lock device.", e);
                }
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return false;
            }
        };
    }

    private void lockProfiles() {
        final UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        final TrustManager tm = (TrustManager) mContext.getSystemService(Context.TRUST_SERVICE);
        final int currentUserId = getCurrentUser().id;
        final int[] profileIds = um.getEnabledProfileIds(currentUserId);
        for (final int id : profileIds) {
            if (id != currentUserId) {
                tm.setDeviceLockedForUser(id, true);
            }
        }
    }

    private UserInfo getCurrentUser() {
        try {
            return ActivityManager.getService().getCurrentUser();
        } catch (RemoteException re) {
            return null;
        }
    }

    private boolean isCurrentUserOwner() {
        UserInfo currentUser = getCurrentUser();
        return currentUser == null || currentUser.isPrimary();
    }

    private void addUsersToMenu(ArrayList<Action> items) {
        UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        if (um.isUserSwitcherEnabled()) {
            List<UserInfo> users = um.getUsers();
            UserInfo currentUser = getCurrentUser();
            for (final UserInfo user : users) {
                if (user.supportsSwitchToByUser()) {
                    boolean isCurrentUser = currentUser == null
                            ? user.id == 0 : (currentUser.id == user.id);
                    Drawable icon = user.iconPath != null ? Drawable.createFromPath(user.iconPath)
                            : null;
                    SinglePressAction switchToUser = new SinglePressAction(
                            R.drawable.ic_menu_cc, icon,
                            (user.name != null ? user.name : "Primary")
                                    + (isCurrentUser ? " \u2714" : "")) {
                        public void onPress() {
                            try {
                                ActivityManager.getService().switchUser(user.id);
                            } catch (RemoteException re) {
                                Log.e(TAG, "Couldn't switch user " + re);
                            }
                        }

                        public boolean showDuringKeyguard() {
                            return true;
                        }

                        public boolean showBeforeProvisioning() {
                            return false;
                        }
                    };
                    items.add(switchToUser);
                }
            }
        }
    }

    /**
     * functions needed for taking screen record.
     */
    final Object mScreenrecordLock = new Object();
    ServiceConnection mScreenrecordConnection = null;

    final Runnable mScreenrecordTimeout = new Runnable() {
        @Override public void run() {
            synchronized (mScreenrecordLock) {
                if (mScreenrecordConnection != null) {
                    mContext.unbindService(mScreenrecordConnection);
                    mScreenrecordConnection = null;
                }
            }
        }
    };

    private void takeScreenrecord() {
       synchronized (mScreenrecordLock) {
            if (mScreenrecordConnection != null) {
                return;
            }
            ComponentName cn = new ComponentName("com.android.systemui",
                    "com.android.systemui.benzo.screenrecord.TakeScreenrecordService");
            Intent intent = new Intent();
            intent.setComponent(cn);
            ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    synchronized (mScreenrecordLock) {
                        Messenger messenger = new Messenger(service);
                        Message msg = Message.obtain(null, 1);
                        final ServiceConnection myConn = this;
                        Handler h = new Handler(mHandler.getLooper()) {
                            @Override
                            public void handleMessage(Message msg) {
                                synchronized (mScreenrecordLock) {
                                    if (mScreenrecordConnection == myConn) {
                                        mContext.unbindService(mScreenrecordConnection);
                                        mScreenrecordConnection = null;
                                        mHandler.removeCallbacks(mScreenrecordTimeout);
                                    }
                                }
                            }
                        };
                        msg.replyTo = new Messenger(h);
                        msg.arg1 = msg.arg2 = 0;
                        try {
                            messenger.send(msg);
                        } catch (RemoteException e) {
                        }
                    }
                }
                @Override
                public void onServiceDisconnected(ComponentName name) {}
            };
            if (mContext.bindServiceAsUser(
                    intent, conn, Context.BIND_AUTO_CREATE, UserHandle.CURRENT)) {
                mScreenrecordConnection = conn;
                mHandler.postDelayed(mScreenrecordTimeout, 31 * 60 * 1000);
            }
        }
    }

    private void prepareDialog() {
        refreshSilentMode();
        mAirplaneModeOn.updateState(mAirplaneState);
        mAdapter.notifyDataSetChanged();
        if (mShowSilentToggle) {
            IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
            mContext.registerReceiver(mRingerModeReceiver, filter);
        }
    }

    private void refreshSilentMode() {
        if (!mHasVibrator) {
            final boolean silentModeOn =
                    mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
            ((ToggleAction) mSilentModeAction).updateState(
                    silentModeOn ? ToggleAction.State.On : ToggleAction.State.Off);
        }
    }

    /** {@inheritDoc} */
    public void onDismiss(DialogInterface dialog) {
        mWindowManagerFuncs.onGlobalActionsHidden();
        if (mShowSilentToggle) {
            try {
                mContext.unregisterReceiver(mRingerModeReceiver);
            } catch (IllegalArgumentException ie) {
                // ignore this
                Log.w(TAG, ie);
            }
        }
    }

    /** {@inheritDoc} */
    public void onClick(DialogInterface dialog, int which) {
        Action item = mAdapter.getItem(which);
        if (!(item instanceof SilentModeTriStateAction)) {
            dialog.dismiss();
        }
        item.onPress();
    }

    /** {@inheritDoc} */
    public void onShow(DialogInterface dialog) {
        MetricsLogger.visible(mContext, MetricsEvent.POWER_MENU);
    }

    /**
     * The adapter used for the list within the global actions dialog, taking
     * into account whether the keyguard is showing via
     * {@link com.android.systemui.globalactions.GlobalActionsDialog#mKeyguardShowing} and whether
     * the device is provisioned
     * via {@link com.android.systemui.globalactions.GlobalActionsDialog#mDeviceProvisioned}.
     */
    private class MyAdapter extends BaseAdapter {

        public int getCount() {
            int count = 0;

            for (int i = 0; i < mItems.size(); i++) {
                final Action action = mItems.get(i);

                if (mKeyguardShowing && !action.showDuringKeyguard()) {
                    continue;
                }
                if (!mDeviceProvisioned && !action.showBeforeProvisioning()) {
                    continue;
                }
                count++;
            }
            return count;
        }

        @Override
        public boolean isEnabled(int position) {
            return getItem(position).isEnabled();
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        public Action getItem(int position) {

            int filteredPos = 0;
            for (int i = 0; i < mItems.size(); i++) {
                final Action action = mItems.get(i);
                if (mKeyguardShowing && !action.showDuringKeyguard()) {
                    continue;
                }
                if (!mDeviceProvisioned && !action.showBeforeProvisioning()) {
                    continue;
                }
                if (filteredPos == position) {
                    return action;
                }
                filteredPos++;
            }

            throw new IllegalArgumentException("position " + position
                    + " out of range of showable actions"
                    + ", filtered count=" + getCount()
                    + ", keyguardshowing=" + mKeyguardShowing
                    + ", provisioned=" + mDeviceProvisioned);
        }


        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            Action action = getItem(position);
            View view = action.create(mContext, convertView, parent, LayoutInflater.from(mContext));
            // Everything but screenshot, the last item, gets white background.
            /*if (position == 2) {
                HardwareUiLayout.get(parent).setDivisionView(view);
            }*/
            return view;
        }
    }

    // note: the scheme below made more sense when we were planning on having
    // 8 different things in the global actions dialog.  seems overkill with
    // only 3 items now, but may as well keep this flexible approach so it will
    // be easy should someone decide at the last minute to include something
    // else, such as 'enable wifi', or 'enable bluetooth'

    /**
     * What each item in the global actions dialog must be able to support.
     */
    private interface Action {
        /**
         * @return Text that will be announced when dialog is created.  null
         * for none.
         */
        CharSequence getLabelForAccessibility(Context context);

        View create(Context context, View convertView, ViewGroup parent, LayoutInflater inflater);

        void onPress();

        /**
         * @return whether this action should appear in the dialog when the keygaurd
         * is showing.
         */
        boolean showDuringKeyguard();

        /**
         * @return whether this action should appear in the dialog when a restricted
         * keyguard is showing.
         */
        default boolean showDuringRestrictedKeyguard() {
            return true;
        }

        /**
         * @return whether this action should appear in the dialog before the
         * device is provisioned.
         */
        boolean showBeforeProvisioning();

        boolean isEnabled();
    }

    /**
     * An action that also supports long press.
     */
    private interface LongPressAction extends Action {
        boolean onLongPress();
    }

    /**
     * A single press action maintains no state, just responds to a press
     * and takes an action.
     */
    private static abstract class SinglePressAction implements Action {
        private final int mIconResId;
        private final Drawable mIcon;
        protected int mMessageResId;
        private final CharSequence mMessage;

        protected SinglePressAction(int iconResId, int messageResId) {
            mIconResId = iconResId;
            mMessageResId = messageResId;
            mMessage = null;
            mIcon = null;
        }

        protected SinglePressAction(int iconResId, Drawable icon, CharSequence message) {
            mIconResId = iconResId;
            mMessageResId = 0;
            mMessage = message;
            mIcon = icon;
        }

        public boolean isEnabled() {
            return true;
        }

        public String getStatus() {
            return null;
        }

        abstract public void onPress();

        public CharSequence getLabelForAccessibility(Context context) {
            if (mMessage != null) {
                return mMessage;
            } else {
                return context.getString(mMessageResId);
            }
        }

        public View create(
                Context context, View convertView, ViewGroup parent, LayoutInflater inflater) {
            View v = inflater.inflate(com.android.systemui.R.layout.global_actions_item, parent,
                    false);

            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView messageView = (TextView) v.findViewById(R.id.message);

            TextView statusView = (TextView) v.findViewById(R.id.status);
            final String status = getStatus();
            if (!TextUtils.isEmpty(status)) {
                statusView.setText(status);
            } else {
                statusView.setVisibility(View.GONE);
            }
            if (mIcon != null) {
                icon.setImageDrawable(mIcon);
                icon.setScaleType(ScaleType.CENTER_CROP);
            } else if (mIconResId != 0) {
                icon.setImageDrawable(context.getDrawable(mIconResId));
            }
            if (mMessage != null) {
                messageView.setText(mMessage);
            } else {
                messageView.setText(mMessageResId);
            }

            return v;
        }
    }

    /**
     * A toggle action knows whether it is on or off, and displays an icon
     * and status message accordingly.
     */
    private static abstract class ToggleAction implements Action {

        enum State {
            Off(false),
            TurningOn(true),
            TurningOff(true),
            On(false);

            private final boolean inTransition;

            State(boolean intermediate) {
                inTransition = intermediate;
            }

            public boolean inTransition() {
                return inTransition;
            }
        }

        public String getStatus() {
            return null;
        }

        protected State mState = State.Off;

        // prefs
        protected int mEnabledIconResId;
        protected int mDisabledIconResid;
        protected int mMessageResId;

        /**
         * @param enabledIconResId           The icon for when this action is on.
         * @param disabledIconResid          The icon for when this action is off.
         * @param message                    The general information message, e.g 'Silent Mode'
         */
        public ToggleAction(int enabledIconResId, int disabledIconResid, int message) {
            mEnabledIconResId = enabledIconResId;
            mDisabledIconResid = disabledIconResid;
            mMessageResId = message;
        }

        /**
         * Override to make changes to resource IDs just before creating the
         * View.
         */
        void willCreate() {

        }

        @Override
        public CharSequence getLabelForAccessibility(Context context) {
            return context.getString(mMessageResId);
        }

        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            willCreate();

            View v = inflater.inflate(R
                    .layout.global_actions_item, parent, false);

            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView messageView = (TextView) v.findViewById(R.id.message);
            TextView statusView = (TextView) v.findViewById(R.id.status);
            final boolean enabled = isEnabled();

            if (messageView != null) {
                messageView.setText(mMessageResId);
                messageView.setEnabled(enabled);
            }

            boolean on = ((mState == State.On) || (mState == State.TurningOn));
            if (icon != null) {
                icon.setImageDrawable(context.getDrawable(
                        (on ? mEnabledIconResId : mDisabledIconResid)));
                icon.setEnabled(enabled);
            }

            final String status = getStatus();
            if (!TextUtils.isEmpty(status)) {
                statusView.setText(status);
            } else {
                statusView.setVisibility(View.GONE);
            v.setEnabled(enabled);
            }

            return v;
        }

        public final void onPress() {
            if (mState.inTransition()) {
                Log.w(TAG, "shouldn't be able to toggle when in transition");
                return;
            }

            final boolean nowOn = !(mState == State.On);
            onToggle(nowOn);
            changeStateFromPress(nowOn);
        }

        public boolean isEnabled() {
            return !mState.inTransition();
        }

        /**
         * Implementations may override this if their state can be in on of the intermediate
         * states until some notification is received (e.g airplane mode is 'turning off' until
         * we know the wireless connections are back online
         *
         * @param buttonOn Whether the button was turned on or off
         */
        protected void changeStateFromPress(boolean buttonOn) {
            mState = buttonOn ? State.On : State.Off;
        }

        abstract void onToggle(boolean on);

        public void updateState(State state) {
            mState = state;
        }
    }

    private class SilentModeToggleAction extends ToggleAction {
        public SilentModeToggleAction() {
            super(R.drawable.ic_audio_vol_mute,
                    R.drawable.ic_audio_vol,
                    R.string.global_action_toggle_silent_mode);
        }

        void onToggle(boolean on) {
            if (on) {
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            } else {
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            }
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        public boolean showBeforeProvisioning() {
            return false;
        }
    }

    private static class SilentModeTriStateAction implements Action, View.OnClickListener {

        private final int[] ITEM_IDS = {R.id.option1, R.id.option2, R.id.option3};

        private final AudioManager mAudioManager;
        private final Handler mHandler;
        private final Context mContext;

        SilentModeTriStateAction(Context context, AudioManager audioManager, Handler handler) {
            mAudioManager = audioManager;
            mHandler = handler;
            mContext = context;
        }

        private int ringerModeToIndex(int ringerMode) {
            // They just happen to coincide
            return ringerMode;
        }

        private int indexToRingerMode(int index) {
            // They just happen to coincide
            return index;
        }

        @Override
        public CharSequence getLabelForAccessibility(Context context) {
            return null;
        }

        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            View v = inflater.inflate(R.layout.global_actions_silent_mode, parent, false);

            int selectedIndex = ringerModeToIndex(mAudioManager.getRingerMode());
            for (int i = 0; i < 3; i++) {
                View itemView = v.findViewById(ITEM_IDS[i]);
                itemView.setSelected(selectedIndex == i);
                // Set up click handler
                itemView.setTag(i);
                itemView.setOnClickListener(this);
            }
            return v;
        }

        public void onPress() {
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        public boolean showBeforeProvisioning() {
            return false;
        }

        public boolean isEnabled() {
            return true;
        }

        void willCreate() {
        }

        public void onClick(View v) {
            if (!(v.getTag() instanceof Integer)) return;

            int index = (Integer) v.getTag();
            mAudioManager.setRingerMode(indexToRingerMode(index));
            mHandler.sendEmptyMessageDelayed(MESSAGE_DISMISS, DIALOG_DISMISS_DELAY);
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                String reason = intent.getStringExtra(SYSTEM_DIALOG_REASON_KEY);
                if (!SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS.equals(reason)) {
                    mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_DISMISS, reason));
                }
            } else if (TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED.equals(action)) {
                // Airplane mode can be changed after ECM exits if airplane toggle button
                // is pressed during ECM mode
                if (!(intent.getBooleanExtra("PHONE_IN_ECM_STATE", false)) &&
                        mIsWaitingForEcmExit) {
                    mIsWaitingForEcmExit = false;
                    changeAirplaneModeSystemSetting(true);
                }
            }
        }
    };

    private BroadcastReceiver mRingerModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                mHandler.sendEmptyMessage(MESSAGE_REFRESH);
            }
        }
    };

    private ContentObserver mAirplaneModeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            onAirplaneModeChanged();
        }
    };

    private static final int MESSAGE_DISMISS = 0;
    private static final int MESSAGE_REFRESH = 1;
    private static final int MESSAGE_SHOW = 2;
    private static final int DIALOG_DISMISS_DELAY = 300; // ms

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_DISMISS:
                    if (mDialog != null) {
                        if (SYSTEM_DIALOG_REASON_DREAM.equals(msg.obj)) {
                            mDialog.dismissImmediately();
                        } else {
                            mDialog.dismiss();
                        }
                        mDialog = null;
                    }
                    break;
                case MESSAGE_REFRESH:
                    refreshSilentMode();
                    mAdapter.notifyDataSetChanged();
                    break;
                case MESSAGE_SHOW:
                    handleShow();
                    break;
            }
        }
    };

    private ToggleAction.State getUpdatedAirplaneToggleState() {
        return (Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) == 1) ?
                ToggleAction.State.On : ToggleAction.State.Off;
    }

    private void onAirplaneModeChanged() {
        // Let the service state callbacks handle the state.
        if (mHasTelephony) return;

        mAirplaneState = getUpdatedAirplaneToggleState();
        mAirplaneModeOn.updateState(mAirplaneState);
    }

    /**
     * Change the airplane mode system setting
     */
    private void changeAirplaneModeSystemSetting(boolean on) {
        Settings.Global.putInt(
                mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON,
                on ? 1 : 0);
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("state", on);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        if (!mHasTelephony) {
            mAirplaneState = on ? ToggleAction.State.On : ToggleAction.State.Off;
        }
    }

    private static final class ActionsDialog extends Dialog implements DialogInterface,
            ColorExtractor.OnColorsChangedListener {

        private final Context mContext;
        private final MyAdapter mAdapter;
        private final LinearLayout mListView;
        private final FrameLayout mSeparatedView;
        private final HardwareUiLayout mHardwareLayout;
        private final OnClickListener mClickListener;
        private final OnItemLongClickListener mLongClickListener;
        private final GradientDrawable mGradientDrawable;
        private final ColorExtractor mColorExtractor;
        private boolean mKeyguardShowing;
        private boolean mShouldDisplaySeparatedButton;

        public ActionsDialog(Context context, OnClickListener clickListener, MyAdapter adapter,
                OnItemLongClickListener longClickListener, boolean shouldDisplaySeparatedButton) {
            super(context, com.android.systemui.R.style.Theme_SystemUI_Dialog_GlobalActions);
            mContext = context;
            mAdapter = adapter;
            mClickListener = clickListener;
            mLongClickListener = longClickListener;
            mGradientDrawable = new GradientDrawable(mContext);
            mColorExtractor = Dependency.get(SysuiColorExtractor.class);
            mShouldDisplaySeparatedButton = shouldDisplaySeparatedButton;

            // Window initialization
            Window window = getWindow();
            window.requestFeature(Window.FEATURE_NO_TITLE);
            // Inflate the decor view, so the attributes below are not overwritten by the theme.
            window.getDecorView();
            window.getAttributes().systemUiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            window.setLayout(MATCH_PARENT, MATCH_PARENT);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            window.setBackgroundDrawable(mGradientDrawable);
            window.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);

            setContentView(com.android.systemui.R.layout.global_actions_wrapped);
            mListView = findViewById(android.R.id.list);
            mSeparatedView = findViewById(com.android.systemui.R.id.separated_button);
            if (!mShouldDisplaySeparatedButton) {
                mSeparatedView.setVisibility(View.GONE);
            }
            mHardwareLayout = HardwareUiLayout.get(mListView);
            mHardwareLayout.setOutsideTouchListener(view -> dismiss());
            mHardwareLayout.setHasSeparatedButton(mShouldDisplaySeparatedButton);
            setTitle(R.string.global_actions);
            mListView.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public boolean dispatchPopulateAccessibilityEvent(
                        View host, AccessibilityEvent event) {
                    // Populate the title here, just as Activity does
                    event.getText().add(mContext.getString(R.string.global_actions));
                    return true;
                }
            });
        }

        private void updateList() {
            mListView.removeAllViews();
            mSeparatedView.removeAllViews();
            for (int i = 0; i < mAdapter.getCount(); i++) {
                ViewGroup parentView = mShouldDisplaySeparatedButton && i == mAdapter.getCount() - 1
                        ? mSeparatedView : mListView;
                View v = mAdapter.getView(i, null, parentView);
                final int pos = i;
                v.setOnClickListener(view -> mClickListener.onClick(this, pos));
                v.setOnLongClickListener(view ->
                        mLongClickListener.onItemLongClick(null, v, pos, 0));
                parentView.addView(v);
            }
        }

        @Override
        protected void onStart() {
            super.setCanceledOnTouchOutside(true);
            super.onStart();
            updateList();

            Point displaySize = new Point();
            mContext.getDisplay().getRealSize(displaySize);
            mColorExtractor.addOnColorsChangedListener(this);
            mGradientDrawable.setScreenSize(displaySize.x, displaySize.y);
            GradientColors colors = mColorExtractor.getColors(mKeyguardShowing ?
                    WallpaperManager.FLAG_LOCK : WallpaperManager.FLAG_SYSTEM);
            updateColors(colors, false /* animate */);
        }

        /**
         * Updates background and system bars according to current GradientColors.
         * @param colors Colors and hints to use.
         * @param animate Interpolates gradient if true, just sets otherwise.
         */
        private void updateColors(GradientColors colors, boolean animate) {
            mGradientDrawable.setColors(colors, animate);
            View decorView = getWindow().getDecorView();
            if (colors.supportsDarkText()) {
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR |
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            } else {
                decorView.setSystemUiVisibility(0);
            }
        }

        @Override
        protected void onStop() {
            super.onStop();
            mColorExtractor.removeOnColorsChangedListener(this);
        }

        @Override
        public void show() {
            super.show();
            mGradientDrawable.setAlpha(0);
            mHardwareLayout.setTranslationX(getAnimTranslation());
            mHardwareLayout.setAlpha(0);
            mHardwareLayout.animate()
                    .alpha(1)
                    .translationX(0)
                    .setDuration(300)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .setUpdateListener(animation -> {
                        int alpha = (int) ((Float) animation.getAnimatedValue()
                                * ScrimController.GRADIENT_SCRIM_ALPHA * 255);
                        mGradientDrawable.setAlpha(alpha);
                    })
                    .withEndAction(() -> getWindow().getDecorView().requestAccessibilityFocus())
                    .start();
        }

        @Override
        public void dismiss() {
            // Remove outside touch listener to avoid multiple dismiss calls.
            mHardwareLayout.setOutsideTouchListener(null);
            mHardwareLayout.setTranslationX(0);
            mHardwareLayout.setAlpha(1);
            mHardwareLayout.animate()
                    .alpha(0)
                    .translationX(getAnimTranslation())
                    .setDuration(300)
                    .withEndAction(() -> super.dismiss())
                    .setInterpolator(new LogAccelerateInterpolator())
                    .setUpdateListener(animation -> {
                        int alpha = (int) ((1f - (Float) animation.getAnimatedValue())
                                * ScrimController.GRADIENT_SCRIM_ALPHA * 255);
                        mGradientDrawable.setAlpha(alpha);
                    })
                    .start();
        }

        void dismissImmediately() {
            super.dismiss();
        }

        private float getAnimTranslation() {
            return getContext().getResources().getDimension(
                    com.android.systemui.R.dimen.global_actions_panel_width) / 2;
        }

        @Override
        public void onColorsChanged(ColorExtractor extractor, int which) {
            if (mKeyguardShowing) {
                if ((WallpaperManager.FLAG_LOCK & which) != 0) {
                    updateColors(extractor.getColors(WallpaperManager.FLAG_LOCK),
                            true /* animate */);
                }
            } else {
                if ((WallpaperManager.FLAG_SYSTEM & which) != 0) {
                    updateColors(extractor.getColors(WallpaperManager.FLAG_SYSTEM),
                            true /* animate */);
                }
            }
        }

        public void setKeyguardShowing(boolean keyguardShowing) {
            mKeyguardShowing = keyguardShowing;
        }
    }
    public static void restartSystemUI() {
        Process.killProcess(Process.myPid());
    }
}
