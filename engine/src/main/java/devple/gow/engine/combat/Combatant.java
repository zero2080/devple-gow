package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.combat.CombatFile.Attack;
import devple.gow.tools.combat.CombatFile.Branch;
import devple.gow.tools.combat.CombatFile.Event;
import devple.gow.tools.combat.CombatFile.Move;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 전투 컴포넌트 (원작 Combat_Update 0x205968 / PollInput 0x208A98 / StartBranch 0x209488 / OnContact).
 * 영웅·적 공용. 상태 = (기술 없음 | 현재 기술). 영웅은 패드 입력으로, 적(AI)은 {@link #request(int)} 로 기술을 시작한다.
 */
public final class Combatant {

	/**
	 * 공격 판정 근사: 무기 히트박스 대신 정면 구 (10 문서 §5 의 부위별 히트박스는 미구현).
	 * 혼돈의 블레이드는 사슬 무기라 사거리가 길다 (크레토스 키 ≈ 37 유닛).
	 */
	public static final float REACH = 55f, HIT_RADIUS = 55f;

	/** 분기 매칭 상대 정보 (폴링: 락온 대상, 이벤트: 공격자). */
	private record Other(float hp, int classId, int tag) {
		static final Other NONE = new Other(0, -1, -1);
	}

	private final CombatData data;
	private final Animator anim;
	private final CombatOwner owner;
	private final Random rnd = new Random(1);

	private Move move;
	private Animation clip;
	private Branch queued;
	private float queuedAt;
	private int queuedPrio;
	private int heldAtStart;
	private float accumDamage;
	private int stanceTag = -1;
	private final Set<Long> hitLog = new HashSet<>();
	private final Set<Integer> firedEvents = new HashSet<>();
	private boolean hitThisFrame;
	private boolean hitThisMove;
	private float hitPause;
	private Hittable target;
	private Supplier<Float> damageScale = () -> 1f;
	private Consumer<String> log = s -> { };
	public boolean debug;
	public float reach = REACH, hitRadius = HIT_RADIUS;
	public int hitCounter;
	public float godMeter;

	public Combatant(CombatData data, Animator anim, CombatOwner owner) {
		this.data = data;
		this.anim = anim;
		this.owner = owner;
	}

	/** 공격자 측 피해 배율 (영웅 = Player_DamageScale, 적 = AI1 Damage Multiplier × 난이도 AI 배율). */
	public void setDamageScale(Supplier<Float> s) {
		damageScale = s;
	}

	public void onLog(Consumer<String> l) {
		log = l;
	}

	public boolean active() {
		return move != null;
	}

	public Move move() {
		return move;
	}

	public CombatOwner owner() {
		return owner;
	}

	public void setTarget(Hittable t) {
		target = t;
	}

	public int stanceTag() {
		return stanceTag;
	}

	public Hittable target() {
		return target;
	}

	/** 현재 기술의 애니 이름 (없으면 null). */
	public String clipName() {
		return clip != null ? clip.name : null;
	}

	/** 현재 기술 진행률 0..1 (애니 시간 / 길이). */
	public float progress() {
		if (move == null || clip == null || clip.duration <= 0) {
			return 1f;
		}
		return Math.min(1f, anim.time() / clip.duration);
	}

	public boolean invulnerable() {
		return move != null && eventActive(9);
	}

	public boolean guarding() {
		return move != null && move.guard() == 1;
	}

	/** 히트스톱 남은 시간 (이 동안 애니/이동 정지). */
	public float hitPause() {
		return hitPause;
	}

	/** @param in 영웅 = 패드, AI = null */
	public void update(Input in, float dt) {
		hitThisFrame = false;
		if (hitPause > 0) {
			hitPause -= dt;
			return;
		}
		float t = progress();
		if (move != null) {
			runEvents(t);
		}
		poll(in, t);
		if (queued != null && (move == null || t >= queuedAt)) {
			Branch b = queued;
			queued = null;
			start(b, in);
			t = progress();
		}
		if (move != null) {
			attacks(t);
			if (clip == null || (!clip.looping() && anim.time() >= clip.duration)) {
				end();
			}
		}
	}

	/** AI/스크립트의 기술 요청 (sub_00209378): 해당 기술로 가는 루트 액션을 Match 해서 시작. */
	public boolean request(int moveId) {
		for (Branch b : data.rootActions()) {
			if (b.target() == moveId && score(b, other()) >= 0) {
				start(b, null);
				return true;
			}
		}
		return false;
	}

	// ---------------- 입력 / 분기 ----------------

	private Other other() {
		return target == null ? Other.NONE : new Other(target.hp(), target.classId(), target.stanceTag());
	}

	private void poll(Input in, float t) {
		Other o = other();
		if (move != null) {
			List<Branch> cands = new ArrayList<>();
			int best = -1;
			for (Branch b : move.branches()) {
				if (t < b.winStart() || t > b.winEnd() || !inputOk(b, in) || score(b, o) < 0) {
					continue;
				}
				int prio = b.input() == CombatInput.AUTO ? 1 : 3;
				if (b.mode() == 3) {
					PadButton pb = CombatInput.button(b.input());
					prio = pb != null && (heldAtStart & (1 << pb.ordinal())) != 0 ? 2 : -1;
				}
				if ((b.flags() & 0x1000) != 0 && prio != -1) {
					prio = 4;
				}
				if (prio < 0) {
					continue;
				}
				if (prio > best) {
					cands.clear();
					best = prio;
				}
				if (prio == best) {
					cands.add(b);
				}
			}
			if (cands.isEmpty() || (queued != null && queuedPrio >= best)) {
				return;
			}
			Branch c = cands.get(rnd.nextInt(cands.size()));
			float execAt = c.immediate() ? 0 : c.winEnd();
			if (execAt > t && (c.input() == CombatInput.AUTO || c.mode() == 3)) {
				return; // 나중에 다시 평가
			}
			queued = c;
			queuedAt = execAt;
			queuedPrio = best;
		} else if (in != null) {
			Branch c = bestMatch(data.rootActions(), in, o);
			if (c != null) {
				queued = c;
				queuedAt = 0;
				queuedPrio = 3;
			}
		}
	}

	private Branch bestMatch(List<Branch> list, Input in, Other o) {
		int bestScore = Integer.MAX_VALUE;
		List<Branch> ties = new ArrayList<>();
		for (Branch b : list) {
			if (in != null && !inputOk(b, in)) {
				continue;
			}
			int s = score(b, o);
			if (s < 0) {
				continue;
			}
			if (s < bestScore) {
				bestScore = s;
				ties.clear();
			}
			if (s == bestScore) {
				ties.add(b);
			}
		}
		return ties.isEmpty() ? null : ties.get(rnd.nextInt(ties.size()));
	}

	/** InputOK (0x207E40): 버튼 모드 + 방향. */
	private boolean inputOk(Branch b, Input in) {
		if (in == null) {
			return b.input() == CombatInput.AUTO;
		}
		boolean ok = switch (b.input()) {
			case CombatInput.AUTO -> true;
			case CombatInput.ANY_INPUT -> CombatInput.anyNewButton(in) || Math.hypot(in.leftX(), in.leftY()) > 0.5;
			case CombatInput.STICK -> Math.hypot(in.leftX(), in.leftY()) > 0.5;
			case CombatInput.EV_HIT_CONFIRM -> b.mode() == 4 ? hitThisMove : hitThisFrame;
			default -> {
				PadButton pb = CombatInput.button(b.input());
				if (pb == null) {
					yield false; // 이벤트 코드는 fire() 로만
				}
				yield switch (b.mode()) {
					case 1 -> in.pressed(pb);
					case 2 -> in.released(pb);
					case 3, 4 -> in.held(pb);
					case 5 -> !in.held(pb);
					default -> in.pressed(pb);
				};
			}
		};
		return ok && dirOk(b.dir(), in);
	}

	/** 방향 조건 (10 문서 §2.6). 3–6 = 캐릭터 기준 좌/우/앞/뒤, 9–14 = 오른쪽 스틱판. */
	private boolean dirOk(int d, Input in) {
		if (d == 0) {
			return true;
		}
		boolean right = d >= 9 && d <= 14;
		int k = right ? d - 8 : d;
		float sx = right ? in.rightX() : in.leftX(), sy = right ? in.rightY() : in.leftY();
		float mag = (float) Math.hypot(sx, sy);
		return switch (k) {
			case 1 -> mag < 0.8f;
			case 2 -> mag >= 0.8f;
			case 3, 4, 5, 6 -> {
				if (mag < 0.8f) {
					yield false;
				}
				// 스틱(카메라 정면 근사)을 캐릭터 기준으로 변환
				float ang = (float) Math.atan2(-sx, sy) - owner.yaw();
				float fx = (float) -Math.sin(ang), fz = (float) Math.cos(ang);
				float c = 0.7071f;
				yield switch (k) {
					case 3 -> -fx >= c;
					case 4 -> fx >= c;
					case 5 -> fz >= c;
					default -> -fz >= c;
				};
			}
			default -> false; // 7, 8, 15: 낙하 높이 조건 (미구현)
		};
	}

	/** Match (0x208578). 통과 못하면 -1, 통과하면 와일드카드 점수 (낮을수록 구체적). */
	private int score(Branch b, Other o) {
		int f = b.flags();
		int state = owner.onGround() ? 0x2 : 0x4;
		if ((f & 0x100A6) != 0 && (f & state) == 0) {
			return -1;
		}
		if ((f & 0x200) == 0 && ((f & 0x100) != 0) != owner.godMode()) {
			return -1;
		}
		if ((f & 0x8000) == 0 && ((f & 0x2000) != 0) != owner.artemisEquipped()) {
			return -1;
		}
		if ((f & 0x20000) != 0 || (f & 0x80000) != 0 || (f & 0x800) != 0 || (f & 0x8) != 0 || (f & 0x4000) != 0) {
			return -1; // 미상 플래그 / 차지 / 특수 태그 일치는 아직 미지원
		}
		if ((f & 0x400) != 0 && target == null) {
			return -1;
		}
		if (b.level() > owner.bladeLevel()) {
			return -1;
		}
		if (b.magic() == 7 && !owner.artemisEquipped()) {
			return -1;
		}
		if (b.magic() >= 3 && b.magic() <= 6 && (b.magic() != owner.selectedMagic() || !owner.hasMagic(b.magic()))) {
			return -1;
		}
		float hp = owner.hp();
		if (hp < b.hpMin() || hp > b.hpMax()) {
			return -1;
		}
		if (o.hp() < b.argMin() || o.hp() > b.argMax()) {
			return -1;
		}
		int s = 0;
		if (b.matchC() == -1) {
			s += 1;
		}
		if (b.reaction() == -1) {
			s += 2;
		}
		if (b.matchB() == -1) {
			s += 4;
		} else if (b.matchB() != o.tag()) {
			return -1;
		}
		if (b.matchA() == -1) {
			s += 8;
		} else if (b.matchA() != o.classId()) {
			return -1;
		}
		if (b.classId() == -1) {
			s += 0x10;
		} else if (b.classId() != owner.classId()) {
			return -1;
		}
		return s;
	}

	private void start(Branch b, Input in) {
		end();
		if (b.target() == -2) {
			return;
		}
		if (b.target() == -1) {
			// 기술만 끝내는 분기(IDLECOMBAT 의 ANY_INPUT 등): 같은 입력으로 루트 액션 재평가
			if (in != null) {
				Branch r = bestMatch(data.rootActions(), in, other());
				if (r != null && r.target() >= 0) {
					start(r, in);
				}
			}
			return;
		}
		Move m = data.move(b.target());
		if (m == null) {
			log.accept("기술 없음: " + b.target());
			return;
		}
		if (b.magic() >= 3 && b.magic() <= 6) {
			owner.useMagic(b.magic());
		}
		move = m;
		clip = data.animation(m);
		heldAtStart = in != null ? CombatInput.mask(in) : 0;
		accumDamage = 0;
		stanceTag = -1;
		hitLog.clear();
		firedEvents.clear();
		hitThisMove = false;
		if (clip != null) {
			anim.playClip(clip, b.startPhase() * clip.duration, m.speed() > 0 ? m.speed() : 1f);
		}
		log.accept(String.format("기술 %d %s (분기 입력 %s)", m.id(), clip != null ? clip.name : "?",
				CombatFile.inputName(b.input())));
	}

	private void end() {
		move = null;
		clip = null;
		anim.setRate(1f);
	}

	/**
	 * 이벤트 코드(13 피격, 14 사망, 16 막음, 17 적중, 21 잡기 원, 22 막힘)로 분기 발동 → 즉시 큐.
	 * 현재 기술의 분기 → 없으면 루트 액션. reaction 은 분기 +0x20 과 비교 (-1 = 무관).
	 */
	public void fire(int code, int reaction, Combatant attacker) {
		Other o = attacker == null ? Other.NONE
				: new Other(attacker.owner.hp(), attacker.owner.classId(), attacker.stanceTag);
		Branch c = null;
		if (move != null) {
			c = bestMatch(eventBranches(move.branches(), code, reaction), null, o);
		}
		if (c == null) {
			c = bestMatch(eventBranches(data.rootActions(), code, reaction), null, o);
		}
		if (c != null) {
			queued = c;
			queuedAt = 0;
			queuedPrio = 5;
		}
	}

	private static List<Branch> eventBranches(List<Branch> src, int code, int reaction) {
		List<Branch> l = new ArrayList<>();
		for (Branch b : src) {
			if (b.input() == code && (b.reaction() == -1 || b.reaction() == reaction)) {
				l.add(b);
			}
		}
		return l;
	}

	// ---------------- 이벤트 / 공격 ----------------

	private boolean eventActive(int type) {
		float t = progress();
		for (Event e : move.events()) {
			if (e.type() == type && t >= e.start() && t <= e.end()) {
				return true;
			}
		}
		return false;
	}

	private void runEvents(float t) {
		for (Event e : move.events()) {
			if (t < e.start() || t > e.end() || e.level() > owner.bladeLevel()) {
				continue;
			}
			boolean once = e.cond() == 0 || e.cond() == 2 || e.cond() == 6;
			if (once && !firedEvents.add(e.bit())) {
				continue;
			}
			switch (e.type()) {
				case 20 -> stanceTag = e.index(); // SET_1B0: 공격 자세 태그 → 피격자 반응 분기 선택
				case 21 -> {
					if ((e.cond() != 1 && e.cond() != 2) || hitThisFrame) {
						godMeter = Math.min(100f, godMeter + e.b()); // GOD_METER_ADD
					}
				}
				default -> {
				}
			}
		}
	}

	private void attacks(float t) {
		if (target == null || target.invulnerable()) {
			return;
		}
		Vector3f p = owner.position();
		float yaw = owner.yaw();
		Vector3f hit = new Vector3f((float) -Math.sin(yaw), 0, (float) -Math.cos(yaw)).mul(reach).add(p);
		Vector3f tp = target.position();
		float dx = tp.x - hit.x, dz = tp.z - hit.z;
		boolean inRange = dx * dx + dz * dz <= hitRadius * hitRadius;
		for (Attack a : move.attacks()) {
			if (t < a.start() || t > a.end()) {
				continue;
			}
			if (!inRange) {
				if (debug) {
					log.accept(String.format("  (빗나감 공격#%d 거리 %.0f)", a.index(), Math.sqrt(dx * dx + dz * dz)));
				}
				continue;
			}
			long key = ((long) System.identityHashCode(target) << 16) | a.index();
			if (hitLog.add(key)) {
				contact(a);
			}
		}
	}

	/** OnContact (10 문서 §15.4) 공격자 쪽. */
	private void contact(Attack a) {
		float dmg = a.damage() * damageScale.get();
		// 충격 벡터 (x 좌우, y 상하, z 전후 — 추정) 를 공격자 방향으로 회전
		Vector3f knock = new Vector3f(a.impulse()[0], a.impulse()[1], -a.impulse()[2]).rotateY(owner.yaw());
		Hittable.Result r = target.receive(a, this, dmg, knock);
		if (r == Hittable.Result.HIT) {
			hitThisFrame = hitThisMove = true;
			if (dmg > 0) {
				hitCounter++;
			}
			for (Event e : move.events()) {
				if (e.type() == 24) {
					hitPause = Math.max(hitPause, e.a()); // HIT_PAUSE
				}
			}
			fire(CombatInput.EV_HIT_CONFIRM, -1, null);
		} else if (r == Hittable.Result.BLOCKED) {
			fire(CombatInput.EV_WAS_BLOCKED, -1, null);
		}
		log.accept(String.format("  적중 공격#%d 기본 %d → 피해 %.1f (%s)", a.index(), a.damage(), dmg, r));
	}

	/**
	 * OnContact 피격자 쪽: 무적 → 방어(이벤트 16) → 누적 피해 / 경직(poise) → 사망(14)·잡기 원(21)·반응(13).
	 */
	public Hittable.Result takeHit(Attack a, Combatant attacker, float dmg) {
		if (invulnerable()) {
			return Hittable.Result.IGNORED;
		}
		int aflags = attacker.move != null ? attacker.move.flags() : 0;
		if (guarding() && (aflags & 2) == 0) {
			fire(CombatInput.EV_BLOCKED, a.blockReaction(), attacker);
			return Hittable.Result.BLOCKED;
		}
		accumDamage += dmg;
		boolean react = (aflags & 6) != 0 || move == null || (move.poise() != -1 && accumDamage > move.poise());
		boolean kill = owner.hp() > 0 && owner.hp() <= dmg;
		boolean circle = owner.applyDamage(dmg);
		if (react || kill || circle) {
			fire(kill ? CombatInput.EV_DEATH : circle ? CombatInput.EV_CIRCLE : CombatInput.EV_HIT,
					kill ? a.death() : a.reaction(), attacker);
		}
		return Hittable.Result.HIT;
	}
}
