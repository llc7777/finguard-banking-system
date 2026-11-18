# FinGuard bank-server

PostgreSQL의 가상 계좌 간 이체를 처리하는 Phase 1 서버입니다. 계좌 잔액과 성공 거래를 하나의 DB 트랜잭션으로 저장합니다. 이번 단계에서는 Kafka 연결과 이벤트 발행, 알림 서버, 배포 설정을 구현하지 않습니다.

기존 Java 21, Spring Boot 4.1.2, Gradle Wrapper 9.8.2를 유지합니다. 금융 도메인 테이블은 `accounts`, `transactions` 두 개이며, Flyway 관리용 `flyway_schema_history`가 추가됩니다. Flyway가 스키마를 생성하고 Hibernate는 `ddl-auto=validate`로 매핑을 검증합니다.

## PostgreSQL과 실행 환경

로컬 PostgreSQL 서버를 실행하고, 관리 계정으로 `psql`에 접속해 개발용 사용자와 빈 DB를 준비합니다. 비밀번호는 `\password`의 프롬프트에서 설정합니다.

```sql
CREATE ROLE finguard LOGIN;
\password finguard
CREATE DATABASE finguard OWNER finguard;
```

Java 21과 Gradle 배포본 및 Maven Central 의존성을 내려받을 수 있는 환경이 필요합니다. `bank-server` 디렉터리에서 `.env.example`의 변수를 셸에 설정합니다. Spring Boot는 이 파일을 자동으로 읽지 않습니다.

PowerShell 예시:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/finguard'
$env:DB_USERNAME = 'finguard'
$env:DB_PASSWORD = '<로컬에서 설정한 비밀번호>'
$env:SPRING_PROFILES_ACTIVE = 'local'
.\gradlew.bat bootRun
```

macOS/Linux에서는 같은 환경 변수를 설정한 뒤 `./gradlew bootRun`을 실행합니다. 비밀번호의 기본값은 없으며 실제 자격 증명을 저장소에 추가하지 않습니다.

`local` 프로파일에서만 가상 계좌 `FG-000001`부터 `FG-000020`까지 20개를 각각 100,000,000원으로 생성합니다. 재시작 시 `account_number` 충돌을 무시하므로 이미 존재하는 계좌의 잔액과 상태를 초기화하지 않습니다. 시드 ID는 DB가 생성하므로 항상 1~20이라고 가정하지 마세요. 기존 데이터가 있는 DB에서는 아래 SQL로 ID를 확인합니다.

```sql
SELECT id, account_number, balance, status FROM accounts ORDER BY account_number;
```

로컬 시드를 끄려면 `BANK_LOCAL_SEED_ACCOUNTS=false`를 설정하거나 `local` 프로파일을 활성화하지 않습니다. 기본 프로파일에서는 시드가 실행되지 않으며, 범용 SQL 자동 초기화는 비활성화되어 있습니다.

## API

기본 주소는 `http://localhost:8080`입니다. 모든 이체 성공과 동일 요청 재전송은 `200 OK`를 반환합니다. 계좌번호는 실제 개인정보가 없는 개발용 가상 번호입니다.

### 계좌 조회

`GET /api/accounts/{accountId}`

```json
{
  "accountId": 1,
  "accountNumber": "FG-000001",
  "balance": 100000000,
  "status": "ACTIVE"
}
```

### 이체

`POST /api/transfers`

```json
{
  "requestId": "8e62db64-a4bc-4b02-a87a-f5b51f50c007",
  "fromAccountId": 1,
  "toAccountId": 2,
  "amount": 10000
}
```

```json
{
  "transactionId": 1,
  "requestId": "8e62db64-a4bc-4b02-a87a-f5b51f50c007",
  "status": "SUCCESS",
  "amount": 10000,
  "createdAt": "2026-11-01T00:00:00.123456Z"
}
```

PowerShell에서 실제 계좌 ID를 넣어 요청할 수 있습니다.

```powershell
$request = @{
    requestId = '8e62db64-a4bc-4b02-a87a-f5b51f50c007'
    fromAccountId = 1
    toAccountId = 2
    amount = 10000
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/transfers' -ContentType 'application/json' -Body $request
Invoke-RestMethod -Uri 'http://localhost:8080/api/accounts/1'
Invoke-RestMethod -Uri 'http://localhost:8080/api/transfers/8e62db64-a4bc-4b02-a87a-f5b51f50c007'
Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health'
```

같은 `requestId`와 같은 출금 계좌·입금 계좌·금액을 보내면 최초 성공 결과를 반환합니다. 이때 계좌 잔액이나 상태가 나중에 바뀌어도 기존 결과를 조회합니다. 같은 키로 다른 내용을 보내면 `409 IDEMPOTENCY_CONFLICT`입니다. 새로운 이체에는 새로운 UUID를 사용합니다. 실패 요청은 거래 행으로 저장하지 않습니다.

`GET /api/transfers/{requestId}`는 확정된 성공 거래만 조회하며 위 이체 응답과 같은 필드를 반환합니다. 진행 중이거나 실패한 요청, 없는 키는 `404 TRANSFER_NOT_FOUND`입니다.

### 금액·오류 규칙

통화는 KRW이며 양의 정수 원 단위만 허용합니다. 금액과 잔액은 Java `BigDecimal`, DB `NUMERIC(19,0)`을 사용합니다. 한도는 9,999,999,999,999,999,999원입니다. 입금 후 잔액이 한도를 넘으면 전체 이체를 롤백합니다. 시간은 UTC `Instant`를 사용하며 이체 응답의 생성 시각은 DB와 동일한 마이크로초 정밀도로 보존합니다.

| HTTP | 코드 | 조건 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 필수값 없음, 잘못된 UUID/계좌 ID, 동일 계좌, 0원·음수·소수·한도 초과 금액 |
| 404 | `ACCOUNT_NOT_FOUND` | 계좌 없음 |
| 404 | `TRANSFER_NOT_FOUND` | 성공 거래 없음 |
| 409 | `INSUFFICIENT_FUNDS` | 출금 계좌 잔액 부족 |
| 409 | `BLOCKED_ACCOUNT` | 출금 또는 입금 계좌 정지 |
| 409 | `BALANCE_LIMIT_EXCEEDED` | 입금 후 잔액 한도 초과 |
| 409 | `IDEMPOTENCY_CONFLICT` | 같은 키에 다른 요청 내용 |
| 409 | `REQUEST_RETRY` | DB 잠금 경합·시간 초과 등 일시적 충돌; `Retry-After: 1` 이후 같은 키와 내용으로 재시도 |
| 500 | `INTERNAL_ERROR` | 예상하지 못한 오류 |

오류 응답에는 `timestamp`, `status`, `code`, `message`를 포함합니다. 스택 트레이스나 DB 오류의 내부 상세는 반환하지 않습니다. Actuator는 `/actuator/health`만 외부에 노출하며 세부 상태를 숨깁니다.

## 트랜잭션과 동시성

이체는 PostgreSQL `READ COMMITTED` 트랜잭션으로 실행됩니다. `requestId`에서 계산한 키로 `pg_advisory_xact_lock`을 먼저 획득하고 기존 성공 거래를 조회합니다. 서로 다른 UUID의 잠금 키가 우연히 같아지면 순차 처리될 뿐 데이터가 섞이지 않습니다. DB의 `transactions.request_id` UNIQUE 제약도 중복 기록을 방어합니다.

신규 이체는 계좌 ID 오름차순으로 `PESSIMISTIC_WRITE` 행 잠금을 각각 획득한 뒤 출금, 입금, 성공 거래 저장을 같은 트랜잭션에서 처리합니다. 중간 예외는 전부 롤백됩니다. 잠금 대기는 5초, 트랜잭션 제한 시간은 20초이며 장시간 경합은 일관된 재시도 응답으로 처리합니다. 이 방식은 여러 서버 프로세스가 같은 DB를 사용해도 적용됩니다.

잠금 동작은 [PostgreSQL 공식 문서](https://www.postgresql.org/docs/current/explicit-locking.html), 트랜잭션 경계는 [Spring Framework 공식 문서](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)를 참고합니다. 스키마의 CHECK/UNIQUE/FK 제약은 잔액 음수, 잘못된 상태, 동일 계좌 이체, 잘못된 계좌 참조와 중복 키를 DB에서도 방어합니다.

## 테스트

API 테스트는 `@SpringBootTest`와 `@AutoConfigureMockMvc`로 MVC 요청 처리·검증·예외 응답을 확인합니다. 서버 포트나 별도 HTTP 클라이언트 없이 실행하며, 서비스와 Repository는 실제 PostgreSQL을 사용합니다. 테스트에 `@Transactional`을 적용하지 않아 요청 안에서 실제 커밋과 롤백이 발생합니다.

기본 통합 테스트는 [Testcontainers PostgreSQL 모듈](https://java.testcontainers.org/modules/databases/postgres/)로 실제 PostgreSQL 17을 실행합니다. Docker 실행 환경과 이미지 다운로드가 필요합니다. H2로 정합성을 판정하지 않습니다.

```powershell
.\gradlew.bat clean test
```

Docker 대신 이미 실행한 PostgreSQL을 쓰려면 별도의 테스트 전용 빈 DB를 생성하고 다음 변수를 설정합니다. **테스트는 계좌와 거래 테이블을 TRUNCATE하므로 개발·운영 DB를 지정하면 안 됩니다.**

```powershell
$env:TEST_DB_URL = 'jdbc:postgresql://localhost:5432/finguard_test'
$env:TEST_DB_USERNAME = 'finguard'
$env:TEST_DB_PASSWORD = '<테스트 DB 비밀번호>'
.\gradlew.bat clean test
```

테스트는 계좌 조회·로컬 시드의 멱등성·정상 이체·입력 및 비즈니스 오류·입금 단계 예외의 롤백·동일 키 100회 동시 호출·서로 다른 키 1,000회 동시 이체의 잔액 보존·양방향 이체·DB 제약을 검증합니다. PostgreSQL이 없으면 통합 테스트를 성공으로 간주하거나 건너뛰지 않고 실패합니다. 상세 결과는 `build/reports/tests/test/index.html`에서 확인합니다.

### 실제 검증 기록 (2026-11-01)

Java 21.0.13, Gradle 9.8.2, Spring Boot 4.1.2와 테스트 전용 PostgreSQL 18.4에서 `TEST_DB_*` 변수를 설정하고 `.\gradlew.bat clean test --no-daemon`을 실행했습니다. 43개 테스트가 모두 통과했고 실패·오류·건너뛰기는 0건입니다. Testcontainers로 컨테이너를 시작하는 경로는 이번 실행에서 사용하지 않았습니다.

별도 빈 DB에 `local` 프로파일로 `.\gradlew.bat bootRun --no-daemon --args='--server.port=18080'`을 실행해 Health `UP`, 계좌 조회, 이체, 동일 요청 재전송과 거래 조회를 확인했습니다. 10,000원 이체 후 출금·입금 잔액은 각각 99,990,000원·100,010,000원이었고 재전송은 같은 거래 ID를 반환했습니다. 계좌 20개의 잔액 합계 2,000,000,000원과 성공 거래 1건을 SQL로 확인했습니다. 검증 후 해당 서버와 테스트 DB 프로세스를 종료했습니다.

2026-11-04에는 API 테스트를 MockMvc로 전환한 뒤 같은 PostgreSQL 18.4 테스트 DB에서 `.\gradlew.bat clean test --no-daemon`을 다시 실행했습니다. 43개 모두 통과했고 실패·오류·건너뛰기는 0건입니다. 동시 이체의 잔액 보존과 실제 DB 변경 및 커밋 실패의 전체 롤백 검증도 포함합니다.

## 다음 단계

Phase 2에서는 `transactions`에 발행 상태·임대·시도 횟수 컬럼을 마이그레이션으로 추가하고 Kafka 이벤트 발행과 복구를 구현합니다. 현재 서버는 성공 거래를 보존하지만 이벤트를 발행하지 않습니다. Phase 3의 Prometheus·배포·확장 실험은 후속 작업입니다.
