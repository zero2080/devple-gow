# 30. 구현: 레벨 카메라 시스템 (CameraRig)

원작 카메라 관리자와 카메라 상태 갱신을 실행 파일에서 읽어 재구현했다. 포맷(CAM_ / NCV_ / CMV)은 12 문서 §3.

```bash
./gradlew :engine:run --args="--game ATHN01A --auto-run 5"                                 # 갑판 레일 카메라
./gradlew :engine:run -PgowDebug --args="--game ARENA06"                                    # [카메라] 전환 로그
./gradlew :engine:run --args="--game PAND01A --entry gomusicgrppan1c1start --auto-run 3"   # 판도라 신전 방 카메라
./gradlew :tools:run --args="level:info gow-rom/ATHN01A.WAD"                                # 카메라 파라미터 / 레일 / 전환표
```

## 1. 실행 파일 지도
| 주소 | 역할 | 신뢰도 |
|------|------|--------|
| `0x13E790` | CAM 로더: 0x70.. → 정의 +0x10, 거리 쌍 정렬, 레일/이름/전환표 | 확실 |
| `0x13E358` | NCV 로더: +8 = 구간 포인터, +0xC = 매듭 포인터 | 확실 |
| `0x1EDF48` | 카메라 리셋: 상태 링을 템플릿(0x1F2500)으로 채운 뒤 a1(이름 구조체) 이 있으면 `"RCM_"+이름` 을 전환 0 으로 push·질의 플래그 = +0xB0, 없으면(`default`) **볼륨 선택 0x1EE8E0** 결과(없으면 Follow 0x337B68) 를 전환 0 으로 push, 질의 켬. Warp_Jump(0x1EFC98)·레벨 내리기(0x1EE240)·세이브 복원(0x1EE4E8) 이 부른다 (§7) | 확실 |
| `0x1EE658` | **SwitchCamera** (Internal 0x8): `RCM_` 붙여 GO 조회 → 후보 목록(0x306CF0)에 한 프레임 추가 | 확실 |
| `0x1EE730` | Internal 0x9: 전역 0x29F138 = CMV 볼륨 질의 on/off | 확실 |
| `0x1EE8E0` | 선택: 볼륨 질의(구/선분, 콜백 0x1EE760/0x1EE830) → 활성 목록 갱신 → 우선순위 → 전환 시간 | 확실 |
| `0x1EE510` | 영웅 상태 마스크 (CAM 플래그 하위 바이트와 AND, 0 이면 후보 탈락): bit0 = 영웅 하위 객체 +0x1A0 바이트, 영웅 +0x174 의 &3→2, &0x400→4, &0x30→8, &0x40→0x10, &0x380→0x20, &4→0x40, &8→0x80 (§7.1) | 비트 계산 확실, 상태 의미 추정 |
| `0x1EF310` | 프레임마다 선택 후 바뀌면 `0x1EEE88`(새 상태 push, 전환 시간). 컷(≤0.1초)이면 입력 기준 방향을 1초 유지(0x1DCE80) | 확실 |
| `0x1EF3F0` | 상태 링 진행: 경과 += 1/60, α = 경과/전환시간, 모든 상태 갱신 후 `0x1F3C90` 으로 연쇄 보간 | 확실 |
| `0x1F3768` | 상태 갱신: 대상(0x1F26E8) / 점프 높이 고정(0x1F2850) / 위치(0x1F29A0 또는 고정) | 확실 |
| `0x1F29A0` | 궤도·레일 카메라 본체 (모드, 레일 t, 각도 범위, 거리). 0x400 = 레일 접선 좌표계 (§7.2) | 확실 |
| `0x1F20F0` | 레일 t 첫 표본: 매듭 수 × 4 등분 중 최소 (앞쪽 우선), 전환 시간 있으면 −100·(n·직전 정면) | 확실 |
| `0x1F1AA0` / `0x1F1280` / `0x1F12F0` / `0x1F1648` | 레일 지역 탐색 (보폭 0.05 걷기, ×−½) / 거리 비용 / 거리+각도 ×1000 (0x200) / 범위로 자른 방향 거리 (각도 범위 + 0x200 없음) | 확실 |
| `0x1F2500` / `0x1F3A38` | 상태 템플릿 (원점·단위 행렬·깊이 200·피치 −90) / 새 상태: 직전 상태 복사, +0x4C = 전환 시간, 레일이 바뀌면 t = −1 | 확실 |
| `0x1A8E10` | Warp_Jump·Goto: Start 슬롯 +0xB8 CameraName → SwitchCamera (한 프레임 후보) | 확실 |
| `0x1F3BC0` | 3차 Hermite (p0, v0, p1, v1, s) | 확실 |
| `0x1DCC20` | 영웅을 카메라 대상으로 등록: 오프셋 (0, 1.5·16, 0) | 확실 |
| `0x1EE5D8` | 후보 목록 0x306CF0 (개수 0x29F134) 에 GO 추가 (중복 무시) — SwitchCamera·볼륨 질의 콜백 공용 | 확실 |
| `0x1EEE88` | 새 상태 push 뒤 **전역 이벤트 0x40D(1037)**, 인자 = 카메라 이름 | 확실 (ATHN02C AlcWall/MintWall 이 "SparkCam13" 으로 받음) |
| `0x1F3A38` | 상태 초기화: CAM GO 에 애니가 있고(+0x104→+0x88) 플래그 **0x1000** 이면 0x1004B8/0x131038 로 애니 처음부터 | 확실 (분기), 의미 추정 |
| `0x1F38C8` | 카메라 이름이 `"AresCamera1"`(0x29F194) 이면 전용 함수 `0x1F67C0` | 확실 |
| `0x1C3370` | 아레스 클래스(Enemy1 하위 클래스 2, vtable 0x2BB2D0, 생성 0x1CEBA0 의 class 2) 갱신: 매 프레임 `SwitchCamera("AresCamera1", 0)` | 확실 |
| `0x1F67C0` / `0x1F5C58` / `0x1F6430` | AresCamera1 본체 / 레일 t 비용 / 붐(눈 거리) 비용 — §6.3 에 전부 해독 | 확실 (VU0 매크로 해독) |
| `0x1F5400` | CameraAI_Tweaks: "f line angle" … "boom speed out" 을 전역 0x29F198.. 에 등록 (TWK /GlobGame/BAR 이 덮어씀) | 확실 |
| `0x1F59A0` / `0x1F5850` / `0x1F5AB8` | 아레스 생성 0x1C2CF8 이 부름: 카메라 대상 슬롯 4개 등록 + 영웅 기본 슬롯(0x29DC24) 끔 / 슬롯 반지름·가중·오프셋 / 해제 | 확실 |
| `0x1F3F78` / `0x1F4130` / `0x1F4138` | 카메라 대상 슬롯 (0x304790, 0x50 × 10): 등록 (모드 = 관절 번호 또는 −1..−4) / 위치 | 확실 |
| `0x1F26E8` | 카메라 대상 T: 플래그 0x10000 이면 슬롯(+0x4C bit0) 가중 평균 (+0x48), 아니면 영웅 | 확실 |

## 2. 동작 (재구현 규칙)
**선택** — 매 틱 대상점이 든 모든 CMV hull 의 카메라 + 스크립트 요청 = 후보. 활성 목록에서 후보가 아닌 것을 지우고 새 후보를 뒤에 붙인다.
우선순위(0xBC) 최대, 동순위면 목록 뒤쪽(=나중에 들어온 볼륨) 또는 전환표에 현재 카메라가 prefer 로 적힌 것. 후보가 하나도 없으면 **현재 카메라 유지**(히스테리시스).
레벨 시작부터 아무 볼륨도 없으면 기본 Follow 카메라(원작 0x337B68 — 내부 미해석, 진행 방향 뒤 150 유닛 / −18° 로 근사).

**전환** — 새 카메라의 전환표에서 이전 카메라 이름을 찾으면 그 시간, 없으면 0xB8. 0 이하면 컷. 전환 중엔 이전·새 상태를 모두 갱신하고 상태 공간
(대상, 요, 피치, 거리, FOV)을 보간하므로 눈이 대상 둘레로 호를 그린다. 원작 Hermite 의 속도항은 0 으로 두었다(= smoothstep) [근사].

**한 카메라 풀기** (`CameraRig.solve`)
1. 대상 T = 영웅 발 + (0, 24, 0). 플래그 0x800/0x4000 이면 공중에서 이륙 높이 유지, 착지 후 차이를 프레임당 ×0.9 로 해소.
2. 플래그 0x100 없음 → 위치 고정. 0x40000 이면 주시점 = 카메라 행렬 × 0xC0(보통 정면 80), 아니면 T 를 본다.
3. 0x100 있음(궤도) → 설계 요/피치 = 카메라 −Z 의 각. 모드 3(레일)이면 기준점 = 레일 P(t), 모드 2 면 카메라 위치.
   - 레일 t: 비용 = |T−P| 가 [0x84, 0x80]×16 밖인 정도; 플래그 0x200 이고 범위 제한이면 max(거리비용, (각도 초과)·1000).
     이전 t 에서 지역 탐색(처음엔 128 표본 + 탐색), t += (t′−t)·(1−감쇠^(dt·60)). 닫힌 곡선은 감김.
   - 요/피치: 모드 1 = 설계 각, 2·3 = (T − 기준점) 각. 범위(0x90/0x94)로 설계 각 둘레에 자름.
   - 거리: 모드 2·3 이면 |T − 기준점|·c (모드 2 는 (1−c) 몫을 [0x84,0x80]×16 로 자름), 모드 1 이면 이전 거리. 최종 [0x8C, 0x88]×16.
   - 눈 = T − 방향(요, 피치)·거리.
4. FOV = 0x70 을 4:3 가로 화각으로 보고 세로 = 2·atan(0.75·tan(f/2)) (0.95 → 42.2°) [추정]. 와이드 창에서는 세로 화각 유지.

**스크립트** — Internal 0x8 SwitchCamera(이름): 한 틱 후보. 볼륨 질의가 켜져 있으면 다음 틱 볼륨 카메라로 돌아가므로 원작 스크립트는
Internal 0x9(0) 으로 질의를 끄고 쓴다. Vis 이펙터 슬롯 4(Attr11_1, 속성 0x11 = 충돌 서버 볼륨으로 추정) 2/1 → 그 오브젝트의 CMV 끔/켬.
SwitchCamera 의 두 번째 값(원작 a1 = 1)은 스크립트 인자가 아니라 호출 측 계산값이며, 1 이면 전환 시 입력 기준 행렬을 바꿔 둔다(0x7B0) [추정].

## 3. API
```java
CameraRig rig = new CameraRig(levelData);
rig.update(dt, heroFeet, heroYaw, airborne);   // 고정 틱
rig.apply(camera, alpha);                       // 렌더 보간 → eye/target/fovDeg
rig.request("RCM_LiftCam");                     // Internal 0x8
rig.setVolumeQuery(false);                      // Internal 0x9
rig.setOwnerVolumes("goidlecamgroup", false);   // Vis Attr11_1
rig.current(); rig.pose();                      // 현재 카메라 이름 / 상태 (target, yaw, pitch, dist, fovDeg)
```
도구 쪽: `NcvCurve`(parse / eval / tangent / wrap), `CameraDef.Params`·`Transition`, `LevelData.curves`, `PlacedCamera.rail()`.
`GameScene.updateCamera` 는 `rig.apply` 만 부르고, 갱신은 `update()` 틱에서 한다. `GowMain --entry <인스턴스>` 로 진입점 지정.

## 4. 검증
- 테스트 `CameraRigTest` 5개: NCV 매듭 연속·월드 좌표, CAM 파라미터/전환표, 갑판 달리기(Boat1Cam4 → Boat1Cam1, 거리 ≤ 160, 설계 요 고정),
  스크립트 요청 + 질의 끔 유지, ARENA06 거리 160 추적.
- 스크린샷 (`work/camera/`):
  - `athn_idle.png`: 시작 — Boat1Cam4(우선순위 5)가 갑판 위 크레토스를 정면 가까이서 잡음.
  - `athn_run_a.png`: 2.5초 달린 뒤 Boat1Cam1(전환표 1.5초 블렌드) — 레일을 따라 설계 각도로 뒤에서(최대 160 유닛) 추적. 원작 갑판 구간과 같은 3인칭 구도.
  - `athn_run_b.png`: Boat1Cam2 → Cam5 → Cam3 전환 (크레토스가 갑판 아래로 떨어져 Cam3 이 아래에서 올려다봄 — 낙하는 기존 충돌 문제).
  - `arena06.png`: camera1 (모드 1/1, 거리 [160,160]) — 원작 신의 시험 아레나처럼 고정 각도로 따라감.
  - `pand01a.png`: HRcam3 (모드 2/2) — 방 구석 고정 위치에서 크레토스를 조준, 원작 판도라 신전 방 카메라와 같은 구도.
- 벽 관통: 레벨 카메라는 설계 위치/레일 위에만 있으므로 벽 안에 들어가지 않는다. 볼륨 밖 Follow 근사에는 충돌 회피가 없다.

## 5. 남은 것
- IdleCam 류: 관절 애니 카메라 자체는 §6 으로 지원하지만, **CMV 볼륨으로 뽑히는** IdleCam(ATHN01A 시작 9.5초 인트로)은 기존 시작 동작을 바꾸지 않으려고 아직 볼륨 후보에서 뺀다 (스크립트 요청은 허용).
- 원작 Follow, 다중 대상 0x10000 일반화(BMI "Camera Target Weight" — 슬롯 구조는 §6.3 으로 해독), 화면 데드존 0x98..0xA4, 0x400 접선 분기, 카메라 흔들림/필터/DOF 템플릿.
- 영웅 상태 마스크(플래그 하위 바이트)로 후보 거르기 — 마스크 계산은 해독(§7.1)했지만 영웅 +0x174 상태 비트 ↔ 엔진 상태 매핑이 추정이라 아직 적용 안 함
  (0xFE/0xFF 카메라가 대부분이라 평지 걷기에는 영향 없음).
- Hermite 속도항, 컷 시 입력 방향 1초 유지(0x1DCE80).
- ~~0x400 접선 분기~~ (§7.2).

## 6. 스크립트·보스 카메라 (관절 애니 카메라, AresCamera1)

### 6.1 스크립트가 카메라를 켜는 방법 [확실: ESC 바이트코드]
레벨 스크립트는 카메라 엔티티 타입이 따로 없다. **EventSensor 의 Action** 이 `Internal 0x9(false)` (CMV 질의 끔) + `Internal 0x8 SwitchCamera("이름")` 을
부르고, 지연(Delay) 된 짝 센서가 `Internal 0x9(true)` 로 질의를 되켠다. 질의를 끈 동안은 후보가 비어 **마지막 카메라가 유지**되므로 한 번의 요청으로 충분하다.
ATHN01E 히드라 (이벤트 1035 = 기술 시작, Filter 250 = 히드라):

| ESC | 조건(기술) | 동작 |
|---|---|---|
| BossLeftFight | 806 / 807 / 808 / 809 | 질의 끔 + grabLeftCam / leftStrugCam / leftFailCam (2초 뒤 켬) / leftWinCam (3.33초 뒤 켬) |
| BossRightFight | 802 / 803 / 804 / 805 | grabRightCam / rightStrugCam / rightFailCam / rightWinCam (같은 규칙) |
| BossFInalGame | 810 / 811 / 812 / 813 / 816 | bossKillCam / "bossKillStrug" / bossKillWinCam (1.13초 뒤 켬) / bossKillFailCam (4초) / bossKillFail04Cam (3.33초) |
| PinLeft / PinRight (Filter 253 / 252) | 114 / 110 | 아기 히드라 돛대 핀 pinLeftCam / pinRightCam (2.5초 뒤 켬) |
| a01Mp01BossIntro | 컷신 끝 501 | SpawnCam, `camGRPdeckCine-VisCamOFFidle` 이 7.2초 뒤 질의 켬 |

- `"bossKillStrug"` 은 CAM_bossKillStrugCam 과 이름이 달라 원작 조회(0x17A7B8, 정확한 이름)도 실패한다 → bossKillCam 이 계속 쓰인다 (ANM 12.5초가 처형 전체를 덮음).
- 보스 잡기 동기(BossSync) 중에도 스크립트·카메라는 그대로 돈다 — 동기는 영웅/보스 기술만 맞추고 카메라는 1035 이벤트로 바뀐다.
- ATHN02D 메두사 방: MedusaHeadCam/MedusaSpawnCam (CMV 우선순위 100), DoorReveal·MedusaRipZone 이 같은 0x9/0x8 패턴. ATHN08A/C 의 FireBallCam(PokeCam) 은 Vis Attr11_1 로 CMV 를 켜고 끔.

### 6.2 관절 애니 카메라 [확실: 데이터 + 0x1F3A38]
- 카메라 오브젝트(예 `gobossleftfight`: 관절 BossLeftFight / grabLeftCam / leftFailCam / leftWinCam, ANM_bossleftfight act 1 개 3.37초)는 **월드 원점 인스턴스**이고,
  카메라와 같은 이름의 관절이 있으면 그 관절을, 없으면 관절 0 을 따른다 (LevelData 와 같은 규칙). CAM 0x08 = 1 표시는 관절이 있는 카메라 일부에만 있어 판정에 쓰지 않는다.
- 카메라 월드 = CAM 로컬 · (ANM 표본 관절 월드 · 인스턴스). 위치는 대개 고정이고 회전이 패닝한다 (grabLeftCam: −Z 가 (−0.87,−0.34,−0.36) → (−0.47,−0.04,−0.88), 히드라 머리를 따라감).
- 플래그 **0x1000** (전부 0x434FE/0x34FE 계열): 이 카메라가 새 상태로 들어올 때 애니를 0 초부터 [원작 0x1F3B60 분기]. 애니 끝은 마지막 프레임 유지 [추정].
- 이 카메라들은 0x100 이 없고 0x40000 → 위치 고정 + 주시점 = 행렬 × (0,0,−1.6) — 즉 관절 −Z 를 그대로 본다.
- 전환마다 `CameraRig.onSwitch` → `ScriptWorld.postEvent(1037, 이름)` (원작 0x1EEE88).

### 6.3 AresCamera1 [확실: 0x1F67C0 해독, 수치는 TWK]
- 원작: Enemy1 생성 0x1CEBA0 이 템플릿(+0x18 → 0x243700 결과) +0x80 값으로 클래스를 고른다: 0 일반(0x360 바이트), 1 (0x400), **2 아레스(0x370, vtable 0x2BB2D0)** [분기 확실, 필드가 어느 TWK 값인지 추정 — goBAR/goBA2]. 그 갱신이 **매 프레임** `SwitchCamera("AresCamera1", 0)` — CMV 없이도 아레스가 살아 있는 동안 후보.
  카메라 갱신 0x1F3768 이 이름을 비교해 0x1F67C0 으로 간다 (플래그 0x100 여부와 무관). CAM_AresCamera1 (ATHN08A/08C, 플래그 0x57DFE, 레일 NCV_AresCamRail = 아레나 +z 쪽 반원).
- **대상 슬롯** (아레스 생성 0x1C2CF8 → 0x1F59A0): 영웅 기본 슬롯(0x29DC24, 오프셋 (0,24,0))을 끄고 4개 등록 — 영웅 관절 `vertebrae4`·아레스 관절 `BAR01JOCVEC1`
  (+0x4C = 2, 가중 0.35 "Camera Target Weight"), 영웅·아레스 몸 (모드 −4, +0x4C = 1, 가중 1, 오프셋 (0, ("bowl base" − "bay floor")·16 = 14.92, 0)). 반지름 +0x44 = "Camera Target Radius"·16 = 22.4.
  카메라 대상 T (0x1F26E8, CAM 0x10000) = bit0 슬롯 가중 평균 = **두 몸의 중점**.
- **수치 출처**: CameraAI_Tweaks 0x1F5400 이 아래 이름을 전역에 등록하고 R_PERMK TWK `/GlobGame/BAR` 가 덮어쓴다 (bool 은 하위 바이트: "f line symetrical" 0x3FB30000 → 거짓, "obscured symetrical" 0x41200001 → 참).
  "bowl h delta"(4), soft_clamp_width(0.1), 레일 감쇠 0.9 는 TWK 에 없어 실행 파일 값. `AresCameraTest.constantsMatchBarTweaks` 가 TWK 와 대조.
- **프레임마다 (0x1F67C0)**:
  1. aim = 이전 눈 + 이전 정면 · 이전 대상 깊이 (상태 +0x30 − +0x20·cos(+0x88)·cos(+0x8C)·+0x90).
  2. 레일 t: 처음(t &lt; 0)엔 매듭 수 ×4 등분 표본 최소 (0x1F20F0), 이후 이전 t 에서 지역 탐색 (0x1F1AA0: 보폭 0.05, 더 나쁘면 ×−½, 점 이동² ≤ 0.0015 에서 멈춤),
     t += 0.1 · softclamp(t* − t, ±"max rail speed"(0.36)/60) (0x1F5398). 레일 점 P 는 눈이 아니라 **방향**만 정한다.
  3. 레일 비용 0x1F5C58(aim, P): 이전 화면 회전(전역 카메라 0x29F16C 의 시야 행렬)으로 본 두 몸의 수평 화면각 a_i 와 폭 w_i = 22.4·100/수평거리 —
     가림 = 두 폭 구간 겹침 × "obscured weight" 80.6, 분리 = ["seperating min" 0, "max" 40] 밖 × 10, 최소 거리 = (8·16 − 가까운 몸 거리) × 100,
     테두리 = aim 에서 P 쪽 수평 반직선이 그릇 원(중심 (0, −3.12·16), 반지름 9·16)을 나가는 거리 &lt; 25 m 이면 × 10, f line (P − aim 과 몸 연결선 각 &lt; 60°) 가중 0, dolly rot 가중 0.
  4. 붐 f (눈 거리): E(f) = T − f·normalize(aim − P) (수평), **높이 = 그릇 면** (0x1F62E8: "bowl base"·16, 반지름 ±4 m 사이 Hermite(0,0,5,1.25), 밖은 기울기 1.25).
     f ∈ [0, |aim − P|수평] 를 이전 수평 거리에서 보폭 −1 (나쁘면 ×−½, 눈 이동² ≤ 0.001 에서 멈춤) 탐색. 비용 0x1F6430 = 먼 몸 &gt; "maximum dist"(8 m) ×10,
     가까운 몸 &lt; "minimum dist"(6.9 m) ×100, 두 관절 화면 수평 각차 &gt; "horizontal max" 35° ×500, 수직 &gt; 25° ×1000, f·10/16.
     변화량은 softclamp("boom speed in" −4·16/60, "boom speed out" 27·16/60).
  5. 방향: 요 = (aim − P) 의 요, 피치 0 인 회전에서 두 관절 화면각의 평균만큼 돌린다 (0x184A98) — 영웅 가슴과 아레스 몸통의 각 중점을 본다.
- softclamp(0x1F5258): 반범위 × 0.1 폭에서 Hermite 로 이어 붙인다 (원작 그대로 경계 밖 값과 정확히 이어지지 않음).
- 엔진: `level/AresCamera` (상태: 필터된 t, 이전 눈·정면·깊이), `CameraRig.request(name, 아레스 몸, 영웅 vertebrae4, 아레스 BAR01JOCVEC1)`,
  `AresFight.tick` 이 `BossFight.jointWorld` 로 관절 월드 위치를 넘긴다. 이전 근사(주시점 = 영웅 + 0.35·(아레스 − 영웅), "f line angle" 최소화)는 제거.

### 6.4 보스 쪽 보정 (37 문서)
- 히드라 방향을 스폰 엔티티 방향으로 **고정** (위치만 고정하던 것을 방향까지). GameScene 이 보스 스폰 방향을 엔티티 행렬에서 읽도록 고침 (BossRig 와 같은 규칙).
  잡기 카메라가 월드 고정 오브젝트라 동기 장면도 스폰 방향 기준으로 저작됐다고 본다 [추정] — 고치기 전엔 AI 가 히드라를 영웅 쪽으로 돌려 HERO_TO_BOSS 정렬된 크레토스가 망대 밖 허공에 놓였다.

### 6.5 검증
- `BossCameraTest` (헤드리스, 실데이터): 히드라 잡기 1035(806/802) → grabLeftCam/grabRightCam, 질의 끔 유지, ANM 0x1000 재시작(0.5초 뒤 애니 시간 0.4–0.6), 연타 → *StrugCam, 성공 → *WinCam → 3.33초 뒤 볼륨 카메라 복귀,
  처형 810 → bossKillCam (bossKillStrugCam 은 안 켜짐) → 812 BossKillWinCam, 아레스 1·3단계 AresCamera1 활성, 눈 높이 = 그릇 면, 레일 t ≥ 0,
  영웅 가슴과 아레스 관절 BAR01JOCVEC1 이 함께 화면 안 (잡기 동기 중 포함). 모든 장면에서 크레토스가 화면(4:3 절두체) 안.
- `AresCameraTest`: TWK /GlobGame/BAR 대조, 0x1F0D00 범위 밖 정도·softclamp·그릇 높이.
- 스크린샷 (`work/shots/`, 커밋 안 함): `cam_hydra_grab0.png` (잡기 직후 망대 위 크레토스 + 내려찍은 머리), `cam_hydra.png` (grabLeftCam 1.7초: 머리 쪽으로 패닝),
  `cam_hydra_strug.png` (leftStrugCam: 망대 위에서 두 팔로 턱을 받치는 크레토스), `cam_hydra_kill.png` / `cam_hydra_kill2.png` (bossKillCam 처형 연출), `cam_ares1.png`, `cam_ares3.png`.
- 원작 AresCamera1 (`work/shots/37b/`): `ares1_b.png` (1단계: 낮은 그릇 면 눈에서 물을 헤치는 크레토스와 공중의 아레스를 함께), `ares1_grab.png` (잡기 동기),
  `ares3_a.png` (3단계: 크레토스 왼쪽, 아레스 오른쪽, 전경 기둥).


## 7. 레벨 시작·도착 카메라 (43 문서 §7)

### 7.1 원작 순서 [확실: 코드]
| 진입 | 흐름 |
|---|---|
| 레벨 시작 (0x21FD48 → 0x1AC348) | 기본 Start = 0x1AE678 (Start 목록에서 레벨 이름이 맞는 첫 모드 1) → **Warp_Jump** |
| Warp 도착 (0x1B2CE0) | 0x1AE710 (모드 2 + 슬롯 0 = 떠나온 레벨, 없으면 첫 모드 1) → Warp_Jump |
| Goto (GameMgr 7) | 같은 이름 Start → Warp_Jump |
| **Warp_Jump** 0x1B2E08 | 영웅을 Start 행렬로 옮김 → **0x1EFC98**: 0x1EDF48(0) = 상태 링 리셋 + 볼륨 선택(영웅 대상점) 을 전환 0 으로, 입력 기준 행렬 = Start 방향, 0x1EF310 한 번 → **0x1A8E10**: Start 슬롯 +0xB8 (CameraName) 이 있으면 SwitchCamera(이름) — 다음 선택의 한 프레임 후보 |
| 이음 (LoadCheck, GameMgr 6) | 현재 레벨 이름만 바뀐다. 카메라 관리자는 그대로 (활성 목록·상태 유지), 떠나온 레벨은 다른 슬롯에 남아 그 CMV 도 계속 질의된다 |
| 레벨 내리기 (Hero_LoadWads → 0x1EE240) | 현재 카메라가 내려가는 레벨 것이면 0x1EDF48(0) 리셋 |

선택 0x1EE8E0 세부: 후보 = 스크립트 요청(0x306CF0, 호출 순서) + 대상점 구 질의(0x1EE830) + **이전 대상점→현재 선분 질의(0x1EE760)** (리셋 직후 제외, +0x804).
활성 목록은 후보에서 빠진 것만 지우고 새 후보를 뒤에 붙인다. 각 활성 카메라는 (CAM 플래그 & 영웅 마스크 0x1EE510) ≠ 0 이어야 하고, 우선순위가 크거나 같으면
뒤엣것이 이긴다. 전환표에 현재 카메라가 prefer 로 있으면 같은 우선순위일 때 최선 자리와 **목록에서 자리를 바꾼다** (다른 우선순위면 건너뜀). 후보가 없으면 현재 유지.
(엔진 select 는 prefer 를 동순위 우선으로만 처리 — 자리 바꿈·다른 우선순위 건너뜀은 아직 [근사].)
볼륨 질의는 레벨 슬롯 구분 없는 충돌 월드(0x29C978)에, RCM_ 이름 조회(0x17A7B8)는 전역 GO 에 걸리므로 **두 슬롯 레벨의 CMV·카메라가 함께 후보** 다.

### 7.2 레일 비용 [확실: 0x1F20F0, 0x1F1280/0x1F12F0/0x1F1648, 0x1F1AA0]
- 비용 함수: 요 범위 &lt; 180 또는 피치 범위 &lt; 90 이면 0x200 → 0x1F12F0 (max(거리 비용, (요·피치 초과)·1000)), 0x200 없음 → **0x1F1648**,
  범위 없음 → 0x1F1280 (거리 [min, max] 밖인 정도). 다중 대상(0x10000, 전역 0x29F18C ≥ 2) 이면 0x1F19D0 이 슬롯별 가중 합.
- **0x1F1648**: v = 기준점 − P(t) 를 좌표계로 옮겨 요·피치를 설계 각 ± 범위로 자르고(0x1F0C38, 감김 고려), 자른 방향 d(0x1F0E78) 로 되돌려
  **v·d** 가 [min, max] 밖인 정도. 범위 밖 방향의 레일 점은 투영 거리가 짧아져 비용이 커진다 — 레일이 영웅 곁을 지나도 설계 정면 쪽(영웅 뒤) 점을 고른다.
- **0x400**: 좌표계 = 레일 접선 (행 X = 위(0,1,0) × 접선, Y = 접선 × X, Z = 접선; 0x185048 역행렬). 0x400 이 없으면 월드 축. 설계 각은 카메라 GO 의
  −Z 로 월드에서 구하므로 0x400 레일에서는 "접선 기준 요 180 = 접선 방향을 봄". 눈 각 자르기(0x1F3354)도 같은 좌표계 — 시선을 접선 좌표로 옮겨 자르고 되돌린다.
- 첫 t (상태 +0x48 &lt; 0): 0x1F20F0 = 매듭 수 × 4 등분 + 끝점 표본 중 최소 (같으면 앞쪽). 기준점 = 상태 +0x30 − +0x20·(+0x90·cos(+0x88)·cos(+0x8C))
  = 대상을 이전 시선 축에 투영한 점. 상태 +0x4C(전환 시간) &gt; 0 이면 비용 −= 100·(normalize(기준점 − P) · 직전 정면).
- 이후 프레임: 0x1F1AA0 (보폭 0.05 로 비용이 줄면 계속 걷고, 늘면 ×−½; 같으면 이미 그 방향으로 와 있으면 ×−½; 직전 최적점과 후보점 거리² ≤ 0.0015 에서 멈춤),
  t += (t′ − t)·(1 − 감쇠) (닫힌 곡선은 짧은 쪽으로 감김).
- 새 상태(0x1F3A38)는 직전 상태를 복사하고 레일(+0x44)이 같으면 t 를 그대로 쓴다 (ConHallCam5/1, camMagical2/4/5/6 처럼 레일을 나눠 쓰는 카메라).

### 7.3 엔진
- `CameraRig.RailCost` (DISTANCE/ANGLES/PROJECTED), `railFrame` (0x400), 눈 각 자르기도 접선 좌표계, `descend` = 0x1F1AA0, 첫 표본 매듭 × 4.
  전환으로 생긴 상태는 직전 화면(눈·정면)으로 기준점을 만들고 전환 시간이 있으면 정면 보너스, 같은 레일이면 t 유지.
- 리셋(새 장면의 첫 선택 = 레벨 시작·Warp) 기준점: 해독대로면 템플릿(원점, 정면 −Z) 이라 (0, 0, T.z) 가 되는데, 이렇게 하면 실제 Warp 도착
  (ATHN06C → ATHN06B ExCam2, ATRET01C → ATRET01B) 과 PAND03B/03F 기본 시작에서 첫 표본이 엉뚱한 끝점으로 가 눈이 벽 속 극소에 남는다.
  빠진 단계가 있다고 보고 리셋 때는 대상 T 를 쓴다 [근사].
- `CameraRig.setOther(LevelData)`: 다른 슬롯(이웃·숙주·떠나온 레벨) CMV 도 질의, 이름 조회는 이 레벨 먼저. GameScene 이 `loadOther` 때마다 넘긴다.
  이웃 레벨 스크립트의 SwitchCamera·Internal 0x9·Vis Attr11_1 도 같은 카메라에 적용 (`SceneNav.Host.neighbourCamera`).
- 시작 순서: 스크립트 `world.start()` (생성 센서 → Vis Attr11_1 CMV 끔) **뒤에** 첫 선택 → Warp 면 Start CameraName 요청 후 한 번 더 (Warp_Jump 순서).
  원작은 이 레벨이 이웃 슬롯에 먼저 올라와 생성 스크립트가 이미 돈 상태로 들어오므로 [추정].
- 이음/걷기 전환: `CameraRig.handoff()` → 새 장면 `resume()` (활성 목록 이름·레벨, 현재 카메라, 자세, 레일 t). 원작은 LoadCheck 에서 카메라 관리자를 건드리지 않는다.
- 디버그: `--game LEVEL --arrive FROM` (Warp 도착), `--arrive FROM@x,y,z[,요°]` (이음 도착, 떠나온 레벨이 다른 슬롯).
- 테스트 `StartCameraTest` (43 §7.4).
