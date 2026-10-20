# 48. 장시간 실행 안정성 · 성능 · 테스트 위생

코드: `core/PerfMonitor` (측정), `render/GlStats` (GL 객체 수), `GameScene` 체인 (`-Dgow.chain`), `ScriptedInput` 반복·스틱.
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

1. **동기 적재 히치**: LoadWad·이음은 메인 스레드에서 WAD 읽기·해석·GL 올리기를 한 번에 한다 (원작은 백그라운드). DEST01 처럼 두 이웃 (DEST00/02) 경계를
   오가면 50–80 ms 히치가 되풀이되고 (같은 WAD 를 매번 다시 읽음), 레벨 전환은 130–350 ms. 해석 결과 1 칸 캐시나 백그라운드 해석이 후보 (LevelCollision·LevelScene
   상태 공유 검토 필요).
2. PAND03G 프레임 작업 12.8 ms (p99 15.7) — 60 Hz 예산 16.7 ms 에 가깝다. 대부분 파티클 VU 에뮬레이션 (`PtcVu.eval`, 파티클마다 VU 프로그램 해석).
   VU 프로그램별 미리 컴파일 (식 트리 → 람다) 이 후보.
3. 애니메이션 샘플 자체 (`Skeleton.localMatrix`·`Mat4.mul` 이 관절마다 `float[16]`) 는 Animator 당 틱 1 회로 줄었지만 남아 있다 — 할당 1 위.
4. 힙 커밋은 G1 이 돌려주지 않는다 (체인 뒤 600–900 MB). RSS 1.0–1.4 GB.
5. R_PERMK 공용 소리 뱅크의 클립 캐시는 재생된 소리만큼 자란다 (상한 = 뱅크 크기, 체인 26 회 뒤 ≈ 160).
6. ATHN02D 헤드리스 메두사의 "영웅 석화" 단계는 씨앗에 따라 나오거나 안 나온다 — 이제 결정적이지만 지금 씨앗에서는 안 나온다
   (석화 자체는 `MedusaQueenFightTest.gazePetrifiesHeroAndWiggleBreaksOut` 가 따로 검사).
7. 게임 안의 적 씨앗 순번은 여전히 앱 전체 (레벨을 넘어) 이어진다 — 원작 난수와 무관한 엔진 근사라 그대로 둠.
