# 37. 구현: 보스전 (히드라 / 메두사 여왕 / 아레스)

원작 보스는 전용 C++ 클래스가 아니다. **일반 `Enemy1` + 결정 트리(DTre) + CombatFile + 레벨 스크립트(ESC)** 이고,
보스다운 연출(구역별 공격, 내려찍기 → 잡기, 기절 → 처형, 컷신, 레벨 전환)은 거의 전부 **레벨 스크립트가 전투 이벤트를 듣고 지시**한다.
그래서 구현의 핵심은 (1) 레벨 WAD 안의 보스 리소스 구성, (2) 전투 ↔ 스크립트 이벤트 다리, (3) 영웅·보스 동기 기술이다.

```bash
./gradlew :engine:run --args="--game ATHN01E --boss-demo --skip-movies --no-fx"   # 히드라 자동 연출 (아래 갑판 스폰 볼륨부터)
./gradlew :engine:run --args="--game ATHN02D --boss-demo --skip-movies --no-fx"   # 메두사 여왕
./gradlew :engine:run --args="--game ATHN08A --boss-demo --skip-movies"           # 아레스 1단계 → ATHN08B 로 전환
./gradlew :engine:run --args="--game ATHN08C --boss-demo --skip-movies"           # 아레스 3단계 → ATHN07B
# --ticks N : 로직 틱(1/60초) N 번 뒤 스크린샷 (--frames 는 렌더 프레임 수라 레벨마다 시간이 다름)
./gradlew :engine:test --tests 'devple.gow.engine.boss.*'
```
`--boss-demo` 없이 `--game ATHN01E` 로 들어가면 원작처럼 직접 싸운다 (보스 등록·이벤트 다리는 항상 켜짐).

## 1. 데이터 위치 [확실: 데이터]

| 보스 | 레벨 | 스켈레톤 / 모델 / 애니 | AI1 | CombatFileSet → 파일 | DTre | 미니게임 템플릿 |
|---|---|---|---|---|---|---|
| 히드라 (큰 머리) | ATHN01E | `bha` (19 관절) MDL_BHA ANM_bha | 250 HP 400 | 250 → 250/251/252/256 | 250 (구역 1–4) | RepeatPress 250 우 / 251 좌 / 253 처형, TimedPress 251 |
| 아기 히드라 (enc3) | ATHN01E | `bhb` | 253 / 258 HP 250 | 253 → 250/251/255/256 | 253 | – |
| 메두사 여왕 | ATHN02D | `me3` (61) MDL_ME3 ANM_me3 (= ANM_mew 와 같은 크기) | 062 HP 10000, 원 9800 | 062 → 066/067/068 | 062 | TimedPress 060/061 (스틱 반/한 바퀴) |
| 아레스 1단계 | ATHN08A | `bar` (96) | 600 HP 4000, 피해 ×2, 원 3000/2000/1000/500/250/50 | 600 → 600/602/604 | 600 (HP 비율 phase 1–4) | RepeatPress 600, TimedPress 000 "slowPress" |
| 분신·가족 | ATHN08B | `evi`, `family` | 666–669, 660 | 666 / 669 / 660 | 666–669 | – |
| 아레스 3단계 | ATHN08C | `ba2` (95) | 601 HP 1500/3000 | 601 → 601/603/605 | 601 | RepeatPress 601 (영웅 쪽 621) |
| 판도라 수호자 (BMI) | PAND03G | `bmi` | 200 HP 1500 | 200 → 200–205 | 없음 | – |

- 보스 모델·애니·CombatFile 은 **레벨 WAD 안**에 있다 (R_* 적 리소스가 아님). RSRCS 의 `R_AHA/AHB/AHH/AH2/AH3`, `R_A1A..A2C`, `R_AB2..4`
  는 "FakeBHAsnd", "FkSnd Ari A1A" 같은 **소리 뱅크 전용 가짜 적**(모델 없음, SpawnEnemy 254/259/602–607/901)이다.
- CombatFileSet `File n` 값은 덤프에서 float 처럼 보이지만 하위 u16 이 번호 (예 1.0000715 = 0x3F800258 → 600). RepeatPress 의
  `Fail Move`/`#931459a0` 도 하위 u16 (히드라 250: 804/805, 251: 808/809, 253: 816/812, 아레스 600: 607/608, 601: 623/622).
- 영웅 쪽 동기 애니는 레벨 외부 그룹 `ANM_hero_Athn01E`, `_athn08a`, `_athn08c` 에 있다. **메두사 여왕만 예외**: 영웅 `maiMewGrab` 은 ATHN02D 에 없고
  R_MEW 의 `ANM_MAIMEW` 에만 있다 → `MedusaQueenFight.heroAnimWads()` 로 R_MEW 를 붙임 [추정: 원작은 앞 레벨 슬롯 잔류].
- 영웅 CombatFile 006 (800번대, BHA*), 002 (605–623, MAIBAR*) 는 R_PERMK 에 이미 있다. ATHN08A/C 의 000–006 은 R_PERMK 와 같은 기술 목록이고
  007 은 아레스의 BAR* (CombatFileSet 001 "ares hero" — 미사용 더미, SpawnAtStart false).

## 2. 실행 파일에서 확인한 것

| 주소 | 내용 | 신뢰도 |
|---|---|---|
| 0x209610 (Combat_StartBranch) | `sub_1A8C78(0x40B=1035, 기술 ID)` — 기술 시작마다 전역 이벤트, 보낸 쪽 = 전투 owner | 확실 |
| 0x209994 (Combat_EndMove) | 진행률 ≥ 1 이면 `0x417=1047(기술 ID)` | 확실 |
| 0x203848 / 0x204830 | 피해 뒤 `0x40F=1039(피해, 남은 HP)` | 확실 |
| 0x25B5D0 | 메시지 0x41E(1054) → 플레이어 +0x88 = 인자1 ("boss zone") | 확실 |
| 0x1A1B18 / 0x1A1B28 | DTre 조건 26/27 = 0x29DC1C(플레이어 객체, 0x1D6994) +0x88 / +0x64 | 확실 |
| 0x2069AC (이벤트 10 SELF_HP) | 값 ≥ 0 → 자기 피해(0x204690, 피해 경로·사망 분기) / < 0 → HP 더하기(0x1E1758) | 확실 |
| SCR_ 해시 | 0xBA76AD70 ExplodeDeath, 0x9E58F03E SonicScream, 0x74B9D718 FlashSprite, 0xE6A829F7 RegenHealthBar, 0x639A7BC2/0x69A3618C EyeBeamOn/Off, 0x8CACF07D HideBerserkBlade, 0x33681849 DisableBerserkBlade, 0x242EF94F ConcussionBurst, 0x6D5FCB68 Teleport | 확실 (실행 파일 문자열 해시) |
| 0x199000 | 메두사 광선 → Freeze 0x1E1778 (영웅 석화) — 상수 12, 8, 256, 14.375 | 호출만 확인, 판정 세부 미해석 |
| BallHull off[2] / off[7] | ball / hull 별 **영역 ID** (엔티티 원본 0x4A 와 같은 번호): ENV_bossEnt hull 51–54 = 보스 구역 1–4 | 확실 (전 레벨 진입/이탈 센서 580 개 중 574 일치) |

## 3. 엔진 구성 (`engine/.../boss`)

| 클래스 | 역할 |
|---|---|
| `BossDirector` | 레벨 → 보스전 등록표 (`hostsBoss`, `create`). ATHN01E/02D/08A/08B/08C/PAND03G |
| `BossKinds` | 레벨 WAD 의 스켈레톤·MDL·ANM + 지정 CombatFileSet + AI1/DTre/Sold → `EnemyFactory.Kind` |
| `CombatScriptBridge` | 1035/1047(기술), 1039(피해), 1019(EvtForce → 보스 기술 요청), 1054(구역 → 조건 26), 1055/1056(체력 표시) |
| `BossFight` | 공통: 스폰 연결, SELF_HP(바닥 무시 자기 피해 / 회복), ExplodeDeath, SonicScream 밀어내기, FlashSprite 프롬프트, 영웅 SELF_HP, 소리 전용 템플릿, 데모 |
| `BossSync` | 같은 ID 동기 기술 쌍 + RepeatPress(속도 0 이 아니어도) + TimedPress. 정렬 BOSS_TO_HERO(아레스) / HERO_TO_BOSS(히드라) |
| `HydraFight`, `MedusaQueenFight`, `AresFight` | 보스별 보정·단계 이름·데모 |
| `AutoPad` | 미니게임 자동 입력 (데모·검증) |
| `BossArena` | 보스가 보는 게임 쪽 (GameScene / 헤드리스 테스트) |

### 3.1 GameScene 연결 (덧붙임만)
- `loadBoss()` → `BossDirector.create`, `spawn()` 은 보스 종류 우선 · 소리 전용 템플릿은 건너뜀 · `boss.onSpawn` (보스 없는 레벨은 `bridge.attach` 만 — **1035/1047/1039 는 모든 레벨의 모든 적**에 원작처럼 나감)
- `update()` → `boss.update`, 보스 동기 중엔 일반 GrabSync·중력 건너뜀, 리스너 `objectEvent` → 보스/다리.
- 스크립트 `Goto` 를 영웅 이동으로 처리 (히드라 deckBossBegin / afterHydraDeath, 메두사 magicPlace).
- 컷신 끝에 **501(인자0 = 이름, 보낸 쪽 = 플레이어)** 도 보냄 — 기존 7 만으로는 Filter 1 인 `…MpegDone` 센서가 동작하지 않았다.

### 3.2 공용 코드 수정 (원작과 다른 동작 고침)
| 위치 | 내용 |
|---|---|
| `Combatant.start` | "(block)" 분기(대상 -2)는 **이벤트만 소비하고 기술 유지** — 전엔 현재 기술을 끝내 KILLWIN 이 EV_DEATH 로 끊겼다 |
| `Combatant.poll` | 창 끝 실행 AUTO 분기를 **큐에 넣음** (Combat_QueueBranch, 실행 시점 = 창 끝). 전엔 60fps 프레임이 0.98 같은 창 끝을 건너뛰면 영영 실행 안 됨 (MAIBARShowdown → 621) |
| `Combatant.end` | 진행률 ≥ 1 이면 1047 (AUTO 로 넘어가도) |
| `Combatant.forceMove` | 이전 기술의 대기 분기 제거 (불덩이 루프 21 의 AUTO 가 22 위에서 실행되던 문제) |
| `Combatant.onMove/onEvent` | 1035/1047 · 애니 이벤트 통지 (추가) |
| `AiBrain` | 조건 25–27 공급자 `setScriptSense`, `engage()` (스크립트가 깨운 보스는 시야 판정 생략) |
| `EnemyFighter.hpFloor` | 피격 최저 HP (보스 1: 피격으로는 죽지 않고 원작 EV_DEATH 분기로 기절) |
| `ScriptWorld` / `BallHull` / `LevelData.PlacedVolume` | **영역 ID 있는 진입/이탈 센서** — 같은 ENV 의 hull 마다 다른 센서. 전엔 히드라 구역 1–6 센서가 한꺼번에 발동했다 |
| `GameScene` | 소리 전용 R_* (R_AHA 등) 은 사운드 뱅크만 로드 |
| `Engine/GowMain` | `--ticks`, `--boss-demo`, `--skip-movies`, `--no-fx` |

## 4. 히드라 (ATHN01E) — 완료
```
진입 볼륨 → a01Mp03 → 501 → Goto deckBossBegin, 아기 히드라 258/253 스폰 (enc3)
아래 갑판 spawnDaddyHydra 볼륨 → 전역 301 → SpawnEnemy 250 (그룹 250, 바다 (-3163, 489, -115))
구역 볼륨(ENV_bossEnt 영역 51–54) → LevelData28 → PlayerZoneTransmit 1054 → 플레이어 +0x88
  DTre_250: 높이 차 < 40 → "down mast"(공격 없음) / 구역 1 포효 55·도발 56, 2 → 52 오른쪽 물기 85%, 3 → 51 왼쪽, 4 → 62 가운데
피격 1039 → bossEnt-healthSensor(LD34 = HP) → HP < 250 & 미니게임 아님 → EvtForce 53(HP 짝)/54(홀) 내려찍기 (+ ○ 사운드)
  내려찍기 0.35– 자세 806 (좌우 모두) → 영웅 ○ GRAB(자세 200) 적중 → 보스 EV_HIT_REACT 태그 200 → 806/802, 영웅 HIT_CONFIRM 태그 806
  → BossSync: 같은 ID → 807/803 연타 RepeatPress 251/250 (10초, ○, F 0.24, 감소 0.025, 간격 0.5초) → 809/805 성공 → 포효
  → 1035 805/809 → mastEnt: 돛대 단계 LevelData39 +1 / 실패 808/804 (영웅 SELF_HP 10)
돛대 3단계 + HP < 150 → EvtForce 123 STUN (SELF_HP −50 회복) → 120 STUNLOOP (자세 810, FlashSprite ○, RegenHealthBar 252)
  영웅 ○ = A800 COMBO5A(대상 250 자세 810) 적중 → 810 BHABOSSKILL 동기: TimedPress 251 [0.39–0.47] ○ (실패 → 813 → 816)
  → 811 연타 RepeatPress 253 (15초) → 812 KILLWIN (SELF_HP 1500 = 사망, EV_DEATH 는 (block))
812 완료 1047 → trigHydras-EventHydraDie: 음악 정지, GlobalData2/LevelData16, 컷신 a01Mp01 → 501 → Goto afterHydraDeath
```
- 엔진 보정: HP 바닥 1, 머리 위치 고정(목이 바다에 붙음, AI 이동 의도 버림) [근사], 시야 판정 생략.
- **동기 정렬 HERO_TO_BOSS [추정]**: 영웅 동기 애니의 zeroJoint = 보스 원점 기준 영웅 위치이고, 보스 정면과 마주 보는 축(180°)이다.
  헤드리스 측정: 영웅 위치가 잡기 직전 서 있던 단상(y 1146) 25 단위 안, 머리 관절과 ~260 단위 (0° 로 두면 730 단위, 바다 위).
- 테스트: 스폰·구역 2 → 52 물기, 데이터 피해 → 1039, HP < 250 → 스크립트 내려찍기, 잡기 3회(연타 성공) → 돛대 단계 1→3,
  HP < 150 → 기절 → ○ 처형 → TimedPress → 연타 → KILLWIN → 컷신 a01Mp01 → Goto afterHydraDeath, 실패 시 영웅 피해, 피격으로 안 죽음.

## 5. 메두사 여왕 (ATHN02D) — 완료 (석화는 근사)
```
MAmagicIcon-EntVol01 → MEWINTRO 컷신 + SpawnEnemy 62 (그룹 26) → mewintroDone → Goto magicPlace
DTre_062: 대상 정지 → 2 꼬리치기 / HP 비율 > 0.975 → 0–3 + 20 시선(대기 3초) / 아니면 30 회복 (SELF_HP −50)
20 MEWBEAMON 0.63– SCR_EyeBeamOn(2) … 다른 기술의 SCR_EyeBeamOff → 영웅 석화
HP ≤ 9800 → 잡기 원 → ○ GRAB → 260 MAIMEWGRAB (일반 GrabSync, 같은 이름 MAI 애니)
  TimedPress 060 [0.25–0.45] / 061 [0.46–0.75] 스틱 회전 (메시지 520 "왼쪽 스틱…"), 실패 → 261 MAIMEWESCAPE
  0.90 SELF_HP 200 + SCR_ExplodeDeath → 사망 (머리 뜯기)
1035(260) → MedusaRipZone-EventCombat1 (5.33초 뒤) → LevelData0, 메시지 141 "메두사의 시선을 획득했습니다", 미노타우르스 3 스폰 (석화·파괴 퍼즐)
```
- 영웅 석화 [근사]: 광선 원뿔 ±20°·600 단위 안 0.5초 → 영웅 FREEZEPOSE(120), 스틱 흔들기 누적 6 또는 4초 → FROZENBREAKOUT(122).
  원작 0x199000 의 광선 판정·석화 중 피격 사망은 미구현.
- 테스트: 로드(HP 10000, 원 9800), 시선 → 석화 → 흔들기 탈출, HP 9700 → 회복 기술 30 → 잡기 → 스틱 회전 2회 성공 → 사망 → LevelData0.

## 6. 아레스 (ATHN08A 1단계 / ATHN08C 3단계) — 결말까지 동작, 연출 일부 미구현
```
1단계: Ares1 SpawnAtStart 600 (그룹 600). DTre_600 HP 비율로 phase 1..4 (테스트로 확인)
  잡기 원 임계 3000/2000/1000/500/250/50 통과 → EV_STUN → 290 비틀거림 (원 켬, 태그 200 잡기 분기)
  HP ≤ 500: 94/95 도발 (95 SELF_HP −15×18 회복). ○ GRAB → 605 MAIBARGRABSTART 동기 → 606 연타 RepeatPress 600
  → 608 MAIBARGRABWIN: TimedPress 000 ×2 (무작위 버튼, 실패 → 609), SELF_HP 100 + 4000 → 사망, 끝나면 1047
  → a07Mp01movie: 컷신 a07Mp01 → LoadForWarp athn08b → Warp  (게임에서 ATHN08B 로 전환 확인)
3단계: Ares3 601. 115 BLOCKPARRY 적중 → 620 MAIBARSHOWDOWN 동기 → 621 연타 (영웅 기술의 RepeatPress 601)
  → 622 SDWIN (SELF_HP 50+50+400) → HP 0 → 390 → 395 → 1035(395) → Semo10 → Warp athn07b (게임에서 ATHN07B 전환 확인)
```
- 엔진 보정: HP 바닥 1, 다중 원 임계(EnemyFighter 는 첫 임계만 앎), 불덩이 모으기 21 루프 → CHARGE_CHECK(이벤트 33, a=4.5초) 뒤 22 던지기
  (Combatant 가 0x800 차지 분기를 아직 평가하지 않음) [근사], 동기 정렬 BOSS_TO_HERO (GrabSync 와 같은 규칙).
- 테스트: 1단계 로드·phase 1–4·2000 임계 비틀거림·잡기 → 608 → 컷신 → LoadForWarp/Warp, 3단계 칼 맞대기 → 622 → 395 → Semo10.

## 7. 스크린샷 (커밋 안 함, `work/shots/37/`)
| 파일 | 장면 |
|---|---|
| `hydra_slam.png` | 히드라 내려찍기 (갑판 위 크레토스, 오른쪽 큰 머리) |
| `hydra_grab.png` | 잡기 연타 (돛대 위 레벨 카메라) |
| `hydra_kill.png` | 처형 중 히드라 목 |
| `medusa_gaze.png` | 메두사 여왕 시선 → 크레토스 석화 |
| `medusa_rip.png` | MAIMEWGRAB 머리 뜯기 동기 |
| `ares1_fight.png` / `ares1_grab.png` | 아레스 1단계 전투 / MAIBARGRAB 동기 |
| `ares3_showdown.png` | 3단계 칼 맞대기 |

모델이 뒤집혀 보이는 면은 main 의 AnimatedModel 렌더 수정(GS 0x44 CUTOUT)으로 병합 시 해결된다 (이 브랜치에서 고치지 않음).

## 8. 남은 것 / 신뢰도
| 항목 | 상태 |
|---|---|
| 보스 체력 표시 (스크립트 1055 HealthMeterOn) | 다리에 대상만 기록, HUD 미표시 |
| 미니게임 버튼 아이콘 (FlashSprite idx 10, TimedPress 아이콘) | 미표시 (35 문서 공백과 같음) |
| 스크립트 카메라 (grabLeftCam, bossKillCam 등 Vis/Animator 로 켜는 카메라), AresCamera1 | 레벨 CMV 선택만 — 동기 장면에서 크레토스가 가려질 수 있음 |
| RegenHealthBar (RegenBar 250/252: Regen Speed 150, 비율 0.8/0.45) | 의미 미확정 → 무시 |
| SonicScream 힘 | 근사 (거리 ×16 안에서 Force/500 단위/초) |
| 히드라 HERO_TO_BOSS 정렬, 메두사 석화, 아레스 차지 | [추정]/[근사] |
| 아기 히드라 enc3 (`bhb` 253/258) | 리소스·DTre 로 싸움, 돛대 핀(110–117) 연출 미검증 |
| ATHN08B 분신 크레토스·가족 (EVIGRAB 860 등), 판도라 수호자 BMI (`/GlobGame/BMI` 판정, BMIRIDEMOUNT 450) | 리소스만 등록 (일반 AI), 전용 연출 미구현 |
| ATHN02D 메두사 방 바닥 충돌이 없음 (충돌 삼각형 138) | 레벨 쪽 문제 — 잡기 뒤 영웅이 떨어짐 |
| 0x800 차지 분기 일반 지원, 조건 25(스크립트 변수 해시), 27(+0x64 쓰는 곳) | 미구현 |

## 보스 체력 미터 (HUD)
- 미터는 FLP_HUDK 가 아니라 **레벨 WAD 의 FLP_&lt;레벨&gt;** (ATHN01B/C/E, ATHN08A/B/C, PAND03G) 에 있다. `GameScene` 이 이 FLP 를 HUD 와 변수를 공유하는
  오버레이(`levelHud`, 입력 없음)로 띄운다.
- 스크립트 `*-HealthMeterOn/Off` EventTransmitter (EventId 1055/1056, Filter = 대상 그룹) → `CombatScriptBridge` 가 대상 적에 슬롯 1..3 배정.
- `EnemyMeterBinding`: `PS2_EnemyMeter_Event{n}` 1 켬 / 0 끔 / 2 갱신, `PS2_EnemyMeter_Value{n}` = HP/최대 × 99 → `HydraMeterMain` 프레임 2 + 값
  (102 프레임 클립에서 녹색 막대 길이가 프레임마다 늘어남) [추정: 백분율 환산], `PS2_EnemyState_{Event,Value}{n}` = 잡기 가능 시 2(BarFlash) [추정].
- 검증 `BossMeterTest`: 아기 히드라 2마리 → 슬롯 1·2, HP 50% → 프레임 50 (= 2 + 49 − 1), 사망 → Off. 화면 `--ui gow-rom/ATHN01E.WAD FLP_Athn01E meter`
  (`work/uirt/meter.png`, 원작 히드라 미터 60%).
