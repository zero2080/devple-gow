package devple.gow.engine.video;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET;
import static org.lwjgl.openal.ALC10.*;

/**
 * 동영상 오디오 출력 (OpenAL 스트리밍 소스 1개) + 재생 시계.
 * 이미 OpenAL 컨텍스트가 있으면(게임 중 {@code OpenAlBackend}) 그 컨텍스트에 소스만 하나 만들고,
 * 없으면(--movie 단독 실행) 장치를 직접 연다. 시계 = (끝난 버퍼 샘플 + AL_SAMPLE_OFFSET) / rate — A/V 동기 기준.
 */
final class MovieAudio implements AutoCloseable {

	private static final int BUFS = 8;
	private static final int FRAMES_PER_BUF = 2048;   // 48kHz 기준 ~43ms

	private final long device;
	private final long context;
	private final int source;
	private final int[] buffers = new int[BUFS];
	private final Deque<Integer> idle = new ArrayDeque<>();
	private final int rate;
	private final int channels;
	private final short[] pending;
	private final ShortBuffer upload;
	private final Deque<Integer> queuedFrames = new ArrayDeque<>();
	private int pendingLen;
	private long playedFrames;
	private boolean started;
	private boolean paused;

	/** 장치를 열 수 없으면 IllegalStateException. */
	MovieAudio(int rate, int channels, float gain) {
		this.rate = rate;
		this.channels = channels;
		if (alcGetCurrentContext() == 0) {
			device = alcOpenDevice((CharSequence) null);
			if (device == 0) {
				throw new IllegalStateException("오디오 장치 열기 실패");
			}
			ALCCapabilities caps = ALC.createCapabilities(device);
			context = alcCreateContext(device, (int[]) null);
			alcMakeContextCurrent(context);
			AL.createCapabilities(caps);
		} else {
			device = 0;
			context = 0;
		}
		source = alGenSources();
		alSourcei(source, AL_SOURCE_RELATIVE, AL_TRUE);
		alSource3f(source, AL_POSITION, 0, 0, 0);
		alSourcef(source, AL_ROLLOFF_FACTOR, 0);
		alSourcef(source, AL_GAIN, gain);
		for (int i = 0; i < BUFS; i++) {
			buffers[i] = alGenBuffers();
			idle.add(buffers[i]);
		}
		pending = new short[FRAMES_PER_BUF * channels];
		upload = MemoryUtil.memAllocShort(pending.length);
	}

	/** 큐에 넣을 자리가 있는지 (버퍼 단위). */
	boolean wantsData() {
		reclaim();
		return !idle.isEmpty();
	}

	/** PCM(채널 인터리브) 추가. 반환 = 받아들인 샘플 수 (빈 버퍼가 없으면 일부만). */
	int write(short[] pcm, int off, int len) {
		int used = 0;
		while (used < len) {
			if (pendingLen == pending.length && !submit()) {
				break;   // 빈 AL 버퍼 없음 — 나머지는 다음 틱에
			}
			int n = Math.min(len - used, pending.length - pendingLen);
			System.arraycopy(pcm, off + used, pending, pendingLen, n);
			pendingLen += n;
			used += n;
		}
		if (pendingLen == pending.length) {
			submit();
		}
		return used;
	}

	/** 남은 조각까지 내보낸다 (스트림 끝). */
	void flush() {
		if (pendingLen > 0) {
			submit();
		}
	}

	private boolean submit() {
		reclaim();
		if (idle.isEmpty()) {
			return false;
		}
		int b = idle.poll();
		upload.clear();
		upload.put(pending, 0, pendingLen).flip();
		alBufferData(b, channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16, upload, rate);
		alSourceQueueBuffers(source, b);
		queuedFrames.add(pendingLen / channels);
		pendingLen = 0;
		return true;
	}

	private void reclaim() {
		int done = alGetSourcei(source, AL_BUFFERS_PROCESSED);
		for (int i = 0; i < done; i++) {
			idle.add(alSourceUnqueueBuffers(source));
			Integer f = queuedFrames.poll();
			playedFrames += f == null ? 0 : f;
		}
	}

	/** 큐에 쌓인 샘플이 minSeconds 이상이면 재생 시작, 재생 중 언더런이면 다시 시작. */
	void pump(double minSeconds) {
		reclaim();
		if (paused) {
			return;
		}
		int state = alGetSourcei(source, AL_SOURCE_STATE);
		long queued = queuedFrames.stream().mapToLong(Integer::longValue).sum();
		if (state != AL_PLAYING && queued > 0 && (started || queued >= minSeconds * rate)) {
			alSourcePlay(source);
			started = true;
		}
	}

	boolean started() {
		return started;
	}

	/** 큐가 비었고 소스가 멈췄으면 true. */
	boolean drained() {
		reclaim();
		return queuedFrames.isEmpty() && pendingLen == 0;
	}

	void pause(boolean p) {
		paused = p;
		if (p) {
			alSourcePause(source);
		} else if (started) {
			alSourcePlay(source);
		}
	}

	/** 재생된 시간 (초). */
	double clock() {
		long f = playedFrames;
		if (!queuedFrames.isEmpty()) {
			f += Math.min(alGetSourcei(source, AL_SAMPLE_OFFSET), queuedFrames.peek());
		}
		return f / (double) rate;
	}

	@Override
	public void close() {
		alSourceStop(source);
		alSourcei(source, AL_BUFFER, 0);
		alDeleteSources(source);
		for (int b : buffers) {
			alDeleteBuffers(b);
		}
		MemoryUtil.memFree(upload);
		if (context != 0) {
			alcMakeContextCurrent(0);
			alcDestroyContext(context);
			alcCloseDevice(device);
		}
	}
}
