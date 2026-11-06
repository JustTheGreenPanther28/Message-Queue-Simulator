package com.tilechat.chat.web;

import com.tilechat.chat.dto.MemberDto;
import com.tilechat.chat.service.BlockService;
import com.tilechat.common.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** PUT/DELETE so both are naturally idempotent. */
@RestController
@RequestMapping("/api/blocks")
public class BlockController {

    private final BlockService blocks;

    public BlockController(BlockService blocks) {
        this.blocks = blocks;
    }

    @GetMapping
    public List<MemberDto> list(@AuthenticationPrincipal AuthUser me) {
        return blocks.blocked(me.id());
    }

    @PutMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@AuthenticationPrincipal AuthUser me, @PathVariable Long userId) {
        blocks.block(me, userId);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@AuthenticationPrincipal AuthUser me, @PathVariable Long userId) {
        blocks.unblock(me, userId);
    }
}
