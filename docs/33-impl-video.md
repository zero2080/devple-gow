# 33. 구현: 컷신 재생 (MoviePlayer / MovieScene)

포맷은 [18 문서](18-video-format.md). 패키지 `devple.gow.tools.video` (디먹스·디코딩), `devple.gow.engine.video` (재생·렌더).

```bash
./gradlew :engine:run --args="--movie SEMO01"                       # 오프닝 (ISO 에서 직접 스트리밍)
./gradlew :engine:run --args="--movie a01Mp03 --wide"               # PSW (16:9)
./gradlew :engine:run --args="--movie SEMO01 --movie-start 8 --screenshot work/video/eng_semo01_8s.png --frames 40"
./gradlew :tools:run  --args="video:bench P2_gow_kor.iso SEMO10.PSW 60"   # 디코딩 속도
```
옵션: `--wide` (PSW 우선), `--movie-start 초` (앞부분은 디코딩만 하고 버림), `--mute` (오디오 장치 없이 벽시계).
재생이 끝나면 창이 닫히고 `[movie] SEMO01.PSS: 표시 1430 프레임, 드롭 0, ...` 통계를 출력한다.
파일 찾기 순서: `gow-rom/NAME.PSS|PSW` (`pak-extract --all` 로 꺼낸 경우) → ISO (`-Dgow.iso`, `./P2_gow_kor.iso`, `gow-rom` 실제 경로의 부모).

## 1. 기술 선택: JCodec 0.2.5 (순수 Java)
| 후보 | 판단 |
|------|------|
| **JCodec `MPEGDecoder`** (Maven Central `org.jcodec:jcodec:0.2.5`, 2019) | **채택.** 의존성 jar 하나, 네이티브 없음. 120개 전부 같은 단순 프로파일(640×480 프로그레시브 MP@ML)이라 JCodec 의 MPEG-2 지원 범위 안 |
| FFmpeg (JavaCV/bytedeco) | 플랫폼 네이티브 jar 수십~수백 MB, macOS arm64 서명/로딩 이슈. 속도 여유가 이미 25배라 이득 없음 |
| 자체 네이티브 헬퍼 (libmpeg2) | 빌드 체인 추가. 불필요 |

측정 (Apple M4, JDK 21, 단일 스레드, `video:bench`, 디먹스+디코딩+YUV 복사 포함):
| 파일 | 프레임 | 실시간 대비 | 처리 속도 | 디코더만 |
|------|-------|-----------|----------|---------|
| A01MP05.PSS (파일) | 246 (8.2초) | | 629 fps (JIT 예열 전, 짧음) | 692 fps |
| A01MP01.PSS (파일) | 804 (26.8초) | | 701 fps | 770 fps |
| SEMO01.PSS (ISO) | 1430 (47.7초) | ×27.9 | **835 fps** | 924 fps |
| SEMO10.PSW (ISO, 60초) | 1802 | ×25.3 | 757 fps | 836 fps |

→ 29.97fps 재생에 필요한 CPU 는 코어 하나의 ~4%. 측정한 4개 파일 모두 디코딩 오류 0 (첫 GOP 가 닫혀 있어 참조 없는 B 프레임이 없다).
엔진 실재생: A01MP05 246/246 프레임, SEMO01 **1430/1430 프레임 표시, 드롭 0**, 재생 위치(오디오 시계) 47.72초 / 경과 48.21초 (차이 0.5초 = 창·OpenAL 초기화 + 프리롤 0.12초).

화질 검증: `video:frames` PNG 를 눈으로 확인 — 블록 깨짐·색 뒤틀림 없음, 한글 자막 선명 (아래 스크린샷).
주의: JCodec 픽셀은 부호 있는 값(−128..127) → `^0x80` 로 0..255 변환. `decodeFrame` 버퍼는 세 평면 모두 루마 크기로 줘야 한다 ("ByteBuffer too small").

## 2. 구성
| 클래스 | 역할 |
|-------|------|
| `tools.video.MovieSource` | 바이트 소스 (파일 / ISO TOC 엔트리), 이름 → 파일 해석 (와이드 = PSW 우선) |
| `tools.video.PssDemuxer` | 팩/PES 파싱, PTS, private_stream_1 서브헤더 4바이트 제거. 256KB 버퍼 순차 읽기 |
| `tools.video.SsAudio` | SShd/SSbd 헤더, 0x200 블록 디인터리브 → LRLR PCM16 (PS-ADPCM 코덱 0x10 도 대비) |
| `tools.video.Mpeg2Video` | ES → 액세스 유닛 분할(B3/B8/00 경계, 필드 픽처 2장 묶음) → JCodec → I/P 보류 방식 표시 순서 재정렬 → `VideoFrame` |
| `tools.video.MovieNames` | 스크립트 이름 → TOC 기본 이름 (대문자화) |
| `engine.video.MoviePlayer` | 디코딩 스레드 + 큐 + 시계 + 표시 프레임 선택 (GL 무관, 헤드리스 테스트 가능) |
| `engine.video.MovieAudio` | OpenAL 스트리밍 소스 1개 (2048프레임 × 8버퍼), 재생 시계 |
| `engine.video.MovieRenderer` | Y/U/V → R8 텍스처 3장, `movie.frag` 에서 BT.601 제한 범위 변환, 레터/필러박스 |
| `engine.video.MovieScene` | 씬 스택 오버레이, START/× 건너뛰기, 종료 시 pop + 콜백 |

## 3. 스레드 / 동기
```
[movie-NAME 스레드]  PssDemuxer ─┬─ 0xE0 → Mpeg2Video → frames (ArrayBlockingQueue 24장 ≈ 0.8초, 꽉 차면 대기)
                                └─ 0xBD/FF → SsAudio → pcm (LinkedBlockingQueue, 비디오 큐가 흐름 제한)
[메인 60Hz update]   pcm → MovieAudio (빈 AL 버퍼만큼), 0.12초 쌓이면 alSourcePlay
                     clock = startAt + (끝난 버퍼 샘플 + AL_SAMPLE_OFFSET) / 48000      ← 오디오가 마스터 시계
                     (오디오 없음/소진/--mute → clock += dt)
                     frames.peek().time ≤ clock + ¼프레임 인 것까지 꺼냄 (한 틱에 2장 이상 = 드롭 집계)
[render]             current 프레임이 바뀌었을 때만 glTexSubImage2D 3회 (460KB) 후 사각형 1개
```
- 비디오 시각 = 표시 순서 번호 / 29.97 (CFR). 첫 비디오/오디오 PTS 가 120개 모두 같아서 따로 오프셋 보정이 필요 없다.
- 종료 = 디코딩 끝 ∧ 프레임 큐 빔 ∧ 오디오 소진 ∧ 마지막 프레임 표시 시간 경과. 오디오가 더 긴 파일(A01MP05 +0.4초)은 마지막 프레임을 유지.
- OpenAL: 이미 컨텍스트가 있으면(게임 중 `OpenAlBackend`) 그 컨텍스트에 소스 하나만 추가, 없으면 장치를 직접 연다 (`--movie` 단독).
- 와이드: `MovieSource.open(name, rom, iso, widescreen=true)` → PSW 우선, PSW 는 16:9 로 표시. BONUS* 는 PSS 만 있으므로 4:3 으로 떨어진다.

## 4. 게임 연결 API (GameScene 담당자용, 추가만 하면 됨)
```java
// ScriptListener.movie(name, op, arg)  — op 0 = PlayMovie, 1 = MovieCtl, 2 = PreloadMovie
@Override public void movie(String name, int op, int arg) {
	if (op == 0) {
		MovieScene.play(engine, name, () -> { /* 스크립트에 종료 알림, 음악 재개 등 */ });
	}
}
// 오프닝: 새 게임으로 ATHN01A 첫 진입 시 (원작 sub_0021efd8)
MovieScene.play(engine, "Semo01", () -> {});
```
- `MovieScene.play(engine, name[, widescreen], onDone)` = 씬 스택에 push. 재생 중엔 아래 씬(GameScene)의 update/render 가 멈춘다. 끝나거나 건너뛰면 스스로 pop 하고 `onDone` 호출. 파일이 없으면 로그 후 즉시 `onDone`.
- `new MovieScene(player, popSelf, onDone).skippable(false)` — 건너뛰기 금지 등 직접 구성.
- PreloadMovie(op 2)/MovieCtl(op 1) 은 무시해도 된다: 여는 데 수 ms, 첫 프레임까지 ~0.1초.

## 5. 테스트 (`MovieTest`, ISO 필요, 없으면 건너뜀)
| 테스트 | 검증 |
|-------|------|
| `a01mp05DemuxDecode` | 640×480, 29.97, 246프레임 오류 0, 표시 순서 연속, SShd = PCM16/48k/2ch/0x200, 본문 1,653,760 B, 길이 8.21초/8.61초 |
| `playerHeadlessClock` | `--movie-start 5` 시작, 무음 벽시계로 끝까지 진행, 마지막 프레임 245, 5초 이전 프레임 없음 |
| `scriptNamesResolve` | 스크립트/실행 파일 이름 25종이 TOC 에 존재, SEMO01.PSW 존재, BONUS01.PSW 부재 |

## 6. 스크린샷 (엔진 실행, `work/video/`)
- `eng_semo01_8s.png` — 오프닝 8초, 4:3 필러박스, 구운 한글 자막 "더 이상 희망은 없다"
- `eng_a01mp01_psw_12s.png` — 히드라 컷신 PSW 12초, 16:9 전체 화면
- `frames/A01MP05_PSS_003.00.png` / `frames/A01MP05_PSW_003.00.png` — 같은 프레임의 4:3 / 아나모픽 비교 (도구 출력)

## 7. 남은 것
- GameScene 연결 (위 API 호출), 스크립트로 보내는 재생 종료 이벤트 번호 확인 (`mpegDone…` 핸들러 = 이벤트 7 추정).
- 옵션 메뉴의 와이드스크린 설정 → `widescreen` 인자 연결.
- 원작의 건너뛰기 조건 (`0x29D528` 표 3번째 값 0/1/2 가 건너뛰기 모드일 가능성), 페이드 인/아웃.
- 보너스 메뉴(FLP)에서 Bonus 영상 재생 연결.
