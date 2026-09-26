# 홈서버 Kubernetes GitOps 배포 작업 계획서

## 1. 목표

애플리케이션 저장소의 `main` 브랜치에 PR이 병합되면 다음 흐름이 자동으로 이어지게 한다.

```text
애플리케이션 PR 검증
  → main 병합
  → 테스트 및 컨테이너 이미지 빌드
  → GHCR에 불변 이미지 태그/다이제스트 게시
  → GitOps 저장소의 이미지 버전 변경 커밋 생성
  → 홈서버 Argo CD가 변경 감지
  → 홈서버 Kubernetes에 동기화
  → readiness 확인 후 배포 완료
```

운영 상태의 유일한 선언 원본은 GitOps 저장소로 둔다. 긴급 상황을 제외하고 클러스터에서 `kubectl set image`, `kubectl edit` 등으로 직접 바꾸지 않으며, 롤백도 GitOps 커밋을 되돌리는 방식으로 수행한다.

## 2. 확인한 현재 상태

### 애플리케이션과 GitHub

- 애플리케이션: Java 17, Spring Boot, 단일 `/hello` 컨트롤러
- 빌드: Gradle Wrapper, 멀티 스테이지 `Dockerfile`
- GitHub 저장소: `woonkim0413/review-for-pr-agent`, 현재 공개 저장소
- 브랜치: 원격에 `feature/all`, `dev`, `main` 존재
- 현재 CI: `feature/all → dev` PR에 JUnit 수행
- 현재 이미지 워크플로: `main` 대상 PR의 테스트·이미지 빌드와 조건부 GHCR push
- 구축 대상: `main` 병합 이후의 운영 이미지 게시, GitOps 저장소 갱신, Argo CD 동기화

운영 이미지 게시 워크플로는 `main` push 이벤트에서 병합 커밋을 다시 테스트·빌드하고, 그 결과만 배포 대상으로 사용한다.

### 홈서버 Kubernetes

2026-09-26에 읽기 전용으로 확인한 상태이다.

| 항목 | 현재 상태 | 계획에 미치는 영향 |
|---|---|---|
| OS | Ubuntu 24.04.4 LTS, x86_64 | 일반 Linux 설치 절차 사용 가능 |
| 클러스터 | 단일 노드 kubeadm, Kubernetes v1.37.0 | 고가용성은 없으며 버전 호환성 확인 필요 |
| 노드 | `home-server`, Ready, control-plane taint 없음 | 일반 워크로드와 Argo CD를 바로 스케줄 가능 |
| 자원 | 16 CPU, 약 30 GiB RAM, 루트 디스크 여유 약 122 GiB | 현재 규모의 앱과 Argo CD에 충분 |
| 컨테이너 런타임 | containerd, nerdctl 설치됨 | GHCR 이미지 pull 검증 가능 |
| 네트워크 | Calico, Pod CIDR `10.244.0.0/16` | 별도 CNI 설치 불필요 |
| 사설 연결 | Tailscale 1.102.3, `tailscaled` active/online | 기존 장비 identity를 확인한 뒤 EC2 연결에 재사용 |
| 미설치 구성 | Argo CD, Helm, IngressClass, LoadBalancer, StorageClass | 설치 순서를 분리해야 함 |

애플리케이션은 영속 볼륨을 사용하지 않으므로 StorageClass는 선행 조건이 아니다. Ingress Controller는 플랫폼 구성으로 설치하며, 설치 전 검증에는 port-forward를 사용한다.

## 3. 먼저 확정할 설계

### 저장소와 배포 단위

- 애플리케이션 소스와 Kubernetes 선언을 별도 저장소로 분리한다.
- GitOps 저장소는 홈 클러스터 전체를 관리한다. 이름 예시: `home-k8s-gitops`
- `review-for-pr-agent`를 첫 workload로 등록하고, 각 애플리케이션은 독립된 디렉터리와 Argo CD `Application`을 갖는다.
- 사용자 workload인 `apps`, 클러스터 공통 구성인 `platform`, 실제 클러스터 등록 정보인 `clusters`, 최초 설치용 `bootstrap`을 분리한다.
- 초기 환경은 `home` 하나만 두되 각 앱을 `base`와 `overlays/home`으로 분리해 이후 다른 클러스터나 환경을 추가할 수 있게 한다.
- 앱 배포 선언은 Helm 차트보다 Kustomize를 우선한다. 현재 앱이 단순해 구조와 변경 내역을 이해하기 쉽고 Argo CD가 별도 플러그인 없이 처리할 수 있다.
- Argo CD는 같은 클러스터 안의 앱을 배포하므로 별도 클러스터 등록 없이 `https://kubernetes.default.svc`를 사용한다.
- 앱마다 명시적인 child `Application` 파일을 둔다. 반복되는 등록 구성이 관리 부담이 되는 규모에서는 같은 디렉터리 구조를 입력으로 사용하는 `ApplicationSet`을 적용한다.

권장 GitOps 저장소 구조:

```text
home-k8s-gitops/
├── apps/
│   ├── review-for-pr-agent/
│   │   ├── base/
│   │   │   ├── deployment.yaml
│   │   │   ├── service.yaml
│   │   │   └── kustomization.yaml
│   │   └── overlays/
│   │       └── home/
│   │           ├── ingress.yaml
│   │           ├── kustomization.yaml
│   │           └── patch-*.yaml
│   └── <future-application>/
│       ├── base/
│       └── overlays/home/
├── platform/
│   ├── ingress-controller/
│   │   └── home/
│   ├── secrets-management/
│   │   └── home/
│   └── observability/
│       └── home/
├── clusters/
│   └── home/
│       ├── kustomization.yaml
│       ├── projects/
│       │   ├── workloads.yaml
│       │   └── platform.yaml
│       └── applications/
│           ├── review-for-pr-agent.yaml
│           ├── ingress-controller.yaml
│           └── <future-application>.yaml
├── bootstrap/
│   └── argocd/
│       ├── README.md
│       └── root-application.yaml
├── policies/
│   └── README.md
└── README.md
```

디렉터리별 책임은 다음과 같다.

- `apps/<app>/base`: 특정 환경에 종속되지 않는 Deployment, Service, ServiceAccount 등의 기본 선언
- `apps/<app>/overlays/home`: 홈 클러스터의 image, replica, resource, Ingress, ConfigMap 차이
- `platform`: Ingress Controller, Secret 관리, 모니터링처럼 여러 앱이 공유하는 클러스터 구성 요소
- `clusters/home/projects`: workload와 platform의 허용 저장소·namespace·리소스 범위를 나눈 `AppProject`
- `clusters/home/applications`: 홈 클러스터에 실제 등록할 child `Application` 목록
- `bootstrap/argocd/root-application.yaml`: 최초 한 번 수동 적용한 뒤 `clusters/home` 아래의 projects와 applications를 관리하는 root application
- `policies`: Kustomize 렌더, 스키마, 보안 정책 검증 규칙과 예외 기록

새 애플리케이션 추가 절차는 “`apps/<app>` 생성 → namespace와 owner 결정 → `clusters/home/applications/<app>.yaml` 등록 → 렌더·권한 검증 → sync”로 표준화한다. 앱 삭제는 child `Application`과 실제 리소스의 cascading deletion 여부를 먼저 확인한 뒤 별도 PR로 수행한다.

root application은 다른 `Application`과 `AppProject`를 생성할 수 있는 관리자 수준 구성이다. 따라서 `bootstrap/`, `clusters/`, `platform/`에는 CODEOWNERS 승인을 요구하고 일반 애플리케이션 CD 봇이 이 경로를 직접 수정하지 못하게 한다. 각 앱 CD는 자기 `apps/<app>/overlays/home`의 이미지 버전만 변경해야 한다.

### 이미지 버전 규칙

- 운영 이미지의 기준 태그는 전체 또는 충분히 긴 Git 커밋 SHA로 한다. 예: `sha-<main-commit>`
- `latest`는 사람이 확인하기 위한 보조 태그로만 사용할 수 있고 GitOps 선언에는 사용하지 않는다.
- 가능하면 최종적으로는 태그와 함께 이미지 digest를 기록해 동일 선언이 항상 동일 이미지를 가리키게 한다.
- 배포 이력은 `애플리케이션 커밋 → GHCR 이미지 digest → GitOps 커밋 → Argo CD sync revision`으로 추적 가능해야 한다.

### GHCR 공개 여부

소스 저장소가 public이고 이미지에 비공개 자산이 없으므로 GHCR 패키지도 public으로 운영한다. 이 구성은 클러스터에 장기 `read:packages` 토큰을 두지 않아도 된다.

### GitOps 저장소 갱신 방식

`main` 이미지 게시 성공 후 각 애플리케이션의 GitHub Actions가 공유 GitOps 저장소에 앱별 update branch와 PR을 생성한다. 검증을 통과한 PR은 운영 정책에 따라 auto-merge하거나 승인 후 merge하며, 이때 생성된 GitOps 커밋을 Argo CD의 배포 기준으로 사용한다.

다음 안전장치를 둔다.

- 앱 저장소의 기본 `GITHUB_TOKEN`은 다른 저장소 쓰기에 사용하지 않는다.
- GitOps 저장소에만 쓸 수 있는 GitHub App 토큰 또는 최소 권한 토큰을 앱 저장소의 Actions secret/environment secret으로 보관한다.
- 각 앱 workflow는 자신의 `apps/<app>/overlays/home` 경로만 수정하도록 입력 경로를 고정하고, diff 검사에서 다른 경로가 바뀌면 실패시킨다.
- `CODEOWNERS`와 branch protection으로 `bootstrap/`, `clusters/`, `platform/`, `policies/` 변경에 관리자 승인을 요구한다.
- 워크플로 concurrency를 설정해 오래된 실행이 최신 이미지 버전을 덮어쓰지 못하게 한다.
- 커밋 메시지에 앱 저장소 커밋 SHA와 이미지 digest를 남긴다.
- GitOps 저장소는 사람의 무검증 직접 수정과 앱 봇의 보호 경로 수정을 막는 규칙을 둔다.

저장소 수준 write 토큰은 경로 단위 권한을 강제하지 못하므로 workflow의 경로 검사와 함께 branch protection 및 CODEOWNERS 승인을 적용한다.

## 4. 단계별 실행 계획

### Phase 0. 저장소 보안 및 위생 정리

현재 공개 저장소에 `.docker-config/` 내부의 Buildx 상태 파일과 `.token_seed`가 Git으로 추적되고 있다. 내용은 확인하지 않았으며, 민감정보로 간주하고 먼저 처리한다.

- `.docker-config/`를 `.gitignore`에 추가하고 추적 대상에서 제거한다.
- 해당 파일이 어떤 도구로 생성되었고 실제 자격 증명인지 확인한다.
- 자격 증명일 가능성이 조금이라도 있으면 관련 Docker/GitHub 토큰을 폐기·재발급한다.
- 이미 공개된 이력이므로 필요하면 `git filter-repo`로 과거 이력에서도 제거한 뒤 강제 갱신 영향 범위를 검토한다.
- GitHub Secret Scanning 및 저장소 보안 알림을 확인한다.
- 로컬 `/sudo`, kubeconfig, GHCR 토큰 등은 소스/GitOps 어느 저장소에도 포함하지 않는다.

완료 기준: 공개 저장소 현재 트리와 필요 시 과거 이력에서 로컬 Docker 상태 파일이 제거되고, 의심되는 자격 증명이 교체되어 있다.

### Phase 1. 애플리케이션의 Kubernetes 배포 계약 준비

- 컨테이너가 8080 포트에서 비 root 사용자로 실행되는 현재 구조를 유지한다.
- Deployment에 replicas, resource requests/limits, 보안 컨텍스트를 정의한다.
- 초기 단일 노드 환경에서는 replica 1로 시작한다.
- Spring Boot Actuator의 liveness/readiness endpoint를 사용해 생존 여부와 트래픽 수신 가능 상태를 분리한다.
- 종료 중 요청 유실을 줄이도록 graceful shutdown과 적절한 termination grace period를 검토한다.
- 앱 Service는 먼저 ClusterIP로 선언하고 노출 방식은 home overlay에서 결정한다.
- 앱 전용 namespace는 `review-agent`처럼 별도로 만든다.

완료 기준: 이미지 이름과 태그만 바꾸면 Kustomize 렌더링 결과가 유효하고, 로컬 또는 임시 namespace에서 Pod가 Ready가 된다.

### Phase 2. GitHub Actions를 CI와 운영 CD로 분리

1. PR 검증 워크플로
   - `main`으로 향하는 PR에서 JUnit과 Docker build를 수행한다.
   - 원칙적으로 운영 이미지 게시와 GitOps 갱신은 하지 않는다.
   - 현재 `feature/all → dev` 전용 CI가 계속 필요한지 결정하고, 필요하면 별도 브랜치 정책으로 유지한다.

2. main 게시 워크플로
   - 이벤트를 `push` to `main`으로 둔다.
   - 체크아웃한 정확한 merge commit에서 테스트한다.
   - `GITHUB_TOKEN`의 `packages: write`로 GHCR에 로그인하고 이미지를 게시한다.
   - OCI source/revision 라벨을 포함해 이미지와 저장소를 연결한다.
   - SHA 태그와 digest를 후속 GitOps 갱신 job의 출력으로 전달한다.

3. GitOps 갱신 job
   - 이미지 게시 job 성공 후에만 실행한다.
   - GitOps 저장소를 체크아웃하고 `apps/review-for-pr-agent/overlays/home`의 이미지 태그 또는 digest 한 곳만 변경한다.
   - Kustomize build 및 스키마 검증 후 앱 전용 branch에 봇 커밋을 만들고 PR을 생성한다.
   - 변경 경로와 검증 결과가 기준을 만족하면 auto-merge하거나, 운영 승인 정책에 따라 사람이 merge한다.
   - 새 커밋이 이미 반영돼 있으면 성공으로 종료해 재실행에 안전하게 한다.
   - concurrency 그룹을 `review-for-pr-agent-production-home`처럼 앱과 환경 조합으로 고정하고 오래된 실행은 취소하거나 최신성을 재검증한다.

완료 기준: `main`의 한 커밋마다 GHCR 이미지가 하나 생성되고, 정확히 그 이미지로 GitOps 커밋이 생성된다. PR 커밋만으로 운영 선언은 바뀌지 않는다.

### Phase 3. GitOps 저장소 생성과 기본 선언 작성

- GitOps 저장소는 private으로 생성해 홈 네트워크 주소, 호스트명과 배포 구조를 보호한다.
- 위 권장 디렉터리 구조를 만든다.
- `apps/review-for-pr-agent/base`에는 Deployment와 ClusterIP Service를 두고, home overlay에는 namespace, image, replica 수, 리소스, Ingress, 환경별 ConfigMap을 둔다.
- Ingress Controller 같은 공통 구성 요소는 `platform`에 두어 개별 앱 수명주기와 분리한다.
- Secret 값, 토큰, kubeconfig, 개인키는 커밋하지 않는다.
- `workloads` AppProject는 앱 namespace만, `platform` AppProject는 승인된 cluster-scoped 리소스만 다룰 수 있도록 권한을 분리한다.
- `review-for-pr-agent` child Application에는 automated sync, `prune`, `selfHeal`, `CreateNamespace`를 명시하되 잘못된 빈 렌더 결과로 전체 삭제되지 않게 보호한다.
- root application은 `clusters/home`만 바라보게 하고 최초 한 번만 수동 bootstrap한다.
- 앱 추가·삭제 시 child Application, namespace, AppProject 권한과 cascading deletion 영향을 함께 검토한다.
- PR에서 `kustomize build`와 Kubernetes 스키마 검증을 실행한다.

완료 기준: GitOps 저장소만 clone하면 home 환경의 모든 등록 앱과 platform 구성을 파악할 수 있고, 새 앱이 기존 앱 디렉터리를 수정하지 않고 추가되며, 비밀값은 포함하지 않는다.

### Phase 4. 외부 트래픽 경로 구축

외부 요청은 다음 경로로 전달한다.

```text
사용자
  → Cloudflare DNS Proxy / CDN / WAF
  → HTTPS
  → AWS EC2 Edge Reverse Proxy
  → Tailscale tailnet
  → 홈서버 Kubernetes Ingress Controller의 NodePort
  → Ingress host/path rule
  → ClusterIP Service
  → Spring Boot Pod
```

EC2는 Kubernetes worker가 아닌 edge reverse proxy로 운영한다. Kubernetes host/path 라우팅은 홈 클러스터의 Ingress Controller가 담당한다. EC2는 Tailscale을 통해 Ingress Controller의 NodePort에 접근하므로 이 경로에 MetalLB는 필요하지 않다.

EC2와 Tailscale 준비는 Argo CD 설치와 병행할 수 있다. Ingress Controller와 앱 Ingress는 Argo CD 부트스트랩 이후 GitOps로 배포하고, 전체 외부 경로는 앱의 첫 동기화가 끝난 뒤 검증한다.

#### 4-1. 주소, 도메인 및 책임 범위 확정

- 외부 hostname, EC2 리전·크기, Elastic IP를 정한다.
- EC2 운영체제와 reverse proxy 제품 하나를 정하고 버전을 고정한다.
- EC2에는 `tag:edge-proxy`, 홈서버에는 `tag:home-k8s` machine identity를 사용한다.
- EC2 upstream은 홈서버의 Tailscale IP 또는 MagicDNS 이름을 사용한다. 운영 중 한 방식을 기준으로 정하고 문서화한다.
- 공개 hostname과 Kubernetes backend Service의 대응 관계를 관리한다. Argo CD UI/API는 외부 공개 대상에서 제외한다.
- EC2/VPC/Tailscale/Cloudflare 설정은 별도 runbook으로 관리하고, 반복 가능한 상태가 되면 Terraform/Ansible 및 Tailscale API 기반 IaC로 관리한다.

완료 기준: DNS명, EC2 주소, tailnet 장비 identity, 공개 대상, 관리 책임 경계가 문서로 확정된다.

#### 4-2. EC2와 Cloudflare edge 구성

- EC2에 Elastic IP를 연결하고 reverse proxy와 Tailscale client만 운영한다.
- Security Group과 OS 방화벽은 443을 Cloudflare IP 범위에만 허용한다. 80은 HTTPS redirect 또는 인증서 발급에 필요한 경우에만 허용한다.
- EC2 관리에는 Systems Manager Session Manager를 우선하며 SSH를 전체 인터넷에 공개하지 않는다.
- 알 수 없는 `Host`와 EC2 IP 직접 요청은 기본 virtual host에서 거절한다.
- Cloudflare에는 Elastic IP를 가리키는 proxied A 레코드를 만들고 SSL/TLS mode를 `Full (strict)`로 설정한다.
- EC2에는 공개 CA 또는 Cloudflare Origin CA 인증서를 설치하고 만료·교체 절차를 둔다.
- 필요한 WAF와 rate limit 정책을 적용하고 애플리케이션 health endpoint가 정상 통과하는지 확인한다.

완료 기준: Cloudflare hostname으로만 HTTPS 서비스에 접근할 수 있고 EC2 우회 요청과 불필요한 공개 포트가 차단된다.

#### 4-3. EC2와 홈서버 간 Tailscale 연결 구성

- 홈서버의 현재 Tailscale 설치와 machine identity를 사용하고 EC2를 같은 tailnet에 등록한다.
- 서버 등록에는 tagged, pre-authorized, one-off auth key를 사용하고 자격 증명은 AWS Secrets Manager/SSM Parameter Store 또는 별도 비밀 저장소에 보관한다.
- 홈서버 identity를 `tag:home-k8s`로 전환하기 전에 기존 사용자 기반 접근 규칙과 새 Grants를 검증한다.
- Tailscale Grants는 `tag:edge-proxy`에서 `tag:home-k8s`의 Ingress NodePort로 향하는 TCP 연결만 허용한다. 관리용 SSH와 Kubernetes API 접근은 별도 정책으로 분리한다.
- EC2는 홈서버의 Tailscale IP에 직접 연결하며 subnet router와 exit node는 구성하지 않는다.
- `tailscaled`의 부팅 자동 시작과 재접속을 확인하고 `tailscale ping` 및 `tailscale netcheck`로 direct/DERP 경로의 지연과 처리량을 기록한다.
- 직접 연결 최적화가 필요한 경우에만 Tailscale 공식 지침에 따라 UDP 41641 방화벽 규칙을 검토한다.

완료 기준: 홈 공유기에 포트 포워딩하지 않아도 EC2에서 홈서버의 Tailscale 주소와 지정된 Ingress 포트에 안정적으로 접근할 수 있고, grant에 없는 홈 LAN·Kubernetes API·SSH에는 접근할 수 없다.

#### 4-4. 홈 Kubernetes Ingress Controller 설치

- ingress-nginx 또는 Traefik 중 하나를 선택하고 Kubernetes v1.37.0 호환 버전으로 고정한다.
- 단일 노드 홈서버에서는 Ingress Controller replica 1로 시작하고 resource requests/limits를 설정한다.
- Controller Service는 NodePort로 노출하되 인터넷에는 공개하지 않는다.
- Tailscale Grant와 홈서버 OS 방화벽에서 해당 NodePort의 source를 `tag:edge-proxy` 장비 또는 EC2 Tailscale IP로 제한한다. 홈 공유기에는 NodePort 포트 포워딩을 만들지 않는다.
- `IngressClass`를 명시하고 앱 Ingress가 그 class만 사용하도록 한다.
- 앱 Ingress에 외부 hostname과 Spring Service의 8080 포트 라우팅을 선언한다.
- Ingress Controller는 `platform`, 앱 Ingress는 `apps/<app>/overlays/home`에서 관리한다.

완료 기준: EC2에서 Tailscale 경유로 Ingress NodePort에 `Host` 헤더를 지정했을 때 `/hello`가 응답하고, 동일 NodePort는 인터넷에서 직접 접근할 수 없다.

#### 4-5. 프록시 연결 및 전체 경로 검증

- 공개 hostname별 upstream을 홈서버 Tailscale IP 또는 MagicDNS 이름과 Ingress Controller NodePort로 설정한다.
- 원래의 `Host` 헤더를 유지해 Kubernetes Ingress host rule이 정상 작동하게 한다.
- `X-Forwarded-For`, `X-Forwarded-Proto`, request ID를 일관되게 전달한다.
- Cloudflare의 실제 사용자 IP 헤더는 요청 source가 Cloudflare IP 범위인 경우에만 신뢰한다. 인터넷에서 위조한 헤더를 그대로 신뢰하지 않는다.
- 정상 경로 `Cloudflare → EC2 → Tailscale → Ingress → Service → Pod`를 구간별로 확인한다.
- 외부 hostname의 HTTPS와 `/hello` 응답, EC2에서 backend로 보내는 `Host` 기반 요청을 각각 확인한다.
- EC2 IP 직접 요청, 임의 `Host`, 홈 공인 IP와 NodePort 직접 접근이 차단되는지 확인한다.
- Tailscale 연결 중단, DERP 전환, Ingress Controller 재시작, 앱 rollout, 홈서버 재부팅, EC2 재부팅 후의 실패 형태와 자동 복구를 확인한다.
- Cloudflare, EC2 reverse proxy, Tailscale, Ingress Controller, Spring 앱 로그의 timestamp와 request ID를 맞춰 한 요청을 추적할 수 있게 한다.
- 단일 EC2와 단일 홈 노드를 각각 단일 장애점으로 문서화한다.

Kubernetes v1.37.0은 매우 최신 버전이므로 선택한 Argo CD와 Ingress Controller 버전의 지원 범위를 설치 직전에 확인한다. 모든 구성 요소 버전은 `stable` 같은 이동 태그가 아니라 명시 버전으로 고정한다.

완료 기준: 정상 요청, 우회 차단, 재부팅 복구, Tailscale direct/DERP 전환과 연결 단절, 애플리케이션 롤백까지 시험하고 결과와 복구 절차를 runbook에 남긴다.

### Phase 5. Argo CD 부트스트랩

- `argocd` namespace를 생성한다.
- Argo CD 공식 설치 매니페스트의 명시 버전을 server-side apply 방식으로 설치한다.
- 설치 직후 모든 Argo CD Pod가 Ready인지, CRD가 생성됐는지 확인한다.
- 최초 접근은 port-forward로 제한한다.
- 초기 admin 암호를 즉시 변경하고, 공식 지침에 따라 초기 암호 Secret을 제거한다.
- Argo CD에는 private GitOps 저장소의 read-only deploy key 또는 read-only 토큰만 제공한다.
- `workloads`와 `platform` AppProject의 권한 범위를 검토한다.
- `bootstrap/argocd/root-application.yaml`을 최초 한 번 수동 적용한다. 이후 root application이 `clusters/home`의 child Applications와 AppProjects를 선언적으로 관리하게 한다.
- root application 소스 경로는 관리자 수준이므로 일반 앱 CD 토큰에 수정 권한을 주지 않는다.
- Argo CD 자체 설정의 GitOps 관리는 애플리케이션 배포 경로가 안정화된 뒤 별도 범위로 수행한다.

완료 기준: Argo CD가 GitOps 저장소의 root application, child Applications, AppProjects를 정상적으로 읽고 동기화 상태를 계산한다.

### Phase 6. 첫 GitOps 동기화와 서비스 검증

- 첫 sync는 수동으로 실행해 생성될 namespace, Deployment, Service를 확인한다.
- Pod 이벤트에서 GHCR 인증, image pull, CNI, probe 실패가 없는지 확인한다.
- `rollout status`와 Argo CD health가 모두 정상인지 확인한다.
- 선택한 접근 방식으로 `/hello`가 기대한 응답을 반환하는지 확인한다.
- 성공한 뒤 automated sync를 활성화한다.
- 테스트용으로 안전한 필드 하나를 클러스터에서 변경해 `selfHeal`이 복원하는지 확인한다.
- GitOps 커밋을 revert해 이전 이미지로 롤백되는지 연습한다.

완료 기준: 배포, health 확인, drift 복구, Git revert 롤백이 모두 재현 가능하다.

### Phase 7. end-to-end 자동 배포 검증

테스트용 작은 변경을 다음 순서로 흘려보낸다.

1. 앱 저장소에 PR 생성
2. JUnit 및 이미지 build 검증 통과
3. `main` 병합
4. merge commit SHA 이미지가 GHCR에 게시됨
5. GitOps 저장소에 해당 SHA/digest 변경 커밋 생성됨
6. Argo CD가 새 GitOps revision을 감지하고 sync함
7. 새 Pod가 Ready가 된 뒤 이전 Pod가 종료됨
8. `/hello` 응답과 Argo CD 상태 확인
9. 앱 SHA, 이미지 digest, GitOps commit, 실행 로그를 한 이력으로 대조

어느 단계가 실패하더라도 다음 단계로 진행되지 않게 한다. 특히 이미지 push 실패 시 GitOps 저장소를 갱신해서는 안 된다.

완료 기준: 사람의 `kubectl apply` 없이 PR merge부터 홈서버 배포까지 한 번 이상 성공하고, 실패 위치와 재시도 방법이 문서화된다.

### Phase 8. 운영 안정화

- GitHub Actions와 Argo CD의 최소 권한을 재검토한다.
- Actions를 커밋 SHA로 pin하고 Dependabot/Renovate로 갱신하는 방안을 검토한다.
- 컨테이너 이미지 취약점 스캔과 SBOM/서명을 후속 단계로 추가한다.
- NetworkPolicy로 앱 ingress/egress와 Argo CD 구성 요소 간 통신 범위를 점진적으로 제한한다.
- 단일 노드 장애를 전제로 etcd 스냅샷과 복구 절차를 만든다.
- GitOps 저장소가 클러스터 재구축 문서 역할을 하게 하되, 외부 Secret 원본과 복원 절차는 별도 안전한 위치에 둔다.
- 로그/메트릭/알림은 배포 경로가 안정화된 뒤 추가한다.

## 5. 환경별로 실제 값을 정해야 하는 항목

아래 값을 GitOps 저장소 README와 운영 runbook에 기록한다.

| 항목 | 계획 값 | 운영 주의점 |
|---|---|---|
| 앱 namespace | `review-agent` | 다른 앱과 격리 |
| GitOps 저장소 역할 | 홈 클러스터 통합 저장소 | 앱 소스 저장소와 분리 |
| Argo CD 등록 방식 | root application + 명시적 child Applications | 앱 증가 시 ApplicationSet 전환 검토 |
| 프로젝트 분리 | `workloads`, `platform` | platform에만 필요한 cluster 권한 부여 |
| GitOps 저장소 공개 여부 | private | Argo는 read-only, CI는 PR 작성 권한으로 분리 |
| GHCR 이미지 공개 여부 | public | 클러스터 pull secret 불필요 |
| 운영 이미지 식별 | main commit SHA + digest | `latest` 배포 금지 |
| Argo CD 접근 | port-forward | 장기적으로도 외부 공개 최소화 |
| 외부 트래픽 경로 | Cloudflare → EC2 → Tailscale → 홈 K8s Ingress | EC2를 Kubernetes worker로 가입시키지 않음 |
| EC2 origin 주소 | Elastic IP | Cloudflare DNS target의 안정성 확보 |
| Edge proxy | Nginx/HAProxy/Traefik 중 하나 | Host 및 forwarding header 보존 |
| 사설 연결 | Tailscale | tagged machine identity와 Grants 사용 |
| Tailscale 경로 | direct 우선, DERP fallback 허용 | 실제 지연·처리량을 측정해 판단 |
| K8s ingress | ingress-nginx 또는 Traefik 중 하나 | Controller NodePort는 EC2 tailnet identity만 허용 |
| 외부 TLS | Cloudflare `Full (strict)` + EC2 origin 인증서 | 인증서 갱신과 만료 알림 필요 |
| Secret 관리 | bootstrap 자격 증명은 외부 보관, workload는 SOPS 또는 Sealed Secrets | 평문 Git 커밋 금지 |

## 6. 운영 Runbook에 반드시 남길 항목

- 정상 배포 상태를 확인하는 명령과 URL
- Argo CD repo 인증 갱신 방법
- GHCR pull 실패 시 확인 순서
- 마지막 정상 이미지와 GitOps revision을 찾는 방법
- Git revert 기반 롤백 절차
- Argo CD 자체 장애 시 수동 복구 절차
- 노드 재부팅 후 kubelet, Calico, CoreDNS, Argo CD, 앱 순서의 점검표
- etcd 백업 위치, 주기, 실제 복원 훈련 기록
- Cloudflare DNS/WAF 변경과 원복 절차
- EC2 Security Group, OS 방화벽 및 Cloudflare IP 범위 갱신 절차
- Tailscale 장비 등록·tag 변경·auth key 폐기·machine 제거 절차
- Tailscale Grants 변경과 policy test, direct/DERP 연결 진단 방법
- EC2 reverse proxy 설정 검증·reload·rollback 절차
- Cloudflare, EC2, Tailscale, Ingress, 앱을 구간별로 진단하는 순서
- EC2 origin 인증서 갱신 및 만료 대응 절차
- root/child Application과 AppProject 변경·추가·안전한 삭제 절차

## 7. 권장 실행 순서 요약

- [ ] `.docker-config` 추적 파일 보안 조치
- [ ] GHCR visibility와 GitOps 저장소 visibility 결정
- [ ] PR CI와 `main` 이미지 게시 CD 분리
- [ ] 통합 GitOps 저장소의 apps/platform/clusters/bootstrap 구조 생성
- [ ] review-for-pr-agent base/home overlay와 child Application 등록
- [ ] workloads/platform AppProject와 root application 작성
- [ ] 앱 매니페스트 로컬 렌더·임시 배포 검증
- [ ] 홈서버에서 선택한 Argo CD 버전 호환성 확인 및 설치
- [ ] GitOps repo read-only 연결, root application과 child Applications 적용
- [ ] 첫 수동 sync, health, `/hello`, rollback 검증
- [ ] 앱 CD에 GitOps 갱신 job 추가
- [ ] PR merge 기반 end-to-end 배포 검증
- [ ] automated sync 활성화
- [ ] 외부 hostname, EC2 Elastic IP, Tailscale machine tags 및 공개 범위 확정
- [ ] EC2 edge proxy와 최소 권한 Security Group 구성
- [ ] EC2–홈서버 Tailscale 등록, Grants 및 재부팅 복구 검증
- [ ] 홈 K8s Ingress Controller 및 tailnet 전용 NodePort 구성
- [ ] EC2 proxy에서 Ingress upstream 및 전달 헤더 구성
- [ ] Cloudflare proxied DNS와 `Full (strict)` TLS 연결
- [ ] 전체 외부 경로, 우회 차단 및 장애 복구 시험
- [ ] 백업·복구·비밀 회전 runbook 작성

## 8. 최종 완료 기준

- `main`에 병합되지 않은 코드는 홈서버 운영 환경에 배포되지 않는다.
- 운영 중인 이미지가 어느 앱 커밋에서 생성됐는지 즉시 추적할 수 있다.
- 클러스터의 실제 상태와 GitOps 저장소 선언이 다르면 Argo CD가 탐지하고 복구한다.
- 배포 또는 probe 실패 시 이전 정상 버전이 유지되거나 Git revert로 복구할 수 있다.
- GitHub, GHCR, GitOps 저장소, Kubernetes 어느 곳에도 불필요한 광범위 토큰이나 평문 비밀이 없다.
- 홈서버를 재구축하더라도 GitOps 저장소와 별도 보관한 Secret/백업으로 서비스를 복원할 수 있다.
- 공개 트래픽은 Cloudflare와 EC2 edge를 통과한 뒤 Tailscale로만 홈서버에 도달하며, 홈 공인 IP와 Kubernetes NodePort는 인터넷에 직접 노출되지 않는다.
- Cloudflare, EC2, Tailscale, Ingress 각 구간의 장애 위치를 식별하고 개별적으로 복구할 수 있다.
- `review-for-pr-agent` 외의 애플리케이션도 동일한 디렉터리·Application·권한 규칙으로 추가할 수 있다.
- root application에서 홈 클러스터의 workload와 platform 구성 전체를 확인하되, 앱별 배포와 권한은 서로 격리된다.

## 참고 문서

- [Argo CD Getting Started](https://argo-cd.readthedocs.io/en/stable/getting_started/)
- [GitHub Container Registry 사용 및 권한](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
- [Kubernetes Ingress 개념](https://kubernetes.io/docs/concepts/services-networking/ingress/)
- [Cloudflare proxied DNS 동작](https://developers.cloudflare.com/dns/proxy-status/)
- [Cloudflare가 proxy하는 네트워크 포트](https://developers.cloudflare.com/fundamentals/reference/network-ports/)
- [Tailscale AWS VM 설치](https://tailscale.com/docs/install/cloud/aws/quickstart)
- [Tailscale Grants](https://tailscale.com/docs/features/access-control/grants)
- [Tailscale 서버용 tags와 auth keys](https://tailscale.com/docs/servers)
- [Tailscale 방화벽 포트 지침](https://tailscale.com/docs/reference/faq/firewall-ports)
- [Argo CD Cluster Bootstrapping](https://argo-cd.readthedocs.io/en/stable/operator-manual/cluster-bootstrapping/)
