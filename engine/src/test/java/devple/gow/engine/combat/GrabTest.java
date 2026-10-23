package devple.gow.engine.combat;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 미노타우로스 잡기 처형: HP 를 잡기 원(25) 아래로 → ○ GRAB → 양쪽 MAIMIN01 → MAIMIN02 연타 QTE
 * → 성공 402(MAIMIN03) / 실패 403(MAIMIN04).
 */
class GrabTest {

	private record Result(List<Integer> heroMoves, List<Integer> enemyMoves, RepeatPressQte.State qte, int pullFrames, float pulledYawGap,
						  List<Integer> bystanderMoves) {
	}

	private Result run(boolean mash) throws Exception {
		return run(mash, "R_MIN.WAD", 20);
	}

	private Result run(boolean mash, String wadName, float enemyHp) throws Exception {
		return run(mash, wadName, enemyHp, false);
	}

	private Result run(boolean mash, String wadName, float enemyHp, boolean bystander) throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, wadName)), "gow-rom 없음");
		WadFile hero = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(hero);
		Skeleton sk = Skeleton.loadAll(hero).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(hero, "ANM_hero");
		Animator an = new Animator(sk, set, tw, "/Animation/goHero/");
		HeroController ctl = new HeroController(an);
		CombatOwner owner = new CombatOwner() {
			public float hp() { return 100; }
			public boolean onGround() { return true; }
			public int bladeLevel() { return 3; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public int selectedMagic() { return 0; }
			public int classId() { return 0; }
			public Vector3f position() { return ctl.position; }
			public float yaw() { return ctl.yaw; }
		};
		CombatData cd = new CombatData(perm, CombatData.heroFiles(), set);
		Combatant c = new Combatant(cd, an, owner);
		EnemyFactory.Kind k = EnemyFactory.load(Content.file(rom, wadName), perm, -1);
		set.attachAll(k.assets().wad());
		cd.reindexAnimations();
		EnemyFighter.resetSpawnCount(); // 적 AI 난수 씨앗을 앞선 테스트와 무관하게
		EnemyFighter en = k.spawn();
		en.pos.set(0, 0, -50);
		en.hp = enemyHp; // 미노타우로스 잡기 원(25) 아래 / 세이렌 MAISIRKILL 은 HP 0..10
		en.setFoe(new Hittable() {
			public Vector3f position() { return ctl.position; }
			public float hp() { return 100; }
			public int classId() { return 0; }
			public int stanceTag() { return c.stanceTag(); }
			public boolean invulnerable() { return true; }
			public Result receive(CombatFile.Attack a, Combatant at, float d, Vector3f kn) { return Result.IGNORED; }
		});
		c.setTarget(en);
		EnemyFighter by = bystander ? k.spawn() : null; // 옆의 같은 종류 적 (HP 가득 — 잡기 불가)
		List<Integer> bm = new ArrayList<>();
		if (by != null) {
			by.pos.set(12, 0, -50);
			by.setFoe(en.foe());
			by.holdAi = true;
			by.combat().onLog(s -> {
				if (s.startsWith("기술 ")) {
					bm.add(Integer.parseInt(s.split(" ")[1]));
				}
			});
			c.setBystanders(() -> List.of(by));
		}
		List<Integer> hm = new ArrayList<>(), em = new ArrayList<>();
		c.onLog(s -> {
			if (s.startsWith("기술 ")) {
				hm.add(Integer.parseInt(s.split(" ")[1]));
			}
		});
		en.combat().onLog(s -> {
			if (s.startsWith("기술 ")) {
				em.add(Integer.parseInt(s.split(" ")[1]));
			}
		});
		List<ScriptedInput.Step> st = new ArrayList<>();
		st.add(new ScriptedInput.Step(0.2f, 0.05f, PadButton.CIRCLE));
		if (mash) {
			for (int i = 0; i < 60; i++) {
				st.add(new ScriptedInput.Step(4.5f + i * 0.12f, 0.05f, PadButton.CIRCLE));
			}
		}
		ScriptedInput in = new ScriptedInput(0, st);
		GrabSync sync = new GrabSync(ctl, c);
		RepeatPressQte.State qs = null;
		int pulls = 0;
		float yawGap = Float.NaN;
		float dt = 1 / 60f;
		for (float t = 0; t < 14f; t += dt) {
			in.advance(dt);
			boolean locked = sync.active();
			if (!locked) {
				c.update(in, dt);
				if (c.active()) {
					ctl.updateCombat(c, in, 0, dt, en.pos);
				} else {
					ctl.update(in, 0, dt);
				}
			}
			en.update(dt);
			if (by != null) {
				by.update(dt);
			}
			sync.update(in, en, k.tweaks(), dt);
			if (sync.qte() != null) {
				qs = sync.qte().state();
			}
			if (sync.pulling() == 2) {
				pulls++;
				yawGap = (float) Math.abs(Math.IEEEremainder(en.yaw - ctl.yaw - Math.PI, Math.PI * 2));
			}
			if (!sync.active()) {
				ctl.separate(en.pos, 22);
			}
		}
		return new Result(hm, em, qs, pulls, yawGap, bm);
	}

	@Test
	void mashingWins() throws Exception {
		Result r = run(true);
		assertTrue(r.heroMoves().contains(400), "영웅 MAIMIN01: " + r.heroMoves());
		assertTrue(r.enemyMoves().containsAll(List.of(400, 401)), "적 MAIMIN01→02: " + r.enemyMoves());
		assertEquals(RepeatPressQte.State.PASSED, r.qte());
		assertTrue(r.enemyMoves().contains(402), "성공 → MAIMIN03(402): " + r.enemyMoves());
	}

	@Test
	void notMashingFails() throws Exception {
		Result r = run(false);
		assertEquals(RepeatPressQte.State.FAILED, r.qte());
		assertTrue(r.enemyMoves().contains(403), "실패 → MAIMIN04(403): " + r.enemyMoves());
	}

	/**
	 * 세이렌 처형 MAISIRKILL (영웅 002 의 421): 이벤트 22 가 영웅 기술에 [0.44–0.91] — 원작 0x204BF0 로 영웅이 세이렌을 synchJoint 관계로 끈다
	 * (마주 봄). 미노타우로스 MAIMIN* 은 이벤트 22 가 없어 끌기 없음 (37 문서 §11).
	 */
	@Test
	void sirenKillPullsEnemy() throws Exception {
		Result r = run(false, "R_SIR.WAD", 5);
		assertTrue(r.heroMoves().contains(421), "영웅 MAISIRKILL: " + r.heroMoves());
		assertTrue(r.pullFrames() > 30, "이벤트 22 창 동안 영웅이 세이렌을 끈다: " + r.pullFrames() + " hero " + r.heroMoves() + " enemy " + r.enemyMoves());
		assertTrue(r.pulledYawGap() < 0.1f, "세이렌이 영웅을 마주 봄: " + r.pulledYawGap());
		assertEquals(0, run(false).pullFrames(), "MAIMIN* 는 이벤트 22 없음");
	}

	/**
	 * GRAB(태그 200) 에 휘말린 HP 가득한 미노타우로스는 자기 잡기 가능 여부로 반응을 고른다 (0x208800, 적은 자기 +0x1B8) → 3 blockShove.
	 * 전에는 혼자 MAIMIN01→02 (재생 속도 0) 에 들어가 결합 상대 없이 무적으로 멈췄다 (ATHN03C 자동 플레이, 43 문서).
	 */
	@Test
	void grabSparesBystander() throws Exception {
		Result r = run(true, "R_MIN.WAD", 20, true);
		assertTrue(r.heroMoves().contains(400), "영웅 MAIMIN01: " + r.heroMoves());
		assertFalse(r.bystanderMoves().contains(400) || r.bystanderMoves().contains(401), "옆 적은 처형에 들어가지 않음: " + r.bystanderMoves());
	}
}
