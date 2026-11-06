package com.tilechat.notification.repository;

import com.tilechat.notification.domain.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByUserIdAndSourceKey(Long userId, String sourceKey);

    List<Notification> findByUserIdOrderByIdDesc(Long userId, Pageable page);

    long countByUserIdAndReadFalse(Long userId);

    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :uid and n.read = false")
    int markAllRead(@Param("uid") Long userId);

    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :uid and n.conversationId = :cid and n.read = false")
    int markConversationRead(@Param("uid") Long userId, @Param("cid") Long conversationId);
}
