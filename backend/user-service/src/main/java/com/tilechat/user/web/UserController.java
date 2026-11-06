package com.tilechat.user.web;

import com.tilechat.common.security.AuthUser;
import com.tilechat.common.storage.PresignedUpload;
import com.tilechat.user.dto.AvatarRequests;
import com.tilechat.user.dto.UpdateProfileRequest;
import com.tilechat.user.dto.UserDto;
import com.tilechat.user.service.UserService;
import com.tilechat.user.service.UserViews;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService users;
    private final UserViews views;

    public UserController(UserService users, UserViews views) {
        this.users = users;
        this.views = views;
    }

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal AuthUser me) {
        return views.of(users.get(me.id()));
    }

    @PatchMapping("/me")
    public UserDto update(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody UpdateProfileRequest req) {
        return views.of(users.updateProfile(me.id(), req));
    }

    /** Step 1 of changing the picture: get a presigned URL and PUT the file to it. */
    @PostMapping("/me/avatar/upload-url")
    public PresignedUpload avatarUploadUrl(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody AvatarRequests.UploadUrl req) {
        return users.avatarUploadUrl(me.id(), req.contentType(), req.size());
    }

    /** Step 2: attach the uploaded file. Safe to retry. */
    @PutMapping("/me/avatar")
    public UserDto setAvatar(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody AvatarRequests.SetAvatar req) {
        return views.of(users.setAvatar(me.id(), req.uploadKey()));
    }

    @DeleteMapping("/me/avatar")
    public UserDto removeAvatar(@AuthenticationPrincipal AuthUser me) {
        return views.of(users.removeAvatar(me.id()));
    }

    @GetMapping("/search")
    public List<UserDto> search(@AuthenticationPrincipal AuthUser me, @RequestParam("q") String q) {
        return users.search(q, me.id()).stream().map(views::of).toList();
    }

    @GetMapping("/{id}")
    public UserDto get(@PathVariable Long id) {
        return views.of(users.get(id));
    }
}
