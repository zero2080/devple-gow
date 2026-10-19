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
IN_GAME ─사망→ DEAD (deaths++) → LEVEL_LOADING | SHELL_LOAD
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
  - 검증: `ShellNewGameTest.chooseHardDifficulty` — 새 게임 ○ → ↓ → ○ 로 `PS2_Difficulty_Level=2` (영웅).
- 불러오기(2)는 세이브 미구현이라 새 게임으로 시작.
