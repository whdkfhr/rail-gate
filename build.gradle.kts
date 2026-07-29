plugins {
    java
    // 루트에서는 적용하지 않는다. apps/* 만 실행 가능한 애플리케이션이다.
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "com.railgate"
    version = "0.1.0-SNAPSHOT"
}

val codeProjects = subprojects.filter { it.buildFile.isFile }

configure(codeProjects) {
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

        // Gradle 9 는 JUnit Platform launcher 를 자동으로 주입하지 않는다.
        // 버전은 Spring Boot BOM 이 관리하므로 여기에 적지 않는다.
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
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

tasks.register("verifyArchitectureRules") {
    group = "verification"
    description = "문서화된 모듈 경계와 도메인 순수성 규칙을 빠르게 검증한다."

    doLast {
        val violations = mutableListOf<String>()
        val forbiddenDomainImports = mapOf(
            "org.springframework." to "Spring 의존성은 apps 또는 infra 모듈에만 둔다.",
            "jakarta.persistence." to "JPA 의존성은 infra 모듈에만 둔다.",
            "javax.persistence." to "JPA 의존성은 infra 모듈에만 둔다.",
            "org.hibernate." to "Hibernate 의존성은 infra 모듈에만 둔다.",
            "org.springframework.data.redis." to "Redis 의존성은 infra 모듈에만 둔다.",
            "redis.clients." to "Redis 의존성은 infra 모듈에만 둔다.",
            "io.lettuce." to "Redis 의존성은 infra 모듈에만 둔다.",
            "org.apache.kafka." to "Kafka 의존성은 infra/support 모듈에만 둔다.",
        )

        fun boundedContext(path: String): String? = when {
            path == ":modules:common" -> null
            path == ":modules:queue-token" -> null
            path.contains(":queue-") -> "queue"
            path.contains(":reservation-") -> "reservation"
            else -> null
        }

        fun isVersionedExternalDependency(line: String): Boolean {
            val dependencyCall = Regex("""\b(api|implementation|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly)\("([^"]+)"\)""")
            val coordinate = dependencyCall.find(line)?.groupValues?.get(2) ?: return false
            if (coordinate.startsWith("project(")) {
                return false
            }
            return coordinate.count { it == ':' } >= 2
        }

        codeProjects.forEach { project ->
            val mainJavaDir = project.layout.projectDirectory.dir("src/main/java").asFile
            if (project.name.endsWith("-domain") && mainJavaDir.exists()) {
                mainJavaDir.walkTopDown()
                    .filter { it.isFile && it.extension == "java" }
                    .forEach { sourceFile ->
                        sourceFile.useLines { lines ->
                            lines.forEachIndexed { index, line ->
                                val matchedImport = forbiddenDomainImports.keys.firstOrNull { line.contains("import $it") }
                                if (matchedImport != null) {
                                    violations += "${sourceFile.relativeTo(rootDir)}:${index + 1} - ${forbiddenDomainImports.getValue(matchedImport)}"
                                }
                            }
                        }
                    }
            }

            val implementationDependencies = mutableListOf<org.gradle.api.Project>()
            listOfNotNull(
                project.configurations.findByName("api"),
                project.configurations.findByName("implementation"),
                project.configurations.findByName("runtimeOnly"),
            ).forEach { configuration ->
                configuration.dependencies.forEach { dependency ->
                    if (dependency is org.gradle.api.artifacts.ProjectDependency) {
                        rootProject.findProject(dependency.path)?.let { implementationDependencies += it }
                    }
                }
            }

            implementationDependencies.forEach { dependencyProject ->
                if (project.path.startsWith(":modules:") && dependencyProject.path.startsWith(":apps:")) {
                    violations += "${project.path} -> ${dependencyProject.path} - modules 는 apps 에 의존할 수 없다."
                }

                if (project.name.endsWith("-domain") && dependencyProject.path != ":modules:common") {
                    violations += "${project.path} -> ${dependencyProject.path} - domain 모듈은 common 외 모듈에 의존하지 않는다."
                }

                val projectContext = boundedContext(project.path)
                val dependencyContext = boundedContext(dependencyProject.path)
                if (projectContext != null && dependencyContext != null && projectContext != dependencyContext) {
                    violations += "${project.path} -> ${dependencyProject.path} - queue 와 reservation 경계를 직접 넘지 않는다."
                }
            }

            project.buildFile.readLines().forEachIndexed { index, line ->
                if (isVersionedExternalDependency(line)) {
                    violations += "${project.buildFile.relativeTo(rootDir)}:${index + 1} - 외부 의존성 버전은 gradle/libs.versions.toml 에 둔다."
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Architecture rule violations:\n" + violations.joinToString(separator = "\n") { "- $it" }
            )
        }
    }
}

codeProjects.forEach { project ->
    project.tasks.named("check").configure {
        dependsOn(rootProject.tasks.named("verifyArchitectureRules"))
    }
}

// 버전 카탈로그를 subprojects 블록에서 쓰기 위한 접근자.
// (type-safe accessor 는 루트 스크립트 최상위에서만 자동 생성된다)
val org.gradle.api.Project.libs: org.gradle.accessors.dm.LibrariesForLibs
    get() = rootProject.extensions.getByName("libs") as org.gradle.accessors.dm.LibrariesForLibs
