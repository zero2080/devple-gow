package devple.gow.engine.audio;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.SoundOverrides;
import devple.gow.tools.audio.WavClip;
import devple.gow.tools.audio.AdpcmClip;
import devple.gow.tools.audio.SoundBank;
import devple.gow.tools.audio.SoundEmitter;
import devple.gow.tools.audio.VagPack;
import devple.gow.tools.audio.WadSounds;
import devple.gow.tools.tweak.TweakFile;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;

/**
 * 효과음·음악 런타임 (29 문서). WAD 의 SND 뱅크(SBlk)·VAG 팩·SEM 이미터를 등록하고, 이름/TWK 해시로 소리를 재생한다.
 * <ul>
 *   <li>{@link #play(String, Vector3fc, int...)} — SND_* 이름 (레지스터 0..3 초기값 선택, 예: 발소리 표면)</li>
 *   <li>{@link #play(int, Vector3fc)} — 전투 이벤트 SOUND 해시 ({@code TweakFile.hash(name)})</li>
 *   <li>{@link #music(String, float)} / {@link #stopMusic(float)} — 스크립트 MusicGroupStart/Stop (SND_M_* → DPMS VPK 스트림)</li>
 *   <li>{@link #soundGroup(String, int, Vector3fc)} — ESC SoundController (그룹 → SEM 이미터 → 슬롯 Op)</li>
 * </ul>
 * {@link #update(float)} 를 매 프레임 호출 (240Hz 그레인 틱 + 백엔드 스트림 보충). 장치 없이도 동작한다 ({@link #headless}).
 */
public final class SoundManager implements AutoCloseable {

	public static final int TICK_HZ = 240;

	/** 989snd 볼륨 그룹 (exe 0x29D078 이름 표). */
	public static final String[] GROUPS = {"SFX", "Music", "Mime Dialogue", "Ambient Streams", "IOP Streams", "Footsteps",
			"Menu Sounds", "Interactives", "MPEG", "Music UW no mod", "SFX UW pitch", "SFX UW no mod", "Ambient UW nomod",
			"IOP UW no mod", "Dry Effects", "Base Reverb"};

	/** 뱅크 + 소리 번호. */
	public record Ref(SoundBank bank, int index) {
		public SoundBank.Sound sound() {
			return bank.sounds().get(index);
		}
	}

	private record PackKey(VagPack pack, String name) {
	}

	/** VagPack 항목 → 디코드한 클립 (팩이 내려가면 버린다). */
	private final Map<PackKey, AdpcmClip> packClips = new HashMap<>();

	private record ClipKey(SoundBank bank, int off, int size, int rate) {
	}

	final AudioBackend backend;
	private final Path romDir;
	final Random rng;
	final byte[] globalRegs = new byte[32];
	final Map<Long, Integer> picks = new HashMap<>();
	private final Map<String, WadSounds> wads = new LinkedHashMap<>();
	private final Map<String, Ref> byName = new HashMap<>();
	private final Map<Integer, String> byHash = new HashMap<>();
	private final Map<String, VagPack> packEntries = new HashMap<>();
	private final Map<ClipKey, AdpcmClip> clips = new HashMap<>();
	private final Map<String, AdpcmClip> vagFiles = new HashMap<>();
	private final Map<Integer, SoundInstance> instances = new LinkedHashMap<>();
	private final Map<String, int[]> groupHandles = new HashMap<>();
	private final float[] groupVolume = new float[16];
	private final Vector3f listener = new Vector3f();
	private float master = 1;
	private float tickAcc;
	private int nextHandle = 1;
	private int musicHandle;
	private String musicName;
	/** 리버브 프리셋 (TWK_Sound /System/Sound/: 0 = 이미터 사용자 값, 1..7 = Outdoor S/M/L, Indoor S/M/L, Underwater) + Base Reverb. */
	private final int[] reverbPresets = {0, 2728, 3432, 4792, 2080, 2608, 3472, 3752};
	private float reverbBase = 3500;
	/** 리버브에 기여하는 재생 중 이미터 (soundGroup 이 SEM flags bit0 이미터를 켤 때 등록). */
	private final List<ReverbSource> reverbSources = new ArrayList<>();
	private float reverbDepth = Float.NaN;

	private record ReverbSource(int handle, int depth, Vector3f pos, float radius) {
	}

	private BiConsumer<Integer, Integer> rumble = (motor, value) -> {
	};

	/**
	 * 카메라 흔들림 요청 (DPMS cmd 0x16 모터 1). 원작은 IOP 가 상태 버퍼 0x29CAF0 (0x280 바이트, 0x1389D0 이 IOP 에 넘김) +0x25C 에 {id, 값, 템플릿, 재생 중} 을 쓰고
	 * 매 프레임 작업 0x228C70 이 id 가 바뀌면 0x1F09C8(템플릿) 으로 흔들림을 시작, 배율 +0x958 = 값/1024 (0x1F0A28) — 모터 0 (+0x24C) 은 패드 진동 (값·100/1024 %).
	 * serial = 새 요청마다 1 증가, scale = 그 소리의 이번 들림 크기 (핸들러·소리 볼륨 × 거리 감쇠 — 백엔드와 같은 선형 [near, far]) [추정: IOP 가 보내는 값 = 보이스 볼륨].
	 */
	public record ShakeRequest(int serial, int template, float scale) {
	}

	private SoundInstance shakeInst;
	private int shakeSerial, shakeTemplate;

	/** 마지막 흔들림 요청 (없으면 null). SEM 이미터 범위가 재생 뒤에 정해지므로 크기는 물을 때 계산한다. */
	public ShakeRequest shakeRequest() {
		if (shakeInst == null) {
			return null;
		}
		SoundInstance i = shakeInst;
		float g = i.vol / 127f * i.sound.vol() / 127f;
		if (i.pos != null) {
			float d = i.pos.distance(listener);
			g *= d <= i.near ? 1 : d >= i.far ? 0 : 1 - (d - i.near) / Math.max(1e-3f, i.far - i.near);
		}
		return new ShakeRequest(shakeSerial, shakeTemplate, Math.max(0, Math.min(1, g)));
	}

	public SoundManager(AudioBackend backend, Path romDir, long seed) {
		this.backend = backend;
		this.romDir = romDir;
		this.rng = new Random(seed);
		Arrays.fill(groupVolume, 1);
	}

	/** 장치 없는 관리자 (테스트·서버). */
	public static SoundManager headless(Path romDir) {
		return new SoundManager(new NullAudioBackend(), romDir, 1);
	}

	/** OpenAL 장치로 열고, 실패하면 헤드리스로 대체. */
	public static SoundManager open(Path romDir) {
		try {
			return new SoundManager(new OpenAlBackend(), romDir, System.nanoTime());
		} catch (Throwable t) {
			System.err.println("오디오 장치 없음 → 무음: " + t.getMessage());
			return headless(romDir);
		}
	}

	public AudioBackend backend() {
		return backend;
	}

	// ---- 뱅크 등록 ----

	/** WAD 의 뱅크/팩/이미터 등록 (R_PERMK → 영웅 → 레벨 → 적 순서, 같은 이름은 나중 것이 우선). */
	public WadSounds load(WadFile wad) {
		return register(WadSounds.load(wad));
	}

	/** 미리 해석한 뱅크 등록 ({@link #load} 와 같은 결과 — 해석만 적재 스레드에서, GameScene 이음 43 §27). */
	public WadSounds register(WadSounds ws) {
		wads.put(ws.wad(), ws);
		index(ws);
		return ws;
	}

	/**
	 * 그 WAD 뱅크로 울리던 소리를 끄고 내린다 — 다른 소리와 음악 (스트림, MusicGroupStart 가 바꿀 때까지) 은 그대로. 이음으로 씬이 이어지는 동안 두 슬롯에서
	 * 내려간 레벨 뱅크 (GameScene, 43 §27) — 원작 LoadWad 는 배경 적재라 음악이 끊기지 않는다. 씬을 떠날 때는 {@link #stopAll} 뒤 {@link #unload}.
	 */
	public void unloadStopping(String wadName) {
		WadSounds ws = wads.get(wadName);
		if (ws == null) {
			return;
		}
		java.util.Set<SoundBank> banks = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		banks.addAll(ws.banks());
		for (java.util.Iterator<SoundInstance> it = instances.values().iterator(); it.hasNext(); ) {
			SoundInstance in = it.next();
			if (banks.contains(in.bank) && !musicTree(in)) {
				in.kill();
				it.remove();
			}
		}
		unload(wadName);
	}

	private static boolean musicTree(SoundInstance in) {
		for (SoundInstance s = in; s != null; s = s.parent) {
			if (s.music) {
				return true;
			}
		}
		return false;
	}

	public void unload(String wadName) {
		if (wads.remove(wadName) != null) {
			byName.clear();
			byHash.clear();
			packEntries.clear();
			clips.entrySet().removeIf(e -> {
				if (wads.values().stream().anyMatch(w -> w.banks().contains(e.getKey().bank()))) {
					return false;
				}
				if (e.getValue() != null) {
					backend.release(e.getValue()); // 장치 버퍼도 (48 문서 §4)
				}
				return true;
			});
			wads.values().forEach(this::index);
			java.util.Set<VagPack> live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
			wads.values().forEach(w -> live.addAll(w.packs()));
			packClips.entrySet().removeIf(e -> {
				if (live.contains(e.getKey().pack())) {
					return false;
				}
				if (e.getValue() != null) {
					backend.release(e.getValue());
				}
				return true;
			});
		}
	}

	private void index(WadSounds ws) {
		for (SoundBank b : ws.banks()) {
			for (Map.Entry<String, Integer> e : b.names().entrySet()) {
				if (e.getValue() >= 0 && e.getValue() < b.sounds().size()) {
					byName.put(e.getKey(), new Ref(b, e.getValue()));
					byHash.put(TweakFile.hash(e.getKey()), e.getKey());
					byHash.putIfAbsent(TweakFile.hashUpper(e.getKey()), e.getKey());
				}
			}
		}
		for (VagPack p : ws.packs()) {
			for (String e : p.entries().keySet()) {
				packEntries.put(e, p);
			}
		}
	}

	public boolean has(String sndName) {
		return byName.containsKey(sndName);
	}

	public Ref find(String sndName) {
		return byName.get(sndName);
	}

	/** TWK 해시 → 이름 (없으면 null). */
	public String nameOf(int hash) {
		return byHash.get(hash);
	}

	public Map<String, WadSounds> loaded() {
		return wads;
	}

	// ---- 재생 ----

	/** 이름으로 재생. pos == null 이면 2D. regs = 로컬 레지스터 0..3 초기값. 반환 = 핸들 (0 = 없는 이름). */
	public int play(String sndName, Vector3fc pos, int... regs) {
		Ref r = byName.get(sndName);
		if (r == null) {
			return 0;
		}
		return start(r.bank(), r.sound(), pos, 127, regs).handle;
	}

	public int play(String sndName) {
		return play(sndName, null);
	}

	/** 전투 이벤트 SOUND(3)/SCR_SOUND(40) 해시로 재생. */
	public int play(int hash, Vector3fc pos) {
		String n = byHash.get(hash);
		return n != null ? play(n, pos) : 0;
	}

	private SoundInstance start(SoundBank bank, SoundBank.Sound s, Vector3fc pos, int vol, int[] regs) {
		if (s.instanceLimit() > 0) {
			// 인스턴스 제한: 같은 소리가 제한 이상이면 가장 오래된 것 정지
			List<SoundInstance> same = instances.values().stream()
					.filter(i -> i.bank == bank && i.sound.index() == s.index() && !i.killed).toList();
			for (int k = 0; k <= same.size() - s.instanceLimit(); k++) {
				same.get(k).kill();
			}
		}
		SoundInstance in = new SoundInstance(this, nextHandle++, bank, s, null);
		in.vol = vol;
		in.pos = pos != null ? new Vector3f(pos) : null;
		for (int i = 0; regs != null && i < Math.min(4, regs.length); i++) {
			in.regs[i] = (byte) regs[i];
		}
		instances.put(in.handle, in);
		// 첫 그레인 대기가 0 이면 즉시 실행 (원작은 다음 틱이지만 체감 차이 없음)
		if (!override(in, s.name())) {
			in.tick();
		}
		return in;
	}

	SoundInstance child(SoundInstance parent, SoundBank.Sound s, int vol) {
		SoundInstance c = new SoundInstance(this, 0, parent.bank, s, parent);
		c.vol = vol;
		c.pos = parent.pos;
		c.near = parent.near;
		c.far = parent.far;
		c.music = parent.music;
		System.arraycopy(parent.regs, 0, c.regs, 0, 4);
		if (!override(c, s.name())) {
			c.tick();
		}
		return c;
	}

	public void stop(int handle) {
		SoundInstance i = instances.get(handle);
		if (i != null) {
			i.stop();
		}
	}

	public void kill(int handle) {
		SoundInstance i = instances.get(handle);
		if (i != null) {
			i.kill();
		}
	}

	public boolean playing(int handle) {
		SoundInstance i = instances.get(handle);
		return i != null && i.alive();
	}

	public void setPosition(int handle, Vector3fc pos) {
		SoundInstance i = instances.get(handle);
		if (i != null) {
			i.setPosition(pos != null ? new Vector3f(pos) : null);
		}
	}

	/** 3D 감쇠 거리 (기본 near 10, far 80 — SEM 이미터 값이 있으면 그것). */
	public void setRange(int handle, float near, float far) {
		SoundInstance i = instances.get(handle);
		if (i != null) {
			i.near = near;
			i.far = far;
			i.updateVoices();
		}
	}

	public void setRegister(int handle, int reg, int value) {
		SoundInstance i = instances.get(handle);
		if (i != null) {
			i.regs[reg & 3] = (byte) value;
		}
	}

	/** 전역 레지스터 (그레인 레지스터 번호 -1-i). */
	public void setGlobalRegister(int i, int value) {
		globalRegs[i & 31] = (byte) value;
	}

	public int globalRegister(int i) {
		return globalRegs[i & 31];
	}

	public void listener(Vector3fc pos, Vector3fc forward, Vector3fc up) {
		listener.set(pos);
		backend.listener(pos, forward, up);
	}

	public void setGroupVolume(int group, float v) {
		groupVolume[group & 15] = v;
	}

	public void setMasterVolume(float v) {
		master = v;
	}

	float groupGain(int group) {
		return master * groupVolume[group & 15] * underwaterGain(group);
	}

	// ---- 수중 (exe sub_001EFB90, TWK_Sound "Underwater *", 29 문서 §10) ----

	/** Underwater Volume Scale / Pitch Offset / Music Vol Scale (1024 = 1.0, 피치 1/128 반음) / Tween Time (초). */
	private float uwVolume = 512 / 1024f, uwPitch = -1524, uwMusic = 768 / 1024f, uwTween = 0.1f;
	private boolean underwater;
	private float uwTime;

	public void setUnderwaterParams(float volScale, float pitchOffset, float musicScale, float tween) {
		uwVolume = volScale;
		uwPitch = pitchOffset;
		uwMusic = musicScale;
		uwTween = Math.max(0.01f, tween);
	}

	/** 원작 플래그 0x29F170&amp;0x8000 (수중) — 0.1초 동안 섞인다. */
	public void setUnderwater(boolean on) {
		underwater = on;
	}

	/** 수중 섞임 0..1. */
	public float underwaterMix() {
		return Math.max(0, Math.min(1, uwTime / uwTween));
	}

	/** 그룹 마스크 0x39 (SFX 0, Ambient 3, IOP 4, Footsteps 5) 볼륨 = 1 + (Scale − 1)·f, 0x2 (Music) = Music Vol Scale. */
	private float underwaterGain(int group) {
		float f = underwaterMix();
		if (f <= 0) {
			return 1;
		}
		return switch (group) {
			case 0, 3, 4, 5 -> 1 + (uwVolume - 1) * f;
			case 1 -> 1 + (uwMusic - 1) * f;
			default -> 1;
		};
	}

	/** 피치 배율: 0x39 그룹과 0x400 (SFX UW pitch, 그룹 10) 에 Pitch Offset·f (1/128 반음 단위 [추정]). */
	float underwaterPitch(int group) {
		float f = underwaterMix();
		if (f <= 0 || !(group == 0 || group == 3 || group == 4 || group == 5 || group == 10)) {
			return 1;
		}
		return (float) Math.pow(2, uwPitch * f / 128.0 / 12.0);
	}

	private void updateUnderwater(float dt) {
		float before = uwTime;
		uwTime = Math.max(0, Math.min(uwTween, uwTime + (underwater ? dt : -dt)));
		if (uwTime != before) {
			for (SoundInstance i : instances.values()) {
				i.updateVoices();
			}
		}
	}

	public void onRumble(BiConsumer<Integer, Integer> l) {
		rumble = l;
	}

	// ---- 음악 / 사운드 그룹 ----

	/** MusicGroupStart(name, fadeSec): 현재 음악을 멈추고 SND_M_* 소리를 음악 그룹으로 재생. */
	public int music(String sndName, float fadeSec) {
		if (sndName.equals(musicName) && playing(musicHandle)) {
			mlog("start " + sndName + " (재생 중 → 유지)");
			return musicHandle;
		}
		mlog("start " + sndName + " fade " + fadeSec);
		stopMusic(fadeSec);
		Ref r = byName.get(sndName);
		if (r == null) {
			return 0;
		}
		SoundInstance in = new SoundInstance(this, nextHandle++, r.bank(), r.sound(), null);
		in.music = true;
		instances.put(in.handle, in);
		if (!override(in, sndName)) {
			in.tick();
		}
		musicHandle = in.handle;
		musicName = sndName;
		return in.handle;
	}

	/** MusicGroupStop(fadeSec). 페이드는 미구현 (즉시 정지). */
	public void stopMusic(float fadeSec) {
		mlog("stop fade " + fadeSec + " (" + musicName + ")");
		if (musicHandle != 0) {
			kill(musicHandle);
		}
		musicHandle = 0;
		musicName = null;
	}

	public String currentMusic() {
		return playing(musicHandle) ? musicName : null;
	}

	/**
	 * MusicGroupParam(a, b, sec) [의미 추정]: 4채널 음악의 층 볼륨으로 해석 — a = 층(0/1 외 값은 층 1), b = 0..127 볼륨.
	 */
	public void musicParam(int a, int b, float sec) {
		mlog("param " + a + " " + b + " " + sec);
		SoundInstance in = instances.get(musicHandle);
		if (in != null) {
			layer(in, a == 0 ? 0 : 1, Math.max(0, Math.min(127, b)) / 127f);
		}
	}

	private void layer(SoundInstance in, int layer, float gain) {
		for (int[] v : in.voices) {
			VpkStream s = streams.get(v[0]);
			if (s != null) {
				s.layerGain[layer] = gain;
			}
		}
		in.children.forEach(c -> layer(c, layer, gain));
	}

	/**
	 * ESC SoundController: group = 엔티티 이름 접두사 (예: SNDGrpAmbDeckofShip), op 0 = 끄기, 1..4 = 이미터 슬롯 소리 켜기.
	 * 이미터 위치 = SEM 행렬 이동 성분 + origin (소유 오브젝트 위치, 모르면 null → 2D).
	 */
	public void soundGroup(String group, int op, Vector3fc origin) {
		int[] old = groupHandles.remove(group);
		if (old != null) {
			for (int h : old) {
				stop(h);
			}
		}
		if (op <= 0) {
			return;
		}
		List<Integer> hs = new ArrayList<>();
		for (WadSounds ws : wads.values()) {
			for (SoundEmitter e : ws.emittersOf(group)) {
				String snd = e.sound(op);
				if (snd == null) {
					continue;
				}
				Vector3f p = origin != null ? new Vector3f(origin).add(e.matrix()[12], e.matrix()[13], e.matrix()[14]) : null;
				int h = play(snd, p);
				if (h != 0) {
					setRange(h, e.near(), Math.max(e.near() + 1, e.far()));
					hs.add(h);
					if (e.reverb()) {
						int t = e.reverbType();
						reverbSources.add(new ReverbSource(h, t > 0 && t < reverbPresets.length ? reverbPresets[t] : e.reverbDepth(), p,
								Math.max(e.near(), e.far())));
					}
				}
			}
		}
		if (!hs.isEmpty()) {
			groupHandles.put(group, hs.stream().mapToInt(Integer::intValue).toArray());
		}
	}

	// ---- 모드 WAV 교체 (49 문서 §11) ----

	/** 읽은 교체 WAV (이름 대문자 + 음악 여부 → 모노 클립 또는 스테레오). 실패는 NONE. */
	private record WavOverride(AdpcmClip mono, WavClip stereo) {
	}

	private static final WavOverride NONE = new WavOverride(null, null);
	private final Map<String, WavOverride> wavOverrides = new HashMap<>();

	/** 계층에 sound/&lt;이름&gt;.wav 가 있으면 in 을 그 WAV 로 시작하고 true. 없으면 false (원래 그레인 경로). */
	private boolean override(SoundInstance in, String sndName) {
		Path f = SoundOverrides.find(sndName);
		if (f == null) {
			return false;
		}
		WavOverride o = wavOverrides.computeIfAbsent(f + (in.music ? "#m" : ""), k -> {
			try {
				WavClip w = WavClip.read("wav:" + f.getFileName(), Files.readAllBytes(f));
				if (w.loopStart() < 0 && SoundOverrides.loop(sndName, in.music)) {
					w = w.withLoop(0);
				} else if (w.loopStart() >= 0 && !SoundOverrides.loop(sndName, true)) {
					w = w.withLoop(-1);
				}
				return w.channels() == 1 ? new WavOverride(w.mono(), null) : new WavOverride(null, w);
			} catch (IOException | RuntimeException e) {
				System.err.println("[mods] " + f + " 읽기 실패: " + e.getMessage());
				return NONE;
			}
		});
		if (o == NONE) {
			return false;
		}
		in.playOverride(o.mono(), o.stereo());
		return true;
	}

	// ---- 그레인 실행기 콜백 ----

	AdpcmClip clip(SoundBank bank, SoundBank.Tone t) {
		int rate = (int) Math.round(t.sampleRate());
		return clips.computeIfAbsent(new ClipKey(bank, t.sampleOffset(), t.sampleSize(), rate), k -> bank.decode(t));
	}

	private final Map<Integer, VpkStream> streams = new HashMap<>();

	/** DPMS 플러그인 메시지. */
	int plugin(SoundInstance in, SoundBank.Plugin p) {
		if (p == null) {
			return 0;
		}
		if (p.command() == 0x16) {
			rumble.accept(p.arg0(), p.arg1());
			if (p.arg0() == 1) { // 모터 1 = 카메라 흔들림 채널 (SND_SHAKE_SMALL/MEDIUM/BIG/HUGE/SPECIAL → 템플릿 1/2/3/4/500)
				shakeInst = in;
				shakeTemplate = p.arg1();
				shakeSerial++;
			}
			return 0;
		}
		if (p.command() != 0) {
			return 0;
		}
		Voice v = in.baseVoice(127, 0);
		v.label = in.name() + " dpms:" + p.name();
		switch (p.kind()) {
			case 0 -> {
				VagPack pack = packEntries.get(p.name().toUpperCase());
				// 팩 항목 클립은 한 번만 디코드 (예전: 재생마다 새 클립 → 백엔드 버퍼 캐시가 재생마다 자랐다, 48 문서 §4)
				AdpcmClip c = pack != null ? packClips.computeIfAbsent(new PackKey(pack, p.name().toUpperCase()), k -> k.pack().clip(k.name())) : null;
				if (c != null) {
					in.addVoice(backend.play(c, v), c.looped());
				}
			}
			case 1 -> {
				AdpcmClip c = vagFile(p.name());
				if (c != null) {
					in.addVoice(backend.play(c, v), c.looped());
				}
			}
			case 2, 3 -> {
				Path f = romFile(p.name() + ".VPK");
				if (f != null) {
					VpkStream s = new VpkStream(f, p.loop());
					int id = backend.stream(s, v);
					if (id > 0) {
						streams.put(id, s);
						in.addVoice(id, p.loop());
					}
				}
			}
			default -> {
			}
		}
		return 0;
	}

	/**
	 * 레벨을 떠날 때: 낱개 VAG 파일 (대사·음악 조각, 레벨마다 다름) 캐시와 그 장치 버퍼를 비운다 — 다시 쓰면 다시 읽는다 (48 문서 §4).
	 * 재생 중인 것은 {@link #stopAll} 뒤라 없다.
	 */
	public void trimFileCache() {
		vagFiles.values().forEach(c -> {
			if (c != null) {
				backend.release(c);
			}
		});
		vagFiles.clear();
	}

	private AdpcmClip vagFile(String name) {
		return vagFiles.computeIfAbsent(name.toUpperCase(), k -> {
			Path f = romFile(k + ".VAG");
			if (f == null) {
				return null;
			}
			try {
				return VagPack.vagClip(k, Files.readAllBytes(f));
			} catch (IOException e) {
				return null;
			}
		});
	}

	private Path romFile(String file) {
		if (romDir == null) {
			return null;
		}
		Path f = Content.file(romDir, file.toUpperCase());
		return Files.exists(f) ? f : null;
	}

	// ---- 리버브 (29 문서 §8) ----

	/** TWK_Sound 값으로 프리셋 갱신 (presets[1..7], base = "Base Reverb"). */
	public void setReverbPresets(int[] presets, float base) {
		System.arraycopy(presets, 1, reverbPresets, 1, Math.min(presets.length, reverbPresets.length) - 1);
		reverbBase = base;
	}

	/** 음악·메뉴·MPEG·수중 음악·Dry Effects 그룹은 리버브를 보내지 않는다. */
	static boolean isDryGroup(int group) {
		return group == 1 || group == 6 || group == 8 || group == 9 || group == 14;
	}

	/**
	 * 리스너 리버브 깊이 (exe 0x171F24 → 0x172910 근사): 리버브 이미터마다 가중치 w (리스너 거리 → 1..0, 반경 = max(near, far) [추정]),
	 * 깊이 = Base + Σ w·(프리셋 − Base) / max(1, Σ w). 이미터가 없으면 Base.
	 */
	public float reverbTarget() {
		float sum = 0, wsum = 0;
		for (ReverbSource r : reverbSources) {
			float w = r.pos == null ? 1 : Math.max(0, 1 - r.pos.distance(listener) / Math.max(1, r.radius));
			sum += w * (r.depth - reverbBase);
			wsum += w;
		}
		return reverbBase + sum / Math.max(1, wsum);
	}

	/** 현재 리버브 깊이 (989snd 단위, 0..0x7FFF). */
	public float reverbDepth() {
		return reverbDepth;
	}

	private void updateReverb(float dt) {
		reverbSources.removeIf(r -> !playing(r.handle));
		float target = reverbTarget();
		// 갑작스런 변화를 막는 이동 (초당 8000 단위 — 원작 슬루 값 미확인)
		reverbDepth = Float.isNaN(reverbDepth) ? target
				: reverbDepth + Math.max(-8000 * dt, Math.min(8000 * dt, target - reverbDepth));
		backend.reverb(reverbDepth / 32767f);
	}

	/** -Dgow.debug.music: 음악 시작/정지/층·보이스 상실 로그 (시각 = 누적 update 초). */
	static final boolean MUSIC_DEBUG = System.getProperty("gow.debug.music") != null;
	private float clock;
	private boolean musicWasPlaying;

	void mlog(String msg) {
		if (MUSIC_DEBUG) {
			System.out.printf("[music %.2f] %s%n", clock, msg);
		}
	}

	// ---- 프레임 ----

	/** 매 프레임: 240Hz 그레인 틱 + 백엔드 보충 + 끝난 인스턴스 정리. */
	public void update(float dt) {
		clock += dt;
		tickAcc += dt;
		int n = 0;
		while (tickAcc >= 1f / TICK_HZ && n++ < TICK_HZ) {
			tickAcc -= 1f / TICK_HZ;
			for (SoundInstance i : instances.values()) {
				i.tick();
			}
		}
		updateReverb(dt);
		updateUnderwater(dt);
		backend.tick(dt);
		streams.keySet().removeIf(id -> !backend.playing(id));
		Iterator<SoundInstance> it = instances.values().iterator();
		while (it.hasNext()) {
			if (!it.next().alive()) {
				it.remove();
			}
		}
		if (MUSIC_DEBUG) {
			boolean now = playing(musicHandle);
			if (musicWasPlaying && !now && musicHandle != 0) {
				mlog("음악 인스턴스 끝남/잃음 (" + musicName + ") " + backend.stats());
			}
			musicWasPlaying = now;
		}
	}

	/** 활성 인스턴스 수. */
	public int active() {
		return instances.size();
	}

	public void stopAll() {
		instances.values().forEach(SoundInstance::kill);
		instances.clear();
		groupHandles.clear();
		musicHandle = 0;
		musicName = null;
	}

	@Override
	public void close() {
		stopAll();
		backend.close();
	}
}
