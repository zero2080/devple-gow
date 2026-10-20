# 48. 장시간 실행 안정성 · 성능 · 테스트 위생

코드: `core/PerfMonitor` (측정), `render/GlStats` (GL 객체 수), `GameScene` 체인 (`-Dgow.chain`), `ScriptedInput` 반복·스틱.
백그라운드 적재 (§8): `asset/Loader`, `asset/WadAssets` (prefetch · prepare/upload), `level/LevelScene.prepare`, `GameScene` (prefetch · 이웃 장면 캐시).
전환 프레임·파티클·자세 (§9): `scene/Scene.preload` · `SceneManager`, `GameScene.preload`, `LevelScene.preUpload/exitDeferred`, `render/Shader` (프로그램 풀), `render/GlDeferred`,
`level/HostLevel.candidates`, `tools/fx/EffectLibrary.preparse` · `WadFile.putDerived`, `fx/ParticleSystem` (VBO orphan · 정의별 색인 · 정점 배열), `tools/fx/PtcVu.eval`,
`tools/anim/Skeleton.localMatrixInto/worldInto` · `Mat4.*Into` · `Pose.world`, `AnimatedModel` (스키닝 작업 공간), `core/Engine` (`gow.lockstep`), 시험 `fx/HotPathEquivalenceTest`.
수정: `combat/AiTargets` (정적 슬롯 정리), `level/LevelScene.exit`, `game/GameScene.exit`, `audio/OpenAlBackend`·`SoundManager` (클립 버퍼),
`fx/MagicFx.unbind`, `anim/Animator.pose` · `tools/anim/Pose.world` (메모), `render/Shader.loc`, `light/ShadowVolume` (작업 공간), `fx/ParticleSystem`.
테스트 위생: `test/.../StaticStateReset` (+ `junit-platform.properties`, `META-INF/services`), `EnemyNavTest`, `HitVolumesTest`, `TraversalVerifier.run`.

원작 동작은 바꾸지 않는다: 모든 수정은 자원 해제·캐시·할당 제거이고 계산 순서·값은 같다 (전 레벨 순회 보고서 `work/verify/report.md` 가 수정 전후 글자 하나 다르지 않음).

## 1. 측정 방법

### 1.1 계측 (원작에 없는 진단 기능, 기본 꺼짐)
| 옵션 | 뜻 |
|---|---|
| `-Dgow.perf=파일` | `gow.perf.interval` 초 (기본 10) 마다 한 줄: 시각·틱·프레임, 프레임 작업 시간 (루프 시작 → 스왑 직전, 평균·p99·최대), 프레임 간격, 힙 사용/커밋, GC 횟수·시간·최대 멈춤 (G1 동시 주기·측정용 `System.gc()` 제외), 직접 버퍼, GL 객체 수, 게이지 (레벨·적 수·소리 인스턴스·AL 보이스/버퍼/클립) |
| `-Dgow.perf.ticks=n` | 로직 틱 n 번 (60 Hz) 뒤 종료. 30 초 무렵과 끝에 전체 GC 한 번 → `live=` (살아 있는 힙) |
| `-Dgow.perf.fullgc` | 매 줄마다 전체 GC (레벨 전환 누수 측정용) |
| `-Dgow.chain=A,B,…` / `gow.chain.secs` | 각 레벨에서 n 초 (기본 20) 뒤 다음 레벨로 LoadWad. 떠나기 직전 한 줄 더 |
| `-Dgow.script=…` + `gow.script.loop=초` | 스크립트 입력 반복. 새 항목 `LS:시작:길이:x:y` / `RS:…` (스틱) |
| `-Dgow.novsync` | 스왑 간격 0 |
| (`-Dgow.perf` 안) `# load 시각 이름 ms [스레드]` | 적재 구간 (§8): `enter 레벨` (GameScene.enter), `swap` (씬 교체 프레임의 exit+enter), `other 레벨` (이웃 충돌·스크립트 데이터), `otherScene 레벨` (이웃 그리기 장면 GL), `prep …` (적재 스레드 CPU 단계), `wait …` (메인이 기다린 시간) |
| (`-Dgow.perf` 안) `# hitch 시각 work ms gap ms` | 프레임 작업이 `gow.perf.hitch` ms (기본 25) 이상인 프레임 |
| `-Dgow.syncLoad` | 적재를 제출한 자리에서 (예전과 같은 프레임 순서). `--screenshot` 이면 자동 |
| `-Dgow.otherCache=n` | 떠난 이웃 그리기 장면을 GL 에 붙잡아 둘 개수 (기본 2, 동기 모드는 0) |

`GlStats` 는 텍스처·버퍼·VAO·FBO·프로그램·쿼리의 생성/삭제 지점 (13 파일) 을 감싸 센다 — 음수가 되면 이중 삭제다.
OpenAL 쪽은 `AudioBackend.stats()` (`slots/buf/clips`).

### 1.2 시나리오
- **장시간 (soak)**: 8 레벨 × 게임 시간 180 초 (10800 틱), `-Dgow.giveAll` (마법 4 종·블레이드 Lv2·분노 100) + 24 초 반복 스크립트
  (전진·□□□△ 콤보·점프 공중 □·좌우 이동·L2 마법·방향키 마법 바꾸기·L3+R3 분노·△ 길게·이단 점프·R1). 레벨: ATHN01A (배·비·해골),
  ATHN02D (메두사 여왕 방), PAND03G (불·증기, BMI), OLYMP02 (거울 → 걷다가 OLYMP01 로 이음), DEST01 (사막, 이웃 DEST00/02 번갈아 LoadWad),
  ATHN08B (아레스·가족), PAND04AA (엘리베이터), HDES01A (하데스, 적 6).
- **레벨 체인**: 24 레벨 (ATHN01A → … → OLYMP01 → ATHN01A → ATHN02A, 스크립트 이음으로 생긴 전환 포함 약 26 회) 각 15 초. 누수 추적은 8 레벨 × 2 바퀴 + 매번 전체 GC.
- 환경: Apple Silicon, 120 Hz 화면, vsync 켬 (프레임 작업 시간은 스왑 대기 제외), JDK 21 G1 기본 힙 (최대 6 GB).
- 분석: JFR (`settings=profile`) `jfr view allocation-by-site / hot-methods`, 누수 경로는 `path-to-gc-roots=true` 의 `jdk.OldObjectSample`.

## 2. 레벨 전환 누수 (체인)

### 2.1 수정 전 (24 레벨 체인, 400 초)
| 지표 | 처음 (ATHN01A) | 끝 (약 26 회 전환 뒤) |
|---|---|---|
| 셰이더 프로그램 | 12 | **42** (레벨마다 +1) |
| GL 쿼리 | 0 | **7** |
| 힙 커밋 / 사용 최대 | 472 / 365 MB | **2128 / 1823 MB** |
| 프로세스 RSS | 642 MB | **2686 MB** |
| AL 클립 버퍼 | 84 | **355** (계속 증가) |
| 살아 있는 `GameScene` (히스토그램) | 1 | **7** (+ ScriptWorld 11, LevelScene 8, WadFile 68) |

JFR 경로: `AiTargets.SLOTS` (정적 `WeakHashMap`) → 값 `Slot.ticker` (AiBrain) → `sightBlocked` 람다 → **떠난 GameScene** → LevelScene → WadAssets → WAD 바이트.
값이 키 (영웅 `Hittable`, 적 → `foe`) 를 강하게 잡아 약한 키가 끝내 풀리지 않는 고전적 WeakHashMap 누수다. 적이 있던 레벨을 떠날 때마다 레벨 전체 (수십~수백 MB) 가 남았다.

### 2.2 수정 뒤
| 지표 | 처음 | 끝 (같은 체인) |
|---|---|---|
| 셰이더 프로그램 | 12 | 9–12 (레벨에 따라, 증가 없음) |
| GL 쿼리 | 0 | 0–1 |
| 힙 커밋 | 492 MB | 616 MB (최대 892) |
| RSS | 667 MB | 1054 MB (최대 1435, 이후 내려옴) |
| AL 클립 버퍼 | 76 | 158–172 (R_PERMK 공용 뱅크가 재생된 만큼, 상한 있음) |

같은 레벨 두 번째 방문 (8 레벨 × 2 바퀴, 매번 전체 GC 뒤 살아 있는 힙):

| 레벨 | 수정 전 1 → 2 바퀴 | 수정 뒤 1 → 2 바퀴 | GL tex/buf (뒤, 두 바퀴 같음) |
|---|---|---|---|
| ATHN02A | 208 → 746 MB | 205 → 206 MB | 199 / 3015 |
| PAND03G | 198 → 736 MB | 196 → 197 MB | 102 / 515 |
| DEST01 | 206 → 743 MB | 203 → 204 MB | 122 / 2274 → 120 / 2262 |

(이 비교의 "수정 전" 은 GL 정리 수정 뒤·AiTargets 수정 전 상태 — GL 수는 이미 같고 자바 힙만 바퀴마다 ≈ 540 MB 증가했다.)

## 3. 프레임 핫스팟 (JFR)

수정 전 할당 상위 (ATHN01A / PAND03G 60 초): `Mat4.mul` 21–29 %, `Mat4.identity` 20–29 %, `Pose.<init>` 11–21 %, `ParticleSystem.corners` 14 %,
`DirectMethodHandle.allocateInstance` 5–13 % (호출자 1 위 = `Shader.loc`), `ShadowVolume.build` 2–6 %. 애니메이션 쪽은 한 틱에 같은 Animator 의 자세를 그리기·
관절 조회 (이펙트 부착은 관절마다 자세 + 월드 행렬 전체를 다시 계산)·밀기 상자·히트 볼륨·보스 동기에서 여러 번 처음부터 샘플링한 것이 원인.
CPU 상위는 PAND03G 에서 파티클 VU 에뮬레이션 (`renderVu` 27 %, `PtcVu.eval` 19 %, `fan` 6 %).

| 수정 | 내용 (값 동일) |
|---|---|
| `Animator.pose()` 메모 | 클립·이전 클립·시간·이전 시간·페이드가 같으면 직전 Pose 그대로 |
| `Pose.world(sk)` 메모 | 자세는 만든 뒤 바뀌지 않으므로 월드 행렬을 한 번만 (돌려준 배열은 읽기 전용 — 엔진 안 쓰기 없음 확인) |
| `Shader.loc` | `computeIfAbsent` 의 this 잡는 람다 → get/put (유니폼 설정마다 객체 1 개) |
| `ParticleSystem.corners` | 파티클마다 `float[4][5]` → 지역 변수, 원반·아지랑이 `cos/sin` → 분할 수별 표 (같은 식) |
| `ShadowVolume.build` | 정점 × 3 `Vector3f`·`int[]` 두 개 → 스레드별 작업 공간, `project` 의 dest 판 |
| `ShadowCasters.build` · 카메라 필터 | 조각마다 `new Matrix4f` → 필드, `computeIfAbsent` 람다 → get/put |

### 3.1 장시간 결과 (8 레벨 × 180 초, 첫 10 초 = 적재 제외)

수정 전 (s1: 누수 수정 뒤·핫스팟 수정 전) → 수정 뒤 (s2). GC 횟수는 180 초 동안 (s2 는 측정용 전체 GC 2 회 포함).

| 레벨 | 프레임 작업 평균 / p99 (ms) | GC 횟수 (분당) | GC 총 ms | 최대 GC 멈춤 | 최대 히치 | 살아 있는 힙 30 초 → 180 초 |
|---|---|---|---|---|---|---|
| ATHN01A | 6.8 / 9.5 → 6.7 / 8.8 | 128 (48) → 40 (15) | 60 → 31 | 1 → 1 ms | 21 → 22 | 104 → 106 MB |
| ATHN02D | 4.9 / 6.4 → 4.8 / 6.5 | 98 (37) → 17 (6) | 45 → 25 | 1 → 2 | 15 → 25 | 107 → 107 |
| PAND03G | 13.7 / 17.3 → 12.8 / 15.7 | 62 (23) → 10 (4) | 37 → 22 | 2 → 1 | 41 → 41 | 141 → 145 |
| OLYMP02 (→ OLYMP01) | 7.5 / 9.7 → 7.3 / 9.3 | 128 (48) → 17 (6) | 63 → 35 | 4 → 6 | 181 → 160 (이음 적재) | 138 → 141 |
| DEST01 | 4.7 / 9.9 → 4.6 / 9.8 | 75 (28) → 20 (8) | 86 → 47 | 4 → 2 | 72 → 79 (§6) | 146 → 154 |
| ATHN08B | 5.9 / 8.3 → 5.5 / 7.9 | 104 (39) → 27 (10) | 47 → 28 | 1 → 1 | 18 → 24 | 142 → 145 |
| PAND04AA | 7.7 / 10.1 → 7.5 / 9.7 | 92 (34) → 25 (9) | 36 → 19 | 1 → 1 | 17 → 21 | 85 → 86 |
| HDES01A | 4.7 / 7.2 → 4.5 / 7.3 | 91 (34) → 54 (20) | 39 → 35 | 1 → 1 | 16 → 23 | 97 → 97 |

- GC 는 3–6 배 줄었다 (G1 젊은 세대만, 멈춤 1–2 ms 라 원래도 끊김 원인은 아니었다). 프레임 시간은 PAND03G 에서 −7 % (p99 −9 %), 나머지는 잡음 범위.
- 레벨 안 누수 없음: 살아 있는 힙 150 초 동안 0–4 MB (DEST01 +8 = 이웃 슬롯 교체 시점 차이). GL 수는 적 생성·이웃 적재에 따라 오르내리고 끝에서 증가 없음.
- 소리 인스턴스 최대 6–25, AL 소스 48 개 풀 안 (ATHN08B 분노·가족 장면 25).
- 최대 히치 (프레임 간격) 는 GC 가 아니라 동기 적재: OLYMP02 → OLYMP01 이음, DEST01 의 이웃 LoadWad (§6).

## 4. 수정한 누수

| 누수 | 증상 | 수정 |
|---|---|---|
| `AiTargets.SLOTS` 값 → 키 | 떠난 GameScene 전체 (레벨·WAD·모델·스크립트) | `GameScene.exit` 에서 `AiTargets.clear()` (슬롯은 그 레벨 대상 것뿐). 검증기 레벨마다도 |
| `LevelScene.exit` | 레벨마다 셰이더 프로그램 1 (`skinned`), 쿼리 1 (거울), 자세 GO 메시 (`posedModels`), 거울 합성 FBO/텍스처 | 모두 닫음 (이웃 슬롯 LevelScene 도 같은 경로) |
| `GameScene.exit` | 적·보스 종류 `WadAssets` (텍스처·모델) 를 닫지 않음 | 종류 자산을 identity 집합으로 한 번씩 닫음. `WadAssets.close` 는 두 번 불러도 안전하게 맵 비움 |
| `ShadowRenderer.close` | 네이티브 정점 버퍼 (`memAlloc`) 미해제 | `memFree` |
| `OpenAlBackend.clipBuffers` | 클립 → AL 버퍼 캐시가 끝없이 자람 (뱅크를 내려도 버퍼·PCM 남음) | `AudioBackend.release(clip)`: `SoundManager.unload` 가 버린 클립 · 레벨을 떠날 때 낱개 VAG 캐시 (`trimFileCache`) 를 해제, 그 클립을 쓰는 보이스는 멈춤 |
| VagPack 항목 | **재생마다** 새로 디코드 → 재생마다 AL 버퍼 2 개 + 캐시 항목 | 팩·항목별 클립 한 번 (`packClips`), 팩이 내려가면 해제 |
| `MagicFx.playFxLive` / `stones` | 사라진 적의 Combatant 가 레벨 끝까지, 석화 중 사라진 적의 돌 이미터는 계속 생성 | 적 정리 (사망 4 초) 때 `MagicFx.unbind` — 남은 PlayFX 끄기·돌 이미터 제거. `enemyFall` 항목도 |

## 5. 테스트 순서 의존

증상: 전체 `:engine:test` 의 ATHN02D 헤드리스 메두사 단계 로그가 단독 실행과 달랐다.
단독 = `등장 → 전투 → 시선 → 영웅 석화 → 시선 → 석화 탈출 → 잡기 가능 → 머리 뜯기 → 격파`, 전체 = `… 시선 → 잡기 가능 → …` (석화 없음).

원인: 적 AI 난수 씨앗 = `7 + 7919 × EnemyFighter.spawnCount` (JVM 전역 정적 순번). 앞선 테스트·앞선 레벨이 만든 적 수만큼 메두사의 씨앗이 바뀐다.
`AiTargets.SLOTS` 도 정적이라 앞 테스트의 대상 슬롯 (토큰 예산·포위 칸) 이 남는다.

수정:
- `TraversalVerifier.run` 이 레벨마다 `resetSpawnCount()` + `AiTargets.clear()` → 레벨 결과가 다른 레벨·테스트 순서와 무관.
  이제 단독·전체·섞은 순서 모두 같은 보고서 (메두사: 석화 단계 없이 격파 — 씨앗 0 기준이 이 경로).
- 모든 테스트 앞 `StaticStateReset` (JUnit 확장 자동 감지, `src/test/resources/junit-platform.properties`) — 같은 두 정적 상태를 처음 값으로.
- `-PtestShuffle[=씨앗]`: 클래스·메서드 순서를 섞는 점검 옵션. 처음 돌렸을 때 실패 2: `AiBrainTest.minotaurApproachesAndAttacksWithTemplateCadence`
  (적 씨앗), `EnemyNavTest.climbersOnSameWallPushApartInsideTheWallPlane` (테스트 클래스의 정적 `wallTop` 을 다른 메서드가 160 으로 낮추고 되돌리지 않음 →
  `@BeforeEach` 로 300). 수정 뒤 씨앗 11·12345·3·4·5·7·99 모두 397 통과, 보고서 동일.

## 6. 테스트가 남기는 파일

- `TraversalTest` → `work/verify/report.md` (유지).
- `HitVolumesTest` 가 `work/hitbox/combo3A_front.png` · `combo3A_behind.png` 를 폴더만 있으면 매번 썼다 → `-PgowTestDraw` (`gow.test.draw`) 일 때만.
- 그 밖: `@TempDir` (MemoryCardFlow·SaveGame·Unlocks). 전체 실행 전후 `work/`·`gow-rom/`·`~/Library/Application Support/devple-gow` 에 새 파일은 report.md 하나.

## 7. 남은 문제

1. ~~**동기 적재 히치**: LoadWad·이음은 메인 스레드에서 WAD 읽기·해석·GL 올리기를 한 번에 한다. DEST01 이웃 교체 50–80 ms 되풀이, 레벨 전환 130–350 ms~~
   → §8 (이웃 재교체 히치 없음, 처음 교체 ≤ 27 ms, 전환 프레임 65–156 ms). 남은 메인 스레드 몫은 §8.5.
2. ~~PAND03G 프레임 작업 12.8 ms (p99 15.7) — 대부분 파티클 VU 에뮬레이션~~ → §9.4: 실제로는 파티클 VBO 덮어쓰기의 GPU 동기. 1.9 ms (p99 4.6).
3. ~~애니메이션 샘플 자체 (`Skeleton.localMatrix`·`Mat4.mul` 이 관절마다 `float[16]`) — 할당 1 위~~ → §9.5 (임시 행렬 없음, 돌려주는 월드 행렬만).
4. 힙 커밋은 G1 이 돌려주지 않는다 (체인 뒤 600–900 MB). RSS 1.0–1.4 GB.
5. R_PERMK 공용 소리 뱅크의 클립 캐시는 재생된 소리만큼 자란다 (상한 = 뱅크 크기, 체인 26 회 뒤 ≈ 160).
6. ATHN02D 헤드리스 메두사의 "영웅 석화" 단계는 씨앗에 따라 나오거나 안 나온다 — 이제 결정적이지만 지금 씨앗에서는 안 나온다
   (석화 자체는 `MedusaQueenFightTest.gazePetrifiesHeroAndWiggleBreaksOut` 가 따로 검사).
7. 게임 안의 적 씨앗 순번은 여전히 앱 전체 (레벨을 넘어) 이어진다 — 원작 난수와 무관한 엔진 근사라 그대로 둠.

## 8. 백그라운드 적재 · 이웃 슬롯 캐시 (§7.1 해결)

### 8.1 원작
- LoadWad / LoadForWarp 는 백그라운드 읽기다: 스크립트의 LoadCheck 는 "적재 끝남" 을 묻고 (엔진 `loadCheck` = 슬롯에 올라 있음), 슬롯은 2 개.
- 다른 슬롯이 다른 레벨로 바뀌면 0x1BC110 이 그 슬롯을 비우고 (`+0x68 & 4` → 0x1BBA20(1)) 새로 읽는다. 같은 레벨로 LoadForWarp 하면 다시 읽지 않는다 (= 1).
  Warp 는 떠난 레벨을 메모리에 둔 채 컨텍스트만 끈다 (0x1BC3A0, 43 §13.9) [확실].
- 엔진의 Warp 정책 (다른 슬롯 없이 시작, 떠난 레벨 화면 상태는 `GameSession.warpLeft`) 은 그대로. 여기서 바꾼 것은 **언제·어느 스레드에서 읽는가** 와 **그리기용 GL 장면을 다시 쓰는가** 뿐이다.

### 8.2 측정 (수정 전, `# load` 계측)
- DEST01 (24 초 반복 스크립트, 90 초): 이웃 교체 8 회 (DEST00 ↔ DEST02 번갈아). 교체마다 `other` (게임용 LevelData + 충돌) 2–5 ms + `otherScene` (그리기용 `LevelScene` 생성·enter)
  38–61 ms → 프레임 작업 45–77 ms. `otherScene` 안: WAD 해석 5–8, 레벨 데이터 2, 배치 굽기 (시작 자세·PlacedMesh·SHG·거울) ≈ 20, 정점 배열·텍스처 해독·GL 올리기 19–27, LevelFx 2–3 ms.
  **같은 WAD 를 매번 처음부터 읽고 굽고 올렸다.**
- 레벨 체인 10 레벨 (`ATHN01A,ATHN02A,ATHN02D,PAND03G,PAND04AA,DEST01,OLYMP02,ATHN08B,HDES01A,ATHN01A`, 각 8 초): `GameScene.enter` 64–258 ms, 그중 레벨 장면 45–60 %,
  R_HERO 12–70, HUD (R_PERMK 를 매번 다시 해석) 10–35, 적 종류 WAD 10–17, MagicFx (R_PERMK 이펙트 GO) 10–30 ms. 이음 도착이면 이웃 그리기 장면 53–106 ms 가 다음 프레임에 더해졌다.

### 8.3 수정
| 수정 | 내용 |
|---|---|
| `asset/Loader` | 적재 스레드 2 개 (데몬, 낮은 우선순위). `submit` → Future, `join` (안 끝났으면 기다림, 예외 그대로). `-Dgow.syncLoad` 면 제출 자리에서 실행 |
| `LevelScene.prepare()` | `enter` 를 CPU 단계 / GL 단계로 나눔. CPU: WAD 해석·`LevelData`·`LevelCollision`·EnvParams·배치 굽기·SHG·거울 평면·뷰 마스크·정점 배열·텍스처 해독·충돌/볼륨 와이어 정점. GL (`enter`): 메시·텍스처 올리기·정적 그림자·렌즈 플레어·LevelFx. `prepare` 없이 `enter` 하면 예전처럼 한 번에 |
| `WadAssets.prepare/upload` | `build(name, tm) = upload(prepare(name, tm, false))`. 레벨 준비만 `decodeTextures` = 쓸 텍스처 (upload 의 `texture()` 와 같은 조건·이름) 를 미리 해독 → 첫 `texture()` 가 꺼내 씀 |
| `WadAssets.prefetch(path)` | 해석 (WadFile·TextureLibrary·MDL·머티리얼 ANM) 을 미리, 다음 `new WadAssets(같은 경로)` 가 한 번 꺼내 씀 (나눠 쓰지 않음). 쓰이지 않은 것은 `GameScene.enter` 끝에 버림. 적재 스레드끼리는 기다리지 않음 (교착 방지) |
| `GameScene` 생성 = 페이드 시작 | `SceneManager` 페이드 아웃 0.4 초 동안 레벨 장면 `prepare` + 그 RSRCS 적 WAD · R_PERMK × 2 (HUD·이펙트) · R_HERO · R_WPN 해석. 이음 도착이면 떠나온 레벨 (다른 슬롯) 그리기 장면도. `enter` 는 결과를 받아 GL 만 |
| 이웃 그리기 장면 | `loadOther` 가 CPU 단계를 적재 스레드로 보내고, 끝난 프레임에 메인이 GL 로 올린다 (그 사이 이웃은 안 그려짐 — 원작도 슬롯을 비우고 읽는 동안 없다) |
| 이웃 장면 캐시 | 떠난 이웃 장면을 닫지 않고 최근 2 개 (`gow.otherCache`) 를 GL 에 둔다. 다시 그 레벨이 이웃이 되면 자세만 지우고 (`clearPoses`, 숨김·자세는 매 프레임 다시 넣음) 그대로. 넘치면 오래된 것부터 닫고, 레벨을 떠날 때 모두 닫는다 |

- 게임 진행은 바뀌지 않는다: 게임용 이웃 데이터 (`other`: LevelData·충돌·이웃 스크립트) 는 예전처럼 그 틱에 동기로 읽고, 레벨 장면은 `enter` 에서 끝난 결과를 받는다 (값은 스레드와 무관 —
  `BackgroundLoadTest`: 적재 스레드의 DEST00 준비 결과 해시 = 호출 스레드, 미리 해석한 R_PERMK = 바로 해석). 시간에 따라 달라지는 것은 이웃이 화면에 나타나는 프레임뿐.
- [엔진 근사] 이웃 장면 캐시는 원작과 다르다 (원작은 바뀐 슬롯을 다시 읽음). 화면 차이: 다시 쓴 장면의 반복 재생 배치 (깃발 등) 위상이 처음 상태가 아니라 이어진다.
- 동기 모드 (`gow.syncLoad`, `--screenshot` 자동) 는 캐시 0 · 제출 자리 실행이라 예전과 같은 프레임에 같은 장면이 나타난다. 전 레벨 순회 보고서 `work/verify/report.md` 는 수정 전후 같다 (GameScene 을 쓰지 않음).

### 8.4 결과 (같은 시나리오, 수정 뒤)
DEST01 이웃 교체:

| | 수정 전 | 수정 뒤 |
|---|---|---|
| 처음 교체 (DEST00 · DEST02 각 1 회) | other 4–5 + otherScene 54–61 → 프레임 63–77 ms | other 4–6 (메인) + 준비 40–42 (적재 스레드) + GL 12–13 (메인) → 프레임 ≤ 27 ms |
| 다시 교체 (6 회) | other 2–5 + otherScene 38–52 → 프레임 45–65 ms | other 2–4 ms 뿐 (캐시) → 25 ms 넘는 프레임 없음 |

레벨 체인 10 레벨 (전환 프레임 = 씬 교체 + 첫 프레임 작업):

| 레벨 | enter 전 → 뒤 (ms) | 최대 프레임 전 → 뒤 (ms) |
|---|---|---|
| ATHN02A (JIT 덜 데워짐) | 258 → 107 | 347 → 156 |
| ATHN02D | 100 → 78 | 226 → 101 |
| PAND03G | 164 → 91 | 186 → 113 |
| PAND04AA | 126 → 63 | 144 → 81 |
| DEST01 | 239 → 90 | 258 → 109 |
| OLYMP02 | 82 → 41 | 114 → 72 |
| ATHN08B | 121 → 71 | 144 → 94 |
| HDES01A | 140 → 46 | 169 → 76 |
| ATHN01A (두 번) | 76 / 64 → 39 / 38 | 158 / 152 → 66 / 65 |

- 이음 도착 이웃 장면: 메인 53–106 ms → 적재 스레드 41–126 ms + 메인 GL 15–22 ms (도착 다음 몇 프레임).
- 누수: 4 레벨 × 2 바퀴 + 매번 전체 GC (`ATHN02A,PAND03G,DEST01,OLYMP02` × 2, 각 20 초) — 바퀴마다 같은 레벨의 살아 있는 힙 (PAND03G 258/258, DEST01 265/265, OLYMP02 183/183 MB) 과
  GL tex/buf/vao (PAND03G 106/621/315, DEST01 127/2380/1196, OLYMP02 74/1349/680) 가 같다. 떠나기 직전 측정은 다음 레벨 준비분 (≈ 40 MB, 페이드 동안) 이 더해져 보인다.
- 시험 399 (+2 `BackgroundLoadTest`) 통과.

### 8.5 남은 메인 스레드 적재 (전환 프레임 40–110 ms) → §9
레벨 장면 GL 올리기 7–31 · HeroRig (스키닝 모델 GL·튜닝·전투 데이터) 12–16 · HUD FLP 4–30 · `Tweaks.load` (R_PERMK·레벨, 매번 해석) 5–10 · MagicFx (EffectSystem GL) 11–15 ·
스크립트 시작·도착 배치 0–30 ms. 그리고 이웃 교체의 게임용 데이터 (`other`) 2–6 ms 는 그 틱에 필요해 동기로 둔다. 후보: GL 올리기를 여러 프레임에 나누기 (페이드 인 동안),
R_PERMK 튜닝·HUD 를 세션에 상주 (레벨마다 같은 내용).

## 9. 전환 프레임 메인 스레드 몫 · PAND03G 파티클 · 자세 샘플 할당 (§7.2·§7.3·§8.5)

원작 동작은 그대로다: 게임 로직·값·순서는 바꾸지 않고, 바뀌는 것은 **어느 프레임·스레드에서 만드는가**, GL 객체를 다시 쓰는가, 같은 값을 덜 계산·덜 할당하는가뿐.
전 레벨 순회 보고서 `work/verify/report.md` 는 수정 전후 글자 하나 다르지 않고, 파티클 출력은 정점 해시로 (§9.4), 자세·VU 식은 참조 구현과 비트 단위로 (`HotPathEquivalenceTest`) 같다.

### 9.1 계측 추가 (원작에 없음, 기본 꺼짐)
| 옵션·줄 | 뜻 |
|---|---|
| `# load … . 단계 ms` | `GameScene.enter` 단계별 (`. level GL` · `. hero` · `. hud` · `. tweaks` · `. enemy kinds` · `. boss` · `. magicFx` · `. wireEffects` · `. spawn` · `. script start` …) |
| `# load … exit 레벨` / `first update·render 레벨` | 떠나는 씬 exit, 들어온 씬의 첫 두 틱·그리기 |
| `# load … pre 단계` | 페이드 동안 미리 한 일 (§9.2) |
| 게이지 `ptcMs=` | 프레임당 파티클 그리기 ms (모든 `ParticleSystem.render`, CPU + GL 호출) |
| `-Dgow.lockstep` | 프레임마다 로직 틱 하나·보간 0, LevelFx 그리기 간격 = 1/60, GameScene 난수 씨앗 1 → 벽시계와 무관하게 같은 화면 순서 (출력 비교용) |
| `-Dgow.ptcHash=파일` | 프레임마다 "프레임 그리기수 정점수 해시" — 파티클 VBO 로 올리는 float 전부 + 그리기 정점 수의 누적 해시 |
| `-Dgow.shaderPool=false` · `-Dgow.preload.ms=4` | 셰이더 풀 끄기 (비교용) · 페이드 한 틱에 미리 할 시간 |

JFR 은 1 ms 샘플 (`profile.jfc` 에서 `jdk.ExecutionSample`·`NativeMethodSample` period = 1 ms) 로 전환 프레임 (enter·첫 그리기) 스택을 모았다.

### 9.2 전환 프레임 (§8.5) — 수정 전 분석과 수정
수정 전 10 레벨 체인 (§8.2 시나리오, `-Dgow.perf.hitch=10`) 의 enter 단계 (ms): 레벨 GL 4–27 · 영웅 10–14 · HUD 3–29 (레벨 HUD 가 레벨 WAD 를 메인에서 다시 해석) ·
튜닝 4–5 · 적 종류 0–6 · 보스 0–15 · MagicFx 8–22 · wireEffects 1–13 · 숙주 찾기 (ATHN02D) 32 · 스크립트 시작 0–71 (레벨 템플릿 적 = 레벨 WAD 다시 해석·모델) · exit 2–8.
JFR: enter 샘플의 28 % 가 `glCompileShader`/`glLinkProgram` (레벨마다 같은 셰이더 8–10 개: 영웅·적·이펙트 스키닝, 파티클 × 3, 렌즈 플레어, 그림자 …), 16 % 가 텍스처 (밉맵·TexImage), exit 은 VAO·버퍼 지우기.

| 수정 | 내용 |
|---|---|
| `Scene.preload` · `SceneManager` | 페이드 아웃 (0.4 초, 이전 씬은 그리기만 하고 갱신 안 됨) 동안 틱마다 다음 씬의 `preload`. 동기 모드 (`gow.syncLoad`, 스크린샷) 는 하지 않는다 |
| `GameScene.preload` | 틱마다 4 ms 까지 단계별로: 영웅 (`HeroRig` — R_HERO 모델·스켈레톤·애니·전투 데이터·블레이드) → HUD (`UiLayer` FLP_HUDK) → `MagicFx` → god 블레이드 → 레벨 GL (`LevelScene.preUpload`: 렌즈 플레어 → 배치 하나씩 → 와이어 → LevelFx, 예전 enter 와 같은 순서) → 레벨 HUD → 오브 튜닝·Breakables·레벨 영웅 애니 연결 → RSRCS 적 종류 (`EnemyFactory.load`). enter 는 만든 것을 그대로 쓴다. 모두 이 씬만의 새 객체이고 정적 상태·이전 씬을 건드리지 않는다 (소리 뱅크 올리기·튜닝 합치기·적 영웅 애니 연결은 이전 씬 exit 뒤여야 해서 enter 에 둠) |
| `Shader` 프로그램 풀 | 닫은 프로그램을 (정점, 조각) 쌍별로 두었다가 (쌍마다 ≤ 8) 다음 `new Shader` 가 다시 쓴다. 링크 직후 모든 활성 유니폼 값을 저장해 두고 다시 쓸 때 `glProgramUniform*` 로 되돌린다 → 새로 링크한 프로그램과 상태가 같다 (유니폼 블록·속성 바인딩 없음). 이중 close 는 무시 |
| `GlDeferred` · `LevelScene.exitDeferred` | 떠난 레벨·이웃 장면 (캐시에서 밀려난 것 포함) 의 GL 해제를 다음 틱들에 1.5 ms 씩 (같은 순서, 배치 32 개씩). GL 해제뿐이라 게임에 보이는 차이 없음 |
| 레벨 WAD 다시 열기 미리 해석 | 레벨 준비 (적재 스레드) 가 레벨 HUD (FLP_)·보스 (BossDirector)·레벨 AI1 템플릿 오브젝트 수만큼 (합쳐 ≤ 3) `WadAssets.prefetch(레벨)` — 해석 사본은 enter 끝에 버림 |
| 이펙트 GO 표 미리 | `EffectLibrary.preparse(WadFile)`: `add` 의 해석을 적재 스레드에서 해 그 WadFile 에 붙여 둔다 (`WadFile.putDerived`, 전역 표 없음 — WAD 와 함께 버려짐). 레벨 WAD·RSRCS 적 WAD·MagicFx 의 R_PERMK·레벨 다시 열기 사본 |
| 숙주 레벨 · 이웃 데이터 | `HostLevel.candidates` (WAD 읽기·스크립트 검사·충돌) 를 레벨 준비에서, `find` 는 점 질의만. enter 안의 `loadOther` (이음 도착 = 떠나온 레벨, 숙주) 가 쓸 LevelData·충돌도 적재 스레드에서 (값·예외 순서 같음, enter 끝에 비움) |

### 9.3 결과 (10 레벨 체인, 같은 시나리오·같은 기계)
전환 프레임 = 씬 교체 + 첫 틱·그리기 (`# hitch` work, 10 ms 미만이면 기록 안 됨). enter 단계는 수정 뒤 남은 큰 것만.

| 레벨 | 전환 프레임 전 → 뒤 (ms) | swap (exit+enter) 전 → 뒤 | 뒤에 남은 enter 단계 |
|---|---|---|---|
| ATHN02A (JIT 덜 데워짐) | 211 → 46 | 180 → 19 | 스크립트 시작 7.6 (레벨 템플릿 적 스폰), 첫 그리기 19 |
| ATHN02D | 103 → 21 | 90 → 13 | 보스 3.4, 숙주 (이웃 데이터 넣기) 4.0 |
| PAND03G | 114 → 19 | 100 → 10 | 보스 3.7 |
| PAND04AA | 83 → 16 | 68 → 5 | — |
| DEST01 | 108 → 26 | 93 → 14 | 스크립트 시작 6.8 |
| OLYMP02 | 74 → 22 | 50 → 3 | 첫 그리기 17 |
| ATHN08B | 101 → 40 | 82 → 27 | 보스 8.4, wireEffects 9.3 (이 회차만, 다른 회차 0.2) |
| HDES01A | 85 → 26 | 62 → 10 | 스크립트 시작 7.1 |
| ATHN01A (두 번) | 74 / 73 → 21 / 20 | 47 / 45 → 4 / 4 | — |

- 페이드 아웃 프레임 (미리 하기) 최대 10–18 ms (영웅 한 단계 7–9 ms, LevelFx 5–11 ms), 첫 전환만 34 ms (JIT). 수정 전 페이드 인 프레임에 있던 25–32 ms 프레임은 없어졌다 (최대 23 → 대부분 10 미만).
- 남은 몫의 가장 큰 것은 **첫 그리기** (적재 직후 첫 render 5–20 ms, 안정 상태 5–8): 드라이버의 첫 그리기 (지연 업로드·셰이더 변형, `glDrawElements` 가 JFR 1 위), HUD `FlpRenderer` 의 텍스처 첫 생성, 파티클 첫 버퍼.
- 누수 (§2 방식: `ATHN02A,PAND03G,DEST01,OLYMP02` × 2 바퀴, 20 초, 매번 전체 GC): 같은 레벨의 살아 있는 힙 1 → 2 바퀴 PAND03G 267/267, DEST01 267/267, OLYMP02 185/185, ATHN02A 269/269 MB, GL tex/buf/vao 바퀴마다 같다 (예: DEST01 127/2380/1196).
  수정 전 같은 실행은 258·265·183·267 MB — 차이 +2 (PAND03G +9) 는 파티클 정점 배열 (필요한 만큼 자람, §9.4). 셰이더 프로그램 수 9–11 → 16–17 (풀에서 쉬는 것, 늘지 않음).
- 시험 409 (+2 `HotPathEquivalenceTest`) 통과, 순회 보고서 동일.

### 9.4 PAND03G 파티클 (§7.2)
JFR (1 ms) 로 보니 PAND03G 메인 스레드 샘플의 78 % 가 `ParticleSystem.flush` 의 `glBufferSubData(0)` 안 (네이티브 대기) 이었다: 한 프레임에 정의마다 (≈ 50 번) 같은 VBO 의 앞부분을
다시 쓰므로 바로 앞 그리기가 끝날 때까지 GPU 를 기다린다. VU 식 해석 (`PtcVu.eval`) 은 CPU 쪽 몫 (≈ 10 %).

| 수정 (`fx/ParticleSystem`, `tools/fx/PtcVu`) | 내용 (출력 같음) |
|---|---|
| VBO orphan | `glBufferSubData` → `glBufferData(같은 바이트, GL_STREAM_DRAW)` — 드라이버가 새 저장소를 주므로 기다리지 않는다. 올리는 float·그리기 호출·나눠 그리는 경계 (GL 버퍼 용량) 는 같다 |
| 정점 배열 | 정점마다 `FloatBuffer.put` 9 번 → 자바 배열에 쓰고 올릴 때 한 번에 복사 (배열은 필요한 만큼 자람) |
| 정의별 색인 | 정의마다 전체 파티클을 훑던 것을 프레임에 한 번 계수 정렬 (정의 객체 동일성별, 색인 순 그대로). 정의 목록의 `contains` (레코드 깊은 equals — `Shape.equals` 가 상위였다) 는 정의 객체마다 한 번. 같은 내용의 다른 정의 객체는 예전처럼 처음 것만 목록에 (그 파티클은 그리지 않는 동작 그대로) |
| `PtcVu.eval` | 상수 슬롯 전부 복사 → T0..T3 이 쓰는 슬롯만 되돌림 (연산은 슬롯을 쓰지 않으므로 같다; 작업 공간의 첫 eval 은 전부), 연산 분기를 성분 루프 밖으로 (같은 식·순서), 항력 `exp` 는 연산 8 이 있을 때만 (쓰이지 않는 값) |

검증: `-Dgow.lockstep -Dgow.syncLoad -Dgow.ptcHash` 로 수정 전·후 실행의 프레임별 해시가 PAND03G 1800 프레임 (프레임당 ≈ 57 그리기·5.6 만 정점), ATHN01A 1800, DEST01·HDES01A 각 1200 프레임 모두 같다
(수정 전끼리 두 번도 같음 = 결정적). `HotPathEquivalenceTest`: 6 WAD 의 VU 프로그램 347 개 × 40 회 (작업 공간을 이어 쓰며) 를 예전 eval 과 비트 비교.

| PAND03G 180 초 (§1.2 스크립트) | 수정 전 | 수정 뒤 |
|---|---|---|
| 파티클 그리기 `ptcMs` | 9.8–12.3 (평균 11.5) | 0.70–0.81 |
| 프레임 작업 평균 / p99 / 최대 (ms) | 12.7 / 15.4 / 31.5 | 1.9 / 4.6 / 23.8 |
| 프레임 간격 평균 | 13.0 ms | 2.2 ms |
| 살아 있는 힙 30 → 180 초 | 145 → 148 MB | 146 → 150 MB |

(이 기계는 이 실행에서 스왑이 vsync 를 기다리지 않아 프레임 수가 6 배 — GC 횟수 6 → 14 는 프레임당으로는 줄었다.) ATHN01A 60 초도 프레임 작업 7.6 / 10.1 → 3.1 / 8.1 ms (같은 VBO 동기 + §9.5 의 자세 GO).

### 9.5 자세 샘플 할당 (§7.3)
| 수정 | 내용 (값 같음) |
|---|---|
| `Skeleton.localMatrixInto` · `worldInto` · `Mat4.mulInto/fromEulerInto/fromQuatInto/mul3Into` | 관절마다 로컬 행렬 (오일러 = `float[16]` 6 개, 쿼터니언 1–2 개) + 월드 곱 1 개 → 스레드별 작업 공간 (적재 스레드도 자세를 샘플). 행렬 곱은 같은 루프·순서 |
| `Pose.world` | 로컬은 작업 공간에, 돌려주는 월드 행렬 (메모, 읽기 전용) 만 새로 — 관절당 8 개 → 1 개 |
| `AnimatedModel.draw` | 스키닝 행렬 (inverse bind · world) 을 모델별 작업 공간에 (그리기 안에서만 쓰임) |
| `LevelScene.drawPosed` | 모델이 없는 이름도 기억 — `computeIfAbsent` 가 null 을 넣지 않아 그런 GO 는 프레임마다 레벨 전체 배치의 관절 월드 (`placements()` → `GoInstance.matrix`·`bindWorld`) 를 다시 만들었다. ATHN01A 할당 1 위 `Mat4.fromEuler/mul` 의 실제 출처 |

ATHN01A 60 초 JFR 할당 상위: 수정 전 `Mat4.mul` 29 %, `Mat4.identity` 19 %, `Pose.<init>` 6 %, `Skeleton.worldOf` 4 % → 수정 뒤 `Mat4` 없음, `LevelCollision.intersect` 12 %, `Pose.<init>` 9 %, `Pose.world` (돌려주는 월드 행렬) 7 %.
`HotPathEquivalenceTest`: 영웅 클립 60 개 × 4 시각의 `Pose.world` 와 아이들 로컬이 예전 코드와 비트 같음.

### 9.6 남은 것
1. ~~적재 직후 첫 그리기 5–20 ms (드라이버 첫 그리기·HUD 텍스처 첫 생성)~~ → §9.7 (페이드 동안 1×1 FBO 에 배치·HUD 를 한 번씩: 첫 그리기 6–21 → 4–12 ms (두 번째 방문부터)).
2. 스크립트 시작의 적 스폰 (레벨 템플릿 종류 = `EnemyFactory.load` 튜닝 + 모델 GL) 0–8 ms, 보스 3–9 ms, 첫 전환 (JIT 덜 데워짐) 46 ms. 페이드 중 영웅 한 단계 7–9 ms (CPU 부분은 적재 스레드로 옮길 수 있음).
3. ~~`BladeChainRenderer.flush` 도 같은 `glBufferSubData` 동기~~ → 해결 (95d7638): `flush` 가 파티클과 같이 `glBufferData(…, GL_STREAM_DRAW)` 로 저장소를 새로 받는다 (orphan).
4. `Pose.<init>` (샘플마다 관절 × 3 배열) 와 `Pose.world` 의 돌려주는 행렬은 남음 — Animator 단위로 Pose 를 다시 쓰려면 이전 Pose·월드 배열을 붙잡는 호출자 (보스 동기 등) 확인이 먼저.
5. 셰이더 풀은 앱이 끝날 때까지 쉬는 프로그램을 둔다 (쌍마다 ≤ 8, 지금 7 개 남짓).
6. 페이드 동안 새 레벨 GL 을 먼저 올리고 떠난 레벨 GL 은 교체 뒤에 지우므로 전환 몇 프레임 동안 두 레벨의 GL 자원이 함께 있다 (VRAM 최대치 증가).

### 9.7 첫 그리기 미리 하기 (§9.6 1번)
원작 동작·화면은 그대로다 (엔진 전용, 원작은 PS2 GS 에 지연 업로드가 없다). 바뀌는 것은 드라이버가 텍스처·정점 버퍼를 GPU 로 올리는 **프레임** 뿐.

| 수정 | 내용 |
|---|---|
| `render/GpuWarmup` (`Renderer.warmup()`) | 1×1 화면 밖 FBO (RGBA8 + 깊이 24/스텐실 8). `begin` 이 이전 FBO·뷰포트를 저장하고 바꾸며 지우고, `end` 가 되돌린다. 앱 끝까지 하나 |
| `GameScene.preload` 단계 8 `warm level` | 적 종류 다음: `LevelScene.warmGpu` 가 모든 배치 (하늘 포함) 를 `Renderer.draw` 로 한 번씩 (deadline 마다 끊고 다음 틱에 이어서). 새 GL 객체 없음, 단계 `ALL` |
| 단계 9 `warm hud` | `UiLayer.warm` → `FlpRenderer.warm`: FLP 의 모든 메시 참조 + 글꼴마다 첫 글리프를 그려 파트 메시·텍스처 (R_PERMK 해독·GL) 를 첫 `hud.render` 전에 (같은 캐시를 미리 채움). 레벨 HUD 도 |
| `-Dgow.warm=false` | 끔 (비교용). 동기 모드 (`--screenshot`) 는 미리 하기 자체가 없다 |

- 반복 act GO 의 스키닝 모델 미리 만들기는 뺐다: 보이지 않는 것까지 만들어 ATHN02D 페이드 한 틱이 280 ms 였다 (원래 첫 그리기 6 ms 는 그 GO 를 그리지 않음).
- 결과 (§8.2 10 레벨 체인 + ATHN01A·ATHN02A 되풀이, 각 8 초, `-Dgow.perf.hitch=10`, 같은 빌드에서 `-Dgow.warm=false` 와 두 번씩): 첫 render (ms, 두 실행)

| 레벨 | 끔 | 켬 | 전환 프레임 work 끔 → 켬 |
|---|---|---|---|
| ATHN02D | 5.8 / 6.0 | 4.1 / 4.3 | 22–26 → 20 |
| PAND03G | 8.9 / 8.6 | 5.9 / 5.9 | 20–26 → 20 |
| PAND04AA | 11.4 / 12.0 | 7.3 / 7.8 | 17–18 → 15–16 |
| DEST01 | 10.5 / 10.5 | 7.2 / 7.1 | 26 → 24–25 |
| OLYMP02 | 17.3 / 17.8 | 11.8 / 11.8 | 22 → 18 |
| ATHN08B | 11.7 / 11.7 | 8.4 / 8.4 | 31–32 → 29–30 |
| HDES01A | 14.9 / 14.5 | 8.7 / 8.8 | 28–35 → 23 |
| ATHN01A (두 번) | 11.5·10.3 / 11.4·10.6 | 6.7·5.6 / 6.4·5.8 | 20–25 → 16–18 |
| ATHN02A (두 번째 방문) | 21.5 / 15.0 | 8.3 / 8.6 | 29–36 → 25 |

  첫 방문 ATHN02A (JIT 덜 데워짐, 레벨 준비가 늦어 페이드 안에 다 못 함) 는 14.9–25.0 그대로. 페이드 동안 warm 합 14–27 ms, 한 틱 최대 4–11 ms (다른 미리 하기 단계와 같은 4 ms 예산).
- 남은 첫 그리기 4–12 ms: 파티클 첫 버퍼 (PAND03G 두 번째 render 12), OLYMP02 거울 (반사 장면 첫 그리기), 영웅·적 스키닝 모델·자세 GO (그리지 않음), 셰이더 상태 조합
  (화면 FBO 와 1×1 FBO 의 형식이 같아도 드라이버 파이프라인 캐시는 [미확인]).
- 시험 424 통과, 순회 보고서 `work/verify/report.md` 동일 (cmp).
