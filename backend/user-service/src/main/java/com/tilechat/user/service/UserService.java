package com.tilechat.user.service;

import com.tilechat.common.storage.ObjectStorage;
import com.tilechat.common.storage.PresignedUpload;
import com.tilechat.common.tx.AfterCommit;
import com.tilechat.common.web.ApiException;
import com.tilechat.user.domain.User;
import com.tilechat.user.dto.UpdateProfileRequest;
import com.tilechat.user.dto.UserSnapshot;
import com.tilechat.user.repository.UserRepository;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class UserService {

    static final String CACHE = "user-snapshots";

    private final UserRepository users;
    private final ObjectStorage storage;
    private final UserEventPublisher events;
    private final CacheManager cacheManager;
    private final TransactionTemplate tx;

    public UserService(UserRepository users, ObjectStorage storage, UserEventPublisher events, CacheManager cacheManager,
                       TransactionTemplate tx) {
        this.tx = tx;
        this.users = users;
        this.storage = storage;
        this.events = events;
        this.cacheManager = cacheManager;
    }

    /** Cache-aside: profiles are read on every app start and every search result. */
    @Cacheable(cacheNames = CACHE, key = "#id")
    @Transactional(readOnly = true)
    public UserSnapshot get(Long id) {
        return users.findById(id).map(UserSnapshot::from).orElseThrow(() -> ApiException.notFound("User not found"));
    }

    @Transactional(readOnly = true)
    public List<User> search(String query, Long me) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return List.of();
        return users.search(q, me, PageRequest.of(0, 20));
    }

    @Transactional
    public User updateProfile(Long me, UpdateProfileRequest req) {
        User user = load(me);
        if (user.getVersion() != req.version()) {
            throw ApiException.conflict("Your profile was changed elsewhere. Reload and try again.");
        }
        user.rename(req.displayName().trim());
        return saved(user);
    }

    /** Step 1: a presigned URL the browser uploads the picture to directly. */
    public PresignedUpload avatarUploadUrl(Long me, String contentType, long size) {
        return storage.presignUpload(String.valueOf(me), contentType, size);
    }

    /**
     * Step 2: validate the upload and make it the profile picture. Validation and copy happen
     * before the DB write; the tmp object and the old picture are deleted only after commit.
     */
    public User setAvatar(Long me, String uploadKey) {
        String key = storage.promote(uploadKey, String.valueOf(me), "avatars/" + me);
        try {
            return tx.execute(status -> setAvatarKey(me, key, uploadKey));
        } catch (RuntimeException e) {
            storage.deleteQuietly(key);
            throw e;
        }
    }

    private User setAvatarKey(Long me, String key, String uploadKey) {
        User user = load(me);
        if (key.equals(user.getAvatarKey())) return user; // a retry of a request that already succeeded
        String old = user.changeAvatar(key);
        User saved = saved(user);
        AfterCommit.run(() -> {
            storage.deleteQuietly(uploadKey);
            if (old != null) storage.deleteQuietly(old);
        });
        return saved;
    }

    @Transactional
    public User removeAvatar(Long me) {
        User user = load(me);
        String old = user.changeAvatar(null);
        if (old == null) return user;
        User saved = saved(user);
        AfterCommit.run(() -> storage.deleteQuietly(old));
        return saved;
    }

    private User load(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
    }

    /** Flushes (so @Version is checked and bumped), emits the event, evicts the cache after commit. */
    private User saved(User user) {
        users.saveAndFlush(user);
        events.updated(user);
        Long id = user.getId();
        AfterCommit.run(() -> Objects.requireNonNull(cacheManager.getCache(CACHE)).evict(id));
        return user;
    }
}
