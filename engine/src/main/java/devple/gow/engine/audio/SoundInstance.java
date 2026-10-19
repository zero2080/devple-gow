package devple.gow.engine.audio;

import devple.gow.tools.audio.AdpcmClip;
import devple.gow.tools.audio.SoundBank;
import devple.gow.tools.audio.SoundBank.Grain;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 재생 중인 소리 하나 = 989snd 블록 사운드 핸들러 (IRX 0x63A0 그레인 실행기 재현, 14 문서 §5).
 * 240Hz 틱마다 대기(countdown)를 줄이고, 0 이 되면 그레인을 실행한다. 그레인 k 의 delay 는 k 실행 전 대기.
 */
public final class SoundInstance {

	final SoundManager mgr;
	final int handle;
	final SoundBank bank;
	SoundBank.Sound sound;
	List<Grain> grains;
	final SoundInstance parent;
	final List<SoundInstance> children = new ArrayList<>();
	/** 보이스 id 와 루프 여부. */
	final List<int[]> voices = new ArrayList<>();
	/** 톤 보이스의 SPU ADSR 엔벨로프 (보이스 id → 엔벨로프, 29 문서 §7). 플러그인·스트림 보이스는 없음 (= 1). */
	final Map<Integer, SpuEnvelope> envs = new HashMap<>();
	final byte[] regs = new byte[4];
	/** 핸들러 볼륨 0..127 (자식 사운드 ref vol), pan(도), pitch bend (-32768..32767). */
	int vol = 127;
	int pan;
	int bend;
	Vector3f pos;
	float near = 10;
	float far = 80;
	boolean music;
	int idx;
	int countdown;
	boolean done;
	boolean killed;
	private boolean stopping;
	private boolean loopedOnce;
	private boolean rpActive;
	private int rpCount;
	private int rpSkip;

	SoundInstance(SoundManager mgr, int handle, SoundBank bank, SoundBank.Sound sound, SoundInstance parent) {
		this.mgr = mgr;
		this.handle = handle;
		this.bank = bank;
		this.parent = parent;
		setSound(sound);
	}

	private void setSound(SoundBank.Sound s) {
		sound = s;
		grains = bank.grains(s);
		idx = 0;
		countdown = grains.isEmpty() ? 0 : grains.get(0).delay();
		done = grains.isEmpty();
		rpActive = false;
	}

	public String name() {
		return sound.name() != null ? sound.name() : bank.bankId() + "#" + sound.index();
	}

	public boolean alive() {
		if (killed) {
			return false;
		}
		if (!done) {
			return true;
		}
		for (int[] v : voices) {
			if (mgr.backend.playing(v[0])) {
				return true;
			}
		}
		for (SoundInstance c : children) {
			if (c.alive()) {
				return true;
			}
		}
		return false;
	}

	/** 240Hz 한 틱. */
	void tick() {
		voices.removeIf(v -> {
			SpuEnvelope e = envs.get(v[0]);
			if (e != null) {
				e.advanceSamples(SpuEnvelope.RATE / SoundManager.TICK_HZ);
				if (e.off()) {
					mgr.backend.stop(v[0]);
				}
			}
			if (!mgr.backend.playing(v[0])) {
				envs.remove(v[0]);
				return true;
			}
			return false;
		});
		if (!envs.isEmpty()) {
			updateVoices();
		}
		for (SoundInstance c : children) {
			if (!c.killed) {
				c.tick();
			}
		}
		if (done || killed) {
			return;
		}
		if (countdown > 0) {
			countdown--;
		}
		int guard = 0;
		while (!done && !killed && countdown <= 0 && guard++ < 512) {
			step();
		}
	}

	private void step() {
		if (idx < 0 || idx >= grains.size()) {
			done = true;
			return;
		}
		Grain g = grains.get(idx);
		SoundBank.Sound before = sound;
		int ret = exec(g);
		if (ret < 0) {
			done = true;
			return;
		}
		if (sound != before) {
			// BRANCH: 새 소리의 첫 그레인부터 (setSound 가 countdown 설정)
			return;
		}
		if (rpActive && --rpCount <= 0) {
			idx += rpSkip;
			rpActive = false;
		}
		idx++;
		if (idx >= grains.size()) {
			done = true;
			return;
		}
		countdown = Math.max(0, grains.get(idx).delay()) + ret;
	}

	private int reg(int r) {
		return r >= 0 ? regs[r & 3] : mgr.globalRegs[(~r) & 31];
	}

	private void setReg(int r, int v) {
		v = Math.max(-128, Math.min(127, v));
		if (r >= 0) {
			regs[r & 3] = (byte) v;
		} else {
			mgr.globalRegs[(~r) & 31] = (byte) v;
		}
	}

	private int exec(Grain g) {
		switch (g.type()) {
			case SoundBank.G_TONE, SoundBank.G_TONE2 -> tone(bank.tone(g));
			case SoundBank.G_PLUGIN -> mgr.plugin(this, bank.plugin(g));
			case SoundBank.G_START_CHILD -> {
				SoundBank.SoundRef r = bank.soundRef(g);
				SoundBank.Sound s = resolve(r);
				if (s != null) {
					children.add(mgr.child(this, s, r.vol() >= 0 ? r.vol() : vol));
				}
			}
			case SoundBank.G_STOP_CHILD -> {
				SoundBank.SoundRef r = bank.soundRef(g);
				for (SoundInstance c : children) {
					if (c.sound.index() == r.soundIndex() || (r.soundIndex() < 0 && r.soundName().equals(c.sound.name()))) {
						c.stop();
					}
				}
			}
			case SoundBank.G_BRANCH -> {
				SoundBank.SoundRef r = bank.soundRef(g);
				SoundBank.Sound s = resolve(r);
				if (s == null || s.numGrains() <= 0) {
					return -1;
				}
				if (r.vol() >= 0) {
					vol = r.vol();
				}
				setSound(s);
			}
			case SoundBank.G_LOOP_END -> {
				for (int k = idx - 1; k >= 0; k--) {
					if (grains.get(k).type() == SoundBank.G_LOOP_START) {
						idx = k - 1;
						int extra = loopedOnce ? 1 : 0;
						loopedOnce = true;
						return extra;
					}
				}
				return -1;
			}
			case SoundBank.G_LOOP_CONTINUE -> {
				for (int k = idx + 1; k < grains.size(); k++) {
					if (grains.get(k).type() == SoundBank.G_LOOP_END) {
						idx = k;
						return 0;
					}
				}
				return -1;
			}
			case SoundBank.G_STOP -> {
				return -1;
			}
			case SoundBank.G_RAND_PLAY, SoundBank.G_PLAY_CYCLE -> {
				int opts = Math.max(1, g.arg(0));
				int group = g.arg(1);
				int pick;
				if (g.type() == SoundBank.G_RAND_PLAY) {
					pick = mgr.rng.nextInt(opts);
					if (pick == lastPick(g) && ++pick == opts) {
						pick = 0;
					}
				} else {
					pick = cycle(g, opts);
				}
				rememberPick(g, pick);
				idx += pick * group;
				rpCount = group + 1;
				rpSkip = (opts - 1 - pick) * group;
				rpActive = true;
			}
			case SoundBank.G_RAND_DELAY -> {
				return mgr.rng.nextInt(g.arg() + 1);
			}
			case SoundBank.G_RAND_PB -> {
				bend = (int) ((mgr.rng.nextInt(65536) - 32768) * (long) g.arg(0) / 100);
				updateVoices();
			}
			case SoundBank.G_PB -> {
				bend = g.arg(0) < 0 ? g.arg(0) * 256 : g.arg(0) * 32767 / 127;
				updateVoices();
			}
			case SoundBank.G_ADD_PB -> {
				bend = Math.max(-32768, Math.min(32767, bend + g.arg(0) * 32767 / 127));
				updateVoices();
			}
			case SoundBank.G_SET_REG -> setReg(g.arg(0), g.arg(1));
			case SoundBank.G_SET_REG_RAND -> setReg(g.arg(0), g.arg(1) + mgr.rng.nextInt(Math.max(1, g.arg(2) - g.arg(1) + 1)));
			case SoundBank.G_INC_REG -> setReg(g.arg(0), reg(g.arg(0)) + 1);
			case SoundBank.G_DEC_REG -> setReg(g.arg(0), reg(g.arg(0)) - 1);
			case SoundBank.G_ADD_REG -> setReg(g.arg(1), reg(g.arg(1)) + g.arg(0));
			case SoundBank.G_COPY_REG -> setReg(g.arg(1), reg(g.arg(0)));
			case SoundBank.G_TEST_REG -> {
				int v = reg(g.arg(0));
				int cmp = g.arg(2);
				boolean ok = switch (g.arg(1)) {
					case 0 -> v < cmp;
					case 1 -> v == cmp;
					case 2 -> v > cmp;
					default -> true;
				};
				if (!ok) {
					idx++; // 다음 그레인 건너뜀
				}
			}
			case SoundBank.G_GOTO_MARKER -> {
				int m = marker(g.arg(0));
				if (m < 0) {
					return -1;
				}
				idx = m - 1;
			}
			case SoundBank.G_GOTO_RANDOM_MARKER -> {
				int lo = g.arg(0);
				int hi = Math.max(lo, g.arg(1));
				int m = marker(lo + mgr.rng.nextInt(hi - lo + 1));
				if (m >= 0) {
					idx = m - 1;
				}
			}
			case SoundBank.G_WAIT_VOICES -> {
				voices.removeIf(v -> !mgr.backend.playing(v[0]));
				if (!voices.isEmpty()) {
					idx--;
					return 1;
				}
			}
			case SoundBank.G_KEY_OFF -> keyOff();
			case SoundBank.G_KILL_VOICES -> keyOff();
			case SoundBank.G_ON_STOP_MARKER -> idx = grains.size() - 1;
			default -> {
				// NULL / CONTROL_NULL / MARKER / LOOP_START / LFO / XREF / 10~12 (GoW 전용 미상): 무시
			}
		}
		return 0;
	}

	// RAND_PLAY / PLAY_CYCLE 는 원작에서 그레인 데이터 arg[2] 에 직전 선택을 기록한다 → 인스턴스 공유 상태로 흉내.
	private int lastPick(Grain g) {
		return mgr.picks.getOrDefault(key(g), -1);
	}

	private int cycle(Grain g, int opts) {
		int cur = mgr.picks.getOrDefault(key(g), g.arg(2) - 1) + 1;
		return Math.floorMod(cur, opts);
	}

	private void rememberPick(Grain g, int pick) {
		mgr.picks.put(key(g), pick);
	}

	private long key(Grain g) {
		return ((long) System.identityHashCode(bank) << 32) | (sound.firstGrain() + idx);
	}

	private int marker(int id) {
		for (int k = 0; k < grains.size(); k++) {
			if (grains.get(k).type() == SoundBank.G_MARKER && grains.get(k).arg(0) == id) {
				return k;
			}
		}
		return -1;
	}

	private SoundBank.Sound resolve(SoundBank.SoundRef r) {
		if (r.soundIndex() >= 0 && r.soundIndex() < bank.sounds().size()) {
			return bank.sounds().get(r.soundIndex());
		}
		return r.soundName().isEmpty() ? null : bank.sound(r.soundName());
	}

	private void tone(SoundBank.Tone t) {
		if (t.sampleSize() <= 0) {
			return; // TONE2 노이즈 등 샘플 없는 톤
		}
		AdpcmClip c = mgr.clip(bank, t);
		SpuEnvelope env = new SpuEnvelope(t.adsr1(), t.adsr2());
		env.advanceSamples(16); // 첫 틱 전에 즉시 공격(shift 0)은 이미 최대
		Voice v = baseVoice(t.vol(), t.pan());
		v.gain *= env.level();
		v.pitch = bendPitch(t);
		v.label = name() + " tone@" + Integer.toHexString(t.sampleOffset());
		int id = mgr.backend.play(c, v);
		if (id > 0) {
			voices.add(new int[]{id, c.looped() ? 1 : 0, t.vol(), t.pan(), t.pbLow(), t.pbHigh()});
			envs.put(id, env);
		}
	}

	private float bendPitch(SoundBank.Tone t) {
		return bendPitch(t.pbLow(), t.pbHigh());
	}

	private float bendPitch(int pbLow, int pbHigh) {
		double semis = bend < 0 ? bend / 32768.0 * pbLow : bend / 32767.0 * pbHigh;
		return (float) Math.pow(2, semis / 12);
	}

	/** 볼륨 = 핸들러 × 소리 × 톤 (각 /127) × 볼륨 그룹 × 마스터. */
	Voice baseVoice(int toneVol, int tonePan) {
		Voice v = new Voice();
		float g = vol / 127f * Math.max(0, sound.vol()) / 127f * Math.max(0, toneVol) / 127f;
		int group = music ? 1 : sound.volGroup();
		v.gain = g * mgr.groupGain(group);
		v.dry = SoundManager.isDryGroup(group);
		v.pan = pan + tonePan;
		v.pos = pos != null ? new Vector3f(pos) : null;
		v.near = near;
		v.far = far;
		return v;
	}

	void addVoice(int id, boolean looped) {
		if (id > 0) {
			voices.add(new int[]{id, looped ? 1 : 0, 127, 0, 0, 0});
		}
	}

	void updateVoices() {
		for (int[] v : voices) {
			Voice x = baseVoice(v[2], v[3]);
			x.pitch = bendPitch(v[4], v[5]);
			SpuEnvelope e = envs.get(v[0]);
			if (e != null) {
				x.gain *= e.level();
			}
			mgr.backend.update(v[0], x);
		}
	}

	/** KEY_OFF: 엔벨로프가 있는 보이스는 릴리스 단계로 (꼬리가 끝나면 tick 이 정지), 없는 보이스는 즉시 정지. */
	void keyOff() {
		voices.removeIf(this::keyOff);
	}

	/** @return 보이스를 목록에서 뺐으면 true (즉시 정지). */
	private boolean keyOff(int[] v) {
		SpuEnvelope e = envs.get(v[0]);
		if (e != null && !killed) {
			e.release();
			return false;
		}
		mgr.backend.stop(v[0]);
		envs.remove(v[0]);
		return true;
	}

	/** 정지 요청: ON_STOP_MARKER 가 있으면 루프 보이스를 끄고 그 뒤 그레인을 실행, 없으면 즉시 정지. */
	public void stop() {
		for (SoundInstance c : children) {
			c.stop();
		}
		int marker = -1;
		for (int k = 0; k < grains.size(); k++) {
			if (grains.get(k).type() == SoundBank.G_ON_STOP_MARKER) {
				marker = k;
				break;
			}
		}
		if (marker >= 0 && !killed && !stopping) {
			stopping = true;
			voices.removeIf(v -> v[1] != 0 && keyOff(v));
			idx = marker + 1;
			done = idx >= grains.size();
			countdown = done ? 0 : Math.max(0, grains.get(idx).delay());
			rpActive = false;
			return;
		}
		kill();
	}

	/** 즉시 정지 (보이스·자식 포함). */
	public void kill() {
		killed = true;
		done = true;
		keyOff();
		for (SoundInstance c : children) {
			c.kill();
		}
	}

	void setPosition(Vector3f p) {
		pos = p != null ? new Vector3f(p) : null;
		updateVoices();
		for (SoundInstance c : children) {
			c.setPosition(p);
		}
	}
}
