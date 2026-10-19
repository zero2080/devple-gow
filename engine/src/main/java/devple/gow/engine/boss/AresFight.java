package devple.gow.engine.boss;

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
	private int demoStep;

	AresFight(String level, Path rom, WadFile permk, int stage) throws IOException {
		super(level);
		this.stage = stage;
		int ai1 = stage == 1 ? 600 : 601;
		kinds.put(ai1, BossKinds.load(rom.resolve(level + ".WAD"), permk, List.of(), stage == 1 ? "bar" : "ba2", ai1, ai1));
		silent.addAll(java.util.List.of(1, 602, 603, 604, 605, 606, 607));
		bossTemplates.add(ai1);
		sync = new BossSync(id -> id >= 605 && id <= 625, BossSync.Align.BOSS_TO_HERO);
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
			contact(200, 0);
		}
	}
}
