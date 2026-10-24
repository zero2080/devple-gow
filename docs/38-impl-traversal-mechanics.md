# 38. 구현: 이동 기술 (23/38 단계) — 표면 속성, 기어오르기·매달리기·사다리·줄·짚라인·수영·균형, R1 오브젝트, 발판, 이웃 레벨 스크립트

근거 문서: 12 §2.1 (SheetHdr), 13 (엔티티), 25 (영웅 이동, Navigation 표), 27 (GameScene 물리), 36 (순회 검증의 남은 막힘).
신뢰도 표기: **[확실]** 디스어셈블/데이터 대조, **[추정]** 맥락 판단, **[근사]** 원작과 다른 대체 구현.

## 0. 요약
- 충돌 시트 **표면 속성 해석 완료** [확실]: 표면 레코드 64B = 이름 24B + 데이터 40B, 배치는 시트 안의 **비트필드 스키마**(엔트리 0x4C B)가 정한다. Ground/Water/Narrow/Climbable/Ladder/Slide/Death … 30 개 비트 + SheetFX(발소리·이펙트 MFX 템플릿) 등 (§1).
- 새 패키지 `engine/nav`: 상태 기계 `HeroNavigator` — 벽 오르기(Climbable), 턱 매달리기·좌우 이동·올라서기, 사다리, 매단 줄 오르기·흔들기, 수평 줄, **짚라인**, 수영(Water), 균형 걷기(Narrow), Death 면, 상자 밀기, CSM 연타·상자 열기·레버 (§2·§3).
- **후속 (§11–13)**: 물 하위 상태 (떠 있기 4.5 m/s·가라앉음 부력 15 m/s²·□ 잠수·R1 돌진·물에서 점프·물 밖 도약·NoDiving = 다이빙 자세 금지), 좁은 턱 벽 따라 걷기 (Narrow 벽 보고 / BackPress 등 대고, 1.25 m/s), 지상 벽 진입 = 앞으로 걸어 들어가기, 벽 옆 끝 막힘 + 90° 옆 점프 (+0x114/+0x120), 줄 흔들기 힘 방향 (잡을 때 축 0x303FB0/0x303FC0, ○ 누르는 동안 움직이는 쪽으로).
- 줄 오브젝트 `SCR_RopeSkinned` 해석 [확실]: 템플릿(Rope/Chain/RopeEastWest/ZipLine/RopeNorthSouth), 플래그, **끝점(미터)**. ATHN01D 의 `gorope3` 은 돛대 꼭대기 → **ATHN01E 갑판으로 가는 짚라인** — 36 문서의 "ATHN01E 도달 불가" 의 정체 (§2.3).
- 비헤이비어 리소스 BHV(0x17) 해석 [확실]: `IO_CSM` (템플릿, **과제 id**) — 쌍발차기 문 = CSM 44 / 과제 206, 원작 0x215928 이 **이벤트 1023 (인자 0 = 진행 %, 인자 1 = 과제 id)** 을 보낸다. `PushPull` (템플릿, 붙잡기 id → 1048/1049) (§3).
- **Marker 엔티티 = 오브젝트**(그룹 = Id) [확실]: 판도라 상자의 `pBoxPand05B-Marker511` 이 PAND05B 의 Filter 511 센서를 연다 (§3.3).
- 움직이는 발판: Animator 오브젝트의 CDV hull 윗면 (hull 관절 = BallHull off[6]) + 오브젝트 애니 관절 이동 (타입 0 디스크립터 1 — 예전 "타입 11 = 이동" 은 배치 착시, 05 §2.8.1) → ATHN05D 엘리베이터 위 Warp 도착 해결 (§4).
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
**GeneralGuide** 면은 영웅 몸을 막지만 바닥이 아니고 벽·턱 선분은 지나간다 (§18.4).
발소리는 이제 SheetFX → MFX "Foot Sound" (GameScene `footSound`).

## 2. 영웅 내비게이션 (`engine/nav`)
| 클래스 | 내용 |
|---|---|
| `NavState` | GROUND · AIR · LEDGE_HANG · WALL_CLIMB · **WALL_WALK** (§12) · LADDER · ROPE · ROPE_TRAVERSE · ZIPLINE · SWIM (물 하위 상태 TREAD/SINK/DIVE, §11) · PUSH · INTERACT |
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
| WALL_CLIMB | 공중에서 정면 벽 질의 (0x1E47E0, §2.4) 가 Climbable, 또는 **지상에서 그 벽 쪽으로 걸어 들어감 (× 불필요, 0x1C7DE8 §12.1)**, 물 위 떠 있기에서 앞으로 (§11) | 스틱 상하좌우 (좌우 24 · 상하 32, 길이 [8, 32]), 매 틱 같은 질의로 벽 + 9.6 에 맞춤 | **올라가다 손 높이에 턱 (0x1E3A80) → 턱 매달리기** (원작 0x1C78FC), 턱 판정이 안 되는 꼭대기는 벽 너머/몸 위 바닥으로 올라섬 [엔진 보조] / 아래 끝 바닥 / **스틱 벽 반대 + × = 등 뒤 점프** (NoBackJumps 면 불가) / **옆 끝에서 × = 90° 옆 점프** (§12.3) → 공중 0.2 초 뒤 틈 건너 같은 쪽 벽 잡기 (§15.1) / ○ 떨어짐 |
| WALL_WALK | 지상에서 Narrow·NarrowNoBB 벽 쪽으로 걸음 (벽 보고) / BackPress 벽 (착지 프레임 등 뒤 질의 또는 앞 질의, 등 대고) | 좌우만 20 (스틱 크기 무관), 매 틱 벽 + 9.6 · 발밑 바닥 맞춤 | 턱 끝·바닥 없음 → 떨어짐, 가만히 + 스틱 벽 반대 + × = 벽 반대 점프, ○ 놓기 [엔진] (§12) |
| LADDER | 정면 Ladder 벽 + 스틱 (지상도) | 상하만 (32), 스틱 끝까지 아래 = 미끄럼 (ladderslide, 최대 160) | 꼭대기 dismount1 |
| LEDGE_HANG | 공중 (타이머 남음) 에서 턱 질의 (0x1E3A80, §2.4): 정면 12.8 의 바닥 (NoWallHang·Slide 아님) 이 손 높이 (발 + 35.2) ± max(이동, 3.2), 내려가는 중이면 손이 턱 아래 — **벽 오르기보다 먼저** | 좌우 이동 16 (매 틱 턱 질의로 턱 높이·벽 법선 따라감) | 위/× 올라서기, 아래/○ 놓기, WallHangTimer 다 쓰면 떨어짐 (데이터는 전부 0 = 무제한) |
| ROPE | 잡기 점(발 + 키 35.2) 32 안의 매단 줄 (공중, 또는 지상 + ×) | 위/아래 오르내리기 (24 × 스틱, 최소 8), 스틱 끝까지 아래 = 줄 미끄럼 (ropeslide, 최대 120), **잡을 때 0.25 초 정면 밀기 + ○ 누르는 동안 흔들기** (§13.1), **몸 방향 = 줄 축 4 (또는 2) 방향, 스틱 좌우로 90° 씩 (§15.3)** — 줄 모양은 `RopeSim` | **잡은 지 0.5 초 뒤 × = 스틱 쪽 줄 축 방향으로 앞 224 · 위 224 (§15.2)** |
| ROPE_TRAVERSE | 양끝 고정 줄 | 줄 방향 스틱으로 손 이동 (40 × 스틱, 최소 20), 손 = 양끝 고정 `RopeSim` 위 점 (§15.4) | 끝 바닥 / **× = 잡은 지 0.5 초 뒤 놓기** (§15.2) / ○ 놓기 [엔진] |
| ZIPLINE | 짚라인 | 낮은 끝으로 0 → 320 (가속 160, 기울기 무관), 손 = 양끝 고정 `RopeSim` 위 점 | 끝 0.25 m 앞에서 속도 0 으로 놓음 (그 자리 낙하). **× 는 아무것도 안 함** (§15.2, 전 "× 도중 이탈" [근사] 폐기) |
| SWIM | 깊은 Water (수면 아래 28.16 안에 단단한 바닥 없음) 에 떨어지거나 들어감 | **TREAD** 수면 − 28.16 에 떠서 72 × 스틱 / **SINK** 가라앉았다가 부력 240 /s² 로 떠오름 / **DIVE** □ 아래 · △× 위 120, R1 충전 돌진 (§11) | **바닥 질의 창 (내비 위치 → 발 − d) 의 마른 바닥 &gt; 수면 − 28.16 → 올라섬** (§15.5, 높은 물가는 점프·턱·사다리로), TREAD × = 점프, 잠수로 빠르게 떠오르면 물 밖 도약, TREAD 에서 Climbable 벽 → 벽 오르기 |
| 균형 걷기 | 발밑 Narrow | 지상 유지, `balanceWblend1` + 속도 배율 1 (Sold+0x230) | – |
| Death 면 | 발밑 Death | 사망 통지 (GameScene `heroDied`) | – |

| 지상 → 아래 벽 | 걸어서 위층 끝 너머 아래 Climbable·Ladder 면이 있으면 (0x1E5638, §15.6) | – | 벽 오르기 / 사다리로 |

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
| 좁은 턱 벽 따라 걷기 (0x100000 Narrow·NarrowNoBB / 0x200000 BackPress) | +0x168 1.25 | 0x1C8CBC, 0x1C7398 | 20 | 좌우 = 스틱·오른쪽 부호 × 20, 상하 0. ANIWallFront/Back (0x35–0x3A) — §12 [확실] |
| 벽 옆 끝 점프 (+0x2D8 0x100/0x200) | +0x114 10, +0x120 12 | 0x1E9C9C | 160 / 192 | 몸 축 90° 회전 + 지역 속도 (0, 192, 160) — §12.3 |
| 줄 잡기 | 키 +0x238 2.2, 반경 32 | 0x1C5C20 (공중 0x4C 일 때만), 0x1C5CF0 | 35.2, 32 | 잡기 점 = 발 + 위축 × 35.2, 줄 위 몸 = 줄 점 − 35.2 (0x1C66E4). 줄 플래그 +0x374: 4 → 0x10000, 1·2 → 0x40000 (짚라인), 1 → 0x20000 (수평 줄), 0 → 매단 줄 (0x1C5DA4). 엔진의 지상 + × 잡기는 "점프해서 잡기" 단축 [근사], 가슴 높이 보조 판정은 없앰 |
| 매단 줄 | +0x138 1.5, +0x13C 0.5 | 0x1C6578 → 0x1E6080 | 24, 최소 8 | 줄 위치 (m) += 속도/16·dt, 매단 점에서 0.25 m (플래그 4 줄은 0.5) 이상 (0x1C6608) |
| 줄 미끄럼 | +0x14C 7.5, +0x150 20, +0x154 20 | 0x1C6448 | 120, 320, 320 | RopeSlide 능력 (+0x2DC 0x8000): 스틱 아래 > 0.8 → 속도 0 에서 가속 320 (최대 120), 놓으면 감속 320, 0 이면 끝 (ANIRSlide) |
| 매단 줄 × 뛰어내리기 | +0x140 14, +0x144 14 | 0x1C55F4, 0x1C561C | 224 / 224 | 잡은 지 0.5 초 (0x29DBF8) 뒤, 지역 속도 (0, 위 224, 앞 224), 앞 = 줄 축 후보 중 스틱 쪽 — §15.2 [확실] |
| 줄 흔들기 | +0x148 500 | 0x1C6BE8 | 500 N × 거리 (m) | f21 = +0x148 × 영웅 줄 위치 +0x294. 잡을 때 0.25 초 창 (0x29DBF4) 2 × 남은 시간 배, 그 뒤 ○ (+0x310 0x10) 누르는 동안 F. 방향 = 줄 위쪽 u × 몸 오른쪽, 부호 = 움직이는 쪽 (0x303FB0/0x303FC0 = 잡을 때 뒤/오른쪽 축) — §13.1 [확실] |
| 수평 줄 (0x20000) | +0x158 2.5, +0x15C 1.25 | 0x1C6370 → 0x1E6080 | 40, 최소 20 | 32 문서의 "+0x158 줄 이동 [추정]" = **수평 줄 속도** (해골 -C Sold_020 2 m/s) |
| 짚라인 (0x40000) | +0x160 20, +0x164 10 | 0x1C60F8 → 0x1E5FE0 | 320, 160 /s² | 기울기와 무관하게 목표 속도로 가속, 방향 = 낮은 끝 (0x1C5E04). 줄 위치를 [0.25, 길이 − 0.25] 로 자르고, 끝에 닿으면 월드 속도 0 · 0x1E9030(2) 로 놓음 (0x1C6B1C) [확실: 속도 / 추정: 끝 처리 경로가 짚라인에도 같음]. 잡을 때 속도 0 [추정] (전엔 중력 성분 ×2, 60..420 [근사]) |
| 수영 (0x80 떠 있기, 0x100 가라앉음, 0x200 잠수) | +0x1D4 7.5, +0x1D8 4.5, +0x1DC 1, +0x1E0/+0x1E4 7.5, +0x1E8 0.15 | 0x1CA7D0–0x1CA868, 0x1CA62C | 120 (떠 있기 72), 최소 16, 120 /s², 프레임당 0.15 | 정면 각을 스틱 각 쪽으로 프레임당 0.15 돌리고 정면 속도를 목표로 가감속 (0x1E5FE0). 떠 있기 0x80 = 4.5 m/s (ANITread 0x28 / ANITreadSwim 0x29), 0x100·0x200 = 7.5 m/s — §11 [확실] |
| 부력·잠수 돌진 | +0x1F0 15, +0x1F8 0.5 / +0x1FC 1, +0x200 11 / +0x204 22, +0x208 0.01 / +0x20C 0.1 | 0x1CAC68, 0x1C9F10 | 240 /s², 176–352 | 0x80/0x100 월드 수직 속도 += 15·16·dt, R1 충전 0.5–1 초 → 11–22 m/s — §11 [확실] |
| 수면 깊이 | +0x1EC 0.2 | 0x1C9C00 | 28.16 | 물 판정 높이 = 키 × (1 − 0.2) (몸 위 20 % 가 물 밖) [추정: 판정 높이 = 떠 있는 높이]. 얕은 물 (수면 아래 32 안에 단단한 바닥) 은 걷는다 |
| R1 오브젝트 | 반경 32 | 0x1C3F18 | 32 | 전 근사 BODY + 14 = 28 |
| 밀기 (무게 w) | +0x17C/0x180/0x184 0.5, +0x188/0x18C/0x190 1.9 / 1.5 / 1.2 | 0x1C44E0 – 0x1C4610 | 30.4 / 24 / 19.2, 최소 8 | 붙은 오브젝트 쪽 영웅 속도 = 스틱 × 최대 [w], 0 이 아니면 최소 이상. w = Push 템플릿 +0x1A "Weight" (vtable +0xE0 = 0x244FB0) — §3.5 |
| 상자 차기 | +0x194–0x19C 0, +0x1A0/0x1A4/0x1A8 24 / 16 / 9, +0x1AC 2 | 0x1C4928 – 0x1C4A68 | lerp × 16 | × 누르고 있는 동안 충전 +0x2B8, 떼면 0x41D + 상자 속도 += 정면 × lerp(최소[w], 최대[w], 충전 / 2) — §3.5 |

애니 슬롯 번호 (+0x328+0xE0) = TWK Navigation 표 순서 (0 ANIStand … 0x10 ANIJump, 0x11 ANIVJump, 0x13 ANIDblJump, 0x26 ANISwim, 0x28 ANITread, 0x34 ANIWallPullup, 0x3B ANIRope, 0x47 ANIZipLine, 0x48 ANILadder, 0x4B ANILDis1, 0x4D ANILSlide, 0x4E ANIPushI …) — 위 주소의 즉값과 표가 모두 맞는다 [확실].
게임 쪽: `GameScene` 영웅 중력 = 800, 최대 낙하 800 (적 낙하 중력도 같은 전역 값). `EnemyNav` 은 아직 9.82×16 (다른 작업 파일, 후속).

### 2.3 줄 `SCR_RopeSkinned` [확실: 전 레벨 27 개]
GO CXT 바로 뒤의 72 바이트 SCR: `+0x20 kind(18), +0x24 템플릿, +0x28 **길이 (미터)** (전 "각도" [추정] 정정, §15.4), +0x2C u32[4] 플래그, +0x3C vec3 끝점(미터 ×16)`.
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
    이벤트 1051 (0x1C4EF8 → 0x1C4FD0, 인자 0, 보낸 이 영웅) 은 엔진 `HeroNavigator.Listener.ropeGrabbed` → `SceneNav` postEvent — ATHN03C RopeAttachGroup 이 받아 메시지 591 (43 §17.2-4).
  - 양끝 고정 줄 (수평 줄·짚라인) 도 같은 시뮬레이션에 끝 노드를 끝점에 고정 (§15.4). 줄 스키닝 렌더는 아직 곧은 모델 그대로 [미구현].

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
엔진: 1–4 (마스크 0x1008001C 전체 — Climbable·Ladder·Narrow·NarrowNoBB·BackPress, 좁은 턱 바닥 맞춤 포함 §12), t1 막힘 반환 3/5 = 벽 옆 끝 (§12.3). a3 머리 재시도 (0x41) 는 [미구현].

**턱 0x1E3A80** (일반 턱):
1. d = max(|새 − 옛|, 3.2). 정면 0.2 m (3.2) + 0.5 × 깊이 앞, 좌우 ∓ 폭 / 4 (4.8) 에서 위 (+0x140 + d) → 아래 (+0x140 − d) 수직 선분 둘. 둘 다 맞고 Slide (bitfield0 0x20)·NoWallHang (bitfield1 0x10) 아님, 법선 y > 0.85. 턱 = 중점.
2. 내려가는 중 (새 y < 옛 y) 이면 턱 − +0x140 ≥ 새 y (손이 턱을 지났음). o + (+0x140 + d) → 턱 + 1 선분이 막히면 실패.
3. 벽: b = 턱 − 위 × (+0x140 + d) − 정면 × 0.5 깊이에서 정면 × 깊이 ± 오른쪽 × 0.5 폭 두 선분 (없으면 b + 위 × (+0x140 − 2) 에서 다시), b + u × (+0x140 − 1) 에서 정면 × 깊이 선분도 맞아야.
4. 위치 = 두 벽 적중 중점 + 세 점 평면 수평 법선 × 9.6, 높이 = 턱 − +0x140 (발 = 턱 − **35.2**). 반환 1, 턱 면 → +0x234 (WallHangTimer), 법선 +0x220.
엔진: 1–4, 한쪽만 맞는 모서리 (반환 8, 0x1E4528) = `probeLedgeCorner` (§15.1), 좁은 턱 윗면 (Narrow·NarrowNoBB, 0x1E3EE8 경로) = `narrowLedge` (§18.1).
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
- 템플릿 TWK `/TweakTemplates/Push/<n>/` (Instance Name, Type, Radius, Acc/Dec, Push Sound). **종류는 Type 하위 바이트로만** (갱신 0x212398, 1015 리셋 0x2100E0) [확실]:
  1–4 크랭크류 (누른 채 스틱, Acc/Dec — 2 = 대포 AthnGun/AthensHarpoon 은 끝 99 % 에서 차서 발사, 42 §5), 5/6 = 레버 (leverpull 한 번), 0/7/8 = PUSH.
  2026-10-24 앞 판은 Instance Name (Crank/Lever/Puller) 으로 골라 ATHN02C 발리스타 크랭크 "AthnGun" (Type 2)·CoffinLid·HadesHead·Pand04CElevs 등 11 개가 밀기 상자였다 (43 §14).
  Type 이 없는 템플릿만 이름으로 [근사].
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
- 후보: Animator 엔티티(타입 5/6)가 가리키는 GO 중 CDV(BallHull 0) 가 있는 것 + **비헤이비어 없는 CDV GO (정지 몸체)** — 원작은 GO 마다 CDV 를 충돌 세계에 올린다 (43 §13.10 6, ATHN02A 정박한 배).
- ~~밟는 hull = 평면 재질 이름에 ground/floor~~ → **밟는 면 = 수직 선분이 들어가는 평면의 재질 (off[9] → 재질 엔트리 = 시트 표면 레코드, +0x18 bitfield0) 이
  영웅 질의 제외 마스크 (Water·Death·AIBlock·GeneralGuide·NoPlayerCollision·ClimbGuide·CombatGuide) 에 안 걸리는 것** [확실: 0x1E3298 / 0x1E2100 → 0x139218, 43 §13.10 6].
  바닥 속성 = 그 재질 (`NavWorld.Floor.attrs`). 이름 규칙에서 빠졌던 PAND04AA 엘리베이터 `SheetM`, PAND04F goelevator, PAND02C 우리 승강기 등 해결.
- **BallHull off[6] = hull 별 관절 번호** [확실: 엘리베이터 hull 0 → 관절 2, hull 1 → 관절 1]. 이전에는 모든 볼륨을 관절 0 에 붙였다 (ENV/CMV 쪽은 그대로).
- **오브젝트 애니 관절 이동** [확실: 05 §2.8.1]: ATHN05D `WarpElevator5D` (세트 [0(p2 3), 11, 11]) 스트림 5/9 (관절 1/2 의 Y) 절대값 138.47 = 바인드 로컬 Y, 가산으로 −52.2 까지 → 발판 윗면 871 → **677 = Warp 도착점 WarpFrom4A 높이**.
  예전에는 "타입 11 (unk10 = 4) = 관절 이동 (타입 5 가 없는 세트만)" 규칙으로 읽었으나 그 자리는 실제로 타입 0 의 디스크립터 1 (이동) 이다 — 디스크립터 표가 항목 수가 아니라 Σ p2 개라
  [0(3), 11, 11] 의 둘째 디스크립터가 이동이 된다 (2026-10-24 정정, 규칙 `objectTranslation()` 은 없앰, 41 §2.1).
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
| `nav/HeroNavigatorTest` (20 + §14 의 8, 데이터 불필요) | 벽 오르기→**턱 매달림**→올라서기, **벽 질의 (두 선분·머리·9.6), 턱 질의 (하강 손 높이·창·Slide·NoWallHang), WallHangTimer 떨어짐·재잡기 금지·착지 초기화, 무게 등급 밀기 속도 (1.2 m/s·최소 0.5), 상자 차기 충전·0x41D·마찰 미끄럼**, 일반 벽은 안 오름, 턱 매달리기·좌우·올라서기, 사다리, 짚라인 끝 착지, 매단 줄 오르기·흔들기·뛰어내리기, 균형/Death, 수영→물가, 상자 밀기(1048/1049·이동량), 벽에 막힘, **§3.4**: 문 R1 한 번 = doorKick 0.9 초 붙음 → 1023 (≥30 % 과제 206, 100 한 번) + 1048/1049 과제 id, 상자 누르고 있기·떼면 −4 되감기·되감는 중 못 잡음·chestGrab+chestOpen 뒤 놓음, 크랭크 Acc/Dec·떼면 되감기, TAndF 누를 때마다 +Acc·TF Time 뒤 −Dec, 레버 leverpull 끝까지·1023 (100, 111, 0) |
| `nav/TaskProgressTest` (+2) | 레버 끝 속도 0, TAndF 식 (0x214400) |
| `nav/RopeSimTest` (3) | 잠든 줄 자세, 매달린 무게 0 → 100 (×0.5 /초)·처짐, 흔들기 창 뒤 감쇠 진동 주기 |
| `nav/NavDataTest` (10) | `pushWeightsAndWallHangTimers`: Pandora's Box 무게 2·최대 5 m/s, HydraPushBox 0x3F000001, 전 레벨 WallHangTimer 전부 0. |
| `nav/NavDataTest` (9) | `attachRulesFromTemplates`: 문 hold 0 / 상자 42 hold 1, ANICSM2 (captainHangExit), 영웅 doorKick·captainsDoorOpen 길이 = 문 애니 길이, PAND02C 크랭크 (Type 1, Acc 3·Dec 1·최대 1.5·되감기 20/15), 레버 Type 5, 물속 레버 leverPullUW, ATHN01C TAndF 키. 그 밖 | ATHN01D 표면 레코드, 전 시트 스키마·Ground, 줄 해석(gorope3 짚라인 끝 아래 ATHN01E 바닥), BHV(CSM 44/206, Pandora's Box 503, 크랭크 LEVER), Marker 511 → Semo08, 엘리베이터 운반 871 → 677, 도착 자세, **ATHN01D 실제 충돌로 그물·돛대 오르기 → 꼭대기 1120 → 점프로 짚라인 → ATHN01E 바닥 착지** |
| `TraversalTest` +1 | 3 막힘이 강제 없이 연결, ATHN05D 도착 |
| `ScriptWorldTest` +1 | 이웃 호스트 GlobalData 공유 |

## 9. 남은 것
- ~~원작 판정 수치~~ → §2.2. ~~벽 잡기 선분 질의 기하·턱 높이~~ → §2.4, ~~WallHangTimer~~ → §2.2 (데이터 전부 0), ~~밀기 속도·상자 차기~~ → §3.5, ~~줄 질점 시뮬레이션~~ → §2.3 `RopeSim`. ~~지상 벽 진입 입력~~ (§12.1: 앞으로 걸어 들어가면), ~~막힘 반환 (3/5)~~ (§12.3), ~~좁은 턱 벽 따라 걷기·등 쪽 BackPress~~ (§12), ~~흔들기 힘 방향 (0x303FB0/0x303FC0)~~ (§13.1), ~~물 위 떠 있기(0x80)·잠수(0x200)·물에서 점프·다이빙(NoDiving)~~ (§11), ~~벽 꼭대기 점프 (+0x2D8 0x300)~~ = 벽 옆 끝 90° 점프 (§12.3). 남은 근사: 벽 질의 a3 머리 재시도 (0x41), 턱 모서리 (반환 8)·벽 바깥 모서리 돌기 (0x1E53F0, §13.3), 위에서 사다리/벽으로 내려가기 (0x1E5638, 반환 9), 매달림 좌우 이동 실패 = 떨어짐, 줄 × 뛰어내리기 (0x1C5848 은 이탈이 아님 §13.2)·줄 위 몸 회전·양끝 고정 줄 시뮬레이션·줄 스키닝 렌더, 상자 로컬 축·좌우 밀기 (PullL/R)·몸 접촉 전달, 물가 올라서기 (0x1EA5F0)·수중 R1 돌진 능력 비트. 줄 공격(10 문서 rope attack), 벽 점프 연속, 미끄럼(Slide) 경사 미구현.
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
- ~~상자(Creator "chest") 의 오브젝트 애니는 아직 없음 → 길이 = 영웅 chestGrab (1.967 초) [추정]~~ → §21: 상자 몸 = R_PERMK 오브젝트 chest, 길이 = 그 ANM chestGrab 1.967 초 [확실]. PushPull 레버는 끝에서 속도 0 (1023 인자 2 = 0), CSM 만 0x215928(1.01, 1, 1).

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

## 11. 물: 떠 있기·가라앉음·잠수·물에서 점프·도약 (`HeroNavigator.swim`, 원작 0x1C9BB8 / 0x1CA448) [확실: 디스어셈블]
물 상태 = +0x174 비트 0x380 중 하나. 수영 갱신 0x1CA448 이 정면·속도를 정하고, 이동 (0x1E5F90) 뒤 물 상태 0x1C9BB8 이 수면 질의 0x1E57D8 로 하위 상태를 바꾼다.

| 하위 상태 | 진입 함수 | 애니 (Navigation 표 번호) | 목표 속도 (+0x164) | 수직 |
|---|---|---|---|---|
| TREAD 0x80 물 위 떠 있기 | 0x1C93B8 | 속도 ≤ 1.6 → ANITread 0x28 `tread`, 아니면 ANITreadSwim 0x29 `treadswim` | Sold+0x1D8 4.5 → 72 | 수면 맞춤, 수직 0 |
| SINK 0x100 가라앉음 | 0x1C94B0 | 다이빙 (+0x2D8 0x100000) 이면 수직 ≥ 0 ? ANISwim 0x26 : ANIDive2 0x2E `swimdive1`, 아니면 ANITread / ANISwim | +0x1D4 7.5 → 120 | 월드 수직 속도 += 부력 Sold+0x1F0 15 m/s² (240) × dt (0x1CAC68) |
| DIVE 0x200 잠수 | 0x1C95B0 | 충전 ANIDiveDashC 0x2A `swimDashPU`, 돌진·속도 &gt; (7.5+1)·16 ANIDiveDash 0x2C `swimdash`, 속도 ≤ 1.6 ANISwimIdle 0x27, 그 밖 ANISwim 0x26 | 120 | 버튼으로 ±120 (가감속 +0x1E0/+0x1E4), 부력 없음 |

- **수면 질의 0x1E57D8** (a1 새 위치, a2 옛 위치, a3 맞춤, f12 = 키 × (1 − Sold+0x1EC) − 바닥 = 35.2 × 0.8 − 6.4 = **21.76**): d = 2 × max(이동, 3.2), 내비 위치 + 21.76 ± d 수직 선분 (아래 끝이 발보다 높으면 발까지) 이 bitfield0 & 0x02000002 (Water·NoDiving) 면에 맞으면 +0x230 = 수면 높이, +0x23C = 면. 맞춤 (a3 = 1) 이면 떠 있기 중이거나 새 위치가 수면 − 21.76 보다 위일 때 그 높이로 맞추고 수직 0 → 1. 맞춤 아님 (공중에서 들어갈 때) 이면 내려가는 중 새 위치 ≤ 수면 − 21.76 일 때 1.
  → 떠 있는 발 = 수면 − 28.16 (`SWIM_DEPTH`), **깊은 물 = 수면 아래 28.16 안에 바닥 없음** (그보다 얕으면 서서 걷는다 — ATHN08A 아레스 만).
- **들어가기** (0x1CA098, 공중 + 수직 ≤ 0): 질의가 맞으면 SINK, 수직 속도 = max(× 0.4, −64), +0x2D8 0x20. 엔진 충돌은 수면도 바닥이므로 내비게이터가 이번 틱에 발이 깊은 수면에 닿을 때 가져가서, 판정 높이까지는 공중 물리 (중력 800) 로 떨어진 뒤 (plunging) 그 높이를 지나는 프레임에 감쇠한다 (원작도 이동 뒤 질의 → 한 프레임 넘침). 걸어서 수면 위로 가면 바로 TREAD.
- **0x80/0x100 → (0x1C9C50)**: 수직 ≥ 0 이면 다이빙 끝. 맞춤 질의가 맞으면 TREAD, 아니면 SINK. 둘 다 **잠수 능력 (+0x2DC 0x20) + □ (+0x310 0x40) + 지역 수직 ≤ 0** 이면 DIVE.
- **0x200 → (0x1C9D7C)**: 수면에 닿으면 (판정 높이보다 위) 수직 속도 ≥ Sold+0x1D4 × 16 × 0.5 = **60** 이면 **물 밖 도약** 0x1C9750 (공중 0x1008 + 다이빙 0x100000, ANIDive1 0x2D `swimdive`, 속도 × min(2, 점프 244 × 1.5 / 수직)), 아니면 TREAD. 위로 버튼을 누른 채 120 으로 올라오면 2 배 → 수직 240.
- **물속 버튼** (조종기 0x1D7FD0, +0x174 0x380 일 때): 논리 0x1000 → +0x310 0x40 (아래), 0x2000 / 0x4000 → 0x20 (위), 잠수 중 0x800 → 0x800 (돌진). 버튼 표 0x29DE98 (논리 번호 &lt;&lt; 8 | 패드 비트): 0x0C07 □, 0x0D04 △, 0x0E06 ×, 0x0B03 R1, 0x0906 × (점프 0x200), 0x1401 R2 (사용) — 패드 비트 = libpad 0 L2 · 1 R2 · 2 L1 · 3 R1 · 4 △ · 5 ○ · 6 × · 7 □ (`PadButton.USE` 주석과 같은 표).
- **물에서 점프** (0x1E9C2C): 점프 (×) 는 지상·벽·턱·**떠 있기 0x80** 에서만 → 수직 += Sold+0xC4 (244), 애니 = 앞 속도 &lt; 1 m/s 면 ANIVJump 아니면 ANIJump (0x1E98B8). 가라앉음·잠수 중엔 안 된다.
- **물 위에서 벽 오르기** (0x1C7DA8): 벽·턱 판정 0x1C7828 이 지상 (0x1) 과 **떠 있기 (0x80)** 를 같게 본다 — 앞으로 가면 벽 질의 → 0x1C7280 (위치 y += 1.6, 수직 0.1). 사다리·Climbable 벽이 물속까지 내려오면 헤엄쳐 붙는다.
- **잠수 돌진** (0x1C9E70): 돌진 중 (+0x2D8 0x80000) 이면 +0x2B8 −= dt, 0 이하 끝. 아니면 R1 (+0x310 0x800) + 능력 +0x2DC 0x200000 → 충전 (0x40000, +0x2B8 0 부터 += dt, 그동안 속도 × (1 − 0.1·60dt)). 떼면 k = clamp((충전 − 0.5) / (1 − 0.5)) &gt; 0 일 때 속도 +0x2C4 = lerp(11, 22, k) m/s, +0x2B8 = lerp(0.01, 0.1, k) + 1, 지역 속도 = (0, 수직 × r, (120 − |수직|) × r) (r = 돌진 / 7.5). +0x2B8 ≥ 1 인 동안 (처음 0.01–0.1 초) 상하 조종 목표 = 돌진 속도, 속도 크기 ≤ 돌진 속도.
- **공중 다이빙 자세** (0x1CA318, 공중 갱신 0x1EA5F0): 다이빙 중이 아니고 (0x100000) 이번 낙하 금지 (0x200000) 가 아닐 때, 낙하 &gt; 점프 초속 (244) 이면 0.35–0.4 초, 앞 속도 &gt; 80 이면 0.2–0.35 초 뒤 포물선 위치 선분 (0x1C98D8, 콜백 0x1C9880) 이 **NoDiving 이 아닌 물** 에 먼저 닿으면 0x1C9750 (ANIDive1), 다른 면 (NoDiving 물·땅) 이면 0x200000. → **NoDiving = 물에 뛰어들 때 다이빙 자세 금지** (ATHN08A/08C 아레스 만 84 면 전부, PAND04E 60/118 …). 엔진은 지금 위치 → 0.4 / 0.35 초 뒤 선분 하나 [근사].
- 죽음 (+0x178 ≤ 0) 이면서 물이면 +0x174 0x4000000 (0x1ED0A8) — 잠수·돌진 금지 비트. NoDiving 은 잠수 버튼을 막지 않는다 [확실: +0x23C 를 읽는 곳은 물보라 소리 0x1FE4A0 뿐].
- 물가 올라서기 = 원작 0x1CA198 (§15.5). 엔진 근사: 잠수·돌진 능력 비트는 처음부터 있다고 봄, 물속 몸 충돌 = 구 (반지름 9.6) 벽 밀어내기 + 밑바닥.

데이터 (전 레벨): Water 면은 대부분 레벨당 2 면 (큰 사각형) — 헤엄 구간은 ATHN02A 수로 (y −687.4, 바닥 −800), PAND02G (116 면), PAND04E (118), PAND02F (41), PAND02DD (51), ATHN08A/08C (얕음, 걷기).

## 12. 좁은 턱 벽 따라 걷기·벽 진입·벽 옆 끝 (`HeroNavigator.wallWalk` / `enterWall` / `edgeJump`) [확실: 디스어셈블]
### 12.1 지상 → 벽 (0x1C7DA8)
지상 (0x1) 또는 떠 있기 (0x80), 전투 기술 아님, +0x2D8 0x20 (방금 떨어짐) 아님:
1. **착지한 프레임 (+0x2D8 0x80, 0x1CB380 / 0x1EA904 가 착지 때 켜고 0x1C7FEC 가 매 프레임 끔) 이거나 지역 앞 속도 &gt; 0** 이면 벽 질의 0x1E47E0 (a3 = 0, t0 = 0) → 맞으면 0x1C7280.
2. 못 맞고 착지 프레임이면 **등 뒤로 다시** (t0 = 1: 몸 축을 뒤집어 질의) → 0x1C7280.
3. 그 밖 (지상, 결과 0) 이면 0x1E5638: 발 − 위 × 35.2 − 뒤 ... 에서 정면 선분이 Climbable·Ladder (0x18) 면에 맞으면 반환 9 — 위에서 아래 벽/사다리로 내려가 붙기 → **§15.6 에서 해독·구현**.
→ **Climbable 벽은 앞으로 걸어 들어가면 붙는다 (× 불필요)** — 전 엔진 규칙 (스틱 + ×) 폐기. 엔진 2 는 BackPress 면만 [근사].

### 12.2 상태 고르기 0x1C7280 과 좁은 턱 갱신
- 0x1C7280(a1 초기화, a2 반환): +0x174 = (… & ~0x7FF) | 0x20. 반환 비트 2 → +0x2D8 0x100 (왼쪽 끝), 4 → 0x200 (오른쪽 끝), 아니면 둘 다 끔. 벽 면 bitfield0: **0x80000 BackPress → 0x200000 (등 대고)**, **0x10000004 Narrow·NarrowNoBB → 0x100000 (벽 보고)**, 0x10 Ladder → 0x400000, 그 밖 벽 오르기. 애니: 0x100000 = ANIWallFront 0x35 / R 0x36 / L 0x37, 0x200000 = ANIWallBack 0x38 / R 0x39 / L 0x3A (지역 좌우 속도 부호), 벽 오르기 = 끝이면 ANIClimbAL 0x1F / AR 0x20, 모서리 0x800/0x1000 이면 ANIClimbCL 0x23 / CR 0x24.
- 몸 축 (0x1C8000): 벽 상태면 +0x70 (뒤 축) = 벽 법선 (등 대고면 −법선) → 벽 보고 / 등 대고.
- **벽 질의의 좁은 턱 경로** (0x1E4D08): 첫 적중 면이 0x10080004 면 새 위치에서 아래 (6.4 + max(이동, 3.2)) · 벽 쪽 반 깊이 선분이 바닥 (법선 y &gt; 0.1) 에 맞아야 하고, 발 = 그 바닥. 못 맞으면 실패.
- **좁은 턱 갱신** (0x1C8C70, +0x174 0x300000): 지역 좌우 = 스틱·몸 오른쪽의 부호 × **Sold+0x168 1.25 m/s (20)** (크기 무관, 문턱 1e-4), 상하 0. 월드 속도 = 오른쪽 × 좌우 (앞뒤 성분은 점프 조건에만 쓰임: 스틱 벽 반대 → −Sold+0x11C).
- **턱 끝** (0x1E52D4): 벽 오르기 중 (t1 = 1) 한쪽 선분만 맞을 때 면이 좁은 턱 마스크면 **막힘 대신 0** → 0x1C7828 이 0x1E9030(2) 로 놓음 (떨어짐).
- 데이터: 벽 보고 걷기 = PAND02B (Narrow 벽 높이 128–288, 18 자리), ARENA08/12, ATHN02C. 등 대고 = PAND00C BackPress 3 쌍 (높이 ≈ 90), PAND01B/BB (높이 38 — 머리 선분 27.8 + 6.4 = 34.2 라 붙음). ATHN06B·PAND03E·ATHN04A·ATHN01C 의 Narrow 는 높이 16 평균대 (옆면 + 윗면) — 머리 선분이 빗나가 벽 걷기가 아니고 윗면이 균형 걷기 바닥.
- 엔진: `NavState.WALL_WALK`, `wallBack`. × = 가만히 + 스틱 벽 반대 (0x1E9D9C: 좌우 ≤ 12, 앞뒤 &lt; 0, NoBackJumps 아님) → 벽 반대 160 · 위 272, ○ = 놓기 [엔진], 스틱 문턱 0.3 [근사: 대각선 떨림 방지].

### 12.3 벽 옆 끝과 90° 옆 점프 (docs/90 의 "벽 꼭대기 점프 +0x114/+0x120")
- 벽 질의 막힘 (0x1E5350 – 0x1E53A8, 벽 오르기 + t1 = 1): 두 선분 중 하나만 붙는 면에 맞고 머리 선분도 맞으면 옛 위치로 되돌리고 **3 (왼쪽 선분이 빗나감) / 5 (오른쪽)** → 0x1C7280 → +0x2D8 **0x100 / 0x200** (ANIClimbAL / AR `wallClimbAL/AR`).
- 끝 표시 중 (0x1C7A24): 0x100 이고 지역 좌우 &lt; 0 (또는 0x200 이고 &gt; 0) 이면 **질의 없이 그 자리** (상하도 멈춤).
- **× (0x1E9C9C)**: 벽 상태 (0x20) + 0x300 이면 몸 축을 90° 돌리고 (0x100: +0x70 = 옛 +0x50, +0x50 = −옛 +0x70 / 0x200 은 반대) 지역 속도 (0, **Sold+0x120 12 → 192**, **Sold+0x114 10 → 160**), +0x2D8 0x1000020 (다시 붙지 않음), +0x2A0 0.1. 등 뒤 점프 (0x1E9D9C) 보다 먼저 본다. → 벽 꼭대기가 아니라 **벽 옆 끝에서 옆으로 뛰는 점프** [확실: 값·90° / 추정: 도는 쪽 = 막힌 쪽].
- 엔진: `edgeSide` (한쪽 선분만 Climbable·Ladder — 좁은 턱 면 제외), `wallEdge` −1/+1, × → 막힌 쪽으로 160 · 위 192, 정면도 그쪽.

## 13. 줄 흔들기 힘·줄 이탈·모서리 해독 상태
### 13.1 매단 줄 흔들기 힘 (0x1C6BC8 – 0x1C71A8, `HeroNavigator.swingForce`) [확실]
- 잡을 때 (0x1C5D54): **0x303FC0 = 몸 오른쪽 +0x50, 0x303FB0 = 몸 뒤 +0x70, 창 0x29DBF4 = 0.25 초** (전엔 "스틱을 새로 밀 때 창" [추정] 이었음).
- u = 매단 점 (줄 위치 0) − 영웅 줄 점 (0x303FD0), F = Sold+0x148 500 × 붙은 거리 (m).
- 창 안: T = u × 0x303FC0, A = (0x303FB0 × −u) · 0x303FC0. A &gt; 0 이면 û·0x303FB0 ≤ 0.1 일 때 +F × 2 × 남은 창, 아니면 ≥ −0.1 일 때 −F × 2 × 남은 창 — 매달린 직후 잡은 정면 쪽으로 한 번 민다. 창 −= dt.
- 창 뒤: **흔들기 버튼 +0x310 0x10 (조종기 0x1D8108: +0x174 0x400 줄 위에서 논리 0x40000 = 패드 비트 5 ○) 을 누르고 있으면** (줄 미끄럼 0x8000 아님, +0x2D8 0x2000000): T = u × 지금 몸 오른쪽, (줄 점 속도 v × u) · 오른쪽 &gt; 0 이면 û·뒤 ≤ 0.1 일 때 +F, 아니면 û·뒤 ≥ −0.1 일 때 −F — **움직이는 쪽으로, 줄이 수직 근처 (아래쪽 반) 일 때만** 민다. 누르는 동안 오르내리기는 건너뛴다 (0x1C61FC).
- 크기는 +0x298 에 프레임당 0.1 저역 통과 (0x1C7170), 힘 = T̂ × +0x298 → 0x218470 (매달림 힘). 흔들기 애니 단계 (ANIRopeSW1–4) 는 속도·정면 내적으로 0..3 (0x1C6FE4 – 0x1C7088) [미구현: 엔진은 ANIRopeIdle].
- 엔진: ○ 누르는 동안 흔들기 + 스틱 = 몸 정면 (흔드는 면) [근사: 원작 줄 위 회전 미해석].

### 13.2 줄 이탈 0x1C5848 [확실: 역할] — × 뛰어내리기 경로는 §15.2 (0x1C5488)
0x1C5848 은 뛰어내리기가 아니라 **줄 위 몸 이동 충돌**: 0x1E2100 으로 몸을 밀고, 매단 줄 (+0x174 0x400) 이면 내려가는 중 발밑 바닥 (0x1E3298) 이 착지 높이보다 높고 Slide 가 아니면 착지 (0x1E9470), 벽에 막히면 줄 위치를 다시 정하거나 (0x218488) 0x1E9030(2) 로 놓는다. 마지막 선분 (옛 → 새, 0x1E1E80 콜백) 이 막히면 그 자리 0x1E9030(2). × 뛰어내리기 속도는 이 함수가 아니다 — 경로 미해석 (엔진은 줄 점 속도 + 정면 40 + 점프 [근사] 유지).

### 13.3 모서리 (반환 8) 와 벽 바깥 모서리 돌기 → **§15.1 에서 정정**: 0x1E53F0 은 모서리 돌기가 아니라 옆 끝 점프 뒤 공중 잡기
- (옛 해석, 틀림) 벽 오르기 중 끝 표시 (0x300) 로 막힌 쪽을 **0.2 초 이상 밀면** (+0x2B8 += dt, 0x1C8180) 0x1E53F0 (a2 = 0x10 / 0x20) 바깥 모서리 질의 → 반환 & 8 이면 +0x2D8 0x1000 / 0x800 (ANIClimbCR / CL), 몸 축을 새 벽 법선 (+0x220) 으로 돌려 벽 질의 (a3 = 1) → 맞으면 0x300 끄고 0x1C7280 (모서리를 돌아 옆 벽으로), 아니면 축 되돌림. 0x1E53F0 내부 기하는 미해석.
- 턱 질의 반환 8 (0x1E4528, 한쪽만 맞는 턱 모서리) 과 좁은 턱 경로 0x1E3EE8 은 미해석 → §15.1 · §18.1 에서 해독.
- 사다리 면 (bitfield0 0x10) 에서 반환 0x20 / 0x10 이면 오른쪽 × 3 m/s 로 가운데 맞춤 (0x1C85A8).

### 13.4 남은 것 (줄)
→ §15 에서 양끝 고정 줄 시뮬레이션·적 매단 줄 회전 해결. 줄 스키닝 렌더만 남음.

## 14. 테스트 (§11–13)
| 테스트 | 내용 |
|---|---|
| `HeroNavigatorTest.treadSpeedJumpAndShallowWater` | 떠 있는 발 = 수면 − 28.16, 물 위 72 (4.5 m/s), `treadswim`, × = 점프 244 `jumpAir`, 수면 아래 20 바닥 = 걷기 |
| `fallIntoWaterSinksThenFloatsUp` | 높이 100 에서 떨어짐 → SINK, −64 자르기, 부력 240 → 가장 깊은 발 ≈ 판정 높이 − (넘침 + 8.5) → TREAD |
| `diveThenLeapOutOfWater` | □ 잠수 −120, △ 로 올라와 수면에서 수직 240 (2 배) `swimdive`, 버튼 없으면 잠수 유지 (부력 없음) |
| `underwaterDashChargesAndReleases` | R1 0.4 초 = 돌진 없음, 1 초 충전 `swimDashPU` → 352 `swimdash`, 1.1 초 뒤 끝 |
| `treadClimbsWallAndAirDivePose` | 물 위에서 Climbable 벽 → 벽 오르기, 높은 낙하 → `swimdive` (NoDiving 물이면 없음) |
| `narrowWallWalkSidewaysThenDropsAtEnd` | Narrow 벽 쪽으로 걸음 → WALL_WALK `wallFrontI`, 0.4 스틱 = 1 초 20, 턱 끝에서 떨어짐 |
| `backPressWallOnLanding` | 착지 프레임 등 뒤 질의 → 등 대고 `wallBackI`, 카메라 +x = 몸 왼쪽 `wallBackL` |
| `climbWallEdgeBlocksThenSideJump` | 스틱만으로 벽 오르기, 왼쪽 끝 x = −50 + 5.33 에서 막힘 `wallClimbAL` (대각선도 멈춤), × → −x 로 160 · 위 192 |
| `hangingRopeClimbAndSwing` (수정) | ○ 누르고 흔들기 → 20 이상, 놓으면 감쇠 |
| `NavDataTest.waterAndWallWalkAnimsFromNavigationTable` | 새 애니 키 = R_HERO0 TWK Navigation 표 |
| `NavDataTest.athn02aCanalSwimDiveAndLeap` | ATHN02A 수로: 떨어짐 → TREAD (−687.43 − 28.16) → □ 로 바닥까지 → △ 도약 |
| `NavDataTest.narrowLedgeWallsInLevels` | PAND02B Narrow 벽 18 자리 붙음·12+ 걸음, PAND00C BackPress 6 (등 대고), ATHN06B 평균대 0 |
| `NavDataTest.heroNavigationTuningIsSold000` | 새 Sold 필드 (+0x114, +0x120, +0x1F0 – +0x20C) 원본 대조 |

순회 검증 (`TraversalTest` 15): 강제 뒤에만 나오는 전환 0, 숨김 상호작용 0 그대로. 전체 `:engine:test` 306.
화면 확인: `--game ATHN02A --arrive ATHN02A@-400,-660,3600,0` 150 프레임 → 수로에 들어간 크레토스가 팔을 벌린 떠 있기 자세 (카메라가 물 안개 볼륨 안이라 화면이 어두움 — 수면 렌더는 이 작업 밖).

## 15. 옆 벽 잡기·턱 모서리·줄 뛰어내리기·줄 위 회전·양끝 고정 줄·물가·위에서 사다리로 (docs/90 §4 항목 4 후속)

### 15.1 0x1E53F0 = 벽 옆 끝 점프 뒤 공중에서 옆 벽 잡기 [확실: 디스어셈블] (§13.3 정정)
- 부르는 곳 0x1C8118 은 **벽 상태가 아닐 때** (+0x174 & 0x30 = 0) 만 온다. 벽 옆 끝 점프 (0x1E9C9C) 는 +0x2D8 0x300 을 끄지 않으므로 그 공중 상태가 대상.
  공중이고 0x300 이 남아 있으면 일반 벽 잡기는 건너뛰고 (0x1C7D20), +0x2B8 += dt × 배율 (벽 상태에서 매 프레임 0, 0x1C8014) 가 **0.2 초** 를 넘으면 매 프레임 질의.
- 질의 (a1 = 새 내비 위치, a2 = 0x100 이면 0x10 아니면 0x20): 점프로 몸 축이 90° 돌았으므로 새 뒤 (+0x70) = −뛴 방향 s, 새 +0x50 = −옛 벽 법선 n.
  선분 o → o + 뒤 × 9.6 (+0x234 × 16) ± +0x50 × 14.4 (폭 × 0.75) — 두 경우 모두 **o − s × 9.6 − n × 14.4** — 와 같은 선분을 위 × 27.8 (+0x140 − 1) 에서.
  처음 맞은 면이 Climbable·Ladder (bitfield0 & 0x18) 이고 |법선 · 새 뒤| &lt; 0.25 면 반환 a2 | 8, +0x220 = 법선, +0x238 = 면.
- 반환 8 이면 (0x1C81C0) +0x2D8 |= 0x800 (0x100) / 0x1000, 몸 뒤 = 법선 (y = 0 정규화) 로 축을 다시 세우고 벽 질의 0x1E47E0 (a3 = 1) → 맞으면 0x300 끄고 0x1C7280 (애니 ANIClimbCL 0x23 / CR 0x24 `wallClimbCL/CR`), 지역 속도 y 0.1, 아니면 축 되돌리고 0x1800 끔.
- 뜻: 법선이 뛴 방향과 수직 = **옛 벽과 같은 쪽을 향한 벽** → 벽 옆 끝에서 옆으로 뛰어 **틈 건너편 벽** 을 잡는다. 바깥 모서리를 도는 동작은 원작에 없다 (모서리 쪽 면은 법선 · 뒤 = ±1 이라 걸러짐).
- 엔진: `HeroNavigator.sideCatch`, `edgeJumpSide` (+0x2D8 0x300 유지), `EDGE_CATCH_TIME` 0.2, 점프 뒤 잡기 금지 0.1 초 (+0x2D8 0x20 · +0x2A0 0.1 [추정: 풀리는 시간]). 애니 짝: 엔진 `wallEdge` −1 ↔ ClimbAL ↔ ClimbCL (원작 0x100 ↔ AL ↔ CL 과 같은 짝).
- **턱 반환 8 (0x1E4528)** [확실]: 두 수직 선분 중 하나만 턱 (조건은 일반 턱과 같음) 에 맞으면, 적중점 + (0, 1, 0) ← 머리 (o + 28.8 + d) 선분이 비어 있고 o → (그 선분 아래 끝 x, o.y, z) 수평 선분이 맞을 때
  법선 (y = 0 정규화) → +0x220, 반환 8. 공중 경로 0x1C83E4: +0x220 · (스틱 월드 방향, 없으면 정면 — +0x2E0) &lt; 0 이면 몸 뒤 = 그 법선 → 다음 프레임 두 선분이 대칭으로 턱을 다시 본다.
  엔진 `probeLedgeCorner` + 공중에서 몸 돌리기. 매달린 중 반환 8 (=1 아님) 이면 원작은 떨어짐 (0x1C7AEC) — 엔진은 여전히 그 자리 [근사].

### 15.2 줄 × = 0x1C5488 (줄 갱신 0x1C6008 의 0x1C6B58 에서) [확실: 디스어셈블]
- 0x29DBF8 = 줄 잡은 뒤 시간 (잡을 때 0x1C5D98 에서 0, +0x174 비트 31 (영웅 [추정]) 일 때만 += dt × 배율). × (+0x310 1) 일 때:
  - 짚라인 (0x40000): **아무것도 안 함**.
  - 수평 줄 (0x20000): 비트 31 이고 0.5 초 이하면 무시, 전투 기술 중 (combatant +0x324 → +0x4C ≠ 0) 이면 무시, 아니면 0x1E9030(2) = 놓기 (§18.2 정정: 전 "AI 면 무시").
  - 매단 줄: 0.5 초 이하면 무시. +0x150 = 0.1, +0x154 = 1.1111, +0x174 |= 0x20000000. 지역 속도 +0x260 = (0, **Sold+0x140 14 → 224**, **Sold+0x144 14 → 224**).
    뒤 후보 = 줄 오브젝트 z 축 (줄 +0x20 = 오브젝트 행렬 z 행, 0x216D2C) 으로 [−z, −(z × 위), z, z × 위] 중 템플릿 방향 제한 (+0xB8 short, 해시 #b38919fb, 0x216AB8 — RopeNorthSouth 1 = 0·2 번, RopeEastWest 2 = 1·3 번, 기본 0 = 넷) 이 허락하는 것.
    −a1 (a1 = +0x2E0 = 스틱 월드 방향, 스틱 없으면 −뒤 = 정면) 과 내적이 가장 큰 후보 → +0x70, 그 앞 번호 → +0x50. 월드 속도 +0xE0 = 위 × 224 + 정면 × 224 (**덮어씀** — 줄 점 속도 안 더함).
    줄에 반작용 −속도 × 5 × min(1, 붙은 거리 m × 0.25) (0x218470), 애니 0x1E98B8 (앞 1 m/s 이상 → ANIJump), 0x10000 아니면 +0x2D8 |= 0x1000000.
- 엔진: `ropeJump` (전 "줄 점 속도 + 정면 40 + 점프 244" [근사] 폐기), 수평 줄 × 0.5 초, 짚라인 × 무시. 데이터: 방향 제한 ATHN03C gorope12·ATHN05C·PAND04E 매단 줄 = 1, ATHN06C = 2, 나머지 0. 줄 z 축은 HDES01E gorope3 만 45°, 나머지 +z.

### 15.3 매단 줄 위 몸 방향 (0x1C6858 – 0x1C6B18) 과 보간 (0x1E0C2C – 0x1E0DE4) [확실: 디스어셈블]
- 흔들기 버튼 (s5 = +0x310 0x10)·줄 미끄럼 (0x8000) 이 아니면: 지금 번호 = 후보 중 지금 뒤 (+0x70) 에 가장 가까운 것. +0x2D8 0x2000000 이 아니고 **+0x150 = 1** 일 때만:
  방향 제한 줄 (간격 2) = −+0x2E0 에 가장 가까운 후보로 / 4 방향 줄 = **+0x2F0 (패드 스틱 벡터, 0x1D6E04) 의 x · w &gt; 0.5 면 번호 + 1, &lt; −0.5 면 − 1**. 바뀌면 +0x150 = 0.1, +0x154 = 1.1111, +0x174 |= 0x20000000.
  몸 축 = 후보[번호] (+0x70), 후보[번호 − 1] (+0x50).
- 보이는 몸 (+0x90 행렬): +0x150 ≠ 1 이면 매 프레임 slerp(보이는 몸, 몸 축, +0x150 × f) 뒤 +0x150 ×= 1 + (+0x154 − 1) × f (f = 60 Hz 프레임 수 × 배율), 1 에서 멈춤.
  0.1 × 1.1111^k ≥ 1 → **k = 22 프레임 (0.37 초)** — 그동안 다음 회전 입력은 무시. 즉 90° 씩, 0.37 초에 한 번 (평균 ≈ 245 °/초).
- 적 (docs/32): AI 두 번째 출력 (1, 0, 0, 1) = +0x2F0 → x · w = 1 → 번호 + 1 을 0.37 초마다, 정렬 (몸 정면 · 목표 &gt; cos45) 되면 w = 0 출력으로 멈춤. 방향 제한 줄은 AI 스틱 0 → 첫 후보로만.
  엔진 `EnemyNav.hang` / `turnRope` (전 π rad/s 연속 회전 [근사] 폐기), 정렬 판정은 보이는 몸 방향 [추정].
- 엔진 영웅: `ropeTurn`, 번호 + 1 = 엔진 몸 오른쪽 (rightOf) 쪽 90° → 스틱 오른쪽이 오른쪽으로 돈다 [추정: 좌우 대응, w = 1]. 흔들기 면 = 이 몸 축 (전 "○ + 스틱 = 정면" [근사] 폐기).

### 15.4 양끝 고정 줄 시뮬레이션 (0x216FC8 / 0x218488 / 0x217748) [확실: 디스어셈블]
- 0x216FC8 (줄 만들기): 노드 수 +0x54 = 사슬 관절 수, **+0x60 길이 = SCR +0x28 (미터)** (+0x384 = SCR + 0x24 의 +4), 칸 +0x5C = 길이 / (n − 1).
  끝 고정 (SCR f1, +0x384+0xC) 이면 길이 = |끝점 − 매단 점| (미터), 초기 노드 = 매단 점 → 끝점 곧은 선 (0x217174), 아니면 매단 점에서 곧게 아래로 (0x217200).
  → SCR +0x28 은 "최대 흔들림 각도" 가 아니라 **줄 길이**: 전 레벨 매단 줄 관절 사슬 길이 / 16 과 같다 (ATHN03C gorope 15 m = 240 / 239.7, ATHN05C 6 m = 96 / 95.7 …). `RopeDef.lengthM`, `NavRope.simLength()`.
- 0x216DCC: f3 → +0x374 = 4 (짧은 사슬), f1 → 0x218488(끝점 × 16, 길이): +0x374 & 4 가 아니면 끝 노드 번호 +0x40 = int(길이 / 칸), 끝점 +0x10 = 끝 / 16, +0x374 |= 1. f2 (짚라인) → +0x374 |= 2.
- 한 단계 0x217478: 적분 뒤 +0x374 & 1 이면 노드 +0x40 위치 = +0x10, 속도 0 (0x217758). 매달림은 힘만 j, j+1 에 나누고 매단 점 스프링 (0x217838 / 0x217954) 은 비트 1 이면 건너뜀. 매달림 최대 무게 10 (0x216D84).
- 엔진: `RopeSim.fixed(top, end, n)` (끝 노드 n − 1 [추정: int 반올림]), 수평 줄·짚라인 손 = 시뮬레이션 줄 위 점 (`placeOnRope`), 무게 10 으로 처짐 (320 유닛 줄 가운데 몇 유닛). 적 (`EnemyNav`) 도 같은 시뮬레이션 (§18.3). 줄 스키닝 렌더 → §17.

### 15.5 물가 올라서기 = 0x1EA5F0 → 0x1CA198 [확실: 디스어셈블]
- 0x1EA5F0 = 내비게이션 공용 이동 (몸 충돌 0x1E2100 → 상태별 이동 뒤 처리). 물 상태 (0x380) 면 0x1CA198:
  바닥 질의 0x1E3298 (a3 마스크 0x8020000) 의 선분 = 위 (내비 위치 + max(d − 6.4, 0)) → 발자국 4–5 점 (내비 − 위 × (6.4 + d)), d = max(이동, 3.2), 처음 맞은 위 법선 &gt; 0.1 면.
  맞으면 **내비 위치 (바닥 + 6.4) &gt; 수면 (+0x230) − 21.76 → 0x1E9470 착지** (= 바닥 &gt; 수면 − 28.16), 아니면 바닥에 박혔을 때만 위로 밀어 올림 (아래로 붙이지 않음).
  그다음 천장 질의 0x1E36B0 이 맞으면 떠 있기·가라앉음에서 잠수 (0x1C95B0).
- 몸 충돌 0x1E2100 (구 0x11EB68, 반높이) 에 계단 오르기는 없다 → **떠 있는 발보다 6.4 넘게 높은 물가는 헤엄쳐서는 못 오른다**. 물 밖으로는 떠 있기 × 점프 (244, §11) → 공중 턱 잡기/착지, 물에 닿은 사다리·Climbable 벽 (0x1C7DA8), 비탈·얕은 턱.
  데이터 (16 격자): 이 규칙의 헤엄 출구는 PAND04E 67, PAND02F 24, PAND02I 14, PAND03A 6, ATHN01B 3 곳뿐 — ATHN02A 수로 등은 사다리·점프로 나간다.
- 엔진: `bankFloor` (표본 = 가운데 + 정면 9.6 [근사: 원작 4–5 점]), 전 "수면 − 32 이상 마른 바닥 (위 24 까지)" [근사] 폐기.

### 15.6 위에서 아래 벽/사다리로 = 0x1E5638 [확실: 디스어셈블]
- 0x1C7F54: 지상 (+0x174 0x1, 떠 있기 0x80 아님), 앞 벽 질의 결과 0, 착지 프레임 아님 → 0x1E5638(a1 = 새 위치).
  a = o − 위 × 35.2 (+0x120.y) + 정면 × 35.2, b = a + 뒤 × (0.5 × 폭 + 키 = 44.8) = o − 위 × 35.2 − 정면 × 9.6. 처음 맞은 면이 Climbable·Ladder (0x18) 면
  P = 적중점 + 법선 × 9.6 (0.5 × 깊이), o → P 선분이 비어 있으면 위치 = P, +0x220 = 법선, 반환 9 → 0x1C7F90: +0x110 = 새 위치, 0x1C7280(1, 9) (끝 비트 없음), 지역 속도 0.
- 즉 위층 끝 너머로 걸어가면 (몸 가운데가 끝에서 ≈ 2.6 안 — o → P 선분이 위층 바닥을 비켜야) 아래 사다리/벽에 붙는다.
- 엔진: `probeClimbDown` (앞면만 [근사: 엔진 충돌은 양면]), 지상 + 스틱으로 움직임 + 앞 벽 없음 + 착지 프레임 아님. 데이터: 사다리 꼭대기에 위층 바닥이 있는 곳 — ATHN02B 7/7 (테스트), ATHN02A 4/4, ATHN02C 11, PAND04G 6/6 …

### 15.7 남은 것
- ~~줄 스키닝 렌더~~ (§17), ~~적의 양끝 고정 줄 시뮬레이션·적 매단 줄 뛰어내리기 속도 (적 Sold +0x140/+0x144, 전역 0x29DBF8 시간 조건)~~ (§18.2–18.3).
- 매달린 중 턱 질의 실패 → 원작은 떨어짐 (엔진 그 자리), ~~좁은 턱 경로 0x1E3EE8~~ (§18.1), 벽 질의 a3 머리 재시도 (0x41), 사다리 가운데 맞춤 (0x1C85A8).
- 좌우 대응: 원작 +0x50 = 뒤 × 위 = 엔진 −rightOf. 엔진 안에서는 스틱·끝·애니 짝이 일관 [추정: 원작 화면 좌우와 같은지 미확인].

## 16. 테스트 (§15)
| 테스트 | 내용 |
|---|---|
| `HeroNavigatorTest.sideJumpCatchesNextWallAcrossGap` | 벽 왼쪽 끝 × → 공중 0.2 초 뒤 틈 10 건너 같은 쪽 벽 잡기 (`wallClimbCL`, z = 벽 + 9.6) |
| `sideJumpWithoutNextWallFalls` | 옆 점프 뒤 일반 벽 잡기 없음, 떨어짐 |
| `walkOffTopOntoLadderBelow` | 위층 끝으로 걸어가면 아래 사다리 (법선 −z) 에 붙음 (z = −9.6, 발 ≈ 위층 − 35.2), 내려가 착지 |
| `ledgeCornerTurnsTowardWall` | 턱 한쪽만 → `probeLedge` null, `probeLedgeCorner` = 벽 법선, 공중에서 비스듬한 몸을 벽 쪽으로 |
| `ropeJumpWaitsHalfSecondAndSnapsToRopeAxis` | 0.5 초 전 × 무시, 2 방향 줄 (축 45°, dirs 2) 에서 몸 = ±(z × 위), × = 스틱 쪽 후보로 |
| `hangingRopeClimbAndSwing` (수정) | 잡을 때 번호 2 (정면 −z), 스틱 오른쪽 → 번호 3 (+x), 22 프레임 전엔 다음 회전 없음, 흔들기 = 몸 축 면, × = 앞 224 · 위 224 `jumpAir` |
| `traverseCrossNeedsHalfSecondAndZiplineIgnoresCross` | 수평 줄 × 0.5 초, 양끝 고정 줄 처짐 (무게 10), 짚라인 × 무시 |
| `waterSwimsToShore` (수정) / `highBankBlocksSwimmer` | 수면 아래 24 턱 = 올라섬 / 수면 아래 2 바닥 + 벽 = 못 오름 |
| `RopeSimTest.fixedRopeHoldsBothEndsAndSagsUnderHero` | 길이 20 m, 곧은 선, 끝 노드 고정, 무게 10 처짐, 놓으면 덜 처짐 |
| `NavDataTest.ropeLengthFieldAndJumpDirections` | SCR +0x28 = 길이 (ATHN03C 15 m = 관절 길이), dirs NorthSouth 1 / EastWest 2, HDES01E 축 45° |
| `NavDataTest.athn02bLaddersMountFromAbove` | ATHN02B 사다리 꼭대기 7 곳 모두 위에서 붙음 |
| `NavDataTest.pand04eShelfExitsWater` | PAND04E 수면 아래 턱으로 헤엄쳐 올라섬 |
| `EnemyNavTest.hangingRopeReleasesTowardGoalAwayFromAnchor` (수정) | 적 매단 줄 회전 = 90° 두 번 (22 프레임마다) → 정렬까지 ≈ 43 프레임 |

`NavDataTest.heroNavigationTuningIsSold000` 에 Sold +0x140 / +0x144 (14 / 14) 추가. 순회 검증 (`TraversalTest` 15): 강제 뒤에만 나오는 전환 0, 숨김 상호작용 0 그대로. 전체 `:engine:test` 326.

## 17. 줄 스키닝 렌더 (SCR_RopeSkinned 0x216FC8 / 0x217E5C) [확실: 디스어셈블] — 47 문서 §5
- 노드 ↔ 관절: 줄 오브젝트 관절 0 (매단 점, +0x48) 의 첫 자식부터 형제를 따라 관절 플래그 0x20 인 사슬 관절만, 매단 점에서 가까운 순으로 노드 0, 1, …
  노드 수 = 사슬 관절 수 (ATHN03C gorope12 41, gorope 61, ATHN05C 25, PAND04E 매단 줄 37·짚라인 105, ATHN06C 41) — `NavRope.joints` 를 사슬 수로 바꿈 (전: 관절 수 [추정]).
- 관절 = 행 (X, Y, Z, 16·p): Y = normalize(p[i−1] − p[i+1]) (끝은 한쪽 차분), X = normalize(Y × ref), Z = X × Y, ref = 매단 줄 (0,0,1) / 양끝 고정 (0,1,0).
  잠든 (0.2 초 정지) 줄은 관절을 갱신하지 않는다 (0x217B38).
- 엔진: `nav/RopeSkin`, `GameScene.buildRopeVisuals` / `poseRopes` → `LevelScene.poseInstance` (스키닝). 레벨의 모든 줄에 시뮬레이션을 만들어 매달리지 않아도 그린다.
  `-Dgow.norope` 면 예전 정지 자세, `-Dgow.ropedebug` 노드 출력. 테스트 `RopeSkinTest` (4).

## 18. 좁은 턱 경로·적 줄·GeneralGuide (docs/90 §4 항목 4·5 후속)

### 18.1 좁은 턱 윗면 = 턱 질의 0x1E3A80 의 0x1E3EE8 – 0x1E4088 (`HeroNavigator.narrowLedge`) [확실: 디스어셈블]
- 갈림 (0x1E3EC0 – 0x1E3EE4): 두 수직 선분이 턱을 찾고 (§2.4 1), 내려가는 중 조건과 머리 → 턱 + 1 선분 (2) 을 통과한 뒤, **첫 수직 선분이 맞은 면** (0x8C(sp) = 결과 +0x2C) 의
  bitfield0 &amp; **0x10000004 (Narrow·NarrowNoBB)** 이면 0x1E3EE8, 아니면 일반 경로 0x1E408C (§2.4 3–4). BackPress 는 들지 않는다 (벽 걷기 마스크 0x10080004 와 다름).
- 좁은 경로: a = 턱 − 위 × (+0x140 + d) − 정면 × 0.5 깊이 + 위 × (+0x140 − 2) = **턱 − 위 × (d + 2) − 정면 × 9.6**, b = a + 정면 × 깊이 (19.2) 선분 **하나**.
  못 맞으면 실패 (0x1E3F68 → 0x1E4678 반환 0 — 일반 경로로 넘어가지 않음). 맞으면 +0x234 = 턱 면, +0x210 = 턱 법선, +0x240 = 턱 면 소유 (0 이면 0x2436F0),
  +0x220 = **맞은 면 법선의 수평 성분 정규화** (0x1E3FA8 – 0x1E3FD0), +0x238 / +0x244 / +0x24C / +0x250 = 벽 적중 결과의 면·소유·재질.
- 위치: **+0x174 &amp; 0x30 (매달림 0x10 · 벽 0x20) 이 아니면** (공중에서 처음 잡을 때) x, z = 적중점 + 법선 × 0.5 깊이 (9.6), y = 턱 − (+0x120.y − +0x130.y) (내비 위치 = 턱 − 28.8 → 발 = 턱 − 35.2).
  이미 붙어 있으면 위치를 쓰지 않는다 (0x1E3FE4 → 0x1E4058): 매달린 채 옆으로 갈 때·벽 오르기에서 턱으로 넘어갈 때 후보 위치 그대로.
  일반 경로는 붙어 있어도 위치를 다시 맞추고 지역 속도에서 법선 성분을 뺀다 (0x1E44C8) — 좁은 경로엔 없다. 반환 1.
- 일반 경로와의 차이: 벽 두 선분 (중심에서 ±9.6 로 벌어짐) · 세 점 평면 · 머리 선분이 없고 **가운데 선분 하나** → 윗면보다 좁은 기둥·평균대 앞면 (폭 &lt; 약 7) 에서도 잡는다.
  선분 높이 (턱 − d − 2) 는 일반 경로의 재시도 높이와 같아서, 앞면이 턱 아래 5.2 까지 내려오지 않는 얇은 판은 두 경로 모두 못 잡는다.
- §12 와의 관계: 좁은 턱 벽 걷기 (§12, 벽 질의 0x1E4D08) 는 **벽 면** 이 Narrow·NarrowNoBB·BackPress 인 경우, 이 경로는 **윗면** 이 Narrow·NarrowNoBB 인 경우다.
  같은 Narrow 면 (평균대 = 옆면 + 윗면, §12.2 데이터) 이 위에서는 균형 걷기 바닥, 옆에서는 (머리 선분이 빗나가) 벽 걷기가 아니고, 아래에서 뛰어오르거나 떨어질 때는 이 경로로 매달리는 턱이다.
- 데이터 (`NavDataTest.narrowLedgeTopsCatchFallingHero`, 윗면 삼각형마다 4 방향에서 떨어지며 질의): ATHN06B 평균대 윗면 88/88, PAND03E 59/59, ARENA08 NarrowNoBB 기둥 윗면 160/576 (가장자리에 닿는 삼각형).
  Narrow·NarrowNoBB 윗면이 있는 레벨: ARENA06–27 (NarrowNoBB), ATHN01B/01C/01D/01E/04A/06B/06C/08B, PAND01A/02B/03E.
- 엔진: `probeLedge` 가 첫 윗면이 `narrowLedge(tri)` 이면 `narrowLedge(...)` 로, 붙어 있음 = `keepN != null` (매달림 좌우 이동·벽 오르기에서 턱 판정).

### 18.2 적 줄 뛰어내리기 = 영웅과 같은 0x1C5488, 전역 줄 시계 0x29DBF8 [확실: 디스어셈블]
- AI 점프 버튼 0x1CF1C8 (카운터 +0x84 가 f12 초를 넘으면 입력 객체 +0x30 |= 1) → 내비게이션 +0x310 비트 1 = × → 줄 갱신 0x1C6008 의 0x1C6B58 이 **모든 생물에 대해** 0x1C5488 을 부른다.
- 0x1C54B0: **+0x174 비트 31** 이면 0x29DBF8 += (1/60) × (vtable +0xC8 × 0.25 + 0.75) × +0x16C. 비트 31 = 내비게이션 생성 0x1E7E20 의 다섯째 인자 t0 (0x1E7E68) —
  **영웅 생성 0x1D6368 (li t0, 1) 만 1**, AI 생성 0x1CECC8 · 0x1C039C · 0x1C2D08 은 0. 0x29DBF8 은 줄을 잡을 때 (0x1C5D98) 누가 잡든 0. 다른 참조 없음 (lui 0x2a / −0x2408 다섯 곳뿐).
- ×: 짚라인 (+0x174 &amp; 0x40000) → 아무것도 안 함. 수평 줄 (0x20000) → 비트 31 이면 0x29DBF8 ≤ 0.5 일 때 무시, **전투 기술 중 (combatant +0x324 → +0x4C ≠ 0)** 이면 무시,
  아니면 0x1E9030(2). (+0x4C 는 기술 시작 0x20976C 가 기술 포인터를 쓰는 칸 — §12.1 의 "전투 기술 아님" 0x1C7DC0 과 같은 검사. §15.2 의 "AI 면 무시" 정정.)
  매단 줄·사슬 → 줄 오브젝트 (vtable +0x30) 가 있고 **0x29DBF8 &gt; 0.5 (비트 31 검사 없음)** 이면 §15.2 의 뛰어내리기.
- 결과: 적은 매단 줄에서 **영웅이 그동안 (어느 줄이든) 0.5 초 넘게 매달려 있었을 때만** 뛰어내린다 — 적이 잡는 순간 시계가 0 이 되고 적의 갱신은 더하지 않는다.
  영웅도 적이 다른 줄을 잡으면 시계가 0 으로 돌아가 다시 0.5 초를 기다린다. 수평 줄 × 는 적에게 시간 조건이 없다.
- 속도: 0x1C55DC owner+0x320 → +8 = **생물 자기 Sold** 의 +0x140 (위) / +0x144 (앞). 전 적 Sold (R_* WAD 의 TWK_Sold 53 개) 모두 14 / 14 = 크라토스와 같은 224 / 224
  (전 엔진 근사 "정면 40 + 점프 Sold+0xC4 17 m/s" 폐기). 정면 = 줄 축 후보 중 −(+0x2E0) 쪽 — AI 는 스틱 0 이라 +0x2E0 = 지금 정면 → 정렬된 후보 그대로.
- 엔진: `NavWorld.ropeGrabbed()` / `tickRopeClock(dt)` (영웅 줄 상태에서만) / `ropeClock()`, 영웅 × 조건을 `stateTime` 에서 이 시계로, `EnemyNav.ropeJump` (`RopeRide.jumpFace` /
  `jumpVelocity` / `kick` 공용), `AiData.Ride.ropeJumpUp/Out` (Sold +0x140/+0x144). 짚라인 적의 "가파르게 아래면 점프" [근사] 폐기 — 끝까지 탄다.
  [근사] 원작 시계는 레벨이 바뀌어도 이어지는 전역, 엔진은 `NavWorld` (레벨 장면) 마다 0 에서 시작.

### 18.3 적의 줄 시뮬레이션 (양끝 고정 줄·매단 줄) [확실: 같은 코드 경로]
- 적의 줄 잡기도 0x1C5C20 → 0x218200 (매달림) · 0x2183B0 (위치) 이고 줄 위 몸은 0x2184E8 (시뮬레이션 줄 위 점) − 키 — 영웅과 같은 줄 오브젝트 (줄당 시뮬레이션 하나).
  흔들기 힘 0x1C6BC8 에도 비트 31 검사가 없다 (줄 +0x384 +8 플래그만) → 적도 잡을 때 0.25 초 창 (0x29DBF4, 0x303FB0/0x303FC0 축) 으로 한 번 민다. 흔들기 버튼은 AI 가 내지 않는다.
- 엔진: 줄 시뮬레이션 지도를 `HeroNavigator` 에서 `NavWorld.sim(NavRope)` 로 옮김 (영웅 `sim()` 은 그대로 위임 — GameScene 줄 스키닝도 같은 것). `EnemyNav.Rope.src` = 줄 오브젝트,
  잡을 때 `attach`, 매 틱 수평 줄·짚라인은 `moveAttach(줄 위치)`, 몸 = `pointAt(dist)` − 손 높이, 놓을 때 `detach`. 매단 줄은 `RopeRide.force` (흔들기 창) 를 매 프레임 준다.
  흔들기 상태 (창·축·저역 통과) 는 생물마다 [근사: 원작 창·축은 전역 하나].
- ~~[근사] 적 손 높이 발 + 30~~ → **손 높이 = 키 = Sold +0x238 × 16 × 개체 스케일 (내비 +0x120.y)** [확실]: 잡기 0x1C5C20 이 위치 + 위 × +0x120.y 를 줄 찾기 점으로 쓰고
  (0x1C5C68 `vmuly` · 0x1C5C74 `vadd`), 줄 위 몸 = 줄 점 0x2184E8 − 위 × +0x120.y (0x1C66E4 – 0x1C66F4) — 영웅 `ROPE_HAND` 35.2 와 같은 식.
  엔진 `EnemyNav.hand()` = `bodyHeight()` (해골 Sold_020 2.8 × 0.87 = 38.98, 미노 3.25 × 16 …). 줄 아래 목표 판정 (`goalOnRope`) 도 같은 값.
  한 줄에 영웅·적이 함께 매달리면 매달림 칸 하나를 나눠 쓴다 (원작도 칸 하나 [추정]).

### 18.4 GeneralGuide 면과 영웅 질의 [확실: 디스어셈블]
질의 제외 마스크 0x3309F4 (면 재질 bitfield0 과 AND, 0 이 아니면 그 면 건너뜀 — 43 §13.10 6) 를 질의마다 다시 읽음:

| 질의 | 마스크 | GeneralGuide (0x1000000) |
|---|---|---|
| 바닥 0x1E3298 | 호출 (0x8020000) \| **0x1010042** \| (+0x258 &amp; 0x4300000) (0x1E34AC) | **뺌** — 바닥이 아님 |
| 몸 0x1E2100 | 호출 \| **0x10042** \| (+0x258 &amp; 0x4300000) (0x1E221C) | **넣음** — 몸을 막는다 |
| 턱 0x1E3A80 | **0x9030042** \| (+0x258 &amp; 0x4300000) (0x1E3C54) | 뺌 — 선분이 지나감 |
| 벽 0x1E47E0 · 옆 벽 0x1E53F0 | **0x9030042** \| +0x258 (0x1E492C / 0x1E5530) | 뺌 |

- 몸 질의 호출 마스크: 0x1EA200 = 0x8020000 (전투 기술 플래그 0x60 이면 0x28020000), 0x1EA5F0 = 0x20000 (벽·벽 옆 끝이 아닐 때) | 0x8000000 (플레이어 전투 기술 중이고 +0x13 ≠ 2 일 때만 **빠지지 않음**)
  | 0x20000000 (기술 플래그 0x60) — **어디에도 0x1000000 이 없다** (세부 §18.6). 43 §13.10 6 의 "몸 0x1E2100 = 호출 | 0x1010042" 는 바닥 질의 상수를 옮겨 적은 것 (정정).
- 뜻: GeneralGuide = **보이지 않는 막이 벽** (ATHN01B goplayerblock "guideMat", 레벨마다 `*_GeneralGuide` 재질 수천 면) — 몸은 막고, 그 위에 서지는 못하고, 벽 오르기·턱 판정은 그 뒤의 진짜 벽을 본다.
- 엔진 (정적 충돌 시트):
  - 바닥 `NavWorld.floor` 가 GeneralGuide 면을 뺀다 (`FLOOR_EXCLUDE_STATIC`) — 전 레벨 GeneralGuide 윗면 ≈ 200 (ATHN01D 8, ATHN02B 16, PAND04J 24 …) 에 더는 서지 않음.
    Water·Death 바닥은 엔진이 바닥 질의로 물·사망 면을 알아내므로 그대로 둔다 [근사].
  - **ClimbGuide 도 바닥에서 뺀다** (43 §19): 0x1EA5F0 은 벽에 붙어 있지 않으면 호출 마스크 s2 에 0x20000 을 넣고 (0x1EA6A0 – 0x1EA6B8) 그 s2 로 바닥 질의
    0x1E3298 을 부른다 (0x1EA8D4), 자유 이동 0x1EA200 은 0x8020000 그대로 (0x1EA2DC) [확실]. 바닥 질의는 벽에 붙지 않았을 때만 쓰므로 정적 거름에 넣었다.
    위를 보는 ClimbGuide 면 = 전 레벨 358 (ATHN05A 덩굴 통로 146 · PAND04E 96 …, 덩굴 벽 아래 16 폭 띠 상자 윗면) — 예전엔 ATHN05A 에서 뒤로 점프한 크라토스가
    맞은편 덩굴 벽 대신 띠 윗면 (y 175 / 205) 에 내려섰다. 위를 보는 CombatGuide 면은 데이터에 없다.
  - 벽·턱·옆 벽·위에서 사다리로 선분 = `NavWorld.segment(from, to, WALL_PROBE_EXCLUDE / LEDGE_PROBE_EXCLUDE)` (0x9030042 = Water·Death·AIBlock·ClimbGuide·GeneralGuide·CombatGuide, 벽은 NoPlayerUse 도).
    공중 다이빙 물 선분 등 다른 선분은 그대로.
  - 몸 구 밀어내기 (`LevelCollision.collideSphere`) 는 원래 GeneralGuide 를 막고 있었음 → 그대로 [확실]. CDV hull `Platforms.WALL_EXCLUDE` 도 이미 같은 규칙 ([근사] → [확실]).
    ~~[근사] 정적 시트 몸 충돌은 NoPlayerCollision 만 빼고 Water·Death·AIBlock·ClimbGuide·CombatGuide 벽은 막는다~~ → §18.6 상태별 마스크 [확실].
- 순회 검증 (`work/verify/report.md`): 변경 전후 같음 (TraversalVerifier 의 바닥 칸은 충돌 시트 질의를 직접 써서 이번 변경과 무관, 내비게이터를 쓰는 도착 검사도 그대로).

### 18.5 테스트
| 테스트 | 내용 |
|---|---|
| `HeroNavigatorTest.narrowLedgeTopHangsWithSingleCenterRay` | 윗면 폭 10 · 앞면 폭 6 기둥: Ground 윗면 = 실패, Narrow / NarrowNoBB = 잡음 (발 = 턱 − 35.2, z = 벽 + 9.6), 붙어 있으면 위치 그대로, 떨어지다 매달림, 턱 − 5.2 에 벽이 없으면 실패 |
| `generalGuideBlocksBodyButIsNoFloorOrProbeTarget` | GeneralGuide 윗면은 바닥 아님, 몸 구는 막힘, 벽 질의는 막이 뒤 Climbable 벽에 붙음 |
| `ropeClockIsSharedAndResetByAnyGrab` | 영웅 수평 줄 0.5 초 뒤 적이 줄을 잡으면 (시계 0) × 무시, 다시 0.5 초 뒤 놓기, 줄 밖에선 시계 정지 |
| `EnemyNavTest.hangingRopeReleasesTowardGoalAwayFromAnchor` (수정) | 정렬 ≈ 43 프레임, 시계 0 이면 매달린 채, 시계 0.6 → 앞 224 · 위 224 (정점 31.4) |
| `traverseRopeEnemyHangsOnSharedSagSim` | 적이 공유 시뮬레이션에 매달림 (무게 10, 손 = 줄 위 점, 처짐), × 놓기 = 매달림 해제 |
| `ziplineIgnoresJumpWhenGoalSteeplyBelow` (이름·내용 바뀜) | 목표가 가파르게 아래여도 짚라인을 계속 탐 |
| `NavDataTest.narrowLedgeTopsCatchFallingHero` | ATHN06B 88/88, PAND03E 59/59, ARENA08 160 |

전체 `:engine:test` 402 (397 + 5).

§18.6 · 적 손 높이 (407 → 413 중):

| 테스트 | 내용 |
|---|---|
| `HeroNavigatorTest.bodyQueryMasksFollowStateBits` | 마스크 표 (영웅 기본·공격·방어·피격 반응·적·벽·자유 이동 + flags 0x60), 정적 시트 구 밀어내기: 기술 없는 지상은 ClimbGuide·CombatGuide·Water 지나감, 공격 중 CombatGuide 막힘, 벽 마스크는 ClimbGuide 막힘 |
| `climbGuideFencesWallClimbing` | 오르는 벽에 수직인 ClimbGuide 막이 x = 20: 오른쪽으로 오르면 x = 20 − 9.6 에서 멈춤, 막이 없으면 계속, `wallBody` 위 선분이 천장 50 에 막혀 발 = 50 − 35.2 |
| `EnemyNavTest.ropeHandHeightIsSoldHeight` | 해골 몸 (2.8 × 0.87): 손 = 38.98, 잡은 몸 = 줄 − 키 |
| `ropeCatchMoveAlongAndDropAboveGoal` (수정) | 놓는 수평 거리 = 줄 − 키 |

### 18.6 몸 질의 면 거름 = 상태별 호출 마스크 (docs/43 §13.10 6 후속) [확실: 디스어셈블]
0x1E2100 의 면 제외 0x3309F4 = **호출 마스크 | 0x10042 | (+0x258 &amp; 0x4300000)** (0x1E221C – 0x1E2240). 면 재질 bitfield0 &amp; 이 값 ≠ 0 이면 지나간다.

| 비트 | 이름 | 언제 빠지나 (지나감) | 근거 |
|---|---|---|---|
| 0x2 · 0x40 · 0x10000 | Water · Death · AIBlock | 늘 | 고정 0x10042 |
| 0x100000 / 0x200000 | NoPlayerCollision / NoAICollision | 영웅 / 적 (각자 하나) | 영웅 +0x258 = 0x500000 (0x1D63E0), AI = 0xA00000 (0x1CEEAC – 0x1CEEB4 [확실]; 다른 AI 생성 경로 [추정]) |
| 0x20000 | ClimbGuide | 자유 이동 (0x1EA200) 은 늘. 지상·공중·물 (0x1EA5F0) 은 **벽 (+0x174 0x20) 도 벽 옆 끝 점프 (+0x2D8 0x300) 도 아닐 때** (0x1EA6A0 – 0x1EA6B8). 줄 0x1C5848 은 0x300 이 아닐 때 | → 벽에 붙어 있을 때만 막는다 |
| 0x8000000 | CombatGuide | 0x1EA200 은 늘. 0x1EA5F0 은 플레이어 (+0x174 bit 31) 가 아니거나, 기술이 없거나 (combatant +0x4C = 0), 기술 (+0x54) +0x13 = 2 (피격 반응 HIT*·DEATH) 일 때 (0x1EA6C0 – 0x1EA6F4). 줄은 늘 | → **플레이어의 공격·방어 기술 중에만** 막는다. 적은 늘 지나감 |
| 0x20000000 | NoCSMCollision | 기술 flags (+0x0E) &amp; 0x60 (동기 기술) 일 때 (0x1EA28C / 0x1EA6FC – 0x1EA724) | 데이터: ATHN01E 바닥 16 면뿐 |
| 0x1000000 | GeneralGuide | 안 빠짐 (늘 막음) | §18.4 |

- 0x1E2100 은 벽 (+0x174 0x20, 좁은 턱 0x300000 아님) 이면 **벽 위 몸 질의 0x1E25E0** 로 간다: 몸 가운데 c = 내비 위치 + 위 × (+0x140 − 키/2) = 발 + 키/2 에서
  벽 평면 안 선분 10 개 (오른쪽 축 ± 반폭 × 위 ± 키/2, 위 ± 키/2, 오른쪽 ± 반폭 × 위 ± 키/6 — 앞뒤 성분 0) 를 차례로 쏘고 (0x1E1F28 → 0x11E190 가장 가까운 것),
  맞을 때마다 c −= n · (n · (끝 − 충돌점)) (끝점이 그 면 위에 오도록). 반환 0x40 / 0x100 (선분별 고정 비트). 호출 마스크는 0x1EA5F0 그대로라 ClimbGuide 가 여기서 막는다.
  → ClimbGuide = **오르는 벽에 수직으로 선 보이지 않는 막이** (오르기 범위 울타리). 데이터 (벽 면 수): ATHN01D 242 · PAND04E 434 · ATHN02B 111 · PAND01C 118 · ATHN05A 82 …
  CombatGuide 벽 면: ATHN01D 201 · ATHN05C 64 · SEWR02 44 · SEWR01 38 · ATHN01E 32 (전투 구역 울타리).
- 엔진:
  - `NavWorld.bodyExclude(player, free, wall, inMove, moveFlags, guard)` = 위 표. `HERO_BODY_EXCLUDE` (기술 없는 영웅) = 예전 CDV `Platforms.WALL_EXCLUDE` 와 같은 값.
  - `LevelCollision.collideSphere(c, r, exclude)` · `Platforms.collideSphere(…, exclude)` · `NavWorld.collideSphere / collideBodies(…, exclude)` — 정적 시트도 CDV 와 같은 거름
    (예전 정적 시트는 NoPlayerCollision 만 뺐다).
  - 영웅 지상·공중 (`GameScene.physics`): `heroBodyExclude()` = 현재 기술의 stateMask &amp; 0x1C = 0 이면 자유 이동, flags · guard (+0x13) 로 CombatGuide·NoCSMCollision. 물 (`HeroNavigator.swim`) 은 기본 마스크.
  - 벽 오르기·사다리 (`HeroNavigator.climb`): 후보 위치에 `wallBody` (0x1E25E0 선분 10 개, 벽 마스크 = ClimbGuide 막음). 머리가 위 면에 막혔을 때 그 면이 몸 바로 위 발판이면 올라서는
    엔진 보조 (예전 꼭대기 보조와 같은 조건, ATHN01D 돛대 꼭대기) [근사].
  - 적 (`EnemyNav` 지상 0x1EA5F0 / 자유 이동 0x1EA200): `bodyExclude(false, …)` — NoAICollision 지나감 · NoPlayerCollision 막힘 (예전엔 반대였다) · CombatGuide 늘 지나감,
    기술 flags 는 `EnemyFighter.rootMotion` 이 `nav.moveFlags` 로 넘김. 적 벽 이동은 엔진에 몸 질의가 없어 ClimbGuide 를 보지 않는다 [근사].
  - Water·Death 면은 이제 몸을 막지 않는다 — 엔진은 물·사망을 바닥 질의로 알아내므로 그대로 동작 (Water·Death 벽면: PAND04E Death 272, PAND03E 6, ARENA* 8 …).
- 순회 보고서: 변경 전후 같음 (`cmp`).

## 19. 이동 선분 마지막 검사 — ATHN01A 갑판 구석 낙하 · 적이 배 밖으로 밀려남 [확실: 디스어셈블 / 근사: 선분 높이·바닥 면]
버그 (사용자 보고, ATHN01A 첫 전투): ① 시작 카메라 화면 왼쪽 (−x) 구석에서 크라토스가 갑판 밖으로 떨어져 리스폰, ② 적이 가끔 배 밖으로 밀려나 돌아오지 못하고 멀어짐.

**ATHN01A 갑판 가장자리** (시트 RIB_sheet): 둘레에 `visuals01A_lambert345` 막 (GeneralGuide · NoAICollision · NoAIUse, 높이 0–176) — 왼쪽 x 1416 (z 1576–1779), 고물 z 1576,
오른쪽 x 1648, 이물 쪽 사선 (1416, 1779) → (1435, 1832) → (1455, 1888) → (1517, 1996) 과 그 반대편. 같은 자리에 `visuals01A_groundWood` 난간 벽 (Ground, 높이 14.6 — 고물 z 1580 은 5.6).
막은 영웅만 (§18.4), 난간은 영웅·적 모두 막는다. 바다 = `ENV_DeathZone1..5` (y ≈ −290 판). 막 밖 아래에는 ATHN01B (다른 슬롯) 바닥·선창 천장 (y −64) 이 있다.

**원인 ①** (GameScene 로그 재현: 구석 (1428, 0, 1592) 에서 해골 넷에 몰림): 한 틱에 생물 밀어내기 (`BodyPush`, 0x1E2DD8) 가 영웅을 x ≈ 1410 까지 밀면
몸 구 (발 + 20, 반지름 12) 의 가운데가 얇은 막 (x 1416) 을 넘어 `collideSphere` 가 **바깥쪽** (1404) 으로 밀어낸다 → 다른 슬롯 바닥만 밟음 → ATHN01B 로 걷기 전환 / 바다 낙하.
**원인 ②**: 적 몸 구는 발 + 14 위 (`EnemyNav.STEP`) 라 난간 (14.6) 을 거의 보지 않고, 막은 NoAICollision 이라 지나감 → 밀리거나 걸으면 난간을 넘어 배 밖으로
(바닥이 없으면 높이 유지 [엔진] 라 떠서 계속 멀어짐; 시험: 난간 쪽으로 3 초 걸으면 x 1061 까지).

**원작** (0x1EA5F0 끝 0x1EAB78 – 0x1EAC20, 자유 이동 0x1EA200 의 0x1EA3D8 – 0x1EA478, 영웅·적 공용):
- 몸 밀어내기 0x1E2DD8 → 몸 질의 0x1E2100 (몸 가운데에서 바깥으로 쏘는 선분 — 가운데가 면을 넘으면 못 봄) → 상태별 이동·바닥 0x1E3298 뒤,
  **이전 내비 위치 (+0x110) → 새 위치** 선분을 0x11E190 (모드 2, 콜백 0x1E1E80 = 오브젝트 flags 0x40 이면 0xB) 으로 던진다.
- 면 제외 0x3309F4 = **0x2030002** (Water · AIBlock · ClimbGuide · NoDiving) | (+0x258 &amp; 0x4300000) (영웅 NoPlayerCollision / AI NoAICollision) | 호출 마스크
  (0x1EA5F0 의 s2: ClimbGuide · CombatGuide · NoCSMCollision, §18.6). 몸 질의 (0x10042) 와 달리 **Death 는 막는다**, GeneralGuide 는 늘 막는다.
- 맞으면 새 위치 = 충돌점 (0x2C8F10) + 면 법선 (0x2C8F20) × **0.05** (0x3D4CCCCD) 로 두고 밀어내기·몸 질의부터 다시, 최대 3 회 (s3 &lt; 3).
- → 밀어내기·피격 변위가 몸 가운데를 막·난간 너머로 넘겨도 선분이 막는다. 적은 막을 지나가지만 난간 (Ground 벽) 에 막힌다 (내비 위치 = 발 + 바닥 0.4 m = 6.4 &lt; 14.6).

**엔진**:
- `NavWorld.moveExclude(body)` (몸 질의 마스크에서 위 마스크), `NavWorld.blockMove(from, to, lift, exclude)` — 발 위치 from → to, 선분 높이 = max(두 발 y) + lift (내비 위치 = 발 + 바닥),
  수평 선분, 정적 시트 (두 슬롯) 의 **벽 면만** [근사: 원작은 두 내비 위치 그대로·바닥 면도 — 엔진은 바닥 붙이기가 계단을 맡으므로 바닥 면을 보면 계단에서 막힌다].
  법선은 시작점 쪽으로 (양면 질의). 수평 64 넘는 변위 (스폰·순간 이동·동기 배치) 는 건너뜀 [엔진].
- 영웅 `GameScene.physics`: 밀어내기 → 몸 구 (`pushWalls`) → `blockMove(prevPosition, p, FOOT 6.4, moveExclude(heroBodyExclude()))` 막히면 몸 구 다시, 최대 3 회 + 마지막 한 번.
- 적 `EnemyNav.update`: 지상·공중·자유 이동 단계 뒤 `guardMove` — 시작 = 지난 이동 단계가 끝난 발 위치 (`last`, 원작 +0x110; 루트 모션은 그 뒤에 더해짐), lift = `bodyBase()`,
  마스크 = `moveExclude(bodyExclude(free, moveFlags))`. 벽·줄 모드는 건너뜀 (그 위치가 그대로 `last`).
- 시험 `nav/DeckEdgeTest` (ATHN01A 원본): 몸 구만이면 막 밖 1404 로 빠지는 재현 + 선분이 막 앞 (1416.05) 에서 멈추고 몸 구가 1428 로 되밀기, 원작 마스크 값,
  해골이 난간 쪽으로 3 초 걸어도 / 영웅 몸에 난간으로 밀려도 x &gt; 1416. 게임 실행 (`-PgowScript=LS:1:25:-1:-1`, 40 초 구석 전투) 전환·낙하 없음.
- 남은 것: 고물 난간은 5.6 높이라 원작 규칙 그대로면 적 선분 (6.4) 이 넘는다 (시험에선 선실 벽·난간 몸 구에 막혀 배 안에 남음). ~~배 밖 바닥 없는 곳의 적은 여전히 높이 유지 [엔진]~~
  → 해결 (28 §7): `EnemyNav` 지상에서 바닥이 없으면 공중으로 바꿔 중력 낙하 (데스존 판 1029 로 사망), 막혀 되돌아온 위치에 바닥이 계단 안이면 착지 (`landAfterGuard` —
  바닥 질의가 선분 검사 전 난간 밖 위치에서 돼 떨어지기 시작하던 것), `fallDepth` &gt; 3000 이면 `GameScene` 사망 [근사]. 시험 `EnemyNavTest.walksOffEdgeIntoVoidAndFalls`.

**§19 후속 — 적 몸이 영웅을 갑판 아래로 민 낙하** (자동 플레이 ATHN01A, 43 문서): 원작 이동 단계 순서는 밀어내기 0x1E2DD8 → 몸 질의 0x1E2100 → 바닥 0x1E3298
("바닥 아래로만" 막음, 32 문서) 인데 `GameScene.physics` 는 바닥을 먼저 맞추고 밀어내기를 뒤에 했다. 위에 있는 적 몸 (띄워진·기어오르는 해골) 이 한 틱에 영웅을
10–14 아래로 밀면 갑판 (0) 밑으로 빠지고, 다음 틱 바닥 질의가 그 높이에서 시작해 ATHN01B 선창 천장 (−64) 으로 떨어졌다.
엔진: 바닥 질의 시작 높이 = max(지금 y, 틱 시작 y) + STEP, 접지 중이면 밀어내기·몸 구 뒤 같은 질의로 한 번 더 바닥 아래를 막는다 [근사: 원작 단계 순서 그대로는 아님].

## 20. 손잡이 관절에 붙기 (TAndF·CSM·PushPull) [확실: 0x200A50, 0x1C3918, 0x1C4318 / 근사: 높이]
- 잡을 곳 = 오브젝트 관절 `handle1..8` (0x200A50 "handle%1d"), 판정 값 (0x213A38): 높이차 #25bb317e ×16, 수평 #2b2d2457 ×16, cos(Handle Angle), 뒤 #b2a8856f ×16 (기본 1 / 2 / 0.3 / 0).
- 잡기 질의 0x1C3918: 영웅 앞 점 P = 발 + 위 17.6 + 정면 9.6 이 손잡이 J 와 높이·수평 안, 정면·J.z < −cos (손잡이를 봄), (P − (J − 뒤·J.z))·J.z > 0 — `Interactables.grabbable`.
- 붙은 동안 매 프레임 0x1C4318: 영웅 = J 수평 + J.z·9.6, 정면 = −J.z (블렌드 없음), 높이 그대로 (TAndF 모드 0x10) — `HeroNavigator.alignToHandle`. 영웅 애니는 자기 좌표계 (trapDoorEnter/TandF 손 = 앞 12.5·높이 7–8.5 ↔ 손잡이 높이 9.42).
  ATHN01A 해치 handle1 (1618.78, 9.42, 1660.53) 축 +z → 영웅 (1618.78, *, 1670.13), −z 를 봄. 전에는 R2 누른 자리에서 중심 쪽만 봐 36 유닛·140° 어긋났다 (사용자 보고).
- 뚜껑 (0x213B28): 연타 중 = #d7f2bea8 (trapDoorTandF, 길이 × 위치 — 길이 0 이라 정지), 완료 = #c5b0169f (trapOpen) 속도 1 (영웅 trapDoorOpen 0.6 초와 함께) — `SceneNav.poseItems`.

## 21. Creator 상자의 몸 — 모델·충돌·종류·열림 (`game/ChestProps`) [확실: 디스어셈블·데이터 / 추정: 표시]
사용자 보고 (PS2 영상 대조): ATHN01B 선창에 내려오면 시작 근처에 녹색 체력 상자가 있어야 하는데 없었다 (메시지 441 "궤짝을 열려면 R2 를 누르고 있으십시오" 만 뜸).
원인 둘: `SceneNav.create` 가 불리지 않았고 (스크립트 Creator → R2 아이템이 안 생김), 불려도 소유 GO `gochest2` (오브젝트 `chest2`) 는 **ESC_Chest2 만 있는 메시 없는 자리표**라 그릴 것이 없었다.
- **Creator 생성 0x1ACCA8** (엔티티 클래스 Creator, vtable 0x2BA4FC): 슬롯 0 Name0 앞에 "go" 를 붙이고 ("go" 로 시작하지 않으면, 0x1A4960, 문자열 0x29D8A8) 0x1AD280 → 0x17A7B8 로
  **그 이름의 리소스 (형 3 = 오브젝트)** 를 찾는다 — 레지스트리는 이름을 대문자로 바꿔 해시 (0x175C10 → 0x1759F0 → 0x175938 + Hash127) 라 **대소문자 무시** ("Chest" 40 개도 상자).
  행렬 = **0x1A9CD0: 엔티티 레코드 행렬 (+0x00) · 소유 GO 관절 (+0x40) 월드, 회전 행 정규화** (소유 GO 인스턴스 행렬이 아니다 — 묶음 GO ATHN05A gochestgroup 은 인스턴스 원점,
  상자마다 엔티티 행렬이 자리를 준다), 그 앞에 슬롯 2 (+0xC0) 배율 대각 행렬, 처음 act = 슬롯 1 Name1 (0x1AD110: 오브젝트 애니에서 Name1 의 대문자 해시와 같은 act),
  슬롯 3..6 (unk3..unk6 — unk5 = CSM 템플릿, unk6 = 과제 id), 슬롯 16 Filter = 오브젝트 그룹 → 0x1A5130 이 새 GO 를 만든다 (세이브 포인트 Name0 "savepoint" 도 같은 경로,
  그 밖의 이름은 레벨 WAD 원형 — ATHN05A goaored/goaosks 병사 대 해골병, 43 §19.2-7·8). "chest" = **R_PERMK 오브젝트 `chest`** (MDL_chest·ANM_chest·CDV_chest + FXC 12 개,
  GO 템플릿 `gochest` = IO_CSM) [확실].
- ANM_chest act: `chestParts` (0.033 초, 대기), `chestGrab` (1.967 초 — 영웅 chestGrab 과 같은 길이 = CSM ANICSM1 #fea292e6), `chestOpen` (0.633 초, #fea292e7). 관절:
  chestParts, chestLid, magic2/health2/power2 (뚜껑 아래), handle1, magic, health, power, plain.
- **종류 = CSM "Chest Type"** (#0c67f4f9, 생성자 0x215010 이 enum 으로 +0x9E s16 에, TWK 값의 하위 16 비트): 1 plain, 2 health, 3 magic, 4 power, 5 = 순환.
  ATHN01B/01D CSM 042 HydraHealth = 2, 041 HydraWeapon = 4, 040 HydraMagic = 3, 060–062 Pinata = 1, 002 magicChest 3, 004 healthChest 2, 008 RaidenChest1 5 [확실: 데이터].
  - 0x215AF0 (호출 0x215D80·0x215F90): 표 0x29F340 {magic 3, magic2 3, health 2, health2 2, power 4, power2 4, plain 1} 의 각 관절을 Hash127Upper 로 찾아 GO 관절 표 (+0x104 → +0x54) 에
    **(표 종류 == 보이는 종류)** 를 쓴다 → 그 종류의 장식·빛 기둥만 보인다 [확실].
  - 0x215BB0: 템플릿 종류 5 면 붙잡히지 않은 동안 (+0x190 &lt; 0) 타이머 +0x1EC −= 1/60, 0 아래면 = +0xA4 (#70f23994) 로 되돌리고 보이는 종류 (+0x1E4) = (지금 − 1) mod 3 + 2 (2→3→4→2) [확실].
  - 완료 오브 0x2161A8: 템플릿 종류가 2..5 면 i = (보이는 종류 − 2) mod 3, 아니면 0 → OrbEmitter = 템플릿 +0x90 + 4i (#b9a0d2ad / #4d7975bf / #4d7975c0) — HydraHealth = 014 "HealthChest" [확실].
    (그 앞 0x1D9278 / 0x1D92F0(#cb865a1c) 비교로 다른 템플릿 7 을 고르는 경로는 미해석.)
- 이미터: FXC 노드 헤더 +0x08 = 붙은 관절 (MSemit* 6 magic, HSemit* 7 health, PSemit* 8 power, WSemit* 9 plain), +0x0A = 링크 = **상태 디스크립터 번호** (40 §3 정정):
  chestGrab 은 디스크립터 3/6/9/12 (종류마다 첫 이미터, 덜컹일 때 반짝임), chestOpen 은 4·5/7·8/10·11 (둘째·셋째, 열릴 때 분출) 에 곡선이 있다 [확실: 데이터].
  숨은 관절에 붙은 이미터는 끈다 [추정: 원작 관절 가시성이 부착물에 미치는 경로 미확인].

**엔진** (`game/ChestProps`, `GameScene.listener().create`):
- Creator Name0 "chest" (대소문자 무시, `SceneNav.creatorIs`) → `SceneNav.create` 가 R2 아이템 (`Interactables.addChest`: 종류·오브 템플릿·순환 주기) 을 돌려주고, `ChestProps.spawn` 이 이펙트 라이브러리의 `chest` (R_PERMK, MagicFx 가 이미 등록) 를
  `SceneNav.creatorMatrix` (위 0x1A9CD0) 에 수명 무한으로 재생 — `Effect.jointMask` (종류 마스크), `emitterOff` (숨은 관절 이미터), `hold`. 키 = 소유 GO 이름 (만든 자리가 그 자리일 때),
  아니면 소유 GO 이름 + "/" + 엔티티 이름 (`creatorKey`, 묶음 상자).
- 충돌: CDV_chest (평면 hull 6 장, 반지름 20) 를 `Platforms.add` 로 정지 몸에 [추정: 원작 GO 생성이 CDV 마다 충돌 인스턴스를 올린다]. 손잡이 = 관절 handle1 (`Interactables.handles(it, sk, jw, …)`, §20),
  길이 = 상자 chestGrab (`lengthKnown`).
- act: 대기 chestParts 0 초 정지 → 과제 시간 > 0 이면 chestGrab 을 그 시간에 (놓으면 −4 되감기 그대로) → 완료 chestOpen 을 속도 1 로 재생, 끝 자세 유지 (뚜껑 열림). 완료 오브 = 종류별 OrbEmitter 이름 (`SceneNav.chestEmitter(it, tw)`).
- 확인: ATHN01B 시작 — 상자 gochest2 CSM 042 종류 2, 손잡이 (1566, −125, 1627) 축 +z. 녹색 기둥 상자가 보이고, R2 누르고 있기 (`-Dgow.script=LS:0.5:0.25:0:-1,LS:0.9:0.25:-1:0,LS:1.3:0.08:0:1,R2:1.6:3.4`)
  → chestGrab 덜컹 → chestOpen 녹색 분출·뚜껑 열림, "상황 동작 완료: CHEST gochest2" + HealthChest 오브. ATHN01D 도 gochest2/3 (042, 종류 2)·gochest4 (041 HydraWeapon, 종류 4) 생성.
- 시험 `game/ChestPropsTest`: 순환 2→3→4, ATHN01B HydraHealth 종류 2·오브 014 "HealthChest"·손잡이 1·길이 1.967·CDV 충돌·health/health2 만 보임·chestParts → chestOpen,
  chestGrab/chestOpen 이미터 곡선이 디스크립터 번호대로 (HSemit1 / HSemit2·3), ATHN01B 쓰러진 기둥이 부서지기 전엔 복도를 막고 뒤엔 통과 (41 §2.1).
- 남은 것: 같은 Creator 경로의 다른 이름 — ATHN01B `hydraHL-aoBHB1a01Hl23/24`·`aoHAR1a01Hl26/27` (소유 gohydrahl, Name1 = 인스턴스 이름, Animator 10 이 재생) 같은 "ao*" 애니 오브젝트와
  ~~R_SAVEPT 세이브 포인트 모델은 아직 만들지 않는다~~ → 세이브 포인트 몸 = **R_PERMK 오브젝트 `savepoint`** (MDL_SavePoint 빛기둥 SFXlightBeam·starBurst +
  ANM "SavePoint" 4 초 반복 + SEM_SNDSavePoint, GO 템플릿 gosavepoint) 를 상자와 같은 Creator 경로로 소유 GO 행렬에 만든다 [확실: 0x1ACCA8 · 데이터] — R_SAVEPT.WAD 는
  메모리카드 아이콘 텍스처 (TXR_SavePt01..41) 뿐이다. R2 안내·저장 거리 4 m = 64 단위 (0x1DC678 거리 × 1/16 < 4.0, 0x1DC3F8 → 아이콘 슬롯 0 = 14) (43 §16).
  열린 상자 상태는 이음으로 돌아올 때 넘기지 않는다.


## 22. 천장 질의 · 한쪽 면 바닥/턱 — ATHN01B 선창에서 2단 점프로 갑판 위 (사용자 보고) [확실: 디스어셈블 / 추정: 내비 위치 = 발 + 6.4]

**증상**: 배 갑판 전투 뒤 승강구로 ATHN01B 선창 (바닥 −134 ~ −164) 에 내려와 2단 점프하면 갑판 (ATHN01A, 0) 위로 올라갔다. 원작은 선창 천장에 머리가 막힌다.

**데이터** (`NavWorld`/`LevelCollision` 격자 조사): ATHN01A 갑판 = 윗면 y 0 (법선 +1, 0x2200001 계열) + 밑면 **y −64 (법선 −1)** 두 장, 승강구 구멍 ≈ x 1605–1650 · z 1600–1660.
ATHN01B 에도 선체 천장 (−44 ~ −64, 법선 −0.94 ~ −1) 과 빗면 들보 (법선 y −0.17 ~ −0.35) 가 있다. 2단 점프 정점 = 발 + 69 (244 → 다시 244, 중력 800) — 선창 바닥에서 머리 (+35.2) 가 −64 를 30 넘게 지난다.

**원인 (엔진)** 세 가지가 겹쳤다.
1. **천장 질의가 없었다** — 머리가 천장 면을 그대로 지났다.
2. **바닥이 양면** — `LevelCollision.floorAt` 가 |법선 y| ≥ 0.7 이면 아래를 보는 면도 바닥으로 봐서, 바닥 질의 (발 + 14 에서) 가 갑판 밑면 −64 를 찾아 슬래브 안에 세웠다.
   거기 서면 "다른 슬롯 레벨 바닥만 밟음" 으로 ATHN01A 로 넘어가고, 다음 점프 (−64 + 37 + 14 ≥ 0) 로 갑판 위.
3. **턱 윗면 선분이 양면** — 손 높이 ± d 수직 선분 (0x1E3A80) 이 |법선 y| > 0.85 를 받아 밑면 −64 를 턱으로 잡고 매달렸다 (재현 화면: 두 팔을 든 채 선창 천장에 매달림) → 올라서기.

**원작**
- 시트 면 선분 검사 **0x16BDE0** (시트 선분 캐스트 0x16BF98 의 면 단위): 면 평면 거리 시작 dS = n·s + w &lt; 0 이면 안 맞음, 끝 dE &gt; 0 이면 안 맞음,
  dS − dE &lt; 1e-4 이면 안 맞음 → **한쪽 면** (시작이 법선 쪽, 끝이 뒤). 평면은 로드 때 정점 감김으로 계산 — 엔진 (b − a) × (c − a) 와 같은 방향
  (갑판 윗면 +1 이 원작에서 밟히므로). 반대쪽에서 오는 선분은 그 면을 아예 못 본다 (가장 가까운 접촉 후보가 아님).
- 바닥 0x1E3298: 처음 맞은 면 법선 · 위 &gt; 0.1 (0x1E3568 `0x3DCCCCCD`) → 위를 보는 면만.
  - **가파른 비탈 (법선 y 0.1–0.7)** [확실: 임계값 / 근사: 선분 4–5 개 대신 가운데 하나]: 엔진 바닥 (`FLOOR_NY` 0.7) 은 이런 면을 바닥으로 안 봐서 구멍이 된다.
    ATHN02B 사이클롭스 바닥의 945 단 → 937 단 경사 띠 (x 32–48, z 2434–2466, 법선 y 0.696, 폭 8) 아래는 데스존 — 사이클롭스 공 휘두르기
    (CY2SHOOTOUT 15 타 넉백, bc258e3 부터) 에 밀려난 영웅이 돌아오다 띠를 밟고 떨어져 자동 플레이 (쉬움 200 초) 가 사이클롭스를 건너뛰었다.
    엔진: 지상 영웅이 일반 바닥을 2 넘게 잃으면 원작 선분 (발 + max(d − 6.4, 0) → 발 − (6.4 + d), d = max(변위, 3.2)) 안의 법선 y &gt; 0.1 정적 면
    (`NavWorld.steepGround`, `LevelCollision.QUERY_NY`) 위에 세운다 (`GameScene.physics`). 선분 위 끝이 발 높이라 가파른 면을 오르지는 않는다. 테스트 `SteepFloorTest`.
- **천장 0x1E36B0** (호출 0x1EA200 자유 이동 0x1EA3CC — 늘, 0x1EA5F0 지상·공중 0x1EAB70 — 수직 속도 +0xE0.y &gt; 0 이면 바닥 대신 (0x1EA8A4 – 0x1EA8BC),
  그 밖 0x1C5848 줄 · 0x1CA198 / 0x1CB308 물 · 0x210DC8):
  - d = |새 내비 y − 옛 +0x110 y|, L = min(+0x120.y − +0x130.y − 1, d + +0x130.y) = min(27.8, d + 6.4).
  - 시작 P0 = 새 위치 + 위 × (키 − base − L) (VU0 0x0812044 / 0x1010041 / 0x1E20858 / 0x1E118A8 = 발 + 35.2 − L), 끝 Q = P0 + 위 × (L + d + base) = 발 + 35.2 + d + 6.4.
  - 끝점 = Q 의 가운데 + 몸 상자 네 모서리 (오른쪽 ± 반폭 9.6, 정면 ± 깊이/2 9.6), 벽 (+0x174 &amp; 0x30) 이면 네 모서리만. 선분은 모두 P0 (가운데) 에서.
  - 마스크 0x3309F4 = 호출 마스크 (몸 질의 0x1E2100 과 같은 s2) | 0x1010042 | (+0x258 &amp; 0x4300000) — 바닥과 같다. 범주 0x3309F0 = ~(0x6B1FF3 | +0x254), 모드 2 (가장 가까운 접촉).
  - 접촉 법선 · 위 &lt; −0.1 (0x1E39A8 `0xBDCCCCCD`) 이고 (접촉 y − 키 + base) &lt; 새 내비 y 면 새 내비 y = 그 값 (= 발 = 접촉 y − 35.2), 1 반환하고 끝.
    아니면 다음 선분. 0x1E1DF0 (접촉 기록 콜백) 은 엔진에 없음.
  - **수직 속도는 그대로** — 두 호출자 모두 반환값을 버린다. 오르는 동안 머리가 천장에 붙어 있다가 속도가 0 아래로 내려가면 떨어진다.

**엔진**
- `NavWorld.ceiling` (위 규칙, 시트 면은 한쪽 면 `frontContact` + CDV hull 들어가는 면 `Platforms.segment`), `ceilingExclude` = 몸 마스크 | GeneralGuide.
  `GameScene.physics`: 공중 적분 뒤 수직 속도 &gt; 0 이거나 자유 이동 기술 (stateMask &amp; 0x1C = 0) 이면 호출, 키 35.2 · base 6.4 · 반폭 9.6 · 반깊이 9.6.
- `LevelCollision.floorAt`: 법선 y ≥ 0.7 인 면만 (아래를 보는 면은 바닥이 아님) — 영웅·적 바닥 (`NavWorld.floor`) 모두.
- `HeroNavigator.ledgeTop` = `NavWorld.frontSegment` (한쪽 면) + 법선 y &gt; 0.85 — 턱 (`probeLedge`) · 턱 모서리 (`probeLedgeCorner`) 윗면 선분.
- 나머지 엔진 선분 (벽·머리·이동 선분 `blockMove`·몸 구) 은 여전히 양면 [근사] — 벽 법선을 시작점 쪽으로 뒤집는 근사 (`facing`) 를 그대로 둠.
- 적 (`EnemyNav`) 의 천장 질의는 아직 없음 [미구현] — 적이 위로 뛰는 기술은 드물다. 바닥이 한쪽 면이 된 것은 적에게도 적용.
- `TraversalVerifier` 의 바닥 칸 (`Tri.floor()`, |법선 y|) 은 그대로.

**재현·증거** (`dev.sh --game ATHN01B --arrive ATHN01A@1620,-130,1686`, × 4.0 초 · 4.25 초):
- 전: 발 −138 → 정점 뒤 (1609, −79) 에서 밑면 −64 를 턱으로 잡고 매달림 (`@1640,-70,1696` 도착은 밑면 −64 위에 서 있다가 2단 점프로 갑판 0 착지).
- 후: 발 −138 → −99.2 (머리 −64) 에서 ≈ 0.2 초 붙어 있다가 (수직 속도 164 → 0) 떨어져 −140.2 착지. 승강구 구멍 밑 (1619, 1636) 2단 점프는 정점 −69 (갑판 0 에 못 닿음 — 전후 같음).
- 시험 `nav/HoldCeilingTest`: 합성 천장 (머리 맞춤·여유·위 보는 면·GeneralGuide), ATHN01B + ATHN01A 데이터 (밑면은 바닥·턱 윗면이 아님, 2단 점프 정점 = −64 − 35.2, 선창 바닥으로 착지).

## 23. 잡기 질의는 충돌 세계 · 이웃 슬롯 R1/R2 오브젝트 · TAndF 물리 들어 올림 (43 §18)

### 23.1 충돌이 꺼진 오브젝트는 잡히지 않는다 [확실: 코드 / 근사: 꺼진 부품이 질의에서 빠지는 것은 추론]
- 0x1DC3F8 → 0x1C3F18: 충돌 세계 (0x29C978) 구 질의 0x11E8D0 (반경 32, 종류 마스크 0x631800, 콜백 0x1C3918) — 찾은 GO 의 클래스 비트로
  PushPull (0x31800: +0x260 → +0x108 & 8) · TAndF (0x200000: +0x1C0 → +0x158 & 2) · CSM (0x400000: +0xB8 & 4).
- Vis 이펙터 0x1ABAA8 → 0x1B1398(대상, 값, 타입, 하위): 슬롯 2 = 0x27 (그림자), 3 = 0x11 하위 0 (Attr11_0), 4 = 0x11/1 (CMV), 5 = 0x11/3, 6 = 0x11/2,
  7 = 0x1A … — GO 부품 중 그 타입 (0x11 은 하위 번호까지 vtable 로 확인) 을 값 1 이면 켜고 (관리자 vtable +0x30) 2 면 끈다 (+0x40).
- 엔진: `Interactables.Item.noCollision` · `usable()` (`grabbable` · `nearest` · R2 안내), `SceneNav.setSolid` 가 몸 충돌과 함께 (`restorePlatforms` 도).
  데이터: 생성 때 끄고 퍼즐 뒤 켜는 짝 17 개 — ATHN04A trapDoor2 CDsOff/CDsOn, PAND00C UWSkullDoor·skullDoor·EyeDoor·ring3Door, PAND04EE 밀기 상자 다섯
  (크랭크 LevelData1), PAND02D caveLever04DD, PAND03F NewCSM, PAND00B NewBook, PAND01BB/03H ArtSonMain, ATHN01E XFCcaptainCSM (그 R2 는 스크립트 1024 볼륨) …
  모두 "충돌 끔 = 아직 못 씀" 으로 읽힌다 (Visible 2 를 함께 쓰는 짝은 예전에도 hidden 으로 막혔다).
- ATHN04A 해치: R2 → UseWorldWithKey (GlobalData DoorKey 참, LevelData8 거짓) → 1 초 뒤 질문 1007 "열쇠를 사용하시겠습니까?" → 예 (MenuYes) →
  LockFlipOpen 애니 + CDsOn (Delay 2: Idle 풀기 · CMV 질의 켬) → 그 뒤 R2 로 잡기 (HatchAttach 1048, TnFcam) → R2 연타 → 100 % → EvtHatchOpen.
  예전엔 첫 R2 에 해치를 잡고 1 초 뒤 질문이 겹쳐 떴다.

### 23.2 이웃 슬롯 오브젝트 [확실: 질의 / 근사: 엔진 구조]
- 원작은 두 슬롯 GO 가 같은 충돌 세계에 있어 "현재 레벨" 과 무관하게 이웃 레벨의 레버·문도 잡힌다. ATHN04A 해치로 떨어진 아래층은 ATHN04C
  LoadCheck4CEntity 볼륨 (z 1224–1336) 이라 창살 앞은 현재 = 04C 인데, 창살 LiftDoor13 (TAndF 400) 과 세이브 포인트 SaveGameZone 은 ATHN04A 것이다
  (영상 6450–6462). 엔진 (예전): R1/R2 오브젝트·Creator 는 현재 레벨 것만, 이웃 레벨 행동 GO 는 몸에서도 빠져 (`Platforms.staticOwner`) 닫힌 창살을
  걸어서 지나가고 R2 도 먹지 않았다.
- 엔진: `SceneNav.setOther` 가 이웃 `Interactables` (그 레벨 TWK = 영구 + 레벨, `GameScene.neighbourTweaks`) 를 만들고 그 몸을 이웃 `Platforms` 에
  (자세·충돌), `HeroNavigator.setNeighbourItems` — 현재 레벨 것이 손에 없을 때 이웃 것을 잡는다. 이벤트 (1023 · 1048 · 1049) 는 공유 큐로 이웃 센서에,
  밀기 이동은 이웃 세계 (`moveOwnerBy`), 자세는 `poseItems(이웃 것, 이웃 몸, 이웃 세계)`, 이웃 Vis 의 Visible · Attr11_0 → 그 오브젝트 hidden · noCollision.
  붙잡는 레버·밀기 상자는 두 슬롯 모두 영웅 몸 충돌에서 뺀다 (`NavWorld.collideBodies`).
- 이웃 Creator "savepoint" 도 이 씬에 만든다 (`SceneNav.Host.neighbourCreate` → 자리 + 빛기둥, R2 저장). 이웃 상자 ("chest") 는 아직 현재 레벨 것만 [근사].
- 이음 상태: `restoreNeighbourPlatforms` / `restorePlatforms` → 충돌 꺼진 몸 = noCollision, 열린 자세·높이의 TAndF = 끝남 (`SceneNav.restoreItems`).

### 23.3 TAndF 물리 들어 올림 (0x214058 / 0x214180) [확실: 코드 / 근사: 영웅 관절 높이 대신 진행 비율]
- 0x213B28: 상태 0/1/2 마다 템플릿 +0xD4 / +0xEC / +0x104 이름 (#d7f2bea8 연타 · #c5b0169f 완료 …) 의 act 를 문 GO 애니에서 찾아 (0x116AC8) 튼다 — 없으면 (−1)
  틀지 않는다. 0x214400 은 그 애니 시간 = (0x100BF0: 재생 중 act 길이 최댓값) × 위치 (+0x1E4).
- 문 ANM 에 그 act 가 없으면 (ATHN04A LiftDoor13 "LiftDoor13" · ATHN01C LiftDoor1 "LiftDoor1" — 길이 0 act 하나) 애니로는 안 움직이고: 잡은 동안 0x214058 이
  문 Y = 영웅 붙잡기 관절 (영웅 클래스 +0xEA) 의 월드 Y, 끝 (+0x1E4 = 2) 이면 0x214180 이 중력 (/GlobGame/Gravity 50 × 16, 속도 +0xE0) 으로 처음 Y (+0x1E8) +
  템플릿 +0x11C (#8743c048, 로더 0x213840) × 16 까지 올려 멈추고 (+0x1EC |= 0x40), 놓으면 같은 중력으로 처음 Y 까지 내린다 (|= 0x20).
- 엔진: `Interactables.Item.liftHeight` (TAndF 400 "Athens3ALiftGat" 5.0 → 80), `Platforms.lift` (몸 follow = 위로 이동 → 렌더·충돌·Marker), `SceneNav.physicalLift`
  → 높이 = 위치 × liftHeight, 끝 = liftHeight, `Platforms.State.lift` 로 이음에 넘김. 해치 trapDoor2 (trapOpen 0.6 초 act 있음) 는 예전처럼 애니.
- 검증: r12 (ATHN04C 에서 04A 창살) 연타에 따라 창살이 올라가고 크레토스가 지나간다 (영상 6460–6464). 시험 `AthensInteriorTest.liftGateRisesByTheTemplateHeight` ·
  `hatchIsNotGrabbableUntilTheKeyTurnsCollisionOn`.
- ~~[근사] 영웅 관절 높이 대신 진행 비율~~ → §23.4 (원작 위치 모드).

### 23.4 위치 모드 미끄럼 문 = 영웅 synchJoint 를 따라 (43 §19) [확실: 디스어셈블 / 근사: 표시한 것]
- 이 갈래는 템플릿 +0x158 플래그 (#a6e81c1e, 로더 0x213830) **비트 0** 인 TAndF 만 탄다 (0x214470 · 0x2145B8 · 0x21491C) — ATHN04A LiftDoor13 (400) ·
  ATHN04C "Slow Lift Gate" LiftDoor13 (7) · ATHN01C LiftDoor1 (50) 은 1, 해치 trapDoor2 (6) 는 0 (오브젝트 애니). `Interactables.Item.slide`.
- 붙어 있고 위치 < 2 (완료 프레임 포함): 0x214058 이 문 GO y = 붙은 순간 y (+0x1E8, 0x2145A4) + **영웅 모델 인스턴스 (+0x104 → +0x50 행렬) 의 관절 +0xEA 의 모델 공간 y**,
  속도 +0xE0 = Δy/dt. 관절 +0xEA = 이름 "synchJoint" (0x1F7D28 이 영웅 생성 때 찾음). 영웅 이동 0x60 ANITandF2 는 **매개변수 = min(문 위치, 1)** 로 세운다:
  0x1ECA44 → 0x1F77B4 가 애니 시간 = 매개변수 × 길이, 재생 속도 0 → 영웅 liftDoor2 의 synchJoint 0 → 33.8 (5.467 초 클립), liftDoor3 끝 54.5.
  → 문은 손과 같이 오르고 연타가 늦으면 같이 내려온다.
- 완료 (위치 2): 0x214180 이 중력 (/GlobGame/Gravity 50 × 16 = 800 /s²) 으로 처음 + 높이 (#8743c048 × 16 = 80) 까지 가속해 올라 멈춤 — 다 든 순간 33.8 → 0.35 초 뒤 80.
  완료 전에 놓으면 위치·속도를 곧바로 0 (0x2142B8) 으로 하고 문은 같은 중력으로 처음 자리까지 떨어진다.
- 스크립트 1015 (IO_TandF 메시지 0x266420 → 0x213E30): 위치 0 · 완료 해제 → 다 오른 문이 떨어지고 다시 잡힌다. ATHN04C 는 문을 지나 DoorVolume 을 나가면
  (LevelData0 거짓) LiftDoor13-EventTransmitter1 이 1015 를 보낸다 — 예전 엔진은 LIFT 에 1015 를 안 받아 문이 열린 채였다.
- 엔진: `HeroNavigator.slideFollow` (`NavBody.jointModelY("synchJoint")` = `Animator` 자세의 모델 공간 y) · `NavBody.scrub` (`Animator.scrub`: 시간 고정, update 가 진행 안 함) ·
  `Item.slideIdle` (중력 오름/떨어짐) · `scriptReset` LIFT 갈래, `SceneNav.poseItems` 가 `Platforms.lift(owner, slideY)` 로 몸·충돌·렌더를 옮긴다.
  `Platforms` 는 높이 0 에서도 follow 행렬 (단위) 을 남겨 떨어져 닫힌 문도 그 자리로 다시 그린다 (예전엔 닫힌 문이 들린 자세로 남았다).
  이음 상태 `restoreItems`: 넘겨받은 높이 그대로 (리셋이면 거기서 떨어진다).
- 시험 `SlideDoorTest` (5): 붙은 동안 문 y = synchJoint (ANITandF2 = 위치 × 길이), 완료 뒤 0.35 초에 80, 놓으면 √(2h/800) 에 처음 자리, 1015 리셋, 데이터 (위 넷),
  R_HERO0 synchJoint 0 → 33.8.
