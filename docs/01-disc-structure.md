# 01. 디스크 구조

도구: `devple.gow.tools.iso.IsoImage`, `devple.gow.tools.pak.*`

## 1. 이미지 개요
- 파일: `P2_gow_kor.iso`, 8,455,979,008 바이트 = 4,128,896 섹터(2048B)
- **DVD9(2층)**. 레이어마다 독립된 ISO9660 볼륨이 있음.

| 레이어 | 기준 섹터 | 볼륨 크기(섹터) | System / Volume ID |
|-------|-----------|----------------|--------------------|
| 0 | 0 | 2,067,056 | PLAYSTATION / GODOFWAR |
| 1 | 2,067,040 | 2,061,856 | PLAYSTATION / GODOFWAR |

> 레이어 1의 PVD(Primary Volume Descriptor)는 `레이어0 크기` 섹터(2,067,056)에 바로 놓여 있다.
> PVD 는 원래 볼륨 시작 + 16 섹터에 있으므로 레이어 1 볼륨의 기준 섹터는 `2,067,056 - 16 = 2,067,040` 이고,
> 레이어 1 디렉토리 레코드의 LBA 는 이 기준에 대한 상대값이다. (처음엔 +16 위치를 찾다 실패 → 실측으로 수정)

## 2. 파일 목록
| 레이어 | 파일 | 크기 | 설명 |
|-------|------|------|------|
| 0 | `SYSTEM.CNF` | 57 | 부트 설정 (`BOOT2 = cdrom0:\SCKA_300.02;1`, `VER = 1.01`, `VMODE = NTSC`) |
| 0 | `SCKA_300.02` | 1,916,956 | EE(메인 CPU) 실행 파일. MIPS R5900 ELF |
| 0 | `IOPRP300.IMG` | 278,305 | IOP 모듈 교체 이미지 |
| 0 | `*.IRX` | – | IOP 드라이버 모듈 (사운드 989snd, 메모리카드, 패드, SIO2 등) |
| 0 | `GODOFWAR.TOC` | 39,180 | 게임 데이터 목차 |
| 0 | `PART1.PAK` | 4,230,144,000 | 게임 데이터 묶음 1 |
| 1 | `PART2.PAK` | 4,222,111,744 | 게임 데이터 묶음 2 |

## 3. GODOFWAR.TOC
24바이트 엔트리 배열, 0으로 채워진 레코드로 종료 (`39,180 = 1,632 × 24 + 12`).

| 오프셋 | 타입 | 필드 | 설명 |
|-------|------|------|------|
| 0x00 | char[12] | name | NUL 패딩. 12자이면 NUL 없음 (예: `TIT1E1_2.VPK`) |
| 0x0C | u32 | part | 0 = PART1.PAK, 1 = PART2.PAK |
| 0x10 | u32 | size | 바이트 |
| 0x14 | u32 | sector | 해당 PAK 내부 섹터(2048B) 오프셋 |

- 처음엔 `name[16] + size + sector` 로 오해했다. `"WAD\x01"` 처럼 확장자 뒤에 01 이 붙는 이름이 대량 발견되어 4바이트가 part 필드임을 알아냈다.
- 엔트리 1,632개 중 고유 이름 951개. **같은 파일을 디스크 여러 위치(양 레이어 포함)에 복제**해 레벨 로딩 시 탐색 거리를 줄인 것으로 보인다. 추출 시 첫 사본만 사용.

## 4. 데이터 파일 종류 (고유 기준)
| 확장자 | 개수 | 총 크기 | 내용 |
|-------|------|---------|------|
| WAD | 203 | 488 MB | 레벨/리소스 컨테이너 → [02-wad-format](02-wad-format.md) |
| PSS | ~64 | 3,986 MB | PS2 MPEG2 동영상(컷신) |
| PSW | ~50 | 2,087 MB | 와이드스크린용 동영상(추정) |
| VPK | ~238 | 1,014 MB | 스트리밍 오디오(음성/음악) |
| VAG | ~390 | 16 MB | PS2 ADPCM 효과음 |
| TXT | 19 | 0 | 더미 (`SANITY.TXT`: "SANITY TEST ... space filler") |

### WAD 이름 규칙
- 레벨: `ATHN`(아테네) `PAND`(판도라) `DEST`(사막) `HDES`(하데스) `OLYMP` `SEWR` `ARENA`(신들의 도전), 뒤 숫자/문자는 구역
- `R_*`: 공용 리소스. `R_HERO*`(크레토스), `R_PERMK`(상주 데이터, K=한글판), `R_SHELLK`(메뉴 셸), `R_SAVEPT`(세이브 포인트),
  `R_WPN{무기}_{레벨}`(무기 업그레이드 단계), `R_MEDHD/R_LGHTN/R_THBLT/R_RDEAD/R_BRSRK{레벨}`(마법 단계), 그 외 3글자는 적 (`R_MIN`=미노타우로스, `R_CY*`=사이클롭스, `R_HAR`=하피, `R_SIR`=세이렌 등 추정)

## 5. 추출
```bash
./gradlew :tools:run --args="pak-extract P2_gow_kor.iso gow-rom"        # 동영상 제외 831개, 1.4GB
./gradlew :tools:run --args="pak-extract P2_gow_kor.iso gow-rom --all"  # 동영상 포함 951개, 7.6GB
```
