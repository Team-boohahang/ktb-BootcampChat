package com.ktb.chatapp.model;

import com.ktb.chatapp.service.SessionMetadata;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Session {
    public static final String SESSION_TTL = "30m";

    private String id;

    private String userId;

    private String sessionId;

    private long createdAt;

    private long lastActivity;

    private SessionMetadata metadata;

    private Instant expiresAt;
}
