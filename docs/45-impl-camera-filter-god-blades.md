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
  위치는 §3 의 블레이드 행렬 (`MagicFx.bindBlades` ← `HeroRig.bladeWorld`, 원작처럼 이전 블레이드 GO 행렬 = 사슬 객체가 둔 행렬).
- `HeroRig.hideBlades = magicFx::godBladesOn` → 신 모드 동안 보통 블레이드 (BladeModels) 를 그리지 않는다. 해제 (godMode(false)) 에서 GO 를 멈추면 보통 판이 다시 보인다.
- 아르테미스의 검을 들었으면 바꾸지 않는다 [추정].
- 테스트 `EffectTest.godBladeGoInEveryWeaponWad`: 36 개 무기 WAD 모두 goMAIgodblade·goMAIblade 해석, 무한 수명.
- 스크린샷: `camf/god_t200.png` / `god_crop.png` (ATHN01A, `-Dgow.giveAll -Dgow.script=L3:1:0.2,R3:1:0.2`, 틱 200: 등의 블레이드가 금빛 불꽃 판), 비교 `nogod_t200.png` / `nogod_crop.png`.

### 2.3 남은 것
- ~~궤적·사슬 렌더, god 머티리얼·God Mode Trail Tint, 보통 블레이드 불꽃 이미터~~ → §3.
- LeftBladeLight / RightBladeLight (LGT) 라이트: 켜고 끄는 규칙은 §3.5 에서 해독, 엔진에 동적 라이트가 없어 미구현.

## 3. 블레이드 사슬 · 궤적 (`game/BladeChain`, `render/BladeChainRenderer`, `HeroRig.tickBlades/drawChains`)

### 3.1 객체와 생성 [확실: 코드]
- 무기 부착 (0x201170, 무기 = `/TweakTemplates/Attachment/000`): 블레이드 GO 2 개를 0x2015B8 (GO 생성·영웅에 부착 0x131460, 슬롯 0x34 바이트) 로 만들고,
  **Blades_Init 0x1F9C38** (0xF0 바이트 객체, 왼쪽 → 슬롯 +0x8, 오른쪽 → +0x3C) 을 부른다. (0x2015B8 은 GO 부착 함수이고 Blades_Init 은 0x1F9C38.)
- Attachment/000 문자열 (오프셋 = 덤프 순서): +0x18/+0x30 goMAIblade, +0x48/+0x60 l/rWeapIH, +0x78/+0x90 l/rWeapOH, +0xA8/+0xC0 **l/rChain**,
  +0xD8/+0xF0 **l/rChainW**, +0x108/+0x120 left/rightBladeBack. Blades_Init 이 관절 번호로 저장: +0x84 lChain (손목, lWrist 자식), +0x86 lChainW (lWeapOH 자식),
  +0x88 lWeapIH, +0x8A lWeapOH, +0x8C leftBladeBack, +0x8E Pelvis, +0x90 = 0 왼쪽 / 1 오른쪽.
- 렌더 객체: +0xB0 (형 0xB, 구 3 개 = 베지어 t 0.2/0.7 + 칼끝, 블레이드 로컬 — 충돌용 [추정]), +0xBC/+0xB8 **MAT_swordtrail** 띠 1 개 × 16 정점 (PRIM 0x54 삼각형 띠, ABE),
  +0xC4/+0xC0 **MAT_chainlink**, +0xCC/+0xC8 **MAT_chainglow** (띠 2 개 × 68 정점), +0xDC/+0xD8 MAT_stencil (그림자, 띠 수 = (0x88·4+0x90)/0xF4+1).
  끝에 0x1FA5B0 (초기화) 과 0x1FA9D8 (보통 머티리얼 = §2.1 표) 을 부른다.
- 튜닝 `/Player/` (Player_RegisterTweaks 0x1D5B8C…): **Blade Damping 0.4** (0x29F294), Blade Scale On Back/In Hand/Out Hand 1 (0x29F298/9C/A0),
  Blade Collision Tolerance 0.5, **Segment Length 0.25**, **Link Diameter 0.13**, **Glow Diameter 0.18**, Shadow Diameter 0.13 (0x29F2A8…B4, 미터 = ×16 단위).
  `Weapon Level n/Trail Tint RGBA` (0x3040C0 + 단계·0x50 +0x00) = (1,1,1,0.8) 전부, `God Mode Trail Tint` (+0x10) = 단계 0: 0, 1–3: (1,1,1,1), 4–5: (1,0,0,1).
  `Costume n/Weapon Length` / `God Mode Weapon Length` (0x3042A0 + 코스튬·0x20 +0x18/+0x1C) = 0.7 등.

### 3.2 블레이드 행렬 (0x1FB460, 매 프레임 ← 0x1FDB18) [확실: 코드]
"사슬 물리" 는 없다. **블레이드 행렬이 애니의 관절 행렬을 늦게 따라가고**, 사슬은 그 행렬과 손목 사이 곡선일 뿐이다.
1. 상태 +0x92 (0 등 / 1 손 / 2 손 밖): 현재 기술 flags (+0x0E) & 0x80 이면 유지. 아니면 dIH = |lWeapOH − lWeapIH|, dBack = |lWeapOH − leftBladeBack| (월드),
   기술 중이면 dIH < **5.6** → 1, dBack < 5.6 → 0, 아니면 2. 기술이 없으면 1 → 0 (dBack < 5.6), 2 → 1 (둘 중 하나 < 5.6), 0 은 그대로.
2. 목표 = 0: 등 관절, 비율 +0x98 = 1, 배율 Blade Scale On Back / 1: lWeapIH, 1, In Hand / 2: **lWeapOH, Blade Damping 0.4**, Out Hand. 0 이면 사슬 숨김 (0x1FDAE8), 1·2 면 보임 (0x1FD9F8).
3. 이전 블레이드 행렬 (정규직교 0x1853E8) → 쿼터니언 (0x1840D8), 목표도 같이 → **slerp(이전, 목표, clamp(비율·dt·60))** (0x184388), 위치 = 이전 + (목표 − 이전)·같은 t.
   배율 +0x9C += (+0xA0 − +0x9C)·0.1. 결과 행렬 (+0x00) 에 배율을 곱해 **블레이드 GO 행렬** (GO +0x20…) 로 쓴다 — 0x1FAA10 이 GO 를 바꿀 때 복사하는 그 행렬.
4. 0x1FAFC8: 골반 높이에서 블레이드까지 레이 → 벽에 막히면 블레이드를 당긴다 (Blade Collision Tolerance) — 엔진 미구현 [근사].

### 3.3 사슬 곡선 · 사슬 고리 (0x1FBD04…, 0x1FC870) [확실: 코드]
- P0 = lChain 위치, d0 = −(lChain Z 축) 정규화. P3 = lChainW 를 lWeapOH 기준으로 옮겨 블레이드 행렬에 붙인 위치 (inv(lWeapOH)·lChainW 를 블레이드에 곱함), d3 = 그 Z 축.
  L = **0.33·|P3 − P0|**, P1 = P0 + d0·L, P2 = P3 + d3·L → **3차 베지어**. (블레이드가 늦게 따라가면 d3 가 돌아 사슬이 휜다.)
- 마디 수 n = (현 + 제어 다각형)/2 ÷ (Segment Length·16). **n ≤ 1 이면 사슬·빛·그림자 숨김** (0x1FDA08).
- t = 1 (블레이드) → 0 (손목), 1/n 씩 (마지막 0). 옆 벡터 = 정규화(cross(P − 카메라 위치, 접선)), 첫 표본 접선 d3, 다음부터 P − 이전 P.
  사슬 고리 정점 P ± 옆·(Link Diameter·16/2), UV (n/16·(1−t), 0/1) — TXR_chainlink 512×32 에 고리 16 개, 알파 블렌드.
  사슬 빛 (P + 카메라 뒤쪽 축·1 단위) ± 옆·(Glow Diameter·16/2), UV (1−t, 0/1), 가산, **색 = clamp(n/16, 0, 1)** (긴 사슬일수록 밝다).
  정점 버퍼 상한 = 띠 2 × 68 (넘치면 잘림). 그림자 (MAT_stencil, +0xA8 bit 1) 는 엔진 미구현.

### 3.4 궤적 (0x1FC070) [확실: 코드, 표본 위치·UV]
- 시간 이력 리본이 아니다. **같은 베지어의 t = 0.2…1 부분을 부채꼴로 채운** 16 정점 (0x141818 = 동적 메시 정점 포인터, 이력 없음):
  띠 1 = [A,B,C,A,D,A,E] (t = .2,.325,.45,.575,.7), 띠 2 = [E,F,G,E,H,E,I,E,T] (t = .775,.85,.925,1, T = 칼끝 = P3 − 블레이드 Z·Weapon Length·16).
  첫 두 정점 ADC → GS 삼각형 띠 = A 중심 부채꼴 + E 중심 부채꼴 + (I,E,T). (A,B,C)/(B,C,A) 처럼 겹친 삼각형도 그대로 (가산 2 배).
  U = 0, .125, .25, .375, .5 / .5 + f·{0, .125, .25, .375, .5}, 칼끝 1 (f = |I−E| / (|I−E| + 블레이드 길이)), **V = 1** (텍스처 아래 줄).
  → 사슬이 휜 만큼 (현과 호 사이) 불꽃 면이 생긴다. 색 = Trail Tint (god 이면 God Mode Trail Tint, 0x12E4D0 ← 0x3040C0[+0x10 if god]), MAT_swordtrail 가산.
- 켜짐: 렌더 객체 +0xB4 의 +0x158 (0x12E4C8) — 0x204B38 이 combatant **+0x1AC 0x8 (왼쪽) / 0x10 (오른쪽)** (기술 이벤트 **17 TRAIL_LEFT / 18 TRAIL_RIGHT**,
  10 문서 이벤트 표) 에 따라 부착물 종류 0·3·4 를 켜고 끈다 (0x201C78 / 0x201DD8). □ 콤보 기술들은 이벤트가 [0, 1] 전체라 기술 내내 켜져 있다.

### 3.5 블레이드 GO 부착물 종류 (0x201A08) · 라이트 [확실: 코드]
| 종류 | 대상 | 켜짐 |
|---|---|---|
| 0 | 형 0xB 컴포넌트 (+0x158 = 1/0) | 궤적 중 |
| 1 / 2 | 파티클 이름 "blooddrip" / "bloodspray" | 다른 코드 |
| 3 | 파티클 이름이 **"flame"** 으로 시작 (PTC_flame6/3 의 "flame6Shape") | **궤적 중만** (초기 끔) |
| 4 | LeftBladeLight / RightBladeLight (0x201F28, GO +0xF4 |= 0x100/0x200) | 궤적 중: 세기 +0xE0 += 4/60 (0.25 초에 1), 끄면 −2/60 (0.5 초에 0) |
불티 (BDEsparkemit)·EG 이미터는 어느 목록에도 없어 늘 켜져 있다.

### 3.6 엔진
- `BladeChain`: 위 상태·slerp·배율 (`step` = 프레임 비율을 dt 로: 1 − (1−r)^(dt·60)), 베지어·마디·표본 (`samples`, 상한 68), 궤적 정점 (`trail`, `stripTriangles`).
  `HeroRig.tickBlades(dt)` 를 GameScene 이 매 틱 (전투·잡기·내비게이션 뒤, MagicFx 앞) 부른다. 관절 = `HeroRig.jointWorld` (영웅 월드 × 관절).
- 블레이드 메시 (`BladeModels.drawAt`) 와 god GO 앵커 모두 블레이드 행렬. 렌더 보간은 (이번 프레임 영웅 행렬 × 틱 영웅 행렬⁻¹) 로 보정.
- `BladeChainRenderer`: 무기 WAD 의 MAT_chainlink/chainglow/swordtrail (god 이면 MAT_god*), 파티클 셰이더, 깊이 검사만 (쓰기 없음), 컬링 끔. 영웅 다음에 그린다 (거울 패스에는 없음).
- `MagicFx`: 보통 블레이드도 goMAIblade GO 를 붙여 이미터만 (`hideMesh`, 메시는 BladeModels), god 판과 같이 `emitterOff` = flame 이미터는 `Combatant.trail(side)` 일 때만.
- `-Dgow.nochain` 사슬·궤적 끔 (비교용).
- [근사] 0x1FAFC8 블레이드 벽 충돌, 그림자 띠, BladeLight 라이트 (엔진에 동적 라이트 없음), CombatFileSet "Trail Left/Right" 세트 플래그, GS CLAMP 미확인이라 궤적 V 는 아래 줄 텍셀 중심.

### 3.7 검증 (스크래치 `blade/`)
- `combo_115/125/142.png` (ATHN01A, `-Dgow.script=SQUARE:1.0:0.05,SQUARE:1.35:0.05,SQUARE:1.7:0.05`): 손목에서 던진 블레이드까지 휘는 사슬 고리·빛, 블레이드 불꽃 (궤적 중).
  `crop_ga_125.png` / `z_ga125b.png` (`-Dgow.giveAll`, 단계 5): 아래로 처졌다 올라가는 사슬. `combo_105` vs `nochain_105`: 기술 시작 직후 (블레이드가 아직 등) 손목–등 사이 궤적 부채꼴.
- `god5_200.png` / `crop_god5_200.png` (`-Dgow.giveAll -Dgow.script=L3:1:0.2,R3:1:0.2,SQUARE:1.6:0.05,…`): god 판 파란 사슬 (MAT_godchainlink) 과 goMAIgodblade 가 블레이드 행렬을 따름.
- 테스트 `BladeChainTest` (5): 제어점·번스타인 계수·길이, 마디/표본/옆 벡터·숨김·상한, 궤적 정점 (U·칼끝·f), 상태 전환·Damping, 실제 □ 콤보에서 왼쪽 블레이드 손 밖·로케이터보다 늦음·사슬 여러 마디·이벤트 17.
  `EffectTest.bladeFlameEmittersAreGated`: goMAIblade/goMAIgodblade 에 flame 이미터 ≥ 2, 그 밖 이미터 존재.
