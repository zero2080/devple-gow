# 11. 성장 / 픽업 / 저장 / 씬 흐름

도구: `devple.gow.tools.progress` (`progress:*` 명령)
대상: `work/SCKA_300.02`, `gow-rom/R_PERMK.WAD`(TWK, FLP_HUDK), 전 레벨 WAD 의 ESC 스크립트
산출물: `work/progression/dump.txt`(표), `work/progression/levels.txt`(씬 이동 호출 935행 + WAD 연결)

신뢰도: **[확실]** = 디스어셈블/데이터로 직접 확인, **[추정]** = 정황. 주소는 EE 가상 주소.

```
./gradlew :tools:run --args="progress:dump gow-rom"                                  # 비용표·성장 TWK·오브·아이템·세이브 구조
./gradlew :tools:run --args="progress:levels gow-rom work/progression/levels.txt"    # ESC 씬 이동 호출 + WAD 그래프
./gradlew :tools:run --args="progress:save data0.bin"                                # (메모리카드 덤프가 있으면) 체크섬 + 진행 상태
```

---

## 0. 요약

- **업그레이드 비용은 실행 파일이 아니라 Flash UI(FLP_HUDK) 스크립트의 상수**다. 실행 파일은 `*Orb_Capacity` HUD 변수를 100(파워 오브 300)으로
  초기화만 하고, 업그레이드 메뉴 스크립트가 현재 레벨에 따라 값을 대입한다. 투자/레벨 증가도 Flash 가 하고, 메뉴를 닫을 때 게임이 HUD 변수를
  플레이어 상태로 다시 복사한다(§1).
- 플레이어 성장 상태는 **0x304440 의 0x90 바이트 구조체 하나**(PlayerProgress)이며, 세이브 스트림에 그대로 들어간다(§4).
- 세이브 = **0x14000 바이트 고정 버퍼(0x3387B8) 의 직렬화 스트림 + u32 합 체크섬**. 체크포인트도 같은 버퍼로 메모리 안에서 직렬화하고, 재시작은
  역직렬화다(§5).
- 06 문서의 `MemCard_Save`(0x230248)/`MemCard_Load`(0x230A98) 이름은 **반대**였다: 0x230248 이 `data%d.bin` **읽기**+체크섬 검증,
  0x230A98 이 아이콘(`MC_ICON`/`MC_ICONSYS`) 생성 + **쓰기**다(§5.1, 정정).
- 상위 흐름은 `g_gameState`(0x29F428, 0..18) 상태 머신(0x21DB20, 점프 테이블 0x2B96B0)이다: 부트 → 셸 → 새 게임/불러오기 → 레벨 로드 → 플레이
  ↔ 일시정지/파워업/사망/세이브(§6).
- ESC 스크립트의 `Internal:N` 호출 = `GameMgr_OnMessage` 메시지 N. 전 레벨에서 Load/LoadWad/LoadCheck/Warp 인자를 모아 **WAD 연결 그래프와 챕터 순서**를
  복원했다(§6.4).

---

## 1. 업그레이드 비용 [확실]

출처: `R_PERMK.WAD / FLP_HUDK`, 클립 `PowerUpMenu` 프레임 3(메뉴 진입 시 현재 레벨로 비용 설정)과 `PowerUpLoop`(레벨 증가 직후 다음 비용 설정).
`progress:dump` 가 스크립트를 직접 스캔해 뽑는다(`Push "PS2_X_Level"; GetVariable; Push "n"; Equals` 뒤의 `Push "_level0/:XOrb_Capacity"; Push "값"; SetVariable`).

레벨 표기: 게임 내 레벨 = 내부 값 + 1. 값 = **현재 레벨에서 다음 레벨로 가는 데 필요한 붉은 오브**.

| 무기/마법 | HUD 접두사 | 최대(내부) | L1→L2 | L2→L3 | L3→L4 | L4→L5 | 합계 |
|-----------|-----------|-----------|------:|------:|------:|------:|-----:|
| 혼돈의 블레이드 | Blades | 4 | 1500 | 2250 | 3750 | 9000 | 16500 |
| 포세이돈의 분노 | Lightning | 2 | 1650 | 4500 | | | 6150 |
| 메두사의 시선 | Medusa | 2 | 3000 | 7500 | | | 10500 |
| 제우스의 분노 | Thunderbolt | 2 | 750 | 3500 | | | 4250 |
| 하데스의 군대 | RaiseDead | 2 | 4500 | 10000 | | | 14500 |
| 아르테미스의 검 | BerserkBlade | 2 | 3750 | 10000 | | | 13750 |

전부 최대로 올리는 데 **65,650 오브**.

### 1.1 메뉴 동작 (FLP_HUDK 스크립트) [확실]
- `PausePowerUp_State` 1..6 = 선택 항목. 블레이드는 `PS2_Blades_Level < 4`, 마법은 `PS2_X_Status == 1`(보유) 이고 `PS2_X_Level < 2` 일 때만 투자 가능.
- 필요 오브 표시: `PausePowerUpOrbs = XOrb_Capacity − PS2_XOrb_Count`.
- 투자: 버튼을 누르는 동안 매 프레임 `PS2_XOrb_Count += k`, `PS2_PowerOrb_Count −= k`. k = 1 (첫 입력, `PowerUpCounterCheck == 0`) 또는
  `PowerUp_QuickDrain` = **10**(누르고 있을 때).
- `PS2_XOrb_Count ≥ XOrb_Capacity` → `XOrb_Count −= Capacity`, `X_Level += 1`, `PowerUp_LevelUp_Check = 새 레벨`, 다음 비용 대입.
  → **투자 오브는 레벨업 때 0 으로 초기화되지 않고 초과분이 이월**된다.
- 메인 HUD 오브 미터: `PowerOrb_Capacity` = **300**(실행 파일 0x1B6B58 상수). 표시값 = `count mod 300`, 배수 `PowerOrb_Multiplier = count div 300`,
  미터 프레임 `1 + (count mod 300)/6`.

### 1.2 게임 쪽 반영 [확실]
| 단계 | 함수 | 동작 |
|------|------|------|
| 메뉴 열기 | `Progress_ToHud` 0x1D7678 | PlayerProgress → HUD `XOrb_Count`(+0x1D4..), `X_Level`(+0x1EC..), `HealthMeter_Level`, `MagicMeter_Level` |
| 메뉴 닫기 (상태 13) | `Progress_FromHudOrbs` 0x1D7700 | HUD `XOrb_Count` → +0x6A..+0x74, `PowerOrb_Count` → `SetRedOrbs` |
| 매 프레임 | `Upgrade_LoadWad` 0x1D7890 | HUD `X_Level` → +0x78..+0x7D, 레벨이 바뀐 무기의 상주 WAD 교체 (§6.3) |

- `SetRedOrbs` 0x1DBE10: `orbs = min(n, 60000)`. `orbs + BladesOrb_Count ≥ 1500` 이고 무기/마법 레벨이 전부 0 이면 `SND_LEVELUPCARD` + 안내 18
  (메시지 518 "무기를 향상시키기에 충분한 오브를 모았습니다…").
- 블레이드 레벨에 따라 기술 목록 잠금 해제: `MoveList_Lock` 0x1BA750 (HUD `MoveName/MoveButtons` 이름 첫 글자 'B'+숫자가 필요 레벨) [추정].

---

## 2. 체력·마법 성장 / 난이도 / 코스튬

### 2.1 레벨 표 (`/Player`, R_PERMK `TWK_Player`, 튜닝 구조체 0x304060) [확실]
| 내부 레벨 | 0 | 1 | 2 | 3 | 4 | 5 |
|----------|---|---|---|---|---|---|
| 최대 HP (`Max Hit Points Level n`, +0x00..) | 100 | 125 | 150 | 175 | 200 | – |
| 최대 MP (`Max Magic Level n`, +0x14..) | 0 | 100 | 125 | 150 | 175 | 200 |
| 무기 레벨 데미지 배율 (`Weapon Level n/Dmg Mult`, 0x3040C0 + n×0x50) | 1.0 | 1.5 | 2.0 | 3.0 | 4.0 | 5.0 |

`Shards Per Meter Level` = **6** (+0x44), `Deaths Before Easy Offer` = **5** (+0x40).

### 2.2 레벨이 오르는 경로 [확실]
| 경로 | 코드 | 효과 |
|------|------|------|
| 고르곤의 눈 (아이템 10) | AddItem 0x1D8DBC | `eyes = min(eyes+n, 3×6=18)`. `eyes/6` 이 늘면 HP 레벨 +Δ (최대 4). 최대 HP 갱신 |
| 불사조 깃털 (아이템 11) | AddItem 0x1D8EA8 | `feathers = min(+n, 18)`. `feathers/6` 증가분만큼 MP 레벨 +Δ (최대 5). **MP 를 새 최대치로 채움** |
| 첫 마법 획득 (아이템 3..6) | AddItem 0x1D9004 | MP 레벨이 0 이면 1 로 (= 마법 게이지 100 생성) |
| 제우스의 선물 (메시지 0x1F `ShowItemCard(msg, sec, kind)`) | 0x1B1228 | kind 1 → HP 레벨 +1 (0x1D97A8), kind 2 → MP 레벨 +1 (0x1D9828), kind 3 → 카드만. PAND00D 의 Health/Magic/Orbs Waterfall (메시지 113/114/115) |

→ 최대치 도달 경로: HP = 눈 18개(+3) + 생명의 샘(+1) = 레벨 4(200). MP = 첫 마법(+1) + 깃털 18개(+3) + 마법의 샘(+1) = 레벨 5(200). 표와 정확히 맞는다.

### 2.3 난이도 [확실]
`g_difficulty` u16 0x29DB54 (새 게임: HUD `Difficulty_Level`, 디버그 부트 기본 1). 배율 표 0x337728 + d×0xC (`/GlobGame/<이름>`):

| d | TWK | 메시지 | 플레이어 데미지 | 적 데미지 | AI 회복시간 |
|---|-----|--------|------:|------:|------:|
| 0 | Easy | 3 인간(쉬움) | 2.0 | 0.5 | 2.0 |
| 1 | Normal | 4 전사(보통) | 1.0 | 1.0 | 1.0 |
| 2 | Hard | 5 영웅(어려움) | 0.75 | 2.5 | 0.75 |
| 3 | Impossible | 6 신(매우 어려움) | 0.5 | 5.0 | 0.5 |

- 쉬움 제안 `EasyOffer_Check` 0x1DBF90: `d ∈ {1,2}` 이고 `deaths % 5 == 0` 이면 사망 메뉴에서 제안.
- 클리어 시(메시지 0x2F `CompleteGame(kind)`): kind 0 → 난이도별 해금 비트 (d 0/1 → bit0, d 2 → bit1, d 3 → bit2), kind 1(ARENA27 신들의 도전 완료)
  → bit3, kind 2 → bit4. 해금 비트 0x2A2768 → `Unlocks_Apply` 0x230118: `(m & 7) != 0` → HUD `HardMode_Unlocked`(= 신 난이도), `m & 8` →
  `Costumes_Unlocked = 3`. 이 비트는 세이브 슬롯 디렉터리에 저장된다 [확실: 코드 / 추정: 메뉴상의 의미].

### 2.4 코스튬 (`/Player/Costume n`, 0x3042A0 + n×0x20) [확실: 값]
선택값 = HUD `Costume_Choice`(hud+0x1C4), 영웅 WAD `R_HERO<n>`, 무기 WAD `R_WPN<n>_<레벨>`.

| n | Damage | AI Damage | Infinite Magic | Health Orb | Magic Orb | Weapon(붉은) Orb |
|---|------:|------:|------:|------:|------:|------:|
| 0 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 |
| 1 | 1.0 | 1.0 | 1.0 | 1.5 | 3.0 | 1.0 |
| 2 | 0.5 | 1.0 | 1.0 | 3.0 | 1.5 | 1.0 |
| 3 | 2.0 | 4.0 | 4.0 | 1.0 | 1.0 | 4.0 |
| 4 | 0.5 | 1.0 | 1.0 | 2.0 | 2.0 | 2.0 |
| 5 | 2.0 | 0.25 | 0.25 | 2.0 | 1.0 | 2.0 |

("Infinite Magic" 열은 AI Damage 와 같은 값이 반복되어 실제로는 다른 의미/쓰레기 상위 바이트일 수 있음 [추정].)

---

## 3. 오브 / 픽업

### 3.1 오브 템플릿 `TweakTemplates/Orb/NNN` (등록 0x2261C8) [확실]
| 오프셋 | 키 | 의미 (Orb_Update 0x226710 / Orb_Spawn 0x226518) |
|-------|----|------|
| +0x18 | `Oscillate Freq` (0.5) | 위아래 흔들림 주파수(Hz), 위상 += dt·π·2·f |
| +0x1C | #b60d39a0 (0.15) | 흔들림 진폭 [추정] |
| +0x20 | `Oscillate Y Offset` (0.4) | 흔들림 기준 높이 (×16 단위) |
| +0x24 | #117c872e (10.0) | **수명(초)**. 생성 시 × (rand+0.5). 초과하면 소멸 |
| +0x28 | `Spawn Time` | 생성 후 튀어나가는 포물선 비행 시간 × (rand+0.5) |
| +0x2C | `Attract Radius` (20) | **자석 반경**: 거리² < (R×16)² 이면 흡수 시작 |
| +0x30 | `Attract Time` | 흡수 비행 시간(초). 플레이어(+16 높이)로 보간, 끝나면 획득 |
| +0x34 | `Gravity` (9.82) | 포물선 중력 (×16) |
| +0x38 | `Type` (s16) | 0 체력(녹색) / 1 마법(파랑) / 2 경험(붉은) |
| +0x3C | `Points` | 기본 획득량 |
| +0x40 / +0x58 | `GO Name` / 사운드 | 모델 GO, 흡수 시작 사운드 (`SND_PICKUPS_ORBS_H/M/E`) |

- 획득 `Orb_Value` 0x226460: `Points × Costume[c].{Health,Magic,Weapon} Orb Mult` → `Player_AddOrbs` 0x1D77B0 (HP 는 최대치, MP 는
  `Max Magic Level[magicLevel]` 로 클램프, 붉은 오브는 `SetRedOrbs`). 그 뒤 안내 2 표시 검사.
- 플레이어 체력이 0 이하이면 떠 있는 오브는 소멸 [추정].
- 상태 비트(obj+0): 1 비행 → 2 대기(흔들림) → 4 흡수 → 8 소멸.

R_PERMK 기본 템플릿과 레벨 공통 인스턴스(`work/exe/twk/*` 집계):

| 이름 | Type | Points | Spawn | Attract Time | GO |
|------|-----:|------:|------:|------:|----|
| healthOrb | 0 | 10 | 0.75 | 0.25 | goHealthOrb |
| magicOrb | 1 | 3 | 1.0 | 1.0 | goMagicOrb |
| weaponOrb | 2 | 2 | 1.0 | 1.0 | goWeaponOrb |
| 10SmallHealth / 25BigHealth / healthOrbBig | 0 | 10 / 25 / 20 | 1.0/1.0/0.75 | 1.0/1.0/0.25 | goHealthOrb(Big) |
| 10SmallMagic / 25BigMagic / magicOrbBig | 1 | 10 / 25 / 10 | 1.0 | 1.0 | goMagicOrb(Big) |
| Red Orb Small 1 / 5 | 2 | 1 / 5 | 1.0 | 1.0 | goWeaponOrb |
| Red Orb Large10 / 20 / 50 | 2 | 10 / 20 / 50 | 1.0 | 1.0 | goWeaponOrbBig |
| Red Orb Mega100 | 2 | 100 | 0.5 | 0.25 | goWeaponOrbBig |
| Cheating Death | 0 | 7 | 0.75 | 0.25 | goHealthOrb |
| Null Orb | – | 0 | – | – | (빈 슬롯) |

수명 10.0, Attract Radius 20.0 은 모든 인스턴스 공통.

### 3.2 오브 방출기 `TweakTemplates/OrbEmitter/NNN` (등록 0x225E38) [확실: 키 이름 해시 역산]
| 키 | 의미 |
|----|------|
| `Health/Magic/Weapon Template` | 슬롯 3개(이름은 H/M/W 지만 실제로는 아무 Orb 템플릿 번호나 들어감, 99 = 없음) |
| `Emitter Joint/Radius`, `Target Joint/Radius` | 방출 위치 |
| `Normal Health Prob/Min/Max`, `Normal Magic …`, `Normal Weapon …` | 슬롯별 방출 확률(f32) · 개수 범위(정수, 하위 바이트) |
| `Health Threshold` + `Low Health Prob/Min/Max` | 플레이어 HP 가 임계 미만일 때 H 슬롯 대체 값 (관찰한 템플릿은 임계 0 → 미사용) |
| `Magic Threshold` + `Low Magic Prob/Min/Max` | MP 임계 미만일 때 M 슬롯 대체 값 |

예 (`progress:emitters gow-rom/ATHN04A.WAD`, 템플릿 번호는 레벨마다 다름):

| 방출기 | 슬롯 H | 슬롯 M | 슬롯 W | 합계 |
|--------|--------|--------|--------|------|
| HealthChest | 25BigHealth ×4 | – | 10SmallHealth ×11 | HP 210 |
| MagicChest (R_PERMK) | 10SmallMagic ×4 | 25BigMagic ×11 | – | MP 315 |
| Pinata1Orbs | Red Small 1 ×10 | – | Red Small 5 ×3 | 붉은 25 |
| BigRed1Orbs | Red Small 1 ×15 | – | Red Small 5 ×15 | 붉은 90 |
| small / med / large obj break | – | – | Red Small 1 ×1 / ×4 / ×10 | 1 / 4 / 10 |
| NPC Health Drop | healthOrb ×1 (p 1.0) | healthOrb ×1 (p 0.33) | – | |
| Weaponchest1 (R_PERMK) | – | – | weaponOrb ×15 | 붉은 30 |

상자 종류(인스턴스 이름 빈도): `HealthChest` 57, `MagicChest` 53, `small/med/large obj break`, `BigRed1/2/3Orbs`, `Pinata1/2/3Orbs`,
`Weapon/Health/Magic only`, `Cheating Death` 32, 적 처치용(`normalKill`, `SKS Decap`, `hydra dad …`, `NPC Health Drop`) 등.
[확실: 값 / 추정: 개수가 Min~Max 균등 난수, Prob 로 슬롯 방출 여부 판정]

### 3.3 아이템 ID (`AddItem(player, id, count, flashSprite)` 0x1D8CF0, `SCR_AddItem`/`SCR_DeductItem`) [확실]
보유 비트 `itemFlags |= 1 << (id−1)`. 개수/최대 표 0x2B81C0 / 0x2B81F0, 처리 점프 테이블 0x2B8190.

| ID | 아이템 | 저장 위치 | 최대 | 처리 |
|----|--------|-----------|-----:|------|
| 3 | 포세이돈의 분노 (Lightning) | bit 0x0004 | 1 | 마법: 새로 얻으면 선택 마법(+0x7E)으로, MP 레벨 0→1 |
| 4 | 메두사의 시선 | bit 0x0008 | 1 | 〃 |
| 5 | 제우스의 분노 | bit 0x0010 | 1 | 〃 |
| 6 | 하데스의 군대 | bit 0x0020 | 1 | 〃 |
| 7 | 아르테미스의 검 | bit 0x0040 | 1 | 플래그만 |
| 9 | 포세이돈의 삼지창 [추정: 이름] | bit 0x0100 | 1 | Player+0x2DC \|= 0x200020 (수중 능력), 로드 시에도 재적용 |
| 10 | 고르곤의 눈 | +0x7F | 18 | §2.2 |
| 11 | 불사조 깃털 | +0x80 | 18 | §2.2 |
| 14 | HUD `SirenPiece_Count` [추정] | +0x81 | 2 | 개수만 |
| 15 / 16 | (0x1D9700) HP 완전 회복 / MP 완전 회복 | – | – | AddItem 아님 |

ID 1, 2, 8, 12, 13 은 처리 없음(최대 0 또는 1). `DeductItem` 0x1D9168 은 ID 3..7, 9 의 비트를 지우고 선택 마법을 해제한다.
ESC Internal 변수 44/45/48/50/57(07 문서) = 눈/깃털/난이도/블레이드 레벨/아이템 14 개수.

---

## 4. PlayerProgress — 플레이어 영속 상태 (0x304440, 0x90 바이트) [확실: 오프셋]

`Progress_Reset` 0x1D6710(새 게임), `Save_PlayerBlock` 0x1DBFD8, `Load_PlayerBlock` 0x1DC130. Java: `PlayerProgress.LAYOUT`.

| 오프셋 | 타입 | 이름 | 내용 |
|-------|------|------|------|
| 0x00 | f32[16] | matrix | 플레이어 월드 행렬 (Player+0x10..0x4F). 체크포인트/세이브 위치 |
| 0x40 | u32 | playerFlags | Player+0x174 & 0xFFFF3BFF [추정: 의미] |
| 0x44 | u32 | – | 리셋 시 0, 참조 미발견 |
| 0x48 | u32 | deaths | 사망 횟수 (쉬움 제안, 재시작 HP 보정). 메모리카드에서 불러오면 0 |
| 0x4C | f32 | playTime | 플레이 시간(초), 매 프레임 +dt (0x1D6D7C). 메시지 1 이 스크립트에 반환 |
| 0x50 | u32 | hintMask | 첫 안내 메시지 비트. `FirstTimeHint(id)` 0x1DBEB0: 플레이 2초 이후, 비트 미설정이면 메시지 **500+id** 표시 후 비트 설정. 체크포인트 재시작에도 유지 |
| 0x54 | u8 | hintCount1 | 안내 1 은 3번째 조건 충족 때 표시 |
| 0x55 | u8 | hintCount19 | 안내 0x13 은 8번째에 표시 |
| 0x56–0x57 | u8×2 | – | 리셋만 |
| 0x58 | f32 | health | 현재 HP |
| 0x5C | f32 | magic | 현재 MP |
| 0x60 | f32 | rage | 신들의 분노 게이지, 새 게임 100 → HUD `CoolMeter_Value` |
| 0x64 | f32 | godMode | ≠0 이면 발동 중 [추정] |
| 0x68 | u16 | redOrbs | 붉은 오브 (≤ 60000) |
| 0x6A | u16×6 | investedOrbs | Lightning, Medusa, Thunderbolt, RaiseDead, BerserkBlade, Blades 순 투자 오브 |
| 0x76 | u8 | healthLevel | 0..4 |
| 0x77 | u8 | magicLevel | 0..5 |
| 0x78 | u8×6 | weaponLevels | Lightning, Medusa, Thunderbolt, RaiseDead, BerserkBlade (0..2), Blades (0..4) |
| 0x7E | u8 | selectedMagic | 아이템 ID 3..6 (0 없음) |
| 0x7F | u8 | gorgonEyes | 0..18 |
| 0x80 | u8 | phoenixFeathers | 0..18 |
| 0x81 | u8 | item14 | 0..2 (`SirenPiece_Count`) |
| 0x82 | u16 | itemFlags | 보유 비트 (bit id−1) → HUD `*_Status` |
| 0x84 | u8×12 | – | 참조 미발견 |

플레이어 오브젝트(별도): +0x178 현재 HP, +0x17C 최대 HP (`Max Hit Points Level[healthLevel]`, 0x1DCBC8 가 계산).
난이도·코스튬·옵션은 이 구조체가 아니라 세이브 스트림의 options 블록(§5.2)에 있다.

---

## 5. 저장 / 체크포인트

### 5.1 파일 [확실]
- 메모리카드 디렉터리 `MC_DATA` 태그 이름(02 문서) `BKSCKA-30002GodOfWar`, 파일 `data0.bin`..`data4.bin`, `icon.sys`, `static.ico`(0x2A2784 표).
- **파일 = 버퍼 0x3387B8 의 0x14000 바이트 그대로**. 마지막 u32(0x13FFC) = 앞 0x4FFF 워드의 합 (`Save_Checksum` 0x230160).
  읽기 후 `Save_Verify` 0x2301A8: 저장값 == 합 이거나 0 이면 유효.
- 읽기 상태기 `MemCard_ReadFlow` 0x230248 (06 문서의 "MemCard_Save" — 정정), 쓰기 상태기 `MemCard_WriteFlow` 0x230A98 (06 의 "MemCard_Load"):
  아이콘 생성 0x22F6B8 → `data%d.bin` 쓰기 0x22F410 → 슬롯 디렉터리 갱신. 읽기는 0x22F308.
- 슬롯 디렉터리 0x35D780 + 카드×0x44 + 슬롯×0x10: `{u32 파일번호, u32 0xCAFEBAD1, f32 playTime, u16 세이브포인트 번호, u8 난이도}`,
  카드 레코드 +0x40 = 해금 비트(§2.3). 슬롯 표시 `Save_SlotText` 0x22E810 `"%s    %d:%02d:%02d"`, `"Save Point %d"`.

### 5.2 직렬화 스트림 (`Save_Serialize` 0x22D490 / `Load_Deserialize` 0x22D518, 압축 없음) [확실: 순서·크기 / 추정: 일부 내용]
쓰기 `Stream_Write` 0x22DBC8 (0x800 스테이징 0x34C7C0 → 버퍼, 넘치면 실패 플래그 0x2A2700), 읽기 `Stream_Read` 0x22DCF8.

| 순서 | 크기 | 출처 | 내용 |
|------|------|------|------|
| 1 | 4 | – | 매직 0xCAFEBAD1 (불일치면 로드 중단) |
| 2 | 4 | HUD `Costume_Choice` | 다르면 영웅 WAD 재로드 |
| 3 | (10+1)×2 | 0x1BBB88 | 현재 WAD 이름 char[10] + u8 (중첩 WAD 2단) [추정] |
| 4 | 0x90 | 0x306828 | **PlayerProgress** (§4) |
| 5 | 0xC0 | 0x3068B8 / 0x1EE438 | 카메라·연출 상태 [추정] |
| 6 | 0xEC | 0x306978 / 0x22BD58 | 음악 그룹 상태 (0x2A26A8 객체) [추정] |
| 7 | 0x1C4 | 0x3368F8 memcpy | GameMgr 상태 (체크포인트·구역) [추정] |
| 8 | 0xB | 0x306C30 | 옵션: [0]0x1BF150() [1]음악 [2]효과음 [3]진동 [4]와이드 [5]디플리커 [6]사운드 모드 [7]`PowerUp_FirstTime` [8]코스튬 [9]난이도 [10]`EndGame` |
| 9 | 가변 | `World_Serialize` 0x1B3E48 | 체크포인트 위치 0x29D920.., 활성 WAD 정보, **GlobalData(0x2FFE38)/LevelData(0x301548) ESC 변수**, 엔티티별 상태 청크(0x1B4080, 0x1B3FC0) |

### 5.3 체크포인트와 재시작 [확실: 흐름 / 추정: 세부]
- ESC `CheckPoint`(메시지 0) → 체크포인트 위치·플래그(0x29D920..0x29D960) 기록 → **같은 0x14000 버퍼에 메모리 직렬화** (0x22D418, 상태 9 진입과
  0x1B3070 에서 호출). 즉 체크포인트 = 메모리 안의 세이브.
- 재시작 `Game_RestartCheckpoint` 0x21E3D0 → 0x22D440 → 역직렬화(메모리카드 플래그 0x29F444 = 0) → 레벨 재로드(상태 9).
- 재시작 HP 규칙 (`Load_PlayerBlock`):
  - 체크포인트 재시작: `hp = max(saved, 25)`; `deaths ≥ 4` 이면 `hp += (deaths − 3) × 10` 후 최대 HP 로 클램프; `saved < 75` 이면 `hp = min(hp, 75)`.
  - 메모리카드 로드: `hp = max(saved, 2)`, `deaths = 0`.
  - 저장 시점에 날아오던 오브(0x226FE0 가 합산)는 저장값에 포함된다.

---

## 6. 씬 흐름

### 6.1 게임 상태 머신 `g_gameState` 0x29F428 (`GameState_Tick` 0x21DB20, 매 프레임 Game_Frame 에서) [확실: 전이 / 추정: 이름]
| 상태 | 핸들러 | 동작 | 다음 |
|------|--------|------|------|
| 0 Boot | 0x21F550 | 메모리카드 초기화(0x22FA98), 시작 레벨 이름 = `"Athn01A"`(0x2B9740 → 0x29F418) | 1 |
| 1 ShellLoad | 0x21F630 | 셸 WAD(R_SHELLK) 로드, 타이틀 씬(0x21E9A8: `SND_M_TIT1E1_2`, `gomai`, `gofireplane`…) | 2/3 |
| 2 ShellCardCheck | 0x21F738 | 메모리카드 확인(0x22FD08) | 3 |
| 3 ShellMenu | 0x21F7C0 | HUD `Done`/`Menu_Choice`: 1 = 새 게임(난이도 ← `Difficulty_Level`, 코스튬 0), 2 = 불러오기 | 4 / 5 |
| 4 NewGame | 0x21F8D0 | 시작 레벨 WAD 설정(0x1BBB08 "Athn01A"), 0x21EFD8 | 9 |
| 5 LoadCard | 0x21F988 | `MemCard_ReadFlow`, 성공 시 0x29F444 = 1 | 6 (취소 7) |
| 6 LoadFade | 0x21FAA0 | 페이드, 영웅 WAD 준비(0x1BE750(costume)) | 8 |
| 7 LoadCancel | 0x21F9F8 | 셸로 | 3 |
| 8 LoadApply | 0x21FB70 | 영웅/세이브포인트 WAD(0x1AC1F8), 역직렬화(0x22D440), 저장된 레벨 로드(0x1BC870) | 9 |
| 9 LevelLoading | 0x21FD48 | 로드 완료 대기, 체크포인트 스냅샷 직렬화(0x22D418) | 11 |
| 10 | 0x21FEF0 | (짧은 대기) | 11 |
| 11 InGame | 0x21FF90 | 플레이. 패드 0x100 → 일시정지(`PauseMenu_Event`, 아레나/OLYMP04 에선 모드 2), 0x800 → 파워업 메뉴(`PSMMenu_Event`) | 12 / 13 |
| 12 Pause | 0x220200 | `PauseDone`+`Menu_Choice`: 0 계속, 1 체크포인트 재시작, 2/3 셸로(`ShellStartState`). 치트 입력 해시 0x8B044410 → 전부 해금+전 아이템(0x1D95B0) | 11 |
| 13 PowerUpMenu | 0x2202F8 | `PSMDone` → 투자 오브 복사(0x1D7700) | 11 |
| 14 Dead | 0x220360 | 사망 메뉴(`DeadMenu_Event`, 쉬움 제안 0x1DBF90, 아레나는 `FailedMenu`) → 재시작/종료 | 11 / 셸 |
| 15 SavePoint | 0x2204E0 | 세이브 화면 (`SaveGame_Event`), 업그레이드 WAD 정리 | 17 / 18 |
| 17 SaveWrite | 0x220608 | `MemCard_WriteFlow` | 18 |
| 18 SaveDone | 0x220740 | 결과 표시 후 복귀 | 11 |

### 6.2 GameMgr 메시지 (ESC `Internal:N` = `GameMgr_OnMessage` 0x1B01E8 의 N) [확실: 코드 / 추정: 표시 이름]
| N | 이름 | 인자 | 동작 |
|---|------|------|------|
| 0 | CheckPoint | – | 체크포인트 갱신 (`"CheckPoint"` 로그) |
| 1 | GetPlayTime | – | PlayerProgress.playTime 반환 |
| 2 | Load | wad, entry | `"======== Load ('%s', '%s') ========"` |
| 3 | LoadWad | wad | 다음 구역 WAD 를 백그라운드 로드 |
| 4 | LoadForWarp | wad | 워프용 로드 |
| 5 | Warp | – | 로드해 둔 WAD 로 전환 (`Warp_Jump` 0x1B2E08) |
| 6 | LoadCheck | wad | 로드 완료 확인 |
| 7 | Goto | 엔티티 이름 | 같은 레벨 안 위치로 이동 |
| 12 | Idle | bool | |
| 16 | ZoneReport | 구역 번호 | 구역 진입 표시 (`ZoneReport_*` HUD) |
| 19 | FailedScreen | | 아레나 실패 화면 |
| 31 | ShowItemCard | msg, sec, kind | §2.2 (kind 1 HP+1, 2 MP+1) |
| 47 | CompleteGame | kind | §2.3 해금 비트 + `EndGame` 종류(0x29D94C) |
| 58 | SetEndGame | n | HUD `EndGame` = n (ATHN08C 2, ATHN07B 3) |

### 6.3 리소스 WAD 로딩 [확실: 이름 형식 / 추정: 힙 배치]
| WAD | 시점 | 로더 |
|-----|------|------|
| `R_PERMK` | 부트 후 상시 상주 (`R_Perm` 0x1BB880, `Heap_SetupLevels`) — TWK_Player/GlobGame, FLP_HUDK, 메시지 | 상시 |
| `R_SHELLK` | 셸 상태(1..7)에서만 (`R_ShellX`) | 0x1BB4B0 |
| `R_HERO<코스튬>` + `R_SAVEPT` | 게임 시작/로드 시 "Hero" 힙 (`HERO_HEAP_SIZE`) | `Hero_LoadWads` 0x1BCF30 (`"R_Hero%d"`, `"R_SavePt"`) |
| `R_WPN<코스튬>_<블레이드레벨>` | 블레이드 레벨이 바뀔 때 교체, "Upgrade" 힙 (`UPGRADE_HEAP_SIZE`) | `"%s%d_%d"`, 이름표 0x29DB00 |
| `R_LGHTN<n>`, `R_MEDHD<n>`, `R_THBLT<n>`, `R_RDEAD<n>`, `R_BRSRK<n>` (n = 마법 레벨 0..2) | 보유 마법만, 레벨 변경 시 교체 | `Upgrade_LoadWad` 0x1D7890 (`WAD_R_Lghtn` …), `"%s%d"` |
| 레벨 WAD (`ATHN01A` …) | "Levels" 힙, LoadWad/Warp 로 교체 | GameMgr |
| 적/공용 리소스 `R_CYW`, `R_SKA` … | "Slots" 힙 (`SLOT_HEAP_SIZE`), 레벨의 `WadInfo` 템플릿 그룹(`Group n Idx` → WadInfoGroup) 이 지정 | `"R_%s"` 0x1BB928 (RSRCS 태그 0x1F4) |

무기 슬롯 상태 0x3372C0 + i×4 → `{…, +4 s16 로드된 레벨, +8 플래그(2 로드됨, 4 로드 중)}`, 영웅 슬롯 0x3372AC(+4 코스튬).
`R_WPN*_5` 파일(블레이드 내부 레벨 5)은 존재하지만 메뉴 최대는 4 → 사용처 미확인.

### 6.4 레벨 연결과 챕터 순서 [확실: 연결 / 추정: 지역 이름]
`progress:levels` 결과 (첫 문자열 인자, 대문자화). 구역 번호 = ESC `ZoneReport(n)` 인자.

```
새 게임 → ATHN01A (구역 1: 에게 해, 배·히드라)
ATHN01A→01B→01C→01D→{01E(→01EE), 01F}→ATHN02A (구역 2)→02B→02C→{02D, 03AA}
ATHN03AA→03A (3)→03C→ATHN04A (4)→{04B, 04C→05A, 04D→06A, 05C, 05D}
ATHN05A (5)→05B→05C→05D→04A          (오라클 구역)
ATHN06A (6)→06B→06C→ATHN07 (6)→SEWR01 (7)→SEWR02→{ATHN03A, ATHN03D}
ATHN03D→ATHN03E→DEST00 (8)→DEST01→DEST02→DEST03→PAND00A (9)
PAND00A→00B→00C (10, 허브)→{PAND01A (11), PAND02A (12), PAND03F/03J (13/10), PAND04AA (15)}
PAND04AA→04C→04D→04E→04F (15/16)→04G→04H→04IB→04J→PAND05A→PAND05B→HDES01A (17, 하데스)
HDES01A→01B→01C→01D→01E→ATRET01A (6, 아테네 귀환)→01B→01C→ATHN08A→08B→08C→ATHN07B→OLYMP01 (18)→OLYMP02 (엔딩, CompleteGame 0/2)
보너스 '신들의 도전': ARENA10→08→06→09→12→14→26→20→19→27 (CompleteGame 1)
```
- 모든 레벨 이동은 `LoadWad`/`LoadForWarp`(미리 로드) → 경계 볼륨의 `LoadCheck`/`Warp` 로 전환, 되돌아가는 연결도 대부분 있다.
- ATHN03D 의 `LoadCheck "Athn0E"` 는 존재하지 않는 WAD (데이터 오타, 무시).
- 구역 메시지 601..629(`난파된 범선`, `히드라의 공격`, `아테네 부두` … `미노타우르스의 둥지`)는 `ZoneReport_Title` 용 이름이지만, 번호→메시지 대응은 미확정.

---

## 7. 이번에 이름 붙인 주소

| 주소 | 이름 | 신뢰도 |
|------|------|--------|
| 0x304440 | g_playerProgress (0x90) | 확실 |
| 0x304060 | g_playerTweak (/Player 튜닝 구조체; +0x60 무기 레벨 0x50×6, +0x240 코스튬 0x20×6, +0x300 God Mode) | 확실 |
| 0x29D9F4 | g_hud (0x15B0, HUD 변수 블록) | 확실 |
| 0x29F428 | g_gameState | 확실 |
| 0x29F418 | g_startLevelName ("Athn01A") | 확실 |
| 0x29DB54 | g_difficulty (u16) | 확실 |
| 0x337728 | g_difficultyTable (0xC×4) | 확실 |
| 0x2A2768 | g_unlockMask | 확실 |
| 0x29F444 | g_loadedFromCard | 추정 |
| 0x3387B8 | g_saveBuffer (0x14000) | 확실 |
| 0x35D780 | g_saveSlotDir | 확실 |
| 0x3372A8.. | 영웅/무기 WAD 슬롯 | 추정 |
| 0x21DB20 | GameState_Tick (점프 0x2B96B0) | 확실 |
| 0x1D6710 / 0x1D6620 | Progress_Reset / Progress_NewGame | 확실 |
| 0x1D7148 | Progress_UpdateHud (매 프레임 HUD 반영) | 확실 |
| 0x1D7678 / 0x1D7700 | Progress_ToHud / Progress_FromHudOrbs | 확실 |
| 0x1D77B0 | Player_AddOrbs(hp, mp, red) | 확실 |
| 0x1D8CF0 / 0x1D9168 | AddItem / DeductItem | 확실 |
| 0x1D9278 / 0x1D92F0 / 0x1D9348 | Item_Has / Item_Max / Magic_Level | 확실 |
| 0x1D95B0 | Cheat_GiveAll | 확실 |
| 0x1D9700 | Item_Give (15 HP 회복, 16 MP 회복, 그 외 AddItem) | 확실 |
| 0x1D97A8 / 0x1D9828 | HealthLevel_Inc / MagicLevel_Inc | 확실 |
| 0x1D9870 | Upgrades_AllMaxed | 확실 |
| 0x1DBE10 | SetRedOrbs | 확실 |
| 0x1DBEB0 | FirstTimeHint | 확실 |
| 0x1DBF90 | EasyOffer_Check | 확실 |
| 0x1DBFD8 / 0x1DC130 | Save_PlayerBlock / Load_PlayerBlock | 확실 |
| 0x1DCBC8 | Player_MaxHealth | 추정 |
| 0x1BA750 | MoveList_Lock | 추정 |
| 0x2261C8 / 0x225E38 | Orb / OrbEmitter 템플릿 등록 | 확실 |
| 0x226518 / 0x226710 / 0x226460 | Orb_Spawn / Orb_Update / Orb_Value | 확실 |
| 0x226FE0 | Orbs_PendingTotal | 추정 |
| 0x22D490 / 0x22D518 | Save_Serialize / Load_Deserialize | 확실 |
| 0x22D418 / 0x22D440 | Checkpoint_Snapshot / Checkpoint_Restore | 확실 |
| 0x22DBC8 / 0x22DCF8 | Stream_Write / Stream_Read | 확실 |
| 0x22D8F8 / 0x22DA20 | Save_GameBlocks / Load_GameBlocks | 확실 |
| 0x1B3E48 | World_Serialize | 확실 |
| 0x230160 / 0x2301A8 | Save_Checksum / Save_Verify | 확실 |
| 0x230248 | MemCard_ReadFlow (06 의 MemCard_Save 정정) | 확실 |
| 0x230A98 | MemCard_WriteFlow (06 의 MemCard_Load 정정) | 확실 |
| 0x230118 | Unlocks_Apply | 확실 |
| 0x1B1228 | ItemCard_Show (메시지 31) | 확실 |
| 0x21E3D0 / 0x21E430 | Game_RestartCheckpoint / Game_QuitToShell | 추정 |

---

## 8. 재구현 명세

### 8.1 데이터
- `PlayerProgress`(§4)를 그대로 Java 레코드로 쓰고(`tools/.../progress/PlayerProgress.java`), HUD 는 이 값의 뷰로 둔다.
- 비용표는 `UpgradeTable.fromWad(R_PERMK)` 로 FLP 에서 읽거나 §1 표를 상수로. 레벨 표/코스튬/난이도/오브 템플릿은 `TweakIndex` 로 TWK 에서 읽는다.

### 8.2 규칙 (의사코드)
```
addRedOrbs(n):   orbs = min(orbs + n, 60000)
                 if orbs + invested[Blades] >= 1500 && allLevelsZero: hint(18)
orbPickup(o):    v = o.points * costume[c].mult[o.type]
                 type 0: hp = min(hp + v, maxHp(healthLevel))
                 type 1: mp = clamp(mp + v, 0, maxMagic[magicLevel])
                 type 2: addRedOrbs((int) v)
orbUpdate:       spawn: arc(time = spawnTime*(rand+0.5), gravity*16), life = 10*(rand+0.5)
                 idle: bob y = (yOffset + amp*sin(phase))*16, phase += dt*2π*freq
                       if |player - orb|^2 < (attractRadius*16)^2: home over attractTime, then pickup
                       if age > life or player dead: remove
invest(w, k):    invested[w] += k; orbs -= k   (k = 1, 버튼 유지 시 10)
                 while invested[w] >= cost[w][level[w]] && level[w] < max[w]:
                     invested[w] -= cost[w][level[w]]; level[w]++  → 해당 무기 WAD 교체
addItem(id, n):  flags |= 1 << (id-1)
                 3..6: if newly acquired: selectedMagic = id; if magicLevel == 0: magicLevel = 1
                 10:  eyes = min(eyes+n, 18); healthLevel += Δ(eyes/6) (≤4); maxHp 갱신
                 11:  feathers = min(feathers+n, 18); magicLevel += Δ(feathers/6) (≤5); mp = maxMagic
itemCard(kind):  1 → healthLevel = min(4, +1); 2 → magicLevel = min(5, +1)
death:           deaths++; if difficulty in {1,2} && deaths % 5 == 0: offer Easy
restartCheckpoint: deserialize(snapshot); hp = max(saved,25); if deaths>=4: hp = min(hp+(deaths-3)*10, maxHp)
                   if saved < 75: hp = min(hp, 75)
loadFromCard:    hp = max(saved, 2); deaths = 0
completeGame(k): k==0: unlock |= {0,0,1,2,4}[difficulty 0..3 → bit0,bit0,bit1,bit2]; k==1: |= 8; k==2: |= 16
                 hardModeUnlocked = unlock & 7; costumesUnlocked = (unlock & 8) ? 3 : 0
```

### 8.3 세이브
- 원본 호환이 목표가 아니면 §5.2 의 블록 순서를 따른 자체 포맷(예: 길이 접두 블록)으로 충분하다. 체크포인트 = 메모리 스냅샷, 세이브 포인트 = 스냅샷을 파일로.
- 저장 대상: PlayerProgress, 난이도·코스튬·옵션, 현재 WAD/체크포인트 위치, ESC GlobalData/LevelData 변수, 엔티티 상태(파괴된 상자 등).

### 8.4 씬 흐름
- 상태 머신 §6.1 을 그대로 구현 (셸 / 로딩 / 플레이 / 메뉴 4종 / 사망 / 세이브).
- 레벨 전환은 ESC 메시지(§6.2)로만 일어난다: `LoadWad`/`LoadForWarp` 로 다음 WAD 를 비동기 로드, `LoadCheck`/`Warp` 볼륨에서 전환.
- 상주 리소스: R_PERMK(항상) + R_HERO<c> + R_SAVEPT + R_WPN<c>_<블레이드> + 보유 마법 WAD(레벨별) + 레벨 WAD + WadInfo 가 지정한 적 리소스.

---

## 9. 미해결 질문
- PlayerProgress +0x40/+0x44/+0x64/+0x84..0x8F, 옵션[0](0x1BF150) 의 정확한 의미.
- 세이브 스트림 블록 5/6/7(카메라·음악·GameMgr)의 필드, World_Serialize 의 엔티티 청크 형식.
- 아이템 14(`SirenPiece`) 와 ID 1/2/8/12/13 의 정체, ID 9 가 삼지창인지(수중 비트 0x200020 로 추정).
- 코스튬 "Infinite Magic" 열의 실제 의미, `R_WPN*_5` 의 사용처.
- #b60d39a0(흔들림 진폭), #117c872e(수명) 원래 키 이름.
- ZoneReport 번호 ↔ 구역 이름 메시지(601..) 대응, 일시정지 패드 비트 0x100/0x800 의 실제 버튼.
- 사망 횟수 증가 지점(0x1D6FE4 추정)과 사망 메뉴의 세부 선택지.
