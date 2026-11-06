package com.tilechat.chat.service;

import com.tilechat.chat.domain.UserProfile;
import com.tilechat.chat.dto.MemberDto;
import com.tilechat.chat.repository.UserProfileRepository;
import com.tilechat.common.events.UserProfileEvent;
import com.tilechat.common.security.AuthUser;
import com.tilechat.common.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/** Read access to the local profile replica, and applying profile events to it. */
@Service
public class UserDirectory {

    /** Who did something, as carried in integration events (an image key, not a URL). */
    public record Actor(Long id, String name, String avatarKey) {}

    private final UserProfileRepository profiles;
    private final DtoMapper mapper;

    public UserDirectory(UserProfileRepository profiles, DtoMapper mapper) {
        this.profiles = profiles;
        this.mapper = mapper;
    }

    public Map<Long, MemberDto> members(Collection<Long> ids) {
        Map<Long, MemberDto> found = profiles.findAllById(new HashSet<>(ids)).stream()
                .collect(Collectors.toMap(UserProfile::getId, mapper::member, (a, b) -> a));
        Map<Long, MemberDto> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, found.getOrDefault(id, MemberDto.unknown(id))));
        return result;
    }

    public MemberDto member(Long id) {
        return members(List.of(id)).get(id);
    }

    /** The caller as an event actor; falls back to the token's username if the replica hasn't caught up. */
    public Actor actor(AuthUser me) {
        return profiles.findById(me.id())
                .map(p -> new Actor(p.getId(), p.getDisplayName(), p.getAvatarKey()))
                .orElseGet(() -> new Actor(me.id(), me.username(), null));
    }

    /** Every id must be a known user, otherwise 404. */
    public void requireExisting(Collection<Long> ids) {
        Set<Long> wanted = new HashSet<>(ids);
        Set<Long> known = profiles.findAllById(wanted).stream().map(UserProfile::getId).collect(Collectors.toSet());
        wanted.removeAll(known);
        if (!wanted.isEmpty()) {
            throw ApiException.notFound("Unknown user(s): " + wanted + ". New accounts can take a second to appear.");
        }
    }

    /**
     * Upserts the replica row under a row lock. The version check makes this safe against
     * duplicate and out-of-order events. Returns the new profile if anything changed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<MemberDto> apply(UserProfileEvent e) {
        UserProfile profile = profiles.lockById(e.userId()).orElseGet(() -> new UserProfile(e.userId()));
        if (!profile.apply(e.username(), e.displayName(), e.avatarKey(), e.version())) {
            return Optional.empty();
        }
        return Optional.of(mapper.member(profiles.save(profile)));
    }
}
