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
- 0x12ABA0 (알파 > 0 → Z 최대) 의 실제 효과 (§12.1 끝), 층 0 깊이 다시 그리기 ④ 의 목적, 거울 뷰 바탕의 원작 지움 색.
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
- 카메라 쪽 남은 것 (렌더 무관, 대부분 §10 이전부터): PAND02B 기본 시작 (모래 둔덕), PAND05B 기본 시작 (`camEleEnter` 눈 y 21 ↔ 영웅 y 259),
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
| DEST02 | ○ |  | PAND05B | △c | camEleEnter 눈 y 21 (영웅 259) — 바닥만 (§11 이전부터) |
| DEST03 | ○ |  | SEWR01 | ○ |  |
| HDES01A | ○ |  | SEWR02 | ○ |  |
| HDES01B | ○ |  | | | |

### 13.6 테스트
`LightServerTest` +1 (7): VU 식 `LightRig.shade(n, M, A)` — 0.8 × 1.5 = 1.2 + 1/128, 자르기는 M 곱한 뒤, 더할 색; R_HERO0 `MAT_maiFskinh` M = 0.8 · A = 0, R_CNM `MAT_lambert2` A = 1. 엔진 테스트 368.

### 13.7 남은 것
- ~~PAND01B ← PAND01A 이음 도착의 카메라 (`Follow` ↔ 탐침 `ShldDrCam`) — 가시 칸이 화면을 비우는 원인.~~ → §13.8
- 원작 프레임 지움 색 (ATHN03C 칸 4 틈, 거울 뷰 바탕 §12.6), 조명 경로 알파 qw78.w, 그림자 합성 (47 §7.4).

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
- 남는 것 [미확인]: `camEleEnter` (gostartwad 관절 카메라, 0x40000 고정 주시) 는 눈 (1081, 21, 1311) 고정, 관절 −Z 가 피치 +14° → −21° 로 내려가는데 엘리베이터는 눈보다 위에서 내려온다
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
- 꺼진 레벨: 스크립트 루프 (0x1A7D68 `컨텍스트 +4 & 4`) 가 건너뛴다 [확실]. 렌더·충돌·CMV 질의도 컨텍스트 서버를 따라 빠진다 [추정] — 그래서 OLYMP02 ← OLYMP01 (같은 좌표 공간) 에
  OLYMP01 입구가 보이지 않는다. LoadForWarp(떠나온 레벨) 은 다시 읽지 않고 (0x1BC110 = 1) +0x68 & 4 면 바로 준비 (0x1B0670) — 무덤으로 떨어지면 곧장 HDES01E 로 돌아가는 설계와 맞다.
- 엔진: Warp 는 다른 슬롯 없이 시작하는 지금 규칙 유지 (꺼진 레벨과 화면·스크립트가 같다). PAND02A ← PAND00C 의 Lvl2HallCam2 (떠나온 레벨 카메라) 는 원작에서도 꺼진 레벨 것이라
  후보가 아니다 [추정] — 엔진 `Follow` 그대로.

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
- 영웅 질의의 제외 마스크: 바닥 0x1E3298 · 몸 0x1E2100 = 호출 마스크 | 0x1010042 (Water·Death·AIBlock·GeneralGuide) | (영웅 +0x258 & 0x4300000).
  영웅 +0x258 = 0x500000 (Player 생성 0x1D63E0) → NoPlayerCollision. 바닥 호출 마스크 0x8020000 (0x1C58D8 · 0x1CA1D8 …) = ClimbGuide · CombatGuide.
  → **이름 규칙 폐기**: 바닥 = 수직 선분이 들어가는 평면 재질이 제외 마스크에 안 걸림. 바닥 속성도 그 재질 (Slide·Narrow 발판은 그대로 미끄럼·균형).
- 전 레벨 Animator CDV 발판 253 개 (윗면 법선 y > 0.7 인 hull 기준, `platforms_old_new.tsv`): 바뀐 것 144 — 늘어남 140 (전부 0 → 있음 121: PAND04F goelevator,
  PAND02C gocagelift2 · gocageelevator, 문·DMC 결계 윗면 …, 일부 → 전부: PAND04AA goelevatorlift 2/3 → 3/3, PAND02I goplat1–4 4/8 → 8/8, PAND02H 칼날 3/9 → 9/9,
  PAND00D 분쇄기 2/8 → 8/8 …), 줄어듦 4 (ATHN08B 세 벽 GeneralGuide 윗면, PAND04G goblocktranslatebg 의 NoPlayerCollision hull). ARENA27 goriseplat 은 전후 같음
  (groundR hull 이 받침, lambert hull 8 개는 NoPlayerCollision). 불 함정 DamageMat 은 NoPushPullCollision 만이라 영웅에게는 단단한 면.
- 벽 (구 밀어내기): hull 의 모든 평면이 제외 마스크면 밀지 않는다. 단 GeneralGuide 만은 남긴다 [근사: 0x1E2100 도 제외하지만 ATHN01B goplayerblock "guideMat",
  ATHN01E 꼬리 막이 등 플레이어를 막는 이름이 GeneralGuide — 따로 막는 질의가 있을 수 있음, 미확인]. 충돌 끔 (Vis Attr11_0 = 2) 인 GO 는 바닥도 아니다.
- **ATHN02A ← ATHN01F**: Warp_Jump 0x1B2E08 은 Start 월드 행렬 (0x1A9CD0 = 엔티티 × goshiptrans 관절 1) 로 SetMatrix 만 한다 — 배에 부모로 붙이지 않는다.
  goshiptrans 애니 (10.4 초) 는 관절 이동이 없고 CDV 는 생성 때 끔 (CDsOff) → 10 초 뒤 CDsOn. 도착점 바로 아래 (−623.8, 도착점 −624.1) 는
  **정박한 배 goshipgroup** (Animator 없는 CDV GO, 사다리·아테나 석상 Mime) 의 갑판이다. 엔진은 Animator·R1 오브젝트의 CDV 만 충돌에 넣어 −800 수로 바닥으로 떨어졌다.
  원작은 GO 를 만들 때 CDV 리소스마다 BallHull 인스턴스를 만들어 충돌 세계 0x29C978 에 올린다 (리소스 vtable 0x2BF75C → 0x11D498 → 0x138E70 → 0x138FD0) [추정: 경로].
  → `Platforms.of` 가 **비헤이비어 없는 CDV GO 151 개** (전 레벨, `static_cdv_bodies.txt`: 정박한 배, 보이지 않는 벽·되돌아감 막이, HDES01C 죽음 굴림대 …) 도 정지 몸체로 넣는다
  (시작 act 가 반복이면 렌더의 반복 재생 배치처럼 돌린다, 도착 자세 맞추기 대상 아님). 비헤이비어 GO (깨지는 물건·상자·밀기·크랭크) 는 각 시스템이 맡는다.
- 0.5 초 PortCam: Start 대상 0x1A9C18 이 발동하는 `ShipTrans-SetCamPortCam` (Delay 0.5) — 데이터대로 원작 동작. 영웅이 갑판에 서면서 PortCam 이 크레토스·석상·부두를 비춘다 (`a02a_a90.png`).
- 순회 검증: 강제 뒤에만 나온 전환 0 · 숨긴 채 쓴 상호작용 0 그대로, 보고서는 HEAD 와 같음 (개선·회귀 없음 — `--tests` 단독 실행 비교.
  전체 묶음 실행에서는 ATHN02D 헤드리스 메두사 단계 기록이 테스트 순서에 따라 바뀐다: HEAD 도 같음, 이번 변경과 무관).
- 시험: `WarpArrivalTest.pand04aaArrivalRidesElevator` (−239 가 아니라 엘리베이터, AnimLiftUp 끝 y ≈ 45), `cdvMaterialEntriesAreSurfaceRecords`,
  `athn02aArrivalStandsOnDockedShipDeck`. 엔진 테스트 397.
