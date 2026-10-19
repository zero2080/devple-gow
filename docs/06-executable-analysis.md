# 06. 실행 파일(SCKA_300.02) 분석 — 서브시스템 지도

도구: `devple.gow.tools.exe` (`exe:*` 명령), `devple.gow.tools.tweak` (`tweak:*` 명령)
대상: `work/SCKA_300.02` (EE ELF, MIPS R5900 LE, 심볼 제거)

신뢰도 표기: **[확실]** = 디스어셈블/데이터로 직접 확인, **[추정]** = 문자열·호출 패턴 등 정황.
주소는 모두 EE 가상 주소(16진).

---

## 0. 요약

- 자체 R5900 디스어셈블러 + 상수 전파 분석기로 **함수 6,222개**, 문자열 참조 1,431개, 호출 인자 상수를 복원했다.
- 엔진은 **"리소스 서버" 구조**다. WAD 태그 스트림을 태그 ID별 핸들러가 처리하고, 리소스(0x1E)는
  `g_servers[data.u16[0]]`(0x3300C8 배열) 의 가상 함수 `Load` 로 넘어간다. 서버 이름/타입 ID 표를 완전히 복원했다(§4).
- 메인 루프는 `while(!quit) { Process_Tick; Game_Frame; FrameTimer; }` 이며 **게임 상태 = 현재 활성 WAD** 다.
  셸(R_SHELLK), 레벨(ATHN01A …)이 모두 WAD 이고, 레벨 전환/체크포인트/워프는 메시지 핸들러 `GameMgr_OnMessage`(0x1B01E8) 가 처리한다.
- **TWK 키 해시 = `h = h*127 + (signed char)c`** (초기값 0, 대소문자 구분; 대문자화 변형도 존재). 전체 TWK 고유 키 3,609개 중
  **3,138개(87%) 이름 복원**, 나머지 중 301개는 코드에 상수로 박힌 위치까지 특정했다.
- TWK 덤프에서 **성장/전투 핵심 수치**를 그대로 얻었다: 레벨별 최대 HP(100→200)·MP(0→200), 무기 레벨별 데미지 배율(1→5),
  난이도별 데미지 배율, "God Mode"(= **신들의 분노**) 게이지/배율, 마법 4종의 단계별 수치, 오브 포인트 등(§7).
- 플레이어 **콤보/기술 테이블**은 `TWK_CombatFile_00N`(TWEAK_REF 0x72 바이너리)에 있으며, 0x18 바이트 레코드마다
  애니메이션 이름의 대문자 해시(`COMBO7A`, `BLOCKBACKFLIP`, `AIRIMPALE`, `BRUTAL1`, `PARRY01` …)가 들어 있다(§8.1).
- 네이티브 "스크립트 컴포넌트" `SCR_*` 74종의 이름 → 구현 함수 표를 복원했다(§6).
- **정정**: 00 문서의 `R_BRSRK` 는 마법 "신들의 분노"가 아니라 **아르테미스의 검(BerserkBlade)** 으로 판단된다. 신들의 분노는 코드상 "God Mode".

---

## 1. 방법론

### 1.1 도구 선택
- REA(rizin/Ghidra) MCP 는 이 환경에서 MIPS 대상 공급자가 없어(rizin 미설치, Ghidra 미설정) 사용 불가.
- macOS 기본 `objdump`(LLVM)는 MIPS 타깃이 없음.
- → **Java 로 R5900 디스어셈블러와 정적 분석기를 직접 작성**했다. 재현 가능하고 이후 단계에서도 재사용한다.

### 1.2 구현 (`tools/src/main/java/devple/gow/tools/exe/`)
| 클래스 | 역할 |
|-------|------|
| `Elf` | ELF32 로더. PT_LOAD(파일 0x1000 → VA 0x100000, filesz 0x1C8768, memsz 0x25E780) 매핑, 섹션 표 |
| `R5900` | 명령 디코더. MIPS III 정수, COP0, COP1(단정도, EE 전용 `adda/madd/max/min` 포함), EE 확장(`lq/sq`, 3피연산자 `mult`, MMI 4그룹 `pcpyld/por/…`), COP2 는 `qmfc2/qmtc2/lqc2/sqc2` 만 해석하고 VU0 매크로는 `vu0 0x…` 로 원시 표시 |
| `ExeAnalysis` | ① 함수 경계: `jal` 대상 + `jr ra`/지연 슬롯 뒤 `addiu sp,sp,-N` + 데이터 영역 코드 포인터 + (2차) `lui/addiu` 로만 주소가 취해지는 콜백. ② 함수별 선형 상수 전파: `lui/addiu/ori/move`, `gp`(=0x2D0770, `_start` 에서 추출), `.rodata` 의 `lw` 는 상수로 취급. 결과로 ADDR/LOAD/STORE/CALL 참조와 **호출 지점별 인자 a0~a3** 를 기록. ③ 리소스 `names.txt` 로 수동 이름 부여 |
| `Cli` | 명령 (아래) |

```
./gradlew :tools:run --args="exe:listing work/SCKA_300.02 work/exe/SCKA.asm"    # 전체 주석 디스어셈블리(43만 행)
./gradlew :tools:run --args="exe:funcs   work/SCKA_300.02 work/exe/funcs.txt"   # 함수: 크기, 호출자 수, 참조 문자열
./gradlew :tools:run --args="exe:strings work/SCKA_300.02 work/exe/strings.txt" # 문자열 → 참조 함수/데이터
./gradlew :tools:run --args="exe:dis     work/SCKA_300.02 0x1b01e8"            # 함수 하나
./gradlew :tools:run --args="exe:xref    work/SCKA_300.02 0x2b5ce8"            # 주소로의 참조
./gradlew :tools:run --args="exe:calls   work/SCKA_300.02 0x175c98"            # 호출 지점별 상수 인자 (등록 테이블 추출에 핵심)
./gradlew :tools:run --args="exe:tree    work/SCKA_300.02 0x138d40 2"          # 호출 트리
./gradlew :tools:run --args="exe:words   work/SCKA_300.02 0x2b5e20 64"         # 데이터 워드(포인터 해석)
```

### 1.3 분석 요령 (이 바이너리의 특징)
- 컴파일러는 GCC 2.9x 계열(EE). **가상 함수 테이블 엔트리가 8바이트 `{s16 delta, s16 idx, fnptr}`** 라서
  가상 호출은 `lh a0,N(vt); lw v0,N+4(vt); jalr v0; addu a0,obj,a0` 형태다. 슬롯 번호 = N/8.
- `switch` 는 `.rodata` 점프 테이블(`sltiu`/`sll 2`/`lw`/`jr`).
- 등록형 코드가 많다(태그 핸들러, 서버, SCR, 튜닝 변수). **`exe:calls` 로 등록 함수의 인자를 뽑으면 표가 바로 나온다.**
- 한계: 상수 전파는 함수 내 선형 스캔(분기 합류 무시)이라 드물게 잘못된 주소 주석이 붙을 수 있다. 디컴파일러 없음.

### 1.4 산출물 (`work/exe/`, git 제외)
`SCKA.asm`, `funcs.txt`, `strings.txt`, `scr_commands.txt`, `class_names.txt`, `twk/twk_<WAD>.txt`(159개 WAD), `twk_R_HERO0.txt` 외 3개.

---

## 2. 메모리 맵 [확실]

| VA | 크기 | 섹션 | 내용 |
|----|------|------|------|
| 0x100000 | 0x19C864 | .text | 코드. 게임 코드 ≈ 0x100000–0x265000, 이후 SDK(989snd, libmpeg, libgraph/dma, libcdvd, libdbc, libpad2, libmc2, libc, libkernl) |
| 0x29C880 | 0x15F80 | .data | 전역 초기값, vtable, 서버/튜닝 전역(0x29D3xx), 문자열 포인터 표 |
| 0x2B2800 | 0x15F68 | .rodata | 문자열, 점프 테이블, vtable 다수(0x2B9xxx–0x2C4xxx) |
| 0x2C8780 | 0x95328 | .bss | 0x2C8780–0x35DAA8 (crt0 가 `sq zero` 로 클리어) |
| 0x2D0770 | – | gp | small data 기준 |
| 0x35DAA8– | – | 힙 | `InitMemory`(0x138CA8): "Root" 힙, 끝(0x1FF7FFC)에 0x0BADC0DE 가드 |
| (overlay) | 26개 | .DVP.overlay | VU1 마이크로코드 (렌더러, 재현 대상 아님 → 셰이더로 대체) |

주요 전역 (확실): `0x3300C8` g_servers[타입ID] · `0x29D198` g_quit · `0x29D310` dt(초) · `0x29D318` 배속 적용 dt ·
`0x3350B0..` 태그 처리 그룹 스택 · `0x305E90..98` 서버 선언 버퍼.

---

## 3. 부트 → 메인 루프 [확실]

```
_start 0x100008
 ├ .bss 클리어, gp=0x2D0770, syscall SetupThread/SetupHeap
 ├ InitMemory 0x138CA8          "Root" 힙
 └ main 0x138D40(argc, argv)
     ├ FS_Init 0x17ABE8 ("cdrom" / "host0:" 경로 선택 0x17AC88: "cdrom0:\%s;1")
     ├ 0x138B08, 0x183A48, 0x17AA70(Master 서버/렌더), 0x220B28(GS/화면 초기화)
     ├ IOP_Init 0x17BDE0         IOPRP300.IMG, sio2man/dbcman/mc2_d/ds2u_d/libsd/smpd_iop.irx
     ├ Game_Init 0x17AAA0
     │    ├ 이름 레지스트리 0x175AD8, "RB NODE Heap"
     │    ├ Tweak_Init 0x17E6A8 ("VFS")
     │    ├ 패드 0x17C168, SCR_Mirror/SCR_cloth 등록
     │    ├ "/TweakTemplates" 0x182988
     │    └ Engine_Init 0x21D328 ── 태그 핸들러, 서버 생성, SCR 환경 명령, 템플릿 타입(Orb, Arrow, Camera*, Fog, Bonus,
     │                              Attachment, BalanceMeter, MFX, Rope, Cloth, WadInfo…), Heap_SetupLevels, HUD 변수…
     ├ MainLoop 0x17AB78:  while (!g_quit) Frame()
     │    Frame 0x17AB48:
     │      Process_Tick 0x17A990   Master 서버 가상 호출(패드 갱신 등)
     │      Game_Frame   0x2208E8   wad = Wad_GetActive(); g_servers[wad.type]->vt[0x80/8](wad) (= 갱신),
     │                              0x21DB20, g_servers[0x1B]->vt[0x88/8] (= 렌더/후처리 추정)
     │      FrameTimer   0x17D8F0   EE Timer0(0x10000000) → dt, 60Hz 기준 /60.0, 배속(0x29D304/308) 적용
     └ Game_Shutdown 0x17AB10
```

**게임 상태 머신**: 별도 enum 상태기가 아니라 **"현재 활성 WAD + 그 WAD 의 엔티티/스크립트"** 가 상태다.
`Wad_GetActive`(0x1BF008)는 WadServer 의 현재 WAD 에서 `+0x50` 자식 링크를 끝까지 따라간다(중첩 로드).
셸 메뉴는 `R_SHELLK.WAD`(`ShellX`, `R_ShellX` 문자열 0x1BB4B0/0x1BB4E0), 상시 데이터는 `R_PERMK.WAD`(`R_Perm` 0x1BB880).

---

## 4. 엔진 코어

### 4.1 WAD 태그 핸들러 — `Wad_RegisterTagHandlers` 0x185810 [확실]
| 태그 | 핸들러 | 의미 |
|------|--------|------|
| 0x18 | 0x1862B0 | PARAM |
| 0x1E | 0x178B40 | RESOURCE → 서버 Load (아래) |
| 0x28 / 0x32 | 0x178D48 / 0x178D58 | GROUP_START / GROUP_END |
| 0x3C | 0x185A48 | (미확인) |
| 0x46, 0x29A | 0x178DD0 | DATA_START 계열 |
| 0x50, 0x309 | 0x178E58 | (미확인, 0x29A 짝) |
| 0x6E / 0x6F | 0x1862E8 / 0x186360 | MC_DATA / MC_ICON |
| 0x70 | 0x1863E0 | MESH_EXTRA |
| 0x71 | 0x17ECB8 | TWEAK (§5) |
| 0x72 | 0x17EC48 | TWEAK_REF: `u32, cstr 경로, 바이너리 블록` (CombatFile 등, §8.1) |
| 0xC8 | 0x14C2F8 | (FLP 관련 추정) |
| 0x378 | 0x185760 | HEADER |
| 0x3E7 | 0x1857F0 | POP_HEAP |
| 0x1F4 | 0x1BB928 | RSRCS — `"R_%s"` 리소스 WAD 로드 [추정] (`0x1BB5A8` 에서 별도 등록) |

`Tag_Resource`(0x178B40): 크기 0 이면 이름으로 기존 리소스 참조, 아니면
`obj = g_servers[*(u16*)data]->vt[10](server, tagHeader, data)` 후 전역 이름 레지스트리(`Registry_Insert` 0x175C98)에 태그 이름으로 등록.
→ **리소스 매직 하위 16비트 = 서버 타입 ID** 가 코드로 확인됨(02 문서의 추정 확정).

### 4.2 리소스 서버 표 — `Servers_Create` 0x17D218 [확실]
`exe:calls 0x17cd48 0x17ce30` 로 추출. (부모 5 = Master, 0xD = renMasterSvr, 2 = renPrimMaster)

| ID | 서버 | ID | 서버 | ID | 서버 |
|----|------|----|------|----|------|
| 0x01 | GOServer (게임 오브젝트, CXT) | 0x0D | renMasterSvr | 0x1A | EmitterServer |
| 0x02 | renPrimMaster | 0x0E | renTriStripSvr | 0x1B | WadServer |
| 0x03 | AnimServer | 0x0F | renModelServer (MDL) | 0x1C | renEEPrimSvr (EEPR) |
| 0x04 | ScriptServer (SCR/ESC) | 0x11 | CollisionServer | 0x1D | renSTriStripSvr |
| 0x05 | Master_Server | 0x13 | renParticleSvr (PRT) | 0x1E | EffectsServer (FX) |
| 0x06 | LightServer | 0x14 | WaypointServer | 0x20 | renPtclPrimSvr |
| 0x07 | TextureServer | 0x15 | EvtServer | 0x21 | renFlashServer (FLP) |
| 0x08 | MatServer | 0x17 | BhvrServer | 0x22 | renFlashPrimSvr |
| 0x09 | CameraServer | 0x18 | SoundServer | 0x23 | renLineServer (LINE) |
| 0x0A | ProServer | 0x0B | EpiServer | 0x24 | renLStripSvr |
| 0x0C | GfxClutServer (GFX/PAL) | | | 0x27 | renShadowServer (SHG) |

WAD 선언부의 `GFXX/TXRX/MATX/SNDX/LGTX/FLPX/LINE` 은 0x17C9D8 로 (타입 0xC,7,8,0x18,6,0x21,0x23) 매핑된다.
각 서버의 실제 `Load` 구현(vt 슬롯 10) 목록은 서버 생성자 추적이 필요해 **미완**(§10 우선순위 1).

### 4.3 이름 레지스트리와 해시 [확실]
- `Hash127` 0x1758A0: `h = h*127 + (signed char)c` (seed 인자, 보통 0). `Hash127Upper` 0x1758E0: a–z 를 대문자로 바꿔 동일 연산.
- 전역 레지스트리(0x175AB8 반환)는 해시 → 포인터 RB 트리("RB NODE Heap"). 리소스, `SCR_*` 구현, 템플릿이 모두 여기에 이름으로 등록된다.

---

## 5. TWK 튜닝 시스템

### 5.1 해시 알고리즘 [확실]
```java
int h = 0; for (byte b : key.getBytes(ISO_8859_1)) h = h * 127 + b;   // b 는 부호 있는 char
```
- 근거: `Tweak_AddFloat`(0x17DCA8) → 노드 이름 설정 0x180B88 → 가상 0x180CB8 → 노드 종류 3/5/6(그룹류)은 `Hash127Upper`, 그 외(값)는 `Hash127`.
- 검증: "On" = 79·127+110 = 0x279F, "Off" = 0x13A44F 가 코드 0x18D288/0x18D2A0 에 즉치값으로 존재. 접미사만 다른 키(…F/…B/…L/…R) 해시 차이가 정확히 문자 코드 차이.
- 해시가 선형 누적이라 **접미사 역산**이 가능: `hash(p+s) = hash(p)·127^|s| + tail(s)` → `hash(p) = (K − tail(s))·inv(127^|s|) mod 2^32`.
  애니메이션 튜닝 키는 실행 파일이 `Hash127("_Tween", seed=hash(애니이름))` 으로 만든다(0x116BB0) → `<애니이름>_Tween/_Cycle` 1,719개를 이렇게 복원.

### 5.2 바이너리 포맷 (WAD 태그 0x71) [확실 — `Tweak_ParseBinary` 0x180208]
텍스트 `.twk` 파일(`%s.twk`, `%s_%03d.twk` 문자열 존재)을 토큰화한 형태. 레코드 첫 바이트의 상위 니블이 종류:

| 바이트 | 뒤따름 | 의미 |
|-------|--------|------|
| (선택) `98 BA DC FE` + u32 | – | 헤더 매직 0xFEDCBA98 + 개수(정확한 의미 미상). 0x17ECB8 이 있으면 8바이트 건너뜀 |
| 0x80 | cstr | 절대 경로(루트). 예 `/Animation/goHero/`, `/TweakTemplates/Orb/000/` |
| 0x10 | cstr | 하위 그룹 시작(이름 원문, 숫자면 `%d` 로 인스턴스 번호) |
| 0x40 | – | 그룹 끝 |
| 0x2N | u32 hash + 값 | N=0: 4바이트. N=1..7: 32/64/256/512/1024/2048/4096 바이트(점프 테이블 0x2B4250) — 문자열/블록 |

※ 02 문서의 "count\|0x80000000" 해석은 틀렸다. 0x80 은 경로 레코드 타입이다.

**값 해석 주의**: 0x20 레코드는 항상 4바이트지만 실제 변수 타입은 등록 함수가 정한다(float 0x17DCA8, short 0x17DDE8, bool/열거 0x17E220, 문자열 0x17E250 …).
bool·short·인덱스형은 **하위 바이트만 유효**하고 상위 바이트는 이전 값의 쓰레기다. 예: `"Allow God Mode" = 0x3F4C0001` → TRUE, `"Walk Forward" = 0x42C60001`(99.00001) → 애니 인덱스 1.
덤프는 float 처럼 보이면 float 로 표시하므로 이런 값은 원시 16진으로 다시 봐야 한다.

### 5.3 이름 복원 결과 — `tweak:dict`
| 단계 | 후보 | 결과 |
|------|------|------|
| 직접 일치 | 실행 파일 문자열(+모든 접미사, 숫자 접미 0–99), WAD 내 식별자형 문자열 51만 개, 대문자 변형 | |
| printf 전개 | `"Stage %d Magic Drain"`, `"S%d Close Left"`, `"Input %d Bias"` 등 `%d` → 0–63 | |
| 접미사 역산 | `_`/` ` 로 시작하는 실행 파일 문자열 56개 중 10개 이상 해결한 것만 채택 → `_Tween`, `_Cycle` | |
| 2단어 조합 | 실행 파일 단어 3,607개 × 구분자(""/" "/"_") | 93개 (대조군 우연 일치 3 → 약 3% 오탐, 사전에 "추정" 구획으로 분리) |
| **합계** | 고유 키 3,609 | **3,138 (87%)** |

남은 471개 중 다수는 **코드에 미리 계산된 해시 상수**로 등록된 키다(이름 문자열 자체가 바이너리에 없음, 예: 애니 액션 0x18D1C0 의 `0x175D4CF5`).
이 중 301개는 `twk-const.txt` 에 사용 코드 주소를 기록해 덤프에서 `#175d4cf5(@0018d278)` 처럼 표시한다.

```
./gradlew :tools:run --args="tweak:dump gow-rom/R_HERO0.WAD work/exe/twk_R_HERO0.txt"
./gradlew :tools:run --args="tweak:dump-all gow-rom work/exe/twk"
./gradlew :tools:run --args="tweak:stats gow-rom"
./gradlew :tools:run --args="tweak:dict work/SCKA_300.02 gow-rom tools/src/main/resources/devple/gow/tools/tweak/twk-names.txt"
```

### 5.4 튜닝 트리 구조 (전체 WAD 159개 / 테이블 5,190개)
| 경로 | 위치 | 내용 |
|------|------|------|
| `/Player/` | R_PERMK `TWK_Player` | HP/MP 레벨, 타깃팅, God Mode, 저체력 연출, 블레이드 체인, `Weapon Level 0..5`, `Costume 0..5`, `Bonus`, `Reticle`, `Lightning`, `MedusaHead`, `Thunderbolt`, `RaiseTheDead`, `BerserkBlade` |
| `/GlobGame/` | R_PERMK `TWK_GlobGame` | 중력 50, 경사 60°, 난이도 `Easy/Normal/Hard/Impossible`, 보스 `BMI`(미노타우로스 추정)/`BAR`(아레스 추정), `BloodSpots` |
| `/System/Flash`, `/System/Sound` | R_PERMK | UI 메시지 스타일, 사운드 믹서 |
| `/Animation/<go>/` | R_HERO0 등 | 애니별 `_Tween`(블렌드 시간)/`_Cycle`, `Navigation/Bank N`(상태→애니 매핑: `ANIWalk = "berwalk"` …), `<ANI>_ACTn`(발 IK·발소리 등 애니 액션) |
| `/TweakTemplates/<Type>/<NNN>/` | 각 WAD | 인스턴스형 템플릿: Orb, OrbEmitter, Arrow(Emitter), PlayFX, Concussion(충격파/넉백), CameraShake/Filter/DOF, ForceFeedback, RepeatPress/TimedPress(QTE), Bonus, WadInfo/WadInfoGroup(레벨 로드 목록), DTre(결정 트리 AI), CombatFile(Set) … |

---

## 6. 스크립트 컴포넌트 `SCR_*` 표 [확실 — `exe:calls 0x175c98`]

레벨 WAD 의 SCR 리소스(매직 0x10004, 이름 `SCR_Sky`, `SCR_Entities` …)와 **이름이 같은 네이티브 구현**이 레지스트리에 등록된다.
(등록 포인터가 생성 함수인지 실행 함수인지는 [추정] — 핸들러 시그니처 분석 필요.) `ESC_*` 는 이벤트 스크립트(바이트코드) 리소스.

| 분류 | 이름 → 구현 주소 |
|------|-----------------|
| 환경/렌더 (0x21D328 등록) | SCR_Sky 246B30, SCR_Fog 2467A8, SCR_LayeredFog 246860, SCR_AresSky 246BB8, SCR_FarClip 2466F0, SCR_RopeSkinned 246A70, SCR_Weather 246DA8, SCR_LensFlare 2469B0, SCR_Water 246CE8, SCR_GhostTrail 246918, SCR_ThingOnASpring 246C50, SCR_Shadow 2465B0, SCR_Mirror 2464C8, SCR_cloth 1061F8 |
| 엔티티/레벨 | SCR_Entities 1A7388 (EntityCount, 레벨 엔티티 배치), SCR_Spawn 19F388, SCR_FollowPlayer 19E488, SCR_SonicScream 19EEE0, SCR_Teleport 18DD40, SCR_SubTrack 193DA0, SCR_SwitchNavBanks 19BAC0, SCR_SendBreakEvent 19B838, SCR_ExplodeDeath 199B58, SCR_TurnToRock 19F8E0, SCR_UnFreeze 19F738, SCR_ArcherShoot 18DAA8 |
| 이펙트/사운드 | SCR_PlayEffect 19A720, SCR_ConcussionBurst 191B70, SCR_Sound 20A568, SCR_NoShadow 20A718 |
| HUD/UI | SCR_ResetHUD 1931D0, SCR_RegenHealthBar 18E880, SCR_BalanceMeter 18EEE8, SCR_BalanceMeterRight 18F068, SCR_GrowTimer 190030, SCR_NoPlayerBars 20A8B8, SCR_FirstTimeMsg 19CD20, SCR_FlashSprite 19CD88, SCR_Commend 19CDD8, SCR_GlowEnemyMeter 1C29C8 |
| QTE | SCR_RepeatPress 1924F0 (연타), SCR_TimedPress 192ED0 (타이밍) |
| 아이템/무기 | SCR_AddItem 19CCA0, SCR_DeductItem 19CC20, SCR_Enable/Disable/HideBerserkBlade 19C578/19C5C0/19C6C0, SCR_BerserkBladeFX 19C798, SCR_Show/HideAttachments 19CEB8/19CE48, SCR_ClearRadius 19C920, SCR_DetachFromWall 19CBB0 |
| 마법 | SCR_Lightning 1948F0; SCR_ShowMedusaHead 197280, SCR_MedusaHead 198348, SCR_FlashMedusaHead 1986B0, SCR_AimMedusaHead 1987A0, SCR_TriggerMedusaHead 197FD8, SCR_MedusaNuke 198928, SCR_EyeBeamOn/Off 199790/1999A8; SCR_Show/Charge/Throw/Trigger/PlaybackThunderbolt 19FF60/1A04F0/1A0B00/1A0E98/1A0FF0; SCR_RaiseTheDead 19DE90 |
| 조준/보스 | SCR_LeftRightAim 20A340, SCR_LeftAim 20A3C8, SCR_RightAim 20A430; SCR_BMITurnOffVents 1C24E8, SCR_BMIOnPlatform 1C2590, SCR_BMIBrokenPieces 1C2600, SCR_BMINoDodge 1C2770 |

---

## 7. 게임플레이 서브시스템 지도

### 7.1 성장/업그레이드 (빨간 오브, 레벨) — 데이터 중심 [확실: 수치 / 추정: 흐름]
- `Player_RegisterTweaks` 0x1D4F90 (`/Player`):
  `Max Hit Points Level 0..4` = **100/125/150/175/200**, `Max Magic Level 0..5` = **0/100/125/150/175/200**,
  `Shards Per Meter Level` = **6** (고르곤의 눈/불사조 깃털 조각 6개 = 게이지 1단계 추정), `Deaths Before Easy Offer` = 5,
  `Targeting Range` 10, `Manual Targeting Range` 50.
- `Weapon Level 0..5`(0x1D5CC8): `Dmg Mult` = **1, 1.5, 2, 3, 4, 5**, 레벨 1부터 God Mode 허용, God Mode 데미지 ×2, 적 데미지 배율 0.75→0.3, 트레일 색.
- `Costume 0..5`(0x1D5EC8): 데미지/AI 데미지/오브 배율, 무기 길이 — 보너스 의상.
- HUD 변수(0x1B6490)에 `PowerOrb_Count/Capacity`, `<Magic>Orb_Count/Capacity`, `Lightning_Level` … `Blades_Level`, `HealthShard_Count`, `MagicShard_Count`, `SirenPiece_Count` → **레벨업 메뉴는 Flash(FLP) UI 가 이 변수로 구동** [추정].
- 무기/마법 레벨별 리소스 WAD: `R_WPN{무기}_{레벨}`, `R_MEDHD0-2` 등을 `Upgrade_LoadWad` 0x1D7890 이 `"WAD_R_Wpn"`… 로 로드, `UPGRADE_HEAP_SIZE` 전용 힙(0x1BB5F0) [추정].
- 오브: `TWK_Orb_NNN` 템플릿 `"Points" = 10`, `"GO Name" = "goHealthOrb"`, 획득 사운드. 오브 생성은 `OrbEmitter` 템플릿.
- 레벨업 비용(오브 몇 개)은 아직 미발견 → §10.

### 7.2 전투
| 요소 | 위치 | 비고 |
|------|------|------|
| 콤보/기술 테이블 | `TWK_CombatFile_000..006`(R_PERMK, 0x72) / 템플릿 0x202718, 0x202868 | §8.1. CombatFileSet `"Instance Name" = "Hero"`, `File 0..7` |
| 데미지 배율 | `/GlobGame/<난이도>` (0x1BF1F0): Easy 2.0/0.5, Normal 1/1, Hard 0.75/2.5, Impossible 0.5/5 (플레이어 데미지/AI 데미지), AI 회복시간 배율 | [확실] |
| 엔티티 속성 | 0x1BECA0: `bitfield0..3`, `SheetFX`, `PartType`, `WallHangTimer`, `Damage`, `AIDamage` | 엔티티 공통 속성 이름 [추정] |
| 방어/패리 | `BlockMod` 0x19BEF0 (`"Move "`, `" Min Block"`, `" Limit"`), 애니 `block*`, `parry01` | [추정] |
| 넉백/충격 | `Concussion` 템플릿 0x190388, `SCR_ConcussionBurst` | [추정] |
| 피격 연출 | `/GlobGame/BloodSpots` 0x1F9550, `CameraShake`, `ForceFeedback`, `TimeWarpBlur` | [확실] |
| 블레이드 | 체인 물리 `Blade Damping`, `Segment Length` …(0x1D4F90), 렌더 0x1F9C38 (`MAT_swordtrail/chainlink/chainglow`), God Mode 판 `goMAIgodblade` 0x1FA9A0 | [확실] |
| 잡기/처형(QTE) | `RepeatPress`/`TimedPress` 템플릿, 버튼 아이콘 이름 표 0x29DA40 (`XButtonGreek`, `Up180CW`, `360CW`, `WiggleStick`, `R2ButtonPress` …), CombatFile 의 `brutal1..3` | [확실] |
| 미니게임 원(잡기 프롬프트) | `goMiniGameCircle` 0x1E1428, `Circle Health Threshold`/`Circle Scale`(AI 0x18B650) — 적 체력이 임계 이하일 때 원 표시 | [추정] |
| AI | `DecisionTree`(0x24E108, `TWK_DTre_NNN`), `DefaultHFSM` 0x11BD88, 보스 패턴 `/GlobGame/BMI` 0x1C1EB0 (거리 구간별 공격 확률 `S%d Close Left` …) | [확실: 데이터] |

### 7.3 마법 / 무기 (단계 = 업그레이드 레벨)
| 게임 내 | 코드 이름 | 튜닝/등록 | 리소스 |
|--------|----------|-----------|--------|
| 포세이돈의 분노 | Lightning | 0x193F10 `/Player/Lightning`: `Stage %d Damage Per Second/Magic Drain/Duration/Reach/Mash …` | R_LGHTN0-2, `goLightningAttackMain` |
| 메두사의 시선 | MedusaHead | 0x1965D8 `/Player/MedusaHead`: `Stage %d Distance` … | R_MEDHD0-2, `goMewH`, `SND_MEDUSA_RAY_SHOT` |
| 제우스의 분노 | Thunderbolt | 0x19FB50: `Stage %d Arrow Emitter/Charge Time/Ch1·Ch2 Threshold·Magic Drain` | R_THBLT0-2, `goBolt`, `goBoltChargeUp` |
| 하데스의 군대 | RaiseTheDead | 0x19D098: `Stage %d Num Dead Guys/Max Time/Damage Per Hit/Homing Amount` | R_RDEAD0-2, `goDedRed/goDeadHit/goDeadDie` |
| 아르테미스의 검 | BerserkBlade | 0x19C128: `Stage %d Damage Multiplier` 1.75/2.25/3.0, `Stage %d GO Name` | R_BRSRK0-2 |
| 신들의 분노 | **God Mode** | `/Player`: `God Mode On Threshold` 99, `Off Threshold` 1, 무기 레벨별 `God Mode Meter Drain` 7.07/s, `Dmg Mult` 2, 사운드 `SND_TRANSFORM_LP/END`(0x1D4DF0) | HUD `CoolMeter_Value` [추정] |

### 7.4 UI / HUD
- UI 는 **Flash 계열(FLP)**: `renFlashServer`(0x21), ActionScript 류 실행기 0x145320(`Play/Stop/GotoFrame/SetTarget/GotoLabel/CallFrame`), `FSCommand:`/`FS_Result` 연동 0x144E68.
- `Hud_RegisterFlashVars` 0x1B6490: 게임→UI 변수 약 200개(`HealthMeter_Value`, `MagicMeter_Value`, `EnemyMeter_Value1-3`, `Combo_Amount`, `HitCounter_Value`, 메뉴/메모리카드/보너스/옵션/이벤트 `*_Event`).
- 텍스트: `msgs%s.txt`(언어 접미 `_en`), `Msg %d`, `Menu %d` (0x1B9260).

### 7.5 카메라
- CameraServer(9), 카메라 볼륨 리소스 `CMV_/CMZ_`(COL).
- 모드 이름 표 0x29F0D8: `Fixed, Zoned, Model Viewer, Flying, Freeze, Ballista, … Zoom, Tilt, Combo, Follow, Swimming`, 디버그 모드 0x29E138 `Overhead, Fly, ModelViewer, Follow, Cinema`.
- 카메라 AI 가중치 튜닝 0x1F5400 (`dolly rot`, `boom speed`, `bowl …`, `seperating …`, `obscured …`).

### 7.6 이벤트 / 레벨 전환 / 체크포인트
- `GameMgr_OnMessage` 0x1B01E8: 메시지 ID 0–0x3A 점프 테이블(0x2B5E20). 처리: `CheckPoint`, `Load(wad, entry)`, `LoadWad`, `LoadForWarp`, `Warp`, `LoadCheck`, `Goto(entity)`, `Idle`, 저장/메모리카드/HUD 메시지 등. 디버그 출력 `"======== Load ('%s', '%s') ========"`.
- `GlobalData`/`LevelData` 스크립트 변수(0x1AEE90/0x1AF3F0, "Too many …variables") — ESC 스크립트가 쓰는 전역/레벨 변수.
- `WadInfo`/`WadInfoGroup` 템플릿: 레벨별 로드할 GO 목록(`GO %d Name`, `Extra GO %d Cap`).
- 엔티티 이벤트 전송 `"Sending event %u to '%s'"` 0x1AC020.

### 7.7 저장 / 체크포인트
- 세이브 포인트 엔티티 `goSavePoint`(0x1A5130), 클래스 `SavePoint`(0x22E260), 텍스처 `TXR_SavePt%02d`, WAD `R_SAVEPT`.
- 메모리카드: libmc2, 파일 `data0..4.bin`, `icon.sys`, `static.ico` (0x2A2784), 저장 0x230248 / 로드 0x230A98 [추정], 슬롯 텍스트 `"Save Point %d"`, 플레이 시간(0x22E810).
- 로드 후 복원 0x1A7A78 `"** Restarted or loaded save game **"`.

### 7.8 입력
- libpad2(0x27C4C8–0x27CDE0) 래퍼: `Pad_Update` 0x17C510 → 0x17BFC0 → `Pad_Read` 0x17C2A8 [추정]. 진동 `ForceFeedback` 템플릿 → 0x17B4C8.
- 버튼 매핑/아날로그 처리 코드는 미확인.

### 7.9 기타
- 사운드: 989snd(IOP RPC) 0x26C340…, 사운드 그룹 표 0x29D078(`SFX, Music, Mime Dialogue, Ambient Streams …`), 리버브 프리셋 0x29CFE8.
- 동영상: libmpeg 0x26FFD8–, 재생 0x189658, 컷신 목록 0x29D510(`A01MP05`, `SatIntro`, `Bonus20a` …).
- 엔티티 클래스 서술자(vtable 에 Create/GetName): `Player`(0x22C910, 0x230바이트), `Enemy1`, `PushPull`, `IO_TandF`, `IO_CSM`, `Breakable`, `SavePoint`, `HeroBreak`, `Creator`, `BalanceGeom`, `GlobalData`, `LevelData`, `Entity`, `DefaultHFSM`.

---

## 8. 핵심 데이터 발견

### 8.1 CombatFile (플레이어/적 기술 테이블) [확실: 해시 / 추정: 필드]
`TWK_CombatFile_000`(R_PERMK, 태그 0x72): `u32 1`, `"/TweakTemplates/CombatFile/000/"`, 이후 블록.
- 블록 머리 `u16 0x25(=37, 레코드 수 추정), u16 0x11, u16 0x0AE8, u16 0x0AE8`, 이어서 **0x18 바이트 레코드**, 각 레코드 +0 에 **애니 이름의 `Hash127Upper`**:
  `COMBO7A, BLOCKBACKFLIP, BLOCK360, BLOCKFORWARDFLIP, DASH, BLOCKLAUNCHER, COMBOJUMP, AIRV1, AIRH1-3, AIRDASH, HIGHFALLLAND, AIRIMPALE, AIRIMPALELAND, COMBO5A, IMPALESLAMCHARGE, COMBO4D, COMBO6A, BLOCK, BLOCKREACTION, BLOCKBREAK, BLOCKLAUNCHERB, WALLJAB*, BRUTAL1/3, PARRY01 …`
- 나머지 필드(입력 조건, 다음 기술 연결, 캔슬 프레임, 데미지)는 미해석 → §10 우선순위 2. `CombatFile_001..006` 은 적(병사 등)용으로 추정.

### 8.2 애니 내비게이션 뱅크
`/Animation/goHero/Navigation/Bank N`: 이동 상태 → 애니 이름(Bank 0 `ANIWalk = "bwalk"`, Bank 1(아르테미스의 검) `ANIWalk = "berwalk"` …). 무기 장착에 따라 뱅크 교체(`SCR_SwitchNavBanks`).

---

## 9. 주요 주소 목록
`tools/src/main/resources/devple/gow/tools/exe/names.txt` 에 약 120개 주소의 이름·설명·신뢰도를 기록했고, `exe:listing` 에 반영된다.
(부트/루프, 태그 핸들러, 서버, 레지스트리/해시, 튜닝 API, 플레이어/마법/난이도, 메시지 처리, 저장, HUD, 입력, 카메라, AI, 클래스.)

---

## 10. 재구현을 위한 심화 분석 우선순위

1. **서버별 `Load`(vt 슬롯 10) 구현 목록** — 리소스 포맷(03~05 문서) 해석의 정답지. 서버 생성자 → vtable 추적 (`Servers_Create` 의 선언 스트림 처리 0x17C980 분석).
2. **CombatFile 레코드 필드** — 콤보 입력/연결/데미지. 해석기(0x202718 계열 템플릿 클래스의 Load)와 레코드 대조.
3. **ScriptServer / ESC 바이트코드 VM** — 레벨 이벤트·트리거의 실체. `SCR_*` 등록 포인터의 시그니처, EvtServer 메시지 ID 표(`GameMgr_OnMessage` 0x3B 개).
4. **플레이어 상태기/입력 → 애니** — `Player` 클래스(ctor 0x245B88, 0x230 바이트) vtable, 내비게이션 뱅크, 패드 버튼 비트.
5. **데미지 계산 경로** — 난이도/무기레벨/코스튬/God Mode 배율이 곱해지는 함수 (튜닝 변수 주소 xref 로 역추적).
6. **업그레이드 비용과 메뉴 흐름** — FLP 이벤트(`PowerUp_FirstTime`, `*_Level`) ↔ 오브 차감 코드.
7. GO 클래스 전체 목록(GOServer 의 `go*` → 클래스) — WadInfo 그룹과 연결.
8. 세이브 데이터 구조(`data%d.bin`, `MC_DATA`).

## 11. 미해결 질문
- TWK 헤더 u32 `count`의 정확한 의미(최상위 레코드 수와 불일치).
- 미해결 키 471개(대부분 코드 상수 해시) — 원 이름 복구는 추측 외 방법 없음. 현재는 사용 코드 주소로 의미 추적.
- 2단어 조합으로 복원한 93개 키는 약 3% 오탐 가능(사전 파일에 구획 분리).
- 태그 0x3C, 0x50/0x309, 0xC8 의 의미.
- `SCR_*` 등록 포인터가 생성자인지 실행 함수인지.
