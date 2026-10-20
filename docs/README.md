# devple-gow 문서 인덱스

PS2 「갓 오브 워」 한글판 디스크(SCKA_300.02)를 분석하고 macOS 네이티브(Java)로 재구현하는 개인 학습 프로젝트.
배포·공개하지 않음. 원본 데이터(`*.iso`, `gow-rom/`, `work/`)는 `.gitignore`로 커밋에서 제외한다.

| # | 문서 | 상태 |
|---|------|------|
| 00 | [프로젝트 개요 / 접근 방식 / 기술 선택](00-project-overview.md) | 완료 |
| 01 | [디스크 구조 (ISO9660 DVD9, TOC, PAK)](01-disc-structure.md) | 완료 |
| 02 | [WAD 컨테이너 포맷](02-wad-format.md) | 완료 (태그 레벨) |
| 03 | [텍스처 / 팔레트 / 머티리얼 (GFX, PAL, TXR, MAT)](03-texture-format.md) | 완료 |
| 04 | [모델 / 메시 (MDL, 메시, VIF)](04-model-format.md) | 완료 |
| 05 | [스켈레톤 / 애니메이션 (CXT 스켈레톤, ANM)](05-animation-format.md) | 완료 (회전·이동·무기 궤적(타입 10) 채널, 스키닝; 기타 부속 채널·이벤트 미상) |
| 06 | [실행 파일(SCKA_300.02) 분석 – 서브시스템 지도 / TWK 해시](06-executable-analysis.md) | 1차 완료 (심화 목록 §10) |
| 07 | [UI(FLP) / 스크립트(SCR, ESC) / 텍스트·폰트](07-ui-script-text.md) | 완료 (구조·텍스트·바이트코드, 일부 필드 미상) |
| 08 | [엔진 아키텍처 (Java + LWJGL)](08-engine-architecture.md) | 골격 + 모델 렌더 |
| 09 | [오디오 포맷 (VAG / VPK)](09-audio-format.md) | 완료 |
| 10 | [전투 시스템 (CombatFile 콤보 그래프 / 피해 / 신들의 분노 / 마법 / 적)](10-combat-system.md) | 1차 완료 (포맷 확정, 재구현 명세 §15) |
| 11 | [성장(업그레이드 비용) / 픽업(오브·아이템) / 저장(세이브 구조) / 씬 흐름(상태 머신·레벨 그래프)](11-progression-save-flow.md) | 1차 완료 (미해결 §9) |
| 12 | [레벨 구성: 배치(go/오브젝트) / 충돌(Sheet, BallHull) / 카메라(CAM, CMV, NCV 레일)](12-level-format.md) | 1차 완료 (표면 속성 비트 미상) |
| 13 | [레벨 스크립트 엔티티 런타임: 타입별 슬롯 / 메시지·발동 순서 / Entity·Internal 함수 인자](13-script-entities.md) | 1차 완료 (이벤트 번호·Vis 속성 일부 미상) |
| 14 | [사운드 뱅크 (SND / 989snd SBlk 그레인·톤 / DPMS 플러그인 / VAG 팩 / SEM 이미터 / 음악 선택)](14-sound-bank-format.md) | 1차 완료 (전 뱅크 디코딩, ADSR·LFO·그레인 10–12 미상) |
| 16 | [환경 / 이펙트 포맷 (SCR_Fog·LayeredFog·FarClip·Sky·Weather·Water·Mirror, 머티리얼 ANM, FXC_/PTC_ 파티클)](16-environment-fx-format.md) | 1차 완료 (안개 램프 기준·VU 상수 미상) |
| 18 | [동영상 포맷 (PSS/PSW: MPEG-2 PS, SShd/SSbd PCM 오디오, 이름 매핑)](18-video-format.md) | 완료 (전 120개 조사) |
| 17 | [적 AI (AI1 필드 / 결정 트리 DTre / 공격 토큰 / 포위 이동 / BlockMod 방어 / 난이도)](17-enemy-ai.md) | 1차 완료 (칸 선택·인지 세부 미상 §8) |
| 20 | [구현: 성장 시스템](20-impl-progression.md) | 완료 (테스트 6) |
| 22 | [구현: 씬 흐름 / 게임 상태 머신](22-impl-game-flow.md) | 로직 완료 (테스트 2) |
| 24 | [구현: 이벤트 / 레벨 스크립트 VM](24-impl-script-events.md) | VM 완료 (전 레벨 실행 검증) |
| 25 | [구현: 애니메이션 전환 / 크레토스 이동](25-impl-hero-locomotion.md) | 평지 이동 완료 |
| 26 | [구현: 전투 시스템 1·2단계](26-impl-combat.md) | 콤보·피해 검증 (테스트 3) |
| 21 | [구현: FLP UI 런타임 (메인 메뉴 / HUD / 일시정지 / 파워업)](21-impl-ui-runtime.md) | 1차 완료 (테스트 2, 스크린샷 4) |
| 23 | [구현: 레벨 씬 / 충돌 질의](23-impl-level.md) | 렌더·충돌 질의 완료 (테스트 4) |
| 27 | [구현: 통합 게임 씬](27-impl-game-scene.md) | 레벨+크레토스+HUD 동작 |
| 28 | [구현: ESC 엔티티 런타임 (ScriptWorld)](28-impl-script-world.md) | 센서·발동·이벤트·스폰 완료 (테스트 8) |
| 29 | [구현: 오디오 런타임 (SoundManager: 그레인 실행기, 3D, 음악 스트림)](29-impl-audio.md) | 완료·게임 연결 (테스트 9) |
| 30 | [구현: 레벨 카메라 시스템 (CameraRig)](30-impl-camera.md) | 선택·레일·전환·스크립트/관절 카메라·AresCamera1(0x1F67C0)·레벨 시작/도착(레일 비용 0x1F1648·0x400, 다른 슬롯 CMV) 완료 |
| 31 | [구현: 환경 / 이펙트 (안개, 하늘, 비, 물, 파티클, 머티리얼 애니메이션)](31-impl-environment-fx.md) | 1차 완료 (테스트 5, 스크린샷 4레벨) |
| 33 | [구현: 컷신 재생 (MoviePlayer / MovieScene, JCodec MPEG-2)](33-impl-video.md) | 재생기 완료 (테스트 3, 스크린샷 3) |
| 34 | [구현: 무기 궤적 / 원작 히트박스 (블레이드 부착, CDV 구, PartType, 스윕 판정)](34-impl-hitboxes.md) | 완료 (테스트 3, 디버그 시트) |
| 32 | [구현: 원작 적 AI (AiBrain, 결정 트리, 공격 토큰)](32-impl-enemy-ai.md) | 완료 (테스트 8, 적 32종 스모크) |
| 35 | [구현: 마법 3종(메두사 석화·제우스 투사체·하데스 영혼) / 방향키 선택 / TimedPress·스틱 미니게임](35-impl-magic-minigames.md) | 완료 (테스트 12, 스크린샷 2) |
| 36 | [검증: 전 레벨 순회 (TraversalVerifier, 레벨 슬롯·Warp·LoadCheck, 볼륨 영역)](36-verify-traversal.md) | 완료 (ATHN01A → OLYMP02 도달, 버그 10 수정, 테스트 18) |
| 37 | [구현: 보스전 (히드라·메두사 여왕·아레스: 레벨 WAD 보스 리소스, 전투↔스크립트 이벤트 1035/1047/1039/1019/1054, 동기 처형)](37-impl-bosses.md) | 히드라·메두사 완료, 아레스 결말까지 (AresCamera1 원작 비용), ATHN08B 가족 보호·분신 잡기, 판도라 수호자 BMI 전용 클래스 AI 결말까지, 동기 정렬 접촉 검사 (§11) |
| 38 | [구현: 이동 기술 (표면 속성, 벽 오르기·매달리기·사다리·줄·짚라인·수영·균형, R1 오브젝트·밀기, 발판, 이웃 레벨 스크립트)](38-impl-traversal-mechanics.md) | 1차 (주 경로 강제 전환 8 → 6 → 0 (§10: 과제 진행률·MaximumCount·헤드리스 보스전), 테스트 22 + 12) |
| 39 | [구현: 귀중품(보너스) 메뉴 (FLP_ShellK Treasure 화면, 메시지 900–916 항목·잠금 문자·BonusScroll, 보너스 영상/신들의 시험)](39-impl-bonus-menu.md) | 완료 (테스트 3, 스크린샷 1) |
| 40 | [구현: 원작 이펙트 GO (이펙트 이름 → GO 인스턴스 → MDL/ANM/FXC/PTC, ANM 타입 10 채널 = FXC 곡선, 부착·수명, PlayFX/Concussion/MFX 템플릿)](40-impl-effects.md) | 마법 4종·적중 피·발소리 먼지 연결 (테스트 5, 스크린샷 7) |
| 41 | [구현: 부서지는 물체 (Breakable 비헤이비어, Brkb 템플릿, Marker 101 → 파괴 센서)](41-impl-breakables.md) | 1차 (테스트 1) |
| 42 | [구현: 버튼 안내 1024·질문 Internal 0xB·Animator 모드, 하네스 2 단계 (이웃 GlobalData·전역 이벤트)](42-impl-use-button-animator.md) | 1차 (테스트 3) |
| 43 | [검증: 전 레벨 시각 QA (효과 켬 105 레벨, SCR_Sky 하늘·배경 안개·반투명 패스 순서, 시작 act 자세·SCR_Mirror 반사 §6, 시작 카메라 §7)](43-visual-qa.md) | 완료 (정상 97, 의심 2, 테스트 3 + 4 + 4) |
| 44 | [구현: 노멀맵 (원작에 없는 선택 기능, 기본 꺼짐 — 덮어쓰기 PNG / 휘도 Sobel 생성, 화면 미분 코탄젠트 좌표계, 구운 색 세부 배율)](44-impl-normal-map.md) | 1차 (테스트 5) |
| 45 | [구현: 카메라 필터 (시선 고도각 세로 그라데이션 덮개, CAM 0xB0 수중 필터, 명중 플래시) · 블레이드 god 판 (goMAIgodblade)](45-impl-camera-filter-god-blades.md) | 1차 (테스트 6) |
| 46 | [구현: 렌즈 플레어 (SCR_LensFlare 단일 가산 스프라이트, 가림 비율)](46-impl-lens-flare.md) | 1차 (테스트 3) |
| 47 | [구현: 동적 라이트 (LGT, 0x1521B0 슬롯 2 + 주변광, VU 조명 커널, 블레이드 라이트) · 줄 스키닝 렌더](47-impl-lighting.md) | 1차 (테스트 10) |
| 48 | [검증: 장시간 실행 안정성·성능 (PerfMonitor·GL 객체 수·레벨 체인, 레벨 전환 누수, 프레임 할당 핫스팟, 테스트 순서 의존)](48-perf-stability.md) | 1차 (8 레벨 × 180 초, 체인 24 레벨) |
| 49 | [확장 구조 (gow.properties 설정 · 지역판 접미사 감지 · 모드 계층 rom/ 교체 · WAD 태그 교체 · 키·TTF 폰트·텍스트·스테이지·컷신·사운드 WAV·애니 replace/speed·텍스처 PNG·적 AI 수치·플러그인·디스크 도구)](49-extensibility.md) | 완료 (테스트 32) |
| 90 | [현황 / 로드맵](90-status-roadmap.md) | 수시 갱신 |

## 게임 실행

```bash
./gradlew :engine:run                                  # 메인 메뉴 → 새 게임 → ATHN01A (원작 흐름)
./gradlew :engine:run --args="--game ATHN01A"          # 레벨 바로 시작
./gradlew :engine:run --args="--game PAND01A --entry gomusicgrppan1c1start --auto-run 3"   # 진입점 지정 + 자동 달리기
./gradlew :engine:run --args="--hero --enemy R_MIN"    # 평지 전투 연습 (마법 4종 보유, 방향키 선택)
./gradlew :engine:run --args="--hero --enemy R_MIN --auto magic"   # 메두사 석화 → 파괴 자동 시연
./gradlew :engine:run --args="--game ATHN01E --boss-demo --skip-movies --no-fx"   # 히드라 보스전 자동 연출 (ATHN02D 메두사, ATHN08A/08C 아레스)
./gradlew :engine:run -PgowUnlockAll                  # 메인 메뉴, 귀중품(보너스) 전부 해금 (39 문서)
./gradlew :engine:run -PgowFxLog -PgowMagicStage=2 "-PgowScript=LEFT:0.3:0.05,R2:0.6:3,CIRCLE:1.5:0.05" --args="--hero --enemy R_MIN"   # 원작 이펙트 GO (40 문서)
./gradlew :engine:run --args="--movie SEMO01"          # 컷신 재생 (ISO 직접 읽기, --wide = PSW, START/× 건너뛰기)
```
조작: WASD 이동, J □(약공) / I △(강공) / L ○(잡기) / K ×(점프) / Q L1(방어) / E R1(대시) / Z L2(마법) / C R2(사용: 상자·문·레버·밀기·세이브, 원작과 같음), 방향키 = 오른쪽 스틱(회피),
Shift L3 + 오른쪽 Shift R3 = 신들의 분노, Enter = START(일시정지).
키·패드 버튼 바꾸기: `config/keys.properties` (예 `CROSS=K,SPACE`, `pad.CROSS=A`) — [49 문서 §6](49-extensibility.md#6-키-변경).
디버그 키: 1 충돌선 / 2 볼륨 / 3 카메라 모드 (레벨 뷰어), 4 환경 효과, **5 노멀맵** (원작에 없음, 기본 꺼짐 — `GOW_NORMALMAP=on` 으로 켜고 시작, 44 문서).

게임패드: 연결하면 자동 인식한다 (슬롯 1–16 중 첫 게임패드, 실행 중 꽂고 빼도 1 초 안에 다시 찾음). GLFW 내장 매핑(Xbox·DualShock 4·DualSense 등)을 쓰고,
버튼은 위치 기준 — 아래 ×, 오른쪽 ○, 왼쪽 □, 위 △, LB/RB = L1/R1, LT/RT = L2/R2 (절반 이상 당김), 스틱 누름 = L3/R3, 방향 패드, Start/Back = START/SELECT.
원작 1편 배치: **L2 마법**, **R2 사용**(상자·문·레버·세이브), R1 대시, ○ 잡기·포세이돈 연타, 오른쪽 스틱 회피.
인식이 안 되면 `./gradlew :engine:run --args="--pad-test"` 로 연결·매핑 상태를 확인한다. "매핑 없음" 이면 SDL 형식
[gamecontrollerdb.txt](https://github.com/mdqinc/SDL_GameControllerDB) 를 작업 폴더에 두거나 `GOW_PAD_DB=경로` 로 지정하면 시작할 때 읽는다.

## 빌드 / 도구 실행

```bash
./gradlew :tools:run --args="iso-info P2_gow_kor.iso"
./gradlew :tools:run --args="disc:info P2_gow_kor.iso"             # 부트 실행 파일(SYSTEM.CNF)·지역·PAK·언어 접미사 (다른 판도, 49 문서 §15)
./gradlew :tools:run --args="disc:setup P2_gow_kor.iso gow-rom"    # 추출 + gow.properties rom=·iso=(·region=) 합쳐 고침 (--all 동영상 포함, --dry-run)
./gradlew :tools:run --args="pak-extract P2_gow_kor.iso gow-rom"   # 동영상 제외 추출 (~1.4GB)
./gradlew :tools:run --args="wad:get gow-rom/ATHN01A.WAD TXR_floor out.bin"             # WAD 태그 데이터 꺼내기 (모드 wad/ 형식)
./gradlew :tools:run --args="wad:put gow-rom/ATHN01A.WAD TXR_floor in.bin out.WAD"      # 태그 오프라인 교체 (엔진 적재와 같은 규칙)
./gradlew :tools:run --args="mod:new mymod"                         # mods/mymod/ 뼈대 (README·rom/·wad/·plugins/·ai.properties)
./gradlew :tools:run --args="wad-ls gow-rom/ATHN01A.WAD"
./gradlew :tools:run --args="texture:export gow-rom/R_HERO0.WAD work/textures/R_HERO0"   # TXR → PNG
./gradlew :tools:run --args="model:export gow-rom/R_HERO0.WAD work/models/R_HERO0 --preview"   # OBJ+MTL(+PNG 미리보기)
./gradlew :tools:run --args="exe:listing work/SCKA_300.02 work/exe/SCKA.asm"      # R5900 주석 디스어셈블리
./gradlew :tools:run --args="exe:calls work/SCKA_300.02 0x175c98"                  # 등록 함수 호출 인자 표
./gradlew :tools:run --args="tweak:dump gow-rom/R_HERO0.WAD work/exe/twk_R_HERO0.txt"  # TWK 튜닝값 (키 이름 복원)
./gradlew :tools:run --args="progress:dump gow-rom"                                  # 업그레이드 비용·성장·오브·세이브 구조
./gradlew :engine:run --args="--ui gow-rom/R_SHELLK.WAD FLP_ShellK"                    # FLP UI 런타임 (hud|pause|powerup 모드)
./gradlew :tools:run --args="progress:levels gow-rom work/progression/levels.txt"    # 레벨 연결 그래프 (ESC 씬 이동 호출)
./gradlew :engine:test --tests '*TraversalTest*'                                  # 전 레벨 순회 검증 → work/verify/report.md (36 문서)
./gradlew :tools:run --args="combat:tree gow-rom/R_PERMK.WAD ROOT 6 normal"              # 영웅 콤보 트리 (combat:dump / combat:enemies)
./gradlew :tools:run --args="anim:sheet gow-rom/R_HERO0.WAD MDL_hero combo3A work/hitbox/c.png --weapon gow-rom/R_WPN0_2.WAD --hit"  # 블레이드 + 히트박스
./gradlew :tools:run --args="level:info gow-rom/ATHN01A.WAD"                          # 레벨 배치/충돌/볼륨/카메라
./gradlew :tools:run --args="script:entities gow-rom/ATHN01A.WAD"                     # ESC 엔티티 슬롯 / 소유 GO / ENV
./gradlew :tools:run --args="audio:sbk gow-rom/R_PERMK.WAD work/sound/R_PERMK"          # 사운드 뱅크 요약 + 톤/VAG 팩 WAV 추출
./gradlew :tools:run --args="audio:music gow-rom"                                       # 음악 이름 → VPK 스트림 표
./gradlew :engine:run --args="--level gow-rom/ATHN01A.WAD --cam auto --col"           # 레벨 뷰어
./gradlew :engine:run --args="--level gow-rom/ATHN01A.WAD --cam auto --col"           # 레벨 뷰어 (4 = 환경 효과 토글)
./gradlew :tools:run --args="video:survey P2_gow_kor.iso"                             # 동영상 헤더 요약 (video:info / frames / bench / wav / names)
./gradlew :tools:run --args="fx:env gow-rom/ATHN01A.WAD"                              # 환경 스크립트 (fx:survey / fx:matanim / fx:ptc / fx:parts)
```
