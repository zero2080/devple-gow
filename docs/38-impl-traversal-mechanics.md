# 38. 구현: 이동 기술 (23/38 단계) — 표면 속성, 기어오르기·매달리기·사다리·줄·짚라인·수영·균형, R1 오브젝트, 발판, 이웃 레벨 스크립트

근거 문서: 12 §2.1 (SheetHdr), 13 (엔티티), 25 (영웅 이동, Navigation 표), 27 (GameScene 물리), 36 (순회 검증의 남은 막힘).
신뢰도 표기: **[확실]** 디스어셈블/데이터 대조, **[추정]** 맥락 판단, **[근사]** 원작과 다른 대체 구현.

## 0. 요약
- 충돌 시트 **표면 속성 해석 완료** [확실]: 표면 레코드 64B = 이름 24B + 데이터 40B, 배치는 시트 안의 **비트필드 스키마**(엔트리 0x4C B)가 정한다. Ground/Water/Narrow/Climbable/Ladder/Slide/Death … 30 개 비트 + SheetFX(발소리·이펙트 MFX 템플릿) 등 (§1).
- 새 패키지 `engine/nav`: 상태 기계 `HeroNavigator` — 벽 오르기(Climbable), 턱 매달리기·좌우 이동·올라서기, 사다리, 매단 줄 오르기·흔들기, 수평 줄, **짚라인**, 수영(Water), 균형 걷기(Narrow), Death 면, 상자 밀기, CSM 연타·상자 열기·레버 (§2·§3).
- 줄 오브젝트 `SCR_RopeSkinned` 해석 [확실]: 템플릿(Rope/Chain/RopeEastWest/ZipLine/RopeNorthSouth), 플래그, **끝점(미터)**. ATHN01D 의 `gorope3` 은 돛대 꼭대기 → **ATHN01E 갑판으로 가는 짚라인** — 36 문서의 "ATHN01E 도달 불가" 의 정체 (§2.3).
- 비헤이비어 리소스 BHV(0x17) 해석 [확실]: `IO_CSM` (템플릿, **과제 id**) — 쌍발차기 문 = CSM 44 / 과제 206, 원작 0x215928 이 **이벤트 1023 (인자 0 = 진행 %, 인자 1 = 과제 id)** 을 보낸다. `PushPull` (템플릿, 붙잡기 id → 1048/1049) (§3).
- **Marker 엔티티 = 오브젝트**(그룹 = Id) [확실]: 판도라 상자의 `pBoxPand05B-Marker511` 이 PAND05B 의 Filter 511 센서를 연다 (§3.3).
- 움직이는 발판: Animator 오브젝트의 CDV hull 윗면 (hull 관절 = BallHull off[6]) + 오브젝트 애니 타입 11 = 관절 이동 [추정, 수치 일치] → ATHN05D 엘리베이터 위 Warp 도착 해결 (§4).
- 이웃 레벨(다른 슬롯) 스크립트를 GameScene 이 같이 돌린다 (GlobalData 공유, LevelData 따로) (§5).
- 순회 검증: 주 경로의 강제 이벤트 전환 8 → 6. **ATHN01D → ATHN01F (R1 문)**, **PAND05B → HDES01A (상자 밀기)**, **ATHN01D → ATHN01E (짚라인)** 이 강제 없이 연결, ATHN04A → ATHN05D 도착 검사 통과 (§6).
- **후속 (§10)**: 남은 6 곳도 원작 동작으로 열림 → **주 경로 강제 0, ATHN01A → OLYMP02 를 걷기·상호작용·전투만으로 도달**. 과제 진행률 = 오브젝트 애니 재생(속도 1 / 되감기 −4), 레버도 1023, SpawnEnemy MaximumCount, 동영상 끝 501 보낸 쪽 = 플레이어, 두 슬롯 이벤트 공유, 헤드리스 보스전(HeadlessBoss), PAND02F → PAND02H 도착 = 바닥 구멍 낙하.

## 1. 충돌 시트 표면 속성 [확실: 데이터 102 시트]
SheetHdr 섹션 [3] = 표면 레코드 × count(0x50), 레코드 = 24 + dataSize(0x52 = 40). 섹션 [4] = 스키마 엔트리 0x4C 바이트:
`char[32] 이름, +0x20 u32 타입 (2 비트 / 0 정수 / 1 실수), +0x24 기본값, +0x3C u32 바이트 오프셋, +0x44 u32 비트 번호(레코드 기준), +0x48 u32 폭`.
`bitfield0..3` 은 워드 자체, 이어서 속성 이름. 전 시트 같은 배치:

| 데이터 | 내용 |
|---|---|
| +0x00 bitfield0 | Ground 0, Water 1, Narrow 2, Climbable 3, Ladder 4, Slide 5, Death 6, AIBlock 16, ClimbGuide 17, PushPullSlide 18, BackPress 19, NoPlayerCollision 20, NoAICollision 21, NoPlayerUse 22, NoAIUse 23, GeneralGuide 24, NoDiving 25, NoPushPullCollision 26, CombatGuide 27, NarrowNoBB 28, NoCSMCollision 29 |
| +0x04 bitfield1 | TakeDamage 0 (기본 1), NoIK 1, NoDecals 2, DarkSurface 3, NoWallHang 4, CamRelControl 5, NoLadderSliding 6, NoBackJumps 7, NoShadows 8 |
| +0x10 | doubleSided (워드) |
| +0x14 | SheetFX (≤ 999) → TWK `/TweakTemplates/MFX/<n>/` ("Foot Sound": 20 Rock → SND_FOOTSTEP_STONE, 30 Wood …) |
| +0x18 / +0x1C | PartType (기본 8) / WallHangTimer (기본 60) |
| +0x20 / +0x24 | Damage / AIDamage |

예: ATHN01D `visuals01D_Climbable` = Climbable|TakeDamage|NoBackJumps, SheetFX 73 (돛대 그물 317 면). `visuals01D_noPlayerNoAI` = Ground + NoPlayer/NoAI Collision/Use.
전 레벨 면 수: Ground 229k, Slide 21k, NoWallHang 18.6k, ClimbGuide 1182, Climbable 1159, Narrow 604, Death 438, Water 400, Ladder 150, PushPullSlide 113.
예외: PAND03D 사각형 10 개의 표면 번호(111, 127 … 271, 16 간격)가 레코드 수 14 밖 → Ground 로 취급.

코드: `tools/level/SurfaceAttr`(이름 ↔ 비트), `SheetSurface`(레코드), `CollisionSheet.surfaces`, `LevelCollision.Tri.attrs/info`, `floorAt` / `surfaceAt` / `wallcast`.
**NoPlayerCollision** 면(AI 전용 길·AI 사망면·투명 벽)은 영웅 질의(바닥·벽)와 순회 검증 바닥 칸에서 뺀다 — 이전엔 HDES01C 의 AI 사망면을 영웅 바닥으로 보고 있었다.
발소리는 이제 SheetFX → MFX "Foot Sound" (GameScene `footSound`).

## 2. 영웅 내비게이션 (`engine/nav`)
| 클래스 | 내용 |
|---|---|
| `NavState` | GROUND · AIR · LEDGE_HANG · WALL_CLIMB · LADDER · ROPE · ROPE_TRAVERSE · ZIPLINE · SWIM · PUSH · INTERACT |
| `NavBody` | 내비게이터가 움직이는 몸 (`HeroController` 구현, 테스트는 스텁) |
| `NavAnims` | TWK `/Animation/goHero/Navigation/` 키 → act (WallhangI/Catch/R/L, bClimb = wClimbBlend0..3, LadderUp/Down, dismount1, ropeIdle/Up/Down, ropeSwing*, ropeHOH(I), zipLine, swim/swimIdle, balWalk, Push/Pull/pushIdle, crankIdle, leverPull, chestGrab/Open) — 전부 R_HERO0 에 존재 확인 |
| `NavWorld` | 정적 충돌(현재 + 다른 슬롯) + 발판 + 줄 |
| `NavRope` | 줄 선분 (§2.3) |
| `Platforms` | 움직이는 발판 (§4) |
| `Interactables` | R1 오브젝트 (§3) |
| `HeroNavigator` | 상태 기계. `update(in, camYaw, dt)` 가 true 면 그 틱 이동을 처리 (HeroRig 는 전투·점프·지상 이동 생략, GameScene 물리 생략) |

### 2.1 진입 규칙 [근사: 원작 판정 함수 미분석, 표면 비트와 Navigation 동작만 원작]
| 상태 | 진입 | 조작 | 종료 |
|---|---|---|---|
| WALL_CLIMB | 공중에서 정면 벽이 Climbable, 또는 지상에서 그 벽 쪽 스틱 + × | 스틱 상하좌우 (속도 28), 벽을 따라 법선 갱신 | 위 끝: 벽 너머 또는 몸 위 바닥으로 올라섬(wallPullup) / 아래 끝 바닥 / × 벽 점프 / ○ 떨어짐 |
| LADDER | 정면 Ladder 벽 + 스틱 (지상도) | 상하만 (32) | 꼭대기 dismount1 |
| LEDGE_HANG | 공중 하강 중 정면 벽(NoWallHang 아님) 위 턱이 손 높이(발 + 34 ± 16) | 좌우 이동(턱 높이 따라감) | 위/× 올라서기, 아래/○ 놓기 |
| ROPE | 손(발 + 30) 22 안의 매단 줄 (공중, 또는 지상 + ×) | 위/아래 오르내리기, 좌우 = 진자 흔들기 (최대 = SCR 각도) | × 접선 속도로 뛰어내림 |
| ROPE_TRAVERSE | 양끝 고정 줄 | 줄 방향 스틱으로 손 이동 | 끝 바닥 / ×·○ 놓기 |
| ZIPLINE | 짚라인 | 낮은 끝으로 가속 (중력 성분 ×2, 60..420) | 끝에서 진행 방향으로 놓아 착지, × 도중 이탈 |
| SWIM | 발밑 바닥 = Water | 수면 − 16 높이로 헤엄 (40) | 수면 − 20 이상 마른 바닥에 닿으면 올라섬 |
| 균형 걷기 | 발밑 Narrow | 지상 유지, `balanceWblend1` + 속도 0.55 배 | – |
| Death 면 | 발밑 Death | 사망 통지 (GameScene `heroDied`) | – |

치수는 영웅 애니에서: 서 있는 키 32.6, 매달린 손 35.9, 줄 손 32.7 유닛.

### 2.3 줄 `SCR_RopeSkinned` [확실: 전 레벨 27 개]
GO CXT 바로 뒤의 72 바이트 SCR: `+0x20 kind(18), +0x24 템플릿, +0x28 각도(도), +0x2C u32[4] 플래그, +0x3C vec3 끝점(미터 ×16)`.
템플릿 번호는 전역: 0 Rope · 1 Chain · 2 RopeEastWest · 3 ZipLine · 4 RopeNorthSouth (각 레벨 TWK `/TweakTemplates/Rope/<n>/` Instance Name 일치; ZipLine 은 "Slide Sound" = SND_ROPEZIP_LP).
분류: f2 = 짚라인 (ATHN01D, ATHN01E, PAND03E, PAND04E), f1 만 = 양끝 고정 수평 줄 (ATHN02B, PAND01D, PAND03D, PAND04C), 나머지 = 매단 줄.
- **ATHN01D gorope3**: 매단 점 (-994, 1201, -1762) = 돛대 꼭대기 발판(1120) 바로 위, 끝점 (-2656, 788, -593) = **ATHN01E 시작 지점 옆 갑판**. ATHN01E gorope2 는 반대 방향 (ATHN01D 갑판으로).
- 돛대 오르기 경로: 뒤쪽 그물 (z −1896, y 180..620) + 앞쪽 Climbable 띠 (z −1840, y 880..1120) → 꼭대기 발판 → 점프로 짚라인 잡기 → ATHN01E 갑판 착지 → 다른 슬롯 바닥만 밟으므로 WALK 전환 (36 문서 §2).
- 줄 모양은 곧은 선분 [근사: 원작은 질점-스프링 시뮬레이션, TWK Segment Mass/Spring Damping/Gravity].

## 3. R1 오브젝트 (`Interactables`)
GO 비헤이비어 `tools/level/GoBehavior` (BHV 서버 0x17, GO CXT 다음 리소스, 0x28 B): `+0x04 클래스 이름, +0x20 템플릿, +0x24 id` [확실].
전 레벨: IO_CSM 27, PushPull 68, IO_TandF 37 (+ Breakable).

### 3.1 CSM (상황 동작) — 쌍발차기 문
- ATHN01D `gotwinkickdoor` = IO_CSM 템플릿 44 ("HydraKickDoor", 영웅 애니 #b50a547c = `doorKick`), 과제 id **206**. `gonewcaptainunlock` = 45 / 202 (`captainsDoorOpen`).
- 원작 0x215928 [확실]: `pct = clamp(round(100·t/len), 0, 100)`, 100 인데 t < len 이면 99, 바뀌면 `PostEvent(0x3FF = 1023, pct, taskId(+0x1CC), …)`. t/len = 오브젝트 애니 시간/길이, 재생 속도 +0x1E0 (입력 없으면 −4 로 되감음).
- 스크립트: `TwinKickDoor-main-taskP` = 1023 ∧ EventArg(1) == 206 ∧ EventArg(0) ≥ 30 → 동영상 Semo02 → 501 → Warp(ATHN01F). 상자 메시지: 인자 1 == 1 ∧ 인자 0 == 100.
- ~~구현 [근사]: R1 한 번 = 진행 +12 %~~ → §10.1: 진행률 = **오브젝트 애니 시간 / 길이** (붙어 있으면 속도 1, 떨어지면 −4 로 되감김) [확실: 0x215DF0]. 연타 유지 판정(마지막 R1 0.6 초 안)만 [근사]. 100 % → 완료 (상자는 chestOpen + 오브 이미터 HealthChest/MagicChest/RaidenChest1).
- 상자 = Creator Name0 "chest" (unk5 = CSM 템플릿, unk6 = 과제 id [추정]).

### 3.2 PushPull — 밀기 상자, 크랭크, 레버
- 붙잡기/놓기 = 이벤트 **1048 / 1049, 인자 0 = BHV id** (PAND05B `pBoxPand05B-EventAttach`: EventArg(0) == 503) [확실: 데이터].
- 템플릿 TWK `/TweakTemplates/Push/<n>/` (Instance Name, Radius, Acc/Dec, Push Sound). Instance Name 에 Crank/Lever/Puller → LEVER (애니 한 번 = 붙잡기+놓기) [근사], 그 밖 → PUSH.
- PUSH [근사]: 영웅→상자 방향의 주축(x 또는 z)으로만, 상자 반지름(CDV 경계구) 구가 벽에 밀리면 막힘, 상자 밑 바닥 필요. 움직인 만큼 소유 GO 의 Marker 이동.

### 3.3 Marker = 오브젝트 [확실: 데이터]
엔티티 타입 14 Marker 는 소유 GO 관절에 붙은 오브젝트로, **그룹 = Id 슬롯**. 진입 센서 Filter 가 그 그룹을 기다린다:
PAND05B `pBoxPand05B-Marker511` → `semoMovieHaveBox-…-semo08marker511` (Filter 511) → Semo08 → Warp(HDES01A); ATHN05D 엘리베이터 `Marker599` (관절 2) → `MarkerVolume1` (Filter 599).
`ScriptWorld` 가 시작 시 Marker 액터를 만들고 (`markers`, `moveOwnerBy`, `poseOwner`), 36 문서의 "강제 오브젝트 그룹" 이 필요 없어졌다. 생성 통지(CreationSensor)는 보내지 않는다 [추정].

## 4. 움직이는 발판 (`Platforms`)
- 후보: Animator 엔티티(타입 5/6)가 가리키는 GO 중 CDV(BallHull 0) 가 있는 것. 밟는 hull = 평면 재질 이름에 ground/floor (전 레벨 253 개 조사 — 칼날·불·화살 함정 DamageMat 제외).
- **BallHull off[6] = hull 별 관절 번호** [확실: 엘리베이터 hull 0 → 관절 2, hull 1 → 관절 1]. 이전에는 모든 볼륨을 관절 0 에 붙였다 (ENV/CMV 쪽은 그대로).
- **오브젝트 애니 타입 11 (unk10 = 4) = 관절 이동** (이동 타입 5 가 없는 세트만) [추정, 수치 일치]: ATHN05D `WarpElevator5D` 스트림 5/9 (관절 1/2 의 Y) 절대값 138.47 = 바인드 로컬 Y, 가산으로 −52.2 까지 → 발판 윗면 871 → **677 = Warp 도착점 WarpFrom4A 높이**. (`Animation.objectTracks`)
- 윗면 = hull 평면들로 수직선 구간을 잘라 위 끝. 서 있으면 지지 관절의 틱 간 이동을 영웅에 적용 (carry). Animator `AnimName "!n"` = n 번째 act [추정].
- 도착점 아래 정적 바닥이 없으면 발판 애니 중 윗면이 도착점 ±24 에 오는 시각으로 자세를 맞춘다 [근사] → ATHN05D 도착 해결.
- 한계: 발판 모델의 렌더링 자세는 아직 바인드 (충돌·Marker 만 움직임). CDV 벽(문·창살)은 영웅을 막지 않는다.

## 5. 이웃 레벨 스크립트 (`SceneNav`)
- GameScene 이 다른 슬롯 레벨(LoadWad/이음 전환으로 남은 레벨)의 `ScriptWorld` 를 같이 틱한다. 호스트 = `ScriptHost.neighbour(main)`: GlobalData·초기화 기록 공유, LevelData 따로.
- 이웃 리스너는 메시지·사망 존·**LoadCheck(→ 현재 리스너와 같은 이음 규칙)**·Goto 만 받는다. 이웃의 LoadWad/Warp 는 원작에서 "호출한 레벨이 아닌 슬롯" = 현재 레벨 자리에 로드하므로 무시, 적 생성·상자 생성은 그 레벨로 넘어간 뒤 [근사].
- 이웃 월드는 시작 시 영웅 위치 볼륨을 "이미 안" 으로 둔다.

## 6. 순회 검증 변경 (`TraversalVerifier`)
- 첫 보행 뒤 **R1 상호작용 단계** (강제 아님): CSM·상자 = 1023 (10..100 %, 과제 id), LEVER = 1048/1049, PUSH = 1048 → 상자 Marker 를 그 그룹 센서 볼륨으로 → 1049 [근사: 경로 없음]. 보고서 "R1 상호작용".
- **줄 연결**: 짚라인/수평 줄의 시작점 근처(288)에 L 바닥이 있고 끝점 아래(96 / ±144)에 이웃 레벨 X 바닥이 있으면 WALK L→X (출처 "줄 …").
- Warp 도착 검사: 바닥 없으면 발판 자세 맞춤까지 본다. 바닥 칸에서 NoPlayerCollision 제외.
- 결과 (38 단계 당시): ATHN01A → OLYMP02 52 전환 그대로, 주 경로 강제 전환 **8 → 6** (DEST03→PAND00A, PAND00C→PAND04AA, PAND04AA→PAND04C, ATHN08A→08B→08C→07B), ATHN01D→ATHN01F "R1 gotwinkickdoor", PAND05B→HDES01A "밀기 gopboxpand05b → ENV_semoMovieHaveBox", ATHN01D ≈ ATHN01E "줄 gorope3 (ZIPLINE)". 도착 검사 실패 2 → 1 (PAND02F → PAND02H: 근처에 발판 없음, 칼날 함정만 — 미해결).

## 7. 게임 씬 연결
| 위치 | 내용 |
|---|---|
| `HeroRig.nav` / `update` | 내비게이션 상태면 전투·점프·지상 이동 대신 `HeroNavigator.update`, 애니만 진행 |
| `HeroController` | `NavBody` 구현, 공중 발사 속도 `launch`, 지상 이동 모양 `locomotion` (균형 걷기) |
| `SceneNav` | NavWorld·Interactables·발판·이웃 스크립트, 내비게이터 → 1023/1048/1049, Animator → 발판 애니, Creator chest |
| `GameScene` | `ground()` = NavWorld (발판 포함), `physics` 는 내비게이션 상태면 생략 + 발판 운반, 도착점 발판 맞춤, 발소리 SheetFX |

## 8. 테스트
| 테스트 | 내용 |
|---|---|
| `nav/HeroNavigatorTest` (12, 데이터 불필요) | 벽 오르기→올라서기, 일반 벽은 안 오름, 턱 매달리기·좌우·올라서기, 사다리, 짚라인 끝 착지, 매단 줄 오르기·흔들기·뛰어내리기, 균형/Death, 수영→물가, 상자 밀기(1048/1049·이동량), 벽에 막힘, CSM 연타 1023 (≥30 % 과제 206, 100 은 한 번), 되감기 |
| `nav/NavDataTest` (8) | ATHN01D 표면 레코드, 전 시트 스키마·Ground, 줄 해석(gorope3 짚라인 끝 아래 ATHN01E 바닥), BHV(CSM 44/206, Pandora's Box 503, 크랭크 LEVER), Marker 511 → Semo08, 엘리베이터 운반 871 → 677, 도착 자세, **ATHN01D 실제 충돌로 그물·돛대 오르기 → 꼭대기 1120 → 점프로 짚라인 → ATHN01E 바닥 착지** |
| `TraversalTest` +1 | 3 막힘이 강제 없이 연결, ATHN05D 도착 |
| `ScriptWorldTest` +1 | 이웃 호스트 GlobalData 공유 |

## 9. 남은 것
- 원작 판정 함수(벽 잡기 거리·턱 높이·줄 잡기 반경, 0x18Cxxx Navigation 코드)는 미분석 — 수치는 애니 크기 기준 [근사]. 줄 공격(10 문서 rope attack), 벽 점프 연속, 다이빙(NoDiving), 미끄럼(Slide) 경사 미구현.
- ~~CSM 진행률은 R1 연타 근사~~ (§10.1 원작 재생 속도 모델). 영웅 쪽 CSM 기술의 연타 판정(붙음 유지 조건), 크랭크 회전 입력(Acc/Dec)은 미분석. IO_TandF(들어 올리는 문, 같은 1023 송신 0x213F90) 미구현.
- 발판 렌더 자세, CDV 벽 충돌, 운반 회전 (yaw), 이웃 레벨의 적 생성.
- ~~순회 검증: 남은 강제 전환 6, PAND02F → PAND02H 도착~~ → §10 해결. PAND00C 링 퍼즐 자체(크랭크 → 링 회전 → Marker 101 정렬 → 204/205 로 엘리베이터 레버 보이기)는 하네스가 Vis 로 상호작용을 막지 않으므로 검증하지 않음 [근사].

## 10. 후속: 남은 강제 전환 6 곳과 PAND02H 도착 해결
결과 (`work/verify/report.md`): ATHN01A → OLYMP02 52 전환, **주 경로 강제 0** (`reach(START, false)` 에 OLYMP02), 전환 186 (강제 뒤 12 — 보너스 아레나 9, ATHN01E→01D, PAND01C→01B, PAND02AA→02A), **도착 검사 실패 0**, 헤드리스 보스 4 종 격파.

| 전환 | 무엇이 여는가 (원작) | 고친 것 |
|---|---|---|
| DEST03 → PAND00A | 첫 동영상 d01Mp01 끝(501) → 세이렌 3 마리 × **MaximumCount 3** = 9 처치 (LevelData0 == LevelData1 = 9) → d01Mp03 → LevelData3 → 두 번째 뿔피리 CSM 1031 → ThirdMpeg + `LoadForWarp(Pand00A)`/`Warp` (Delay 12.2) [확실: 데이터] | 하네스가 501 을 보낸 쪽 null 로 보냄 → `…MpegDone` (Filter 1) 무반응. GameScene 처럼 보낸 쪽 = 플레이어. R1 상호작용 ↔ 보행 2 회 (뿔피리 2 가 LevelData3 를 기다림) |
| PAND00C → PAND04AA | `ElevatorLever-LeverProgress`: **1023 인자 1 == 111 ∧ 인자 0 == 100** → `LoadForWarp(Pand04AA)` + `Warp` (Delay 30). 111 = 레버 `goelevatorlever` 의 **PushPull id** — 레버도 과제 진행률을 보낸다 [확실: §10.1] | 레버(PushPull) 를 잡고 있는 동안 레버 애니(3.667 초) 재생 → 1023 (§10.1). 전엔 1048/1049 만 |
| PAND04AA → PAND04C | `MpegPlayer` Semo06 끝(501, Filter 1) → LevelData1 → `LoadWad(Pand04C)` → LoadCheckC | 501 보낸 쪽 (위와 같음) |
| ATHN08A → ATHN08B | 아레스 1단계 `608 MAIBARGRABWIN` 끝 **1047(608), 보낸 쪽 그룹 600** → a07Mp01 → LoadForWarp athn08b → Warp | 헤드리스 보스전 (§10.4) |
| ATHN08B → ATHN08C | 분신 크레토스 4 파도: SpawnEnemy 마다 **MaximumCount** (2 파 7·7·8·8·8·8, 3 파 4×5+5, 4 파 4×5+5 = 25 = LevelData31). 파도 i 의 사망 카운터 == 최대 → 다음 파도 SpawnEnemy (조건 `LevelData(2i) == LevelData(2i+1)`) → 4 파 25 처치 → winEnt (Delay 6) A07MP05 → 501 → Warp [확실: 합 일치 + 0x1AC580] | `ScriptWorld.spawn` 이 MaximumCount 를 지킴 (전엔 무한 재생성이라 카운터가 최대를 지나쳐 3 파가 다시 안 나옴). 하네스: 죽여도 스크립트가 반응하지 않는 적(가족 660 — 죽으면 실패)은 죽이지 않음, 전투 마무리 단계 |
| ATHN08C → ATHN07B | 아레스 3단계 칼 맞대기 → 622 → 390/395 → **1035(395)** → Semo10 → 501 → Warp | 헤드리스 보스전 |
| PAND02F → PAND02H 도착 | 발판이 아니다. `ENV_LoadCheckH` (y −1610..−1413) 가 PAND02F 바닥(−1528)의 **구멍** (x 700–720, z 5117–5199) 을 덮고, 그 아래 PAND02H 구덩이 바닥 −1904 로 **떨어진다** (낙하 ≈ 380). GameScene 의 SEAMLESS 판정(다른 슬롯 바닥 600 아래까지)은 이미 받는다 | 하네스 도착 검사: 보행 점(구멍 옆 PAND02F 바닥)만 보지 않고 LoadCheck 볼륨 안 점 중 떠나온 레벨 바닥에 막히지 않고 대상 바닥으로 떨어지는 곳을 찾는다 (`fallInto`) |

### 10.1 과제 진행률 = 오브젝트 애니 재생 (`nav/TaskProgress`) [확실: 디스어셈블]
- 1023 송신 함수 3 개가 **같은 코드**: 0x215928 (IO_CSM, +0x1C4/+0x1C8/+0x1CC), 0x2118E8 (+0x27C/+0x280/+0x284, 호출 0x2127B0 ← 0x212398), 0x213F90 (+0x1C8/+0x1CC/+0x1D0, 호출 0x214400). 0x2118E8 = PushPull [강한 추정: 레버/크랭크 id 111·21·22·120 을 1023 인자 1 로 기다리는 센서, Push TWK 의 크랭크 소리], 0x213F90 = IO_TandF [추정].
- 인자: `PostEvent(1023, pct, id, (int)(속도 × 100), 오브젝트)`. pct = clamp(round(100·t/len)), 100 인데 t < len 이면 99. **pct 또는 속도가 바뀔 때마다** 보낸다.
- 속도 (CSM 0x215DF0): 영웅이 붙어 있으면(+0x190 ≥ 0) **1.0**, 떨어졌고 t > 0 이면 **−4.0**, t = 0 에서 정지. t ≥ len 이고 속도 > 0 이면 완료 → 0x215928(1.01, 1, 1) = 100. PushPull 0x2127B0 도 같은 속도 상수(1.0 / −4.0, +0x268).
- 길이 = 오브젝트 애니: CSM TWK `#fea292e6` act (쌍발차기 문 "Default" 0.9 초), 없으면 가장 긴 act (레버 3.667, 링 크랭크 12, PAND04C 크랭크 24). 길이 0 (DEST03 뿔피리 "main") = 붙자마자 완료.
- 게임: `HeroNavigator` CSM/상자 = 마지막 R1 뒤 0.6 초 안이면 붙음 [근사: 영웅 CSM 기술의 유지 조건 미분석], 레버 = 잡고 있는 동안. 놓은 뒤에도 오브젝트는 −4 로 되감기며 1023 을 보낸다. `SceneNav` 가 인자 3 개로 보냄.
- 상자(Creator "chest") 의 오브젝트 애니는 아직 없음 → 길이 1 초 [근사].

### 10.2 SpawnEnemy MaximumCount [확실: 0x1AC580]
- SpawnEnemy 슬롯 0 = **MaximumCount** (`"MaximumCount %d exceeded (%d)"`): 0 이 아니면 만든 수(+0x100) 가 이 값에 닿은 뒤 발동은 생성하지 않는다. 메시지 6(제거) 은 사망 수(+0x104)++ 후 Respawn(슬롯 3) 이면 자기 Trigger (조건 슬롯 평가 포함, 0x29D93C = 최소 지연) [확실: 0x1AC630].
- `EntitySlots` 의 SpawnEnemy `unk0` → `MaximumCount`.

### 10.3 두 슬롯 이벤트 공유 (`ScriptWorld.linkEvents`)
- PostEvent(0x1A8C78) 는 큐 하나(0x303DB8)에 넣고 전달(0x1AAB00)은 엔티티 목록 전체를 돈다 [확실: 큐 하나 / 추정: 두 슬롯 엔티티가 한 목록]. 그래서 PAND00C EyeDoor 204 → PAND00D FakeEyeDoor 동영상 p00MP03, PAND00D 205 → PAND00C 엘리베이터 동상·레버 표시 같은 **레벨 간 이벤트**가 있다.
- GameScene: `SceneNav.setOther` 가 이웃 월드와 잇는다 (게임 이벤트·EventTransmitter 모드 5 가 양쪽으로). 하네스는 레벨 단독이라 쓰지 않음.

### 10.4 헤드리스 보스전 (`boss/HeadlessBoss`)
- 37 문서 테스트용 BossRig 의 핵심을 본 코드로: 크레토스 Combatant + HeroController + BossFight(autoPlay 데모) + CombatScriptBridge + GrabSync, GameScene.update 순서. 순회 검증이 보스 레벨(ATHN01E/02D/08A/08C)에서 보행·R1 단계 뒤 60 Hz 로 최대 240 초, 격파 후 20 초 (컷신 → Warp) 돌린다.
- 데모가 보스 HP 를 마무리 임계 밑으로 내리는 것은 플레이어 전투를 대신한다 [근사]. 그 뒤 잡기 → 연타 → 타이밍 버튼 → 마무리 기술 → 1047/1035 → 컷신 → Warp 는 원본 데이터 그대로.
- 결과: 히드라 (구역 → 내려찍기 잡기 3 회 → 기절 → 처형), 메두사 여왕 (시선 → 석화 탈출 → 머리 뜯기), 아레스 1 (잡기 원 2000/1000/500 → 잡기 연타 → 608), 아레스 3 (칼 맞대기 → 395) 모두 격파. 보고서 레벨 상세 "보스전 (헤드리스)".

### 10.5 하네스 변경 (`TraversalVerifier`)
| 변경 | 이유 |
|---|---|
| 동영상 끝 501 의 보낸 쪽 = 플레이어 | Filter 1 `…MpegDone` (DEST03, PAND04AA …) |
| R1 상호작용 ↔ 보행 2 회, 레버도 1023 (`TaskProgress` 로 애니 끝까지) | 앞 단계 진행을 기다리는 과제, 엘리베이터 레버 |
| 죽이면 스크립트가 반응하는 적만 0.5 초 뒤 제거 (DestructionSensor 모드 2/3/4 또는 Respawn) | ATHN08B 가족(지킬 대상) |
| 전투 마무리: 적이 8 초 동안 없을 때까지 (최대 180 초) | 파도 전투 + Delay 6 동영상 |
| 보스 단계 (HeadlessBoss) | 아레스 1/3, 히드라, 메두사 여왕 |
| SEAMLESS 도착 검사: LoadCheck 볼륨 안에서 대상 바닥으로 낙하 (`fallInto`) | PAND02F → PAND02H |
| `gow.trace=<레벨>` 시스템 속성 / `trace` 필드: 그 레벨의 요청·동영상·스폰을 시간순 출력 | 디버그 |

### 10.6 테스트
| 테스트 | 내용 |
|---|---|
| `nav/TaskProgressTest` (4, 데이터 불필요) | 속도 1 로 0.9 초 → 100 (그 전 99 이하, 100 은 한 번), 놓으면 −4 되감기·속도 변화도 송신, 길이 0 즉시 완료, 99 규칙 |
| `nav/HeroNavigatorTest` +1 | 레버 1048 → 1023 (100, id 111, 속도 100) → 1049 |
| `nav/NavDataTest` +1 | 애니 길이: 쌍발차기 문 0.9, PAND00C 엘리베이터 레버 LEVER id 111 3.667, DEST03 뿔피리 0 |
| `script/ScriptWorldTest` +3 | ATHN08B 파도: 4 파 25 · 전체 100 생성 → A07MP05 → Warp, PAND00D 205 → PAND00C LevelData22 (이음/끊음) |
| `verify/TraversalTest` +3 | 주 경로 6 곳 강제 없음 + `reach(START, false)` ∋ OLYMP02, 보스 4 종 격파, PAND02H 도착 = 낙하 · 전체 도착 실패 0 |

