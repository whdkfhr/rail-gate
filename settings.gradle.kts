rootProject.name = "rail-gate"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // 저장소 선언을 settings 한 곳으로 강제한다.
    // 모듈이 제각각 repositories 를 선언하면 빌드 재현성이 깨진다.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// ---------------------------------------------------------------------------
// 모듈 구성
//
// apps/    : 배포 단위(실행 가능한 Spring Boot 애플리케이션)
// modules/ : 라이브러리. 단독 실행되지 않는다.
//
// 의존 방향은 항상 apps -> modules 이며 그 역은 금지한다.
// 모듈 간 허용/금지 규칙은 CLAUDE.md 와 docs/ARCHITECTURE.md 에 명시한다.
// ---------------------------------------------------------------------------
include(
    "apps:queue-service",
    "apps:reservation-service",

    "modules:common",
    "modules:queue-domain",
    "modules:queue-token",
    "modules:reservation-domain",
)
