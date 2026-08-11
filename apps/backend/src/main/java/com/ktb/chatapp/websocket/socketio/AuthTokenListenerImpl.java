package com.ktb.chatapp.websocket.socketio;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.AuthTokenResult;
import com.corundumstudio.socketio.SocketIOClient;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.JwtService;
import com.ktb.chatapp.service.SessionService;
import com.ktb.chatapp.service.SessionValidationResult;
import com.ktb.chatapp.websocket.socketio.handler.ConnectionLoginHandler;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Socket.IO Authorization Handler
 * socket.handshake.auth.token과 sessionId를 처리한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AuthTokenListenerImpl implements AuthTokenListener {
    private final JwtService jwtService;
    private final SessionService sessionService;
    private final UserRepository userRepository;
    private final ObjectProvider<ConnectionLoginHandler> socketIOChatHandlerProvider;

    @Value("${PERF_CHAT_MESSAGE_SLOW_THRESHOLD_MS:${perf.chat-message.slow-threshold-ms:100}}")
    private long perfSlowThresholdMs = 100;

    @Override
    public AuthTokenResult getAuthTokenResult(Object _authToken, SocketIOClient client) {
        long startedAt = System.nanoTime();
        long parseAuthMs = 0;
        long jwtMs = 0;
        long validateSessionMs = 0;
        long userLookupMs = 0;
        long onConnectMs = 0;
        long userContextMs = 0;
        String userId = null;
        String outcome = "exception";
        try {
            long stepStartedAt = System.nanoTime();
            var authToken = (Map<?, ?>) _authToken;
            String token = authToken.get("token") != null ? authToken.get("token").toString() : null;
            String sessionId = authToken.get("sessionId") != null ? authToken.get("sessionId").toString() : null;
            parseAuthMs = elapsedMillis(stepStartedAt);

            if (token == null || sessionId == null) {
                log.warn("Missing authentication credentials in Socket.IO handshake - token: {}, sessionId: {}",
                        token != null, sessionId != null);
                outcome = "missing_credentials";
                return new AuthTokenResult(false, "Authentication error");
            }

            try {
                stepStartedAt = System.nanoTime();
                userId = jwtService.extractUserId(token);
                jwtMs = elapsedMillis(stepStartedAt);
            } catch (JwtException e) {
                jwtMs = elapsedMillis(stepStartedAt);
                outcome = "invalid_token";
                return new AuthTokenResult(false, Map.of("message", "Invalid token"));
            }

            // Validate session using SessionService
            stepStartedAt = System.nanoTime();
            SessionValidationResult validationResult =
                    sessionService.validateSession(userId, sessionId);
            validateSessionMs = elapsedMillis(stepStartedAt);

            if (!validationResult.isValid()) {
                log.error("Session validation failed: {}", validationResult.getMessage());
                outcome = "invalid_session";
                return new AuthTokenResult(false, Map.of("message", "Invalid session"));
            }

            // Load user from database
            stepStartedAt = System.nanoTime();
            User user = userRepository.findById(userId).orElse(null);
            userLookupMs = elapsedMillis(stepStartedAt);
            if (user == null) {
                log.error("User not found: {}", userId);
                outcome = "user_not_found";
                return new AuthTokenResult(false, Map.of("message", "User not found"));
            }

            log.info("Socket.IO connection authorized for user: {} ({})", user.getName(), userId);
            
            stepStartedAt = System.nanoTime();
            var socketUser = new SocketUser(user.getId(), user.getName(), sessionId, client.getSessionId().toString());
            userContextMs = elapsedMillis(stepStartedAt);
            stepStartedAt = System.nanoTime();
            socketIOChatHandlerProvider.getObject().onConnect(client, socketUser);
            onConnectMs = elapsedMillis(stepStartedAt);
            outcome = "success";
            return AuthTokenResult.AuthTokenResultSuccess;
        } catch (Exception e) {
            log.error("Socket.IO authentication error: {}", e.getMessage(), e);
            return new AuthTokenResult(false, Map.of("message", e.getMessage()));
        } finally {
            long totalMs = elapsedMillis(startedAt);
            if (totalMs >= perfSlowThresholdMs) {
                log.info("[PERF][socketAuth] status={} totalMs={} authPayloadMs={} jwtMs={} sessionMs={} userQueryMs={} userContextMs={} connectionHandlerMs={} userId={} socketId={}",
                        outcome, totalMs, parseAuthMs, jwtMs, validateSessionMs, userLookupMs,
                        userContextMs, onConnectMs, userId, client.getSessionId());
            }
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
