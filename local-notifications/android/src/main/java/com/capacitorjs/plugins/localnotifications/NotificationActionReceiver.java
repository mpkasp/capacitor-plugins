package com.capacitorjs.plugins.localnotifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.CapConfig;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
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
 *
 * <p>Phase 3b: a background action carrying {@code snoozeMinutes > 0} is a <em>snooze</em> — it does
 * not resolve the dose, it re-schedules the reminder. That path inverts the invariant: the durable
 * artifact is the new alarm (not the outbox record), so it arms the alarm FIRST and only then
 * suppresses the current nag + records the snooze forward (JS sets {@code notificationSnoozedUntil}
 * on drain). See {@link #handleSnooze}.
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
        int snoozeMinutes = intent.getIntExtra(LocalNotificationManager.SNOOZE_MINUTES_INTENT_KEY, 0);

        if (snoozeMinutes > 0) {
            handleSnooze(context, notificationId, actionId, notificationJson, snoozeMinutes);
            return;
        }

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

    /**
     * Snooze: re-schedule the reminder {@code snoozeMinutes} out on the companion id JS stamped into
     * {@code extra.snoozeNotificationId}, then retire the current nag. Ordering is the snooze analogue
     * of "persist before suppress" — here the <em>alarm</em> is the durable artifact (the outbox
     * record only carries the snooze forward to {@code notificationSnoozedUntil} for UI/cloud), so:
     *
     *   1. Arm the snooze alarm first. If that fails, do nothing — leave the primary nagging so a
     *      missed snooze never silences the dose.
     *   2. Only then append the outbox event (JS -> notificationSnoozedUntil), clear the tray, and
     *      mark the primary resolved so its chain skips today's remaining nags while keeping tomorrow.
     *
     * The companion notification reuses the primary's own burst shape (every/count/limit) when it was
     * a nagging reminder, else a one-shot — and NEVER carries a cron, so the publisher's existing
     * "remaining == 0 + no cron -> stop" terminates it after the snooze fires (no new publisher logic).
     */
    private void handleSnooze(Context context, int notificationId, String actionId, String notificationJson, int snoozeMinutes) {
        boolean armed = false;
        try {
            JSObject source = new JSObject(notificationJson);
            JSObject extra = source.getJSObject("extra");
            Integer snoozeId = extra != null ? extra.getInteger("snoozeNotificationId") : null;
            if (snoozeId == null) {
                // JS didn't stamp a companion id — we can't arm a separate snooze alarm without
                // colliding with the primary chain. Leave the primary nagging.
                Logger.error(Logger.tags("LN"), "Snooze action missing extra.snoozeNotificationId; not snoozing " + notificationId, null);
                return;
            }

            long snoozeAtMs = System.currentTimeMillis() + (long) snoozeMinutes * 60_000L;
            String snoozeAt = formatUtc(snoozeAtMs);

            // Reuse the primary's burst params so native and JS reconcile converge on an identical
            // schedule for the same companion id (replace-in-place, no double-buzz).
            JSObject primarySchedule = source.getJSObject("schedule");
            String every = primarySchedule != null ? primarySchedule.getString("every") : null;
            Integer count = primarySchedule != null ? primarySchedule.getInteger("count") : null;
            Integer limit = primarySchedule != null ? primarySchedule.getInteger("limit") : null;

            JSObject snoozeSchedule = new JSObject();
            if (every != null && count != null && count > 0 && limit != null && limit > 0) {
                snoozeSchedule.put("every", every);
                snoozeSchedule.put("count", (int) count);
                snoozeSchedule.put("limit", (int) limit); // capped burst, no `on:` cron -> stops after the cap
                snoozeSchedule.put("startAt", snoozeAt);
            } else {
                snoozeSchedule.put("at", snoozeAt);
                snoozeSchedule.put("repeats", false);
            }
            snoozeSchedule.put("allowWhileIdle", true);

            // Rewrite the source into the companion notification and arm through the shared build path.
            source.put("id", (int) snoozeId);
            source.put("schedule", snoozeSchedule);

            LocalNotificationManager manager = new LocalNotificationManager(
                new NotificationStorage(context), null, context, CapConfig.loadDefault(context));
            armed = manager.scheduleFromSource(source);
        } catch (Exception e) {
            Logger.error(Logger.tags("LN"), "Failed to arm snooze alarm for " + notificationId, e);
        }

        if (!armed) {
            Logger.error(Logger.tags("LN"), "Snooze alarm not armed; leaving notification " + notificationId + " nagging", null);
            return;
        }

        // Alarm (the durable artifact) is set. Record the snooze forward + retire the current nag.
        try {
            JSONObject record = new JSONObject();
            record.put("eventId", UUID.randomUUID().toString());
            record.put("notificationId", notificationId);
            record.put("actionId", actionId);
            record.put("timestamp", System.currentTimeMillis());
            record.put("notification", notificationJson);
            Outbox.append(context, record);
        } catch (Exception e) {
            // Best-effort: the reminder already fires from the armed alarm. JS just won't reflect the
            // snooze in notificationSnoozedUntil until it recomputes; a reconcile then re-derives it.
            Logger.error(Logger.tags("LN"), "Snoozed but failed to append outbox event for " + notificationId, e);
        }

        NotificationManagerCompat.from(context).cancel(notificationId);
        ResolvedStore.markResolved(context, notificationId);
        LocalNotificationsPlugin.fireOutboxAppended();
    }

    private static String formatUtc(long ms) {
        SimpleDateFormat sdf = new SimpleDateFormat(LocalNotificationSchedule.JS_DATE_FORMAT, Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(ms));
    }
}
