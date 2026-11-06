package com.tilechat.user.repository;

import com.tilechat.user.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    @Query("""
            select u from User u
             where u.id <> :excludeId
               and (lower(u.username) like concat('%', :q, '%') or lower(u.displayName) like concat('%', :q, '%'))
             order by u.displayName
            """)
    List<User> search(@Param("q") String lowerQuery, @Param("excludeId") Long excludeId, Pageable page);
}
