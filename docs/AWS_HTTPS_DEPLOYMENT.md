# AWS EC2·NGINX HTTPS 임시 배포 가이드

이 문서는 SafeMask-AI를 단일 EC2에서 잠시 검증하기 위한 기준입니다. 원본 민감정보가 서버 내부에
저장될 수 있으므로 공개 샘플 데이터만 사용하고, 실제 회사 자료를 사용하려면 별도의 보안 승인을
먼저 받아야 합니다.

## 1. 네트워크 경계

EC2 보안 그룹의 인바운드는 다음과 같이 최소화합니다.

| 포트 | 소스 | 용도 |
| --- | --- | --- |
| 22 | 관리자 공인 IP `/32` | SSH |
| 80 | 인터넷 | 인증서 발급 및 HTTPS 리다이렉트 |
| 443 | 허용할 사용자 대역 | SafeMask HTTPS |

- 8080, Oracle, Redis 포트는 인터넷에 열지 않습니다.
- 같은 EC2에서 NGINX와 Spring Boot를 실행할 때 Spring Boot는 `127.0.0.1:8080`에만 바인딩합니다.
- DB와 Redis가 다른 서버라면 사설 서브넷과 보안 그룹 간 참조만 허용합니다.

## 2. 운영 프로필과 비밀값

애플리케이션은 `prod` 프로필에서 다음 조건을 만족하지 않으면 기동을 중단합니다.

- Refresh Token 쿠키 `Secure=true`
- 파일 저장소가 OS 임시 디렉터리 밖의 절대 경로
- Spring Boot가 loopback 주소에만 바인딩
- 파일 저장소 디렉터리를 생성하고 쓸 수 있음

비밀값은 저장소, AMI, 셸 이력, 서비스 유닛 파일에 직접 작성하지 않습니다. AWS Systems Manager
Parameter Store, Secrets Manager 또는 애플리케이션 사용자만 읽을 수 있는 별도 환경 파일로
주입합니다. `.env`와 인증서 개인 키는 Git에 추가하지 않습니다.

필수·주요 환경변수 이름은 다음과 같습니다. 문서와 예제에는 실제 값을 기록하지 않습니다.

```text
SPRING_PROFILES_ACTIVE=prod
DB_DRIVER_CLASS_NAME
DB_URL
DB_USERNAME
DB_PASSWORD
REDIS_HOST
REDIS_PORT
REDIS_PASSWORD
OPENAI_API_KEY
JWT_SECRET
FILE_STORAGE_PATH=/var/lib/safemask/files
PDF_FONT_PATH
SAFEMASK_BOOTSTRAP_ADMIN_LOGIN_ID
```

최초 관리자 승격이 끝나면 `SAFEMASK_BOOTSTRAP_ADMIN_LOGIN_ID`를 제거하고 재기동합니다.

단일 EC2의 systemd 실행 예시는
[`deploy/systemd/safemask.service.example`](../deploy/systemd/safemask.service.example)에 있습니다.
환경 파일은 `root:safemask`, 권한 `640` 이하로 두고 서비스 계정 외 사용자가 읽지 못하게 합니다.

## 3. 파일 저장소

프로젝트 디렉터리와 NGINX 정적 경로 밖에 전용 디렉터리를 만들고 애플리케이션 계정만 접근시킵니다.

```bash
sudo install -d -o safemask -g safemask -m 700 /var/lib/safemask/files
```

파일에는 원복된 개인정보가 들어갈 수 있습니다. EBS 암호화를 사용하고, 백업·스냅샷과 폐기 시점도
같은 민감도 기준으로 관리합니다.

## 4. NGINX와 인증서

[`deploy/nginx/safemask.conf.example`](../deploy/nginx/safemask.conf.example)을 복사한 뒤 도메인과
인증서 경로를 변경합니다. 설정은 다음 SafeMask 제한과 맞춰져 있습니다.

- 요청 본문 최대 25MB
- SSE 프록시 버퍼링 해제
- AI 요청보다 긴 240초 프록시 타임아웃
- `Host`, `X-Real-IP`, `X-Forwarded-For`, `X-Forwarded-Proto` 전달
- HTTP에서 HTTPS로 리다이렉트

최초 인증서 발급 전에는 예제의 80 포트 `server` 블록만 먼저 적용합니다. 아직 존재하지 않는
`ssl_certificate` 경로가 포함된 443 블록을 먼저 적용하면 NGINX 설정 검사가 실패합니다.

```bash
sudo install -d -o www-data -g www-data -m 755 /var/www/certbot
sudo nginx -t
sudo systemctl reload nginx
sudo certbot certonly --webroot -w /var/www/certbot -d example.com
```

인증서가 발급된 뒤 예제의 443 블록과 실제 인증서 경로를 적용하고 다시 검증합니다.

```bash
sudo nginx -t
sudo systemctl reload nginx
sudo certbot renew --dry-run
```

예제는 임시 배포를 고려해 HSTS를 1일로 시작합니다. 도메인과 인증서가 정상 동작하고 장기 운영이
확정된 뒤 기간을 늘립니다. `includeSubDomains`는 모든 하위 도메인이 HTTPS일 때만 추가해야 합니다.

## 5. 배포 검증

- HTTP 주소가 동일한 HTTPS 주소로 리다이렉트되는지 확인
- 로그인 응답의 Refresh Token에 `Secure; HttpOnly; SameSite=Strict`가 있는지 확인
- 로그인 유지 상태에서 Access Token 만료 후 자동 갱신되는지 확인
- 10MB 첨부 및 여러 파일의 합산 요청이 정상 처리되는지 확인
- AI 진행 상태가 한꺼번에 몰리지 않고 SSE로 순차 표시되는지 확인
- 8080, Oracle, Redis 포트가 외부에서 접근되지 않는지 확인
- 로그인 반복 요청이 429로 제한되고 로그에 IP·사번 원문이 없는지 확인
- 관리자 모니터링에서 DB·Redis·파일 저장소 상태가 정상인지 확인

## 6. 임시 배포 종료

과금과 민감정보 잔존을 막기 위해 종료 순서를 확인합니다.

- 테스트 계정과 대화·첨부·생성 파일 삭제
- 파일 저장소와 로그의 보존 필요성을 확인한 뒤 안전하게 폐기
- Parameter Store·Secrets Manager의 임시 비밀값 삭제 또는 회전
- 발급한 인증서와 DNS 레코드 정리
- EC2 종료 후 연결된 EBS·스냅샷·AMI 삭제 여부 확인
- Elastic IP를 사용했다면 반드시 연결 해제 후 릴리스
- Route 53 Hosted Zone과 임시 도메인의 유지 여부 확인
- OpenAI API Key와 JWT Secret 회전

EC2를 `stop`만 하면 EBS, 공인 IPv4 또는 기타 연결 자원의 과금이 계속될 수 있으므로 AWS Billing과
Cost Explorer에서 잔여 자원을 마지막으로 확인합니다.
