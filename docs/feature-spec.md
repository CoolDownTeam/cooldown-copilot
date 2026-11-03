# 기능명세서 — 쿨다운 코파일럿 (가칭)

> 버전 1.0 · 2026-11-03
> 요구사항 ID(`C0-01` 등)는 브랜치·커밋·테스트 이름에서 참조한다.
> 수치(임계값·시간)는 모두 초기값이며 `CopilotConfig` 한 곳에서 관리하고, 에뮬레이터 모의 주행으로 조정한다.
> API 시그니처는 SDK jar(Vehicle 2.0.3, NaviHelper 2.0.3)와 Pleos 문서(TextToSpeech·SpeechToText 3.3.1)에서 확인한 것이다.

---

## 0. 개요

### 0.1 제품 한 문장
평소의 나를 학습한 AI가 운전이 거칠어지거나 졸릴 때 실내 공기를 바꿔 주고, 어려운 길은 출발 전에 미리 알려 주는 무상호작용 코파일럿.

### 0.2 기능 목록

| ID | 기능 | 동작 시점 | 운전자 입력 | 우선순위 |
|---|---|---|---|---|
| D0 | 차량 데이터 계층 | 항상 | 없음 | 필수 |
| C0 | 운전 패턴 엔진 (개인 기준선 학습) | 항상 (백그라운드) | 없음 | 필수 |
| F2 | 쿨다운 캐빈 | 주행 중 | 없음 | 필수 |
| F3 | 피로 케어 | 주행 중 | "응/아니" 음성 한마디 (선택) | 필수 |
| F1 | 코스 브리핑 | 출발 전(P단) + 어려운 구간 직전 | 없음 (자동 재생) | 필수 |
| F4 | 주행 리포트 | 도착 후(P단) | 정차 중 터치 | 필수 |
| S1 | 온보딩·설정 | 첫 실행, 정차 중 | 정차 중 터치 | 필수 |
| X1 | LLM 문장 생성 | 정차 중 | 없음 | 선택 (11/12 보완일) |

### 0.3 용어

| 용어 | 정의 |
|---|---|
| 주행 중 | 기어가 P가 아니거나 차속 > 0 km/h |
| 정차 중 | 기어가 P이고 차속 = 0 km/h |
| 주행 1회 (Trip) | 기어가 P → 다른 기어로 바뀐 시점부터, 다시 P로 돌아온 뒤 60초가 지날 때까지. 60초 안에 다시 출발하면 같은 주행으로 이어 본다 |
| 속도대 | `URBAN` 0~60 km/h, `RURAL` 60~90 km/h, `HIGHWAY` 90 km/h 이상 |
| 기준선 | 속도대별 특징 값의 평균과 표준편차. 운전자 한 명분 |
| 개입 | F2·F3가 공조를 바꾸거나 음성으로 말을 건 것 |

### 0.4 운전 안전 원칙 (모든 기능 공통)

| ID | 원칙 |
|---|---|
| SAFE-01 | 주행 중 화면에는 입력 UI(버튼, 스크롤, 입력란)를 표시하지 않는다 |
| SAFE-02 | 주행 중 음성은 한 번에 한 문장, 20자 안팎으로 한다. 코스 브리핑(최대 30초)은 정차 중에만 재생한다 |
| SAFE-03 | 운전자가 직접 조작한 차량 설정은 언제나 앱보다 우선한다 |
| SAFE-04 | 지원하지 않는 차량 기능은 `checkXXXCapabilities`로 미리 확인해서, 해당 기능을 조용히 끈다 |
| SAFE-05 | 주행 데이터는 차량 밖으로 보내지 않는다. 원시 센서 로그는 저장하지 않고, 주행별 요약과 기준선만 저장한다 |
| SAFE-06 | 앱 카테고리는 게임으로 두지 않는다. 심사 가이드상 게임은 미디어 앱으로 분류되어 주행 중에 실행할 수 없다 |

---

## 1. 아키텍처

### 1.1 패키지 구조 (`com.pleos.cooldown`)

```
data/
  vehicle/   VehicleDataSource (인터페이스), PleosVehicleDataSource, FakeVehicleDataSource
  hvac/      HvacController (인터페이스), PleosHvacController
  navi/      NaviDataSource, PleosNaviDataSource
  voice/     VoiceOutput, VoiceInput (TTS·STT 래퍼)
  local/     Room DB: TripEntity, BaselineEntity, InterventionEntity
domain/
  model/     VehicleSignal, DrivingFeatures, Baseline, DriverState, Trip, Intervention ...
  engine/    FeatureExtractor, BaselineLearner, StateScorer   ← C0 (순수 Kotlin, 단위 테스트 대상)
  cooldown/  CooldownPolicy                                   ← F2 상태 머신 (순수 Kotlin)
  fatigue/   FatiguePolicy                                    ← F3 상태 머신 (순수 Kotlin)
  briefing/  HardSectionDetector, BriefingComposer            ← F1 (순수 Kotlin)
  report/    TripSummarizer                                   ← F4
service/
  CopilotService  ← 포그라운드 서비스. 주행 중 앱 화면이 없어도 C0·F1~F3를 실행
di/
  AppContainer    ← 수동 DI. 실제/가짜 데이터 소스 교체 지점
presentation/
  onboarding/ report/ history/ settings/ driving/ (주행 중 최소 화면)
util/
  Clock, CopilotConfig
```

### 1.2 데이터 흐름

```
 D0 VehicleDataSource ──(StateFlow, 10Hz 샘플링)──▶ C0 FeatureExtractor ──1초 창──▶ StateScorer ──▶ DriverState
                                                                              ▲                  │
                                                         BaselineLearner ─────┘                  ├──▶ F2 CooldownPolicy ──▶ HvacController
                                                         (Room에서 로드, 주행 종료 시 갱신)          └──▶ F3 FatiguePolicy  ──▶ HvacController / VoiceOutput / Navi
 NaviDataSource ──▶ F1 HardSectionDetector ──▶ BriefingComposer ──▶ VoiceOutput
 Trip 기록 (Room) ──▶ F4 TripSummarizer ──▶ 리포트 화면
```

### 1.3 설계 규칙
- 비동기는 Coroutines와 `StateFlow`만 쓴다 (프로젝트 규칙).
- `domain/` 아래는 Android 의존성이 없는 순수 Kotlin으로 작성해서 JVM 단위 테스트로 검증한다.
- 시간은 `Clock` 인터페이스로 주입한다. 테스트에서는 가짜 시계로 몇 분 단위의 상황을 즉시 재현한다.
- 실제 차량 SDK와 가짜 데이터 소스(`FakeVehicleDataSource`)를 `AppContainer`에서 바꿔 끼운다. 가짜 소스는 녹화된 시나리오를 재생해서 시연과 테스트에 쓴다.
- 어노테이션 처리는 KSP만 쓴다 (Room).

---

## 2. D0 — 차량 데이터 계층

### 2.1 수집 신호

| ID | 신호 | API | 권한 | 단위·값 |
|---|---|---|---|---|
| D0-01 | 차속 | AAOS `CarPropertyManager.registerCallback(PERF_VEHICLE_SPEED, rate)` | `android.car.permission.CAR_SPEED` | m/s로 받아서 km/h로 변환. 음수(후진)는 절댓값 |
| D0-02 | 기어 | `Vehicle.getGear().registerGearSelection(GearSelectionListener)` → `onSelected(VehicleGear?)` | `pleos.car.permission.CAR_DRIVE` | P/R/N/D/UNKNOWN |
| D0-03 | 브레이크 페달 | `Vehicle.getBrake().registerBrakePedalStatus(BrakePedalStatusListener)` → `onBrakePedalStatusUpdated(Boolean?)` | `pleos.car.permission.CAR_DRIVE` | true = 밟음 |
| D0-04 | 조향각 | `Vehicle.getSteeringWheel().registerSteeringWheelAngle(SteeringWheelAngleListener)` → `onSteeringWheelAngleUpdated(Float?)` | `pleos.car.permission.CAR_DRIVE` | 도(°) |
| D0-05 | 누적 주행 거리 | `Vehicle.getOdometer().registerDistanceDriven(DistanceDrivenListener)` → `onDistanceDrivenUpdated(Float?)` | `pleos.car.permission.CAR_DRIVE` | km (단위 확인 필요) |

### 2.2 출력 모델

```kotlin
data class VehicleSignal(
    val timestampMs: Long,
    val speedKmh: Float,
    val gear: Gear,
    val brakePressed: Boolean,
    val steeringAngleDeg: Float,
    val odometerKm: Float?,
)
enum class Gear { P, R, N, D, UNKNOWN }

interface VehicleDataSource {
    val signal: StateFlow<VehicleSignal>
    val drivingState: StateFlow<DrivingState>   // PARKED / DRIVING
    fun start()
    fun stop()
}
```

### 2.3 요구사항

| ID | 요구사항 | 완료 기준 |
|---|---|---|
| D0-10 | 각 신호를 개별 리스너로 받아서 하나의 `VehicleSignal` `StateFlow`로 합친다 | 에뮬레이터 Virtual Console에서 속도·기어를 바꾸면 로그에 반영됨 |
| D0-11 | 콜백 값이 null이면 직전 값을 유지한다 | 단위 테스트 |
| D0-12 | 리스너의 `onFailed`는 로그를 남기고 해당 신호만 "사용 불가"로 표시한다. 앱은 죽지 않는다 | 단위 테스트 |
| D0-13 | `drivingState`는 0.3의 정의로 계산한다 | 단위 테스트 |
| D0-14 | `stop()`에서 모든 리스너를 해제하고 `Vehicle.release()`를 호출한다 | 코드 리뷰 |
| D0-15 | 매니페스트에는 실제로 쓰는 권한만 선언한다. 쓰지 않는 `CAR_INFO`, `CAR_ENERGY`, 존재하지 않는 `pleos.car.permission.CAR_SPEED`는 지운다 | 매니페스트 확인 |
| D0-16 | `FakeVehicleDataSource`는 CSV 시나리오(시각, 속도, 기어, 브레이크, 조향)를 재생한다 | 시나리오 A·B 파일 재생 |

---

## 3. C0 — 운전 패턴 엔진 (개인 기준선 학습)

### 3.1 특징 추출 (`FeatureExtractor`)

입력: 10Hz로 샘플링한 `VehicleSignal`. 출력: 1초마다 `DrivingFeatures` (최근 3분 슬라이딩 창).

| ID | 특징 | 계산 | 의미 |
|---|---|---|---|
| C0-01 | 가속도 a | 차속 변화량(m/s) ÷ 시간. 0.5초 이동평균으로 노이즈 제거 | — |
| C0-02 | 급가속 횟수/분 | a ≥ `T_acc`(기준선 상위 5%)인 구간의 시작 횟수 | 거친 출발·추월 |
| C0-03 | 급감속 횟수/분 | 브레이크 ON이고 −a ≥ `T_dec`(기준선 상위 5%)인 구간의 시작 횟수 | 급제동 |
| C0-04 | 가감속 반전 횟수/분 | 가속(a > +0.5 m/s²)과 제동(브레이크 ON)이 10초 안에 바뀐 횟수 | 정체 속 신경질적 운전 |
| C0-05 | 조향 변화율 표준편차 | 조향각 변화 속도(°/s)의 표준편차 | 거친 핸들 조작 |
| C0-06 | 조향 미세 보정률 | 1~5° 크기의 작은 조향 보정 횟수/분 | 피로 지표 (줄어들고 커지면 졸음 신호) |
| C0-07 | 조향 정지 시간 비율 | 조향 변화가 0.5°/s 미만으로 유지된 시간의 비율 (직진 구간에서) | 피로 지표 (무뎌짐) |
| C0-08 | 연속 주행 시간 | 마지막으로 5분 넘게 정차한 뒤부터 흐른 시간 | 피로 지표 |

- 특징은 **현재 속도대**별로 따로 집계한다. 속도대가 바뀌면 새 창을 쓴다.
- 차속이 5 km/h 미만인 구간은 C0-05~07 계산에서 뺀다 (주차장 조작 제외).

### 3.2 기준선 학습 (`BaselineLearner`)

```kotlin
data class FeatureStat(val mean: Double, val std: Double, val samples: Int)
data class Baseline(
    val bySpeedBand: Map<SpeedBand, Map<FeatureId, FeatureStat>>,
    val totalTrips: Int,
    val totalDrivingMinutes: Double,
)
```

| ID | 요구사항 |
|---|---|
| C0-10 | **학습 모드:** 주행 5회 미만 또는 누적 60분 미만이면 학습 모드다. 학습 모드에서는 내장 기본값(`DEFAULT_BASELINE`)을 기준선으로 쓰고, F2·F3의 개입 임계값을 75로 올린다 |
| C0-11 | **갱신:** 주행이 끝나면 그 주행의 속도대별 특징 평균을 지수이동평균으로 반영한다. `mean ← (1−α)·mean + α·x`, α = 0.2. 분산도 같은 방식으로 갱신한다 |
| C0-12 | **개입 구간 제외:** F2·F3가 개입한 구간과 그 앞 3분은 갱신에 쓰지 않는다. 화난 운전이나 졸린 운전이 "평소"로 학습되는 것을 막는다 |
| C0-13 | **최소 표준편차:** 표준편차가 너무 작으면 사소한 변화에도 점수가 튀므로, 특징별 하한(`minStd`)을 둔다 |
| C0-14 | **저장:** 기준선은 Room `BaselineEntity`에 주행이 끝날 때마다 저장하고, 앱이 시작할 때 불러온다 |
| C0-15 | **초기화:** 설정에서 "운전 습관 다시 배우기"를 누르면 기준선과 학습 횟수를 지운다 |

### 3.3 상태 점수 (`StateScorer`)

각 특징의 편차를 z = (x − mean) / max(std, minStd)로 구하고, 0 미만은 0으로 자른 뒤 가중합해서 0~100으로 바꾼다.

| 점수 | 사용하는 특징과 가중치 (초기값) | 변환 |
|---|---|---|
| 공격성 점수 | 급가속 0.30, 급감속 0.30, 가감속 반전 0.25, 조향 변화율 0.15 | `score = 100 × (1 − e^(−Σ w·z / 2))` |
| 피로 점수 | 미세 보정률 변화 0.35, 조향 정지 비율 0.25, 연속 주행 시간 0.30, 야간(22~06시) 0.10 | 같은 변환. 연속 주행 시간은 z 대신 `min(시간/120분, 1.5)`를 씀 |

| ID | 요구사항 | 완료 기준 |
|---|---|---|
| C0-20 | 점수는 1초마다 계산해서 `StateFlow<DriverState>`로 내보낸다 | 단위 테스트 |
| C0-21 | 평소와 같은 운전 시퀀스는 공격성 점수 < 40을 유지한다 | 녹화 시나리오 테스트 |
| C0-22 | 급가속·급제동을 반복하는 시퀀스는 60초 안에 공격성 점수 ≥ 60이 된다 | 녹화 시나리오 테스트 |
| C0-23 | 같은 운전 시퀀스라도 기준선이 "평소 거친 운전자"면 점수가 더 낮게 나온다 (개인화 검증) | 단위 테스트 |

```kotlin
data class DriverState(
    val timestampMs: Long,
    val aggression: Int,        // 0~100
    val fatigue: Int,           // 0~100
    val speedBand: SpeedBand,
    val learning: Boolean,      // 학습 모드 여부
)
```

---

## 4. F2 — 쿨다운 캐빈

### 4.1 한 문장 요약
운전이 *평소의 나보다* 거칠어지면, 차가 말없이 설정 온도를 1~2℃ 낮추고 바람을 한 단계 올린다. 운전이 진정되면 원래 설정으로 되돌린다.

**근거:** 기온이 높을수록 경적 사용이 직선적으로 늘었고, 창문을 열고 있던 운전자, 즉 에어컨을 쓰지 않던 운전자에게서 효과가 더 강했다 (Kenrick & MacFarlane, 1986, *Environment and Behavior* 18(2)).

### 4.2 상태 머신 (`CooldownPolicy`)

```
          점수≥T 30초 유지              2분 경과 & 점수≥T
 IDLE ─────────────────▶ STAGE1 ─────────────────▶ STAGE2
  ▲                         │  점수<40 5분 유지         │ 점수<40 5분 유지
  │       15분 경과          ▼                         ▼
 COOLDOWN_LOCK ◀────────── RESTORING ◀────────────────┘
                     (1분에 걸쳐 원래 값으로)

 어느 상태에서든 운전자가 공조를 직접 조작 ──▶ YIELDED (이번 주행 동안 개입 안 함)
 어느 상태에서든 주행 종료 ──▶ 개입 중이었다면 즉시 복원 후 IDLE
```

| 상태 | 진입 조건 | 동작 |
|---|---|---|
| `IDLE` | 시작, 또는 잠금 해제 | 없음 |
| `STAGE1` | 공격성 점수 ≥ T(개인화 60 / 학습 모드 75)가 30초 이상 유지 | ① 현재 설정 온도·풍량·공조 전원 상태를 저장 ② 설정 온도 −1℃ ③ 풍량 +1단 |
| `STAGE2` | STAGE1에서 2분이 지나도 점수 ≥ T | 설정 온도 −1℃ 추가 (원래 값 대비 −2℃) |
| `RESTORING` | 점수 < 40이 5분 이상 유지 | 1분 동안 온도를 0.5℃씩, 풍량을 1단씩 원래 값으로 되돌림 |
| `COOLDOWN_LOCK` | 복원 완료 | 15분 동안 재개입하지 않음 |
| `YIELDED` | 개입 중에 운전자가 온도나 풍량을 바꿈 | **복원하지 않고**(운전자가 정한 값을 존중) 이번 주행 동안 개입하지 않음 |

### 4.3 안전장치

| ID | 규칙 |
|---|---|
| F2-01 | 온도 하한: max(원래 값 − 2℃, 20℃, 차량이 지원하는 최솟값). 지원 범위는 `checkHvacTemperatureCapabilities`의 `AreaBounds`로 확인 |
| F2-02 | 풍량 상한: min(원래 값 + 1, 차량이 지원하는 최댓값) |
| F2-03 | 공조 전원이 꺼져 있으면(`getHvacPowerState` = false) 개입하지 않는다 |
| F2-04 | **운전자 조작 감지:** `registerHvacTemperature`·`registerHvacFanSpeed`로 값 변경을 구독한다. 앱이 마지막으로 설정한 값과 다른 값이 들어오면 운전자 조작으로 본다. 앱이 값을 바꾼 직후 2초 동안 들어오는 콜백은 앱 자신의 변경으로 본다 |
| F2-05 | 적용 좌석은 운전석(`SeatArea(SeatValue.ROW_1_LEFT)`)으로 한다. 좌핸들 기준이며 우핸들 차량은 확인 필요 |
| F2-06 | API 호출이 실패하면 상태를 `YIELDED`로 바꾸고 다시 시도하지 않는다 |
| F2-07 | 설정에서 F2를 끄거나 강도를 고를 수 있다 (약하게: −1℃까지 / 보통: −2℃까지) |
| F2-08 | 첫 실행 때 정차 중에 동의를 받는다. 동의하지 않으면 F2는 꺼진 상태로 시작한다 |

### 4.4 투명성
- 개입하는 순간에는 말하지 않는다.
- 모든 개입을 `InterventionEntity`(시각, 종류, 단계, 지속 시간, 결과)로 기록해서 F4 리포트에 보여준다. 예: "18:52에 4분간 시원하게 해 드렸어요."

### 4.5 사용 API

| 동작 | API | 권한 |
|---|---|---|
| 지원 여부·범위 | `HVAC.checkHvacTemperatureCapabilities`, `checkHvacFanSpeedCapabilities` | `CAR_CLIMATE` |
| 현재 값 | `HVAC.getHvacTemperature(SeatArea, TemperatureUnit, …)`, `getHvacFanSpeed(SeatArea, …)`, `getHvacPowerState(SeatArea, …)` | `CAR_CLIMATE` |
| 변경 | `HVAC.setHvacTemperature(SeatArea, Float, TemperatureUnit, …)`, `setHvacFanSpeed(SeatArea, Int, …)` | `CONTROL_CAR_CLIMATE` **(Pleos App Market 사전 승인 필요)** |
| 운전자 조작 감지 | `HVAC.registerHvacTemperature(SeatArea, HvacTemperatureListener)`, `registerHvacFanSpeed` | `CAR_CLIMATE` |

### 4.6 수용 기준

| ID | 시나리오 | 기대 결과 |
|---|---|---|
| F2-T1 | 24℃·풍량 3에서 공격성 점수 70이 30초 유지 | 23℃·풍량 4 |
| F2-T2 | 이어서 2분 동안 점수 70 유지 | 22℃·풍량 4 |
| F2-T3 | 이어서 점수 30이 5분 유지 | 1분에 걸쳐 24℃·풍량 3으로 복원 |
| F2-T4 | 복원 후 10분 안에 다시 점수 70 | 개입 없음 (잠금) |
| F2-T5 | STAGE1에서 운전자가 25℃로 바꿈 | 25℃ 유지, 이번 주행 동안 개입 없음 |
| F2-T6 | 공조 전원이 꺼진 상태에서 점수 90 | 개입 없음 |
| F2-T7 | 원래 값 21℃에서 STAGE2 | 20℃에서 멈춤 (하한) |

---

## 5. F3 — 피로 케어

### 5.1 한 문장 요약
장거리 주행에서 조향이 평소보다 무뎌지고 들쭉날쭉해지면 졸음 신호로 본다. 먼저 외기 유입과 시원한 바람으로 깨우고, 그래도 계속되면 다음 휴게소를 한마디로 제안한다.

### 5.2 상태 머신 (`FatiguePolicy`)

```
          피로≥60 60초 유지 & 연속 주행≥40분        10분 경과 & 피로≥60
 IDLE ──────────────────────────────▶ REFRESH ─────────────────────▶ SUGGEST
  ▲                                     │ 피로<40 5분 유지             │
  │                                     ▼                            ├─ "응" ──▶ WAYPOINT_ADDED ──▶ 정차하면 IDLE
  └────────── 30분 잠금 ◀─────────── RESTORING                          ├─ "아니"/무응답 8초 ──▶ 30분 잠금
                                                                     └─ 피로<40 ──▶ RESTORING
```

| 상태 | 동작 |
|---|---|
| `REFRESH` | 현재 설정 저장 → 외기 유입(`setHvacRecirculation(false)`) → 설정 온도 −1℃. F2와 같은 하한·운전자 우선 규칙을 적용 |
| `SUGGEST` | TTS: "두 시간째 운전 중이에요. 12km 앞에 휴게소가 있어요. 들를까요?" → STT로 8초 동안 응답을 듣는다 |
| `WAYPOINT_ADDED` | `NaviHelper.addWaypoint(RequestWaypointInfo)`로 휴게소를 경유지에 추가한다. TTS: "휴게소를 경유지로 넣었어요." |

### 5.3 요구사항

| ID | 요구사항 |
|---|---|
| F3-01 | 연속 주행 시간이 40분 미만이면 피로 점수와 상관없이 개입하지 않는다 (짧은 주행에서 오탐 방지) |
| F3-02 | F2와 F3가 동시에 조건을 만족하면 F2를 우선한다. 공조 제어는 한 번에 한 정책만 소유한다 (`HvacController`가 소유권 관리) |
| F3-03 | 응답 인식: "응, 네, 그래, 좋아, 가자" → 수락. "아니, 괜찮아, 됐어" → 거절. 그 외나 무응답 → 거절로 처리 |
| F3-04 | 휴게소 정보: NaviHelper에 POI 검색 API가 있는지 확인이 필요하다. 없으면 문구를 "두 시간째 운전 중이에요. 다음 휴게소에서 쉬어 가세요."로 바꾸고 응답을 받지 않는다 |
| F3-05 | 내비 안내 음성과 겹치지 않도록, 다음 회전 안내(TBT)까지 500m 미만이면 제안을 미룬다 |
| F3-06 | 제안은 한 주행에서 최대 3회까지만 한다 |

### 5.4 사용 API

| 동작 | API | 권한 |
|---|---|---|
| 외기 전환 | `HVAC.setHvacRecirculation(SeatArea, Boolean, …)` | `CONTROL_CAR_CLIMATE` |
| 음성 합성 | `ai.pleos.playground:TextToSpeech:3.3.1` — `TextToSpeech(context, Mode.ON_DEVICE)`, `initialize`, `speak`, `stop`, `release` | `pleos.car.permission.TTS_SERVICE` |
| 음성 인식 | `ai.pleos.playground:SpeechToText:3.3.1` — `SpeechToText(context, Mode.ON_DEVICE)`, `request`, `stop`, `release` | `pleos.car.permission.STT_SERVICE` |
| 경유지 추가 | `NaviHelper.addWaypoint(RequestWaypointInfo)` | `NAVI_ROUTE` 계열 (정확한 권한 확인 필요) |

**음성 모드:** `ON_DEVICE`를 쓴다. 네트워크가 필요 없고, 앱 등록(My Project 자격 증명)도 필요 없다.

### 5.5 수용 기준

| ID | 시나리오 | 기대 결과 |
|---|---|---|
| F3-T1 | 연속 주행 50분, 피로 점수 65가 60초 유지 | 외기 유입, 온도 −1℃ |
| F3-T2 | 이어서 10분 동안 피로 점수 65 유지 | 휴게소 제안 음성 |
| F3-T3 | "응" | 경유지 추가 |
| F3-T4 | 무응답 8초 | 30분 동안 제안 없음 |
| F3-T5 | 연속 주행 20분, 피로 점수 80 | 개입 없음 |
| F3-T6 | F2 STAGE1 진행 중에 피로 조건 충족 | F3 개입 없음 (F2 우선) |

---

## 6. F1 — 코스 브리핑

### 6.1 흐름

| 단계 | 조건 | 동작 |
|---|---|---|
| 1 | `onRouteStarted` 또는 `onDestinationInfo` 수신 | 경로 정보 수집 시작 |
| 2 | `onTBTInfo(List<TBTInfo>)` 수신 | 회전 목록(유형 `TurnType`, 거리, 설명)과 정체 구간(`TrafficCongestionInfo`)으로 어려운 구간 추출 |
| 3 | 정차 중 (P단) | 브리핑 재생. 30초 이내, 어려운 구간 최대 3개 |
| 4 | 주행 중, 어려운 구간 1km 전 | 한 문장 안내 |

### 6.2 어려운 구간 판정 (`HardSectionDetector`)

| 유형 | 규칙 (초기값) |
|---|---|
| 연속 분기 | 회전·분기 2개 이상이 500m 안에 연달아 있음 |
| 복잡 교차로 | 회전 유형이 유턴·P턴·로터리 (`TurnType` 값 매핑은 구현 때 확인) |
| 정체 진입 | `TrafficCongestionType`이 정체인 구간의 시작 지점 |

### 6.3 문장 템플릿 (`BriefingComposer`)

| 상황 | 템플릿 |
|---|---|
| 브리핑 시작 | "오늘 길은 {총 시간}이에요." |
| 연속 분기 | "{거리} 지점에서 분기가 {n}번 연속 나와요. {마지막 분기 방향}으로 가세요." |
| 복잡 교차로 | "{거리} 지점에 {교차로 설명}이 있어요." |
| 정체 | "{도로명}에서 {길이} 정체가 있어요." |
| 어려운 구간 없음 | "오늘 길은 {총 시간}, 어렵지 않은 길이에요." |
| 직전 안내 | "곧 그 {구간 유형}이에요, {방향}." |

### 6.4 요구사항

| ID | 요구사항 |
|---|---|
| F1-01 | 브리핑은 정차 중에만 재생한다. 재생 중에 출발하면 즉시 멈추고, 남은 내용은 직전 안내로만 전달한다 |
| F1-02 | 재탐색(`onReRouteEnded`) 후에는 어려운 구간을 다시 계산하되, 브리핑은 다시 재생하지 않는다 |
| F1-03 | 내비 안내 음성과 겹치면 내비를 우선한다 |
| F1-04 | `onTBTInfo`가 경로 전체가 아니라 다음 몇 개 회전만 준다면, 브리핑은 "다음 구간" 범위로 줄이고 직전 안내를 중심으로 동작한다 (구현 첫날 확인) |
| F1-05 | 설정에서 F1을 끌 수 있다 |

---

## 7. F4 — 주행 리포트

### 7.1 표시 조건
주행이 끝나고(0.3 정의) 정차 중일 때 자동으로 보여준다. 주행 중에는 열 수 없다.

### 7.2 화면 구성

| 영역 | 내용 | 예시 |
|---|---|---|
| 헤드라인 | 기준선 대비 이번 주행의 안정도 | "평소보다 차분한 주행이었어요" |
| 개입 기록 | F2·F3 개입 목록 | "18:52에 4분간 시원하게 해 드렸어요" / "휴게소 제안 1회" |
| 어려운 구간 | F1이 브리핑한 구간 통과 결과 (그 구간의 공격성 점수) | "브리핑한 연속 분기 2곳을 안정적으로 통과했어요" |
| 추세 | 최근 7회 주행의 평균 공격성·피로 점수 | 막대 그래프 |
| 학습 상태 | 학습 모드라면 진행률 | "운전 습관을 배우는 중이에요 (3/5회)" |

### 7.3 저장 모델 (Room)

```kotlin
@Entity data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long, val endMs: Long,
    val distanceKm: Float?,
    val avgAggression: Int, val maxAggression: Int,
    val avgFatigue: Int, val maxFatigue: Int,
    val learning: Boolean,
)
@Entity data class InterventionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long, val startMs: Long, val endMs: Long?,
    val kind: String,      // COOLDOWN / FATIGUE_REFRESH / FATIGUE_SUGGEST
    val maxStage: Int, val outcome: String,   // RESTORED / YIELDED / ACCEPTED / DECLINED ...
)
@Entity data class BaselineEntity(
    @PrimaryKey val id: Int = 0, val json: String, val totalTrips: Int, val totalMinutes: Double,
)
```

---

## 8. S1 — 온보딩·설정

| 화면 | 내용 |
|---|---|
| 온보딩 1 | "평소의 운전을 배워요" — 처음 5번은 학습 기간이라는 안내 |
| 온보딩 2 | "운전이 거칠어지면 실내를 시원하게 해 드려요" — F2 동의 (동의 / 나중에) |
| 온보딩 3 | "졸리면 바람을 바꾸고 휴게소를 알려 드려요" — F3 동의 |
| 설정 | F1·F2·F3 켜기/끄기, F2 강도, 기준선 초기화, 기록 전체 삭제 |

- 설정과 온보딩은 정차 중에만 열 수 있다. 주행이 시작되면 주행 중 화면으로 바뀐다.
- 주행 중 화면: 앱 로고, 현재 상태 한 줄("평소대로 운전 중" / "시원하게 해 드리는 중"), 입력 UI 없음.

---

## 9. 권한 정리 (AndroidManifest)

| 권한 | 용도 | 상태 |
|---|---|---|
| `android.car.permission.CAR_SPEED` | 차속 (AAOS) | 추가 |
| `pleos.car.permission.CAR_DRIVE` | 기어·브레이크·조향·주행 거리 | 추가 |
| `pleos.car.permission.CAR_CLIMATE` | 공조 상태 조회 | F2 작업 때 추가 |
| `pleos.car.permission.CONTROL_CAR_CLIMATE` | 공조 제어 | F2 작업 때 추가 **(사전 승인 필요)** |
| `pleos.car.permission.TTS_SERVICE` | 음성 합성 | F3 작업 때 추가 |
| `pleos.car.permission.STT_SERVICE` | 음성 인식 | F3 작업 때 추가 |
| `pleos.car.permission.NAVI_ROUTE` 계열 | 경로·TBT·경유지 | 필요한 것만 남김 (F1 작업 때 정리) |
| `CAR_INFO`, `CAR_ENERGY`, `pleos.car.permission.CAR_SPEED` | — | **삭제** (쓰지 않거나 존재하지 않음) |

---

## 10. 테스트·시연 계획

| 종류 | 대상 | 방법 |
|---|---|---|
| 단위 테스트 | C0 특징·기준선·점수, F2·F3 상태 머신, F1 구간 판정·문장 | JVM, 가짜 `Clock`, 녹화 시퀀스 |
| 통합 확인 | D0 리스너, HVAC 제어, TTS·STT | Pleos Connect 에뮬레이터 + Virtual Console |
| 시연 | 시나리오 A(퇴근길 정체), B(주말 장거리) | `FakeVehicleDataSource` CSV 재생 + `Assistant.requestSimulatedDrive` |

## 11. 기존 코드 처리

| 기존 | 처리 | 시점 |
|---|---|---|
| `PleosVehicleDataSource` (속도 리스너 미등록, 기어만) | D0로 재작성 | 11/3 |
| `appCategory="game"` | 제거 (기본값 사용). Pleos 마켓 카테고리는 콘솔 등록 정보에서 정함 `[확인 필요]` | 11/3 |
| `TrafficViolation`, `DrivingScore`, 대시보드 점수 판정 | 삭제, C0로 교체 | 11/4 |
| 대시보드·결과·기록 화면 | 주행 중 최소 화면, F4 리포트, 기록 화면으로 재사용 | 11/9 |
| `shared` 모듈의 `MyMusicService` (템플릿 잔재, 미디어 서비스로 등록됨) | 삭제 | 11/9 |

## 12. 미확인 항목

| 항목 | 영향 | 확인 방법 | 대안 |
|---|---|---|---|
| `CONTROL_CAR_CLIMATE` 승인 | F2·F3 | Pleos App Market 문의 | 도착 후 리포트에서 "이럴 때 시원하게 해 드릴 수 있어요"로 축소 |
| `onTBTInfo` 범위 | F1 | 에뮬레이터 모의 주행 로그 | F1-04 |
| POI(휴게소) 검색 | F3 | NaviHelper jar·문서 | F3-04 |
| 주행 거리 단위 | F4 | 에뮬레이터 로그 | — |
| 공모전 시연에서 LLM 사용 가능 여부 | X1 | — | 템플릿 문장 |
