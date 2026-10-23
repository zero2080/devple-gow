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
- ATHN01A: 1.5 초마다 살아 있는 적 전부 처치 → 269 → 244 → 동영상 a01mp05 → 501 → 해치 해골 3, 그 뒤 생성 없음 (`deckWavesEndAfterTutorialKills`, §7)
- ATHN01A: 처치가 없으면 도발 해골 4 외에 생성 없음 — Respawn 은 죽음(메시지 6)으로만 (`deckRespawnOnlyOnDeath`)
- 전 레벨(R_ 제외) ScriptWorld 생성 + 시작 + 30틱 예외 없음
- 전 레벨 전 핸들러: 인자 수 표로 스택 균형 (return 뒤 빈 스택, underflow 없음)

## 5. 도구
```bash
./gradlew :tools:run --args="script:entities gow-rom/ATHN01A.WAD"   # 엔티티 슬롯 이름/상수, 소유 GO, ENV
./gradlew :tools:run --args="script:survey gow-rom"                 # 전 레벨 집계 (모드 분포, 이벤트 번호, DeathZone 필터)
```
BallHull 수정: 평면 수를 헤더 0x38 이 아니라 hull 별 평면 수의 합으로 읽는다 (ENV_LoadCheckB 는 0x38 = 0 인데 평면 6개 → 이전엔 판정 불가).

## 6. 남은 일
- 원작 접촉 판정 (0x1A7A78 → 0x11E5E8, 종류 0xFF9CE00F, 콜백 0x1A8410) 도 점이다: 오브젝트 관절 하나의 월드 위치 (0x1A6308 — 관절 = 모델 인스턴스 +0x86 ← 정의 +0x3C, 부품 슬롯이 있으면 그쪽 +0x34). 여기서는 발 위치 — 영웅이 어느 관절인지 미확인 (ATHN01F 출구 영역은 계단 위 +28 부터, 43 문서 자동 플레이)
- Vis/Animator/Creator 효과를 실제 오브젝트에 적용 (GameScene 통합), 적 템플릿 대응
- EventSensor 버튼 안내(PromptOnEnter), 세이브(메시지 7/8 발동 시각 저장)

## 7. ATHN01A 갑판 파도와 튜토리얼 안내 (사용자 보고 "적이 끊임없이 나옴") [확실: 데이터·디스어셈블]
**스폰 엔티티 원작** (vtable 0x2BA4F4..): 초기화 0x1A3840 (슬롯 0..9 = +0xB0..+0xF8, 8 바이트씩, 만든 수 +0x100·죽은 수 +0x104 = 0),
조건 0x1AC580 = 기본 Condition (0x1AA1C0, +0x6C) 이 참이고 **MaximumCount (+0xB0) 가 0 이거나 만든 수 &lt; 그 값**이면 참 → 만든 수 +1
("MaximumCount %d exceeded (%d)"), 메시지 0x1AC630: 1 = 시작 (SpawnAtStart +0xC0), **6 = 만든 오브젝트 제거 → 죽은 수 +1, 보낸 쪽이 있고 Respawn (+0xC8)
이면 0x29D93C = 1 로 0x1A9750 (지연 ≥ 0.25, 0x1A97A8)**, 7/8 = 저장/복원 (만든 수·죽은 수·마지막 발동). 효과 0x1AC850 = TemplateId (+0xD0) &gt; 0 이면 생성.
슬롯 1 (`unk1`, 기본 −1) 은 0x1A3840 이 읽어 둘 뿐 vtable 함수 어디서도 쓰지 않는다 (ClimbSkellys 만 4) — "살아 있는 최대 수" 가 아니다.
→ **Respawn 스폰은 "죽을 때마다 하나 다시" (살아 있는 수 = 스폰 엔티티 수) 이고, 끝은 MaximumCount 가 아니라 Condition 스크립트가 낸다.**

**ATHN01A 데이터** (`script:entities --code`): `TauntEnemy1..4` (MaximumCount 1, 도발 SpawnAction 0..2, 그룹 8/2/3/4, PlayerStartZone 진입 후 6–8 초) 와
`ClimbSkellys-SkellyLevel1AI4..7` (MaximumCount 없음, **Respawn**, SpawnAction 523 = 난간 넘어 올라오기, 그룹 5, Condition `LevelData[4..7] == true && LevelData12 <= LevelData13`).
도발 해골이 죽으면 자기 짝 ClimbSkellys 를 켜고 (LevelData4..7 = true + 그 스폰 발동), 그 뒤로는 올라오는 해골이 죽을 때마다 같은 자리에서 다시 올라온다.
단계 = LevelData1 Init 값 (PreGrab 9 = 2, PostGrab 11 = 5, PostLaunch 13 = 5), 처치는 DestructionSensor (모드 3, Filter 5 / 도발 그룹) 의 Action 이 센다:

| 단계 (LevelData2, 0) | 처치를 세는 센서 → 카운터 | 넘어가는 조건 → 안내 |
|---|---|---|
| 1 (거짓, 거짓) | DesSkel·DesPreGrab → 8 | 8 ≥ 9 (2) → `GrabMsgGroup-VisMsg`: PreloadMovie a01mp05, **ShowMessage(269, 5)** "[□] 보통 공격 [△] 강한 공격", LevelData2 = 참 |
| 2 (참, 거짓) | DesPostGrab → 10 | 10 ≥ 11 (5) → `LaunchMsgGroup-VisMsg`: **ShowMessage(244, 5)** "적을 잡으려면 [○]", LevelData0 = 참 |
| 3 (참, 참) | DesPostLaunch → 12 | 12 ≥ 13 (5) 이고 해치 위 아님 (14) → `MpegController` Idle + **PlayMovie a01mp05**; 501 → LevelData3, 해치 해골 3·AI8 (MaximumCount 1) |

- 안내는 이 두 ShowMessage (Internal 0xA → HUD 메시지 상자) 뿐이고 **진행은 처치 수만** 본다 — 버튼 입력·잡기/띄우기 성공 이벤트를 기다리지 않는다
  (레벨의 EventSensor 는 1023/1029/1039/1048/1049/501 뿐, 영웅 기술 이벤트 1035/1047 없음). `LaunchMsgGroup-VisMsglaunch` 는 Action 없는 빈 Vis (Delay 11).
- Respawn 조건은 제거 순간 (메시지 6) 에 보므로 카운터 증가 (센서 Action, 같은 틱 지연 큐) 보다 먼저 — 발사 단계 6 번째 처치까지 다시 나오고 그 뒤로는 끝.
- 해치 보류: 5 번째 처치 때 영웅이 해치 위 (`HatchZoneGroup-pCube3974` Entry/ExitSensor → LevelData14) 면 동영상 조건이 거짓이라 보류되고,
  해치에서 내려오는 순간 ExitSensor (대상 VisMpeg·VisFadeOut) 가 같은 조건을 다시 보고 재생한다 (`deckMovieWaitsUntilHeroLeavesHatch`).
  해치 위에 계속 서 있으면 적이 다 끝나도 진행이 없다 — 원작 스크립트 그대로 (시연 봇이 여기서 멈췄던 원인, 43 시연 녹화).
- 오브: 해골 처치 = `SKS Death` (R_SKS OrbEmitter 500) 빨강 1×3. HUD 숫자는 300 단위 배수라 300 개 전에는 0 이고 원형 게이지만 찬다 (11 문서).
  1.5 초마다 모두 처치하는 헤드리스 재현: 도발 4 + 올라오기 ≈ 14 + 해치 3 + AI8 1, 마지막 생성 ≈ 25 초. 게임 실행 (적 4 초 뒤 자동 처치, `-Dgow` 임시 추적) 도 같은 순서·수로 끝났다.
- 엔진 런타임 (`ScriptWorld.spawn`/`destroy`/`trigger`) 은 위 원작과 같다 — **카운터가 멈추는 경우는 적이 죽지 않을 때뿐**. 원인으로 고친 것: 배 밖으로 밀려나거나 떨어진 적이
  바닥이 없으면 높이를 유지해 떠 있었다 (죽지 않음 → 그 Respawn·단계 카운터 정지, 38 §19 남은 것) → `EnemyNav` 가 바닥 없을 때도 중력 낙하,
  바다 판 (`ENV_DeathZone1..5`, y ≈ −290, 1029) 에 들어가면 원작처럼 데스존 사망, 바닥 없이 3000 넘게 떨어지면 `GameScene` 이 사망 처리 [근사] → 메시지 6 이 센다.

## Vis 의 Visible (표시/숨김)
Vis 엔티티 슬롯 0 `Visible`: 1 = 보임, 2 = 숨김, 0 = 그대로 (`Attr11_0/1` 은 충돌·카메라 볼륨). 이전에는 카메라 볼륨만 반영하고 표시 상태는 무시해
스크립트가 숨긴 오브젝트가 계속 그려지고 R1 로 잡혔다. 이제 `GameScene` 이 대상 오브젝트(소유 GO 인스턴스)를 렌더에서 빼고/되살리고, 숨긴 상호작용
오브젝트(`Interactables.Item.hidden`)는 잡을 수 없다 — 예 PAND00C 엘리베이터 레버는 시작 시 `LeverOffInit`(2) 로 숨고, PAND00D 고리 퍼즐(204/205) 뒤
`LeverOn`(1) 으로 나타난다. 하네스는 두 레벨에 걸친 퍼즐을 재현하지 못해 숨김 상태 상호작용을 표시만 한다 (보고서 "숨김 상태 상호작용").

- 시작 연출 (PlayerStartZone): Internal 0xC Idle(true) 동안 조작 잠금 (`GameScene.scriptIdle`) — 13 초 IdleCam → Idle2Cam → VisIdleOff (Idle false). 전에는 무시해 시작 카메라 동안 움직일 수 있었다.
- HatchSkellys (ClimbAI5–7, SpawnAction 없음) 은 동영상 a01mp05 끝 (501) 에 해치 옆에 선다 — 동영상이 난간을 넘어 뛰어내리는 장면을 덮는다 (`--skip-movies` 면 갑자기 나타남). 스폰 기술 500–504·523 의 SCRIPT SCR_NoShadow (0xBFC016FE) 동안 그림자 없음.
- Internal 0xC Idle(bool) = 0x1B0930 → 0x1BF080(0, flag): 전역 정지 0x29DB58 (getter 0x1BF108). 켜져 있으면 HUD 리셋 (Progress_UpdateHud 0x1D71A0 → 0x1BA150 — 미터·히트 카운터 내림), 적 토큰 반납·비용 있는 공격 없음 (0x1CDEC8 / 0x1CCB60), 조작 잠금. 실제 PS2 플레이 영상 대조: ATHN01A 시작 연출 13 초 동안 HUD 없음, 조작이 풀리면 HUD 가 올라온다 (`GameScene.scriptIdle`, `AiTargets.globalHold`).
