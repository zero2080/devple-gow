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
| 의심 (미해결, §4) | 4 | ATHN05AA, OLYMP02, PAND03A, ATHN01A |

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
- **OLYMP02** 바닥: 반투명 유리 바닥 아래가 이제 안개색(0.5, 0.6, 1.0, Fog_A 1 → 배경 전부 안개)으로 밝게 보인다. 원작은 SCR_Mirror 반사가 그 자리를 채운다 (미구현, 31 §7).
- **PAND03A** 붉은 쐐기: `godmcdog2` (DMC 문 결계, `MAT_fireMat` 가산, 관절 22, ANM_dmcdog2) 가 Vis 끔 없이 보이는데 바인드 자세로 그려져 크게 펼쳐진다.
  이전에는 패스 순서 버그로 뒤 벽에 덮여 거의 안 보였다. ESC 가 Animator 로 루프 애니를 거는 경로 미해석.
- **ATHN01A** 오른쪽 흰 물보라: `--no-fx`/`GOW_FX=off` 면 사라지는 파티클 (폭포 `gowaterfall03` 옆, `govolumefoge` 볼륨 이미터 배율 10.77 또는 `gosplash2` 표면 이미터).
  FXC 5–9 볼륨 상자 배율·표면 이미터(FXC 3) 해석이 [추정] 이라 과밝음 여부 판단 보류.

## 5. 테스트
`SkyBackgroundTest` (3): SCR_Sky 하늘 배치 (ATHN05A skyLR, ATHN02B 2개, PAND04E 2개, PAND01BB group1224, ATHN01A, ATHN03A group2),
배경 안개 계수 (DEST01 1, ATHN07 100/128, ATHN01A 층 95/128, PAND01A −1), ATHN01D 판자 ALPHA · 구름 CUTOUT · 구름 인스턴스 y −0.4.
