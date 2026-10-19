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
| 05 | [스켈레톤 / 애니메이션 (CXT 스켈레톤, ANM)](05-animation-format.md) | 완료 (회전·이동 채널, 스키닝; 부속 채널·이벤트 미상) |
| 06 | [실행 파일(SCKA_300.02) 분석 – 서브시스템 지도 / TWK 해시](06-executable-analysis.md) | 1차 완료 (심화 목록 §10) |
| 07 | [UI(FLP) / 스크립트(SCR, ESC) / 텍스트·폰트](07-ui-script-text.md) | 완료 (구조·텍스트·바이트코드, 일부 필드 미상) |
| 08 | [엔진 아키텍처 (Java + LWJGL)](08-engine-architecture.md) | 골격 + 모델 렌더 |
| 09 | [오디오 포맷 (VAG / VPK)](09-audio-format.md) | 완료 |
| 10 | [전투 시스템 (CombatFile 콤보 그래프 / 피해 / 신들의 분노 / 마법 / 적)](10-combat-system.md) | 1차 완료 (포맷 확정, 재구현 명세 §15) |
| 11 | [성장(업그레이드 비용) / 픽업(오브·아이템) / 저장(세이브 구조) / 씬 흐름(상태 머신·레벨 그래프)](11-progression-save-flow.md) | 1차 완료 (미해결 §9) |
| 12 | [레벨 구성: 배치(go/오브젝트) / 충돌(Sheet, BallHull) / 카메라(CAM, CMV, NCV 레일)](12-level-format.md) | 1차 완료 (표면 속성 비트 미상) |
| 13 | [레벨 스크립트 엔티티 런타임: 타입별 슬롯 / 메시지·발동 순서 / Entity·Internal 함수 인자](13-script-entities.md) | 1차 완료 (이벤트 번호·Vis 속성 일부 미상) |
| 14 | [사운드 뱅크 (SND / 989snd SBlk 그레인·톤 / DPMS 플러그인 / VAG 팩 / SEM 이미터 / 음악 선택)](14-sound-bank-format.md) | 1차 완료 (전 뱅크 디코딩, ADSR·LFO·그레인 10–12 미상) |
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
| 30 | [구현: 레벨 카메라 시스템 (CameraRig)](30-impl-camera.md) | 선택·레일·전환 완료 (테스트 5) |
| 90 | [현황 / 로드맵](90-status-roadmap.md) | 수시 갱신 |
| 2x | 구현 진행 중: 환경 연출(31) | 진행 중 |

## 게임 실행

```bash
./gradlew :engine:run                                  # 메인 메뉴 → 새 게임 → ATHN01A (원작 흐름)
./gradlew :engine:run --args="--game ATHN01A"          # 레벨 바로 시작
./gradlew :engine:run --args="--game PAND01A --entry gomusicgrppan1c1start --auto-run 3"   # 진입점 지정 + 자동 달리기
./gradlew :engine:run --args="--hero --enemy R_MIN"    # 평지 전투 연습
```
조작: WASD 이동, J □(약공) / I △(강공) / L ○(잡기) / K ×(점프) / Q L1(방어) / E R1, C R2(마법), 방향키 = 오른쪽 스틱(회피),
Shift L3 + 오른쪽 Shift R3 = 신들의 분노, Enter = START(일시정지). 게임패드 자동 인식.

## 빌드 / 도구 실행

```bash
./gradlew :tools:run --args="iso-info P2_gow_kor.iso"
./gradlew :tools:run --args="pak-extract P2_gow_kor.iso gow-rom"   # 동영상 제외 추출 (~1.4GB)
./gradlew :tools:run --args="wad-ls gow-rom/ATHN01A.WAD"
./gradlew :tools:run --args="texture:export gow-rom/R_HERO0.WAD work/textures/R_HERO0"   # TXR → PNG
./gradlew :tools:run --args="model:export gow-rom/R_HERO0.WAD work/models/R_HERO0 --preview"   # OBJ+MTL(+PNG 미리보기)
./gradlew :tools:run --args="exe:listing work/SCKA_300.02 work/exe/SCKA.asm"      # R5900 주석 디스어셈블리
./gradlew :tools:run --args="exe:calls work/SCKA_300.02 0x175c98"                  # 등록 함수 호출 인자 표
./gradlew :tools:run --args="tweak:dump gow-rom/R_HERO0.WAD work/exe/twk_R_HERO0.txt"  # TWK 튜닝값 (키 이름 복원)
./gradlew :tools:run --args="progress:dump gow-rom"                                  # 업그레이드 비용·성장·오브·세이브 구조
./gradlew :engine:run --args="--ui gow-rom/R_SHELLK.WAD FLP_ShellK"                    # FLP UI 런타임 (hud|pause|powerup 모드)
./gradlew :tools:run --args="progress:levels gow-rom work/progression/levels.txt"    # 레벨 연결 그래프 (ESC 씬 이동 호출)
./gradlew :tools:run --args="combat:tree gow-rom/R_PERMK.WAD ROOT 6 normal"              # 영웅 콤보 트리 (combat:dump / combat:enemies)
./gradlew :tools:run --args="level:info gow-rom/ATHN01A.WAD"                          # 레벨 배치/충돌/볼륨/카메라
./gradlew :tools:run --args="script:entities gow-rom/ATHN01A.WAD"                     # ESC 엔티티 슬롯 / 소유 GO / ENV
./gradlew :tools:run --args="audio:sbk gow-rom/R_PERMK.WAD work/sound/R_PERMK"          # 사운드 뱅크 요약 + 톤/VAG 팩 WAV 추출
./gradlew :tools:run --args="audio:music gow-rom"                                       # 음악 이름 → VPK 스트림 표
./gradlew :engine:run --args="--level gow-rom/ATHN01A.WAD --cam auto --col"           # 레벨 뷰어
```
