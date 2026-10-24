# 04. 모델 / 메시 포맷 (MDL, 메시, VIF)

도구: `devple.gow.tools.model` 패키지 / `model:*` 명령

```bash
./gradlew -q :tools:run --args="model:info   gow-rom/R_HERO0.WAD"
./gradlew -q :tools:run --args="model:export gow-rom/R_HERO0.WAD work/models/R_HERO0 [--lod N] [--colors] [--preview]"
./gradlew -q :tools:run --args="model:render gow-rom/R_HERO0.WAD MDL_hero work/models/hero.png [--lod N]"
./gradlew -q :tools:run --args="model:batch  gow-rom [outDir] [--preview]"    # 전체 WAD 일괄 + 실패 통계
```

| 클래스 | 역할 |
|-------|------|
| `GowModel` | MDL_(0x2000F) + 같은 WAD 그룹의 MAT 참조/메시 연결, MAT 안의 TXR 이름 추출 |
| `GowMesh` | 메시 리소스(0x1000F): 헤더 → 파트 → 그룹(LOD) → 오브젝트 |
| `MeshObject` | 오브젝트 헤더, DMA 체인을 VIF 스트림으로 펼침, 배치 디코드 |
| `VifParser` / `VifUnpack` | VIF1 코드 해석 (UNPACK 전 포맷, STCYCL/STMOD/STROW, MSCAL 단위 배치 분할) |
| `VertexBatch` | UNPACK → 위치/UV/법선/색, ADC 플래그로 스트립 → 삼각형 |
| `TriMesh` / `ObjWriter` | 법선 기준 감김 보정, OBJ + MTL 출력 |
| `PreviewRenderer` | 검증용 소프트웨어 래스터라이저 (정면/측면/3/4 뷰 PNG) |
| `MshMesh` | MESH_EXTRA(0x70) 의 `MSH_` float 메시 |

## 1. 리소스 관계

```
GROUP{
  RES MDL_hero      0x0002000F  (0x48 바이트 모델 헤더)
  RES MAT_xxx       (size 0 참조) ×materialCount   ← 오브젝트 materialIndex 순서
  [GROUP{ SCR_... }GROUP]                          ← 하늘 등 일부 모델은 스크립트 그룹이 끼어 있음
  RES hero_0        0x0001000F  (메시 본체)  [hero_1 ...]
}GROUP
GROUP{ RES hero (CXT 0x40001, 스켈레톤 포함)  RES ANM_hero (참조)  RES MDL_hero (참조) ... }GROUP
```
- 메시는 MDL 그룹 안에 있다. MAT 참조 순서가 오브젝트의 `materialIndex` 와 일치한다 (히어로: 0=`MAT_ClothCollisionMat` … 6).
- MAT_ 데이터 0x48 부근에 `TXR_` 이름이 문자열로 들어 있어 MTL `map_Kd <TXR>.png` 로 연결한다 (MAT 상세는 03 문서).
- 레벨 지오메트리도 같은 구조의 MDL 이며 정점이 이미 월드 좌표다 (`MDL_Arena`, `MDL_Hull1` …).

## 2. MDL_ (0x2000F, 0x48 바이트)

| 오프셋 | 타입 | 필드 |
|-------|------|------|
| 0x00 | u32 | magic 0x0002000F |
| 0x08 | f32×3 | -2^31, 2^31, -2^31 (자리표시 값) |
| 0x14 | u32 | materialCount (그룹 안 MAT 참조 수와 일치) |
| 0x18 | u32 | meshCount (그룹 안 0x1000F 수와 일치, 예: `flagGrp017_0/_1`) |
| 0x1C | u32 | jointCount (히어로 111, 미노타우로스 55, 정적 1) |
| 0x28 | u64 | 렌더 플래그 (0x156820 → 모델 +0xC0): bit 57 = 거울 뷰 전용 (가짜 반사 대리 메시), bit 61 = 거울에도 비침, bit 56 = 글로우/플레어 [추정] — 43 §9 |
| 0x30 | u64 | 대부분 0xFFFFFFFF_FFFFE7FB (bit 56/57 모델은 그 비트를 뺀 값) [추정: 마스크] |
| 0x20~ | | 그 밖 미확정. float 와 미초기화 값이 섞여 있음 |

## 3. 메시 리소스 (0x1000F)

### 3.1 헤더
| 오프셋 | 타입 | 필드 |
|-------|------|------|
| 0x00 | u32 | magic 0x0001000F |
| 0x04 | u32 | 데이터 크기 - 0x50 |
| 0x08 | u32 | partCount |
| 0x0C, 0x10, 0x14 | u32 | 미확정 (정점/스트립 총계 계열로 추정) |
| 0x18 | u32 | recordCount — 아래 20바이트 레코드 수 |
| 0x20 | u32 | 0x10(정적) / 0x24(스키닝) 등 |
| 0x28 | u32 | 스키닝 모델은 관절 수(히어로 111), 정적 모델은 partCount+1 → "매트릭스 노드 수" 로 추정 |
| 0x2C | u32 | **혼합 노드 수** (대부분 0) — 아래 혼합 노드 표 [확실: 데이터, 2026-10-24 43 §22] |
| 0x30 | u32 | 혼합 노드 표 단어 수 (노드마다 1 + 2·n) |
| 0x34 | u32 | **접촉 관절** — 모델 부품 +0xE4 (메시 목록) [0] +0x34: 스크립트 볼륨 접촉점 (0x1ADD74 → 0x1A6308) · 카메라 선택점 (0x1EEFA0) 이 이 관절의 월드 위치. hero_0 = 1 "pelvis" (바인드 높이 21.65) [확실: 코드 · 데이터] |
| 0x38 | char[] | `"main"` |
| 0x3C~0x4F | | 나머지는 툴이 초기화하지 않은 쓰레기 값(경로 문자열 조각, 포인터 등) |
| 0x50 | u32[partCount] | 파트 오프셋 (리소스 데이터 기준) |
| 이후 | 20B × recordCount | `{u32 0xFFFF 또는 인덱스, f32×4}` — 바운딩/컬링 트리로 추정, 마지막 레코드 = 모델 경계 구 (0x156884) |
| 이후 | 혼합 노드 표 | 노드마다 `{u32 n, (u32 관절, f32 가중) × n}` (+0x2C 개, 단어 수 +0x30) — 아래 3.5 |

### 3.2 파트
| 오프셋 | 타입 | 필드 |
|-------|------|------|
| 0x00 | u16 | jointRefCount |
| 0x02 | u16 | groupCount (= LOD 수) |
| 0x04 | u32[groupCount] | 그룹 오프셋 (파트 기준) |
| 이후 | u32[jointRefCount] | 파트가 붙는 관절 |

### 3.3 그룹 = LOD
| 오프셋 | 타입 | 필드 |
|-------|------|------|
| 0x00 | f32 | lodDistance. 0xD1000000(-3.4e10) = 무한 / 그 외 음수 거리 (-480 등) |
| 0x04 | u32 | objectCount |
| 0x08 | u32 | hasBounds (1이면 오프셋 표 뒤에 오브젝트별 바운딩 구) |
| 0x0C | u32[objectCount] | 오브젝트 오프셋 (그룹 기준) |

LOD 의미: `aoRED` 처럼 lod[0](-480) 이 고해상도, lod[1](무한) 이 저해상도. 거리 &lt; 480 이면 lod[0].
`lodGroup*` 처럼 lod[0] 이 비어 있는 "원거리 전용 대역 모델" 도 있다 → 내보내기는 지정 LOD 가 비면 다음 LOD 사용.

### 3.4 오브젝트 (머티리얼 1개 단위)
| 오프셋 | 타입 | 필드 |
|-------|------|------|
| 0x00 | u32 | type: 0x0E (일반/스키닝), 0x1D (정적, STROW 사용 다수) |
| 0x04 | u32 | dmaTagCount — 체인 하나의 DMA 태그 수 (RET 포함) |
| 0x08 | u16 | materialIndex |
| 0x0A | u16 | jointMapCount |
| 0x0C | u32 | chainCount — DMA 체인 수 |
| 0x10 | u32 | vuOr: 0x10 (대부분) / **0x50 = 비트 0x40 → 혼합 스키닝 VU 프로그램** (프로그램 = (VU 플래그 &amp; +0x14) \| +0x10, 0x165998). 이 비트가 있는 오브젝트는 부착 관절이 있어도 정점이 모델 공간이다 (말하는 머리 2 개뿐 — ATHN05B 제우스 얼굴 MAmagicDoor, 43 §22) [확실: 데이터 / 추정: VU1 프로그램 미해독] |
| 0x14 | i32 | 플래그로 추정 (-219, -220, -203, -204, -81, -84: 하위 바이트 비트필드. 0x80 비트는 히어로·적·아레나 일부, 0x10 비트는 z=0 인 2D 오버레이 메시(`Athn01B_0`) 에서 관찰) |
| 0x18 | u8,u8,u16,u16,u16 | 미확정 (레이어/배치/정점 수 계열) |
| 0x20 | DMAtag[chainCount][dmaTagCount] | 16바이트: 하위 64비트 = DMA 태그, 상위 64비트 = VIF 코드 2개 |
| 이후 | u32[jointMapCount] | 오브젝트가 쓰는 스켈레톤 관절 인덱스 (VU 매트릭스 슬롯 → 관절) |
| 이후 | | REF 가 가리키는 VIF 패킷 |

### 3.5 혼합 노드 (헤더 +0x2C) [확실: 데이터, 2026-10-24]
- 전 WAD 메시 6288 개 중 12 개만 0 이 아니다: ATHN05B `MAmagicDoor_0` (제우스 얼굴, 관절 31 + 노드 32), ATHN02D `MAtalkingHead01_0`, ATHN01F `aoXPT_0`,
  PAND03I `LoopingHead_0` · `kAndSwitchOffhead_0`, PAND04G `deadWifeyTF_0`, R_CY3 · R_CYW · R_MI5 · R_MIN, R_HERO1 · R_HERO5 (`hero_0` 노드 1 – 2).
- 표 시작 = 0x50 + 4·partCount + 20·recordCount. 노드 i = Σ 가중 × 관절 행렬 (가중 합 1, 관절 &lt; +0x28) 이고 오브젝트 jointMap 의 번호
  **관절 수 + i** 가 그 노드다 — 05 문서의 "jointMap 범위 밖 번호" (미노타우로스 55 · 56 = 24×0.75 + 25×0.25, 11×0.75 + 12×0.25) 가 이것.
  예전 엔진은 범위 밖 번호를 짝 관절 (번호 − 관절 수) 로 읽어 얼굴·목이 엉뚱한 관절에 붙었다.
- 엔진: `GowMesh.blendJoints/blendWeights`, `SkinnedModel.Part.palette` (관절 행렬 뒤에 노드 행렬을 성분별 가중 합으로), `AnimatedModel` 이 같은 팔레트를
  올린다 (`PlacedMesh` 도). 원작 VU1 의 노드 계산 코드는 못 찾았다 [추정: 쓰임 / 확실: 표].

DMA 태그는 **REF(id 3)** 와 **RET(id 6)** 만 관찰됨. REF 주소는 **오브젝트 시작 기준**.
VIF1 DMA 는 TTE=0 이라 태그 상위 64비트(VIF 코드 2개)가 먼저 전송되고 이어서 REF 데이터가 전송된다.
마지막 배치의 `MSCAL` 이 RET 태그의 VIF 코드에 들어 있으므로, 체인을 "태그 VIF 코드 + REF 데이터" 순서로 이어 붙여 하나의 스트림으로 해석한다.

`chainCount > 1` (부서지는 통/항아리 등): 체인끼리 지오메트리 REF 는 동일하고 **정점색 REF 만 다르다** → 배치된 인스턴스별 베이크 라이팅으로 추정. 내보내기는 체인 0 사용.

## 4. VIF 스트림

### 4.1 처리하는 명령
NOP, STCYCL, OFFSET, BASE, ITOP, STMOD, MSKPATH3, MARK, FLUSHE/FLUSH/FLUSHA, MSCAL/MSCALF/MSCNT, STMASK(+1워드), STROW/STCOL(+4워드), MPG/DIRECT(건너뜀), UNPACK(0x60~0x7F).
전체 214개 WAD 에서 실제로 쓰인 것은 NOP, STCYCL, STMOD, STROW, MSCAL(0/3/6), UNPACK 6종뿐이다.

배치 = `MSCAL` 사이의 UNPACK 묶음 (VU1 마이크로프로그램 1회 실행). UNPACK 주소는 대부분 FLG=1(TOPS 상대, 더블 버퍼링).

### 4.2 UNPACK 포맷 → 정점 속성
| 포맷 | cmd | 의미 | 변환 |
|------|-----|------|------|
| V4-16 | 0x6D | 위치 x,y,z + w | 정수(+STROW) ÷ 16. w: bit15 = ADC, 하위 15비트 = 스키닝 가중치(4096 = 1.0) |
| V2-16 | 0x65 | UV | ÷ 4096 (VU ITOF12) — OBJ 는 v 뒤집음 |
| V2-32 | 0x64 | UV (float) | 비트 그대로 float (`fogMaterial`, 다층 메시) |
| V3-8 | 0x6A | 법선 | ÷ 127 |
| V4-8 (usn) | 0x6E | 정점색 RGBA | 0x80 = 1.0 (PS2 관례) |
| V4-32 | 0x6C | VU 파라미터 | 주소 0: GIF 태그, 주소 0xF4: 바운딩 구 (x,y,z,r ×16 단위) |

정점 레이아웃은 STCYCL CL 로 정해지는 인터리브 (WL=1):
| STCYCL | 슬롯 (base+0 …) | 용도 |
|--------|----------------|------|
| CL=4 | UV, 법선, 색, 위치 | 스키닝/라이팅 메시 (히어로, 적) |
| CL=3 | UV, 색, 위치 | 정적 레벨 지오메트리 (정점색 라이팅) |
| CL=2 | 색, 위치 | 텍스처 없는 메시 (`fogCube`) |

정적 메시(type 0x1D)는 `STMOD 1` + `STROW (ox,0,oz,0)` 으로 int16 위치에 큰 오프셋을 더한다 (int16 범위를 넘는 월드 좌표 표현). 다층(멀티패스) 오브젝트는 배치마다 지오메트리 REF 뒤에 "레이어 REF" 가 **같은 VU 주소**에 UV/색을 다시 쓰므로, 같은 주소는 나중 UNPACK 을 쓴다.

### 4.3 GIF 태그 (V4-32 @ VU 주소 0)
- 항상 `PRE=1, PRIM=4(트라이앵글 스트립)`. 다른 프리미티브는 전 WAD 에서 관찰되지 않음.
- REGS: `0x512` (ST, RGBAQ, XYZ2) 76976회, `0x51F2` (ST, NOP, RGBAQ, XYZ2 — 법선 슬롯은 NOP) 8168회, `0x52`, `0x51`, `0x5` 등.
- 정적 메시는 태그 1개 (NLOOP = 정점 수, EOP). 스키닝 메시는 태그가 여러 개이고 NLOOP 합 = 정점 수 → **관절 블록 표**로 쓰인다 (5절).
- REGS 상위 32비트(실제 GS 레지스터로는 안 쓰는 영역)에 게임 데이터가 들어 있다: 정적 `0x80000000`, 스키닝 `(A<<12)|(B<<2)`.

### 4.4 스트립 → 삼각형
- 위치 w 의 bit15 = GS ADC(그리기 안 함). 스트립 시작 두 정점에 ADC, 중간의 단일 ADC 는 삼각형 하나 건너뛰기(스트립 꺾기).
- **감김 방향이 스트립마다 제각각**이다. 법선과 비교하면 전역 짝/홀 규칙, 스트립 시작 기준 규칙 모두 44~56% 만 일치했다 (배치 단위로는 0% 또는 100% 가 섞임). GS 는 컬링을 하지 않으므로 툴이 감김을 신경 쓰지 않은 것으로 판단.
- 그래서 법선이 있으면 삼각형마다 면 법선과 정점 법선 합의 내적으로 감김을 보정한다 (보정 후 일치율 1.000). 법선이 없는 정적 메시는 스트립 시작 기준 짝/홀 규칙만 적용 → **양면 렌더링 필요**.

### 4.5 좌표 스케일 근거 (÷16)
- VU 가 ITOF4(÷16) 로 변환한다고 가정. CXT `hero` 스켈레톤의 골반 관절 높이 21.65 와 메시 높이 0~37.25(정수 0~596) 가 맞는다 (골반이 키의 58%).
- 정적 메시 VU 바운딩 구(주소 0xF4) = 정수 ÷ 16 단위와 일치. 그룹 헤더의 바운딩 구는 그 1/16 값으로 저장되어 있음.
- 결과: 크레토스 키 ≈ 37 단위, 판도라 아레나 반경 ≈ 800 단위, 큰 항아리 ≈ 32 단위.
- `MSH_`(0x70) float 메시는 ÷16 이 없는 다른 값 범위(≈1400)라 같은 공간인지 미확인.

## 5. 스키닝 / 스켈레톤 (05 애니메이션 단계 메모)

- 히어로 정점은 **모델 공간 바인드 포즈**로 저장되어 있어 관절 변환 없이도 T자 자세가 나온다.
- 위치 w 하위 15비트 = 가중치 (4096 = 1.0). GIF 태그 블록마다 NLOOP 개 정점이 같은 관절 쌍을 쓴다:
  상위 워드 `(A<<12) | (B<<2)` → A, B = 오브젝트 jointMap 인덱스 (B*4 는 VU 매트릭스 주소 오프셋으로 추정).
  강체 블록은 A == B, 가중치 0 (예: 통 조각 `0x701C` = 7,7). 가중치는 A 에 w, B 에 1-w (05 문서).
- 오브젝트 jointMap 은 최대 12개 → VU 메모리에 올라가는 매트릭스 팔레트.
- **해결(05 문서 1.5절):** 부서지는 통(`breakBarrel2`) 조각처럼 inverse bind 가 없는 관절, 그리고 메시 파트 `jointRefs[0] != 0` 인 파트의 정점은 **관절 로컬 공간**이다. CXT 바인드 포즈(관절 월드 행렬)를 적용하면 제자리로 간다. 가중치는 A 관절에 w, B 에 1-w.
- CXT `hero`(0x40001, v4) 에 스켈레톤이 있다:
  - 0x1C u32 jointCount (111), 0x30 부터 관절 레코드 16바이트 × N: `s16 firstChild, s16 nextSibling, s16 parent, s16 ?, u32 0xD1000000, u32 flags`
  - 이어서 관절 이름 (`pelvis`, `vertebrae2` … `lWeapIH`, `rightBladeBack`, `skirtJoints` …)
  - 0x28 오프셋의 블록: `{u32 count, u32 off, u32 4, u32 off, ...}` 다음 0x30 바이트 뒤부터 관절별 4×4 float 로컬 행렬 (부모 기준, 이동 성분이 마지막 행)
- 메시 0x18 의 20바이트 레코드(히어로 500개)는 관절별 바운딩/충돌 구일 가능성 — 미해석.

## 6. MESH_EXTRA (0x70)
- `MSH_*` (253개): 파티클 방출면 등에 쓰는 float 메시. `u32 vertexCount, u32 triCount, u32×2 0xFFFFFFFF`, 정점 `{f32 pos[3], f32 normal[3]}`, 삼각형 `{u16 a,b,c, u16 ?}`. 직전/직후의 `FXC_`/`PTC_` 가 참조. 렌더 모델과는 연결되지 않는다. `export` 시 `MSH_*.obj` 로 함께 출력.
- `NCV_*` (1952개): 메시가 아니라 곡선(제어점 배열) 데이터 — 카메라/경로 스플라인으로 추정 (`NCV_Boat1Rail1` 뒤에 `CMV_/CAM_`).

## 7. 발견 과정 요약
1. `R_HERO0.WAD` 의 MDL 그룹에서 `MDL_hero`(0x48) 와 `hero_0`(0x2A660) 을 확인 → 메시 본체는 MDL 그룹 안의 0x1000F 리소스.
2. `hero_0` 에서 VIF 코드 패턴(`0x01000103` STCYCL, `0x6A..`, `0x6D..` UNPACK) 검색 → 오브젝트 헤더 뒤 DMA REF 태그(`0x300000xx`)를 발견하고, REF 주소가 오브젝트 기준임을 확인.
3. 헤더 0x50 = 파트 오프셋 표, 파트 → 그룹 → 오브젝트 오프셋 체인을 정적 모델(`group441_0`)과 교차 검증.
4. UNPACK 개수/주소 패턴으로 속성 배치를 확정 (CL=4: UV, 법선, 색, 위치). 위치 w 에서 0x8000 이 스트립 시작 두 정점에 나타나는 것으로 ADC 확인.
5. 전체 WAD 통계로 DMA 태그 종류, VIF 명령, GIF REGS, 체인 수, LOD 거리 분포를 모아 예외 케이스(다층 REF, 체인별 정점색, 빈 LOD) 처리.
6. Python 일회성 스크립트로 가설 확인 후 Java 로 이식, 소프트웨어 래스터라이저 PNG 로 육안 확인.

## 8. 검증 결과
- `R_HERO0.WAD` `MDL_hero`: 21 오브젝트, 139 배치, 7418 정점, 5453 삼각형. 인간형(T자세, 스커트, 손가락) 정상.
- `R_MIN.WAD` 미노타우로스(+도끼), `ARENA06.WAD` 판도라 아레나(검 기둥, 바닥 균열), 하늘 돔, 통/항아리, `ATHN01A.WAD` 배·바위 배경 정상 (통의 관절 로컬 조각은 5절 참고).
- `model:batch` 전체 214 WAD: 모델 6154 / 메시 6288 / 오브젝트 49499 / 배치 191523 / 정점 12.8M / 삼각형 9.15M, **실패 0, 빈 모델 0, 경고 0**, MSH 253개.
- 미리보기 PNG 는 `work/models/<WAD>/<MDL>.png` (커밋 제외).

## 9. 미해결 질문
- 메시 헤더 0x0C~0x24, 20바이트 레코드, 오브젝트 0x14/0x18 필드의 정확한 의미.
- (해결 → 05 문서) 관절 로컬 / 모델 공간 구분, 가중치 대상 관절. VU1 마이크로코드로 재확인은 06 문서.
- MSCAL 0/3/6 이 고르는 VU 프로그램의 차이 (조명 유무? 클리핑?).
- UV 에 STMOD 1(오프셋) 이 걸리는 레이어 — 런타임에 STROW 로 UV 스크롤하는 것으로 추정.
- chainCount 와 CXT 인스턴스(go*)의 대응, MSH_ 좌표계.
