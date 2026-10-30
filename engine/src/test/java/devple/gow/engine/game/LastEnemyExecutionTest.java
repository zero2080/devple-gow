package devple.gow.engine.game;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.combat.CombatData;
import devple.gow.engine.combat.CombatOwner;
import devple.gow.engine.combat.Combatant;
import devple.gow.engine.combat.EnemyFactory;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.combat.GrabSync;
import devple.gow.engine.combat.Hittable;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
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
 * 마지막 적을 붙잡은 채 처형 (ATHN01A 갑판 해골병 R_SKS): 붙잡기 MAISKB01 223 → ○ 머리치기 MAISKB03 222 — 적 222 @0.05 CONCUSSION 400 으로 즉사,
 * EV_DEATH → MAISKB06 227 (영웅도 따라감) 을 끝까지 한다. 장면의 대상 (가장 가까운 산 적) 은 그 틱부터 null 인데, 예전 GameScene 은 대상이 null 이면
 * GrabSync 를 돌리지 않아 결합이 남고 영웅 갱신도 건너뛰어 크레토스가 붙잡은 자세로 굳었다 ({@link GameScene#ticksGrab}).
 */
class LastEnemyExecutionTest {

	@Test
	void heroFinishesTheExecutionWhenTheLastEnemyDiesInTheHold() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "R_SKS.WAD")), "gow-rom 없음");
		WadFile hero = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(hero);
		Skeleton sk = Skeleton.loadAll(hero).stream().filter(x -> x.name.equals("hero")).findFirst().orElseThrow();
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
		EnemyFactory.Kind k = EnemyFactory.load(Content.file(rom, "R_SKS.WAD"), perm, -1);
		set.attachAll(k.assets().wad());
		cd.reindexAnimations();
		EnemyFighter.resetSpawnCount();
		EnemyFighter en = k.spawn(20);
		en.pos.set(0, 0, -30);
		en.holdAi = true;
		en.combat().addEventListener(ev -> { // GameScene 의 적 기술 이벤트와 같게: SELF_HP (10) · CONCUSSION (48, 머리치기 222 @0.05 idx 400 → 즉사)
			if (ev.type() == 10) {
				en.selfHp(ev.b());
			} else if (ev.type() == 48) {
				en.selfHp(ev.index());
			}
		});
		en.setFoe(new Hittable() {
			public Vector3f position() { return ctl.position; }
			public float hp() { return 100; }
			public int classId() { return 0; }
			public int stanceTag() { return c.stanceTag(); }
			public boolean invulnerable() { return true; }
			public Result receive(CombatFile.Attack a, Combatant at, float d, Vector3f kn) { return Result.IGNORED; }
		});
		c.setTarget(en);
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
		// ○ 잡기 → 붙잡는 동안 ○ (MAISKB03)
		List<ScriptedInput.Step> st = new ArrayList<>();
		for (float s = 0.2f; s < 2f; s += 0.3f) {
			st.add(new ScriptedInput.Step(s, 0.05f, PadButton.CIRCLE));
		}
		ScriptedInput in = new ScriptedInput(0, st);
		GrabSync sync = new GrabSync(ctl, c);
		float dt = 1 / 60f, deathAt = -1, releasedAt = -1;
		boolean diedLinked = false;
		for (float t = 0; t < 10f; t += dt) {
			in.advance(dt);
			if (!sync.active()) {
				c.update(in, dt);
				if (c.active()) {
					ctl.updateCombat(c, in, 0, dt, null);
				} else {
					ctl.update(in, 0, dt);
				}
			}
			en.update(dt);
			EnemyFighter target = en.dead() ? null : en; // GameScene.nearestEnemy: 죽은 적은 대상이 아니다 — 마지막 적이면 null
			if (GameScene.ticksGrab(target, null)) {
				sync.update(in, target, target != null ? k.tweaks() : null, dt);
			}
			if (en.dead() && deathAt < 0) {
				deathAt = t;
				diedLinked = sync.active();
			}
			if (deathAt >= 0 && releasedAt < 0 && !sync.active()) {
				releasedAt = t;
			}
		}
		assertTrue(hm.contains(227) && em.contains(227), "처형 MAISKB06 227: 영웅 " + hm + " 적 " + em);
		assertTrue(deathAt >= 0 && diedLinked, "적이 죽는 틱에 아직 결합 중 (대상 = null): " + deathAt);
		assertTrue(releasedAt >= 0, "대상 없이도 결합이 풀림 — 영웅 " + hm + " (지금 " + (c.active() ? c.move().id() : -1) + ")");
		assertTrue(hm.subList(hm.lastIndexOf(227), hm.size()).contains(80), "227 을 끝내고 IDLECOMBAT 80: " + hm);
	}
}
