# 13. 레벨 스크립트 엔티티 런타임 (SCR_Entities)

명세 대상: ESC 리소스(07 문서 §5)의 엔티티가 **언제, 무엇을 기준으로** 핸들러를 실행하는지.
근거는 실행 파일 `SCKA_300.02` 정적 분석(주소 표기), 데이터 대조는 전 레벨 ESC(`script:entities`, `script:survey`).
구현은 [28-impl-script-world](28-impl-script-world.md).

신뢰도: **[확실]** 코드 흐름을 직접 확인, **[추정]** 이름/의미를 데이터로 추론, **[미확인]** 근거 부족.

---

## 1. 생성과 객체 [확실]

| 주소 | 내용 |
|------|------|
| `0x1A7388` | SCR_Entities 생성 (`"EntityCount"`). 엔티티를 순서대로 읽어 타입별 팩토리 `0x336AC0[type]` 호출 |
| `0x1A4880` | 팩토리 16개 등록 (타입 0..15 = `0x245280` .. `0x2455D8`, 크기 0xB0..0x140) |
| `0x1A9558` | 공통 생성자: 슬롯 7개(+0x6C..+0xA3) 를 (-1, 0) 으로, +0x68 = -FLT_MAX |
| `0x1A9CD0` | 엔티티 월드 행렬 = 엔티티 행렬(원본 0x00) · **소유 GO 관절(원본 0x40) 월드**, 정규직교화 |

원본 필드 → 객체: 0x48 id → +0x42, 0x4A → +0x44 **볼륨 영역 ID**, 0x4C → +0x46 **대상 모드**(Data 타입은 변수 수),
0x50/0x54.. 대상 수/목록 → +0x4A/+0x4C, 이름 → +0x50, 소유 GO → +0x54, 코드 → +0x58.

- **소유 GO**: ESC 리소스를 참조(refs)하는 오브젝트(0x40001)의 인스턴스(`go*`). ESC 이름 = Maya 그룹 이름 = 엔티티 이름의 첫 `-` 앞
  (`ESC_DeathZone1` ↔ `DeathZone1-pCube2-EntrySensor1` ↔ `godeathzone1` ↔ `ENV_DeathZone1`). 두 번째 토막(`pCube2`, `nurbsSphere1`)은
  볼륨을 만든 Maya 노드 이름일 뿐 런타임에 쓰이지 않는다. 전 레벨에서 ESC 를 가진 오브젝트는 모두 정확히 한 번 배치되고,
  진입/이탈 센서의 소유자는 ENV 볼륨을 0..1 개 가진다 (예외 1: ATHN02B `Elev2BDown`, ENV 없음).
- **영역 ID** (원본 0x4A): BallHull 의 hull 별 영역 번호(off[7] u8 표, ball 은 off[2] — 36 문서 §5 에서 off[6] 을 정정)와 같다 — `ENV_DeathZone1` 0x0A = 엔티티 base 10,
  `ENV_HatchZoneGroup` 0x35 = 53. 진입 콜백은 (소유 GO, 영역 ID) 로 엔티티를 찾는다(`0x1A88F0`). [확실: 값 일치 / 추정: 표 의미]
- Marker(14) 가 핸들러를 정확히 1개 가지면 생성 시 그 값을 ID 로 웨이포인트 오브젝트를 만든다 (`0x1A74EC`, `0x1A5E18`).

### 1.1 가상 함수 표 (타입별 vtable `0x2BA3C8`..`0x2BA788`)
| vt | 의미 |
|----|------|
| +0x08 | 공통 Condition 평가 (`0x1AA1C0`) |
| +0x18 | 슬롯 바인딩 (§2) |
| +0x20 | **메시지 처리** `(sender, msg)` (§3) |
| +0x28 | **발동 효과** (센서: 대상 발동, 이펙터: 대상 선택 `0x1A9958`) |
| +0x30 | 오브젝트 하나에 효과 적용 (이펙터) |

## 2. 슬롯 = 핸들러 ID [확실]

타입별 바인딩 함수(vt+0x18)가 `Slot_Bind`(`0x1A9FD0`)를 차례로 호출하고, 전역 카운터 `0x3067CC`(바인딩 시작 `0x1A9FA8` 에서 0)가 슬롯 번호가 된다.
**핸들러 ID = 슬롯 번호.** 슬롯은 (코드 오프셋, 값) 8바이트: 핸들러가 `push 상수; return; exit` 이면 상수를 슬롯 타입으로 변환해 접고(`0x1A8A30`),
아니면 필요할 때마다 VM(`0x16F278`)으로 평가한다. 없는 슬롯은 기본값.

### 2.1 공통 블록 (센서·이펙터 7 슬롯, 객체 +0x6C..+0x9C)
| 슬롯 | 이름 | 타입 | 기본 | 의미 / 근거 |
|------|------|------|------|-------------|
| B+0 | Condition | bool | true | 거짓이면 Trigger 무시 (`0x1A96C0`) |
| B+1 | RefractTime | float | 0 | 재발동 대기(초). **음수 = 한 번만** (`0x1AA210`: <0 → FLT_MAX) |
| B+2 | Delay | float | 0 | 발동까지 지연(초) (`0x1AA280`) — 시간순 큐 `0x303DB0` |
| B+3 | DelayedEnable | bool | true | 지연 후 다시 검사, 거짓이면 취소 (`"Delayed enable false"`, `0x1A98C0`) |
| B+4 | Action | action | – | 발동 시 실행 (`0x1AA320`) |
| B+5 | DebugMsg | string | "" | 발동 시 로그 (`0x1AA370`) |
| B+6 | Filter | int | 0 | **오브젝트 그룹 필터** (오브젝트 +0x2A 와 비교, 0 = 모두) (`0x1AA3C0`) |

### 2.2 타입별 슬롯 표 (B = 공통 블록 시작)
| 타입 | 고유 슬롯 (번호: 이름 타입) | B | 비고 |
|------|----------------------------|---|------|
| 0 EntrySensor / 1 ExitSensor / 2 CreationSensor / 3 DestructionSensor | – | 0 | `0x1A24B8` / `0x1A2640` / `0x1A27C8` / `0x1A2950` |
| 4 EventSensor | 0 EventId int, 1 ? int, 2 PromptOnEnter int | 3 | `0x1A2AD8` |
| 5 Animator | 0 AnimName str (`!n` act 번호, `$` 재질 애니), 1 모드 비트 int, 2 속도 float (기본 1.0), 3 시작 시간 float — 42 문서 §3 | 4 | `0x1A2CE0` |
| 6 (Animator 변형, 4개뿐) | 0 str, 1 int, 2..5 float, 6 int | 7 | `0x1A2F20` |
| 7 Vis | 0 Visible, 1..7 속성(int, 0 그대로 / 1 켬 / 2 끔), 8 FadeTime float | 9 | +16 Attr17, +17 JointVisibility (`0x1A3200`, 효과 `0x1ABAA8`) |
| 8 EventTransmitter | 0 EventId, 1 Arg1, 2 Arg2 (int) | 3 | `0x1A3578` |
| 9 Start | 0 PlayerName str ("Hero"), 1 Filter int(+0x9C), 2 CameraName str | – | `0x1A3780` |
| 10 SpawnEnemy | 0 MaximumCount int, 1 ? int(-1), 2 SpawnAtStart bool, 3 Respawn bool, 4 TemplateId int, 5..6 int, 7 SpawnAction int(-1, 생성 액션 = CombatFile 액션 ID, 32 문서), 8 InitState int (5 = 석화), 9 Patrol str ("patrolN") | 10 | `0x1A3840`, 효과 `0x1AC850` |
| 11 Creator | 0 Name0 str ("chest", "savepoint", "aoRED" …), 1 Name1 str, 2 float, 3..5 int(-1), 6,7 int, **8 CreateAtStart bool** (36 문서 §5), 9 bool | 10 | +17 bool (`0x1A3B90`) |
| 12 GlobalData / 13 LevelData | 0 Priority int, 1..16 Init action (변수 초기화) | – | `0x1A3F18` / `0x1A4240` |
| 14 Marker | 0 Id int (+0x9C) | – | `0x1A4568` |
| 15 SoundController | 0 Op int(기본 1), 1..4 float, 5 int | 6 | `0x1A45D0` |

데이터 대조 (ATHN01A): `GrabMsgGroup-VisMsg` 9 조건 / 10 −1(한 번) / 11 `LevelData:1 MsgTime`(지연) / 12 조건 재검사 / 13 ShowMessage(269) — Vis B=9 와 일치.
`MusicGrpAthC1Start-SoundController1` 7 −1 / 8 13초 / 10 음악 전환 — SoundController B=6 과 일치. 전 레벨 핸들러 ID 가 표를 넘는 경우 0 (`script:survey`).

## 3. 메시지와 평가 시점 [확실: 흐름 / 추정: 메시지 이름]

엔티티 메시지(vt+0x20 의 두 번째 인자):

| msg | 보내는 곳 | 의미 | 받는 타입 |
|-----|-----------|------|----------|
| 0 | 생성 직후 (`0x1A7638`) | 등록 | EventSensor → 이벤트 목록 `0x303D98`, Creation/Destruction(모드 2..4) → 목록, Start → `0x303D80`, Data → 변수 등록(`0x1AF2C0`) + 시작 큐 |
| 1 | **시작 큐** `0x303DA0` (우선순위 오름차순, `0x1A95E8`) / 오브젝트 생성 통지 | 시작 / 생성 | GlobalData(우선순위 = 슬롯0), LevelData(1000 + 슬롯0): Init 슬롯 실행. CreationSensor(10000). SpawnEnemy·Creator·Start(0x7FFFFFFF) |
| 2 | 볼륨 진입 (`0x1A8410`) | Enter | EntrySensor → Trigger, EventSensor → 버튼 안내 표시(`0x21DA50(1,0xE)`) |
| 4 | 볼륨 이탈 (`0x1A7EF0`) | Exit | ExitSensor → Trigger, EventSensor → 안내 숨김 |
| 5 | 전역 이벤트 (`0x1AAB00`) | Event | EventSensor (EventId 일치 시 인자 3개 → +0x5C..) |
| 6 | 오브젝트 제거 | Destroyed | DestructionSensor, SpawnEnemy(Respawn) |
| 7 / 8 | 세이브 / 복원 | +0x68(마지막 발동 시각) 저장·복원 | 전 타입 |

**프레임 순서** (`0x1A7A78`, 시간 += 1/60): 시작 큐 메시지 1 → 오브젝트별 ENV 접촉 질의(콜백 `0x1A8410`, 이전 접촉과 비교해 이탈 메시지 4) →
전역 이벤트 큐 `0x303DB8` → 지연 발동 큐 `0x303DB0`.

### 3.1 볼륨 진입/이탈
충돌 질의 마스크 0xFF9CE00F 로 오브젝트가 겹친 ENV 영역(소유 GO, 영역 ID)을 얻고, 그 영역 엔티티 중 **Filter == 0 또는 오브젝트 그룹(+0x2A) == Filter** 인 것에 메시지 2/4.
그룹 번호: 영웅 = 1 (Start 엔티티 Filter, 데이터의 플레이어용 센서가 전부 1), 적 = 그 적을 만든 SpawnEnemy 의 Filter (ATHN01A TauntEnemy 8, ClimbSkellys 5/6).

### 3.2 모드 (원본 0x4C, +0x46) — 대상 선택
| 모드 | CreationSensor (msg 1) | DestructionSensor (msg 6) | EventSensor (msg 5) | 이펙터 효과 대상 (`0x1A9958`) |
|------|------------------------|---------------------------|--------------------|---------------------------|
| 1 | 소유 GO 가 있으면 (시작 시) | 소유 GO 자신 | Filter 만 검사 | 소유 GO |
| 2 | 생성된 것의 부모 == 소유 GO | 제거된 것의 부모 == 소유 GO | 보낸 쪽이 볼륨 안 | 보낸 쪽(sender), Filter |
| 3 | 아무 생성 | 아무 제거 | 보낸 쪽 부모 == 소유 GO | 모든 오브젝트 중 Filter 일치 |
| 4 | 생성 위치가 볼륨 안 | 볼륨 안에서 제거 | – | 볼륨 안 + Filter (`0x1A62B8`: 오브젝트 접촉 목록에 (소유 GO, 영역 ID)) |
| 5 | 무조건 (시작 시) | – | – | EventTransmitter: **전역 이벤트** 발행 (`0x1A8D88`) |

"부모" = 오브젝트 +0x2C → +0x54 (스폰한 엔티티의 소유 GO). [확실: 분기 / 추정: 부모 의미]

## 4. Trigger / Fire [확실]

```
Trigger(e, sender)  0x1A9750
  Condition 거짓 → 무시                                   (0x1A96C0)
  now < last + RefractTime (음수면 ∞) → "Refracting" 무시, 아니면 last = now
  delay = Delay   (SpawnAtStart: 0, Respawn: max(delay, 0.25))
  시간순 큐에 (e, sender, now + delay)                    (0x1A97E0)
Fire(e, sender)     0x1A98C0  (큐에서 꺼낼 때)
  DelayedEnable 거짓 → 취소
  Action 실행, DebugMsg 출력, vt+0x28:
    센서 0..4        대상 ID 목록의 엔티티 전부 Trigger(target, sender)   (0x1A9C18, 없는 ID 는 경고)
    Animator / Vis   모드별 대상 오브젝트에 애니 / 속성 (0x1AB0B8 / 0x1ABAA8)
    EventTransmitter 모드 5 → 전역 이벤트, 그 외 → 대상 오브젝트에 이벤트 (EventId, Arg1, Arg2)  (0x1ABF20 / 0x1AC020)
    SpawnEnemy       TemplateId > 0 이면 엔티티 월드 행렬에 적 생성, 그룹 = Filter, 순찰 "patrolN"  (0x1AC850 → 0x1A4DB0)
    SoundController  사운드 그룹(이름 접두사) 조작
```
- **대상(targets) 은 센서만 쓴다.** 이펙터(5..15)는 대상 목록이 비어 있고, 대신 모드로 오브젝트를 고른다.
- 대상 발동은 같은 sender 를 넘긴다 → 연쇄 센서(DeathZone: EntrySensor → EventTransmitter 모드 4) 에서 "볼륨 안" 판정이 이어진다.
- Goto(name) (Internal:7, `Warp_Jump` 0x1B2E08): 이름이 같은 엔티티(Start/Marker)의 월드 행렬로 영웅 이동 + 그 엔티티의 대상 발동(`0x1A9C18`). [추정: 대상 발동 부분]

### 4.1 오브젝트 이벤트 번호 (EventTransmitter EventId, 전 레벨 집계) [추정]
| ID | 횟수 | 데이터 근거 |
|----|------|-------------|
| 1019 | 184 | `EvtForce`: Arg1 = 강제 상태 번호 (PlayerStartZone 512, PatrolChange 0, ATHN05A 영웅 DeathZone **504**) |
| 1029 | 100 | 적 DeathZone (ATHN01A Filter 8/2/3/4/5) → 제거 |
| 1015 / 1025 / 1008 | 49 / 25 / 26 | 미확인 |
| 1017 / 1018 | 13 / 15 | **영웅 PAUSE / UNPAUSE** (`0x1AC0EC`: 영웅에게 0x3F9/0x3FA 면 `"======== %sPAUSE Hero"`) [확실] |
| 200..352 (모드 5) | 각 1 | 전역 이벤트 (EventSensor 로 수신) |

게임 → 스크립트 이벤트 예: 501 = 동영상 끝 (인자0 = 동영상 이름 문자열, `MpegController-EvtMpegDone`), 1048/1049 = 붙잡기/놓기 (`TrapDoorZone`, 인자0 == 6).

## 5. Entity 스코프 함수 (scope 0) — `0x1AF848`, 점프 표 `0x2B5BC0` [확실: 동작 / 추정: 이름]

인자는 원작 함수가 VM 스택(+0x140 깊이)에서 직접 꺼낸다. 현재 엔티티 = `0x29D928`.

| N | 이름 | 인자 → 반환 | 동작 |
|---|------|-------------|------|
| 0 / 1 | Min / Max | a, b → float | |
| 2 | Clamp | v, lo, hi → float | min(max(v, lo), hi) |
| 3 | RandomRange | a, b → float | a + rand·(b−a) (`0x13E600`) |
| 4 | Sqrt | x → float | |
| 5 | FadeOut | r, g, b, sec | 화면 페이드 (0x303D50 상태 1, 프레임 = sec·60) |
| 6 | FadeIn | sec | 상태 2 |
| 7 | FindObject | name → 핸들 | 오브젝트 이름 검색 |
| 8 | Printf | fmt, 4개 | 디버그 출력 (`0x245650`) |
| **9..12** | **EventArg** | i → (float/int/bool/string) | **현재 엔티티가 받은 이벤트 인자 i(0..2)** (+0x5C + 4i). 호출 반환형만 다르다 (1195회 = 최다) |
| 13 | PlayMovie | name | 동영상 재생 (`0x1B3440`) |
| 14 | MovieCtl? | name, bool | `0x1B3490(name, b ? 0 : 2)` |
| 15 | MovieSkip? | – | 플래그 0x29D954 = 1 |
| 16 | PreloadMovie | name, int | `0x1B3490(name, n)` |

## 6. Internal 함수 인자 수 (GameMgr_OnMessage 0x1B01E8, 점프 표 0x2B5E20) [확실]

case 별 스택 감소량으로 읽고, **전 레벨 모든 핸들러를 실행했을 때 return 뒤 스택이 비고 underflow 가 없음**으로 검증했다 (`ScriptWorldTest.arityKeepsStackBalanced`; 데이터에 나오는 함수만 실행 검증, 나머지는 코드 판독).

| 인자 | N |
|------|---|
| 0 | 0 CheckPoint, 1 GetPlayTime, 5 Warp, 0xD, 0xE, 0x13 FailedScreen, 0x20, 0x21..0x2D(미구현 case) |
| 1 | 3 LoadWad, 4 LoadForWarp, 6 LoadCheck, 7 Goto, 8 SwitchCamera(카메라 관리자 `0x1EE658`), 9 CameraCtl?(bool, `0x1EE730`), 0xB 질문(msgId → 답 1021, 42 문서 §2), 0xC Idle, 0xF, 0x10 ZoneTitle, 0x11, 0x12, 0x14..0x1A (타이머 계열), 0x1C MusicGroupStop(sec), 0x1E, 0x2E, 0x2F CompleteGame, 0x3A SetEndGame |
| 2 | 2 Load(wad, entry), 0xA ShowMessage(msg, sec), 0x1B MusicGroupStart(name, sec), 0x31, 0x33, 0x36, 0x38 PlaySound? |
| 3 | 0x1D MusicGroupParam?(4, 0, 5.0), 0x1F ShowItemCard(msg, sec, kind) |
| 4 / 5 | 0x34, 0x35 / 0x37 |

LoadCheck 는 bool 을 반환하지만 데이터에서는 EntrySensor 의 Action 으로만 쓰인다 (`LoadCheckB-Entryvol-EntrySensor1`: 볼륨에 들어가면 `LoadCheck("Athn01B")` — 로드가 끝났으면 GameMgr 가 전환).

## 7. 변수 (GlobalData / LevelData)
- 메시지 0 에서 이름과 타입을 등록(`0x1AF2C0`, `"Too many LevelData variables!"`), 원본 0x4A = 변수 번호 시작, 0x4C = 개수.
- 메시지 1 에서 Init1..N 실행 → 초기값. GlobalData 는 게임 전체에서 한 번 (`"%s re-inited with different value"`, `GlobalData_Set` 0x1AEE90).
- 스코프 객체: 0 Entity `0x303D50` (vt 0x2BA338), 1 Internal `0x303D70` (vt 0x2BA308), 2 GlobalData `0x2FFE38`, 3 LevelData `0x301548` — 등록 순서 `0x1A6EC0`.

### 7.1 Internal 변수 (GameMgr getter 0x1AFE18 / setter 0x1AFFB0, 점프 표 0x2B5C10 / 0x2B5C80, 번호 − 0x21) [확실]
| 번호 | 읽기 | 쓰기 |
|------|------|------|
| 33 | 영웅 HP (+0x178) | Entity_SetHP 0x1E1638 (0..최대) |
| 34 | 최대 HP (+0x17C) | – |
| 35 | MP (0x30449C) | 0..최대 MP 로 자름 |
| 36 | 최대 MP (0x304060 [MP 레벨 0x3044B7] +0x14) | – |
| 37 / 38..42 | 아이템 9 / 3..7 보유 (0x3044C2 비트 1 << (아이템 − 1)) | 참 = AddItem 0x1D8CF0 (아이템, 1), 거짓 = DeductItem 0x1D9168 |
| 43 | 0x3044A8 (u16) | 0x1DBE10 |
| 44 / 45 / 57 | 아이템 10 / 11 / 14 개수 (0x1D9278) | AddItem (값 − 지금) |
| 48 | 난이도 0x29DB54 | – |
| 50 | 0x3044BD + 1 | 0x1B01A0 |

예: ATHN02D `MedusaRipZone-pCube3661-EventCombat1` = `set_bool Internal:39 true` (메두사의 시선 획득) + `set_float Internal:35 ← Internal:36` (MP 가득).
엔진 `GameScriptHost` 가 33..36·37..42·44·45 를 영웅 PlayerState 로 (게임: `GameScene` 이 `players` 를 넣음, 이웃 레벨 호스트도 같은 플레이어) —
예전엔 Internal 변수 쓰기를 버려 머리를 뜯어도 시선 마법이 생기지 않았다. Internal 0x12 (함수) = 0x3044A4 실수 저장 (의미 미상).

## 8. 남은 일
- SpawnEnemy 슬롯 0/1/5..8, TemplateId → 적 템플릿(`CLI_WYP_Site%d`, `0x1A4E88`) 대응.
- Vis 속성 번호(0x1C/0x1E/0x27/0x11/0x1A/0x17 → 오브젝트 속성 `0x1B1398`)의 의미 (보임/충돌/AI …).
- 이벤트 1008/1015/1025 의 의미, EventSensor 슬롯 1.
- Creator, Animator 효과의 세부, Marker 웨이포인트.
- Internal 0x9/0x11/0x12/0x34..0x37 의 의미 (0xB = 질문, 42 문서).

> **27 문서 통합 시 확인**: ATHN01A SpawnEnemy 의 TemplateId 20 = 레벨 RSRCS 적 리소스 R_SKS 의 AI1 020 → TemplateId 는 AI1 템플릿 번호로 판단 [강한 추정].
