package com.jupiterlyr.phonewarmer.monitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

public class BatteryMonitor {

    public interface Listener {
        void onBatteryChanged(BatterySnapshot snapshot);
    }

    private final Context context;
    private BroadcastReceiver receiver;

    public BatteryMonitor(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start(Listener listener) {
        if (receiver != null) {
            return;
        }

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null) {
                    return;
                }

                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int percent = scale > 0 ? (int) ((level * 100f) / scale) : level;

                int status = intent.getIntExtra(
                        BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN
                );

                boolean isCharging =
                        status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL;

                int tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                float tempC = tempRaw / 10f;

                listener.onBatteryChanged(new BatterySnapshot(percent, isCharging, tempC));
            }
        };

        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = context.registerReceiver(receiver, filter);
        if (sticky != null) {
            receiver.onReceive(context, sticky);
        }
    }

    public void stop() {
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiver = null;
        }
    }
}
