package devple.gow.engine.game;

import devple.gow.engine.fx.EffectSystem;
import devple.gow.engine.nav.Interactables;
import devple.gow.engine.nav.Platforms;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.fx.EffectLibrary;
import devple.gow.tools.level.BallHull;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.wad.WadTag;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Creator 가 만든 R2 상자의 몸 (38 §21): R_PERMK 오브젝트 {@code chest} (GO {@code gochest}, IO_CSM) 를 소유 GO 행렬에 둔다.
 * <ul>
 *   <li>모델·애니·이미터 = 이펙트 GO 와 같은 구성 ({@link EffectSystem}) — act chestParts (0 초 정지) → 붙잡는 동안 chestGrab 을 과제 시간에
 *       (뚜껑 덜컹·반짝임, 원작 ANICSM1 #fea292e6 = 오브젝트 애니 = 진행) → 완료 chestOpen (#fea292e7, 0x215DF0 끝 → 오브젝트 애니 상태 1).</li>
 *   <li>종류 = CSM "Chest Type" — 관절 가시성 표 0x29F340 {magic 3, magic2 3, health 2, health2 2, power 4, power2 4, plain 1} 에서
 *       보이는 종류와 같은 관절만 보임 (0x215AF0, 관절 +0x54 표) [확실: 코드]. 숨은 관절에 붙은 이미터 (FXC 노드 헤더 +0x08 = 관절) 는 끈다 [추정].</li>
 *   <li>충돌 = CDV_chest (평면 hull 6 장, 반지름 20), 손잡이 = 관절 handle1.</li>
 * </ul>
 */
public final class ChestProps {

	/** 0x29F340: 관절 이름 → 그 관절이 보이는 상자 종류. */
	static final String[] TYPE_JOINTS = {"magic", "magic2", "health", "health2", "power", "power2", "plain"};
	static final int[] TYPE_OF_JOINT = {3, 3, 2, 2, 4, 4, 1};
	/** 원작 Creator Name0 "chest" → 오브젝트 chest (GO gochest). */
	public static final String OBJECT = "chest";

	/** 상자 하나. */
	public static final class Prop {
		public final Interactables.Item item;
		public final EffectSystem.Effect effect;
		final Skeleton skeleton;
		float cycleTimer;
		int shownApplied = Integer.MIN_VALUE;
		/** 아이템 상자: chestOpen 이 끝난 뒤 남은 지연 (NaN = 아직), 준 뒤 참. */
		float itemTimer = Float.NaN;
		boolean itemGiven;

		Prop(Interactables.Item item, EffectSystem.Effect effect, Skeleton skeleton) {
			this.item = item;
			this.effect = effect;
			this.skeleton = skeleton;
		}
	}

	private final List<Prop> props = new ArrayList<>();
	/**
	 * 아이템 상자 (CSM +0x9C &gt; 0) 의 아이템을 줄 때 (원작 0x215F34–0x21602C: 상자 오브젝트 chestOpen 이 끝난 프레임에 타이머 = −(+0xA0),
	 * 1/60 씩 올라 0 을 넘으면 0x1D9700) [확실]. 게임 씬이 개수·카드를 처리한다.
	 */
	public java.util.function.Consumer<Interactables.Item> onItem = it -> { };

	public List<Prop> props() {
		return props;
	}

	/**
	 * 상자 몸을 만든다.
	 *
	 * @param inst 소유 GO 인스턴스 행렬 ({@code GoInstance.matrix()}, 행벡터)
	 * @param ps   충돌 (null 이면 생략)
	 * @return 몸 (이펙트 GO chest 가 없으면 null — 손잡이·충돌 없이 중심 반경 판정만)
	 */
	public Prop spawn(Interactables.Item it, float[] inst, EffectSystem fx, Platforms ps, Tweaks tw) {
		if (it == null || fx == null) {
			return null;
		}
		EffectLibrary.EffectDef def = fx.library().resolve(OBJECT);
		if (def == null || def.skeleton() == null) {
			return null;
		}
		Skeleton sk = def.skeleton();
		String node = String.format(Locale.ROOT, "/TweakTemplates/CSM/%03d/", it.template);
		float[][] jw = LevelLayout.jointWorld(sk, inst);
		Interactables.handles(it, sk, jw, tw, node);
		Animation grab = def.act(it.heroAnim);
		if (grab != null && grab.duration > 0) {
			it.relength(grab.duration); // 원작 ANICSM1 = 상자 오브젝트 애니 chestGrab 1.967 초 (영웅 chestGrab 과 같은 길이)
			it.lengthKnown = true;
		}
		BallHull cdv = cdv(def);
		if (ps != null && cdv != null) {
			ps.add(it.owner, sk, inst, cdv);
		}
		EffectSystem.Effect e = fx.play(OBJECT, EffectSystem.Anchor.of(new Matrix4f().set(inst)), Float.POSITIVE_INFINITY);
		if (e == null) {
			return null;
		}
		e.hold = true;
		e.scale = 1f / Math.max(def.scale(), 1e-3f); // 인스턴스 스케일은 inst 에 이미 들어 있다
		Prop p = new Prop(it, e, sk);
		apply(p);
		props.add(p);
		float[] h = it.handles.isEmpty() ? null : it.handles.getFirst();
		System.out.printf(Locale.ROOT, "상자 %s: CSM %03d 종류 %d, 손잡이 %s, 충돌 %s%n", it.owner, it.template, it.chestType,
				h == null ? "없음" : String.format(Locale.ROOT, "(%.0f, %.0f, %.0f) 축 (%.2f, %.2f)", h[0], h[1], h[2], h[3], h[4]), cdv != null);
		return p;
	}

	/** 오브젝트의 CDV_ (평면 hull). */
	static BallHull cdv(EffectLibrary.EffectDef def) {
		String want = "CDV_" + def.object();
		for (WadTag t : def.wad().tags()) {
			if (t.hasData() && t.name().equalsIgnoreCase(want)) {
				try {
					BallHull h = new BallHull(t.name(), t.data());
					return h.kind == 0 && !h.hulls.isEmpty() ? h : null;
				} catch (RuntimeException ex) {
					return null;
				}
			}
		}
		return null;
	}

	/** 종류 t 에서 숨길 관절 (0x215AF0: 표의 관절은 종류가 같을 때만 보임). */
	static boolean[] mask(Skeleton sk, int t) {
		boolean[] m = new boolean[sk.size()];
		for (int i = 0; i < sk.size(); i++) {
			String n = sk.joints.get(i).name();
			for (int k = 0; k < TYPE_JOINTS.length; k++) {
				if (TYPE_JOINTS[k].equalsIgnoreCase(n)) {
					m[i] = TYPE_OF_JOINT[k] != t;
				}
			}
		}
		return m;
	}

	/** 다음 순환 종류 (0x215BB0: (지금 − 1) mod 3 + 2 → 2→3→4→2, 처음 5 → 3). */
	static int nextCycle(int shown) {
		return Math.floorMod(shown - 1, 3) + 2;
	}

	private static void apply(Prop p) {
		int t = p.item.chestShown;
		if (t == p.shownApplied) {
			return;
		}
		p.shownApplied = t;
		boolean[] m = mask(p.skeleton, t);
		p.effect.jointMask = m;
		p.effect.emitterOff = ed -> {
			int j = ed.fxc().header().length > 0 ? ed.fxc().header()[0] : -1;
			return j >= 0 && j < m.length && m[j];
		};
	}

	/** 한 틱: 종류 순환, act = 진행 (chestParts 정지 → chestGrab 과제 시간 → chestOpen 재생). */
	public void update(float dt) {
		for (Prop p : props) {
			Interactables.Item it = p.item;
			EffectSystem.Effect e = p.effect;
			if (e.done()) {
				continue;
			}
			e.setLife(Float.POSITIVE_INFINITY);
			boolean started = it.taskStarted() && it.task().time > 0;
			if (it.chestType == 5 && !it.done && !started) {
				// 0x215BB0: 붙잡히지 않은 동안 타이머 −1/60, 0 아래면 = #70f23994 (+0xA4) 로 되돌리고 종류를 넘긴다
				p.cycleTimer -= dt;
				if (p.cycleTimer < 0) {
					p.cycleTimer = it.chestCycle;
					it.chestShown = nextCycle(it.chestShown);
				}
			}
			apply(p);
			if (it.done) {
				if (!"chestOpen".equalsIgnoreCase(e.actName())) {
					e.setAct(it.finishAnim != null && e.def.act(it.finishAnim) != null ? it.finishAnim : "chestOpen");
				}
				e.hold = false;
				if (it.chestItem > 0 && !p.itemGiven && e.actTime() >= e.actDuration()) {
					if (Float.isNaN(p.itemTimer)) {
						p.itemTimer = it.chestItemDelay;
					}
					p.itemTimer -= dt;
					if (p.itemTimer < 0) {
						p.itemGiven = true;
						onItem.accept(it);
					}
				}
			} else if (started) {
				e.setAct(it.heroAnim != null && e.def.act(it.heroAnim) != null ? it.heroAnim : "chestGrab");
				e.hold = true;
				e.setActTime(it.task().time);
			} else {
				e.setAct(e.def.anim() != null ? e.def.anim().name : "chestParts");
				e.hold = true;
				e.setActTime(0);
			}
		}
	}

	/** 레벨을 떠날 때. */
	public void clear() {
		for (Prop p : props) {
			p.effect.stop();
		}
		props.clear();
	}
}
