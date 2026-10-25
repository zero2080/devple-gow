# 31. 구현: 환경 / 이펙트 (안개, 하늘, 비, 물, 파티클, 머티리얼 애니메이션)

포맷은 [16 문서](16-environment-fx-format.md). 패키지 `devple.gow.engine.fx` (엔진), `devple.gow.tools.fx` (파서).

```bash
./gradlew :engine:run --args="--level gow-rom/ATHN01A.WAD --cam 0"          # 4 키 = 환경 효과 켜기/끄기
GOW_FX=off ./gradlew :engine:run --args="--level gow-rom/ATHN01A.WAD --cam 0" # 효과 끈 비교 스크린샷
./gradlew :engine:run --args="--game ATHN01A"                                 # 통합 게임 씬에서도 동작
```

## 1. 구성
| 클래스 | 역할 |
|-------|------|
| `LevelFx` | 레벨 씬 훅 묶음: 환경, 파티클, 물, 효과 볼륨 메시 숨김, 4 키 토글 |
| `FxEnvironment` | SCR_Fog / LayeredFog / FarClip → `basic.frag` 안개 유니폼, 활성 안개 선택, 안개 텍스처 레이어 |
| `SceneFog` | 안개 앞 반투명 패스 (파티클 버킷 0–5 · 이펙트 GO · 렌즈플레어 · 물) 의 안개 — 프레임당 장면 깊이 사본, PTC 플래그 규칙 (§3.1) |
| `ParticleSystem` / `ParticleDef` | CPU 시뮬레이션 + 동적 VBO 사각형 (빌보드 / 속도 줄기), 상자·원뿔 생성, LOD |
| `Weather` | SCR_Weather → 카메라 앞 셀 정육면체 감싸기 이미터 (비/눈/불씨) |
| `LevelEmitters` | 오브젝트 그룹의 FXC_ + PTC_ → 이미터 (횃불, 화톳불, 불꽃) |
| `WaterSurface` · `WaterSim` | SCR_Water 파문 높이장 (파동 방정식 + 물방울 + 환경맵). 격자 원점 = 소유 GO 위치 (최소 모서리), 충격 = 쌍선형 s → 이전 배열, 색 = 텍스처 × 거리 감쇠 (16 §1.6, 43 §21.2-4) |
| `MaterialFx` | 파트별 플립북 프레임, 머티리얼 ANM (UV 스크롤, 불투명도), 2·3번째 레이어 |

## 2. 렌더 훅 (LevelScene, 추가만)
```
enter   fx = new LevelFx(e, assets, data, instances)
update  fx.update(e, dt, eye)                          // GameScene 은 LevelScene.update 를 안 부르므로 beforeRender 가 스스로 진행
render  fx.beforeRender(e, cam)                        // FarClip → cam.far, renderer.setFxTime
        begin3d → fx.beginWorld(e, cam, true)  하늘   // BackPlane_A 사용
              → fx.beginWorld(e, cam, false) 지오메트리 (fx.hidden(i) = 안개/비 볼륨 상자 메시 생략)
              → fx.renderEffects(e, cam)               // 물 → 파티클 (깊이 테스트 O, 쓰기 X)
        FxEnvironment.disableFog(basic)                // 이후 HUD/디버그는 안개 없음
SceneFog.begin(env, cam, w, h)   (LevelScene.render, 하늘 단계 전)      // §3.1: 하늘 GO 파티클 = 배경 계수
SceneFog.armDepth()              (GameScene: 사슬 뒤 · 레벨 이펙트 전)   // 깊이를 쓰는 안개 앞 지오메트리 끝 → 첫 사용 때 깊이 복사
  level.renderEffects · 이웃 슬롯 이펙트 · magicFx.render              // 물 · 파티클 · 렌즈플레어 · 이펙트 GO = 장면 깊이 안개
SceneFog.end()                   (카메라 필터 · 페이드 · HUD 전, Renderer.beginFrame 도)
```
- 다른 셰이더(스키닝)에도 안개를 쓰려면 `levelScene.fx().env().applyFog(shader, cam, fbHeight)` (같은 `basic.frag`).
- 하늘: SCR_Sky 규칙대로 **관절 0 월드 위치 → 카메라** (이전: AABB 중심). ATHN01A 하늘 미니어처의 바다면이 눈높이 아래에 와서 바다·원경 배·먼 바위가 보인다.
- **하늘 판정 = SCR_Sky 대상** (43 §2.1) [확실]: 모델(MDL_x, 템플릿 스크립트 소유자) 또는 오브젝트 이름이 SCR_Sky 이름과 같은 배치 (`LevelScene.skyNames/isScrSky`).
  SCR_Sky 없는 레벨만 이전 휴리스틱 (이름/텍스처 sky + 원점 중심). 이전엔 16 레벨 (group2·skyLR·group1224·ShitBoxl …) 하늘을 놓쳐 구석이 검었다.
  + 본문 없는 인스턴스 SCR_Sky 의 **소유 GO** (ATHN03A/03AA/03C `goaresanimgroup1`, 16 §1.4) 와 GO 인스턴스 이름. 애니 하늘 GO (시작 act 반복) 는 구운 자세 대신
  `renderSkyAmbient` 가 매 프레임 스키닝 (관절 0 = 눈, 동적 조명·그림자 없음 — 0x218778), 하늘 GO 이미터는 하늘 공간 (`GameScene.startSkyEffects`) 에서 돌고
  그 파티클은 하늘 단계 끝에 그린다 (`ParticleSystem.renderSky`, 층 1 — 세계가 덮음, 43 §17.2-5),
  다른 슬롯 레벨의 하늘 GO 도 그 레벨 가시 칸대로 (`LevelScene.skyNeighbour`, 43 §16.3).
- **배경 안개** (43 §2.2) [확실: 16 §1.2]: `fx.drawBackground` 가 하늘 전에 화면 사각형(지움 색)을 배경 모드 안개로 칠한다.
  계수 `FxEnvironment.backgroundFactor` = Fog_A ≥ 1 ∧ 블렌드 0 단일 안개 → 1 (0x1275E0, 배경 Z 0 → 안개색 전부), 그 밖 BackPlane_A/128, BackPlane < 0 → 안 칠함. 비교 `GOW_FOGBG=off`.
- **패스 순서** (43 §2.3) [확실: 16 §3.3]: 모든 인스턴스의 패스 0 (불투명·알파 테스트) 뒤에 패스 1 (반투명) — `Renderer.drawPass`. 이전엔 모델마다 두 패스라
  뒤 인스턴스의 불투명 면이 반투명 면을 덮었다 (ATHN01D 판자 위 구름 반사). 비교 `GOW_PASSORDER=model`. 디버그 `GOW_HIDE=인스턴스,…` (`*`, `sky`).

## 3. 셰이더 (기존과 호환: 새 유니폼은 기본 0 = 끔)
`fog.glsl` (안개 공용, `#include` — `Shader.source` 가 펼친다): 안개 유니폼 · `layerColor` · `fogAt(창 깊이, 배경 계수)` · `fogScene()` (장면 깊이 사본).
`basic.frag` · `particle.frag` · `lensflare.frag` 가 함께 쓴다 (예전 basic.frag 안개 코드를 그대로 옮김 — 같은 결과, §3.1).
`basic.frag`
- 안개: `uFogMode` (0/1/2), `uFogBlend`, `uFogColor/1/2`, `uFogStops`, `uFogDepth` (D0, near, far, BackPlane), `uFogScreen`, `uFogAdd` (가산 파트는 기여를 줄임),
  `uFogScene` (1 = 조각 깊이 대신 장면 깊이 사본 — 이펙트 GO 반투명 파트·물), `uFogOff` (1 = 안개 뒤 층 3·4 머티리얼, 이펙트 GO).
  깊이 d 는 `gl_FragCoord.z` 와 near/far 로 선형화 (정점 셰이더 변경 없음). `alpha = keyA · A8(z')/128` (원작 GS 패스 해석식, 16 §1.2: z' = 16·C·n·(f−d)/((f−n)·d), z' ≥ 0x7FFF → 0, 아니면 (128 − ⌊z'/256⌋); ≈ 1 − D0/d, D0 = CentreZ·10/2048, `FxEnvironment.depthAlpha`), 층 안개 색 = 화면 y 그라디언트,
  배경 패스면 BackPlane_A/128. 텍스처 레이어 `uFogTex` (유닛 1) × 2, 화면 UV 스크롤·회전·배율.
  층 안개 t = `mix(uFogBand.x, uFogBand.y, 화면 y)` — `FxEnvironment.band(cam)` 이 원작 0x1208C8 처럼 시선 고도각 눈금 (화면 위/아래 = 1 − (φ ± h)/π, 지평선 0.5) 을 준다 [확실].
  Fog_A ≥ 1 ∧ 블렌드 0 단일 안개 = 원작 0x1275E0 경로: 알파 1 (Fog_A 곱 없음), BackPlane 없음 [확실]. 비교: `GOW_FOGBAND=off` (이전: t = 화면 높이 비율).
- 알파: `uAlphaRef` (CUTOUT 0.75), `uAlphaMax` (가장자리 패스).
`basic.vert` / `skinned.vert`: `uUvOffset` (UV 스크롤), `uEnvUv` (법선 구면 환경맵 UV, 둘 다), `uEnvView` (월드 → 카메라 회전 3×3 — `Renderer.envView`, 거울 패스는 반사 포함):
UV = (0.5 + 0.5·n_v.x, 0.5 − 0.5·n_v.y) (원작 VU 0x0D9, 16 §3.3, 43 §25.2). `uEnvView` 를 안 넣은 셰이더 상태는 예전처럼 월드 법선.
`particle.vert/frag`: 신규. `uScreen` = 열기 아지랑이 (VU 종류 5): 정점 셰이더가 화면 좌표(noperspective) + aUv 오프셋을 UV 로, 조각 셰이더가 프레임 복사본 `uFrame` (유닛 1) 을 DECAL 로 읽고 알파 = 정점 알파.
`uFogPass` (0 없음 · 1 장면 깊이 · 2 하늘 단계) · `uFogDst` (1 = 알파 블렌드) 로 미리 곱한 색에 안개 (§3.1). `lensflare.frag` 도 `uFogPass` 1 이면 (1 − F) 배.

### 3.1 안개 앞 반투명 패스의 장면 깊이 안개 (`fx/SceneFog`, 16 §1.2, 43 §24)
원작 안개는 층 2 칸 5 의 후처리 노드 (렌더 0x1281C8, 키 0x7100) 라 층 1 · 층 2 지오메트리 · 파티클 버킷 0–5 · 렌즈플레어 위에 깔리고, 파티클 버킷 6 (flags & 0x4040) ·
층 3 (PTC 0x80000, 블레이드·궤적, 층 3 머티리얼) 은 그 뒤다 [확실]. 안개 패스는 Z 버퍼만 보므로 깊이를 쓰지 않는 파티클은 뒤 장면 깊이의 계수를 받는다.
- 지오메트리는 예전처럼 셰이더 안 안개 (조각 자신의 깊이 — 불투명이면 후처리와 같은 값). 그 뒤 안개 앞 반투명 패스는 `SceneFog` 가 프레임에 한 번
  복사한 장면 깊이 (`glCopyTexSubImage2D` → `GL_DEPTH_COMPONENT32F`, 유닛 3) 로 같은 계수 F 와 안개색 (층 띠 · 텍스처 레이어 포함, 화면 좌표) 을 구해
  자기 색만 바꾼다: 안개 섞기 M(x) = (1−F)x + F·c 와 블렌드가 교환되므로 알파 (0x44) P' = (1−F)·P + F·c·As, 가산·감산 P' = (1−F)·P,
  안개 가산·감산 모드 (0x58 / 0x52) 면 알파만 P' = P ± F·c·As — 여러 겹도 "파티클을 다 그린 뒤 안개" 와 같다 (`SceneFogTest`).
  장면 깊이가 지움 값 (배경) 인 화소는 `FxEnvironment.backgroundFactor` (없으면 0).
- 누가 받나: 파티클 = PTC 가 있고 `SceneFog.preFogFlags` (버킷 ≠ 6 ∧ 층 ≠ 3) 인 정의 (`ParticleSystem.fogFor`) — 레벨 이미터 · 날씨 · 이웃 슬롯 이미터 ·
  이펙트 파티클. 근사 정의 (PTC 없음, `MagicFx` 의 bolt/soul …) 와 사슬·궤적 (`BladeChainRenderer`, 층 3) 은 안개 없음. 하늘 단계 파티클 (층 1) 은 하늘 지오메트리와
  같은 배경 계수 (`uFogPass` 2). 이펙트 GO 모델 (`AnimatedModel.sceneFog`: Creator 병사 · 파편 · 마법 GO) = 불투명 조각 깊이 · 반투명 장면 깊이 · 가산 (1−F) ·
  층 3·4 머티리얼 없음 (예전엔 안개가 아예 없었다). 렌즈플레어 (가산) · 물 (층 2 모델 쪽으로 봄 [근사], 예전 게임 경로는 안개 없음) 도 장면 깊이.
- 그대로 둔 것 [미수정 — 지오메트리 모양을 바꾸지 않기로]: ~~층 3 지오메트리 (블레이드, 층 3 머티리얼) 는 원작이 안개 뒤인데 셰이더 안 안개를 받는다~~,
  ~~그림자 합성 (원작은 안개 앞 −38 → 안개가 그림자를 옅게) 은 안개 뒤처럼 −38~~, 반투명 레벨 지오메트리는 뒤 Z 대신 자기 깊이 [근사].
- **2026-10-25 (43 §31)**: 캐릭터·자세 GO 는 사실 안개를 아예 받지 않았다 — `FxEnvironment.applyFog` / `disableFog` 가 프로그램을 묶지 않고 glUniform 을 불러 영웅·적·자세 GO
  셰이더에 건 값이 그때 묶인 다른 프로그램으로 갔다 → 두 함수가 먼저 `s.use()`. 블레이드·아르테미스의 검은 층 3 이 아니라 flags2 0x100 층 2 (층 3 은 사슬·궤적 띠) 라 안개를
  받는다. `AnimatedModel` 은 층 3 모델 · 층 3·4 머티리얼 파트에 uFogOff, 가산·뺄셈 파트에 uFogAdd 를 모든 모델에 건다. 그림자 합성은 `SceneFog.applyShadow` 의 깊이로
  38·(1 − F) 만 뺀다 (`ShadowRenderer.foggedSubtract`, shadow.frag).
- 확인: `-Dgow.scenefog=off` (또는 `GOW_SCENEFOG=off`) 면 예전 그대로 — 10 화면에서 예전 빌드와 화소까지 같다 (43 §24).
- 비용 (1280×720, M 시리즈, 05A 마당, 수직 동기 끔): GPU +0.4–0.9 ms (프레임 2.2 → 2.8 ms), 스왑 전 CPU +0.9 ms — 깊이 복사가 렌더 패스를 나누는 고정 비용
  (64×64 만 복사 · 32F/24 · 블릿 모두 같음), 프레임 간격 7.8 → 7.3 ms 로 늘지 않음. 안개가 없거나 안개 앞 반투명 패스가 없는 프레임은 복사하지 않는다.

## 4. 머티리얼 (WadAssets / Renderer)
- `WadAssets.classify`: 0x48 → ADD, 0x42 → SUB, 0x01 → INV, p28·p38 ≠ 1 · TXR bit18 → ALPHA, 텍스처 없는 0x44 → OPAQUE, 나머지 → **CUTOUT**.
- `Renderer.draw`: 패스 0 = OPAQUE + CUTOUT(α ≥ 0.75, 깊이 쓰기), 패스 1 = CUTOUT 가장자리(α < 0.75, 블렌드) + 반투명. 추가 레이어는 기본 레이어 직후 LEQUAL.
  레벨은 `drawPass` 로 장면 전체 패스 0 → 패스 1 (§2, 43 §2.3).
  → ATHN01A 그물·밧줄, PAND01A 의 "검은 상자"(정렬 오류) 해결.
- 플립북: `WadAssets.textureFrames` 가 GFX 세로 스트립을 프레임별 GL 텍스처로 자름, 프레임 = 머티리얼 ANM type 9 키 (1/30 초).
- UV 스크롤 / 불투명도: 머티리얼 ANM type 8 / type 3 hdr8. 시간 = `Renderer.setFxTime` (레벨 시간).
- 텍스처 없는 레이어 색 = header × layer / 0.8.
- 정점색 체인: `PlacedMesh` 가 체인 c 인스턴스에 `MeshObject.chainColors(c)` (체인 c 의 색 REF) 를 쓴다.
- 환경맵 (16 §3.3): 레이어 rf & 0x02 (`MaterialFx.ENV_FLAG`) 만 — 예전엔 0x04 도 환경맵으로 봤다. 첫 레이어가 환경맵이면 `MaterialFx.env0` → `Renderer` 가 첫 레이어도 법선 구면 UV
  (노멀맵 없음). 발리스타 틀 `MAT_M02MetalTrim01b` 가 메시 UV 로 크롬 공의 검은 바깥을 찍던 것 (43 §25.2).
- `AnimatedModel` (캐릭터 · 자세 GO · 이펙트 GO): 블렌드는 기존 `Blend.of`. 파트마다 `WadAssets.materialFx` 를 받아 첫 레이어 환경맵 · 2·3 번째 레이어 (`drawLayers`, LEQUAL,
  CUTOUT 은 패스 0 뒤에만) 를 구운 레벨 메시와 같은 규칙으로 그린다 — 예전엔 첫 레이어만이라 밀린 발리스타 틀 · 하데스의 군대 가산 레이어가 빠졌다.

## 5. 파티클 / 날씨 / 물
- 날씨 (`fx/Weather`, 16 §1.5) = 원작 경로: 종류 GO 의 PTC (레벨 WAD, 없으면 R_PERMK — `LevelFx` 가 레벨 WAD 옆 R_PERMK 를 필요할 때 읽음) 를
  최대 개수만큼 한 번 채우고 (`ParticleSystem.Emitter.weather`), 매 프레임 −vec·16·dt (+ 비 낙하 160/s, 플랑크톤 난수 걸음) 이동 → 카메라 앞
  (셀×8 − near 2) 정육면체로 감싸기 → 볼륨 상자 (소유 ± box×8) 밖은 안 그림. 그리기 = PTC VU 식, 나이 = 수명 − 0.5 고정, 레코드 = PTC +0x8C 속성 배치
  (비: q1 = 방향). PTC 머티리얼은 PTC 앞 마지막 같은 이름 MAT/TXR 로 푼다 (`WadAssets.textureBefore`; R_PERMK MAT_lambert2 가 여럿).
  이전 문제 [확실]: 손으로 맞춘 비 (줄기 48, 색 1.7) 를 모든 종류에 썼고 (플랑크톤·먼지 레벨도 비), PTC VU 경로는 방향 레코드(q1)가 비어 화면이 하얗게 덮였다.
  `GOW_PTCVU=off` 면 근사 이미터 (비는 원작 속도·크기로 맞춤). 확인: ATHN01A/D 가는 사선 빗줄기, DEST01 옅은 모래 안개, PAND00C 떠다니는 점.
- FXC 이미터: 서브타입 1 원뿔 / 2 전방향은 원작 식, 5–9 볼륨은 로컬 배율 상자 [추정], 4 커브는 0xD 노드의 NCV 커브 위 (16 §2.5, 43 §19 — ATHN05A 불벽),
  3 표면은 메시 표면 미해석으로 생략.
  - **정의 고르기** (16 §2, 43 §25.1): 같은 이름 FXC_/PTC_/필드/0xD 노드는 오브젝트 태그 앞의 마지막 정의 (`FxResources.emitter/shape/field/geom(이름, LevelObject.tag)`) —
    `LevelEmitters` · `EffectLibrary` · `curveSamples`. 정의 캐시는 (FXC, PTC) 객체 쌍마다.
  - **속력 · 생성 거리** (서브타입 1 · 2): × `LevelEmitters.speedScale` = |이미터 월드 · dir| / |dir| (원작 생성 행렬에 이미터 월드 배율이 들어감), 생성 위치 = 원점 + 뽑은 방향 ·
    lerp(최소, 최대 거리) × 16 × 배율 (`Emitter.coneDistMin/Max`). 레벨 GO 관절 이미터 (`EffectSystem`) 도 같은 배율, 근사 속력 이펙트는 예전 `min(배율, 4)`.
  - **CPU 동역학** (16 §2.3): `ParticleDef.cpuMotion` (PTC 배치 바이트 0xB bit 0) ∧ `Emitter.cpuMotion` (레벨 이미터 · 레벨 GO 관절 이미터) 이면 VU 레코드 위치 = 처음 위치 + v·t +
    `fieldAccel`·t²/2 (`ParticleSystem.record`) — 원작 0x15C0F0. 필드 가속 = 소유 GO 의 중력 · 균일 필드 합 (`LevelEmitters.fieldAccel`, 0x12B530) [근사: 상수 가속,
    소용돌이 · 뉴턴 · 감쇠 · 최대 거리 생략]. 파티클 공간 (0x10/0x20) 이면 가속을 공간 역행렬로.
  **PTC VU 식** (`tools/fx/PtcVu`, `ParticleDef.ptc`): 파티클마다 레코드 {(생성 위치, 0), (생성 속도)} + 고정 난수 씨앗 + 나이(초) 로 식을 계산 →
  색 (0x80 = 1.0, 알파 최대 ≈ 2), 위치, 반폭, 회전, 종류별 모양 (사각형/원반/선/줄기/평면). 블렌드 = flags&3 (0x44 / 0x48 / 0x42), TCC/TFX = flags, `particle.frag uGs` 가 GS 규칙(미리 곱한 색, As/0x80)으로 출력 [확실].
  `GOW_PTCVU=off` 면 이전 근사, `GOW_PTCVU_LOG=1` 이면 식 출력 로그 (+ 레벨의 열기 아지랑이 이미터 위치).
  - **파티클 공간** (PTC 0x10/0x20, 16 §2.3) [확실]: `Emitter.space` = 소유자 월드 (이펙트 = 앵커·배율 제거, 레벨 = 관절 월드). 0x10 이면 회전, 0x20 이면 이동을
    파티클 공간에 넣고, 생성 레코드는 그 역행렬로, VU 출력(위치·방향·축)은 매 프레임 현재 공간 행렬로 월드에 옮긴다 → 손·무기·투사체에 붙은 이펙트의 파티클이 소유자를 따라간다.
  - **열기 아지랑이** (종류 5, 16 §2.4) [확실: 구조, 추정: UV 단위]: 다른 파티클을 다 그린 뒤 (원작 버킷 6) `glCopyTexSubImage2D` 로 화면 복사 → 원반 (부채꼴 2·꼬리+2 .z),
    중심 UV 만 −굴절량(0x225.w)·화면 반지름 만큼 x 로 민다, 알파 중심 0x222.a → 테두리 0x223.a. 비교: `GOW_HEAT=off`.
  - **피 자국** (종류 9): 축 = `Emitter.normal` (이펙트 `planeNormal`, 바닥 피는 충돌 레이 면 법선) 평면 안의 두 축 + 파티클별 임의 회전, 위치는 이미터를 지나는 평면에 투영 (원작 0x1F8990) [확실].
- 물 (PAND01A 등): 격자 nx×nz, 60 Hz 고정 스텝, 물방울은 3×3 로 나눠 스파이크 방지, UV = 법선 + 위치 → REFLENVM, α 0.4.
- 성능: 파티클 최대 12000, 정의별 1 드로우. PAND01A 이미터 86개, ATHN01A 비 1000개 — M4 에서 체감 저하 없음.

## 6. 검증
- 테스트 `EnvFxTest` (5): ATHN01A LayeredFog/비/하늘 값, PAND01A Fog·Water·HDES FarClip, ATHN07 풀 플립북 20키, ATHN01A UV 스크롤, PAND01A 횃불 FXC→PTC 연결·색 곡선.
- 스크린샷 (`work/fx/`, 커밋 제외):
  - `a.png` / `a_off.png`: ATHN01A 갑판 — 비 줄기, 지평선 아래 회녹색 층 안개, 하늘 미니어처의 바다·먼 바위, 그물/밧줄(CUTOUT).
  - `game.png`: `--game ATHN01A` 크레토스 + 비 + 바다 (원작 오프닝과 가장 가까운 화면).
  - `p.png`: PAND01A 안뜰 — 검은 상자 사라짐, 깃발/난간 정상, 횃불·화톳불, 구덩이 물 표면.
  - `g.png` / `g_off.png`: ATHN07 신전 문 — 푸른 밤 안개, 풀 플립북.
  - `h.png` / `h_off.png`: HDES01A — 붉은/주황 층 안개, FarClip 2800. 안개가 원작보다 짙어 보임 (D0 보정값 의존).

## 6.1 검증 (PTC VU / 안개, 2026-10)
- `PtcVuTest` (3): SKLpart 표·식 (색·중력·반폭), Tfire 가산·포화, rslicepart 알파, HOgpart 원반, BloodSpart 모서리, 안개 깊이 알파 경계 (ATHN07 181).
- 스크린샷 (`work/shots/`, 커밋 안 함): `bon_vu/bon_old` PAND01A 화톳불·등불 (이전 흰 덩어리 → 작은 불꽃), `hero_vu/old` 피,
  `p3g_600/p3g_600_old` PAND03G 백색 화면 해소 (원인 = 이전 근사의 거대한 가산 파티클), `p3g_4200` 배출구 증기,
  `fog_*_{old,new}`, `fogG_*_{old,new}` ATHN01A/ATHN07/HDES01A (HDES01A 기둥: 가까운 화소 안개 없음).
- 디버그: `GOW_LEVEL_CAM=ex,ey,ez,tx,ty,tz` (LevelScene 카메라 고정, `print` = 레벨 카메라 출력).

## 6.2 검증 (PTC 2차, 2026-10)
- `PtcVuTest` +2: 평면 연산 9 (반사 1회) / 10 (반발 0.5 튕김·10회 뒤 정지·이전 프레임 값), 층 안개 t 눈금 (수평 = 0.5 중심, 화각 60° = 1/3).
- 스크린샷 (스크래치 `ptc2/`, 커밋 안 함): `h3 / h3off` ATHN03D 복도 횃불 (`GOW_LEVEL_CAM=135,562,1250,105,556,1185`) — 아지랑이가 횃불 머리·벽 모서리를 휘게 함 (차이 5309 px),
  `fogA / fogA_old` ATHN01A (고도각 눈금: 갑판 쪽 바다 안개가 화면 아래 전체로), `hdes / hdes_old` HDES01A (주황 키 c1 이 지평선 바로 위 띠로),
  `a8b` ARENA08 전투 (적중 피·바닥 피 자국), `p3g` PAND03G 보스 데모, `med` 메두사 광선 (회귀 없음).

## 6.3 검증 (전 레벨 시각 QA, 2026-10, 43 문서)
- 효과 켬 105 레벨 스크린샷: 예외 0, 정상 84 (이번 수정으로 개선 14), 시작 카메라 13 (효과 무관), 부속 WAD 4, 의심 4 (ATHN05AA 길거리 연기층, OLYMP02 거울 없는 유리 바닥,
  PAND03A DMC 결계 바인드 자세, ATHN01A 물보라 밝기). 수정: SCR_Sky 하늘 판정, 배경 안개, 반투명 패스 순서. 테스트 `SkyBackgroundTest` (3).

## 7. 남은 문제
- ~~안개 거리 보정값~~ → 원작 패스 해석식 (16 §1.2). ~~층 안개 키 알파·t 의 카메라 피치~~ (16 §1.3). 남은 것: TEXA 상태 가정, 텍스처 층 안개 0x127240 UV.
- 여러 SCR_Fog 전환은 소유 GO 최근접으로 대체 (원작은 ESC 추정).
- 비 볼륨 상자 판정, 눈/먼지/플랑크톤 모양, 표면 이미터, 칼 궤적(0xB), 필드. (~~PTC 크기 단위~~ 16 §2.4, ~~레벨 커브 이미터~~ 16 §2.5)
- ~~SCR_Mirror~~ (§8 / 43 §9·§12: 합성 순서·층 0·값 [0] 흐림 해석, 0x12ABA0 의 Z 최대 효과만 미해석), ~~LensFlare~~ (46), GhostTrail, RopeSkinned, cloth 미구현.
- 반투명 패스 안의 정렬 (원작 정렬 단위 미확인, 지금은 인스턴스 순서). ~~스크립트 Animator 없이 보이는 관절 오브젝트 (PAND03A `godmcdog2`) 는 바인드 자세~~ → 시작 act 자세 (43 §6). ~~LGT 동적 조명 (캐릭터용)~~ → 47 문서.
- 파티클·물은 레벨 렌더 안에서 그려서 이후에 그리는 캐릭터가 덮는다 (GameScene 의 늦은 효과 패스 훅 필요).

## 8. SCR_Mirror 반사 (`level/Mirrors`, `LevelScene.renderMirrors`) — 16 §1.7
- 원작 [확실]: 소유 GO 를 일반 그리기에서 빼고, 거울 렌더러가 카메라를 평면에 대해 반사 (0x12A4E8) 해 장면을 거울 영역에 합성.
- 엔진 [근사: GL 합성]: 평면 = 거울 메시 (월드) 면적 가중 법선 · 무게중심 (이탈 > max(0.5, 크기 1%) 면 모델 Y 축 평면, 43 §9).
  불투명 패스 뒤: ① 보이는 거울 화소 스텐실 + 가림 질의 ② 그 화소 깊이 = 1 (`glDepthRange(1,1)`) ③ 조건부 렌더로 VP·R (R = 평면 반사) 하늘 (반사 눈 위치 중심) + 레벨 인스턴스 2 패스,
  `gl_ClipDistance` (basic.vert `uClipPlane`) 로 평면 뒤쪽 자름 ④ 거울 깊이 복원 ⑤ 거울 모델 두 패스 (일반 패스에서는 뺌 = 원작 bit 61) —
  불투명 파트는 반사를 덮고 반투명 파트 (OLYMP02·ATHN04B 대리석) 는 반사 위에 블렌드 [추정: 원작 합성 순서].
- ~~반사에 넣지 않는 것: 영웅·적, 스키닝 오브젝트, 파티클~~ → 43 §9 로 정정 [확실: 뷰 마스크 0x1508D0]:
  거울 뷰 (0x82, 값 [4] ≠ 0 이면 0x02) 에는 정적 월드를 다시 그리지 않고 **반사 전용 대리 메시 (MDL +0x28 bit 57, 일반 뷰에서는 안 그림)**,
  bit 61 모델 (자세/반복 act 포함, 스키닝으로), 값 [4] = 0 이면 영웅·블레이드 (`setMirrorActors`) 만 그린다. 적·파티클·하늘은 없다.
  거울 소유자는 bit 61 만 꺼질 뿐 일반 뷰에 그대로 그린다 (⑤ 는 합성 순서 근사). 가장 가까운 거울 하나만 반사 (0x12A6C8),
  평면 아닌 거울 메시는 모델 Y 축 평면 (0x12A330). 반사 카메라 = `Camera.mirror`/`clipPlane`, `glFrontFace(GL_CW)`.
  비교 `GOW_MIRROR=off` (이전: 반사 없음 + 대리 메시 일반 뷰), `GOW_MIRROR=raw` (거울 모델 생략).
- **43 §12 후속** [확실: 0x161A80 · 0x165998 · 0x11FEA0 · 0x152EC8]: 원작은 거울 뷰를 프레임 맨 앞에 그리고 소유 모델 (층 0) 을 알파 테스트 없이 그 위에 블렌드
  ((Cs−Cd)·As+Cd, 깊이 씀) → ⑤ 를 `Renderer.drawLayer0` 로 (불투명 분류 없음, 반사 = 1 − As). 반사 바탕 = 지움 색 + 배경 안개, 값 [0] ≠ 0 이면 ½ 축소·확대 흐림 2 회
  (`render/MirrorComposite`), 거울 고르기 = 모델 Y 축 앞쪽 + 경계 중심 거리 (0x12A330), 가시 칸 = 주 눈과 반사 눈의 누적 마스크 (모든 뷰 공통).
