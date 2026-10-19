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
| 05 | 애니메이션 / 스켈레톤 (ANM) | 진행 예정 |
| 06 | 실행 파일(SCKA_300.02) 분석 – 서브시스템 지도 | 진행 예정 |
| 07 | [UI(FLP) / 스크립트(SCR, ESC) / 텍스트·폰트](07-ui-script-text.md) | 완료 (구조·텍스트·바이트코드, 일부 필드 미상) |
| 08 | [엔진 아키텍처 (Java + LWJGL)](08-engine-architecture.md) | 골격 완료 |
| 09 | [오디오 포맷 (VAG / VPK)](09-audio-format.md) | 완료 |
| 1x | 게임 시스템 재구현: 전투, 스킬/마법, 성장, 씬 이동, 이벤트, UI | 진행 예정 |

## 빌드 / 도구 실행

```bash
./gradlew :tools:run --args="iso-info P2_gow_kor.iso"
./gradlew :tools:run --args="pak-extract P2_gow_kor.iso gow-rom"   # 동영상 제외 추출 (~1.4GB)
./gradlew :tools:run --args="wad-ls gow-rom/ATHN01A.WAD"
./gradlew :tools:run --args="texture:export gow-rom/R_HERO0.WAD work/textures/R_HERO0"   # TXR → PNG
./gradlew :tools:run --args="model:export gow-rom/R_HERO0.WAD work/models/R_HERO0 --preview"   # OBJ+MTL(+PNG 미리보기)
```
