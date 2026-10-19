# 35. 구현: 마법 3종(메두사·제우스·하데스) / 마법 선택 / TimedPress·스틱 미니게임

분석: [10 문서](10-combat-system.md) §10·§12 (이번 단계에서 확장). 패키지 `devple.gow.engine.combat` (+ `fx/MagicFx`).
실행: `./gradlew :engine:run --args="--hero --enemy R_MIN --auto magic"` (← 메두사 선택 → R2 홀드 광선 → 석화 → □ 로 파괴).
연습 씬은 마법 4종을 모두 보유한 상태로 시작한다. 방향키로 선택하고 R2 로 시전한다.

## 1. 발동 경로: 영웅 기술의 SCRIPT 이벤트 = SCR_ 명령
영웅 CombatFile 의 SCRIPT(8) 이벤트 해시는 **SCR_ 명령 이름의 Hash127Upper** 다 [확실: 전부 대조됨].
원작은 기술 진행 중 이벤트 창에서 해당 스크립트 명령을 부른다. 엔진도 같은 이벤트 창으로 효과를 발동한다
(`Combatant.activeScript(hash)`, 기술 인스턴스 번호 `moveSerial()` 로 1회 발동).

| 해시 | 명령 | 기술 (창) |
|------|------|-----------|
| 0x45FA7BF4 | SCR_MedusaHead | M82 USEHEAD [0–1] = 광선 |
| 0x8466F02A | SCR_FlashMedusaHead | M84 AIRUSEHEAD [0.38–1] |
| 0xE5D96CF1 / 0x7408DBD2 | SCR_ShowMedusaHead / SCR_TriggerMedusaHead | M81 PSENTHEAD |
| 0x46B9F9F5 | SCR_MedusaNuke | M65/66 HEADNUKESLAM [0.47] |
| 0x96EDE590 | SCR_ThrowThunderbolt | M61 BOLTTHROW 0.55, M62 BOLTTHROWL 0.33, M63 AIRBOLTTHROW 0.30 |
| 0xDD87FB68 / 0x8D7EAE7F / 0x1706B01E / 0x32544161 | Charge / Show / Trigger / PlaybackThunderbolt | M60/M61 |
| 0x139D9006 | SCR_RaiseTheDead | M508 RAISETHEDEAD 0.58, M509 AIR 0.72 |
| 0x79C369CD | SCR_Lightning | M507 |
| 0xF980EF69 / 0xE513E9CB | SCR_TimedPress / SCR_RepeatPress | 적 MAI 기술 (idx = 템플릿 번호) |

**단위** [확실]: TWK 거리·속도 × 16 = 월드 유닛 (0x41800000 곱이 화살 적분·영혼·메두사 원뿔 모두에 있음).

**MP 소모 위치**: 원작은 효과 쪽에서 0x1DCA58(ConsumeMagic)을 부른다. 분기 시작 시 확인은 HasMagic(0x1D93C0)뿐이다.
엔진의 `Magic.use()` 는 번개(3)만 분기 시작 때 차감하고, 나머지는 각 효과 클래스가 차감한다.

## 2. 메두사의 시선 (`MedusaGaze`) — 0x1964D0, 0x1972E8, 0x1961D8, Freeze 0x1E1778
TWK `/Player/MedusaHead` 의 일부 값은 덤프가 float 로 잘못 보여준다. 실제로는 u16/bool 이다:

| 단계 | Distance | Cone Top/Base | Drain (MP/초) | Freeze Factor (u16) | Flash 거리/원뿔 | Flash Points | Flash Freeze | Nuke |
|---|---|---|---|---|---|---|---|---|
| 0 | 15 | 0.5 / 3.25 | 12.5 | 100 | – | – | (10000, 불가) | – |
| 1 | 15 | 0.5 / 3.25 | 10 | 115 | 14 / 0.5–2.5 | 20 | 8000 | – |
| 2 | 15 | 0.5 / 3.25 | 8.33 | 125 | 18 / 0.5–2.5 | 20 | 8000 | 100 |

**원뿔**:
- 기울기 = (Base − Top) / Distance, 반각 = atan(기울기) ≈ 10.4°.
- 꼭짓점은 눈에서 Top/기울기 (2.73m) 뒤, 길이는 (Distance + apexBack) × 16.
- 적 반지름 구와 원뿔의 교차로 판정한다.
- 원작은 rWeapIH 관절 위치를 쓰고 시선 충돌 검사(0x195FB0)를 한다. 엔진은 고정 높이 50을 쓰고 시선 검사를 생략한다 [근사].

**광선**:
- USEHEAD 동안 마법 버튼을 누르고 있고 MP ≥ Drain·dt 이면 켜진다. MP −= Drain·dt.
- 원뿔 안 적의 동결 게이지 −= FreezeFactor × 60 × dt.
- 조준은 락온 대상 쪽으로 프레임당 0.15 보간한다 (Aim Speed). 몸 정면 ±80° 로 제한한다 [추정].

**USEHEAD 는 애니가 없는 기술이다**. 원작은 SCR_MedusaHead 가 useHeadTurnLeft/Right 를 직접 섞는다.
`Combatant.setMoveHold` 로 버튼을 누르는 동안 기술을 유지하고, 버튼을 떼면 원작 분기대로 PUTAWAYHEAD(83)로 간다.

**섬광** (단계 1·2):
- AIRUSEHEAD 의 SCR_FlashMedusaHead, 또는 광선 중 **△** 로 발동한다. USEHEAD 내내 도는 `SCR_TriggerMedusaHead`(0x197FD8, #7408dbd2)가
  단계 `Allow Flash Freeze`(bool) 이고 패드 비트 4(게임 패드 배치 = △, 10 문서 §2.5 표와 같은 순서) 새로 누름이면 섬광 요청(0x29D804 = 1)을 건다
  [확실: 코드 / 이전 문서의 "○" 는 오류].
- MP ≥ 20 이면 20 을 한 번 소모하고, 섬광 원뿔 안 적에 8000 을 한 번 준다.
- MP 가 모자라면 SND_IG_MAGICOUT 을 낸다.

**Nuke** (단계 2) [확실: 흐름·값 / 추정: 반경 축소 근사]:
- 조건: `SCR_TriggerMedusaHead` 가 매 프레임 `Allow Nuke`(단계 2 만 1) 이고 MP ≥ `Nuke Points`(100) 이면 combatant `+0x1AC |= 0x1000`
  (sub_001E1B70), 아니면 끈다(sub_001E1BD0). USEHEAD 의 `CIRCLE/press MedusaHead → M64 HEADNUKESTART` 분기 flags `0x10824B` 의
  **0x8 = +0x1AC&0x1000 필요, 실행 시 소비** (10 문서 §2.4) — 그래서 단계 0·1 이나 MP 부족이면 ○ 가 아무 일도 안 한다.
- HEADNUKESTART(64, 차지 SND_CHARGE_LP) → ○ 를 떼면 M65, 끝까지 차지하면 M66 HEADNUKESLAM. 0.47 에 `SCR_MedusaNuke`(0x198928, #46B9F9F5)
  이벤트 둘: M65 = idx 0 + 3, M66 = idx 1 + 4. **idx = Concussion 템플릿 번호**.
- `SCR_MedusaNuke`: 단계 Nuke Points 를 MP 에서 소모(sub_001DCA58) → Concussion 템플릿을 synchJoint 에 생성(sub_00191720).
  | 템플릿 | 이름 | Freeze(bool) | Freeze Factor(u16) | 반경 시작→끝 | 지속 |
  |---|---|---|---|---|---|
  | 000 | Medusa Nuke S | 1 | 30000 | 7.5 → 3.0 | 0.1 |
  | 001 | Medusa Nuke L | 1 | 30000 | 15.0 → 3.0 | 0.3 |
  | 003 / 004 | Medusa Small / Large FX | 0 | – | 0 → 7.5 / 15 | 0.3 / 0.6 |
- 엔진 (`MedusaGaze.nuke`): 이벤트마다 한 번, Freeze 템플릿이면 영웅 중심 시작 반경(× 16, 적 반지름 포함) 안 대상에 동결 30000 (석화 게이지 최대
  6000 인 미노타우로스는 즉시 석화, 원뿔 밖·뒤도 포함), FX 템플릿은 Effect goMedHeadNuke 만. `Combatant.setStateBit(STATE_TRIGGER)` / 분기 0x8 조건.
- 검증: `MagicSpellsTest.medusaNukeAtStage2` (뒤쪽 120 유닛 미노타우로스 석화, MP 100 소모), `medusaNukeNotAtStage0` (○ 분기 없음).

### 2.1 적 쪽 동결 게이지 / 석화 / 파괴 (`EnemyFighter`, 0x1E8C40)

| 값 | 출처 | 예 |
|---|---|---|
| 게이지 최대 | Sold +0x26C u16 (`AiData.stone`) | 미노타우로스 6000, 하피 1500, 사이클롭스 20000/25000. ≥ 60000 = 면역 (메두사, 갑옷병) |
| 석화 기술 | Sold +0x270 | 적 160 freezePose, 영웅 120 |
| 돌 지속 | AI1 #b5134762 (+0x40, ms) | 대부분 5000, 사티로스 4000 |
| 회복 | `/GlobGame/Unfreeze Speed` (u16 50) × 60 | 3000/초. 그 프레임에 광선을 안 맞았을 때만 |

- 단계 0 광선은 6000/초이므로 미노타우로스는 **약 1초** 만에 석화된다.
- 게이지가 0 이 되면 메시지 0x407 → 석화 기술. 엔진은 freezePose 를 끝까지 재생한 뒤 그 자세로 정지하고 AI 를 멈춘다.
- 지속 시간이 지나면 UnFreeze 로 원상 복귀하고 게이지를 최대로 되돌린다. 원작의 석상 흔들림(`경과/지속 × 1.6`)은 미구현.
- 돌 상태에서 맞으면 석상(Brkb Hit Points 1)이 부서진다. 소유자는 사망(0x405)하고 0x416 = Stone Kill 이다.
- 보너스는 적 WAD 의 Bonus 023 "Stone Kill" (붉은 오브 15, 메시지 33), 공중이면 024 "Air Stone Kill" (30, 34) [확실: 값].
- `GameScene.stoneKill` 에서 오브를 더한다. 파편 모델 `*stonedbrk` 대신 회색 파편 파티클을 쓴다 [근사].

## 3. 제우스의 분노 (`ZeusBolt`) — 0x19FB50–0x1A10A8, Arrow 0x222710 / 0x2249A0
| 단계 | Arrow Emitter | Magic Drain | Playback | Charge Time | Ch1 (임계·이미터·MP) | Ch2 |
|---|---|---|---|---|---|---|
| 0 | 2 | 5 | 1.15 | 0.5 (불가) | – | – |
| 1 | 2 | 3.33 | 1.35 | 0.5 | 0.25 · 3 · 10 | 0.5 · 3 · 10 |
| 2 | 2 | 2.5 | 1.75 | 0.75 | 0.375 · 3 · 10 | 0.75 · 5 · 20 |

**흐름**:
- R2 홀드 → BOLTPULLOUT(60) → AUTO → BOLTTHROW(61) 반복.
- SCR_ThrowThunderbolt 창에서 기술 인스턴스당 1발을 쏜다.
- 원작 상태 머신 0x29D85A: 큐 최대 2, 버튼 0x10 홀드 시 차지.

**차지**:
- □ 를 누르고 있는 시간 / Charge Time ≥ Ch2 이면 Ch2, ≥ Ch1 이면 Ch1 [추정: 비트 0x10 = □].
- 차지 샷의 MP 는 기본 소모를 **대체**한다 (0x1DCA58 한 번).
- Allow Charging 은 bool 이다 (하위 바이트, 단계 0 = 거짓).

**투사체** (Arrow 002/003/004, 모두 Velocity 60, 수명 #117c872e 5초):

| 샷 | Damage | Ground Impulse | Concussion |
|---|---|---|---|
| 일반 002 | 8 | 300 | – |
| Ch1 003 | 20 | 600 | 005 반경 2m |
| Ch2 004 | 65 | 900 | 006 반경 4m→2m |

- 속도는 60 × 16 = 960 유닛/초다. 중력 플래그는 꺼진 것으로 본다 (직선) [추정].
- 대상 중심을 조준한다. 원작 0x2223C8 은 탄도 해이며 중력이 없으면 직선이다. 대상이 없으면 정면으로 쏜다.
- 프레임마다 이전→현재 위치 선분과 대상 중심(높이 40) 거리 ≤ 28 이면 명중한다 [근사: 원작은 충돌 마스크 광선].
- Concussion 범위 충격의 피해값은 덤프 손상으로 미확인이다 → 0 (충격만).

## 4. 하데스의 군대 (`HadesSouls`) — 0x19D098–0x19F6B0, 매 프레임 0x19DAC0
| 단계 | 영혼 | Magic Drain | 합계 | Max Time | 타당 | Max Damage | 속도 m/s | Reach m | Homing |
|---|---|---|---|---|---|---|---|---|---|
| 0 | 3 | 25 | 75 | 15 | 10 | 125 | 12.5 | 25 | 10 |
| 1 | 4 | 18.75 | 75 | 15 | 15 | 120 | 15 | 50 | 10 |
| 2 | 6 | 12.5 | 75 | 15 | 20 | 115 | 17.5 | 75 | 10 |

- 시전 조건은 0x1D9510: Num × Drain = 75. 영혼마다 Drain 을 소모한다.
- 초기 속도는 반경 방향 (cos 2πi/N, 0, sin 2πi/N) × MaxVel 이다.
- 표적은 영웅 Reach × 16 안의 적을 차례로 배정한다 (적보다 영혼이 많으면 순환).
- 표적이 없으면 크레토스 주위 궤도점 `16·(3 sin a, 2 + sin b, 3 sin c)` 을 따른다 (a, b, c = π/2, 4.19, 2.97 rad/s).
- 조향: `vel += dir·MaxVel·Homing·dt → |vel| = MaxVel`.
- 명중: 거리 < 2m, 마지막 명중 후 0.5초 경과 → Damage Per Hit. 누적이 Max Damage 이상이면 소멸 (goDeadDie).
- 15초가 지나면 전부 소멸한다.

## 5. 미니게임
### 5.1 RepeatPress 재해석 (0x192160) — `RepeatPressQte` 수정
이전 구현은 일부 필드 의미가 틀렸다. 고친 내용:
- 게이지는 **0 에서 시작**한다.
- `#1fc6d36d`(0.5)는 시작값이 아니라 **누름 사이 최대 간격**이다. 넘으면 실패.
- 감소 `#b4af76ab` 는 **프레임당** 적용한다 (× 60 × dt).
- 최소 간격 `#870e6017`(덤프 이름 "Bpf_B2", 0.1) 보다 빨리 누르면 `F·(1 − since/min)` 만큼 감점한다.
- 표시 평활 `#4ba7dfc2`: disp += (meter − disp) × k. 이 disp 가 애니 진행률이 된다.
- 템플릿 번호는 SCRIPT 이벤트 idx 다. 미노타우로스 MAIMIN02 = 50 인데 R_MIN 에는 000 뿐이라 첫 템플릿을 대신 쓴다.

### 5.2 TimedPress (`TimedPressQte`, 0x192810 / 0x192ED0 / 틱 0x192C90 / 실패 0x192A80)
- **SCRIPT 이벤트 하나 = 프롬프트 하나**. 입력 창 = 그 이벤트의 [start, end] (시간 상수 없음).
- 성공해도 기술은 그대로 진행된다.
- 실패(틀린 버튼은 즉시, 창 만료)하면 현재 기술의 **입력 0 이고 창이 이벤트 창을 포함하는 첫 분기**를 실행한다 (`[창]! REQUEST0 -> 실패 기술`, `Combatant.runWindowBranch`).

**템플릿 필드**:

| 키 | 형 | 의미 |
|---|---|---|
| #d650acb2 | u16 (하위 바이트) | 종류 (값 표 0x29D740) |
| #776f2158 | s8 | 회전 허용 오차 (첫 단계 전 틀린 사분면 변화 수) |
| #815ff05d | 문자열 | 표시 소리 |
| #f48a3e39 | 문자열 | 성공 소리 (덤프 "dlOvlytab") |

**종류**:

| 값 | 의미 | HUD 클립 (0x29DA40) |
|---|---|---|
| 6 / 4 / 5 / 7 | × / △ / ○ / □ (틀린 얼굴 버튼 = 즉시 실패) | XButtonGreek, TriangleButtonGreek, CircleButtonGreek, SquareButtonGreek |
| F0–F5 | Up180CW (←,↑), Up180CCW (→,↑), Down180CW (→,↓), Down180CCW (←,↓), 360CW (←,↑,→), 360CCW (→,↑,←) | 이름 그대로 |
| FB / FC / FD / FE / FF | 무작위: 10종 / 회전 6 / 180 4 / 360 2 / 얼굴 버튼 4 | – |

**회전**:
- 왼쪽 스틱 사분면: |x|,|y| < **0.6** 이면 중앙, 그 밖은 큰 축 방향.
- 사분면 **변화**만 센다. 순서와 일치하면 진행, 다르면 오차 +1.
- 진행한 뒤 틀리면 실패 표시를 하고, 창이 끝날 때 실패 분기를 실행한다.

| 적 | 기술 | 프롬프트 |
|---|---|---|
| 사이클롭스 (R_CY2, self 41) | M240 MAICYWMINI02 | 0.07–0.17 / 0.21–0.31 / 0.38–0.48 / 0.53–0.63 → TP 000 "slowPress" (FF 무작위 버튼). 실패 242/243/244/249 |
| 사이클롭스 Lv3 (self 42) | M280 MAICY3MINI01 | 3개 → 000. 실패 281–283 |
| 고르곤 (R_MEW) | M260 MAIMEWGRAB | 0.25–0.45 → 060 "medusa mini 180" (FD), 0.46–0.75 → 061 "360" (FE). 실패 M261 MAIMEWESCAPE |
| 고르곤 | M263 | 060, 060, 061 |
| 하피 (R_HAR) | M270 MAIHARWINGRIP | QTE 없음 |
| 공용 (R_PERMK) | – | 206 arcPress180rand (FD, 오차 5), 207 arcPress360rand (FE, 10) |

**사이클롭스 처형 애니**:
- 적 세트(ANM_cyw/cy2)에는 처형 애니가 없다. 영웅 외부 그룹 maiCy2(R_CY2 의 ANM_MAICYW)에만 MAICYWMini02 … 가 있다.
- `Combatant.setClipFallback` 이 영웅 세트의 같은 해시 애니로 시간·이름을 맞춘다. 적 자세는 정지 [근사].
- GrabSync 가 결합 대상마다 연결한다.

**Wiggle Meter** (`/Player/Wiggle Meter Drain` 2.0, 0x1D8B98): 프레임마다 `max(좌, 우 스틱 |Δ| × 0.2 × (1 + 0.6(1 − |s|))) − 2/60`. 사용처는 미확인 (잡힘 탈출 추정) → 미구현.

### 5.3 GrabSync 통합
- 결합 중 적 기술에 SCR_RepeatPress + 속도 0 이면 연타, SCR_TimedPress 창이면 타이밍 프롬프트.
- 결과는 `executionSucceeded()` 로 확인한다: 연타 성공, 또는 프롬프트 전부 성공.
- `GameScene` 은 처형이 성공으로 끝나면 적을 사망 처리한다.
- 소리 SND_MINIGAME_BUTTON/HIT 는 `onSound` 로 낸다.

## 6. 마법 선택 / HUD
**방향키** (sub_001D7E10 → 0x1D7ED8):

| 방향 | 마법 |
|---|---|
| → | 3 포세이돈 |
| ← | 4 메두사 |
| ↓ | 5 제우스 |
| ↑ | 6 하데스 |

- 보유하지 않은 마법이면 SND_SELECT_NULL, 이미 선택된 마법이면 무시한다.
- 바뀌면 SND_SELECT_LIGHTNING/MEDUSA/BOLT/RTD 를 내고 0x3044BE 에 기록한다.
- 구현: `Magic.select` (HeroRig 매 틱).
- 키보드 방향키는 오른쪽 스틱(회피)과 겹친다.

**HUD**:
- `PS2_PickUp_Event` 가 선택 마법 아이콘이다. 0 = 없음, 2·id − 1 = 시전 가능, 2·id = 불가 [짝/홀 의미 추정].
- FLP_HUDK 는 MainMeter/PickUps 를 GotoFrame(1 + 값) 한다. 바뀔 때만 보낸다 (`HudBinding`).
- `*_Status` 는 아이템 비트 0x4–0x40 (기존 구현 그대로).
- 미니게임 버튼 아이콘은 변수가 아니다. 원작 `sub_001BA338(hud, idx)`: idx 가 바뀌면 이전 인스턴스를 떼고(`sub_0014AB18`),
  idx &lt; 15 이면 표 0x29DA40 의 이름 심볼을 FLP 에 붙인다(`sub_0014A980`). 표: 0–9 TimedPress 종류(XButtonGreek … 360CCW),
  10 XButtonPress(연타 ○ 버튼 그림), 11 R2ButtonPress, 12 WiggleStick, 13 WiggleBackForth, 14 R2ButtonStatic.
- **엔진**: `FlpPlayer.attach/detach(name)` — 루트 타임라인 키프레임에서 그 이름의 배치를 찾아 같은 변환으로 인스턴스를 만들어
  루트 위에 그린다 (루트 타임라인이 지나가도 유지). `MinigameIcon.show(player, idx)` 가 한 번에 하나만 유지.
  `GameScene`: 결합 중 타이밍 프롬프트 → `timed().iconIndex()`, 연타 진행 중 → 10 XButtonPress [추정: 연타 아이콘 선택], 그 밖 숨김.
  검증 `MinigameIconTest` (15개 전부 FLP_HUDK 에 있음, 붙이기/떼기/유지), 화면 `--ui gow-rom/R_PERMK.WAD FLP_HUDK icon:CircleButtonGreek`
  (`work/uirt/icon_circle.png` 화면 위 가운데 ○, `icon_press.png` 왼쪽 아래 연타 버튼).

## 7. 시각 효과 (`fx/MagicFx`, 근사)
원작 GO 이펙트는 마법 WAD(R_THBLT/R_MEDHD/R_RDEAD)에 FXC/PTC 로 들어 있다 (goBolt/goStdBolt/goBoltHit, goEyeBeam/goMedHeadFlash, goDedRed/goDeadHit/goDeadDie).
GO 부착과 애니 구동이 미구현이라 같은 색·수명의 파티클로 대신한다.

| 효과 | 근사 |
|---|---|
| 번개 꼬리 | 노랑→파랑 가산 파티클 |
| 명중 | 폭발 |
| 영혼 | 주황 꼬리 |
| 메두사 광선 | 녹색 원뿔 모드 (반각 ≈ 10°, 15m/초) |
| 석상 | 회색 먼지 |
| 파괴 | 회색 파편 (중력) |

스크린샷 (커밋 안 함): `work/shots/35/medusa_beam.png` (광선, 미노타우로스 석화), `stone_shatter.png` (□ 로 파괴).

## 8. 테스트 (헤드리스, 원본 데이터)
| 테스트 | 검증 |
|---|---|
| `MagicSpellsTest.medusaPetrifiesMinotaurAndShatters` | R2 홀드 → 81→82, 정면 150 유닛 미노타우로스 석화 (저항 6000), 옆 적은 그대로, MP 초당 12.5, 돌 지속 5초, □ 공격 → 파괴 + 콜백 |
| `medusaStoneWearsOff` | 5초 후 해제, 게이지 최대 복귀 |
| `zeusBoltHitsAtDistanceAndCostsMp` | 25m 표적 명중, 피해 = 8 × 발수, MP = 100 − 5 × 발수 |
| `zeusChargedShotUsesCh2` | 단계 2 + □ 홀드 0.8초 → Ch2, 소모 20, 피해 65 |
| `hadesSoulsDamageNearbyEnemies` | 영혼 3, MP 75 소모, 근처 두 적 10 단위 피해, 사거리 밖은 무피해 |
| `MiniGameTest` (6) | 사이클롭스 4 프롬프트 성공 / 무입력 → 242 / 틀린 버튼 즉시 242, 고르곤 180+360 회전 성공 / 무입력 → 261 탈출, 무작위·사분면 |
| `GrabTest` | 새 RepeatPress 규칙으로도 연타 성공 402 / 무입력 실패 403 |

## 9. 남은 것
- 메두사 Nuke(Concussion 원형 Freeze 30000), 시선 충돌 검사, 석상 흔들림, `*stonedbrk` 파편 모델, 영웅 석화(적 메두사 EyeAttack).
- 제우스 차지 연출(boltCharge* 애니 혼합, goBoltChargeUp), 연속 입력 큐 2개, rWeapIH 관절 기준 발사 위치.
- 원작 FXC/PTC 이펙트를 GO 단위로 붙이기, Wiggle Meter (아이콘 12/13 은 있으나 사용처 미확인).
- 마법 선택·섬광·차지 버튼 비트(0x10/0x80)의 실제 패드 대응 확인.

## 9. 아르테미스의 검 (리드, 2026-10-19)
| 항목 | 구현 | 근거 |
|------|------|------|
| 전환 | L1 홀드 + R1 → BERSERKENTER(555), 아르테미스 방어(BERBLOCKPARRY/IDLE) 중 R1 → BERSERKEXIT(556) | CombatFile 분기 데이터 그대로 |
| 장착 시점 | BERSERKENTER 0.22 의 SCRIPT `SCR_EnableBerserkBlade`(0xE9283E7E), 해제 = BERSERKEXIT 의 `SCR_DisableBerserkBlade`(0x33681849) | 실행 파일 문자열의 Hash127Upper 와 이벤트 해시 일치 [확실] |
| 분기 조건 | magic 7 = **보유**(장착 아님) → `CombatOwner.artemisOwned()` 신설. 기존 코드는 "장착" 으로 검사해 전환 자체가 불가능했음 (버그 수정) | 10 문서 §2.3 |
| 장착 중 기술 | 분기 flags 0x2000 (BER* 콤보) 열림 — `PlayerState.artemisEquipped` | 10 문서 §2.4 |
| 피해 | 블레이드 레벨 배율 대신 `/Player/BerserkBlade/Stage n Damage Multiplier` 1.75 / 2.25 / 3.0 | 10 문서 §5.3, TWK |
| 모델 | `R_BRSRK<단계>.WAD` 의 `berserkblade` + `MDL_berserkBlade` 를 `rWeapOH` 에 부착, 장착 중 혼돈의 블레이드 숨김 | 부착 관절 [추정], 스크린샷 `work/artemis.png` 로 손에 쥔 모습 확인 |

`ArtemisBladeTest`: 장착 → BERCOMBOH1 피해 5 × 1.75, 찌르기 4 × 1.75 → L1+R1 해제.

### 검증용 디버그 옵션 (GameScene)
```bash
./gradlew :engine:run -PgowGiveAll "-PgowScript=L1:0.5:0.5,R1:0.75:0.05,SQUARE:2.2:0.05" --args="--game ATHN01A"
```
`gowScript` = 버튼:시작초:누름초 목록 (패드 입력 대신 재생), `gowGiveAll` = 마법 4종 + 아르테미스의 검 + 블레이드 Lv2 지급.
