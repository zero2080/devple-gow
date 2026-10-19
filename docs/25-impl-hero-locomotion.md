# 25. 구현: 애니메이션 전환 / 크레토스 이동 (엔진)

패키지 `devple.gow.engine.anim`, 테스트 씬 `scene/HeroTestScene` (`./gradlew :engine:run --args="--hero"`).

## 1. 구성
| 클래스 | 역할 | 원작 데이터 |
|-------|------|------------|
| `PoseBlend` | 포즈 보간. 쿼터니언 관절 = 정규화 후 최단경로 nlerp, 오일러 관절 = 16384 주기 각도 보간 | 05 문서 관절 플래그 0x8000 |
| `Animator` | 클립 재생 + 크로스페이드 | TWK `/Animation/goHero/<클립>_Tween` (초) |
| `HeroController` | 대기/걷기/달리기, 카메라 기준 스틱 방향 회전, 이동 | 클립 `standIdle`, `walkBlend1`, `walkBlend2` |

## 2. 측정·결정 사항
| 항목 | 값 | 근거 / 확신도 |
|------|----|--------------|
| 루프 이동 클립의 `zeroJoint` | 전 프레임 일정: 걷기 Z = −3.2, 달리기 Z = −8.0 | 측정 (확실) |
| 이동 속도 해석 | `|Z| × 15` 유닛/초 → 걷기 48, 달리기 120 | 15fps 기준 프레임당 이동량으로 **추정**. 단발 클립 `dash` 는 1초에 −108 누적(실제 루트 모션)이라 크기가 맞음 |
| TWK `walkBlend0/1_Cycle = 1.7`, `walkBlend2_Cycle = 5.0` | 미사용 | blendParam(3.5/5.0)과 함께 블렌드 공간 파라미터로 추정 — 전투/이동 상세 분석 시 확정 |
| 메시 정면 | **−Z** (34 문서 §4 에서 정정) | 발끝 정점 z −3.99, 코 −3.19, 미노타우로스 꼬리 +Z. 이전의 렌더 180° 회전은 캐릭터가 이동 방향의 반대를 보게 했다 → 렌더 행렬 = translation · rotateY(yaw) |
| 크로스페이드 기본값 | TWK 에 없으면 0.15초 | |

## 3. 검증
- `--hero --screenshot` (자동 입력: 스틱 오른쪽 앞) → 달리기 클립으로 전환, 카메라 반대 방향 오른쪽 앞으로 이동 확인 (`work/hero_run.png`)

## 4. 다음
- 레벨 충돌(12/23 문서) 연결: 지면 높이 추종, 벽 충돌
- 전투 상태(10 문서): 공격/방어/회피/점프를 Animator 위에 상태 머신으로
- 걷기 ↔ 달리기 블렌드 공간(blendIndex/blendParam) 재현

## 5. 점프 / 공중 상태
| 항목 | 구현 | 근거 |
|------|------|------|
| 클립 | 제자리 `jumpUp`(ANIVJump), 이동 중 `jumpAir`(ANIJump), 2단 `jumpdoubleair`(ANIDblJump), 하강 `fallv`(ANIFall), 착지 `land`(ANILand) | TWK `/Animation/goHero/Navigation/` 표 (데이터 그대로) |
| 이름 대소문자 | `AnimationSet.find` 에 대소문자 무시 2차 검색 추가 | Navigation 표는 "standidle", "fallv" 처럼 소문자 |
| 점프 초속 | √(2·g·45) ≈ 119 유닛/s, 정점 ≈ 45 유닛 | **추정**. 점프 애니의 zeroJoint Y 는 ~9 유닛뿐 → 궤적은 물리로 처리됨. 원작 점프 파라미터 키는 미복원 |
| 2단 점프 | 공중에서 × 한 번 더 | 원작 조작 |
| 공중 제어 | 스틱 방향으로 48 유닛/s | 걷기 속도 [추정] |
| 공중 상태 | `CombatOwner.onGround() = grounded` → 분기 상태 플래그 0x4 → 공중 기술(AIRH1/AIRV1/AIRDASH …) 활성 | 10 문서 §2.4 |
| 우선순위 | 전투 기술이 × 를 쓰지 않았을 때만 점프 | |

`JumpAirComboTest`: × → 정점 30–60 유닛, 공중 □ → AIRH1(13), 착지.
