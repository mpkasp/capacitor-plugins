package com.capacitorjs.plugins.localnotifications;

import android.content.Context;
import android.content.SharedPreferences;
import com.getcapacitor.Logger;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Fork addition (Phase 3). A dumb, flat native → JS event queue.
 *
 * Native producers (today: the background notification-action {@link NotificationActionReceiver};
 * later: the BLE cap) {@link #append} domain events here from a short wake window in which no
 * WebView / JS is guaranteed to run. JS is the sole consumer: on boot/resume it {@link #peekAll}s
 * (via the {@code getOutboxEvents()} plugin method), feeds the records into its single
 * {@code logMedicine(intent)} writer, then {@link #remove}s the ones it processed (the
 * {@code ackOutboxEvents()} method).
 *
 * Delivery is deliberately <b>at-least-once, not read-and-clear</b>: events are only removed after
 * JS has acked them, so a crash mid-processing re-delivers rather than silently dropping a dose
 * log. The consumer dedups by {@code eventId} to stay idempotent (also catching producer-side
 * duplicates, e.g. the cap re-sending an unacked event across reconnections).
 *
 * Records are intentionally domain-agnostic — the fork carries the raw notification payload and
 * lets JS interpret it, so no medicine/dose semantics leak into the plugin.
 *
 * Storage is a dedicated SharedPreferences file (app-global by name, so a future BLE plugin can
 * append to the same queue). All mutations hold a process-wide lock and use {@code commit()} so a
 * producer only reports success once the event is durably persisted — the "persist before
 * suppress" invariant: never dismiss a reminder unless its dose log is safely captured.
 */
public final class Outbox {

    private static final String OUTBOX_STORE_ID = "DailyOutbox";
    private static final String EVENTS_KEY = "events";

    // Process-wide guard so a producer append can't interleave with a JS drain (read-modify-write
    // on the JSON array). BroadcastReceivers and the plugin run in the same app process by default.
    private static final Object LOCK = new Object();

    private Outbox() {}

    private static SharedPreferences storage(Context context) {
        return context.getSharedPreferences(OUTBOX_STORE_ID, Context.MODE_PRIVATE);
    }

    /**
     * Durably append one event. Returns true only once the write has been committed to disk, so
     * callers can gate an irreversible action (dismissing the notification) on it.
     */
    public static boolean append(Context context, JSONObject record) {
        synchronized (LOCK) {
            SharedPreferences prefs = storage(context);
            JSONArray events = read(prefs);
            events.put(record);
            // commit() (synchronous) rather than apply() — we must know the event is on disk before
            // the caller suppresses the reminder.
            return prefs.edit().putString(EVENTS_KEY, events.toString()).commit();
        }
    }

    /**
     * Return all queued events WITHOUT removing them. The consumer processes them, then calls
     * {@link #remove} with the eventIds it handled — so nothing is dropped if it crashes first.
     */
    public static JSONArray peekAll(Context context) {
        synchronized (LOCK) {
            return read(storage(context));
        }
    }

    /**
     * Remove the given eventIds from the queue (the consumer's ack after processing). Filters by id
     * rather than clearing, so events appended concurrently by another producer are preserved.
     */
    public static void remove(Context context, Set<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            SharedPreferences prefs = storage(context);
            JSONArray events = read(prefs);
            JSONArray kept = new JSONArray();
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event != null && eventIds.contains(event.optString("eventId"))) {
                    continue;
                }
                if (event != null) {
                    kept.put(event);
                }
            }
            if (kept.length() > 0) {
                prefs.edit().putString(EVENTS_KEY, kept.toString()).commit();
            } else {
                prefs.edit().remove(EVENTS_KEY).commit();
            }
        }
    }

    private static JSONArray read(SharedPreferences prefs) {
        String raw = prefs.getString(EVENTS_KEY, null);
        if (raw == null) {
            return new JSONArray();
        }
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            Logger.error(Logger.tags("LN"), "Outbox store corrupt; resetting", e);
            return new JSONArray();
        }
    }
}
