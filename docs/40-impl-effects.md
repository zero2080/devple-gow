# 40. 구현: 원작 이펙트 GO (마법·전투 이펙트 이름 → GO → FXC/PTC/MDL/ANM, 부착·수명)

분석 기반: [16 문서](16-environment-fx-format.md) §2 (FXC_/PTC_), [31 문서](31-impl-environment-fx.md) (파티클 런타임),
[35 문서](35-impl-magic-minigames.md) §7 (이전 근사), [10 문서](10-combat-system.md) §2.7–2.8 (Attack MFX, 애니 이벤트).
패키지: `devple.gow.tools.fx.EffectLibrary` (해석, 헤드리스), `devple.gow.engine.fx.EffectSystem` (런타임), `fx.MagicFx` (연결).

신뢰도: [확실] = 실행 파일 코드·데이터 일치, [추정] = 정황/패턴, [근사] = 엔진이 대신 정한 값.

```bash
./gradlew :engine:test --tests '*EffectTest*'                                   # 해석·수명 (헤드리스, 5)
./gradlew :engine:run -PgowFxLog -PgowMagicStage=2 "-PgowScript=LEFT:0.3:0.05,R2:0.6:3,CIRCLE:1.5:0.05" \
    --args="--hero --enemy R_MIN --screenshot shot.png --ticks 150"           # 메두사 Nuke (연습 씬, 단계 2)
./gradlew :engine:run -PgowGiveAll "-PgowScript=LEFT:0.5:0.1,R2:1.0:3" --args="--game ATHN01A --skip-movies"   # 게임 씬 광선
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
| `SEM_x` | 사운드 이미터 (재생은 기존 소리 경로가 담당, 미연결) |

정의 위치 (전부 `EffectLibrary.add` 가 인스턴스를 훑어 등록, 나중에 더한 WAD 가 같은 이름을 덮음):

| WAD | 이펙트 (오브젝트: 구성) |
|-----|------------------------|
| R_PERMK | sklblood (ANM 3채널 + FXC 3 + 중력), bloodspot, rundust (ANM 1채널, 반복 0.2초), fleshslice/armorslice/metalslice/woodslice/rockslice/…, *land (착지 먼지), splash, maisweffect, maishockwave(MDL), combo3fexplode(MDL+FXC 6), fxfirepath(MDL), strikegrp/flashgrp(MDL), doublejump, maifreeze, stoneherobrk, 오브(healthorb …) |
| R_MEDHD0..2 | eyebeam (MDL 17관절, ANM 반복 5초), medheadflash (FXC 1 + 채널, 1회 0.333초), medheadnuke (MDL 2관절, 반복 2초), mewh (메두사 머리 모델) |
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
- **스트림 k = FXC P 블록(파일 0x84) 의 k 번째 float**: 3 = 퍼짐(P+0x0C), 4 = 속력(P+0x10), 5 = 속력 랜덤, 8 = 초당 생성 수(P+0x20).
  증거: sklblood 채널 0 프레임 0 의 스트림 3 = 0.1117, 스트림 4 = 2.869 → FXC_SKLemit 의 spread 0.11 / speed 2.87 과 같다.
- 값: 절대 f32 키 (1/30 초), 트랙의 [start, start+count−1+hold] 만 정의. 한 디스크립터에 같은 스트림 트랙이 여러 개면 구간을 나눠 가진다
  (medheadflash: 프레임 0–1 + hold 8 = 5000, 프레임 9–10 = 5000→0).
- 예: sklblood 생성률 0 → 280 → 0 (0.4초), rundust 0 → 240 → 0 (0.2초 반복), deadhit 500 → 0, medheadflash 5000/초 × 0.3초.
- 채널이 없는 이미터(헤더 링크 −1)는 FXC 상수 생성률: stdbolt 150/초, dedred 60/초 — 소유자가 끌 때까지.
- 링크 번호는 있는데 해당 디스크립터가 없는 1회성 (bolthit 단계 2 의 emitter1 600/초): 처음 0.1초만 생성 [근사].

구현: `Animation.channelGroups` (디스크립터별 트랙), `Animation.channel(group, stream, t, def)`, `EffectDef.rate/param`.

### 3.1 PTC VU 프로그램 3 상수 [확실: 색·가속, 추정: 크기]
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
Z 라는 것의 확인이다 (`EffectLibrary.fieldAccel`: (x,y,z) ↦ (x, z, −y)·16). 크기는 q10/q11 × 3 월드 [근사: 단위 미확정].
VU 4 (반짝이 점) 는 기존 휴리스틱 (A·u + B·u² 알파) 을 쓴다.

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
배율 sub_00227D80: GO 컴포넌트 중 종류 < 0xB 인 것(파티클 이미터) +0xB4 = 배율 (MFX 0x221810 은 영웅이 맞을 때 1, 아니면 0.125 [추정]).

엔진 (`EffectSystem`):
- `play(name, Anchor, life)` → `Effect`. `Anchor` = 매 프레임 기준 행렬(관절 부착이면 관절 월드, 아니면 고정). false 를 돌려주면 소유자 소멸 → 종료.
- 수명 = `EffectDef.lifetime()`: 1회성 act 길이, 반복·애니 없음 = ∞ (`stop()`). 길이 0 정지 act (dedred) 는 지속으로 본다 [추정].
- 메시: `AnimatedModel` + 오브젝트 act 포즈 + **머티리얼 ANM** (새 `draw(..., fxTime, alpha)`: 레이어 0 UV 스크롤·hdr8 불투명도). 월드 = 기준 × 인스턴스 스케일 × 사용자 배율.
- 이미터: FXC 로컬 행렬의 회전(스케일 제거)·dir → 기준 회전. 로컬 이동은 |t| ≤ 64 만 (Maya 장면 좌표가 남은 slice 이미터 (407,0,1344) 는 원점) [근사].
  `ParticleSystem.Emitter` 에 프레임별 덮어쓰기 필드 (`rate`, `speed`, `speedRnd`, `cone`, `sizeScale`) 를 추가했다.
- 파티클 정의: 크기 |PTC+0x74| (≥ 800 → /128, VU 4 는 /256, ≥ 160 → /64, 그 밖 /16, 음수 → 2.5) 또는 VU 3 상수 크기 [근사],
  블렌드 = PTC 플래그 0x8000 이면 알파(피·먼지), 아니면 가산(불·번개·반짝임) [추정], 텍스처 = PTC 뒤 MAT_ 첫 레이어.
- `ParticleSystem.render` 는 이제 살아 있는 파티클에서 정의를 모은다 (이미터를 지워도 남은 파티클을 수명까지 그림).

### 4.1 마법 (MagicFx)
| 효과 | 원작 | 엔진 |
|------|------|------|
| 메두사 광선 | goEyeBeam 을 메두사 머리 GO 관절(0x3368C8)에 부착, 광선 동안 유지 | 영웅 `rWeapIH` 위치 + 조준 yaw (메시 −Z 400 = 정면) [근사: 머리 GO 미렌더] |
| 메두사 섬광 | goMedHeadFlash 1회 (행렬 = 머리) | 손 위치·조준 방향, MHESemit 5000/초 × 0.3초, 로컬 회전 Y→−Z |
| 메두사 Nuke | Concussion 0/1(동결) + 3/4(FX) 의 Effect goMedHeadNuke @synchJoint, Scale X/Y/Z 플래그 | 템플릿마다 재생, 배율 = 반경(TWK m)×16/27 (메시 반경 27) 을 Start→End 로 Duration 동안, 이후 0.5초 페이드 [근사] |
| 제우스 | Arrow GO Name goBolt + Trail goStdBolt 를 화살에, 명중 Hit Effect goBoltHit | 화살 위치·속도 방향(+Z) 부착, 화살 소멸 시 정지, 명중 1회 0.8초 |
| 하데스 | goDedRed 영혼, goDeadHit, goDeadDie | 영혼 위치·속도 방향 부착 (꼬리 −Z) |
| 포세이돈 | goLightningAttackMain (루프 기술 동안), goLightningSingleBolt | 루프 동안 영웅 부착, 피해 틱마다 대상 위 230 에 가지 0.25초 [추정] |

마법 WAD 는 처음 필요할 때 현재 단계(`Magic.stage`)의 `R_MEDHD<n>` 등을 등록하고, 단계가 바뀌면 다시 등록해 같은 이름을 덮는다.

### 4.2 PlayFX 템플릿 (sub_0019BBE0, SCR_PlayEffect 0x19A720) [확실: 키·오프셋, 추정: 동작]
`/TweakTemplates/PlayFX/nnn/` → 0xA0 바이트: `Effect Name` +0x42, `Joint Name` +0x5A (char[24]), u16 플래그 +0x40:
0x1 Particle Effect, 0x2 Looped, 0x4 #71bfa948, 0x8 Attached, 0x10 #a6de59b7, 0x20 Left Weapon, 0x40 Right Weapon, 0x80 Remove, 0x400 Blood Drop;
f32 `Scale X/Y/Z` +0x18, `Offset X/Y/Z` +0x24 (#cc9eaf6b..), `Rot X/Y/Z` +0x30 (#05245f0f..), #a4e61bc3 +0x3C (0.5, 미상).
(키 이름은 Hash127 역산으로 복원 — `Scale X` 0x79D90A02 등.)
발동: 영웅/적 CombatFile 의 SCRIPT 이벤트 해시 **0x097AE822 = Hash127Upper("SCR_PlayEffect")**, idx = 템플릿 (R_PERMK 영웅 기술에 35/36 각 57회, 100–108, 311–313).
엔진: `MagicFx.bind` 가 `Combatant.addEventListener` 로 받아 관절 위치에 재생. Attached 면 매 프레임 관절을 따르고, 아니면 발동 순간 위치. Looped 는 기술이 바뀌면 끈다.

### 4.3 Concussion 템플릿 이펙트 (sub_00191720)
`Effect Name` / `Joint Name`, u32 플래그 (Scale X 0x1, Y 0x2, Z 0x4, No Orient 0x8, Link Duration 0x10, #a0cdb24e 0x40 — 덤프의 각 키 값 하위 바이트),
`Start/End Radius`, `Duration`. 발동: SCRIPT 해시 **0x242EF94F = SCR_ConcussionBurst** (idx = 템플릿, 영웅 7A/BF 기술의 7–10 goRunDust),
메두사 Nuke 는 SCR_MedusaNuke 가 같은 템플릿을 쓴다 (`Magic.Sink.concussion` 신설). 판정(넉백·동결)은 기존 코드, 이 문서는 이펙트만.

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
  영웅 기술의 520 "Hit Effect" 는 +0x28 이 비어 있어 피는 ② 에서만 나온다.
  엔진: `Combatant.addContactListener` → 피격자가 적이면 MFX 500, 영웅이면 501 의 +0x28 (= goSklBlood) 을 피격자 높이 40, 공격자→피격자 방향에 [근사: 부위 재질 표 미해석]. 바닥 피 자국 미구현.
- **Slice Hit Name** 은 무기가 월드(벽·바닥)를 칠 때 (0x202248 → 0x221918) — 미연결.
- **발소리 먼지** (0x221A90): 발 관절 아래 지면 MFX 의 +0x34. 엔진: `GameScene.footsteps` 의 달리기 걸음마다 발밑 SheetFX 의 Run Dust Name (Rock → goRunDust) 을 0.6초.

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

## 8. 검증
- `EffectTest` (5): 메두사 해석·채널 생성률(5000 → 0)·광선 반복, 피 3채널·스트림 3/4 = FXC 값·중력 아래, 마법 WAD (bolthit 0.8초, stdbolt 상수 꼬리, chargeup 스케일 0.25,
  deadhit/die 1초), 튜닝 템플릿 (PlayFX 035/101, Concussion 000/009 플래그, MFX 500/020, Arrow 002, MedusaHead), 헤드리스 수명 (1회성 0.333초 종료, 강제 수명, 반복 유지·stop, 소유자 소멸).
- 전체 `:engine:test` 186 통과.
- 스크린샷 (커밋 안 함, 스크래치): 게임 씬 ATHN01A 메두사 광선(원작 나선 빔 메시), 연습 씬 단계 2 섬광·Nuke(감산 용암 + 청록 소용돌이 원반),
  제우스 명중, 하데스 영혼(붉은 낫 영혼 메시), 포세이돈(번개 가지·하늘 번개 메시), 적중 피(검붉은 분출).

## 9. 남은 것
- 메두사 머리 GO (mewh) 렌더와 그 관절에 광선 부착, 영웅 rWeapIH 대신.
- PTC 크기 단위 (VU 3 q10/q11, VU 4 상수), VU 4·8·9 상수 배치, PTC 플래그 0x8000 = 알파 가정 검증.
- 피격 부위 재질 표 0x3376FC (적 CDV 재질 → MFX), 바닥 피 자국(BloodSpots), 무기-월드 Slice Hit, Jump Land Effect (착지 먼지).
- SEM_ 이펙트 소리, 표면/커브 이미터 (FXC 3/4: mewh 의 MHBcurve), FXC 필드 소용돌이·뉴턴.
- PlayFX 의 Rot X/Y/Z·Left/Right Weapon·Remove·Blood Drop, Concussion No Orient/Link Duration, #a4e61bc3.
- 제우스 차지(goBoltChargeUp), 포세이돈 goLightningRadius·goStrikeGrp/goFlashGrp 연결, 적 전용 이펙트(BHA 피 등)의 적 WAD 템플릿 로드 (`MagicFx.addTweaks`).
