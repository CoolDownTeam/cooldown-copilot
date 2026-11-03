# 프로젝트
- Pleos Connect SDK(AAOS 기반) 차량용 앱 `com.pleos.drivinggame`. minSdk 28, Gradle 8+.
- 모듈: `automotive`(메인 차량 앱, Compose), `mobile`, `shared`.
- 패키지 구조: data / domain / presentation / service / di / util. 새 코드도 이 계층을 따른다.
- 사용 중인 SDK: Vehicle, NaviHelper (버전은 gradle/libs.versions.toml). 의존성은 버전 카탈로그로만 추가한다.

# 언어
- 새 코드는 Kotlin. 기존 Java 파일은 Java로 수정하고, 요청 없이 Kotlin으로 변환하지 않는다.
- `!!` 금지. null은 `?.`, `?:`, early return으로 처리한다.
- 비동기는 Coroutines + StateFlow. LiveData, AsyncTask, RxJava는 새로 쓰지 않는다.
- 어노테이션 처리는 KSP를 쓴다 (kapt와 같이 쓰지 않는다).

# 차량 앱 규칙
- 운전 안전 최우선: 주행 중에는 터치 입력·긴 텍스트·영상을 쓰지 않고, 음성 한 문장이나 무상호작용으로 처리한다.
- 주행 중에도 쓰는 화면에는 Driver Distraction Optimized 선언과 UX 제한 처리를 한다.
- 차량 데이터 권한은 AndroidManifest에 필요한 것만 선언한다.
- AAOS 표준 API가 있으면 그걸 먼저 쓰고, Pleos SDK는 AAOS에 없는 기능에만 쓴다.
- SDK API는 추측하지 말고 Pleos 문서(document.pleos.ai)나 SDK jar를 확인한다.

# 빌드·검증
- 빌드: `./gradlew :automotive:assembleDebug -q`
- 테스트: `./gradlew :automotive:testDebugUnitTest -q`
- 린트: `./gradlew :automotive:lintDebug -q`
- 완료라고 말하기 전에 빌드와 테스트를 통과시킨다. 실패 시 출력은 마지막 50줄만 본다.

# 읽지 말 것
- build/, .gradle/, .kotlin/, .idea/, *.apk 같은 생성 파일은 읽지 않는다.
