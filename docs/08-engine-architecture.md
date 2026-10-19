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

- 2026-10-19: `--view gow-rom/R_HERO0.WAD` 로 `MDL_hero` 를 원본 텍스처·머티리얼로 렌더 확인 (문신/수염/치마/팔 붕대 정상).

- 2026-10-19: `--view gow-rom/ARENA06.WAD "*"` (모든 MDL 을 변환 없이 겹쳐 그림) → 하늘 구체 + 거대한 검은 박스만 보임.
  레벨 메시가 전부 월드 좌표로 저장된 것은 아니며, **오브젝트/CXT 배치 변환이 있어야 레벨이 구성됨**을 확인. 검은 박스는 오클루더/볼륨용 메시로 추정(머티리얼 확인 필요).
  → 해결: 12 문서 (go 인스턴스 · 관절 월드 · 정적 메시 STROW 제거), 23 문서 `LevelScene` (`--level`).

## 5. 에셋 연동 (asset 패키지)
| 클래스 | 역할 |
|-------|------|
| `WadAssets` | WAD 하나 → `TextureLibrary`(03) / `GowModel`·`TriMesh`(04) 로 파싱, GL 텍스처·`RenderModel` 캐시 |
| `RenderModel` | 서브메시별 `Mesh` + 머티리얼 첫 레이어의 텍스처, 블렌드(OPAQUE/ALPHA/ADD/SUB), 색 배율, 조명 여부 |
| `Renderer.draw(RenderModel, Matrix4f)` | 불투명 → 반투명 2패스. 반투명은 깊이 쓰기 끔. GS 블렌드 0x44/0x48/0x42 를 GL 블렌드 식으로 매핑 |

변환 규칙
- 정점색: PS2 `0x80 = 1.0` → `c / 128` (GS MODULATE 가 `tex × color / 0x80` 이므로 2배 밝기까지 표현 가능, 셰이더에서 그대로 곱함)
- UV: GS 텍스처 좌표 원점이 좌상단 = 이미지 첫 행. GL 업로드도 첫 행부터 하므로 뒤집지 않는다
- 법선이 없는 정적 메시(레벨)는 베이크된 정점색만 사용(`lit=false`), 컬링 없음(양면)

## 6. GPU 스키닝 (05 문서 연동)
| 클래스 | 역할 |
|-------|------|
| `render/SkinnedMesh` | 정점 15 float: pos, normal, uv, color, joints(A,B), weight |
| `shaders/skinned.vert` | `p = (J[A]·v)·w + (J[B]·v)·(1−w)` — 원작 VU1 의 2관절 블렌딩 대체 |
| `asset/AnimatedModel` | `SkinnedModel`(tools) → GPU. 파트마다 팔레트 선택: 모델 공간 파트 = `skin`(invBind·world), 관절 로컬 파트 = `world` |
| `scene/AnimViewerScene` | `--anim <WAD> <MDL> <스켈레톤> <ANM> [애니]`, □/△ 로 251개 애니메이션 순환 |

- 원본 행렬은 **행벡터 규약 row-major**. 그대로(전치 없이) `glUniformMatrix4fv` 로 올리면 GLSL 이 열 우선으로 읽어 전치가 되므로 `M * v` 가 원작의 `v · M` 과 같아진다 → 변환 코드 없이 재사용.
- 관절 수: 크레토스 111 (유니폼 배열 128 로 충분, macOS GL 4.1 정점 유니폼 1024 vec4).
- `SkinnedModel.Part` 에 UV / 정점색 / hasUv / hasNormal 추가 (텍스처 표시용).
- 검증: `combo3A`, `walkBlend1` 을 엔진에서 텍스처 포함 재생 (스크린샷 `work/anim_engine.png`, `work/anim_walk.png`).

## 7. 다음 단계
1. 레벨: 게임 오브젝트(`go*`)/CXT 배치 정보를 해석해 레벨 전체 지오메트리 배치
2. 05 단계 스켈레톤·애니메이션 → GPU 스키닝
3. 06/07 단계의 실행 파일·스크립트 분석을 바탕으로 게임 오브젝트 / 전투 / UI 시스템 구현
