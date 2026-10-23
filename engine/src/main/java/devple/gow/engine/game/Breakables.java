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
 * Brkb 템플릿 (생성자 0x1DD768, 0x3C 바이트) [확실: 키·오프셋]:
 * <pre>
 *   +0x18 s16 Type (0 보통, 2 석상, 3 재부착)      +0x1A s16 #cb865a1c
 *   +0x1C f32 Hit Points  +0x20 f32 #c3441d8c (3.0)  +0x24 f32 Fade Time
 *   +0x28 s16 #b9a0d2ad = OrbEmitter 템플릿 (900 "small obj break", −1 = 없음; 0x1DDE70 → 0x2258D8)
 *   +0x2A s16 #49322803 = 다른 경로(마법?)의 OrbEmitter  +0x2C/+0x2E s16 = Bonus 템플릿 (0x1DDF34 → 0x1D4DB8)
 *   +0x30 비트: 0x1 #93191d85 (파편 물리 객체 0xC10, 0x1DE218), 0x2 Force Feedback
 * </pre>
 * 판정 (0x1DE4D8 → 0x1DDDF0): hp −= 공격 레코드 +0x19 (피해 바이트), ≤ 0 이면 이벤트 1009, OrbEmitter 방출, 그 뒤 0x1DDF70 이 GO 애니를
 * 0초부터 재생하고 이벤트 1018. 물체 GO 는 이펙트 GO 와 같은 구성 (ANM 파편 애니 + MDL + FXC 먼지 + SEM 소리) → {@code MagicFx.breakObject}.
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
		/** Brkb 템플릿 노드, OrbEmitter 템플릿 (−1 없음), Fade Time (초), 인스턴스 월드 행렬 (행벡터). */
		public String template;
		public int orbEmitter = -1;
		public float fadeTime;
		/** Brkb +0x20 #c3441d8c: 파괴 뒤 페이드 시작까지 (초, 0x1DE278). */
		public float fadeDelay = 3;
		/** Brkb +0x30 bit 0x1 #93191d85: 파편 물리 객체 (0x1DE218). */
		public boolean debrisPhysics;
		public float[] matrix;

		Item(String owner, Vector3f center, float radius, float hp) {
			this.owner = owner;
			this.center = center;
			this.radius = radius;
			this.hp = hp;
		}

		/** OrbEmitter 노드 (없으면 null). */
		public String orbNode() {
			return orbEmitter >= 0 ? String.format(Locale.ROOT, "/TweakTemplates/OrbEmitter/%03d/", orbEmitter) : null;
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
						if (h.balls.isEmpty() && !h.hulls.isEmpty()) {
							// 평면 hull CDV (ATHN01B 침상·쓰러진 기둥): 경계 구 반지름, 중심 = hull 관절 월드의 hull 중심 [근사: 판정 = 구, 원작은 hull]
							r = h.sphere[3];
							hullCenter(data, g, h, center);
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
			Item it = new Item(b.owner(), center, radius, Math.max(1, Math.round(hp))); // 0.9999991 처럼 하위 비트 잡음 → 반올림
			it.template = node;
			it.fadeTime = tweaks != null ? tweaks.f(node, "Fade Time", 1f) : 1f;
			if (tweaks != null) {
				it.orbEmitter = (short) tweaks.rawHash(node, 0xB9A0D2AD).orElse(-1); // s16 (상위 2바이트는 덤프 잡음)
				it.fadeDelay = Float.intBitsToFloat(tweaks.rawHash(node, 0xC3441D8C).orElse(Float.floatToIntBits(3f)));
				it.debrisPhysics = (tweaks.rawHash(node, 0x93191D85).orElse(0) & 1) != 0; // 비트 필드 (하위 바이트)
			}
			it.matrix = g.matrix();
			items.add(it);
		}
	}

	/** hull 0 중심을 그 관절의 배치 월드 행렬로 (경계 중심은 몸 hull 과 15 가량 어긋난다: ATHN01B 침상). */
	private static void hullCenter(LevelData data, GoInstance g, BallHull h, Vector3f out) {
		for (var p : data.layout.placements()) {
			if (p.instance().name().equalsIgnoreCase(g.name())) {
				float[][] jw = p.jointWorld();
				if (jw.length == 0) {
					return;
				}
				float[] c = h.hulls.getFirst().center();
				float[] o = new float[3];
				devple.gow.tools.anim.Mat4.transformPoint(jw[Math.min(Math.max(h.hullJoint[0], 0), jw.length - 1)], c[0], c[1], c[2], o);
				out.set(o[0], o[1], o[2]);
				return;
			}
		}
	}

	public List<Item> items() {
		return items;
	}

	/** 소유 GO 이름 → 물체 (없으면 null). */
	public Item item(String owner) {
		for (Item it : items) {
			if (it.owner.equalsIgnoreCase(owner)) {
				return it;
			}
		}
		return null;
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
			it.hp -= Math.max(1, a.damage()); // 0x1DE5A0: 공격 레코드 +0x19 (피해 바이트) 만큼 [확실: 코드], 0 이하 피해는 1 [근사]
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
