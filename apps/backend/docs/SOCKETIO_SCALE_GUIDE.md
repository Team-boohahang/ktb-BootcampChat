# Socket.IO Scale Guide

이 문서는 Socket.IO 백엔드를 단일 인스턴스와 멀티 인스턴스로 운영할 때 필요한 환경 변수, 네트워크 구조, Redis Pub/Sub 역할, 부하테스트 확인 지표를 정리한다.

## 핵심 구분

Socket.IO 멀티 인스턴스에서 필요한 것은 두 가지다.

| 영역 | 담당 | 해결하는 문제 |
| --- | --- | --- |
| Sticky Session | ALB, Nginx, Ingress 같은 로드밸런서 | 하나의 Socket.IO 연결이 같은 앱 인스턴스에 계속 도달하도록 보장 |
| Redis Pub/Sub store | 백엔드 `SOCKETIO_STORE_TYPE=redis` | 다른 앱 인스턴스에 붙은 클라이언트에게 room broadcast 이벤트 전파 |

Sticky Session만 있으면 연결 안정성은 좋아지지만, A 인스턴스에서 보낸 room 이벤트가 B 인스턴스 클라이언트에게 전달되지 않는다. Redis Pub/Sub store만 있으면 이벤트 전파는 가능하지만, transport upgrade나 polling fallback 요청이 매번 다른 인스턴스로 가면서 연결이 불안정해질 수 있다. 멀티 인스턴스는 둘 다 필요하다.

## 단일 인스턴스

### 구조

```text
Client
  -> Load Balancer or direct host
  -> Backend instance 1
       - HTTP API: PORT
       - Socket.IO: WS_PORT
       - Socket.IO store: memory
  -> MongoDB
  -> Redis
```

### 환경 변수

```env
PORT=5001
WS_PORT=5002
SOCKETIO_STORE_TYPE=memory
SOCKETIO_REDIS_STATE_TTL_HOURS=168

MONGO_URI=mongodb://localhost:27017/bootcamp-chat
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
```

### 동작 방식

- Socket.IO room membership와 Pub/Sub store는 프로세스 메모리를 사용한다.
- `ConnectedUsers`, `UserRooms`도 로컬 메모리에 저장된다.
- room broadcast는 같은 프로세스에 연결된 클라이언트에게만 전달된다.
- 구조가 단순하고 Redis Pub/Sub 비용이 없어서 로컬 개발과 단일 서버 운영에 적합하다.

## 멀티 인스턴스

### 구조

```text
Client
  -> Load Balancer
       - Sticky Session enabled
       - WebSocket upgrade allowed
       - idle timeout >= Socket.IO ping timeout
  -> Backend instance 1
       - SOCKETIO_STORE_TYPE=redis
  -> Backend instance 2
       - SOCKETIO_STORE_TYPE=redis
  -> Backend instance N
       - SOCKETIO_STORE_TYPE=redis
  -> Shared Redis
       - netty-socketio Pub/Sub
       - ConnectedUsers/UserRooms state
  -> Shared MongoDB
```

### 모든 앱 노드 공통 환경 변수

```env
PORT=5001
WS_PORT=5002
SOCKETIO_STORE_TYPE=redis
SOCKETIO_REDIS_STATE_TTL_HOURS=168

REDIS_HOST=<shared-redis-private-host>
REDIS_PORT=6379
REDIS_PASSWORD=<password-or-empty>

SOCKETIO_PING_INTERVAL=25000
SOCKETIO_PING_TIMEOUT=60000
SOCKETIO_UPGRADE_TIMEOUT=10000
```

### 동작 방식

- `SocketIOConfig`가 `socketio.store.type=redis`를 감지하면 `RedissonStoreFactory`를 사용한다.
- netty-socketio의 room broadcast가 Redis Pub/Sub을 통해 다른 앱 인스턴스로 전파된다.
- `RedisChatDataStore`가 `ConnectedUsers`, `UserRooms`를 Redis에 저장한다.
- 중복 로그인 알림은 기존 로컬 socket UUID 조회 대신 `socket:{socketId}` room broadcast로 전송한다.
- 각 클라이언트는 연결 시 `user:{userId}`, `socket:{socketId}`, `room-list` room에 참여한다.

## Redis Pub/Sub으로 전파되는 이벤트

아래처럼 `socketIOServer.getRoomOperations(roomId).sendEvent(...)` 경로를 타는 이벤트는 Redis store 모드에서 다른 인스턴스에 붙은 같은 room 클라이언트까지 전파될 수 있다.

- 채팅 메시지: `message`
- 읽음 상태: `messagesRead`
- 참여자 변경: `participantsUpdate`
- 방 목록: `roomCreated`, `roomUpdate`, `roomActivity`
- AI 메시지 스트림: `aiMessageStart`, `aiMessageChunk`, `aiMessageComplete`, `aiMessageError`
- 중복 로그인 대상 알림: `duplicateLogin`, `sessionEnded`

특정 클라이언트에게 직접 `client.sendEvent(...)` 하는 오류 응답이나 요청자 단독 응답은 현재 연결된 인스턴스에서만 처리된다. 이는 정상이다.

## 로드밸런서 설정

### 필수

- WebSocket upgrade 허용
- Sticky Session 활성화
- 앱 노드 health check는 HTTP API 포트 `PORT` 기준 사용
- idle timeout은 `SOCKETIO_PING_TIMEOUT`보다 길게 설정

예시 기준:

```env
SOCKETIO_PING_INTERVAL=25000
SOCKETIO_PING_TIMEOUT=60000
```

로드밸런서 idle timeout은 최소 70초 이상을 권장한다. 운영 여유를 두려면 120초 이상으로 둔다.

### 포트

| 포트 | 용도 |
| --- | --- |
| `PORT` 기본 `5001` | REST API, Actuator, Swagger |
| `WS_PORT` 기본 `5002` | Socket.IO |
| `REDIS_PORT` 기본 `6379` | 앱 노드 간 Socket.IO Pub/Sub 및 상태 공유 |

## 배포 확인

각 앱 노드 로그에서 store 타입을 확인한다.

```bash
journalctl -u ktb-backend.service -n 100 --no-pager | grep 'Socket.IO store factory'
```

단일 인스턴스 정상 로그:

```text
Socket.IO store factory: memory (single instance only)
```

멀티 인스턴스 정상 로그:

```text
Socket.IO store factory: redis (multi-instance Pub/Sub enabled)
Socket.IO Redis store configured for <host>:6379
```

Redis 연결 확인:

```bash
redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" ping
```

비밀번호가 있으면:

```bash
redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -a "$REDIS_PASSWORD" ping
```

## 부하테스트 확인 지표

부하테스트 콘솔에서 최소 아래 지표를 기록한다.

| 지표 | 보는 이유 |
| --- | --- |
| Connected | 목표 동시 접속 수에 도달했는지 확인 |
| Connection Errors | 연결 실패 발생 여부 |
| Disconnected + reason | ping timeout, transport close 등 연결 안정성 확인 |
| Messages Sent | 클라이언트가 보낸 메시지 수 |
| Messages Received | room broadcast 수신 비율 확인 |
| Message Errors | 서버 처리 실패 확인 |
| Avg/P95/P99 Message Latency | 지연시간 분포 확인 |
| Avg Connection Time | 접속 지연 확인 |

Grafana/Prometheus에서는 서버 기준 지표를 같이 본다.

```promql
socketio_concurrent_users
rate(socketio_messages_total[1m])
rate(socketio_messages_errors_total[1m])
process_cpu_usage
jvm_memory_used_bytes
```

## 멀티 인스턴스 검증 시나리오

1. 같은 Redis를 바라보는 백엔드 2대를 띄운다.
2. 로드밸런서 Sticky Session을 켠다.
3. 부하테스트 클라이언트가 로드밸런서 주소로 접속하게 한다.
4. 두 앱 노드 로그에서 연결이 분산되는지 확인한다.
5. 메시지 송수신 비율, connection error, disconnect reason, P95/P99 latency를 기록한다.

로컬에서 간단히 재현하려면 앱을 서로 다른 포트로 2개 띄우고, 실제 멀티 인스턴스 검증은 로드밸런서나 프록시 뒤에서 수행한다.

```bash
# 터미널 1
PORT=5001 WS_PORT=5002 SOCKETIO_STORE_TYPE=redis ./mvnw compile spring-boot:test-run -Dspring-boot.run.profiles=dev

# 터미널 2
PORT=5011 WS_PORT=5012 SOCKETIO_STORE_TYPE=redis ./mvnw compile spring-boot:test-run -Dspring-boot.run.profiles=dev
```

로컬 2개 프로세스만으로는 실제 로드밸런서 Sticky Session 동작을 검증할 수 없다. 이벤트 전파 자체는 서로 다른 포트에 붙은 클라이언트가 같은 room에서 메시지를 받는지로 확인한다.

## 남은 위험

- Redis가 장애나 지연을 겪으면 멀티 인스턴스 room broadcast도 영향을 받는다.
- `RedisChatDataStore.size()`는 Prometheus gauge 계산 시 Redis `SCAN`을 사용한다. 부하가 매우 큰 환경에서는 별도 counter/set 기반 집계로 개선할 수 있다.
- MongoDB 읽음 상태, 방 목록 인덱스, 세션 저장소 최적화는 별도 범위다.
