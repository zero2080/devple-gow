package devple.gow.engine.audio;

import devple.gow.tools.audio.PcmSound;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;

/** OpenAL 기반 효과음/음악 재생. 디코딩은 tools 의 AudioDecoder 를 재사용한다. */
public final class AudioSystem implements AutoCloseable {

	private static final int VOICES = 32;

	private final long device;
	private final long context;
	private final Map<String, Integer> buffers = new HashMap<>();
	private final List<Integer> sources = new ArrayList<>();
	private int next;

	public AudioSystem() {
		device = alcOpenDevice((CharSequence) null);
		if (device == 0) {
			throw new IllegalStateException("오디오 장치 열기 실패");
		}
		ALCCapabilities caps = ALC.createCapabilities(device);
		context = alcCreateContext(device, (int[]) null);
		alcMakeContextCurrent(context);
		AL.createCapabilities(caps);
		for (int i = 0; i < VOICES; i++) {
			sources.add(alGenSources());
		}
	}

	public int buffer(PcmSound s) {
		return buffers.computeIfAbsent(s.name(), k -> {
			int b = alGenBuffers();
			ShortBuffer sb = MemoryUtil.memAllocShort(s.samples().length).put(s.samples()).flip();
			alBufferData(b, s.channels() == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16, sb, s.sampleRate());
			MemoryUtil.memFree(sb);
			return b;
		});
	}

	/** 라운드로빈 보이스로 재생. 반환값 = 소스 id. */
	public int play(PcmSound s, float gain, boolean loop) {
		int src = sources.get(next++ % VOICES);
		alSourceStop(src);
		alSourcei(src, AL_BUFFER, buffer(s));
		alSourcef(src, AL_GAIN, gain);
		alSourcei(src, AL_LOOPING, loop ? AL_TRUE : AL_FALSE);
		alSourcePlay(src);
		return src;
	}

	public void stop(int source) {
		alSourceStop(source);
	}

	@Override
	public void close() {
		sources.forEach(s -> alDeleteSources(s));
		buffers.values().forEach(b -> alDeleteBuffers(b));
		alcDestroyContext(context);
		alcCloseDevice(device);
	}
}
