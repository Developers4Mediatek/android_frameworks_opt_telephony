/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_DEFAULT_SUBSCRIPTION;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.LocalLog;

import com.android.internal.telephony.cdma.CDMALTEPhone;
import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.cdma.CdmaSubscriptionSourceManager;
import com.android.internal.telephony.dataconnection.DctController;
import com.android.internal.telephony.gsm.GSMPhone;
import com.android.internal.telephony.SubscriptionInfoUpdater;
import com.android.internal.telephony.imsphone.ImsPhone;
import com.android.internal.telephony.imsphone.ImsPhoneFactory;
import com.android.internal.telephony.sip.SipPhone;
import com.android.internal.telephony.sip.SipPhoneFactory;
import com.android.internal.telephony.uicc.IccCardProxy;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.util.IndentingPrintWriter;

import com.mediatek.internal.telephony.NetworkManager;
import com.mediatek.internal.telephony.RadioManager;
import com.mediatek.internal.telephony.cdma.CdmaFeatureOptionUtils;
// import com.mediatek.internal.telephony.ltedc.LteDcPhoneProxy;
// import com.mediatek.internal.telephony.ltedc.svlte.SvlteDcPhone;
// import com.mediatek.internal.telephony.ltedc.svlte.SvlteModeController;
// import com.mediatek.internal.telephony.ltedc.svlte.SvltePhoneProxy;
// import com.mediatek.internal.telephony.ltedc.svlte.SvlteRoamingController;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteUtils;
// import com.mediatek.internal.telephony.uicc.SvlteUiccController;
import com.mediatek.internal.telephony.worldphone.IWorldPhone;
import com.mediatek.internal.telephony.worldphone.WorldPhoneUtil;
import com.mediatek.internal.telephony.worldphone.WorldPhoneWrapper;
import com.mediatek.internal.telephony.worldphone.WorldMode;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.lang.reflect.Constructor;

/**
 * {@hide}
 */
public class PhoneFactory {
    static final String LOG_TAG = "PhoneFactory";
    static final int SOCKET_OPEN_RETRY_MILLIS = 2 * 1000;
    static final int SOCKET_OPEN_MAX_RETRY = 3;
    static final boolean DBG = false;

    //***** Class Variables

    // lock sLockProxyPhones protects both sProxyPhones and sProxyPhone
    final static Object sLockProxyPhones = new Object();
    static private PhoneProxy[] sProxyPhones = null;
    static private PhoneProxy sProxyPhone = null;

    static private CommandsInterface[] sCommandsInterfaces = null;

    static private ProxyController mProxyController;
    static private UiccController mUiccController;

    static private CommandsInterface sCommandsInterface = null;
    static private SubscriptionInfoUpdater sSubInfoRecordUpdater = null;

    static private boolean sMadeDefaults = false;
    static private PhoneNotifier sPhoneNotifier;
    static private Context sContext;

    static private final HashMap<String, LocalLog>sLocalLogs = new HashMap<String, LocalLog>();

    // MTK
    // static private SvlteUiccController sSvlteUiccController;  // MTK TODO
    // MTK-END, Refine SVLTE remote SIM APP type, 2015/04/29
    //MTK-START [mtk06800]  RadioManager for proprietary power on flow
    static private RadioManager mRadioManager;
    //MTK-END [mtk06800]  RadioManager for proprietary power on flow
    static private NetworkManager mNetworkManager;

    static private IWorldPhone sWorldPhone = null;

    /* C2K support start */
    static final String EVDO_DT_SUPPORT = "ril.evdo.dtsupport";

    // MTK TODO
    /*
    // SVLTE RIL instance
    static private CommandsInterface[] sCommandsInterfaceLteDcs;
    // SVLTE LTE dual connection PhoneProxy
    static private LteDcPhoneProxy[] sLteDcPhoneProxys;
    static private int sActiveSvlteModeSlotId;
    */
    /* C2K support end */

    //***** Class Methods

    public static void makeDefaultPhones(Context context) {
        makeDefaultPhone(context);
    }

    /**
     * FIXME replace this with some other way of making these
     * instances
     */
    public static void makeDefaultPhone(Context context) {
        synchronized (sLockProxyPhones) {
            if (!sMadeDefaults) {
                sContext = context;

                // create the telephony device controller.
                TelephonyDevController.create();

                int retryCount = 0;
                for(;;) {
                    boolean hasException = false;
                    retryCount ++;

                    try {
                        // use UNIX domain socket to
                        // prevent subsequent initialization
                        new LocalServerSocket("com.android.internal.telephony");
                    } catch (java.io.IOException ex) {
                        hasException = true;
                    }

                    if ( !hasException ) {
                        break;
                    } else if (retryCount > SOCKET_OPEN_MAX_RETRY) {
                        throw new RuntimeException("PhoneFactory probably already running");
                    } else {
                        try {
                            Thread.sleep(SOCKET_OPEN_RETRY_MILLIS);
                        } catch (InterruptedException er) {
                        }
                    }
                }

                sPhoneNotifier = new DefaultPhoneNotifier();

                // Get preferred network mode
                int preferredNetworkMode = RILConstants.PREFERRED_NETWORK_MODE;
                if (TelephonyManager.getLteOnCdmaModeStatic() == PhoneConstants.LTE_ON_CDMA_TRUE) {
                    preferredNetworkMode = Phone.NT_MODE_GLOBAL;
                }
                if (TelephonyManager.getLteOnGsmModeStatic() != 0) {
                    preferredNetworkMode = Phone.NT_MODE_LTE_GSM_WCDMA;
                }

                int cdmaSubscription = CdmaSubscriptionSourceManager.getDefault(context);
                Rlog.i(LOG_TAG, "Cdma Subscription set to " + cdmaSubscription);

                /* In case of multi SIM mode two instances of PhoneProxy, RIL are created,
                   where as in single SIM mode only instance. isMultiSimEnabled() function checks
                   whether it is single SIM or multi SIM mode */
                int numPhones = TelephonyManager.getDefault().getPhoneCount();
                int[] networkModes = new int[numPhones];
                sProxyPhones = new PhoneProxy[numPhones];
                sCommandsInterfaces = new RIL[numPhones];
                /// M: SVLTE solution2 modify, expand to object array
                /// and get active svlte mode slot id. @{
                // MTK TODO
                /*
                if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
                    sLteDcPhoneProxys = new SvltePhoneProxy[numPhones];
                    sCommandsInterfaceLteDcs = new CommandsInterface[numPhones];
                    sActiveSvlteModeSlotId = SvlteModeController.getActiveSvlteModeSlotId();
                    SvlteModeController.setCdmaSocketSlotId(sActiveSvlteModeSlotId
                            == SvlteModeController.CSFB_ON_SLOT
                            ? PhoneConstants.SIM_ID_1 : sActiveSvlteModeSlotId);
                }
                */
                /// @}
                //[ALPS01784188]
                int capabilityPhoneId = Integer.valueOf(
                        SystemProperties.get(PhoneConstants.PROPERTY_CAPABILITY_SWITCH, "1"));

                String sRILClassname = SystemProperties.get("ro.telephony.ril_class", "RIL").trim();
                Rlog.i(LOG_TAG, "RILClassname is " + sRILClassname);

                for (int i = 0; i < numPhones; i++) {
                    // reads the system properties and makes commandsinterface
                    // Get preferred network type.
                    // MTK
                    /*
                   try {
                        networkModes[i]  = TelephonyManager.getIntAtIndex(
                                context.getContentResolver(),
                               Settings.Global.PREFERRED_NETWORK_MODE , i);
                    } catch (SettingNotFoundException snfe) {
                        Rlog.e(LOG_TAG, "Settings Exception Reading Value At Index for"+
                               " Settings.Global.PREFERRED_NETWORK_MODE");
                        networkModes[i] = preferredNetworkMode;
                    }
                    Rlog.i(LOG_TAG, "Network Mode set to " + Integer.toString(networkModes[i]));
                    */

                    // EVDO project need phone type to be C+G
                    if (CdmaFeatureOptionUtils.isEvdoDTSupport()) {
                        try {
                            networkModes[i] =
                                    TelephonyManager.getIntAtIndex(context.getContentResolver(),
                                    Settings.Global.PREFERRED_NETWORK_MODE, i);
                        } catch (SettingNotFoundException snfe) {
                            Rlog.e(LOG_TAG, "Settings Exception Reading Value At Index for"
                                    + " Settings.Global.PREFERRED_NETWORK_MODE");
                            networkModes[i] = preferredNetworkMode;
                        }
                        // workaround for cannot get phone 1 network mode
                        if (i == 1) {
                            networkModes[i] = RILConstants.NETWORK_MODE_GSM_ONLY;
                        }
                        Rlog.i(LOG_TAG, "EVDO Network Mode set to " +
                        Integer.toString(networkModes[i]));
                    } else {
                        if (i == (capabilityPhoneId - 1)) {
                            networkModes[i] = calculatePreferredNetworkType(context);
                        } else {
                            networkModes[i] = RILConstants.NETWORK_MODE_GSM_ONLY;
                        }
                        /// M: SVLTE solution2 modify, calculate network type for SVLTE @{
                        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
                            networkModes[i] = calculateNetworkType(context, i);
                        }
                        /// @}
                    }
                    Rlog.i(LOG_TAG, "RILJ Sub = " + i);
                    Rlog.i(LOG_TAG, "capabilityPhoneId=" + capabilityPhoneId
                            + " Network Mode set to " + Integer.toString(networkModes[i]));

                    // Use reflection to construct the RIL class (defaults to RIL)
                    try {
                        sCommandsInterfaces[i] = instantiateCustomRIL(
                                                     sRILClassname, context, networkModes[i], cdmaSubscription, i);
                    } catch (Exception e) {
                        // 6 different types of exceptions are thrown here that it's
                        // easier to just catch Exception as our "error handling" is the same.
                        // Yes, we're blocking the whole thing and making the radio unusable. That's by design.
                        // The log message should make it clear why the radio is broken
                        while (true) {
                            Rlog.e(LOG_TAG, "Unable to construct custom RIL class", e);
                            try {Thread.sleep(10000);} catch (InterruptedException ie) {}
                        }
                    }
                }
                Rlog.i(LOG_TAG, "Creating SubscriptionController");
                TelephonyPluginDelegate.getInstance().initSubscriptionController(context,
                        sCommandsInterfaces);

                // Instantiate UiccController so that all other classes can just
                // call getInstance()
                mUiccController = UiccController.make(context, sCommandsInterfaces);

                // MTK-START, Refine SVLTE remote SIM APP type, 2015/04/29
                // MTK TODO
                /*
                if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
                    sSvlteUiccController = SvlteUiccController.make();
                }
                */
                // MTK-END, Refine SVLTE remote SIM APP type, 2015/04/29
                //MTK-START [mtk06800] create RadioManager for proprietary power on flow
                mRadioManager = RadioManager.init(context, numPhones, sCommandsInterfaces);
                //MTK-END [mtk06800] create RadioManager for proprietary power on flow
                mNetworkManager = NetworkManager.init(context, numPhones, sCommandsInterfaces);

                // MTK SVLTE
                if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
                    svlteInit(context);
                } else {
                for (int i = 0; i < numPhones; i++) {
                    PhoneBase phone = null;
                    int phoneType = TelephonyManager.getPhoneType(networkModes[i]);
                    if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                        phone = TelephonyPluginDelegate.getInstance().makeGSMPhone(context,
                                sCommandsInterfaces[i], sPhoneNotifier, i);
                    } else if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                        phone = TelephonyPluginDelegate.getInstance().makeCDMALTEPhone(context,
                                sCommandsInterfaces[i], sPhoneNotifier, i);
                    }
                    Rlog.i(LOG_TAG, "Creating Phone with type = " + phoneType + " sub = " + i);

                    sProxyPhones[i] = TelephonyPluginDelegate.getInstance().makePhoneProxy(phone);
                }
                }  // MTK
                mProxyController = ProxyController.getInstance(context, sProxyPhones,
                        mUiccController, sCommandsInterfaces);

                // Set the default phone in base class.
                // FIXME: This is a first best guess at what the defaults will be. It
                // FIXME: needs to be done in a more controlled manner in the future.
                sProxyPhone = sProxyPhones[0];
                sCommandsInterface = sCommandsInterfaces[0];

                // Ensure that we have a default SMS app. Requesting the app with
                // updateIfNeeded set to true is enough to configure a default SMS app.
                ComponentName componentName =
                        SmsApplication.getDefaultSmsApplication(context, true /* updateIfNeeded */);
                String packageName = "NONE";
                if (componentName != null) {
                    packageName = componentName.getPackageName();
                }
                Rlog.i(LOG_TAG, "defaultSmsApplication: " + packageName);

                // Set up monitor to watch for changes to SMS packages
                SmsApplication.initSmsPackageMonitor(context);

                sMadeDefaults = true;

                Rlog.i(LOG_TAG, "Creating SubInfoRecordUpdater ");
                sSubInfoRecordUpdater = TelephonyPluginDelegate.getInstance().
                        makeSubscriptionInfoUpdater(context, sProxyPhones, sCommandsInterfaces);
                SubscriptionController.getInstance().updatePhonesAvailability(sProxyPhones);

                TelephonyPluginDelegate.getInstance().
                        initExtTelephonyClasses(context, sProxyPhones, sCommandsInterfaces);
                // Start monitoring after defaults have been made.
                // Default phone must be ready before ImsPhone is created
                // because ImsService might need it when it is being opened.
                for (int i = 0; i < numPhones; i++) {
                    sProxyPhones[i].startMonitoringImsService();
                    // Get users NW type, let it override if its not the default NW mode (-1)
                    int userNwType = SubscriptionController.getInstance().getUserNwMode(
                            sProxyPhones[i].getSubId());
                    if (userNwType != SubscriptionManager.DEFAULT_NW_MODE
                            && userNwType != networkModes[i]) {
                        sProxyPhones[i].setPreferredNetworkType(userNwType, null);
                    }
                }

                // MTK
                //[WorldMode]
                if (WorldPhoneUtil.isWorldModeSupport() && WorldPhoneUtil.isWorldPhoneSupport()) {
                    Rlog.i(LOG_TAG, "World mode support");
                    WorldMode.init();
                } else if (WorldPhoneUtil.isWorldPhoneSupport()) {
                    Rlog.i(LOG_TAG, "World phone support");
                    sWorldPhone = WorldPhoneWrapper.getWorldPhoneInstance();
                } else {
                    Rlog.i(LOG_TAG, "World phone not support");
                }
            }
        }
    }

    public static Phone getCdmaPhone(int phoneId) {
        Phone phone;
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            phone = TelephonyPluginDelegate.getInstance().makeCDMALTEPhone(sContext,
                    sCommandsInterfaces[phoneId], sPhoneNotifier, phoneId);
        }
        return phone;
    }

    public static Phone getGsmPhone(int phoneId) {
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            Phone phone = TelephonyPluginDelegate.getInstance().makeGSMPhone(sContext,
                    sCommandsInterfaces[phoneId], sPhoneNotifier, phoneId);
            return phone;
        }
    }

    private static <T> T instantiateCustomRIL(
                      String sRILClassname, Context context, int networkMode, int cdmaSubscription, Integer instanceId)
                      throws Exception {
        Class<?> clazz = Class.forName("com.android.internal.telephony." + sRILClassname);
        Constructor<?> constructor = clazz.getConstructor(Context.class, int.class, int.class, Integer.class);
        return (T) clazz.cast(constructor.newInstance(context, networkMode, cdmaSubscription, instanceId));
    }

    public static Phone getDefaultPhone() {
        synchronized (sLockProxyPhones) {
            if (!sMadeDefaults) {
                throw new IllegalStateException("Default phones haven't been made yet!");
            }
            return sProxyPhone;
        }
    }

    public static Phone getPhone(int phoneId) {
        Phone phone;
        String dbgInfo = "";

        synchronized (sLockProxyPhones) {
            if (!sMadeDefaults) {
                throw new IllegalStateException("Default phones haven't been made yet!");
                // CAF_MSIM FIXME need to introduce default phone id ?
            } else if (phoneId == SubscriptionManager.DEFAULT_PHONE_INDEX) {
                if (DBG) dbgInfo = "phoneId == DEFAULT_PHONE_ID return sProxyPhone";
                phone = sProxyPhone;
            } else {
                /// M: for SVLTE @{
                if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
                    phoneId = SvlteUtils.getSvltePhoneIdByPhoneId(phoneId);
                }
                /// @}
                if (DBG) dbgInfo = "phoneId != DEFAULT_PHONE_ID return sProxyPhones[phoneId]";
                phone = (((phoneId >= 0)
                                && (phoneId < TelephonyManager.getDefault().getPhoneCount()))
                        ? sProxyPhones[phoneId] : null);
            }
            if (DBG) {
                Rlog.d(LOG_TAG, "getPhone:- " + dbgInfo + " phoneId=" + phoneId +
                        " phone=" + phone);
            }
            return phone;
        }
    }

    public static Phone[] getPhones() {
        synchronized (sLockProxyPhones) {
            if (!sMadeDefaults) {
                throw new IllegalStateException("Default phones haven't been made yet!");
            }
            return sProxyPhones;
        }
    }

    /**
     * Makes a {@link SipPhone} object.
     * @param sipUri the local SIP URI the phone runs on
     * @return the {@code SipPhone} object or null if the SIP URI is not valid
     */
    public static SipPhone makeSipPhone(String sipUri) {
        return SipPhoneFactory.makePhone(sipUri, sContext, sPhoneNotifier);
    }

    /* Sets the default subscription. If only one phone instance is active that
     * subscription is set as default subscription. If both phone instances
     * are active the first instance "0" is set as default subscription
     */
    public static void setDefaultSubscription(int subId) {
        SystemProperties.set(PROPERTY_DEFAULT_SUBSCRIPTION, Integer.toString(subId));
        int phoneId = SubscriptionController.getInstance().getPhoneId(subId);

        synchronized (sLockProxyPhones) {
            // Set the default phone in base class
            if (phoneId >= 0 && phoneId < sProxyPhones.length) {
                sProxyPhone = sProxyPhones[phoneId];
                sCommandsInterface = sCommandsInterfaces[phoneId];
                sMadeDefaults = true;
            }
        }

        // Update MCC MNC device configuration information
        String defaultMccMnc = TelephonyManager.getDefault().getSimOperatorNumericForPhone(phoneId);
        if (DBG) Rlog.d(LOG_TAG, "update mccmnc=" + defaultMccMnc);
        MccTable.updateMccMncConfiguration(sContext, defaultMccMnc, false);

        // Broadcast an Intent for default sub change
        Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        SubscriptionManager.putPhoneIdAndSubIdExtra(intent, phoneId);
        Rlog.d(LOG_TAG, "setDefaultSubscription : " + subId
                + " Broadcasting Default Subscription Changed...");
        sContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Returns the preferred network type that should be set in the modem.
     *
     * @param context The current {@link Context}.
     * @return the preferred network mode that should be set.
     */
    // TODO: Fix when we "properly" have TelephonyDevController/SubscriptionController ..
    public static int calculatePreferredNetworkType(Context context, int phoneSubId) {
        int phoneId = SubscriptionController.getInstance().getPhoneId(phoneSubId);
        int phoneIdNetworkType = RILConstants.PREFERRED_NETWORK_MODE;
        try {
            phoneIdNetworkType = TelephonyManager.getIntAtIndex(context.getContentResolver(),
                    Settings.Global.PREFERRED_NETWORK_MODE , phoneId);
        } catch (SettingNotFoundException snfe) {
            Rlog.e(LOG_TAG, "Settings Exception Reading Valuefor phoneID");
        }
        int networkType = phoneIdNetworkType;
        Rlog.d(LOG_TAG, "calculatePreferredNetworkType: phoneId = " + phoneId +
                " phoneIdNetworkType = " + phoneIdNetworkType);

        if (SubscriptionController.getInstance().isActiveSubId(phoneSubId)) {
            networkType = android.provider.Settings.Global.getInt(context.getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE + phoneSubId,
                    phoneIdNetworkType);

            // Get users NW type, let it override if its not the default NW mode (-1)
            int userNwType = SubscriptionController.getInstance().getUserNwMode(phoneSubId);
            if (userNwType != SubscriptionManager.DEFAULT_NW_MODE && userNwType != networkType) {
                Rlog.d(LOG_TAG, "calculatePreferredNetworkType: overriding for usernw mode " +
                        "phoneSubId = " + phoneSubId + " networkType = " + networkType);
                networkType = userNwType;
            }

            //Update phone id based network type with Sub ID based.
            if (networkType != phoneIdNetworkType) {
                TelephonyManager.putIntAtIndex(context.getContentResolver(),
                        Settings.Global.PREFERRED_NETWORK_MODE, phoneId,
                        networkType);
            }
        } else {
            Rlog.d(LOG_TAG, "calculatePreferredNetworkType: phoneSubId = " + phoneSubId +
                    " is not a active SubId");
        }
        Rlog.d(LOG_TAG, "calculatePreferredNetworkType: phoneSubId = " + phoneSubId +
                " networkType = " + networkType);
        return networkType;
    }

    /* Gets the default subscription */
    public static int getDefaultSubscription() {
        return SubscriptionController.getInstance().getDefaultSubId();
    }

    /* Gets User preferred Voice subscription setting*/
    public static int getVoiceSubscription() {
        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

        try {
            subId = Settings.Global.getInt(sContext.getContentResolver(),
                    Settings.Global.MULTI_SIM_VOICE_CALL_SUBSCRIPTION);
        } catch (SettingNotFoundException snfe) {
            Rlog.e(LOG_TAG, "Settings Exception Reading Dual Sim Voice Call Values");
        }

        return subId;
    }

    /* Returns User Prompt property,  enabed or not */
    public static boolean isPromptEnabled() {
        boolean prompt = false;
        int value = 0;
        try {
            value = Settings.Global.getInt(sContext.getContentResolver(),
                    Settings.Global.MULTI_SIM_VOICE_PROMPT);
        } catch (SettingNotFoundException snfe) {
            Rlog.e(LOG_TAG, "Settings Exception Reading Dual Sim Voice Prompt Values");
        }
        prompt = (value == 0) ? false : true ;
        Rlog.d(LOG_TAG, "Prompt option:" + prompt);

       return prompt;
    }

    /*Sets User Prompt property,  enabed or not */
    public static void setPromptEnabled(boolean enabled) {
        int value = (enabled == false) ? 0 : 1;
        Settings.Global.putInt(sContext.getContentResolver(),
                Settings.Global.MULTI_SIM_VOICE_PROMPT, value);
        Rlog.d(LOG_TAG, "setVoicePromptOption to " + enabled);
    }

    /* Returns User SMS Prompt property,  enabled or not */
    public static boolean isSMSPromptEnabled() {
        boolean prompt = false;
        int value = 0;
        try {
            value = Settings.Global.getInt(sContext.getContentResolver(),
                    Settings.Global.MULTI_SIM_SMS_PROMPT);
        } catch (SettingNotFoundException snfe) {
            Rlog.e(LOG_TAG, "Settings Exception Reading Dual Sim SMS Prompt Values");
        }
        prompt = (value == 0) ? false : true ;
        Rlog.d(LOG_TAG, "SMS Prompt option:" + prompt);

       return prompt;
    }

    /*Sets User SMS Prompt property,  enable or not */
    public static void setSMSPromptEnabled(boolean enabled) {
        int value = (enabled == false) ? 0 : 1;
        Settings.Global.putInt(sContext.getContentResolver(),
                Settings.Global.MULTI_SIM_SMS_PROMPT, value);
        Rlog.d(LOG_TAG, "setSMSPromptOption to " + enabled);
    }

    /* Gets User preferred Data subscription setting*/
    public static long getDataSubscription() {
        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

        try {
            subId = Settings.Global.getInt(sContext.getContentResolver(),
                    Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION);
        } catch (SettingNotFoundException snfe) {
            Rlog.e(LOG_TAG, "Settings Exception Reading Dual Sim Data Call Values");
        }

        return subId;
    }

    /* Gets User preferred SMS subscription setting*/
    public static int getSMSSubscription() {
        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        try {
            subId = Settings.Global.getInt(sContext.getContentResolver(),
                    Settings.Global.MULTI_SIM_SMS_SUBSCRIPTION);
        } catch (SettingNotFoundException snfe) {
            Rlog.e(LOG_TAG, "Settings Exception Reading Dual Sim SMS Values");
        }

        return subId;
    }

    /**
     * Makes a {@link ImsPhone} object.
     * @return the {@code ImsPhone} object or null if the exception occured
     */
    public static ImsPhone makeImsPhone(PhoneNotifier phoneNotifier, Phone defaultPhone) {
        return ImsPhoneFactory.makePhone(sContext, phoneNotifier, defaultPhone);
    }

    /**
     * Adds a local log category.
     *
     * Only used within the telephony process.  Use localLog to add log entries.
     *
     * TODO - is there a better way to do this?  Think about design when we have a minute.
     *
     * @param key the name of the category - will be the header in the service dump.
     * @param size the number of lines to maintain in this category
     */
    public static void addLocalLog(String key, int size) {
        synchronized(sLocalLogs) {
            if (sLocalLogs.containsKey(key)) {
                throw new IllegalArgumentException("key " + key + " already present");
            }
            sLocalLogs.put(key, new LocalLog(size));
        }
    }

    /**
     * Add a line to the named Local Log.
     *
     * This will appear in the TelephonyDebugService dump.
     *
     * @param key the name of the log category to put this in.  Must be created
     *            via addLocalLog.
     * @param log the string to add to the log.
     */
    public static void localLog(String key, String log) {
        synchronized(sLocalLogs) {
            if (sLocalLogs.containsKey(key) == false) {
                throw new IllegalArgumentException("key " + key + " not found");
            }
            sLocalLogs.get(key).log(log);
        }
    }

    public static void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("PhoneFactory:");
        PhoneProxy [] phones = (PhoneProxy[])PhoneFactory.getPhones();
        int i = -1;
        for(PhoneProxy phoneProxy : phones) {
            PhoneBase phoneBase;
            i += 1;

            try {
                phoneBase = (PhoneBase)phoneProxy.getActivePhone();
                phoneBase.dump(fd, pw, args);
            } catch (Exception e) {
                pw.println("Telephony DebugService: Could not get Phone[" + i + "] e=" + e);
                continue;
            }

            pw.flush();
            pw.println("++++++++++++++++++++++++++++++++");

            try {
                ((IccCardProxy)phoneProxy.getIccCard()).dump(fd, pw, args);
            } catch (Exception e) {
                e.printStackTrace();
            }
            pw.flush();
            pw.println("++++++++++++++++++++++++++++++++");
        }

        try {
            DctController.getInstance().dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            mUiccController.dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        try {
            SubscriptionController.getInstance().dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        try {
            sSubInfoRecordUpdater.dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();

        pw.println("++++++++++++++++++++++++++++++++");
        synchronized (sLocalLogs) {
            final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
            for (String key : sLocalLogs.keySet()) {
                ipw.println(key);
                ipw.increaseIndent();
                sLocalLogs.get(key).dump(fd, ipw, args);
                ipw.decreaseIndent();
            }
            ipw.flush();
        }
    }

    public static SubscriptionInfoUpdater getSubscriptionInfoUpdater() {
        return sSubInfoRecordUpdater;
    }

    // MTK

    public static int calculatePreferredNetworkType(Context context) {
        // TODO: allow overriding like in the AOSP impl
        int networkType = android.provider.Settings.Global.getInt(context.getContentResolver(),
                android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                RILConstants.PREFERRED_NETWORK_MODE);
        Rlog.d(LOG_TAG, "calculatePreferredNetworkType: networkType = " + networkType);
        return networkType;
    }

    /// M: SVLTE solution2 modify, svlte will create Phones,
    /// Ril of inactive phone and SvltePhoneProxy here. @{
    // MTK TODO

    private static void svlteInit(Context context) {
        /*
        PhoneBase svlteDcPhone = null;
        PhoneBase cdmaPhone = null;
        int networkType = -1;
        int cdmaSubscription = CdmaSubscriptionSourceManager.getDefault(context);
        int numPhones = TelephonyManager.getDefault().getPhoneCount();
        for (int phoneId = 0; phoneId < numPhones; phoneId++) {
            networkType = calculateNetworkType(context, SvlteUtils.getLteDcPhoneId(phoneId));
            Rlog.i(LOG_TAG, "svlteInit, phoneId = " + phoneId + ", networkType = " + networkType);
            if (sActiveSvlteModeSlotId == phoneId) {
                cdmaPhone = new CDMAPhone(context,
                                          sCommandsInterfaces[phoneId],
                                          sPhoneNotifier,
                                          phoneId);

                sCommandsInterfaceLteDcs[phoneId] = new RIL(context,
                                                            networkType,
                                                            cdmaSubscription,
                                                            SvlteUtils.getLteDcPhoneId(phoneId));
                svlteDcPhone = new SvlteDcPhone(context,
                                                sCommandsInterfaceLteDcs[phoneId],
                                                sPhoneNotifier,
                                                SvlteUtils.getLteDcPhoneId(phoneId));
                sLteDcPhoneProxys[phoneId] = new SvltePhoneProxy(svlteDcPhone,
                                                     cdmaPhone,
                                                     SvlteModeController.RADIO_TECH_MODE_SVLTE);
            } else {
                svlteDcPhone = new SvlteDcPhone(context,
                                                sCommandsInterfaces[phoneId],
                                                sPhoneNotifier,
                                                phoneId);
                //sCommandsInterfaceLteDcs is for cdma phone in csfb mode.
                sCommandsInterfaceLteDcs[phoneId] = new RIL(context,
                                                            networkType,
                                                            cdmaSubscription,
                                                            SvlteUtils.getLteDcPhoneId(phoneId));
                cdmaPhone = new CDMAPhone(context,
                                          sCommandsInterfaceLteDcs[phoneId],
                                          sPhoneNotifier,
                                          SvlteUtils.getLteDcPhoneId(phoneId));

                sLteDcPhoneProxys[phoneId] = new SvltePhoneProxy(svlteDcPhone,
                                                     cdmaPhone,
                                                     SvlteModeController.RADIO_TECH_MODE_CSFB);
            }
            sLteDcPhoneProxys[phoneId].initialize();
            sProxyPhones[phoneId] = sLteDcPhoneProxys[phoneId];
        }
        SvlteModeController.make(context);
        sLteDcPhoneProxys[SvlteModeController.getInstance().getCdmaSocketSlotId()]
                .getNLtePhone().mCi.connectRilSocket();
        int sCdmaSocketSlotId = SvlteModeController.getInstance().getCdmaSocketSlotId();
        mUiccController.setSvlteCi(sCommandsInterfaceLteDcs[sCdmaSocketSlotId]);
        mUiccController.setSvlteIndex(sCdmaSocketSlotId);
        SvlteRoamingController.make(sLteDcPhoneProxys);
        */
    }
    /// @}
    /// M: SVLTE solution2 modify,calculate network type by phoneId. @{
    private static int calculateNetworkType(Context context, int phoneId) {
        int networkMode = -1;
        int capabilityPhoneId = Integer.valueOf(
                        SystemProperties.get(PhoneConstants.PROPERTY_CAPABILITY_SWITCH, "1")) - 1;

        if (!SvlteUtils.isValidPhoneId(phoneId)) {
            Rlog.i(LOG_TAG, "calculateNetworkType error, phone id : " + phoneId);
            return networkMode;
        }

        // MTK TODO
        /*
        if (sActiveSvlteModeSlotId == phoneId) {
            networkMode = RILConstants.NETWORK_MODE_CDMA;
            return networkMode;
        } else */ if (SvlteUtils.isValidateSlotId(phoneId)) {
            if (phoneId == capabilityPhoneId) {
                networkMode = calculatePreferredNetworkType(context);
            } else {
                networkMode = RILConstants.NETWORK_MODE_GSM_ONLY;
            }
            return networkMode;
        }

        phoneId = SvlteUtils.getSlotId(phoneId);

        //handle second phone in svltepohoneproxy
        // MTK TODO
        /*
        if (sActiveSvlteModeSlotId != phoneId) {
            networkMode = RILConstants.NETWORK_MODE_CDMA;
        } else */ if (SvlteUtils.isValidateSlotId(phoneId)) {
            if (phoneId == capabilityPhoneId) {
                networkMode = calculatePreferredNetworkType(context);
            } else {
                networkMode = RILConstants.NETWORK_MODE_GSM_ONLY;
            }
        }
        return networkMode;
    }
    /// @}

    public static IWorldPhone getWorldPhone() {
        if (sWorldPhone == null) {
            Rlog.d(LOG_TAG, "sWorldPhone is null");
        }

        return sWorldPhone;
    }

    public static boolean isEvdoDTSupport() {
        if (SystemProperties.get(EVDO_DT_SUPPORT).equals("1")) {
            return true;
        } else {
            return false;
        }
    }
}
