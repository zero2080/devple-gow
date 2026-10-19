package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.asset.AnimatedModel;
import devple.gow.engine.game.Tweaks;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile.Attack;
import devple.gow.tools.wad.WadFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Random;

/**
 * 적 1체 = 원작 데이터(스켈레톤/애니/CombatFile/AI1 템플릿/결정 트리) + 원작 AI({@link AiBrain}, 17 문서).
 * <ul>
 *   <li>기술·피격 반응·사망: 적 CombatFile 그대로 — 원작과 같은 Combatant</li>
 *   <li>수치: TWK {@code /TweakTemplates/AI1/NNN/} ({@link AiTemplate})</li>
 *   <li>의사결정: TWK_DTre 결정 트리 + 공격 토큰 + 포위 이동 + BlockMod ({@link AiBrain}). 결정 트리가 없는 적
 *       (스크립트 구동 보스 등)만 예전 단순 규칙(접근 → 무작위 공격)으로 대체</li>
 * </ul>
 */
public final class EnemyFighter implements Hittable, CombatOwner {

	/** 회전 속도 (rad/s) [근사: 원작 0x1CE240 회전 보간 값 미확인]. */
	static final float TURN_RATE = 8f;

	private final Combatant combat;
	private final Animator anim;
	private final int classId;
	private final AiTemplate tmpl;
	private final AiBrain brain;
	private final Tweaks tw;
	private final float maxHp;
	private final float circleHp;
	private final float aiDamageMult;
	private final int[] attackMoves;
	private final String walkClip;
	private final String idleClip;
	private final float walkSpeed;
	/** 달리기 클립 (없으면 null) / 초당 이동량. 결정 트리 없는 적의 예전 규칙용. */
	private final String runClip;
	private final float runSpeed;
	/** 자신의 이동 상태 (지상/공중/벽/줄) — 결정 트리 조건 4, 분기 상태 마스크, Sold 속도 (32 문서). */
	private final EnemyNav nav;
	/** 지상 이동 클립 후보와 블렌드 축 값 (m/s, TWK &lt;클립&gt;_Cycle = ANM act +0x14). 속도에 가장 가까운 클립을 고른다 [추정]. */
	private final List<String> locoClips = new java.util.ArrayList<>();
	private final List<Float> locoParams = new java.util.ArrayList<>();
	/** Navigation 표 노드 (예 /Animation/goSKS/Navigation/) — 벽·줄 애니 이름. */
	private final String navNode;
	/** 모델 크기 = ScaleCenter/100 + ScaleRange/100 × (0.5 − rand) (0x1CEE00). */
	public final float scale;
	private final Random rnd;
	public final Vector3f pos = new Vector3f();
	public final Vector3f prevPos = new Vector3f();
	public float yaw;
	private float prevYaw;
	public float hp;
	/** 피격으로 내려갈 수 있는 최저 HP (보스: 1 = 스크립트/자기 피해로만 사망, 원작은 EV_DEATH 분기로 기절 등에 들어감). */
	public float hpFloor;
	/** 이 공격자에게서만 피해를 받는다 (null = 모두). ATHN08B 가족: 영웅 공격은 CombatFile 661 의 모든 반응 태그가 (block). */
	public java.util.function.Predicate<Combatant> damageFrom;
	/**
	 * 피해를 클래스가 직접 처리 (원작 Enemy1 하위 클래스의 vtable +0x40 TakeDamage 재정의, 예 BMI 0x1C1838: 배출구·갑옷 단계 누적).
	 * 있으면 {@link #applyDamage} 는 HP 를 건드리지 않고 이것만 부르며 잡기 원 통과는 없다.
	 */
	public java.util.function.Consumer<Float> damageHook;
	/** 모델 변형 (원작 모델 컴포넌트 +0x120 강제 LOD, 클래스 코드가 정함 — BMI 단계별 갑옷). −1 = 기본 (렌더러가 AnimatedModel.setVariant 로). */
	public int modelVariant = -1;
	private float think = 1f;
	private Hittable foe;
	private float deadTime;
	private static int spawnCount;

	/** 난수 씨앗 순번 초기화 (헤드리스 테스트가 앞선 테스트의 생성 수에 좌우되지 않게). */
	public static synchronized void resetSpawnCount() {
		spawnCount = 0;
	}
	// ---- 석화 (메두사의 시선, 35 문서 §2) ----
	/** 동결 게이지 최대 = Sold +0x26C (≥ 60000 면역), 기본 30 (0x1E8C40 초기값). */
	private final float freezeMax;
	private float freezeMeter;
	private boolean freezeHit;
	private final int stoneMove;
	/** 돌 지속 = AI1 #b5134762 (+0x40, ms). */
	private final float stoneTime;
	/** /GlobGame/Unfreeze Speed (u16 50) × 60 = 초당 회복량. */
	private final float unfreezeRate;
	private boolean stone;
	private float stoneElapsed;
	private boolean shattered;
	private Runnable onShatter = () -> { };

	/**
	 * @param ai1         AI1 템플릿 번호 (미노타우로스 Lv1 = 50)
	 * @param combatFiles 적 CombatFile 이름들 (TWK_CombatFile_050 ...)
	 */
	public EnemyFighter(WadFile wad, Tweaks tw, Skeleton sk, AnimationSet set, int ai1, List<String> combatFiles,
						int[] attackMoves, int blockMove, String idleClip, String walkClip, float walkSpeed) {
		this(wad, tw, sk, set, ai1, combatFiles, attackMoves, blockMove, idleClip, walkClip, walkSpeed, "/Animation/goMIN/");
	}

	public EnemyFighter(WadFile wad, Tweaks tw, Skeleton sk, AnimationSet set, int ai1, List<String> combatFiles,
						int[] attackMoves, int blockMove, String idleClip, String walkClip, float walkSpeed, String animNode) {
		this(wad, tw, sk, set, ai1, combatFiles, attackMoves, blockMove, idleClip, walkClip, walkSpeed, animNode, AiData.load(wad));
	}

	public EnemyFighter(WadFile wad, Tweaks tw, Skeleton sk, AnimationSet set, int ai1, List<String> combatFiles,
						int[] attackMoves, int blockMove, String idleClip, String walkClip, float walkSpeed, String animNode,
						AiData ai) {
		this.classId = ai1;
		this.tw = tw;
		this.tmpl = new AiTemplate(tw, ai1);
		this.maxHp = Math.max(tmpl.hitPoints, tmpl.maxHitPoints) & 0xFFFF;
		this.hp = tmpl.hitPoints & 0xFFFF;
		this.aiDamageMult = tmpl.damageMult;
		this.circleHp = tmpl.circleHp; // 65535 = 잡기 원 없음
		this.attackMoves = attackMoves;
		this.idleClip = idleClip;
		this.walkClip = walkClip;
		this.anim = new Animator(sk, set, tw, animNode);
		this.navNode = animNode + "Navigation/";
		// 이동 속도: 루프 이동 클립의 zeroJoint Z × 15fps (25 문서, 크라토스와 같은 규칙). 없으면 주어진 값
		float ws = clipSpeed(anim, walkClip);
		this.walkSpeed = ws > 1f ? ws : walkSpeed;
		String rc = null;
		for (String cand : new String[]{"runfront", "runforward", "run"}) {
			for (devple.gow.tools.anim.AnimationSet.Group g : set.groups) {
				for (devple.gow.tools.anim.Animation an : g.acts) {
					if (rc == null && an.hasSkeletalData() && an.name.toLowerCase(java.util.Locale.ROOT).startsWith(cand)) {
						rc = an.name;
					}
				}
			}
		}
		float rs = rc != null ? clipSpeed(anim, rc) : 0;
		this.runClip = rs > this.walkSpeed ? rc : null;
		this.runSpeed = rs;
		long seed = 7L + 7919L * (spawnCount++);
		this.rnd = new Random(seed);
		this.scale = tmpl.scaleCenter / 100f + tmpl.scaleRange / 100f * (0.5f - rnd.nextFloat());
		this.combat = new Combatant(new CombatData(wad, combatFiles, set), anim, this);
		this.nav = new EnemyNav(tmpl.moveCaps, ai != null ? ai.motion(tmpl.soldier) : null, tmpl.pauseMoveMs);
		collectLocoClips(set);
		DecisionTree tree = ai != null ? ai.tree(tmpl.decisionTree) : null;
		AiData.Soldier sold = ai != null ? ai.soldier(tmpl.soldier) : null;
		this.brain = new AiBrain(this, tmpl, tree, AiData.blockMod(tw, tmpl.blockMod), sold, seed);
		setDifficulty(1);
		anim.play(idleClip, true);
		AiData.Stone stn = ai != null ? ai.stone(tmpl.soldier) : null;
		this.freezeMax = stn != null ? stn.resist() : 30f;
		this.stoneMove = stn != null ? stn.move() : 160;
		this.freezeMeter = freezeMax;
		var sm = tw.rawHash(tmpl.node, 0xb5134762);
		this.stoneTime = (sm.isPresent() ? sm.getAsInt() : 5000) / 1000f;
		var us = tw.raw("/GlobGame/", "Unfreeze Speed");
		this.unfreezeRate = (us.isPresent() ? us.getAsInt() & 0xFFFF : 50) * 60f;
	}

	/** 난이도 0..3 (Easy..Impossible): "AI Recovery Time Mult" 를 공격 후 대기에 적용. */
	public void setDifficulty(int d) {
		float m = tw.f("/GlobGame/" + DamageRules.DIFFICULTY[Math.max(0, Math.min(3, d))] + "/", "AI Recovery Time Mult", 1f);
		brain.setRecoveryScale(() -> m);
	}

	public AiBrain brain() {
		return brain;
	}

	public AiTemplate template() {
		return tmpl;
	}

	public int ai1() {
		return classId;
	}

	public float maxHp() {
		return maxHp;
	}

	public Combatant combat() {
		return combat;
	}

	public Animator animator() {
		return anim;
	}

	public void setFoe(Hittable f) {
		foe = f;
		combat.setTarget(f);
		brain.setTarget(f);
	}

	/** 공격자 배율 = AI1 Damage Multiplier × (영웅 쪽 받는 피해 배율은 영웅이 적용). */
	public void setIncomingScale(java.util.function.Supplier<Float> heroIncoming) {
		combat.setDamageScale(() -> aiDamageMult * heroIncoming.get());
	}

	public void update(float dt) {
		prevPos.set(pos);
		prevYaw = yaw;
		if (stone) {
			// 돌: AI·전투 정지, 석화 자세(freezePose)만 끝까지 재생 후 유지. 지속 후 원상 복귀 (UnFreeze 0x1E8AF8)
			stoneElapsed += dt;
			var cur = anim.current();
			if (cur != null && anim.time() + dt < cur.duration) {
				anim.update(dt);
			}
			if (stoneElapsed >= stoneTime) {
				stone = false;
				freezeMeter = freezeMax;
			}
			if (nav.world() != null) {
				nav.update(pos, ZERO, null, true, dt); // 돌도 떨어진다 (벽·줄이면 붙은 채)
			}
			return;
		}
		if (!freezeHit && freezeMeter > 0 && freezeMeter < freezeMax) {
			freezeMeter = Math.min(freezeMax, freezeMeter + unfreezeRate * dt);
		}
		freezeHit = false;
		combat.update(null, dt);
		if (hp <= 0) {
			deadTime += dt;
			brain.update(dt);
			nav.drop(); // 죽으면 벽·줄에서 떨어진다
			if (nav.world() != null) {
				nav.update(pos, ZERO, null, true, dt);
			}
			anim.update(dt);
			return;
		}
		if (brain.tree != null) {
			brain.update(dt);
			boolean busy = combat.active();
			// 이동 = 공용 내비게이션 (지상 Sold 속도 / 벽 / 줄, EnemyNav). AI 는 스틱(brain.move)과 목표 점만 준다 (0x1CF9D0)
			// 벽·줄 위에서는 전투 중이면 목표 = 대상 자신 (DTre "get the hero!" / "rope attack!") [근사: 원작은 포위 칸을 투영]
			Hittable tg = brain.target();
			boolean clinging = nav.mode() == EnemyNav.Mode.WALL || nav.mode() == EnemyNav.Mode.ROPE;
			Vector3f goal = clinging && tg != null && brain.state() == AiBrain.State.COMBAT ? tg.position()
					: brain.hasGoal ? brain.goal : null;
			Boolean goalClinging = clinging && tg != null ? tg.locomotionState() == nav.conditionState() : null;
			nav.update(pos, brain.move, goal, goalClinging, busy, dt);
			if (!Float.isNaN(nav.faceYaw())) {
				yaw = nav.faceYaw();
			} else if (!busy && !Float.isNaN(brain.face)) {
				yaw = turn(yaw, brain.face, TURN_RATE * dt);
			}
			if (!busy) {
				locomotionAnim();
			}
		} else if (!combat.active() && foe != null && attackMoves.length > 0) {
			// 결정 트리 없는 적: 예전 단순 규칙 (접근 → 무작위 공격)
			Vector3f d = new Vector3f(foe.position()).sub(pos);
			float dist = (float) Math.hypot(d.x, d.z);
			yaw = (float) Math.atan2(-d.x, -d.z);
			if (dist > 60) {
				anim.play(walkClip, false);
				pos.add(d.x / dist * walkSpeed * dt, 0, d.z / dist * walkSpeed * dt);
			} else {
				anim.play(idleClip, false);
				if ((think -= dt) <= 0) {
					think = 0.8f + rnd.nextFloat();
					combat.request(attackMoves[rnd.nextInt(attackMoves.length)]);
				}
			}
		}
		if (combat.active() && combat.move().speed() == 0) {
			// 미니게임 기술: 진행은 QTE 게이지가 setProgress 로 지정
		} else if (combat.active() && combat.hitPause() <= 0) {
			anim.update(dt);
		} else if (!combat.active()) {
			anim.update(dt);
		}
	}

	/**
	 * 루프 이동 클립의 초당 이동량. zeroJoint Z × 15 (크라토스 규칙, 25 문서)가 0 이면(적 걷기 클립은 제자리 루프)
	 * 디딘 발(가장 낮은 발 관절)이 뒤로 미끄러지는 속도로 추정한다 [근사: 원작 적 이동 속도 출처 미분석].
	 */
	static float clipSpeed(Animator anim, String clip) {
		Skeleton sk = anim.skeleton();
		int zj = sk.indexOf("zeroJoint");
		devple.gow.tools.anim.Animation a = clip != null ? anim.find(clip) : null;
		if (a == null || !a.hasSkeletalData()) {
			return 0;
		}
		if (zj >= 0) {
			float z = Math.abs(a.sample(sk, 0, true).pos[zj][2]) * devple.gow.engine.anim.HeroController.ROOT_FPS;
			if (z > 1f) {
				return z;
			}
		}
		List<Integer> feet = new java.util.ArrayList<>();
		for (Skeleton.Joint j : sk.joints) {
			String n = j.name().toLowerCase(java.util.Locale.ROOT);
			if (n.contains("toe") || n.contains("metatarsal") || n.contains("foot") || n.contains("ankle")) {
				feet.add(j.index());
			}
		}
		if (feet.isEmpty() || a.duration <= 0) {
			return 0;
		}
		int n = 48;
		float dt = a.duration / n, slide = 0, time = 0;
		int prevFoot = -1;
		float prevZ = 0;
		for (int i = 0; i <= n; i++) {
			float[][] w = a.sample(sk, i * dt, true).world(sk);
			int low = feet.getFirst();
			for (int f : feet) {
				if (w[f][13] < w[low][13]) {
					low = f;
				}
			}
			if (low == prevFoot) {
				slide += Math.abs(w[low][14] - prevZ);
				time += dt;
			}
			prevFoot = low;
			prevZ = w[low][14];
		}
		return time > 0 ? slide / time : 0;
	}

	public float walkSpeed() {
		return walkSpeed;
	}

	private static final Vector3f ZERO = new Vector3f();

	/** 자신의 이동 상태 (지상/공중/벽/줄). */
	public EnemyNav nav() {
		return nav;
	}

	/** 충돌 세계 연결 (GameScene). 연결하면 바닥·중력·벽·줄을 적 스스로 처리한다 (스폰 직후 공중 → 벽·줄 잡기). */
	public void setNavWorld(EnemyNav.World w) {
		nav.setWorld(w);
	}

	/** 지상 이동 클립 후보 수집: 이름이 앞으로 걷기/달리기인 루프 클립과 그 블렌드 축 값 (TWK _Cycle, 없으면 ANM +0x14). */
	private void collectLocoClips(AnimationSet set) {
		java.util.Set<String> names = java.util.Set.of("front", "forward", "walk", "walkfront", "walkforward", "runfront", "runforward", "run");
		for (AnimationSet.Group g : set.groups) {
			for (devple.gow.tools.anim.Animation a : g.acts) {
				String n = a.name.toLowerCase(java.util.Locale.ROOT);
				if (a.hasSkeletalData() && (names.contains(n) || a.name.equals(walkClip) || a.name.equals(runClip)) && !locoClips.contains(a.name)) {
					locoClips.add(a.name);
					locoParams.add(tw.f(navNode.substring(0, navNode.length() - "Navigation/".length()), a.name + "_Cycle", a.blendParam));
				}
			}
		}
	}

	/** 속도 (m/s) 에 블렌드 축 값이 가장 가까운 지상 이동 클립 [추정: 원작 블렌드 그룹(ANIWalk) 축 = 속도]. */
	String locoClip(float mps) {
		String best = walkClip;
		float bd = Float.MAX_VALUE;
		for (int i = 0; i < locoClips.size(); i++) {
			float p = locoParams.get(i);
			if (p > 0 && Math.abs(p - mps) < bd) {
				bd = Math.abs(p - mps);
				best = locoClips.get(i);
			}
		}
		return best;
	}

	/** 이동 상태 애니: 지상 = 속도별 클립, 공중 = ANIFall, 벽 = ANIClimbIdle / 방향별 wClimbBlend, 줄 = ANIBRope(F) / ANIRope(U/D). */
	private void locomotionAnim() {
		anim.setRate(1f);
		switch (nav.mode()) {
			case GROUND -> {
				float v = nav.speed();
				anim.play(v > 4f ? locoClip(v / 16f) : idleClip, false);
			}
			case AIR -> playNav("ANIFall", null);
			case WALL -> {
				float mx = nav.moveX(), my = nav.moveY();
				if (mx == 0 && my == 0) {
					playNav("ANIClimbIdle", "wallClimbIdle");
				} else if (Math.abs(my) >= Math.abs(mx)) {
					playNav(null, devple.gow.engine.nav.NavAnims.CLIMB[my > 0 ? 0 : 2]);
				} else {
					playNav(null, devple.gow.engine.nav.NavAnims.CLIMB[mx > 0 ? 1 : 3]);
				}
			}
			case ROPE -> {
				boolean moving = nav.moveX() != 0;
				if (nav.rope() != null && nav.rope().horizontal()) {
					playNav(moving ? "ANIBRopeF" : "ANIBRope", moving ? "ropeHOH" : "ropeHOHI");
				} else {
					playNav(moving ? "ANIRopeU" : "ANIRope", moving ? "ropeHOH" : "ropeHOHI");
				}
			}
		}
	}

	/** Navigation 표 키의 애니 (없으면 기본 이름, 그것도 없으면 그대로). */
	private void playNav(String key, String def) {
		String n = key != null ? tw.s(navNode, key, def) : def;
		if (n != null && anim.find(n) != null) {
			anim.play(n, false);
		} else if (def != null && anim.find(def) != null) {
			anim.play(def, false);
		}
	}

	private static float turn(float from, float to, float max) {
		float d = (float) Math.IEEEremainder(to - from, Math.PI * 2);
		return from + Math.max(-max, Math.min(max, d));
	}

	public boolean dead() {
		return hp <= 0;
	}

	public float deadTime() {
		return deadTime;
	}

	public Matrix4f world(Matrix4f out, float alpha) {
		Vector3f p = new Vector3f(prevPos).lerp(pos, alpha);
		return out.translation(p).rotateY(yaw).scale(scale); // 모델 -Z 정면 (34 문서 §4), ScaleCenter
	}

	// ---- Hittable ----

	@Override
	public Vector3f position() {
		return pos;
	}

	@Override
	public float hp() {
		return hp;
	}

	@Override
	public int classId() {
		return classId;
	}

	@Override
	public int stanceTag() {
		return combat.stanceTag();
	}

	@Override
	public boolean grabbable() {
		return circleHp < 65535 && hp > 0 && hp <= circleHp;
	}

	@Override
	public HitVolumes hitVolumes() {
		return combat.hitVolumes();
	}

	@Override
	public boolean invulnerable() {
		return hp <= 0 || (!stone && combat.invulnerable());
	}

	@Override
	public boolean addFreeze(float amount) {
		if (hp <= 0 || stone || freezeMax >= 60000) {
			return false;
		}
		freezeHit = true;
		freezeMeter = Math.max(0, freezeMeter - amount);
		if (freezeMeter > 0) {
			return false;
		}
		stone = true;
		stoneElapsed = 0;
		combat.request(stoneMove); // 메시지 0x407 → 석화 기술 (160 freezePose)
		return true;
	}

	@Override
	public boolean petrified() {
		return stone;
	}

	/** 돌 상태에서 맞아 부서졌는지 (Stone Kill). */
	public boolean shattered() {
		return shattered;
	}

	/** 동결 게이지 (현재, 최대) — 테스트/HUD. */
	public float freezeMeter() {
		return freezeMeter;
	}

	public float freezeMax() {
		return freezeMax;
	}

	/** 돌 지속 시간 (초). */
	public float stoneTime() {
		return stoneTime;
	}

	/** 부서짐 콜백 (Stone Kill 보너스 등). */
	public void onShatter(Runnable r) {
		onShatter = r;
	}

	@Override
	public Result receive(Attack attack, Combatant attacker, float damage, Vector3f knock) {
		if (damageFrom != null && !damageFrom.test(attacker)) {
			return Result.IGNORED;
		}
		if (stone) {
			// 석상(Brkb, Hit Points 1)은 한 번 맞으면 부서짐 → 소유자 사망 0x405 + Stone Kill 0x416
			stone = false;
			shattered = true;
			hp = 0;
			onShatter.run();
			return Result.HIT;
		}
		// 원작 Combat_OnContact(0x204124): 방어 자세가 아니고 방어 불가 공격이 아니면 owner 의 방어 판단(0x1CBFF8) 먼저
		boolean fromFoe = attacker != null && foe != null && attacker.owner() != null
				&& attacker.owner().position().distanceSquared(foe.position()) < 1f;
		if (fromFoe) {
			brain.observeTargetCombat(attacker);
		}
		brain.onHit();
		boolean unblockable = attacker != null && attacker.move() != null && (attacker.move().flags() & 0x2) != 0;
		if (!combat.guarding() && !unblockable && brain.tree != null) {
			brain.tryBlock(attacker != null && attacker.move() != null ? attacker.move().id() : -1, fromFoe);
		}
		Result r = combat.takeHit(attack, attacker, damage);
		if (r == Result.HIT && nav.grounded()) {
			pos.add(knock.x / 400f, 0, knock.z / 400f);
		}
		return r;
	}

	// ---- CombatOwner ----

	@Override
	public boolean onGround() {
		return nav.grounded();
	}

	@Override
	public int stateMask() {
		return nav.combatMask();
	}

	@Override
	public int locomotionState() {
		return nav.conditionState();
	}

	@Override
	public int bladeLevel() {
		return 0;
	}

	@Override
	public boolean godMode() {
		return false;
	}

	@Override
	public boolean artemisEquipped() {
		return false;
	}

	@Override
	public int selectedMagic() {
		return 0;
	}

	@Override
	public float yaw() {
		return yaw;
	}

	@Override
	public boolean killedBy(float damage) {
		return damageHook == null && CombatOwner.super.killedBy(damage);
	}

	@Override
	public boolean applyDamage(float damage) {
		if (damageHook != null) {
			damageHook.accept(damage);
			return false;
		}
		float before = hp;
		hp = Math.max(Math.min(hpFloor, before), hp - damage);
		return circleHp < 65535 && before > circleHp && hp <= circleHp && hp > 0;
	}
}
