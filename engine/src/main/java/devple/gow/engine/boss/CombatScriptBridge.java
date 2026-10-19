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
 *                    1055 / 1056 보스 체력 표시 켬/끔 (bossEnt-HealthMeterOn)                       [추정]
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
	/** 체력 미터를 켠 적 → 미터 슬롯 1..3 (레벨 FLP 의 EnemyMeter_*1..3, 1055 순서대로 배정). */
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
		if (f == null || (e.event() == EVENT_FORCE && !forceable.contains(f))) {
			return false;
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
			case METER_ON -> {
				if (!meters.containsKey(a)) {
					for (int slot = 1; slot <= 3; slot++) {
						if (!meters.containsValue(slot)) {
							meters.put(a, slot);
							break;
						}
					}
				}
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
