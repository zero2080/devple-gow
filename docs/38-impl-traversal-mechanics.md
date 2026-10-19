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
- 구현 [근사]: R1 한 번 = 진행 +12 % (상자 25 %), 0.6 초 입력 없으면 초당 50 % 되감김. % 가 바뀔 때마다 1023 (같은 99/100 규칙). 100 % → 완료 (상자는 chestOpen + 오브 이미터 HealthChest/MagicChest/RaidenChest1).
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
- 결과 (`work/verify/report.md`): ATHN01A → OLYMP02 52 전환 그대로, 주 경로 강제 전환 **8 → 6** (DEST03→PAND00A, PAND00C→PAND04AA, PAND04AA→PAND04C, ATHN08A→08B→08C→07B), ATHN01D→ATHN01F "R1 gotwinkickdoor", PAND05B→HDES01A "밀기 gopboxpand05b → ENV_semoMovieHaveBox", ATHN01D ≈ ATHN01E "줄 gorope3 (ZIPLINE)". 도착 검사 실패 2 → 1 (PAND02F → PAND02H: 근처에 발판 없음, 칼날 함정만 — 미해결).

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
- CSM 진행률은 R1 연타 근사 (원작은 오브젝트 애니 재생 속도). 크랭크 회전량·레버 결과 이벤트(1048/1049 외) 미확인. IO_TandF(들어 올리는 문) 미구현.
- 발판 렌더 자세, CDV 벽 충돌, 운반 회전 (yaw), 이웃 레벨의 적 생성.
- 순회 검증: 남은 강제 전환 6 (보스·과제 대부분 아레스 전투/동영상), PAND02F → PAND02H 도착.
