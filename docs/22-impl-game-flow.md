# 22. 구현: 씬 흐름 / 게임 상태 머신 (엔진)

명세: [11-progression-save-flow](11-progression-save-flow.md) §6. 패키지 `devple.gow.engine.game`.

## 1. 구성
| 클래스 | 원작 대응 | 역할 |
|-------|----------|------|
| `GameState` | `g_gameState` 0x29F428 (값 0–18) | 상태 열거. 번호를 원작 값과 동일하게 유지 |
| `GameFlow` | `GameState_Tick` 0x21DB20, `GameMgr_OnMessage` 0x1B01E8 | 전이 규칙 + 스크립트 메시지(Load/LoadWad/Warp/LoadCheck) |
| `WadPlan` | 힙 배치 (Perm/Shell/Hero/Upgrade/Levels/Slots) | 상태·진행도로 메모리에 있어야 할 WAD 목록 계산 |

## 2. 전이
```
BOOT → SHELL_LOAD → SHELL_MENU ─새 게임→ NEW_GAME → LEVEL_LOADING ─로드 완료→ IN_GAME
                              └불러오기→ LOAD_CARD → …
IN_GAME ─START→ PAUSE ─계속→ IN_GAME / ─체크포인트→ LEVEL_LOADING / ─종료→ SHELL_LOAD
IN_GAME ─SELECT→ POWER_UP_MENU → IN_GAME
IN_GAME ─사망→ DEAD (deaths++) → LEVEL_LOADING (스냅숏 되읽기, §8) | SHELL_LOAD
IN_GAME ─FailedScreen→ DEAD (실패 메뉴, 같은 상태 0xE) → LEVEL_LOADING (스냅숏 되읽기) | SHELL_LOAD
IN_GAME ─세이브 포인트→ SAVE_POINT → SAVE_DONE → IN_GAME
스크립트: LoadWad(w) → pendingLevel=w (다음 WAD 백그라운드 로드) … Warp → level=w, LEVEL_LOADING
```
- 화면 전환(페이드)·실제 로딩은 `onStateChange` 리스너가 엔진 `SceneManager` 에 연결한다 (레벨 로더 구현 시).
- 원작은 START = 패드 비트 0x100, SELECT = 0x800 으로 추정 (11 문서 §9 미확정) → 엔진 `PadButton.START/SELECT` 에 매핑.

## 3. WadPlan 예
| 상황 | 필요한 WAD |
|------|-----------|
| 셸 메뉴 | `R_PERMK, R_SHELLK` |
| 새 게임 직후 ATHN01A | `R_PERMK, R_HERO0, R_SAVEPT, R_WPN0_0, ATHN01A` |
| 포세이돈 획득 + LoadWad(Athn01B) | … + `R_LGHTN0, ATHN01B` |

## 4. 검증
`GameFlowTest` (새 게임 → 플레이 → LoadWad/Warp, 사망 → 재시작) 통과. 전체 엔진 테스트 8개 통과.

## 5. 미구현
- 메모리카드 불러오기/저장 흐름(LOAD_CARD, SAVE_WRITE)과 세이브 직렬화 → 자체 포맷으로 구현 예정
- `Goto`, `ZoneReport`, `ShowItemCard`, `CompleteGame` 메시지는 ESC 스크립트 VM 구현 시 연결

## 6. 실제 화면 흐름 연결 (ShellScene → GameScene)
`./gradlew :engine:run` (인자 없음) = 원작처럼 **메인 메뉴부터** 시작.

| 원작 상태 | 엔진 |
|----------|------|
| 1–3 ShellLoad/ShellMenu | `game/ShellScene`: R_SHELLK `FLP_ShellK` 실행 |
| 메뉴 결과 | 스크립트가 `PS2_Menu_Choice`(1 새 게임 / 2 불러오기) + `PS2_Done = 1` |
| 4 NewGame → 9 LevelLoading → 11 InGame | `GameSession`(난이도) 생성 → 페이드 → `GameScene(ATHN01A)` |
| 레벨 전환 | 스크립트 LoadWad/Warp → 새 `GameScene` (27 문서) |

- `ShellNewGameTest`(헤드리스): 원작 메뉴에서 ○ → `PS2_Menu_Choice=1, PS2_Done=1` 확인.
- **엔진 제공 셸 변수 (`ShellBinding`)**: FLP_ShellK 는 아래 `PS2_*` 변수를 읽기만 하므로 엔진이 시작 시 넣어 준다.
  - `PS2_Difficulty_Level` — 난이도 커서 초기값 (기본 1 = 보통). 메뉴는 선택 결과 `_level0/:ShellDifficulty` 를 다시 이 변수로 복사 → 게임 시작 시 `GameRules` 난이도로 사용. 값이 비어 있으면 난이도 화면이 결과를 쓰지 않는다 (이전 버그 원인).
  - `PS2_EnableButtons=1` (입력 허용), `PS2_HardMode_Unlocked` / `PS2_Costumes_Unlocked` (세이브 해금 비트 0x7 / 0x8).
  - 옵션: `PS2_SoundVolume`·`PS2_MusicVolume` (0..10 → `SoundManager` 그룹 0/1 볼륨 = 값/10, 변경 리스너로 즉시 반영), `PS2_Vibration`, `PS2_WideScreen`, `PS2_ProgressiveScan`, `PS2_DeFlicker`, `PS2_SoundMode` (값만 보관).
  - `PS2_Costume_Choice`: 의상 해금(`Costumes_Unlocked`, 해금 비트 0x8) 이면 난이도 다음에 의상 화면(CostumeOn, ←/→) 이 나오고 선택을 이 변수에 쓴다 →
    `GameSession.costume` → `HeroRig` 가 `R_HERO<n>` / `R_WPN<n>_<레벨>` 과 `/Player/Costume n` 배율 사용 (11 문서 §2.4). 세이브 불러오기도 세이브의 코스튬을 쓴다
    (이전에는 항상 0 으로 덮어썼음). 디버그: `--game LVL --costume n`. 검증 `ShellNewGameTest.chooseCostumeWhenUnlocked` (→ 한 번 = 의상 1, 요리사).
  - 검증: `ShellNewGameTest.chooseHardDifficulty` — 새 게임 ○ → ↓ → ○ 로 `PS2_Difficulty_Level=2` (영웅).
- 불러오기(2) → §7 메모리카드 화면.

## 7. 메모리카드 화면 (`MemoryCardFlow`)

원작 메모리카드 UI 는 FLP 안의 `SaveLoadMenu/MemoryScreen1..27` 클립이고(셸 FLP_ShellK, 게임 중 FLP_HUDK 둘 다), **순서는 실행 파일이**
정한다 (MemCard_ReadFlow 0x230248 / MemCard_WriteFlow 0x230A98, 11 문서 §5.1). 엔진 ↔ FLP 규약:

- 엔진 → FLP: `PS2_MemoryCard_Event = n` → 루트 SimKeyEvent 가 이전 화면 AnimateOff, `MemoryScreen<n>` AnimateOn, `MemoryCardState = n`.
  `0` = 메뉴 닫기 (`SaveLoad_Off` → 셸은 MainWait 로 복귀). 슬롯 표시 변수 `PS2_MemoryCard_CardSlot`, `PS2_MemoryCard_Name/Date/PlayTime1..4`.
- FLP → 엔진: `FSCommand:PS2_MemoryCard_Select arg` — ○ 확인 화면(2,4,5,8,12,14,18,19) = 1, 예/아니오 화면(3,10,15,16,24,26,27) = 1/0
  (기본 커서 = 아니오, ↑↓ 로 전환), 카드 선택 21 = 1/2, 파일 선택 22/23 = 1..4 (←/→ 이동), △(21/22/23) = 0.
- 21/22/23 에서 고르면 FLP 가 `EnableButton = 2` 로 입력을 잠그고 다음 화면을 기다린다 → 엔진은 선택을 무시할 때도 같은 화면을 다시 보낸다.

화면 번호 (FLP 텍스트로 확인):

| n | 화면 | n | 화면 |
|---|------|---|------|
| 1 | 카드 발견 | 14 | GOW 파일 없음 (○) |
| 2 | 카드 없음 (○) | 15 / 16 | 덮어쓸까? / 새 파일을 만들까? (예/아니오) |
| 3 | 포맷할까? | 17 | 저장 중 |
| 4 | 여유 공간 부족 (○) | 18 | 저장 완료 (○) |
| 5 | 읽기 오류 (○) | 19 | 저장 오류 (○) |
| 6 / 7 / 8 | 포맷 중 / 포맷 완료 / 포맷 오류 | 21 | 카드 슬롯 선택 (1/2) |
| 9 | 공간 부족 (저장 중 경고) | 22 | 저장할 파일 선택 (1..4) |
| 10 | 불러올까? (예/아니오) | 23 | 불러올 파일 선택 (1..4) |
| 11 | 불러오는 중 | 24 / 27 | 공간 부족 — 그래도 시작할까? |
| 12 | 불러오기 오류 (○) | 26 | 귀중품(해금) 데이터만 있음 — 불러올까? |
| 13 | 불러오기 완료 | 20 / 25 | 빈 화면 |

엔진 순서:

| 모드 | 흐름 |
|------|------|
| 불러오기 (셸 `PS2_Menu_Choice=2`) | 21 → 23 → 10 → 11 (1초, 읽기) → 13 (1초) → 0 → `GameScene(SaveGame)` |
| 저장 (세이브 포인트 R1) | HUD `PS2_SaveGame_Event=1` "저장하시겠습니까?" → 예(`Menu_Choice=1, Done=1`) → 21 → 22 → 15/16 → 17 (1초, 쓰기) → 18 (○) → 0 |
| 오류·취소 | 2 / 14 → 21, 12 → 23, 19 → 22, △ 21 → 0 (취소), △ 22/23 → 21 |

- 카드 1 = `~/Library/Application Support/devple-gow/slot0..3.save` (파일 4개), 카드 2 = 없음 (화면 2).
- **세이브 질문 열기·닫기** (원작 상태 15 0x2204E0 → 17 0x220608 → 18 0x220740, 디스패치 0x21DB20 표 0x2B96B0) [확실: 코드]:
  - 열기 0x21D8B0: 0x29F47C = 1, HUD SaveGame_Event (+0x268) = 1, Menu_Choice (+0x278) · Done (+0x27C) = 0, 미니게임 아이콘 뗌 (0x2207F8 → 0x1BA150 → 0x1BA338(−1)).
  - ○ 에서 FLP 는 SaveGameMenu 를 "Off" (질문만 숨김 — **Ω 배경은 남음**) + Pause_Check 10 → 0, Menu_Choice · Done = 1.
  - 아니오 → 상태 18: 첫 프레임 MemoryCard_Event (+0x274) = 0, 10 프레임 (0x29F468) 뒤 SaveGame_Event = 0 (FLP Animate_Off) → 상태 11 (놀이).
  - 예 → 상태 17 쓰기 흐름 0x230A98: 끝 (≠ −1) 이면 MemoryCard_Event = 0, 10 프레임 뒤 상태 18 (1 + 10 프레임) → SaveGame_Event = 0.
    카드 선택 △ (흐름 −1, 0x2A2730 ≠ 1) 이면 10 프레임 뒤 SaveGame_Event = 1 · Menu_Choice · Done = 0 → 상태 15 (질문으로 돌아감).
  - 엔진 (예전): SaveGame_Event 0 을 보내지 않아 저장·아니오 뒤 Ω 배경이 화면에 남았고, Done 1 이 남아 두 번째 세이브 포인트의 ○ 가 "바뀜" 으로 들어오지 않아
    질문이 닫히지 않았다 → `GameScene.askSave` (Done·Menu_Choice 0) · `closeSaveMenu` (11 / 21 / 10 프레임, 60 Hz 로 초 환산 [근사]) (43 §18).
    시험 `AthensInteriorTest.saveMenuNeedsEventZeroToClose`.
- 파일 표시 = 원작 `Save_SlotText` 0x22E810 처럼 난이도 이름(메시지 3–6) / 저장 날짜 / 플레이 시간 `h:mm:ss` (`SaveGame.playTime`, 일시정지가 아닐 때 누적),
  빈 파일 = 메시지 2 "비어있음".
- 포맷·공간 부족·귀중품 화면(3–9, 24–27)은 PC 에서 일어나지 않아 쓰지 않는다.
- 검증: `MemoryCardFlowTest` (원작 FLP 헤드리스) — 셸 불러오기 끝까지 / 빈 카드·카드 2 없음·△ 취소 / HUD 저장 질문 → 새 파일 저장.
  화면: `./gradlew :engine:run --args="--ui gow-rom/R_SHELLK.WAD FLP_ShellK memcard --ui-warm 120"` → 파일 선택 화면 (`work/uirt/memcard23.png`).

## 8. 체크포인트 스냅숏과 재시작 (11 문서 §5.3 · §5.6, 43 §26)

원작 체크포인트는 세이브와 같은 직렬화를 메모리 버퍼에 쓰는 것이고 (0x22D418), "마지막 체크포인트에서 다시 시작" (사망 메뉴 · 실패 메뉴 · 일시정지 메뉴 → 0x21E3D0)
은 그 버퍼를 되읽어 두 슬롯 레벨을 다시 올린다 (상태 9) [확실]. 엔진:

| 단계 | 원작 | 엔진 |
|------|------|------|
| 찍기 | CheckPoint 프레임 끝 0x1B3070 (점프·떨어짐·줄이면 미룸), 새 게임 적재 끝 (상태 9 — 불러오기는 읽은 버퍼가 곧 스냅숏), 세이브 포인트 R2 0x22D390 | `GameScene.checkpointFrameEnd` · 첫 틱 · R2 → `takeCheckpoint` → `GameSession.checkpoint` (`SaveGame`), 불러오기 = 그 세이브 |
| 담는 것 | 스트림 전부 (두 슬롯 이름, PlayerProgress, 카메라, 음악, GameMgr, 옵션, 세계: GlobalData · LevelData 풀 · 레벨 상태 표 + 두 슬롯 덩어리) | `SaveGame.capture` + 다른 슬롯 · 이동 상태 · 타이머 · 초기화 기록 · 음악 이름, 레벨 기억 (`ScriptWorld.Memory`: 발동 기록 · 살아 있는 적 · 미뤄 둔 발동 · Animator 몸 · 부순 물체) |
| 되읽기 | 0x22D440 → 명령 0xF/0x10 → 0x22D660 → 0x1B4450 · 0x1B4528 → "Restarted or loaded" 0x22DA88 (사망 수 · 첫 안내 비트 유지, 체력 규칙, 쉬움 0x29F484) | `restartFromCheckpoint` → `SaveGame.applyCheckpoint` → `new GameScene(session, 스냅숏)` (다른 슬롯 · 레벨 기억 · 벽 다시 붙이기 · 음악) |

- 메모리카드 저장은 R2 때 찍은 스냅숏을 쓴다 (`GameScene.onSaveAnswered`), 불러오기 (`SaveGame.applyTo`) 도 같은 세계 블록 · 다른 슬롯을 쓰고 읽은 세이브를 그대로
  체크포인트로 둔다 (0x21FB70 → 0x21FC28 a0 = 0).
- 음악: 원작 음악 그룹은 전역이라 씬이 떠날 때 지금 음악 이름을 `GameSession.music` 에 넘기고 새 씬이 다시 튼다 (되읽기면 스냅숏의 음악) [근사: 이름만].
- 하네스 [엔진 전용]: 경로 단계 `die:` (영웅 HP 0) · `break:소유GO` (부서지는 물체), `-Dgow.auto.deadmenu` (자동 플레이도 사망 메뉴를 거쳐 ○).
- 시험: `CheckpointRestartTest` (7), `HeroNavigatorTest.restoredClimbSnapshotReattachesToTheWall`.
