package devple.gow.engine.boss;

import devple.gow.engine.combat.CombatInput;
import devple.gow.engine.combat.EnemyFactory;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.input.Input;
import devple.gow.engine.script.ScriptActor;
import devple.gow.engine.script.ScriptListener;
import devple.gow.tools.combat.CombatFile;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 보스전 하나 (레벨 단위). 원작 보스는 전용 C++ 클래스가 아니라 일반 Enemy1 + 결정 트리 + 레벨 스크립트(ESC)로 돌아간다 —
 * 이 클래스는 그 사이를 잇는 것만 한다:
 * <ul>
 *   <li>레벨 WAD 안의 보스 리소스 구성 ({@link BossKinds}) — SpawnEnemy TemplateId 로 고름</li>
 *   <li>전투 ↔ 스크립트 이벤트 ({@link CombatScriptBridge}: 1035/1047/1039/1019/1054)</li>
 *   <li>애니 이벤트: SELF_HP(10) 자기 피해/회복 (0x2069AC: 양수 = 피해 경로 0x204690, 음수 = HP 더함 0x1E1758),
 *       SCR_ExplodeDeath(0xBA76AD70) = 사망, SCR_SonicScream(0x9E58F03E) 밀어내기 [근사]</li>
 *   <li>동기 기술 ({@link BossSync})</li>
 * </ul>
 */
public abstract class BossFight {

	public static final int SCR_EXPLODE_DEATH = 0xba76ad70, SCR_SONIC_SCREAM = 0x9e58f03e, SCR_FLASH_SPRITE = 0x74b9d718,
			SCR_REGEN_HEALTH_BAR = 0xe6a829f7, SCR_EYE_BEAM_ON = 0x639a7bc2, SCR_EYE_BEAM_OFF = 0x69a3618c;
	/** 애니 이벤트 SELF_HP (eventName 10). */
	public static final int EV_SELF_HP = 10;

	public final String level;
	protected final Map<Integer, EnemyFactory.Kind> kinds = new LinkedHashMap<>();
	/** 본체 템플릿 (히드라 250, 메두사 62, 아레스 600/601). */
	protected final Set<Integer> bossTemplates = new HashSet<>();
	protected EnemyFighter boss;
	protected ScriptActor actor;
	protected EnemyFactory.Kind bossKind;
	protected BossSync sync;
	protected BossArena arena;
	private String phase = "대기";
	private final Set<Long> applied = new HashSet<>();
	/** 단계 전환 기록 (테스트·디버그). */
	public final List<String> phases = new ArrayList<>();
	/** 소리 뱅크만 싣는 가짜 적 템플릿 (원작 "FakeBHAsnd", "FkSnd Ari A1A", ME3SoundBankFake 901 — 모델 없음): 스폰하지 않음. */
	protected final Set<Integer> silent = new HashSet<>();
	/** 기타 적 (보스 쫄 등). */
	protected final Map<EnemyFighter, ScriptActor> minions = new HashMap<>();
	private boolean defeated;
	/** 데모/검증: 미니게임을 스스로 입력 ({@link AutoPad}). GowMain --boss-demo. */
	public boolean autoPlay;
	private final AutoPad autoPad = new AutoPad();
	/** 데모 경과 시간. */
	protected float demoTime;
	private int pendingContact = -1;
	private float pendingDamage;
	/** 화면 버튼 프롬프트 (SCR_FlashSprite idx, -1 = 없음). */
	public int prompt = -1;

	protected BossFight(String level) {
		this.level = level;
	}

	/** SpawnEnemy TemplateId 에 해당하는 보스 리소스 (없으면 null → 일반 적 경로). */
	public EnemyFactory.Kind kindFor(int template) {
		return kinds.get(template);
	}

	/** 영웅 세트에 붙일 추가 외부 애니 WAD (동기 처형의 영웅 쪽 애니가 레벨에 없을 때). */
	public java.util.List<java.nio.file.Path> heroAnimWads() {
		return java.util.List.of();
	}

	/** 이 템플릿은 화면에 나오지 않는 소리 전용 (GameScene 은 대체 적을 만들지 않는다). */
	public boolean silent(int template) {
		return silent.contains(template);
	}

	/** 순회 검증(HeadlessBoss)이 이 보스전을 맡는지. 격파할 본체가 없는 싸움(ATHN08B 가족 보호)은 false — 웨이브는 일반 처치로. */
	public boolean headless() {
		return true;
	}

	public Map<Integer, EnemyFactory.Kind> kinds() {
		return kinds;
	}

	/** 관절 월드 위치 (이름 대소문자 무시, 없으면 null). obj = 오브젝트 월드 행렬. */
	public static Vector3f jointWorld(devple.gow.engine.anim.Animator anim, org.joml.Matrix4f obj, String joint) {
		var sk = anim.skeleton();
		for (int i = 0; i < sk.size(); i++) {
			if (sk.joints.get(i).name().equalsIgnoreCase(joint)) {
				float[] m = anim.pose().world(sk)[i];
				return obj.transformPosition(new Vector3f(m[12], m[13], m[14]));
			}
		}
		return null;
	}

	public EnemyFighter boss() {
		return boss;
	}

	public String phase() {
		return phase;
	}

	public boolean defeated() {
		return defeated;
	}

	public BossSync sync() {
		return sync;
	}

	protected void setPhase(String p) {
		if (!p.equals(phase)) {
			phase = p;
			phases.add(p);
			System.out.println("[보스 " + level + "] 단계: " + p);
		}
	}

	protected void markDefeated() {
		if (!defeated) {
			defeated = true;
			setPhase("격파");
		}
	}

	/** GameScene/테스트가 적을 만든 직후. */
	public void onSpawn(EnemyFighter f, ScriptActor a, int template, BossArena arena) {
		this.arena = arena;
		// 신들의 분노 게이지는 플레이어 전역: 적 기술의 GOD_METER_ADD(ATHN08B 가족 포옹 865/866 의 +1 ×N)도 크레토스에게
		f.combat().setGodMeterOwner(arena.heroCombat());
		if (arena.bridge() != null) {
			arena.bridge().attach(f, a);
			if (kinds.containsKey(template)) {
				arena.bridge().allowForce(f); // 스크립트 구동 보스·쫄 (아기 히드라 baby1Ent 도 1019 사용)
				f.brain().setScriptSense(arena.bridge()::scriptSense);
			}
		}
		if (!bossTemplates.contains(template)) {
			minions.put(f, a);
			return;
		}
		boss = f;
		actor = a;
		bossKind = kinds.get(template);
		if (arena.bridge() != null) {
			arena.bridge().allowForce(f);
			f.brain().setScriptSense(arena.bridge()::scriptSense);
		}
		f.combat().onEvent(e -> bossEvent(f, e));
		arena.heroCombat().onEvent(e -> heroEvent(e));
		bind(f, a);
		setPhase("등장");
	}

	/** 보스별 초기화 (HP 바닥, 고정 위치 등). */
	protected abstract void bind(EnemyFighter f, ScriptActor a);

	/** 매 틱 (두 쪽 전투 갱신 뒤). */
	public void update(Input in, float dt) {
		if (arena == null) {
			return;
		}
		if (arena.bridge() != null) {
			arena.bridge().tick();
		}
		if (boss == null) {
			if (autoPlay) {
				demoTime += dt;
				beforeSpawnDemo(dt);
			}
			return;
		}
		if (pendingContact >= 0) {
			contactNow(pendingContact, pendingDamage);
			pendingContact = -1;
		}
		if (sync != null) {
			Input sin = in;
			if (autoPlay) {
				autoPad.drive(sync.timed(), sync.qte());
				sin = autoPad;
			}
			sync.update(sin, arena.heroCtl(), arena.heroCombat(), boss, bossKind.tweaks(), dt);
		}
		if (autoPlay) {
			demoTime += dt;
			demo(dt);
		}
		if (!boss.combat().active() || boss.combat().activeScript(SCR_FLASH_SPRITE) == null) {
			prompt = -1;
		}
		tick(in, dt);
	}

	protected abstract void tick(Input in, float dt);

	/** 게임 쪽 연결 (스폰 전에도 데모·이벤트 다리를 쓰도록). */
	public void attach(BossArena a) {
		this.arena = a;
	}

	/** 데모: 보스가 나오기 전 (스폰 볼륨으로 영웅 이동 등). */
	protected void beforeSpawnDemo(float dt) {
	}

	/** 데모 연출 (autoPlay): 보스별로 영웅 위치·피해·잡기를 지시해 단계를 차례로 보여 준다. */
	protected void demo(float dt) {
	}

	/** 일반 잡기(GrabSync) 미니게임 입력: autoPlay 면 자동 입력, 아니면 그대로. */
	public Input minigameInput(Input in, devple.gow.engine.combat.TimedPressQte timed, devple.gow.engine.combat.RepeatPressQte qte) {
		if (!autoPlay) {
			return in;
		}
		autoPad.drive(timed, qte);
		return autoPad;
	}

	/**
	 * 영웅 기술 heroMove 의 첫 공격 판정이 보스에 닿은 것으로 처리 (데모·검증용 — 실제 판정은 Combatant 히트박스).
	 * 기술을 시작하고 다음 틱에 (공격 자세 SET_1B0 적용 후) 피격자 receive → 공격자 HIT_CONFIRM, Combatant.contact 와 같은 순서.
	 */
	public void contact(int heroMove, float damage) {
		var hc = arena.heroCombat();
		if (!hc.active() || hc.move().id() != heroMove) {
			hc.forceMove(heroMove);
		}
		pendingContact = heroMove;
		pendingDamage = damage;
	}

	private void contactNow(int heroMove, float damage) {
		var hc = arena.heroCombat();
		if (boss == null || !hc.active() || hc.move().id() != heroMove || hc.move().attacks().isEmpty()) {
			return;
		}
		hc.setTarget(boss);
		var r = boss.receive(hc.move().attacks().getFirst(), hc, damage, new Vector3f());
		if (r == devple.gow.engine.combat.Hittable.Result.HIT) {
			hc.fire(CombatInput.EV_HIT_CONFIRM, -1, null);
		}
	}

	/**
	 * 스크립트 엔티티 볼륨(영역) 안이면서 다른 엔티티 영역 밖인 점 중 높이가 preferY 에 가장 가까운 점 (데모 배치). 없으면 null.
	 */
	protected float[] pointIn(String entity, float preferY, String... others) {
		var w = arena.world();
		var e = w != null ? w.find(entity) : null;
		if (e == null || e.volume == null) {
			return null;
		}
		var v = e.volume;
		float[] sp = v.hull().sphere, p = new float[3], best = null;
		for (int i = 0; i <= 24; i++) {
			for (int j = 0; j <= 24; j++) {
				for (int k = 0; k <= 24; k++) {
					devple.gow.tools.anim.Mat4.transformPoint(v.world(), sp[0] + sp[3] * (i / 12f - 1), sp[1] + sp[3] * (j / 12f - 1),
							sp[2] + sp[3] * (k / 12f - 1), p);
					if (!v.contains(p[0], p[1], p[2], e.data.baseId())) {
						continue;
					}
					boolean clash = false;
					for (String o : others) {
						var oe = w.find(o);
						clash |= oe != null && oe.volume != null && oe.volume.contains(p[0], p[1], p[2], oe.data.baseId());
					}
					if (!clash && (best == null || Math.abs(p[1] - preferY) < Math.abs(best[1] - preferY))) {
						best = p.clone();
					}
				}
			}
		}
		return best;
	}

	/** 영웅을 점으로 (데모). */
	protected void placeHero(float[] p) {
		if (p != null) {
			arena.heroCtl().position.set(p[0], p[1], p[2]);
			arena.heroCtl().prevPosition.set(arena.heroCtl().position);
		}
	}

	/** 동기 기술 중 영웅 위치를 보스가 정함 (GameScene 은 중력·지면 처리를 건너뜀). */
	public boolean heroLocked() {
		return sync != null && sync.active();
	}

	/** 이 적의 잡기/처형을 보스 동기({@link BossSync})가 맡는지 (GameScene 은 일반 GrabSync 를 건너뜀). */
	public boolean claimsGrab(EnemyFighter target) {
		return sync != null && target == boss;
	}

	/** 스크립트 → 오브젝트 이벤트. */
	public boolean objectEvent(ScriptListener.ObjectEvent e) {
		return arena != null && arena.bridge() != null && arena.bridge().objectEvent(e);
	}

	private boolean once(EnemyFighter f, CombatFile.Event e, int serial) {
		return applied.add(((long) System.identityHashCode(f) << 40) ^ ((long) serial << 12) ^ e.bit() ^ ((long) e.index() << 20));
	}

	/** 보스 애니 이벤트. */
	protected void bossEvent(EnemyFighter f, CombatFile.Event e) {
		int serial = f.combat().moveSerial();
		if (e.type() == EV_SELF_HP) {
			if (once(f, e, serial)) {
				selfHp(f, e.b());
			}
			return;
		}
		if (e.type() != 8) {
			return;
		}
		int h = e.hash();
		if (h == SCR_EXPLODE_DEATH && once(f, e, serial)) {
			f.hp = 0;
		} else if (h == SCR_FLASH_SPRITE) {
			prompt = e.index();
		} else if (h == SCR_SONIC_SCREAM) {
			sonicScream(f, e);
		}
		script(f, e);
	}

	/** 보스별 SCRIPT 이벤트 추가 처리. */
	protected void script(EnemyFighter f, CombatFile.Event e) {
	}

	/** 영웅 애니 이벤트: SELF_HP (동기 기술 실패 시 영웅 피해, 예 BHABOSSGRABRIGHTFAIL 10). */
	protected void heroEvent(CombatFile.Event e) {
		if (e.type() == EV_SELF_HP && applied.add(0x7F00000000000000L ^ ((long) arena.heroCombat().moveSerial() << 12) ^ e.bit()
				^ ((long) e.index() << 20))) {
			arena.damageHero(e.b());
		}
	}

	/** SELF_HP: 양수 = 자기 피해 (HP 바닥 무시, 0 이 되면 EV_DEATH 분기), 음수 = 회복 (최대 HP 까지). */
	protected void selfHp(EnemyFighter f, float v) {
		if (v >= 0) {
			float before = f.hp;
			f.hp = Math.max(0, f.hp - v);
			if (before > 0 && f.hp <= 0) {
				f.combat().fire(CombatInput.EV_DEATH, -1, null);
			}
		} else {
			f.hp = Math.min(f.maxHp(), f.hp - v);
		}
	}

	/**
	 * SCR_SonicScream (TWK SonicScream/NNN: Distance, Distance Force): 거리 안의 영웅을 보스에서 멀어지게 민다.
	 * [근사: 원작 0x19E9xx 힘 적용 미분석 — 거리 ×16 안에서 초당 Force/500 단위]
	 */
	protected void sonicScream(EnemyFighter f, CombatFile.Event e) {
		var tw = bossKind.tweaks();
		String n = String.format("/TweakTemplates/SonicScream/%03d/", e.index());
		float dist = tw.f(n, "Distance", 60f) * 16f;
		float force = tw.f(n, "Distance Force", 60000f);
		Vector3f hp = arena.heroCtl().position;
		Vector3f d = new Vector3f(hp).sub(f.pos).setComponent(1, 0);
		float len = d.length();
		if (len < 1e-3f || len > dist) {
			return;
		}
		d.div(len).mul(force / 500f / 60f); // 60fps 1틱
		hp.add(d);
	}
}
