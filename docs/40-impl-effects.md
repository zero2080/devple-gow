# 40. 구현: 원작 이펙트 GO (마법·전투 이펙트 이름 → GO → FXC/PTC/MDL/ANM, 부착·수명)

분석 기반: [16 문서](16-environment-fx-format.md) §2 (FXC_/PTC_), [31 문서](31-impl-environment-fx.md) (파티클 런타임),
[35 문서](35-impl-magic-minigames.md) §7 (이전 근사), [10 문서](10-combat-system.md) §2.7–2.8 (Attack MFX, 애니 이벤트).
패키지: `devple.gow.tools.fx.EffectLibrary` (해석, 헤드리스), `devple.gow.engine.fx.EffectSystem` (런타임), `fx.MagicFx` (연결).

신뢰도: [확실] = 실행 파일 코드·데이터 일치, [추정] = 정황/패턴, [근사] = 엔진이 대신 정한 값.

```bash
./gradlew :engine:test --tests '*EffectTest*'                                   # 해석·수명 (헤드리스, 5)
./gradlew :engine:run -PgowFxLog -PgowMagicStage=2 "-PgowScript=LEFT:0.3:0.05,L2:0.6:3,CIRCLE:1.5:0.05" \
    --args="--hero --enemy R_MIN --screenshot shot.png --ticks 150"           # 메두사 Nuke (연습 씬, 단계 2)
./gradlew :engine:run -PgowGiveAll "-PgowScript=LEFT:0.5:0.1,L2:1.0:3,SQUARE:1.4:2" --args="--game ATHN01A --skip-movies"   # 게임 씬 광선
```
- `-PgowFxLog`: 재생마다 `이펙트 goX → 오브젝트 @WAD (길이|반복)` 출력. `-PgowMagicStage=n`: 연습 씬 마법 단계 n + MP 레벨 3.
- 연습 씬(`--hero`)도 이제 `-PgowScript` (버튼:시작초:누름초) 를 읽는다 (35 §9 와 같은 문법).

## 1. 이름 → GO 인스턴스 → 오브젝트 [확실]
튜닝/스크립트가 쓰는 이펙트 이름(`"goEyeBeam"`, `"goSklBlood"` …)은 **GO 인스턴스** 리소스(CXT 매직 0x00020001, 0x5C 바이트) 이름이다.
WAD 안에서는 소문자 (`goeyebeam`), 조회는 대소문자 무시 (Hash127Upper 계열). 인스턴스 +0x04 char[24] 가 **오브젝트**(CXT 0x00040001, `eyebeam`)를,
+0x3C 가 균일 스케일을 준다 (`goboltchargeup` 단계 0 = 0.25). 오브젝트 그룹의 참조가 이펙트 구성 요소다 (`Skeleton.groupRefs`):

| 참조 | 역할 |
|------|------|
| `ANM_x` | 오브젝트 애니 act 1개: 관절 회전 = 메시 변형(빔 꼬임, 충격파 회전), **타입 10 f32 채널 = FXC 파라미터 곡선** (§3) |
| `MDL_x` | 메시 (빔·충격파·번개 가지·영혼). 머티리얼 ANM (UV 스크롤·불투명도) 이 대부분 붙는다 |
| `FXC_x` (서브타입 1–10) | 이미터. +0x54 모양 이름 → 같은 WAD 의 `PTC_` (+0x54 같은 이름) |
| `FXC_x` (0xC) | 필드 (중력 등) |
| `SEM_x` | 사운드 이미터: 슬롯 1 소리를 이펙트 생성 때 재생 (§4.4). 같은 이름이 여러 번 정의된다 → 오브젝트 그룹 바로 앞의 정의 |

정의 위치 (전부 `EffectLibrary.add` 가 인스턴스를 훑어 등록, 나중에 더한 WAD 가 같은 이름을 덮음):

| WAD | 이펙트 (오브젝트: 구성) |
|-----|------------------------|
| R_PERMK | sklblood (ANM 3채널 + FXC 3 + 중력), bloodspot, rundust (ANM 1채널, 반복 0.2초), fleshslice/armorslice/metalslice/woodslice/rockslice/…, *land (착지 먼지), splash, maisweffect, maishockwave(MDL), combo3fexplode(MDL+FXC 6), fxfirepath(MDL), strikegrp/flashgrp(MDL), doublejump, maifreeze, stoneherobrk, 오브(healthorb …) |
| R_MEDHD0..2 | eyebeam (MDL 17관절, ANM 반복 5초), medheadflash (FXC 1 + 채널, 1회 0.333초), medheadnuke (MDL 2관절, 반복 2초), mewh (메두사 머리 MDL_MEWH 15관절, act main/burn/off/On, FXC_MHBemit 커브 이미터) |
| R_THBLT0..2 | bolt (MDL + FXC, 반복), stdbolt (FXC 상수 150/초 = 꼬리), bolthit (MDL 25관절, 1회 0.8초), boltchargeup |
| R_RDEAD0..2 | dedred (영혼 MDL + FXC 60/초), deadhit / deaddie (FXC + 채널, 1회 1초) |
| R_LGHTN0..2 | lightningattackmain (MDL 52관절 + FXC 3), lightningsinglebolt (MDL, 반복 0.2초), lightningradius (MDL) |

## 2. 이펙트 이름이 나오는 곳
| 출처 | 필드 | 예 | 근거 |
|------|------|----|------|
| `/Player/MedusaHead/Stage n Effect` / `Flash Effect` | 문자열 | goEyeBeam / goMedHeadFlash | 단계 표 0x2FF7C0 (0x60/단계) +0x30 / +0x48 [확실: 0x1969F0, 0x196968] |
| `/TweakTemplates/Arrow/nnn/` | GO Name / Trail Name / Hit Effect | goBolt / goStdBolt / goBoltHit | 화살 템플릿 [확실: 키] |
| 표 0x29D82C.. | 상수 | goDedRed / goDeadHit / goDeadDie | 하데스 [확실] |
| sub_001948F0 / sub_00195B00 | 상수 | goLightningAttackMain, goLightningRadius, goStrikeGrp / goLightningSingleBolt | 포세이돈 [확실: 문자열 참조, 동작 추정] |
| PlayFX 템플릿 | Effect Name, Joint Name | 035 "Hero Blood Top" goSklBlood @head, 101 "Hero Plume 7A" goCombo3fExplode @synchJoint | §4.2 |
| Concussion 템플릿 | Effect Name, Joint Name | 000/001/003/004 goMedHeadNuke @synchJoint, 007–010 goRunDust | §4.3 |
| MFX 템플릿 (재질) | #405ce7fc (피), Jump Land Effect, Slice Hit Name, Run Dust Name | 500 Flesh: goSklBlood / goFleshSlice, 020 Rock: goDirtPuff / goRockLand / goRockSlice / goRunDust | §6 |

## 3. FXC 파라미터 곡선 = ANM 타입 10 채널 [확실: 데이터 일치]
이펙트 이미터의 FXC +0x20 (초당 생성 수)는 대부분 **0** 이다. 대신 오브젝트 ANM 이 타입 10, unk10 = 4 (f32) 상태 디스크립터를 이미터마다 하나씩 가진다.
- **디스크립터 순서 = FXC 노드 헤더 +0x0A s16 (링크 번호)**. sklblood: SKLemit 0 / Sklemit1 1 / Sklemit2 2 ↔ 디스크립터 3개.
  - **정정 (38 §21)**: 링크 = act 의 **상태 디스크립터 번호 그대로** (타입 0 회전·타입 5·타입 10/unk10 2·1·타입 11·트랙 없는 디스크립터도 번호를 차지).
    이전 구현은 "트랙 있는 타입 10/unk10 4 디스크립터의 순번" 이라 sklblood 처럼 앞에 다른 디스크립터가 없는 act 에서만 맞았다.
    증거: R_PERMK 상자 chestGrab 의 곡선 디스크립터 = 3/6/9/12 = MSemit1/HSemit1/PSemit1/WSemit1 링크, combo3fExplode (0·10/4 빈·10/2·10/4×3) 의 이미터 링크 3/4/5 = 마지막 셋,
    ATHN01B 통 (0·5·10/2·10/1·10/4·11) 링크 4. R_PERMK·ATHN01B·ATHN03A·R_* 전수: 곡선을 얻는 이미터 962 (이전 804), 이전에만 얻던 17 [확실: 데이터 일치].
    `Animation.channelGroups` 는 디스크립터마다 자리 (빈 목록 포함), 빈 자리는 곡선 없음 규칙 (FXC 상수·1회성 0.1 초) 으로.
- **스트림 k = FXC P 블록(파일 0x84) 의 k 번째 float**: 3 = 퍼짐(P+0x0C), 4 = 속력(P+0x10), 5 = 속력 랜덤, 8 = 초당 생성 수(P+0x20).
  증거: sklblood 채널 0 프레임 0 의 스트림 3 = 0.1117, 스트림 4 = 2.869 → FXC_SKLemit 의 spread 0.11 / speed 2.87 과 같다.
- 값: 절대 f32 키 (1/30 초), 트랙의 [start, start+count−1+hold] 만 정의. 한 디스크립터에 같은 스트림 트랙이 여러 개면 구간을 나눠 가진다
  (medheadflash: 프레임 0–1 + hold 8 = 5000, 프레임 9–10 = 5000→0).
- 예: sklblood 생성률 0 → 280 → 0 (0.4초), rundust 0 → 240 → 0 (0.2초 반복), deadhit 500 → 0, medheadflash 5000/초 × 0.3초.
- 채널이 없는 이미터(헤더 링크 −1)는 FXC 상수 생성률: stdbolt 150/초, dedred 60/초 — 소유자가 끌 때까지.
- 링크 번호는 있는데 해당 디스크립터가 없는 1회성 (bolthit 단계 2 의 emitter1 600/초): 처음 0.1초만 생성 [근사].

- **정정 2 (2026-10-24, 05 §2.8.1)**: 링크 = **원작 채널 id** = 세트 데이터 타입 항목 p2 의 앞 합 (0x100EA8 → 채널 +0x1C, 0x130240 이 링크와 비교) = act 디스크립터 번호
  **(디스크립터 표가 Σ p2 개일 때)**. 예전 파서는 디스크립터를 항목 수만큼만 읽고 항목 형으로 표시해 타입 0 (p2 3) 뒤가 2 칸씩 밀렸다 — 위 combo3fExplode 의 "10/4 빈 · 10/2" 는
  실제로 타입 0 의 이동 · 스케일 디스크립터, ATHN03A GateExplosion 링크 4 (FBFemit2GE, 생성률 150) 는 "타입 11" 로 보여 채널이 없었고, 메두사 머리 mewh "off" 의 링크 3
  (Σ p2 = 4 째) 은 읽히지도 않았다. 원작 배치 = `Animation.recordChannels` · `recordChannel` (관절 이미터 이펙트 — Animator·하늘 GO — 만 쓴다, `EffectDef.rate(…, records)`);
  나머지 이펙트는 아직 예전 배치 (`channelGroups`, 전환은 후속 — 05 §2.8.1). GateExplosion 링크 22–25 (폭발 이미터) 는 채널 13 개 밖이라 원작에서도 곡선이 없다 [확실: 데이터].

구현: `Animation.channelGroups` (디스크립터별 트랙), `Animation.channel(group, stream, t, def)`, `EffectDef.rate/param`.

### 3.1 PTC VU 프로그램 3 상수 [확실 — 전체 해석은 16 문서 §2.4]
VU 3 (연기·불·피) 모양의 상수 qword 배치 (16 문서 §2.3 의 "색 곡선 휴리스틱" 을 대체):

| qword | 내용 | 예 SKLpart (피) | dustpart (먼지) |
|-------|------|----------------|-----------------|
| q0 | (1,1,1,0) 표지 | | |
| q1–q4 .w | 회전 범위 ±π, 2π | | |
| q5 | 색 RGBA 상수항 (0x80 = 1.0) | (63.2, 0.2, 0, 200) = 어두운 빨강 | (94.8, 88.2, 83.5, 6.3) = 옅은 회색 |
| q6 | 색 RGBA 1차항 (u = 나이/수명) | (−66.2, −0.3, 0.1, −286) | (0,0,0,−4.9) |
| q9 | 가속 xyz (월드/초²) | (0, −112, 0) | (0, −32, 0) |
| q10.w / q11.w | 크기 상수·1차항 | 0.87 / 8.42 | 1.43 / 6.29 |

**q9 = 그 이펙트 필드 합 × 16** (SKLgrav −7·(0,0,1) → −112, dustgrav −2 → −32, Fsgrave 9.8·(0,0,−1) → −156.8) — 필드 방향의 수직축이
Z 라는 것의 확인이다 (`EffectLibrary.fieldAccel`: (x,y,z) ↦ (x, z, −y)·16). q10.w/q11.w = **월드 반폭** 상수·1차항 (t 는 초) [확실, 16 §2.4]. 이 표는 SKLpart 배치의 예일 뿐이고 실제 위치·크기는 블록의 표 T0..T7 이 정한다.
이제 엔진은 모든 PTC 를 `PtcVu` 식 해석기로 그린다 (VU 종류 0–5, 7–9; 5 = 열기 아지랑이는 프레임 복사 굴절, 31 §5).

## 4. 이펙트 객체 런타임 (원작 0x227xxx–0x228xxx) [확실: 구조, 추정: 세부]
`sub_002272C8(effect, goTemplate, matrix, owner, joint)`: GO 를 복제(sub_00132530)하고 월드 행렬을 넣는다. owner 와 joint ≥ 0 이면
행렬 = 로컬 · owner 관절 월드 (sub_00131618 → sub_00185550), 이어서 **sub_00131460(owner, go, joint) 로 관절에 부착** (부모 연결).
생성자 계열 (0x1C 바이트 객체, vtable 0x2BBA28/0x2BBA58):

| 생성자 → 초기화 | 쓰는 곳 | 성격 |
|----------------|--------|------|
| sub_00228558 → 0x227AA8 | MFX 타격 0x221810, 베기 0x221A90/0x221EA8 | 위치 + 방향, 1회 |
| sub_002283A8 → 0x2279D0 | 메두사 섬광 0x196968, 하데스 0x19D258/0x19D460, 0x1FE518, 화살 0x2244D8 | 행렬 + (소유자, 관절), 1회 |
| sub_00228428 → 0x227788 | SCR_PlayEffect 0x19A720, 하데스 0x19F8E0 | 이름 → 1회 |
| sub_00228480 → 0x227840 | Concussion 0x191720 | 반경 연동 |
| sub_00227148 + 0x227788 | 메두사 광선 0x1969F0 (0x10 바이트, 1회 콜백 없음) | **지속**: 소유자가 지운다 |

**수명** (0x228210 / 0x2282D0) [확실]: 1회성 객체는 GO 애니 컴포넌트(색인 0x3304E4)의 재생 핸들 최대 64개를 받아(sub_001007C8) 각 핸들 +0x40 에
자기 콜백을 걸고 재생을 0초부터 시작(sub_00100578). 콜백마다 카운트 −1, 0 이 되면 +0x14 콜백 호출 후 플래그 bit0 (= 삭제). 즉
**수명 = 오브젝트 애니 act 가 끝날 때** (반복 act 이면 끝나지 않으므로 지속형으로만 쓴다). 지울 때 0x227548 이 GO 를 제거(sub_00132768) — 이미 나온 파티클은 남는다.
배율 sub_00227D80: GO 컴포넌트 중 종류 < 0xB 인 것(파티클 이미터) +0xB4 = **생성률** 배율 (0x122B00) [확실]. MFX 0x221810 의 값은 §6 (주 대상 1 / 그 외 0.125).
엔진: `Effect.rateScale` (이미터 rate × rateScale).

엔진 (`EffectSystem`):
- `play(name, Anchor, life)` → `Effect`. `Anchor` = 매 프레임 기준 행렬(관절 부착이면 관절 월드, 아니면 고정). false 를 돌려주면 소유자 소멸 → 종료.
- 수명 = `EffectDef.lifetime()`: 1회성 act 길이, 반복·애니 없음 = ∞ (`stop()`). 길이 0 정지 act (dedred) 는 지속으로 본다 [추정].
- 메시: `AnimatedModel` + 오브젝트 act 포즈 + **머티리얼 ANM** (새 `draw(..., fxTime, alpha)`: 레이어 0 UV 스크롤·hdr8 불투명도). 월드 = 기준 × 인스턴스 스케일 × 사용자 배율.
- 이미터: FXC 로컬 행렬의 회전(스케일 제거)·dir → 기준 회전. 로컬 이동은 |t| ≤ 64 만 (Maya 장면 좌표가 남은 slice 이미터 (407,0,1344) 는 원점) [근사].
  `ParticleSystem.Emitter` 에 프레임별 덮어쓰기 필드 (`rate`, `speed`, `speedRnd`, `cone`, `sizeScale`) 를 추가했다.
- 파티클 정의: `ParticleDef.withPtc(shape)` → 색·반폭·위치·회전·블렌드를 PTC VU 블록 식으로 (16 §2.4) [확실]. 아래 근사(크기 |PTC+0x74|/k, 0x8000 = 알파)는 `GOW_PTCVU=off` 비교용으로만 남음 —
  블렌드 = PTC 플래그 0x8000 이면 알파(피·먼지), 아니면 가산(불·번개·반짝임) [추정], 텍스처 = PTC 뒤 MAT_ 첫 레이어.
- `ParticleSystem.render` 는 이제 살아 있는 파티클에서 정의를 모은다 (이미터를 지워도 남은 파티클을 수명까지 그림).

### 4.1 마법 (MagicFx)
| 효과 | 원작 | 엔진 |
|------|------|------|
| 메두사 머리 | SCR_ShowMedusaHead (0x197280, 이벤트 해시 0xE5D96CF1) → 0x1970D0: `/Player/MedusaHead` "HeadName" (goMewH) GO 를 만들어 영웅의 "JointName" (rWeapIH) 관절에 부착 (sub_00131460), act "Off" (0x196B18). 광선 시작 때 "On" (0x1972E8). 참조 수 0 (스크립트 끝, 0x197248) 이면 제거 [확실] | 영웅 rWeapIH 관절 월드 그대로 (회전 포함), 광선 중 "On" / 아니면 "Off", 기술이 바뀌고 광선도 꺼지면 제거 [추정: 스크립트 수명 = 기술]. 첫 act "main" (0.03초)은 수명이 아니다 |
| 메두사 광선 | goEyeBeam 을 **영웅** 관절 0x3368C8 에 부착 — 0x3368C8 은 0x1964D0 이 영웅 스켈레톤에서 찾은 rWeapIH 번호 (머리와 같은 관절) [확실] | rWeapIH 위치 + 조준 yaw (메시 −Z 400 = 정면) [근사: 관절 회전 대신 조준 방향] |
| 메두사 섬광 | goMedHeadFlash 1회 (행렬 = 머리) | 손 위치·조준 방향, MHESemit 5000/초 × 0.3초, 로컬 회전 Y→−Z |
| 메두사 Nuke | Concussion 0/1(동결) + 3/4(FX) 의 Effect goMedHeadNuke @synchJoint, Scale X/Y/Z 플래그 | 템플릿마다 재생, 배율 = 반경(TWK m)×16/27 (메시 반경 27) 을 Start→End 로 Duration 동안, 이후 0.5초 페이드 [근사] |
| 제우스 차지 | SCR_ShowThunderbolt (0x19FF60, 0x8D7EAE7F): 이벤트 인자 1 이면 LWEAPIH, 아니면 RWEAPIH 에 goBolt + goBoltChargeUp (0x19FE10) 부착 [확실] | 같은 관절에 부착, SCR_ThrowThunderbolt (0x96EDE590) 또는 기술 변경에서 뗀다 [추정] |
| 제우스 | Arrow GO Name goBolt + Trail goStdBolt 를 화살에, 명중 Hit Effect goBoltHit | 화살 위치·속도 방향(+Z) 부착, 화살 소멸 시 정지, 명중 1회 0.8초 |
| 하데스 | goDedRed 영혼, goDeadHit, goDeadDie | 영혼 위치·속도 방향 부착 (꼬리 −Z) |
| 포세이돈 | SCR_Lightning (0x1948F0): 시작 때 goLightningAttackMain + goLightningRadius. 매 프레임 AttackMain 행렬 = 단위 회전 + 영웅 위치 (0x194C08, 회전 없음), Radius = 같은 행렬 축 × s (0x195500), s (0x3368B8, 시작 1) += (f23 − s)·0.05. 타이머 0x3368A8 (시작 1) 이 (1/60)·연타율·**From Above Bolt Freq** (전역 short 0x29D780 = 20) 씩 줄어 ≤ 0 이면 1 로 되돌리고 goStrikeGrp 를 반경 (1 − rnd²)·f23·16, 각 rnd·2π 에 (슬롯 4 순환, 재사용 슬롯의 이전 GO 제거, 회전 없음). f23 = Reach·(1 + 연타율·(Mash Reach Scale − 1)), 연타율 = 매시 미터 / Mashometer Max (35 문서 §4.5) [확실: 식] — 예전 문서의 "Stage n Flash Freq" 는 틀림 (Flash Freq +0x34 는 0x195BA8 화면 효과 인자) | 같은 식 (`Magic.mashRate()/lightningReach()`), 배율 감쇠는 dt 로 환산 (1 − 0.95^(60dt)). 연타 없으면 낙뢰 없음. 피해 틱마다 goLightningSingleBolt (기존) |

마법 WAD 는 처음 필요할 때 현재 단계(`Magic.stage`)의 `R_MEDHD<n>` 등을 등록하고, 단계가 바뀌면 다시 등록해 같은 이름을 덮는다.

### 4.2 PlayFX 템플릿 (sub_0019BBE0, SCR_PlayEffect 0x19A720) [확실: 키·오프셋, 추정: 동작]
`/TweakTemplates/PlayFX/nnn/` → 0xA0 바이트: `Effect Name` +0x42, `Joint Name` +0x5A (char[24]), u16 플래그 +0x40:
0x1 Particle Effect, 0x2 Looped, 0x4 #71bfa948, 0x8 Attached, 0x10 #a6de59b7, 0x20 Left Weapon, 0x40 Right Weapon, 0x80 Remove, 0x400 Blood Drop;
f32 `Scale X/Y/Z` +0x18, `Offset X/Y/Z` +0x24 (#cc9eaf6b..), `Rot X/Y/Z` +0x30 (#05245f0f..), #a4e61bc3 +0x3C (0.5, 미상).
(키 이름은 Hash127 역산으로 복원 — `Scale X` 0x79D90A02 등.)
발동: 영웅/적 CombatFile 의 SCRIPT 이벤트 해시 **0x097AE822 = Hash127Upper("SCR_PlayEffect")**, idx = 템플릿 (R_PERMK 영웅 기술에 35/36 각 57회, 100–108, 311–313).
엔진: `MagicFx.bind` 가 `Combatant.addEventListener` 로 받아 관절 위치에 재생. Attached 면 매 프레임 관절을 따르고, 아니면 발동 순간 위치. Looped 는 기술이 바뀌면 끈다.
- Rot X/Y/Z (도, FirePath 017/018 = Y 90°) = 관절 기준 로컬 회전 (X→Y→Z), Scale X/Y/Z 비균일 (Y/Z 는 X 대비 행렬, X 는 이펙트 배율) [확실: 값, 추정: 순서].
- Left/Right Weapon (0x20/0x40): 블레이드 로케이터 lWeapOH / rWeapOH 기준 [추정: 0x19AEE0 의 무기 오브젝트 경로를 로케이터로 근사].
- Remove (0x80): 그 전투자가 PlayFX 로 켠 같은 이름의 이펙트를 끈다 [추정]. Blood Drop (0x400): 관절 아래 바닥 피 자국 (0x19B798 → 0x221A50, §6.1) [추정].
- 적 WAD 의 PlayFX/MFX 템플릿·이펙트 GO: 적 스폰 때 `MagicFx.addWad(kind.assets())` (튜닝 + GO 등록, WAD 당 1번). 같은 번호는 나중 WAD 가 덮는다 [근사: 원작은 WAD 별 표].

### 4.3 Concussion 템플릿 이펙트 (sub_00191720)
`Effect Name` / `Joint Name`, u32 플래그 (Scale X 0x1, Y 0x2, Z 0x4, No Orient 0x8, Link Duration 0x10, #a0cdb24e 0x40 — 덤프의 각 키 값 하위 바이트),
`Start/End Radius`, `Duration`. 발동: SCRIPT 해시 **0x242EF94F = SCR_ConcussionBurst** (idx = 템플릿, 영웅 7A/BF 기술의 7–10 goRunDust),
메두사 Nuke 는 SCR_MedusaNuke 가 같은 템플릿을 쓴다 (`Magic.Sink.concussion` 신설). 판정(넉백·동결)은 기존 코드, 이 문서는 이펙트만.

### 4.4 이펙트 소리 (SEM_) [추정: 슬롯·시점]
- 오브젝트 그룹의 `SEM_` 이미터 슬롯 1 을 GO 가 처음 갱신될 때 재생 (`EffectSystem.Sounds`), 위치는 매 프레임 이펙트 원점, 거리 = SEM near/far.
  반복 이펙트(eyebeam SND_MEDUSA_RAY_LOOP, bolt SND_BOLT_LP, lightningattackmain SND_LIGHTNING360_LP, dedred SND_RAISEDEAD_LP)는 GO 를 지우면 끄고, 1회성(medheadflash RAY_SHOT, 항아리 SND_POTTERYSMASH)은 끝까지 둔다.
- 같은 이름(`SEM_soundEmitter`)이 WAD 에 여러 번 정의된다 (R_PERMK 5개: chest / strikegrp / deathparts / stoneherobrk …). WAD 는 순차 적재라 참조는 **그룹 앞의 마지막 정의** [확실: 배치, 추정: 로더].
- 게임 쪽 코드가 같은 소리를 직접 내는 경우 (MedusaGaze 의 SND_MEDUSA_RAY_SHOT, 원작 0x197A48 도 직접 재생) 0.3초 안 같은 이름은 한 번만 [근사].
- 연결: `GameScene.wireEffects` → `SoundManager.play/setRange/setPosition/stop`.

## 5. 메시 이펙트 축 [추정: 바인드 포즈 범위]
| 오브젝트 | 범위 | 배치 |
|---------|------|------|
| eyebeam | z −402.5 … −1.7, |x| 32 | 원점 = 눈, −Z 정면 (엔진 정면과 같음) |
| medheadnuke | ±27 × ±5.6 × ±27 | 수평 원반 |
| bolt | z −19 … 22.5 | +Z = 진행 방향 [추정] |
| dedred | z −224 … 32 | 머리 +Z, 꼬리 −Z |
| lightningsinglebolt | y −246 … 17 | 원점에서 아래로 내리꽂음 → 원점 = 대상 위 |
| lightningattackmain | y −40 … 448 | 영웅 원점 기준 하늘 번개 |

## 6. 전투 타격 / 발소리 (MFX, sub_002213C8) [확실: 오프셋, 추정: 선택]
MFX 템플릿 0x48 바이트 (`/TweakTemplates/MFX/nnn/`):

| +오프셋 | 키 | 형 | 예 (500 Flesh / 020 Rock) |
|---------|----|----|---------------------------|
| 0x20 | #33317a59 | s16 | |
| 0x22 | #da729778 | s16 | ≥ 0 이면 0x1F09C8 (진동/소리 추정) |
| 0x24 / 0x25 | #23d21c86 / #6217302a | u8 | |
| 0x28 | #405ce7fc (피 GO) | GO 핸들 | goSklBlood / goDirtPuff |
| 0x2C | Jump Land Effect | GO | – / goRockLand |
| 0x30 | Slice Hit Name | GO | goFleshSlice / goRockSlice |
| 0x34 | Run Dust Name | GO | – / goRunDust |
| 0x38 | Foot Sound | 소리 | – / SND_FOOTSTEP_STONE |
| 0x3C | Slice Hit Sound | 소리 | SND_BLOODSPURT / SND_SWORDHIT_STONE |
| 0x1C 비트 | 0x1 #4867d77b, 0x2 Drop Blood, 0x4 #d82a4c3e, 0x8 #4726f43e, 0x10 #f676b63b | | 500: Drop Blood 1 |
| 0x40 / 0x44 | #99755447 / #99755449 | f32 | |

- **적중** (0x2032C0, 공격자 쪽 OnContact): ① 공격 MFX (Attack +0x16, flags 0x2) 와 ② 피격 부위 재질 MFX (표 0x3376FC) 각각
  `sub_00221810(mfx, 접촉점, 방향, …, 배율)` → +0x28 GO 를 0x228558 로 1회 재생 + `sub_00221A50` 이 Drop Blood 면 바닥 피 자국(0x1F9278, /GlobGame/BloodSpots).
  **배율** (0x2034CC) [확실: 분기]: ① 공격 MFX 는 1.0, ② 재질 MFX 는 공격자 객체 +0x80 핸들이 피격자 루트(호출 인자 t1)와 같으면 1.0, 아니면 0.125 — 바닥 피도
  같은 조건일 때만. +0x80 = 공격자의 주 대상 [추정]. 엔진: 공격 `Combatant.target()` 이 피격자(또는 없음)면 1, 아니면 `rateScale` 0.125 + 바닥 피 생략 (`MagicFx.lastHitScale`).
  영웅 기술의 520 "Hit Effect" 는 +0x28 이 비어 있어 피는 ② 에서만 나온다.
  - **0x3376FC 는 표가 아니라 속성 오프셋 변수**다: 0x1BECA0 가 충돌 재질 속성 스키마(0x29C978)에서 "bitfield0..3", "SheetFX", "PartType",
    "WallHangTimer", "Damage", "AIDamage" 의 오프셋을 찾아 0x3376EC.. 에 둔다 → SheetFX = 재질 엔트리 +0x2C, PartType = +0x30 [확실].
    레벨 충돌 시트의 SheetFX (발소리) 와 같은 속성이다. 0x203450: `MFX 번호 = *(피격 구 재질 엔트리 + SheetFX)` → sub_00182D50 템플릿.
  - CDV 값: CDV_hero Body 501 "Hero Flesh", CDV_MIN / SKA / HAR 510 "Zombie Flesh", CY2 506 "Cyclops Flesh", 석상 파편 22 "Breakable Rock" [확실: 데이터].
    MFX 5xx 는 레벨 WAD 에 있다 (510 은 ATHN01B/03A … , 501 은 R_HERO*, 502 "UW Hero Flesh" = goWaterBlood, 하이드라 "Hyd Bod Flesh" = goBHABloodBB).
  엔진: `HitVolumes` 가 구마다 SheetFX 를 들고 `sweptHit` 이 맞은 피격 구의 SheetFX·접촉점(두 구 중심을 반지름 비로 내분)을 피격자 쪽에 기록 →
  `MagicFx.hitBlood` 가 그 MFX 의 +0x28 GO 를 접촉점에. 기록이 없으면(폴백 판정) 몸 구 SheetFX, 템플릿이 든 WAD 가 없으면 500/501 [근사].
- **바닥 피 자국** (§6.1): MFX "Drop Blood" (bit 0x2) 면 sub_00221A50 → 0x1F9278.
- **Slice Hit Name** (무기가 월드를 칠 때, 0x202248 → 0x221918): 엔진 `MagicFx.weaponWorld` — 공격 창(피해 &gt; 0) 안의 공격 부위 구의 이전→현재 중심 선분을
  이동 방향으로 반지름만큼 늘려 레벨 충돌 레이캐스트 → 면 SheetFX 의 MFX +0x30 GO 를 충돌점·면 법선 방향에, +0x3C 소리 (SND_SWORDHIT_STONE …).
  (기술, 공격 번호) 당 1번 [추정: 원작 구-시트 접촉 0x13C400 을 선분으로 근사]. **블레이드 부위 2/3/11 은 제외** — 원작 0x202248 은 손 밖 블레이드의 사슬 벽 질의 0x1FAFC8 에서 부른다 (45 §3.8, `MagicFx.bladeSpark`). ARENA06 □□□ 는 블레이드 구 바닥이 바닥 위 4–10 이라 없음, △ 내려찍기 = goRockSlice 2회 (SheetFX 20 Rock).
- **착지** (sub_00221EA8, 0x1FE490/0x1FE518): 발밑 MFX "Jump Land Effect" (+0x2C, Rock → goRockLand, Dirt → goDirtLand …) + Foot Sound 레지스터 3 [확실: 경로].
  엔진: `GameScene.footsteps` 의 착지 순간 `MagicFx.land`.

### 6.1 바닥 피 자국 (/GlobGame/BloodSpots, 0x1F9550 등록, 0x1F9278 생성)
키: Offset 0.1, Cone 25, Impulse 1, Gravity 18, Nominal Distance 8 / Particles 36 / Size 3, Fall Time / Emit Time (s16) 3, Clamp Spread Size 1.3 / Part Size 0.65 / Num Part 10,
"Particle System 1 Light" = goBloodSpot (FXC_BloodSemit 상수 생성률 0, PTC_BloodSpart VU 9, 수명 3초) [확실: 키·값]. 원작은 전용 코드로 방울을 떨어뜨려 바닥에 붙인다.
엔진: 접촉점 아래 바닥(충돌 질의) + Offset 에서 goBloodSpot 을 0.1초 동안 Nominal Particles 개 분출 (속력 ¼, `Effect.rateOverride`) [근사: 낙하·Size 미반영].
원작 0x1F8990 [확실]: 충돌 레이 0x11E190 의 점·**면 법선** n 으로 A = norm(d × n), B = norm(A × n) 평면 축을 만들고 파티클마다 n 축 임의 회전 (0x183D00),
위치 = 충돌점 + 평면 안 난수 + n·Offset. 엔진: 아래 레이캐스트 법선을 `Effect.planeNormal` → 종류 9 레코드 축 (평면 + 임의 회전), 위치를 그 평면에 투영.
- **발소리 먼지** (0x221A90): 발 관절 아래 지면 MFX 의 +0x34. 엔진: `GameScene.footsteps` 의 달리기 걸음마다 발밑 SheetFX 의 Run Dust Name (Rock → goRunDust) 을 0.6초.

### 6.2 부서지는 물체 이펙트 → 41 문서 §2

## 7. 연결 (변경 파일)
| 파일 | 변경 |
|------|------|
| `tools/fx/EffectLibrary` (신규) | 인스턴스 → 오브젝트 → 구성 요소, 채널 생성률, 수명, 필드 가속 |
| `tools/anim/Animation` | `channelGroups`, `channel()` |
| `tools/fx/FxResources` | `Shape.vu3()` |
| `engine/fx/EffectSystem` (신규) | 런타임 (헤드리스 모드 포함) |
| `engine/fx/MagicFx` | 원작 이펙트 우선 + 근사 대체, `bind/bindHero`, `playFx`, `concussion`, `footstep`, `sink()` |
| `engine/fx/ParticleSystem` | 이미터 덮어쓰기 필드, 남은 파티클 렌더 |
| `engine/asset/AnimatedModel` | 머티리얼 ANM 적용 draw |
| `engine/combat/Combatant` | `addEventListener`, `addContactListener` |
| `engine/combat/Magic`, `MedusaGaze` | `Sink.concussion`, `lightningOn()`, 번개 가지 |
| `engine/game/HeroRig` | `jointWorld(name, out)` |
| `engine/game/GameScene` | MagicFx 생성(rom)·sink·bind (영웅·적)·render(white)·달리기 먼지 (최소 변경) |
| `engine/scene/HeroTestScene` | `-PgowScript`, `-PgowMagicStage`, MagicFx 연결 |
| (2차) `tools/fx/EffectLibrary` | `EffectDef.acts`/`act(name)`, `sem` (그룹 앞 SEM 정의), act 지정 `rate/param` |
| (2차) `engine/fx/EffectSystem` | `Effect.setAct`, `setLife`, `rateOverride`/`speedScale`, `Sounds` (SEM), `add(WadAssets)` (비소유) |
| (2차) `engine/fx/MagicFx` | 메두사 머리, 제우스 차지, 포세이돈 Radius/StrikeGrp, PlayFX 플래그, 부위 MFX, 바닥 피, 착지, 무기-월드 베기, `breakObject`, `addWad`, `setSounds`, `setSurface` |
| (2차) `tools/level/BallHull`, `engine/combat/HitVolumes` | `materialFx` (+0x2C SheetFX), `lastHitFx/lastHitPoint/bodyFx/sweeps` |
| (2차) `engine/game/GameScene` | `wireEffects` (레벨·적 WAD, 소리, 충돌), `onBreak`, 착지·무기-월드 호출, 스폰 때 `addWad` (최소 변경) |
| (2차) `engine/level/LevelScene` | `assets()` |

## 8. 검증
- `EffectTest` (5): 메두사 해석·채널 생성률(5000 → 0)·광선 반복, 피 3채널·스트림 3/4 = FXC 값·중력 아래, 마법 WAD (bolthit 0.8초, stdbolt 상수 꼬리, chargeup 스케일 0.25,
  deadhit/die 1초), 튜닝 템플릿 (PlayFX 035/101, Concussion 000/009 플래그, MFX 500/020, Arrow 002, MedusaHead), 헤드리스 수명 (1회성 0.333초 종료, 강제 수명, 반복 유지·stop, 소유자 소멸).
- 전체 `:engine:test` 186 통과.
- 스크린샷 (커밋 안 함, 스크래치): 게임 씬 ATHN01A 메두사 광선(원작 나선 빔 메시), 연습 씬 단계 2 섬광·Nuke(감산 용암 + 청록 소용돌이 원반),
  제우스 명중, 하데스 영혼(붉은 낫 영혼 메시), 포세이돈(번개 가지·하늘 번개 메시), 적중 피(검붉은 분출).

2차 (이 문서 §4.1 머리·차지·포세이돈, §4.2 플래그, §4.4 소리, §6 부위 MFX·베기·착지·피 자국, 41 §2 파괴):
- `EffectTest` +3: 메두사 머리 act (On/Off, 수명), SEM 소리 (그룹 앞 정의, 반복만 정지), 부서지는 물체 GO·Brkb (OrbEmitter 900, Fade Time, Hit Points 8, 오브 롤).
- `HitVolumesTest`: CDV_hero SheetFX [501, 0, 0], combo3A 정면 적중의 피격 구 SheetFX = 510.
- 전체 `:engine:test` 196 통과.
- 스크린샷 (스크래치 `fx2/shots`): `fx2_medusa_hero.png` (손에 든 메두사 머리 + 광선), `fx2_medusa.png` (ATHN01A), `fx2_pots.png` (ARENA06 항아리 파편 애니),
  `fx2_zeus_charge.png` (손의 번개 + 차지 불꽃), `fx2_poseidon.png` (StrikeGrp 기둥 + Radius), `fx2_blood.png` (ARENA08 적중 MFX 510/501 + 피 자국), `fx2_land.png` (goRockLand).

## 8.1 판도라 수호자 BMI 이펙트 (37 §10.1)
- 통나무 Arrow/060 GO golog (메시 + 불) 를 투사체 위치·logAim 회전으로 매 프레임, 명중/벽 충돌에 Hit Effect gologShatter (1회 act).
- 배출구 goSteamVent01 ×8 을 BMI 관절 steamAttach01..08 에 부착, 원작 +0xB4 배율 0/1 → `rateOverride` 0 / 데이터대로.
- 갑옷 BRK GO ×8: 깨지기 전엔 관절 부착 + **`Effect.hold`** (신설: 시간 정지 = 원작 0x1C1B10 이 애니를 0초에 둠), 깨지면 그 순간 관절 행렬 고정 후 재생.
- 증기(ST2part1/1A, VU 3, 플래그 0x281/0x681)가 화면을 하얗게 덮는다 — §9 의 PTC 크기·블렌드 가정 문제로 보임 (미해결).

## 8.1 전 레벨 시각 QA (43 문서)
- 효과 켬 105 레벨: 날씨·파티클이 화면을 덮는 레벨 0. 반투명 패스 순서 수정 (43 §2.3) 으로 가려졌던 빛줄기·광원 후광·폭포가 보인다.
- 남은 파티클 의심: ATHN01A 폭포 옆 흰 물보라 (볼륨/표면 이미터 해석 [추정], 43 §4).

## 9. 남은 것
- ~~날씨 흰 줄기 (ATHN01D·ATHN02A·DEST01·PAND00C)~~ (16 §1.5, 31 §5): 원작 날씨 갱신 fn·VU 나이 고정·속성 배치 레코드로 해결. 남은 것: snow/embers/groundfog 갱신 fn (사용 레벨 없음, 표류만 적용), 숨김 중 블록 시각.
- ~~PTC 크기 단위·VU 4/8/9 배치·블렌드~~ (16 §2.4: 반폭 = 출력 0x223.w, 블렌드 = flags&3, 0x8000 은 블렌드 무관; rslicepart 는 0x44 알파). PTC 로더 = def 생성자 0x15AD60 (호출 0x15D5A0).
  ~~종류 5 (열기 아지랑이)~~, ~~연산 9/10/13/14~~ (디스크에 쓰는 PTC 없음, 해석·구현만), ~~피 자국 레코드 축~~, ~~PTC 0x10/0x20 파티클 공간~~ (16 §2.3–2.4, 31 §5).
- ~~MFX 배율 0x221810 f12~~ → `rateScale` (§6). 남은 것: +0x80 이 주 대상이라는 해석 확인.
- 메두사 광선 방향: 원작은 rWeapIH 관절 행렬 그대로 (팔 조준 자세가 방향을 만든다) — 엔진은 조준 yaw [근사]. 머리 MHBemit 은 커브 이미터 (FXC 4, MHBcurve) + 링크 3 인데 On act 의 채널 그룹은 1개 — 링크 대응 미해석.
- 표면 이미터 (FXC 3), 이펙트 쪽 커브 이미터 (레벨 배치 커브 이미터는 16 §2.5 · 43 §19 에서 구현), FXC 필드 소용돌이·뉴턴.
- 바닥 피 자국의 원작 낙하·부착 (Cone/Impulse/Gravity/Fall Time), Particle System 1 Dark / 2.
- MFX #33317a59/#da729778 (진동·소리 추정), Slice Hit 의 원작 접촉 조건.
- ~~포세이돈 매시 미터~~ (§4.1, 35 §4.5). ~~포세이돈 화면 효과 0x195BA8~~ (35 §4.6: 보이는 것은 Fog/000 "360 Lightning" 감산 안개 대체뿐, 틴트 1·카메라 필터 "None"). ~~신들의 분노 GO~~ (35 §4.7): 0x1DAEC0 이 만든 갑옷 GO 8개 (goBreastPlate·goHelmet·견갑·팔찌·정강이, 관절 vertebrae4/head/humerus/radius/tibia) 를 진입 0x1DAB08 이 켜고 부착, goStrikeGrp/goFlashGrp 는 진입 때 한 번 (위치 미지정 → 엔진은 영웅 위치 [추정]), 해제 0x1DABF8 이 끔. +0x44/+0x48 을 읽는 갱신 코드는 없다 [확실]. 남은 것: 카메라 필터 렌더 0x162008, 블레이드 god 판 0x1FA9A0.
- Concussion No Orient/Link Duration, PlayFX #a4e61bc3 / #71bfa948 / #a6de59b7, Left/Right Weapon 의 정확한 부착 대상.
- 적 템플릿 번호 충돌 (여러 적 WAD 의 같은 PlayFX 번호) — 마지막 WAD 가 덮음.

## 석화 석상 GO (35 문서 §2.2, 2026-10-24)
- 적 WAD 의 Sold +0x38 (goMINFreeze, SCR_TurnToRock 0x19F8E0) 은 일반 1회 이펙트, +0x20 (goMINStonedBRK, 0x1E8960) 은 부서지는 물체와 같은 구성의 GO 를
  `Effect.hold` (act 0 초) 로 소유자 행렬에 세워 두었다가 파괴 때 act 를 0 초부터 재생 (`MagicFx.stoneLook`). Brkb 517 지연 3 초 + Fade 1 초, bit 0x1 파편 물리.
- 같은 WAD 에 같은 이름 MAT 이 둘 (R_MIN `MAT_body`: 몸 / 석상 돌) — 모델은 MDL 앞쪽 가장 가까운 정의 (`MAT_body@태그` 별칭, `GowModel.load`·`WadAssets`).
- 연습 씬 (`HeroTestScene`) 도 적 WAD 를 이펙트 라이브러리에 등록한다 (예전엔 R_PERMK·마법 WAD 만).

