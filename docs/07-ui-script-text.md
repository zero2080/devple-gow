# 07. UI(FLP) / 스크립트(SCR, ESC) / 텍스트·폰트

도구: `devple.gow.tools.ui.*` (`FlpFile`, `FlpScript`, `MessageTable`, `Cli`), `devple.gow.tools.script.*` (`EscScript`, `EscOp`)

```bash
./gradlew -q :tools:run --args="ui:info   /abs/gow-rom/R_SHELLK.WAD"            # FLP 요약 + 동적 라벨 → 한글 메시지
./gradlew -q :tools:run --args="ui:text   /abs/gow-rom/R_PERMK.WAD work/ui/R_PERMK.txt"   # 메시지 737개 + 라벨 + 글리프표
./gradlew -q :tools:run --args="ui:flp    /abs/gow-rom/R_SHELLK.WAD"            # 타임라인/키프레임/액션 스크립트 전체
./gradlew -q :tools:run --args="ui:script /abs/gow-rom/ATHN01A.WAD"             # ESC 엔티티 + 바이트코드 디스어셈블
./gradlew -q :tools:run --args="ui:script-stats /abs/gow-rom work/ui/script-stats.txt"   # 레벨별 엔진 함수 사용 통계
```

참고: FLP/SCR 레코드 배치는 공개 프로젝트 mogaika/god_of_war_browser(`pack/wad/flp`, `pack/wad/scr`) 의 분석을 출발점으로 삼고,
이 디스크(한글판)의 실데이터로 전부 재검증했다. 다른 부분(Push 인코딩, 0x9A, 입력 조건 플래그, 동적 라벨 rect, 메시지 테이블 연결)은 직접 확인/수정한 것.

## 1. 한눈에 보기

| 구성 | 위치 | 형식 |
|------|------|------|
| 메인 메뉴 셸 UI | `R_SHELLK.WAD` / `FLP_ShellK` (+ `MDL_ShellK`) | FLP (플래시 변환) |
| HUD·일시정지·업그레이드·저장·사망 메뉴 | `R_PERMK.WAD` / `FLP_HUDK` | FLP |
| 레벨 전용 소형 UI(디버그용 `PS2_WadName` 등) | `ATHN01B/C/E`, `ATHN08A/B/C`, `PAND03G` 의 `FLP_<레벨>` | FLP |
| **모든 한글 문장** | `R_PERMK.WAD` 태그 0x6F `msgsK.txt` | UTF-8 텍스트, `*ID*` 블록 |
| 한글 폰트 | `FLP_ShellK`/`FLP_HUDK` 의 폰트 0 → 텍스처 `TXR_GOWK_0..5` | 글리프 = 메시 파트, 문자표 = UCS-2 |
| 이벤트 스크립트 | 각 레벨 WAD `ESC_*` (target `SCR_Entities`) | 엔티티 + 스택 머신 바이트코드 |
| 환경 스크립트 | `" SCR_Fog"`, `" SCR_Sky"` … | target 별 파라미터 블록 |

`R_SAVEPT.WAD` 에는 FLP/스크립트가 없다 (세이브 포인트 모델/이펙트만). 저장 화면 UI 는 `FLP_HUDK` 의 `SaveScreen`, `SaveGameMenu`, `MemoryScreen1..25` 클립이 담당한다.

## 2. 텍스트 — `msgsK.txt`

- `R_PERMK.WAD` 의 태그 **0x6F**(02 문서의 MC_ICON 과 같은 "원시 파일" 태그), 이름 `msgsK.txt`, 0xD94B 바이트.
  실행 파일에 `msgs%s.txt` 문자열이 있어 언어 접미사(K)로 파일명을 만든다고 본다.
- **인코딩: UTF-8** (EUC-KR/CP949 아님, 커스텀 코드도 아님). NUL 로 끝남.
- 형식: `*ID*\n본문\n` 반복. 737개, ID 2 ~ 4554.

| ID 범위 | 내용 (예) |
|---------|-----------|
| 2–7 | 슬롯/난이도/월 이름 — `*3* 인간(쉬움)`, `*6* 신(매우 어려움)` |
| 10–34 | 콤보 칭호 — `야수!` … `신!!!` |
| 100–600대 | 레벨 안내문/튜토리얼 — `*100* 더블 점프를 하려면 [XButton], [XButton]을 누르십시오.` |
| 1000–3999 | 보너스/아이템 설명 등 |
| 4000–4554 | 메뉴 UI — `*4517* 새 게임`, `*4516* 불러오기`, `*4051* 옵션`, `*4515* 귀중품` |

본문 문법
- `[CircleButton]`, `[TriangleButton]`, `[L1Button]`, `[Joystick]`, `[IconMedusa]` … : 버튼/아이콘 치환 태그 (27종).
  폰트에는 이 글리프가 없고, `FLP_HUDK` 에 같은 이름의 클립(`'CircleButton'`, `'IconMedusa'` …)이 있어 런타임에 인라인 배치하는 것으로 보인다.
- `[*1]` : 런타임 인자 자리 (예: `[*1]포세이돈의 분노\n를 획득하였습니다.`)
- `----` 줄 : 페이지 구분.

UI 연결: FLP 동적 라벨의 변수명이 **`PS2_<ID>`** 이면 해당 메시지를 출력한다 (예: `PS2_4018` → `[CircleButton] = 선택`).
셸 루트 타임라인 1프레임 스크립트가 `loadVariables("dynK.txt", "/")`(opcode 0x9A) 를 호출하는데, 엔진이 이것을 메시지 테이블 → `PS2_*` 변수 주입으로 처리하는 것으로 추정.

덤프: `work/ui/R_PERMK.txt` (메시지 전체 + HUD 라벨 + 글리프표), `work/ui/R_SHELLK.txt`.

## 3. 폰트 / 글리프 매핑

| 폰트 | 글리프 수 | flags | 문자표 | 텍스처 |
|------|----------|-------|--------|--------|
| ShellK/HUDK font 0 | 699 | 4 | 글리프→UCS-2 (정렬됨) | `TXR_GOWK_0..5` (ASCII는 `_5`) |
| 레벨 FLP font 0 | 80 | 5 | 0x100 개 (문자→글리프) | `TXR_GodOfWar` (라틴 전용) |

- 한글 폰트 문자 구성: ASCII/기호 77자 + `…`(U+2026) + **한글 완성형 621자**(U+AC00 `가` … U+D7A3 영역에서 실제 쓰인 것만).
  `msgsK.txt` 의 모든 문자(치환 태그 제외)가 폰트에 있음을 확인 → **메시지 테이블에서 쓰인 문자만 뽑아 만든 폰트**.
- 글리프 = 같은 이름 모델(`MDL_ShellK`, `MDL_HUDK`)의 **메시 파트** (ShellK 는 파트 3..701). 즉 글리프 사각형과 UV 는 모델 쪽에 있고,
  FLP 는 파트 번호 + 머티리얼(블렌드 컬러 + `TXR_GOWK_n`)만 가진다. 아틀라스 이미지 디코드는 03(텍스처) 작업 대상.
- 폭: s16, 1024 = 전각(한글). ASCII 409~1092. 폰트 `size` 819(0x333), `scale` 0.005.

폰트 레코드 (0x24)

| 오프셋 | 타입 | 내용 |
|-------|------|------|
| 0x00 | u32 | 글리프 수 |
| 0x04 | u16 | 미상 (231 / 2845 / 337) |
| 0x06 | s16 | size (819) |
| 0x08 | u16 | 미상 (205) |
| 0x0c | u16 | flags: bit0 = 문자표가 0x100 짜리 문자→글리프, bit1/2 = 글리프 메시 참조 있음 |
| 0x20 | f32 | scale |

하위 데이터 순서: [메시참조 8B × n][머티리얼 8B × Σ] → 폭 s16 × n → 4정렬 → 문자표 u16 × (n 또는 0x100) → 4정렬.

## 4. FLP 포맷 (매직 0x21)

플래시(SWF4) 무비를 PS2 런타임용으로 굽은 형식. 좌표계는 800×600 가상 화면(중심 400,300), 위치 단위 1/16 픽셀.

### 4.1 헤더 (0x60) — 포인터 필드는 툴 머신 메모리 주소(쓰레기값)라 무시
| 오프셋 | 내용 | ShellK | HUDK |
|-------|------|-------|------|
| 0x0c | 전역 핸들러 수 (4B 레코드) | 416 | 1019 |
| 0x14 | 메시 참조 수 (8B) | 33 | 218 |
| 0x1c | 폰트 수 (0x24B) | 1 | 1 |
| 0x24 | 정적 라벨 수 (0x24B) | 0 | 0 |
| 0x2c | 동적 라벨 수 (0x20B) | 196 | 365 |
| 0x34 | Data6 = 입력 처리 스프라이트 수 (0xcB) | 40 | 12 |
| 0x3c | Data7 = 무비클립 수 (0x18B 타임라인) | 131 | 381 |
| 0x44 | Data8 = 루트 타임라인 (1개, 카운트 없음) | 148프레임 | 95프레임 |
| 0x48 | 변환 수 (u16, 0x14B) | 1018 | 6731 |
| 0x50 | 블렌드 컬러 수 (u16, 8B) | 305 | 1461 |
| 0x58 | 문자열 섹션 크기 (u16) | 8504 | 22686 |

카운트는 하위 16비트만 유효(상위는 쓰레기, 예 `0x3F800131`). 헤더 뒤는 위 순서대로 **스트림**: 각 배열은 고정 레코드 전체 → 각 레코드의 가변 데이터, 곳곳에 4바이트 정렬.
9개 FLP 모두 파싱 끝 위치가 문자열 섹션 시작(파일 끝 − 0x58 값)과 정확히 일치(`consistent=true`).

### 4.2 레코드
- **전역 핸들러** `u16 type, u16 index` — 타임라인이 요소를 가리키는 간접 테이블. type: 0 없음, 1 메시, 3 폰트, 4 정적라벨, 5 동적라벨, 6 Data6, 7 Data7 (= 헤더 배열 번호+1).
- **메시 참조** `s16 meshPart, u16 머티리얼 수, ptr` + 머티리얼 `u32 RGBA, u32 텍스처명 오프셋(-1 없음)`. 이미지(배경/아이콘/바)도 모델 메시 파트.
- **동적 라벨** (플래시 EditText 대응)

| 오프셋 | 타입 | 내용 |
|-------|------|------|
| 0x00 | u16 | 변수명 (문자열 오프셋) — `PS2_4517`, `_level0/:PS2_MenuTemplate_Menu4` |
| 0x02 | u16 | 초기 텍스트 (0xFFFF 없음) |
| 0x04 | u16 | 폰트 핸들러 |
| 0x06 | u16 | 글자 높이 (1/16 px: 400 = 25px) |
| 0x08 | u32 | 색 (ARGB 바이트 순 `ff cc cc cc`) |
| 0x0c | u16 | 최대 길이 (0) |
| 0x0e–0x13 | u16×3 | 여백/들여쓰기 추정 (0) |
| 0x14 | u16 | leading 추정 (40) |
| 0x16 | s16×4 | rect (x, y, w, h) 1/16 px 추정 |
| 0x1e | u8 | 정렬 (1 = 오른쪽?, 2 = 가운데) |
| 0x1f | u8 | 플래그 (0x41, 0x45) |

- **정적 라벨** (이 디스크에서는 0개): 변환 0x14 + `u32 명령 길이` + 명령 스트림
  (`0x80|f` 헤더: f&8 폰트핸들러+스케일/1024, f&4 색, f&2 X, f&1 Y / `n<0x80`: (glyph u16, advance s16/16) × n). `FlpFile.staticText()` 로 복원 가능하게 구현만 해 둠.
- **타임라인** (Data7, Data8, Data6 내부) 0x18: `u16 총프레임, u16 레이어 수, u16 라벨 수, s16×4 bounds(추정)`, 포인터
  - 레이어 8B `u16 프레임수, u16 키 수` → 키프레임 10B `u16 이 프레임까지, u16 핸들러, u16 변환ID, u16 컬러ID, u16 인스턴스명`
  - 프레임 라벨 0xC `u16 프레임, u16 스크립트 수, …, u16 라벨명(0x08)` → 스크립트 `u32 길이, ptr` → 바이트코드
- **Data6** 0xC (`u16 입력핸들러 수 @0x08`) → 타임라인 → 입력 핸들러 0x10 `u32 스크립트 길이, ptr, u32 버튼 마스크, u16 조건` → 바이트코드
- **변환** 0x14: 2×2 행렬 s32 16.16 고정소수, `s16 x/16, s16 y/16`. **컬러** `u16 R,G,B,A` (256 = 1.0, 페이드는 A 를 0→51→…→256).

입력 조건(u16): 플래시 ButtonCondAction 플래그와 일치 — **5 = 누름(0x4|1), 9 = 뗌(0x8|1)**, 16 = 마스크 0 과 함께 `SimKeyEvent`.
버튼 마스크(스크립트 내용과 메시지로 확인): 0x10 = △(뒤로, `SND_MM_BACK`), 0x20 = ○(선택, `SND_MM_SELECT`),
0x1000/0x2000/0x4000/0x8000 = 방향키 상/우/하/좌, 0x10000.. = 왼쪽 스틱 같은 순서, 0x100000.. = 오른쪽 스틱(추정). 0x4, 0x8, 0x40, 0x80, 0x100, 0x800 미확정.

### 4.3 액션 스크립트 (SWF4 바이트코드 변형)
opcode 번호는 SWF4 와 같고, **0x80 이상에 길이 필드가 없으며 문자열은 문자열 섹션 u16 오프셋**이다.

| op | 인자 | 의미 |
|----|------|------|
| 0x81 | u16 | GotoFrame |
| 0x83 | u16 url, u16 target | GetURL — 실제로는 `FSCommand:PS2_MemoryCard_Select` 등 **엔진 호출** |
| 0x8B / 0x8C | u16 | SetTarget / GotoLabel |
| 0x96 | u16 | Push 문자열 (숫자도 `"8"` 같은 문자열) |
| 0x99 / 0x9D | s16 (다음 명령 기준) | Jump / If |
| 0x9A | u8 | GetURL2 (`0xC0` = loadVariables) |
| 0x9E | – | Call (pop 한 라벨의 프레임 스크립트 실행) |
| 0x9F | u8 | GotoFrame2 (0 stop / 1 play) |
| <0x80 | – | End, Play, Stop, Add…Divide, Equals, Less, And, Or, Not, StringAdd, GetVariable, SetVariable, SetTarget2, GetTime, ToInteger, Pop |

> mogaika 구현은 Push 첫 바이트가 1 이면 float, 0 이면 3바이트로 읽지만, 이 디스크에선 오프셋 하위 바이트가 우연히 01 인 경우(`96 01 35` = 오프셋 0x3501)여서 스크립트가 깨졌다.
> "항상 u16" 으로 바꾸자 ShellK/HUDK 의 2만 2천여 명령이 미지 opcode 없이, 모든 문자열 오프셋이 문자열 경계에 맞게 해석됨.

사용 빈도(ShellK+HUDK): Push 6270, SetTarget 2421, GetVariable 1901, Not 1516, End 1440, Play 1408, SetVariable 1404, If 1330, Equals 1264, GotoLabel 999 …

### 4.4 메뉴/HUD 가 기술되는 방식
엔진 ↔ FLP 인터페이스는 **변수 + 라벨 호출** 이다. 엔진이 `PS2_*` 변수를 세팅하고 루트 타임라인의 라벨 스크립트를 Call, FLP 는 `FSCommand:PS2_*` 로 엔진을 부른다.

메인 메뉴 (`FLP_ShellK` 루트, 148프레임). 라벨: `MainWait`, `MainOff`, `OpeningUp/Down`(메뉴 커서), `OptionOn/Off`, `OptionsUp/Down/Left/Right`,
`ControllerOn/Off`, `TreasureOn/Loop/Off`, `SaveLoad_Off`, `ProgressiveOn`, `DifficultyOn/Up/Down`, `CostumeOn/CostumesLeft/Right`, `DiscOn`, `CheckDiscPad`, `SimKeyEvent`.
메뉴 항목 텍스트는 동적 라벨 `PS2_4517 새 게임`, `PS2_4516 불러오기`, `PS2_4051 옵션`, `PS2_4515 귀중품`, 하단 안내 `PS2_4018 [CircleButton] = 선택`, `PS2_4020 [TriangleButton] = 뒤로`.
커서 이동 예 (Data6 입력 핸들러):
```
on input mask=0x1000 unk=5      ; 방향키 위 누름
  Push "/:OpeningUp"
  Call                          ; 루트의 OpeningUp 라벨 스크립트 실행
  End
```
옵션 화면 라벨: `진동:`(4107), `사운드 모드:`(4092) `모노/스테레오/서라운드`, `효과음 볼륨`, `배경 음악 볼륨`, `부드러운 화면:`, `프로그레시브 스캔:`, `와이드 스크린:`.

HUD (`FLP_HUDK` 루트 95프레임). 라벨: `HUD`, `OpenPause/ClosePause`, `SetHealthMeter`, `SetMagicMeter`, `SetHealthShard`, `SetMagicShard`, `SetTrident`, `SetSiren`,
`PowerOrbCheck`, `PowerLevelCheck`, `PowerSetMeters`, `PowerUpCalculate`, `PowerMenuLoop1..3`, `SaveScreen`, `DeadRestart/DeadExit`, `DisplayTextOn/Off` …
주요 클립: `MainMeter/HealthMeter`, `MagicMeter`, `HealthMeterBlackBar`, `RageOfTheGods`, `OrbCount`, `HitCounter`, `PowerUpMenu/NeededOrbs`, `PauseMenu`, `SaveScreen`, `DeadMenu`.
체력 바 갱신 예 (`SetHealthMeter`):
```
SetTarget "/MainMeter/HealthMeter"
Push "1"; Push "PS2_HealthMeter_Value"; GetVariable; Push "2"; Divide; Add
GotoFrame2 stop                 ; 바 = 무비클립 프레임 (1 + 값/2)
SetTarget "/MainMeter/HealthMeterBlackBar"
Push "BarLevel"; Push "PS2_HealthMeter_Level"; GetVariable; StringAdd
GotoFrame2 stop                 ; 최대치 단계 = "BarLevel<n>" 라벨
```
업그레이드 메뉴(`PowerOrbCheck`)는 `_level0/:PausePowerUp_State`(선택 무기/마법) 별로 `PS2_Blades_Level` 등 레벨이 최대(4)인지 보고
`PausePowerUpOrbs = <무기>Orb_Capacity − PS2_<무기>Orb_Count` 로 필요 오브 수를 계산해 `NeededOrbs` 클립에 표시한다.

FSCommand 목록: `PS2_MemoryCard_Select 0..4`, `PS2_MoveScroll 0..4`, `PS2_BonusScroll 0..7`, `PS2_Tmp_Wad 1..5`.

## 5. 스크립트 리소스 (매직 0x00010004, 서버 SCRX)

### 5.1 헤더 (0x24)
| 오프셋 | 내용 |
|-------|------|
| 0x00 | magic 0x00010004 |
| 0x04 | char[16] **target** — 실행 파일에 이름으로 등록된 스크립트 클래스 (16자 초과 시 0x14 로 넘침: `SCR_ThingOnASpring`) |
| 0x1e | u16 항상 0x1388 |
| 0x20 | u16 0x12 / 0x02 / 0x00 (미상) |
| 0x22 | u16 0..2 (미상) |

전체 5,015개: `SCR_Entities` 4341, `SCR_Sky` 158, `SCR_Fog` 156, `SCR_Mirror` 104, `SCR_LayeredFog` 65, `SCR_RopeSkinned` 54, `SCR_Weather` 38, `SCR_Water` 36,
`SCR_FarClip` 18, `SCR_ThingOnASpring` 12, `SCR_GhostTrail` 12, `SCR_LensFlare` 8, `SCR_cloth` 7, `SCR_Shadow` 4, `SCR_AresSky` 2.
실행 파일의 `SCR_*` 문자열 74개 중 `SCR_ShowMedusaHead`, `SCR_AddItem`, `SCR_TurnToRock` 등은 데이터에 이름으로 나타나지 않는다 →
애니메이션 이벤트/비헤이비어 쪽에서 해시나 인덱스로 참조되는 엔진 콜백으로 추정 (06 문서 과제).

### 5.2 `SCR_Entities` 엔티티 (연속 배치)
| 오프셋 | 내용 |
|-------|------|
| 0x00 | f32[16] 월드 행렬 (트리거 볼륨 위치/크기) |
| 0x40/0x42 | u16 미상 |
| 0x44 | u16 엔티티 전체 크기 |
| 0x46 | u16 타입 |
| 0x48 | u16 고유 ID |
| 0x4a | u16 기준 ID (Data 타입: 변수 번호 시작) |
| 0x4c | u16 파라미터 (Data 타입: 변수 수) |
| 0x4e / 0x50 / 0x52 | u16 핸들러 수 H / 대상 엔티티 수 T / 코드 영역 크기 |
| 0x54 | (u16 핸들러ID, u16 시작오프셋) × H, u16 대상ID × T, u16 0, 바이트코드 + 문자열 상수 |
| … | 엔티티 이름 cstr, (Global/LevelData) `[u8 타입, cstr 이름] × 변수 수` |

타입(9,623개 집계): 0 EntrySensor 1665, 1 ExitSensor 226, 2 CreationSensor 461, 3 DestructionSensor 278, 4 EventSensor 884, 5 Animator 829, 6 ? 4, 7 Vis 1812,
8 EventTransmitter 516, 9 Start 211, 10 SpawnEnemy 1120, 11 Creator 504, 12 GlobalData 75, 13 LevelData 158, 14 Marker 146, 15 SoundController 734.

핸들러 ID 는 "이벤트 슬롯" 또는 "파라미터 슬롯". 상수 파라미터는 `push → return → exit` 로 표현된다:
```
=== ESC_Start (ARENA06)  entity #0 Start 'Start'
  handler 0:  push_string "Hero"; return; exit      ; 플레이어 오브젝트
  handler 1:  push_int 1; return; exit
```
안내문 표시 예 (ATHN01A):
```
@002a push_int 269   ; msg "[SquareButton] 보통 공격.\n[TriangleButton] 강한 공격."
@002f push_int 5
@0034 int2float
@0035 call_int Internal:0xa ShowMessage(msgId,fSec?)
```
→ ESC 의 정수 인자가 `msgsK.txt` 의 ID 와 직결된다.

### 5.3 바이트코드 (스택 머신)
| op | 인자 | 의미 |
|----|------|------|
| 00 / 01 | f32 / s32 | push 상수 |
| 02–05 | u16 | get {float,int,bool,string} 변수 `scope<<12 | id` |
| 06–09 | u16 | set … |
| 0A–0D | u16 | 엔진 함수 호출, 반환형 {float,int,bool,string} |
| 0E | u16 | push_string (코드 영역 기준 오프셋) |
| 0F / 10 | s16 | jump_if (pop 이 **거짓**이면 점프, 0x16F570) / jump (다음 명령 기준) |
| 11 / 12 | – | true / false |
| 13–1D | – | neg, add, sub, mul, div (int/float 교대), mod |
| 1E–23 | – | 형변환 |
| 24–27 | – | not, and, or, xor |
| 28–37 | – | lt, le, gt, ge, eq × (int, float, string/bool) |
| 38 | – | return (pop) |
| ≥3A | – | exit |

scope: 0 Entity(엔티티 함수), 1 Internal(엔진 전역), 2 GlobalData, 3 LevelData. 전 레벨 파싱 결과 미지 opcode 0.

엔진 함수 호출 빈도 상위 (`work/ui/script-stats.txt`, `?` 는 추정명):
Entity:0x9(엔티티 참조?) 1195, Internal:0x9 AbortCutscene? 515, 0xc Idle 495, 0x1c MusicGroupStop 335, 0x8 SwitchCamera? 315, 0x1b MusicGroupStart 293,
0x0 CheckPoint 239, 0xa ShowMessage 229, 0x6 LoadCheck? 183, 0x3 LoadWad? 177, 0x4 LoadForWarp? 99, 0x5 Warp? 68, 0x14 CreateTimer? 67, 0x13 FailedScreen 14, 0x2f CompleteGame 3 …
레벨별 사용 목록은 `script-stats.txt` 하단.

## 6. 출력물 (`work/ui/`)
| 파일 | 내용 |
|------|------|
| `R_PERMK.txt` | 메시지 737개(UTF-8) + HUD 동적 라벨→메시지 + 한글 폰트 글리프표 699행 |
| `R_SHELLK.txt` | 셸 동적 라벨→메시지 + 글리프표 |
| `FLP_ShellK.dump.txt`, `FLP_HUDK.dump.txt` | 메시/라벨/스프라이트/클립/루트 타임라인 + 디스어셈블 |
| `ATHN01A.script.txt` | ESC 엔티티/핸들러 디스어셈블 예 |
| `script-stats.txt` | 스크립트 target/엔티티 타입/함수 호출 통계 (전 레벨) |

## 7. 미해결 과제
- 글리프/이미지 메시 파트의 실제 사각형·UV (MDL_ShellK/MDL_HUDK) 와 `TXR_GOWK_n` 아틀라스 디코드 → 03/04 문서와 연계.
- 동적 라벨 0x0c–0x15, 0x1f 플래그, 타임라인 bounds, 폰트 0x04/0x08 필드 의미.
- 버튼 마스크 하위 비트(0x4, 0x8, 0x40, 0x80, 0x100, 0x800)와 오른쪽 스틱 추정 검증.
- `[CircleButton]` 등 치환 태그 → `FLP_HUDK` 클립 인라인 배치 방식, `[*1]` 인자 공급 경로 (실행 파일 분석 필요).
- `loadVariables("dynK.txt")` 처리와 `msgs%s.txt` 로딩 경로 확인.
- ESC 엔진 함수(Internal/Entity) 이름 대부분 추정. 실행 파일의 함수 테이블과 대조 필요 (06 문서).
- 실행 파일 `SCR_*` 콜백(SCR_ShowMedusaHead 등)의 호출 경로, 환경 스크립트(SCR_Fog/Sky/Water…) 본문 파라미터 구조.
- ESC 헤더 0x20/0x22, 엔티티 0x40/0x42 필드.
