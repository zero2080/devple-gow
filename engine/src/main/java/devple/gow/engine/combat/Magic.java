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
	private static final float TICK = 0.25f;

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
		lightning(c, dt);
	}

	private void lightning(Combatant c, float dt) {
		String clip = c.clipName();
		Hittable t = c.target();
		if (clip == null || !clip.toLowerCase().contains("lightningloop") || t == null || t.invulnerable()) {
			acc = 0;
			return;
		}
		acc += dt;
		if (acc < TICK) {
			return;
		}
		acc -= TICK;
		String node = "/Player/Lightning/";
		int st = stage(3);
		float reach = tw.f(node, "Stage " + st + " Reach", 5f) * UNIT * 2;
		Vector3f p = c.owner().position();
		if (t.position().distance(p) > reach) {
			return;
		}
		float dmg = tw.f(node, "Stage " + st + " Damage Per Second", 30f) * TICK;
		Vector3f away = new Vector3f(t.position()).sub(p).normalize()
				.mul(-tw.f(node, "Ground Impulse Away", -500f));
		t.receive(LIGHTNING_HIT, c, dmg, away);
	}

	/** 번개 피격 판정 (반응 종류 0). */
	private static final Attack LIGHTNING_HIT = new Attack(0, 0, new float[9], 0, 1, -1, 0, 0, 0, 0, 0, 0);
}
