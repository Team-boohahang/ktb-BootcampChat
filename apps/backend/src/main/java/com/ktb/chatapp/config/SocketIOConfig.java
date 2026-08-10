package com.ktb.chatapp.config;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.SocketConfig;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.SpringAnnotationScanner;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.corundumstudio.socketio.store.MemoryStoreFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.websocket.socketio.ChatDataStore;
import com.ktb.chatapp.websocket.socketio.LocalChatDataStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;

import static org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class SocketIOConfig {

    @Value("${socketio.server.host:localhost}")
    private String host;

    @Value("${socketio.server.port:5002}")
    private Integer port;

    @Value("${socketio.server.origin:*}")
    private String origin;

    @Value("${socketio.server.accept-backlog:128}")
    private int acceptBackLog;

    @Value("${socketio.server.tcp-send-buffer-size:65536}")
    private int tcpSendBufferSize;

    @Value("${socketio.server.tcp-receive-buffer-size:65536}")
    private int tcpReceiveBufferSize;

    @Value("${socketio.server.tcp-no-delay:true}")
    private boolean tcpNoDelay;

    @Value("${socketio.server.ping-timeout:60000}")
    private int pingTimeout;

    @Value("${socketio.server.ping-interval:25000}")
    private int pingInterval;

    @Value("${socketio.server.upgrade-timeout:10000}")
    private int upgradeTimeout;

    @Value("${socketio.server.boss-threads:0}")
    private int bossThreads;

    @Value("${socketio.server.worker-threads:0}")
    private int workerThreads;

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SocketIOServer socketIOServer(AuthTokenListener authTokenListener, MeterRegistry meterRegistry) {
        com.corundumstudio.socketio.Configuration config = new com.corundumstudio.socketio.Configuration();
        config.setHostname(host);
        config.setPort(port);
        
        var socketConfig = new SocketConfig();
        socketConfig.setReuseAddress(true);
        socketConfig.setTcpNoDelay(tcpNoDelay);
        socketConfig.setAcceptBackLog(acceptBackLog);
        socketConfig.setTcpSendBufferSize(tcpSendBufferSize);
        socketConfig.setTcpReceiveBufferSize(tcpReceiveBufferSize);
        config.setSocketConfig(socketConfig);

        if (bossThreads > 0) {
            config.setBossThreads(bossThreads);
        }
        if (workerThreads > 0) {
            config.setWorkerThreads(workerThreads);
        }

        config.setOrigin(origin);

        // Socket.IO settings
        config.setPingTimeout(pingTimeout);
        config.setPingInterval(pingInterval);
        config.setUpgradeTimeout(upgradeTimeout);

        config.setJsonSupport(new JacksonJsonSupport(new JavaTimeModule()));
        config.setStoreFactory(new MemoryStoreFactory()); // 단일노드 전용

        log.info(
                "Socket.IO server configured on {}:{} with {} boss threads, {} worker threads, backlog={}, sendBuffer={}, receiveBuffer={}, tcpNoDelay={}, pingInterval={}ms, pingTimeout={}ms, upgradeTimeout={}ms",
                host, port, config.getBossThreads(), config.getWorkerThreads(),
                acceptBackLog, tcpSendBufferSize, tcpReceiveBufferSize, tcpNoDelay,
                pingInterval, pingTimeout, upgradeTimeout
        );
        var socketIOServer = new SocketIOServer(config);
        socketIOServer.getNamespace(Namespace.DEFAULT_NAME).addAuthTokenListener(authTokenListener);
        socketIOServer.getNamespace(Namespace.DEFAULT_NAME).addEventInterceptor((client, name, data, ack) -> {
            // 이벤트 발생 빈도 수집
            Counter.builder("socketio.events.total")
                .description("Total Socket.IO events received")
                .tag("event_type", name)
                .register(meterRegistry)
                .increment();
        });
        
        return socketIOServer;
    }
    
    /**
     * SpringAnnotationScanner는 BeanPostProcessor로서
     * ApplicationContext 초기화 초기에 등록되고,
     * 내부에서 사용하는 SocketIOServer는 Lazy로 지연되어
     * 다른 Bean들의 초기화 과정에 간섭하지 않게 한다.
     */
    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    public BeanPostProcessor springAnnotationScanner(@Lazy SocketIOServer socketIOServer) {
        return new SpringAnnotationScanner(socketIOServer);
    }
    
    // 인메모리 저장소, 단일 노드 환경에서만 사용
    @Bean
    @ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
    public ChatDataStore chatDataStore() {
        return new LocalChatDataStore();
    }
}
