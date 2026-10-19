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
| `BossSync` | 같은 ID 동기 기술 쌍 + RepeatPress(속도 0 이 아니어도) + TimedPress (보스 기술에 없으면 영웅 기술의 것 — BMI 451–453). 정렬 (§11): BOSS_TO_HERO(아레스·분신 잡기) / SYNCH(가족 포옹) = synchJoint **위치** 겹침·같은 방향, HERO_TO_BOSS(히드라), ZERO(BMI 올라타기 = zeroJoint 차) |
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
| `CombatScriptBridge` 1039 → 적 | 스크립트가 적에게 보낸 1039 = 피해 인자1 (0x25B6E8 → 0x1CC810 → 0x204690) [확실] — PAND03G LogDamageBoss 999 |
| `LevelData.behaviors` | GO 하나에 비헤이비어가 여럿이면 Creator 대신 다음 것 (PAND03G gologlever = Creator 100 + PushPull 15 "BMI Log Puller" 과제 351 → 레버 R1 오브젝트) |
| `Combatant.setBystanders` | 대상 외에 같은 공격 판정을 받는 개체 (분신 공격이 가족에게 휘말림 — 피격자 처리만, 공격자 HIT_CONFIRM 없음) |
| `HeroNavigator.deepWater` | Water 면 아래 16+4 안에 단단한 바닥이 있으면 헤엄치지 않음 (ATHN08A/08C 아레스 만: 수면 y 1, 바다 바닥 −6.7 = "bay floor" −0.4125·16) — 전엔 크레토스가 바닥 아래 y −15 에 헤엄 상태로 갇혔다 [추정: 깊이 기준] |
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
- 엔진 보정: HP 바닥 1, 몸통 위치·**방향** 스폰 값 고정(목이 바다에 붙음, AI 이동·회전 의도 버림) [추정], 시야 판정 생략.
  방향 고정 근거: 구역별 공격이 애니(51/52/62)로 나뉘고, 잡기·처형 카메라 오브젝트가 월드 고정이라 동기 장면도 스폰 방향 기준 (30 문서 §6.4).
  GameScene 이 보스 스폰 방향을 엔티티 행렬에서 읽도록 고침 — 전엔 0 이라 크레토스가 망대 밖 허공(−3120, 1170, −563)에 정렬됐다.
- 잡기·처형 카메라: 1035(806/802/807/803/809/805/810/812 …) → ESC BossLeftFight/BossRightFight/BossFInalGame 의 SwitchCamera + 질의 끔, 관절 애니 카메라 (30 문서 §6).
- **동기 정렬 HERO_TO_BOSS [추정]**: 영웅 동기 애니의 zeroJoint = 보스 원점 기준 영웅 위치에서 **z 만 뒤집은** 점, 영웅은 보스를 마주 봄 (요 + π).
  근거 [데이터]: 보스 물기 애니 synchJoint(물기가 떨어지는 단상 지점)와 영웅 zeroJoint 의 대응 — BiteMiddle (1, 679, −382) ↔ 영웅 BHAbossKill (1, 682, 383),
  BiteLeft (−74, 679, −449) ↔ GrabLeft (−40, 682, 432), BiteRight (69, 679, −451) ↔ GrabRight (52, 682, 436). 이전 180° 회전은 x(좌우)가 반대라
  grabLeftCam 이 머리 쪽으로 돌면 크레토스가 카메라 축에서 45–57° (화면 밖)였다 → 뒤집기 후 30–40°. 그래도 1.2–2초 구간엔 화면 가장자리 밖이다 (§8).
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
| 히드라 잡기·처형 카메라에서 크레토스가 안 보임 | **부분**: 좌우 반전은 고침(위 §4). 남은 원인 — 잡기 애니에서 히드라 머리(JAW/TIP 관절)가 영웅 자리에서 100–200 옆 (손–관절 최소 거리 ≥ 84, 어떤 회전·반사로도 접촉 안 됨). 보스 grab 애니 synchJoint 는 원점·180°, 영웅 synchJoint 는 원점·0° 라 synchJoint 규칙(아래 §9)으로는 영웅이 바다 높이가 됨 → 히드라 쪽 정렬 규칙 미해결 |
| 아레스 잡기 (BOSS_TO_HERO) | **검증** (§11): 같은 원점·같은 방향이 맞다 (손목 ↔ 아레스 손가락 관절 평균 7.0). "64 앞 마주 봄" 은 25, zeroJoint 차 규칙은 40 |
| RegenHealthBar (RegenBar 250/252: Regen Speed 150, 비율 0.8/0.45) | 의미 미확정 → 무시 |
| SonicScream 힘 | 근사 (거리 ×16 안에서 Force/500 단위/초) |
| 히드라 HERO_TO_BOSS 정렬, 메두사 석화, 아레스 차지 | [추정]/[근사] |
| 아기 히드라 enc3 (`bhb` 253/258) | 리소스·DTre 로 싸움, 돛대 핀(110–117) 연출 미검증 |
| ATHN08B 분신 크레토스·가족 | **해결** (§9): 가족 보호·포옹(정렬 고침 §11)·휘말림·사망 실패, 분신 잡기 860–863 (흔들기 탈출 / 실패 피해), 포옹 신들의 분노 게이지 |
| 판도라 수호자 BMI (PAND03G) | **구현** (§10): 전용 클래스 Think/TakeDamage 원작 해독 → `BmiFight`, 결말(245 → p03MP07 → Pand03H)까지. 통나무 궤적·증기/갑옷 조각·HUD EnemyState·카메라 대상 슬롯은 근사/미구현 |
| AresFight 데모 크레토스가 ~2.5초부터 멈춤 | **해결**: 1단계 데모는 0.9초마다 정면 60 에서 공격 (4 번에 500 임계 밑, 원 임계마다 290), 그 뒤 ○ 잡기. 예전엔 3초까지 서 있다가 불덩이에 맞고 정지 |
| FakeDeadBMI (죽은 수호자 장식, 통나무 박힘) | 생성 센서 VisOff 가 안 먹어 전투 내내 보임 (GameScene/Vis 쪽, 이 작업에서 손대지 않음) |
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
  포옹 정렬 **SYNCH** (§11): synchJoint **위치**만 겹치고 방향은 같게 — 가족 MAIEVIHug* synch (0,0,−16), 영웅 (0,0,−32) → 영웅 원점이 가족 원점 16 뒤, 같은 방향
  (손목 ↔ 가족 관절 6.4; 예전 "정면 48 마주 봄" 은 약 25 — 크레토스가 가족 앞 허공을 안았다).
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
엔진 (`BmiFight`) 근사: 위치 = 기술 애니 zeroJoint 변화량 (BMI 애니는 zeroJoint 절대값이 클립마다 다르고 몸은 제자리), 앵커 = 스폰 위치 (원작은 GO 부모 로케이터),
통나무 = 1020(1000) 뒤 (거리 / 150·16) 초에 "이번 레버 주기에 999 로 피하지 않았으면" 적중 [근사: Arrow 궤적·logAim 미구현, Arrow "Ignore Invulnerable"],
올라타기 정렬 ZERO (§11). 미구현: 배출구 증기 GO·갑옷 조각 BRK·HUD EnemyState(MeterState)·카메라 대상 슬롯(Camera Target Weight/Radius), 통나무 모델.
순회 검증은 아직 맡지 않음 (`headless() == false`, 검증 하네스 경로 그대로).

- 테스트 `BmiFightTest`: 문 앞 볼륨 → P03MP06 → Goto → 231 → 스폰 / 단계표 (멀리 → 1 걷기 이동, 정면 가까이 → 150), 앵커 선 /
  배출구 250·200·200·200·201 (HP 1500 유지, VentCam) → 203 → 잠금 중 무피해 → ○ → 450 (메시지 589) → 451–453 TimedPress 7 → 455 → 208 (LevelData4) → 레버 → 통나무 적중 → 단계 1, 210 /
  비기절 레버 → 999 → 220 → 빗나감 / 데모 끝까지: 232 (ArmorCam1/2), 240 → 242 → 243 → 245, 통나무 3 번, p03MP07 → camMinoDeado, LoadWad Pand03H.
- 스크린샷 (`work/shots/37c/`): `bmi_t600.png` (정면 BMI 다리 사이 크레토스), `bmi_mount.png`·`bmi_ride1.png`·`bmi_ride3.png` (등에 올라탐), `bmi_loghit.png`·`bmi_armor.png` (통나무 카메라, 갑옷 균열), `bmi_final.png` (격파 뒤 문).

## 11. 동기 애니 정렬 — 관절 접촉 검사
synchJoint 의 의미를 추정으로 정하던 것을 **데이터로 검사**: 동기 중 매 프레임 영웅 손목(lwrist/rwrist) ↔ 상대 골격 관절의 최소 거리 평균 (`BossSyncContact`).

| 동기 | synchJoint (보스 / 영웅) | 같은 원점·같은 방향 | synch 위치 겹침 | synch 행렬 통째 (마주 봄) | zeroJoint 차 |
|---|---|---|---|---|---|
| 아레스 MAIBARGrab* | (0,0,−32) 180° / (0,0,−32) 0° | **7.0** | = 같은 원점 | 25 (64 앞) | 40 |
| 가족 포옹 MAIEVIHug* | (0,0,−16) 180° / (0,0,−32) 0° | 약 12 | **6.4** (16 뒤) | 약 25 (48 앞) | 33 |
| BMI 올라타기 BMIRide* | (0,0,0) / (0,0,0) | 20–45 (바닥) | = 같은 원점 | – | 19–45, **영웅 zeroJoint 높이 0 → 212 (등)** |

- 규칙: **synchJoint 위치를 겹치고 방향은 같게** (`BossSync.synchOffset`, 보스 배율 무시 — 아레스 1.15 를 곱하면 7.0 → 9.9). synchJoint 회전(180°)은 정렬에 안 쓴다 [추정: 상대 방향 표식].
  아레스 잡기의 "64 떨어져 마주 봄" 가설은 틀림 — 아레스가 크레토스를 두 손에 쥔 화면 (`ares_t330.png`).
- BMI 올라타기는 거대한 몸에 관절이 드물어 접촉 수치로 못 가린다. 영웅 BMIRide* 애니는 몸이 제자리이고 zeroJoint 가 높이 212 까지 기어오르므로
  **ZERO** = 영웅 = BMI + R·(zero_영웅 − zero_BMI) (높이 포함, 바닥 아래로는 안 내려감) [추정: 화면 확인 — 크레토스가 BMI 등·어깨 위].
- 스크린샷 (`work/shots/37c/`): `ares_t330.png`·`ares_t700.png` (잡기), `hug_t530.png` (포옹), `ares_demo_t200.png` (1단계 데모 공격).

## 보스 체력 미터 (HUD)
- 미터는 FLP_HUDK 가 아니라 **레벨 WAD 의 FLP_&lt;레벨&gt;** (ATHN01B/C/E, ATHN08A/B/C, PAND03G) 에 있다. `GameScene` 이 이 FLP 를 HUD 와 변수를 공유하는
  오버레이(`levelHud`, 입력 없음)로 띄운다.
- 스크립트 `*-HealthMeterOn/Off` EventTransmitter (EventId 1055/1056, Filter = 대상 그룹) → `CombatScriptBridge` 가 대상 적에 슬롯 1..3 배정.
- `EnemyMeterBinding`: `PS2_EnemyMeter_Event{n}` 1 켬 / 0 끔 / 2 갱신, `PS2_EnemyMeter_Value{n}` = HP/최대 × 99 → `HydraMeterMain` 프레임 2 + 값
  (102 프레임 클립에서 녹색 막대 길이가 프레임마다 늘어남) [추정: 백분율 환산], `PS2_EnemyState_{Event,Value}{n}` = 잡기 가능 시 2(BarFlash) [추정].
- 검증 `BossMeterTest`: 아기 히드라 2마리 → 슬롯 1·2, HP 50% → 프레임 50 (= 2 + 49 − 1), 사망 → Off. 화면 `--ui gow-rom/ATHN01E.WAD FLP_Athn01E meter`
  (`work/uirt/meter.png`, 원작 히드라 미터 60%).
