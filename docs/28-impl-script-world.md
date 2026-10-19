# 28. 구현: ESC 엔티티 런타임 (ScriptWorld)

명세: [13-script-entities](13-script-entities.md). VM 은 [24-impl-script-events](24-impl-script-events.md).
패키지 `devple.gow.engine.script`, 슬롯 표는 도구 쪽 `devple.gow.tools.script.EntitySlots` (엔진·도구 공용).

## 1. 구성
| 클래스 | 역할 |
|-------|------|
| `ScriptWorld` | 레벨 하나의 엔티티 런타임. 생성 → `start()` (시작 큐) → `tick(dt, x, y, z)` |
| `ScriptEntity` | 엔티티 + 소유 GO 배치 + 월드 행렬(엔티티 행렬 · 관절 월드) + ENV 볼륨 + 마지막 발동 시각 + 이벤트 인자 |
| `ScriptActor` | 스크립트가 보는 오브젝트 (이름, **그룹** = Filter 비교값, 위치, 스폰한 엔티티) |
| `ScriptHost` | `GameScriptHost` 확장: Entity 스코프 17개 + Internal 전 함수 인자 수 표, 리스너로 통지 |
| `ScriptListener` | 게임 쪽 통지 (전부 default 무시) |

## 2. API
```java
ScriptHost host = new ScriptHost(flow, player);     // 레벨이 바뀌어도 재사용 (GlobalData 1회 초기화). 헤드리스는 new ScriptHost()
ScriptWorld w = new ScriptWorld(levelData, host, listener);
w.start();                                          // 플레이어 액터(Start: "Hero", 그룹 1) + Data Init + 시작 센서
w.tick(dt, x, y, z);                                // 플레이어 위치 → 볼륨 진입/이탈 → 이벤트 → 지연 발동
ScriptActor e = w.addActor("skel", 8, pos);         // 게임이 만든 오브젝트 (CreationSensor)
w.destroy(e);                                       // 제거 (DestructionSensor, SpawnEnemy Respawn)
w.postEvent(501, "a01mp05", 0, 0, null);            // 게임 → EventSensor (다음 틱)
w.find("Start"); w.entity(id); w.slot(entity, "Delay");
```
리스너: `message(id, sec)` · `checkpoint` · `level(LevelRequest{LOAD, LOAD_WAD, LOAD_FOR_WARP, WARP, LOAD_CHECK, GOTO})` · `loadCheck(wad)` ·
`deathZone(actor, source, event, arg)` · `objectEvent` · `spawnEnemy(SpawnRequest{actor, templateId, patrol, world})` · `sound(group, op)` ·
`music(func, args)` · `camera(name)` · `vis` · `animate` · `create` · `movie` · `internal(func, args)` · `debug`.

`ScriptHost(flow, …)` 에 GameFlow 를 주면 Load/LoadWad/LoadForWarp/Warp/LoadCheck 가 `GameFlow.msg*` 로도 전달된다.

## 3. 구현 결정
| 항목 | 결정 | 근거 |
|------|------|------|
| 볼륨 바인딩 | ESC 를 refs 에 가진 오브젝트의 첫 배치 = 소유 GO, 그 GO 의 ENV(kind 2) 볼륨 | 전 레벨 다중 배치 0, ENV ≤ 1 (`script:survey`) |
| 진입 판정 | 매 틱 액터 위치가 `PlacedVolume.contains` 인지 이전 틱과 비교 | 원작 접촉 목록 비교(0x1A6030 / 0x1A60C8) 대체 |
| 슬롯 평가 | 핸들러가 있으면 매번 VM, 결과를 슬롯 타입으로 변환 | 원작 상수 접기와 결과 동일 |
| 지연 큐 | (시각, 순번) 우선순위 큐. 같은 틱에 만기된 연쇄 발동은 같은 틱에 처리 | 0x303DB0 정렬 삽입 |
| DestructionSensor 모드 3 | Filter 를 적용 | 데이터(DesSkel Filter 8 ↔ TauntEnemy 그룹 8). 원작 핸들러엔 검사 없음 → 통지 쪽에서 거른다고 추정 |
| deathZone 통지 | 송신기 이름 접두사가 `DeathZone` 이거나 이벤트 1029 | 이벤트 의미가 [추정] 이라 이름도 함께 사용 |

## 4. 테스트 (`ScriptWorldTest`, 실데이터)
- ATHN01A: 플레이어를 `ENV_LoadCheckB` 안으로 → `LoadCheck("ATHN01B")`
- ATHN01A: 시작 시 LevelData1 Init (PreGrabNum=2, PostGrabNum=5 …), 플레이어 "Hero"/그룹 1
- ATHN01A: 영웅은 적 전용 DeathZone1(Filter 8) 무반응, 그룹 8 액터는 이벤트 1029 → deathZone
- ATHN05A: 영웅 DeathZone → 영웅에게 1019(Arg1 504) + deathZone
- ATHN01A: 그룹 8 적 2마리 제거 → DesSkel → `GrabMsgGroup-VisMsg` → `ShowMessage(269, 5.0)` ("[SquareButton] 보통 공격 …")
- ATHN01A: 시작 위치 체류 → PlayerStartZone → LoadWad("Athn01B") + TauntEnemy 스폰(그룹 8, Delay 후)
- 전 레벨(R_ 제외) ScriptWorld 생성 + 시작 + 30틱 예외 없음
- 전 레벨 전 핸들러: 인자 수 표로 스택 균형 (return 뒤 빈 스택, underflow 없음)

## 5. 도구
```bash
./gradlew :tools:run --args="script:entities gow-rom/ATHN01A.WAD"   # 엔티티 슬롯 이름/상수, 소유 GO, ENV
./gradlew :tools:run --args="script:survey gow-rom"                 # 전 레벨 집계 (모드 분포, 이벤트 번호, DeathZone 필터)
```
BallHull 수정: 평면 수를 헤더 0x38 이 아니라 hull 별 평면 수의 합으로 읽는다 (ENV_LoadCheckB 는 0x38 = 0 인데 평면 6개 → 이전엔 판정 불가).

## 6. 남은 일
- 원작 접촉 판정은 오브젝트 충돌 형상 기준 — 여기서는 점(위치) 판정
- Vis/Animator/Creator 효과를 실제 오브젝트에 적용 (GameScene 통합), 적 템플릿 대응
- EventSensor 버튼 안내(PromptOnEnter), 세이브(메시지 7/8 발동 시각 저장)
