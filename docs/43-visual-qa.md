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

## 3. 레벨별 상태 (수정 후, 240 프레임)
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
