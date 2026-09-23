# Spring Boot 프로젝트 설정 및 실행 기록

## 프로젝트 정보

- 프로젝트 경로: `/home/woongang-kim/Projects/k8s_review_agent`
- 빌드 도구: Gradle Groovy
- 언어: Java
- 패키징: Jar
- Spring Boot: 4.1.1
- Java: Temurin JDK 17

## 수행한 작업

### 1. Spring Initializr 프로젝트 생성

Spring Initializr에서 다음 설정으로 프로젝트를 생성했다.

- Project: Gradle - Groovy
- Language: Java
- Packaging: Jar
- Group: `com.example`
- Artifact: `demo`

생성된 프로젝트를 작업공간 루트에 배치했다.

### 2. JDK 설치

시스템 전역 `sudo` 권한이 없어 프로젝트 내부에 JDK 17을 설치했다.

```text
.jdk/
```

JDK 버전:

```text
17.0.20.1 (Eclipse Temurin)
```

### 3. Spring MVC 의존성 추가

`build.gradle`에 다음 의존성을 추가했다.

```groovy
implementation 'org.springframework.boot:spring-boot-starter-webmvc'
```

### 4. 간단한 Controller 작성

파일:

```text
src/main/java/com/example/demo/HelloController.java
```

```java
package com.example.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    @GetMapping("/hello")
    public String hello() {
        return "Hello, Spring Boot!";
    }
}
```

### 5. `hello()` JUnit 테스트 작성

파일:

```text
src/test/java/com/example/demo/HelloControllerTest.java
```

```java
package com.example.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HelloControllerTest {

    @Test
    void hello_returnsGreeting() {
        HelloController controller = new HelloController();

        String result = controller.hello();

        assertEquals("Hello, Spring Boot!", result);
    }
}
```

### 6. VS Code 프로젝트 설정

파일:

```text
.vscode/settings.json
```

프로젝트 내부 JDK를 Java 확장 기능과 VS Code 통합 터미널이 사용하도록 설정했다.

```json
{
  "java.jdt.ls.java.home": "${workspaceFolder}/.jdk",
  "java.import.gradle.java.home": "${workspaceFolder}/.jdk",
  "terminal.integrated.env.linux": {
    "JAVA_HOME": "${workspaceFolder}/.jdk",
    "PATH": "${workspaceFolder}/.jdk/bin:${env:PATH}"
  },
  "files.exclude": {
    "**/.vscode": false,
    "**/.jdk": false,
    "**/.gradle": false,
    "**/.git": false
  },
  "explorer.excludeGitIgnore": false
}
```

따라서 기존 VS Code 터미널을 닫고 새 터미널을 열면 별도의 `export` 없이 Gradle 명령을 실행할 수 있다.

## 빌드 및 테스트

프로젝트 루트에서 실행한다.

```bash
./gradlew test bootJar --no-daemon
```

생성된 JAR:

```text
build/libs/demo-0.0.1-SNAPSHOT.jar
```

테스트 결과:

```text
tests=2, skipped=0, failures=0, errors=0
```

JUnit 테스트 실행 결과:

```text
BUILD SUCCESSFUL
```

## 애플리케이션 실행

8080 포트는 기존 VS Code 프로세스가 사용 중이므로 8081 포트로 실행한다.

```bash
./gradlew bootRun --args='--server.port=8081'
```

서버가 실행된 뒤 Chrome 또는 다른 브라우저에서 다음 주소를 연다.

```text
http://localhost:8081/hello
```

## curl 통신 검증

새 터미널에서 실행한다.

```bash
curl -i http://127.0.0.1:8081/hello
```

검증 결과:

```text
HTTP/1.1 200
Content-Type: text/plain;charset=UTF-8

Hello, Spring Boot!
```

테스트가 끝나면 서버가 실행 중인 터미널에서 `Ctrl + C`를 눌러 종료한다.
