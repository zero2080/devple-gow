package devple.gow.engine.nav;

import devple.gow.engine.game.Tweaks;
import devple.gow.tools.level.BallHull;
import devple.gow.tools.level.GoBehavior;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * R1 상황 버튼으로 다루는 레벨 오브젝트 (38 문서 §3). 레벨의 GO 비헤이비어 ({@link GoBehavior}) 와 Creator "chest" 에서 만든다.
 * <table>
 * <tr><td>CSM</td><td>IO_CSM: 연타로 진행률을 올리는 동작 (쌍발차기 문, 선장실 문, 책). 진행률 % 가 바뀔 때마다 과제 이벤트 1023 (인자 0 = %, 인자 1 = 과제 id)</td></tr>
 * <tr><td>CHEST</td><td>상자 (Creator Name0 "chest", unk5 = CSM 템플릿, unk6 = 과제 id): chestGrab → 연타 → chestOpen, 100 % 에 오브</td></tr>
 * <tr><td>PUSH</td><td>PushPull 상자류: 붙잡기 1048 / 놓기 1049 (인자 0 = id), 상자와 그 Marker(그룹 = Marker Id) 가 같이 움직인다</td></tr>
 * <tr><td>LEVER</td><td>PushPull 중 TWK Push Instance Name 이 Crank/Lever/Puller 인 것: 애니 한 번 = 붙잡기 + 놓기 [근사]</td></tr>
 * </table>
 */
public final class Interactables {

	public enum Kind { CSM, CHEST, PUSH, LEVER }

	/** CSM 애니 이름 TWK 키 (해시): 영웅 동작 / 오브젝트 동작 (0x21508C / 0x2150C4). */
	public static final int CSM_HERO_ANIM = 0xb50a547c, CSM_HERO_ANIM2 = 0xb50a547d, CSM_OBJECT_ANIM = 0xfea292e6;

	public static final class Item {
		public final Kind kind;
		public final String owner;
		public final Vector3f pos;
		public final int template;
		/** CSM/CHEST = 과제 id (1023 인자 1), PUSH/LEVER = 붙잡기 id (1048/1049 인자 0). */
		public final int id;
		public final String heroAnim, finishAnim, label;
		public final float radius;
		/** 진행률 0..1 (CSM/CHEST). */
		public float progress;
		public int lastPercent = -1;
		public boolean done;

		public Item(Kind kind, String owner, Vector3f pos, int template, int id, String heroAnim, String finishAnim, String label, float radius) {
			this.kind = kind;
			this.owner = owner;
			this.pos = pos;
			this.template = template;
			this.id = id;
			this.heroAnim = heroAnim;
			this.finishAnim = finishAnim;
			this.label = label;
			this.radius = radius;
		}

		@Override
		public String toString() {
			return kind + " " + owner + " (" + label + ", 템플릿 " + template + ", id " + id + ")";
		}
	}

	private final List<Item> items = new ArrayList<>();

	public List<Item> items() {
		return items;
	}

	public Item add(Item it) {
		items.add(it);
		return it;
	}

	/** 레벨 GO 비헤이비어로 만든다. tw = 레벨 TWK (CSM/Push 템플릿). */
	public static Interactables of(LevelData lv, Tweaks tw) {
		Interactables out = new Interactables();
		for (LevelLayout.Placement p : lv.layout.placements()) {
			GoBehavior b = lv.behaviors.get(p.instance().name());
			if (b == null) {
				continue;
			}
			float[] w = p.jointWorld()[0];
			Vector3f pos = new Vector3f(w[12], w[13], w[14]);
			float r = 24;
			for (String ref : p.object().refs()) {
				BallHull h = lv.hulls.get(ref);
				if (h != null && h.kind == 0) {
					r = Math.max(12, Math.min(64, h.sphere[3] * p.instance().scale()));
				}
			}
			switch (b.type()) {
				case "IO_CSM" -> {
					String node = String.format("/TweakTemplates/CSM/%03d/", b.template());
					String anim = tw != null ? tw.sHash(node, CSM_HERO_ANIM, "doorKick") : "doorKick";
					String label = tw != null ? tw.s(node, "Instance Name", "CSM") : "CSM";
					out.items.add(new Item(Kind.CSM, p.instance().name(), pos, b.template(), b.id(), anim, null, label, r));
				}
				case "PushPull" -> {
					String node = String.format("/TweakTemplates/Push/%03d/", b.template());
					String label = tw != null ? tw.s(node, "Instance Name", "Push") : "Push";
					String l = label.toLowerCase(Locale.ROOT);
					boolean lever = l.contains("crank") || l.contains("lever") || l.contains("leever") || l.contains("puller");
					out.items.add(new Item(lever ? Kind.LEVER : Kind.PUSH, p.instance().name(), pos, b.template(), b.id(),
							lever ? (l.contains("crank") ? "crankIdle" : "leverpull") : "pushIdle", null, label, r));
				}
				default -> {
					// IO_TandF / Breakable: 아직 없음
				}
			}
		}
		return out;
	}

	/** Creator "chest" (unk5 = CSM 템플릿, unk6 = 과제 id [추정: ATHN01D Chest4 unk6 = 1, ChestMessage 가 1023 인자 1 == 1 을 기다림]). */
	public Item addChest(String owner, Vector3f pos, int csmTemplate, int taskId, Tweaks tw) {
		String node = String.format("/TweakTemplates/CSM/%03d/", csmTemplate);
		String grab = tw != null ? tw.sHash(node, CSM_HERO_ANIM, "chestGrab") : "chestGrab";
		String open = tw != null ? tw.sHash(node, CSM_HERO_ANIM2, "chestOpen") : "chestOpen";
		if (open == null || open.isEmpty() || Character.isDigit(open.charAt(0))) {
			open = "chestOpen";
		}
		String label = tw != null ? tw.s(node, "Instance Name", "chest") : "chest";
		return add(new Item(Kind.CHEST, owner, pos, csmTemplate, taskId, grab, open, label, 20));
	}

	/** p 에서 수평 반경(오브젝트 반지름 + reach) 안, 높이 차 64 이내의 가장 가까운 것 (끝난 CSM/상자 제외). */
	public Item nearest(Vector3f p, float reach) {
		Item best = null;
		float bd = Float.MAX_VALUE;
		for (Item it : items) {
			if (it.done && it.kind != Kind.PUSH) {
				continue;
			}
			float d = (float) Math.hypot(it.pos.x - p.x, it.pos.z - p.z) - it.radius;
			if (d <= reach && Math.abs(it.pos.y - p.y) < 64 && d < bd) {
				bd = d;
				best = it;
			}
		}
		return best;
	}

	public Item byOwner(String owner) {
		for (Item it : items) {
			if (it.owner.equalsIgnoreCase(owner)) {
				return it;
			}
		}
		return null;
	}
}
