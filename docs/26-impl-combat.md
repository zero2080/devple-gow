# 26. 구현: 전투 시스템 (엔진) — 1·2단계

명세: [10-combat-system](10-combat-system.md) §15 재구현 명세. 패키지 `devple.gow.engine.combat`.
실행: `./gradlew :engine:run --args="--hero --blade 2"` (J □, I △, L ○, K ×, Q L1, E R1, 방향키 = 오른쪽 스틱)

## 1. 구성
| 클래스 | 원작 대응 | 역할 |
|-------|----------|------|
| `CombatData` | CombatFileSet 000 "Hero" (파일 000–006) | 기술 387개 전역 ID 맵, 루트 액션 201개, 기술→애니(이름 대문자 해시) |
| `Combatant` | Combat_Update 0x205968, PollInput 0x208A98, Match 0x208578, StartBranch 0x209488 | 상태 = (기술 없음 | 기술). 입력 폴링·우선순위·버퍼(큐)·즉시 캔슬·이벤트 분기·공격 판정·OnContact |
| `CombatInput` | 코드→패드 비트 표 0x2C2238 | 분기 입력 코드 1–10 ↔ `PadButton`, 11 AUTO, 12 ANY_INPUT, 13~22 이벤트 |
| `DamageRules` | Player_DamageScale 0x1DBD18 | 난이도 × 코스튬 × 무기 레벨 × 신 모드 배율 — **전부 TWK 에서 읽음** |
| `CombatOwner` / `Hittable` | owner+0x174/0x178 | 공격자 상태 / 피격 대상 인터페이스 |
| `TrainingDummy` | – | 연습용 표적 (HP 90, poise 10 = 미노타우로스 Lv1 기준) |
| `HeroController.updateCombat` | – | 기술 중 루트 모션(zeroJoint 변화량), 기술 시작 시 스틱 방향 또는 자동 조준 회전, 몸통 겹침 방지 |

## 2. 원작 규칙 반영 상태
| 규칙 | 상태 |
|------|------|
| 분기 창 `[winStart, winEnd]`, 즉시(0x1) vs 버퍼(winEnd 에 실행) | 구현 |
| 우선순위 AUTO 1 < 시작부터 홀드 2 < 버튼 3 < 0x1000 4, 큐 교체 규칙 | 구현 |
| 가장 구체적인 분기(와일드카드 점수 최저) + 동점 난수 | 구현 |
| 상태(지상/공중), 신 모드 0x100/0x200, 아르테미스 0x2000/0x8000, 락온 0x400, 무기 레벨, 마법 ID, HP 범위, 종류 ID | 구현 |
| 차지(0x800), 플래그 0x8, 0x20000/0x80000, 상대 자세 태그(+0x18) | 미구현 → 해당 분기는 불통과 |
| 입력 모드 1 누름 / 2 뗌 / 3 시작부터 홀드 / 4 홀드 / 5 안 누름 | 구현 |
| 방향 조건 1–6, 오른쪽 스틱 9–14 (회피) | 구현 (7/8/15 낙하 높이 미구현) |
| target −1 = 기술 종료, −2 = 차단 | 구현. −1 후 같은 입력으로 루트 액션 재평가 (IDLECOMBAT → △ 가 바로 나가도록) |
| 이벤트: 무적(9), 신 게이지(21), 히트스톱(24) | 구현 (나머지 종류는 미구현) |
| 이벤트 분기 fire(13/14/16/17/22) | 구현 (17 적중 확인, 22 막힘) |
| 피해 = 공격.damage × DamageRules | 구현 |
| 공격 판정 | **근사**: 부위별 히트박스 대신 정면 55 유닛 구(사슬 무기 사거리). 같은 판정 구간은 상대당 1회 |

## 3. 검증 (`CombatantTest`, 헤드리스 60Hz)
| 입력 | 기대 (10 문서 §4.4) | 결과 |
|------|--------------------|------|
| □□□□ (블레이드 Lv2) | COMBO3A(71)→3B(72)→3C(73)→3D(74), 피해 2,2,2,3+3 × 2.0 | 4,4,4,6,6 ✔ |
| △ | COMBO6A(32), 6+6 × 2.0 | 12,12 ✔ |
| R1 | DASH(6) 는 블레이드 Lv ≥1 에서만 | Lv0 불가, Lv1 DASH ✔ |

## 4. 구현 중 발견·수정
- 루트 모션 때문에 크레토스가 표적을 **통과**해 이후 공격이 빗나감 → 몸통 반지름 겹침 방지 + 기술 시작 시 자동 조준(160 유닛, TWK `Targeting Range` 10 × 16 으로 추정) 추가.
- `IDLECOMBAT` 의 ANY_INPUT(target −1) 이 △ 입력을 소비해 아무 기술도 안 나감 → 종료 직후 루트 액션 재평가.
- 디스플레이가 120Hz(ProMotion)라 "N 프레임 후 캡처" 가 예상보다 일찍 끝남 → 로직은 고정 60Hz 이므로 검증은 헤드리스 테스트로 수행.

## 5. 적 (3단계 일부) — 미노타우로스 Lv1
`EnemyFighter` = 원작 데이터 + 단순 AI. `--hero` 씬에 기본 배치.

| 요소 | 출처 |
|------|------|
| 스켈레톤 `min`(55관절), `MDL_MIN`, `ANM_min`(41) | R_MIN.WAD |
| 기술·피격 반응·사망 | CombatFile 050–053 (루트 액션 REQUEST0 / EV_HIT_REACT / EV_DEATH) |
| HP 90, Damage Multiplier, Block 40%, Circle Health Threshold 25 | TWK `/TweakTemplates/AI1/050/` |
| 의사결정 | **단순 AI** (원작 AI 코드 미분석): 60 유닛 밖이면 `walkFront` 로 접근, 안이면 0.8~1.8초마다 공격 목록(10,11,20,21,22,40,41,44)에서 무작위 요청, 피격 시 Block% 확률로 BLOCK(17) |

`Combatant` 를 영웅/적 공용으로 일반화:
- 피격자 파이프라인 `takeHit` (무적 → 방어 이벤트 16 → 누적 피해·poise → 사망 14 / 잡기 원 21 / 반응 13)
- **공격 자세 태그**(이벤트 20 `SET_1B0`)를 피격자 반응 분기의 matchB 와 비교 → 태그별 HITSTAGGER/HITLAUNCH/HITSPIN…, 태그 없으면 HITFRONT/BACK/LEFT/RIGHT 중 동점 난수
- `request(moveId)` = 원작 sub_00209378 (AI 기술 요청)

검증 로그 (`--hero --blade 2 --auto combo`):
```
기술 71 combo3A → 피해 4.0 (HIT)   [미노타우로스] 기술 122 hitLeft (EV_HIT_REACT)
기술 72 combo3B → 피해 4.0 (HIT)   [미노타우로스] 기술 120 hitFront
기술 74 combo3D → 6.0 + 6.0        [미노타우로스] hitBack / hitFront
기술 32 combo6A                    [미노타우로스] 기술 17 block → 12.0 (BLOCKED) → 기술 13 blockShove (EV_BLOCKED_HIT 반격)
```
원작 HUD(`FLP_HUDK`, 21 문서)를 같은 씬에 올려 체력/오브를 `PlayerState` 와 연동.

알려진 문제: 미노타우로스 기술 40(AXEUPPERCUT) 애니가 R_MIN 에 없음(다른 WAD 공유 추정), 바닥의 여분 도끼 파트 표시 제어 미상(05 문서 미해결).

## 6. 다음 (10 문서 §15.6 의 3·4단계)
1. 방어/패리 (L1 홀드 PARRY01→BLOCK, 이벤트 16 반응), 회피 무적 검증
2. 적 AI: 적 CombatFile(파일 020–023 해골병 등) + AI1 템플릿(HP/방어 확률) 로 Combatant 를 적에도 적용 (fire 기반 반응 기술)
3. 신들의 분노(L3+R3, 99/1, 7.0715/s), 마법 4종 MP 비용, 히트 카운터 보상, 처치 시 오브
4. 부위별 히트박스 (무기 관절 rWeapIH 등) 로 판정 정밀화
