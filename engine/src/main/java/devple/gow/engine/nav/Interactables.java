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

	public enum Kind { CSM, CHEST, PUSH, LEVER, LIFT }

	/** CSM 애니 이름 TWK 키 (해시): 영웅 동작 / 오브젝트 동작 (0x21508C / 0x2150C4). */
	public static final int CSM_HERO_ANIM = 0xb50a547c, CSM_HERO_ANIM2 = 0xb50a547d, CSM_OBJECT_ANIM = 0xfea292e6;
	/** TAndF 영웅 애니 (잡기 liftdoor1 / 들기 liftdoor2) 키 해시 (0x2136D4/0x2136F0). */
	public static final int TANDF_GRAB_ANIM = 0xff5f5921, TANDF_LIFT_ANIM = 0xdc77f412;

	public static final class Item {
		public final Kind kind;
		public final String owner;
		public final Vector3f pos;
		public final int template;
		/** CSM/CHEST = 과제 id (1023 인자 1), PUSH/LEVER = 붙잡기 id (1048/1049 인자 0). */
		public final int id;
		public final String heroAnim, finishAnim, label;
		public final float radius;
		/** 진행률 0..1 (CSM/CHEST/LEVER) = {@link #task} 의 애니 위치 / 길이. */
		public float progress;
		public int lastPercent = -1;
		public boolean done;
		/** 스크립트 Vis 로 숨겨짐 (Visible 2) — 잡을 수 없다 (예 PAND00C 엘리베이터 레버는 고리 퍼즐 205 뒤 LeverOn). */
		public boolean hidden;
		/** 오브젝트 애니 길이 (초): 과제 진행률의 분모 (원작 0x215928 f13). 애니를 못 찾으면 1 초 [근사]. */
		public float length = 1f;
		private TaskProgress task;
		/** 진행 속도 (붙음 / 떨어짐) 와 연타 유지 시간 (LIFT = TAndF Acc / −Dec / TF Time, 그 밖은 1 / −4 / 0.6). */
		public float engagedSpeed = TaskProgress.ENGAGED_SPEED, rewindSpeed = TaskProgress.REWIND_SPEED, mashWindow = 0.6f;

		/** 과제 진행 상태 (CSM/CHEST = 과제 id, LEVER = PushPull id 로 1023 을 보낸다). */
		public TaskProgress task() {
			if (task == null) {
				task = new TaskProgress(id, length, engagedSpeed, rewindSpeed);
			}
			return task;
		}

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
			String anm = null;
			for (String ref : p.object().refs()) {
				BallHull h = lv.hulls.get(ref);
				if (h != null && h.kind == 0) {
					r = Math.max(12, Math.min(64, h.sphere[3] * p.instance().scale()));
				}
				if (ref.startsWith("ANM_")) {
					anm = ref;
				}
			}
			switch (b.type()) {
				case "IO_CSM" -> {
					String node = String.format("/TweakTemplates/CSM/%03d/", b.template());
					String anim = tw != null ? tw.sHash(node, CSM_HERO_ANIM, "doorKick") : "doorKick";
					String label = tw != null ? tw.s(node, "Instance Name", "CSM") : "CSM";
					Item it = new Item(Kind.CSM, p.instance().name(), pos, b.template(), b.id(), anim, null, label, r);
					it.length = animLength(lv, anm, tw != null ? tw.sHash(node, CSM_OBJECT_ANIM, null) : null, it.length);
					out.items.add(it);
				}
				case "PushPull" -> {
					String node = String.format("/TweakTemplates/Push/%03d/", b.template());
					String label = tw != null ? tw.s(node, "Instance Name", "Push") : "Push";
					String l = label.toLowerCase(Locale.ROOT);
					boolean lever = l.contains("crank") || l.contains("lever") || l.contains("leever") || l.contains("puller");
					Item it = new Item(lever ? Kind.LEVER : Kind.PUSH, p.instance().name(), pos, b.template(), b.id(),
							lever ? (l.contains("crank") ? "crankIdle" : "leverpull") : "pushIdle", null, label, r);
					if (lever) {
						it.length = animLength(lv, anm, null, it.length);
					}
					out.items.add(it);
				}
				case "IO_TandF" -> {
					// 들어 올리는 문·함정문 (41 문서 §IO_TandF): R1 로 잡고 연타 — 연타 중 Acc, 멈추면 −Dec 로 내려온다 [추정: 속도 해석·입력]
					String node = String.format("/TweakTemplates/TAndF/%03d/", b.template());
					String label = tw != null ? tw.s(node, "Instance Name", "TAndF") : "TAndF";
					String lift = tw != null ? tw.sHash(node, TANDF_LIFT_ANIM, "liftdoor2") : "liftdoor2";
					Item it = new Item(Kind.LIFT, p.instance().name(), pos, b.template(), b.id(), lift, null, label, r);
					it.length = animLength(lv, anm, null, 0);
					if (it.length < 0.05f) {
						// 문 ANM 은 길이 0 (오브젝트 트랙만) → TAndF #fe467fe6 (1.5) 를 들어 올리는 시간으로 [추정]
						it.length = tw != null ? Math.max(0.1f, Float.intBitsToFloat(tw.rawHash(node, 0xfe467fe6).orElse(Float.floatToIntBits(1.5f)))) : 1.5f;
					}
					if (tw != null) {
						it.engagedSpeed = tw.f(node, "Acc", 1f);
						it.rewindSpeed = -tw.f(node, "Dec", 20f);
						it.mashWindow = Math.max(0.1f, tw.f(node, "TF Time", 0.25f) * 2);
					}
					out.items.add(it);
				}
				default -> {
					// Breakable: game/Breakables
				}
			}
		}
		return out;
	}

	/**
	 * 오브젝트 애니 길이: 이름이 맞는 act (CSM TWK #fea292e6, 예 쌍발차기 문 "Default" 0.9 초), 없으면 가장 긴 act.
	 * 길이 0 인 act 만 있으면 0 (DEST03 뿔피리 — 붙자마자 완료). ANM 이 없으면 def.
	 */
	static float animLength(LevelData lv, String anm, String act, float def) {
		if (anm == null) {
			return def;
		}
		try {
			devple.gow.tools.anim.AnimationSet set = devple.gow.tools.anim.AnimationSet.load(lv.wad, anm);
			float best = -1;
			for (devple.gow.tools.anim.Animation a : set.all()) {
				if (act != null && a.name.equalsIgnoreCase(act)) {
					return a.duration;
				}
				best = Math.max(best, a.duration);
			}
			return best >= 0 ? best : def;
		} catch (RuntimeException ex) {
			return def;
		}
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
			if (it.done && it.kind != Kind.PUSH || it.hidden) {
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
