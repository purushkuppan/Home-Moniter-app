package com.homehub.homeassistant;

import java.time.Instant;

/**
 * Someone tapped a button on a phone notification (Home Assistant's {@code mobile_app_notification_action} event),
 * for example "I'm on it" on a critical alert. Published by the Home Assistant connector as a Spring event.
 *
 * @param action the button's action id, as Kudil set it when sending the notification
 * @param time   when Home Assistant received the tap
 */
public record NotificationAction(String action, Instant time) {
}
