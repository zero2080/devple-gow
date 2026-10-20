package devple.gow.engine.boss;

import devple.gow.tools.content.Content;
import devple.gow.engine.combat.CombatInput;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.input.Input;
import devple.gow.engine.script.ScriptActor;
import devple.gow.tools.wad.WadFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 아레스 (37 문서 §4). 1단계 ATHN08A (AI1 600 "BAR_stage1", HP 4000, 피해 배율 2.0) / 3단계 ATHN08C (601 "BAR_stage3", HP 1500/3000).
 * <pre>
 * 1단계  DTre_600: HP 비율 &gt; 0.75 / 0.5–0.75 / 0.25–0.5 / &lt; 0.25 → phase 1..4 공격표 (칼날 6, 도끼 40/41, 망치 50/55, 순간이동 70/75 …)
 *        잡기 원 HP 3000/2000/1000/500/250/50 (AI1 Circle Health Threshold 0..5) → EV_STUN(21) → 290 비틀거림 (원 켬, ○ 가능)
 *        HP ≤ 500: 94/95 도발(원 켬, 95 는 SELF_HP −15×18 회복). 영웅 ○ GRAB(자세 200) → 605 MAIBARGRABSTART 동기
 *        → 606 연타 RepeatPress 600 → 608 MAIBARGRABWIN (TimedPress 000 ×2, SELF_HP 100 + 4000 = 사망) / 607
 *        608 완료 1047 → a07Mp01movie: 컷신 a07Mp01 → LoadForWarp athn08b → Warp (분신 단계)
 * 3단계  DTre_601: 칼 기술 144/146/105, 마법 121/170 (대기 5–10초), 방어 112/113
 *        115 BLOCKPARRY 적중 → 620 MAIBARSHOWDOWN (칼 맞대기) 동기 → 621 연타(영웅 쪽 RepeatPress 601) → 622 SDWIN
 *        (SELF_HP 50+50+400) → HP 0 이면 390 → 395 → 1035(395) → Semo10 컷신 → Warp athn07b (엔딩 쪽)
 * </pre>
 * 엔진 쪽 보정: HP 바닥 1 (사망은 SELF_HP 로만), 다중 잡기 원 임계는 여기서 EV_STUN 으로 발동 (EnemyFighter 는 첫 임계만 앎).
 */
public final class AresFight extends BossFight {

	private final int stage;
	private final List<Integer> circles = new ArrayList<>();
	private float lastHp = -1;
	/** 불덩이 모으기(21 루프) 경과 — CHARGE_CHECK(이벤트 33) a = 4.5초 뒤 0x800 분기(→ 22 던지기). */
	private float charge;
	private int demoStep, demoHits;
	private float demoWait;

	AresFight(String level, Path rom, WadFile permk, int stage) throws IOException {
		super(level);
		this.stage = stage;
		int ai1 = stage == 1 ? 600 : 601;
		kinds.put(ai1, BossKinds.load(Content.file(rom, level + ".WAD"), permk, List.of(), stage == 1 ? "bar" : "ba2", ai1, ai1));
		silent.addAll(java.util.List.of(1, 602, 603, 604, 605, 606, 607));
		bossTemplates.add(ai1);
		// 동기 쌍 (기술 플래그 0x20/0x40): 600 BARMAIBLADESHITTHROW (아레스 공격 적중 → 영웅 A600 반응), 605–609 잡기, 620–623 칼 맞대기.
		// (예전 605–625 는 아레스 610 BARSTANDIDLE ↔ 영웅 610 HITLAND 같은 비동기 기술까지 묶었다) 정렬은 원작 0x204BF0 만 —
		// 아레스 600 [0–0.35] · 608 MAIBARGRABWIN [0–0.52] 의 이벤트 22 가 영웅을 끈다 (영웅 = 아레스 정면 31, 마주 봄).
		// 605/606/607/609 는 이벤트 19 — 원작은 이 창도 끈다 (0x205B28 의 0x24 검사, 37 문서 §11.6): 아레스가 영웅을 끈다
		// (예전 BOSS_TO_HERO "같은 원점·같은 방향" 은 폐기, 37 문서 §11)
		sync = new BossSync(id -> id == 600 || id >= 605 && id <= 609 || id >= 620 && id <= 623);
	}

	public int stage() {
		return stage;
	}

	@Override
	protected void bind(EnemyFighter f, ScriptActor a) {
		f.hpFloor = 1;
		f.brain().engage();
		String n = String.format("/TweakTemplates/AI1/%03d/", f.ai1());
		var tw = bossKind.tweaks();
		for (int i = 1; i <= 5; i++) {
			int t = tw.i(n, "Circle Health Threshold " + i, 65535) & 0xFFFF;
			if (t < 65535) {
				circles.add(t);
			}
		}
		lastHp = f.hp;
	}

	@Override
	protected void tick(Input in, float dt) {
		EnemyFighter f = boss;
		// 원작 아레스 클래스(Enemy1 하위 클래스 2) 갱신 0x1C3370: 매 프레임 SwitchCamera("AresCamera1", 0) — 영웅 + 아레스 두 대상 카메라
		// 대상 슬롯 (0x1F59A0): 영웅 관절 "vertebrae4", 아레스 관절 "BAR01JOCVEC1" + 두 몸 위치
		var hc = arena.heroCtl();
		arena.requestCamera(devple.gow.engine.level.CameraRig.ARES_CAMERA, f.pos,
				jointWorld(hc.animator(), hc.world(new org.joml.Matrix4f(), 1), "vertebrae4"),
				jointWorld(f.animator(), f.world(new org.joml.Matrix4f(), 1), "BAR01JOCVEC1"));
		for (int t : circles) {
			if (lastHp > t && f.hp <= t && f.hp > 0) {
				f.combat().fire(CombatInput.EV_CIRCLE, -1, null);
				setPhase("잡기 원 " + t);
			}
		}
		lastHp = f.hp;
		int m = f.combat().active() ? f.combat().move().id() : -1;
		// 차지 분기(플래그 0x800)는 Combatant 가 아직 평가하지 않음 → 여기서 CHARGE_CHECK 시간 후 던지기 [근사]
		if (m == 21) {
			charge += dt;
			float need = 4.5f;
			for (var e : f.combat().move().events()) {
				if (e.type() == 33) {
					need = e.a();
				}
			}
			if (charge >= need) {
				charge = 0;
				f.combat().forceMove(22);
			}
		} else if (m != 20) {
			charge = 0;
		}
		if (f.hp <= 0 || m == 395 || m == 390) {
			markDefeated();
			return;
		}
		if (sync.active()) {
			setPhase(stage == 1 ? "잡기 연타" : "칼 맞대기");
			return;
		}
		if (m >= 0 && stage == 1) {
			float r = f.hp / f.maxHp();
			setPhase(r > 0.75f ? "1단계 phase 1" : r > 0.5f ? "1단계 phase 2" : r > 0.25f ? "1단계 phase 3" : "1단계 phase 4");
		} else if (m >= 0) {
			setPhase("3단계 결투");
		}
	}

	/**
	 * 데모: 1단계 — 영웅을 정면 60 에, 3초 뒤 HP 480 (500 임계 → 290 비틀거림) → ○ 잡기 → 605/606 자동 연타 → 608 (TimedPress ×2)
	 * → 사망 → 컷신 a07Mp01 → athn08b. 3단계 — HP 400, 칼 맞대기(620) → 621 자동 연타 → 622 → 390/395 → Semo10.
	 */
	@Override
	protected void demo(float dt) {
		EnemyFighter f = boss;
		var c = arena.heroCtl();
		int m = f.combat().active() ? f.combat().move().id() : -1;
		if (demoStep == 0) {
			demoStep = 1;
			c.position.set(f.pos.x - (float) Math.sin(f.yaw) * 60, c.position.y, f.pos.z - (float) Math.cos(f.yaw) * 60);
			c.prevPosition.set(c.position);
		} else if (demoStep == 1 && stage == 1 && demoTime > 0.8f && !sync.active()) {
			// 1단계: 크레토스가 계속 싸운다 (예전엔 3초 동안 서 있다가 불덩이에 맞은 뒤 ~2.5초부터 멈춰 있었음) — 0.9초마다 정면 60 에서
			// 공격 한 번, 피해는 4 번 만에 500 임계 밑 (원 임계 3000/2000/1000/500 마다 290 비틀거림)
			if ((demoWait -= dt) <= 0 && (!arena.heroCombat().active() || arena.heroCombat().move().id() == 80)) {
				demoWait = 0.9f;
				faceBoss(c, f, 60);
				contact(71, Math.max(1, (f.hp - 480) / Math.max(1, 4 - demoHits)));
				if (++demoHits >= 4) {
					demoStep = 2;
				}
			}
		} else if (demoStep == 1 && demoTime > 3 && !sync.active()) {
			demoStep = 2;
			if (stage == 1) {
				f.hp = Math.min(f.hp, 480);
			} else {
				f.hp = Math.min(f.hp, 400);
				f.combat().forceMove(620);
			}
		} else if (demoStep == 2 && stage == 1 && (m == 290 || m == 94 || m == 95)) {
			demoStep = 3;
			faceBoss(c, f, 40);
			contact(200, 0);
		} else if (demoStep == 2 && stage == 1 && f.hp > 500 && !sync.active() && (demoWait -= dt) <= 0) {
			demoWait = 0.9f; // 무적 기술 중이라 피해가 안 들어갔으면 다시
			faceBoss(c, f, 60);
			contact(71, f.hp - 480);
		}
	}

	/** 영웅을 아레스 정면 dist 에, 아레스를 보게. */
	private static void faceBoss(devple.gow.engine.anim.HeroController c, EnemyFighter f, float dist) {
		c.position.set(f.pos.x - (float) Math.sin(f.yaw) * dist, c.position.y, f.pos.z - (float) Math.cos(f.yaw) * dist);
		c.prevPosition.set(c.position);
		c.yaw = f.yaw + (float) Math.PI;
	}
}
