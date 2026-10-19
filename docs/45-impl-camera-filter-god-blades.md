# 45. 카메라 필터 · 블레이드 god 판

로드맵 2 (90 문서 §4) 의 남은 두 항목: 카메라 필터 렌더 (0x162008, CameraFilter 템플릿) 와 블레이드 god 판 (0x1FA9A0).
렌즈 플레어는 46 문서.

## 1. 카메라 필터 (`fx/CameraFilter`, `render/CameraFilterRenderer`, `GameScene.renderCameraFilter`)

### 1.1 무엇을 그리나 [확실: 코드]
화면 전체를 덮는 **세로 그라데이션 색 사각형 한 장**이다. 블러·색 행렬 같은 후처리는 없다.

- 필터 노드: 0x16004C 가 크기 0x80 노드를 만들어 0x29CF6C 에 두고 (vtable 0x2BE240) 0x15F288(노드, 층 3, 우선순위 7, 0x8000) 로 렌더 목록에 넣는다.
  같은 모양의 노드가 0x29CF68 에도 있다 (FBMSK 0x00FFFFFF = 알파만 쓰는 다른 용도, 여기서는 다루지 않음).
- 0x162008 = **값 설정 함수**: a0/a1/a2 = 위/가운데/아래 RGBA (노드 +0x20/+0x30/+0x40), f12/f13/f14 = Toppoint/Midpoint/BottomPoint (+0x5C/+0x60/+0x64),
  a3 = GS ALPHA 레지스터 값 (+0x68). 바뀐 값이 있으면 +0xC |= 2 (다시 만들기).
- 그리기 0x121128 (vtable +0x14): GIF 패킷 ZBUF_2 (ZMSK = +0x70 ≠ 0), TEST_2 (ZTE = 1, ZTST = +0x54 = 1 ALWAYS), XYOFFSET_2 = 0, SCISSOR_2 = 화면,
  ALPHA_2 = +0x68, RGBAQ = **(R·255, G·255, B·255, A·128)** (VU0 `vmulx.w` 128 / `vmulx.xyz` 255, Q = 1.0), FRAME_2 (FBMSK = +0x58).
  세 색이 같으면 화면 전체 스프라이트 0x120800, 다르면 0x120D80 이 32 화소 폭 열마다 Gouraud 삼각형 띠를 만든다 (행 위치·색 = 0x1208C8).
  색이 같고 다시 만들 일이 없으면 지난 DMA 체인을 그대로 붙인다.
- ALPHA 표 0x29F140 (u64 ×3): **0 = 0x44** (Cs−Cd)·As+Cd 블렌드, **1 = 0x48** Cs·As+Cd 가산, **2 = 0x42** Cd−Cs·As 감산. FIX 0. GS 알파 128 = 1.0.

### 1.2 그라데이션 좌표 (0x1208C8) [확실: 코드]
그라데이션은 **화면 좌표가 아니라 시선 고도각**이다.
- 카메라 (+0x360 목록 첫 원소) 행렬 3행 (= 뒤쪽 벡터) 의 y 를 acos (0x183F98, 다항식 근사) → a = 뒤쪽 벡터와 월드 위쪽의 각.
- 반세로화각 h = 카메라 +0xF8 / 화면비 (0x330A30) · 0.5.
- 화면 위 끝 u = 1 − (a + h)/π, 아래 끝 u = 1 − (a − h)/π. 화면 세로로 선형.
  → **u = 광선과 월드 위쪽의 각 / π** (0 = 바로 위, 0.5 = 수평, 1 = 바로 아래).
- Toppoint 보다 위는 위 색, Toppoint–Midpoint, Midpoint–BottomPoint 는 선형, BottomPoint 아래는 아래 색 (경계가 화면 밖이면 화면 끝 색을 보간해 넣는다).
- 데이터는 모두 Toppoint 0 / Midpoint 0.5 / BottomPoint 1 → 위를 보면 위 색, 수평은 가운데 색, 내려다보면 아래 색.

### 1.3 템플릿 `/TweakTemplates/CameraFilter/NNN/` (생성자 0x1F4B90, 목록 0x337B74) [확실]
| 필드 | 해시 | 오프셋 | 형 |
|---|---|---|---|
| (위 색) RGBA | 0x0014E3D5 | +0x20 | f32 ×4 |
| (가운데 색) RGBA | 0xB7EB5695 | +0x30 | f32 ×4 |
| (아래 색) RGBA | 0x7947B8AB | +0x40 | f32 ×4 |
| Toppoint / Midpoint / BottomPoint | 0x0B1EC59B / 0x5CAAE128 / 0xB83947A5 | +0x50 / +0x54 / +0x58 | f32 |
| Blend Mode | 0x8DC4C112 | +0x5C | 열거 (0x181EA0, 하위 바이트) |

- RGBA 성분 키 = `Hash127(":R"/":G"/":B"/":A", 필드 해시)` (0x1823F8 → Hash127 0x1758A0). 덤프의 `#24266bed/#24266be2/#24266bdd/#24266bdc` = 위 색 R/G/B/A (차이 11/5/1 = 'R'−'G', 'G'−'B', 'B'−'A').
- **Blend Mode 는 하위 바이트만** (06 문서 §5.2): 덤프 `1.0` (0x3F800000) = **0 블렌드**, `1.0000001` (0x3F800001) = **1 가산**. 이름이 맞는다 (…WhiteAdd 만 가산).
  35 문서 §4.6 의 "None = Blend Mode 1 가산" 은 틀렸다 (0 블렌드, 알파 0 이라 결과는 같다).

데이터 (전부):

| 번호 | 이름 | 위 RGBA | 가운데 RGBA | 아래 RGBA | 블렌드 | 위치 |
|---|---|---|---|---|---|---|
| 000 | None | 0 | 0 | 0 | 0 | R_PERMK |
| 001 | waterGel | (0.3, 0.7, 1, 0.6) | (0, 0.79, 1, 0.1) | (0, 0.25, 1, 0.05) | 0 블렌드 | ATHN04A, PAND00C/00D/02D/02F/02G/02H/02I/03F/03J |
| 004 | bloodGel | (0.4, 0, 0, 0.4) | (0.5, 0, 0, 0.28) | (0.3, 0.05, 0.08, 0.65) | 0 블렌드 | PAND03A |
| 008 | brightWhiteAdd | (1, 1, 0.75, 0.5) | (1, 1, 0.82, 0.75) | (1, 1, 0.75, 0.5) | 1 가산 | R_PERMK |
| 009 | dimWhiteAdd | (1, 1, 0.82, 0.1) | (1, 1, 0.82, 0.15) | (1, 1, 0.82, 0.1) | 1 가산 | R_PERMK |

### 1.4 무엇이 켜나 [확실: 코드]
카메라 관리자 렌더 0x1EF620 의 0x1EFA14 가 매 프레임 고른다:
1. **카메라 +0x910 ≥ 0** 이면 그 번호의 템플릿 (0x182D50 = 번호로 조회·없으면 적재) 을 그대로.
2. 아니면 **현재 카메라 상태 (+0x180) 의 색** (+0xA0/+0xB0/+0xC0, 위치 +0xD8/+0xDC/+0xE0) + 현재 카메라 정의 +0x50 번 템플릿의 Blend Mode.
   이 색은 카메라 활성화 0x1F3A38 (← 0x1EEE88) 이 **CAM 0xB0** (정의 +0x50) 번 템플릿에서 복사한다. 전환 중 보간 코드는 없다 (+0xA0 를 읽는 곳은 0x1EFAA4 뿐) → 카메라가 바뀌는 프레임에 바로 바뀐다.

CAM 0xB0 (12 문서 §3, 이전 [추정] → [확실]): 전 레벨 스캔 (`level:info` 의 `필터=`) — 0 이 아닌 카메라는 **수중 카메라뿐**:
ATHN04A SwimCam1/2/3/5/6, PAND00C/00D/02D/02F/02G/02H/02I/03F/03J 의 물속 관 카메라 (모두 1 waterGel, 103 개 중 95),
PAND03A WaterCam1/3/4/5·PoolLvrCam·PoolCam12/14/16 (4 bloodGel, 피 웅덩이). 그 레벨 WAD 에만 해당 템플릿이 있다.

+0x910 을 쓰는 곳은 0x195C90 (플래시) 하나와 초기화 0x1EDC40 (= −1, 카메라 관리자 생성 0x1EDBD0) 뿐 — **아무도 −1 로 되돌리지 않는다**:
- 포세이돈의 분노 (35 §4.6): Bright 0 → 첫 프레임에 +0x910 = 0 ("None"). 이후 그 레벨에서는 수중 waterGel 도 나오지 않는다 (원작 그대로) [확실: 코드, 실제로 수중에서 마법을 쓸 수 있는지는 미확인].
- **Arrow 명중** (0x2249A0 → 0x22411C): 0x195BA8(+0x164 Fog Template, +0x15C Bright, +0x160 Dark, +0x168 Flash Freq, f12 = +0x16C 지속 #8ae891cf).
  0x195BA8 은 지속을 0x29D7A8 에 두고, 매 프레임 0x204AB0 → 0x195D70 이 `0x29D7A8 −= 1/60`, ≥ 0 이면 0x195C90 (플래시 타이머: ≤ 0 이면 +0x910 = Bright, 타이머 += 1/Freq, 아니면 Dark).
  R_PERMK 제우스의 분노 화살: Arrow 002 thunderbolt (0/0/−1/1/지속 0 → 아무것도 안 함), **Arrow 003 thunderbolt ch1 (Bright 9 dimWhiteAdd, Dark 0, Freq 6, 지속 0.4)**,
  Arrow 004 ch2 (Bright 8 brightWhiteAdd, Dark 0, Freq 20, 지속 0 → 안 켜짐). ATHN08B Arrow 666 "EVI Bolt" (0/0/−1/1) 는 보이지 않는 값.
  → 차지 1 단계 번개가 맞으면 0.4초 동안 초당 6번, **한 프레임씩** 흰빛 가산 플래시 (3 번), 끝나면 +0x910 = 0 이 남는다.

### 1.5 엔진
- `CameraDef.filter()` (tools, CAM 0xB0), `CameraRig.filter()` = 현재 샷의 카메라 필터 번호 (엔진 Follow 카메라 = −1, 그리지 않음).
- `CameraFilter.load(tweaks, n)` (성분 키 해시, Blend 하위 바이트), `colorAt(u)`, `screenRange(정면 y, 세로 화각)`, `select(대체, 카메라)`.
- `CameraFilterRenderer`: 정점 버퍼 없는 화면 사각형 (`camfilter.vert/frag`), 조각마다 u = mix(위, 아래, 화면 y) → 구간 선형 색.
  GL 블렌드: 0 = (SRC_ALPHA, 1−SRC_ALPHA), 1 = (SRC_ALPHA, ONE), 2 = REVERSE_SUBTRACT (SRC_ALPHA, ONE). 깊이 무시. 알파 = 템플릿 A 그대로 (A·128/128).
- `GameScene.renderCameraFilter`: 3D 장면·이펙트 뒤, HUD 앞 [추정: 원작 노드 층 3·우선순위 7 이 HUD 보다 먼저라고 가정]. 템플릿 = R_PERMK + 레벨 WAD 튜닝.
  `-Dgow.nocamfilter` 끔, `-Dgow.camfilter=N` 번호 강제 (검증용).
- `LightningScreen` 을 0x195BA8/0x195C90/0x195D70 그대로 나눔: `start(fog, bright, dark, freq, 지속)`, 포세이돈은 지속 0 + 매 틱 직접, Arrow 명중은 지속 타이머.
  안개 대체는 0x195C90 이 돈 틱에만 (`fog()`). `ZeusBolt.flash` → `Magic.Sink.screenFlash` → `MagicFx` → `screen.start`.
- 카메라 필터 번호는 레벨마다 MagicFx 를 새로 만들어 −1 로 돌아간다 (원작 카메라 관리자 생성 0x1EDBD0) [추정: 생성 시점 = 레벨 적재].

[근사] 원작은 32 화소 열 Gouraud 띠 (정점 색 선형) 이고 엔진은 조각마다 같은 식 — 결과 같음. 원작 세로 화각은 가로 화각/화면비 (선형 근사), 엔진은 실제 세로 화각.

### 1.6 검증 (스크래치 `camf/`)
- `water_on.png` / `water_off.png`: ATHN04A `--arrive ATHN03E@1250,-900,800,0 --ticks 2` → SwimCam2 (필터 1). 위를 올려다보는 시선이라 위 색 쪽 (알파 0.3~0.5) 청록 덮개.
  (영웅은 엔진에 수영이 없어 떨어지는 중.)
- `dim_on.png` / `bright_on.png` / `arena_off.png`: ARENA08 틱 100, `-Dgow.camfilter=9/8` 가산 흰빛 (ch1 명중 플래시 한 프레임의 모양).
- `blood_forced.png`: PAND03A `-Dgow.camfilter=4` 붉은 덮개.
- 테스트 `CameraFilterTest` (5): 성분 키 = 덤프 해시, R_PERMK 템플릿·Blend 하위 바이트, ATHN04A SwimCam1 = 1·waterGel 값, PAND03A bloodGel,
  그라데이션·화면 범위·선택, ch1 명중 → 1프레임 플래시 3번 후 0 유지.

## 2. 블레이드 god 판 (`MagicFx.setGodBlades` / `godBladesOn`, `HeroRig.hideBlades`)

### 2.1 원작 [확실: 코드]
신들의 분노 진입 0x1DAD18(a1 ≠ 0) 이 양손 블레이드마다 0x1FA9A0, 해제가 0x1FA9D8 (Blades_Init 도 같은 함수) 을 부른다. 둘 다 0x1FAA10 (Blades_Lights) 에 이름 4개를 넘길 뿐:

| | 블레이드 GO | 궤적 | 사슬 고리 | 사슬 빛 |
|---|---|---|---|---|
| god (0x1FA9A0) | goMAIgodblade | MAT_godswordtrail | MAT_godchainlink | MAT_godchainglow |
| 보통 (0x1FA9D8) | goMAIblade | MAT_swordtrail | MAT_chainlink | MAT_chainglow |

0x1FAA10:
1. 현재 무기 WAD (`WAD_R_Wpn` = R_WPN<코스튬>_<단계>, 0x187B08) 에서 머티리얼을 찾아 (0x17A7B8) 궤적 (+0xB4 →+0x184 →+0x1C), 사슬 고리 (+0xBC), 사슬 빛 (+0xC0, +0xC8) 머티리얼 목록의 **마지막 원소를 바꾼다** (pop + push).
2. 블레이드 GO 교체: 영웅 (+0x80) 의 부착 슬롯 (+0x90 = 0 왼쪽 / 1 오른쪽) 의 이전 GO 행렬 (+0x20..+0x50) 을 보관 → 이전 GO 제거 (0x132768) →
   이름으로 새 GO (0x132508 조회, 없으면 0x1327B8 생성, 0x132648) → 영웅에 부착 (0x131460, 관절 −1) → 보관 행렬 복사 → 슬롯에 넣고 활성 (0x2021F0/0x201A08/0x201F28).
3. 라이트: 왼쪽 "LeftBladeLight" (GO 플래그 +0xF4 |= 0x100), 오른쪽 "RightBladeLight" (|= 0x200) 를 0x201F28(슬롯, 4, 라이트) 로 연결.

무기 WAD 내용 (R_WPN0_2 등 36 개 모두): `gomaigodblade` = MDL_MAIgodblade + 스켈레톤 maigodblade (관절 3, 보통 판과 같은 구조, 칼끝 효과 관절만 위치 다름) +
**ANM_maigodblade** (1.5초 반복, 형 10 = 재질 애니만, 회전·이동 트랙 0) + FXC_BDepoly6/3 (PTC_flame6/3 불꽃), FXC_BDEsparkemit/0/2, **FXC_EGemit + FXC_EGgrav** (god 판 전용).
보통 `gomaiblade` 는 FXC_Egemit1/2 이고 EGgrav·sparkemit2 가 없다. 텍스처 Bstage5TX (불붙은 금빛 칼날).

### 2.2 엔진
- `GameScene` 이 영웅을 만든 뒤 `magicFx.setGodBlades(R_WPN<c>_<n>, 아르테미스가 아니면 참)` → 무기 WAD 를 이펙트 라이브러리에 등록.
- `MagicFx.godMode(true)`: 갑옷 8개와 함께 `goMAIgodblade` 를 **lWeapOH / rWeapOH** 에 부착 (수명 무한, 반복 act = ANM_maigodblade 재질 애니, 이미터 포함).
  위치는 BladeModels 와 같은 무기 로케이터 [근사: 원작은 사슬 물리가 둔 이전 블레이드 GO 행렬을 복사; 엔진에 사슬 물리 없음].
- `HeroRig.hideBlades = magicFx::godBladesOn` → 신 모드 동안 보통 블레이드 (BladeModels) 를 그리지 않는다. 해제 (godMode(false)) 에서 GO 를 멈추면 보통 판이 다시 보인다.
- 아르테미스의 검을 들었으면 바꾸지 않는다 [추정].
- 테스트 `EffectTest.godBladeGoInEveryWeaponWad`: 36 개 무기 WAD 모두 goMAIgodblade·goMAIblade 해석, 무한 수명.
- 스크린샷: `camf/god_t200.png` / `god_crop.png` (ATHN01A, `-Dgow.giveAll -Dgow.script=L3:1:0.2,R3:1:0.2`, 틱 200: 등의 블레이드가 금빛 불꽃 판), 비교 `nogod_t200.png` / `nogod_crop.png`.

### 2.3 남은 것
- 궤적 (MAT_swordtrail/godswordtrail) · 사슬 (chainlink/chainglow) 은 엔진에 렌더가 아예 없다 → god 머티리얼 교체도 적용할 곳이 없다. "God Mode Trail Tint" 도 같은 이유로 미적용.
- LeftBladeLight / RightBladeLight (LGT) 라이트 연결 미구현.
- 보통 블레이드의 FX (gomaiblade 불꽃·불티 이미터) 는 엔진이 BladeModels 로 메시만 그려 나오지 않는다 (god 판만 이미터가 나온다).
