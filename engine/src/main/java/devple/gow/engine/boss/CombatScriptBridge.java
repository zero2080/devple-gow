package devple.gow.engine.boss;

import devple.gow.engine.combat.Combatant;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.script.ScriptActor;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 전투 ↔ 레벨 스크립트 이벤트 다리 (원작 sub_001A8C78 전역 이벤트, 보낸 쪽 = 전투 owner). 보스전 스크립트가 전부 이것으로 돈다.
 * <pre>
 *  게임 → 스크립트   1035 기술 시작 (인자0 = 기술 ID)            Combat_StartBranch 0x209610      [확실]
 *                    1047 기술을 끝까지 재생 (인자0 = 기술 ID)      Combat_EndMove 0x209994          [확실]
 *                    1039 피해 후 (인자0 = 피해, 인자1 = 남은 HP)   0x203848 / 0x204830              [확실]
 *  스크립트 → 오브젝트 1019 EvtForce: 적이면 인자1 기술 요청 (히드라 53/54 SLAM, 123 STUN)          [추정: 적 쪽 처리]
 *                    1054 플레이어 +0x88 = 인자1 (보스 구역, 결정 트리 조건 26)  0x25B5D0            [확실]
 *                    1055 / 1056 체력 미터 켬/끔 (+0x48 비트 4, 슬롯 = AI1 +0x9B + 1)  0x25B720      [확실]
 * </pre>
 */
public final class CombatScriptBridge {

	static final boolean DEBUG = System.getProperty("gow.debug") != null;
	public static final int EVENT_FORCE = 1019, EVENT_HEALTH = 1039, EVENT_ZONE = 1054, METER_ON = 1055, METER_OFF = 1056;

	private final ScriptWorld world;
	private final Map<ScriptActor, EnemyFighter> byActor = new HashMap<>();
	private final Map<EnemyFighter, Float> lastHp = new HashMap<>();
	private final Map<EnemyFighter, ScriptActor> actorOf = new HashMap<>();
	private int playerVar88, playerVar64;
	/** 체력 미터를 켠 적 → 미터 슬롯 1..3 (레벨 FLP 의 EnemyMeter_*1..3 = AI1 +0x9B + 1, 1055 또는 생성 때 0x84 &amp; 0x40). */
	private final java.util.Map<ScriptActor, Integer> meters = new java.util.LinkedHashMap<>();
	/** 1019 EvtForce 를 기술 요청으로 받는 적 (보스만 — 일반 적의 PatrolChange 0 은 순찰 변경이라 제외). */
	private final java.util.Set<EnemyFighter> forceable = new java.util.HashSet<>();
	/** 디버그·테스트 기록: "1035 250 54" 처럼 (이벤트, 그룹, 인자). */
	public final List<String> log = new ArrayList<>();

	public CombatScriptBridge(ScriptWorld world) {
		this.world = world;
	}

	/** 스폰된 적 연결: 기술 시작/완료 → 1035/1047, HP 감소 → 1039 (보낸 쪽 = actor, 그룹 = SpawnEnemy Filter). */
	public void attach(EnemyFighter f, ScriptActor actor) {
		if (actor == null) {
			return;
		}
		byActor.put(actor, f);
		actorOf.put(f, actor);
		lastHp.put(f, f.hp);
		f.combat().onMove((code, id) -> post(code, id, 0, actor));
		if ((f.template().flags & devple.gow.engine.combat.AiTemplate.FLAG_METER) != 0) {
			meters.putIfAbsent(actor, f.template().meterSlot + 1); // 생성 때 미터 켬 (0x1CEF10, 37 문서 §4.3)
		}
	}

	/** 이 적은 1019 인자1 을 기술 ID 로 받는다 (스크립트 구동 보스). */
	public void allowForce(EnemyFighter f) {
		forceable.add(f);
	}

	public ScriptActor actorOf(EnemyFighter f) {
		return actorOf.get(f);
	}

	private void post(int code, int a0, int a1, ScriptActor actor) {
		if (world != null) {
			world.postEvent(code, a0, a1, 0, actor);
		}
		String line = code + " " + actor.group + " " + a0 + (code == EVENT_HEALTH ? " " + a1 : "");
		if (log.size() < 4000) {
			log.add(line);
		}
		if (DEBUG) {
			System.out.println("[스크립트 이벤트] " + line);
		}
	}

	/** 매 틱: HP 가 줄었으면 1039 (피해, 남은 HP). */
	public void tick() {
		for (Map.Entry<EnemyFighter, Float> e : lastHp.entrySet()) {
			EnemyFighter f = e.getKey();
			float before = e.getValue();
			if (f.hp < before - 1e-3f) {
				post(EVENT_HEALTH, Math.round(before - f.hp), Math.round(Math.max(0, f.hp)), actorOf.get(f));
			}
			e.setValue(f.hp);
		}
	}

	/** 스크립트 → 오브젝트 이벤트. 처리했으면 true. */
	public boolean objectEvent(ScriptListener.ObjectEvent e) {
		ScriptActor a = e.actor();
		if (a == null) {
			return false;
		}
		if (world != null && a == world.player()) {
			if (e.event() == EVENT_ZONE) {
				playerVar88 = e.arg1();
				return true;
			}
			return false;
		}
		EnemyFighter f = byActor.get(a);
		if (f == null) {
			return false;
		}
		if (e.event() == EVENT_FORCE && !forceable.contains(f)) {
			// 일반 Enemy1 도 원작 0x25B8F8 → 0x1D3530 (인자2 ≠ 0 이면 0x209918 로 지금 기술을 끊고 0x209378 기술 요청, ai +0x40 = 기술).
			// 선창 히드라 hydraEnt3b-stunTransmit 100 / regen 107 / getup 108 [확실]. 순찰 변경 PatrolChange 0 은 기술 0 이 없는 적이 대부분이라
			// 엔진은 0 을 받지 않고, 전투 파일에 있는 기술만 [근사: 원작은 거르지 않음]
			Combatant c = f.combat();
			if (e.arg1() == 0 || f.dead()) {
				return false;
			}
			boolean ok = e.arg2() != 0 ? c.forceMove(e.arg1()) : c.request(e.arg1());
			log.add("1019 → " + a.group + " 기술 " + e.arg1() + (ok ? "" : " (없음)"));
			if (DEBUG) {
				System.out.println("[스크립트 이벤트] 1019 → " + a.group + " 기술 " + e.arg1() + (ok ? "" : " (없음)"));
			}
			return ok;
		}
		switch (e.event()) {
			case EVENT_FORCE -> {
				Combatant c = f.combat();
				if (!c.request(e.arg1())) {
					c.forceMove(e.arg1());
				}
				log.add("1019 → " + a.group + " 기술 " + e.arg1());
				if (DEBUG) {
					System.out.println("[스크립트 이벤트] 1019 → " + a.group + " 기술 " + e.arg1());
				}
				return true;
			}
			case EVENT_HEALTH -> {
				// 스크립트 → 적 1039 (0x25B6E8: 0x40F → 0x1CC810 → 0x204690 피해 적용, 피해 = 인자1) — 예 PAND03G logLoadGRP-LogDamageBoss 999
				float before = f.hp;
				f.applyDamage(e.arg1());
				if (before > 0 && f.hp <= 0) {
					f.combat().fire(devple.gow.engine.combat.CombatInput.EV_DEATH, -1, null);
				}
				log.add("1039 → " + a.group + " 피해 " + e.arg1());
				return true;
			}
			case METER_ON -> {
				// 원작 0x25B720: +0x48 |= 4 → 슬롯 = AI1 +0x9B + 1 (0x1D0FD4) [확실]. 예전엔 1055 순서대로 1, 2, 3
				meters.putIfAbsent(a, f.template().meterSlot + 1);
				return true;
			}
			case METER_OFF -> {
				meters.remove(a);
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	/** 결정 트리 조건 26 (플레이어 +0x88) / 27 (+0x64, 쓰는 곳 미확인 → 0). */
	public int scriptSense(int cond) {
		return switch (cond) {
			case 26 -> playerVar88;
			case 27 -> playerVar64;
			default -> 0;
		};
	}

	public int playerZone() {
		return playerVar88;
	}

	/** 체력 표시 대상 적 (스크립트 1055 이후, 없으면 null). */
	public EnemyFighter meterTarget() {
		return meters.isEmpty() ? null : byActor.get(meters.keySet().iterator().next());
	}

	/** 미터 슬롯(1..3) → 적 (표시 중인 것만). */
	public java.util.Map<Integer, EnemyFighter> meterTargets() {
		java.util.Map<Integer, EnemyFighter> out = new java.util.TreeMap<>();
		meters.forEach((a, slot) -> {
			EnemyFighter f = byActor.get(a);
			if (f != null) {
				out.put(slot, f);
			}
		});
		return out;
	}
}
