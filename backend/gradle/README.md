# Gradle Version Catalogs

이 프로젝트는 Gradle Version Catalogs를 사용하여 의존성 버전을 중앙 집중식으로 관리합니다.

## 📁 구조

```
gradle/
├── libs.versions.toml    # Version Catalog 정의 파일
└── README.md            # 이 문서
```

## 📋 libs.versions.toml 구조

Version Catalog는 4개의 주요 섹션으로 구성됩니다:

### 1. [versions]
버전 번호를 정의합니다. 여러 라이브러리에서 공통으로 사용할 수 있습니다.

```toml
[versions]
spring-boot = "3.5.0"
jjwt = "0.12.5"
```

### 2. [libraries]
실제 라이브러리 의존성을 정의합니다.

```toml
[libraries]
jjwt-api = { module = "io.jsonwebtoken:jjwt-api", version.ref = "jjwt" }
```

### 3. [bundles]
관련된 라이브러리들을 그룹화합니다.

```toml
[bundles]
jwt = ["jjwt-api", "jjwt-impl", "jjwt-jackson"]
```

### 4. [plugins]
Gradle 플러그인을 정의합니다.

```toml
[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
```

## 🚀 사용 방법

### build.gradle.kts에서 사용

#### 플러그인 적용
```kotlin
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}
```

#### 개별 라이브러리 사용
```kotlin
dependencies {
    implementation(libs.mysql.connector.j)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
}
```

#### 번들(Bundle) 사용
```kotlin
dependencies {
    // 여러 관련 라이브러리를 한 번에 추가
    implementation(libs.bundles.spring.boot.starters)
    implementation(libs.bundles.flyway)
    runtimeOnly(libs.bundles.jwt)
}
```

## 💡 장점

### 1. 중앙 집중식 버전 관리
- 모든 버전을 한 곳에서 관리
- 버전 업데이트 시 `libs.versions.toml` 파일만 수정

### 2. 타입 안전성
- IDE 자동완성 지원
- 컴파일 타임에 오타 감지
- 리팩토링 지원

### 3. 멀티모듈 대응
- 모든 모듈에서 동일한 버전 사용
- 버전 불일치 방지

### 4. 가독성 향상
```kotlin
// Before
implementation("io.jsonwebtoken:jjwt-api:0.12.5")

// After
implementation(libs.jjwt.api)
```

### 5. 번들을 통한 그룹 관리
```kotlin
// Before
implementation("org.springframework.boot:spring-boot-starter-web")
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("org.springframework.boot:spring-boot-starter-validation")

// After
implementation(libs.bundles.spring.boot.starters)
```

## 🔧 버전 업데이트 방법

### 1. 개별 라이브러리 버전 업데이트
`gradle/libs.versions.toml` 파일을 수정:

```toml
[versions]
spring-boot = "3.5.1"  # 3.5.0 → 3.5.1로 변경
```

### 2. 새로운 라이브러리 추가

#### Step 1: 버전 정의
```toml
[versions]
redis = "3.2.0"
```

#### Step 2: 라이브러리 정의
```toml
[libraries]
spring-boot-starter-data-redis = { module = "org.springframework.boot:spring-boot-starter-data-redis", version.ref = "redis" }
```

#### Step 3: build.gradle.kts에서 사용
```kotlin
dependencies {
    implementation(libs.spring.boot.starter.data.redis)
}
```

### 3. 새로운 번들 생성

```toml
[bundles]
redis = [
    "spring-boot-starter-data-redis",
    "spring-data-redis"
]
```

## 📊 현재 관리 중인 의존성

### Spring Framework
- Spring Boot 3.5.0
- Spring Boot Starters (Web, Data JPA, Security, Validation)

### Database
- MySQL Connector 9.1.0
- Flyway 11.0.0
- H2 Database 2.3.232 (테스트용)

### Security
- JWT (JJWT) 0.12.5

### Monitoring & Logging
- P6Spy 1.9.2

### API Documentation
- SpringDoc OpenAPI 2.7.0

### Utilities
- Lombok 1.18.34

### Testing
- JUnit Platform 1.11.3
- Spring Security Test
- Spring Boot Test

## 🎯 멀티모듈 전환 시 고려사항

Version Catalogs는 멀티모듈 프로젝트에서 더욱 빛을 발합니다:

```
project/
├── gradle/
│   └── libs.versions.toml    # 전체 프로젝트 공통 버전
├── api-module/
│   └── build.gradle.kts      # libs.* 사용
├── domain-module/
│   └── build.gradle.kts      # libs.* 사용
└── infrastructure-module/
    └── build.gradle.kts      # libs.* 사용
```

### 장점
1. **일관성**: 모든 모듈에서 동일한 버전 사용
2. **효율성**: 한 번의 수정으로 모든 모듈에 적용
3. **안정성**: 버전 불일치로 인한 문제 방지

## 🔍 참고 자료

- [Gradle Version Catalogs 공식 문서](https://docs.gradle.org/current/userguide/platforms.html)
- [Spring Boot with Gradle Version Catalogs](https://spring.io/blog/2021/09/03/spring-boot-gradle-version-catalogs)

## 🤝 컨벤션

### 네이밍 규칙
- **kebab-case 사용**: `spring-boot`, `mysql-connector`
- **명확한 이름**: 라이브러리 이름을 직관적으로 표현
- **접두사 사용**: 관련 라이브러리는 동일한 접두사 사용 (예: `spring-boot-starter-*`)

### 버전 관리
- **메이저/마이너 버전 명시**: `3.5.0` (명확한 버전 표기)
- **호환성 고려**: 버전 업데이트 시 Breaking Changes 확인
- **테스트 필수**: 버전 변경 후 반드시 테스트 실행

## ⚠️ 주의사항

1. **Spring Boot 버전 관리**
   - Spring Boot의 의존성 버전은 대부분 자동 관리됩니다
   - 명시적 버전 지정은 특정 이유가 있을 때만 사용

2. **IDE 캐시**
   - IntelliJ IDEA에서 인식 안될 경우: `Invalidate Caches and Restart`
   - Gradle 동기화: `./gradlew --refresh-dependencies`

3. **버전 충돌**
   - `./gradlew dependencies`로 의존성 트리 확인
   - 필요 시 `constraints` 블록 사용하여 버전 강제 지정

