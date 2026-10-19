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
  엔진은 R2를 마법에 쓰고(10 문서 §16 미확정) R1을 상호작용에 쓰므로 **R1**으로 보낸다 [근사]. 아이콘은 원작대로 R2를 보인다.
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
- 엔진 `GameScene`: 질문 메시지를 띄운 채 ×(예 = 1) / △(아니오 = 0)를 기다려 1021을 보낸다 [근사: 원작 메뉴 UI 대신 메시지 + 버튼, 아니오 값은 PAND01BB의 2와 다를 수 있음].
- 하네스: 질문에는 항상 "예"로 답한다.

## 3. Animator 효과 (0x1AB0B8) [확실: 코드]

| 슬롯 | 의미 | 기본값 (생성자 0x1A2CE0) |
|---|---|---|
| 0 AnimName | `!n` = n 번째 act, `$이름` = **재질(텍스처) 애니** (`MAT_…`), 그 밖 = act 이름 해시 | "" |
| 1 모드 비트 | 0x10 일시 정지 · 0x11 이어 재생 (단독 값) / 0x1 역재생 (속도 부호 반전, 시작 ≤ 0 이면 끝에서) / 0x8 시간 유지 / 재생 방식 0x100A60: 0x4 → 4, 0x2 → 1, 그 밖 → 2 | 0 |
| 2 속도 | 재생 속도 | **1.0** (0x1A2D50) |
| 3 시작 시간 | 모드 0x8이 아니면 여기서 시작 | 0 |

- 재생 방식 4·1·2의 이름(왕복·반복·한 번)은 [추정]이다. 데이터: 레버 8/9(정·역 이어서), 회전방 0/1, 재질 10/11/16.
- 엔진 `Platforms.play(owner, anim, mode, speed, start)`:
  - `$` 이름은 무시(관절 애니 아님).
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

## 5. 테스트

| 테스트 | 내용 |
|---|---|
| `verify/TraversalTest.backtrackingNeedsNoForcedEvents` | 세 전환이 강제 없이 열리고, 강제 뒤에만 나온 전환이 하나도 없음 |
| `verify/TraversalTest.hiddenObjectsNeedOnlyTheRingPuzzle` | 숨김 표시 = PAND00C 엘리베이터 레버뿐. ATHN01D·PAND01BB는 이웃 상태로 다시 돌림 |
| `nav/AnimatorModeTest` | 회전방 정방향 → Marker 202 → LevelData:1 참 / 역방향(끝에서 시작) → 거짓 / 0x10 정지·0x11 이어서·0x8 시간 유지 / `$` 무시 |

## 6. 남은 것

- 1024 실제 버튼(R2?)과 마법 버튼 배치 — 10 문서 §16과 함께 확인.
- 질문 메뉴 UI(원작 `Menu %d`, Yes/No 선택 커서)와 아니오 값.
- Animator 재생 방식 4/1/2 이름과 0x100A60 내부. 재질 애니(`$…`, 모드 10/11/16)는 미구현.
- 발판 애니가 렌더 자세에 반영되는지 확인(충돌·Marker만 확인함).
- PAND00C 고리 퍼즐 풀이(하네스).
