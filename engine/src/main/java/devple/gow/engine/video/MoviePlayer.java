package devple.gow.engine.video;

import devple.gow.tools.video.MovieSource;
import devple.gow.tools.video.Mpeg2Video;
import devple.gow.tools.video.PssDemuxer;
import devple.gow.tools.video.SsAudio;
import devple.gow.tools.video.VideoFrame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 컷신 스트리밍 재생기 (33 문서). GL 과 무관한 부분: 디코딩 스레드 + 오디오 + 시계 + 표시 프레임 선택.
 * <pre>
 * 디코딩 스레드: PssDemuxer → Mpeg2Video(JCodec) → frames 큐(24장 ≈ 0.8초, 꽉 차면 대기)
 *                         → SsAudio(PCM16 디인터리브) → pcm 큐
 * 메인 스레드 update(): pcm → OpenAL 스트림, 시계 = 오디오 재생 위치 (오디오 없으면 벽시계),
 *                       시계를 지난 프레임까지 꺼내 current 로 둔다 (늦으면 건너뜀 = 프레임 드롭).
 * </pre>
 * 비디오 첫 PTS 와 오디오 첫 PTS 는 120개 전부 같다(18 문서 §1) — 그래서 시계 0 = 프레임 0 = 샘플 0.
 */
public final class MoviePlayer implements AutoCloseable {

	private static final int FRAME_QUEUE = 24;
	private static final double PREROLL = 0.12;

	private final MovieSource src;
	private final boolean widescreen;
	private final double startAt;
	private final float gain;
	private final boolean mute;
	private final BlockingQueue<VideoFrame> frames = new ArrayBlockingQueue<>(FRAME_QUEUE);
	private final BlockingQueue<short[]> pcm = new LinkedBlockingQueue<>();
	private final Thread thread;
	private volatile boolean decodeDone;
	private volatile boolean closed;
	private volatile int audioRate;
	private volatile int audioChannels;
	private volatile double fps = 30000 / 1001.0;
	private volatile Throwable error;

	private MovieAudio audio;
	private boolean audioResolved;
	private short[] carry;
	private int carryOff;
	private double clock;
	private boolean running;
	private VideoFrame current;
	private boolean skipped;
	private int dropped;
	private long started = System.nanoTime();

	/**
	 * @param widescreen PSW(16:9 아나모픽) 이면 true — 표시 비율만 다르다
	 * @param startAt    시작 시각(초). 앞부분은 디코딩만 하고 버린다 (검증·디버그용)
	 * @param mute       오디오 장치를 쓰지 않음 (벽시계 재생)
	 */
	public MoviePlayer(MovieSource src, boolean widescreen, double startAt, float gain, boolean mute) {
		this.src = src;
		this.widescreen = widescreen;
		this.startAt = Math.max(0, startAt);
		this.gain = gain;
		this.mute = mute;
		this.clock = this.startAt;
		this.thread = Thread.ofPlatform().daemon().name("movie-" + src.name()).start(this::decodeLoop);
	}

	/** 이름(스크립트 PlayMovie 인자, 대소문자 무관)으로 연다. 없으면 null. */
	public static MoviePlayer open(String name, boolean widescreen) throws IOException {
		Path rom = devple.gow.tools.content.Content.romDir();
		MovieSource s = MovieSource.open(name, rom, isoPath(rom), widescreen);
		if (s == null) {
			return null;
		}
		return new MoviePlayer(s, s.name().endsWith(".PSW"), 0, 1f, false);
	}

	/** ISO 위치: 설정 iso (-Dgow.iso) → 작업 디렉터리의 *.iso → rom 실제 경로의 부모의 *.iso (49 문서 §2). */
	public static Path isoPath(Path rom) {
		return devple.gow.tools.content.Content.isoPath(rom);
	}

	private void decodeLoop() {
		try {
			PssDemuxer d = new PssDemuxer(src);
			SsAudio ss = new SsAudio();
			long[] skipSamples = {-1};
			Mpeg2Video video = new Mpeg2Video(f -> {
				if (f.time() + 1e-6 < startAt) {
					return;
				}
				try {
					frames.put(f);
				} catch (InterruptedException e) {
					throw new CancelledException();
				}
			});
			PssDemuxer.Packet p;
			while (!closed && (p = d.next()) != null) {
				if (p.stream == PssDemuxer.VIDEO) {
					video.feed(p.data, 0, p.len);
					fps = video.fps();
				} else if (p.sub == 0xFF) {
					ss.feed(p.data, 0, p.len);
					if (!ss.ready()) {
						continue;
					}
					if (skipSamples[0] < 0) {
						skipSamples[0] = Math.round(startAt * ss.rate) * ss.channels;
						audioChannels = ss.channels;
						audioRate = ss.rate;   // volatile 쓰기 순서: rate 가 마지막 (메인 스레드는 rate 로 판단)
					}
					int n = ss.available();
					if (n > 0) {
						short[] chunk = new short[n];
						ss.drain(chunk, 0, n);
						int from = (int) Math.min(n, skipSamples[0]);
						skipSamples[0] -= from;
						if (from < n) {
							pcm.add(from == 0 ? chunk : java.util.Arrays.copyOfRange(chunk, from, n));
						}
					}
				}
			}
			if (!closed) {
				video.flush();
			}
		} catch (CancelledException ignored) {
			// close()
		} catch (Throwable t) {
			error = t;
		} finally {
			decodeDone = true;
		}
	}

	private static final class CancelledException extends RuntimeException {
		CancelledException() {
			super(null, null, false, false);
		}
	}

	/** 메인 스레드, 로직 틱마다. 오디오 공급·시계 진행·표시 프레임 선택. */
	public void update(float dt) {
		if (!audioResolved) {
			if (audioRate > 0) {
				audioResolved = true;
				if (!mute) {
					try {
						audio = new MovieAudio(audioRate, audioChannels, gain);
					} catch (RuntimeException e) {
						System.err.println("[movie] 오디오 출력 없음: " + e.getMessage());
					}
				}
			} else if (decodeDone || frames.remainingCapacity() == 0) {
				audioResolved = true;   // 오디오 트랙 없음 (또는 비디오가 먼저 꽉 참) → 벽시계
			}
		}
		boolean audioClock = false;
		if (audio != null) {
			while (audio.wantsData()) {
				if (carry == null) {
					carry = pcm.poll();
					carryOff = 0;
					if (carry == null) {
						break;
					}
				}
				int w = audio.write(carry, carryOff, carry.length - carryOff);
				carryOff += w;
				if (carryOff >= carry.length) {
					carry = null;
				} else if (w == 0) {
					break;
				}
			}
			if (decodeDone && carry == null && pcm.isEmpty()) {
				audio.flush();
			}
			audio.pump(decodeDone ? 0 : PREROLL);
			audioClock = audio.started() && !audio.drained();
		}
		if (audioClock) {
			clock = startAt + audio.clock();
			running = true;
		} else if (audioResolved && (running || !frames.isEmpty() || decodeDone)) {
			clock += dt;
			running = true;
		}
		// 시계를 지난 프레임까지 꺼낸다
		VideoFrame f;
		int pulled = 0;
		while ((f = frames.peek()) != null && f.time() <= clock + 0.25 / fps) {
			current = frames.poll();
			pulled++;
		}
		dropped += Math.max(0, pulled - 1);   // 한 틱에 2장 이상 = 늦어서 건너뛴 프레임
	}

	/** 지금 보여줄 프레임 (아직 없으면 null). */
	public VideoFrame frame() {
		return current;
	}

	/** 재생 위치 (초, 파일 기준). */
	public double time() {
		return clock;
	}

	/** 표시 가로세로비: PSS 4:3, PSW 16:9 (아나모픽 640×480 을 늘려 표시). */
	public float displayAspect() {
		return widescreen ? 16f / 9f : 4f / 3f;
	}

	public String name() {
		return src.name();
	}

	public void skip() {
		skipped = true;
	}

	public boolean finished() {
		if (skipped || error != null) {
			return true;
		}
		if (!decodeDone || !frames.isEmpty() || audio != null && !audio.drained()) {
			return false;
		}
		return current == null || clock >= current.time() + 1 / fps;
	}

	public Throwable error() {
		return error;
	}

	public int dropped() {
		return dropped;
	}

	/** 처음 update 부터의 경과 (초) — 측정용. */
	public double wallSeconds() {
		return (System.nanoTime() - started) / 1e9;
	}

	@Override
	public void close() {
		closed = true;
		thread.interrupt();
		try {
			thread.join(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (audio != null) {
			audio.close();
			audio = null;
		}
		try {
			src.close();
		} catch (IOException ignored) {
			// 읽기 전용
		}
	}
}
