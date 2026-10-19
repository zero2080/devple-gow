# 27. 구현: 통합 게임 씬

실행: `./gradlew :engine:run --args="--game ATHN01A"` (레벨 이름 생략 시 ATHN01A = 새 게임 시작 레벨)

## 1. 구성
| 요소 | 클래스 | 출처 문서 |
|------|-------|----------|
| 레벨 렌더 (배치·하늘) | `level/LevelScene` (외부 카메라 모드 `setExternalCamera`) | 12, 23 |
| 지면/벽 | `level/LevelCollision.groundHeight / collideSphere` | 12, 23 |
| 카메라 | `LevelData.activeCamera` (CMV 볼륨 → 레벨 카메라 위치) + 크레토스를 주시 | 12 |
| 크레토스 | `game/HeroRig` = 모델·스켈레톤·애니 + `HeroController` + `Combatant` + `PlayerState` + `GodMode` + `HitCounter` | 05, 10, 11, 25, 26 |
| HUD / 일시정지 | `ui/UiLayer`(FLP_HUDK) + `HudBinding` | 07, 21 |

## 2. 규칙
- 시작 위치: 레벨의 `start*` GO 인스턴스 위치·Y 회전 (ATHN01A = (1536, 19, 1957))
- 지면: 발 위 14 유닛(계단 허용)에서 아래로 바닥 탐색. 바닥과 2 유닛 이내면 붙이고, 아니면 중력 낙하
- 중력: 9.82 × 16 유닛/s². 1 m ≈ 16 유닛은 크레토스 키(≈37 유닛 ≈ 2.3 m 의 거구)와 Orb 템플릿 `Gravity = 9.82` 로부터의 **추정**
- 벽: 허리(+20) 높이 반지름 12 구를 비바닥 면 밖으로 밀어냄
- 카메라: 원작은 볼륨별 고정/레일 카메라. 현재는 레일 이동·FOV 파라미터가 미해석이라 **레벨 카메라 위치에서 크레토스를 바라보는 방식**으로 근사. 볼륨 밖이면 추적 카메라
- 이동 입력은 카메라 기준 (원작과 동일)
- START → `PS2_PauseMenu_Event` 로 원작 일시정지 메뉴, 메뉴가 `*Done`/`*Menu_Choice` 를 쓰면 복귀 (선택 1 = 체크포인트 재시작)
- 사망(HP 0) 또는 바닥 없는 곳 낙하 → 시작 위치로 (체크포인트 시스템은 스크립트 런타임 연결 시 교체)

## 3. 검증
- `--game ATHN01A --auto-run 1.5 --screenshot` → 배 갑판 위 크레토스 + 원작 HUD + 레벨 카메라 (`work/game1.png`)

## 4. 스크립트·적 통합 (`GameSession` + `ScriptWorld`)
| 스크립트 이벤트 | 게임 처리 |
|----------------|----------|
| ShowMessage(id, 초) | 원작 HUD 메시지 창: `PS2_MessageTemplate_Line1..5` 에 줄별 텍스트, `PS2_MessageTemplate_Event = 1`, 시간 후 0 |
| CheckPoint | 재시작 위치 갱신 |
| 데스존 (플레이어) | 사망 → 체크포인트 재시작 (원작 HP 보정 `onCheckpointRestart`) |
| LoadWad / LoadForWarp → Warp, Load(wad, entry) | 페이드 전환으로 새 `GameScene` (진행도·GlobalData 는 `GameSession` 으로 유지). 진입점 이름의 GO 위치에서 시작 |
| LoadCheck | WAD 가 있으면 준비 완료 (원작은 백그라운드 로드 완료 여부) |
| SpawnEnemy | 레벨 RSRCS 태그(0x1F4)의 적 리소스 `R_<이름>` 를 `EnemyFactory` 로 미리 구성, **TemplateId = AI1 번호**로 종류 선택 |
| 적 사망 | `ScriptWorld.destroy(actor)` → DestructionSensor (튜토리얼 메시지 등) + OrbEmitter "<적> Death" 오브 |

**새로 확인**: ATHN01A 의 SpawnEnemy TemplateId = 20 = R_SKS 의 AI1 020 (skelly Lvl 1) → 13 문서에서 미해결이던 TemplateId 의미는 AI1 템플릿 번호로 판단 [강한 추정].

검증 로그 (`--game ATHN01A`):
```
레벨 ATHN01A (진입점 null): 시작 (1536, 19, 1957), 적 종류 [R_SKS]
다음 레벨 미리 로드 요청: ATHN01B          ← PlayerStartZone 센서
적 스폰: R_SKS.WAD (템플릿 20) at (1526, 0, 1739)
적 스폰: R_SKS.WAD (템플릿 20) at (1547, 0, 1751)
```
스크린샷 `work/game2.png`: 배 갑판에서 해골병들이 크레토스에게 접근.

## 5. 다음
- 레벨 스크립트 런타임(13/28 문서, 진행 중) 연결: 센서 → 메시지/체크포인트/사망 구역/레벨 전환(LoadWad·Warp)/적 스폰
- 적 스폰: SpawnEnemy 엔티티 + WadInfo 가 지정한 적 WAD(`R_SKS` 등) 로 `EnemyFighter` 생성
- 레일 카메라(NCV 커브), FOV, 바다면(SCR_Water) 등 연출
