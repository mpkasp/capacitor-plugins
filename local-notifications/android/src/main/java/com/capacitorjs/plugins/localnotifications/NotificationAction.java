package com.capacitorjs.plugins.localnotifications;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Action types that will be registered for the notifications
 */
public class NotificationAction {

    private String id;
    private String title;
    private Boolean input;
    // Fork addition (Phase 3): when true, tapping this action fires a background broadcast
    // (NotificationActionReceiver) instead of foregrounding the app — so it resolves silently,
    // including from a bridged Wear OS watch with the phone locked. General capability, not app
    // domain logic (cf. the exact-alarm fix).
    private Boolean background;
    // Fork addition (Phase 3b): a background action that RE-SCHEDULES its own notification this many
    // minutes out instead of resolving it terminally (i.e. snooze). 0 / null = a plain resolve
    // (LOG/SKIP). Generic capability — the fork re-arms the notification, JS owns the domain meaning.
    private Integer snoozeMinutes;

    public NotificationAction() {}

    public NotificationAction(String id, String title, Boolean input, Boolean background) {
        this(id, title, input, background, null);
    }

    public NotificationAction(String id, String title, Boolean input, Boolean background, Integer snoozeMinutes) {
        this.id = id;
        this.title = title;
        this.input = input;
        this.background = background;
        this.snoozeMinutes = snoozeMinutes;
    }

    public static Map<String, NotificationAction[]> buildTypes(JSArray types) {
        Map<String, NotificationAction[]> actionTypeMap = new HashMap<>();
        try {
            List<JSONObject> objects = types.toList();
            for (JSONObject obj : objects) {
                JSObject jsObject = JSObject.fromJSONObject(obj);
                String actionGroupId = jsObject.getString("id");
                if (actionGroupId == null) {
                    return null;
                }
                JSONArray actions = jsObject.getJSONArray("actions");
                if (actions != null) {
                    NotificationAction[] typesArray = new NotificationAction[actions.length()];
                    for (int i = 0; i < typesArray.length; i++) {
                        NotificationAction notificationAction = new NotificationAction();
                        JSObject action = JSObject.fromJSONObject(actions.getJSONObject(i));
                        notificationAction.setId(action.getString("id"));
                        notificationAction.setTitle(action.getString("title"));
                        notificationAction.setInput(action.getBool("input"));
                        notificationAction.setBackground(action.getBool("background"));
                        notificationAction.setSnoozeMinutes(action.getInteger("snoozeMinutes"));
                        typesArray[i] = notificationAction;
                    }
                    actionTypeMap.put(actionGroupId, typesArray);
                }
            }
        } catch (Exception e) {
            Logger.error(Logger.tags("LN"), "Error when building action types", e);
        }
        return actionTypeMap;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public boolean isInput() {
        return Boolean.TRUE.equals(input);
    }

    public void setInput(Boolean input) {
        this.input = input;
    }

    public boolean isBackground() {
        return Boolean.TRUE.equals(background);
    }

    public void setBackground(Boolean background) {
        this.background = background;
    }

    // Minutes to re-schedule this action's notification (snooze). 0 = a plain resolve, not a snooze.
    public int getSnoozeMinutes() {
        return snoozeMinutes == null ? 0 : snoozeMinutes;
    }

    public void setSnoozeMinutes(Integer snoozeMinutes) {
        this.snoozeMinutes = snoozeMinutes;
    }
}
