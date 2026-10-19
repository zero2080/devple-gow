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
| `NavRope` | 줄 선분 (§2.3, 잡기 판정·양끝 고정 줄) |
| `RopeSim` | 매단 줄 질점-스프링 (원작 0x217A40 / 0x217478, §2.3) |
| `Platforms` | 움직이는 발판 (§4) |
| `Interactables` | R1 오브젝트 (§3) |
| `HeroNavigator` | 상태 기계. `update(in, camYaw, dt)` 가 true 면 그 틱 이동을 처리 (HeroRig 는 전투·점프·지상 이동 생략, GameScene 물리 생략) |

### 2.1 진입 규칙 [벽·턱 기하 = 원작 (§2.4) / 속도·점프·줄 잡기 = 원작 (§2.2) / 지상 진입 입력은 엔진 규칙]
| 상태 | 진입 | 조작 | 종료 |
|---|---|---|---|
| WALL_CLIMB | 공중에서 정면 벽 질의 (0x1E47E0, §2.4) 가 Climbable, 또는 지상에서 그 벽 쪽 스틱 + × | 스틱 상하좌우 (좌우 24 · 상하 32, 길이 [8, 32]), 매 틱 같은 질의로 벽 + 9.6 에 맞춤 | **올라가다 손 높이에 턱 (0x1E3A80) → 턱 매달리기** (원작 0x1C78FC), 턱 판정이 안 되는 꼭대기는 벽 너머/몸 위 바닥으로 올라섬 [엔진 보조] / 아래 끝 바닥 / **스틱 벽 반대 + × = 등 뒤 점프** (NoBackJumps 면 불가) / ○ 떨어짐 |
| LADDER | 정면 Ladder 벽 + 스틱 (지상도) | 상하만 (32), 스틱 끝까지 아래 = 미끄럼 (ladderslide, 최대 160) | 꼭대기 dismount1 |
| LEDGE_HANG | 공중 (타이머 남음) 에서 턱 질의 (0x1E3A80, §2.4): 정면 12.8 의 바닥 (NoWallHang·Slide 아님) 이 손 높이 (발 + 35.2) ± max(이동, 3.2), 내려가는 중이면 손이 턱 아래 — **벽 오르기보다 먼저** | 좌우 이동 16 (매 틱 턱 질의로 턱 높이·벽 법선 따라감) | 위/× 올라서기, 아래/○ 놓기, WallHangTimer 다 쓰면 떨어짐 (데이터는 전부 0 = 무제한) |
| ROPE | 잡기 점(발 + 키 35.2) 32 안의 매단 줄 (공중, 또는 지상 + ×) | 위/아래 오르내리기 (24 × 스틱, 최소 8), 스틱 끝까지 아래 = 줄 미끄럼 (ropeslide, 최대 120), 스틱을 새로 밀면 흔들기 힘 창 (0.25 초, 500 × 거리) — 줄 모양은 `RopeSim` | × 줄 점 속도 + 정면 40 + 점프 초속으로 뛰어내림 [근사] |
| ROPE_TRAVERSE | 양끝 고정 줄 | 줄 방향 스틱으로 손 이동 (40 × 스틱, 최소 20) | 끝 바닥 / ×·○ 놓기 |
| ZIPLINE | 짚라인 | 낮은 끝으로 0 → 320 (가속 160, 기울기 무관) | 끝 0.25 m 앞에서 속도 0 으로 놓음 (그 자리 낙하), × 도중 이탈 |
| SWIM | 발밑 바닥 = Water (수면 아래 32 안에 단단한 바닥이 없을 때) | 수면 − 28.16 높이로 헤엄: 정면을 스틱 쪽으로 돌리며 (프레임당 0.15) 속도 → 120 × 스틱 (최소 16, 가감속 120) | 수면 − 32 이상 마른 바닥에 닿으면 올라섬 |
| 균형 걷기 | 발밑 Narrow | 지상 유지, `balanceWblend1` + 속도 배율 1 (Sold+0x230) | – |
| Death 면 | 발밑 Death | 사망 통지 (GameScene `heroDied`) | – |

치수: 원작 몸 = Sold+0x238 키 2.2 m (35.2), 반폭 +0x234 0.6 m (9.6), 바닥 +0x23C 0.4 m (6.4) (0x1E6D58: 내비 +0x120 = (폭 19.2, 키 35.2, 깊이 19.2), +0x130.y = 6.4, +0x140 = 키 − 바닥 = 28.8). 애니 실측은 서 있는 키 32.6, 매달린 손 35.9, 줄 손 32.7 유닛.
엔진 상수: `BODY_W` 19.2, `BODY` (반폭) 9.6, `FOOT` 6.4 (내비 위치 = 발 + 6.4 [추정: 0x1E47E0 좁은 턱 바닥 맞춤 y = 바닥 + +0x130.y]), `REACH` 28.8, `HANG` 35.2, `WALL_OFF` 9.6, `WALL_PROBE` 14.4 / `WALL_SPREAD` 8. 전 근사 BODY 14 · WALL_OFF 10 · CHEST 20 · HANG 34 폐기.

### 2.2 원작 내비게이션 수치 (`nav/NavTuning`) [확실: 디스어셈블 + 데이터 대조]
**영웅과 적은 같은 내비게이션 코드(0x1EBD78)를 쓰고, 모든 속도·가속은 (owner+0x320)+8 = TWK_Sold 구조체에서 읽는다** (32 문서 §이동 속도 출처). 크라토스 = R_PERMK `TWK_Sold_000`.
단위 m(/s), 월드 ×16. `NavTuning.KRATOS` 에 값을 두고 `NavDataTest.heroNavigationTuningIsSold000` 이 원본 Sold_000 (와 R_SKS Sold_020) 과 대조한다. `HeroNavigator` 상수는 전부 이 값 × 16.

| 상태 (+0x174) | 필드 (Sold_000) | 읽는 곳 | 엔진 값 | 규칙 |
|---|---|---|---|---|
| 공중 중력 | /GlobGame/ "Gravity" **50** | 등록 0x1BF2B0 → 0x337718, 사용 0x1EBC0C (밀기 상자 0x211B34 도) | 800 /s² | 수직 속도 −= g·dt (+0x324+0x2C 배율 1 [추정]). 줄 질점 시뮬레이션만 줄 TWK "Gravity" 9.82 |
| 최대 낙하 | +0xDC 50 | 0x1EBC4C | 800 | 수직 속도 ≥ −800 |
| 점프 | +0xC4 **15.25** | 0x1E9E88 (점프 0x1E9B68, × = +0x310 비트 1) | 244 | 현재 수직 속도에 더함 → 정점 ≈ 37 (전엔 45 [추정]). 애니 = 전진 속도 1 m/s 미만이면 ANIVJump (0x11) 아니면 ANIJump (0x10) (0x1E98B8). **전투 기술이 아님** |
| 2단 점프 | +0xC8 15.25, +0xCC 20.009, +0xD0 −100 | 0x1EA130 | 244 / 320 / −1600 | 공중(0x4/0x8) 이고 능력 +0x2DC 0x40, 수직 속도가 (−1600, 320) 안 → 수직 속도 = 244, ANIDblJump/ANIVDblJump |
| 공중 수평 | +0xEC 7.5, +0xD8 7.5, +0xE4 0.05 | 0x1EB650–0x1EB7AC | 120, 120 /s², 프레임당 0.05 | 정면 속도 → 120 × (스틱·정면), 가감속 120. 정면은 스틱 쪽으로 프레임당 0.05 회전 [추정: 0x1E60E8 보간]. 엔진 `HeroController.airUpdate` (점프 직전 지상 속도에서 시작) |
| 지상 | +0x178 10, +0x16C 100 / +0x170 50 | 0x1EAFF0 | – | 목표 160 × 스틱. 엔진 영웅은 애니 이동량 그대로 [근사], 적은 이미 Sold (32) |
| 좁은 길 | +0x230 1 | 0x1EAFB0 (+0x2D8 비트 4) | 배율 1 | 전 근사 0.55 폐기 [추정: 비트 4 = Narrow] |
| 벽 오르기 (0x20) | +0x10C 1.5, +0x110 2, +0x108 0.5 | 0x1C8D08 | 24, 32, 8 | 속도 = (스틱 좌우 × 24, 스틱 벽 쪽 × 32), 길이 > 0.1 이면 [8, 32] 로 자름 (대각선도 32 이하) |
| 벽 등 뒤 점프 | +0x11C 10, +0x118 17, +0x10C | 0x1E9D9C, 0x1E9A88 | 160, 272 | **스틱이 벽 반대** (벽 쪽 성분 < 0 → 이탈 속도 −160) + ×, 좌우 속도 ≤ 12, 면이 NoBackJumps 아님 → 돌아서서 벽 반대 160·위 272 (ANIWJump). 그 밖의 × 는 무시 (전엔 × = 법선 70 점프 [근사]) |
| 턱 매달리기 (0x10) | +0x104 1 | 0x1C8A24 | 16 | 좌우만, 스틱 크기 무관. 애니 0xC–0xF (WallHang/Catch/R/L). 시간 제한 = 턱 면 WallHangTimer (+0x1C) → +0x2A4 (0 이면 FLT_MAX, 이미 돌고 있으면 이어 감, 0x1C772C), 매 프레임 −dt × (vtable +0xC8 × 0.25 + 0.75) × +0x16C [배율 추정 1] 해서 0 이면 떨어짐 (0x1C7B04), 공중 턱 판정은 +0x2A4 > 0 일 때만, 지상·+0x174 2·0x80·벽 오르기 때 초기화 (0x1C78A0) [확실: 엔진 구현]. **데이터: 전 레벨 표면 1027 개 모두 0 = 무제한** |
| 사다리 (0x400000 = 면 Ladder) | +0x124 2 | 0x1C8C24 | 32 | 스틱 부호 × 32 (크기 무관). 애니 0x48–0x4A |
| 사다리 미끄럼 | +0x128 0.9, +0x12C 10, +0x130 20, +0x134 40 | 0x1C8A88 | 0.9, 160, 320, 640 | LadderSlide 능력 (+0x2DC 0x4000) + 면이 NoLadderSliding 아님: 내려가는 중 (< −16) 스틱 아래 > 0.9 → 미끄럼 (+0x2D8 0x8000, ANILSlide 0x4D), 유지 = 가속 320 (최대 160), 놓음 = 감속 640, −32 보다 느려지면 끝 |
| 좁은 턱 벽 따라 걷기 (0x100000 Narrow·NarrowNoBB / 0x200000 BackPress) | +0x168 1.25 | 0x1C8CBC, 0x1C7398 | 20 | ANIWallFront/Back (0x35–0x3A) [엔진 미구현] |
| 줄 잡기 | 키 +0x238 2.2, 반경 32 | 0x1C5C20 (공중 0x4C 일 때만), 0x1C5CF0 | 35.2, 32 | 잡기 점 = 발 + 위축 × 35.2, 줄 위 몸 = 줄 점 − 35.2 (0x1C66E4). 줄 플래그 +0x374: 4 → 0x10000, 1·2 → 0x40000 (짚라인), 1 → 0x20000 (수평 줄), 0 → 매단 줄 (0x1C5DA4). 엔진의 지상 + × 잡기는 "점프해서 잡기" 단축 [근사], 가슴 높이 보조 판정은 없앰 |
| 매단 줄 | +0x138 1.5, +0x13C 0.5 | 0x1C6578 → 0x1E6080 | 24, 최소 8 | 줄 위치 (m) += 속도/16·dt, 매단 점에서 0.25 m (플래그 4 줄은 0.5) 이상 (0x1C6608) |
| 줄 미끄럼 | +0x14C 7.5, +0x150 20, +0x154 20 | 0x1C6448 | 120, 320, 320 | RopeSlide 능력 (+0x2DC 0x8000): 스틱 아래 > 0.8 → 속도 0 에서 가속 320 (최대 120), 놓으면 감속 320, 0 이면 끝 (ANIRSlide) |
| 줄 흔들기 | +0x148 500 | 0x1C6BE8 | 500 N × 거리 (m) | f21 = +0x148 × 영웅 줄 위치 +0x294, 0.25 초 창 (0x29DBF4) 동안 2 × 남은 시간 배 → 매달림 힘 (0x218470) → `RopeSim` [확실: 크기 / 추정: 방향 = 스틱, 창 재개 = 스틱 새로 밈 (0x303FB0/0x303FC0 미해석)] |
| 수평 줄 (0x20000) | +0x158 2.5, +0x15C 1.25 | 0x1C6370 → 0x1E6080 | 40, 최소 20 | 32 문서의 "+0x158 줄 이동 [추정]" = **수평 줄 속도** (해골 -C Sold_020 2 m/s) |
| 짚라인 (0x40000) | +0x160 20, +0x164 10 | 0x1C60F8 → 0x1E5FE0 | 320, 160 /s² | 기울기와 무관하게 목표 속도로 가속, 방향 = 낮은 끝 (0x1C5E04). 줄 위치를 [0.25, 길이 − 0.25] 로 자르고, 끝에 닿으면 월드 속도 0 · 0x1E9030(2) 로 놓음 (0x1C6B1C) [확실: 속도 / 추정: 끝 처리 경로가 짚라인에도 같음]. 잡을 때 속도 0 [추정] (전엔 중력 성분 ×2, 60..420 [근사]) |
| 수영 (0x100 Swim, 0x80 Tread, 0x200 잠수) | +0x1D4 7.5, +0x1D8 4.5, +0x1DC 1, +0x1E0/+0x1E4 7.5, +0x1E8 0.15 | 0x1CA7D0–0x1CA868, 0x1CA62C | 120 (Tread 72), 최소 16, 120 /s², 프레임당 0.15 | 정면 각을 스틱 각 쪽으로 프레임당 0.15 돌리고 정면 속도를 목표로 가감속. 0x80 (물 위 떠 있기, ANITread 0x28/0x29) 는 4.5 m/s — 엔진은 0x100 (ANISwim 0x26) 하나 [근사]. 물에서 점프 가능 (0x80, 0x1E9C2C) [미구현] |
| 수면 깊이 | +0x1EC 0.2 | 0x1C9C00 | 28.16 | 물 판정 높이 = 키 × (1 − 0.2) (몸 위 20 % 가 물 밖) [추정: 판정 높이 = 떠 있는 높이]. 얕은 물 (수면 아래 32 안에 단단한 바닥) 은 걷는다 |
| R1 오브젝트 | 반경 32 | 0x1C3F18 | 32 | 전 근사 BODY + 14 = 28 |
| 밀기 (무게 w) | +0x17C/0x180/0x184 0.5, +0x188/0x18C/0x190 1.9 / 1.5 / 1.2 | 0x1C44E0 – 0x1C4610 | 30.4 / 24 / 19.2, 최소 8 | 붙은 오브젝트 쪽 영웅 속도 = 스틱 × 최대 [w], 0 이 아니면 최소 이상. w = Push 템플릿 +0x1A "Weight" (vtable +0xE0 = 0x244FB0) — §3.5 |
| 상자 차기 | +0x194–0x19C 0, +0x1A0/0x1A4/0x1A8 24 / 16 / 9, +0x1AC 2 | 0x1C4928 – 0x1C4A68 | lerp × 16 | × 누르고 있는 동안 충전 +0x2B8, 떼면 0x41D + 상자 속도 += 정면 × lerp(최소[w], 최대[w], 충전 / 2) — §3.5 |

애니 슬롯 번호 (+0x328+0xE0) = TWK Navigation 표 순서 (0 ANIStand … 0x10 ANIJump, 0x11 ANIVJump, 0x13 ANIDblJump, 0x26 ANISwim, 0x28 ANITread, 0x34 ANIWallPullup, 0x3B ANIRope, 0x47 ANIZipLine, 0x48 ANILadder, 0x4B ANILDis1, 0x4D ANILSlide, 0x4E ANIPushI …) — 위 주소의 즉값과 표가 모두 맞는다 [확실].
게임 쪽: `GameScene` 영웅 중력 = 800, 최대 낙하 800 (적 낙하 중력도 같은 전역 값). `EnemyNav` 은 아직 9.82×16 (다른 작업 파일, 후속).

### 2.3 줄 `SCR_RopeSkinned` [확실: 전 레벨 27 개]
GO CXT 바로 뒤의 72 바이트 SCR: `+0x20 kind(18), +0x24 템플릿, +0x28 각도(도), +0x2C u32[4] 플래그, +0x3C vec3 끝점(미터 ×16)`.
템플릿 번호는 전역: 0 Rope · 1 Chain · 2 RopeEastWest · 3 ZipLine · 4 RopeNorthSouth (각 레벨 TWK `/TweakTemplates/Rope/<n>/` Instance Name 일치; ZipLine 은 "Slide Sound" = SND_ROPEZIP_LP).
분류: f2 = 짚라인 (ATHN01D, ATHN01E, PAND03E, PAND04E), f1 만 = 양끝 고정 수평 줄 (ATHN02B, PAND01D, PAND03D, PAND04C), 나머지 = 매단 줄.
- **ATHN01D gorope3**: 매단 점 (-994, 1201, -1762) = 돛대 꼭대기 발판(1120) 바로 위, 끝점 (-2656, 788, -593) = **ATHN01E 시작 지점 옆 갑판**. ATHN01E gorope2 는 반대 방향 (ATHN01D 갑판으로).
- 돛대 오르기 경로: 뒤쪽 그물 (z −1896, y 180..620) + 앞쪽 Climbable 띠 (z −1840, y 880..1120) → 꼭대기 발판 → 점프로 짚라인 잡기 → ATHN01E 갑판 착지 → 다른 슬롯 바닥만 밟으므로 WALK 전환 (36 문서 §2).
- 판정·잡기는 곧은 선분. **매단 줄에 매달리면 `RopeSim`** (원작 0x217A40 갱신 + 0x217478 한 단계) [확실: 디스어셈블]:
  - 템플릿 /TweakTemplates/Rope/n/ (0x2168C0, 전 레벨 같은 값): +0x18 Segment Mass 0.2, +0x1C #51fee0b7 10000 (이웃 스프링), +0x20 Spring Damping 10, +0x24 #ad717afb 500 / +0x28 #480eb083 5 (한 칸 건너 굽힘), +0x2C #1f59f04a 5000 / +0x30 #893d4e1c 5 (매단 점 – 매달림 스프링), +0x34 Global Damping 0.003, +0x38 Gravity 9.82, +0x3C #9a6419f6 8 (60 Hz 하위 단계 수).
  - 미터 단위 (관절 × 1/16). 노드 0 = 매단 점, 노드 수 = 줄 모델 사슬 관절 수 (0x216FC8, 엔진은 줄 오브젝트 관절 수 [추정]), 칸 길이 = 길이 / (n − 1).
  - 한 단계: 이웃 F = (k(|d| − L) + c(d̂·Δv)) d̂, 굽힘 (i, i+2, 쉼 2L). v += F h/m, 힘 = (0, −g m, 0), p += v h, **v ×= (1 − 감쇠)** (하위 단계마다 → 감쇠율 ≈ 1.44 /초, 흔들림이 빨리 줄어 계속 밀어야 함). 매달림 힘을 거리 비율로 j, j+1 에, 매단 점 – j 스프링 (쉼 1.1 j L, 5000 × 무게 비율 × (1 − frac)).
  - 프레임: 0.2 초 정지면 잠듦 (감쇠 = Global Damping), 매달린 무게 = 최대 (매단 줄 100, 양끝 고정 10, 0x216D84) × 0.5 /초로 늘고 줄어듦, 노드 질량 = 0.2 + 무게 (j, j+1 비율), 하위 단계 int(8·dt·60 + 0.99), 감쇠 −0.05·dt (최소 Global Damping).
  - 영웅: 잡기 = 0x218200 (반경 = 상자 폭 × 0.5 × 1.75 / 16) + 0x2183B0 위치 [0.25, 길이 − 0.25], 이벤트 0x41B (1051). 몸 = 줄 점 (0x2184E8) − 키. 매 프레임 매달림 힘 (0x218470) — 흔들기 §2.2.
  - 양끝 고정 줄 (수평 줄·짚라인) 과 줄 스키닝 렌더는 곧은 선분 그대로 [근사].

### 2.4 벽·턱 판정 기하 (`HeroNavigator.probeWall` / `probeLedge`) [확실: 디스어셈블]
호출 (0x1C7828, 영웅·적 공용 0x1EB3D8 / 0x1C88F0 / 0x1CA448 에서): 공중 (+0x174 0x4C) 이면 +0x2A4 > 0 일 때 턱 0x1E3A80 → 아니면 벽 0x1E47E0 (a3 = 1),
벽 오르기 (0x20) 중엔 올라갈 때 (수직 속도 > 0) 턱 → 사다리면 내려섬 (ANILDis1 0x4B, 지상 상태) / 벽이면 매달림 (0x1C75F8), 그다음 벽 질의 (저장된 법선 +0x220 사용).
선분 질의 = sub_0011E190 (레벨 충돌 선분), 면 속성 = bitfield0 (0x3376EC) / bitfield1 (0x3376F0). 엔진 `NavWorld.segment` (영웅 충돌 면, 바닥·벽 모두).

**벽 0x1E47E0** (a0 내비, a1 새 위치, a2 옛 위치, a3 머리 높이 재시도, t0 등 쪽 (BackPress 0x200000 이면 축 반대), t1 막힘 반환):
1. o = 내비 위치 (= 발 + 6.4). o → o ∓ 오른쪽 × 8 + 정면 × 0.75 × 깊이 (14.4) 두 선분 (0x1E46B0), 면 마스크 0x1008001C (Narrow·Climbable·Ladder·BackPress·NarrowNoBB). 둘 다 맞고 (반환 0xA) 법선 내적 > 0.9.
2. 법선 n = 붙어 있으면 +0x220, 아니면 정규화(n1 + n2). |n·위| < 0.5.
3. 머리: u = 벽 면 위 방향 (n × (n × Y) 의 반대), s = o + u × (+0x140 − 1 = 27.8) → s − n × 14.4 가 마스크 면, 법선 내적 > 0.85 (0x20). 아니면 0x10.
4. 0x20 이면 n' = 세 적중점 평면 법선 (+0x220 에 저장), 위치 = 두 적중 중점 + n' × 0.5 × 깊이 (**9.6**) — 반환 1. 첫 면이 좁은 턱 마스크 (0x10080004) 면 아래 바닥 맞춤 (y = 바닥 + 6.4).
5. a3 = 1 이고 실패면 머리 높이 (o + 27.8) 에서 Climbable (마스크 8) 두 선분으로 다시 → 반환 0x41. t1 = 1 (벽 오르기 중) 이면 한쪽만 맞을 때 옛 위치로 되돌리고 1/3/5 (막힘).
엔진: 1–4 (Climbable·Ladder), 5 와 좁은 턱·등 쪽 벽은 [미구현].

**턱 0x1E3A80** (일반 턱):
1. d = max(|새 − 옛|, 3.2). 정면 0.2 m (3.2) + 0.5 × 깊이 앞, 좌우 ∓ 폭 / 4 (4.8) 에서 위 (+0x140 + d) → 아래 (+0x140 − d) 수직 선분 둘. 둘 다 맞고 Slide (bitfield0 0x20)·NoWallHang (bitfield1 0x10) 아님, 법선 y > 0.85. 턱 = 중점.
2. 내려가는 중 (새 y < 옛 y) 이면 턱 − +0x140 ≥ 새 y (손이 턱을 지났음). o + (+0x140 + d) → 턱 + 1 선분이 막히면 실패.
3. 벽: b = 턱 − 위 × (+0x140 + d) − 정면 × 0.5 깊이에서 정면 × 깊이 ± 오른쪽 × 0.5 폭 두 선분 (없으면 b + 위 × (+0x140 − 2) 에서 다시), b + u × (+0x140 − 1) 에서 정면 × 깊이 선분도 맞아야.
4. 위치 = 두 벽 적중 중점 + 세 점 평면 수평 법선 × 9.6, 높이 = 턱 − +0x140 (발 = 턱 − **35.2**). 반환 1, 턱 면 → +0x234 (WallHangTimer), 법선 +0x220.
엔진: 1–4. 좁은 턱 (Narrow·NarrowNoBB, 0x1E3EE8 경로) 과 한쪽만 맞는 모서리 (반환 8, 0x1E4528) 는 [미구현].
매달림 중 좌우 이동도 매 틱 턱 질의로 다시 맞춘다 (원작: 실패하면 떨어짐 0x1E9030, 엔진은 그 자리 [근사]).

## 3. R1 오브젝트 (`Interactables`)
GO 비헤이비어 `tools/level/GoBehavior` (BHV 서버 0x17, GO CXT 다음 리소스, 0x28 B): `+0x04 클래스 이름, +0x20 템플릿, +0x24 id` [확실].
전 레벨: IO_CSM 27, PushPull 68, IO_TandF 37 (+ Breakable).

### 3.1 CSM (상황 동작) — 쌍발차기 문
- ATHN01D `gotwinkickdoor` = IO_CSM 템플릿 44 ("HydraKickDoor", 영웅 애니 #b50a547c = `doorKick`), 과제 id **206**. `gonewcaptainunlock` = 45 / 202 (`captainsDoorOpen`).
- 원작 0x215928 [확실]: `pct = clamp(round(100·t/len), 0, 100)`, 100 인데 t < len 이면 99, 바뀌면 `PostEvent(0x3FF = 1023, pct, taskId(+0x1CC), …)`. t/len = 오브젝트 애니 시간/길이, 재생 속도 +0x1E0 (입력 없으면 −4 로 되감음).
- 스크립트: `TwinKickDoor-main-taskP` = 1023 ∧ EventArg(1) == 206 ∧ EventArg(0) ≥ 30 → 동영상 Semo02 → 501 → Warp(ATHN01F). 상자 메시지: 인자 1 == 1 ∧ 인자 0 == 100.
- ~~구현 [근사]: R1 한 번 = 진행 +12 %~~ → §10.1: 진행률 = **오브젝트 애니 시간 / 길이** (붙어 있으면 속도 1, 떨어지면 −4 로 되감김) [확실: 0x215DF0]. ~~연타 유지 판정(마지막 R1 0.6 초 안) [근사]~~ → §3.4: 문은 R1 한 번에 doorKick 0.9 초 동안 붙어 있어 100 %, 상자는 누르고 있기 [확실]. 100 % → 완료 (상자는 chestOpen + 오브 이미터 HealthChest/MagicChest/RaidenChest1).
- 상자 = Creator Name0 "chest" (unk5 = CSM 템플릿, unk6 = 과제 id [추정]).

### 3.2 PushPull — 밀기 상자, 크랭크, 레버
- 붙잡기/놓기 = 이벤트 **1048 / 1049, 인자 0 = BHV id** (PAND05B `pBoxPand05B-EventAttach`: EventArg(0) == 503) [확실: 데이터].
- 템플릿 TWK `/TweakTemplates/Push/<n>/` (Instance Name, Type, Radius, Acc/Dec, Push Sound). Instance Name 에 Crank/Lever/Puller → LEVER, 그 밖 → PUSH. LEVER 중 Type 5/6 = 레버 (leverpull 한 번), 그 밖 = 크랭크 (누른 채 스틱, Acc/Dec) — §3.4 [확실].
- PUSH: 상자 속도·무게·차기 = §3.5 [확실]. 축은 영웅→상자 방향의 주축(x 또는 z)으로만, 상자 반지름(CDV 경계구) 구가 벽에 밀리면 막힘, 상자 밑 바닥 필요 [근사]. 움직인 만큼 소유 GO 의 Marker 이동.

### 3.3 Marker = 오브젝트 [확실: 데이터]
엔티티 타입 14 Marker 는 소유 GO 관절에 붙은 오브젝트로, **그룹 = Id 슬롯**. 진입 센서 Filter 가 그 그룹을 기다린다:
PAND05B `pBoxPand05B-Marker511` → `semoMovieHaveBox-…-semo08marker511` (Filter 511) → Semo08 → Warp(HDES01A); ATHN05D 엘리베이터 `Marker599` (관절 2) → `MarkerVolume1` (Filter 599).
`ScriptWorld` 가 시작 시 Marker 액터를 만들고 (`markers`, `moveOwnerBy`, `poseOwner`), 36 문서의 "강제 오브젝트 그룹" 이 필요 없어졌다. 생성 통지(CreationSensor)는 보내지 않는다 [추정].

### 3.4 붙음 유지 규칙 — R1 오브젝트를 언제 잡고, 붙들고, 놓는가 [확실: 디스어셈블]
"연타로 문을 찬다"는 것은 원작 동작이 아니다. 문·책·레버는 **한 번 누르면 영웅 애니가 끝날 때까지** 붙어 있고, 상자·크랭크는 **누르고 있기**, 연타는 **IO_TandF 들어 올리는 문**에만 쓴다.
영웅 쪽 기술(전투 기술 분기 표)과는 관계가 없다. 판정은 영웅 갱신 0x1EBD78 의 붙잡기 경로와 오브젝트별 갱신 함수에 있다.

**입력** — 플레이어 컨트롤러 0x1D7FD0 이 매 프레임 영웅 +0x310 (입력 구조체 +0x2E0 의 +0x30) 비트를 만든다:
- 버튼 표 0x29E0F8: 패드 비트 → 논리 비트. 원작 "사용" 버튼 = 패드 비트 1 (표준 배치 R2. 버튼 안내 아이콘도 `R2ButtonStatic`, 42 문서 §1). 엔진도 R2 (`PadButton.USE`), 밀기 상자는 누르고 있는 동안만 (떼거나 × → 놓기, 상자 차기 미구현).
- 논리 비트 0x100000 → 누르고 있음 **+0x310 0x100**, 새로 누름 **0x80** (2 프레임 버퍼 0x29DC60). 0x80000 새로 누름 → **0x1000**. 붙어 있을 때 × 누르고 있음 → 0x400.

**잡기** — 0x1C4008 (0x1EBD78 가 영웅 +0x2DC 0x10000 일 때 부름): 붙어 있지 않음(+0x174 0x4000), 사용 버튼 새로 누름(+0x310 0x80), 전투 기술 중 아님.
0x1C3F18 이 반경 32 안의 오브젝트를 찾고 0x1C3498 이 오브젝트 클래스 비트(+0xF4)로 붙잡기 모드를 고른다 → 0x1E17F0(영웅, 오브젝트, 관절 슬롯, 모드):

| 클래스 비트 | 모드 (+0x194) | 종류 | 영웅 애니 슬롯 (표 0x29E2B8, 0x24 B, +0x20 = 끝나면 다음 슬롯) |
|---|---|---|---|
| 0x800 | 1 | 밀기 상자 (Push Type 0) | 0x4E ANIPushI, 0x4F–0x52 방향, × → 0x53 ANIStartKickBox → 0x54 |
| 0x1000 | 2 (영웅 쪽이면 0x80000002) | 크랭크류 (Type 1–4) | 0x56 ANICrankIdle, 스틱 앞/뒤 → 0x57–0x5C Push/Pull (크랭크 +0x10C 종류별 L/R) |
| 0x10000 | 4 | 레버 (Type 5) | 0x5D ANILever `leverPull` |
| 0x20000 | 8 | 물속 레버 (Type 6) | 0x5E ANIUWLever `leverPullUW` |
| 0x200000 | 0x10 | IO_TandF | 0x5F ANITandF1 (#ff5f5921) → 0x60 ANITandF2 (#dc77f412) → 끝나면 0x61 ANITandF3 (#4e2b8035) |
| 0x400000 | 0x40 (+0x174 0x200 이 없으면 0x20) | IO_CSM·상자 | 0x62 ANICSM1 (#b50a547c) → **끝나면 0x63** ANICSM2 (#b50a547d) |

- 0x1E17F0 은 플레이어면 **1048 (인자 0 = 모드 1/2/4/8 → PushPull +0x284, 0x10 → TAndF +0x1D0, 0x20/0x40 → CSM 과제 id +0x1CC)**, 0x1E18F8 (놓기) 은 **1049** 를 보낸다. CSM 도 붙잡기/놓기 이벤트를 보낸다.
- Push "Type" 은 TWK 값의 하위 바이트 (상위는 쓰레기): 0 상자, 1 크랭크(Crank 30F/20B·Core Ext Crank·PBox Lever·CoffinLid…), 2 대포, 3 기중기, 4 링 크랭크, 5 레버(newLeever·R2Lever·BMI Log Puller), 6 물속 레버, 7 얼음, 8 테트리스 블록. 갱신 0x212398 이 Type 으로 0x212998 / 0x2127B0 을 고른다.

**붙들기 / 놓기** — 붙어 있는 동안 0x1C4258:

| 모드 | 붙어 있는 조건 | 놓는 때 |
|---|---|---|
| CSM 0x20/0x40 | 애니가 0x62 인 동안: CSM 템플릿 +0xB8 비트 0 (**TWK #fb908478**) 이 켜져 있으면 사용 버튼을 누르고 있어야 함 (+0x310 0x100), 꺼져 있으면 무조건 | 비트가 켜졌는데 뗌 → 바로. 0x62 가 끝나 0x63 (또는 다른 애니) 이 되면 그 애니가 끝날 때 → 오브젝트 클래스 0x400000 을 끄고 (다시 못 잡음) 놓음 |
| 레버 4 / 물속 8 | 애니 0x5D / 0x5E 가 끝날 때까지 (입력 무관) | 애니 t/len ≥ 1 (0x1C4C6C, 0x1ECB24) |
| 크랭크 2 | 사용 버튼을 누르고 있음 + 크랭크 클래스 비트 0x1000 이 켜져 있음 | 뗌, 또는 크랭크가 끝에 닿아 0x1000 이 꺼짐 |
| 밀기 1 | 사용 버튼을 누르고 있음 (+ 상자와 32 이내) | 뗌 (엔진 구현). × 누르고 있음 = 상자 차기 충전 (0x53 → 0x54), 떼면 차기 0x55 (§3.5) [엔진 구현] |
| TAndF 0x10 | 0x60 에서 누를 때마다 마지막 누름 시간 0x29DBF0 = 0, 누르고 있거나 스틱·관절축 내적 ≤ 0.5 | 0x61 이 끝날 때 (클래스 0x200000 끔), 또는 버튼을 뗀 채 스틱을 축 방향으로 (내적 > 0.5) |

- 공통: 전투 기술이 붙잡기 상태 비트(0x1C) 없는 것으로 바뀌면 (피격 등) 0x1ECD70, 사망 0x1EBE48, 오브젝트가 꺼짐(+0x174 1/2 없음) 이면 놓는다.
- CSM 템플릿 비트 (0x215248–0x21528C, +0xB8): 4 = #ca686863 (버튼 안내 "Show R2" [추정]), **1 = #fb908478 (누르고 있기)**, 2 = #e88fda39. 데이터: 상자류 (chest 002–004·008, Shard 010/011, SirenChest 022, Hydra* 040–043·047, Pinata/BigRed 060–065) = 1, 문 (SkullDoor 001, KickBridge 015/162, HydraKickDoor 044, HydraUnlockCap 045, WheelUnlock 023), 책 007, 샤워 018, 뿔피리 021, 선장 051, 왕좌 090, 손잡이 017 = 0.

**오브젝트 진행** (1023 = 오브젝트 애니 위치, §10.1):
- CSM 0x215DF0: 붙어 있으면 속도 1, 떨어지면 t > 0 일 때 −4 와 함께 클래스 0x400000 을 끄고 (**되감는 동안 못 잡음**), t = 0 에서 0x80400000 을 다시 켠다. 끝 → 오브젝트 애니 상태 1 (#fea292e7, chestOpen), 오브.
- **데이터: 영웅 ANICSM1 길이 = 오브젝트 애니 길이** — doorKick 0.9 = 문 "Default" 0.9, captainsDoorOpen 4.833, captainHangPullup 6.0, readBook 2.367, skullKey01 1.9, skullKeyUW 3.467, timePiece 3.833, bmiDoorOpen 3.167 (왕좌 3.333 + ThroneStand 3.333 ≥ 3.967). 그래서 한 번 잡아 끝까지 붙어 있으면 정확히 100 % 가 된다.
- 레버 0x2127B0: 붙어 있으면 속도 1, 끝에 닿으면 **속도 0 으로 멈추고** 클래스 0x30000 을 끔 → 1023 (100, id, 0). 떨어지면 −4 (클래스 끔, 0 에서 0x8001/0x8002 켬). leverPull 3.8 초 > 레버 3.667 초.
- 크랭크 0x212998 (Type 1): 붙어 있고 t < len: 입력 = 영웅 이동 방향 · 크랭크 관절 축 (0x2117E0, 붙잡은 쪽 비트 31 로 부호) > 0 이면 속도 += **Acc**·dt (최대 #fe467fe6), 아니면 −**Dec**·dt (0 까지). 떨어지면 속도 −= #b6ba95a3·dt (최대 −#b503fbe4). 끝 → 클래스 0x1000 끔. Push 템플릿: +0x2C Acc, +0x30 Dec, +0x34 #b6ba95a3, +0x38 #fe467fe6, +0x3C #b503fbe4 (0x2163E0–). 링 크랭크 (Type 4, 0x212D08) 는 양방향 [미분석].
- TAndF 0x214400: 위치 +0x1E4 (0..1), 속도 +0x1C4. 붙어 있으면 위치 += 속도·dt/4, **누를 때마다 속도 += Acc** (최대 #fe467fe6, dt 곱 없음), 마지막 누름 뒤 **TF Time** 이 지나면 속도 −= Dec·dt (최대 −#b503fbe4). 떨어지면 속도 ≤ 0 으로 내려옴. 위치 ≥ 1 → 완료 (2.0). TAndF 템플릿 +0x18 Acc, +0x1C Dec, +0x20 #fe467fe6, +0x24 #b503fbe4, +0x28 TF Time (0x213640–).

**엔진** (`HeroNavigator.interact`, `Interactables`, `TaskProgress.advance/lift`):
- CSM/상자: R1 → 1048 (과제 id), ANICSM1 길이 (`NavBody.clipLength`, 없으면 오브젝트 애니 길이) 동안 붙음, `hold` (#fb908478) 면 R1 을 누르고 있어야 함 → ANICSM2 (상자 chestOpen, 선장 captainHangExit, 왕좌 ThroneStand) → 1049. 상자 (Creator) 길이 = 영웅 chestGrab 1.967 초 [추정: 상자 ANM 미확인].
- 레버: leverpull 끝까지 (물속 레버 leverPullUW). 크랭크: R1 누른 채 스틱 > 0.3 이면 가속 [근사: 관절 축 대신 스틱 크기], 떼면 놓고 #b6ba95a3 로 되감기. TAndF: 위 식 그대로, 잡기 ANITandF1 → ANITandF2, R1 을 뗀 채 스틱을 문 반대로 (> 0.5) 밀면 놓음 [근사: 관절 축 대신 영웅→문 반대 방향].
- 되감는 중인 오브젝트는 0 으로 돌아갈 때까지 잡히지 않는다. ○/스틱으로 CSM·레버를 놓는 엔진 전용 동작은 없앴다 (원작에 없음).
- 검증 하네스(`TraversalVerifier.runTask`)는 그대로: 붙잡은 채 `update(true)` 로 끝까지 돌린다 (플레이어 성공 가정).

### 3.5 밀기 상자 물리 (Push Type 0) [확실: 디스어셈블 / 접촉 전달은 추정]
- **무게 등급** w = Push 템플릿 +0x1A "Weight" (s16, 0x216450 등록). TWK 값의 하위 16 비트 — 데이터가 float 0.5 (0x3F000000) 의 아래 비트에 등급을 얹었다:
  0.5 → 0 (Pand01APush, CagePush …), 0.50000006 (0x3F000001) → 1 (HydraPushBox, MazePushblocks, 석상·테트리스 일부), 0.5000001 (0x3F000002) → 2 (Pandora's Box, Pand02A, Tetris Question/L/straight/T).
  "Type" 하위 바이트와 같은 방식 [추정: 0x181EA0 읽기].
- **영웅 쪽** (0x1C4258, 모드 1): 영웅 국소 속도 = 스틱 × Sold+0x188[w] (1.9 / 1.5 / 1.2 m/s), 길이가 Sold+0x17C[w] 0.5 보다 작으면 0.5 로 (0x1C44E0 – 0x1C4610). 영웅은 붙잡기 관절 − 위 × 반 키 + 정면 반 깊이 자리를 Sold+0xEC (120) 이하 속도로 따라감 (0x1C4828).
- **상자 쪽** (0x212398 Type 0 → 0x211A10): 지상이면 마찰 0x1E0910(계수 1): |v| > 1.6 이면 v ×= clamp(1 − 9.82·16·dt / |v|, 0, 1), 아니면 0. 중력 GlobGame 50·16, 공중 최대 낙하 800. 지상 속도 > 템플릿 #95b8bfb6 (+0x28, 5 m/s → 80) 이면 그 크기로 자름 (+0x29A 비트 2 = 차인 상태면 자르지 않고 80 아래로 떨어질 때 다시 잡을 수 있게 0x800 켬). 0x210DC8 = 상자 모서리 충돌, 0x211F10 = 밀기 소리 피치 (속도 / 80).
- 엔진: 상자 속도 = 영웅 밀기 속도 (축 방향) 를 상자 최대로 자름 [추정: 원작은 몸 접촉으로 전달], 안 밀면 마찰, 놓은 뒤에도 멈출 때까지 미끄러짐. 영웅은 상자 이동량을 그대로 따라감.
- **차기** (0x1C4928): 붙어 있고 × 누르고 있으면 (+0x310 0x400) 충전 +0x2B8 += dt, +0x2D8 0x20000 (ANIStartKickBox 0x53 KickBox1 → ANIKickBoxIdle 0x54 KickBox3). × 를 떼면 이벤트 **0x41D (1053)**, 상자 상태 2, 상자 속도 += 영웅 정면 × lerp(Sold+0x194[w] 0, +0x1A0[w] 24 / 16 / 9, clamp(충전 / Sold+0x1AC 2 초)) m/s. 엔진: ANIKickBox 0x55 KickBox2 재생, 상자 최대로 자른 속도로 마찰 감속하며 미끄러짐 (Pandora's Box 1 초 충전 4.5 m/s → 약 16.5 유닛) [추정: 차인 상자 자르기].
- 이벤트 1048/1049 (붙잡기/놓기)·Marker 이동 (`world.moveOwnerBy`) 그대로. PAND05B Pandora's Box 는 하네스가 Marker 를 센서로 옮기므로 속도와 무관.

## 4. 움직이는 발판 (`Platforms`)
- 후보: Animator 엔티티(타입 5/6)가 가리키는 GO 중 CDV(BallHull 0) 가 있는 것. 밟는 hull = 평면 재질 이름에 ground/floor (전 레벨 253 개 조사 — 칼날·불·화살 함정 DamageMat 제외).
- **BallHull off[6] = hull 별 관절 번호** [확실: 엘리베이터 hull 0 → 관절 2, hull 1 → 관절 1]. 이전에는 모든 볼륨을 관절 0 에 붙였다 (ENV/CMV 쪽은 그대로).
- **오브젝트 애니 타입 11 (unk10 = 4) = 관절 이동** (이동 타입 5 가 없는 세트만) [추정, 수치 일치]: ATHN05D `WarpElevator5D` 스트림 5/9 (관절 1/2 의 Y) 절대값 138.47 = 바인드 로컬 Y, 가산으로 −52.2 까지 → 발판 윗면 871 → **677 = Warp 도착점 WarpFrom4A 높이**. (`Animation.objectTracks`)
- 윗면 = hull 평면들로 수직선 구간을 잘라 위 끝. 서 있으면 지지 관절의 틱 간 이동을 영웅에 적용 (carry). Animator `AnimName "!n"` = n 번째 act [추정].
- 도착점 아래 정적 바닥이 없으면 발판 애니 중 윗면이 도착점 ±24 에 오는 시각으로 자세를 맞춘다 [근사] → ATHN05D 도착 해결.
- ~~한계: 발판 모델의 렌더링 자세는 아직 바인드~~ → 애니 자세로 그림 (42 §3·§5). ~~CDV 벽(문·창살)은 영웅을 막지 않는다~~ → 막는다 (42 §5).

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
| `nav/HeroNavigatorTest` (20, 데이터 불필요) | 벽 오르기→**턱 매달림**→올라서기, **벽 질의 (두 선분·머리·9.6), 턱 질의 (하강 손 높이·창·Slide·NoWallHang), WallHangTimer 떨어짐·재잡기 금지·착지 초기화, 무게 등급 밀기 속도 (1.2 m/s·최소 0.5), 상자 차기 충전·0x41D·마찰 미끄럼**, 일반 벽은 안 오름, 턱 매달리기·좌우·올라서기, 사다리, 짚라인 끝 착지, 매단 줄 오르기·흔들기·뛰어내리기, 균형/Death, 수영→물가, 상자 밀기(1048/1049·이동량), 벽에 막힘, **§3.4**: 문 R1 한 번 = doorKick 0.9 초 붙음 → 1023 (≥30 % 과제 206, 100 한 번) + 1048/1049 과제 id, 상자 누르고 있기·떼면 −4 되감기·되감는 중 못 잡음·chestGrab+chestOpen 뒤 놓음, 크랭크 Acc/Dec·떼면 되감기, TAndF 누를 때마다 +Acc·TF Time 뒤 −Dec, 레버 leverpull 끝까지·1023 (100, 111, 0) |
| `nav/TaskProgressTest` (+2) | 레버 끝 속도 0, TAndF 식 (0x214400) |
| `nav/RopeSimTest` (3) | 잠든 줄 자세, 매달린 무게 0 → 100 (×0.5 /초)·처짐, 흔들기 창 뒤 감쇠 진동 주기 |
| `nav/NavDataTest` (10) | `pushWeightsAndWallHangTimers`: Pandora's Box 무게 2·최대 5 m/s, HydraPushBox 0x3F000001, 전 레벨 WallHangTimer 전부 0. |
| `nav/NavDataTest` (9) | `attachRulesFromTemplates`: 문 hold 0 / 상자 42 hold 1, ANICSM2 (captainHangExit), 영웅 doorKick·captainsDoorOpen 길이 = 문 애니 길이, PAND02C 크랭크 (Type 1, Acc 3·Dec 1·최대 1.5·되감기 20/15), 레버 Type 5, 물속 레버 leverPullUW, ATHN01C TAndF 키. 그 밖 | ATHN01D 표면 레코드, 전 시트 스키마·Ground, 줄 해석(gorope3 짚라인 끝 아래 ATHN01E 바닥), BHV(CSM 44/206, Pandora's Box 503, 크랭크 LEVER), Marker 511 → Semo08, 엘리베이터 운반 871 → 677, 도착 자세, **ATHN01D 실제 충돌로 그물·돛대 오르기 → 꼭대기 1120 → 점프로 짚라인 → ATHN01E 바닥 착지** |
| `TraversalTest` +1 | 3 막힘이 강제 없이 연결, ATHN05D 도착 |
| `ScriptWorldTest` +1 | 이웃 호스트 GlobalData 공유 |

## 9. 남은 것
- ~~원작 판정 수치~~ → §2.2. ~~벽 잡기 선분 질의 기하·턱 높이~~ → §2.4, ~~WallHangTimer~~ → §2.2 (데이터 전부 0), ~~밀기 속도·상자 차기~~ → §3.5, ~~줄 질점 시뮬레이션~~ → §2.3 `RopeSim`. 남은 근사: 지상 벽 진입 입력 (원작 0x1C7E1C 조건 +0x2D8 0x80 / 국소 속도), 벽 질의 a3 머리 재시도 (0x41)·막힘 반환 (1/3/5), 좁은 턱 벽 따라 걷기 (+0x168, 0x1E3EE8 / 등 쪽 BackPress), 턱 모서리 (반환 8), 매달림 좌우 이동 실패 = 떨어짐, 흔들기 힘 방향 (0x303FB0/0x303FC0)·줄 이탈 속도 (0x1C5848)·양끝 고정 줄 시뮬레이션·줄 스키닝 렌더, 상자 로컬 축·좌우 밀기 (PullL/R)·몸 접촉 전달, 물 위 떠 있기(0x80)·잠수(0x200)·물에서 점프, 벽 꼭대기 점프 (+0x2D8 0x300: 위 +0x120 12 · 뒤 +0x114 10). 줄 공격(10 문서 rope attack), 벽 점프 연속, 다이빙(NoDiving), 미끄럼(Slide) 경사 미구현.
- ~~CSM 진행률은 R1 연타 근사~~ (§10.1 원작 재생 속도 모델). ~~영웅 쪽 CSM 기술의 연타 판정(붙음 유지 조건), 크랭크 회전 입력(Acc/Dec)~~ → §3.4 [확실]. ~~크랭크 입력 축~~·~~링 크랭크 Type 4~~·~~기중기 Type 3~~ (42 §5), 밀기 상자의 누르고 있기·× 걷어차기 (엔진 PUSH 는 R1 토글), 버튼 안내 아이콘 (0x21DA50 슬롯 2: 크랭크 0xD, TAndF 0xB).
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
- 게임: `HeroNavigator` 붙음 유지 = §3.4 (~~마지막 R1 뒤 0.6 초 [근사]~~). 놓은 뒤에도 오브젝트는 −4 로 되감기며 1023 을 보낸다. `SceneNav` 가 인자 3 개로 보냄.
- 상자(Creator "chest") 의 오브젝트 애니는 아직 없음 → 길이 = 영웅 chestGrab (1.967 초) [추정]. PushPull 레버는 끝에서 속도 0 (1023 인자 2 = 0), CSM 만 0x215928(1.01, 1, 1).

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

