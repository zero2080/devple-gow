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
| `0x1EE510` | 영웅 상태 마스크 (CAM 플래그 하위 바이트와 AND, 0 이면 그 선택에서 건너뜀): bit0 = 전투 (영웅 대상 슬롯 +8 = 적 무리, 무리의 적 수 +0x1A0), 영웅 +0x174 의 &3→2 지상, &0x400→4 줄, &0x30→8 턱·벽, &0x40→0x10 날기, &0x380→0x20 물, &4→0x40 점프, &8→0x80 떨어짐 (§8.1) | 확실 |
| `0x1EF310` | 프레임마다 선택 후 바뀌면 `0x1EEE88`(새 상태 push, 전환 시간). 컷(≤0.1초)이면 입력 기준 방향을 1초 유지(0x1DCE80) | 확실 |
| `0x1EF3F0` | 상태 링 진행: 경과 += 1/60, α = 경과/전환시간, 모든 상태 갱신 후 `0x1F3C90` 으로 연쇄 보간 | 확실 |
| `0x1F3768` | 상태 갱신: 대상(0x1F26E8) / 점프 높이 고정(0x1F2850) / 위치(0x1F29A0 또는 고정) | 확실 |
| `0x1F29A0` | 궤도·레일 카메라 본체 (모드, 레일 t, 각도 범위, 거리). 0x400 = 레일 접선 좌표계 (§7.2). 머리 0x1F2A34–0x1F2BBC = 화면 데드존 자르기·기준점, 끝 0x1F3640/0x1F36D0 = 각도 범위면 눈 고정·회전만 자름 (§8.2) | 확실 |
| `0x1F1108` / `0x1F11A0` / `0x1F0DC8` / `0x1F0E78` | 상태 → 행렬: R(+0x80 요, +0x84 피치, +0x94 롤), 눈 = T − +0x90·R·방향(+0x88, +0x8C) / 행렬 → 상태 (각·거리 다시) / 카메라 좌표 벡터 → (atan2(−x,−z), 피치) / 그 역 | 확실 |
| `0x1F20F0` | 레일 t 첫 표본: 매듭 수 × 4 등분 중 최소 (앞쪽 우선), 전환 시간 있으면 −100·(n·직전 정면) | 확실 |
| `0x1F1AA0` / `0x1F1280` / `0x1F12F0` / `0x1F1648` | 레일 지역 탐색 (보폭 0.05 걷기, ×−½) / 거리 비용 / 거리+각도 ×1000 (0x200) / 범위로 자른 방향 거리 (각도 범위 + 0x200 없음) | 확실 |
| `0x1F2500` / `0x1F3A38` | 상태 템플릿 (원점·단위 행렬·깊이 200·피치 −90) / 새 상태: 직전 상태 복사, +0x4C = 전환 시간, 레일이 바뀌면 t = −1 | 확실 |
| `0x1A8E10` | Warp_Jump·Goto: Start 슬롯 +0xB8 CameraName → SwitchCamera (한 프레임 후보). 그 전에 Start GO 의 컨텍스트를 이름 조회 범위로 세우고 ([0x330134] vt+0x88 로 내림) 0x17A7B8 → 0x186990 범위 조회 — 떠나온 (꺼진) 레벨 카메라는 못 찾는다 (43 §13.11 3) | 확실 (범위 연결 +0x50 내용은 추정) |
| `0x13EC10` / `0x1303D0` | 카메라 노드 리셋: 부모에 애니 컨트롤러가 있고 관절 +0x60 ≥ 0 이면 +0x104 = 1 (0x1F3B40 의 0x1000 재시작 조건) / 노드 월드 = 로컬 × 부모 애니 관절 행렬 (43 §13.11 1) | 확실 |
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
우선순위(0xBC) 최대, 동순위면 목록 뒤쪽(=나중에 들어온 볼륨). 전환표에 현재 카메라가 prefer 로 적힌 카메라는 이기지 못한다 (§7.1). 후보가 하나도 없으면 **현재 카메라 유지**(히스테리시스).
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
4. FOV = 0x70 을 4:3 가로 화각으로 보고 세로 = 2·atan(0.75·tan(f/2)) (0.95 → 42.2°) [추정]. 와이드 창에서는 **가로 화각 유지** — 원작 투영 0x1692D8 이 x 배율 = 1/tan(f/2) 고정, 세로는 화면비 0x330A30 (와이드 16/9, 0x1B61B8) 로 줄인다 [확실]. 전에는 세로를 유지해 16:9 에서 가로 68.9° (원작 54.4°) — ATHN01A 시작 카메라가 멀어 보이던 원인 (`GameScene.updateCamera`).

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
- ~~IdleCam 볼륨 후보~~ (§8.3), ~~화면 데드존 0x98..0xA4~~ (§8.2), ~~영웅 상태 마스크~~ (§8.1), ~~0x400 접선 분기~~ (§7.2).
- 원작 Follow, 다중 대상 0x10000 일반화(BMI "Camera Target Weight" — 슬롯 구조는 §6.3 으로 해독), 카메라 흔들림/DOF 템플릿 (필터는 45 문서 §1 에서 구현).
- ~~Hermite 속도항~~ (§9.4), ~~컷 시 입력 방향 유지(0x1DCE80)~~ (§9.1), ~~전환표 prefer 자리 바꿈~~ (§7.1, 43 §13.10), ~~패드 회전 입력~~ (§9.6: 소매판에서 꺼짐).
- ~~카메라 흔들림~~: 전투 이벤트 5 (26 문서) + 소리 흔들림 SND_SHAKE_* (§9.2). ~~처형 클로즈업 이벤트 23~~ (§9.3).
- 리셋 기준점 (§8.2 끝): 데드존이 있는 카메라는 해독대로면 |T| 에 비례해 밀린다 — 엔진은 리셋 첫 머리만 데드존 0 으로 [근사].

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
- 카메라 월드 = CAM 로컬 · (ANM 표본 관절 월드 · 인스턴스). ~~위치는 대개 고정이고 회전이 패닝한다~~ (grabLeftCam: −Z 가 (−0.87,−0.34,−0.36) → (−0.47,−0.04,−0.88), 히드라 머리를 따라감).
  **정정 (2026-10-24, 05 §2.8.1, 43 §16)**: 카메라 오브젝트 ANM 은 거의 모두 데이터 타입 [0(p2 3)] 하나 — act 디스크립터 0 회전 · **1 이동** · 2 스케일. 예전 파서는
  항목 수만큼 (1 개) 읽어 이동을 버렸으므로 "위치 고정" 은 파서 착시였다. 원작 배치 (`Animation.sample` — 당시 `sampleRecords`, 전역 전환 뒤 이름 통합, `CameraRig.CamAnim`) 로 전 WAD 관절 카메라 213 개 중
  179 개에 눈 이동이 생긴다: ATHN03AA camSpecialSparky (12 초 동안 계단 위 → 원형 제단 위 z −514 → 다시 위로, PS2 영상 4800–4812 의 커튼 통과·거대한 아레스),
  ATHN03A camFireballShot (+x 120 · +y 52, 불덩이가 오른쪽 위에서 성문으로 = 영상 4978), camMinoZoom (−x 410 로 창살을 따라), ATHN01A IdleCam (갑판 위로 +z 390),
  PAND05B camEleEnter (엘리베이터 판 높이로 올라 따라 내려옴 — 43 §13.11 의 "눈 고정" 정정). 회전 (패닝) 은 두 배치가 같다.
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


### 6.6 고정 스크립트 카메라가 엉뚱한 곳을 보던 것 (QA vs PS2: AthenaCam · twinscam1/TwinCamLeft · camVase)
세 카메라 모두 `0x434FE`/`0x414FE` (0x100 없음, 0x40000) — 위치·회전 = GO 행렬 그대로, 주시점 = 행렬 × 0xC0 (0x1F3768 → 0x1F11A0) [확실: 코드].
CAM 로컬 (회전 행 × 관절 스케일 역수, 위치 = 관절 로컬) × 관절 월드도 원작 0x1303D0 (노드 +0x60 관절, +0x8C 관절 월드 표) 과 같고, 툴 경계 중심 (GO 0x40) 이
엔진 눈과 일치한다 (AthenaCam 눈 (652, −567, 3494) ≈ 경계 (653, −565, 3492)) [확실: 데이터]. **카메라 쪽은 고칠 것이 없었다** — 화면에 들어올 것이 제자리에 없었다.

- **ATHN02A AthenaCam — 배가 부두에 들어오지 않았다** [확실: 데이터 / 추정: 원작 소비 경로]. 석상 (SpawnEnemy shipGroup-topDeck-AthenaMime, AI1 307)·Goto
  지점 MimeSpot·R2 볼륨 ENV_shipGroup 은 정박한 배 goshipgroup 관절 1 기준이라 z ≈ 3903–3936, 카메라 (z 3494, −Z = 부두 쪽) 는 440 뒤의 돌다리를 봤다.
  - `ANM_shiptrans` (10.4 초, Warp 도착 Start `ShipTrans-Anim-WarpFrom1F` 의 Animator "!0") 의 **타입 10 f32 채널 스트림 6 = 관절 1 "Anim" 의 Z**:
    절대 0 → 가산 −1.6/프레임 × 298 → 절대 −480 (05 §2.8). 예전엔 "관절 이동 없음" 으로 읽었다 (43 §13.10).
  - **GO 부모 링크** `GoInstance` 0x58 = (부모 관절 ≪ 16) | 부모 GO id — goshipgroup = 0x100A9 → goshiptrans (id 169) 관절 1 (12 §, 전 레벨 68 건이 모두
    "올라탄 것 → 탈것": 엘리베이터 위 레버, PAND04G 크레인 위 줄 …) [확실: 데이터].
  - 끝 자세 (−480) 에서 석상 3423 · MimeSpot 3456 · 아테나 반짝임 goathenasparkle 3431 (원래 그 자리에 배치) · LadderCam/DocCam20 레일 (≈ 3550–3750) 이 맞고,
    AthenaCam 이 석상을 앞에 낮게, 크레토스를 오른쪽에, 뒤로 계단·난간을 잡는다 (PS2 영상 ~2124–2130 초와 같음). 입항 중 크레토스는 Idle(true) 로 묶여
    갑판에 실려 가고 0.5 초에 PortCam, 10 초 뒤 CDsOn 이 Idle 을 풀고 질의를 켠다 (스크립트 그대로).
  - (2026-10-24 정정, 05 §2.8.1: 이 "타입 10 채널" 은 실제로 act 디스크립터 1 = 타입 0 의 관절 이동이다 — 원작 배치에서는 보통 이동 트랙. ATHN02A Anim 바인드 로컬 z = 0 이라
    결과는 같고, PAND03A AnimHead 는 프레임 0 절대 −192 (= 바인드 로컬) → +160 이라 운반 규칙 (−384 → −32) 과 다르다 [미확인: 원작 화면].)
  - 엔진: ~~`Animation.transportTrack` (관절 이름 "Anim" → 바인드 로컬 + 채널)~~ → 원작 배치의 관절 이동 (디스크립터 1, `Animation.sample`) 그대로 (2026-10-24, 05 §2.8.1):
    ATHN02A 는 바인드 로컬 0 이라 배 입항 −480 이 같고, PAND03A AnimHead 는 −192 → +160 (352 오름). `Platforms.Body` 부모 링크: follow = 부모 관절 처음 월드⁻¹ ·
    지금 → 자식 관절 월드에 곱함 (렌더 자세 `poseInstance`, CDV, 영웅·적 실려 감, Marker, 엔티티 월드 `ScriptWorld.followOwner`, ENV/CMV 볼륨
    `SceneNav.followVolumes`) [근사: 둘 다 몸 (CDV·Animator·R1 대상) 인 짝만 — 렌더만 있는 반짝임 GO 는 아직 따라가지 않음].
  - 적도 디딘 발판에 실려 간다 (원작 생물 +0x24C 발판 객체, 32 문서) — 갑판 위 석상. 배 모델의 MAT_stencil 판 (flags2 bit 28, 그림자 볼륨 전용 — 원작은 초록
    채널에만, 47 §7.4) 을 스키닝 경로가 흰 판으로 그리던 것도 뺐다 (`WadAssets.stencilOnly`, 정적 경로도) [확실].
- **ATHN01F twinscam1 / TwinCamLeft — 쌍둥이가 매트리스 위로 떠 있었다** [확실: 데이터·코드]. 쌍둥이 (Twins-AOXPTtwins, y 96) 스켈레톤은 루트가 바닥이고
  골반이 +22 (앉은 자세). 내비 세계가 없는 적 (트리 없는 소품·보스) 의 바닥 붙이기가 발 + STEP 14 에서 질의해 매트리스 윗면 107 로 올렸다 → 이불이 공중에
  뜨고 두 카메라 (눈 높이 117/126, 30–40 앞) 화면 위로 벗어나 머리판·바닥만 보였다. 질의 시작을 바닥 질의 0x1E3298 의 발 + 6.4 로 (`GameScene.updateEnemies`)
  → 바닥 88, 골반 110 (매트리스 위). R2 → 클로즈업에 둘이 들어온다. ○ 미니게임 동기 (846–850) 그대로.
- **camVase** [추정: 데이터대로]: `ANM_twinscamgrp` 2 초 = 바인드 (요 20.8°, 침대 기둥 항아리 HealthPotion1 쪽) → X −29.5°·Y 0 (발치 바닥, 떨어진 항아리들),
  0x1000 재시작. 엔진은 이것을 그대로 튼다 — 처음 2 초는 기둥 항아리, 뒤는 바닥. 성공 camVaseSuccess 는 그 끝 방향에서 항아리 가까이로 시작.
  원작이 끝까지 항아리를 보이는지는 영상 구간만으로는 미확인.
- **ATHN02A 도착 화면의 AniRvlCam** [확실: 로그]: 실제 Warp 도착 (`--arrive ATHN01F`) 은 리셋 → Start CameraName DocCam20 → 0.5 초 PortCam (항구) 으로
  처음부터 아테나 쪽이다. 그물 화면은 갑판 위 자리로 이음 재현 (`--arrive ATHN01F@x,y,z`, 하네스) 해서 ATHN01F 가 켜진 이웃 슬롯으로 돌아 heroEntry
  (질의 끔 + AniRvlCam) 가 발동한 것 — ATHN01F → 02A 는 Warp 로만 이어지므로 원작에 없는 경로.
- 검증 (커밋 안 함, `/private/tmp/claude-501/-Users-obeomsu-IdeaProjects-devple-gow/6f65672f-1b0f-4007-9bee-60aa049603b5/scratchpad/cam/`): `athn02a_final.png` (입항 → AthenaCam 석상 → 질의 복귀), `t1s.png` (twinscam1 쌍둥이), `v1t.png` (TwinCamLeft), `v1s.png` (camVase).
  `WarpArrivalTest.athn02aShipDocksWithShipTransAnimJoint`. ATHN01E 처음 70 초 카메라 전환 40 개는 HEAD 와 같음.
  봇 검증 속성: `-Dgow.auto.novol` (볼륨 방문 없이 목표로), `-Dgow.auto.wait=초` (시작 연출 동안 입력 없음).

### 6.7 0x100 · 0x40000 둘 다 없는 카메라 = 노드 행렬 그대로 (ATHN04A SuicideCam, 43 §18) [확실: 코드]
- 원작 0x1F3768: 0x100 (영웅 추적) 이 없으면 0x40000 과 무관하게 카메라 노드 월드 행렬 (관절 애니 포함, 노드 +0x70) 을 상태 행렬로 복사하고
  (0x1F3904..0x1F3960) 0x1F11A0 으로 각을 뽑는다 — 0x40000 은 주시점을 행렬 × 0xC0 으로 정할 뿐이다 (§6.6). 엔진은 0x40000 이 없으면 주시점 = 영웅이었다.
- 전 WAD 2916 카메라 중 28 개가 둘 다 없다. ATHN04A **SuicideCam** (플래그 0x14FE, ANM JumpPan 11 초 — ATHN04B 가 현재인 채 ATHN04A LedgeJumpCams 가 부름):
  4 초에 눈 y ≈ 3 (떨어진 여자 몸 높이) 까지 내려가 내려다보고 (피치 −68°, 영웅은 등 뒤), 11 초에 난간 위 (y 394) 로 올라와 크레토스를 본다 — 영상 5929–5935
  "위에서 본 원형 바닥, 피 웅덩이의 보라 옷" 과 같다. 예전엔 같은 크레인 경로에서 영웅 (난간) 을 봐 몸이 화면에 없었다.
- 다른 슬롯 레벨 카메라의 관절 애니도 묶는다 (`CameraRig.setOther` → `bindAnimations(other)`, 떠난 레벨 것은 뺌) — 이웃 스크립트가 부르는 SuicideCam·JumpPan 은 ATHN04A 것.
- 시험 `AthensInteriorTest.suicideCamLooksDownAtTheFallenBody`.

## 7. 레벨 시작·도착 카메라 (43 문서 §7)

### 7.1 원작 순서 [확실: 코드]
| 진입 | 흐름 |
|---|---|
| 레벨 시작 (0x21FD48 → 0x1AC348) | 기본 Start = 0x1AE678 (Start 목록에서 레벨 이름이 맞는 첫 모드 1) → **Warp_Jump** |
| Warp 도착 (0x1B2CE0) | 0x1BC3A0: 떠나온 슬롯 레벨의 루트 컨텍스트 (+0x5C) 를 끄고 (0x1BE688 + 0x1BEF10 → 서버 vt+0x44) 새 레벨을 켬 (0x1BEEB8, vt+0x34) → 현재 슬롯 (0x1BE4C0). 떠나온 레벨은 **메모리에 남지만 꺼진다** (스크립트 루프 0x1A7D68 은 켜진 레벨만 — 렌더·충돌·CMV 도 빠진다 [추정]). 준비 플래그 0x29D914 = 0. → 0x1AE710 (모드 2 + 슬롯 0 = 떠나온 레벨, 없으면 첫 모드 1) → Warp_Jump |
| Goto (GameMgr 7) | 같은 이름 Start → Warp_Jump |
| **Warp_Jump** 0x1B2E08 | 영웅을 Start 행렬로 옮김 → **0x1EFC98**: 0x1EDF48(0) = 상태 링 리셋 + 볼륨 선택(영웅 대상점) 을 전환 0 으로, 입력 기준 행렬 = Start 방향, 0x1EF310 한 번 → **0x1A8E10**: Start 슬롯 +0xB8 (CameraName) 이 있으면 SwitchCamera(이름) — 다음 선택의 한 프레임 후보 |
| 이음 (LoadCheck, GameMgr 6) | 현재 레벨 이름만 바뀐다. 카메라 관리자는 그대로 (활성 목록·상태 유지), 떠나온 레벨은 다른 슬롯에 남아 그 CMV 도 계속 질의된다 |
| 레벨 내리기 (Hero_LoadWads → 0x1EE240) | 현재 카메라가 내려가는 레벨 것이면 0x1EDF48(0) 리셋 |

선택 0x1EE8E0 세부: 후보 = 스크립트 요청(0x306CF0, 호출 순서) + 대상점 구 질의(0x1EE830) + **이전 대상점→현재 선분 질의(0x1EE760)** (리셋 직후 제외, +0x804).
활성 목록은 후보에서 빠진 것만 지우고 새 후보를 뒤에 붙인다. 각 활성 카메라는 (CAM 플래그 & 영웅 마스크 0x1EE510) ≠ 0 이어야 하고, 우선순위가 크거나 같으면
뒤엣것이 이긴다. 전환표에 현재 카메라가 prefer (+0x1C) 로 있으면 같은 우선순위일 때 최선 자리와 **목록에서 자리를 바꾸고 최선 색인은 그 자리 (= 원래 최선)**,
다른 우선순위 (또는 최선이 아직 없음) 면 건너뜀 → **그 카메라는 현재 카메라에서 넘어오는 선택에서 이기지 못한다** [확실: 0x1EECE4–0x1EED58]. 후보가 없으면 현재 유지.
엔진 `select` 도 이대로 (43 §13.10 — 이전 "동순위 우선" 해석은 서로 prefer 인 PAND00D Lv1TunCam3/4 를 매 프레임 번갈게 했다).
볼륨 질의는 레벨 슬롯 구분 없는 충돌 월드(0x29C978)에, RCM_ 이름 조회(0x17A7B8)는 전역 GO 에 걸리므로 **두 슬롯 레벨의 CMV·카메라가 함께 후보** 다.

### 7.2 레일 비용 [확실: 0x1F20F0, 0x1F1280/0x1F12F0/0x1F1648, 0x1F1AA0]
- 비용 함수: 요 범위 &lt; 180 또는 피치 범위 &lt; 90 이면 0x200 → 0x1F12F0 (max(거리 비용, (요·피치 초과)·1000)), 0x200 없음 → **0x1F1648**,
  범위 없음 → 0x1F1280 (거리 [min, max] 밖인 정도). 다중 대상(0x10000, 전역 0x29F18C ≥ 2) 이면 0x1F19D0 이 슬롯별 가중 합.
- **0x1F1648**: v = 기준점 − P(t) 를 좌표계로 옮겨 요·피치를 설계 각 ± 범위로 자르고(0x1F0C38, 감김 고려), 자른 방향 d(0x1F0E78) 로 되돌려
  **v·d** 가 [min, max] 밖인 정도. 범위 밖 방향의 레일 점은 투영 거리가 짧아져 비용이 커진다 — 레일이 영웅 곁을 지나도 설계 정면 쪽(영웅 뒤) 점을 고른다.
- **0x400**: 좌표계 = 레일 접선 (행 X = 위(0,1,0) × 접선, Y = 접선 × X, Z = 접선; 0x185048 역행렬). 0x400 이 없으면 월드 축. 설계 각은 카메라 GO 의
  −Z 로 월드에서 구하므로 0x400 레일에서는 "접선 기준 요 180 = 접선 방향을 봄". 눈 각 자르기(0x1F3354)도 같은 좌표계 — 시선을 접선 좌표로 옮겨 자르고 되돌린다.
- 첫 t (상태 +0x48 &lt; 0): 0x1F20F0 = 매듭 수 × 4 등분 + 끝점 표본 중 최소 (같으면 앞쪽). 기준점 = 상태 +0x30 − +0x20·(+0x90·cos(+0x88)·cos(+0x8C))
  — 단 머리의 데드존 자르기가 눈을 옮긴 뒤의 값이라 데드존 0 이면 T (§8.2). 상태 +0x4C(전환 시간) &gt; 0 이면 비용 −= 100·(normalize(기준점 − P) · 직전 정면).
- 이후 프레임: 0x1F1AA0 (보폭 0.05 로 비용이 줄면 계속 걷고, 늘면 ×−½; 같으면 이미 그 방향으로 와 있으면 ×−½; 직전 최적점과 후보점 거리² ≤ 0.0015 에서 멈춤),
  t += (t′ − t)·(1 − 감쇠) (닫힌 곡선은 짧은 쪽으로 감김).
- 새 상태(0x1F3A38)는 직전 상태를 복사하고 레일(+0x44)이 같으면 t 를 그대로 쓴다 (ConHallCam5/1, camMagical2/4/5/6 처럼 레일을 나눠 쓰는 카메라).
- **두 극소 재확인** (43 §13.9, PAND03F/03J CoreTubeCam4 · PAND02B camFRMCLFFS): 0x1F20F0 은 비용 함수를 인자 t2 로 받지 않으면 (0x1F29A0 은 0) 위 규칙으로 고르고,
  표본은 매듭 수 × 4 + 1 (양 끝 포함), `c.lt.s` 라 같은 비용이면 앞쪽. 0x1F1648 은 범위로 자른 방향과의 투영 (각도 자르기 = 비용 **안**, 눈 회전 자르기 0x1F3640/0x1F3354 는
  t 를 정한 **뒤**). 정면 보너스는 +0x4C (전환 시간) &gt; 0 일 때만 — 리셋 (Warp·레벨 시작) 은 0 이라 없음. 대상 T = 카메라 대상 슬롯 +0x30
  (0x1F26E8: 생물 +0xD0 = 위치 행 (0x1E09C0) + (0, 24, 0), 0x1F4380 이 반경 1.6 끈으로 끌어옴 — Warp_Jump 0x1EFC98 가 리셋 전에 부른다).
  **카메라 갱신에 시선·충돌 질의 없음**: 0x1EF620/0x1EF310/0x1EF3F0/0x1F3768/0x1F29A0/0x1F20F0/0x1F1AA0 의 호출은 수학·GO 조회뿐이고,
  광선 질의 0x11E190 은 패드 디버그 자유 카메라 0x1F0260 (관리자 +4 모드 분기 0x1EFDA8) 에만 있다 [확실].
  CAM 플래그 중 0x8000·0x20000 은 카메라 코드가 읽지 않고, 0x2000 = 카메라 대상 덮어쓰기 (0x304AB0, 0x1F4468) 가 켜져 관리자 +0x7AC = 1 인 동안 상태 갱신 생략 (0x1F38A0).
  → 두 카메라 모두 원작도 먼 극소를 고른다 (데이터대로). ~~(PAND03F/03J)~~ → **43 §44 정정**: 대상 T 는 새 자리가 아니다. 카메라 대상 슬롯 표 0x304790 은 영웅 생성
  (0x1DCC20 → 0x1F3F48) 때만 비워 Warp 를 넘어 남고, Warp_Jump → 0x1EFC98 첫 줄 0x1F4380 이 떠나온 레벨 좌표의 옛 자리에서 새 자리로 반지름 1.6 까지만 끌어온 뒤
  리셋 0x1EDF48 → 볼륨 선택 → 0x1F3A38 → 0x1F3768 이 곧바로 첫 표본을 잡는다 [확실]. 두 극소 비용 차 (3.46 / 2.75) 가 T 1 단위로 뒤집히는 CoreTubeCam4 는 PAND00C 관
  Warp 볼륨 쪽 1.6 (−1.52, −0.06, −0.49) 으로 **u 9.25 (영웅 뒤)** — 영상 3화 5817.8 과 같다. PAND02B 기본 시작 (Warp 아님) 은 그대로.

### 7.3 엔진
- `CameraRig.RailCost` (DISTANCE/ANGLES/PROJECTED), `railFrame` (0x400), 눈 각 자르기도 접선 좌표계, `descend` = 0x1F1AA0, 첫 표본 매듭 × 4.
  전환으로 생긴 상태는 직전 상태 행렬을 복사하고 머리(§8.2)로 기준점을 만든다. 전환 시간이 있으면 정면 보너스, 같은 레일이면 t 유지.
- 리셋(새 장면의 첫 선택 = 레벨 시작·Warp) 기준점: §8.2 (머리의 데드존 자르기가 눈을 옮기므로 데드존 0 이면 T — 이전의 "(0, 0, T.z)" 해독은 이 단계를 빠뜨렸다).
- `CameraRig.setOther(LevelData)`: 다른 슬롯(이웃·숙주·떠나온 레벨) CMV 도 질의, 이름 조회는 이 레벨 먼저. GameScene 이 `loadOther` 때마다 넘긴다.
  이웃 레벨 스크립트의 SwitchCamera·Internal 0x9·Vis Attr11_1 도 같은 카메라에 적용 (`SceneNav.Host.neighbourCamera`).
- 시작 순서: 스크립트 `world.start()` (생성 센서 → Vis Attr11_1 CMV 끔) **뒤에** 첫 선택 → Warp 면 Start CameraName 요청 후 한 번 더 (Warp_Jump 순서).
  원작은 이 레벨이 이웃 슬롯에 먼저 올라와 생성 스크립트가 이미 돈 상태로 들어오므로 [추정].
- 이음/걷기 전환: 원작은 LoadCheck 에서 카메라 관리자를 건드리지 않는다 → 같은 씬 이음 (43 §27) 은 같은 `CameraRig` 의 현재 ↔ 다른 슬롯만 바꾼다 (`swapLevels` —
  상태 링·활성 목록·전환 보간·관절 카메라 시간·꺼진 볼륨·질의 끔이 그대로, 이름 조회만 현재 레벨 먼저, 볼륨 후보 순서는 슬롯 순서로 고정). 새 씬으로 넘어가는 이음
  (보스 레벨이 낌) 만 예전처럼 `CameraRig.handoff()` → 새 장면 `resume()` (활성 목록 이름·레벨, 현재 카메라, 자세, 레일 t — 보간·꺼진 볼륨은 끊김).
- 디버그: `--game LEVEL --arrive FROM` (Warp 도착), `--arrive FROM@x,y,z[,요°]` (이음 도착, 떠나온 레벨이 다른 슬롯).
  이음 재현에는 넘겨받을 카메라가 없으므로 도착점에 CMV 후보가 없으면 `CameraRig.seedApproach` 가 떠나온 레벨의 가장 가까운 후보 자리에서 먼저 고른다
  (원작: 후보 0 이면 현재 카메라 유지 0x1EE8E0 → 걸어 들어오던 카메라가 남는다) [근사: 하네스 전용]. `-Dgow.arrive.open=오브젝트[:애니]` = 문 열린 자세 (43 §13.8).
- 이웃 레벨 스크립트의 Vis Visible (숨김) 과 Animator 도 다른 슬롯 렌더에 건다 — 카메라 전환 이벤트 1037 로 벽을 숨기는 스크립트 (PAND01A AxeAlcWall) 가 이웃이어도 동작 (43 §13.8).
- 테스트 `StartCameraTest` (43 §7.4).
- Warp 도착 (43 §13.9): 도착점을 덮은 스크립트 볼륨은 "안" 으로 시작 (원작 순간이동 프레임은 영웅 볼륨 선분 생략 0x29D90C), 줄 잡기 대기 없음 (`HeroNavigator.warpArrived`).
  Warp 는 계속 다른 슬롯 없이 시작한다 — 원작도 떠나온 레벨을 메모리에 두되 컨텍스트를 꺼 (§7.1) 화면·스크립트에 남지 않는다.
- Warp 도착 첫 T (43 §44): 떠날 때 `CameraRig.targetSlot()` 을 `GameSession.warpTargetSlot` 에 남기고, 새 씬이 첫 갱신 전에 `carryTargetSlot` — 첫 `target()` 의
  1.6 끈 (0x1F4380) 이 옛 자리 쪽 T 를 만들어 볼륨 선택·첫 레일 표본에 쓴다. 하네스 `--arrive FROM` 은 `-Dgow.arrive.slot=x,y,z` (떠나온 레벨 좌표, 없으면 예전 그대로).
  PAND03J/03F ← PAND00C CoreTubeCam4 = 영웅 뒤, PAND02A ← PAND00C = camFromWarp (예전 Follow).
- Warp 도착 Start 의 대상 발동 (Warp_Jump 끝 0x1A9C18, `ScriptWorld.fireStart` — 예 ATHN02A ShipTrans 0.5 초 PortCam), Warp 로 바로 되돌아오면 떠날 때의 화면 상태
  (`GameSession.warpLeft`) — 43 §13.10.

### 7.4 LoadForWarp 슬롯은 꺼진 채 · 내려간 레벨 카메라 리셋 (ATHN01D 그물 검은 화면)
QA (PS2 영상 ~980–1080 초, ATHN01D 돛대 그물): 엔진 자동 플레이가 t≈141 부터 비만 보이는 검은 화면, 크레토스가 몇 픽셀 — 카메라가 ATHN01F 의 `AniRvlCam`
(눈 (548, 114, −535), 다른 좌표계) 에 갇혔다.
- **원인** [확실: 분기]: 봇이 그물보다 먼저 아랫갑판 복도로 가 `toAthn01F-loadAthn01F` (LoadForWarp Athn01F) 를 밟았다. 엔진은 LoadForWarp 레벨도 이웃 스크립트로
  돌려 ATHN01F `heroEntry-CreationSensor1` → `-specialCam` (2 초: CMV 질의 끔 + SwitchCamera AniRvlCam) 이 ATHN01D 안에서 발동, 9.5 초 뒤 질의를 켜는
  `-idleOFF` 전에 봇이 되돌아가 `LoadE1` (LoadWad Athn01E) 로 슬롯이 바뀌어 질의가 꺼진 채 남았다 → 그물 CMV (`goendboatcams` → Boat3Cam12) 를 못 고름.
- **원작**: LoadWad/LoadForWarp 는 0x1BC110(이름, 호출 레벨, **인자**) → 명령 3 (0x1BBA20) → 적재 (Hero_LoadWads 0x1BD270: 슬롯 +0x64 = 인자) →
  끝 (0x1BDBE8: +0x68 |= 4) 에 콜백 0x3368F4 = **0x1B2AA8(슬롯, +0x64)**: 0 (LoadWad) → **0x1BC2E0** (루트 컨텍스트가 꺼져 있으면 켬 0x1BEEB8 → 서버 vt+0x34,
  현재 슬롯 0x1BE4C0), 1 (LoadForWarp) → Warp 준비 0x29D914 = 1 (요청돼 있으면 바로 Warp 0x1B2CE0) 만. 켜는 것은 Warp 0x1BC3A0.
  꺼진 컨텍스트는 스크립트 루프 (0x1A7D68 `+4 & 4`)·충돌·CMV 질의 (43 §13.11) 에서 빠진다 → **LoadForWarp 로 새로 올린 레벨은 Warp 전까지 생성 센서·카메라
  조작이 없다** [확실: 분기 / 추정: 새로 만든 컨텍스트가 꺼진 채 시작 — 0x1BC2E0·0x1BC3A0 이 켜는 이유]. heroEntry 는 Warp 도착 (새 장면 `world.start`) 에서 돈다.
- **레벨 내리기** (Hero_LoadWads → 0x1EE240, §7.1): 현재 카메라가 내려가는 레벨 것이면 0x1EDF48(0) 리셋 (템플릿 상태, 볼륨 선택 전환 0, 질의 켬).
- 엔진: `LevelLink.otherActive()` (LoadWad·이음 도착 = 켜짐, LoadForWarp 로 새로 올림 = 꺼짐, 이미 있는 레벨 LoadForWarp = 그대로, LoadWad = 켬 [추정: 이미 올라
  있는 레벨에 콜백이 다시 불리는지 미추적]). 꺼지면 `GameScene.unloadOther` — 다른 슬롯 충돌·이웃 스크립트·발판 (`SceneNav.clearOther`)·CMV·렌더·라이트 없음.
  `CameraRig.setOther` 가 바뀔 때 떠난 레벨 카메라를 활성 목록에서 빼고, 현재 카메라가 그것이면 상태 링 비움 + 질의 켬 (다음 선택 = 템플릿·전환 0).
- 검증: 자동 플레이 200 초 재녹화 — 복도 (t 140) → 그물 상자 위 (t 142) 에서 `ExitCam1 → Boat3Cam12`, 그물·돛대·하늘 보임 (이전: AniRvlCam 고정, 검은 화면).
  `LevelLinkTest.loadForWarpLevelStaysOffUntilWarp`. 원작 순서 (그물 → 망대 → 상자 → 줄 → 아랫갑판) 로 그물을 오르면 처음부터 문제 없음 (32 문서 그물 해골).
- 같은 대상에 LoadForWarp 와 LoadCheck 를 둘 다 쓰는 레벨 (ATHN03C↔04A, 06A↔06B, PAND00A↔00B, ATRET01A↔01B …) 은 Warp 짝이라 좌표계가 달라
  이음이 원래 일어나지 않는다 (36 §2) — 꺼진 슬롯이어도 바뀌는 것 없음 [추정: 전 레벨 재순회 안 함].

## 8. 영웅 상태 마스크 · 화면 데드존 · 각도 범위 · IdleCam (43 문서 §10)

### 8.1 영웅 상태 마스크 0x1EE510 [확실: 비트·출처 / 추정: 엔진 상태 대응]
선택 0x1EE8E0 은 활성 목록을 돌며 `(CAM 플래그 & 마스크) == 0` 인 카메라를 **이번 선택에서만** 건너뛴다 (0x1EEC9C, 목록에는 남음). 스크립트 요청(SwitchCamera)도 같은 목록이라 똑같이 걸린다.
아무것도 남지 않으면 현재 카메라 유지.

| 마스크 | 영웅 +0x174 | 출처 (+0x174 를 쓰는 곳) | 엔진 |
|---|---|---|---|
| 0x1 | – | 영웅 대상 슬롯 (vtable +0x78) +8 = 적 무리 (0x1B5D00: 대상 위치 반경 320 안 무리, 없으면 새로 0x1B5208), 무리 +0x1A0 = 무리의 적 수 (+0xD8 목록, 0x1B56E8: +0xF4 bit0 이 없는 쪽). 적 AI 가 대상을 잡을 때 0x1D30D0 이 대상과 자신을 무리에 넣는다 | 살아 있는 적 중 AI 상태 COMBAT 이 있으면 [추정] |
| 0x2 | 0x1 / 0x2 | 지상 갱신 0x1EA200 | GROUND (지상)·PUSH·INTERACT |
| 0x4 | 0x400 | 줄 0x1C6008 | ROPE·ROPE_TRAVERSE·ZIPLINE |
| 0x8 | 0x10 / 0x20 | 턱 / 벽 0x1C7280 | LEDGE_HANG / WALL_CLIMB·WALL_WALK·LADDER |
| 0x10 | 0x40 | 날기 (지상에서 발밑이 없고 능력 Fly, 0x1EA38C) | – |
| 0x20 | 0x80/0x100/0x200 | 물 0x1CA448 | SWIM |
| 0x40 | 0x4 | 점프 0x1E9950 / 0x1E9A38 | 공중 + 점프로 뜸 (HeroController 점프, 내비 벽·줄·물 점프) |
| 0x80 | 0x8 | 떨어짐: 지상에서 발밑 없음 0x1EA3B0, 놓기 0x1E9030 (줄·벽·턱), SCR_DetachFromWall 0x1C9380 | 공중 + 걸어 떨어짐 / ANIFall 놓기 |

데이터 (214 WAD, 배치 카메라 2920): 0xFE 2284, 0xFF 195, 0x00 64 (AmbientSpot/FireLight 류 — 볼륨 카메라 아님), 나머지는 상태 전용 —
FallCam (0x80), LadderCam·LgHangCam·MastCam4 (0x08), camRopes·UprCam14 (0x04), SwimCam·WaterCam·CrushCam16 (0x20) 처럼 이름과 맞는다.
엔진: `CameraRig.setHeroState(+0x174 비트, 전투)`, `CameraRig.stateMask` = 0x1EE510, `GameScene.heroStateBits()`. 시험 `StartCameraTest.ladderCameraOnlyOnWall`
(ATHN02A LadderCam 볼륨 안에서 지상이면 다른 카메라, 벽이면 LadderCam).

### 8.2 화면 데드존과 각도 범위 [확실: 0x1F29A0 머리·끝, 0x1F1108/0x1F11A0]
- **상태**: 행렬 (눈 +0x30, 회전 행), +0x80/+0x84 카메라 요/피치, +0x88/+0x8C 화면 속 대상 각 (카메라 좌표 (atan2(−x, −z), 피치), 0x1F0DC8), +0x90 대상까지 거리.
  0x1F1108 이 R(+0x80, +0x84, 롤 +0x94) 로 행렬을 만들고 **눈 = T − +0x90·R·방향(+0x88, +0x8C)** — 대상이 화면 (+0x88, +0x8C) 자리에 온다.
- **머리** (매 프레임, 0x1F2A34): 상태 행렬로 본 T 의 각·거리 → 새 카메라 데드존 (CAM 0x98/0x9C 세로 위/아래, 0xA0/0xA4 가로 왼/오른, 화면 비율;
  로더 0x13E8A0 이 0x9C ≤ 0x98, 0xA0 ≤ 0xA4 로 정렬) 을 화각 (GO+0xF8 = CAM 0x70, 0x13EA98) 으로 각으로 바꿔 — 세로 [atan(0.75·tan(f/2)·0x9C), atan(0.75·tan(f/2)·0x98)],
  가로 [−atan(tan(f/2)·0xA4), −atan(tan(f/2)·0xA0)] — 밖이면 자르고 0x1F1108 로 눈을 옮긴다. **기준점 = 눈 + 정면·(거리·cos·cos)** (0x1F2C50).
  레일 첫 표본(0x1F20F0)·지역 탐색(0x1F1AA0)·모드 2·3 의 요/피치·거리(0x1F2FD4, 0x1F3290)가 T 가 아니라 이 기준점을 쓴다.
  데드존 0 (2188/2920) 이면 기준점 = T. 이전 엔진의 "전환 기준점 = T 를 직전 시선 축에 투영" 은 이 자르기(눈 이동)를 빠뜨린 것 — 전환도 데드존 0 이면 T 다.
- 데이터: 0 아닌 데드존 732 개 중 대부분이 **한 점** (예 [−0.1, −0.1] 103, [−0.2, −0.2] 50, [−0.25, −0.25] 43 — 영웅을 가운데보다 아래에), 구간형 ([0.1, −0.1] 41, [0.05, −0.05]×[−0.05, 0.05] 10).
- **끝 — 각도 범위 없음** (0x1F36D0): +0x90 = 깊이 / (cos +0x88 · cos +0x8C), 0x1F1108. 깊이 = 엔진의 거리 (모드 2·3 은 |기준점 − 피벗|·c, 모드 1 은 이전 상태 깊이 +0x90·cos·cos — 리셋 템플릿 200).
- **끝 — 각도 범위 있음** (요 범위 &lt; 180 또는 피치 범위 &lt; 90, 0x1F3640 / 0x400 이면 0x1F3354): 눈 = T − 깊이·R·방향(+0x88, +0x8C) 을 먼저 정하고 **회전만** 설계 각 ± 범위로 자른 뒤
  (0x184A98, 위치는 그대로) 0x1F11A0 으로 화면 각·거리를 다시 구한다. 눈은 움직이지 않는다 — 이전 엔진은 자른 방향으로 눈을 T 둘레로 돌렸다.
  예: PAND02H Acam4 (요·피치 범위 0, c = 1) 는 눈 = 레일 점 (카메라 GO 높이), 회전은 설계 그대로.
- **리셋** (레벨 시작·Warp, 0x1EDF48 → 템플릿 0x1F2500: 단위 행렬, 원점, +0x80 −90, +0x90 200; 0x1EEE88 의 직전 상태 = 없음 (+0x750 = −1) → 0x1F3A38 은 복사 안 하고 곧바로 0x1F3768):
  해독대로면 첫 머리는 원점에서 본 T 의 각을 자르므로 데드존이 있으면 기준점이 |T|·tan(자른 각) 만큼 밀린다 (HDES01A PillarCam1 |T| ≈ 9600 → 약 740 위).
  그러면 실제 Warp 도착 (PAND05B → HDES01A) 에서 레일 t 가 엉뚱한 곳에 잡혀 영웅이 화면 밖 — **엔진은 리셋 첫 머리만 데드존 0 (기준점 = T)** [근사]. 데드존 0 카메라는 해독 그대로 T 다
  (이전 [근사] "리셋 때 T" 의 근거 — PAND03F 가 깨졌던 "(0, 0, T.z)" 는 0x1F1108 눈 이동을 빠뜨린 값).
- 엔진: `Pose.sa/sb` (+0x88/+0x8C), `Pose.eye` = T − 거리·R·방향(sa, sb), `CameraRig.rotate/unrotate` (0x1F1000 회전 순서: 피치 X → 요 Y), `Shot` 상태 행렬 (`stEye`, `mYaw/mPitch`, `stYaw/stPitch`, 템플릿 플래그),
  `head()` = 머리, 렌더 시선 = 카메라 정면 (대상은 화면 sa/sb 자리). 디버그 `-Dgow.debug.cam2` 매 프레임 기준점·화면 각. 시험 `StartCameraTest.deadZoneFramesHeroBelowCentre`.
- **위 벡터 = 상태 행렬 R(요, 피치) 의 Y 축** (0x1F1108 → 0x1F1000, 롤 +0x94 = 0) [확실: 행렬 구성] — `CameraRig.up`. 시선이 수직이 아니면 룩앳 (0, 1, 0) 과 같은 뷰이고,
  피치 ±90 카메라 (SEWR01 camStairCase · ATHN03D camTunnel0A2 — 나선 계단을 똑바로 내려다봄, 피치·요 범위 0) 에서도 화면 위 = 요 쪽 수평 방향으로 정해진다.
  예전엔 늘 (0, 1, 0) 이라 정면과 평행 → 룩앳 행렬 NaN → 화면 전체가 검었다 (43 §30). 프레이밍 줌 (0x1EF078) 의 지금 회전도 이 위 벡터로.

### 8.3 볼륨 인트로 IdleCam [확실: 스크립트 데이터]
ATHN01A `goidlecamgroup` (CAM_IdleCam, 우선순위 50, 0x434FE, ANM_idlecamgroup) 와 `goenemyidlecamgroup` (CAM_Idle2Cam, 51) 의 CMV 는 시작 지점 구 (반지름 119) 를 덮는다.
ESC: `EnemyIdleCamGroup-CreationSensor1` → VisOffInit (Attr11_1 = 2) 로 Idle2Cam 끔, `PlayerStartZone` 진입 → IdleCamGroup-VisOff (9.5 초), EnemyIdleCamGroup-VisOn (9.5 초) /
VisOff (13 초). 즉 IdleCam 팬 9.5 초 → Idle2Cam 3.5 초 → Boat1Cam4. 관절 애니 카메라 (§6.2) 가 이미 되므로 볼륨 후보 제외를 없앴다. Start CameraName 도 "IdleCam".
ATHN01A 볼륨 관절 카메라는 이 둘뿐이다 (다른 레벨 관절 카메라는 스크립트 요청 전용 또는 생성 시 꺼짐: MedusaSpawnCam, DMCCam2, camTitanShot …).
엔진 실행 (`ATHN01A`, 2200 프레임): IdleCam → Idle2Cam → Boat1Cam4 + 해골 도발. `CameraRigTest.boatRunFramesKratos` 가 생성 센서·9.5 초 끔을 흉내 낸다.

### 8.4 near 평면 · 가시 칸 (43 문서 §11)
- 원작 뷰포트 near = 10 (`+0x384 = −10`, 생성자 0x168DC8; far −10000 → SCR_FarClip), 투영 0x1692D8 / 클립 행렬 0x169768 이 이 값을 쓴다. 다른 쓰는 곳은 거울 뷰 복사 (0x12A81C) 와
  한 패스 동안의 −0.005 깊이 바이어스 (0x15312C) 뿐 — 카메라가 바꾸지 않는다 [확실]. 엔진 `cam.near` 2 → **10** (`FxEnvironment.viewNear`).
- 카메라 눈 위치가 SheetHdr 가시 칸을 골라 컨텍스트 (`CXT_bv…`) 단위로 레벨 오브젝트를 켜고 끈다 (43 §11). PAND01BB ← PAND00D 의 ConHallCam5 는 원작도 같은 카메라이고,
  덮던 탑 외피 (`gosandarena`, CXT_bvoutside) 는 그 눈 위치 칸에서 꺼진다.

## 대상 프레이밍 줌 (전투 이벤트 11 CAMERA_FRAME) [확실: 0x2069F8, 0x2070E8, 0x1EF948, 0x1EF078]
- 이벤트 11 (예전 라벨 GLOBAL_7C24) = 창 안 매 프레임 (once 비트를 지움) b → 0x337C24 → 프레임 끝에 같은 프레임 이벤트 23 CAMERA 가 없으면 관리자 +0x7A4 (목표 가중치). 영웅·적 구분 없음. a·idx 안 씀. 해골 잡기 MAISKB00 0.75, 던지기 0, 마무리 0.7.
- 카메라 매 프레임: cur += (tgt − cur)·0.1, tgt = 0 (끊기면 같은 속도로 줄어듦). cur &gt; 0.01 이고 지금 CAM 플래그 0x2000 이면 0x1EF078: d = |눈 − T| (T = 카메라 대상 = 영웅 + 24), 가로 FOV += (2·atan(R/d) − FOV)·cur (R = 관리자 +0x7A8 1.5 × 16 = 24), 시선을 T 쪽으로 slerp(cur), 눈은 그대로.
- 엔진: `Combatant.onCameraFrame` → `CameraRig.frameTarget` / `frame` (실제 PS2 플레이 영상의 잡기 클로즈업과 대조).

## 9. 카메라 시스템 심층 조사 2차 (QA vs PS2: 컷 뒤 조작, 흔들림, 처형 클로즈업, 보간)

관리자 한 프레임 (0x1EFDA8 → **0x1EF620**) [확실: 코드]:
1. 0x1F4380 카메라 대상 슬롯 갱신 (끈 반지름, §9.5) — 그 안에서 0x1F46D0 전투 카메라 덮어쓰기 (§9.3).
2. +0x7B0 (Warp 직후) 이면 선택 0x1EF310 먼저.
3. 오른쪽 스틱 시선 (§9.6 — 꺼짐), 상태 링 0x1EF3F0 (§9.4).
4. 줌 가중치 +0x7A0 += (+0x7A4 − +0x7A0)·0.1, 0.01 보다 크고 지금 카메라 0x2000 이면 0x1EF078 프레이밍 (s2 = 1). +0x7A4 = 0.
5. 카메라 GO 행렬 기록, DOF 템플릿 (+0x910 또는 CAM +0x50, 0x162008), +0xE4 초점 거리.
6. **s2 (프레이밍) 이 아니면 선택 0x1EF310** — 다음 프레임 카메라. 잡기·처형 줌 동안은 카메라가 바뀌지 않는다 (엔진 `CameraRig.framedLast`).
7. 흔들림 0x1F0A30 은 0x1EFDA8 이 이어서 부른다 (회전 = 카메라 로컬 X, 이동 = 로컬 Y).

### 9.1 컷 뒤 입력 기준 유지 (0x1EF310 → 0x1DCE80, 영웅 입력 0x1DD1E8) [확실: 코드]
- 선택이 바뀌고 전환 시간 ≤ 0.1 (0x29E15C) 이면 0x1DCE80: 영웅 입력 객체 +0x100 = 1.0/(1/60) = **60**. 전역 정지 (0x29DB58, 0x1BF108) 중이거나
  0x29F178 ≠ 0 이면 안 건다. 0x29F178 = 전역 정지 해제 때 0x1BF080 → 0x1EF2F0(2) (+0x100 = 0 도), 선택마다 1 감소.
- 0x1DD1E8 (매 프레임): +0x100 = 0 이거나 스틱 크기 (+0xC) = 0 → 기준 행렬 +0x60 = 지금 카메라, +0x104 = 지금 스틱 각, +0x100 = 0.
  아니면 Δ = |wrap(스틱 각 − +0x104)| (0x1DCCC8, 도) 가 7° (0x29E154) 보다 크면 7° 마다 기준 사원수를 지금 카메라 쪽으로 slerp(1/+0x100) 하고 +0x100 −1.
  **프레임으로 줄지 않는다** — 컷 순간의 스틱 방향을 유지하는 한 컷 전 카메라 기준으로 계속 달린다. 놓으면 바로 새 카메라 기준.
- 엔진 이전: 렌더 카메라 요를 그대로 써 컷 순간 크레토스가 화면 기준으로 돌아섰다 (이전 볼륨으로 되돌아가 카메라가 왔다 갔다 할 수 있음).
- 엔진: `input/InputFrame` (yaw / cut / selected / release), `CameraRig.cutThisTick()`, `GameScene` 이 영웅·봇 입력에 기준 요를 넘긴다.
  `-Dgow.debug.inyaw` 로 0.5 초마다 스틱·카메라·기준·남은 단계. 시험 `InputFrameTest`.
- [근사] 원작은 기준 전체 행렬 (요·피치) 을 slerp — 엔진 입력은 요만 쓰므로 요만. 스틱 데드존은 엔진 0.15.

### 9.2 흔들림 (0x1F09C8 시작 / 0x1F0A30 매 프레임 / 0x1F0A28 배율) [확실: 코드 / 추정: 값의 뜻]
- 시작 호출자 셋: 전투 이벤트 5 (0x206030, 이미 구현 — 배율 1), MFX 템플릿 +0x22 (#da729778, 0x2217E8 ← MFX 적중 0x221810 / 베기 0x221918) [미구현: 템플릿 값 미조사],
  **소리 흔들림 0x228C70** (매 프레임 작업, vtable 0x2B98A0).
- 소리 흔들림: IOP 사운드 드라이버 (SMPD_IOP.IRX) 가 0x280 바이트 상태 버퍼 (0x29CAF0, 0x1389D0 이 IOP 에 주소 전달) 에 채널 둘을 쓴다 —
  +0x24C {id, 값, 템플릿, 재생 중} = 패드 진동 (0x228E98, 세기 = 값·100/1024 %), +0x25C = 카메라 흔들림. id 가 바뀌면 0x1F09C8(템플릿) (진행 중이면 무시) 그리고
  **배율 +0x958 = 값/1024** (0x1F0A28 — 무시된 시작이어도 지금 흔들림의 배율은 바뀜). 재생 중이고 흔들리는 중이면 매 프레임 배율 갱신.
- 쓰는 소리: R_PERMK GENERAL2 `SND_SHAKE_SMALL/MEDIUM/BIG/HUGE/SPECIAL` = DPMS cmd 0x16 (모터 1, 인자 1/2/3/4/500 = CameraShake 템플릿 001–004/500),
  `SND_RUMBLE_*` = 모터 0 (패드). 레벨 소리 그룹 SEM_SNDShake (PAND03B gosndgrpshake = SND_SHAKE_BIG, PAND00C gosndgrpshakeuw = HUGE, ATHN03A 건물 붕괴·문 폭발 오브젝트 그룹 …).
- 0x1F0A30: d = 배율·(D − t)/D (엔진에 배율 없던 것 추가). 값 = 그 보이스의 들림 크기로 보고 (핸들러·소리 볼륨 × 거리 감쇠 [near, far] 선형) [추정: IOP 쪽 미해석].
- 엔진: `SoundManager.shakeRequest()` (serial, 템플릿, 크기 — SEM 범위가 재생 뒤에 정해지므로 물을 때 계산), `CameraShake.scale`, `GameScene` 틱. 시험 `SoundManagerTest.shakeSoundRequestsCameraShake`.
  자동 플레이 (ATHN03A·02C·04A·PAND03B 150 초) 에서는 해당 스크립트 구간까지 못 가 실행 증거 없음.

### 9.3 처형 클로즈업 — 전투 이벤트 23 CAMERA (goCamera) [확실: 코드·데이터 / 추정: 표시]
- 이벤트 23 은 창 안 매 프레임 (once 비트를 지움) 0x2054A0 → 전투원 +0x40 (카메라 GO) 이 비었을 때만:
  1. R_PERMK **goCamera** (스켈레톤 "camera": main, AimJoint, CamPos, Width, locator1; ANM_camera) 생성, 덮어쓰기 표 0x304AB0 에 세 관절 번호 (0x1F4560).
  2. 변형 i = 0, 1 …: act 키 = Hash127("%d", 시드 = 이벤트 해시). 이벤트 해시 = Hash127Upper(접두사) 이고 act 는 Hash127Upper(이름) 로 찾으므로 **act 이름 = 접두사 + i** —
     미노타우로스 M400 #d485fc51 = "MAIMIN01CAM" → `maiMin01Cam0/1`, 사이클롭스 M240 #ad15316a → `MAICYWMini02Cam0`, 고르곤 `maiMewGrabCam0/1`, 님프 `heroKissCam0`,
     해골 D `MAISKDslamCam0/1`, 영웅 세이렌 영혼 `MaiSirSoulCam0` (R_PERMK ANM_camera 의 외부 그룹 MaiMin/MaiCy2/MaiMew/MaiNya/MaiSkd/MaiSir … 을 적 WAD 가 채움).
     (37 문서 §10 의 "BMI 이벤트 #9E9C68F3 act 못 찾음" = MaiBmi 그룹을 채우는 WAD 가 없어서.)
  3. 각 변형의 0 초 CamPos 를 전투원 synchJoint 관절 (루트 모션 +0xEA) 기준으로 두고 (전투원 → CamPos) 수평 방향 · (전투원 → 지금 카메라 눈) 수평 방향이 가장 큰 것을 고름 (같으면 앞).
  4. goCamera 를 그 관절에 붙임 (0x131460). 변형이 없으면 지우고 표 비움.
- 매 프레임 (0x205968 → 0x205280, 카메라 GO 가 있으면): act 시간 = 전투원 기술 애니 시간 (+0x5C +0x8), **전투원 몸을 Y 축으로 (눈 방향 − CamPos 방향) 수평 각 × 가중치만큼 회전**
  (0x1846F0 은 회전 행만 — 제자리). 저작된 CamPos 가 실제 카메라 쪽으로 돌아와 화면이 저작 구도에 가까워진다. 이벤트 19/22 끌기 중이면 상대도 따라 돈다.
- 창이 없는 프레임: 0x206030 끝 (0x2070B8) 이 GO 를 지우고 표를 비움. 기술 끝 0x209918 도.
- 카메라 쪽 (0x1F46D0, 관리자 프레임 처음): 표가 살아 있으면 가중치 +0x14 += 0.01/프레임 (최대 1), 관리자 +0x7AC = 1, +0x7A8 = |Width − AimJoint|/16 (줌 반경),
  +0x790 = CamPos 관절 행렬 둘째 행 (Y 축 = 위 벡터), +0x7A4 = 가중치 (0x1EF040). 꺼지면 0x1EF058(0): +0x7A4 = +0x7A0 = 0 (줌이 바로 풀림), 반경 1.5, 위 (0, 1, 0), +0x7AC = 0.
  - 대상 T (0x1F26E8 앞머리): 영웅 슬롯 + (AimJoint − 영웅 슬롯)·가중치 — 다중 대상보다 먼저.
  - 0x1F3768: +0x7AC 이고 CAM 0x2000 이면 T 는 갱신하고 **위치·회전 풀기 생략** — 눈이 멈춘 채.
  - 0x1EF078: 가로 FOV → 2·atan(R/|눈 − T|) 쪽으로, 회전은 룩앳 (T, 위 = +0x790) 사원수 slerp — CamPos 관절 롤이 화면에 들어온다. 이벤트 11 은 같은 프레임에 23 이 있으면 무시 (0x2070EC).
- [추정] 다음 기술 (다른 이벤트 해시) 로 창이 이어질 때: 원작 경로상 GO 를 지우는 곳이 없어 처음 act 가 남는 것처럼 읽히지만 (기술 시작 0x2098DC 도 안 지움),
  그대로 하면 미노타우로스 MAIMIN03 (6.67 초) 동안 maiMin01Cam0 (3.8 초) 을 0 초부터 다시 틀어 빈 바닥을 본다. 기술마다 길이가 맞는 act 가 저작돼 있어
  엔진은 해시가 바뀌면 그 기술의 act 로 다시 고른다 (`CombatCamera.update`).
- [근사] 붙는 행렬 = 엔진 synchJoint (zeroJoint 기준, `SyncPull.synchRel`) — 원작 0x2054A0 은 부모 관절 회전 + 관절 위치로 만든다.
- 엔진: `combat/CombatCamera` (변형·표본·몸 회전, R_PERMK 는 첫 이벤트 23 때 적재, 적 WAD 는 스폰 때 `addWad`), `CameraRig.combatCamera / combatWeight`, `Camera.up` (롤).
  `-Dgow.debug.cam` 로 act·가중치·AimJoint·반경, `-Dgow.nocombatcam` 비교용 끔. 시험 `CombatCameraTest` (해시·변형, 미노타우로스 처형: maiMin01Cam* → 402 의 maiMin03Cam0,
  몸 회전으로 CamPos 방향이 눈 쪽 5° 안, 창 끝 해제), `CameraRigTest.combatCameraOverride` (가중치 100 프레임, 눈 멈춤, 시선 = AimJoint, 줌, 롤, 선택 생략, 해제 즉시 원래 화각).
- 검증 (커밋 안 함, 스크래치 `cm/a3_cmp.png` 위 = 끔 / 아래 = 켬, `cm/a3_rs.png`, `cm/cmp_2b.png` 4 번째 = ATHN02B 사이클롭스 처형 클로즈업): ATHN03A 자동 플레이 91–100 초 갑옷 미노타우로스 처형 —
  예전엔 레벨 카메라 그대로 멀리서, 지금은 크레토스가 미노타우로스 위에서 찌르는 장면을 가까이 (원작 처형 연출과 같은 구도).

### 9.4 상태 보간 (0x1EF3F0 → 0x1F3C90 → 0x1F3BC0) [확실: 코드]
- 링 4 칸 (+0x190 + 0x170·i). 경과 += 1/60, ≥ 전환 시간이면 그 상태가 가장 오래된 것 (앞은 0x1F25F0 로 버림), 아니면 α = 경과/전환시간.
- 오래된 것부터: 첫 상태가 누적, 다음마다 0x1F3C90(α): 상태 +0x70..+0xEC (32 값: T, 요, 피치, 화면 각, 거리, 롤, FOV +0xD0, DOF +0xE4 …) 마다
  3차 Hermite(p0, v0, p1, v1, α), 속도 = (값 − 직전 값 +0xF0..)·60. 요 (+0x80) 만 ±180 감김. 결과 관리자 +0x10 의 직전 값 = 결과 − dp/ds/60.
- 상태 갱신 0x1F3768 끝이 직전 값 = 지금 값을 복사하므로 **카메라 상태의 속도는 0**, 0 이 아닌 것은 앞 보간 결과뿐 → 상태 둘이면 smoothstep (이전 엔진과 같음),
  전환 중 또 전환이면 앞 보간의 도함수가 v0 (엔진 `blend(a, va, b, s, out, vout)` 연쇄).
- 엔진 렌더 보간 (틱 사이, 원작 없음) 도 smoothstep 이던 것을 선형으로 — 렌더 주기 &gt; 60Hz 에서 틱마다 가감속이 생겨 카메라가 떨렸다.
- 시험 `CameraRigTest.hermiteChainCarriesDerivative`.

### 9.5 대상 슬롯 끈 · 점프 높이 [확실: 코드 / 미해석 한 비트]
- 0x1DCC20: 영웅 슬롯 오프셋 (0, 1.5·16, 0), +0x40 반지름 **1.6** (×16 아님), +0x44 16, +0x48 가중 1. 0x1F4380 이 매 프레임 원하는 자리에서 반지름 밖으로 벗어난 만큼만 끈다.
  선택 질의점 0x1EEFA0 = 영웅 관절 위치 (컴포넌트 0x330514 의 +0xE4 → +0x34 관절) — 엔진은 T [근사].
- 0x1F2850 (CAM 0x800 / 0x4000, 상태별): 영웅 +0x174 &amp; 0x4C (점프·떨어짐·날기) 이고 날기 아님 → 첫 프레임 이륙 높이 = 직전 T 높이, 오프셋 = **max(0, T.y − 이륙)** —
  이전 엔진은 떨어져 이륙보다 낮아져도 T 를 이륙 높이에 묶었고, 낮은 곳에 내린 뒤에도 위에서 천천히 내려왔다. 착지 프레임 오프셋 같은 식, 이후 ×0.9.
  [미해석] 영웅 +0x2D8 bit 0x8: 0x800 카메라는 서면 ×0.95 로 풀고, 0x4000 만인 카메라는 그 비트가 있어야 고정 — 엔진은 둘 다 0x800 처럼. 데이터: 0x800 21 / 0x4000 만 4 (ATHN·PAND01A 표본).
- 시험 `CameraRigTest.jumpHoldFollowsDownNotUp` (ATHN02A ScafCam1).

### 9.6 조사했지만 바꿀 것 없음 / 남은 것
- **오른쪽 스틱 카메라**: 0x1EF6C4 (±30°·스틱, 0.1 slerp → +0x760) 와 0x1F3170 (모드 0/4 카메라 회전·거리) 은 관리자 +0x4 == 1 / +0x6 == 3 이면 건너뛰는데
  생성자 0x1EDC68 만 이 값을 쓴다 (1, 3) → **소매판에서 꺼짐** [확실]. 원작 오른쪽 스틱은 구르기만.
- **충돌·가림**: 카메라 갱신 경로에 광선 질의 없음 (§7.2) — 벽 안 카메라 보정·지오메트리 페이드 없음. 가림 비용은 AresCamera1 전용 (§6.3) [확실].
- **전투 마스크 bit0**: 0x01 만 가진 CAM 0 개 (표본 레벨) — 전투 판정 근사가 선택에 영향 없음.
- 원작 Follow 카메라 0x337B68 (선택 결과 없음 + 관리자 +0x4 == 0 일 때만 — 소매판 +0x4 = 1 이므로 후보가 없으면 현재 유지 경로만) — 엔진 Follow 근사는 볼륨이 전혀 없는 처음에만.
- DOF (+0xE4 = |눈 − T|·(1 − k)/16, k = CAM +0x3C (0x100000) 또는 0.75, 0x80000 없으면 ≤ 100; 템플릿 0x162008) 미구현. 레터박스·화면비는 §2 (가로 화각 유지) 그대로.
