# 32. 구현: 원작 적 AI (AiBrain)

명세: [17-enemy-ai](17-enemy-ai.md). 패키지 `devple.gow.engine.combat`. 예전 단순 AI(26 문서 §5: 60 단위 접근 + 0.8–1.8초 무작위 공격)를 대체.

## 1. 구성
| 클래스 | 원작 대응 | 역할 |
|---|---|---|
| `AiTemplate` | AI_RegisterTweaks 0x18B650 | AI1 필드를 해시·형·오프셋 그대로 읽음 (short/byte 하위 비트 마스크) |
| `DecisionTree` | DTre_Parse 0x1A1CF8 / Evaluate 0x1A2250 / Leaf 0x2296E0 / Pick 0x229258·0x229660 | 결정 트리 블록 파서·평가, 공격 가중 선택, 방어 % 선택 |
| `AiData` | TWK_DTre / TWK_Sold / BlockMod 0x19BEF0 | WAD 에서 트리·병사 구조체(비용/용량/A/B) 로드, BlockMod 항목 |
| `AiTargets` | 대상 슬롯(vtable+0x78), 영웅 기술 기록 0x3044D0 | 대상별 공유 토큰 예산·공격 슬롯·포위 칸 점유·기술 사용 기록 (WeakHashMap 레지스트리) |
| `AiBrain` | AI_Update 0x1D0E08 외 | 인지 → 토큰 → 포위 칸 이동 → 공격 시도 / 방어 판단 / 조건 함수 28개 |
| `EnemyFighter` | Enemy1 | Brain 의 이동 의도 적용(회전, 이동 애니), 피격 시 방어 판단 후 `takeHit` |
| `EnemyNav` | 공용 내비게이션 0x1EBD78 의 적 쪽 (owner+0x174 / +0x2DC) | 자신의 지상/공중/벽/줄 상태, Sold 속도, 벽·줄 잡기·이동·놓기 (§적 자신의 벽·줄 이동) |

API:
```java
EnemyFighter f = kind.spawn();          // Kind 에 AiData 포함 (EnemyFactory 가 R_PERMK Sold_000 을 영웅 용량으로 등록)
f.setFoe(hero);                          // AiBrain.setTarget → AiTargets.slot(hero) 공유
f.setDifficulty(session.difficulty);     // AI Recovery Time Mult (기본 Normal)
f.brain().setPatrol(waypoints);          // 선택: 미인지 시 순찰
f.brain().onLog(System.out::println);    // 판단 로그 (인지/토큰/행동/방어)
f.brain().debugString();                 // 상태 요약
```
`Hittable.combatant()` (기본 null)를 영웅 쪽이 구현하면 조건 11/18/20/23(대상 방어 중·기술 중·방위·기술 ID)과 BlockMod 기록이
즉시 동작한다. 구현하지 않아도 첫 피격 때 공격자 Combatant 를 기억한다.

## 2. 원작 대비
| 항목 | 상태 |
|---|---|
| 결정 트리 73개 전부 파싱, 조건 0–24 | 구현 (4/5 = 자신·대상 이동 상태, 21/22 충돌, 25–27 스크립트 변수는 0) |
| 공격 토큰 (획득 ×1.1 / 반납 ×1.5 / 경직 시 반납, 예산 ≤ 대상 용량) | 구현 |
| 공격 슬롯 A/B (동시 공격 수), 공격 후 대기 × 난이도, 재사용 대기 | 구현 |
| 포위 11×11 칸, 반경(토큰 0x94 / 대기 0x96), 칸 대기·재배치 타이머, 상자 밖 직접 접근 | 구현. 칸 선택 = 원작 0x20BA60/0x20BFB0 (아래) |
| 토큰 없는 적의 도발 후 칸 경과 타이머 0 | **근사** — 원작 그대로면 같은 자리에서 도발만 반복해 멈춤 |
| 방어: BlockMod(40초 창 기술 기록) + Block% + 방어 목록 % | 구현. 기술 시작 감지는 Combatant 관찰(같은 기술 재시작은 진행률 되감기로 판단) [근사] |
| 방어 조건 | 이미 방어 자세 아님 + 방어 불가 공격(flags&2) 아님 + 자신이 기술 중 아님(근사: 원작은 NO_BLOCK 이벤트로 제한) |
| 인지: 시야 거리(0x4C×16)·반각(0x50), 피격 시 즉시, **시선 가림** (0x1D257C sub_0011E3E8 레이, 템플릿 0x84&0x800 이면 생략) | 구현 (레벨 충돌 레이, 눈 높이 48 [추정]). **경보**: 인지 순간 경보 반경(0x48 × 16) 안의 같은 대상 동료도 전투 (`AiTargets.Slot.brains`, 메시지 1000 수신 처리 [추정]) |
| 이동 속도 | **Sold +0x178 (m/s) × AI 스틱** [확실] — 미노 3 → 48, 해골 8 → 128 유닛/s, 가속 +0x16C / 감속 +0x170. 스틱 = AI1 0x68 % (순찰 0x64 %). 클립 = 블렌드 축 값(_Cycle)이 속도에 가장 가까운 것 [추정] (§이동 속도 출처) |
| 모델 크기 ScaleCenter/Range | 구현 (`world()` 에 적용, 해골 0.87) |
| 결정 트리 없는 적 (보스·스크립트 구동) | 예전 단순 규칙 유지 |
| 순찰 | 웨이포인트 목록 따라가기만 (ESC Patrol → 좌표 연결은 GameScene 쪽 미연결) |

## 3. 테스트 (`AiBrainTest` 헤드리스 + `EnemyNavTest` / `EnemyClimbTest`)
| 테스트 | 검증 |
|---|---|
| `EnemyNavTest` (데이터 없음, 10개) | Sold 속도·가속, 능력 없으면 낙하, 벽 잡기(조건 4 = 1, 마스크 0x20), 벽 평면 이동·바닥 내려서기, 꼭대기에서 0x8A ms 뒤 점프, 줄 잡기(32)·줄 따라 이동·45° 보다 가파르면 놓기, 줄 32 밖, **줄 하위 상태 → 마스크 (0x20000 만 0x80)**, **매단 줄 놓기 (수평 > 16)** |
| `EnemyClimbTest` (8개) | AI1 021 능력·0x8A, Sold_020/050/000 속도, ATHN01D 그물 스폰 → 벽 → 그물 위 영웅에 벽 기술(15–18)만, 갑판 영웅에게 내려옴, ATHN02B 벽 스폰·수평 줄 잡기 → 줄 기술(29/7)만, **슬롯 7 = 504 (01D 그물 −1) → SKSwallSpawn·무적·벽에서 끝**, **벽 피격: 지상 태그 반응 없음 / 태그 71 → 175 / HP ≤ 5 태그 72 → 172, 벽 유지**, **수평 줄 0x20000 → ROPEHOHR**, **같은 지점 두 해골 겹침 < 2/3** |
| decisionTreesDecode | DTre_050 행동 노드·AXECOMBO 비용/대기/거리, 방어 17, 크라토스 Sold (4,12,10,10), 미노 비용 2, AI1 값, DTre_021 25 노드 |
| minotaurApproachesAndAttacksWithTemplateCadence | 400 단위에서 접근(<120), 토큰 보유, 도끼 공격 ≥3, 공격 끝→다음 시작 ≥ pause × 난이도 배율 (Easy/Normal/Impossible) |
| easyAttacksLessOftenThanImpossible | 해골 80초 공격 수 Easy < Impossible (Sold 속도로 칸 이동이 빨라져 Impossible 간격이 칸 대기 2초에 묶임 → 40초는 난수에 따라 같아짐) |
| skeletonUsesTreeMoves | 해골 기술 ⊂ {0,1,2,11,12}, SKU3HIT 사용, 크기 0.87 |
| blocksAtTemplateRate | 미노 Block 40% (400회 ±7%), 해골 0% |
| blockModLearnsRepeatedMoves | 영웅 기술 7 을 9회 → 항상 방어, 40초 후 기록 소멸 |
| attackTokensLimitSimultaneousAttackers | 7마리 포위: 토큰 보유 ≤ 12/비용(해골 3, 미노 6) 이고 최대치 도달, 예산 = 보유자×비용, 공격 비용 합 ≤ 10, 동시 공격 ≤ 3 |
| everyEnemyKindRunsTenSeconds | 적 WAD 32종 · AI1 템플릿 47개를 10초씩 실행, 예외 없음 |

```bash
./gradlew -q :engine:test --tests '*AiBrainTest*' < /dev/null
./gradlew -q :engine:run --args="--hero --enemy R_MIN" < /dev/null   # 접근 → 대기 0.5초 → 도끼 공격, 40% 방어
```

## 대상 이동 상태 (결정 트리 조건 4/5)
`Hittable.locomotionState()`: 영웅은 내비게이터 상태로 벽 오르기·턱 매달리기·사다리 → 1, 줄·수평 줄·짚라인 → 3, 공중 → 2, 그 밖 0
(원작 owner+0x174 비트 0x20/0x400/0x4 → 0x1A14B8/0x1A14D8 → 0x1A1480). 적 자신(조건 4)은 `EnemyNav` 상태 (아래 §).
→ DTre_021 (해골 -C) 의 "대상 벽 → attack up!/down!", "대상 로프 → rope attack!" 가지가 실제로 선택된다. 검증 `AiBrainTest.targetLocomotionStateCondition`.

## 적 자신의 벽·줄 이동 (`EnemyNav`) [확실: 흐름·수치 / 근사: 표시]
**영웅과 적은 같은 내비게이션 코드를 쓴다.** 적 갱신(0x1C13E0·0x1C3370 → 0x1EBD78, 37 문서의 BMI Think 도 같은 경로) = 영웅 갱신과 같은 함수이고, 이동 상태는 owner+0x174 하위 11 비트,
이동 능력은 owner+0x2DC. 적은 AI1 0x38 을 그대로 +0x2DC 로 복사한다 (AI 초기화 0x1D0818: `sw v0(=tmpl+0x38), 0x2DC(owner)`) [확실].

| owner+0x174 | 상태 | 0x1EBD78 분기 | 조건 4 (0x1A1480) | 분기 상태 마스크 (0x207DC0) |
|---|---|---|---|---|
| 0x1 / 0x2 | 지상 | 0x1EAEC8 | 0 | 0x2 |
| 0x4 / 0x8 / 0x40 | 공중 / 놓음(SCR_DetachFromWall 0x1C9380 이 0x8) / 날기 | 0x1EB3D8 | 2 (0x4) | 0x4 (0x1000 아니면) |
| 0x20 (0x30 검사) | 벽 | 0x1C88F0 | 1 | 0x20 (0x400000·0x300000 아니면) |
| 0x80/0x100/0x200 | 수영 계열 | 0x1CA448 | 0 | 0x10000 |
| 0x400 | 줄 (하위 0x10000 사슬/0x20000 수평 줄/0x40000 짚라인, 매단 줄은 없음) | 0x1C6008 | 3 | 0x80 (하위 0x20000 일 때만, 0x207E20) |
| 0x4000 / 0x8000 | R1 오브젝트 붙음 / 조작 | 0x1C4258 / 0x1C4E18 | – | – |

AI1 0x38 이동 능력 (등록 0x18BC30–0x18BE0C, TWK 해시 대조로 이름 확인) [확실]:
Climb 0x1 (1c2da9b5), Balance 0x2, Fly 0x8, Swim 0x10, Dive/DiveDash 0x20, (112af47f) 0x40, **Rope 0x100**, Pole 0x200, Zipline 0x400, (27f351d1) 0x800,
LadderSlide/RopeSlide 0x4000, (ff971f92) 0x10000 (영웅: R1 잡기 0x1C4008 조건), **WallPullup 0x20000**, Jump 0x100000.
해골 -C (AI1 021/023/026/028/030) = Climb·0x40·Rope·Pole·Zipline·0x800·RopeSlide·Jump (021 은 WallPullup 도), 일반 해골 020·미노 050 = 0.
Fly 이면 시작 상태가 0x40 (0x1D08C0).

**잡기** [확실: 흐름 / 근사: 판정 기하]
- 벽: 공중 갱신 0x1EB3D8 이 +0x2DC & 1 이면 0x1C7828 (벽 잡기) → 0x1C7280 이 0x20 을 세운다 (0x1EBCE4). 엔진: 공중일 때 가슴 높이 8 방향 34 캐스트 중
  가장 가까운 Climbable 면 (영웅 `HeroNavigator` 와 같은 WALL_OFF 10 / CHEST 20).
- 줄: 0x1EC54C 가 +0x2DC & (0x100|0x200|0x400|0x800) 이면 0x1C5C20 → 상태가 공중(0x4C)일 때만, 손 점(+0x120) 반경 **32** (0x1C5CF0 `lui 0x4200`) 안의
  줄 → 0x1C4EF8 (이벤트 0x41B 1051) → 줄 플래그 +0x374 로 하위 상태 (4 → 0x10000, 1&2 → 0x40000, 1 → 0x20000, 0 → 없음). 엔진: 손 = 발 + 30.
  +0x374 출처 = 줄 초기화 0x216DC4 (SCR_RopeSkinned, 38 §2.3 의 f1/f2/f3) [확실]: f3 → 4 (짧은 사슬), 아니면 f1 (끝점 고정) → 0x218488 이 1, f2 → |2.
  즉 **매단 줄 = 하위 없음, 양끝 고정 수평 줄 (f1) = 0x20000, 짚라인 (f1+f2) = 0x40000, 사슬 (f3) = 0x10000**.
- **스폰 = 벽·줄 옆 공중**: 해골 -C 스폰 지점은 벽·줄 바로 옆이라 첫 프레임 공중 → 잡기. 데이터 확인 (`EnemyClimbTest`):
  ATHN01D `CargoRopeEnemies1–7` 의 AI1 021 18 체 (돛대 그물 z −1903) 전부 벽 (같은 그룹의 020 2 체는 꼭대기 발판 → 지상), ATHN02B `ClimbWallEnemies2–5,7` 벽, `RopeEnemies2–6` (줄 x 544 아래
  36–70) 은 떨어지다 수평 줄(gorope8) 잡기. `ClimbWallEnemies1/6`·`RopeEnemies1`·ATHN01A `HatchSkellys` 는 바닥 위 스폰 → 지상.

**AI 이동 의도** — AI_MoveTo 0x1CF9D0 (호출: 포위 0x1CDBF0, 접근 0x1CD300, 0x1CCD58, 순찰 0x1CF020, 0x1D0348) 가 자신의 +0x174 로 갈린다 [확실]:
- 지상(+0x2D8&1): 0x1CF458 — 남은 거리 < Sold+0x174 ×16/60 이면 정지, 아니면 단위 스틱.
- **벽(0x20): 0x1CF5D0** — 목표 방향을 몸 행렬의 오른쪽·위 축(벽 평면)에 투영, 정지 거리 Sold+0x108 ×16/60, 스틱 크기 = min(1, 거리 / (min(+0x10C, +0x110) ×16/60)).
  +0x2D8 & 0x300 (벽 끝 상태, 의미 추정) 이면 **AI1 0x8A ms (1000) 뒤 점프 버튼** (sub_001CF1C8: 입력(vtable+0x50) +0x30 |= 1).
- 날기(0x40): Sold+0x1B4 기준. 공중(0x4C): 스틱 유지.
- **줄(0x400)** (0x1CFCE0–0x1CFEF4, VU0 해독) [확실: 흐름]:
  - 하위 0x20000/0x40000 (수평 줄·짚라인): 스틱 = 목표 방향. 목표가 **16 넘게 아래 (Δy < −16) 이고 방향이 45° 보다 가파르면** (dir.y < −cos45 0xBF34FDF4) 점프 버튼(놓기), 아니면 거리 < 16 이면 도착.
  - 그 밖 (매단 줄·사슬): 목표가 자신보다 아래이고, 줄 객체(vtable+0x34) 위치에서 목표까지 **수평 거리 > 16** 이고 스틱 · 몸 정면(+0x20 행) > cos45 이면 점프(놓기), 정렬이 아니면 스틱 (0,1,0).
  (예전 문서의 "정렬이고 거리 ≤ 16 이면 점프" 는 bc1fl 방향을 거꾸로 읽은 것.)
- 그 밖(지상 일반, 0x1D0030): 스틱 = 방향 × (ai+0x72 & 0x22 ? AI1 0x64 : 0x68) %, Sold+0xEC 로 10 프레임 안 거리면 감속. 끝으로 이전 스틱과
  AI1 0x98 (같은 방향) / 0x99 (반대) % 로 보간 (벽 제외).

**엔진 규칙** (`EnemyNav.update(pos, stick, goal, goalClinging, busy, dt)`):
- 지상: §이동 속도. 공중: 수평 속도 유지 + 중력, 바닥 STEP 14 안이면 착지 (GameScene 의 예전 적 낙하 처리를 대신, 결정 트리 적에만 연결).
- 벽: 목표 = 전투 중이면 대상 위치, 아니면 AI 목표 점. 벽 평면 투영, 좌우 Sold+0x10C / 상하 +0x110 (×16) — 해골 2.17/3.7 m/s. 매 틱 앞쪽 캐스트로
  Climbable 면에 다시 붙임. 대상도 벽(조건 5 = 1)이면 24 앞에서 멈춤 [근사], 아니면 내려가다 지나친 바닥에 착지 → 지상. 위로 막히면 WallPullup 능력은 벽 너머 바닥으로,
  아니면 AI1 0x8A ms 뒤 벽 점프(법선 70) → 공중, 착지 전까지 다시 잡지 않음 [근사].
- 줄: 목표의 줄 위 투영점 쪽으로 Sold+0x158 ×16 (해골 32 유닛/s) [추정: 필드 의미]. 대상도 줄(조건 5 = 3)이면 24 앞에서 멈춤. 놓기 = 위 원작 규칙
  (수평 줄·짚라인: Δy < −16 & 45° 보다 가파름 / 매단 줄: 아래 & 매단 점에서 수평 > 16, 정렬 조건 생략 [근사], 위치 기준 = 발 [근사]). 짚라인 미끄럼·매단 줄 흔들기는 없음.
- 목표 = **AI 목표 점 그대로** (포위 칸 또는 칸 상자 밖이면 대상, 원작 AI_MoveTo 호출 0x1CDBF0/0x1CD300) — 예전 근사(벽·줄 위 전투 중 목표 = 대상)를 바꿈. 대상 자신이 목표일 때만 KEEP 24.
- 죽으면 놓고 떨어짐. 벽·줄 위에서는 넉백 없음, 바라보는 방향 = 벽 / 줄 진행 방향.
- 애니 = 적 TWK `/Animation/go<적>/Navigation/` 표: ANIClimbIdle (wallClimbIdle), 방향별 wClimbBlend0–3 (위/오른쪽/아래/왼쪽 [추정, 영웅과 같음]),
  수평 줄 ANIBRope/ANIBRopeF (ropeHOHI/ropeHOH), 매단 줄 ANIRope/ANIRopeU, 공중 ANIFall. 벽 공격 = DTre 기술 15–18 (wallClimbAT*), 줄 = 19/29·7.
- 분기 상태 마스크: `CombatOwner.stateMask()` (기본 지상 0x2/공중 0x4) → `EnemyFighter` 는 `EnemyNav.combatMask()`. R_SKS 기술 15–18 의 분기 플래그 0x148260
  = 벽(0x20) 전용, 19·7 의 0x1482C0 = 줄(0x80) 전용이라 벽·줄에 붙어야만 시작된다 [확실: 데이터]. 줄은 **하위 0x20000 (수평 줄) 일 때만 0x80**, 매단 줄·짚라인·사슬은 0
  (상태 조건 없는 분기만) [확실: 0x207DC0]. `EnemyNav.Rope.sub` = NavRope 종류 (TRAVERSE 0x20000 / ZIPLINE 0x40000 / HANG 0; f3 사슬 0x10000 은 NavRope 에 플래그가 없어 0 [근사: 마스크·AI 분기는 같음]).

### 벽 스폰 (SKSwallSpawn) — 스폰 슬롯 7 = 생성 액션 [확실: 코드 경로·데이터]
SpawnEnemy 슬롯 (등록 0x1A3840 기본값: 0 0, 1 −1, 2–6 0, **7 −1**, 8 0) → 0x1AC850 이 16 바이트 생성 데이터 {+0 TemplateId(4), +2 슬롯5, +4 슬롯6, **+6 슬롯7**, +8 슬롯8, +A 순찰 번호, +C −1, +E 1} 을 만들어
클래스 "Enemy1" (0x29D8E8) 초기화 데이터로 넘긴다 (0x1A4FC8 → 0x1AD978). Enemy1 초기화 0x22CA78:
- **+6 (슬롯 7) → AI+0x94**: −1 이 아니면 0x1DFD00 + AI+0x86 = 1 (생성 액션 대기) [확실: 저장 / 추정: 요청 시점 = 첫 갱신].
- +8 (슬롯 8) → AI+0x7C, **5 면 석화 메시지 0x407** (돌로 시작), +C ≥ 0 이면 0x1E1638, +A → 순찰.
슬롯 7 값 = 적 CombatFile **액션 ID** (데이터로 확인, `EntitySlots` 이름 `SpawnAction`/`InitState`):
| 값 | 의미 (R_SKS/R_SKC CombatFile 023·020) | 쓰는 곳 |
|---|---|---|
| −1 (없음) | 바로 AI | ATHN01D CargoRopeEnemies1–6 (그물 옆 → 첫 프레임 벽), ATHN02B RopeEnemies, ClimbWallEnemies3(일부)·6·7 |
| 500/501/502/550 | SPAWN (`spawn` 3.7 초, 땅에서 솟음, PlayFX 020 goSpawnHole, 슈퍼아머) | ATHN01D CaptKey, ATHN02A Deck/Respawn, PAND01A HoleSkellys, ATHN02B Sec*… |
| **504** | **SKSWALLSPAWN** (`SKSwallSpawn` 2 초: 벽 안에서 몸을 빼 벽 매달림 자세로 끝, PlayFX 034 "WallSpawn" goSpawnHole, 0–0.83 무적) | ATHN02B ClimbWallEnemies2·3(35/36)·4·5 |
| 523 | #97406ae2 (사다리 소리, 애니가 R_SKS 에 없음 → 엔진에선 즉시 끝) | ATHN01A ClimbSkellys, ATHN02B ClimbWallEnemies1 |
| 0/1/2 | TAUNT02/03/04 | ATHN01A TauntEnemy1–4 |
루트 액션 A500–A504 분기 플래그 0x148242 = 지상 전용 → 원작은 초기화 상태(지상)에서 Match. 엔진 `EnemyFighter.startSpawnAction(id)` 는 요청 동안 상태 마스크를 0x2 로 두고
`combat.request` → 벽 옆 스폰은 동작 중(공중 → 0x1C7828) 벽을 잡고, 애니가 벽 자세로 끝나 벽 상태로 이어진다. GameScene 스폰이 `SpawnRequest.spawnAction` 으로 부른다 (보스 제외).

### 벽·줄 피격 반응 [확실: 데이터 — 새 코드 없음, 분기 마스크로 동작]
R_SKS/R_SKC CombatFile 021: 벽 반응은 **공격자 자세 태그로만** 고른다 (영웅 WALLJAB*/WALLCLIMBAT* 의 SET_1B0 70 위·71 오른쪽·72 아래·73 왼쪽):
A170–173 (자신 HP 0..5) WALLHITRIGHT/LEFT → AUTO [0–0.6] WALLCLIMBATR/L **반격**, A174–177 (HP ≥ 6) WALLHITRIGHT2/LEFT2 (0.8 초), EV_STUN → A178.
지상 반응 A101–A123 은 0x148242 (지상 전용)이라 **벽 위에서 지상 기술(다른 태그)에 맞으면 반응 없이 피해만**. 애니(`anim:sheet`): wallHitRight2 = 한 손으로 매달려 흔들린 뒤 원위치 —
**맞아도 벽에서 떨어지지 않는다** (해골 CombatFile 에 SCR_DetachFromWall 0x19CBB0 없음, 영웅 쪽에만 1 회). 줄 (0x80 = 수평 줄): A165 EV_HIT → ROPEHOHR (태그 무관), 사망 태그 79 (ROPEPOWER01) → ROPEDECAP,
그 밖 사망 A152 DEATH02 (줄) / A153 태그 78 (ROPEHOHA) 벽 DEATH01 / A154 HITLAND (상태 무관, 즉시). 엔진: 사망하면 `nav.drop()` → 떨어짐 (원작 사망 낙하 경로 미분석 [근사]).
벽 잡기 처형 = 파일 022 WALLGRABLEFT/RIGHT/UP (영웅 잡기 태그 201/205/206, GrabSync).

### 같은 벽 위 여러 적
원작은 벽 위에서도 AI 목표 = 포위 칸 (칸은 대상당 한 적, 0x20BA60) → 벽 평면 투영이라 좌우로 갈린다. 이전 엔진(대상 직행)은 같은 지점 스폰 두 해골이 12 초 내내 겹침 (719/719 프레임),
칸 투영 후 약 절반 (칸이 벽 법선 방향으로만 다르면 같은 점에 투영 — 원작도 같은 식). 원작의 생물끼리 충돌 밀어내기는 미분석.

미구현/근사: 줄 흔들기·짚라인 타기 (적), 매단 줄 놓기의 정렬 조건, 생물끼리 밀어내기, 0x2D8 0x100/0x200 의미, 슬롯 5 (204/202/12/20…, 의미 미상), 523 사다리 스폰 애니(레벨 쪽 애니 미확인).

## 이동 속도 출처 [확실]
(owner+0x320)+8 = **TWK_Sold 구조체** (sub_00243700; 같은 포인터로 +0x244 토큰 비용, +0x26C 동결 저항, +0x270 석화 기술을 읽는 곳과 같음). 쓰는 곳:
| Sold | 사용처 | 의미 | 해골 020 / 미노 050 / 크라토스 000 |
|---|---|---|---|
| +0x178 | 0x1EAFF0 (지상 0x1EAEC8) | **목표 속도 = +0x178 × min(1, 스틱 크기) × 16** (× Narrow 면 +0x230) | 8 / 3 / 10 |
| +0x16C / +0x170 | 0x1EAFF8 → sub_001E5FE0 | 가속 / 감속 (×16 /s²) | 80·80 / 10·20 / 100·50 |
| +0xEC | 0x1D0088 (AI) | 도착 감속: 10 프레임 안 거리면 스틱 축소 | 8 / 3 / 7.5 |
| +0xF0 | 0x1CBED8 (AI) | 막힘: 프레임 이동 < ×16/60 × 0.25 가 AI1 0x9A 프레임 | 3 / 3 / 0.9 |
| +0x174 | 0x1CF4D0 (AI, +0x2D8&1) | 정지 거리 (×16/60) | 3 / 3 / 0.9 |
| +0x108 / +0x10C / +0x110 | 0x1CF5D0, 0x1C8D34 | 벽 최소 / 좌우 / 상하 | 0.5 / 2.17 / 3.7 |
| +0x158 | 0x1C6370 (줄) | 줄 이동 [추정] | 2 / 2 / 2.5 |
| +0x1B4 | 0x1CFBB0 | 날기 | 7.5 / 7.5 / 15 |
AI 스틱 크기 = AI1 0x68 % (평상 100) 또는 0x64 % (ai+0x72 & 0x22, 순찰 등 — 엔진은 순찰에 0x64) [확실: 값 / 추정: 비트 뜻].
→ 예전 근사(디딘 발 미끄럼: 미노 걷기 28·달리기 156, 해골 53)는 폐기. 클립 선택은 원작 ANIWalk 가 블렌드 그룹이고 그룹 축 값
(`<클립>_Cycle` = ANM act +0x14: 미노 walkFront 3.0 / runFront 5.0, 해골 Front 4.0 / runFront 8.5) 이 속도(m/s)와 같은 단위로 보여
"속도에 가장 가까운 축 값의 클립" [추정] — 미노 3 m/s → walkFront, 해골 8 m/s → runFront, 순찰 50 % 4 m/s → Front.

## 포위 칸 선택 (0x20BA60 → 0x20BFB0) [확실: 흐름 / 근사: 벽 칸]
1. 막힌 칸 = 격자 비트맵 +0x40 | +0x50 | +0x60 (엔진: 다른 적 점유 + 대상 칸 + 벽 칸). +0x40 은 0x20B038 이 대상→칸 선분(0x20B870)과 충돌 질의(0x1853E8)로 채움 → 엔진은 대상 눈높이에서 칸 중심까지 레벨 충돌 레이가 막히면 벽 칸 [근사: VU0 매크로 구간 미해석].
2. 자기 칸(0x20AF30, 밖이면 생략)에서 4방향 flood fill — 도달 못 하는 칸도 제외 (121 비트 4 워드, 변화 없을 때까지 반복).
3. 0x20BFB0: 대상 중심 격자 원점 + (2·rand − 1)·X축 + (2·rand − 1)·Z축 를 반경(토큰 보유 64 / 대기 160)배 → 칸 → 제외 칸이 아니면 채택, 재귀로 최대 5번.
4. 그래도 없으면 rand(121) 칸; 막혀 있으면 sub_00243B68 (미상 → 엔진은 자신에 가장 가까운 허용 칸).
→ 이전 근사("반경에 가까운 칸 + 자기 거리 비용")와 달리 **반경 정사각형 안 무작위** 가 원작이다.
