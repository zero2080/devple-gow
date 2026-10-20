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
| 8 | 텍스트·대사 | `text/messages.properties` | 진행 중 |
| 9 | 스테이지 | `stages.properties` + `rom/` | 진행 중 |
| 10 | 이벤트 씬 (동영상·스크립트) | `movies.properties` + `rom/*.PSS` + `wad/` | 진행 중 |
| 11 | 사운드 | `sound/<SND이름>.wav` + `sound.properties` | 완료 |
| 12 | 애니메이션 | `anim.properties` + `wad/` | 완료 |
| 13 | 적 AI | `ai.properties` + 플러그인 | 진행 중 |
| 14 | 그래픽 (텍스처 PNG) | `textures/` + `normalmaps/` | 완료 |

(각 절은 구현과 함께 아래에 채운다.)

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

(구현과 함께 채운다.)

## 16. 테스트

- `ContentLayersTest`: 4개.
  - 위 계층 rom 파일 우선, 속성 덮어 합치기.
  - 모드 없을 때 원래 경로.
  - 태그 교체: 길이가 바뀌어도 다른 태그 보존.
  - 지역판 감지와 설정 우선.
