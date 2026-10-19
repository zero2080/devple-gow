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
- **하늘 판정 = SCR_Sky 대상** (43 §2.1) [확실]: 모델(MDL_x, 템플릿 스크립트 소유자) 또는 오브젝트 이름이 SCR_Sky 이름과 같은 배치 (`LevelScene.skyNames/isScrSky`).
  SCR_Sky 없는 레벨만 이전 휴리스틱 (이름/텍스처 sky + 원점 중심). 이전엔 16 레벨 (group2·skyLR·group1224·ShitBoxl …) 하늘을 놓쳐 구석이 검었다.
- **배경 안개** (43 §2.2) [확실: 16 §1.2]: `fx.drawBackground` 가 하늘 전에 화면 사각형(지움 색)을 배경 모드 안개로 칠한다.
  계수 `FxEnvironment.backgroundFactor` = Fog_A ≥ 1 ∧ 블렌드 0 단일 안개 → 1 (0x1275E0, 배경 Z 0 → 안개색 전부), 그 밖 BackPlane_A/128, BackPlane < 0 → 안 칠함. 비교 `GOW_FOGBG=off`.
- **패스 순서** (43 §2.3) [확실: 16 §3.3]: 모든 인스턴스의 패스 0 (불투명·알파 테스트) 뒤에 패스 1 (반투명) — `Renderer.drawPass`. 이전엔 모델마다 두 패스라
  뒤 인스턴스의 불투명 면이 반투명 면을 덮었다 (ATHN01D 판자 위 구름 반사). 비교 `GOW_PASSORDER=model`. 디버그 `GOW_HIDE=인스턴스,…` (`*`, `sky`).

## 3. 셰이더 (기존과 호환: 새 유니폼은 기본 0 = 끔)
`basic.frag`
- 안개: `uFogMode` (0/1/2), `uFogBlend`, `uFogColor/1/2`, `uFogStops`, `uFogDepth` (D0, near, far, BackPlane), `uFogScreen`, `uFogAdd` (가산 파트는 기여를 줄임).
  깊이 d 는 `gl_FragCoord.z` 와 near/far 로 선형화 (정점 셰이더 변경 없음). `alpha = keyA · A8(z')/128` (원작 GS 패스 해석식, 16 §1.2: z' = 16·C·n·(f−d)/((f−n)·d), z' ≥ 0x7FFF → 0, 아니면 (128 − ⌊z'/256⌋); ≈ 1 − D0/d, D0 = CentreZ·10/2048, `FxEnvironment.depthAlpha`), 층 안개 색 = 화면 y 그라디언트,
  배경 패스면 BackPlane_A/128. 텍스처 레이어 `uFogTex` (유닛 1) × 2, 화면 UV 스크롤·회전·배율.
  층 안개 t = `mix(uFogBand.x, uFogBand.y, 화면 y)` — `FxEnvironment.band(cam)` 이 원작 0x1208C8 처럼 시선 고도각 눈금 (화면 위/아래 = 1 − (φ ± h)/π, 지평선 0.5) 을 준다 [확실].
  Fog_A ≥ 1 ∧ 블렌드 0 단일 안개 = 원작 0x1275E0 경로: 알파 1 (Fog_A 곱 없음), BackPlane 없음 [확실]. 비교: `GOW_FOGBAND=off` (이전: t = 화면 높이 비율).
- 알파: `uAlphaRef` (CUTOUT 0.75), `uAlphaMax` (가장자리 패스).
`basic.vert` / `skinned.vert`: `uUvOffset` (UV 스크롤), `uEnvUv` (법선 구면 환경맵 UV, basic 만).
`particle.vert/frag`: 신규. `uScreen` = 열기 아지랑이 (VU 종류 5): 정점 셰이더가 화면 좌표(noperspective) + aUv 오프셋을 UV 로, 조각 셰이더가 프레임 복사본 `uFrame` (유닛 1) 을 DECAL 로 읽고 알파 = 정점 알파.

## 4. 머티리얼 (WadAssets / Renderer)
- `WadAssets.classify`: 0x48 → ADD, 0x42 → SUB, 0x01 → INV, p28·p38 ≠ 1 · TXR bit18 → ALPHA, 텍스처 없는 0x44 → OPAQUE, 나머지 → **CUTOUT**.
- `Renderer.draw`: 패스 0 = OPAQUE + CUTOUT(α ≥ 0.75, 깊이 쓰기), 패스 1 = CUTOUT 가장자리(α < 0.75, 블렌드) + 반투명. 추가 레이어는 기본 레이어 직후 LEQUAL.
  레벨은 `drawPass` 로 장면 전체 패스 0 → 패스 1 (§2, 43 §2.3).
  → ATHN01A 그물·밧줄, PAND01A 의 "검은 상자"(정렬 오류) 해결.
- 플립북: `WadAssets.textureFrames` 가 GFX 세로 스트립을 프레임별 GL 텍스처로 자름, 프레임 = 머티리얼 ANM type 9 키 (1/30 초).
- UV 스크롤 / 불투명도: 머티리얼 ANM type 8 / type 3 hdr8. 시간 = `Renderer.setFxTime` (레벨 시간).
- 텍스처 없는 레이어 색 = header × layer / 0.8.
- 정점색 체인: `PlacedMesh` 가 체인 c 인스턴스에 `MeshObject.chainColors(c)` (체인 c 의 색 REF) 를 쓴다.
- `AnimatedModel` (캐릭터) 은 변경하지 않음 (기존 `Blend.of`).

## 5. 파티클 / 날씨 / 물
- 날씨 (`fx/Weather`, 16 §1.5) = 원작 경로: 종류 GO 의 PTC (레벨 WAD, 없으면 R_PERMK — `LevelFx` 가 레벨 WAD 옆 R_PERMK 를 필요할 때 읽음) 를
  최대 개수만큼 한 번 채우고 (`ParticleSystem.Emitter.weather`), 매 프레임 −vec·16·dt (+ 비 낙하 160/s, 플랑크톤 난수 걸음) 이동 → 카메라 앞
  (셀×8 − near 2) 정육면체로 감싸기 → 볼륨 상자 (소유 ± box×8) 밖은 안 그림. 그리기 = PTC VU 식, 나이 = 수명 − 0.5 고정, 레코드 = PTC +0x8C 속성 배치
  (비: q1 = 방향). PTC 머티리얼은 PTC 앞 마지막 같은 이름 MAT/TXR 로 푼다 (`WadAssets.textureBefore`; R_PERMK MAT_lambert2 가 여럿).
  이전 문제 [확실]: 손으로 맞춘 비 (줄기 48, 색 1.7) 를 모든 종류에 썼고 (플랑크톤·먼지 레벨도 비), PTC VU 경로는 방향 레코드(q1)가 비어 화면이 하얗게 덮였다.
  `GOW_PTCVU=off` 면 근사 이미터 (비는 원작 속도·크기로 맞춤). 확인: ATHN01A/D 가는 사선 빗줄기, DEST01 옅은 모래 안개, PAND00C 떠다니는 점.
- FXC 이미터: 서브타입 1 원뿔 / 2 전방향은 원작 식, 5–9 볼륨은 로컬 배율 상자 [추정], 3/4 표면·커브는 지오메트리 미해석으로 생략.
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
- 비 볼륨 상자 판정, 눈/먼지/플랑크톤 모양, 표면·커브 이미터, 칼 궤적(0xB), 필드. (~~PTC 크기 단위~~ 16 §2.4)
- SCR_Mirror (반사 바닥 — OLYMP02 유리 바닥 아래가 배경 안개색으로 보임, 43 §4), LensFlare, GhostTrail, RopeSkinned, cloth 미구현.
- 반투명 패스 안의 정렬 (원작 정렬 단위 미확인, 지금은 인스턴스 순서). 스크립트 Animator 없이 보이는 관절 오브젝트 (PAND03A `godmcdog2`) 는 바인드 자세. LGT 동적 조명 미구현 (캐릭터용).
- 파티클·물은 레벨 렌더 안에서 그려서 이후에 그리는 캐릭터가 덮는다 (GameScene 의 늦은 효과 패스 훅 필요).
