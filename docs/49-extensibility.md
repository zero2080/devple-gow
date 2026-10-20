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
| 6 | 키 변경 | `keys.properties` | 진행 중 |
| 7 | 폰트 | `font.properties` + TTF | 진행 중 |
| 8 | 텍스트·대사 | `text/messages.properties` (+ `text/msgs.txt`) | 완료 |
| 9 | 스테이지 | `stages.properties` + `rom/` | 완료 |
| 10 | 이벤트 씬 (동영상·스크립트) | `movies.properties` + `rom/*.PSS` + `wad/` | 완료 |
| 11 | 사운드 | `sound/<SND이름>.wav` | 진행 중 |
| 12 | 애니메이션 | `anim.properties` + `wad/` | 진행 중 |
| 13 | 적 AI | `ai.properties` + 플러그인 | 진행 중 |
| 14 | 그래픽 (텍스처 PNG) | `textures/` | 진행 중 |

(각 절은 구현과 함께 아래에 채운다.)

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
- 본문의 줄바꿈은 `\n`. `----` (페이지 구분), `[CircleButton]` 같은 버튼 태그, `[*1]` 인자 자리는 그대로 적는다.
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

## 15. 도구 · 다른 디스크

(구현과 함께 채운다.)

## 16. 테스트

- `ContentLayersTest`: 4개.
  - 위 계층 rom 파일 우선, 속성 덮어 합치기.
  - 모드 없을 때 원래 경로.
  - 태그 교체: 길이가 바뀌어도 다른 태그 보존.
  - 지역판 감지와 설정 우선.
