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
	/** 이벤트 5 CAMERA_SHAKE (템플릿 번호) / 7 GLOBAL_TIME (지속 a, 배율 b) 받는 쪽 (0x206030 — 어느 전투원의 이벤트든 카메라·게임 시간에). */
	private java.util.function.IntConsumer shakeSink = i -> { };
	private java.util.function.BiConsumer<Float, Float> timeSink = (a, b) -> { };
	/** 마지막으로 발동한 HIT_PAUSE (이벤트 24) — 원작 combatant +0x44, 기술 시작 때 지움 (0x206C08 / 0x2061C0). */
	private Event hitPauseEvent;

	public void onCameraShake(java.util.function.IntConsumer s) {
		shakeSink = s != null ? s : i -> { };
	}

	private java.util.function.Consumer<Float> frameSink = b -> { };

	/** 이벤트 11 CAMERA_FRAME (영웅·적 구분 없음): 창 안 매 프레임 목표 가중치 b — 잡기 MAISKB00 0.75, 마무리 0.7. */
	public void onCameraFrame(java.util.function.Consumer<Float> s) {
		frameSink = s != null ? s : b -> { };
	}

	public void onGlobalTime(java.util.function.BiConsumer<Float, Float> s) {
		timeSink = s != null ? s : (a, b) -> { };
	}

	/** 히트스톱 (메시지 0x414 수신 0x2652E4: 개체 시간 배율 0.01 을 s 초) — 피격자 쪽. */
	public void pause(float s) {
		hitPause = Math.max(hitPause, s);
	}
	private boolean hitThisFrame;
	private boolean hitThisMove;
	/**
	 * 이벤트 조건 (+0x10) 의 근거 — 원작은 공격자 combatant 의 마지막 적중 / 처치 / 막힘 프레임 (+0xA4 / +0xA0 / +0xA8, 0x209CA0) 이
	 * "지금 프레임 − 1" 인지 본다 (0x206030 점프표 0x2B9190). 엔진은 접촉 때 켜고 다음 이벤트 처리 ({@link #runEvents}) 가 끈다.
	 */
	private boolean pendHit, pendKill, pendHitPlayer, pendBlocked;
	private float hitPause;
	private Hittable target;
	/** 대상 외에 같은 공격 판정을 받는 개체 (ATHN08B 가족 660: 분신 공격에 휘말림 — CombatFile 661 의 피격 분기 tgt=666). */
	private Supplier<java.util.List<? extends Hittable>> bystanders = java.util.List::of;
	private Supplier<Float> damageScale = () -> 1f;
	private Consumer<String> log = s -> { };
	/** 애니 이벤트 SOUND(3/40): (이름 해시, 위치). 해시 = TWK 해시 (14 문서). */
	private java.util.function.BiConsumer<Integer, Vector3f> soundSink = (h, p) -> { };
	private HitVolumes volumes;
	public boolean debug;
	public float reach = REACH, hitRadius = HIT_RADIUS;
	public int hitCounter;
	public float godMeter;
	/** GOD_METER_ADD 를 받을 combatant (null = 자기). 원작 게이지는 플레이어 전역 — 적 기술(ATHN08B 가족 포옹 865/866)의 충전도 크레토스에게 간다. */
	private Combatant godMeterOwner;
	/**
	 * 스틱 흔들기 게이지 = 플레이어 객체 +0x50 (sub_001D8B98, 매 프레임): 두 스틱 각각 |Δ스틱|·0.2·(1 + 0.6·(1 − |스틱|)) 중 큰 값을 더하고
	 * "Wiggle Meter Drain"(/Player/, 2.0)·dt 를 뺌 (≥ 0). 기술 시작 시 0 (0x209750). 분기 방향 조건 7/8/15 = 게이지 &gt; 2/4/15 (InputOK 0x2082BC)
	 * — 분신 잡기 EVIGRABHEROLOOP 861 "AUTO fall&gt;2 → 862 탈출" [확실: 디스어셈블].
	 */
	private float wiggle, wiggleLX, wiggleLY, wiggleRX, wiggleRY;
	public float wiggleDrain = 2f;
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

	/** 추가 이벤트 구독 (이펙트 등, {@link #onEvent} 와 별개로 여럿). 발동한 애니 이벤트마다 호출. */
	private final List<Consumer<Event>> eventListeners = new ArrayList<>();

	public void addEventListener(Consumer<Event> l) {
		eventListeners.add(l);
	}

	/** 적중 통보 (공격자 쪽 OnContact): 공격, 맞은 대상, 결과. MFX 타격 이펙트용 (40 문서 §6). */
	@FunctionalInterface
	public interface ContactListener {
		void contact(Attack a, Hittable victim, Hittable.Result r);
	}

	private final List<ContactListener> contactListeners = new ArrayList<>();

	public void addContactListener(ContactListener l) {
		contactListeners.add(l);
	}

	/** 기술 데이터 (처형 기술 조회 — {@link GrabRelation#finisher}). */
	public CombatData data() {
		return data;
	}

	public Combatant(CombatData data, Animator anim, CombatOwner owner) {
		this.data = data;
		this.anim = anim;
		this.owner = owner;
	}

	/** 공격자 측 피해 배율 (영웅 = Player_DamageScale, 적 = AI1 Damage Multiplier × 난이도 AI 배율). */
	/** 이 전투원이 주는 피해 배율 (영웅 = Damage Mult × 코스튬 × 무기, 적 = AI1 배율 × AI Damage Mult × …). */
	public float damageScale() {
		return damageScale.get();
	}

	public void setDamageScale(Supplier<Float> s) {
		damageScale = s;
	}

	public void onSound(java.util.function.BiConsumer<Integer, Vector3f> s) {
		soundSink = s;
	}

	public void onLog(Consumer<String> l) {
		log = l;
	}

	/** GOD_METER_ADD 이벤트를 다른 combatant(크레토스)의 게이지로 보낸다. */
	public void setGodMeterOwner(Combatant c) {
		godMeterOwner = c;
	}

	/** 스틱 흔들기 게이지 (플레이어 +0x50). */
	public float wiggle() {
		return wiggle;
	}

	private void updateWiggle(Input in, float dt) {
		float lx = in.leftX(), ly = in.leftY(), rx = in.rightX(), ry = in.rightY();
		float a = (float) (Math.hypot(lx - wiggleLX, ly - wiggleLY) * 0.2 * (1 + 0.6 * (1 - Math.min(1, Math.hypot(lx, ly)))));
		float b = (float) (Math.hypot(rx - wiggleRX, ry - wiggleRY) * 0.2 * (1 + 0.6 * (1 - Math.min(1, Math.hypot(rx, ry)))));
		wiggleLX = lx;
		wiggleLY = ly;
		wiggleRX = rx;
		wiggleRY = ry;
		wiggle = Math.max(0, wiggle + Math.max(a, b) - wiggleDrain * dt);
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

	/** 공격 판정을 대상과 함께 받는 개체 목록 (피격자 receive 만, 공격자 HIT_CONFIRM 분기는 대상 적중에서만). */
	public void setBystanders(Supplier<java.util.List<? extends Hittable>> s) {
		bystanders = s != null ? s : java.util.List::of;
		bystandersSameTeam = false;
	}

	/** 휘말림 후보가 같은 편 (+0xF4 &amp; 3 이 겹침) 이면 true — 그때는 공격 플래그 0x8 일 때만 피해 (0x203798). */
	private boolean bystandersSameTeam;

	public void setBystanders(Supplier<java.util.List<? extends Hittable>> s, boolean sameTeam) {
		setBystanders(s);
		bystandersSameTeam = sameTeam;
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
	/** +0x1AC 0x400 차지 완료 (이벤트 33 CHARGE_CHECK 또는 마법 스크립트 sub_001E1AB0, 분기 flags 0x800 의 조건). */
	public static final int STATE_CHARGED = 0x400;
	/** +0x1B4 차지 시간 (초): 기술 플래그 0x100 인 동안 누적. */
	private float holdTime;
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
				// 대기 중인 분기 (이 기술의 AUTO) 를 버린다 — 안 버리면 ATHN01F 침대 846 의 AUTO → 847 @0.28 이 실패 852 위에서 실행돼
				// 실패해도 미니게임이 이어졌다 (852 → 847 → … → 853 승리). 원작 실패 처리도 기술 요청 경로 (0x209378: 분기 큐 비움) [추정: 경로]
				queued = null;
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
		wiggle = 0;
		accumDamage = 0;
		stanceTag = -1;
		hitLog.clear();
		firedEvents.clear();
		hitPauseEvent = null;
		hitThisMove = false;
		if (clip != null) {
			anim.playClip(clip, 0, m.speed() > 0 ? m.speed() : 1f);
		}
		log.accept(String.format("기술 %d %s (직접)", m.id(), clip != null ? clip.name : "?"));
		moveSink.accept(EVENT_MOVE_START, m.id());
		return true;
	}

	/**
	 * 지금 기술을 끊고 기술 없음으로 (원작 0x25B8F8 → 0x1D3530: 1019 인자2 ≠ 0 이면 0x209918 Combat_EndMove 뒤 인자1 요청 — 요청할 기술이 없으면
	 * (ATHN01F 쌍둥이 A70 "REQUEST0 -> -", quitMime 1019 −1) 기술 없는 대기로 남는다 [확실: 경로 / 추정: 자세 태그 +0x1B0 도 지움]).
	 */
	public void stop() {
		end();
		queued = null;
		stanceTag = -1;
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

	/** 지금 진행률이 이 종류 이벤트의 창 안인지 (예 15 FLAG_1 = +0x1AC &amp; 1). */
	public boolean eventOn(int type) {
		return move != null && eventActive(type);
	}

	/**
	 * 블레이드 궤적 (이벤트 17 TRAIL_LEFT / 18 TRAIL_RIGHT → combatant +0x1AC 0x8 / 0x10, 0x205B4C 가 블레이드 0 / 1 의 부착물
	 * 종류 0·3·4 (궤적·불꽃 이미터·BladeLight) 를 켜고 끔, 45 문서 §3). 무기 레벨 조건은 다른 이벤트와 같이 적용.
	 * [근사: CombatFileSet "Trail Left/Right" 세트 플래그는 엔진이 쓰지 않음]
	 */
	public boolean trail(int side) {
		if (move == null) {
			return false;
		}
		float t = progress();
		int type = side == 0 ? 17 : 18;
		for (Event e : move.events()) {
			if (e.type() == type && t >= e.start() && t <= e.end() && e.level() <= owner.bladeLevel()) {
				return true;
			}
		}
		return false;
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
		if (in != null) {
			updateWiggle(in, dt);
		}
		if (volumes != null && anim.current() != null) {
			volumes.update(anim.pose().world(anim.skeleton()), owner.position(), owner.yaw(), owner.modelScale());
		}
		if (hitPause > 0) {
			hitPause -= dt;
			if (move != null && (pendHit || pendKill || pendHitPlayer || pendBlocked)) {
				runEvents(progress()); // 원작 0x206030 은 히트스톱 (개체 시간 0.01) 중에도 매 프레임 — 적중 다음 프레임의 조건 이벤트
			}
			return;
		}
		float t = progress();
		if (move != null) {
			// +0x1B4 (0x207108): 현재 기술 플래그에 0x100 이 있으면 프레임마다 +1/60, 없으면 0. 기술이 바뀌어도(같은 플래그면) 이어진다
			// (HITLIE 0x190 → HITLIE 반복 중에도 누적 → CHARGE_CHECK 2.5초 → 일어남)
			holdTime = (move.flags() & 0x100) != 0 ? holdTime + dt : 0;
			for (Event e : move.events()) {
				if (e.type() == 33 && t >= e.start() && t <= e.end() && holdTime >= e.a()) {
					stateBits |= STATE_CHARGED; // CHARGE_CHECK
				}
			}
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
				if (owner.classId() != 0 && owner.hp() <= 0 && !deathHandled && queued == null) {
					// 원작 0x208A98 끝 (0x209024–0x20907C): 분기 큐가 비어 있고 HP ≤ 0 이면 <b>매 프레임</b> EV_DEATH(14) 를 맞춰 넣는다 —
					// 죽던 순간 기술의 EV_DEATH 가 (block) 이면 그 기술이 끝난 뒤 루트 액션의 EV_DEATH 로 이어진다 [확실: 코드].
					// 선창 히드라: 830 BHAENC1GRAB 의 SELF_HP (0.79) 로 죽음 → (block) → 끝 → A150 EV_DEATH → 150 BHAENC1GRAB @0.83
					// (1035 150 → playerBlock-DetectDeath → LD11, 길막이 치워짐). 엔진은 죽음 처리가 아직 기술로 이어지지 않았을 때 한 번만 [근사]
					deathHandled = true;
					fire(CombatInput.EV_DEATH, -1, null);
					if (queued != null) {
						Branch b = queued;
						queued = null;
						start(b, in);
					}
				}
			}
		}
	}

	/** 사망이 이미 EV_DEATH 분기 (기술) 로 처리됐는지 — {@link #update} 의 기술 끝 사망 재검사용. */
	private boolean deathHandled;

	/** AI/스크립트의 기술 요청 (sub_00209378): 해당 기술로 가는 루트 액션을 Match 해서 시작. */
	public boolean request(int moveId) {
		for (Branch b : data.rootActions()) {
			if (b.target() == moveId && score(b, other()) >= 0) {
				// 원작 Combat_RequestAction 0x209378 은 분기 큐 링 (+0x1C0: +0x1C5 머리 = 0xFF, +0x1C6 꼬리 = 0) 을 비우고 넣는다 [확실: 코드].
				// 예전엔 이전 기술의 대기 AUTO 가 남아 새 기술 위에서 실행 — 아기 히드라 111 루프 중 스크립트 1019 113 (닻 고정) 이 끝나면
				// 111 의 AUTO 로 쓰러진 루프에 돌아갔다 (113 자체 AUTO → 113 @0.10 이 고정 유지, 37 문서 §4.4)
				queued = null;
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
			if (cands.isEmpty()) {
				return;
			}
			if (queued != null && queuedPrio >= best) {
				// 창 끝을 기다리는 분기(예 EVIGRABHEROLOOP 861 의 [0–1] AUTO → 863)가 있어도, 나중에 조건이 맞은 즉시(!) 분기
				// (같은 창의 "AUTO fall>2 → 862")는 앞선다 [추정: 원작 큐 교체 규칙 미분석 — 그대로면 탈출 분기가 영영 막힘]
				List<Branch> imm = cands.stream().filter(b -> b.immediate() && b != queued).toList();
				if (queuedAt <= t || imm.isEmpty() || queuedPrio > best) {
					return;
				}
				cands = imm;
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
			case 7 -> !right && wiggle > 2; // 플레이어 +0x50 > 2 (스틱 흔들기 게이지)
			case 8 -> !right && wiggle > 4;
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
			default -> d == 15 && wiggle > 15;
		};
	}

	/** Match (0x208578). 통과 못하면 -1, 통과하면 와일드카드 점수 (낮을수록 구체적). */
	private int score(Branch b, Other o) {
		int f = b.flags();
		int state = owner.stateMask();
		if ((f & 0x100A6) != 0 && (f & state) == 0) {
			return -1;
		}
		if ((f & 0x200) == 0 && ((f & 0x100) != 0) != owner.godMode()) {
			return -1;
		}
		if ((f & 0x8000) == 0 && ((f & 0x2000) != 0) != owner.artemisEquipped()) {
			return -1;
		}
		if ((f & 0x20000) != 0 || (f & 0x80000) != 0) {
			return -1; // 미상 플래그
		}
		if ((f & 0x800) != 0 && (stateBits & STATE_CHARGED) == 0) {
			return -1; // 차지 완료 필요 (10 문서 §2.4)
		}
		if ((f & 0x8) != 0 && (stateBits & STATE_TRIGGER) == 0) {
			return -1; // combatant+0x1AC&0x1000 필요 (메두사 Nuke: SCR_TriggerMedusaHead 가 켬)
		}
		// 잡기 조건 (10 문서 §2.4): 0x40 = 무시, 아니면 +0x1B8(잡기 가능) == (0x10 ? 참 : 거짓). 누구의 +0x1B8 인지는 0x208800:
		// 플레이어 (owner+0x174 < 0) 는 공격 대상 (+0x80 핸들, 여기서는 폴링한 락온 대상), 그 밖 (적·보스) 은 자기 owner (+0x48).
		// 적 피격 반응이 이것으로 갈린다 — 미노타우로스 GRAB(태그 200) 피격: 잡기 가능 → 400 MAIMIN01 (0x148212), 아니면 3 blockShove (0x148202)
		if ((f & 0x40) == 0) {
			boolean want = (f & 0x10) != 0;
			if (owner.classId() == 0 ? o.polled() && o.grabbable() != want : selfGrabbable() != want) {
				return -1;
			}
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
		} else if ((b.matchB() == o.tag()) == ((f & 0x4000) != 0)) {
			// 0x4000 = 태그 부정 ("상대 자세 태그 ≠ 이 값") [확실: 0x208578 의 0x2089B4–0x208A04 — 같으면 0x4000 일 때 실패,
			// 다르면 0x4000 일 때만 통과]. 예전 엔진은 0x4000 을 "태그 무시" 로 읽어, 선창 히드라 기절 (SET_1B0 830) 에 ○ GRAB 의
			// HIT_CONFIRM 이 830 BHAENC1GRAB (tag 830) 대신 같은 점수의 204 GRABREJECT (tag 830, 0x4000) 로 갈 수 있었다 (37 문서 §4.3).
			// 원작 +0x1B0 기본값은 0 (매 프레임 0x2061C8 이 지움), 상대 없음은 −2 — 엔진 기본 −1 은 어느 분기 태그와도 같지 않아 같은 결과
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

	private boolean selfGrabbable() {
		return owner instanceof Hittable h && h.grabbable();
	}

	private void start(Branch b, Input in) {
		if ((b.flags() & 0x8) != 0) {
			stateBits &= ~STATE_TRIGGER; // 실행 시 소비
		}
		if ((b.flags() & 0x800) != 0) {
			stateBits &= ~STATE_CHARGED;
		}
		if (b.input() == CombatInput.EV_DEATH && b.target() >= 0) {
			deathHandled = true;
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
		wiggle = 0; // 0x209750: 기술 시작 시 플레이어 +0x50 = 0
		// 기술 시작 시 +0x1AC = 세트 플래그 | (이전 & 0x4004) → 차지·트리거 비트는 새 기술마다 지움, 누른 시간 0 (10 문서 §17)
		stateBits &= 0x4004;
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
	private void addGodMeter(float v) {
		godMeter = Math.min(100f, godMeter + v);
	}

	/** 전투 combatant 가 아닌 공격자(투사체: Arrow "Context" = 자세 태그)의 이벤트. 예 BMI 통나무 Arrow/060 Context 456 → A210/A245. */
	public void fireFrom(int code, int reaction, float attackerHp, int attackerClass, int attackerTag) {
		Other o = new Other(attackerHp, attackerClass, attackerTag, false, false);
		Branch c = move != null ? bestMatch(eventBranches(move.branches(), code, reaction), null, o) : null;
		if (c == null) {
			c = bestMatch(eventBranches(data.rootActions(), code, reaction), null, o);
		}
		if (c != null) {
			queued = c;
			queuedAt = 0;
			queuedPrio = 5;
		}
	}

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

	/** 카메라 흔들림 (5, 0x2068A8 → 0x1F09C8) / 전역 시간 (7, 0x2068DC → 0x337C1C 지속 a · 0x337C20 배율 b). */
	private void presentation(Event e) {
		if (e.type() == 5) {
			shakeSink.accept(e.index());
		} else {
			timeSink.accept(e.a(), e.b());
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
			if (e.cond() == 6 && owner.hp() > 0) {
				// 조건 6 = 사망 (10 문서 §3): 살아 있으면 발동하지 않는다 — 메두사 여왕 hitLand·hitBounce 의 SCR_ExplodeDeath (cond 6) 가
				// 넘어지기만 해도 사망시켰다 (머리 뜯기 260 의 것은 cond 0, 자동 플레이 43 문서)
				continue;
			}
			// 조건 1–5 (0x206030 점프표 0x2B9190) [확실]: 1 적중 프레임마다 / 2 적중 1 회 / 3 처치 / 4 플레이어에게 적중 / 5 막힘 —
			// 이 전투원의 마지막 적중 (+0xA4)·처치 (+0xA0)·막힘 (+0xA8) 프레임이 직전 프레임일 때만. 예전 엔진은 SOUND·HIT_PAUSE·GOD_METER 등을
			// 조건 없이 창 안에서 냈다: DASHMULTISTAB 의 막힘 전용 HIT_PAUSE 0.04 가 적중용 0.06 을 덮고, 막힘 흔들림·진동이 휘두를 때마다 났다
			boolean ok = switch (e.cond()) {
				case 1, 2 -> pendHit;
				case 3 -> pendKill;
				case 4 -> pendHitPlayer;
				case 5 -> pendBlocked;
				default -> true;
			};
			if (!ok) {
				continue;
			}
			if ((e.type() == 5 || e.type() == 7) && (e.cond() == 1 || e.cond() == 2)) {
				continue; // 적중 조건 흔들림·전역 시간은 접촉 순간에 이미 (contact)
			}
			// 11 CAMERA_FRAME 은 once 비트를 다시 지워 창 안에서 매 프레임 (0x2069F8)
			boolean once = e.type() != 11 && (e.cond() == 0 || e.cond() == 2 || e.cond() == 6);
			if (once && !firedEvents.add(e.bit())) {
				continue;
			}
			eventSink.accept(e);
			for (Consumer<Event> l : eventListeners) {
				l.accept(e);
			}
			switch (e.type()) {
				case 20 -> stanceTag = e.index(); // SET_1B0: 공격 자세 태그 → 피격자 반응 분기 선택
				case 3, 40 -> soundSink.accept(e.hash(), owner.position()); // SOUND
				case 21 -> (godMeterOwner != null ? godMeterOwner : this).addGodMeter(e.b()); // GOD_METER_ADD (조건은 위에서)
				case 24 -> hitPauseEvent = e; // HIT_PAUSE: 적중 때 공격자·피격자 양쪽 (contact)
				case 11 -> frameSink.accept(e.b()); // CAMERA_FRAME (0x2069F8 → 0x337C24 → 카메라 관리자 +0x7A4)
				case 5, 7 -> presentation(e); // 조건 없는 것·막힘/처치 조건은 여기, 적중 조건 (1·2) 은 contact 에서
				default -> {
				}
			}
		}
		pendHit = pendKill = pendHitPlayer = pendBlocked = false;
	}

	/**
	 * NO_BLOCK (이벤트 12, 0x1AC |= 0x40) 창 안인가 — 원작은 매 프레임 0x206030 이 비트를 지우고 (0x2061AC: &amp; 0x5400) 창 안 이벤트가 다시 켠다.
	 * OnContact (0x2040FC) 와 0x203248 은 이 비트가 있으면 AI 자동 방어 (0x1CBFF8) 를 묻지 않는다 [확실].
	 * 데이터: 미노타우로스·켄타우로스·다이버 공격 기술과 무거운 피격 (HITSPIN/BOUNCE/LAUNCH/LAND/AIR) 은 전 구간 NO_BLOCK,
	 * 가벼운 피격 (HITFRONT/BACK/LEFT/RIGHT, 일부 HITSTAGGER) 과 사티로스 공격 기술은 없음 → 맞는 도중·자기 연속기 도중에도 막는다.
	 */
	public boolean noBlock() {
		return move != null && eventActive(12);
	}

	// ---------------- 밀어내기 (충격 속도) ----------------

	/** 원작 owner +0xF0 (밀어내기 속도, 월드 단위/초) — 0x2039E0 이 덮어쓰고 이동 단계가 위치에 더한다. y 는 쓰지 않음 [근사]. */
	private final Vector3f impulse = new Vector3f();
	/** Sold +0x84 질량 (크라토스·해골·미노타우로스 100, 사이클롭스·하피 1000) / +0x88 지상 마찰 계수 (거의 3) / CombatFileSet 충격 배율. */
	private float impMass = 100f, impFriction = 3f, impSx = 1f, impSy = 1f, impSz = 1f;
	/** Sold +0x1C 비트 3 (0x2438E8: 0x243700 → +0x1C &amp; 8) — 밀리지 않음 (해골 궁수 031/033/035, 히드라 250/252/253 …). */
	private boolean immovable;

	/**
	 * 밀어내기 물리 값. 기본 = 크라토스 (Sold_000 질량 100·마찰 3, CombatFileSet 000 배율 1/1/1) [확실: 데이터].
	 *
	 * @param sx CombatFileSet #35f40b1b (+0x3C, 옆) / {@code sy} #7a4a8930 (+0x40, 위) / {@code sz} #df4375e3 (+0x44, 앞) — 0x202868 [확실]
	 */
	public void setImpulsePhysics(float mass, float friction, float sx, float sy, float sz, boolean immovable) {
		this.impMass = mass > 0 ? mass : 100f;
		this.impFriction = friction;
		this.impSx = sx;
		this.impSy = sy;
		this.impSz = sz;
		this.immovable = immovable;
	}

	/** 지금 밀어내기 속도 (읽기 전용으로 쓸 것). */
	public Vector3f impulse() {
		return impulse;
	}

	/**
	 * 0x2039E0 (← OnContact 방어 0x204164, 피격 반응 0x204458) [확실]: 0x203890 이 공격자 → 피격자 수평 방향 z' 와 위 (0, 1, 0), 옆 x' = 위 × z' 로
	 * v = z'·vec.z·sz + 위·vec.y·sy + x'·vec.x·sx 를 만들고, ÷ 질량 (vslot 0xB4 = Sold +0x84) × 16 을 속도 +0xF0 에 <b>덮어쓴다</b> (0x2438E8,
	 * Sold 비트 3 이면 무시). 재생 속도 상태 (+0x2C/+0x30/+0x34, 이벤트 14) 도 1 로 되돌린다.
	 * 공격 플래그 0x4 (+0x1A) 면 방향 = 공격자 정면 (−행렬 2행 [추정: 정면]) − 0.3·정규화(피격자 − 공격자) (0x204044).
	 *
	 * @param base 0 지상 피격 / 3 공중 피격 / 6 방어 (Attack +0x00 / +0x06 / +0x0C)
	 */
	void push(Combatant attacker, Attack a, int base) {
		if (attacker == null || attacker.owner == null || a.impulse() == null || a.impulse().length < base + 3) {
			return;
		}
		Vector3f me = owner.position(), at = attacker.owner.position();
		float dx = me.x - at.x, dz = me.z - at.z, len = (float) Math.hypot(dx, dz);
		float ay = attacker.owner.yaw();
		float fx = (float) -Math.sin(ay), fz = (float) -Math.cos(ay);
		if (len < 1e-4f) {
			dx = fx;
			dz = fz;
		} else {
			dx /= len;
			dz /= len;
		}
		if ((a.flags() & 0x4) != 0) {
			dx = fx - 0.3f * dx;
			dz = fz - 0.3f * dz;
		}
		float n = (float) Math.hypot(dx, dz);
		if (n < 1e-4f) {
			return;
		}
		dx /= n;
		dz /= n;
		float[] v = a.impulse();
		float side = v[base] * impSx, fwd = v[base + 2] * impSz;
		// x' = 위 × z' = (z'.z, 0, −z'.x)
		float k = 16f / impMass;
		if (immovable) {
			return;
		}
		impulse.set((dx * fwd + dz * side) * k, 0, (dz * fwd - dx * side) * k);
	}

	/**
	 * 한 프레임 밀어내기 이동량 (월드). 지상이면 0x1E8F80 → 0x1E0910 감속: |v| &gt; 1.6 이면 v ×= clamp(1 − 마찰·9.82·16·dt / |v|, 0, 1),
	 * 아니면 0 (쿨롱 마찰 — 크라토스 470 단위/초²: 방어 (0,0,1000) → 160 단위/초, 0.34 초에 27 단위). 공중 (+0x174 &amp; 0x4C) 은 줄지 않는다 [확실].
	 * [추정: 시간 배율 vslot 0xC8 / +0x16C = 1]
	 */
	public Vector3f stepImpulse(float dt, boolean grounded) {
		Vector3f d = new Vector3f(impulse.x * dt, 0, impulse.z * dt);
		if (grounded) {
			float s = (float) Math.hypot(impulse.x, impulse.z);
			if (s > 1.6f) {
				impulse.mul(Math.max(0f, Math.min(1f, 1f - impFriction * 9.82f * 16f * dt / s)));
			} else {
				impulse.zero();
			}
		}
		return d;
	}

	/** 밀어내기 속도를 버린다 (매달림·줄·동기 등 이동 단계가 다른 상태). */
	public void clearImpulse() {
		impulse.zero();
	}

	/**
	 * 공격 판정: 진행률이 판정 구간 [start, end] 안인 공격마다 (부위 히트박스 ∩ 상대 몸) 스윕 검사 → OnContact.
	 * 부위 = Attack.part = 공격자 CDV 재질 PartType (10 문서 §5.4). 데이터가 없으면 정면 구 근사.
	 */
	/**
	 * 지금 진행 중인 공격(피해 &gt; 0 창)이 구(중심 c, 반지름 r)에 닿는지 — 부서지는 물체 등 전투 대상이 아닌 것용.
	 * 공격 부위 히트박스(CDV 구)가 있으면 그 구와, 없으면 정면 reach 근사 (34 문서). 공격 번호 집합으로 한 번만: key 로 중복 방지는 호출 측.
	 *
	 * @return 닿은 공격 (없으면 null)
	 */
	public Attack strikes(Vector3f c, float r) {
		if (move == null) {
			return null;
		}
		float t = progress();
		for (Attack a : move.attacks()) {
			if (t < a.start() || t > a.end() || a.damage() <= 0) {
				continue;
			}
			if (volumes != null && volumes.hasPart(a.part())) {
				for (float[] s : volumes.spheres(a.part())) {
					float dx = s[0] - c.x, dy = s[1] - c.y, dz = s[2] - c.z, rr = s[3] + r;
					if (dx * dx + dy * dy + dz * dz <= rr * rr) {
						return a;
					}
				}
			} else {
				Vector3f p = owner.position();
				float yaw = owner.yaw();
				Vector3f hit = new Vector3f((float) -Math.sin(yaw), 0, (float) -Math.cos(yaw)).mul(reach).add(p);
				float dx = c.x - hit.x, dz = c.z - hit.z, rr = hitRadius + r;
				if (dx * dx + dz * dz <= rr * rr) {
					return a;
				}
			}
		}
		return null;
	}

	private void attacks(float t) {
		for (Hittable b : bystanders.get()) {
			if (b != target && b != null && !b.invulnerable()) {
				bystanderHits(b, t);
			}
		}
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

	/** 대상 아닌 개체 판정: 대상과 같은 부위 히트박스 스윕 (없으면 정면 구). 적중 시 피격자 처리만. */
	private void bystanderHits(Hittable b, float t) {
		HitVolumes victim = b.hitVolumes();
		Vector3f p = owner.position();
		float yaw = owner.yaw();
		Vector3f hit = new Vector3f((float) -Math.sin(yaw), 0, (float) -Math.cos(yaw)).mul(reach).add(p);
		Vector3f bp = b.position();
		float dx = bp.x - hit.x, dz = bp.z - hit.z;
		boolean inRange = dx * dx + dz * dz <= hitRadius * hitRadius;
		boolean sameTeam = bystandersSameTeam;
		for (Attack a : move.attacks()) {
			if (t < a.start() || t > a.end()) {
				continue;
			}
			if (sameTeam && (a.flags() & 0x8) == 0) {
				continue; // 같은 편은 공격 플래그 0x8 (날아가는 몸 — 던진 해골 MAISKB02, HITSPIN, AIRTHROW …) 일 때만 (0x203798)
			}
			boolean real = volumes != null && victim != null && !victim.isEmpty() && volumes.hasPart(a.part());
			if (!(real ? volumes.sweptHit(a.part(), victim) : inRange)) {
				continue;
			}
			if (hitLog.add(((long) System.identityHashCode(b) << 16) | a.index())) {
				float dmg = a.damage() * damageScale.get();
				Vector3f knock = new Vector3f(a.impulse()[0], a.impulse()[1], -a.impulse()[2]).rotateY(owner.yaw());
				boolean alive = b.hp() > 0;
				Hittable.Result r = b.receive(a, this, dmg, knock);
				noteContact(b, r, alive);
				log.accept(String.format("  휘말림 공격#%d → 피해 %.1f (%s)", a.index(), dmg, r));
			}
		}
	}

	/**
	 * 0x209D00: 지금 공격 창 안에서 부위 part 인 첫 공격 레코드의 피해 (없으면 0). 블레이드 사슬 적 스윕의 조건.
	 */
	public float activeDamage(int part) {
		if (!active() || move == null) {
			return 0;
		}
		float t = progress();
		for (Attack a : move.attacks()) {
			if (t >= a.start() && t <= a.end() && a.part() == part) {
				return a.damage();
			}
		}
		return 0;
	}

	/** 마지막 {@link #chainSweep} 에서 선분이 개체를 지났는가 (0x337C18). */
	public boolean chainTouched;

	/**
	 * 블레이드 사슬 적 스윕 (0x1FAFC8 의 0x1FB1B4 – 0x1FB2C0, 45 문서 §3.7) [확실: 조건·부위 / 추정: 피격 구 필터]:
	 * 손 밖 블레이드의 부위 part (왼쪽 2 / 오른쪽 3) 나 쌍 11 의 피해가 0 이 아니면 (0x209D00 두 번), 시작점 (영웅 x, 골반 y, 영웅 z) →
	 * 끝점마다 선분 질의 (0x11E190, 질의 0x100003, 콜백 0x1FAF40) 에 걸린 개체에 OnContact (0x203AF0) 를 부위 part 와 11 로 두 번.
	 * 같은 (개체, 공격 번호) 는 기술당 한 번 (손 기술 판정과 같은 기록).
	 *
	 * {@link #chainTouched} = 이번 호출에서 선분이 개체 피격 구를 하나라도 지남 (콜백 0x1FAF40 이 OnContact 결과와 관계없이 0x337C18 = 1 →
	 * 블레이드 +0xA4 = 0, 피 묻은 블레이드 시간).
	 *
	 * @return 이번에 새로 맞힌 공격 수
	 */
	public int chainSweep(int part, Vector3f from, java.util.List<Vector3f> to) {
		chainTouched = false;
		if (to.isEmpty() || activeDamage(part) == 0 && activeDamage(HitVolumes.PART_TWIN) == 0) {
			return 0;
		}
		int n = 0;
		java.util.List<Hittable> victims = new ArrayList<>();
		if (target != null) {
			victims.add(target);
		}
		for (Hittable b : bystanders.get()) {
			if (b != null && b != target) {
				victims.add(b);
			}
		}
		float t = progress();
		for (Hittable v : victims) {
			HitVolumes hv = v.hitVolumes();
			if (v.invulnerable() || hv == null || hv.isEmpty()) {
				continue;
			}
			boolean touch = false;
			for (Vector3f p : to) {
				if (hv.segmentHit(from, p)) {
					touch = true;
					break;
				}
			}
			if (!touch) {
				continue;
			}
			chainTouched = true;
			for (int p : new int[]{part, HitVolumes.PART_TWIN}) {
				for (Attack a : move.attacks()) {
					if (t < a.start() || t > a.end() || a.part() != p) {
						continue;
					}
					if (!hitLog.add(((long) System.identityHashCode(v) << 16) | a.index())) {
						continue;
					}
					n++;
					if (v == target) {
						contact(a);
					} else {
						float dmg = a.damage() * damageScale.get();
						Vector3f knock = new Vector3f(a.impulse()[0], a.impulse()[1], -a.impulse()[2]).rotateY(owner.yaw());
						boolean alive = v.hp() > 0;
						Hittable.Result r = v.receive(a, this, dmg, knock);
						noteContact(v, r, alive);
						log.accept(String.format("  사슬 휘말림 공격#%d → 피해 %.1f (%s)", a.index(), dmg, r));
					}
				}
			}
		}
		return n;
	}

	/** 접촉 결과를 이벤트 조건 근거로 (적중 +0xA4 / 처치 +0xA0 — 0x2032C0, 막힘 +0xA8 — 0x209CA0, 마지막 적중 상대 = 플레이어 → 조건 4). */
	private void noteContact(Hittable victim, Hittable.Result r, boolean wasAlive) {
		if (r == Hittable.Result.HIT) {
			pendHit = true;
			pendHitPlayer |= victim.classId() == 0;
			pendKill |= wasAlive && victim.hp() <= 0;
		} else if (r == Hittable.Result.BLOCKED) {
			pendBlocked = true;
		}
	}

	/** OnContact (10 문서 §15.4) 공격자 쪽. */
	private void contact(Attack a) {
		float dmg = a.damage() * damageScale.get();
		// 충격 벡터 (x 좌우, y 상하, z 전후 — 추정) 를 공격자 방향으로 회전
		Vector3f knock = new Vector3f(a.impulse()[0], a.impulse()[1], -a.impulse()[2]).rotateY(owner.yaw());
		Hittable victim = target;
		boolean alive = victim.hp() > 0;
		Hittable.Result r = target.receive(a, this, dmg, knock);
		for (ContactListener l : contactListeners) {
			l.contact(a, victim, r);
		}
		noteContact(victim, r, alive);
		if (r == Hittable.Result.HIT) {
			hitThisFrame = hitThisMove = true;
			if (dmg > 0) {
				hitCounter++;
			}
			// HIT_PAUSE (0x203688): 마지막으로 발동한 이벤트 24 의 a 초를 공격자와 피격자 양쪽에 (메시지 0x414 ×2)
			if (hitPauseEvent != null) {
				hitPause = Math.max(hitPause, hitPauseEvent.a());
				victim.hitPause(hitPauseEvent.a());
			}
			// 적중 조건 (1 적중 프레임마다 / 2 적중 1 회) 의 흔들림·전역 시간 — 창 안에서
			float tp = progress();
			for (Event e : move.events()) {
				if ((e.type() == 5 || e.type() == 7) && (e.cond() == 1 || e.cond() == 2) && tp >= e.start() && tp <= e.end()
						&& e.level() <= owner.bladeLevel() && (e.cond() == 1 || firedEvents.add(e.bit()))) {
					presentation(e);
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
		if (guarding()) {
			// 0x204158: 방어 자세 (기술 +0x13 == 1) 면 방향과 관계없이 (뒤에서 맞아도 — 각도 검사 없음 [확실]) 방어 밀어내기 (+0x0C) 부터.
			// 방어 불가 공격 (기술 flags & 2: 사이클롭스 전 공격, 세이렌 비명, 히드라 물기, 영웅 GRAB·COMBO5A·신 모드 7xx) 이면 이어서 피해·반응
			push(attacker, a, 6);
			if ((aflags & 2) == 0) {
				// 막힘: 피해 없음 (깎이는 피해 없음), 히트스톱 없음 — 피격자 이벤트 16 (인자 = 공격 +0x1B, 1 = BLOCKBREAK), 공격자 22 (0x2042E4)
				fire(CombatInput.EV_BLOCKED, a.blockReaction(), attacker);
				return Hittable.Result.BLOCKED;
			}
		}
		accumDamage += dmg;
		boolean react = (aflags & 6) != 0 || move == null || (move.poise() != -1 && accumDamage > move.poise());
		boolean kill = owner.killedBy(dmg);
		boolean circle = owner.applyDamage(dmg);
		if (react || kill || circle) {
			fire(kill ? CombatInput.EV_DEATH : circle ? CombatInput.EV_CIRCLE : CombatInput.EV_HIT,
					kill ? a.death() : a.reaction(), attacker);
			// 0x2044FC: 반응이 난 피격만 넉백 — 지상 (+0x174 & 3) 은 +0x00, 공중 (& 0x4C) 은 +0x06, 벽·줄은 없음
			int st = owner.stateMask();
			if ((st & 0x2) != 0) {
				push(attacker, a, 0);
			} else if ((st & 0x4) != 0) {
				push(attacker, a, 3);
			}
		}
		return Hittable.Result.HIT;
	}
}
