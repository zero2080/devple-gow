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
| 정상 | 97 | 아래 표 (14 개는 §2 수정으로 개선, 12 개는 §6 시작 카메라 수정으로 해결) |
| ~~시작 카메라가 영웅을 못 봄~~ | 0 | §6 에서 해결: 레일 비용 원작 해독 (10), 다른 슬롯 레벨 CMV (ATHN02D, ATHN05AA), ATHN01F 는 원작 진입 연출, ATHN07B 는 거울 반사 지오메트리 (§4) |
| 단독 진입 대상 아님 (부속 WAD) | 4 | ATHN01EE · PAND01CC · PAND03GG (렌더 인스턴스 0), PAND00E (2) — 검은 화면에 영웅만 |
| 의심 (미해결, §4) | 4 → 2 | ATHN07B (포털 반사 사본), ATHN01A (OLYMP02·PAND03A 는 §6, ATHN05AA 는 §7 에서 해결) |

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

## 3. 레벨별 상태 (수정 후, 240 프레임) — 최신은 §13.5
상태: ○ 정상, ◎ 이번 수정으로 개선, ◎c 시작 카메라 (§6 수정으로 해결), N 부속 WAD, △ 의심 (§4).

| 레벨 | 상태 | 메모 | 레벨 | 상태 | 메모 |
|---|---|---|---|---|---|
| ARENA06 | ○ | 항아리 아레나, 소용돌이 하늘 | PAND00A | ○ | 사막 안뜰 (하늘 SCR_Sky) |
| ARENA08 | ○ | | PAND00B | ○ | 모래 안개 다리 |
| ARENA09 | ○ | | PAND00C | ○ | |
| ARENA10 | ○ | | PAND00D | ○ | |
| ARENA12 | ○ | 붉은 하늘 | PAND00E | N | 인스턴스 2 (부속) |
| ARENA14 | ○ | | PAND01A | ○ | 횃불·물 |
| ARENA19 | ○ | 피 효과 | PAND01B | ○ | |
| ARENA20 | ○ | | PAND01BB | ◎c | LowCam1 → 석상 방 정면 (§6) |
| ARENA26 | ○ | | PAND01C | ○ | |
| ARENA27 | ○ | | PAND01CC | N | 인스턴스 0 |
| ATHN01A | △ | 비 정상, 오른쪽 물보라 과밝음 (§4) | PAND01D | ○ | |
| ATHN01B | ○ | 갑판 구멍 빗물·빛줄기 | PAND02A | ○ | |
| ATHN01C | ◎c | Boat1Cam9 → 영웅 뒤 갑판 아래 통로 (§6) | PAND02AA | ○ | |
| ATHN01D | ◎ | 베이지 갑판 → 젖은 판자 (§2.3) | PAND02B | ○ | 기본 시작 카메라가 모래 둔덕에 반쯤 가림 (§6.5) |
| ATHN01E | ○ | 비 | PAND02C | ○ | |
| ATHN01EE | N | 인스턴스 0 | PAND02D | ○ | 물 |
| ATHN01F | ○ | 240 프레임 = heroEntry 진입 연출 AniRvlCam (원작 스크립트, 9.5 초 뒤 SexCam1) | PAND02DD | ○ | |
| ATHN02A | ○ | 플랑크톤 | PAND02F | ○ | |
| ATHN02B | ○ | 플랑크톤, 하늘 2 | PAND02G | ◎ | 폭포 반투명 보임 |
| ATHN02C | ◎c | Room2Cam9 → 문 너머 영웅 (§6) | PAND02H | ○ | |
| ATHN02D | ◎c | 자기 CMV 생성 시 끔 → 숙주 Room2Cam (§6.2) | PAND02I | ○ | |
| ATHN03A | ◎ | 하늘 group2, 빛 | PAND03A | △ | DMC 결계 바인드 자세 (§4) |
| ATHN03AA | ○ | | PAND03B | ○ | |
| ATHN03C | ○ | | PAND03C | ○ | |
| ATHN03D | ○ | | PAND03D | ○ | |
| ATHN03E | ○ | | PAND03E | ○ | |
| ATHN04A | ◎ | 검은 하늘 → 밤하늘 | PAND03F | ○ | |
| ATHN04B | ◎ | 광원 후광 | PAND03G | ○ | 증기·불 정상 |
| ATHN04C | ◎c | camera1 → 영웅 뒤 복도 (§6) | PAND03GG | N | 인스턴스 0 |
| ATHN04D | ◎c | LedgeCam2 → 절벽 계단 (§6) | PAND03H | ○ | |
| ATHN05A | ◎ | 아치 너머 하늘 | PAND03I | ○ | |
| ATHN05AA | ◎c | 회색 화면 → 숙주 ATHN05A RublRmCam19 (§6.2) | PAND03J | ○ | |
| ATHN05B | ○ | | PAND04AA | ◎ | 하늘 2 |
| ATHN05BB | ○ | | PAND04C | ◎ | 풀 보임 |
| ATHN05C | ○ | 전투 중 쓰러짐 | PAND04D | ○ | |
| ATHN05D | ◎ | 문 너머 하늘 | PAND04E | ◎ | 왼쪽 검정 → 하늘 |
| ATHN06A | ◎ | 검은 하늘 → 수평선 | PAND04EE | ○ | |
| ATHN06B | ○ | | PAND04F | ◎c | camera2 → 나무 난간 너머 영웅 (§6) |
| ATHN06C | ◎c | LwrCam18 → 신전 복도 (§6) | PAND04G | ○ | |
| ATHN07 | ◎ | 횃불 후광 | PAND04H | ◎c | WarpCam → 문 앞 광장 (§6) |
| ATHN07B | △ | 카메라 정상, 금색 덩어리 = 거울 반사 사본 (§4) | PAND04IB | ○ | |
| ATHN08A | ○ | 아레스 | PAND04J | ○ | |
| ATHN08B | ○ | | PAND05A | ○ | 어두운 밤 절벽 |
| ATHN08C | ○ | | PAND05B | ◎ | 빛줄기 |
| ATRET01A | ○ | | SEWR01 | ○ | 층 안개 |
| ATRET01B | ◎c | FlrCam11 → 붉은 문 앞 (§6) | SEWR02 | ○ | 아래 검은 띠 = 구덩이 (BackPlane −1) |
| ATRET01C | ◎c | ATHN06C 와 같음 (§6) | DEST00 | ○ | |
| HDES01A | ○ | 붉은 층 안개 | DEST01 | ◎ | 하늘 띠 검정 → 모래색 (§2.2) |
| HDES01B | ○ | | DEST02 | ○ | |
| HDES01C | ○ | | DEST03 | ○ | |
| HDES01D | ○ | | OLYMP01 | ○ | |
| HDES01E | ○ | | OLYMP02 | △ | 유리 바닥 (§4) |
| | | | OLYMP04 | ○ | |

시작 카메라: 처음엔 "기본 Start 가 개발용 지점이라서" 로 보았으나, 원인은 레일 비용 함수 해독 누락과 다른 슬롯 레벨 CMV 미구현이었다 (§6).

## 4. 남은 의심 (미해결, 근거)
- ~~**ATHN05AA** 회색 화면~~ → §7.2 해결: 자기 CMV 만 질의해 볼륨 밖 Follow 카메라(눈 y 694)가 숙주 ATHN05A 의 길거리 연기층 `gosmokeplane2main`
  (y 665–697) 안에 있었다. 원작처럼 숙주 CMV 도 질의하면 RublRmCam19 (눈 y 712) 가 방을 내려다본다.
- ~~**ATHN07B** 금색 덩어리~~ → §9 해결 (반사 전용 메시 bit 57 은 거울 뷰에만): 카메라(camMagical4/6, 레일 swordSideRail)는 맞다. 화면을 덮는 것은 `gortalfakereflections` (포털 거울 `goportalreflect` 의
  SCR_Mirror 반사 사본) — `GOW_HIDE=gortalfakereflections` 면 포털 앞 크레토스가 보인다. 거울 패스(31 §7) 몫.
- ~~**OLYMP02** 바닥~~ → §6.2 (SCR_Mirror 반사 패스). 반사의 크레토스는 §9.
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
  → **§9 에서 정정**: 원작은 거울 소유자를 일반 그리기에서 빼지 않고 (bit 61 = 반사에 비침 을 끔), 정적 월드를 다시 그리지 않으며 반사 전용 대리 메시·bit 61 모델·영웅만 그린다.
- 거울 인스턴스가 평면으로 잡힌 레벨 25 곳 (ATHN01F, 03A/03AA/03D/03E, 04A/04B/04C, 05B/05D, 06B/06C, 07/07B, ATRET01B/01C, OLYMP01/02, PAND00B/00D/01BB/03H/03I/05A/05B).
- 화면: OLYMP02 복도 바닥에 벽·횃불·왕좌 반사 + 대리석 결 (이전: 안개색으로 하얗게), ATHN04B 반투명 대리석 타일에 벽화 반사, OLYMP01·ATHN03A·PAND05A 바닥 반사.
  ATHN06B/PAND01BB/PAND03I 는 이미 가짜 반사 지오메트리 (`fakereflections`, `goreflection`, `bloodstairreflect`) 가 있어 변화가 작다.
- (§9 에서 해결) 데이터 관찰: 여러 레벨에 미리 뒤집어 둔 가짜 반사 메시가 있다 — 원작 SCR_Mirror 가 정적 월드까지 다시 그리는지, 값 [0]/[4] 로 캐릭터만 그리는지는
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

## 7. 시작 카메라 13 레벨 (30 문서 §7)
`--game LEVEL` 기본 시작과 실제 도착(이웃 LoadCheck 이음 / Warp) 을 모두 봤다. 실제 도착은 신설 `--arrive FROM[@x,y,z[,요°]]` 로 재현
(FROM 만 = Warp 도착 Start + CameraName, 좌표 = 이음 위치, 지점은 36 하네스의 전환 위치).

### 7.1 레일 비용 함수 [확실: 0x1F20F0/0x1F1AA0 분기, 0x1F1648 해독]
13 레벨 중 10 개(ATHN01C·02C·04C·04D·06C, ATRET01B·01C, PAND01BB·04F·04H)의 시작 카메라는 플래그 **0x435FE** 레일 카메라
(0x100 궤도, **0x400**, 0x200 없음, 요 범위 ±60..±88) 다. 원작은 각도 범위가 있으면 0x200 유무로 비용 함수를 고르는데
(0x200 = 0x1F12F0 각도 초과 ×1000, **없음 = 0x1F1648**: 대상 방향을 범위 안으로 자른 방향으로 잰 거리가 [min, max] 밖인 정도), 엔진은 0x200 이 없으면
거리 비용 0x1F1280 을 썼다. 그래서 레일이 영웅 곁을 지나가면 **영웅 앞쪽** 점(설계 정면과 반대)을 골랐고, 각도 자르기로 눈이 그 점에서 옆으로 돌아 벽 속에 들어갔다.
**0x400** = 각도를 레일 접선 좌표계(Z = 접선, X = 위 × 접선)에서 잰다 (비용, 눈 각 자르기 0x1F3354 둘 다) — 설계 정면 (0,0,±1) 레일 카메라가
"접선 방향/반대 방향을 본다" 는 뜻이 된다. 같이 맞춘 것: 첫 표본 = 매듭 수 × 4 등분 (0x1F20F0), 지역 탐색 = 보폭 0.05 걷기 + ×(−½) (0x1F1AA0),
전환 시 기준점 = 머리 데드존 자르기 뒤의 시선 축 위 점 (30 §8.2, 데드존 0 이면 T) + 직전 정면 보너스 (0x1F23E0), 같은 레일 카메라끼리 전환하면 t 유지 (0x1F3A38).

### 7.2 다른 슬롯 레벨 CMV [확실: 볼륨 질의 = 충돌 월드, RCM_ 조회 = 전역 GO]
- ATHN02D (메두사 방, 숙주 ATHN02C): 생성 센서가 자기 CMV 2 개(MedusaHeadCam, MedusaSpawnCam, 우선순위 100)를 Vis Attr11_1 = 2 로 끈다. 엔진은 생성
  스크립트 전에 카메라를 골라 MedusaHeadCam 이 남았다 → 시작 카메라를 `world.start()` 뒤에 고르고, 숙주 CMV(Room2Cam*) 도 질의한다.
  기본 Start (DMC 문 앞 z 2144) 는 Room2Cam3 레일 범위 밖이라 문틀이 영웅을 가린다 — ATHN02D 는 원작에서 단독으로 시작하지 않는 부속 WAD 이고,
  실제 진입(ATHN02C LoadCheck)에서는 Room2Cam4 가 영웅을 잡는다 (`after_arr/ATHN02D_from02C.png`).
- ATHN05AA: §4 참고 (RublRmCam19, 눈이 연기층 위).

### 7.3 나머지 둘
- **ATHN01F**: 240 프레임 화면은 원작 진입 연출이다 — `heroEntry-specialCam` (생성 2 초 뒤 Internal 0x9 끔 + SwitchCamera "AniRvlCam",
  관절 애니 카메라 cine1camgrp) → `heroEntry-idleOFF` 9.5 초 뒤 질의 켬 → SexCam1. Warp 도착(WarpfromD, CameraName RvlCam)도 같은 연출.
- **ATHN07B**: 카메라 정상, 거울 반사 사본 (§4).

### 7.4 검증
- 스크린샷 (`/Users/obeomsu/.claude/jobs/6f65672f/tmp/cam2/`, 커밋 안 함): `before/` (수정 전 13 + ATHN05AA), `after/` (수정 후), `after_arr/` (실제 도착 16 경로),
  `long/ATHN01F_1000.png` (연출 끝 SexCam1).
- 헤드리스 비교 (임시 탐침, 눈 → 대상 시선이 정적 충돌(NoPlayerCollision 제외)에 막히지 않는 수): 기본 시작 105 레벨 68 → **73**,
  36 하네스의 실제 도착 188 건 96 → **117**.
- `StartCameraTest` 4: 7 레벨 기본 시작 카메라 이름 + 시선, ATHN04C camera1 눈이 영웅 뒤 128, 숙주 CMV (ATHN02D Room2Cam, ATHN05AA 숙주 카메라·연기층 밖),
  이음 넘기기(ATHN04A → 04C 같은 카메라·눈 연속).

### 7.5 남은 것
- PAND02B 기본 시작: 레일 camFRMCLFFS 의 두 극소(영웅 앞뒤) 중 먼 쪽을 골라 모래 둔덕이 영웅을 반쯤 가린다 (이전 거리 비용은 가까운 쪽).
  → §8.2: 비용·표본·좌표계는 원작과 같다. → **§10**: 각도 범위 카메라는 눈을 고정하고 회전만 자른다 (30 §8.2) — 이제 모래 둔덕이 가리지 않지만 (시선 통과),
  먼 극소 u 6.61 에서 회전이 설계 ±85° 로 잘려 영웅이 화면 밖이다. 해독상 원작도 같은 t·같은 자르기다 (PAND02B 는 PAND02AA 에서 걸어 들어오는 레벨이라 기본 시작은 원작 경로가 아님).
- PAND01BB ← PAND00D Warp: ConHallCam5 (모드 2, 고정 위치) 앞을 덮는 것은 z −720 벽 (그것은 충돌 끝면) 이 아니라 `gosandarena` 파트 p20 (탑 외피) 이다.
  → §8.1: 원작 VU1 은 레벨 메시 뒷면을 제거하지 않는다 [확실] — 뒷면 제거는 넣지 않음, 미해결. → §10: 상태 마스크·데드존으로도 ConHallCam1 이 되지 않는다.
  → **§11 해결**: 가시 칸 컨텍스트 — ConHallCam5 눈 위치 칸이 bvoutside (gosandarena) 를 켜지 않는다.
- ATRET01C ← ATRET01B Warp: Start CameraName LWRCAM11 은 한 프레임 후보라 볼륨 카메라 LwrCam18 로 돌아간다 (원작 SwitchCamera 와 같은 규칙).
- ~~리셋(레벨 시작·Warp) 직후 레일 첫 표본 기준점~~ → §10 (30 §8.2): 머리의 데드존 자르기가 눈을 옮기므로 데드존 0 이면 T [확실]. 데드존이 있는 카메라만 리셋 첫 머리를 데드존 0 으로 [근사].

## 8. 3 차 (뒷면 제거 조사 · PAND02B 레일 재확인)
스크린샷 (커밋 안 함): `/Users/obeomsu/.claude/jobs/6f65672f/tmp/qa2/` — `before/` (현재), `exp/` (실험: 뒷면 제거 `cull_back/front.png`, 리셋 기준점 `lit_*.png`).
ATHN07B 는 거울 담당 브랜치가 처리 (MDL +0x28 bit 57 = 거울 화면 전용, 0x1508D0 → 오브젝트 +0xD8 마스크, 0x162F20 에서 뷰포트 +0x3AC 와 AND).

### 8.1 레벨 메시 뒷면 제거 — 원작은 하지 않는다 [확실: 마이크로코드 + 전 WAD 데이터]
- 메시 VU1 = 주 프로그램 0x2ABB00 (`exe:vu1 … 0x2abb00 0x2ae000`, VU 0x000–0x49A) + 커널 라이브러리 0x2AE000..0x2B1690 (VU 0x49B 부터 캐시, 0x16AAD0/0x16ABA0/0x16A980).
  커널 표 0x2AB800 (u32 × 0x89, 값/8 = 커널 시작). 프로그램 번호 = `((머티리얼 VU 플래그 | 0x20?) & obj+0x14) | obj+0x10` (0x165FFC–0x16601C), 표 색인 = `& 0x7F` (bit 0x20 은 같은 커널의 두 번째 진입점).
- **뒷면 제거가 있는 커널은 표 4·0x44 (0x090, 0x343) 와 0x81·0x83·0x85·0x87 뿐**: 빠른 경로에서 화면 2D 외적 `mulay/msuby` 부호를 정점 w 비트와 `fmeq` 로 비교해
  ADC 를 세우고 (0x09E/0x09F), 돌려주는 이어가기 주소의 bit15 (−0x7BD6/−0x7BD2) 로 클립 경로 0x302 의 3D 행렬식 검사도 켠다 (928.x 부호, `ibgez vi8, 0x30F`).
  나머지 커널은 0x3EE/0x3FB/0x419 (양수) 를 돌려줘 클립 경로에서도 제거하지 않는다.
- 프로그램 bit 2 의 출처는 0x154410 의 `rf & 0x4000 → VU 플래그 = 4` 하나뿐인데 (그 외 rf&0x80 → 1, rf&2 → 0x82, rf&0x2000 → 0x10001/0x10003, rf&4 → 0x80),
  **214 WAD 머티리얼 레이어 11492 개 중 rf 0x4000 은 0 개** (rf 값 22 종). MDL +0x28 렌더 플래그 (u64, 9 종) 에도 없다.
  obj+0x10 은 0x10 (46848) / 0 (1980) / 0x50 (671) 이라 대부분 bit 4 가 서 있어 표 4 에 올 수도 없다. 0x80+ 커널은 0x166278/0x168698 (0x810 기준, 별도 패스) 전용.
- 정적 메시 (type 0x1D) 정점 w 하위 15 비트는 전부 0 (PAND01BB 108618 정점) — 제거 커널이 쓰는 정점별 기대 부호 정보도 없다. 04 §4.4 의 "스트립마다 감김이 제각각" 과 맞다.
- 결론: 08 문서의 레벨 메시 양면 렌더가 원작과 같다. 뒷면 제거는 넣지 않았다 (`MeshObject.vuOr` = obj+0x10 만 노출).
- **PAND01BB ← PAND00D (→ §11 해결: 가시 칸 컨텍스트)**: 화면을 덮는 것은 `gosandarena` (`MDL_sandArena` 파트 p20, `MAT_M01stone128Sand04a`) — 바깥에서 보면 탑 (모래 위 기둥,
  꼭대기 석상) 이고 복도 `HallSectionB` 가 그 안을 지나간다. ConHallCam5 는 고정 위치 (−2816, −222.5, −791) — 충돌 끝면 z −720 너머 복도 연장부 안이며,
  p20 외피가 눈 앞 11 (z −780) 과 영웅 뒤 (z −507) 에서 복도를 가로지른다. 선택: T 가 ConHallCam5 hull 안쪽 0.6, ConHallCam1 hull 바깥 6 (경계).
  실험으로 sandArena 만 뒷면 제거하면 영웅이 보이지만 (`exp/cull_back.png`, 엔진 감김 기준) 감김이 일정하지 않아 외피 일부는 남고, 원작 근거가 없다.
  원작에서 이 외피가 안 보이는 경로 (오브젝트 가시성 마스크 0x162F20 +0xD8, 뷰포트 +0x3AC 등) 는 찾지 못했다.

### 8.2 PAND02B 기본 시작 레일 — 원작과 다른 곳 없음 (리셋 기준점 [근사] 제외)
- camFRMCLFFS: 플래그 0x435FE (0x400 접선 좌표계, 0x200 없음 → 0x1F1648), 요 180 ± 85, 거리 240 (min = max), 매듭 9 → 표본 37.
  첫 표본 비용 (기준점 = T): u 0.97 (−1920, 264, 365) 9.97 / **u 6.61 (−2735, 219, 155) 6.63** → 먼 쪽.
- 다시 해독해 엔진과 같음을 확인: 0x1F1648 (기준점 − P, 접선 좌표계 X = 위 × 접선, Y = 접선 × X, Z = 접선, 0x1F17C8–0x1F1844; 피치·요를 설계 ± 범위로 자름 0x1F18F4/0x1F0C38;
  방향 · v 가 [0x84, 0x80]×16 밖인 정도), 0x1F20F0 (매듭 × 4 등분, 양 끝 포함, 엄격한 < 로 앞쪽 우선, 범위 0x1F2290–0x1F22C8 = 설계 각 ∓ 범위,
  정면 보너스 = −100·normalize(기준점 − P)·t0, 0x1F23E8).
- 남은 차이는 리셋(레벨 시작·Warp) 때의 기준점뿐. 해독: 0x1EDF48 이 상태 링을 템플릿 0x1F2500 (단위 행렬, 원점, +0x80 −90, +0x90 200) 으로 채우고,
  0x1F29A0 머리가 (대상 − 눈) 을 시야 좌표로 옮겨 +0x90/+0x88/+0x8C 를 채운 뒤 **새 카메라 데드존 (CAM 0x98/0x9C × 0.75·tan(화각/2), 0xA0/0xA4 × tan, 0x1F2AAC–0x1F2BB8)
  으로 각을 자르고**, 기준점 = +0x30 − 행2·(+0x90·cos·cos) 이다. 데드존 0 이면 기준점 = 눈 + 정면·|T − 눈| (투영이 아니라 전체 거리 — 전환에서도 엔진과 다른 점, 미반영).
  리셋 그대로 (0, 0, −|T|) 를 쓰면 PAND02B·PAND03B·ATHN06C→06B 는 괜찮지만 PAND03F (THallCam1) 가 벽 속으로 간다 (`exp/lit_*.png`) — 템플릿과 첫 갱신 사이에
  빠진 단계가 있다는 7.5 의 판단 그대로. 근거 없이 기준점을 바꾸지 않았다.
  → **§10 에서 해결**: 빠진 단계는 머리의 0x1F1108 (데드존 밖이면 행렬을 (+0x80, +0x84) 로 다시 만들고 눈 = T − 거리·R·방향(자른 각)). 그래서 데드존 0 이면 기준점 = T,
  위 "눈 + 정면·|T − 눈|" 도 틀림 (눈이 옮겨진 뒤라 정확히 T).
- 디버그: `-Dgow.debug` 카메라 후보 목록, `-Dgow.debug.cam` 풀기 매개변수·레일 첫 표본 비용, `-Dgow.debug.cmv` CMV hull 별 바깥 거리 (첫 선택).

### 8.3 테스트
엔진 테스트 287 (변화 없음).

## 9. SCR_Mirror 거울 뷰에 그리는 것 (§6.2 후속) — 16 §1.7, 31 §8
§6.2 의 열린 질문 (정적 월드를 다시 그리나, 값 [0]/[4] 의 뜻) 을 실행 파일로 정했다. 결론: **정적 월드는 다시 그리지 않는다.**
거울 뷰 = 반사 전용 대리 메시 + 렌더 플래그 bit 61 모델 + (값 [4] = 0 이면) 영웅. 가짜 반사 메시는 "미리 뒤집은" 것이 아니라 방의 **간이 사본**
(ATHN06B `fakeReflections` y 736..1216 = 바닥 위 방 공간) 이고 거울 뷰에서만 반사 카메라로 그린다. 이전 엔진은 이 사본을 일반 뷰에도 그려
ATHN07B (화면 전체), ATHN06B (방 안이 흐린 벽), PAND03I (주황 판) 를 덮었다.

### 9.1 뷰 마스크 [확실: 디스어셈블 + 데이터]
- 모든 모델은 뷰 마스크 (모델 +0xD8) 를 가진다. 렌더러 (0x151120, 0x15CD50, 0x162F20 …) 는 `모델 마스크 & 현재 뷰 +0x3AC ≠ 0` 일 때만 그린다.
- **0x1508D0(모델, m)**: 렌더 플래그 (+0xC0 u64) bit 57 → `(m & 0x40000055) << 1` (거울 뷰만), bit 61 → `m | 그 시프트` (둘 다), 그 밖 → `m & 0x7FFFFF55` (일반만).
- 뷰: 생성자 0x168D38/0x168E68 → 0x41. 거울 관리자 0x129A18 (Engine_Init) 의 거울 뷰 = `(0x41 & 0x40000055) << 1` = **0x82**.
  갱신 0x12A6C8 가 고른 거울의 값 [4] (렌더러 +0x28) ≠ 0 이면 0x80 을 빼 **0x02**, 0 이면 0x80 을 켠다.
- 레벨 모델: 0x156820 이 MDL 렌더 플래그를 모델 +0xC0 으로 복사, m = 1 (0x1503E0). **MDL 데이터 +0x28 u64** 가 그 플래그다 (04 문서):
  전 WAD 조사 — bit 57 28 개 (전부 반사 대리: `fakeReflections`, `refkect`, `reflection`, `…ReflectONLY`, `mirroredStatue`, `swordReflections`, `PAND05A Mirror*` …),
  bit 61 60 개 (ATHN04 방·벽화·문·항아리, PAND03H 석상, PAND05A, ATHN05 `…Reflect`), bit 56 671 개 (글로우·플레어 [추정]). 적 MDL 은 bit 61 이 없다.
- 실행 중 bit 61 + m = 0x40 (→ 0xC0) 을 주는 곳: 영웅 0x1DAEC0 (Player 0x22C958 → 0x1D62B0), 블레이드 Blades_Init·0x2015B8, 무기 부착 0x19C240 (`RWEAPIH`),
  0x1DF1E0 ← PushPull 0x20FB98 / IO_TandF 0x213C08 / IO_CSM 0x2154F8 (+ 0x1E7B90). → 값 [4] 가 끄는 쪽 (0x80) 이 "영웅 무리".
- 0x129820 (SCR_Mirror 시작) 은 소유 모델의 bit 61 을 **지운다** = 거울이 자기 자신을 비추지 않게 (일반 뷰에는 그대로 그림 — §6.2 의 "일반 그리기에서 뺀다" 는 오독).
  0x150738(모델, 0) 은 파트 플래그 bit 32..36 을 모드 0 (bit 32) 으로 [미해석: 파트 그리기 모드].

### 9.2 거울 고르기 · 값 [0] [확실: 흐름 / 추정: 의미]
- 0x12A6C8: 관리자 목록 (+0x1C) 의 렌더러마다 0x12A330 (모델 숨김 bit 54 면 FLT_MAX, 모델 Y 축 = 법선, 카메라가 앞쪽일 때 거리) → **가장 가까운 하나만** 거울 뷰를 켜고
  0x12A4E8 로 반사 카메라. 없으면 거울 뷰 끔 (+0x3A4 = 0).
- 값 [0] (렌더러 +0x24) ≠ 0 → 거울 관리자 목록 +0x14 (vtable 0x2BE268, 그리기 0x120498 → 0x11FEA0 스프라이트 2 회, 계수 0.5) 켬 — 반사 이미지 후처리 [추정: 흐림]. 미구현.
- 목록 +0xC/+0x10 (vtable 0x2BE240 = 0x121128), +0x18 (vtable 0x2BDC10 = **0x12ABA0**, §6.2 의 "vtable 5" 는 이 목록의 그리기) 는 0x15F288 로 단계 (2,6)/(2,7)/(0,7) 에 등록되는
  GS 패킷 목록. 0x12ABA0 = TEST_2 0x3000D, ALPHA_2 0, TEX1 선형, 뷰 사각형 (+0x368..0x374) 을 32 화소 띠 스프라이트로 복사 — 합성 세부 [미해석].

### 9.3 엔진 (`Mirrors`, `LevelScene.renderMirrors`, `GowModel.renderFlags`)
- 인스턴스 뷰 마스크 = `Mirrors.modelMask(MDL +0x28, 1)` (PushPull/IO_TandF/IO_CSM 비헤이비어 GO 는 bit 61 + 0x40), 거울 소유자는 bit 61 뺌.
  일반 패스·이웃 슬롯·자세 인스턴스는 마스크 & 0x41 ≠ 0 만 → **반사 전용 메시는 일반 뷰에 안 그림**.
- 반사 패스: 가장 가까운 거울 하나 (`Mirrors.nearest`, 눈 → 거울 중심 거리 [근사: 0x12A330 의 앞쪽 검사 생략]) 만 스텐실 반사, 나머지 거울은 모델만.
  `마스크 & 거울 뷰 마스크` 인 레벨 인스턴스 + 자세/반복 act 인스턴스 (스키닝) + 값 [4] = 0 이면 영웅·블레이드 (`LevelScene.setMirrorActors`, GameScene 이 건다).
  적·시체·파티클은 그리지 않는다 (원작 마스크에 없음 — 파티클 노드 마스크 +0x48 의 기본값은 [미해석]). 하늘도 반사하지 않는다 (bit 61 없음).
- 반사 카메라 = `Camera.mirror` (VP·R) + `Camera.clipPlane` (skinned.vert 에도 `gl_ClipDistance`), `glFrontFace(GL_CW)` 로 감김 반전 (면 컬링 패스 대비).
- 평면이 아닌 거울 메시 (ATHN01E goreflfloor 이탈 10, PAND05B oorMirrorGRP 5) 는 생략하던 것을 0x12A330 처럼 모델 Y 축 (관절 0 월드 2 행) 평면으로 —
  평면 거울의 맞춘 법선도 모두 이 축과 평행 (ATHN01E 는 이제 거울 1).
- 디버그: `GOW_MIRROR=raw` = 거울 모델을 반사 위에 그리지 않음 (반사 장면만), `GOW_MIRROR=off` = 이전 동작 (반사 없음, 대리 메시도 일반 뷰에).

### 9.4 검증 (스크린샷 `/Users/obeomsu/.claude/jobs/6f65672f/tmp/qa3/`, 커밋 안 함, `_on` / `_off` = `GOW_MIRROR=off`)
- OLYMP02: 대리석 복도 바닥에 크레토스가 거꾸로 비치고 벽 반사는 `refkect` 대리 메시 (이전: 벽 반사만, `off` 는 대리 메시가 벽 무늬를 늘어뜨림).
- ATHN04B: 바닥 타일에 bit 61 벽화 + 크레토스 다리 반사 (쓰러진 적은 반사 없음).
- ATHN07B: 이전엔 `rtalFakeReflections` 가 화면 전체를 덮었다 → 포털 앞 크레토스와 포털 속 반사 장면 (§4·§7.3 의 "거울 반사 사본" 해결).
- ATHN06B (`_hero*` = `--arrive ATHN06B@-2800,740,-1520,90`): 방 안을 덮던 흐린 사본 벽이 사라지고 실제 방이 보인다. PAND03I: 주황 판 사라짐. PAND01BB·PAND05A·ATHN01E: 작은 변화.
- 테스트: `MirrorViewMaskTest` 3 (마스크 식, MDL bit 57/61 — OLYMP02 refkect, ATHN06B fakeReflections, ATHN04B polySurface16576, 가장 가까운 거울·모델 축 평면). 엔진 테스트 290.

### 9.5 남은 것
- ~~ATHN06B floorReflect 등 거울 모델 머티리얼이 불투명이면 반사가 전부 덮인다~~ → §12 (층 0 = 알파 테스트 없는 GS 블렌드, 반사 = 1 − As).
- ~~값 [0] 후처리 (0x120498), 0x12A330 의 앞쪽 검사~~ → §12. 정확한 거리 기준점 (메시 경계 구 중심 +0x48.., 엔진 AABB 중심), 파티클 노드 뷰 마스크.

## 10. 4 차 (영웅 상태 마스크 · 화면 데드존 · 각도 범위 눈 고정 · IdleCam) — 30 §8
스크린샷 (커밋 안 함): `/Users/obeomsu/.claude/jobs/6f65672f/tmp/cam2/r2/` — `v1/` (리셋 해독 그대로), `v2/` (최종), `dbg/`.

### 10.1 해독·구현 요약
- **영웅 상태 마스크** 0x1EE510 의 각 비트 출처를 +0x174 를 쓰는 곳에서 확인 (30 §8.1): 0x1 전투 (적 무리, 0x1B5D00/0x1D30D0), 0x2 지상, 0x4 줄, 0x8 턱·벽, 0x10 날기, 0x20 물, 0x40 점프, 0x80 떨어짐.
  CAM 이름이 마스크와 맞는다 (FallCam 0x80, LadderCam 0x08, camRopes 0x04, SwimCam 0x20). 엔진 `CameraRig.setHeroState` (GameScene 이 내비 상태·점프 여부·전투 적으로 채움).
- **화면 데드존** 0x98..0xA4 (30 §8.2): 매 프레임 머리가 대상의 화면 각을 자르고 눈을 옮긴다. 기준점 (레일 비용·모드 2·3 각·거리) = 옮긴 눈의 시선 축 위 점.
  카메라 2920 개 중 732 개가 데드존을 가지며 대부분 한 점 (영웅을 화면 가운데 아래 0.1–0.25 반화면에) — 이제 원작처럼 그 자리에 둔다 (`Pose.sa/sb`).
- **각도 범위 카메라** (요 &lt; 180 또는 피치 &lt; 90): 눈 = T − 깊이·R·방향(화면 각) 을 정한 뒤 회전만 자른다 (0x1F3640/0x1F3354). 이전 엔진은 자른 방향으로 눈을 T 둘레로 돌렸다.
- **리셋 기준점** (§7.5, §8.2 의 "빠진 단계"): 0x1F1108 눈 이동. 데드존 0 이면 T [확실]. 데드존 카메라는 해독대로면 |T| 비례로 밀려 (HDES01A 약 740) 실제 Warp 도착이 깨지므로 첫 머리만 데드존 0 [근사].
- **IdleCam**: 볼륨 관절 카메라 제외를 없앰 (30 §8.3). ATHN01A 시작 = IdleCam 팬 9.5 초 → Idle2Cam → Boat1Cam4 (`v2/ATHN01A_120.png`, `ATHN01A_1300.png`, `ATHN01A_2200.png`).

### 10.2 검증
- 헤드리스 시선 (이전과 같은 탐침, NoPlayerCollision 제외; 충돌 없는 부속 3 레벨은 항상 통과로 셈): 기본 시작 105 레벨 73 → **75**, 실제 도착 188 건 117 → **120**.
  얻음: ATHN01A (Idle2Cam — 탐침은 스크립트를 안 돌려 생성 센서 끔이 없다), PAND02B·PAND03G (눈 고정), PAND01B → PAND01A, PAND03F/03J → PAND00C (Lvl3TubeCam2, 관절 카메라 볼륨).
  잃음: PAND02H Acam4 — 요·피치 범위 0, c = 1 이라 눈 = 레일 점 (카메라 GO 높이, 물속 바닥 가까이) — 해독대로이며 엔진 화면에서는 크레토스가 보인다 (`v2/PAND02H.png`).
  화면 안 (4:3 절두체) 판정을 더해 보면 기본 시작 101/102, 도착 185/188 이 대상을 화면 안에 둔다 (밖: PAND02B 기본 시작 — 10.1 각도 범위, 원작 경로 아님 / PAND00C → PAND03F·03J·02A Warp 의 Start CameraName 카메라 CoreTubeCam4·Lvl2HallCam2, 한 프레임 요청 뒤 연출용).
- 스크린샷 `v2/`: ATHN04D (LedgeCam2), ATHN04D_from04A (BrgCam8), PAND03F (THallCam1), PAND03B, HDES01A (PillarCam1, 영웅이 가운데 아래), ATHN06B_from06C (ExCam2), ATHN04C, ARENA06 — 이전과 같은 카메라.
- `StartCameraTest` 7 (+3: 상태 마스크 비트, ATHN02A LadderCam 벽 전용, HDES01A 데드존 화면 위치), `CameraRigTest.boatRunFramesKratos` 는 IdleCam 인트로부터.

### 10.3 남은 것
- ~~PAND01BB ← PAND00D~~ (§11 가시 칸 컨텍스트로 해결): 마스크 (0xFE, 지상) 와 데드존 (ConHallCam5 는 모드 2 고정 위치라 무관) 으로는 바뀌지 않는다. 선택은 점 질의 0x11E5E8 (반지름 없음) 이고
  리셋 직후 선분 질의 (+0x804 = 0) 도 없어 ConHallCam1 (hull 밖 6) 은 후보가 아니다 — 원작도 ConHallCam5. 외피가 안 보이는 경로는 여전히 미상 (§8.1).
- 리셋 첫 머리 데드존 [근사], 전투 비트 = AI COMBAT 상태 [추정], 날기 0x40 (영웅 없음).
- ~~히드라 잡기 카메라의 크레토스 위치~~ → 37 §4.2 (원작 동기 정렬 0x204BF0).

## 11. 5 차 — 가시 칸 컨텍스트 (PAND01BB 탑 외피) · near 10 · 안개 선택
스크린샷 (커밋 안 함): `/Users/obeomsu/.claude/jobs/6f65672f/tmp/cam3/` — `pand01bb_before.png` (이전), `vis2_pand01bb.png` (해결), `qa/on|off/<레벨>.png`
(105 레벨 240 틱, `off` = `GOW_VISCELLS=off`), `qa/fog2|fognear/` (안개 선택 새/이전).

### 11.1 가시 칸 → 컨텍스트 [확실: 0x16B2D0 · 0x16DC60 · 0x16D240 · 0x16D6F8 · 0x152EC8]
- 레벨 WAD 끝의 `GROUP{ RES CXT_bv<이름> (서버 0x80000001, +0x30 = 번호)  하위 서버들…  RES go… (크기 0) }GROUP` 가 **컨텍스트 소속**이다
  (예 PAND01BB: bvbstatuefog {gofog}, bvsthdroom, bvladder {goladderdown}, **bvoutside {…gosandarena…}**, bvhall {gohallsectionb}, default). 전 WAD 102 시트 중 99 개가 칸을 가진다.
- SheetHdr (12 문서 §2.1) 의 숨은 섹션: 0x5C u16 = 칸 수, [10] (+0x8C) = 칸 0x1C B {min, max, u32 레코드 번호}, [1] (+0x68) = 레코드 (u16 단위),
  [2] (+0x6C) = 컨텍스트 이름 0x18 B × ([3] − [2]) / 0x18 (0x16D9C0 이 레지스트리에서 찾음). 레코드 = u16 머리 (bit0 한 번만 / bit1 씀), u16 항목 수,
  항목 {u16 형식, u16 n, …}: 0/1 = 비트맵 켬/끔 (전 WAD 비어 있음), **2 = 컨텍스트 켬, 3 = 끔**, 4 → 0x1A·n, 5–7 → 0x18·n (건너뜀).
- 매 프레임 0x152EC8: 시트마다 마스크 +0x150 = 0 (0x16D6F0) → 활성 뷰포트마다 **카메라 행렬 위치**로 0x16DC60 (포함하는 칸마다 형식 3 먼저, 그다음 0/1/2;
  경계 포함) → 뷰포트마다 0x16D6F8(뷰 마스크 +0x3AC): 비트가 선 컨텍스트는 항목 (MDL 0xF / PRT 0x13 / 0x1C / SHG 0x27) 의 +0x48 에 그 뷰 비트를 켜고, 아니면 끈다 (0x134980).
  목록에 없는 컨텍스트 (CXT_default, CXT_<레벨>) 는 건드리지 않아 항상 보인다. 마스크가 0 에서 시작하므로 뷰 하나면 형식 3 은 효과가 없다.
- **PAND01BB ← PAND00D**: ConHallCam5 눈 (−2816, −222.5, −791) 은 칸 1 (−3120,−320,−880)..(−2192,−32,−160) 안 → bvladder·bvbstatuefog·bvhall 만 켬 →
  `gosandarena` (bvoutside) 를 그리지 않는다 → 복도 끝 크레토스가 보인다 (§8.1·§10.3 의 미해결). 바깥 칸 6 에서는 반대로 외피가 보이고 복도가 숨는다.
- 안개 (0x20D220 → 0x20D0D8): SCR_Fog / SCR_LayeredFog 는 소유 GO 의 컨텍스트가 CXT_default 이거나 눈 위치 칸이 그 컨텍스트를 형식 2 로 켤 때만 걸고 (0x16DE18),
  한 프레임에 하나만 (프레임 번호 0x29F324). 안개 GO 는 거의 다 자기 bvfog… 컨텍스트에 있다 (전 WAD 대조).

### 11.2 엔진 (`CollisionSheet.visContexts/visCells/visibleContextMask`, `LevelLayout.contextOf`, `LevelData.contextHidden/contextActive`)
- `LevelScene.updateContextVisibility` (렌더마다 눈 위치, 이웃 슬롯 레벨도 같은 눈으로 자기 시트) → 일반·거울·자세/반복 act·하늘 인스턴스를 건너뛴다, 배치 이미터는 생성 정지
  (`LevelFx.setContextHidden` [근사: 원작은 PRT 그리기 마스크]). 거울 뷰 카메라 위치는 합치지 않는다 [근사]. 비교 `GOW_VISCELLS=off`, 디버그 `-Dgow.debug.vis`.
- 안개: `FxEnvironment.update` — 켜질 수 있는 안개 중 컨텍스트 소속 목록 순서로 앞의 것 (bv 그룹이 앞, CXT_default 가 뒤 [추정: SCR 갱신 순서]), 아무것도 없으면 이전 안개 유지
  (처음은 첫 안개). 이전 "소유 GO 가 가장 가까운 Fog" 는 `GOW_FOGPICK=near`. 바뀐 레벨: PAND04E (gofoggerwater 청색 → gofogger 사막색), ATHN01D (돛대 안개 → 궁수 안개),
  PAND04G·PAND02D·ATHN03D (같은 계열 다른 안개). PAND00C 는 같은 결과 (CXT_default 의 관 안개 gofogtubel3 는 주 안개가 꺼진 곳에서만).
- near: 엔진 2 → **10** (원작 뷰포트 +0x384, 30 §8.4). 원작 클립은 VU1 에서 6 평면 다각형 자르기 (주 프로그램 0x2E5–0x3CE) 라 삼각형을 통째로 버리지 않는다.

### 11.3 검증
- 105 레벨 `on`/`off` 비교 (화소 변화 > 60 비율): PAND05B 25% (바닥 거울 반사 속 숨은 컨텍스트 — 원작도 질의는 뷰 카메라 위치), ATHN03C 6% (아래), PAND00A 4%
  (카메라 앞 벽 조각이 숨어 배경이 보임), HDES01D·ATHN01F·PAND03B 3% 이하 (먼 배경·조명 잡음), 나머지 ≤ 2.5% (비·AI 잡음).
- **ATHN03C 기본 Start** (`Start03C`, 눈 (−1987, 973, −1821)): 칸 0 (dbvmedusa) · 칸 4 (pcube309, bvall) 만 → **bvsky (하늘 gogroup2) 꺼짐** → 오른쪽 위가 검다.
  데이터·해독대로의 결과 (실제 진입 ATHN04A 도착 눈 (−3249, 987, −2444) 은 칸 3 이 bvsky 를 켜 하늘이 보인다, `athn03c_from04a.png`). 03A → 03C 는 이음 전환이라 이 Start 는 불러오기 전용.
- ATHN01A 인트로 뒤 (2200 틱, Boat1Cam4): 눈 (1519, 68.8, 2030) 이 칸 0 (bvstartship)·칸 1 (dbvdeck) 안 → 숨김 0 — 이 메커니즘과 무관.
  가리는 것은 선미 난간 (gohull*/gomainshipfront*): Boat1Cam4 레일 (1519, 2030)..(1437, 1987) 전체가 시작 지점에서 89–110 이고 요 범위 0 이라 눈이 레일 끝에 고정,
  해골 넷에게 맞아 **크레토스가 쓰러지면** 난간 뒤로 숨는다 (`vis_athn01a_2200.png`, 서 있으면 상반신이 보임 — 10.1 의 `v2/ATHN01A_2200.png`). near 10 으로도 같음 → 데이터대로 [수정 안 함].
- 시선 탐침 (§10.2) 은 충돌 기반이라 바뀌지 않는다: 기본 시작 75/105, 도착 120/188 그대로 (카메라 선택·충돌 코드 변경 없음). `StartCameraTest` 7 통과.
- 시험 `VisCellsTest` 2 (PAND01BB 칸·컨텍스트·안개, ATHN01A 숨김 0). 엔진 테스트 347.

## 12. 6 차 — SCR_Mirror 합성 순서 · 층 0 · 흐림 · 거울 뷰 가시 칸 (§9.5 후속)
스크린샷 (커밋 안 함): `/Users/obeomsu/.claude/jobs/6f65672f/tmp/mirror2/` — `before/` (HEAD 7acc5ea 빌드), `after/`, `dbg/ATHN04B_raw.png`.

### 12.1 프레임 순서 0x161A80 [확실: 디스어셈블]
- 렌더 목록 노드 (0x15F288(목록 0x3300D0, 층 a1, 칸 a2, 키 a3)): 층 × 0x40 + 칸 × 8 + 0x34 의 이중 연결 목록, 키 오름차순. 노드 +0x8 = 뷰 마스크, +0xC bit 0 = 켬.
  층별 그리기 0x160CC0(목록, 패스, 층, 뷰 마스크): 단계 0–4 서버 그리기 (vt+0xB8 (패스, 층, 단계)) 사이사이 칸 0–7 노드 (0x15F4E0 → 뷰마다 0x15F308).
- **머티리얼 그리기 층** = 머티리얼 플래그 bit 32..36 (MAT 레이어 flags2 하위 5 비트, 0 이면 0x1534BC 가 bit 34 = 층 2). 디스크 MAT 11,105 개가 0 (층 2),
  130 개가 8 (층 3). 0x150738(모델, 모드): 0 → bit 32 (층 0), 1 → bit 33, 2 → bit 35 (블레이드·0x12DE70), 3 → bit 40. **SCR_Mirror 시작 0x129820 이 소유 모델을 모드 0 = 층 0 으로**.
- 0x161A80: ① 거울 뷰 (마스크 0x800000AA, 패스 0) 층 1–4 → ② 0x1607A0(1, 0, 1): 주 뷰 층 0 (거울 모델) 컬러로 + 층 0 칸 7 노드 →
  ③ 주 뷰 층 1 → ④ 0x1607A0(0, 1, 0): 층 0 을 FBMSK 0xFFFFFFFF (깊이만) 로 다시 → ⑤ 주 뷰 층 2–4. ⇒ **거울 뷰가 프레임 맨 앞** (지운 프레임 위).
- 거울 관리자 0x129A18 의 노드 (0x12A6C8 이 거울 뷰를 켤 때만 +0xC bit 0 켬):

| 노드 | 뷰 | 층·칸·키 | 그리기 | 내용 |
|---|---|---|---|---|
| +0x0C | 거울 | 2·7·0x8000 | 0x121128 | 뷰 사각형 Z = 0, ZTST ALWAYS, FBMSK 전부 = **거울 뷰 층 2 뒤 Z 지움** |
| +0x14 | 거울 | 2·6·0 | 0x120498 | 값 [0] ≠ 0 일 때만: 0x11FEA0 × 2 (계수 0.5) 흐림 |
| +0x10 | 주 | 0·7·0x7FFF | 0x121128 | Z GEQUAL 0 (= Z 가 0 인 화소), RGBA (0,0,0,0), ALPHA 0 → **층 0 이 덮지 않은 곳의 반사를 지움** |
| +0x18 | 주 | 0·7·0x8000 | 0x12ABA0 | 프레임을 텍스처로 (TEX0 = FRAME, TCC), TEST_2 0x3000D = 알파 > 0 만, Z ALWAYS, FBMSK 전부, Z = 0xFFFFFFFF, 32 화소 띠 |

- 머티리얼 렌더 0x165998: **주 패스 ∧ 층 0** 이면 TEST = 0x50000 (알파 테스트 없음, Z GEQUAL, 깊이 씀) — 보통은 0x5360B (A ≥ 0x60, 실패는 RGB 만).
  ⇒ 거울 면은 (Cs − Cd)·As + Cd 로 반사 위에 블렌드되고 깊이를 쓴다. **반사가 비치는 정도 = 1 − As** (As = 텍스처 α × 정점 α × p28 × p38).
- 데이터: ATHN06B floorReflect 텍스처 α 0x70–0x80 · 정점 α 121–128 → As ≈ 0.75–1 (반사 0–25 %, 원작도 거의 덮임). OLYMP02 gomirror 정점 α 83 (반사 35 %).
- **0x12ABA0 의 Z = 0xFFFFFFFF (가장 가까움, Z24 = 0x800000 + z′, 16 §1.2)** 를 알파 > 0 인 거울 화소에 쓰면 뒤의 층 1–4 (영웅·월드 대부분은 층 2) 가
  그 화소를 못 덮는다 — 그대로면 거울 바닥 위 크레토스가 가려지므로 해석이 빠진 곳이 있다 [미해석: 층 0 그리기 때 프레임 알파·0x167900 칸 선택 t0 = 0]. 엔진은 넣지 않는다.
  → §12.1.1 에서 다시 봤다.

#### 12.1.1 0x12ABA0 재해독 — 바로 뒤 층 1 (하늘) 막기 [확실: GS 패킷 · 층 1 소유자 / 추정: 의도]
- GIF (NLOOP 10, 문맥 2): ZBUF_2 = ZBP·PSM, **ZMSK 0 (Z 씀)**; TEST_2 **0x3000D** (ATE · ATST GREATER · AREF 0 · AFAIL KEEP, ZTE · ZTST ALWAYS);
  XYOFFSET_2 0, SCISSOR_2 화면; ALPHA_2 0; TEXFLUSH; RGBAQ (0x80, 0x80, 0x80, 0x80); TEX1_2 0x60; **TEX0_2 = 프레임 (TBP = FBP·32, PSMCT32, TCC, MODULATE)**;
  FRAME_2 FBMSK **0xFFFFFFFF**. 스프라이트 = 뷰 사각형 (뷰 +0x368..+0x374 × 화면) 32 화소 띠, XYZ2 Z = **0xFFFFFFFF** (PSMZ24 → 0xFFFFFF).
  ⇒ 프레임 알파 > 0 인 화소의 Z 만 최대 (가장 가까움) 로, 색은 그대로.
- 프레임 알파: 지움 노드 0x29CF64 (§13.4.1) 가 0, ② 층 0 (거울 면) 이 As, 노드 +0x10 이 Z = 0 화소 (층 0 이 덮지 않은 곳) 를 0 으로 → **거울 면 화소만 알파 > 0**.
- 바로 뒤 ③ = 주 뷰 **층 1** — 층 1 은 모델 모드 1 (0x150738 → bit 33) 을 받는 것뿐이고 그 호출은 0x218778 (SCR_Sky 0x246B30 · SCR_AresSky 0x246BB8 → 0x218848)
  하나 [확실] ⇒ **하늘 모델이 거울 화소를 덮지 못하게 한다**. ④ 층 0 깊이 다시 그리기 (FBMSK 전부, 주 패스 층 0 TEST 0x50000 = GEQUAL) 는 Z 최대를 낮출 수 없어
  거울 면 깊이가 되돌아오는 경로는 여전히 [미해석] (층 2 이후가 거울 위에 그려지는 원작 화면과 맞추려면 어딘가 복원이 있어야 한다).
- 엔진: 하늘을 맨 처음 깊이 쓰기 없이 그리고 (LevelScene `render`) 거울 층 0 을 불투명 뒤에 그리므로 **하늘이 거울 화소를 덮을 수 없다** = 같은 결과 →
  추가 구현 없음. 층 2 이후는 거울 면 깊이로 정상 가림 (원작 화면과 같다).

### 12.2 거울 고르기 0x12A330 · 흐림 0x11FEA0 [확실]
- 0x12A330(렌더러, 눈): 모델 숨김 bit 54 → FLT_MAX. n = 모델 Y 축 (월드 행렬 2 행, 정규화), c = 메시 경계 중심 (+0x18 → +0x1C → +0x48..+0x50, 모델 +0x11C bit 3 이면 관절 위치).
  **n·(c − 눈) < 0 (눈이 Y 축 쪽) 일 때만** 렌더러 +0x0 = 평면 (n, n·c), 반환 |c − 눈|, 아니면 FLT_MAX. 0x12A6C8 의 시작값 0x4B189680 = 1e7.
  0x12A4E8 의 a3 (근거리 출력) 은 쓰이지 않는다 (호출자 sp+0x80 = 0 그대로 → near 그대로).
- 0x11FEA0(뷰, 프레임, 0.5): ZMSK, TEST 0x30000, ALPHA 0, TEX1 쌍선형. 뷰 사각형을 32 화소 띠씩 작업 버퍼 0x330A24 (폭 64) 에 ½ 축소 (UV 오프셋 0x29C9C0 = 0) →
  띠 4 개마다 프레임으로 확대 (UV +15/16 텍셀 0x29C9C8 = 15, ABE 없음). 두 번. 값 [0] = 1: ATHN01E/01F, ATHN05B/05D, PAND00B, PAND01BB, PAND05B (전 WAD 스캔).

### 12.3 거울 뷰 가시 칸 [확실: 0x152EC8 · 0x16DC60 · 0x16D240 · 0x16D6F8]
- 0x152EC8: 거울 갱신 0x12A6C8 (반사 카메라) → 시트 마스크 +0x150 = 0 (vt+0x98 = 0x16D6F0) → 활성 뷰 목록 (0x2CD3F8) 순서로 vt+0xB0 = 0x16DC60(카메라 위치):
  칸마다 형식 3 끔 → 그다음 켬을 **같은 마스크에 누적** (뷰 마스크 인자는 0x16D240 에서 쓰이지 않음) → vt+0xA0 (a1 = 0, 컨텍스트 활성화) →
  뷰마다 0x11EF98(뷰 +0x3AC) = 0x16D6F8: 누적 마스크로 그 뷰 비트를 켜고 끔. ⇒ **모든 뷰가 같은 집합**, 거울 뷰 카메라 = 반사 눈.
  §11.2 의 "거울 뷰 카메라 위치는 합치지 않는다 [근사]" 정정. 뷰 순서는 거울 뷰 먼저 [추정: 0x12A2A8 이 Engine_Init 에서 목록 맨 앞에 넣음].
- ATHN04B: 주 눈 (2352, −50, 1242) 은 칸 y −96..64 안, 반사 눈 y −111 은 밖 → 누적 결과 = 주 뷰 그대로 (벽화 반사 유지).
  반사 눈만으로 질의하면 45 개가 더 숨어 벽화 반사가 사라진다 (`dbg/ATHN04B_raw.png` 는 그 중간 시도).

### 12.4 엔진 (`LevelScene.renderMirrors`, `Mirrors.Mirror.distance/withAxis/blur`, `Renderer.drawLayer0`, `render/MirrorComposite`, `LevelData.contextHidden(float[][])`)
- `render()` 가 먼저 거울을 고르고 (`chooseMirror`, 0x12A330 앞쪽 검사·1e7) 반사 눈을 주 눈 앞에 넣어 가시 칸 질의. 거울 고르기는 가시 칸과 무관 (bit 54 만).
- 반사 패스: 스텐실 → 깊이 먼 값 → **바탕 = 지움 색 + 배경 안개** (`MirrorComposite.fill`, 원작 ① 은 지운 프레임 위) → 반사 장면 → 값 [0] 이면
  `MirrorComposite.blur` (반해상도 FBO 쌍선형 2 회, 확대 오프셋 3/16 작업 텍셀 [근사: GS 화소 왼쪽 위 표본 → GL 중심 환산]) → 깊이 복원 →
  `drawLayer0` (모든 파트를 ALPHA/ADD/SUB 블렌드 그대로, 깊이 씀). 고르지 않은 거울도 `drawLayer0`. 반사 패스 동안 그림자 모으기 멈춤 (`ShadowCasters.pause`).
- 비교: `GOW_MIRROR=off|raw` (이전과 같음), `GOW_MIRRORBLUR=off`, 디버그 `-Dgow.debug.mirror` (고른 거울·후보 거리).

### 12.5 검증 (240 → 120 틱, before = HEAD 빌드)
- 화소 변화 (> 24): ATHN04B 6.6 % (나무 바닥·틀도 As < 1 이라 크레토스·벽화가 비침, 이전엔 반투명 타일만), ATHN06B (`ATHN06B_hero`) 5.2 % (바닥에 기둥 반사 10–25 %),
  PAND01BB 0.9 % (흐린 크레토스 반사), ATHN03A 0.2 %, OLYMP02 0.05 % (이미 반투명), ATHN07B·PAND05A·ATHN05A ≈ 0, PAND05B 6 % (엘리베이터 시작 — 실행마다 다름).
- 테스트 `MirrorCompositeTest` 5 (앞쪽 검사·1e7, 값 [0] 흐림 표지, 공유 마스크 ATHN04B·PAND01BB 순서, SCR_Shadow 소유자·AI1 템플릿, 그림자 멈춤). 엔진 테스트 361.

### 12.6 남은 것
- ~~0x12ABA0 (알파 > 0 → Z 최대) 의 실제 효과 (§12.1 끝)~~ → §12.1.1 (층 1 하늘 막기, 엔진 순서로 이미 같음), 층 0 깊이 다시 그리기 ④ 의 목적 [미해석],
  ~~거울 뷰 바탕의 원작 지움 색~~ → §13.4.1 (검정).
- 메시 경계 구 중심 (엔진 AABB 중심), 파티클 노드 뷰 마스크, ATHN05A "거울 방" 은 SCR_Mirror 가 없다 (goaosks SCR_Shadow 만, 47 §11).

## 13. 2 차 전 레벨 순회 (LGT 조명 · 그림자 · 가시 칸 · near 10 · 거울 합성 · 카메라 필터 · 렌즈 플레어 · 사슬 · 줄 · 카메라 마스크 · 루트 모션 뒤)
스크린샷 (커밋 안 함): `/Users/obeomsu/.claude/jobs/6f65672f/tmp/qa4/` — `on/` (HEAD 9091a53, 240 틱), `on2/` (§13.3 수정 후), `arr/` (실제 도착 188 건, 수정 후),
`noshadow/` (`-Dgow.noshadow`), `novis/` (`GOW_VISCELLS=off`, 의심 3 건), `sheets/` (대조 시트: `all_*` = §3 의 `qa/after2` | `on`, `arr_*`, `c1–c4` = `cam3/qa/on` | `on`,
`fog1/fog2` = `cam3/qa/fognear` | `on`, `lit1/lit2` = 수정 전후, `shd` = 그림자 끔 | 켬). 스크립트 `sweep.sh` / `one.sh` (목록 `레벨` 또는 `레벨:FROM[@x,y,z]`), `Stat.java` (화소 변화·밝기·검정/흰색 비율).

### 13.1 방법
- 효과 전부 켬, 105 레벨 기본 시작 240 틱 + 30 §7 탐침이 쓰던 실제 도착 188 건 (Warp 65 · 이음 99 · 걷기 24). 예외 0, 이미지 105/105 · 188/188.
- 비교 기준: §3 의 `qa/after2` (조명·그림자 이전), §11 의 `cam3/qa/on` (가시 칸, 거울 합성·루트 모션 이전), `cam3/qa/fognear` (이전 안개 고르기).
  `Stat` 으로 화소 변화 > 24 비율·평균 밝기·검정 (합 < 40)·흰색 (합 > 720) 비율을 뽑아 큰 순서로 눈으로 봤다.

### 13.2 결과
- **검은/흰 화면, 지오메트리 사라짐, 거울 깨짐, 그림자 판 (큰 검은 덩어리·벽 너머 그림자): 0 건.** `noshadow` 대비 평균 밝기 감소 ≤ 1.1 (PAND00A 영웅 자기 그림자), 화소 변화가 큰 레벨은 AI·파티클 실행 차이.
- `cam3/qa/on` 대비 큰 변화 (ATHN08A/08C 69·63 %, ARENA08/26/19/20, ATHN05C …) 는 **루트 모션** (적·아레스 위치, 37 §11.4) 과 안개 고르기 (§11.2) 결과 — 렌더 회귀 아님.
- 가시 칸: 기본 시작에서 칸 때문에 사라진 것 없음 (ATHN03C §13.4 제외). 도착 중 `PAND01B ← PAND01A` 이음 (-1028,0,-1371) 은 화면이 거의 검정 —
  `GOW_VISCELLS=off` 면 복도가 보인다. 엔진 카메라가 `Follow` (CMV 후보 0, 눈 (-1028, 70, -1228)) 이고 그 눈이 두 레벨 어느 칸에도 넉넉히 들지 않아 bv 컨텍스트가 대부분 꺼진다.
  30 §7 헤드리스 탐침은 같은 도착에서 `ShldDrCam` 을 골랐으므로 **카메라 선택 차이 (탐침 ↔ 엔진 `--arrive`)** 가 원인 [미해결, 렌더 규칙은 데이터대로].
- 안개 바뀐 다섯 레벨 (§11.2) — 전부 눈 칸이 **한 안개 컨텍스트만** 켜서 순서 [추정] 이 끼어들지 않는다 [확실: 데이터]:
  PAND04E 칸 4 = bvfogmain 켬·bvfogwater 끔 → `gofogger` (보라 0.36/0.36/0.62, 물속용 `gofoggerwater` 아님), PAND02D → `goprfog` (`gofogwet` 컨텍스트 꺼짐),
  ATHN03D 칸 6 = bvfogaa 켬·bvlower 끔 → 회색 `gopcube3949` (청색 `gopcube3948` 은 아래층용), ATHN01D·PAND04G 는 같은 계열. 이름·위치로도 맞는 고르기.
- 그림자: 모드 2 (영웅) 볼륨이 받는 면까지 이어져 빛 반대쪽 몸이 어두워진다 (PAND03B `shd.png`, 47 §7.3 해독대로; 합성은 47 §7.4 [근사]). 벽 너머·판 모양 잘못 0.
- 카메라 쪽 남은 것 (렌더 무관, 대부분 §10 이전부터): PAND02B 기본 시작 (모래 둔덕), PAND05B 기본 시작 (~~`camEleEnter` 눈 y 21 ↔ 영웅 y 259~~ → §13.11 1: 해독 경로 = 엔진, 데이터대로),
  DEST01 ↔ DEST02 이음 (닫힌 문 너머 도착), PAND00C → PAND03F/03J/02A Warp (§10.2 OFFSCREEN), HDES01D → HDES01E · HDES01E → ATRET01A · OLYMP01 → OLYMP02 (탐침에서도 blocked).

### 13.3 캐릭터 밝기 — VU1 조명 커널 재검토 → 수정 [확실: 47 §3.1]
- 47 §3 의 "화면 배율 = M·min(…, 255/128)" 과 엔진 (레이어 rgba × min(라이트 합, 255/128)) 을 마이크로코드로 다시 봤다.
  주 프로그램 0x090–0x0E9: VU 플래그 4 (렌더 플래그 0x1000 — 조명 모델 228 개 전부 0x1800) 면 vf7 = **qw76 × 128**, vf29 = **qw77** + 주변 × vf7, 커널 = vf29 + vf27·d.x + vf28·d.y 를 255 로 자름.
  ⇒ 정점 색/128 = **min(A + M·(주변 + 색0·d0 + 색1·d1) + 1/128, 255/128)**: 자르기는 M 을 곱한 뒤, +1 편향.
- qw76 = 0x154410 의 `VMUL` = **MAT 헤더 색 (파일 0x08) × 레이어 rgb × 물체 색** (런타임 머티리얼 +0x10 = 파일 0x08, 0x1537C8 로 확인). 영웅 머티리얼은 헤더 0.8 × 레이어 1 → **M = 0.8**.
  qw77 = MAT 파일 0x18..0x20 (지금까지 "reserved 항상 0" 으로 읽던 칸) — 디스크 237 개가 0 아님 (적 발광 12.8 / 19.2 / 64, BMI 균열·눈 128): A = 값/128.
- 엔진은 헤더 색을 빼먹어 **영웅이 1.25 배 밝았고** (흰 PSdirLite 1.5 + 주변 0.38 → 1.88 → 원작 0.8 × 1.88 = 1.50), 헤더 0.4 × 레이어 2 파트는 2.5 배, 발광 더할 색은 없었다.
  수정: `WadAssets.litColor` → `uLgtMat` / `uLgtAdd` (정점 셰이더 `vuLight`, 노멀맵 경로 포함), 조명 경로 조각 셰이더는 uTint.rgb 를 곱하지 않음.
  `MaterialResource.addColor` (0x18) 추가. 화면 (`lit1`, `lit2`): PAND00A·DEST01 영웅·석상 −20 %, 그 밖 화소 변화 ≤ 14 % (캐릭터 영역만).
- 확인한 나머지: 라이트 색 = 색 × 세기, 슬롯별 채널 최댓값, 주변광 평균 (0x152490–0x1525C0 `VMULx`/`VMAX`/`VADD`), 커널 d.zw = 0 (0x0BC), 자르기 `minii` 2²³ + 255 (라이브러리 0x0BC/0x0D4).
  남은 [근사]: 조명 경로 알파 (원작 qw78.w = 물체 α × p28 × p38, 정점 α 무시 — 엔진 정점 α × uTint.a), 조명 없는 법선 모델의 고정 조명.
- 그래도 밝아 보이는 것은 식대로다: 크레토스 피부 텍스처가 밝고 (재 색), 흰 PSdirLite 1.37–1.5 가 정면이면 0.8 × 1.9 ≈ 1.5 배까지 간다 (255/128 아래).

### 13.4 ATHN03C 기본 시작 하늘 [확실: 데이터 / 원작 화면 미대조]
- 눈 (-1987, 973, -1821) 은 칸 0 (dbvmedusa 켬) 과 **칸 4 (-2096..-1280, 0..1056, -2560..-1552) — bvpcube309·bvall 켬, bvfogc·bvsky·bvcorner 끔** 안.
  칸 5 (같은 기둥의 y 1056 위) 도 bvsky 를 끈다. 즉 디자이너가 이 구역에서 하늘 (`gogroup2` = bvsky) 과 먼 LOD 건물 (`golodgroup7/8` = bvcorner) 을 일부러 끈다.
- 엔진 화면 오른쪽 위 검정 = 두 컨텍스트가 꺼진 틈으로 보이는 지움 색. 이 Start 는 03A → 03C 가 이음이라 불러오기 전용이고 카메라 `camNxtWad1` (다음 WAD 전환용 레일) 이다 —
  실제 도착 (03A·04A, `arr/ATHN03C_*`) 은 칸 3 이 bvsky 를 켜 하늘이 보인다. 원작에서 이 구역 카메라가 그 틈을 보지 않는지, 지움 색이 검정이 아닌지 (§12.6) 는 미확인 → 수정 안 함.
  → 지움 색은 §13.4.1 로 해결 (검정). 틈 자체는 데이터대로.

#### 13.4.1 원작 지움 색 = 검정 (0, 0, 0, 0) [확실: 디스어셈블]
- 프레임 그리기 0x161A80 이 뷰보다 먼저 0x29CF64 의 `+0xC bit 0` 을 보고 vt+0x14 를 부른다. 0x29CF64 = 사각형 노드 (클래스 vtable 0x2BE240,
  그리기 0x121128: 색 +0x20/+0x30/+0x40 × 128, Z +0x50, ZTST +0x54, FBMSK +0x58, ALPHA +0x68, ZMSK +0x70) — 0x15F638 이 만든 값:
  **색 (0, 0, 0, 0), Z 0, TEST 0x30000 (ZTE · ALWAYS), FBMSK 0, ALPHA 0, ZMSK 0** → 화면 전체 RGBA 0 · Z 0. `+0xC` = 3 (켬).
- 0x29CF64 를 읽는 곳은 0x161B60 하나, 쓰는 곳은 0x15FCB4 (생성) 하나 → 레벨·안개·컨텍스트에 따라 바뀌지 않는다. 같은 클래스의 다른 노드는 거울 Z 지움 (§12.1 +0x0C/+0x10)
  과 0x29CF68 (주 뷰 층 1 칸 7 키 0x8000: FBMSK 0x00FFFFFF = 알파만 0, ZMSK) 이다. 안개 (16 §1.2) 는 그 위에 배경 화소 (Z = 0) 를 칠한다.
- 엔진: `Renderer.CLEAR_R/G/B` = 0 (이전 (0.05, 0.04, 0.04) 은 임의 값) — `beginFrame`, `LevelFx.drawBackground` 바탕, 거울 바탕 `MirrorComposite.fill` 이 같은 값.
  ATHN03C 칸 4 틈은 이제 원작처럼 순수 검정 (`/Users/obeomsu/.claude/jobs/6f65672f/tmp/rend5/after/ATHN03C.png`).

### 13.5 상태표 (2 차, 기본 시작 240 틱)
상태: ○ 정상, ○f 안개 바뀜 (§13.2, 데이터대로), ○s 그림자 [근사], ◎l 밝기 수정 (§13.3), N 부속 WAD, △ 의심 (§6.3), △d 데이터대로 검정 (§13.4), △c 카메라 (§13.2).
정상 88 · 안개 6 · 밝기 수정 2 · 그림자 1 · 부속 4 · 의심 4 (렌더 회귀 0).

| 레벨 | 상태 | 메모 | 레벨 | 상태 | 메모 |
|---|---|---|---|---|---|
| ARENA06 | ○ |  | HDES01C | ○ |  |
| ARENA08 | ○ |  | HDES01D | ○ |  |
| ARENA09 | ○ |  | HDES01E | ○ |  |
| ARENA10 | ○ |  | OLYMP01 | ○ |  |
| ARENA12 | ○ |  | OLYMP02 | ○ | 거울 바닥 반사 |
| ARENA14 | ○ |  | OLYMP04 | ○ |  |
| ARENA19 | ○ |  | PAND00A | ◎l | 영웅 0.8 배 (§13.3) |
| ARENA20 | ○ |  | PAND00B | ○ |  |
| ARENA26 | ○ |  | PAND00C | ○f | 주 안개 (관 안개 아님) |
| ARENA27 | ○ |  | PAND00D | ○ |  |
| ATHN01A | △ | 물보라 밝기 (§6.3, 데이터대로) | PAND00E | N | 부속 WAD |
| ATHN01B | ○ |  | PAND01A | ○ |  |
| ATHN01C | ○ |  | PAND01B | ○ |  |
| ATHN01D | ○f | 궁수 안개 | PAND01BB | ○ | §11 유지, 흐린 거울 반사 |
| ATHN01E | ○ |  | PAND01C | ○ |  |
| ATHN01EE | N | 부속 WAD | PAND01CC | N | 부속 WAD |
| ATHN01F | ○ |  | PAND01D | ○ |  |
| ATHN02A | ○ |  | PAND02A | ○ |  |
| ATHN02B | ○ |  | PAND02AA | ○ |  |
| ATHN02C | ○ |  | PAND02B | △c | 기본 시작 카메라가 모래 둔덕에 가림 (§10.2 이후 그대로) |
| ATHN02D | ○ |  | PAND02C | ○ |  |
| ATHN03A | ○ |  | PAND02D | ○f | 안개 goprfog (gofogwet 컨텍스트 꺼짐) |
| ATHN03AA | ○ |  | PAND02DD | ○ |  |
| ATHN03C | △d | 기본 Start 칸 4 가 bvsky·bvcorner 끔 → 오른쪽 위 지움 색 (§13.4) | PAND02F | ○ |  |
| ATHN03D | ○f | 안개 gopcube3949 (칸 6: bvfogaa 켬·bvlower 끔) | PAND02G | ○ |  |
| ATHN03E | ○ |  | PAND02H | ○ |  |
| ATHN04A | ○ |  | PAND02I | ○ |  |
| ATHN04B | ○ |  | PAND03A | ○ | §6.1 유지 |
| ATHN04C | ○ |  | PAND03B | ○s | 영웅 모드 2 자기 그림자 [근사] |
| ATHN04D | ○ |  | PAND03C | ○ |  |
| ATHN05A | ○ |  | PAND03D | ○ |  |
| ATHN05AA | ○ |  | PAND03E | ○ |  |
| ATHN05B | ○ |  | PAND03F | ○ |  |
| ATHN05BB | ○ |  | PAND03G | ○ |  |
| ATHN05C | ○ |  | PAND03GG | N | 부속 WAD |
| ATHN05D | ○ |  | PAND03H | ○ |  |
| ATHN06A | ○ |  | PAND03I | ○ |  |
| ATHN06B | ○ |  | PAND03J | ○ |  |
| ATHN06C | ○ |  | PAND04AA | ○ |  |
| ATHN07 | ○ |  | PAND04C | ○ |  |
| ATHN07B | ○ | §9 해결 유지 | PAND04D | ○ |  |
| ATHN08A | ○ | 아레스 위치 = 루트 모션 | PAND04E | ○f | 안개 gofogger (칸 4: bvfogmain 켬·bvfogwater 끔) |
| ATHN08B | ○ |  | PAND04EE | ○ |  |
| ATHN08C | ○ | 아레스 위치 = 루트 모션 | PAND04F | ○ |  |
| ATRET01A | ○ |  | PAND04G | ○f | 같은 계열 다른 안개 |
| ATRET01B | ○ |  | PAND04H | ○ |  |
| ATRET01C | ○ |  | PAND04IB | ○ |  |
| DEST00 | ○ |  | PAND04J | ○ |  |
| DEST01 | ◎l | 조명 석상·영웅 0.8 배 (§13.3) | PAND05A | ○ |  |
| DEST02 | ○ |  | PAND05B | △d | camEleEnter 눈 y 21 고정·패닝 — 데이터대로 (§13.11 1) |
| DEST03 | ○ |  | SEWR01 | ○ |  |
| HDES01A | ○ |  | SEWR02 | ○ |  |
| HDES01B | ○ |  | | | |

### 13.6 테스트
`LightServerTest` +1 (7): VU 식 `LightRig.shade(n, M, A)` — 0.8 × 1.5 = 1.2 + 1/128, 자르기는 M 곱한 뒤, 더할 색; R_HERO0 `MAT_maiFskinh` M = 0.8 · A = 0, R_CNM `MAT_lambert2` A = 1. 엔진 테스트 368.

### 13.7 남은 것
- ~~PAND01B ← PAND01A 이음 도착의 카메라 (`Follow` ↔ 탐침 `ShldDrCam`) — 가시 칸이 화면을 비우는 원인.~~ → §13.8
- ~~원작 프레임 지움 색 (ATHN03C 칸 4 틈, 거울 뷰 바탕 §12.6)~~ (§13.4.1: 검정), 조명 경로 알파 qw78.w, ~~그림자 합성 (47 §7.4)~~ (47 §7.4.1: 초록 스텐실 + DATE − 38).

### 13.8 카메라 의심 5 건 정리 (도착 상태 · 이웃 Vis · 두 슬롯 Animator · 발판 시작)
스크린샷 (커밋 안 함): `/Users/obeomsu/.claude/jobs/6f65672f/tmp/cam4/` — 단건 `p01b_*`, `d0*_*`, `p05b_*`, `a_*`/`b_*`, 전수 `arr/` (도착 188) · `on/` (기본 시작 105).

**1. PAND01B ← PAND01A 이음 (-1028, 0, -1371)** [확실: 스크립트 데이터 / 근사: 재현 씨앗]
- 탐침 ↔ 엔진 차이: 탐침은 스크립트를 돌리지 않는다. `goshlddrcamnode` (RCM_ShldDrCam) 는 PAND01A `ShldDrCamnode-CreationSensor1` → `-CamOff` (Attr11_1 = 2) 로 꺼지고,
  켜는 `-CamOn` (#188) 은 대상 목록·바이트코드·문자열 어디에서도 불리지 않는다 (PAND01A/01B 전수) → **원작에서도 ShldDrCam 은 후보가 아니다**. 탐침의 ShldDrCam 이 틀린 쪽.
- 도착점은 어느 CMV 에도 없다 (동쪽 PAND01A AltCam3/AltCam43, 서쪽 PAND01B SAHallCam2 사이 문틀). 원작은 이음에서 카메라 관리자를 그대로 두고 (30 §7), 0x1EE8E0 은 후보가 0 이면
  현재 상태를 유지하므로 **걸어 들어오던 AltCam43 이 남는다**. 실제 이음은 `handoff()` 로 이미 그렇게 된다. `--arrive` 재현은 넘겨받을 상태가 없어 `Follow` 였다 →
  `CameraRig.seedApproach`: 도착점에 후보가 없으면 떠나온 레벨의 가장 가까운 CMV 후보 자리 (수평 고리 ≤ 512, 위 ≤ 1024 · 아래 ≤ 256) 에서 한 번 먼저 고른다 [근사: 하네스 전용].
- AltCam43 화면을 막던 벽은 `goaxealcwall` — PAND01A `AxeAlcWall-CamSensor1` 이 카메라 전환 이벤트 1037 이름이 AltCam25/3/43/2/44 면 Vis Visible 2 로 숨긴다.
  엔진은 **이웃 레벨 스크립트의 Vis Visible 을 버리고 있었다** → `SceneNav.Host.neighbourVisible` → `GameScene.otherHidden` → 다른 슬롯 `LevelScene.setRemoved` (원작은 두 슬롯 GO 가 같은 렌더 서버).
- 가시 칸: AltCam43 눈 (-936, 44, -1259) 은 PAND01A 칸이 bvalcove 를 켜 문 앞 홀이 보인다 (이전 `Follow` 눈은 어느 칸에도 들지 않아 거의 검정).
- 남는 것: 문 `goshieldoor` 는 방패를 끼워 열린 상태여야 한다 (아래 2). 열린 상태 재현 `-Dgow.arrive.open=goshieldoor` (`p01b_open.png`).

**2. DEST01 ↔ DEST02 이음 — 닫힌 문** [확실: 스크립트 데이터]
- 도착점 (2429, 516, 6496) 은 DEST01 `goenddoor` (2416, 539, 6432) 바로 뒤, `goloadcheckdsrt2` 안 — 위치는 맞다. 문은 `EndDoor-AnimOpen` (Animator "!0", 조건 LevelData:0 == 3 = 세이렌 셋) 으로 열린다.
- 엔진은 이음마다 두 레벨을 새로 읽고, 다른 슬롯은 정적 지오메트리만 그렸다 → 열린 문이 닫힌 자세로 돌아왔다 (실제 플레이에서도). 원작은 LoadCheck 가 현재 레벨 표시만 바꾸고 두 슬롯이 그대로 산다.
  - `GameScene.Resident` (레벨 이름, Vis 숨김, `Platforms.State` 목록) 를 이음/걷기 `Arrival` 로 두 슬롯 모두 넘긴다 → 떠난 레벨 = 다른 슬롯 (`neighbourPlatforms().restore`), 들어간 레벨 = 현재 (생성 스크립트 뒤 `restorePlatforms`·`hideInstance`).
  - 이웃 스크립트의 Animator 도 이웃 오브젝트를 움직인다 (`SceneNav.neighbourPlatforms`; ~~렌더 자세만 — 충돌은 이웃 정적 충돌 [근사]~~ → 이웃 CDV hull 도 이 레벨 발판과 같은 규칙으로 막고 받침, 이웃 Vis 충돌 끔·이음 넘기기 포함 — 42 §5). `LevelScene.renderNeighbour` 가 자세 인스턴스를 스키닝으로 그린다.
  - `Platforms.settleUnder` (Warp 도착 발판 맞추기) 가 맞지 않는 발판의 애니를 지우던 것을 원래 상태로 되돌리게 함 (넘겨받은 열린 문이 지워졌다).
- 검증: `-Dgow.arrive.open=goenddoor` 로 DEST02 ← DEST01 (`d02_open.png`), DEST01 ← DEST02 → 걷기로 DEST02 (넘겨받은 문 자세, `d01_open.png`) 모두 문 너머 크레토스가 보인다.
  재현 옵션 없는 `--arrive` 는 여전히 닫힌 문 (상태 없음, 데이터대로).

**3. PAND05B 기본 시작 — 엘리베이터** [확실: 데이터 + 엔진 버그]
- `startWad-CreationSensor1` → `elevatorGRPpand05B-AnimElevator` ("!0", 모드 0x8) 와 `startWad-start` (Idle, CMV 질의 끔, `camEleEnter`), 11.3 초 뒤 `-release`.
  엘리베이터 윗면은 t=0 에서 281 (= Start y 282), 11.33 초에 0 으로 내려간다 → **영웅은 꼭대기에서 타고 내려오는 것이 맞다** (Start 위치·발판 데이터 정상).
- 엔진: Start 아래 정적 바닥이 없어 `settleArrival` → `settleUnder` 가 이미 재생 중인 엘리베이터를 "Start 를 받치는 시각" (0.9 초) 에 **멈췄다** → 영웅이 y 259 에 떠 있고 카메라는 바닥만.
  수정: 재생 중인 발판이 지금 자세로 도착점을 받치면 그대로 둔다. 이제 영웅이 281 → 0 으로 내려온다 (`p05b_t060/t450/t660.png`).
- ~~남는 것 [미확인]~~ → **§13.11 1 (해독 경로 = 엔진, 데이터대로)**: `camEleEnter` (gostartwad 관절 카메라, 0x40000 고정 주시) 는 눈 (1081, 21, 1311) 고정, 관절 −Z 가 피치 +14° → −21° 로 내려가는데 엘리베이터는 눈보다 위에서 내려온다
  (11 초에 크레토스가 화면 왼쪽 위 끝). 관절 애니 축 규칙 (30 §6.2) 은 다른 레벨에서 맞았으므로 원작 화면 대조 전에는 손대지 않는다.

**4. 탐침에서 막힌 도착** — 분류
| 도착 | 엔진 화면 | 분류 |
|---|---|---|
| PAND03F / PAND03J ← PAND00C Warp (CoreTubeCam4) | 관 벽 | **데이터대로 (§13.9)** — 카메라 해독 그대로: 레일 두 극소 u 9.25 (비용 3.46) / u 14.5 (2.75), 0x400 접선 좌표계 요 −180±60 은 u 14.5 만 만족 → 관 바깥 눈. PAND02B 와 같은 종류 (§8.2) |
| PAND02A ← PAND00C Warp (Start CameraName Lvl2HallCam2) | `Follow` | 데이터: Lvl2HallCam2 는 PAND00C 카메라. 도착점 x 960 은 camFromWarp (x ≤ 958.6) 와 camWarpTunle (x ≥ 964.2) 사이 5.6 단위 틈. 탐침은 떠나온 레벨을 다른 슬롯에 두어 Lvl2HallCam2 를 찾았다 — Warp 후에도 떠난 레벨을 남기면 OLYMP02 ← OLYMP01 에 OLYMP01 입구·staircam 이 겹쳐 (같은 좌표 공간) 되돌렸다 → 엔진의 "Warp 는 다른 슬롯 없음" 유지, 탐침 쪽 차이 (§13.9: 원작도 떠난 레벨 컨텍스트를 끔) |
| HDES01E ← HDES01D 걷기 | 이전 `Follow` (벽) → **Dcam11** (기둥 위 크레토스) | 도착 상태: 떨어져 들어오는 길 — 바로 위 −1260 에 HDES01D Dcam11. 1 의 씨앗 (수직) 으로 해결 |
| ATRET01A ← HDES01E Warp (camFrmHades07) | 바로 HDES01E 로 되돌아감 → **§13.9 해결** (줄에 매달려 도착) | 도착 위치: Start (5095, 619, 991) 는 무덤 줄 `gorope1` 위, 영웅이 떨어져 `loadHDES01G` 영역 2 (Warp) 에 들어간다. 원작은 줄에 매달린 채 나오는 연출로 보인다 [추정] — 카메라 무관, 미해결 |
| OLYMP02 ← OLYMP01 Warp | HallCam1, 정상 | 탐침 차이 (위와 같이 떠난 레벨 충돌을 넣어 막힘) |
| OLYMP02 ← OLYMP01 걷기 (0, 50, −352) | OLYMP01 `goentrance` 가 가림 | 하네스 인공물: Warp 짝 두 레벨이 같은 좌표를 써서 바닥 겹침이 "걷기" 로 잡힌 것. 실제 경로는 Warp |

**5. PAND02B** — 1–4 와 공통 원인 없음 (레일 두 극소 §8.2 는 PAND03F 와 같은 종류로만 기록). 다시 보지 않음.

검증 (전수, 240 틱, `cam4/arr` · `cam4/on` ↔ `qa4/arr` · `qa4/on2`): 
- 기본 시작 105: 화소 변화 최대 8.6 % (PAND02G 적·파티클), 나머지 ≤ 7.3 % — 회귀 0. PAND05B 는 240 틱에 영웅이 내려오는 중 (화면 차이 작음).
- 도착 188: 씨앗이 걸린 이음/걷기 10 건 — PAND01B ← 01A (AltCam43, 해결), HDES01E ← 01D (Dcam11, 해결), HDES01A ← 01B (RampCam4), PAND01B ← 01BB (LowCam1),
  ATHN02A ← 02B (CrtYrdCam6), DEST01 ← 00 (EntryCam3, 영웅이 멀리), PAND03H ← 03I (camera14 — 이전 검정 → 수호자 등, 영웅은 가림), ATHN01B ← 01A (Boat1Cam5, 전후 모두 선창 속),
  PAND01D ← 01A (같음), HDES01D ← 01C (아래 1024 ClimbCam3 를 골라 나빠져 → 아래 탐색 ≤ 256 으로 줄여 이전 `Follow` 화면). 그 밖 ≤ 29 % (ATHN01F 연출·AI 잡음).
  PAND01B ← 01BB 는 이웃 PAND01BB 스크립트의 Vis·Animator 가 걸려 문 쪽 화면이 바뀜 (스크립트대로).
시선 탐침은 카메라 선택·풀기 코드를 바꾸지 않아 (씨앗은 `--arrive` 이음 전용) 그대로: 기본 시작 75/105, 도착 120/188. 시험 `ArrivalStateTest` 4 (씨앗 AltCam43, 이웃 1037 → goaxealcwall 숨김, 엘리베이터 시작, 문 상태 넘기기), `StartCameraTest` 7 통과. 엔진 테스트 372.

### 13.9 남은 도착 3 건 (두 극소 재확인 · ATRET01A 무덤 줄 · Warp 슬롯 정책)
스크린샷 (커밋 안 함): `/Users/obeomsu/.claude/jobs/6f65672f/tmp/cam5/` — `b_*` (수정 전), `a_atret*.png` (ATRET01A 도착), `w_*.png` (도착점이 진입 센서 안인 다른 Warp 도착).

**1. CoreTubeCam4 (PAND03F/03J ← PAND00C) · camFRMCLFFS (PAND02B 기본 시작) — 데이터대로** [확실: 디스어셈블]
- 이기는 극소를 바꿀 수 있는 것을 전부 다시 읽었다 (30 §7.2 끝): 0x1F20F0 표본 (매듭 × 4 + 1, `c.lt.s` 앞쪽 우선, 비용 함수 = t2 인자 0 → 플래그 규칙),
  각도 자르기는 비용 0x1F1648 **안** (자른 방향 투영), 눈 회전 자르기는 t 를 정한 뒤, 리셋 (+0x4C = 0) 이라 직전 정면 보너스 없음, 대상 T = 생물 +0xD0 (위치 행, 0x1E09C0) + 24
  (반경 1.6 끈 0x1F4380 — 떠나온 PAND00C 쪽으로 1.6 끌려도 u 9.25 비용은 3.46 → 3.9 로 오히려 나빠짐). **카메라 갱신 경로에 시선·충돌 질의가 없다** (광선 0x11E190 은 디버그 자유 카메라 0x1F0260 전용).
  CoreTubeCam4 플래그 0x6B5FE 의 0x2000 은 카메라 대상 덮어쓰기 중 갱신 생략, 0x8000·0x20000 은 카메라 코드가 읽지 않음.
- 그래서 원작도 u 14.5 (관 바깥, 접선 좌표계 요 57.5° → 120° 로 자른 투영 ≈ 141, 비용 2.75) 를 고르고 회전만 ±60° 로 잘라 영웅이 화면 밖이다 (u 9.25 는 범위 안이지만 |v| 140.5 → 비용 3.46).
  Start CameraName 도 같은 CoreTubeCam4 (도착점이 볼륨 hull 2 안) 라 요청이 결과를 바꾸지 않는다. PAND02B 는 기본 시작이 원작 경로가 아니다 (§7.5).

**2. ATRET01A ← HDES01E Warp — 무덤 줄 도착** [확실: 0x1E79D0 · 0x1C4FF8 · 0x1C5C20 · 0x1E0A18 · 0x1B3030 · 0x1A7FFC]
- 데이터: Start `WarpFrmhdes05` (5095, 619, 991, 모드 2, 대상 없음, CameraName camFrmHades07) 는 줄 `gorope1` (꼭대기 699.8, 길이 160) 한가운데이고, 되돌아가는 볼륨 `loadHDES01G`
  영역 1 (5095, 592, 987, r 192 — LoadForWarp HDES01E) · 영역 2 (5096, 570, 987, r 185 — Warp) 가 줄 전체를 덮는다. 무덤 밖으로 나오면 ExitSensor `frmHadesMpeg-pCube1-idle` → 동영상 a06Mp05.
  HDES01E 쪽은 rope3 를 올라가다 꼭대기 `warpAtRet01A` 구에서 Warp — **영웅은 줄 위에서 떠난다**.
- 원작 흐름: Warp_Jump (0x1B2E08) 는 영웅 상태를 지우지 않고 SetMatrix (영웅 vtable 0x2BBC78 +0x2C = **0x1E79D0**) 만 부른다. 0x1E79D0 = 0x1E1D78 (0x1E0A18 행렬) 뒤
  줄 위 (+0x174 0x400) 면 **0x1C4FF8(1)**: 줄 값 지우고 +0x174 = (… & ~0x7FF) | 0x8 (떨어짐), +0x290 (줄 핸들) = 0. 다음 프레임 공중 (0x4C) 줄 잡기 0x1C5C20 이
  잡기 점 반경 32 안의 gorope1 을 바로 잡는다 (놓은 줄 제외 0x3067E8 는 GetRope 0x243878 = 0 이라 없음). 별도 도착 스크립트·도착 애니는 없다.
- 볼륨: 영웅 스크립트 레코드는 +0x30 0x20 (0x1AC310) 이라 일반 점 질의 루프에서 빠지고, 이전 위치 → 지금 위치 **선분** (0x1A7FFC → 0x11E190, 콜백 0x1A8508 진입·이탈) 만 쓴다.
  순간이동 0x1E0A18 (영웅 +0x174 bit 31) → **0x1B3030** 이 0x29D90C = 1 → 그 프레임은 선분을 건너뛰고 이전 위치 = 도착점. 그래서 도착점을 덮은 loadHDES01G 가 곧바로 되돌리지 않는다
  (경계를 지날 때만 [추정: 0x11E190 은 경계 교차]). 전 레벨 Warp 도착 Start 중 진입 센서 안인 것 25 곳 (센서 36) — HDES01E fromAtRet01A ⊂ LoadCheckATRNA, PAND00D WarpFrm1BB ⊂ LoadCheckBB,
  ATHN04A WarpFrom5D ⊂ 엘리베이터 발판 PlayerEntry 처럼 도착 즉시 발동하면 말이 안 되는 것들이라 같은 규칙과 맞는다.
- 엔진 (이전: 새 장면이 도착점 볼륨을 진입으로 쳐서 LoadForWarp + Warp → 떨어지며 HDES01E 로 되돌아감):
  `GameScene` Warp 도착은 `placePlayer(…, inside = true)` (이음과 같이 이미 선 볼륨은 "안"), `HeroNavigator.warpArrived()` = 리스폰 잡기 대기 0.3 초 [근사] 없앰 → 첫 틱에 gorope1 (y 618) 에 매달림 (`a_atret.png`).
  거꾸로 HDES01E ← ATRET01A 도 rope3 (y 103) 를 잡는다. 진입 센서 안 Warp 도착 (`w_athn04a/w_hdes01e/w_pand00d`) 은 도착 즉시 LoadCheck·발판 발동이 없어짐.

**3. Warp 의 다른 슬롯 정책** [확실: 0x1B2CE0 · 0x1BC3A0 · 0x1BC110 · 0x1B0584 / 추정: 꺼진 컨텍스트의 렌더·충돌]
- Warp_Update 0x1B2CE0: 준비 (0x29D914) + 요청 (0x29D918) + 두 슬롯 로드 (+0x68 & 1) → **0x1BC3A0(새 슬롯)**: 떠나온 슬롯 레벨의 루트 컨텍스트 (+0x5C)
  가 켜져 있으면 0x1BE688 (하위 컨텍스트 +0x60 끔) + 0x1BEF10 (서버 vt+0x44 끔), 새 레벨 0x1BEEB8 (vt+0x34 켬), 0x1BE4C0 (현재 슬롯). 그 뒤 0x29D910/14/18 초기화.
  **떠나온 레벨은 메모리에 남지만 꺼진다** — 내려 놓는 것은 다음 LoadWad/LoadForWarp 의 0x1BC110 (그 슬롯 +0x68 & 4 면 0x1BBA20(1) 로 비우고 새로 로드).
- 꺼진 레벨: 스크립트 루프 (0x1A7D68 `컨텍스트 +4 & 4`) 가 건너뛴다 [확실]. ~~렌더·충돌·CMV 질의도 컨텍스트 서버를 따라 빠진다 [추정]~~ → 충돌·CMV 는 §13.11 2 (충돌 세계 활성 목록에서 빠짐 [확실: 0x2319D0 · 0x11DB40] / 컨텍스트 → GO → 컴포넌트 전파 [추정]) — 그래서 OLYMP02 ← OLYMP01 (같은 좌표 공간) 에
  OLYMP01 입구가 보이지 않는다. LoadForWarp(떠나온 레벨) 은 다시 읽지 않고 (0x1BC110 = 1) +0x68 & 4 면 바로 준비 (0x1B0670) — 무덤으로 떨어지면 곧장 HDES01E 로 돌아가는 설계와 맞다.
- 엔진: Warp 는 다른 슬롯 없이 시작하는 지금 규칙 유지 (꺼진 레벨과 화면·스크립트가 같다). PAND02A ← PAND00C 의 Lvl2HallCam2 (떠나온 레벨 카메라) 는 원작에서도 꺼진 레벨 것이라
  후보가 아니다 ~~[추정]~~ → §13.11 3 (Start CameraName 조회는 Start 컨텍스트 범위 0x1A8E10 [확실: 범위 세움 / 추정: 연결 범위 내용]) — 엔진 `Follow` 그대로.

테스트 `WarpArrivalTest` 2 (도착점 볼륨 = 안 → 되돌림 없음 / 대조로 진입 처리면 LoadForWarp HDES01E, Warp 도착 첫 틱 gorope1). 엔진 테스트 380.

### 13.10 도착 3 차 (prefer 전환표 · 짧은 사슬 줄 · Warp Start 대상 · Warp 되돌아옴 상태)
스크린샷 (커밋 안 함): `/Users/obeomsu/.claude/jobs/6f65672f/tmp/arr2/` — `p00d_b/p00d_a.png` (PAND00D 수정 전후), `h1e_b/h1e_a.png` (HDES01E), `arr/` (도착 188, 수정 후),
`head/` (같은 188, HEAD 빌드), `sheets/arr_0..9.png` (대조 시트), `start/` (Start 대상이 있는 Warp 도착).

**1. PAND00D ← PAND01BB Warp (WarpFrm1BB)** — 카메라 선택 [확실: 0x1EE8E0 / 0x1EECE4–0x1EED58]
- 도착점 (-1883, −220, 3171) 은 아래층 바닥 (데이터대로), 후보 Lv1TunCam3 · Lv1TunCam4 (둘 다 순위 2, 서로의 전환표 항목 +0x1C 가 켜짐). 엔진은 이것을 "prefer = 동순위 우선" 으로 읽어
  **매 프레임 둘이 번갈았다** (전환 1 초씩 섞임 → 벽 모서리만). 가시 칸·도착 위치·스크립트 문제 아님.
- 해독: 최선 = −1, 최선 순위 = INT_MIN 으로 활성 목록을 돈다. 상태 마스크로 빠지지 않은 카메라 c 에 대해 전환표에서 **현재 카메라 이름 (+0x808)** 항목을 찾아 +0x1C ≠ 0 이면
  c 의 순위 = 최선 순위일 때만 목록의 c 자리와 최선 자리를 맞바꾸고 최선 색인 = c 의 자리 (= 원래 최선 카메라), 다르면 (아직 최선이 없을 때 포함) 건너뜀. 아니면 순위 ≥ 최선이면 최선.
  → **그 표시가 있는 카메라는 현재 카메라에서 넘어오는 선택에서 이기지 못한다** (목록 순서만 바뀜). 마지막에 최선이 이번 후보 (0x306CF0) 에 있고 현재와 다르면 전환.
- 엔진 `CameraRig.select` 를 해독대로 (목록 자리 바꿈 포함). PAND00D 는 Lv1TunCam4 하나로 남아 크레토스가 보인다 (`p00d_a.png`). 시험 `StartCameraTest.preferTransitionDoesNotFlipFlop`.

**2. HDES01E ← ATRET01A Warp — rope3** [확실: 0x216DCC · 0x1C5DA4 · 0x1C6BB8 · 0x1C61CC · 0x1C65E4 · 0x2170BC]
- rope3 (54 m) 와 ATRET01A gorope1 은 SCR f3 = 1 (**짧은 사슬**, 모델 사슬 관절 2 개 = 매단 점 + 끝). 0x216DCC 가 줄 +0x374 = 4 → 잡을 때 0x1C5DA4 가 영웅 +0x174 를 0x10000 으로.
  줄 갱신은 0x10000 이면 흔들기 힘 구간 (0x1C6BC8–0x1C71B8: 잡을 때 0.25 초 미는 창 + ○ 흔들기 + 0x218470 매달림 힘) 을 통째로 건너뛰고 (0x1C6BB8), ○ 도 무시 (0x1C61CC),
  줄 위치 최소 0.5 m (0x1C65E4). 같은 줄: PAND01A gorope6, PAND01C gorope7, PAND04C gorope61, PAND04G 5 관절 줄들.
- 엔진은 f3 를 읽지 않아 잡을 때 미는 창 (500 × 붙은 거리 16 m × 2 × 남은 창) 을 무게 0 (붙으면 50/초로 늘어남) 인 끝 노드에 그대로 줘 크게 흔들렸고, 노드도 최소 3 개로 만들었다.
  영웅이 가져오는 속도는 없다 (잡기 0x1C5D54 는 속도를 줄에 넘기지 않음). 줄은 처음부터 쉬고, 영웅 무게는 붙은 뒤 늘어난다 — 원작도 같다.
- 수정: `NavRope.shortChain` (SCR f3), `HeroNavigator` 미는 창·흔들기 힘·○ 없음·최소 0.5 m, `RopeSim` 노드 = 사슬 관절 수 그대로 (최소 2). 도착 첫 틱에 rope3 에 매달려 가만히 있고
  Gcam6 이 크레토스를 비춘다 (`h1e_a.png`). 시험 `HeroNavigatorTest.shortChainRopeDoesNotSwing`, `WarpArrivalTest.warpStartTargetsFireAndShortChainRopes`.
- rope3 모델은 `rope3-CreationSensor1` → VisOFF, 동영상 h01Mp03 끝 이벤트에서 VisON — `--arrive` 재현에는 그 상태가 없어 안 보인다 (아래 4).

**3. Warp 도착 Start 의 대상 발동** [확실: Warp_Jump 0x1B2E08 끝 → 0x1A9C18(Start)]
- 0x1A9C18 은 엔티티 대상 목록 (+0x4A 개수, +0x4C 번호) 을 하나씩 0x1A9750 (발동) 으로 보낸다. 엔진은 Start 의 대상을 발동하지 않았다.
  데이터: 대상이 있는 Start 는 Warp 도착 5 곳 — PAND04AA WarpFrmCore (엘리베이터 AnimLiftUp · IdleOff), ATHN02A ShipTrans-Anim-WarpFrom1F (배 Animator · 소리 · CDsOn 10 초 · 체크포인트 11 초 ·
  PortCam 0.5 초), PAND00D WarpFrom2i (Load · 체크포인트), ATHN08C WarpFrm08B (음악), ATHN01F WarpfromD (Load).
- 엔진 `ScriptWorld.fireStart` (GameScene Warp 도착, `placePlayer` 뒤).
- ~~남는 것: PAND04AA 엘리베이터 바닥 이름 규칙~~ · ~~ATHN02A 배~~ → 아래 6.

**4. Warp 로 바로 되돌아올 때의 레벨 상태** [확실: 0x1BC3A0 · 0x1BC110 (§13.9) / 근사: 넘기는 것은 화면 상태만]
- 원작은 Warp 로 떠나온 레벨을 꺼진 채 메모리에 두고, 곧바로 그 레벨로 LoadForWarp/Warp 하면 다시 읽지 않는다 → 떠날 때의 Vis·Animator 상태가 남는다.
  엔진은 Warp 마다 새로 읽어 HDES01E rope3 (VisON 뒤) · OLYMP01 goentrance (OpenGates 로 연 문 — 도착점이 볼륨 안이라 다시 열리지 않음) 가 처음 상태로 돌아왔다.
- 엔진: `GameSession.warpLeft` = Warp 로 떠난 레벨의 `Resident` (숨김 집합 · 발판 상태), 바로 그 레벨로 Warp 하면 생성 스크립트 뒤 숨김 집합을 그대로 (`setRemoved`)·발판 복원.
  그 사이 이음·LoadWad 가 있으면 잊는다. `--arrive` 재현에는 넘길 상태가 없다 (rope3 안 보임, OLYMP01 문 닫힘 = 하네스).

**5. 도착 188 전수 (240 틱, `arr/` ↔ `head/`)**
- HEAD 대비 화소 변화 > 10 %: HDES01E ← ATRET01A (87 %, 위 2), PAND00D ← PAND01BB (69 %, 위 1), ATHN01C ← ATHN01D (32 %, 비 효과 잡음), ATRET01A ← HDES01E (26 %, gorope1 짧은 사슬 —
  흔들리지 않고 그대로 매달림), ATHN01F 둘 (연출 타이밍), PAND00D ← PAND02I (18 %, 같은 카메라·적 위치). 그 밖 ≤ 6 % — 회귀 0.
- 크레토스가 안 보이는 도착 (눈으로, 대조 시트) 과 분류:

| 도착 | 원인 | 분류 |
|---|---|---|
| PAND00D ← PAND01BB | prefer 번갈기 | **해결** (1) |
| HDES01E ← ATRET01A | rope3 흔들림 → Gcam6 벽 | **해결** (2) |
| PAND04AA ← PAND00C | Start 대상 미발동 + 엘리베이터 바닥 판정 | 대상 **해결** (3), 발판 판정 **해결** (6) |
| OLYMP01 ← OLYMP02 Warp | goentrance 문 닫힘 (도착점이 OpenGates 안) | 실제 플레이 **해결** (4), 하네스는 상태 없음 |
| ATHN02A ← ATHN01F | 정박한 배 goshipgroup 갑판 충돌 없음 | **해결** (6) |
| PAND03F/03J ← PAND00C, PAND02A ← PAND00C | CoreTubeCam4 / Follow | 데이터대로 (§13.9) |
| DEST01 ↔ DEST02, OLYMP02 ← OLYMP01 걷기, PAND03H ← PAND03I, PAND05B ← PAND05A | 닫힌 문 · 겹친 좌표 · 수호자 가림 · camEleEnter | 기존 분류 (§13.8) |
| ATHN01D ← ATHN01E, ATHN01B ← ATHN01A, ATHN01E ← ATHN01D (짚라인 끝 Follow), PAND01C ← PAND01B (도착 즉시 PAND01B 로 걷기 → Follow) | 이음 도착점이 돛대·상자 뒤 / 넘겨받을 카메라 없음 | 하네스 (이음 재현 위치·카메라 상태) |
| DEST00 ↔ DEST01, ATHN03E ← DEST00, ATHN06A ← ATHN06B (camNewWarp 계단 아래 고정, 영웅 400 위 어두운 문) | 멀리 작게 / 어두움 | 데이터대로 (카메라 설계) |
| ATHN06C ← ATHN07, ATHN07 ← ATHN06C, ATHN07B ← OLYMP01 이음, PAND02AA ↔ PAND02B, PAND03A ← PAND03B, PAND03H ← PAND03G, PAND04J ← PAND05A | 이음 도착점 바로 앞 문·벽 (CMV 카메라, 시선 질의 없음 §13.9) | 데이터대로 [추정: 원작은 걸어 들어오는 카메라] |

시선 탐침 (같은 탐침, 60 프레임 · 정적 충돌): 기본 시작 변화 없음, 도착 +1 (PAND00D ← PAND01BB). 엔진 테스트 394.

**6. CDV 발판 밟기 판정 · ATHN02A 배 도착** [확실: 0x1E3298 · 0x1E2100 · 0x139218 · 0x1D63E0 / 추정: GO 생성 경로 0x11D498]
스크린샷 (커밋 안 함): `/Users/obeomsu/.claude/jobs/6f65672f/tmp/plat/` — `p04aa_a240.png` (엘리베이터 위), `a02a_b20.png` (수정 전: 배 옆으로 낙하),
`a02a_a20/90/400.png` (수정 후: 갑판, 0.5 초 뒤 PortCam), 전 레벨 목록 `platforms_old_new.tsv` · `static_cdv_bodies.txt`.
- **CDV 재질 엔트리 = 시트 표면 레코드** (이름 24B + 데이터 40B, 스키마는 전 시트 공통 배치): +0x18 bitfield0 (Ground…NoCSMCollision), +0x1C bitfield1,
  +0x28 doubleSided, +0x2C SheetFX, +0x30 PartType. 평면마다 재질 번호 (BallHull off[9]) 가 따로 있다.
- 충돌 질의 0x139218 (BallHull vtable 0x2BF3A0) 은 맞은 평면의 재질 (인스턴스 +0x70 = off[9] → +0x4C + 번호 × +0x48) bitfield0 (오프셋 [0x29C97C] = 스키마 "bitfield0")
  을 **질의 제외 마스크 0x3309F4** 와 AND — 0 이 아니면 그 면을 버리고 다음 후보 (0x1397A4). 시트 면 (0x16C3B8) 도 같은 검사.
- 영웅 질의의 제외 마스크: 바닥 0x1E3298 = 호출 마스크 | 0x1010042 (Water·Death·AIBlock·GeneralGuide) | (영웅 +0x258 & 0x4300000).
  (정정, 38 §18.4: 몸 0x1E2100 의 고정 부분은 0x10042 — GeneralGuide 없음.)
  영웅 +0x258 = 0x500000 (Player 생성 0x1D63E0) → NoPlayerCollision. 바닥 호출 마스크 0x8020000 (0x1C58D8 · 0x1CA1D8 …) = ClimbGuide · CombatGuide.
  → **이름 규칙 폐기**: 바닥 = 수직 선분이 들어가는 평면 재질이 제외 마스크에 안 걸림. 바닥 속성도 그 재질 (Slide·Narrow 발판은 그대로 미끄럼·균형).
- 전 레벨 Animator CDV 발판 253 개 (윗면 법선 y > 0.7 인 hull 기준, `platforms_old_new.tsv`): 바뀐 것 144 — 늘어남 140 (전부 0 → 있음 121: PAND04F goelevator,
  PAND02C gocagelift2 · gocageelevator, 문·DMC 결계 윗면 …, 일부 → 전부: PAND04AA goelevatorlift 2/3 → 3/3, PAND02I goplat1–4 4/8 → 8/8, PAND02H 칼날 3/9 → 9/9,
  PAND00D 분쇄기 2/8 → 8/8 …), 줄어듦 4 (ATHN08B 세 벽 GeneralGuide 윗면, PAND04G goblocktranslatebg 의 NoPlayerCollision hull). ARENA27 goriseplat 은 전후 같음
  (groundR hull 이 받침, lambert hull 8 개는 NoPlayerCollision). 불 함정 DamageMat 은 NoPushPullCollision 만이라 영웅에게는 단단한 면.
- 벽 (구 밀어내기): hull 의 모든 평면이 제외 마스크면 밀지 않는다. 단 GeneralGuide 만은 남긴다 [확실: 38 §18.4 — 몸 질의 0x1E2100 의 마스크는 0x10042 | 호출 (0x20000 · 0x8000000 · 0x20000000)
  로 GeneralGuide 를 빼지 않는다. ATHN01B goplayerblock "guideMat", ATHN01E 꼬리 막이 = 보이지 않는 막이 벽]. 충돌 끔 (Vis Attr11_0 = 2) 인 GO 는 바닥도 아니다.
- **ATHN02A ← ATHN01F**: Warp_Jump 0x1B2E08 은 Start 월드 행렬 (0x1A9CD0 = 엔티티 × goshiptrans 관절 1) 로 SetMatrix 만 한다 — 배에 부모로 붙이지 않는다.
  goshiptrans 애니 (10.4 초) 는 ~~관절 이동이 없고~~ → 타입 10 채널로 관절 1 "Anim" 을 Z −480 옮긴다 (배 입항, goshipgroup 은 GO 부모 링크로 따라감 — 30 §6.6) CDV 는 생성 때 끔 (CDsOff) → 10 초 뒤 CDsOn. 도착점 바로 아래 (−623.8, 도착점 −624.1) 는
  **정박한 배 goshipgroup** (Animator 없는 CDV GO, 사다리·아테나 석상 Mime) 의 갑판이다. 엔진은 Animator·R1 오브젝트의 CDV 만 충돌에 넣어 −800 수로 바닥으로 떨어졌다.
  원작은 GO 를 만들 때 CDV 리소스마다 BallHull 인스턴스를 만들어 충돌 세계 0x29C978 에 올린다 (리소스 vtable 0x2BF75C → 0x11D498 → 0x138E70 → 0x138FD0) [추정: 경로].
  → `Platforms.of` 가 **비헤이비어 없는 CDV GO 151 개** (전 레벨, `static_cdv_bodies.txt`: 정박한 배, 보이지 않는 벽·되돌아감 막이, HDES01C 죽음 굴림대 …) 도 정지 몸체로 넣는다
  (시작 act 가 반복이면 렌더의 반복 재생 배치처럼 돌린다, 도착 자세 맞추기 대상 아님). 비헤이비어 GO (깨지는 물건·상자·밀기·크랭크) 는 각 시스템이 맡는다.
- 0.5 초 PortCam: Start 대상 0x1A9C18 이 발동하는 `ShipTrans-SetCamPortCam` (Delay 0.5) — 데이터대로 원작 동작. 영웅이 갑판에 서면서 PortCam 이 크레토스·석상·부두를 비춘다 (`a02a_a90.png`).
- 순회 검증: 강제 뒤에만 나온 전환 0 · 숨긴 채 쓴 상호작용 0 그대로, 보고서는 HEAD 와 같음 (개선·회귀 없음 — `--tests` 단독 실행 비교.
  전체 묶음 실행에서는 ATHN02D 헤드리스 메두사 단계 기록이 테스트 순서에 따라 바뀐다: HEAD 도 같음, 이번 변경과 무관).
- 시험: `WarpArrivalTest.pand04aaArrivalRidesElevator` (−239 가 아니라 엘리베이터, AnimLiftUp 끝 y ≈ 45), `cdvMaterialEntriesAreSurfaceRecords`,
  `athn02aArrivalStandsOnDockedShipDeck`. 엔진 테스트 397.

### 13.11 카메라 남은 것 정리 (camEleEnter · 꺼진 컨텍스트 충돌·CMV · Warp CameraName 범위 · BMI 460)
스크린샷 (커밋 안 함): `/Users/obeomsu/.claude/jobs/6f65672f/tmp/cam5/` — `p05b_b_30/150/300/600.png` (PAND05B 기본 시작, 변경 없음),
`w_p02a_b.png` (PAND02A ← PAND00C Warp, 현재 엔진 = 해독대로), `w_p02a_a.png` (시험해 본 "떠나온 레벨 카메라 이름 조회" — 3 의 해독으로 기각, 3000 단위 밖 눈이라 검정).

**1. PAND05B `camEleEnter` 패닝 — 엔진 = 원작 경로, 데이터대로** [확실: 0x1F3768 · 0x1303D0 · 0x136990 · 0x1F3A38/0x1F3B40 · 0x13EC10 · 0x1004B8 · 0x131038 / 미확인: 원작 화면]
- 데이터: `gostartwad` (MDL 없음, (869.9, 520.7, 944.4) = 엘리베이터 중심 위) 관절 0 `startWad` · 관절 1 `camEleEnter` (오일러, 부모 기준 이동 (210.7, −499.9, 367.1),
  아이들 (656, 2248) = 바인드 행렬과 일치). `ANM_startwad` act 1 개 11.33 초 (엘리베이터 애니와 같은 길이), 회전 트랙 2 개 모두 스트림 4·5 (관절 1 x·y),
  0–30 프레임 정지 → 341 프레임까지 (656, 2249) → (−959, 0) = **피치 +14.4° → −21.1°, 요 49.4° → 0°** (눈은 관절 1 원점이라 고정 (1080.6, 20.8, 1311.5)).
  `camEleStart` (gocamgrpstart CMV, 순위 30, Warp 도착 WarpFrom05A 의 CameraName) 는 같은 눈·같은 방향의 정지 카메라 = camEleEnter 0 프레임.
- 갱신 경로: 0x1F3768 은 0x100 없고 0x40000 이면 주시점 T = 카메라 GO 월드 행렬 × (C0..C8 ×16 = (0, 0, −567.8)), 그 행렬을 상태 +0..+0x30 에 복사하고 0x1F11A0.
  카메라 GO 월드 (0x1303D0, 관절 번호 +0x60 ≥ 0) = CAM 로컬 (+0x20, 단위) × 부모 애니 인스턴스 관절 행렬 ([+0x8C] + j·64, 0x136990 이 프레임마다 루트 = 포즈 × GO 월드,
  자식 = 0x138108). 오일러 Rx·Ry (05 문서 §1.4) 가 바인드 행렬과 일치 — 회전은 관절 제자리, 이동은 회전되지 않는다 (엔진 `CamAnim.world` 와 같은 곱).
- 애니 재생: 카메라 노드 리셋 0x13EC10 이 부모에 애니 컨트롤러가 있고 +0x60 ≥ 0 이면 카메라 +0x104 = 1. 활성화 0x1F3A38 → 0x1F3B40: +0x104 ≠ 0 이고 컨트롤러 +4 & 4 가 아니며
  CAM 플래그 0x1000 (0x414FE) 이면 0x1004B8 (채널마다 vt+0x1C 다시 시작) + 0x131038 (부모 GO 의 꺼진 컴포넌트 vt+0x34 켬) → **SwitchCamera 순간부터 0 초**.
  카메라 GO 는 전 WAD 가 MDL 없는 ANM 오브젝트이고 Animator 엔티티 없이 이 경로로만 돈다 (히드라 grabLeftCam 등 같음).
- 결론: 엔진 (`CameraRig.CamAnim`, 0x1000 재시작, 고정 주시) 과 해독이 같다. 0.5 초에는 엘리베이터 판 (폭 ≈ 310) 아래에서 올려다보고, 1–3 초에 아래·오른쪽으로 돌아
  끝에는 −z 바닥을 내려다본다 — 영웅 (요 37°, 높이 24–305) 은 2 초 뒤 화면 밖. 원작 화면 대조 전에는 **데이터대로** (스크립트 0x9 끔 → 11.3 초 `-release` 까지 유지).
  시험 `CameraRigTest.eleEnterJointCameraPans` (눈 고정 ±0.5, 활성화 애니 0 초, 피치 14.4° → −21.1°, 끝 요 0°).
- **정정 (2026-10-24, §16 · 05 §2.8.1)**: "눈 고정" 은 ANM 파서 착시였다 — `ANM_startwad` 는 데이터 타입 [0(p2 3)] 하나라 act 디스크립터 1 = 관절 이동인데 예전 파서는
  디스크립터를 1 개만 읽었다. 원작 배치로는 눈이 1–3.5 초에 엘리베이터 판 높이 (y ≈ 180) 로 올라 가운데 x 869.9 쪽으로 가고, 내려오는 엘리베이터를 따라 (870, 79, 1227) 까지
  내려간다 — 영웅이 2 초 뒤 화면 밖으로 나가던 것이 풀린다 [확실: 코드 0x100EA8 + 데이터 / 미확인: 원작 화면]. 시험은 새 값 (최고 y 175–185, 끝 눈) 으로.

**2. 꺼진 컨텍스트의 충돌·CMV** [확실: 충돌 세계 서버 vtable 0x2BF5E0 의 vt+0x34 0x2316D0 / vt+0x44 0x2319D0, 광역 재구성 0x11DB40 → 0x11D560, GO 컴포넌트 끄기 0x1310D0 /
추정: 레벨 루트 컨텍스트 → GO → 컴포넌트 전파]
- 충돌 세계 0x29C978 (생성 0x11D648, 서버 종류 0x11) 의 **켬 0x2316D0** 은 항목 (+8 고리) 을 world +0x24 목록에 잇고 개수 +0x2C, 항목 +4 |= 4.
  **끔 0x2319D0** 은 그 목록에서 빼고 +4 &= ~5. 질의 (0x11E090 / 0x11EB68 / 0x11ED88) 는 dirty (+0x1058, 리소스 핸들러 0x11D530/0x11D548 가 세움) 면
  0x11DB40 이 **world +0x24 목록만** 돌아 (각 항목의 인스턴스 목록 +0x24, 0x11D560 → 0x175580 범주 = GO +0xF4) 광역 표를 다시 만든다 — 구 함수 0x13A998 는 활성 검사 없이 항상 1.
  → 꺼진 CDV·CMV (같은 BallHull 서버) 는 모든 충돌 질의·카메라 볼륨 질의 (0x1EE8E0: 점 질의 0x11E5E8 + 이전 T → T 선분 0x11E190, 질의 0x3309F0 범주·면 마스크 0) 에서 빠진다 [확실].
- GO 끄기는 0x1310D0 이 그 GO 의 켜진 컴포넌트마다 서버 vt+0x44 를 부른다 (0x131038 은 반대로 vt+0x34) [확실]. Warp 0x1BC3A0 은 떠난 레벨 루트 컨텍스트 (+0x5C) 를
  0x1BE688 (하위 +0x60) · 0x1BEF10 으로 끈다 — 컨텍스트 항목은 GO 서버 (종류 1, vtable 0x2C1BD0: vt+0x44 = 0x239B88 → 항목 핸들러 vt+0x44) 소속이라
  GO·컴포넌트로 전파된다고 본다 [추정: 핸들러 vt+0x44 본문 미추적]. 스크립트 루프는 컨텍스트 +4 & 4 를 직접 본다 (0x1A7D68, §13.9).
- **레벨 안 가시 칸 컨텍스트 (CXT_bv…)** 는 끄지 않는다: 0x152EC8 → 0x16D6F8 은 렌더 항목 (MDL/PRT/0x1C/SHG) +0x48 의 뷰 비트만 바꾸고 vt+0x44 를 부르지 않는다 (§11.1) [확실]
  → 숨은 칸의 충돌·CMV 는 그대로 질의된다. 엔진도 가시 칸은 그리기만 거르고 (`LevelScene.updateContextVisibility`) 충돌·`CameraRig` 는 건드리지 않는다 — 맞음.
- 엔진: Warp 는 다른 슬롯 없이 시작 (떠난 레벨 충돌·CMV 없음) — 해독과 같다. 변경 없음.

**3. Warp 도착 Start CameraName 은 Start 컨텍스트 범위에서만 찾는다** [확실: 0x1A8E10 범위 세움·내림, 0x17A7B8 → 0x186990 / 추정: 범위의 연결 목록 내용]
- 데이터 (전 WAD Start 중 CameraName 이 자기 레벨에 없는 8 개): PAND02A WarpFromHub `Lvl2HallCam2`, PAND00D WarpFrm1BB `ConHallCam4` 만 떠나온 레벨 (PlayerName) 카메라.
  둘 다 떠나온 레벨이 Warp 뒤 꺼진 채 상주하므로 (0x1BC3A0) 전역 이름 조회라면 찾힌다 — 시험 삼아 엔진에서 찾게 했더니 PAND02A 는 3000 단위 밖 PAND00C 레일 눈 (−1169, 523, 1697) 에서
  도착점 (960, −364, −295) 을 보는 검정 화면 (`w_p02a_a.png`).
- 해독: 0x1A8E10 (Warp_Jump·Goto 의 CameraName) 은 Start GO (+0x54) 의 GO 핸들러 +0x88 = **그 GO 의 컨텍스트**를 컨텍스트 서버 vt+0x80 으로 세운 뒤 SwitchCamera 0x1EE658
  → 0x17A7B8 을 부르고 [0x330134] (서버 종류 0x1B) vt+0x88 로 내린다. 0x17A7B8 은 전역 레지스트리가 범위 모드 (+0 ≠ 0) 이고 [0x330134] vt+0x94 (현재 범위) 가 있으면
  **0x186990(범위, 이름)** = 그 범위 레지스트리 (+0x4C) → 연결 범위 ≤ 6 (+0x50..) 재귀 → 대체 레지스트리 0x175AA8. → 도착 레벨의 Start 는 도착 레벨 범위에서 찾고 떠나온 레벨 카메라는
  못 찾는다 → 요청 없음 → 볼륨 선택 그대로 (PAND02A 는 볼륨 틈이라 Follow, PAND00D 는 Lv1TunCam4).
- 엔진은 Warp 에 다른 슬롯이 없어 이미 같은 결과 — 변경 없음 (시험한 이름 조회는 되돌림). ATHN02D magicPlace `Room2Cam3` (숙주 ATHN02C) 같은 숙주·부속 레벨은
  엔진이 다른 슬롯에서 찾는다 (범위 연결 +0x50 이 숙주를 포함한다고 봄 [추정]). 시험 `StartCameraTest.warpStartCameraNameScopedToArrivalLevel`.

**4. 그 밖 §13 카메라 [근사]/[추정] 재검토** — 싸게 닫을 수 있는 것은 위 셋 (§13.9 3 의 Lvl2HallCam2 [추정] → 3, "렌더·충돌·CMV 질의도 빠진다 [추정]" → 2, camEleEnter → 1).
남은 카메라 [근사] 는 해독 비용이 크다: 리셋 첫 머리 데드존 (§10, 30 §8.2), `seedApproach` (하네스 전용), 원작 Follow 0x337B68, Hermite 속도항.

**5. BMI 발판 잡아 던지기 460 동기** → 37 문서 §11.6 (BMI 205 / 영웅 002 의 460 쌍, BMI 이벤트 19 창 [0–0.4] 끌기). 엔진 테스트 418, 순회 보고서 같음.

## 14. QA vs PS2 영상 — ATHN02C 첫 발리스타 길 (영상 3126–3406 초, 2026-10-24)

메두사·미노타우로스 방 (ATHN02D) 남쪽 문이 열린 뒤부터 복도 사다리 위 싸움까지. 엔진은 처음부터 끝까지 한 실행으로 (`--game ATHN02D --arrive ATHN02C`, 쉬움,
봇 + `gow.auto.route` §14.6, 결정적 — r8–r13 여섯 번 같은 시각). 영상 시트 `v3126_3406` (스크래치, 커밋 안 함), 엔진 증거 `work/verify/athn02c_route/` (§14.7).

### 14.1 원작 길 · 스크립트 [확실: 스크립트·데이터 / 영상 대조는 섬네일]
| 영상 | 원작 (스크립트) | 엔진 (게임 시각) |
|---|---|---|
| 3126 | ATHN02D 9 번째 EventSmash1 → FinalCamOn, 문 얼굴 MAtalkingHead01 Talk02 + 재질 Animator 넷 ("$M01AphroFront", "$SubScrollTex1", "$doorSub", "$M01DoorFade1", 지연 0.5) 이 6–7.5 초에 p28 → 0, MAcdHullOff, DMCWall-group669 VisOff | FinalCamOn 126.3, 재질 시계 126.8 → 문 페이드 132.8–134.3, DMCWall 135.3 초 (37 §5.3), `01`→`04` |
| 3142–3208 | 통로 (LoadCheckD) → BalistaEnemSpawner: 해골병 SkellyLevel2 둘 (템플릿 22, 리스폰) | 136–177 초 싸움, `05` |
| 3225–3258 | 사다리 굴 (x −808, y 955 → 1216) | `up` 178–186 초, `06` |
| 3274 · 3324 · 3340 | 회전판 레버 `gogunlever` (Push 012 "newLeever" Type 5, 과제 140): 1023 → LevelData1 = 100 → `LazySueGroup-AniFor` (모드 8) 3 초 → Marker320 이 GunMidZone (LevelData3) → `AniPause` (모드 0x12, 시작 = LevelData6 = 3) → 3.5 초 뒤 `Gunlever-EventTmid` 1015 (레버 되감기) | 레버 190.8, 정지 193.8 초, `07` |
| 3291 | 세이브 제단 Creator `SaveGameZone` "savepoint" (−424, 1216, 1489) → "제우스께서 … 저장하시겠습니까?" → 예 → 메모리카드 덮어쓰기 질문 (영상) | 첫 질문까지 `14` (봇이 저장 메뉴에 답하지 못해 메모리카드 화면은 안 봄) |
| 3307 | 발리스타 크랭크 `gobiggun1crank` (Push 202 "AthnGun" Type 2 대포, 과제 320): R2 누른 채 감기 → 99 % → 차기 → 100 % → `EvtTaskProgress100` → `EvtArrow` 1020 (Creator 200 = ArrowEmitter/200 "athensgun" → Arrow/099: Velocity 60 × 16 = 960/초, Damage 10000, 중력 없음, Aim Joint "arrowjoint") + 장전 카메라 ReloadCntrCam 6.5 초 + 1015 되감기 | 감기 198.8–203.1, 발사 203.1, 문 파괴 203.4 초, `08` `09` |
| 3357–3373 | breakableDoor01 (Brkb 950, HP 10000) 파괴 → Marker27 → DestructionSensor1 → LevelData14. 복도 끝 문장 돌문 = LiftDoor1Grp (IO_TandF, 과제 2, 손잡이는 동쪽) | `10` (206 초, 발리스타 카메라에서 같은 돌문) |
| 3390 | door1 바로 동쪽 북벽 사다리 (x −176..−144, y 1216 → 1361, z 1739) → 위층 방 (ATHN02B 바닥과 겹침, LoadCheckB5) | 223–229 초, `11` |
| 3406–3456 | ATHN02B `ClimbwallSpawner1` → `ClimbWallEnemies1` 해골병 셋 (템플릿 23, 벽 타고 오르기 500/523) | 229 초부터 (§14.5 수정 뒤), `12` `13` |

그 뒤 (엔진으로 몰지 않음, 스크립트 읽기 [확실: 데이터] / 원작 화면 미대조): 돌문 LiftDoor1 을 동쪽에서 R2 연타로 올리면 LevelData13 = 진행률 (100 이면 DrsOpenCams·
HallDrOpenCams). door1 이 부서진 뒤 가운데 자리에서 다시 쏘면 — LevelData13 == 0 && LevelData21 == 0 → `HarpoonStoneDoor` VisOn + AniFor (작살이 돌문에 박힘,
MainDoorCam), LevelData21 > 0 (EnemyBalistaZone 에 든 그룹 98 적 수) → 적을 쏨, LevelData13 == 100 → LastDoorCam → breakableDoor02 (Marker28) → LevelData15 →
HallwayMinos 미노타우로스 넷·doorsGroup·음악. 끝 자리 (남쪽) 에서 쏘면 BreakSecret (Marker321) → BrokenWall.

### 14.2 엔진 결함과 수정
1. **jump_if (0x0F) = 거짓이면 점프** [확실: 0x16F570 — 0x16F5A4 `bne v0, zero` 가 참이면 건너뛰고 거짓일 때만 pc += 오프셋]. 엔진은 참이면 점프였다 —
   77 개 핸들러 (29 레벨). 여기서는 `LazySueGroup-AniPause` 슬롯 3·4 (다음 멈춤 자리 LevelData6 = 0 / 3 / 6) 와 ATHN02D MedusaRmEnemies 지연 (처음 7 초, 리스폰 1 / 2 / 1.25 초 — 앞 판은 거꾸로, 37 §5.3).
2. **Animator 모드 0x12 = 그 시간에 세움** [확실: 0x1AB5E8 → 0x1AB394] — 0x2 (반복) 로 읽어 회전판이 계속 돌았다 (42 §3).
3. **PushPull 종류 = TWK Type 바이트** [확실: 0x212398 · 0x2100E0] — 이름 규칙이라 "AthnGun" (Type 2) 이 밀기 상자였다 (38 §3.2, 11 개). Type 2 대포 크랭크:
   끝에서 99 % (길이 − 0.01, 0x212AD0) → 차기 (+0x29A 비트 2) 가 발사 → 100 % → 1015 되감기 (42 §5) [근사: 차기 시점 = ANIStartKickBox 길이].
4. **오브젝트 이벤트 1015 / 1020** [확실: 0x2100E0 / 0x267698 → 0x231590 → 0x222710]: 1015 = R2 오브젝트 리셋 (되감기), 1020 = GO 의 Creator 비헤이비어 (ArrowEmitter)
   발사 → `ArrowShots` (Velocity·Damage·Gravity·Aim Joint 원작 값, 맞음 = 이전→현재 선분 vs 부서지는 물체 구·적·정적·움직이는 CDV [근사]).
5. **DestructionSensor Filter 는 모든 모드** [확실: 0x1AE1D0] — EnemyBalistaZone 센서 (모드 4, Filter 98) 가 Marker27 에도 발동해 LevelData21 = −1 → 작살 석문 조건이 영영 거짓 (13 §3.2).
6. **부모 링크 손잡이**: 크랭크·발리스타는 회전판 "Anim" 관절의 자식 GO — 손잡이 관절을 매 틱 자세로 다시 (`Interactables.refreshHandles`, 원작 0x1C3918 은 매 질의 관절 행렬) [확실: 데이터].
7. **재질 Animator "$"** (§14.3) [확실: 0x1AB0B8 '$' 갈래].
8. **두 슬롯 현재 레벨** (§14.4) · **이웃 SpawnEnemy** (§14.5) [근사: 엔진 구조].

### 14.3 남쪽 문 재질 페이드 (영상 3126 의 열린 통로)
엔진은 Animator 슬롯 0 "$이름" 을 무시해 미노타우로스 뒤에도 문이 감산 막 `MAT_doorSub` (색 2 × p28 0.5, 텍스처 없음) 으로 통로를 검게 덮었다 (`01`, 137 초 — 영웅이 검은
막을 지나감). 원작은 "MAT_" + 이름 재질 인스턴스의 애니 제어기를 관절 애니 규칙으로 튼다 (42 §3). 문 재질 ANM: doorSub · M01AphroFront · SubScrollTex1 = act 0x42 (스크립트가
틀 때까지 시간 0), M01DoorFade1 = 0x41 (불꽃 UV 반복), 넷 다 7.667 초, hdr8 (p28) 트랙이 키 180–225 (6–7.5 초) 에 0.5 / 0.9 → 0. 엔진 `Platforms.MatClock` → 렌더가
그 재질만 시계 시간 (정지 인스턴스 `Renderer.setMaterialTimes`, 자세 GO `AnimatedModel.matTimes`), 이음 넘기기 포함. 결과: 132 초 얼굴 (`02`) → 134.5 초 통로 보임 (`03`)
→ 영상 3126 과 같은 회색 통로·벽 횃불 (`04`). `ScriptMaterialAnimTest`.

### 14.4 두 슬롯 — 엔진의 현재 레벨 [근사: 엔진 구조]
원작은 두 슬롯 레벨의 스크립트·GO 가 모두 살아 있고 LoadCheck 는 "현재 레벨 이름" 만 바꾼다 (0x1BCA10, 36 §2). 엔진은 현재 레벨의 R2 오브젝트·Creator (세이브 제단·상자) 만 다룬다.
- **덧붙는 레벨 → 숙주**: ATHN02D (자기 바닥 없음) 가 현재인 채 남쪽 출구를 나가면 ATHN02C 의 해골병·레버·크랭크·제단이 동작하지 않았다 → 자기 ENV 볼륨 밖 + 숙주 바닥 0.1 초면 숙주로 WALK 전환
  (`GameScene.insideOwnVolumes`). 경로 봇은 전환을 넘어 이어진다 (`GameSession.autoCarry`).
- **LoadCheck 는 현재 레벨 바닥이 발밑에 없을 때만**: ATHN02C `LoadCheckB2` 는 회전판 북쪽 가장자리의 얇은 볼륨 (크랭크 뒤에 서면 닿음) 인데 ATHN02B 도 그 바닥을 가져 발사 순간
  ATHN02B 로 넘어가 레버·크랭크가 이웃 것이 되었다 (R2 무반응). 겹친 바닥을 벗어나면 WALK 규칙 (다른 슬롯 바닥만) 이 넘긴다.
- 렌더 확인: 전환 뒤 통로가 검던 것 (`01`) 은 위 §14.3 (전환과 무관). ATHN02C 를 바로 시작하면 ATHN02D 의 `MAmagicIcon` (icon360, 남쪽 출구) 이 검은 번개 무늬로 보인다
  — 정상 흐름에서는 미노타우로스 뒤 VisOffAfterSmash 로 꺼진다 [미확인: 아이콘 재질].

### 14.5 이웃 (다른 슬롯) 스크립트의 SpawnEnemy [근사: 엔진 구조]
사다리 위 방은 두 레벨 바닥이 겹치고 그 적은 ATHN02B 스크립트가 낸다 (ClimbWallEnemies1, LoadCheckB5 는 위 규칙으로 넘어가지 않음). 엔진은 이웃 SpawnEnemy 를 버려 영상 3406 의
싸움이 없었다 → `SceneNav.Host.neighbourSpawn` → `GameScene.spawn(r, neighbour)`: 이미 올린 적 종류의 템플릿만 (보스 레벨이 현재면 생략), 피해 1039·제거는 이웃 세계
(`CombatScriptBridge` 따로, `worldOf`). 결과 229 초 해골병 셋 + 리스폰 (`12` `13`). 이웃 음악 (MusicGrpAth2C5Start) 은 아직 안 튼다.

### 14.6 경로 하네스 [엔진 전용]
`-Dgow.auto.route` (`AutoPlay.routeStep`, 단계: `x,y,z[,반경]` 걷기 (6 초 넘으면 순간이동, 기록) · `use:fx,fz` (몸 돌리고 R2) · `hold:초:fx,fz` (R2 + 스틱) · `push:초:fx,fz` ·
`up:초` · `mash:초` · `wait:초` · `btn:이름`), `-Dgow.auto.route.after=초` (그 전에는 보통 자동 플레이 — 메두사·미노타우로스). 이번 실행:
`after=128`, `-1230,960,1900;-1250,960,1820,20;-1250,960,1700,20;-860,960,1680;push:1.5:-700,1680;up:8.4;-700,1216,1682,5;use:-688,1682;wait:9;-527,1216,1682,6;use:-480,1682;hold:8:-480,1682;wait:12;-300,1216,1682,10;-160,1216,1700,6;push:1.2:-160,1760;up:7;-160,1361,1790,10;-150,1361,1900,10;-150,1361,2000,10;wait:20`.

### 14.7 증거 (`work/verify/athn02c_route/`, 커밋 안 함)
`01_before_door_black_137.0s` (수정 전 검은 막) · `02_door_face_before_fade_132.0s` · `03_door_faded_134.5s` · `04_corridor_137.0s` · `05_legionnaire_fight_170.0s` ·
`06_ladder_shaft_179.0s` · `07_lever_turntable_rotating_191.0s` · `08_crank_winding_200.0s` · `09_door1_break_204.0s` · `10_hallway_emblem_door_206.0s` ·
`11_hallway_ladder_225.0s` · `12_upper_fight_232.0s` · `13_climbwall_legionnaires_245.0s` · `14_save_altar_dialog`.
시험: `BallistaPuzzleTest` (멈춤 자리 3 / 6 / 역 3 초, jump_if, Filter, 대포 크랭크, 화살 → 문 → LevelData14), `ScriptMaterialAnimTest`, `AnimatorModeTest` ("$" 재질 시계).

### 14.8 남은 것 · 미확인
- 위층 방 남쪽 돌무더기 위 해골병 둘 (−138, 1366, 1882) 을 봇이 80 초 동안 못 잡고 죽음 (r13 315 초) [미확인: 봇 한계 / 적 위치·충돌].
- 3291 메모리카드 덮어쓰기 화면, 3357 회전판 위 높은 카메라 — 엔진으로 안 봄. 돌문 R2 · 둘째 발사 (작살 석문 / door2) · HallwayMinos · BreakSecret · 짚라인 — 몰지 않음.
- 뒤 시트 (3420–4698): 3420–3456 복도 해골병 싸움만 (§14.5). 3474 이후 (상자, 우리, 바깥 ATHN02B …) 는 다루지 않음.

## 15. QA vs PS2 영상 — HUD 미터가 탐험 중 사라진다 (영상 5658–5675 초, 2026-10-24)

- 영상 (1 초 간격): 5658–5664 미터 없음 (달리기) → 5665 블레이드 휘두름에 뜸 → 5669 까지 → 5670 사라짐 → 5674 다시 휘두름에 뜸.
  5136 · 5616 · 5636 · 5661 · 5671 같은 섬네일의 "HUD 없음" 은 캡처 결함이 아니라 원작 동작이다.
- 원작 Progress_UpdateHud 0x1D7148 [확실]: 전투 기술 중 · 값 변화 · 오브/아이템 · 마법 재생이면 미터를 띄우고, 변화 없이 TWK_Player
  `Meter Bar Hold Time` 2.0 초가 지나면 내린다 (21 문서 §5 "미터 자동 표시").
- 엔진 결함: 게임 씬은 첫 갱신에 MeterBar 1 을 보내고 전역 정지 때만 내렸다 — 전 레벨에서 미터가 늘 떠 있었다. 수정 `HudBinding.meter`.
- 검증: ATHN03C 자동 플레이 녹화 (쉬움, 0.5 초 간격) — 2–12 초 싸움 동안 미터, 12.5 초부터 (마지막 기술 뒤 2 초) 미터 없음. `HudMeterTest` 7 건.

## 16. QA vs PS2 영상 — ATHN03AA / ATHN03A "아테네로 향한 도로" (영상 4744–5131 초, 2026-10-24)

오라클 홀 (ATHN03AA) 부터 갑옷 미노타우로스 방 (ATHN03A) 까지. 엔진 = origin/main 7b47a31 (jump_if 0x0F = 거짓이면 점프, HUD 미터 자동 숨김) 위.
녹화 r18 (`--game ATHN03AA --skip-movies`, 경로 봇 `gow.auto.route` §14.6, 1/3 초 간격, 결정적), 영상 포함 실시간 r13 (로그만), 성문 r17 (`--game ATHN03A`).
경로: `160,965,1086;wait:5;160,965,782;wait:3;160,1050,300;160,1127,-29;160,1150,-148,4;wait:13;160,1124,-280;160,1120,-400;300,1120,-420;350,1120,-465,8;wait:3;200,1120,-500;wait:7;2380,1090,-830!;use:2447,-829;wait:6;btn:CROSS;wait:2;-825,1030,-650!;-825,1027,-560;-815,1027,-451,10;wait:40`.
jump_if 정정 재확인: ATHN03AA 스크립트에는 jump_if 가 없고 ATHN03A 는 `archerEnt` H4 하나 (타이머 없으면 5 초 타이머, 있으면 지움 — 길 위 궁수, 이 경로 밖) — 아래 결론은 정정 뒤 실행이다.

### 16.1 장면 대응 [확실: 스크립트·데이터 / 영상 대조는 섬네일]
| 영상 | 원작 (스크립트) | 엔진 r18 (게임 시각) |
|---|---|---|
| 4744–4752 | ATHN03AA 홀 (봉인 원판, 시작 (160, 960, 1344)) | 0–3 초 |
| 4756–4780 | A03MP02 오라클 영상: `a03Mp02MPEG-pCube1-idle` → Idle + FadeOut(0, 0, 0, 0.4) → 0.4 초 뒤 PlayMovie → 끝 (501 "a03Mp02", Filter 1 = 플레이어) → FadeIn(1) · 0.3 초 뒤 LoadWad Athn03A · 3 초 뒤 Idle 해제 | 3.0 초 페이드 → 3.4 초 영상 (건너뜀) → 페이드 인. r13 실시간은 영상을 틀고 봇이 × 로 넘긴다 |
| 4784 | 원형 방 | 6–8 초 |
| 4788–4796 | 계단 + 구역 제목 **"아테네로 향한 도로"** (ZoneReportGroup: GlobalData:0 ≠ 3 → Internal 0x10(3) → 메시지 803 첫 줄, GlobalData:0 = 3 이라 한 번) | 12.8 초부터 (`01`) |
| 4800–4808 | 거대한 아레스 (trigSParkyCine: Idle 12 초 + camSpecialSparky, 계단 위 → 원형 제단 위로 크레인) | 23.9–35.9 초 (`02`–`04`) |
| 4812 | 계단 위 아레스 망토 (어두운 붉은 "커튼") 통과 | 24.3 초 · 36–42 초 (`02`) |
| 4816–4825 | ATHN03A 세이브 제단 빛기둥 + R2 | 45–48 초 (`05`) |
| 4834–4843 | 절벽 길 (기둥) | 경로가 건너뜀 |
| 4852–4879 | A03MP01 아레스 영상 (MoviePlay 띠 x 8..320, z −464..−536, LevelData:5) | 48.3 초 (건너뜀) → 하늘의 아레스 (`06`) |
| 4888–4915 | 사막 문 쪽 절벽 길 | 61 초 순간이동 |
| 4924 | DesertGates (2448, 1082, −829): R2 (1024 UseWorld, 보낸 이 접촉) → camGateZoom + Idle → 3 초 뒤 메시지 209 "오라클의 상징이 새겨진 거대한 문입니다 … 이쪽에서는 열 수 없습니다" → 1040 → 카메라·Idle 복귀 | 62–65 초 (`07` `08`) |
| 4942–4960 | 길 위·원형 제단 위 하늘의 아레스 | (`06`) |
| 4969–4978 | 동굴 성문 (CaveGateA1 volumeGateClose, −815, −451) → Idle, 성문 내림, GateExplosion AniFor (불덩이), cine1FireBall = camFireballShot, 3 초 뒤 camMinoZoom, 7 초 Idle 해제, 7.1 초 CheckPoint | 71.3–72.7 초 (`09`–`11`) |
| 4987 | 창살 · MinotaurLevel2 둘 (템플릿 51, 셋째는 처치 2 이상) | 74.7–78 초 (`12`) |
| 4996–5059 | 갑옷 미노타우로스 싸움 | 80 초부터 (`13`) |
| 5068 | 원형 압력판 MinoPressurePlate | 봇이 이기지 못해 안 봄 (§16.5) |
| 5086– | 고르곤 = ATHN03C (MedusaLevel1 스폰) — 이 두 레벨이 아니다 | — |

### 16.2 엔진 결함과 수정
1. **구역 제목이 없었다** — Internal 0x10 을 버림. 원작 GameMgr_OnMessage 0x1B0A28 → 0x1B60D0 → 0x1B8BA0: 메시지 800 + 구역 의 첫 줄 (0x1B95D0 줄 0, 없으면 "Unknown zone") →
   HUD +0x84 `PS2_ZoneReport_Title`, +0x264 `PS2_ZoneReport_Event` = 1 [확실]. `GameScene.zoneReport` (11 §6, 21 §5).
2. **스크립트 화면 페이드를 버렸다** (Entity 0x5 FadeOut / 0x6 FadeIn) — 오라클 영상 앞뒤가 바로 잘렸다. 상태 0x303D50 + 매 프레임 0x1AF648 색 사각형 [확실], `ScreenFade` ·
   `Renderer.overlay` (HUD 아래 [추정]), 이웃 슬롯 스크립트도 (상태가 전역 하나) — `SceneNav.Host.neighbourFade` (13 §2).
3. **세이브 제단에 빛기둥·R2 안내가 없었다** — Creator "savepoint" 의 몸 = R_PERMK 오브젝트 savepoint (0x1ACCA8) [확실], R2 아이콘·저장 거리 4 m = 64 (0x1DC678 / 0x1DC3F8,
   예전 45 [근사]) [확실] (38 §21). `GameScene.spawnSavePoint`, `SAVE_POINT_RANGE`.
4. **하늘의 거대한 아레스가 없거나 찢어졌다**:
   (a) `goaresanimgroup1` 의 SCR_Sky 는 인스턴스 스크립트 (본문 없음) — 원작은 본문을 읽지 않고 소유 GO 를 카메라로 옮긴다 [확실: 0x218920 · 0x218848] (16 §1.4, `EnvParams.skyOwners`).
   (b) 애니 하늘 GO 는 매 프레임 스키닝 (관절 0 = 눈, 반복 시작 act aoBAR1a03Hl09 = 레벨 시간) [확실: GO 이동 / 근사: 시간] (`LevelScene.renderSkyAmbient`).
   (c) 부착 관절 파트 공간: 부착 관절에 inverse bind 가 없으면 (Ares `group9` 배율 2.91 노드) 파트 정점은 그 관절 공간의 바인드 메시 — skin (invBind·world) [추정: VU1 팔레트 미해독,
       데이터 일치 invBind·bindWorld = bindWorld[부착] 오차 0.000] (`SkinnedModel.Part.jointLocal(Skeleton)`; 키클롭스 R_CY2/CY3/CYW 의 노란·분홍 큰 삼각형도 같은 원인).
   (d) 하늘 모델은 동적 조명·그림자 없음 (0x218778 렌더 플래그 &= ~0x800) [확실].
5. **하늘 GO 이미터가 없었다** (도시 화톳불 group2 9, 아레스의 불붙은 바위·불덩이·재 43): 하늘 공간 (기준 = 눈 − 관절 0) 에서 돈다 [확실: GO 이동 / 근사: 이미 나온 파티클은 월드,
   깊이는 일반 파티클 패스] (`GameScene.startSkyEffects`, `LevelEmitters` 는 하늘 배치를 건너뜀).
6. **Animator 가 튼 레벨 GO 의 채널 이미터가 없었다** (성문 불덩이 GateExplosion, 건물 폭발 group3251/3278/3284/3289, TallBuildExplode2, CornerRip00): act 가 돌 때 링크된 채널
   (생성률·속력·퍼짐) 로 이미터를 관절에 붙여 돌린다 [확실: 0x130240 + 데이터 일치] (`GameScene.startAnimatorEffects`, `EffectSystem.Effect.jointEmitters`).
7. **ANM 디스크립터 배치** (05 §2.8.1) [확실: 코드 0x100EA8 · 0x130240 + 전 WAD 데이터]: act 디스크립터 = 데이터 타입 항목 p2 의 합. 예전 파서는 항목 수만 읽어 GateExplosion 의
   생성률 150 채널 (디스크립터 4) 을 "타입 11 = 관절 이동" 으로 읽어 불덩이를 0.45 초에 x 로 1000 옮겼고 (불꽃 본체 FBFemit2GE 는 곡선 없음), 진짜 이동 (디스크립터 1) 은 버렸다.
   관절 카메라도 같아 camSpecialSparky · camFireballShot · camMinoZoom 이 제자리에서 돌기만 했다 (전 WAD 213 개 중 179 개). 원작 배치 (`Animation.records` …) 를
   관절 카메라 (`CameraRig.CamAnim`) 와 관절 이미터 이펙트에만 먼저 적용 — 영상 4978 처럼 불덩이가 오른쪽 위에서 성문으로 들어오고 (`10`, 전: 카메라 고정 · 위에서 내려옴
   `before_static_fireball_cam_r14`), sparky 크레인이 계단 위 커튼을 지나 원형 제단 위로 나간다 (영상 4800–4812).
8. **다른 슬롯 하늘** (§16.3).

### 16.3 두 슬롯 하늘 [확실: 가시 칸 데이터 / 근사: 렌더 구조]
ATHN03AA 와 ATHN03A 는 같은 하늘 GO (gogroup2 · goaresanimgroup1, 컨텍스트 CXT_bvsky) 를 따로 가진다. 눈이 계단 위 (z > −40) 면 ATHN03AA 칸만, 그보다 남쪽이면 ATHN03A 칸만 그
하늘을 켠다 (`LevelData.contextHidden`, 경로 위 7 점 확인). 원작은 두 슬롯이 함께 상주해 각자 가시 칸대로 그리므로 늘 하나가 보인다. 엔진은 현재 레벨 하늘만 그려 sparky 크레인
(ATHN03AA 가 현재) 이 남쪽으로 나가는 동안·계단 위에서 ATHN03A 로 걸어 들어가 전환되기 전까지 하늘이 검게 비었다 (`before_sparky_black_sky_r15`) → 하늘 단계에서 다른 슬롯
장면의 하늘 GO 도 그 레벨 가시 칸으로 걸러 그린다 (`LevelScene.skyNeighbour`, 애니 하늘 시간 = 현재 레벨 시간).

### 16.4 시험
`RoadToAthensScriptTest` (구역 제목 한 번, 오라클 영상 FadeOut → 영상 → FadeIn), `RoadToAthensTest` (메시지 800 + 구역 첫 줄, FadeOut/FadeIn 프레임 계수 — n = cvt.w.s(초 / 0.016666668),
1 초 = 59 · 0.4 초 = 24 [근사: EE FPU 0 쪽 반올림이면 23] · 뒤집어 잇기, 세이브 포인트 몸·4 m), `AnimRecordsTest` (Σ p2 디스크립터 13 / 히어로 11, 표 뒤 트랙, 불덩이 경로·관절 1/3 고정,
생성률 채널 id), `SkinAttachRuleTest` (아레스 group9 · ME3 STB), `SkyBackgroundTest` (goaresanimgroup1 하늘, skyOwners), `CameraRigTest.eleEnterJointCameraPans` (정정, §13.11).

### 16.5 남은 것 · 미확인
- 불덩이 모양: 영상은 흰·노란 핵에 주황 꼬리, 엔진은 회색 연기 + 주황 (PTC VU 파티클 색 — 16 §2.4) [미확인]. 미노타우로스가 영상보다 회청색 [미확인: 조명/재질].
- 원형 압력판 (5068), 셋째 미노타우로스, 성문 열림 — 봇이 갑옷 미노타우로스 둘에게 짐 (r18 사망 2, r17 사망 2) [미확인: 봇 한계].
- 영상 재생: 녹화 lockstep 에서는 영상이 느리게 간다 (r12 210 초에도 오라클 대사 중) [엔진 전용, 녹화 한계]; 실시간 봇은 × 로 넘긴다 — 영상 자체 대조는 하지 않았다.
- 두 슬롯 스크립트: 전환 때 현재 레벨 ScriptWorld 를 새로 만들어 이웃으로 이미 돈 일회성 (LevelData) 이 다시 돌 수 있다 — 순간이동 (r14) 으로 MoviePlay 띠 안에서 전환하면
  사망 뒤 MoviePlay 가 또 불렸다. 걸어 오면 띠 전에 전환되어 한 번 [근사: 엔진 구조]. 이웃 슬롯의 PlayMovie · Idle 은 받지 않는다.
- SCR_AresSky (ATHN03D/03E 무작위 act) 미구현, 하늘 GO SEM 소리 끔, 하늘 파티클 깊이 [근사] (→ 층 1 하늘 단계로, §17.2-5).
- ~~ANM 원작 배치의 전역 전환 (영웅·적·레벨 GO 자세, 루트 모션, 가시성) — 시험 7 개가 예전 배치 값 (05 §2.8.1).~~ → §20 (전환 완료, 7 개 값을 원작 근거로 다시 정함).
- [엔진 전용] 봇: 경로 끝 뒤 이어받은 AutoPlay 가 이전 레벨 (ATHN03AA) 볼륨 계획으로 돌아간다 (r15 140 초) — 경로 끝에 긴 wait 로 피함.

### 16.6 증거 (`work/verify/road_to_athens/`, 커밋 안 함)
`01_zone_title_stairs_16.0s` · `02_sparky_curtain_24.3s` · `03_sparky_ares_circle_26.3s` · `04_sparky_ares_city_31.0s` · `05_save_altar_R2_46.0s` ·
`06_ares_sky_after_movieplay_52.0s` · `07_desert_gates_zoom_64.0s` · `08_desert_gates_msg209_65.0s` · `09_cave_gate_approach_71.3s` · `10_gate_fireball_72.3s` ·
`11_gate_fireball_trail_72.7s` · `12_mino_zoom_portcullis_77.3s` · `13_armored_minotaurs_87.3s`, 시트 `sheet_engine_r18` · `sheet_sparky_cine_r16` · `sheet_ps2_4784_4897` ·
`sheet_ps2_4906_5068`, 수정 전 `before_sparky_black_sky_r15` · `before_static_fireball_cam_r14`.

## 17. QA vs PS2 영상 — 아테네 거리 ATHN03A · ATHN03C → 광장 ATHN04A (영상 5136–5716 초, 2026-10-24)

분홍 미노타우로스 홀 (ATHN03A) 뒤 거리부터 광장 키클롭스 싸움 (ATHN04A) 앞까지. 엔진 = origin/main dd06e6e (§16) 위, 쉬움, 녹화 lockstep (결정적).
- r10 거리: `--game ATHN03A --arrive "ATHN03AA@-1340,1030,-370,0"`, 봇 + 경로 `-1300,1023,-600;-1450,1017,-720;-1650,1017,-820;-1760,1017,-1000;-1770,993,-1300;
  -1780,953,-1550;-1850,929,-1780;-2000,913,-1830;-2200,913,-1850;-2400,913,-1720;-2500,913,-1550;wait:30` (§14.6), 1/3 초 간격 70 초.
- r11 줄: `--game ATHN03C --arrive "ATHN03A@-3237,975,-1660,180"` (줄 위에서 떨어져 잡음), 8 초.
- r12 업그레이드: `--game ATHN03C --arrive "ATHN03A@-3300,913,-2500,0"` (절벽 길), `-Dgow.redOrbs=6000`,
  `-Dgow.script=SELECT:1.0:0.15,CIRCLE:3.0:6.0,CIRCLE:10.0:0.15,CIRCLE:12.0:6.0,CIRCLE:19.0:0.15,CIRCLE:21.0:0.15,TRIANGLE:23.0:0.15,SQUARE:24.5:0.1,…`, 27 초.
- r9 광장: `--game ATHN04A --arrive ATHN03C` (Warp), 경로 `2050,-404,-690;1800,-404,-720;1620,-404,-600;1450,-404,-470;1440,-300,-250;1440,-150,0;1440,0,300;1450,49,560;wait:20`, 1/4 초 간격.

### 17.1 장면 대응 [확실: 스크립트·데이터 / 영상 대조는 섬네일 (5 초·3 초 간격)]
레벨 연결: ATHN03A ─(Sensor2 LoadCheck, 이음)→ ATHN03C ─(Athn04AWarp: LoadForWarp + EntryWarp (−3418, 914, −2564))→ ATHN04A `WarpFrmVista` (2212, −404, −624, 슬롯 0 "athn03c").
| 영상 | 원작 (스크립트·데이터) | 엔진 (녹화, 게임 시각) |
|---|---|---|
| 5130–5172 | ATHN03A 거리 (RSRCS R_SKA·R_MI2) 에 **ATHN03C 스크립트** RoadSpawner2 (진입 볼륨 (−1768, 1017, −1049) r 794) → RoadEnemies2 MedusaLevel1 (템플릿 60 = R_MEW, ATHN03C 슬롯 리소스) 고르곤, 지붕 위 궁수 (ATHN03A ArcherLevel1 템플릿 31 셋, ATHN03C MinoAIGRPropes ArcherLevel1AI1·3·11·12·13) 의 붉은 화살 | r10: 화살 5 초부터 (`01`), 고르곤 스폰 14.4 초 (수정 전 "이웃 스폰 생략"), 고르곤 + 궁수 + 하늘의 아레스 51–62 초 (`02` `03`) |
| 5133–5145 | 지붕 상자 (고르곤의 눈 6 개 → 체력 증가) | 주행 안 함 [미확인] |
| 5148–5306 · 5376–5546 | 거리 싸움 (궁수 · 해골병 · ATHN03C MinotaurLevel2 둘 + 처형), 창살 창문 | r10 거리 (ATHN03A → 03C 전환) |
| 5316 · 5366 | 업그레이드 메뉴: 블레이드 레벨 2 → 3, "혼돈의 블레이드 레벨 3 – 새로운 액션" | r12: SELECT 1 초 → ○ 유지 → 레벨업 · 새 기술 화면 18 초 (`05`) → 메뉴 19.3 초 (`06`) → △ 23 초 닫힘 → R_WPN0_2 (`07`) (§17.3) |
| 5556–5606 | ATHN03C 비계 · 구덩이 (바닥 y 800) | — |
| 5612–5646 | 줄: RopeAttachGroup-attachRope (EventSensor 모드 1, 1051 인자 0) → 메시지 591 "줄을 앞뒤로 흔들려면 ◎…", 올라감 | r11 잡자마자 (0.3 초) 591 (`08`) |
| 5652–5656 | 절벽 길 + 하늘의 거대한 아레스 (goaresanimgroup1, 불붙은 바위·불덩이·재 = 하늘 GO 이미터 43) | r12 0–1 · 23–27 초 (불덩이가 건물 뒤 하늘에) |
| 5657–5660 | Warp → ATHN04A `WarpFrmVista` (문 앞 굽은 길). 도착 볼륨 ZoneReportGroup1 (구역 3) 은 이미 안으로 둔다 | r9 0 초, 같은 구도 (`09`) |
| 5664–5676 | 문 옆 출입구 · 길 | r9 0.5–3 초 (0.8–1.3 초 카메라 전환이 건물을 지남 [미확인: 영상 간격]) |
| 5681–5686 | 분수 | r9 4–6 초 |
| 5696–5706 | 계단 → ZoneReportGroup (중심 (1440, −312, −184)): ZoneArea ≠ 4 → ZoneReport(4) → 메시지 804 **"아테네 도시 광장"** | r9 7.37 초 계단 아래쪽에서 발동, 8–16 초 표시 (`10`) — 영상 5701 과 같은 자리 |
| 5711–5716 | 광장 키클롭스 (DMCCyclops 셋 — 이미 검증) | — |

### 17.2 엔진 결함과 수정
1. **이웃 슬롯 적 리소스가 없어 고르곤이 안 나왔다.** 원작 Tag_Resources 0x1BB928 은 RSRCS 이름을 읽는 슬롯 (0x3372D8) 구조 +0xA + 10·i 에 "R_%s" 로 두고
   (최대 4, 슬롯을 비우면 0x1BC028) — 리소스는 레벨 슬롯마다 상주한다 [확실: 코드]. 이웃 스크립트의 SpawnEnemy 는 현재 레벨 RSRCS 에 없는 종류도 낸다. 엔진은 현재 레벨
   종류만 찾아 "이웃 스폰 생략" → `GameScene.loadNeighbourKinds` (이웃 레벨 RSRCS 를 따로 적재, 외부 애니·트윅·소리·영웅 잡기 애니) + `spawn` 의 이웃 요청 폴백 (11 §6.3).
2. **궁수 SHOOT 가 시작 프레임에 끝났다.** 기술 1 의 애니 해시 = ANM 블렌드 그룹 "shoot" (그룹 플래그 0x20, Ashoot00/01/02 = 블렌드 번호 0·1·2) — 원작 0x1F6FC8 → 0x1006F8 →
   0x107200 은 플래그 0x20 그룹의 act 를 모두 모아 (+0xCC, +0xDC 개수) 겨눔 값으로 섞는다 [확실: 모으기 / 근사: 엔진은 가운데 act 하나]. 엔진은 act 이름만 색인해 애니가 없어
   SCR_ArcherShoot · SND_BOW_FIRE 등 이벤트가 하나도 나지 않았다 → `CombatData.reindexAnimations` 가 블렌드 그룹 이름 → `blendCentre` (아기 히드라 물기·BMI 주먹도 같은 꼴).
3. **적 화살이 없었다** (10 §16, 17 §AI Mode 2):
   (a) SCR_ArcherShoot (Hash127Upper 0x63F805BF, 이벤트 b = 0.2, 이미터 20) 0x18DAA8 → 매 프레임 0x18D500: 겨눔 값 = 수평 각 / 25° (0.43633) 를 [−1, 1] 로 자른 것까지 0.02 / 프레임,
       그동안 당김 += 0.01 (첫 −0.99), 그다음 당김 += 0.05 / 프레임이 b 이상이면 0x18D800 → 0x222710 [확실: 상수] — 바로 앞 25 프레임, 25° 옆 64 프레임. 맞아서 기술이 바뀌면 취소 [추정].
   (b) TARGET_FX (전투 이벤트 종류 1, 0x206788) = 그 인덱스 ArrowEmitter 를 대상에게 0x222710 [확실: 흐름] — ASHOOT3X 의 이미터 33 (폭발 화살) 세 번.
   (c) 0x222710 (대상 있음): 시작 = 이미터 "Aim Joint" (rWrist), 대상점 = 발 + 몸 높이 ½ (0x18D708) [추정: 몸 높이 2.2 m], Count 발마다 대상 속도로 앞질러 (Arrow #9fdb479c = 0 일 때,
       |대상 − 시작| / (Velocity·16)) + 단위 구 무작위 점 × #1bf49679 m (0x2228E0: 궁수 0.1, less_accurate 1.75, 폭발 1.5) → 탄도 해 0x2223C8 (θ 를 0 부터 π/4 걸음으로 8 번 반감,
       마지막으로 평가한 각을 쓴다) [확실: 상수]. Arrow 020: 100 m/s, 중력 9.82 (Gravity Flag 1), 피해 2, 수명 #117c872e 10 초, GO/Trail = goStdArrow 붉은 꼬리.
   (d) 맞음: 이전→현재 선분이 정적 충돌 (두 슬롯) · 움직이는 몸보다 먼저 영웅 몸 구 (CDV_hero, 무기 GO 제외) 에 닿으면 [근사: 원작 충돌 마스크 미해석] — 피해 = Damage × 쏜 적
       AI 배율 [근사: 배율 위치], Ground/Air Impulse, 막으면 Block Sound, Concussion ≥ 0 이면 그 자리 범위 (폭발 화살 33) [추정]. 적끼리는 맞지 않는다 [추정].
   `GameScene.archerShoot/fireEnemyArrow/enemyArrowHit`, `ArrowShots.fireAt/launch/heroStep`. r10: 70 초 동안 영웅 적중 14 회 (피해 2 × 쏜 적 배율 → 1.0, 쉬움).
4. **줄 잡기 이벤트 1051 이 없어 줄 안내가 안 떴다.** 0x1C4EF8 → 0x1C4FD0: sub_001A8C78(0x41B, 0, 0, 0, 영웅) [확실] → ATHN03C RopeAttachGroup-attachRope (모드 1, 인자 0 == 0, 한 번)
   → ShowMessage(591, 0) (영상 5612). `HeroNavigator.EVENT_ROPE_GRAB` · `Listener.ropeGrabbed` → `SceneNav` postEvent (38 §2).
5. **하늘 GO 불덩이가 세계 위에 떠 있었다** (§16 의 하늘 이미터 뒤): 0x218848 은 하늘 GO 의 모델 (0x330514) 과 파티클 (0x330524) 부품을 모두 0x218778 → 0x150738(부품, 1) =
   층 1 로 둔다 → 세계 (층 2–4) 보다 먼저 그려지고 세계가 덮는다 [확실: 부품 목록 둘 / 추정: 0x330524 = 파티클]. 엔진은 일반 파티클 패스에서 그려 하늘 미니어처 거리 (~260) 보다
   먼 건물 위에 불덩이·재가 보였다 (ATHN03C 구덩이 · 절벽 길) → `EffectSystem.Effect.sky` → `ParticleSystem.Emitter.sky` 는 일반 패스에서 빼고 하늘 단계 끝
   (`LevelScene.skyEffects` = `MagicFx.renderSky`) 에서만 (16 §1.4, 31).
6. **업그레이드 뒤 무기 WAD 를 바꾸지 않았다.** 원작 Upgrade_LoadWad 0x1D7890 이 레벨이 바뀐 무기의 "Upgrade" 힙 WAD 를 교체 (`"%s%d_%d"` 0x29DB00) [확실] — 레벨마다 블레이드
   텍스처·불꽃 이미터가 다르다 (R_WPN0_1 Blade128·129·130, R_WPN0_2 Stage3Btx + FXC_EGemit1·EGgrav1, R_WPN0_3 Stage4Btx, R_WPN0_5 athenaBtx). 엔진은 다음 레벨 적재까지 옛
   블레이드 → `HeroRig.swapBlades` (메뉴가 닫힌 다음 그리기에서 모델·사슬 머티리얼) + `onBladesSwapped` → `setGodBlades` (새 WAD 의 goMAIblade / goMAIgodblade 가 뒤에 등록돼 이김)
   + `MagicFx.reattachBlades` [추정: 원작은 힙과 함께 GO 를 새로 만듦]. 블레이드 라이트는 레벨 적재 때 것 [근사].
7. **블레이드 레벨 ≥ 1 이면 다음 레벨부터 블레이드가 사라졌다.** 기본 코스튬 0 과 코스튬 5 의 R_WPN 레벨 1–5 (36 개 중 10 개) 는 모델 이름이 `MDL_MAIblade` (CDV·SHG·메시도 소문자 b),
   나머지는 `MDL_MAIBlade` [확실: 데이터 전수]. `BladeModels.load` 가 정확한 이름만 찾아 null → 블레이드 메시·사슬·블레이드 라이트 없음 (피격 구는 대소문자 무시라 공격은 맞음).
   → `WadAssets.gowModelIgnoreCase` (원작 자원 조회는 대문자 해시 계열 — 머티리얼 0x17A7B8, 46 문서 [추정: 모델 조회 경로]).
8. **GlobalData 를 번호로 공유했다.** 원작 GlobalData 엔티티는 메시지 0 에서 변수 (이름, 타입) 를 등록할 때 (0x1AE848 → 0x1AEC88) 전역 표 0x30063C (0x1C 바이트 × 최대 128) 에서
   이름 (strcmp 0x28A4A4) 으로 찾거나 붙이고, 그 레벨 슬롯의 번호표 (0x2FFE3C + 2·(슬롯 +0x10 + 번호)) 에 칸을 적는다. 읽기·쓰기 0x1AEDE8 은 실행 중 엔티티 슬롯의 표로 찾는다 [확실].
   같은 변수의 번호가 레벨마다 다르다 — 이름 93 개 중 48 개 (ZoneArea: ATHN03A 0 · ATHN04A 2 · ATHN02A 1 · PAND00C 14 …, HydraDead: ATHN01A 0 · 01B 1 · 01E 2, DoorKey: ATHN04A 1 · 04B 0).
   엔진은 번호를 열쇠로 써서 ATHN04A 의 cameFromA5C (0) 가 ATHN03A 의 ZoneArea 3 을 읽고, ZoneArea (2) 는 0 이라 구역 3 볼륨에 다시 들어가면 "아테네로 향한 도로" 가
   또 떴다 (시험) → 레벨 선언 이름으로 (`ScriptWorld.globalName`, `ScriptHost.globalKey`, `GameScriptHost.global(이름)`, 13 §7).
   Init 핸들러는 데이터 전체에 넷뿐이고 모두 false (기본값) 라 "한 번만 초기화" (0x1AEE90, +0x19) 는 예전처럼 엔티티 단위 [근사].
   예전 세이브의 GlobalData (번호 열쇠) 는 이름 열쇠와 맞지 않아 무시된다 [엔진 전용].

### 17.3 업그레이드 메뉴 (영상 5316 · 5366) [확실: FLP_HUDK 스크립트 · 엔진 실행]
- SELECT → 상태 13 PowerUpMenu: `HudBinding.write` (Progress_ToHud) + `PS2_PSMMenu_Event` 1 → ○ 유지로 투자 (오브 6000: 1500 에 레벨 2, 다시 2250 에 레벨 3, 다음은 3750) → 레벨업 화면
  ("혼돈의 블레이드 레벨 3 – 새로운 액션": 혼돈의 싸이클론 L1 + □ · 공중 · 헤라클레스의 기세 △△△ · 용맹 △△□ · 하데스의 복수) — 영상 5363 과 같은 문장·배치.
- △ 뗌 → Animate_Off → `PS2_PSMDone` 1 → `GameScene.onPowerUpClosed`: 오브 2231 (3750 + 다음 레벨로 19), 블레이드 내부 0 → 2 (화면 레벨 3), `HeroRig.swapBlades` → R_WPN0_2
  (r12 23 초, 로그 "블레이드 무기 WAD 교체").
- `gow.script` 입력이 메뉴에도 가도록 (SELECT · HUD 갱신) [엔진 전용], `-Dgow.redOrbs` [엔진 전용], `-Dgow.debug.hudvars` (FLP 가 바꾼 HUD 변수 기록) [엔진 전용].

### 17.4 광장 구역 제목 (영상 5701–5706)
"아테네 도시 광장" 은 원작과 같은 자리 (계단 아래쪽, ZoneReportGroup) 에서 뜬다 — r9 도착 뒤 7.37 초 (경로 봇이 분수를 곧장 지남; 영상은 도착 ≈5659 → 출입구 · 분수를 돌아 5701).
도착 볼륨 ZoneReportGroup1 (구역 3) 은 Warp 도착이 "이미 안" (`placePlayer` seamless) 이라 영상처럼 뜨지 않는다. 제목 렌더·지속은 §16 담당 (`GameScene.zoneReport`).

### 17.5 시험
`AthensStreetsTest` (궁수 당김 25 / 64 / 21 프레임, 탄도 해 낮은 탄도·오차, R_SKA 이미터 20 화살이 영웅 몸 구에 "영웅" 으로 멈춤, SHOOT = 블렌드 그룹 가운데 act + SCR_ArcherShoot 20,
ATHN03A/03C RSRCS · R_MEW 템플릿 60), `RopeGrabEventTest` (줄 잡기 통지 한 번, ATHN03C 1051 → 591 한 번), `BladeWeaponWadTest` (R_WPN 36 개 모델·오브젝트, 소문자 b 10 개),
`GlobalDataNamesTest` (ATHN03A ZoneArea → ATHN04A 구역 3 다시 안 뜸 · cameFromA5C false · 계단 구역 4, HydraDead 01E/01A/01D/01C 이웃 호스트). 바뀐 시험: `RoadToAthensScriptTest`,
`HydraFightTest` (GlobalData 를 이름으로).

### 17.6 남은 것 · 미확인
- 화살 꼬리: 영상은 가는 붉은 선, 엔진은 틱 위치마다 뭉친 큰 점 (`04`) — FXC_stdemit 생성률 800/초를 한 점에서 낸다. 원작이 프레임 안 위치를 보간하는지·늘인 스프라이트인지 [미확인: PTC VU].
- 궁수가 60–70 m 밖에서도 쏜다 (AI 결정 트리 거리) — 영상에서도 지붕 궁수가 멀리서 쏘지만 거리 상한은 대조 못 함 [미확인].
- 이웃 슬롯의 레벨 템플릿 (ATHN03A·03C 각자의 시민 AOXY1 = 템플릿 313) 은 이웃 요청이면 내지 않는다 (레벨이 현재가 되면 그 스크립트가 다시 냄) [근사: 엔진 구조].
- 새 기술 화면의 인라인 버튼: 영상은 L1 · R1 글자가 있는 배지 + 바탕 없는 △□ 기호, 엔진은 글자 없는 배지 + 둥근 바탕 (StaticTextL1 의 글자 출처 못 찾음) [미확인] (21).
- 줄 안내 591: 영상은 글자가 차례로 써지는 중 (5612), 엔진은 0.5 초에 이미 전부 — FLP 진행 속도 (21 "30Hz 가능성") [미확인].
- r9 0.8–1.3 초 카메라 전환이 건물 벽을 지난다 — 영상 간격 (4 초) 으로는 대조 못 함 [미확인].
- 지붕 상자 (고르곤의 눈, 5133–5145), 비계 (5556), 미노타우로스 처형 (5426) 은 엔진으로 몰지 않았다.
- 업그레이드 뒤 블레이드 라이트 (LeftBladeLight 등) 는 레벨 적재 때 WAD 것 [근사].

### 17.7 증거 (`work/verify/athn03_streets/`, 커밋 안 함)
`01_archer_arrows_hit_9.3s` · `02_gorgon_from_athn03c_slot_53.7s` · `03_gorgon_archers_ares_59.0s` · `04_archer_trail_dotted_69.7s` (r10) · `05_upgrade_new_moves_18.0s` ·
`06_upgrade_blades_lv3_19.3s` · `07_after_upgrade_R_WPN0_2_24.7s` (r12) · `08_rope_grab_msg591_1.0s` (r11) · `09_athn04a_arrival_0.0s` · `10_plaza_zone_title_8.0s` (r9),
시트 `sheet_streets_r10_35-70s` · `sheet_upgrade_r12` · `sheet_athn04a_r9`.

## 18. QA vs PS2 영상 — 대리석 실내 ATHN04B · 광장 지붕·해치 ATHN04A · 해치 아래 ATHN04C (영상 5846–6461 초, 2026-10-24)

광장 키클롭스 싸움 (§17) 뒤: ATHN04B 실내 → 여자 추격·투신 → 광장 발코니 세이브 제단 → 상자 (불사조 깃털) → 웅덩이 시신의 열쇠 → ATHN04B 두 번째 (방패 해골 · 궁수 ·
고르곤) → 발코니 → 지붕 길 → 해치 (열쇠) → ATHN04C 아래층 세이브 → 창살. 엔진 = origin/main b4b50e2 (§17) 위, 녹화 lockstep. 영상 난이도는 **보통 (전사)** —
경로·스크립트 대조는 쉬움 (r9–r13) 으로 했고, 전투가 걸린 항목 (방패 해골 안내 519, 두 번째 방문 스폰) 은 보통 (r14) 으로 다시 봤다 (§18.6).
- r13 (≈ 이전 r9) 실내·투신·깃털·열쇠·두 번째 방문: `--game ATHN04A --arrive ATHN04B@1880,-76,1140,90 --skip-movies`, `-Dgow.arrive.state=ATHN04A:L0=3,
  F=DMCsqr-DMC-TriggerVol0-PlayerEntry,F=TPSpawner-pCube4411-EntrySensor1` (광장 결투 끝), 경로 `2000,-76,1140;…;2336,-80,1400;…;1485,112,2092!;wait:9;
  1440,112,2000;1440,200,1780;1440,304,1600;1440,304,1408;wait:4;1440,304,1150;1440,308,1080;1500,304,1030;wait:14;1568,320,1040;wait:2;1667,249,1000!;use:1667,1024;
  hold:4;wait:3;1456,-80,880!;wait:1;use:1456,849;wait:7;…`, 1/2 초 간격 260 초. r14 = 같은 경로 보통 + 창살 손잡이 영역 (`1248,-80,1185,8;use:1248,1211`).
- r10 두 번째 방문 (자유 봇): `-Dgow.arrive.state=ATHN04B:GDoorKey=true,GShesDeadJim=true,L0=true;ATHN04A:…`.
- r11 (발코니 제단 → 지붕 → 해치 → 아래층 세이브 → 창살): `--game ATHN04A --arrive ATHN04B@1500,304,1035,0 --skip-movies`, `-Duser.home=<scratch>`
  (세이브를 실제 디렉터리에 쓰지 않게), `-Dgow.arrive.state=ATHN04A:L0=3,L3=true,F=…,GDoorKey=true,GShesDeadJim=true;ATHN04B:GDoorKey=true,…`, 경로
  `wait:1;1545,304,1045;use:1552,1027;wait:2;1320,304,1030;1150,304,1030!;1150,304,1150;1182,304,1247,6;use:1202,1247;wait:4;use:1202,1247;mash:6;wait:3;1236,-80,1244;wait:3;
  1240,-80,1300;1249,-80,1420;use:1249,1442;wait:2;1232,-80,1232,4;use:1232,1200;mash:6;wait:3;1232,-80,1100;1232,-80,1000;wait:3`, 1/4 초 간격. r12 = r11 1/2 초 간격.

### 18.1 장면 대응 [확실: 스크립트·데이터 / 영상 대조는 섬네일 (2–3 초 간격)]
레벨 연결: ATHN04A ─(걷기 이음, (2336, −80, 1400))→ ATHN04B ─(걷기 이음, 발코니 (1568, 320, 1040))→ ATHN04A; 해치 구멍 Load4CTop (LoadWad Athn04C) →
떨어지며 ATHN04C LoadCheck4CEntity (LoadCheck = 04C 현재) → 아래층 (04A·04C 가 같은 바닥을 겹쳐 가짐, 창살 LiftDoor13 · 세이브 SaveGameZone 은 04A 것).
| 영상 | 원작 (스크립트·데이터) | 엔진 (게임 시각, 녹화) |
|---|---|---|
| 5846–5896 | ATHN04B 대리석 홀 · 복도 (시민 aoXSC 템플릿 328) | r13 8.6 초 이음 → 04B, 9–46 초 |
| 5902–5911 | 여자 컷신 (자막 "극악무도한 놈! - 기다려!", "저리가! …") | `--skip-movies` 로 건너뜀 [미대조] |
| 5914–5926 | 추격 · 계단 | r13 55–78 초 (1440, 112 → 304) |
| 5929–5935 | 난간 LedgeJumpCams → Idle + **SuicideCam** (0x14FE, JumpPan 11 초): 내려다본 원형 바닥, 피 웅덩이의 보라 옷 | r13 89.2 초 SuicideCamOn, 크레인이 몸 높이까지 내려가 내려다봄 (`01`) — 수정 전엔 영웅을 봄 (§18.2-1) |
| 5938–5941 | 어두운 석상 홀 → 발코니 세이브 제단 빛기둥 | r13 92.8 초 이음 → 04A, 제단 빛기둥 · R2 |
| 5944–5950 | 저장 질문 → 메모리카드 "저장하는 중" → 빈 Ω | r11 1.2 초 (질문 → 카드 21 → 파일 22 → 덮어쓰기 15 → 17 → 18) — 수정 전엔 Ω 가 남음 (§18.2-5) |
| 5953–5956 | 초록 빛 상자 Chest5 (CSM 11) → "피닉스의 깃털을 획득했습니다. 마나를 증가시키려면 2 개를 더 찾아야 합니다" | r13 96–100 초 카드 4 (`02`, 하네스는 깃털 0 개에서 시작해 "5 개를 더") — 수정 전엔 상자만 열림 (§18.2-3) |
| 5962–5965 | 웅덩이 시신 R2 → 질문 1014 "주머니에 있는 열쇠를 가져가시겠습니까?" → 메시지 125 + 그림 MAT_FlashMat125 (금 열쇠) | r13 104–111 초 질문 (`03`) → 카드 8 + 금 열쇠 (`04`) (§18.2-3) |
| 5971 | 창살 남쪽에서 R2 안내 — DoorHandleZone (EventSensor 모드 2, PromptOnEnter, LevelData6 ≤ 1) → R2 면 메시지 274 "문을 열기 위한 장치가 있습니다만 반대쪽에 있습니다" | **다름**: 영역 밑이 바닥 +6 (y −74) 이라 발 (−80) 로 판정하는 엔진은 영역에 못 들어간다 — R2 안내·274 없음 (r13 · r14) [미수정, §18.4] |
| 5974–5989 | 나무문 · 석상 · 웅덩이 · 복도 | r13 115–134 초 |
| 6020–6030 | ATHN04B 두 번째: 방패 해골 (R_SKB 24) · 막기 8 번째 → 첫 안내 19 → 519 "마법 방패는 …" | r13 134.7 초 이음, 137–140 초 519 (`05`) (§18.2-4) |
| 6228–6244 | 받침대 상자 (붉은 오브) | 주행 안 함 [미대조] |
| 6260–6300 | 밤 안뜰 · 사다리 · 나무 · 고르곤 (R_MEW 60, 초록 시선) | r10 고르곤 스폰 (1952, 2, 2048) · (1578, −80, 2309) …, 첫 안내 1 → 501 (메두사 목) |
| 6416–6430 | 발코니 제단 R2 (오른쪽 아래 금 투구 아이콘 — 5953 상자 앞에도) | r11 0–1 초 (R2 · 왼쪽 아래 아이콘 같음, 금 투구 없음 — HUD CoolMeter_Helmet (700, 430) 로 보임 [추정], 미터 표시는 §15 담당) |
| 6430–6434 | 지붕 길 (나무 경사) | r11 5–7 초 |
| 6436–6444 | 해치: R2 → (질문 1007 · 자물쇠 TnFcam) → 쪼그려 잡고 R2 연타 · TnFcam | r11 7.1 초 R2 → 8.35 초 질문 1007 (`06`) → 10.4 초 CDsOn → 11.7 초 잡기 → 15.0 초 열림 (`07`) — 수정 전엔 첫 R2 에 잡고 질문이 겹침 (§18.2-6) |
| 6446 | 열린 구멍, 뚜껑은 왼쪽에 서 있음 | r11 18 초 (`08`) |
| 6448 | 아래층 창살 (아래에서) | r11 22 초 떨어짐 → ATHN04C (`09`) |
| 6450–6456 | 아래층 세이브 R2 → Ω → "저장하는 중" → "저장하기 완료" | r11 26.7 초 (`10` R2 없음 · `11` · `12`) — 수정 전엔 이웃 (04A) 세이브 포인트가 없어 R2 가 안 먹음 (§18.2-7) |
| 6458–6462 | 창살 R2 → 들어 올림 | r12 30.7 초 잡기 → 35 초 끝 (`13`) — 수정 전엔 R2 가 안 먹고 닫힌 창살을 걸어 지나감, 끝나도 창살이 안 올라감 (§18.2-7·8) |
| 6464–6470 | 창살 너머 복도 (남쪽, ATHN04A 아래층) | r12 40 초 걷기 이음 → 04A (`14`) |

### 18.2 엔진 결함과 수정
1. **크레인 카메라가 영웅을 봤다** (SuicideCam): 0x100 · 0x40000 둘 다 없는 카메라는 노드 월드 행렬로 각을 정한다 (0x1F3768 → 0x1F3904..0x1F3960 → 0x1F11A0) [확실].
   전 WAD 28 개. `CameraRig` 비추적 분기 + 다른 슬롯 카메라 관절 애니 (30 §6.7).
2. **이음마다 두 슬롯 세계를 새로 시작했다**: 원작 LoadWad 는 이미 있는 레벨이면 다시 읽지 않고 (0x1BC110), LoadCheck 는 이름만 (0x1BCA10). 엔진은 ATHN04B → 광장으로
   돌아오면 광장 시민·키클롭스 결투·동영상이 다시 돌았다 → `GameSession.residentWorlds` · `ScriptWorld.rebind/recreate` (+ Vis 상태 다시 보내기, 28 §8). 이웃 Idle
   (Internal 0xC) 도 전역 정지 (`SceneNav.Host.neighbourIdle`) — 04B 가 현재인 채 04A LedgeJumpCams 가 영웅을 세운다.
3. **상자 아이템 · 메시지 그림이 없었다**: CSM 템플릿 +0x9C 아이템 · +0xA0 지연 → AddItem 0x1D8CF0 → 카드 0x21DA08 (PickUpInfoMenu 3/4), 메시지 n 의 "MAT_FlashMat<n>" →
   카드 8 GenericPickUp 텍스처 교체 (0x1B1228 · 0x1B8178) [확실] (21 "아이템 카드").
4. **첫 안내가 없었다**: FirstTimeHint 0x1DBEB0 (2 초 · 비트 · 1 은 3 번째 · 0x13 은 8 번째) + SCR_FirstTimeMsg (0x42077801) 기술 이벤트 — R_SKB 막기 M70 idx 19 → 519,
   영웅 메두사 잡기 1 → 501, 오브 1500 → 18 (SND_LEVELUPCARD) [확실] (`PlayerState.firstTimeHint`, 세이브 "hints").
5. **저장 뒤 Ω 배경이 남고 두 번째 저장 질문이 안 닫혔다**: 상태 18 0x220740 이 10 프레임 뒤 SaveGame_Event = 0 (Animate_Off), 아니오는 곧 18, △ 는 15 로 되돌림,
   질문 열기 0x21D8B0 는 Menu_Choice · Done = 0 [확실]. 엔진은 0 을 안 보내고 Done 1 을 남겼다 → `GameScene.askSave/closeSaveMenu` (22 §7). 같은 이유로 질문 메뉴
   (0x1B9F34 Done = 0) 도 `openQuestion` 에서 Done 0 — 저장 뒤 해치 질문 1007 이 안 닫히던 것. 메뉴 동안 미니게임 아이콘 뗌 (0x1BA150 → 0x1BA338(−1)) — 예전엔 "R2" 가
   Ω 위에 남았다 (영상 6452–6456 에는 없음).
6. **해치를 열쇠 전에 잡았다**: 생성 CDsOff (Vis Attr11_0 = 2) 로 충돌이 꺼진 오브젝트는 잡기 질의 (충돌 세계 0x1C3F18) 에 안 걸린다 [확실: 질의 경로 / 근사: 추론]
   → `Interactables.Item.noCollision` (38 §23.1). 데이터 17 짝이 같은 꼴.
7. **이웃 슬롯 창살 · 세이브 포인트가 안 먹었다**: 해치 아래층은 현재 = 04C 인데 창살 LiftDoor13 · SaveGameZone 은 04A 것 — 원작은 슬롯 구분 없는 질의 [확실]. 엔진은 현재
   레벨 것만이고 이웃 행동 GO 는 몸에서도 빠져 닫힌 창살을 걸어 지나갔다 → 이웃 `Interactables` · 몸 · 세이브 포인트 Creator (38 §23.2).
8. **TAndF 창살이 안 올라갔다**: 문 ANM 에 템플릿 act (liftdoor2/liftdoor3) 가 없으면 원작은 문 Y 를 직접 — 잡은 동안 영웅 관절, 끝 = 처음 + #8743c048 × 16 (중력 50 × 16)
   [확실: 0x214058 / 0x214180] → `Platforms.lift` · `Interactables.Item.liftHeight` (38 §23.3) [근사: 관절 높이 대신 진행 비율].
9. GlobalData 이름 공유는 이 구간에서도 필요했지만 (DoorKey: 04A 1 · 04B 0, ShesDeadJim) §17-8 (b4b50e2) 구현을 쓴다 — 이 작업의 같은 수정은 버렸다.
10. 하네스 [엔진 전용]: `AutoPlay` 메뉴 ○ (FLP 확정 = ○, UiPad 0x20), 메모리카드 예/아니오 화면은 ↑ 뒤 ○ (`MemoryCardFlow.yesNoScreen`, 기본 아니오), "use" 단계는 R2 한 번
    (멈춘 사이 다시 눌러 저장 질문이 또 열리던 것), `-Dgow.arrive.state` (L/F/G).

### 18.3 시험
`AthensInteriorTest` — 저장 메뉴는 SaveGame_Event 0 이 있어야 Animate_Off (FLP_HUDK 헤드리스) · Done 되돌림, 메모리카드 예/아니오 화면, 해치 CDsOff → 안 잡힘 · CDsOn →
잡힘, 넘겨 쓴 세계의 Vis·Creator 다시 보내기, 창살 높이 80 · 들어 올리면 충돌 통과 · 이음 상태 · 끝남, SuicideCam 4 초 내려다봄 · 11 초 난간 위, 상자 아이템 11/10/없음,
첫 안내 규칙 + R_SKB M70 SCR_FirstTimeMsg 19.

### 18.4 남은 것 · 미확인
- 여자 컷신 (5902–5911) 은 `--skip-movies` 로 건너뛰어 대조하지 않았다. 받침대 상자 (6228–6244) · 밤 안뜰 사다리 (6260–6300) 는 경로로 몰지 않았다 (r10 봇 스폰만).
- 6416–6430 오른쪽 아래 금 투구 아이콘: FLP_HUDK 의 "Loading" 클립 (650, 545) 은 붉은 글자 PS2_4085 라 아니다 — 출처 못 찾음 [미확인].
- ~~**DoorHandleZone (5971) 미수정**~~ → **해결 (§22.2-2)**: 영웅 접촉 관절 = 메시 [0] +0x34 = pelvis. ENV_DoorHandleZone 은 y −74 부터 (바닥 −80, 엔진 r14 에서 영역 한가운데 (1249, −80, 1178) 에 서도 안 됨) — 원작은 영웅 스크립트
  레코드의 점 = 영웅 GO 관절 [레코드 +0x28] 월드 × 로컬 [+0x10] (0x1A6308, 레코드 초기화 0x1A5E18 ← 0x1ADD74: 관절 = 부품 +0x34 또는 오브젝트 +0x86) 로 판정한다
  [확실: 경로 / 미확인: 영웅 관절 번호]. 엔진은 발 위치라 바닥보다 위에서 시작하는 볼륨 (영웅 거름 센서 1144 개 중 ≈ 70 개가 +3 이상 — 대부분 큰 구, LoadCheck 다섯 (ATHN05A/05C LoadCheckB +5/+9, PAND01B LoadCheckC +6, PAND02B AA +10, PAND02D DD +12) 포함) 에
  못 들어가고, 반대로 꼭대기가 바닥 +10 아래인 얇은 볼륨 (ATHN01C LoadB · PAND03A CheckPointGroup …) 도 있어 고정 높이 하나로는 둘 다 맞지 않는다 → 관절을 찾기 전엔 바꾸지 않음.
- 해치 열쇠 질문 1007 · 자물쇠 TnFcam 클로즈업은 영상 섬네일 (2 초 간격) 에 없다 [미대조]. 아래층 Ω 화면 앞 카드·파일 선택 화면도 영상엔 안 보인다 (빨리 넘김 추정).
- 해치로 떨어질 때 ATHN04A ZoneReportGroup3 → 구역 제목 4 "아테네 도시 광장" 이 뜬다 (스크립트 그대로) — 영상 6448 섬네일엔 안 보임 [미확인] (구역 제목은 §16 담당).
- 이음 직후 다른 슬롯 렌더 장면을 다시 준비하는 동안 (r11 8.6 초) 그 레벨만 지오메트리를 가진 곳이 검게 보였다 (아래층 복도 = 04A 렌더) — r12 에선 없음 [미확인: 적재 속도].
- 메모리카드 화면은 4:3 FLP 라 16:9 출력에서 양옆으로 게임이 보인다 (원작 4:3 은 전체가 검다) [엔진 표시].
- ~~이웃 상자 ("chest") Creator~~ (§21.2-6), 멈추지 않는 메시지 동안의 미니게임 아이콘 숨김, 첫 안내 21 (고르곤 시선 0x1964A4), ~~열린 상자의 이음 상태~~ (§21.2-6) [근사/미구현].
- 창살이 영웅 손 관절 대신 진행 비율로 올라간다 [근사]. 넘겨 쓴 세계에서 다시 낸 적은 체력이 처음 값 [근사].

### 18.5 증거 (`work/verify/athn04b_interior/`, 커밋 안 함)
`01_suicidecam_body_r13` · `02_feather_card_r13` · `03_key_question_1014_r13` · `04_key_card_125_r13` · `05_hint_519_shield_r13` · `06_hatch_key_question_1007_r11` ·
`07_hatch_lift_TnFcam_r11` · `08_hatch_open_r11` · `09_lower_gate_from_below_r11` · `10_save_question_no_R2_r11` · `11_memcard_saving_r11` · `12_save_done_r11` ·
`13_gate_lifting_r12` · `14_corridor_past_gate_r12`, 수정 전 `before_omega_stuck_after_save` (저장 뒤 Ω · R2 남음) · `before_gate_not_rising_sheet`, 후 `after_gate_rising_sheet`.

### 18.6 보통 난이도 재확인 (영상 = 전사 (보통), `-Dgow.difficulty=1`)
r14 (r13 경로 보통): 이음·SuicideCam·깃털·열쇠 질문/카드·두 번째 방문 방패 해골 스폰 (R_SKB 24 셋, 이웃 슬롯 스폰 → 이음 뒤 그 자리 다시) · 첫 안내 519 가 쉬움과 같은 순서로 났다.
r11 보통 (해치 → 아래층 저장 → 창살) 도 같다. 처치 시간·받는 피해·AI 간격은 이 구간에서 대조하지 않았다 (전투 판단 없음). r14 의 한 번 사망은 경로 순간이동이 바닥 없는 곳에 둔 것 (하네스) [엔진 전용].

## 19. QA vs PS2 영상 — 아테네의 지붕: ATHN04C 나무 창살 → ATHN05A 마당·덩굴 통로·술집 → ATHN05B 발코니 (영상 6461–7351 초, 2026-10-24)

§18 창살 (6458–6462) 뒤: 남쪽 복도 → 불 켜진 방 → ATHN04C "Slow Lift Gate" (R2 · 연타) → 굽은 복도·계단 → ATHN05A 마당 (구역 제목 5 "아테네의 지붕") →
불타는 덩굴 통로 (뒤로 더블 점프 안내 599) → 불타는 문간. 엔진 = origin/main e442777 (§18) 위, 녹화 lockstep 1/4 초 (c2 만 1/2 초). 이 부분엔 전투가 없어
쉬움으로 몰았다 (영상은 보통). 6651 이후 (술집 · 군단병 · 키클롭스 · 붉은 장벽 · ATHN05B 기둥·체크포인트 · 세이브 · 하피) 는 §19.4.
- c2 (수정 전): `--game ATHN04C --arrive ATHN04A@1232,-80,1301,0 --skip-movies`, 경로 `1240,-80,1440;1150,-80,1470;1000,-77,1470;950,-77,1550;944,-77,1800;
  944,-77,2100;944,-77,2280;1000,-77,2400;1024,-77,2600;1024,-77,2800;use:1024,2900;mash:4;wait:2;1024,-77,2950;1000,-77,3030;850,-30,3030;700,80,3030;wait:8`.
- c5 (창살): `--game ATHN04C --arrive ATHN04A@1024,-80,2400,0 --skip-movies`, 경로 `1024,-77,2600;1024,-80,2866!;wait:0.5;use:1024,2950;mash:6;wait:1;1024,-77,2960;
  1000,-77,3030;wait:3;1024,-77,2900;wait:3`. f3 (창살 → 마당 제목) = 같은 시작 + `…;1000,-77,3030;850,-30,3030;700,40,3030;600,-16,3010;540,-16,2990;500,-16,2900;wait:8`.
- w6 / w7 (덩굴 통로): `--game ATHN05A --arrive ATHN04C@600,-16,3010,90 --skip-movies`, 경로 `520,-16,2900;480,-16,2864;push:1.5:300,2864;stick:6:0,1;stick:6:-1,0;
  stick:2:0,1;stick:0.05:0,-1:CROSS;stick:0.25:0,0;stick:0.5:0,0:CROSS;wait:1;stick:5:0,1;wait:3` (w6 은 뒤로 더블 점프를 한 번 더 + `stick:8:0,1;wait:2;push:2:700,3000`).
  새 단계 `stick:초:x,y[:버튼]` = 스틱 그대로 (첫 프레임에 버튼). 공중에서 스틱을 뒤로 계속 당기면 공중 회전으로 몸이 돌아 맞은편 벽을 놓친다 — 손 조작처럼
  점프 직후 스틱을 놓는다 [엔진 전용: 하네스].

### 19.1 장면 대응 [확실: 스크립트·데이터 / 영상 대조는 섬네일 (2–10 초 간격)]
레벨 연결: §18 창살 (ATHN04A LiftDoor13, TAndF 400) ─ 남쪽 복도 (04C 현재, 04A 이웃) ─ ATHN04C 불 켜진 방 · Slow Lift Gate (LiftDoor13, TAndF 7) · DoorVolume ─
굽은 복도 · 계단 (04C LoadCheck5A · Sensor3 LoadWad Athn05A, 05A LoadCheckC) → 걷기 이음 → ATHN05A 마당 (ZoneReportGroup: GlobalData0 ≠ 5 → Internal 0x10(5) →
메시지 805 첫 줄, GlobalData0 = 5 라 한 번) → 덩굴 통로 (x = 450 · 606 덩굴 벽, BackJumpMsgGroup → ShowMessage(599, −1)) → 위 발코니 · 불타는 문간.
| 영상 | 원작 (스크립트·데이터) | 엔진 (게임 시각, 녹화) |
|---|---|---|
| 6471 | 창살 남쪽 복도 (횃불 기둥) | c2 0–8 초 |
| 6491 | 불 켜진 방 (기둥 횃불, 바닥 시신) | c2 9–11 초 — 기둥 옆 폭발 불꽃이 보인다. 영상 6481–6521 은 "컨트롤러 제거" 화면이라 대조 못 함 [미대조] |
| 6527–6533 | Slow Lift Gate: R2 → 잡기 (ANITandF1 → 2) → R2 연타 → 가로대를 가슴 (6531) → 양손 머리 위 (6533) | c5 2.0 초 R2 → 2.5 초 잡기 → 연타 → ≈ 6 초 머리 위 · 끝 (`01` · `02`), 문이 손을 따라 오른 뒤 0.35 초에 80 — 수정 전 (c2) 문이 안 움직여 못 지나감 (§19.2-1) |
| 6535–6537 | 문 너머 굽은 복도 · 계단 (HUD 없음) | c5 7–21 초, f3 10–13 초. 문 너머 DoorVolume 을 나가면 1015 → 문이 떨어짐 (f3 11.3 초) |
| 6539–6545 | ATHN05A 마당 · 위쪽 가운데 "아테네의 지붕" (6539 → 6545 이상) · 철책 너머 불 | f3 12.78 초 제목 (05A 는 아직 이웃 슬롯), 13.9 초 걷기 이음 → 05A 에서 이어 23.5 초까지 (`03` · `04`) — 수정 전 제목 · 불 없음 (§19.2-3·4), 이음에서 제목이 꺼짐 (§19.2-5) |
| 6551–6571 | 마당 · 철책 너머 불길 · 덩굴 벽 | w7 0–5 초 |
| 6575–6578 | x = 450 덩굴 벽 오르기, 아래 불 · 위 발코니에서 싸우는 병사 (aoRED 아테네 병사 대 aoSKS 해골병, Creator 연출 여섯 쌍) | w7 5–14 초 (`05`), w8 10–13 초 발코니 싸움 (`08`) — 수정 전 병사 없음 (§19.2-8) |
| 6581–6584 | 양피지 상자 599 "뒤로 더블 점프를 하려면 왼쪽 스틱 (아이콘) 을 당기고 ✕, ✕을 누르십시오." (상자 동안 HUD 없음, 6584 사라짐) | w6 16.85 초 BackJumpMsgGroup → 599, w7 17 초 (`06`) — 스크립트 ShowMessage 경로 (FirstTimeHint 아님) |
| 6591–6651 | 뒤로 더블 점프 → 맞은편 x = 606 벽 → 다시 뒤로 → 위, 불타는 문간 (6651, 왼쪽 아래 창 든 병사) | w6 17.5 초 x = 606 벽, 24.5 초 다시 x = 450 벽, 33.5 초 꼭대기; w7 23–29 초 불타는 문간 (`07`), w8 22–28 초 문간 앞 병사 싸움 (`09`) — 수정 전 (w1–w5) 맞은편 벽 아래 띠 위에 서고 (§19.2-2) 문간에 불·병사 없음 |
| 6661–6671 | 통로 위 발코니의 초록 상자 (ChestGroup-chest12, CSM 4 체력) — 옆에 파란 상자 chest13 (CSM 2 마법) | k1 0 초 두 상자 (`10`), 1.1 초 R2 → 4 초 초록 오브 (`11`) — 수정 전 두 상자가 원점 (0, 0, 0) 에 생겨 없었다 (§19.2-7) |
| 6681–6704 | 술집: 동영상 SKDIntro (24.8 초: 3 초 항아리 옆 크라토스, 7–9 초 블레이드 근접 (6687), 11 초 바닥 핏자국 (6689), 13 초 막대에 매달린 시체 (6693), 15–17 초 갑옷 언데드 (6695·6697), 19 초 크라토스 (6699), 21–23 초 언데드 얼굴·셋 (6701·6703)) — DMCSpawnerPlayMovieNew: FadeOut 1 초 → PlayMovie → MPEGdone (501, "SKDIntro") → FadeIn 1 초 · LowerBarDive 셋 · 장벽 | b2 1.92 초 볼륨 → 페이드 → SKDINTRO.PSS (`12`, 같은 장면 순서 [확실: video:frames]) — 녹화 lockstep 에선 동영상이 느리게 돈다 [하네스]. b3 (--skip-movies) 1.93 초 MPEGdone → 2.45–2.95 초 R_SKD Dive 셋 → 4.42 초 DMC 장벽 dogIn |
| 6705–6851 | 술집·갑판 싸움 (보통): Dive (R_SKD 37) · Skelly (R_SKC 22) · 궁수 (R_SKA 31) · 키클롭스 (R_CY2 41, 6821–6851), 붉은 장벽 문 (6871·6911) | b3 (보통, 봇) 2–120 초: 장벽 붉게, Dive 다시 남 (20.8 · 43.5 초 처치), 43.5 초 Skelly 둘 · 궁수 둘 (`13`) — 봇이 느려 처치 시간·키클롭스는 대조 못 함 [미대조] |
| 7001 · 7045–7053 | ATHN05B 발코니 (문간 옆 상자 Chest141 · 횃불): CheckPointGroup 구 → 7047 기둥으로 뛰다 7049 붉은 화면 → 7051 "YOU ARE DEAD" → 7053 같은 발코니 상자 옆에서 다시 시작 | d3: 1.78 초 CheckPointGroup → 체크포인트 (71, 448, 3805) (`14`), 10.2 초 떨어져 죽음 → 10.5 초 그 자리 · 그 방향, 카메라는 7053 처럼 문간 쪽 (`15`) — 수정 전 방향 = 레벨 시작 방향 (§19.2-9). 붉은 화면·사망 메뉴는 하네스가 건너뜀 [미대조] |

### 19.2 엔진 결함과 수정
1. **ATHN04C 나무 창살이 원작처럼 오르지 않았다**: 리프트 창살 ANM 에 템플릿 act 가 없어 b4b50e2 엔진은 문을 안 움직였고 (c2: 연타 뒤에도 닫힌 채 → 경로 순간이동),
   e442777 (§18.2-8) 은 높이 = 위치 × 80 으로 근사했다. 원작은 템플릿 +0x158 (#a6e81c1e) 비트 0 인 TAndF 를 위치 모드로 몬다: 붙은 동안 문 y = 처음 + 영웅
   synchJoint (관절 +0xEA) 의 모델 공간 y (0x214058), 영웅 ANITandF2 는 min(위치, 1) × 길이로 세운 자세 (0x1ECA44 → 0x1F77B4, 속도 0), 완료 (2.0) 면 Gravity 50 × 16 으로
   처음 + 80 까지 (0x214180), 완료 전에 놓으면 위치·속도 0 → 같은 중력으로 떨어짐, 스크립트 1015 (0x266420 → 0x213E30) 면 떨어져 다시 잡힘 [확실]
   → `HeroNavigator.slideFollow` · `NavBody.scrub/jointModelY` · `Animator.scrub` · `Item.slideIdle` · `scriptReset` LIFT (38 §23.4). e442777 의 `liftHeight` ·
   `Platforms.lift` · `physicalLift` · 이음 상태는 그대로 쓴다. 영상 6529 (잡음) → 6533 (양손 머리 위) ↔ c5 2.5 → 6 초.
2. **덩굴 통로에서 맞은편 벽을 못 잡았다**: 뒤로 점프한 크라토스가 x = 606 벽 아래 16 폭 ClimbGuide 띠 윗면 (y 205) 에 내려서 (w1–w5) 떨어지곤 했다. 원작 지상·공중
   이동 0x1EA5F0 은 벽에 붙어 있지 않으면 바닥 질의 (0x1EA8D4 → 0x1E3298) 마스크에 ClimbGuide 0x20000 을 넣는다 (0x1EA6A0 – 0x1EA6B8) [확실]
   → `NavWorld.FLOOR_EXCLUDE_STATIC` 에 ClimbGuide (38 §18.4). 위를 보는 ClimbGuide 면은 전 레벨 358 (05A 146).
3. **ATHN05A 불벽이 하나도 없었다**: 철책 너머 불 (6541), 통로 바닥 불 (6578), 불타는 문간 (6651) 은 커브 이미터 (FXC 서브타입 4, `FXC_Fwemit*` 72 개) 인데 엔진은
   서브타입 3/4 를 통째로 건너뛰었다. 이미터 +0x6C → 0xD 방출 지오메트리 노드 (+0x08 관절, +0x10 로컬, +0x58 NCV, +0x70 모양) → NCV 커브, 생성 0x1249C8
   (t 균일 · P(t) · 방향 dir + T·접선 속력 + N·법선 속력 · 속도 = 방향 × 속력) [확실: 연결·흐름 / 근사: 표본 보간 · 퍼짐 원뿔] → `FxResources.geoms` ·
   `LevelEmitters.curveSamples` · `ParticleSystem.spawnOnCurve` (16 §2.5). 전 게임 커브 이미터 564 개 (05B 15 · 05C 36 …) 가 같이 켜진다.
4. **"아테네의 지붕" 이 안 떴다**: 계단 위 ZoneReportGroup 은 ATHN05A 스크립트인데 그 순간 05A 는 이웃 슬롯 (현재 04C) — 엔진 이웃 리스너는 Internal 0x10 을 버렸다.
   원작 GameMgr 구역 제목 0x1B0A28 → 0x1B60D0 → 0x1B8BA0 은 슬롯을 보지 않는다 [확실] → `SceneNav.neighbourInternal` (0x9 · 0xC · 0x10) → `Host.neighbourZoneReport` (28 §8).
5. **이음에서 제목이 꺼졌다**: 제목 1.1 초 뒤 걷기 이음 (f2 13.5 → 14.5 초) 의 새 씬 HUD 는 처음 상태라 제목이 사라졌다. 원작 HUD 는 하나라 StatsZoneReport (페이드 인
   9 프레임 + Loop_Stats 10 초) 가 이어진다 → 이음 직전 10 초 안에 띄운 제목을 새 씬이 다시 띄운다 (`GameSession.seamZone`) [근사: 페이드 인부터 다시라 이음까지의 시간만큼
   길다]. 영상 6539 → 6545 이상 ↔ f3 12.8 → 23.5 초.
6. 하네스 [엔진 전용]: `AutoPlay` 경로 단계 `stick:초:x,y[:버튼]`.
7. **Creator 가 만든 상자가 엉뚱한 자리에 생기거나 아예 안 생겼다**: 원작 Creator 생성 0x1ACCA8 은 새 GO 행렬을 0x1A9CD0 로 만든다 = 엔티티 레코드 행렬 (+0x00) ·
   소유 GO 관절 (+0x40) 월드, 회전 행 정규화 [확실]. 엔진은 소유 GO 인스턴스 행렬을 써서 묶음 GO (ATHN05A gochestgroup: 인스턴스 원점, 관절 0 = (608, 640, 3008)) 의 상자
   둘이 원점에 생겼다 — 영상 6661–6671 의 발코니 초록 상자. 전 레벨 상자 24 개 (05B SecretChests 2 · 05BB 5 · 05C 6 · PAND03A/03B/04AA 6 …) · PAND02C 세이브 포인트 하나가
   그랬다. 또 오브젝트 이름은 레지스트리가 대문자로 바꿔 해시하므로 (0x17A7B8 → 0x175C10 → 0x1759F0 → 0x175938 + Hash127) 대소문자 무시 [확실] — Name0 "Chest" 40 개
   (DEST01 8 · HDES01B 8 · ATHN06B 6 · ATHN05B RespawnChest1 · 05C RaidenChest …) 는 `"chest".equals` 로 상자가 아예 없었다
   → `SceneNav.creatorMatrix / creatorIs / creatorKey` (묶음 상자마다 키 "gochestgroup/chestgroup-chest12"), 세이브 포인트도 같은 행렬.
8. **Creator 연출 오브젝트가 없었다** (0x1ACCA8 은 이름을 가리지 않는다): Name0 앞에 "go" 를 붙인 레벨 WAD 오브젝트 원형 (05A 원점의 goaored · goaosks · goaoskb ·
   goaoxy1) 을 위 행렬에 새 GO 로, 처음 act = Name1 (0x1AD110: 오브젝트 애니에서 Name1 의 대문자 해시 0x1758E0 와 같은 act 번호), 오브젝트 그룹 = Creator Filter
   (슬롯 16) → 같은 Filter 의 Animator (모드 3, 지연 0.5–2 초) 가 act "aoRED1a05Hl05" / "aoSKS1a05Hl05" (4.33 초 반복) 를 다시 튼다 [확실: 생성 경로]. ATHN05A 덩굴 통로·
   발코니·문간의 병사 대 해골병 싸움 여섯 쌍 + HL11 셋 (볼륨 진입 때) 이 예전엔 하나도 없었다 (영상 6575–6584 발코니 병사, 6651 문간 병사). 전 게임 aoRED 41 · aoSKS 27 ·
   aoREDCAGE 17 · aoHAR 13 … → `GameScene.spawnCreated / animateCreated` (이펙트 GO, `ScriptWorld.addActor`), `Effect.loopAct` (Animator 비트 0x2)
   [근사: CDV 충돌 없음, Animator 속도·역재생 비트·슬롯 unk17 무시].
9. **체크포인트 재시작 방향과 거름**: 원작 CheckPoint (GameMgr 메시지 0, 0x1B0234) 는 체력 (+0x178) ≥ 1, 세계 시작 (0x29D920 − 0x29D924 ≥ 1 초 — 0x29D924 는 세계 초기화
   0x1A6EC0 (레벨 적재) 만 0 으로, 걷기 이음은 그대로), 같은 엔티티 (0x303D74) 는 5 초 (0x303D78) 안 다시 안 받음 → 0x29D960 = 1 → 프레임 끝 메모리 스냅샷 (플레이어 월드
   행렬 포함, 11 §4·§5.3) [확실]. 엔진은 조건 없이 위치만 저장하고 레벨 시작 방향으로 되살렸다 → `GameScene.acceptCheckpoint` (`GameSession.worldStart`), `checkpointYaw`
   (사망 재시작 · 일시정지 메뉴 재시작 · 저장 뒤) [근사: 세계 상태 (적·스크립트) 는 되돌리지 않음].

### 19.3 남은 것 · 미확인 (6461–6651)
- ~~6575–6584 위 발코니의 군단병 둘~~ → §19.2-8 (Creator 연출 병사).
- 마당·통로의 **검은 원반**: 불 옆 `FXC_FPemit*` (구 이미터, PTC 종류 4 원반 — 중심색 (0,0,0) → 짙은 회색, 중심 α 151.7/128 ≈ 1.19, 테두리 α 0, 수명 9–11 초,
  알파 블렌드 0x44) 이 검은 연기 덩어리로 그려진다 (FPemit 만 빼면 사라짐, `16_fp_smoke_puffs_*`). 영상 6541–6545 의 철책 너머 불엔 안 보인다.
  ~~원작 프레임 순서: 안개 노드 = 목록 0 · 버킷 7 (0x12A210, vtable 0x2BDC10)~~ → 바로잡음 §24: 안개 노드 (vtable 0x2BDC38, 렌더 0x1281C8) 는 0x125D08 이
  **층 2 · 칸 5 · 키 0x7100** 으로 넣는다 (0x12A210 은 거울 관리자의 층 0 칸 7 노드). 결론 (안개가 파티클 위에 깔려 깊이를 쓰지 않는 파티클이 뒤 장면 깊이의 계수를
  받는다) 은 같다 → **수정** (§24, `SceneFog`): FPpart 원반이 먼 배경 앞에서 안개색으로 묻힌다.
  불벽 연기 커브 `FXC_Fwemit4*` (`FWpart4`, 가산 0x48) 는 주황 빛이라 어두워지지 않는다.
- 통로 위쪽 주황 갑옷 거인 (BARSCEAN = Ares, (240, −320, 4630), ChkptTurnOffAres 볼륨에서 숨김) 이 엔진 w6/w7 위쪽에 밝게 보인다 — 영상 6651 위쪽은 어두운 갈색 덩어리라
  같은 것인지 판단 못 함 [미확인].
- 이음 순간 0.4 초 페이드 아웃·인 (엔진 구조, 원작은 끊김 없음) [엔진 표시]. 구역 제목이 이음까지의 시간만큼 길다 [근사].
- 영상 6527–6533 의 연타 속도는 모름 — 엔진 c5 는 초당 10 번 (TF Time 0.2 안) 으로 최대 속도라 4 초 [근사: 입력].

### 19.4 6651 이후 — 경로와 남은 것
- 녹화: w8 = w7 경로 (Creator 병사), k1 = `--arrive ATHN04C@615,624,2995,180` 경로 `wait:1;615,624,2985;use:615,2949;hold:4;wait:4;615,624,3030;use:615,3066;hold:4;wait:2`,
  b2 (동영상 켬) / b3 (--skip-movies, 보통 `-Dgow.difficulty=1`, 봇 전투) = `--arrive ATHN04C@615,624,2995,90` 경로 `660,624,3000;720,624,3000;800,628,3060;900,628,3090;wait:…`,
  d3 = `--game ATHN05B --arrive ATHN05A@60,448,3830,90` 경로 `wait:1.5;114,452,3773;wait:2;220,452,3700;320,452,3650;wait:10`.
- 술집 동영상 볼륨 (TriggerVol y 608–800, (848, 3072) 근처만) 은 좁아 경로 도착 허용 40 이 그 앞에서 멈췄다 (b1) — 하네스 [엔진 전용].
- s1 (05B 세이브 방, `--arrive ATHN05A@-880,484,3640,270`): Chest140 (CSM 8 RaidenChest1, 종류 5) 이 파랑 → 빨강 → 초록으로 돈다 — 영상 7104 초록 · 7108 파랑 열기와 맞다.
  세이브 포인트 (−1341, 480, 3675) + 빛기둥. 세이브 메뉴 흐름은 §18 에서 확인.
- h1 (05C, `--arrive ATHN05B`, 보통, 봇 90 초): 묶음 상자 (ChestGroup-Chest1 · SecretRoofChests 다섯 · RespawnChest1-RaidenChest) 가 제자리, Creator 병사 p01HL02d 한 쌍,
  미노타우르스 (R_MIN 50) · 하피 (R_HAR 70) · 궁수 (R_SKA 32). 45 초 첫 안내 7 → 메시지 507 "미노타우르스를 죽이려면 ○ 을 빠르게 …", 52–56 초 미노타우르스 처형 (영상 7191 처럼
  등에 올라탐). 처치 시간·하피 공격은 대조 못 함 [미대조]. 05C 하늘에도 FPemit 검은 연기 원반이 보인다 (§19.3).
- 몰지 못한 것 [미대조]: 키클롭스 잡기 (6851·6901 ○), 장벽 문이 열리는 처치 수 (LevelData5 = LevelData6), 05A 기둥 오르기 (6961–6991) 와 05B 기둥 (7055–7091), 불 함정 (7101),
  마법 상자 (7108), 05B 세이브 제단 (7120–7148), 05C 하피·미노타우르스 (7160–7351), 사망 붉은 화면 (7049) · 사망 메뉴 (하네스는 건너뜀).
- 체크포인트 재시작은 위치·방향·체력만 (원작은 스냅샷 역직렬화 + 레벨 다시 적재 — 적·스크립트·상자 상태도 되돌린다) [근사]. 재시작 자리가 체크포인트 구 안이라 엔진은
  EntrySensor 가 다시 발동해 같은 자리를 다시 저장한다 (원작은 볼륨 안 상태도 복원) [근사].

### 19.5 시험
`SlideDoorTest` (5, 38 §23.4), `ClimbGuideFloorTest` (2: 띠 윗면은 바닥 아님, 실제 05A 충돌로 x = 450 벽 → 뒤로 점프 → x = 606 벽), `CurveEmitterTest` (2: 05A 커브 72 개가 노드·커브를
찾음, 마당 바닥 높이 불 커브), `RooftopsZoneTitleTest` (2: 이웃 슬롯 05A 세계 → 구역 5 "아테네의 지붕" 한 번, 이음 창 10 초), `RooftopsCreatorTest` (4: 이름 대소문자·"go",
묶음 상자 엔티티 행렬 (614.5, 624, 2948.7)/(…, 3065.5)·키, 05B 상자 Creator 6 (예전 5), 병사 Creator Filter 605 → Animator 모드 3 이 제 짝에만 act 4.33 초 반복),
`CheckpointRulesTest` (2: 체력·1 초·같은 엔티티 5 초).

### 19.6 증거 (`work/verify/athn05_rooftops/`, 커밋 안 함)
`01_gate_grab_c5` · `02_gate_overhead_c5` · `03_title_courtyard_fire_f3` · `04_title_after_seam_f3` · `05_shaft_climb_fire_w7` · `06_backjump_msg599_w7` · `07_burning_doorway_w7` ·
`08_ambient_soldiers_balcony_w8` · `09_ambient_fight_doorway_w8` · `10_balcony_group_chests_k1` · `11_green_chest_open_k1` · `12_skdintro_movie_b2` · `13_bar_fight_dmc_doors_b3` ·
`14_05b_balcony_checkpoint_d3` · `15_05b_restart_d3` · `16_fp_smoke_puffs_all_noFP_noFPFW` (왼쪽 전부 · 가운데 FPemit 뺌 · 오른쪽 FPemit·Fwemit4 뺌),
수정 전 `before_gate_not_rising_c2` · `before_no_fire_doorway_w6` · `before_climbguide_band_w3`.

## 20. ANM 디스크립터 배치 전역 전환 — 영웅·적·레벨 GO·카메라·이펙트가 한 경로 (2026-10-24)

§16.2-7 에서 관절 카메라·관절 이미터 이펙트에만 쓰던 원작 배치 (act 디스크립터 = 세트 항목 p2 의 합, 05 §2.8.1) 를 모든 소비자로 넓혔다.
엔진 = origin/main e442777 위. 측정 도구 `anim:layout` (`LayoutDiff`, 예전 해석은 비교 전용으로만 남김), 증거 `work/verify/anm_layout/` (커밋 안 함).

### 20.1 바꾼 것 [확실: 코드 + 전 WAD 데이터]
- `Animation` 하나의 경로: `sample`/`bake` = 타입 0 디스크립터 0 회전 + 1 이동 (+ p2 ≥ 4 면 3 로케이터, 같은 이동 배열 — 0x1146C4) + 2 스케일, `hiddenJoints`/`byteChannel` = 타입 5 u8,
  `channel(id, …)`/`hasChannel` = 타입 10 (원작 채널 id). 영웅·적 자세, 루트 모션, 히트 볼륨, 블레이드 로케이터, 레벨 GO (시작 자세·반복 재생·Animator·부모 링크), 관절 카메라,
  이펙트 GO, 잡기·동기 관계 (synchJoint) 가 모두 이것을 쓴다 (`sampleRecords`/`recordChannels`/`channelGroups`/`EffectDef.rate(…, records)` 없앰).
- 없앤 착시 보정: "타입 11 = 이동 (타입 5 없을 때)", 운반 관절 "Anim" (바인드 + 채널), 히어로 "타입 10 슬롯 1/2" 이름 규칙, 선창 히드라 X 축 50.7° 들어 올림 (`hangLift`·`setRollFix`).
- **스케일 = 원작이 쓴다**: 0x111760 → 0x231B50 (s16 × (π/2)/4096, 2607.6 = 1.0) → 재생기 +0x64 · 비트 +0x170 → 객체 +0x184 → 행렬 함수 표 0x29C890 의 스케일 칸 (0x10B488 …)
  이 회전 행 i 에 스케일[i] 를 곱함 (0x10B8A0). 엔진 `Animation.scaleTracks` → `Pose.scale` → `Skeleton.localMatrix` (05 §2.8.1).
- 값 채널 시간 = 그 디스크립터의 frameTime (0x268948) — 부서지는 물체·석화 파괴 407 디스크립터 (회전 1/15 · 채널 1/30) 의 곡선이 제 속도.

### 20.2 예전 배치에 맞춰 둔 값 7 개 → 원작 값
| 시험 | 예전 값 | 원작 값 | 근거 |
|---|---|---|---|
| `RootMotionTest.holdHydraHeadUprightInEveryClip` | 골반 앞 X 50.7° 들어 올림 (θ = 2·atan2(421, 889)) | 보정 없음: 디스크립터 1 이 골반을 (77, 842.7, −106.7) 에 둬 대기 턱 끝 (·, 421.4, −995.6), 잡기 아닌 클립 머리 바로 섬 | [확실: 데이터 + PS2 272.5–342 초 (목이 위 선체에서 내려옴)] |
| `RootMotionTest.holdHydraStandsUprightAnchoredAndIsHittable` | 턱 끝 앞 150–360 | 150–520 (Bite2x 가 골반을 앞으로 옮기며 0.3 × 1639 ≈ 492 까지) | [확실: 데이터] |
| `GrabRelationTest.designedStartPerEnemy` | 고르곤 37.7 · 세이렌 45.9 · 새끼 케르베로스 42.2 · 케르베로스 43.3 | 26.4 · 21.7 · 34.8 · 44.2 (+ 하피 23.9), 열 종류 모두 GRAB 첫 접촉 = 설계 관계 ± 0.7 | [확실: 0x1F7110 · 0x204BF0 식 + 데이터, CDV 첫 접촉과 독립 일치] |
| `GrabRelationTest.capsuleLimitedFinishers` | 하피 = 캡슐에 막힌 처형 (설계 7.9 < 캡슐 19.6) | 하피 설계 23.9 → 해당 없음 (사이클롭스만) | [확실: 데이터] |
| `EvilKratosFightTest.hugTransfersHealth` | 영웅 = 가족 정면 ≈ 27 | ≈ 16 (가족 zero/synch = (0,0,−27.1)/(0,0,−32)) — 머리 사이 2.6 · 골반 8 (포옹; 27 이면 12.6) | [확실: 데이터] |
| `EffectTest.medusaHeadActs` | "off" 생성률 0 | 링크 3 곡선 0.5 초 반복 12.4 → 0 → 12.4, "On" 30 → 25 → 30 | [확실: 데이터 + 0x130240] |
| `CameraRigTest.eleEnterJointCameraPans` | 눈 고정 | (dd06e6e §16.4 에서 이미 정정) | [확실] |

### 20.3 효과 (`anim:layout gow-rom`, 스켈레톤과 묶인 7373 act · 9 시점)
| 종류 | act | 자세 바뀜 | u8 가시성 바뀜 |
|---|---|---|---|
| 영웅 R_HERO* | 1512 | 0 | 0 |
| 적·공용 R_* | 1878 | 558 | 203 |
| 관절 카메라 | 96 | 89 | 0 |
| 레벨 GO | 3887 | 1703 | 280 |
- 영웅은 [0(4), 5, 10×6] 이라 예전 해석이 우연히 같은 디스크립터를 읽었다: dash zeroJoint z −108.5 (최대 −114.6), evadeFront −146.3, wallJumpUp y +60.3, combo3A lWeapOH 0.3 초 z −100 — 전후 같음.
- 적 466 act 가 이동을 처음 얻었다 (고르곤·세이렌·하피·해골병 R_SKA/SKB/SK2/SK3·케르베로스 — 세트 [0(3)] / [0, 10 …]): 대기 골반이 제 높이 (해골병 24.5, 예전 바인드 29.9),
  기술 중 루트 모션 (해골병 Aswipe 앞 16.8), 하피 벽 출현 zeroJoint 7139.
- 엔진이 스스로 도는 레벨 반복 act (시작 act 반복 · 비헤이비어 없음) 중 360 개가 이제 움직인다 — 원작도 GO 리셋 0x1309C0 이 시작 act 를 걸고 애니 속도 +0x3C = 1 로 두며
  0x116EF0 이 매 프레임 시간을 늘려 반복 act 는 저절로 돈다 [확실: 코드]: PAND00B 엘리베이터 goelevatorgrpa (26.7 초에 −8386, 스크립트·BHV 연결 없음), PAND02B 땅 ground1–3 · gotit (거신 배경),
  PAND03C 굴러가는 바위 Left/Mid/RightRock1–6 (30 초, ~4640), PAND02H 칼날 Blade1–3, HDES01C–E 떨어지는 혼령 FallGuy* (4 초 1280), ATHN08B 벽 left/rightWall, DEST03 sandWedge [미확인: 영상 범위 밖 (영상은 ATHN08 까지)].
- 그 밖 큰 것: ATHN05A–C 화염탄 a03Hl08Scat* (예전 "타입 11" 이동이 값 채널까지 섞음), PAND02I Plat3 (예전 값 채널 0 이 루트 x 를 1997 옮김), HDES01B group867–875 (예전 값 채널을 이동으로 9264),
  ARENA06 항아리 u8 (온전/조각 전환 — 예전엔 둘 다 그림, 41 §1.3), PAND03A AnimHead (−192 → +160, 예전 −384 → −32) [미확인: 화면].

### 20.4 엔진 확인 (녹화, lockstep)
- **ATHN01B 선창 히드라** (`--game ATHN01B`, 봇, 12–75 초 2 fps): 새 배치는 머리가 화면 위쪽에서 목과 함께 내려와 바로 서서 물고 (`hold_hydra_engine_new`), 예전 X 50.7° 는 목이 바닥 끝 아래 물에서
  솟아 머리가 바닥 높이 (`hold_hydra_engine_old_lift`) — PS2 272.5 (등장: 머리 오른쪽 위 천장 가까이), 273–316 (위에서 내리찍는 물기), 328 (기절, 턱이 바닥), 338–342 (마무리) 는 새 배치와 같다
  (`hold_hydra_ps2_272-342`).
- PAND00B 엘리베이터 (영웅을 옆에 둠): 1–5 초 원판이 영웅 옆, 8 초부터 내려가 사라지고 26.7 초에 다시 (`pand00b_elevator_loop` 시트 — 원작 규칙대로 [미확인: 화면]).
- 해골 궁수 R_SKA Aswipe (`anim:sheet`): 새 배치는 웅크린 자세로 발이 바닥, 예전은 골반이 바인드 높이라 다리가 늘어나 보임.

### 20.5 회귀 순회 (봇 60 초, 전 레벨 89 개, 실시간 3 줄)
`-Dgow.difficulty=0 -Dgow.auto=1 -Dgow.auto.secs=60`, 전 = b4b50e2 빌드, 후 = 이 빌드. 실시간이라 봇 결과에 흔들림이 있어 차이가 난 레벨은 두 빌드로 한 번 더 돌렸다.

| | 종료 코드 ≠ 0 | 예외 | 사망 합 | 방문 합 |
|---|---|---|---|---|
| 전 | 0 | 0 | 72 | 604 |
| 후 | 0 | 0 | 72 | 591 |

| 레벨 | 전 (2 회) | 후 (2 회) | 원인 |
|---|---|---|---|
| ATHN01B | 방문 8/8 | 6/8 | 선창 히드라 머리가 원작처럼 높이 떠 있어 봇이 물기 때만 맞힘 — 180 초 실행은 95 초에 처치 · 9/9 (전과 같은 끝 자리) [엔진 전용: 봇 시간] |
| ATHN05B | 14, 14 | 9, 9 | 하늘의 하피가 이제 제자리에서 움직여 (새 이동) 봇의 "포기" 가 5 초 늦고, 그 뒤 220 아래 미노타우로스에 붙어 머묾 [엔진 전용: 봇 대상 고르기] |
| HDES01C | 7/7, 7/7 | 5/6, 5/6 | 발판 DeathPlatMain00–04 가 이제 160–240 오르내림 (디스크립터 1) [데이터대로] |
| PAND02DD | 1/1, 1/1 | 0/0, 0/0 | 두 빌드 모두 시작하자마자 영웅이 바닥 (9.67) 아래 −22 로 빠짐 [기존 문제]; 새 루트 모션으로 새끼 케르베로스 하나가 그 위까지 와서 몸 밀기가 더 아래로 민다 |
| PAND04AA | 4/5, 1/5 | 0/5, 0/5 | 흔들림 큼 (사티로스 싸움 시간), DMC 결계 dogIn/dogOut 에 스케일이 생김 [미확인] |
| ATHN05C | 사망 1, 1 | 0, 0 | — |
| PAND01B · PAND01C | 22/23 · 3/16 | 23/23 · 3/15 | 흔들림 (재실행에서 엇갈림) |


### 20.6 남은 것 · 미확인
- 영상 범위 밖 (PAND·HDES·DEST) 의 자동 반복 물체 (엘리베이터·바위·혼령 …) 화면 대조 [미확인]. 1회 시작 act (bit1) 는 엔진이 시작 시각 자세로만 둔다 — 원작은 레벨 시작에 한 번 재생 [근사, 이번 범위 밖].
- 타입 5 u8 을 읽는 원작 코드, 타입 11 값 채널 (정수 펄스) 의 소비자 [미확인]. 적·영웅에는 u8 가시성을 쓰지 않는다 (미노타우로스 도끼 손/바닥 전환 미구현).
- 반복 주기: 원작은 duration 으로 감고 (0x116EF0) 엔진은 (프레임 수 − 1) × frameTime — 한 프레임 긴 반복 act 2095 (영웅 walkBlend1 등) [확실: 차이 / 후속] (05 §5).
- 선창 히드라 830 동안 영웅 손 ↔ 머리 평균 64.5 (예전 108) — 장면별 영상 대조는 안 함.
- [엔진 전용] PAND02DD 시작 직후 영웅이 바닥 아래로 빠지는 것 (두 빌드 공통), 봇이 닿지 않는 적 (공중 하피, 아래층 미노타우로스) 에 붙어 머무는 것 (ATHN05B) — 이번 범위 밖.

### 20.7 증거 (`work/verify/anm_layout/`, 커밋 안 함)
`hold_hydra_ps2_272-342` · `hold_hydra_engine_new` · `hold_hydra_engine_old_lift` · `pand00b_elevator_loop` · `ska_aswipe_new` / `ska_aswipe_old` · `anim_layout_diff.txt` (전 act 차이 목록).

## 21. QA vs PS2 영상 — 제우스의 분노 뒤: ATHN05C 다리 → ATHN05D 복도·물 방·엘리베이터 → ATHN04A 광장 재방문 → ATHN04D 절벽 → ATHN06A 오라클의 사원 (영상 8066–8806 초, 2026-10-24)

레벨 연결 [확실: 스크립트]: ATHN05C 바닥 레버 "idiot" 다리 (8066, 다른 작업) ─ 걷기·LoadCheck ─ ATHN05D 복도 (FirstSetEnemies R_SKD 셋) · 물 방
(golocatorwater) · 둘째 복도 (SecondSetEnemies 넷) · 엘리베이터 방 WarpElevator5D (발판 PlayerEntryAtTop → LoadForWarp Athn04A · AnimDown → Marker599 가
DownVol2 → Warp) ─ ATHN04A WarpFrom5D (엘리베이터 위 홀) ─ OracleMovie (FadeOut → a04Mp01 → FadeIn · IdleOff · Reload4D · SaveGameZone2) · 들보 위
ZoneReportGroup2 → 구역 4 "아테네 도시 광장" · RubbleLadder · SaveGameZone2 ─ LoadCheck04D ─ ATHN04D 절벽 길 (Skellys R_SKC 세 스폰, 최대 4 씩) ─ LoadCheck6A ─
ATHN06A 테라스 (Chest3 CSM 002 마법 · Chest4 CSM 004 체력, ZoneReportGroup → 구역 6 "오라클의 사원") · 날개 석상 · movie1orcle (a06Mp01 → CheckPoint ·
AIArenaGoto camCenR1 · 하피 R_HA2 셋 (Respawn, 최대 5/6/5) · DMC 결계 · 다섯 처치 뒤 메두사 R_MEW 둘 · 18 처치 = LevelData7) → camDoorOff → GraveSpot →
a06Mp04 (무덤 파는 노인) → graveDig AOXDI (템플릿 309) · R2 대화 335/336.
엔진 = origin/main 0efbe1a 위, 녹화 lockstep 1/4–1/2 초. 경로·스크립트 대조는 쉬움, 전투가 걸린 절벽 (d1) · 하피 마당 (h1) · 엘리베이터 (e2/e3) 는 보통.
- e3 (엘리베이터): `--game ATHN05D --arrive ATHN05C@-940,864,1600,180`, `-Dgow.arrive.state=ATHN05D:GZoneArea=5`, 경로 `-940,864,1450;-944,864,1250;-944,864,1100,12;wait:20`.
- o2 (04A 도착 → 동영상 → 들보 제목): `--game ATHN04A --arrive ATHN05D --skip-movies`, `GZoneArea=5,GcameFromA5C=true`, 경로 `wait:2;600,140,450;600,140,700;
  900,140,710;1119,144,704;wait:6;1250,140,704;…`. o1 = 같은 도착 동영상 재생 (lockstep 은 오디오 시계라 느리게 보임 [엔진 전용]).
- d1 (절벽): `--game ATHN04D --arrive ATHN04A@2208,352,850,90`, 봇 보통 200 초. h1 (사원): `--game ATHN06A --arrive ATHN04D@3600,658,1280,90`, `GZoneArea=4`,
  경로 `3700,658,1280;4300,690,1280;4832,697,1283`, 봇 보통 300 초. r2 (05D 전체): `--arrive ATHN05C@-1440,864,2400,180`, 쉬움.

### 21.1 장면 대응 [확실: 스크립트·데이터 / 영상 대조는 섬네일 (2–10 초 간격)]
| 영상 | 원작 (스크립트·데이터) | 엔진 (게임 시각, 녹화) |
|---|---|---|
| 8070–8076 | 다리 카메라 (BridgeCam): 연기 낭떠러지 위 비계 → 다리 · 횃불 둘 문 · 바닥 홈 레버 | r2 4–12 초 (레버 · 비계 · 다리 시점) |
| 8082–8110 | 항아리 방 (바닥은 05C 에만) · 바닥 레버 · 횃불 옆 상자 → 깃털 카드 "1 개를 더" (깃털 5 개째) | 상자 = ATHN05D Creator Chest4 (CSM 011 MagicShard) — c1 14.1 초 R2 → 16.4 chestOpen → 17.1 카드 (`12` `13`, 하네스 처음 상태라 깃털 1 개 → "5 개를 더") — 고치기 전 상자 없음 (`before_jar_room_no_chests_r2_12.5s`) (§21.2-6) |
| 8120–8142 | 횃불 복도 · 언데드 (FirstSetEnemies DiveLevel1AI ×3, R_SKD 37) | r2 14–52 초 |
| 8136 | 횃불 복도 공중 연타 — 카메라가 공중의 크레토스를 올려 본다 (천장이 보임) | 복도 카메라 ExitCam3 = 레일 NCV_ExitRail5 · 피치 모드 3 (레일 점에서 대상 조준) · 플래그 0x0435FE 에 점프 높이 고정 (0x800/0x4000) 없음 [확실: 데이터] → j1 1.3–1.9 초 2단 점프에 같은 올려 보기 (`11`) |
| 8190–8202 | 물 방 (어두운 반사 물) 싸움 | w3 0–8 초 (고친 뒤 어두운 물, `06`) — 고치기 전 희뿌연 판 (`before_water_pale_05d`) (§21.2-4) |
| 8210–8224 | "YOU ARE DEAD" → 기둥 방에서 다시 → 깃털 카드가 또 (체크포인트가 상자 앞) | 엔진 재시작은 체크포인트로 옮기기만 해 세계를 되돌리지 않는다 [미구현, §21.3] |
| 8232–8358 | 둘째 복도 (SecondSetEnemies ×4) · 업그레이드 메뉴 (8286) · 엘리베이터 방 | r2 64–95 초 |
| 8366–8378 | 둥근 발판에 서면 내려감 → 어둠 → Warp | e3 34.5 초 PlayerEntryAtTop → 36.5 MarkerEntry → 39.9 beginFade (FadeOut 0.6) → 41.5 Warp (`01` `02`) — 고치기 전 발판에 서도 그대로 (`before_elevator_stands_on_plate_e1`) (§21.2-1·2) |
| 8378–8390 | 04A 도착 밝은 홀 → 기둥 복도 → 동영상 | o2 0–9 초 (`03`), 9.1 FadeOut → 10.1 PlayMovie |
| 8390–8412 | a04Mp01 (오라클 · 하피 · "크레토스! 도착했군요!") | o1 10 초부터 (`--skip-movies` 면 10.27 MovieFinish → FadeIn 2 · SaveGameZone2) |
| 8416–8420 | 들보 위 구 장식 옆 "아테네 도시 광장" | o2 16.38 초 ZoneReportGroup2 → 구역 4 (`04`) — 같은 자리. 69cfd3e (pelvis 접촉점 · 선분 질의) 위 o3: 16.27 초, 같은 자리 |
| 8426–8458 | 사다리로 광장 · 분수 옆 달림 · 다시 들보 — 산 시민 없음, 시신 (SuicideBody, ShesDeadJim) | 처음부터 시작한 04A 는 SectionZone 에서 시민 8 명 (o2 24 초) → 레벨 기억 (§21.2-5) |
| 8462–8470 | 들보 끝 세이브 빛기둥 (SaveGameZone2) → Ω · 메모리카드 | SaveGameZone2 (1690, 137, 705) 생성 (o2 10.27 초) |
| 8476–8584 | 횃불 절벽 계단 · 절벽 길 해골 (R_SKC 22) 싸움 ≈ 100 초 | d1 10–115 초 (봇, 보통, 115 초 사망) (`09`) |
| 8590–8598 | 테라스 파랑·초록 상자 · "오라클의 사원" · 흰 흐름 = 파란 상자 오브가 크레토스로 · 초록 오브 | h1 0 초 상자 둘 (`07`), 21.3 초 제목 (`08`, 봇이 되돌아와 볼륨에 다시 들어갈 때 — 도착점이 볼륨 안이라 [엔진 전용]) |
| 8602–8610 | 난간 길 · 날개 석상 · 검정 (movie1orcle FadeOut 0.7) | h1 3–9 초, 53.2 초 FadeOut |
| 8616–8634 | a06Mp01 → 팔각 마당 (AIArenaGoto camCenR1) | h1 53.9 MovieDone → FadeIn 1.3 · CheckPoint · 하피 셋 · DMC 결계 |
| 8636–8736 | 팔각 마당 하피 · 메두사 (8686 초록 광선 = 메두사의 시선) | h1 54–237 초 (봇, 보통 — 사람보다 느림), 5 처치 뒤 R_MEW, 18 처치에서 결계 dogOut — 고치기 전 연못 물이 흰 뾰족한 산 (`before_water_spikes_06a`) (§21.2-4) |
| 8740–8806 | camDoorOff → a06Mp04 (노인 "누구요?…", "무덤?", "파야 할 무덤이 너무 많아", "그 때는 내가 도움을 주겠네") → 노인 곁을 떠남 | h1 237.1 camDoorOff → 239.1 FadeOut → 239.7 a06Mp04 → GraveSpot · graveDig 스폰 (5148, 654, 1012) (`10`) |

### 21.2 엔진 결함과 수정
1. **엘리베이터가 내려가지 않았다 — 볼륨 조각이 관절 0 에만 붙었다.** BallHull off[6] 은 조각마다 관절 번호 (CDV 는 38 §4 에서 이미 [확실]) 인데 ENV/CMV/SEV 판정
   (`LevelData.PlacedVolume`) 은 모든 조각을 관절 0 공간으로 봤다. ATHN05D ENV_WarpElevator5D 조각 0 (영역 0 PlayerVolume0) 은 관절 2 pressPlate 의 r 32 구
   (로컬 y 17.55) — 관절 0 (승강로 바닥 713.6) 으로 두면 구 중심 731 이라 발판 윗면 870 에 선 영웅이 영영 들지 않았다. 원작 영웅 볼륨 판정 0x1A7FFC →
   0x11E190 은 CDV 와 같은 충돌 월드 0x29C978 를 질의한다 [확실: 흐름 · 데이터 (발판 구는 관절 2 가 아니면 쓸 수 없다)] → `PlacedVolume.partInverse` (관절
   0 이 아닌 조각의 관절 역행렬) + `BallHull` 조각별 관절 점 판정, 몸 자세가 바뀌면 (애니·부모 링크·도착 자세) `PlacedVolume.pose` (`SceneNav.poseBody`).
   전 레벨 21 볼륨 (ENV 15 · CMV 4 · SEV 2: PAND00C Ring1Crank 725 · HDES01E ElevanthPost 2266 · PAND01B 톱날 · PAND02H 칼날 …). 평면 1 개 hull (= 구) 을
   영역 판정에서 평면으로 읽던 것도 구로 (`BallHull.contains(…, region)`, 36 §5). CMV 의 조각별 관절은 카메라 질의 (`CameraRig`) 에 아직 [미적용].
2. **엘리베이터가 내려가도 Warp 가 없었다 — 끝 자세 Marker.** `Platforms.update` 는 한 번 재생 act 의 마지막 틱에 playing 을 먼저 거짓으로 두고 끝 자세를
   매기는데 `SceneNav` 는 playing 일 때만 Marker 를 옮겨, Marker599 가 끝 바로 앞 틱 (y 676.9) 에 남아 DownVol2 (668.6–676.6, 끝 676.2) 에 들지 않았다
   [확실: 엔진 / 데이터 여유 0.4] → 관절 배열이 바뀐 틱마다 (`SceneNav.poseBody`).
3. **이웃 슬롯 시작 스폰 적이 현재 레벨 적으로 되살아났다.** 이웃 세계 시작 큐 (SpawnAtStart) 동안 `SceneNav.neighbour` 가 아직 비어 전투 다리 세계가 null
   이었고 → 제거가 현재 레벨 세계로 가 Respawn 이 현재 리스너로 템플릿 70 (하피)·50 (미노타우로스) 을 첫 적 종류 R_SKD 대역으로 3 초마다 냈다 (ATHN05D 에서
   ATHN05C 지붕 위치) [확실: 엔진] → `SceneNav.spawningNeighbour` (시작 중 세계).
4. **물 (SCR_Water) 이 밀리고 산처럼 솟고 희뿌옜다** (16 §1.6) [확실]:
   (a) 격자 원점 = 소유 GO 위치 = **최소 모서리** (감쇠 중심 = 위치 + (nx·cell·8, nz·cell·8) 0x219E24 VADD, 충격 칸 = (점 − 위치)/(cell·16) 0x21A608 VSUB) —
   엔진은 한가운데로 두어 크기 절반만큼 밀렸다 (06A 팔각 연못 −160, −96 · 05D 물 방 −128, −192). 06A gowaternode (4848, 1184) + 20 × 12 → 가운데 z 1280 = 연못 축.
   (b) 물방울 = 위치 + (난수[0,1)·크기 + 오프셋) × 16 (0x21A09C), (c) 충격 = 세기 s 를 네 칸 쌍선형으로 **이전** 높이 배열에 (0x21A608 → +0x18) — 엔진은 현재
   배열에서 4s + 2s×4 를 빼 12 배 (06A 60 초 최대 |h| 72.8 → 9.6), (d) 머티리얼 "MAT_REFLENVM" 은 원작 자원 조회 0x17A7B8 → 0x1759F0 이 대문자 해시
   (0x175938) 라 MAT_reflenvm 을 찾는데 엔진은 대소문자를 가려 흰 텍스처로 그렸다 (`WadAssets.materialTexture`), (e) 물 메시는 정점 색을 쓰지 않고 (0x21A1D0 ·
   0x21A888: 위치·UV 만) GO 색 +0xB0 의 w = 거리 감쇠 a (0x219F54) → 색·알파 = 텍스처 (어두운 반사, 알파 ≈ 0.62) × a. → `WaterSim` · `WaterSurface`.
5. **다시 들어간 레벨이 늘 처음부터였다 — 레벨 기억.** 원작은 레벨 상태 표 0x336B28 (이름 칸 120, 개수 0x29D9E8) 을 둔다 [확실: 코드]: 슬롯을 비울 때
   0x1B3CD0 → 0x1B4080(슬롯, 칸, 1) 이 슬롯 문맥 (0x303DE8 + 0x6C·슬롯) 의 +0x8 첫 적재 비트 · +0x10..+0x1C (LevelData 풀 기준 +0x18 · 개수 +0x1C) · 기록 목록
   (+0x20 엔티티별 오브젝트, +0x28..+0x58 GO 상태, +0x38 미뤄 둔 발동) 을 덩어리로 쓰고, 같은 레벨을 읽으면 0x1B38A8 이 이름으로 칸을 찾아 0x1B4618 로 되살린다.
   LevelData 값은 전역 풀 0x301548 (0x500 칸, 새 게임 0x1AF258 전엔 비우지 않음) 에 있고 되살린 슬롯은 +0x1C ≠ 0 이라 0x1AF2C0 이 기준을 다시 잡지 않는다 →
   **LevelData 가 이어진다**. 첫 적재 관문 0x1A9F30 → 0x1B3C58 (슬롯 +0x8 의 플레이어 번호 비트) 을 GlobalData/LevelData Init (0x1AE848 · 0x1AE9E8), SpawnEnemy 시작
   생성 (0x1AC630), Creator 시작 생성 (0x1ACA70), CreationSensor 모드 1 (0x1AA5C0) 이 거치고, 거짓이면 SpawnEnemy·Creator 는 0x1A6C70 으로 기록된 오브젝트를
   되살린다; 모드 5 생성 센서는 관문 없이 매번 (ATHN04A SuicideBody-SpecialCreationSensor 가 GlobalData ShesDeadJim 으로 시신을 다시 켜는 까닭). 판도라 신전
   입구 (0x1B3A10): 새 칸 "Pand00B" 바로 앞이 "Pand00A" 면 표를 둘만 남기고 비운다. 세이브 (World_Serialize 0x1B3E48) 는 올라 있는 슬롯도 칸에 쓴 뒤 표 전체를 쓴다.
   엔진은 매번 처음부터라 05D 엘리베이터로 돌아온 광장에 첫 방문의 시민 (SecTP 조건 LevelData0 (키클롭스 처치) &lt; LevelData4) 이 나왔다 (영상 8426–8458 엔
   없음) → `GameSession.levelMemory` · `enterLevel` · `rememberLevel`, `ScriptWorld.Memory` · `restore` (LevelData + 첫 적재 메시지 건너뜀 + 만들어 둔 Creator +
   오브젝트별 마지막 Vis 값) · `SaveGame` (levelTable · level.* · levelCreated.* · levelVis.* · levelOpened.*) [근사: 적 기록 · Animator 자세 · 미뤄 둔 발동 ·
   센서 한 번 발동 기록은 담지 않는다 — 원작 기록 목록의 엔티티 발동 상태는 미확인].
6. **ATHN05C 항아리 방의 상자 둘이 없었다 — 이웃 슬롯 상자.** 방 바닥은 05C 에만 있어 (05D 충돌 없음) 영웅이 거기 서면 걷기 이음 규칙으로 현재 레벨 = 05C 인데, 방의
   상자는 ATHN05D 의 Creator Chest3 (CSM 010 HealthShard = 고르곤의 눈) · Chest4 (CSM 011 MagicShard = 불사조 깃털) 다. 원작은 두 슬롯이 다 살아 있어 Creator 0x1ACCA8 이
   슬롯마다 GO 를 만들고 잡기 질의 0x1C3F18 은 슬롯을 가리지 않는다 [확실: 구조 (38 §23.2) · 영상 8090–8100 깃털 카드]. 엔진은 이웃 Creator 중 세이브 포인트만 만들어
   ("이웃 상자는 현재 레벨 것만" [근사]) 05D 로 넘어가야만 상자가 생겼고, 05D 바닥에서는 방에 닿기 전에 다시 05C 로 넘어가 상자가 사라졌다 — 깃털·눈을 얻을 수 없었다.
   → `SceneNav.createNeighbour` (이웃 `Interactables`, CSM 은 이웃 레벨 TWK — 05C TWK 에는 CSM 011 이 없다) · `GameScene.neighbourCreate` (몸·충돌 = 이웃 `Platforms`,
   다른 슬롯이 바뀌면 지움). 이음마다 씬이 상자를 새로 만드므로 연 상자를 세계에 기억한다 (`ScriptWorld.markOpened` / `opened`, Creator 키 — 원작은 GO 가 그대로 남는다
   [확실: 구조]): 다시 만든 상자는 연 채 (`SceneNav.addChest` → done, `ChestProps.spawn` → chestOpen 끝 자세, 아이템 안 줌), 레벨 기억 (`Memory.opened`) · 세이브
   (`levelOpened.*`) 에도. 예전엔 05C ↔ 05D 이음을 오갈 때마다 연 상자가 닫힌 채 다시 나와 아이템을 또 줄 수 있었다. c2: 05C 에서 깃털 상자 열기 (16.4 초) → 05D 이음
   (24.6) → 05C 이음 (59.0) → 그 상자에 R2 3 초 — 잡히지 않고 열린 채 (`14`), 아이템 1 번.
7. **잡은 해골병을 끄는 동안 영웅이 벽을 지나 떨어졌다 — 결합 중 벽 처리.** rebase 뒤 엘리베이터 재확인 (e4/e5, 보통) 에서 엘리베이터 방 둘째 무리와 싸우던 봇이
   서쪽 벽 감실 (x −1148..−1132, z 1216..1245) 에서 R_SKD 를 잡아 끄는 결합 (32.4–34.6 초) 동안 감실 북쪽 벽을 지나 (−1146, 1258) 바닥 없는 바깥으로 나가
   35.78 초에 떨어졌다. 원작 생물 갱신 0x1EBD78 은 루트 모션 D 의 이동을 변위 (sp+0x3C0) 로 내비게이션 이동 (지상 0x1EB3D8 / 0x1EAEC8 → 벽 0x1E2100 · 이동
   선분 0x1EAB78) 에 넘긴다 (37 §11.4) [확실: 경로] — 엔진은 결합 중 장면 물리를 건너뛰었다 → `GameScene.grabWalls` (몸 구 밀어내기 + 선분, 수평만).
   같은 실행 (e6): 감실 안에 남아 (z ≤ 1234) 무리를 끝내고 40.47 초 PlayerEntryAtTop → 45.87 beginFade → 47.47 DownVol2 Warp (e3 와 같은 간격 +5.4 / +7.0 —
   pelvis 접촉점 (68b8f16) 뒤에도 발판 판정 그대로). 끌리는 적의 벽 처리는 그대로 [근사] (05D 복도에서 적이 05C 웅덩이로 떨어지는 것과 같은 뿌리일 수 있다).

### 21.3 남은 것 · 미확인
- 8210–8224: 원작 체크포인트 재시작은 메모리 세이브 (0x22D418) 를 되읽어 레벨을 다시 올린다 (11 §5.3) — 상자 (깃털) 가 닫힌 채로 돌아가 다시 줍는다. 엔진은 영웅만
  체크포인트로 옮겨 세계가 그대로라 깃털 카드가 다시 나오지 않는다 [미구현: 스냅숏 되읽기]. (69cfd3e 의 FailedScreen 다시 하기는 레벨을 처음 상태로 새로 읽지만
  보통 사망 (DeadMenu) 은 여전히 자리만 옮긴다.) 연 상자 기억 (§21.2-6) 은 이 재시작에 끼어들지 않는다 (세계를 다시 만들지 않으므로).
- 05D 복도에서 봇의 공격에 적이 서쪽 벽 (x −1000) 너머 · 항아리 방 아래로 밀려 ATHN05C 웅덩이 바닥 (y 453) 에 떨어진 적이 있다 (r2 105–155 초, c2 55–110 초 —
  봇이 닿지 않는 그 적을 계속 노림) [미해결: 적 넉백 벽 충돌].
- 항아리 방 → 횃불 복도 (8110–8120) 걷기 이음에서 엔진은 0.4 초 어두워졌다 밝아진다 (t1 15.8–16.7 초, `SceneManager` 전환 페이드) — 원작은 끊김 없음. 이미 적힌
  엔진 구조 항목 (§19 "이음 순간 0.4 초 페이드") [엔진 구조].
- 깃털 카드 문구의 개수 (영상 "1 개를 더" = 깃털 5 개째) 는 앞 레벨에서 모은 개수에 달렸다 — 하네스 도착은 처음 상태라 "5 개를 더" (같은 규칙) [대조 불가: 상태].
- 동영상 a04Mp01 · a06Mp01 · a06Mp04 는 원작 PSS 그대로라 대조하지 않았다 (`--skip-movies` 와 o1 재생으로 앞뒤 스크립트만).
- 06A 구역 제목: 하네스 도착점이 ZoneReportGroup 볼륨 안이라 처음엔 뜨지 않는다 (이음 도착은 "이미 안", 원작은 04D 계단을 걸어 들어오며 뜬다) [엔진 전용].
- 하피 마당 처치 시간은 봇 (183 초) 과 영상 (≈ 100 초, 메두사의 시선 사용) 이 달라 대조하지 않았다. 무덤 파는 노인 (AOXDI: Ignore Hits · Target Weight 0 ·
  결정 트리 없음) 을 봇이 때렸다 [엔진 전용] → 69cfd3e 뒤 봇은 Target Weight 0 NPC 를 고르지 않는다. 노인 R2 대화 (335/336, camGraveD) 는 영상에 없다.
- 레벨 기억이 센서의 한 번 발동 기록을 담지 않아 재방문 광장에서 북쪽 계단 DMC TriggerVol0 에 다시 들어가면 키클롭스 결투가 다시 시작된다 (LevelData0 이 3 에서
  늘어 결계가 안 열림) — 원작이 같은지 미확인 (영상 경로는 그 볼륨에 가지 않는다) [미확인].

### 21.4 시험
`OracleApproachTest` (3: 발판 구 = 관절 2 pressPlate, 05D 엘리베이터 승강 → Warp 6.5–8 초 · 끝 Marker 676.2, 이웃 시작 스폰 하피의 제거는 그 세계로),
`WaterSimTest` (4: 격자 모서리 = 소유 위치 (06A 연못 축 1280 · 05D 물 방), 쌍선형 충격 → −0.99·s, 머티리얼 대소문자, 60 초 최대 &lt; 12),
`LevelMemoryTest` (3: 광장 재방문 LevelData0 = 3 · Init 건너뜀 · 상자 되살림 · 모드 5 시신 · SectionZone1 시민 없음 (처음이면 있음), 판도라 입구 표 비우기,
세이브 왕복 + 연 상자), `NeighbourChestTest` (2: 항아리 방 상자 = 05D Creator (CSM 010/011, 아이템 10/11, 05C TWK 에 CSM 011 없음) · 상자 앞 바닥은 05C 에만,
연 상자가 이음 recreate · 레벨 기억으로 다시 만들어도 끝난 채), `GrabWallTest` (1: 감실 끌기 1 초 — 벽 처리 없이는 바닥 없는 바깥, 있으면 방 안).

### 21.5 증거 (`work/verify/athn06_oracle_approach/`, 커밋 안 함)
`01_elevator_descending_e3_36s` · `02_elevator_fade_e3_40.5s` · `03_athn04a_arrival_hall_o2_6s` · `04_zone_title_plaza_beam_o2_16.5s` (영상 8416 과 같은 자리) ·
`05_water_06a_pool_w4_3s` · `06_water_05d_dark_w3_1s` · `07_athn06a_terrace_chests_h1_0s` · `08_athn06a_title_h1_24s` · `09_athn04d_cliff_fight_d1_10s` ·
`10_gravedigger_npc_h1_242s` · `11_corridor_jump_camera_j1_1.6s` · `12_jar_room_feather_chest_open_c1_16.8s` · `13_feather_card_c1_17.2s` ·
`14_chest_stays_open_after_seams_c2_65s`, 고치기 전 `before_elevator_stands_on_plate_e1_50s` · `before_water_spikes_06a_h1_57s` · `before_water_pale_05d_w2_6s` ·
`before_jar_room_no_chests_r2_12.5s` (횃불 둘 옆이 비었다) · `before_grab_through_wall_e5_33.8s` (벽에 반쯤 묻힌 크레토스) · 고친 뒤 `15_grab_stays_in_room_e6_34s`.

## 22. QA vs PS2 영상 — ATHN05B 발리스타 복도 · ATHN05BB 감옥 홀 · 제우스 석상 (영상 7351–8066 초, 2026-10-24)

§19 지붕 싸움 (–7351) 뒤: ATHN05B 실내 · 세이브 → 발리스타 첫 등장 (7486) → ATHN05BB 감옥 홀 리프트 문 (R2 연타) · 해골 다섯 (7506–7576) → 사다리 ·
둥근 판 방 · 초록 상자 (7586–7696) → **발리스타 퍼즐** (7716–7858: 복도 → 홀로 밀기, 둥근 판 레버로 두 번 돌리기, 크랭크 → 볼트 → 홀 끝 나무문) →
부서진 아치문 계단 (7864) → 제우스 석상 방 (7876–7942: MAtBolt 동영상 · 아이템 268 · 안내 598 · 얼굴 문) → 제우스의 분노 첫 사용 · 세이브 · 안내 (7956–8056) →
바닥 레버 (8066, ATHN05C idiotR2). 엔진 = origin/main 9403d31 (§20) 위, 녹화 lockstep 1/2 초. 퍼즐·연출은 쉬움으로 몰았고 (영상은 보통) 싸움은 `-Dgow.auto.route.nofight`
로 뺐다 — 싸움 대조는 하지 않았다 [미대조].
- r15 (발리스타 전 과정, 230 초): `--game ATHN05B --arrive ATHN05BB@-720,480,4307,90`, 경로 `-712,480,4307!;use:-650,4307;hold:34:-121,4300;hold:24:-121,4735;
  hold:5:-27,4735;wait:0.5;195,424,4729!;use:223,4729;wait:5;21,432,4670!;21,432,4687,2;hold:6:-73,4687;wait:0.5;195,424,4729!;use:223,4729;wait:5;-73,428,4670!;
  -73,428,4687,2;hold:6:21,4687;wait:0.5;195,424,4729!;use:223,4729;wait:5;70,432,4721!;56,432,4721,2;hold:6:-39,4721;wait:0.5;-80,428,4660!;-80,428,4677,2;btn:R2;
  hold:10:-80,4728;wait:10` (판 사이 오가기는 순간이동 — 봇이 발리스타 몸·레일 턱을 돌아가지 못한다 [하네스]).
- r6 (리프트 문): `--game ATHN05BB --arrive ATHN05B@20,440,4728,-90`, `wait:1;40,440,4728,8;use:61,4728;wait:1.5;use:61,4728;mash:6;wait:20`.
- r4 (제우스 석상, 보통): `--game ATHN05B --arrive ATHN05BB@-862,480,4960,180`, `-862,480,4880,10;-862,480,4600,20;-862,480,4472,6;wait:40`.
- r5 (바닥 레버): `--game ATHN05C --arrive ATHN05B@-1450,864,2850,180`, `-1450,864,2850!;wait:1;-1450,864,2830,10;use:-1450,2700;wait:30`.

### 22.1 장면 대응 [확실: 스크립트·데이터 / 영상 대조는 섬네일 (1–5 초 간격)]
레벨: ATHN05B (발코니 · 실내 · 세이브 · 발리스타 복도 x −700 → −290 · 작은 방 · **홀** (충돌은 05B, z 4420 – 5050) · 제우스 방) / ATHN05BB (홀의 감옥 리프트 문 넷 ·
홀 끝 BrkbDoor · 보물 방 · 복도) — 홀 안에서는 늘 05B 가 현재 슬롯이고 05BB 물체는 이웃 슬롯이다 (LoadCheckBB2 는 바닥이 05B 라 전환하지 않음).
| 영상 | 원작 (스크립트·데이터) | 엔진 (게임 시각, 녹화) |
|---|---|---|
| 7486 | 발리스타 (goballistapushpull, Push 025 "BallistaPlatfor", 부모 링크로 크랭크 gobiggun1crank · 팔 gobiggun1anim) 복도 서쪽 끝, R2 안내 | r15 0 초 (`R2`) |
| 7506–7516 | 05BB PresLiftDoor1 (TAndF 332): R2 잡기 → 연타 → 손잡이를 따라 오르는 ENV 공 (반지름 44.8) 안에서 1023 → LevelData0 | r6 1.38 초 잡기 → 6.72 초 1023 (100) — 수정 전 42 % 에서 멈춤 (§22.2-2) (`03`) |
| 7526–7576 | LiftDoor3Enem: LevelData0 == 100 → R_SKC 템플릿 22 다섯 (지연 0 / 0.25 / 0.5 / 0.75 / 1 초) + 음악 Ath5C2 | r6 6.73 – 7.73 초 다섯 · 음악 (7.3 초 걷기 전환 뒤 이웃 슬롯 적으로 이어짐). 싸움 [미대조] |
| 7716–7736 | 서쪽 손잡이 (handle2) 에 붙어 동쪽으로 — 복도 내리막 (480 → 444), KickMessage (Marker555 → 메시지 183 차기 안내) | r15 0.6 – 34 초, 13 초 183 — 수정 전 문 x −480 에 걸리고 (§22.2-4) 영웅이 내리막 위 허공에 떴다 (§22.2-6) |
| 7738–7752 | 작은 방에서 손잡이에 붙은 채 옆걸음으로 북쪽 홀로 (볼트는 옆을 향한 채), ClunkZone · CDHullZone (Marker555 → LevelData9, 문간 PushPullCD 켬) | r15 34 – 58 초 (40.7 · 41.2 초) (`01`) — 수정 전 손잡이 축으로만 밀려 홀로 못 나감 (§22.2-7) |
| 7786–7840 | 둥근 판 (goladder 관절 15) 에 올리고 사슬 레일 끝 레버 (lever4Ladder 602) → 30 % 에서 ladder-AnimDown (모드 8) · 사슬 재질 → 판 90°, 내려놓고 빈 판을 되돌리고 (AnimUp 모드 9) 다시 올려 한 번 더 → 앞이 홀 끝 문 | r15 64.4 / 76.8 / 89.1 초 레버, 65.5 / 77.9 / 90.2 초 판 (`02`) — 수정 전 판만 돌고 발리스타는 그대로 (§22.2-8) |
| 7852–7858 | 홀 가운데에서 뒤 크랭크 R2 (Push 202 AthnGun 601) → 활 팔 당겨짐 → 7857 볼트 → 7858 TestCamera: 나무문 부서져 판자가 계단에 | r15 107.5 초 크랭크 → 111.86 초 발사 (−80, 459, 4673) → 112.41 초 BrkbDoor (05BB) 파괴 · DestructionSensor (Filter 600) · TestCamera (`05`) — 수정 전 볼트가 남쪽 벽에 (§22.2-8 · 9) |
| 7876–7942 | 제우스 방: toMagicDoor → playMA360 (동영상 MAtBolt) → 얼굴 문 (MAmagicDoor) → 아이템 268 → 안내 598 → 얼굴 애니 · VO → ZEUSdoor 내려감 | r4 0.58 / 5.37 / 6.58 / 6.70 / 8.73 / 16.73 초 (`04`) — 수정 전 얼굴이 문 밖에 (§22.2-1) |
| 7956–8056 | 제우스의 분노 첫 사용 · 세이브 · 벽 문장 · 안내 상자 | [미대조] (경로로 몰지 않음) |
| 8066 | ATHN05C 바닥 레버 병사 (idiotR2: R2 → idiotCineON → forceMove) | r5 1.52 – 20.03 초 연출 |

### 22.2 엔진 결함과 수정
1. **제우스 얼굴이 없었다** (7924–7942): MAmagicDoor_0 은 관절 31 + 혼합 노드 32 (메시 +0x2C, 표 단어 수 +0x30, 20 바이트 레코드 뒤 {n, (관절, 가중) × n}) —
   jointMap 의 번호 관절 수 + i 가 노드 i = Σ 가중 × 관절 행렬이다 [확실: 데이터 — 전 메시 6288 중 12 개, 모두 단어 수·가중 합 1 일치]. 엔진은 범위 밖 번호를 짝
   관절로 읽고, 얼굴 파트 (부착 2 head, vuOr 0x50) 를 관절 로컬로 옮겨 머리 바인드만큼 한 번 더 밀어 문 밖 (z 4316…4454) 에 그렸다. vuOr 비트 0x40 = 혼합
   스키닝 VU 프로그램 (0x165998: (VU 플래그 &amp; +0x14) | +0x10) 인 오브젝트는 모델 공간 [확실: 데이터 / 추정: VU1 코드 미해독] → `GowMesh.blendJoints` ·
   `SkinnedModel.Part.palette/skinProgram` · `AnimatedModel` · `PlacedMesh` (04 §3.5, 05 열린 항목 해결).
2. **스크립트 볼륨 접촉점이 발이었다**: 원작 영웅 스크립트 레코드 점 = 메시 [0] +0x34 관절 (0x1ADD74 → 0x1A5E18, 매 프레임 0x1A6308) = hero_0 의 1 "pelvis"
   (바인드 21.65) [확실: 코드 · 데이터]. 05BB PresLiftDoor 의 ENV 공은 손잡이를 따라 올라가 발이 42 % 에서 빠지고 1023 (100) 을 못 받아 해골이 나오지 않았다 →
   `HeroRig.contactPoint` → `ScriptWorld.tick` · 이웃 슬롯 (28 §6). §18.4 ATHN04A DoorHandleZone (+6) 도 같은 원인.
3. **한 틱에 얇은 볼륨을 건너뛰면 센서가 안 섰다**: 원작은 지난 점 → 지금 점 선분 질의 (0x1A7FFC → 0x11E190, 콜백 0x1A8508: 접촉 목록에 없는 볼륨에 진입 2 다음 이탈 4)
   도 한다 [확실] → `ScriptWorld.sweep` (96 이하, 순간이동은 건너뜀) [근사: 지난·지금 점이 안이면 뺌].
4. **발리스타가 방 문 (x −480, 폭 84) 에 걸렸다**: 원작 상자 몸 = Push 템플릿 Radius 1.76 × 16 = 반 너비 28.16 · #044ed4a7 2 × 16 = 높이 32 (0x20F9F8 → 몸 +0x120),
   벽 판정 = 몸 둘레 8 점 (0x210DC8 → 0x210AE8 → 0x1E1F28) [확실]. 엔진은 CDV 경계구 0.8 × 55 = 44 구 하나였다 → `Interactables.Item.boxHalf/boxHeight`,
   `HeroNavigator.moveBox` 둘레 8 점 [근사: 작은 구가 벽에 닿으면 그 틱 막음].
5. **밀린 상자가 제자리에 그려졌다**: 상자 위치 (Item.pos) 와 Marker 만 옮겨 렌더·CDV·자식 GO 는 처음 자리 → 밀기 상자도 발판 몸으로 (`SceneNav.bodyOwners`),
   `Platforms.Body.place` (처음 · 이동 · 실림) · `Platforms.shift`, 손잡이 다시 읽기 (`refreshHandles`), 상자 높이는 바닥 높이 변화만큼 (예전엔 첫 밀기에 5.5 떠올랐다).
6. **밀기 중 영웅이 내리막에서 떴다**: 원작 영웅은 붙어 있어도 중력 (0x1C47B0) 과 바닥 탐침 (0x1C4DE0 → 0x1EA5F0 → 0x1E3298) 을 거친다 [확실] → `HeroNavigator.follow`
   가 발밑 바닥으로 높이를 맞춘다 (놓는 순간 34 를 떨어지던 것).
7. **발리스타를 홀로 밀지 못했다**: 원작 영웅 국소 속도 = 스틱의 옆·정면 성분 그대로 × Sold+0x188[무게] (0x1C448C – 0x1C4630), 붙은 상자는 영웅 이동량 (+0x100 xz)
   을 그대로 따르고 속도 0 (0x212398 → 0x2119A8) [확실] — 손잡이 (handle1/2, 로컬 ±x) 에 붙은 채 옆걸음으로 북쪽 홀로 간다. 엔진은 손잡이 축 성분만 썼다 →
   `HeroNavigator.push` (스틱 그대로, 놓아도 미끄러지지 않음, 애니 Push / Pull / PullL / PullR [추정: 고르는 규칙]), 붙잡기 = 잡은 손잡이에서 몸 반 너비 바깥, 그 축이
   상자 국소 축 (0x1C3918 · 0x1C4638) [근사: 반 깊이 = 반 너비].
8. **둥근 판이 발리스타를 돌리지 않았다**: 판 = goladder (사다리 장치, fl 0) 관절 15 group2920 — act "ladder" 3.67 초에 yaw 0 → 90° (같은 act 가 사다리를 124 내림),
   레버 lever4Ladder (newLeever Type 5, 602) 30 % 에서 LevelData5 (LadderUp/DownZone, Marker99) 에 따라 AnimDown (모드 8) / AnimUp (모드 9) [확실: 스크립트·데이터].
   원작 상자 갱신은 매 틱 0x1E6248 로 지면 GO 관절 (바닥 탐침이 적은 +0x24C / +0x250) 의 움직임을 yaw 만 남겨 몸에 곱한다 [확실: 코드] → `SceneNav.rideBoxes` ·
   `Platforms.ride` (자식 GO 같은 틱). 처음 볼트 = 남쪽 (Aim Joint arrowjoint 행 2, 0x222B24) → 한 번 = 서쪽 → 두 번 = 북쪽 문 (r15 111.86 초 (0, 0, 1)).
9. **볼트가 다른 슬롯 문을 지나쳤다**: BrkbDoor 는 05BB (이웃 슬롯) — 화살·공격이 현재 슬롯 Breakables 만 봤다. 원작은 두 슬롯 GO 가 한 세계 [확실] →
   `GameScene.otherBreakables`, `ArrowShots.Targets.otherBreakables/otherWorld/otherOnBreak`, `onBreakOther` (이웃 렌더 숨김 · 충돌 끔 · 그 WAD 이펙트로 파괴 연출,
   `MagicFx.addWad(Path)`) (41 §2).
10. 다른 슬롯 레벨 FXC 이미터 파티클도 그린다 (`LevelScene.renderNeighbourEffects` → `LevelFx.renderNeighbourParticles`) [근사: 진행은 렌더 간격] — 단 이 구간의
    횃불 불꽃은 두 슬롯 모두 안 보여 (§22.4) 영상 대조는 못 했다.
11. 하네스 [엔진 전용]: `-Dgow.auto.route.nofight` (경로 중 싸움 안 함), `-Dgow.debug.events` (게임 → 스크립트 이벤트 출력), `-Dgow.debug.hudvars` HUD 패드 출력.

### 22.3 테스트
`AthensCatapultTest` (제우스 얼굴 혼합 노드 · vuOr 0x40 파트 모델 공간, 혼합 팔레트 R_MIN 55/56, hero_0 접촉 관절 1 pelvis, HandleSeenGroup 은 pelvis 로만,
toMagicDoor 한 틱 건너뛰기 → LevelData8, 발리스타 몸 28.16 / 32, 판 두 번 = 북쪽 + 자식 GO 이동, 05BB BrkbDoor 를 05B 볼트가 부숨 → LevelData5 · TestCamera),
`HeroNavigatorTest.attachedPushFollowsTheStickSideways`. 엔진 테스트 611 개 통과.

### 22.4 남은 것 · 확인 못 한 것
- 싸움 (해골 다섯 7526–7576, 군단병 7862) 은 보통 난이도로 대조하지 않았다 [미대조]. 7956–8056 (제우스의 분노 첫 사용 · 세이브 · 안내) 은 경로로 몰지 않았다.
- 횃불 불꽃 (FXC_TFfireemit*, 서브타입 1, rate 45) 이 영상 (7740 · 7852 · 7858) 처럼 보이지 않는다 — 엔진 이미터 자리 = FXC 로컬 · 관절 0 · 인스턴스 인데
  05BB gocellroom 문 옆 둘 (TF29/30, 로컬 x −298 / −24) 이 바닥 아래·문 뒤 (y 363, z 5284) 에 놓인다 [미확인: 이미터 기준 노드].
- 상자 몸 둘레 판정은 정적 충돌만 (움직이는 몸·다른 상자와는 안 함), 놓인 상자의 `place` 는 이음 상태 (`Platforms.State`) 로 넘기지 않는다 [근사].
- 판 위에 선 영웅은 자리만 실리고 방향은 안 돈다 (원작 0x1E6248 은 영웅도 yaw) [근사]. 원작 크랭크 손잡이 잡기 · 밀기 애니 선택 규칙은 미해독.
- 혼합 노드의 VU1 계산 코드는 못 찾았다 (데이터 일관성만) [추정]. 05BB → 05B 걷기 전환 (r6 7.3 초) 은 엔진 구조 [근사].
- 다른 슬롯 물체를 처음 부술 때 그 WAD 를 다시 해석한다 (지연) [근사].

### 22.5 증거 (`work/verify/athn05_catapult_zeus/`, 커밋 안 함)
`01_strafe_into_hall_video_vs_engine` (7744 / 7748 / 7752 ↔ r15 40 / 45 / 50 초) · `02_turntable_video_vs_engine` (7792 / 7818 / 7836 / 7840 ↔ r15 63 / 66 / 90 / 95 초) ·
`03_presliftdoor_skeletons_engine` (r6) · `04_zeus_face_item_engine` (r4) · `05_crank_bolt_door_video_vs_engine` (7852 / 7855 / 7858 ↔ r15 107 / 110 / 113 초).

## 23. QA vs PS2 영상 — 오라클의 사원: ATHN06A 무덤지기 뒤 → ATHN06B 실내 · 들보 홀 → ATHN06C 오라클 구출 · 엔딩 대화 → ATHN07 세이브 (영상 8806–9917 초, 2026-10-24)

레벨 연결 [확실: 스크립트]: ATHN06A (무덤지기 a06Mp04 뒤 GraveSpot · SaveGameZone (5467, 724, 1146) · Chest1 깃털 CSM 011 · Chest2 고르곤의 눈 CSM 010 · 신전 계단 위
toAthn06B: LoadForWarp → Warp) ─ ATHN06B (WarpFrom6A → Harpies-Volume0 → 동영상 a06Mp05b (하피) → 석상 둘 (Marker 710/711) 로 하피 구멍 둘 막기 · 들보 홀 (FallingBeam
17–29) · SaveGameZone (−3148, 992, −1833) · 복도 해골 셋 묶음 (DMCSpawner) → WarpTo6C) ─ ATHN06C (WarpFrom6B → beginIntro06C a06Mp02 (하피가 오라클을 매닮) →
석상 당기기 (Marker 721/722) · 엘리베이터 (720) · 석상 쌓기 → 덩굴 벽 (x −310, y 198–418) → 카메라 UprCam21 → a06Mp03 → 70 초 구출 → 접시 saveOraclTrig → 동영상
Semo03 (크라토스의 과거) → OracleSaved · R2 대화 342 → 출구 WarpToAth7) ─ ATHN07 (WarpFAthn06C · SaveGameZone1 → 세이브). 엔진 = origin/main 68b8f16 위.

### 23.1 장면 대응
| 영상 | 원작 (스크립트·데이터) | 엔진 |
|---|---|---|
| 8806–8842 | ATHN06A 무덤지기 뒤 세이브 · 상자 둘: 깃털 카드 ("제물이 받아들여졌습니다" 셋째 줄 포함 — 원작도 나옴) · 고르곤의 눈 카드, 카드 위에 미터 없음 (8825 · 8839) | s1: 카드 문구 · 둘째 쪽 자동 넘김 같음 — 고치기 전 카드 위에 HP/MP 미터가 떠 있었다 (§23.2-8) |
| 8842–8859 | ATHN06A 신전 바깥 계단 → 안 붉은 카펫 계단 복도 (8854 는 입구 문틀에서 고정 카메라) → ATHN06B | s2: 같은 계단·복도 → 06B 전환 19 초 (카메라는 복도 안 뒤따름 — 봇이 순간이동해 시점이 다름 [미대조]) |
| 9036–9054 | ATHN06B 하피 방: 서쪽 위층 (792) 벽감의 석상2 (Push 710, 방패·창 전사, 뒤에 순환 상자) 를 끌어내 계단 아래 바닥 (736) 으로 | h8 0–24 초: 벽감에서 끌어냄 → 첫 단 (792 → 764) 위로 떠 가다 놓여 떨어짐 → 아래 바닥 — 고치기 전 벽감 옆 벽에 막혀 꿈쩍 않음 (§23.2-10), 계단 첫 단에서 막힘 (§23.2-11) |
| 9056–9098 | 석상을 바닥을 따라 북쪽 무너진 벽 구멍 (돌무더기 · 부러진 기둥 · 횃불) 앞까지 밀고 R2 다시 잡기 (9072) | h8 24–41 초: 같은 길·같은 구도 (`11` ↔ `12`), 구멍 공 (−3248, 736, −1257) r 32 에 Marker710 → LeftHoleEntry → 6 초마다 석상 흔들림 (Statue2-AnimWobble · 하피 비명) |
| 9150–9310 | 위층 들보 홀 (06B, y 992): 동서 정적 들보 (z −1600 · −1440, 폭 6) 사이의 남북 들보 17 개가 FallingBeam (Push 아님 — ENV 진입 → Animator "!0" 모드 8, act 4 초: 0.5 초 흔들림, 0.75–1.5 초에 한쪽 끝부터 기울며 250 아래로). 영상은 두 번 무너짐 (9163.25 · 9207.75) — 둘 다 크라토스가 옆 동서 들보에 매달려 다시 오름, 9261.85 은 아래층으로 떨어져 붉은 카펫 계단으로 되돌아옴 | m2: 진입 볼륨 (들보 ±40 × ±119, 동서 들보 위 교차점도 듦) → 0.6 초 뒤 무너져 영웅이 아래층 (736) 으로 떨어짐. 무너질 때 옆 들보 붙잡기는 봇 경로에서 재현 못 함 — 원작 턱 질의 0x1E3A80 은 정면 3.2 + 9.6 만 보므로 교차점에서 들보 쪽을 볼 때만 잡힌다 [미확인: 영상의 위치·방향] |
| 9399–9412 | 동영상 a06Mp02 → 줄에 매달린 오라클 (TriggeredEntryCam · PanBackCam) | c1 4–6 초 매달린 오라클 · 카메라 |
| 9416–9456 | ATHN06C 안뜰 석상 (statuePull1, Push 720) 을 받침에 손을 대고 남쪽으로 민다 | p3: 받침 북쪽 면에 붙어 민다 (`09`) — 고치기 전 72 유닛 뒤에서 허공을 밀었다 (`08`, §23.2-9) |
| 9506–9546 | 석상을 둥근 판 (goelevator, 구덩이 −84 위 112) 에 올리고 올라서면 판이 석상과 함께 내려감 (Elevator-AnimDown 모드 8, MarkerVol 720) | e3: 구덩이 북쪽 턱 (z −656, x ≥ −188) 에 막혀 서쪽 (x ≤ −204) 으로 돌아 서→동으로 판에 올림 → PlayerEntry → AnimDown → 석상이 판에 실려 같이 내려감 (`14`). 그 뒤 기어오르기 · 덩굴 (9556–9670) 은 [미대조] |
| 9676–9694 | 동영상 a06Mp03 ("크레토스! 손이 미끄러져요!", "하지만 서둘러야 해요!") — 덩굴 벽에서 카메라 UprCam21 (Internal 0xD) | 고치기 전 a06Mp03Done 의 카메라 조건이 늘 거짓 (§23.2-2) |
| 9698–9699.5 | 안내 523 "건너가려면 가장자리에서 ✕" — 상자 동안 HUD·모래시계 숨김, 타이머는 흐름 | a6 (ARENA06 같은 구조): 메시지 2053 동안 숨김 → 닫힌 뒤 18 · 16 … (`01`) |
| 9699.6–9760.75 | 모래시계 70 → 67 … 7 (흰색) → 6 (붉게, 경과 91 % = FLP 프레임 92) | a6 6 흰색 → 2 붉게 (`02`) · `OracleRescueTimerTest` 9760.25 / 9760.5 값 |
| 9761 | 구출 직후 "0" 흰색 (표시 타이머가 지워져 Event 0) | a6 21 초 다 된 뒤 "0" 흰색 (`03`) — 같은 FLP 동작 |
| 9762–9880 | 동영상 Semo03 → Goto OracleSaved · killOrclonWIN (1008 → 줄의 오라클 제거) | c5 3.17 초 Semo03 끝 → 1008 로 매달린 오라클 제거 · 체크포인트 |
| 9883–9890 | OracleTalk R2 → camOrcTalk · 대화 342 (양피지, 한 자씩: 9885 "문을 통해 사막으로") 세 줄 + ○ → 둘째 쪽 "유일한 방법입니다." | c5 7.73 초 R2 → 342 두 쪽, 타자 50 자/초 (`04` `05` `06`) — 고치기 전 넷째 줄부터 잘리고 한 번에 다 보임 (§23.2-4) |
| 9892–9894 | 문 앞 크라토스, 왼쪽에 오라클 (HUD 없음) | c5 11 초 (`07`) — 고치기 전 봇이 오라클을 때려 죽여 사라짐 (§23.2-6) |
| 9897–9901 | 복도 → 계단 아래 방 | c5 18–31 초 (순간이동 섞임) → ATHN07 |
| 9904–9915 | ATHN07 SaveGameZone1 빛기둥 → R2 → 세이브 (9911 "저장하는 중입니다") | s7: 복도 · 빛기둥 방 · 저장 질문 → 덮어쓰기 질문 (기존 세이브가 있어서) → 저장 완료 |

### 23.2 엔진 결함과 수정
1. **스크립트 타이머가 아예 없었다 (Internal 0x14–0x19, 이벤트 1050).** GameMgr 타이머 표 0x3368F8 (칸 4: 남은 · 길이 · 플래그 1 사용 / 2 멈춤 / 4 HUD) —
   0x14 만들기 (0x1B0B68, 번호 = 칸 + 1) · 0x15/0x16 멈춤/다시 · 0x17 지움 · 0x18 남은 초 · 0x19 모래시계 (0x1B0CE0), 매 프레임 0x1A81F8 이 1/60 씩 줄이고 다 되면
   PostEvent(1050, 번호, 보낸 쪽 = 영웅 0x29D904) [확실]. 엔진은 0x14–0x19 를 버려 1050 이 한 번도 오지 않았다 — 오라클 구출 70 초 (영상 9699.6–9760), 아레나 6 곳의 시간
   제한, 궁수 스폰 · 히드라 카메라 · VO 안내 타이머 (전 레벨 67 곳 만들기, 1050 센서 35 곳) 가 모두 멈춰 있었다 → `ScriptTimers` (13 §6.1, 28 §10).
2. **HUD 모래시계** (0x1A827C – 0x1A83D4): 표시 타이머의 경과 % · 남은 초 · 보임 (메시지 · 질문 · 카드가 없을 때) 을 바뀔 때만 PS2_Timer_Value / _Seconds / _Event 로 →
   FLP_HUDK HourGlassMeter (GotoFrame2(2 + %), 0 기반 92 부터 붉은 깜박임) [확실] → `GameScene.timerHud` (21 문서).
3. **Internal 0xD / 0xE (지금 / 바로 앞 카메라 이름) 가 "" 였다.** 카메라 관리자 +0x808 / +0x820 (전환 0x1EEE04) [확실]. ATHN06C oraclPuzzle-a06Mp03Done 의 조건
   (영상 이름 && (0xD == "UprCam21" || "UprCam3")) 이 늘 거짓이라 a06Mp03 뒤 체크포인트 · 안내 523 · 70 초 타이머가 없었다 → `ScriptListener.cameraName`.
4. **여러 쪽 메시지 · 타자 효과 · 시간 메시지의 1040** (0x1B96D0 / 0x1B8C00 / 0x1482E0) [확실]: "----" 쪽 구분 (쪽마다 1..3 줄, 여러 쪽은 세 줄 틀), ○ 마다 다음 쪽,
   첫 ○ 는 타자 끝 표시만, 글자 50 자/초, 1040 은 버튼으로 닫을 때만. 예전 엔진은 "----" 를 빈 줄로 넣어 다섯 줄 틀에서 넷째 줄부터 잘렸고 (게임 안 여러 쪽 메시지 22 개),
   시간 메시지에도 1040 을 보냈다 → `GameScene.messagePages` · `showMessagePage` · `closeMessage`, `UiVariables.typeLines` (21 문서).
5. **1008 이 Creator GO 를 지우지 않았다.** 원작 EventTransmitter 는 1008 (죽음) 을 Creator 가 만든 GO (핸들러 0x1ADEE0) 에 보내면 메시지 대신 0x1AE448 로 GO 를
   지운다 (0x1AC154 – 0x1AC198) [확실] — killOrclonWIN 이 Semo03 뒤 줄의 오라클을 지우고, 실패 때 oraclFallDIE 가 매단 오라클을 지운 뒤 떨어지는 오라클
   (aoXOR1a06hl02a) 을 새로 만든다. 엔진은 버려 구출 뒤에도 줄에 오라클이 남았다 → `GameScene.destroyCreated` (13 §4.1).
6. **FailedScreen (Internal 0x13) 이 없었다** (0x1B0B58 → 0x21D808: HUD FailedMenu, 상태 0xE) [확실] → `GameScene.failedScreen` (메뉴 → 다시 하기 / 나가기). 다시 하기는
   레벨을 처음 상태로 다시 읽어 체크포인트에 둔다 [근사: 원작은 스냅샷 복원, 11 §5.5]. a6: ARENA06 21 초 → 1050 → 메시지 2005 → FailedScreen → 다시.
7. 하네스 [엔진 전용]: 봇이 조준 가중 (CombatFileSet "Target Weight") 0 인 NPC (오라클 310 · 무덤지기 309) 를 적으로 골라 때렸다 (c5 수정 전: 대화 시작 전에 오라클이 쓰러짐)
   → `EnemyFighter.targetWeight`. 경로 단계 `jump:초:fx,fz` · `post:번호[:인자]` · `cam:이름`, 도착 상태 `In=값` (Internal 플레이어 변수, 예 I45=5 깃털).
8. **아이템 카드 위에 HP/MP 미터가 떴다.** 카드 (Pause_Check 8) · 질문 메뉴 (5) 동안은 게임이 멈춰 영웅 갱신 0x1D6CF0 → Progress_UpdateHud 0x1D7148 이 돌지 않는다 —
   AddItem 0x1D8CF0 의 표시 요청 (+0x1C0) 은 카드가 닫힌 뒤에 미터를 올린다 [확실: 호출 경로]. 영상 8825 (깃털 카드) · 8839 (고르곤의 눈 카드) 에 미터 없음.
   엔진은 카드가 열린 프레임에 미터를 올렸다 → `GameScene` 미터 갱신을 `pickupOpen` · `questionId` 동안 건너뜀 (21 문서 §5).
9. **손잡이 관절이 없는 밀기 상자를 너무 먼 데서 잡고 밀었다.** 잡기 질의 0x1C3918 은 손잡이 관절 (0x200A50 "handle%1d") 이 없으면 (0x1C3CD8) GO 몸 크기 +0x120
   (밀기 상자 = (2R, H, 2R)·16, 0x20F9F8) 의 반으로 **네 옆면 가운데** — 국소 (0, 16, −½z) 축 −z · (½x, 16, 0) +x · (0, 16, ½z) +z · (−½x, 16, 0) −x — 를
   GO 행렬로 놓고 영웅 앞 점 (발 + 위 17.6 + 정면 9.6) 과 3 차원 거리 &lt; 20 (0x41A00000), 정면·축 &lt; −0.5 인 첫 면을 잡는다 (0x1C3C7C: 국소 점 · −축 · 관절 −1).
   붙은 동안 0x1C4258 의 관절 −1 갈래 (0x1C43C0) 가 영웅 = GO 행렬 × 국소 점 − 위·반 키 + 축·반 깊이 (9.6) 로 둔다 (높이 그대로) [확실: 코드].
   엔진은 손잡이가 없으면 CDV 경계 구 반지름 + 9.6 뒤에 세웠다 — 밀기 상자 20 개 중 19 개가 손잡이 없음 (발리스타만 handle1/2): ATHN06C 석상 r 62.7 ↔ 반 너비
   0.9·16 = 14.4 라 72 유닛 (영웅 키의 두 배) 뒤에서 허공을 밀었고 (`08`), 영상 9426 – 9456 은 받침에 붙어 민다. 판도라 상자 (r 30 ↔ 14.4) · PAND04EE 테트리스 블록
   (r 64 ↔ 16) · PAND03B 미로 벽 (r 60 ↔ 32) · ATHN06B 석상 (r 52 ↔ 16) 도 같았다 → `Interactables.faceGrab` (GO 행렬 `goMatrix`, 밀면 `refreshHandles` 가 갱신),
   잡은 면이 손잡이와 같은 꼴로 `HeroNavigator.startItem` 에 간다 (38 §3.2).
10. **상자가 벽에 닿기만 해도 꿈쩍하지 않았다.** 원작 상자 벽 판정 0x210DC8 → 0x210AE8 은 막지 않고 **밀어낸다**: 상자 물리 위치 (GO + 위·6.4, +0x130) 높이의 옆면 가운데
   넷 · 모서리 넷과 그 위 +0x140 (몸 높이·16 − 6.4) 의 모서리 넷 — 12 점마다 중심 → 점 선분 (0x1E1F28 → 0x11E190, 바닥 면 거름 0x1E1EC0: 법선 y ≥ 0.5 는 지나감) 이
   벽을 넘으면 위치 −= N·((끝 − 맞은 점)·N), 앞 점의 보정이 다음 점으로 이어지고, 밀려난 쪽으로 들어가던 속도를 없앤다 (0x2113A0) [확실: 코드]. 엔진은 둘레 점의
   작은 구 (6) 가 벽에 닿으면 그 틱 이동을 통째로 막아, 옆 벽에 몸이 닿아 벽감 (폭 ≈ 32) 에 선 ATHN06B 석상 둘을 끌어낼 수 없었다 (영상 9036 → 9051 은 끌어냄) →
   `HeroNavigator.boxWalls` · `NavWorld.wallSegment`. 중심은 관절 0 이 아니라 GO 위치 (`Item.goMatrix`, 석상1 관절 0 이 GO 보다 0.7 아래).
11. **붙은 상자를 계단으로 끌어내릴 수 없었다.** 원작 붙은 상자는 바닥을 따르지 않고 수평 이동량만 받으며 (0x2119A8), 중력은 붙지 않았을 때만 (0x211A10: −50·16,
   최저 −800) [확실]. 밀기 중 영웅은 중력으로 단을 내려가고 (0x1C47B0), 잡은 점 − 반 키 17.6 과 영웅 높이 차가 32 를 넘으면 놓는다 (0x1C4674 – 0x1C46C4) [확실].
   그래서 석상을 위층 (789) 에서 계단 (764 …) 으로 끌면 영웅이 내려가는 동안 석상은 단 너머로 떠 가다 놓여 떨어진다. 엔진은 발밑 ±16 에 바닥이 없으면 상자를
   막고 영웅도 같은 높이에 붙잡아 둬, 하피 구멍 (아래 바닥 북쪽 (−3248, −1257) · 남쪽 (−3056, −1784) 의 공 r 32) 까지 석상을 옮길 길이 없었다 →
   `moveBox` (놓인 상자만 ±16 비탈·턱을 따름 [근사: ATHN05B 발리스타 내리막 유지], 깊은 단 위는 떠서), `grabTooFar` (32), `boxFall`, `follow` (영웅 중력 ·
   영웅 쪽 이동 선분 — 막히면 상자도 그만큼 되돌림 [근사: 원작은 영웅의 실제 이동량으로 상자를 옮김]), 공중에서 놓이면 공중 상태로. h8: 끌어냄 → 떨어짐 → 바닥 →
   구멍 → `Harpies-LeftVolume2-LeftHoleEntry (Statue2-Marker710)` → 하피가 막힌 구멍을 두드리는 6 초 흔들림 (TimerMcGuyverLeft 352 → Statue2-AnimWobble).
   ATHN05B 발리스타 복도 (r15 앞부분) · ATHN06C 석상 (p3) 은 그대로 [확인: 같은 경로 재실행].

### 23.3 시험
`OracleRescueTimerTest` (5: 타이머 표 · 붉은 경고 경과 % · a06Mp03 끝 + 카메라 UprCam21 → 523 · 70 초 모래시계 (다른 카메라면 없음), 70 초 → 오라클 떨어짐 → 6.4 초 뒤
실패 화면, 접시 → 타이머 지움 · Semo03), `HourglassFailedMenuTest` (3: FLP 모래시계 프레임 5 · 91 · 92 · Off, 실패 메뉴 ○ = 다시 하기, 타자 효과),
`MessagePagesTest` (오라클 342 두 쪽, 게임 안 메시지 모든 쪽 1..3 줄), `HeroNavigatorTest.handlelessBoxIsGrabbedAtItsSideFace` (옆면 20 안 · 등지면 못 잡음 ·
붙은 자리 = 면 + 9.6 · 돌아간 GO 축), `NavDataTest.athens06cStatueIsGrabbedAtItsPedestalFace` (ATHN06C 원본: 반 너비 14.4, 예전 자리 z −369 에선 못 잡음),
`HeroNavigatorTest.boxWallsPushOutSoABoxCanLeaveATightNiche` (옆 벽 0.5 벽감에서 끌어냄 · 뒤 벽 면에서 멈춤),
`HeroNavigatorTest.attachedBoxHoversOverADeepStepAndFallsWhenReleased` (단 24: 붙은 채 떠서 넘어감 → 놓으면 아래로, 단 40: 영웅이 떨어지는 사이 32 넘어 놓임).

### 23.4 증거 (`work/verify/athn_oracle_temple/`, 커밋 안 함)
`01_arena06_hourglass_16_a6` · `02_arena06_hourglass_red_2_a6` · `03_arena06_hourglass_0_white_a6` · `04_oracle_342_typing_c5` · `05_oracle_342_page1_c5` ·
`06_oracle_342_page2_c5` · `07_oracle_by_exit_door_c5` · `08_statue_push_gap_before_fix` · `09_statue_push_at_pedestal_p3` · `10_video_9336-9506_statue_push` ·
`11_video_9054-9098_06b_statue` · `12_06b_statue_to_hole_h8` · `13_06b_statue_stuck_in_niche_before_fix` · `14_06c_statue_on_elevator_e3`.

### 23.5 미대조 · 미확인
- 06B 하피 싸움 (8876–9050, 보통) · 복도 해골 셋 묶음 (DMCSpawner, 9326–9396) 은 봇 싸움을 영상 수치와 대조하지 않았다 (경로는 `nofight`).
- 들보가 무너질 때 옆 들보를 붙잡는 장면 (9163.25 · 9207.75): 원작 턱 질의는 정면만 본다 — 영상의 발 위치·방향을 몰라 엔진 결손인지 정하지 못함.
- 06C 판 아래층 · 석상 쌓기 · 덩굴 벽 (9546–9670) 경로, 06B LiftDoor2 들어 올리기 (9136–9140), 하피 구멍 막은 뒤 하피가 더 안 나오는지 (9100–9118 남은 하피와 구별 불가).
- 06A → 06B 복도 입구 고정 카메라 (8854) — 봇 순간이동으로 시점 대조 불가.

## 24. 안개를 파티클 뒤에 — 원작 안개 노드 순서 (층 2 칸 5) 와 장면 깊이 안개 (2026-10-24)

§19.3 의 검은 원반 (ATHN05A 마당·덩굴 통로·05C 지붕 불 옆 `FXC_FPemit*` → PTC_FPpart, 원반 중심색 (0,0,0) · α 151.7/128 · 알파 0x44) 은 원작에서 안개에 묻혀
보이지 않는다 (영상 6541–6545 · 6575–6584 · 7160). 원작 안개는 장면 위 후처리라 깊이를 쓰지 않는 파티클이 **뒤 장면 Z 의 계수** 로 안개색에 섞이는데,
엔진은 지오메트리 셰이더 안에서만 안개를 걸어 파티클이 안개를 전혀 받지 않았다. 엔진 = origin/main 71ea2e4 위, 화면 1280×720 lockstep.

### 24.1 원작 순서 [확실: 디스어셈블, 표시한 것 제외] (16 §1.2)
- **안개 노드**: 0x125D08 (SCR_Fog 생성자 0x20CED8 · 포세이돈 Fog Template 0x195BA8) 이 0x120 바이트 노드 (vtable 0x2BDC38 → +0x14 = 렌더 0x1281C8, +0x8 = The_Viewport
  뷰 마스크) 를 만들어 0x29CA60 에 두고 0x15F288(목록 0x3300D0, **층 2, 칸 5, 키 0x7100**) 로 넣는다 (0x12619C). §19 의 "0x12A210 → 목록 0 · 버킷 7 · 키 0x8000,
  vtable 0x2BDC10" 은 거울 관리자 +0x18 노드 (층 0 칸 7, 렌더 0x12ABA0) 였다 — vtable 0x2BDC10 +0x14 = 0x12ABA0, 0x2BDC38 +0x14 = 0x1281C8.
- **층 그리기 0x160CC0 (층 2)**: 단계 k = 0..4 마다 서버 A/B/C (지오메트리 — 주 뷰는 불투명 → 그림자 합성 0x160518 → 반투명) → 파티클 0x15DE50 버킷 k → 칸 k 노드
  0x15F4E0, 이어서 0x146188 → 파티클 버킷 5 (0x161574) → **칸 5 노드 (0x161594: 렌즈플레어 키 0 → 안개 키 0x7100)** → 파티클 버킷 6 (0x1615D4) → 칸 6 → 칸 7
  (0x1616AC), 그다음 층 3 · 4 (0x161A80). 안개 패스 (0x127918 / 0x1275E0 → 0x126CF8 → 0x127FF0 → 0x127000) 는 Z 버퍼를 프레임 알파로 바꾸고 안개색
  스프라이트를 (Cs−Cd)·Ad + Cd (ZTST GREATER 0x807FFF) 로 깐다 → 그때까지 프레임에 있는 모든 것이 그 화소 Z 의 계수로 섞인다.
- **파티클 버킷·층** (PTC 인스턴스 0x15B8D0): 버킷 +0x170 = flags & 0x4040 → 6, & 0x2000 → 5, flags&3 0 → 1 · 1 → 3 · 2 → 2/4 · 3 → 0; 층 +0xD4 = flags 0x80000 이면 3
  (0x15BB38), 아니면 생성 기본값 (0x1503E0 서버 +0x4C; 하늘 GO 는 0x218778 이 1) [기본 2 = 추정]. 목록 = 서버 + 0x13C + 뷰·0x118 + 층·0x38 + 버킷·8 (0x15D0F0).
  → **안개 앞** = 층 1 (하늘 · 하늘 GO 파티클), 층 2 지오메트리, 파티클 버킷 0–5, 렌즈플레어. **안개 뒤** = 파티클 버킷 6 (열기 아지랑이 0x40, 횃불 TFfirepart 0x4681 …),
  층 3 (PTC 0x80000 — 오브 빛 HOgpart 0x88604 · R_PERMK 빛 · 마법 PTC, 블레이드 Blades_Init, 무기 궤적 0x12DE70, 층 3 머티리얼 flags2 0x8 130 개), 층 4.
  디스크 PTC 8951 개: 안개 앞 6081, 뒤 2870 (버킷 6 1424 ∪ 층 3 1468). FPpart = 0x604 (버킷 1, 층 2) → 안개 앞.
- 곁 확인: PTC 0x78/0x7C 는 생성률 LOD (0x15BED0, 거리/16 이 near..far 에서 1 → 0, FXC 0xDC/0xE0 과 같은 꼴) [확실] — FPpart 250/300 = 4000–4800 이라 이 장면과 무관 (엔진 미구현).

### 24.2 엔진 (31 §3.1 `fx/SceneFog`, `fog.glsl`)
1. 지오메트리 안개는 그대로 (셰이더 안, 조각 깊이 — 불투명 화소는 후처리와 같은 값). 안개 공용 코드를 `fog.glsl` 로 옮겨 (`Shader.source` 의 `#include`)
   basic · particle · lensflare 가 같은 식을 쓴다.
2. `SceneFog.begin` (하늘 단계 전) → `armDepth` (사슬 뒤 · 레벨 이펙트 전 = 깊이를 쓰는 안개 앞 지오메트리 끝) → 첫 사용 때 기본 프레임버퍼 깊이를 32F 텍스처로 복사
   → `end` (카메라 필터 · 페이드 · HUD 전). 안개 앞 반투명 패스는 그 화소 장면 깊이로 같은 계수 F 와 안개색 (층 띠 · 텍스처) 을 구해 자기 색만 바꾼다:
   알파 P' = (1−F)P + F·c·As, 가산·감산 P' = (1−F)P (안개 가산·감산 모드는 알파만 ±F·c·As) = "다 그린 뒤 안개" 와 같다. 배경 (깊이 지움) 화소 = `backgroundFactor`.
3. 받는 것: PTC 파티클 중 `preFogFlags` (버킷 ≠ 6 ∧ 층 ≠ 3) — 레벨 이미터 · 날씨 · 이웃 슬롯 이미터 · 이펙트 파티클, 하늘 단계 파티클 (하늘과 같은 배경 계수), 이펙트 GO 모델
   (`AnimatedModel.sceneFog`: Creator 병사 · 파편 · 마법 GO — 불투명 조각 깊이 · 반투명 장면 깊이 · 가산 (1−F) · 층 3·4 머티리얼 없음; 예전엔 안개가 아예 없었다),
   렌즈플레어, 물 (원작 SCR_Water 는 층 2 · 3 모델 둘 — 층 2 쪽으로 봄 [근사]; 예전 게임 경로는 안개 없음). 받지 않는 것: 버킷 6 · 층 3 PTC, 근사 정의 (PTC 없음), 사슬·궤적.
4. 끄기 (비교): `-Dgow.scenefog=off` / `GOW_SCENEFOG=off`.

### 24.3 결과 (`work/verify/fog_after_particles/diff_stats.txt`, 0..255 채널 차)
| 화면 | 끔 (`scenefog=off`) vs 이전 빌드 | 켬 vs 이전 빌드 | 바뀐 곳 |
|---|---|---|---|
| c05 05A 마당 (`--arrive ATHN04C@600,-16,3010,90`, 4 초) | 최대 0 | 최대 96 · 평균 0.43 · 22.0 % | 철책 너머 불벽 (가산, (1−F) 배) · 연기 원반 → 안개색 |
| v05 05A 덩굴 통로 (11 초, 봇 경로) | 0 | 121 · 0.86 · 6.0 % | 검은 원반 사라짐, 발코니 Creator 병사 (이펙트 GO) 에 안개 |
| r05c 05C 지붕 (`--arrive ATHN05B`, 5 초) | 0 | 198 · 1.00 · 4.9 % | 지붕 밖 연기 원반 → 갈색 안개, 먼 불 |
| d01a 01A 갑판 · s02a 02A · n02d 02D (안개 없음) | 0 | 0 | — |
| s03a 03A · i04b 04B 실내 | 0 | 37 / 15 · 0.01 · 0.3–0.5 % | 먼 도시 불 · 횃불 |
| k01a 01A 전투 (봇 25 초) | 0 | 40 · 1.23 · 13.9 % | 먼 배경 앞 피 분무 (BloodSpart 0x680, 안개 앞) 가 안개색 쪽으로 |
| m03a 03A 마법 (giveAll, L2) | 0 | 61 · 0.01 · 0.3 % | 먼 도시 불만 — 제우스 번개 (층 3) 그대로 |
| w05d 05D 물 방 · w06a 06A 연못 | 0 | 16 / 117 · 0.12 / 0.37 · 7.6 / 7.8 % | 물의 먼 쪽에 안개 |
- 지오메트리: 끔이면 12 화면 모두 이전 빌드와 화소까지 같다 (셰이더 정리 · 단계 훅이 지오메트리를 바꾸지 않음). 켬의 차이는 안개 앞 반투명 패스 (물 · 파티클 ·
  이펙트 GO · 렌즈플레어) 화소에만 — `--no-fx` (레벨 물·파티클·플레어 끔) 로 c05 · r05c · d01a · s02a · n02d 0, 나머지는 이펙트 GO (v05 병사) · 이펙트 / 하늘 GO
  파티클 (03A 아레스 하늘 미니어처의 도시 불 — 하늘 단계는 하늘과 같은 계수) 화소뿐.
- 영상 대조: 6541 (철책 너머 불 — 검은 덩어리 없음) · 6578 (덩굴 통로 불 위 옅은 회색 연기 · 흐린 발코니 병사) · 7160 (05C 지붕 밖 갈색 안개) 과 켬 화면이 맞는다
  (`01`–`03`). 가까운 불·피·오브는 F ≈ 0 이라 그대로, 오브 빛·마법은 층 3 이라 안개 없음.
- 성능 (05A 마당, 수직 동기 끔, 3 회 교대): GPU 프레임 2.2 → 2.8 ms (+0.4–0.9, 깊이 복사가 렌더 패스를 나누는 고정 비용 — 64×64 만 복사해도 · 32F/24/블릿 모두 같음),
  스왑 전 CPU 5.3 → 6.2 ms (복사 지점에서 GPU 대기), 프레임 간격 7.8 → 7.3 ms (늘지 않음). 안개 앞 파티클이 없거나 안개가 없으면 복사하지 않는다.

### 24.4 시험
`SceneFogTest` (7: 실행 파일 — 안개 노드 vtable · 층 2 칸 5 키 0x7100 · 렌즈플레어 칸 5 키 0 · 0x160CC0 의 버킷 5 → 칸 5 → 버킷 6 → 칸 6 → 칸 7 순서, 파티클
버킷 0x4040 → 6 · 0x80000 → 층 3 · 목록 색인; 플래그 규칙; 05A FPpart 안개 앞 · TFfirepart 뒤 · R_PERMK HOgpart 뒤 · BloodSpart 앞; 패스 결정; 파티클 안개 식 =
"파티클 뒤 안개" (알파·가산·감산 겹침 × 안개 블렌드 0·1·2, 2000 회); 셰이더 include). 엔진 테스트 646 통과.

### 24.5 남은 것 · 미확인
- [미수정 — 지오메트리 모양은 바꾸지 않기로] 층 3 지오메트리 (블레이드, 층 3 머티리얼 130 개) 는 원작이 안개 뒤인데 엔진은 셰이더 안 안개를 건다 (멀리 선 크레토스의 블레이드가
  원작보다 흐림). 그림자 합성은 원작이 안개 앞 (−38 이 안개로 옅어짐) 인데 엔진은 안개 뒤처럼 −38 그대로 (먼 그림자가 F·38 만큼 진함). 반투명 레벨 지오메트리는 뒤 Z 대신
  자기 깊이 [근사].
- [근사] 깊이를 쓰는 PTC (0x20000, 104 개) 도 장면 깊이 사본 (자기 깊이 아님) · 이펙트 GO 반투명 파트는 그 GO 불투명 파트 깊이가 없는 사본 · 엔진은 블레이드 (층 3) 를
  파티클보다 먼저 그려 파티클이 블레이드 앞에 보일 수 있다 (원작은 층 3 이 덮음). 물의 두 모델 중 어느 쪽이 무엇인지 [미확인].
- [추정] 파티클 기본 층 = 2 (서버 +0x4C 값을 직접 보지 못함 — 0x80000 → 3, 하늘 → 1 만 덮어씀, 층 0 은 파티클을 그리지 않음).
- [미확인, 무관] 03A 마법 화면의 제우스 번개 줄기가 이전 빌드부터 검게 그려진다 (`05_magic`).
- 영상의 불 밝기 (6541 노란 불) 는 카메라 거리가 달라 정량 대조 못 함.

### 24.6 증거 (`work/verify/fog_after_particles/`, 커밋 안 함)
`01_courtyard_t6541_before_after` · `02_vine_passage_t6578_before_after` · `03_05c_roof_t7160_before_after` (영상 · 이전 · 이후) · `04_combat_01a_before_after_diff` ·
`05_magic_03a_before_after` · `06_courtyard_zoom_before_after` · `07_water_06a_pond_before_after_diff` · `08_water_05d_room_before_after_diff` · `diff_stats.txt`.
화면 조건: v05 = c05 도착 + 경로 `520,-16,2900;480,-16,2864;push:1.5:300,2864;stick:6:0,1;wait:6` 11 초, k01a = `--game ATHN01A` 봇 보통 25 초,
m03a = `-Dgow.giveAll -Dgow.script=L2:2.0:0.4` 2.8–3.3 초, w05d = `--game ATHN05D --arrive ATHN05C@-1440,864,2400,180` 경로 `-480,880,1700!` 5 초,
w06a = §21 h1 도착 + 경로 `3700,658,1280;4300,690,1280;4832,697,1283` 15 초.

## 시연 녹화 (-Dgow.record, -Dgow.bot) [엔진 전용]

- `-Dgow.record=DIR` 로직 틱 `gow.record.every`(기본 2 = 30fps) 번마다 `DIR/f000000.jpg` 저장, `gow.record.secs` 초 뒤 종료. 녹화 중에는 lockstep (프레임당 틱 하나) 이라 렌더 속도와 무관하게 영상 시간이 게임 시간과 같다.
- `-Dgow.bot` 자동 전투 (`BotInput`): 가장 가까운 적에게 걸어가 □□□△, 네 번째 묶음마다 ○ 잡기 뒤 □□□. 적이 없으면 원을 그리며 걷는다 (해치 위에 멈춰 있으면 ATHN01A 컷신이 보류됨, 28 §7).
- 예: `JAVA_OPTS="-Dgow.bot=1 -Dgow.record=work/rec -Dgow.record.secs=180" engine --game --skip-movies` → JPEG 열을 AVFoundation (Swift AVAssetWriter) 등으로 MP4 로 묶는다 (ffmpeg 불필요). 소리는 녹음하지 않는다.

## 자동 플레이 검증 (-Dgow.auto) [엔진 전용]

레벨 하나를 실제 게임 화면·물리·전투로 진행해 주 경로 다음 레벨로 넘어가는지 본다 (`game/AutoPlay`). 사람 플레이를 대신하지는 못한다 — 퍼즐 장치 조작이 없고, 3 초 안에 걸어서 못 가면 순간이동한다.

| 속성 | 뜻 |
|---|---|
| `gow.auto` | 켬. `gow.difficulty` (0 쉬움) 와 함께 |
| `gow.auto.next` | 성공으로 칠 다음 레벨. 다른 레벨 전환은 막고 기록 (`막은 전환`) |
| `gow.auto.goal` | 주 경로 전환 지점 x,y,z (`TraversalVerifier` 의 전환 위치) |
| `gow.auto.secs` | 제한 시간 (기본 300) |
| `gow.auto.log` | 결과 TSV 한 줄 (레벨, 다음, 결과, 시간, 방문, 순간이동, 사망, 막은 전환, 마지막 위치, 붉은 오브) |

동작: ① 500 안의 적 (보스전이면 보스) 과 `BotInput` 으로 싸운다 — 20 초 동안 80 안에 못 들어가거나 90 초 넘게 못 이기면 그 적은 포기 (보스는 포기 안 함), HP 32767 소품 (ATHN01F 침대) 은 적이 아님.
② 진입/이탈 센서 볼륨의 내부 점을 가까운 순으로 방문 (`TraversalVerifier.interior`, 공중 점 제외), 안에서 한 번 점프. 걷다가 죽으면 다음엔 순간이동, 순간이동한 곳에서 죽으면 그 볼륨 포기.
③ 목표에서 10 초 주기: 6 초는 목표 위에서 R1·×, 4 초는 물러남 / 목표 너머 400 (양쪽 번갈아 — LoadCheck 경계 안쪽에 다음 레벨 바닥, ATHN03AA). 20 초가 지나도 안 넘어가면 목표 1000 안 볼륨을
먼 것부터 다시 (마지막 LoadForWarp 가 슬롯 레벨을 정함). 90 초 뒤 `STUCK_AT_GOAL`. 질문 메뉴는 ×. 보스 미니게임 (연타·타이밍 버튼) 만 `BossFight.autoMinigames` 로 자동 입력 — 데모 연출 (`--boss-demo`, 피해·잡기 강제) 은 쓰지 않는다.

주 경로 52 전환 (ATHN01A → OLYMP02) 을 레벨마다 새로 시작해 (앞 레벨 도착점에서, ATHN01 뒤로는 `gow.giveAll`) 420 초씩 돌린 결과 (2026-10-23, 쉬움):
1 차 15 OK → 3 차 26 OK / STUCK_AT_GOAL 14 / TIMEOUT 10 / DEATH_AT_GOAL 2, 예외 0. 남은 실패는 대부분 봇이 못 하는 조작 (상자 밀기·레버·크랭크·기어오르기·줄) 이다.

보스전 (데모 없이 봇 + 미니게임 자동 입력, 600 초): 메두사 여왕 ATHN02D — 잡기 원 → 스틱 회전 → 머리 뜯기 → 메시지 141 "메두사의 시선 획득" (약 20 초, 아래 수정 뒤),
아레스 1 단계 ATHN08A — 격파 (398 초, 사망 10). 못 끝낸 것 (봇 한계): 히드라 ATHN01E (영웅이 돛대 위 y 832 에 올라가 갑판 구역 0 — 구역 볼륨 51..56 밖),
BMI PAND03G (보스 등장 조건 미충족), 분신 크라토스 ATHN08B (가족 보호 HP 73 까지), 아레스 3 단계 ATHN08C (칼 맞대기 진입 못 함).
메두사 여왕 뒤 미노타우로스 방 (2026-10-24, 37 §5.2): 봇이 시선 (← 선택, L2 홀드 + □ 홀드) 으로 석화하고 □ 로 부숴 6 번째 파괴 98.1 초 → 문 카메라·아테나 얼굴
99.1 초 → DMCWall 107.1 초. 고친 것: 광선 = □ 홀드 (0x1972E8), 석상 파괴 1046 전역 이벤트 (0x265E04), 부서진 석상 즉시 제거 (두 번 세기 방지).
증거 `work/verify/athn02d_gaze/1_beam_91.8s.jpg`, `2_petrified_93.0s.jpg`, `3_shatter_93.4s.jpg`, `4_door_cam_98.8s.jpg`, `5_flame_wall_off_107.5s.jpg`.
석화 겉모습 (2026-10-24, 35 §2.2): 원작 영상은 석화된 미노타우로스가 온통 회색 돌이고 부수면 돌 덩어리가 튄다. 엔진은 색이 그대로였고 회색 파티클만 냈다.
원작은 몸을 다시 칠하지 않고 Sold +0x20 석상 GO (goMINStonedBRK: 돌 텍스처 GFX_minotaurstone 조각 모델, Brkb 517 타수 7) 로 바꿔 끼운다 (0x19F8E0 → 0x19F798 → 0x1E8960)
[확실] → `EnemyFighter.StoneLook` / `MagicFx.stoneLook`. 석상 메시는 같은 이름 MAT_body 의 두 번째 정의를 써야 해서 MAT 이름 해석도 고쳤다.
같은 재현 (쉬움, 봇): 회색 석상 84.0 초, 첫 파괴 85.8 초 (조각·돌 먼지, 적중 MFX 22 돌), 6 번째 EventSmash1 115.6 초 (예전 98.1 초 — 석상이 한 방이 아니라 □ 4 번). 그 사이 Respawn 이 더 내 6 번째 때 스폰 수 ≠ 없어진 수라
문 조건 (LevelData1 ≥ 6 && LevelData3 == LevelData4) 이 안 맞고, 봇은 MP 가 바닥나 (미노타우로스 오브는 붉은 오브뿐) 남은 미노타우로스를 보통 공격으로 늦게 잡아 280 초 안에 문이 안 열린다 [봇 한계 — 스크립트·데이터는 그대로].
증거 `work/verify/athn02d_statue/before_petrified_93.0s.jpg` (예전), `after_statues_84.0s.jpg`, `after_shatter_86.3s.jpg`, `hero_before_petrified.png`, `hero_after_statue.png`, `hero_after_shatter.png`.
MP·리스폰 정밀 검사 (2026-10-24, 37 §5.3): 위 "봇 한계" 는 엔진 결함 둘이었다 — (1) 스크립트 Internal 0x12 (`RewardPlayer` 10 = 마법 재생 10 MP/초,
0x1B0B34 → 0x3044A4, 영웅 갱신 0x1D6F20) 를 버려 MP 가 바닥났다, (2) 여왕 머리 뜯기 결합이 끝나는 틱의 "가장 가까운 적" 을 처형 성공으로 죽여 68.88 초에
막 스폰된 미노타우로스가 HP 0. 리스폰·카운터 (SpawnEnemy 조건 LevelData1 ≤ 6 · 지연 7 초, MinoDestruction1 0.5 초) 는 원작 스크립트와 같고 교착은 없다.
봇: 광선 조준 = 봇 대상, 광선 중 정면 ±60° 220 안의 다음 대상으로 넘김, 게이지가 0.4 초 안 줄면 뗌, 석상은 △ (6) + □ (2), 석상에 ○ 없음.
결과 (쉬움, 결정적 3 회 같음): 파괴 83.6 / 86.8 / 87.1 / 93.6 / 96.9 / 100.0 초 (6 번째에 문 조건) → FinalCamOn 100.0, CheckPoint2 108.0, DMCWall VisOff
109.0 초 (여왕 EventCombat1 62.9 초 뒤 46 초, 영상 약 75 초), TurnFaceOffFinal 110.0 초 (재생 0). 예전: 6 번째 115.6 초, MP 15 에서 멈춤, DMCWall 179.8 초 (280 초 실행 중 한 번).

이 검증으로 찾아 고친 엔진 버그:
- **갑판 아래로 떨어짐** (ATHN01A): 위에 있는 적 몸이 영웅을 한 틱에 10–14 아래로 밀면 다음 틱 바닥 질의가 갑판 밑에서 시작해 ATHN01B 로 떨어졌다 → 38 §19 후속.
- **미노타우로스가 무적으로 멈춤** (ATHN03A·03C·SEWR 등): 적 피격 반응의 잡기 조건을 건너뛰어, 잡기 (GRAB 태그 200) 에 맞은 HP 가득한 미노타우로스가 혼자 MAIMIN01 → MAIMIN02 (재생 속도 0) 에 들어가 결합 없이 영원히 무적 → 원작 0x208800 대로 적은 자기 잡기 가능 여부 (10 §2.4, 26 §11).
- **갑옷 미노타우로스가 처형에 혼자 멈춤** (ATHN03A 철로 구역 R_MI2, 쉬움): ○ 잡기에 적만 400 MAIMIN01 → 401 MAIMIN02 (재생 속도 0, 무적) 에 들어가고 영웅은 그대로 — HP 8 로 90 초 동안
  죽지 않아 봇이 포기했다. 전투 분기의 종류 (자신 0x14 / 상대 0x16) 를 AI1 번호 (51) 로 비교해 영웅 GRAB 의 HIT_CONFIRM tgt=50 → 400 이 안 맞았다. 원작 종류는 AI 객체 가상 슬롯 3
  (0x244AF8 → AI1 템플릿 +0x9C = 해시 0x247B) 이고 R_MI2 51 은 50 [확실: 0x208A24·0x2041CC, 템플릿 데이터] → `AiTemplate.classId`. 디스크 AI1 92 개 중 31 개가 번호와 다르다
  (R_MI2/4/5/F → 50, R_SKA·SK2–5 → 20/22/26, R_SKB 24 → 25, R_HA2/HAF → 70, 시민 301·313 → 280, 아기 히드라 258 → 253, 분신 667–669 → 666 …, 10 §2.3). 수정 뒤 같은 실행에서
  R_MI2 5 마리가 400 → 401 → 402 MAIMIN03 으로 처형되고 (240 초) 오브가 마리당 40 오른다 (`GrabTest.armoredMinotaurExecution`, `EnemyFactoryTest.combatClassIsTemplateClassField`).
  같은 자리 (-1168, 1023, -272) 생성기가 죽을 때마다 두 마리씩 다시 낸다 — 미노타우로스 발판 퍼즐 (`gominopressureplate`·`goinstantminodeath`) 용으로 보이나 원작 대조 안 함 [미확인].
  `-Dgow.debug` 적 줄에 HP·기술·무적·잡기 가능, `-Dgow.debug.combat` 은 영웅·적 기술/적중 기록. `-Dgow.debug.script` 은 발동한 스크립트 엔티티 (시각·이름·보낸 쪽).
- **선창 히드라가 뒤집힘 / ○ 마무리가 안 잡힘** (ATHN01B 첫 히드라, 사용자 플레이 보고): 매달린 BHAenc1* 클립을 Z 축 180° 로 세워 머리 등이 아래였다 → 옆축 50.7° 들어 올림.
  (2026-10-24 정정, §20: 들어 올림은 ANM 디스크립터 배치 착시를 메운 것 — 원작 배치의 골반 이동으로 목이 위 선체에서 내려오고 머리가 바로 선다, PS2 영상 272.5–342 초.)
  ○ 는 넉백으로 뿌리가 84 밀려 기절 머리가 손에 안 닿고, GRAB 의 HIT_CONFIRM 이 플래그 0x4000 (원작 = 자세 태그 부정, 엔진 = 무시) 때문에 GRABREJECT 와 동점,
  830 중 사망 뒤 150 (길막 치우는 DetectDeath) 이 안 나왔다 (원작 매 프레임 EV_DEATH 재검사 0x209024) — 37 §4.3. 봇: ○ → 830 → 150 → goplayerblock → 다음 구역.
- **검증기 Warp 출구가 되돌아가는 Warp** (ATRET01A 등 6 곳): 36 §2 `warpFor`.
- **메두사 여왕이 넘어지기만 해도 격파** (ATHN02D, 데모 없이 실제 전투): 피격 착지 hitLand 의 SCR_ExplodeDeath (이벤트 조건 6 = 사망) 를 조건 없이 발동해 HP 9808 에서 사망 → 조건 6 은 HP ≤ 0 일 때만 (10 §3, `MedusaQueenFightTest.landingDoesNotExplodeWhileAlive`).
- 아레스 데모 연출이 영웅 높이를 도착 Start (−417) 그대로 두어 투기장 밖 허공으로 떨어짐 → 높이도 아레스 쪽.

엔진 문제가 아니었던 것: HDES01D 사망 무한 반복 (봇이 죽는 바닥으로 순간이동을 되풀이), ATHN01F 출구 (Warp 영역 1 이 계단 위 바닥 +28 부터라 점프해야 닿음 — `ScriptWorldTest.athn01fExitWarpRegionAboveStairs`;
원작 센서 접촉점은 오브젝트 관절 하나 (0x1A6308, 모델 인스턴스 +0x86 관절 = 스켈레톤 정의 +0x3C) 라 발이 아닐 수 있다 [미확인 — 어느 관절인지]), HUD 오브 숫자 0 (300 단위 배수, 11 §1).
