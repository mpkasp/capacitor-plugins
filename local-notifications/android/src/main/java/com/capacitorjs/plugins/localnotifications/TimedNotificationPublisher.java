package com.capacitorjs.plugins.localnotifications;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Class used to create notification from timer event
 * Note: Class is being registered in Android manifest as broadcast receiver
 */
public class TimedNotificationPublisher extends BroadcastReceiver {

    public static String NOTIFICATION_KEY = "NotificationPublisher.notification";
    public static String CRON_KEY = "NotificationPublisher.cron";
    // Self-rearming `every` interval chain (fork addition): the interval in millis, and the number
    // of deliveries remaining after the current one (-1 = unlimited). EVERY_LIMIT_KEY holds the
    // original cap so a recurring capped burst can reset itself at each outer (`on:`) occurrence.
    public static String EVERY_INTERVAL_KEY = "NotificationPublisher.everyInterval";
    public static String EVERY_REMAINING_KEY = "NotificationPublisher.everyRemaining";
    public static String EVERY_LIMIT_KEY = "NotificationPublisher.everyLimit";

    /**
     * Restore and present notification
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
//         Notification notification = intent.getParcelableExtra(NOTIFICATION_KEY);
//         int id = intent.getIntExtra(LocalNotificationManager.NOTIFICATION_SCHEDULE_ID_INTENT_KEY, Integer.MIN_VALUE);

        Notification notification;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notification = intent.getParcelableExtra(NOTIFICATION_KEY, Notification.class);
        } else {
            notification = getParcelableExtraLegacy(intent, NOTIFICATION_KEY);
        }

        notification.when = System.currentTimeMillis();

        int id = intent.getIntExtra(LocalNotificationManager.NOTIFICATION_INTENT_KEY, Integer.MIN_VALUE);
        if (id == Integer.MIN_VALUE) {
            Logger.error(Logger.tags("LN"), "No valid id supplied", null);
        }
        NotificationStorage storage = new NotificationStorage(context);

        // Was this dose resolved out-of-band (a background LOG/SKIP action, or later a BLE cap)
        // since the last fire? consume() read-and-clears the mark, so it only ever affects this
        // first fire after the resolve. If it was resolved AND this fire is one of the dose's
        // leftover nags (not the next occurrence), suppress the post and advance the chain straight
        // to the next occurrence — this is how we skip today's remaining nags while keeping tomorrow.
        boolean forceAdvance = ResolvedStore.consume(context, id) && isNagOfResolvedDose(intent);

        if (!forceAdvance) {
            JSObject notificationJson = storage.getSavedNotificationAsJSObject(Integer.toString(id));
            LocalNotificationsPlugin.fireReceived(notificationJson);
            notificationManager.notify(id, notification);
        } else {
            Logger.debug(Logger.tags("LN"), "notification " + id + " resolved out-of-band; suppressing nag and advancing");
        }
        if (!rescheduleNotificationIfNeeded(context, intent, id, forceAdvance)) {
            storage.deleteNotification(Integer.toString(id));
        }
    }

    /**
     * Whether a resolved dose's THIS fire is a leftover nag (suppress it) rather than the next
     * occurrence (keep it). Derived purely from the alarm's own counters — no clock:
     *   - pure cron / uncapped interval  -> each fire is its own occurrence -> not a nag.
     *   - capped burst, no cron (snooze) -> no next occurrence to protect -> always a nag (stop).
     *   - capped burst with cron         -> a nag iff remaining != limit-1 (limit-1 is an
     *                                       occurrence's first fire, i.e. the next dose).
     */
    private boolean isNagOfResolvedDose(Intent intent) {
        long interval = intent.getLongExtra(EVERY_INTERVAL_KEY, -1L);
        if (interval <= 0) {
            return false;
        }
        int remaining = intent.getIntExtra(EVERY_REMAINING_KEY, -1);
        if (remaining < 0) {
            return false;
        }
        String cron = intent.getStringExtra(CRON_KEY);
        if (cron == null) {
            return true;
        }
        int limit = intent.getIntExtra(EVERY_LIMIT_KEY, remaining + 1);
        return remaining != (limit - 1);
    }

    @SuppressWarnings("deprecation")
    private Notification getParcelableExtraLegacy(Intent intent, String string) {
        return intent.getParcelableExtra(NOTIFICATION_KEY);
    }

    private boolean rescheduleNotificationIfNeeded(Context context, Intent intent, int id, boolean forceAdvance) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        int flags = PendingIntent.FLAG_CANCEL_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags | PendingIntent.FLAG_MUTABLE;
        }

        // Interval (`every:`) chain — exact, wake-through-Doze. Checked before the plain cron path
        // because a capped-burst schedule may ALSO carry an outer `on:` cron for its recurrence.
        // Behaviour:
        //   - remaining != 0  -> still inside the burst (or an uncapped interval): step by interval.
        //   - remaining == 0 + outer cron present -> burst finished: jump to the next dose
        //     occurrence and RESET the burst. This is what keeps a daily reminder firing even when
        //     the app never runs and the user never taps — every fire here is an OS alarm event, so
        //     Monday being ignored does not stop Tuesday.
        //   - remaining == 0 + no cron (e.g. a snooze burst) -> stop.
        long interval = intent.getLongExtra(EVERY_INTERVAL_KEY, -1L);
        if (interval > 0) {
            int remaining = intent.getIntExtra(EVERY_REMAINING_KEY, -1);
            if (forceAdvance) {
                // Resolved out-of-band: abandon any remaining nags and behave as if the burst just
                // finished — the branches below then jump to the next cron occurrence (daily/weekly
                // recurrence preserved) or, for a one-shot snooze burst with no cron, stop.
                remaining = 0;
            }
            String cron = intent.getStringExtra(CRON_KEY);
            long trigger;
            int nextRemaining;
            if (remaining != 0) {
                trigger = System.currentTimeMillis() + interval;
                nextRemaining = (remaining > 0) ? remaining - 1 : -1;
            } else if (cron != null) {
                trigger = DateMatch.fromMatchString(cron).nextTrigger(new Date());
                int limit = intent.getIntExtra(EVERY_LIMIT_KEY, 1);
                nextRemaining = limit - 1; // reset the burst for the next occurrence
            } else {
                Logger.debug(Logger.tags("LN"), "notification " + id + " reached its interval limit; not rescheduling");
                return false;
            }
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent clone = (Intent) intent.clone();
            clone.putExtra(EVERY_REMAINING_KEY, nextRemaining);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, id, clone, flags);
            // Medication reminders: wake through Doze. Fall back to inexact only when the user has
            // revoked the exact-alarm permission.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Logger.warn(
                    "Capacitor/LocalNotification",
                    "Exact alarms not allowed in user settings.  Interval notification scheduled with non-exact alarm."
                );
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent);
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent);
            }
            Logger.debug(Logger.tags("LN"), "notification " + id + " (every) will next fire at " + sdf.format(new Date(trigger)) + "; remaining=" + nextRemaining);
            return true;
        }

        // Cron (`on:`) chain — recurring wall-clock schedule with no burst.
        String dateString = intent.getStringExtra(CRON_KEY);
        if (dateString != null) {
            DateMatch date = DateMatch.fromMatchString(dateString);
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

            long trigger = date.nextTrigger(new Date());
            Intent clone = (Intent) intent.clone();
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, id, clone, flags);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Logger.warn(
                    "Capacitor/LocalNotification",
                    "Exact alarms not allowed in user settings.  Notification scheduled with non-exact alarm."
                );
                alarmManager.set(AlarmManager.RTC, trigger, pendingIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC, trigger, pendingIntent);
            }
            Logger.debug(Logger.tags("LN"), "notification " + id + " will next fire at " + sdf.format(new Date(trigger)));
            return true;
        }

        return false;
    }
}
