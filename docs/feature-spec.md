# 기능명세서 — 쿨다운 코파일럿 (가칭)

> 버전 1.4 · 2026-11-04 (1.1: F2 운전자 반응·Gleo 연동 추가 / 1.2: F2 계절·운전자별 강도 학습, 화면 명세 분리 → `docs/screen-spec.md` / 1.3: C0 구현 반영 — 피로 점수 변환, 속도대 히스테리시스, 임계값 개인화 보류 / 1.4: F3를 예방·감지·휴식 유도 3단계로 재설계)
> 요구사항 ID(`C0-01` 등)는 브랜치·커밋·테스트 이름에서 참조한다.
> 수치(임계값·시간)는 모두 초기값이며 `CopilotConfig` 한 곳에서 관리하고, 에뮬레이터 모의 주행으로 조정한다.
> API 시그니처는 SDK jar(Vehicle 2.0.3, NaviHelper 2.0.3)와 Pleos 문서(TextToSpeech·SpeechToText 3.3.1, Gleo Interface 구현 가이드)에서 확인한 것이다.

---

## 0. 개요

### 0.1 제품 한 문장
평소의 나를 학습한 AI가 운전이 거칠어지면 실내를 식혀 주고, 졸음은 실내 공기로 미리 막고 조향으로 잡아 실제로 쉬게 만드는 무상호작용 코파일럿. 어려운 길은 출발 전에 미리 알려 준다.

### 0.2 기능 목록

| ID | 기능 | 동작 시점 | 운전자 입력 | 우선순위 |
|---|---|---|---|---|
| D0 | 차량 데이터 계층 | 항상 | 없음 | 필수 |
| C0 | 운전 패턴 엔진 (개인 기준선 학습) | 항상 (백그라운드) | 없음 | 필수 |
| F2 | 쿨다운 캐빈 | 주행 중 | 없음 | 필수 |
| F3 | 피로 케어 (예방·감지·휴식 유도) | 주행 중 (예방은 항상) | "응/아니" 음성 한마디 (선택) | 필수 |
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
| SAFE-03 | 운전자가 직접 조작한 차량 설정은 언제나 앱보다 우선한다. 풍향은 변경 리스너가 없어서 주행 중 2초마다 다시 읽어 조작을 알아챈다. 앞유리 김서림 제거(DEFROST)가 켜져 있거나 풍향을 읽지 못하면 F2·F3 모두 풍향을 바꾸지 않는다. 알던 풍향이 읽히지 않게 되면 운전자 조작으로 보고 양보한다 (2026-11-10 보안 점검에서 찾은 결함 수정) |
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
  local/     Room DB: TripEntity, BaselineEntity, InterventionEntity, ComfortProfileEntity
  gleo/      GleoActionReceiver (Gleo Interface 수신·응답)
domain/
  model/     VehicleSignal, DrivingFeatures, Baseline, DriverState, Trip, Intervention ...
  engine/    FeatureExtractor, BaselineLearner, StateScorer   ← C0 (순수 Kotlin, 단위 테스트 대상)
  cooldown/  CooldownPolicy, ComfortLearner                   ← F2 상태 머신·계절별 강도 학습 (순수 Kotlin)
  fatigue/   FatiguePolicy, FreshAirPolicy                    ← F3 휴식 유도 상태 머신·실내 공기 예방 (순수 Kotlin)
  briefing/  HardSectionDetector, BriefingComposer            ← F1 (순수 Kotlin)
  report/    TripSummarizer                                   ← F4
service/
  CopilotService  ← 포그라운드 서비스. 주행 중 앱 화면이 없어도 C0·F1~F3를 실행
di/
  AppContainer    ← 수동 DI. 실제/가짜 데이터 소스 교체 지점
presentation/
  onboarding/ home/ driving/ report/ history/ settings/   ← 화면 명세 docs/screen-spec.md (SC-01~06)
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
| C0-02 | 급가속 횟수/분 | a ≥ `T_acc`인 구간의 시작 횟수. `T_acc` = 2.5 m/s² (초기값, 아래 참고) | 거친 출발·추월 |
| C0-03 | 급감속 횟수/분 | 브레이크 ON이고 −a ≥ `T_dec`인 구간의 시작 횟수. `T_dec` = 3.0 m/s² (초기값) | 급제동 |
| C0-04 | 가감속 반전 횟수/분 | 가속(a > +0.5 m/s²)과 제동(브레이크 ON)이 10초 안에 바뀐 횟수 | 정체 속 신경질적 운전 |
| C0-05 | 조향 변화율 표준편차 | 조향각 변화 속도(°/s)의 표준편차 | 거친 핸들 조작 |
| C0-06 | 조향 미세 보정률 | 1~5° 크기의 작은 조향 보정 횟수/분 | 피로 지표 (줄어들고 커지면 졸음 신호) |
| C0-07 | 조향 정지 시간 비율 | 조향 변화가 0.5°/s 미만으로 유지된 시간의 비율 (직진 구간에서) | 피로 지표 (무뎌짐) |
| C0-08 | 연속 주행 시간 | 마지막으로 5분 넘게 정차한 뒤부터 흐른 시간 | 피로 지표 |

- 특징은 **속도대별로 따로** 집계한다. 속도대마다 최근 3분 창을 따로 두고, 속도대가 바뀌면 그 속도대의 창으로 바꿔 쓴다(지우지 않는다). 횟수/분의 분모는 최근 3분 중 그 속도대에서 보낸 시간이다. 처음에는 바뀔 때 창을 새로 썼지만, 에뮬레이터 확인에서 60km/h 근처를 오가는 거친 운전이 계속 지워져 공격성을 낮게 보는 문제가 있었다.
- **속도대 히스테리시스:** 속도대는 경계를 ±3 km/h 넘어야 바뀐다 (예: URBAN → RURAL은 63 km/h 이상, RURAL → URBAN은 57 km/h 미만). 경계 근처를 오갈 때 창이 계속 비는 것을 막는다.
- 차속이 5 km/h 미만인 구간은 C0-05~07 계산에서 뺀다 (주차장 조작 제외).
- 표본 간격이 1초를 넘으면(신호 끊김) 그 구간은 미분하지 않는다.
- 횟수/분 특징은 창이 1분보다 짧으면 분모를 1분으로 둔다 (창 초반에 한 번의 사건으로 값이 튀지 않게).
- **`T_acc`·`T_dec` 개인화 보류:** "개인 상위 5%" 임계값은 1초 스냅샷의 가속도로는 순간 최댓값을 잡지 못한다. 추출기에서 가속도 분포를 따로 모아야 하므로, 시간이 남으면 보완일(11/12)에 한다. 그 전까지는 초기값을 쓴다. 개인화는 z 점수(3.3)가 이미 담당한다.

### 3.2 기준선 학습 (`BaselineLearner`)

```kotlin
data class FeatureStat(val mean: Double, val variance: Double, val samples: Int)  // std = √variance
data class Baseline(
    val bySpeedBand: Map<SpeedBand, Map<FeatureId, FeatureStat>>,
    val totalTrips: Int,
    val totalDrivingMinutes: Double,
)
```

| ID | 요구사항 |
|---|---|
| C0-10 | **학습 모드:** 주행 5회 미만 또는 누적 60분 미만이면 학습 모드다. 학습 모드에서는 내장 기본값(`DEFAULT_BASELINE`)을 기준선으로 쓰고, F2·F3의 개입 임계값을 75로 올린다 |
| C0-11 | **갱신:** 주행이 끝나면 그 주행의 속도대별 특징 평균을 지수이동평균으로 반영한다. `mean ← (1−α)·mean + α·x`, α = 0.2. 분산도 같은 방식으로, 이번 주행 1초 스냅샷들의 분산을 반영한다. 한 속도대에서 1분(스냅샷 60개) 미만으로 달렸으면 그 속도대는 갱신하지 않는다 |
| C0-12 | **개입 구간 제외:** F2·F3가 개입한 구간과 그 앞 3분은 갱신에 쓰지 않는다. 화난 운전이나 졸린 운전이 "평소"로 학습되는 것을 막는다 |
| C0-13 | **최소 표준편차:** 표준편차가 너무 작으면 사소한 변화에도 점수가 튀므로, 특징별 하한(`minStd`)을 둔다 |
| C0-14 | **저장:** 기준선은 Room `BaselineEntity`에 주행이 끝날 때마다 저장하고, 앱이 시작할 때 불러온다 |
| C0-15 | **초기화:** 설정에서 "운전 습관 다시 배우기"를 누르면 기준선과 학습 횟수를 지운다 |

### 3.3 상태 점수 (`StateScorer`)

각 특징의 편차를 z = (x − mean) / max(std, minStd)로 구하고, 0 미만은 0으로 자른 뒤 가중합해서 0~100으로 바꾼다.

| 점수 | 사용하는 특징과 가중치 (초기값) | 변환 |
|---|---|---|
| 공격성 점수 | 급가속 0.30, 급감속 0.30, 가감속 반전 0.25, 조향 변화율 0.15 | `score = 100 × (1 − e^(−Σ w·z / 2))` |
| 피로 점수 | 미세 보정률 감소 0.35 (평소보다 줄어든 쪽만), 조향 정지 비율 증가 0.25, 연속 주행 시간 0.30, 야간(22~06시) 0.10 | `score = 100 × (1 − e^(−Σ w·z))` (분모 1). 연속 주행 시간은 z 대신 `min(시간/120분, 1.5)`, 야간은 0 또는 1 |

**피로 점수의 분모를 1로 두는 이유:** 피로 특징은 비율이라 z가 2 안팎에서 막힌다 (미세 보정은 0 밑으로, 정지 비율은 1 위로 갈 수 없다). 공격성과 같은 /2를 쓰면 고속도로·야간·3시간 연속에 가장 졸린 조향이어도 58점에 그쳐 F3 기준(60)에 닿지 않는다.

| 상황 | /2 (이전) | /1 (현재) |
|---|---|---|
| 평범한 조향, 2시간 | 14 | 26 |
| 졸린 조향(미세 보정 z 1.5, 정지 비율 z 1.5), 고속도로 2시간 | 45 | 70 |
| 가장 극단적인 조건 | 58 | 83 |

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
운전이 *평소의 나보다* 거칠어지면, 차가 한 문장으로 알리고 설정 온도를 1~2℃ 낮추고 바람을 한 단계 올린다. 운전이 진정되면 원래 설정으로 되돌린다. 운전자가 춥다고 하면 바로 되돌리고, 다음부터는 덜 차갑게 한다.

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
 개입 중 "추워"·"그만" (STT 응답창 또는 Gleo) ──▶ 즉시 원래 값으로 복원 후 YIELDED
 어느 상태에서든 주행 종료 ──▶ 개입 중이었다면 즉시 복원 후 IDLE
```

| 상태 | 진입 조건 | 동작 |
|---|---|---|
| `IDLE` | 시작, 또는 잠금 해제 | 없음 |
| `STAGE1` | 공격성 점수 ≥ T(개인화 60 / 학습 모드 75)가 30초 이상 유지 | ① 현재 설정 온도·풍량·풍향·통풍시트·공조 전원 상태를 저장 ② 현재 강도 단계(4.7.1)의 1단계 동작: 설정 온도 −min(1℃, D) ③ 풍량 +1단 ④ 겨울 구간이면 풍향을 얼굴 쪽으로 |
| `STAGE2` | STAGE1에서 2분이 지나도 점수 ≥ T | 설정 온도를 원래 값 대비 −D까지 추가로 낮춤. 여름 L3이면 통풍시트 켜기 (지원 차량만). D ≤ 1이면 온도는 더 바꾸지 않는다 |
| `RESTORING` | 점수 < 40이 5분 이상 유지 | 1분 동안 온도를 0.5℃씩, 풍량을 1단씩 원래 값으로 되돌림. 풍향·통풍시트는 마지막에 원래 값으로 |
| `COOLDOWN_LOCK` | 복원 완료 | 15분 동안 재개입하지 않음 |
| `YIELDED` | 개입 중에 운전자가 온도나 풍량을 바꿈 | **복원하지 않고**(운전자가 정한 값을 존중) 이번 주행 동안 개입하지 않음 |

### 4.3 안전장치

| ID | 규칙 |
|---|---|
| F2-01 | 온도 하한: max(원래 값 − D, 개인 기준 온도 − D, 18℃, 차량이 지원하는 최솟값). D는 현재 강도 단계의 최대 하강폭(4.7.1). 지원 범위는 `checkHvacTemperatureCapabilities`의 `AreaBounds`로 확인 |
| F2-02 | 풍량 상한: min(원래 값 + 1, 차량이 지원하는 최댓값) |
| F2-03 | 공조 전원이 꺼져 있으면(`getHvacPowerState` = false) 개입하지 않는다 |
| F2-04 | **운전자 조작 감지:** `registerHvacTemperature`·`registerHvacFanSpeed`로 값 변경을 구독한다. 앱이 마지막으로 설정한 값과 다른 값이 들어오면 운전자 조작으로 본다. 앱이 값을 바꾼 직후 2초 동안 들어오는 콜백은 앱 자신의 변경으로 본다 |
| F2-05 | 적용 좌석은 운전석(`SeatArea(SeatValue.ROW_1_LEFT)`)으로 한다. 좌핸들 기준이며 우핸들 차량은 확인 필요 |
| F2-06 | API 호출이 실패하면 상태를 `YIELDED`로 바꾸고 다시 시도하지 않는다 |
| F2-07 | 설정에서 F2를 끌 수 있다. 강도는 직접 고르지 않고 학습된 계절별 강도를 보여 준다 ("여름: 최대 −2℃ · 바람 · 통풍시트"). "운전 습관 다시 배우기"로 초기화한다 |
| F2-08 | 첫 실행 때 정차 중에 동의를 받는다. 동의하지 않으면 F2는 꺼진 상태로 시작한다 |
| F2-09 | 운전자 반응은 4.7의 세 경로(직접 조작·STT 응답창·Gleo)로 받고, 어느 경로든 결과는 `YIELDED`다. 개입 중이 아닐 때 들어온 반응은 상태를 바꾸지 않는다 |

### 4.4 투명성
- STAGE1에 들어갈 때 TTS 한 문장으로 알린다: "조금 시원하게 해 드릴게요" (SAFE-02). STAGE2와 복원 때는 말하지 않는다.
- 설정에서 안내 음성을 끌 수 있다. 끄면 STT 응답창(F2-11)도 열지 않는다.
- 모든 개입을 `InterventionEntity`(시각, 종류, 단계, 지속 시간, 결과)로 기록해서 F4 리포트에 보여준다. 예: "18:52에 4분간 시원하게 해 드렸어요."

### 4.5 사용 API

| 동작 | API | 권한 |
|---|---|---|
| 지원 여부·범위 | `HVAC.checkHvacTemperatureCapabilities`, `checkHvacFanSpeedCapabilities` | `CAR_CLIMATE` |
| 현재 값 | `HVAC.getHvacTemperature(SeatArea, TemperatureUnit, …)`, `getHvacFanSpeed(SeatArea, …)`, `getHvacPowerState(SeatArea, …)` | `CAR_CLIMATE` |
| 변경 | `HVAC.setHvacTemperature(SeatArea, Float, TemperatureUnit, …)`, `setHvacFanSpeed(SeatArea, Int, …)` | `CONTROL_CAR_CLIMATE` **(Pleos App Market 사전 승인 필요)** |
| 운전자 조작 감지 | `HVAC.registerHvacTemperature(SeatArea, HvacTemperatureListener)`, `registerHvacFanSpeed` | `CAR_CLIMATE` |
| 풍향 (겨울) | `HVAC.checkHvacFanDirectionCapabilities`, `getHvacFanDirection`, `setHvacFanDirection(SeatArea, FanDirectionType, …)` | 조회 `CAR_CLIMATE`, 변경 `CONTROL_CAR_CLIMATE` `[확인 필요]` |
| 통풍시트 (여름) | `Seat.getHvacSeatVentilationState`, `setHvacSeatVentilation`, `registerHvacSeatVentilation` | `[확인 필요]` |
| 외기온 (계절 판단) | AAOS `CarPropertyManager` · `VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE` (Pleos HVAC SDK에는 없음) | `android.car.permission.CAR_EXTERIOR_ENVIRONMENT` |

### 4.6 수용 기준

| ID | 시나리오 | 기대 결과 |
|---|---|---|
| F2-T1 | 여름 L3·기준 온도 24℃, 24℃·풍량 3에서 공격성 점수 70이 30초 유지 | 23℃·풍량 4 |
| F2-T2 | 이어서 2분 동안 점수 70 유지 | 22℃·풍량 4 |
| F2-T3 | 이어서 점수 30이 5분 유지 | 1분에 걸쳐 24℃·풍량 3으로 복원 |
| F2-T4 | 복원 후 10분 안에 다시 점수 70 | 개입 없음 (잠금) |
| F2-T5 | STAGE1에서 운전자가 25℃로 바꿈 | 25℃ 유지, 이번 주행 동안 개입 없음 |
| F2-T6 | 공조 전원이 꺼진 상태에서 점수 90 | 개입 없음 |
| F2-T7 | 여름 L3·기준 온도 22℃, 원래 값 21℃에서 STAGE2 | 20℃에서 멈춤 (기준 온도 − 2℃ 하한) |
| F2-T8 | STAGE1 안내 직후 "추워" | 즉시 24℃·풍량 3, 이번 주행 동안 개입 없음, 추위 반응 1회 기록 |
| F2-T9 | STAGE2에서 Gleo `ADJUST_CABIN_COMFORT(feeling=cold)` 수신 | F2-T8과 같음. 7초 안에 `success` 응답 |
| F2-T10 | 개입 중이 아닐 때 Gleo `feeling=cold` 수신 | 공조 변화 없음, `success`와 "지금은 조절하고 있지 않아요" 취지의 message로 응답 |
| F2-T11 | 여름 L3에서 "추워" | 여름 구간만 L2로 내려감. 봄·가을·겨울 강도는 그대로 |
| F2-T12 | 여름 L2에서 점수 70 유지 | −1℃·풍량 +1에서 멈춤 (STAGE2에서 온도 추가 변경 없음) |
| F2-T13 | 정의되지 않은 `feeling` 값 수신 | 공조 변화 없음, `asking`으로 응답 |
| F2-T14 | 겨울(외기 3℃) L1, 점수 70 유지 | 온도 그대로, 풍향 얼굴 쪽, 풍량 +1. 복원 때 풍향도 원래대로 |
| F2-T15 | 여름 L3·통풍시트 지원 차량, STAGE2 진입 | 통풍시트 켬. 복원 때 원래 상태로 |
| F2-T16 | 서로 다른 두 주행에서 개입 중 운전자가 풍량을 내림 | 두 번째 뒤에 그 구간 강도 한 단계 내림 |
| F2-T17 | 여름 L2에서 반응 없이 정상 복원 3회 | L3으로 올라감. 기본값(L3) 위로는 올라가지 않음 |
| F2-T18 | 외기온을 읽지 못함, 1월 | 겨울 구간으로 판단 |
| F2-T19 | 주행 시작 때 설정 온도 26℃, 기존 여름 기준 온도 24℃ | 기준 온도 24.4℃ (α = 0.2) |

### 4.7 운전자 반응 (추위·중단)

운전자가 춥다고 느끼면 손을 쓰지 않아도 바로 되돌릴 수 있어야 한다. 반응 경로를 셋 두고, 모든 반응을 다음 개입에 반영한다.

| 경로 | 운전자 행동 | 처리 | 상태 |
|---|---|---|---|
| ① 직접 조작 | 공조 온도·풍량을 직접 바꿈 | F2-04로 감지 → `YIELDED`, 운전자 값 유지 | 필수 |
| ② STT 응답창 | STAGE1 안내 직후 "추워" / "그만" | 즉시 원래 값 복원 → `YIELDED` | 필수 |
| ③ Gleo 연동 | 언제든 "글레오, 너무 추워" | Gleo Action 수신 → ②와 같게 처리 | 승인 대기 (4.8) |

| ID | 요구사항 |
|---|---|
| F2-10 | **직접 조작 해석:** 개입 중에 운전자가 온도를 올리면 강한 추위 신호(F2-20), 풍량을 내리거나 풍향을 바꾸면 약한 신호(F2-21)로 기록한다. 온도를 내리거나 풍량을 올리면 기록하지 않는다 |
| F2-11 | **STT 응답창:** STAGE1 안내 TTS가 끝나면 `SpeechToText(Mode.ON_DEVICE).request()`로 8초 동안 듣는다. "추워, 춥다, 추운데" → 추위. "그만, 됐어, 꺼 줘" → 중단. 그 외나 무응답 → 개입 계속. 응답창은 STAGE1 진입 때 한 번만 연다 |
| F2-12 | **복원 방식:** 추위·중단 반응은 1분에 걸친 점진 복원(`RESTORING`) 대신 저장해 둔 원래 값으로 즉시 되돌린다. 복원 뒤 TTS "원래대로 돌려 드렸어요" (Gleo 경로에서는 Gleo가 말한다, F2-15) |
| F2-13 | **추위 반응 학습:** 반응은 그 주행의 계절 구간 강도에만 반영한다 (4.7.1). 중단 반응("그만")은 기록만 하고 강도에 반영하지 않는다 |
| F2-14 | **저장·초기화:** 계절 구간별 강도와 기준 온도는 Room `ComfortProfileEntity`에 구간당 한 행으로 저장한다. "운전 습관 다시 배우기"(C0-15)를 누르면 함께 지운다 |

#### 4.7.1 계절·운전자별 강도 학습 (`ComfortLearner`)

같은 −2℃라도 한여름과 한겨울에 느끼는 정도가 다르고, 사람마다 평소 맞춰 두는 온도도 다르다. 그래서 **운전자 프로필 × 계절 구간**마다 강도와 기준 온도를 따로 배운다.

**계절 구간** (주행 시작 때 한 번 정하고 그 주행 동안 바꾸지 않는다)

| 구간 | 외기온 | 기본 강도 | 수단 |
|---|---|---|---|
| `SUMMER` | 25℃ 이상 | L3 | 온도를 낮춤, 풍량 +1, L3이면 통풍시트 |
| `MILD` | 10~25℃ | L2 | 온도를 낮춤, 풍량 +1 |
| `WINTER` | 10℃ 미만 | L1 | 온도는 그대로, 풍향 얼굴 쪽, 풍량 +1. 열선시트·핸들 열선은 건드리지 않는다 ("머리는 시원하게, 몸은 따뜻하게") |

**강도 단계** (D = 최대 하강폭)

| 단계 | D | 동작 |
|---|---|---|
| L3 | 2℃ | 그 구간의 수단 전부 |
| L2 | 1℃ | 온도 변화 절반, 통풍시트 없음 |
| L1 | 0℃ | 바람만 (풍량 +1, 겨울이면 풍향) |
| L0 | — | 그 구간에서는 개입하지 않고, 리포트에 "이럴 때 시원하게 해 드릴 수 있어요"로만 남긴다 |

| ID | 요구사항 |
|---|---|
| F2-19 | **계절 판단:** 주행 시작 때 `ENV_OUTSIDE_TEMPERATURE`를 읽어 구간을 정한다. 값을 못 읽으면 달로 정한다 (6~9월 `SUMMER`, 12~2월 `WINTER`, 그 외 `MILD`) |
| F2-20 | **강한 신호:** 음성 "추워"(STT·Gleo), 또는 개입 중 운전자가 온도를 올림 → 즉시 복원하고 그 구간 강도를 한 단계 내린다 (최저 L0) |
| F2-21 | **약한 신호:** 개입 중 운전자가 풍량을 내리거나 풍향을 바꿈 → 약한 신호 1점. 2점이 쌓이면 한 단계 내리고 0점으로 되돌린다 |
| F2-22 | **긍정 신호:** 반응 없이 `RESTORING`까지 정상으로 끝난 개입 → 연속 1회. 3회가 쌓이면 한 단계 올린다. 그 구간의 기본 강도보다 높이지 않는다. 강한·약한 신호가 오면 연속 횟수는 0으로 |
| F2-23 | **개인 기준 온도:** 주행 시작 때(개입 전) 설정 온도를 그 구간 기준 온도에 지수이동평균으로 반영한다 (α = 0.2, 첫 값은 그대로). 하한(F2-01)은 이 값에서 D를 뺀 값이다 |
| F2-24 | **운전자 구분:** AAOS는 운전자 프로필마다 Android 사용자를 따로 두고 앱 데이터도 따로 저장하므로, Room DB가 프로필별로 나뉜다. 별도 구현은 하지 않는다 `[확인 필요: Pleos 프로필 = Android 사용자 1:1인지]`. 한 프로필을 여럿이 쓰면 구분하지 못한다 (리포트·기획서에 한계로 적는다) |
| F2-25 | **학습 모드:** C0 학습 모드(C0-10) 동안에도 강도는 구간 기본값으로 시작하고, 반응 학습은 그대로 한다 |

### 4.8 Gleo Interface 연동

Gleo AI(호출어 "글레오")가 사용자 발화를 LLM으로 해석해서, 앱이 등록한 function을 Android Intent로 호출하는 공식 연동 방식이다 (Pleos 문서 › Gleo Interface 구현 가이드).

**등록할 function** (최대 3개, description은 영어)

| function | 파라미터 | component | 용도 |
|---|---|---|---|
| `ADJUST_CABIN_COMFORT` | `feeling`: `cold` / `stop` | `service` (화면 없이 broadcast) | F2 추위·중단 반응 (경로 ③) |
| `ANSWER_REST_SUGGESTION` | `accept`: boolean | `service` | F3 휴게소 제안 응답. STT 응답창(F3-03)의 보조 경로 |

- "더워"·"온도 올려 줘" 같은 일반 공조 명령은 등록하지 않는다. Gleo의 기본 공조 기능이 처리하는 영역이고, 앱 function과 겹치면 LLM이 엉뚱한 쪽을 부를 수 있다.

| ID | 요구사항 |
|---|---|
| F2-15 | **수신:** 매니페스트에 `exported` broadcast 수신기를 두고 function 이름을 action으로 받는다. 파라미터는 `ACTION_PARAM` extra의 JSON 문자열이다 |
| F2-16 | **응답:** 7초 안에 `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE` broadcast로 `functionName`·`status`(`success`/`asking`/`fail`)·`message`(영어 한 문장)·`data`를 보낸다. `mode`는 기본값 `voice`로 두어 Gleo가 응답을 말하게 하고, 앱은 TTS를 따로 재생하지 않는다 |
| F2-17 | **입력 검증:** 누구나 같은 broadcast를 보낼 수 있으므로, 정의된 enum 값만 받고 개입 중일 때만 공조를 바꾼다. 앱이 할 수 있는 일은 "원래 값으로 되돌리기"와 "이번 주행 개입 끄기"뿐이라 오용돼도 위험이 작다 |
| F2-18 | **stopped 상태:** 설치 후 한 번도 실행하지 않은 앱은 broadcast를 받지 못한다 (실험으로 확인). 온보딩(S1)에서 앱을 한 번 실행하므로 실사용에는 문제없지만, 시연 전에 앱을 한 번 실행한다 |

**에뮬레이터 실험 결과 (2026-11-04, Pleos_Connect_v2.0, Gleo `ai.umos.ambientai` 3.0.5.10)**

| 단계 | 결과 | 근거 로그 |
|---|---|---|
| 앱 설치 감지 | 성공 | `getInstalledUserApplications Installed packaged: com.pleos.cooldown` |
| 스키마 등록 | **실패.** 로컬 `assets/schema.json`이 아니라 서버에서 스키마를 받아 온다 | `GET connect-gw.umos.ai/…/action-call-schema` → `404 Not Found` → "기본 JSON을 사용합니다" |
| Gleo Action → 앱 수신 (`adb shell am broadcast`로 흉내) | 성공 | `received action=ADJUST_CABIN_COMFORT param={"feeling":"cold"}` |
| 앱 응답 → Gleo 처리 | 성공 | `processReceiver(): APP_ACTION_RESPONSE` → `"type":"actionCallback","value":"success"` |
| 음성 호출 | 미시험 | 스키마가 서버에 없어 Gleo가 우리 function을 모른다 |

- 결론: 앱 쪽 구현은 그대로 쓸 수 있고, 음성 호출은 Pleos의 스키마 승인·서버 등록이 끝나야 동작한다.
- 승인 전 시연: `adb shell am broadcast -a ADJUST_CABIN_COMFORT -p com.pleos.cooldown --es ACTION_PARAM '{"feeling":"cold"}'`로 Gleo와 같은 Intent를 보내고, 영상에 "승인 후 '글레오' 음성으로 동작"이라고 표시한다.
- 실험 코드: `spike/gleo-interface` 브랜치 (`data/gleo/GleoActionReceiver.kt`, `assets/schema.json`)
- 우리 앱만의 호출어("00아")는 두지 않는다. STT SDK에는 호출어 기능이 없고(`request()`로만 시작), 직접 만들려면 마이크를 상시 열어야 해서 Gleo와 마이크가 충돌하고 개인정보·심사 위험이 크다.

---

## 5. F3 — 피로 케어 (예방 · 감지 · 휴식 유도)

### 5.1 한 문장 요약
공조로 졸음을 "깨우지" 않는다. **졸음을 부르는 실내 공기를 미리 막고(예방), 운전자 자신도 모르는 졸음을 조향으로 잡아(감지), 실제로 쉬게 만든다(휴식 유도).** 공조의 찬 바람은 휴게소에 닿을 때까지 버티게 하는 보조 수단으로만 쓴다.

### 5.2 근거와 설계 원칙 (자세한 근거는 `docs/research.md` 5절)

| 근거 | 설계 원칙 |
|---|---|
| 찬 바람은 졸음에 일시적 효과뿐이다 (Reyner & Horne, 1998) | 공조를 졸음 해결 수단으로 내세우지 않는다. "휴게소까지 버티기" 용도로만 쓴다 |
| 내기순환에서만 CO2가 문제 수준까지 쌓이며, 긴 주행·여러 명 탑승일수록 위험하다. 내기순환을 주기적으로 끊으라고 권고 (Hudda & Fruin, 2018). CO2 1,000ppm부터 의사결정 능력이 떨어진다 (Satish 외, 2012) | 졸음이 오기 **전에** 내기순환 시간을 관리한다 (① 예방) |
| 스스로 졸음이 "낮다"고 할 때 75%는 실제로 중간 이상 졸렸다 (Gaspar & Carney, 2023) | 운전자의 자기 판단에 맡기지 않고 조향으로 객관적으로 잡는다 (② 감지, C0) |
| 매우 졸리다고 스스로 답해도 75% 넘게 계속 운전했다 (Gaspar & Carney, 2023). 그냥 쉬기는 효과가 없고, 낮잠+카페인은 사고 행동을 9%로 줄였다 (Horne & Reyner, 1996; Reyner & Horne, 1997) | 경고로 끝내지 않는다. "응" 한마디면 경로 위 휴게소 입구 전에 다시 알려 줘서 쉬기로 결정하는 부담을 줄이고, 휴게소에서는 효과가 확인된 행동(커피·15분 낮잠)을 권한다 (③ 휴식 유도) |

### 5.3 ① 예방 — 실내 공기 관리 (`FreshAirPolicy`)

피로 점수와 상관없이 주행 내내 돈다. 무음이고 화면에도 띄우지 않는다. 기록만 남겨서 F4 리포트에 보여 준다.

| ID | 요구사항 |
|---|---|
| F3-P1 | `registerHvacRecirculationState`로 내기순환 상태를 구독하고, 내기순환이 **연속으로 이어진 시간**을 잰다 |
| F3-P2 | 탑승 인원을 `getCurrentSeatOccupancy`(좌석별)로 센다. 지원하지 않는 차량이면 운전자 1명으로 본다 (SAFE-04) |
| F3-P3 | 내기순환이 기준 시간을 넘으면 외기로 바꾸고(`setHvacRecirculation(false)`), 5분 뒤 원래 상태(내기순환)로 되돌린다. 기준 시간: 1~2명 **30분**, 3명 이상 **15분** `[초기값]` |
| F3-P4 | 차량에 CO2 센서 API가 없으므로 농도를 재지 않고 **추정 규칙**으로 동작한다. 기준 시간은 "1~2명의 평균 26분 통근에서는 내기순환이어도 2,500ppm을 넘지 않았다"는 Hudda & Fruin(2018)의 결과보다 보수적으로 잡았다. 에뮬레이터·실차에서 조정한다 |
| F3-P5 | 운전자 우선 (SAFE-03): 앱이 외기로 바꾼 뒤 운전자가 다시 내기순환으로 바꾸면(예: 터널, 냄새) 이번 주행 동안 예방을 멈춘다 |
| F3-P6 | F2와 동시에 동작할 수 있다. F2는 온도·풍량·풍향·통풍시트만, 예방은 내·외기만 다룬다. F2가 개입 중일 때 외기로 바꿔도 F2의 복원 값에는 내·외기가 없으므로 충돌하지 않는다 |
| F3-P7 | 공조 전원이 꺼져 있거나 자동 내기순환(`AutoRecirculation`)이 켜져 있으면 차량 로직에 맡기고 개입하지 않는다 |

### 5.4 ② 감지 + ③ 휴식 유도 — 상태 머신 (`FatiguePolicy`)

```
          피로≥60 60초 유지 & 연속 주행≥40분        10분 경과 & 피로≥60
 IDLE ──────────────────────────────▶ HOLD_ON ─────────────────────▶ SUGGEST
  ▲              (피로≥80이면 바로 SUGGEST)  │ 피로<40 5분 유지            │
  │                                     ▼                            ├─ "응" ──▶ HEADING_TO_REST ──▶ 휴게소 P단 ──▶ REST_TIP
  └────────── 30분 잠금 ◀─────────── RESTORING                          ├─ "아니"/무응답 8초 ──▶ 30분 잠금
                                                                     └─ 피로<40 ──▶ RESTORING
```

| 상태 | 동작 |
|---|---|
| `HOLD_ON` | **버티기.** 현재 설정 저장 → 외기 유입 → 얼굴 쪽 바람·설정 온도 −1℃. F2와 같은 하한·운전자 우선 규칙. 말하지 않는다 |
| `SUGGEST` | TTS 한 문장: "12km 앞 ○○휴게소에서 잠깐 쉬어 갈까요?" → STT로 8초 동안 응답을 듣는다 |
| `HEADING_TO_REST` | TTS: "좋아요, 입구 전에 알려 드릴게요." 휴게소 1km 전에 "곧 휴게소 입구예요." 공조는 도착할 때까지 `HOLD_ON` 상태를 유지한다. 수락한 휴게소를 지나치면 거절로 본다 |
| `REST_TIP` | 휴게소에서 P단(정차 중)이 되면 TTS: "커피 한 잔이나 15분 낮잠이 졸음에 가장 잘 들어요." 공조는 복원한다 |

### 5.5 요구사항

| ID | 요구사항 |
|---|---|
| F3-01 | 연속 주행 시간이 40분 미만이면 피로 점수와 상관없이 `HOLD_ON`·`SUGGEST`에 들어가지 않는다 (짧은 주행에서 오탐 방지). 예방(5.3)은 예외 |
| F3-02 | F2와 `HOLD_ON`이 동시에 조건을 만족하면 F2를 우선한다. 온도·풍량은 한 번에 한 정책만 소유한다 (`HvacController`가 소유권 관리) |
| F3-03 | 응답 인식: "응, 네, 그래, 좋아, 가자" → 수락. "아니, 괜찮아, 됐어" → 거절. 그 외나 무응답 → 거절로 처리. Gleo 연동이 승인되면 `ANSWER_REST_SUGGESTION`(4.8)으로도 응답을 받는다 |
| F3-04 | **휴게소 정보:** NaviHelper 2.0.3에는 장소 검색 API가 없고, 회전 안내(TBT)에는 좌표가 없어 `addWaypoint`로 휴게소를 넣을 수 없다(SDK jar 확인). 대신 TBT의 `TURN_RESTAREA`·`TURN_ENTER_SERVICEAREA` 항목으로 **경로 위에 이미 있는 다음 휴게소**의 이름과 거리를 얻는다. 경로 안내 중이 아니거나 휴게소가 없으면 "다음 휴게소에서 잠깐 쉬어 가세요."로 권하기만 하고 응답은 받지 않는다 |
| F3-05 | 내비 안내 음성과 겹치지 않도록, 다음 회전 안내(TBT)까지 500m 미만이면 제안을 미룬다 |
| F3-06 | 제안은 한 주행에서 최대 3회까지만 한다 |
| F3-07 | **피로≥80이 60초 유지되면 `HOLD_ON`을 건너뛰고 바로 제안한다.** 찬 바람의 효과는 일시적이므로 매우 졸릴 때 10분을 기다리지 않는다 |
| F3-08 | 거절하면 30분 동안 다시 제안하지 않지만, `HOLD_ON`의 공조는 피로가 내려갈 때까지 유지한다 |
| F3-09 | `REST_TIP`은 수락한 휴게소로 가던 중(`HEADING_TO_REST`) P단이 됐을 때 한 번만 말한다. 정차 중이므로 SAFE-02의 길이 제한 안에서 두 문장까지 허용한다 |

### 5.6 사용 API

| 동작 | API | 권한 |
|---|---|---|
| 내기순환 상태 구독·조회 | `HVAC.registerHvacRecirculationState(SeatArea, HvacRecirculationStateListener)`, `getHvacRecirculationState`, `getHvacAutoRecirculationState` | `CAR_CLIMATE` |
| 외기 전환 | `HVAC.setHvacRecirculation(SeatArea, Boolean, …)` | `CONTROL_CAR_CLIMATE` |
| 탑승 인원 | `Seat.checkSeatOccupancyCapabilities`, `getCurrentSeatOccupancy(SeatArea, …)`, `registerSeatOccupancy` | `CAR_SEATS` |
| 음성 합성 | `ai.pleos.playground:TextToSpeech:3.3.1` — `TextToSpeech(context, Mode.ON_DEVICE)`, `initialize`, `speak`, `stop`, `release` | `pleos.car.permission.TTS_SERVICE` |
| 음성 인식 | `ai.pleos.playground:SpeechToText:3.3.1` — `SpeechToText(context, Mode.ON_DEVICE)`, `request`, `stop`, `release` | `pleos.car.permission.STT_SERVICE` |
| 다음 휴게소·회전 거리 | `NaviHelper.getTBTInfo()` → `NaviHelperEventListener.onTBTInfo(List<TBTInfo>)` (`TurnType`, `distance`, `description`) | `NAVI_ROUTE` 계열 (정확한 권한 확인 필요) |

**참고:** SDK의 `HvacRecirculationStateListener` 콜백 이름은 `onHvacAutoRecirculationStateUpdated`이고, 일반·자동 내기순환 구독에 같은 리스너를 쓴다 (SDK jar 확인).

### 5.7 수용 기준

| ID | 시나리오 | 기대 결과 |
|---|---|---|
| F3-T1 | 연속 주행 50분, 피로 점수 65가 60초 유지 | `HOLD_ON`: 외기 유입, 얼굴 쪽 바람, 온도 −1℃. 음성 없음 |
| F3-T2 | 이어서 10분 동안 피로 점수 65 유지 | 휴게소 제안 한 문장 |
| F3-T3 | "응" | 수락 안내, 휴게소 1km 전 입구 알림, 도착할 때까지 공조 유지 |
| F3-T4 | 무응답 8초 | 30분 동안 제안 없음, 공조는 유지 |
| F3-T5 | 연속 주행 20분, 피로 점수 80 | 개입 없음 |
| F3-T6 | F2 STAGE1 진행 중에 피로 조건 충족 | `HOLD_ON` 없음 (F2 우선). 예방(5.3)은 그대로 동작 |
| F3-T7 | 연속 주행 50분, 피로 점수 85가 60초 유지 | `HOLD_ON` 없이 바로 제안 (F3-07) |
| F3-T8 | 수락한 휴게소에서 P단 | "커피 한 잔이나 15분 낮잠…" 안내, 공조 복원 |
| F3-T9 | 운전자 1명, 내기순환 30분 연속 | 외기 5분 → 내기순환 복귀 |
| F3-T10 | 탑승 3명, 내기순환 15분 연속 | 외기 5분 → 내기순환 복귀 |
| F3-T11 | 앱이 외기로 바꾼 뒤 운전자가 내기순환으로 되돌림 | 이번 주행 동안 예방 중지 |
| F3-T12 | 자동 내기순환이 켜진 차량 | 예방 개입 없음 |

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
| F1-05 | 설정에서 F1을 끌 수 있다 (설정 화면 작업 때 연결) |

### 6.5 구현 메모 (2026-11-05)

- 경로·TBT·정체는 `PleosRouteSource` 하나로 모아 F1과 F3(휴게소·다음 회전 거리)가 함께 쓴다.
- 정체는 `RouteStateInfo.trafficStatus.congestionItemList`의 `offset`(시작 지점), `distance`(길이), `trafficCongestionType`(`SLOW`·`HEAVY`)을 쓴다. `offset`이 현재 위치 기준인지 경로 시작 기준인지는 확인 필요.
- `RouteInfo` 생성자는 **경도가 먼저**다 (SDK jar 필드 순서 `longitude, latitude`).
- 경로마다 `onRouteStarted`로 새 경로 ID를 정하고, 재탐색(`onReRouteEnded`)은 같은 경로로 본다 (F1-02).
- 직전 안내는 다른 회전이 300m 안에 있으면 미룬다 (F1-03을 내비 음성 겹침 대신 거리로 어림).
- **에뮬레이터 확인 막힘:** 경로 요청은 내비 앱까지 정상 전달되지만, Pleos 경로 서버(`connect-gw.umos.ai/.../route/find-route`)가 `500 "fail to get odd policy … Unavailable"`을 돌려줘 경로가 만들어지지 않는다. 그래서 TBT 범위(F1-04)·거리 단위·정체 `offset`·휴게소 판정은 서버가 정상일 때 다시 확인한다.

---

## 7. F4 — 주행 리포트

### 7.1 표시 조건
주행이 끝나고(0.3 정의) 정차 중일 때, 주행 시간이 5분 이상이면 자동으로 보여준다. 주행 중에는 열 수 없다. 화면 구성·문구는 `docs/screen-spec.md` 4절을 따른다.

### 7.2 화면 구성
`docs/screen-spec.md` 4절(SC-04)을 따른다. 헤드라인 문장, 숫자 3개, 개입 타임라인(학습 결과 문구 포함), 최근 7회 공격성·피로 추세, 학습 상태로 구성한다.

### 7.2.1 문장 규칙 (`TripSummarizer`, 2026-11-06 구현)

| 항목 | 규칙 |
|---|---|
| 헤드라인 | 피로 케어(졸음 케어·휴게소 제안)가 있었던 주행이면 "오늘은 조금 피곤하셨던 것 같아요" (공격성이 낮아도 "차분한 주행"이라고 하지 않는다). 아니면 이번 주행 평균 공격성 − 최근 7회 평균. −6 이하 "평소보다 차분한 주행이었어요", +6 이상 "오늘은 조금 바빴네요", 그 사이 "평소와 비슷했어요". 지난 기록이 없으면 기준 20점(기준선과 같은 운전의 점수)과 비교 |
| 휴게소 제안 기록 | F3 단계가 `AWAITING_REPLY`로 바뀔 때 시작, 다음 단계가 `HEADING_TO_REST`면 수락, 그 외는 거절. 수락 뒤 정차하면 `RESTED` |
| 저장 시점 | 주행 1회가 끝날 때(P 후 60초) 한 번 저장. 5분 이상이면 리포트를 띄운다 |
| DB 버전 | v2. 출시 전이라 스키마가 바뀌면 지우고 다시 만든다(`fallbackToDestructiveMigration`) |

### 7.3 저장 모델 (Room)

```kotlin
@Entity data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long, val endMs: Long,
    val distanceKm: Float?,
    val avgAggression: Int, val maxAggression: Int,
    val avgFatigue: Int, val maxFatigue: Int,
    val learning: Boolean,
    val learnedTrips: Int,          // 이 주행까지 쌓인 주행 수 (학습 진행 "3/5회")
    val hardSections: Int?,         // F1 직전 안내로 지난 어려운 구간 수. 경로 안내가 없었으면 null
    val longestCalmMs: Long,        // 공격성 40 미만이 이어진 가장 긴 시간
)
@Entity data class InterventionEntity(   // trip 삭제 시 함께 삭제 (CASCADE)
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long, val startMs: Long, val endMs: Long?,
    val kind: String,      // COOLDOWN / FRESH_AIR / FATIGUE_HOLD_ON / FATIGUE_SUGGEST
    val maxStage: Int,
    val outcome: String,   // DONE / COLD / STOPPED / YIELDED / FAILED / ACCEPTED / DECLINED / RESTED
    val levelChange: Int,  // 이 개입 뒤 F2 강도 변화. −1이면 "다음엔 덜 차갑게 할게요"
    val recoveryMs: Long?, // v3: 쿨다운 시작부터 공격성이 40 아래로 내려와 2분 넘게 유지되기 시작한 때까지. 리포트에 "평균 N분 만에 차분해졌어요" (지난 쿨다운 3건 이상이면 평소와 비교)
)
@Entity data class BaselineEntity(
    @PrimaryKey val id: Int = 0, val json: String, val totalTrips: Int, val totalMinutes: Double,
)
@Entity data class ComfortProfileEntity(   // F2-14, 4.7.1 — 계절 구간당 한 행
    @PrimaryKey val band: String,   // SUMMER / MILD / WINTER
    val level: Int,                 // 0~3
    val baselineTempC: Float?,      // 개인 기준 온도 (F2-23)
    val weakSignals: Int,           // F2-21
    val positiveStreak: Int,        // F2-22
)
```

---

## 8. S1 — 온보딩·설정

화면 구성·문구는 `docs/screen-spec.md` 2절(SC-01 온보딩)과 7절(SC-06 설정)을 따른다.

| ID | 요구사항 |
|---|---|
| S1-01 | 온보딩은 3단계(소개 → 공조 자동 조절 동의·권한 → 학습·음성 안내)이고 정차 중에만 진행한다. 2단계에서 "나중에"를 고르면 F2·F3는 꺼진 채로 시작한다 (F2-08) |
| S1-02 | 설정에서 F1·F2·F3·음성 안내를 켜고 끈다. F2 강도는 직접 고르지 않고 학습된 계절별 강도와 기준 온도를 보여 준다 (F2-07) |
| S1-03 | "운전 습관 다시 배우기"는 C0 기준선과 `ComfortProfileEntity`를 지운다. "기록 전체 삭제"는 Trip·Intervention을 지운다 |
| S1-04 | 설정과 온보딩은 정차 중에만 열 수 있다. 주행이 시작되면 주행 중 화면(SC-03)으로 바뀐다 |

---

## 9. 권한 정리 (AndroidManifest)

| 권한 | 용도 | 상태 |
|---|---|---|
| `android.car.permission.CAR_SPEED` | 차속 (AAOS) | 추가 |
| `pleos.car.permission.CAR_DRIVE` | 기어·브레이크·조향·주행 거리 | 추가 |
| `pleos.car.permission.CAR_CLIMATE` | 공조 상태 조회 | F2 작업 때 추가 |
| `android.car.permission.CAR_EXTERIOR_ENVIRONMENT` | 외기온 (계절 판단, F2-19) | F2 작업 때 추가 |
| `pleos.car.permission.CONTROL_CAR_CLIMATE` | 공조 제어 | F2 작업 때 추가 **(사전 승인 필요)** |
| `pleos.car.permission.TTS_SERVICE` | 음성 합성 (F2 안내, F3 제안) | F2 작업 때 추가 |
| `pleos.car.permission.STT_SERVICE` | 음성 인식 (F2 응답창, F3 응답) | F2 작업 때 추가 |
| (권한 없음) Gleo Action 수신기 | F2·F3 Gleo 연동 (4.8) | F2 작업 때 추가 |
| `pleos.car.permission.NAVI_ROUTE` 계열 | 경로·TBT·경유지 | 필요한 것만 남김 (F1 작업 때 정리) |
| `CAR_INFO`, `CAR_ENERGY`, `pleos.car.permission.CAR_SPEED` | — | **삭제** (쓰지 않거나 존재하지 않음) |

---

## 10. 테스트·시연 계획

| 종류 | 대상 | 방법 |
|---|---|---|
| 단위 테스트 | C0 특징·기준선·점수, F2·F3 상태 머신, F1 구간 판정·문장 | JVM, 가짜 `Clock`, 녹화 시퀀스 |
| 통합 확인 | D0 리스너, HVAC 제어, TTS·STT | Pleos Connect 에뮬레이터 + Virtual Console |
| 시연 F2 | `f2_aggressive` (거친 운전 13분) | 디버그·demo 빌드 `--es scenario f2_aggressive` → `FakeVehicleDataSource` CSV 재생 |
| 시연 F1·F3 | `f3_highway` (P 브리핑 → 고속도로 100km/h 56분 단조 주행 → 휴게소 P, 약 63분) | `--es scenario f3_highway`. 짝 파일 `f3_highway.route.csv`가 있으면 `ScenarioRouteSource`가 내비 경로 대신 분기·정체·휴게소를 주행 거리에 맞춰 준다 (경로 서버 없이 F1·F3 확인). 시나리오 모양은 `F3ScenarioTest`로 고정 |

## 11. 기존 코드 처리

| 기존 | 처리 | 시점 |
|---|---|---|
| `PleosVehicleDataSource` (속도 리스너 미등록, 기어만) | D0로 재작성 | 11/3 |
| `appCategory="game"` | 제거 (기본값 사용). Pleos 마켓 카테고리는 콘솔 등록 정보에서 정함 `[확인 필요]` | 11/3 |
| `TrafficViolation`, `DrivingScore`, 대시보드 점수 판정 | 삭제, C0로 교체 | 11/4 |
| 대시보드·결과·기록·설정 화면 | **폐기.** `docs/screen-spec.md`대로 새로 만든다 | 11/9 |
| `shared` 모듈의 `MyMusicService` (템플릿 잔재, `appCategory="audio"` 미디어 서비스로 등록됨) | automotive에서 `shared` 의존성 제거. 그래야 앱이 미디어 앱으로 병합되지 않음 | 11/3 완료 |

## 11.5 에뮬레이터 확인 결과 (2026-11-05, Pleos_Connect_v2.0)

| 항목 | 결과 |
|---|---|
| 포그라운드 서비스, Vehicle·HVAC·Seat·NaviHelper·TTS·STT 초기화 | 정상 |
| 외기온 `ENV_OUTSIDE_TEMPERATURE` | 25.0℃ → `SUMMER` |
| F2 1단계 | 30초 유지 후 설정 온도 24 → 23℃, TTS "조금 시원하게 해 드릴게요", 3초 뒤 STT 응답창 |
| F2 2단계 | 2분 뒤 22℃, 통풍시트 0 → 1 |
| Gleo `ADJUST_CABIN_COMFORT(cold)` | 0.5초 안에 24℃·통풍 0 복원, `COLD_FEEDBACK`, `success` 응답 → Gleo가 응답 TTS 단계로 진행 |
| 주행 종료·저장 | P 60초 뒤 종료, Room에 `SUMMER` 강도 3 → 2, 기준 온도 24℃, 기준선 1회·11.5분 저장 |

**확인 중 발견해 고친 것:** 속성별 VHAL 영역(풍량·풍향 0x5, 전원·내기순환 0x75), 지원 범위 확인을 순차·시간 제한으로, 구독 뒤 현재 값 읽기, SDK 숫자가 Double로 와서 생긴 크래시, 속도대가 바뀔 때 특징 창이 지워지던 문제, TTS `onDone`이 오지 않아 STT가 늦게 열리던 문제.

**에뮬레이터 한계·특이 사항:**
- Pleos가 풍량 단계 목록과 풍향 지원 목록을 빈 값으로 준다 → 이 에뮬레이터에서는 풍량 +1, 얼굴 쪽 풍향이 동작하지 않는다 (VHAL 자체는 풍량 1~8)
- VHAL 차속·기어 주입(`inject-vhal-event`)은 곧바로 덮어써진다 → 디버그 빌드의 시나리오 재생(`--es scenario`)이나 Virtual Console을 쓴다
- 앱은 운전자 프로필(Android 사용자 10)로 돈다. `adb` broadcast는 `--user current`가 필요하다 (F2-24의 프로필별 분리와 일치)
- 서버에 스키마가 등록되지 않은 상태에서 Gleo Action을 `adb`로 보내면, Gleo가 응답 생성 서버 요청에서 `HTTP 404`로 죽는다 (Gleo 앱 쪽 문제, 문의 필요)
- TBT는 경로 안내 중에만 와서 휴게소 판정은 F1 경로 연동 때 확인한다

- 2026-11-06: `f2_aggressive` 재생 시 쿨다운이 복원되지 않고 `TRIP_ENDED stage=2`로 끝남. 정책 문제가 아니라 시나리오 길이 문제였다. 공격성은 3분 창이라 거친 운전이 끝나고 약 2분 반 뒤에야 40 아래로 내려오고, 거기서 평온 5분(F2-05)을 채우기 전에 P가 됐다. 차분 구간을 7분 → 10분으로 늘리고, 시나리오가 P 전에 복원·잠금까지 가는지 `F2ScenarioTest`로 확인한다.

- 2026-11-10: 풍향 다시 읽기 확인. VHAL `0x15400501`(영역 0x5)을 바꾸면 2초 안에 snapshot이 바뀐다. 쿨다운 1단계 중 DEFROST로 바꾸자 `cooldown ended: YIELDED_MANUAL stage=1`. **Pleos SDK `FanDirectionType`은 FACE=1·FLOOR=2·DEFROST=3이라 VHAL 표준 DEFROST(4)·조합값(3·6)을 읽으면 결과가 오지 않는다** → 읽지 못하면 null로 두고 양보하도록 바꿨고, 2단계 중 VHAL 4로 바꾸자 `YIELDED_MANUAL stage=2`. 실차의 값 체계는 확인 필요. 다시 읽기는 샘플링과 따로 돌려 SDK가 답하지 않아도(최대 3초) 점수 계산이 멈추지 않는다.

- 2026-11-12~13: `f3_highway` 66분 녹화에서 찾아 고친 것.
  - P단 신호가 경로보다 먼저 오면 구간 계산 전에 브리핑해 "어렵지 않은 길이에요"로 오안내 → 브리핑 때 받은 경로로 다시 계산.
  - 앱 시작 직후 SDK가 온도 지원 범위 확인에 3초 안에 답하지 않아 공조 snapshot이 끝까지 비어, 외기 전환·버티기 공조가 모두 멈춤 → 지원 범위 확인·현재 값 읽기를 1초 뒤 한 번 더 묻고, 그래도 못 얻은 것은 주행 중 10초마다 다시 얻는다(`recoverMissing`).
  - 외기 전환 중에 버티기가 시작되면 "원래 값"을 외기로 저장해 끝나도 외기로 남을 수 있음 → 피로 케어에는 운전자가 둔 값(내기순환)을 넘긴다(`FreshAirPolicy.driverSet`).
  - 피로 케어·외기 전환·점수 로그가 없어 원인을 못 찾음 → 단계 변화·공조 명령·1분마다 점수와 공조 상태를 남긴다.
  - `cmd car_service set-property-value`로 불리언 속성에 `1`을 넣으면 false로 들어간다. `true`를 쓴다.
- 2026-11-13 재녹화: 브리핑·직전 안내 2회, 외기 전환 2회(각 5분 뒤 복원), 버티기 24 → 23℃, 휴게소 제안("12km 앞")·Gleo 수락·입구 안내·휴식 요령, 정차 후 공조 원래대로(24℃·내기순환), 리포트 "도와드린 횟수 4번". 에뮬레이터가 탑승 인원을 3명 이상으로 줘서 외기 전환은 15분 기준으로 동작했다.

## 12. 미확인 항목

| 항목 | 영향 | 확인 방법 | 대안 |
|---|---|---|---|
| `CONTROL_CAR_CLIMATE` 승인 | F2·F3 | Pleos App Market 문의 | 도착 후 리포트에서 "이럴 때 시원하게 해 드릴 수 있어요"로 축소 |
| `onTBTInfo` 범위 | F1 | 에뮬레이터 모의 주행 로그 | F1-04 |
| POI(휴게소) 검색 | F3 | NaviHelper jar·문서 | F3-04 |
| 주행 거리 단위 | F4 | 에뮬레이터 로그 | — |
| 공모전 시연에서 LLM 사용 가능 여부 | X1 | — | 템플릿 문장 |
| Gleo 스키마 승인·서버 등록 절차와 기간 | F2 경로 ③, F3 보조 경로 | Pleos Playground 문의 (배포·심사 문서 17개에는 언급 없음) | `adb` broadcast로 시연 (4.8) |
| App Schema `invokeWord` 필드의 의미 | F2 경로 ③ | 문의 (문서 예시에만 있고 설명 없음) | 생략 |
| TTS 직후 STT 자동 시작 시 차량 UI·알림음 | F2-11 | 에뮬레이터 실험 | 응답창 없이 경로 ①·③만 사용 |
| 에뮬레이터·차량의 `ENV_OUTSIDE_TEMPERATURE` 제공 여부 | F2-19 | 에뮬레이터 `cmd car_service get-property-value` | 달로 판단 |
| 풍향·통풍시트 제어 권한 | 4.7.1 (겨울·여름 수단) | SDK jar 권한 어노테이션, 문서 | 해당 수단 없이 온도·풍량만 |
| 실내 공기 예방(F3-P3)의 기준 시간 15분·30분 | F3 예방 | 차량에 CO2 센서 API가 없어 추정 규칙으로 둠. 에뮬레이터 모의 주행·실차로 조정 | 기준 시간을 보수적으로 유지 |
| Pleos 운전자 프로필 = Android 사용자 1:1 여부 | F2-24 (운전자별 학습) | 에뮬레이터에서 프로필 전환 후 `am get-current-user` | 프로필 구분 없이 학습 (한계로 명시) |
| 차량 화면 해상도별 레이아웃·타이포그래피 토큰 | 화면 명세 | Pleos 디자인 가이드 | 14.6 기준 단일 레이아웃 |
