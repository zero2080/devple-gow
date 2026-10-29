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
| PAND03F / PAND03J ← PAND00C Warp (CoreTubeCam4) | 관 벽 | ~~데이터대로 (§13.9)~~ → **§44: 떠나온 레벨에 남은 대상 슬롯의 1.6 끈으로 u 9.25 (영웅 뒤)** — 카메라 해독 그대로: 레일 두 극소 u 9.25 (비용 3.46) / u 14.5 (2.75), 0x400 접선 좌표계 요 −180±60 은 u 14.5 만 만족 → 관 바깥 눈. PAND02B 와 같은 종류 (§8.2) |
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

**1. CoreTubeCam4 (PAND03F/03J ← PAND00C) · camFRMCLFFS (PAND02B 기본 시작) — 데이터대로** [확실: 디스어셈블] → **PAND03F/03J 는 §44 정정 (Warp 를 넘어 남는 카메라 대상 슬롯)**
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
| PAND03F/03J ← PAND00C, PAND02A ← PAND00C | CoreTubeCam4 / Follow | ~~데이터대로 (§13.9)~~ → §44: 영웅 뒤 CoreTubeCam4 / camFromWarp |
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
- 새 기술 화면의 인라인 버튼: 영상은 L1 · R1 글자가 있는 배지 + 바탕 없는 △□ 기호, 엔진은 글자 없는 배지 + 둥근 바탕 (StaticTextL1 의 글자 출처 못 찾음) [미확인] (21). → §25.3-4: 글자 = 아이콘 클립 안 동적 라벨, 값은 FLP_HUDK 루트 프레임 0 의
  SetVariable — 인라인 아이콘이 이제 그린다 (L2 배지로 확인, 이 화면은 다시 보지 않음).
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
- ~~이음 직후 다른 슬롯 렌더 장면을 다시 준비하는 동안 (r11 8.6 초) 그 레벨만 지오메트리를 가진 곳이 검게 보였다 (아래층 복도 = 04A 렌더) — r12 에선 없음 [미확인: 적재 속도].~~
  → §27: 이음은 떠난 레벨 장면을 그대로 다른 슬롯 장면으로 쓴다 (다시 준비하지 않음).
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
   길다]. 영상 6539 → 6545 이상 ↔ f3 12.8 → 23.5 초. → §27: 이음이 같은 씬이라 HUD 가 그대로 — 넘겨 띄우기는 예전 길 (보스 레벨이 낀 이음) 에만 남았다.
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
- ~~이음 순간 0.4 초 페이드 아웃·인 (엔진 구조, 원작은 끊김 없음) [엔진 표시]. 구역 제목이 이음까지의 시간만큼 길다 [근사].~~ → §27 (이음 = 같은 씬에서 현재 슬롯만
  바꿈: 페이드 없음, HUD 가 그대로라 제목은 한 번 원래 10 초).
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
- ~~체크포인트 재시작은 위치·방향·체력만 (원작은 스냅샷 역직렬화 + 레벨 다시 적재 — 적·스크립트·상자 상태도 되돌린다) [근사].~~ → §26 (스냅숏 되읽기). 재시작 자리가 체크포인트 구 안이라 엔진은
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
| 8210–8224 | "YOU ARE DEAD" → 기둥 방에서 다시 → 깃털 카드가 또 (체크포인트가 상자 앞) | ~~엔진 재시작은 체크포인트로 옮기기만 해 세계를 되돌리지 않는다 [미구현, §21.3]~~ → §26: 스냅숏 되읽기, k7 38.1 초 사망 → 레버 방 → 50.6 초 닫힌 상자 → 같은 카드 |
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
   센서 한 번 발동 기록은 담지 않는다 — 원작 기록 목록의 엔티티 발동 상태는 미확인] → §26: 발동 기록 (+0x30, 메시지 7/8) · 살아 있는 적 · 미뤄 둔 발동 · Animator 몸 ·
   부순 물체도 담는다.
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
- ~~8210–8224: 원작 체크포인트 재시작은 메모리 세이브 (0x22D418) 를 되읽어 레벨을 다시 올린다 (11 §5.3) — 상자 (깃털) 가 닫힌 채로 돌아가 다시 줍는다. 엔진은 영웅만
  체크포인트로 옮겨 세계가 그대로라 깃털 카드가 다시 나오지 않는다 [미구현: 스냅숏 되읽기]. (69cfd3e 의 FailedScreen 다시 하기는 레벨을 처음 상태로 새로 읽지만
  보통 사망 (DeadMenu) 은 여전히 자리만 옮긴다.) 연 상자 기억 (§21.2-6) 은 이 재시작에 끼어들지 않는다 (세계를 다시 만들지 않으므로).~~ → §26 (스냅숏 되읽기).
- ~~05D 복도에서 봇의 공격에 적이 서쪽 벽 (x −1000) 너머 · 항아리 방 아래로 밀려 ATHN05C 웅덩이 바닥 (y 453) 에 떨어진 적이 있다 (r2 105–155 초, c2 55–110 초 —
  봇이 닿지 않는 그 적을 계속 노림) [미해결: 적 넉백 벽 충돌].~~ → §28.2: 그 적은 복도 해골이 아니라 이웃 05C 시작 스폰 R_SKA 032 (발코니에서 떨어져 웅덩이 바닥으로 따라옴) —
  넉백과 무관. 밀린·끌린 적의 같은 틱 벽 처리는 따로 고침 (`EnemyNav.settle`, 10 §7.2).
- ~~항아리 방 → 횃불 복도 (8110–8120) 걷기 이음에서 엔진은 0.4 초 어두워졌다 밝아진다 (t1 15.8–16.7 초, `SceneManager` 전환 페이드) — 원작은 끊김 없음. 이미 적힌
  엔진 구조 항목 (§19 "이음 순간 0.4 초 페이드") [엔진 구조].~~ → §27 (같은 씬 이음 — 그 걷기 이음 프레임 4.70 / 앞뒤 4.93, 밝기 그대로).
- 깃털 카드 문구의 개수 (영상 "1 개를 더" = 깃털 5 개째) 는 앞 레벨에서 모은 개수에 달렸다 — 하네스 도착은 처음 상태라 "5 개를 더" (같은 규칙) [대조 불가: 상태].
- 동영상 a04Mp01 · a06Mp01 · a06Mp04 는 원작 PSS 그대로라 대조하지 않았다 (`--skip-movies` 와 o1 재생으로 앞뒤 스크립트만).
- 06A 구역 제목: 하네스 도착점이 ZoneReportGroup 볼륨 안이라 처음엔 뜨지 않는다 (이음 도착은 "이미 안", 원작은 04D 계단을 걸어 들어오며 뜬다) [엔진 전용].
- 하피 마당 처치 시간은 봇 (183 초) 과 영상 (≈ 100 초, 메두사의 시선 사용) 이 달라 대조하지 않았다. 무덤 파는 노인 (AOXDI: Ignore Hits · Target Weight 0 ·
  결정 트리 없음) 을 봇이 때렸다 [엔진 전용] → 69cfd3e 뒤 봇은 Target Weight 0 NPC 를 고르지 않는다. 노인 R2 대화 (335/336, camGraveD) 는 영상에 없다.
- ~~레벨 기억이 센서의 한 번 발동 기록을 담지 않아 재방문 광장에서 북쪽 계단 DMC TriggerVol0 에 다시 들어가면 키클롭스 결투가 다시 시작된다 (LevelData0 이 3 에서
  늘어 결계가 안 열림) — 원작이 같은지 미확인 (영상 경로는 그 볼륨에 가지 않는다) [미확인].~~ → §26: 원작은 덩어리 +0x30 에 발동 기록을 담아 (재발동 −1 = 한 번만)
  다시 읽어도 결투가 다시 열리지 않는다 [확실: 0x1AA410 메시지 7/8 · 0x1B2280 · 0x1B2360] — 엔진도 같게.

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
| 7956–8056 | 제우스의 분노 첫 사용 · 세이브 · 벽 문장 · 안내 상자 | [미대조] (경로로 몰지 않음) → §25.3 |
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
- 싸움 (해골 다섯 7526–7576, 군단병 7862) 은 보통 난이도로 대조하지 않았다 [미대조]. 7956–8056 (제우스의 분노 첫 사용 · 세이브 · 안내) 은 경로로 몰지 않았다 (→ §25.3).
- 횃불 불꽃 (FXC_TFfireemit*, 서브타입 1, rate 45) 이 영상 (7740 · 7852 · 7858) 처럼 보이지 않는다 — 엔진 이미터 자리 = FXC 로컬 · 관절 0 · 인스턴스 인데
  05BB gocellroom 문 옆 둘 (TF29/30, 로컬 x −298 / −24) 이 바닥 아래·문 뒤 (y 363, z 5284) 에 놓인다 [미확인: 이미터 기준 노드].
  → §25.1 해결: 자리 규칙은 맞았고, 같은 이름 다시 정의 (오브젝트 앞 마지막 정의) · CPU 스트림 PTC 움직임 (0x15C0F0) · 이미터 월드 배율 속력이 빠져 있었다.
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
| 9150–9310 | 위층 들보 홀 (06B, y 992): 동서 정적 들보 (z −1600 · −1440, 폭 6) 사이의 남북 들보 17 개가 FallingBeam (Push 아님 — ENV 진입 → Animator "!0" 모드 8, act 4 초: 0.5 초 흔들림, 0.75–1.5 초에 한쪽 끝부터 기울며 250 아래로). 영상은 두 번 무너짐 (9163.25 · 9207.75) — 둘 다 크라토스가 옆 동서 들보에 매달려 다시 오름, 9261.85 은 아래층으로 떨어져 붉은 카펫 계단으로 되돌아옴 | m2: 진입 볼륨 (들보 ±40 × ±119, 동서 들보 위 교차점도 듦) → 0.6 초 뒤 무너져 영웅이 아래층 (736) 으로 떨어짐. 무너질 때 옆 들보 붙잡기는 봇 경로에서 재현 못 함 — ~~원작 턱 질의 0x1E3A80 은 정면 3.2 + 9.6 만 보므로 교차점에서 들보 쪽을 볼 때만 잡힌다 [미확인: 영상의 위치·방향]~~ → §28.1 평균대 붙잡기 |
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
   ~~레벨을 처음 상태로 다시 읽어 체크포인트에 둔다 [근사: 원작은 스냅샷 복원, 11 §5.5]~~ → §26: 스냅숏 되읽기 (원작과 같은 0x21E3D0). a6: ARENA06 21 초 → 1050 →
   메시지 2005 → FailedScreen → 다시.
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
- ~~들보가 무너질 때 옆 들보를 붙잡는 장면 (9163.25 · 9207.75): 원작 턱 질의는 정면만 본다 — 영상의 발 위치·방향을 몰라 엔진 결손인지 정하지 못함.~~
  → §28.1: 턱 질의가 아니라 좁은 바닥 단계 0x1BFBF0 의 평균대 붙잡기 (한 손 ANIBalanceCatch) — 엔진 결손이었다 (38 §24).
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
- ~~[미수정 — 지오메트리 모양은 바꾸지 않기로] 층 3 지오메트리 (블레이드, 층 3 머티리얼 130 개) 는 원작이 안개 뒤인데 엔진은 셰이더 안 안개를 건다 (멀리 선 크레토스의 블레이드가
  원작보다 흐림). 그림자 합성은 원작이 안개 앞 (−38 이 안개로 옅어짐) 인데 엔진은 안개 뒤처럼 −38 그대로 (먼 그림자가 F·38 만큼 진함).~~ → §31.2: 영웅·적·자세 GO 셰이더는
  실제로는 안개를 아예 받지 않았고 (`applyFog` 가 프로그램을 묶지 않음, §31.2-1), 블레이드·아르테미스의 검은 층 3 이 아니라 flags2 0x100 층 2 라 안개를 받는다 (원작도,
  §31.2-2). 층 3·4 머티리얼 (130 개) 파트와 사슬·궤적 띠 (층 3, Blades_Init · 0x12DE70 의 0x23EB58 인스턴스) 는 안개 없음 그대로 (`AnimatedModel` uFogOff,
  `BladeChainRenderer`). 그림자 합성은 38·(1 − F) (§31.2-3). 남은 것: 반투명 레벨 지오메트리는 뒤 Z 대신 자기 깊이 [근사].
- [근사] 깊이를 쓰는 PTC (0x20000, 104 개) 도 장면 깊이 사본 (자기 깊이 아님) · 이펙트 GO 반투명 파트는 그 GO 불투명 파트 깊이가 없는 사본 · 엔진은 ~~블레이드 (층 3)~~
  사슬·궤적 띠 (층 3, §31.2-2 — 블레이드 모델은 층 2) 를 파티클보다 먼저 그려 파티클이 띠 앞에 보일 수 있다 (원작은 층 3 이 덮음). 물의 두 모델 중 어느 쪽이 무엇인지 [미확인].
- [추정] 파티클 기본 층 = 2 (서버 +0x4C 값을 직접 보지 못함 — 0x80000 → 3, 하늘 → 1 만 덮어씀, 층 0 은 파티클을 그리지 않음).
- [미확인, 무관] 03A 마법 화면의 제우스 번개 줄기가 이전 빌드부터 검게 그려진다 (`05_magic`). → §25.2: 하데스의 군대 (감산 머티리얼) 였고, 자세 GO 가 빠뜨린 2 번째 레이어를 고쳤다.
- 영상의 불 밝기 (6541 노란 불) 는 카메라 거리가 달라 정량 대조 못 함.

### 24.6 증거 (`work/verify/fog_after_particles/`, 커밋 안 함)
`01_courtyard_t6541_before_after` · `02_vine_passage_t6578_before_after` · `03_05c_roof_t7160_before_after` (영상 · 이전 · 이후) · `04_combat_01a_before_after_diff` ·
`05_magic_03a_before_after` · `06_courtyard_zoom_before_after` · `07_water_06a_pond_before_after_diff` · `08_water_05d_room_before_after_diff` · `diff_stats.txt`.
화면 조건: v05 = c05 도착 + 경로 `520,-16,2900;480,-16,2864;push:1.5:300,2864;stick:6:0,1;wait:6` 11 초, k01a = `--game ATHN01A` 봇 보통 25 초,
m03a = `-Dgow.giveAll -Dgow.script=L2:2.0:0.4` 2.8–3.3 초, w05d = `--game ATHN05D --arrive ATHN05C@-1440,864,2400,180` 경로 `-480,880,1700!` 5 초,
w06a = §21 h1 도착 + 경로 `3700,658,1280;4300,690,1280;4832,697,1283` 15 초.

## 25. QA vs PS2 영상 — 벽 횃불 불꽃 · 밀린 발리스타 틀 색 · 제우스의 분노 첫 사용 (영상 7716–8066 초, 2026-10-24)

§22.4 의 남은 것 셋 (횃불 불꽃이 안 보임, 밀면 발리스타 틀이 검음, 7956–8056 제우스의 분노 첫 사용 [미대조]) 을 다시 봤다. 엔진 = origin/main 4f4439c 위 (§23 밀기 상자 ·
§24 안개 포함), 화면 1280×720 lockstep. 제우스의 분노는 영상과 같은 보통 (`-Dgow.difficulty=1`), 횃불·틀은 쉬움 (난이도 무관).
- c (복도·홀 끝 횃불): `--game ATHN05B --arrive ATHN05BB@-720,480,4307,90`, 경로 `-600,480,4307!;wait:2;-80,428,4900!;wait:2`. h (홀): 같은 도착,
  `-80,428,4560!;wait:2;-160,428,4720!;wait:2;20,428,4720!;wait:2;-80,428,4980!;wait:2`.
- 틀: §22 r15 앞부분 (`-712,480,4307!;use:-650,4307;hold:34:-121,4300;hold:24:-121,4735;hold:5:-27,4735;wait:0.5;195,424,4729!;use:223,4729;wait:5`) — 22d2e77 위에서 다시 녹화해도 같다.
- r4 (제우스 석상, 보통): §22 그대로. s (세이브 제단): `--game ATHN05B --arrive ATHN05A@-880,484,3640,270`, `-1230,480,3672!;wait:1;-1315,480,3675,6;wait:3`
  (뒤에 `use:-1341,3675` 를 붙이면 AUTO 가 저장까지 답해 `~/Library/Application Support/devple-gow/slot0.save` 를 만든다 — 한 번 확인하고 지웠다).
- z (첫 사용, 보통): `--game ATHN05C --arrive ATHN05B@-1450,864,2830,180`, `-Dgow.giveAll=1 -Dgow.script=DOWN:2.5:0.1,L2:3.5:1,CIRCLE:6.5:0.1,CIRCLE:7.0:0.1,L2:7.5:4,SQUARE:8.5:0.3,SQUARE:10.0:0.3`.

### 25.1 벽 횃불 불꽃 [확실: 디스어셈블 · 데이터, 표시한 것 제외]
원작:
- **자리**: 이미터 월드 = FXC 로컬 (+0x10) · 소유 GO 관절 행렬 · 인스턴스 — 0x1227C8 → 0x130140: 노드 +0x60 (= FXC 헤더 +0x08 s16) 이 −1 이면 노드 로컬만, 아니면 로컬 ·
  소유 GO 관절 (0x131618 → 모델 +0x8C, 0x136990). 헤더 +0x0A 링크 (0x130240) 는 act 채널 id (40 §3) 라 자리와 무관 — 여기까지는 엔진 규칙과 같았다.
- **어느 정의인가**: 레벨 WAD 는 오브젝트 그룹마다 자기 FXC_/PTC_ (필드 · 0xD 노드 포함) 를 그룹 바로 앞에 **같은 이름으로 다시 정의**한다 — 05B `FXC_TFfireemit10` 은 지붕 출입 방
  로컬 (666, −171, −812) · 궁수 발코니 (349, −50, 557). 순차 적재에서 오브젝트가 참조를 풀 때 보이는 것은 그 앞의 마지막 정의 (MAT_·TXR_·SEM_ 과 같은 규칙) [확실: 데이터 /
  추정: 레지스트리가 마지막 등록을 돌려줌]. 엔진은 이름마다 처음 정의를 써서 전 레벨 배치 이미터 3893 중 1514 가 다른 자리·PTC 였다 (05B 발코니 횃불이 (333, 427, 3178) —
  가장 가까운 렌더 정점이 174 떨어진 허공; 자기 정의로는 (174.6, 486.9, 3862.5)).
- **움직임**: PTC 배치 바이트 0xB (PTC+0x97) bit 0 = CPU 스트림 → 파티클 서버 매 프레임 갱신 0x15C0F0 (PTC 서버 vtable 0x2BE5EC) 이 블록마다 위치 = 처음 위치 + 속도 · 나이 로
  VU 레코드를 다시 쓰고 (0x12B2B8 / 0x12B3F0), 소유 GO 에 필드 노드 (FXC 0xC) 가 있으면 0x12B530 (점프 표 0x2B29F0) 이 힘을 더한다 — 종류 3 (중력) · 6 (균일) 은
  normalize(dir · 필드 노드 월드) × 크기 × 16. VU 식은 위치 연산 0 (C0) 이라 이 처리가 없으면 파티클이 생성 점에 멈춘다 — 받침 (MAT_1decorAthens64Torch, 이미터 위아래
  −10.5 … +9.7) 안에 반폭 ≤ 4.8 불꽃이 갇혀 안 보이던 원인. 전 디스크 PTC 5585 중 739 (TMfire · TFfire · hangfire 불·연기 대부분), 레벨 배치 이미터 1116 (필드 202).
- **속력 · 생성 거리**: 생성 행렬 M = R_dir · 이미터 월드 · inv(시스템) (0x122C88 → 0x1227C8 → 0x122E78) → 이미터 월드의 배율이 속력과 생성 거리 (P+0x18/0x1C × 16) 에 그대로
  들어간다. 05B 복도 청동 받침 TMfire: 방 관절 0.5 × FXC 로컬 6.63 = 3.32 → 0.45·16·3.32 = 23.9/초, 수명 0.85 초에 20 솟아 받침 위로 나온다 (예전 7.2/초).

엔진: `FxResources.emitter/field/shape/geom(이름, 오브젝트 태그)` (`LevelLayout.LevelObject.tag`) 를 `EffectLibrary` · `LevelEmitters` · `curveSamples` 가 쓴다.
`ParticleDef.cpuMotion` · `ParticleSystem.Emitter.cpuMotion/fieldAccel/coneDistMin·Max` — 레코드 위치 += v·t + a·t²/2 [근사: 필드는 상수 가속, 소용돌이 · 뉴턴 · 감쇠 · 최대 거리 생략],
`LevelEmitters.speedScale/fieldAccel`, 레벨 GO 관절 이미터 (`EffectSystem`) 도 같은 속력 배율 · CPU 동역학 (근사 속력을 쓰는 R_PERMK 물보라 같은 이펙트는 예전대로).
- 결과 (`01`, `02`): 05B 복도 청동 받침 · 홀 기둥 받침 · 홀 끝 문 양옆 등불 (05BB gocellroom TF29/30 = (−149, 469, 5042) / (−12, 470, 5042)) 이 영상 7716 · 7792 · 7852 처럼
  받침 위로 솟는다. §22.4 의 "바닥 아래 · 문 뒤 (y 363, z 5284)" 는 지금 계산으로는 나오지 않는다 [미확인: 그때 경로].
- 전 레벨 (렌더 정점 10 밖 불 이미터): 1610 중 예전 486 → 60 (고침 426, 새로 밖 0). 남은 60 은 [미확인: 하나씩 대조 안 함].
- 회귀 아님: b4b50e2 (§20 ANM 전환 전) · 9403d31 · 68b8f16 모두 같은 카메라에서 불꽃이 없다 (`01` 2–3 열).
- 곁 수정 (같은 정의 규칙): ATHN03A GateExplosion 폭발 이미터 emitter11 / emitter12 / Demitter11sub / Demitter11 은 이 오브젝트 앞 정의의 링크가 7–10 → 프레임 14 에
  600 / 500 / 600 / 1000 개 터진다 (불덩이가 성문에 닿는 순간). 예전 (처음 정의 = 다른 오브젝트, 링크 22–25) 은 채널 밖이라 터짐이 없었다 (40 §3 정정 4).
- 안개 버킷 (§24): TFfirepart 0x4681 & 0x4040 = 0x4000 → 버킷 6 (안개 뒤), TFsmokepart 0x681 → flags & 3 = 1 → 버킷 3 · 층 2 (안개 앞) — 원작 0x15B8D0 규칙 그대로 [확실].
  05B 실내는 안개가 옅어 영상으로는 가를 수 없다 [미대조].

### 25.2 밀린 발리스타 틀이 검었다 [확실: VU1 마이크로코드 구조 · 데이터 / 근사: 축 부호]
- 틀 (손잡이 레일) 머티리얼 `MAT_M02MetalTrim01b` — 05B 에 정의 하나, 대소문자 변형 없음 (aa0d0ae 의 대소문자 무관 이름 해석과 무관): 레이어 0 rf 0x86 · `TXR_entities02_file330`
  (크롬 공 사진, 원 밖 검정), 레이어 1 rf 0x01010080 (GS 0x01 반전 블렌드, 메시 UV).
- 원작: rf 하위 비트 = VU 플래그. bit 1 (0x02) = 환경맵 경로 — 메시 VU1 마이크로코드 (오버레이 0x2ABB00–0x2AE000) 0x0D9–0x0E3 이 법선을 모델·뷰 행렬 행 (배율 제거) 으로 옮겨
  UV = 0.5 − 0.5·(n·행).xy 를 쓴다 (ST 출력 vf16–19). 레이어마다 자기 플래그로 같은 프로그램을 돌리므로 (16 §3.3) **첫 레이어도 환경맵**이다. bit 2 (0x04) 는 머티리얼 색
  (qw76) — 환경맵이 아니다 (예전 엔진은 0x04 도 환경맵으로 봤다).
- 엔진은 첫 레이어를 메시 UV 로 찍어 크롬 공의 검은 바깥이 틀에 깔렸고, 밀기 시작하면 자세 GO 경로 (`AnimatedModel`) 가 첫 레이어만 그려 정적일 때와 또 달랐다 →
  `MaterialFx.env0` · `ENV_FLAG` (0x02), `Renderer` 첫 레이어 환경맵, `basic.vert` · `skinned.vert` 의 `uEnvView` (월드 → 카메라 회전, `Renderer.envView`, 거울 패스는 반사 포함) 로
  카메라 공간 구면 UV, `AnimatedModel.drawLayers` (2·3 번째 레이어를 구운 메시와 같은 블렌드 · 색 · 환경맵으로). 결과: 영상 7792 · 7846 처럼 금빛 틀 (`03`).
- 범위: 법선 있는 파트 중 첫 레이어 환경맵 1324 (rf & 7 = 2: 399, 6: 925 — 아레나 난간 · 금속 판 · 장식 금속 …) 가 같은 식으로 바뀐다 [영상 대조는 이 틀만].
- ATHN03A "검게 그려지는 제우스의 분노 번개" (§24.5 [미확인]): `-Dgow.giveAll` 의 L2 는 하데스의 군대 (영혼 — 감산 0x42 머티리얼이라 원래 어둡다) 였다. 그 가산 2 번째 레이어를
  자세 GO 가 안 그리던 것은 위 `drawLayers` 로 함께 고쳤다. 제우스의 분노 볼트는 정상 (9403d31 부터 같음).

### 25.3 제우스의 분노 첫 사용 (영상 7926–8066) [확실: 스크립트 · 디스어셈블, 표시한 것 제외]
| 영상 | 원작 | 엔진 (보통) |
|---|---|---|
| 7926 | 아이템 카드 268 "제우스의 분노를 획득했습니다." (그림 · ○ = 계속, 미터 없음) → 안내 598 | r4 카드 268 → 598, 카드 동안 미터 내림 (ee1dbb1) (`04`) |
| 7941–7996 | 석상 방 → 불타는 바닥 방들, 군단병 싸움 | [미대조: 싸움] |
| 8006–8011 | ATHN05B 세이브 제단 (Creator SaveGameZone "savepoint", CreateAtStart, (−1341, 480, 3675)) 빛기둥 · R2 → 질문 → 메모리카드 "저장하는 중" | s 빛기둥 · R2 → 질문 → 슬롯 → 저장 중 → 완료 (`04`) |
| 8056 | 첫 안내 9 → 메시지 509 (BOLTPULLOUT 의 SCR_FirstTimeMsg) — **HUD 미터가 떠 있고** 상자에 "L2" 배지 | 수정 전: 미터 숨김 · 조작 잠금 · 배지 빈칸 → 수정 뒤 같음 (`04`, `05`) |
| 8058–8061 | 상자 닫힘, L2 볼트 (손 위 번개) | z 볼트 · □ 던지기 |
| 8066 | 바닥 레버 병사 (§22 r5) | 건드리지 않아 다시 보지 않음 |

확인만 한 것: ↓ 로 제우스의 분노 선택 (HUD 원에 번개), 볼트 생김새 (8061), 연속 던지기, 보통 난이도 레버 병사 = 볼트 두 발 (hitAir3 → death01), 볼트당 MP 5
("Stage 0 Magic Drain"), Arrow 002 피해 8 · 지면 충격 300.

엔진 결함과 수정:
1. **초 0 메시지가 게임을 멈췄다**: 0x1B9F60 은 GameMgr +0x153C (초) 가 **정확히 0** 이면 0x1BF080(1, 0) (전역 정지 슬롯 1 끔) · 0x21D970(1) (0x29F458 = 1 — 일시정지를 풀 때
   소리 채널 0 만 다시 묶음, 0x21E1C0), 음수일 때만 0x1BF080(1, 1). 첫 안내 0x1DBEB0 은 메시지 500 + id 를 $f12 = 0 으로 0x21D980 큐에 넣고 (0x21E520 → 0x1B9638 → 0x1B96D0),
   질문 메뉴 0x1B9BA0 은 −1. 스크립트 ShowMessage 초 (전 WAD): −1 143 곳 (대사 — 아테나 석상 218/322, 쌍둥이 317/319 …), 0 62 곳 (궤짝 441 · 세이브 590 · 아레나 시험 ·
   메두사의 시선 595 · 제우스의 분노 594 · 가족 안기 454 …), 양수 24 곳. 엔진은 초 ≤ 0 을 모두 정지로 다뤄 안내 동안 HUD 를 내리고 조작 · 적을 멈췄다 →
   `GameScene.messageIdle` (초 ≠ 0 일 때만 정지, ○ 대기는 그대로) (42 §5.1 정정).
2. **메시지 쪽 표시의 HUD 리셋**: 0x1B96D0 은 어느 갈래든 (그림 0x1B9974 · 여러 쪽 0x1B9994 · 한 쪽 0x1B9A18 · "Msg %d" 0x1B9A74) 0x1BA150(hud, 1) — 올라온 미터를 내리고
   (+0x157C → MeterBar_Event +0x210 = 0) 히트 카운터 이벤트 0. 정지하지 않는 메시지는 다음 틱 Progress_UpdateHud (0x1D7148) 가 기술 중 · 값 변화면 다시 올린다 — 509 는
   BOLTPULLOUT 중이라 미터가 떠 있다 (영상 8056) → `showMessage` 에서 `hideMeter` + `PS2_HitCounter_Event` 0.
3. 버튼 안내 아이콘 (R2) 은 메시지가 떠 있는 동안 숨김 — 다시 고르는 0x21E908 은 메시지·질문 (+0x1528 / +0x152C / +0x1530) 이 없을 때만 (21 문서) [확실]. 예전엔 정지 메시지라 우연히
   숨었다 (`minigameIconIndex`, 잡은 장치 · 크랭크 아이콘은 그대로).
4. **인라인 버튼 그림의 배지 글자가 없었다**: `[L2Button]` = FLP_HUDK clip 359 (배지 메시 + 클립 358 'L2'), 글자는 클립 358 의 동적 라벨 `_level0/:StaticTextL2` 이고 값 "L2" 는 루트 프레임 0
   액션의 SetVariable (L1 · R1 · L2 · R2 · L3 · R3 · + · = · 괄호 · / · 1–4 · R) [확실: FLP 덤프]. 엔진 인라인 아이콘 (`FlpRenderer.drawStatic`) 은 메시 · 클립만 그렸다 → 동적 라벨도
   그린다 (공유 글자 묶음은 아이콘 앞에서 부모 라벨 것을 먼저 그림). 21 문서의 업그레이드 화면 L1 · R1 배지 글자 [미확인] 도 같은 출처다 [추정: 그 화면은 다시 보지 않음].

결과 (`04`, `05`): 509 동안 미터 · 번개 아이콘이 떠 있고 (영상 8056), 볼트를 든 채 상자가 남고, ○ 두 번 (첫 ○ = 글자 끝, 2.5 초 뒤 ○ 표시) 에 닫힌다. R2 안내는 상자 동안 숨김.
- 남은 차이: 상자 글자가 영상보다 옅고 가늘다 (TTF 대체 글꼴, 색 0xff644b28 은 같음), □ 그림의 바탕 원이 영상보다 크다 [미수정].

### 25.4 시험
`TorchFlameTest` (5: 같은 이름 정의를 오브젝트 태그로 · 05BB 오른쪽 방 TFfireemit13 · TMfire CPU 스트림과 속력 배율 3.3155 · 06A 균일 필드 39.7/초² · 발리스타 틀 첫 레이어 환경맵),
`AnimRecordsTest.gateFireballRatesUseChannelIds` (폭발 이미터 링크 7–10 · 프레임 14 터짐), `HintMessageTest` (3: 0x1B9F60 초 0 → 정지 안 함 · 첫 안내 $f12 = 0 · 질문 −1,
쪽 표시 HUD 리셋 4 갈래, L2Button 배지 = 동적 라벨 StaticTextL2 · 루트가 "L2"). 엔진 테스트 658 통과.

### 25.5 남은 것 · 미확인
- 횃불: 필드는 상수 가속 근사, 표면 이미터 (서브타입 3) 는 여전히 없음, 렌더 정점 10 밖 불 이미터 60 은 하나씩 보지 않았다. ATHN04B · 05A · 06A 횃불은 전 레벨 조사로만 봤다
  (영상 프레임 대조 안 함).
- 환경맵 축 부호 (u/v 방향) 는 화면으로만 맞춤 [근사]. 1324 파트 중 발리스타 틀 말고는 영상 대조 안 함.
- 초 0 메시지 동안 ○ 가 영웅에게도 간다 (원작도 패드를 소비하지 않는다고 봄) [추정]. 0x29F458 의 소리 채널 묶음은 엔진에 없다.
- 오른쪽 아래 금 투구 아이콘 (영상 7956 · 8058 · 8061, §18) 출처는 여전히 [미확인]. 7941–7996 군단병 싸움 · 불타는 바닥 피해는 대조하지 않았다.

### 25.6 증거 (`work/verify/torches_rails_zeus/`, 커밋 안 함)
`01_torches_video_vs_engine` (7716 · 7852 · 7792 ↔ b4b50e2 · 9403d31 · 68b8f16 · 수정) · `02_torch_closeup_video_vs_engine` · `03_ballista_rail_video_vs_engine` (7792 · 7846 ↔ 전 · 후) ·
`04_zeus_fury_first_use_video_vs_engine` (7926 카드 · 8006 제단 · 8056 안내 전 · 후 · 8061 볼트) · `05_hint509_box_video_vs_engine` (L2 배지 글자).

## 26. 체크포인트 재시작 = 스냅숏 되읽기 · 레벨 기억의 엔티티 발동 기록 (영상 8202–8224 · 7045–7065, 2026-10-24)

영상 8202–8224: ATHN05C CheckpointGroup4 (레버 방) 를 지나 항아리 방에서 깃털 상자 (ATHN05D Chest4, 깃털 5 개째 "1 개를 더", 8100) 를 열고 항아리를 부순 뒤 (8088)
05D 물 방에서 죽어 (8209 붉은 화면, 8210 "YOU ARE DEAD / 마지막 체크포인트에서 다시 시작") "로딩 중..." ≈ 4.5 초 (8212–8215.5) 뒤 레버 방 기둥 뒤에서 다시 (8216 —
오른쪽 바닥 레버, 문간 너머 항아리 방) → 항아리가 다시 서 있고 (8220) 상자는 닫혀 있어 다시 열면 같은 카드 (8222–8224). 예전 엔진은 영웅만 체크포인트로 옮겨 상자가
열린 채였다 (§21.3). 엔진 = origin/main 5a6dc4c 위, 보통 (`-Dgow.difficulty=1`).

### 26.1 원작 [확실: 디스어셈블, 표시한 것 제외] (11 §5.2–5.5)
- 체크포인트 = 세이브와 같은 스트림을 메모리 버퍼 0x3387B8 에 (0x22D418 → 0x22D490): 두 슬롯 WAD 이름 + 켬 바이트, PlayerProgress (Save_PlayerBlock 0x1DBFD8 —
  **살아 있는 사망 수를 0 으로**), 카메라 0xC0, 음악 0xEC, GameMgr 0x1C4 (타이머 · 본 동영상), 옵션, 세계 (0x1B3E48: 세계 시각 · 두 슬롯 덩어리를 표에 0x1B4080(…, 0) ·
  GlobalData · LevelData 풀 · 페이드 · 레벨 상태 표 전부).
- 찍는 곳: CheckPoint 프레임 끝 (0x1B3070, 0x29D960 — 영웅 +0x174 &amp; 0x40E (점프 · 떨어짐 · 줄) 이거나 HP &lt; 1 이면 다음 프레임으로 미룸), 새 게임 적재 끝
  (상태 9 0x21FD48 — 메모리카드 불러오기는 읽은 버퍼가 곧 스냅숏), 세이브 포인트 R2 (0x1DC4DC → 0x22D390 → 저장 질문; 메모리카드는 이 버퍼를 쓴다). Warp 는 찍지 않는다.
- 다시 시작 0x21E3D0 (사망 메뉴 0x220360 · 실패 메뉴 (같은 상태 0xE) · 일시정지 메뉴): 0x22D440 → 0x1BC9D0 → WAD 명령 0xF (0x22D660 단계 0: 두 슬롯 내림) → 0x10
  (단계 1: 블록 · 세계 0x1B4450 (세계 시작 0x29D924 = 스냅숏 세계 시각, 표 전체를 스냅숏으로) · 두 슬롯 다시 올림 0x1B4528) → 상태 9 → "** Restarted or loaded save
  game **" 0x1A7C80 → 0x22DA88: PlayerProgress 를 스냅숏으로 (사망 수 · 첫 안내 비트 +0x50 은 지금 것, 이동 상태 +0x174 = 스냅숏, 체력 max(저장, 25) …), GameMgr
  (타이머; 본 동영상 목록은 지금 것), 난이도 = 쉬움으로 (0x29F484) 가 아니면 스냅숏 것.
- **레벨 상태 표 덩어리의 엔티티 발동 기록** (+0x30): 슬롯을 내릴 때 · 스냅숏 때 0x1B1830 이 엔티티마다 메시지 7 → 마지막 발동 시각 +0x68 &gt; 0 이고 재발동 대기 &gt; 0
  (음수 = 한 번만 → FLT_MAX) 이면 0x1B2280 이 {시각, 엔티티 번호, (SpawnEnemy: 만든 수 · 죽은 수)} 를 남기고, 다시 읽을 때 엔티티를 만들자마자 0x1B2360 이 메시지 8 로
  되돌린다 (시작 큐보다 먼저). 그 밖에 +0x38 미뤄 둔 발동 · +0x40 부순 물체 · +0x20 SpawnEnemy 적 (형 3) · Creator GO (형 7) · +0x28/+0x48..+0x58 GO 상태.
  → ATHN04A 광장 재방문에서 북쪽 계단 DMC TriggerVol0 (재발동 −1, 대상 SpawnEnemy 9 키클롭스 · 결계 VisOn · AniIn) 은 다시 돌지 않는다 — 결투는 다시 열리지 않는다.

### 26.2 엔진 결함과 수정
1. **사망 재시작이 세계를 되돌리지 않았다** — 영웅만 `checkpoint` 자리로 옮겼다. → `GameSession.checkpoint` (`SaveGame` = 세이브 직렬화를 메모리로, 11 §5.6):
   새 게임 첫 씬 첫 틱 (불러오기는 읽은 세이브) · 받아들인 CheckPoint 의 프레임 끝 (`GameScene.checkpointFrameEnd`, 대기 `GameSession.checkpointPending`, `snapshotWaits` 0x40E · HP) · 세이브 포인트 R2
   (`takeCheckpoint`: 사망 수 0, 두 슬롯 세계를 표에). 사망 메뉴 · 실패 메뉴 · 일시정지 메뉴 → `restartFromCheckpoint` → `SaveGame.applyCheckpoint` (사망 수 · 첫 안내 비트
   유지, 체력 규칙, 쉬움 `easyRestart`, GlobalData · 초기화 기록 · 타이머 · 레벨 상태 표 · 플레이 시간) → `new GameScene(session, 스냅숏)` (스냅숏 레벨 + 다른 슬롯
   (`SaveGame.other` · `otherActive`, 꺼진 채면 Warp 준비) — 두 세계를 표에서 되살림).
2. **레벨 기억이 한 번만 센서 · 적 · 미뤄 둔 발동 · 문 자세 · 부순 물체를 담지 않았다** (§21.2-5 [근사]) → `ScriptWorld.Memory` 에 `fired` (엔티티 번호 → 몇 초 전 ·
   만든 수 · 죽은 수, 시작 전에 되돌림), `enemies` (살아 있는 SpawnEnemy 적 → 그 SpawnEnemy 시작 메시지에서 기록한 자리에, 등장 동작 없이), `pending` (시작 큐 뒤 큐로),
   `anims` (Animator 몸 `Platforms.State` — 발동 기록 때문에 다시 틀지 않는 문이 열린 채), `broken` (부순 물체: 숨김 + 충돌 끔, 오브 없음), `stamp` (세션 시계 —
   내려가 있던 시간을 더함, `aged`). 세이브에도 (`levelFired.*` · `levelEnemies.*` · `levelPending.*` · `levelAnims.*` · `levelBroken.*`).
3. **실패 화면 다시 하기** (§23.2-6, 69cfd3e: 레벨을 처음 상태로 새로 읽는 근사) → 같은 `restartFromCheckpoint`. 오라클 덩굴 체크포인트처럼 벽 · 사다리에서 찍은
   스냅숏은 이동 상태 (`SaveGame.heroState`, 원작 +0x40 → +0x174) 로 그 자리 벽 질의에 다시 붙인다 (`HeroNavigator.reattachWall`).
4. **Warp 가 세계 시작을 다시 잡았다** — 0x29D924 를 쓰는 곳은 0x1A6EC0 (새 게임) · 0x1B4450 (되읽기) 둘뿐이라 Warp 로 들어온 레벨 시작의 CheckPoint (ARENA06
   TimerControl-AutoCheckPoint 등) 가 1 초 거름에 버려졌다 → `GameSession.worldStart` 는 도착 없는 씬 (새 게임 · 불러오기 · 되읽기) 만.
5. **이웃 슬롯 CheckPoint 를 버렸다** (GameMgr 메시지 0 은 슬롯 구분 없음) → `SceneNav.Host.neighbourCheckpoint`.
6. 음악: 씬이 떠날 때 모든 소리를 멈추고 다시 틀지 않아 (발동 기록으로 음악 센서가 다시 돌지 않으면) 조용해진다 → 지금 음악을 `GameSession.music` 으로 넘겨 새 씬이
   다시 튼다, 스냅숏 · 세이브는 음악 이름을 담는다 (`SaveGame.music`, 원작 0xEC 블록의 근사).
7. 하네스 [엔진 전용]: 경로 단계 `die:` · `break:소유GO`, `-Dgow.auto.deadmenu` (자동 플레이도 사망 메뉴 → 1 초 뒤 ○). 게임 실행은 `-Duser.home=<스크래치>` (세이브 위치).

### 26.3 장면 대응 (k7 = `--game ATHN05C --arrive ATHN05D@-1580,864,2520,180 --skip-movies`, `-Dgow.arrive.state=ATHN05C:I45=4`, 경로 `wait:2;-1588,864,2517,3;
push:0.3:-1601,2517;btn:SQUARE;wait:0.5;btn:SQUARE;wait:1;break:gobreakdoorstone;wait:1;-1450,864,2440,14;wait:1;-1455,864,2330,14;-1617,864,2118,3;push:0.3:-1631,2118;
btn:SQUARE;wait:0.5;btn:SQUARE;wait:1;-1552,864,2100,4;push:0.3:-1552,2070;hold:3:-1552,2070;wait:4;-1420,864,2000,24;-1200,864,1880,30;wait:2;die:;wait:3;-1455,864,2330,14;
-1617,864,2118,6;wait:1;-1552,864,2100,4;push:0.3:-1552,2070;hold:3:-1552,2070;wait:4`, `nofight`, `deadmenu`, 녹화 1/10 초)
| 영상 | 원작 | 엔진 (게임 시각) |
|---|---|---|
| 8077–8081 | 레버 방 ↔ 항아리 방 문간이 이미 열림 (돌문 gobreakdoorstone, Brkb 946 HP 99 — 앞 구간에서 부서짐) | 10.45 초 `break:` (하네스) |
| — | CheckpointGroup4 (재발동 0, 같은 엔티티 5 초) | 0.02 초 적재 스냅숏, ≈ 11.7 초 CheckpointGroup4 스냅숏 (−1534, 864, 2469) |
| 8088 | 항아리 방 항아리 부수기 | 20.9 · 21.7 초 05D 항아리 셋 (`01`) |
| 8100 | 깃털 카드 "1 개를 더" | 23.9 초 R2 → 상자 → 같은 카드 (`02` `03`) |
| 8209–8210 | 붉은 화면 → "YOU ARE DEAD / 마지막 체크포인트에서 다시 시작 / 게임 종료" | 38.1 초 `die:` → 사망 메뉴 (`04`) — 붉은 낙하 화면은 없음 [미대조] |
| 8212–8215.5 | "로딩 중..." ≈ 4.5 초 | 씬 전환 페이드 (로딩 화면 없음) [엔진 구조] |
| 8216–8217 | 레버 방 기둥 뒤, 오른쪽 바닥 레버, 문간 너머 항아리 방 | 같은 구도 (`05`) — 부순 돌문 그대로 (레벨 기억 broken), 05C 적 9 · 미룸 0 · 몸 9 되살림 |
| 8220 | 항아리가 다시 서 있음, 상자 닫힘 | 항아리 셋 다시, 상자 닫힘 (`06`) — 05D 기억은 스냅숏 때 (부순 것 없음) |
| 8222–8224 | R2 → 상자 → 같은 카드 | 50.6 초 R2 → 같은 카드 "1 개를 더" (`07`), 깃털 4 → 5 (두 번 모두) |
| 7045–7065 | ATHN05B 발코니 → 기둥으로 뛰다 떨어짐 → 같은 발코니에서 다시 | d4 (d3 경로): 1.9 · ≈ 5 초 CheckPointGroup 스냅숏 → 10.2 초 떨어짐 → 사망 메뉴 → 같은 발코니 (129, 448, 3720) (`08`) — 둘째 스냅숏은 봇이 난간에 막혀 구를 드나든 것 [하네스] |
| (a6) | ARENA06 21 초 → 1050 → 2005 → FailedScreen → 다시 | 적재 스냅숏 (미룸 2: StartTimer 0.1 초) → 실패 → 다시 0.08 초 StartTimer · 2053 · 21 초 실패 … 반복 |

### 26.4 시험
`CheckpointRestartTest` (7: 깃털 상자 재시작 — 깃털 5 → 4, 상자 닫힘, 05D LevelData 되돌림 · 사망 수 유지; 사망 수 · 첫 안내 비트 유지 · 체력 45 · 타이머 · GlobalData ·
쉬움 0x29F484 유지 · 불러오기는 지움; 광장 결투 실제 흐름 (키클롭스 1 → 처치 → 둘 → LevelData0 3 → dogOut) 뒤 재방문은 TriggerVol0 이 다시 돌지 않음, 발동 기록 없으면
다시 열림; 살아 있는 키클롭스 · 미뤄 둔 AniOut 되살림; 시계 늙힘; 스냅숏 미룸 0x40E; 세이브 파일 왕복), `HeroNavigatorTest.restoredClimbSnapshotReattachesToTheWall`,
기존 `LevelMemoryTest` · `NeighbourChestTest` · `SaveGameTest` · `MemoryCardFlowTest` · `AthensInteriorTest` 그대로. `:engine:test` 666.

### 26.5 남은 것 · 미확인
- ATHN06C 오라클 덩굴 체크포인트 → 실패 → 다시 하기를 게임 안에서 돌리지 않았다 (덩굴 벽 경로 미대조, §23.5) — 벽 다시 붙이기는 합성 시험만 [미확인].
- 카메라 블록 (0xC0) · 음악 재생 위치 · 층 볼륨 · 페이드 상태 · 본 동영상 목록 (지금 것 유지 규칙) 은 담지 않는다. 적은 자리만 (체력 처음 값), 미뤄 둔 발동의 보낸 쪽은
  영웅 아니면 없음, GO 컴포넌트 상태 (+0x48..+0x58) 는 Vis 값으로만, 부순 물체의 Marker 는 남긴다 [근사].
- 붉은 낙하 화면 (7049) · "로딩 중..." 화면 (8212) 은 엔진에 없다. 이음 (`Resident`) 으로 넘어간 이웃 슬롯의 부순 물체는 `Breakables` 에 부순 표시가 없어 다시 맞힐 수 있다 (숨김 ·
  충돌은 넘어감) [엔진 구조, 기존].
- 광장 결투 재방문은 시험 (실제 스크립트 흐름) 으로만 봤고 게임 안 재방문 경로 (05D 엘리베이터 → 광장 북쪽 계단) 는 돌리지 않았다.

### 26.6 증거 (`work/verify/checkpoint_restart/`, 커밋 안 함)
`00_video_vs_engine_8100-8224` (영상 8100 · 8210 · 8216 · 8220 · 8224 ↔ k7) · `01_urns_smashed_after_checkpoint_k7_22.5s` · `02_feather_card_1_more_k7_26s` ·
`03_chest_open_k7_29s` · `04_dead_menu_restart_k7` · `05_restart_lever_room_door_open_k7` · `06_jar_room_urns_back_chest_closed_k7` · `07_feather_card_again_k7` ·
`08_video_vs_engine_7049-7055_balcony_d4`.

## 27. 이음 = 같은 씬에서 현재 슬롯만 바꾸기 — LoadCheck · 걷기 전환에 페이드도 끊김도 없음 (영상 8110–8120 외, 2026-10-24)

원작 LoadCheck (GameMgr 메시지 6, 0x1BCA10) 는 그 레벨이 슬롯에 적재돼 있으면 상태 10 + 현재 레벨 이름 0x29DAE8 복사만 한다 — 두 슬롯 레벨 (스크립트·GO·충돌·
렌더)·영웅·카메라 관리자·HUD·이펙트·음악은 그대로라 화면이 바뀌지 않는다 [확실: 0x1BCA10, 36 §2]. 엔진은 이음 (SEAMLESS = LoadCheck, WALK = 다른 슬롯 바닥만
0.25 초) 마다 새 GameScene 을 `SceneManager` 0.4 초 페이드 아웃 → 교체 → 페이드 인으로 만들었다: 0.8 초 검게 꺼졌다 켜지고 (§19.3, §21.3 항아리 방 → 횃불 복도
t1 15.8–16.7 초), 페이드 아웃 동안 세계가 멈추고, 교체 프레임이 46–188 ms 걸렸고, 영웅을 같은 자리에 다시 세워 (속도 0, 내비 상태 리셋) 줄·벽·밀기·결합이 끊겼으며,
HUD·메시지 상자·구역 제목 (§19.2-5 의 넘겨 띄우기)·이펙트·파티클·적 (자리만 다시)·음악 (멈춤 → §26.2-6 의 이름 넘기기)·카메라 (handoff/resume) 가 새로 시작했다.
엔진 = origin/main 5f5fbd1 위.

### 27.1 바꾼 것 — `GameScene.seamSwap` (보스 레벨 밖의 모든 이음)
- 요청 (`goToLevel` SEAMLESS/WALK) 은 `pendingSeam` 으로 미뤘다가 그 틱 끝 (`update`) 에 — 스크립트 틱 도중 (이웃 세계의 LoadCheck) 이어도 두 세계가 이번 틱에 한 번씩만
  돈다. 같은 틱의 Warp 는 이음보다 앞선다 (새 씬).
- **역할만 바꾼다** (객체는 그대로): 이름 (`levelName` ↔ `otherName`, `currentSlot`) · 렌더 장면 (다른 슬롯 `LevelScene` 이 그대로 현재 장면 — GL 을 다시 올리지
  않음, 효과 시계는 `LevelFx.takeOverMain` 으로 이어 받음, Vis 숨김 집합 넘기기) · 정적 충돌 순서 · 발판 · R1/R2 오브젝트 (잡은 오브젝트 그대로) · TWK · 스크립트 세계
  (`SceneNav.swapSlots` — 리스너 `rebind`, 이벤트 큐 `linkEvents`) · 전투 다리 (적은 낸 세계 `actorWorld` 로) · 부서지는 물체 두 벌 · 카메라 리그 (`CameraRig.swapLevels`:
  상태 링·활성 목록·전환 보간·꺼진 볼륨·질의 끔이 그대로, 이름 조회만 현재 레벨 먼저, 볼륨 후보 순서는 슬롯 순서 — 원작 충돌 월드 질의는 슬롯 구분 없음).
- **새 현재 레벨 것**: RSRCS 적 종류 (다른 슬롯으로 이미 올린 종류를 옮김, 소리 뱅크 로더 판정은 기억), 오브 튜닝 (영구 + 레벨 + 종류, enter 와 같은 구성 —
  다른 슬롯일 때 적재 스레드가 만들어 둠), 레벨 소리 뱅크 (미리 해석, 같은 이름은 새 현재 레벨 것이 이김), 영웅 외부 애니, 이펙트 GO 등록 (적재 스레드가 미리 해석,
  레벨 → 종류 순으로 맨 뒤 = enter 와 같은 우선순위), 레벨 HUD (FLP_Athn01B 히드라 미터 — 떠난 레벨 것은 다른 슬롯에 있는 동안 두었다가 다시 씀), 줄 그리기, 하늘 GO 이미터
  (같은 이름은 이어서), `LevelLink` (현재 = 새 레벨, 다른 슬롯 = 떠난 레벨), 체크포인트 대체 자리 (예전 씬과 같이 지금 자리, 스냅숏 §26 은 찍지 않음 — Warp 처럼 원작도 안 찍음).
- **이어지는 것** (예전엔 끊김): 영웅 위치·속도·자세·기술·내비 상태 (떨어지는 중 LoadCheck · 줄 · 벽), HUD · 메시지 상자 · 질문 · 카드 · 구역 제목 (한 번, 원래 10 초 —
  `seamZone` 넘겨 띄우기 필요 없음), 화면 페이드, 이펙트·파티클 (두 레벨 이미터 포함), 적 (싸움 중이던 것 그대로), 음악·소리, 카메라, 두 세계 (레벨 기억에 다시 쓸 필요
  없음), 자동 플레이 (`autoCarry` 필요 없음).
- **다른 슬롯이 내려갈 때** (LoadWad 로 다른 레벨 · LoadForWarp): 씬이 이음을 넘어 이어지므로 그 레벨이 이 씬에 남긴 Creator 연출 오브젝트 · 세이브 포인트 · (이음으로
  현재였던) 레벨 소리 뱅크와 그 소리 (음악은 그대로 — 배경 적재) · 레벨 HUD 를 정리 (`releaseLevel`), 캐시에서 닫히는 장면의 이펙트 GO 등록·파티클을 뺀다
  (`EffectSystem.remove`, `EffectLibrary.remove` — 남은 WAD 정의로 되돌림). 이미 내려간 슬롯의 적은 다음 이음에서 정리 (예전 이음 씬과 같은 때).
- 예전 길 (새 씬 + 페이드) 은 보스 레벨 (BossDirector: ATHN01E · 02D · 08A–C · PAND03G — 보스 리소스·연출이 현재 레벨 씬에 묶임) · 덧붙는 레벨 (숙주 바닥) 이 낀 이음,
  다른 슬롯 렌더 장면을 못 갖춘 경우, `-Dgow.seamSwap=false` (비교용) 만. Warp · Load · 재시작은 지금처럼 페이드 (스크립트 `ScreenFade` · §26 로딩).

### 27.2 역할에 따라 다르게 그리던 것 (이음 순간 튀던 것) [확실: 두 슬롯 상주 / 표시한 근사]
이음 프레임 쌍 (마지막 앞 → 첫 뒤) 의 칸별 차를 앞뒤 프레임 쌍과 비교해 (8×6 · 16×12 칸, 1.5 배 넘는 칸) 찾았다.
1. **층 안개 텍스처 흐름** (`uFogTexXf` = 흐름 × 효과 시간): 다른 슬롯 환경 시계가 멈춰 있다가 이음 순간 현재 레벨 시계로 뛰었다 (ATHN03A ↔ 03C 먼 건물·덤불) →
   다른 슬롯 환경은 현재 레벨 시계를 따른다 (`FxEnvironment.follow`).
2. **안개 고르기**: 현재 레벨 환경만 썼다 (다른 슬롯 지오메트리는 그 레벨 첫 안개) → 원작 안개 관리자는 하나 (0x20D220, 프레임 하나에 하나 0x29F324) 라 두 슬롯 중 눈
   위치에서 안개 컨텍스트가 켜진 레벨의 안개를 두 슬롯 지오메트리·하늘·배경·캐릭터·파티클이 같이 쓴다 (둘 다 켜졌으면 지난 프레임 것, 둘 다 꺼졌으면 지난 것 — 슬롯 간
   순서 [미확인]) (`GameScene.selectFogSource`, `FxEnvironment.setFogSource`). ATHN01A 갑판 → 01B 선창 (해치로 떨어지며 LoadCheck) 의 하늘·배경색이 이음 순간 바뀌던 것이
   카메라가 선창 안개 칸 (CXT_bvfog · bvwaterfog) 에 들어갈 때로 옮겨졌다. 조사한 안개 소유자 20 레벨은 모두 bv 컨텍스트 (CXT_default 없음).
3. **하늘 순서**: 현재 레벨 하늘 → 다른 슬롯 하늘 순 (깊이 안 씀 — 나중 것이 덮음) 이라 이음 순간 위에 오는 하늘·산이 바뀌었다 (ATHN03A ↔ 03C 의 두 group2) → 슬롯 순서로
   고정 (`LevelScene.skyNeighbourFirst`) [근사: 원작 슬롯 간 순서 미확인].
4. **거울**: 다른 슬롯 레벨 거울 바닥 (ATHN04B 대리석 홀 SCR_Mirror) 을 반사 없이 그려 걸어 들어가는 순간 반사가 튀어나왔다 → 두 슬롯 거울 중 가장 가까운 하나만 거울 뷰
   (0x12A6C8 은 렌더러의 거울 전체에서 하나), 다른 슬롯 장면도 거울 뷰 · 층 0 · 반사의 영웅 (`LevelScene.decideMirror` · `renderNeighbour`).
5. **반복 act 배치** (깃발·돛·흔들리는 등) 를 다른 슬롯은 구운 시작 자세로, **정적 GO 그림자** (SHG) 는 현재 레벨만 → 다른 슬롯도 같은 시계로 애니, 그림자 서버에.
6. 볼륨 카메라 후보 순서 (동순위면 뒤엣것 0x1EECE4) 가 현재/다른 슬롯 순이라 바뀔 수 있었다 → 슬롯 순서 (위 27.1).

### 27.3 이음 프레임 시간 (`-Dgow.perf` 모든 프레임, 실시간, 같은 기계·같은 경로, 전 = 5f5fbd1)
| 이음 | 전: 교체 (exit+enter) / 그 프레임 | 후: 이음 처리 / 그 프레임 | 후: 앞뒤 1 초 최대 (중앙값) |
|---|---|---|---|
| t1 ATHN05C → 05D → 05C → 05D | 140 · 57 · 60 / 188 · 111 · 93 ms | 2.4 · 4.1 · 1.4 / 10.0 · 8.1 · 4.9 ms | 10.0 (2.8) |
| s3 ATHN03A → 03C → 03A | 54 · 55 / 82 · 103 | 5.8 · 2.3 / 12.7 · 8.8 | 12.7 (5.1) |
| s4 ATHN04A → 04B → 04A | 37 · 36 / 83 · 80 | 5.9 · 3.0 / 11.1 · 7.8 | 11.1 (3.7) |
| s5 ATHN01A → 01B → 01A | 16 · 20 / 46 · 47 | 5.1 · 1.5 / 12.9 · 5.8 | 15.6 (3.9) |
| s6 ATHN02B → 02C → 02B | 28 · 25 / — · 75 | 3.3 · 4.1 / 13.1 · 13.1 | 13.1 (8.6) |
- 전은 그 앞에 0.4 초 페이드 아웃 (세계 멈춤, 미리 하기) · 뒤에 0.4 초 페이드 인. 크게 걸린 교체는 레벨 준비 (적재 스레드) 가 페이드 안에 못 끝나 기다린 것
  (다른 작업이 기계를 쓰는 동안 t1 교체 1452 ms 도 봤다).
- 후의 이음 처리 단계 (`# load . seam …`): 처음 이음 2–11 ms 중 줄 그리기 ≤ 2.3 · 이펙트 등록 ≤ 2.6 · 역할 바꾸기 ≤ 3.4 · 적 종류 + 튜닝 ≤ 2.7 ms. 미리 하지 않던 때
  레벨 HUD FLP_Athn01B 39 ms (UiLayer 가 WAD 의 MDL 전부를 다시 해석 → 자산의 해석 결과를 씀, 이음과 무관하게 HUD 만들 때마다), R_PERMK 튜닝 3–14 ms,
  소리 뱅크 3 ms 였다.

### 27.4 이음별 확인 (녹화 lockstep 매 프레임 `-Dgow.record.every=1`, 이음 틱 −59 … +60, 프레임 60 = 이음 뒤 첫 프레임)
| 이음 (경로 · 이음 틱) | 전 (페이드) | 후: 밝기 최저 / 앞 | 후: 이음 쌍 차 / 앞뒤 쌍 평균 · 튄 칸 (8×6) |
|---|---|---|---|
| t1 ATHN05C 항아리 방 → 05D 횃불 복도 (걷기, 608) | +25 프레임에 0 (검정) | 80 % (복도가 어두움) | 4.70 / 4.93 · 0 |
| t1 05D → 05C (걷기, 1082 — 해골 셋과 싸우는 중) | — | 60 % (+29 카메라 컷) | 9.63 / 10.16 · 0 |
| t1 05C → 05D (걷기, 1360) | — | 83 % | 10.97 / 10.88 · 0 |
| s3 ATHN03A 거리 → 03C (걷기, 271 — 궁수와 싸움) | 0 | 96 % | 3.33 / 2.84 · 0 |
| s3 03C → 03A (걷기, 900) | — | 101 % | 4.10 / 4.10 · 0 |
| s4 ATHN04A → 04B 대리석 홀 (걷기, 291) | 0 | 101 % | 9.48 / 9.91 · 0 |
| s4 04B → 04A (걷기, 517) | — | 90 % | 7.87 / 8.30 · 0 |
| s5 ATHN01A 갑판 → 01B 선창 (해치로 떨어지며 LoadCheck, 140) | 0 | 45 % (+6 선창 카메라 컷) | 2.40 / 2.59 · 1 (비) |
| s5 01B → 01A (걷기, 387) | — | 95 % | 3.52 / 3.41 · 0 |
| s6 ATHN02B → 02C (걷기, 468) | 0 | 91 % | 5.65 / 5.01 · 0 |
| s6 02C → 02B (걷기, 1048) | — | 100 % | 0.22 / 0.16 · 0 |
- 경로 (`-Dgow.difficulty=0 -Dgow.auto=1 -Dgow.auto.route.nofight`): t1 `--game ATHN05C --arrive ATHN05D@-1440,864,2400,180`, `wait:1;-1440,864,2100;-1440,864,1840;
  -1300,864,1840;-1100,864,1840;wait:1;-1440,864,1840;-1440,864,2000;wait:1;-1440,864,1840;-1100,864,1840;wait:2` · s3 `--game ATHN03A --arrive ATHN03C@-1850,929,-1780,90`,
  `wait:1;-2000,913,-1830;-2200,913,-1850;-2400,913,-1720;wait:2;-2200,913,-1850;-2000,913,-1830;-1850,929,-1780;wait:2` · s4 `--game ATHN04A --arrive ATHN04B@2050,-76,1140,90`
  + §18 r13 의 `gow.arrive.state`, `wait:1;2336,-80,1150;2336,-80,1250;2336,-80,1450;wait:2;2336,-80,1250;2336,-80,1150;wait:2` · s5 `--game ATHN01A --arrive ATHN01B@1580,0,1720,180`,
  `wait:2;1616,-30,1628!;wait:4;1500,10,1760!;wait:4` (해치 퍼즐 대신 뚜껑 아래로 순간이동 · 01A 바닥만인 갑판으로 순간이동 [하네스]) · s6 `--game ATHN02B
  --arrive ATHN02C@-450,1216,1600,0`, `wait:1;-465,1216,1500;-465,1216,1300;wait:2;-36,1360,2016!;wait:1;40,1374,2016;wait:2` (두 레벨 바닥이 겹친 곳 → 02C 바닥만 · 02B 바닥만 [하네스]).
- 걷던 영웅·싸우던 적·떨어지던 영웅 (s5 틱 140 — 떨어지는 중), 메시지·구역 제목·HUD 미터, 이펙트가 이음 프레임을 넘어 그대로. 카메라는 이음 뒤에 그 자리 볼륨이 고를 때
  (s5 +6 선창, t1 1082 +29) 만 바뀐다.

### 27.5 시험
`SeamSwapTest` (4: 같은 이름 GO 의 두 레벨 WAD — 나중 것이 이김 · promote · remove 가 남은 정의로; 정적 충돌 순서만 바뀌고 바닥 같음; 카메라 리그 swapLevels 뒤
60 틱 같은 카메라·같은 눈 (ATHN05C → 05D 경계); 두 슬롯 안개 — 다른 슬롯 시계 = 현재 레벨 시계, 고른 환경의 안개). `:engine:test` 670 통과.
60 초 봇 스윕 95 레벨 (ATHN · ATRET · DEST · HDES · OLYMP · PAND · SEWR, `-Dgow.difficulty=0 -Dgow.auto=1 -Dgow.auto.secs=60`, 전 = 5f5fbd1 · 후 같은 부하에서 동시에):
종료 코드 ≠ 0 · 예외 0 / 0, 방문 604 / 604, 순간이동 371 / 372, 사망 76 / 78 (ATHN08C 16 → 18 — 두 빌드 다시 두 번씩 모두 18, 흔들림). 경로 없는 봇은 이음에서
끝나므로 (`auto.allow`) 같은 씬 이음은 위 경로 녹화가 확인한다.

### 27.6 남은 것 · 미확인
- 보스·덧붙는 레벨이 낀 이음은 예전 길 (새 씬 + 0.4 초 페이드): ATHN01D → 01E (짚라인 끝 걷기), ATHN02C ↔ 02D (메두사 방), PAND03G 이음 [엔진 구조].
- 두 슬롯 안개가 둘 다 켜진 곳의 슬롯 간 순서 (지난 것 유지) [추정], 하늘 슬롯 순서 [근사].
- 다른 슬롯 레벨 줄은 시뮬레이션하지 않는다 (떠난 레벨 줄은 마지막 자세로 멈춤, 매달린 채 넘어온 줄은 남김) · 다른 슬롯 레벨의 물 표면·렌즈 플레어는 그리지 않는다
  (그런 이음에선 이음 순간 나타남 — 5 이음엔 없음) [엔진 구조, 기존].
- 이웃 스크립트의 소리·음악 요청은 여전히 버린다 — 떠난 레벨 스크립트가 켠 루프 소리는 그 레벨이 내려갈 때까지 울린다. 떠난 레벨 Creator 연출 오브젝트는 act 를 계속
  반복하지만 그 스크립트 Animator 는 받지 않는다 [근사].
- `--arrive` 하네스 · 보스 이음은 여전히 새 씬이라 `Arrival.resident` · `GameSession.residentWorlds` · `seamZone` · `autoCarry` 를 그 길에 남겼다.
- 01A ↔ 01B · 02B ↔ 02C 녹화는 순간이동 하네스를 썼다 (해치 퍼즐 · 높이 차) — 영상 대조 없음 [미대조].

### 27.7 증거 (`work/verify/seamless_seams/`, 커밋 안 함)
이음마다 한 장 (위: 전 — 이음 요청 59 → +25 검정, 가운데: 후 58 · 59 · 60 (이음 뒤 첫) · 61 · 72, 아래: 후 차 ×4 — 이음 쌍이 앞뒤 쌍과 같은 모양):
`01_athn05c_to_05d_jar_room_torch_corridor_t1_608` · `02_athn05d_to_05c_back_mid_fight_t1_1082` (+ `02b_…_fight_continues_after_seam`) · `03_athn03a_to_03c_streets_s3_271` ·
`04_athn03c_to_03a_s3_900` · `05_athn04a_to_04b_marble_mirror_s4_291` · `06_athn04b_to_04a_s4_517` · `07_athn01a_to_01b_hatch_fall_seamless_s5_140` · `08_athn01b_to_01a_deck_s5_387` ·
`09_athn02b_to_02c_s6_468` · `10_athn02c_to_02b_s6_1048`, 고치기 전 (같은 씬이지만 27.2 전) `before_fix_neighbour_mirror_pop_04b_s4_291` · `before_fix_sky_order_fog_clock_pop_03a_s3_900` ·
`before_fix_sky_crop_03c_to_03a_s3_900`, 수치 `metrics.txt`.

## 28. QA vs PS2 영상 — ATHN06B 들보 홀 평균대 붙잡기 (영상 9150–9265) · 밀린 적의 벽 처리 (05D 복도, §21.3) · 아레나 울타리 틈 (2026-10-24)

§23.5 둘째 줄 (들보가 무너질 때 옆 들보에 매달림 — 엔진은 그냥 떨어짐) 과 §21.3 둘째 줄 (05D 복도 적이 서쪽 벽 너머로 밀림) 을 다시 봤다. 엔진 = origin/main 9547268 위,
화면 1280×720 lockstep, 들보 홀은 영상과 같은 보통 (`-Dgow.difficulty=1`).
- b2 (들보 홀): `--game ATHN06B --arrive ATHN06A@-3104,992,-1250,0`, `-Dgow.auto.route.nofight=1`, 경로 `-3104,992,-1300,6;-3104,992,-1440,5;-3150,992,-1440,4;
  stick:0.6:-1,-0.5;wait:0.4;btn:CROSS;wait:2.6;-3196,992,-1440,4;-3200,992,-1437,2;wait:1.2;stick:1.0:0,-0.55;wait:0.3;btn:CROSS;wait:3` (북쪽 발코니 → 정적 남북 들보 x −3104 →
  동서 들보 z −1440 를 서쪽으로 → 북쪽 옆으로 걸어 나감 → × 올라섬 → 교차점 x −3200 에서 무너진 북쪽 들보 쪽으로).
- r2 (05D 복도): §21 r2 그대로 (`--game ATHN05D --arrive ATHN05C@-1440,864,2400,180`, 쉬움, 봇 170 초) + 적 위치 0.25 초 기록 (조사용, 커밋 안 함).

### 28.1 평균대 붙잡기 [확실: 디스어셈블 — 38 §24]
원작은 턱 질의 0x1E3A80 (정면) 이 아니라 **좁은 바닥 단계 0x1BFBF0** 이 붙잡는다: Narrow·NarrowNoBB 바닥 (+0x174 0x80000 | 0x2000, 0x1BFA70) 에서는 바닥 질의 대신
다섯 선분 (0x1E5A70: 가운데 + 오른·왼 4.8 × 앞·뒤 9.6, 발 − max(이동, 3.2) 까지) 으로 서고, 다 비면 지난 비트 쪽 (앞 비트가 없으면 뒤, ± 오른쪽) 16 선분 (옛 발 − 1.6)
이 맞힌 면 + 9.6, 발 = 옛 발 − 35.2 로 옮겨 턱 질의 → 0x1C75F8 이 윗면 Narrow 면 **ANIBalanceCatch (한 손)**, 시간 Sold+0x22C **1 초**, 좌우 이동 없음,
× 는 ANIBalancePullupL/R. 들보 홀 들보 (Entities_Narrow, 폭 8, 높이 16) 가 모두 Narrow 라 크라토스는 들보 옆·끝으로 나가면 방금 서 있던 들보를 잡는다.
엔진은 이 단계가 없어 그냥 떨어졌다 → `HeroNavigator.narrowStep` · `balanceCatch` · `groundSurface`, `GameScene.physics` 좁은 바닥 단계, 0x1C75F8 애니·타이머 규칙, `pullUpNarrow`.

| 영상 | 원작 | 엔진 b2 |
|---|---|---|
| 9162–9163 | 동서 들보 위를 걷다가 (화면 가로 들보, 남쪽을 보는 TopCam) | 0–4.2 초: 같은 카메라·같은 들보 (`01` 아래 1 열) |
| 9163.25–9164 | 들보 옆에 **한 손**으로 매달림, 가까운 카메라로 바뀜 (아치 아래) | 4.3 초 비트 4 (오른뒤) → 뒤 + 오른쪽 → 들보 북쪽 면 (z −1436) + 9.6, 발 956.8, balancebeamcatch, 카메라도 가까운 쪽으로 (`01` · `02` · `03`) |
| 9164.25–9165.5 | 다른 손을 올려 다시 들보 위로 | 5.2 초 × → balancebeampullup 으로 들보 위 (애니 루트 이동 대신 끝 자리 — 벽 + 13.1 [근사]) |
| 9164.25–9164.75 · 9208–9208.75 | 옆 남북 들보가 기울며 떨어짐 | 지나온 교차점의 무너지는 들보가 뒤에서 떨어짐 (돌무더기) |
| 9207.75 | 동서 들보 가운데 (교차점 사이) 에 한 손 매달림 | 같은 규칙 — 옆으로 나간 자리 그대로 (`walkingOffABeamSideCatchesTheBeam`) |
| 9261.85 – 9262.25 | 붙잡지 못하고 아래층 (736) 으로 | 10.6 초: 교차점 북서 모서리로 나감 → 붙잡기 선분은 교차점 북쪽 면을 맞지만 P (x −3207.9) 의 턱 질의 두 수직 선분 (±4.8) 중 하나가 교차점 (폭 16) 밖 → 턱 없음 → P 로 옮겨진 채 떨어짐 (원작과 같은 규칙) (`04`). 무너지는 들보 위에 서 있으면 발판 실림으로 같이 내려간다 |

- 고치기 전 (5f5fbd1, 같은 경로): 들보 북쪽 옆으로 나가면 그대로 아래층으로 떨어진다 (`before_…`). 9547268 위에서 다시 돌려도 붙잡기 자리·시각은 같다.
- 교차점에서 이미 내려간 들보 쪽으로 걸어 나가는 경우 (데이터 시험 `athens06bBeamHallCatchesTheCrossingWhenTheBeamAheadHasDropped`): 들보가 28.8 넘게 내려간 뒤 (애니 0.9 초)
  면 교차점 북쪽 면을 잡고, 6 만 내려간 때 (0.6 초) 는 막힘 선분 (CDV 몸 포함) 을 들보가 막아 붙잡지 않고 들보 위로 떨어져 같이 내려간다.

### 28.2 05D 복도 "벽 너머로 밀린 적" [확실: 엔진 기록 / 디스어셈블 경로]
- r2 를 적 위치 기록과 함께 다시 돌리니 봇이 끝까지 노린 y 453 의 적 (65 초 (−1413, 453, 2559) · 155–162 초 (−940, 453, 1659) "적 포기") 은 복도 해골 (R_SKD 037, FirstSetEnemies) 이
  **아니었다** — 복도 해골 넷은 내내 y ≥ 862. 그 적은 **이웃 슬롯 ATHN05C 의 시작 스폰 R_SKA 032 넷** ((−2288 … −2312, 724, 4284 … 4448), "이웃 적 스폰") 으로, 시작
  1.5 초에 발코니 끝 (x ≈ −2270, 높이 16 턱면뿐 — 막이 없음) 에서 떨어져 웅덩이 바닥 (452.8) 을 따라 영웅 아래 (복도 밑) 까지 걸어왔다. 넉백 · 벽과 무관 — 하네스가 05C 를
  처음 상태로 두고 05D 에서 시작해 원작 흐름 (05C 싸움을 지나옴) 에는 없는 적이다 [엔진 전용: 하네스]. 남은 것: 영웅에 닿지 못하는 아래층 적을 AI 가 끝없이 따라가는지
  (원작 AI 이동 0x1CF9D0 · AIBlock 콜백 0x1D2EB8 의 경로 판단) [미확인].
- 그래도 밀린 적의 벽 처리는 원작과 달랐다 (10 §7.2): 원작은 밀어내기 속도 +0xF0 · 루트 모션 D · 동기 끌기 변위를 **같은 프레임 내비 이동** (0x1EAEC8 → 0x1E5F90 → 0x1EA5F0 /
  0x1EA200: 몸 질의 · 이동 선분) 에 넣는데, 엔진은 내비 단계 뒤에 위치에 더해 다음 틱에야 막았다 — 그 사이 그리는 위치가 벽 속. 복도 서쪽 벽 (연석 −1003.1 · 벽 −1008)
  쪽 넉백 2000 /초면 넉백 내내 x −1023 (벽 속 15), 900 /초면 −1006.4. 동기 끌기 (`GrabSync`) 는 위치를 바로 써서 다음 틱까지 벽 너머일 수 있었다.
  → `EnemyNav.settle` (같은 틱 몸 구 + 이동 선분) 을 `EnemyFighter.settleMotion` · `GrabSync.align` 이 부른다: 240 · 320 (영웅 피격 충격 최대) · 900 · 2000 /초 모두 x ≥ −992.5.
  이음 (§27) 뒤에도 적 내비는 두 슬롯 정적 충돌을 함께 가진 같은 `NavWorld` (`EnemyNav.of(nav.navWorld)`, 순서만 `swapStatics`) 를 본다.

### 28.3 아레나 둘레 울타리 틈 (순회에서 찾음) [확실: 데이터 · 디스어셈블 / 근사: 표시]
- 붙잡기만 넣은 판의 첫 순회 (5f5fbd1 기준) 에서 아레나 사망이 늘었다 (ARENA06 3 → 6 · 19 1 → 2 · 20 1 → 2). ARENA06 을 같은 봇으로 다시 돌려 기록하니 영웅이 원판
  (NarrowNoBB 20 각형, 꼭짓점 278.9 · 변 가운데 275.5, y 20.6) 가장자리와 둘레 울타리 (안쪽 면만, y 16 – 29.2 = 바닥 위 8.6, 반지름 ≈ 280) 사이 **틈** (폭 1 – 4.5, 아래는 −203 /
  −413) 에 서 있었다: 울타리를 따라 걸으면 바깥 선분은 울타리 면에, 안쪽 선분 (4.8) 은 원판 윗면 대신 옆면에 닿아 다섯 선분이 빈다 (r 279.5 – 280.3). 이어 붙잡기 선분이
  원판 옆면을 맞혀 P = 옆면 + 9.6 (r 285) 이 울타리 밖인데, 이번 이동이 이미 울타리 면을 넘은 자리라 막힘 선분도 비어 P 로 옮겨졌고, 이동 선분이 x · z 만 되돌려
  바닥 밑 −14.6 에서 떨어졌다 ("턱 없음" ARENA06 3 · 19 2 · 20 2 · 26 3 번).
- 원인은 엔진 몸 충돌: 원작 몸 질의 0x1E2100 은 바닥 단계 전에 발 + 6.4 에서 몸 상자 모서리 (±9.6 × ±9.6) 로 선분을 쏴 이 울타리에 막혀 영웅을 9.6 떨어뜨리는데
  (38 §24.2), 엔진 몸 구 (발 + 20, 반지름 12) 는 발 + 8 아래를 못 본다. 고치기 전 엔진 (일반 바닥 질의) 도 틈에 들어가면 떨어진다 (5f5fbd1 순회 아레나 낙하 18 번:
  ARENA09 4 · 10 3 · 26 3 · 27 3 …).
- → 좁은 바닥 단계 앞에 이동 선분 (0x1EAB78) 과 `HeroNavigator.narrowBody` (아래 단 모서리 선분) [근사: 좁은 바닥에서만]. ARENA06 60 초 봇: 붙잡기만 — 낙하 5 (턱 없음 3),
  이동 선분만 더함 — 낙하 6 (턱 없음 0), 몸 질의까지 — 낙하 0 (영웅이 울타리에서 ≈ 13 떨어져 돈다, r ≈ 267). 시험 `arenaFenceKeepsTheHeroOnTheNarrowDisc`.

### 28.4 순회 (봇 60 초 쉬움, 전 레벨 105 개, 3 줄, 같은 기준 9547268 — 전 · 후를 나란히)
| 레벨 | 전 방문 · 사망 | 후 방문 · 사망 · 붙잡기 | 까닭 |
|---|---|---|---|
| ARENA08 | 0/3 · 0 | 0/3 · 0 · 1 | 원판 가장자리에서 붙잡기 1 (턱 16.5), 봇이 올라서지 않아 1 초 뒤 떨어짐 (낙하 1 = 전 1) |
| ARENA09 | 0/1 · 3 | 0/1 · 0 · 0 | 전: 울타리 틈에서 낙하 4 (r 276 – 280, 사망 3); 후: 몸 질의 아래 단 (§28.3) 이 틈에 들지 않게 — 낙하 0 |
| ARENA10 | 0/2 · 3 | 0/2 · 0 · 1 | 전: 낙하 3 (안쪽 가장자리 r ≈ 120 둘 · 바깥 틈 하나, 사망 3); 후: 안쪽 가장자리 붙잡기 1 (턱 20.6) → 봇이 올라서지 않아 1 초 뒤 떨어짐 (사망 0) |
| ARENA14 | 0/0 · 1 | 0/0 · 1 · 45 | 봇이 울타리 없는 같은 가장자리 ((151, 15, −199) 둘레) 로 거듭 걸어 나감 — 전: 낙하 2 (사망 1); 후: 붙잡기 45 · 올라섬 되풀이, 낙하 1 (사망 1) |
| ARENA19 | 0/1 · 1 | 0/1 · 0 · 0 | 전: 울타리 틈 낙하 1 (r 278); 후: 0 |
| ARENA20 | 0/1 · 1 | 0/1 · 0 · 0 | 전: 울타리 틈 낙하 1 (r 279); 후: 0 |
| ARENA26 | 0/1 · 3 | 0/1 · 0 · 0 | 전: 울타리 틈 낙하 3 (r 277 – 279); 후: 0 |
| ARENA27 | 0/2 · 2 | 0/2 · 4 · 4 | 원판 (6.6) 아래 둘레에 Death 띠 (y −26.8) — 전: 걸어 나가며 띠 너머 아래층 (−27) 에 내림; 후: 원판 옆면에 매달렸다가 봇이 올라서지 않아 1 초 뒤 띠 위로 (데스존) [하네스: 봇]. 두 번 다시 돌려도 전 2 · 2, 후 4 · 4 |
| ATHN02C | 12/30 · 0 | 8/30 · 0 · 0 | 적 settle 로 35 초에 적 위치가 1 다름 → 봇 길이 갈림: 후는 위층 (1216) 에서 바로 아래 (960) 적을 노리며 15 초 멈춤. 두 번 다시 돌려도 전 12 · 12, 후 8 · 8 (사망·예외 없음) [하네스: 봇] |
| ATHN05D | 9/9 · 0 | 7/8 · 0 · 0 | 복도 해골 싸움이 ≈ 10 초 길어짐 (밀린 적이 벽 속으로 들지 않고 벽에 멈춤) → 60 초 안 방문 9 → 7/8. 다시 돌림: 전 9 · 9, 후 8 · 7 |
| PAND03E | 7/7 · 3 | 7/7 · 2 · 38 | 들보 (Narrow) 옆으로 거듭 걸어 나감 — 전: 떨어져 사망; 후: 붙잡기 38 (봇 스틱이 올라서기), 사망 3 → 2 |
| PAND04AA | 4/5 · 0 | 0/5 · 0 · 0 | 흔들림: 다시 돌림 전 0 · 4, 후 0 · 1 (5f5fbd1 짝은 1 → 4) |
| **합 (105 레벨)** | 방문 606 · 사망 95 · 순간이동 376 · rc≠0 0 · 예외 0 | 방문 596 · 사망 85 · 순간이동 370 · rc≠0 0 · 예외 0 · 붙잡기 89 | |

- 나머지 93 레벨 (Narrow 윗면이 있는 ATHN01B · 01C · 01D · 04A · 06B · 06C · 08B, PAND01A · 02B 포함) 은 방문 · 사망 · 낙하가 같다. "턱 없음" (P 로 옮겨진 채 떨어짐) 0 번.
- 아레나 낙하 전 18 → 후 3 (ARENA08 · 10 · 14 의 매달림 뒤 떨어짐). 사망 95 → 85 = 아레나 −9 (ARENA09 · 10 · 19 · 20 · 26 −11, ARENA27 +2) · PAND03E −1.
- 5f5fbd1 짝 (붙잡기만, 몸 질의 아래 단 · 이동 선분 전): 사망 93 → 101, 방문 603 → 602 — 아레나 "턱 없음" 10 번 (§28.3) 이었고 이번 판에서 고쳤다.

### 28.5 시험
`HeroNavigatorTest.walkingOffANarrowCrossingCatchesItsEdge` (16 × 16 교차점: z −14.4 까지 섬, 뒤 → 면 −8, 발 −35.2 · z −17.6 · 면을 봄 · balancebeamcatch · 1 초, 좌우 없음 ·
1 초 뒤 떨어짐 / × → balancebeampullup 면 안 3.5, 3 아래 들보는 내려섬), `walkingOffABeamSideCatchesTheBeam` (30° 비스듬히 → 남쪽 면에 매달림, 옆면 없는 판은 그 자리에서 떨어짐),
`NavDataTest.athens06bBeamHallCatchesTheCrossingWhenTheBeamAheadHasDropped` (위), `NavDataTest.arenaFenceKeepsTheHeroOnTheNarrowDisc` (§28.3), `KnockbackWallTest` (2: 넉백 4 속도 ·
끌린 자리), `NavDataTest.heroNavigationTuningIsSold000` (Sold+0x22C 1 대조 추가). 엔진 테스트 676 통과.

### 28.6 남은 것 · 미확인
- 0x80000 (Narrow) 의 균형 동작 (가운데가 비면 바깥쪽 지역 속도 +0x260.x · 기울기 +0x2BC · 흔들림 +0x2C0) 은 넣지 않았다 — 엔진 영웅 이동이 애니 이동량이라 [근사].
  영상의 두 붙잡기가 이 기울기로 옆으로 밀려 난 것인지, 플레이어가 옆으로 걸어 나간 것인지는 프레임 간격 (0.25 초) 으로 가르지 못했다 [미대조].
- 평균대 올라서기는 애니 구동 루트 이동 대신 끝 자리에 둔다 (일반 턱 올라서기와 같은 엔진 방식) [근사]. 붙잡기 첫 선분은 정적 시트만 [근사].
- 몸 질의 아래 단 모서리 선분은 좁은 바닥 단계에서만 (원작은 모든 지상·공중 이동) — 일반 바닥의 발 + 6.4 – 8 높이 낮은 벽·계단은 여전히 몸 구 근사 [근사].
- 9208 · 9164 의 무너지는 들보가 영웅 바로 옆에서 떨어지는 장면은 경로 (어느 교차점에서 어느 들보의 ENV 에 들었는지) 를 정확히 맞추지 않았다 [미대조].
- §28.2 의 아래층 R_SKA 추격 (AI 경로 판단) [미확인].
- 봇 (AutoPlay) 은 매달림을 따로 다루지 않는다 — 목표 쪽 스틱이 위 (벽 쪽) 면 올라서고 (PAND03E · ARENA14), 아니면 평균대 매달림 1 초가 지나 떨어진다
  (ARENA27 데스존 띠 위 · ARENA08 · 10, §28.4) [엔진 전용: 하네스].

### 28.7 증거 (`work/verify/ledge_catch_knockback/`, 커밋 안 함)
`01_video_9162-9165_vs_engine_b2_side_catch` (위 영상 9162.75 · 9163.25 · 9164 · 9165.5 ↔ 아래 엔진 4.27 · 4.53 · 5.07 · 5.6 초) · `02_engine_b2_4.5s_catch_camera_switch` ·
`03_engine_b2_5.1s_one_hand_balancebeamcatch` · `04_engine_b2_10-12s_crossing_corner_no_ledge_falls` · `before_engine_5f5fbd1_b2_side_step_falls`.

## 29. 영상 QA 마무리 회귀 순회 — 아테네 36 레벨 (92bedb9 = 2a550b1 + 문서, 2026-10-25)

봇 60 초 쉬움 (`-Dgow.difficulty=0 -Dgow.auto=1 -Dgow.auto.secs=60`, `-Duser.home=<스크래치>`), 2 줄 동시, `--game L --skip-movies`. 기준 = 같은 방법의 68b8f16 순회.
36 레벨 모두 rc 0 · 예외 0. 25 레벨은 방문 · 순간이동 · 사망이 같다. 다른 11 레벨:

| 레벨 | 68b8f16 방문 · 순간이동 · 사망 | 92bedb9 | 까닭 |
|---|---|---|---|
| ATHN01D | 14/38 · 3 · 1 | 13/38 · 4 · 0 | 작은 차이 — 조사 안 함 |
| ATHN02C | 12/30 · 1 · 0 | 8/30 · 1 · 0 | §28.4 (적 settle 로 봇 길이 갈림) [하네스: 봇] |
| ATHN03C | 5/10 · 1 · 0 | 0/11 · 0 · 0 | 69cfd3e (봇이 조준 가중 0 NPC 를 고르지 않음). 전: 시민 (템플릿 313 Patrol8 자리 (−2812, 913, −1185)) 을 노리다 (−2622, 919, −1422) 에서 25 초 멈춘 뒤 포기하고 볼륨을 돌았다. 후: 싸움터까지 가서 갑옷 미노타우로스 (R_MI2 Patrol10 자리) 로 보이는 적과 60 초 넘게 싸운다 — 180 초로 돌리면 85–115 초에 처치 (오브 17 → 122) 뒤 방문 7/10 [하네스: 봇] |
| ATHN04A | 8/40 · 1 · 0 | 11/40 · 1 · 0 | 늘어남 |
| ATHN04B | 16/18 · 6 · 0 | 19/19 · 7 · 0 | 늘어남 (볼륨 18 → 19) |
| ATHN04D | 2/5 · 3 · 0 | 2/5 · 2 · 0 | 순간이동 하나 적음 |
| ATHN05B | 9/28 · 1 · 0 | 4/28 · 1 · 0 | 69cfd3e (봇 대상 고르기 · 메시지 넘기기 변경): 첫 대상 포기가 31 → 26 초로 당겨져 길이 갈림 → 35 초부터 (−850, 519, 3908) 에서 바로 아래 (y 453) 적을 노리며 멈춤 (§20.6 "봇이 닿지 않는 아래층 적" 그대로) [하네스: 봇] |
| ATHN08A | 0/2 · 0 · 3 | 0/2 · 0 · 1 | 보스전 (봇이 못 이김) 흔들림 |
| ATHN08B | 0/1 · 0 · 0 | 1/1 · 0 · 1 | 보스전 |
| ATHN08C | 0/0 · 0 · 10 | 0/0 · 0 · 16 | 아레스 결투 (AresFight 601, 봇이 못 이김): 5a6dc4c 13 → 5f5fbd1 18 — 재시작이 스냅숏을 되읽어 레벨을 다시 올리고 아레스가 등장부터 다시 한다 (§26, 원작 0x21E3D0 과 같음). 엔진엔 "로딩 중..." (≈ 4.5 초) 이 없어 60 초 안에 더 자주 되풀이한다 [엔진 구조] |

- 이분 (결정적, 같은 빌드 두 번씩 같은 결과): ATHN03C · ATHN05B 모두 68b8f16 = 전 값, 69cfd3e = 후 값 (71ea2e4 · 22d2e77 도 후 값).
- 실제 세이브 위치 (`~/Library/Application Support/devple-gow`) 는 생기지 않았다. 엔진 테스트 676 통과.

## 30. QA vs PS2 영상 2화 — ATHN07 절벽 · 올림포스 둥근 문 → 아테네의 하수도 SEWR01 · SEWR02 → ATHN03D 분홍 우물 · 나선 · 금빛 봉인 → ATHN03E 오메가 광장 · 아레스 · 절벽 길 (영상 0–1040 초, 2026-10-25)

영상 = hoXYXNef60U (2화, 전사 (보통) = `-Dgow.difficulty=1`), 프레임 728×409 (4:3 게임 화면이 가운데, 스크래치 `vid/sewers/t<초>.jpg` 156 장 — 커밋 안 함,
고른 것은 `work/verify/sewers/`). 엔진 = origin/main 위 (마지막 dcc9752 까지 rebase), 1280×720 lockstep, 경로 `-Dgow.auto.route` (`.nofight`, 보통) ·
봇 (`-Dgow.auto=1`), 레벨마다 `--game L --arrive …` 로 시작 (엔진 시각은 그 실행의 시각, 영상의 앞 레벨 상태 없음).
레벨 연결 [확실: 스크립트]: ATHN07 (세이브 · 절벽 계단 · 올림포스 둥근 문 · 아테나 석상 칼 위 LoadForWarp("sewr01") 면 z 438 → LoadCheck("Sewr01") 면 z 1470)
→ SEWR01 (Start WarpFAthn07 (−1872, 949, 447) 둥근 방 · 나선 계단 · 구역 제목 7 · 초록 방 상자 · DMCSpawn1 · DMCSpawn2 · LoadWad("sewr02")) → SEWR02 (폭포 ·
사다리 · toAthn03D LoadForWarp / Warp) → ATHN03D (Start WarpFSewr01 = 우물 안 사다리 앞 (160, 347, 1489) · 분홍 복도 · 나선 · magicLever → MechanicalStairs ·
구역 제목 3 · LoadWad ATHN03E · 걷기 이음) → ATHN03E (세이브 (363, 1120, −470) · MoviePlay · SCR_AresSky · 절벽 길 · LoadWad DEST00) → §31.

### 30.1 장면 대응
| 영상 | 원작 (스크립트·데이터) | 엔진 |
|---|---|---|
| 0–6 | ATHN07 절벽 아래 세이브 제단 빛기둥 · R2 (Start WarpFAthn06C) | 세이브 포인트 (88, −80, 206) + 빛기둥 — 같은 자리 (a7 경로 0–6 초) |
| 10–46 | 횃불 달린 절벽 계단을 올라감 | 같은 계단 · 레일 카메라 (a7 경로 6–72 초) |
| 50–62 | 달빛 절벽 · 올림포스 둥근 문 (MDL_olympusDoorTrue, R2) · 문 옆 아테나 석상 · 횃불 · 바위 옆 마른 풀 | 고치기 전 문에 공 사진 · 석상 옆 줄무늬 "우리" → 고친 뒤 청동 문양 (§30.2-4) · 짧은 풀 다발 (§30.2-5) |
| 70–90 | 바다 위 거대한 아테나 석상 (칼) · R2 석상 442 "아테네 여신의 석상입니다…" (86) · R2 문 110 "기이한 유적입니다…" (90) | 같은 메시지 (a7n 39.1 · 46.5 초, a7 경로 92.1 · 99.5 초) |
| 104–110 | 절벽 끝 R2 → 315 "이상한 기운이 감돌고 있습니다…" | 같음 (a7top, R2 방향 (1054, 1000) — (1054, 988) 쪽을 보면 안 뜸) |
| 122–132 | 계단을 내려가 세이브 빛기둥 옆으로 → 석상 칼 위를 걸음 | 칼 위 경로 — z 438 에서 LoadForWarp("sewr01") (SEWR01 꺼진 채 적재), z 1450–1500 에서 LoadCheck → Warp 요청 (a7seam 11.0 초, 경로 중 Warp 는 하네스가 막음 — 도착은 `--arrive ATHN07`) |
| 136–156 | SEWR01 둥근 방 → 나선 계단을 위에서 내려다봄 (camStairCase, 시선 바로 아래) | 고치기 전 하강 내내 검은 화면 → 고친 뒤 같은 구도 (§30.2-1, s1n 4–11 초) |
| 160–166 | 초록 물 방 · 상자 → 카드 "피닉스의 깃털을 획득했습니다… 4 개를 더" | 같은 방 · 상자 둘 (s1n 15–19 초). 상자는 gochest4 CSM 011 (깃털) [데이터]. 카드는 ATHN07 gochest1 로 확인 (종류 4, 하네스는 0 개에서 시작 → "5 개를 더") |
| 170–224 | 구역 제목 7 "아테네의 하수도" · 안개 낀 물 홀에서 언데드 병사 (내려꽂기 청록 빛줄기 199 · 201 · 770), 보랏빛 기둥 (219–220) | 구역 제목 7 (s1n 15.5 초) · DMCSpawn1 = R_SKC 3 + 발코니 R_SK2 궁수 3 (20–25 초), 내려꽂기 청록 띠 (§30.5) |
| 336–348 | 상자 초록 오브 · 창살 문 · 벽 오르기 | 봇이 벽 오르기 앞 (−2639, 74, −984) 에서 멈춤 — 비교 안 함 |
| 352–462 | 미노타우로스 둘 (붙잡기 420 · ○ 450 · 끝내기 456–462) · 포세이돈의 분노 432 | DMCSpawn2 R_MI2 2 + R_SK2 2 · 첫 안내 7 → 메시지 507 "미노타우르스를 죽이려면 ○ 을 빠르게…" · 체크포인트 CheckPoint1 · challengeB (s1easy) |
| 628–704 | SEWR02 큰 적 (사이클롭스) · 미노타우로스 · 병사, 마법, 15 히트 보너스 | SEWR02 R_CYW (사이클롭스 Lvl1) 2 · R_MI2 2 · R_SKC 2 · R_SK2 4 (s2easy) — 싸움 연출은 봇이라 대조 안 함 |
| 766–924 | 폭포 옆 절벽 싸움 · 상자 · 사다리 → 초록 터널 폭포 · 빛 드는 구멍으로 사다리 | 사다리 아래에서 위로 (s2l 0–2 초) → toAthn03D Warp |
| 924–930 | ATHN03D 분홍 팔각 우물을 내려다보는 카메라 · 크레토스가 사다리로 올라옴 | 고치기 전 우물 옆 복도에 서서 나타남 → 고친 뒤 우물 사다리에서 올라옴 (§30.2-3, s2l 2–7 초 camFrm02) |
| 935–948 | 분홍 복도 · 나선 계단 (camTunnel0A2, 시선 바로 아래) | 고치기 전 검은 화면 → 같은 구도 (§30.2-1) |
| 950–966 | 둥근 방 R2 (magicLever) → 금빛 봉인 (오메가) 이 가라앉아 나선 계단 · 쓰러진 병사들 | R2 12.5 초 → magicLever taskP → 16.4 초 camGRPtopSpiral-ON · MechanicalStairs-AniFor · SpiralStairPlug-TurnOff → 21.4 초 CamOff (d3 경로) |
| 970–985 | 광장 · 구역 제목 3 "아테네로 향한 도로" · 붉은 휘장 계단 · 폭풍 하늘 · 화로 | 구역 제목 3 (ZoneReportGroup nurbsSphere2, 31.1 초) · LoadWad ATHN03E · 걷기 이음 03D → 03E |
| 986–987 | 오메가 광장 왼쪽 위 골짜기에 불타는 거대한 아레스 | 고치기 전 없음 → 고친 뒤 같은 자리 (§30.2-2) |
| 990–1005 | 세이브 제단 빛기둥 · R2 · "메모리 카드 슬롯 1 … 덮어쓰시겠습니까?" · 화로 둘 둥근 터 | 세이브 포인트 (363, 1120, −470) + 빛기둥 · 같은 질문 흐름 (cf7f631) · 화로 (e3b 18 초) |
| 1008–1025 | 절벽 길 · 디딤돌 모랫길 · 바위 벽 문 · 상자 | 같은 길 (e3b 20–37 초), 27.3 초 LoadWad DEST00 — 구역 제목 8 부터 §31 |
| (없음) | MoviePlay R2 (영상은 누르지 않음) | camMorning · 메시지 439 "전투가 극에 달합니다…" · 아레스가 화면 가운데 (d3m 13 초) |

### 30.2 엔진 결함과 수정
1. **시선이 바로 아래인 카메라가 검은 화면이었다** (eee2250, 영상 144–152 · 945–948) [확실: 0x1F1108 → 0x1F1000]. SEWR01 camStairCase · ATHN03D camTunnel0A2 는
   전방 (0, −1, 0) (pitch −90, yaw −41.2 / −48.7, 범위 0). 원작 카메라 상태 행렬은 R(yaw, pitch) (roll 0) 이고 위쪽 = 그 행렬의 Y 축인데, 엔진은 위쪽을 늘 (0, 1, 0) 으로
   두어 look-at 이 퇴화 → 뷰 행렬 NaN → SEWR01 나선 하강 내내 (6.5–19 초) 화면 전체가 검었다. `CameraRig.up(yaw, pitch)` = R 의 Y 축 (수평 시선에서는 예전과 같은
   look-at), 전투 화면 맞춤 줌 (0x1EF078) 도 그 위쪽에서 시작. 레벨 WAD 의 배치 카메라 중 |전방.y| ≥ 0.995 는 38 개.
2. **SCR_AresSky — 오메가 광장 왼쪽 골짜기의 거대한 아레스** (9c5433f, 영상 986–987) [확실: 0x246BB8 → 0x218968 · 0x25C6B0 → 0x2189A8]. 생성자는 SCR_Sky 와 같은
   0x218848 (모델·파티클을 층 1, GO 위치 = 원점 → 관절 0 이 눈) + {f32 −1, s32 −1}. 매 프레임 t = 항목 시간 최댓값, d = act 길이 최댓값 — d ≤ t (1 회 act 끝) 또는
   t < 이전 t (반복이 감김) 이고 ANM act ≥ 2 이면 마지막 고른 것을 뺀 무작위 act 를 처음부터 (0x13E6B0 · 0x1007C8). ATHN03D/03E: 0 main (0.033 초, 관절 트랙 없음),
   1–4 aoBAR1a03Hl01–04 (8.8 · 7.7 · 5.6 · 6.1 초). 엔진은 SCR_AresSky 를 무시해 GO 가 ATHN03E 아래 ≈ 1000 의 원점에 바인드 자세로 있었다. 이제 `LevelScene.skyNames`
   에 SCR_AresSky 소유자를 넣고 `AresSkyActs` 가 1/60 초 단위로 같은 규칙을 밟으며 하늘 패스가 지금 act 를 스키닝 (하늘 이미터 38 개도 같은 act·시간). main 동안
   메시 숨김 [추정: 원작은 1–2 프레임 바인드 자세]. 문서 16 §1.7.
3. **Warp 가 영웅 이동 상태를 지웠다 — SEWR02 사다리 → ATHN03D 우물 사다리** (f534ac7, 영상 924–929) [확실: Warp_Jump 0x1B2E08 은 영웅 행렬만 도착 Start 로
   (0x1E79D0), +0x174 이동 상태 유지]. SEWR02 toAthn03D 영역 1 (공 (−5247, 327, −4658) r 84) 은 사다리 위, ATHN03D WarpFSewr01 (160, 347.2, 1488.6, yaw 0) 은 우물
   사다리 면 (z 1488, 법선 +z, y 167–505) 0.6 앞 공중. 엔진은 Warp 때마다 상태를 버리고 바닥 없는 Start 를 가까운 바닥에 붙여 크레토스가 열린 우물 옆 복도에 서
   있었다. 이제 `GameSession.warpHeroState` 로 넘기고 WALL_CLIMB / LADDER / WALL_WALK 도착은 Start 그대로 `HeroNavigator.reattachWall` (체크포인트 되읽기와 같은 벽
   질의), 실패할 때만 바닥에 붙임. 하네스: `-Dgow.arrive.heroState`, `-Dgow.auto.msgread`.
4. **조명 없는 모델의 2·3 번째 레이어는 텍스처 α 그대로 — 올림포스 둥근 문 청동 문양** (aced5e0, 영상 54–56) [확실: 메시 VU1 마이크로코드 0x091–0x098 ·
   0x154410 · 0x167A98]. 0x154410 이 레이어마다 qw78.w = 물체 α × p28 × p38 을 만들지만 메시 프로그램은 VU 플래그 4 (vi10 bit 10) 일 때만 vf30.w 로 쓴다. 플래그 4 =
   MDL 렌더 플래그 0x1000 (조명 모델 228 개 모두 0x1800, 0x168310) · 그리기 기록 거리 페이드 α ≠ 1 · 머티리얼 rf 0x4000 (디스크 0). 엔진은 늘 p38 (대개 0.25) 를 곱해
   GS 0x01 반전 블렌드 ((Cd − Cs)·As + Cs) 레이어가 ¾ 을 덮었다 — MAT_phong15 (환경맵 ballenv7 + timeDoorA + ballenv7 메시 UV) 반원마다 공 사진이 늘어나고 문양이 거의
   안 보였다. 조명 없는 모델 2·3 레이어 p38 ≠ 1 은 56 WAD 497 곳 (대부분 0x01 반사 마스크). 첫 레이어는 그대로 [미확인].
5. **자세 GO 의 첫 레이어 플립북 — 둥근 문 옆 풀이 줄무늬 우리였다** (6098cd7, 영상 54–56) [확실: GFX 0x14C588 · gfx+0x22 현재 프레임 · 0x14CDD8 → 0x14C9F0 (16 §3.2)].
   goentrance 는 스스로 도는 반복 act ("entrance" 3.33 초) 가 있어 구운 메시가 아니라 `AnimatedModel` 로 그린다. 그 풀 MAT_lambert254 = GFX 20 프레임
   (TXR_visuals07_file178, 64×640 세로 스트립 → 프레임 64×32) + ANM_lambert254 (40 키, 1/30 초, 반복). 원작은 GFX 마다 현재 프레임 한 장만 올리므로 모든 사용자가
   64×32 한 장을 샘플한다. 구운 메시 `Renderer` 는 `MaterialFx.frame` 으로 골랐지만 `AnimatedModel` 은 첫 레이어에 스트립 전체 (2 의 거듭제곱 64×1024) 를 붙여,
   부채꼴 풀 다발 (중심 v = 1, 가장자리 v = 0.125) 마다 프레임 띠가 동심 줄무늬로 늘어선 키 큰 어두운 우리가 되었다 (석상 오른쪽 바위 앞). 이제 첫 레이어도
   `MaterialFx.frame` (노멀맵도 그 프레임), 같은 모델의 MAT_lambert250 (file174 풀잎 플립북) 도 같이 고쳐짐. 찾은 방법: `GOW_HIDE=goentrance` 로 사라지는 것을 보고
   (재질 숨김은 자세 GO 경로에 걸어야 사라짐) 프레임 선택 경로 차이를 찾음.

### 30.3 시험
`CameraRigTest.straightDownCameraKeepsAFiniteView` (SEWR01 camStairCase, pitch −90, 유한한 viewProj, 수평 위쪽 = yaw 방향; `combatCameraOverride` 는 "roll 없음"
검사로), `AresSkyTest` (03D/03E 하늘 = [goaresanimgroup1, gogroup2], act 무작위 전환 규칙, act 하나면 전환 없음), `NavDataTest.athn03dWarpArrivalClimbsOutOfTheWell`
(Start 아래 바닥 없음 · 사다리 재부착 y 347 · 스틱 위 → 복도 바닥 504), `LayerAlphaTest` (MAT_phong15 레이어 · 문 모델 0x1000 없음 · TriMesh 가 렌더 플래그를
나름 · 2·3 레이어 α 1 / 조명 0.25), `AnimatedFlipbookTest` (goentrance = 스스로 도는 반복 act, MAT_lambert254 GFX 20 프레임 64×640, ANM 40 키 반복,
`MaterialFx.pickFrame` 이 스트립이 아닌 그 시간의 프레임).

### 30.4 확인만 (바꿀 것 없음)
- ATHN07 세이브 빛기둥 · 절벽 계단 · 상자 깃털 카드 · 메시지 442 / 110 / 315, ATHN03D 분홍 복도 · 레버 · 금빛 봉인 · 구역 제목 3, ATHN03E 세이브 · 오메가 광장 · 절벽 길
  · 문 — 위 표대로 같다. ATHN07 구역 제목 6 "오라클의 사원" 은 ZoneReportGroup H0 (GlobalData:0 ≠ 6) 조건이라 ATHN06C 에서 온 영상에는 없고, `--arrive` 로 0 에서
  시작한 엔진에만 뜬다 [하네스].
- 안개 fbbc209 (걸린 안개 없으면 안개 없음) 뒤: ATHN03D 위 계단 · 03E 오메가 광장 · 03E 세이브 쪽이 rebase 전후 같은 화면 — 03D 는 SCR_Fog gopcube3948 (청록, 0.5) · 3949 (회색, 0.25), 03E 는 3950 (회색, 0.25).
  SEWR01 gofogcube 층 안개 (가산 블렌드 1, ×2 텍스처) 는 확인한 눈 위치 9 곳 (나선 · 초록 방 · 홀 · 카메라 레일) 모두에서 걸린다.
- V2-32 UV cc7e2fa 뒤: ATHN03D 광장 바닥 (오메가 봉인 둘레) · 계단으로 가는 길 타일이 어두운 띠에서 영상 958–975 의 밝은 돌 타일로 바뀌어 영상에 가까워짐 (같은 경로
  고치기 전/후). 03E 절벽 길 · 문 이상 없음.
- 9f69e4c (자세 GO 이미터가 관절을 따름 · Vis 슬롯 1) · 4214de8 (표면 이미터) 뒤: 내 레벨 Vis 엔티티는 슬롯 1 을 쓰지 않는다 (ATHN07 MZone1, ATHN03D camGRPtopSpiral ·
  MechanicalStairs · SpiralStairPlug · sewrTop · PostCamVol, ATHN03E PlaneChestOpen — 슬롯 0/3/4 · Idle). 9f69e4c 뒤 ATHN03D 광장 경로 32 프레임이 전과 화소 단위로
  같고 ATHN07 경로 이상 없음, 4214de8 뒤 SEWR01 보통 봇 싸움 70 초 (불화살 · 블레이드 불꽃 · 언데드) 이상 없음 (하수도 적은 R_SKC · R_SK2 · R_MI2 · R_CYW — 눈빛 R_SK5/SKF 없음).
- ATHN03E 끝 (e3b 38 초 뒤) 의 "광장 아래 시점 · 낙하" 는 경로가 끝난 봇이 이미 내려간 ATHN03D 자리 (160, 1152, −80) 볼륨으로 순간이동한 것 [하네스: 봇].

### 30.5 남은 것 · 미확인
- [근사] **언데드 내려꽂기 청록 빛줄기** (R_SKC goSKCflasher, PlayFX 148 SKC_DiveHitFX @rWrist): 영상 199 · 770 은 위에서 곧게 내려오는 매끈한 기둥 (흰 심),
  엔진은 손목을 따라 휘는 청록 띠 + 끝의 점들. PTC_SKCFpart 는 종류 8 (방향 줄기: 위치 q0 + q1·t, 방향 q2 = 속도 방향, 폭 16 → 0 / 0.15 초, 상자 이미터 sub 5,
  속도 5) — 모양 차이는 주로 도약 궤적 (영상은 화면 위에서 수직 강하) 으로 보이나 원작 도약 높이·q2 를 디스어셈블로 확인하지 않았다.
- [미확인] **보랏빛 기둥 (영상 219–220)** · 생성 구멍 (goSpawnHole: 반짝이 · 원반 · BlurstarTX 회전 사각형, 기둥 메시 없음): 엔진의 생성 이펙트와 같은 순간을 잡지 못함.
- [미확인] **SEWR01 물 홀 밝기**: 영상 196 · 208 은 위에서 가파르게 내려다보는 카메라에 우윳빛 안개 (층 안개 키 c2 α 0.74, 가산, ×2 텍스처) 와 빛줄기가 밝은데,
  엔진 봇 카메라 (낮은 레일) 는 어둡다. 같은 영웅 위치·카메라로 대조하지 못함.
- [미확인] SEWR01 → SEWR02 이음 (봇이 벽 오르기 앞에서 멈춤), SEWR02 사이클롭스 · 미노타우로스 붙잡기 끝내기 · 마법 (포세이돈의 분노 432, 628 · 696 의 빛) 은 봇이라
  영상과 대조하지 않음. 순찰 (WYP) 은 다른 작업 (엔진 전체) — 영상 홀의 병사 움직임은 그 뒤 다시 볼 것.
- [근사] ATHN07 바다 띠 (영상 70–82 석상 아래) 가 엔진에서 조금 밝다 · 환경맵 레이어 밝기 (영상 문이 조금 더 밝음).
- 실제 세이브 위치 (`~/Library/Application Support/devple-gow`) 는 생기지 않았다 (모든 실행 = 스크래치 홈).

### 30.6 증거 (`work/verify/sewers/`, 커밋 안 함)
`01_spiral_v144_v152_vs_engine` (영상 144 · 152 | 엔진 나선 5 · 9 초), `02_door_emblem_v54_v56_before_after` (영상 | 고치기 전 | 고친 뒤, 두 구도),
`03_grass_v56_before_after` (영상 56 | 줄무늬 우리 | 풀 다발), `04_ares_v985_v987_vs_engine` (영상 985 휘장 · 987 아레스 | 엔진 휘장 · 22 · 28 초),
`05_athn03d_plaza_v958_v975_cc7e2fa_before_after`, `06_flasher_v199_v770_vs_engine` (§30.5), `07_hall_v196_v208_vs_engine` (§30.5),
`08_cliff_path_v1008_v1020_vs_engine` (영상 1008 · 1015 · 1020 | 엔진 22 · 30 · 36 초), `09_well_ladder_v924_v929_vs_engine` (영상 924 · 926 · 929 | 엔진 2 · 4 · 7 초).

## 31. QA vs PS2 영상 2화 — 길 잃은 영혼들의 사막: DEST00 협곡 → DEST01 모래 폭풍 · 아테나 석상 · 세이렌 사냥 → DEST02 세이브 (영상 1040–2520 초, 2026-10-25)

영상 = 박대봉 2화 (https://www.youtube.com/watch?v=hoXYXNef60U, 전사 (보통)), 프레임 728×409 (4:3 게임 화면이 가운데, `work/verify/desert_sirens/video/v<초>.jpg`
112 장, 커밋 안 함). 엔진 = origin/main 4fe5fa2 위, 1280×720 lockstep, `-Dgow.difficulty=1` (보통).
레벨 연결 [확실: 스크립트]: ATHN03E → DEST00 (구역 제목 8 "길 잃은 영혼들의 사막" · 협곡 · 상자 둘 · wadLoadDest01 LoadWad("Dest01") · LoadCheckDsrt1 이음) →
DEST01 (StartMovie-MovieTrigger: FadeOut 1 초 → 동영상 Semo04 → MpegDone: 첫 세이렌 Siren1Stuff 스폰 · 메시지 345 9 초 · LoadWad("Dest00") · IdleOff + CheckPoint;
아테나 석상 둘 d01Mi01a/b (템플릿 307, R2 대화 345 / 414–416); SwitchSiteZone (첫 세이렌 근처 진입 또는 세이렌 피격 1035 115–123·160) → SirenCam · 0.6 초 PAUSE ·
5 초 뒤 SwitchSite 1025 (2, 1) → 체크포인트; 세이렌 셋 = Filter 119 → 118 (patrol3) → 120 (patrol2), 처치마다 sirenSpirit 날기 · 문 카메라 SirenDrCam · 메시지
415 / 416 / 417 · 문 별 SirenEnter/Glow; 2·3 째 사냥 동안 구역 (Siren2Sec1–15 · Siren3Sec1–18) 마다 갑옷 미노타우로스 (템플릿 51) 둘씩 리스폰; 셋째 뒤 EndDoor 열림 ·
DMCdog 문 열림) → LoadCheckDsrt2 → DEST02 (SaveGameZone 세이브 (2433, 512, 6739)). 영상의 처치는 넷 (1490 · 2015 · 2335 · 2445) 이고 메시지는 415 · 415 · 416 · 417 —
둘째 415 는 첫 세이렌을 다시 죽인 것 (사이에 체크포인트 재시작, 첫 처치 뒤 체크포인트는 석상 1 대화 MimeDone → Dummy4Checkpt 에서만) 으로 본다 [추정: 영상에 사망 화면은
10 초 간격 섬네일에 없음].

### 31.1 장면 대응
| 영상 | 원작 (스크립트·데이터) | 엔진 |
|---|---|---|
| 1035–1060 | DEST00 협곡: 구역 제목 · 상자 · 어두운 협곡을 내려감, 카메라는 협곡 위 EntryCam 에 남아 크레토스가 작아짐 (1055 · 1060) | 같은 카메라 · 같은 구도 (`03` 1 행 1 열) |
| 1065–1068 | DEST01 입구: 어두운 모래 안개 속 석상 실루엣 쪽으로 걷다가 페이드 (FadeOutEntry 1 초) | 고치기 전 석상 없음 (§31.2-2), 크레토스·석상 안개 없음 (§31.2-1) → 고친 뒤 같음 (`03` 2 행 1 열) |
| 1072–1144 | 동영상 Semo04 (빛나는 아테나 석상 · 판도라의 신전을 진 크로노스) | `--skip-movies` 로 건너뜀 (MpegDone 501 → 같은 흐름) |
| 1145–1153 | 메시지 345 "사이렌의 노래를 따라가라, 크레토스. …" (9 초) — 크레토스 뒤 카메라, 안개 속 석상 실루엣 | 같은 메시지 · 같은 구도 (`01` · `02`) |
| 1170–1344 | 모래 폭풍 속 달리기 — 석상 실루엣 (1170) · 마른 풀밭 (1340) · 돌바닥 (1344); gofogregsand + goweatherfxsand1/2 | 같은 안개·모래 날림 (§31.2-1 뒤) — 날림은 영상이 조금 진함 [근사] |
| 1348–1478 | SwitchSiteZone → SirenCam: 계단 위 첫 세이렌 (1348) → 싸움 · 초록 상자 (1352 · 1420) · 세이렌 불덩이 (1380 · 1478) | 같은 SirenCam 컷 (SwitchSiteZone-CamOff 21.3 초 → VisCheckPoint 21.8 초 체크포인트) · 상자 |
| 1488–1497 | 첫 처치: ○ 피니셔 (1488–1489, 세이렌 반응 기술 MAISIRSOUL 423) → 보랏빛 영혼 + 검은 연기 (1490–1495) → 문 카메라 · 메시지 415 (1497) | 봇이 세이렌을 못 이겨 경로 `kill:122` (§31.3) → FirstSirenDestruction · sirenSpirit AnimFly · DoorCamOn · 415 (24.85 초). 고치기 전 영혼 꼬리가 곧은 막대 · 연기 없음 → 고친 뒤 길을 따라 휜 보랏빛 꼬리 + 검은 연기 (§31.2-6, `07`), 몸 색은 §31.4 |
| 1499–1502 | 문 카메라: 날아든 별 (흰 원반 1500) 이 문 둥근 홈 왼쪽 아래에 남음 (1502) | 고치기 전 섬광 (EndDefx) 뿐 · 별 없음 → 고친 뒤 SirenGlow1 이 홈 왼쪽 아래 (28.17 초 VisOn, §31.2-5, `04` · `04b`) |
| 1790–1835 | 둘째 사냥: 갑옷 미노타우로스 (템플릿 51) · 모래 폭풍 · 석상 2 R2 (1830) | 구역 Siren2Sec 리스폰 (경로 `kill:51`) |
| 2005–2020 | 첫 세이렌 재처치 (피니셔 2010 · 영혼 2015) → 415 다시 (2020) | (경로는 한 번) |
| 2160–2345 | 미노타우로스 붙잡기 (2210 ○ 연타) → 둘째 세이렌 (2325) 피니셔 (2330) · 영혼 (2335) → 416 (2340) → 문 카메라: 첫 별 (흰 점) 옆으로 꼬리·연기 끄는 영혼이 문으로 (2345) | 40.43 초 처치 → 416 · 같은 구도 (첫 별 + 꼬리·연기 영혼, `07` 3 행 4 열) · SirenEnter2/Glow2 |
| 2435–2456 | 마지막 세이렌 (머리 위 ○ 2435) 피니셔 (2440) → 영혼 (2446) → 417 (2448–2450) → 문 카메라: 별 셋 (2453–2454, 위 · 오른쪽 · 왼쪽 아래) → 문이 올라감 (2456) | 56.52 초 처치 → 417 · EndDoor-DoorCam · DMCdog AniOut · SirenEnter3/Glow3 (62.0–63.0 초) · EndDoor AnimOpen (63.03) — 별 셋 같은 자리 (`04c`) · 별 끔 66.02 · EndCutscene 67.02 |
| 2475–2511.5 | 열린 문으로: 모래 안개 속 빛기둥 · 횃불 (2505–2511.5) | 같은 구도 · 같은 안개 (r16 74.6–80 초, `06`) |
| 2512–2513 | 카메라가 제단 방 안으로 끊김: 안개 없음 · 횃불 · 색이 바뀌는 상자 · R2 | 같음 — DEST01 gofogregsand 칸 밖, DEST02 goshaftfog2 (CXT_bvfoginitial, CentreZ 37000) 는 150 안쪽 계수 0 (200 에 0.12). 상자 빨강/초록/파랑 순환 |
| 2514–2520 | 세이브 질문 "제우스께서 …" → "저장하기 완료." | 같은 흐름: R2 (64 안) → 질문 → 메모리카드 슬롯 → 새 GOW 파일 → 저장 중 → 완료 (82.55 초, 세이브 포인트 번호 14 = Creator SaveGameZone 슬롯 6, 스크래치 홈 slot0.save) |

### 31.2 엔진 결함과 수정
1. **캐릭터·자세 GO 가 안개를 전혀 받지 않았다.** `FxEnvironment.applyFog` / `disableFog` 가 셰이더 프로그램을 묶지 않고 glUniform 을 불러, `GameScene.drawActors`
   (영웅·적 셰이더) · `LevelScene.renderPosed` (자세 GO 셰이더) · 거울 반사의 영웅에 건 안개가 **그때 묶여 있던 다른 프로그램** 으로 갔다 (같은 소스면 같은 위치 —
   레벨 자세 GO 셰이더가 대신 받고, 다른 소스면 엉뚱한 유니폼). 원작 안개는 층 2 칸 5 노드의 후처리라 층 2 의 모든 것 (레벨 · 캐릭터 · 자세 GO) 에 Z 로 걸린다 [확실:
   0x125D08 → 0x15F288(층 2, 칸 5, 0x7100), 0x161A80 의 층 1–4 차례, 16 §1.2]. DEST01 gofogregsand (CentreZ 16500, Fog_A 1) 는 175 앞의 크레토스에 0.55, 297 앞의
   석상에 0.74 — 영상 1150 · 1170 · 1200 · 1300 은 크레토스가 모래 안개에 반쯤 묻히는데 엔진은 또렷했다 (`01` · `02`). 수정: 두 함수가 먼저 `s.use()`.
   셰이더 디버그 (안개 계수를 색으로) 로 확인: 고치기 전 영웅 화소 uFogMode 0, 고친 뒤 계수 0.54 (식 0.555).
2. **블레이드·아르테미스의 검 = 층 2** [확실: 0x1F9F28 – 0x1FA010 · 0x12E118 – 0x12E1F8 · 데이터]. 47 §7.4.2 의 "Blades_Init 의 0x150738(모델, 2) → 층 3" 은
   0x23EB58 로 만든 **사슬 띠 인스턴스** (MAT_chainlink/chainglow, 서버 vtable +0x2C 의 반환, +0xD4 = 3) 였고 무기 궤적 0x12DE70 도 궤적 띠다. 블레이드 모델 머티리얼
   (R_WPN* `MAT_M01Blade128` …, R_BRSRK* `MAT_Berserker1A`) 은 flags2 0x100 (층 2 반투명 단계 = 그림자 합성 뒤) 이라 안개를 받는다 — 영상 1065 · 1150 의 등 블레이드도
   크레토스와 같이 묻힌다. `BladeModels` · `ArtemisBladeModel` 의 `layer3` 를 뺐고 (단계는 0x100 으로 그대로 LATE), `AnimatedModel` 은 층 3 모델·층 3·4 머티리얼
   (flags2 0x18) 파트에 uFogOff, 가산·뺄셈 파트에 uFogAdd ((1 − F) 배, 구운 메시 `Renderer` 와 같은 규칙) 를 모든 모델에 건다 (예전엔 이펙트 GO `sceneFog` 만).
3. **그림자도 안개 아래.** 원작 그림자 합성 0x160518 은 층 2 불투명 단계 뒤 · 안개 노드 앞이라 (1 − F)·(c − 38) + F·f — 엔진은 이미 안개를 받은 화소에서 38 을 빼
   안개 속 크레토스 아래 그림자만 또렷했다. `ShadowRenderer` 어둡게 하기 패스가 그 화소 장면 깊이 (지금 깊이 사본, `SceneFog.applyShadow` — 안개 앞 반투명 패스용
   사본은 armDepth 뒤 다시 복사) 의 계수로 38·(1 − F) 만 뺀다 (안개 블렌드 0; 가산·감산 안개는 38 그대로) [확실: 순서 / 근사: 0 에서 멈추는 자리].
4. **사막 입구 아테나 석상이 없었다.** DEST01 d01Mi01a/b-aoXSAAI1/2 (SpawnAtStart, 템플릿 307 = DEST01 자체 AI1 → 오브젝트 goaoxsa) 는 DEST00 의 LoadWad("Dest01")
   로 DEST01 이 **다른 슬롯** 일 때 시작 엔티티가 돈다. 엔진의 이웃 스폰은 RSRCS 종류 (43 §17.2-1) 만 찾아 "이웃 스폰 생략" 하고, 이음 뒤에도 시작 엔티티가 다시 돌지
   않아 석상이 끝내 없었다 (영상 1065 · 1145–1153 의 석상 실루엣, R2 대화 345 · 414–416). 원작 레벨 WAD 도 슬롯마다 상주한다 → `GameScene.neighbourTemplateKind`
   (다른 슬롯 레벨 WAD 의 AI1 → "레벨:오브젝트" 종류, `neighbourKinds`), 이음 때 새 현재 레벨의 것을 `kinds` 로 옮긴다 (`levelTemplateKind` 와 같은 키). 확인: 협곡 경로
   (r3) 에서 "이웃 적 스폰: DEST01.WAD (템플릿 307)" 둘, 이음 뒤 적 종류 `[R_MI2, DEST01:aoxsa, R_SID]`.
5. **사이렌 문 별이 보이지 않았다 — V2-32 UV 는 float 가 아니라 정수다** (04 §4.2 정정). DEST01 SirenGlow1–3 / SirenEnter1–3 (MAT_lambert13, 가산 · 텍스처
   TXR_ntitiesDest01_file2 · flags2 0x8 = 층 3) 은 스크립트대로 켜지고 그려졌지만 UV 가 0 … 5.7e-42 였다: VIF UNPACK V2-32 를 float 비트로 읽어 4096 (= 1.0) 이 비정규
   실수가 되어 텍스처 모서리 화소 (검정 (1, 1, 1) — 가산이라 0) 만 찍혔다. 근거 [확실]: (a) V2-32 UV 배치는 V2-16 배치와 같은 MSCAL 진입점 0/3/6 (735 / 352 / 282 배치) · 같은 STCYCL CL 2/3/4
   배치를 쓴다 — VU1 메모리에는 V2-16 은 부호 확장, V2-32 는 그대로 같은 32비트 정수가 들어가므로 마이크로프로그램이 둘을 구분할 수 없다 (ITOF12); (b) 81 WAD 의
   V2-32 UV 1369 배치 · 값 182 292 개 중 0 이 12 019, |v| < 2^23 정수 170 251 (−255.8 … 255.9), float 로 그럴듯한 값 (지수 0x60–0x9F) 0, 나머지 22 는
   ATHN03A/03E MAT_M01stnBlock05 의 ±5322 (반복 무늬, float 로 읽으면 3.8e-38 · NaN). V2-32 는 V2-16 의 ±8 을 넘는 UV 용이다 (DEST02 톱니바퀴 −7.25 … 8.25, 기둥
   줄 ±16). 수정: `VertexBatch` 가 V2-32 도 ÷ 4096. 영향 [확실: 목록]: 줄 · 사슬 · 달빛 (MAT_moonlight) · 금속 테 · 코니스 · 구름 하늘 (ARENA testCloud1asdf) ·
   OLYMP01 별 플레어 · PAND00C 고리 석재 · DEST02 톱니바퀴 · DEST01 문 별 — 모두 지금까지 텍스처 (0, 0) 화소 한 색이었다. 확인: 첫 처치 문 카메라 (영상 1499–1502)
   에 고치기 전 별 없음 → 고친 뒤 홈 왼쪽 아래에 별 (`04` · `04b`), 셋째 뒤 별 셋이 영상 2454 와 같은 삼각 배치 (`04c`).
6. **사이렌 영혼의 꼬리가 곧은 막대였고 검은 연기가 없었다 — SCR_GhostTrail 미구현 · 배치 이미터 고정 · Vis 슬롯 1 무시.** 문으로 날아가는 영혼
   gosirenspirit (MDL_sirenSpirit, 관절 14: 2 "tail" 아래 joint1–8 이 16 간격) 은 원작에서 꼬리가 비행 길을 따라 휘어 긴 보랏빛 줄기가 되고 (영상 1491–1497,
   2335, 2345, 2445–2447) 몸 관절 1 의 감산 이미터가 검은 연기 덩이를 길에 남긴다. 원작 [확실: 코드]: SCR 처리 0x246918 → 0x20D988 (0x24 바이트, 부품 +0x34 =
   0x20E708 = 켤 때 기록 리셋 0x20DE20) · 매 프레임 0x20DAA8 → 0x20E450 — 머리 (인스턴스 헤더 0x22 = 2, 템플릿 본문 "tail") 의 직속 자식 중 플래그 0x20
   관절을 생성 자세 거리로 가까운 순 (스킵 리스트 0x234088, 키 −거리 내림차순) · 길이 112 · 기록 ⌊112/8⌋ + 3 = 17; 머리가 기록 [0] 에서 8 보다 멀면 8 간 점을
   (위치 lerp · 회전 slerp 0x184388) 앞에 끼우고, 관절 i 는 길 위 (누적 구간 − 남은 거리) 자리 — 머리 쪽은 lerp/slerp, 기록 안은 Catmull-Rom (0x20DEF0) ·
   slerp 피라미드 (0x20DFC0, Barry-Goldman). 그 행렬이 관절 월드 (로컬 = T · 머리⁻¹ 을 +0x50 에 씀). 수정: `GhostTrail` (기록·자리 식 그대로), `Platforms`
   몸이 생성 자세로 만들고 `Body.drawJoints` 가 매 틱 갱신해 그릴 관절로 (숨김과 무관 — 0x246918 은 GO +0x4 bit 0 만 보고, Vis 슬롯 0 은 부품 0xF/0x1C 만).
   연기: FXC_emitter31 (관절 1, 60/초, 감산 PTC_particle31 수명 0.7 초) 이 엔진에선 불러올 때 자리 (쉬는 영혼 = 사막 한가운데) 에 고정 · 늘 켜져 있었다 →
   `LevelFx.followOwner` 가 움직이는 몸의 관절로 이미터를 다시 놓고 (이미터 월드 = FXC 로컬 · 관절 월드, 원작 0x1227C8 → 0x130140), Vis 슬롯 1 (부품 타입
   0x1E EffectsServer — Vis 0x1ABAA8 의 +0xB8 → 0x1B1398, 0x1ABB6C; Servers_Create 0x17D3FC) 을 `LevelFx.setOwnerEffects` 로 받는다 — SoulOffInit /
   SoulOff 는 슬롯 0 · 1 을 2 로, SoulOn 은 1 로; DoorSparkle-VisOff · AthenaSparkle2-SparkleOff 도 같은 짝 (예전엔 문이 열린 뒤에도 반짝이가 났을 것).
   확인: 첫 처치 문 카메라에서 꼬리가 길을 따라 휘고 연기 덩이가 남음 (`07`, `07b` — 영상 1493 · 2345 와 같은 짜임), 둘째 처치 문 카메라는 첫 별 옆으로
   영혼이 문으로 (2345 와 같은 구도).
7. **세이렌 둘레의 흰 빛 줄기 · 사막에 남는 회청색 먼지 덩이 — PlayFX 0x4 (기술 진행에 맞춤 + 창 끝 정지) 와 코드 이펙트의 act 한 바퀴.**
   ① 세이렌 불덩이 (SIRFIREBALLLEFT/RIGHT, 1.333 초) 는 SCRIPT SCR_PlayEffect 창 0.00–0.70 으로 PlayFX 122–127 (goSIRsum*: 플래그 0x1 Particle ·
   0x4 · 0x8 Attached, 오른/왼팔 관절, Scale 2) 을 켠다. 이펙트 act 는 5 초이고 FXC_SSAGemit* (커브, 가산 MAT_BHAsmoke7) 는 act 4.0–4.4 초에만 분출한다.
   엔진은 0x4 를 몰라 매번 5 초를 실시간으로 다 돌려, 던진 뒤 4 초에 세이렌 몸을 덮는 흰 줄기 · 덩이가 났다 (`08a`; 영상 1360 · 1380 · 1400 · 1478 · 2005
   의 세이렌은 어둡고 손에 작은 빛뿐, `08c`). 원작 [확실: 코드]: 0x19BBE0 의 셋째 키 (해시 0x71BFA948) = 비트 0x4 → 0x19B308–0x19B320 이 이펙트 GO
   애니 트랙 속도 +0x3C 를 0 으로 (스스로 진행하지 않음), 창이 끝나면 문맥 정지 0x19A4A0 이 Particle 이면 0x2 · 0x4 일 때 이펙트에 정지 비트 (+0x0 |= 1,
   0x19A5AC–0x19A5D4); 같은 스크립트 · 템플릿 문맥이 살아 있으면 새로 만들지 않고 (0x207408 마스크 6 → 0x19A7F4) 끄지도 않는다. 시간 = 기술
   진행률 × act 길이 [추정: 데이터 — 같은 goSIRsum* 이 5 초 SIRSUMMON (속도 1.5) 에서는 진행 0.8 의 분출이 TARGET_FX 0.82 와, SIRLITSONG 의
   goSIRsumbodyG 분출이 SCR_ConcussionBurst 0.80 과 겹치고, 외눈박이 PlayFX act 는 기술 애니와 길이가 같다 (cy2Swipe 3.333, cy2Overhead 7.333)].
   그래서 불덩이에서는 act 0 → 3.5 초 (창 0.7) 에 꺼져 분출에 닿지 않는다. 수정: `MagicFx.playFx` 가 SCRIPT 창 끝을 받아 Particle + (Looped | 0x4) 면
   창 끝 · 기술 바뀜에 정지, 0x4 면 `Effect.hold` + 시간 = 진행률 × act, 같은 기술 창 안 같은 템플릿은 이어 쓰기. 확인: 같은 싸움 (r18b) 에서 흰 빛이
   사라지고 세이렌이 영상처럼 어둡다 (`08b`). 0x4 를 쓰는 다른 PlayFX (전 디스크 76 개 중 0x4: R_CNM 불티 · 구멍, R_CEA 도약, R_CY2 휘두르기 · 내려치기 ·
   사격, ATHN08 바바리안 · 이블 크레토스) 도 같은 규칙. Particle 이 아닌 0x4 (goBMIRWR1–3, goBARflameThro …) 는 예전대로 실시간 [근사].
   ② 달리기 먼지: 사막 모래 = SheetFX 40 → MFX 040 (SND_FOOTSTEP_DIRT, Run Dust Name goRunDust). goRunDust = act runDust 0.2 초 반복 · 생성 곡선 최대
   218/초 · PTC_dustpart 알파 6.3 − 4.9·t (5 %) · 수명 1.3 초. 엔진 `MagicFx.footstep` 은 0.6 초 (세 바퀴 ≈ 60 개) 를 틀어 겹친 먼지가 크레토스 뒤에
   회청색 덩이로 남았다 (영상 1185 · 1505 · 1832 에는 없음, `09`). 원작 [확실: 흐름]: 0x221A90 (영웅만 — 생물 +0x174 bit 31, 0x1E7E20 다섯째 인자,
   0x1D6368 만 1) → 0x228558 → 0x228210 이 GO 애니를 0x1007C8(애니, act 0, …, 0x40, 1) 로 틀고 트랙 끝 콜백 셈 (+0x18, 0x2282D0) 이 0 이면 지운다 →
   act 한 바퀴 [추정: 모드 1 = 한 번]. 수정: `MagicFx.oneAct` = 첫 act 길이 (0.2 초). 확인: 사막 (r17) · ATHN04A 도로 (r20) 에서 먼지가 옅은 한 점으로.

### 31.3 시험
`DesertSirensQaTest` — `desertFogHazesCharactersAtCameraDistance` (DEST01 안개 데이터 · 175 → 0.555, 297 → 0.742, 70 → 0), `shadowSubtractionIsUnderTheFog`
(38·(1 − F), 가산·감산 안개는 38), `bladeModelsAreLayer2AndLayer3IsTheChainStrip` (블레이드·검 머티리얼 0x100 · 층 3·4 아님, Blades_Init · 0x12DE70 의 0x23EB58 →
+0xD4 = 3 → 0x150738(…, 2) 순서), `desertAthenaStatueIsANeighbourLevelTemplate` (SpawnAtStart · 307 · RSRCS 에 없음 · DEST01 레벨 템플릿 aoxsa),
`v2_32UvIsTheSame4_12IntegerAsV2_16` (합성 VIF 배치: V2-32 4096 → 1.0 · −2048 → −0.5 · 21 799 936 → 5322.25, 같은 값의 V2-16 과 같음),
`sirenDoorStarsHaveFullTextureUvs` (DEST01 별 모델 6 개 UV 0..1 · 최대 1), `sirenSoulGhostTrailIsTheTailJointChain` (인스턴스 둘 · 머리 2 · 꼬리 3–10 ·
112 · 17, 0x246940 → 0x20D988 · 0.125 · 8 · 플래그 0x20 · +0x34), `ghostTrailTailFollowsTheHeadPath` (+X 300 → 112 뒤 정확, +Z 80 → 48 뒤 Z 다리 ·
모퉁이 지난 joint8 은 기록이 모퉁이를 현으로 잘라 ≤ 8·(√2 − 1) 더 뒤, 회전 = 머리), `visSlot1SwitchesTheSoulSmokeEmitter` (0x1ABB6C 0x1E · Servers_Create · 영혼/반짝이 Vis 의 슬롯 0·1 짝 ·
emitter31 관절 1 · 60/초 · 감산 0.7 초). `SirenSummonRunDustTest` — `playFxBit4FreezesTheEffectTrackAndStopsAtTheEventEnd` (0x19BCB0–0x19BCC4 해시 ·
비트 0x4, 0x19B30C/0x19B320 속도 0, 0x19A5AC–0x19A5D0 정지 비트), `sirenSummonGlowIsSyncedToTheFireballAndStopsBeforeTheBurst` (PlayFX 122–127 = 0xD,
act 5 초 · 창 0.7 안 분출 0 · 4.05 초 > 300/초, SIRfireBall 1.333 · SIRsummon 5, 외눈박이 act = 기술 길이), `runDustPlaysOneActLoop` (MFX 040 →
goRunDust, 반복 · 수명 무한 → oneAct 0.2, 0x221C7C → 0x228558, 0x228268–0x228270 0x1007C8(…, 0x40, 1)).

도구: 자동 경로 단계 `kill:AI1` (빈 값 = 가장 가까운 적) — 그 템플릿의 살아 있는 적을 HP 0 + EV_DEATH. 봇이 세이렌 (DTre 122 자가 회복 · 불덩이 Arrow 124 · 비명) 을
쉬움에서도 이기지 못해 (둘째 세이렌에서 사망) 처치 뒤 연출 (DestructionSensor → 문 카메라 · 메시지 415/416/417 · 별 · 문 · 세이브) 을 보려고 쓴다. 피니셔
(MAISIRSOUL) 를 거치지 않으므로 처치 → 문 카메라 간격 (엔진 0.5 초) 은 영상 (피니셔 끝 → 약 6 초) 과 대조하지 않는다. 경로 (r16, `--game DEST01 --arrive
DEST00@2400,507,1282,0 --skip-movies`, 쉬움): 협곡 → 첫 세이렌 옆 순간이동 → kill:122 → patrol3 (1792, 509, 2336) → kill:51 · kill:122 → patrol2 (3360, 520, 3968)
→ kill:51 · kill:122 → 문 앞 (2432, 510, 6380) → 걸어서 제단 (2433, 512, 6715) → use → ○ ○.

### 31.4 남은 것 · 미확인
- ~~[미구현] SCR_GhostTrail — 영혼 뒤 보랏빛 긴 꼬리와 검은 연기가 엔진엔 없다~~ → §31.2-6. 남은 것: 다른 슬롯 레벨 몸의 이미터는 따라가지 않음 (영혼이 날 때는
  DEST01 이 현재 레벨), 피니셔 중 R_SID PlayFX 121 "Siren Soul" (gosirenspiritgrp, 같은 꼬리) 은 봇이 피니셔를 못 해 확인 못 함, 둘째·셋째 비행 첫 순간에 머리가
  문 → 출발점으로 뛰어 꼬리가 잠깐 곧게 문 쪽을 가리킨다 (원작도 그때 리셋하지 않는다고 읽음 [추정]), 0x20DAD0 의 그리기 반지름 = 길이 × 1.5 는 엔진에 없음 (자세 GO 는
  절두체로 자르지 않음).
- [미확인] 영혼 몸 색: 영상은 연보라 몸에 파란 꼬리 (narrowAdd1 텍스처), 엔진은 몸이 거의 흰색. MAT_oorFlyBaked_AddMat1 (텍스처 없음 · 가산 · 헤더 0.4 ·
  레이어 2.0 · p28 0.5) 정점색 평균 (88, 88, 141, 62), 최대 (255, 255, 255, 64). GS 는 텍스처가 없으면 정점색을 0–255 그대로 쓰므로 (조명 없는 모델 —
  aced5e0 의 vf30 = 1) vc/128 인 엔진이 2 배 밝을 수 있으나, 텍스처 없는 레이어 색을 절반으로 해 본 실험에서도 몸은 그대로 희어 원인이 아니다 —
  가산 껍질 겹침 · 텍스처 레이어 (addSubMat1: 감산 repeaterSoft × 0.6 + 가산 narrowAdd1) 쪽 [미확인]. 문 카메라는 멀어 안개 (1 − F) 도 섞인다.
- ~~1025 SwitchSite (세이렌 AI 모드 (2, 1))~~ → 942a3a1 (WYP 순찰, 43 §32.6). 다시 확인 (r22, 보통): SirenCam 뒤 20.8 초 SwitchSite 로 첫 세이렌이 Patrol1 로 움직이고
  (2426, 6150) → (2514, 6028), 둘째는 Patrol3 노드 11 에서 (1744, 2256) → (1113, 2557) 로 약 80/초, 셋째는 Patrol2 노드 3 에서 움직인다 — 영상 1170 · 2325 처럼
  모래 속을 떠다닌다 (속도는 영상에서 잴 수 없음). 1017/1018 영웅 PAUSE/UNPAUSE 는 미구현 (원작도 디버그 출력 · 오브젝트 메시지 전달뿐).
- [근사] 문 별이 영상보다 조금 크고 테가 회색 (층 3 = 안개 없음, 자리·개수·시점은 같음). 세이브 빛기둥은 영상 2512.5–2513 에서 가까이 보면 넓고 밝아 크레토스가
  묻히는데 엔진은 좁다 (R_PERMK savepoint, 공용). 모래 날림 (goweatherfxsand) 은 영상이 조금 진함.
- [미확인] 처치 → 문 카메라 간격 (영상: 영혼이 몸에서 나와 날아간 뒤 약 6 초 — 원작 제거 시점이 MAISIRSOUL 의 SCR_ExplodeDeath @0.84 인지) 은 봇이 피니셔를
  못 해 엔진에서 재현하지 못함.
- ~~[미확인] 달리기 먼지 회청색 덩이~~ → §31.2-7 ②. 남은 것: 엔진은 달리기 (walkBlend2) 반 주기마다 내고, 원작은 기술 발소리 이벤트 (0x206FE0 → 0x221DC0,
  레지스터 0–3) 마다 걷기에도 낸다 — 먼지 한 점은 아직 영상보다 조금 보인다 [근사].
- [미확인] 싸움 (봇, 보통, r18 — 영상 1352–1488 · 2005 · 2330 · 2440 과 대조): ① ~~영상의 블레이드 휘두르기는 손에서 칼끝까지 넓은 주황 불꽃 호 (1352 · 1356 ·
  1484 · 2005) 를 남기는데 엔진은 사슬이 검은 고리 그대로이고 칼 둘레의 좁은 노란 부채 (0x1FC070 사슬 굽힘 부채, `BladeChain.trail`) 와 칼끝 불꽃뿐 —
  넓은 호는 무기 궤적 띠 0x12DE70 (층 3, 0x23EB58 인스턴스, 호출 0x25DD68 = vtable 0x2C3D54) 쪽으로 보이며 엔진에 없다 [추정]~~ → §35 (블레이드 객체 +0xB4 의
  EffectsServer 궤적 0x12DE70 [확실], `BladeTrail`).
  ② ~~세이렌 불덩이 소환 때 흰 빛 줄기·덩이~~ → §31.2-7 ①.

### 31.5 증거 (`work/verify/desert_sirens/`, 커밋 안 함)
`01_fog_v1150_before_after` (영상 1150 | 고치기 전 | 고친 뒤), `02_fog_kratos_closeup_v1150_before_after`, `03_entrance_statue_after_seam` (엔진 협곡 → 입구 → 메시지 345 6 장 |
영상 1060 · 1065 · 1150), `04_door_star_v1500_before_after` (영상 1499 · 1500 · 1502 · 2345 | 고치기 전 | 고친 뒤 | 영상 2453 · 엔진 별 둘 · 영상 2454 · 엔진 별 셋),
`04b_door_star_socket_zoom_before_after`, `04c_three_stars_v2454_vs_engine`, `05_soul_door_cam_v1490` (영상 1490 · 1491 · 1495 · 1497 | 엔진 kill 뒤),
`06_door_open_to_save_v2453_2520` (영상 2453 · 2456 · 2510.5 · 2512 · 2512.5 · 2514 · 2520 | 엔진 r16), `07_soul_ghost_trail_smoke_before_after` (영상 1491 ·
1493 · 2335 · 2345 | 고치기 전 문 카메라 | 고친 뒤 첫·둘째 처치), `07b_soul_trail_zoom_v1493_vs_engine` (영상 1493 | 전 | 후 | 영상 2345 | 후), `08a_siren_fight_summon_glow_before` · `08b_…_after` (r18b, 보통),
`08c_siren_video_v1360_1400_1356_vs_engine_before`, `09_run_dust_video_before_after` (영상 1185 · 1832 | 고치기 전 덩이 | 고친 뒤).

## 32. QA vs PS2 영상 2화 — 뿔피리와 크로노스: DEST02 돌 상자 · 부조 → DEST03 뿔피리 · 모래 벽 · 세이렌 → 둘째 뿔피리 · 크로노스 → PAND00A 세이브 · 다리 → PAND00B/00E 모래 길 · 죽어 가는 병사 · 키클롭스 · 크랭크 (영상 2520–3520 초, 2026-10-25)

영상 = hoXYXNef60U (2화, 전사 (보통) = `-Dgow.difficulty=1`, 길 찾기만 쉬움), 프레임 728×409 (`work/verify/horn_cronos/video/v<초>.jpg` 183 장, 커밋 안 함).
엔진 = origin/main 위 (첫 커밋 9f8f70d 때 4fe5fa2+, 마지막 대조 7521ac5 + aced5e0 · cc7e2fa), 1280×720 lockstep, 스크래치 홈.
레벨 연결 [확실: 스크립트]: DEST02 (SaveGameZone 세이브 14 (2433, 512, 6739) → 아래 홀 (바닥 274, 해골 함정 SkellyTrap1–4 리스폰) · 문장 문 PushBlockLiftDoor
(TAndF 208 "DestLiftDoor", id 1020) 뒤 벽감의 돌 상자 PushBlock-BlockOnFloor (Creator, 그룹 103) · 초록 상자 RespawnChest1 (CSM 008 순환) · 동쪽 턱 (390 → 위층 384) ·
ExitLiftDoor (TAndF 208, id 1021) · 북동 복도 부조 ReliefMsgLeft/ReliefCU (ReliefCamL/R, 메시지 170 / 169)) → DEST03 (HornCSM1 CSM 021 "DesertHorn" id 1030 → BlowCam ·
3 초 뒤 sandWedge-Animator1 · EndCutscene 13 초 → 열린 모래 복도 (FirstMpeg LoadEntry 에서 먼 타이탄 aoTITAI1 스폰, 템플릿 321, Patrol1030) → 원형 단:
PlayFirstMpeg 동영상 d01Mp01 · StormCmbtCam 세이렌 싸움 → SecondMpeg d01Mp03 → SecondMpgDone: LevelData3 · Goto CentrePlatform · HornCSM2 VisOn · 체크포인트 →
HornCSM2 (id 1031) → Horn2Cam · ThirdMpeg-fadeDown 11.4 초 · d01Mp02 12 초 · LoadForWarp/Warp "Pand00A" 12.2 초) → PAND00A (fromDest03 체크포인트 · SaveGameZone 세이브 15 ·
구역 제목 9 "판도라의 사원" (GlobalData0 ≠ 9 일 때) · 줄다리) → PAND00B (LoadSwitch2 LoadWad 로 PAND00E 를 다른 슬롯에 · 장작더미 Mpeg-TriggerVol → p00MP02 →
Gate1 · 병사 MimeGuy-aoXUSAI1 (템플릿 300) 대화 211 / 212 · CycCam 키클롭스 (R_CY3 템플릿 42 둘) · 둥근 단 문 크랭크 godoorcrank (Push "Core Ext Crank", Type 1, 12 초)
→ DemoDoors 열림 → 사원 홀 (§33)).

### 32.1 장면 대응
| 영상 | 원작 (스크립트·데이터) | 엔진 |
|---|---|---|
| 2520–2545 | DEST02 세이브 뒤 서쪽 위층 (512) → 아래 홀 (274) | 같은 길 (경로는 (2758, 300, 6700) 순간이동) |
| 2558–2566 | 문장 문: R2 (2562) · 연타로 들어 올림 → 문 뒤 벽감에 돌 상자 | b4 4.6 초 R2 → INTERACT · 연타 6 초 → "상황 동작 완료: LIFT gopushblockliftdoor" (같음) |
| 2566–2615 | 상자를 벽감에서 끌어냄 (2566 · 2570) → 홀에서 옆으로 밂 (2600–2615), 그 사이 해골 군단병 싸움 (2575–2585), 문 너머 초록 상자 | 고치기 전: 상자를 잡을 수도 막히지도 않음 · 부서지는 act 로 파편이 둘레에 흩어짐 (§32.2-6) → 고친 뒤 b4 11.9 초 잡음 (2768, 6800 면) → 17.9 초 z 6816 → 6684 끌어냄, 해골 리스폰 · 초록/파랑/빨강 순환 상자 (`09`) |
| 2620–2665 | 싸움 · 상자 동쪽으로 (반쯤 묻힌 톱니바퀴 2650) | 19.8–37.7 초 x 2768 → 3136 (턱 벽에서 멈춤). 톱니바퀴 V2-32 UV (cc7e2fa) 뒤 같은 어두운 금속 (`16`) |
| 2670 | 상자 위에서 카리아티드 벽의 위층 턱으로 점프 | b3 (쉬움) 39.7 초 상자 윗면 307 · 41 초 LEDGE_HANG · 42.3 초 위층 384 — 같은 높은 카메라 (`10`). 보통 (b4) 은 봇이 맞아 점프가 빗나감 |
| 2676 | ExitLiftDoor 앞 싸움 | 같은 TAndF 문 (연타) |
| 2702–2716 | 부조 복도: ReliefCamL/R · 양피지 메시지 170 / 169 ("…거인 크로노스의 등에", "사슬로 연결되어 있습니다.") | 같은 카메라 · 메시지 (r1) |
| 2716–2732 | DEST03 원형 단 뿔피리 R2 (2718) → BlowCam 옆 → 뒤로 물러남 · 모래 구름 (2719–2723) → 두 기둥 사이 정면, 모래 벽이 솟음 (2725–2726) → 복도가 열림 (2732) | 고치기 전 모래 벽이 처음부터 갈라져 있음 (`01`, §32.2-1) → 고친 뒤 닫힌 채 (`02`) · R2 4.32 초 100 % · BlowCam (`03`) · 7.32 초 sandWedge · 17.33 초 EndCutscene (`04`) |
| 2740–2762 | 열린 모래 복도 → 계단 · 기둥 옆 상자 → 원형 단의 둘째 뿔피리 (맑은 하늘) | tit3: 같은 길 · 같은 맑은 하늘 (StrmCam1 → 3 → 6) |
| 2770–2788 | PlayFirstMpeg: FadeOut 2 초 · Idle → d01Mp01 (모래 폭풍 · 크라토스 · 세이렌 둘) | `--skip-movies` 로 건너뜀 |
| 2792–2930 | MpegDone: CMV 질의 끔 + StormCmbtCam → 원형 단 세이렌 싸움 | 같은 카메라 · 싸움 중 R2 가 계속 떠 있었음 (`06`, §32.2-3) → 고친 뒤 없음 (`07`). 봇은 세이렌을 못 이겨 경로는 `post:501:d01Mp03` |
| 2930–2950 | 세이렌을 다 잡음 → SecondMpeg d01Mp03 → SecondMpgDone: LevelData3 · Goto CentrePlatform · HornCSM2 VisOn · 체크포인트 → 보통 카메라로 뿔피리 (2944) | 고치기 전 싸움 카메라 StormCmbtCam 이 남음 → 고친 뒤 StrmCam6 (`08`, §32.2-2) |
| 2970–2981 | 둘째 뿔피리 Horn2Cam: 높은 원경 (타이탄이 오른쪽 위 모래 언덕에) → 가까운 높은 컷 → 낮은 옆 → 어깨 뒤 → 반대쪽 → 다시 높은 원경 | h3 2.6 초 R2 → Horn2Cam (3.3 초 전환) → 같은 컷 순서 · 같은 빠르기 (`11a` · `11b`). 뿔피리 재질은 aced5e0 전후 같음. 원경의 타이탄 없음 (경로가 LoadEntry 를 건너뜀) |
| 2983–2998 | d01Mp02 (종 클로즈업 · 크라토스 뒤 · 일어서는 크로노스 "최후의 거인 크로노스…") | ThirdMpeg-fadeDown 11.4 초 → 동영상 12 초 (건너뜀) → Warp 12.2 초 |
| 3076–3094 | PAND00A 도착 · 세이브 제단 R2 | 같음 (빛기둥 · R2) |
| 3098–3104 | 저장 파일 선택: 슬롯 1 = 그림 · "길 잃은 영혼들의 사막" · "2023/1/ 9  6:17:26 am" · "전사(보통)  2:50:17" → 덮어쓰기 | s1 (DEST02 저장 14) → s2 PAND00A: 같은 그림 · "길 잃은 영혼들의 사막" · "2026/10/25 11:13:23 am" · "전사(보통)  0:00:02" → 덮어쓸까? (`12`, cf7f631 확인) |
| 3110 | 줄다리 · 구역 제목 9 "판도라의 사원" | 같음 |
| 3118–3190 | PAND00B 모래 길 (짙은 모래 안개) · DragCutscene | 같음 — fbbc209 (안개 객체가 없는 프레임은 안개 없음) 뒤에도 모래 안개 유지 확인 |
| 3194–3262 | 장작더미 → FadeOut 0.7 초 → p00MP02 (죽어 가는 병사) | 고치기 전 이웃 슬롯 PlayMovie 를 버려 동영상·501 없음 → 관문 닫힌 채, 병사 없음 (§32.2-4) → 고친 뒤 15.63 초 Gate1-EvtFromMpeg · 이웃 스폰 300 |
| 3262–3270 | 병사 대화 211 / 212 (TalkCam) | 같은 메시지 (p4 22.6 초 211, 36.15 초 212), 구도는 조금 멂 (`13`) [근사] |
| 3330–3460 | CycCam · 키클롭스 싸움 (3370 □ 붙잡기) | 같은 CycCam 구도 (`13` 아래) · R_CY3 템플릿 42 둘 |
| 3466–3510 | 둥근 단 크랭크: 크라토스가 기둥 둘레를 돌며 밂 (3476–3482) → DemoDoors | 고치기 전 잡은 자리에 선 채 기둥만 돎 (§32.2-5) → 고친 뒤 둘레를 돎 (`14`) → 문 열림 (`15`) |
| 3488–3506 | 크랭크 옆 상자 Chest2 (CSM 011 MagicShard) → 깃털 카드 "…3 개를 더 찾아야 합니다." · 빈 왕좌 둘 · Chest6 (CSM 010 HealthShard) → 고르곤의 눈 카드 (여섯째: "체력치가 증가합니다. 제물이 받아들여졌습니다.") | c1: 두 상자 열림 · 아이템 카드 종류 4 / 3 · 같은 문구 꼴 (하네스는 개수 0 에서 시작해 "5 개를 더") (`17`) |

### 32.2 엔진 결함과 수정
1. **DEST03 모래 벽이 처음부터 갈라져 있었다 (영상 2716–2732: 뿔피리 뒤 닫힌 장막 → 분 뒤 갈라짐).** GO 생성 0x1307C8 이 GO +0xF8 = 인스턴스 플래그
   (메모리 기록 +0x26), 타입 5 Animator 등록 0x1AAFE8 (메시지 0, 모드 1) 이 소유 GO +0xF8 을 2 로 덮고, GO 컴포넌트 갱신 0x130F48 은 +0xF8 비트 0 이 없으면
   애니 컴포넌트를 건너뛴다 (0x130F88–0x130F94) [확실]. 엔진은 반복 시작 act 를 모두 스스로 돌려 gosandwedge (sandWedge 12.33 초, sandWedge-Animator1 "!0" 모드 8,
   뿔피리 100 % 3 초 뒤) 가 적재 때 이미 갈라졌고 충돌은 t = 0 이었다. `LevelData.animatorOwners` / `selfAnimates` — LevelScene ambient 와 Platforms 자동 반복이
   이것을 요구. 전 레벨 모드 1 Animator 소유 반복 GO 40 개 (OLYMP01 입구 문 · PAND02I 포세이돈 문 · PAND03F/G 문 · PAND04G 미끄럼 · ATHN08B 벽 · DEST02 컨베이어 …)
   와 플래그 0 인 2 개 (PAND00B goelevatorgrpa, PAND03A gocutcambgrp) 가 스크립트가 틀 때까지 시작 자세. (9f8f70d)
2. **Goto 뒤 싸움 카메라가 남았다 (영상 2950).** Warp_Jump 0x1B2E08 → 0x1EFC98 (0x1B2FD0–0x1B2FDC) → 카메라 리셋 0x1EDF48 (이름 없음): 상태 링 비움, "default",
   전환 0 볼륨 선택, CMV 질의 0x29F138 = 1 (0x1EE20C) [확실]. 엔진은 영웅만 옮겨 d01Mp03 의 Goto CentrePlatform 뒤에도 StormCmbtCam (질의 끔) 이 남았다.
   `CameraRig.reset` 을 두 Goto 경로에 — 데이터의 모든 Goto 는 뒤에 카메라 요청을 부르므로 그대로 적용된다. (9f8f70d)
3. **세이렌 싸움 내내 "R2" 가 떠 있었다.** 0x1DC3F8 은 영웅이 자유 상태가 아니면 사용 질의를 건너뛰고 (0x1DC42C–0x1DC44C) 끝에서 슬롯 0 을 지운다 (0x1DC648)
   [확실]. 엔진은 기술 중에 마지막 질의를 남겨 뿔피리의 R2 가 싸움 내내 보였다. `HeroNavigator.clearPrompt` (HeroRig), 세이브 포인트 안내도 같은 조건. (9f8f70d)
4. **죽어 가는 병사 동영상 · 관문 · 병사가 없었다 (영상 3194–3262).** PAND00E 는 PAND00B 의 LoadSwitch2 LoadWad 로만 올라오는 이웃이라 (LoadCheck 없음) 장작더미
   Mpeg-TriggerVol → FadeOut 0.7 초 → Mpeg-p00MP02play 는 늘 이웃 슬롯 스크립트에서 불린다. 원작 동영상 재생기와 끝 이벤트 501 은 슬롯 구분이 없다 (PostEvent 큐
   0x303DB8 하나) [확실]. 엔진 이웃 리스너가 PlayMovie 를 버려 Mpeg-EvtMpegDone 체크포인트 · 이벤트 200 (PAND00B Gate1-EvtFromMpeg → 관문 → LevelData11 → 키클롭스
   스폰 조건) · 병사 (템플릿 300) 가 모두 빠졌다. `SceneNav.Host.neighbourMovie` → 지금 레벨과 같은 재생기 (501 은 공유 큐로 이웃 센서에). (ad5895a)
5. **크랭크를 돌려도 크라토스가 잡은 자리에 서 있었다 (영상 3476–3482: 기둥 둘레를 돌며 민다).** 붙은 영웅은 매 프레임 잡은 손잡이 관절을 다시 읽는다 (0x1C4258)
   [확실]. godoorcrank 의 handle1/2 는 도는 "Anim" 관절 아래다. `SceneNav.poseItems` 가 자세 뒤 크랭크류 (Push Type 1–4) 손잡이를 다시 읽고, `Interactables.refreshHandles`
   는 같은 배열을 고쳐 써 HeroNavigator 가 잡은 손잡이를 따라간다 [근사: 레버·CSM·TAndF 는 영상 대조가 없어 그대로]. (2436838)
6. **DEST02 돌 상자를 잡을 수도, 밀 수도, 올라설 수도 없었다 — 퍼즐을 풀 수 없음 (영상 2558–2670).** 상자는 Creator PushBlock-BlockOnFloor (Name0 "PushPullBlock",
   슬롯 3 = 211, 4 = 950, 6 = 0, CreateAtStart) 가 만든다. 원작 Creator 0x1ACCA8 → 0x1A5130 이 슬롯 3·4·5·6 을 생성 기록 +0x34/+0x36/+0x38/+0x3A 에 두고, 0x1A5298
   이 새 GO 의 비헤이비어 목록을 만든다 [확실: 디스어셈블]: 슬롯 3 ≥ 0 → **PushPull** (클래스 이름 포인터 0x29D8EC, 템플릿 = 슬롯 3, id = 슬롯 6), 슬롯 4 ≥ 0 →
   **Breakable** (0x29D8F0, 템플릿 = 슬롯 4), 슬롯 5 ≥ 0 → **IO_CSM** (0x29D8F4, 템플릿 = 슬롯 5, 과제 id = 슬롯 6 — 상자), 이름 "goSavePoint" (0x28DF50 비교) →
   **SavePoint** (0x29D8F8, 슬롯 6) → 0x1AD978 이 GO 생성. 원형 GO (레벨 원점 gopushpullblock, 관절 44 = 몸 · 받침 · 파편 30) 에는 BHV 가 없다. Name1 이 없으면 0x1AD110 이
   −1 → 0x1A5298 은 +0x26 &lt; 0 이라 act 를 틀지 않는다 [확실]. 엔진은 Creator 오브젝트를 이펙트 GO 로만 만들어 (충돌·R2 없음) 유일한 act "PushPullBlock" (14.1 초 —
   몸 숨김 2–121 프레임 · 파편 흩어짐 = 부서짐) 을 돌렸다 → 받침이 부서지고 파편이 둘레에 흩어진 채 (d4 녹화) 크라토스가 상자를 통과했다.
   `Interactables.addPush` / `placeCreated` (Push 211 "DesertPush": 반 너비 Radius 1.0 × 16 = 16, 높이 #044ed4a7 2.0 × 16 = 32, 손잡이 관절 없음 → 옆면 잡기
   0x1C3CD8), `SceneNav.createPush` (CDV_PushPullBlock 몸 — `Platforms.add` 가 바인드 관절을 남겨 shift 가 됨), `GameScene.spawnCreated` 가 act 0 초에 세우고
   (`hold`, [근사: 쉬는 자세 = act 0 초 = 바인드]) `updateCreatedBoxes` 가 렌더 행렬 · 그룹 103 스크립트 액터를 밀린 몸에 맞춘다 (TrapDamage-BoxEntry 같은 그룹 판정).
   이음 recreate 는 액터 (밀린) 자리에 다시 만든다 [근사]. 같은 경로로 ATHN01D Crate (PushPullBox, Push 030 "HydraPushBox" 반 너비 12 · Brkb 919) 와 PAND04C
   BlockCreator (Push 098 · Brkb 951) 도 밀기 상자가 된다. (7521ac5)
7. **놓은 상자가 바닥 아래로 17 가라앉았다.** 놓인 높이 (`HeroNavigator.restDy`) 를 관절 0 ±16 의 바닥으로 쟀는데 이 상자는 관절 0 = 몸 가운데 (GO 원점 위 15.9,
   바닥 위 17.2) 라 바닥을 못 찾아 0 이 되었다. 거기 바닥이 없으면 GO 원점 (밑면) 아래로 다시 잰다 [근사: 원작 상자 물리 위치 = GO + 위·6.4, 0x2119A8].
   → 상자 윗면 307 에 올라서 위층 턱 (390) 에 매달린다 (영상 2670). (7521ac5)
8. 하네스 [엔진 전용]: 경로 단계 `pull:초:fx,fz` — (fx, fz) 쪽으로 돌아 R2 를 누른 채 스틱을 영웅 정면 반대로 (상자 끌어내기). 미는 쪽은 `crank:초:fx,fz` 로 같다.

### 32.3 시험
`StartPoseMirrorTest.scriptAnimatedAndFlagZeroGosHoldTheirStartPose` (gosandwedge 플래그 1 · Animator 소유 · 시작 자세, PAND00B goelevatorgrpa 플래그 0, DEST02 gogearanim
반복 · goconveyer 정지, ATHN08A gofellergroup20), `CameraRigTest.gotoResetReenablesVolumeQuery` (DEST03 (3680, 550, 3296) StormCmbtCam 질의 끔 → reset → StrmCam*),
`HeroNavigatorTest.usePromptIsClearedWhileTheNavigatorIsNotQueried`, `PandoraDyingSoldierTest` (PAND00B + PAND00E 연결 세계: 장작더미 → 이웃 PlayMovie p00MP02 →
501 → 템플릿 300 스폰 · Gate1-AnimGate), `NavDataTest.doorCrankHandleFollowsTheTurningCrank` (3 초에 손잡이가 기둥 둘레로 20° 넘게, 같은 배열 · 반지름),
`CreatorPushBlockTest` (DEST02 슬롯 3/4/5/6 = 211/950/−1/0 · 원형 BHV 없음 · DesertPush 16/32 · 관절 0 = GO + 15.9 · 남쪽 면 잡기 · 몸 윗면 = 밑면 + 32 · shift 로 벽감이 빔 ·
턱 높이 계산, ATHN01D Crate HydraPushBox 12), `HeroNavigatorTest.boxWithACentredRootJointRestsOnTheFloorWhenReleased` (관절 0 바닥 위 17.2 → 끌고 놓아도 17.2).
엔진 시험 711 개 통과 (d440b08 위).

### 32.4 남은 것 · 미확인
- ~~타이탄 (aoTITAI1, 템플릿 321 "AOTIT", Patrol1030) 이 멈춰 있다~~ → **고침, §32.6** (엔진 전체 순찰: WYP 웨이포인트 그래프 · InitState · 1025). 타이탄은 이제
  Patrol1030 (남쪽 모래 언덕 x 2522–4792, z 84–1323 의 고리) 을 TIT1d01Mi02 로 기어 Horn2Cam 첫 컷 모래 언덕에 보인다 (`18`). 이 항목의 예전 WYP 해석 (표 위치 0x5A0 =
  간선) 은 틀렸다 — 간선 표는 머리 +0x14 (자료 0x66C), 32 문서 §대기 모드 · 순찰.
- 뿔피리 클로즈업 (2974–2975) 의 옅은 흰 광택이 엔진에서는 더 짙은 금색 [미확인] — aced5e0 (무조명 추가 층 α) 전후 엔진 모습은 같다.
- TalkCam 대화 구도가 영상보다 조금 멀고 병사가 왼쪽 [근사] — 병사 스폰 자리 (2640, −826, 645) · 크라토스 대화 자리 차이로 본다, 미조사.
- 먼지 벽 (DEST03 모래 벽 파티클) 밝기 [미확인] — 데이터는 가산 0x48, 영상이 더 진하다 (`05`).
- 상자 Breakable 950 (Hit Points 10000) · TrapDamage-BoxEntry → EvtArrow 1020 (10000) 으로 상자가 부서지고 BlockCreator 가 다시 만드는 경로는 엔진에 없다 [미확인: 영상에
  부서지는 장면 없음]. 공격으로 부서지는 ATHN01D Crate (Brkb 919, 8) 도 아직 부서지지 않는다.
- 보통 난이도에서 봇은 해골 군단병에 맞아 상자 위 점프가 빗나간다 (길 찾기 b3 는 쉬움) — 봇 한계, 고칠 대상 아님.
- 2800–2930 세이렌 싸움 자체 (봇이 이기지 못함) 와 3330–3460 키클롭스 싸움의 세부 (□ 붙잡기 연출) 는 컷 단위로만 대조.

### 32.5 증거 (`work/verify/horn_cronos/`, 커밋 안 함)
`01_dest03_sandwall_open_before_fix_4s` · `02_…_closed_after_fix_2s` · `03_dest03_blowcam_6s` · `04_dest03_sand_parting_16s` · `05_dest03_dustwall_fx_off_vs_on` ·
`06_siren_fight_stale_R2_before_fix` · `07_…_no_R2_after_fix` · `08_goto_centreplatform_strmcam6_12s` · `09_dest02_block_v2566-2615_vs_engine_pull_push` (영상 2566 · 2570 ·
2600 · 2615 | 엔진 b4 13 · 15 · 17 · 20 초) · `10_dest02_block_climb_v2670_vs_engine_on_block_ledge` (영상 2670 | b3 상자 위 · 턱 매달림) ·
`11a_dest03_horn2_video_v2970-2990` · `11b_dest03_horn2cam_engine_0.5s` · `12_pand00a_save_slot_v3098_vs_engine` · `13_pand00e_talk_cyclops_video_vs_engine` ·
`14_pand00b_crank_orbit_engine` · `15_pand00b_demodoors_video_vs_engine` · `16_dest02_gear_v2650-2660_vs_engine_zoom` · `17_pand00b_feather_eye_cards_v3492-3504_vs_engine` · `18_dest03_titan_patrol_v2970-2972_vs_engine` (영상 2970–2972 | 엔진 Horn2Cam 첫 컷, 타이탄 순찰),
`video/` (영상 프레임 183 장).

### 32.6 적 순찰 — WYP 웨이포인트 그래프 · 대기 모드 · 스크립트 1025 (엔진 전체, 2026-10-25)
§32.4 의 "타이탄이 기지 않는다" 를 쫓다 찾은 엔진 전체 결함: **엔진은 WYP_ 자원을 읽지 않았고 `AiBrain.setPatrol` 을 부르는 곳이 없어 순찰하는 적이 하나도 없었다.**
SpawnEnemy InitState 2 · 4 + "patrolN" 을 가진 적 (전 레벨 22 곳: DEST03 타이탄 · DEST01 세이렌 둘 · PAND00B 해골 무리 · ATHN02A 갑판 해골 · ATHN02C 복도 미노 · ATHN03A/03C
시민 · 갑옷 미노 · 메두사 · ATHN04A 계단 사람 37 · ATHN06B 하피 · ATHN08B 이블 · ARENA12 · PAND00D/01A/01BB/01D/02A/02C/04C/04G) 이 모두 스폰 자리에 서 있었고, 스크립트 1025 (대기 모드 ·
순찰 바꾸기 — DEST01 SwitchSite 등 9 레벨) 는 버려졌다. 원작 경로와 엔진 구현은 32 문서 §대기 모드 · 순찰 (요약):
1. **자원** [확실]: 형 0x14, 머리 = 자료 +4 "WypHeadr", 노드 24 바이트 (xyz · 나가는/들어오는 간선 수 · 첫 칸), 간선 4 바이트 (u16 이웃), 속성 "Radius" — 전 80 레벨 505 개가 이 꼴
   (`WaypointGraph`, `LevelData.waypoints()`). 순찰 경로는 한 방향 고리 (DEST03 Patrol1030 50 노드, 0→1→3→…→48→47→…→2→49→0) 또는 줄 (ATHN02C Patrol14 0→…→4), 자리 그래프는 양방향.
2. **생성** [확실]: 슬롯 9 "patrolN" → 대소문자 무시 + 10 진수 (0x1AC9B8), 슬롯 8 InitState → ai+0x7C → 0x245DB0: 0 서기 · 1 자리 걷기 · 2 순찰 · 4 싸우지 않는 순찰 (ai+0x48 비트 0,
   0x26B190 이 인지 1005 · 경보 1000 을 거른다).
3. **순찰** [확실]: 시작 0x1D17B8 = 가장 가까운 노드 + 그 노드 반경 (Radius × 16) 원 위 무작위 점 (0x1CBB08), 한 걸음 0x1D0348 = 3 차원 거리 < 반경이면 다음 노드 = 나가는 + 들어오는
   간선 가운데 무작위 (흩뿌림 0), 간선이 없으면 0x411 → 서기 · 자리 걷기. 노드에서 멈추지 않음. 스틱 = AI1 0x64 % (#cd7da53a).
4. **1025** [확실]: AI 메시지 0x401 (0x265478 – 0x2654E4) — 대상 놓고 경로 지우고 대기 모드 = 인자 1, 2 · 4 면 순찰 번호 = 인자 2. DEST01 SwitchSiteZone-SwitchSite (2, 1) 은 첫
   세이렌을 Patrol1 로 (사막 에이전트 메모: 영상 ≈1350 SirenCam 5 초 뒤).
5. **엔진**: `AiBrain.startIdle` / `startPatrol` / `wander` / `scriptIdle` / `passive`, `GameScene.spawn` (적을 낸 슬롯 레벨의 그래프), `EnemyFighter` (결정 트리 없는 적도 순찰 중이면
   이동 — 타이탄 AOTIT 321 은 DecisionTree 0 · AI Mode 5), `EnemyFactory` (걷기 = Navigation ANIWalk, 타이탄 "TIT1d01Mi02"), `CombatScriptBridge` 1025, 이웃 슬롯 액터 이벤트
   (`SceneNav.Host.neighbourObjectEvent`). [근사] 모드 1 (자리 그래프 무작위 걷기 — 데이터 SpawnEnemy 대부분) 은 서 있고, 1026 (자리 그래프 바꾸기) · 자리 경로 (0x1CF020 A*) 는 없다.
   엔진 전투는 대기로 돌아오지 않아 "싸운 뒤 가까운 노드로 돌아가기" (0x245DB0 → 0x1D17B8(−1)) 는 쓰이지 않는다. 모드 4 가 맞았을 때 싸우는지 [미확인].

**확인**
- DEST03 타이탄 (t3: `--game DEST03`, LoadEntry 로 스폰 → 210 초 → 세이렌 단 → `post:501:d01Mp03` → 둘째 뿔피리): 스폰 자리 (2452, 61, 1171) 에서 Patrol1030 노드 0 부터 8 유닛/초
  (Sold 1 m/s × 50 % × 16) 로 동쪽 남쪽 줄을 기어 218 초에 (3955, 50, 226) — Horn2Cam 첫 컷에 모래 언덕 위로 기는 거인이 보인다 (영상 2970–2972 오른쪽 위, `18`). 예전엔 스폰 자리에
  "main" 으로 서 있어 이 컷에 없었다. TitanCam 컷에서도 기는 act (TIT1d01Mi02) 로 몸이 흔들린다.
- DEST01 세이렌 (s3: `--game DEST01 --arrive DEST00@2400,507,1282,0`, 시작 동영상 볼륨 → SwitchSiteZone): 24.9 초 1025 (2, 1) → 첫 세이렌 Patrol1 노드 16 (영웅이 곁에 있어 곧 다시
  싸움), kill → 둘째 (patrol3 → Patrol3 노드 11, 27 개) 가 (1677, 2163) → (1142, 2447) 로 돌고, kill → 셋째 (patrol2 → Patrol2 노드 3, 40 개) 가 (3808, 4341) → (2720, 4128) 로 돈다.
  영상 대조 (v1170–v1420 · v2005–v2025 · v2325–v2345) 는 사막 에이전트가 한다.
- 시험 `PatrolTest` 5 (그래프 꼴 · 번호 · ATHN02C 미노 복도 줄 끝까지 · 모드 4 는 보고도 순찰 · 1025 · 타이탄 60 초 480 유닛 · ANIWalk).
- **순회** (봇 60 초 쉬움, 스크래치 홈, WYP 가 있는 80 레벨, 같은 기준 4214de8 전 · 후, 2 줄 동시): 80 레벨 모두 rc 0 · 예외 0. 72 레벨은 방문 · 순간이동 · 사망이 같고 순찰이 없다
  (ATHN02A · 02C · PAND00B 등은 60 초 안에 순찰 적이 스폰되지 않음). 다른 8 레벨:

| 레벨 | 전 방문 · 순간이동 · 사망 · 오브 | 후 | 순찰 시작 | 까닭 |
|---|---|---|---|---|
| ARENA12 | 0/0 · 0 · 0 · 0 | 0/0 · 0 · 0 · 0 | 9 | 해골 · 병사 9 이 모드 4 로 PATROL1/2 를 돈다 — 마지막 자리만 다름 |
| ATHN03C | 0/11 · 0 · 0 · 17 | 0/11 · 0 · 0 · 16 | 6 | 시민 넷 (313, 모드 4, Patrol8/9) 이 걷고 갑옷 미노 둘 (R_MI2 51, 모드 2, Patrol10/12) 이 네 노드 사각형을 돌아 싸움 자리가 옮겨감 → 해골 처치 하나 적음. 시민은 싸움에 들지 않는다 |
| ATHN04A | 11/40 · 1 · 0 · 0 | 같음 | 17 | 계단 사람 (313, 모드 4, Patrol500–507) 이 걷는다 — 마지막 자리만 |
| ATHN06B | 7/35 · 2 · 0 · 26 | 8/35 · 6 · 0 · 8 | 9 | 하피 (70, 모드 4, Patrol710/711) 가 스폰 자리 대신 경로를 돌아 봇이 쫓다 36 초에 포기 (최소 거리 439) → 처치 적음 |
| ATHN08C | 0/0 · 0 · 16 · 0 | 0/0 · 0 · 18 · 0 | 0 | 순찰 없음 — 흔들림: 같은 빌드 다시 돌리면 전 18 · 후 16 (아레스 결투, §29) |
| DEST03 | 7/7 · 4 · 0 · 0 | 같음 | 1 | 타이탄 순찰 (멀리) |
| PAND04C | 1/28 · 3 · 1 · 0 | 같음 | 3 | 줄 하피 (71, 모드 2, Patrol69/431/432) |
| PAND04G | 25/38 · 6 · 0 · 0 | 24/38 · 5 · 0 · 0 | 1 | 50 초에 스폰된 해골 (모드 2, Patrol474) 이 걸어와 봇이 마지막 볼륨 대신 그 적을 노림 |

- 합: 방문 467 → 467, 사망 30 → 32 (ATHN08C 흔들림), 순간이동 313 → 316. 실제 세이브 위치는 생기지 않았다.

## 33. QA vs PS2 영상 2화 — 판도라의 사원 입구 홀 · 해 문 → PAND00C 판도라의 반지 (세이브 · 해골 문 · 크랭크 방 · 뮤즈 석상) → PAND01A 아르테미스의 검 (영상 3520–4050 초, 2026-10-25)

영상 = hoXYXNef60U (2화, 전사 (보통) = `-Dgow.difficulty=1`), 프레임 728×409 (`work/verify/temple_entrance/video/v<초>.jpg` 67 장, 커밋 안 함).
엔진 = origin/main 위 (fbbc209 → d440b08 → 4214de8, 마지막 대조는 aced5e0 · cc7e2fa · 9f69e4c 뒤), 1280×720 lockstep, 스크래치 홈.
레벨 연결 [확실: 스크립트]: PAND00B (문 크랭크 → DemoDoors → 사원 홀 x 560–1472: Dive 군단병 R_SD2 템플릿 38 + 궁수 R_SK2 33, 9 처치 (LevelData17 == 18) →
이벤트 201 · 책 gonewbook (CSM 007, 손잡이 (806.7, 10.5, 1230.9) 축 (1, 0), 잡기 반경 12) 메시지 126 네 쪽) → PAND00C (해 문 EntrySide goentryside (600, 0, 1232):
R2 EvtUseWorld 는 201 이 켠 LevelData21 이 있어야 · 글자 gontrydoorglowletters `$M01..M14sfxLightRayCore` 모드 8 · AnimOpen · 구역 제목 810 "판도라의 반지" (456, 36, 1232) ·
세이브 SaveGameZone (−936, 481, 1720) · 해골 문 goskulldoor (−241, 0, 1877) SkullDoorCam-nurbsSphere2-NoKeyMsg (1024, GlobalData0 거짓 → 메시지 123, SklDrCam) ·
반지 1 크랭크 goring1crank (−316, 0, 1953) · Pand1 문 (CAM_Pand1DrCam)) → PAND00D (뮤즈 석상 문 SirenDoor (−1552, 96, 1232), TrigVol-NoKeys → Msg127) → PAND01A
(MAmagicIcon-EntVol01 (−240, −2, 206), 안쪽 z ≈ 126 → FadeOut 0.7 → 동영상 MagicBZK → 501 → Goto magicGoTo · camera238 · FadeIn 1.3 · TurnFaceOn · 1.5 초 뒤 카드 267
(MAT_FlashMat267 그림) · 메시지 593 "L1 + R1" · Internal:42 = 아이템 7 (아르테미스 보유) · Internal:35 = 36 (MP 가득) · 얼굴 재질 페이드 (hdr8 7.67 초) · +6 초 Idle 풀림 ·
+7 초 체크포인트 → 굴 · 구역 제목 811 "아틀라스의 시험" (−240, 43, −36) · 세이브 (−505, −6, −337) → §34).

### 33.1 장면 대응
| 영상 | 원작 (스크립트·데이터) | 엔진 |
|---|---|---|
| 3518–3598 | 사원 홀: 초록 상자, Dive 군단병 · 궁수 9 처치 (사람 ≈ 78 초), 따뜻하고 맑은 홀 | 고치기 전 모래 폭풍 안개 (gofog) 가 홀을 덮음 → 고친 뒤 맑은 홀 (§33.2-1) · 관 안개 끔 (§33.2-2). 봇 (블레이드 4 + 마법) 은 9 처치 36 초 |
| 3602–3606 | SparkleCUcam 해 문 클로즈업 → 책 클로즈업 | 같은 순서 (h11) |
| 3610–3634 | 메시지 126 네 쪽 ("책의 내용:" 다음 `[*1]` 줄은 주황 기울임) | 고치기 전 흰 보통 글자 → 고친 뒤 주황 기울임 (§33.2-6) |
| 3634–3642 | 해 문 글자가 빛나다 꺼짐 | 고치기 전 빛나지 않음 → 고친 뒤 같은 빛 · 10 초에 꺼짐 (§33.2-3) |
| 3646–3650 | 해 문이 먼지 속에 굴러 열림 | 고치기 전 먼지 없음 → 고친 뒤 이웃 슬롯 Animator 먼지 (§33.2-4). 9f69e4c 뒤에도 같음 (h15) |
| 3655–3662 | 복도 · 구역 제목 810 "판도라의 반지" · 톱니 부조 · 깃발 | 같음 (aced5e0 / cc7e2fa 뒤 톱니 부조 · 깃발 · 횃불 재질 어긋남 없음) |
| 3686–3690 | 세이브 빛기둥 R2 → 메모리 카드 저장 | 같은 R2 · 빛기둥 · 초록 상자. 방 벽이 영상 (회색 돌) 보다 붉음 → §36: 영상은 PAND00D 눈 방 세이브 16 (다른 방) |
| 3700–3760 | 가라앉은 바닥 방 싸움 · 파랑 상자 | 정지 프레임만 — 엔진으로 몰지 않음 |
| 3770–3810 | 크랭크 · 큰 원통 · 톱니 원판 문 · 눈 광선 · 석상 레버 | 눈 분쇄기 방 (구 · 다이아몬드) 만 순간이동으로 확인 (카메라 다름) |
| 3820 | 가시 굴 · 피 | 대조 안 함 |
| 3830 | 해골 문: SklDrCam 클로즈업 · 메시지 123 "문은 잠긴 것 같습니다. / 문에 해골 모양의 빈 공간이 있습니다." | 같은 카메라 · 같은 문구 (skull1) |
| 3840–3958 | 반지 1 크랭크 방: 크랭크 · 사슬 · 벽의 돌 톱니바퀴 · 왼쪽 아치 문 → 불 굴 | 크랭크 · 사슬 (cc7e2fa 뒤 텍스처 있는 사슬) · 톱니 확인. 하네스 crank 로 돌리기는 끝까지 안 됨 · 카메라 다름 [미확인] |
| 3870 | PAND00D 뮤즈 석상 문: 메시지 127 세 줄 | 같은 문구 · 비슷한 클로즈업 (muse1) |
| 3962 | 불 굴로 · 빛 구슬 | 같음 |
| 3966–3994 | 동영상 MagicBZK (밝은 분홍 얼굴 대화 · 검을 건넴) | `--skip-movies` |
| 3998 | 카드 267 "[*1]아르테미스의 검 / 을 획득했습니다." | 고치기 전 흰 글자 → 고친 뒤 첫 줄 주황 기울임 |
| 4001–4010 | 메시지 593 · 불 속 얼굴 → 얼굴이 사라지며 굴이 열림 | 고치기 전 얼굴이 안 보임 (뺄셈 파트가 덮음) → 고친 뒤 같은 어두운 붉은 얼굴 · 페이드 (§33.2-5) |
| 4014 | L1 + R1: 검을 뽑을 때 손목 옆 보라 빛 | 고치기 전 없음 → goMAIBSActive 섬광 (§33.2-7) |
| 4018 | 휘두를 때 칼날의 파랑-보라 빛과 보라 자국 | 고치기 전 맨 은빛 검 → 같은 보라 빛 · 자국 (ar12 f52 · f74, §33.2-7) |
| 4022–4030 | 업그레이드 메뉴: "봉인된 마법" → "아르테미스의 검 레벨 1 / 다음 업그레이드: 검에 파워 증가 / 파워 업 하려면 3750 개 더 필요." (오브 911) | 같은 화면 · 같은 문구 · 3750 · 911 (블레이드에서 ↑ 두 번, menu2) |
| 4034–4050 | 굴 → 구역 제목 811 "아틀라스의 시험" · 세이브 빛기둥 R2 → 저장 완료 | 같은 제목 · R2 (save1). 빛기둥이 영상은 넓은 세로 기둥, 엔진은 가는 선 + 비스듬한 원뿔 [미확인: 카메라] |

### 33.2 엔진 결함과 수정
1. **홀이 모래 폭풍 안개에 덮였다 (영상 3518–3598: 맑은 홀).** 안개 설정 0x125970 이 안개 관리자 +0x84 에 프레임 번호 0x3305D8 을 찍고, 렌더 0x1281C8 은 그 값이
   이번 프레임이 아니면 안개를 그리지 않는다 (0x128210–0x128250, 뷰포트 +0x3A8 = 524287.0) [확실]. SCR_Fog 0x20D428 · SCR_LayeredFog 0x20D6A8 둘 다 0x125970 을 부른다.
   엔진은 이전 안개를 남겨 PAND00B 모래 길 LayeredFog (gofog, 홀 칸 1/3 에서 꺼짐) 가 홀을 덮었다. `FxEnvironment.selectFog` 는 이번 프레임 걸린 안개가 없으면 안개 없음.
   PAND00B 크랭크 (1508, 60, 1233) 는 안개, 홀 (800, 80, 1232) 은 없음. (fbbc209)
2. **(1 뒤) 홀이 파랗게 물들었다 — PAND00C 관 안개.** Vis 효과기 0x1ABAA8 의 슬롯 16 (+0xF8, 엔진 Attr17) 은 0x1B1398 로 부품 타입 0x17 · 0x4 (0x1ABD24–0x1ABD4C) —
   Servers_Create 0x17D218 의 BhvrServer · ScriptServer — 에 걸리고, SCR_Fog 처리 0x2467A8 은 부품 +0x4 비트 4 가 없으면 안개를 고치지 않는다 [확실]. PAND00C 생성
   스크립트 fogTubeL3-FogOff 가 CXT_default 파란 관 안개를 끈다. `ScriptWorld.scriptsOff` (Vis 슬롯 16 = 2) → `FxEnvironment` / `LevelFx.setScriptsOff` (안개 고르기 ·
   SCR_Weather 플랑크톤), `Interactables.behaviourOff` (ATHN01A trapDoorCSM, ATHN02B HarpoonGun, PAND04D 크랭크, PAND04E 레버 — 사용 불가). (fbbc209)
   9f69e4c 가 같은 Vis 경로의 슬롯 1 (EffectsServer 0x1E) 을 더했다 — 둘이 함께 산다.
3. **해 문 글자가 빛나지 않았다 (영상 3634–3642).** 머티리얼 ANM 타입 3 트랙 (16 §3.1): p1 = L+1 의 스트림 0–3 = 레이어 L rgba, p1 = 0 의 0–2 = 헤더 색 — 엔진은 hdr8 (p28)
   만 썼다. 글자 MAT_M01–M14sfxLightRayCore 는 레이어 rgba 0 (보이지 않는 가산) 에 0x42 (스크립트 1회) 트랙 1 → 0 (키 280 부터, 9.33–10 초). `MaterialFx.tint` /
   `litColor` / `colorTint` (Renderer · AnimatedModel). 아직 아무 스크립트도 틀지 않은 0x42 재질 시계는 NaN (`MaterialFx.times`) → 색 트랙 없이 기본값 (영상 3610–3626 은
   R2 전 어두운 글자) [근사: 재질 컨트롤러 코드는 따라가지 않음]. (fbbc209)
4. **해 문이 먼지 없이 열렸다 (영상 3646).** PAND00C EntrySide-AnimOpen 은 PAND00B 홀에서 이웃 슬롯 스크립트로 돈다. `GameScene.startAnimatorEffects` 가 이웃 슬롯 GO 의
   act 채널 이미터도 켠다 (goentryside act EntrySide, 먼지 이미터 둘 — 이웃 WAD 를 이펙트 표에 더함). (fbbc209)
5. **불 속 아르테미스 얼굴이 보이지 않았다 (영상 4001–4010).** 키 배정 0x155C40 → 0x155B50 (a3 비트 머티리얼은 구간 위 = 나중, t0 비트는 아래 = 먼저) 이 층마다 반투명
   단계를 (0x0A000400 / 나머지, 1) → (0x0A000000 / 0x400, 2) → (0x08000000 가산 / 0x02000000 뺄셈, 3 · 4) 로 나눠 **알파 &lt; 뺄셈 &lt; 가산** 순서로 그린다 [확실].
   PAND01A gomatalkinghead (MDL_MAtalkingHead) 는 SubScrollTex2/3 · darkenMatFade (GS 0x42) 로 굴을 어둡게 하고 얼굴 M01ArtemisFront01 · 불 M01DoorFade2 (0x48) 를 그 위에
   그린다. 엔진은 파트 순서 (얼굴 → 뺄셈) 로 그려 얼굴이 지워졌다. `RenderModel.translucentRank` (0 알파·가장자리, 1 뺄셈, 2 가산) — `Renderer.drawPasses` · `AnimatedModel`
   이 패스 1 을 세 번. (d440b08) 영상 3966–3994 의 밝은 분홍 얼굴은 동영상 MagicBZK 이고, 4001–4010 실시간 얼굴은 엔진과 같은 어두운 붉은 색이다.
6. **`[*1]` 글자가 보통 흰 글자였다 (영상 3610–3634 책 · 3998 카드).** 텍스트 배치 0x1494A8 이 '[' 다음 '*' 면 그 다음 글자 − '0' 을 서체 번호로 (']' 면 0,
   0x149894–0x1498D8) 글자 레코드 +0x6 에, 그리기 0x148388 이 서체 1 = 고정 색 (0xDC, 0x6E, 0x00) (0x29CCDC), 2 = (0x00, 0xDC, 0xDC) (0x29CCE4) 를 (바이트 + 1) × 부모 색
   변환 ≫ 8 로, 둘 다 기울임 0.2 (0x29CCE0 / 0x29CCE8 를 부호 바꿔 행렬에) [확실]. 엔진은 "인자 자리" 로 보고 버렸다 (07 문서 정정). `FlpRenderer.tokenize` 의 `Style`,
   FLP 글리프 행렬 · `TtfFont.setShear`, 줄마다 0 에서. (d440b08)
7. **아르테미스의 검이 맨 은빛이었다 (영상 4014–4018: 파랑-보라 빛 · 휘두른 자국 · 뽑을 때 섬광).**
   - 장착 0x19C240 이 /Player/BerserkBlade "Stage n GO Name" = goBerserkBlade (WAD_R_Brsrk) 를 만들어 RWEAPIH 에 붙이고 종류 &lt; 0xB 부품 (이미터) 의 생성률 배율 +0xB4 를 1
     로 (0x19C3E8–0x19C414) [확실]. 이미터 FXC_Bglowemit1 (700/초) · FXC_Bfireemit1 (1000/초) 은 **표면 이미터 (서브타입 3)** — 칼날 모양 MSH_berzergeomShape1 (노드 로컬
     배율 (4, 7.4, 5.6) × 관절 0 배율 0.25) 위에서, 색 (25, 29, 78) 가산, 속력 0. PTC_Bglowpart1 은 플래그 0x30 (시스템 행렬 = 검을 따라감, 0x15BE18 [확실]) 이라 칼날의 빛,
     PTC_Bfirepart1 (0x681, 0x30 없음) 은 월드에 남아 휘두른 자리에 보라 자국.
   - BERSERKENTER (555) 의 [0, 1] SCRIPT `SCR_BerserkBladeFX` (0x5968598D, Items_RegisterScr 0x19CF28) → 0x19C798 이 영웅 RWRIST 관절 월드로 goMAIBSActive 를 만들어 그 관절에
     (0x2283A8) — maibsactive (관절 배율 1/32) act 0.93 초가 0.2 초 무렵 표면 이미터 BBglowemit (BBEFXgeomShape, 313/초, PTC_BBglowpart (83, 75, 204) 가산, 반폭 24 − 200t) 와
     불티 둘을 잠깐 = 뽑을 때 섬광 [확실: 이름·관절 / 추정: 부착].
   - **표면 생성 0x1252E0** [확실]: 난수 × 65535 (0x125344) 보다 누적 면적 (MSH 삼각형 +6, 04 §6) 이 작은 동안 다음 삼각형 → r1, r2 (합 &gt; 1 이면 1 − r) 무게중심 점 ·
     보간 법선 정규화 → b = N · 법선 속력 + normalize((난수 − ½)³ × N) · 접선 속력 → 위치 += b̂ · 생성 거리 · 16 → 속도 = b · (속력 ± 랜덤) · 16 → 노드 월드 (0x12EDA8 이
     0x29CA68+0xC0 에 둔 노드 로컬 · 소유 GO 관절) 로 (16 §2.6). 엔진은 서브타입 3 을 이미터 원점 한 점에서 냈다 (레벨 배치는 아예 건너뜀).
   - 엔진: `MshMesh.cumArea`, `EffectLibrary.Surface` (EmitterDef.surface), `EffectSystem` 이 매 프레임 노드 월드 (이펙트 월드 · 관절 (바인드 / act 자세) · 노드 로컬) ·
     `ParticleSystem.surfacePoint` / `spawnOnSurface`, `MagicFx.artemisBlade` (장착 중 goBerserkBlade 를 rWeapIH 에, 메시는 ArtemisBladeModel) · `berserkBladeFx`,
     `GameScene.setGodBlades` 가 단계 (0–2) 를 넘김. 같은 경로로 혼돈의 블레이드 불꽃 BDEsparkemit (BDepoly6/3Shape, 궤적 중) 도 블레이드 표면에서 나온다. (4214de8)
8. 하네스 [엔진 전용]: 경로 단계 `crank:초:fx,fz` (R2 를 누른 채 스틱을 영웅 정면으로 — 둘레를 도는 Type 1 크랭크), `keys:초:B1+B2` (L1 + R1), `-Dgow.blades=N`,
   `-Dgow.debug.task` (1023 기록), `-Dgow.debug.mat` (재질 애니 시작). 업그레이드 메뉴는 `-Dgow.script=SELECT:…,UP:…` 로 (카드는 같은 스크립트의 ○ 로 닫는다).

### 33.3 시험
`SceneFogTest.noFogWhenNoFogObjectAppliedThisFrame` (0x125A24 / 0x125A2C · 0x20D428 / 0x20D6A8 → 0x125970 · 0x128214–0x12824C, PAND00B 크랭크 안개 / 홀 없음),
`TempleEntranceTest` (visSlot16TogglesBehaviourAndScriptComponents, pand00cTubeFogIsOffAfterCreation, sunDoorLettersGlowFromTheLayerColourTrack,
artemisFaceSortsSubtractiveBeforeAdditive), `ScriptMaterialAnimTest` (틀지 않은 0x42 = NaN), `FlpTextStyleTest` (4: 0x149894–0x1498D8 · 0x29CCDC–0x29CCE8, 토큰, 색,
책 126 · 카드 267), `SurfaceEmitterTest` (4: 0x1252E0 삼각형 · 접기 · 보폭, MSH_berzergeomShape1 누적 면적 = 실제 면적 비례, surfacePoint, goBerserkBlade · goMAIBSActive
표면 · PTC 0x30, 0x19C3F0–0x19C408 · SCR_BerserkBladeFX → 0x19C798 · BERSERKENTER 이벤트). 엔진 시험 718 개 통과 (4214de8).

### 33.4 남은 것 · 미확인
- 반지 (3700–3958) 의 싸움 · 크랭크 · 톱니 원판 문 · 눈 광선 · 석상 레버 · 가시 굴은 엔진으로 몰지 않았다 (순간이동 확인은 카메라가 달라 근사 대조). 3840–3958 크랭크를
  하네스로 끝까지 돌려 아치 문이 열리는 것은 확인 못 함 [미확인].
- 세이브 방 (3686) 벽이 영상 회색 돌보다 붉다, PAND01A 세이브 빛기둥 (4040) 모양 — 카메라가 달라 판단 보류 [미확인].
- 아르테미스의 검 숨김 · 해제 (0x1DB788, 영웅 갱신 0x1D6CF0 이 매 프레임) [확실: 코드] → **§37 에서 구현**: 장착 (+0xC & 0x20) 이면 GO 가 없을 때 만들고, 영웅
  +0x174 가 (0x400 & 0x20000 = 수평 줄) 또는 (0x20 이고 0x400000 | 0x300000 이 아님 = 오르는 벽 — 여기 처음 적은 "(0x20 & (0x400000 | 0x300000))" 는 반대였다) 이면
  **해제** (0x19C240(0)), SCR_HideBerserkBlade (0x8CACF07D, 0x19C6C0 → 0x29D814 = 1, 끝 0x19C608 이 0) 또는 +0x174 & (0x4000 | 0x400 | 0x10 | 0x380 | 0x80000 | 0x20)
  또는 +0x2D8 & 0x800000 이면 검 GO 를 숨기고 블레이드를 보인다 (0x201908), 아니면 다시 보인다.
- 표면 이미터 세부 [근사]: 원작은 한 프레임에 낸 파티클의 출생 시각을 1/60 ÷ 개수씩 엇갈리고 이미터 +0x80 / +0x90 을 더한다 — 엔진은 틱 단위. 레벨 배치 표면 이미터
  (`LevelEmitters`, ATHN 46 개 등) 는 아직 건너뛴다.
- ~~영상 3545 · 3598 의 넓은 붉은 블레이드 호가 엔진 (표면 불꽃 + 궤적 부채꼴) 보다 넓다 [미확인: 45 §3.4 궤적 쪽].~~ → §35: 휘두르기 궤적 띠 (0x12DE70) —
  블레이드 단계 4 (R_WPN0_3) 의 TXR_swordtrail 은 붉은 불꽃이라 호가 붉다 (PAND00B `-Dgow.blades=3` 엔진 녹화 같은 붉은 호, `work/verify/blade_trail/08_…`).
- 봇 홀 싸움 36 초 vs 영상 ≈ 78 초 (사람) — 결함 아님. 이 구간 정지 프레임에서 길을 따라 걷는 순찰 적은 보지 못했다 (WYP 순찰은 다른 작업).

### 33.5 증거 (`work/verify/temple_entrance/`, 커밋 안 함)
`engine_hall_book_sundoor.jpg` (h11: 홀 → 책 → 메시지 126 → 해 문 → 구역 제목) · `engine_sundoor_letters_dust.jpg` (h14: 글자 빛 → 꺼짐 → 먼지 속에 굴러 열림) ·
`engine_artemis_face_msg593_menu.jpg` (ar7: 불 속 얼굴 · 메시지 593 · 업그레이드 메뉴) · `engine_artemis_equip.jpg` (ar10: L1 + R1 → 검) ·
`artemis_glow_v4014-4018_vs_engine.jpg` (ar12 f52 · f74: 보라 빛 · 자국) · `menu_v4026-4030_vs_engine.jpg` (봉인된 마법 · 아르테미스의 검 3750) ·
`muse_skull_v3870_v3830_vs_engine.jpg` (메시지 127 · 123 클로즈업), `video/` (영상 프레임 67 장).

## 34. QA vs PS2 영상 2화 — 아틀라스의 시험: PAND01A 구멍 방 → 위층 · 방패 · 수레 → PAND01D 줄 싸움 · 하데스의 망령 → 세이브 (영상 4050–5189 초, 2026-10-25)

영상 = hoXYXNef60U (2화, 전사 (보통) = `-Dgow.difficulty=1`). 레벨 연결 [확실: 스크립트]: PAND01A (구역 11 "아틀라스의 시험" — ZoneReportGroup (−240, −2, −36),
아르테미스의 검 방 MAtalkingHead (−240, 10, 126) 다음) ─ 가운데 구멍 방 (네 구역 Section1..4, 해골 HoleSkelly · 고르곤 HoleMedusa 스포너) · 위층 들보 (BBeamZone,
갑옷 해골 R_SK4 · 궁수 setTimedArchers) · 남쪽 수레 (PushPullBlock) → 누름판 PressPlate (−904, 8, −1532) → 문 ScoobyDoor · 위층 방패 AlcoveShield (−1016, 148, −1728)
메시지 259 · 방패 문 shieldoor (−1009, 4, −1345) ─ 구멍 바닥 LoadCheckD (−505, −191, −914) ─ PAND01D (줄 해골 RopeSkelly · 동굴 방패 CaveShield 메시지 260 ·
HoleLoad → PAND01A). 엔진 = origin/main 4fe5fa2 위에서 시작, 마지막 확인은 4214de8 위 (fbbc209 · aced5e0 · cc7e2fa · 9f69e4c · 4214de8 뒤 재확인 §34.3).
검증 = 경로 하네스 (`-Dgow.auto.route`, 순간이동 · `use:` · `hold:` · `jump:` · `kill:33`) + 봇, 1280×720 lockstep 녹화를 영상 프레임 (728×409) 과 나란히.

### 34.1 엔진 결함과 수정
1. **아르테미스의 검 공격이 칼날이 아니라 영웅 앞 구로 맞았다 (영상 4062–4160 · 4245–4265 · 4980–5060, 검으로 싸우는 구간 전부).** BER* 공격 레코드는 부위 10
   (예 BERCOMBOH1 [0.29–0.35] 피해 5, fl 02) 인데 엔진 영웅 히트박스엔 부위 10 구가 없어 정면 근사 (앞 55, 반지름 55 — 영웅 앞 110 까지, 옆 · 뒤는 못 맞힘) 로 판정했다.
   원작: 검 GO `berserkblade` 의 `CDV_berserkBlade` (R_BRSRK0..2 같은 값, 구 2 개 r 35.52 · 관절 로컬 (0, 8.73, −88.5) · (0, 0, −34.7), 재질 berserkColl PartType 10)
   를 장착 0x19C240 이 히어로 관절 **RWEAPIH** 에 붙인다 (0x19C388 Hash127Upper → 0x138360 관절 찾기 → 0x131460 부착, GO +0xF4 |= 0x200) [확실]. 검 관절 idle 배율 0.25
   가 구에도 걸려 (0x13AAA8) 월드 r 8.88, 손잡이에서 칼날 따라 −8.7 · −22.1 — 메시 (3.8 … −30.4) 와 같은 범위 [확실: 데이터]. → `HitVolumes.forHero(…, brsrk)`
   (부위 10 구를 rWeapIH 에), `WeaponAttach.berserkBlade`, HeroRig 가 R_BRSRK0 을 읽는다. □□□□ (H1 → H2 → H1 → H4) 의 해골 (R_SKS) 적중: 앞 40 · 60 = 3 (예전 3),
   앞 75 · 100 = 0 (예전 3), 옆 40 = 2–3 · 뒤 40 = 3 (예전 0) (`ArtemisHitVolumeTest`). 같은 까닭으로 검 렌더도 rWeapOH (블레이드 사슬 로케이터 — BER* 자세에서 손과
   5–15 · 축 40° 넘게 어긋남) → rWeapIH (`ArtemisBladeModel`): 칼을 손에 쥐고 휘두른다 (영상 4062 · 4200 의 칼 방향).
2. 하네스 [엔진 전용]: `-Dgow.auto.artemis` — 봇이 검을 보유했는데 들지 않았으면 싸울 때 L1 홀드 + R1 (BERSERKENTER 555) 로 든다 (`BotInput.equipArtemis`).
3. **세이브 슬롯 이름이 "아틀라스의 시험" 이 아니라 난이도였다 (영상 5182: 그림 · "아틀라스의 시험" · "2023/1/ 9  6:42:53 am" · "전사(보통)    3:10:43").**
   원작 Save_SlotText 0x22E810 [확실]: 파일마다 MemoryCard_Name (HUD +0xE0) = **메시지 600 + 세이브 포인트 번호** 첫 줄 (번호 ≤ 0 "* Not A Save Point *", 메시지 없음
   "Save Point %d", 빈 파일 메시지 2), MemoryCard_Date (+0xF0) = 0x22E4A8 언어 5 (0x1BF41C 고정) 형식 "%d/%d/%2d %2d:%02d:%02d%s" (12 시간제 " am"/" pm"),
   MemoryCard_PlayTime (+0x100) = "%s    %d:%02d:%02d" (난이도 이름 0x29DB68 = 메시지 3..6 + 시간). 번호는 슬롯 디렉터리 u16 +0xC (0x2312B0 ← 0x2A2704 ← R2 때
   0x1DC3F8 → 0x22D390(영웅 +0x8C)) — 세이브 포인트 GO 가 매 프레임 0x2671C8 → 0x1DC678 로 가장 가까운 번호를 영웅에 적고, 그 번호는 Creator "savepoint" 의
   **슬롯 6** (0x1ACCA8 → 0x1A5130 → SavePoint 비헤이비어 +8, 0x22E2A8/0x22E250) 이다: ATHN06A 10 "오라클의 사원" · ATHN07 12 · PAND00A 15 "판도라의 다리" ·
   PAND00D 16 · **PAND01A 17 "아틀라스의 시험"** (−505, −6, −337 — 영상 4040 의 빛기둥 · 5180 의 저장) · PAND01B 18 · PAND01C 19 · PAND00C 20. 슬롯 그림 = 0x1B81C0:
   번호 &gt; 0 이면 R_SAVEPT `TXR_SavePt%02d`, 아니면 MemoryCard_Image1 원래 그림 (HUD FLP MemoryCard_Image1..4 = TXR_HUDK020..023, 0x1B8178) — R_SAVEPT 는
   게임 중에만 올라 셸 불러오기 화면은 기본 그림. 예전 엔진은 이름에 난이도, 시간 줄에 시간만, 날짜 "yyyy/MM/dd HH:mm", 그림 없음이었다 →
   `GameSession.savePoint` (R2 때 가장 가까운 세이브 포인트 번호) · `SaveGame.savePoint` (파일 속성) · `MemoryCardFlow.slotName / slotDate / thumbnails` ·
   `GameScene.saveThumbnails` (`01_save_slot_atlas_s2` ↔ `02_video_5182_save_slot`, 같은 그림·이름·형식).
4. **PAND01D 수평 줄이 V 자로 처졌다 (영상 4865–4940 · 5115: 팽팽한 곧은 줄 위에 크레토스와 갑옷 해골이 매달려 싸움).** gorope1 (TRAVERSE, 103 관절,
   매단 점 (−472, −357, −976) → 끝점 (119.6, −357.4, −987.8) 592 유닛) 에 매달리면 엔진은 가운데가 140 넘게 내려가 영웅이 y −500 에 매달렸고, 줄 위 구역
   FirstRopeZone · SecondRopeZone · ThirdRopeZone · RopeZone6 (y −384 … −398) 에 들지 않아 줄 해골 RopeSkelly1..6 이 나오지 않았다. 원작 0x216FC8 [확실]: +0x58 ·
   +0x5C 를 SCR 줄 길이 (+0x384 +4 = 20 m) / (n − 1) 로 채운 뒤 양끝 고정이면 +0x5C (노드 간격) · +0x60 (길이) 만 끝점 거리로 다시 쓴다 — 단계 0x217478 의 이웃 ·
   굽힘 스프링 쉼 길이는 +0x58 (2 배), 줄 위 점 · 무게 · 힘 나눔 (0x2184E8 / 0x2177BC / 0x217B5C) 은 +0x5C. 출시 데이터의 양끝 고정 줄 8 개 모두 SCR 길이 &lt;
   끝점 거리 (비율 0.44–0.86: ATHN01D/01E 짚라인 · ATHN02B gorope8 · PAND01D gorope1 320/592 · PAND03D · PAND03E · PAND04C · PAND04E) → 미리 당겨진 팽팽한 줄.
   예전 엔진은 쉼 길이 = 노드 간격이라 장력이 없었다 → `RopeSim.fixed(…, restLength, …)` · `RopeSim.of(NavRope)` (`06_rope_sag_before_d2` ↔ `05_rope_taut_after_d3`:
   곧은 줄, 영웅 y −395 … −402, 줄 구역 넷 · RopeSpawner 발동, 해골 9 마리가 줄 위 (y −344 / −352) 에 나온다).
5. **수평 줄에서 크레토스가 싸우지 못했다 (영상 4865–4940: 매달린 채 발차기 · "6 Hits", 해골이 크레토스에 매달리면 떨쳐 냄).** 엔진 HeroRig 는 내비 상태
   (매달림 · 줄 · 밀기) 에서 Combatant.update 를 통째로 건너뛰어 ("원작 줄 공격 기술은 미구현") □ 가 아무것도 안 했고, 피격 구도 줄을 잡기 전 바닥 자리에 남아 해골의
   ROPEHOHA (피해 5) · ROPELEAP 이 한 번도 맞지 않았다 (d4: 90 초 동안 해골 공격 50 번 · 적중 0). 원작 Combat_StateMask 0x207DC0 [확실]: +0x174 0x400 (줄) 과
   0x20000 (수평 줄) 이면 분기 마스크 0x80 — 영웅 분기 A51 □ ROPEHOHA (발 부위 7 피해 12) · A68 △ ROPEPOWER01 (칼 부위 3 피해 20, 자세 태그 79 → 해골 A157
   ROPEDECAP) · A202 ○ ROPEHOHGRAB (HIT_CONFIRM → MAISKB32, 해골 A226) · A135 피격 ROPEHOHR1 · A323 해골 ROPELEAP 에 잡힘 MAISKB30 (자동으로 떨쳐 냄) 이
   모두 0x1082c0 / 0x1080c0 (줄 상태) 이다. 넉백은 지상 · 공중만 (0x2044FC) 이라 줄에서는 없다. → `CombatOwner.stateMask` (ROPE_TRAVERSE = 0x80), 수평 줄에서도
   Combatant.update, 기술 중에는 `HeroNavigator.holdRope` (손은 줄 위 자리, 기술 첫 틱에 대상 쪽 줄 방향 [근사]). 엔진만의 "○ = 줄 놓기" 는 지웠다 (원작 ○ =
   ROPEHOHGRAB, 놓기는 × 0.5 초 뒤). d5 (보통, 봇): ROPEHOHA 적중 12 · 해골 ROPEHOHR · 해골 ROPEHOHA 피해 5 → ROPEHOHR1 · ROPELEAP → 둘 다 maiSkb30 → 해골
   death02 로 떨어짐 (`07_rope_fight_d5`). 시험 `RopeCombatTest` · `HeroNavigatorTest.traverseRopeFightHoldsHandsOnRopeAndFacesTarget`.
6. **레버로 내려온 rope6 을 잡을 수 없었다 (영상 4845–4852: 레버를 당기면 줄이 구멍 바닥까지 내려오고 크레토스가 그 줄을 타고 PAND01D 로 내려감).** 레버
   goleverrope (Push 205 Type 5, 손잡이 (−374, 168, −1007) 축 +x, R2 한 번) → 1023 (id 40) → leverRope 센서가 LevelData 8 = 100 → RopeAniCam-CamOn
   (Vis 4 → CMV RopeLockCam1 우선 15 · 애니 카메라, Idle 10 초) · TriggeredBlockers 끔 · rope6-AnimContinue — ANM_rope6 (9.67 초) 이 줄 GO 전체를 656 내린다
   (뿌리 y 933 → 277, 끝 256 → −400). 원작 줄 갱신 0x217A40 [확실]: 매 프레임 줄 뿌리 관절 (+0x48) 행렬의 이동 × 1/16 을 매단 점 (+0x30) 과 비교해 바뀌면
   쓰고 깨운다. 엔진은 `NavRope` 선분 · `RopeSim` 매단 점을 배치 자리에 고정해 렌더만 애니로 내려오고, 잡기 선분은 위층 바닥 (146) 위 110 에 남아
   위층에서 뛰어도 줄을 지나쳐 구멍으로 떨어져 죽었다 (l11). → `GameScene.followRopeAnchors` (매단 줄 소유 발판 몸 관절 0 → `NavRope.moveTop` ·
   `RopeSim.moveAnchor`), 애니로 옮겨지는 줄은 뿌리 = 애니 · 사슬 = 시뮬레이션으로 그림 (38 §17.1). l13: 18.6 초 위층 (−470, 146, −1008) 에서 뛰어 잡음
   (y 177) → 아래로 미끄러져 구멍 방 다리 옆 (영상 4852 와 같은 자리) → 23.0 초 LoadCheckD → 이음 PAND01A → PAND01D → 24.75 초 줄 아래 끝
   (`10_rope6_descent_l13`). 시험 `RopeAnchorFollowTest` (2).
7. **PAND01D 에서 PAND01A rope6 을 잡고 올라갈 수 없었다 (영상 5135–5145: 수평 줄 기둥 옆 세로 줄을 타고 위로).** 원작 줄 잡기 0x1C5C20 → 0x1C5CD8 =
   충돌 세계 0x29C978 반경 32 구 질의 (콜백 0x1C5AF0) — 두 슬롯 구분이 없다 [확실]. 엔진은 현재 레벨 줄만 잡고, 이음 때 새 현재 레벨 줄을 데이터에서 다시
   만들었다. → `NavWorld.neighbourRopes` (`SceneNav.setOther`), `nearestRope` 가 두 목록, `swapSlots` 는 목록 자리만 바꿈 (옮긴 매단 점 · 시뮬레이션이
   이어짐), 이웃 줄 매단 점도 이웃 발판으로 따름 (38 §23.2). l16 (현재 = PAND01D): 받침 (−506, −416, −994) 에서 × → 이웃 rope6 잡음 → 9 초에 y −212
   까지 올라감, 수평 줄 기둥 · 횃불이 영상 5135 와 같은 구도 (`11_rope6_climb_back_l16`).
8. **싸운 뒤 R2 로 레버를 잡지 못했다 (영상 4300 궁수 싸움 → 4845 레버).** 싸움이 끝나면 영웅은 전투 대기 IDLECOMBAT (M85 → M80 반복, ANY_INPUT →
   기술 끝) 에 서 있다. 원작 ANY_INPUT = 폴링 0x2B9290[12] = 0x207FE0: 새로 눌린 패드 비트 `& 0xFF` (L2 R2 L1 R1 △ ○ × □) 또는 스틱 &gt; 0.5 [확실]. 엔진은
   분기 코드 1–8 (× □ △ ○ L1 L2 L3 R1) 을 돌아 R2 가 빠지고 L3 가 들어 있어 R2 가 M80 을 끝내지 못했고, 내비게이션 (HeroRig 는 기술 중엔 nav 를 안 돌림)
   이 R2 를 받지 못했다 (l10: 52–64 초 idleCombat 만 반복). → `CombatInput.anyNewButton` 을 패드 비트 0–7 로 (26 문서). l14: 싸운 뒤 57.75 초 R2 → INTERACT
   → 61.65 초 RopeAniCam. 시험 `IdleCombatInputTest`.

### 34.2 경로와 장면 대조 (영상 ↔ 엔진 실행)
엔진 시각은 하네스 실행 안의 초 (장면마다 순간이동으로 시작). "같음" = 구도 · 색 · 장치 동작이 영상 프레임과 맞음.

| 영상 (초) | 장면 | 레벨 · 장치 | 엔진 실행 (초) | 결과 |
|---|---|---|---|---|
| 4050–4160 | 구멍 방, 아르테미스의 검 첫 싸움 (해골 · 고르곤 · 32 Hits) | PAND01A Section1..4, HoleSkelly · HoleMedusa | a2 (봇), b2/b3 (□□□□ △△ ○) | 칼 히트 구 고침 (34.1-1), 보라 궤적 b3 2.4–6.9 ↔ v4062/4070 (`14_boa_trail_b3_vs_4062`) |
| 4205–4285 | 구멍 방 아래 · 위층 길 | PAND01A | a2 | 같음 |
| 4300 | 레버 방 궁수 싸움 | ArcherRow (R_SK2 ×4, goarcherrow (−352, 0, −960) + 로컬 ÷8 → x −352, z −1043 … −873, 위층 바닥 146) | l3 · l9 (봇이 15 초에 넷 처치) | 궁수 자리 = 데이터. 4845 에서 방이 빈 것은 4300 에 죽인 것 |
| 4310 | 매달린 줄 끝 추 | rope6 배치 (끝 y 256) | l11 16.7 | 같음 |
| 4350–4400 | 고르곤 복도 | PressSpawner (−483, 4, −1346): PressArchers ×2 · Medusa 26/27 | c5 7.7 (들어갈 때 스폰) | 같음 (영상도 수레 전에 싸움) |
| 4415–4505 | 수레를 밀어 누름판에 → 문 | gopushpullblock (Push 099, 반 너비 16) → PressPlate (−904, 8, −1532) → ScoobyDoor | c5: 0.65–6.25 남 · 7.67–22.78 서 · 24.2–30.0 북 (상자 (−904.5, 10.6, −1538)), 28.87 CubeEntry → AnimDown → 29.67 ScoobyDoor-AnimForward | 같음 (`12_cart_to_plate_c5`) |
| 4640 | 황금 방패 질문 | AlcoveShield (−1016, 148, −1728) 질문 1008 → zeusSheildOFF · AnimShrink → 카드 "제우스의 방패" | v1 · v4 2.8–5.8 | 문구 · 카드 같음, 방패 빛 다름 (§34.4) |
| 4710–4716 | 사자 부조 방패 홈 | shieldoor (−1009, 4, −1345) | v1 · v4 9.5–11.8 | 같음 (부조 색 · 오른쪽 홈 방패) |
| 4720–4730 | 문 방 · 복도 | ScoobyDoor 방 | c5 28–34 | 같음 |
| 4845 | 레버 방 | goleverrope | l11: 0.95 R2 → 4.85 완료 → RopeLockCam1 10 초 (추가 구멍 바닥까지) | 같음 (`09_lever_ropecam_l11`), 고침 34.1-6 · 8 |
| 4852 | rope6 타고 내려감 | rope6 → LoadCheckD → PAND01D | l13 18.6–24.75 | 고침 (34.1-6) |
| 4865–4940 | 수평 줄 싸움 | PAND01D gorope1 · RopeSkelly | d5 · d6 | 고침 (34.1-4 · 5), 줄 꼰 무늬 (`15_rope_texture_d6_vs_4880`) |
| 4958 | 하데스의 방패 카드 | CaveShield 메시지 260 | c (동굴) | 같음 |
| 5135–5145 | rope6 타고 올라감 | PAND01D → 이웃 PAND01A rope6 | l16 28.6–38.4 | 고침 (34.1-7) |
| 5182 | 세이브 "아틀라스의 시험" | PAND01A 세이브 포인트 17 | s2 | 고침 (34.1-3) |

### 34.3 다른 에이전트 변경 뒤 재확인
- fbbc209 (안개 · Vis 슬롯 16 · 재질 ANM 색): 구멍 방 (l13 21.5 ↔ v4852), 수레 복도 · 문 방 (c5 ↔ v4445–4505), 부조 (v4 ↔ v4712), PAND01D (d6 ↔ v4880) 모두 같은 톤.
  수레 (4440–4510) 와 레버 (4845) 동작 그대로 (c5 · l11).
- aced5e0 (무조명 추가 레이어 알파): 부조 · 금속 장식 · 황금 방패 얼굴 (FX 끈 v2 에서 얼굴 또렷) 퇴행 없음.
- cc7e2fa (V2-32 UV): rope6 · gorope1 이 한 색이 아니라 꼰 줄 무늬 (영상과 같음). PAND01B (metalTrack01 · leverSAW · 모래주머니 줄) · PAND01C NewLever 는 이
  구간 (4050–5189) 에 나오지 않는다 [추정: 영상 5155–5176 은 구멍 방 위층 → 북쪽 등잔 복도 → PAND01A 세이브 포인트 17 (−505, −6, −337), LoadB 볼륨은 지나가기만].
- 9f69e4c (움직이는 GO 이미터 · Vis 슬롯 1): 방패를 가져가면 zeusSheildOFF 로 빛이 멈춤, 등잔 불꽃 자리 그대로.
- 4214de8 (표면 이미터): 아르테미스의 검 파란 · 보라 빛과 휘두른 궤적이 영상 4062 · 4070 과 같은 색 (b3).

### 34.4 남은 것 · 미확인
- 황금 방패 (영상 4640) 빛: 엔진은 FXC_emitter1 (곡선 이미터, rate 1400, PTC_particle4 수명 0.35 · 크기 1600) 의 주황 빛이 방패 얼굴을 덮는다 (FX 를 끄면
  얼굴이 보임, `13_alcove_shield_glow_vs_4640`). 영상은 얼굴이 또렷하고 가장자리만 빛난다. 레벨 파티클 색 · 알파는 `LevelEmitters.def` 의 [추정] 휴리스틱 [미해결].
- rope6 아래 끝: 줄 끝까지 미끄러지면 발이 받침 바닥 (−416) 보다 39 아래 (−455) 라 "끝에서 바닥이 있으면 내림" 이 바닥을 못 찾는다 (엔진 줄이 영웅 무게로 늘어남)
  — 영상은 줄 사이를 뛰어 옮겨 보이지 않는다 [미확인].
- PAND01D → PAND01A 로 올라간 뒤 현재 레벨: PAND01D 스크립트에 LoadCheck "Pand01A" 가 없다 (HoleLoad 는 LoadWad 만) — 엔진은 현재 = PAND01D 그대로 [미확인].
- 레버 방 궁수 (ArcherRow, setTimedArchers 의 1019 사격 명령 필터 66–69) 가 엔진에선 걸어와 근접 공격도 한다 — 영상은 4300 에 처치해 비교 못 함 [미확인].
- 하네스로 본 것: 레버 Idle (10 초) 중 화살에 맞으면 카메라 볼륨 (x −422 … −346) 밖으로 밀려 RopeLockCam1 이 풀린다 (궁수가 살아 있을 때만, 영상과 무관).
- 이전 항목 그대로: 구멍 방 고르곤 시각 차이 (봇이 느림), 봇이 이웃 슬롯 적을 노림, 다른 내비 상태의 오래된 피격 구, 동굴 분쇄기 60 처치 (봇 85 초에 깔림),
  ROPEHOHGRAB → MAISKB32 미시험, 줄 위 기술 방향 [근사].

### 34.5 증거 · 시험
- `work/verify/pandora_atlas/` (커밋 안 함): 01–08 (§34.1-1 … 5), `09_lever_ropecam_l11`, `10_rope6_descent_l13`, `11_rope6_climb_back_l16`, `12_cart_to_plate_c5`,
  `13_alcove_shield_glow_vs_4640`, `14_boa_trail_b3_vs_4062`, `15_rope_texture_d6_vs_4880`, 영상 프레임 `vid/v4055 … v5182`.
- 시험: `ArtemisHitVolumeTest`, `MemoryCardFlowTest`, `RopeSimTest`, `RopeCombatTest`, `HeroNavigatorTest.traverseRopeFightHoldsHandsOnRopeAndFacesTarget`,
  `RopeAnchorFollowTest`, `IdleCombatInputTest` — engine 721 개 통과.

## 35. QA vs PS2 영상 — 혼돈의 블레이드 휘두르기 궤적: 손 쪽 사슬에서 칼끝까지 넓은 주황 불꽃 호 (영상 2화 1352–1356 · 1484 · 2005 · 2580–2615 · 3400, 하수도 217–355, 2026-10-25)

영상의 모든 블레이드 휘두르기는 **손 쪽 사슬에서 칼끝까지 넓은 주황 불꽃 띠** 가 휘두른 길을 따라 남는다 (2605.0 머리 위 큰 초승달, 1356 세로 호, 1352 가로 줄,
1484 · 2005 · 3400 굽은 호). 엔진은 사슬을 따라 휜 좁은 부채꼴 (0x1FC070) · 칼끝 불꽃 · 검은 사슬 고리뿐이었다 (§31.4 ①).

### 35.1 원작 — 넓은 호 = 블레이드 객체 +0xB4 의 EffectsServer 궤적 (0x12DE70) [확실: 디스어셈블, 45 §3.4.1]
- Blades_Init (0x1F9C38) 이 블레이드마다 **EffectsServer (0x1E) 서브타입 0xB "칼 궤적"** 을 만든다: 리소스 +0xB0 = 0x12DD60 (템플릿 0x000B001E, **점 3 개**),
  인스턴스 +0xB4 = 0x12DE70 (MAT_swordtrail, 위치 + ST 정점, 링 200 칸). 45 §3.1 의 "+0xB0 = 충돌용 구 3 개 [추정]" 은 틀렸다 — 그 세 점이 궤적 띠의 폭이다.
  0x1FC070 의 부채꼴은 이 띠의 맨 앞 (T = 1) 을 지금 사슬 굽음까지 메우는 덮개였다 (+0xB4 가 켜졌을 때만 그림).
- 블레이드 갱신 0x1FB460 끝이 매 프레임 리소스 행렬 = 블레이드 행렬, 점 = 블레이드 로컬 {사슬 베지어 **t 0.2**, **t 0.7**, **칼끝** (P3 − Z × Weapon Length × 16)} 을 쓴다.
- EffectsServer 갱신 0x240A88: 켜져 있으면 (부착물 종류 0 = 이벤트 17/18, Blades_Lights 0x1FAE74 가 0x201F28(슬롯, 0, +0xB4)) 프레임마다 **최대 10 개 표본** —
  n = min(10, int(4·‖ΔR‖ + 4·|Δ이동|)) (0x12F288, 가중 0x2C9080/84), 지난 세 프레임 행렬 (원소별) · 점의 **이차 보간** (0x12EEC0 / 0x12F080 · 0x12F658 / 0x12F718) 으로
  사이를 메워 빠른 휘두르기도 곡선이다. 수명 **0.32 초** ([0x29CA70], 0x12DAC8 이 템플릿 값을 덮어씀) — 끈 뒤에도 남은 띠는 수명까지 그려진다.
- 텍스처 좌표: S = 0 / 0.5 / 1 (t 0.2 → t 0.7 → 칼끝), **T = 1 − 나이 / 0.32** (0x2410xx). TXR_swordtrail (64×32, 무기 단계마다 다름) 은 아래 줄 (새 표본) 이 밝은
  주황/노랑, 위로 갈수록 붉고 어두운 불꽃 혀, 오른쪽 (칼끝) 이 왼쪽보다 4 배쯤 밝다 → **바깥쪽이 밝고, 지나간 쪽은 0.1 초 만에 붉게, 0.2 초면 가는 붉은 줄만** 남는 초승달.
  불꽃 혀가 T 방향이라 띠 길이 방향으로 가는 줄무늬가 선다 (영상 2605.0 의 "띠를 따라 고운 줄"). 색 = Trail Tint (1, 1, 1, 0.8), 신들의 분노는 MAT_godswordtrail (파란 불꽃) · God Mode Trail Tint.
- 궤적 이벤트 17/18 이 있는 영웅 기술 (R_PERMK CombatFile 000–006 전수): 모든 □ · △ 콤보 (COMBO3*–7A), 공중 (AIRV1 · AIRH1–3 · AIRDASH · AIR360 · AIRIMPALE),
  막기 · 패리 (BLOCK · PARRY01/02), 벽 찌르기 (한쪽), ○ 잡기 · 던지기 · 처형 (COMBO5A · HARAIRTHROW1 · NPCKILL · MAISKB* …), 줄 · BMI 타기, 착지 일부, BERSERKENTER (0.05–0.32).
  **마법 시전 (CombatFile 003) 에는 없다** — 포세이돈의 분노 · 제우스의 분노 동안에는 궤적이 생기지 않는다 [확실: 데이터].

### 35.2 엔진 수정
- `game/BladeTrail` (GL 없음): 블레이드마다 링 200 칸 · 행렬 이력 · 점 이력 · 0x240A88 갱신 그대로 (하위 표본, 꺼졌다 켜진 첫 칸 면적 0, 꼬리 지우기, T).
  `HeroRig.tickBlades` 가 사슬 갱신 뒤 `bladeTrail.update` (켜짐 = 아르테미스가 아니고 `trail(side)`, 블레이드 길이 = Weapon Length (god 이면 God Mode Weapon Length) × 16),
  `GameScene.respawn` (순간 이동 = SetMatrix 0x1E0A18) → `HeroRig.snapBlades` → 지움 (0x12E768).
- `BladeChainRenderer`: 블레이드마다 칸을 삼각형 4 개로 (옛0·새0·옛1 / 새0·옛1·새1 / …), 부채꼴과 같은 MAT_swordtrail (god 판) 텍스처 · Tint · 가산 · 깊이 검사만,
  사슬 고리 · 부채꼴보다 먼저 [추정: Blades_Init 생성 순서]. 층 3 이라 안개 없음 (§31.2-2).
- `-Dgow.notrail` 띠만 끔 (전·후 비교), `-Dgow.debug.trail` 틱마다 기술 · 켜짐 · 칸 수 · 하위 표본 수.

### 35.3 영상 대조 (스크래치 녹화, 블레이드 단계 `-Dgow.blades=2` = R_WPN0_2)
| 영상 | 엔진 |
|---|---|
| 1352 · 1356 · 1484 · 2005 (사막 안개) 손에서 칼끝까지 넓은 주황 호 | 고치기 전 (d1n, `-Dgow.notrail`): 사슬 · 노란 부채꼴 · 칼끝 불꽃뿐 → 고친 뒤 (d1, DEST01 `--arrive DEST00@2400,507,1282,0`, □□□ △△): 같은 넓은 주황 호 (`01`) |
| 2605 (돌 상자 홀) 머리 위 큰 초승달 · 217–355 · 770 하수도 | SEWR01 (`--arrive ATHN07`, □□□ △△△): 같은 초승달 · 가로 줄 (`02`). 불꽃 화소 평균 영상 (227, 140, 70) · 밝은 10 % (251, 200, 103) / 엔진 (190–216, 102–132, 57–82) · (247–252, 155–196, 83–139) |
| 0.1 초 연속 2605.0 → 2605.3: 밝은 머리 위 호 → 0.1 초 뒤 붉고 넓은 반원 + 바닥 사슬 따라 밝은 띠 → 0.2 초 가는 붉은 줄 → 사슬 띠만 | △ 내려찍기 (s1 틱 6 간격): 밝은 호 → 붉은 반원 + 사슬 따라 밝은 띠 → 가는 줄 → 사슬 띠만 — 같은 순서 · 같은 빠르기 (`03`) |
| 1355.8 → 1356.1 사막 세로 호 · 지난 고리는 흐려지고 새 띠는 밝음 | DEST01 콤보 같은 흐림 (`03` 아래 두 줄) |
| 3545 · 3598 (판도라 신전, 블레이드 단계 4) 붉은 호 (§33.4) | PAND00B `-Dgow.blades=3` (R_WPN0_3 붉은 불꽃 텍스처): 같은 붉은 호 (`08`) |
| — | 공중 □ (AIRH1–3), 신들의 분노 (`-Dgow.giveAll`, L3+R3 → 파란 띠 MAT_godswordtrail, `06`) [영상 대조 없음] |

### 35.4 시험
`BladeTrailTest` (6): 링 200 칸 · 0.32 · S (0x12DE70 식, 색까지면 195), 행렬 이력 (첫 push 버릇 · 둘째 간격 2/60 · 세 프레임 이차식 정확 · 하위 표본 수 이동 2.6 → 10 · 90° → 8 · 0.8 → 0),
점 이력 (간격 1 이차식), 띠 수명 · T (새 1 · 꼬리 옛 0 · 이어짐 · 등속이면 하위 표본이 1/10 간격 · 끈 뒤 19–21 프레임에 사라짐), 꺼졌다 켜진 첫 칸 면적 0 · 지운 뒤 순간 이동에 줄 없음,
실제 □ 콤보 (R_HERO0 · R_PERMK): 이벤트 17 동안 머리 칸 = 이번 틱 세 점, 하위 표본 생김, 60 칸 이상 쌓임, 폭 (t 0.2 → 칼끝) > 칼 길이 + 10, 콤보 끝나면 사라짐.

### 35.5 남은 것 · 미확인
- [근사] 색 배율: 띠 인스턴스 색 (Tint) 을 부채꼴과 같이 텍스처 × 색, 알파 0.8 가산. VU1 커널의 정점 색 경로 (128·qw76 · 알파 qw78.w, MAT_swordtrail 헤더 p14 = 0) 는
  끝까지 따라가지 않았다 — 영상 대조 밝기는 같은 범위 (35.3).
- [근사] GS 텍스처 감싸기 (CLAMP/REPEAT) 미확인 — S·T 를 끝 텍셀 중심으로 자름. [추정] 프레임 안 순서 (EffectsServer 갱신이 블레이드 갱신 뒤) · 사슬보다 먼저 그림.
- [미확인] 2605.1–2605.5 의 바닥 사슬 따라 불꽃 띠는 영상이 더 높고 두껍다 (사슬이 바닥에 놓인 뒤에도 0.4 초) — 엔진은 사슬 따라 얇은 밝은 띠. 다른 카메라 높이 탓인지,
  내려찍기 MFX 같은 다른 이펙트인지 보지 못함. ○ 잡기 · 던지기 궤적은 데이터로만 확인 (적이 있는 녹화 없음). 신들의 분노 파란 띠는 영상 장면이 없어 대조 못 함.
- [미구현] 0x1FDB18 의 순간 이동 때 블레이드 행렬을 상태 관절로 바로 놓기 (궤적만 지움), 데이터 궤적 FXC 0xB001E (ATHN08C · R_SAF · R_SAT 의 NCV_ 곡선 궤적 — 사티로스 무기 [추정]).

### 35.6 증거 (`work/verify/blade_trail/`, 커밋 안 함)
`01_desert_v1352_1356_1484_2005_vs_before_after` (영상 | 고치기 전 | 고친 뒤), `02_dark_v2605_t0352_t0355_t0770_vs_engine_sewr01`,
`03_burst_0.1s_v2605.0-2605.3_vs_slam_v1355.8-1356.1_vs_dest01` (0.1 초 연속: 영상 | 엔진), `04_engine_sewr01_combo3_crescent`, `05_engine_dest01_arc` · `05b_…_before_notrail`,
`06_engine_god_mode_blue_trail`, `07_engine_air_combo`, `08_engine_pand00b_level3_red_arc_vs_v3545_3598` (영상 | 엔진),
영상 프레임 `video/v2604.5 … v2605.5` · `v1355.6 … v1356.4`.

## 36. QA vs PS2 영상 2화 — 판도라의 반지: PAND00D 눈 방 (세이브 · 궁수 · 물웅덩이 · 레버 · 석상 손잡이 · 빛줄기) → 가시 굴 → PAND00C 해골 문 · 고리 1 크랭크 → 뮤즈 석상 → 크랭크 → 아르테미스의 굴 (영상 3690–3958 초, 2026-10-25)

영상 = hoXYXNef60U (2화, 전사 (보통)), 프레임 728×409 (`work/verify/rings_rooms/video/v<초>.jpg` 159 장 — 3691–3958 3 초 간격, 3743–3800 · 3836–3861 · 3944–3960 1 초 간격, 커밋 안 함).
엔진 = origin/main 68cf54a 위 (rebase 8d9fcae), 1280×720 lockstep, 스크래치 홈, 경로 하네스 (`-Dgow.auto.route`, 순간이동 · `use:` · `crank:` · `kill:33`).
**정정 (§33.1)**: 영상 3686–3694 의 세이브는 PAND00C (−936, 481, 1720) 이 아니라 **PAND00D 눈 방 남서쪽 구석 세이브 16** (−448, 0, 3296) 이다 (회색 돌 벽 · 오른쪽 부조 = 엔진 x1 첫 프레임과 같음) —
§33 의 "방 벽이 붉음" 은 다른 세이브 방을 본 것.

레벨 연결 [확실: 스크립트 · 배치 · 충돌]: PAND00C 고리 1 (중심 (−240, 0, 1232), 반지름 ≈ 650–800, 북 = −z 쪽 이름 규칙: R1SouthVol = +z) 의 +z 출구 (부수는 문 gocrusherdoor z 2001)
→ PAND00D 가시 굴 (x −240, z 2040–3250, 분쇄기 crusher1–4) → 눈 방 (x −480…−20, z 3260–3700): 가운데 물웅덩이 (Water 면 y −7.1, 바닥 −144, 가운데 배수구),
북쪽에 두 석상이 받친 구 (PAND00C goeyedoor, 손잡이 crank Type 1 id 22 · 받침 톱니 둘), 구 뒤 레버 goeyermlever (Push 012 Type 5, id 120), 서쪽 뿔 해골 문 goliftdoor (잠김),
상자 chest1 (−439) · chest2 (−50, 종류 순환) · chest5. 적: EyeRoomEnemies-ArcherLevel2AI1–4 = R_SK2 템플릿 33, MaximumCount 2 · Respawn · 지연 5 초 (넷이 한 번씩 다시 = 8).

### 36.1 장면 대응
| 영상 (초) | 장면 | 원작 (스크립트·데이터) | 엔진 (실행 · 초) | 결과 |
|---|---|---|---|---|
| 3691–3694 | 메모리 카드 저장 → 세이브 빛 R2 | PAND00D SaveGameZone 16 | x1 0 | 같은 방 (§33.1 정정) |
| 3697–3742 | 눈 방 싸움 · 초록 상자 · 파랑 상자 (오브) | 궁수 R_SK2 ×4 (+ 다시 4) · chest1/2 | x1 0–30 (보통, 봇) | 같은 방 · 같은 적 · 화살. 시간 비교 안 함 (봇) |
| 3744–3761 | 웅덩이에 뛰어들어 배수구까지 잠수 · 헤엄 → 북쪽으로 나옴 | Water y −7.1 깊이 137 | — | 엔진 수영 (38 §11) 있음, 이 구간은 몰지 않음 [미확인] |
| 3762–3766 | 뿔 해골 문 계단 → 구 사이로 | goliftdoor (잠김) | x1 2 | 같은 문 |
| 3767–3771 | 구 뒤 레버 R2 → 당김 (뒤 카메라) | goeyermlever 3.67 초 → 1023 (100, 120) → CrushersOff · 203 | a1/a6: R2 → 100 % 3.9 초 | 같은 구도 (`p_lever1`) |
| 3771–3779 | KrushOffCam: 구 사이 크레토스 → 석상 전경 → 가시 굴 분쇄기 물러남 → 굴 끝 | KrushOffCam (12 초), CrusherReset1–4 (5.25–6.75 초) | a1/a6 같은 순서 | 같음 |
| 3780–3782 | 고리 1 톱니 벽: 받침이 없다가 가짜 크랭크가 벽에서 접혀 내려옴 → 받침 · 기둥 | 203 → FakeCrank-AnimMurphyBed (+8), +11 초 FakeCrank-CrankOff · **CrankOn (JointVisibility 관절 2 = 1)** | 고치기 전: 받침 · 기둥이 처음부터 있고 가짜 크랭크와 겹침 → 고친 뒤 같음 (36.2-2, `a6m`) | 고침 |
| 3785–3790 | 구 사이로 다시 → 석상 받침 톱니 클로즈업 (손잡이가 나옴) | EyeRmLever-TriggerVol (CrushersOff) → HandleZmCam 4 초 · 203 → EyeDoor-MsgFromCrank2 → FakeHandles-AnimPlay, +3 초 LeftLeverOn / RightLeverOn (관절 2 · 8) · DoorCollOn | a6 25.3 → 28.3 | 같음 (`p_handle`). 고치기 전엔 손잡이가 처음부터 보였다 (36.2-2) |
| 3791–3796 | 서쪽 석상 손잡이 돌리기 | goeyedoor Type 1 3 초 → LevelData 2 = 100 | a6 36.6–40.5 (동쪽 석상) | 같음 |
| 3797–3808 | 수정 클로즈업 → 빛줄기가 구에서 왼쪽 (−z) 으로 → 굴 입구 → 해골 문에 닿음 → 크레토스 | EyeCrusherBeam-BeamOn · Ring1Beam-BeamOn (Ring1 = 마커 South), IbeamCam 2.66 초 → IbeamCam2 | a6 40.5 같은 카메라 순서 | **빛줄기가 거의 안 보임** (36.4) |
| 3811–3823 | 세이브 방 → 가시 굴 (피 · 물러난 가시) | — | a1 KrushOffCam 굴 | 같은 굴 · 같은 피 |
| 3826–3832 | 해골 문 메시지 123 | §33 | §33 | 같음 |
| 3835–3858 | 고리 1 크랭크: 아치 너머 가시 굴 → 돌리기 (아치 닫힘 · 연기) → 3857 아치 너머 뮤즈 계단 | goring1crank Type 4 (12 초 = 한 바퀴, 최대 0.15) — ¼ = 20 초, R1West 진입 = Siren 출구, **Crank1cam (관절 1 RingRot)** | 고치기 전: 카메라가 고리 바깥 (−338, 25, 2586) 에 고정되어 검은 화면 → 고친 뒤 크랭크 · 톱니 · 아치가 영상과 같은 구도로 고리와 함께 돈다, 23.4 초 (¼) 에 R1WestVol (`c2a`) | 고침 (36.2-1) |
| 3859–3874 | 계단 → 금 뮤즈 부조 R2 → 메시지 127 | PAND00D SirenDoor | §33 muse1 | 같음 |
| 3877–3928 | 회랑 (깃발 · 톱니 원판 · 등불) 을 돌아 해골 문 → 다시 크랭크 | 고리 1 회랑 (고정 해골 문, 고리와 함께 도는 장식) | c1 (고리 회전 중 영웅 위치 북 → 서 → 남) | 같은 회랑 |
| 3931–3958 | 둘째 크랭크 → 아치 너머 불 굴 · 빛 구슬 (아르테미스) | ¼ 더 → R1North = Lvl1 (PAND01A), Ring1LoadVols-Load01AVol2 → LoadWad Pand01A | c1: 50 % 43.4 초 R1North, 27.1 초 LoadWad PAND01A | 같음 (구도 · 시간) |

### 36.2 엔진 결함과 수정
1. **고리 1 크랭크 카메라가 고리 밖에서 검은 화면을 비췄다 (영상 3836–3958: 크랭크 기둥 · 벽 톱니 · 왼쪽 아치를 늘 같은 구도로).** CAM_Crank1cam (0x41CFE, 고정 · 고정 주시) 은
   크랭크 GO goring1crank 소유인데 엔진은 "카메라 이름과 같은 관절, 없으면 루트" 로 붙여 크랭크 뿌리 (−316, 0, 1953) 기준 로컬 (−22, 25, 633) = 눈 (−338, 25, 2586)
   이었다. 원작 [확실]: 노드 헤더 복사 0x12FEE0 이 CAM 리소스 +0x08 (s16) 을 정의 +8 로, 노드 만들기 0x1300C8 이 그것을 카메라 노드 **+0x60 = 부모 관절** 로,
   노드 월드 0x1303D0 이 (−1 이 아니면) 부모 GO 애니 인스턴스 (+0x104) 의 **지금** 관절 행렬 ([+0x8C] + j·64, 0x1306C8 — 매 프레임 0x136990) 을 곱한다.
   Crank1cam 의 +0x08 = 1 = RingRot (고리 중심) → 눈 (−273, 29, 1864) 에서 +z (바깥 벽) 를 보고, 크랭크가 고리를 돌리면 카메라도 같이 돈다.
   전 WAD 2916 카메라 중 이름 규칙과 다른 것 19 개 (PAND00C Crank1cam · Crank3cam · Crank4Cam · EyeCUcam · Zcam · Ring3Cam4 · ElevatorCam · LogCam 1–3,
   PAND02I PistonCam, PAND03E PinCam2–4, PAND03G BMI 4 개 (관절 56), PAND04G CrnkCam) — 대개 0 (루트), IdleCam 류 1 은 같다.
   → `CameraDef.Params.joint` (예전 쓰이지 않던 `animated`), `LevelData.PlacedCamera.joint` (범위 밖 → 루트 [근사]: PAND03G gologhitcamgrp 관절 1 개에 56),
   `CameraRig` 관절 애니 카메라도 그 관절, **`CameraRig.setLiveJoints`** — 엔진이 자세를 정한 GO (발판 몸 중 애니가 걸린 것: 크랭크 진행 · Animator, 두 슬롯) 면
   눈 · 정면 = 로컬 × 그 GO 의 지금 관절 (`GameScene.liveJoints`).
2. **크랭크 받침 · 석상 손잡이가 숨지 않았다 — Vis 슬롯 17 JointVisibility 미구현 (영상 3780–3782 · 3788).** 원작 Vis 이펙터 0x1ABAA8 끝 [확실]: 값 ≠ 0 이면 GO +0x104 의
   관절 표 +0x54 (0x1364F0 이 관절 수만큼 1 로 만듦, 없으면 "Game object needs '_JointVisibility' attribute") 의 [Vis 엔티티 +0x40 = 붙은 관절] 에 1 → 1, 2 → 0,
   3 → 뒤집기를 쓰고 관절 행렬 캐시 시각 (+0x40) 을 지운다. 렌더 행렬 0x137290 (0x159190 이 부름) 은 부모 비트 (+0x64) 가 없거나 표 값이 0 인 관절을 건너뛰고
   비트를 세우지 않아 **자손까지** 그리지 않는다 (0x137338–0x1374B0). 애니 u8 트랙도 같은 표에 쓴다 (0x1365A8 이 층 종류 6 출력에 +0x54 를 건다) — 41 §1.3 의 [추정]
   "값 0 = 자손 숨김" 을 확인. 데이터 21 개 중 관절 ≠ 0 은 PAND00C 넷 (Ring1Crank-RingRot-crankPlatform01-CrankOff/On 관절 2, EyeDoor-LeftAnim-lever01 2 ·
   RightAnim-lever02 8 Off/On), 나머지 (말하는 머리 · ATHN05B 문 빛 · PAND01BB 반짝이) 는 관절 0 이고 모두 Visible 과 짝이다.
   → `Platforms.Body.setJointVisible` / `hidden()` (애니 u8 숨김 + 스크립트 관절의 자손 상속), 한 번 받은 몸은 애니 전에도 관절별로 그림 (`jointsHidden`),
   `GameScene` · `SceneNav` (이웃 슬롯) Vis 처리, 기억 · 다시 보내기는 관절마다 (`ScriptWorld` 열쇠 "오브젝트#j관절" — 오브젝트 하나에 관절 둘을 쓰는 goeyedoor 가
   마지막 Vis 의 관절로 섞이지 않게).
3. **고리 톱니 가운데가 크랭크를 돌리는 동안 흐린 덩어리로 덮였다 (영상 3838–3856 의 톱니 가운데는 또렷).** 고리 1 GO 의 이미터 FXC_emitter1–3 은 서브타입 4 (커브) —
   emitter3 = 관절 9 RingArt1 의 0xD 노드 nurbsCircleShape5 → NCV_nurbsCircle5 (톱니 중심 둘레 반지름 26 원), 생성률은 act 채널. 레벨 배치 이미터
   (`LevelEmitters`) 는 커브 위에서 내지만 (16 §2.5) GO 이펙트 (Animator · 관절 이미터, `EffectSystem`) 는 커브를 몰라 400/초를 이미터 원점 한 점 (톱니 중심) 에 쌓았다.
   → `EffectLibrary.Curve` (0xD 노드 → NCV, 표면 이미터와 같은 "그룹 앞 마지막 정의" 규칙), `EffectSystem` 이 매 틱 노드 월드 = GO · 노드 관절 · 노드 로컬 로 커브 표본
   (`curveSamples`) 을 옮겨 `ParticleSystem.spawnOnCurve`, 방향 = FXC dir, 퍼짐 = FXC (원뿔 이미터와 같은 규칙) [확실: 0x25EC88 · 0x1249C8 흐름 / 근사: 표본 보간].

### 36.3 시험
`RingsRoomsTest` (3): cameraNodeJointComesFromHeader (0x12FEFC lhu +8 · 0x130100 sh +0x60 · 0x130400 lh +0x60, Crank1cam 관절 1 · 눈 (−273, ·, 1864) · +z),
jointVisibilityHidesJointAndDescendants (0x1ABDEC · 0x1ABE18 · 0x137368, crankPlatform01 의 자손 숨김 · 고리 장식 · 벽 톱니 보임 · 다시 켬 · 뒤집기, goeyedoor 관절 8),
ringCrankCurveEmitterHasCurve (FXC_emitter3 → NCV_nurbsCircle5 반지름 26, 상자 이미터 7 은 커브 없음). `CameraRigTest.cameraParams` 는 IdleCam joint 1 · Boat1Cam4 0.

### 36.4 남은 것 · 미확인
- **빛줄기 (영상 3798–3808)**: 원작은 수정에서 굴을 따라 해골 문까지 굵고 하얀 빛기둥이다. 엔진은 BeamOn (Visible 1) 뒤 MDL_EyeCrusherBeam (z −212…914, 굵기 ≈ 9,
  MAT_M01SFXlightBeam5 가산 · 레이어 rgba 0.116 · UV 스크롤 3.33 초) 을 그리지만 거의 보이지 않는다. 조명 없는 모델의 정점 색 · 레이어 rgba 가 첫 레이어에 어떻게
  들어가는지 (43 §30 은 2 · 3 레이어만 해독) 와 두 번째 오브젝트 (MAT_phong4, 형 0x1D) 의 역할 [미해석] — 이번에 고치지 않았다.
- 아치 안 연기 · 크랭크 받침 먼지 (영상은 어두운 연기와 가는 흰 줄): 엔진은 FXC_emitter7 (서브타입 5 상자) 을 작은 상자 한 점에서 내 아치 가운데 흰 덩어리 [근사: 상자 크기
  · 서브타입 5–9 갱신 미해석].
- 크랭크 받침이 숨어 있는 동안 (CrankOn 전) 크랭크를 잡을 수 있는지: CrankOff 는 슬롯 16 (비헤이비어) 을 끄지 않아 엔진은 잡힌다 — 원작 잡기 질의 0x1C3F18 이 숨은 관절
  손잡이를 거르는지 [미확인] (영상에서는 레버 뒤에만 간다).
- 웅덩이 잠수 · 헤엄 (3744–3761) · 눈 방 싸움 시간 · 둘째 궁수 무리는 엔진으로 영상과 나란히 보지 않았다 (봇 싸움은 같은 R_SK2 화살).
- 관절 카메라의 0x1000 (활성화 때 소유 GO 애니 재시작): 엔진은 자체 시계 카메라 애니만 다시 튼다 — 발판 몸 (PAND00C 굴러오는 통나무 LogCam) 의 Animator 애니는 다시 틀지 않는다 [근사, 이 구간 밖].
- 영상 3836 의 아치 너머 가시 굴 (크랭크 전) · 3857 뮤즈 계단 · 3957 불 굴은 고리 회전 뒤 바깥 고정 세계가 보이는 것 — 엔진 c1/c2 에서 같은 출구 정렬 (R1West · R1North) 을 확인했고, 아치 너머 그림의 나란한 대조는 하지 않았다.

### 36.5 증거 (`work/verify/rings_rooms/`, 커밋 안 함)
`video/` (영상 159 장), `p_lever1.png` · `p_lever2.png` (3767–3782 ↔ a1: 레버 · KrushOffCam · 가시 굴 · 톱니 벽), `a6m.png` (고친 뒤 받침 없음 → 가짜 크랭크 → 받침),
`p_handle.png` (3786–3791 ↔ a6 손잡이), `p_beam.png` · `beam2.png` (3797–3808 ↔ a6 빛줄기 — 36.4), `c1a.png` (고치기 전 크랭크 카메라 검은 화면), `c2a.png` · `c2b.png` (고친 뒤 3838 · 3850 구도),
`gear.png` · `hub.png` (톱니 가운데 덩어리, 36.2-3 전), `map_all.png` · `map_eye.png` (위에서 본 충돌 지도).

## 37. QA vs PS2 영상 2화 — 아르테미스의 검 숨김 · 해제: 수평 줄 · 오르는 벽 = 해제, 줄 · 사다리 · 붙잡기 · 잡기 · 마법 = 숨김, 검을 든 이동 애니 (영상 4014–5189, 2026-10-25)

영상 = hoXYXNef60U (2화, 전사 (보통) = `-Dgow.difficulty=1`), 클로즈업 = 메인 세션이 1920×1080 원본에서 4:3 게임 화면만 자른 1270×952 / 635×476 프레임.
엔진은 장착 비트 (player.flags &amp; 0x20) 하나로 검 / 블레이드를 그렸다 — 수평 줄 · 상자 · 마법 중에도 검이 손에 남고, 수평 줄에서 내려와도 검을 든 채였고,
검을 든 대기 · 달리기가 블레이드 자세 (두 팔 흔듦) 였다.

### 37.1 원작 [확실: 디스어셈블]
1. **장착 0x19C240(1)** (부르는 곳: SCR_EnableBerserkBlade 0x19C578 — BERSERKENTER 555 의 0.22 SCRIPT, 비트가 없을 때만 · 아래 2 의 GO 재생성): 플레이어
   (0x29DC1C) +0xC |= 0x20, 검 GO (0x29D818) 가 없고 업그레이드 적재 중 (0x29D810) 이 아니면 WAD_R_Brsrk 맥락 (0x19C1F0) 에서 /Player/BerserkBlade
   "Stage n GO Name" (goBerserkBlade, 단계 = 0x1D9348(…, 7)) 을 만들어 영웅 RWEAPIH 에 붙이고 (0x131460, GO +0xF4 |= 0x200) 이미터 +0xB4 = 1,
   영웅 vt+0x5C (getter 0x2437C8 = +0x328 내비 애니 표 — 같은 vtable 의 +0x64 = +0x324 전투원, +0x6C = +0x338 블레이드) **+0xA8 = 1 (이동 애니 뱅크 1)**, 혼돈의 블레이드 숨김
   0x201930(왼 · 오) (블레이드 GO 부품 0xF · 0x27 · 0x11 · 0x1E 끔 0x131300 · 사슬 +0xA8 &amp;= ~1 · 슬롯 +0xC &amp;= ~2).
   **해제 0x19C240(0)** (SCR_DisableBerserkBlade 0x19C5C0 · 아래 2 · Progress_Reset 0x1D6818 (새 게임 · 체크포인트 · 불러오기 — +0xC &amp;= 0x18) · 아테나의 검
   0x1C2EB0): +0xC &amp;= ~0x20, GO 지움 (0x132768), 뱅크 +0xA8 = 0, 블레이드 보임 0x201908 (부품 켬 0x1313B0 · 사슬 +0xA8 |= 1 · 슬롯 +0xC |= 2).
2. **BerserkBlade_Update 0x1DB788** — 플레이어 갱신 0x1D6CF0 이 매 프레임 (0x1DB608 뒤, HUD 0x1D7148 앞): 비트가 없으면 끝. GO 가 없으면 0x19C240(1).
   영웅 +0x174 가 **(0x400 줄 &amp; 0x20000 수평 줄) 또는 (0x20 벽 이고 0x400000 사다리 · 0x300000 좁은 턱 (벽 보고 · 등 대고) 이 아님 = 오르는 벽) → 해제
   0x19C240(0)**. 그 밖에 SCR_HideBerserkBlade (0x29D814) 또는 +0x174 &amp; (0x4000 물체 붙잡기 | 0x400 줄 | 0x10 턱 매달림 | 0x380 물 | 0x80000 좁은 바닥 |
   0x20 벽) 또는 +0x2D8 &amp; 0x800000 (애니 구동 내비 전환 — 올라서기 등) → **숨김**: 검 GO 끔 (vt+0x44) + 블레이드 보임 (0x201908). 아니면 → **보임**:
   GO 켬 (vt+0x34) + 블레이드 숨김 (0x201930). 보일 때 부위 10 공격 중이면 (0x209D00(…, 10)) 검 선분 → 벽 불꽃 0x202248.
   → 숨김은 상태가 끝나면 저절로 돌아오고, **해제는 돌아오지 않는다** (0x19C240(1) 을 부르는 곳은 L1+R1 의 SCR_EnableBerserkBlade 와 비트가 서 있을 때의
   GO 재생성뿐). §33.4 에 적었던 "(0x20 &amp; (0x400000 | 0x300000)) 이면 해제" 는 반대 — 사다리 · 좁은 턱은 숨김, 오르는 벽이 해제다.
3. **SCR_HideBerserkBlade** (Hash127Upper 0x8CACF07D, Items_RegisterScr 0x19CFB0 → 0x19C6C0): 영웅 combatant 의 살아 있는 스크립트 목록 (+0x90) 에 같은
   함수의 다른 이벤트가 없으면 (0x207408(…, 2)) 0x29D814 = 1, 목록에 넣고 끝 콜백 +0x30 = 0x19C608 (빼고, 남은 것이 없으면 0). R_PERMK 영웅 기술 87 개가
   [0, 1] 창으로 가진다: ○ 잡기 GRAB 200, 처치 MAI* (해골 · 고르곤 MAIMEWGRAB 260 · 키클롭스 · 미노타우로스 MAIMIN01 400 · 세이렌 · 케르베로스 · 하피 ·
   야만인 …), 마법 (포세이돈 506 · 하데스 508 · 제우스 BOLTPULLOUT 60 · 메두사 PSENTHEAD 81), 석화 FREEZEPOSE 120, BMI 타기 · 악령 잡기. BER* · 줄 기술에는 없다.
   적 기술 파일 (R_SKS) 에는 없다 [확실: 데이터].
4. 숨김 · 해제가 건드리지 않는 것: 분기 플래그 0x2000 (0x208578 → +0xC &amp; 0x20) · 피해 배율 (0x1DBD18 → +0xC &amp; 0x20) 은 숨김 중에도 검. HUD 에는 지금 무기
   아이콘이 없다 — BerserkBlade_Status (HUD +0x80) 는 보유 아이템 비트 0x40 (Progress_UpdateHud 0x1D7638) 이고, 영상 HUD 틀도 검 전후 같다 (3545 ↔ 4018).
5. **이동 애니 뱅크 1** (0x1F7408 이 내비 애니 표 +0xA4 의 [+0xA8] 번째 뱅크에서 고름): TWK goHero Navigation "Bank 1" — ANIStand · ANITurnOnSpot berserkIdle,
   ANIWalk berwalk (berWalkBlend0..2), ANIJump · ANIVJump berJumpAir, ANIDblJump berJumpDoubleAir, ANIFall · ANIHighFall berFallN, ANILand · ANIHighLand berLand,
   ANICrankPush/Pull CrankPushR/PullR (뱅크 1 의 ladderidle · ropeswing* · slide 는 ANM_hero 에 없다). 숨김은 뱅크를 바꾸지 않는다.

### 37.2 영상 ↔ 엔진
| 영상 (초) | 장면 | 영상 | 원작 규칙 | 엔진 (고친 뒤) |
|---|---|---|---|---|
| 5095 · 5104 · 5108 | 통나무 문을 검으로 · 계단을 뛰어 올라감 | 오른손을 머리 옆에 들고 칼날을 등 뒤로 (보라 빛) | 뱅크 1 berWalkBlend2 | 같은 자세 (a4 10.9 초, a5 3.0 초 berserkIdle). 전: 블레이드 달리기 · 대기 |
| 5100.5 – 5101.9 | 초록 상자 (CSM, R2 누르고 있기) | 등에 혼돈의 블레이드, 검 없음 | 숨김 (+0x174 0x4000) | a4 5.0 – 6.5 초 블레이드 (로그 HIDE 0x4000). 전: 손에 검 |
| 5102.0 – 5103.5 | 상자 끝 | 초록 빛 속에서 검이 손에 — L1+R1 없이 (비트가 서 있어 L1+R1 이면 BERSERKEXIT 였다) | 보임 (저절로) | a4 7.5 초 SHOW, 검을 들고 뜀 |
| 5110 · 5118 | 수평 줄 (gorope1) 손 이동 | 등에 블레이드, 검 없음 | 해제 (+0x174 0x20400) | a5 4.1 초 UNEQUIP, 줄 내내 블레이드. 전: 줄에 매달린 손에 검 |
| 5150 · 5152 · 5135 · 5140 | 세로 줄 rope6 | 블레이드 (5140 등에 엇갈린 두 칼) | (이미 해제) | — |
| 5155 – 5170 | 내려와서 달림 · 방패 부조 앞 | 블레이드 — 다시 들지 않음 | 해제는 돌아오지 않음 | a5 25 – 48 초 저편 싸움 내내 블레이드 |
| 4860 – 4942 · 4970 – 4977 | 첫 수평 줄 싸움 · 동굴 싸움 | 블레이드 → 4974 긴 보라 칼날 (L1+R1 로 다시 듦) | — | 봇 `-Dgow.auto.artemis` 가 싸울 때 다시 듦 |

- 영상에 대조가 없는 것 [미확인: 영상]: 이 구간에서 검을 든 채 오르는 벽 · 사다리 · 턱 · 세로 줄 · 잡기 처치를 하는 장면이 없다 — 4852 · 5135 의 세로 줄과
  4126 – 4134 의 고르곤 꼬리 처치는 이미 블레이드 (4128 달리기가 두 팔을 흔드는 블레이드 자세), 4270 → 4274 는 싸우는 중 보라 → 주황 (L1+R1 BERSERKEXIT [추정]).
- 5102.0 의 다시 보인 칼은 위를 향하고, 엔진 a4 7.5 초는 수평 (chestOpen 끝 → berserkIdle 섞임 중) [미확인: 같은 프레임인지].

### 37.3 엔진
- `BerserkBladeRule` — `decide` (0x1DB788 판정: NONE · UNEQUIP · HIDE · SHOW), `stateBits` (내비 상태 → +0x174: 지상 0x1 (Narrow 바닥 |0x80000), 공중 0x4/0x8,
  턱 0x10, 벽 오르기 0x20, 좁은 턱 0x20|0x100000/0x200000, 사다리 0x20|0x400000, 줄 0x400, 수평 줄 |0x20000, 짚라인 |0x40000, 물 0x80, 밀기 · 상황 동작 0x4000).
- `HeroRig.berserkBladeUpdate` (매 틱, BERSERKENTER/EXIT 처리 뒤) · `artemisVisible` · `heroStateBits`: 검 모델 · goBerserkBlade 이펙트는 보일 때만, 블레이드
  모델 · 사슬 · 사슬 그림자 · 블레이드 라이트 · 사슬 스윕 · goMAIblade 는 검이 안 보이면 (숨김 포함). 피해 배율 · 분기는 그대로 장착 비트.
  `-Dgow.debug.combat` 이면 "[아르테미스의 검] UNEQUIP (+0x174 0x20400)" 처럼 바뀔 때 기록.
- `HeroController.berserkBank` / `bank()` (HeroRig 가 장착 비트로): 대기 · 걷기 · 달리기 · 점프 · 2단 점프 · 떨어짐 · 착지를 뱅크 1 클립으로. 속도는 뱅크 0 클립 —
  원작 지상 이동 0x1EAEC8 은 Sold +0x178 × 스틱 (32 문서) 인데 berWalkBlend2 의 zeroJoint 는 걷기와 같은 −3.2 라 그대로 쓰면 검을 든 달리기가 48 이 된다.
  `GameScene.footsteps` 가 berWalkBlend1/2 도 걸음으로.
- [근사] +0x2D8 0x800000 은 보지 않는다 — 엔진 `land()` 가 올라서기 · 사다리 내려서기 애니를 지상 상태에서 곧바로 섞어 끝낸다. 숨김의 GO 끄기 (vt+0x44) 는
  이펙트를 멈췄다가 보일 때 다시 낸다. 보일 때 검 벽 불꽃 (0x1DB9AC –) 은 예전대로 `MagicFx.weaponWorld` 부위 10 근사.
- [미구현] 내비게이터가 직접 고르는 뱅크 1 애니 (줄 · 사다리에서 뛰어내릴 때 ANIJump · ANIFall · ANILand, 크랭크 CrankPushR/PullR) — `NavAnims` 는 뱅크 0 표만.

### 37.4 시험
`BerserkBladeRuleTest` (8): 장착 아님 = 무엇도 안 함, 수평 줄 · 오르는 벽 = 해제 (숨김 스크립트보다 먼저), 사다리 · 좁은 턱 (벽 보고 · 등 대고) · 턱 · 줄 · 짚라인 ·
물 · 밀기 · 상황 동작 · Narrow 바닥 · SCR_HideBerserkBlade · 0x800000 = 숨김, 지상 · 공중 = 보임, 상자 뒤 장착 유지 · 수평 줄 뒤 해제 유지, +0x174 비트 표,
R_PERMK 의 SCR_HideBerserkBlade 기술 (GRAB 200 · 260 · 400 · 506 · 508 · 60 · 81 · 120 포함, [0, 1] 창, BER* 555–590 · ROPEHOHA 51 없음), 뱅크 1 클립 이름과 같은
달리기 거리. 엔진 시험 730 개 통과.

### 37.5 증거 (`work/verify/artemis_hide/`, 커밋 안 함)
`chest_v5100-5104_vs_engine_a4.jpg` (상자: 블레이드 → 검이 저절로 → 검을 든 달리기), `rope_v5108-5166_vs_engine_a5.jpg` (검 → 수평 줄 해제 → 저편에서도 블레이드),
`video/` (메인 세션 클로즈업 58 장: 4126 – 4134 · 4266 – 4296 · 4852 – 4977 · 5100 – 5170). 엔진 실행 a4 = `--game PAND01D`, 경로 `wait:0.5;200,-412,-1022!;wait:0.5;
kill:28;keys:1.5:L1+R1;wait:1.2;push:0.4:236,-1022;hold:4:236,-1022;…` (gochest3 healthChest), a5 = `…;-470,-412,-976!;wait:0.3;keys:1.5:L1+R1;wait:1.5;
jump:1.0:-400,-976;push:40:120,-988;…`, 둘 다 `-Dgow.giveAll -Dgow.debug.combat -Dgow.auto.route.nofight`.

## 38. 영상 2화 QA 마무리 회귀 순회 — 51 레벨 (7804ba7, 2026-10-25)

봇 60 초 쉬움 (`-Dgow.difficulty=0 -Dgow.auto=1 -Dgow.auto.secs=60`, `-Duser.home=<스크래치>`), 2 줄 동시, `--game L --skip-movies`.
대상 = ATHN 전부 (34) · SEWR01/02 · DEST00–03 · PAND00A–E · PAND01A–D (51). 기준 = 같은 방법의 4fe5fa2 (1 차 QA 끝) 빌드.
51 레벨 모두 rc 0 · 예외 0 (전 · 후). 46 레벨은 방문 · 순간이동 · 사망이 같다. 다른 5 레벨:

| 레벨 | 4fe5fa2 방문 · 순간이동 · 사망 | 7804ba7 | 까닭 |
|---|---|---|---|
| ATHN01B | 6/8 · 2 · 0 | 8/8 · 4 · 0 | 늘어남 [하네스: 봇] |
| ATHN05B | 4/28 · 1 · 0 | 5/28 · 1 · 0 | 늘어남 [하네스: 봇] |
| ATHN05D | 8/8 · 4 · 0 | 9/9 · 5 · 0 | 늘어남 (볼륨 8 → 9) |
| ATHN06B | 7/35 · 2 · 0 | 8/35 · 3 · 0 | 하피가 Patrol710/711 을 돈다 (942a3a1, §32.6 순회와 같음) |
| ATHN08C | 0/0 · 0 · 16 | 0/0 · 0 · 18 | 아레스 결투 흔들림 (§29, §32.6 — 다시 돌리면 뒤바뀜) |

- 엔진 테스트 747 통과 (7804ba7). 실제 세이브 위치 (`~/Library/Application Support/devple-gow`) 는 생기지 않았다.

## 39. QA vs PS2 영상 3화 — 아틀라스의 시험 나머지: PAND01B 톱날 방 · 창살 레버 · 가시벽 컨베이어 → PAND01C 절벽 · 손잡이 방 → PAND01BB 아틀라스 석상 크랭크 · 공 → 건축가 아들의 관 → PAND00D 기억 동영상 Semo05 (영상 0–1960 초, 2026-10-26)

영상 = HF4CELG5OxQ (3화, 전사 (보통) = `-Dgow.difficulty=1`), 프레임 = 메인 세션이 4:3 게임 화면만 자른 423×317 (`work/verify/ep3_a/video/v<초>.jpg` 737 장 — 5 초 간격 0–1960, 1 초 간격
1040–1090 · 1150–1170 · 1280–1300 · 1440–1600 · 1700–1880, 커밋 안 함). 엔진 = origin/main fdd299f 위, 1280×720 lockstep, 스크래치 홈, 경로 하네스.
레벨 연결 [확실: 스크립트 · 배치]: PAND01B (LoadCheckA ← PAND01A, 톱날 방 (−1360, ·, −1360) · 레버 leverSAW (id 43) / leverSAW1 (id 42) → 창살 barsClose / barsFar
(70 % 에 열림, 100 % 뒤 12 초에 닫힘 = 두 레버를 차례로 당기고 12 초 안에 지나감) → 구덩이 복도 beforePITtrig · 등 대고 좁은 턱 (BackPress 벽 x −1407, 바닥 x −1406…−1398,
z −1047…−796) → atlasLiftDoor (TAndF) → LoadCheckBB) · 서쪽 BreakThatWall → 가시벽 Block1/3/4 · 컨베이어 → LoadCheckC → PAND01C (절벽 · 하피 · 오르는 해골 ·
손잡이 방 handleTrig: 사이렌 · 방패 해골 → 질문 1019 → 메시지 258 → LoadWad Pand01B, ChestZone 에서 세이브 포인트 19 생성) → PAND01BB (아틀라스 석상 · 공: 크랭크
RamleverGroup (Push 220 "Pand1BBAtlas" Type 1) 에 손잡이 없음 106 / 있음 질문 1005 → HandleOn → 돌리면 neptune · StatueBall 이 Animate2Crank, 손잡이를 가져오면
StarthandleSkellys/Minos (궁수 R_SK2 ×3 · 미노타우로스 R_MI5 ×2) · NewLever → 동영상 p01Mp01 → 공이 벽을 뚫음 → 다리 → 건축가 아들의 관 (책 메시지 175 · 뚜껑 크랭크
gocoffinlidcrank · TAndF CoffinHeadRip 141 → 카드 257 "건축가 아들의 머리" · CoffinBase 내려감) → WarpTo00D) → PAND00D PlaySemoMovie (Semo05) → LoadWad Pand00C.

### 39.1 장면 대응
| 영상 (초) | 장면 | 원작 (스크립트·데이터) | 엔진 (실행 · 초) | 결과 |
|---|---|---|---|---|
| 0–40 | 세이브 빛기둥 → 사다리 · 등잔 복도 | **PAND01B 세이브 포인트 18** (−989, −96, −2004) | sv1 | 같은 복도 · 등잔 · 빛기둥 (2화 끝 저장 자리 — 아래 39.3) |
| 45–160 | 톱날 방: 들어가면 바닥 홈의 톱날, 해골 싸움, 레버 R2 | SawBladeONnOFF → SawBladeEnt VisOn, leverSAW | l2 · l3 · l5 | 같음 (톱날 · 레버 · 창살). 레버 손잡이 (−1157.5, 23, −1132.8) 축 −z / (−1488.3, 23, −1469.8) 축 +x |
| 160–235 | 벽 부수고 횃불 방 싸움 → 가시벽 컨베이어 복도 | BreakThatWall · BreakDoorEnemies R_SK4 · Block1/3/4 AnimPlay 0.6 · Conveyer 1.5 | k1 · k2 | 같은 장치 (가시벽 왕복 · 컨베이어 판). 봇 실행은 해골에 막혀 판단 못 함 |
| 240–330 · 705–760 · 840–905 | PAND01C 절벽 (하피 · 오르는 해골 · 초록 상자) | Bridgespawner · CS1–5 SkellyLevel4Climb · HarpyLevel2 | c1 | 같은 절벽 · 모래 안개 |
| 360–445 | 톱날 방 레버 둘 → 창살 통과 | 위 | l5 (두 레버 · 11.47 초 beforePITtrig) | 같음 |
| 445–480 · 640–655 · 770–795 | 구덩이 복도 등 대고 좁은 턱 | BackPress 벽 (`HeroNavigator` WALL_WALK 0x200000) | p3 (벽 쪽으로 밀어 붙고 턱을 따라 −1032 → −779) | 같음 (등을 벽에, 정면 = 법선) |
| 480–490 | 아틀라스 공 방 R2 → "크랭크에 손잡이가 없습니다." | StatueControl-CrankVolume 1024 → Dummy4NoHandle 106 | s1 · s2 | 같은 문구 · 구도. 석상 R2 = 105 (Statcam) 도 같음 |
| 760–790 | 오르는 벽에서 죽음 → 검은 화면 "로딩 중..." → 구덩이 턱에 등을 대고 다시 | 마지막 체크포인트 = beforePITtrig (턱 위에서 찍힘, PAND01C Checkpoint1/2 는 재발동 −1 이라 두 번째 방문엔 안 탐) | p4 (고치기 전: "벽 질의 빗나감 (공중)") → p5 | **고침 (39.2)**. "로딩 중..." 글 (메시지 4085) 은 엔진에 없다 (39.3) |
| 900–1020 | 아치 방 싸움 (방패 해골 떼) · 붉은 손잡이 방 (사이렌 · 방패 해골) | FloorDropControl RoomEntry → DMCcyl2 · 문 닫힘 카메라 · Siren ×2 · SkellyLevel2Shield ×5 | h1 | 같은 방 · 같은 적 |
| 1030–1080 | 질문 "손잡이를 가져가시겠습니까?" → 카드 → 강화 메뉴 | handleTrig 1019 → uPickedYes 258 | — | 질문 · 카드 문구는 메시지 표 그대로 (강화 메뉴는 이 구간에서 비교 안 함) |
| 1147–1170 | PAND01C 절벽 세이브 빛기둥 R2 → 저장 | 세이브 포인트 19 (H8 false, ChestZone 이 GlobalData0 뒤 생성) | sv2 (ChestZone 전이라 아직 없음) | 슬롯 이름 [미확인] (39.3) |
| 1180–1420 | 돌아와 공 방 아래 싸움 (궁수 · 가시 갑옷 미노타우로스) | StarthandleSkellys / StarthandleMinos (GotStatueHandle) | r1 (상태 미리 둠: 궁수 ×3 · R_MI5 ×2 Patrol11/12) | 같은 적 구성 |
| 1442–1470 | 질문 1005 → 손잡이 끼움 (낮은 카메라) → 크랭크를 밀며 돔 → 공이 올라가 아틀라스 석상 | HMsgSensorYES → HandleCam 3 초 · HandleOn (+1 초) · RamStatic Off → RamLever 1023 (id 40) → neptune / StatueBall Animate2Crank | r2 · r4 (손잡이 (−1262.6, 51.2, −644.1) 축 ±x) | 같음 (`r4.png`) |
| 1570–1600 | 석상 · 안개 · 둥근 구멍 너머 다리 | NewLever BallUp → p01Mp01 | — | 동영상 (몰지 않음) |
| 1610–1640 | 다리 → 열린 사원, 양피지 3–4 쪽 | bookold CSM 142 → 메시지 175 (4 쪽) | co1 | 같은 문구 (4 쪽) |
| 1640–1675 | 관 (황금 석상) R2 연타 → 머리를 뜯어 들어 올림 → 카드 | 뚜껑 크랭크 140 → CollOn → TAndF 141 (Acc 20 · Dec 20) → 100 % 2.5 초 뒤 257 · CoffinBase | co4 (8 초 연타 21.45 초 완료, 23.97 초 카드) | 같은 순서. 머리가 손 높이에 늦게 나옴 [미확인] |
| 1680–1715 | 초록 복도 · 상자 · 계단 | PAND01BB 아래층 → WarpTo00D | — | 몰지 않음 |
| 1717–1840 | 기억 동영상 (자막) | PAND00D PlaySemoMovie → Semo05 → 501 → LoadWad Pand00C | mv1 (녹화) · mv2 | 첫 자막 "판도라의 상자를 찾으려 했던 자들의 시체가 그의 앞에 있었다" 같음 |
| 1859–1870 | 눈 방 세이브 → 저장 | PAND00D 세이브 포인트 16 (−448, 0, 3296) | sv5 | 같은 방. 슬롯 이름 [미확인] (39.3) |

### 39.2 엔진 결함과 수정
1. **등 대고 좁은 턱에서 찍힌 체크포인트를 되읽으면 구덩이로 떨어졌다 (영상 770–790: 죽은 뒤 "로딩 중..." → 구덩이 턱에 등을 댄 채 다시 시작).** PAND01B beforePITtrig
   (CheckPoint, 재발동 대기 없음) 는 x −1404…−1308 · z −1072…−1032 로 턱 시작 (z −1047…−1032) 을 덮는다 — PAND01BB 에서 턱을 따라 돌아오면 턱 위에서 체크포인트가
   찍히고, 그 뒤 PAND01C 체크포인트들은 한 번만 (H1 −1) 이라 두 번째 방문에선 다시 찍히지 않는다. 원작 Load_PlayerBlock 0x1DC130 은 행렬과 +0x174 (0x200000 = BackPress
   벽 따라 걷기, 몸 정면 = 벽 법선 0x1C8000) 를 그대로 되돌린다 [확실: 43 §26]. 엔진 `HeroNavigator.reattachWall` 은 몸 정면으로만 벽 질의를 해 등 뒤 벽을 놓치고
   ("되읽은 이동 상태 WALL_WALK → 벽 질의 빗나감 (공중)") 공중이 되어 구덩이 쪽 Slide 경사로 미끄러졌다. → 정면 질의가 빗나가면 등 쪽으로 다시 질의하고 그 면이
   BackPress 면 등 대고 걷기로 붙인다 [근사: 원작은 질의 없이 상태를 되돌림] (p5: "→ 벽에 다시 붙음", 영상 790 과 같은 자세 · 구도 `p5.png`).
   시험 `HeroNavigatorTest.restoredBackPressSnapshotReattachesBackToTheWall` (등 뒤 BackPress = 붙음 · 등 대고 · 정면 = 법선, 등 뒤 Climbable = 안 붙음).

### 39.3 남은 것 · 미확인
- **세이브 슬롯 이름**: Save_SlotText 0x22E810 은 파일 u16 +0xC (= 영웅 +0x8C → 0x22D390 → 0x2A2704 → 0x2312B0) 에 0x258 (600) 을 더한 메시지 [확실]. 그런데 영상의 이름은
  세이브 포인트 Creator 슬롯 6 + 600 과 맞지 않는다: 2화 3098 PAND00A (15) → "길 잃은 영혼들의 사막" 614, 2화 끝 → 3화 0 초의 되읽은 자리가 **PAND01B (18)** 등잔 복도인데
  2화 5182 의 이름은 "아틀라스의 시험" 617 (§34.1-3 은 PAND01A 17 로 보았다 — 3화 첫 프레임 (`sv1` ↔ v0) 로 보면 18), 3화 1150 PAND01C (19) → "아틀라스에게 가는 길" 618,
  3화 1865 PAND00D 눈 방 (16) → "아틀라스의 절벽" 619. 앞 셋은 599 + 번호지만 눈 방은 맞지 않는다. 0x22E2A8 의 번호 = *[[생성 인자 +0x1C] + 4] 를 Creator 0x1ACCA8 →
  0x1A49C8 까지 따라가지 못했다 (§34 의 "슬롯 6" 은 이 경로를 확인하지 않은 것) — 엔진 `MemoryCardFlow.slotName` (600 + 번호) 은 그대로 두었다.
- 체크포인트 되읽기 화면: 원작은 검은 화면 오른쪽 아래에 "로딩 중..." (메시지 4085) 을 띄운다 (영상 785). 엔진은 검은 한 프레임 뒤 밝아짐 — 메시지 4085 를 쓰는 코드는 찾지 못했다.
- 관 머리 뜯기: 영상 1670 은 크레토스가 들어 올린 손에 머리, 엔진 co4 는 손을 든 뒤 머리가 엉덩이 높이에 잠깐 보인다 (오브젝트 headRip03 과 영웅 headRip03 의 시각 · 붙는 관절) — 1 초 프레임이 없어 확인 못 함.
- 녹화 lockstep 에서 동영상 (Semo05) 이 매우 느리게 나간다 (mv1: 138 초 동안 첫 장면) — 하네스 쪽, 녹화 없는 실행은 끝 이벤트 501 → LoadWad Pand00C 가 바로 이어짐 (mv2).
- 몰지 않은 것: 톱날 · 가시벽에 맞는 피해, PAND01C 손잡이 방 바닥 무너짐 (FloorFall1–3 → PAND01CC) 의 끝까지, p01Mp01 뒤 공 굴리기 장면 (1570–1600), 강화 메뉴 값 (1060–1080),
  1280–1420 싸움 시간 비교 (봇).

### 39.4 증거 (`work/verify/ep3_a/`, 커밋 안 함)
`video/` (영상 737 장), 스크래치 `ep3a/`: `p5.png` (고친 뒤 턱 되읽기 ↔ v790), `p4.log` (고치기 전 "벽 질의 빗나감"), `s2.png` (106 문구), `r4.png` (크랭크 · 공 · 석상), `co4.png`
(관 · 머리 · 카드 ↔ v1655–1675), `sv.png` · `sv3.png` · `sv5.png` (세이브 포인트 18 / 19 / 17 / 16 ↔ v0 · v1140 · v1868), `mv1.png` (Semo05 첫 자막).
엔진 테스트 (rebase 뒤) 전부 통과.

## 40. QA vs PS2 영상 3화 — 포세이돈의 시험 PAND02A 원형 경기장: 케르베로스 토하기 · 새끼가 자라 어른 · 깔려 물리기 (영상 1960–2880 초, 2026-10-26)

영상 = HF4CELG5OxQ (3화, 전사 (보통) = `-Dgow.difficulty=1`), 프레임 423×317 (`work/verify/ep3_b/video/v<초>.jpg` 333 장 — 1960–2880 5 초 간격, 2255–2320 ·
2455–2470 · 2490–2520 · 2570–2620 · 2775–2800 1 초 간격, 커밋 안 함). 엔진 = origin/main fdd299f 위, 1280×720 lockstep, 스크래치 홈, `--game PAND02A --arrive PAND00C
--skip-movies` + 봇 (`-Dgow.auto=1`).

레벨 [확실: 스크립트]: PAND02A (구역 제목 12 "포세이돈의 시험") — 허브 PAND00C 에서 Warp (WarpFromHub) → 원형 경기장. cerebusIntroMPEG 볼륨 → 동영상 p02Mp01
(영상 2255–2282: 크레토스 클로즈업 → 새끼가 불타며 자라 머리 셋 케르베로스) → CerebStart · DMC 문 (DMCdog/dog1 dogIn) · 케르베로스 CerebusVAI1 (R_CEA 템플릿 81
"cerebus vomit") + 새끼 CerebusPuppyTAI2–5 (R_CEP 86, Respawn, LevelData9 ≤ 16) → LevelData3 (DeathDetectZone 제거, Filter 2) = LevelData4 (생성 Action) 이고
LevelData9 ≥ 16 이면 DMCCamOff · dogOut. 그 뒤 협곡 (Spawner/Spawner2 · 키클롭스 R_CY3 Patrol10/12 · 궁수 timedArchers · DMCCerebus) → PAND02AA.

### 40.0 장면 대응
| 영상 (초) | 장면 | 원작 (스크립트·데이터) | 엔진 (실행) | 결과 |
|---|---|---|---|---|
| 1960–2210 | 해골 문 (메시지 "자물쇠에 해골 열쇠를…") → 고리 회랑 → 사다리 → 물 고리 헤엄 · 수로 · 둥근 통 위 · 사다리 | PAND00C 고리 (SkullDoorCam · UWSkullDoor · ring3water-AnimWaterDrain · LadderLower · Lvl2Warp) | 몰지 않음 | [미확인] (§36 의 고리 1 이후) |
| 2215–2235 | 세이브 방 (초록 · 파랑 상자, 메모리 카드) → 모래 복도 | PAND02A 도착 WarpFromHub (Chesty5/6 CSM 008) | `--arrive PAND00C` 0 초 방 · 상자 둘 | 같음 |
| 2250 | 구역 제목 "포세이돈의 시험" | ZoneReportGroup 12 | 4 초 | 같음 |
| 2255–2282 | 동영상 p02Mp01 (새끼가 불타며 자라 머리 셋) | cerebusIntroMPEG | `--skip-movies` (녹화 lockstep 은 동영상이 느림, r1) | 원작 PSS |
| 2283–2440 | 케르베로스 + 새끼 떼, 2370–2385 어른 둘 · 셋, 2390 사망 → 2400 체크포인트 (CheckPoint1) → 다시 | DTre 081 · GrowTimer 085 · VomitSpawnTransform | 고치기 전: 어른 하나뿐, 깔려 물려도 HP 그대로 (r2) → 고친 뒤: 46–71 초에 새끼 여섯이 자람 · 60 초 토하기 (r6), 사망 → 같은 체크포인트에서 다시 (r3) | 고침 (40.1-1–5) |
| 2455–2490 | 제우스의 분노 · 영혼 | (마법) | 봇은 안 씀 | — |
| 2495–2550 | 밀기 상자 gopushy · 키클롭스 둘 (Spawner, R_CY3 Patrol10/12) | PushPull 097 · skellyLvl3GrpBox | r12: R2 잡고 밀기 20 초 (−832 → −1207), 키클롭스 순찰 | 같음 (밀기 구도 · 상자 · 초록 상자) |
| 2550–2620 | 키클롭스 처형 프롬프트 △ · □ | MAICY3MINI* TimedPress | 봇이 키클롭스를 못 이김 (r13, 잡기 거부) | [미확인] |
| 2600–2690 | 상자를 벼랑 아래로 밀고, 벼랑 위 궁수 열이 쏨 → 상자 위로 올라감 | timedArchers1–5 (034 do nothing) · shootTransmit 1019 45 | 고치기 전: 궁수가 경기장까지 내려옴 (r7) → 고친 뒤 벼랑 위에 남아 쏨 (r10) | 고침 (40.1-6) |
| 2690–2765 | 협곡 싸움 · 석상 문 (DMCCerebus 새끼 · ArchersDMC) | Spawner2 → DMCZone (LevelData19 = 20 → 문) | 몰지 않음 | [미확인] |
| 2770–2786 | 둥근 방: 구멍 속 레버 R2 → 철창 닫힘 → 방이 돎 | PAND02AA SpawnTrig → entitiesLever-VisON · leverGrate-AnimBack, 1023 (100, 60) → SpinRoom1-AnimForward · leverGrate · floorCogs · 1015 | r14: 같은 순서 · 같은 구도 (레버 5.6 초 잡음 → 9.3 초 1023) | 같음 |
| 2796–2803 | 감실 상자를 열자 창살이 내려오고 바닥 가시 → 사망 | (CSM 064 "BigRed2", 회전 벽 group1263–1266 pCube333–336 EvtDie 는 EventId 0) | 감실 앞에서 상자가 열리지 않았고 카메라가 회전 벽에 막힘 (r15) | [미확인] |
| 2805–2880 | 다시: 레버 → 철창 감실 · 붉은 오브 상자 | — | — | [미확인] |

### 40.1 엔진 결함과 수정
1. **새끼가 자라지 않았다 — SCR_GrowTimer 미구현 (영상 2370–2385: 경기장에 어른 케르베로스 둘 · 셋, 엔진은 처음 하나뿐).** 새끼의 생성 기술 SPAWNVOMIT 502 ·
   SPAWNVOMITLAND 506 의 SCRIPT 0x01EE9AD5 = Hash127Upper("SCR_GrowTimer") idx 85. 원작 [확실: 디스어셈블] 등록 0x190210 → 시작 0x190030: GrowChar/085
   "PuppyGrow" (R_CEP) 남은 시간 = +0x18 (30) + 난수 0x13E600 × +0x1C (0), 갱신 0x18FDD8 → 0x18F440: 매 프레임 1/60 (0x29D318) 빼고, 남은 시간 &lt; +0x24 (2 초)
   이면 "FX Name" goCEPtranSpark 을 몸에 붙여 세기 +0x20 + (1 − 남은/2) × (1 − +0x20) (0x18F664), 남은 &lt; 0 이면 0x209918 (지금 기술 끝) · 0x209378 (액션
   +0x2C u8 = 30 TRANSFORM, 무적) → 변신 0x18F798: p = 기술 진행률 (없으면 1), GO 행렬 세 축 × (1 + p × "Adult Scale" 1.5), p &gt; 0.95 (0x3F733333) 이면
   엔티티 "GrowAdult" (#556a7bc2) 를 0x1A90D0 으로 읽고 자리 (+2) 만 새끼 것으로, 새끼를 지우고 (0x1DFFA8) 정규화 행렬 (0x1853E8) 에 0x1A9298.
   → `combat/GrowTimer` (템플릿 · 시간 · 불꽃 세기 · 배율 · 0.95), `GameScene.growTick` (요청 · `EnemyFighter.growScale` · 지우고 GrowAdult), 새끼 제거는
   보통 제거 (`ScriptWorld.destroy` — DestructionSensor · Respawn) [추정: LevelData3/4 균형 — GrowAdult 는 Action 이 없고 Filter 3 이라 어른의 죽음은
   DeathDetectZone (Filter 2) 이 안 센다, 그러면 새끼 제거가 세어져야 DMC 조건이 맞는다]. 불꽃은 시작 때 한 번 [근사: 붙여서 세기 올림은 안 함].
2. **케르베로스가 새끼를 토하지 않았다 — 결정 트리 조건 25 · SCR_Spawn 미구현.** DTre 081 (AI1 081 "cerebus vomit"): 토큰 → c25(80) → c25(81) 참이면 공격 목록에
   VOMIT 4 (가중 15, 0–2000, 대기 5 초). 조건 25 = 0x1A1A80 [확실]: 인자 = Spawn 템플릿 → "Entity Name" 엔티티를 지금 레벨에서 찾아 0x1A8F70 (만든 수 +0x100
   &lt; MaximumCount — 0 이면 거짓 — 이고 템플릿 자원 있음 0x132728). 엔진은 25 를 보스 다리 (늘 0) 에만 물어 VOMIT 을 고를 수 없었다. VOMIT 4 @0.60
   SCRIPT 0x5BDB52BE = SCR_Spawn idx 81 → 0x19F388: Spawn/081 "VomitSpawnTransform" 을 소유 GO "Joint Name" synchJoint 의 월드 행렬에 0x1A9298
   (정보를 넘겨 MaximumCount 검사 · 만든 수 증가 없음, Action LevelData4 + 1 은 돈다) → 새끼 86 이 생성 기술 502 SPAWNVOMIT 으로 (그 기술이 다시 GrowTimer).
   Spawn 템플릿 플래그 셋 (바닥 붙임 · 대상 쪽 · 자리) 은 080/081 모두 0.
   → `AiBrain.setSpawnSense` (조건 25 = 인자), `ScriptWorld.canSpawn` · `spawnAt`, `GameScene.scriptSpawn` (관절 행렬, 적 갱신 순회 중이라 다음 틱 머리에 [근사]).
3. **케르베로스에게 깔려 물려도 HP 가 그대로였다 — 보스전 밖 영웅 SELF_HP 무시 (엔진 r2: 54–176 초 동안 MAICEALEAPBITE 381 만 되풀이, HP 가득).**
   영웅 기술의 SELF_HP (이벤트 10) 는 `BossFight.heroEvent` 에만 있었다. 원작 0x2069AC [확실]: b ≥ 0 이면 0x204690(…, 플래그 7) — 무적 검사 없음, 주인 HP 0 이면
   그대로, 피해 b (0x204774 의 배율은 신 모드 God Mode Enmy Dmg Mult 뿐 — 난이도 AI Damage Mult 아님), 음수면 0x1E1758 회복. 381 은 10 × 2 (0.40 · 0.54),
   HP ≤ 10 이면 382 MAICEALEAPDEATH (SELF_HP 200), "AUTO fall&gt;4" (흔들기 게이지 &gt; 4) 면 383 탈출. 같은 규칙의 다른 잡힘 기술: 하피 HARMAIPECK 373 (2 × 6),
   키클롭스 MAICY3MINIF0x · MAICYWMINI02F*, MAIMIN04, SKFMAIGRAB, 맞아 튐 HITBOUNCE 456 (15), GRABHEROFAIL 327 — 모두 예전엔 피해 없음.
   → `DamageRules.heroSelfHp`, `GameScene` 영웅 이벤트 (보스가 묶였으면 BossFight 가 그대로) [근사: 신 모드 감소 생략].
4. **흔들기 아이콘이 안 떴다 — SCR_FlashSprite (0x74B9D718) 미표시.** 0x19CD88 [확실]: 기술 SCRIPT 창 동안 HUD 아이콘 슬롯 4 = idx (0x21DA50(4, idx)), 끝 콜백
   0x19CD58 이 −1. 슬롯 4 는 0–3 (R2 · 안내 센서 · 잡은 TAndF · 미니게임 0x192xxx) 보다 커서 앞선다. 영웅 기술: 흔들기 12 = MAICEALEAPBITE · HARMAIPECK ·
   MAISKB20/30/31 · GRABHERO01 · 석화 FREEZEPOSE 120/121 · 분신 EVIGRABHEROLOOP 861, 연타 10 = MAIMIN02 · MAISATGRAB/STRUGGLE · MAIBARSHOWDOWN/SDSTRUG.
   → `GameScene.minigameIconIndex` 가 영웅 기술의 활성 SCR_FlashSprite 를 먼저.
5. [엔진 전용 봇] 잡힌 영웅 기술에 흔들기 분기 (방향 조건 7/8/15) 가 있으면 `AutoPad` 가 틱마다 스틱을 좌우 끝으로 (|Δ| 2 → 게이지 +0.4/틱) — 고치기 전 봇은
   381 에서 영영 못 나왔다 (r4: 게이지 0). 고친 뒤 r5: 54.25 초 381 → 54.45 초 383 탈출.

6. **벼랑 위 궁수가 걸어 내려와 영웅을 쫓았다 — 빈 결정 트리 (엔진 전체; 영상 2600–2685 은 협곡 벼랑 위에 줄지어 서서 쏜다, 엔진 r7 64 · 128–196 초는 열 명이 경기장까지 내려옴).**
   PAND02A timedArchers1–5 (Spawner1, R_SK2 AI1 034 "archer Lvl 2 -P") 의 DecisionTreeTemplate 034 = TWK_DTre_034 "do nothing", "DecisionTree" = 0. 엔진 `AiData` 는
   빈 블롭을 버려 트리 없음 → `EnemyFighter` 의 옛 규칙 (다가가 무작위 공격, 바닥 무시) 으로 걸어왔다. 원작은 결정이 없어 스스로 움직이지 않고 [추정: 노드 0 개 평가]
   스크립트 Spawner1-shootTransmit1–5 (1019 · 45 ADRAW, 0–2.5 초 난수 지연, 5 초 타이머 1050) 로만 쏜다. 같은 "do nothing": R_SKA 032 (ATHN01D/05C timedArchers ·
   ATHN05B MainRoofArchers), R_SK3 036 (HDES01C MovePlatAI), 시민 AOX* (공격 기술이 없어 영향 없음). → `AiData.emptyTree`, `EnemyFighter.doNothing` (바닥 · 중력만,
   기술 중엔 자동 조준 0x2077A0 으로 대상 쪽) [근사]. 고친 뒤 r10: 90 초 동안 벼랑 위 (y −295) 에 남아 쏨, ATHN05B 지붕 궁수 6 명도 제자리 (예외 0).
   (같은 궁수의 SpawnEnemy 슬롯 5 = 자리 그래프 Site204–213 (노드 하나, Radius 10) — 원작 이동 0x1D0E08 은 ai+0x72 0x40 이면 자리 그래프 A* (0x1CF020 → 0x1D4190)
   이고 엔진은 자리 그래프를 쓰지 않는다 [미구현, 32 문서 §대기 모드 와 같음].)

### 40.2 시험
`CerberusArenaTest` (6): puppyGrowTemplate (085: 30 · 0 · 0 · 2 · 1.5 · GrowAdult · 30 · goCEPtranSpark, 1800 프레임, 남은 1 초 세기 0.5, 0.95 초과 한 번),
growTimerCode (0x18F474 +0x24 · 0x18F9BC 0x3F73 · +0x28 · +0x2C · 0x209378/0x209918 · +0x18/+0x1C · 0x1A90D0 → 0x1DFFA8 → 0x1A9298), vomitNeedsSpawnerCondition
(DTre 081 c25 · VOMIT 4, DTre 080 없음, Spawn/081 VomitSpawnTransform · synchJoint), scriptSpawnAtMatrix (PAND02A: canSpawn, GrowAdult 7 번 = 최대 6 무시 · 만든 수
그대로 · 템플릿 81 · 기술 10 · 그룹 3 · Action 없음, VomitSpawnTransform 86 · 502 · 그룹 2 · LevelData4 + 1, VomitSpawn 없음), heroSelfHp (+ 0x207084 플래그 7,
0x19CDB4 슬롯 4), doNothingArchers (R_SK2 034 · R_SKA 032 · R_SK3 036 빈 트리, 033 은 트리). 엔진 시험 766 통과 (rebase 뒤).

### 40.3 남은 것 · 미확인
- **케르베로스 · 새끼 색**: 영상은 초록 빛 회색 살갗인데 엔진은 하얗게 날아간다 (`cerberus_color_engine.png`). 텍스처 CLUT 0 (초록) 을 쓰고 MAT_lambert2/3 의
  더할 색 12.8 (qw77) 은 작다 — 레벨 라이트 (PAND02A 44 개) 와 VU 조명 (47 문서) 의 밝기 쪽 [미해석, 고치지 않음].
- 새끼 불꽃 goCEPtranSpark 은 시작 때 한 번 (몸에 붙여 세기 올림 0x2279D0 · 0x227D80 없음), 변신 중 맞는 부피 · 내비 몸은 커지지 않음 [근사].
- SCR_Spawn 은 적 갱신 순회 중이라 다음 틱 머리에 만든다 [근사].
- 자리 그래프 (Site%d, 슬롯 5) 를 전투 이동에 쓰지 않는다 (원작 0x1D0E08 의 0x40 = A* 0x1CF020) [미구현].
- PAND02AA 감실 함정 (영상 2797–2801: 상자 열기 → 창살 · 바닥 가시 → 사망) 의 원작 장치 · 엔진 재현, 협곡 DMC (Spawner2 · DMCZone) · PAND02B 철창 방 · PAND00C 물 고리
  (1960–2210) 는 이번에 몰지 않았다.
- 키클롭스 처형 프롬프트 (2550–2620): 봇이 HP 를 깎지 못해 대조 못 함.

### 40.4 증거 (`work/verify/ep3_b/`, 커밋 안 함)
`video/` (영상 v<초> 333 장 + h<초> 1270 14 장), `video_1960-2255.jpg` · `video_2255-2319.jpg` · `video_2320-2490.jpg` · `video_2490-2880.jpg` (격자),
`pinned_before_r2.jpg` (깔려 물림 120 초, HP 그대로), `grow_engine_r6_43-51s.jpg` (새끼가 자라 어른 둘), `archers_before_r7.jpg` · `archers_after_r10.jpg`,
`push_block_r12.jpg`, `spinroom_r14.jpg`, `cerberus_color_engine.png`. 실행: `--game PAND02A --arrive PAND00C --skip-movies -Dgow.difficulty=1 -Dgow.auto=1`
(r6 봇), r10 `-Dgow.auto.novol -Dgow.auto.route=wait:1;-616,-380,-980!;wait:2`, r12 `…;-600,-380,-770!;wait:1;crank:20:-600,-832`, r14 `--game PAND02AA`
`…;-2186,-172,-1605!;wait:2;-2309,-236,-1047!;wait:1;-2300,-236,-890,20;use:-2319,-864;…`. 실제 세이브 위치는 생기지 않았다.

## 41. QA vs PS2 영상 3화 — 포세이돈의 시험: PAND02B 협곡 (해골 군단 · 좁은 턱 · 미노타우로스) → PAND02C 철창 방 · 병사의 쪽지 · 바닥 손잡이 · 크랭크 · 우리 밀기 · 불태우기 → PAND02D 삼지창 (영상 2880–3840 초, 2026-10-26)

영상 = HF4CELG5OxQ (3화, 전사 (보통) = `-Dgow.difficulty=1`), 프레임 423×317 (`work/verify/ep3_c/video/v<초>.jpg` 362 장 — 2880–3840 4 초 간격, 3176–3215 ·
3310–3370 · 3410–3428 · 3770–3810 1 초 간격, 커밋 안 함). 엔진 = origin/main d975335 위, 1280×720 lockstep, 스크래치 홈, 경로 하네스 (`-Dgow.auto.route`, 순간이동 ·
`use:` · `crank:` · `hold:` · `pull:` · `mash:` · `kill:25`, `-Dgow.auto.route.nofight`), `--game PAND02C --arrive PAND02B --skip-movies` / `--game PAND02D`.

레벨 [확실: 스크립트 · 배치]: PAND02B (협곡 — DiveEnemies 해골 군단 SkellyLevel3 · 세이렌 열쇠 상자 SirenKey1 · Narrow 벽 · Minos/MinotaurLevel5 템플릿 54 R_MI5)
→ athn02CWarp (LoadForWarp Pand02C) → PAND02C. PAND02C 퍼즐 (LevelData): 쪽지 deadGuyNote (R2 1024 → Goto noteRead · 430 두 쪽 · camNote) → 바닥 손잡이
GroundHandle (CSM 017 crankPickUpGround/pickUpCrank, 1023 인자 1 = 123 → LevelData13 · 431) → 크랭크 CageTriggerHandle (R2 1024 GotHandle → HandleOn, Push 013
Type 1 크랭크 id 230, 1023 → LevelData15 · 18 → CageElevator AnimDriven · cageLower01, 100 % → cagePush01 · fadeOut 0.6 → VisDump02Mp02 동영상 p02Mp02 →
501 → FadeIn · CamOff CheckPoint) → 우리 gopushpullcage (Push 006 id 221) 를 철창 방 턱에서 아래 복도로 떨어뜨려 오르막을 밀어 올림 (EnemyZoneGroup0–3 해골
SkellyLevel3 템플릿 25 · Patrol10–14 · 미끄러짐 소리 볼륨) → 판 plateTRIGgrp CageEntry (LevelData10, 함정 · 불 구멍 AnimForward, CagePlate AnimUp) → 레버
golever (Push 012 id 81, fryAttach · taskP → LevelData11 · 21) → LeftFire/RightFire · CgeFRYing 카메라 (Idle 8 초) · cageBurn02 → +3 초 GearsTrap · CageLift2
AnimSink (LevelData22) → +4 초 TraproomDoor AnimOpen (LevelData16 = 문 메시지 끝) · lever-VisCheck CheckPoint → LoadZoneFor2D → PAND02D tridentZoom (Idle 4 초,
camTridentZoom) → 삼지창 TAndF 205 id 141 (camTridentLift, 1023 → GlobalData0 = 100 → 카드 155 · 184 · CheckPoint 3.2) → R2Statue 144 → 세이브.

### 41.1 장면 대응
| 영상 (초) | 장면 | 원작 (스크립트·데이터) | 엔진 (실행) | 결과 |
|---|---|---|---|---|
| 2880–2970 | 모래 마당 · 탑, 낫을 든 해골 군단 (아르테미스의 검) | PAND02B DiveEnemies SkellyLevel3AI1–3 (DiveSpawner 셋) | b1 봇 (`b1s.jpg`): 같은 마당 · 같은 해골 | 같음 |
| 2944–3024 | 절벽 오르기 · 뮤즈의 열쇠 카드 · 사슬 · 좁은 턱 | SirenKey1 (CSM 022 상자) · Narrow 벽 18 자리 | b2 f4 열쇠 상자 자리만 | [미확인: 몰지 않음] |
| 3032–3128 | 협곡 길, 가시 철퇴 · 방패의 보라 미노타우로스, 제우스의 분노, 올라타기 처치 | Minos MinotaurLevel5AI1/2 · AI3 (템플릿 54) | b2 f100–116 (`b2s.jpg`): 같은 길 · 같은 모델 | 같음 (싸움 시간 비교 안 함) |
| 3136–3216 | 어두운 복도 · 화로 → 세이브 빛기둥 R2 · 업그레이드 메뉴 | PAND02C 세이브 · 메뉴 (1 · 2 차에서 대조) | — | 몰지 않음 |
| 3216–3244 | 병사 석상 부수기 · 우리 방 들어가며 매달린 우리들 (camCUTCGEFLY) | gostatuesoldierbrk 13 · animCUTSCENE1 (10.5 초 Idle) · cageHang RedHang2–18 | c1 · c2 (`c1s.jpg`): 같은 방 · 같은 카메라 | 같음 |
| 3256–3308 | 복도 싸움 → 포세이돈 문 방 (손잡이가 바닥 고리 옆) | — | c2 f30 | 같음 |
| 3316–3331 | 쪽지 R2 → 시체 클로즈업 → 벽 쪽지 · 투구, 430 두 쪽 | deadGuyNote → camNote (godeadguynote 관절 1, ANM deadGuyNote 5 초, 0x1000) | c2 f16–26 (`cmpnote.jpg`): 같은 메시지 · 쪽 · 타자, **카메라는 시체 무릎에서 몸통으로만 가고 벽 쪽지에 닿지 않는다** | 41.3 [미확인] |
| 3340–3349 | "바닥에 손잡이가 있습니다." · 집어 듦 | GroundHandle 431 | c2 f40–46 | 같음 |
| 3352 | 판을 밟자 양옆 불 구멍이 확 | plateTRIGgrp PlayerEntry → L/RFlamSnd AnimForward | c12 117.4 초 같은 사건 (로그) | 같음 |
| 3358 | 문 메시지 121 (camPosdoor) | DoorMsg ENV 구 반지름 640 × 배치 0.0625 = 40 | c2 (−340 에서 44 떨어져 안 뜸 = 볼륨 밖) | 데이터대로 (하네스 위치) |
| 3376–3408 | 손잡이를 크랭크에 끼우고 돌림 → 우리가 복도로 내려옴 | GotHandle · Push 013 Type 1 (손잡이 관절 handle1/2 (−2048, 388, −3245) 축 ±x) · CAMcrankPush | c3 (`c3s.jpg` · `c3m.jpg` f84) | 같음 |
| 3410–3428 | 크레토스 · 우리 속 병사 클로즈업 | 동영상 p02Mp02 | c3 f87–190 동영상 | 같음 |
| 3432–3712 | 우리를 아래 복도로 떨어뜨림 → 오르막으로 밀며 해골 · 궁수와 싸움 | 미끄러짐 볼륨 · 해골 4 무리 · 돌 더미 · 벽 턱 | c4–c12 (`c4s.jpg` · `c9s.jpg` · `c10s.jpg`): 같은 낙하 (바닥 368 → 240) · 오르막 | 같음 (41.2) |
| 3720–3740 | 우리를 판에 → 레버 당김 | CageEntry · lever fryAttach / taskP | c12 116.6 · 118.5 초 | 같음 |
| 3740–3748 | 불 구멍 → 우리 속 병사가 불탐 (camCGEFRY1) → 빈 판 | CgeFRYing CamOn (Idle true) · cageBurn02 · CageLift2 AnimSink | c12 (`c12f.jpg` · `c12g.jpg`) 같은 순서 · 시각 (122.2 · 123.2 · 126.2 · 130.2) | R2 고침 (41.2-1), 불 밀도 41.3 |
| 3752–3768 | 열린 문 → 나무 들보 복도 → 삼지창 방 (camTridentZoom) | TraproomDoor · LoadZoneFor2D · tridentZoom Idle 4 초 | d1 (`d1s.jpg`) | 같음 |
| 3770–3790 | 삼지창 R2 연타 (camTridentLift, R2 표시) → 카드 155 → 184 | TAndF 205 · GlobalData0 | d1 f58–94 | 같음 |
| 3798–3808 | 석상 R2 144 (camPosStatue) → 세이브 | R2Statue · SaveGameZone | d1 41.5 · 54.2 초 | 같음 |
| 3810– | 물에 뛰어들어 잠수 | 삼지창 = 잠수 능력 (bd6a17a, 43 §42) | d2: 삼지창 뒤 □ 로 212 → 131 잠수 | 같음 |

### 41.2 엔진 결함과 수정
1. **불태우기 연출 (Idle) 동안 레버 옆 영웅에게 R2 안내가 떠 있었다 (영상 3740–3748 은 없음).** 레버는 당긴 뒤 0.5 초에 되감겨 (lever-EventReset 1015) 다시 잡을 수
   있는 Show R2 오브젝트라 엔진 `minigameIconIndex` 의 근접 안내가 CgeFRYing 카메라 (CamOn H13 = Idle(true), CamOff = Idle(false), 8 초) 내내 R2 를 띄웠다.
   원작 근접 안내 0x1DC3F8 [확실: 디스어셈블]: 0x1DC450 이 0x1BF108 (= lw 0x29DB58, 스크립트 Idle 의 전역 정지) 을 부르고 참이면 0x1DC610 으로 건너뛰어
   잡기 질의 0x1C3F18 · 세이브 포인트 거리를 보지 않고, s1 = 0 이라 0x1DC648–0x1DC654 가 슬롯 0 에 −1 (0x21DA50(0, −1)) — 안내를 지운다. 엔진은 이 조건을 세이브
   포인트 안내에만 걸고 있었다 → `MinigameIcon.useQueryOpen(전역 정지, 메시지)` 를 근접 오브젝트 · 세이브 포인트 둘 다에 (메시지 중 아이콘을 다시 고르지 않는 0x21E908
   조건도 같이). 연출이 끝나면 (130.2 초) 레버 옆에서 R2 가 다시 나오는 것은 원작과 같다 (되감긴 레버는 잡힌다).
   엔진 전역: Idle 연출 중 Show R2 오브젝트 · 세이브 포인트 곁에 선 장면 모두.

- 결함 아님 (하네스 · 경로) [확실: 데이터]: 크랭크 CageTriggerHandle 의 관절 0 은 GO 배치에서 758 떨어진 (−1290, 416, −3231) 이고 잡기는 손잡이 관절 handle1/2
  (축 ±x) 로만 된다 — 크랭크 팔 옆에 서야 잡힌다. 문 메시지 DoorMsg 구는 배치 배율 0.0625 로 반지름 40. 우리는 복도의 벽 턱 (−1547, z −3136) · 돌 더미 (−1100,
  z −3200) · 레버 기둥 (정적 충돌 x −478, z −3250…−3221, 높이 104) · 판 감실 기둥 (x −396) 에 막히므로 당기기로 옆으로 옮겨 가운데 (z −3188 → −3232) 로 넣어야 한다
  (영상 3480 · 3552 · 3724 도 우리를 가운데로 돌려 민다). 해골이 우리 앞에 서면 막힌다.

### 41.3 남은 것 · 미확인
- **쪽지 카메라 camNote (영상 3319 → 3322–3331)**: 원작은 시체 무릎에서 벽에 붙은 쪽지 (그리스 글자) · 오른쪽 투구로 올라간다. 엔진은 godeadguynote 관절 1 을
  ANM deadGuyNote 대로 (눈 (−453.8, 494.5, −3241.2) → (−456.2, 480.6, −3300.1), 아래 21° → 위 4°) 따라가 시체 몸통 앞에서 끝난다. GO 배치가 y 축 180° · 이동
  (−896.7, 0, −6464) 인 반전 배치라 관절 트랙 · 배치 곱 순서 또는 카메라 0x1000 재시작 시점 해석이 다를 수 있다 [미확인] — 이번에 고치지 않았다.
- 불태우기 클로즈업 (영상 3744: 우리 속이 불길로 가득) 은 엔진이 벽 불 구멍 불길 · 병사만 보이고 덜 짙다. 병사 GO 의 FXC_RSF*/RSFA* 이미터는 서브타입 7 (생성률 0, act
  채널 구동) 이고 엔진 GO 이펙트의 서브타입 5–9 갱신은 미해석 (36.4) [근사].
- 메시지 상자 글자 색 (영상 짙은 적갈색, 엔진 주황) · 상자 너비는 1 · 2 차 대조 그대로 두었다.
- PAND02B 좁은 턱 · 사슬 오르기 · 세이렌 열쇠 상자 (2944–3024), 세이브 · 업그레이드 메뉴 (3176–3215), 협곡 싸움 시간, 해골 · 궁수 수 · 출현 시각은 나란히 몰지 않았다.
- 하네스: 경로 순간이동으로 PAND02C 에서 PAND02D 로 넘어가면 LoadCheck 를 지나지 않아 지금 슬롯이 PAND02C 로 남는다 (`--arrive PAND02C@…` d1 첫 실행의 세이브가
  PAND02C 로 기록) — 걸어서 넘어가는 원작 경로와 무관 [엔진 전용 하네스].

### 41.4 시험
`CageSacrificeTest` (2): useHintIsClearedDuringScriptIdle (0x1DC450 jal 0x1BF108 · 0x1DC458 bne → 0x1DC610 · 0x1BF108/0x1BF110 = 0x29DB58 · 0x1DC650 movz ·
0x1DC654 jal 0x21DA50, PAND02C CgeFRYing CamOn/CamOff Idle true/false, lever-EventReset 1015, `useQueryOpen`), cageCrankHandlesSitOnTheArm (handle1 (−2048, ·, −3245) 축 −x,
관절 0 −1290). 엔진 시험 전부 통과.

### 41.5 증거 (`work/verify/ep3_c/`, 커밋 안 함)
`video/` (영상 362 장), `vs1.jpg`–`vs9.jpg` (영상 시트 2880–3810), `b1s.jpg` · `b2s.jpg` (PAND02B 해골 · 미노타우로스), `c2n.jpg` · `cmpnote.jpg` (쪽지 카메라 대조),
`c2t.jpg` · `c3s.jpg` · `c3m.jpg` (손잡이 · 크랭크 · 동영상), `c4s.jpg` · `c9s.jpg` · `c10s.jpg` (우리 낙하 · 막힘), `c12s.jpg` · `c12f.jpg` (고치기 전: 연출 중 R2),
`c12g.jpg` (고친 뒤: 연출 중 R2 없음, 끝난 뒤 레버 곁 R2), `d1s.jpg` (삼지창), `route/` (경로 c2–c12 · d1 · d2 · b2).

## 42. QA vs PS2 영상 3화 — 포세이돈의 시험 물 구간: PAND02D 수영장 · 돌진 문 → PAND02DD 새끼 케르베로스 동굴 → PAND02F 물속 홀 · 나이아드 · 빛줄기 방 → PAND02G 붉은 결계 방 → PAND02H 물속 큰 홀 → PAND02I 기둥 · 화로 방 · 세이브 → PAND00D (영상 3840–5760 초, 2026-10-26)

영상 = HF4CELG5OxQ (3화, 전사 (보통) = `-Dgow.difficulty=1`), 프레임 423×317 (`work/verify/ep3_d/video/v<초>.jpg` 60 장 — 3840–5720, 커밋 안 함).
엔진 = origin/main fdd299f → 8ab6023 위, 1280×720 lockstep, 스크래치 홈, `--game PAND02D/02DD/02F/02G/02H/02I --skip-movies` + 경로 하네스
(`-Dgow.auto.route`: 순간이동 · `keys:초:SQUARE` 잠수 · `keys:초:R1` 돌진 충전 · `die:`) 또는 봇, `-Dgow.giveAll` (이제 삼지창 포함).

레벨 [확실: 스크립트 · 충돌]: PAND02D 포세이돈 방 (삼지창 TridentGroup, 메시지 155 · 184 · 526 헤엄치기) 의 수영장 (Water y 240, x 768–1792 · z −3296…−2720) 바닥
구멍 (960, −2830) → 관 (cam2Tunnel · camUnderWater) → 돌진 문 godashdoorgrp / 1 / 2 (z −3393 · −3847 · −4303, 메시지 525 "R1 을 누르고 있으십시오") → PAND02DD
얕은 물 동굴 (NoDiving y 8, 새끼 케르베로스 R_CEP 85 × 8 · GrowAdult) → PAND02F (입구 웅덩이 −641, 물속 홀, 나이아드 NyadAI4–7 = R_NYA 템플릿 100 (체력) ·
101 (마법), 물속 체크포인트 CheckPointGroup6 (−212, −950, 2112) · 7 (10, −1245, 3583), 세이브 (612, −1518, 4766)) → PAND02G (붉은 결계 · 하피 · 군단병, 물 −992)
→ PAND02H (물속 큰 홀, 빛줄기) → PAND02I (기둥 · 화로 · 어른 케르베로스, 세이브) → LoadyToHub → PAND00D.

### 42.1 장면 대응
| 영상 (초) | 장면 | 원작 (스크립트 · 데이터) | 엔진 | 결과 |
|---|---|---|---|---|
| 3840 | 수영장 구멍 위에서 내려다본 수면 (옅은 하늘색, 바닥 구멍이 비침) | gowaterdynamic SCR_Water 5 개 (관절 1–5) + golocator6, MAT_reflenvmb (TXR_Water1_text 가운데 하늘색 · 바깥 갈색) | 고치기 전: 구멍 위 수면 없음 (어두운 물), 다른 곳은 갈색 얼룩이 흐름 → 고친 뒤 같은 옅은 수면 (`p_pool_3840.jpg`) | 고침 (42.2-3 · 4) |
| 3850 | 사다리로 물 밖 | camLadderR/L | d1 사다리 근처 | 같은 방 |
| 3880 | 물속 얼굴 문 + 메시지 525 | dashDoorGrp-volume-EntryMSG, Breakable 문 | 같은 문 · 메시지. 고치기 전: 돌진 없이 얼굴을 지나 헤엄침 → 막고, 충전 돌진 (18 m/s 초과) 에 부서짐 (`dashdoor_*.jpg`) | 고침 (42.2-2) |
| 3900–3960 | 얕은 물 동굴 · 새끼 케르베로스 불덩이 | PAND02DD CerPupEnemies 85 | dd1 봇: 같은 동굴 · 새끼 · 불덩이 (`dd_pups.jpg`) | 같음 |
| 4000–4020 | 관 속으로 잠수 (위에서 내려다봄) | cam2Tunnel (아래를 봄) | d3 f024–036 같은 구도 | 같음 |
| 4200–4340 · 4540–4580 | 물속 홀 · 계단 · 가시 벽 · 돌 더미 | PAND02F/02G 물속 | f1 물속 청록 안개 · 거품 | 비슷 (색 · 안개) |
| 4260 · 4280 · 4760–4820 · 4980 | 나이아드 (푸른 반투명 형체) 가 물 가운데 떠 있음, 메시지 445, R2 | NyadAI4–7 (자리 243–246, y −948 …) | **엔진은 바닥 (−1282 …) 까지 가라앉아 흰 형체로 누움** (`naiad_floor.jpg`) | 42.3 [미확인] |
| 4360–4460 | 붉은 결계 방 싸움 | PAND02G | g1 봇 같은 방 · 결계 · 적 (`g_redroom.jpg`) | 같음 |
| 4860–4940 | 둥근 방 빛줄기 · 매달린 석상 | PAND02F 빛줄기 방 | f1 f098–134 같은 빛줄기 · 석상 (`p_light_4860.jpg`) | 같음 |
| 5120–5180 | 어두운 물속 큰 홀 | PAND02H | h1 같은 어두운 청색 · 빛줄기 | 같음 (봇은 지상으로 시작 — 42.3) |
| 5280–5420 · 5560 | 기둥 · 화로 방, 어른 케르베로스, 세이브 | PAND02I | i1 봇 같은 방 · 적 | 같음 |
| 5680–5720 | 둥근 다이얼 방 | PAND00D (LoadyToHub) | — | 이 구간 끝 (§43 이 이어받음) |

### 42.2 엔진 결함과 수정
1. **삼지창 전에도 잠수 · 돌진이 됐다.** 원작 [확실: 디스어셈블]: 잠수 (□, 0x1C9CC8 / 0x1C9D18) 는 영웅 +0x2DC 0x20, 돌진 (R1, 0x1C9F58) 은 0x200000 을 본다.
   AddItem 0x1D8CF0 의 점프 표 0x2B8190 [아이템 − 3] 에서 **아이템 9 (포세이돈의 삼지창, Internal 37 — PAND02D VisTridentPopUp `set_bool Internal:37`) → 0x1D8FDC 가
   +0x2DC |= 0x200020**, DeductItem 0x1D9168 (0x1D91C8) 이 끄고, 영웅 생성 0x1DC21C 가 보유 비트 0x3044C2 &amp; 0x100 으로 정한다. 엔진은 "처음부터 능력 있음"
   [추정] 이었다 (38 §11). → `HeroNavigator.diveAbility` (기본 참 = 시험), `HeroRig.update` 가 매 틱 `PlayerState.ITEM_BIT_TRIDENT`, `-Dgow.giveAll` 이 아이템 9 도.
   영상 2화 3744–3761 (PAND00D 눈 방 웅덩이, 삼지창 전) 도 크레토스는 수면만 헤엄쳐 배수구 위를 지난다 — 같은 규칙.
2. **PAND02D 돌진 문을 그냥 헤엄쳐 지나갔다 (영상 3880: 얼굴 문 앞에서 R1 안내).** 문 godashdoorgrp* = Breakable (Brkb 0 "No Orbs", HP 1) + CDV 공 하나 (중심 (0, 71, 0.45)
   반지름 56, 재질 Ground · TakeDamage — NoPlayerCollision 아님). 원작 Breakable 접촉 0x1DE4D8 [확실] (충돌 접촉 0x203AF0 → 0x203CD4 가 부름): 공격 레코드
   (0x209AB0) 가 없으면 접촉한 생물이 **돌진 중 (+0x2D8 0x80000)** 이고 |속도 +0xE0| × 1/16 &gt; Sold+0x214 (크라토스 **18 m/s**, 0x1DE640) 이면 피해 1000
   (0x447A0000) → 0x1DDDF0, Brkb +0x30 bit 2 면 진동 (Sold+0x210). 몸이 충돌 인스턴스에 닿아야 오는 접촉이라 공도 몸을 막는다 (공 질의 0x139C20 — 인스턴스 +0x3C 공 수,
   +0x88 공 월드). 엔진은 (a) 평면 hull 없는 CDV 를 몸에서 뺐고 (41 §2.1), (b) 수영 중 몸 충돌이 정적 시트만 봤고 (`NavWorld.collideSphere`), (c) 돌진 접촉 파괴가 없었다.
   → (a) `Platforms.breakableBalls` · `pushOutBalls` (Breakable 의 공 CDV = 몸 구 밀어내기, 재질이 몸 제외 마스크면 지나감) [근사: 바닥 · 선분 질의 없음],
   (b) `HeroNavigator.swim` 이 `collideBodies` 도 (문 · 창살 · 공), (c) `NavTuning.DASH_BREAK` (0x214) · `HeroNavigator.dashContactSpeed` · `swimBodyCenter` ·
   `Breakables.dashContact` (몸 구와 물체 판정 구가 맞닿고 속도 &gt; 288 → 1000), `GameScene` 이 두 슬롯 물체에. 반쯤 충전 (0.75 초 → 16.5 m/s) 은 못 부수고
   0.82 초 넘게 충전해야 부서진다. 확인 d4: 문 앞 −3331 에서 멈춤 → 1.2 초 충전 돌진 "파괴: godashdoorgrp" → 둘째 문 0.75 초 돌진은 튕김 → 1.2 초 돌진 "파괴: godashdoorgrp1".
3. **수면 반사가 갈색 얼룩으로 흘렀다 (영상 3840 은 옅은 하늘색).** 원작 갱신 0x21A888 [확실: 0x21AA0C – 0x21AB3C]: D = 정점 − 카메라 위치 (카메라 관리자 0x29F16C +8 의
   월드 행렬 행 3, 0x219DC8), 법선 N = (h[x−1] − h[x+1], **4·칸**, h[z−1] − h[z+1]), 반사 R = D − 2N(D·N)/|N|², r = R/|D|, m = √(rx² + (ry+1)² + rz²),
   ST = 2048 + 2048·(rx, rz)/m (4096 = 1) — **카메라 반사 벡터의 위쪽 축 구면 환경맵**. 위에서 보면 가운데 (밝은 하늘색), 비스듬하면 바깥 고리 (갈색).
   엔진은 0.5 + 4·법선 + 0.02·격자 번호 [근사] 라 격자를 따라 텍스처 바깥 갈색이 얼룩으로 깔렸다. → `WaterSurface.sphereUv` (법선 y = 4·칸 — 예전 2·칸 의 절반 기울기).
   메시 만들기 0x21A1D0 의 **물 정의 +0x0C (16 §1.6 의 "플래그 미상") = 1 이면 내접 타원 밖 칸에 ADC 0x8000** (0x21A454 – 0x21A4F0) → `WaterSurface.insideEllipse`
   (PAND02D 분수 gowaterfountain 8.88 × 8.88 이 둥근 물).
4. **GO 하나의 SCR_Water 여럿 중 첫째만, 그것도 엉뚱한 자리에 그렸다 (구멍 위 수면이 없었다).** PAND02D gowaterdynamic 그룹에 SCR_Water 인스턴스 5 개 —
   `EnvScript.owner` 가 "바로 앞 리소스" 라 둘째부터 소유가 " SCR_Water" 가 되어 위치를 못 찾고 버려졌다. 위치는 SCR_Water 0x219D88 이 GO 애니 인스턴스 (+0x104)
   가 있으면 0x136BD8(인스턴스, 스크립트 +0x46) = **헤더 +0x22 u16 관절** 의 월드 위치 (SCR_GhostTrail 머리 관절과 같은 필드, 38 · GhostTrail) — 다섯 = MDL_WaterDynamic
   관절 1–5 (1248, −3296) · (1728, −3296) · (768, −2928) · (1248, −2928) · (1728, −2928). 엔진은 관절 0 (1249.7, 292.2) 이었다. → `EnvScript.owner` 가 앞 스크립트를
   건너뜀, `EnvScript.joint` · `EnvParams.Water.joint`, `LevelFx.ownerPosition(…, joint)`. 전 WAD 에서 소유가 바뀌는 것은 이 다섯뿐 (`MultiScr` 조사).
5. **물속 체크포인트 · 순간이동 뒤 크라토스가 바닥을 걸어 다녔다.** 원작 세이브 · 체크포인트 되읽기 Load_PlayerBlock 0x1DC130 은 이동 상태 +0x174 (= 스냅숏 +0x40) 를
   그대로 둔다 [확실, 38 · 43 §26] — 물 0x80 · 0x100 · 0x200 도. 엔진은 `NavState.SWIM` 이름만 저장하고 되읽을 때 지상 / 공중으로 (`reset`) 두어 PAND02F 물속 체크포인트
   (CheckPointGroup6 · 7) 에서 다시 하면 가라앉아 걸었다. → 스냅숏 · Warp 이동 상태 "SWIM:하위 상태" (`GameScene.navStateName` · `swimState`),
   `HeroNavigator.restoreWater` (수면 = 위쪽 첫 Water 면, 없으면 다음 수면 질의가 정함 [근사: 원작 스냅숏에 +0x230 이 있는지 미확인]). 하네스 경로 순간이동도 물 상태를 잇는다.
   확인 f2: CheckPointGroup6 스냅숏 (−205, −950, 2108) → `die:` → "이동 상태 SWIM:DIVE → 물", 잠수 유지.
6. **수면 질의가 처음 들어간 수면 둘레만 봤다.** 원작 0x1E57D8 [확실]: 새 위치 + (0, 키·0.8 − 바닥 = 21.76) ± d (d = 2·max(이동, 3.2)) 선분이 Water·NoDiving 면에
   맞으면 +0x230 = 그 높이 — 머리 근처의 수면이 곧 수면이다. 엔진은 "지금 수면 ± 8" 만 따라가 물 높이가 다른 웅덩이 (PAND02F −641 → −1104 · −1184 · −984 · −1091)
   로 헤엄쳐 오르면 그 위 공기 속을 헤엄칠 수 있었다. → 발 + 28.16 ± d 질의를 더함.

### 42.3 남은 것 · 미확인
- **나이아드가 바닥에 누워 있다 (영상: 물 가운데 떠 있는 푸른 반투명 형체).** R_NYA 템플릿 100/101 = 이동 능력 Swim (0x10) 만, 결정 트리 100, Sold_100. 생성 위치
  (자리 243–246, y −948 · −1278 · −1381 · −1469) 는 물 가운데이고 둘레 40 안에 바닥이 없다 (천장 −820 만). 원작 생물 갱신은 Swim 능력이면 이동 뒤 물 상태 0x1C9BB8 을
  부르지만 (0x1CADD8 · 0x1EBD10), 물 밖 → 물 들어가기 (0x1CA098) 도 머리 근처 수면 질의라 수면 없는 깊은 물속에서는 맞지 않는다 — 원작이 어떻게 띄우는지 (시작 상태 ·
  결정 트리 · 애니) [미확인]. 색도 엔진은 흰 불투명, 영상은 푸른 반투명 (재질 · 가산) [미확인]. R2 나이아드 상호작용 (310–312 기술, 메시지 445 · 456 · 457) 은 몰지 않았다.
- 레벨을 물속 Start 로 바로 시작하면 (PAND02H `--game`, 봇) 지상으로 시작해 바닥을 걷는다 — 원작 경로는 헤엄쳐 이어 들어오고 (이음은 이동 상태를 잇는다) 하네스만의 일.
- `--game PAND02D` 이후 레벨을 `-Dgow.giveAll` 없이 바로 시작하면 삼지창이 없어 잠수 · 돌진이 안 된다 (원작 규칙) — 자동 순회 · 다른 QA 하네스는 giveAll 을 쓸 것.
- 공 CDV 몸은 Breakable 의 것만 (항아리 ARENA06 반지름 16 도 이제 몸을 막는다) — 비헤이비어 없는 공 CDV (gobarrel* 등) 는 그대로 몸이 아니다 [근사: 원작은 모든 CDV 공이
  충돌 인스턴스]. 공은 바닥 질의 · 이동 선분 질의에는 없다.
- 영상의 돌진 문이 부서지는 프레임은 대조하지 않았다 (3880 앞뒤 프레임 없음). 엔진 파편 = Breakable 파괴 act (`dashdoor_after.jpg` f078).
- 영상 3880 · 4260 메시지 상자는 화면 아래 폭 넓은 양피지, 엔진은 가운데 아래 좁은 상자 — 이 구간에서 고치지 않음 (UI 공통).
- 수면 바닥의 어두운 얼룩 (d1 f028 · f070) 은 평평한 법선으로 그려도 남아 물 아래 바닥 · 장식으로 본다 [추정].

### 42.4 시험
`nav/PoseidonWaterTest` (6): tridentGrantsDiveAndDash (점프 표 0x2B81A8 → 0x1D8FDC, 0x200020, 0x1DC220 andi 0x100), noDiveWithoutTrident, surfaceFollowsPoolAboveHead
(웅덩이 A 0 → B −200), checkpointRestoreKeepsDive (삼지창 없으면 SINK), dashDoorBallBlocksBody (PAND02D 문 셋 · 밀어내기 · 부서진 뒤 통과), dashBreakThreshold
(0x1DE600 · 0x1DE640 +0x214 · 0x447A, Sold 18 → 288, 반 충전 &lt; 288). `fx/PoseidonWaterFxTest` (4): sphereMapUsesViewReflection, ellipseWaterDropsCorners,
ellipseAndSphereMapCode (0x21A45C +0xC · 0x8000 · 0x4500 · 0x29F16C), multipleWatersOnOneObject (관절 1–5 · 분수 타원). 엔진 시험 764 개 통과 (8ab6023 위).

### 42.5 증거 (`work/verify/ep3_d/`, 커밋 안 함)
`video/` (영상 60 장), `eng/p_pool_3840.jpg` (영상 ↔ 고치기 전 구멍 위 수면 없음 ↔ 고친 뒤), `eng/pool_before_uv.jpg` · `pool_after.jpg` (갈색 얼룩 → 옅은 수면),
`eng/dashdoor_before.jpg` (얼굴 문을 지나 걸음/헤엄) · `dashdoor_after.jpg` (막힘 → 돌진 파괴), `eng/p_light_4860.jpg` (빛줄기 방), `eng/naiad_floor.jpg` (바닥의 나이아드),
`eng/g_redroom.jpg` · `dd_pups.jpg`.

## 43. QA vs PS2 영상 3화 — 하데스의 시험: PAND00C 허브 잠수 → PAND03J 폭포 웅덩이 · 통나무 홀 · 세이브 → PAND03A 하데스의 홀 (피 웅덩이 · 제물 제단 · 켄타우로스 · 하데스의 원) → PAND03B 미로 → PAND03A 석상 머리 크랭크 · 붉은 방 세이브 → PAND03C 문 홀 · 굴러오는 불덩이 (영상 5760–7723 초, 2026-10-26)

영상 = HF4CELG5OxQ (3화, 전사 (보통) = `-Dgow.difficulty=1`), 프레임 423×317 (`work/verify/ep3_e/video/v<초>.jpg` — 5770–7680 10 초 간격, 5815–5832 1 초,
6375–6385 · 7525–7535 · 7665–7720 보탬, 커밋 안 함). 엔진 = origin/main fdd299f 위, 1280×720 lockstep, 스크래치 홈, `--skip-movies`, 레벨마다 `--game L`
(+ `--arrive PAND00C`), 경로 `-Dgow.auto.route` · 봇 `-Dgow.auto=1` (90 초 순회: PAND03B/03C/03D/03E/03F/03H/03I/03J).

경로 [확실: 스크립트 · 영상]: 5770–5823 PAND00C 허브 웅덩이로 잠수 · 관 (Lvl3TubeCam, Lvl3Warp-WarpVol → Warp) → 5826 PAND03J WarpFromHub (CoreTubeCam4) 폭포
웅덩이 → 5830 복도 · 5840 해골 문장 문 (MinotaurLock) · 5850 크랭크 → 5860–5880 용암 커튼 홀 · 불타는 통나무 → 5920 세이브 (663, −96, 1308) → 5940 머리 장식 복도
→ 5950–5960 PAND03A 하데스의 홀 · 구역 제목 13 "하데스의 시험" · 피 웅덩이 둘 (gosac6loop1/2, z −224 / 240) → 6020 제물 제단 (KeyDoorHoriz, 메뉴 "하데스가 당신이
지나가기 전에 그의 제단에 피의 제물을…" 예 = 1021 (1, 1011)) → 동영상 p03MP98 · 켄타우로스 CentaurLevel1AI2–5 (R_CNM 90) 계단 → 6100–6220 하데스의 원 (Sac6Loop
On · AnimRing · $sacCir 재질 애니, 원 안 처치 LevelData7/8 > 3) → 6240–6400 미로 PAND03B (6250 "더 이상 전진할 수 없습니다. 어두워서…", 바닥 용암 칸 · 6375–6385
사망 · 체크포인트) → 6400–7400 미로 (메두사 R_ME2 · 해골 궁수 · 빛줄기 · 둥근 창살 바닥 기둥 6820/6920–6960 · 6880 업그레이드 · 7000–7040 붉은 하데스 석상 R2
(HeadMsg 146) · DoorText 피 글자) → 7420–7480 PAND03A 위층 거대 석상 머리 · R2 크랭크 (S1CrankHead) → 7500–7520 붉은 방으로 떨어짐 (PressPlate 마커 → SaveGameZone
(−66, 0, −266) 생성) → 7540–7620 세이브 ("하데스의 홀") → 7640 사다리 → 7660–7720 PAND03C 문 홀 · 불덩이가 계단으로 굴러옴. PAND03G (보스 이음, §27.6) 는 이 구간에 없다.

### 43.1 엔진 결함과 수정
1. **컨텍스트 그룹 밖 템플릿 GO 를 레벨에 만들었다 — PAND03A 홀의 검은 파편 구름 · 불덩이 · 피 웅덩이 위 통나무 (영상 5950–5960 에는 없음, 엔진 전체)** (4bc5f0e)
   [확실: 호출 그래프 · 데이터]. PAND03J golog · gologshatter 는 원점 (0, 0, 0) 에 놓였고 어느 `GROUP{ RES CXT_x … RES go… }` 에도 없다 — TWK_Arrow_060
   (BMILogLauncher) 의 "GO Name" = golog, "Hit Effect" = gologShatter, 즉 통나무 화살과 명중 효과의 **복사 원본**. 원작 GO 생성 0x1307C8 의 유일한 호출자는
   컨텍스트 클래스 (생성자 0x133C58, vtable 0x2C1D70) 메서드 0x133E30 (컨텍스트 +0x8C 목록에 만든다) — 컨텍스트에 없는 인스턴스는 GO 가 되지 않는다.
   엔진은 모든 배치를 그리고 FXC 이미터를 만들어, PAND03J 가 이웃 슬롯일 때 PAND03A 홀 원점 (두 웅덩이 사이) 에서 FWparts 불 · rubbleParti 검은 파편
   (600/초) 이 끝없이 나오고 (용암 기둥 왼쪽에 검은 조각 구름 · 흰 불덩이) 통나무 메시가 피 웅덩이를 가로질러 누워 있었다. 전 WAD 조사: 컨텍스트가 있는 WAD 의
   그룹 밖 인스턴스는 화살 · 명중 효과 (golog · gologshatter · gobrokenarrowb), 히드라 · 아레스 · BMI 효과 (gobha* · gobar* · gobmi* · gosteamvent01 …), 밀기
   상자 원본 (DEST02 · PAND04C gopushpullblock — 실제 상자는 gopushblock), 적 AI1 템플릿 (goao* · gofamily · goevi) 뿐. 이 GO 를 쓰는 엔진 코드 (Creator ·
   BmiFight · ArrowShots) 는 모두 `EffectSystem` 복사로 만든다.
   → `LevelEmitters.template` (컨텍스트가 있는 WAD 에서 소속 없음) 이면 레벨 이미터를 만들지 않고, `LevelScene` 도 그리지 않는다 — AI1 템플릿 (aiTemplateObjects)
   은 예전 동작 그대로 [근사: 원작은 그것도 안 그릴 것 — 다른 작업 영역이라 그대로 둠]. 이미터가 빠진 레벨: ATHN01B/01C/01E (히드라 효과), ATHN08A/08B/08C,
   DEST02, PAND03G/03I/03J, PAND04C. 증거 `01_hall_specks…` · `02_log_on_pool…`.
2. 하네스: 경로 단계 `post:번호:인자0:인자1` (메뉴 "예" = `post:1021:1:1011` — 제단 메뉴 · 동영상 없이 켄타우로스 · 하데스의 원 확인) [엔진 전용].

### 43.2 장면 대조
| 영상 | 엔진 |
|---|---|
| 5950–5960 PAND03A 홀 · 구역 제목 13 · 피 웅덩이 · 용암 기둥 | 고치기 전: 왼쪽 용암 기둥 앞 검은 조각 구름 · 흰 불덩이, 웅덩이 위 통나무 → 고친 뒤 같은 구도 (`01` · `02`). 구역 제목 10 초 (21 문서) |
| 6020 제단 메뉴 → 6040 켄타우로스 계단 → 6100–6220 하데스의 원 (청색 그리스 무늬 띠 · 소켓 넷) | `post:1021:1:1011` → R_CNM 스폰 · gosac6loop1/2 AnimRing · $sacCirFire01/02 · $sacCirRing · $sacCirSubMat · DMC 결계 dogIn, 그리스 무늬 띠 같음 (`03`) |
| 5920 세이브 빛기둥 (PAND03J) · 7540 세이브 (PAND03A) | PAND03J (663, −96, 1308) 은 처음부터, PAND03A (−66, 0, −266) 는 PressPlate 마커 104 (석상) 뒤 생성 — 봇 순회에서 생성 확인 |
| 6400–7400 미로 회색 돌 복도 · 빛줄기 · 메두사 · 창살 | PAND03B 봇 순회 — 같은 복도 · 빛줄기 · R_ME2 · 창살 문 (`05`) |
| 7660–7720 PAND03C 문 홀 · 불덩이 | PAND03C 기본 시작은 불덩이 복도 (영상 앞 문 홀은 03A → 03C 걷기 이음) — 대조 안 함 |

### 43.3 남은 것 · 미확인
- ~~[미확인] **허브 잠수 도착 카메라 (영상 5826–5828: 위에서 폭포 웅덩이 · 웅덩이에서 나오는 크레토스)**~~ → **§44 해결** (Warp 도착은 5817.8, 5826 은 웅덩이 위
  WtrEntryCam1 — 떠나온 레벨에 남은 카메라 대상 슬롯의 1.6 끈이 두 극소를 가른다): 엔진 `--arrive PAND00C` 는 CMV golv3tubecams hull 2 →
  CoreTubeCam4 (레일 + 접선 틀 0x400, 설계 요 −180 ± 60). 첫 표본 (0x1F20F0, 매듭 × 4) 의 비용 극소가 둘 (u ≈ 9 (1304, −342, 2727) 13.0 · u ≈ 14.5
  (904, −356, 2625) 7.6) 이라 먼 쪽을 골라, 접선 틀에서 영웅 방향 국소 요 57.6° 가 −180 ± 60 밖 → 120° 로 잘려 화면 밖 62° — 크레토스가 (1039, −381, 2766) 까지
  걸어가도 파란 바위벽만 보인다 (`04`). §8.2 와 같은 "리셋 기준점 [근사]" 문제로 보인다 (Warp 직후 기준점 = 엔진은 영웅 머리, 원작은 직전 상태의 눈 + 정면·거리).
  카메라는 공유 코드라 고치지 않았다.
- [미확인] 하데스의 원 안 검은 덩어리: gosac6fade1/2 · loop1/2 의 FXC_emitter1 (서브타입 6 구, 관절 2, dist 0–0.15, PTC_particle1 색 ≈ 0) 이 엔진에선 원 가운데
  위에 뭉친 검은 구름, 영상 6110 은 원 안 바닥 전체가 검다. 구 이미터 반경 (엔진 = 배율 × sectionRadius [추정]) 미해석.
- [미대조] 미로 바닥 용암 칸 함정 (6300–6370) · 6250 "어두워서" 메시지 · 붉은 석상 R2 (HeadMsg 146) · DoorText 피 글자 · 석상 머리 크랭크 (7420–7480) · 붉은 방
  낙하 · 03C 문 홀은 봇으로 그 자리에 가지 못해 영상과 나란히 보지 못했다. 영상 6380 붉은 소용돌이는 사망 화면 (YOU ARE DEAD → 체크포인트) — 엔진 사망 화면은
  기존 (§ 체크포인트).
- 실제 세이브 위치 (`~/Library/Application Support/devple-gow`) 는 생기지 않았다 (모든 실행 = 스크래치 홈).

### 43.4 시험 · 증거
`TemplateEmitterTest` (PAND03J 템플릿 = {golog, gologshatter} · 원점 · 횃불 gotorchfire1 은 레벨 이미터, PAND03A 템플릿 없음). 엔진 테스트 754 (db86e34 위, 이 작업 +2).
증거 `work/verify/ep3_e/` (커밋 안 함): `01_hall_specks_v5950_v5960_before_after` (영상 | 고치기 전 | 고친 뒤), `02_log_on_pool_v5960_before_after`,
`03_hades_circles_v6100_v6110_vs_engine`, `04_tube_arrival_v5825_v5828_vs_engine`, `05_maze_v6410_v6520_vs_engine_03b`.

## 44. Warp 도착 첫 레일 표본 — 카메라 대상 슬롯은 Warp 를 넘어 남는다 (PAND03J ← PAND00C 허브 잠수 CoreTubeCam4, 영상 3화 5817.8–5819.5, 2026-10-26)

영상 = HF4CELG5OxQ (3화), 프레임 `work/verify/ep3_e/video/v5816.5 … v5819.5.jpg` (0.1 초 간격) · `v5824 … v5828.jpg` (0.2 초). §43.3 의 "5826 = 도착" 은
틀렸다: **Warp 는 5817.8** (5816.5–5817.7 은 PAND00C 관을 위에서 내려다보는 고정 카메라, 크레토스가 아래로 작아짐). 5817.8 첫 프레임부터 PAND03J 관 안에서
**크레토스 바로 뒤·조금 위** 카메라가 헤엄치는 그를 따라가고 (5818.5–5819.5 관 벽이 왼쪽→오른쪽으로 쓸려 지나감, 거리 거의 일정), 5824–5825 는 웅덩이 아래에서
위를 보는 고정 카메라 (CoreTubeCam2/1), 5825.2 부터 웅덩이 위 WtrEntryCam1 (상태 마스크 0xE0 = 물·점프·떨어짐, 순위 13) 이다. 엔진은 같은 도착에서 CoreTubeCam4 가
관 끝 극소 (u 14.5) 를 골라 영웅이 화면 밖 62° 였다 (`--arrive PAND00C` 첫 1.7 초 파란 바위벽만).

### 44.1 원작 [확실: 디스어셈블, 표시한 것 제외]
- **슬롯 표는 Warp 를 넘어 남는다**: 카메라 대상 슬롯 10 개 (0x304790, 0x50 바이트) 는 0x1F3F48 (영웅 생성 0x1DCC20 만 부름) 에서만 비운다. 영웅 슬롯 = 종류 −4
  (0x1F4138: 생물 +0xD0 + 오프셋 (0, 24, 0)), 반지름 +0x40 = 1.6, +0x44 = 16, 가중치 1. Warp 는 영웅을 다시 만들지 않는다 (Warp_Jump 0x1B2E08 = SetMatrix 만).
- **Warp_Jump → 0x1EFC98(옛 행렬, 새 행렬)**: 첫 줄이 **0x1F4380** — 슬롯 +0x30 을 원하는 자리 (새 Start + 24) 에서 반지름 1.6 밖으로 벗어난 만큼만 끌어온다
  (0x1F43CC–0x1F4440). 슬롯에는 떠나온 레벨 좌표의 옛 자리가 남아 있으므로 결과 T = 새 자리 + 1.6·normalize(옛 자리 − 새 자리) — 두 레벨 좌표계를 그대로 섞는다.
  그 뒤 **0x1EDF48(관리자, 0)** 리셋: 상태 링 템플릿, +0x770 = 0x1EEFA0 (대상점), 볼륨 선택 0x1EE8E0 → 활성화 0x1EEE88 → 새 상태 0x1F3A38 이 **곧바로
  0x1F3768(상태, 0)** 을 불러 (0x1F3B94) 첫 레일 표본 (+0x48 = −1 → 0x1F29A0 → 0x1F20F0) 을 이 T 로 잡는다. 이어지는 +0x7B0 = 1, +0x7C0 = 새·옛⁻¹ (0x185490)
  은 입력 기준 행렬만 옮긴다 (0x1EF310 → 0x1DCE80, 카메라 상태가 아님). 0x1F3A38 은 직전 상태 (리셋 템플릿) 를 복사하고 전환 0 이라 정면 보너스 없음.
- 영웅 속도는 잇지 않는다: SetMatrix 0x1E79D0 → 0x1E1D78 이 +0x210/+0x220 (속도) = 0, +0x230 (수면) = FLT_MAX. Warp_Jump 는 SetMatrix 앞에서 수평 속도 (+0xE0, 작으면
  +0x100) 와 옛 정면의 각 차이만큼 새 행렬을 돌릴 뿐이다 (0x1B2E84–0x1B2F6C, 0x1846F0). 그러니 첫 표본은 정지한 새 자리 + 1.6 끈으로 정해진다.
- 다시 읽은 나머지 (§13.9 · 30 §7.2 와 같음): 0x1F20F0 표본 = 구간 수 n × 4 + 1 (u = 끝 매듭 · i / 4n), 점 = (u³, u², u, 1) × 구간 행렬 / w, 비용 함수 = 0x200 없음 →
  0x1F1648 (설계 요·피치 0x1F0EE0 = 카메라 GO −행2, 범위 0x306E00..0C, 거리 [0x14·16, 0x10·16]), `c.lt.s` 엄격 (앞쪽 우선), 0x1F29A0 머리의 데드존 0 자르기 →
  기준점 = T. 시선·충돌 질의 없음.

### 44.2 PAND03J 에서 1.6 이 고르는 것
CoreTubeCam4 (NCV_CoreTubeRail1, 구간 15 → 표본 61, 거리 144, 접선 틀 요 180 ± 60) 의 첫 표본은 도착점 (1159.6, −344.7, 2784) + 24 에서 u 9.25 (1292.6, −342.3, 2743.8)
|v| 140.6 → 비용 3.46 (영웅 뒤, 범위 안) · u 14.5 (904.2, −356.5, 2624.7) |v| 303, 자른 방향 투영 141.3 → 2.75 (관 끝, 범위 밖) 로 거의 같다. **T 를 −X 로 1 만 옮겨도
u 9.25 가 이긴다** (탐침: x 1158.6 → 영웅 뒤, z −4 → 관 끝). PAND00C 관 Warp 볼륨 (golvl3warp 영역 1 공 (−1178, −443, 2023) r 224) 쪽 방향은
normalize(−2338, −98, −761) = (−0.95, −0.04, −0.31) → T 이동 (−1.52, −0.06, −0.49) → **u 9.25, 눈 (1294, −342, 2743), 영웅 화면 가운데** — 영상 5817.8 의 "바로 뒤에서
따라가는" 구도. §13.9 의 "1.6 끌려도 3.46 → 3.9 로 나빠짐" 은 옛 자리 방향을 다르게 잡은 것으로 보인다 [추정] — 여기서 볼륨 위치로 다시 쟀다.
정확한 옛 자리 (볼륨 경계에서 슬롯이 1.6 늦게 따라온 점) 는 몰라도 두 레벨 사이 거리 (≈ 2460) 에 비해 작아 방향은 거의 같다 [근사: 옛 자리 = 볼륨 중심].

### 44.3 엔진 (`CameraRig.targetSlot/carryTargetSlot`, `GameSession.warpTargetSlot`, `GameScene`)
- Warp 로 떠날 때 지금 슬롯 자리를 세션에 남기고, 새 씬은 첫 카메라 갱신 전에 그 자리로 슬롯을 놓는다 → 첫 `target()` 이 1.6 끈으로 끌어온 T 로 볼륨 선택·첫 표본.
  예전엔 새 `CameraRig` 의 슬롯이 비어 첫 T = 새 자리 그대로였다. 이음 (같은 씬 `swapLevels` · `handoff`) 은 원래 같은 리그·슬롯이라 바뀌지 않는다.
- 하네스 `--arrive FROM` 은 떠나온 자리가 없으므로 `-Dgow.arrive.slot=x,y,z` (떠나온 레벨 좌표) [엔진 전용]. 없으면 예전과 같다 (§7 · §13 의 하네스 도착 결과 그대로).
- 잠수 도착 재현은 `-Dgow.arrive.heroState=SWIM:DIVE -Dgow.giveAll` (bd6a17a: Warp 가 물 상태를 잇는다, 잠수는 포세이돈의 삼지창).

### 44.4 검증 (`work/verify/cam44/`, 커밋 안 함)
- `01_pand03j_arrival_v5817.8-5819.2_vs_before_after.png` — 위 영상 5817.8 · 5818.2 · 5818.8 · 5819.2, 가운데 고치기 전 (0 · 0.25 · 0.75 · 1.25 초: 바위벽만),
  아래 고친 뒤 (`-Dgow.arrive.slot=-1178,-419,2023`, 헤엄 경로): 크레토스를 뒤·위에서 화면 가운데에 두고 따라간다 — 영상과 같은 구도 (엔진 물 안개가 더 푸름).
  관 끝을 돌면 CoreTubeCam3 → (웅덩이 아래) CoreTubeCam2 — 영상 5824–5825 의 위를 보는 구도.
- `02_pand02a_arrival_v2215_vs_before_after.png` — 같은 규칙이 **PAND02A ← PAND00C** 도 바꾼다: 도착점 x 960 은 camFromWarp (x ≤ 958.6) · camWarpTunle (x ≥ 964.2)
  사이 5.6 틈 (§13.8 표) 이라 예전엔 `Follow` (벽 속), PAND00C Lvl2Warp (≈ (−1138, 466, 1725)) 쪽 1.6 이면 T x ≈ 958.5 → **camFromWarp** (이름 그대로 Warp 도착용
  레일 카메라, 크레토스 뒤). 영상 2215 는 이미 상자 앞이라 첫 초 구도는 [미대조].
- 전 Warp 도착 73 곳 탐침 (각 Start 에서 슬롯을 ±X/±Y/±Z 먼 곳에 두고 첫 카메라·눈 비교): 바뀔 수 있는 것은 PAND03F/03J ← PAND00C (같은 관, −X 쪽이면 눈 Δ 406),
  PAND02A ← PAND00C (−X 쪽 → camFromWarp), ATRET01C ← ATRET01B (+Z 쪽일 때만 같은 LwrCam18 눈 Δ 17), PAND04H ← PAND04IB (−Z 쪽일 때만 눈 Δ 15 — 실제 옛 자리
  reloadPand04H 공 (128, −321, −5860) 쪽으로는 변화 없음). 나머지 68 곳은 어느 방향이든 같은 카메라 · 눈 Δ ≤ 10.
- 기본 시작·하네스 도착 (§7 13 레벨, §13 도착 188) 은 코드 경로가 같다 (슬롯 없음 → 예전 그대로).
- 60 초 봇 (§44.5).
- 시험 `StartCameraTest.warpCarriesTargetSlotIntoFirstRailSample` (PAND03J 슬롯 없음 = 관 끝 · 영웅 화면 밖 / 슬롯 = 영웅 뒤 x > 1250 · 화면각 < 5°,
  PAND02A Follow → camFromWarp).

### 44.5 60 초 봇 (보통 난이도, `-Dgow.auto=1`, 스크래치 홈)
| 실행 | 예외 | 카메라 차례 |
|---|---|---|
| PAND03J ← PAND00C (슬롯 · SWIM:DIVE) | 0 | CoreTubeCam4 → WtrEntryCam3 → camera11 → RmPanCam → Hallcam8/2/3 → CoreTubeCam6/5 (BackToHub 쪽 관으로 돌아감) |
| PAND03F ← PAND00C (슬롯 · SWIM:DIVE) | 0 | CoreTubeCam4 → WtrEntryCam3 → Hallcam1/4/2 → SaveCam ↔ WheelCam → THallCam1 → CoreTubeCam5 |
| PAND02A ← PAND00C (슬롯) | 0 | camFromWarp → camWarpTunle → camFromWarp → camBrdg1 → camCine1 (케르베로스 동영상 앞) |
| 기본 시작 PAND03J / 03F / 02A | 0 | camera2 … / THallCam1 … / camBrdg1 … — 바뀐 코드 경로 밖 (슬롯 없음) |

## 45. 회귀: 물 상태 되돌리기가 없는 수면에 맞춤 — 60 초 봇 순간이동 급증 (ATHN01C 6 → 467, ATHN02A 5 → 28, PAND02D 7 → 60, 2026-10-26)

에피소드 3 QA 전 (fdd299f) 과 d672353 의 60 초 봇 (쉬움 `-Dgow.difficulty=0 -Dgow.auto=1 -Dgow.auto.secs=60`) 을 비교하면 ATHN01C 순간이동 6 → 467 (방문 16 → 11/25),
ATHN02A 5 → 28 (`급강하 99398.0 → (801, −602, 3216)` 가 매 틱), PAND02D 7 → 60 (`급강하 211.8 → (1304, 132, −2856)` 매 틱), PAND02F 3 → 26, PAND03F 6 → 29 였다. 예외 없음.

### 45.1 이분 (실행은 결정적)
| 커밋 | ATHN02A | ATHN01C |
|---|---|---|
| 8ab6023 (bd6a17a 앞) | 5 · 4/17 | 6 · 16/25 |
| **bd6a17a** (포세이돈 물: 삼지창 · 돌진 문 · 물 상태 되돌리기 · 머리 높이 수면 질의) | 28 · 4/17 | 502 · 11/25 |

ATHN01D 사망 +1 은 따로 **7ec7699** (50d4188 사망 0 → 7ec7699 사망 1): 빈 결정 트리 timedArcher 가 이제 절벽 위에 서서 쏘기만 한다 (§40 의 의도된 고침) —
봇이 절벽 위 궁수에게 못 가고 화살에 맞는다. 물과 무관, 그대로 둔다.

### 45.2 원인 [확실: 엔진 코드 / 하네스]
bd6a17a 는 하네스 순간이동 (`AutoPlay` → `GameScene`) 에서 헤엄치던 영웅이면 `HeroNavigator.restoreWater` 로 물 상태를 잇게 했다. 그런데
- **수면이 없으면** `restoreWater` 가 수면 = 발 + 28.16 + **100000** 으로 두고 물 상태 (떠 있기) 로 들어갔다. 다음 `swim()` 의 떠 있기 맞춤
  (`water == TREAD || y ≥ line → y = line`) 이 영웅을 y ≈ 99398 로 옮김 → 봇이 급강하 / 못 도착으로 보고 매 틱 다시 순간이동 (ATHN02A: 배 옆 바다에 빠진 뒤 갑판
  볼륨 점 godeckspawn2 로 순간이동).
- **발 아래 수면**도 잡았다 (발 − 64 부터 위로 훑음): ATHN01C 갑판 아래 물 (수면 −116) 에 빠진 뒤 볼륨 점 goa01hl07 (y −60, 수면보다 56 위) 으로 순간이동하면
  떠 있기 맞춤이 y −144 로 끌어내려 도착 판정 (높이 차 < 64) 이 영영 안 남 → 매 틱 순간이동 467 회.
- **떠 있기인데 물속 깊은 점**: PAND02D 수영장 바닥 점 (1304, 132, −2856, 수면 ≈ 240) 으로 순간이동하면 떠 있기 맞춤이 곧바로 수면 아래 28.16 (211.8) 로 올림 → 매 틱
  순간이동 60 회. **-Dgow.giveAll (삼지창) 로도 60 회 그대로** — 삼지창 잠수 게이트 탓이 아니다 (봇은 □ 잠수를 하지 않는다).

### 45.3 원작 [확실: 디스어셈블]
물 상태 갱신 0x1C9BB8 (+0x174 & 0x380) 의 떠 있기 · 가라앉기 갈래는 머리 높이 수면 질의 **0x1E57D8**(a3 = 1) 를 부르고 (0x1C9C9C), **맞으면** 0x1C93B8
(+0x174 = 0x80 떠 있기, 수면 높이 맞춤), **빗나가면** 0x1C94B0 (+0x174 = 0x100 가라앉기) — 없는 수면이나 질의 창 (발 + 28.16 ± 2·max(이동, 3.2)) 밖의 수면에
맞추는 일이 없다. 0x1E57D8 은 Water · NoDiving 면에 맞을 때만 +0x230 (수면) 을 쓴다 (0x1E5960). Warp 의 SetMatrix 0x1E1D78 이 +0x230 = FLT_MAX 로 두어도
(§44.1) 다음 질의가 빗나가면 가라앉기일 뿐이다.

### 45.4 고침
- `HeroNavigator.restoreWater` → boolean: **발 위** (발 + 16 부터 위로 16 씩, 수면 > 발) 첫 Water 면이 없으면 물 상태로 두지 않고 거짓 (호출자 = 지상 / 공중 그대로)
  [근사: 물 없는 곳의 물 스냅숏은 원작에 없다]. 100000 수면 폴백 삭제.
- 떠 있기 (TREAD) 를 되돌리는데 발이 수면 질의 창보다 깊으면 (발 < 수면 − 28.16 − 6.4) **가라앉기** (0x1C94B0 갈래) — 부력으로 떠오른다.
- `GameScene`: 체크포인트 · Warp 되돌리기 로그가 수면 없음을 알린다 ("위에 수면 없음 (물 상태 아님)"). bd6a17a 의 의도 (PAND02F 물속 체크포인트 · Warp 잠수 도착이
  물 상태 유지) 는 그대로 — 그 자리들은 발 위에 수면이 있다 (`PoseidonWaterTest.checkpointRestoreKeepsDive` 통과).
- 하네스 `AutoPlay` [엔진 전용]: 볼륨 점으로 순간이동한 뒤 1 초 (`TELEPORT_HOLD`) 안에 도착 판정이 안 나면 그 점을 포기 ("볼륨 포기 (순간이동해도 못 머묾, …)",
  결과의 방문 분모에 넣음). 높이 차 ≥ 64 (물속 점) 면 그 1 초 동안 다시 순간이동하지 않는다. 예전엔 도착할 때까지 매 틱 다시 순간이동했다.
  PAND02D 에서 포기하는 점: 수영장 바닥 두 곳 (1304, 132, −2856) · (958, −236, −2871) — 삼지창 전엔 잠수 못 함 (봇은 잠수 안 함), 그리고 돌진 문 공 CDV 안 (960, −239, −3382)
  — bd6a17a 의 돌진 문이 몸을 48 밀어낸다 (의도된 고침, 돌진으로만 부숨). PAND03A 는 원점 (0, 0, 0) 볼륨 점 (도착 판정이 안 남, 예전 매 틱 순간이동 ≈ 30 회).
- 시험 `PoseidonWaterTest.restoreWaterWithoutSurfaceAboveStaysOnLand` (수면 56 위 점 · 물 없는 곳 → 거짓, y 그대로).

### 45.5 60 초 봇 (쉬움, 스크래치 홈) — 순간이동 · 방문 · 사망
| 레벨 | fdd299f | d672353 | 고친 뒤 | 고친 뒤 + giveAll | 비고 |
|---|---|---|---|---|---|
| ATHN01C | 6 · 16/25 · 0 | 467 · 11/25 · 0 | **6 · 16/25 · 0** | | 발 아래 수면 |
| ATHN02A | 5 · 4/17 · 0 | 28 · 4/17 · 0 | **5 · 4/17 · 0** | | 수면 100000 |
| PAND02D | 7 · 12/12 · 0 | 60 · 12/12 · 0 | **26 · 9/12 · 0** | 26 · 9/12 · 0 | 물속 점 2 · 돌진 문 1 포기 (나머지 19 = 문 점 1 초 재시도) |
| PAND02F | 3 · 7/17 · 0 | 26 · 7/17 · 0 | **3 · 7/17 · 0** | 3 · 7/17 · 0 | |
| PAND03F | 6 · 15/15 · 0 | 29 · 15/15 · 0 | **6 · 15/15 · 0** | 6 · 15/15 · 0 | |
| PAND03A | 62 · 7/18 · 0 | 63 · 7/18 · 0 | 31 · 6/18 · 0 | | 원점 볼륨 점 포기 |
| ATHN01D | 4 · 13/38 · 0 | 4 · 13/38 · 1 | 4 · 13/38 · 1 | | 7ec7699 궁수 (§45.1) |
| ATHN01B | 4 · 8/8 · 0 | 4 · 7/8 · 0 | 4 · 7/8 · 0 | | 물과 무관 (다른 커밋) |
| ATHN05B | 1 · 5/28 · 0 | 1 · 6/28 · 0 | 2 · 8/28 · 0 | | |
| ATHN06B | 6 · 8/35 · 0 | 3 · 8/35 · 0 | 4 · 10/35 · 0 | | |
| ATHN08C | 0 · 0/0 · 16 | 0 · 0/0 · 18 | 0 · 0/0 · 18 | | 아레스 보스전 (물과 무관) |

PAND02D/02F/03F 의 증가는 삼지창 게이트가 아니었다 — giveAll 로도 같고, 고친 뒤 PAND02F · 03F 는 예전 값으로 돌아왔다. 남은 PAND02D 차이는 물속 · 돌진 문 볼륨 점 (봇이 잠수 ·
돌진을 하지 않음) 이다.

## 46. QA vs PS2 영상 4화 — 하데스의 시험 계속: PAND03C 굴러오는 불덩이 복도 · 끝 방 (해골 · 상자 · 병사의 쪽지) · 들어 올리는 옆문 · 사망 → 체크포인트 → PAND03C DMC 굴 → PAND03D 줄 다리 · 용암 원형 경기장 (영상 0–960 초, 2026-10-29)

영상 = 7QAMXQj38Io (4화, 전사 (보통) = `-Dgow.difficulty=1`), 프레임 422×317 (`work/verify/ep4_a/video/v<초>.jpg` — 0–950 10 초 간격, 115–130 · 140–150 · 195–210 ·
270–300 1 초, 커밋 안 함). 엔진 = origin/main 464352b 위, 1280×720 lockstep, 스크래치 홈, `--skip-movies`, `--game PAND03C|PAND03D`, 경로 `-Dgow.auto.route` · 봇.

경로 [확실: 스크립트 · 영상]: 0–40 PAND03A → 03C 계단 · 빛줄기 문 (3화 끝의 이음) → 50–100 PAND03C 굴러오는 불덩이 복도 (goleftrock1–6 · gomidrock1–5 · gorightrock1–6, 반복 act
30 초, 세 줄) → 110–130 끝 방 (−5440…−5650): 해골 BallwaySkels (R_SKC 템플릿 22) · 상자 셋 · 불 기둥 → 140–150 병사의 쪽지 NewBook (메시지 225 "8개의 막다른 길, 하나의 올바른 길…")
→ 195–260 복도로 돌아와 옆 벽감 (S3LiftDoor1–9, TAndF 330 "Pandora3CBallWa") 으로 불덩이 피하기 → 271–276 불덩이에 맞아 튕김 · 넘어짐 → 다음 불덩이 → YOU ARE DEAD →
281–284 "로딩 중.." → 285 체크포인트 (복도) 다시 → 300–530 옆문 탐색 · 끝 방 다시 (해골 다시 생김) → 540–600 PAND03C DMC 굴 (미노타우로스 R_MI4 · 궁수 · DMC 개 머리 문) →
600–620 강화 메뉴 (혼돈의 블레이드 5) → 640–720 굴 싸움 → 720–760 PAND03D 용암 위 줄 다리 (RopeArchers · 오르는 해골) → 780–950 PAND03D 용암 원형 경기장 (DMCEnemies 켄타우로스
R_CNM · 미노타우로스 R_MI4 · 해골). PAND03E (적 없음, 바람개비 기둥) 는 960 이후 (B 구간).

### 46.1 엔진 결함과 수정
1. **CDV 재질의 접촉 피해가 없었다 — 굴러오는 불덩이가 크레토스를 지나가도 피해 · 넘어짐 · 사망이 없음 (영상 271–276: 맞아 튕겨 넘어지고 다음 불덩이에 사망, 엔진 전체)**
   [확실: 디스어셈블 · 데이터, 표시한 것 제외].
   - 표면 레코드 스키마 0x1BECA0 은 "bitfield0–3 · SheetFX · PartType · WallHangTimer · **Damage · AIDamage**" 의 오프셋을 0x3376EC…0x33770C 에 둔다. Damage (0x337708) 를
     읽는 곳은 **0x1E1DF0 하나**: 접촉 레코드 +0x2C (맞은 면의 재질) 에서 플레이어 (+0x174 비트 31) 면 Damage, 아니면 AIDamage 를 읽어 0 이 아니면 생물 vtable +0xA8
     (대기 피해 +0x280 · 접촉 +0x270 · SheetFX +0x284). 부르는 곳 = 바닥 질의 0x1E3298 · 0x1E36B0 · 0x1E2F20 · 0x1E1F28 — 정적 시트와 GO 충돌 인스턴스 (CDV) 를 같이 본다.
   - 0x1EAC70 (생물 갱신 0x1EBD78 매 프레임): +0x280 ≠ 0 이고 타이머 +0x2B0 = 0 이면 피해 × AI Damage Mult × 코스튬 × 신 모드로 **0x204690 (플래그 4, MFX +0x1C & 0x10 이면 6)**,
     타이머 1 초. 0x204690 은 HP 가 0 이면 사망 14, 아니면 (플래그 비트 0 없음) **피격 13** 을 0x2090B8 로 쏘고, 키 t2 = SheetFX 가 분기 매치 0x208578 의 스택 인자 →
     **0x208970: 분기 +0x1A == 키 → 점수 0, 분기 −1 → +1, 그 밖 실패**. 영웅 루트 A180–A189 의 +0x1A = 100–109 → 함정 MFX 100–109 에 짝: **105 "trap roll ball" → A185
     HITBALL** (튕겨 넘어짐 → 193 hitGetupGround), 101 → A181 HITFIREBALL, 104 → A184 PARRYHITLEFT …
   - 데이터 (CDV 재질 엔트리 0x40 바이트 = 이름 24 + 표면 레코드 40, Damage +0x38 · AIDamage +0x3C): PAND03C 불덩이 공 CDV_Left/Mid/RightRock1 **20 / 1024** (MFX 105, 공 1 개
     반지름 43.2, Ground · Slide), PAND03E 바람개비 10 / 0, HDES01B BladeDamage · HDES01C 죽음 굴림대 10 / 10, PAND01B 톱날 10 / 15 · 블록 10 / 1024, PAND02G 가시 바퀴 25,
     PAND02H 날 1 · 가시 20, PAND03B 화살 함정 20 · 분쇄기 40 / 1024, PAND04G 톱 25, PAND00C 굴러가는 통나무 50, PAND00D 분쇄기 10, PAND02C 불 15, DEST02 분쇄기 33 / 1024,
     PAND02AA 회전방 1024, PAND04C 가시 1024 (전 WAD 조사).
   - 엔진은 정적 시트의 **바닥 면** 피해만 보았고 (PAND04J), CDV 재질 피해는 읽지도 않았다. 불덩이는 피해 없는 공 CDV 라 몸도 아니어서 크레토스를 그냥 지나갔다.
   → `BallHull.materialDamage/materialAiDamage`, `Platforms.contactDamage` (몸 구가 닿은 공 · hull 면의 재질 피해 최댓값과 SheetFX; hull 은 가장 얕은 면의 재질 [근사]),
   피해 공 CDV (`Platforms.damageBalls`) 는 몸으로 올림 (몸 밀어내기 — 원작은 모든 CDV 를 충돌 세계에 올림 [추정: 0x138FD0], 피해 없는 장식 공은 예전처럼 뺌 [근사]),
   `GameScene.surfaceDamage` 가 바닥 + CDV 접촉 (발 + 20, 반지름 12 — 벽 밀어내기와 같은 몸 구) 의 최댓값을 1 초 타이머로, 영웅은 이어서 `Combatant.fireFrom(13, …, SheetFX)`,
   적은 AIDamage (불덩이에 닿은 해골 1024 → 즉사). `Combatant.score` 는 키 (SheetFX ≥ 0) 가 있으면 0x208970 규칙 (키 없는 예전 호출은 그대로).
   [근사: 0x1EAD5C 밀어내기 (+0x270 수평 × 6 × 16 × dt × 질량) 생략 — 넘어짐은 HITBALL 루트 모션, 몸 밀어내기는 공이 민다; 정적 시트 벽 면 접촉은 아직 바닥만]
   - 증거 `01_fireball_hit_v272-276_vs_engine.png` (위 영상 271–274: 튕겨 넘어짐, 아래 엔진 `0,260,-1500!;wait` 8.0–9.6 초: 불덩이 → HITBALL → 넘어짐). 같은 자리 30 초
     대기 = 피해 20 × 5 → 22.4 초 사망 → 체크포인트 (영상 276 사망 화면 → 281 로딩 → 285 다시).

### 46.2 60 초 봇 (보통, 스크래치 홈) — 고치기 전 (464352b) → 뒤
| 레벨 | 사망 | 피격 반응 (EV_HIT_REACT) | 비고 |
|---|---|---|---|
| PAND03C | 0 → 1 | 11 → 24 | 불덩이 HITBALL · 사망 (의도) |
| PAND01B | 2 → 2 | 0 → 6 | 톱날 · 블록 접촉 (사망 2 = 예전과 같은 낙하) |
| PAND03D · PAND04J · HDES01C · ATHN01C · PAND02G · PAND04G | 같음 | 같음 | 예외 0 |

### 46.3 장면 대조
| 영상 | 엔진 |
|---|---|
| 50–100 불덩이 복도 세 줄 · 용암 벽 · 회전 | 같은 배치 · 반복 act (`c_bot` 봇 순회) |
| 110–130 끝 방: 해골 · 상자 · 불 기둥 · 바닥 용암 창살 | 같은 방 · R_SKC 해골 · 불 기둥 (gosndbigfire 쪽) · 상자 (`0,262,-5300!` 경로) — 상자 빛 · 해골 수는 46.4 |
| 140–150 쪽지 메시지 225 | NewBook 1048 (인자 329) → ShowMessage 225 (스크립트 그대로) |
| 220–260 옆 벽감 · 들어 올리는 문 (TAndF 330) · 벽 횃불 방 | S3LiftDoor1 R2 연타 → 과제 1023 (인자 1 = 320) · 들어 올림 · 횃불 방 (`use:-144,-1824;mash:4`) |
| 271–276 불덩이에 튕김 → 사망 | 고친 뒤 HITBALL → 넘어짐 → 다음 불덩이 (46.1) |
| 281–284 "로딩 중.." → 285 복도 체크포인트 | 검은 페이드 뒤 체크포인트 (로딩 글 4085 없음 — §39.3 과 같은 [엔진 구조]) |
| 540–600 DMC 굴: 미노타우로스 둘 → 처치 → 궁수 넷 · 빛줄기 · 메두사 시선 (초록) | DMCSpawner → R_MI4 둘 · DMC 개 머리 dogIn → 미노타우로스 처치 (DestructionSensor) → R_SK2 궁수 넷 (`-560,262,-3280!` 봇, 보통 HP 100 으로 35 초 사망) |
| 720–950 PAND03D 줄 다리 · 용암 원형 경기장 (용암 홈 · 미노타우로스 · 켄타우로스) | PAND03D 기본 시작 = 같은 원형 경기장 · 줄 · 줄 궁수 (봇) — 싸움은 대조 안 함 |

### 46.4 남은 것 · 미확인
- [미확인] 끝 방 상자 셋: 영상 121–129 는 왼쪽 초록 (healthChest CSM 004) · 가운데 나무 상자 · 오른쪽 붉은 빛 상자, 엔진은 초록 하나 + 회색 (HealthShard CSM 010 · MagicShard
  CSM 011 = 종류 1 plain) 둘 — 아이템 상자의 빛 이펙트 출처 미해석.
- 끝 방 해골 수: 엔진은 BallwaySkels AI1 · AI2 (H0 = 2) 가 넷까지 함께 나오고 다시 생김, 영상 110–130 은 한두 마리로 보인다 [미대조: 스폰 규칙].
- [미확인] PAND03D 궁수 턱 (x ≈ −1200, y 320) 의 해골 SkellyLevel2AI2 (R_SKC 22) 가 봇 6 초에 턱 끝을 걸어 나가 용암 구덩이 (y 47) 로 떨어져 살아 남는다 — 턱 가장자리에
  AIBlock 면이 없다 (Ground · TakeDamage 만). 원작 적 이동의 낭떠러지 처리 (공용 EnemyNav) 는 이 구간에서 해독하지 않았다. 영상은 크레토스가 턱 아래를 지나가는 장면이 없어 대조 못 함.
- 영상 800 경기장 바닥의 주황 불 고리 (돌 조각과 함께 퍼짐) 의 출처 (적 기술 · 영웅 기술) 는 [미확인] — 엔진 경기장 싸움은 몰지 않았다.
- 정적 시트의 **벽 면** 피해 (원작 0x1E36B0 등 몸 질의) 는 아직 바닥 면만 (46.1 [근사]).
- 실제 세이브 위치 (`~/Library/Application Support/devple-gow`) 는 생기지 않았다 (모든 실행 = 스크래치 홈).

### 46.5 시험 · 증거
`ContactDamageTest` (PAND03C 불덩이 CDV 재질 20 / 1024 / MFX 105 · 피해 공 = 몸 17 개 · 닿음 Touch(20, 105) · 적 1024 · 충돌 끈 몸 없음, PAND03E 바람개비 10 / 0,
영웅 피격 13 키 105 → 185 HITBALL · 101 → 181 · 104 → 184). 엔진 테스트 773 (464352b 위, 이 작업 +3).
증거 `work/verify/ep4_a/` (커밋 안 함): `01_fireball_hit_v272-276_vs_engine`, `02_end_room_v121_v129_vs_engine`, `03_dmc_v550_v580_vs_engine`, `04_liftdoor_v240_v270_vs_engine`.

## 47. QA vs PS2 영상 4화 — 하데스의 시험 기둥 갱도: PAND03D 세이브 → PAND03E 회전 칼날 기둥 · 들보 · 빛줄기 · 사망 → PAND03F 복도 · 바퀴 문 · 세이브 · 업그레이드 (영상 960–1980 초, 2026-10-29)

영상 = 7QAMXQj38Io (4화, 전사 (보통) = `-Dgow.difficulty=1`), 프레임 422×317 (`work/verify/ep4_b/video/v<초>.jpg` — 960–1980 10–100 초 간격, 1124–1160 1 초,
`video1/` = 1화 5658–5680 ATHN04A, 커밋 안 함). 엔진 = origin/main 464352b 위, 1280×720 lockstep, 스크래치 홈, `--skip-movies`.

### 47.0 장면 대응
| 영상 (초) | 장면 | 원작 (스크립트 · 데이터) | 엔진 |
|---|---|---|---|
| 960–1015 | PAND03D 원형 경기장 끝 · 줄 · 해골 처치 → 돌 방 | DMCSpawner · RopeArchers · ropeskellygroup (§46 구간 끝) | — (A 구간) |
| 1020–1025 | 세이브 빛기둥 R2 → "메모리 카드 슬롯 1의 … 덮어쓰시겠습니까?" | PAND03D SaveGameZone (−380, 497, −525) | 같은 빛기둥 (`02` 4 행) |
| 1030 | 붉은 오브 상자 | PAND03D Chest3 / Chest10 | 상자 생성 확인 |
| 1035–1050 | PAND03E 입구 · 용암 커튼 · 계단 판 | PAND03E Start (−282, 499, −551), ChkptBeams | 같은 구도 |
| 1040–1300 | 갱도: 들보 (Narrow) · 회전 칼날 기둥 (PinWheel1–3) · 위 석상 얼굴에서 내려오는 노란 빛줄기 | FakeStatue MAT_M01SFXlightBeam5 · SR2lightFX02 | **빛줄기가 안 보였다 → 47.1** |
| 1127–1131 | 칼날에 맞아 떨어짐 → 붉은 소용돌이 → "YOU ARE DEAD / 마지막 체크포인트에서 다시 시작" | 0x21D770 · TimeWarpBlur 999 "DeathFade" | **소용돌이 없음 → 47.2** |
| 1133–1137 | 입구 계단 판에서 다시 (ChkptBeams) → 다시 떨어짐, 실루엣 위 제목 | 체크포인트 ChkptBeams (−272, 528, −568) | 같은 규칙 (§26) |
| 1138–1141 | 소용돌이 (제목 · 체력 막대 사라짐) → "쉬움 모드로 바꾸시겠습니까?" | 0x220360 2.5 초 → 쉬움 제안 0x1DBF90 (사망 수 % 5) | 47.2 |
| 1142 | 검은 화면 오른쪽 아래 붉은 "로딩 중..." | FLP_HUDK 루트 'Loading' (PS2_4085) | 47.3 |
| 1144–1780 | 갱도 오르기 (사다리 · 사슬 · 들보 · 빛줄기) | PAND03E NewChkpt · ChkptBeams2 · SirenKey2 상자 | 봇 순회 (7/7 볼륨) |
| 1790 | 짚라인 (gorope5) 으로 PAND03F 쪽 | LoadF · ZoneReportGroup 13 | 구역 제목 13 "하데스의 시험" |
| 1800–1830 | PAND03F 나무 판 복도 → 바퀴 (NewCSM 353) → FloatDrCam 문 → 떨어지는 판 | NewCSM-main 1023 (100, 353) · BossHallGates · fallPlatGRP | (47.4) |
| 1860–1910 | 세이브 빛기둥 R2 → 업그레이드 메뉴 (제우스의 분노 1 → 2) | PAND03F SaveGameZone1 | (47.4) |

### 47.1 조명 없는 모델에 레이어 rgba 를 또 곱했다 — 갱도 빛줄기가 안 보임 (엔진 전체, 영상 1040–1300) [확실: 마이크로코드 · 데이터] (47 문서 §3.2)
- 영상: 위 석상 얼굴에서 갱도 아래로 굵은 노란 빛줄기 (1040 · 1100 · 1150 · 1600). 엔진: 석상 머리만, 빛줄기 없음 (`02` 3 행 가운데).
- 빛줄기 = `MDL_FakeStatue` (렌더 플래그 0) 의 `MAT_M01SFXlightBeam5` (가산 0x48, 레이어 rgba (0.116, 0.112, 0.093), UV 흐름) · `MAT_SR2lightFX02`.
  정점 색 (27–29, 24–28, 11–23) = rgba × 256 — 내보내기 도구가 머티리얼 색을 정점 색에 구웠다. 엔진은 조명 없는 경로에서 정점 색 × 레이어 rgba × 텍스처라
  0.22 × 0.116 ≈ 2.5 % 로 사라졌다 (§36.4 PAND00D EyeCrusherBeam 도 같은 머티리얼 · 같은 원인).
- 원작: 메시 VU1 주 프로그램 0x090–0x098 은 VU 플래그 4 (모델 렌더 플래그 0x1000 · 그리기 α ≠ 1 · rf 0x4000) 일 때만 qw76 (헤더 × 레이어 rgb) 을 vf7 · vf30 에 넣고
  아니면 1 이다; 조명 없는 색 커널 (라이브러리 0x179 그대로 · 0x189 / 0x19B vf31 + vf30 × 정점 색) 이라 정점 색만 남는다. 데이터 확인: ATHN04A breakCrate02
  wood 0.8595 · wood6 0.719 · wood7 1.2 · wood9 0 ↔ 정점 색 최댓값 64 · 54 · 89 · 0 (비가 정확히 같음).
- 고침: `WadAssets.unlitVertexColorOnly` — 렌더 플래그 0x1000 이 없는 모델의 첫 · 추가 레이어 색 배율 rgb = 1. 예외 [근사]: 헤더 · 레이어 색 ANM 트랙이 있는 머티리얼
  (PAND00C 해 문 글자 rgba 0 → 1 — 2화 3610–3642 은 R2 전 어두움) 은 예전 규칙 (이 경우의 플래그 4 출처 [미확인]).
- 영향: 렌더 플래그 0x1000 없는 모델 · 색 배율 ≠ 1 머티리얼 쌍 약 2300 (6155 사용, 0.6–1.5 가 대부분). 영상과 견준 장면 (전 · 후, 메인 요청):
  2화 DEST01 v1150 · 3화 PAND01B 세이브 v0 · PAND00D 세이브 v1868 · PAND03J 세이브 v5920 · PAND03A 홀 v5960 · PAND02A v1985 · 4화 PAND03D 세이브 v1025 ·
  PAND03E v1100 · PAND03F v1800 · 1화 ATHN04A 도착 v5660 · 광장 v5680 — 레벨 지오메트리 대부분은 0x1000 없는 정적 메시라도 색 배율 1 이라 눈에 띄는 변화 없음,
  ATHN04A 길 · 왼쪽 벼랑이 조금 더 푸르고 밝아져 영상 (밝은 회보라 길) 에 가깝거나 같음, PAND03E 빛줄기가 생김. 더 나빠진 장면 없음.
- 시험 `UnlitTintTest` (3: 규칙 · breakCrate02 정점 색 비 = rgba 비 · 빛줄기 정점 색 = rgba × 256).

### 47.2 사망 흐림 "DeathFade" 가 없었다 — 붉은 소용돌이 (엔진 전체, 영상 1129–1130 · 1138–1141, 1화 7049 · 8209, 3화 6380) [확실: 디스어셈블 · 데이터]
- 원작: 사망 0x1D6CF0 (HP &lt; 1 → "Death Menu Time" 1.0 초 0x304094 또는 +0x174 0x08000000 (낙하 · Death 재질) 이면 곧) → 사망 수 + 1 → 0x21D770: 제목 (+0x25C = 1, DeadMenu_Event 1),
  0x220830: 선택지 대기 0x29F46C = 2.5 초 (0x29F470) 와 **TweakTemplate "TimeWarpBlur" 999 "DeathFade"** (0x182D50 → 0x142DE0), 상태 0xE.
  0x143048 (그리기 0x140988, 게임 상태와 무관하게 1/60 초씩) → 0x142E40 이 키 0→1 (1.0 초) → 2 ("Hold Time" 1.5) → 3 (1.0) 선형 보간: 색 (0.52, 0.4, 0.3) ·
  A 1 → 0.1 → 0 → 키 3 (0, 0, 0, 1), 확대 128 → 128 → 256 → 64 (1/16 화소). 0x142A28: 앞 화면을 텍스처로, 가장자리에서 확대만큼 안쪽 UV (TEX1 선형) 띠 스프라이트,
  RGBAQ (R·255, G·255, B·255, 0x80) MODULATE, ALPHA_2 0x21 FIX = A·128 → 결과 = Cs + (Cd − Cs)·A. 되먹임마다 R 1.04 · G 0.80 · B 0.60 배라 붉은 방사 소용돌이.
  성분 키 Hash127(":R", 필드) — 필드 0x153DA042 · 0x3DBFF41C · 0x3DBFF41D · 0x0011323B, 시간 0x74623D84 · "Hold Time" · 0x34C726E3, 확대 "Zoom Start" · 0x79F7A78F ·
  0x79F7A790 · "Zoom End".
- 엔진 (전): 제목 → 2 초 → 선택지, 소용돌이 없음 (`05` 2 행). 고침: `fx.TimeWarpBlur` (템플릿 읽기 · 구간 보간) · `render.TimeWarpBlurRenderer`
  (앞 화면 텍스처 · 확대 UV · 색 곱 · `GL_CONSTANT_ALPHA` 섞기 · 복사) · `GameScene.openDeadMenu` → `startTimeWarp(999)`, 틱마다 (메뉴 정지 중에도) 진행,
  선택지 2.5 초 (`DEAD_MENU_CHOICE_DELAY`). 그리기 순서: HUD 뒤 [추정: 영상 1138–1139 소용돌이에 제목 · 체력 막대가 없고 1140 (A 0 → 1 구간) 에 선택지가 보임 —
  원작 그리기 목록 0x17A240 의 층 순서는 따라가지 못함]. 확대 단위는 640 × 448 프레임 기준 [근사]. `-Dgow.notimewarp` 로 끔.
- 결과 (`05` 3 행): 떨어지는 검은 실루엣 위 제목 → 붉은 방사 소용돌이 (제목 · 막대 사라짐) → 선택지 — 영상 1137 → 1138–1139 → 1140 순서와 같다.
- **"Death Menu Time" (추가)**: 0x1D6F90–0x1D7058 — HP &lt; 1 이 된 뒤 PlayerProgress +0x4C 가 1/60 초씩 늘어 "Death Menu Time" (/Player/ +0x34 = 0x304094,
  R_PERMK 1.0 초) 을 넘거나, 영웅 +0x174 **0x08000000** 이면 곧 사망 처리 (이전 판의 "0x800" 은 `lui 0x800` 을 잘못 읽은 것). 0x08000000 은 0x1ED048 – 0x1ED05C 가 세운다:
  접촉 재질 0x1E1DF0 의 속성 0x40 (Death 재질 → 0x29E170 = 1) 이거나 떨어지는 중 (+0x174 &amp; 8) 공중 시간 +0x2B8 ≥ 8 이면 최대 HP × 100 피해 (0x204690) [확실].
  즉 공격 · 접촉 피해로 죽으면 1 초 동안 세계가 돌고 (사망 동작) 메뉴, 떨어짐 · Death 바닥은 곧바로. 영상 4화 사망 다섯 (1043 · 1084 · 1109 · 1127 · 1137, 모두 낙하) 은
  곧바로 제목. 엔진: `GameScene.dyingTime` — HP 0 경로만 1 초 기다리고, 데스존 · Death 바닥 · 바닥 없는 낙하 (`heroDied` 직접) 는 곧바로 [근사: 엔진은 낙하 피해가 없다].
  60 초 봇 (쉬움): ATHN02A · PAND02D · PAND03D · PAND03E · PAND03F · ATHN01D 방문 · 순간이동 · 사망 같음 (지연 끔 대조 PAND03E · ATHN01D 같음).
- 쉬움 제안: 영상 1020 세이브 뒤 다섯째 사망 (1141) 에 "쉬움 모드로 바꾸시겠습니까?" — 앞 넷은 보통 메뉴 (메인 집계, 1 초 시트). 세이브 · 체크포인트 스냅숏이 사망 수를
  0 으로 하고 재시작은 유지 (§26, 0x1DBF90 사망 수 % 5) 와 같다. 재시작 자리 넷 모두 입구 계단 (ChkptBeams 한 번만 — 그 뒤 재시작도 같은 스냅숏).
- TimeWarpBlur 의 다른 사용 (0x206030 → 0x142DE0, 기술 이벤트) 은 보지 않았다.
- 시험 `TimeWarpBlurTest` (2: 필드 해시 = 이름 해시 · 성분 키 = 덤프 키, R_PERMK 값 · 구간 보간 · 길이 3.5 초), `DeathTimingTest` (선택지 2.5 초 · Death Menu Time 1.0).

### 47.3 "로딩 중..." (영상 1142) — 고치지 않음 [미확인]
- 영상 1142–1143: 쉬움 제안 "예" 뒤 검은 화면 오른쪽 아래 붉은 "로딩 중..." (1화 8212–8215 는 4.5 초, 3화 785). 글은 FLP_HUDK 루트의 'Loading' 클립 (#327, 90 프레임 붉은
  깜박임, 동적 글자 PS2_4085 "로딩 중...", 자리 (650, 545)) — 루트 표시 목록 0 (프레임 0) 에만 있고 다음 키 프레임에서 깊이 2 가 비워진다 [확실: FLP 덤프].
  실행 파일에는 "Loading" 문자열이 없고, 다시 시작 0x21E3D0 → 0x21FC28 (상태 9, 0x29F464 = 60) 이 HUD 루트를 프레임 0 으로 되돌리는 곳은 찾지 못했다.
  엔진 재시작은 곧바로 새 씬이라 로딩 화면이 없다 — 가짜 지연을 넣지 않았다.

### 47.4 PAND03F 바퀴 문 · 세이브 · 상자 방 (영상 1795–1915) — 원작과 같음
- 1804–1812 복도 → 바퀴 R2 (1808) → 돌리기: `IO_CSM` 023 "WheelUnlock" (영웅 bmiDoorOpen · 오브젝트 unlockDoor2, 손잡이 handle1 (191.5, 370.7, 1298.2) 축 −z,
  잡기 높이 16 · 반경 28 · 각 90° · 뒤 32) → 1048 붙잡기 → 100 % 1023 (100, 353) → GlobalData1 · Idle · FloatDrCam → 1813–1820 문 클로즈업 (사슬 문 · 붉은 Ω 자물쇠 켜짐 ·
  사슬 걷힘, DoorWall AnimUnlock · BossHallGates GatesDown · Chest3) → CamOff 9 초 → 1821 바퀴 앞. 엔진 w6: 7.80 붙잡기 → 10.97 완료 → FloatDrCam → 19.98 CamOff — 같은 순서 ·
  같은 구도 (`06`). 바퀴는 GateTrigger (03F 위쪽 입구) 가 CDOn 해야 잡힌다 (생성 시 CDOff).
- 1834–1858 세이브 (덮어쓰기 · 저장 중 · 저장하기 완료) → 1860 빛기둥, 1870 초록 · 파란 상자 (HealthChestOpen / MagicChestOpen, GlobalData6/7), 1886–1898 업그레이드
  메뉴 (제우스의 분노 1 → 2, 카드 "제우스의 힘: L2 홀드 + △ 홀드"), 1905 붉은 창살 복도 (PAND03G 이음) — 엔진 봇 순회에서 같은 방 · 같은 상자 빛 · 같은 창살 (업그레이드는 §17.3).
- 1798–1802 갱도 → 03F 다리에서 구역 제목이 없다: ZoneReportGroup (03E) 는 GlobalData:2 ≠ 13 일 때만 — 3화에서 PAND03A 에 들어올 때 이미 13. 엔진도 같은 조건.

### 47.5 남은 것 · 미확인
- 회전 칼날 기둥 (PinWheel1–3) 피해 · 넉백은 §46 (A 구간, 접촉 재질 피해 13d5bc1) 에서 다룸 — 이 구간의 영상 낙사 다섯과 엔진 피해량은 나란히 보지 않았다.
- 갱도 오르기 1144–1780 (사다리 · 사슬 · 짚라인) 은 봇 순회 (7/7 볼륨) 와 섬네일로만 보고, 영상과 같은 경로로 몰지 않았다.
- 1823–1834 줄에 매단 계단 판 (fallPlatGRP "drop the platform", StepsBottomCollision TurnOff) 은 봇이 지나가지 않아 떨어지는 판을 보지 못했다.
- 47.1 예외 (색 ANM 머티리얼) 의 원작 플래그 4 출처 · 47.2 그리기 층 순서 · 47.3 "로딩 중..." [미확인].
- 실제 세이브 위치 (`~/Library/Application Support/devple-gow`) 는 생기지 않았다 (모든 실행 = 스크래치 홈).

### 47.6 시험 · 증거
`UnlitTintTest` (3), `TimeWarpBlurTest` (2), `DeathTimingTest` (1). 엔진 테스트 782 (origin/main 661a3e5 위) 통과.
증거 `work/verify/ep4_b/` (커밋 안 함): `01`–`04_unlit_tint_*` (영상 | 전 | 후: 2화 DEST01, 3화 PAND01B/00D/03J/03A/02A, 4화 PAND03D/03E/03F, 1화 ATHN04A),
`05_death_fade_video_before_after` (영상 1137–1141 | 전 | 후), `06_wheel_door_floatdrcam_video_vs_engine`, `07_shaft_light_beam_video_vs_engine_after`, `video/` · `video1/`.

## 48. QA vs PS2 영상 4화 — 하데스의 시험 끝: PAND03G 판도라 수호자 (BMI) → PAND03I 썩은 시체 · PAND03H 제단 (책 · 관 · 머리) → PAND03I 하데스의 군대 · 결계 싸움 → PAND03F 소용돌이 · 관 → PAND00C 허브 (영상 1980–2880 초, 2026-10-29)

영상 = 7QAMXQj38Io (4화, 전사 (보통) = `-Dgow.difficulty=1`), 프레임 422×317 (`work/verify/ep4_c/video/v<초>.jpg` — 1960–2880 10 초 간격, 1995–2010 · 2095–2125 ·
2385–2405 · 2455–2460 · 2655–2690 · 2770–2810 1 초, 커밋 안 함). 엔진 = origin/main 464352b 위, 1280×720 lockstep, 스크래치 홈, `--skip-movies`, 레벨마다 `--game L`
+ 경로 `-Dgow.auto.route` (+ `-Dgow.auto.route.nofight`, BMI 는 `--boss-demo`).

### 48.1 장면 대응
| 영상 (초) | 장면 | 원작 (스크립트 · 데이터) | 엔진 | 결과 |
|---|---|---|---|---|
| 1960–1990 | PAND03G 홀 입구 · 붉은 Ω 자물쇠 문 클로즈업 · 문 앞 불기둥 → 홀을 걸어 감 | playCutScene-EnterRoomVol (z 1672 문턱, 조건 BossDead 아님 ∧ **MinoUnlock** = PAND03F NewCSM 353) → IntroCam · Idle 12 초 | 단독 시작은 MinoUnlock 이 없고, `-Dgow.arrive.state=PAND03G:GMinoUnlock=true` 로도 문턱 밖 순간이동이 이웃 이음으로 레벨을 다시 만들어 못 봄 | [미대조] |
| 1995–2010 | 문 앞 → 동영상 P03MP06 (BMI 가 문을 부숨) → BMI 머리 · 크레토스 | CloseToDoorVol → P03MP06 → 501 → Goto BossSpawnPoint · 231 → minoBoss 스폰 · MPGpancam | r16: 같은 순서 (19.95 초 CloseToDoor → 20.10 스폰 → 22.98 UnIdle) | 같음 |
| 2020–2070 | 단계 0: 배출구 증기 · 갑옷 막대 HUD (회색 + 초록 점) · 거북 기절 (흰 증기 공 넷) | §37 §10 | `--boss-demo` r2 | 같음. 단 엔진 첫 실행 r7 (route `post:501` 로 곧바로 스폰) 은 오른쪽 HUD 가 초록 막대 — 레벨 HUD 준비 전 Event 1 [하네스, 실제 흐름은 동영상 뒤라 영향 없음 추정] |
| 2080 | 올라타기 안내 589 "화면에 표시가 뜨면 왼쪽 스틱으로…" (초 0, ○ 대기) | SwirlyMessage-EvtCombatMove 1035(450) | 같은 문구 · 같은 시점 | 같음 |
| 2090–2125 | 등에 올라탐 · **화면 위 회전 화살표 아이콘** (2095–2099 Up180, 2125 360°) · 황금빛 등딱지 | 451–453 TimedPress 206 arcPress180rand / 207 arcPress360rand | 고치기 전: 아이콘이 한 번도 안 뜸 → **고침 (48.2)**. 황금빛 등딱지는 엔진도 같다 (r15 `f000036`) | 고침 |
| 2140 · 2260 · 2320 · 2398 | 문 쪽 BMI 주위 보라 사각 기둥 | 크레토스가 **포세이돈의 분노** 를 쓰는 프레임에만 (번개 블레이드) — 40 §9 / 35 §4.6 의 "360 Lightning" 감산 안개 교체 | 마법 대조 안 함 | 설명됨 (BMI · 레벨 이펙트 아님) |
| 2250–2400 | 단계 2–3: 갑옷 깨짐 · 초록 HP 막대 · 통나무 · 2401–2405 왼쪽 HUD 꺼짐 (보스 막대만) → 레버 쪽 계단 | §37 §10 | 데모 r2 (갑옷 · 통나무 · 단계 3) | 대체로 같음. 2401 HUD 자동 숨김 [미대조] |
| 2420–2440 | 레버 클로즈업 (Ω) · 레버 당김 · 불붙은 통나무 | gologlever (PushPull 15, 과제 351) · logArrow | 데모 | 같은 장치 |
| 2450 | 메시지 198 "이미 썩어가는 미노타우로스의 시체가 당신 위에 매달려 있습니다." | DeadBossExamine R2 (BossDead) — PAND03G · PAND03I 둘 다에 있음 | PAND03I 홀에 BMI 시체 (r4 `f000000`) | 문구 표 그대로 [미대조: R2 실행] |
| 2457–2490 | 나선 경사로 · 용암 기둥 → 세이브 질문 | PAND03H 세이브 포인트 29 (−319, 560, 1895) | r3 · r8 같은 경사로 · 빛기둥 | 같음 |
| 2500–2530 | 제단 계단 · 붉은 융단 · 책 → 메시지 153 (2 쪽) "…하지만 너무 큰 슬픔에 내 영혼은 의심하기 시작했다. …파토스 베데스 3세" | NewBook CSM 007 id 352 (1048) | r8: `use` (R2) → 메시지 153 같은 문구 · 2 쪽 | 같음 |
| 2540–2560 | 관 뚜껑 밀기 → 관 위에서 R2 연타 → 머리 뜯기 | CoffinLidCrank (Push 024 CoffinLid, 과제 140) → ArtSonMain TAndF 001 CoffinHeadRip 141 → 메시지 257 카드 | r9: 손잡이 (328.7, 723.5, 1912) 축 −x → 크랭크 140 완료 → 손잡이 (353.4, 735.4, 1900.7) → 연타 → 카드 257 | 같음 |
| 2570–2640 | 나선 경사로를 내려가 PAND03I 홀 (BMI 시체) → 하데스 얼굴 문 | LoadH / LoadF 볼륨 | r11 | 같음 |
| 2650–2690 | 동영상 MagicRTD (자막 "하데스 신이여!" · "네 능력만으론 최종 임무 완수는 역부족이다" · "이 무기를 받거라, 크레토스" · "너게 하데스의 유령들을 주겠다") → 카드 150 "하데스의 군대를 얻었습니다." → 메시지 152 | MAmagicIcon EntVol → MagicRTD → 501 → Msg2 150 → Msg3 152 | `--skip-movies` → 카드 150 → 메시지 152 (같은 문구) | 같음 (동영상 자막은 엔진 동영상 경로, 33 문서) |
| 2684–2690 | DMConCam 하데스 얼굴 클로즈업 → 홀 → 해골 · 하피 출현 | EvtMsg3Done → DMCEnemies 7 (해골 R_SK4 ×4 · 하피 R_HA2 ×3) · dogIn, 6.5 초 WorldIdleOff | r11 같은 구도 · 같은 적 | 같음 |
| 2700–2740 | 새 마법으로 싸움 (14 처치) | LevelData1 = 14 (스포너 7 × 2) | r11 `kill:` 로 14 → DMCCamOn · dogOut · 얼굴 말함 | 같음 (보통 봇은 2 번 죽음 — r4) |
| 2750 | 메시지 101 "크레토스. 신들이 함께 할 것이다. 올림푸스의 이름으로." · 말하는 얼굴 | Dummy4Msg1 (6 초) · TalkAndSwitchOffhead $M01HadesFO | 같은 문구 · 같은 얼굴 (r11 `f000092`) | 같음. 얼굴 붉은색이 영상보다 진함 [미대조: 재질 애니] |
| 2760–2792 | PAND03F 사암 방 → 소용돌이 → 관을 따라 잠수 | BackToHub LoadVol (영역 0) → WarpVol (영역 1, (2032…2192, −344…−152, 1560…1752)) | r13: 수면 −100 에서 □ 잠수 → 관 (911, −360, 2470) → (1303, −359, 2214) 에서 봇이 막힘 (관 굽이) | [미대조: Warp 까지 못 감] |
| 2793–2810 | PAND00C 관에서 위로 → 구멍 밖으로 튀어 나옴 · 구역 제목 "판도라의 반지" · 물에 잠긴 고리 · "물 속에 잠겼습니다. 작동시킬 수 없습니다." | WarpFromLvl3F (−121, −251, 1005) Lvl3TubeCam2 | `--arrive PAND03F` (+ `-Dgow.arrive.heroState=SWIM:DIVE`): Warp 점 아래 바닥이 없어 `snapToFloor` 가 관 바닥 (−132, −380, 1016) 에 세움, 위 수면 없음 → 서 있음 | **차이 [미해결]** (48.3) |
| 2840–2880 | 석상 복도 · 줄 레버 | PAND00C / PAND04AA | — | D 구간 |

### 48.2 엔진 결함과 수정
1. **보스 동기 기술의 미니게임 아이콘이 한 번도 뜨지 않았다 — BMI 올라타기 회전 화살표 (영상 2095–2099 · 2125, 엔진 전체: 히드라 810 · 아레스 · 분신 · 가족 포옹의
   TimedPress/연타도 같음)** [확실: 디스어셈블]. TimedPress 시작 0x192ED0 은 SCRIPT 명령 (0x193274 에서 등록) 이고 종류에 따라 0x21DA50(3, 0..9) 를 조건 없이 부르며
   (0x1930DC–0x193178), 끝 0x192C90 이 −1 을 넣는다 — 적 잡기 결합이든 보스 동기 기술이든 같은 영웅 기술 이벤트다. 엔진 `GameScene.minigameIconIndex` 는
   `grab.timed()` (적 결합) 만 보아 `BossSync.timed()` (보스 쪽 TimedPress, BMI 451–453 의 206/207) 는 버려졌다 (BMI 데모 녹화에 아이콘 없음).
   → `MinigameIcon.heroMinigame(timed, qte)` (TimedPress 종류 아이콘 / 연타 진행 = 10 [추정, 35 §6 과 같은 근사]) 를 보스 동기 중에도 쓴다. 메시지 · 질문 중에는
   0x21E908 이 아이콘을 다시 고르지 않으므로 (§18) 숨긴다 — 올라타기 안내 589 (초 0) 를 ○ 로 닫으면 화살표가 뜬다 (r15: 360° 아이콘이 영상 2097 과 같은 화면 위
   가운데-왼쪽). 시험 `BmiFightTest.ventsStunRideAndLog` (올라타기 동안 180° 4..7 · 360° 8..9 아이콘). 증거 `01_bmi_ride_icon_v2097_v2125_vs_engine.png`.
2. 진단 [엔진 전용]: `-Dgow.debug.items` — 레벨 R1 오브젝트 목록 (종류 · 손잡이 · 잡기 판정 값) 과 R2 마다 잡기 질의 (발 · 정면 · 결과). PAND03H 관 뚜껑을 못 잡던
   원인이 하네스 (돌기 단계가 영웅을 손잡이 앞 7 너머로 밀어 (P − J)·축 &lt; 0) 임을 이것으로 확인.

### 48.3 남은 것 · 미확인
- **PAND00C 허브 Warp 도착 (WarpFromLvl3F, 영상 2793–2796)**: 원작 Warp_Jump 는 Start 행렬을 그대로 쓴다 (바닥 맞춤 없음, §13.9) — 영상은 관 안에서 위로 올라와
  구멍 밖으로 튀어 나온다. 엔진 `placeSpawn` 은 Warp 점 아래 정적 바닥이 없으면 (`snapToFloor`, 반경 256) 관 바닥 (−380) 으로 옮기고, 관 벽은 Slide (SlideNoShadow
  407 면) 뿐 Water 면이 없어 물 상태도 아니다. 위로 올리는 힘 (관 물살? 영웅 상태?) 을 찾지 못해 고치지 않았다. 고리의 물 (goring3water CDV_ring3water, y 8…104)
  · 물 빠짐 상태 (LevelData 9) 도 단독 시작에선 원작 진행 상태와 다르다 [미확인].
- PAND03G 입구 IntroCam (영상 1970–1990): MinoUnlock (PAND03F GlobalData 1 = PAND03G 3, 이름 공유 §17) 이 있어야 하고 문턱 (z 1672) 을 바깥에서 넘어야 한다 — 하네스로
  재현 못 함 (문턱 밖 순간이동 → 이음으로 레벨 재생성).
- BMI 장면의 하얀 덩어리 (엔진 r2 `f000058–064`, 통나무가 맞을 때 BMI 가슴 앞): 증기 (goSteamVent01) 를 끄고도 남아 증기는 아니다 (임시 실험, 되돌림). 통나무 불
  (golog FXC_FWemit1) · gologShatter 쪽 [추정]. 영상의 통나무 적중 순간 1 초 프레임이 없어 대조 못 함. ST2part1 크기 = 2.1–6.3 + (−141…+329)·t (0.2 초) — 데이터상
  가는 증기 줄기라 영상 2210 과 맞다.
- PAND03F 관 잠수 (영상 2771–2792): 봇이 관 굽이 (1303, −359, 2214) 에서 막혀 WarpVol (2032…2192, −344…−152, 1560…1752) 까지 못 가 Warp 연출을 보지 못했다.
- 2401 왼쪽 HUD 숨김 (전투 밖 자동 숨김), DMC 하데스 얼굴 색, MagicRTD 자막 타이밍은 대조하지 않았다.

### 48.4 시험 · 증거
`BmiFightTest.ventsStunRideAndLog` (+ 아이콘). 증거 `work/verify/ep4_c/` (커밋 안 함): `01_bmi_ride_icon_v2097_v2125_vs_engine` (영상 2097 · 2125 | 고친 뒤 360° 아이콘 ·
황금빛 등딱지), `02_pand03h_book_coffin_v2530_v2550_vs_engine` (책 153 · 뚜껑 · 머리), `03_pand03i_army_of_hades_v2682_v2750_vs_engine` (메시지 152 · DMConCam ·
메시지 101), `04_hub_arrival_v2794_v2796_vs_engine` (관 바닥에 선 엔진 ↔ 구멍 밖으로 나오는 영상).

## 49. QA vs PS2 영상 4화 — 판도라의 반지 마무리: 고리 크랭크 · 세이브 · PAND00D 빛 방 · 가시 굴 싸움 · 눈 방 마지막 손잡이 → 빛줄기 동영상 p00MP03 → 엘리베이터 → PAND04AA 사티로스 → Semo06 (영상 2880–3860 초, 2026-10-29)

영상 = 7QAMXQj38Io (4화, 전사 (보통)), 프레임 422×317 (`work/verify/ep4_d/video/v<초>.jpg` 48 장, 커밋 안 함). 엔진 = 464352b 위, lockstep 녹화, 스크래치 홈,
경로 하네스 (`-Dgow.auto.route`, `-Dgow.arrive.state=PAND00C:L3=true,L4=true,L5=true,L7=true,L8=true,L9=true,GCrushersOff=true;PAND00D:GCrushersOff=true` = 고리 1·3·4 남쪽 ·
해골 문 · 고리 3 문 · 물속 해골 문 · 분쇄기 끔, 손잡이 100 % = `post:1023:100:22`).

### 49.1 장면 대응
| 영상 (초) | 장면 | 원작 (스크립트·데이터) | 엔진 | 결과 |
|---|---|---|---|---|
| 2885–2970 | 고리 회랑 · 톱니 문 · 크랭크 받침 | PAND00C 고리 크랭크 (§36) | §36 에서 대조 | 이번엔 몰지 않음 |
| 2990–3000 | 세이브 질문 "제우스께서 게임 진행 상황을…" | SaveGameZone | §33 · §36 | 같은 메뉴 (몰지 않음) |
| 3000–3085 | 두 부조 · 세이브 빛기둥 방 (PAND00D 서쪽, 세이브 (−2022, 80, 1156)), 쪽지 164, 얼굴 샘 (체력 · 마법) | PAND00D NewBook (1048 id 125 → 메시지 164) · Health/MagicShower (CSM 18, 과제 123 · 124 → 1023 100 %) | f1 · f2: 같은 방 · 같은 부조 · 쪽지 164 같은 글 (2 쪽) · 샘 3.4 초 100 % → 아이템 카드 113 · 114 | 같음. **바닥 · 샘 물받이의 흰 반짝이 물 (영상 3010 · 3045 · 3085) 이 엔진에 없다** (49.4) |
| 3125–3300 | 크랭크 · 문, 회랑, 가시 굴 싸움 → 빨간 오브 | PAND00C/00D 고리 · 분쇄기 굴 (Spawner2–4 해골) | — | [미확인] |
| 3310–3345 | 눈 방 석상 손잡이 마지막 돌리기 | PAND00C EyeDoor (goeyedoor 과제 22) | `post:1023:100:22` | 같은 흐름 |
| 3345–3400 | 빛줄기 · 가시 굴 · 사막이 타는 동영상 | 204 → PAND00D FakeEyeDoor-EvtAllLinedUp → **p00MP03** | 동영상 재생 (b2) | 같음 |
| 3405 | 동영상 끝 → 톱니 크랭크 클로즈업 | 다시 읽은 PAND00C: EyeDoor-EvtResetAfterMovie · **FinalEyeOffCam1–3 (EyeOffCam)** | 고치기 전 없음 → 고친 뒤 4.0 · 8.0 초에 EyeOffCam (b3) | **고침 (49.2)** |
| 3425–3445 | 피 묻은 가시 굴 → 해골 문 아치 | 분쇄기 굴 → 고리 1 | b5 (걸음 + 순간이동) | 같은 길 |
| 3465–3485 | 둥근 바닥 가운데 레버 → 엘리베이터 | ElevatorLever (Push, 과제 111) → 1023 100 % → WarpToLvl4 LoadForWarp Pand04AA, 30 초 뒤 Warp | **고치기 전: 레버가 숨은 채 (205 가 안 옴) → 진행 불가** → 고친 뒤 LeverOn · 동쪽에서 R2 → 100 % → 30 초 뒤 Warp (b8) | **고침 (49.2)** |
| 3485–3585 | PAND04AA 손 위 도착 → 사티로스 등장 · 계단 광장 싸움 | WarpFrmCore (camInsideHands) · satintro · SaytrEnemies | 60 초 봇 (lv_PAND04AA) | 같은 광장 · 같은 적 (몰아서 대조하지 않음) |
| 3605–3745 | 기억 동영상 (아레스에게 맹세 · 사슬) | PAND04AA Semo06 → 끝 LoadWad Pand04C | — | 동영상 (엔진 재생기) |
| 3765–3860 | 하피 · 광기의 절벽 입구 → 세이브 완료 | PAND04C (영상 E 구간, §50) | — | 범위 밖 |

### 49.2 엔진 결함과 수정 — 동영상이 다른 슬롯을 내리고, 다시 읽은 레벨의 시작 전송기가 이웃에 닿는다
**영상 3405–3485 · 엔진 고치기 전**: 마지막 손잡이 뒤 p00MP03 이 끝나면 PAND00D FakeEyeDoor-ReloadWadC 가 `LoadWad Pand00C` 를 부르지만 엔진은 이미 슬롯에 있는 레벨이라
아무 일도 없었다 → PAND00C EyeDoor-EvtLoaded (202 "telling wad D that C has reloaded") 가 안 옴 → FakeEyeDoor-EvtCReloaded · EvtSendSwitchStatue (205) 없음 →
ElevatorHousing-EventSwitchStatue 가 엘리베이터 석상 · **레버 (LeverOn)** · 리프트를 켜지 않아 PAND04AA 로 갈 수 없었다 (순회 검증은 1023 을 강제해 지나갔다, 36 §4).
가짜 눈 문 (FakeEyeDoor) 도 진짜 문과 겹친 채, EyeOffCam 연출 (영상 3405) 도 없었다.

원작 [확실: 디스어셈블]:
1. **PlayMovie 0x1B3440 · PreloadMovie (0x1AF848) → 0x1B3490**: 미리 읽어 둔 이름 (0x33691E) 과 다르면 (0x28DF50) "Levels" 힙 (0x29DAF4) 의 가장 큰 빈 블록을
   0x13E238(힙, 8) 로 재고, **0x322417 이하면** (0x1B350C–0x1B3518) 영웅 문맥 +0x88 과 슬롯 +0x5C 를 비교해 영웅이 있는 슬롯 a2 를 찾고 (0x1B3520–0x1B358C)
   **0x1BC940(1 − a2, 3) → 0x1BBA20(1, 슬롯, 3)** 으로 다른 슬롯을 내린다 (슬롯 +0x68 & 1 일 때만).
2. "Levels" 힙 = Heap_SetupLevels 0x1BB5F0 이 루트 힙에서 뗀 **0x1000300** 바이트. Tag_Header 0x185760 → 0x186590 이 레벨 WAD 의 HEADER (0x378) 값 (4 정렬,
   빈 블록보다 크면 그만큼) 을 이 힙의 위 / 아래 끝 (0x29D4C8 → 0x13DE68 / 0x13DCB8) 에서 하위 힙으로 잡는다. Hero · Slots · Upgrade 힙은 루트의 따로 블록 (0x1BB510).
   HEADER 값: PAND00C 7 MB, PAND00D 8 MB → 빈 ≈ 1 MB → 눈 방 p00MP03 은 PAND00C 를 내린다. PAND00B 9 + PAND00E 2 → 빈 5 MB → 죽어 가는 병사 p00MP02 는 내리지 않아
   이웃 501 이 산다 (§32) — 데이터가 두 경우와 맞는다.
3. 이벤트 큐는 하나 (0x303DB8, §32) — 다시 읽힌 PAND00C 의 시작 큐에서 도는 전송기 (EventTransmitter 모드 5, EyeDoor-EvtLoaded 202) 를 PAND00D 센서가 받는다.

고침:
- `LevelLink.movieDropsOther` (Levels 힙 − 두 HEADER ≤ 0x322417) · `levelHeap` (WAD 앞 36 바이트) · `dropOther` [근사: 블록 머리 · 미리 읽은 동영상 버퍼 무시,
  영웅 슬롯 = 엔진 현재 레벨].
- `GameScene.movieHeapCheck`: 지금 · 이웃 슬롯 스크립트의 PlayMovie (op 0) · PreloadMovie (op 2) 에서 (같은 이름 미리 읽기 뒤 PlayMovie 는 건너뜀) 내리기로 하면
  다음 틱 머리 (`movieDropTick`, 스크립트 순회 밖) 에 `unloadOther` (레벨 기억 · 렌더 · 충돌 · 라이트) + `link.dropOther()` — 이후 LoadWad 가 레벨 기억으로 되살린다
  (`SceneNav.setOther` → `ScriptWorld.restore`, 모드 5 생성 센서 · 전송기는 다시 돈다). `--skip-movies` 도 같은 규칙. 비교용 `-Dgow.nomovieheap` [엔진 전용].
- `SceneNav.setOther`: 새로 읽는 이웃 세계는 **시작 큐 전에** 이벤트 큐를 잇는다 (예전엔 시작 뒤 — 시작 전송기가 이웃에 닿지 않았다).
- 결과 (b3 · b5 · b8): 동영상 → "다른 슬롯 PAND00C 내림" → 0.1 초 LoadWad → "이웃 레벨 기억으로 되살림: PAND00C" → EyeDoor-EvtLoaded → FakeEyeDoor-EvtCReloaded ·
  VisOff → 205 → ElevatorHousing-EventSwitchStatue · ElevatorStatueOn · **LeverOn** · LiftOn → EyeOffCam (4 · 8 초) → 레버 (손잡이 동쪽 (−200, 0, 1258) 에서 R2) 1023
  100 % → WarpToLvl4 LoadForWarp Pand04AA → 30 초 뒤 Warp.

동영상 · 힙 표 (도구 `MovieDrop`, 이웃 후보 = `work/verify/levels.txt`): 동영상을 트는 37 레벨 중 대부분의 이웃 짝이 "내림" (예 ATHN01E a01Mp03 · ATHN03A a03Mp01 ·
PAND04AA Semo06 · PAND04E Semo07 · DEST03 d01Mp0x) — 그 스크립트들은 동영상 끝에 다른 슬롯을 다시 LoadWad 하거나 (PAND04AA MpegPlayer-VisLoad → Pand04C,
ATHN06C → WarpToAth7 LoadForWarp) LoadWad 볼륨으로 돌아온다. 내리지 않는 짝: PAND00B/00E, PAND05B (5 MB) 의 이웃 전부, ATHN08B/08C, HDES01E.

### 49.3 시험 · 회귀
`EyeBeamElevatorTest` (2): movieHeapCheckDropsTheOtherSlot (0x1B34F0 · 0x1B3504 · 0x1B350C/10 · 0x1B3594 · 0x1BB7C4/D8, HEADER 7/8 MB · 9/2 MB, dropOther → LoadWad 다시 올림),
reloadedHubTellsEyeRoomAndEnablesTheElevator (두 슬롯 세계: 204 → p00MP03 → 501 → LoadWad Pand00C → 기억으로 되살린 PAND00C 202 → FakeEyeDoor-VisOff → LevelData 22 · LeverOn).
전체 772 통과.

60 초 봇 (쉬움, `-Dgow.nomovieheap` 비교, 순간이동 · 방문 · 사망): ATHN01E 5 · 8/25 · 0 = 같음, DEST03 4 · 7/7 · 0 = 같음, PAND00C 12 · 21/27 · 0 = 같음,
PAND02A 2 · 5/15 · 0 = 같음 (마지막 자리만 다름), PAND04AA 1 · 0/5 · 0 = 같음, ATHN06C 12 · 12/13 · 0 = 같음 (a06Mp02 · Semo03 미리 읽기에서 ATHN06B · ATHN07 내림,
막은 전환 [ATHN07, ATHN06B] → [ATHN06B]).

### 49.4 남은 것 · 미확인
- PAND00D 샘 방 (영상 3010 · 3045 · 3085): 원작은 방 바닥 웅덩이와 샘 물받이에 흰 반짝이 물 (샘을 쓰는 동안 얼굴에서 쏟아지는 물줄기) 이 보인다 — 엔진은 바닥이 마른
  돌, 샘 물줄기도 안 보인다 (OrbsWaterfall/HealthWaterfall/MagicWaterfall GO · 물 재질 · 이미터 중 무엇인지 [미해석], `p_fountain_room.png`).
- 영상 3125–3300 (크랭크 · 회랑 · 가시 굴 싸움) 은 엔진으로 나란히 몰지 않았다. PAND04AA (3485–3585) 는 60 초 봇으로 같은 광장 · 사티로스 (R_SAT 템플릿 130) ·
  DMC 문 순서를 봤다 (`e_pand04aa.png`, satintro · Semo06 동영상은 `--skip-movies`).
- 영웅 슬롯: 원작은 영웅 문맥 (0x3300CC 가상 함수 → +0x88) 과 슬롯 +0x5C 를 비교한다 — 엔진은 현재 레벨 (LoadCheck · 이음) 로 본다 [근사]. 경계에서 동영상이 돌면 다를 수 있다.
- 빈 블록은 두 HEADER 만 뺀 값 — 미리 읽은 동영상 버퍼 (0x29D96C = Levels 힙) · 블록 머리 · 조각남은 무시 [근사]. 경계값 근처 짝 (DEST02/DEST03 빈 3.0 MB) 은 어긋날 수 있다.
- 엘리베이터 레버는 손잡이 쪽 (동쪽) 에서만 잡힌다 (0x1C3F18 손잡이 축 판정) — 영상 3465 의 크레토스도 레버 왼쪽 (화면) 에 서 있다.

### 49.5 증거 (`work/verify/ep4_d/`, 커밋 안 함)
`video/` (영상 48 장), `p_beam_elevator.png` (위: 영상 3345 · 3365 · 3405 · 3465, 아래: 엔진 손잡이 뒤 동영상 2 장 · EyeOffCam · 엘리베이터 레버).
`p_fountain_room.png` (위: 영상 3010 · 3025 · 3045 · 3065 · 3085, 아래: 엔진 쪽지 · 샘), `e_pand04aa.png` (엔진 PAND04AA 도착 · 사티로스 봇 50 초).
## 50. QA vs PS2 영상 4화 — 광기의 절벽: PAND04AA 세이브 · 구역 제목 15 → PAND04C 절벽 (줄 · 우리 승강기 · 세이브) → PAND04D 탑 · 줄다리 → PAND04E 헤라 석상 · 소용돌이 물길 → PAND04EE 테트리스 블록 방 · 헤라의 목걸이 (영상 3860–5195 초, 2026-10-29)

영상 = 7QAMXQj38Io (4화, 전사 (보통) = `-Dgow.difficulty=1`, 세이브 화면 3960 · 4880 에서 확인), 프레임 422×317 (`work/verify/ep4_e/video/v<초>.jpg`, 커밋 안 함).
이 구간은 하데스 (HDES) 가 아니라 판도라의 사원 **광기의 절벽** (구역 제목 15 · 16) 이다 [확실: 스크립트 ZoneReportGroup · 메시지 630–632]. 엔진 = origin/main 위, 1280×720
lockstep, 스크래치 홈 (`home-ep4e`), `--skip-movies`.

### 50.1 엔진 결함과 수정
1. **밀기 상자를 그냥 걸어 지나갔다 — PAND04EE 테트리스 정육면체 · 블록을 면에 서서 잡을 수 없음 (영상 4880: 정육면체를 잡고 창살 문까지 민다, 엔진 전체).**
   `SceneNav.collideBodies` 가 영웅 몸 구 밀어내기에서 **모든** 밀기 상자 (Kind.PUSH) 를 레버·크랭크와 함께 뺐다 ("손잡이에 닿아야 하므로"). 정적 이동 선분
   (`NavWorld.blockMove`) 은 정적 시트만 보므로 상자 CDV 는 영웅을 전혀 막지 않았다 — `--game PAND04EE` 에서 (815, 880, −4442) → 동쪽으로 걸으면 gopushbox1
   (861, −4442, 반 너비 16) 을 뚫고 문 앞 (916) 까지 가고, R2 를 눌러도 잡기 거리 (앞 점 ↔ 옆면 가운데 &lt; 20, 0x1C3CD8) 밖이라 잡히지 않았다 (`01_pushbox1_engine_before`).
   원작 [확실: 데이터 · 코드]: 몸 질의 0x1E2100 은 GO 충돌 인스턴스를 면 속성 마스크로만 거른다 — ATHN06B 석상 CDV 는 영웅을 막는 hull (Entities_groundRockNOAI) 과
   적을 막는 hull (…NoPl) 을 따로 두고, PAND04EE 블록 · DEST02 돌 상자 hull 에는 거르는 속성이 없다. 잡기 0x1C3918 → 0x1C3CD8 은 면에 막혀 선 자리에서 닿는다.
   → 붙지 않은 밀기 상자는 막고, **붙어 있는 그 상자만** 뺀다 (`SceneNav.bodySkip` / `leverOwners`) [근사: 원작은 붙은 동안 영웅을 손잡이 자리에 바로 둔다 0x1C43C0].
   레버·크랭크류는 예전대로 뺀다. 고친 뒤 (833, −4444) 에서 막혀 잡고 (`[밀기 잡음] gopushbox1 … 손잡이 [845.3, 896.0, −4442.4, −1, 0]`) 928 까지 민다
   (`01_pushbox1_engine_after`, 영상 4880 과 같은 자리). 전 WAD 밀기 상자 21 개의 hull 옆면은 Push 몸 반 너비와 같거나 몇 유닛 차 (PAND04G 석상 +z 33 ↔ 24,
   PAND03B 미로 벽은 관절 0 이 −32 라 GO 기준 ±32) — 몸 구 12 로 막힌 자리의 앞 점은 모든 면에서 잡기 거리 20 안이다. 발리스타 (손잡이 관절) r15 경로 앞 64 초
   (§22) 그대로 (−667 → 14, 4728). 60 초 봇 (쉬움) DEST02 · ATHN06B · ATHN01D · ATHN05B · PAND02A · PAND01A · ATHN01C · PAND04C 결과 (방문 · 순간이동 · 사망) 고치기 전과 같다.
2. **우리 승강기를 돌리면 우리만 올라가고 크라토스는 구덩이 바닥에 남았다 (영상 4 4010–4024: 우리 안의 크랭크를 돌려 함께 절벽 위 세이브 턱으로, 엔진 전체).**
   PAND04C gocavecrank · gohangcrank = Push "Pand04CElevs" Type 3 (기중기형 크랭크) — GO 자체가 둥근 우리이고 손잡이 관절 (−217.6, 236, −1374.9) 이 우리 바닥 위다.
   `GameScene.physics` 는 내비게이터가 붙잡기 상태 (INTERACT · PUSH) 면 바로 돌아가 발판 실림 (`SceneNav.carry`) 을 건너뛰었다 → 진행 시간으로 우리 몸이 오르는 동안
   영웅은 y 219 에 남아 빈 바닥에서 크랭크 동작만 했다 (`--game PAND04C`, 경로 `-218,236,-1352!;wait:1;crank:14:-218,-1375`).
   원작 [확실: 호출 순서]: 영웅 갱신 0x1EBD78 은 지면 GO 실림 0x1E6248 (+0x24C GO 의 +0x250 관절 이번 틱 움직임) 을 **0x1EC514 에서 상태와 무관하게** 먼저 하고,
   그 뒤에 +0x174 0x4000 이면 붙잡기 0x1C4258 (0x1EC60C) 로 간다. → 붙잡은 동안 (INTERACT · PUSH) 에도 발밑 발판으로 실린다. 고친 뒤 2.3 → 15 초 동안
   y 219 → 747 (세이브 (−288, 752, −1408) 턱), 영상 4010 → 4024 (≈ 14 초) 와 같은 오름 (`02_cage_elevator_video` | `02_cage_elevator_engine_after`).
   60 초 봇 8 레벨 결과 같음 (DEST02 사망 0 → 1 은 이 고침 전 · 후 같다 — rebase 로 들어온 다른 작업, 지면 (2436, 274, 6662)).
3. **물속에서 크라토스 몸에 거품이 전혀 없었다 (영상 4 4428–4450 · 4520–4545 PAND04E 물길: 손발에서 흰 거품 줄기가 올라간다, 엔진 전체 — 3화 PAND02 물길도).**
   PAND04E 물길에는 레벨 거품 GO 가 없다 (PAND02F 는 golevelbubble* 10 개) — 영상의 거품은 영웅 것이다. 원작 [확실: 디스어셈블]: 영웅 생성 0x1D62B0 이 만드는
   영웅 부품 (생성 0x1FE270, vtable 0x2BADE0) 의 처음 갱신 0x1FE3A0 이 거품 객체 0x1FF0A8 를 만든다 — R_PERMK **gobubpart** (PTC_Bubpart · MAT_bubble2TX,
   수명 1 초, 이미터 0 개) 파티클 서버에 콜백 0x1FF3E0 을 걸고 영웅 관절 여섯 (표 0x29F2C0: lMetatarsal · rMetatarsal · pelvis · lwrist · rwrist · neck) 을 잡는다.
   갱신 0x1FE518 은 물 상태 (+0x174 &amp; 0x380) 면 프레임마다 누적 +0x20 += 0.5 + |속도 +0xE0| × 0.5 / 16 (0x1FE938 – 0x1FE99C), 잠수 돌진이 막 시작된 프레임
   (+0x2D8 0x80000, 지난 0x40000 충전) 이면 +100 (0x1FE9B4). 콜백은 칸마다 살아 있는 거품을 위치 += 속도 (프레임 단위) 로 옮기고 나이 + 0.01 ≥ 1 이거나 수면
   (+0x230) 위면 지우며, 누적 ≥ 1 인 동안 새 거품: k = 난수 [0, 4), 위치 = E + (R − E)·r² (E = 발 · 발 · 손목 · 손목 [k], R = 골반 · 목 [k/2], VSUB · VMULx · VADD
   0x1FF73C – 0x1FF758), 속도 = (0, (0.5 + 0.5 r')·16/60, 0) (VADDx 의 y 만, 0x1FF76C – 0x1FF7A0 — 위로 초당 8 – 16), 누적 −= 1.
   엔진은 이 부품이 없었다. → `HeroBubbles` (`GameScene` 이 SWIM 상태 · 속도 · 수면 · `HeroRig.jointWorld` 로 매 틱), `EffectSystem.shapeDef` (이미터 없는 PTC 모양 →
   VU 식 정의) [근사: 이미터 8 개를 틱마다 새 거품 자리에 두고 한 개씩 · 수면 위 점은 내지 않음 (원작은 만든 뒤 지움) · 살아 있는 거품의 수면 지우기 없음 ·
   돌진 시작 = 이번 틱 돌진 ∧ 지난 틱 아님]. 잠수해 관을 따라가면 손발에서 거품 줄기가 뒤로 남는다 (`05_swim_tube_engine_no_bubbles` → `05_swim_tube_engine_bubbles_after`,
   영상 `05_swim_tube_video_4410_4550`).
4. 하네스 [엔진 전용]: 경로 단계 `stick:초:x,y:버튼+` — 버튼 이름 끝 `+` 면 단계 내내 누름 (□ 를 누른 채 물속 관을 따라 잠수).

### 50.2 장면 대응 (경로 [확실: 스크립트 · 영상])
| 영상 (초) | 장면 | 원작 (스크립트 · 데이터) | 엔진 | 결과 |
|---|---|---|---|---|
| 3850–3878 | PAND04AA 세이브 (빛기둥 · R2) · 건축가의 책 "광기의 절벽이 당신 앞에…" · 상자 | SaveGameZone 30 (메시지 630 "광기의 벼랑"), NewBook | r4: 같은 석상 다리 · 세이브 · 책 · 파란 상자 | 같음 |
| 3880–3890 | 좁은 협곡 · 구역 제목 15 "광기의 절벽" · 해골 싸움 | PAND04C ZoneReportGroup (−73, 223, −541 근처, GlobalData 4) → R_SK4 넷 | r4: 같은 협곡 · 제목 · R_SK4 스폰 넷 | 같음 (협곡 벽이 엔진에서 조금 더 밝은 주황 — 세이브 빛 [미확인]) |
| 3952–3966 | 비계 · 하피 · 해골 | RopeHarpies (HarpyLevel2AI3–6) · goharpyspawner (710, 260, −910) | 봇 순회 | 대조 안 함 (봇 전투) |
| 3966–3986 | 비계에서 줄 gorope2 를 잡고 협곡을 건너 끝의 가파른 줄로 절벽 위 | gorope2 TRAVERSE (742, 348, −1125) → (142, 288, −1424), 관절 145 | r19: `jump` 로 잡고 `push` 로 끝까지 — 가운데가 (525, 214) 로 처지고 끝 쪽이 가파름 | 같음 (끝에서 내려서는 동작은 대조 못 함) |
| 4004–4026 | 둥근 우리 승강기 · 크랭크 → 절벽 위 세이브 | gocavecrank (Type 3) · SaveGameZone 31 (−288, 752, −1408) | 고치기 전: 우리만 올라감 → **50.1-2 고침** | 고침 |
| 4030 | 세이브 화면 (기존 칸 "광기의 벼랑" 전사 (보통) 5:54:30) | 메시지 630 | — | — |
| 4040–4130 | 사다리 · 울타리 길 · 방패 석상 · 파란 상자 · 해골 문 앞 | SpawnTrigger1/2 · Chesty2/6 | r5 순회: 같은 장소 (−640, 1140, −1665) · (322, 1140, −1511) | 같음 |
| 4140–4210 | 어두운 굴 싸움 (사티로스) | MusicP4C2SatyrCave1 · CaveEnemies (R_SAT · R_SK4) | 봇 순회 | 대조 안 함 |
| 4225–4275 | PAND04D 파란 상자 · 둥근 탑 위 크랭크 → 다리가 돌아 붙음, 불화살 궁수 | gonewcranks (PAND4DCrank Type 1, id 430) · Archers 1–6 | r20: 같은 탑 · 크랭크 · 돌아오는 다리 · 불화살에 넘어짐 | 같음 |
| 4300–4330 | 메두사의 시선 · 둥근 방패 문 · 업그레이드 (블레이드 5 · 메두사 2) | PAND04E | 대조 안 함 (업그레이드 메뉴는 공통) | — |
| 4395–4405 | 헤라 석상 R2 → 메시지 254 (카메라는 크라토스에서 시작 → Key1Cam 얼굴) → ZipCam18, 이어서 아프로디테 석상 | LeftHera-UseWorldHeraNoKey (GlobalData 1 = 0) | r15: 같은 메시지 · 같은 순서의 카메라 · HUD 숨김 | 같음 (`04_hera_statue_*`) |
| 4428–4450 · 4520–4545 | 위 웅덩이 (수면 1478) 로 잠수해 물길 관을 따라 아래 물기둥 홀 (수면 1292) 로, 손발 거품 | 관 카메라 SwimCam1–13 · SwmRtrnCam1–6 | 같은 관 · 카메라. 거품 없음 → **50.1-3 고침** | 고침 (관 끝까지는 봇이 못 감) |
| 4460–4510 | 물기둥 홀 (얕음, 서서 걸음) · 돌 파편 싸움 · 업그레이드 (하데스의 군대 2) | gosndgrpunderwater 홀 | r16: 같은 둥근 물기둥 홀 · 얕은 물 | 같음 |
| 4560–4760 | 나무 턱 · 미노타우로스 · 사티로스 · 어두운 굴 (케르베로스 · 궁수), 4696 사망 → 체크포인트 (비계) · 짚라인 | PAND04E Enemies · BridgeSaytrs · CSgroup | 대조 안 함 (봇 전투) | — |
| 4770–4790 | PAND04EE 들어가며 문 닫힘 · 여는 카메라 (금빛 문 · 상자 둘 · 정육면체) | newTCSpawner → TetrisOpenCam (12 초 Idle) → TetrisCam1 | r7: 같은 카메라 · 같은 구도 | 같음 |
| 4800–4850 | 미노타우로스 다섯 · 해골 · 궁수 | MinoEnemies 1–5 · Enemies1 | 봇 | 대조 안 함 |
| 4855–4865 | 책 "내 영혼이 산산이 조각났듯이…" | NewBook (CSM 7, id 441) | — | 대조 안 함 |
| 4870–5130 | 테트리스 방: 블록을 밀어 회전판에 올리고 캡스턴 크랭크로 돌려 창살 문 앞 틀에 끼움 | 블록 6 (Push 447–452 Type 8), TetrisCrank 446 (Type 4, id 440) → SpinDeck · CollisionOff/On, 칸 볼륨 5 개 → LevelData 2–6 | 고치기 전: 블록을 통과 → **50.1-1 고침** (`01`) · 크랭크로 회전판 · 블록이 돈다 (r13) | 밀기 · 돌리기 같음, 풀이 끝까지는 못 몰았음 |
| 5140–5166 | 목걸이 제단 R2 → "헤라의 목걸이를 획득했습니다" 카드 → TetGateCam (창살 문 오름) → LadderCam (PAND04E 사다리 내려옴) → 크라토스 | Key1 (1018 질문 · 메시지 231 · Cam2On 0.1 · caveDoor1 AnimDoorUp · TransmitToWadE 241 3 초 · Cam3Off 8.5) | r14: 같은 카드 · 같은 두 카메라 · 이웃 PAND04E LadderAnimated-AnimDown | 같음 (`03_necklace_*`) |
| 5190 | 세이브 중 | — | — | 구간 끝 |

### 50.3 남은 것 · 미확인
- [미대조] 싸움 장면 (3885–3960 해골, 4000–4010 · 4060–4210 · 4560–4760 · 4800–4850) — 봇으로 그 자리까지 몰아 같은 적이 나오는 것만 봤다. 보통 난이도 피해량 대조 없음.
- [미대조] 테트리스 퍼즐의 끝 (칸 볼륨 다섯 → TetrisLowerCam · elevatorGrpAnim 되감기 · TopBlockCopy) — 블록 다섯을 제자리에 끼우는 경로를 봇으로 만들지 못했다. 블록이
  칸 볼륨 (EntrySensor, Marker 440–445 필터) 에 들어갈 때 센서가 오는지 확인 못 함.
- [미대조] gorope2 끝에서 절벽 위로 올라서는 동작 (영상 3982 → 3986), 짚라인 (4710).
- [근사] 거품: 살아 있는 거품이 수면 위로 올라가도 지우지 않음, 한 틱에 최대 8 개.
- [미확인] 엔진 협곡 (3880) 벽이 영상보다 따뜻한 주황 — 세이브 빛기둥 조명 반경일 수 있다.

### 50.4 시험 · 증거
`PushBoxBodyTest` (PAND04EE gopushbox1 Square 16 · 크랭크는 레버 목록 · 붙지 않은 상자는 몸 구를 면 + 12 로 밀어냄 · 붙은 상자는 밀지 않음),
`CageElevatorCarryTest` (PAND04C gocavecrank Type 3 · 손잡이 앞 발밑 = 우리 몸 · 길이 끝 자세로 실으면 y ≈ 747 이고 여전히 우리 바닥 위),
`HeroBubblesTest` (누적 0.5 + |v|/32 → 멈춤 초당 30 · 32/초 헤엄 90 · 물 밖 0 · 돌진 +100 · 위치 E + (R − E)·r² · R_PERMK BubpartShape 수명 1 · MAT_bubble2TX · VU).
증거 `work/verify/ep4_e/` (커밋 안 함): `01_pushbox1_engine_before` · `01_pushbox1_engine_after`, `02_cage_elevator_video` (4004–4026 2 초) · `02_cage_elevator_engine_after`.

## 51. 4화 마무리 회귀 점검: 60 초 봇 77 레벨 (464352b ↔ 0fe063a) · 하네스가 막은 전환과 동영상 슬롯 내리기 (DEST00 사망 0 → 2, 2026-10-29)

4화 QA 전 (464352b) 과 4화 끝 (0fe063a) 을 같은 60 초 봇 (쉬움 `-Dgow.difficulty=0 -Dgow.auto=1 -Dgow.auto.secs=60 --skip-movies`, 스크래치 홈) 으로 77 레벨
(이전 72 + PAND04AA/04C/04D/04E/04EE) 돌렸다. 모두 rc 0 · 예외 0.

### 51.1 달라진 레벨 (순간이동 · 방문 · 사망 · 막은 전환)
| 레벨 | 464352b | 0fe063a | 원인 |
|---|---|---|---|
| DEST00 | 2 · 5/5 · 0 · [DEST01] | 2 · 5/5 · **2** · [DEST01] | 하네스: 막은 전환 + 동영상 슬롯 내리기 (§51.2, 고침 → 0) |
| DEST02 | 7 · 14/14 · 0 | 7 · 14/14 · 1 | §46 접촉 피해: 봇이 분쇄기 TrapDamage 볼륨 gotrapdamage 로 순간이동 (부수기 전 Damage 33) — 의도대로 |
| ATHN06C | 12 · 12/13 · 0 · [ATHN07, ATHN06B] | 12 · 12/13 · 0 · [ATHN06B] | §49: Semo03 이 다른 슬롯 ATHN07 을 내려 전환 시도 자체가 없음 |
| ATHN08C | 0 · 0/0 · 18 | 0 · 0/0 · 14 | 아레스 보스전 (쉬움 봇은 늘 죽음) — 경로 차 |
| PAND03C | 3 · 6/10 · 0 · [PAND03D] | 3 · 4/10 · 0 · [] | 예전엔 20 초 제자리 뒤 적 포기, 이제 가까운 해골과 싸움 (오브 1) — 경로 차 |
| PAND03E | 4 · 7/7 · 2 | 4 · 6/7 · 2 | 순간이동 점 (101, 492, −522) 포기 — 같은 사망 수 |
| PAND00C · PAND00D · PAND01B · ATHN04A · ATHN05B · PAND04AA | ±1 방문 · ±1–6 순간이동 | | 경로 차 (사망 · 막은 전환 같음) |

나머지 65 레벨은 마지막 위치 말고 같다.

### 51.2 DEST00: 하네스가 막은 전환 + 동영상 슬롯 내리기 [확실: 엔진 로그 / 하네스]
봇이 DEST01 전환 (gowadloaddest01) 을 막은 채 DEST01 땅 (2304, 510, 1448) 에 서 있는데 Semo04 PlayMovie 의 힙 검사 (§49, 0x1B3490) 가
"영웅이 있지 않은 슬롯" = 엔진 현재 레벨이 아닌 DEST01 을 내림 → 발밑 충돌이 사라져 y −2029 까지 떨어져 사망. 체크포인트 (DEST00) 로 돌아간 뒤 한 번 더 낙사.
`-Dgow.nomovieheap` 이면 사망 0. 실제 플레이에서는 영웅이 그 전에 DEST01 로 전환하므로 영웅 슬롯이 DEST01, 내려가는 쪽은 DEST00 — 원작과 같은 상황이 아니다.

고침 [엔진 전용 하네스]: `AutoPlay.vetoed(level)` — 자동 플레이가 그 레벨로의 전환을 막았으면 `GameScene.movieHeapCheck` 가 그 슬롯을 내리지 않는다. DEST00 사망 2 → 0.
ATHN04B (a04Mp04 → ATHN04A) · ATHN05A · ATHN06C 의 내리기는 막은 전환보다 먼저라 그대로다.

### 51.3 남은 것 (4화)
- 체크포인트 다시 시작의 "로딩 중..." 화면 없음 (§47, FLP_HUDK 'Loading' 클립 PS2_4085, 발동 조건 미확인).
- PAND03C 끝 방 상자 빛 · 해골 수, PAND03D 줄 위 해골이 용암에 떨어져도 삶 (§46), 경기장 불 고리 출처 미확인.
- PAND00C 허브 Warp 도착 (영상 2793): 엔진은 관 바닥에 세움 (§48). BMI 흰 덩어리.
- PAND00D 빛 방 바닥 · 분수 흰 반짝이 물 없음 (§49). PAND04C 협곡 벽이 엔진에서 더 주황.
- 테트리스 퍼즐 완성 · 짚라인 오르기 미대조, 보통 난이도 전투 피해 미대조 (§50).
- 근사: 빛 없는 모델 틴트 예외 규칙 (§47), 사망 페이드 그리기 순서, 동영상 힙 "영웅 슬롯 = 엔진 현재 레벨".

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
