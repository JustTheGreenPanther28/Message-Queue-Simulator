package com.tilechat.notification.dto;

import java.util.List;

public record NotificationPage(List<NotificationDto> items, long unreadCount) {
}
