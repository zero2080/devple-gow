package devple.gow.engine.audio;

import devple.gow.tools.audio.AdpcmClip;
import org.joml.Vector3fc;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.system.MemoryUtil;

import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.AL_LINEAR_DISTANCE_CLAMPED;
import static org.lwjgl.openal.ALC10.*;

/**
 * OpenAL 백엔드. 소스 풀(48) 을 돌려 쓰고, 클립 버퍼는 클립 단위로 캐시한다.
 * 루프 클립: loopStart &gt; 0 이면 [인트로, 루프 몸통] 두 버퍼를 큐에 넣고, 인트로가 끝나면 빼고 AL_LOOPING 을 켠다.
 * 스트림: 0.25초 버퍼 4개를 큐에 돌려 쓴다.
 */
public final class OpenAlBackend implements AudioBackend {

	private static final int SOURCES = 48;
	private static final int STREAM_BUFS = 4;
	/** SPU2 깊이(0..1) → EFX 슬롯 이득. 원작 깊이 3500/0x7FFF ≈ 0.11 이 EFX 에선 너무 작게 들려 키운다 [추정: 청감 보정]. */
	private static final float REVERB_SCALE = 3f;

	private static final class Slot {
		int id;
		int source;
		PcmStream stream;
		int[] streamBufs;
		short[] scratch;
		boolean introPending;
		boolean drained;
	}

	private final long device;
	private final long context;
	private final Deque<Integer> free = new ArrayDeque<>();
	private final Map<Integer, Slot> slots = new LinkedHashMap<>();
	private final Map<AdpcmClip, int[]> clipBuffers = new IdentityHashMap<>();
	private int next = 1;
	/** EFX 리버브 (장치가 ALC_EXT_EFX 를 지원할 때만, 아니면 0). */
	private int reverbEffect;
	private int reverbSlot;

	/** 장치를 열 수 없으면 IllegalStateException. */
	public OpenAlBackend() {
		device = alcOpenDevice((CharSequence) null);
		if (device == 0) {
			throw new IllegalStateException("오디오 장치 열기 실패");
		}
		ALCCapabilities caps = ALC.createCapabilities(device);
		context = alcCreateContext(device, (int[]) null);
		alcMakeContextCurrent(context);
		AL.createCapabilities(caps);
		alDistanceModel(AL_LINEAR_DISTANCE_CLAMPED);
		for (int i = 0; i < SOURCES; i++) {
			free.add(alGenSources());
		}
		if (caps.ALC_EXT_EFX) {
			// SPU2 리버브 대역: EFX 표준 리버브 (원작 리버브 모드(방 크기·감쇠)는 미확인 — 기본 generic 값), 젖은 소리 양만 깊이로 조절
			reverbEffect = EXTEfx.alGenEffects();
			EXTEfx.alEffecti(reverbEffect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
			reverbSlot = EXTEfx.alGenAuxiliaryEffectSlots();
			EXTEfx.alAuxiliaryEffectSloti(reverbSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, reverbEffect);
			EXTEfx.alAuxiliaryEffectSlotf(reverbSlot, EXTEfx.AL_EFFECTSLOT_GAIN, 0);
		}
	}

	private int[] buffers(AdpcmClip c) {
		return clipBuffers.computeIfAbsent(c, k -> {
			short[] pcm = k.pcm().length > 0 ? k.pcm() : new short[2];
			if (!k.looped() || k.loopStart() <= 0 || k.loopStart() >= pcm.length) {
				return new int[]{0, upload(pcm, 1, k.rate())};
			}
			return new int[]{upload(Arrays.copyOfRange(pcm, 0, k.loopStart()), 1, k.rate()),
					upload(Arrays.copyOfRange(pcm, k.loopStart(), pcm.length), 1, k.rate())};
		});
	}

	private static int upload(short[] pcm, int ch, int rate) {
		int b = alGenBuffers();
		ShortBuffer sb = MemoryUtil.memAllocShort(pcm.length).put(pcm).flip();
		alBufferData(b, ch == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16, sb, rate);
		MemoryUtil.memFree(sb);
		return b;
	}

	private Slot alloc() {
		if (free.isEmpty()) {
			// 가장 오래된 보이스를 뺏는다
			Iterator<Slot> it = slots.values().iterator();
			Slot old = it.next();
			it.remove();
			release(old);
		}
		Slot s = new Slot();
		s.id = next++;
		s.source = free.poll();
		alSourcei(s.source, AL_BUFFER, 0);
		slots.put(s.id, s);
		return s;
	}

	private void release(Slot s) {
		alSourceStop(s.source);
		alSourcei(s.source, AL_BUFFER, 0);
		if (s.streamBufs != null) {
			for (int b : s.streamBufs) {
				alDeleteBuffers(b);
			}
			s.stream.close();
		}
		alSourcei(s.source, AL_LOOPING, AL_FALSE);
		free.add(s.source);
	}

	private void apply(int src, Voice v) {
		if (reverbSlot != 0) {
			org.lwjgl.openal.AL11.alSource3i(src, EXTEfx.AL_AUXILIARY_SEND_FILTER, v.dry ? EXTEfx.AL_EFFECTSLOT_NULL : reverbSlot, 0, EXTEfx.AL_FILTER_NULL);
		}
		alSourcef(src, AL_GAIN, Math.max(0, v.gain));
		alSourcef(src, AL_PITCH, Math.max(0.05f, v.pitch));
		if (v.pos != null) {
			alSourcei(src, AL_SOURCE_RELATIVE, AL_FALSE);
			alSource3f(src, AL_POSITION, v.pos.x, v.pos.y, v.pos.z);
			alSourcef(src, AL_REFERENCE_DISTANCE, v.near);
			alSourcef(src, AL_MAX_DISTANCE, Math.max(v.near + 0.01f, v.far));
			alSourcef(src, AL_ROLLOFF_FACTOR, 1);
		} else {
			double a = Math.toRadians(v.pan);
			alSourcei(src, AL_SOURCE_RELATIVE, AL_TRUE);
			alSource3f(src, AL_POSITION, (float) Math.sin(a) * 0.5f, 0, (float) -Math.cos(a) * 0.5f);
			alSourcef(src, AL_ROLLOFF_FACTOR, 0);
		}
	}

	@Override
	public int play(AdpcmClip clip, Voice v) {
		Slot s = alloc();
		int[] b = buffers(clip);
		apply(s.source, v);
		if (b[0] != 0) {
			alSourceQueueBuffers(s.source, new int[]{b[0], b[1]});
			alSourcei(s.source, AL_LOOPING, AL_FALSE);
			s.introPending = true;
		} else {
			alSourcei(s.source, AL_BUFFER, b[1]);
			alSourcei(s.source, AL_LOOPING, clip.looped() ? AL_TRUE : AL_FALSE);
		}
		alSourcePlay(s.source);
		return s.id;
	}

	@Override
	public int stream(PcmStream st, Voice v) {
		Slot s = alloc();
		s.stream = st;
		s.scratch = new short[Math.max(st.channels(), st.sampleRate() / 4 * st.channels())];
		s.streamBufs = new int[STREAM_BUFS];
		apply(s.source, v);
		alSourcei(s.source, AL_LOOPING, AL_FALSE);
		int queued = 0;
		for (int i = 0; i < STREAM_BUFS; i++) {
			s.streamBufs[i] = alGenBuffers();
			if (fill(s, s.streamBufs[i])) {
				alSourceQueueBuffers(s.source, s.streamBufs[i]);
				queued++;
			}
		}
		if (queued > 0) {
			alSourcePlay(s.source);
		}
		return s.id;
	}

	private boolean fill(Slot s, int buf) {
		int n = s.stream.read(s.scratch);
		if (n <= 0) {
			s.drained = true;
			return false;
		}
		short[] pcm = n == s.scratch.length ? s.scratch : Arrays.copyOf(s.scratch, n);
		ShortBuffer sb = MemoryUtil.memAllocShort(pcm.length).put(pcm).flip();
		alBufferData(buf, s.stream.channels() == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16, sb, s.stream.sampleRate());
		MemoryUtil.memFree(sb);
		return true;
	}

	@Override
	public void update(int id, Voice v) {
		Slot s = slots.get(id);
		if (s != null) {
			apply(s.source, v);
		}
	}

	@Override
	public boolean playing(int id) {
		return slots.containsKey(id);
	}

	@Override
	public void stop(int id) {
		Slot s = slots.remove(id);
		if (s != null) {
			release(s);
		}
	}

	@Override
	public void listener(Vector3fc pos, Vector3fc fwd, Vector3fc up) {
		alListener3f(AL_POSITION, pos.x(), pos.y(), pos.z());
		alListenerfv(AL_ORIENTATION, new float[]{fwd.x(), fwd.y(), fwd.z(), up.x(), up.y(), up.z()});
	}

	@Override
	public void reverb(float depth) {
		if (reverbSlot != 0) {
			EXTEfx.alAuxiliaryEffectSlotf(reverbSlot, EXTEfx.AL_EFFECTSLOT_GAIN, Math.max(0, Math.min(1, depth * REVERB_SCALE)));
		}
	}

	@Override
	public void tick(float dt) {
		Iterator<Slot> it = slots.values().iterator();
		while (it.hasNext()) {
			Slot s = it.next();
			int processed = alGetSourcei(s.source, AL_BUFFERS_PROCESSED);
			if (s.introPending && processed > 0) {
				alSourceUnqueueBuffers(s.source);
				alSourcei(s.source, AL_LOOPING, AL_TRUE);
				s.introPending = false;
			}
			if (s.stream != null) {
				for (int i = 0; i < processed; i++) {
					int b = alSourceUnqueueBuffers(s.source);
					if (!s.drained && fill(s, b)) {
						alSourceQueueBuffers(s.source, b);
					}
				}
				if (alGetSourcei(s.source, AL_SOURCE_STATE) != AL_PLAYING && alGetSourcei(s.source, AL_BUFFERS_QUEUED) > 0) {
					alSourcePlay(s.source); // 언더런 복구
				}
			}
			if (alGetSourcei(s.source, AL_SOURCE_STATE) == AL_STOPPED && !s.introPending) {
				it.remove();
				release(s);
			}
		}
	}

	@Override
	public void close() {
		for (Slot s : slots.values()) {
			release(s);
		}
		slots.clear();
		free.forEach(src -> alDeleteSources((int) src));
		clipBuffers.values().forEach(b -> {
			if (b[0] != 0) {
				alDeleteBuffers(b[0]);
			}
			alDeleteBuffers(b[1]);
		});
		if (reverbSlot != 0) {
			EXTEfx.alDeleteAuxiliaryEffectSlots(reverbSlot);
			EXTEfx.alDeleteEffects(reverbEffect);
		}
		alcMakeContextCurrent(0);
		alcDestroyContext(context);
		alcCloseDevice(device);
	}
}
