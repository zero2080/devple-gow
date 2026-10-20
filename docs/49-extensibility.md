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
| 11 | 사운드 | `sound/<SND이름>.wav` | 진행 중 |
| 12 | 애니메이션 | `anim.properties` + `wad/` | 진행 중 |
| 13 | 적 AI | `ai.properties` + 플러그인 (`plugins/*.jar`) | 구현 (§13) |
| 14 | 그래픽 (텍스처 PNG) | `textures/` | 진행 중 |

(각 절은 구현과 함께 아래에 채운다.)

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
| `stone.<필드>` | 석화: `resist` (≥ 60000 면역), `move` |

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
