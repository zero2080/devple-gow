package devple.gow.engine.game;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.combat.CombatData;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.engine.nav.NavState;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

import static devple.gow.engine.game.BerserkBladeRule.Action.HIDE;
import static devple.gow.engine.game.BerserkBladeRule.Action.NONE;
import static devple.gow.engine.game.BerserkBladeRule.Action.SHOW;
import static devple.gow.engine.game.BerserkBladeRule.Action.UNEQUIP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 아르테미스의 검 숨김 · 해제 (원작 0x1DB788, 43 §37): 수평 줄 · 오르는 벽 = 해제 (돌아오지 않음), 줄 · 사다리 · 좁은 턱 · 턱 · 물 · 물체 붙잡기 ·
 * 좁은 바닥 · SCR_HideBerserkBlade = 숨김 (끝나면 돌아옴), 그 밖 = 보임. 장착 뒤 이동 애니 뱅크 1 (berserkIdle · berWalkBlend …).
 */
class BerserkBladeRuleTest {

	private static int bits(NavState s) {
		return BerserkBladeRule.stateBits(s, s == NavState.GROUND, false, false, false);
	}

	private static BerserkBladeRule.Action in(NavState s) {
		return BerserkBladeRule.decide(true, bits(s), false, false);
	}

	@Test
	void notEquippedDoesNothing() {
		for (NavState s : NavState.values()) {
			assertEquals(NONE, BerserkBladeRule.decide(false, bits(s), true, true), s.name());
		}
	}

	/** 0x1DB7D8 – 0x1DB820: +0x174 0x400 · 0x20000 (수평 줄) 또는 0x20 이고 0x400000 · 0x300000 아님 (오르는 벽) → 0x19C240(0). */
	@Test
	void horizontalRopeAndClimbWallUnequip() {
		assertEquals(UNEQUIP, in(NavState.ROPE_TRAVERSE));
		assertEquals(UNEQUIP, in(NavState.WALL_CLIMB));
		// 숨김 스크립트가 있어도 해제가 먼저
		assertEquals(UNEQUIP, BerserkBladeRule.decide(true, bits(NavState.ROPE_TRAVERSE), true, false));
	}

	/** 0x1DB834 – 0x1DB89C: 0x29D814 · +0x174 &amp; (0x4000 | 0x400 | 0x10 | 0x380 | 0x80000 | 0x20) · +0x2D8 0x800000 → 숨김. */
	@Test
	void otherTraversalAndGrabStatesHide() {
		for (NavState s : List.of(NavState.LADDER, NavState.WALL_WALK, NavState.LEDGE_HANG, NavState.ROPE, NavState.ZIPLINE, NavState.SWIM,
				NavState.PUSH, NavState.INTERACT)) {
			assertEquals(HIDE, in(s), s.name());
		}
		assertEquals(HIDE, BerserkBladeRule.decide(true, BerserkBladeRule.stateBits(NavState.WALL_WALK, false, false, true, false), false, false),
				"등 대고 (0x200000)");
		assertEquals(HIDE, BerserkBladeRule.decide(true, BerserkBladeRule.stateBits(NavState.GROUND, true, false, false, true), false, false),
				"좁은 바닥 Narrow (0x80000)");
		assertEquals(HIDE, BerserkBladeRule.decide(true, bits(NavState.GROUND), true, false), "SCR_HideBerserkBlade");
		assertEquals(HIDE, BerserkBladeRule.decide(true, bits(NavState.GROUND), false, true), "+0x2D8 0x800000");
		// 물 0x100 · 0x200 도 0x380
		assertEquals(HIDE, BerserkBladeRule.decide(true, 0x200, false, false));
		assertEquals(HIDE, BerserkBladeRule.decide(true, 0x400 | 0x10000, false, false), "짧은 사슬 줄");
	}

	@Test
	void groundAndAirShow() {
		assertEquals(SHOW, in(NavState.GROUND));
		assertEquals(SHOW, in(NavState.AIR));
		assertEquals(SHOW, BerserkBladeRule.decide(true, BerserkBladeRule.stateBits(NavState.AIR, false, true, false, false), false, false));
		assertEquals(SHOW, BerserkBladeRule.decide(true, BerserkBladeRule.stateBits(null, true, false, false, false), false, false), "내비 없음");
	}

	/**
	 * 영상 5100–5102.5: 초록 상자 (CSM = 물체 붙잡기) 동안 등에 블레이드 → 끝나자 L1+R1 없이 손에 검. 영상 5104–5166: 검으로 뛰다 수평 줄 → 블레이드,
	 * 세로 줄 · 달리기 내내 블레이드 (장착 비트가 지워져 다시 보이지 않음).
	 */
	@Test
	void hiddenBladeComesBackButUnequippedBladeDoesNot() {
		boolean equipped = true;
		NavState[] chest = {NavState.GROUND, NavState.INTERACT, NavState.INTERACT, NavState.GROUND};
		BerserkBladeRule.Action[] want = {SHOW, HIDE, HIDE, SHOW};
		for (int i = 0; i < chest.length; i++) {
			BerserkBladeRule.Action a = BerserkBladeRule.decide(equipped, bits(chest[i]), false, false);
			assertEquals(want[i], a, "상자 " + i);
			equipped &= a != UNEQUIP;
		}
		assertTrue(equipped, "상자 뒤에도 장착");
		NavState[] rope = {NavState.GROUND, NavState.AIR, NavState.ROPE_TRAVERSE, NavState.AIR, NavState.ROPE, NavState.GROUND};
		BerserkBladeRule.Action[] want2 = {SHOW, SHOW, UNEQUIP, NONE, NONE, NONE};
		for (int i = 0; i < rope.length; i++) {
			BerserkBladeRule.Action a = BerserkBladeRule.decide(equipped, bits(rope[i]), false, false);
			assertEquals(want2[i], a, "줄 " + i);
			equipped &= a != UNEQUIP;
		}
		assertFalse(equipped, "수평 줄에서 해제된 뒤 지상에 내려와도 해제");
	}

	/** 32 문서 표 · 38 문서의 +0x174 비트. */
	@Test
	void stateBitsMatchOriginalFlags() {
		assertEquals(0x400 | 0x20000, bits(NavState.ROPE_TRAVERSE));
		assertEquals(0x400 | 0x40000, bits(NavState.ZIPLINE));
		assertEquals(0x400, bits(NavState.ROPE));
		assertEquals(0x20, bits(NavState.WALL_CLIMB));
		assertEquals(0x20 | 0x400000, bits(NavState.LADDER));
		assertEquals(0x20 | 0x100000, bits(NavState.WALL_WALK));
		assertEquals(0x20 | 0x200000, BerserkBladeRule.stateBits(NavState.WALL_WALK, false, false, true, false));
		assertEquals(0x10, bits(NavState.LEDGE_HANG));
		assertEquals(0x4000, bits(NavState.PUSH));
		assertEquals(0x4000, bits(NavState.INTERACT));
		assertEquals(0x1, bits(NavState.GROUND));
		assertEquals(0x1 | 0x80000, BerserkBladeRule.stateBits(NavState.GROUND, true, false, false, true));
		assertEquals(0x8, bits(NavState.AIR));
		assertEquals(0x4, BerserkBladeRule.stateBits(NavState.AIR, false, true, false, false));
		assertTrue((bits(NavState.SWIM) & 0x380) != 0);
	}

	private static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "R_HERO0.WAD")), "gow-rom 없음");
		return rom;
	}

	/**
	 * SCR_HideBerserkBlade (Hash127Upper 0x8CACF07D) 를 가진 영웅 기술 [확실: R_PERMK 데이터]: ○ 잡기 GRAB 200 · 처치 MAI* (해골 · 고르곤 MAIMEWGRAB 260 ·
	 * 미노타우로스 MAIMIN01 400 · 세이렌 · 케르베로스 …) · 마법 (포세이돈 506 · 하데스 508 · 제우스 BOLTPULLOUT 60 · 메두사 PSENTHEAD 81) · 석화
	 * FREEZEPOSE 120 — 모두 [0, 1] 창. 아르테미스의 검 기술 (BER* 555–590) · 줄 기술 (ROPEHOHA 51) 에는 없다.
	 */
	@Test
	void hideScriptOnGrabKillMagicMovesNotOnBerserkMoves() throws Exception {
		Path rom = rom();
		WadFile hero = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		CombatData data = new CombatData(perm, CombatData.heroFiles(), AnimationSet.load(hero, "ANM_hero"));
		TreeSet<Integer> with = new TreeSet<>();
		for (int id = 0; id < 1000; id++) {
			CombatFile.Move m = data.move(id);
			if (m == null) {
				continue;
			}
			for (CombatFile.Event e : m.events()) {
				if (e.type() == 8 && e.hash() == BerserkBladeRule.SCR_HIDE_BERSERK_BLADE) {
					with.add(id);
					assertEquals(0f, e.start(), 1e-3f, "창 시작 " + id);
					assertEquals(1f, e.end(), 1e-3f, "창 끝 " + id);
				}
			}
		}
		for (int id : new int[]{200, 260, 400, 506, 508, 60, 81, 120}) {
			assertTrue(with.contains(id), id + " 에 SCR_HideBerserkBlade: " + with);
		}
		assertTrue(with.subSet(555, 591).isEmpty(), "BER* 에는 없음: " + with.subSet(555, 591));
		assertFalse(with.contains(51), "ROPEHOHA");
	}

	/**
	 * 장착 0x19C240 의 내비 애니 뱅크 1 (TWK goHero Navigation "Bank 1"): 대기 berserkIdle, 걷기 · 달리기 berWalkBlend1/2, 점프 berJumpAir, 착지 berLand.
	 * 달리기 속도는 뱅크와 무관 (원작 0x1EAEC8 = Sold +0x178 × 스틱) — berWalkBlend2 의 zeroJoint (−3.2) 는 걷기 값이라 속도에 쓰지 않는다.
	 */
	@Test
	void berserkBankSwapsGroundLocomotionClips() throws Exception {
		Path rom = rom();
		WadFile hero = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(hero);
		Skeleton sk = Skeleton.loadAll(hero).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(hero, "ANM_hero");
		float[] ran = new float[2];
		for (boolean bank : new boolean[]{false, true}) {
			HeroController ctl = new HeroController(new Animator(sk, set, tw, "/Animation/goHero/"));
			ctl.berserkBank = bank;
			ScriptedInput in = new ScriptedInput(0, List.of(new ScriptedInput.Step(1.0f, 0.05f, PadButton.CROSS)));
			in.stick(new ScriptedInput.Stick(0.5f, 0.4f, false, 0, 1));
			float dt = 1 / 60f;
			String idle = null, run = null, jump = null, land = null;
			for (float t = 0; t < 2.5f; t += dt) {
				if (t > 0.45f && t < 0.95f) {
					ran[bank ? 1 : 0] = ctl.position.length();
				}
				in.advance(dt);
				if (in.pressed(PadButton.CROSS)) {
					ctl.jump(false);
					jump = ctl.animator().current().name;
				}
				ctl.update(in, 0, dt);
				// 게임 씬 물리 대신: 0.4 초 뒤 착지
				if (!ctl.grounded && t > 1.4f) {
					ctl.grounded = true;
				}
				String n = ctl.animator().current().name;
				if (t < 0.4f) {
					idle = n;
				} else if (t > 0.6f && t < 0.85f) {
					run = n;
				} else if (t > 1.42f && t < 1.45f) {
					land = n;
				}
			}
			assertEquals(bank ? "berserkIdle" : "standIdle", idle);
			assertEquals(bank ? "berWalkBlend2" : "walkBlend2", run);
			assertEquals(bank ? "berJumpAir" : "jumpUp", jump);
			assertEquals(bank ? "berLand" : "land", land);
		}
		assertTrue(ran[0] > 30f, "달리기 거리 " + ran[0]);
		assertEquals(ran[0], ran[1], 0.01f, "검을 들어도 같은 속도");
	}
}
