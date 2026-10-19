package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TimedPress (35 문서 §5): 사이클롭스(R_CY2) MAICYWMINI02(240) 버튼 프롬프트 4개 (템플릿 000 = 무작위 얼굴 버튼),
 * 고르곤 MAIMEWGRAB(260) 스틱 회전 2개 (060 = 180 무작위, 061 = 360 무작위).
 */
class MiniGameTest {

	private static final float DT = 1 / 60f;

	/** 스크립트 입력: 프롬프트를 보고 버튼/스틱을 고르는 가상 패드. */
	private static final class Bot extends Input {
		PadButton press;
		float lx, ly;

		Bot() {
			super(0);
		}

		@Override
		public void poll() {
		}

		@Override
		public boolean pressed(PadButton b) {
			return b == press;
		}

		@Override
		public boolean held(PadButton b) {
			return b == press;
		}

		@Override
		public float leftX() {
			return lx;
		}

		@Override
		public float leftY() {
			return ly;
		}
	}

	private record Run(GrabSync sync, List<Integer> moves) {
	}

	private interface Policy {
		void act(Bot bot, TimedPressQte q, int frameInPrompt);
	}

	private Run run(String wad, int move, Policy policy) throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve(wad)), "gow-rom 없음");
		WadFile hero = WadFile.load(rom.resolve("R_HERO0.WAD"));
		WadFile perm = WadFile.load(rom.resolve("R_PERMK.WAD"));
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
			public int bladeLevel() { return 1; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public int selectedMagic() { return 0; }
			public int classId() { return 0; }
			public Vector3f position() { return ctl.position; }
			public float yaw() { return ctl.yaw; }
		};
		CombatData cd = new CombatData(perm, CombatData.heroFiles(), set);
		Combatant c = new Combatant(cd, an, owner);
		EnemyFactory.Kind k = EnemyFactory.load(rom.resolve(wad), perm, -1);
		set.attachAll(k.assets().wad()); // 영웅 처형 애니 (ANM_MAICYW / MAIMEW)
		cd.reindexAnimations();
		EnemyFighter en = k.spawn();
		en.combat().setClipFallback(c::animationByHash);
		List<Integer> moves = new ArrayList<>();
		en.combat().onLog(s -> {
			if (s.startsWith("기술 ")) {
				moves.add(Integer.parseInt(s.split(" ")[1]));
			}
		});
		assertTrue(en.combat().forceMove(move), "기술 " + move);
		GrabSync sync = new GrabSync(ctl, c);
		Bot bot = new Bot();
		TimedPressQte last = null;
		int frame = 0;
		for (float t = 0; t < 12f && en.combat().active(); t += DT) {
			en.update(DT);
			TimedPressQte q = sync.timed();
			if (q != last) {
				frame = 0;
				last = q;
			}
			bot.press = null;
			bot.lx = bot.ly = 0;
			if (q != null) {
				policy.act(bot, q, frame++);
			}
			sync.updateTimed(en, k.tweaks(), bot);
		}
		return new Run(sync, moves);
	}

	@Test
	void cyclopsButtonsPass() throws Exception {
		Run r = run("R_CY2.WAD", 240, (bot, q, f) -> {
			if (f == 3) {
				bot.press = q.button();
			}
		});
		assertEquals(4, r.sync().timedPassed, "프롬프트 4개 성공: " + r.moves());
		assertEquals(0, r.sync().timedFailed);
		assertEquals(List.of(240), r.moves(), "실패 기술 없이 끝까지");
	}

	@Test
	void cyclopsTimeoutFails() throws Exception {
		Run r = run("R_CY2.WAD", 240, (bot, q, f) -> {
		});
		assertEquals(1, r.sync().timedFailed);
		assertEquals(List.of(240, 242), r.moves().subList(0, 2), "첫 창 만료 → MAICYWMINI02F1(242)");
	}

	@Test
	void cyclopsWrongButtonFailsImmediately() throws Exception {
		Run r = run("R_CY2.WAD", 240, (bot, q, f) -> {
			if (f == 2) {
				bot.press = q.button() == PadButton.CROSS ? PadButton.SQUARE : PadButton.CROSS;
			}
		});
		assertEquals(1, r.sync().timedFailed);
		assertEquals(242, r.moves().get(1));
	}

	@Test
	void gorgonStickRotationPasses() throws Exception {
		int[] kinds = new int[2];
		Run r = run("R_MEW.WAD", 260, (bot, q, f) -> {
			kinds[q.kind() >= 0xF4 ? 1 : 0] = q.kind();
			int[] seq = q.sequence();
			int step = Math.min(seq.length - 1, f / 3); // 3프레임마다 다음 사분면
			switch (seq[step]) {
				case 1 -> bot.ly = 1;
				case 2 -> bot.lx = 1;
				case 3 -> bot.ly = -1;
				default -> bot.lx = -1;
			}
		});
		assertEquals(2, r.sync().timedPassed, "180 + 360 회전: " + r.moves());
		assertTrue(kinds[0] >= 0xF0 && kinds[0] <= 0xF3, "060 = 180 무작위");
		assertTrue(kinds[1] == 0xF4 || kinds[1] == 0xF5, "061 = 360 무작위");
		assertFalse(r.moves().contains(261), "탈출(실패) 없음");
	}

	@Test
	void gorgonNoRotationEscapes() throws Exception {
		Run r = run("R_MEW.WAD", 260, (bot, q, f) -> {
		});
		assertTrue(r.moves().contains(261), "창 만료 → MAIMEWESCAPE(261): " + r.moves());
	}

	@Test
	void randomKinds() {
		Random rnd = new Random(1);
		for (int i = 0; i < 50; i++) {
			int b = TimedPressQte.resolve(0xFF, rnd);
			assertTrue(b >= 4 && b <= 7);
			int a = TimedPressQte.resolve(0xFD, rnd);
			assertTrue(a >= 0xF0 && a <= 0xF3);
		}
		assertEquals(1, TimedPressQte.quadrant(0, 1));
		assertEquals(0, TimedPressQte.quadrant(0.5f, 0.5f));
		assertEquals(4, TimedPressQte.quadrant(-0.9f, 0.2f));
	}
}
