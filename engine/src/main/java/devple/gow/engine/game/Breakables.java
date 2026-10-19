package devple.gow.engine.game;

import devple.gow.engine.combat.Combatant;
import devple.gow.engine.script.ScriptActor;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.level.BallHull;
import devple.gow.tools.level.GoBehavior;
import devple.gow.tools.level.GoInstance;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadTag;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 부서지는 물체 (GO 비헤이비어 "Breakable", 템플릿 = /TweakTemplates/Brkb/&lt;n&gt;/, 41 문서).
 * <ul>
 *   <li>항아리·문 같은 레벨 오브젝트. 영웅 공격(피해 &gt; 0 창)이 물체 구(CDV_&lt;오브젝트&gt; 의 공, 없으면 경계 중심 반지름 24)에 닿으면
 *       Brkb "Hit Points" 만큼 맞으면 부서진다 (대부분 1).</li>
 *   <li>부서지면 렌더에서 빼고, 그 GO 의 Marker 오브젝트(대개 Id 101)를 파괴 → DestructionSensor (예 ARENA06 "all broken" 36 개,
 *       ATHN03A 철문)가 반응한다.</li>
 * </ul>
 * 파편 모델(*Brk*_0/1)·파편 애니·오브 방출(OrbEmitter)·Fade Time 은 미구현 [근사: 즉시 사라짐].
 */
public final class Breakables {

	/** 물체 하나. */
	public static final class Item {
		public final String owner;
		public final Vector3f center;
		public final float radius;
		public float hp;
		boolean broken;
		final Set<Long> hits = new HashSet<>();

		Item(String owner, Vector3f center, float radius, float hp) {
			this.owner = owner;
			this.center = center;
			this.radius = radius;
			this.hp = hp;
		}

		public boolean broken() {
			return broken;
		}
	}

	private final List<Item> items = new ArrayList<>();

	public Breakables(LevelData data, Tweaks tweaks) {
		for (GoBehavior b : data.behaviors.values()) {
			if (!b.type().equals("Breakable")) {
				continue;
			}
			GoInstance g = data.layout.instances.stream().filter(x -> x.name().equalsIgnoreCase(b.owner())).findFirst().orElse(null);
			if (g == null) {
				continue;
			}
			float radius = 24;
			Vector3f center = new Vector3f(g.bound()[0], g.bound()[1], g.bound()[2]);
			for (WadTag t : data.wad.tags()) {
				if (t.hasData() && t.name().equalsIgnoreCase("CDV_" + g.object())) {
					try {
						BallHull h = new BallHull(t.name(), t.data());
						float r = 0;
						for (float[] ball : h.balls) {
							r = Math.max(r, (float) Math.hypot(Math.hypot(ball[0], ball[2]), 0) + ball[3]);
						}
						if (r > 1) {
							radius = r * Math.max(0.01f, g.scale());
						}
					} catch (RuntimeException ignored) {
						// 형식이 다른 CDV: 기본 반지름
					}
				}
			}
			String node = String.format(Locale.ROOT, "/TweakTemplates/Brkb/%03d/", b.template());
			float hp = tweaks != null ? tweaks.f(node, "Hit Points", 1f) : 1f;
			items.add(new Item(b.owner(), center, radius, Math.max(1, Math.round(hp)))); // 타수 (0.9999991 처럼 하위 비트 잡음 → 반올림)
		}
	}

	public List<Item> items() {
		return items;
	}

	/**
	 * 영웅 공격 판정. 부서진 물체마다 onBreak(소유 GO 이름) 를 부르고, 그 GO 의 Marker 를 파괴한다.
	 */
	public void update(Combatant hero, ScriptWorld world, Consumer<String> onBreak) {
		if (hero == null || !hero.active()) {
			return;
		}
		for (Item it : items) {
			if (it.broken) {
				continue;
			}
			var a = hero.strikes(it.center, it.radius);
			if (a == null || !it.hits.add(((long) hero.moveSerial() << 16) | a.index())) {
				continue;
			}
			it.hp -= 1; // 물체는 피해량이 아니라 타수 (Brkb Hit Points 1 = 한 번) [추정]
			if (it.hp > 0) {
				continue;
			}
			it.broken = true;
			if (world != null) {
				for (ScriptActor m : List.copyOf(world.markers(it.owner))) {
					world.destroy(m);
				}
			}
			onBreak.accept(it.owner);
		}
	}
}
