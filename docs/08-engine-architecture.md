# 08. 엔진 아키텍처 (Java 21 + LWJGL 3)

모듈: `engine/` (패키지 `devple.gow.engine`). 분석 도구 모듈 `tools` 를 의존해 원본 데이터 파서를 그대로 재사용한다.

## 1. 실행
```bash
./gradlew :engine:run                                        # 창 실행
./gradlew :engine:run --args="--screenshot work/shot.png"    # 30프레임 후 캡처하고 종료 (자동 검증용)
```
- macOS 는 GLFW 창을 메인 스레드에서 만들어야 하므로 `applicationDefaultJvmArgs = ['-XstartOnFirstThread']`.
- LWJGL 네이티브는 `natives-macos-arm64` (Apple Silicon). Intel 맥이면 `natives-macos` 로 바꾼다.
- OpenGL 4.1 core / forward-compatible (macOS 가 지원하는 최고 버전). Retina 는 프레임버퍼 크기 기준으로 뷰포트를 잡는다.

## 2. 구성
```
engine/
 ├─ GowMain                 진입점, 실행 인자 처리
 ├─ core/
 │   ├─ Engine              메인 루프 (고정 60Hz 로직 + 가변 렌더 + 보간 alpha)
 │   └─ Window              GLFW 창 / GL 컨텍스트
 ├─ input/
 │   ├─ PadButton           PS2 듀얼쇼크2 버튼 추상화
 │   └─ Input               키보드 + 게임패드(GLFW gamepad mapping) → PadButton, 아날로그 스틱
 ├─ scene/
 │   ├─ Scene               씬 인터페이스 (enter/update/render/exit, transparent)
 │   ├─ SceneManager        씬 스택: push(오버레이) / transitionTo(페이드 전환)
 │   └─ DebugScene          동작 확인용 씬
 └─ render/
     ├─ Renderer            프레임 시작/끝, 공용 셰이더, 페이드 오버레이
     ├─ Shader, Mesh, MeshBuilder, Texture, Camera, Screenshot
     └─ resources/shaders/  basic.vert / basic.frag (GLSL 410)
```

## 3. 설계 결정
| 항목 | 결정 | 이유 |
|------|------|------|
| 게임 루프 | 고정 틱 60Hz (`Engine.TICK`) + 누적기, 렌더 보간 | 원작은 NTSC 60fps 기준으로 튜닝값(TWK)이 프레임/초 단위. 고정 틱이면 원작 수치를 그대로 쓸 수 있고 재현성이 높다 |
| 입력 | 물리 키 → `PadButton` 추상화, `pressed/held/released` 엣지 판정은 틱 단위 | 콤보 입력 판정(□□△ 등)이 틱 기준으로 결정적이 된다 |
| 씬 이동 | `SceneManager` 스택 + 페이드 | 원작의 레벨 전환(페이드 → WAD 로드 → 페이드 인), 일시정지/업그레이드 메뉴는 오버레이(push) |
| 렌더러 | 즉시 사용 가능한 단순 forward 렌더러 → 이후 원작 머티리얼(MAT) 레이어/블렌드 모드 재현 셰이더로 확장 | PS2 GS 는 고정 기능 블렌딩. 셰이더로 대응 |
| VU1 마이크로코드 | 재현하지 않음 | 정점 변환/스키닝은 GLSL 버텍스 셰이더로 대체 |
| 수학 | JOML 1.10.5 | 1.10.8 은 kotlin-stdlib 를 끌고 와서 제외 |

## 4. 검증 기록
- 2026-10-19: 디버그 씬(그리드 + 박스 + 추적 카메라) 렌더 확인. Apple M4, 2560×1440 Retina 프레임버퍼.
- 텍스처를 바인딩하지 않은 상태에서 sampler 경고(`GLD_TEXTURE_INDEX_2D is unloadable`) → 1×1 흰색 기본 텍스처를 0번 유닛에 바인딩해 해결.

## 5. 다음 단계
1. 03/04 단계 결과(텍스처, 메시)를 엔진에서 로드 → 크레토스 / 레벨 지오메트리 표시
2. 05 단계 스켈레톤·애니메이션 → GPU 스키닝
3. 06/07 단계의 실행 파일·스크립트 분석을 바탕으로 게임 오브젝트 / 전투 / UI 시스템 구현
