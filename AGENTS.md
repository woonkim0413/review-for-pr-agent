# Repository Guidelines

## Project Structure & Module Organization

This is a single-module Spring Boot project built with Gradle:

- `src/main/java/com/example/demo/`: application and production Java code.
- `src/main/resources/`: runtime resources such as `application.properties`.
- `src/test/java/com/example/demo/`: JUnit 5 tests.
- `gradle/wrapper/`, `gradlew`, and `gradlew.bat`: pinned Gradle Wrapper files.
- `build/`, `.gradle/`, `.jdk/`, and `.vscode/` contain generated or local-only content and are ignored by Git.

## Build, Test, and Development Commands

Run commands from the repository root:

```bash
./gradlew test --no-daemon                 # Run all tests
./gradlew bootJar --no-daemon              # Build the executable JAR
./gradlew bootRun --args='--server.port=8081'  # Run locally
curl http://127.0.0.1:8081/hello           # Verify the sample endpoint
```

The project uses the JDK configured in `.vscode/settings.json` when run from a new VS Code terminal. The generated JAR is written to `build/libs/` and should not be committed.

## Coding Style & Naming Conventions

Use Java 17-compatible code, package names in lowercase, `PascalCase` for classes, and `lowerCamelCase` for methods and variables. Follow the existing tab-based indentation in Java and Gradle files. Keep controllers thin and place reusable business logic in separate services. No formatter or linter is currently configured; keep changes consistent with nearby code.

## Testing Guidelines

Tests use JUnit 5 through Spring Boot’s test dependencies. Name test classes with the `Test` suffix and test methods after the behavior they verify, such as `hello_returnsGreeting`. Add focused unit tests for pure logic and Spring context or MVC tests for integration behavior. Run `./gradlew test` before submitting changes.

## Commit & Pull Request Guidelines

The repository currently has only an initial setup commit, so no established commit convention exists. Use short, imperative subjects, for example `Add GitHub webhook handler`. Pull requests should describe the behavior changed, list validation commands and results, identify configuration changes, and include request/response examples for API changes.

## Security & Configuration Tips

Never commit GitHub tokens, LLM API keys, private keys, or local secrets. Read sensitive values from environment variables or Kubernetes Secrets. Keep local overrides outside tracked configuration files.
