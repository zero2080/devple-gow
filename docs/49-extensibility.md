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
| 8 | 텍스트·대사 | `text/messages.properties` | 진행 중 |
| 9 | 스테이지 | `stages.properties` + `rom/` | 진행 중 |
| 10 | 이벤트 씬 (동영상·스크립트) | `movies.properties` + `rom/*.PSS` + `wad/` | 진행 중 |
| 11 | 사운드 | `sound/<SND이름>.wav` | 진행 중 |
| 12 | 애니메이션 | `anim.properties` + `wad/` | 진행 중 |
| 13 | 적 AI | `ai.properties` + 플러그인 | 진행 중 |
| 14 | 그래픽 (텍스처 PNG) | `textures/` | 진행 중 |

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

## 15. 도구 · 다른 디스크

(구현과 함께 채운다.)

## 16. 테스트

- `ContentLayersTest`: 4개.
  - 위 계층 rom 파일 우선, 속성 덮어 합치기.
  - 모드 없을 때 원래 경로.
  - 태그 교체: 길이가 바뀌어도 다른 태그 보존.
  - 지역판 감지와 설정 우선.
