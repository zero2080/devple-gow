# 18. 동영상 포맷 (PSS / PSW)

컷신·보너스 영상. 디스크 TOC 에 PSS 67개 + PSW 53개 = 120개 (고유 이름 기준, 약 6.1GB).
기본 추출(`pak-extract`)에서는 빠지므로 도구·엔진 모두 **ISO 에서 직접 스트리밍**한다 (`GowDisc.read(entry, off, len)`).
구현은 [33 문서](33-impl-video.md), 도구 `devple.gow.tools.video`.

```bash
./gradlew :tools:run --args="video:survey P2_gow_kor.iso"                 # 120개 전부: 해상도/fps/오디오 헤더/길이 (앞 256KB 만 읽음)
./gradlew :tools:run --args="video:info P2_gow_kor.iso A01MP05.PSS"       # PES 통계, PTS 범위
./gradlew :tools:run --args="video:frames P2_gow_kor.iso SEMO01.PSS work/video/frames 8 25"   # 해당 초의 프레임 PNG
./gradlew :tools:run --args="video:wav P2_gow_kor.iso SEMO01.PSS work/video/SEMO01.wav"
./gradlew :tools:run --args="video:names gow-rom"                          # 레벨 스크립트의 PlayMovie/PreloadMovie 호출
```

## 1. 요약 (전 120개 공통, `video:survey` 결과)
| 항목 | 값 |
|------|----|
| 컨테이너 | MPEG-2 프로그램 스트림 (Sony "PSS"), 팩 16KB(0x4000) 정렬 |
| 비디오 | MPEG-2 MP@ML, **640×480**, 29.97fps (frame_rate_code 4), aspect_ratio_information 2 (4:3), 프로그레시브 프레임 픽처, I/P/B GOP. 인코더 문자열 "encoded by TMPGEnc (ver. 2.521.58.169)" (사용자 데이터 0x1B2) |
| 오디오 | private_stream_1 (0xBD) 서브 0xFF, "SShd/SSbd" 헤더, **PCM16LE 48kHz 스테레오**, 채널 인터리브 0x200 바이트 |
| A/V 시작 | 120개 모두 **첫 비디오 PTS = 첫 오디오 PTS** (값은 파일마다 0.042–0.108초) → 프레임 0 과 샘플 0 이 같은 시각 |
| 자막 | **한국어 자막이 영상에 구워져 있음** (SEMO01 8초 "더 이상 희망은 없다" 등). 별도 자막 데이터 없음 |

PS2 SDK 의 PSS 는 오디오가 보통 PS-ADPCM 이지만 이 디스크는 **전부 무압축 PCM16** (codec=1) 이다.
실행 파일 문자열 표 `0x29D4EC = {"PCM", "ADPCM", "SPDIF"}` 와 경고 "Audio not processing in MPEG %s, thinks it's %s which it probably isn't"
(`sub_00189658`) 가 코덱 종류를 구분하는 증거. 대역폭: 192KB/s 오디오 + ~4.5Mbps 비디오.

## 2. 프로그램 스트림 구조
`A01MP05.PSS` (6,012,932 B) 앞부분 (`xxd`):
```
0000  00 00 01 BA 44 00 04 00 04 01 01 74 83 F8 | 팩 헤더 MPEG-2 (14B, 스터핑 0)
000E  00 00 01 BB 00 09 ...                      | 시스템 헤더 (첫 팩만)
001D  00 00 01 E0 0F FA 81 C1 0D 31 00 01 35 AD 11 00 01 1E 37 ...   | 비디오 PES, PTS+DTS
      → 페이로드 00 00 01 B3 28 01 E0 24 13 88 23 81 ...  시퀀스 헤더: 0x280×0x1E0, aspect 2, rate 4
```
- 팩은 **정확히 0x4000 바이트 간격** (367팩 × 16KB = 0x5BC000, 마지막 4바이트 = `00 00 01 B9` 프로그램 끝).
- 팩 하나에 PES 4개 내외 (각 최대 0x1000 바이트대). 스트림 id:
  | id | 의미 | A01MP05 개수 |
  |----|------|------|
  | 0xE0 | 비디오 ES | 1059 |
  | 0xBD | private_stream_1 (오디오) | 407 |
  | 0xBE | 패딩 | 3 |
  | 0xBB | 시스템 헤더 | 1 |
- PES 헤더는 MPEG-2 형식: 플래그 0x81/0x83, PTS(5B) 또는 PTS+DTS(10B), 오디오 PES 는 PTS 뒤 0xFF 스터핑 5바이트.
- 비디오/오디오 다중화 간격: 같은 파일 위치에서 비디오 PTS 가 오디오보다 ~0.1–0.15초 앞선다 → 재생기 비디오 큐 0.8초면 충분.

## 3. 오디오 (private_stream_1)
PES 페이로드 앞 **4바이트 서브헤더** `FF A0 00 00` (서브 스트림 0xFF = SS 오디오, A0 = 트랙 0 으로 추정. 120개 모두 이 값 1종).
그 뒤 바이트들을 패킷 경계와 무관하게 이어 붙이면:
```
"SShd" u32 0x18                 ← 첫 오디오 패킷
  +08 u32 codec       1  (PCM16LE; SDK 정의상 0x10 = PS-ADPCM)
  +0C u32 rate        48000 (0xBB80)
  +10 u32 channels    2
  +14 u32 interleave  0x200
  +18 s32 loopStart   -1
  +1C s32 loopEnd     -1
"SSbd" u32 bodySize   (A01MP05 = 1,653,760 = 8.613초)
[L 0x200 B][R 0x200 B][L 0x200 B][R 0x200 B] ...   ← 채널당 256샘플 블록 교대
```
- 검증: 블록 경계(256샘플마다)의 샘플 차 평균 643 vs 블록 내부 632 (SEMO01) — 불연속 없음 = 해석 정확.
- 오디오 길이는 비디오와 같거나 조금 길다 (A01MP05: 비디오 246프레임 8.21초, 오디오 8.61초; SEMO01: 1430프레임 47.71초 / 오디오 47.70초).
- 패킷당 오디오 4,073 B ≈ 21.2ms (PTS 간격 0.0212초와 일치).

## 4. PSS vs PSW (와이드스크린)
- 두 파일 모두 **640×480, aspect 코드 2(4:3)** — 스트림에 16:9 표시가 없다.
- 같은 장면을 비교하면 PSW 는 수평으로 압축된 **아나모픽 16:9** 렌더 (`work/video/frames/A01MP05_PSS_003.00.png` vs `_PSW_`): 인물이 좁고 가로 시야가 넓다.
- 실행 파일 `sub_00189658` (`0x1897E4`~`0x189894`): 파일 이름의 마지막 '.' 을 찾아 `".m2"` / `".ps"` 와 비교해 컨테이너 종류를 정하고,
  표시 플래그 `0x29D50C & 0x80` (와이드 모드) 이면 확장자 4번째 글자가 `'W'`(0x57) 또는 `'2'` 인지 검사 → 아니면 `0x30661C = 2` (4:3 소스를 와이드 화면에 맞추는 모드로 추정).
  즉 와이드스크린 설정이면 `.PSW` 를 고르고 16:9 로 늘려 표시한다.
- **BONUS*.PSS 14개는 PSW 판이 없다** (보너스 영상: 메이킹·트레일러 등, 4:3 고정).
- 영상 아래쪽 ~32줄은 대부분 검은 띠(자막 영역/오버스캔 여유) — 그대로 표시한다.

## 5. 이름 매핑 (스크립트 → 파일)
- 레벨 스크립트 `Entity:0xD PlayMovie(name)` / `0x10 PreloadMovie(name, n)` / `0xE MovieCtl(name, bool)` / `0xF MovieSkip()` (13 문서).
  `video:names gow-rom` 로 95건 수집, PlayMovie 이름 46종. 이름은 **대소문자만 다르고 TOC 기본 이름과 1:1** (`"a01Mp03"` → `A01MP03.PSS`).
  재생 함수 `sub_00189278(name, ...)` 가 이름을 복사하고 '.' 이후를 잘라낸 뒤 확장자를 붙인다.
- 실행 파일이 직접 고르는 이름 (스크립트에 없음):
  | 이름 | 호출 | 의미 |
  |------|------|------|
  | Semo01 | `sub_0021efd8` (`0x21F0D0`) | ATHN01A 첫 진입 시 (레벨 상태 비트 0x4) — **오프닝**. 인자 0x7EFF |
  | Bonus20 | `sub_001bad40` (`0x1BAEF4`) | 메인 메뉴 크레딧 (SND_M_CREDITS1 음악과 함께) |
  | Bonus01..25 | 보너스 메뉴 (BonusName1..8 등 FLP) | 보너스 영상 |
- 표 `0x29D510`: `{name, u32}` × 3 = A01MP05 0x7CFD, A01MP02 0x7EFF, A01MP03 0x7EFF — `sub_00189278` 이 이름으로 찾아 인자를 덮어쓴다 (0x7EFF = Semo01 과 같은 값; 볼륨/플래그로 추정).
- 표 `0x29D528` (12B × 33: name, 1, 0/1/2): a01mp05 … p04mp05, SKDIntro, SatIntro, Ballway, Bonus21/25, Semo01(0,2), Bonus20(1,2), Bonus01/19/24(1,1), Bonus20a–e, Semo01a–e, demoend, h01mp03.
  직접 참조 명령을 찾지 못함 (포인터 계산 접근). `Semo01a–e`, `Bonus20a–e`, `demoend` 는 TOC 에 파일이 없다 → 파일 이름이 아닌 구간/설정 키 [미상].
- 레벨 스크립트에 없는 PSW/PSS 쌍 (P03MP06/07, P04MP05, P06MP01, SATINTRO, SEMO06/07/08, MAGICRTD, A06MP05 …) 은 실행 파일 표·보스 처리 코드에서 호출되는 것으로 보인다.

## 6. 실행 파일 쪽 재생기 (참고)
- `"PsIIShlMpeg 1230"` (`sub_00189f98`) — Sony libmpeg 기반 IPU 디코더. 상태 변수 `0x29D4F8` (1..11 상태 머신), 스트림 버퍼 `0x2FF590` (16KB 단위 `sub_0017b140(…, 0x4000, 0x4000)` 읽기 = 팩 크기).
- 미해결: 스크립트에 재생 종료를 알리는 이벤트 번호 (핸들러 `mpegDone…` h7 = 이벤트 7 로 추정), PreloadMovie 두 번째 인자(0..3) 의미.
