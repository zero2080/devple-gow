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
	 * 공격 판정 대체(폴백): 정면 구. 공격자/피격자에 CDV 히트 볼륨({@link HitVolumes})이 없거나
	 * 공격 부위(Attack.part)에 해당하는 구가 없을 때만 쓴다 (34 문서).
	 * 혼돈의 블레이드는 사슬 무기라 사거리가 길다 (크레토스 키 ≈ 37 유닛).
	 */
	public static final float REACH = 55f, HIT_RADIUS = 55f;

	/** 분기 매칭 상대 정보 (폴링: 락온 대상, 이벤트: 공격자). */
	private record Other(float hp, int classId, int tag, boolean grabbable, boolean polled) {
		static final Other NONE = new Other(0, -1, -1, false, true);
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
	/** 애니 이벤트 SOUND(3/40): (이름 해시, 위치). 해시 = TWK 해시 (14 문서). */
	private java.util.function.BiConsumer<Integer, Vector3f> soundSink = (h, p) -> { };
	private HitVolumes volumes;
	public boolean debug;
	public float reach = REACH, hitRadius = HIT_RADIUS;
	public int hitCounter;
	public float godMeter;
	/** 기술 시작마다 1 증가 (같은 기술 재시작 구분 — 마법 스크립트 이벤트 1회 발동용). */
	private int moveSerial;
	private java.util.function.IntPredicate moveHold = id -> false;
	/** 이 combatant 애니 세트에 없는 기술 애니의 대체 (시간·이름만, 재생 안 함). 예: 사이클롭스 처형 애니는 적 세트에 붙지 않음. */
	private java.util.function.IntFunction<Animation> clipFallback = h -> null;
	private Animation vclip;
	private float vtime;
	/** 기술 시작(1035)/완료(1047) 통보: (이벤트 번호, 기술 ID). 원작 Combat_StartBranch 0x209610 / Combat_EndMove 0x209994. */
	private java.util.function.BiConsumer<Integer, Integer> moveSink = (c, id) -> { };
	/** 애니 이벤트 발동 통보 (SELF_HP·SCRIPT 등 보스 연출용). */
	private Consumer<Event> eventSink = e -> { };

	/** 원작 게임 → 스크립트 이벤트: 기술 시작 = 1035 (0x40B), 기술이 끝까지 재생 = 1047 (0x417). 인자 = 기술 ID. */
	public static final int EVENT_MOVE_START = 1035, EVENT_MOVE_DONE = 1047;

	public void onMove(java.util.function.BiConsumer<Integer, Integer> s) {
		moveSink = s != null ? s : (c, id) -> { };
	}

	public void onEvent(Consumer<Event> s) {
		eventSink = s != null ? s : e -> { };
	}

	public Combatant(CombatData data, Animator anim, CombatOwner owner) {
		this.data = data;
		this.anim = anim;
		this.owner = owner;
	}

	/** 공격자 측 피해 배율 (영웅 = Player_DamageScale, 적 = AI1 Damage Multiplier × 난이도 AI 배율). */
	public void setDamageScale(Supplier<Float> s) {
		damageScale = s;
	}

	public void onSound(java.util.function.BiConsumer<Integer, Vector3f> s) {
		soundSink = s;
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

	/** 원작 CDV 히트 볼륨 (공격 부위 + 피격 몸). null 이면 정면 구 근사. */
	public void setHitVolumes(HitVolumes v) {
		volumes = v;
	}

	public HitVolumes hitVolumes() {
		return volumes;
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

	/** 현재 기술에 SCRIPT(8) 이벤트가 있는지 (잡기 미니게임 등 스크립트 템플릿 구동). */
	public boolean hasScriptEvent() {
		return move != null && move.events().stream().anyMatch(e -> e.type() == 8);
	}

	/**
	 * 현재 진행률에서 활성인 SCRIPT(8) 이벤트 중 해시가 같은 것 (없으면 null).
	 * 해시 = SCR_ 명령 이름의 Hash127Upper (예 SCR_ThrowThunderbolt = 0x96EDE590, 35 문서 §1).
	 */
	/** {@link #activeScript} 와 같지만 같은 해시의 활성 이벤트 전부 (예 HEADNUKESLAM 의 SCR_MedusaNuke idx 0 + 3). */
	/** combatant+0x1AC 상태 비트 0x1000 (분기 flags 0x8 의 조건, 10 문서 §2.4). */
	public static final int STATE_TRIGGER = 0x1000;
	private int stateBits;

	/** 원작 sub_001E1B70 / sub_001E1BD0: +0x1AC 비트 켜기/끄기. */
	public void setStateBit(int bit, boolean on) {
		stateBits = on ? stateBits | bit : stateBits & ~bit;
	}

	public boolean stateBit(int bit) {
		return (stateBits & bit) != 0;
	}

	public java.util.List<CombatFile.Event> activeScripts(int hash) {
		if (move == null) {
			return java.util.List.of();
		}
		float t = progress();
		return move.events().stream().filter(e -> e.type() == 8 && e.hash() == hash && t >= e.start() && t <= e.end()).toList();
	}

	public CombatFile.Event activeScript(int hash) {
		if (move == null) {
			return null;
		}
		float t = progress();
		for (CombatFile.Event e : move.events()) {
			if (e.type() == 8 && e.hash() == hash && t >= e.start() && t <= e.end()) {
				return e;
			}
		}
		return null;
	}

	/**
	 * 애니가 없는 기술(예 USEHEAD 82 — 원작은 SCR_MedusaHead 가 useHeadTurnLeft/Right 를 직접 섞음)을
	 * 스크립트가 붙잡는 동안 유지. 기본 = 즉시 종료.
	 */
	public void setMoveHold(java.util.function.IntPredicate p) {
		moveHold = p != null ? p : id -> false;
	}

	/**
	 * 미니게임 실패 처리 (TimedPress 실패 핸들러 0x192A80): 현재 기술에서 입력 코드 0 이고
	 * 창이 이벤트 창을 포함하는 첫 분기(예 {@code [창]! REQUEST0 -> MAICYWMINIF1})를 실행.
	 */
	public boolean runWindowBranch(CombatFile.Event e) {
		if (move == null) {
			return false;
		}
		for (Branch b : move.branches()) {
			if (b.input() == 0 && b.winStart() <= e.start() + 1e-3f && b.winEnd() >= e.end() - 1e-3f && b.target() >= 0) {
				start(b, null);
				return true;
			}
		}
		return false;
	}

	/** 분기 조건 없이 기술 시작 (테스트·스크립트용: 처형 기술을 직접 재생). */
	public boolean forceMove(int moveId) {
		Move m = data.move(moveId);
		if (m == null) {
			return false;
		}
		end();
		queued = null; // 이전 기술의 대기 분기(예 루프 AUTO)가 새 기술 위에서 실행되지 않게
		move = m;
		moveSerial++;
		clip = data.animation(m);
		virtualClip(m);
		heldAtStart = 0;
		accumDamage = 0;
		stanceTag = -1;
		hitLog.clear();
		firedEvents.clear();
		hitThisMove = false;
		if (clip != null) {
			anim.playClip(clip, 0, m.speed() > 0 ? m.speed() : 1f);
		}
		log.accept(String.format("기술 %d %s (직접)", m.id(), clip != null ? clip.name : "?"));
		moveSink.accept(EVENT_MOVE_START, m.id());
		return true;
	}

	/** 애니 대체 조회 (애니 해시 → 다른 세트의 Animation). GrabSync 가 영웅 세트를 연결한다. */
	public void setClipFallback(java.util.function.IntFunction<Animation> f) {
		clipFallback = f != null ? f : h -> null;
	}

	/** 애니 해시로 이 combatant 세트의 애니 (없으면 null). */
	public Animation animationByHash(int hash) {
		return data.animation(hash);
	}

	private void virtualClip(Move m) {
		vtime = 0;
		vclip = clip == null ? clipFallback.apply(m.animHash()) : null;
	}

	/** 기술 인스턴스 번호 (시작마다 증가). */
	public int moveSerial() {
		return moveSerial;
	}

	/** 애니 진행을 외부에서 지정 (재생 속도 0 인 미니게임 기술: 게이지 = 진행률). */
	public void setProgress(float p) {
		if (clip != null) {
			anim.seek(Math.max(0, Math.min(1, p)) * clip.duration);
		} else if (vclip != null) {
			vtime = Math.max(0, Math.min(1, p)) * vclip.duration;
		}
	}

	/** 현재 기술의 애니 이름 (없으면 null). */
	public String clipName() {
		return clip != null ? clip.name : vclip != null ? vclip.name : null;
	}

	/** 현재 기술 진행률 0..1 (애니 시간 / 길이). */
	public float progress() {
		if (move != null && clip == null && vclip != null && vclip.duration > 0) {
			return Math.min(1f, vtime / vclip.duration);
		}
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
		if (volumes != null && anim.current() != null) {
			volumes.update(anim.pose().world(anim.skeleton()), owner.position(), owner.yaw());
		}
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
			if (clip == null && vclip != null && move.speed() > 0) {
				vtime += dt * move.speed();
			}
			boolean vdone = vclip == null || vtime >= vclip.duration;
			if ((clip == null && vdone && !moveHold.test(move.id()))
					|| (clip != null && !clip.looping() && anim.time() >= clip.duration)) {
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
		return target == null ? Other.NONE
				: new Other(target.hp(), target.classId(), target.stanceTag(), target.grabbable(), true);
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
			if (execAt > t && c.mode() == 3) {
				return; // 나중에 다시 평가
			}
			// AUTO 는 창 끝 시점으로 큐 (Combat_QueueBranch 0x209400 실행 시점 f12). 프레임 간격이 창 끝(예 0.98)을
			// 건너뛰어도 실행된다 (이전엔 "나중에 재평가" 라 1초 기술의 [0–0.98] AUTO 를 60fps 에서 놓침)
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
		if ((f & 0x20000) != 0 || (f & 0x80000) != 0 || (f & 0x800) != 0) {
			return -1; // 미상 플래그 / 차지는 아직 미지원
		}
		if ((f & 0x8) != 0 && (stateBits & STATE_TRIGGER) == 0) {
			return -1; // combatant+0x1AC&0x1000 필요 (메두사 Nuke: SCR_TriggerMedusaHead 가 켬)
		}
		// 대상 조건 (10 문서 §2.4): 0x40 = 무시, 아니면 대상 +0x1B8(잡기 가능) == (0x10 ? 참 : 거짓). 폴링(락온 대상)에만 적용
		if ((f & 0x40) == 0 && o.polled() && o.grabbable() != ((f & 0x10) != 0)) {
			return -1;
		}
		if ((f & 0x400) != 0 && target == null) {
			return -1;
		}
		if (b.level() > owner.bladeLevel()) {
			return -1;
		}
		if (b.magic() == 7 && !owner.artemisOwned()) { // 7 = 보유 조건 (장착 아님, 10 문서 §2.3)
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
		} else if ((f & 0x4000) == 0 && b.matchB() != o.tag()) {
			return -1; // 0x4000 = 특수 일치 (잡기 태그 14 등) → 위의 잡기 가능 조건으로 대체 [추정]
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
		if ((b.flags() & 0x8) != 0) {
			stateBits &= ~STATE_TRIGGER; // 실행 시 소비
		}
		if (b.target() == -2) {
			return; // "(block)" 분기: 이벤트를 소비만 하고 현재 기술 유지 (예 KILLWIN 의 EV_DEATH, 슈퍼아머 EV_HIT_REACT)
		}
		end();
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
		moveSerial++;
		clip = data.animation(m);
		virtualClip(m);
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
		moveSink.accept(EVENT_MOVE_START, m.id());
	}

	private void end() {
		// Combat_EndMove (0x209918): 진행률 ≥ 1 로 끝난 기술이면 1047 (AUTO 분기로 다음 기술에 넘어가도)
		Move done = move != null && progress() >= 0.999f ? move : null;
		move = null;
		clip = null;
		vclip = null;
		anim.setRate(1f);
		if (done != null) {
			moveSink.accept(EVENT_MOVE_DONE, done.id());
		}
	}

	/**
	 * 이벤트 코드(13 피격, 14 사망, 16 막음, 17 적중, 21 잡기 원, 22 막힘)로 분기 발동 → 즉시 큐.
	 * 현재 기술의 분기 → 없으면 루트 액션. reaction 은 분기 +0x20 과 비교 (-1 = 무관).
	 */
	public void fire(int code, int reaction, Combatant attacker) {
		// 공격자 쪽 이벤트(17 적중 확인, 22 막힘)는 attacker == null → 상대 = 맞은 대상(락온 대상)
		Other o = attacker == null ? other()
				: new Other(attacker.owner.hp(), attacker.owner.classId(), attacker.stanceTag, false, false);
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
			eventSink.accept(e);
			switch (e.type()) {
				case 20 -> stanceTag = e.index(); // SET_1B0: 공격 자세 태그 → 피격자 반응 분기 선택
				case 3, 40 -> soundSink.accept(e.hash(), owner.position()); // SOUND
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

	/**
	 * 공격 판정: 진행률이 판정 구간 [start, end] 안인 공격마다 (부위 히트박스 ∩ 상대 몸) 스윕 검사 → OnContact.
	 * 부위 = Attack.part = 공격자 CDV 재질 PartType (10 문서 §5.4). 데이터가 없으면 정면 구 근사.
	 */
	private void attacks(float t) {
		if (target == null || target.invulnerable()) {
			return;
		}
		HitVolumes victim = target.hitVolumes();
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
			boolean real = volumes != null && victim != null && !victim.isEmpty() && volumes.hasPart(a.part());
			boolean touch = real ? volumes.sweptHit(a.part(), victim) : inRange;
			if (!touch) {
				if (debug) {
					log.accept(String.format("  (빗나감 공격#%d 부위 %d %s)", a.index(), a.part(), real ? "히트박스"
							: String.format("근사 거리 %.0f", Math.sqrt(dx * dx + dz * dz))));
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
