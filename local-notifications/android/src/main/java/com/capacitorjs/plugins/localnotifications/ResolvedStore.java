package com.capacitorjs.plugins.localnotifications;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Fork addition (Phase 3). A tiny native flag store: "notification id N was resolved out-of-band"
 * (a background LOG/SKIP action, or later a BLE cap press) while JS was not running.
 *
 * It exists to solve one problem: a daily nagging reminder is a single self-rearming alarm chain
 * (see {@link TimedNotificationPublisher}), where this dose's remaining nags and the next dose's
 * occurrence are sequential links. An out-of-band resolve wants to skip the remaining nags but keep
 * the next occurrence. Rather than cancel the whole chain from outside (which would also drop the
 * next occurrence — a missed-med-reminder hazard for wrist-only users), the resolver just marks the
 * id here, and the publisher consumes the mark on its next fire to advance the chain itself.
 *
 * The mark is a plain boolean; the publisher distinguishes "a nag of the resolved dose" from "the
 * next occurrence" from the alarm's own {@code remaining}/{@code limit}, so no timestamp is needed.
 * {@link #consume} is read-and-clear, so a stale mark self-heals on the very next fire of that id.
 */
public final class ResolvedStore {

    private static final String RESOLVED_STORE_ID = "DailyResolved";
    private static final Object LOCK = new Object();

    private ResolvedStore() {}

    private static SharedPreferences storage(Context context) {
        return context.getSharedPreferences(RESOLVED_STORE_ID, Context.MODE_PRIVATE);
    }

    /** Mark that notification id N was resolved out-of-band. */
    public static void markResolved(Context context, int id) {
        synchronized (LOCK) {
            storage(context).edit().putBoolean(Integer.toString(id), true).commit();
        }
    }

    /**
     * Clear any resolved mark for id N. Called when the id is freshly (re)scheduled: a new alarm on
     * that id starts a new lifecycle, so a mark left over from a prior resolve is stale and must not
     * suppress the new reminder's first fire. This is the "self-heals when JS reschedules" guarantee —
     * critical for the snooze companion id, which is deliberately re-armed on the same id and would
     * otherwise be suppressed by a mark left when the user snoozed that same reminder earlier.
     */
    public static void clear(Context context, int id) {
        synchronized (LOCK) {
            storage(context).edit().remove(Integer.toString(id)).commit();
        }
    }

    /**
     * Read whether id N is marked resolved AND clear the mark in one step. Called once per fire so
     * the mark only ever affects the first fire after a resolve — later fires are normal.
     */
    public static boolean consume(Context context, int id) {
        synchronized (LOCK) {
            SharedPreferences prefs = storage(context);
            String key = Integer.toString(id);
            boolean resolved = prefs.getBoolean(key, false);
            if (resolved) {
                prefs.edit().remove(key).commit();
            }
            return resolved;
        }
    }
}
