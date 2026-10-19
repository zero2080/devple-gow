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
| `EnemyFighter` | Enemy1 | Brain 의 이동 의도 적용(걷기/달리기, 회전), 피격 시 방어 판단 후 `takeHit` |

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
| 결정 트리 73개 전부 파싱, 조건 0–24 | 구현 (4/5 벽·로프 상태, 21/22 충돌, 25–27 스크립트 변수는 0) |
| 공격 토큰 (획득 ×1.1 / 반납 ×1.5 / 경직 시 반납, 예산 ≤ 대상 용량) | 구현 |
| 공격 슬롯 A/B (동시 공격 수), 공격 후 대기 × 난이도, 재사용 대기 | 구현 |
| 포위 11×11 칸, 반경(토큰 0x94 / 대기 0x96), 칸 대기·재배치 타이머, 상자 밖 직접 접근 | 구현. 칸 선택 비용은 근사 (반경 바깥 칸 강하게 회피) |
| 토큰 없는 적의 도발 후 칸 경과 타이머 0 | **근사** — 원작 그대로면 같은 자리에서 도발만 반복해 멈춤 |
| 방어: BlockMod(40초 창 기술 기록) + Block% + 방어 목록 % | 구현. 기술 시작 감지는 Combatant 관찰(같은 기술 재시작은 진행률 되감기로 판단) [근사] |
| 방어 조건 | 이미 방어 자세 아님 + 방어 불가 공격(flags&2) 아님 + 자신이 기술 중 아님(근사: 원작은 NO_BLOCK 이벤트로 제한) |
| 인지: 시야 거리(0x4C×16)·반각(0x50), 피격 시 즉시, **시선 가림** (0x1D257C sub_0011E3E8 레이, 템플릿 0x84&0x800 이면 생략) | 구현 (레벨 충돌 레이, 눈 높이 48 [추정]). **경보**: 인지 순간 경보 반경(0x48 × 16) 안의 같은 대상 동료도 전투 (`AiTargets.Slot.brains`, 메시지 1000 수신 처리 [추정]) |
| 이동 속도 | 걷기/달리기 클립의 디딘 발 미끄럼 속도로 추정 (미노: 걷기 28, 달리기 156 단위/s). 대기 반경 ×1.5 밖이면 달리기 |
| 모델 크기 ScaleCenter/Range | 구현 (`world()` 에 적용, 해골 0.87) |
| 결정 트리 없는 적 (보스·스크립트 구동) | 예전 단순 규칙 유지 |
| 순찰 | 웨이포인트 목록 따라가기만 (ESC Patrol → 좌표 연결은 GameScene 쪽 미연결) |

## 3. 테스트 (`AiBrainTest`, 헤드리스, 8개)
| 테스트 | 검증 |
|---|---|
| decisionTreesDecode | DTre_050 행동 노드·AXECOMBO 비용/대기/거리, 방어 17, 크라토스 Sold (4,12,10,10), 미노 비용 2, AI1 값, DTre_021 25 노드 |
| minotaurApproachesAndAttacksWithTemplateCadence | 400 단위에서 접근(<120), 토큰 보유, 도끼 공격 ≥3, 공격 끝→다음 시작 ≥ pause × 난이도 배율 (Easy/Normal/Impossible) |
| easyAttacksLessOftenThanImpossible | 해골 40초 공격 수 Easy < Impossible |
| skeletonUsesTreeMoves | 해골 기술 ⊂ {0,1,2,11,12}, SKU3HIT 사용, 크기 0.87 |
| blocksAtTemplateRate | 미노 Block 40% (400회 ±7%), 해골 0% |
| blockModLearnsRepeatedMoves | 영웅 기술 7 을 9회 → 항상 방어, 40초 후 기록 소멸 |
| attackTokensLimitSimultaneousAttackers | 7마리 포위: 토큰 보유 ≤ 12/비용(해골 3, 미노 6) 이고 최대치 도달, 예산 = 보유자×비용, 공격 비용 합 ≤ 10, 동시 공격 ≤ 3 |
| everyEnemyKindRunsTenSeconds | 적 WAD 32종 · AI1 템플릿 47개를 10초씩 실행, 예외 없음 |

```bash
./gradlew -q :engine:test --tests '*AiBrainTest*' < /dev/null
./gradlew -q :engine:run --args="--hero --enemy R_MIN" < /dev/null   # 접근 → 대기 0.5초 → 도끼 공격, 40% 방어
```
