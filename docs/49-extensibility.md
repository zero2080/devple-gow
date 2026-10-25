# 49. 확장 구조 (설정 · 지역판 · 모드 계층 · 리소스 교체)

원작 데이터를 그대로 재생하는 엔진 위에, **원본을 건드리지 않고** 바꿔 끼울 수 있는 자리를 한곳에 모은다.
- 다른 디스크(지역판) 추출
- 그래픽·애니메이션·폰트·텍스트·사운드·스테이지·이벤트 씬·적 AI 교체
- 키 변경

모드가 하나도 없으면 모든 조회가 원래 경로를 돌려주므로 원작 동작은 바뀌지 않는다 (전 테스트 그대로 통과).

## 1. 한눈에

```
gow.properties            실행 설정 (rom·iso·region·mods)            §2
gow-rom/                  원본 추출물 (계층 맨 아래)
mods/<이름>/              모드 하나 = 계층 하나 (gow.properties mods= 로 켬)
  rom/NAME                rom 파일 통째 교체 (WAD·VAG·VPK·PSS)       §3
  wad/<WAD>/<태그>.bin     WAD 안 리소스 하나 교체                     §5
  textures/…  sound/…  text/…  *.properties   종류별 교체          §6–§14
config/                   사용자 계층 (늘 맨 위 — 개인 키 설정 등)
```

계층 우선순위 (낮음 → 높음): `rom` < `mods/<mods[0]>` < `mods/<mods[1]>` … < `config/`.
- 같은 상대 경로가 여러 계층에 있으면 위 계층 것을 쓴다.
- `.properties` 표는 아래 계층부터 키 단위로 덮어 합친다 (`Content.properties`).

코드 위치: `tools/src/main/java/devple/gow/tools/content/`.
- `GameConfig`: 설정.
- `Content`: 계층 조회.
- `Region`: 지역판.
- `WadPatches`: 태그 교체.

엔진과 도구가 같은 클래스를 쓴다.

## 2. 실행 설정 `gow.properties`

작업 디렉터리(저장소 루트)의 `gow.properties` 를 읽는다. 없으면 기본값을 쓴다.
같은 키를 `-Dgow.<키>` 시스템 속성으로 주면 그것이 이긴다. 테스트는 `-Dgow.rom` 을 쓴다.

| 키 | 기본 | 뜻 |
|----|------|----|
| `rom` | `gow-rom` | 추출된 디스크 파일 디렉터리 |
| `iso` | (탐색) | 동영상을 ISO 에서 바로 읽을 때. 비우면 작업 디렉터리의 `*.iso` → rom 실제 경로 부모의 `*.iso` |
| `region` | (감지) | 언어 접미사 (§4). `-` = 접미사 없음 |
| `mods` | (없음) | 켤 모드 이름, 쉼표 구분, 뒤에 적을수록 우선 |
| `modsDir` | `mods` | 모드 디렉터리 |
| `userDir` | `config` | 사용자 계층 |

예시는 저장소 루트의 `gow.properties.example`.

`gow.properties`, `mods/`, `config/` 는 `.gitignore` 에 들어 있다. 원본에서 파생한 자산이 섞이기 쉽기 때문이다.

## 3. rom 파일 교체 (`mods/<m>/rom/`)

- 엔진의 모든 rom 파일 접근은 `Content.file(romDir, 이름)` 을 지난다. 바꾼 곳은 170곳이다.
  - 레벨·영웅·무기·마법·보스·HUD·메뉴 WAD
  - VAG/VPK 사운드
  - PSS/PSW 동영상
- 위 계층에 `rom/<이름>` 이 있으면 그 파일을 연다.
- 레벨 WAD 를 통째로 바꾸거나, 원작에 없는 이름의 WAD 를 더해 새 스테이지를 만들 때 쓴다 (§9).
- 같은 폴더의 다른 파일을 찾던 자리는 `Content.sibling(wad, 이름)` 을 쓴다.
  - 예: 레벨 WAD 옆의 R_PERM.
  - wad 가 모드의 `rom/` 에서 왔어도 설정 rom 디렉터리로 돌아가 찾는다.

## 4. 지역판 (`Region`)

언어별 자산은 이름 끝 접미사로 갈린다.
- 한글판 SCKA_300.02 는 `K` 다: `R_PERMK.WAD`, `R_SHELLK.WAD`, `FLP_ShellK`, `FLP_HUDK`.
- 엔진은 이 이름을 직접 쓰지 않는다. `Region.of(rom).perm()/shell()/flpShell()/flpHud()` 로 만든다.

접미사를 정하는 순서:
1. 설정 `region`
2. rom 디렉터리의 `R_PERM<접미사>.WAD` 감지 (`K` 우선, 그다음 이름순 첫째)
3. 기본 `K`

다른 판의 ISO 추출 절차는 §15.

> 주의: 다른 지역판은 실행 파일 주소·TWK 해시·메시지 ID 가 다를 수 있다. 엔진은 실행 파일을 돌리지 않고 데이터(WAD)만 읽으므로, 데이터 형식이 같으면 그대로 동작할 것으로 본다. 검증은 그 판의 디스크가 있어야 한다.

## 5. WAD 태그 교체 (`mods/<m>/wad/<WAD이름>/<태그이름>.bin`)

`WadFile.load` 가 파일을 읽은 직후 `WadPatches.apply` 가 태그 스트림을 다시 짠다.
- 교체 파일이 있는 데이터 태그만 바꾼다. 같은 이름이 여럿이면 전부 바꾼다.
- 헤더 크기를 새 길이로 고치고, 데이터는 16 바이트 정렬로 채운다.
- 나머지 태그는 바이트 그대로다.
- 크기가 달라도 되므로 원작 리소스와 같은 형식이면 무엇이든 바꿀 수 있다.
  - 텍스처: GFX/PAL/TXR
  - MDL 모델
  - ANM 애니메이션
  - ESC/SCR 스크립트
  - FLP UI (폰트 글리프 포함)
  - SND 뱅크
  - TWK 튜닝
- WAD 이름은 대문자이고 `.WAD` 를 뺀다. 예: `wad/ATHN01A/TXR_floor.bin`.
- 꺼내기·확인 도구: §15.

## 6–14. 종류별 교체

| § | 종류 | 자리 | 상태 |
|---|------|------|------|
| 6 | 키 변경 | `keys.properties` | 완료 (키보드·게임패드 버튼) |
| 7 | 폰트 | `font.properties` + TTF | 완료 (stb_truetype 동적 아틀라스) |
| 8 | 텍스트·대사 | `text/messages.properties` (+ `text/msgs.txt`) | 완료 |
| 9 | 스테이지 | `stages.properties` + `rom/` | 완료 |
| 10 | 이벤트 씬 (동영상·스크립트) | `movies.properties` + `rom/*.PSS` + `wad/` | 완료 |
| 11 | 사운드 | `sound/<SND이름>.wav` + `sound.properties` | 완료 |
| 12 | 애니메이션 | `anim.properties` + `wad/` | 완료 |
| 13 | 적 AI | `ai.properties` + 플러그인 (`plugins/*.jar`) | 완료 |
| 14 | 그래픽 (텍스처 PNG) | `textures/` + `normalmaps/` | 완료 |

(각 절은 구현과 함께 아래에 채운다.)

## 6. 키 변경

`engine/input/KeyBindings` 가 계층의 `keys.properties` 를 덮어 합쳐 읽는다 (`Content.properties`). 적지 않은 항목은 기본 배치 그대로다.
- 기본: J=□ I=△ L=○ K=×, Q=L1 E=R1 Z=L2 C=R2, Enter=START, Tab=SELECT, 방향키 = 방향 버튼이자 오른쪽 스틱, WASD = 왼쪽 스틱, 왼쪽/오른쪽 Shift = L3/R3.
- 개인 설정은 `config/keys.properties` (사용자 계층, 늘 맨 위). 모드가 배치를 들고 와도 그 위에서 덮는다.

```properties
# config/keys.properties
CROSS=K,SPACE          # 여러 키 = 쉼표
SQUARE=J
STICK_L_UP=W           # 스틱 방향: STICK_L_/STICK_R_ + UP/DOWN/LEFT/RIGHT
STICK_R_LEFT=LEFT
SELECT=                # 빈 값 = 키보드에서 뺌
pad.CROSS=A            # 게임패드 버튼 (위치 기준 GLFW 이름)
pad.L2=LEFT_TRIGGER,LEFT_BUMPER
```

| 항목 | 값 |
|------|----|
| 동작 | `PadButton` 이름 (`CROSS CIRCLE SQUARE TRIANGLE L1 R1 L2 R2 L3 R3 START SELECT UP DOWN LEFT RIGHT`), `STICK_L_*`, `STICK_R_*`, 앞에 `pad.` = 게임패드 |
| 키 이름 | GLFW 키 이름에서 `GLFW_KEY_` 를 뺀 것: `A`–`Z`, `0`–`9`, `F1`–`F25`, `KP_0`–`KP_9`, `SPACE ENTER TAB ESCAPE BACKSPACE UP DOWN LEFT RIGHT LEFT_SHIFT RIGHT_CONTROL …` (대소문자 무관, `KEY_` 접두사 허용) |
| 패드 이름 | GLFW 게임패드 버튼 이름: `A B X Y` (= `CROSS CIRCLE SQUARE TRIANGLE`), `LEFT_BUMPER RIGHT_BUMPER BACK START GUIDE LEFT_THUMB RIGHT_THUMB DPAD_*`, 그리고 `LEFT_TRIGGER RIGHT_TRIGGER` (절반 이상 당김) |

- 모르는 동작·이름은 `[keys]` 경고를 내고 건너뛴다. 값의 이름이 전부 모르는 것이면 그 항목은 기본을 유지한다.
- 게임패드 스틱 축은 바꾸지 않는다 (키 스틱 값보다 축이 0.2 넘게 기울면 축).
- 디버그 키 (1–5, 레벨 뷰어·노멀맵 등) 는 장면 코드에 그대로 있다.
- 이름은 컴파일 때 박힌 GLFW 상수 표로 푼다 — GLFW 네이티브 없이 해석·테스트된다. `Input` 의 공개 API 는 그대로다.
- 테스트 `KeyBindingsTest` (4개): 기본 배치 = 원래 하드코딩, 여러 키·스틱·패드 해석, 모르는 이름 경고 후 기본 유지, 계층 덮어 합치기.

## 7. 폰트

UI 글자 (`FlpRenderer` 의 동적 라벨) 를 TrueType 폰트로 그릴 수 있다. `font.properties` 가 없으면 아무것도 만들지 않고 원래 FLP 글리프 메시 경로 그대로다.

```properties
# mods/myfont/font.properties
ttf=fonts/NanumGothic.ttf   # 계층 디렉터리 기준 (Content.find, 위 계층 우선) 또는 절대 경로
index=0                     # .ttc 안 폰트 번호
scale=1.0                   # FLP 글자 높이(em) 에 대한 배율
yOffset=0                   # 기준선 이동, em 비율 (+ = 아래)
mode=missing                # missing = FLP 폰트에 없는 글자만 / all = 모든 글자
bakeSize=48                 # 아틀라스에 굽는 em 픽셀 크기 (8–256)
```

- 크기: FLP 라벨의 글자 높이 h (`fontHeight/16`) 를 em 으로 보고, TTF em 도 h × scale 에 맞춘다. 기준선은 FLP 글리프와 같은 자리다.
- `mode=missing`: FLP 폰트 표 (`Font.glyphOf`) 에 없는 글자만 TTF. 원작 폰트에 없는 한글·기호를 보충할 때.
- `mode=all`: TTF 에 있는 글자는 전부 TTF, TTF 에 없으면 FLP 글리프.
- 버튼 태그 (`[CircleButton]` 등) 아이콘은 그대로 FLP 클립.
- 구현:
  - `TtfGlyphs`: stb_truetype (lwjgl-stb) 로 코드포인트 하나를 굽는다 (GL 없음).
  - `TtfFont`: 처음 쓰는 글자를 굽어 1024² 아틀라스 장 (RGBA, 흰색 + 알파) 에 `glTexSubImage2D` 로 올린다. 장이 차면 새 장 (`GlyphPacker` 선반 배치, 1px 간격). 한글처럼 글자가 많아도 쓴 글자만 굽는다.
  - 라벨 하나의 TTF 글자는 장마다 사각형으로 모아 basic 셰이더로 한 번에 그린다. 색·변환은 FLP 글리프와 같다 (uTint = 라벨색 × 노드색, uModel = 라벨 변환).
- 실패 (파일 없음·해석 실패·범위 밖 값) 는 `[font]` 경고 후 FLP 그대로 또는 기본값.
- 한계: 커닝 없음, BMP 밖 문자 (서로게이트 쌍) 없음, 아틀라스 장은 끝날 때까지 유지.
- 테스트 `TtfFontOverrideTest` (5개): 설정 없음 = null, 위 계층 ttf·옵션, 없는 파일·잘못된 값, 아틀라스 선반 배치 (겹침 없음), 시스템 한글 폰트 굽기 (있을 때). GL 그리기는 컴파일만.

## 8. 텍스트·대사

게임 문장은 전부 메시지 표 하나에서 나온다: `R_PERMK.WAD` 의 원시 파일 `msgsK.txt` (`*ID*` 줄 + 본문, `MessageTable`).
- 쓰는 곳: HUD·메뉴의 FLP 동적 라벨 `PS2_<ID>`, 스크립트 ShowMessage(Internal 0xA) 안내·대사 상자 (`GameScene.showMessage`), 질문 상자 (`QuestionMenu`), 보너스 메뉴, 메모리 카드 문구.
- 이 표는 모두 `MessageTable.fromWad` 로 만든다 (`GameSession`, `UiLayer`, `HeroTestScene`). 교체는 그 안에서 한 번 적용되므로 위 모든 자리에 함께 반영된다.
- 동영상 속 자막은 PSS 영상에 박혀 있어 이 표와 무관하다 (§10 로 영상째 바꾼다).

교체 파일 (계층마다, 위 계층이 이김):

| 파일 | 형식 | 뜻 |
|------|------|----|
| `text/messages.properties` | UTF-8 properties `ID=본문` | ID 단위 덮어쓰기. 없던 ID 는 추가 (새 FLP 라벨·스크립트 메시지용) |
| `text/msgs.txt` | 원본 `*ID*` 형식 | 가장 위 계층 것 하나로 표를 통째 교체 |

- 적용 순서: WAD 표 → `msgs.txt` (있으면 통째) → `messages.properties` (아래 계층부터 키 단위).
- 본문의 줄바꿈은 `\n`. `----` (페이지 구분), `[CircleButton]` 같은 버튼 태그, `[*1]` 서체 태그 (주황 기울임, 07 문서) 는 그대로 적는다.
- 글자는 FLP 폰트에 있는 글리프만 그려진다. 없는 글자는 폰트 교체(§7)가 필요하다.

꺼내기 도구 — 원본 표(교체 전)를 이 형식으로 저장한다:

```
./gradlew :tools:run --args="text:export gow-rom/R_PERMK.WAD mods/mymod/text/messages.properties"
```

예 (`mods/mymod/text/messages.properties`, 바꿀 줄만 남김):

```properties
# 옵션 메뉴 제목
4051=설정
# 안내 상자: 두 줄 + 버튼 태그
101=크레토스여.\n[CircleButton] 을 눌러 문을 열어라.
# 새 메시지 (스크립트 ShowMessage(90001) 용)
90001=새로 만든 안내문
```

## 9. 스테이지

`stages.properties` (계층마다 키 단위로 합침, `Stages` 클래스):

```properties
# 새 게임 시작 레벨 (기본 ATHN01A)
start=ATHN01A
# 시작 인스턴스 (비우면 레벨 기본 시작점). --entry 와 같은 이름
start.entry=
# 레벨 ATHN02A 를 적재할 때 MYLVL01.WAD 를 읽는다
redirect.ATHN02A=MYLVL01
```

- `start` 는 셸의 새 게임, `--game` (레벨 생략 시), `GameFlow` NEW_GAME 이 쓴다. `start.entry` 는 시작 레벨일 때만 쓴다.
- `redirect.X=Y`: 레벨 X 를 적재하는 모든 자리가 `Y.WAD` 를 읽는다.
  - 새 게임, 스크립트 레벨 전환 (Load / LoadWad / Warp), LoadCheck 이웃 슬롯, 이음 전환 대상 존재 검사 (`LevelLink`), 숙주 레벨 탐색 (`HostLevel`), 보스전 리소스 (`BossDirector`·각 Fight), `--game X`.
  - 레벨 WAD 접근은 `Stages.wad(rom, 레벨)` 한 곳을 지난다 (`GameScene` 의 `Content.file(rom, name + ".WAD")` 를 모두 바꿈).
  - Y 는 rom 이나 모드 `rom/` 에 둔 새 WAD 여도 된다 (§3). 어느 계층에도 없으면 경고하고 원래 X 를 읽는다.
  - 연쇄는 따르지 않는다 (X→Y 한 번).
- **바뀌는 것은 읽는 파일뿐이다.** 레벨의 논리 이름은 X 그대로다.
  - 세이브 데이터의 레벨, 스크립트가 부르는 LoadWad/LoadCheck 이름, 이웃 슬롯·카메라 레벨 비교, 보스 표 (`BossDirector.hostsBoss`), 오프닝 판정 (ATHN01A 첫 진입 → Semo01) 은 모두 X 로 본다.
  - 이를 위해 대신 읽은 WadFile 의 `name()` 도 `X.WAD` 다 (`Stages.logicalName`). 같은 Y 를 다른 이름으로도 직접 열면 그 이름도 X 로 보인다.
  - WAD 태그 교체 (§5) 는 실제 파일 이름 기준이다 (`wad/Y/…`).
- 새 레벨 Y 의 스크립트가 다음 레벨로 갈 때는 원작 이름 (또는 다른 redirect 키) 을 부르면 된다.
- 시작 레벨을 바꿔도 오프닝 동영상은 ATHN01A 진입에만 붙는다.
- `TraversalVerifier` (전 레벨 도달 검증) 는 원본 rom 기준이라 이 표를 보지 않는다.
- 파일이 없으면 원작과 같다.

## 10. 이벤트 씬

### 10.1 동영상 (`movies.properties`)

컷신은 이름으로 재생된다: 스크립트 PlayMovie 인자 (`a01Mp03`, `Semo02` …), 새 게임 오프닝 `Semo01`, 보너스 메뉴 영상.
모두 `MovieScene` → `MoviePlayer.open` → `MovieSource.open` 을 지난다.

```properties
# Semo01 대신 MYINTRO.PSS (mods/<m>/rom/MYINTRO.PSS, 없으면 ISO TOC)
Semo01=MYINTRO
# 이 영상은 틀지 않는다
a01Mp03=skip
```

- 키는 대소문자·확장자 무관. 연쇄 없음.
- `skip`: 재생 없이 바로 끝난다. 스크립트에는 원래 이름으로 종료 이벤트 501 이 간다 (`GameScene.playMovie`, `--skip-movies` 와 같은 길). 음악도 끊지 않는다.
- 바꿔 튼 영상도 끝 이벤트는 원래 이름이다 (스크립트의 `EventArg(0) == "Semo02"` 검사가 그대로 맞음).
- 이름을 그대로 두고 파일만 바꾸려면 `mods/<m>/rom/A01MP03.PSS` 를 두면 된다 (`MovieSource.open` 이 `Content.file` 로 찾음, ISO 보다 먼저). 와이드 모드는 `.PSW` 를 먼저 찾는다.
- 형식은 원작과 같은 PS2 PSS (MPEG-2 + ADS 음성) 여야 한다.

### 10.2 스크립트 연출 (ESC)

엔진 안 연출 (카메라 팬·문 열기·대사 순서 등) 은 레벨 WAD 의 ESC 스크립트다. §5 의 태그 교체로 바꾼다.
- `WadFile.load` 가 교체를 적용하므로 스크립트 로더 (`LevelData`·`ScriptWorld`) 는 바뀐 바이트를 그대로 읽는다.
- 같은 바이트로 교체해도 레벨이 그대로 적재됨을 테스트로 확인한다.

테스트 (`ModTextStagesMoviesTest`, 6개): 메시지 덮어쓰기·통째 교체·새 ID, 내보내기 왕복 (R_PERMK 737개), 스테이지 기본값·redirect (논리 이름 유지, 대상 없음 → 원래 WAD), 동영상 바꿈·skip·rom/ 교체, ESC 태그 교체 (ARENA06).

태그 이름 찾기:

```
./gradlew :tools:run --args="wad-ls gow-rom/ATHN01A.WAD"                  # 모든 태그 (ESC_… 리소스 이름)
./gradlew :tools:run --args="script:entities gow-rom/ATHN01A.WAD"         # ESC 별 엔티티·슬롯 상수, 소유 오브젝트/인스턴스
./gradlew :tools:run --args="script:entities gow-rom/ATHN01A.WAD --code"  # 핸들러 명령까지
./gradlew :tools:run --args="ui:script gow-rom/ATHN01A.WAD"               # 디스어셈블 전체
```

예: `mods/mymod/wad/ATHN01A/ESC_<이름>.bin` = 고친 ESC 리소스 데이터 (헤더 0x24 + 엔티티 표, 13 문서 형식).
- 이름이 같은 ESC 태그가 여럿이면 모두 바뀐다.
- 스크립트 안 메시지 ID 는 §8 표, 동영상 이름은 §10.1 표를 그대로 탄다.

## 11. 사운드

이름 있는 소리 하나를 WAV 로 바꾼다. 효과음 `SND_*` 과 음악 `SND_M_*` 모두 같은 자리다.

```
mods/mymod/sound/SND_IG_POPUP.wav        메뉴 팝업 효과음
mods/mymod/sound/SND_M_ATH1C1_4.wav      아테네 음악 (VPK 스트림 대신)
mods/mymod/sound.properties              (선택) loop.SND_AMB_FIRE=true
```

- 형식: RIFF PCM 16 비트 (8 비트도 됨), 모노·스테레오, 샘플레이트 자유. 3 채널 이상은 앞 두 채널만.
- 파일 이름 = SND 이름 + `.wav`. 대소문자 무시. 이름 목록은 `tools audio:sbk <wad>` (뱅크 소리 이름), 음악은 `tools audio:music <gow-rom>`. 원본 소리를 WAV 로 들어 보려면 `audio:sbk <wad> <outDir>`, `audio:wav <VPK>`.
- 갈아 끼우는 자리: `SoundManager` 가 소리를 시작하는 세 곳.
  - `play` (이름·TWK 해시·전투 이벤트 해시·SoundController 이미터)
  - 자식 소리 (`START_CHILD` 그레인)
  - `music`
- 교체가 있으면 그레인 프로그램을 돌리지 않는다. 대신 보이스 하나로 WAV 를 재생한다 (`SoundInstance.playOverride`).
- 원래 경로에서 그대로 쓰는 것:
  - 볼륨: 핸들러 × 소리 × 볼륨 그룹 × 마스터 (`baseVoice`)
  - 3D 위치·거리 (`setPosition`/`setRange`)
  - 물속 피치·감쇠, 인스턴스 제한, `stop`/`kill`, 음악 핸들
- 모노는 메모리 클립이라 3D 로 들린다. 스테레오는 메모리 스트림이라 위치가 없다 (OpenAL 은 스테레오를 공간화하지 않음). 3D 효과음은 모노로 만든다.
- 반복:
  1. WAV `smpl` 청크의 첫 루프 시작부터 반복
  2. 없으면 `sound.properties` 의 `loop.<SND이름>=true|false`
  3. 둘 다 없으면 음악은 처음부터 반복, 효과음은 한 번
  - `loop.<이름>=false` 는 smpl 루프도 끈다.
- 원작의 무작위 변형 (`RAND_PLAY`)·피치 흔들림 (LFO)·엔벨로프는 WAV 소리에 적용되지 않는다.
- 읽은 WAV 는 관리자에 캐시한다 (이름당 한 번 디코드). 교체가 없으면 조회 한 번 (빈 표) 뿐이다.
- 뱅크 안 샘플 자체를 바꾸려면 SND 뱅크 태그 교체 (§5), VAG/VPK 파일 통째는 `rom/` (§3).

코드: `tools/content/SoundOverrides` (경로 표), `tools/audio/WavClip` (WAV 읽기), `engine/audio/MemoryPcmStream`.

## 12. 애니메이션

ANM 세트 안 클립을 이름으로 바꾸거나 재생 속도를 바꾼다. 계층마다 `anim.properties` 를 덮어 합친다.

```properties
# 크라토스 서 있기를 걷기 클립으로
replace.standIdle=walkBlend1
# 모든 세트의 walkBlend2 를 1.5 배 빠르게
speed.walkBlend2=1.5
# 한 세트에만: <클립>@<ANM세트> (이름만 쓴 키보다 우선)
speed.land@ANM_hero=0.5
replace.idle@ANM_MIN=idle2
```

- 이름은 대소문자를 가리지 않는다.
- 세트 지정은 `@` 로 쓴다. `:` 와 `=` 는 properties 의 키·값 구분자라 키에 쓸 수 없다.
- `replace.<클립>=<다른 클립>`
  - `AnimationSet.find` 가 이름을 찾을 때 같은 세트의 `<다른 클립>` 을 돌려준다. 그 세트에 없으면 무시한다.
  - 엔진의 클립 이름 조회 (영웅 이동·전투 기술·적·보스·발판·카메라) 는 모두 여기를 지난다.
  - 한 단계만 바꾼다 (사슬 없음). 교체 없는 원래 조회는 `findOriginal`.
  - 주의: 게임 로직이 `current().name` 을 비교하는 자리 (예: 착지 `land` 끝 대기) 는 바꾼 클립 이름을 보게 된다.
- `speed.<클립>=배율`
  - `Animator.update` 의 클립 시간 진행에 곱한다. 배율은 재생 시작 때 한 번 조회한다.
  - 전투 기술 속도 (`playClip` 의 rate) 와 곱해진다. 크로스페이드 중 직전 클립은 자기 배율을 쓴다.
  - 같은 시간축을 보는 것이 함께 맞춰진다.
    - 루트 모션: 시간 차분.
    - 전투 진행도와 이벤트: 클립 시간 / 길이.
    - 한 번 재생 끝 판정.
  - 루프 이동 클립의 초당 이동량도 배율을 곱한다 (`HeroController.clipSpeed`, `EnemyFighter.clipSpeed`). 그래서 발이 미끄러지지 않는다.
  - 클립 없이 시간만 쓰는 기술 (`Combatant` 의 vclip) 과 원작 고정 시간 (TWK 초 단위 값) 은 바뀌지 않는다.
- 클립 데이터 자체 (새 동작, 다른 길이) 는 ANM 태그 교체로 바꾼다 (§5).
  - 예: `wad/R_HERO0/ANM_hero.bin`.
  - 외부 그룹 (`ANM_hero_Athn01A` 등) 은 그 레벨 WAD 의 태그다.
  - 꺼내기는 `tools wad:get`.
- 표가 비면 조회는 곧바로 돌아온다. 이름 조회는 빈 표 확인, 재생 시간은 곱셈 하나만 더해진다.

코드: `tools/content/AnimOverrides`, `AnimationSet.find`, `engine/anim/Animator` (`speedOf`).

## 13. 적 AI

두 갈래다. 수치만 바꿀 때는 `ai.properties`, 행동 자체를 바꿀 때는 코드 플러그인.

### 13.1 수치 덮어쓰기 `ai.properties`

계층마다 덮어 합친 표에서 `<열쇠>.<항목>=값` 을 읽는다 (`AiOverrides`).

- 적용 지점은 한 곳: `EnemyFighter` 생성자. AI1 템플릿 → Sold 번호를 푸는 자리다.
  - `AiTemplate` 는 읽을 때 얹고, `AiData` 레코드 (`Motion`·`Body`·`Ride`·`Soldier`·`Stone`) 는 바꾼 사본을 만든다. 원본 레코드는 공유되므로 건드리지 않는다.
  - 표가 비면 `AiOverrides.NONE` → 모든 값이 원래 그대로.
- 열쇠 (대소문자 무시, 낮음 → 높음 — 같은 항목이면 뒤가 이김):
  1. `*` — 모든 적
  2. 적 WAD 이름 — `r_min`, `min` (레벨 자체 템플릿은 레벨 WAD 이름, 예 `athn01d`)
  3. 템플릿 `Instance Name` — 공백은 `\ ` 로 (`hydra\ enc2.health=...`)
  4. AI1 번호 — `50`, `050`
- 값: `10` = 바꿈, `*1.5` (또는 `x1.5`) = 원래 값에 곱함, `0x...` = 16진.

| 항목 | 뜻 (원작 필드, 17·32·35·38 문서) |
|------|------------------------------|
| `health` | HP (= `tmpl.hitPoints` + `tmpl.maxHitPoints`) |
| `damage` | 공격 피해 배율 (= `tmpl.damageMult`, AI1 Damage Multiplier) |
| `tree` | 결정 트리 번호 (= `tmpl.decisionTree`, TWK_DTre_NNN) — 다른 적의 트리를 빌려 쓸 수 있음 |
| `tmpl.<필드>` | `AiTemplate` 필드: `sightRange`·`sightFov`·`block`·`tokenRadius`·`waitRadius`·`dwellMs`·`speedPct`·`leashRange`·`scaleCenter`·`scaleRange`·`mode`·`flags`·`moveCaps`·`soldier`·`blockMod`·`circleHp` … |
| `motion.<필드>` | Sold 이동 속도: `ground`·`accel`·`decel`·`maxSpeed`·`minSpeed`·`wallMin`·`wallSide`·`wallUp`·`rope` (m/s) |
| `body.<필드>` | 몸 상자: `half`·`height`·`base` (m), `flags` (비트 1 = 밀기 끔), `joint` |
| `ride.<필드>` | 점프·줄: `jump`·`zipSpeed`·`zipAccel`·`climb`·`ropeJumpUp`·`ropeJumpOut` |
| `soldier.<필드>` | 공격 토큰: `cost`·`capacity`·`capA`·`capB` |
| `stone.<필드>` | 석화: `resist` (≥ 60000 면역), `move`, `statue` (석상 GO, 빈 값 = 없음), `freeze` (석화 순간 GO) |

예 (`mods/hard/ai.properties`):

```properties
# 모든 적 피해 2배, 미노타우로스 HP 200 · 시야 2배, 해골 병사(020) 지상 속도 1.5배
*.damage=*2
min.health=200
050.tmpl.sightRange=*2
020.motion.ground=*1.5
```

- Sold 가 없는 적 (레코드 null) 의 `motion`·`body`… 는 바꾸지 않는다.
- 영웅 Sold (`AiTargets.setHeroSoldier`) 는 대상이 아니다.
- 적용되면 `[mods] ai 50: {…}` 를 찍는다.

### 13.2 코드 플러그인 `GowPlugin`

`devple.gow.engine.mod` 패키지.

| 클래스 | 하는 일 |
|--------|---------|
| `GowPlugin` | `onStart(PluginContext)` 한 번, `onFrame(dt)` 매 프레임 (기본 빈 함수) |
| `PluginContext` | `replaceEnemy(열쇠, 적 → EnemyController)`, `content()`, `properties(rel)` |
| `EnemyController` | `update(EnemyFighter self, float dt)` — 원래 AI 대신 불림 |
| `Plugins` | 찾기·등록표. `start()` / `frame(dt)` / `reset()` |

- 찾는 곳 (`java.util.ServiceLoader`):
  - 클래스패스의 `META-INF/services/devple.gow.engine.mod.GowPlugin`
  - 각 계층의 `plugins/*.jar` (`Content.listAll("plugins")` → `URLClassLoader`, 같은 클래스 이름은 한 번)
- 시작은 `GowMain` 이 한 번, 매 프레임 훅은 `GameScene.updateTick`. `start()` 전 (테스트·도구) 에는 등록표가 비어 원작 그대로다.
- 이음매는 `EnemyFighter.update` 한 곳이다.
  - 생성자 끝에서 `Plugins.controllerFor(this, modKeys)` 로 대체 제어를 찾는다. 열쇠는 §13.1 과 같고 구체적인 것이 이긴다.
  - 있으면 결정 트리 분기 (`brain.tree != null`) 앞에서 그것을 부른다.
  - 석화·사망·AI 정지 (`holdAi`) 분기, 기술·피격 반응·루트 모션은 원래 그대로다.
- 제어가 쓰는 것:
  - 이동: `self.steer(스틱, 바라볼 yaw, dt)` — 원래 AI 와 같은 내비게이션 (바닥·벽·줄) + 이동 애니.
  - 기술: `self.combat().request(기술 ID)`.
  - 대상: `self.foe()`. 모드 열쇠: `self.modKeys()`.
  - 원래 AI 위임: `self.brain().update(dt); self.steer(self.brain().move, self.brain().face, dt);`
- 예외: `onStart` 실패는 그 플러그인만 건너뛰고, `onFrame` 예외를 낸 플러그인은 끈다.

예제 (`engine/src/test/java/devple/gow/engine/mod/ExampleBrainPlugin.java`, 테스트 자원 META-INF/services 로 등록):

```java
public final class ExampleBrainPlugin implements GowPlugin {
	public void onStart(PluginContext ctx) {
		ctx.replaceEnemy("min", f -> (self, dt) -> {           // 미노타우로스 = 대상 쪽으로 걷기만
			Vector3f d = new Vector3f(self.foe().position()).sub(self.pos).setComponent(1, 0);
			self.steer(d.normalize(), (float) Math.atan2(-d.x, -d.z), dt);
		});
	}
}
```

jar 로 낼 때: 엔진 클래스 (`engine/build/install/engine/lib/*.jar`) 를 컴파일 클래스패스에 두고, jar 에 `META-INF/services/devple.gow.engine.mod.GowPlugin` (구현 클래스 이름 한 줄) 을 넣어 `mods/<m>/plugins/` 에 둔다.

### 13.3 테스트

- `EnemyModTest`: 4개.
  - 레코드 사본·열쇠 우선순위·배율·16진·null 유지.
  - 표 없으면 `NONE` (원래 레코드 그대로).
  - R_MIN 생성: `health`·`tmpl.sightRange=*2`·`damage` 우선순위.
  - 예제 플러그인: ServiceLoader 로 찾고, 미노타우로스만 대체 (해골은 원래 AI), 60 틱 동안 제어가 불려 대상 쪽으로 걷고 결정 트리 공격 0, `onFrame` 호출.
- `StaticStateReset` 이 테스트마다 `Plugins.reset()` — 예제 플러그인이 다른 테스트에 새지 않는다.

## 14. 그래픽

텍스처를 PNG 로 바꾼다. WAD 텍스처 해독 (`TextureDecoder`) 결과 대신 쓴다.

```
mods/mymod/textures/ATHN01A/TXR_Athn01A003.png    그 WAD 에서만 (.WAD 뺀 대문자 이름)
mods/mymod/textures/TXR_KratosBody.png            모든 WAD
mods/mymod/textures/ATHN01A/TXR_Water01.2.png     애니메이션 텍스처 프레임 2 만
mods/mymod/normalmaps/TXR_Athn01A003.png          노멀맵 (44 문서, 선택 기능)
```

- 같은 이름이 WAD 별과 전역에 다 있으면 WAD 별이 이긴다. 계층 사이에서는 위 계층이 이긴다.
- 크기는 자유다. 원본과 같은 UV 가 같은 그림을 가리키게 맞춘다 (`TextureOverrides.fit`).
  - 원본과 같은 크기면 그대로 쓴다 (원본처럼 2 의 거듭제곱으로 패딩).
  - 원본의 정수배 (2 배·4 배) 면 그 배율의 텍스처로 복사한다.
  - 2 의 거듭제곱이 아닌 원본도 된다. 예: 512×72 → 업로드 512×128. 1024×144 PNG → 1024×256 의 위 144 줄.
  - 그 밖의 크기는 같은 비율 자리로 쌍선형 크기 변경.
- 색·알파는 일반 8 비트 RGBA 다. 해독기가 PS2 알파 (0x80 = 불투명) 를 이미 0..255 로 늘려 내므로 PNG 알파 255 = 불투명이다.
  - 알파 없는 PNG 는 불투명.
  - 반투명 판정 (머티리얼·TXR 플래그) 은 원본 그대로라 PNG 알파만 바꿔서는 블렌드 방식이 안 바뀐다.
- 애니메이션 텍스처 (GFX 프레임 &gt; 1):
  - `<TXR>.<프레임>.png` 가 있으면 그 프레임.
  - 없으면 `<TXR>.png` 를 세로 스트립으로 보고 프레임 수로 자른다 (원본 GFX 와 같은 배치).
- 이름이 겹치는 R_PERM 텍스처 (`textureBefore`, 태그 번호로 고른 사본) 에도 같은 이름 PNG 가 쓰인다.
- 갈아 끼우는 자리: `WadAssets` 의 `texture` / `textureFrames` / `textureBefore` / 미리 해독 (`predecode`). 엔진의 텍스처 해독은 모두 여기를 지난다 (레벨·캐릭터·이펙트·HUD/FLP).
- 노멀맵: 바꾼 텍스처의 생성 노멀맵은 PNG 에서 만든다. 덮어쓰기 출처 순서:
  1. 계층 `normalmaps/<키>.png`
  2. `work/normalmaps/<키>.png`
  3. CPU 생성
  - 키는 `NormalMaps` 와 같다: TXR 이름, 사본 `이름@태그`, 프레임 `이름#프레임`.
- 내보내기: `tools texture:export <wad> <outDir> --mod`.
  - `outDir/<WAD>/<TXR>.png` 로, 엔진이 쓰는 항목 (이름별 첫 항목, CLUT 0) 만 쓴다.
  - `outDir` 를 `mods/<m>/textures` 로 주고 고칠 파일만 남기면 된다.
  - 예: `tools texture:export gow-rom/ATHN01A.WAD mods/hd/textures --mod`
- GFX/PAL/TXR 데이터 자체 (형식·팔레트) 를 바꾸려면 태그 교체 (§5).
- 교체가 없으면 텍스처마다 빈 표 확인 한 번뿐이다 (텍스처는 처음 올릴 때만 조회).

코드: `tools/content/TextureOverrides`, `engine/asset/WadAssets` (`textureImage` = CPU 그림, 확인용).

### 11–14 테스트

`ModOverridesTest`: 5개.
- PNG 크기 맞춤 (같은 크기·정수배·2 의 거듭제곱 아닌 원본·임의 배율).
- R_HERO0 실제 TXR 의 WAD 별·전역 PNG 교체 (반투명 알파 그대로), 다른 WAD 디렉터리 무시, 모드 없으면 해독 그대로.
- ANM_hero 의 `replace` (대소문자 무시, 없는 대상 무시), `speed` (이름·세트 지정, 전투 rate 와 곱).
- WAV 읽기 (스테레오·smpl 루프·잘못된 파일).
- 헤드리스 SoundManager: 효과음 WAV 교체 (3D 클립, 한 번), 다른 소리는 원래 경로, 음악 스테레오 스트림 반복·정지.

## 15. 도구 · 다른 디스크

### 15.1 다른 디스크 (지역판)

`GowDisc` 는 판마다 다를 수 있는 것을 이름으로 찾는다.

| 항목 | 한글판 SCKA_300.02 | 일반화 |
|------|--------------------|--------|
| 부트 실행 파일 | `SYSTEM.CNF` `BOOT2 = cdrom0:\SCKA_300.02;1` | `SystemCnf.read(iso)` → `executable()`·`serial()`·`region()` (앞 네 글자: SCUS/SLUS 북미, SCES/SLES 유럽, SCPS/SLPS/SLPM/SCAJ 일본·아시아, SCKA/SLKA 한국) |
| 목차 | `/GODOFWAR.TOC` | `GODOFWAR.TOC` 우선, 없으면 처음 만난 `*.TOC` |
| PAK | `PART1.PAK` (레이어 0), `PART2.PAK` (레이어 1) | `PART1`..`PART9.PAK`, 레이어·디렉터리 무관. 1층 디스크는 `PART1` 만 (목차 part 번호 n → `PART<n+1>`) |
| 레이어 1 볼륨 | 레이어 0 크기 − 16 섹터 | `IsoImage` 가 −16 / +0 두 위치를 시도 (예전 그대로) |
| 언어 접미사 | `R_PERMK.WAD` → `K` | 목차의 `R_PERM*.WAD` 이름 (`Region.suffixes`), 고르기는 §4 (`Region.choose`) |

```
$ ./gradlew :tools:run --args="disc:info P2_gow_kor.iso"
레이어 0: PLAYSTATION / GODOFWAR, 2067056 섹터
레이어 1: PLAYSTATION / GODOFWAR, 2061856 섹터
부트: SCKA_300.02 (SCKA-30002, 한국 (NTSC-K), VER 1.01, NTSC)
목차: GODOFWAR.TOC, 파일 951 개 (사본 포함 1632), PAK [PART1.PAK@L0, PART2.PAK@L1]
언어 접미사 (R_PERM*.WAD): [K] → R_PERMK.WAD
```

`iso-info` 도 끝에 `boot:` 줄을 찍는다 (갓오브워가 아닌 PS2 디스크도).

### 15.2 `disc:setup` — 추출 + 설정

```
./gradlew :tools:run --args="disc:setup <iso> [romDir=gow-rom] [--all] [--dry-run] [--config gow.properties]"
```

1. `disc:info` 와 같은 요약.
2. 추출: `pak-extract` 와 같은 규칙 (이름별 첫 사본, 크기가 같으면 건너뜀, `--all` 이 아니면 동영상 PSS/PSW 제외 — 엔진은 ISO 에서 바로 읽는다). `pak-extract` 도 이 함수를 쓴다.
3. `gow.properties` 고치기 (`GameConfig.update`) — 덮어쓰지 않고 합친다.
   - 넣는 키: `rom=`·`iso=`. 언어 접미사가 여럿이고 `region` 이 아직 없을 때만 `region=` (K 우선, 빈 접미사는 `-`).
   - 있는 줄은 그 자리에서 값만 바꾸고 (같은 키가 여러 줄이면 전부), 없는 키는 끝에 더한다. 주석·다른 키·순서는 그대로. 바뀐 것이 없으면 쓰지 않는다.
4. `--dry-run`: 추출할 파일과 넣을 값만 찍는다.

예 — 이미 `mods=` 가 있는 설정:

```
$ ./gradlew :tools:run --args="disc:setup /path/SCUS_973.99.iso gow-rom-us"
...
gow.properties: 고침 [rom, iso]

# 내 설정            ← 그대로
mods=a,b            ← 그대로
rom=gow-rom-us      ← 바뀜
iso=/path/SCUS_973.99.iso   ← 끝에 더함
```

> 다른 판은 실행 파일 주소·TWK 해시가 다를 수 있다 (§4 주의). 한글판 외의 디스크로는 아직 검증하지 못했다.

### 15.3 모드 제작 도구

| 명령 | 하는 일 |
|------|---------|
| `wad:get <WAD> <태그> <out.bin>` | 태그 데이터 꺼내기 = `mods/<m>/wad/<WAD>/<태그>.bin` 형식 그대로. 같은 이름이 여럿이고 내용이 다르면 경고 후 첫째 |
| `wad:put <WAD> <태그> <in.bin> <out.WAD>` | 오프라인 교체. 엔진 적재와 같은 `WadPatches.rewrite(raw, Map<이름, 바이트>)` (순수 함수 — 같은 이름 전부, 크기 고침, 16 바이트 정렬) |
| `mod:new <이름>` | `mods/<이름>/` 뼈대: `README.md`, `rom/`, `wad/`, `plugins/`, 주석 처리된 `ai.properties`. 이미 있는 파일은 건드리지 않음. 위치는 설정 `modsDir` |

두 `wad:` 명령은 원본 파일 그대로 읽는다 (모드 계층의 태그 교체를 거치지 않음 — `wad.Cli.raw`).

텍스처 하나 바꾸기:

```
./gradlew :tools:run --args="mod:new hd"
./gradlew :tools:run --args="wad:get gow-rom/ATHN01A.WAD TXR_floor mods/hd/wad/ATHN01A/TXR_floor.bin"
#   … TXR_floor.bin 편집 …
# gow.properties: mods=hd   → 엔진이 적재 때 바꿈 (§5)
./gradlew :tools:run --args="wad:put gow-rom/ATHN01A.WAD TXR_floor mods/hd/wad/ATHN01A/TXR_floor.bin work/ATHN01A.WAD"   # 또는 WAD 통째로 (§3 rom/)
```

### 15.4 테스트

- `DiscToolsTest`: 4개.
  - SYSTEM.CNF 해석 (북미·유럽 예문, 빈 파일) + 실제 한글판 ISO 의 `SCKA_300.02` (읽기만, ISO 없으면 건너뜀).
  - 언어 접미사 목록, `disc:setup` 의 region 규칙, `gow.properties` 합치기 (주석 유지·중복 키·바뀐 것 없으면 안 씀).
  - `wad:get`/`wad:put` 왕복: 같은 데이터면 원본과 바이트 동일, 길이가 다르면 다른 태그 보존, 모드 계층 `wad/` 적재 결과 = 오프라인 put.
  - `mod:new` 뼈대와 기존 파일 보존.

## 16. 테스트

- `ContentLayersTest`: 4개.
  - 위 계층 rom 파일 우선, 속성 덮어 합치기.
  - 모드 없을 때 원래 경로.
  - 태그 교체: 길이가 바뀌어도 다른 태그 보존.
  - 지역판 감지와 설정 우선.
- 종류별 테스트는 각 절 끝에 적었다:
  - `KeyBindingsTest` 4개, `TtfFontOverrideTest` 5개 (§6–§7).
  - `ModTextStagesMoviesTest` 6개 (§8–§10).
  - `ModOverridesTest` 5개 (§11, §12, §14).
  - `EnemyModTest` 4개 (§13).
  - `DiscToolsTest` 4개 (§15).
- 합계 32개. 전체 엔진 테스트는 460개다.
