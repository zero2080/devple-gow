# 05. 스켈레톤 / 애니메이션 (CXT 스켈레톤, ANM)

도구: `devple.gow.tools.anim` 패키지 / `anim:*` 명령

```bash
./gradlew -q :tools:run --args="anim:info  gow-rom/R_HERO0.WAD"                      # 스켈레톤 검증 + ANM 세트/그룹 요약
./gradlew -q :tools:run --args="anim:skel  gow-rom/R_HERO0.WAD hero"                 # 관절 트리 (플래그, 바인드 월드 위치)
./gradlew -q :tools:run --args="anim:list  gow-rom/R_HERO0.WAD ANM_hero"             # 애니메이션 목록 (길이, fps, 트랙 수, 루프, 블렌드)
./gradlew -q :tools:run --args="anim:pose  gow-rom/R_HERO0.WAD MDL_hero standIdle 0.5 work/anim/idle.png"
./gradlew -q :tools:run --args="anim:sheet gow-rom/R_HERO0.WAD MDL_hero combo3A work/anim/combo.png --frames 6"
./gradlew -q :tools:run --args="anim:sheet gow-rom/R_HERO0.WAD MDL_hero maiMin01 out.png --ext gow-rom/R_MIN.WAD"  # 외부 그룹
./gradlew -q :tools:run --args="anim:batch gow-rom"                                  # 전체 WAD 검증
./gradlew -q :tools:run --args="anim:sheet gow-rom/R_HERO0.WAD MDL_hero combo3A out.png --weapon gow-rom/R_WPN0_2.WAD --hit"  # 블레이드 부착 + CDV 구
```
`pose` 의 애니메이션 자리에 `bind`(정점 원본), `idle`(스켈레톤 아이들 포즈) 도 쓸 수 있다.
공통 옵션: `--skel CXT` `--anm ANM_x` `--ext 다른.wad` `--lod N` `--inst N`(체인/인스턴스) `--yaw 도`.

| 클래스 | 역할 |
|-------|------|
| `Skeleton` | CXT 0x40001 파싱: 관절 계층/이름/플래그, 바인드 로컬 행렬, 확장 회전, inverse bind, 아이들 성분. 로컬→월드→스킨 행렬 |
| `AnimationSet` | ANM_ (0x3) 세트: 데이터 타입, 그룹, act. 외부 그룹(0x10003) 연결 |
| `Animation` | act 하나: 헤더, 회전/이동 트랙, 프레임 펼치기(`bake`), 시간 샘플(`sample`) |
| `AnimTrack` | 12바이트 트랙 엔트리 + 비트맵 + 양자화 샘플 디코드 |
| `Pose` | 관절별 로컬 성분 → `local/world/skin` 행렬 |
| `SkinnedModel` | 메시 정점에 (관절 A, B, 가중치) 부여, CPU 스키닝 → `TriMesh` (검증 렌더용) |
| `Mat4` | 행벡터 규약 4×4 유틸 |

## 0. 좌표계 / 규약

- **행벡터 규약**: `p' = p · M`, 행렬은 행 우선 float[16], 이동 = 마지막 행(m12..m14). `world = local · parentWorld`.
- 오른손 좌표, **Y 위, 캐릭터 정면 = -Z**, 캐릭터 왼쪽 = -X (발가락 관절 z = -2.33, 턱 끝 z = -2.82, `lClavicle` x < 0).
- 단위는 04 문서와 같다 (크레토스 키 ≈ 37).

## 1. 스켈레톤 = CXT 오브젝트 (매직 0x00040001, "v4")

WAD 배치: `GROUP{ RES hero (CXT 0x40001)  RES SCR_cloth  RES ANM_hero(참조)  RES MDL_hero(참조) ... }GROUP`
→ CXT 와 같은 그룹 안의 `ANM_*`, `MDL_*` 참조로 스켈레톤·애니메이션 세트·모델이 연결된다 (`Skeleton.groupRefs`).

### 1.1 헤더
| 오프셋 | 타입 | 필드 |
|-------|------|------|
| 0x00 | u32 | magic 0x00040001 |
| 0x1C | u32 | jointCount (히어로 111, 미노타우로스 55, 소품 1~20) |
| 0x20 | u32 | 0 |
| 0x24 | u32 | objectFlags (히어로 1) |
| 0x28 | u32 | dataOffset → 1.3 행렬 블록 |
| 0x2C | Joint[jointCount] | 16바이트 관절 레코드 |
| 이후 | char[jointCount][24] | 관절 이름 (`main`, `pelvis`, `vertebrae2` … `zeroJoint`, `synchJoint`, `lWeapOH`) |

04 문서의 "0x30 부터 레코드" 는 4바이트 밀린 해석이었다. 레코드는 0x2C 에서 시작한다.

### 1.2 관절 레코드 (16B)
| 오프셋 | 타입 | 필드 |
|-------|------|------|
| 0x0 | u32 | flags (아래) |
| 0x4 | s16 | firstChild (-1 없음) |
| 0x6 | s16 | nextSibling |
| 0x8 | s16 | parent (-1 = 루트). **부모 인덱스는 항상 자식보다 작다** |
| 0xA | s16 | extIndex: flags&0x8 일 때 확장 회전 행렬 번호 |
| 0xC | f32 | -2^35 (자리표시 값) |

| 플래그 비트 | 의미 | 근거 |
|------------|------|------|
| 0x0002 | 애니메이션 대상 관절 (말단 tip 관절은 0) | 히어로 전 관절 관찰 |
| 0x0008 | 확장 회전 행렬 사용 (`R · Ext[extIndex]`) | 아래 1.4 검증 (오차 0.003) |
| 0x0020 | 일반 관절 | |
| 0x0080 | **inverse bind 행렬 보유** (보유 관절 순서대로 inv 배열 인덱스) | 개수 = invCount (히어로 103) |
| 0x0200 / 0x0400 / 0x0800 | 회전 / 이동 / 스케일 애니메이션 가능 | |
| 0x8000 | 회전이 **쿼터니언** (없으면 오일러) | 1.4 검증 |

### 1.3 행렬 / 벡터 블록 (dataOffset 기준)
| 오프셋 | 타입 | 필드 |
|-------|------|------|
| 0x00 | u32 | localCount (= jointCount) |
| 0x04, 0x08 | u32 | extOffset, extCount (히어로 4: 무기 손잡이 2개, 등 뒤 블레이드 2개) |
| 0x0C, 0x10 | u32 | invOffset, invCount |
| 0x14~0x1C | | 0, -1, 0 |
| 0x20 | u32 | posOffset → f32×4 [jointCount] 아이들 로컬 이동 |
| 0x24 | u32 | rotOffset → s32×4 [jointCount] 아이들 로컬 회전 |
| 0x28 | u32 | scaleOffset → f32×4 [jointCount] 아이들 스케일 |
| 0x2C | u32 | vec7Offset → f32×4 (항상 0, 미사용) |
| 0x30 | Mat4[jointCount] | 부모 기준 바인드 로컬 행렬 |
| ext | Mat4[extCount] | 확장 회전 (3×3 만 사용) |
| inv | Mat4[invCount] | inverse bind (모델 공간 → 관절 공간) |

### 1.4 회전 표현과 로컬 행렬
- **오일러**: (x, y, z) 정수, **16384 = 1회전**. 행렬 = `Rx · Ry · Rz` (행벡터: X 먼저). 12가지 축 순서·부호 중 이 조합만 저장된 로컬 행렬과 일치 (총오차 0.65, 다른 조합 ≥ 10).
- **쿼터니언** (flags&0x8000): (x, y, z, w) 정수, 노름이 4096·2/π ≈ 2607.6 (애니메이션 쪽은 32768·2/π ≈ 20861 — 2절). 정규화해서 사용.
- **로컬 행렬** = `Scale · Rot · Ext · Translate` (Ext 는 flags&0x8 일 때). 이동 = 마지막 행.
- 검증: 히어로 111관절에서 `idle 성분으로 만든 로컬 ≈ 저장 로컬` (최대 원소합 오차 0.0036), `bindWorld · inv = I` (오차 0.0003). 미노타우로스 0.0037 / 0.0015.
- 전체 14531 CXT 중 128개는 저장 로컬 행렬이나 inverse bind 가 아이들 성분과 다르다 (예: `group10` 은 로컬 행렬에 스케일 0.022 가 들어 있음 = 숨김 상태로 추정, `aobhb` 등은 inverse bind 가 아이들과 다른 포즈). 스키닝은 항상 inverse bind 를, 포즈는 아이들 성분 + 애니메이션을 쓴다.

### 1.5 스키닝 규칙 (04 문서 미해결 항목 해결)
```
GIF 태그(VU 메타 블록) REGS 상위 32비트 = (A<<12) | (B<<2)      A, B = 오브젝트 jointMap 슬롯
정점 w 하위 15비트 = 가중치 w (4096=1.0)
포즈 위치 = w · (p · M[jointMap[A]]) + (1 - w) · (p · M[jointMap[B]])
M[j] = invBind[j] · world[j]    (파트 jointRefs[0] == 0 이고 관절에 inverse bind 가 있을 때)
M[j] = world[j]                 (그 밖: 정점이 관절 로컬 공간)
```
- **가중치는 A(상위 니블 쪽) 관절에 걸린다.** 강체 블록은 A==B, w=0 이므로 결과는 같다.
- 메타 블록은 배치의 V4-32 UNPACK 중 **가장 작은 VU 주소부터 연속**이다 (더블 버퍼라 주소 0 / 341 / 683 …). NLOOP 합 = 배치 정점 수 (히어로·미노타우로스 경고 0).
- 스티치 블록: 태그 비트 45(0x2000 of 워드1) 가 켜진 블록에서, 비트 44 가 켜져 있으면 홀수 번째 정점이 다음 블록의 관절 쌍을, 꺼져 있으면(스택 감소) 이전 블록의 관절 쌍을 쓴다 (블록 경계 이음매). 히어로 899 블록 중 108개.
- **관절 로컬 파트**: 메시 파트 헤더의 `jointRefs[0]` 이 0 이 아니면 그 파트의 정점은 관절 로컬 공간이다 (inverse bind 가 있는 관절이어도). 미노타우로스 다리 파트(jointRefs=[6], 관절 37~47 은 0x80 보유)가 이 규칙으로만 제자리에 붙는다. 히어로 몸통 파트는 jointRefs=[0].
- inverse bind 가 없는 관절(소품, `breakBarrel2` 의 통 조각 관절 0~18)은 원래 관절 로컬이다. **04 문서의 통 조각 문제는 바인드 포즈 적용으로 해결** — 조각 2~9(파트 jointRefs=[2])와 11~18(jointRefs=[11]) 모두 `world` 로 변환하면 통이 완성되고, `breakBarrel2` 애니메이션에서 조각이 날아가 바닥에 흩어진다.
- `chainCount > 1` 오브젝트는 체인(인스턴스)마다 jointMap 이 있다: jointMap 영역에 `chainCount × jointMapCount` 개 u32 (`MeshObject.jointMaps`). 관찰한 통에서는 체인끼리 같은 맵이었다.

## 2. 애니메이션 세트 ANM_ (매직 0x00000003)

### 2.1 세트 헤더
| 오프셋 | 타입 | 필드 |
|-------|------|------|
| 0x00 | u32 | magic 3 |
| 0x08 | u32 | flags: 0x1000 회전 / 0x2000 이동 / 0x4000 스케일 채널 존재, bit0 자동 재생 추정 (히어로 0x3001) |
| 0x0C | u32 | 데이터 크기 |
| 0x10 | u16 | dataTypeCount |
| 0x12 | u16 | groupCount |
| 0x14 | u32 | totalActCount (외부 그룹 포함, 히어로 499) |
| 0x18 | u32[groupCount] | 그룹 오프셋 (세트 기준) |
| 이후 | {u16 typeId, u8 p1, u8 p2}[dataTypeCount] | 데이터 타입 |

| typeId | 대상 | 상태 |
|-------|------|------|
| 0 | 관절 회전 (p2 = 3 오일러 전용? / 4 쿼터니언 포함) | **해석 완료** |
| 5 | 관절 이동 | **해석 완료** |
| 10 | 오브젝트별 범용 채널 (슬롯 의미는 오브젝트 클래스가 정함). 히어로 슬롯 1 = **무기 로케이터 `lWeapOH`/`rWeapOH` 모델 공간 위치**(블레이드 사슬 궤적), 슬롯 2 = 관절별 u8 부착 상태 — 2.8절 | **히어로 해석 완료** |
| 11 | 사운드 이미터/부서지는 물체 쪽 (통, 상자, 레버) | 미해석 |
| 3, 8, 9 | 머티리얼 색 / UV 스크롤 / 텍스처 시트 (03·07 문서 영역) | 미해석 |
| 12 | `loddetailgroup` 등 | 미해석 |

데이터 타입 목록은 거의 항상 `[0, 5, 10…, 11…]` 순서다. **ANM 세트는 스켈레톤 관절 인덱스를 그대로 쓴다** (스트림 번호 = 관절 × 4 + 성분) — 매핑 테이블 없음. 세트가 쓰는 최대 관절 번호 ≤ 스켈레톤 관절 수 (전체 7373 act 에서 초과 0).

### 2.2 그룹
| 오프셋 | 타입 | 필드 |
|-------|------|------|
| 0x00 | u32 | 그룹 번호 |
| 0x04 | u32 | firstAct (세트 전체 act 번호) |
| 0x08 | u32 | flags: 0x40 일반, 0x220/0x820/0x420 블렌드 그룹(`bwalk`, `balWalk`, `bClimb`), **0x20000 외부** |
| 0x0C | u32 | actCount |
| 0x10 | u32 | 0x0B |
| 0x14 | char[24] | 이름 (`hero`, `bwalk`, `maiMin`, `Athn01A` …) |
| 0x30 | u32[actCount] | act 오프셋 (그룹 기준) |

**외부 그룹** (flags 0x20000): 본 세트에는 이름만 있고, act 는 다른 WAD 의 **0x00010003 리소스**에 있다.
`ANM_hero` 의 `maiMin` 그룹 → `R_MIN.WAD` 의 `ANM_MAIMIN`, `Athn01A` → `ATHN01A.WAD` 의 `ANM_hero_Athn01A`.
0x10003 포맷 = `u32 magic, u32 0` + 위 그룹 레코드 그대로 (그룹 번호로 연결, 이름 일치 확인). 레벨/적 WAD 가 로드될 때 히어로 세트의 빈 그룹을 채우는 구조다.
(`R_MIN` 의 두 번째 `ANM_MAIMIN` 은 `ANM_camera` 참조 그룹 안에 있어 카메라 세트용이다.)

### 2.3 act (애니메이션 하나)
| 오프셋 | 타입 | 필드 |
|-------|------|------|
| 0x00 | u32 | flags: bit0 루프(`standIdle` 0x3041, `walkBlend1` 0x3221), bit1 1회(`combo3A` 0x3042), 0x1000/0x2000 회전/이동 데이터, 0x200~0x800 블렌드 |
| 0x04 | f32 | ? (0, 0.09, 0.12, 2.0, 3.0) |
| 0x08 | u32 | 0x0B |
| 0x0C | f32 | 1.0 (재생 속도 추정) |
| 0x10 | s32 | blendIndex: 블렌드 그룹 안 슬롯 (walkBlend0=2, 1=1, 2=0 …), 일반 -1 |
| 0x14 | f32 | blendParam: 블렌드 축 값 (걷기 3.5, 달리기 5.0, 균형 2.37) |
| 0x1C | f32 | duration (초) |
| 0x24 | char[24] | 이름 (뒤쪽은 툴 쓰레기 값) |
| 0x3C | u32 | act 크기 |
| 0x40 | u32 | globalIndex (= 그룹 firstAct + j) |
| 0x60 | | 툴 포인터 쓰레기 (0x0351BE10 패턴) |
| 0x64 | StateDescr[dataTypeCount] | 20B: `u16 ?, u16 trackCount, u32 ?, u32 offset(act 기준), f32 frameTime, u32 ?` |

- frameTime 은 **1/15 (히어로 212개) 또는 1/30 (40개)**. 회전/이동 디스크립터는 항상 같은 값 (전체 검사 불일치 0).
- 프레임 수 = round(duration / frameTime) + 1 과 트랙 끝 프레임 + 1 중 큰 값.

### 2.4 트랙 (회전 = 타입 0, 이동 = 타입 5 디스크립터가 가리킴)
트랙 엔트리 12바이트 × trackCount:
| 오프셋 | 타입 | 필드 |
|-------|------|------|
| 0x0 | u16 | baseStream: 첫 스트림 번호 (**스트림 = 관절 × 4 + 성분**) |
| 0x2 | u8 | flags: bit0 = 가산(델타), bit1 = 비트맵 명시, bit4~7 (부호 있는 상위 니블) = 원소 1개일 때의 시프트 |
| 0x3 | u8 | offHi: 데이터 오프셋 비트 16~23 |
| 0x4 | u16 | count: 샘플 수. **0 이면 서브 트랙 묶음** |
| 0x6 | u16 | start: 시작 프레임 |
| 0x8 | u16 | hold: 마지막 샘플 유지 프레임 수 |
| 0xA | u16 | offLo: 데이터 오프셋 비트 0~15 (엔트리 기준) |

서브 트랙 묶음 (count == 0): 데이터 = `u8 addCount, u8 total, Mgr[total]{u16 count, start, hold, offLo}`, 이어서 공용 비트맵.
앞의 addCount 개 서브 트랙은 가산, 나머지는 절대값. 서브 트랙 데이터 = 엔트리 + (offHi<<16 | Mgr.offLo).

비트맵 (flags bit1, 없으면 기본 `{1워드, 1원소, dataOffset 0, 0x0001}`):
```
u8 wordCount, u8 elemCount, u16 dataOffset, u16 words[wordCount], s8 shifts[elemCount] (elemCount > 1 일 때)
word w 의 비트 b  →  스트림 baseStream + w*16 + b     (켜진 비트 순서 = 원소 순서)
샘플 주소 = 데이터 + dataOffset + frame * elemCount * size + elem * size        (프레임 우선 인터리브)
```
| 채널 | 절대 | 가산 |
|------|------|------|
| 회전 | s16 (오일러 16384=1회전 / 쿼터니언 노름 ≈ 20861) | s8 × 2^-shift |
| 이동 | f32 | s16 × 2^-shift / 256 |

시프트: shift > 0 → 1/2^shift, shift < 0 → 2^-shift (예: flags 0x83 → 상위 니블 -8 → ×256).

### 2.5 재생 알고리즘 (`Animation.bake` / `sample`)
```
cur[stream] = 아이들 값 (오일러/이동은 CXT 값 그대로, 쿼터니언은 ×8 로 애니메이션 단위에 맞춤)
for frame f = 0 .. frames-1:
    가산 트랙:   start < f <= start+count  이면 cur += sample[f - start - 1]     (샘플 k = 프레임 start+k → start+k+1 변화량)
    절대 트랙:   start <= f <= start+count-1+hold 이면 cur = sample[min(f - start, count-1)]   (경계 프레임에서는 절대값 우선)
    table[f] = cur
시간 t: fp = t / frameTime, 인접 두 프레임 선형 보간 (쿼터니언은 부호 맞춘 뒤 nlerp)
로컬 행렬 = Skeleton.localMatrix(관절, rot, pos, scale) → world → skin
```
근거:
- 절대 키 → 가산 구간 → 절대 키 연속성: 이동 379건 중앙값 오차 0.000 (최대 0.004), 오일러 2359건 중앙값 1 (16384 단위; 큰 값은 int16 랩 = 4회전 차이), 쿼터니언 2828건 중앙값 9 / 최대 255 (노름 20861 대비 1% 이하 양자화 누적).
- 절대 트랙의 마지막 프레임 = 가산 트랙 시작 프레임 (예: `air360` 관절 30.z: 절대 0~3, 가산 3~33).
- 히어로 세트에서 가산·절대가 섞인 성분은 확인한 범위에서 모두 절대 키가 먼저 나온다 (예: `air360` 관절 36.z 는 절대 0~10 → 가산 10~28 → 절대 28~33). 따라서 아이들 값(특히 쿼터니언 ×8 배율)은 애니메이션이 건드리지 않는 성분에만 쓰인다.
- 시간 압축 방식: 키프레임 감축이 아니라 **프레임 단위 고정 샘플 + 구간별 절대(16비트)/델타(8비트, 스케일 시프트) 선택** 이다. 바뀌지 않는 성분은 트랙에 없다(아이들/직전 값 유지).

### 2.6 루트 모션 / 특수 관절
- `zeroJoint`(히어로 105, `main` 의 자식이며 `pelvis` 의 형제)에 **루트 이동**이 들어 있다: `dash` z 0.14 → -74, `evadeFront` → -146 (정면 -Z 로 전진), `wallJumpUp` y +60, `jumpUp` y +9.4. 메시는 이 관절에 스키닝되지 않으므로 게임은 zeroJoint 위치를 오브젝트 이동으로 옮긴다고 판단.
- 루프 걷기 계열은 zeroJoint z 가 상수 (`walkBlend1` -3.2, `walkBlend2`/`runLand` -8.0, `standIdle` 3.64) — blendParam(3.5/5.0)과 함께 이동 속도 파라미터로 추정 (미확정).
- `synchJoint`(106, 아이들 z = -20 = 정면 20단위)는 잡기/처형(`maiMin*`) 상대 위치 기준, `lWeapOH`/`rWeapOH`(107/109)는 블레이드(외부 모델 `R_WPN*` 의 `maiblade`) 부착점 — 위치는 타입 10 (2.8절).
- 미노타우로스 `axeOut`(54)는 바닥에 놓인 도끼 위치용으로 보인다 (가시성은 타입 10/11 또는 스크립트 담당, 미해석).

### 2.7 이벤트 / 사운드
act 안에서 명시적인 이벤트(사운드·히트 프레임) 테이블은 찾지 못했다. 히어로 act 이름은 `TWK_goHero` / `TWK_CombatFile_*` 튜닝 테이블이 이름으로 참조하므로(02 문서) 히트 판정·사운드 타이밍은 TWK 쪽에 있을 가능성이 높다 → 06/1x 단계.

### 2.8 타입 10 채널 (무기 궤적 / 부착 상태)
디스크립터(2.3) 의 `+0x10 u32` 가 **원소 크기**다: 회전 2(s16), 이동 4(f32), 타입 10 은 슬롯마다 4 또는 1. `+0x00 u16` 은 툴 쓰레기(같은 act 라도 R_HERO0/1/4 에서 0xBE10/0x2110/0xC010).

**원소 크기 4** = 2.4절 트랙 포맷 그대로(f32 절대 / s16 가산). 히어로 슬롯 1(전체 996 act):
- 엔트리 1개, baseStream 0x1A0, 비트맵 2워드 `0x7000, 0x0070` → 스트림 428–430, 436–438 = **관절 107 `lWeapOH`, 109 `rWeapOH` 의 x/y/z**.
- 두 관절은 `main`(0) 의 자식(부모 = 모델 원점), inverse bind 없음, 아이들 위치 0 → 값이 곧 **모델 공간 위치**. 회전은 타입 0 트랙(스트림 428–431, 436–439)에 있다.
- 측정(`combo3A`): 평소엔 손목에서 1.5–1.6 (손에 쥔 상태), 프레임 6–14(0.2–0.47초) 왼쪽 OH 가 정면 −Z 로 최대 76 단위까지 날아갔다 돌아온다 = **블레이드 사슬 투척 궤적**. `standIdle` 에서는 y=32.6, 등 뒤(`leftBladeBack`/`rightBladeBack` 위치) = 등에 멘 상태.
- 재생: `Animation.channelTracks` 로 파싱, `bake` 때 `locatorTrack()` 조건(성분 x/y/z, 부모 0, inverse bind 없음, 쿼터니언, 같은 act 타입 5 가 안 건드림, 이름 `*WeapOH`)을 만족하면 이동 테이블에 합친다. 이름 조건 없이 고르면 통나무 조각(`logShard*`), `zeroJoint`, `Axe2Out` 등 의미 미상 채널이 걸려서(anim:batch 통계) 히어로 바인딩만 적용한다. 전체 batch: 적용 4518 act × 2관절, 실패/NaN 0.
- **운반 관절 "Anim"** [추정: 데이터 2 건]: ATHN02A `ANM_shiptrans` 스트림 6 (관절 1 "Anim" Z) 0 → −480 (10.4 초, 배 입항), PAND03A `ANM_animhead` 스트림 5 (관절 1 Y) 0 → 160.
  둘 다 메시 없는 관절 · 타입 5 없음 · 첫 값 0 → 엔진은 바인드 로컬 위치에 **더한다** (`Animation.transportTrack`, 30 §6.6). 원작 소비자 (타입 10 채널 vtable 0x2C05E0 = 값만 드는 수동 채널) 는 미해석.
- 다른 오브젝트의 원소 크기 4 슬롯은 대부분 스트림 8(= "관절 2 x" 위치가 아니라 스칼라 채널 번호로 보임), 값 250–1055 (`airImpale` 슬롯 3–5, 통·모래주머니·횃불) — 미해석.

**원소 크기 1** = 12B 엔트리 `{u16 id(관절 번호), u8 flags, u8 offHi, u16 count, u16 start, u16 hold, u16 offLo}` + `u8 값[count]` (`Animation.ByteTrack`, `byteChannel(joint, t)`).
- 히어로 슬롯 2 (1/60초 프레임): 관절 106(synchJoint)=1, 107/109=0 — 히어로 세트 전체에서 상수.
- 미노타우로스 `ANM_min` 슬롯 1: 관절 54(`axeOut`) 대부분 0, `maiMin01` 에서 87프레임에 관절 36=1→0, 54=0→1 (도끼가 손에서 바닥 로케이터로 넘어감), `maiMin04` 반대 방향 → **부착 관절 전환(무기 소유/가시성) 플래그** [추정]. 엔진은 부서지는 물체에서만 관절 가시성으로 쓴다 (41 문서 §1.3, `Animation.hiddenJoints`).

블레이드 오브젝트·충돌 구는 34 문서.

## 3. 엔진용 샘플러 API

```java
WadFile wad = WadFile.load(path);
Skeleton sk = Skeleton.loadAll(wad).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
AnimationSet set = AnimationSet.load(wad, "ANM_hero");      // 같은 WAD 의 0x10003 외부 그룹 자동 연결
set.attachAll(WadFile.load(levelWad));                       // 레벨/적 WAD 의 외부 그룹 추가 연결
Animation walk = set.find("walkBlend1");                     // 또는 "bwalk/walkBlend1"
Pose pose = walk.sample(sk, t, walk.looping());              // 관절별 rot(raw)/pos/scale
float[][] world = pose.world(sk);                            // 관절 월드 행렬 (모델 공간, 행벡터 float[16])
float[][] skin  = sk.skin(world);                            // invBind·world (inverse bind 없는 관절은 world)
// GPU: 파트 jointRefs[0] != 0 이면 world, 아니면 skin 팔레트를 쓴다 (1.5절)
float[] rootMotion = pose.pos[sk.indexOf("zeroJoint")];       // 루트 이동
```
`Animation.bake(sk)` 결과(프레임 × 스트림 테이블)는 스켈레톤별로 캐시되어 `sample` 은 O(관절 수) 이다.
블렌딩(walkBlend0..2 등)은 두 `Pose` 의 성분 보간으로 구현하면 된다 (원본 블렌드 규칙은 미확정).

## 4. 검증 결과
- 히어로(`R_HERO0` `MDL_hero` + `hero` + `ANM_hero`): `standIdle`, `walkBlend1` 걷기 사이클(제자리 걸음, 시작/끝 프레임 일치), `combo3A` 공격(돌진 휘두르기), 외부 그룹 `maiMin01`(R_MIN 연결, 뿔 잡기) — 모두 자연스러운 포즈, 터진 팔다리 없음. 이미지 `work/anim/sheet_*.png` (커밋 제외).
- 미노타우로스(`R_MIN` `MDL_MIN` + `min` + `ANM_min`): `axeAttack2` 도끼 들어 내려찍기, 다리·발굽·꼬리 정상 (관절 로컬 파트 규칙 적용 후).
- `breakBarrel2`(ATHN03A): 아이들에서 완전한 통, 애니메이션에서 조각 비산.
- `anim:batch` 전체 214 WAD: CXT 14531, ANM 세트 5493, 스켈레톤과 묶인 act 7373 개 bake/샘플 **실패 0, NaN 0, 관절 범위 초과 0**. 스키닝 모델 6160 중 45개는 jointMap 에 스켈레톤 관절 수 이상의 번호가 있다(예: 미노타우로스 슬롯 값 55/56 — 정점 8개, 아래 미해결).

## 5. 미해결 질문
- jointMap 에 스켈레톤 범위 밖 번호(관절 수, 관절 수+1)가 들어간 경우의 행렬 (오브젝트 루트/카메라 행렬로 추정). 현재는 짝 관절 행렬로 대체.
- 타입 10 의 히어로 외 슬롯(스트림 8 스칼라), 11/3/8/9/12 채널 상세, 가시성(통 껍데기 `barrelVisDisapear`, 바닥 도끼 — u8 채널 추정) 제어 주체.
- act 0x04 f32, 0x18 f32, 디스크립터의 u16/u32 미상 필드, 세트 flags bit0.
- 블렌드 그룹(blendIndex/blendParam) 의 정확한 혼합 공식, zeroJoint 상수 z 값의 의미(속도?).
- 저장 로컬 행렬이 아이들 성분과 다른 128개 CXT 에서 게임이 처음에 어느 쪽을 쓰는지.
- 스티치 블록 규칙은 레퍼런스 구현(mogaika/god_of_war_browser) 해석을 따름 — VU1 마이크로코드로 재확인 필요 (06 문서).
- 이벤트/사운드 트리거 위치 (TWK 쪽으로 추정).

## 6. 발견 과정 요약
1. `ANM_hero` 헤더의 u16 쌍(8, 62)과 오프셋 표 62개 → 그룹, 그룹 0x0C 의 개수 합 = 0x14 (499) 로 그룹/act 구조 확정. 외부 플래그 그룹은 R_MIN 의 0x10003 리소스(그룹 레코드 + 8바이트 헤더)와 그룹 번호로 맞음.
2. 공개 레퍼런스(mogaika/god_of_war_browser 의 anm/obj 파서)의 트랙 엔트리/비트맵/시프트 해석을 출발점으로 Python 프로토타입 작성 → 쿼터니언 원시값 노름이 항상 20861 로 일정한 것을 확인(디코드 정확성의 강한 증거), 아이들 쿼터니언 노름 2607 과 8배 차이 발견.
3. CXT 아이들 성분 → 로컬 행렬을 12가지 오일러 순서로 맞춰 `Rx·Ry·Rz` 확정, 확장 회전 관절은 `R·Ext` 확정, `world·inv = I` 로 계층/inverse bind 확정.
4. 절대→가산→절대 경계 연속성 통계로 가산 샘플의 시간 정의(구간 변화량)와 경계 우선순위 확정.
5. Java 이식 후 CPU 스키닝 렌더 → 히어로 정상, 미노타우로스 다리 붕괴 → 원본 정점/월드 행렬만 적용 렌더 비교로 "jointRefs[0] != 0 파트 = 관절 로컬" 규칙 도출.

> **엔진 구현 시 정정 (25 문서)**: 메시의 시각적 정면은 +Z 이다. 루트 모션(zeroJoint)은 −Z 로 진행하므로, 이동 방향에 맞추려면 모델을 Y축 180° 회전해서 배치한다.
