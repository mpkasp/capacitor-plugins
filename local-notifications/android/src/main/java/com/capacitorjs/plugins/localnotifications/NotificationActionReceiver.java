package com.capacitorjs.plugins.localnotifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.Logger;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Fork addition (Phase 3, "Tier-2" native action resolve).
 *
 * Background notification actions (LOG / SKIP) that were built with {@code background:true} target
 * this receiver via {@code PendingIntent.getBroadcast} instead of foregrounding the app. It runs in
 * a short native wake window with NO WebView, and does the two latency-critical things a dose
 * resolution needs, in this order (the "persist before suppress" invariant):
 *
 *   1. Append a domain-agnostic event to the {@link Outbox} — the durable record of "the user
 *      resolved this dose from the shade." JS reads it later (getOutboxEvents) into its single
 *      logMedicine writer, then acks it.
 *   2. Only if that persisted: dismiss the medicine's reminder *now* — clear the tray entry so it
 *      stops buzzing immediately, and mark the id resolved so the alarm chain skips this dose's
 *      remaining nags while KEEPING its next occurrence.
 *
 * We deliberately do NOT cancel the alarm here. A daily reminder is a single self-rearming chain
 * (this dose's nags and tomorrow's dose are sequential links — see {@link TimedNotificationPublisher}),
 * so cancelling to stop today's nags would also drop tomorrow's reminder — a missed-med hazard for a
 * wrist-only user who never opens the app. Instead the {@link ResolvedStore} mark lets the chain's
 * own next fire suppress the leftover nag and advance to the next occurrence.
 *
 * If the outbox append fails we do NOT dismiss — keep nagging. A missed dismissal is an annoyance;
 * a silently lost dose log is not. JS is the eventual-consistency backstop: when it drains and
 * reschedules for the now-logged dose it re-cancels/reschedules anyway.
 *
 * The key win over the Tier-1 {@code getActivity} action: a bridged Pixel Watch / Wear OS tap logs
 * straight from the wrist with the phone locked and the app closed, instead of launching the phone
 * app (which may block on unlock).
 */
public class NotificationActionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int notificationId = intent.getIntExtra(LocalNotificationManager.NOTIFICATION_INTENT_KEY, Integer.MIN_VALUE);
        if (notificationId == Integer.MIN_VALUE) {
            Logger.debug(Logger.tags("LN"), "Background action received without a notification id; ignoring");
            return;
        }
        String actionId = intent.getStringExtra(LocalNotificationManager.ACTION_INTENT_KEY);
        String notificationJson = intent.getStringExtra(LocalNotificationManager.NOTIFICATION_OBJ_INTENT_KEY);

        // 1. Persist first. The record is deliberately domain-agnostic: it carries the raw
        //    notification source so JS can extract the medicine uid and map the action to a log
        //    type — no medicine/dose semantics live in the fork. eventId gives JS exactly-once.
        boolean persisted = false;
        try {
            JSONObject record = new JSONObject();
            record.put("eventId", UUID.randomUUID().toString());
            record.put("notificationId", notificationId);
            record.put("actionId", actionId);
            record.put("timestamp", System.currentTimeMillis());
            record.put("notification", notificationJson);
            persisted = Outbox.append(context, record);
        } catch (Exception e) {
            Logger.error(Logger.tags("LN"), "Failed to append background action to outbox", e);
        }

        if (!persisted) {
            // Keep nagging: leave the notification and its alarm untouched so the dose isn't lost.
            Logger.error(Logger.tags("LN"), "Outbox append failed; not dismissing notification " + notificationId, null);
            return;
        }

        // 2. Suppress now — stop the current buzz and hand the "skip this dose's remaining nags,
        //    keep the next occurrence" decision to the alarm chain via a resolved mark.
        NotificationManagerCompat.from(context).cancel(notificationId); // clear the going-off tray entry
        ResolvedStore.markResolved(context, notificationId);

        // If the app happens to be alive, nudge JS to drain immediately (prompt foreground UX). When
        // it isn't, this is a no-op and JS drains on next boot/resume.
        LocalNotificationsPlugin.fireOutboxAppended();
    }
}
