package com.tilechat.chat.repository;

import com.tilechat.chat.domain.UserBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    List<UserBlock> findByBlockerIdOrderByCreatedAtDesc(Long blockerId);

    @Modifying
    @Query("delete from UserBlock b where b.blockerId = :blocker and b.blockedId = :blocked")
    int unblock(@Param("blocker") Long blockerId, @Param("blocked") Long blockedId);

    /** Which of these users have blocked {@code userId}. */
    @Query("select b.blockerId from UserBlock b where b.blockedId = :uid and b.blockerId in :candidates")
    List<Long> findWhoBlocked(@Param("uid") Long userId, @Param("candidates") Collection<Long> candidates);
}
