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
| `ParticleSystem` / `ParticleDef` | CPU 시뮬레이션 + 동적 VBO 사각형 (빌보드 / 속도 줄기), 상자·원뿔 생성, LOD |
| `Weather` | SCR_Weather → 카메라 앞 셀 정육면체 감싸기 이미터 (비/눈/불씨) |
| `LevelEmitters` | 오브젝트 그룹의 FXC_ + PTC_ → 이미터 (횃불, 화톳불, 불꽃) |
| `WaterSurface` | SCR_Water 파문 높이장 (파동 방정식 + 물방울 + 환경맵) |
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
```
- 다른 셰이더(스키닝)에도 안개를 쓰려면 `levelScene.fx().env().applyFog(shader, cam, fbHeight)` (같은 `basic.frag`).
- 하늘: SCR_Sky 규칙대로 **관절 0 월드 위치 → 카메라** (이전: AABB 중심). ATHN01A 하늘 미니어처의 바다면이 눈높이 아래에 와서 바다·원경 배·먼 바위가 보인다.

## 3. 셰이더 (기존과 호환: 새 유니폼은 기본 0 = 끔)
`basic.frag`
- 안개: `uFogMode` (0/1/2), `uFogBlend`, `uFogColor/1/2`, `uFogStops`, `uFogDepth` (D0, near, far, BackPlane), `uFogScreen`, `uFogAdd` (가산 파트는 기여를 줄임).
  깊이 d 는 `gl_FragCoord.z` 와 near/far 로 선형화 (정점 셰이더 변경 없음). `alpha = keyA · A8(z')/128` (원작 GS 패스 해석식, 16 §1.2: z' = 16·C·n·(f−d)/((f−n)·d), z' ≥ 0x7FFF → 0, 아니면 (128 − ⌊z'/256⌋); ≈ 1 − D0/d, D0 = CentreZ·10/2048, `FxEnvironment.depthAlpha`), 층 안개 색 = 화면 y 그라디언트,
  배경 패스면 BackPlane_A/128. 텍스처 레이어 `uFogTex` (유닛 1) × 2, 화면 UV 스크롤·회전·배율.
- 알파: `uAlphaRef` (CUTOUT 0.75), `uAlphaMax` (가장자리 패스).
`basic.vert` / `skinned.vert`: `uUvOffset` (UV 스크롤), `uEnvUv` (법선 구면 환경맵 UV, basic 만).
`particle.vert/frag`: 신규.

## 4. 머티리얼 (WadAssets / Renderer)
- `WadAssets.classify`: 0x48 → ADD, 0x42 → SUB, 0x01 → INV, p28·p38 ≠ 1 · TXR bit18 → ALPHA, 텍스처 없는 0x44 → OPAQUE, 나머지 → **CUTOUT**.
- `Renderer.draw`: 패스 0 = OPAQUE + CUTOUT(α ≥ 0.75, 깊이 쓰기), 패스 1 = CUTOUT 가장자리(α < 0.75, 블렌드) + 반투명. 추가 레이어는 기본 레이어 직후 LEQUAL.
  → ATHN01A 그물·밧줄, PAND01A 의 "검은 상자"(정렬 오류) 해결.
- 플립북: `WadAssets.textureFrames` 가 GFX 세로 스트립을 프레임별 GL 텍스처로 자름, 프레임 = 머티리얼 ANM type 9 키 (1/30 초).
- UV 스크롤 / 불투명도: 머티리얼 ANM type 8 / type 3 hdr8. 시간 = `Renderer.setFxTime` (레벨 시간).
- 텍스처 없는 레이어 색 = header × layer / 0.8.
- 정점색 체인: `PlacedMesh` 가 체인 c 인스턴스에 `MeshObject.chainColors(c)` (체인 c 의 색 REF) 를 쓴다.
- `AnimatedModel` (캐릭터) 은 변경하지 않음 (기존 `Blend.of`).

## 5. 파티클 / 날씨 / 물
- 비 (ATHN01A): 셀 20×16 = ±160 정육면체를 카메라 앞 150 에 두고 감쌈, 1000 개 (PTC_rain), 수명 0.6 s, 낙하 900/s + 바람 vec×16,
  `MAT_nalRain` 텍스처 가산, 줄기 48×6. 시작 시 2초 미리 채움. 볼륨 상자 밖 숨김은 미구현.
- FXC 이미터: 서브타입 1 원뿔 / 2 전방향은 원작 식, 5–9 볼륨은 로컬 배율 상자 [추정], 3/4 표면·커브는 지오메트리 미해석으로 생략.
  **PTC VU 식** (`tools/fx/PtcVu`, `ParticleDef.ptc`): 파티클마다 레코드 {(생성 위치, 0), (생성 속도)} + 고정 난수 씨앗 + 나이(초) 로 식을 계산 →
  색 (0x80 = 1.0, 알파 최대 ≈ 2), 위치, 반폭, 회전, 종류별 모양 (사각형/원반/선/줄기/평면). 블렌드 = flags&3 (0x44 / 0x48 / 0x42), TCC/TFX = flags, `particle.frag uGs` 가 GS 규칙(미리 곱한 색, As/0x80)으로 출력 [확실].
  `GOW_PTCVU=off` 면 이전 근사, `GOW_PTCVU_LOG=1` 이면 식 출력 로그.
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

## 7. 남은 문제
- ~~안개 거리 보정값~~ → 원작 패스 해석식 (16 §1.2). 남은 것: 층 안개 키 알파 결합(0x127240) 세부, TEXA 상태 가정, 층 안개 t 의 카메라 피치 의존성.
- 여러 SCR_Fog 전환은 소유 GO 최근접으로 대체 (원작은 ESC 추정).
- 비 볼륨 상자 판정, 눈/먼지/플랑크톤 모양, 표면·커브 이미터, 칼 궤적(0xB), 필드. (~~PTC 크기 단위~~ 16 §2.4)
- SCR_Mirror (반사 바닥), LensFlare, GhostTrail, RopeSkinned, cloth 미구현. LGT 동적 조명 미구현 (캐릭터용).
- 파티클·물은 레벨 렌더 안에서 그려서 이후에 그리는 캐릭터가 덮는다 (GameScene 의 늦은 효과 패스 훅 필요).
