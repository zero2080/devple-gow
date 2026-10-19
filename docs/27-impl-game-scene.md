# 27. 구현: 통합 게임 씬

실행: `./gradlew :engine:run --args="--game ATHN01A"` (레벨 이름 생략 시 ATHN01A = 새 게임 시작 레벨)

## 1. 구성
| 요소 | 클래스 | 출처 문서 |
|------|-------|----------|
| 레벨 렌더 (배치·하늘) | `level/LevelScene` (외부 카메라 모드 `setExternalCamera`) | 12, 23 |
| 지면/벽 | `level/LevelCollision.groundHeight / collideSphere` | 12, 23 |
| 카메라 | `level/CameraRig` (CMV 선택·레일/궤도·전환 보간, 원작 재구현) | 12, 30 |
| 크레토스 | `game/HeroRig` = 모델·스켈레톤·애니 + `HeroController` + `Combatant` + `PlayerState` + `GodMode` + `HitCounter` | 05, 10, 11, 25, 26 |
| HUD / 일시정지 | `ui/UiLayer`(FLP_HUDK) + `HudBinding` | 07, 21 |

## 2. 규칙
- 시작 위치: 레벨의 `start*` GO 인스턴스 위치·Y 회전 (ATHN01A = (1536, 19, 1957))
- 지면: 발 위 14 유닛(계단 허용)에서 아래로 바닥 탐색. 바닥과 2 유닛 이내면 붙이고, 아니면 중력 낙하
- 중력: 9.82 × 16 유닛/s². 1 m ≈ 16 유닛은 크레토스 키(≈37 유닛 ≈ 2.3 m 의 거구)와 Orb 템플릿 `Gravity = 9.82` 로부터의 **추정**
- 벽: 허리(+20) 높이 반지름 12 구를 비바닥 면 밖으로 밀어냄
- 카메라: `CameraRig` (30 문서) — 원작과 같은 CMV 선택·우선순위·전환표, 레일(NCV_) 위 기준점, 설계 각도/범위, 거리 제한, FOV
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
| LoadWad / LoadForWarp / Warp / LoadCheck | 레벨 슬롯 2개 모델 `LevelLink` → SEAMLESS(LoadCheck, 위치 유지)·WALK(다른 슬롯 바닥)·WARP(도착 Start) 전환. 36 문서 §2 |
| Load(wad, entry) | 진입점 이름의 GO/엔티티 위치에서 시작 (데이터에는 사용처 없음) |
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

## 6. 세이브 / 불러오기
| 항목 | 구현 |
|------|------|
| 세이브 포인트 생성 | SaveZoneOn 센서 → Creator 엔티티(이름 슬롯 `Name0 = "savepoint"`) → 소유 GO(`gosavegamezone*`) 위치에 세이브 포인트 |
| 저장 | 세이브 포인트 45 유닛 안에서 R1 → `SaveGame` 슬롯 0 (`~/Library/Application Support/devple-gow/slot0.save`) + 체크포인트 갱신 |
| 형식 | 자체 properties(UTF-8): 레벨·위치·방향, 난이도·코스튬, PlayerProgress 항목, 무기별 레벨/투자, ESC GlobalData (11 문서 §5.2 순서) |
| 불러오기 | 메인 메뉴 "불러오기"(Menu_Choice 2) → 슬롯 0 이 있으면 세션 복원 후 저장 위치에서 시작. 원작 규칙 `hp = max(저장, 2)`, 사망 수 0 |
| 생략 | 원작 메모리카드 UI(SaveGameMenu 예/아니오, SaveLoadMenu 슬롯 선택, `FSCommand:PS2_MemoryCard_Select`) |

`SaveGameTest`: 진행도·무기·마법·GlobalData 왕복 일치.

## 7. 적 템플릿 매칭 보강
- 한 적 WAD 에 AI1 이 여러 개(R_SKA: 031 / 032 / 039) → 스폰 TemplateId 를 가진 WAD 를 찾아 그 번호로 생성 (`Kind.hasTemplate/spawn(int)`)
- 레벨 WAD 자체의 AI1 (ATHN01D 의 301 "AOXF1", 303 "AOXF4") → 레벨 튜닝값을 합쳐 그 템플릿으로 생성
  → **TemplateId = AI1 번호** 판단 재확인. 단 301 계열 모델 연결(어떤 스켈레톤/모델을 쓰는지)은 미해결: 현재 레벨 첫 적 종류 모델로 표시되며 거꾸로 보이는 개체가 있음
- 디버그: `./gradlew :engine:run -PgowDebug --args="--game ATHN01D"` → 1초마다 영웅 위치/지면 출력

## 8. 허공 방지 (근사)
지상 이동 결과 발밑 4000 유닛 안에 바닥이 전혀 없으면(배 가장자리 밖 등) 수평 이동을 취소한다. 원작의 보이지 않는 벽·바다 데스존을 대신함.
아래층 바닥이 있는 낙하는 그대로 허용. 발견 경위: `--auto-run 6` 에서 ATHN01A 갑판 끝(z≈1635)을 넘어 무한 낙하 (카메라 작업 중 보고).
검증 스크린샷 `work/game4.png`: 원작 레일 카메라(30 문서) 시점, 갑판 끝에서 멈춘 크레토스와 접근하는 언데드 병사들.

## 9. 렌더 순서 (환경 연출 통합)
레벨(하늘 → 지오메트리, 안개) → 적·크레토스(같은 안개 유니폼 적용) → **이펙트 패스**(비·파티클·물, `LevelScene.renderEffects`) → HUD.
이펙트를 캐릭터 뒤에 그려야 반투명 비가 캐릭터를 올바르게 덮는다 (`setDeferEffects(true)`). 스크린샷 `work/game5.png`.

## 10. 컷신 연결 (33 문서)
- 스크립트 `PlayMovie`(Entity 함수 13) → `ScriptListener.movie(op 0)` → `MovieScene.play` (씬 스택 위에 올라가 게임 정지, START/× 건너뛰기)
- 재생 중 음악 정지 → 끝나면 이전 음악 재개 + 스크립트에 이벤트 7 (mpegDone 핸들러, 추정)
- 새 게임으로 ATHN01A 첫 진입 시 오프닝 `Semo01` (원작 sub_0021efd8)
