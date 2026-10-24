# 36. 검증: 전 레벨 순회 (21/36 단계)

근거 문서: 11 §6.2–6.4 (GameMgr 메시지, 레벨 그래프), 12 §2.2 (BallHull), 13 (엔티티 런타임), 27/28 (GameScene, ScriptWorld), 26/32 (EnemyFactory).
신뢰도 표기: **[확실]** 디스어셈블/데이터 대조, **[추정]** 맥락 판단, **[근사]** 원작과 다른 대체 구현.

## 0. 요약
- 헤드리스 하네스 `verify/TraversalVerifier` 가 레벨 WAD 105 개 전부를 GL 없이 돌린다: LevelData + 충돌 → 시작 지점 바닥 → ScriptWorld 로 모든 스크립트 볼륨(영역 단위)을 걸어 다님 → 게임 이벤트 강제 → 전환·스폰·동영상·메시지·세이브 포인트·오류·누락 리소스 기록 → 전환 그래프.
- 결과 (`work/verify/report.md`): **ATHN01A → OLYMP02 도달 (52 전환, 38 문서 §10 이후 게임 이벤트 강제 없이)**, 보너스 ARENA10 → ARENA27 도달, 스크립트 예외 0, 스택 불균형 0, 동영상·메시지·전환 대상·적 템플릿 미해결 0, 누락 MDL/ANM/MAT/TXR 0.
- 원작의 레벨 이동은 **레벨 슬롯 2개가 동시에 상주**하는 구조다 (같은 월드 좌표의 이웃 레벨 + Warp 짝 레벨). 이걸 모르고 만든 기존 GameScene 은 LoadCheck 를 무시해서 **ATHN01A 에서 한 발짝도 다음 레벨로 갈 수 없었다** — 이번 단계의 가장 큰 수정.
- 고친 버그 10 개 (§5): LoadCheck 전환 없음, Warp 도착점, 동영상 끝 이벤트 번호(7 → 501), 볼륨 영역(region) 무시, 평면 1개 hull = 구, Goto 미처리, Creator 시작 생성, 레벨 자체 적 템플릿 모델, 사운드 로더 템플릿을 해골로 스폰, `start*` 없는 레벨 시작점.

## 1. 실행
```bash
./gradlew :engine:test --tests '*TraversalTest*' < /dev/null   # 순회 + 보고서 (work/verify/report.md, gow-rom 옆 work/verify 가 있으면)
```
`TraversalVerifier.main(<gow-rom>, <report.md>)` 로도 돌릴 수 있다. 전 레벨 수 초 (엔진 테스트 전체 ~10 초).

| 코드 | 내용 |
|---|---|
| `engine/verify/TraversalVerifier` | 하네스 (레벨별 실행, 전환 결정, 도착 검사, 그래프) |
| `engine/verify/TraversalReport` | Markdown 보고서 (요약, 주 경로 상세, 레벨별 표, 상세) |
| `engine/game/LevelLink` | 레벨 슬롯 2개 모델 — GameScene 과 공용 (§2) |
| `engine/game/LevelEntry` | 시작/진입점/Warp 도착 지점 결정 + 바닥 보정 — GameScene 과 공용 |
| 테스트 | `TraversalTest` 9, `LevelLinkTest` 4, `ScriptWorldTest` +4, `EnemyFactoryTest` +1 |

## 2. 원작 레벨 이동 (GameMgr 메시지 2..7) [확실: 코드]
| 메시지 | 함수 | 동작 |
|---|---|---|
| 3 LoadWad(w) | `0x1B0404` | **호출한 엔티티의 레벨**(`0x1A9EC0(0x29D928)`)이 아닌 슬롯에 w 로드. w = 자기 레벨이면 무시. Warp 준비 플래그 0x29D914 = 0 |
| 4 LoadForWarp(w) | `0x1B0584` | 같은 슬롯에 로드 + Warp 준비 (0x29D914 = 1) |
| 5 Warp() | `0x1B0688` → `0x1B2CE0` | 준비됐으면 다른 슬롯이 현재가 되고 `0x1AE710` 이 도착 Start 를 고름 → `Warp_Jump` |
| 6 LoadCheck(w) | `0x1BCA10` | w 가 로드된 슬롯이면 현재 레벨 이름(0x29DAE8) = w. **위치는 바꾸지 않는다** |
| 7 Goto(name) | `0x1B07C4` | 같은 이름 Start/Marker 로 `Warp_Jump` |

Warp 는 대상 이름이 없어 그때 슬롯에 있는 레벨로 간다. 검증기의 Warp 전환 위치 (`TraversalVerifier.warpFor`): 각 Warp 센서를 가장 가까운 LoadForWarp 의 레벨에 짝짓고 그 레벨의 것, 없으면 대상 LoadForWarp 에 가장 가까운 Warp. 전에는 첫 Warp 센서 하나를 모든 대상에 써서 되돌아가는 Warp 가 출구로 적혔다 — ATRET01A → ATRET01B 가 `loadHDES01G` (HDES01E 를 싣는 자리) 로, ATHN07 → SEWR01, PAND04H → PAND04IB, ATRET01B/01C, OLYMP01 도 (자동 플레이 43 문서).

- **Warp 도착 (`0x1AE710`)**: Start 엔티티 목록(0x303D80) 중 새 레벨의 것에서, 모드 2 이고 슬롯 0 문자열 = 떠나온 레벨 이름인 것, 없으면 첫 모드 1. 데이터: `WarpFrom6A`(슬롯 0 "Athn06A"), `WarpFrmAthn08A`("athn08a") … 대소문자가 섞여 있어 비교는 대소문자 무시로 본다 [추정: 0x28DF50].
- **Start 모드**: 1 기본 시작 (플레이어 생성), 2 Warp 도착 (슬롯 0 = 떠나온 레벨, 슬롯 2 = 도착 카메라), 3 Goto 지점. ATHN01E 처럼 첫 Start 가 Goto 지점(InMouth)인 레벨이 있다.
- **이웃 레벨은 월드 좌표를 공유**한다 (ATHN01B 의 LoadCheck01A 볼륨이 ATHN01A 갑판 바로 아래). 두 레벨의 충돌·스크립트가 동시에 살아 있어서 SEWR01 → SEWR02 처럼 **LoadCheck 없이 걸어 들어가는 연결**, ATHN04C 처럼 **자기 레벨을 LoadCheck** 하는 볼륨도 있다. Warp 짝(ATHN06A ↔ 06B, 아레나들)은 좌표계가 달라 LoadCheck 지점 아래에 상대 레벨 바닥이 없다 → LoadCheck 는 "현재 레벨 표시" 일 뿐.

### 재구현 (레벨 하나만 올림)
`LevelLink(current, other, exists)` 가 위 규칙을 그대로 따르고, GameScene 이 결과를 장면 전환으로 바꾼다:
| 사건 | GameScene |
|---|---|
| LoadWad/LoadForWarp | 다른 슬롯 레벨의 **충돌만** 즉시 로드 (`otherCol`, 렌더·스크립트 없음) [근사: 원작은 백그라운드 전체 로드] |
| (게임 GameScene) | LoadWad = 다른 슬롯 레벨 켬 (충돌·이웃 스크립트·CMV·렌더), **LoadForWarp 로 새로 올린 레벨은 꺼진 채** (슬롯 비움, Warp 에서 켬 — 원작 0x1B2AA8 인자 1 은 0x1BC2E0 을 안 부름, 30 §7.4) |
| 바닥 질의 | 현재 + 다른 슬롯 충돌 중 높은 바닥 (경계에서 끊기지 않음) |
| LoadCheck(다른 슬롯) | 발밑에 그 레벨 바닥이 있고 **현재 레벨 바닥은 없으면** **SEAMLESS**: 같은 씬에서 현재 ↔ 다른 슬롯 역할만 바꾼다 (`GameScene.seamSwap`, 43 §27 — 페이드·다시 읽기 없음, 영웅·카메라·HUD·이펙트·음악 그대로; 보스·덧붙는 레벨이 끼면 예전처럼 같은 위치·방향으로 새 GameScene + 0.4 초 페이드). 아니면 무시 (Warp 짝 · 두 레벨이 같은 바닥을 겹쳐 가진 곳 — ATHN02C 발리스타 방 LoadCheckB2, 43 §14.4) |
| 다른 슬롯 바닥만 0.25 초 밟음 | **WALK**: SEAMLESS 와 같은 처리 (같은 씬에서 역할 바꾸기) |
| 덧붙는 레벨 (자기 바닥 없음, 숙주가 다른 슬롯) 의 ENV 볼륨 밖 + 숙주 바닥 0.1 초 | **WALK** → 숙주 (ATHN02D 메두사 방 남쪽 출구 → ATHN02C, 43 §14.4) [근사: 엔진은 현재 레벨의 R2 오브젝트·Creator 만 다룬다] — 보스 레벨이라 새 GameScene |
| 이웃 (다른 슬롯) 스크립트 SpawnEnemy | 그 적도 나온다 (이미 올린 적 종류의 템플릿만, 피해·제거 이벤트는 이웃 세계로) — 원작은 두 슬롯 스크립트가 같은 세계 (43 §14.5). 보스 레벨이 현재면 생략 |
| Warp (LoadForWarp 뒤) | **WARP**: 새 GameScene, `LevelEntry.resolve(…, warpFrom)` = 도착 Start + 도착 카메라 |
| Goto | 같은 레벨 엔티티 위치·방향으로 이동 (+ Start 의 카메라) |
| 도착 후 | 떠나온 레벨이 다른 슬롯에 남는다 (되돌아가는 LoadCheck 가 동작). 같은 씬 이음은 두 세계가 그대로 돌아 볼륨 안팎 기록이 이어지고, 새 씬 (Warp · `--arrive` · 보스 이음) 은 `ScriptWorld.placePlayer(…, seamless)` 가 서 있는 볼륨을 "이미 안" 으로 표시해 되돌아가는 LoadCheck 가 바로 발동하지 않는다 |

## 3. 하네스 방법
1. **시작 지점**: `LevelEntry.resolve` — 첫 모드 1 Start → `start*` GO → 원점. 바닥 = `groundHeight(y+32, 600)`. 없으면 수평 256 안 가장 가까운 바닥으로 보정 (움직이는 발판 위 Start, §6).
2. **보행**: 엔티티가 붙은 (볼륨, 영역) 마다 내부 점 하나 — hull/ball 중심 → 경계구 격자 순으로 찾고, 볼륨 안 바닥 위 4 유닛(걷는 높이)을 우선. 시작점에서 가까운 순서로 1 초씩 머문다. 스폰된 적은 0.5 초 뒤 제거(= 플레이어가 이김), 동영상은 다음 틱에 끝 이벤트 501. Goto 는 플레이어를 옮긴다.
3. **게임 이벤트 강제** (2 회): 스크립트가 보내지 않는 EventId 의 EventSensor 에 Condition 핸들러의 상수 조합(최대 8×8)으로 이벤트를 보낸다 (QTE·문 차기 1023, 보스 사망, 붙잡기 1048 …). 진입 센서 Filter 가 영웅(1)이 아닌 볼륨에는 그 그룹의 오브젝트를 넣는다 (PAND05B 판도라 상자 511). 그 뒤 다시 보행, 마지막 30 초 대기. 이 단계 뒤에야 나온 전환은 `*`(강제) 로 표시.
4. **전환 결정** (보행 순서가 임의라 순서 무관 고정점):
   - SEAMLESS L→X: L 에 LoadCheck(X) + (L 이 X 를 로드했거나 X→L 전환 존재) + LoadCheck 지점 수평 256·수직 ±256 안에 X 바닥.
   - WALK L→X: L 이 X 를 LoadWad 했고 (LoadForWarp 짝 제외) 두 레벨 바닥 칸(48 유닛)이 맞닿음, 또는 X 의 Goto 볼륨이 L 바닥 위에 있음.
   - WARP L→X: L 에 Warp, X ∈ L 의 LoadForWarp 대상 [근사: 여러 대상이면 모두].
5. **도착 검사**: SEAMLESS = 지점에서 대상 바닥까지 512 이내 (떠나온 레벨 충돌이 남아 걸어 넘어감), WARP = 도착 Start(모드 2 일치 여부) 아래 바닥 또는 보정 가능.
6. **기타**: 적 템플릿 해석(§4), 동영상 = `gow-rom/NAME.PSS|PSW`, 메시지 = R_PERMK 메시지 표, 세이브 포인트 = Creator `savepoint` 발동/전체, 배치 인스턴스 MDL/ANM 참조·모델 머티리얼·머티리얼 텍스처 존재, RSRCS WAD 존재.

## 4. 적 템플릿 (SpawnEnemy TemplateId = AI1 번호)
| 종류 | 예 | 처리 (GameScene = 하네스) |
|---|---|---|
| RSRCS 적 WAD 의 AI1 | ATHN01A 20 → R_SKS | 그 종류 |
| 레벨 WAD 자체 AI1, **Instance Name = 레벨 오브젝트** | ATHN01D 301 "AOXF1" → 오브젝트 `aoxf1` (MDL_aoXF1, ANM_aoxf1, CDV_aoXF1), ATHN06C 310 "AOXOR"(오라클), ATHN08B 660 "family" | `EnemyFactory.load(레벨 WAD, permk, ai1, 오브젝트)` [확실: 데이터 23 종 전부 일치] |
| RSRCS 의 비전투 WAD 에만 있는 AI1 | ATHN01B 256 "FakeBHHsnd" (R_AHH), ATHN08A 602 "FkSnd Ari A1A" | **스폰하지 않음** (사운드 뱅크만 로드). 이전엔 레벨 첫 적 종류로 해골 병사가 나왔다 |
| 오브젝트 없는 레벨 템플릿 (보스) | 히드라 250..253/258, 메두사 여왕 62, 아레스 600/601, 악한 크레토스 666/667 | 첫 적 종류를 대역으로 [근사] — 사망 → DestructionSensor 로 스크립트 진행. ATHN08A/B/C 는 적 종류가 없어 대역도 없음 (§6) |

## 5. 고친 버그
| # | 증상 | 원인 / 수정 | 위치 |
|---|---|---|---|
| 1 | 어느 레벨에서도 다음 레벨로 못 감 (ATHN01A → 01B 포함) | GameScene 이 LoadCheck 를 무시, LoadWad/Warp 만 처리. → `LevelLink` + 다른 슬롯 충돌 + SEAMLESS/WALK/WARP 전환 (§2) | `GameScene`, `LevelLink` |
| 2 | Warp 도착이 항상 기본 시작점 (`start*` GO) | 원작 0x1AE710: 떠나온 레벨 이름의 모드 2 Start. → `ScriptWorld.warpTarget`, `LevelEntry` | `ScriptWorld`, `LevelEntry` |
| 3 | 동영상 끝에 진행하는 스크립트(ATHN01D 쌍발차기 문 → Semo02 → Warp, ATHN08B/C) 정지 | 끝 이벤트를 7 로 보냄. 데이터는 **501 + 인자 0 = 동영상 이름** (`TwinKickDoor-main-movieDone`: EventArg(0) == "Semo02") [확실: 데이터] | `GameScene.playMovie`, `ScriptWorld.EVENT_MOVIE_DONE` |
| 4 | PAND00B 시작/도착 즉시 PAND00A 로 되돌아감 등 | 한 ENV 볼륨 안의 ball/hull 마다 **영역 번호**(ball off[2], hull off[7] u8)가 있고 엔티티 영역 ID(원본 0x4A)와 짝이다 — 무시하고 볼륨 전체로 판정했다. 전 레벨 1617 쌍 중 1610 일치, 99 볼륨이 다중 영역 (Warpto00A: LoadVol 0 / WarpVol 1). → 영역별 진입/이탈. 영역이 없는 7 쌍은 볼륨 전체로 [근사] | `BallHull.regionsAt`, `ScriptWorld.contacts`, `regionOf` |
| 5 | 일부 ENV 볼륨이 공간 절반을 덮음 | 평면 수 1 인 hull 은 **구 (x, y, z, r)** (값 = hull 중심, 전 데이터 37 개 일치). 평면으로 읽어 반공간이 됐다. CMV 판정(CameraRig)도 같이 | `BallHull.insideHull`, `CameraRig.inside` |
| 6 | ATHN02A `ENV_LoadCheckB` 등 얇은 볼륨 판정 누락 (하네스) | 격자 탐색 간격 > hull 두께 → hull/ball 중심을 먼저 후보로 | `TraversalVerifier.interior` |
| 7 | Goto 가 영웅을 옮기지 않음 (ATHN01E 히드라 입속/갑판 등 26 곳) | GameScene 이 GOTO 요청 무시 → 엔티티 위치·방향 + 카메라 | `GameScene` |
| 8 | 세이브 포인트·상자 대부분이 생기지 않음 (발동 3/42) | Creator 슬롯 8 = **시작 시 생성** [강한 추정: 상자 335·세이브 42 개 대부분 이 값만 true, 다른 발동 경로 없음]. → 시작 큐에서 발동 (41/42) | `ScriptWorld.startMessage`, `EntitySlots` "CreateAtStart" |
| 9 | 레벨 자체 템플릿(AOXF1 …)이 첫 적 종류로, 사운드 로더 템플릿이 해골로 스폰 | §4 | `GameScene.spawn`, `EnemyFactory.levelTemplateObject` / `load(…, skeleton)` |
| 10 | PAND00B/PAND01A/PAND04F (`start*` GO 없음) 원점 시작, ATHN02D/ATHN08B 시작점 아래 바닥 없음 | 기본 시작 = 첫 모드 1 Start 엔티티, 바닥 없으면 256 안 바닥으로 보정 [근사] | `LevelEntry` |

부수: RSRCS 이름 파싱을 `EnemyFactory.resourceWads` 로 공용화, `Tweaks.nodes(prefix)`.

## 6. 결과와 남은 문제
`work/verify/report.md` 요약 (레벨 105, 전환 183 = SEAMLESS 102 / WALK 18 / WARP 63, 강제 뒤 20):
- **본편**: ATHN01A → … → OLYMP02 52 전환 (최단 경로; 판도라 신전 고리 PAND01–03 은 우회 — GlobalData 진행 조건은 레벨마다 새 호스트라 보지 않는다). 걷기만으로는 ATHN01D 까지 (ATHN01D → 01F 는 쌍발차기 문 이벤트 1023).
- 경로 중 게임 이벤트가 필요한 전환 8: ATHN01D→01F (1023 문 차기 과제), DEST03→PAND00A, PAND00C→04AA, PAND04AA→04C, PAND05B→HDES01A (상자 그룹 511), ATHN08A→08B / 08B→08C / 08C→07B (아레스 전투·동영상). 해당 게임 쪽 기능(과제/QTE 이벤트, 밀기 상자, 보스)이 있어야 실제 플레이로 통과한다.
- **보너스**: ARENA10 → 08 → 06 → 09 → 12 → 14 → 26 → 20 → 19 → 27 (11 문서 순서 그대로).
- **도달 불가 16**: 아레나 10 (메뉴에서 시작), OLYMP04 (본편 연결 없음), 보조 WAD 4 — ATHN01EE·PAND01CC·PAND03GG (Start 원점, 엔티티 1–3, 사운드 로더), ATHN05AA — 그리고 **ATHN01E (히드라)**: ATHN01D 의 LoadCheck(01E) 지점에서 01E 바닥까지 ~1000, 바닥이 맞닿지 않음 → 돛대/밧줄/기어오르기 이동(미구현)으로 넘어가는 연결로 보인다.
- **도착 검사 실패 2**: ATHN04A→05D Warp 도착 `WarpFrom4A` (−944, 677, 1098) 아래·주변 256 에 바닥 없음, PAND02F→02H LoadCheck 지점 근처 02H 바닥 없음 — 움직이는 발판/엘리베이터(정적 충돌 아님)로 추정.
- 시작 지점 보정 2 (ATHN02D 192, ATHN08B 224), 보정 불가 4 (보조 WAD 3 + ATHN05AA).
- 데이터 오류: ATHN03D `LoadCheck("Athn0E")` (없는 WAD, 무시).
- 보스 대역: ATHN08A/B/C (아레스·악한 크레토스)는 레벨에 적 종류가 없어 대역도 없다 → 전투로 진행하는 스크립트는 아직 못 넘는다.

### 한계 (하네스)
- 보행은 경로 탐색 없이 순간 이동이다 (볼륨 사이 지형 연결·점프·기어오르기를 보지 않음). 바닥 이음은 칸(48) 맞닿음으로만 판정.
- GlobalData 는 레벨마다 새로 시작 (레벨 간 진행 조건 미반영). 이벤트 강제는 조건 상수 조합의 전수 대입이라 실패 분기도 같이 발동한다.
- 다른 슬롯 레벨의 스크립트는 돌리지 않는다 (원작은 두 레벨 엔티티가 같이 동작). GameScene 도 마찬가지 [근사] — 이웃 레벨 볼륨이 현재 레벨 땅 위에 있는 연결(Goto 등)은 걸어 들어가야 발동한다.

### 38 단계 후 (38 문서 §6)
- **ATHN01D → ATHN01F**: R1 쌍발차기 문 (IO_CSM 과제 206, 1023 ≥ 30 %) — 강제 아님.
- **PAND05B → HDES01A**: 판도라 상자 밀기 (Marker 511 = 오브젝트 그룹) — 강제 아님.
- **ATHN01E**: 돛대 꼭대기 짚라인 `gorope3` 끝점 = ATHN01E 갑판 → WALK. "바닥이 맞닿지 않음" 의 정체는 짚라인.
- ATHN04A → ATHN05D 도착: 엘리베이터 발판(CDV + 오브젝트 애니) 위. 남은 도착 실패는 PAND02F → PAND02H 하나.
- 이웃 레벨 스크립트: GameScene 이 같이 돌린다 (하네스는 아직 레벨 단독).
- 주 경로 강제 전환 8 → 6. NoPlayerCollision 바닥은 바닥 칸에서 제외 (HDES01B → HDES01C 가 AI 사망면 대신 실제 바닥 이음 WALK 로).

### 38 문서 §10 후 (남은 6 곳 + 도착 실패 1)
- **주 경로 강제 0**: `reach(ATHN01A, 강제 없이)` 에 OLYMP02. 각 전환을 연 원작 동작:
  | 전환 | 계기 |
  |---|---|
  | DEST03 → PAND00A | d01Mp01 끝 501 (보낸 쪽 = 플레이어 — 하네스가 null 로 보내던 버그) → 세이렌 3 × MaximumCount 3 = 9 → d01Mp03 → 두 번째 뿔피리 CSM 1031 → Warp [확실: 데이터] |
  | PAND00C → PAND04AA | 엘리베이터 레버 = PushPull 이 보내는 **1023 (100 %, id 111)** (원작 0x2118E8 = CSM 0x215928 과 같은 코드) |
  | PAND04AA → PAND04C | Semo06 끝 501 (Filter 1) → LoadWad → LoadCheckC |
  | ATHN08A → ATHN08B | 헤드리스 아레스 1단계: 608 끝 1047 → a07Mp01 → Warp |
  | ATHN08B → ATHN08C | 분신 4 파도 — SpawnEnemy **MaximumCount** (슬롯 0, 원작 0x1AC580) 로 25 처치 → A07MP05 → Warp |
  | ATHN08C → ATHN07B | 헤드리스 아레스 3단계: 395 → 1035 → Semo10 → Warp |
- **도착 검사 실패 0**: PAND02F → PAND02H 는 발판이 아니라 PAND02F 바닥 구멍(LoadCheckH 볼륨 안) 으로 PAND02H 구덩이 바닥(−1904)에 **낙하** (≈ 380).
- 남은 강제 뒤 전환 12 (주 경로 밖): 보너스 아레나 9 (도전 과제 완료 이벤트), ATHN01E → ATHN01D, PAND01C → PAND01B, PAND02AA → PAND02A.
- 한계 추가: 하네스는 Vis(보이기/충돌)로 상호작용을 막지 않는다 — PAND00C 엘리베이터 레버는 원작에서 링 퍼즐(PAND00C/00D 두 레벨 이벤트 204/205)을 풀어야 보인다. 레벨 간 이벤트 공유는 GameScene 에만 (38 §10.3).

## 7. 이번에 이름 붙인 주소
| 주소 | 이름 | 신뢰도 |
|---|---|---|
| 0x1BCA10 | Level_LoadCheck (슬롯 이름 비교 → 현재 레벨 0x29DAE8) | 확실 |
| 0x1B2CD0 / 0x1B2CE0 | Warp_Request / Warp_Update (슬롯 교체 + 도착 Start) | 확실 |
| 0x1AE710 | Warp_FindArrival (Start 모드 2 슬롯 0 = 떠나온 레벨, 기본 모드 1) | 확실 |
| 0x1A9EC0 | Entity_Level (엔티티가 속한 레벨) | 추정 |
| 0x29D910 / 0x29D914 / 0x29D918 | Warp 대상 슬롯 / Warp 준비 / Warp 요청 | 확실 |
| 0x336B00 | 레벨 슬롯 2개 (+0x68 플래그: 1 로드됨, 4 로드 중) | 추정 |

## 7. 메시지 끝 이벤트 1040 / 신들의 도전 아레나
- 원작은 HUD 메시지가 닫힐 때 플레이어가 이벤트 **1040(메시지 번호)** 를 보낸다 (0x1B8ECC / 0x1B8F98 / 0x1B9170 → sub_001A8C78). 아레나는
  목표 달성 시 메시지 2008 을 띄우고 `WarpEntities-EvtMsgDone`(1040, arg0 == 2008) 이 페이드 → Warp. `GameScene` 과 하네스 모두 표시 시간이 지나면 1040 을 보낸다.
- 하네스: 처치할 적을, 그 그룹을 세는 **볼륨 안 파괴 센서**(DestructionSensor mode 4, 실패 구역 제외 — 이름/대상에 Fail) 안에서 처치한다
  (ARENA10 "단상 밖으로 떨어뜨리기" = FallenZone 8 마리 → 2008 → 1040 → ARENA08). 플레이어가 밀어 떨어뜨리는 것을 대신 [하네스 근사].
- 결과: 아레나 전환 10 개 모두 강제 없이 이어진다. ARENA06 "부서지는 물체(그룹 101) 전부 파괴" 는 하네스가 Breakable 을 모두 파괴해 재현 (41 문서).
  1050 은 제한 시간 초과(실패)라 필요 없음.
