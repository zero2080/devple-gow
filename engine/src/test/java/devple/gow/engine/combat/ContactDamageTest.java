package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.engine.nav.HeroNavigator;
import devple.gow.engine.nav.Platforms;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.level.BallHull;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CDV 재질 접촉 피해 (43 §46): 재질 엔트리 +0x38 "Damage" / +0x3C "AIDamage" — 접촉 0x1E1DF0 → 대기 피해 +0x280 → 0x1EAC70 이 1 초마다 0x204690,
 * 피격 이벤트 13 의 분기 키 = 재질 SheetFX (분기 +0x1A, 0x208970).
 */
class ContactDamageTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	/** PAND03C 굴러오는 불덩이 = 공 하나 CDV (피해 20 / 적 1024, MFX 105 "trap roll ball") — 이제 몸이고, 닿으면 Touch(20, 105). */
	@Test
	void rollingFireballTouch() throws Exception {
		Path p = ROM.resolve("PAND03C.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		LevelData d = new LevelData(WadFile.load(p));
		BallHull h = d.hulls.get("CDV_LeftRock1");
		assertEquals(20, h.materialDamage[0]);
		assertEquals(1024, h.materialAiDamage[0]);
		assertEquals(105, h.materialFx[0]);
		Platforms ps = Platforms.of(d, Set.of());
		Platforms.Body rock = ps.body("gomidrock1");
		assertNotNull(rock, "피해 공 CDV 는 몸");
		assertEquals(17, ps.bodies().stream().filter(b -> b.owner.matches("go(left|mid|right)rock\\d")).count());
		float[] j = rock.drawJoints()[h.ballJoint[0]]; // 공 관절 1 (공 중심 ≈ 관절 원점)
		Vector3f far = new Vector3f(j[12] + 300, j[13], j[14]);
		assertEquals(Platforms.Touch.NONE, ps.contactDamage(far, HeroNavigator.BODY, false, Set.of(), 0));
		// 공 중심 옆 (공 반지름 43.2 × 관절 배율 안쪽) → 영웅 20, 적 1024, SheetFX 105
		Vector3f near = new Vector3f(j[12] + 20, j[13], j[14]);
		assertEquals(new Platforms.Touch(20, 105), ps.contactDamage(near, HeroNavigator.BODY, false, Set.of(), 0));
		assertEquals(1024, ps.contactDamage(near, HeroNavigator.BODY, true, Set.of(), 0).damage());
		ps.setSolid("gomidrock1", false);
		Set<String> others = new java.util.HashSet<>();
		ps.bodies().stream().filter(b -> !b.owner.equals("gomidrock1")).forEach(b -> others.add(b.owner));
		assertEquals(0, ps.contactDamage(near, HeroNavigator.BODY, false, others, 0).damage(), "충돌 꺼진 몸은 접촉 없음");
	}

	/** PAND03E 바람개비 날 (hull CDV) 피해 10 — 재질 MFX 50 · 101. */
	@Test
	void pinwheelHullDamage() throws Exception {
		Path p = ROM.resolve("PAND03E.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		LevelData d = new LevelData(WadFile.load(p));
		BallHull h = d.hulls.get("CDV_PinWheel1Grp");
		assertEquals(10, h.materialDamage[0]);
		assertEquals(0, h.materialAiDamage[0]);
	}

	/** 영웅 피격 이벤트 13 + 키 105 → A185 HITBALL (분기 +0x1A = 105), 101 → A181 HITFIREBALL. */
	@Test
	void sheetFxKeyPicksHitBall() throws Exception {
		Assumptions.assumeTrue(Files.exists(Content.file(ROM, "R_HERO0.WAD")), "gow-rom 없음");
		for (int k = 0; k < 3; k++) {
			assertEquals(185, firstReaction(105), "SheetFX 105 → HITBALL");
		}
		assertEquals(181, firstReaction(101), "SheetFX 101 → HITFIREBALL");
		assertEquals(184, firstReaction(104), "SheetFX 104 → PARRYHITLEFT");
	}

	private static int firstReaction(int fx) throws Exception {
		WadFile hero = WadFile.load(Content.file(ROM, "R_HERO0.WAD"));
		WadFile perm = WadFile.load(Content.file(ROM, Region.of(ROM).perm()));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(hero);
		Skeleton sk = Skeleton.loadAll(hero).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(hero, "ANM_hero");
		Animator an = new Animator(sk, set, tw, "/Animation/goHero/");
		HeroController ctl = new HeroController(an);
		CombatOwner owner = new CombatOwner() {
			public float hp() { return 50; }
			public boolean onGround() { return true; }
			public int bladeLevel() { return 1; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public boolean artemisOwned() { return false; }
			public int selectedMagic() { return 0; }
			public int classId() { return 0; }
			public Vector3f position() { return ctl.position; }
			public float yaw() { return ctl.yaw; }
		};
		Combatant c = new Combatant(new CombatData(perm, CombatData.heroFiles(), set), an, owner);
		List<Integer> moves = new ArrayList<>();
		c.onLog(s -> {
			if (s.startsWith("기술 ")) {
				moves.add(Integer.parseInt(s.split(" ")[1]));
			}
		});
		ScriptedInput in = new ScriptedInput(0, List.of());
		float dt = 1 / 60f;
		for (int i = 0; i < 4; i++) {
			c.update(in, dt);
		}
		moves.clear();
		c.fireFrom(CombatInput.EV_HIT, -1, 0, -1, -1, fx);
		c.update(in, dt);
		assertFalse(moves.isEmpty(), "반응 없음 (SheetFX " + fx + ")");
		return moves.get(0);
	}
}
