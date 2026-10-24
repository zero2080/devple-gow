# 41. 구현: 부서지는 물체 (Breakable / Brkb)

## 1. 데이터 [확실]
- GO 비헤이비어 리소스(0x17) 클래스 `"Breakable"` (`GoBehavior`, 0x24 바이트) → 템플릿 번호 = `/TweakTemplates/Brkb/<n>/`.
  - ARENA06 항아리 `gopotlgdirtyabrk*` 36 개: Brkb 000 "No Orbs", `Hit Points` ≈ 1 (덤프 0.9999991 — 하위 비트 잡음, 타수로 반올림), `Fade Time` 3.
  - 그 밖: R_PERMK Brkb 500 "MAI StoneStatue"(석화 석상, 35 문서), ATHN01B/02A/02C/03A Brkb 910 "HydraBreakSmall" 등 레벨별 템플릿.
- 각 물체 GO 에는 작은 `ESC_<오브젝트>` 가 붙어 **Marker(Id 101)** 를 만든다. 스크립트는 이 오브젝트들을 그룹 101 로 센다:
  ARENA06 `TimerControl-DestructionSensor1` (mode 3, Filter 101) → LevelData0 += 1 → 36 이면 `SuccessTransmitter` → 메시지 2008 → 1040 → Warp.
  ATHN03A 철문 `SwingMetalGate*-DestructionSensor` 도 Filter 101.
- 파편: 물체 오브젝트 자체가 **이펙트 GO 와 같은 구성**이다 — `ANM_<이름>` (파편이 흩어지는 act 1개), `MDL_<이름>` (온전한 몸 + 조각 관절),
  `FXC_/PTC_` 먼지 (ATHN03A 통·양동이: breakemitter10 + gravityField + DURNemitter13), `SEM_` 소리 [확실: 그룹 참조].

| 물체 (레벨) | Brkb | 관절 | 파괴 act | SEM 슬롯 1 |
|---|---|---|---|---|
| potlgdirtyabrk8..43 (ARENA06) | 000 "No Orbs" | 12 (pasted__shard10..18) | 1.667초 | SEM_SNDPotBreak = SND_POTTERYSMASH |
| breakablewooddoor1..7 (ATHN03A) | 956 "Breakable5" HP 8 | 2 (doorVis) | 2.1초 (byte·obj 트랙) | SEM_SNDDoorSmash |
| swingmetalgate00..05 (ATHN03A) | 002 "default no phys" HP 2 | 3 | 0.6초 | SEM_SNDGateBash |
| breakbarrel2 / breakbucket017 / breakpallete1 (ATHN03A) | 910 "HydraBreakSmall" | 10–19 조각 | 2.0–2.67초 | SEM_breakSNDBreakable* |

### 1.1 Brkb 템플릿 (생성자 0x1DD768, 0x3C 바이트) [확실: 키·오프셋·형]
| +off | 키 | 형 | 000 / 002 / 910 / 956 |
|---|---|---|---|
| 0x18 | Type | s16 (열거, 표 0x337B60) | 0 / 0 / 0 / 0 (R_PERMK 500 석상 = 2) |
| 0x1A | #cb865a1c | s16 | 0 |
| 0x1C | Hit Points | f32 | 1 / 2 / 1 / 8 (912 HydraBreakBig 25) |
| 0x20 | #c3441d8c | f32 | 3 |
| 0x24 | Fade Time | f32 | 3 / 3 / 1 / 1 |
| 0x28 | #b9a0d2ad = **OrbEmitter 템플릿** | s16 | −1 / −1 / **900** "small obj break" / −1 (912 → 902 "large obj break") |
| 0x2A | #49322803 = 다른 경로 OrbEmitter (0x1DDDF0 a1 ≠ 0) | s16 | −1 |
| 0x2C / 0x2E | #350a54bc / #c49baa12 = Bonus 템플릿 (0x29DC28 "Bonus" 루트 → 0x1D4DB8) | s16 | −1 |
| 0x30 | bit 0x1 #93191d85 (파편 물리 객체 0xC10, 0x1DE218) / bit 0x2 Force Feedback (0x1DE5C4 진동) | 비트 | 1·1 / 0·1 / 0·1 / 0·1 |

덤프의 원시값(예 Type 0x72640000) 은 s16 필드의 상위 2바이트가 잡음 — 하위 16비트만 읽는다. "No Orbs" 인스턴스 이름이 +0x28 = −1 과 맞고,
910 "HydraBreakSmall" 의 0x0384 = 900, 912 "HydraBreakBig" 0x0386 = 902 가 OrbEmitter 900–902 ("small/med/large obj break", ATHN01B–04D) 와 맞는다.

### 1.2 판정·파괴 흐름 [확실: 코드]
- 접촉 (0x203AF0 → 0x1DE4D8): 공격자 쪽 공격 레코드 (sub_00209AB0) 가 있으면 `hp −= (s8) 공격 +0x19` (피해 바이트) → 0x1DDDF0.
  공격 없이 부딪힌 물체 속력 &gt; 기준이면 1000 피해.
- 0x1DDDF0: hp ≤ 0 → 이벤트 1009 (0x3F1), OrbEmitter (+0x28) 가 ≥ 0 이면 OrbEmitter 템플릿(루트 0x2A2670) → sub_002258D8 (GO 위치에서 오브 방출), Bonus (+0x2C).
- 0x1DDF70 (파괴 상태 진입): GO 애니 컴포넌트를 0초부터 재생 (sub_00100578), 이벤트 1018 (0x3FA), +0x30 bit 0x1 이면 파편 물리 객체.
- 0x1DE370 (애니 끝): Type ≤ 0 이면 GO 플래그 |= 3 (숨김/비활성 추정), Type 3 은 재부착 경로.
- 0x1DE278 (파괴 뒤 매 프레임): 경과 +0x24 += 1/60. 파편 객체가 있으면 0x1DEBA8. 경과가 [#c3441d8c (+0x20, 3초), +0x20 + Fade Time] 이면
  0x1DE6B8(1 − (t − 지연)/Fade) (0.01 미만이면 다른 렌더 모드 — 페이드), 지연을 처음 넘을 때 0x1D9700(플레이어, #cb865a1c, 0) 1번, 지연 + Fade 를 넘으면 1 (제거) [확실: 시점].

### 1.3 byte 트랙 = 관절 가시성 [확실: 데이터 / 추정: 의미·계층]
ANM **타입 5** (원소 크기 1, 05 문서 §2.8 — 예전 문서의 "타입 10 원소 1" 은 디스크립터 배치 착시, 05 §2.8.1) 의 12B 엔트리 `{u16 관절, u8 flags, u8 offHi, u16 count, u16 start, u16 hold, u16 offLo}` + u8 값 (1/60 초 프레임):

| 물체 | 트랙 | 메시 |
|---|---|---|
| breakableWoodDoor1..7 | doorVis (1) = [1,1,0] hold 124 (2.1초) | 파트 하나가 관절 1 에만 스킨 (온전한 문) → 2 프레임 뒤 사라지고 먼지 FXC 만 |
| breakBarrel2 | barrelVisDisapear (1) = [1,1,0] hold 0 | 관절 1 = 온전한 통 몸 (파트 p1), 자식 barrelWood19–26 + 몸 혼합 파트 p0, barrelBisShards (10) 아래 11–18 = 날아가는 조각 (p2) |
| breakbucket017 / BreakBucketFull02 | breakgroup1 (1) = [1,1,0], breakgroup2 (2) = [0,0,1], group3 (11) = [1,1,0] | 관절 1 = 온전한 양동이, 2 의 자식 3–10 = 조각 |
| 항아리 (ARENA06 PotLgDirtyABrk*, 세트 [0(3), 5, 11]) | pasted__PotLgDirtyA1 = [1,1,0], pasted__PotLgDirtyABrea (조각 묶음) = [0,0,1] | 온전한 항아리 → 2 프레임 뒤 조각만. 예전 해석은 디스크립터를 항목 수 (3) 만큼만 읽어 이 트랙을 놓쳐 온전한 몸과 조각을 함께 그렸다 (2026-10-24, 43 §20) |
| breakPallete1, SwingMetalGate | 없음 | 조각 관절만 애니 |

값 0 = 그 관절과 자손 숨김, 트랙이 끝난 뒤 (hold 0) 에도 마지막 값 유지, 시작 전은 보임 [추정: 원작 소비 코드 미발견 — 객체 +0x64 관절 비트는 행렬 캐시 유효 비트 (0x136B18 초기화)라 아님].
양동이의 [0,0,1] (조각은 처음 2 프레임 숨김) 와 통의 조각 묶음이 몸과 다른 가지에 있는 것이 계층 해석과 맞는다.

### 1.4 파편 물리 객체 (bit 0x1, 0x1DE218 → 0x1DD638(0xC10) → 0x1DEA28, 갱신 0x1DEBA8) [확실: 식]
- 0xC10 바이트 = 0x30 바이트 슬롯 64 개 + 개수 (+0xC00). 루트의 첫 자식부터 **자식 없는 관절**마다 슬롯 {+0x0 위치 = 현재 관절 월드 위치, +0x10 속도 0, +0x20 관절 행렬, +0x24 관절, +0x28 9.82 (미사용), +0x2C 플래그 1 = 애니 따름}.
- 애니 따름: A = 애니 관절 월드 위치, V = (A − P)·60. 선분 P + (0,1.6,0) → A − (0,16,0) (0x11E190) 이 부딪히면 그 y 가 루트 관절 y 와 1.6 넘게 다를 때,
  안 부딪히면 A.y &lt; 루트 y − 1.6 일 때 플래그를 끈다. 아니면 P = A.
- 물리: V.y −= 640/60, P' = P + V/60. 선분 P + (0,1.6,0) → P' − (0,1.6,0) 충돌 시 V ×= 0.25, V −= 2(V·N)N (N = 충돌 법선 0x2C8F20), P' = P + V/60.
  관절 행렬 = 부모 역행렬 × (애니 회전 행 + 위치 P') — 조각은 애니대로 돌면서 위치만 시뮬레이션.
- 경계 반지름 = 루트에서 가장 먼 조각 + 32 (0x1DE928).
- 즉 받침 바닥 위에 그대로 떨어지는 조각은 애니를 따르고, 계단·비탈·턱 (조각 아래 16 안의 바닥 높이가 루트와 다름) 에서만 물리로 굴러 떨어진다.
- Brkb 000 (항아리) 만 bit 0x1 = 1 (#93191d85 하위 바이트), 910/956/002 는 0.

## 2. 엔진 (`game/Breakables`)
- 레벨 적재 시 Breakable GO 마다 중심 = 인스턴스 경계 중심, 반지름 = `CDV_<오브젝트>` 공(없으면 24), 타수 = Brkb Hit Points.
- 매 프레임 `Combatant.strikes(중심, 반지름)`: 진행 중 공격(피해 > 0 창)의 부위 히트박스 구(34 문서) 와 겹치면 hp −= 공격 피해 바이트 (최소 1) (같은 기술·공격 번호는 한 번) [확실: 0x1DE5A0].
- hp ≤ 0 → 렌더에서 숨김(`LevelScene.hideInstance`) + 그 GO 의 Marker 오브젝트 파괴 → DestructionSensor 발동.
- 파괴 연출 (`GameScene.onBreak` → `MagicFx.breakObject(이름, 행렬, 지연, Fade, 물리)`): 레벨 WAD 를 이펙트 라이브러리에 등록해 두고 (`MagicFx.addWad(level.assets())`), 물체 GO 이름
  그대로 이펙트로 재생 — 인스턴스 행렬(`GoInstance.matrix()` 전치) 에 오브젝트 ANM 파괴 act + 메시(조각 관절) + FXC 먼지 + SEM 소리 (§4.4 of 40).
  ~~act 가 끝나면 Fade Time 동안 흐리게~~ → 파괴 뒤 #c3441d8c (`Item.fadeDelay`, 3초) 부터 Fade Time 동안 불투명도 1→0, 그 뒤 제거 (§1.2) [확실: 시점, 추정: 페이드 = 불투명도].
- u8 가시성 (§1.3): `Animation.hiddenJoints(sk, t)` → `AnimatedModel.hiddenJoints` (파트가 쓰는 관절이 모두 숨김이면 그 파트를 그리지 않음), `EffectSystem.Effect.jointVisibility` (부서지는 물체만 켬). `AnimViewerScene` 도 적용.
- 파편 물리 (§1.4): `fx/BreakDebris` — `Item.debrisPhysics` 면 `Effect.debris` 가 tick 마다 애니 관절 월드를 받아 조각 관절 행렬을 바꾼다. 충돌 = `MagicFx.Surface.cast` (레벨 충돌), dt = 프레임 (원작 1/60).
- 오브: Brkb +0x28 OrbEmitter 번호 → `OrbDrops.rollNode("/TweakTemplates/OrbEmitter/900/")` → 즉시 획득 (다른 오브 드롭과 같은 근사). 900: Normal Weapon Prob 1, Weapon Template 50.
- [미구현] Bonus 템플릿, 마법·발차기·물체 충돌 판정, Type 2/3, 지연 시점의 0x1D9700(#cb865a1c), 0x1DE6B8 의 정확한 렌더 모드.
- 검증 `BreakDebrisTest`: 통·양동이·나무문 가시성 표 (몸 숨김·조각 보임·트랙 뒤 유지), 항아리 조각 9+ 개 — 루트 높이 바닥에서는 애니 그대로, 10 아래 바닥에서는 물리로 떨어져 바닥 위에 멈춤.
  스크린샷: `--anim gow-rom/ATHN03A.WAD MDL_breakBarrel2 breakbarrel2 ANM_breakbarrel2` 50 프레임 = 몸 사라지고 널빤지만, ARENA06 항아리 □ 2회 = 바닥 조각.
- 검증 `BreakablesTest`: ARENA06 36 개 로드, 영웅 □ 로 항아리 파괴 → Marker 101 → LevelData0 ≥ 1.
  `EffectTest.breakableObjects`: 항아리 GO = 1회성 1.667초 + SND_POTTERYSMASH, Brkb 000 OrbEmitter −1·Fade 3, ATHN03A 통 900 → 붉은 오브, 나무문 HP 8.
- 스크린샷 `fx2_pots.png` (`--game ARENA06 --entry gopotlgdirtyabrk20`, □ 3회): 깨진 항아리 밑동과 흩어진 조각.
- 하네스(36 문서 §7): 걷기 뒤 Breakable 을 모두 파괴(플레이어 대신) → ARENA06 도 강제 없이 ARENA09 로.

### 2.1 부서지기 전에는 막는다 (ATHN01B 선창) [확실: 데이터 / 추정: 원작 충돌 경로]
사용자 보고 (PS2 영상 대조): 선창 복도를 막은 나무 잔해를 원작은 블레이드로 부숴야 (붉은 오브) 지나가는데, 엔진은 그냥 통과했다.
- 원인: `Platforms.staticOwner` 가 **비헤이비어가 붙은 GO 를 모두** 정지 충돌에서 뺐다 (상자·밀기·크랭크는 각자 시스템이 맡는다는 규칙) — Breakable 은 충돌을 맡는 시스템이 없어 몸이 통째로 빠졌다.
  원작은 GO 를 만들 때 CDV 리소스마다 충돌 인스턴스를 올리고 (38 §4, 0x138E70 → 0x138FD0), Breakable 비헤이비어 코드 (0x1DDDF0 / 0x1DDF70 / 0x1DE278) 는 그것을 건드리지 않는다.
  파괴 애니가 끝나면 0x1DE370 이 GO 플래그 |= 3 (숨김·비활성) [추정: 이때 충돌도 빠짐].
- ATHN01B 데이터: `gofallenposts` (Brkb 912 HydraBreakBig HP 25 → OrbEmitter 902 "large obj break", CDV_fallenPosts 평면 hull 6 장 관절 2, 경계 구 95) 가 복도를 z ≈ 1400 에서 막는다.
  복도 폭 = 정지 충돌 바닥 x 1590–1636 (z 1340–1520 같음, 양옆 벽 x ≈ 1588 / 1638, 바닥 y −143 → −136 로 기욺) [확실: 시트 질의].
  `gobreakablebunk01–08` (Brkb 910 HP 1, hull 6 장 관절 2, 반지름 29–33) 은 침상, `gobarrel*` 은 공 CDV (평면 hull 없음 → 정지 몸 아님).
- 엔진: Breakable GO 도 정지 몸 (`staticOwner` 가 Breakable 은 남김), 부서지면 `GameScene.onBreak` → `Platforms.setSolid(owner, false)` [근사: 원작은 애니 끝 (0x1DE370) 에서 빠짐 — 엔진은 부서진 순간].
  꺼진 상태는 `Platforms.states()` 로 이음에 넘어간다. 판정 구: 평면 hull CDV 는 반지름 = 경계 구 (`BallHull.sphere`), 중심 = hull 관절 월드의 hull 중심 (경계 중심과 침상은 15 어긋남) [근사: 원작은 hull].
- **hull 위치 정정 (자동 진행 봇이 x 1598 로 그냥 지나감)**: hull 관절 cdHull (2) 이 시작 act `fallenPosts` 자세에서 x 1659.8 (바인드 1585.0) 에 놓여
  hull 이 x 1627–1723 = 복도 동쪽 벽 바깥에 있었다 (렌더 메시는 관절 1 group1238 에 스킨, x 1569–1674). 원인은 act 의 **타입 11 트랙** (스트림 8 = 관절 2 x,
  0 → 1000 → 0 펄스, 세트 데이터 타입 [0, 5, 10×6, 11]) 을 관절 이동으로 읽은 것 — 이동 트랙 (타입 5) 이 비어 있으면 타입 11 로 대신하던 규칙 (38 §5, ATHN05D 엘리베이터).
  **정정 (2026-10-24, 05 §2.8.1)**: 그 "타입 11" 도 "타입 5 이동" 도 항목 번호 = 디스크립터 번호로 읽은 착시다. 원작 배치 (Σ p2) 에서 관절 이동은 늘 타입 0 의 디스크립터 1 이고
  타입 11 은 값 채널이라 이동으로 쓰이지 않는다 — 아래 결론 (cdHull = 바인드) 은 그대로, 규칙 (`declaresTranslation` / `objectTranslation()`) 은 없앴다.
  - 원작은 데이터 타입 번호마다 다른 채널 소비자를 만든다: 0x100EA8 이 세트 데이터 타입을 돌며 점프표 0x2B2810 으로 갈라 5 → vtable 0x2C0730 (샘플러 0x2C2278),
    11 → 0x2C0500 (샘플러 0x2C4448 → 재생 0x2C50C8/0x2C5110, 평가 0x269ED8 = 대상 포인터 배열 +0x5C 에 쓰는 범용 f32 채널) [확실: 코드 / 미해석: 대상 결정 0x262360].
  - 타입 5 를 선언한 세트에서 이동 트랙 없이 타입 11 만 가진 act 100 개 (전 WAD) 는 모두 스트림 8 하나에 0 → 500/1000/2000/600 → 0 (부서지는 문·모래주머니·상자·병풍, SEM 소리 이미터 보유)
    또는 0.22 같은 FX 값이고, 바인드 로컬과 맞는 것이 없다. 반대로 타입 5 가 없는 [0, 11, …] 세트 (엘리베이터·breakPallete·breakPlank·TIT) 의 타입 11 절대값은 바인드 로컬과 같다 [확실: 데이터].
  - ~~엔진: 타입 11 은 세트가 타입 5 를 선언하지 않을 때만 관절 이동 (`Animation.declaresTranslation` / `objectTranslation()`)~~ → 원작 배치에서는 이동 = 디스크립터 1 뿐 (05 §2.8.1).
    "타입 5 가 없는 [0, 11, …] 세트의 타입 11 절대값 = 바인드 로컬" 은 그 자리가 실제로 디스크립터 1 (이동) 이었기 때문이다.
    → cdHull 바인드 x 1585.0, hull 월드 x 1552–1648 · y −160–−32 · z 1351–1455 가 복도 전폭 (1590–1636) 과 렌더 메시를 덮는다. 같은 유형 (ATHN02C 모래주머니·상자, ATHN03–04 부서지는 문 등) 의 hull 도 바인드로 돌아간다.
- 확인: 자동 진행 봇 (`-Dgow.auto=1`) 이 x 1598 로 걷다 z 1468 에서 멈춤 (이전: z 1317 까지 통과) → 걷기 한도 뒤 순간이동.
  `-Dgow.script=LS:0:5:0:1,SQUARE…(5–11초),LS:12:5:0:1` — 기둥 앞에서 멈춤 → □ 연타 뒤 "파괴: gofallenposts 오브 10" (기둥 파편·먼지·붉은 오브) → 통과.
  시험 `ChestPropsTest.athn01bBlockersSolidUntilBroken` (cdHull x = 바인드, 복도 전폭 x 1596–1628 을 4 간격으로 훑기: 부서지기 전 z &gt; 1440 에서 밀림, 끈 뒤 끝까지 안 밀림).

## 3. IO_TandF 들어 올리는 문·함정문 (`Interactables.Kind.LIFT`)
- GO 비헤이비어 `IO_TandF` 37 개 (ATHN01C goliftdoor1/2 "HydraLiftDoor", ATHN05BB 압력 문 4, DEST02, PAND00C/00D/01B/02D/03A–C …).
  템플릿 `/TweakTemplates/TAndF/<n>/` (25 개): `Acc` 1, `Dec` 20, `TF Time` 0.25, #fe467fe6 1.5, 영웅 애니 liftdoor1(잡기)/liftdoor2(들기)/liftdoor3,
  소리 SND_LIFTDOOR_GRAB / _LIFT_LP / _STOP, `Reset Sound`. 진행률 이벤트는 IO_CSM 과 같은 코드(0x213F90 → 1023, 인자 1 = id) [확실: 키·값 / 추정: 의미].
- 엔진: ~~마지막 입력 뒤 TF Time × 2 안이면 "붙음" [근사]~~ → 38 문서 §3.4 원작 식 (0x214400): 누를 때마다 속도 +Acc (최대 #fe467fe6), TF Time 동안 안 누르면 −Dec·dt (최대 −#b503fbe4), 위치 += 속도·dt/4 [확실].
  검증 하네스만 예전처럼 길이 = #fe467fe6 (1.5초), 속도 Acc 로 끝까지 돌린다.
- 검증 `NavDataTest.liftDoorTask` (ATHN01C goliftdoor1: id 200, Acc 1 / Dec 20, 놓으면 1/60 초에 20/60 내려감). 하네스는 CSM 과 같이 진행 (주 경로 강제 없음 유지).
