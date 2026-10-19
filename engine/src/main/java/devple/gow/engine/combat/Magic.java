package devple.gow.engine.combat;

import devple.gow.engine.game.PlayerState;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.game.Weapon;
import devple.gow.tools.combat.CombatFile.Attack;
import org.joml.Vector3f;

/**
 * 마법 비용과 효과 (10 문서 §10). 수치는 TWK {@code /Player/<마법>/Stage n ...}.
 * 현재 구현: 포세이돈의 분노(Lightning) — 루프 기술(AIRLIGHTNINGLOOP) 동안 Reach 안의 대상에게 초당 피해.
 * 메두사/제우스/하데스는 비용만 (효과 미구현).
 */
public final class Magic {

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
	}

	private int stage(int magicId) {
		Weapon w = Weapon.ofItem(magicId);
		return w == null ? 0 : Math.min(2, player.level.get(w));
	}

	/** 시전 비용 (HasMagic 기준값). */
	public float cost(int magicId) {
		String node = "/Player/" + NODE[magicId] + "/";
		int st = stage(magicId);
		return switch (magicId) {
			case 4 -> tw.f(node, "Stage " + st + " Drain", 12.5f) * 0.05f;
			case 6 -> 75f; // Num Dead Guys × Magic Drain (10 문서 §10)
			default -> tw.f(node, "Stage " + st + " Magic Drain", 33f);
		};
	}

	public boolean has(int magicId) {
		return magicId >= 3 && magicId <= 6 && player.mp >= cost(magicId);
	}

	public void use(int magicId) {
		player.mp = Math.max(0, player.mp - cost(magicId));
	}

	/** 매 틱: 번개 루프 중이면 범위 피해. */
	public void update(Combatant c, float dt) {
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
