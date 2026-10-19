# 14. 사운드 뱅크 (SND 리소스 / 989snd SBlk / DPMS 플러그인 / SEM 이미터)

도구: `devple.gow.tools.audio` — `SoundBank`, `VagPack`, `SoundEmitter`, `WadSounds`, `AdpcmClip`.
```bash
./gradlew :tools:run --args="audio:sbk gow-rom/R_PERMK.WAD work/sound/R_PERMK [--grains]"  # 요약 + 톤/팩 WAV 추출
./gradlew :tools:run --args="audio:sbk-all gow-rom work/sound/all"                          # 전 WAD
./gradlew :tools:run --args="audio:music gow-rom"                                           # 음악 이름 → VPK 표
./gradlew :tools:run --args="audio:hash gow-rom e423314d"                                   # 해시 → SND 이름
```
결과: 전 214 WAD 에서 **SBlk 뱅크 157 개, VAG 팩 48 개, 이름 3,644 개(고유 1,830), 톤 8,185 개(루프 994)** 디코딩, 예외 0, 클리핑 톤 0.

근거 자료: `989NOMID.IRX` (ISO 루트, LBA 1380, "989snd (c)2000-2003 SCEA, Buzz Burrowes", NO MIDI 빌드) 를 `exe:listing` 으로
디스어셈블(`work/sound/irx/989.asm`, 재배치 전 0 기준 주소). 주소 표기는 이 IRX 기준.

## 1. SND 리소스 종류 (WAD 리소스 매직)
| 매직 | 내용 | 예 |
|------|------|----|
| `0x00018` | 이름 표 + FileAttributes + **SBlk** 효과음 뱅크 + 샘플 | R_PERMK `GENERAL`(GEN1), `GENERAL2`(GEN2), `SBK_Athn01A`(A01A), `SBK_MIN_Mntaur1`(MIN) |
| `0x40018` | **VAG 팩** (메모리 상주 음성) | R_PERMK `Hero` (DIEVOC1.vag …), R_MIN `min` (MINATK_2 …) |

리소스 이름은 SBK_ 접두사가 아닐 수 있다 (R_PERMK 는 `GENERAL`). 매직으로 판별. 영웅 음성/공용 효과는 모두 R_PERMK 에 있고 R_HERO0 에는 SND 가 없다.

## 2. 매직 0x18 레이아웃 [확실]
```
+0x00 u32 0x18
+0x04 u32 N                                이름 수 (번호가 없는 소리도 있다: GEN1 소리 223 / 이름 218)
+0x08 N × { char[24] "SND_FOOTSTEP_DIRT", u32 soundIndex }
A = 8 + 28N:  FileAttributes (989snd 파일 헤더, OpenGOAL 989snd 와 같은 구조)
  +0 u32 type = 3   +4 u32 chunks = 2
  +8 {u32 off, u32 size} chunk0 = SBlk           (A 기준, 항상 off 0x18)
  +16 {u32 off, u32 size} chunk1 = 샘플 데이터     (SPU ADPCM, VAG 헤더 없음, size 0 가능 = GEN2)
```
### 2.1 SBlk 헤더 (chunk0 기준) [확실: 크기·오프셋 일치 / 추정: 이름]
| 오프 | 타입 | 필드 | 관찰 |
|-----|------|------|------|
| 0x00 | char[4] | "SBlk" | |
| 0x04 | u32 | version | 3 (전 뱅크) |
| 0x08 | u32 | flags | 4 |
| 0x0C | char[4] | BankID (뒤집힘) | "1NEG" = GEN1, "A10A" = A01A |
| 0x14 | s16 | ? | 0 |
| 0x16 | s16 | NumSounds | |
| 0x18 | s16 | NumGrains | |
| 0x1A | s16 | NumVAGs | |
| 0x1C | u32 | FirstSound | 0x40 |
| 0x20 | u32 | FirstGrain | = 0x40 + 12·NumSounds |
| 0x24 | u32 | SRAM 주소 | 0x5040 |
| 0x28 | u32 | VagDataSize | = chunk1 size |
| 0x2C | u32 | SRAMAllocSize | |
| 0x30 | u32 | NextBank | 0 |
| 0x34 | u32 | FirstGrainData | = FirstGrain + 8·NumGrains |

### 2.2 Sound (12B) [확실: IRX 0x63A0 `lb 4(s1)` 그레인 수, `lw 8(s1)` 그레인 포인터]
| 오프 | 타입 | 필드 |
|-----|------|------|
| 0 | s8 | vol (0..127) |
| 1 | s8 | volGroup — exe 0x29D078 표: 0 SFX, 1 Music, 2 Mime Dialogue, 3 Ambient Streams, 4 IOP Streams, 5 Footsteps(발소리 = 5 일치), 6 Menu, 7 Interactives, 8 MPEG, 9 Music UW, 10 SFX UW pitch, 11 SFX UW, 12 Ambient UW, 13 IOP UW, 14 Dry Effects |
| 2 | s16 | pan (도) |
| 4 | s8 | numGrains |
| 5 | s8 | instanceLimit (0 = 무제한) |
| 6 | u8 | flags (0x01 루프성/자식 소리, 0x08 음성 계열, 0x4000 은 런타임 사용 중 표시) |
| 8 | u32 | firstGrain **바이트** 오프셋 (/8 = 그레인 번호) |

### 2.3 Grain (8B) [확실]
`u32 (type << 24) | arg24`, `s32 delay`. delay 는 **그 그레인을 실행하기 전 대기 틱** — 실행기(0x63A0)는 그레인 실행 후
`countdown = grains[next].delay(u16) + 반환값` 으로 설정한다. 틱 = **240 Hz** (0x7828 `li v0, 0xF0` 반환 함수).

그레인 처리기 표 = IRX `0x1E47C` (45 항목, type < 0x2D 검사). arg 바이트 i = `(s8)(arg >> 8i)`.

| type | 이름 | 동작 (IRX 처리기) | 확신 |
|-----:|------|------------------|------|
| 1 | TONE | arg = 그레인 데이터 오프셋 → Tone 재생 (0x4A0C) | 확실 |
| 4 | LFO_SETTINGS | 16B 데이터 (0x4F14) — 미구현 | 이름 추정 |
| 5 | STARTCHILDSOUND | SoundRef → 자식 소리 시작 (0x5DBC) | 확실 |
| 6 | STOPCHILDSOUND | 자식 정지 (0x60CC) | 확실 |
| 7 | PLUGIN_MESSAGE | 32B 'DPMS' 메시지 → SMPD_IOP 플러그인 (§4) | 확실 |
| 8 | BRANCH | SoundRef: 핸들러를 대상 소리로 바꾸고 첫 그레인부터 (0x5BC8, +0xC 번호, &lt;0 이면 +0x10 이름 검색 "Didn't find child sound named") | 확실 |
| 9 | TONE2 | Tone 형식, 샘플 크기 0 (노이즈?) 10 개뿐 | 추정 |
| 10–12 | ? | GoW 확장 (10 = 20B 데이터, 78 회) — 미상 | 미상 |
| 20 / 35 | CONTROL_NULL / MARKER | 없음 (MARKER arg0 = 번호) | 확실 |
| 21 / 22 | LOOP_START / LOOP_END | END: 뒤로 LOOP_START 찾아 이동, 두 번째 반복부터 +1 틱 (0x504C) | 확실 |
| 23 | LOOP_CONTINUE | 앞의 LOOP_END 뒤로 이동 = **break** (0x50FC) | 확실 |
| 24 | STOP | -1 반환 → 그레인 종료 | 확실 |
| 25 | RAND_PLAY | arg0 선택지 수, arg1 선택지당 그레인 수, arg2 직전 선택(반복 회피, 데이터에 기록) (0x51E4) | 확실 |
| 26 | RAND_DELAY | 추가 대기 `rand % (arg24 + 1)` | 확실 |
| 27 | RAND_PB | 피치 벤드 = 균등[-32768,32767] × arg0 / 100 | 확실 |
| 28 / 29 | PB / ADD_PB | bend = arg0 × 32767/127 (음수 ×256) / 더하기 | 확실 |
| 30 | SET_REGISTER | reg[arg0] = arg1 | 확실 |
| 31 | SET_REGISTER_RAND | reg[arg0] = arg1 + rand % (arg2 − arg1 + 1) | 확실 |
| 32 / 33 | INC / DEC_REGISTER | ±1, [-128,127] | 확실 |
| 34 | TEST_REGISTER | reg[arg0] (arg1: 0 &lt;, 1 ==, 2 &gt;) arg2 이 **거짓이면 다음 그레인 건너뜀** | 확실 |
| 36 | GOTO_MARKER | MARKER(arg0) 로 이동 | 확실 |
| 37 | GOTO_RANDOM_MARKER | 범위 내 임의 마커 | 추정 |
| 38 | WAIT_FOR_ALL_VOICES | 보이스가 남아 있으면 대기 | 추정 |
| 39 | PLAY_CYCLE | RAND_PLAY 와 같되 arg2 카운터 순환 (0x5960) | 확실 |
| 40 | ADD_REGISTER | **reg[arg1] += arg0** (인자 순서 주의, 0x5A2C) | 확실 |
| 41 / 42 | KEY_OFF / KILL_VOICES | 보이스 끄기 | 확실 |
| 43 | ON_STOP_MARKER | 정상 진행 시 종료(idx = n−1). 정지 요청 시 이 다음 그레인부터 실행 (꼬리음·레지스터 정리) | 확실(종료) / 추정(정지 진입) |
| 44 | COPY_REGISTER | reg[arg1] = reg[arg0] | 확실 |

레지스터: 번호 ≥ 0 → 핸들러 로컬 4바이트(+0x4C), 음수 → 전역 표 `0x1E45C[~n]` (예: SWIM 루프가 전역 18 을 1/0 으로).
발소리 `SND_FOOTSTEP_DIRT` 는 `TEST_REGISTER reg0 == k → BRANCH` 7쌍 = 레지스터 0 이 STEP/WALK/RUN/LAND… 선택 (테스트로 확인).

### 2.4 Tone (24B) [확실: 오프셋·크기 연속성, 피치 공식은 IRX]
| 오프 | 타입 | 필드 |
|-----|------|------|
| 0 | s8 | priority |
| 1 | s8 | vol |
| 2 | s8 | **CenterNote** (음수 = PS2 샘플) |
| 3 | s8 | **CenterFine** (0..127) |
| 4 | s16 | pan |
| 6 / 7 | s8 | MapLow / MapHigh (효과음은 0) |
| 8 / 9 | s8 | PBLow / PBHigh (벤드 범위, 반음) |
| 0xA / 0xC | u16 | ADSR1 / ADSR2 (SPU 형식) — 미구현 |
| 0xE | u16 | flags (1 / 0x41 / 0 / 0x40; 0x8 이면 다른 보이스 할당 경로) |
| 0x10 | u32 | 샘플 오프셋 (chunk1 기준) |
| 0x14 | u32 | 샘플 크기 (다음 샘플 오프셋 = 오프셋 + 크기 로 연속) |

**재생 피치** (IRX 0x1B5DC → libsd export 13 `sceSdNote2Pitch`):
```
note = 60 (핸들러 기본 0x3C, 0x4140 `li v0,0x3C; sb 0x34`), fine = 0, 여기에 벤드·피치 모드 가산 (0x1B67C)
cn < 0 → |cn| 사용, 48kHz 기준          cn ≥ 0 → 결과 × 44100/48000 (PS1 샘플 보정, 0x057619F1 곱 확인)
rate = 48000 × 2^((note − |cn| + cf/128) / 12)      (SPU2 상한 0x3FFF ≈ 192kHz 로 자름 — 9 개 톤)
```
검증: (cn,cf) 빈도 상위가 표준 레이트로 떨어진다 — (−80,124)=15,989 ≈ 16k, (−74,66)=22,028 ≈ 22.05k, (−86,66)=11,014 ≈ 11.025k,
(−92,124)=7,995 ≈ 8k, (−68,124)=31,978 ≈ 32k. (cf 를 빼는 해석이면 이 일치가 깨진다.) 같은 샘플을 STEP/WALK/RUN 이 6반음 간격 피치로 쓴다.
벤드: bend &lt; 0 → bend/32768 × PBLow 반음, &gt; 0 → bend/32767 × PBHigh 반음.

### 2.5 샘플 데이터 [확실]
SPU ADPCM 16B 프레임 연속 (09 문서 §1). 첫 프레임은 무음(플래그 0), 원샷 끝 = `..., 1, 7`, 루프 = 프레임 1 에 플래그 6(시작), 끝 3(끝+반복).
전 뱅크 2,496 개 고유 샘플: 원샷 2,187 (끝 7: 2,138, 끝 1: 49) / 루프 309 (끝 3). 범위 초과 0.
신호 검사(R_PERMK / R_MIN / ATHN01A 175 샘플): RMS 2,400–14,800, 영교차율 0.03–0.59(고음 금속음만 0.5↑), 클리핑 0,
길이 0.13–3.2s. 발소리 DIRTSTEP: 0.35s, RMS 3,451, 영교차 0.247, 인접 샘플 상관 &gt; 0.3 (노이즈 아님).

## 3. VAG 팩 (매직 0x40018) [확실]
```
+0 u32 0x40018  +4 u32 N  +8 N × { char[24] "DIEVOC1.vag", u32 오프셋(리소스 시작 기준) }   → 각 오프셋에 완전한 VAGp 파일 (09 §2)
```
R_PERMK `Hero` 16 항목(크레토스 기합·피격), R_MIN `min` 10 항목 (16 kHz, 0.6–2.5s).

## 4. DPMS 플러그인 메시지 (PLUGIN_MESSAGE 데이터, 32B) [확실: 형식 / 추정: 일부 필드 의미]
'DPMS' = `SMPD_IOP.IRX` (exe IOP_Init 이 로드) 의 플러그인 ID.

| 오프 | cmd 0 (재생) | cmd 0x16 (진동) |
|-----|-------------|----------------|
| 0x00 | "DPMS" | "DPMS" |
| 0x04 | 0 | 0x16 |
| 0x08 | char[8] 이름 (파일명, 확장자 없음) | u32 모터 (0/1) |
| 0x0C | (이름 계속) | u32 값 (1..4, 500) |
| 0x10 | u32 param (음성 분류? 2 영웅 피격, 3 영웅 공격, 5 사망, 6 피격, 7 공격, 9 숨/아이들) | |
| 0x14 | u32 **kind**: 0 메모리 VAG 팩 항목 / 1 디스크 `NAME.VAG` / 2 디스크 `NAME.VPK` 스테레오 / 3 `NAME.VPK` 4채널 | |
| 0x18 | u32 **loop** (LP 앰비언트·전투 음악 = 1) | |
| 0x1C | u32 1 = 음악 계열(SND_M_*), 0 = 대사·효과 [추정] | |

cmd 0x16 은 GENERAL2 의 `SND_SHAKE_*` / `SND_RUMBLE_*` 에만 있다 → 패드 진동 [추정: 인자 의미].
kind 1 파일은 대사(`A01HL01` …), 비명(`MSCRM01`), kind 2 는 앰비언트 루프(`A2DockLP`), 컷신 대사(`A01HL17`), 음악 큐(`ATH1Q1_2`).

## 5. 음악 / 앰비언트 선택 [확실: 연결 / 추정: 파라미터]
스크립트 `MusicGroupStart("SND_M_ATH1C1_4", 0.0)` 의 문자열은 **레벨 뱅크의 소리 이름**이다. 그 소리의 그레인이 DPMS kind 2/3 로 VPK 를 연다.
`audio:music` 결과 (`work/sound/music.txt`, 257 개):

| 이름 패턴 | 스트림 | 의미 | 예 |
|----------|-------|------|----|
| `SND_M_<지역><n>C<k>_2` | `<..>C<k>_2.VPK` loop | 전투(Combat) 루프 | SND_M_ATH1C2_2 → ATH1C2_2.VPK (ATHN01B, ATHN01E) |
| `SND_M_<..>E<k>_2` | loop | 탐험(Explore) 루프 | SND_M_ATH2E1_2 (ATHN02B, ATHN02D) |
| `SND_M_<..>Q<k>_2` | 1회 | 큐(Queue/stinger) | SND_M_ATH2Q3_2 (ATHN02A) |
| `SND_M_<..>_4` | kind 3, 4채널 loop | 2 스템 음악 (MusicGroupParam 으로 층 조절 추정) | SND_M_ATH1C1_4 (ATHN01A) |
| `SND_M_AREC<nn>_2` | loop | 도전의 신전(아레나) 전투 | ARENA06 … |
| `SND_M_CREDITS1` | loop | 엔딩 크레딧 | OLYMP02 |

지역 접두사: ATH 아테네, PAN 판도라 사원(121), DES 사막, HAD 하데스, SEW 하수도, OLY 올림푸스, ARE 아레나, TIT 타이탄, ATR 귀환.
MusicGroupStart 로 참조되지 않는 63 개는 SoundController(MusicGrp…) 또는 다른 경로로 시작된다 [추정].
`MusicGroupStop(sec)` 는 페이드 시간, `MusicGroupParam(a, b, sec)` 는 ATHN01A 에서 (4, 0, 5.0) — 의미 미확정.
앰비언트 `*_LP` (예 `SND_OCEANSTORM_LP`) 는 톤 루프 또는 DPMS VPK 루프이며 SEM 이미터 + SoundController 로 켜고 끈다 (§6).

## 6. SEM 사운드 이미터 (EMT 매직 0x1A, 224B) [확실: 이름·행렬 / 추정: 거리]
```
+0x10 float[16] 로컬 행렬 (소유 GO 기준, +0x40 이동)
+0x50 char[24] × 4   소리 이름 슬롯 — SoundController Op 1..4 가 선택 (SNDGrpAmbDeckofShip: Op1 OCEANSTORM_LP / Op2 POSTSTORMAMB_LP)
+0xD0 u32 8000(전부)  +0xD4 u8 mode 0..3  +0xD8 float near  +0xDC float far   (mode 2 는 near 170 / far 10 → 의미 다름)
```
GO 그룹 연결: WAD 의 `GROUP{ CXT go, (size 0) SEV_x, ESC_x, SEM_y }` 에서 같은 그룹의 ESC 이름 ↔ SEM. ESC 이름은 24자 제한으로
**앞이 잘린다** (`ESC_NDGrpHatchOpenStrug` ↔ 그룹 `SNDGrpHatchOpenStrug`) → 접미사 비교.

## 7. 이름 ↔ 해시 [확실]
전투 이벤트 SOUND(3)/SCR_SOUND(40) 의 `Event.hash` = **TWK 해시 `h = h·127 + c`** (06 §5). SND 이름은 대문자라 hash = hashUpper.
`SND_MINO_SNORT`, `e423314d = SND_HAMHITGROUND_SOFT`, `e89a8f09 = SND_MINO_VOTAUNT2` 등 확인.
R_PERMK 콤보 이벤트 중 일부(`#b9630087` 60 회, `#0279aaec` 35 회, `#bbbfa8cc` 15 회 …)는 어느 뱅크·exe 문자열로도 풀리지 않는다 →
잘린 뱅크에서 빠진 소리이거나 런타임 생성 이름으로 추정 (재생 안 함).

## 8. 미해결
- 그레인 10–12, LFO(4) 효과, ADSR 엔벨로프, Tone flags 비트, Sound flags 0x08.
- DPMS param(+0x10)·+0x1C, cmd 0x16 인자, MusicGroupParam 의미, SEM mode·+0xD0·mode 2 거리.
- 리버브(Base Reverb 그룹, exe 0x29CFE8 프리셋), 수중(UW) 그룹 전환.
