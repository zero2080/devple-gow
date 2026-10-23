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
public final class EnemyFighter implements Hittable, CombatOwner, BodyPush.Body {

	/** 회전 속도 (rad/s) [근사: 원작 0x1CE240 회전 보간 값 미확인]. */
	static final float TURN_RATE = 8f;

	private final Combatant combat;
	private final Animator anim;
	/** AI1 템플릿 번호. */
	private final int ai1;
	/** 전투 분기의 종류 (자신 0x14 / 상대 0x16) = AI1 템플릿 +0x9C ({@link AiTemplate#classId}) — AI1 번호와 다를 수 있다 (R_MI2 51 → 50). */
	private final int classId;
	private final AiTemplate tmpl;
	private final AiBrain brain;
	/** 플러그인 대체 제어 (49 문서 §13) / 모드 열쇠. */
	private final devple.gow.engine.mod.EnemyController controller;
	private final List<String> modKeys;
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
		this.ai1 = ai1;
		this.tw = tw;
		// 모드 수치 덮어쓰기 (ai.properties, 49 문서 §13) — AI1·Sold 값이 정해지는 유일한 자리. 표가 없으면 원래 값
		this.modKeys = AiOverrides.keys(ai1, tw.s(String.format("/TweakTemplates/AI1/%03d/", ai1), "Instance Name", null), wad != null ? wad.name() : null);
		AiOverrides ov = AiOverrides.of(modKeys);
		if (!ov.isEmpty()) {
			System.out.println("[mods] ai " + ai1 + ": " + ov);
		}
		this.tmpl = new AiTemplate(tw, ai1, ov);
		this.classId = tmpl.classId;
		this.maxHp = Math.max(tmpl.hitPoints, tmpl.maxHitPoints) & 0xFFFF;
		this.hp = tmpl.hitPoints & 0xFFFF;
		this.aiDamageMult = tmpl.damageMult;
		this.circleHp = tmpl.circleHp; // 65535 = 잡기 원 없음
		this.attackMoves = attackMoves;
		this.idleClip = idleClip;
		this.walkClip = walkClip;
		this.anim = new Animator(sk, set, tw, animNode);
		int pel = sk.indexOf(HYDRA_ROOT);
		if (pel >= 0 && sk.joints.get(pel).quaternion() && sk.joints.get(pel).parent() == 0) {
			java.util.Map<devple.gow.tools.anim.Animation, Boolean> hang = new java.util.HashMap<>();
			anim.setRollFix(pel, a -> hang.computeIfAbsent(a, x -> hangsUpsideDown(sk, x, pel)));
		}
		this.navNode = animNode + "Navigation/";
		// 이동 속도: 루프 이동 클립의 zeroJoint Z × 15fps (25 문서, 크라토스와 같은 규칙). 없으면 주어진 값
		devple.gow.tools.anim.Animation wc = walkClip != null ? anim.find(walkClip) : null;
		this.stationary = sk.indexOf(HYDRA_ROOT) >= 0 && (wc == null || !wc.hasSkeletalData());
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
		this.nav = new EnemyNav(tmpl.moveCaps, ai != null ? ov.apply("motion", ai.motion(tmpl.soldier)) : null, tmpl.pauseMoveMs);
		AiData.Body soldBody = ai != null ? ov.apply("body", ai.body(tmpl.soldier)) : null;
		nav.setBody(soldBody, scale);
		nav.setRide(ai != null ? ov.apply("ride", ai.ride(tmpl.soldier)) : null);
		collectLocoClips(set);
		DecisionTree tree = ai != null ? ai.tree(tmpl.decisionTree) : null;
		AiData.Soldier sold = ai != null ? ov.apply("soldier", ai.soldier(tmpl.soldier)) : null;
		this.brain = new AiBrain(this, tmpl, tree, AiData.blockMod(tw, tmpl.blockMod), sold, seed);
		setDifficulty(1);
		anim.play(idleClip, true);
		AiData.Stone stn = ai != null ? ov.apply("stone", ai.stone(tmpl.soldier)) : null;
		this.freezeMax = stn != null ? stn.resist() : 30f;
		this.stoneMove = stn != null ? stn.move() : 160;
		this.freezeMeter = freezeMax;
		var sm = tw.rawHash(tmpl.node, 0xb5134762);
		this.stoneTime = (sm.isPresent() ? sm.getAsInt() : 5000) / 1000f;
		var us = tw.raw("/GlobGame/", "Unfreeze Speed");
		this.unfreezeRate = (us.isPresent() ? us.getAsInt() & 0xFFFF : 50) * 60f;
		this.controller = devple.gow.engine.mod.Plugins.controllerFor(this, modKeys); // 플러그인 AI 대체 (49 문서 §13), 없으면 null
	}

	/** 히드라 리그 (bha/bhb/bhh) 의 뿌리 관절. */
	static final String HYDRA_ROOT = "BHA01JOCPEL1";

	/**
	 * 클립 첫 프레임의 히드라 골반이 X 축 180° (정규화 |x| &gt; 0.95) = 목이 아래로 매달린 자세. ATHN01B bhh 의 BHAenc1* 21 개만 해당
	 * (같은 리그의 BHAbabyEmerge·bha·bhb 클립은 거의 단위) — 37 문서 §4.3 [추정].
	 */
	static boolean hangsUpsideDown(Skeleton sk, devple.gow.tools.anim.Animation a, int joint) {
		if (a == null || !a.hasSkeletalData()) {
			return false;
		}
		float[] q = a.sample(sk, 0, false).rot[joint];
		float n = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
		return n > 0 && Math.abs(q[0] / n) > 0.95f;
	}

	/** 난이도 0..3 (Easy..Impossible): "AI Recovery Time Mult" 를 공격 후 대기에 적용. */
	public void setDifficulty(int d) {
		float m = tw.f("/GlobGame/" + DamageRules.DIFFICULTY[Math.max(0, Math.min(3, d))] + "/", "AI Recovery Time Mult", 1f);
		brain.setRecoveryScale(() -> m);
	}

	public AiBrain brain() {
		return brain;
	}

	/** 플러그인이 등록한 대체 제어 (없으면 null = 원래 AI). */
	public devple.gow.engine.mod.EnemyController controller() {
		return controller;
	}

	/** 모드 열쇠 (*, WAD 이름, Instance Name, AI1 번호 — 낮음 → 높음, {@link AiOverrides#keys}). */
	public List<String> modKeys() {
		return modKeys;
	}

	/** 현재 적 (setFoe, 없으면 null). */
	public Hittable foe() {
		return foe;
	}

	public AiTemplate template() {
		return tmpl;
	}

	public int ai1() {
		return ai1;
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
		if (combat.hitPause() > 0 && hp > 0) {
			// 히트스톱 (0x2652E4: GO +0x16C 개체 시간 배율 0.01 — 이동·애니 dt 에 곱함) [근사: 0.01 대신 정지]
			combat.update(null, dt);
			return;
		}
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
			nav.rootMoved = false;
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
			rootMotion();
			return;
		}
		if (holdAi) {
			// AI 정지 (시험·HeroTestScene 배치용): 스틱 0 으로 내비게이션 이동 단계는 돈다 — 서 있으면 (속도·변위 0) 원작처럼 자기는 밀지 않고
			// (0x1EBB38 정지 → 0x1E9470), 기술 루트 모션으로 움직이거나 자유 이동 기술이면 민다
			nav.update(pos, ZERO, null, combat.active(), dt);
		} else if (controller != null) {
			controller.update(this, dt); // 플러그인 AI (49 문서 §13)
		} else if (brain.tree != null) {
			brain.update(dt);
			boolean busy = combat.active();
			// 이동 = 공용 내비게이션 (지상 Sold 속도 / 벽 / 줄, EnemyNav). AI 는 스틱(brain.move)과 목표 점만 준다 (0x1CF9D0)
			// 벽·줄 위에서는 전투 중이면 목표 = 대상 자신 (DTre "get the hero!" / "rope attack!") [근사: 원작은 포위 칸을 투영]
			Hittable tg = brain.target();
			boolean clinging = nav.mode() == EnemyNav.Mode.WALL || nav.mode() == EnemyNav.Mode.ROPE;
			// 원작 AI_MoveTo 0x1CF9D0 의 목표 = 포위 칸 (0x1CDBF0) 또는 칸 상자 밖이면 대상 (0x1CD300) — 벽·줄 위에서도 같은 목표를 벽 평면/줄에 투영한다.
			// 칸이 대상 주위 무작위 칸이라 같은 벽 위 적들이 좌우로 흩어진다 (32 문서 §같은 벽 위 여러 적) [확실: 호출 경로]
			Vector3f goal = brain.hasGoal ? brain.goal
					: clinging && tg != null && brain.state() == AiBrain.State.COMBAT ? tg.position() : null;
			boolean goalIsTarget = goal != null && tg != null && goal.distanceSquared(tg.position()) < 1f;
			Boolean goalClinging = clinging && tg != null ? goalIsTarget && tg.locomotionState() == nav.conditionState() : null;
			if (stationary) {
				goal = null; // 이동 애니 없음 → 제자리 (선창 히드라 bhh, 37 문서 §4.3)
			}
			nav.update(pos, stationary ? ZERO : brain.move, goal, goalClinging, busy, dt);
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
		rootMotion();
	}

	/**
	 * 플러그인 제어용 이동 ({@link devple.gow.engine.mod.EnemyController}): 원래 AI 와 같은 내비게이션 단계 (바닥·벽·줄) + 바라보기 + 이동 애니.
	 *
	 * @param stick 월드 방향 × 속도 배율 0..1 (원래 AI 의 {@link AiBrain#move})
	 * @param face  바라볼 yaw (NaN = 유지)
	 */
	public void steer(Vector3f stick, float face, float dt) {
		boolean busy = combat.active();
		nav.update(pos, stick, null, null, busy, dt);
		if (!Float.isNaN(nav.faceYaw())) {
			yaw = nav.faceYaw();
		} else if (!busy && !Float.isNaN(face)) {
			yaw = turn(yaw, face, TURN_RATE * dt);
		}
		if (!busy) {
			locomotionAnim();
		}
	}

	/** 루트 모션 (원작 0x1F7110 → 0x1EBD78, 영웅과 같은 규칙) — 동기 상대가 끄는 중이면 없음. */
	private final devple.gow.engine.anim.RootMotion root = new devple.gow.engine.anim.RootMotion();
	/**
	 * 걷기 클립이 없는 히드라 리그 (bhh/bhb: Navigation ANIWalk "bwalk" 가 ANM 에 없고 이름 규칙으로도 못 찾아 "main"): AI 스틱·목표를
	 * 버리고 제자리에서 돌기만 한다. 원작 지상 이동 0x1EAEC8 은 속도 기반이라 이 규칙의 원작 경로는 못 찾았다 — 동기 애니 BHAenc1Grab 의
	 * 영웅 배치가 스폰 자리 기준으로 바닥 끝에 맞는 것이 근거 [추정]. 하피·궁수 등 다른 걷기 없는 적은 그대로 (날기·제자리 사격).
	 */
	public final boolean stationary;
	/** AI 결정·이동을 멈춘다 (시험·장면 배치용, 원작에 없음). 전투 기술·애니·루트 모션은 그대로. */
	public boolean holdAi;
	/** 동기 상대가 이 적을 끄는 중 (0x204BF0 의 끌리는 쪽: D = 단위, 0x1EC058). 동기 코드가 매 틱 정한다. */
	public boolean rootHold;
	/** 루트 모션을 쓰지 않음 (클래스 코드가 위치를 직접 정하는 보스 등). */
	public boolean rootMotionOff;

	/**
	 * 전투 기술 중에만 (0x1EBEF0: combatant +0x4C ≠ 0) zeroJoint 델타를 적용: 요는 바로, 수평 이동은 위치에 더한다 — 다음 틱 내비게이션
	 * (EnemyNav 지상: 벽 충돌·바닥 붙이기) 이 원작 이동 함수처럼 처리. 수직 변위는 자유 이동 기술 (stateMask &amp; 0x1C == 0) 에서만 — 원작 0x1ECD60 경로
	 * (중력 없는 0x1E5F90 + 충돌 0x1EA200). stateMask 7 기술은 지상 이동이 바닥에 붙이므로 버린다.
	 */
	private void rootMotion() {
		if (rootMotionOff || !combat.active()) {
			root.reset();
			nav.free = false;
			nav.moveFlags = -1;
			nav.rootMoved = false;
			return;
		}
		root.step(anim, scale, yaw, rootHold);
		pos.x += root.dx;
		pos.z += root.dz;
		yaw += root.dyaw;
		nav.rootMoved = root.dx != 0 || root.dz != 0; // 다음 이동 단계의 변위 (0x1EC6D8) — 정지 판정 0x1EBB38
		// 수직: 자유 이동 기술 (stateMask & 0x1C == 0, 0x1EC350) 은 3D 변위를 그대로 (중력 없음, 다음 틱 내비가 바닥 아래만 막음).
		// 그 밖 (stateMask 7) 은 지상 이동 0x1EAEC8 이 바닥에 붙인다 → 수직 버림
		int mask = combat.move().stateMask();
		nav.free = (mask & 0x1C) == 0;
		nav.pushInFree = (mask & 2) != 0;
		nav.moveFlags = combat.move().flags(); // 몸 질의 마스크: flags & 0x60 → NoCSMCollision 지나감 (0x1EA6FC)
		if (nav.free && nav.mode() != EnemyNav.Mode.WALL && nav.mode() != EnemyNav.Mode.ROPE) {
			pos.y += root.dy;
		}
	}

	/** 이번 틱에 적용한 루트 모션을 되돌린다 (끌리는 첫 프레임 — {@link devple.gow.engine.anim.HeroController#undoRoot}). */
	public void undoRootMotion() {
		pos.x -= root.dx;
		pos.z -= root.dz;
		if (nav.free) {
			pos.y -= root.dy;
		}
		yaw -= root.dyaw;
		root.dx = root.dy = root.dz = root.dyaw = 0;
		nav.rootMoved = false;
	}

	/** 마지막 루트 모션 (월드 변위·요 변화) — 테스트·보스 클래스. */
	public devple.gow.engine.anim.RootMotion rootMotionState() {
		return root;
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
				return z * anim.speedOf(a); // 모드 속도 배율 (49 문서 §12)
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
		return time > 0 ? slide / time * anim.speedOf(a) : 0;
	}

	public float walkSpeed() {
		return walkSpeed;
	}

	private static final Vector3f ZERO = new Vector3f();

	/** 생성 액션 요청 중 (원작 Enemy1 초기화 시점의 상태 = 지상 → 분기 상태 마스크 0x2). */
	private boolean spawning;

	/**
	 * 스폰 엔티티 슬롯 7 (SpawnAction) 의 생성 액션 (원작 SpawnEnemy 0x1AC850 → 생성 데이터 +6 → Enemy1 초기화 0x22CB18, 기본 -1 = 없음).
	 * 해골 R_SKS: 500/501/502/550 SPAWN (땅에서 솟음, goSpawnHole), 504 SKSWALLSPAWN (벽에서 나옴, PlayFX 034 WallSpawn, 0–0.83 무적),
	 * 0..2 TAUNT. 루트 액션 분기 플래그가 0x148242 (지상 전용) 라 초기 상태(지상)로 Match 한다 — 벽 옆 스폰은 동작 중에 벽을 잡는다.
	 *
	 * @return 시작했는지
	 */
	public boolean startSpawnAction(int action) {
		if (action < 0 || hp <= 0) {
			return false;
		}
		spawning = true;
		try {
			return combat.request(action);
		} finally {
			spawning = false;
		}
	}

	/** 자신의 이동 상태 (지상/공중/벽/줄). */
	public EnemyNav nav() {
		return nav;
	}

	/** 충돌 세계 연결 (GameScene). 연결하면 바닥·중력·벽·줄을 적 스스로 처리한다 (스폰 직후 공중 → 벽·줄 잡기). */
	public void setNavWorld(EnemyNav.World w) {
		nav.setWorld(w);
	}

	/**
	 * 생물끼리 밀어내기 (0x1E2DD8, {@link BodyPush}): 이동할 때 others (영웅·다른 적, 자신 포함 가능) 밖으로 자신을 민다.
	 * GameScene 이 프레임마다 같은 목록을 준다.
	 */
	public void setCrowd(java.util.function.Supplier<? extends Iterable<? extends BodyPush.Body>> others) {
		nav.setCrowd(this, others);
	}

	@Override
	public Vector3f feet() {
		return pos;
	}

	/**
	 * 캡슐 반지름 = Sold 상자 (0x1E6D58). 관절 상자 0x1E6DF0 은 0x1EBD78 이 owner+0x174 비트 31 (플레이어 — 내비 생성 0x1E7E20 의 t0,
	 * Player 0x1D636C 만 1) 일 때만 부르므로 적·보스는 Sold +0xA4 관절 (BMI·히드라 머리) 이 있어도 늘 Sold 상자 [확실].
	 */
	@Override
	public float bodyRadius() {
		return nav.bodyRadius();
	}

	/** 밀기 질의 후보 = 자기 CDV 구 (부착 무기 GO 제외). */
	@Override
	public java.util.List<float[]> pushSpheres() {
		HitVolumes hv = combat.hitVolumes();
		return hv != null && !hv.isEmpty() ? hv.ownSpheres() : null;
	}

	@Override
	public float bodyHeight() {
		return nav.bodyHeight();
	}

	@Override
	public float bodyBase() {
		return nav.bodyBase();
	}

	/**
	 * 공중 (+0x174 &amp; 0x4C). 날기 능력 (AI1 0x38 Fly 0x8) 이면 시작 상태가 0x40 날기 (0x1D08C0) — 하피: 지상 영웅은 하피에게 밀리지 않는다
	 * (0x1E2B80) [확실: 시작 상태 / 근사: 엔진은 날기 높이·날기 상태 이탈을 모의하지 않고 계속 공중으로 본다].
	 */
	@Override
	public boolean airborne() {
		return nav.mode() == EnemyNav.Mode.AIR || ((nav.caps() & AiTemplate.CAP_FLY) != 0 && nav.mode() == EnemyNav.Mode.GROUND);
	}

	/** 단단함 = 현재 기술 stateMask 비트 2 (0x1E29A8). 사망 기술이 끝난 시체는 통과 [근사: 원작 시체 처리 미분석]. */
	@Override
	public boolean solid() {
		if (hp <= 0 && !combat.active()) {
			return false;
		}
		return BodyPush.solid(combat);
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
				if (nav.rope() != null && nav.rope().sub() == EnemyNav.SUB_ZIPLINE) {
					playNav("ANIZipLine", "zipLine");
				} else if (nav.rope() != null && nav.rope().horizontal()) {
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

	@Override
	public float modelScale() {
		return scale;
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

	/**
	 * 몸 CDV 가 없는 생물 (ATHN02A 부두 아테나 석상 aoxsa, AI1 307): 원작 공격 접촉은 CDV 구 충돌 메시지 (0x203150 → Combat_OnContact) 로만 생기므로
	 * 맞지도 잡히지도 않는다 [확실: 데이터 (ATHN02A 에 CDV_aoXSA 없음, 다른 레벨 AI aoxpt/aoxf1… 와 R_ 적은 모두 있음) / 추정: 접촉 경로가 CDV 뿐].
	 * 엔진은 CDV 가 없으면 정면 구 근사로 맞혔다 → 석상이 HP 20 을 잃고 쓰러졌다. 영웅 대상 (나침반·봇) 에서도 뺀다 (GameScene.nearestEnemy) [추정].
	 */
	public boolean bodiless;

	@Override
	public boolean invulnerable() {
		return hp <= 0 || bodiless || (!stone && combat.invulnerable());
	}

	/**
	 * SELF_HP (기술 이벤트 10): 양수 = 자기 피해 (HP 바닥 무시, 0 이 되면 EV_DEATH 분기 — (block) 이면 기술을 끝까지), 음수 = 회복.
	 * 예: 던져지는 해골 MAISKB02 221 [0.20] b 200 → 날아가는 동안 사망 (BossFight.selfHp 와 같은 규칙).
	 */
	public void selfHp(float v) {
		if (v >= 0) {
			float before = hp;
			hp = Math.max(0, hp - v);
			if (before > 0 && hp <= 0) {
				combat.fire(CombatInput.EV_DEATH, -1, null);
			}
		} else {
			hp = Math.min(maxHp, hp - v);
		}
	}

	@Override
	public void hitPause(float s) {
		combat.pause(s);
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
		return spawning ? 0x2 : nav.combatMask();
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
