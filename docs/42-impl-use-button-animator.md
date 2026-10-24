# 42. 구현: 버튼 안내 센서(1024)·질문(Internal 0xB)·Animator 모드, 검증 하네스 2 단계

주 경로 밖에서 강제 이벤트가 있어야만 열리던 되돌아가는 전환 3 곳(ATHN01E→01D, PAND01C→01B, PAND02AA→02A)을 원작 동작으로 연다.
게임(`GameScene`)과 검증 하네스(`TraversalVerifier`)에 같은 규칙을 넣었다. 이제 보고서에 강제 뒤에만 나오는 전환(`*`)이 하나도 없다.

## 1. 버튼 안내 센서 — 이벤트 1024

| 항목 | 원작 | 근거 |
|---|---|---|
| 아이콘 켜기 | 볼륨 진입 처리 0x1AAC88: EventSensor **mode 2 + PromptOnEnter**, 조건 참, 재발동 대기 아님 → `0x21DA50(슬롯 1, 아이콘 14 = R2ButtonStatic)` | [확실: 코드] |
| 아이콘 끄기 | 나감 0x1AAD6C → `0x21DA50(1, −1)` | [확실] |
| 누름 | 영웅 0x1DC3F8: 잡기 등 다른 동작이 아니고 입력 +0x30 비트 7 → `PostEvent(0x400 = 1024, 0, 0, 0, 영웅)` 후 슬롯 1 지움 | [확실: 이벤트·보낸 쪽] |
| 받는 쪽 | EventSensor mode 2 (보낸 쪽이 볼륨 안) — ATHN01E `XFCcaptainCSM-main-r2` → a01MP04 → 501 → `LoadWad(Athn01D)`, PAND01C `handleTrig-r2`, ATHN01D `CaptainsWobbleDoor…GotKey`, PAND05A `statuesR2-EventR2`, PAND02D `R2Statue-volume-EventUSEwrld` | [확실: 데이터] |

- 버튼: 아이콘이 `R2ButtonStatic`이고 엔티티 이름에도 "R2"가 붙어 있어 원작에서는 R2로 불린 버튼이다 [강한 추정].
  ~~엔진은 R1으로 보낸다~~ → Pad_Read 비트 순서로 확정되어 엔진도 **R2** (`PadButton.USE`), 마법은 L2 (10 문서 §16).
- 엔진:
  - `SceneNav.EVENT_USE` 정의.
  - `GameScene`: R1을 누르고 전투 동작 중이 아니면 1024를 보낸다(mode 2라서 볼륨 밖에서는 아무 일도 없다).
  - `ScriptWorld.promptActive()` → `MinigameIcon.PROMPT`(14). 잡기 아이콘이 없을 때만 보인다(엔진 아이콘은 한 번에 하나).

## 2. 질문 — Internal 0xB, 이벤트 1021

- Internal 0xB(msgId) = `0x21D9C8` → GameMgr +0x152C = 질문 메시지 번호. 메뉴(`Menu %d`, `Yes`/`No`, 0x1B9EC8)가 열린다.
- 답하면 0x1B8F4C에서 `PostEvent(0x3FD = 1021, 답 +0x278, 질문 번호 +0x152C, 0, 영웅)`를 보낸다. 예 = 1 [확실: 코드·데이터].
  - PAND01C `handleTrig-yes1`: `arg1 == 1019 ∧ arg0 == 1` → 메시지 258 → 1040 → `LoadWad(Pand01B)`
  - PAND01BB `HMsgSensorYES/NO`: 1005, 예 1 / 아니오 2
  - ATHN01D 선장 문: 1013
- 메뉴 내용 (원작 0x1B9EC0) [확실]: GameMgr +0xA0 = 줄, +0xAC/+0xB0 = "Yes"/"No", +0x208 = **12** (메뉴 상태), +0x278 = 기본 선택 1. 답 = **선택 번호 (1부터)**, 예 1 / 아니오 2 (PAND01BB `HMsgSensorNO` = 2와 일치).
- 메시지 본문에 메뉴가 들어 있다: 1019 = "손잡이를 가져가시겠습니까? ---- *예 / 아니오" (`*` = 기본 선택). 1005·1013도 같은 형식이다.
- HUD FLP `MenuTemplates` (FLP_HUDK): 프레임 = 10·줄 수 + 선택지 수 (`GotoFrame2`는 1부터 → 1줄 2지 = 12 = 원작 값). `PS2_MenuTemplate_Event = n`이면 FLP가 `Pause_Check = 5`, `MenuTemplate_State = n`으로 연다.
  - 위/아래는 HUD 버튼 스프라이트(`/:PressUp`·`/:PressDown`)가 `PS2_Menu_Choice`와 TemplateChoice On/Off를 바꾼다.
  - 확정은 **○** (입력 마스크 0x20, 한국판 배치) → `PS2_Done = 1`.
- 엔진: `QuestionMenu`가 메시지를 나누고, `GameScene.openQuestion`이 줄·선택지·기본 선택을 HUD 변수에 넣고 이벤트를 보낸다. 메뉴가 열린 동안 게임 틱은 멈추고, `PS2_Done`이 오면 `PS2_Menu_Choice`를 읽어 1021을 보낸다. 디버그 스크립트 입력(`-PgowScript`)도 메뉴 중에는 HUD로 간다 (확인: ↓ → 2, ○ → `질문 1019 답 2`).
- 하네스: 질문에는 항상 "예"로 답한다.

## 3. Animator 효과 (0x1AB0B8) [확실: 코드]

| 슬롯 | 의미 | 기본값 (생성자 0x1A2CE0) |
|---|---|---|
| 0 AnimName | `!n` = n 번째 act, `$이름` = **재질(텍스처) 애니** (`MAT_…`), 그 밖 = act 이름 해시 | "" |
| 1 모드 비트 | 0x10 일시 정지 · 0x11 이어 재생 (단독 값) / 0x1 역재생 (속도 부호 반전, 시작 ≤ 0 이면 끝에서) / 0x8 시간 유지 / 재생 방식 0x100A60: 0x4 → 4, 0x2 → 1, 그 밖 → 2 | 0 |
| 2 속도 | 재생 속도 | **1.0** (0x1A2D50) |
| 3 시작 시간 | 모드 0x8이 아니면 여기서 시작 | 0 |

- 재생 방식 4·1·2의 이름(왕복·반복·한 번)은 [추정]이다. 데이터: 레버 8/9(정·역 이어서), 회전방 0/1, 재질 10/11/16.
- **모드 0x12 = 세움** [확실: 0x1AB5E8 → 0x1AB394]: 그 act 를 슬롯 3 시작 시간에 두고 속도 0 (0x100578(시간) + 애니 +0x3C = 0). ATHN02C `LazySueGroup-AniPause`
  (지연 3 초) 가 회전판을 다음 멈춤 자리 (0 / 3 / 6 초 = LevelData6) 에 세운다. 2026-10-24 앞 판은 0x12 를 0x2 (반복) 로 읽어 회전판이 계속 돌았다 (43 §14.2).
- 엔진 `Platforms.play(owner, anim, mode, speed, start)`:
  - `$` 이름 = **재질 애니** [확실: 0x1AB1E4–0x1AB348]: 0x20 버퍼에 "MAT_" + 앞 공백·탭을 건너뛴 이름 (strncpy 0x1C, [0x1F] = 0), 리소스 0x17A7B8 → 대상 GO 모델의
    재질 목록 (+0xD0) 에서 그 리소스의 인스턴스 → 그 +0x44 애니 제어기에 관절 애니와 같은 모드 규칙으로 act 0 (이름 해시 없음). 못 찾으면 "Can't find material '%s'".
    엔진 `Platforms.MatClock` (소유 GO × 재질 시계, `materialTimes` → `LevelScene.setMaterialTimes` → 정지 인스턴스 `Renderer.setMaterialTimes`·자세 GO
    `AnimatedModel.matTimes`), 생성 상태: 재질 ANM act 0x41 은 반복으로 돌고 0x42 는 시간 0 (16 §3.1) — `SceneNav.registerMaterialAnimators` 가 모드 1 "$" Animator
    대상을 미리 등록해, 0x42 재질이 레벨 시간으로 저절로 한 번 재생되던 것도 막는다. 이음 넘기기 `Platforms.State` anim "$MAT_…". 데이터: 16 레벨 86 개
    (ATHN02D 남쪽 문 doorFade/doorSub, ATHN01E·PAND01A·PAND03I 말하는 머리, ATHN05B 마법 문, PAND00C 문 글자, 사슬 스크롤 …).
  - 한 번 재생은 끝에서 멈추고, 반복은 감고, 왕복은 속도를 뒤집는다.
- `EntitySlots` Animator 슬롯 2 기본값을 0 → 1로 고쳤다. 전에는 속도 슬롯이 없는 Animator가 정지했다.
- **연결 누락 수정**: `GameScene` 스크립트 리스너가 `animate`를 받지 않아, 스크립트가 움직이는 발판·문·회전방이 게임에서 재생되지 않았다.
  이제 `SceneNav.animate(source, …)` → 관절 애니 → `ScriptWorld.poseOwner`로 Marker가 따라간다.
- PAND02AA 회전방 `gospinroom1`: 한 act 51.7 초, 속도 0.9 → 57 초.
  - 정방향 끝에서 벽 관절의 Marker 202가 `spinRmEnd`에 들어가 LevelData:1 = 참이 되고, 레버가 보인다(`entitiesLever-VisON`).
  - 다음 레버(1023 id 60 = 100 ∧ LevelData:1) → `SpinRoom1-loadPand02A`.
  - 역재생하면 Marker 201 → `spinRmBegin`.

## 4. 검증 하네스

- **R1 상호작용 순서**: 보이는 오브젝트 → 버튼 안내 센서 1024(볼륨 안 점에서 누름) → 그 사이 드러난 것 → 끝까지 숨은 것(표시).
  - 보스·전투가 끝난 뒤 한 번 더 한다(ATHN01E 선장은 히드라를 쓰러뜨린 뒤에 열린다).
- **Animator**: 레벨마다 `Platforms`를 만들고 스크립트 `animate`로 재생한다. 매 틱 `poseOwner`로 Marker를 옮긴다.
  - 상호작용 뒤에는 한 번 재생 애니가 끝날 때까지 최대 90 초 기다린다.
  - 볼륨을 밟은 뒤에는 최대 20 초 기다린다(PAND03A 누름판 → Marker 104).
- **숨김 표시**: 보이는 상태에서 한 번도 쓰지 못한 오브젝트만 "숨김 상태 상호작용"으로 남긴다. 다 쓴 뒤 숨는 열린 문이나 처치한 선장은 뺀다.
- **2 단계 (이웃 레벨 상태)** `rerunWithNeighbours`: 숨김 표시가 남은 레벨을 다음 두 가지를 받아 다시 돌린다.
  1. 2 걸음 안 이웃(전환·LoadWad·LoadCheck)이 끝났을 때의 **GlobalData** 합집합. 원작은 게임 전체에서 이어진다.
  2. 이웃이 보낸 **전역 이벤트**(EventTransmitter mode 5, `ScriptWorld.tapGlobalEvents`). 이 레벨이 듣고 스스로는 보내지 않는 것만.

  숨김 표시가 줄면 결과를 바꾸고, 보고서에 "이웃 레벨 상태로 다시 돌림"을 남긴다.
  - ATHN01D 선장 문: 열쇠 GlobalData:0 ← ATHN01E → 1024 → 질문 1013 → 1021 → `DoorOn`
  - PAND01BB 석상 크랭크: 손잡이 GlobalData:3 ← PAND00C / PAND01A → 1024 → 질문 1005 → `HandleOn`
- 남은 것: **PAND00C 엘리베이터 레버**.
  - 고리 5 개 위치(LevelData 3/4/7/8/9)를 맞추는 정답 회전과 GlobalData:4, 크랭크 22 = 100이 있어야 204를 보낸다.
  - 그다음 PAND00D와 203 → 204 → (PAND00C 다시 적재) 202 → 205 → `LeverOn`으로 오간다.
  - 하네스는 크랭크를 한 번씩만 돌리므로 퍼즐을 풀지 못한다. 게임은 두 슬롯이 이벤트 큐를 공유하므로 플레이어가 풀면 그대로 동작한다.

## 5. 링 크랭크와 PAND00C 고리 퍼즐

**링 크랭크** (Push Type 4 → `0x212998` 모드 2 = `0x212F58`) [확실: 디스어셈블]:
- 오브젝트 애니가 고리 한 바퀴다 (CoreCrankRing 12 초). 시간은 길이로 감기고 완료가 없다.
- 붙어 있음: 크랭크 축 방향 입력 > 0 → 속도 += Acc·dt (최대 +0x38), 입력 < 0 이고 +0x108 비트 4 (`#228882e3`, 양방향) → 역회전, 입력 없음 → Dec·dt 로 0 쪽.
- 놓음 (`0x2131FC`): 비율 f 가 **멈춤 자리 0·¼·½·¾** ± 허용 (+0x40, `#a4c814e9`, 기본 0.03) 안이면 접근 가속(+0x34, `#b6ba95a3`)으로 그 자리까지 (최대 +0x3C) 가서 멈춘다. 밖이면 그 자리에 멈춘다. f ≥ 1 − 허용은 0 자리.
- 템플릿 기본값 (`0x216788`): Acc 1, Dec 1, +0x34 2, +0x38 1, +0x3C 2, +0x40 0.03, +0x108 0. TWK 덤프는 `count` 개수까지만 유효하다 (Push/051 은 6 개 → 나머지 기본값).
- 엔진 `TaskProgress.ring` / `detent`, `HeroNavigator` (스틱 앞/뒤 = ±입력 [근사: 원작은 영웅 이동 방향 · 크랭크 관절 축 `0x2117E0`]). 원작은 |목표 − f| < 1e-4 에서 붙이고, 엔진은 프레임 사이에 목표를 지나면 붙인다 [근사].

**기중기형 크랭크** (Push Type 3 → 모드 1 = `0x212DE0`, PAND04C "Pand04CElevs"·PAND04G "Pand4GCrane") [확실]: 붙어 있음 — 입력 > 0 → +Acc·dt, < 0 → −Acc·dt (±최대), 없음 → Dec·dt 로 0 쪽. 놓음 — Dec 로 멈춤 (되감지 않음). 시간은 [0, 길이] 에서 멈추고 (`0x212EA0`) 완료가 없다. 엔진 `TaskProgress.crane`.

**대포 크랭크** (Push Type 2 = AthnGun / AthensHarpoon, ATHN02C 발리스타 `gobiggun1crank` Push 202) [확실: 0x212AD0 · 0x2100E0 / 근사: 차기 시점]:
감기는 Type 1 과 같은 Acc/Dec 지만 끝에서 멈추고 1023 은 길이 − 0.01 (99 %) 로 보고 → 다 감긴 채 차기 (+0x29A 비트 2, 영웅 ANIStartKickBox) 가 발사 → 100 %
(BigGun1Crank-EvtTaskProgress100 → 1020 화살) → 스크립트 1015 리셋으로 되감아 0. 엔진 `TaskProgress.cannon / wound / fire / rewind`, `HeroNavigator.cannon`, `Interactables.Item.cannon()`.
**종류 = Push "Type" 바이트** (+0x264): 0·7·8 밀기 상자, 1 크랭크, 2 대포, 3 기중기, 4 링, 5 레버, 6 물속 레버 — 갱신 0x212398 과 1015 리셋 0x2100E0 (표 0x2B9420)
이 Type 으로만 고른다 [확실]. 2026-10-24 앞 판은 이름 (crank/lever/puller) 으로 골라 AthnGun·CoffinLid·HadesHead·Pand04CElevs 등 11 개가 밀기 상자였다 (43 §14.2, 38 §3).

**크랭크 입력** (`0x2117E0`) [확실]: 영웅 이동 방향 (수평, 정규화) · 붙잡은 손잡이 관절의 −Z (붙잡을 때 비트 31 이면 +Z). 붙잡기 조건 (`0x1C3918`): 관절 위치 − Z·오프셋·16 에서 영웅이 +Z 쪽 (내적 > 0), 수평 거리 < 반경·16, 높이 차 < +0x24·16, 영웅 정면 · Z < −cos — 즉 영웅이 손잡이를 바라본다. 그래서 −Z ≈ 영웅 정면이고, 엔진은 스틱 방향 · 영웅 정면 (> 0.3 밀기, < −0.3 당기기) 으로 계산한다 [근사: 관절 행렬 대신 영웅 방향]. Type 1 크랭크도 전에는 스틱 크기만 보던 것을 미는 방향으로 바꿨다.

**오브젝트 자세 = 진행 시간**: R1 오브젝트(레버·크랭크·CSM 문·TAndF)의 관절 자세를 과제 진행 시간으로 정한다 (`Platforms.of(…, posed)` — 충돌 볼륨 없는 오브젝트도, `Platforms.poseAt`, `SceneNav.poseItems`). Marker 가 관절을 따라가고, 렌더도 애니 자세다 (CSM 문이 열리는 모습 등).
- PAND00C: Marker 101 (`goring1crank` 관절 1) → `R1SouthVol` → LevelData 3, Marker 104 (`goring4`), Marker 103 (`goring3group4`).

**움직이는 오브젝트 충돌**: 자세를 갖는 오브젝트(Animator 발판·회전방, R1 문)의 CDV 볼록 hull 이 영웅을 막는다 (`Platforms.collideSphere`) — hull 관절 로컬에서 평면 n·x + d 의 부호 거리 최댓값이 반지름보다 작으면 그 평면 법선으로 밀어낸다 [근사: 모서리 영역도 면으로], 최대 3 회. 전에는 문·창살을 그냥 통과했다.
- 스크립트 Vis 슬롯 3 `Attr11_0` (1 켬 / 2 끔) = 충돌 [추정: "collisionOFF"·"DoorCollOff"·"XFCcaptainCSM-main-collisionOFF" 등 데이터 용례]. `GameScene.vis` → `SceneNav.setSolid`.
- 붙잡는 오브젝트(레버·크랭크·밀기 상자)는 영웅이 손잡이에 닿아야 하므로 뺀다. 적은 아직 막지 않는다.
- **다른 슬롯 레벨** (2026-10-20): 이웃 스크립트 Animator 가 움직이는 이웃 오브젝트도 같은 규칙 — `NavWorld.setNeighbourPlatforms` (바닥 `floor`·실려 감 `carry`)
  + `NavWorld.collideBodies` (이 레벨 + 이웃 hull), 이웃 Vis 슬롯 3 → `SceneNav.applyCollision` (이웃 발판 `setSolid`), 이음 넘기기 `Platforms.State.solid` (43 §13.8).
  예: DEST02 에서 본 DEST01 `goenddoor` — 닫히면 hull (y 516–668) 이 막고, `EndDoor-AnimOpen` (모드 8, 3 초) 이 hull 을 104 내리고 `EndDoor-DoorCDOff` (Attr11_0 = 2) 가 끄면 지나간다
  (`NeighbourDoorCollisionTest`). 예전에는 이웃 hull 을 아예 보지 않아 닫힌 이웃 문도 통과했다 (정적 시트에는 문이 없다).

**고리 퍼즐 흐름** [확실: 데이터]:
1. 고리 3 개를 남쪽 자리에 둔다 → LevelData 3/4/7, 레버 112·113 = 100 → LevelData 8/9.
2. PAND01BB 석상 크랭크에 손잡이 (질문 1005 예) → GlobalData:4.
3. PAND00D 크랭크 120 → 203 → PAND00C 눈 문 레버가 보임 → 크랭크 22 = 100.
4. PAND00D 가 202 를 받고 GlobalData:5 면 205 → PAND00C `ElevatorHousing-EventSwitchStatue` → `ElevatorLever-LeverOn`.

**하네스**:
- 링 크랭크마다 네 자리를 원작 식으로 돌려 보고 (붙잡고 정회전 → 놓아 멈춤 자리로), 참인 LevelData bool 이 가장 많은 자리에 둔다 (`solveRing`) [하네스 탐색]. 보고서 `RING <오브젝트> → k/4`.
- 2 단계를 결과가 바뀌지 않을 때까지 (최대 3 회) 되풀이한다. 숨김 표시가 남은 레벨 외에, 이웃이 **새로 보낸**, 이 레벨이 듣고 스스로 보내지 않으며 아직 받아 본 적 없는 전역 이벤트가 있는 레벨도 다시 돌린다.
- 결과는 숨김 표시가 줄거나 보낸 전역 이벤트가 늘면 바꾸되, 원래 실행의 요청(적재·LoadCheck·Goto·Warp·전환)을 합친다 — 퍼즐 전·후 상태가 둘 다 게임에서 가능한 경로이기 때문이다 (PAND00C: 전 = LoadWad 03J, 후 = 03F).
- 결과: **숨김 상태 상호작용 0** (ATHN01D·PAND01BB·PAND00C 모두 보이는 상태에서 쓰임).

## 5.1 말하는 석상 (ATHN02A 부두 아테나 aoXSA) · 버튼을 기다리는 메시지 (2026-10-23 QA vs PS2 영상 2120–2132 초)
- 스크립트 [확실: 데이터]: `SpawnEnemy shipGroup-topDeck-AthenaMime-aoXSAAI1` (AI1 307, 그룹 300). 안내 볼륨 `AthenaMime-volume-useWorld` (mode 2, 1024) →
  Idle 참, `Goto MimeSpot`, CMV 끔, `AthenaCam` → `talky0` (LevelData:5 = 0: 1019 기술 0 AOXSA1A02MI03, 메시지 **218** "아레스를 파괴하라…") 또는
  `talky1` (= 1: 기술 1, 메시지 **322** "오라클을 찾으면…") 번갈아 → 1040 (218/322) → CMV 켬, Idle 거짓, `quitMime` 1019 −1. 전투 파일 307 은 대사 동작
  10 개뿐 (공격·피격·사망 분기 없음).
- **몸이 없다**: ATHN02A 에 `CDV_aoXSA` 가 없다 (aoxsa CXT 는 ANM·MDL·사운드 이미터만) — 다른 레벨 AI (aoxpt/aoxf1/aoxor …) 와 R_ 적 32 종은 모두 CDV 가 있다
  [확실: 데이터, 헤드리스 조사]. 원작 공격 접촉은 CDV 구 충돌 메시지 (0x203150 → Combat_OnContact) 로만 생긴다 [추정: 다른 접촉 경로 없음, 10 문서 §5.4].
  엔진은 CDV 가 없으면 정면 구 근사로 맞혀 HP 20 석상이 쓰러졌고, 봇·자동 조준이 석상을 적으로 골랐다 → `EnemyFighter.bodiless` (CDV 없음) = 무적·영웅 대상
  (`GameScene.nearestEnemy`) 에서 제외 [추정: 대상 선택도 몸 기준].
- **버튼을 기다리는 메시지** [확실: 코드]: `ShowMessage(id, 초 ≤ 0)` (0x21D980 → GameMgr +0x1528/+0x153C) 는 쪽 표시 0x1B96D0 에서 0x1B9F60(gm, 1) →
  0x1BF080(슬롯 1, 1) = 전역 정지 (스크립트 Idle 과 같은 0x29DB58, 슬롯만 다름) 를 걸고, `MessageTemplate_Style` (+0x9C) = 1 → HUD `XButtonOnOff` 프레임 1
  (메시지 500–599 는 +0x1578 → 2.5 초 뒤 `MessageTemplate_Event` 10 으로 늦게). 갱신 0x1B8C00 은 시간 대신 확정 버튼 (0x29DB5C 가 4·5 면 패드 비트 5 ○,
  아니면 비트 6 ×) 을 눌러야 다음 쪽 / 닫고 1040 을 보낸다. 엔진은 예전 4 초 뒤 저절로 닫았다 → ○ 를 기다리고 전역 정지 (조작 잠금·HUD 내림·적 정지)
  [한국판 ○ — 영상의 ○ 아이콘과 일치]. AUTO·봇은 1.5 초 뒤 ○ [엔진 전용].
  **정정 (2026-10-24, 43 §25.3)** [확실: 디스어셈블]: 0x1B9F60 은 +0x153C 가 **정확히 0** 이면 0x1BF080(슬롯 1, 0) (정지 안 함) · 0x21D970(1), 음수일 때만 0x1BF080(슬롯 1, 1).
  ○ 대기는 둘 다 같다. 초 0 = 첫 안내 500–599 (0x1DBEB0, $f12 = 0) · 스크립트 ShowMessage 0 초 62 곳 (궤짝 · 세이브 · 아레나 시험 안내 …) → 게임이 계속 돌고 HUD 미터도 규칙대로
  (영상 8056 제우스의 분노 안내 509 동안 미터가 떠 있다). 이 석상 218/322 와 쌍둥이 317/319 는 −1 이라 그대로 정지 (`GameScene.messageIdle`).
- 버튼 안내 아이콘: R2 로 1024 를 보내면 아이콘을 지우고 (0x1DC3F8 → 슬롯 1 −1) 볼륨을 나갔다 다시 들어올 때만 다시 켠다 (0x1AAC88) — 예전엔 대사 상자 위에
  R2 가 남았다. 전투 대기 IDLECOMBAT (M80) 중에도 R2 를 받는다 (원작 0x1DC3F8 은 전투 기술을 보지 않음; 엔진은 싸운 뒤 R2 가 먹지 않았다) [근사: 공격 중은 막음].
- 결과 (`--game ATHN02A --arrive ATHN01F@671,-624,3940,0`, □ 3 번 뒤 R2 → ○ → R2 → ○): 석상 HP 20 그대로, 메시지 218 (○ 아이콘, HUD 내림, ○ 까지 유지)
  → 1040 → 322 → 1040. AUTO 봇은 석상을 적으로 보지 않는다. 시험 `combat.MimeStatueTest`.
- 남은 것: `AthenaCam` 화면이 석상이 아니라 물·부두를 비춘다 (ATHN01F `camVase`·`TwinCamLeft` 도 비슷 — GO 에 딸린 CAM 의 기준 변환 의심, 카메라 쪽 미확인).

## 6. 테스트

| 테스트 | 내용 |
|---|---|
| `verify/TraversalTest.backtrackingNeedsNoForcedEvents` | 세 전환이 강제 없이 열리고, 강제 뒤에만 나온 전환이 하나도 없음 |
| `game/QuestionMenuTest` | 질문 본문 → 줄·선택지·기본 선택·프레임 12 |
| `nav/TaskProgressTest.ringCrankSnapsToQuarterDetents` | 링 크랭크 가속·멈춤 자리 복귀·자리 밖 정지·감기·단방향 |
| `nav/TaskProgressTest.craneCrankIsTwoWayAndBounded` | 기중기형: 끝에서 멈춤·완료 없음·역방향·놓으면 Dec 정지 |
| `verify/TraversalTest.hiddenObjectsAllRevealed` | 숨김 상태 상호작용 0, PAND00C 링 크랭크 풀이 |
| `nav/BodyCollisionTest` | ATHN01D 쌍발차기 문 hull 안 → 밀려남, 충돌 끔이면 통과 |
| `nav/AnimatorModeTest` | 회전방 정방향 → Marker 202 → LevelData:1 참 / 역방향(끝에서 시작) → 거짓 / 0x10 정지·0x11 이어서·0x8 시간 유지 / `$` = 재질 시계 (관절 그대로), 없는 재질 거짓 |
| `game/ScriptMaterialAnimTest` | ATHN02D 남쪽 문: 0x42 doorSub 는 스크립트 전 시간 0, 0x41 반복, subShaderAnim1 → 7.667 초에서 멈춤 (p28 0.5 → 0), 이음 넘기기, "MAT_" 이름 규칙 |
| `game/BallistaPuzzleTest` | ATHN02C 회전판 멈춤 자리 0x12 (3 / 6 / 역 3 초), jump_if 거짓이면 점프, DestructionSensor Filter, 대포 크랭크 99 %·발사·되감기, 화살 → 문 (43 §14) |

## 7. 남은 것

- ~~1024 실제 버튼~~: R2 [확실], 엔진 반영 (마법 L2, 대시 R1).
- ~~질문 메뉴 UI·아니오 값~~: 원작 HUD 메뉴 템플릿, 아니오 = 2 (§2).
- Animator 재생 방식 4/1/2 이름과 0x100A60 내부. ~~재질 애니(`$…`, 모드 10/11/16)는 미구현~~ → §3 `Platforms.MatClock` (모드 10/11 의 재생 방식 1 = 반복 [추정]).
  대상 GO 모델에 그 재질이 있는지는 보지 않는다 (재질 ANM 이 있으면 시계를 만든다) [근사].
- ~~발판 애니 렌더 자세~~: 애니가 한 번이라도 돈 오브젝트는 `LevelScene.poseInstance` → 구운 메시 대신 `AnimatedModel`(관절 월드 = 애니 × 인스턴스)로 그린다 (PAND02AA 회전방 벽·레버 확인). 관절 숨김 (byte 트랙) 도 함께 넘긴다 (`Platforms.Body.hidden`, DMC 결계 dogIn/dogOut). 애니 전 자세 = 인스턴스 시작 act (43 §6, `StartPose`).
- ~~PAND00C 고리 퍼즐 풀이(하네스)~~ (§5). 크랭크 입력은 영웅 정면 근사 (손잡이 관절 행렬·비트 31 쪽 미사용).
