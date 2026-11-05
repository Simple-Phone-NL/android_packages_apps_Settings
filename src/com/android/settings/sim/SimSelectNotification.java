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

package com.android.settings.sim;

import static android.provider.Settings.ENABLE_MMS_DATA_REQUEST_REASON_INCOMING_MMS;
import static android.provider.Settings.ENABLE_MMS_DATA_REQUEST_REASON_OUTGOING_MMS;
import static android.provider.Settings.EXTRA_ENABLE_MMS_DATA_REQUEST_REASON;
import static android.provider.Settings.EXTRA_SUB_ID;
import static android.telephony.TelephonyManager.EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE;
import static android.telephony.TelephonyManager.EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_ALL;
import static android.telephony.TelephonyManager.EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_DATA;
import static android.telephony.TelephonyManager.EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_DISMISS;
import static android.telephony.TelephonyManager.EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_NONE;
import static android.telephony.TelephonyManager.EXTRA_SUBSCRIPTION_ID;
import static android.telephony.data.ApnSetting.TYPE_MMS;

import static com.android.settings.Utils.SETTINGS_PACKAGE_NAME;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settings.R;
import com.android.settings.network.SatelliteRepository;
import com.android.settings.network.SubscriptionUtil;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SimSelectNotification extends BroadcastReceiver {
    private static final String TAG = "SimSelectNotification";

    private static final int DEFAULT_TIMEOUT_MS = 1000;

    @VisibleForTesting
    public static final int ENABLE_MMS_NOTIFICATION_ID = 2;

    @VisibleForTesting
    public static final String ENABLE_MMS_NOTIFICATION_CHANNEL =
            "enable_mms_notification_channel";

    @Override
    public void onReceive(Context context, Intent intent) {
        UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager != null && !userManager.isMainUser()) {
            Log.d(TAG, "The userId is not the main user");
            return;
        }
        if (!SubscriptionUtil.isSimHardwareVisible(context)) {
            Log.w(TAG, "Received unexpected intent with null action.");
            return;
        }
        String action = intent.getAction();

        if (action == null) {
            Log.w(TAG, "Received unexpected intent with null action.");
            return;
        }

        switch (action) {
            case TelephonyManager.ACTION_PRIMARY_SUBSCRIPTION_LIST_CHANGED:
                PrimarySubscriptionListChangedService.scheduleJob(context, intent);
                break;
            case Settings.ACTION_ENABLE_MMS_DATA_REQUEST:
                onEnableMmsDataRequest(context, intent);
                break;
            default:
                Log.w(TAG, "Received unexpected intent " + intent.getAction());
        }
    }

    private void onEnableMmsDataRequest(Context context, Intent intent) {
        // Getting subId from extra.
        int subId = intent.getIntExtra(EXTRA_SUB_ID, SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        }

        SubscriptionManager subscriptionManager = ((SubscriptionManager) context.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE));
        if (!subscriptionManager.isActiveSubscriptionId(subId)) {
            Log.w(TAG, "onEnableMmsDataRequest invalid sub ID " + subId);
            return;
        }
        final SubscriptionInfo info = subscriptionManager.getActiveSubscriptionInfo(subId);
        if (info == null) {
            Log.w(TAG, "onEnableMmsDataRequest null SubscriptionInfo for sub ID " + subId);
            return;
        }

        // Getting request reason from extra, which will determine the notification title.
        CharSequence notificationTitle = null;
        int requestReason = intent.getIntExtra(EXTRA_ENABLE_MMS_DATA_REQUEST_REASON, -1);
        if (requestReason == ENABLE_MMS_DATA_REQUEST_REASON_INCOMING_MMS) {
            notificationTitle = context.getResources().getText(
                    R.string.enable_receiving_mms_notification_title);
        } else if (requestReason == ENABLE_MMS_DATA_REQUEST_REASON_OUTGOING_MMS) {
            notificationTitle = context.getResources().getText(
                    R.string.enable_sending_mms_notification_title);
        } else {
            Log.w(TAG, "onEnableMmsDataRequest invalid request reason " + requestReason);
            return;
        }

        TelephonyManager tm = ((TelephonyManager) context.getSystemService(
                Context.TELEPHONY_SERVICE)).createForSubscriptionId(subId);

        if (tm.isDataEnabledForApn(TYPE_MMS)) {
            Log.w(TAG, "onEnableMmsDataRequest MMS data already enabled on sub ID " + subId);
            return;
        }

        CharSequence notificationSummary = context.getResources().getString(
                R.string.enable_mms_notification_summary,
                SubscriptionUtil.getUniqueSubscriptionDisplayName(info, context));

        cancelEnableMmsNotification(context);

        createEnableMmsNotification(context, notificationTitle, notificationSummary, subId);
    }

    /**
     * Handles changes to the primary subscription list, performing actions only
     * if the device is not currently in a satellite session. This method is
     * intended to be executed on a worker thread.
     *
     * @param context The application context
     * @param intent  The intent signaling a primary subscription change
     */
    @WorkerThread
    public static void onPrimarySubscriptionListChanged(@NonNull Context context,
            @NonNull Intent intent) {
        Log.d(TAG, "Checking satellite enabled status");
        Executor executor = Executors.newSingleThreadExecutor();
        ListenableFuture<Boolean> isSatelliteSessionStartedFuture =
                new SatelliteRepository(context).requestIsSessionStarted(executor);
        boolean isSatelliteSessionStarted = false;
        try {
            isSatelliteSessionStarted =
                    isSatelliteSessionStartedFuture.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Log.w(TAG, "Can't get satellite session status", e);
        } finally {
            if (isSatelliteSessionStarted) {
                Log.i(TAG, "Device is in a satellite session.g Unable to handle primary"
                        + " subscription list changes");
            } else {
                Log.i(TAG, "Device is not in a satellite session. Handle primary"
                        + " subscription list changes");
                startSimSelectDialogIfNeeded(context, intent);
            }
        }
    }

    private static void startSimSelectDialogIfNeeded(Context context, Intent intent) {
        int dialogType = intent.getIntExtra(EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE,
                EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_NONE);

        if (dialogType == EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_NONE) {
            return;
        }

        // If the dialog type is to dismiss.
        if (dialogType == EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_DISMISS) {
            SimDialogProhibitService.dismissDialog(context);
            return;
        }

        if (dialogType == EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_ALL) {
            int subId = intent.getIntExtra(EXTRA_SUBSCRIPTION_ID,
                    SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
            int slotIndex = SubscriptionManager.getSlotIndex(subId);
            // If there is only one subscription, ask if user wants to use if for everything
            Intent newIntent = new Intent(context, SimDialogActivity.class);
            newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            newIntent.putExtra(SimDialogActivity.DIALOG_TYPE_KEY,
                    SimDialogActivity.PREFERRED_PICK);
            newIntent.putExtra(SimDialogActivity.PREFERRED_SIM, slotIndex);
            context.startActivity(newIntent);
        } else if (dialogType == EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_DATA) {
            // If there are multiple, ensure they pick default data
            Intent newIntent = new Intent(context, SimDialogActivity.class);
            newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            newIntent.putExtra(SimDialogActivity.DIALOG_TYPE_KEY, SimDialogActivity.DATA_PICK);
            context.startActivity(newIntent);
        }
    }

    private void createEnableMmsNotification(Context context, CharSequence titleString,
            CharSequence notificationSummary, int subId) {
        final Resources resources = context.getResources();

        NotificationChannel notificationChannel = new NotificationChannel(
                ENABLE_MMS_NOTIFICATION_CHANNEL,
                resources.getText(R.string.enable_mms_notification_channel_title),
                NotificationManager.IMPORTANCE_HIGH);

        Notification.Builder builder =
                new Notification.Builder(context, ENABLE_MMS_NOTIFICATION_CHANNEL)
                        .setSmallIcon(R.drawable.ic_settings_24dp)
                        .setColor(context.getColor(R.color.sim_noitification))
                        .setContentTitle(titleString)
                        .setContentText(notificationSummary)
                        .setStyle(new Notification.BigTextStyle().bigText(notificationSummary))
                        .setAutoCancel(true);

        // Create the pending intent that will lead to the subscription setting page.
        Intent resultIntent = new Intent(Settings.ACTION_MMS_MESSAGE_SETTING);
        resultIntent.setPackage(SETTINGS_PACKAGE_NAME);
        resultIntent.putExtra(Settings.EXTRA_SUB_ID, subId);
        PendingIntent resultPendingIntent = PendingIntent.getActivity(context, 0, resultIntent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setContentIntent(resultPendingIntent);

        // Notify the notification.
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(notificationChannel);
        notificationManager.notify(ENABLE_MMS_NOTIFICATION_ID, builder.build());
    }

    private void cancelEnableMmsNotification(Context context) {
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(ENABLE_MMS_NOTIFICATION_ID);
    }
}
