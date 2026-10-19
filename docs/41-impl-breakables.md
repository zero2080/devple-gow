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

## 2. 엔진 (`game/Breakables`)
- 레벨 적재 시 Breakable GO 마다 중심 = 인스턴스 경계 중심, 반지름 = `CDV_<오브젝트>` 공(없으면 24), 타수 = Brkb Hit Points.
- 매 프레임 `Combatant.strikes(중심, 반지름)`: 진행 중 공격(피해 > 0 창)의 부위 히트박스 구(34 문서) 와 겹치면 hp −= 공격 피해 바이트 (최소 1) (같은 기술·공격 번호는 한 번) [확실: 0x1DE5A0].
- hp ≤ 0 → 렌더에서 숨김(`LevelScene.hideInstance`) + 그 GO 의 Marker 오브젝트 파괴 → DestructionSensor 발동.
- 파괴 연출 (`GameScene.onBreak` → `MagicFx.breakObject`): 레벨 WAD 를 이펙트 라이브러리에 등록해 두고 (`MagicFx.addWad(level.assets())`), 물체 GO 이름
  그대로 이펙트로 재생 — 인스턴스 행렬(`GoInstance.matrix()` 전치) 에 오브젝트 ANM 파괴 act + 메시(조각 관절) + FXC 먼지 + SEM 소리 (§4.4 of 40).
  act 가 끝나면 Fade Time 동안 마지막 포즈를 흐리게 하고 지운다 [추정: Fade Time 사용처 미확인, 원작은 0x1DE370 에서 숨김].
- 오브: Brkb +0x28 OrbEmitter 번호 → `OrbDrops.rollNode("/TweakTemplates/OrbEmitter/900/")` → 즉시 획득 (다른 오브 드롭과 같은 근사). 900: Normal Weapon Prob 1, Weapon Template 50.
- [미구현] byte 트랙 가시성 (doorVis, barrelVisDisapear: 조각 숨김), 파편 물리 (bit 0x1), Bonus 템플릿, 마법·발차기·물체 충돌 판정, Type 2/3.
- 검증 `BreakablesTest`: ARENA06 36 개 로드, 영웅 □ 로 항아리 파괴 → Marker 101 → LevelData0 ≥ 1.
  `EffectTest.breakableObjects`: 항아리 GO = 1회성 1.667초 + SND_POTTERYSMASH, Brkb 000 OrbEmitter −1·Fade 3, ATHN03A 통 900 → 붉은 오브, 나무문 HP 8.
- 스크린샷 `fx2_pots.png` (`--game ARENA06 --entry gopotlgdirtyabrk20`, □ 3회): 깨진 항아리 밑동과 흩어진 조각.
- 하네스(36 문서 §7): 걷기 뒤 Breakable 을 모두 파괴(플레이어 대신) → ARENA06 도 강제 없이 ARENA09 로.

## 3. IO_TandF 들어 올리는 문·함정문 (`Interactables.Kind.LIFT`)
- GO 비헤이비어 `IO_TandF` 37 개 (ATHN01C goliftdoor1/2 "HydraLiftDoor", ATHN05BB 압력 문 4, DEST02, PAND00C/00D/01B/02D/03A–C …).
  템플릿 `/TweakTemplates/TAndF/<n>/` (25 개): `Acc` 1, `Dec` 20, `TF Time` 0.25, #fe467fe6 1.5, 영웅 애니 liftdoor1(잡기)/liftdoor2(들기)/liftdoor3,
  소리 SND_LIFTDOOR_GRAB / _LIFT_LP / _STOP, `Reset Sound`. 진행률 이벤트는 IO_CSM 과 같은 코드(0x213F90 → 1023, 인자 1 = id) [확실: 키·값 / 추정: 의미].
- 엔진: R1 로 잡고 연타 — 마지막 입력 뒤 TF Time × 2 안이면 "붙음" → 진행 속도 +Acc, 아니면 −Dec 로 빠르게 내려온다 (`TaskProgress` 속도 지정).
  문 ANM 은 길이 0 (오브젝트 트랙만) 이라 들어 올리는 시간 = #fe467fe6 (1.5초) [추정]. 생성자 0x2135D0 의 키→오프셋, 갱신 함수의 정확한 식은 미해석.
- 검증 `NavDataTest.liftDoorTask` (ATHN01C goliftdoor1: id 200, Acc 1 / Dec 20, 놓으면 1/60 초에 20/60 내려감). 하네스는 CSM 과 같이 진행 (주 경로 강제 없음 유지).
