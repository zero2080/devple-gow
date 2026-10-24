# 37. 구현: 보스전 (히드라 / 메두사 여왕 / 아레스)

원작 보스는 거의 전용 C++ 클래스가 아니다 (예외: 아레스는 Enemy1 하위 클래스 2 — 매 프레임 AresCamera1 요청, 30 문서 §6.3; 판도라 수호자 BMI 는 하위 클래스 1 — 기술 선택·피해 처리 전부 클래스 코드, §10). **일반 `Enemy1` + 결정 트리(DTre) + CombatFile + 레벨 스크립트(ESC)** 이고,
보스다운 연출(구역별 공격, 내려찍기 → 잡기, 기절 → 처형, 컷신, 레벨 전환)은 거의 전부 **레벨 스크립트가 전투 이벤트를 듣고 지시**한다.
그래서 구현의 핵심은 (1) 레벨 WAD 안의 보스 리소스 구성, (2) 전투 ↔ 스크립트 이벤트 다리, (3) 영웅·보스 동기 기술이다.

```bash
./gradlew :engine:run --args="--game ATHN01E --boss-demo --skip-movies --no-fx"   # 히드라 자동 연출 (아래 갑판 스폰 볼륨부터)
./gradlew :engine:run --args="--game ATHN02D --boss-demo --skip-movies --no-fx"   # 메두사 여왕
./gradlew :engine:run --args="--game ATHN08A --boss-demo --skip-movies"           # 아레스 1단계 → ATHN08B 로 전환
./gradlew :engine:run --args="--game ATHN08C --boss-demo --skip-movies"           # 아레스 3단계 → ATHN07B
./gradlew :engine:run --args="--game ATHN08B --boss-demo --skip-movies --no-fx"   # 분신 웨이브 + 가족 포옹
./gradlew :engine:run --args="--game PAND03G --boss-demo --skip-movies --no-fx"   # 판도라 수호자: 배출구 → 올라타기 → 통나무 … → 죽음 (약 110초)
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
| 분신·가족 | ATHN08B | `evi`, **`xmc`** (가족, `family` 스켈레톤은 정지 모델) | 666–669, 660 | 666 / 669 / 660 | 666–669, 가족 001(빈 트리) | – |
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
| `BossDirector` | 레벨 → 보스전 등록표 (`hostsBoss`, `create`). ATHN01E/02D/08A/08B/08C/PAND03G (`BmiFight`) |
| `BossKinds` | 레벨 WAD 의 스켈레톤·MDL·ANM + 지정 CombatFileSet + AI1/DTre/Sold → `EnemyFactory.Kind` |
| `CombatScriptBridge` | 1035/1047(기술), 1039(피해), 1019(EvtForce → 보스 기술 요청), 1054(구역 → 조건 26), 1055/1056(체력 표시) |
| `BossFight` | 공통: 스폰 연결, SELF_HP(바닥 무시 자기 피해 / 회복), ExplodeDeath, SonicScream 밀어내기, FlashSprite 프롬프트, 영웅 SELF_HP, 소리 전용 템플릿, 데모 |
| `BossSync` | 같은 ID 동기 기술 쌍 + RepeatPress(속도 0 이 아니어도) + TimedPress (보스 기술에 없으면 영웅 기술의 것 — BMI 451–453). 정렬 (§11, §11.6): 어느 쪽이든 이벤트 22 **또는 19** 창 안이면 원작 0x204BF0 (`combat.SyncPull`, 그 쪽이 상대를 끈다: 히드라 802–816, 아레스 600·605–609·621–623, 영웅 620 → 아레스, 가족 포옹, 분신 잡기, BMI 올라타기) [확실]. 창 밖은 두 쪽 루트 모션만. 예전 대체 규칙 (SYNCH / BOSS_TO_HERO / ZERO / HERO_TO_BOSS / TARGET_SYNCH) 은 모두 폐기 |
| `HydraFight`, `MedusaQueenFight`, `AresFight`, `EvilKratosFight` | 보스별 보정·단계 이름·데모 |
| `BmiFight` | 판도라 수호자 전용 클래스 재현 (Think 0x1C0A48 / TakeDamage 0x1C1838, §10) |
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
| `EnemyFighter.damageFrom` | 피해를 주는 공격자 거르기 (ATHN08B 가족: 영웅 공격은 무시, 잡기 자세 200 만 통과) |
| `EnemyFighter.damageHook` / `CombatOwner.killedBy` | 클래스가 피해를 직접 처리 (vtable +0x40 TakeDamage 재정의, BMI) — HP·잡기 원·EV_DEATH 판정을 건너뜀 |
| `Combatant` 흔들기 게이지 | 플레이어 +0x50 (sub_001D8B98): 두 스틱 |Δ|·0.2·(1+0.6·(1−|스틱|)) 최댓값 누적 − "Wiggle Meter Drain"(2.0)·dt, 기술 시작 시 0. 방향 조건 7/8/15 = 게이지 > 2/4/15 (InputOK 0x2082BC) — 예전 "낙하 높이 [추정]" 은 틀림 [확실] |
| `Combatant.poll` 즉시 분기 | 창 끝을 기다리는 AUTO 가 큐에 있어도 나중에 조건이 맞은 즉시(!) 분기가 앞선다 (861 "AUTO fall>2 → 862") [추정: 원작 큐 교체 규칙] |
| `Combatant.setGodMeterOwner` | 적 기술의 GOD_METER_ADD 를 크레토스 게이지로 (게이지는 플레이어 전역; 가족 포옹 865/866 의 +1 ×N). `BossFight.onSpawn` 이 보스전 적 전부에 연결 |
| `Combatant.fireFrom` | 투사체(Arrow Context = 자세 태그) 피격 분기 (BMI 통나무 456) |
| `GrabSync.updateTimed(Combatant…)` | TimedPress 를 가진 쪽이 영웅 기술일 때 (실패 = 영웅 REQUEST0 분기) |
| `GameScene` 몸 목록 · `BmiFight.bind` | **보스 밀기** (32 문서 §생물끼리 밀어내기 — 보스) [확실: 범주·비트]: 보스도 Enemy1 (+0xF4 범주 1\|2) 이라 단단한 기술이면 영웅을 민다. 자기 밀기는 Sold +0x1C 비트 1 (히드라 250–253·악령 크라토스 660 = 끔) 과 BMI Think (매 프레임 +0x2D8 비트 31 = 끔) — 아레스·메두사 여왕·분신은 자기도 밀림. 예전: 보스는 밀기 목록에 없었다. `HeadlessBoss` 하네스는 밀기 없음 |
| `CombatScriptBridge` 1039 → 적 | 스크립트가 적에게 보낸 1039 = 피해 인자1 (0x25B6E8 → 0x1CC810 → 0x204690) [확실] — PAND03G LogDamageBoss 999 |
| `LevelData.behaviors` | GO 하나에 비헤이비어가 여럿이면 Creator 대신 다음 것 (PAND03G gologlever = Creator 100 + PushPull 15 "BMI Log Puller" 과제 351 → 레버 R1 오브젝트) |
| `Combatant.setBystanders` | 대상 외에 같은 공격 판정을 받는 개체 (분신 공격이 가족에게 휘말림 — 피격자 처리만, 공격자 HIT_CONFIRM 없음) |
| `HeroNavigator.deepWater` | Water 면 아래 16+4 안에 단단한 바닥이 있으면 헤엄치지 않음 (ATHN08A/08C 아레스 만: 수면 y 1, 바다 바닥 −6.7 = "bay floor" −0.4125·16) — 전엔 크레토스가 바닥 아래 y −15 에 헤엄 상태로 갇혔다 [추정: 깊이 기준] |
| `ScriptWorld` / `BallHull` / `LevelData.PlacedVolume` | **영역 ID 있는 진입/이탈 센서** — 같은 ENV 의 hull 마다 다른 센서. 전엔 히드라 구역 1–6 센서가 한꺼번에 발동했다 |
| `GameScene` | 소리 전용 R_* (R_AHA 등) 은 사운드 뱅크만 로드 |
| `Engine/GowMain` | `--ticks`, `--boss-demo`, `--skip-movies`, `--no-fx` |
| `EnemyFighter.resetSpawnCount` | 적 난수 씨앗 = 정적 생성 순번이라 헤드리스 보스 테스트 결과가 앞선 테스트 수에 좌우됐다 → `BossRig` 가 매번 0 으로 (HydraFightTest 구역 2 대기 6 → 15초: 씨앗 0 은 포효 55 를 먼저 끝냄) |
| `EnemyFighter.modelVariant` / `AnimatedModel.setVariant` / `SkinnedModel(…, variant)` | 클래스 코드가 정하는 모델 그룹 (BMI +0x120, §10.1) |
| `BossArena.effects/collision/cameraTarget`, `BossFight.hud`, `CameraRig.setTargetSlot`, `EffectSystem.Effect.hold` | BMI 이펙트·투사체·HUD·카메라 대상 (§10.1) — GameScene 은 연결만 |

## 4. 히드라 (ATHN01E) — 완료
```
진입 볼륨 → a01Mp03 → 501 → Goto deckBossBegin, 아기 히드라 258/253 스폰 (enc3, §4.4)
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
- 엔진 보정: HP 바닥 1, 몸통 위치·**방향** 스폰 값 고정(목이 바다에 붙음, AI 이동·회전 의도 버림) [추정], 시야 판정 생략.
  방향 고정 근거: 구역별 공격이 애니(51/52/62)로 나뉘고, 잡기·처형 카메라 오브젝트가 월드 고정이라 동기 장면도 스폰 방향 기준 (30 문서 §6.4).
  GameScene 이 보스 스폰 방향을 엔티티 행렬에서 읽도록 고침 — 전엔 0 이라 크레토스가 망대 밖 허공(−3120, 1170, −563)에 정렬됐다.
- 잡기·처형 카메라: 1035(806/802/807/803/809/805/810/812 …) → ESC BossLeftFight/BossRightFight/BossFInalGame 의 SwitchCamera + 질의 끔, 관절 애니 카메라 (30 문서 §6).
- ~~**동기 정렬 HERO_TO_BOSS [추정]**~~ (→ §4.2 원작 해독으로 대체): 영웅 동기 애니의 zeroJoint = 보스 원점 기준 영웅 위치에서 **z 만 뒤집은** 점, 영웅은 보스를 마주 봄 (요 + π).
  근거 [데이터]: 보스 물기 애니 synchJoint(물기가 떨어지는 단상 지점)와 영웅 zeroJoint 의 대응 — BiteMiddle (1, 679, −382) ↔ 영웅 BHAbossKill (1, 682, 383),
  BiteLeft (−74, 679, −449) ↔ GrabLeft (−40, 682, 432), BiteRight (69, 679, −451) ↔ GrabRight (52, 682, 436). 이전 180° 회전은 x(좌우)가 반대라
  grabLeftCam 이 머리 쪽으로 돌면 크레토스가 카메라 축에서 45–57° (화면 밖)였다 → 뒤집기 후 30–40°. 그래도 1.2–2초 구간엔 화면 가장자리 밖이다 (§8).
- ~~잡기 중 크레토스가 화면 밖 (1.2–2초) — 정렬 문제가 아니다~~ → **정렬 문제였다** (§4.2: 보스가 영웅을 synchJoint 로 붙이는 원작 0x204BF0).
- 테스트: 스폰·구역 2 → 52 물기, 데이터 피해 → 1039, HP < 250 → 스크립트 내려찍기, 잡기 3회(연타 성공) → 돛대 단계 1→3,
  HP < 150 → 기절 → ○ 처형 → TimedPress → 연타 → KILLWIN → 컷신 a01Mp01 → Goto afterHydraDeath, 실패 시 영웅 피해, 피격으로 안 죽음.

### 4.1 히드라 잡기 정렬 재조사 (806 BHAbossGrabLeft, 헤드리스 실측) — §4.2 로 대체
> 아래는 HERO_TO_BOSS (z 뒤집기) 기준 실측이다. 원작은 손–턱 접촉이 아니라 보스가 영웅을 synchJoint 로 붙이고 (§4.2) 영웅은 사슬로 머리를 당긴다.

보스 (−3163, 489, −115) 요 −π/2, 영웅 HERO_TO_BOSS → (−2731, 1170, −155). 0.17초 간격 실측 (월드):

| t | JAW (BHA01JOCJAW1) | 영웅 손목 (lwrist/rwrist) | 차이 |
|---|---|---|---|
| 0.17 | (−2734, 1203, −180) | (−2722, 1189, −54…−64) | 옆 120 (손이 아직 올라오는 중) |
| 0.50 | (−2731, 1245, −151) | (−2689…−2720, 1191, −130) | 옆 20, 위 55 |
| 0.67–1.00 | (−2726…−2719, 1267…1297, −155…−175) | (−2699…−2726, 1186…1192, −157…−177) | **옆 ≤ 10, 위 77–105** (턱 관절 아래 면을 받침) |
| 1.33–2.03 | (−2707…−2731, 1320…1357, −241…−316) | (−2721…−2770, 1184…1197, −147…−178) | 옆 90–160 (머리가 비틀며 옆으로 당김) |

- 0–1초는 현재 정렬(z 뒤집기)로 손과 턱이 옆으로 10 안에 맞는다 → 정렬 규칙은 맞다. 1.2초부터 **히드라 애니(BHAbossGrabLeft) 자체가 머리를 옆으로 당기고,
  영웅 애니의 zeroJoint 는 (−40, 682, 432) 그대로**다 (영웅도 손목만 40 정도 바다 쪽으로). 원작 데이터도 이 구간엔 손이 턱에서 떨어진다 → 보스 쪽을
  다른 관절로 끌거나 영웅을 옮길 근거가 없다 (히드라는 Enemy1 일반 클래스, 레벨 스크립트 Animator 중 히드라·돛대 동기용은 없음: mast-main-mastBreak*/Shake* 는 돛대 오브젝트 애니).
- 화면 밖 원인은 카메라: grabLeftCam 관절 애니가 머리 쪽으로 패닝 (30 §6.2). 원작 화면 대조 전에는 고치지 않음 [미확인].

### 4.2 원작 동기 정렬 0x204BF0 — 보스가 영웅을 붙인다 [확실: 코드]
- CombatFile 이벤트 22 (덤프 이름 `FLAG_24`, 처리 0x206BA0): 자기 combatant +0x1AC |= 0x24, 락온 대상의 +0x1AC |= 0x4000.
  매 프레임 0x205968 → 0x2441E0(+0x1AC, 0x24) 이 참이면 **0x204BF0**: 락온 대상 (0x207B38) 오브젝트를
  `목표 = inv(S_대상) · S_자기 · W_자기` (행벡터) 로 옮긴다. S = 모델 관절 행렬 [synchJoint] 인데, 루트 모션 컴포넌트 0x1F7110 이 매 프레임
  [zeroJoint] = 단위, [synchJoint] = S·Z⁻¹ 로 덮어쓰므로 **zeroJoint 기준 synchJoint** 다 (위치 × 오브젝트 배율 +0x168 × 내비 +0xEC).
  회전은 쿼터니언 slerp (0x1840D8/0x184388), 위치는 lerp, 비율 t = 대상 combatant +0x1BC: 기술 플래그 0x20 기술 시작에 0.1 (0x209830, 자기·대상 둘 다),
  프레임마다 ×1.1, 최대 1 (0x205098).
- 이벤트 22 를 가진 기술: **히드라 CombatFile 252 의 802 / 806 / 810** (BHABOSSGRABRIGHT/LEFT, BHABOSSKILL — 보스 쪽). 807 연타·808/809 결과·811/812 에는 없다
  → 그 구간은 정렬 없이 영웅 루트 모션만 (0x1F7110 델타: 기술 첫 프레임은 델타 없음). 영웅 파일 002 에도 2 개 (MAISIR* 등, 영웅이 적을 붙임).
- 결과 (806, 보스 (−3163, 489, −115) 요 −π/2): 영웅 = (−2699, 1170, −75) — 높이 = 보스 + 영웅 zeroJoint 682 = **돛대 단상 바닥 1168** 과 맞는다.
  영웅은 단상에서 블레이드 사슬을 히드라 입에 박고 당긴다 (영웅 806 SND_SWORD_IMPALE). 처형 810 은 크레토스가 머리 위로 올라탄다.
  이전 HERO_TO_BOSS (zeroJoint z 뒤집기, 손–턱 접촉 가정) 는 사슬 연출을 손 접촉으로 오해한 것 — 화면 밖 문제의 원인.
- 엔진: `combat.SyncPull` (원래 `BossSync.Align.TARGET_SYNCH`, §11 에서 일반화 — 창 [start, end] 검사·영웅 → 적 방향 포함, 섞기 비율은 동기 연결 시작에 0.1).
  동기 끝에 원래 자리로 되돌리지 않는다 (원작처럼 대상 오브젝트 자체가 옮겨짐; 이후 내비게이션이 단상/갑판 바닥으로). 다른 적·보스의 이벤트 22 는 §11 전수 조사.
- "락온 대상" 0x207B38 은 정확히는 **동기 상대** (+0x84 핸들, 서로 연결된 경우만): 기술 플래그 +0x0E 0x20 (보스·적 쪽 동기 기술, 예 0x130/0x131) 이 시작할 때
  +0x84 가 비어 있으면 공격 대상 +0x80 을 상대로 연결하고 상대의 +0x84 도 자기로 (0x209488 → 0x2097A8), 0x40 (영웅 쪽, 0x140/0x150) 은 연결만 받는다. 플래그 0x60 이 없는 기술이 시작되면 +0x84 = 0 (0x2050D8).
- 스크린샷 (`/Users/obeomsu/.claude/jobs/6f65672f/tmp/cam3/`, 커밋 안 함, `--boss-demo --no-fx`): `hy_cur_*.png` (이전: 650–800 틱 크레토스 없음),
  `hy_new_700/725/775/800/850.png` (806/807: 단상 위 크레토스 + 머리에 박힌 사슬), `hy_r_1400.png` (802 사슬), `hy_k_2900.png` (810 머리 위 크레토스), `hy_k_3600.png`.
- 시험: `HydraFightTest.damageScriptSlamGrabAndFinisher` — 잡기 0.6 초 뒤 영웅 y = 보스 y + 682 (±3).

### 4.3 선창 히드라 (ATHN01B, AI1 251 "hydra enc1" → `bhh`)
```
hydraEnt3b-EntrySensor1 (LD12 거짓) → SpawnEnemy 251, SpawnAction 10 BHAENC1BURST (월드 (1525, −259, 91), 요 π = 바닥 끝 +Z 쪽)
DTre_251: 0..30 (×16 = 480) 물기 15/16(→17 BITE2X), 50..175 도발 18/19 — 뿌리는 물에 잠긴 noHero 바닥 (bossEnt01B_noHeroMat, y −268, z 0..210)
피격 1039 → healthSensor (LD6 = HP) / lowHealthSensor HP ≤ 200 → stunTransmit 1019 100 (BHAENC1STUN → 101 루프, ○ 아이콘 + 메시지 522)
  → StunBegin(1035 100) LD7 → 2 초 뒤 1019 107 (STUNLOOP), 8 초 뒤 1019 108 (GETUP) / 101·107 중 영웅 GRAB(태그 200) → 830 BHAENC1GRAB
  (TimedPress f980ef69 네 창, 실패 831–836 → miniFailSensor) / EV_DEATH → 150 BHAENC1GRAB @0.83 → playerBlock-DetectDeath (LD11)
```
- **1019 EvtForce = 모든 Enemy1** [확실]: 0x25B6E8 의 0x3FB 가지 (0x25B8F8) → 0x1D3530(ai, 인자1, 인자2): 인자2 ≠ 0 이면 0x209918 로 기술을 끊고
  0x209378 기술 요청, ai +0x40 = 기술, +0x72 |= 0x400. 예전 엔진은 보스만 받아 선창 히드라가 기절하지 않았다. 엔진 `CombatScriptBridge`:
  보스가 아닌 적은 인자1 ≠ 0 이고 전투 파일에 있는 기술만 (순찰 변경 PatrolChange 0 은 거름) [근사].
- **체력 미터** [확실]: 생물 초기화 0x1CEF10 — AI1 +0x84 & 0x40 (fc037268) 이면 owner +0x48 |= 4 (1055 0x25B720 과 같은 비트, 1056 이 지움).
  매 프레임 0x1D0F54: 비트 4 & 영웅 HP > 0 & HUD +0x15AC = 0 이면 슬롯 n = **AI1 +0x9B (f3a51122) + 1** 의 HUD +0x1584[n] / Event{n} / Value{n} = HP/최대 × 100 + 0.99999.
  소멸 0x1CB6A0 이 끈다. 0x40 템플릿: 251·252 (히드라), 600/601 (아레스), 660 (분신), 200 (BMI). 엔진: 생성 때 미터 켬 + 1055 슬롯도 +0x9B + 1
  (예전: 1055 순서 1, 2, 3 → ATHN01E 아기 히드라 258/253 은 이제 2/3, `BossMeterTest`).
  FLP_Athn01B/01C SimKeyEvent 는 **PS2_WadName = "Athn01B"** 일 때만 미터 1 을, 아니면 미터 2 를 갱신한다 — HUD 변수 "WadName" (0x1B8668 → 0x1B8704,
  불러온 레벨 이름) 을 엔진이 안 채워 미터가 안 떴다. `GameScene.loadLevelHud` 가 FLP 이름 뒤 (Athn01B) 로 채운다 [추정: 대소문자].
- **대기 클립**: 이름 규칙 (standidle/idle) 이 실패해 첫 클립 "main" (골격 없음 = 바인드 자세, 목이 위로 곧게 선 기둥) 이었다 →
  `EnemyFactory` 가 Navigation ANIStand ("BHAEnc1Idle" → ANM BHAenc1Idle, 대소문자 무시) 로 [확실: TWK].
- **매달린 애니 = 위에서 내려오는 목** (2026-10-24 정정, 05 §2.8.1) [확실: 데이터 + PS2 영상 272.5–342 초]: BHAenc1* 21 개는 골반 BHA01JOCPEL1 회전이 X 축 180° (목이 골반에서 아래로)
  이고, **타입 0 디스크립터 1 (관절 이동) 의 절대 키가 골반을 뿌리 위 (77, 842.7, −106.7) 에 둔다** (Burst 첫 프레임은 y 2021 에서 842.7 로 내려옴). 배율 0.3 이면 골반 = 뿌리 위 253 (선창 바닥 위 ≈ 150,
  선체 위쪽), 목이 거기서 내려와 대기 턱 끝 = 뿌리 + (126 위 · 앞 300), 머리는 바로 선다 (두 눈 JOLEYE11/JOREYE11 이 턱 끝 위 — 잡기 830 · 실패 831–836 의 씨름 구간만 비틀림).
  PS2 영상 (272.5 등장: 머리가 오른쪽 위 천장 가까이, 목은 오른쪽 위 선체에서 내려옴 · 273–316 위에서 내리찍는 물기 · 328 기절에 턱이 바닥 · 338–342 마무리) 과 같다.
  예전 항목 번호 배치는 이 세트 ([0(p2 3)]) 의 이동을 읽지 않아 골반이 뿌리 (물속 −268) 에 있고 머리가 바닥 아래 −385 에서 놀았다 → 엔진이 골반 앞 회전으로 대신 세웠다
  (51d51bc 모델 Z 180° → 머리 등이 아래, 그 뒤 X 축 50.7° `EnemyFighter.hangLift`·`Animator.setRollFix` — 목이 바닥 끝 아래 물에서 솟는 모양, 영상과 다름). 50.7° 회전의 턱 끝 y +421 은
  바로 골반 이동 842.7 − 목 421.3 이었다. 둘 다 없앴다. 830 BHAenc1Grab 동안 영웅 손목 ↔ 히드라 머리 (머리 관절·두 눈·턱 끝 중 가까운 것) 평균 거리: 새 배치 64.5, X 50.7° 108, 무보정 259.
  영웅 동기 관계는 그대로 (BHAenc1Grab zero/synch 는 y 0 — 영웅 = 뿌리 + 115 = **−153 선창 바닥**).
- **제자리** [추정]: 걷기 클립이 없다 (Navigation ANIWalk "bwalk" 가 ANM 에 없음 → "main"). 예전엔 모드 1 이 거리 > 64 면 Sold 251 속도 (5 × 16) 로
  영웅 쪽 이동 → 공격 사이마다 뿌리가 z 91 → 295 로 밀렸다. 엔진: 히드라 리그 + 걷기 클립 골격 없음 → `EnemyFighter.stationary` (스틱·목표 버림, 회전만).
  원작 지상 이동 0x1EAEC8 은 속도 기반이라 원작 근거는 위 동기 배치뿐 (뿌리가 스폰 자리여야 영웅이 바닥 끝).
- **CDV 구에 개체 배율** [확실: 0x13A998 관절 행렬 × 로컬 중심, 반지름 × 관절 배율 — 원작 관절 행렬엔 +0x168 배율이 들어 있다]: 엔진 `HitVolumes.update` 가
  배율을 빼먹어 0.3 배 히드라의 구가 3.3 배 먼 곳 (천장 위) 에 있었다 → `CombatOwner.modelScale` (적 = ScaleCenter). 대부분 적은 ≈ 1 이라 영향 적음.
- 자동 플레이: 제자리 적은 영웅에 가장 가까운 CDV 구 (머리) 로 다가간다 (`GameScene.aimPoint`, 엔진 전용) — 예전엔 뿌리까지 355 에서 포기.
  대상의 지금 기술에 영웅 GRAB 태그 200 피격 분기가 있으면 (기절 101/107 → 830) 묶음마다 ○ 부터 누른다 (`AutoPlay.grabTarget`, 엔진 전용).
- **○ 마무리가 안 잡힘** (사용자 보고 "히드라 HP 소모 후 O 액션 안잡힘") — 원인 셋:
  - **넉백으로 뿌리가 밀림** [근사]: 엔진 피격 넉백 (`pos += knock / 400`) 이 제자리 히드라에도 걸려 □ 콤보마다 뿌리가 뒤로 — 150 초 싸움에 z 91 → 7 (84) 물러나
    기절한 머리 (턱 끝 z ≈ 뿌리 + 270) 가 바닥 끝 (z ≈ 320) 에서 멀어지고, 머리 몸 상자 (Sold 관절 상자) 에 막힌 영웅 (z 431 고정) 의 ○ GRAB 이 닿지 않았다.
    제자리 적 (`stationary`) 은 넉백 이동 없음 → 같은 녹화에서 뿌리 z 91 유지, 영웅이 머리 옆 z 365 까지.
  - **GRABREJECT 와 동점** [확실: 0x2089B4–0x208A04]: 영웅 GRAB 200 의 HIT_CONFIRM `830 BHAENC1GRAB tgt=251 tag=830 0x109247` 과 `204 GRABREJECT tgt=251 tag=830 0x10c247` 은
    점수가 같고, 엔진은 0x4000 을 "태그 무시" 로 읽어 기절 중에도 둘 다 통과 → 동점 난수로 GRABREJECT 가 나왔다 (수정 중 녹화: 기절 107 에 ○ → 63.58 초 204). 원작 0x4000 은 **태그 부정** — 기절 (SET_1B0 830) 이면
    GRABREJECT 가 빠진다 (10 §2.3–2.4, `Combatant.score`).
  - **사망 뒤 150 이 안 나옴** [확실: 0x209024–0x20907C]: 830 의 SELF_HP (0.79) 로 죽을 때 830 의 EV_DEATH 는 (block). 원작은 분기 큐가 비면 매 프레임 HP ≤ 0 → EV_DEATH 를 다시 맞춰
    830 이 끝난 뒤 루트 A150 `EV_DEATH → 150 @0.83 self=251` (A170 → 123 보다 구체적) 로 간다 → 1035 150 → `playerBlock-DetectDeath` (1.25 초 뒤 LD11 참·LD9 거짓,
    goplayerblock 애니 = 길막 치움). 엔진은 이 재검사가 없어 830 끝에 히드라가 그냥 사라지고 길막이 남았다 → `Combatant.update` 기술 끝 재검사 (사망이 아직 기술로 이어지지 않았을 때 한 번),
    죽은 적 정리 (4 초) 는 이어진 기술이 끝날 때까지 (최대 30 초) 기다린다.
- 시험: `RootMotionTest.holdHydraStandsUprightAnchoredAndIsHittable` (대기 클립·제자리·턱 끝 높이/앞 (물기 Bite2x 는 골반을 앞으로 옮겨 뿌리 앞 ≈ 492 까지)·물기 15/16·□ 콤보 적중·맞아도 뿌리 고정),
  `holdHydraHeadUprightInEveryClip` (골반 = 디스크립터 1 절대 키, 대기 턱 끝 (·, 421, −996), 21 개 클립 × 9 표본에서 턱 끝이 뿌리 위 · 잡기 아닌 클립의 머리 위쪽 = (턱 끝 − 머리) × (왼눈 − 오른눈) 이 BHAbabyEmerge 기준과 같은 부호),
  `holdHydraCircleFinisherAndDeathMove` (기절 107 에서 ○ → 6 번 모두 영웅·히드라 830, GRABREJECT 없음 / 830 중 사망 → 끝난 뒤 150 @0.83 한 번), `BossMeterTest` (슬롯 2, 3).
- 녹화 (커밋 안 함, `-Dgow.auto=1`, 쉬움): 스크래치 `hh/hold_hydra_sheet.png` — Burst (바닥 끝에서 솟음) · 싸움 (머리 바로 섬, 뿔·눈 위) · 기절 (머리가 바닥 끝에 눕고 ○ 아이콘) ·
  봇 ○ → 830 BHAENC1GRAB (56.6 초, TimedPress 자동 입력 통과) · SELF_HP 사망 (HP 0) → 150 (71.7 초) → goplayerblock → 봇이 다음 구역으로 (오브 175).
  예전 녹화 (`hh/crops.png`): 머리 등이 아래, 기절 머리가 바닥 끝에서 멀어 봇이 z 431 에 갇힘.
  ~~남은 것: θ 회전은 원작 코드로 확인하지 못했다~~ → 회전 보정 없음 (위 정정). 830 동안 영웅 손과 머리는 평균 ~65 (손이 머리에 딱 붙지는 않음 [미확인: 830 장면별 영상 대조]).
  녹화 (`work/verify/anm_layout/hold_hydra_*`, 커밋 안 함): 새 배치 엔진 · 예전 (X 50.7°) 엔진 · PS2 272.5–342 시트.

### 4.4 갑판 아기 히드라 (ATHN01E enc3, 템플릿 258 = 그룹 252 "baby1" / 253 = 그룹 253 "baby2", `bhb`) [확실: 스크립트·CombatFile]
```
a01Mp03 끝 → 302/303 → SpawnEnemy 258 (-2741, 712, 129) SpawnAction 30 / 253 (-2720, 712, -353) SpawnAction 31 (BHABABYEMERGE, 갑판 구멍)
피격 1039 → baby*Ent-healthSensor (LD53/54 = HP) / lowHealthSensorRevive HP ≤ 90 → stunTransmit 1019 110 (baby1) / 114 (baby2)
  (조건: 고정 LD47/48 거짓 · 쓰러짐 LD49/50 거짓 · LD51/52 거짓) + movingBlock 애니 0
  110 BHAENC3PINRIGHT / 114 PINLEFT (쓰러져 머리가 갑판에) → AUTO 111 / 115 고정 루프 (INVULNERABLE, SET_1B0 827/826, RegenHealthBar 253)
  1035 110/114 → lowHealthSensorStop: LD49/50 참, recoverTransmit 1019 112/116 지연 18 초 (그때도 LD49 참 · 고정 아님이면 일어남, LD49 거짓)
  쓰러진 동안 영웅이 닻 볼륨 gospike1 / gospike2 (갑판 위 공중 y 1079–1114, (-2763..-2694, 201..270) / (-2766..-2694, -520..-448)) 에 들어가면
    spike*entry (재발동 6 초, 고정 전만): 닻 애니 act 8, LdropCam / RdropCam, spike*transmit 1019 113 / 117 지연 0.25 (LD49/50 참일 때)
  113 / 117 PIN*SLAM: INVULNERABLE, SELF_HP 15 × 11 (EV_DEATH 는 (block)), AUTO → 자기 @0.10 반복 = 영구 고정 ("baby dead IMPOSSIBLE")
  1035 113/117 → spike*-pinSensor LD47/48 참 (+ AnchorSplat 소리), trigHydras-pinBaby* LD12 +1 → LD12 ≥ 2 면 aftrBabiesCheckpoint
```
- 즉 아기 히드라는 **죽지 않는다** — 체력을 90 이하로 깎아 쓰러뜨린 뒤 18 초 안에 닻을 떨어뜨려 고정하는 것이 마무리다. 닻을 못 떨어뜨리면 일어나서 (112/116)
  다시 싸우고, 다시 HP ≤ 90 피격이면 또 쓰러진다 (LD49 가 거짓으로 돌아왔으므로). "누운 채 끝나지 않음" 은 이 반복이다 (예전 녹화: 18 초 누움 → 일어남 → 봇 공격 → 쓰러짐).
- ○ 는 서 있는 아기 히드라에 **영웅 A806 CIRCLE (대상 종류 252) → COMBO5A 800 (SET_1B0 800)** → 아기 255 의 A825 EV_HIT_REACT 태그 800 → **825 BHABABYGRAB**
  (사슬로 크레토스를 끌어 [0–0.22] 이벤트 19, 0.5 에 SELF_HP 10) / 영웅 COMBO5A → GRABREJECT (§11.2, `GrabSync.lonePull`). 쓰러진 루프 111/115 는 피격을 전부 (block) 하므로
  ○ 마무리 미니게임은 데이터에 없다 (826/827 BHAENC3GRAB* 은 어느 분기·태그도 가리키지 않는 미사용 기술, 111/115 의 SET_1B0 827/826 도 받는 쪽 없음) [확실: R_PERMK·ATHN01B/C/E CombatFile 검색].
- 1035 는 기술 시작마다 (0x209488 → 0x209610, AUTO 자기 반복 포함) 라 113/117 이 반복될 때마다 LD12 가 오른다 — 하나만 고정해도 체크포인트 조건이 찬다 (원작 데이터 그대로).
- **미터**: 51d51bc 의 슬롯 규칙 (AI1 #f3a51122 + 1: 258 → 2, 253 → 3) 이 FLP_Athn01E 의 HydraMeter2 (왼쪽 반) / HydraMeter3 (오른쪽 반) 와 맞다. 녹화에서도
  baby1 피해로 왼쪽 막대가 줄어든다 (예전 녹화 e3: t 90 가득 → t 120 1/3, 새 녹화 t 112 에 붉은 감소분도 보임 — FLP 의 HydraMeterBar "On", 값이 줄 때).
  "미터가 안 준다" 는 피해 2–12 에 HP 250 이라 몇 대로는 거의 안 보였던 것 + 머리가 엉뚱한 곳에 눕던 문제의 착시 [확실: 녹화]. RegenHealthBar (111/115 의 253) 는 여전히 무시 [미확인].
- **고침 1 — 제자리** [확실: TWK / 추정: 원작 이동 실패 처리]: goBHB Navigation ANIWalk = "bwalk" 가 ANM 에 없는데 `BossKinds` 가 이름 규칙 (…"idle") 으로
  BHAbabyIdle 을 걸음으로 잡아 Sold 속도로 미끄러졌다 → 뿌리가 갑판 구멍에서 영웅 발밑까지 따라와 (녹화 (-2741, 129) → (-2644, 188)) 쓰러진 머리가 엉뚱한 곳에 누웠다.
  이제 히드라 리그 (BHA01JOCPEL1) 는 ANIWalk 가 ANM 에 없으면 걷기 없음 → `EnemyFighter.stationary` (선창 히드라 §4.3 과 같은 규칙, 회전만).
- **고침 2 — 요청이 분기 큐를 비움** [확실: 코드]: 원작 Combat_RequestAction 0x209378 은 큐 링 (+0x1C5 = 0xFF, +0x1C6 = 0) 을 비우고 넣는다. 엔진 `Combatant.request` 는
  이전 기술의 대기 AUTO 를 남겨, 111 루프 중 1019 113 (고정) 이 끝나면 111 의 AUTO 가 실행돼 쓰러진 루프로 돌아갔다 (113 의 AUTO → 113 @0.10 이 져서 고정이 풀림).
- **고침 3 — 쫄의 기술 이벤트**: 보스전 리소스로 스폰한 쫄은 GameScene 의 일반 적 이벤트 처리 (SELF_HP·ExplodeDeath·생성 액션) 를 건너뛰고 BossFight 는 보스만
  받아, 아기 히드라의 113/117 SELF_HP 15 × 11 · 825 SELF_HP 10 과 SpawnAction 30/31 (BHABABYEMERGE) 이 빠졌다 → `BossFight.minionEvent`, 쫄도 생성 액션.
  EV_DEATH 가 (block) 인 기술 중 SELF_HP 로 HP 0 이 되면 **HP 1 에서 멈춘다** [근사]: 원작은 HP 0 이어도 사망 분기가 막히면 생물이 남고 (고정된 아기는
  큰 히드라가 죽은 뒤 trigHydras-EventInstantDie252/253 의 1029 로 없앤다), 엔진은 HP 0 = 사망·4 초 뒤 제거라서. 미터는 1/250 ≈ 빈 칸.
- 자동 플레이 [엔진 전용]: `BossFight.autoObjective` — 아기 히드라가 111/115 면 그 닻 볼륨 점으로 걷고 가까우면 이단 점프, 6 초 안에 못 들어가면 순간이동 (기록),
  두 아기가 고정되면 (LD47·48) 아래 갑판 spawnDaddyHydra 볼륨으로. `autoIgnores` — 고정된 아기 (113/117/124/125) 는 대상에서 뺌. 쫄과 싸우다 8 초 동안 24 도
  못 움직이면 대상 쪽 같은 높이 점으로 순간이동 (닻이 떨어진 상자·머리 사이 틈에 갇힘). 닻 볼륨은 갑판 위 265 (공중) 라 상자 위로 올라가야 하는데 봇은 오르기를 못 한다.
- 녹화 (커밋 안 함, 스크래치 `hb/r4/sheet.png`, 1 fps): t 95 baby1 피격 → t 112 왼쪽 미터 대부분 빔 + 붉은 감소분 → t 120 쓰러진 머리 위 → t 123 LdropCam (닻 낙하)
  → t 140 왼쪽 미터 빈 칸·baby2 와 싸움 → t 153–157 오른쪽 미터 빔·baby2 쓰러짐 → t 159 RdropCam → t 161 고정된 머리 → t 166– 아래 갑판, 큰 히드라 250 스폰
  (그 뒤 봇은 바다 쪽 큰 히드라에 못 다가가 사망 — 큰 히드라 싸움은 `HydraFightTest` 몫). 예전 녹화 (`e3`): 뿌리가 영웅 발밑까지 미끄러져 와 머리가 엉뚱한 곳에 눕고,
  봇은 닻으로 가지 않아 18 초 누움 ↔ 일어남이 끝없이 반복.
- 시험: `BabyHydraTest` (제자리, 미터 슬롯 2/3, HP ≤ 90 → 110 → 111, spike1 → 113 유지·LD47, baby2 114 → 115 → spike2 → 117·LD48, LD12 ≥ 2,
  닻 없이 18 초 → 1019 112 일어남).

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
- 테스트: 로드(HP 10000, 원 9800), 시선 → 석화 → 흔들기 탈출, HP 9700 → 회복 기술 30 → 잡기 → 스틱 회전 2회 성공 → 사망 → LevelData0,
  메두사의 시선 보유·MP 가득·메시지 141, 벽에 막혀 머리 뜯기 구역 안 (`queenStaysInsideTheRipZone`).

### 5.1 "HP 10000 에 한 대 4 — 죽지 않는다" (자동 플레이 QA, 2026-10-24)
증상: `--game ATHN02D --arrive ATHN02C -Dgow.auto=1 -Dgow.difficulty=0` 120 초 뒤 HP 9980. 원작 영상은 약 35 초 (□ 콤보·포세이돈·사슬, 약 30 타)
뒤 머리 뜯기 → 시체 → 아테나 석상 → L2 시선 안내 → 석상 미노타우로스.
- **HP·피해는 원작 그대로 [확실]**: AI1 062 HitPoints = MaxHitPoints = 10000 (0x2710), 피해 = 공격.damage × 공격자 배율 (0x203720 → 0x209E58,
  영웅 = 0x1DBD18 난이도 Damage Mult: 쉬움 2.0) — 피격자 쪽 배율·방어구 필드는 없다. □ 콤보 COMBO3A..C 기본 2 → 쉬움 4 는 맞는 값.
  여왕은 HP 로 죽는 적이 아니다: **원 임계 6 개** (AI1 +0xA0..+0xAA, 0x18BE58 루프 "Circle Health Threshold"/"…%d") = 9800, 9790, 9780, 9770,
  9760, 9750 → 200 피해 (쉬움 약 50 타, 보통 100 타 — 영상의 히트 카운터는 포세이돈·사슬 다단 포함) 뒤 잡기 원 → ○ 260 MAIMEWGRAB 이 처치.
  9750 (비율 0.975) 아래면 DTre 회복 30 (SELF_HP −50) 이 다시 원 구간으로 되돌린다. TakeDamage 0x1E1650 은 6 임계 모두 검사하지만 원 켜짐
  (+0x1B8) 은 260 의 CIRCLE_OFF · 261 의 CIRCLE_ON 이 관리하므로 엔진의 `hp ≤ 9800 = 잡기 가능` 과 결과가 같다 (회복은 ≤ 9800 까지만).
  AI1 +0x9E (a6e37c64, 여왕 2) 는 피해와 무관 — 전투 이벤트 템플릿 변형 번호 (0x206418 → 0x225798).
- **원인 1 — 자동 플레이 봇 [엔진 전용]**: `GameScene.waitsForGrab` 이 "적 기술에 GRAB(200) 피격 분기가 있으면 ○" 였는데, 여왕은 모든 기술에
  260 분기 (0x148212 = 잡기 원 필요) 가 있어 봇이 묶음마다 ○ 만 눌러 204 GRABREJECT 를 반복했다 (90 초에 □ 적중 5 번). 이제 분기의 잡기 조건
  (0x40 무시 / 0x10 == 잡기 가능) 도 본다 — 선창 히드라 830 (0x148247, 0x40) 은 그대로.
- **원인 2 — 보스에 벽이 없음 [확실: 데이터·원작 경로]**: GameScene 은 보스 개체에 공용 내비 세계를 붙이지 않아 (`bossKind == null` 일 때만)
  여왕이 바닥 높이만 유지하고 벽을 무시했다. 회피·꼬리치기 루트 모션과 걷기로 동쪽 기둥 벽 (ATHN02C 시트 x ≈ −887, GeneralGuide ·
  NoPlayerCollision = 적만 막는 면) 을 지나 x −785 (영웅은 −898 에서 막힘) 까지 나갔고, 거기서 머리를 뜯으면 `MedusaRipZone-pCube3661`
  (x −1280..−864, z 1856..2176) 밖이라 EventCombat1 이 걸리지 않았다 — EventSensor 메시지 5 모드 2 = 보낸 쪽 접촉 목록에 소유 볼륨
  (0x1AAE48 → 0x1A62B8). 결과: 메시지 141·보상·미노타우로스 없음, Idle 로 멈춤. 여왕은 일반 Enemy1 이므로 `BossFight.usesNavWorld()`
  (기본 false, `MedusaQueenFight` true) 로 같은 이동 단계 (0x1EA5F0: 벽 0x1E2100, 이동 선분 0x1EAB78) 를 탄다. 히드라·아레스·BMI 는 그대로.
- **원인 3 — 마법 획득 없음 [확실]**: EventCombat1 의 `set_bool Internal:39 true; set_float Internal:35 ← Internal:36` 은 GameMgr 변수
  setter 0x1AFFB0 (점프 표 0x2B5C80) 의 39 → 0x1B0088 → AddItem 0x1D8CF0 (4 = 메두사의 시선) 과 35 = MP 를 최대 MP 로. 엔진은 Internal
  변수 쓰기를 버렸다 → `GameScriptHost` Internal 33..45 (13 문서 §7.1).
- 결과 (쉬움, 봇 □□□△ + ○, 결정적): 전투 시작 4.5 초 → 머리 뜯기 57.4 초 (석화 2 번 포함 약 53 초), 메시지 520 → 63 초 EventCombat1 →
  66.4 초 메시지 141 → 미노타우로스 3 스폰 → 77.9 초 메시지 595 "[L2]+[□] 메두사의 시선 … 미노타우르스를 얼린 뒤 부숴야" → HUD 마법 아이콘
  메두사·MP 가득. 예전: 120 초 HP 9980, 머리를 뜯어도 (봇 수정 뒤) 그 뒤가 없음.

### 5.2 여왕 뒤 미노타우로스 방 — "얼린 뒤 부숴야" (자동 플레이 QA, 2026-10-24)
원작 영상: 메시지 141 → 메시지 595 → 미노타우로스 (+ 하피류) 와 싸우며 시선 (흰 섬광) 으로 석화 → 부숨 → 문이 열리고 아테나 석상 (금빛 얼굴)
→ 여왕 사망 약 75 초 뒤 다음 연출. 엔진은 봇이 시선을 못 써 미노타우로스가 계속 다시 나왔다.
- **메시지 595 [확실: R_PERMK 텍스트]**: "메두사의 시선을 사용하려면 [L2Button]을 누르고, [SquareButton]을 눌러서 발사하십시오. 전진하려면 모든
  미노타우르스를 얼린 뒤 부숴야 합니다." 상황 시전이 아니라 시선의 기본 조작이다 — 업그레이드 설명 734 "고르곤의 응시: L2 홀드 + □ 홀드",
  735 "고르곤의 투사: L2 홀드 + △", 737 "고르곤의 분노: L2 홀드 + ○ 홀드".
- **원인 1 — 광선 버튼 [확실]**: 엔진은 USEHEAD 동안 L2 만 누르고 있으면 광선을 켰다. 원작 SCR_MedusaHead 0x1972E8 은 광선 상태 0x29D7F8 을
  패드 현재 비트 (+0x28) **& 0x80 = □** 와 HasMagic(Drain/60, 0x1DCB20) 로 켜고 끈다 ("On"/"Off" 0x196B18). L2 (MAGIC 비트 0) 는 81 PSENTHEAD →
  82 USEHEAD 를 유지할 뿐 (MAGIC/not-held → 83 PUTAWAYHEAD), MP 부족은 0x1E1AB0 (+0x1AC |= 0x400 → AUTO 91). 같은 패드 워드의 새 누름
  0x10 = △ 섬광, 0x20 = ○ Nuke (SCR_TriggerMedusaHead 0x197FD8) 와 일치. `MedusaGaze.update`: 광선 = 머리 스크립트 && □ 홀드 && MP.
- **원인 2 — 석상 파괴를 스크립트가 못 셈 [확실]**: 석화 상태 메시지 처리 sub_00265C48 (vtable 0x2C4DB4) 의 0x405 (사망) 분기가 0x1D38C8 뒤
  `PostEvent(0x416 = 1046, 0, 0, 0, 보낸 쪽 = 그 적)` (0x265E04 → 0x1A8C78) 를 보낸다. ATHN02D 스크립트:
  ```
  EventSmash1 (1046, 모드 2 = 보낸 쪽이 MedusaRipZone 안, 조건 LevelData0 = 여왕 사망)
      → LevelData1 += 1 (부순 수), LevelData5 = 참, LevelData4 += 1 (없어진 수), HulkSmashy (0.51 초 뒤 LevelData5 = 거짓)
  MinoDestruction1 (제거 센서, Filter 25, 조건 LevelData5 == 거짓) → LevelData4 += 1          ← 보통 사망 (부수지 않음)
  SpawnEnemy MinotaurLevel1AI6/9/10 (Respawn, 조건 LevelData1 ≤ LevelData2 (6)) → LevelData3 += 1 (스폰 수)
  문 (MAcdHullOff, FinalCamOn → DrOffCam, doorFadeAnim, PlayTalk2, 9 초 뒤 DMCWall VisOff): LevelData1 ≥ 6 && LevelData3 == LevelData4
  ```
  엔진은 1046 을 보내지 않아 보통 사망만 세었다 (문 영영 닫힘). → `CombatScriptBridge.stoneKill` (GameScene.stoneKill).
- **원인 3 — 두 번 세기 [추정: 원작 제거 시점]**: 엔진은 죽은 적의 스크립트 제거 (메시지 6, 제거 센서) 를 0.5 초 뒤에 했다. 0.5 초 안에 둘을
  부수면 앞 석상의 HulkSmashy 가 LevelData5 를 끈 뒤 뒤 석상이 제거돼 MinoDestruction1 이 한 번 더 세어 LevelData4 > LevelData3 → 문이 안 열렸다
  (실측 83.97 / 84.04 초 두 마리 → 85.00 MinoDestruction1). 스크립트 설계 (LevelData5 창 0.51 초) 는 제거가 그 창 안이라는 뜻이므로 부서진 석상은
  1046 전달 다음 틱 (`GameScene.SHATTER_DESTROY` 0.05 초) 에 제거한다. 보통 사망은 그대로 0.5 초.
- **봇 [엔진 전용]**: 시선 보유 + MP ≥ 15 + 대상 게이지 < 60000 이고 돌이 아니면 ← 로 선택 → 170 유닛 안으로 → 0.2 초 몸 돌리기 → L2 홀드,
  0.35 초부터 □ 홀드 (최대 3 초) → 놓고 0.4 초 → 400 안 석상이 있으면 그쪽 □ 묶음 (`BotInput.gaze`, `AutoPlay.gaze`).
- 결과 (쉬움, 결정적): 머리 뜯기 57.5 초 → 메시지 595 77.9 초 → 광선·석화·파괴 83.97 / 84.04 / 86.10 / 93.17 / 96.02 / 98.12 초 (6 번째) →
  MAcdHullOff·DrOffCam·문 불꽃 사라짐·아테나 얼굴 PlayTalk2 99.1 초 (여왕 사망 약 42 초 뒤) → 107.1 초 DMCWall VisOff (문 열림), CheckPoint.
  예전: 200 초 동안 미노타우로스만 다시 나옴 (석상 파괴 0 회 집계). 증거 `work/verify/athn02d_gaze/` (광선, 석화, 파괴, 문 카메라, 불꽃 벽 꺼짐).
- 남은 차이: ~~석화된 적은 색이 그대로~~ → 석상 GO 로 바꿔 끼움 (35 §2.2, 2026-10-24; 석상 타수 7 이라 6 번째 파괴 115.6 초 → MP 재생·처형 대상 수정 뒤 문 109.0 초, §5.3), 하피류는 이 구간에 나오지 않음 [미확인], 6 번째 뒤에도 남은
  Respawn 이 3 마리를 더 낸다 (조건은 트리거 시점 LevelData1 ≤ 6 — 원작도 같은 데이터).
- 테스트: `MedusaQueenFightTest.stoneKillCountsOncePerMinotaur` (1046 → LevelData1 2, 0.07 초 차이 두 석상 → LevelData4 2; 대조 0.5 초 제거 = 3),
  `MagicSpellsTest.medusaBeamNeedsSquare`, `BotInputTest`.

### 5.3 미노타우로스 방 리스폰·MP 정밀 검사 (2026-10-24)
**스크립트 (원작 그대로, 교착 없음) [확실: 데이터·코드]**
- SpawnEnemy MinotaurLevel1AI6/10/9 (모드 1, Filter 25): 슬롯 0 MaximumCount 없음 (무제한), 1 = 3 (+0xB8, 스폰 경로 0x1AC580/0x1AC630/0x1AC850 이
  읽지 않음), Respawn 참, RefractTime 1, 조건·DelayedEnable = `LevelData1 ≤ 6 && LevelData0 && !GlobalData1`, Delay = LevelData3 == 0 (처음,
  FirstMsgDone 1040(141) 이 셋을 발동) 이면 **7 초**, 아니면 (리스폰) AI6 1 / AI10 2 / AI9 1.25 초 — `jump_if` (0x0F) 는 **거짓이면** 점프 (0x16F570,
  43 §14.2) [확실]. 2026-10-24 앞 판은 참이면 점프로 읽어 처음 1/2/1.25 · 리스폰 7 초로 거꾸로였다. Action = LevelData5 = 거짓, LevelData3 += 1.
- 제거 메시지 6 (0x1AC630): +0x104 += 1, 보낸 쪽이 있고 Respawn 이면 Trigger (최소 지연 0.25) → 조건은 **죽은 시점** LevelData1 ≤ 6.
- MinoDestruction1 (DestructionSensor 모드 3): 조건 LevelData5 == 거짓은 제거 때, **Delay 0.5 초** 뒤 LevelData5 = 거짓, LevelData4 += 1.
- EventSmash1 (1046) 은 지연 없이 LevelData1/4 += 1, LevelData5 = 참 (HulkSmashy 0.51 초 뒤 끔). 문 엔티티는 두 센서의 대상이라 그 발동 때만 조건
  (LevelData1 ≥ 6 && LevelData3 == LevelData4) 을 본다.
- 결론: 3 마리가 늘 유지되고, 부순 수 ≤ 6 인 동안 죽은 자리마다 1–2 초 뒤 다시 나온다. 문은 (a) 일곱 번째 파괴 뒤 (더는 안 나옴) 남은 것을 다 잡거나,
  (b) 6 이상에서 살아 있는 셋을 리스폰 1–2 초 안에 모두 없애면 열린다. 세는 쪽은 스폰마다 정확히 한 번 (석상 = EventSmash1, 보통 사망 = MinoDestruction1)
  이라 원작은 교착이 없다. 엔진 카운트도 실측 일치 (LevelData3 == 4, 석상 제거 1046 다음 틱 — §5.2 원인 3).

**엔진 결함 1 — 마법 재생 [확실]**: `MedusaRipZone-RewardPlayer` (FirstMsgDone + 10 초) 의 `call_int Internal:0x12 (10)` 는 GameMgr_OnMessage 0x12
(0x1B0B34, 표 0x2B5E20) = float 를 0x3044A4 (PlayerProgress +0x64) 에 저장하고, 영웅 갱신 sub_001D6CF0 (0x1D6F00–0x1D6F88) 이 게임 중 매 프레임
MP (0x30449C) = min(max(MP + 값/60 × 시간 배율, 0), 최대 MP) 로 더한다 → **10 MP/초**. 문 조건 + 10 초의 `TurnFaceOffFinal` 이 0 으로 끈다.
단계 0 광선 12.5 MP/초 이므로 순소모 2.5/초 — 이 방은 MP 가 사실상 무한하다. 미노타우로스 오브 (770/771/772/773) 는 Health·Magic 슬롯 템플릿이
51/52 = 붉은 오브라 파란 오브가 없고 (35 §2), 방에 상자 Creator 도 없다. 엔진은 0x12 를 버렸다 (`ScriptHost` 기본 → listener.internal) →
`PlayerState.mpRegen` / `regenMagic` (`HeroRig` 가 magic.update 앞에서, HP > 0), 세이브 `mpRegen`.
**엔진 결함 2 — 처형 대상 [확실: 실측]**: `GameScene` 이 결합 (GrabSync) 이 처형 성공으로 끝나면 그 틱의 `target` (가장 가까운 적) 을 HP 0 으로
했다. 여왕 머리 뜯기 결합은 여왕 사망 뒤 영웅 Idle 동안 남아 있다가 68.88 초 첫 미노타우로스가 스폰된 틱에 대상이 바뀌며 끝나, 그 미노타우로스가 즉사
(1039 25 90 0 → 69.88 MinoDestruction1, 7 초 리스폰). 이제 잡고 있던 상대 (`grab.partner()`) 만 죽인다.
**봇 [엔진 전용]**: 광선 조준 (`MedusaGaze.aim` = 영웅 combatant 대상) 을 봇 대상으로 (가장 가까운 적이 석상이면 광선이 석상 쪽으로 갔다); 광선 중 정면 ±60°,
220 안의 아직 돌이 아닌 적이 있으면 끄지 않고 넘김 (`GAZE_SWEEP`, 최대 4.5 초); 대상 게이지가 0.4 초 동안 안 줄면 뗌 (헛 광선 방지 — 석화되면 곧바로
떼는 것은 그대로); 215 안에서 시작; 석상은 △ (combo6A 피해 6) + □ (2) = 두 타 (□□□△ = 2+2+2+0 네 타), 석상에는 ○ 묶음 없음.
- 결과 (쉬움, 결정적, 3 회 같음): EventCombat1 62.9 → RewardPlayer 77.9 → 파괴 83.6 / 86.8 / 87.1 / 93.6 / 96.9 / 100.0 초 (6 번째에 LevelData3 == 4)
  → FinalCamOn 100.0, CheckPoint2 108.0, **DMCWall VisOff 109.0 초** (여왕 사망 46 초 뒤, 영상 약 75 초), TurnFaceOffFinal 110.0 (재생 0). 그 뒤 남은
  리스폰 3 회는 원작 데이터대로. 예전: 6 번째 115.6 초, MP 15 고정, 보통 공격으로 셋을 잡아 DMCWall 179.8 초 (실행마다 다름 — 280 초 안에 못 연 적도).
- **jump_if 수정 뒤 (2026-10-24, 43 §14)**: 첫 셋 74.9 초 (FirstMsgDone 67.9 + 7), 리스폰 1.25–2 초 → 파괴 83.1 / 86.3 / 92.2 / 114.4 / 115.9 / 117.9 /
  120.6 / 123.2 / 126.3 초 (6 번째에는 살아 있는 리스폰이 있어 LevelData3 ≠ 4, 9 번째에 같아짐) → FinalCamOn 126.3, CheckPoint2 134.3,
  **DMCWall VisOff 135.3 초** — 머리 뜯기 57.5 초 뒤 약 78 초 (영상 약 75 초에 가까워짐, 앞 판 109.0 초 = 52 초). 문 재질 페이드 ("$doorSub" 등) 는 43 §14.3.
- 테스트: `MedusaQueenFightTest.minotaurRoomRegeneratesMagicUntilTheDoorOpens`, `GameScriptHostTest.internal12SetsMagicRegeneration`,
  `BotInputTest.stoneTargetStartsWithTriangleAndNeverGrabs`.

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
- 카메라: 아레스 클래스가 매 프레임 AresCamera1 을 요청하고, 원작 비용 함수(0x1F67C0)로 두 몸·두 관절(vertebrae4 / BAR01JOCVEC1)을 함께 잡는다 (30 문서 §6.3).
- 아레나는 얕은 만(灣)이다: 바닥 면이 Water(y 1)이고 7.7 아래 바다 바닥(y −6.7 = "bay floor"·16). 거인 크레토스는 물을 헤치고 싸운다 —
  예전엔 Water 면에서 바로 헤엄 상태가 되어 바닥 아래 y −15 에 갇혔다 (`--boss-demo` 의 "크레토스가 바닥 아래" 원인) → `HeroNavigator.deepWater` (§3.2).

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
| 스크립트 카메라 (grabLeftCam, bossKillCam 등), AresCamera1 | **해결** (30 문서 §6): ESC 0x9/0x8 + 관절 애니 카메라(0x1000 재시작) + 전환 이벤트 1037. AresCamera1 = 원작 0x1F67C0 비용 함수 (30 §6.3) |
| 히드라 잡기·처형 카메라에서 크레토스가 안 보임 | **해결** (§4.2): 원작 0x204BF0 — 보스 기술 802/806/810 의 이벤트 22 가 영웅을 synchJoint (zeroJoint 기준) 로 붙인다. 영웅은 돛대 단상 (y 1170) 에서 사슬로 머리를 당기고, 처형은 머리 위. 이전 §4.1 의 "정렬 문제 아님" 판단은 손–턱 접촉 가정이 틀렸다 |
| 아레스 잡기 | **원작 정렬로 교체** (§11): 605/606/607/609 는 아레스 기술의 이벤트 19 로 아레스가 영웅을 끈다 (§11.6), 608 [0–0.52]·600 [0–0.35] 의 이벤트 22 가 영웅을 끈다 (0x204BF0). 예전 BOSS_TO_HERO "같은 원점·같은 방향" 폐기 |
| RegenHealthBar (RegenBar 250/252: Regen Speed 150, 비율 0.8/0.45) | 의미 미확정 → 무시 |
| SonicScream 힘 | 근사 (거리 ×16 안에서 Force/500 단위/초) |
| 히드라 정렬 = 원작 0x204BF0 [확실], 메두사 석화, 아레스 차지 | –/[추정]/[근사] |
| 아기 히드라 enc3 (`bhb` 253/258) | **§4.4**: 쓰러짐 110/114 → 닻 고정 113/117 (스크립트), 제자리, 미터 2/3. 원작 화면 대조는 닻 연출 (LdropCam) 만 남음 |
| ATHN08B 분신 크레토스·가족 | **해결** (§9): 가족 보호·포옹(정렬 고침 §11)·휘말림·사망 실패, 분신 잡기 860–863 (흔들기 탈출 / 실패 피해), 포옹 신들의 분노 게이지 |
| 판도라 수호자 BMI (PAND03G) | **구현** (§10, §10.1): 전용 클래스 Think/TakeDamage 원작 해독 → `BmiFight`, 결말(245 → p03MP07 → Pand03H)까지. 통나무 투사체·증기·갑옷 조각·모델 변형·HUD EnemyState·카메라 대상 슬롯 원작 해독 (§10.1). 피한 통나무가 빗나가는 판정만 근사 |
| AresFight 데모 크레토스가 ~2.5초부터 멈춤 | **해결**: 1단계 데모는 0.9초마다 정면 60 에서 공격 (4 번에 500 임계 밑, 원 임계마다 290), 그 뒤 ○ 잡기. 예전엔 3초까지 서 있다가 불덩이에 맞고 정지 |
| FakeDeadBMI (죽은 수호자 장식, 통나무 박힘) | **해결** (Vis 적용, §10.x) |
| ATHN02D 메두사 방 바닥 충돌이 없음 (충돌 삼각형 138) | **해결**: 바닥은 숙주 레벨 ATHN02C 시트에 있음 (아래 §8.1) |
| 다른 슬롯 레벨 렌더 | ATHN02D 직접 진입 시 방 지오메트리(ATHN02C)가 그려지지 않음 — 충돌·스크립트만 올림 |
| 0x800 차지 분기 일반 지원, 조건 25(스크립트 변수 해시), 27(+0x64 쓰는 곳) | 미구현 |

### 8.1 ATHN02D 바닥 = 숙주 레벨 ATHN02C [확실: 데이터]
- ATHN02D 는 **덧붙는 레벨**이다: 렌더 인스턴스 9 개(마법 획득 단상·메두사·문·카메라), 충돌 시트는 단상(정점 89, 면 72, AABB z 1792..1957)뿐.
  메두사 방 바닥·벽은 ATHN02C 의 RIB_sheet (정점 1793, AABB (−1312, 944, 1248)..(960, 1520, 2832)) 에 있다. 디코딩 문제가 아님 (CDV/hull 충돌도 DMCWall 문 하나).
- ATHN02C 스크립트가 이 레벨을 올린다: `ESC_LoadWad2D` / `ESC_LoadWadZone3` → `LoadWad("Athn02D")`, `ESC_LoadCheckD` → `LoadCheck("Athn02D")`. ATHN02D 자신은 Load 계열 호출이 없다.
  원작은 두 슬롯 레벨이 함께 상주하므로 ATHN02D 가 "현재 레벨"이어도 ATHN02C 바닥을 밟는다.
- 엔진 `level/HostLevel`: 이 레벨 스크립트에 적재 호출(LoadWad/LoadCheck/LoadForWarp/Load)이 하나도 없을 때만 (`addOn` — 보통 레벨은 이웃으로 돌아가는 센서를 가져서 WALK 전환 대상), `find(rom, 레벨, 점)` = 같은 앞 6자 레벨 중 스크립트에 `push_string "<레벨>"` + LoadWad/LoadCheck 가 있고 그 시트가 점 아래 바닥을 가진 레벨.
  `GameScene.placeSpawn` 이 서 있을 점(시작/도착/세이브) 아래 자기 바닥이 없을 때만 찾아 `loadOther` (다른 슬롯: 충돌 + 이웃 스크립트) 하고, 숙주 바닥 위에서는 WALK 전환(다른 슬롯 바닥만 밟으면 그 레벨로)을 하지 않는다.
- 검증 `MedusaFloorTest`: 숙주 = ATHN02C (ATHN02B 는 아님), 메두사 정면 40/150·둘레 8 방향·시작 지점 모두 ATHN02D 시트엔 바닥 없음 / ATHN02C 시트 높이 960±40, 위에서 떨어뜨리면 그 높이에 선다.
  게임 (`--game ATHN02D --boss-demo`): "숙주 레벨 ATHN02C → 다른 슬롯", 머리 뜯기 뒤 영웅 (−1144, 960, 2017) 지면=true 유지 (전엔 낙하).


## 9. 분신 크레토스 / 가족 보호 (ATHN08B) — `EvilKratosFight`
원작은 전용 클래스가 아니다 [확실: 데이터]. 가족·분신 모두 일반 Enemy1 + CombatFile + 레벨 스크립트:
```
familyEnt-family60 SpawnAtStart 660 (그룹 60) → 0.1초 뒤 1019(0) → M0 XMCSTANDIDLE(무적) → AUTO → M1 XMCTAUNT (잡기 원, 소리)
giveAthenaSword CreationSensor(영웅) → evilsWAVE1 666 ×4 (2..5초) → Destruction → WAVE2 (MaximumCount 7/8, Respawn) → LevelData 카운터
  → WAVE3 667 / WAVE4 668 (Condition LevelData n == n+1) → Destruction6681 이 LevelData30++ → 25 = LevelData31 → winEnt: A07MP05 → Warp
beginEvil 볼륨 → LevelData17+30 초 뒤 궁수 669 (DTre "dark lightning!" 20)
분신 DTre 666/667/668: 토큰 있음(c24) ? 칼날 공격 1/2/3 · 4/5 · 10–13, 0 EVIGRAB(잡기) : back off(30)
가족 피격: 661 A130/A131 EV_HIT_REACT tgt=666 → XMCHIT*, 영웅(class 0)의 반응 태그 0..20 → 전부 (block), A150 EV_DEATH → M150 XMCDEATH2
  → 1035(150) familyEnt-move150: Idle + camWifeDeatho, 2.5초 페이드, A07MP04 → 501 → FailedScreen
포옹: 영웅 ○ GRAB(자세 200) → 가족 M1 EV_HIT_REACT tag 200 → 865 / 영웅 HIT_CONFIRM tgt=660 → 865 MAIEVIHUGSTART
  865: 영웅 SELF_HP +2 ×12 (0.40–0.95), 가족 SELF_HP −2 ×12 → 866 루프(○ 유지 · 영웅 HP ≥ 11 · 가족 HP < 99, 1씩) → 867 → 영웅 80 / 가족 대기
  1035(865) → hugMsg: 메시지 454 "가족을 안으려면 ○을 계속 누르십시오. 그들을 안으면 당신의 생명력이 흡수당하게 됩니다."
```
- 엔진: 가족 = 본체(보호 대상), 분신 = 쫄. 가족 스켈레톤은 **xmc** (CombatFile 의 XMC* 애니; 예전 등록은 정지 모델 `family`) — 이동 애니가 없고 DTre 001 이 비어 있어 위치·요 고정.
  분신 공격 판정이 가족에게도 (`Combatant.setBystanders`), 영웅 공격은 가족 피해 없음 (`damageFrom`, 잡기 자세 200 만 통과 — 포옹 개시).
  포옹 정렬 = 가족 662 의 865/866 [0–1]·867 [0–0.4] 이벤트 19 → 가족이 영웅을 0x204BF0 로 끈다 (§11.6) [확실]: 영웅 오브젝트가 가족 정면 ≈ 16, 마주 봄
  (2026-10-24 정정, 05 §2.8.1: 가족 MAIEVIHugStart 의 디스크립터 1 이 zeroJoint (0,0,−27.1) · synchJoint (0,0,−32) 를 두어 가족 synch = −4.9 — 예전 항목 번호 배치는
  가족 세트 [0(p2 3)] 의 이동을 읽지 않아 바인드 (0 / −16) 로 정면 27 이었다. 16 이면 영웅 머리 ↔ 아내 머리 2.6 · 골반 8 (포옹), 27 이면 머리 사이 12.6) [확실: 데이터].
  (예전 엔진 대체 SYNCH "16 뒤·같은 방향" 은 폐기).
  가족 HP 0 인데 대기 순환(M0/M1, EV_DEATH (block))이면 엔진이 기술 분기를 루트 액션보다 먼저 보므로 A150 이 안 걸린다 → 사망 기술 150 으로 [추정: 원작 우선순위].
  포옹 뒤 가족 기술이 없으면 0 요청 (원작 865 SYNC → M0, 스크립트 1019(0) 와 같은 대기 순환).
- **분신 잡기** (860–863) [확실: 데이터 + 0x1D8B98]:
  ```
  분신 M0 EVIGRAB (판정 [0.47–0.53] dmg 0, SET_1B0 860) 적중 → 영웅 A860 EV_HIT_REACT tag 860 → 860 EVIGRABHEROSTART / 분신 HIT_CONFIRM → 860 → AUTO 861
  861 EVIGRABHEROLOOP: 영웅 "[0–1]! AUTO dir 7 → 862 WIN" (dir 7 = 스틱 흔들기 게이지 > 2, §3.2) / "[0–1] AUTO → 863 LOSE" (창 끝), FlashSprite 12 = 흔들기 아이콘
  862: 분신 SELF_HP 5+15 · SCR_ExplodeDeath(on-dead) / 863: 영웅 SELF_HP 10, 분신 PlayFX 672/673
  ```
  엔진: `EvilKratosFight` 가 잡은 분신을 찾아 별도 `BossSync`(860–863, BOSS_TO_HERO — synch 둘 다 (0,0,−32), 쌍 기술 ID 따라가기)로 묶는다. 예전엔 영웅 860 이
  SYNC 분기(엔진에서 발동 안 함)만 가져 861 로 못 가고 끝났다. 탈출 분기는 창 끝 AUTO 가 큐에 있어도 즉시(!) 분기가 앞서게 고침 (§3.2).
- 신들의 분노: 가족 865/866 의 GOD_METER_ADD(+1 ×10, ×N)는 원작에서 플레이어 전역 게이지 → 크레토스에게 (예전엔 가족 combatant 에 쌓여 사라짐).
- 순회 검증: 격파할 본체가 없어 `HeadlessBoss` 는 맡지 않음 (`headless() == false`) — 분신은 일반 적처럼 처치되어 25 → ATHN08C (기존 TraversalTest 그대로 통과).
- 테스트 `EvilKratosFightTest`: 가족 xmc·HP 100·세트 660/661/662, M0 → M1, 제자리, 영웅 공격 무피해 / 포옹 동기(16 뒤·같은 방향, 손목 접촉 < 8), ○ 유지 HP 이동(+24 이상 / −24 이하), 메시지 454, 크레토스 게이지 ≥ 10, 끝나면 대기 /
  분신 잡기 흔들기 → 862 탈출 / 흔들지 않으면 863 → 영웅 피해 10 /
  분신 칼날이 가족에 휘말림, HP 0 → XMCDEATH2 → camWifeDeatho + A07MP04 → FailedScreen.
- 스크린샷 (`work/shots/37b/`): `evil_a.png` (집 안: 가족 앞 크레토스, 분신 4 — 하나는 스폰 점프), `evil_hug.png` (포옹 + 메시지 454, 영웅 HP 감소).

## 10. 판도라 수호자 BMI (PAND03G) — `BmiFight` (전용 클래스, 결말까지)
원작 [확실: 실행 파일]: 팩토리 0x1CEBA0 클래스 1 = BMI (객체 0x400, 생성자 0x1C0378, vtable 0x2BB3D8). 재정의 = +0x08 소멸, **+0x10 갱신 0x1C13E0**
(Think 0x1C0A48 → 기본 Enemy1 갱신 0x1EBD78 → 갑옷 조각 0x1C1B10), **+0x40 TakeDamage 0x1C1838** (0x204690 피해 적용이 부름). AI1 200 은 DTre 가 없다.
수치 `/GlobGame/BMI` (R_PERMK, BMI_RegisterTweaks 0x1C1EB0; 짧은 필드는 TWK 덤프에서 float 로 보이나 하위 u16 이 값):

| 항목 | 값 |
|---|---|
| Damage Per Vent / Armor Stage, No Armor Health Threshold | 100 / 150 / 99 |
| 기술 | Walk Forward 1, Leap Back 2, Dodge 220, Stagger First Time 250, Stagger 200, Stunned 201, Armor Stagger 230, Armor Stunned 232, No Armor Stunned 240, Platform … 251/205/206/231/232/241, Platform Mount 10 / Dismount 2, Victory 5 / 13, Retreat 40 |
| 거리 (×16) | Behind Close 2.5, Close Mid 12, Mid Far 18, Far Walk 34, Retreat 45, Platform 53, Platform Back 75. 각도 Center 30°, Platform Center 55° (tan) |
| 단계표 0x303EC0 (S0..S3, 0x24) | Close L/C/R, Mid L/C/R, Far L/C/R, Idle Time, Platform L/C/R/Back, Platform Idle Time. S0 = 78/150/79, 1/151/1, 1/152/1, 0.5, 105/100/103/103, 0.25 … S3 = 78/180/79, 1/181/1, 72/182/72, 0, 0 (150–182 = CombatFile 203 의 "대기 → AUTO 무작위 공격" 기술) |

```
Think (매 프레임): d = 영웅 − BMI, fz = 정면·d, fx = 왼쪽·d, hy = 높이, bA = 정면·(BMI − 앵커), hA = 정면·(영웅 − 앵커)
  기술 중: 영웅이 뒤(fz < 40) / 걷는 중 Close 안·낮음 / 발판 위에서 영웅이 내려옴 → CHARGED (대기·걷기·도발의 0x800 AUTO 로 끝냄)
  기술 없음: 대기 시간 −1/60. 같은 프레임 나중 요청이 이김 —
    발판: 영웅 HP 0 → 13, 뒤·낮음 → Dismount 2 / 아니면 영웅 HP 0 → 5, bA > Retreat → 40, fz > Far Walk 또는 hy > 96 → 1, fz < Behind → 2
    없으면 단계표: 쪽 = |fx| < |fz|·tan(Center) ? 가운데 : 왼/오, 띠 = Close/Mid/Far → 기술 + Idle Time 대기 (발판: Back/가운데/왼/오)
  앵커 선 고정: HP > 99 면 bA 를 [0, Platform Dist] 로 (BMI 는 돌지 않고 앵커 정면 선 위에서만 움직인다). 걷다가 Platform Dist → Mount 10 (발판)
TakeDamage: ≥ 1000 → 단계 +1 (통나무) / == 999 → NoDodge 아니면 Dodge 220 / 잠금(기절 연쇄) 중 무시 / 그 밖 누적:
  단계 0·1 배출구: 100 마다 하나 (소리 SND_MB_VENTSHOT·VENT%d_LP). 5 번째 = Stunned 201 (잠금), 첫 번째 = 250 STEAMVENT, 나머지 200
  단계 2 갑옷: 150 마다 230, 5 번째 = 232 갑옷 파괴 + 단계 3 (잠금)
  단계 3: HP 감소, 99 바닥에서 240 (잠금) → 242 → 243 통나무 기절 (INVULNERABLE·NoDodge, 22초 CHARGE_CHECK → 244 SELF_HP −10×25 회복)
  HP 는 단계 0–2 동안 1500 그대로
SCR: BMITurnOffVents(b) = 배출구 수 −b, 누적 0 (204/207/209/222/227 — 기절 풀림·올라타기 실패) / BMIOnPlatform (77 들이받기) / BMIBrokenPieces(b) / BMINoDodge (창)
```
연출 고리 (데이터):
```
5 번째 배출구 → 201 → 202 → 203 TURTLESTUNIDLE (CIRCLE_ON, 자세 203, CHARGE_CHECK 2초 → 204 흔들기 → 배출구 4)
  영웅 ○ GRAB(자세 200) → 203 EV_HIT_REACT tag 200 → 450 BMIRIDEMOUNT / 영웅 HIT_CONFIRM tgt=200 tag=203 → 450 (1035(450) → mountcam, 메시지 589)
  영웅 450 → AUTO 451 → 452 → 453 → 455: 각 기술 TimedPress 206 "arcPress180rand" ×2 (453 은 + 207 "arcPress360rand"), 실패 → REQUEST0 454 RIDEFAIL
  BMI 는 같은 ID 로 따라감 (BossSync), 454 → 207 도발 (배출구 −1) / 455 RIDEWIN → 208 LOGSTUNIDLE (22초, NoDodge, 1035(208) → LevelData4 "Boss is stunned")
레버 gologlever (PushPull 15 "BMI Log Puller", 과제 351) → 1023 → LevelData0 = % → 99: 통나무 장전·카메라
  LevelData4 거짓 → 0.5초 뒤 1039(999) → Dodge 220 (통나무 빗나감) / 2.5초 뒤 1020(1000) "fire log arrow" → Arrow/060 (피해 1000, Context 456)
  적중 → 단계 +1, EV_HIT_REACT tag 456 → A210 LOGHIT (HP 1001..) → 75 들이받기 / A245 (HP ≤ 1000) → SCR_ExplodeDeath
  단계 0 → 1 → 2 는 통나무 2 번, 2 → 3 은 갑옷 파괴, 마지막 243 의 통나무 → 245 → 1035(245) FinalMpegStuff → p03MP07 → LoadWad Pand03H
```
엔진 (`BmiFight`) 근사: 위치 = 기술 애니 zeroJoint 변화량 (BMI 애니는 zeroJoint 절대값이 클립마다 다르고 몸은 제자리), 앵커 = 스포너 소유 GO (§10.1),
통나무 = 레버 logAim 투사체 (§10.1), 올라타기 정렬 ZERO (§11). 증기·갑옷 조각·모델 변형·HUD EnemyState·카메라 대상 슬롯은 §10.1.
순회 검증은 아직 맡지 않음 (`headless() == false`, 검증 하네스 경로 그대로).

- 테스트 `BmiFightTest`: 문 앞 볼륨 → P03MP06 → Goto → 231 → 스폰 / 단계표 (멀리 → 1 걷기 이동, 정면 가까이 → 150), 앵커 선 /
  배출구 250·200·200·200·201 (HP 1500 유지, VentCam) → 203 → 잠금 중 무피해 → ○ → 450 (메시지 589) → 451–453 TimedPress 7 → 455 → 208 (LevelData4) → 레버 → 통나무 적중 → 단계 1, 210 /
  비기절 레버 → 999 → 220 → 빗나감 / 데모 끝까지: 232 (ArmorCam1/2), 240 → 242 → 243 → 245, 통나무 3 번, p03MP07 → camMinoDeado, LoadWad Pand03H.
- 스크린샷 (`work/shots/37c/`): `bmi_t600.png` (정면 BMI 다리 사이 크레토스), `bmi_mount.png`·`bmi_ride1.png`·`bmi_ride3.png` (등에 올라탐), `bmi_loghit.png`·`bmi_armor.png` (통나무 카메라, 갑옷 균열), `bmi_final.png` (격파 뒤 문).

### 10.1 통나무 투사체 / 증기 / 갑옷 조각 / HUD / 카메라 대상 (원작 해독, `BmiFight`)
| 항목 | 원작 | 엔진 |
|---|---|---|
| 통나무 | logLever-Anim-logArrow 1020(1000) → 레버 GO `gologlever` 의 ArrowEmitter/100 "BMILogLauncher" (Arrow Template 60, **Aim Joint logAim** = 레버 스켈레톤 관절 6) → Arrow/060: **GO Name golog** (MDL_log + 불 FXC_FWemit1), Velocity 150 (×16 /초), Gravity Flag 꺼짐 (직선), 수명 #117c872e 10초, Hit Effect **gologShatter** (MDL_logShatter 28관절 1회 애니 + 불·파편), Damage 1000 Context 456, Ignore Invulnerable [확실: TWK + 0x222CE0 등록 오프셋 + 0x222710 발사] | logAim 월드 (1184, 8, 1912), +Z = (−1, 0, 0) (BMI 앵커 선을 따라 문 쪽) 에서 2400/초. 이전→현재 선분이 BMI 피격 구(CDV_BMI)에 닿으면 적중 (피해 1000 → 단계 +1, 태그 456 → 210/245), 레벨 면에 닿으면 빗나감 (문 쪽 벽 x ≈ −150), 그 자리에 gologShatter. golog 를 이펙트 GO 로 그림 (메시 + 불) |
| 피한 통나무 | 999 → Dodge 220 BMIJUMPBACK → AUTO 221 TURTLESTUNIDLE. 스크립트는 LogMissCam → 2.5초 뒤 DrHitCam (문에 맞는 통나무) | 220/221/225/226 중에는 몸 판정을 건너뜀 [근사: 웅크린 몸 구가 통나무 높이(바닥 + 104)에 남아 있어 순수 기하로는 맞는다 — 원작 Arrow 충돌 0x223920 의 대상 거르기 미해석] |
| 앵커 | Think 첫 갱신 (플래그 0x10): +0x360 = sub_1A8ED0(GO) → sub_1A9E98 = 부모 로케이터 행렬 이동 [확실: 경로] | 스폰한 SpawnEnemy 엔티티의 소유 GO (gominoboss 관절 0 (0, 0, 1920)) [추정: 부모 = 스포너 소유 GO] — 스폰 위치 (64, −96, 1920) 와 정면 64 차이 |
| 배출구 증기 | 생성자 0x1C0378: steamAttach01..08 관절마다 **goSteamVent01** (FXC_ST2emit1/1A 200/초) 부착, 파티클 이미터 +0xB4 = 0. Think → 0x1C1D70: 열린 수 (0x1C1E40, 단계 0·1: 개수 0..5 → 0, 1, 2, 3, 5, 8, 단계 2+ → 0) 미만이면 1, 아니면 0. 소리 SND_MB_VENTSHOT (+ VENT%d_LP) | 8 이펙트를 관절에 붙이고 `rateOverride` 0 / 데이터대로. VENTSHOT 은 arena.sound, 반복음 미연결 |
| 갑옷 조각 | 생성자: 관절 표 0x2B7400 (BMI01JOLULN1, JOLHUM1, JORULN1, JORHUM1, JOCVEB1, JOCVEC1, JOCHEA1, JOCNEC1) ↔ GO 표 0x2B74B0 (goLeftArmShatterBRK, goLeftShouldBRK, goRArm2BRK, goRightShouldBRK, goAbdomenBRK, goChestBRK, goHelmetBRK, goCollarBRK), 숨김. 갱신 0x1C1B10 (단계 ≥ 2): 깨진 수 (0x1C1D00: 단계 2 개수 0..4 → 같은 수, 5 이상·단계 3 → +0x37C = SCR_BMIBrokenPieces, 기본 4, 232 에서 6 → 8) 이상인 조각은 애니 0초 + BMI 관절 행렬 복사 (보임), 미만은 그 자리에서 애니 재생, 끝나면 숨김 | 같은 규칙: 안 깨진 조각 = 관절 부착 + `Effect.hold` (0초 정지), 깨지는 순간 관절 행렬 고정 + 애니 재생, 수명 = act 길이 |
| 모델 변형 | 0x1C1AA8 (Think): 모델 컴포넌트 +0x120 = 단계 0 → 2, 1 → 1, 2+ → 0. MDL_BMI 파트 그룹 3개 = 갑옷 상태 (그룹 0 = 맨몸 본체, 1 = 금 + RayStreaks 빛줄기, 2 = 온전한 갑옷) | `EnemyFighter.modelVariant` → `AnimatedModel.setVariant`: 파트마다 그룹[v] (비면 그룹 0) — `SkinnedModel(…, variant=true)`. 예전 거리 LOD 규칙은 단계 0 부터 금·빛줄기가 보였다 |
| HUD EnemyState | 0x1C1958 → +0x380: set(a1=1): 단계 0 → 1, 1 → 3, 2 → 4 + 2·개수, 3 → 14 / 아니면 0 → 2, 1 → 4, 2 → 5 + 2·개수. 생성자·통나무·갑옷 문턱 = set, 배출구·갑옷 누적 = 아니면. Think 0x1C0B00: HUD +0x28 "EnemyState_Value1", +0x238 "EnemyState_Event1" (처음 1, 그 뒤 2), +0x1590 켬 [확실] | `BossFight.hud(UiVariables)` (GameScene levelHud 갱신에서) → PS2_EnemyState_Value1/Event1. FLP_Pand03G SetValue: 1 LargeArmor 켬, 2·4 BMI_Glow, 3 조각 1–5, 5.. 흔들림/깨짐 |
| 카메라 대상 | 생성자: sub_1F3F78(GO, 관절 BMI01JOCHEA1) → 슬롯 표 0x304790 (+0x4C 7, +0x48 가중 = "Camera Target Weight" 0.35, +0x40 = "Camera Target Radius" 2·16). CAM 0x10000 카메라의 대상 T (0x1F26E8) = bit0 슬롯 가중 평균 [확실] | `CameraRig.setTargetSlot` (BossArena.cameraTarget): 0x10000 카메라면 영웅 대상(가중 1)과 머리 관절(0.35)의 가중 평균. 반지름 미사용 |
| 기술 250 의 CAMERA 이벤트 #9E9C68F3 | 종류 23 = 0x2054A0: **goCamera** GO 를 만들고 act = Hash127(이벤트 해시 시드, "%d" 번호) 를 GO 애니에서 찾아 재생 (전투 카메라 애니). R_PERMK ANM_camera 에는 act "main" 하나뿐 → BMI 에서는 찾지 못함 [확실: 경로, 미확인: 이 해시의 act 이름 — PAND03G/R_PERMK 문자열 전수 대입 불일치] | 미구현 (VentCam 은 스크립트 1035(250) 가 켬) |

- 테스트 `BmiFightTest.logProjectileVentsHudCamera`: HUD 1 → 배출구 1 → 증기 1·HUD 2·Event 2·VENTSHOT, 5 개째 증기 8, 피한 통나무가 logAim (1184, 8) 에서 −x 로 날아 BMI 뒤 벽에서 gologShatter, golog GO 재생, 카메라 슬롯 = 머리 관절.
  `demoReachesScriptedEnd`: 단계 2 에서 모델 변형 0·조각 8 개 보임·증기 0·HUD 4, 갑옷 피해로 조각 깨짐, 결말까지 8 조각·HUD 14. 기존 통나무 적중 3 번·빗나감 테스트는 실제 투사체로 통과.
- 스크린샷 (`work/shots/`, 커밋 안 함): `bmi2_log2000.png` (LogHitCam: 통나무 적중 직후 단계 1 금 간 BMI + 파편 불꽃, HUD 갑옷 막대 금), `bmi2_armor5100.png` (단계 2 갑옷 BRK 조각을 두른 BMI),
  `bmi2_v360_nofx.png` / `bmi2_v360.png` (보스 이펙트 끔/켬 비교 — 증기 과노출), `bmi2_t30.png` (등장 전, HUD 갑옷 막대).
- **렌더 문제 (이 작업 밖)**: ① 증기(ST2part VU 3, 플래그 0x281 → 가산)·통나무 불이 크게 그려져 BMI 앞을 하얗게 덮는다 — 원작 생성률(200/초 ×2 ×8)은 데이터대로이고 PTC 크기 단위·블렌드 가정(40 §9)이 원인으로 보임.
  ② 아레나 밖 배경이 하얗다: FinalMpegStuff(SceneryFlatsOn 의 소유 GO)는 돌벽·바닥 메시 (MAT_M01StnL03Wall/Floor/WallTrim)일 뿐이고, 그것과 gofogmain·BMI 빛 FX 인스턴스를 숨겨도 그대로 → 배경판 탓이 아니다 (레벨 지오메트리/안개/클리어 쪽, 미해결).

## 11. 동기 애니 정렬 — 디스크 전체 조사와 원작 규칙

### 11.1 원작 [확실: 코드]
- 위치 정렬은 **0x204BF0 하나뿐** (§4.2). 0x205968 이 매 프레임 0x2441E0(+0x1AC, 0x24) 가 0 이 아닐 때 (0x20·0x4 **중 하나라도** — 0x205B28 `beql v0, zero`)
  부르고, 0x206030 이 프레임마다 +0x1AC 를 0x5400 으로 지운 뒤 **창 안 이벤트만 다시 켜므로** 정렬은 CombatFile 이벤트 22·19 의 창 [start, end] 동안만이다
  (2026-10-20 정정: 예전 "0x24 가 모두 있을 때" 는 틀림). synchJoint 인덱스(루트 모션 컴포넌트 +0xEA)를 읽는 곳은
  0x1F7110 (덮어쓰기)·0x204BF0·0x2054A0 (이벤트 23 카메라)·0x214058 (다른 오브젝트) 뿐 — 다른 정렬 경로는 없다.
- 이벤트 19 (FLAG_4, 0x206B44): 자기 |= 0x4 → 이벤트 22 와 같은 꼬리 0x206BA8 (상대 |= 0x4000). 동기 유지 (0x2050D8/0x209918: 0x4004 이고 상대 기술이 끝나면
  자기 기술도 끝) 에 더해 **0x4 가 위 0x24 검사에 걸리므로 이벤트 22 처럼 끈다** [확실: 코드] (§11.5). 차이는 루트 모션 0x1EBFB4 뿐: 0x20 (이벤트 22) 이면
  끄는 쪽 변위 = 0 + D, 0x4 만이면 변위 시작값 = 상대 combatant +0x10 (= 상대의 자유 이동 충돌 보정량, §11.6).
  Enemy1 갱신 0x1EBFAC/0x1EC048 은 자기 또는 상대가 0x24 (끌기 중) 면 자기 내비 방향 갱신을 건너뛴다.
- 이벤트 22 는 **끄는 쪽** 기술에 있다: 그 combatant 의 동기 상대가 끌려온다. 영웅 기술에 있으면 영웅이 적을 끈다 (역방향).
- 비율 +0x1BC = 0.1 은 기술마다가 아니라 **동기 연결이 새로 생길 때** (0x209830: 플래그 0x20 기술 시작 + 빈 +0x84) — 히드라 806 → 807 → 809 는 한 연결.

### 11.2 전수 조사 (`combat:dump` 전 WAD 64 개, 이벤트 22 = `FLAG_24`) [확실: 데이터]
| 파일 | 기술 (창) | 끄는 쪽 → 끌리는 쪽 |
|---|---|---|
| 252 히드라 (ATHN01E, R_AH*/R_AMI) | 802 BHABOSSGRABRIGHT, 806 …LEFT, 810 BHABOSSKILL [0–1] | 보스 → 영웅 (§4.2) |
| 604 아레스 1단계 (ATHN08A) | 600 BARMAIBLADESHITTHROW [0–0.35], 608 MAIBARGRABWIN [0–0.52] | 보스 → 영웅 |
| 002 영웅 (R_PERMK, ATHN08A/C 사본) | 221 MAISKB02 [0–0.76], 421 MAISIRKILL [0.44–0.91] | 영웅 → 적 (해골병 SKB, 세이렌) |
| 254 아기 히드라 (ATHN01C) | 821–824 BHAENC2BITE* [0–0.78/0.90], 825 BHABABYGRAB [0–0.22] | 적 → 영웅 (§11.4 연결) |
| 305 침대 (ATHN01F) | 846–853, 880–887 MAIXPTBEDMOUNT* [0–1] | 미니게임 오브젝트 → 영웅 (§11.4 연결) |

이벤트 22 가 **없는** 동기 (이벤트 19 만): 아레스 605/606/607/609, 칼 맞대기 620–623, 가족 포옹 865–867, 분신 잡기 860–863, BMI 올라타기 450–455,
메두사 여왕·고르곤 260/261/263/264, 사이클롭스 240–249/280–283, 미노타우로스 400–403, 하피 270, 사티로스·켄타우로스 … → 예전 결론 "원작은 이 구간에 위치를 맞추지
않는다" 는 **틀림** — 이벤트 19 창도 0x204BF0 으로 끈다 (§11.1, §11.5). 엔진: 일반 적 처형 (`GrabSync`)·보스 (`BossSync`, §11.6) 모두 적용.

### 11.3 엔진 (`combat.SyncPull`)
- `BossSync`·`GrabSync` 모두: 보스/적 기술의 이벤트 22 창 → 영웅을 끈다, 영웅 기술의 창 → 적을 끈다 (목표 = W_자기 · S_자기 · S_상대⁻¹, 요 slerp·위치 lerp, 비율 0.1 ×1.1).
- 아레스: 동기 쌍 = 600, 605–609, 620–623 (예전 605–625 는 아레스 610 BARSTANDIDLE ↔ 영웅 610 HITLAND 를 묶었다). 정렬 NONE + 이벤트 22.
  끌기가 없는 프레임은 영웅 **수직 루트 모션** (zeroJoint y 변화량) 을 `BossSync` 가 적용 — 동기 중 물리가 꺼져, 608 창 끝 (영웅 y +30) 뒤 크레토스가 떠 있던 것을
  애니대로 (zero y 62 → 0) 착지시킨다.
- 측정 (헤드리스, 손목 ↔ 아레스 관절 평균): 0x204BF0 관계 = 605/606 에서 아레스 정면 31·마주 봄 (2–4), 608 창에서 정면 z −28 → −3·높이 +30 (1.6–12, 매달려 찌름).
  예전 "같은 원점·같은 방향" (BOSS_TO_HERO) 은 1–24 로 흔들렸고 608 끌기와 이어지지 않는다. 예전 표의 "synch 행렬 통째 (64 앞 마주 봄) 25" 는 synchJoint 를 **zeroJoint 기준**으로
  바꾸지 않은 계산이었다 (원작 0x1F7110 은 S·Z⁻¹).
- 이벤트 22 가 없는 동기: 예전 엔진 대체 규칙 (`BossSync.Align` SYNCH / BOSS_TO_HERO / ZERO, `GrabSync` 같은 기준점) 은 **삭제**했다 (§11.4).
  원작처럼 잡기 적중 자리 + 두 쪽 루트 모션 (이동·회전) 만으로 관계가 생긴다.

- 세이렌 처형 421: 영웅 기술 창 [0.44–0.91] 동안 영웅이 세이렌을 끈다 → 세이렌이 영웅을 마주 봄 (`GrabTest.sirenKillPullsEnemy`). 그 밖 (창 앞) 은 적중 자리 그대로.
- 시험: `AresFightTest.grabAlignmentTargetSynch` (606 이벤트 19 끌기·아레스 제자리·영웅 정면 마주 봄 (§11.6), 608 창 안에서만 끌기, 정면·높이 +25 이상·마주 봄, 손목 평균 < 6),
  `bladesThrowPullsHeroInWindow` (600 창 [0–0.35]), `HydraFightTest` (영웅 y = 보스 + 682), `GrabTest.sirenKillPullsEnemy` (MAIMIN* 은 끌기 0).
- 스크린샷 (`/Users/obeomsu/.claude/jobs/6f65672f/tmp/sync/`, 커밋 안 함): `ares_t450.png` (606: 정렬 없이 정면 40 에서 아레스 손을 맞잡음),
  `ares_t760.png` (608 창: 아레스에게 매달려 칼날로 찌름, 피), `ares_t860.png` (창 뒤 영웅 루트 모션으로 뛰어내림), `medusa_t900.png` (MAIMEWGRAB 머리 잡기),
  `cyclops_t120/220/320.png` (`--hero --auto grab --enemy R_CY2`: MAICYWMini02 → 등에 올라탐 → QTE 실패로 내던져짐).
- BMI: 영웅 BMIRide* 애니는 몸이 제자리이고 zeroJoint 가 높이 212 까지 기어오른다 → 이제 수직 루트 모션으로 그대로 올라간다 (ZERO 규칙 삭제).

### 11.4 루트 모션 0x1F7110 → 0x1EBD78 (영웅·적·보스 공용) [확실: 코드]
- **애니 컴포넌트** (생성 0x1F7C30, 생물 생성 0x1E7B90 이 생물 +0x328 에 저장; vtable 0x2BAF5C → 0x1F7F38 → **0x1F7110**):
  +0xE8 = zeroJoint 인덱스, +0xEA = synchJoint 인덱스 (Hash127Upper 이름 → 0x138360), +0xEC = 모델 행렬 첫 행 길이 (= 오브젝트 배율, 0x1F7D64),
  +0xC8 = 프레임 카운터 (0x1F7408 이 새 애니 시작 때 0, 0x1F7E48 초기화는 델타 = 단위), +0xCC.. = 애니 층 (가중치 +0x18·+0x1C·+0x24 곱이 가장 큰 층을 씀).
- 0x1F7110 매 프레임: 카운터 +1 → Z = 그 층의 zeroJoint 행렬 (0x116708), Z⁻¹ (0x185550). 카운터 ≥ 2 면 **D = Z · Zprev⁻¹** (행벡터; 열벡터로 Zprev⁻¹·Z)
  를 +0x50..+0x80 에 저장, 아니면 (새 애니 첫 프레임) **직전 D 유지**. 두 경우 모두 D 이동 행 × +0xEC. Zprev⁻¹ = +0x10..+0x40 에 Z⁻¹ 저장.
  그 뒤 모델 관절 행렬 [zeroJoint] = 단위, [synchJoint] = S · Z⁻¹ (0x204BF0 의 synch 기준, §4.2).
- **0x1EBD78** (영웅·적 공용 생물 갱신, 0x1EBF04): combatant (+0x324) 에 **기술이 있을 때만** (+0x4C ≠ 0) D 를 읽는다.
  동기 기술 (기술 플래그 +0x0E & 0x60) 이면 동기 상대 (+0x84) 를 풀어 — 자기가 끄는 중 (0x2441E0(+0x1AC, 0x24) 참이고 0x20) 이면 그대로 적용,
  **상대가 끄는 중이면 D = 단위** (0x1EC058: 끌리는 쪽은 루트 모션 없음).
  적용: 오브젝트 행렬 W (+0x50..) 를 W' = D · W — **회전 행은 W 에 바로 저장**, 이동은 (W'.t − W.t) 를 변위 (sp+0x3C0) 에 더하고 W 이동 행은 옛 값 유지
  → 변위는 내비게이션 이동 (지상 0x1EB3D8 / 0x1EAEC8 / 0x1C88F0, 줄 0x1CA448) 에 넘어가 벽·바닥 처리를 거친다.
- 데이터로 확인 (헤드리스 덤프): 몸통은 제자리이고 zeroJoint 만 움직인다 → 루트 모션으로 오브젝트가 움직이면 **월드 synchJoint 가 고정**된다
  (Z⁻¹·S 에서 Z 변화가 W 이동과 상쇄). 예: 영웅 maiMin01 zero z 4 → −88 (앞으로 92), synch 상대값 −18 → +74; 영웅 maiMewGrab zero 요 0 → 180°;
  사이클롭스 처형 영웅 MAICYWMini02 zero 높이 0 → 74 (등에 올라탐), 적 쪽 zero z 0 → 105. MAIEVIHug* 는 두 쪽 zero 고정.
- **층 목록** [확실: 코드]: +0xCC.. 은 애니를 틀 때 (전투 0x1F6FC8, 내비 0x1F7408) 0x1006F8 → 0x107200 이 채운다 — 채널 블렌드 노드 중 **새 애니와 같은 act**
  를 가리키고 페이드아웃·종료 (+0x10 / +0x2C & 0x90) 가 아닌 층만. 크로스페이드로 사라지는 옛 애니는 목록에 없으므로 "가장 무거운 층" 은 새 애니의 인스턴스
  (블렌드 그룹 등) 중 고르는 것이고 옛 클립 → 새 클립 zeroJoint 도약은 없다. 엔진의 "현재 클립" 이 곧 이 목록 (인스턴스 하나) 이라 바꿀 것이 없다.
  (가설 "크로스페이드 앞 절반 = 옛 층" 을 시험했지만 미노타우로스 axeAttack2 — zero z −228 상수, 골반 0 — 가 274 순간이동해 기각.)
- **엔진** (`anim/RootMotion`): 현재 클립의 zeroJoint 모델 행렬로 같은 D (첫 프레임 유지, 루프가 돈 프레임은 끝→처음 이어 붙임 [근사],
  `Animator.playSerial` 이 새 재생 = 카운터 리셋). 변위 = R(요)·(배율 · D.t), 요 변화 = D 회전의 요.
  - 영웅 (`HeroController.applyRoot`): 전투 기술 중 수평 변위 + **요 회전** (예전: 수평 zero 차이만, 회전 없음, 루프 클립 제외, 첫 프레임 0).
    수직 변위는 `rootDy` 에 남기고 동기 중 (물리 꺼짐) 에만 더한다 (`BossSync`, `GrabSync`).
  - 적·보스 (`EnemyFighter.rootMotion`): 전투 기술 중 수평 변위 + 요 회전 (예전: 없음 — 적은 기술 중 제자리). 변위의 벽 처리는 같은 틱 `EnemyNav.settle`
    (10 §7.2), 바닥은 다음 틱 `EnemyNav` 지상 처리. **수직**: 기술 stateMask & 0x1C == 0 (stateMask 1/3 — 처형 MAI*, 띄우기 등) 이면 원작 0x1ECD60 경로 = 중력 없는 3D 변위
    (0x1E5F90) + 충돌 0x1EA200 (밀기는 stateMask 비트 2 일 때만, 바닥 아래만 막음) → 엔진 `EnemyNav.free` + 수직 변위 적용. stateMask 7 은 지상 이동이
    바닥에 붙이므로 버린다 (32 문서 §기술 중 루트 모션). BMI 의 자체 zeroJoint 이동 코드는 공용으로 대체.
    `rootHold` = 상대가 끄는 중 (0x1EC058), `rootMotionOff` = 클래스가 위치를 직접 정할 때.
  - 끌리는 쪽: 원작 0x1EBF8C 는 상대 이벤트 창을 같은 프레임에 0x206030 으로 갱신한 뒤 0x2441E0 을 보므로 **끌리는 첫 프레임부터** D = 단위.
    엔진은 두 쪽 갱신이 끝난 뒤 끌기를 알게 되므로 그 틱의 루트 모션을 되돌린다 (`HeroController.undoRoot`, `EnemyFighter.undoRootMotion` —
    `BossSync`·`GrabSync`·결합 없는 끌기 모두). 예전 `BossSync` 의 "한 틱 늦음" [근사] 해소.
  - 동기 중 영웅 수직 하한 = 동기 시작 높이 [근사: 원작은 지상 이동이 바닥에 붙임 — BMI 실패 454 는 zero 가 바닥 아래로].
  - 동기 (`GrabSync`) 중 영웅 수평 변위도 벽 처리: 원작은 위 변위가 내비게이션 이동 (벽 0x1E2100 · 이동 선분 0x1EAB78) 을 거친다 → `GameScene.grabWalls`
    (몸 구 밀어내기 + 틱 시작 → 지금 선분, 장면 물리와 같은 순서). 예전엔 결합 중 물리를 건너뛰어 벽 가에서 해골병을 끄는 동안 영웅이 벽을 지나
    바닥 없는 바깥으로 떨어졌다 (ATHN05D 엘리베이터 방, 43 §21.2-7, `GrabWallTest`). 끌리는 적도 같은 틱 벽 처리 (`EnemyFighter.settleMotion`, 10 §7.2) [근사: 이벤트 19 끌기의 +0x10 되먹임 없음].
- 대체 규칙 삭제: `BossSync(IntPredicate)` (Align 없음), `GrabSync` 의 "적 = 영웅 위치·방향" 삭제. 결과 (헤드리스, 두 synchJoint 월드 거리 = 설계 관계와의 차이):
  메두사 MAIMEWGRAB 4.0 (영웅이 루트 회전으로 0 → 150° 돌고 synch 고정), 사이클롭스 MAICYWMini02 5.1 (F1 실패 분기 13), 하피 WingRip 11,
  미노타우로스 maiMin01 36 (적중 거리 40 에서 시작 — 설계 관계는 정면 70.4) → §11.5 (적중 거리 해독: 멀리서 누르면 70.7 에서 잡혀 0.5).
  가족 포옹: 두 zero 고정 → 영웅은 잡은 자리 그대로. 분신 잡기 861: 영웅–분신 43 (분신 EVIGrab 이 앞으로 64 나아가 잡음).
- **연결 안 됐던 이벤트 22 동기**:
  - 아기 히드라 ATHN01C (AI1 252, Instance Name "hydra enc2" 와 같은 오브젝트가 없어 대역으로 나오던 것): `EnemyFactory` 가 이름 오브젝트가 없으면
    **CombatFileSet 기술 애니를 가장 많이 가진 스켈레톤** (bhb) 을 고른다 [추정]. BITEGRAB(8) 적중 → 영웅 006 A821 (태그 821, 공격자 252) → 821.
    `GrabSync` 결합 조건에 "적 기술 플래그 0x20 + 같은 기술 ID" 추가 (유지 = 적 기술 플래그 0x60, 원작 0x2050D8), 821–824 창에서 적이 영웅을 끈다.
    825 BHABABYGRAB [0–0.22] 은 영웅 기술과 무관 (영웅은 COMBO5A → GRABREJECT) → **결합 없는 끌기** (`GrabSync.lonePull`, 플래그 0x60 기술의 창).
    끄는 쪽 = **공격 대상이 영웅인 적** [확실: 0x2097A8 — 플래그 0x20 기술 시작 때 +0x84 가 비어 있으면 +0x84 = +0x80 (공격 대상), 기술 플래그 0x8 이면
    +0x80 = 0, 상대 +0x84 = 자기]: 가장 가까운 적이 아니다. 엔진 `GrabSync.setPullers` (GameScene = 적 전부) 중 이미 끄는 적, 없으면 조건을 만족하는 첫 적
    (`GrabRelationTest.lonePullUsesAttackTargetNotNearest`: 가까운 아기 히드라는 대기, 먼 쪽 825 가 영웅을 끈다).
  - 침대 ATHN01F (AI1 305 aoxpt): MAIXPTBEDMOUNT* 는 MAI 이름이라 기존 결합 + 적 기술 창 끌기로 동작 (정렬만 — 미니게임 흐름은 그대로).
- 시험 `combat.RootMotionTest`: 적 공격 변위 = R(요)·Δzero·배율 (미노타우로스), 메두사 잡기 루트 회전 > 126°·synch 고정 < 6·적을 영웅에 겹치지 않음,
  아기 히드라 821 끌기 (목표 오차 < 2), 침대 846 끌기. `EvilKratosFightTest` 포옹·분신 잡기 = 정렬 없음 (제자리) 으로 바꿈.
- 스크린샷 (`/Users/obeomsu/.claude/jobs/6f65672f/tmp/sync2/`, 커밋 안 함, `--hero --auto grab --enemy R_xxx --no-fx`, 틱 60/120/200/300/420):
  `before_*` (예전 기준점 규칙) / `after_*`, 비교 시트 `sheet_R_CY2/R_MIN/R_HAR/R_MEW.png` (위 = 이전, 아래 = 이후). `HeroTestScene` GRAB 은 이제 서로 마주 보게 둔다
  (예전 배치는 등을 지고 있었는데 기준점 규칙이 가렸다).

### 11.5 잡기 적중 거리와 설계 관계 (일반 적 처형) [확실: 코드 / 데이터 계산]
- **원작은 이벤트 19 창으로 맞춘다** (2026-10-20 정정). 오브젝트 위치를 바꾸는 곳은 루트 모션 (0x1EBD78) 과 0x204BF0 (끌리는 쪽 combatant +0x00 변위 — 0x205088)
  뿐이고 combatant 변위 +0x00/+0x10 을 쓰는 다른 곳은 초기화 0x2030B8 뿐. 동기 연결 0x209790–0x2098B4 도 +0x84/+0x1BC 만 (위치 없음). 처형 애니를 트는 0x1F6FC8 도 층만.
  그런데 0x204BF0 을 부르는 조건 0x205B28 은 +0x1AC & 0x24 ≠ 0 이고, 처형 기술의 **이벤트 19 (FLAG_4, 0x206B44 → +0x1AC |= 0x4)** 가 거기 걸린다 [확실: 코드]
  (예전 조사는 "0x24 가 모두" 로 읽어 이벤트 22 만 셌다). 결합 처형 11 종 전부 한쪽에 이벤트 19 창이 있다 — 끄는 쪽 = 그 창을 가진 기술 (이벤트 22 와 같음):
  사이클롭스 적 240 MAICYWMINI02 [0–0.86] → 영웅, 하피 영웅 270 MAIHARWINGRIP [0–1] → 하피, 그 밖 (미노타우로스 400, 고르곤 260/263, 세이렌 421, 사티로스 431,
  해골병 220, 켄타우로스 291, 케르베로스 286/395) 도 창이 있어 0.5 초 뒤 오차 0 (헤드리스, 시작 거리 40/60/80 모두).
  `SyncPull.linkedPullWindow` (이벤트 22 또는 19) → `GrabSync.align`, 보스 동기 `BossSync` 도 같은 규칙 (§11.6).
- **GRAB 적중**: 영웅 200 GRAB 의 공격 = 부위 1 (몸 구, [0.05–0.40]) · 부위 9 (extra1 구 정면 z −20 r27, [0.27–0.32]), dmg 0. 거리 조건 없음 —
  0x13B688 이 두 오브젝트의 **모든 구 쌍**을 0x13C400 로 시험하고 (콜백 0x203150 이 0xB 를 돌려 계속), 0x203AF0 → 0x209AB0 이 공격 부위만 비교.
  그 프레임 HIT_CONFIRM → 처형 기술 (GRAB 분기: 상대 종류·HP·잡기 가능 0x10). GRAB 애니는 앞으로 나간다 (zero z 1.8 → −26.5 (0.3) → −30.7).
  GRAB 의 SCRIPT 0x8CACF07D = SCR_HideBerserkBlade (접근·스냅 아님).
- 결합 순간 관계 = **처음 닿은 자리**. 설계 관계 (처형 0 프레임 synchJoint 일치, `GrabRelation.relation`) 는 대부분 **멀리서 ○ 를 눌러 GRAB 이 달려들고
  부위 9 창 [0.27–0.32] 에서 처음 닿는 거리**와 같다 (아래 표 "결합 오차" = 끌기 전, 헤드리스 `GrabRelationTest.designedStartPerEnemy`) — 이때는 끌림이 거의 없다.
  가까이서 누르면 부위 1 이 바로 닿아 결합 순간은 그 차이만큼 어긋나고 (미노타우로스 40 에서 ≈ 30), 이벤트 19 끌기 (비율 0.1 → ×1.1, 약 0.4 초) 가 메운다
  (`tooCloseIsPulledIntoRelation`: 0.5 초 뒤 < 1).

- **정정 (2026-10-24, ANM 디스크립터 배치 05 §2.8.1, 43 §20)**: 설계 관계의 zeroJoint · synchJoint 는 처형 애니의 관절 이동 (타입 0 디스크립터 1) 을 받는다. 고르곤 · 세이렌 ·
  케르베로스 · 하피 세트는 데이터 타입이 [0(p2 3)] / [0, 10 …] 이라 예전 항목 번호 배치는 이동을 하나도 읽지 않았고 (zero/synch = 바인드), 그 설계 거리 (고르곤 37.7 · 세이렌 45.9 ·
  새끼 케르베로스 42.2 · 케르베로스 43.3 · 하피 7.9) 는 착시였다. 원작 배치로 다시 잰 값이 아래 표 — **열 종류 모두 GRAB 첫 접촉 (CDV 구 판정, 설계 관계와 독립) 이 설계 관계와
  0.7 안에서 맞는다** (예전: 고르곤은 부위 9 창에서만, 하피는 캡슐 밀기로 8.8 밖) [확실: 코드 경로 + 데이터]. 하피는 더 이상 캡슐에 막히지 않는다 (23.9 > 캡슐 합 19.6).

| 적 (처형) | 설계 관계 (적 정면, 마주 봄) | ○ 시작 거리 | 적중 GRAB 진행률 | 결합 거리 | 결합 오차 (끌기 전) |
|---|---|---|---|---|---|
| 미노타우로스 R_MIN (400 MAIMIN01) | 70.4 | 99 | 0.29 (부위 9) | 70.7 | 0.5 |
| 고르곤 R_MEW / R_ME2 (260 / 263 MAIMEWGRAB) | 26.4 (예전 37.7) | 33 | 0.07 (부위 1) | 26.4 | 0.2 |
| 세이렌 R_SIR (421 MAISIRKILL, HP ≤ 10) | 21.7 (예전 45.9) | 28 | 0.07 | 21.5 | 0.15 |
| 사티로스 R_SAT (431 MAISATGRAB) | 37.4 | 66 | 0.29 | 37.7 | 0.5 |
| 해골병 R_SKS (220 MAISKB00) | 27.6 | 44 | 0.16 (부위 1) | 27.6 | 0.3 |
| 켄타우로스 R_CNM (291 MAICNMKILL) | 40.6 | 67 | 0.28 | 40.7 | 0.4 |
| 케르베로스 새끼 R_CEP (286 MAICEPSTOMP) | 34.8 (예전 42.2) | 59 | 0.25 | 34.3 | 0.65 |
| 케르베로스 R_CEA (395 MAICEAKILL) | 44.2 (예전 43.3) | 51 | 0.07 | 44.6 | 0.5 |
| 사이클롭스 R_CY2 (240 MAICYWMINI02) | 29.7 | 50 이상 | 0.07 | ≈ 43 (캡슐 경계) | 이벤트 19 끌기 → 0 |
| 하피 R_HAR (270 MAIHARWINGRIP, 뒤에서·같은 방향) | 23.9 (예전 7.9) | 31 | 0.07 | 23.8 | 0.1 |

  **사이클롭스 — 몸 캡슐과 밀기** (32 문서 §생물끼리 밀어내기) [확실: 코드] (하피는 위 정정으로 해당 없음 — 예전 배치의 7.9 가 만든 경우였다):
  - 캡슐 합 (크라토스 9.6 + 사이클롭스 48 = 57.6, + 하피 10 = 19.6) 이 설계 거리 (29.7 / 예전 하피 7.9) 보다 커서 GRAB 은 캡슐 경계에서 닿는다 (밀기를 GRAB 동안 끄는 장치는
    없다: 밀기 끔은 Sold +0x1C 비트 1·BMI Think 뿐, 콜백은 상대 기술 stateMask & 2 만 — GRAB 7, 잡기 원 M140 HITSTAGGER 7, 대기 0x1F).
  - **메우는 것은 이벤트 19 끌기** (위): 사이클롭스가 영웅을 [0–0.86], 영웅이 하피를 [0–1] 설계 관계로 끈다. 처형 MAI* (stateMask 1) 가 시작되면 양쪽 모두 밀지 않으므로
    끌기를 막는 것도 없다. 결과 (원작 밀기 규칙 그대로, `GrabRelationTest.capsuleLimitedFinishers`): 오차 27.3 / 10.2 → 0.5 초 뒤 < 1 (시작 40/60/80 모두),
    밀기를 빼면 (비교용) GRAB 첫 접촉이 그대로 설계 관계 (0.5 / 0.2).
  - 스크린샷 (`/Users/obeomsu/.claude/jobs/6f65672f/tmp/grab4/`, 커밋 안 함): `R_CY2_t40…t330` (t240 = 쓰러진 사이클롭스 등 위 — 밀기 없던 grab2 와 같은 장면,
    grab3 은 머리 뒤에 걸림), `R_HAR_t40/t80` (하피 등에서 날개 뜯기), 나머지 9 종 `R_MIN_t40/t200`, `R_MEW/R_ME2_t90`, `R_SAT_t100`, `R_SKS_t80`, `R_SIR_t80`,
    `R_CNM_t90`, `R_CEP/R_CEA_t60` (변화 없음).
  - 조사 중 찾은 밀기 세부 (32 문서, 엔진 적용): 서 있는 적은 자기를 밀지 않는다 (0x1EB3D8 끝 정지 판정 — 플레이어는 없음), 밀기 후보 = 질의 구와 겹치는 상대 CDV 구 (0x175648).
    적용 뒤 서 있는 사이클롭스는 자기를 밀지 않아 시험 시작 거리를 캡슐 밖 (50–130) 으로 좁혔고, 결합 거리 ≈ 43 (CDV 후보 때문에 경계 45.6 보다 조금 안쪽) — 이벤트 19 끌기로 오차 ±1 은 그대로.
    관절 상자 0x1E6DF0 은 플레이어만 (+0x174 비트 31) 이라 보스 (BMI·히드라) 의 머리 관절 상자는 없다 (32 문서 §생물끼리 밀어내기 2).
- 엔진 변경: 대체 정렬 없음 — 이벤트 19/22 끌기 (원작대로). `GrabRelation` = 설계 관계·처형 기술 조회·시작 거리 헤드리스 탐색 (결합 순간 오차 `contact` 최소,
  5 단위 훑기 + ±4 1 단위, 적 하나 100–250 ms), 0.5 초 뒤 오차 `error`.
  모의는 원작 밀기 규칙 (영웅 `BodyPush.HeroBody` 머리 관절 상자, 적도 움직일 때 영웅 밖으로 자기를 밂, 지상은 밀린 뒤 높이 유지) — `run(…, push=false)` 는 비교용.
  `HeroTestScene` 도 예전 단순 구 분리 대신 같은 `BodyPush`. 나머지 9 종은 밀기를 넣어도 위 표 그대로 (설계 거리 > 캡슐 합).
- 스크린샷 (`/Users/obeomsu/.claude/jobs/6f65672f/tmp/grab3/`, 커밋 안 함, 이벤트 19 끌기 적용 전): `R_CY2_t40/t240` (원작 밀기 규칙만 — 크레토스가 등에 못 올라탐),
  `R_HAR_t40/t80`, `R_MIN_t40`. 적용 뒤는 위 `grab4/`.
  `HeroTestScene --auto grab` 은 이 거리에 적을 둔다 (AI 정지 `EnemyFighter.holdAi`). 예전 "적중 거리 40" 은 시작 32 + GRAB 루트 모션 없이 부위 1 이 바로 닿은 것.
- **결합 중 영웅 전투 갱신** (원작: 동기 중에도 영웅 Combat_Update): `GrabSync` 가 결합 중 영웅 `Combatant.update` 를 돌린다 → 입력 분기 (해골병 MAISKB00
  [0.4–1] □ → 221 MAISKB02, 이벤트 22 로 영웅이 해골병을 끌어 꿰뚫음), 끝 AUTO (사티로스 431 → 435). **같은 ID 따라가기** (원작 SYNC(18) 분기): 적이 새 기술
  (실패 분기·Pass/Fail Move) → 영웅도 같은 ID, 영웅이 새 기술 → 적도 같은 ID (`GrabSync.follow`). 영웅이 이끄는 동기 (영웅 기술 플래그 0x20, 사티로스
  431 의 적 쪽 플래그 0x40) 는 적 기술이 먼저 끝나 끊긴 뒤 영웅 새 기술 시작 때 다시 묶는다 (0x2097A8). 영웅 기술의 연타 (사티로스 432/435 SCR_RepeatPress
  idx 130/131, 템플릿은 적 WAD) 도 `RepeatPressQte`.
- **전환 시간**: `Animator` 기본값 = act +0x04 (0x116BB0 이 TWK `<클립>_Tween` 으로 덮어쓰는 칸 — 적 WAD 의 영웅용 처형 애니는 TWK 가 없어 act 0.2) [확실: 코드].
  act 값이 1 을 넘는 적 애니 (2.0 / 5.0, 단위 미확인) 는 예전 기본값 [근사].
- 미니게임 자동 입력 (`HeroTestScene`, `boss.AutoPad`): 타이밍 = 그 버튼 / 스틱 회전 = 사분면 순서, 연타 = 0.1 초마다. 해골병 처형은 □ 를 누른다.
  결과 (헤드리스 로그): 사이클롭스 MAICYWMini02 타이밍 4/4 성공 → 등에 올라타 눈 찌름 → 쓰러짐, 미노타우로스 연타 → 402 MAIMIN03,
  고르곤 타이밍 2/2, 사티로스 431 → 435 연타 PASSED → 429 MAISATKILL, 해골병 □ → 221 → 사망.
- 스크린샷 (`/Users/obeomsu/.claude/jobs/6f65672f/tmp/grab2/`, 커밋 안 함, `--hero --auto grab --enemy R_xxx --no-fx --ticks N`): `R_MIN_t40` (부위 9 창에서 뿔을 잡음),
  `R_MIN_t200` (가슴 위 연타), `R_MIN_t380` (402 성공), `R_CY2_t240` (등에 올라타 찌름), `R_CY2_t330` (쓰러진 사이클롭스), `R_MEW_t40/t90/t150`, `R_SAT_t100/t200/t330`,
  `R_SKS_t80/t120`, `R_SIR_t40/t80`, `R_CNM_t40/t90`, `R_HAR_t40/t80`.

### 11.6 보스 동기의 이벤트 19 끌기 (`BossSync`) [확실: 코드 / 데이터]
- **0x1EBF28–0x1EC0A0 해독** (생물 갱신 0x1EBD78 안, 기술 플래그 & 0x60 일 때): 변위 sp+0x3C0 은 0x1EBEEC 에서 0. s1 = 동기 상대 오브젝트 (+0x84 핸들), 자기 combatant 의
  이벤트 창을 0x206030 으로 갱신한 뒤 0x2441E0(+0x1AC, 0x24):
  - 자기가 끄는 중 & 0x20 (이벤트 22) → 변위 0 + D (자기 루트 모션 그대로).
  - 자기가 끄는 중 & 0x4 만 (이벤트 19) → 변위 = **상대 combatant +0x10** (0x1EBFE0, 상대 vtable +0x60/+0x64 = combatant) + D.
  - 끄지 않음 → 변위 = 자기 combatant +0x00 (0x204BF0 이 0x205088 에 쓴 끌기 변위), 상대가 끄는 중이면 D = 단위 (0x1EC058).
  - 끝에 0x1EC2A0 이 자기 +0x00·+0x10 을 0 으로 지우고, 자유 이동 경로 0x1ECD60 이 **+0x10 = 충돌 뒤 변위 − 요청 변위** (0x1ECE44: 0x1EA200 결과 − 0x1E5F90 결과) 를 쓴다
    (지상 이동 경로는 쓰지 않음 → 0). 다른 쓰기는 초기화 0x2030B8 뿐. 즉 이벤트 19 로 끄는 쪽은 끌린 상대가 벽·바닥에 막혀 깎인 만큼 자기도 따라 움직여 둘이 떨어지지 않는다.
    엔진의 끌기는 충돌 없이 위치를 정하므로 이 항은 0 [근사: 차이 없음].
- **0x204BF0 (자기 = 끄는 쪽 combatant s0, 상대 s4 = 0x207B38 — 서로 +0x84 로 연결된 경우만)**: 두 모델의 [synchJoint] 행렬 (0x1F7110 이 S·Z⁻¹ 로 덮어쓴 것) 위치 × 배율
  (+0xEC) → 목표 = S_상대⁻¹ · S_자기 · W_자기 (0x185550 역행렬, 0x1853E8 곱). 회전: 0x1840D8 사원수 → 0x184388 slerp (비율 = **상대 combatant +0x1BC**) 로 상대 W 회전 행을 바로 덮어씀.
  위치: (목표 − 상대 현재) × 비율 → 상대 combatant +0x00 (0x205088). 비율 ×1.1, 최대 1 (0x2050A0). 0x209830: 연결이 새로 생기면 자기·상대 +0x1BC 모두 0.1.
  → 엔진: 비율을 끌리는 쪽마다 따로 (`BossSync.heroPull` / `bossPull`).
- **synch 기준 = 가장 무거운 층 단독** [확실: 0x1F7110]: [synchJoint] = 0x116708(층 s0, synchJoint) · Z⁻¹, 층 s0 = 가중치 곱 최대 층 (새 애니). 엔진 `SyncPull.synchRel` 이
  섞인 자세 (`Animator.pose`) 를 읽던 것을 **현재 클립 단독 표본**으로 고침 — 포옹 865 첫 0.2 초에 GRAB → 포옹 페이드로 영웅 synch 방향이 돌아 목표가 가족 뒤까지 흔들리고 영웅이 70° 휘청였다.
- **보스 데이터** (`combat:dump`, FLAG_4 = 이벤트 19 / FLAG_24 = 22, 끄는 쪽 → 끌리는 쪽):

| 싸움 | 보스 쪽 (창) | 영웅 쪽 |
|---|---|---|
| 히드라 252 (R_AHA, ATHN01E) | 802/806/810 (22) [0–1], 803/804/807/808/811/812/813/816 (19) [0–1], 805/809 (19) [0–0.55] | 006 의 802–816 없음 |
| 아레스 604 (ATHN08A) | 600 (22) [0–0.35], 608 (22) [0–0.52], 605/606 (19) [0–1], 607 (19) [0–0.6], 609 (19) [0–0.4] | 002 의 600–609 없음 |
| 아레스 605 (ATHN08C) | 621 (19) [0–1], 622 (19) [0–0.4], 623 (19) [0–1] | **620 (19) [0–1] → 영웅이 아레스를** |
| 가족 662 (ATHN08B) | 865/866 (19) [0–1], 867 (19) [0–0.4] | 006 의 865–867 없음 |
| 분신 668 (ATHN08B) | 860/861 (19) [0–1] (862/863 없음) | 006 의 860–863 없음 (864 MAIEVIKILL 은 영웅 [0–0.5] — 일반 처형) |
| BMI 205 (PAND03G) | 450–453 (19) [0–1], 454 [0–0.5], 455 [0–0.8], **460 BMIPLATFORMGRABTOSS (19) [0–0.4]** (아래) | 002 의 450–455 없음, 002 의 460 은 창 없음 |

- **BMI 460 발판 잡아 던지기** [확실: 데이터 + 0x2097A8 · 0x205B28 · 0x204BF0] (예전: 엔진 동기 쌍이 450–455 뿐이라 두 쪽이 각자 루트 모션):
  BMI 202 의 104 BMIPLATFORMGRAB (발판 단계표, 부위 4 [0.48–0.52] react 1, SET_1B0 = 460) 이 영웅에 맞으면 영웅 002 의 **A460 EV_HIT_REACT tag 460 · tgt 200** → 영웅 460
  (플래그 0x140, INVULNERABLE, 0.9 AUTO → 456 HITBOUNCE), BMI 는 HIT_CONFIRM → 205 의 460 (플래그 **0x130 = 0x20 동기 시작** → 0x2097A8 이 +0x84 = 공격 대상 = 영웅).
  BMI 460 의 이벤트 19 (FLAG_4) [0–0.4] → 0x205B28 의 0x24 검사 → **BMI 가 영웅을 0x204BF0 으로 끈다**. 창 뒤는 두 쪽 루트 모션.
  엔진: `BmiFight` 의 `BossSync` 동기 ID = 450–455 + **460** (`PLATFORM_TOSS`). 헤드리스 (`BmiFightTest.platformGrabTossPairsAndPulls`): 104 적중 → 두 쪽 460 쌍,
  창 139 틱 동안 끎, 오차 167 → 25 틱에 4.9 (BMI 애니가 영웅을 휘두르는 한 틱 늦음) → 창 끝 0.0. 영웅은 BMI 애니대로 앞으로 ≈ 500 휘둘려 던져진다.
  같은 프레임에 두 쪽 모두 창이 있는 보스 동기는 없다 (원작이라면 서로 회전만 끌고 위치는 각자 루트 모션 — 끄는 쪽은 자기 +0x00 을 안 읽음). 엔진은 보스 쪽 우선.
- **아레스 606 시험 정정**: 예전 `AresFightTest` "606 은 끌지 않음" 은 틀림 — 아레스 604 의 606 에 이벤트 19 [0–1] 이 있어 아레스가 영웅을 끈다. 이제 606 에서 끌기 = 1,
  아레스 제자리 (끄는 쪽, MAIBARAutoStruggle zero 고정), 영웅 = 아레스 정면·마주 봄 (synch 관계 오차 < 1).
- **포옹 "329 단위" 원인**: 데이터·관절 문제 아님 (가족 xmc synchJoint (0,0,−16) parent 0 / zeroJoint 원점, 영웅 hero zero 기준 (−1, 0, −11) 180° — 관계 = 가족 정면 27 마주 봄;
  2026-10-24 정정: 원작 배치로 가족 포옹 애니의 zero/synch 는 (0,0,−27.1)/(0,0,−32) → 정면 16, §9).
  예전 시험 `hugTransfersHealth` 가 영웅을 원점 (가족에서 400 넘게) 에 둔 채 `contact` 로 잡기를 걸어 목표가 멀리 보였다 + 위 섞인 자세 문제. 시험은 이제 정면 40 에서 잡고
  틱당 이동 < 4, 0.5 초 뒤 관계 오차 < 1, 정면 12–20 (예전 22–32)·마주 봄을 확인. 분신 잡기 861: 영웅–분신 수평 1.0 (예전 43 — 같은 원점 관계).
- **히드라 806 → 807 한 틱 40 튐** (이벤트 19 적용으로 드러남): 새 기술 첫 프레임은 직전 루트 모션 델타를 유지 (0x1F7110) 해 히드라 요가 0.09 돌고, `HydraFight` 의 몸통 고정
  (위치 x/z·요 = 스폰 값) 이 끌기 **뒤**에 와서 영웅 synch 거리 ≈ 450 만큼 목표가 휘둘렸다 → `BossFight.pin()` 을 동기 갱신 **앞**에 (히드라 몸통·가족 위치).
- 전수 점검 (헤드리스, 보스 시험 전부, 틱당 영웅·보스 이동 > 3 / 요 > 0.1 기록): 남은 큰 이동은 애니 그대로 — 아레스 600 던지기 (11–12/틱), 622 뛰어내림 (13),
  BMI 455 뛰어내림 (7–10). 기술 경계 튐은 BMI 450 → 451 의 7.8 뿐인데 **데이터 자체** (BMIRideMount 끝 관계 x −14.1 ↔ BMIRide01 처음 −6.3, 451 → 452 → 453 → 455 는 0) — 원작도 같다 [추정].
  아레스 605 시작 요 0.34/틱 (비율 0.1 → 1 로 마주 봄까지 약 0.3 초), 분신 860 은 분신 루트 모션 앞으로 3–7/틱 + 영웅 끌림 < 2/틱.
- 스크린샷 (`/Users/obeomsu/.claude/jobs/6f65672f/tmp/bsync/`, 커밋 안 함, `--game … --boss-demo --skip-movies --no-fx --ticks N`): `hug_t486/496/512` (정면 40 → 27 로 안음, 휘청임 없음),
  `clone_t860` (861 등 뒤에서 잡음), `ares1_t380/600` (605/606 손 맞잡기), `ares1_t700` (608 어깨에 매달림), `hydra_t700/780/2900`, `ares3_t240` (621 칼 맞대기), `bmi_t900/1300` (올라타기),
  `medusa_t900` (머리 뜯기, `GrabSync`).

### 11.7 해골병 잡기 → □ (ATHN01A 배 위 레기오네어 R_SKS, AI1 020) — 들린 적의 공중 상태 [확실: 코드 / 데이터]
- 버그 (사용자 보고 "잡기 후 약공격 애니 미스매치"): ○ 로 잡고 □ 를 누르면 크레토스는 221 MAISKB02 (꿰뚫어 들어 넘기기) 를 끝까지 하는데 해골은 적 진행률 0.4
  (영웅 0.34, 0.8 초) 에서 105 HITLAND 로 빠져 결합이 끊기고 영웅이 허공을 들어 넘겼다.
- 원작 그래프 (`combat:dump`): 영웅 002 의 200 GRAB HIT_CONFIRM tgt=20 → 220 MAISKB00 (속도 1.5) → [0.4–1]! □ → 221 MAISKB02 (속도 1.0, 플래그 0x12A — 0x20 이라
  적도 SYNC 로 221), △ → 224 MAISKB05, ○ → 222 MAISKB03, 끝 AUTO → 223 MAISKB01 (반복, 같은 □/△/○ 분기). 적 R_SKS 022 도 같은 ID·같은 이름 애니 (220 속도 1.5, 221 1.0).
  영웅 221 이벤트 22 (FLAG_24) [0–0.76] → 영웅이 해골을 0x204BF0 로 끈다 (synchJoint 관계, 영웅 클립 2.33 초 / 적 클립 2.0 초 — 같은 애니 시간으로 감).
  적 221 의 `[0.40–1]! AUTO → 105 HITLAND` 는 분기 플래그 0x148243 = **지상 전용** (0x2, Combat_StateMask 0x207DC0).
- 원작에서 그 분기가 막히는 이유: 적 221 은 stateMask 1 (자유 이동) → 0x1EBD78 의 0x1ECD60 경로 → 0x1EA200 (a3 = 지상·공중일 때 1, 0x1ECE08). 0x1EA200 의 바닥 질의
  0x1E3298 (선분 = 이번 변위 d = |새 위치 − +0x110|, 최소 3.2 = 0x404CCCCD, 위 max(d − 6.4, 0)) 결과로 (0x1EA2E4–0x1EA3B4) 맞았고 바닥 아래면 올려 붙이기만, 맞았고 위면
  +0x174 = 1 (지상), 안 맞으면 +0x174 = 8 (낙하 → 분기 마스크 0x4, +0x2B8 = 0; 날기 +0x2DC & 8 이면 0x40). 영웅이 끌어 들어 올린 해골은 바닥이 닿지 않아 공중 →
  HITLAND 는 내려와 다시 바닥이 닿을 때까지 막힌다.
- 엔진 (예전): 자유 이동 `EnemyNav.free` 가 바닥 아래만 막고 상태를 바꾸지 않아 해골이 늘 지상 → 0.4 에서 바로 HITLAND. **수정**: `free` 가 위 규칙대로 상태를 바꾼다
  (지난 내비 위치로 d, 날기는 그대로, 낙하는 공중 잡기 없음). 세계가 없는 적 (보스·시험) 은 예전대로. `HeroTestScene` 적에 바닥 평면 세계 (`EnemyNav.flat(0)`).
- 같은 ID 따라가기의 애니 시간: 따라간 쪽이 1–2 틱 늦던 것 (이끈 쪽이 그 틱에 이미 진행) → 따라간 쪽 애니 시간 = 이끈 쪽 (`GrabSync.matchTime`; 원작은 SYNC 분기로 같은 프레임에
  같은 ID 를 0 부터 [추정: 프레임 안 순서]).
- 결과 (헤드리스 `GrabRelationTest.skeletonGrabSquareStaysPaired`, 바닥 평면): 220 결합 → □ → 221 쌍 ≈ 100 틱, 두 쪽 애니 시간 같음 (오차 < 1e-4), 영웅 이벤트 22 끌기 유지,
  해골 y 0 → 18 (공중) → 끌기 끝 무렵 착지 → HITLAND (영웅 진행률 ≈ 0.74). 스크린샷 (`--hero --auto grab --enemy R_SKS --no-fx --ticks 120/150`, 커밋 안 함):
  t120 꿰뚫어 든 해골, t150 어깨 너머로 던져 뒤에 떨어짐.
- 남은 것: 220 끝 → 223 사이 한 틱 결합 끊김 (적 220 은 AUTO 없이 끝나 한 틱 기술 없음 → 영웅 223 (0x20) 시작 때 다시 묶음, 0x2097A8) [근사: 원작 프레임 안 순서 미확인].
  0x1E3298 의 위 오프셋 6.4 (+0x130.y) 는 영웅 값 — 해골의 +0x130 미확인 [근사]. 끄는 속도가 빠르면 d 가 커져 공중 높이에서도 바닥이 닿을 수 있다 (원작 규칙 그대로).

### 11.8 ATHN01F 선장실 침대 미니게임 (쌍둥이 AI1 305 aoxpt, 2026-10-23 QA vs PS2 영상 2060–2090 초)
- **R2 와 ○ 는 다른 것** [확실: 데이터]. R2 아이콘은 침대 옆 `teaseTwins-volumeR/L` 의 버튼 안내 (r2icon, 42 문서 §1) — R2 (1024) → `talky`: LevelData:4/5,
  메시지 317 "침대로 돌아와요, 크레토스." / 319 "크레토스, 조금만 더 머물러요.", CMV 끔 + `twinscam1`/`TwinCamLeft`, `forceMove` 1019 기술 0/1 (인자2 1 →
  A0/A1 AOXPT1A02MI01/02 놀림) → 메시지 끝 1040 → `quitMime` 1019 −1 + CMV 켬. **미니게임은 ○** (영웅 002 M200 GRAB 의 `HIT_CONFIRM -> M846 tgt=305 tag=305`):
  - 침대 위 원 볼륨 `twinsCircle-pCube2` 진입 → `Minigamecircle-miniGame1idle69` (1019 69, LevelData:8 거짓) 또는 `miniGame2idle875` (참) + 침대 위 붉은 원
    (`Minigamecircle-VisON`). 나감 → 1019 70 (A70 `REQUEST0 -> -`, 기술 없음) + 원 끔 (LevelData:6 = 미니게임 중이면 안 나감).
  - 쌍둥이 M69 AOXPT1A02MI06 (반복) 이 SET_1B0 **305** (자세 태그) → ○ GRAB 적중 → 영웅 846 MAIXPTBEDMOUNT, 쌍둥이 `EV_HIT_REACT tag=200` (GRAB 의 SET_1B0 200) → 846.
    두 쪽 같은 ID 결합 (`GrabSync`), 쌍둥이 846 이벤트 22 [0–1] 로 영웅을 끈다 (§11.4).
  - 쌍둥이 846–851: SCR_TimedPress 템플릿 300–305 창 [0.31–0.57] (프롬프트 하나씩, 35 문서 §5.2), 맞히면 `[0.00–0.60] AUTO → 다음 @0.28`, 놓치면
    `[0.31–0.58]! REQUEST0 → 852` (실패) → AUTO 69. 851 다음 853 MAIXPTBEDMOUNTWIN: ORB_EMIT 850 ×5 [0.70–0.75] → AUTO 875 (둘째 판 대기, SET_1B0 306 → 880–887,
    887 ORB_EMIT 851 ×1).
  - 스크립트 (1035 기술 시작): 846 (¬LevelData:8) → LevelData:6, CMV 끔, `camVase`, Animator `gobedshake` bedShake00 (+ 소리 그룹 `gosndgrpbed*` "!0");
    847–851 → bedShake01/00/02/01/03; 852 → bedshakeFail + miniGame1idle69 (CMV 켬), LevelData:6 거짓; 853 → `camVaseSuccess`, LevelData:6 거짓·**8 참**,
    bedshakeWin (침대 기둥 위 항아리가 떨어짐), 4.2 초 뒤 CMV 켬, 7.5 초 bedShakeReset. 880–887 은 같은 흐름 (LevelData:8 조건).
- **엔진에서 안 되던 것과 수정**:
  1. 봇·디버그 스크립트의 R2 가 버튼 안내 1024 에 닿지 않았다 (`GameScene` 이 창 입력만 봄) → 지난 틱의 실제 입력 (`lastInput`) 도 본다 [엔진 전용].
  2. 1019 인자1 0 을 모든 적에서 버렸다 → 전투 파일에 기술 0 이 있으면 받는다 (쌍둥이 놀림). 인자2 ≠ 0 인데 요청할 기술이 없으면 (70, −1) 지금 기술을 끊는다
     (`Combatant.stop`: 0x1D3530 → 0x209918 [확실: 경로], 자세 태그도 지움 [추정]) — 예전엔 원 밖에서도 305 태그가 남았다.
  3. TimedPress 실패 분기 (`Combatant.runWindowBranch`) 가 대기 분기를 안 지워 852 위에서 846 의 AUTO → 847 이 실행 — 실패해도 미니게임이 이어져 853 까지 갔다
     → 실패 때 대기 분기를 버린다 [추정: 원작 실패 처리 = 기술 요청 경로 0x209378 의 큐 비움].
  4. 처형 성공 → 사망 처리가 소품에도 적용돼 853 뒤 쌍둥이가 죽을 뻔했다 → HP 32767 은 제외.
  5. Animator 대상 `gobedshake` 는 CDV 가 없어 발판 목록에서 빠져 애니가 재생되지 않았다 → 스크립트 Animator 대상이고 ANM 이 있으면 충돌 없는 몸으로 넣는다
     (`Platforms.of`, 원작 0x1AB0B8 은 충돌과 무관 [확실: 코드]).
  6. AUTO 봇: 잡기를 기다리는 소품 (HP 32767, 자세 태그 ≥ 0, 120 안) 이 있으면 목표에서 R2 대신 ○, 결합 중 미니게임은 `AutoPad` 가 프롬프트 버튼 [엔진 전용].
- 결과: AUTO (`-Dgow.auto.goal=590,107,-580 -Dgow.auto.face=575,96,-576`) — 1019 69 → ○ → 846 → camVase → 847…851 (bedShake00–03) → 853 → camVaseSuccess
  (항아리 떨어짐) → 875, 붉은 오브 0 → 330 (850 ×5) / 둘째 판 880 → 887 +5. 무입력은 846 → 852 → 69. R2 는 메시지 319 + TwinCamLeft + 놀림 1 → 1040 → −1.
  시험 `combat.BedMinigameTest` (성공 순서·오브 850 ×5·생존·875 태그 306 / 실패 852 → 69 / `stop` 이 기술·태그를 지움).
- 남은 것: `camVase`/`TwinCamLeft` 화면 구도 — 엔진 레일 카메라는 바닥과 오른쪽 끝 항아리를 비춘다 (원작 영상과 대조 못 함, 카메라 시스템 쪽) [근사].
  Animator 속도 슬롯 (H6 = 2 / 0.5) 이 엔진에서 1.0 으로 읽힘 — 슬롯 번호 미확인.
- 이후 (ATHN02A 커밋): 317/319 는 `ShowMessage(…, −1)` 이라 ○ 를 누를 때까지 떠 있고 그동안 전역 정지 (42 문서 §5.1) — 예전엔 4 초 뒤 닫혔다.

## 보스 체력 미터 (HUD)
- 미터는 FLP_HUDK 가 아니라 **레벨 WAD 의 FLP_&lt;레벨&gt;** (ATHN01B/C/E, ATHN08A/B/C, PAND03G) 에 있다. `GameScene` 이 이 FLP 를 HUD 와 변수를 공유하는
  오버레이(`levelHud`, 입력 없음)로 띄운다.
- 스크립트 `*-HealthMeterOn/Off` EventTransmitter (EventId 1055/1056, Filter = 대상 그룹) → `CombatScriptBridge` 가 대상 적에 슬롯 1..3 배정.
- `EnemyMeterBinding`: `PS2_EnemyMeter_Event{n}` 1 켬 / 0 끔 / 2 갱신, `PS2_EnemyMeter_Value{n}` = HP/최대 × 99 → `HydraMeterMain` 프레임 2 + 값
  (102 프레임 클립에서 녹색 막대 길이가 프레임마다 늘어남) [추정: 백분율 환산], `PS2_EnemyState_{Event,Value}{n}` = 잡기 가능 시 2(BarFlash) [추정].
- 검증 `BossMeterTest`: 아기 히드라 2마리 → 슬롯 1·2, HP 50% → 프레임 50 (= 2 + 49 − 1), 사망 → Off. 화면 `--ui gow-rom/ATHN01E.WAD FLP_Athn01E meter`
  (`work/uirt/meter.png`, 원작 히드라 미터 60%).

### 10.x 병합 메모 (Vis 적용 후)
- 스크립트 Vis 표시/숨김(28 문서)이 적용되어 `FakeDeadBMI-VisOff` 가 장식용 죽은 수호자 석상을 숨긴다 (전에는 싸움 내내 보였음).
- 등장 동영상 끝(이벤트 231) 에 `FinalMpegStuff-SceneryFlatsOn` 이 gofinalmpegstuff 를 켠다 — 이 GO 는 돌벽·바닥(MAT_M01StnL03*) 메시라 밝지 않다.
  카메라가 아레나 밖을 볼 때의 흰 화면은 이것을 숨겨도 그대로다 (§10.1 렌더 문제 ②).
