# 21. 구현: FLP UI 런타임

패키지: `engine/src/main/java/devple/gow/engine/ui/` — 07 문서(FLP 포맷)의 파서(`tools/.../ui/FlpFile`)를 그대로 쓰고,
그 위에 플래시 4 플레이어 축소판(표시 목록 + 타임라인 + 액션 VM + 입력)과 2D 렌더러를 얹었다.

```bash
./gradlew :engine:run --args="--ui gow-rom/R_SHELLK.WAD FLP_ShellK"                       # 메인 메뉴 (↑↓, L=○ 선택, I=△ 뒤로)
./gradlew :engine:run --args="--ui gow-rom/R_PERMK.WAD FLP_HUDK hud --ui-warm 60"         # HUD (데모 PlayerState)
./gradlew :engine:run --args="--ui gow-rom/R_PERMK.WAD FLP_HUDK pause --ui-warm 90"       # 일시정지 메뉴
./gradlew :engine:run --args="--ui gow-rom/R_PERMK.WAD FLP_HUDK powerup --ui-warm 120"    # 파워업(업그레이드) 메뉴
#   --ui-warm n : 시작 전에 n 틱 미리 진행 (스크린샷 결정성), 환경변수 GOW_UI_DUMP=1 : 표시 트리 덤프
./gradlew :engine:test --tests 'devple.gow.engine.ui.*'                                    # GL 없이 VM/타임라인 검증
```

## 1. 구조

| 클래스 | 역할 |
|--------|------|
| `FlpPlayer` | 무비 인스턴스. 표시 목록 갱신, 프레임 진행, 액션 큐, 입력 스프라이트 디스패치, 슬래시 경로 해석, 엔진 API |
| `FlpNode` | 표시 노드: MOVIE(루트/클립 Data7/입력 스프라이트 Data6), MESH(메시 참조), TEXT(동적 라벨). 로컬 변환 + 컬러 곱 |
| `FlpVm` | 액션 스크립트 인터프리터 (§3) |
| `FlpRenderer` | 2D 패스: 메시 파트/글리프를 모델 메시에서 GPU 메시로 만들어 엔진 basic 셰이더로 그림 |
| `UiVariables` | 엔진 ↔ FLP 공유 `PS2_*` 변수 (대소문자 무시) + 스크립트 쓰기 리스너 |
| `UiPad` | 엔진 `Input`/`PadButton` → FLP 버튼 마스크 |
| `UiLayer` | 위 전부를 묶은 파사드 (WAD 로드, update/render) |
| `HudBinding` | `PlayerState` → HUD 변수 + 이벤트 |
| `UiScene` | `--ui` 단독 실행 씬 |

엔진 코어 변경은 `GowMain` 의 `--ui` 옵션 추가뿐이다 (Renderer/Shader 는 public API 만 사용: `renderer().basic()`).

## 2. 타임라인 / 표시 목록 의미 (실데이터로 확정)

- 키프레임의 첫 필드(07 문서의 "이 프레임까지")는 실제로 **시작 프레임**이다. 페이드 키 `..30 α0, ..31 α44 … ..40 α0, ..41 none` 처럼
  해당 프레임부터 다음 키 전까지 유지. 핸들러 0(none) = 제거.
- 프레임 번호(키, 라벨, `GotoFrame`)는 모두 **0 기반**. `GotoFrame2` 의 숫자 인자만 플래시처럼 1 기반 (HUD `1 + 값/2`).
- 레이어 번호 오름차순 = 아래→위 그리기 순서.
- 노드 동일성: (핸들러, 같은 핸들러가 연속된 키 구간의 시작 키). 구간이 바뀌면 새 인스턴스(프레임 0부터 재생).
- 변환 `(a,b,c,d,tx,ty)` = 플래시 MATRIX: `x' = a·x + c·y + tx`, `y' = b·x + d·y + ty` (회전 키 `[0 1 -1 0]` 로 확인).
- 컬러 `RGBA/256` 은 곱셈만 (가산 성분 없음). 알파 0 이하인 노드는 자식까지 생략.
- 입력 스프라이트(Data6)는 플래시 버튼: 타임라인을 재생하지 않고, 배치 알파가 0 이라 보이지 않으며, 액션 문맥은 **부모 클립**.

### 프레임/액션 순서
1. 입력 이벤트: 각 스프라이트 핸들러 — 조건 5 = 누름 에지, 9 = 뗌 에지, 16 + 마스크 0 = **매 프레임** (SimKeyEvent).
2. 타임라인 진행: 재생 중인 클립이 다음 프레임으로 (끝이면 0 으로 루프, 1프레임 클립은 정지).
3. 프레임 진입 시 그 프레임 스크립트를 큐에 넣고 현재 스크립트가 끝난 뒤 실행 (부모 → 자식 순).
   큐의 스크립트는 실행 시점에 노드 프레임이 바뀌었으면 버린다 — 새 자식의 frame 0 `Stop` 이 부모의 `gotoAndPlay` 를 덮어쓰는 것 방지.
- `GotoFrame`/`GotoLabel` 은 gotoAndStop (뒤의 `Play` 가 재생). 같은 프레임으로의 goto 는 스크립트 재실행 안 함.
- 예: 셸 frame 1 스크립트 `GotoFrame 2; Play; Stop` → frame 2 스크립트(큐)가 `Play` → 2..7 페이드 인 → frame 7 `MainWait` 의 `Stop`.

## 3. 액션 VM (`FlpVm`)

값은 전부 문자열(플래시 4), 산술/비교 시 숫자 변환 (숫자 아님/미정의 = 0).

| 범주 | opcode |
|------|--------|
| 흐름 | 0x00 End, 0x99 Jump, 0x9D If, 0x9E Call(`[경로:]라벨` 프레임 스크립트를 그 클립 문맥에서 즉시 실행) |
| 타임라인 | 0x04/05 Next/PrevFrame, 0x06 Play, 0x07 Stop, 0x81 GotoFrame, 0x8C GotoLabel, 0x9F GotoFrame2(숫자 1기반 / `[경로:]라벨`) |
| 대상 | 0x8B SetTarget, 0x20 SetTarget2 (`""` = 원래 문맥, 못 찾으면 이후 타임라인 연산 무시) |
| 산술/논리 | 0x0A–0x0D, 0x0E Equals, 0x0F Less, 0x10 And, 0x11 Or, 0x12 Not, 0x18 ToInteger, 0x30 Random |
| 문자열 | 0x13 StringEquals, 0x14 Length, 0x15 Extract, 0x21 StringAdd, 0x29 StringLess |
| 변수 | 0x96 Push, 0x17 Pop, 0x1C GetVariable, 0x1D SetVariable |
| 속성 | 0x22/0x23 Get/SetProperty (_x _y _xscale _yscale _currentframe _totalframes _alpha _name) — 데이터에선 미사용 |
| 엔진 | 0x83 GetURL `FSCommand:PS2_*` → `CommandListener`, 0x9A GetURL2(loadVariables "dynK.txt" 는 무시), 0x34 GetTime(ms), 0x26 Trace |

ShellK+HUDK 에 나오는 opcode 28종 전부 지원 (07 문서 §4.3 빈도표).

### 변수 규칙
- 경로 `a/b:이름`, `/:이름`, `_level0/:이름`(`_Level0` 도 있음 → 경로·이름 모두 대소문자 무시).
- 이름이 `PS2_` 로 시작하면 경로와 무관하게 `UiVariables`(엔진 공유). 미설정 `PS2_<숫자>` 는 `msgsK.txt` 메시지.
- 그 외는 클립별 변수 (SetTarget 이후엔 대상 클립).
- 스크립트에 나오는 `PS2_*_Event` 는 시작 시 -1 로 초기화 (미정의 "" 는 0 으로 읽혀 "닫기" 이벤트로 오인됨), `PS2_EnableButtons = 1`.

## 4. 좌표계 / 렌더링

- FLP 가상 화면은 **800×600, y 아래** (모델 파트 0 이 0..800×0..600 사각형, 전체화면 페이드 메시가 (400,300) 중심 808×610).
  창에는 4:3 레터박스 (`glViewport`), 직교 투영 `ortho(0,800,600,0)`.
- 메시 참조 = `MDL_<FLP이름 접미사>` (MDL_ShellK / MDL_HUDK) 의 메시 0 파트 번호, 그룹 0. 파트의 오브젝트 i ↔ 메시 참조 머티리얼 i.
  정점 좌표는 픽셀 단위 2D(z=0). 머티리얼 색 u32 = **ARGB** (`ff870000` = "경고." 진한 빨강). 최종 색 = 정점색 × 머티리얼 × 컬러 변환 누적.
- 블렌딩은 전부 알파 블렌드, 깊이 끔. UI 텍스처는 가장자리 이음선 때문에 CLAMP_TO_EDGE 로 바꿔 쓴다.
- 텍스트(동적 라벨): `rect = (x, y, w, h)` 1/16 px (07 문서의 xmax/ymax 추정 정정: `[987,-40,906,474]` 처럼 w < x 인 예가 있음),
  여백 2.5px(40). 글자 높이 H = fontHeight/16, 글리프 메시는 em 64 단위 → 배율 H/64, 진행폭 = width/1024·H,
  기준선 = 위 + 여백 + `size/1024`·H (size 819 = 0.8 em = ascent). 줄 간격 H + leading/16. 정렬 0 왼쪽 / 1 오른쪽 / 2 가운데.
- 버튼 태그 `[CircleButton]` 등: 같은 인스턴스 이름의 FLP 클립 frame 0 을 타임라인 bounds 기준으로 글자 높이 1.15배에 맞춰 인라인 배치 (폭 1.25H).
  `[*1]` 인자 자리는 생략.

## 5. 엔진(게임) 쪽 API

```java
UiLayer hud = UiLayer.load(rom.resolve("R_PERMK.WAD"), "FLP_HUDK", null);  // UiLayer(WadAssets, owns, flpName, MessageTable, UiVariables) 도 있음
HudBinding bind = new HudBinding();
// 고정 틱
bind.update(playerState, hud.variables());   // 값 기록 + 바뀐 값만 PS2_*_Event 발행
hud.update(engine);                          // 패드 → FLP 마스크, tick()
// 렌더 (3D 뒤)
hud.render(engine);

hud.variables().event("PS2_PauseMenu_Event", 1);      // 일시정지 열기 (0 닫기)
hud.variables().event("PS2_PSMMenu_Event", 1);        // 파워업 메뉴
hud.variables().addListener((name, v) -> ...);        // PS2_Sound1/2 효과음, PS2_Done, PS2_Menu_Choice, PS2_PauseDone ...
hud.player().addCommandListener((cmd, arg) -> ...);   // FSCommand: PS2_MemoryCard_Select, PS2_MoveScroll, PS2_BonusScroll, PS2_Tmp_Wad
hud.player().call("/:SetHealthMeter");                // 라벨 스크립트 직접 호출
hud.player().gotoLabel("/MainMeter", "AnimateOn", true);
hud.player().getVariable("_level0/:PausePowerUp_State");
```

HUD 변수 규약 (HUD 루트 `SimKeyEvent` 스크립트에서 확인):

| 변수 | 의미 |
|------|------|
| `PS2_HealthMeter_Value` / `PS2_MagicMeter_Value` | HP/MP 원값 (최대 200). 바 클립 프레임 = 1 + 값/2 |
| `PS2_HealthMeter_Level` | 0..4 = hpLevel (검은 바 `BarLevel<n>`, 0 = frame 0 = 최대 100) |
| `PS2_MagicMeter_Level` | 0..5 = mpLevel (0 = 마법 없음, 스크립트가 `BarLevel(n−1)`) |
| `PS2_PowerOrb_Count` / `_Capacity` | 붉은 오브 / 300. 미터 = count mod 300, 배수 텍스트 = count div 300 |
| `PS2_MeterBar_Event` | 1 = MainMeter AnimateOn(+미터 갱신), 0 = AnimateOff |
| `PS2_HealthMeter_Event` (≥1), `PS2_MagicMeter_Event` (2 = 값, 1 = 반짝임, 0 = 끔), `PS2_PowerOrb_Event` (1) | 값 반영 |
| `PS2_<무기>_Level`, `PS2_<무기>Orb_Count`, `PS2_<무기>_Status` | 파워업 메뉴 (11 문서 §1) |

주의: `SetHealthMeter/SetMagicMeter` 는 저장된 단계(`_level0/:HealthMeter_Level`)가 현재 단계와 **같을 때만** 바를 옮긴다
(이벤트 처리 끝에 단계를 저장). 그래서 단계가 바뀐 프레임 다음에 이벤트를 한 번 더 보내야 한다 — `HudBinding` 이 처리.

## 6. 입력 마스크 (`UiPad`)

확인: 0x10 △, 0x20 ○, 0x1000/2000/4000/8000 ↑→↓←, 0x10000.. 왼쪽 스틱, 0x100000.. 오른쪽 스틱.
추정: PS2 패드 버튼 워드 바이트 교환 배치 — 0x1 L2, 0x2 R2, 0x4 L1, 0x8 R1, 0x40 X, 0x80 □, 0x100 SELECT, 0x200 L3, 0x400 R3, 0x800 START.
(파워업 메뉴 0x4/0x8 = 탭 전환 L1/R1 — 화면의 L1/R1 표시와 일치, 일시정지 0x100/0x800 뗌 = 닫기.)

## 7. 결과 (스크린샷: `work/uirt/`)

| 파일 | 내용 |
|------|------|
| `shell_menu.png` | R_SHELLK 메인 메뉴: GOD OF WAR 로고, "새 게임" + ↓ 화살표, 하단 "○ = 선택" (버튼 아이콘 인라인) |
| `hud.png` | HUD: 좌상단 블레이드 장식, 녹색 체력 바(100/125), 청색 마법 바(90/125), 붉은 오브 미터(1234 mod 300) + 배수 "4" |
| `pause.png` | 일시정지: "일시정지" 현판, 계속/옵션/마지막 체크포인트에서 다시 시작/게임 종료, ○/△ 안내 |
| `powerup.png` | 파워업: 무기/마법 메달 체인, 혼돈의 블레이드 레벨 2, 보유 1234, 필요 1950 |

테스트 `FlpPlayerTest`: 셸 MainWait 정지 → ↓ 로 "불러오기" → ○ 로 `SND_MM_SELECT` 요청, HUD 미터 프레임(50/45)과 이벤트 소비.

## 8. 알려진 한계 / 다음 과제

- 프레임레이트: FLP 를 엔진 틱(60Hz)마다 1프레임 진행. 원작 UI 진행 속도(30Hz 가능성) 미확인.
- 셸 배경 3D 장면(R_SHELLK 의 MAI/횃불/석상 모델, 카메라)은 그리지 않음 — 로고/메뉴가 화면 왼쪽(x≈300)에 있는 것은 데이터 그대로.
- 텍스트 그림자(`PS2_DropShadowOffset/Color` 를 스크립트가 세팅) 미구현. 자간/줄간격·기준선은 추정치 (화면상 자연스러움으로 확인).
- 동적 라벨 플래그(0x41/0x45/0x49), maxLength, 여백 필드 미사용. 정적 라벨은 이 디스크에 없어 미구현.
- 버튼 아이콘 배치 크기/위치는 근사. `[*1]` 인자 치환 경로 미구현 (실행 파일 분석 필요).
- 블렌드 모드는 전부 알파 (PS2 가산 블렌드 이펙트가 있는지 미확인), 컬러 변환 가산 성분 없음 가정.
- 마스크 추정 비트(§6)와 SELECT/START 역할은 실행 파일 패드 처리와 대조 필요.
- 오디오: `PS2_Sound1/2` 요청은 리스너로만 노출 (AudioSystem 연결은 게임 쪽 몫).
- 성능: 글리프마다 드로우콜 1회 (메뉴 화면 수백 회). 필요하면 배칭.
