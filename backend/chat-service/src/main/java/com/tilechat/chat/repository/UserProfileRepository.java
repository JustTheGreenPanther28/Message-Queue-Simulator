package com.tilechat.chat.repository;

import com.tilechat.chat.domain.UserProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserProfile p where p.id = :id")
    Optional<UserProfile> lockById(@Param("id") Long id);
}
