# 16. 환경 / 이펙트 포맷 (환경 스크립트, 머티리얼 애니메이션, 파티클)

도구: `devple.gow.tools.fx` 패키지 / `fx:*` 명령. 구현은 [31 문서](31-impl-environment-fx.md).

```bash
./gradlew -q :tools:run --args="fx:env     gow-rom/ATHN01A.WAD"          # 환경 스크립트 해석값 (Fog/LayeredFog/FarClip/Sky/Weather/Water …)
./gradlew -q :tools:run --args="fx:survey  gow-rom"                      # 전 WAD 환경 스크립트 target 별 분포
./gradlew -q :tools:run --args="fx:matanim gow-rom/ATHN07.WAD"           # 머티리얼 ANM (플립북 / UV 스크롤 / 색 트랙)
./gradlew -q :tools:run --args="fx:ptc     gow-rom/PAND01A.WAD [필터]"   # FXC_ 이미터·필드 / PTC_ 모양 연결
./gradlew -q :tools:run --args="fx:parts   gow-rom/ATHN01A.WAD gonightsky" # 인스턴스 서브메시별 머티리얼 / AABB
```

| 클래스 | 역할 |
|-------|------|
| `EnvScript` | 환경 SCR 리소스 (0x10004) 헤더/본문, 소유 리소스 |
| `EnvParams` | target 별 해석 (`Fog`, `LayeredFog`, `FarClip`, `Sky`, `Weather`, `Water`, 나머지 `Raw`) |
| `MaterialAnim` | MAT 그룹의 ANM_ → 플립북 프레임 / UV 오프셋 / float 트랙 |
| `FxResources` | FXC_ 이미터 (1..10) · 필드 (0xC) · PTC_ 모양 + 머티리얼 + 색 곡선 휴리스틱 |

신뢰도 표기: [확실] = 실행 파일 코드와 데이터 일치, [추정] = 정황/데이터 패턴.
**단위**: 스크립트·이펙트의 길이/속도는 ×16 이 월드 단위다 (SCR_FarClip 값 ×16 → 뷰포트 far, Weather/Water/FXC 코드의 `0x41800000` 곱) [확실].
뷰포트 기본값 (`0x168DC8`): near −10 (+0x384), far −10000 (+0x388), CentreZ 524287 (+0x3A8).

## 1. 환경 스크립트 (SCR, 매직 0x10004)

07 문서 §5.1 의 0x24 헤더 뒤 본문. 헤더 0x20 의 u16 이 **bit4(0x10) = 인스턴스 값** [확실: 배치로 확인]:
- 0x12 형식: `GROUP{ go인스턴스, SCR }` 안. 값 블록 그대로 = C++ 생성자가 받는 데이터 (`scr+0x50 + scr+0x58`). **실제로 쓰이는 값**.
- 0x02 형식: MDL 그룹 안 템플릿. `u32 n, u32 offset[n]` 뒤에 같은 배치의 기본값 (+ 소유 오브젝트 이름 등 추가 필드).

생성 함수(등록 표 06 문서 §6)가 객체를 할당하고 생성자에 본문을 넘긴다:

| target | 생성 / 생성자 | 객체 크기 | 개수 (인스턴스) |
|--------|--------------|----------|----------------|
| SCR_FarClip | 0x2466F0 / 0x20CDA0 | 0xC | 9 |
| SCR_Fog | 0x2467A8 / 0x20D2C0 (적용 0x20D3D0) | 0x50 | 85 |
| SCR_LayeredFog | 0x246860 / 0x20D4F0 (적용 0x20D650) | 0x7C | 41 |
| SCR_Water | 0x246CE8 / 0x2192B8 (갱신 0x219DE8) | 0x3C | 31 |
| SCR_Weather | 0x246DA8 / 0x21CBE0 (갱신 0x21CF80) | 0xA0 | 25 |
| SCR_Sky | 0x246B30 / 0x218920 | – | 81 |
| SCR_Mirror | 0x2464C8 / 0x129820 | 0x14 | 56 |

### 1.1 SCR_FarClip [확실]
`[0x00] f32` → 값이 바뀌면 뷰포트 +0x388 = 값×16 (The_Viewport, The_Viewport2 둘 다, 0x20CDE8 → 0x20CD40). 음수 = −Z 방향.
HDES01A–C −175 (far 2800), HDES01D/E −250 (4000), ATHN01E −800, PAND00A −4000, PAND02B −1000.

### 1.2 SCR_Fog (본문 0x44 바이트 → 객체 +0x0C)
| 오프셋 | 타입 | 필드 | 신뢰도 |
|-------|------|------|-------|
| 0x00 | f32[4] | 색 RGB + Fog_A (최대 불투명도). 튜닝 키 `Fog_R/G/B/A` (0x125888) | 확실 |
| 0x10 | s32 | **CentreZ** (튜닝 키 "CentreZ") → 안개 관리자 +0x30 → 뷰포트 +0x3A8 | 확실 |
| 0x14 / 0x18 | f32 | 안개 텍스처 스크롤 U / V (초당, 코드는 ×1/60 프레임당) | 확실 |
| 0x1C | f32 | 안개 텍스처 회전 (도/초 → 라디안/프레임) | 확실 |
| 0x20 | f32 | 안개 텍스처 사각형 배율 (화면 중심 기준, 0x12936C) | 추정 |
| 0x24 | char[24] | 머티리얼 이름: `"MAT_"+이름` 의 첫 레이어 텍스처, `"FOG"` 면 `TXR_FOG` (0x20CED8) | 확실 |
| 0x3C | s32 | 블렌드 모드: 0 → GS 0x54 `(Cs−Cd)·Ad+Cd`, 1 → 0x58 `Cs·Ad+Cd`, 2 → 0x52 `Cd−Cs·Ad` (0x125B60) | 확실 |
| 0x40 | s32 | BackPlane_A (0..128, −1 = 끔, 범위 밖이면 생성자가 −1) | 필드 확실 / 의미 추정 |

안개 관리자 (뷰포트 +0x80): +0x00/+0x10/+0x20 색, +0x30 CentreZ, +0x34 블렌드, +0x38 BackPlane, +0x40 스크롤/회전/배율, +0x50..0x58 층 위치,
+0x80 텍스처, +0x88 플래그 (bit0 변경, bit1 = 층 안개). 렌더 0x1281C8: 층 안개이거나 Fog_A > 0 이면 켬.

**깊이 → 안개 알파.** 투영 0x169390 은 GL 식 (`m22 = (n+f)/(n−f)`, `m32 = 2nf/(f−n)`), 화면 변환 0x1694F0 은
`z_s = z_ndc·(−C·2^−121) + (2^−101 + C·2^−121)` 를 float 비트로 쓰는 정수화 → **GS Z = 8·C·(1 − z_ndc) = 16·C·n·(f − d)/((f − n)·d)** [확실]
(d = 시선 깊이, n = 10, C 기본 2^19 → Z(n) ≈ 2^23). 안개 패스는 Z 버퍼를 CT32 텍스처로 읽어(TEX0 PSM 0) 32px 띠로 대상 알파에 Z 비트를 옮긴 뒤
안개색 전체 화면 스프라이트를 Ad 로 블렌드한다 [추정: 패킷 구성으로 판단, 램프 기준 비트 미확정]. 결과적으로 Z 에 선형 → 거리에 대해
`alpha(d) = Fog_A · clamp(1 − Z(d)/Zref)` ≈ `Fog_A · (1 − D0/d)`, D0 ∝ CentreZ. 기준 Zref 는 미확정 → 엔진은 D0 = CentreZ·10/1024 로 보정 [추정].

| 레벨 | 색 RGB | Fog_A | CentreZ | 스크롤 / 회전 / 배율 | 블렌드 / BackPlane |
|------|--------|-------|---------|---------------------|-------------------|
| PAND01A goholefog1 | (.45,.375,.25) | .8 | 160000 | .015, −.115, 10°/s, 1 | 0 / −1 |
| PAND01A goholefog2 | (.26,.195,.135) | .725 | 70000 | 〃 | 0 / −1 |
| PAND01A goholefog3 | (.4,.36,.22) | 0 | 50000 | 〃 | 0 / −1 |
| ATHN07 | (.12,.16,.2) | .6 | 37136 | .17, 0, 0, .6 | 0 / 100 |

한 레벨에 SCR_Fog 가 여럿이면 소유 GO(구멍 위치 `goholefog*`)마다 다르고 ESC 가 전환하는 것으로 보인다 [추정].

### 1.3 SCR_LayeredFog (본문 0x70 바이트)
| 오프셋 | 필드 | 신뢰도 |
|-------|------|-------|
| 0x00 / 0x10 / 0x20 | 색 키 c0, c1, c2 (RGBA, A = 그 키의 안개 불투명도) → 0x125A48 | 확실 (3색 전달) |
| 0x30 / 0x34 / 0x38 | 오름차순 t0 < t1 < t2: 화면 높이 비율 위치 (0x126828/0x1208C8 이 띠 y = y0 + (y1−y0)·t 보간) | 추정 (높음) |
| 0x3C | CentreZ | 확실 |
| 0x40 / 0x44 / 0x48 / 0x4C | 스크롤 U, V, 회전 도/초, 배율 (Fog 와 같음) | 확실 |
| 0x50 | char[24] 머티리얼 | 확실 |
| 0x68 / 0x6C | 블렌드 모드 / BackPlane_A | 확실 |

색: 화면 위→아래 t 에서 t<t0 = c0, 키 사이 선형, t>t2 = c2. 알파 = 키 알파 × 깊이 알파.
ATHN01A: c0 (.03,.045,.025,0) c1 (.14,.125,.115,0) c2 (.285,.325,.28,.56), t (.445,.475,.54), CentreZ 30000, 스크롤 −.335/.035, .9°/s, 배율 2.4, BackPlane 95 → 하늘/화면 위는 맑고 지평선 아래 회녹색 바다 안개.
HDES01A: c0 (.605,.165,.12,.72) c1 (.985,.385,.255,1) c2 (.375,0,0,1), t (.265,.46,.58), CentreZ 62500, BackPlane 104 → 붉은/주황 빛.

**BackPlane_A**: 지오메트리가 없는 배경(하늘, Z 클리어 값) 화소는 깊이 대신 BackPlane_A/128 을 안개 알파로 쓴다 [추정] (ATHN01A .74, ATHN07 .78, HDES01A .81).
**텍스처 레이어**: 안개색 × 2·텍스처 (GS MODULATE), 화면 공간 사각형 UV += 스크롤·t, 회전 rot·t, 배율 scale (ATHN01A `MAT_defFogMat` → `TXR_suals01A_defFogText`) [스크롤/회전 확실, 배율 추정].

### 1.4 SCR_Sky [확실]
0x02 형식만 의미가 있다: `u32 1, u32 8, char[] 오브젝트 이름` ("nightSky", HDES01A "group926"). 생성자가 스크립트 플래그 |3, 매 프레임 0x218848 이
소유 GO 위치(+0x50)를 카메라로 옮기고 모델을 갱신 → **하늘 오브젝트 관절 0 이 카메라 위치에 온다** (AABB 중심이 아님).
ATHN01A `nightSky` 는 반지름 ~1024 의 **미니어처 세계**: 바다면 `lambert24` y=432 (2048×2048), 먼 바위 `lambert34` y≈416, 원경 배 `owResBoat` y≈428,
하늘 돔 `lambert9` y 212..869, 구름 반사 `lambert28` (`TXR_fxCloudReflection_1`), 소용돌이 `vortex_lambert406`. 관절 0 = (0,448,0) → 바다면이 눈높이 16 아래.
ATHN01A 의 "바다 표면" 은 별도 물 객체가 아니라 이 하늘 미니어처다 (SCR_Water 없음).

### 1.5 SCR_Weather
| 오프셋 | 필드 | 신뢰도 |
|-------|------|-------|
| 0x00 | s32 종류 → 표 0x29F388 (0x18 바이트: s16, 초기화 fn, 갱신 fn, GO 이름, f32 셀, u32 카메라 추적) | 확실 |
| 0x04..0x0C | f32 vec3 바람/표류 속도 (×16). 초기화 0x21AFD8 이 기본 (0,−10,0) (0x21AD48) 과 합쳐 정규화 → 줄기 방향 | 추정 |
| 0x10..0x18 | f32 vec3 볼륨 상자 전체 크기 → 반폭 = v×16×0.5, 소유 GO 위치 중심 | 확실 |

종류: 0 gorain (셀 20), 1 gosnow (20), 2 goplankton (14), 3 godust (20), 4 goembers (20), 5 gogroundfog (10, 카메라 비추적).
동작 (0x21CF80, 0x21AD60): 종류 GO 의 파티클(`gorain` → `PTC_rain`, `MAT_nalRain_M01sfxRain3`)을 **셀×16 정육면체**(비 ±160)에 PTC 최대 개수(1000)만큼 뿌리고,
정육면체 중심 = 카메라 + 시선·(셀×16/2 − near). 매 프레임 속도·dt·16 이동 후 정육면체로 감싼다(mod). 정육면체가 볼륨 상자(±100 여유) 안일 때만 그린다.
값: ATHN01A 0 (12,12,−12) (70,50,63); ATHN01C (12,12,12) (105,50,60); DEST01 먼지 (6,0,−12) (250,9,360); PAND00C/D·PAND02F.. 플랑크톤 (속도 0).

### 1.6 SCR_Water — 파문 높이장 시뮬레이션 [확실, 표시한 것 제외]
| 오프셋 | 필드 |
|-------|------|
| 0x00 / 0x04 | f32 크기 X, Z (×16) |
| 0x08 | f32 격자 간격 (×16, 보통 0.5 = 8). nx = X/cell+1, nz = Z/cell+1, 소유 관절 중심 (0x219D88) |
| 0x0C | s32 플래그 0/1 (미상) |
| 0x10 | char[24] 머티리얼 ("MAT_%s": REFLENVM, REFLENV2, REFLENVMB, REFLENVNEW — 반사 환경맵) |
| 0x28 | s32 1 = 머티리얼 프레임 사용 [추정] |
| 0x2C / 0x30 | f32 물방울 영역 오프셋 X/Z (×16) |
| 0x34 / 0x38 | f32 물방울 영역 크기 X/Z (×16) [원점 추정] |
| 0x3C | s32 초당 물방울 수 (×1/60 누적) |
| 0x40 | f32 물방울 충격 세기 (0x21A608) |

갱신 0x21A758: `k = (2·dt/cell)²` (dt = 1/60), `h' = (k·(N+S+E+W) + (2−4k)·h − h_prev)·0.99`. 물체가 물에 닿으면 구 질의(0x11E8D0, 콜백 0x219B78)로 파문.
영웅 거리 감쇠 `a = clamp((R + 480 − 거리)/160)`, R = √(nx²+nz²)·8, a = 0 이면 정지 (0x219A90). 메시 재생성 0x21A888.
PAND01A gowaterholeloc1: 11×15, cell .5 (23×31), REFLENVM, 물방울 20×15 영역 30/s 세기 .2.

### 1.7 SCR_Mirror / SCR_AresSky
- SCR_Mirror: `[0] u32, [4] u32` = (1,0)/(0,1)/(0,0). 소유 GO 모델의 렌더 플래그 bit 61 을 지우고(일반 그리기 숨김) 뷰 슬롯 2개(표 0x29CD48)마다
  거울 렌더러 0x12AA18(model, a, b) 생성 → 반사 바닥/평면 거울. a, b 의미 미상 (캐릭터/월드 반사 플래그 추정).
- SCR_AresSky: 객체 {f32 −1, s32 −1}, 갱신 0x2189A8 (2개뿐, 미해석).

## 2. 파티클 (FXC_ 이미터 / PTC_ 모양)

**SEM_ (0x1A) 은 사운드 이미터** (`SEM_SNDxxx`, 224 바이트) — 파티클이 아니다. 파티클 = FXC_ (EffectsServer, 0x1E) + PTC_ (renParticleSvr, 0x13).
FXC_ 서브타입은 Maya dynamics 노드: 1–10 이미터, 0xB 칼 궤적(`MAT_trail1`), 0xC 필드, 0xD 방출 표면/커브.

| 매직 | 개수 | 크기 | 의미 |
|------|------|------|------|
| 0x13 PTC_ | 8951 | 392–616 | 파티클 모양 (particleShape) |
| 0x1001E..0x9001E FXC_ | 4098 / 2203 / 257 / 660 / 1209 / 292 / 462 / 27 | 228 | 이미터 |
| 0xA001E / 0xB001E | 1 / 5 | 228 / 112 | 관절 이미터 / 칼 궤적 |
| 0xC001E / 0xD001E | 1315 / 913 | 180 / 136 | 필드 / 방출 지오메트리 |

**레벨 배치** [확실]: 오브젝트(0x40001) 그룹이 PTC/FXC 를 참조 → 인스턴스가 월드 변환. 이미터 월드 = FXC 로컬 행렬(+0x10) · 관절 월드 · 인스턴스.
FXC 는 **+0x54 모양 이름 문자열**로 PTC 를 찾는다 (둘 다 `"TfirepartShape"`). PTC 의 머티리얼 = PTC 바로 뒤 같은 그룹의 MAT_ 참조.
노드 헤더 +0x08 `s16 a, s16 b` (0x12FEE0) = 관절 번호, 링크 번호(−1 없음) [추정]. 비는 예외: `gorain` 은 PTC_rain 만 갖고 SCR_Weather 가 만든다.

### 2.1 FXC_ 이미터 (228 바이트, def 생성자 0x121BA8, 인스턴스 0x121CE0)
| 오프셋 | 필드 | 신뢰도 |
|-------|------|-------|
| 0x10 | f32[16] 로컬 행렬 (이동 0x40) | 확실 |
| 0x54 / 0x6C | char[24] 파티클 모양 이름 / 표면·커브 모양 이름 (서브타입 3, 4) | 확실 |
| P+0x00..0x08 (파일 0x84) | 방향 (0x122C88 이 +Y 를 이 방향으로 회전) | 확실 |
| P+0x0C | 원뿔 퍼짐: θ = rand·spread·π/2 | 확실 |
| P+0x10 / 0x14 | 속력 / 속력 랜덤: v = (speed − sr/2 + rand·sr)·16 | 확실 |
| P+0x18 / 0x1C | 최소 / 최대 생성 거리 ×16 | 확실 |
| P+0x20 | 초당 생성 수 (프레임당 rate/60·lod + 누적, 0x122B00) | 확실 |
| P+0x24 / 0x28 | 접선 / 법선 속력 (표면·커브) | 추정 |
| P+0x38 / 0x3C | volumeSweep (2π) / sectionRadius (0.5) | 확실 |
| P+0x40..0x54 | awayFromCenter, awayFromAxis, alongAxis, aroundAxis, randomDirection, directionalSpeed | 확실 (읽기) / 이름은 Maya |
| 0xDC / 0xE0 | LOD near / far: 생성률 × (far − d/16)/(far − near), −1 = 끔 (0x122910) | 확실 |

서브타입 → 갱신: 1 방향 원뿔 0x261C00, 2 전방향 0x261538, 3 표면 0x25E5C0, 4 커브 0x25EC88, 5 상자, 6 구, 7 원기둥, 8 원뿔 볼륨, 9 토러스 (5–9 추정),
0xA 관절-스킨 0x25D360, 0xC 필드 0x25E530. 서브타입 1 생성 (0x122E78) [확실]:
```
θ = rand·spread·π/2, φ = rand·2π, d = (sinφ·sinθ, cosθ, cosφ·sinθ)       // +Y 원뿔 → dir 로 회전
pos = EmitterWorld · (d · lerp(minDist, maxDist, rand) · 16)
vel = EmitterRot · (d · (speed − sr/2 + rand·sr) · 16)
```

### 2.2 FXC_ 필드 (0xC001E, 180 바이트) [이름 추정]
0x54 u32 종류 (3 중력 1184, 6 균일 125, 7 소용돌이 4, 36 뉴턴 2), 0x58 크기, 0x5C 방향, 0x68 감쇠, 0x80/0x84 최소/최대 거리 (−1 무한).
예: `FXC_KWgrav` −20·(0,0,1) 로컬, `FXC_uniformField1` 17·(1,−1,0).

### 2.3 PTC_ 파티클 모양 (def 생성자 0x15AD60, 인스턴스 0x15B8D0)
| 오프셋 | 필드 | 신뢰도 |
|-------|------|-------|
| 0x54 | char[24] 모양 이름 (FXC 연결) | 확실 |
| 0x6C | s32 최대 개수 (비 1000, −1 = 동적) | 확실 (비) |
| 0x70 | f32 수명 (초) | 확실 (복사) |
| 0x74 | f32 크기 (16 / 48 / 80 / 800 / 1600 / 4800, 음수 = 오브/불꽃) | 추정 |
| 0x78 / 0x7C | LOD 페이드 near / far (−1 끔) | 추정 |
| 0x80 | 플래그 → 렌더 모드: &0x4040 → 6 (줄기), &0x2000 → 5, flags&3: 0→1, 1→3, 2→(0x40000 ? 4 : 2), 3→0 | 확실 (코드) |
| 0x84 | 파티클 레코드 크기 (qword) | 추정 |
| 0x8C | u8[12] 속성 배치 (스트림 &3 + 바이트 오프셋): [0] 출생 시각, [1] 위치, [2] 속도, [6] 비 줄기 방향 | 확실 |
| 0x98.. | VU1 블록: +4 프로그램 종류 (불 3, 큰 불 1, 비 8, 파문 9, 조각 0), +8 상수 크기, +0x10..0x8F u16 색인 표 8개, 이후 상수 qword | 확실 (구조) |

VU 상수 = 나이에 대한 다항식 계수 (VU1 마이크로코드 미해석). 관찰 패턴 [추정]:
RGB 0..128 + 선형 변화량 (Tfire c0 (84.4,53.5,25.3) Δ (−31.7,−40.9,−22.6); rain (134.9,124.2,102)),
알파 = (A·u + B·u²)/128, B ≈ −2A (Tfire 2966/−5932, rain 380.7/−761.4, Tglow 181.6/−269). `FxResources.Shape.colorCurve()` 가 이 패턴을 찾는다.

예 (PAND01A 횃불 `firetorch01`, 관절 0): FXC_Tfiremit 서브타입 1, 로컬 (−0.32, 4.28, −0.26), 배율 3.316, 방향 (0,1,0), 퍼짐 0.1, 속력 0.25·16 = 4/s, 35/s
→ PTC_Tfirepart 수명 1.0, 크기 80, LOD 15/30, 플래그 0x691 (모드 3), `MAT_fire_M01firepart3`. Tglow (1.35 s, MAT_lambert17), TSpark (MAT_lambert18) 동반.
오브 빛 (R_PERMK `healthorb`): FXC_HOgemit 서브타입 6 (구), 50/s, PTC_HOgpart 수명 0.3, 크기 −16, MAT_pticleMat. **HDES01A 에는 PTC/FXC 가 없다** (용암 빛은 머티리얼).

## 3. 머티리얼 부가 효과

### 3.1 머티리얼 ANM [확실]
MAT 생성 0x1537C8 이 MAT 그룹 자식을 순회: `ANM_` (서버 3) → mat+0x48, `SCR` 최대 2개 → mat+0x54. ANM 은 05 문서 레이아웃 (세트 → 그룹 0x40 → 액트).
액트 +0x64 `StateDescr[타입수]` 20B (`u16, u16 트랙수, u32, u32 오프셋, f32 키간격 1/30, u32`), 트랙 12B 는 05 문서 항목 (비트맵 헤더 = 플래그 bit1).
액트 플래그 0x41 = 반복 자동 재생, 0x42 = 스크립트가 켜는 1회 (문 페이드 `M01DoorFade`).

| typeId | p1 | 대상 | 개수 |
|--------|----|------|------|
| 9 | 0 | 레이어 0 GFX 플립북 프레임 (u32 키) | 25 |
| 8 | 0x80+L | 레이어 L UV 오프셋 (스트림 0 = U, 1 = V) | ~1200 트랙 |
| 3 | 0 | MAT 헤더 0x08 부터 f32 (스트림 0–2 색, 8 = 0x28 p28 불투명도) | ~245 |
| 3 | L+1 | 레이어 L 0x28 부터 (0–3 rgba, 4 p38, 5 UV 회전 라디안 [추정]) | ~480 |

### 3.2 플립북 / UV 스크롤
- GFX 생성 0x14C588 이 세로 스트립을 frames 개로 나눔, gfx+0x22 현재 프레임, 0x14CDD8 이 바뀌면 0x14C9F0 으로 업로드 [확실].
  프레임 = `keys[floor(t/(1/30)) mod keyCount]` (ATHN07 `MAT_lambert250` plantGrass200: 20키 0,0,1,3..19; `lambert254` 40키). ANM 없으면 프레임 0. TXR/MAT 필드는 무관.
- UV 스크롤은 type 8 트랙뿐 (flags2 무관). 키 사이 선형, 반복 구간 끝값이 정수라 이음매 없음. `uv' = uv + (U(t), V(t))` (부호 추정).
  ATHN01A `M01sfxWaterShimmer` V 1→2/1.67 s, `sfxCloudReflection` U 0→−1, V 1→0 /4 s, `lambert24`(하늘 바다) U 0→2/33 s; HDES01A `lambert18` U 0→2, V 1→−1 /23.3 s;
  PAND01A `M01sfxWaterFall` U 0→1, V 1→−3 /6.3 s.

### 3.3 블렌드 / 정렬 / 레이어 [확실, 표시 제외]
머티리얼 렌더 0x165998: `ALPHA_1 = (rf>>24) | 0x80<<32` (FIX 0x80), `ZBUF.ZMSK = rf & 0x80000` (bit19 = 깊이 쓰기 끔), bit7 = 텍스처,
**TEST = 0x5360B: 알파 테스트 A ≥ 0x60 (0.75), 실패 화소는 RGB 만 쓰고 Z 는 안 씀**, Z GEQUAL. 텍스처 없는 레이어는 0x50000 (알파 테스트 없음).

정렬(반투명) 플래그 mat+0x3C bit 0x8000 (0x1537C8) — 다음 중 하나: p28 ≠ 1, 레이어 0 p38 ≠ 1, 레이어 0 텍스처 런타임 bit 0x40000 (TXR flags bit18 추정), 블렌드 0x48/0x42.
**그 외(대부분의 0x44)는 불투명 + 알파 테스트**다. (기존 엔진은 0x44 를 전부 반투명 패스로 그려 정렬 오류·검은 상자가 생겼다.)

| 바이트 | GS 식 | GL | 용도 |
|-------|-------|----|------|
| 0x44 | (Cs−Cd)·As+Cd | ADD / SRC_ALPHA, ONE_MINUS_SRC_ALPHA | 일반 |
| 0x48 | Cs·As+Cd | ADD / SRC_ALPHA, ONE | 불, 빛줄기 |
| 0x42 | Cd−Cs·As | REVERSE_SUBTRACT / SRC_ALPHA, ONE | 연기, 어둡게 |
| 0x01 | (Cd−Cs)·As+Cs | ADD / ONE_MINUS_SRC_ALPHA, SRC_ALPHA | 2·3번째 환경/반사 레이어 |

- 실효 As ≈ 텍스처 α × 정점 α × p28 × p38 (0x154410).
- 멀티 레이어: 배치마다 UV/정점색 unpack 이 하나뿐 → **모든 레이어가 같은 정점·UV·정점색으로 레이어 순서대로 추가 패스** (VIF 덤프 DEST00 `lambert180`, ATHN07 `phong14`).
  법선 있는 메시의 phong 환경 레이어 UV 는 VU1 이 법선으로 만드는 구면 맵 [추정].
- renderFlags 하위 → VU 플래그: bit2 (0x04) 조명, bit1 (0x02) 조명+환경, 아니면 bit7 텍스처 UV. 최종 VU 프로그램 = `(vuFlags|0x20?) & mesh+0x14 | mesh+0x10`
  (법선 없는 레벨 메시는 0x…25 로 조명/환경이 꺼진다).
- 미상 비트: rf bit16 (defFogMat/skyline/stencil 만 0 → 안개 무시 후보), rf 0x400 (양면 후보), flags2 하위 5비트 열거 (0 → 4 강제, 0x1534BC), 0x100 / 0x80000000 / 0x10000000.
- 색 배율: 텍스처 없는 레이어는 rgba 2.0 + 헤더 0.4 → 중립 = `header.rgb × layer.rgb / 0.8` [추정] (텍스처 레이어는 layer.rgb).

### 3.4 HDES01A 용암 빛
대부분 **베이크 정점색 과포화** (GS MODULATE 2배): `lambert9` 평균 (146,87,81) 60% 가 0x80 초과, `lambert13` (154,91,83), `lambert318` (255,255,255).
`lambert18` 레이어 rgba 2.5 + UV 스크롤, `lambert431/432` 하늘 감산 레이어. 가산 용암 레이어는 없다 → `tex · min(vc/128, 2) · layer.rgb` 로 충분.

### 3.5 정점색 체인 [확실, DMA 덤프]
메시 오브젝트 체인 c 는 관절 `jointMaps[c]` 에 놓이는 별도 복사본. 지오메트리/UV REF 는 공유, **정점색 REF 만 체인마다 다르다**
(PAND01A `holeBeam09` 체인 0 @6C0, 체인 1 @740). 체인 c 의 색 = DMA 태그 `off + 0x20 + c·dmaTagCount·16` 부터 이어 붙인 스트림의 V4-8 unpack.
인스턴스별 선택 로직은 없다. PAND01A 다중 체인 43개 중 19개가 체인 간 20 이상 차이 (`holeBeam10` 676 체인 10–56, `alcoveTorches` 40–101).
**PAND01A 의 어두운 방은 체인 문제가 아니다**: 체인별 평균 80.7 vs 체인 0 고정 81.6. `cave` (8,6,4), `AxeAlcWall` (3,3,4) 처럼 베이크 색 자체가 어둡고,
법선이 없어 GS 가 실시간 조명도 못 한다 → 원작의 밝기 인상은 SCR_Fog (안개색 (.45,.375,.25) α .8) 몫이 크다 [추정].

### 3.6 LGT_ (매직 6, 0x58 바이트) [배치 확실, 의미 추정]
0x08 u32 종류 (0 주변, 2 방향, 6 방향+위치), 0x0C vec4 위치, 0x1C vec3 방향, 0x2C rgb, 0x38 세기 A, 0x3C 반경 A, 0x40 세기 B, 0x44 반경 B, 0x48 플래그.
PAND01A `PSambLite` .751 회색 .5 / 8000, `PSFirelite*` (.777,.284,0) .75, 반경 36.8/86.4. **법선 있는 메시(캐릭터·소품)만** 영향 → 레벨 정적 메시엔 무관.

## 4. 미해결
- 안개 Z→알파 램프의 기준 비트/곡선 (0x127918, 0x1275E0 의 CLUT/FBMSK 구성) → 현재 D0 = CentreZ·10/1024 보정값.
- LayeredFog t 가 화면 높이인지 지평선 기준인지 (카메라 피치 반영 여부).
- PTC VU1 상수의 정확한 의미 (크기/회전/색 표 8개, 프로그램 표 0x29CD58), PTC +0x74 크기 단위, 음수 개수/크기.
- FXC 0xD 방출 지오메트리 포맷 (표면/커브 이미터), 노드 헤더, 필드 단위.
- SCR_Water 물방울 원점, 0x0C 플래그; SCR_Mirror a/b; SCR_AresSky; SCR_LensFlare / GhostTrail / RopeSkinned / cloth / ThingOnASpring 본문.
- renderFlags bit16 / 0x400, flags2 의미; 한 레벨 여러 SCR_Fog 의 전환 조건 (ESC).
