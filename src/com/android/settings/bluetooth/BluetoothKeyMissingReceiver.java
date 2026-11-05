/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settings.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.android.settings.flags.Flags;

/**
 * BluetoothKeyMissingReceiver is a receiver for Bluetooth key missing error when reconnecting to a
 * bonded bluetooth device.
 */
public final class BluetoothKeyMissingReceiver extends BroadcastReceiver {
    private static final String TAG = "BtKeyMissingReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Flags.enableBluetoothKeyMissingDialog()) {
            return;
        }
        String action = intent.getAction();
        if (action == null) {
            return;
        }

        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        PowerManager powerManager = context.getSystemService(PowerManager.class);
        if (TextUtils.equals(action, BluetoothDevice.ACTION_KEY_MISSING)) {
            Log.d(TAG, "Receive ACTION_KEY_MISSING");
            if (shouldShowDialog(context, device, powerManager)) {
                Intent pairingIntent = getKeyMissingDialogIntent(context, device);
                Log.d(TAG, "Show key missing dialog:" + device);
                context.startActivityAsUser(pairingIntent, UserHandle.CURRENT);
            } else {
                // Simple Phone: no notification, the dialog is only shown while in the foreground.
                Log.d(TAG, "Skip key missing dialog: " + device);
            }
        }
    }

    private Intent getKeyMissingDialogIntent(Context context, BluetoothDevice device) {
        Intent pairingIntent = new Intent();
        pairingIntent.setClass(context, BluetoothKeyMissingDialog.class);
        pairingIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        pairingIntent.setAction(BluetoothDevice.ACTION_KEY_MISSING);
        pairingIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return pairingIntent;
    }

    private boolean shouldShowDialog(
            Context context, BluetoothDevice device, PowerManager powerManager) {
        return LocalBluetoothPreferences.shouldShowDialogInForeground(context, device)
                && powerManager.isInteractive();
    }
}
