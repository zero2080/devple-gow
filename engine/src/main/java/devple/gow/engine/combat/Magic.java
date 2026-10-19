package devple.gow.engine.combat;

import devple.gow.engine.game.PlayerState;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.game.Weapon;
import devple.gow.tools.combat.CombatFile.Attack;
import org.joml.Vector3f;

/**
 * 마법 비용과 효과 (10 문서 §10, 35 문서). 수치는 TWK {@code /Player/<마법>/Stage n ...}.
 * <ul>
 *   <li>포세이돈의 분노(Lightning, 3): 루프 기술(AIRLIGHTNINGLOOP) 동안 Reach 안의 대상에게 초당 피해. MP 는 분기 시작 때</li>
 *   <li>메두사의 시선(4): {@link MedusaGaze} — 광선 중 초당 소모</li>
 *   <li>제우스의 분노(5): {@link ZeusBolt} — 던질 때마다 소모</li>
 *   <li>하데스의 군대(6): {@link HadesSouls} — 영혼마다 소모 (합 75)</li>
 * </ul>
 * 효과는 영웅 기술의 SCRIPT 이벤트(SCR_ 명령 해시)로 발동한다 — 원작도 기술 스크립트가 SCR_ 명령을 부른다.
 */
public final class Magic {

	/** 효과/소리 출력 (렌더·오디오 쪽이 구현, 헤드리스는 기본 무시). */
	public interface Sink {
		default void sound(String name, Vector3f at) {
		}

		/** 원작 GO 이펙트 이름 (goBoltHit, goDeadHit, goEyeBeam ...). */
		default void effect(String name, Vector3f at) {
		}

		default void hit(Hittable h, float damage) {
		}

		/**
		 * Concussion 템플릿 발동 (sub_00191720: 템플릿의 Effect Name 을 Joint Name 위치에, 반경 Start→End 를 Duration 동안,
		 * Scale X/Y/Z 플래그면 이펙트를 반경으로 늘림). 40 문서 §4.3.
		 */
		default void concussion(int template, Vector3f at) {
		}

		default void petrified(Hittable h) {
		}
	}

	/** USEHEAD 기술 ID (영웅 CombatFile, 애니 없음 → 메두사 스크립트가 유지). */
	static final int USEHEAD = 82;

	public final MedusaGaze medusa;
	public final ZeusBolt zeus;
	public final HadesSouls hades;
	private java.util.function.Supplier<? extends java.util.List<? extends Hittable>> targets;
	private Sink sink = new Sink() {
	};

	/** 1 TWK 거리 단위 = 16 유닛 (Targeting Range 와 같은 추정, 27 문서). */
	public static final float UNIT = 16f;
	private static final String[] NODE = {null, null, null, "Lightning", "MedusaHead", "Thunderbolt", "RaiseTheDead"};

	private final Tweaks tw;
	private final PlayerState player;
	private float acc;

	public Magic(Tweaks tw, PlayerState player) {
		this.tw = tw;
		this.player = player;
		this.medusa = new MedusaGaze(tw);
		this.zeus = new ZeusBolt(tw);
		this.hades = new HadesSouls(tw);
	}

	/** 마법 효과 대상 목록 (게임 씬 = 살아있는 적 전체). 없으면 락온 대상 하나. */
	public void setTargets(java.util.function.Supplier<? extends java.util.List<? extends Hittable>> t) {
		targets = t;
	}

	/** 메두사 시선 충돌 검사 (레벨 충돌 레이). */
	public void setSightBlocked(java.util.function.BiPredicate<Vector3f, Vector3f> p) {
		medusa.sightBlocked = p;
	}

	public void setSink(Sink s) {
		sink = s;
	}

	public int stage(int magicId) {
		Weapon w = Weapon.ofItem(magicId);
		return w == null ? 0 : Math.min(2, player.level.get(w));
	}

	/** 방향키 → 마법 (sub_001D7E10): 오른쪽 3 포세이돈, 왼쪽 4 메두사, 아래 5 제우스, 위 6 하데스. */
	private static final devple.gow.engine.input.PadButton[] DPAD = {devple.gow.engine.input.PadButton.RIGHT,
			devple.gow.engine.input.PadButton.LEFT, devple.gow.engine.input.PadButton.DOWN, devple.gow.engine.input.PadButton.UP};
	/** 선택 효과음 표 0x29DC50. */
	private static final String[] SELECT_SOUND = {"SND_SELECT_LIGHTNING", "SND_SELECT_MEDUSA", "SND_SELECT_BOLT", "SND_SELECT_RTD"};

	/**
	 * 방향키 마법 선택 (sub_001D7ED8): 보유하지 않은 마법이면 SND_SELECT_NULL, 이미 선택이면 무시,
	 * 아니면 선택 효과음 + 선택 마법(0x3044BE) 변경.
	 *
	 * @return 바뀌었으면 새 마법 ID, 아니면 0
	 */
	public int select(devple.gow.engine.input.Input in, Vector3f at) {
		if (in == null) {
			return 0;
		}
		for (int i = 0; i < 4; i++) {
			if (!in.pressed(DPAD[i])) {
				continue;
			}
			int id = 3 + i;
			Weapon w = Weapon.ofItem(id);
			if (w == null || !player.owned(w)) {
				sink.sound("SND_SELECT_NULL", at);
				return 0;
			}
			if (player.selectedMagic == w) {
				return 0;
			}
			player.selectedMagic = w;
			sink.sound(SELECT_SOUND[i], at);
			return id;
		}
		return 0;
	}

	/** 시전 비용 (HasMagic 기준값). */
	public float cost(int magicId) {
		String node = "/Player/" + NODE[magicId] + "/";
		int st = stage(magicId);
		return switch (magicId) {
			case 4 -> tw.f(node, "Stage " + st + " Drain", 12.5f) * 0.05f;
			// Num Dead Guys × Magic Drain = 75 (모든 단계, 시전 조건 0x1D9510)
			case 6 -> tw.i16(node, "Stage " + st + " Num Dead Guys", 3) * tw.f(node, "Stage " + st + " Magic Drain", 25f);
			default -> tw.f(node, "Stage " + st + " Magic Drain", 33f);
		};
	}

	public boolean has(int magicId) {
		return magicId >= 3 && magicId <= 6 && player.mp >= cost(magicId);
	}


	/**
	 * 분기 시작 시 소모 (Combatant.start). 번개만 시작 때 Stage Magic Drain 을 쓰고,
	 * 메두사·제우스·하데스는 효과 쪽(0x1DCA58 호출 위치)에서 소모한다.
	 */
	public void use(int magicId) {
		if (magicId == 3) {
			drain(cost(magicId));
		}
	}

	private void drain(float v) {
		if (v > 0) {
			player.mp = Math.max(0, Math.min(player.maxMp(), player.mp - v));
		}
	}

	/** 입력 없는 갱신 (예전 호출 호환). */
	public void update(Combatant c, float dt) {
		update(c, null, dt);
	}

	/** 매 틱: 번개 루프 피해 + 메두사·제우스·하데스 효과. */
	public void update(Combatant c, devple.gow.engine.input.Input in, float dt) {
		c.setMoveHold(id -> id == USEHEAD && medusa.canHold());
		java.util.List<? extends Hittable> ts = targets != null ? targets.get()
				: c.target() != null ? java.util.List.of(c.target()) : java.util.List.of();
		drain(medusa.update(c, in, ts, stage(4), player.mp, dt, sink));
		drain(zeus.update(c, in, ts, stage(5), player.mp, dt, sink));
		drain(hades.update(c, ts, stage(6), dt, sink));
		lightning(c, in, ts, dt);
	}

	private boolean lightningOn;
	/** SCR_Lightning 스크립트 해시 (영웅 AIRLIGHTNINGSTART 0.25~, AIRLIGHTNINGLOOP 이벤트). */
	public static final int SCR_LIGHTNING = 0x79C369CD;
	private boolean lightningScript;
	private float lightningTime;
	public final LightningMash mash = new LightningMash();

	/** SCR_Lightning 이 돌고 있는지 (시작 기술 0.25 부터 LIGHTNINGEXIT 전까지) — 화면 효과·영웅 틴트 (0x195BA8, 0x1E03C0) 의 수명. */
	public boolean lightningScript() {
		return lightningScript;
	}

	/** 스크립트 경과 초 (0x3368B4). */
	public float lightningTime() {
		return lightningTime;
	}
	private float mashRate, lightningReach = 5;

	/** 포세이돈의 분노 루프 기술(*lightningloop) 재생 중 — 이펙트 goLightningAttackMain 유지 (40 문서 §5.4). */
	public boolean lightningOn() {
		return lightningOn;
	}

	/** 연타율 = 매시 미터 / Mashometer Max (0x194BD4). 번개 중이 아니면 0. */
	public float mashRate() {
		return mashRate;
	}

	/** 연타로 늘어난 사거리 (TWK 미터): Reach · (1 + 연타율·(Mash Reach Scale − 1)) (0x194C04, f23). */
	public float lightningReach() {
		return lightningReach;
	}

	/**
	 * 영웅 회전 속도 (라디안/초): 전역 "Mash Max Spin" (0x29D774, 1200 도/초) · 연타율. 원작은 매 프레임 영웅 행렬에
	 * Y 회전 (1200/180·π · 연타율 · 1/60) 을 곱한다 (0x195160 → sub_00184A98) [확실].
	 */
	public float lightningSpin() {
		return lightningOn ? (float) Math.toRadians(tw.f("/Player/Lightning/", "Mash Max Spin", 1200f)) * mashRate : 0;
	}

	/**
	 * SCR_Lightning (0x1948F0) 의 매시·피해 부분. 매시 미터는 {@link LightningMash}. 피해: 사거리 f23 안의 모든 대상
	 * (0x1DAA20 이 반경 f23·16 안 전투자 수집, 개별 판정 거리 &lt; (f23 + 대상 반지름)·16) 에 대상별 타이머로
	 * 초당 "Damage Freq" (short, 12) 번, 1회 피해 = DPS · (1 + 연타율·(Mash Damage Scale − 1)) / Damage Freq (0x195718).
	 * 엔진은 대상 반지름 대신 사거리 ×2 [근사, 기존], 타이머는 하나.
	 */
	private void lightning(Combatant c, devple.gow.engine.input.Input in, java.util.List<? extends Hittable> ts, float dt) {
		String clip = c.clipName();
		boolean on = clip != null && clip.toLowerCase().contains("lightningloop");
		String node = "/Player/Lightning/";
		int st = stage(3);
		String sp = "Stage " + st + " ";
		// 스크립트 수명 (0x1948F0: 첫 인스턴스가 0x3368B4 = 0 으로 시작, 매 프레임 += 1/60, ≥ Stage Duration 이면
		// sub_001E1AB0 = combatant +0x1AC |= 0x400 → AIRLIGHTNINGLOOP 의 "! AUTO → LIGHTNINGEXIT" (flags 0x800) 가 열린다) [확실]
		boolean script = c.activeScript(SCR_LIGHTNING) != null;
		if (script && !lightningScript) {
			lightningTime = 0;
		}
		lightningScript = script;
		if (script) {
			lightningTime += dt;
			if (lightningTime >= tw.f(node, sp + "Duration", 1f)) {
				c.setStateBit(Combatant.STATE_CHARGED, true);
			}
		}
		if (on && !lightningOn) {
			mash.start();
			acc = 0;
		}
		lightningOn = on;
		if (!on) {
			mashRate = 0;
			acc = 0;
			return;
		}
		boolean allow = (tw.i(node, sp + "Allow Mashing", 0) & 0xFF) != 0;
		float max = tw.f(node, sp + "Mashometer Max", 1f);
		mashRate = mash.update(allow, tw.f(node, sp + "Mashometer Drain", 1f), max,
				in != null && in.pressed(devple.gow.engine.input.PadButton.CIRCLE), dt);
		float reach = tw.f(node, sp + "Reach", 5f) * (1 + mashRate * (tw.f(node, sp + "Mash Reach Scale", 1f) - 1));
		float dps = tw.f(node, sp + "Damage Per Second", 30f) * (1 + mashRate * (tw.f(node, sp + "Mash Damage Scale", 1f) - 1));
		lightningReach = reach;
		int freq = Math.max(1, tw.i16(node, "Damage Freq", 12));
		float tick = 1f / freq;
		acc += dt;
		if (acc < tick) {
			return;
		}
		acc -= tick;
		Vector3f p = c.owner().position();
		java.util.List<? extends Hittable> list = ts.isEmpty() && c.target() != null ? java.util.List.of(c.target()) : ts;
		for (Hittable t : list) {
			if (t == null || t.invulnerable() || t.hp() <= 0 || t.position().distance(p) > reach * UNIT * 2) {
				continue;
			}
			Vector3f away = new Vector3f(t.position()).sub(p);
			away.y = 0;
			if (away.lengthSquared() > 1e-6f) {
				away.normalize();
			}
			away.mul(-tw.f(node, "Ground Impulse Away", -500f));
			t.receive(LIGHTNING_HIT, c, dps * tick, away);
			sink.effect("goLightningSingleBolt", t.position()); // 대상에 내리꽂는 가지 (sub_00195B00 근처 [추정])
		}
	}

	/** 번개 피격 판정 (반응 종류 0). */
	private static final Attack LIGHTNING_HIT = new Attack(0, 0, new float[9], 0, 1, -1, 0, 0, 0, 0, 0, 0);
}
