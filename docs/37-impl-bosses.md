# 37. 구현: 보스전 (히드라 / 메두사 여왕 / 아레스)

원작 보스는 거의 전용 C++ 클래스가 아니다 (예외: 아레스는 Enemy1 하위 클래스 2 — 매 프레임 AresCamera1 요청, 30 문서 §6.3; 판도라 수호자 BMI 도 전용 클래스, §10). **일반 `Enemy1` + 결정 트리(DTre) + CombatFile + 레벨 스크립트(ESC)** 이고,
보스다운 연출(구역별 공격, 내려찍기 → 잡기, 기절 → 처형, 컷신, 레벨 전환)은 거의 전부 **레벨 스크립트가 전투 이벤트를 듣고 지시**한다.
그래서 구현의 핵심은 (1) 레벨 WAD 안의 보스 리소스 구성, (2) 전투 ↔ 스크립트 이벤트 다리, (3) 영웅·보스 동기 기술이다.

```bash
./gradlew :engine:run --args="--game ATHN01E --boss-demo --skip-movies --no-fx"   # 히드라 자동 연출 (아래 갑판 스폰 볼륨부터)
./gradlew :engine:run --args="--game ATHN02D --boss-demo --skip-movies --no-fx"   # 메두사 여왕
./gradlew :engine:run --args="--game ATHN08A --boss-demo --skip-movies"           # 아레스 1단계 → ATHN08B 로 전환
./gradlew :engine:run --args="--game ATHN08C --boss-demo --skip-movies"           # 아레스 3단계 → ATHN07B
./gradlew :engine:run --args="--game ATHN08B --boss-demo --skip-movies --no-fx"   # 분신 웨이브 + 가족 포옹
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
| `BossDirector` | 레벨 → 보스전 등록표 (`hostsBoss`, `create`). ATHN01E/02D/08A/08B/08C/PAND03G |
| `BossKinds` | 레벨 WAD 의 스켈레톤·MDL·ANM + 지정 CombatFileSet + AI1/DTre/Sold → `EnemyFactory.Kind` |
| `CombatScriptBridge` | 1035/1047(기술), 1039(피해), 1019(EvtForce → 보스 기술 요청), 1054(구역 → 조건 26), 1055/1056(체력 표시) |
| `BossFight` | 공통: 스폰 연결, SELF_HP(바닥 무시 자기 피해 / 회복), ExplodeDeath, SonicScream 밀어내기, FlashSprite 프롬프트, 영웅 SELF_HP, 소리 전용 템플릿, 데모 |
| `BossSync` | 같은 ID 동기 기술 쌍 + RepeatPress(속도 0 이 아니어도) + TimedPress. 정렬 BOSS_TO_HERO(아레스) / HERO_TO_BOSS(히드라) / SYNCH(synchJoint, 포옹) |
| `HydraFight`, `MedusaQueenFight`, `AresFight`, `EvilKratosFight` | 보스별 보정·단계 이름·데모 |
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
| 아레스 잡기 (BOSS_TO_HERO) | synchJoint 로 보면 아레스 MAIBARGrab* (0,0,−32) 180° / 영웅 (0,0,−32) 0° → 64 떨어져 마주 봄이 맞을 수 있다 (현재는 같은 위치·같은 요) [미검증] |
| RegenHealthBar (RegenBar 250/252: Regen Speed 150, 비율 0.8/0.45) | 의미 미확정 → 무시 |
| SonicScream 힘 | 근사 (거리 ×16 안에서 Force/500 단위/초) |
| 히드라 HERO_TO_BOSS 정렬, 메두사 석화, 아레스 차지 | [추정]/[근사] |
| 아기 히드라 enc3 (`bhb` 253/258) | 리소스·DTre 로 싸움, 돛대 핀(110–117) 연출 미검증 |
| ATHN08B 분신 크레토스·가족 | **해결** (§9): 가족 보호·포옹·휘말림·사망 실패. 분신의 영웅 잡기 EVIGRAB 860–863 (dir 7 "fall>2" 조건) 은 일반 경로 그대로 [미검증] |
| 판도라 수호자 BMI (PAND03G) | 분석만 (§10): 전용 C++ 클래스 AI — 리소스 등록·스크립트 카메라만 동작 |
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
  포옹 정렬 **SYNCH** [추정: synchJoint 의미]: 영웅 = 가족 · S_가족 · S_영웅⁻¹ — 가족 MAIEVIHug* synch (0,0,−16) 180°, 영웅 (0,0,−32) 0° → 가족 정면 48 에서 마주 봄.
  가족 HP 0 인데 대기 순환(M0/M1, EV_DEATH (block))이면 엔진이 기술 분기를 루트 액션보다 먼저 보므로 A150 이 안 걸린다 → 사망 기술 150 으로 [추정: 원작 우선순위].
  포옹 뒤 가족 기술이 없으면 0 요청 (원작 865 SYNC → M0, 스크립트 1019(0) 와 같은 대기 순환).
- 순회 검증: 격파할 본체가 없어 `HeadlessBoss` 는 맡지 않음 (`headless() == false`) — 분신은 일반 적처럼 처치되어 25 → ATHN08C (기존 TraversalTest 그대로 통과).
- 테스트 `EvilKratosFightTest`: 가족 xmc·HP 100·세트 660/661/662, M0 → M1, 제자리, 영웅 공격 무피해 / 포옹 동기(정면 48·마주 봄), ○ 유지 HP 이동(+24 이상 / −24 이하), 메시지 454, 끝나면 대기 /
  분신 칼날이 가족에 휘말림, HP 0 → XMCDEATH2 → camWifeDeatho + A07MP04 → FailedScreen.
- 스크린샷 (`work/shots/37b/`): `evil_a.png` (집 안: 가족 앞 크레토스, 분신 4 — 하나는 스폰 점프), `evil_hug.png` (포옹 + 메시지 454, 영웅 HP 감소).

## 10. 판도라 수호자 BMI (PAND03G) — 분석 (미구현)
- **전용 C++ 클래스** [확실: 실행 파일]: AI1 200 은 DTre 가 없고(-1) 모든 기술이 REQUEST0 루트 액션(0–49 이동·도발, 50–94 공격, 200–260 비틀거림·거북 기절·통나무 기절·갑옷 파괴 232·죽음 전환 242·증기 배출 250, 450–460 올라타기)이라
  기술 선택을 클래스 코드가 한다. 0x1C0000–0x1C2D00: BMI_RegisterTweaks 0x1C1EB0 (`/GlobGame/BMI`: Camera Target Weight 0.35 / Radius 2.0, Damage Per Vent Stage 100,
  Damage Per Armor Stage 150, No Armor Health Threshold 99, 기술 번호 Walk Forward / Leap Back / Dodge / Stagger First Time / Stagger / Stunned),
  SCR_BMITurnOffVents / SCR_BMIOnPlatform / SCR_BMIBrokenPieces / SCR_BMINoDodge / SCR_GlowEnemyMeter (0x1C2B90), 증기 배출구 소리 SND_MB_VENT%d_LP (0x1C1518/0x1C1D70),
  관절 표 BMI01JOCHEA1 …(0x2B7360..). AresCamera1 처럼 카메라 대상 슬롯을 쓰는 것으로 보인다 ("Camera Target Weight").
- 데이터로 확인된 연출 고리: 영웅 ○ GRAB → HIT_CONFIRM M450 BMIRIDEMOUNT (tgt=200 tag=203) — tag 203 은 M450 자신이 세우므로 올라탈 기회는 클래스 코드가 연다.
  450 → SYNC → 451/452/453 BMIRIDE01..03 → 454 RIDEFAIL / 455 RIDEWIN (→ 208 통나무 기절). 스크립트 Mount/MinGame451..453/MinGameFail/MinGameWin/ArmorBreak(232)/SpitCams 는
  1035(기술) Filter 200 으로 카메라만 바꾼다 (mountcam, cam451 …, FailCam, WinCam1–3, ArmorCam1/2) — 일반 이벤트 다리로 이미 동작.
- 남은 일: 0x1C0000 대의 BMI 갱신(배출구 단계·갑옷 단계 피해 누적 → 비틀거림/기절 기술 요청, 올라타기 열기) 해독 후 `PandoraGuardianFight`.

## 보스 체력 미터 (HUD)
- 미터는 FLP_HUDK 가 아니라 **레벨 WAD 의 FLP_&lt;레벨&gt;** (ATHN01B/C/E, ATHN08A/B/C, PAND03G) 에 있다. `GameScene` 이 이 FLP 를 HUD 와 변수를 공유하는
  오버레이(`levelHud`, 입력 없음)로 띄운다.
- 스크립트 `*-HealthMeterOn/Off` EventTransmitter (EventId 1055/1056, Filter = 대상 그룹) → `CombatScriptBridge` 가 대상 적에 슬롯 1..3 배정.
- `EnemyMeterBinding`: `PS2_EnemyMeter_Event{n}` 1 켬 / 0 끔 / 2 갱신, `PS2_EnemyMeter_Value{n}` = HP/최대 × 99 → `HydraMeterMain` 프레임 2 + 값
  (102 프레임 클립에서 녹색 막대 길이가 프레임마다 늘어남) [추정: 백분율 환산], `PS2_EnemyState_{Event,Value}{n}` = 잡기 가능 시 2(BarFlash) [추정].
- 검증 `BossMeterTest`: 아기 히드라 2마리 → 슬롯 1·2, HP 50% → 프레임 50 (= 2 + 49 − 1), 사망 → Off. 화면 `--ui gow-rom/ATHN01E.WAD FLP_Athn01E meter`
  (`work/uirt/meter.png`, 원작 히드라 미터 60%).
