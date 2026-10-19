package devple.gow.tools.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SND 리소스(매직 0x18) = 이름 표 + 989snd {@code SBlk} 효과음 뱅크 (14 문서).
 * <pre>
 * +0x00 u32 magic 0x18          +0x04 u32 이름 수 N
 * +0x08 N × {char[24] name, u32 soundIndex}
 * A = 8 + 28N : FileAttributes { u32 type=3, u32 chunks=2, {u32 off,u32 size}[2] }  (오프셋은 A 기준)
 *   chunk0 = SBlk 뱅크 데이터, chunk1 = SPU ADPCM 샘플 데이터 (VAG 헤더 없음)
 * SBlk (chunk0 기준)
 *   +00 "SBlk" +04 version(3) +08 flags(4) +0C BankID("GEN1", 뒤집힌 4문자) +10 u32
 *   +14 s16 ? +16 s16 NumSounds +18 s16 NumGrains +1A s16 NumVAGs
 *   +1C FirstSound +20 FirstGrain +24 SRAM 주소(0x5040) +28 VagDataSize +2C SRAMAllocSize +30 NextBank +34 FirstGrainData
 * Sound (12B): s8 vol, s8 volGroup, s16 pan, s8 numGrains, s8 instanceLimit, u8 flags, u8 pad, u32 firstGrain(바이트 오프셋)
 * Grain (8B):  u32 (type << 24 | arg 24비트), s32 delay(240Hz 틱, 이 그레인 실행 전 대기)
 * </pre>
 */
public final class SoundBank {

	public static final int MAGIC = 0x18;

	/** 그레인 타입 (989snd, IRX 그레인 표 0x1E47C 로 확인). */
	public static final int G_NULL = 0, G_TONE = 1, G_XREF_ID = 2, G_XREF_NUM = 3, G_LFO = 4, G_START_CHILD = 5,
			G_STOP_CHILD = 6, G_PLUGIN = 7, G_BRANCH = 8, G_TONE2 = 9, G_CONTROL_NULL = 20, G_LOOP_START = 21,
			G_LOOP_END = 22, G_LOOP_CONTINUE = 23, G_STOP = 24, G_RAND_PLAY = 25, G_RAND_DELAY = 26, G_RAND_PB = 27,
			G_PB = 28, G_ADD_PB = 29, G_SET_REG = 30, G_SET_REG_RAND = 31, G_INC_REG = 32, G_DEC_REG = 33,
			G_TEST_REG = 34, G_MARKER = 35, G_GOTO_MARKER = 36, G_GOTO_RANDOM_MARKER = 37, G_WAIT_VOICES = 38,
			G_PLAY_CYCLE = 39, G_ADD_REG = 40, G_KEY_OFF = 41, G_KILL_VOICES = 42, G_ON_STOP_MARKER = 43, G_COPY_REG = 44;

	public record Sound(int index, String name, int vol, int volGroup, int pan, int numGrains, int instanceLimit,
						int flags, int firstGrain) {
	}

	/** arg = 하위 24비트. arg(i) = i 번째 바이트(부호 있음). */
	public record Grain(int type, int arg, int delay) {
		public int arg(int i) {
			return (byte) (arg >> (8 * i));
		}
	}

	/**
	 * 톤 (24B). centerNote 음수 = PS2 샘플(48kHz 기준), 양수 = PS1(44.1kHz 비율 보정).
	 * 재생 피치 = sceSdNote2Pitch(|cn|, cf, note=60, fine=0) (IRX 0x1B5DC).
	 */
	public record Tone(int priority, int vol, int centerNote, int centerFine, int pan, int mapLow, int mapHigh,
					   int pbLow, int pbHigh, int adsr1, int adsr2, int flags, int sampleOffset, int sampleSize) {

		/** note(60 기본) 에서의 재생 샘플레이트 (Hz). */
		public double sampleRate(double note) {
			int cn = Math.abs(centerNote);
			double r = 48000 * Math.pow(2, (note - cn + centerFine / 128.0) / 12);
			if (centerNote >= 0) {
				r = r * 44100 / 48000;
			}
			return Math.min(r, 0x3FFF * 48000.0 / 0x1000); // SPU2 피치 레지스터 상한 (0x3FFF ≈ 4배)
		}

		public double sampleRate() {
			return sampleRate(60);
		}
	}

	/**
	 * PLUGIN_MESSAGE 데이터 (32B, 'DPMS' = SMPD_IOP.IRX 플러그인).
	 * cmd 0: name[8] = 재생할 파일/팩 항목, param(+0x10, 음성 분류?), kind(+0x14) 0 메모리 VAG 팩 / 1 디스크 VAG / 2 VPK 스테레오 / 3 VPK 4채널,
	 * loop(+0x18), flag1c(+0x1C, 음악 계열에서 1). cmd 0x16: 패드 진동 (arg0 모터, arg1 세기/시간).
	 */
	public record Plugin(int command, String name, int param, int kind, boolean loop, int flag1c, int arg0, int arg1) {
	}

	/** START/STOP_CHILD, BRANCH 데이터 (32B): +0 s8 vol(-1 = 상속), +0xC s32 소리 번호(&lt;0 이면 +0x10 이름으로 검색). */
	public record SoundRef(int vol, int soundIndex, String soundName) {
	}

	private final String name;
	private final ByteBuffer res;
	private final String bankId;
	private final int sblk;
	private final int grainData;
	private final byte[] samples;
	private final List<Sound> sounds;
	private final List<Grain> grains;
	private final Map<String, Integer> byName = new HashMap<>();

	public SoundBank(String name, ByteBuffer data) {
		this.name = name;
		this.res = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		if (res.getInt(0) != MAGIC) {
			throw new IllegalArgumentException("SND 리소스 아님: " + name);
		}
		int n = res.getInt(4);
		List<String[]> pairs = new ArrayList<>();
		int off = 8;
		for (int i = 0; i < n; i++) {
			String nm = cstr(res, off, 24);
			int idx = res.getInt(off + 24);
			byName.putIfAbsent(nm, idx);
			pairs.add(new String[]{nm, Integer.toString(idx)});
			off += 28;
		}
		int a = off;
		int chunk0 = res.getInt(a + 8);
		int chunk1 = res.getInt(a + 16);
		int chunk1Size = res.getInt(a + 20);
		sblk = a + chunk0;
		if (res.getInt(sblk) != 0x6B6C4253) { // "SBlk"
			throw new IllegalArgumentException("SBlk 없음: " + name);
		}
		byte[] id = new byte[4];
		res.get(sblk + 12, id);
		bankId = new StringBuilder(new String(id, StandardCharsets.ISO_8859_1).replace("\0", "")).reverse().toString();
		int numSounds = res.getShort(sblk + 0x16);
		int numGrains = res.getShort(sblk + 0x18);
		int firstSound = res.getInt(sblk + 0x1C);
		int firstGrain = res.getInt(sblk + 0x20);
		grainData = sblk + res.getInt(sblk + 0x34);
		int sOff = a + chunk1;
		samples = new byte[Math.max(0, Math.min(chunk1Size, res.limit() - sOff))];
		res.get(sOff, samples);
		String[] names = new String[numSounds];
		for (String[] p : pairs) {
			int idx = Integer.parseInt(p[1]);
			if (idx >= 0 && idx < numSounds && names[idx] == null) {
				names[idx] = p[0];
			}
		}
		List<Sound> ss = new ArrayList<>(numSounds);
		for (int i = 0; i < numSounds; i++) {
			int o = sblk + firstSound + i * 12;
			ss.add(new Sound(i, names[i], res.get(o), res.get(o + 1), res.getShort(o + 2), res.get(o + 4), res.get(o + 5),
					res.get(o + 6) & 0xFF, res.getInt(o + 8) / 8));
		}
		sounds = Collections.unmodifiableList(ss);
		List<Grain> gs = new ArrayList<>(numGrains);
		for (int i = 0; i < numGrains; i++) {
			int o = sblk + firstGrain + i * 8;
			int w = res.getInt(o);
			gs.add(new Grain(w >>> 24, w & 0xFFFFFF, res.getInt(o + 4)));
		}
		grains = Collections.unmodifiableList(gs);
	}

	public static boolean isBank(ByteBuffer data) {
		return data.limit() >= 8 && data.order(ByteOrder.LITTLE_ENDIAN).getInt(0) == MAGIC;
	}

	/** 리소스 이름 (예: GENERAL, SBK_Athn01A). */
	public String name() {
		return name;
	}

	/** SBlk BankID (예: GEN1, A01A). */
	public String bankId() {
		return bankId;
	}

	public List<Sound> sounds() {
		return sounds;
	}

	public List<Grain> grains() {
		return grains;
	}

	/** 이름 → 소리 번호, 없으면 -1. */
	public int index(String sndName) {
		return byName.getOrDefault(sndName, -1);
	}

	public Map<String, Integer> names() {
		return Collections.unmodifiableMap(byName);
	}

	public Sound sound(String sndName) {
		int i = index(sndName);
		return i >= 0 ? sounds.get(i) : null;
	}

	/** 소리의 그레인 목록. */
	public List<Grain> grains(Sound s) {
		int from = Math.min(grains.size(), s.firstGrain());
		return grains.subList(from, Math.min(grains.size(), from + Math.max(0, s.numGrains())));
	}

	public byte[] sampleData() {
		return samples;
	}

	public Tone tone(Grain g) {
		int o = grainData + g.arg();
		return new Tone(res.get(o), res.get(o + 1), res.get(o + 2), res.get(o + 3), res.getShort(o + 4), res.get(o + 6),
				res.get(o + 7), res.get(o + 8), res.get(o + 9), res.getShort(o + 10) & 0xFFFF, res.getShort(o + 12) & 0xFFFF,
				res.getShort(o + 14) & 0xFFFF, res.getInt(o + 16), res.getInt(o + 20));
	}

	/** PLUGIN 데이터. DPMS 가 아니면 null. */
	public Plugin plugin(Grain g) {
		int o = grainData + g.arg();
		if (o + 32 > res.limit() || res.getInt(o) != 0x534D5044) { // "DPMS"
			return null;
		}
		int cmd = res.getInt(o + 4);
		if (cmd != 0) {
			return new Plugin(cmd, "", 0, -1, false, 0, res.getInt(o + 8), res.getInt(o + 12));
		}
		return new Plugin(0, cstr(res, o + 8, 8), res.getInt(o + 16), res.getInt(o + 20), res.getInt(o + 24) != 0,
				res.getInt(o + 28), 0, 0);
	}

	public SoundRef soundRef(Grain g) {
		int o = grainData + g.arg();
		return new SoundRef(res.get(o), res.getInt(o + 12), cstr(res, o + 16, 16));
	}

	/** 톤 샘플 디코딩 (재생 레이트 = 톤 기본 피치). */
	public AdpcmClip decode(Tone t) {
		String nm = String.format("%s@%x", bankId, t.sampleOffset());
		if (t.sampleSize() <= 0 || t.sampleOffset() < 0 || t.sampleOffset() >= samples.length) {
			return new AdpcmClip(nm, (int) Math.round(t.sampleRate()), new short[0], -1);
		}
		return AdpcmClip.decode(nm, (int) Math.round(t.sampleRate()), samples, t.sampleOffset(), t.sampleSize());
	}

	static String cstr(ByteBuffer b, int off, int max) {
		int len = 0;
		while (len < max && off + len < b.limit() && b.get(off + len) != 0) {
			len++;
		}
		byte[] tmp = new byte[len];
		b.get(off, tmp);
		return new String(tmp, StandardCharsets.ISO_8859_1);
	}

	@Override
	public String toString() {
		return String.format("SoundBank[%s %s sounds=%d grains=%d samples=%d]", name, bankId, sounds.size(), grains.size(),
				samples.length);
	}
}
