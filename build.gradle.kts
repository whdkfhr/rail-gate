plugins {
    java
    // 루트에서는 적용하지 않는다. apps/* 만 실행 가능한 애플리케이션이다.
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "com.railgate"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    java {
        // PATH 의 java 버전과 무관하게 항상 Java 21 로 컴파일한다.
        // (현재 개발 머신 기본 java 는 17 이다)
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    dependencies {
        // 모든 모듈이 Spring Boot BOM 을 통해 버전을 관리받는다.
        // 개별 spring 의존성에는 버전을 적지 않는다.
        add("implementation", platform(rootProject.libs.spring.boot.dependencies))
        add("testImplementation", platform(rootProject.libs.spring.boot.dependencies))

        add("testImplementation", "org.springframework.boot:spring-boot-starter-test")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // 컴파일 경고를 놓치지 않는다. 동시성 코드에서 unchecked 경고는 실제 버그 신호인 경우가 많다.
        options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// 버전 카탈로그를 subprojects 블록에서 쓰기 위한 접근자.
// (type-safe accessor 는 루트 스크립트 최상위에서만 자동 생성된다)
val org.gradle.api.Project.libs: org.gradle.accessors.dm.LibrariesForLibs
    get() = rootProject.extensions.getByName("libs") as org.gradle.accessors.dm.LibrariesForLibs
