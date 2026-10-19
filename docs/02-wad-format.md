# 02. WAD 컨테이너 포맷

도구: `devple.gow.tools.wad.WadFile` / `wad-ls` 명령

## 1. 태그 레코드
WAD 는 **태그 레코드의 연속 스트림**이다. 압축 없음, little endian.

| 오프셋 | 타입 | 필드 |
|-------|------|------|
| 0x00 | u16 | tag |
| 0x02 | u16 | flags (리소스 태그에서는 선언된 서버 슬롯 인덱스로 추정) |
| 0x04 | u32 | size |
| 0x08 | char[24] | name |
| 0x20 | u8[size] | data — **16바이트 정렬** 패딩 |

예외: **PARAM(0x18) 태그는 데이터가 없고 `size` 필드 자체가 정수 값**이다.
(처음 일괄 파싱 시 `EntityCount`(값 53)를 크기로 오해해 스트림이 어긋남 → 어긋나는 직전 태그 통계로 원인 특정)

## 2. 태그 ID
| ID | 이름 | 설명 |
|----|------|------|
| 0x0018 | PARAM | 정수 파라미터 (`EntityCount`, `HERO_HEAP_SIZE`, `SLOT_HEAP_SIZE`, `UPGRADE_HEAP_SIZE` …) |
| 0x001E | RESOURCE | 리소스. data 첫 u32 = 리소스 매직. size 0 이면 이미 로드된 리소스에 대한 참조(인스턴스) |
| 0x0028 | GROUP_START | 그룹 시작 — 직전 리소스의 하위 의존 리소스를 묶음 |
| 0x0032 | GROUP_END | 그룹 끝 |
| 0x006E | MC_DATA | 메모리카드 저장 정보 (`BKSCKA-30002GodOfWar`) |
| 0x006F | MC_ICON | 메모리카드 아이콘 |
| 0x0070 | MESH_EXTRA | `MSH_*`, `NCV_*` 부가 데이터 |
| 0x0071 | TWEAK | `TWK_*` 튜닝 테이블 |
| 0x0072 | TWEAK_REF | `TWK_*` 참조 |
| 0x01F4 | RSRCS | 리소스 목록 (`SKS` …) |
| 0x029A | DATA_START | 데이터 섹션 시작 |
| 0x0378 | HEADER | 파일 첫 태그. data u32 = 레벨 힙 크기 (`R_PERMK` 는 0xFFFFFFFF) |
| 0x03E7 | POP_HEAP | 서버 선언부 끝 |

## 3. 파일 레이아웃
```
HEADER  WAD_<name>                       (힙 크기)
GROUP{                                   ── 서버(리소스 매니저) 선언부
  RES WAD_<name>   0x8000001B
  GROUP{ RES GFXX_<name> 0x8000000C }    각 서버를 그룹으로 하나씩
  GROUP{ RES TXRX ... } GROUP{ MATX } GROUP{ SNDX } GROUP{ LGTX } GROUP{ FLPX } GROUP{ LINE }
  GROUP{ RES CXT_ 0x80000001             컨텍스트(게임 오브젝트) 서버 하위에
    GROUP{ANMX} GROUP{SCRX} GROUP{BHVX} GROUP{MDLX} GROUP{PRTX} GROUP{CAMX}
    GROUP{EEPR} GROUP{EMTX} GROUP{COLX} GROUP{SHGX} GROUP{FX_} GROUP{WYPX} }
}GROUP
POP_HEAP
DATA_START
PARAM EntityCount = N
RES SBK_<name>   사운드뱅크 (내부에 PARAM 리스트 + 'SBlk' 블록)
RES GFX_* / PAL_* / TXR_* / MAT_* ...   리소스 본체 (의존 순서대로)
RES MDL_* ... GROUP{ ... }GROUP
GROUP{ RES go* (size 0) ... }GROUP      게임 오브젝트 인스턴스 목록
TWEAK TWK_*                              튜닝값
RSRCS
GROUP{ RES ANM_hero_<level> } ...         레벨별 애니메이션 세트
```

## 4. 리소스 매직 (data 첫 u32)
`0x8000_00XX` = 서버 선언, 그 외 = 리소스 데이터 (상위 16비트는 서브타입/버전).

| 하위 16비트 | 서버 | 이름 접두사 | 비고 |
|------------|------|------------|------|
| 0x01 | CXT | `CXT_`, `go*` | 게임 오브젝트 / 컨텍스트 (0x20001, 0x40001 서브타입) |
| 0x03 | ANM | `ANM_` | 애니메이션 (0x10003 = 애니메이션 세트) |
| 0x04 | SCR | `ESC_`, `SCR` | 스크립트 (0x10004) |
| 0x06 | LGT | `PSdi…`, `PSpo…` | 라이트 (directional / point) |
| 0x07 | TXR | `TXR_` | 텍스처 (GFX + PAL 참조) |
| 0x08 | MAT | `MAT_` | 머티리얼 |
| 0x09 | CAM | `CAM_` | 카메라 |
| 0x0C | GFX | `GFX_`, `PAL_` | GS 이미지 / 팔레트 원시 데이터 |
| 0x0F | MDL | `MDL_` | 모델 (0x2000F), 0x1000F 변형 |
| 0x11 | COL | `CDV_` `CDZ_` `ENV_` `ENZ_` `SEV_` `SEZ_` `CMV_` `CMZ_` | 충돌 / 환경 / 카메라 볼륨 |
| 0x13 | PRT | `PTC_` | 파티클 |
| 0x14 | WYP | `WYP_` | 웨이포인트 (AI 경로) |
| 0x17 | BHV | `Brea…`, `Push…` | 비헤이비어 (부서지는 물체, 미는 물체 등) |
| 0x18 | SND | `SBK_` | 사운드뱅크 |
| 0x1A | EMT | `SEM_` | 이미터 |
| 0x1B | WAD | `WAD_` | WAD 자체 |
| 0x1E | FX | `FXC_` | 이펙트 (0x1001E–0xD001E 서브타입) |
| 0x21 | FLP | `FLP_` | UI 레이아웃 / 2D 애니메이션 |
| 0x27 | SHG | `SHG_` | 그림자 지오메트리 |

## 5. TWEAK(TWK_) 테이블 — 1차 관찰
```
u32 0xFEDCBA98   매직 (0x77542F80 변형도 존재)
u32 count | 0x80000000
cstr path        예: "/Animation/goHero/"
반복 { u8 0x20, u32 keyHash, u32 value(float 또는 int) } ...
```
키는 해시라서 실행 파일의 해시 함수와 문자열 테이블로 역산해야 한다 → 06 문서에서 계속.
