# 43. 검증: 전 레벨 시각 QA (효과 켬)

`--no-fx` 로 찍던 이전 확인은 날씨가 화면을 덮는 문제(4 레벨)를 놓쳤다. 이번에는 **효과를 모두 켠 채** 플레이 가능한 레벨 105 개
(`gow-rom/*.WAD` 중 `R_*` 제외)를 하나씩 띄워 스크린샷을 보고 분류했다.

```bash
./gradlew -q :engine:installDist < /dev/null
engine/build/install/engine/bin/engine --game LEVEL --skip-movies --screenshot shot/LEVEL.png --frames 240 < /dev/null > shot/LEVEL.log
```
- 레벨마다 2–4 초 (240 프레임). 105 개 전부 예외 0, 이미지 105/105.
- 판정 보조: `--no-fx` (파티클·물만 끔), `GOW_FX=off` (안개 포함 환경 효과 전부 끔), `-Dgow.debug=1` (영웅·카메라 위치),
  **`GOW_HIDE=인스턴스,…`** (신설: 그 레벨 인스턴스를 그리지 않음, `*` = 하늘 밖 전부, `sky` = 하늘 — 화면을 덮는 메시를 이분 탐색으로 찾는 데 썼다),
  `GOW_FOGBG=off` (배경 안개 끔), `GOW_PASSORDER=model` (이전 패스 순서) — 수정 전후 비교용.

## 1. 결과 요약 (수정 후)
| 분류 | 수 | 레벨 |
|---|---|---|
| 정상 | 84 | 아래 표 (이 중 14 개는 이번 수정으로 개선) |
| 시작 카메라가 영웅을 못 봄 (효과 무관) | 13 | ATHN01C, ATHN01F, ATHN02C, ATHN02D, ATHN04C, ATHN04D, ATHN06C, ATHN07B, ATRET01B, ATRET01C, PAND01BB, PAND04F, PAND04H |
| 단독 진입 대상 아님 (부속 WAD) | 4 | ATHN01EE · PAND01CC · PAND03GG (렌더 인스턴스 0), PAND00E (2) — 검은 화면에 영웅만 |
| 의심 (미해결, §4) | 4 → 2 | ATHN05AA, ATHN01A (OLYMP02·PAND03A 는 §6 에서 해결) |

## 2. 찾은 문제와 수정 [확실: 데이터/원작 규칙]
### 2.1 하늘 = SCR_Sky 대상 (31 §2, 16 §1.4)
- 원인: 하늘을 "이름/텍스처에 sky + 원점 중심 큰 메시" 휴리스틱으로 골라, SCR_Sky 가 있는 **16 레벨**에서 하늘을 못 찾았다
  (ATHN03A/03AA/03C `group2`, ATHN04A `group3218sky`, ATHN04B, ATHN05A/05C `skyLR`, ATHN06A `nightSky`, OLYMP01, PAND00A, PAND01B,
  PAND01BB `group1224`, PAND01C, PAND04E `ShitBoxl`+`group3508`, PAND04F, ATHN02B 의 두 번째 `SkyBattleFX`, PAND04AA 의 FX 하늘).
  하늘이 제자리 지오메트리로 그려져 시야 밖이면 화면 구석이 지움 색(검정)으로 남았다.
- 수정: `LevelScene.skyNames/isScrSky` — SCR_Sky 템플릿 소유자(MDL_x) 또는 본문 오브젝트 이름과 같은 배치 = 하늘 (원작 0x218848 은 SCR_Sky 가
  붙은 GO 를 카메라로 옮긴다). SCR_Sky 가 없는 레벨만 이전 휴리스틱. 다른 슬롯 레벨(이웃) 렌더도 같은 규칙.
- 효과: ATHN04A·ATHN05A·ATHN05D·ATHN06A·PAND04E·PAND04AA 의 검은 하늘 구석 → 밤하늘/수평선.

### 2.2 지오메트리 없는 배경에도 안개색 (31 §3, 16 §1.2)
- 원인: 안개를 지오메트리 조각에만 입혀, 하늘이 없는 레벨에서 먼 배경이 지움 색 그대로였다 (DEST01 모래 폭풍 위 검은 띠).
- 원작: 안개색 스프라이트 0x127000 은 Z 가 지워진 화소에도 그린다. Fog_A ≥ 1 ∧ 블렌드 0 단일 안개(0x1275E0) 는 배경 G = 0 → **안개색 전부**,
  그 밖은 BackPlane 패스 0x127FF0 의 BackPlane_A/128 (BackPlane < 0 이면 ≈ 1/128 → 생략).
- 수정: `FxEnvironment.backgroundFactor` + `LevelFx.drawBackground` — 하늘·지오메트리 전에 화면 전체 사각형(지움 색)을 배경 모드 안개로 칠한다.
- 효과: DEST01 하늘 띠가 모래색, OLYMP02 유리 바닥 아래가 안개색(§4).

### 2.3 반투명 패스를 모든 불투명 뒤에 (31 §4)
- 원인: `Renderer.draw` 가 **모델마다** 패스 0(불투명·알파 테스트) → 패스 1(반투명) 을 연달아 그려, 뒤에 그려지는 인스턴스의 불투명 면이
  앞서 그린 반투명 면(깊이 안 씀)을 덮었다. ATHN01D 갑판: 판자 `MAT_2woodPlanks06aTrans` (p28 0.967 → 정렬 플래그 0x8000 = 반투명) 가
  0.406 아래 구름 반사 평면 `gostartdeckhull1` (`MAT_1sfxCloudReflection`, 불투명) 에 덮여 **갑판 전체가 베이지 단색**.
- 원작: 정렬(반투명) 플래그 머티리얼은 불투명 뒤 따로 그린다 (16 §3.3, 0x1537C8).
- 수정: `Renderer.drawPass(model, world, pass)`, `LevelScene` 이 모든 인스턴스의 패스 0 뒤 패스 1 (이웃 레벨 렌더도). 반투명끼리 정렬은 하지 않음 [추정: 원작 정렬 단위 미확인].
- 효과: ATHN01D 젖은 판자 + 구름 반사, 그 밖에 가려졌던 빛줄기·광원 후광·풀 (ATHN03A, ATHN04B, ATHN07 횃불, PAND02G 폭포, PAND04C 풀, PAND05B 빛줄기).

## 3. 레벨별 상태 (수정 후, 240 프레임)
상태: ○ 정상, ◎ 이번 수정으로 개선, C 시작 카메라 (효과 무관), N 부속 WAD, △ 의심 (§4).

| 레벨 | 상태 | 메모 | 레벨 | 상태 | 메모 |
|---|---|---|---|---|---|
| ARENA06 | ○ | 항아리 아레나, 소용돌이 하늘 | PAND00A | ○ | 사막 안뜰 (하늘 SCR_Sky) |
| ARENA08 | ○ | | PAND00B | ○ | 모래 안개 다리 |
| ARENA09 | ○ | | PAND00C | ○ | |
| ARENA10 | ○ | | PAND00D | ○ | |
| ARENA12 | ○ | 붉은 하늘 | PAND00E | N | 인스턴스 2 (부속) |
| ARENA14 | ○ | | PAND01A | ○ | 횃불·물 |
| ARENA19 | ○ | 피 효과 | PAND01B | ○ | |
| ARENA20 | ○ | | PAND01BB | C | LowCam1 이 벽을 봄 (먼지 날씨 정상) |
| ARENA26 | ○ | | PAND01C | ○ | |
| ARENA27 | ○ | | PAND01CC | N | 인스턴스 0 |
| ATHN01A | △ | 비 정상, 오른쪽 물보라 과밝음 (§4) | PAND01D | ○ | |
| ATHN01B | ○ | 갑판 구멍 빗물·빛줄기 | PAND02A | ○ | |
| ATHN01C | C | Boat1Cam9, 선실 안 어둠 | PAND02AA | ○ | |
| ATHN01D | ◎ | 베이지 갑판 → 젖은 판자 (§2.3) | PAND02B | ○ | |
| ATHN01E | ○ | 비 | PAND02C | ○ | |
| ATHN01EE | N | 인스턴스 0 | PAND02D | ○ | 물 |
| ATHN01F | C | 카메라가 벽 쪽 | PAND02DD | ○ | |
| ATHN02A | ○ | 플랑크톤 | PAND02F | ○ | |
| ATHN02B | ○ | 플랑크톤, 하늘 2 | PAND02G | ◎ | 폭포 반투명 보임 |
| ATHN02C | C | Room2Cam9, 어둠 | PAND02H | ○ | |
| ATHN02D | C | 숙주 ATHN02C 카메라 | PAND02I | ○ | |
| ATHN03A | ◎ | 하늘 group2, 빛 | PAND03A | △ | DMC 결계 바인드 자세 (§4) |
| ATHN03AA | ○ | | PAND03B | ○ | |
| ATHN03C | ○ | | PAND03C | ○ | |
| ATHN03D | ○ | | PAND03D | ○ | |
| ATHN03E | ○ | | PAND03E | ○ | |
| ATHN04A | ◎ | 검은 하늘 → 밤하늘 | PAND03F | ○ | |
| ATHN04B | ◎ | 광원 후광 | PAND03G | ○ | 증기·불 정상 |
| ATHN04C | C | camera1 이 벽 | PAND03GG | N | 인스턴스 0 |
| ATHN04D | C | LedgeCam2 | PAND03H | ○ | |
| ATHN05A | ◎ | 아치 너머 하늘 | PAND03I | ○ | |
| ATHN05AA | △ | 회색 화면 (§4) | PAND03J | ○ | |
| ATHN05B | ○ | | PAND04AA | ◎ | 하늘 2 |
| ATHN05BB | ○ | | PAND04C | ◎ | 풀 보임 |
| ATHN05C | ○ | 전투 중 쓰러짐 | PAND04D | ○ | |
| ATHN05D | ◎ | 문 너머 하늘 | PAND04E | ◎ | 왼쪽 검정 → 하늘 |
| ATHN06A | ◎ | 검은 하늘 → 수평선 | PAND04EE | ○ | |
| ATHN06B | ○ | | PAND04F | C | camera2 가 바위 |
| ATHN06C | C | LwrCam18 | PAND04G | ○ | |
| ATHN07 | ◎ | 횃불 후광 | PAND04H | C | WarpCam, 어둠 |
| ATHN07B | C | 이웃 OLYMP01 camMagical6 | PAND04IB | ○ | |
| ATHN08A | ○ | 아레스 | PAND04J | ○ | |
| ATHN08B | ○ | | PAND05A | ○ | 어두운 밤 절벽 |
| ATHN08C | ○ | | PAND05B | ◎ | 빛줄기 |
| ATRET01A | ○ | | SEWR01 | ○ | 층 안개 |
| ATRET01B | C | FlrCam11 | SEWR02 | ○ | 아래 검은 띠 = 구덩이 (BackPlane −1) |
| ATRET01C | C | ATHN06C 와 같은 시작 | DEST00 | ○ | |
| HDES01A | ○ | 붉은 층 안개 | DEST01 | ◎ | 하늘 띠 검정 → 모래색 (§2.2) |
| HDES01B | ○ | | DEST02 | ○ | |
| HDES01C | ○ | | DEST03 | ○ | |
| HDES01D | ○ | | OLYMP01 | ○ | |
| HDES01E | ○ | | OLYMP02 | △ | 유리 바닥 (§4) |
| | | | OLYMP04 | ○ | |

시작 카메라(C): `--game LEVEL` 의 기본 Start 는 대부분 개발용 지점이라 그 자리 CMV 카메라가 영웅을 보지 않는다 (영웅은 땅 위, `-Dgow.debug`).
정상 경로(이웃 LoadCheck·Warp 도착)에서는 36/38 문서 순회로 확인된 카메라가 쓰인다 — 시각 효과 문제가 아니다.

## 4. 남은 의심 (미해결, 근거)
- **ATHN05AA** 회색 화면: `GOW_HIDE` 이분 탐색 → 숙주 ATHN05A 의 `gosmokeplane2main` (`MAT_smokeFog_lambert364`, 0x44, 텍스처 α 전부 0x80, 정점 α 128 = 불투명)
  이 y 665–697 의 길거리 연기층이고, 시작 지점 (725, 624, 3017) 이 그 아래·카메라 (694) 가 층 안이다. 데이터대로면 불투명 — 원작은 지붕 위에서 이 층을
  내려다본다. 단독 시작 지점 문제로 보이며 실제 진입 경로 검증 필요.
- ~~**OLYMP02** 바닥~~ → §6.2 (SCR_Mirror 반사 패스). 반사에 캐릭터는 아직 없다.
- ~~**PAND03A** 붉은 쐐기~~ → §6.1 해결 (시작 act "DMC" 의 가시성 트랙이 결계를 숨김).
- **ATHN01A** 오른쪽 흰 물보라: `--no-fx`/`GOW_FX=off` 면 사라지는 파티클 (폭포 `gowaterfall03` 옆, `govolumefoge` 볼륨 이미터 배율 10.77 또는 `gosplash2` 표면 이미터).
  FXC 5–9 볼륨 상자 배율·표면 이미터(FXC 3) 해석이 [추정] 이라 과밝음 여부 판단 보류. → §6.3 (데이터 대조, 수정 안 함).

## 5. 테스트
`SkyBackgroundTest` (3): SCR_Sky 하늘 배치 (ATHN05A skyLR, ATHN02B 2개, PAND04E 2개, PAND01BB group1224, ATHN01A, ATHN03A group2),
배경 안개 계수 (DEST01 1, ATHN07 100/128, ATHN01A 층 95/128, PAND01A −1), ATHN01D 판자 ALPHA · 구름 CUTOUT · 구름 인스턴스 y −0.4.

## 6. 2 차 (시작 act 자세 · SCR_Mirror · 물보라 대조)
비교 스크린샷 (스크래치 `fx4/`, 커밋 안 함): `sp_off/` (`GOW_STARTPOSE=off GOW_MIRROR=off` 상당, 이전 동작), `sp_on2/` (최종), 105 레벨 예외 0.

### 6.1 배치 오브젝트 = 시작 act 자세 [확실: 코드 + 데이터]
- 원작 GO 리셋 `0x1309C0`: 애니 컴포넌트가 있으면 act = 인스턴스 `0x54` u16 (act 수 이상이면 0, `sltu/movz`) 를 `0x1007C8` 로 걸고,
  시작 시간 `0x50` f32 ≠ 0 이면 `0x1005D0` 으로 그 시간에 둔다. 즉 ANM 이 있는 레벨 오브젝트는 **CXT 바인드 자세가 아니라 시작 act 자세**다
  (`0x56` u16 = 같은 템플릿 안 번호 — breakbucket016 → 1 — 이라 act 로 읽으면 안 된다).
- PAND03A `godmcdog2`: act 0 "DMC" (2 프레임, 관절 트랙 없음) 의 u8 가시성 트랙 = 관절 1 `dmcSquare`·3 `dogHead` 0 → 자손 19/22 관절 숨김 = **결계 안 보임**.
  스크립트 `DMCdog2-DMC-AniIn` (열쇠 문 이벤트 151) 이 "dogIn" (값 1) 으로 보이게, 적 처치 (DestructionSensor 185/196) 의 "dogOut" 끝 (값 0) 이 다시 숨긴다.
  같은 DMC 결계 13 곳 (ATHN06A/ATRET01B/DEST01/DEST03/HDES01C/PAND00B/PAND00D 4/ATHN01C·01E·02A·02B·04A·05A·05B dmcsqr) 도 시작 숨김.
- 전 레벨 조사 (ANM 있는 배치): 바인드와 다른 시작 자세 442 배치 — 부서지는 물체 (조각 관절 숨김), 발판 (PAND02I plat1–3 시작 높이 → 카메라 앞 쇠사슬 사라짐),
  ATHN08A/C fellergroup (act 1–3 · 시간 0–13 초), PAND03C 굴러가는 바위 (30 초 act, 시간 0.25–27.75).
- **계속 재생** [추정: act flags bit0 (반복) = 스스로 돎]: 시작 시간을 엇갈려 둔 배치는 모두 반복 act (fellergroup 0x3041, rock 0x7041, sail 0x1041, swingingBowl 0x5041),
  스크립트·비헤이비어가 재생하는 것은 모두 1 회 act (DMC 0x42/0x7042, breakbucket 0x3042, plat 0x3042, spinroom 0x3042).
  단 비헤이비어 GO (PushPull 크랭크 등) 는 그 클래스가 시간을 정하므로 빼고 (PAND00C goring1crank 는 반복 act 지만 크랭크로만 돎), 하늘 (SCR_Sky) 도 뺀다.
- 엔진: `tools/level/StartPose` (act·시간 → 관절 월드·숨김), `GoInstance.startTime/startAct`, `PlacedMesh.build(…, hidden)` (숨김 파트 빼고 굽기),
  `LevelScene` 반복 act 배치는 `AnimatedModel` 로 시작 시간 + 레벨 시간 재생 (스크립트 Animator 가 잡으면 그쪽 우선), `Platforms.Body` 시작 관절 = 시작 자세 (Marker·hull),
  `Body.hidden()` → `poseInstance(…, hidden)` (dogIn/dogOut 가시성). 비교 `GOW_STARTPOSE=off`.
- 화면 변화 (전후 화소 차 큰 순): PAND03A (결계 사라짐), ATHN08A/08C (기둥·나무 흔들림 자세), DEST03 (`gosandwedge` 모래 흐름 재생), PAND02I, ATHN01E/02A …
  PAND00C 는 비헤이비어 제외 후 이전과 같다. PAND00B 관문 DMC 결계도 시작에 숨고 키클롭스 생성 볼륨 (EntrySensor 44) 에서 나타난다.

### 6.2 SCR_Mirror 반사 [확실: 반사 식 / 근사: 합성] — 16 §1.7, 31 §8
- 0x12A4E8 = 카메라 행렬을 평면에 대해 반사 (v − 2(n·v − d)n). 거울 모델 = 반사 바닥 (OLYMP02 gomirror 대리석 알파 블렌드).
- 엔진: 스텐실 + 깊이 초기화 + VP·R 로 하늘·레벨 인스턴스 다시 그리기 (`gl_ClipDistance` 로 평면 뒤 자름) → 거울 모델은 일반 패스에서 빼고
  (원작 bit 61) 반사 뒤 두 패스로 그 위에 그린다 — 처음엔 일반 불투명 패스에 두었더니 ATHN04B 대리석 타일이 반사로 덮였다.
  평면 아닌 거울 (ATHN07 sword1, PAND05B oorMirrorGRP 등) 은 생략. 캐릭터·스키닝 오브젝트·파티클은 반사 안 함 [근사]. 비교 `GOW_MIRROR=off`.
- 거울 인스턴스가 평면으로 잡힌 레벨 25 곳 (ATHN01F, 03A/03AA/03D/03E, 04A/04B/04C, 05B/05D, 06B/06C, 07/07B, ATRET01B/01C, OLYMP01/02, PAND00B/00D/01BB/03H/03I/05A/05B).
- 화면: OLYMP02 복도 바닥에 벽·횃불·왕좌 반사 + 대리석 결 (이전: 안개색으로 하얗게), ATHN04B 반투명 대리석 타일에 벽화 반사, OLYMP01·ATHN03A·PAND05A 바닥 반사.
  ATHN06B/PAND01BB/PAND03I 는 이미 가짜 반사 지오메트리 (`fakereflections`, `goreflection`, `bloodstairreflect`) 가 있어 변화가 작다.
- 데이터 관찰: 여러 레벨에 미리 뒤집어 둔 가짜 반사 메시가 있다 — 원작 SCR_Mirror 가 정적 월드까지 다시 그리는지, 값 [0]/[4] 로 캐릭터만 그리는지는
  0x12ABA0 GS 패킷 해석 전에는 [추정] (OLYMP02 복도처럼 가짜 메시가 없는 거울도 있어 월드 반사로 둠).
- PAND05B 는 엘리베이터 시작이라 240 프레임 시점의 카메라가 실행마다 달라 화소 차가 크다 (거울과 무관).

### 6.3 ATHN01A 물보라 [데이터 대조 — 수정 안 함]
- 흰 덩어리 = `govolumefoge` 의 `FXC_emitter3` (서브타입 5 상자 볼륨, 30/초) → `PTC_particle3` (수명 1, 크기 800, VU 4, flags 0x4605 → &3 = 1 **가산** (0x48), 모드 6).
  월드 위치 (1851, −31, 1755), 월드 배율 (10.77, 1.31, 18.63).
- 블렌드 (16 §2.4 flags&3) 와 VU 4 색·알파 식은 [확실] 해석 그대로다. 불확실한 것은 볼륨 상자 생성 식뿐 — 엔진은 등방 반폭 10.77 × 0.5,
  Maya 단위 상자라면 축별 (5.4, 0.65, 9.3) 이라 밀도 차는 크지 않다. 서브타입 5 생성 함수 (vtable 0x2C3F88/0x2C3FA0/0x2C3FE8 중 하나, 0x2607A8/0x260E70/0x2622C8) 미확정.
  → 데이터로 틀렸다고 할 근거가 없어 그대로 둔다 (원작도 폭포 옆 가산 안개로 밝다고 본다 [추정]).

### 6.4 테스트
`StartPoseMirrorTest` (4): fellergroup20 act 3 · 13 초 / breakbucket016 act 0 (0x56 무시), godmcdog2 시작 숨김 (구운 메시 0 파트, dogIn 은 보임),
PAND03C 바위 반복 act · 9.25 초, OLYMP02 gomirror 수평 평면 · 반사 점 / ATHN03A 거울 3 개 값 (0, 1). 엔진 테스트 283 (이전 279).
