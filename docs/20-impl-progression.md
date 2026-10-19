# 20. 구현: 성장 시스템 (엔진)

명세: [11-progression-save-flow](11-progression-save-flow.md) §8 재구현 명세. 패키지 `devple.gow.engine.game`.

## 1. 구성
| 클래스 | 역할 | 원작 대응 |
|-------|------|----------|
| `Tweaks` | TWK 튜닝값 런타임 조회 (경로 + 키 해시) | TWK 로더 0x17ecb8, 해시 0x1758a0 |
| `GameRules` | 최대 HP/MP 표, 업그레이드 비용표, 코스튬 오브 배율, 조각당 레벨 | TWK `/Player/`, `FLP_HUDK` 스크립트 상수 |
| `Weapon` | 업그레이드 대상 6종 (블레이드 + 마법 4 + 아르테미스의 검) | HUD 변수 접두사 `Blades`, `Lightning` … |
| `PlayerState` | 영속 상태 + 규칙 (오브 획득, 투자, 아이템, 체크포인트 HP) | `PlayerProgress` 0x304440 |

**설계 원칙: 수치는 원본 데이터에서 읽는다.** 코드의 상수는 데이터가 없을 때의 기본값과, 원작 코드에 하드코딩된 값(붉은 오브 상한 60000, 조각 상한 18)뿐이다.

## 2. 규칙 요약
| 규칙 | 구현 |
|------|------|
| 오브 획득 | `points × costume 배율` → 체력/마법은 최대치로 클램프, 붉은 오브는 60000 상한 |
| 업그레이드 투자 | `invest(w, k)`: 투자액이 비용 이상이면 레벨업 후 초과분 이월 (루프), 최대 레벨에서 중단 |
| 고르곤의 눈 / 불사조 깃털 | ID 10/11, 최대 18개, 6개마다 HP/MP 레벨 +1 (조각당 수는 TWK `Shards Per Meter Level`) |
| 첫 마법 | 새로 얻은 마법을 선택 마법으로, MP 레벨이 0 이면 1 로 |
| 제우스의 선물 | `itemCard(1|2)`: HP / MP 레벨 +1 |
| 체크포인트 재시작 | `hp = max(saved,25)`, 4번째 사망부터 `+(deaths-3)×10`, 저장 HP < 75 이면 75 상한 |

## 3. 검증 (`./gradlew :engine:test`, gow-rom 이 있을 때만 실행)
- `TweaksTest`: `/Player/Max Hit Points Level 0/4 = 100/200`, `Max Magic Level 5 = 200`, `God Mode Loop Name = SND_TRANSFORM_LP`
- `PlayerStateTest`: 블레이드 비용 1500/…/9000, 제우스 750, 하데스 L2 10000 / 2000 투자 → L1 + 이월 500 / 눈 6개 → HP 125 / 첫 마법 → MP 100 / 오브 상한 / 재시작 HP 45

## 4. 미정 사항
- 아르테미스의 검 아이템 ID 미확정 → 임시로 7 (11 문서 §9)
- 업그레이드에 따른 무기/마법 WAD 교체(`R_WPN<c>_<lv>`, `R_LGHTN<lv>` …)는 레벨 로딩 구현 시 연결

## 5. 게임 안 파워업 메뉴 (원작 FLP_HUDK 스크립트 그대로)
| 단계 | 처리 |
|------|------|
| SELECT | `HudBinding.write` (Progress_ToHud) → `PS2_PSMMenu_Event = 1`, 게임 정지, 이후 HUD 값 쓰기 중단 (메뉴가 투자 처리) |
| 투자 | 메뉴 스크립트: ○ 누르고 있으면 프레임당 1개 → 잠시 후 10개(QuickDrain) 씩 `PS2_PowerOrb_Count` → `PS2_<무기>Orb_Count`, 비용 도달 시 `PS2_<무기>_Level`++ 와 레벨업 화면 |
| 레벨업 화면 | 실제 2초(GetTime) 대기 후 `PowerUp_LevelUp_Check = 99` → ○ 로 닫기 |
| 메뉴 닫기 | △ **뗌**(입력 cond 9) → `Animate_Off` → `PS2_PSMDone = 1` |
| 반영 | `GameScene.onPowerUpClosed`: 오브·투자·레벨을 HUD 변수에서 PlayerState 로 (원작 0x1D7700 / Upgrade_LoadWad 0x1D7890) |

엔진이 넣어 줘야 하는 변수 (없으면 메뉴 버튼이 무반응 — 테스트로 발견):
- `PS2_EnableButtons = 1` : 메뉴 ○/△ 핸들러의 첫 조건
- `PS2_PowerUp_FirstTime = 0` : 첫 사용 안내가 끝났음 (원작 PlayerProgress 힌트 비트)

FLP `GetTime` 을 벽시계에서 **UI 틱 × 1000/60** 으로 변경 (결정적, 일시정지와 일관 — 헤드리스 테스트에서 2초 대기가 통과하지 않던 문제 해결).
`PowerUpMenuTest`: 붉은 오브 2000 → 블레이드 레벨 1, 남은 500, `PS2_PSMDone = 1`.
