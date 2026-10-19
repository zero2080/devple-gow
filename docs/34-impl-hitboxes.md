# 34. 구현: 무기 궤적 / 원작 히트박스 (19/34 단계)

근거 문서: 05 §2.8 (ANM 타입 10), 10 §5.1a (PartType·접촉 경로), 12 (BallHull).
신뢰도 표기는 10 문서와 같다: **[확실]** 디스어셈블/데이터 대조, **[추정]** 맥락 판단.

## 0. 요약
- **블레이드 위치** = ANM 타입 10 슬롯 1: 로케이터 관절 `lWeapOH`(107)/`rWeapOH`(109) 의 모델 공간 x/y/z (회전은 타입 0). 평소엔 손목 옆, 투척 시 정면 최대 ~100 단위, 대기 중엔 등 뒤 [확실: 데이터].
- **블레이드 = 별도 오브젝트** `maiblade` (R_WPN&lt;코스튬&gt;_&lt;블레이드 레벨&gt;.WAD: CXT 3관절, `MDL_MAIBlade`, `CDV_MAIBlade`). 오브젝트 루트를 OH 로케이터 월드 행렬에 둔다.
- **히트박스 = CDV BallHull 구**. 구마다 관절 번호(off[1])와 재질 엔트리(off[3]); 재질 엔트리 +0x30 = **PartType** = `Attack.part` 와 비교되는 번호 [확실: 0x13C400 → 0x203150 → 0x203AF0 → 0x209AB0].
  - 히어로: 몸 1 (관절 4 r12, 관절 1 r13), 오른발 7 (관절 56·60 r6.8), extra1 9 (관절 1, 정면 z−20 r27)
  - 블레이드: 왼손 2 / 오른손 3 (오브젝트 flags 0x200 이면 2→3), 쌍 11 — 같은 구 (0.03, 0, −4.56) r13.2
  - 미노타우로스: `LeftCollisionMat` 2 (관절 26, r10.7 / r14.9), 몸 1 (관절 3 r21.3, 관절 1 r17.0)
- 엔진: `HitVolumes` 가 매 프레임 구를 포즈하고, 공격 구간 동안 **공격 부위 구 × 상대 몸 구** 를 이전→현재 프레임 스윕으로 검사. 데이터가 없으면 기존 정면 구 근사로 폴백.
- 부수 발견: 엔진 렌더 행렬의 **+π 회전이 틀렸다** (메시 정면 = 모델 −Z). 수정 (§4).

## 1. 코드
| 위치 | 내용 |
|---|---|
| `tools/anim/Animation` | `channelTracks`(타입 10 원소 4), `byteTracks`/`byteChannel()`(원소 1), `locatorTrack()` → bake 때 `*WeapOH` 위치 합성 |
| `tools/anim/WeaponAttach` | 블레이드 한 벌 로드 (`blades(wpnWad)`), 부착 행렬 기준 관절/메시 포즈, CDV 구 포즈, 디버그 링 메시 |
| `tools/level/BallHull` | `ballJoint[]`, `ballMaterial[]`, `materialPart[]`, `ballPart(b)`. **재질 엔트리 크기 = 헤더 0x38** (CDV/ENV/SEV 0x40, CMV 24) — 이전 24 고정은 두 번째 이후 이름을 잘못 읽었다 |
| `tools/anim/Cli` | `anim:sheet … --weapon R_WPN0_n.WAD [--hit]` |
| `tools/model/PreviewRenderer` | 서브메시 이름 `#RRGGBB` = 고정 색 |
| `engine/combat/HitVolumes` | 구 원본(관절/무기 부착) → 월드 구, `sweptHit(part, victim)`, `forHero` / `forObject` / `cdvFor` |
| `engine/combat/Combatant` | `setHitVolumes`/`hitVolumes`, `update` 첫머리에서 포즈 갱신, `attacks()` = 히트박스 판정 + 폴백 |
| `engine/combat/Hittable` | `default HitVolumes hitVolumes()` |
| `EnemyFighter` | `hitVolumes()` 접근자 1개 + 렌더 행렬 +π 제거 (최소 변경) |
| `EnemyFactory.Kind.spawn` | 적 WAD 의 CDV 로 `HitVolumes.forObject` |
| `game/HeroRig`, `scene/HeroTestScene` | 히어로 볼륨(CDV_hero + 블레이드), 블레이드 렌더 (`asset/BladeModels`) |

### API
```java
HitVolumes hero = HitVolumes.forHero(heroWad, heroSkeleton, WadFile.load(rom.resolve("R_WPN0_2.WAD")));
combatant.setHitVolumes(hero);                      // Combatant.update 가 매 프레임 animator 포즈로 갱신
HitVolumes min = HitVolumes.forObject(minWad, minSkeleton);  // 몸 + 도끼손
boolean hit = hero.sweptHit(attack.part(), min);    // 이전→현재 프레임 스윕
List<float[]> s = hero.spheres(2);                  // 디버그: 월드 구 (x, y, z, r)
```
오브젝트 행렬 = `translation(pos) · rotateY(yaw)` (모델 −Z → 월드 (−sin yaw, −cos yaw) = 루트 모션·이동·AI 정면과 같은 규약).

## 2. 판정 규칙
1. 공격 구간 [start, end] 안의 `Attack` 마다:
2. 공격자 볼륨에 `part` 구가 있고 상대에 볼륨이 있으면 → 공격 구(이전/현재 위치) × 상대 **PartType 1(몸)** 구(이전/현재) 상대 운동의 최근접 거리 ≤ 반지름 합.
   상대에 몸 구가 없으면 상대의 모든 구. [추정: 원작은 모든 구 쌍을 접촉시키고 OnContact 에서 부위가 맞는 공격만 찾는다 — 피격 쪽 구 종류 필터는 확인 못 함]
3. 아니면 기존 근사(정면 55 유닛 구).
4. 같은 (상대, 공격 번호) 는 기술당 1회 (원작 +0xAC 기록).

스윕을 쓰는 이유: 블레이드는 투척 구간에 프레임당 20 단위 이상 움직인다 (combo3A f6→f8 왼쪽 OH 41 단위). 원작 접촉 단계(0x13C400)가 프레임 사이를 보간하는지는 미확인.

## 3. 검증
| 테스트 | 내용 |
|---|---|
| `HitVolumesTest.cdvPartTypes` | CDV_hero 관절 [4,56,60,1,1], PartType [1,7,9], 블레이드 [2,11], 히어로 볼륨에 부위 1/2/3/9/11 |
| `HitVolumesTest.sweptSpheres` | 시작·끝은 멀고 중간에 관통하는 구 = 적중, 비켜가면 실패 |
| `HitVolumesTest.combo3AHitsFrontNotBehind` | 블레이드 Lv2 □ → COMBO3A: 정면 40 의 미노타우로스(R_MIN 실제 CDV) 적중 1, 등 뒤 40 은 0 |
| 기존 `CombatantTest`/`DefenseTest`/`GrabTest`/`JumpAirComboTest`/`MagicTest`/`EnemyFactoryTest` | 변경 없이 통과 (TrainingDummy 는 볼륨이 없어 폴백 경로 — 기대값 수정 없음) |
| `anim:batch gow-rom` | 214 WAD, act 7373 bake 실패 0 / NaN 0, 타입 10 로케이터 적용 = `lWeapOH` 4518, `rWeapOH` 4518 |

디버그 이미지 (커밋 제외, `work/hitbox/`):
- `sheet_combo3A.png` (`anim:sheet --weapon --hit`): 빨강 = 공격 부위 구(블레이드·발·extra1), 초록 = 몸. 0.21–0.43초 왼쪽 블레이드 투척.
- `sheet_combo6A.png`: 양손 블레이드 내려찍기 궤적.
- `combo3A_front.png` / `combo3A_behind.png` (`HitVolumesTest` 가 `work/hitbox` 가 있으면 생성): 위 = 위에서 본 장면, 아래 = 측면. 빨강/주황 = 블레이드 2/3, 초록 = 미노타우로스 몸.
- `engine_facing_before.png` / `engine_facing_fixed.png`: §4 렌더 방향 수정 전후 (`--hero --enemy R_MIN`).

## 4. 렌더 방향 정정 (25 문서 수정)
25 문서는 "메시 정면 +Z" 로 보고 렌더 행렬에 +π 를 넣었지만, 메시·스켈레톤 모두 **−Z 정면**이다:
히어로 바인드 메시 발 정점 z −3.99..+1.45, 얼굴 중심 −3.19, `jawTip` z −2.82, 발가락 관절 −2.33 / 미노타우로스 턱 −7.56, 꼬리 관절 +5..+27.
루트 모션·이동(`HeroController`)·적 AI yaw(`atan2(−dx, −dz)`)·전투 정면 근사는 전부 모델 −Z = 월드 (−sin, −cos) 규약이므로 렌더만 반대였다
(캐릭터가 등을 보이며 이동, 미노타우로스가 크레토스에게 꼬리를 향함, 바닥 도끼 `axeOut` 이 반대편에 놓임).
`HeroController.world` / `EnemyFighter.world` 에서 +π 를 제거 — 수정 후 스크린샷에서 크레토스가 적을 향하고 도끼가 미노타우로스 옆에 놓인다.
블레이드를 렌더하므로 투척 방향 = 정면도 눈으로 확인 가능.

## 5. 미해결 / 근사
- PartType 10 (아르테미스의 검) CDV 위치 미확인 → BER* 기술은 폴백 근사. 4/5/0 부위 (영웅 #84EF57FD 기술, 미노타우로스 0·3) 도 데이터 없음 → 폴백.
- 미노타우로스 부위 3: 원작 규칙상 flags 0x200 무기 오브젝트의 2→3 인데 미노타우로스는 무기 오브젝트가 없다 — 폴백.
- 피격 쪽 구 필터(몸만) [추정]. CDV 재질 엔트리 +0x1C, +0x2C(0x1F5/0x1FE, MFX 추정), off[2] 바이트(접촉 +0x38) 의미 미상.
- 관절 활성 비트(+0x64, 0x13AAA8)로 구를 끄는 규칙 미구현 (예: 블레이드를 등에 멨을 때).
- 타입 10 u8 채널(부착 상태)로 바닥 도끼·무기 가시성 제어 미구현.
- ~~블레이드 사슬(체인)·트레일(이벤트 17/18)~~ → 45 문서 §3, 블레이드 모델은 레벨 변경 시 교체하지 않음(구는 레벨 무관 동일).
- 원작 접촉 단계의 프레임 보간 방식 세부(0x13C400 앞부분 VU0 코드) 미확인 — 엔진은 선형 스윕.
