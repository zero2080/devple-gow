# 39. 구현: 귀중품(보너스) 메뉴 (BonusMenu)

원작 셸 메뉴 FLP_ShellK(R_SHELLK.WAD)의 네 번째 항목 **귀중품**(`PS2_4515`, 메시지 4515) 화면과 그 뒤의 실행 파일 로직을 분석하고
엔진(`game/BonusMenu`)에 옮겼다. 관련: [07](07-ui-script-text.md) FLP, [11](11-progression-save-flow.md) §2.3 해금 비트, [18](18-video-format.md) 동영상 이름,
[22](22-impl-game-flow.md) §6–7 셸 연결 방식, [33](33-impl-video.md) 재생기.

```bash
./gradlew :engine:run -PgowUnlockAll                          # 메인 메뉴 → 귀중품 (전부 해금)
./gradlew :engine:run --args="--ui gow-rom/R_SHELLK.WAD FLP_ShellK bonus --ui-warm 400"   # 귀중품 화면 단독 (커서 3칸 아래)
./gradlew :engine:test --tests '*BonusMenuTest*'
```

## 1. FLP 쪽 (FLP_ShellK 루트 타임라인) [확실: FLP 덤프]

### 1.1 메인 메뉴 → 귀중품
- 메인 메뉴 커서 = `_level0/:ShellMenu_State` 1 새 게임 / 2 불러오기 / 3 옵션 / **4 귀중품** (`OpeningUp/Down` 프레임 13/14, 클립 `Treasures`).
- ○ → `MainOff` → 프레임 12 에서 상태 4 이면 `GotoFrame 41` (= `TreasureOn`). **잠금과 무관하게 항상 들어갈 수 있다** — 숨기지 않고, 항목별로 잠김 표시만 한다.
- 시작 시(프레임 1) `PS2_ShellStartState == 1` 이면 곧바로 프레임 41(귀중품) 로 들어간다 → 원작은 보너스 영상 뒤 셸을 다시 띄울 때 이 값으로 귀중품 화면에 돌아오는 것으로 보인다 [추정].
  (3 이면 `On` = 프레임 66.) 엔진은 영상을 셸 위에 덮어 재생하므로 FLP 상태가 그대로 유지돼 이 값이 필요 없다.

### 1.2 화면 프레임
| 프레임 | 라벨 | 동작 |
|-------|------|------|
| 41 | `TreasureOn` | `FSCommand:PS2_BonusScroll 4`, `EnableButton = 0`, `PS2_BonusName1..8` → `_level0/:BonusName1..8` 복사 |
| 46 | – | `EnableButton = 1`, `TreasureLockedCheck` 호출 (47–55 도 매 프레임 호출) |
| 56–57 | `TreasureLoop` | `EnableButton == 1` 동안 `TreasureLockedCheck` 반복, 아니면 `TreasureOff` |
| 59 | `TreasureOff` | `EnableButton = 0` (페이드 아웃) |
| 63 | – | `PS2_BonusScroll 4`, `GotoFrame 2` (메인 메뉴) |
| 64 | `TreasureLockedCheck` | 이름 8개 복사 + `PS2_BonusLocked<n> != 0` 이면 `/BonusMain/Bonus<n>` 프레임 1(회색), 아니면 0 |

- 표시: 클립 `BonusMain` 안에 `Bonus1..8` 줄(동적 텍스트 `_level0/:BonusName<n>`), **4번째 줄 = 커서**(배율 1.2, 밝은 색). 설명은 `PS2_BonusDesc1..3` 를 직접 표시.
- 즉 FLP 는 **목록 내용·커서·스크롤을 전혀 모른다** — 엔진이 커서 기준 8줄을 매번 다시 채운다.

### 1.3 입력 → FSCommand (프레임 46–63 에 놓인 스프라이트)
| 스프라이트 | 버튼 (마스크, 조건) | 결과 |
|-----------|-------------------|------|
| #15 | ↑ / 왼쪽 스틱 ↑ (0x1000/0x10000) 누름 · 뗌 | `PS2_BonusScroll 0` · `1` (+ `SND_MM_NAV_UPDN`) |
| #16 | ↓ (0x4000/0x40000) 누름 · 뗌 | `PS2_BonusScroll 2` · `3` |
| #13 | ○ (0x20) 누름 | `PS2_BonusScroll 5` |
| #17 | □ (0x80) 누름 | `PS2_BonusScroll 6` |
| #18 | ○ (0x20) 누름 | `PS2_BonusScroll 7` (○ 한 번에 5 와 7 이 둘 다 나간다) |
| #14 | △ (0x10) 누름 | `EnableButton = 0`, `SND_MM_BACK`, `TreasureOff` |

↑↓ 는 `EnableButton == 1` 일 때만, ○/□ 는 `PS2_EnableButtons == 1` (엔진 변수) 일 때만. 선택 결과는 **`PS2_Done`/`PS2_Menu_Choice` 를 쓰지 않는다** — 전부 FSCommand.
`PS2_Tmp_Wad 1..5`·`PS2_MoveScroll` 은 이 화면이 아니다 (07 문서 FSCommand 목록).

## 2. 실행 파일 쪽 [확실: 코드]

### 2.1 함수
| 주소 | 이름(가칭) | 역할 |
|------|-----------|------|
| 0x1B8AA0 | `Hud_OnFSCommand2` | FSCommand 디스패처 (함수 표 0x2BBC1C): `"MemoryCard_Select"` → 0x22EC98, `"MoveScroll"` → 0x1BA508, **`"BonusScroll"` → 0x1BAB10** (인자 = `atoi`) |
| 0x1BAA68 | `Bonus_Init` | `Hud_RegisterFlashVars` 에서 호출. 메시지 **900..999** 중 줄이 2개 이상인 것의 개수 → 항목 수(hud+0x14F8), 커서(+0x14FC) = 0, 이어서 0x1BAD40(강제 갱신) |
| 0x1BAB10 | `Bonus_Scroll(hud, arg)` | 점프 표 0x2B7000 (arg 0..7) |
| 0x1BAD40 | `Bonus_Update(hud, force)` | 매 프레임 (0x1B8FE0 HUD 갱신에서). 실행 중 동작 처리 + 스크롤 + 8줄 채우기 |
| 0x1BB140 | `Bonus_FillRow(hud, index, &name, &locked, isCenter)` | 줄 하나 채움. 커서 줄이면 설명 3줄 + 동작 문자열 |
| 0x1B9528 | `Msg_Lines(id, &first)` | 메시지 색인(0x29DA00, {u16 id, u16 첫 줄}) → 줄 수, 줄 표 0x29DA04 |

HUD 변수 오프셋 (`Hud_RegisterFlashVars` 0x1B6490): `BonusName1..8` = +0x110..0x12C, `BonusLocked1..8` = +0x130..0x14C, `BonusDesc1..3` = +0x150..0x158,
`ShellStartState` = +0x1A8, `EnableButtons` = +0x1BC.

### 2.2 `Bonus_Scroll` (0x1BAB10)
실행 중(+0x1520 ≠ 0) 이거나 쿨다운(0x29DA7C ≠ 0) 이면 **4 외에는 무시**.
| arg | 동작 |
|-----|------|
| 0 / 2 | 방향 −1 / +1 (+0x1500), 속도 +0x150C = 1.0, 시작 커서 +0x1504 = 현재 커서 |
| 1 / 3 | 방향 = 0 (멈춤) |
| 4 | 상태 전부 초기화 (방향·실행·쿨다운·연속 재생·레벨·영상), 갱신 플래그 +0x1508 = 1, `sub_0021F4C8` 호출 [미상] |
| 5 | 스크롤 중이 아니면 커서 항목 동작 문자열(+0x1510) 첫 글자로 분기: `M` → `"BonusXX"`(XX = 2·3번째 글자), `A` → `"Arena10"`, `G` → `"Olymp04"`, `S` → 연속 재생 |
| 6 | 동작 길이 ≥ 6 이고 `M` 이면 XX = 5·6번째 글자 |
| 7 | 동작 길이 ≥ 9 이고 `M` 이면 XX = 8·9번째 글자 |

원작 데이터의 동작 문자열은 모두 3글자 이하라 6·7 은 아무 일도 하지 않는다 (○ 가 5·7 을 같이 보내도 결과는 5) [확실: 데이터].

### 2.3 스크롤 (0x1BAD40 끝부분)
```
매 프레임 (방향 ≠ 0):  n = (int)(speed²);  idx = clamp(start + dir·n, 0, count−1);  speed += 2/60
                     idx ≠ 커서 이면 커서 = idx, 8줄 다시 채움 (커서−3 .. 커서+4, 4번째 = 커서)
```
→ 누르는 즉시 한 칸, 약 0.2초 뒤부터 가속 (1초 뒤 속도 2 → 4칸/프레임 단위 도약). 끝에서 멈춤 (순환 없음).

### 2.4 항목 = 메시지 900+i (R_PERMK msgsK.txt)
줄 0 이름, **줄 1 = 잠금 문자 + 동작**, 줄 2..4 설명(열림), 줄 5..7 설명(잠김). 줄이 2개 미만이면 잠금 문자 = `"L"`(0x2B70A0).
커서 줄이 잠겨 있으면 동작 문자열이 `""` 가 되어 ○ 가 무반응, 설명은 잠긴 설명.

잠금 문자 표 0x2B70B0 (문자 − 'A', 해금 비트 = `g_unlockMask` 0x2A2768, 11 문서 §2.3):
| 문자 | 잠김 조건 | 의미 |
|-----|----------|------|
| S | 없음 (항상 열림) | |
| E | `(m & 7) == 0` | 아무 난이도로든 게임 클리어 (CompleteGame 0) |
| H | `(m & 6) == 0` | 영웅(어려움) 또는 신 난이도 클리어 — 문구는 "신 모드를 클리어" 지만 코드는 비트 1 도 허용 |
| G | `(m & 4) == 0` | 신 난이도 클리어 |
| A | `(m & 8) == 0` | 신들의 시험 완료 (CompleteGame 1, ARENA27) — 문구는 "신 모드를 클리어" |
| F | `(m & 16) == 0` | CompleteGame 2 (비트 4) [추정: 의미] |
| L, 그 밖 | 항상 잠김 / 이전 값 유지 | 데이터에 없음 |

### 2.5 실행 (0x1BAD40)
- **영상** (`M`): EnableButtons = 0, `SND_MM_SELECT`, `PlayMovie`(0x1B3440, 13 문서) 모드 0. 단 `Bonus20` 은 모드 3 + 음악 `SND_M_CREDITS1` 시작(0x29DA80 = 1),
  끝나면 `SND_M_TIT1E1_2`(타이틀 음악) 복귀. 영상이 끝나면(0x29D4F8 == 0) 실행 해제 + **쿨다운 60프레임** 뒤 EnableButtons = 1.
- **연속 재생** (`S`, +0x1514 카운터 1..11): 첫 회 음악 페이드(2초) + `SND_MM_SELECT`. 매 영상 종료마다 표 0x29DA98 의 다음 영상
  `Semo01 … Semo10, Semo13` (11개). 두 번째부터는 건너뛰기 플래그(0x29D500, `sub_00189548` 읽고 지움)가 서 있으면 중단. 끝나면 타이틀 음악 + 쿨다운.
- **레벨** (`A`/`G`): EnableButtons = 0, `SND_MM_SELECT`, 레벨 이름 → 0x29F4B0. NewGame 상태(`sub_0021F7C0`)가 0x29F4B0 이 있으면
  `Athn01A` 대신 그 레벨로 시작하고 **난이도 = 1 고정**, hud+0x1C0 = 1, +0x1C4 = 0. 레벨 진입 후(0x21FD48) 0x29F4B0 = 0.
  ShellMenu → NewGame 전이를 누가 일으키는지는 찾지 못함 [미확인].
- 표 0x29D5E8 (`{name, 1, 0/1/2}`, 18 문서의 0x29D528 표 일부): Bonus21/25 (1,0), Semo01 (0,2), Bonus20 (1,2), Bonus01/19/24 (1,1), Bonus20a–e (1,0) —
  동영상 재생 속성 표. 메뉴 로직은 이 표를 직접 쓰지 않는다 [확실: 참조 없음 / 추정: 의미].
- `"%s/Bonus"`(0x1D4F90 `Player_RegisterTweaks`)는 `/Player/Bonus` 히트 마일스톤(10 문서 §11.2)이라 이 메뉴와 무관.

## 3. 항목 표 (메시지 900–916, 17개) [확실: 데이터]
| # | 메시지 | 이름 | 코드 | 잠금 | 내용 | 파일 |
|---|-------|------|------|------|------|------|
| 0 | 900 | GOW의 제작 과정 | SM03 | 열림 | 영상 | BONUS03.PSS (566MB) |
| 1 | 901 | GOW 예고편 | SM21 | 열림 | 영상 | BONUS21.PSS |
| 2 | 902 | 만든이들 | EM20 | 클리어 | 영상 (크레딧, 음악 동반) | BONUS20.PSS (210초) |
| 3 | 903 | 삭제된 레벨 | EM06 | 클리어 | 영상 | BONUS06.PSS |
| 4 | 904 | 영웅의 탄생과정 | EM12 | 클리어 | 영상 | BONUS12.PSS |
| 5 | 905 | 고대 그리스의 재현 | EM14 | 클리어 | 영상 | BONUS14.PSS |
| 6 | 906 | 신화 속의 괴물 | EM10 | 클리어 | 영상 | BONUS10.PSS |
| 7 | 907 | 야수의 탄생 | EM16 | 클리어 | 영상 | BONUS16.PSS |
| 8 | 908 | 신 모드 | E | 클리어 | 안내만 (새 게임 난이도에 "신" 추가) | – |
| 9 | 909 | 캐릭터 공동 묘지 | EG | 클리어 | 레벨 OLYMP04 | OLYMP04.WAD |
| 10 | 910 | 신들의 시험 | EA | 클리어 | 레벨 ARENA10 (→ … ARENA27, 11 문서 §6.4) | ARENA10.WAD |
| 11 | 911 | 게임 속 동영상 | ES | 클리어 | 연속 재생 Semo01–10, 13 | SEMO*.PSS |
| 12 | 912 | 숨겨진 비밀 | HM17 | 어려움+ | 영상 | BONUS17.PSS |
| 13 | 913 | 거인의 운명 | HM18 | 어려움+ | 영상 | BONUS18.PSS |
| 14 | 914 | 추가적인 의상 | A | 신들의 시험 | 안내만 (새 게임 의상 선택, `Costumes_Unlocked`) | – |
| 15 | 915 | 비밀 메시지 #1 | GM24 | 신 모드 | 영상 (잠긴 설명 "??????") | BONUS24.PSS |
| 16 | 916 | 비밀 메시지 #2 | FM19 | 비트 4 | 영상 (이스터 에그) | BONUS19.PSS |

- 디스크의 BONUS PSS 14개 중 메뉴가 쓰지 않는 것: **BONUS01, BONUS25** (각 11.7MB / 68MB) — 다른 경로(데모/어트랙트?) [미상].
- 설명 줄의 `[*1]` 은 런타임 인자 자리로 FLP 렌더러가 버린다 (빈 줄처럼 보임). `[CircleButton]` 은 버튼 아이콘.

## 4. 엔진 구현
| 파일 | 내용 |
|------|------|
| `game/BonusMenu` | §2 그대로: 메시지 900.. 파싱, 잠금 표, 8줄/설명 채우기, 가속 스크롤, 5/6/7 동작, 실행 중·쿨다운(1초) 입력 잠금(`PS2_EnableButtons`) |
| `BonusMenu.Host` | `playMovie(name, done(skipped))`, `startLevel(wad)`, `sound(name)` — 셸/테스트가 구현 |
| `game/ShellScene` | 명령 리스너에 `bonus.command`, update 에 `bonus.update`. 영상 = `MovieScene.play` (셸 위에 push → 끝나면 pop, 메뉴 그대로), 레벨 = 난이도 1 로 `GameScene(level)` |
| `video/MovieScene` | `play(...)` 가 씬을 돌려주고 `skipped()` 추가 (연속 재생 중단 조건) |
| `ui/UiScene` | `bonus` 모드: 전부 해금 + 메인 메뉴 ↓×3 ○ → 귀중품 → ↓×3 |
| `engine/build.gradle` | `-PgowUnlockAll` → `-Dgow.unlockAll=true` |

- 해금: 아직 게임 클리어 기록(CompleteGame)을 저장하지 않으므로 기본 = 0 (원작 첫 실행과 같음: S 두 개만 열림).
  `BonusMenu.unlockBits()` = `-Dgow.unlockAll` 이면 0x1F. 같은 값을 `ShellBinding.init` 에 넘겨 신 난이도·의상 해금도 함께 열린다.
- `Bonus20` 크레딧 음악(`SND_M_CREDITS1`)·타이틀 음악 복귀는 생략 — 셸이 아직 타이틀 음악을 틀지 않고, BONUS20.PSS 자체에 오디오(210초)가 있다.
- 연속 재생 첫 회 음악 페이드도 생략 (같은 이유).

## 5. 검증
- `BonusMenuTest` (원작 FLP 헤드리스, 3개):
  - `namesFillScrollAndPlayMovie` — 메인 메뉴 ↓↓↓○ → 귀중품. 항목 17개, 4번째 줄 "GOW의 제작 과정"(FLP `_level0/:BonusName4` 복사 확인), 위 3줄 빈칸·잠김,
    ↓/↑ 스크롤·끝에서 멈춤, ○ → `Bonus03`, 재생 중 입력 잠금 → 종료 1초 뒤 복구, 예고편 → `Bonus21`, 잠긴 "만든이들" 은 잠긴 설명·○ 무반응, △ 로 닫기.
  - `unlockAllEntries` — 전부 해금: 만든이들 → `Bonus20`, 신들의 시험 → `ARENA10`, 게임 속 동영상 → Semo01, 02, 03, 04 … 건너뛰면 중단. 영상 항목 12개 이름 = 디스크 파일.
  - `lockRules` — 잠금 문자 표.
- 화면: `work/uirt/bonus.png` (`--ui ... bonus --ui-warm 400`).

## 6. 남은 것
- ShellMenu → NewGame 전이 트리거 (0x29F4B0 설정 후) 위치, `sub_0021F4C8` (arg 4 에서 호출) 의미.
- 클리어 해금 비트 저장 (CompleteGame 메시지 → 세이브/설정 파일) — 지금은 디버그 해금만.
- 의상 선택 화면 (`Costumes_Unlocked`, `CostumeOn/CostumesLeft/Right`) 실제 의상 WAD(R_HERO1..) 적용, OLYMP04 레벨 단독 검증.
- `PS2_ShellStartState`(1 = 귀중품으로 바로) 를 쓰는 원작 경로 확인.
